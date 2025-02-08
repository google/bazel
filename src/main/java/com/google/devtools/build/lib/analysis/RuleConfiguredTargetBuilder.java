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

import static com.google.devtools.build.lib.analysis.ExtraActionUtils.createExtraActionProvider;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironments;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider.RemovedEnvironmentCulprit;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.test.AnalysisTestActionBuilder;
import com.google.devtools.build.lib.analysis.test.AnalysisTestResultInfo;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.test.TestActionBuilder;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.analysis.test.TestTagsProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.AllowlistChecker;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Builder class for analyzed rule instances.
 *
 * <p>This is used to tell Bazel which {@link TransitiveInfoProvider}s are produced by the analysis
 * of a configured target. For more information about analysis, see {@link
 * RuleConfiguredTargetFactory}.
 *
 * @see RuleConfiguredTargetFactory
 */
public final class RuleConfiguredTargetBuilder {
  private final RuleContext ruleContext;
  private final TransitiveInfoProviderMapBuilder providersBuilder =
      new TransitiveInfoProviderMapBuilder();
  private final TreeMap<String, NestedSetBuilder<Artifact>> outputGroupBuilders = new TreeMap<>();
  private final ImmutableList.Builder<Artifact> additionalTestActionTools =
      new ImmutableList.Builder<>();

  /** These are supported by all configured targets and need to be specially handled. */
  private NestedSet<Artifact> filesToBuild = NestedSetBuilder.emptySet(Order.STABLE_ORDER);

  private final NestedSetBuilder<Artifact> filesToRunBuilder = NestedSetBuilder.stableOrder();
  private RunfilesSupport runfilesSupport;
  private Artifact executable;
  private final ImmutableSet<ActionAnalysisMetadata> actionsWithoutExtraAction = ImmutableSet.of();

  public RuleConfiguredTargetBuilder(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    // Avoid building validations in analysis tests (b/143988346)
    addNativeDeclaredProvider(LicensesProviderImpl.of(ruleContext));
  }

  /**
   * Constructs the RuleConfiguredTarget instance based on the values set for this Builder. Returns
   * null if there were rule errors reported.
   */
  @Nullable
  public ConfiguredTarget build() throws ActionConflictException, InterruptedException {
    // If allowing analysis failures, the current target may not propagate all of the
    // expected providers; be lenient on such cases (for example, avoid precondition checks).
    boolean allowAnalysisFailures = ruleContext.getConfiguration().allowAnalysisFailures();

    if (ruleContext.getConfiguration().enforceConstraints()) {
      checkConstraints();
    }

    for (AllowlistChecker allowlistChecker :
        ruleContext.getRule().getRuleClassObject().getAllowlistCheckers()) {
      handleAllowlistChecker(allowlistChecker);
    }

    if (ruleContext.hasErrors() && !allowAnalysisFailures) {
      return null;
    }

    maybeAddRequiredConfigFragmentsProvider();

    NestedSet<Artifact> runfilesTrees =
        runfilesSupport != null
            ? NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesTreeArtifact())
            : NestedSetBuilder.emptySet(Order.STABLE_ORDER);

    FilesToRunProvider filesToRunProvider =
        FilesToRunProvider.create(
            buildFilesToRun(runfilesTrees, filesToBuild), runfilesSupport, executable);
    addProvider(FileProvider.of(filesToBuild));
    addProvider(filesToRunProvider);

    if (runfilesSupport != null) {
      // If a binary is built, build its runfiles, too
      addOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL, runfilesTrees);
    } else if (providersBuilder.contains(RunfilesProvider.class)) {
      // If we don't have a RunfilesSupport (probably because this is not a binary rule), we still
      // want to build the files this rule contributes to runfiles of dependent rules so that we
      // report an error if one of these is broken.
      //
      // Note that this is a best-effort thing: there is .getDataRunfiles() and all the language-
      // specific *RunfilesProvider classes, which we don't add here for reasons that are lost in
      // the mists of time.
      addOutputGroup(
          OutputGroupInfo.HIDDEN_TOP_LEVEL,
          providersBuilder
              .getProvider(RunfilesProvider.class)
              .getDefaultRunfiles()
              .getAllArtifacts());
    }

    if (propagateValidationActionOutputGroup()) {
      propagateTransitiveValidationOutputGroups();
    }

    // Add a default provider that forwards InstrumentedFilesInfo from dependencies, even if this
    // rule doesn't configure InstrumentedFilesInfo. This needs to be done for non-test rules
    // as well, but should be done before initializeTestProvider, which uses that.
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()
        && !providersBuilder.contains(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey())
        && !providersBuilder.contains(ToolchainInfo.PROVIDER.getKey())) {
      addNativeDeclaredProvider(InstrumentedFilesCollector.forwardAll(ruleContext));
    }
    // Create test action and artifacts if target was successfully initialized
    // and is a test. Also, as an extreme hack, only bother doing this if the TestConfiguration
    // is actually present.
    if (TargetUtils.isTestRule(ruleContext.getTarget())) {
      ImmutableList<String> testTags = ImmutableList.copyOf(ruleContext.getRule().getRuleTags());
      add(TestTagsProvider.class, new TestTagsProvider(testTags));
      if (ruleContext.getConfiguration().hasFragment(TestConfiguration.class)) {
        if (runfilesSupport != null) {
          add(TestProvider.class, initializeTestProvider(filesToRunProvider));
        } else {
          if (!allowAnalysisFailures) {
            throw new IllegalStateException("Test rules must have runfiles");
          }
        }
      }
    }

    // Only add {@link ExtraActionProvider} if extra action listeners are applied
    if (!ruleContext.getConfiguration().getActionListeners().isEmpty()) {
      ExtraActionArtifactsProvider extraActionsProvider =
          createExtraActionProvider(actionsWithoutExtraAction, ruleContext);
      add(ExtraActionArtifactsProvider.class, extraActionsProvider);
    }

    if (!outputGroupBuilders.isEmpty()) {
      addNativeDeclaredProvider(OutputGroupInfo.fromBuilders(outputGroupBuilders));
    }

    if (ruleContext.getConfiguration().evaluatingForAnalysisTest()) {
      if (ruleContext.getRule().isAnalysisTest()) {
        ruleContext.ruleError(
            String.format(
                "analysis_test rule '%s' cannot be transitively "
                    + "depended on by another analysis test rule",
                ruleContext.getLabel()));
        return null;
      }
      addProvider(new TransitiveLabelsInfo(transitiveLabels()));
    }

    if (ruleContext.getRule().hasAnalysisTestTransition()) {
      NestedSet<Label> labels = transitiveLabels();
      int depCount = labels.memoizedFlattenAndGetSize();
      if (depCount > ruleContext.getConfiguration().analysisTestingDepsLimit()) {
        ruleContext.ruleError(
            String.format(
                "analysis test rule exceeded maximum dependency edge count. "
                    + "Count: %s. Limit is %s. This limit is imposed on analysis test rules which "
                    + "use analysis_test_transition attribute transitions. Exceeding this limit "
                    + "indicates either the analysis_test has too many dependencies, or the "
                    + "underlying toolchains may be large. Try decreasing the number of test "
                    + "dependencies, (Analysis tests should not be very large!) or, if possible, "
                    + "try not using configuration transitions. If underlying toolchain size is "
                    + "to blame, it might be worth considering increasing "
                    + "--analysis_testing_deps_limit. (Beware, however, that large values of "
                    + "this flag can lead to no safeguards against giant "
                    + "test suites that can lead to Out Of Memory exceptions in the build server.)",
                depCount, ruleContext.getConfiguration().analysisTestingDepsLimit()));
        return null;
      }
    }

    if (ruleContext.getRule().isBuildSetting()) {
      BuildSetting buildSetting = ruleContext.getRule().getRuleClassObject().getBuildSetting();
      Object defaultValue =
          ruleContext
              .attributes()
              .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, buildSetting.getType());
      addProvider(
          BuildSettingProvider.class,
          new BuildSettingProvider(buildSetting, defaultValue, ruleContext.getLabel()));
    }
    TransitiveInfoProviderMap providers = providersBuilder.build();

    if (ruleContext.getRule().isAnalysisTest()) {
      // If the target is an analysis test that returned AnalysisTestResultInfo, register a
      // test pass/fail action on behalf of the target.
      AnalysisTestResultInfo testResultInfo =
          providers.get(AnalysisTestResultInfo.STARLARK_CONSTRUCTOR);

      if (testResultInfo == null) {
        ruleContext.ruleError(
            "rules with analysis_test=true must return an instance of AnalysisTestResultInfo");
        return null;
      }

      AnalysisTestActionBuilder.writeAnalysisTestAction(ruleContext, testResultInfo);
    }

    AnalysisEnvironment analysisEnvironment = ruleContext.getAnalysisEnvironment();
    ImmutableList<ActionAnalysisMetadata> actions = analysisEnvironment.getRegisteredActions();
    try {
      Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
          analysisEnvironment.getActionKeyContext(), actions, ruleContext.getOwner());
    } catch (Actions.ArtifactGeneratedByOtherRuleException e) {
      ruleContext.ruleError(e.getMessage());
      return null;
    }

    if (ruleContext.getConflictFinder() != null) {
      for (ActionAnalysisMetadata action : actions) {
        ruleContext.getConflictFinder().conflictCheckPerAction(action);
      }
    }
    return new RuleConfiguredTarget(ruleContext, providers, actions);
  }

  private boolean propagateValidationActionOutputGroup() {
    return !ruleContext.getRule().isAnalysisTest();
  }

  /** Actually process */
  private void handleAllowlistChecker(AllowlistChecker allowlistChecker) {
    if (allowlistChecker.attributeSetTrigger() != null
        && !ruleContext
            .getRule()
            .isAttributeValueExplicitlySpecified(allowlistChecker.attributeSetTrigger())) {
      return;
    }
    boolean passing =
        switch (allowlistChecker.locationCheck()) {
          case INSTANCE -> Allowlist.isAvailable(ruleContext, allowlistChecker.allowlistAttr());
          case DEFINITION ->
              Allowlist.isAvailableBasedOnRuleLocation(
                  ruleContext, allowlistChecker.allowlistAttr());
          case INSTANCE_OR_DEFINITION ->
              Allowlist.isAvailable(ruleContext, allowlistChecker.allowlistAttr())
                  || Allowlist.isAvailableBasedOnRuleLocation(
                      ruleContext, allowlistChecker.allowlistAttr());
        };
    if (!passing) {
      ruleContext.ruleError(allowlistChecker.errorMessage());
    }
  }

  /**
   * Adds {@link RequiredConfigFragmentsProvider} if {@link
   * CoreOptions#includeRequiredConfigFragmentsProvider} isn't {@link
   * CoreOptions.IncludeConfigFragmentsEnum#OFF} and if the provider is not already present.
   *
   * <p>For Stalark rules the provider is already added in {@link
   * com.google.devtools.build.lib.analysis.starlark.StarlarkRuleConfiguredTargetUtil}.
   *
   * <p>See {@link com.google.devtools.build.lib.analysis.config.RequiredFragmentsUtil} for a
   * description of the meaning of this provider's content. That class contains methods that
   * populate the results of {@link RuleContext#getRequiredConfigFragments}.
   */
  // TODO(blaze-team): Simplify the conditional logic and make it easier to understand.
  private void maybeAddRequiredConfigFragmentsProvider() {
    if (ruleContext.shouldIncludeRequiredConfigFragmentsProvider()
        && !providersBuilder.contains(RequiredConfigFragmentsProvider.class)) {
      addProvider(ruleContext.getRequiredConfigFragments());
    }
  }

  private NestedSet<Label> transitiveLabels() {
    NestedSetBuilder<Label> nestedSetBuilder = NestedSetBuilder.stableOrder();

    for (String attributeName : ruleContext.attributes().getAttributeNames()) {
      Type<?> attributeType =
          ruleContext.attributes().getAttributeDefinition(attributeName).getType();
      if (attributeType.getLabelClass() == LabelClass.DEPENDENCY) {
        for (TransitiveLabelsInfo labelsInfo :
            ruleContext.getPrerequisites(attributeName, TransitiveLabelsInfo.class)) {
          nestedSetBuilder.addTransitive(labelsInfo.getLabels());
        }
      }
    }
    nestedSetBuilder.add(ruleContext.getLabel());
    return nestedSetBuilder.build();
  }

  /**
   * Collects the validation action output groups from every dependency-type attribute of this
   * target and adds them to this target's output groups.
   *
   * <p>This is done within {@link RuleConfiguredTargetBuilder} so that every rule always and
   * automatically propagates the validation action output group.
   */
  private void propagateTransitiveValidationOutputGroups() {
    if (outputGroupBuilders.containsKey(OutputGroupInfo.VALIDATION_TRANSITIVE)) {
      Label rdeLabel =
          ruleContext.getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel();
      // only allow native and builtins to override transitive validation propagation
      if (rdeLabel != null
          && BuiltinRestriction.isNotAllowed(
              rdeLabel,
              RepositoryMapping.ALWAYS_FALLBACK,
              BuiltinRestriction.INTERNAL_STARLARK_API_ALLOWLIST)) {
        ruleContext.ruleError(rdeLabel + " cannot access the _transitive_validation private API");
        return;
      }
      addOutputGroup(
          OutputGroupInfo.VALIDATION,
          outputGroupBuilders.remove(OutputGroupInfo.VALIDATION_TRANSITIVE).build());
    } else {
      collectTransitiveValidationOutputGroups(
          ruleContext,
          unused -> true,
          validationArtifacts -> addOutputGroup(OutputGroupInfo.VALIDATION, validationArtifacts));
    }
  }

  /**
   * Collects the validation action output groups from every dependency-type attribute of the given
   * target that matches the given predicate and passes them to the given consumer.
   *
   * <p>This function can be used to implement custom validation action propagation logic that for
   * example ignores some attributes.
   */
  public static void collectTransitiveValidationOutputGroups(
      RuleContext ruleContext,
      Predicate<String> includeAttribute,
      Consumer<NestedSet<Artifact>> consumer) {
    for (String attributeName : ruleContext.attributes().getAttributeNames()) {
      if (!includeAttribute.test(attributeName)) {
        continue;
      }

      // Validation actions for tools, or from implicit deps should
      // not fail the overall build, since those dependencies should have their own builds
      // and tests that should surface any failing validations.
      Attribute attribute = ruleContext.attributes().getAttributeDefinition(attributeName);
      if (!attribute.skipValidations()
          && !attribute.isToolDependency()
          && !attribute.isImplicit()
          && attribute.getType().getLabelClass() == LabelClass.DEPENDENCY) {

        for (OutputGroupInfo outputGroup :
            ruleContext.getPrerequisites(attributeName, OutputGroupInfo.STARLARK_CONSTRUCTOR)) {

          NestedSet<Artifact> validationArtifacts =
              outputGroup.getOutputGroup(OutputGroupInfo.VALIDATION);

          if (!validationArtifacts.isEmpty()) {
            consumer.accept(validationArtifacts);
          }
        }
      }
    }
  }

  /**
   * Compute the artifacts to put into the {@link FilesToRunProvider} for this target. These are the
   * filesToBuild, any artifacts added by the rule with {@link #addFilesToRun}, and the runfiles
   * tree of the rule if it exists.
   */
  private NestedSet<Artifact> buildFilesToRun(
      NestedSet<Artifact> runfilesTrees, NestedSet<Artifact> filesToBuild) {
    filesToRunBuilder.addTransitive(filesToBuild);
    filesToRunBuilder.addTransitive(runfilesTrees);
    if (executable != null && ruleContext.getRule().getRuleClassObject().isStarlark()) {
      filesToRunBuilder.add(executable);
    }
    return filesToRunBuilder.build();
  }

  /**
   * Invokes Blaze's constraint enforcement system: checks that this rule's dependencies
   * support its environments and reports appropriate errors if violations are found. Also
   * publishes this rule's supported environments for the rules that depend on it.
   */
  private void checkConstraints() {
    if (!ruleContext.getRule().getRuleClassObject().supportsConstraintChecking()) {
      return;
    }
    ConstraintSemantics<RuleContext> constraintSemantics =
        ruleContext.getRuleClassProvider().getConstraintSemantics();
    EnvironmentCollection supportedEnvironments =
        constraintSemantics.getSupportedEnvironments(ruleContext);
    if (supportedEnvironments != null) {
      EnvironmentCollection.Builder refinedEnvironments = new EnvironmentCollection.Builder();
      Map<Label, RemovedEnvironmentCulprit> removedEnvironmentCulprits = new LinkedHashMap<>();
      constraintSemantics.checkConstraints(ruleContext, supportedEnvironments, refinedEnvironments,
          removedEnvironmentCulprits);
      add(
          SupportedEnvironmentsProvider.class,
          SupportedEnvironments.create(
              supportedEnvironments, refinedEnvironments.build(), removedEnvironmentCulprits));
    }
  }

  private TestProvider initializeTestProvider(FilesToRunProvider filesToRunProvider)
      throws InterruptedException {
    int explicitShardCount =
        ruleContext.attributes().get("shard_count", Type.INTEGER).toIntUnchecked();
    if (explicitShardCount < 0
        && ruleContext.getRule().isAttributeValueExplicitlySpecified("shard_count")) {
      ruleContext.attributeError("shard_count", "Must not be negative.");
    }
    if (explicitShardCount > 50) {
      ruleContext.attributeError("shard_count",
          "Having more than 50 shards is indicative of poor test organization. "
          + "Please reduce the number of shards.");
    }
    TestActionBuilder testActionBuilder =
        new TestActionBuilder(ruleContext)
            .setInstrumentedFiles(
                (InstrumentedFilesInfo)
                    providersBuilder.getProvider(
                        InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey()));

    RunEnvironmentInfo environmentProvider =
        (RunEnvironmentInfo) providersBuilder.getProvider(RunEnvironmentInfo.PROVIDER.getKey());
    if (environmentProvider != null) {
      testActionBuilder.addExtraEnv(environmentProvider.getEnvironment());
      testActionBuilder.addExtraInheritedEnv(environmentProvider.getInheritedEnvironment());
    }

    TestParams testParams =
        testActionBuilder
            .setFilesToRunProvider(filesToRunProvider)
            .addTools(additionalTestActionTools.build())
            .setExecutionRequirements(
                (ExecutionInfo) providersBuilder.getProvider(ExecutionInfo.PROVIDER.getKey()))
            .build();
    return new TestProvider(testParams);
  }

  /**
   * Add files required to run the target. Artifacts from {@link #setFilesToBuild} and the runfiles
   * tree, if any, are added automatically.
   */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addFilesToRun(NestedSet<Artifact> files) {
    filesToRunBuilder.addTransitive(files);
    return this;
  }

  /**
   * Add a specific provider with a given value.
   *
   * @deprecated use {@link #addProvider}
   */
  @Deprecated
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder add(Class<T> key, T value) {
    return addProvider(key, value);
  }

  /** Add a specific provider with a given value. */
  @CanIgnoreReturnValue
  public <T extends TransitiveInfoProvider> RuleConfiguredTargetBuilder addProvider(
      Class<? extends T> key, T value) {
    providersBuilder.put(key, value);
    return this;
  }

  /** Adds a specific provider. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addProvider(TransitiveInfoProvider provider) {
    providersBuilder.add(provider);
    return this;
  }

  /** Add a collection of specific providers. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addProviders(TransitiveInfoProviderMap providers) {
    providersBuilder.addAll(providers);
    return this;
  }
  /**
   * Adds a "declared provider" defined in Starlark to the rule. Use this method for declared
   * providers defined in Starlark. The provider symbol must be exported.
   *
   * <p>Has special handling for {@link OutputGroupInfo}: that provider is not added from Starlark
   * directly, instead its output groups are added.
   *
   * <p>Use {@link #addNativeDeclaredProvider(Info)} in definitions of native rules.
   */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addStarlarkDeclaredProvider(Info provider) {
    Provider constructor = provider.getProvider();
    // Starlark providers are already exported (enforced by SRCTU.getProviderKey).
    Preconditions.checkArgument(constructor.isExported());
    if (OutputGroupInfo.STARLARK_CONSTRUCTOR.getKey().equals(constructor.getKey())) {
      OutputGroupInfo outputGroupInfo = (OutputGroupInfo) provider;
      for (String outputGroup : outputGroupInfo) {
        addOutputGroup(outputGroup, outputGroupInfo.getOutputGroup(outputGroup));
      }
    } else {
      providersBuilder.put(provider);
    }
    return this;
  }

  /**
   * Adds "declared providers" defined in native code to the rule. Use this method for declared
   * providers in definitions of native rules.
   *
   * <p>Use {@link #addStarlarkDeclaredProvider(Info)} for Starlark rule implementations.
   */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addNativeDeclaredProviders(Iterable<Info> providers) {
    for (Info provider : providers) {
      addNativeDeclaredProvider(provider);
    }
    return this;
  }

  /**
   * Adds a "declared provider" defined in native code to the rule. Use this method for declared
   * providers in definitions of native rules.
   *
   * <p>Use {@link #addStarlarkDeclaredProvider(Info)} for Starlark rule implementations.
   */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addNativeDeclaredProvider(Info provider) {
    Provider constructor = provider.getProvider();
    Preconditions.checkState(constructor.isExported());
    providersBuilder.put(provider);
    return this;
  }

  /**
   * Returns true if a provider matching the given provider key has already been added to the
   * configured target builder.
   */
  public boolean containsProviderKey(Provider.Key providerKey) {
    return providersBuilder.contains(providerKey);
  }

  /**
   * Returns true if a provider matching the given legacy key has already been added to the
   * configured target builder.
   */
  public boolean containsLegacyKey(String legacyId) {
    return providersBuilder.contains(legacyId);
  }

  /** Add a Starlark transitive info. The provider value must be safe. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addStarlarkTransitiveInfo(String name, Object value) {
    providersBuilder.put(name, value);
    return this;
  }

  /** Set the runfiles support for executable targets. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder setRunfilesSupport(
      RunfilesSupport runfilesSupport, Artifact executable) {
    this.runfilesSupport = runfilesSupport;
    this.executable = executable;
    return this;
  }

  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addTestActionTools(List<Artifact> tools) {
    this.additionalTestActionTools.addAll(tools);
    return this;
  }

  /** Set the files to build. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder setFilesToBuild(NestedSet<Artifact> filesToBuild) {
    this.filesToBuild = filesToBuild;
    return this;
  }

  private NestedSetBuilder<Artifact> getOutputGroupBuilder(String name) {
    NestedSetBuilder<Artifact> result = outputGroupBuilders.get(name);
    if (result != null) {
      return result;
    }

    result = NestedSetBuilder.stableOrder();
    outputGroupBuilders.put(name, result);
    return result;
  }

  /** Adds a set of files to an output group. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addOutputGroup(String name, NestedSet<Artifact> artifacts) {
    getOutputGroupBuilder(name).addTransitive(artifacts);
    return this;
  }

  /** Adds a file to an output group. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addOutputGroup(String name, Artifact artifact) {
    getOutputGroupBuilder(name).add(artifact);
    return this;
  }

  /** Adds multiple output groups. */
  @CanIgnoreReturnValue
  public RuleConfiguredTargetBuilder addOutputGroups(Map<String, NestedSet<Artifact>> groups) {
    for (Map.Entry<String, NestedSet<Artifact>> group : groups.entrySet()) {
      getOutputGroupBuilder(group.getKey()).addTransitive(group.getValue());
    }

    return this;
  }

  /**
   * Contains a nested set of transitive dependencies of the target which propagated this object.
   *
   * <p>This is automatically provided by all targets which are being evaluated in analysis testing.
   *
   * <p>For large builds, this object will become <i>very large</i>, but analysis tests are required
   * to be very small. The small-size of analysis tests are enforced by evaluating the size of this
   * object.
   */
  private static final class TransitiveLabelsInfo implements TransitiveInfoProvider {
    private final NestedSet<Label> labels;

    TransitiveLabelsInfo(NestedSet<Label> labels) {
      this.labels = labels;
    }

    public NestedSet<Label> getLabels() {
      return labels;
    }
  }
}
