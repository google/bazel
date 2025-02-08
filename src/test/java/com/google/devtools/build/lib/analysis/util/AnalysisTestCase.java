// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableMultiset.toImmutableMultiset;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ProviderCollection;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutorRepositoryHelpersHolder;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestConstants.InternalTestExecutionMode;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DelegatingSyscallCache;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;

/**
 * Testing framework for tests of the analysis phase that uses the BuildView and LoadingPhaseRunner
 * APIs correctly (compared to {@link BuildViewTestCase}).
 *
 * <p>The intended usage pattern is to first call {@link #update} with the set of targets, and then
 * assert properties of the configured targets obtained from {@link #getConfiguredTarget}.
 *
 * <p>This class intentionally does not inherit from {@link BuildViewTestCase}; BuildViewTestCase
 * abuses the BuildView API in ways that are incompatible with the goals of this test, i.e. the
 * convenience methods provided there wouldn't work here.
 */
public abstract class AnalysisTestCase extends FoundationTestCase {
  private static final int LOADING_PHASE_THREADS = 20;

  /** All the flags that can be passed to {@link BuildView#update}. */
  public enum Flag {
    // The --keep_going flag.
    KEEP_GOING,
    // Flags for visibility to default to public.
    PUBLIC_VISIBILITY,
    // Flags for CPU to work (be set to k8) in test mode.
    CPU_K8,
    // Flags from TestConstants.PRODUCT_SPECIFIC_FLAGS.
    PRODUCT_SPECIFIC_FLAGS,
    // The --nolegacy_external_runfiles flag.
    NO_LEGACY_EXTERNAL_RUNFILES
  }

  /** Helper class to make it easy to enable and disable flags. */
  public static final class FlagBuilder {
    private final Set<Flag> flags = EnumSet.noneOf(Flag.class);

    @CanIgnoreReturnValue
    public FlagBuilder with(Flag flag) {
      flags.add(flag);
      return this;
    }

    public boolean contains(Flag flag) {
      return flags.contains(flag);
    }
  }

  protected BlazeDirectories directories;
  protected MockToolsConfig mockToolsConfig;

  protected AnalysisMock analysisMock;
  protected BuildOptions buildOptions;
  private OptionsParser optionsParser;
  protected PackageManager packageManager;
  private BuildViewForTesting buildView;
  protected final ActionKeyContext actionKeyContext = new ActionKeyContext();

  // Note that these configurations are virtual (they use only VFS)
  private BuildConfigurationValue universeConfig;
  private BuildConfigurationValue execConfig;

  private AnalysisResult analysisResult;
  protected SkyframeExecutor skyframeExecutor = null;
  protected ConfiguredRuleClassProvider ruleClassProvider;

  protected AnalysisTestUtil.DummyWorkspaceStatusActionFactory workspaceStatusActionFactory;
  private PathPackageLocator pkgLocator;
  protected final DelegatingSyscallCache delegatingSyscallCache = new DelegatingSyscallCache();

  protected Path moduleRoot;
  protected FakeRegistry registry;

  @Before
  public final void createMocks() throws Exception {
    delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE);
    analysisMock = getAnalysisMock();
    pkgLocator =
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(Root.fromPath(rootDirectory)),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    workspaceStatusActionFactory = new AnalysisTestUtil.DummyWorkspaceStatusActionFactory();

    moduleRoot = scratch.dir("modules");
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());

    mockToolsConfig = new MockToolsConfig(rootDirectory);
    analysisMock.setupMockToolsRepository(mockToolsConfig);
    analysisMock.setupMockClient(mockToolsConfig);

    useRuleClassProvider(analysisMock.createRuleClassProvider());
  }

  @After
  public final void cleanupInterningPools() {
    skyframeExecutor.getEvaluator().cleanupInterningPools();
  }

  private SkyframeExecutor createSkyframeExecutor(PackageFactory pkgFactory) {
    return BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
        .setPkgFactory(pkgFactory)
        .setFileSystem(fileSystem)
        .setDirectories(directories)
        .setActionKeyContext(actionKeyContext)
        .setWorkspaceStatusActionFactory(workspaceStatusActionFactory)
        .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
        .setSyscallCache(delegatingSyscallCache)
        .setRepositoryHelpersHolder(getRepositoryHelpersHolder())
        .build();
  }

  @ForOverride
  @Nullable
  protected SkyframeExecutorRepositoryHelpersHolder getRepositoryHelpersHolder() {
    return null;
  }

  /** Changes the rule class provider to be used for the loading and the analysis phase. */
  protected void useRuleClassProvider(ConfiguredRuleClassProvider ruleClassProvider)
      throws Exception {
    this.ruleClassProvider = ruleClassProvider;
    PackageFactory pkgFactory =
        analysisMock
            .getPackageFactoryBuilderForTesting(directories)
            .setExtraPrecomputeValues(
                ImmutableList.<PrecomputedValue.Injected>builder()
                    .addAll(analysisMock.getPrecomputedValues())
                    .add(
                        PrecomputedValue.injected(
                            ModuleFileFunction.REGISTRIES, ImmutableSet.of(registry.getUrl())))
                    .build())
            .build(ruleClassProvider, fileSystem);
    useConfiguration();
    skyframeExecutor = createSkyframeExecutor(pkgFactory);
    skyframeExecutor.setEventBus(new EventBus());
    reinitializeSkyframeExecutor();
    packageManager = skyframeExecutor.getPackageManager();
    buildView = new BuildViewForTesting(directories, ruleClassProvider, skyframeExecutor, null);
  }

  private void reinitializeSkyframeExecutor() {
    SkyframeExecutorTestHelper.process(skyframeExecutor);
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 3;
    BuildLanguageOptions buildLanguageOptions = Options.getDefaults(BuildLanguageOptions.class);
    skyframeExecutor.preparePackageLoading(
        pkgLocator,
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    skyframeExecutor.injectExtraPrecomputedValues(analysisMock.getPrecomputedValues());
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                ModuleFileFunction.REGISTRIES, ImmutableSet.of(registry.getUrl()))));
  }

  /** Resets the SkyframeExecutor, as if a clean had been executed. */
  protected void cleanSkyframe() {
    skyframeExecutor.resetEvaluator();
    reinitializeSkyframeExecutor();
  }

  protected AnalysisMock getAnalysisMock() {
    return AnalysisMock.get();
  }

  protected static InternalTestExecutionMode getInternalTestExecutionMode() {
    return InternalTestExecutionMode.NORMAL;
  }

  /**
   * Sets exec and target configuration using the specified options, falling back to the default
   * options for unspecified ones, and recreates the build view.
   */
  public void useConfiguration(String... args) throws Exception {
    optionsParser =
        OptionsParser.builder()
            .optionsClasses(
                Iterables.concat(
                    Arrays.asList(
                        ExecutionOptions.class,
                        PackageOptions.class,
                        BuildLanguageOptions.class,
                        BuildRequestOptions.class,
                        AnalysisOptions.class,
                        KeepGoingOption.class,
                        LoadingPhaseThreadsOption.class,
                        LoadingOptions.class),
                    ruleClassProvider.getFragmentRegistry().getOptionsClasses()))
            .build();
    if (defaultFlags().contains(Flag.PUBLIC_VISIBILITY)) {
      optionsParser.parse("--default_visibility=public");
    }
    if (defaultFlags().contains(Flag.CPU_K8)) {
      optionsParser.parse("--cpu=k8", "--host_cpu=k8");
    }
    if (defaultFlags().contains(Flag.PRODUCT_SPECIFIC_FLAGS)) {
      optionsParser.parse(TestConstants.PRODUCT_SPECIFIC_FLAGS);
    }
    if (defaultFlags().contains(Flag.NO_LEGACY_EXTERNAL_RUNFILES)) {
      optionsParser.parse("--nolegacy_external_runfiles");
    }
    optionsParser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
    optionsParser.parse(args);

    buildOptions =
        BuildOptions.of(ruleClassProvider.getFragmentRegistry().getOptionsClasses(), optionsParser);
  }

  protected FlagBuilder defaultFlags() {
    return new FlagBuilder()
        .with(Flag.PUBLIC_VISIBILITY)
        .with(Flag.CPU_K8)
        .with(Flag.PRODUCT_SPECIFIC_FLAGS)
        .with(Flag.NO_LEGACY_EXTERNAL_RUNFILES);
  }

  protected Action getGeneratingAction(Artifact artifact) {
    ensureUpdateWasCalled();
    ActionAnalysisMetadata action = analysisResult.getActionGraph().getGeneratingAction(artifact);

    if (action != null) {
      Preconditions.checkState(
          action instanceof Action, "%s is not a proper Action object", action.prettyPrint());
      return (Action) action;
    } else {
      return null;
    }
  }

  /**
   * Returns the target configuration for the most recent build, as created in Blaze's primary
   * configuration creation phase.
   */
  protected BuildConfigurationValue getTargetConfiguration() throws InterruptedException {
    return universeConfig;
  }

  protected BuildConfigurationValue getExecConfiguration() {
    return execConfig;
  }

  protected final void ensureUpdateWasCalled() {
    Preconditions.checkState(analysisResult != null, "You must run update() first!");
  }

  /** Update the BuildView: syncs the package cache; loads and analyzes the given labels. */
  protected AnalysisResult update(
      EventBus eventBus,
      FlagBuilder config,
      ImmutableSet<Label> explicitTargetPatterns,
      ImmutableList<String> aspects,
      ImmutableMap<String, String> aspectsParameters,
      String... labels)
      throws Exception {
    Set<Flag> flags = config.flags;

    LoadingOptions loadingOptions = optionsParser.getOptions(LoadingOptions.class);

    AnalysisOptions viewOptions = optionsParser.getOptions(AnalysisOptions.class);
    // update --keep_going option if test requested it.
    boolean keepGoing = flags.contains(Flag.KEEP_GOING);
    boolean discardAnalysisCache = viewOptions.discardAnalysisCache;

    PackageOptions packageOptions = optionsParser.getOptions(PackageOptions.class);
    PathPackageLocator pathPackageLocator =
        PathPackageLocator.create(
            outputBase,
            packageOptions.packagePath,
            reporter,
            rootDirectory.asFragment(),
            rootDirectory,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;

    BuildLanguageOptions buildLanguageOptions =
        optionsParser.getOptions(BuildLanguageOptions.class);

    skyframeExecutor.preparePackageLoading(
        pathPackageLocator,
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));

    TargetPatternPhaseValue loadingResult =
        skyframeExecutor.loadTargetPatternsWithFilters(
            reporter,
            ImmutableList.copyOf(labels),
            PathFragment.EMPTY_FRAGMENT,
            loadingOptions,
            LOADING_PHASE_THREADS,
            keepGoing,
            /* determineTests= */ false);

    analysisResult =
        buildView.update(
            loadingResult,
            buildOptions,
            explicitTargetPatterns,
            aspects,
            aspectsParameters,
            viewOptions,
            keepGoing,
            LOADING_PHASE_THREADS,
            AnalysisTestUtil.TOP_LEVEL_ARTIFACT_CONTEXT,
            reporter,
            eventBus);
    if (discardAnalysisCache) {
      buildView.clearAnalysisCache(
          analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().keySet());
    }

    universeConfig = analysisResult.getConfiguration();
    scratch.overwriteFile("platform/BUILD", "platform(name = 'exec')");
    execConfig =
        skyframeExecutor.getConfiguration(
            reporter,
            AnalysisTestUtil.execOptions(universeConfig.getOptions(), skyframeExecutor, reporter),
            /* keepGoing= */ false);

    return analysisResult;
  }

  protected AnalysisResult update(
      EventBus eventBus, FlagBuilder config, ImmutableList<String> aspects, String... labels)
      throws Exception {
    return update(
        eventBus,
        config,
        /* explicitTargetPatterns= */ ImmutableSet.of(),
        aspects,
        /* aspectsParameters= */ ImmutableMap.of(),
        labels);
  }

  protected AnalysisResult update(EventBus eventBus, FlagBuilder config, String... labels)
      throws Exception {
    return update(eventBus, config, /* aspects= */ ImmutableList.of(), labels);
  }

  protected AnalysisResult update(FlagBuilder config, String... labels) throws Exception {
    return update(new EventBus(), config, /* aspects= */ ImmutableList.of(), labels);
  }

  /** Update the BuildView: syncs the package cache; loads and analyzes the given labels. */
  protected AnalysisResult update(String... labels) throws Exception {
    return update(new EventBus(), defaultFlags(), /* aspects= */ ImmutableList.of(), labels);
  }

  protected AnalysisResult update(ImmutableList<String> aspects, String... labels)
      throws Exception {
    return update(new EventBus(), defaultFlags(), aspects, labels);
  }

  protected AnalysisResult update(
      ImmutableList<String> aspects,
      ImmutableMap<String, String> aspectsParameters,
      String... labels)
      throws Exception {
    return update(
        new EventBus(),
        defaultFlags(),
        /* explicitTargetPatterns= */ ImmutableSet.of(),
        aspects,
        aspectsParameters,
        labels);
  }

  protected ConfiguredTargetAndData getConfiguredTargetAndTarget(String label)
      throws InterruptedException {
    return getConfiguredTargetAndTarget(label, getTargetConfiguration());
  }

  protected ConfiguredTargetAndData getConfiguredTargetAndTarget(
      String label, BuildConfigurationValue config) {
    ensureUpdateWasCalled();
    Label parsedLabel;
    try {
      parsedLabel = Label.parseCanonical(label);
    } catch (LabelSyntaxException e) {
      throw new AssertionError(e);
    }
    try {
      return skyframeExecutor.getConfiguredTargetAndDataForTesting(reporter, parsedLabel, config);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  protected Target getTarget(String label) throws InterruptedException {
    try {
      return SkyframeExecutorTestUtils.getExistingTarget(
          skyframeExecutor, Label.parseCanonical(label));
    } catch (LabelSyntaxException e) {
      throw new AssertionError(e);
    }
  }

  protected final ConfiguredTargetAndData getConfiguredTargetAndData(
      String label, BuildConfigurationValue configuration) {
    ensureUpdateWasCalled();
    return getConfiguredTargetForSkyframe(label, configuration);
  }

  protected final ConfiguredTargetAndData getConfiguredTargetAndData(String label)
      throws InterruptedException {
    return getConfiguredTargetAndData(label, getTargetConfiguration());
  }

  protected final ConfiguredTarget getConfiguredTarget(
      String label, BuildConfigurationValue configuration) {
    ConfiguredTargetAndData result = getConfiguredTargetAndData(label, configuration);
    return result == null ? null : result.getConfiguredTarget();
  }

  /**
   * Returns the corresponding configured target, if it exists. Note that this will only return
   * anything useful after a call to update() with the same label.
   */
  protected ConfiguredTarget getConfiguredTarget(String label) throws InterruptedException {
    return getConfiguredTarget(label, getTargetConfiguration());
  }

  private ConfiguredTargetAndData getConfiguredTargetForSkyframe(
      String label, BuildConfigurationValue configuration) {
    Label parsedLabel;
    try {
      parsedLabel = Label.parseCanonical(label);
    } catch (LabelSyntaxException e) {
      throw new AssertionError(e);
    }
    try {
      return skyframeExecutor.getConfiguredTargetAndDataForTesting(
          reporter, parsedLabel, configuration);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  protected final BuildConfigurationValue getConfiguration(ConfiguredTarget ct) {
    return skyframeExecutor.getConfiguration(reporter, ct.getConfigurationKey());
  }

  /**
   * Returns the corresponding configured target, if it exists. Note that this will only return
   * anything useful after a call to update() with the same label. The label passed in must
   * represent an input file.
   */
  protected InputFileConfiguredTarget getInputFileConfiguredTarget(String label) {
    return (InputFileConfiguredTarget) getConfiguredTarget(label, null);
  }

  protected boolean hasErrors(ConfiguredTarget configuredTarget) {
    return buildView.hasErrors(configuredTarget);
  }

  protected Artifact getBinArtifact(String packageRelativePath, ConfiguredTarget owner)
      throws InterruptedException {
    Label label = owner.getLabel();
    ActionLookupKey actionLookupKey =
        ConfiguredTargetKey.builder()
            .setLabel(label)
            .setConfigurationKey(owner.getConfigurationKey())
            .build();
    ActionLookupValue actionLookupValue;
    try {
      actionLookupValue =
          (ActionLookupValue) skyframeExecutor.getEvaluator().getExistingValue(actionLookupKey);
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
    PathFragment rootRelativePath = label.getPackageFragment().getRelative(packageRelativePath);
    for (ActionAnalysisMetadata action : actionLookupValue.getActions()) {
      for (Artifact output : action.getOutputs()) {
        if (output.getRootRelativePath().equals(rootRelativePath)) {
          return output;
        }
      }
    }
    // Fall back: some tests don't actually need the right owner.
    return buildView
        .getArtifactFactory()
        .getDerivedArtifact(
            label.getPackageFragment().getRelative(packageRelativePath),
            getTargetConfiguration().getBinDirectory(label.getRepository()),
            ConfiguredTargetKey.fromConfiguredTarget(owner));
  }

  protected Set<ActionLookupKey> getSkyframeEvaluatedTargetKeys() {
    return buildView.getSkyframeEvaluatedActionLookupKeyCountForTesting();
  }

  protected void assertNumberOfAnalyzedConfigurationsOfTargets(
      Map<String, Integer> targetsWithCounts) {
    ImmutableMultiset<Label> actualSet =
        getSkyframeEvaluatedTargetKeys().stream()
            .filter(key -> key instanceof ConfiguredTargetKey)
            .map(ArtifactOwner::getLabel)
            .collect(toImmutableMultiset());
    ImmutableMap<Label, Integer> expected =
        targetsWithCounts.entrySet().stream()
            .collect(
                toImmutableMap(
                    entry -> Label.parseCanonicalUnchecked(entry.getKey()), Map.Entry::getValue));
    ImmutableMap<Label, Integer> actual =
        expected.keySet().stream().collect(toImmutableMap(label -> label, actualSet::count));
    assertThat(actual).containsExactlyEntriesIn(expected);
  }

  protected BuildViewForTesting getView() {
    return buildView;
  }

  protected ActionGraph getActionGraph() {
    return skyframeExecutor.getActionGraph(reporter);
  }

  protected AnalysisResult getAnalysisResult() {
    return analysisResult;
  }

  protected void clearAnalysisResult() {
    analysisResult = null;
  }

  /**
   * Makes {@code rules} available in tests, in addition to all the rules available to Blaze at
   * running time (e.g., java_library).
   *
   * <p>Also see {@link AnalysisTestCase#setRulesAndAspectsAvailableInTests(Iterable, Iterable)}.
   */
  protected void setRulesAvailableInTests(RuleDefinition... rules) throws Exception {
    // Not all of these aspects are needed for all tests, but it makes it simple to offer them all.
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(
            TestAspects.SIMPLE_ASPECT,
            TestAspects.PARAMETRIZED_DEFINITION_ASPECT,
            TestAspects.ASPECT_REQUIRING_PROVIDER,
            TestAspects.FALSE_ADVERTISEMENT_ASPECT,
            TestAspects.ALL_ATTRIBUTES_ASPECT,
            TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT,
            TestAspects.BAR_PROVIDER_ASPECT,
            TestAspects.EXTRA_ATTRIBUTE_ASPECT,
            TestAspects.PACKAGE_GROUP_ATTRIBUTE_ASPECT,
            TestAspects.COMPUTED_ATTRIBUTE_ASPECT,
            TestAspects.FILE_PROVIDER_ASPECT,
            TestAspects.FOO_PROVIDER_ASPECT,
            TestAspects.ASPECT_REQUIRING_PROVIDER_SETS,
            TestAspects.WARNING_ASPECT,
            TestAspects.ERROR_ASPECT),
        ImmutableList.copyOf(rules));
  }

  /**
   * Makes {@code aspects} and {@code rules} available in tests, in addition to all the rules
   * available to Blaze at running time (e.g., java_library).
   */
  protected final void setRulesAndAspectsAvailableInTests(
      Iterable<NativeAspectClass> aspects, Iterable<RuleDefinition> rules) throws Exception {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    for (NativeAspectClass aspect : aspects) {
      builder.addNativeAspectClass(aspect);
    }
    for (RuleDefinition rule : rules) {
      builder.addRuleDefinition(rule);
    }

    useRuleClassProvider(builder.build());
    update();
  }

  /**
   * Retrieves Starlark provider from a configured target.
   *
   * <p>Assuming that the provider is defined in the same bzl file as the rule.
   */
  protected StarlarkInfo getStarlarkProvider(ConfiguredTarget target, String providerSymbol)
      throws Exception {
    return getStarlarkProvider(
        target,
        getTarget(target.getLabel().toString())
            .getAssociatedRule()
            .getRuleClassObject()
            .getRuleDefinitionEnvironmentLabel()
            .toString(),
        providerSymbol);
  }

  /** Retrieves Starlark provider from a configured target or aspect. */
  protected StarlarkInfo getStarlarkProvider(
      ProviderCollection target, String label, String providerSymbol) throws Exception {
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical(label)), providerSymbol);
    return (StarlarkInfo) target.get(key);
  }
}
