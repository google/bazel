// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLogBufferPathGenerator;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.MapBasedActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.RunfilesTreeAction;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DependencyResolutionHelpers.Failure;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.PseudoAction;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.configuredtargets.FileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.extra.ExtraAction;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.analysis.test.BaselineCoverageAction;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageOverheadEstimator;
import com.google.devtools.build.lib.packages.PackageValidator;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleClassUtils;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageRootsNoSymlinkCreation;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyFunctionEnvironmentForTesting;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutorRepositoryHelpersHolder;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.After;
import org.junit.Before;

/** Common test code that creates a BuildView instance. */
public abstract class BuildViewTestCase extends FoundationTestCase {
  protected static final int LOADING_PHASE_THREADS = 20;

  protected AnalysisMock analysisMock;
  protected ConfiguredRuleClassProvider ruleClassProvider;
  protected BuildViewForTesting view;

  protected SequencedSkyframeExecutor skyframeExecutor;

  protected TimestampGranularityMonitor tsgm;
  protected BlazeDirectories directories;
  protected ActionKeyContext actionKeyContext;

  protected Path moduleRoot;
  protected FakeRegistry registry;

  // Note that these configurations are virtual (they use only VFS)
  protected BuildConfigurationValue targetConfig; // "target" or "build" config
  protected BuildConfigurationValue execConfig;
  private List<String> configurationArgs;

  private PackageOptions packageOptions;
  private BuildLanguageOptions buildLanguageOptions;
  protected PackageFactory pkgFactory;

  protected MockToolsConfig mockToolsConfig;

  protected WorkspaceStatusAction.Factory workspaceStatusActionFactory;

  private MutableActionGraph mutableActionGraph;

  private LoadingOptions customLoadingOptions = null;
  protected BuildConfigurationKey targetConfigKey;

  private ActionLogBufferPathGenerator actionLogBufferPathGenerator;

  @Nullable private BzlLoadFunction inliningBzlLoadFunction;

  @After
  public final void cleanupInterningPools() {
    skyframeExecutor.getEvaluator().cleanupInterningPools();
  }

  @Before
  public void initializeSkyframeExecutor() throws Exception {
    initializeSkyframeExecutor(/* doPackageLoadingChecks= */ true);
  }

  public void initializeSkyframeExecutor(boolean doPackageLoadingChecks) throws Exception {
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ doPackageLoadingChecks,
        /* diffAwarenessFactories= */ ImmutableList.of(),
        /* globUnderSingleDep= */ true);
  }

  public void initializeSkyframeExecutor(
      boolean doPackageLoadingChecks, ImmutableList<DiffAwareness.Factory> diffAwarenessFactories)
      throws Exception {
    initializeSkyframeExecutor(
        doPackageLoadingChecks, diffAwarenessFactories, /* globUnderSingleDep= */ true);
  }

  /**
   * Only {@link com.google.devtools.build.lib.skyframe.PackageFunctionTest} still covers testing
   * Skyframe Hybrid globbing by passing in the test parameter globUnderSingleDep.
   *
   * <p>All other tests adopt GLOBS strategy by setting {@code globUnderSingleDep} to {@code true}.
   */
  public void initializeSkyframeExecutor(
      boolean doPackageLoadingChecks,
      ImmutableList<DiffAwareness.Factory> diffAwarenessFactories,
      boolean globUnderSingleDep)
      throws Exception {
    analysisMock = getAnalysisMock();
    directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    moduleRoot = scratch.dir("modules");
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());

    actionKeyContext = new ActionKeyContext();
    mockToolsConfig = new MockToolsConfig(rootDirectory, false);
    analysisMock.setupMockToolsRepository(mockToolsConfig);
    initializeMockClient();

    packageOptions = parsePackageOptions();
    buildLanguageOptions = parseBuildLanguageOptions();
    workspaceStatusActionFactory = new AnalysisTestUtil.DummyWorkspaceStatusActionFactory();
    mutableActionGraph = new MapBasedActionGraph(actionKeyContext);
    ruleClassProvider = createRuleClassProvider();
    getOutputPath().createDirectoryAndParents();
    ImmutableList<PrecomputedValue.Injected> extraPrecomputedValues =
        ImmutableList.<PrecomputedValue.Injected>builder()
            .addAll(analysisMock.getPrecomputedValues())
            .add(
                PrecomputedValue.injected(
                    ModuleFileFunction.REGISTRIES, ImmutableSet.of(registry.getUrl())))
            .addAll(extraPrecomputedValues())
            .build();
    PackageFactory.BuilderForTesting pkgFactoryBuilder =
        analysisMock
            .getPackageFactoryBuilderForTesting(directories)
            .setExtraPrecomputeValues(extraPrecomputedValues)
            .setPackageValidator(getPackageValidator())
            .setPackageOverheadEstimator(getPackageOverheadEstimator());
    if (!doPackageLoadingChecks) {
      pkgFactoryBuilder.disableChecks();
    }
    pkgFactory = pkgFactoryBuilder.build(ruleClassProvider, fileSystem);
    tsgm = new TimestampGranularityMonitor(BlazeClock.instance());
    if (skyframeExecutor != null) {
      cleanupInterningPools();
    }
    skyframeExecutor =
        BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(pkgFactory)
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
            .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
            .setSyscallCache(SyscallCache.NO_CACHE)
            .setDiffAwarenessFactories(diffAwarenessFactories)
            .setRepositoryHelpersHolder(getRepositoryHelpersHolder())
            .setGlobUnderSingleDep(globUnderSingleDep)
            .build();
    if (usesInliningBzlLoadFunction()) {
      injectInliningBzlLoadFunction(skyframeExecutor, ruleClassProvider, directories);
    } else {
      // As of 05/21/2024, SerializationCheckingGraph does not deserialize analysis phase objects
      // from inline bzl correctly.
      //
      // The SerializationCheckingGraph assumes that objects that are exported from a given .bzl
      // file can be looked up later as a global symbol in the corresponding BzlLoadValue and that
      // the BzlLoadValue is present in Skyframe. This isn't true when .bzl inlining is used.
      SkyframeExecutorTestHelper.process(skyframeExecutor);
    }
    skyframeExecutor.injectExtraPrecomputedValues(extraPrecomputedValues);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    skyframeExecutor.preparePackageLoading(
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(root),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        tsgm);
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    useConfiguration();
    setUpSkyframe();
    this.actionLogBufferPathGenerator =
        new ActionLogBufferPathGenerator(directories.getActionTempsDirectory(getExecRoot()));
  }

  @ForOverride
  @Nullable
  protected SkyframeExecutorRepositoryHelpersHolder getRepositoryHelpersHolder() {
    return null;
  }

  private void injectInliningBzlLoadFunction(
      SkyframeExecutor skyframeExecutor,
      RuleClassProvider ruleClassProvider,
      BlazeDirectories directories) {
    ImmutableMap<SkyFunctionName, SkyFunction> skyFunctions =
        ((InMemoryMemoizingEvaluator) skyframeExecutor.getEvaluator()).getSkyFunctionsForTesting();
    inliningBzlLoadFunction =
        BzlLoadFunction.createForInlining(
            ruleClassProvider,
            directories,
            // Use a cache size of 2 for testing to balance coverage for where loads are present and
            // aren't present in the cache.
            /* bzlLoadValueCacheSize= */ 2);
    // The builtins should be empty since this was just created but reset it anyway to be sure.
    inliningBzlLoadFunction.resetInliningCacheAndBuiltinsForTesting();
    // This doesn't override the BZL_LOAD -> BzlLoadFunction mapping, but nothing besides
    // PackageFunction should be requesting that key while using the inlining code path.
    ((PackageFunction) skyFunctions.get(SkyFunctions.PACKAGE))
        .setBzlLoadFunctionForInliningForTesting(inliningBzlLoadFunction);
  }

  /**
   * Returns whether or not to use the inlined version of BzlLoadFunction in this test.
   *
   * @see BzlLoadFunction#computeInline
   */
  protected boolean usesInliningBzlLoadFunction() {
    return false;
  }

  /**
   * Returns extra precomputed values to inject, both into Skyframe and the testing package loaders.
   */
  protected ImmutableList<PrecomputedValue.Injected> extraPrecomputedValues() throws Exception {
    return ImmutableList.of();
  }

  protected void initializeMockClient() throws IOException {
    analysisMock.setupMockClient(mockToolsConfig);
    analysisMock.setupPrelude(mockToolsConfig);
  }

  protected AnalysisMock getAnalysisMock() {
    return AnalysisMock.get();
  }

  /**
   * Called to create the rule class provider used in this test.
   *
   * <p>This function is called only once. (Multiple calls could lead to subtle identity bugs
   * between native objects.)
   */
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    return getAnalysisMock().createRuleClassProvider();
  }

  protected final ConfiguredRuleClassProvider getRuleClassProvider() {
    return ruleClassProvider;
  }

  protected StarlarkSemantics getStarlarkSemantics() {
    return buildLanguageOptions.toStarlarkSemantics();
  }

  protected PackageValidator getPackageValidator() {
    return PackageValidator.NOOP_VALIDATOR;
  }

  protected PackageOverheadEstimator getPackageOverheadEstimator() {
    return PackageOverheadEstimator.NOOP_ESTIMATOR;
  }

  protected final BuildConfigurationValue createConfiguration(String... args) throws Exception {
    BuildOptions buildOptions = createBuildOptions(args);

    // This is being done outside of BuildView, potentially even before the BuildView was
    // constructed and thus cannot rely on BuildView having injected this for us.
    skyframeExecutor.setBaselineConfiguration(buildOptions, reporter);
    return skyframeExecutor.createConfiguration(reporter, buildOptions, false);
  }

  protected BuildOptions createBuildOptions(String... args)
      throws OptionsParsingException, InvalidConfigurationException {
    ImmutableList<String> allArgs = ImmutableList.copyOf(args);
    return skyframeExecutor.createBuildOptionsForTesting(reporter, allArgs);
  }

  protected Target getTarget(String label)
      throws NoSuchPackageException,
          NoSuchTargetException,
          LabelSyntaxException,
          InterruptedException {
    return getTarget(Label.parseCanonical(label));
  }

  protected Target getTarget(Label label)
      throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
    return getPackageManager().getTarget(reporter, label);
  }

  /**
   * Checks that loading the given target fails with the expected error message.
   *
   * <p>Fails with an assertion error if this doesn't happen.
   *
   * <p>This method is useful for checking loading phase errors. Analysis phase errors can be
   * checked with {@link #getConfiguredTarget} and related methods.
   */
  protected void assertTargetError(String label, String expectedError) throws InterruptedException {
    try {
      getTarget(label);
      fail("Expected loading phase failure for target " + label);
    } catch (NoSuchPackageException | NoSuchTargetException | LabelSyntaxException e) {
      // Target loading failed as expected.
    }
    assertContainsEvent(expectedError);
  }

  private void setUpSkyframe() {
    PathPackageLocator pkgLocator =
        PathPackageLocator.create(
            outputBase,
            packageOptions.packagePath,
            reporter,
            rootDirectory.asFragment(),
            rootDirectory,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    skyframeExecutor.preparePackageLoading(
        pkgLocator,
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        tsgm);
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    skyframeExecutor.setDeletedPackages(packageOptions.getDeletedPackages());
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty()),
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.VENDOR_DIRECTORY, Optional.empty())));
  }

  protected void setPackageOptions(String... options) throws Exception {
    packageOptions = parsePackageOptions(options);
    setUpSkyframe();
    invalidatePackages(/* alsoConfigs= */ false);
  }

  protected void setBuildLanguageOptions(String... options) throws Exception {
    buildLanguageOptions = parseBuildLanguageOptions(options);
    setUpSkyframe();
    invalidatePackages(/* alsoConfigs= */ false);
  }

  /**
   * Override to change the default visibility for a test suite. Visibility can also be controlled
   * with {@link #setPackageOptions}.
   */
  protected String getDefaultVisibility() {
    return "public";
  }

  private PackageOptions parsePackageOptions(String... options) throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(PackageOptions.class).build();
    parser.parse("--default_visibility=" + getDefaultVisibility());
    parser.parse(options);
    return parser.getOptions(PackageOptions.class);
  }

  protected BuildLanguageOptions parseBuildLanguageOptions(String... options) throws Exception {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BuildLanguageOptions.class).build();
    parser.parse(getDefaultBuildLanguageOptions());
    parser.parse(options);
    return parser.getOptions(BuildLanguageOptions.class);
  }

  protected List<String> getDefaultBuildLanguageOptions() throws Exception {
    ImmutableList.Builder<String> ans = ImmutableList.builder();
    ans.addAll(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
    return ans.build();
  }

  /** Used by skyframe-only tests. */
  protected SequencedSkyframeExecutor getSkyframeExecutor() {
    return Preconditions.checkNotNull(skyframeExecutor);
  }

  protected PackageManager getPackageManager() {
    return skyframeExecutor.getPackageManager();
  }

  /**
   * Invalidates all existing packages, clears the cache for inlined bzl loads (including builtins),
   * and invalidates configurations.
   */
  protected void invalidatePackages() throws InterruptedException, AbruptExitException {
    invalidatePackages(true);
  }

  /**
   * Invalidates all existing packages and clears the cache for inlined bzl loads (including
   * builtins). Optionally also invalidates configurations.
   *
   * <p>Tests should invalidate both unless they have specific reason not to.
   */
  protected void invalidatePackages(boolean alsoConfigs)
      throws InterruptedException, AbruptExitException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));
    if (inliningBzlLoadFunction != null) {
      inliningBzlLoadFunction.resetInliningCacheAndBuiltinsForTesting();
    }
    if (alsoConfigs) {
      try {
        // Also invalidate all configurations. This is important: by invalidating all files we
        // invalidate CROSSTOOL, which invalidates CppConfiguration (and a few other fragments).
        // Otherwise we end up with old CppConfiguration instances. Even though they're logically
        // equal to the new ones, CppConfiguration has no .equals() method and some production code
        // expects equality.
        useConfiguration(configurationArgs.toArray(new String[0]));
      } catch (Exception e) {
        // There are enough dependers on this method that don't handle Exception that just passing
        // through the Exception would result in a huge refactoring. As it stands this shouldn't
        // fail anyway because this method only gets called after a successful useConfiguration()
        // call anyway.
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Returns options that will be implicitly prepended to any options passed to {@link
   * #useConfiguration}.
   */
  protected Iterable<String> getDefaultsForConfiguration() {
    return TestConstants.PRODUCT_SPECIFIC_FLAGS;
  }

  /**
   * Sets exec and target configuration using the specified options, falling back to the default
   * options for unspecified ones, and recreates the build view.
   *
   * <p>NOTE: Build language options are not support by this method, for example
   * --experimental_google_legacy_api. Use {@link #setBuildLanguageOptions} instead.
   *
   * @param args native and Starlark option name/pair descriptions in command line form (e.g.
   *     "--cpu=k8")
   */
  protected void useConfiguration(String... args) throws Exception {
    ImmutableList<String> actualArgs =
        ImmutableList.<String>builder().addAll(getDefaultsForConfiguration()).add(args).build();

    targetConfig = createConfiguration(actualArgs.toArray(new String[0]));
    if (!scratch.resolve("platform/BUILD").exists()) {
      scratch.overwriteFile("platform/BUILD", "platform(name = 'exec')");
    }
    execConfig =
        skyframeExecutor.getConfiguration(
            reporter,
            AnalysisTestUtil.execOptions(targetConfig.getOptions(), skyframeExecutor, reporter),
            /* keepGoing= */ false);

    targetConfigKey = targetConfig.getKey();
    configurationArgs = actualArgs;
    createBuildView();
  }

  /**
   * Creates BuildView using current execConfig/targetConfig values. Ensures that execConfig is
   * either identical to the targetConfig or {@code isExecConfiguration()} is true.
   */
  protected final void createBuildView()
      throws InvalidConfigurationException, InterruptedException {
    Preconditions.checkNotNull(targetConfig);
    Preconditions.checkState(
        getExecConfiguration().equals(getTargetConfiguration())
            || getExecConfiguration().isExecConfiguration(),
        "Exec configuration %s is not an exec configuration' "
            + "and does not match target configuration %s",
        getExecConfiguration(),
        getTargetConfiguration());

    skyframeExecutor.handleAnalysisInvalidatingChange();
    skyframeExecutor.setBaselineConfiguration(targetConfig.getOptions(), reporter);

    view = new BuildViewForTesting(directories, ruleClassProvider, skyframeExecutor, null);
    view.setConfigurationForTesting(targetConfig);

    view.setArtifactRoots(new PackageRootsNoSymlinkCreation(Root.fromPath(rootDirectory)));
  }

  protected CachingAnalysisEnvironment getTestAnalysisEnvironment() throws InterruptedException {
    SkyFunction.Environment env = new SkyFunctionEnvironmentForTesting(reporter, skyframeExecutor);
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue)
            Preconditions.checkNotNull(env.getValue(StarlarkBuiltinsValue.key()));
    return new CachingAnalysisEnvironment(
        view.getArtifactFactory(),
        actionKeyContext,
        new ActionLookupKey() {
          @Nullable
          @Override
          public Label getLabel() {
            return null;
          }

          @Nullable
          @Override
          public BuildConfigurationKey getConfigurationKey() {
            return null;
          }

          @Override
          public SkyFunctionName functionName() {
            return null;
          }
        },
        /* extendedSanityChecks= */ false,
        /* allowAnalysisFailures= */ false,
        reporter,
        env,
        starlarkBuiltinsValue);
  }

  /**
   * Returns the sorted list of all rule classes available in builtins, following the logic of
   * {@code bazel info build-language}.
   *
   * @param includeMacroWrappedRules if true, include rule classes for rules wrapped in macros.
   */
  protected ImmutableList<RuleClass> getBuiltinRuleClasses(boolean includeMacroWrappedRules)
      throws Exception {
    SkyFunction.Environment env = new SkyFunctionEnvironmentForTesting(reporter, skyframeExecutor);
    StarlarkBuiltinsValue builtins =
        (StarlarkBuiltinsValue) checkNotNull(env.getValue(StarlarkBuiltinsValue.key()));
    return RuleClassUtils.getBuiltinRuleClasses(
        builtins, ruleClassProvider, includeMacroWrappedRules);
  }

  /**
   * Allows access to the prerequisites of a configured target. This is currently used in some tests
   * to reach into the internals of RuleCT for white box testing. In principle, this should not be
   * used; instead tests should only assert on properties of the exposed provider instances and / or
   * the action graph.
   */
  protected final Collection<ConfiguredTarget> getDirectPrerequisites(ConfiguredTarget target)
      throws InterruptedException,
          TransitionException,
          InvalidConfigurationException,
          InconsistentAspectOrderException,
          Failure {
    return view.getDirectPrerequisitesForTesting(reporter, target);
  }

  protected final ConfiguredTarget getDirectPrerequisite(ConfiguredTarget target, String label)
      throws Exception {
    Label candidateLabel = Label.parseCanonical(label);
    Optional<ConfiguredTarget> prereq =
        getDirectPrerequisites(target).stream()
            .filter(candidate -> candidate.getOriginalLabel().equals(candidateLabel))
            .findFirst();
    return prereq.orElse(null);
  }

  protected final ConfiguredTargetAndData getConfiguredTargetAndDataDirectPrerequisite(
      ConfiguredTargetAndData ctad, String label) throws Exception {
    Label candidateLabel = Label.parseCanonical(label);
    for (ConfiguredTargetAndData candidate :
        view.getConfiguredTargetAndDataDirectPrerequisitesForTesting(
            reporter, ctad.getConfiguredTarget())) {
      if (candidate.getConfiguredTarget().getLabel().equals(candidateLabel)) {
        return candidate;
      }
    }
    return null;
  }

  /** Returns a {@link BuildOptions} with options in {@code exclude} trimmed away. */
  private static BuildOptions trimConfiguration(
      BuildOptions original, Set<Class<? extends FragmentOptions>> exclude) {
    BuildOptions.Builder trimmed = original.toBuilder();
    exclude.forEach(trimmed::removeFragmentOptions);
    return trimmed.build();
  }

  /**
   * Asserts that two configurations are the same, with exclusions.
   *
   * <p>Any fragments options of type specified in excludeFragmentOptions are excluded from the
   * comparison.
   *
   * <p>Generally, this means they share the same checksum, which is computed by iterating over all
   * the individual @Option annotated values contained within the {@link FragmentOptions} classes
   * contained within the {@link BuildOptions} inside the given configurations.
   */
  protected static void assertConfigurationsEqual(
      BuildConfigurationValue config1,
      BuildConfigurationValue config2,
      Set<Class<? extends FragmentOptions>> excludeFragmentOptions) {
    // BuildOptions and crosstool files determine a configuration's content. Within the context
    // of these tests only the former actually change.

    assertThat(trimConfiguration(config2.cloneOptions(), excludeFragmentOptions))
        .isEqualTo(trimConfiguration(config1.cloneOptions(), excludeFragmentOptions));
  }

  protected static void assertConfigurationsEqual(
      BuildConfigurationValue config1, BuildConfigurationValue config2) {
    assertConfigurationsEqual(config1, config2, /* excludeFragmentOptions= */ ImmutableSet.of());
  }

  /**
   * Creates and returns a rule context that is equivalent to the one that was used to create the
   * given configured target.
   */
  protected RuleContext getRuleContext(ConfiguredTarget target) throws Exception {
    return view.getRuleContextForTesting(reporter, target, new StubAnalysisEnvironment());
  }

  /**
   * Creates and returns a rule context to use for Starlark tests that is equivalent to the one that
   * was used to create the given configured target.
   */
  protected RuleContext getRuleContextForStarlark(ConfiguredTarget target) throws Exception {
    // TODO(bazel-team): we need this horrible workaround because CachingAnalysisEnvironment
    // only works with StoredErrorEventListener despite the fact it accepts the interface
    // ErrorEventListener, so it's not possible to create it with reporter.
    // See BuildView.getRuleContextForTesting().
    StoredEventHandler eventHandler =
        new StoredEventHandler() {
          @Override
          public synchronized void handle(Event e) {
            super.handle(e);
            reporter.handle(e);
          }
        };
    return view.getRuleContextForTesting(target, eventHandler);
  }

  /**
   * Allows access to the prerequisites of a configured target. This is currently used in some tests
   * to reach into the internals of RuleCT for white box testing. In principle, this should not be
   * used; instead tests should only assert on properties of the exposed provider instances and / or
   * the action graph.
   */
  protected List<? extends TransitiveInfoCollection> getPrerequisites(
      ConfiguredTarget target, String attributeName) throws Exception {
    return Lists.transform(
        getRuleContext(target).getPrerequisiteConfiguredTargets(attributeName),
        ConfiguredTargetAndData::getConfiguredTarget);
  }

  /**
   * Allows access to the prerequisites of a configured target. This is currently used in some tests
   * to reach into the internals of RuleCT for white box testing. In principle, this should not be
   * used; instead tests should only assert on properties of the exposed provider instances and / or
   * the action graph.
   */
  protected <C extends TransitiveInfoProvider> Iterable<C> getPrerequisites(
      ConfiguredTarget target, String attributeName, Class<C> classType) throws Exception {
    return AnalysisUtils.getProviders(getPrerequisites(target, attributeName), classType);
  }

  /**
   * Allows access to the prerequisites of a configured target. This is currently used in some tests
   * to reach into the internals of RuleCT for white box testing. In principle, this should not be
   * used; instead tests should only assert on properties of the exposed provider instances and / or
   * the action graph.
   */
  protected ImmutableList<Artifact> getPrerequisiteArtifacts(
      ConfiguredTarget target, String attributeName) throws Exception {
    Set<Artifact> result = new LinkedHashSet<>();
    for (FileProvider provider : getPrerequisites(target, attributeName, FileProvider.class)) {
      result.addAll(provider.getFilesToBuild().toList());
    }
    return ImmutableList.copyOf(result);
  }

  /**
   * Retrieves Starlark provider from a configured target.
   *
   * <p>Assuming that the provider is defined in the same bzl file as the rule.
   */
  protected StarlarkInfo getStarlarkProvider(ConfiguredTarget target, String providerSymbol)
      throws Exception {
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                getTarget(target.getLabel())
                    .getAssociatedRule()
                    .getRuleClassObject()
                    .getRuleDefinitionEnvironmentLabel()),
            providerSymbol);
    return (StarlarkInfo) target.get(key);
  }

  protected ActionGraph getActionGraph() {
    return skyframeExecutor.getActionGraph(reporter);
  }

  /** Returns all arguments used by the action. */
  protected final ImmutableList<String> allArgsForAction(SpawnAction action) throws Exception {
    ImmutableList.Builder<String> args = new ImmutableList.Builder<>();
    ImmutableList<CommandLineAndParamFileInfo> commandLines = action.getCommandLines().unpack();
    for (CommandLineAndParamFileInfo pair : commandLines.subList(1, commandLines.size())) {
      args.addAll(pair.commandLine.arguments());
    }
    return args.build();
  }

  /** Locates the first parameter file used by the action and returns its command line. */
  @Nullable
  protected final CommandLine paramFileCommandLineForAction(Action action) {
    if (action instanceof SpawnAction spawnAction) {
      CommandLines commandLines = spawnAction.getCommandLines();
      for (CommandLineAndParamFileInfo pair : commandLines.unpack()) {
        if (pair.paramFileInfo != null) {
          return pair.commandLine;
        }
      }
    }
    ParameterFileWriteAction parameterFileWriteAction = paramFileWriteActionForAction(action);
    return parameterFileWriteAction != null ? parameterFileWriteAction.getCommandLine() : null;
  }

  /** Locates the first parameter file used by the action and returns its args. */
  @Nullable
  protected final Iterable<String> paramFileArgsForAction(Action action)
      throws CommandLineExpansionException, InterruptedException {
    CommandLine commandLine = paramFileCommandLineForAction(action);
    return commandLine != null ? commandLine.arguments() : null;
  }

  /**
   * Locates the first parameter file used by the action and returns its args.
   *
   * <p>If no param file is used, return the action's arguments.
   */
  @Nullable
  protected final Iterable<String> paramFileArgsOrActionArgs(CommandAction action)
      throws CommandLineExpansionException, InterruptedException {
    CommandLine commandLine = paramFileCommandLineForAction(action);
    return commandLine != null ? commandLine.arguments() : action.getArguments();
  }

  /** Locates the first parameter file used by the action and returns its contents. */
  @Nullable
  protected final String paramFileStringContentsForAction(Action action)
      throws CommandLineExpansionException, InterruptedException, IOException {
    if (action instanceof SpawnAction spawnAction) {
      CommandLines commandLines = spawnAction.getCommandLines();
      for (CommandLineAndParamFileInfo pair : commandLines.unpack()) {
        if (pair.paramFileInfo != null) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ParameterFile.writeParameterFile(
              out, pair.commandLine.arguments(), pair.paramFileInfo.getFileType());
          return out.toString(StandardCharsets.ISO_8859_1);
        }
      }
    }
    ParameterFileWriteAction parameterFileWriteAction = paramFileWriteActionForAction(action);
    return parameterFileWriteAction != null ? parameterFileWriteAction.getStringContents() : null;
  }

  @Nullable
  protected ParameterFileWriteAction paramFileWriteActionForAction(Action action) {
    for (Artifact input : action.getInputs().toList()) {
      if (!(input instanceof SpecialArtifact)) {
        Action generatingAction = getGeneratingAction(input);
        if (generatingAction instanceof ParameterFileWriteAction parameterFileWriteAction) {
          return parameterFileWriteAction;
        }
      }
    }
    return null;
  }

  protected final ActionAnalysisMetadata getGeneratingActionAnalysisMetadata(Artifact artifact) {
    Preconditions.checkNotNull(artifact);
    ActionAnalysisMetadata actionAnalysisMetadata =
        mutableActionGraph.getGeneratingAction(artifact);

    if (actionAnalysisMetadata == null) {
      if (artifact.isSourceArtifact() || !((DerivedArtifact) artifact).hasGeneratingActionKey()) {
        return null;
      }
      actionAnalysisMetadata = getActionGraph().getGeneratingAction(artifact);
    }

    return actionAnalysisMetadata;
  }

  protected Action getGeneratingAction(ConfiguredTarget target, String outputName) {
    NestedSet<Artifact> filesToBuild = getFilesToBuild(target);
    return getGeneratingAction(outputName, filesToBuild, "filesToBuild");
  }

  private Action getGeneratingAction(
      String outputName, NestedSet<Artifact> filesToBuild, String providerName) {
    return getGeneratingAction(findArtifactNamed(outputName, filesToBuild, providerName));
  }

  protected final Action getGeneratingAction(Artifact artifact) {
    ActionAnalysisMetadata action = getGeneratingActionAnalysisMetadata(artifact);

    if (action != null) {
      Preconditions.checkState(
          action instanceof Action, "%s is not a proper Action object", action.prettyPrint());
      return (Action) action;
    } else {
      return null;
    }
  }

  protected RunfilesTree runfilesTreeFor(TestRunnerAction testRunnerAction) throws Exception {
    Artifact runfilesTreeArtifact = testRunnerAction.getRunfilesTree();
    RunfilesTreeAction runfilesTreeAction =
        (RunfilesTreeAction) getGeneratingAction(runfilesTreeArtifact);
    return runfilesTreeAction.getRunfilesTree();
  }

  protected FakeActionInputFileCache inputMetadataFor(TestRunnerAction testRunnerAction)
      throws Exception {
    FakeActionInputFileCache result = new FakeActionInputFileCache();
    result.putRunfilesTree(testRunnerAction.getRunfilesTree(), runfilesTreeFor(testRunnerAction));
    return result;
  }

  private static Artifact findArtifactNamed(
      String name, NestedSet<Artifact> artifacts, Object context) {
    return artifacts.toList().stream()
        .filter(artifactNamed(name))
        .findFirst()
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    String.format(
                        "Artifact named '%s' not found in %s (%s)", name, context, artifacts)));
  }

  protected Action getGeneratingActionInOutputGroup(
      ConfiguredTarget target, String outputName, String outputGroupName) {
    NestedSet<Artifact> outputGroup = OutputGroupInfo.get(target).getOutputGroup(outputGroupName);
    return getGeneratingAction(outputName, outputGroup, "outputGroup/" + outputGroupName);
  }

  /**
   * Returns the SpawnAction that generates an artifact. Implicitly assumes the action is a
   * SpawnAction.
   */
  protected final SpawnAction getGeneratingSpawnAction(Artifact artifact) {
    return (SpawnAction) getGeneratingAction(artifact);
  }

  protected SpawnAction getGeneratingSpawnAction(ConfiguredTarget target, String outputName) {
    return getGeneratingSpawnAction(
        findArtifactNamed(outputName, getFilesToBuild(target), target.getLabel()));
  }

  protected final List<String> getGeneratingSpawnActionArgs(Artifact artifact)
      throws CommandLineExpansionException, InterruptedException {
    SpawnAction a = getGeneratingSpawnAction(artifact);
    return a.getArguments();
  }

  protected ActionsTestUtil actionsTestUtil() {
    return new ActionsTestUtil(getActionGraph());
  }

  // Get a MutableActionGraph for testing purposes.
  protected MutableActionGraph getMutableActionGraph() {
    return mutableActionGraph;
  }

  /**
   * Returns the ConfiguredTarget for the specified label, configured for the "build" (aka "target")
   * configuration. If the label corresponds to a target with a top-level configuration transition,
   * that transition is applied to the given config in the returned ConfiguredTarget.
   *
   * <p>May return null on error; see {@link #getConfiguredTarget(Label, BuildConfigurationValue)}.
   */
  @Nullable
  public ConfiguredTarget getConfiguredTarget(String label) throws LabelSyntaxException {
    return getConfiguredTarget(label, targetConfig);
  }

  /**
   * Returns the ConfiguredTarget for the specified label, using the given build configuration. If
   * the label corresponds to a target with a top-level configuration transition, that transition is
   * applied to the given config in the returned ConfiguredTarget.
   *
   * <p>May return null on error; see {@link #getConfiguredTarget(Label, BuildConfigurationValue)}.
   */
  @Nullable
  protected ConfiguredTarget getConfiguredTarget(String label, BuildConfigurationValue config)
      throws LabelSyntaxException {
    return getConfiguredTarget(Label.parseCanonical(label), config);
  }

  /**
   * Returns the ConfiguredTarget for the specified label, using the given build configuration. If
   * the label corresponds to a target with a top-level configuration transition, that transition is
   * applied to the given config in the returned ConfiguredTarget.
   *
   * <p>If the evaluation of the SkyKey corresponding to the configured target fails, this method
   * may return null. In that case, use a debugger to inspect the {@link ErrorInfo} for the
   * evaluation, which is produced by the {@link MemoizingEvaluator#getExistingValue} call in {@link
   * SkyframeExecutor#getConfiguredTargetForTesting}. See also b/26382502.
   *
   * @throws AssertionError if the target cannot be transitioned into with the given configuration
   */
  // TODO(bazel-team): Should we work around b/26382502 by asserting here that the result is not
  // null?
  @Nullable
  protected ConfiguredTarget getConfiguredTarget(Label label, BuildConfigurationValue config) {
    try {
      return view.getConfiguredTargetForTesting(reporter, label, config);
    } catch (InvalidConfigurationException | InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Returns a ConfiguredTargetAndData for the specified label, using the given build configuration.
   */
  protected ConfiguredTargetAndData getConfiguredTargetAndData(
      Label label, BuildConfigurationValue config)
      throws StarlarkTransition.TransitionException,
          InvalidConfigurationException,
          InterruptedException {
    return view.getConfiguredTargetAndDataForTesting(reporter, label, config);
  }

  /**
   * Returns the ConfiguredTargetAndData for the specified label. If the label corresponds to a
   * target with a top-level configuration transition, that transition is applied to the given
   * config in the ConfiguredTargetAndData's ConfiguredTarget.
   */
  public ConfiguredTargetAndData getConfiguredTargetAndData(String label)
      throws LabelSyntaxException,
          StarlarkTransition.TransitionException,
          InvalidConfigurationException,
          InterruptedException {
    return getConfiguredTargetAndData(Label.parseCanonical(label), targetConfig);
  }

  /**
   * Returns the ConfiguredTarget for the specified file label, configured for the "build" (aka
   * "target") configuration.
   */
  protected FileConfiguredTarget getFileConfiguredTarget(String label) throws LabelSyntaxException {
    return (FileConfiguredTarget) getConfiguredTarget(label, targetConfig);
  }

  /**
   * Returns the Artifact for the specified label, configured for the "build" (aka "target")
   * configuration.
   */
  protected Artifact getArtifact(String label) throws LabelSyntaxException {
    ConfiguredTarget target = getConfiguredTarget(label, targetConfig);
    if (target instanceof FileConfiguredTarget fileConfiguredTarget) {
      return fileConfiguredTarget.getArtifact();
    } else {
      return getFilesToBuild(target).getSingleton();
    }
  }

  /**
   * Returns the ConfiguredTarget for the specified label, configured for the "exec" configuration.
   */
  protected ConfiguredTarget getExecConfiguredTarget(String label) throws LabelSyntaxException {
    return getConfiguredTarget(label, getExecConfiguration());
  }

  /**
   * Returns the ConfiguredTarget for the specified file label, configured for the "exec"
   * configuration.
   */
  protected FileConfiguredTarget getExecFileConfiguredTarget(String label)
      throws LabelSyntaxException {
    return (FileConfiguredTarget) getExecConfiguredTarget(label);
  }

  /** Returns the configurations in which the given label has already been configured. */
  protected Set<BuildConfigurationKey> getKnownConfigurations(String label) throws Exception {
    Label parsed = Label.parseCanonicalUnchecked(label);
    Set<BuildConfigurationKey> cts = new HashSet<>();
    for (Map.Entry<SkyKey, SkyValue> e :
        skyframeExecutor.getEvaluator().getDoneValues().entrySet()) {
      if (!(e.getKey() instanceof ConfiguredTargetKey ctKey)) {
        continue;
      }
      if (parsed.equals(ctKey.getLabel())) {
        cts.add(ctKey.getConfigurationKey());
      }
    }
    return cts;
  }

  /**
   * Returns the {@link ConfiguredAspect} with the given label. For example: {@code
   * //my:defs.bzl%my_aspect}.
   *
   * <p>Assumes only one configured aspect exists for this label. If this isn't true, or you need
   * finer grained selection for different configurations, you'll need to expand this method.
   */
  protected ConfiguredAspect getAspect(String label) throws Exception {
    return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
        .filter(
            e ->
                e.getKey() instanceof AspectKey
                    && ((AspectKey) e.getKey()).getAspectName().equals(label))
        .map(e -> (AspectValue) e.getValue())
        .collect(onlyElement());
  }

  /**
   * Rewrites the MODULE.bazel file
   *
   * <p>Triggers Skyframe to reinitialize everything.
   */
  public void rewriteModuleDotBazel(String... lines) throws Exception {
    scratch.overwriteFile("MODULE.bazel", lines);
    invalidatePackages();
  }

  /**
   * Create and return a configured scratch rule.
   *
   * @param packageName the package name of the rule.
   * @param ruleName the name of the rule.
   * @param lines the text of the rule.
   * @return the configured target instance for the created rule.
   */
  protected ConfiguredTarget scratchConfiguredTarget(
      String packageName, String ruleName, String... lines) throws Exception {
    return scratchConfiguredTarget(packageName, ruleName, targetConfig, lines);
  }

  /**
   * Create and return a configured scratch rule.
   *
   * @param packageName the package name of the rule.
   * @param ruleName the name of the rule.
   * @param config the configuration to use to construct the configured rule.
   * @param lines the text of the rule.
   * @return the configured target instance for the created rule.
   */
  protected ConfiguredTarget scratchConfiguredTarget(
      String packageName, String ruleName, BuildConfigurationValue config, String... lines)
      throws Exception {
    ConfiguredTargetAndData ctad =
        scratchConfiguredTargetAndData(packageName, ruleName, config, lines);
    return ctad == null ? null : ctad.getConfiguredTarget();
  }

  /**
   * Creates and returns a configured scratch rule and its data.
   *
   * @param packageName the package name of the rule.
   * @param rulename the name of the rule.
   * @param lines the text of the rule.
   * @return the configured tatarget and target instance for the created rule.
   */
  protected ConfiguredTargetAndData scratchConfiguredTargetAndData(
      String packageName, String rulename, String... lines) throws Exception {
    return scratchConfiguredTargetAndData(packageName, rulename, targetConfig, lines);
  }

  /**
   * Creates and returns a configured scratch rule and its data.
   *
   * @param packageName the package name of the rule.
   * @param ruleName the name of the rule.
   * @param config the configuration to use to construct the configured rule.
   * @param lines the text of the rule.
   * @return the ConfiguredTargetAndData instance for the created rule.
   */
  protected ConfiguredTargetAndData scratchConfiguredTargetAndData(
      String packageName, String ruleName, BuildConfigurationValue config, String... lines)
      throws Exception {
    Target rule = scratchRule(packageName, ruleName, lines);
    return view.getConfiguredTargetAndDataForTesting(reporter, rule.getLabel(), config);
  }

  /**
   * Create and return a scratch rule.
   *
   * @param packageName the package name of the rule.
   * @param ruleName the name of the rule.
   * @param lines the text of the rule.
   * @return the rule instance for the created rule.
   */
  protected Rule scratchRule(String packageName, String ruleName, String... lines)
      throws Exception {
    // Allow to create the BUILD file also in the top package.
    String buildFilePathString = packageName.isEmpty() ? "BUILD" : packageName + "/BUILD";
    scratch.file(buildFilePathString, lines);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        new ModifiedFileSet.Builder().modify(PathFragment.create(buildFilePathString)).build(),
        Root.fromPath(rootDirectory));
    return (Rule) getTarget("//" + packageName + ":" + ruleName);
  }

  /**
   * Check that configuration of the target named 'ruleName' in the specified BUILD file fails with
   * an error message containing 'expectedErrorMessage'.
   *
   * @param packageName the package name of the generated BUILD file
   * @param ruleName the rule name for the rule in the generated BUILD file
   * @param expectedErrorMessage the expected error message.
   * @param lines the text of the rule.
   * @return the found error.
   */
  protected Event checkError(
      String packageName, String ruleName, String expectedErrorMessage, String... lines)
      throws Exception {
    eventCollector.clear();
    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget target = scratchConfiguredTarget(packageName, ruleName, lines);
    if (target != null) {
      assertWithMessage(
              "Rule '" + "//" + packageName + ":" + ruleName + "' did not contain an error")
          .that(view.hasErrors(target))
          .isTrue();
    }
    return assertContainsEvent(expectedErrorMessage);
  }

  /**
   * Check that configuration of the target named 'ruleName' in the specified BUILD file fails with
   * an error message matching 'expectedErrorPattern'.
   *
   * @param packageName the package name of the generated BUILD file
   * @param ruleName the rule name for the rule in the generated BUILD file
   * @param expectedErrorPattern a regex that matches the expected error.
   * @param lines the text of the rule.
   * @return the found error.
   */
  protected Event checkError(
      String packageName, String ruleName, Pattern expectedErrorPattern, String... lines)
      throws Exception {
    eventCollector.clear();
    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget target = scratchConfiguredTarget(packageName, ruleName, lines);
    if (target != null) {
      assertWithMessage(
              "Rule '" + "//" + packageName + ":" + ruleName + "' did not contain an error")
          .that(view.hasErrors(target))
          .isTrue();
    }
    return assertContainsEvent(expectedErrorPattern);
  }

  /**
   * Check that configuration of the target named 'label' fails with an error message containing
   * 'expectedErrorMessage'.
   *
   * @param label the target name to test
   * @param expectedErrorMessage the expected error message.
   * @return the found error.
   */
  protected Event checkError(String label, String expectedErrorMessage) throws Exception {
    eventCollector.clear();
    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget target = getConfiguredTarget(label);
    if (target != null) {
      assertWithMessage("Rule '" + label + "' did not contain an error")
          .that(view.hasErrors(target))
          .isTrue();
    }
    return assertContainsEvent(expectedErrorMessage);
  }

  /**
   * Checks whether loading the given target results in the specified error message.
   *
   * @param target the name of the target.
   * @param expectedErrorMessage the expected error message.
   */
  protected void checkLoadingPhaseError(String target, String expectedErrorMessage) {
    reporter.removeHandler(failFastHandler);
    // The error happens during the loading of the Starlark file so checkError doesn't work here
    assertThrows(Exception.class, () -> getTarget(target));
    assertContainsEvent(expectedErrorMessage);
  }

  /**
   * Check that configuration of the target named 'ruleName' in the specified BUILD file reports a
   * warning message ending in 'expectedWarningMessage', and that no errors were reported.
   *
   * @param packageName the package name of the generated BUILD file
   * @param ruleName the rule name for the rule in the generated BUILD file
   * @param expectedWarningMessage the expected warning message.
   * @param lines the text of the rule.
   * @return the found error.
   */
  protected Event checkWarning(
      String packageName, String ruleName, String expectedWarningMessage, String... lines)
      throws Exception {
    eventCollector.clear();
    ConfiguredTarget target = scratchConfiguredTarget(packageName, ruleName, lines);
    assertWithMessage("Rule '" + "//" + packageName + ":" + ruleName + "' did contain an error")
        .that(view.hasErrors(target))
        .isFalse();
    return assertContainsEvent(expectedWarningMessage);
  }

  /**
   * Given a collection of Artifacts, returns a corresponding set of strings of the form "[root]
   * [relpath]", such as "bin x/libx.a". Such strings make assertions easier to write.
   *
   * <p>The returned set preserves the order of the input.
   */
  protected Set<String> artifactsToStrings(NestedSet<? extends Artifact> artifacts) {
    return artifactsToStrings(artifacts.toList());
  }

  /**
   * Given a collection of Artifacts, returns a corresponding set of strings of the form "[root]
   * [relpath]", such as "bin x/libx.a". Such strings make assertions easier to write.
   *
   * <p>The returned set preserves the order of the input.
   */
  protected Set<String> artifactsToStrings(Iterable<? extends Artifact> artifacts) {
    return AnalysisTestUtil.artifactsToStrings(targetConfig, artifacts);
  }

  /**
   * Given a list of PathFragments, returns a corresponding list of strings. Such strings make
   * assertions easier to write.
   */
  protected static ImmutableList<String> pathfragmentsToStrings(List<PathFragment> pathFragments) {
    return pathFragments.stream().map(PathFragment::toString).collect(toImmutableList());
  }

  protected Artifact getSourceArtifact(PathFragment rootRelativePath, Root root) {
    return view.getArtifactFactory().getSourceArtifact(rootRelativePath, root);
  }

  protected Artifact getSourceArtifact(String name, ArtifactOwner owner) {
    return view.getArtifactFactory()
        .getSourceArtifact(PathFragment.create(name), Root.fromPath(rootDirectory), owner);
  }

  protected Artifact getSourceArtifact(String name) {
    return getSourceArtifact(PathFragment.create(name), Root.fromPath(rootDirectory));
  }

  /**
   * Gets a derived artifact, creating it if necessary. {@code ArtifactOwner} should be a genuine
   * {@link ConfiguredTargetKey} corresponding to a {@link ConfiguredTarget}. If called from a test
   * that does not exercise the analysis phase, the convenience methods {@link
   * #getBinArtifactWithNoOwner} or {@link #getGenfilesArtifactWithNoOwner} should be used instead.
   */
  protected final Artifact.DerivedArtifact getDerivedArtifact(
      PathFragment rootRelativePath, ArtifactRoot root, ArtifactOwner owner) {
    if (owner instanceof ActionLookupKey) {
      SkyValue skyValue;
      try {
        skyValue = skyframeExecutor.getEvaluator().getExistingValue(((ActionLookupKey) owner));
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      if (skyValue instanceof ActionLookupValue actionLookupValue) {
        for (ActionAnalysisMetadata action : actionLookupValue.getActions()) {
          for (Artifact output : action.getOutputs()) {
            if (output.getRootRelativePath().equals(rootRelativePath)
                && output.getRoot().equals(root)) {
              return (Artifact.DerivedArtifact) output;
            }
          }
        }
      }
    }
    // Fall back: some tests don't actually need an artifact with an owner.
    // TODO(janakr): the tests that are passing in nonsense here should be changed.
    return view.getArtifactFactory().getDerivedArtifact(rootRelativePath, root, owner);
  }

  /**
   * Gets a Tree Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getBinDirectory} corresponding to the package of {@code owner}. So to
   * specify a file foo/foo.o owned by target //foo:foo, {@code packageRelativePath} should just be
   * "foo.o".
   */
  protected final Artifact getTreeArtifact(String packageRelativePath, ConfiguredTarget owner) {
    ActionLookupKey actionLookupKey = ConfiguredTargetKey.fromConfiguredTarget(owner);
    return getDerivedArtifact(
        owner.getLabel().getPackageFragment().getRelative(packageRelativePath),
        getConfiguration(owner).getBinDirectory(RepositoryName.MAIN),
        actionLookupKey);
  }

  /**
   * Gets a derived Artifact for testing with path of the form
   * root/owner.getPackageFragment()/packageRelativePath.
   *
   * @see #getDerivedArtifact(PathFragment, ArtifactRoot, ArtifactOwner)
   */
  private Artifact getPackageRelativeDerivedArtifact(
      String packageRelativePath, ArtifactRoot root, ArtifactOwner owner) {
    return getDerivedArtifact(
        owner.getLabel().getPackageFragment().getRelative(packageRelativePath), root, owner);
  }

  /** Returns the input {@link Artifact}s to the given {@link Action} with the given exec paths. */
  protected static List<Artifact> getInputs(Action owner, Collection<String> execPaths) {
    Set<String> expectedPaths = new HashSet<>(execPaths);
    List<Artifact> result = new ArrayList<>();
    for (Artifact output : owner.getInputs().toList()) {
      if (expectedPaths.remove(output.getExecPathString())) {
        result.add(output);
      }
    }
    assertWithMessage("expected paths not found in: %s", Artifact.asExecPaths(owner.getInputs()))
        .that(expectedPaths)
        .isEmpty();
    return result;
  }

  /**
   * Gets a derived Artifact for testing in the {@link BuildConfigurationValue#getBinDirectory}.
   * This method should only be used for tests that do no analysis, and so there is no
   * ConfiguredTarget to own this artifact. If the test runs the analysis phase, {@link
   * #getBinArtifact(String, ConfiguredTarget)} or its convenience methods should be used instead.
   */
  protected Artifact.DerivedArtifact getBinArtifactWithNoOwner(String rootRelativePath) {
    return getDerivedArtifact(
        PathFragment.create(rootRelativePath),
        targetConfig.getBinDirectory(RepositoryName.MAIN),
        ActionsTestUtil.NULL_ARTIFACT_OWNER);
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getBinDirectory} corresponding to the package of {@code owner}. So to
   * specify a file foo/foo.o owned by target //foo:foo, {@code packageRelativePath} should just be
   * "foo.o".
   */
  protected final Artifact getBinArtifact(String packageRelativePath, ConfiguredTarget owner) {
    try {
      return getPackageRelativeDerivedArtifact(
          packageRelativePath,
          getRuleContext(owner).getBinDirectory(),
          ConfiguredTargetKey.fromConfiguredTarget(owner));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getBinDirectory} corresponding to the package of {@code owner}, where
   * the given artifact belongs to the given ConfiguredTarget together with the given Aspect. So to
   * specify a file foo/foo.o owned by target //foo:foo with an aspect from FooAspect, {@code
   * packageRelativePath} should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
   * method is necessary when an Aspect of the target, not the target itself, is creating an
   * Artifact.
   */
  protected Artifact getBinArtifact(
      String packageRelativePath, ConfiguredTarget owner, AspectClass creatingAspectFactory) {
    return getBinArtifact(
        packageRelativePath, owner, creatingAspectFactory, AspectParameters.EMPTY);
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getBinDirectory} corresponding to the package of {@code owner}, where
   * the given artifact belongs to the given ConfiguredTarget together with the given Aspect. So to
   * specify a file foo/foo.o owned by target //foo:foo with an aspect from FooAspect, {@code
   * packageRelativePath} should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
   * method is necessary when an Aspect of the target, not the target itself, is creating an
   * Artifact.
   */
  protected Artifact getBinArtifact(
      String packageRelativePath,
      ConfiguredTarget owner,
      AspectClass creatingAspectFactory,
      AspectParameters parameters) {
    try {
      return getPackageRelativeDerivedArtifact(
          packageRelativePath,
          getRuleContext(owner).getBinDirectory(),
          AspectKeyCreator.createAspectKey(
              AspectDescriptor.of(creatingAspectFactory, parameters),
              ConfiguredTargetKey.builder()
                  .setLabel(owner.getLabel())
                  .setConfiguration(getConfiguration(owner))
                  .build()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Gets a derived Artifact for testing in the {@link
   * BuildConfigurationValue#getGenfilesDirectory}. This method should only be used for tests that
   * do no analysis, and so there is no ConfiguredTarget to own this artifact. If the test runs the
   * analysis phase, {@link #getGenfilesArtifact(String, ConfiguredTarget)} or its convenience
   * methods should be used instead.
   */
  protected Artifact getGenfilesArtifactWithNoOwner(String rootRelativePath) {
    return getDerivedArtifact(
        PathFragment.create(rootRelativePath),
        targetConfig.getGenfilesDirectory(RepositoryName.MAIN),
        ActionsTestUtil.NULL_ARTIFACT_OWNER);
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getGenfilesDirectory} corresponding to the package of {@code owner}. So
   * to specify a file foo/foo.o owned by target //foo:foo, {@code packageRelativePath} should just
   * be "foo.o".
   */
  protected Artifact getGenfilesArtifact(String packageRelativePath, String owner) {
    BuildConfigurationValue config = getConfiguration(owner);
    return getGenfilesArtifact(
        packageRelativePath,
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked(owner))
            .setConfiguration(config)
            .build(),
        config);
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getGenfilesDirectory} corresponding to the package of {@code owner}. So
   * to specify a file foo/foo.o owned by target //foo:foo, {@code packageRelativePath} should just
   * be "foo.o".
   */
  protected Artifact getGenfilesArtifact(String packageRelativePath, ConfiguredTarget owner) {
    ConfiguredTargetKey configKey = ConfiguredTargetKey.fromConfiguredTarget(owner);
    BuildConfigurationValue configuration =
        skyframeExecutor.getConfiguration(reporter, configKey.getConfigurationKey());
    return getGenfilesArtifact(packageRelativePath, configKey, configuration);
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getGenfilesDirectory} corresponding to the package of {@code owner},
   * where the given artifact belongs to the given ConfiguredTarget together with the given Aspect.
   * So to specify a file foo/foo.o owned by target //foo:foo with an apsect from FooAspect, {@code
   * packageRelativePath} should just be "foo.o", and aspectOfOwner should be FooAspect.class. This
   * method is necessary when an Apsect of the target, not the target itself, is creating an
   * Artifact.
   */
  protected Artifact getGenfilesArtifact(
      String packageRelativePath, ConfiguredTarget owner, AspectClass creatingAspectFactory) {
    return getGenfilesArtifact(
        packageRelativePath, owner, creatingAspectFactory, AspectParameters.EMPTY);
  }

  protected Artifact getGenfilesArtifact(
      String packageRelativePath,
      ConfiguredTarget owner,
      AspectClass creatingAspectFactory,
      AspectParameters params) {
    return getPackageRelativeDerivedArtifact(
        packageRelativePath,
        getConfiguration(owner).getGenfilesDirectory(owner.getLabel().getRepository()),
        getOwnerForAspect(owner, creatingAspectFactory, params));
  }

  /**
   * Gets a derived Artifact for testing in the subdirectory of the {@link
   * BuildConfigurationValue#getGenfilesDirectory} corresponding to the package of {@code owner}. So
   * to specify a file foo/foo.o owned by target //foo:foo, {@code packageRelativePath} should just
   * be "foo.o".
   */
  private Artifact getGenfilesArtifact(
      String packageRelativePath, ArtifactOwner owner, BuildConfigurationValue config) {
    return getPackageRelativeDerivedArtifact(
        packageRelativePath, config.getGenfilesDirectory(RepositoryName.MAIN), owner);
  }

  protected AspectKey getOwnerForAspect(
      ConfiguredTarget owner, AspectClass creatingAspectFactory, AspectParameters params) {
    return AspectKeyCreator.createAspectKey(
        AspectDescriptor.of(creatingAspectFactory, params),
        ConfiguredTargetKey.builder()
            .setLabel(owner.getLabel())
            .setConfiguration(getConfiguration(owner))
            .build());
  }

  /**
   * @return a shared artifact at the binary-root relative path {@code rootRelativePath} owned by
   *     {@code owner}.
   * @param rootRelativePath the binary-root relative path of the artifact.
   * @param owner the artifact's owner.
   */
  protected Artifact getSharedArtifact(String rootRelativePath, ConfiguredTarget owner) {
    try {
      return getDerivedArtifact(
          PathFragment.create(rootRelativePath),
          getRuleContext(owner).getBinDirectory(),
          ConfiguredTargetKey.fromConfiguredTarget(owner));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected Action getGeneratingActionForLabel(String label) throws Exception {
    return getGeneratingAction(getArtifact(label));
  }

  protected Path getOutputPath() {
    return directories.getOutputPath(ruleClassProvider.getRunfilesPrefix());
  }

  protected String getRelativeOutputPath() {
    return directories.getRelativeOutputPath();
  }

  /**
   * Verifies whether the rule checks the 'srcs' attribute validity.
   *
   * <p>At the call site it expects the {@code packageName} to contain:
   *
   * <ol>
   *   <li>{@code :gvalid} - genrule that outputs a valid file
   *   <li>{@code :ginvalid} - genrule that outputs an invalid file
   *   <li>{@code :gmix} - genrule that outputs a mix of valid and invalid files
   *   <li>{@code :valid} - rule of type {@code ruleType} that has a valid file, {@code :gvalid} and
   *       {@code :gmix} in the srcs
   *   <li>{@code :invalid} - rule of type {@code ruleType} that has an invalid file, {@code
   *       :ginvalid} in the srcs
   *   <li>{@code :mix} - rule of type {@code ruleType} that has a valid and an invalid file in the
   *       srcs
   * </ol>
   *
   * @param packageName the package where the rules under test are located
   * @param ruleType rules under test types
   * @param expectedTypes expected file types
   */
  protected void assertSrcsValidityForRuleType(
      String packageName, String ruleType, String expectedTypes) throws Exception {
    reporter.removeHandler(failFastHandler);
    String descriptionSingle = ruleType + " srcs file (expected " + expectedTypes + ")";
    String descriptionPlural = ruleType + " srcs files (expected " + expectedTypes + ")";
    String descriptionPluralFile = "(expected " + expectedTypes + ")";
    assertSrcsValidity(
        ruleType,
        packageName + ":valid",
        false,
        "need at least one " + descriptionSingle,
        "'" + packageName + ":gvalid' does not produce any " + descriptionPlural,
        "'" + packageName + ":gmix' does not produce any " + descriptionPlural);
    assertSrcsValidity(
        ruleType,
        packageName + ":invalid",
        true,
        "source file '" + packageName + ":a.foo' is misplaced here " + descriptionPluralFile,
        "'" + packageName + ":ginvalid' does not produce any " + descriptionPlural);
    assertSrcsValidity(
        ruleType,
        packageName + ":mix",
        true,
        "'" + packageName + ":a.foo' does not produce any " + descriptionPlural);
  }

  protected void assertSrcsValidity(
      String ruleType, String targetName, boolean expectedError, String... expectedMessages)
      throws Exception {
    ConfiguredTarget target = getConfiguredTarget(targetName);
    if (expectedError) {
      assertThat(view.hasErrors(target)).isTrue();
      for (String expectedMessage : expectedMessages) {
        String message =
            "in srcs attribute of " + ruleType + " rule " + targetName + ": " + expectedMessage;
        assertContainsEvent(message);
      }
    } else {
      assertThat(view.hasErrors(target)).isFalse();
      for (String expectedMessage : expectedMessages) {
        String message =
            "in srcs attribute of "
                + ruleType
                + " rule "
                + target.getLabel()
                + ": "
                + expectedMessage;
        assertDoesNotContainEvent(message);
      }
    }
  }

  protected static ConfiguredAttributeMapper getMapperFromConfiguredTargetAndTarget(
      ConfiguredTargetAndData ctad) {
    return ctad.getAttributeMapperForTesting();
  }

  protected static ImmutableList<String> actionInputsToPaths(
      NestedSet<? extends ActionInput> actionInputs) {
    return ImmutableList.copyOf(
        Lists.transform(actionInputs.toList(), ActionInput::getExecPathString));
  }

  /**
   * Utility method for asserting that the contents of one collection are the same as those in a
   * second plus some set of common elements.
   */
  protected void assertSameContentsWithCommonElements(
      Iterable<String> artifacts, String[] expectedInputs, Iterable<String> common) {
    assertThat(artifacts)
        .containsExactlyElementsIn(Iterables.concat(Lists.newArrayList(expectedInputs), common));
  }

  /**
   * Utility method for asserting that a list contains the elements of a sublist. This is useful for
   * checking that a list of arguments contains a particular set of arguments.
   */
  protected static void assertContainsSublist(List<String> list, List<String> sublist) {
    assertContainsSublist(null, list, sublist);
  }

  /**
   * Utility method for asserting that a list contains the elements of a sublist. This is useful for
   * checking that a list of arguments contains a particular set of arguments.
   */
  protected static void assertContainsSublist(
      String message, List<String> list, List<String> sublist) {
    if (Collections.indexOfSubList(list, sublist) == -1) {
      fail(
          String.format(
              "%sexpected: <%s> to contain sublist: <%s>",
              message == null ? "" : (message + ' '), list, sublist));
    }
  }

  protected void assertContainsSelfEdgeEvent(String label) {
    assertContainsEvent(Pattern.compile(label + " \\([a-f0-9]+\\) \\[self-edge]"));
  }

  protected static NestedSet<Artifact> collectRunfiles(ConfiguredTarget target) {
    RunfilesProvider runfilesProvider = target.getProvider(RunfilesProvider.class);
    if (runfilesProvider != null) {
      return runfilesProvider.getDefaultRunfiles().getAllArtifacts();
    } else {
      return Runfiles.EMPTY.getAllArtifacts();
    }
  }

  protected static NestedSet<Artifact> getFilesToBuild(TransitiveInfoCollection target) {
    return target.getProvider(FileProvider.class).getFilesToBuild();
  }

  /** Returns all extra actions for that target (no transitive actions), no duplicate actions. */
  protected ImmutableList<Action> getExtraActionActions(ConfiguredTarget target) {
    LinkedHashSet<Action> result = new LinkedHashSet<>();
    for (Artifact artifact : getExtraActionArtifacts(target).toList()) {
      result.add(getGeneratingAction(artifact));
    }
    return ImmutableList.copyOf(result);
  }

  protected ImmutableList<Action> getActions(String label, Class<?> actionClass) throws Exception {
    return ((RuleConfiguredTarget) getConfiguredTarget(label))
        .getActions().stream()
            .map(Action.class::cast)
            .filter(action -> action.getClass().equals(actionClass))
            .collect(toImmutableList());
  }

  protected ImmutableList<Action> getActions(String label, String mnemonic) throws Exception {
    return ((RuleConfiguredTarget) getConfiguredTarget(label))
        .getActions().stream()
            .map(Action.class::cast)
            .filter(action -> action.getMnemonic().equals(mnemonic))
            .collect(toImmutableList());
  }

  protected ImmutableList<Action> getActions(String label) throws Exception {
    return ((RuleConfiguredTarget) getConfiguredTarget(label))
        .getActions().stream().map(Action.class::cast).collect(toImmutableList());
  }

  protected static NestedSet<Artifact> getOutputGroup(
      TransitiveInfoCollection target, String outputGroup) {
    OutputGroupInfo provider = OutputGroupInfo.get(target);
    return provider == null
        ? NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        : provider.getOutputGroup(outputGroup);
  }

  protected static NestedSet<Artifact.DerivedArtifact> getExtraActionArtifacts(
      ConfiguredTarget target) {
    return target.getProvider(ExtraActionArtifactsProvider.class).getExtraActionArtifacts();
  }

  protected Artifact getExecutable(String label) throws Exception {
    return getConfiguredTarget(label).getProvider(FilesToRunProvider.class).getExecutable();
  }

  protected static Artifact getExecutable(TransitiveInfoCollection target) {
    return target.getProvider(FilesToRunProvider.class).getExecutable();
  }

  protected static NestedSet<Artifact> getFilesToRun(TransitiveInfoCollection target) {
    return target.getProvider(FilesToRunProvider.class).getFilesToRun();
  }

  protected NestedSet<Artifact> getFilesToRun(String label) throws Exception {
    return getConfiguredTarget(label).getProvider(FilesToRunProvider.class).getFilesToRun();
  }

  protected RunfilesSupport getRunfilesSupport(String label) throws Exception {
    return getConfiguredTarget(label).getProvider(FilesToRunProvider.class).getRunfilesSupport();
  }

  protected static RunfilesSupport getRunfilesSupport(TransitiveInfoCollection target) {
    return target.getProvider(FilesToRunProvider.class).getRunfilesSupport();
  }

  protected static Runfiles getDefaultRunfiles(ConfiguredTarget target) {
    return target.getProvider(RunfilesProvider.class).getDefaultRunfiles();
  }

  protected static Runfiles getDataRunfiles(ConfiguredTarget target) {
    return target.getProvider(RunfilesProvider.class).getDataRunfiles();
  }

  protected BuildConfigurationValue getTargetConfiguration() {
    return targetConfig;
  }

  protected BuildConfigurationValue getExecConfiguration() {
    return execConfig;
  }

  private BuildConfigurationValue getConfiguration(String label) {
    try {
      return getConfiguration(getConfiguredTarget(label));
    } catch (LabelSyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected final BuildConfigurationValue getConfiguration(ConfiguredTarget ct) {
    return skyframeExecutor.getConfiguration(reporter, ct.getConfigurationKey());
  }

  protected void useLoadingOptions(String... options) throws OptionsParsingException {
    customLoadingOptions = Options.parse(LoadingOptions.class, options).getOptions();
  }

  protected AnalysisResult update(String target, int loadingPhaseThreads, boolean doAnalysis)
      throws Exception {
    return update(
        ImmutableList.of(target),
        ImmutableList.of(),
        /* keepGoing= */ true, // value doesn't matter since we have only one target.
        loadingPhaseThreads,
        doAnalysis,
        new EventBus());
  }

  protected AnalysisResult update(
      List<String> targets,
      boolean keepGoing,
      int loadingPhaseThreads,
      boolean doAnalysis,
      EventBus eventBus)
      throws Exception {
    return update(
        targets, ImmutableList.of(), keepGoing, loadingPhaseThreads, doAnalysis, eventBus);
  }

  protected AnalysisResult update(
      List<String> targets,
      List<String> aspects,
      boolean keepGoing,
      int loadingPhaseThreads,
      boolean doAnalysis,
      EventBus eventBus)
      throws Exception {

    LoadingOptions loadingOptions =
        customLoadingOptions == null
            ? Options.getDefaults(LoadingOptions.class)
            : customLoadingOptions;

    AnalysisOptions viewOptions = Options.getDefaults(AnalysisOptions.class);

    TargetPatternPhaseValue loadingResult =
        skyframeExecutor.loadTargetPatternsWithFilters(
            reporter,
            targets,
            PathFragment.EMPTY_FRAGMENT,
            loadingOptions,
            loadingPhaseThreads,
            keepGoing,
            /* determineTests= */ false);
    if (!doAnalysis) {
      // TODO(bazel-team): What's supposed to happen in this case?
      return null;
    }
    return view.update(
        loadingResult,
        targetConfig.getOptions(),
        /* explicitTargetPatterns= */ ImmutableSet.of(),
        aspects,
        /* aspectsParameters= */ ImmutableMap.of(),
        viewOptions,
        keepGoing,
        loadingPhaseThreads,
        AnalysisTestUtil.TOP_LEVEL_ARTIFACT_CONTEXT,
        reporter,
        eventBus);
  }

  protected static Predicate<Artifact> artifactNamed(String name) {
    return artifact -> name.equals(artifact.prettyPrint());
  }

  /**
   * Utility method for tests. Converts an array of strings into a set of labels.
   *
   * @param strings the set of strings to be converted to labels.
   * @throws LabelSyntaxException if there are any syntax errors in the strings.
   */
  public static Set<Label> asLabelSet(String... strings) throws LabelSyntaxException {
    return asLabelSet(ImmutableList.copyOf(strings));
  }

  /**
   * Utility method for tests. Converts an array of strings into a set of labels.
   *
   * @param strings the set of strings to be converted to labels.
   * @throws LabelSyntaxException if there are any syntax errors in the strings.
   */
  public static Set<Label> asLabelSet(Iterable<String> strings) throws LabelSyntaxException {
    Set<Label> result = Sets.newTreeSet();
    for (String s : strings) {
      result.add(Label.parseCanonical(s));
    }
    return result;
  }

  protected static String getErrorMsgNoGoodFiles(
      String attrName, String ruleType, String ruleName, String depRuleName) {
    return String.format(
        "in %s attribute of %s rule %s: '%s' does not produce any %s %s files",
        attrName, ruleType, ruleName, depRuleName, ruleType, attrName);
  }

  protected static String getErrorMsgMisplacedFiles(
      String attrName, String ruleType, String ruleName, String fileName) {
    return String.format(
        "in %s attribute of %s rule %s: source file '%s' is misplaced here",
        attrName, ruleType, ruleName, fileName);
  }

  protected static String getErrorNonExistingTarget(
      String attrName, String ruleType, String ruleName, String targetName) {
    return String.format(
        "in %s attribute of %s rule %s: target '%s' does not exist",
        attrName, ruleType, ruleName, targetName);
  }

  protected static String getErrorNonExistingRule(
      String attrName, String ruleType, String ruleName, String targetName) {
    return String.format(
        "in %s attribute of %s rule %s: rule '%s' does not exist",
        attrName, ruleType, ruleName, targetName);
  }

  protected static String getErrorMsgMisplacedRules(
      String attrName, String ruleType, String ruleName, String depRuleType, String depRuleName) {
    return String.format(
        "in %s attribute of %s rule %s: %s rule '%s' is misplaced here",
        attrName, ruleType, ruleName, depRuleType, depRuleName);
  }

  protected static String getErrorMsgNonEmptyList(
      String attrName, String ruleType, String ruleName) {
    return String.format(
        "in %s attribute of %s rule %s: attribute must be non empty", attrName, ruleType, ruleName);
  }

  protected static String getErrorMsgWrongAttributeValue(String value, String... expected) {
    return String.format(
        "has to be one of %s instead of '%s'",
        StringUtil.joinEnglishListSingleQuoted(ImmutableSet.copyOf(expected)), value);
  }

  protected static String getErrorMsgMandatoryProviderMissing(
      String offendingRule, String providerName) {
    return String.format(
        "'%s' does not have mandatory providers: '%s'", offendingRule, providerName);
  }

  /**
   * Utility method for tests that result in errors early during package loading. Given the name of
   * the package for the test, and the rules for the build file, create a scratch file, load the
   * build file, and produce the package.
   *
   * @param packageName the name of the package for the build file
   * @param lines the rules for the build file as an array of strings
   * @return the loaded package from the populated package cache
   * @throws Exception if there is an error creating the temporary files for the test.
   */
  protected com.google.devtools.build.lib.packages.Package createScratchPackageForImplicitCycle(
      String packageName, String... lines) throws Exception {
    eventCollector.clear();
    reporter.removeHandler(failFastHandler);
    scratch.file(packageName + "/BUILD", lines);
    return getPackageManager()
        .getPackage(reporter, PackageIdentifier.createInMainRepo(packageName));
  }

  /** A stub analysis environment. */
  protected class StubAnalysisEnvironment implements AnalysisEnvironment {

    @Override
    public void registerAction(ActionAnalysisMetadata action) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasErrors() {
      return false;
    }

    @Override
    public Artifact getConstantMetadataArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SpecialArtifact getRunfilesArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SpecialArtifact getTreeArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SpecialArtifact getSymlinkArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExtendedEventHandler getEventHandler() {
      return reporter;
    }

    @Override
    public Action getLocalGeneratingAction(Artifact artifact) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableList<ActionAnalysisMetadata> getRegisteredActions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SkyFunction.Environment getSkyframeEnv() {
      throw new UnsupportedOperationException();
    }

    @Override
    public StarlarkSemantics getStarlarkSemantics() {
      return buildLanguageOptions.toStarlarkSemantics();
    }

    @Override
    public ImmutableMap<String, Object> getStarlarkDefinedBuiltins() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact getFilesetArtifact(PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact.DerivedArtifact getDerivedArtifact(
        PathFragment rootRelativePath, ArtifactRoot root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact.DerivedArtifact getDerivedArtifact(
        PathFragment rootRelativePath, ArtifactRoot root, boolean contentBasedPath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact getStableWorkspaceStatusArtifact() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Artifact getVolatileWorkspaceStatusArtifact() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionLookupKey getOwner() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<Artifact> getOrphanArtifacts() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<Artifact> getTreeArtifactsConflictingWithFiles() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionKeyContext getActionKeyContext() {
      return actionKeyContext;
    }

    @Override
    public RepositoryMapping getMainRepoMapping() {
      throw new UnsupportedOperationException();
    }
  }

  protected ImmutableList<String> baselineCoverageArtifactBasenames(ConfiguredTarget target)
      throws Exception {
    Artifact baselineCoverage =
        target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR).getBaselineCoverageArtifact();
    if (baselineCoverage == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> basenames = ImmutableList.builder();
    var baselineCoverageAction = (BaselineCoverageAction) getGeneratingAction(baselineCoverage);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    baselineCoverageAction
        .newDeterministicWriter(ActionsTestUtil.createContext(reporter))
        .writeOutputFile(bytes);

    for (String line : Splitter.on('\n').split(bytes.toString(UTF_8))) {
      if (line.startsWith("SF:")) {
        String basename = line.substring(line.lastIndexOf('/') + 1);
        basenames.add(basename);
      }
    }
    return basenames.build();
  }

  /**
   * Finds an artifact in the transitive closure of a set of other artifacts by following a path
   * based on artifact name suffixes.
   *
   * <p>This selects the first artifact in the input set that matches the first suffix, then selects
   * the first artifact in the inputs of its generating action that matches the second suffix etc.,
   * and repeats this until the supplied suffixes run out.
   */
  protected Artifact artifactByPath(NestedSet<Artifact> artifacts, String... suffixes) {
    return artifactByPath(artifacts.toList(), suffixes);
  }

  /**
   * Finds an artifact in the transitive closure of a set of other artifacts by following a path
   * based on artifact name suffixes.
   *
   * <p>This selects the first artifact in the input set that matches the first suffix, then selects
   * the first artifact in the inputs of its generating action that matches the second suffix etc.,
   * and repeats this until the supplied suffixes run out.
   */
  protected Artifact artifactByPath(Iterable<Artifact> artifacts, String... suffixes) {
    Artifact artifact = getFirstArtifactEndingWith(artifacts, suffixes[0]);
    Action action = null;
    for (int i = 1; i < suffixes.length; i++) {
      if (artifact == null) {
        if (action == null) {
          throw new IllegalStateException(
              String.format(
                  "No suffix %s among artifacts: %s",
                  suffixes[0], ActionsTestUtil.baseArtifactNames(artifacts)));
        } else {
          throw new IllegalStateException(
              String.format(
                  "No suffix %s among inputs of action %s: %s",
                  suffixes[i], action.describe(), ActionsTestUtil.baseArtifactNames(artifacts)));
        }
      }

      action = getGeneratingAction(artifact);
      artifacts = action.getInputs().toList();
      artifact = getFirstArtifactEndingWith(artifacts, suffixes[i]);
    }

    return artifact;
  }

  /**
   * Retrieves an instance of {@code PseudoAction} that is shadowed by an extra action
   *
   * @param targetLabel Label of the target with an extra action
   * @param actionListenerLabel Label of the action listener
   */
  protected PseudoAction<?> getPseudoActionViaExtraAction(
      String targetLabel, String actionListenerLabel) throws Exception {
    useConfiguration(String.format("--experimental_action_listener=%s", actionListenerLabel));

    ConfiguredTarget target = getConfiguredTarget(targetLabel);
    List<Action> actions = getExtraActionActions(target);

    assertThat(actions).isNotNull();
    assertThat(actions).hasSize(2);

    ExtraAction extraAction = null;

    for (Action action : actions) {
      if (action instanceof ExtraAction loopAction) {
        extraAction = loopAction;
        break;
      }
    }

    assertWithMessage(actions.toString()).that(extraAction).isNotNull();

    Action pseudoAction = extraAction.getShadowedAction();

    assertThat(pseudoAction).isInstanceOf(PseudoAction.class);
    assertThat(pseudoAction.getPrimaryOutput().getExecPathString())
        .isEqualTo(
            String.format(
                "%s%s.extra_action_dummy",
                targetConfig.getGenfilesFragment(RepositoryName.MAIN),
                convertLabelToPath(targetLabel)));

    return (PseudoAction<?>) pseudoAction;
  }

  /**
   * Converts the given label to an output path where double slashes and colons are replaced with
   * single slashes.
   */
  private static String convertLabelToPath(String label) {
    return label.replace(':', '/').substring(1);
  }

  protected final String getImplicitOutputPath(
      ConfiguredTarget target, SafeImplicitOutputsFunction outputFunction) {
    Rule rule;
    try {
      rule = (Rule) skyframeExecutor.getPackageManager().getTarget(reporter, target.getLabel());
    } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
    RawAttributeMapper attr = RawAttributeMapper.of(rule.getAssociatedRule());

    return Iterables.getOnlyElement(outputFunction.getImplicitOutputs(eventCollector, attr));
  }

  /**
   * Gets the artifact whose name is derived from {@code outputFunction}. Despite the name, this can
   * be called for artifacts that are not declared as implicit outputs: it just finds the artifact
   * inside the configured target by calling {@link #getBinArtifact(String, ConfiguredTarget)} on
   * the result of the {@code outputFunction}.
   */
  protected final Artifact getImplicitOutputArtifact(
      ConfiguredTarget target, SafeImplicitOutputsFunction outputFunction) {
    return getBinArtifact(getImplicitOutputPath(target, outputFunction), target);
  }

  public Path getExecRoot() {
    return directories.getExecRoot(ruleClassProvider.getRunfilesPrefix());
  }

  /** Returns true iff commandLine contains the option --flagName followed by arg. */
  protected static boolean containsFlag(String flagName, String arg, Iterable<String> commandLine) {
    Iterator<String> iterator = commandLine.iterator();
    while (iterator.hasNext()) {
      if (flagName.equals(iterator.next()) && iterator.hasNext() && arg.equals(iterator.next())) {
        return true;
      }
    }
    return false;
  }

  /** Returns the list of arguments in commandLine that follow after --flagName. */
  protected static ImmutableList<String> flagValue(String flagName, Iterable<String> commandLine) {
    ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
    Iterator<String> iterator = commandLine.iterator();
    boolean found = false;
    while (iterator.hasNext()) {
      String val = iterator.next();
      if (found) {
        if (val.startsWith("--")) {
          break;
        }
        resultBuilder.add(val);
      } else if (flagName.equals(val)) {
        found = true;
      }
    }
    Preconditions.checkArgument(found);
    return resultBuilder.build();
  }

  /** Creates instances of {@link ActionExecutionContext} consistent with test case. */
  public class ActionExecutionContextBuilder {
    private InputMetadataProvider actionInputFileCache = null;
    private final TreeMap<String, String> clientEnv = new TreeMap<>();
    private ArtifactExpander artifactExpander = null;
    private Executor executor = new DummyExecutor(fileSystem, getExecRoot());

    @CanIgnoreReturnValue
    public ActionExecutionContextBuilder setMetadataProvider(
        InputMetadataProvider actionInputFileCache) {
      this.actionInputFileCache = actionInputFileCache;
      return this;
    }

    @CanIgnoreReturnValue
    public ActionExecutionContextBuilder setArtifactExpander(ArtifactExpander artifactExpander) {
      this.artifactExpander = artifactExpander;
      return this;
    }

    @CanIgnoreReturnValue
    public ActionExecutionContextBuilder setExecutor(Executor executor) {
      this.executor = executor;
      return this;
    }

    public ActionExecutionContext build() {
      return new ActionExecutionContext(
          executor,
          actionInputFileCache,
          /* actionInputPrefetcher= */ null,
          actionKeyContext,
          /* outputMetadataStore= */ null,
          /* rewindingEnabled= */ false,
          LostInputsCheck.NONE,
          actionLogBufferPathGenerator.generate(ArtifactPathResolver.IDENTITY),
          reporter,
          clientEnv,
          /* topLevelFilesets= */ ImmutableMap.of(),
          artifactExpander,
          /* actionFileSystem= */ null,
          /* skyframeDepsResult= */ null,
          DiscoveredModulesPruner.DEFAULT,
          SyscallCache.NO_CACHE,
          ThreadStateReceiver.NULL_INSTANCE);
    }
  }
}
