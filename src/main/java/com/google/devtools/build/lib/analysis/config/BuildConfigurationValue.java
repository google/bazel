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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.NullConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.BuildConfigurationApi;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.TriState;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/**
 * Represents a collection of context information which may affect a build (for example: the target
 * platform for compilation, or whether or not debug tables are required). In fact, all
 * "environmental" information (e.g. from the tool's command-line, as opposed to the BUILD file)
 * that can affect the output of any build tool should be explicitly represented in the {@code
 * BuildConfigurationValue} instance.
 *
 * <p>A single build may require building tools to run on a variety of platforms: when compiling a
 * server application for production, we must build the build tools (like compilers) to run on the
 * execution platform, but cross-compile the application for the production environment.
 *
 * <p>There is always at least one {@code BuildConfigurationValue} instance in any build: the one
 * representing the target platform. Additional instances may be created, in a cross-compilation
 * build, for example.
 *
 * <p>Instances of {@code BuildConfigurationValue} are canonical:
 *
 * <pre>{@code c1.equals(c2) <=> c1==c2.}</pre>
 */
@AutoCodec
public class BuildConfigurationValue
    implements BuildConfigurationApi, SkyValue, BuildConfigurationInfo {

  private static final Interner<ImmutableSortedMap<Class<? extends Fragment>, Fragment>>
      fragmentsInterner = BlazeInterners.newWeakInterner();

  /** Global state necessary to build a BuildConfiguration. */
  public interface GlobalStateProvider {
    /** Computes the default shell environment for actions from the command line options. */
    ActionEnvironment getActionEnvironment(BuildOptions options);

    FragmentRegistry getFragmentRegistry();

    ImmutableSet<String> getReservedActionMnemonics();
  }

  private final OutputDirectories outputDirectories;

  private final ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments;

  private final ImmutableMap<String, Class<? extends Fragment>> starlarkVisibleFragments;
  private final String workspaceName;
  private final ImmutableSet<String> reservedActionMnemonics;
  private final CommandLineLimits commandLineLimits;

  /**
   * The global "make variables" such as "$(TARGET_CPU)"; these get applied to all rules analyzed in
   * this configuration.
   */
  private final ImmutableMap<String, String> globalMakeEnv;

  private final ActionEnvironment actionEnv;
  private final ActionEnvironment testEnv;

  private final BuildOptions buildOptions;
  private final CoreOptions options;

  /**
   * If non-empty, this is appended to output directories as ST-[transitionDirectoryNameFragment].
   * The value is a hash of BuildOptions that have been affected by a Starlark transition.
   *
   * <p>See b/203470434 or #14023 for more information and planned behavior changes.
   */
  private final String mnemonic;

  private final ImmutableMap<String, String> commandLineBuildVariables;

  /** Data for introspecting the options used by this configuration. */
  private final BuildOptionDetails buildOptionDetails;

  private final boolean siblingRepositoryLayout;

  private final FeatureSet defaultFeatures;

  @Nullable // lazily initialized
  private transient volatile BuildConfigurationEvent buildEvent;

  /**
   * Validates the options for this BuildConfigurationValue. Issues warnings for the use of
   * deprecated options, and warnings or errors for any option settings that conflict.
   */
  public void reportInvalidOptions(EventHandler reporter) {
    // Validate that --cpu has an allowed value. Since there is no CoreConfiguration, handle this
    // directly instead of using reportInvalidOptions.
    // TODO: blaze-configurability-team - Remove this when --cpu is fully deprecated.
    CoreOptions coreOptions = getOptions().get(CoreOptions.class);
    if (!coreOptions.allowedCpuValues.isEmpty()) {
      if (!coreOptions.allowedCpuValues.contains(coreOptions.cpu)) {
        reporter.handle(
            Event.error(
                String.format(
                    "Invalid --cpu value \"%s\": allowed values are %s.",
                    coreOptions.cpu, Joiner.on(", ").join(coreOptions.allowedCpuValues))));
      }
    }

    for (Fragment fragment : fragments.values()) {
      fragment.reportInvalidOptions(reporter, this.buildOptions);
    }
  }

  /**
   * Compute the test environment, which, at configuration level, is a pair consisting of the
   * statically set environment variables with their values and the set of environment variables to
   * be inherited from the client environment.
   */
  private ActionEnvironment setupTestEnvironment() {
    if (!buildOptions.contains(TestOptions.class)) {
      // TestOptions have been trimmed.
      return ActionEnvironment.EMPTY;
    }
    // Order doesn't matter here as ActionEnvironment sorts by key.
    Map<String, String> testEnv = new HashMap<>();
    for (Map.Entry<String, String> entry : buildOptions.get(TestOptions.class).testEnvironment) {
      testEnv.put(entry.getKey(), entry.getValue());
    }
    return ActionEnvironment.split(testEnv);
  }

  // Only BuildConfigurationFunction should instantiate this.
  public static BuildConfigurationValue create(
      BuildOptions buildOptions,
      @Nullable BuildOptions baselineOptions,
      String workspaceName,
      boolean siblingRepositoryLayout,
      // Arguments below this are server-global.
      BlazeDirectories directories,
      GlobalStateProvider globalProvider,
      FragmentFactory fragmentFactory)
      throws InvalidConfigurationException {

    FragmentClassSet fragmentClasses =
        buildOptions.hasNoConfig()
            ? FragmentClassSet.of(ImmutableSet.of())
            : globalProvider.getFragmentRegistry().getAllFragments();
    ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments =
        getConfigurationFragments(buildOptions, fragmentClasses, fragmentFactory);

    String mnemonic =
        OutputPathMnemonicComputer.computeMnemonic(buildOptions, baselineOptions, fragments);

    return new BuildConfigurationValue(
        buildOptions,
        mnemonic,
        workspaceName,
        siblingRepositoryLayout,
        directories,
        fragments,
        globalProvider.getReservedActionMnemonics(),
        globalProvider.getActionEnvironment(buildOptions));
  }

  // TODO(blaze-configurability-team): Ideally tests use the above create; however,
  //   ConfigurationTestCase most just checks equality constraints and this wants to directly
  //   fiddle with the mnemonic (and supplying a baselineOptions would be somewhat heavy).
  @VisibleForTesting
  public static BuildConfigurationValue createForTesting(
      BuildOptions buildOptions,
      String mnemonic,
      String workspaceName,
      boolean siblingRepositoryLayout,
      // Arguments below this are server-global.
      BlazeDirectories directories,
      GlobalStateProvider globalProvider,
      FragmentFactory fragmentFactory)
      throws InvalidConfigurationException {

    FragmentClassSet fragmentClasses =
        buildOptions.hasNoConfig()
            ? FragmentClassSet.of(ImmutableSet.of())
            : globalProvider.getFragmentRegistry().getAllFragments();
    ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments =
        getConfigurationFragments(buildOptions, fragmentClasses, fragmentFactory);

    return new BuildConfigurationValue(
        buildOptions,
        mnemonic,
        workspaceName,
        siblingRepositoryLayout,
        directories,
        fragments,
        globalProvider.getReservedActionMnemonics(),
        globalProvider.getActionEnvironment(buildOptions));
  }

  private static ImmutableSortedMap<Class<? extends Fragment>, Fragment> getConfigurationFragments(
      BuildOptions buildOptions, FragmentClassSet fragmentClasses, FragmentFactory fragmentFactory)
      throws InvalidConfigurationException {
    ImmutableSortedMap.Builder<Class<? extends Fragment>, Fragment> fragments =
        ImmutableSortedMap.orderedBy(FragmentClassSet.LEXICAL_FRAGMENT_SORTER);
    for (Class<? extends Fragment> fragmentClass : fragmentClasses) {
      Fragment fragment = fragmentFactory.createFragment(buildOptions, fragmentClass);
      if (fragment != null) {
        fragments.put(fragmentClass, fragment);
      }
    }
    return fragments.buildOrThrow();
  }

  // Package-visible for serialization purposes.
  BuildConfigurationValue(
      BuildOptions buildOptions,
      String mnemonic,
      String workspaceName,
      boolean siblingRepositoryLayout,
      // Arguments below this are either server-global and constant or completely dependent values.
      BlazeDirectories directories,
      ImmutableMap<Class<? extends Fragment>, Fragment> fragments,
      ImmutableSet<String> reservedActionMnemonics,
      ActionEnvironment actionEnvironment) {
    this.fragments =
        fragmentsInterner.intern(
            ImmutableSortedMap.copyOf(fragments, FragmentClassSet.LEXICAL_FRAGMENT_SORTER));
    this.starlarkVisibleFragments = buildIndexOfStarlarkVisibleFragments();
    this.buildOptions = buildOptions;
    this.mnemonic = mnemonic;
    this.options = buildOptions.get(CoreOptions.class);
    this.outputDirectories =
        new OutputDirectories(
            directories,
            options,
            buildOptions.get(PlatformOptions.class),
            mnemonic,
            workspaceName,
            siblingRepositoryLayout);
    this.workspaceName = workspaceName;
    this.siblingRepositoryLayout = siblingRepositoryLayout;

    // We can't use an ImmutableMap.Builder here; we need the ability to add entries with keys that
    // are already in the map so that the same define can be specified on the command line twice,
    // and ImmutableMap.Builder does not support that.
    commandLineBuildVariables =
        ImmutableMap.copyOf(options.getNormalizedCommandLineBuildVariables());

    this.actionEnv = actionEnvironment;
    this.testEnv = setupTestEnvironment();
    this.buildOptionDetails =
        BuildOptionDetails.forOptions(
            buildOptions.getNativeOptions(), buildOptions.getStarlarkOptions());

    // These should be documented in the build encyclopedia.
    // TODO(configurability-team): Deprecate TARGET_CPU in favor of platforms.
    globalMakeEnv =
        ImmutableMap.of(
            "TARGET_CPU",
            options.cpu,
            "COMPILATION_MODE",
            options.compilationMode.toString(),
            "BINDIR",
            getBinDirectory(RepositoryName.MAIN).getExecPathString(),
            "GENDIR",
            getGenfilesDirectory(RepositoryName.MAIN).getExecPathString());

    this.reservedActionMnemonics = reservedActionMnemonics;
    this.commandLineLimits = new CommandLineLimits(options.minParamFileSize);
    this.defaultFeatures = FeatureSet.parse(options.defaultFeatures);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BuildConfigurationValue otherVal)) {
      return false;
    }
    // Only considering arguments that are non-dependent and non-server-global.
    return this.buildOptions.equals(otherVal.buildOptions)
        && this.workspaceName.equals(otherVal.workspaceName)
        && this.siblingRepositoryLayout == otherVal.siblingRepositoryLayout
        && this.mnemonic.equals(otherVal.mnemonic);
  }

  @Override
  public int hashCode() {
    return Objects.hash(buildOptions, workspaceName, siblingRepositoryLayout, mnemonic);
  }

  private ImmutableMap<String, Class<? extends Fragment>> buildIndexOfStarlarkVisibleFragments() {
    ImmutableMap.Builder<String, Class<? extends Fragment>> builder = ImmutableMap.builder();

    for (Class<? extends Fragment> fragmentClass : fragments.keySet()) {
      StarlarkBuiltin module = StarlarkAnnotations.getStarlarkBuiltin(fragmentClass);
      if (module != null) {
        builder.put(module.name(), fragmentClass);
      }
    }
    return builder.buildOrThrow();
  }

  /**
   * Returns the {@link BuildConfigurationKey} for this configuration.
   *
   * <p>Note that this method does not apply a platform mapping. It is assumed that this
   * configuration was created with a platform mapping and thus its key does not need to be mapped
   * again.
   */
  public BuildConfigurationKey getKey() {
    return BuildConfigurationKey.create(buildOptions);
  }

  /** Retrieves the {@link BuildOptionDetails} containing data on this configuration's options. */
  public BuildOptionDetails getBuildOptionDetails() {
    return buildOptionDetails;
  }

  /** Returns the output directory for this build configuration. */
  public ArtifactRoot getOutputDirectory(RepositoryName repositoryName) {
    return outputDirectories.getOutputDirectory(repositoryName);
  }

  /**
   * @deprecated Use {@link #getBinDirectory} instead.
   */
  @Override
  @Deprecated
  public ArtifactRoot getBinDir() {
    return outputDirectories.getBinDirectory(RepositoryName.MAIN);
  }

  /**
   * Returns the bin directory for this build configuration.
   *
   * <p>TODO(kchodorow): This (and the other get*Directory functions) won't work with external
   * repositories without changes to how ArtifactFactory resolves derived roots. This is not an
   * issue right now because it only effects Blaze's include scanning (internal) and Bazel's
   * repositories (external) but will need to be fixed.
   *
   * @deprecated Use {@code RuleContext#getBinDirectory} instead whenever possible.
   */
  @Deprecated
  public ArtifactRoot getBinDirectory(RepositoryName repositoryName) {
    return outputDirectories.getBinDirectory(repositoryName);
  }

  /**
   * Returns a relative path to the bin directory at execution time.
   *
   * @deprecated Use {@code RuleContext#getBinFragment} instead whenever possible.
   */
  @Deprecated
  public PathFragment getBinFragment(RepositoryName repositoryName) {
    return outputDirectories.getBinDirectory(repositoryName).getExecPath();
  }

  /**
   * @deprecated Use {@link #getGenfilesDirectory} instead.
   */
  @Override
  @Deprecated
  public ArtifactRoot getGenfilesDir() {
    return outputDirectories.getGenfilesDirectory(RepositoryName.MAIN);
  }

  /**
   * Returns the genfiles directory for this build configuration.
   *
   * @deprecated Use {@code RuleContext#getGenfilesDirectory} instead whenever possible.
   */
  @Deprecated
  public ArtifactRoot getGenfilesDirectory(RepositoryName repositoryName) {
    return outputDirectories.getGenfilesDirectory(repositoryName);
  }

  public boolean hasSeparateGenfilesDirectory() {
    return !outputDirectories.mergeGenfilesDirectory();
  }

  @Override
  public boolean hasSeparateGenfilesDirectoryForStarlark(StarlarkThread thread)
      throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return hasSeparateGenfilesDirectory();
  }

  /**
   * Returns the directory where coverage-related artifacts and metadata files should be stored.
   * This includes for example uninstrumented class files needed for Jacoco's coverage reporting
   * tools.
   *
   * @deprecated Use {@code RuleContext#getCoverageMetadataDirectory} instead whenever possible.
   */
  @Deprecated
  public ArtifactRoot getCoverageMetadataDirectory(RepositoryName repositoryName) {
    return outputDirectories.getCoverageMetadataDirectory(repositoryName);
  }

  /**
   * Returns the testlogs directory for this build configuration.
   *
   * <p>Use {@code RuleContext#getTestLogsDirectory} instead whenever possible.
   */
  public ArtifactRoot getTestLogsDirectory(RepositoryName repositoryName) {
    return outputDirectories.getTestLogsDirectory(repositoryName);
  }

  /**
   * Returns a relative path to the genfiles directory at execution time.
   *
   * @deprecated Use {@code RuleContext#getGenfilesFragment} instead whenever possible.
   */
  @Deprecated
  public PathFragment getGenfilesFragment(RepositoryName repositoryName) {
    return outputDirectories.getGenfilesFragment(repositoryName);
  }

  /**
   * Returns the path separator for the host platform. This is basically the same as {@link
   * java.io.File#pathSeparator}, except that that returns the value for this JVM, which may or may
   * not match the host platform. You should only use this when invoking tools that are known to use
   * the native path separator, i.e., the path separator for the machine that they run on.
   */
  @Override
  public String getHostPathSeparator() {
    return outputDirectories.getHostPathSeparator();
  }

  public boolean isStrictFilesets() {
    return options.strictFilesets;
  }

  public boolean isStrictFilesetOutput() {
    return options.strictFilesetOutput;
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  @Override
  public String getMnemonic() {
    return outputDirectories.getMnemonic();
  }

  /** Returns whether to use automatic exec groups. */
  public boolean useAutoExecGroups() {
    return options.useAutoExecGroups;
  }

  /**
   * Returns the name of the base output directory under which actions in this configuration write
   * their outputs.
   *
   * <p>This is the same as {@link #getMnemonic}.
   */
  public String getOutputDirectoryName() {
    return outputDirectories.getOutputDirName();
  }

  @Override
  public String toString() {
    return checksum();
  }

  @Override
  public void debugPrint(PrintStream out) {
    out.printf("BuildConfigurationValue: %s\n", this.checksum());
    out.printf("  %s\n", this.options);
  }

  public ActionEnvironment getActionEnvironment() {
    return actionEnv;
  }

  public boolean isSiblingRepositoryLayout() {
    return siblingRepositoryLayout;
  }

  @Override
  public boolean isSiblingRepositoryLayoutForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return isSiblingRepositoryLayout();
  }

  /**
   * Return the "fixed" part of the actions' environment variables.
   *
   * <p>An action's full set of environment variables consist of a "fixed" part and of a "variable"
   * part. The "fixed" variables are independent of the Bazel client's own environment, and are
   * returned by this function. The "variable" ones are inherited from the Bazel client's own
   * environment, and are returned by {@link #getVariableShellEnvironment}.
   *
   * <p>Since values of the "fixed" variables are already known at analysis phase, it is returned
   * here as a map.
   */
  @Override
  public ImmutableMap<String, String> getLocalShellEnvironment() {
    return actionEnv.getFixedEnv();
  }

  /**
   * Return the "variable" part of the actions' environment variables.
   *
   * <p>An action's full set of environment variables consist of a "fixed" part and of a "variable"
   * part. The "fixed" variables are independent of the Bazel client's own environment, and are
   * returned by {@link #getLocalShellEnvironment}. The "variable" ones are inherited from the Bazel
   * client's own environment, and are returned by this function.
   *
   * <p>The values of the "variable" variables are tracked in Skyframe via the {@link
   * com.google.devtools.build.lib.skyframe.SkyFunctions#CLIENT_ENVIRONMENT_VARIABLE} skyfunction.
   * This method only returns the names of those variables to be inherited, if set in the client's
   * environment. (Variables where the name is not returned in this set should not be taken from the
   * client environment.)
   */
  @Deprecated // Use getActionEnvironment instead.
  public Iterable<String> getVariableShellEnvironment() {
    return actionEnv.getInheritedEnv();
  }

  /**
   * Returns a regex-based instrumentation filter instance that used to match label names to
   * identify targets to be instrumented in the coverage mode.
   */
  public RegexFilter getInstrumentationFilter() {
    return options.instrumentationFilter;
  }

  /**
   * Returns a boolean of whether to include targets created by *_test rules in the set of targets
   * matched by --instrumentation_filter. If this is false, all test targets are excluded from
   * instrumentation.
   */
  public boolean shouldInstrumentTestTargets() {
    return options.instrumentTestTargets;
  }

  /** Returns a boolean of whether to collect code coverage for generated files or not. */
  public boolean shouldCollectCodeCoverageForGeneratedFiles() {
    return options.collectCodeCoverageForGeneratedFiles;
  }

  /**
   * Returns a new, unordered mapping of names to values of "Make" variables defined by this
   * configuration.
   *
   * <p>This does *not* include package-defined overrides (e.g. vardef) and so should not be used by
   * the build logic. This is used only for the 'info' command.
   *
   * <p>Command-line definitions of make environments override variables defined by {@code
   * Fragment.addGlobalMakeVariables()}.
   */
  public ImmutableMap<String, String> getMakeEnvironment() {
    ImmutableMap.Builder<String, String> makeEnvironment = ImmutableMap.builder();
    makeEnvironment.putAll(globalMakeEnv);
    makeEnvironment.putAll(commandLineBuildVariables);
    return makeEnvironment.buildKeepingLast();
  }

  /**
   * Returns a new, unordered mapping of names that are set through the command lines. (Fragments,
   * in particular the Google C++ support, can set variables through the command line.)
   */
  public ImmutableMap<String, String> getCommandLineBuildVariables() {
    return commandLineBuildVariables;
  }

  /** Returns the global defaults for this configuration for the Make environment. */
  public ImmutableMap<String, String> getGlobalMakeEnvironment() {
    return globalMakeEnv;
  }

  /**
   * Returns the default value for the specified "Make" variable for this configuration. Returns
   * null if no value was found.
   */
  public String getMakeVariableDefault(String var) {
    return globalMakeEnv.get(var);
  }

  /** Returns a configuration fragment instances of the given class. */
  public <T extends Fragment> T getFragment(Class<T> clazz) {
    return clazz.cast(fragments.get(clazz));
  }

  /** Return all the configuration fragments. */
  public ImmutableSortedMap<Class<? extends Fragment>, Fragment> getFragments() {
    return fragments;
  }

  /** Returns true if the requested configuration fragment is present. */
  public <T extends Fragment> boolean hasFragment(Class<T> clazz) {
    return getFragment(clazz) != null;
  }

  /** Returns true if all requested configuration fragment are present (this may be slow). */
  public boolean hasAllFragments(Set<Class<?>> fragmentClasses) {
    for (Class<?> fragmentClass : fragmentClasses) {
      if (!hasFragment(fragmentClass.asSubclass(Fragment.class))) {
        return false;
      }
    }
    return true;
  }

  public BlazeDirectories getDirectories() {
    return outputDirectories.getDirectories();
  }

  /** Returns true if non-functional build stamps are enabled. */
  public boolean stampBinaries() {
    return options.stampBinaries;
  }

  @Override
  public boolean stampBinariesForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return stampBinaries();
  }

  /** Returns true if extended sanity checks should be enabled. */
  public boolean extendedSanityChecks() {
    return options.extendedSanityChecks;
  }

  /** Returns true if we are building runfiles manifests for this configuration. */
  public boolean buildRunfileManifests() {
    return options.buildRunfileManifests;
  }

  /** Returns true if we are building runfile links for this configuration. */
  public boolean buildRunfileLinks() {
    return options.buildRunfileManifests && options.buildRunfileLinks;
  }

  /** Returns if we are building external runfiles symlinks using the old-style structure. */
  public boolean legacyExternalRunfiles() {
    return options.legacyExternalRunfiles;
  }

  /**
   * Returns true if Runfiles should merge in FilesToBuild from deps when collecting data runfiles.
   */
  public boolean alwaysIncludeFilesToBuildInData() {
    return options.alwaysIncludeFilesToBuildInData;
  }

  /**
   * Returns user-specified test environment variables and their values, as set by the --test_env
   * options.
   */
  @Override
  public ImmutableMap<String, String> getTestEnv() {
    return testEnv.getFixedEnv();
  }

  /**
   * Returns user-specified test environment variables and their values, as set by the {@code
   * --test_env} options. It is incomplete in that it is not a superset of the {@link
   * #getActionEnvironment}, but both have to be applied, with this one being applied after the
   * other, such that {@code --test_env} settings can override {@code --action_env} settings.
   */
  // TODO(ulfjack): Just return the merged action and test action environment here?
  public ActionEnvironment getTestActionEnvironment() {
    return testEnv;
  }

  @Override
  public CommandLineLimits getCommandLineLimits() {
    return commandLineLimits;
  }

  @Override
  public boolean isCodeCoverageEnabled() {
    return options.collectCodeCoverage;
  }

  @Nullable
  public RunUnder getRunUnder() {
    return options.runUnder;
  }

  /** Should the {@code --run_under} be configured in the exec configuration? */
  public boolean runUnderExecConfigForTests() {
    return options.bazelTestExecRunUnder;
  }

  /** Returns true if this is an execution configuration. */
  public boolean isExecConfiguration() {
    return options.isExec;
  }

  @Override
  public boolean isToolConfiguration() {
    return isExecConfiguration();
  }

  @Override
  public boolean isToolConfigurationForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return isToolConfiguration();
  }

  public boolean checkVisibility() {
    return options.checkVisibility;
  }

  public boolean checkTestonlyForOutputFiles() {
    return options.checkTestonlyForOutputFiles;
  }

  public boolean checkLicenses() {
    return options.checkLicenses;
  }

  public boolean enforceConstraints() {
    return options.enforceConstraints;
  }

  public boolean allowAnalysisFailures() {
    return options.allowAnalysisFailures;
  }

  public boolean evaluatingForAnalysisTest() {
    return options.evaluatingForAnalysisTest;
  }

  public int analysisTestingDepsLimit() {
    return options.analysisTestingDepsLimit;
  }

  public List<Label> getActionListeners() {
    return options.actionListeners;
  }

  /**
   * <b>>Experimental feature:</b> if true, qualifying outputs use path prefixes based on their
   * content instead of the traditional <code>blaze-out/$CPU-$COMPILATION_MODE</code>.
   *
   * <p>This promises both more intrinsic correctness (outputs with different contents can't write
   * to the same path) and efficiency (outputs with the <i>same</i> contents share the same path and
   * therefore permit better action caching). But it's highly experimental and should not be relied
   * on in any serious way any time soon.
   *
   * <p>See <a href="https://github.com/bazelbuild/bazel/issues/6526">#6526</a> for details.
   */
  public boolean useContentBasedOutputPaths() {
    return options.outputPathsMode == CoreOptions.OutputPathsMode.CONTENT;
  }

  public boolean allowUnresolvedSymlinks() {
    return options.allowUnresolvedSymlinks;
  }

  /** Returns compilation mode. */
  public CompilationMode getCompilationMode() {
    return options.compilationMode;
  }

  @Override
  public String checksum() {
    return buildOptions.checksum();
  }

  /**
   * Returns a user-friendly short configuration identifier.
   *
   * <p>See {@link BuildOptions#shortId()} for details.
   */
  public String shortId() {
    return buildOptions.shortId();
  }

  /** Returns a copy of the build configuration options for this configuration. */
  public BuildOptions cloneOptions() {
    return buildOptions.clone();
  }

  /**
   * Returns the actual options reference used by this configuration.
   *
   * <p><b>Be very careful using this method.</b> Options classes are mutable - no caller should
   * ever call this method if there's any change the reference might be written to. This method only
   * exists because {@link #cloneOptions} can be expensive when applied to every edge in a
   * dependency graph.
   *
   * <p>Do not use this method without careful review with other Bazel developers.
   */
  public BuildOptions getOptions() {
    return buildOptions;
  }

  public String getCpu() {
    return options.cpu;
  }

  @VisibleForTesting
  public String getHostCpu() {
    return options.hostCpu;
  }

  /**
   * Describes how to create runfile symlink trees.
   *
   * <p>May be overridden if an {@link com.google.devtools.build.lib.vfs.OutputService} capable of
   * creating symlink trees is available.
   */
  public enum RunfileSymlinksMode {
    /** Do not create. */
    SKIP,
    /** Use the out-of-process implementation. */
    EXTERNAL,
    /** Use the in-process implementation. */
    INTERNAL
  }

  @VisibleForTesting
  public static RunfileSymlinksMode getRunfileSymlinksMode(CoreOptions options) {
    // TODO(buchgr): Revisit naming and functionality of this flag. See #9248 for details.
    if (options.enableRunfiles == TriState.YES
        || (options.enableRunfiles == TriState.AUTO && OS.getCurrent() != OS.WINDOWS)) {
      return options.inProcessSymlinkCreation
          ? RunfileSymlinksMode.INTERNAL
          : RunfileSymlinksMode.EXTERNAL;
    }
    return RunfileSymlinksMode.SKIP;
  }

  public RunfileSymlinksMode getRunfileSymlinksMode() {
    return getRunfileSymlinksMode(options);
  }

  public static boolean runfilesEnabled(CoreOptions options) {
    return getRunfileSymlinksMode(options) != RunfileSymlinksMode.SKIP;
  }

  public boolean runfilesEnabled() {
    return runfilesEnabled(options);
  }

  @Override
  public boolean runfilesEnabledForStarlark(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return runfilesEnabled();
  }

  public boolean remotableSourceManifestActions() {
    return options.remotableSourceManifestActions;
  }

  /**
   * Returns a modified copy of {@code executionInfo} if any {@code executionInfoModifiers} apply to
   * the given {@code mnemonic}. Otherwise returns {@code executionInfo} unchanged.
   */
  public ImmutableMap<String, String> modifiedExecutionInfo(
      ImmutableMap<String, String> executionInfo, String mnemonic) {
    if (!ExecutionInfoModifier.matches(
        options.executionInfoModifier, options.additiveModifyExecutionInfo, mnemonic)) {
      return executionInfo;
    }
    Map<String, String> mutableCopy = new HashMap<>(executionInfo);
    modifyExecutionInfo(mutableCopy, mnemonic);
    return ImmutableSortedMap.copyOf(mutableCopy);
  }

  /** Applies {@code executionInfoModifiers} to the given {@code executionInfo}. */
  public void modifyExecutionInfo(Map<String, String> executionInfo, String mnemonic) {
    ExecutionInfoModifier.apply(
        options.executionInfoModifier,
        options.additiveModifyExecutionInfo,
        mnemonic,
        executionInfo);
  }

  /** Returns the list of default features used for all packages. */
  public FeatureSet getDefaultFeatures() {
    return defaultFeatures;
  }

  /**
   * Returns the "top-level" environment space, i.e. the set of environments all top-level targets
   * must be compatible with. An empty value implies no restrictions.
   */
  public List<Label> getTargetEnvironments() {
    return options.targetEnvironments;
  }

  @Nullable
  public Class<? extends Fragment> getStarlarkFragmentByName(String name) {
    return starlarkVisibleFragments.get(name);
  }

  public ImmutableCollection<String> getStarlarkFragmentNames() {
    return starlarkVisibleFragments.keySet();
  }

  public BuildEventId getEventId() {
    return BuildEventIdUtil.configurationId(checksum());
  }

  @Override
  public BuildConfigurationEvent toBuildEvent() {
    if (buildEvent == null) {
      synchronized (this) {
        if (buildEvent == null) {
          buildEvent = createBuildEvent();
        }
      }
    }
    return buildEvent;
  }

  private BuildConfigurationEvent createBuildEvent() {
    BuildEventId eventId = getEventId();
    BuildEventStreamProtos.BuildEvent.Builder builder =
        BuildEventStreamProtos.BuildEvent.newBuilder();
    builder
        .setId(eventId)
        .setConfiguration(
            BuildEventStreamProtos.Configuration.newBuilder()
                .setMnemonic(getMnemonic())
                .setPlatformName(getCpu())
                .putAllMakeVariable(getMakeEnvironment())
                .setCpu(getCpu())
                .setIsTool(isToolConfiguration())
                .build());
    return new BuildConfigurationEvent(eventId, builder.build());
  }

  public static BuildEventId.ConfigurationId configurationIdMessage(
      @Nullable BuildConfigurationValue configuration) {
    if (configuration == null) {
      return BuildEventIdUtil.nullConfigurationIdMessage();
    }
    return BuildEventIdUtil.configurationIdMessage(configuration.checksum());
  }

  public static BuildEventId configurationId(@Nullable BuildConfigurationValue configuration) {
    if (configuration == null) {
      return BuildEventIdUtil.nullConfigurationId();
    }
    return configuration.getEventId();
  }

  public static BuildEvent buildEvent(@Nullable BuildConfigurationValue configuration) {
    return configuration == null ? NullConfiguration.INSTANCE : configuration.toBuildEvent();
  }

  public ImmutableSet<String> getReservedActionMnemonics() {
    return reservedActionMnemonics;
  }
}
