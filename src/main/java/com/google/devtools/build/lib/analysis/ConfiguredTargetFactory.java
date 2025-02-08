// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.FailAction;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.RequiredFragmentsUtil;
import com.google.devtools.build.lib.analysis.configuredtargets.EnvironmentGroupConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.PackageGroupConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleConfiguredTargetUtil;
import com.google.devtools.build.lib.analysis.test.AnalysisFailure;
import com.google.devtools.build.lib.analysis.test.AnalysisFailureInfo;
import com.google.devtools.build.lib.analysis.test.AnalysisFailurePropagationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.PackageGroupsRuleVisibility;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetRecorder.MacroNamespaceViolationException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.memory.CurrentRuleTracker;
import com.google.devtools.build.lib.server.FailureDetails.FailAction.Code;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.IncrementalArtifactConflictFinder;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.Mutability;

/**
 * This class creates {@link ConfiguredTarget} instances using a given {@link
 * ConfiguredRuleClassProvider}.
 */
@ThreadSafe
public final class ConfiguredTargetFactory {

  // This class is not meant to be outside of the analysis phase machinery and is only public
  // in order to be accessible from the .view.skyframe package.

  private final ConfiguredRuleClassProvider ruleClassProvider;
  private final Supplier<IncrementalArtifactConflictFinder> conflictFinder;

  public ConfiguredTargetFactory(
      ConfiguredRuleClassProvider ruleClassProvider,
      Supplier<IncrementalArtifactConflictFinder> conflictFinder) {
    this.ruleClassProvider = ruleClassProvider;
    this.conflictFinder = conflictFinder;
  }

  /**
   * Returns the visibility of the given target, as represented by {@link
   * VisibilityProvider#getVisibility}.
   *
   * <p>This is constructed by starting with the value obtained from either {@link
   * Target#getVisibility} or {@link Target#getActualVisibility}, and recursively expanding package
   * groups. Errors during package group resolution are reported to the {@code AnalysisEnvironment}.
   */
  private static NestedSet<PackageGroupContents> convertVisibility(
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap,
      EventHandler reporter,
      Target target) {
    // Optimization: don't use actual visibility if not in a symbolic macro. See javadoc on
    // VisibilityProvider#getVisibility. Since the actual visibility only ever adds a ...:__pkg__
    // item, not a package group, it doesn't need to be handled here.
    RuleVisibility ruleVisibility =
        target.isCreatedInSymbolicMacro() ? target.getActualVisibility() : target.getVisibility();
    if (ruleVisibility.equals(RuleVisibility.PUBLIC)) {
      return VisibilityProvider.PUBLIC_VISIBILITY;
    }
    if (ruleVisibility.equals(RuleVisibility.PRIVATE)) {
      return VisibilityProvider.PRIVATE_VISIBILITY;
    }
    checkState(ruleVisibility instanceof PackageGroupsRuleVisibility, ruleVisibility);
    PackageGroupsRuleVisibility packageGroupsVisibility =
        (PackageGroupsRuleVisibility) ruleVisibility;

    NestedSetBuilder<PackageGroupContents> result = NestedSetBuilder.stableOrder();
    for (Label groupLabel : packageGroupsVisibility.getPackageGroups()) {
      // PackageGroupsConfiguredTargets are always in the package-group configuration.
      TransitiveInfoCollection group = findVisibilityPrerequisite(prerequisiteMap, groupLabel);
      PackageSpecificationProvider provider = null;
      // group == null can only happen if the package group list comes from a default_visibility
      // attribute, because in every other case, this missing link is caught during transitive
      // closure visitation or if the RuleConfiguredTargetGraph threw out a visibility edge because
      // if would have caused a cycle. The filtering should be done in a single place,
      // ConfiguredTargetGraph, but for now, this is the minimally invasive way of providing a sane
      // error message in case a cycle is created by a visibility attribute.
      if (group != null) {
        provider = group.get(PackageSpecificationProvider.PROVIDER);
      }
      if (provider != null) {
        result.addTransitive(provider.getPackageSpecifications());
      } else {
        reporter.handle(
            Event.error(
                target.getLocation(),
                String.format("Label '%s' does not refer to a package group", groupLabel)));
      }
    }

    result.add(packageGroupsVisibility.getDirectPackages());
    return result.build();
  }

  @Nullable
  private static TransitiveInfoCollection findVisibilityPrerequisite(
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap, Label label) {
    for (ConfiguredTargetAndData prerequisite :
        prerequisiteMap.get(DependencyKind.VISIBILITY_DEPENDENCY)) {
      if (prerequisite.getTargetLabel().equals(label) && prerequisite.getConfiguration() == null) {
        return prerequisite.getConfiguredTarget();
      }
    }
    return null;
  }

  /**
   * Invokes the appropriate constructor to create a {@link ConfiguredTarget} instance.
   *
   * <p>For use in {@code ConfiguredTargetFunction}.
   *
   * <p>Returns null if Skyframe deps are missing or upon certain errors.
   */
  @Nullable
  public ConfiguredTarget createConfiguredTarget(
      AnalysisEnvironment analysisEnvironment,
      ArtifactFactory artifactFactory,
      Target target,
      BuildConfigurationValue config,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      @Nullable NestedSet<Package> transitivePackages,
      ExecGroupCollection.Builder execGroupCollectionBuilder,
      @Nullable StarlarkAttributeTransitionProvider starlarkExecTransition)
      throws InterruptedException,
          ActionConflictException,
          InvalidExecGroupException,
          AnalysisFailurePropagationException {
    if (target instanceof Rule) {
      try {
        CurrentRuleTracker.beginConfiguredTarget(((Rule) target).getRuleClassObject());
        return createRule(
            analysisEnvironment,
            (Rule) target,
            config,
            configuredTargetKey,
            prerequisiteMap,
            configConditions,
            toolchainContexts,
            transitivePackages,
            execGroupCollectionBuilder,
            starlarkExecTransition);
      } finally {
        CurrentRuleTracker.endConfiguredTarget();
      }
    }

    // Enforce that targets whose names are outside their declaring macro's namespace cannot be
    // analyzed. (createRule() already enforces this above for rule targets, with optional error
    // interception through analysis_test.)
    try {
      target.getPackage().checkMacroNamespaceCompliance(target);
    } catch (MacroNamespaceViolationException e) {
      analysisEnvironment
          .getEventHandler()
          .handle(Event.error(target.getLocation(), e.getMessage()));
      return null;
    }

    // Visibility, like all package groups, doesn't have a configuration
    NestedSet<PackageGroupContents> visibility =
        convertVisibility(prerequisiteMap, analysisEnvironment.getEventHandler(), target);
    if (target instanceof OutputFile outputFile) {
      TargetContext targetContext =
          new TargetContext(
              analysisEnvironment,
              target,
              config,
              prerequisiteMap.get(DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY),
              visibility);
      if (analysisEnvironment.getSkyframeEnv().valuesMissing()) {
        return null;
      }
      Label ruleLabel = outputFile.getGeneratingRule().getLabel();
      RuleConfiguredTarget rule =
          (RuleConfiguredTarget)
              targetContext.findDirectPrerequisite(
                  ruleLabel,
                  // Don't pass a specific configuration, as we don't care what configuration the
                  // generating rule is in. There can only be one actual dependency here, which is
                  // the target that generated the output file.
                  Optional.empty());
      Verify.verifyNotNull(
          rule, "While analyzing %s, missing generating rule %s", outputFile, ruleLabel);
      // If analysis failures are allowed and the generating rule has failure info, just propagate
      // it. The output artifact won't exist, so we can't create an OutputFileConfiguredTarget.
      if (config.allowAnalysisFailures()
          && rule.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR.getKey()) != null) {
        return rule;
      }
      Artifact artifact = rule.findArtifactByOutputLabel(outputFile.getLabel());
      return new OutputFileConfiguredTarget(targetContext, artifact, rule);
    } else if (target instanceof InputFile inputFile) {
      TargetContext targetContext =
          new TargetContext(
              analysisEnvironment,
              target,
              config,
              prerequisiteMap.get(DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY),
              visibility);
      SourceArtifact artifact =
          artifactFactory.getSourceArtifact(
              inputFile.getExecPath(
                  analysisEnvironment
                      .getStarlarkSemantics()
                      .getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)),
              inputFile.getPackage().getSourceRoot().get(),
              ConfiguredTargetKey.builder()
                  .setLabel(target.getLabel())
                  .setConfiguration(config)
                  .build());
      return new InputFileConfiguredTarget(targetContext, artifact);
    } else if (target instanceof PackageGroup packageGroup) {
      TargetContext targetContext =
          new TargetContext(
              analysisEnvironment,
              target,
              config,
              prerequisiteMap.get(DependencyKind.VISIBILITY_DEPENDENCY),
              visibility);
      return new PackageGroupConfiguredTarget(configuredTargetKey, targetContext, packageGroup);
    } else if (target instanceof EnvironmentGroup) {
      return new EnvironmentGroupConfiguredTarget(configuredTargetKey);
    } else {
      throw new AssertionError("Unexpected target class: " + target.getClass().getName());
    }
  }

  /**
   * Factory method: constructs a RuleConfiguredTarget of the appropriate class, based on the rule
   * class. May return null if an error occurred.
   */
  @Nullable
  private ConfiguredTarget createRule(
      AnalysisEnvironment env,
      Rule rule,
      BuildConfigurationValue configuration,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      @Nullable NestedSet<Package> transitivePackages,
      ExecGroupCollection.Builder execGroupCollectionBuilder,
      @Nullable StarlarkAttributeTransitionProvider starlarkExecTransition)
      throws InterruptedException,
          ActionConflictException,
          InvalidExecGroupException,
          AnalysisFailurePropagationException {
    RuleClass ruleClass = rule.getRuleClassObject();
    ConfigurationFragmentPolicy configurationFragmentPolicy =
        ruleClass.getConfigurationFragmentPolicy();
    // Visibility computation and checking is done for every rule.
    RuleContext ruleContext =
        new RuleContext.Builder(env, rule, /* aspects= */ ImmutableList.of(), configuration)
            .setRuleClassProvider(ruleClassProvider)
            .setConfigurationFragmentPolicy(configurationFragmentPolicy)
            .setActionOwnerSymbol(configuredTargetKey)
            .setMutability(Mutability.create("configured target"))
            .setVisibility(convertVisibility(prerequisiteMap, env.getEventHandler(), rule))
            .setPrerequisites(removeToolchainDeps(prerequisiteMap))
            .setConfigConditions(configConditions)
            .setToolchainContexts(toolchainContexts)
            .setExecGroupCollectionBuilder(execGroupCollectionBuilder)
            .setRequiredConfigFragments(
                RequiredFragmentsUtil.getRuleRequiredFragmentsIfEnabled(
                    rule,
                    configuration,
                    ruleClassProvider.getFragmentRegistry().getUniversalFragments(),
                    configConditions,
                    Iterables.transform(
                        prerequisiteMap.values(), ConfiguredTargetAndData::getConfiguredTarget),
                    starlarkExecTransition))
            .setTransitivePackagesForRunfileRepoMappingManifest(transitivePackages)
            .setConflictFinder(conflictFinder)
            .build();

    ImmutableList<NestedSet<AnalysisFailure>> analysisFailures =
        depAnalysisFailures(ruleContext, ImmutableList.of());
    if (!analysisFailures.isEmpty()) {
      return erroredConfiguredTargetWithFailures(ruleContext, analysisFailures);
    }
    if (ruleContext.hasErrors()) {
      return erroredConfiguredTarget(ruleContext, null);
    }

    try {
      rule.getPackage().checkMacroNamespaceCompliance(rule);
    } catch (MacroNamespaceViolationException e) {
      ruleContext.ruleError(e.getMessage());
      return erroredConfiguredTarget(ruleContext, null);
    }

    try {
      Class<?> missingFragmentClass = null;
      for (Class<? extends Fragment> fragmentClass :
          configurationFragmentPolicy.getRequiredConfigurationFragments()) {
        if (!configuration.hasFragment(fragmentClass)) {
          MissingFragmentPolicy missingFragmentPolicy =
              configurationFragmentPolicy.getMissingFragmentPolicy(fragmentClass);
          if (missingFragmentPolicy != MissingFragmentPolicy.IGNORE) {
            if (missingFragmentPolicy == MissingFragmentPolicy.FAIL_ANALYSIS) {
              ruleContext.ruleError(
                  missingFragmentError(
                      ruleContext, configurationFragmentPolicy, configuration.checksum()));
              return null;
            }
            // Otherwise missingFragmentPolicy == MissingFragmentPolicy.CREATE_FAIL_ACTIONS:
            missingFragmentClass = fragmentClass;
          }
        }
      }
      if (missingFragmentClass != null) {
        return createFailConfiguredTargetForMissingFragmentClass(ruleContext, missingFragmentClass);
      }

      final ConfiguredTarget target;

      if (ruleClass.isStarlark()) {
        if (ruleClass.getRuleClassType().equals(RuleClass.Builder.RuleClassType.WORKSPACE)) {
          ruleContext.ruleError(
              "Found reference to a workspace rule in a context where a build"
                  + " rule was expected; probably a reference to a target in that external"
                  + " repository, properly specified as @reponame//path/to/package:target,"
                  + " should have been specified by the requesting rule.");
          return erroredConfiguredTarget(ruleContext, null);
        }

        final Object rawProviders;
        final boolean isDefaultExecutableCreated;
        @Nullable final RequiredConfigFragmentsProvider requiredConfigFragmentsProvider;
        try {
          // must be called before any calls to ruleContext.getStarlarkRuleContext()
          ruleContext.initStarlarkRuleContext();
          // TODO(bazel-team): maybe merge with RuleConfiguredTargetBuilder?
          rawProviders = StarlarkRuleConfiguredTargetUtil.evalRule(ruleContext, ruleClass);
          // TODO(b/268525292): isDefaultExecutableCreated is set to True when
          // ctx.outputs.executable
          // is accessed in the implementation. This fragile mechanism should be revised and removed
          isDefaultExecutableCreated =
              ruleContext.getStarlarkRuleContext().isDefaultExecutableCreated();
          requiredConfigFragmentsProvider = ruleContext.getRequiredConfigFragments();
        } finally {
          ruleContext.close();
        }
        if (rawProviders == null) {
          return erroredConfiguredTarget(ruleContext, requiredConfigFragmentsProvider);
        }
        // Because ruleContext was closed, rawProviders are now immutable
        // Postprocess providers to create the finished target.
        target =
            StarlarkRuleConfiguredTargetUtil.createTarget(
                ruleContext,
                rawProviders,
                ruleClass.getAdvertisedProviders(),
                isDefaultExecutableCreated,
                requiredConfigFragmentsProvider); // may be null
        return target != null
            ? target
            : erroredConfiguredTarget(ruleContext, requiredConfigFragmentsProvider);
      } else {
        try {
          target =
              Preconditions.checkNotNull(
                      ruleClass.getConfiguredTargetFactory(RuleConfiguredTargetFactory.class),
                      "No configured target factory for %s",
                      ruleClass)
                  .create(ruleContext);
          if (target != null) {
            // If a configured target is created, check that all advertised providers are returned.
            validateRuleAdvertisedProviders(
                ruleContext, target, ruleClass.getAdvertisedProviders());
          }
        } finally {
          // close() is required if the native rule created StarlarkRuleContext to perform any
          // Starlark evaluation, i.e. using the @_builtins mechanism.
          ruleContext.close();
        }
        // TODO(https://github.com/bazelbuild/bazel/issues/17915): genquery and similar native rules
        // may return null without setting a ruleContext error to signal a skyframe restart.
        return target != null ? target : erroredConfiguredTarget(ruleContext, null);
      }
    } catch (RuleErrorException ruleErrorException) {
      return erroredConfiguredTarget(ruleContext, null);
    }
  }

  /**
   * Checks that all the rule advertised providers are returned in the configured target and add
   * error to {@code ruleContext} if not.
   */
  private static void validateRuleAdvertisedProviders(
      RuleContext ruleContext,
      ConfiguredTarget configuredTarget,
      AdvertisedProviderSet advertisedProviders) {
    for (StarlarkProviderIdentifier providerId : advertisedProviders.getStarlarkProviders()) {
      if (configuredTarget.get(providerId) == null) {
        ruleContext.ruleError(
            String.format(
                "rule advertised the '%s' provider, but this provider was not among those"
                    + " returned",
                providerId));
      }
    }

    for (Class<?> klass : advertisedProviders.getBuiltinProviders()) {
      if (configuredTarget.getProvider(klass.asSubclass(TransitiveInfoProvider.class)) == null) {
        ruleContext.ruleError(
            String.format(
                "rule advertised the '%s' provider, but this provider was not among those"
                    + " returned",
                klass.getSimpleName()));
      }
    }
  }

  /**
   * If {@code --allow_analysis_failures} is true, returns a collection of propagated analysis
   * failures from the target's dependencies and {@code extraDeps} -- one NestedSet per dep with
   * failures to propagate. Otherwise if {@code --allow_analysis_failures} is false, returns the
   * empty set.
   */
  private static ImmutableList<NestedSet<AnalysisFailure>> depAnalysisFailures(
      RuleContext ruleContext, Iterable<? extends TransitiveInfoCollection> extraDeps) {
    if (ruleContext.getConfiguration().allowAnalysisFailures()) {
      ImmutableList.Builder<NestedSet<AnalysisFailure>> analysisFailures = ImmutableList.builder();
      Iterable<? extends TransitiveInfoCollection> infoCollections =
          Iterables.concat(ruleContext.getAllPrerequisites(), extraDeps);
      for (TransitiveInfoCollection infoCollection : infoCollections) {
        AnalysisFailureInfo failureInfo =
            infoCollection.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR);
        if (failureInfo != null) {
          analysisFailures.add(failureInfo.getCausesNestedSet());
        }
      }
      return analysisFailures.build();
    }
    // Analysis failures are only created and propagated if --allow_analysis_failures is
    // enabled, otherwise these result in actual rule errors which are not caught.
    return ImmutableList.of();
  }

  private static ConfiguredTarget erroredConfiguredTargetWithFailures(
      RuleContext ruleContext, List<NestedSet<AnalysisFailure>> analysisFailures)
      throws ActionConflictException, InterruptedException, AnalysisFailurePropagationException {
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
    builder.addNativeDeclaredProvider(AnalysisFailureInfo.forAnalysisFailureSets(analysisFailures));
    builder.addProvider(RunfilesProvider.class, RunfilesProvider.simple(Runfiles.EMPTY));
    ConfiguredTarget configuredTarget = builder.build();
    if (configuredTarget == null) {
      // A failure here is a failure in analysis failure testing machinery, not a "normal" analysis
      // failure that some outer analysis failure test may want to capture. Instead, this failure
      // means that the outer test would be unusable. So we throw an exception rather than returning
      // null and allowing it to propagate up in the usual way.
      throw new AnalysisFailurePropagationException(
          ruleContext.getLabel(), ruleContext.getSuppressedErrorMessages());
    }
    return configuredTarget;
  }

  /**
   * Returns a {@link ConfiguredTarget} which indicates that an analysis error occurred in
   * processing the target. In most cases, this returns null, which signals to callers that the
   * target failed to build and thus the build should fail. However, if analysis failures are
   * allowed in this build, this returns a stub {@link ConfiguredTarget} which contains information
   * about the failure.
   */
  // TODO(blaze-team): requiredConfigFragmentsProvider is used for Android feature flags and should
  // be removed together with them.
  @Nullable
  private static ConfiguredTarget erroredConfiguredTarget(
      RuleContext ruleContext, RequiredConfigFragmentsProvider requiredConfigFragmentsProvider)
      throws ActionConflictException, InterruptedException, AnalysisFailurePropagationException {
    if (ruleContext.getConfiguration().allowAnalysisFailures()) {
      ImmutableList.Builder<AnalysisFailure> analysisFailures = ImmutableList.builder();

      for (String errorMessage : ruleContext.getSuppressedErrorMessages()) {
        analysisFailures.add(AnalysisFailure.create(ruleContext.getLabel(), errorMessage));
      }
      RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
      builder.addNativeDeclaredProvider(
          AnalysisFailureInfo.forAnalysisFailures(analysisFailures.build()));
      builder.addProvider(RunfilesProvider.class, RunfilesProvider.simple(Runfiles.EMPTY));
      if (requiredConfigFragmentsProvider != null) {
        builder.addProvider(requiredConfigFragmentsProvider);
      }
      ConfiguredTarget configuredTarget = builder.build();
      if (configuredTarget == null) {
        // See comment in erroredConfiguredTargetWithFailures.
        throw new AnalysisFailurePropagationException(
            ruleContext.getLabel(), ruleContext.getSuppressedErrorMessages());
      }
      return configuredTarget;
    } else {
      // Returning a null ConfiguredTarget is an indication a rule error occurred. Exceptions are
      // not propagated, as this would show a nasty stack trace to users, and only provide info
      // on one specific failure with poor messaging. By returning null, the caller can
      // inspect ruleContext for multiple errors and output thorough messaging on each.
      return null;
    }
  }

  private static String missingFragmentError(
      RuleContext ruleContext,
      ConfigurationFragmentPolicy configurationFragmentPolicy,
      String configurationId) {
    RuleClass ruleClass = ruleContext.getRule().getRuleClassObject();
    Set<Class<?>> missingFragments = new LinkedHashSet<>();
    for (Class<? extends Fragment> fragment :
        configurationFragmentPolicy.getRequiredConfigurationFragments()) {
      if (!ruleContext.getConfiguration().hasFragment(fragment)) {
        missingFragments.add(fragment);
      }
    }
    checkState(!missingFragments.isEmpty());
    return "all rules of type "
        + ruleClass.getName()
        + " require the presence of all of ["
        + missingFragments.stream().map(Class::getSimpleName).collect(joining(","))
        + "], but these were all disabled in configuration "
        + configurationId;
  }

  @VisibleForTesting
  public static OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> removeToolchainDeps(
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> map) {
    OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> result =
        OrderedSetMultimap.create();

    for (Map.Entry<DependencyKind, ConfiguredTargetAndData> entry : map.entries()) {
      if (DependencyKind.isToolchain(entry.getKey())) {
        continue;
      }
      result.put(entry.getKey(), entry.getValue());
    }

    return result;
  }

  /**
   * Constructs a {@link ConfiguredAspect}. Returns null if an error occurs; in that case, {@code
   * aspectFactory} should call one of the error reporting methods of {@link RuleContext}.
   */
  public ConfiguredAspect createAspect(
      AnalysisEnvironment env,
      Target associatedTarget,
      ConfiguredTarget configuredTarget,
      ImmutableList<Aspect> aspectPath,
      ConfiguredAspectFactory aspectFactory,
      Aspect aspect,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      @Nullable
          ToolchainCollection<AspectBaseTargetResolvedToolchainContext> baseTargetToolchainContexts,
      @Nullable ExecGroupCollection.Builder execGroupCollectionBuilder,
      BuildConfigurationValue aspectConfiguration,
      @Nullable NestedSet<Package> transitivePackages,
      AspectKeyCreator.AspectKey aspectKey,
      StarlarkAttributeTransitionProvider starlarkExecTransition)
      throws InterruptedException,
          ActionConflictException,
          InvalidExecGroupException,
          RuleErrorException {
    RuleContext ruleContext =
        new RuleContext.Builder(env, associatedTarget, aspectPath, aspectConfiguration)
            .setRuleClassProvider(ruleClassProvider)
            .setConfigurationFragmentPolicy(aspect.getDefinition().getConfigurationFragmentPolicy())
            .setActionOwnerSymbol(aspectKey)
            .setMutability(Mutability.create("aspect"))
            .setVisibility(
                convertVisibility(prerequisiteMap, env.getEventHandler(), associatedTarget))
            .setPrerequisites(removeToolchainDeps(prerequisiteMap))
            .setConfigConditions(configConditions)
            .setToolchainContexts(toolchainContexts)
            .setBaseTargetToolchainContexts(baseTargetToolchainContexts)
            .setExecGroupCollectionBuilder(execGroupCollectionBuilder)
            .setExecProperties(ImmutableMap.of())
            .setRequiredConfigFragments(
                RequiredFragmentsUtil.getAspectRequiredFragmentsIfEnabled(
                    aspect,
                    aspectFactory,
                    associatedTarget.getAssociatedRule(),
                    aspectConfiguration,
                    ruleClassProvider.getFragmentRegistry().getUniversalFragments(),
                    configConditions,
                    Iterables.concat(
                        Iterables.transform(
                            prerequisiteMap.values(), ConfiguredTargetAndData::getConfiguredTarget),
                        ImmutableList.of(configuredTarget)),
                    starlarkExecTransition))
            .setTransitivePackagesForRunfileRepoMappingManifest(transitivePackages)
            .setConflictFinder(conflictFinder)
            .build();

    // If allowing analysis failures, targets should be created as normal as possible, and errors
    // will be propagated via a hook elsewhere as AnalysisFailureInfo.
    boolean allowAnalysisFailures = ruleContext.getConfiguration().allowAnalysisFailures();

    ImmutableList<NestedSet<AnalysisFailure>> analysisFailures =
        depAnalysisFailures(ruleContext, ImmutableList.of(configuredTarget));
    if (!analysisFailures.isEmpty()) {
      return erroredConfiguredAspectWithFailures(ruleContext, analysisFailures);
    }
    if (ruleContext.hasErrors() && !allowAnalysisFailures) {
      return erroredConfiguredAspect(ruleContext, null);
    }

    ConfiguredAspect configuredAspect =
        aspectFactory.create(
            associatedTarget.getLabel(),
            configuredTarget,
            ruleContext,
            aspect.getParameters(),
            ruleClassProvider.getToolsRepository());

    if (ruleContext.getConflictFinder() != null && configuredAspect != null) {
      for (ActionAnalysisMetadata action : configuredAspect.getActions()) {
        ruleContext.getConflictFinder().conflictCheckPerAction(action);
      }
    }
    if (configuredAspect == null) {
      return erroredConfiguredAspect(ruleContext, null);
    } else if (configuredAspect.get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR) != null) {
      // this was created by #erroredConfiguredAspect, return early to skip validating advertised
      // providers
      return configuredAspect;
    }

    validateAdvertisedProviders(
        configuredAspect,
        aspectKey,
        aspect.getDefinition().getAdvertisedProviders(),
        associatedTarget,
        env.getEventHandler());
    return configuredAspect;
  }

  private static ConfiguredAspect erroredConfiguredAspectWithFailures(
      RuleContext ruleContext, List<NestedSet<AnalysisFailure>> analysisFailures)
      throws ActionConflictException, InterruptedException {
    ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(ruleContext);
    builder.addNativeDeclaredProvider(AnalysisFailureInfo.forAnalysisFailureSets(analysisFailures));
    // Unlike erroredConfiguredTargetAspectWithFailures, we do not add a RunfilesProvider; that
    // would result in a RunfilesProvider being provided twice in the merged configured target.

    // TODO(b/242887801): builder.build() could potentially return null; in that case, should we
    // throw an exception, as erroredConfiguredTarget does, to avoid propagating the error to an
    // outer analysis failure test?
    return builder.build();
  }

  /**
   * Returns a {@link ConfiguredAspect} which indicates that an analysis error occurred in
   * processing the aspect. In most cases, this returns null, which signals to callers that the
   * target failed to build and thus the build should fail. However, if analysis failures are
   * allowed in this build, this returns a stub {@link ConfiguredAspect} which contains information
   * about the failure.
   */
  @Nullable
  public static ConfiguredAspect erroredConfiguredAspect(
      RuleContext ruleContext,
      @Nullable RequiredConfigFragmentsProvider requiredConfigFragmentsProvider)
      throws ActionConflictException, InterruptedException {
    if (ruleContext.getConfiguration().allowAnalysisFailures()) {
      ImmutableList.Builder<AnalysisFailure> analysisFailures = ImmutableList.builder();

      for (String errorMessage : ruleContext.getSuppressedErrorMessages()) {
        analysisFailures.add(AnalysisFailure.create(ruleContext.getLabel(), errorMessage));
      }
      ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(ruleContext);
      builder.addNativeDeclaredProvider(
          AnalysisFailureInfo.forAnalysisFailures(analysisFailures.build()));

      if (requiredConfigFragmentsProvider != null) {
        builder.addProvider(requiredConfigFragmentsProvider);
      }

      // Unlike erroredConfiguredTarget, we do not add a RunfilesProvider; that would result in a
      // RunfilesProvider being provided twice in the merged configured target.

      // TODO(b/242887801): builder.build() could potentially return null; in that case, should we
      // throw an exception, as erroredConfiguredTarget does, to avoid propagating the error to an
      // outer analysis failure test?
      return builder.build();
    } else {
      // Returning a null ConfiguredAspect is an indication a rule error occurred. Exceptions are
      // not propagated, as this would show a nasty stack trace to users, and only provide info
      // on one specific failure with poor messaging. By returning null, the caller can
      // inspect ruleContext for multiple errors and output thorough messaging on each.
      return null;
    }
  }

  private static void validateAdvertisedProviders(
      ConfiguredAspect configuredAspect,
      AspectKeyCreator.AspectKey aspectKey,
      AdvertisedProviderSet advertisedProviders,
      Target target,
      EventHandler eventHandler) {
    if (advertisedProviders.canHaveAnyProvider()) {
      return;
    }
    for (Class<?> aClass : advertisedProviders.getBuiltinProviders()) {
      if (configuredAspect.getProvider(aClass.asSubclass(TransitiveInfoProvider.class)) == null) {
        eventHandler.handle(
            Event.error(
                target.getLocation(),
                String.format(
                    "Aspect '%s', applied to '%s', does not provide advertised provider '%s'",
                    aspectKey.getAspectClass().getName(),
                    target.getLabel(),
                    aClass.getSimpleName())));
      }
    }

    for (StarlarkProviderIdentifier providerId : advertisedProviders.getStarlarkProviders()) {
      if (configuredAspect.get(providerId) == null) {
        eventHandler.handle(
            Event.error(
                target.getLocation(),
                String.format(
                    "Aspect '%s', applied to '%s', does not provide advertised provider '%s'",
                    aspectKey.getAspectClass().getName(), target.getLabel(), providerId)));
      }
    }
  }

  /**
   * A pseudo-implementation for configured targets that creates fail actions for all declared
   * outputs, both implicit and explicit, due to a missing fragment class.
   */
  private static ConfiguredTarget createFailConfiguredTargetForMissingFragmentClass(
      RuleContext ruleContext, Class<?> missingFragmentClass) throws InterruptedException {
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
    if (!ruleContext.getOutputArtifacts().isEmpty()) {
      ruleContext.registerAction(
          new FailAction(
              ruleContext.getActionOwner(),
              ruleContext.getOutputArtifacts(),
              "Missing fragment class: " + missingFragmentClass.getName(),
              Code.FRAGMENT_CLASS_MISSING));
    }
    builder.addProvider(RunfilesProvider.class, RunfilesProvider.simple(Runfiles.EMPTY));
    try {
      return builder.build();
    } catch (ActionConflictException e) {
      throw new IllegalStateException(
          "Can't have an action conflict with one action: " + ruleContext.getLabel(), e);
    }
  }
}
