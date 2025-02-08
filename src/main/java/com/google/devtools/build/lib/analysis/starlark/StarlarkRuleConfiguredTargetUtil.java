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
package com.google.devtools.build.lib.analysis.starlark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ActionsProvider;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DefaultInfo;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunEnvironmentInfo;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.StarlarkProviderValidationUtil;
import com.google.devtools.build.lib.analysis.test.CoverageCommon;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.FormatMethod;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/**
 * A helper class to build Rule Configured Targets via runtime loaded rule implementations defined
 * using the Starlark Build Extension Language.
 */
public final class StarlarkRuleConfiguredTargetUtil {

  private StarlarkRuleConfiguredTargetUtil() {}

  private static final ImmutableSet<String> DEFAULT_PROVIDER_FIELDS =
      ImmutableSet.of("files", "runfiles", "data_runfiles", "default_runfiles", "executable");

  /**
   * Evaluates the rule's implementation function and returns what it returns (raw providers).
   *
   * <p>If there were errors during the evaluation or the type of the returned object is obviously
   * wrong, it sets ruleErrors on the ruleContext and returns null.
   *
   * <p>Unchecked exception {@code UncheckedEvalException}s and {@code MissingDepException} may be
   * thrown.
   *
   * @param ruleContext the rule context
   * @param ruleClass the rule class for which to evaluate the implementation function gets. This
   *     serves extended rules, where for parent's implementation function needs to be evaluated.
   */
  // TODO(blaze-team): Legacy providers are preventing to change the return type to Sequence<Info>.
  @Nullable
  public static Object evalRule(RuleContext ruleContext, RuleClass ruleClass)
      throws InterruptedException {
    // TODO(blaze-team): expect_failure attribute is special for all rule classes, but it should
    // be special only for analysis tests
    String expectFailure = ruleContext.attributes().get("expect_failure", Type.STRING);
    Object providersRaw;

    try {
      // call rule.implementation(ctx)
      providersRaw =
          Starlark.positionalOnlyCall(
              ruleContext.getStarlarkThread(),
              ruleClass.getConfiguredTargetFunction(),
              ruleContext.getStarlarkRuleContext());

    } catch (Starlark.UncheckedEvalException ex) {
      // MissingDepException is expected to transit through Starlark execution.
      throw ex.getCause() instanceof CachingAnalysisEnvironment.MissingDepException
          ? (CachingAnalysisEnvironment.MissingDepException) ex.getCause()
          : ex;

    } catch (EvalException ex) {
      // An error occurred during the rule.implementation call

      // If the error was expected by an analysis test, return None, to produce an empty target.
      if (!expectFailure.isEmpty() && ex.getMessage().matches(expectFailure)) {
        return Starlark.NONE;
      }

      // Emit a single event that spans multiple lines:
      //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
      //     Traceback:
      //        File foo.bzl, line 1, in foo_library_impl:
      //        ...
      ruleContext.ruleError("\n" + ex.getMessageWithStack());
      return null;
    }

    // Errors already reported?
    if (ruleContext.hasErrors()) {
      return null;
    }

    // Wrong result type?
    if (!(providersRaw instanceof Info
        || providersRaw == Starlark.NONE
        || providersRaw instanceof Iterable)) {
      ruleContext.ruleError(
          String.format(
              "Rule should return a struct or a list, but got %s", Starlark.type(providersRaw)));
      return null;
    }

    // Did the Starlark implementation function fail to fail as expected?
    if (!expectFailure.isEmpty()) {
      ruleContext.ruleError("Expected failure not found: " + expectFailure);
      return null;
    }

    return providersRaw;
  }

  private static void checkDeclaredProviders(
      ConfiguredTarget configuredTarget, AdvertisedProviderSet advertisedProviders)
      throws EvalException {
    for (StarlarkProviderIdentifier providerId : advertisedProviders.getStarlarkProviders()) {
      if (configuredTarget.get(providerId) == null) {
        throw Starlark.errorf(
            "rule advertised the '%s' provider, but this provider was not among those returned",
            providerId);
      }
    }
  }

  /**
   * Creates a Rule Configured Target from the raw providers returned by the rule's implementation
   * function.
   *
   * <p>If there are problems with the raw providers, it sets ruleErrors on the ruleContext and
   * returns null.
   */
  @Nullable
  public static ConfiguredTarget createTarget(
      RuleContext context,
      Object rawProviders,
      AdvertisedProviderSet advertisedProviders,
      boolean isDefaultExecutableCreated,
      @Nullable RequiredConfigFragmentsProvider requiredConfigFragmentsProvider)
      throws InterruptedException, ActionConflictException {
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(context);

    // Location of rule.implementation function.
    Location implLoc =
        context.getRule().getRuleClassObject().getConfiguredTargetFunction().getLocation();

    // TODO(adonovan): clean up addProviders' error handling,
    // reporting provider validity errors through ruleError
    // where possible. This allows for multiple events, with independent
    // locations, even for the same root cause.
    // The required change is fiddly due to frequent and nested use of
    // Structure.getField, Sequence.cast, and similar operators.
    try {
      addProviders(context, builder, rawProviders, implLoc, isDefaultExecutableCreated);
    } catch (EvalException ex) {
      // Emit a single event that spans two lines (see infoError).
      // The message typically starts with another location, e.g. of provider creation.
      //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
      //     ...message...
      context.ruleError("\n" + ex.getMessage());
      return null;
    }

    // This provider is kept out of `addProviders` method, because it's not generated by the
    // Starlark rule and because `addProviders` will be simplified by the legacy providers removal
    // RequiredConfigFragmentsProvider may be removed with removal of Android feature flags.
    if (requiredConfigFragmentsProvider != null) {
      builder.addProvider(requiredConfigFragmentsProvider);
    }

    ConfiguredTarget ct;
    try {
      // This also throws InterruptedException from a convoluted dependency:
      // TestActionBuilder -> TestTargetExecutionSettings -> CommandLine -> Starlark.
      ct = builder.build(); // may be null
    } catch (IllegalArgumentException ex) {
      // TODO(adonovan): eliminate this abuse of unchecked exceptions.
      // Emit a single event that spans two lines (see infoError).
      // The message typically starts with another location, e.g. of provider creation.
      //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
      //     ...message...
      context.ruleError("\n" + implLoc + ": " + ex.getMessage());
      return null;
    }

    if (ct != null) {
      // If there was error creating the ConfiguredTarget, no further validation is needed.
      // Null will be returned and the errors thus reported.
      try {
        // Check all artifacts have actions. Despite signature, must be done after build().
        StarlarkProviderValidationUtil.validateArtifacts(context);
        // Check all advertised providers were created.
        checkDeclaredProviders(ct, advertisedProviders);
      } catch (EvalException ex) {
        context.ruleError("\n" + implLoc + ": " + ex.getMessage());
        return null;
      }
    }

    return ct;
  }

  private static void addOutputGroups(Object outputGroups, RuleConfiguredTargetBuilder builder)
      throws EvalException {
    for (Map.Entry<String, StarlarkValue> entry :
        Dict.cast(outputGroups, String.class, StarlarkValue.class, "output_groups").entrySet()) {
      String outputGroup = entry.getKey();
      NestedSet<Artifact> artifacts = convertToOutputGroupValue(outputGroup, entry.getValue());
      builder.addOutputGroup(outputGroup, artifacts);
    }
  }

  private static void addInstrumentedFiles(
      StructImpl insStruct, RuleContext ruleContext, RuleConfiguredTargetBuilder builder)
      throws EvalException {
    List<String> extensions = null;
    if (insStruct.getFieldNames().contains("extensions")) {
      extensions = Sequence.cast(insStruct.getValue("extensions"), String.class, "extensions");
    }

    List<String> dependencyAttributes = Collections.emptyList();
    if (insStruct.getFieldNames().contains("dependency_attributes")) {
      dependencyAttributes =
          Sequence.cast(
              insStruct.getValue("dependency_attributes"), String.class, "dependency_attributes");
    }

    List<String> sourceAttributes = Collections.emptyList();
    if (insStruct.getFieldNames().contains("source_attributes")) {
      sourceAttributes =
          Sequence.cast(insStruct.getValue("source_attributes"), String.class, "source_attributes");
    }

    InstrumentedFilesInfo instrumentedFilesProvider =
        CoverageCommon.createInstrumentedFilesInfo(
            ruleContext, sourceAttributes, dependencyAttributes, extensions);
    builder.addNativeDeclaredProvider(instrumentedFilesProvider);
  }

  public static NestedSet<Artifact> convertToOutputGroupValue(String outputGroup, Object objects)
      throws EvalException {
    // regrettable preemptive allocation of error message
    String what = "output group '" + outputGroup + "'";
    return objects instanceof Sequence
        ? NestedSetBuilder.<Artifact>stableOrder()
            .addAll(Sequence.cast(objects, Artifact.class, what))
            .build()
        : Depset.cast(objects, Artifact.class, what);
  }

  private static void addProviders(
      RuleContext context,
      RuleConfiguredTargetBuilder builder,
      Object rawProviders,
      Location implLoc,
      boolean isDefaultExecutableCreated)
      throws EvalException, InterruptedException {

    StructImpl oldStyleProviders =
        StarlarkInfo.create(StructProvider.STRUCT, ImmutableMap.of(), implLoc);
    Map<Provider.Key, Info> declaredProviders = new LinkedHashMap<>();

    if (rawProviders instanceof Info info) {
      // Either an old-style struct or a single declared provider (not in a list)
      if (getProviderKey(info).equals(StructProvider.STRUCT.getKey())) {
        if (context
            .getAnalysisEnvironment()
            .getStarlarkSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_STRUCT_PROVIDER_SYNTAX)) {
          throw infoError(
              info,
              "Returning a struct from a rule implementation function is deprecated and will "
                  + "be removed soon. It may be temporarily re-enabled by setting "
                  + "--incompatible_disallow_struct_provider_syntax=false . See "
                  + "https://github.com/bazelbuild/bazel/issues/7347 for details.");
        }

        // Old-style struct, but it may contain declared providers
        StructImpl struct = (StructImpl) rawProviders;
        oldStyleProviders = struct;

        Object providersField = struct.getValue("providers");
        if (providersField != null) {
          for (Info provider : Sequence.cast(providersField, Info.class, "providers")) {
            Provider.Key providerKey = getProviderKey(provider);
            if (declaredProviders.put(providerKey, provider) != null) {
              context.ruleError("Multiple conflicting returned providers with key " + providerKey);
            }
          }
        }
      } else {
        if (info instanceof StarlarkInfo starlarkInfo) {
          info = starlarkInfo.unsafeOptimizeMemoryLayout();
        }
        Provider.Key providerKey = getProviderKey(info);
        // Single declared provider
        declaredProviders.put(providerKey, info);
      }
    } else if (rawProviders instanceof Sequence) {
      // Sequence of declared providers
      for (Info provider :
          Sequence.cast(rawProviders, Info.class, "result of rule implementation function")) {
        if (provider instanceof StarlarkInfo) {
          // Provider instances are optimised recursively, without optimising elements of the list.
          // Tradeoff is that some object may be duplicated if they are reachable by more than one
          // path, but we don't expect that much in practice.
          provider = ((StarlarkInfo) provider).unsafeOptimizeMemoryLayout();
        }
        Provider.Key providerKey = getProviderKey(provider);
        if (declaredProviders.put(providerKey, provider) != null) {
          context.ruleError("Multiple conflicting returned providers with key " + providerKey);
        }
      }
    }

    boolean defaultProviderProvidedExplicitly = false;

    for (Info declaredProvider : declaredProviders.values()) {
      if (getProviderKey(declaredProvider).equals(DefaultInfo.PROVIDER.getKey())) {
        parseDefaultProviderFields(
            (DefaultInfo) declaredProvider, context, builder, isDefaultExecutableCreated);
        defaultProviderProvidedExplicitly = true;
      } else if (getProviderKey(declaredProvider).equals(RunEnvironmentInfo.PROVIDER.getKey())
          && !(context.getRule().getRuleClassObject().isExecutableStarlark()
              || context.isTestTarget())) {
        String message =
            "Returning RunEnvironmentInfo from a non-executable, non-test target has no effect";
        RunEnvironmentInfo runEnvironmentInfo = (RunEnvironmentInfo) declaredProvider;
        if (runEnvironmentInfo.shouldErrorOnNonExecutableRule()) {
          context.ruleError(message);
        } else {
          context.ruleWarning(message);
          builder.addStarlarkDeclaredProvider(declaredProvider);
        }
      } else {
        builder.addStarlarkDeclaredProvider(declaredProvider);
      }
    }

    if (!defaultProviderProvidedExplicitly) {
      parseDefaultProviderFields(oldStyleProviders, context, builder, isDefaultExecutableCreated);
    }

    for (String field : oldStyleProviders.getFieldNames()) {
      if (DEFAULT_PROVIDER_FIELDS.contains(field)) {
        // These fields have already been parsed above.
        // If a default provider has been provided explicitly then it's an error that they also
        // occur here.
        if (defaultProviderProvidedExplicitly) {
          throw infoError(
              oldStyleProviders,
              "Provider '%s' should be specified in DefaultInfo if it's provided explicitly.",
              field);
        }
      } else if (field.equals("output_groups")) {
        addOutputGroups(oldStyleProviders.getValue(field), builder);
      } else if (field.equals("instrumented_files")) {
        addInstrumentedFiles(
            oldStyleProviders.getValue("instrumented_files", StructImpl.class), context, builder);
      } else if (!field.equals("providers")) { // "providers" already handled above.
        addProviderFromLegacySyntax(
            builder, oldStyleProviders, field, oldStyleProviders.getValue(field));
      }
    }
  }

  // Returns an EvalException whose message has the info's creation location as a prefix.
  // The exception is intended to be reported as ruleErrors by createTarget.
  @FormatMethod
  private static EvalException infoError(Info info, String format, Object... args) {
    return Starlark.errorf("%s: %s", info.getCreationLocation(), String.format(format, args));
  }

  @SuppressWarnings("deprecation") // For legacy migrations
  private static void addProviderFromLegacySyntax(
      RuleConfiguredTargetBuilder builder,
      StructImpl oldStyleProviders,
      String fieldName,
      Object value)
      throws EvalException {
    builder.addStarlarkTransitiveInfo(fieldName, value);

    if (value instanceof Info info) {

      // To facilitate migration off legacy provider syntax, implicitly set the modern provider key
      // and the canonical legacy provider key if applicable.
      if (shouldAddWithModernKey(builder, oldStyleProviders, fieldName, info)) {
        builder.addNativeDeclaredProvider(info);
      }

      if (info.getProvider()
          instanceof BuiltinProvider.WithLegacyStarlarkName providerWithLegacyName) {
        if (shouldAddWithLegacyKey(oldStyleProviders, providerWithLegacyName)) {
          builder.addStarlarkTransitiveInfo(providerWithLegacyName.getStarlarkName(), info);
        }
      }
    }
  }

  @SuppressWarnings("deprecation") // For legacy migrations
  private static boolean shouldAddWithModernKey(
      RuleConfiguredTargetBuilder builder,
      StructImpl oldStyleProviders,
      String fieldName,
      Info info)
      throws EvalException {
    // If the modern key is already set, do nothing.
    if (builder.containsProviderKey(info.getProvider().getKey())) {
      return false;
    }
    if (info.getProvider() instanceof BuiltinProvider.WithLegacyStarlarkName) {
      String canonicalLegacyKey =
          ((BuiltinProvider.WithLegacyStarlarkName) info.getProvider()).getStarlarkName();
      // Add info using its modern key if it was specified using its canonical legacy key, or
      // if no provider was used using that canonical legacy key.
      return fieldName.equals(canonicalLegacyKey)
          || oldStyleProviders.getValue(canonicalLegacyKey) == null;
    } else {
      return true;
    }
  }

  @SuppressWarnings("deprecation") // For legacy migrations
  private static boolean shouldAddWithLegacyKey(
      StructImpl oldStyleProviders, BuiltinProvider.WithLegacyStarlarkName provider)
      throws EvalException {
    String canonicalLegacyKey = provider.getStarlarkName();
    // Add info using its canonical legacy key if no provider was specified using that canonical
    // legacy key.
    return oldStyleProviders.getValue(canonicalLegacyKey) == null;
  }

  /**
   * Returns the provider key from an info (provider instance).
   *
   * @throws EvalException if the provider for this info object has not been exported, which can
   *     occur if the provider was declared in a non-global scope (for example a rule implementation
   *     function)
   */
  private static Provider.Key getProviderKey(Info info) throws EvalException {
    Provider provider = info.getProvider();
    if (!provider.isExported()) {
      // TODO(adonovan): report separate error events at distinct locations:
      //  "cannot return non-exported provider" (at location of instantiation), and
      //  "provider definition not at top level" (at location of definition).
      throw infoError(
          info,
          "The rule implementation function returned an instance of an unnamed provider. "
              + "A provider becomes named by being assigned to a global variable in a .bzl file. "
              + "(Provider defined at %s.)",
          provider.getLocation());
    }
    return provider.getKey();
  }

  /**
   * Parses fields of (not necessarily a default) provider. If it is an actual default provider,
   * throws an {@link EvalException} if there are unknown fields.
   */
  // TODO(brandjon): Consider refactoring this method by splitting it into two versions for the
  // case of DefaultInfo and the case of a legacy struct. They'd differ in how they parse the
  // fields, then dispatch to a common validation helper and finally the common
  // addSimpleProviders call. Also rename this method to make clear that it mutates the builder.
  // If lagacy struct providers are removed first, this is moot.
  private static void parseDefaultProviderFields(
      StructImpl info,
      RuleContext context,
      RuleConfiguredTargetBuilder builder,
      boolean isDefaultExecutableCreated)
      throws EvalException, InterruptedException {
    Depset files = null;
    Runfiles statelessRunfiles = null;
    Runfiles dataRunfiles = null;
    Runfiles defaultRunfiles = null;
    Artifact executable = null;

    if (getProviderKey(info).equals(DefaultInfo.PROVIDER.getKey())) {
      DefaultInfo defaultInfo = (DefaultInfo) info;

      files = defaultInfo.getFiles();
      statelessRunfiles = defaultInfo.getStatelessRunfiles();
      dataRunfiles = defaultInfo.getDataRunfiles();
      defaultRunfiles = defaultInfo.getDefaultRunfiles();
      executable = defaultInfo.getExecutable();

    } else {
      // Rule implementations aren't required to return default-info fields via a DefaultInfo
      // provider. They can return them as fields on the returned struct. For example,
      // 'return struct(executable = foo)' instead of 'return DefaultInfo(executable = foo)'.
      // TODO(cparsons): Look into deprecating this option.
      for (String field : info.getFieldNames()) {
        if (field.equals("files")) {
          Object x = info.getValue("files");
          Depset.cast(x, Artifact.class, "files"); // may throw exception
          files = (Depset) x;
        } else if (field.equals("runfiles")) {
          statelessRunfiles = info.getValue("runfiles", Runfiles.class);
        } else if (field.equals("data_runfiles")) {
          dataRunfiles = info.getValue("data_runfiles", Runfiles.class);
        } else if (field.equals("default_runfiles")) {
          defaultRunfiles = info.getValue("default_runfiles", Runfiles.class);
        } else if (field.equals("executable") && info.getValue("executable") != null) {
          executable = info.getValue("executable", Artifact.class);
        }
      }

      if ((statelessRunfiles != null) && (dataRunfiles != null || defaultRunfiles != null)) {
        throw infoError(
            info,
            "Cannot specify the provider 'runfiles' together with 'data_runfiles' or"
                + " 'default_runfiles'");
      }
    }

    if (executable != null && !executable.getArtifactOwner().equals(context.getOwner())) {
      throw infoError(
          info,
          "'executable' provided by an executable rule '%s' should be created "
              + "by the same rule.",
          context.getRule().getRuleClass());
    }

    boolean isExecutable = context.getRule().getRuleClassObject().isExecutableStarlark();
    if (executable != null && isExecutable && isDefaultExecutableCreated) {
      Artifact defaultExecutable = context.createOutputArtifact();
      if (!executable.equals(defaultExecutable)) {
        throw infoError(
            info,
            "The rule '%s' both accesses 'ctx.outputs.executable' and provides "
                + "a different executable '%s'. Do not use 'ctx.output.executable'.",
            context.getRule().getRuleClass(),
            executable.getRootRelativePathString());
      }
    }

    if (context.getRule().isAnalysisTest()) {
      // The Starlark Build API should already throw exception if the rule implementation attempts
      // to register any actions. This is just a check of this invariant.
      Preconditions.checkState(
          context.getAnalysisEnvironment().getRegisteredActions().isEmpty(),
          "%s",
          context.getLabel());

      executable = context.createOutputArtifactScript();
    }

    if (executable == null && isExecutable) {
      if (isDefaultExecutableCreated) {
        // This doesn't actually create a new Artifact just returns the one
        // created in StarlarkRuleContext.
        executable = context.createOutputArtifact();
      } else {
        throw infoError(
            info,
            "The rule '%s' is executable. "
                + "It needs to create an executable File and pass it as the 'executable' "
                + "parameter to the DefaultInfo it returns.",
            context.getRule().getRuleClass());
      }
    }

    addSimpleProviders(
        builder, context, executable, files, statelessRunfiles, dataRunfiles, defaultRunfiles);
  }

  private static void addSimpleProviders(
      RuleConfiguredTargetBuilder builder,
      RuleContext ruleContext,
      Artifact executable,
      @Nullable Depset files,
      Runfiles statelessRunfiles,
      Runfiles dataRunfiles,
      Runfiles defaultRunfiles)
      throws EvalException, InterruptedException {

    // TODO(bazel-team) if both 'files' and 'executable' are provided, 'files' overrides
    // 'executable'
    NestedSetBuilder<Artifact> filesToBuild =
        NestedSetBuilder.<Artifact>stableOrder().addAll(ruleContext.getOutputArtifacts());
    if (executable != null) {
      filesToBuild.add(executable);
    }
    builder.setFilesToBuild(filesToBuild.build());

    if (files != null) {
      // If we specify files_to_build we don't have the executable in it by default.
      builder.setFilesToBuild(Depset.cast(files, Artifact.class, "files"));
    }

    if (statelessRunfiles == null && dataRunfiles == null && defaultRunfiles == null) {
      // No runfiles specified, set default
      statelessRunfiles = Runfiles.EMPTY;
    }

    RunfilesProvider runfilesProvider =
        statelessRunfiles != null
            ? RunfilesProvider.simple(mergeFiles(statelessRunfiles, executable, ruleContext))
            : RunfilesProvider.withData(
                // The executable doesn't get into the default runfiles if we have runfiles states.
                // This is to keep Starlark genrule consistent with the original genrule.
                defaultRunfiles != null ? defaultRunfiles : Runfiles.EMPTY,
                dataRunfiles != null ? dataRunfiles : Runfiles.EMPTY);
    builder.addProvider(RunfilesProvider.class, runfilesProvider);

    Runfiles computedDefaultRunfiles = runfilesProvider.getDefaultRunfiles();
    // This works because we only allowed to call a rule *_test iff it's a test type rule.
    boolean testRule = TargetUtils.isTestRuleName(ruleContext.getRule().getRuleClass());
    if (testRule && computedDefaultRunfiles.isEmpty()) {
      throw Starlark.errorf("Test rules have to define runfiles");
    }
    if (executable != null || testRule) {
      RunfilesSupport runfilesSupport = null;
      if (!computedDefaultRunfiles.isEmpty()) {
        Preconditions.checkNotNull(executable, "executable must not be null");
        runfilesSupport =
            RunfilesSupport.withExecutable(ruleContext, computedDefaultRunfiles, executable);
        assertExecutableSymlinkPresent(runfilesSupport.getRunfiles(), executable);
      }
      builder.setRunfilesSupport(runfilesSupport, executable);
    }

    if (ruleContext.getRule().getRuleClassObject().isStarlarkTestable()) {
      Info actions =
          ActionsProvider.create(ruleContext.getAnalysisEnvironment().getRegisteredActions());
      builder.addStarlarkDeclaredProvider(actions);
    }
  }

  private static void assertExecutableSymlinkPresent(Runfiles runfiles, Artifact executable)
      throws EvalException {
    // Extracting the map from Runfiles flattens a depset.
    // TODO(cparsons): Investigate: Avoiding this flattening may be an efficiency win.
    Map<PathFragment, Artifact> symlinks = runfiles.asMapWithoutRootSymlinks();
    if (!symlinks.containsValue(executable)) {
      throw Starlark.errorf("main program %s not included in runfiles", executable);
    }
  }

  private static Runfiles mergeFiles(
      Runfiles runfiles, Artifact executable, RuleContext ruleContext) {
    if (executable == null) {
      return runfiles;
    }
    return new Runfiles.Builder(
            ruleContext.getWorkspaceName(), ruleContext.getConfiguration().legacyExternalRunfiles())
        .addArtifact(executable)
        .merge(runfiles)
        .build();
  }
}
