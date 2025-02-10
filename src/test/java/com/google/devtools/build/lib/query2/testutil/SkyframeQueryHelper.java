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
package com.google.devtools.build.lib.query2.testutil;

import static com.google.devtools.build.lib.packages.Rule.ALL_LABELS;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternPreloader;
import com.google.devtools.build.lib.query2.QueryEnvironmentFactory;
import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeTargetPatternEvaluator;
import com.google.devtools.build.lib.skyframe.packages.PackageFactoryBuilderWithSkyframeForTesting;
import com.google.devtools.build.lib.testing.common.FakeOptions;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestPackageFactoryBuilderFactory;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DelegatingSyscallCache;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.Options;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An implementation of AbstractQueryHelper to support testing bazel query. */
public abstract class SkyframeQueryHelper extends AbstractQueryHelper<Target> {
  protected SkyframeExecutor skyframeExecutor;
  protected FileSystem fileSystem =
      new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
  private FakeRegistry registry;

  protected Path rootDirectory;
  protected Path outputBase;
  protected Path moduleRoot;
  protected BlazeDirectories directories;
  private RepositoryName toolsRepository;

  protected AnalysisMock analysisMock;
  private QueryEnvironmentFactory queryEnvironmentFactory;

  private PackageManager pkgManager;
  private TargetPatternPreloader targetParser;
  protected final ActionKeyContext actionKeyContext = new ActionKeyContext();

  private final PathFragment ignoredSubdirectoriesFile = PathFragment.create(".bazelignore");
  private final DelegatingSyscallCache delegatingSyscallCache = new DelegatingSyscallCache();

  @Override
  public void setUp() throws Exception {
    super.setUp();
    analysisMock = AnalysisMock.get();
    rootDirectory = createDir(getRootDirectoryNameForSetup());
    outputBase = createDir(fileSystem.getPath("/output").getPathString());
    directories =
        new BlazeDirectories(
            new ServerDirectories(
                rootDirectory,
                outputBase,
                outputBase,
                outputBase.getRelative(ServerDirectories.EXECROOT),
                useVirtualSourceRoot() ? Root.fromPath(rootDirectory) : null,
                /* installMD5= */ null),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE);

    moduleRoot = createDir(outputBase.getRelative("modules").getPathString());
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());
    writeFile("MODULE.bazel", "module( name = \"root\", version = \"1.0\")");

    MockToolsConfig mockToolsConfig = new MockToolsConfig(rootDirectory);
    analysisMock.setupMockClient(mockToolsConfig);
    analysisMock.setupMockToolsRepository(mockToolsConfig);
    analysisMock.ccSupport().setup(mockToolsConfig);
    analysisMock.pySupport().setup(mockToolsConfig);
    performAdditionalClientSetup(mockToolsConfig);

    initTargetPatternEvaluator(analysisMock.createRuleClassProvider());

    this.queryEnvironmentFactory = makeQueryEnvironmentFactory();
  }

  @Override
  public final void cleanUp() {
    skyframeExecutor.getEvaluator().cleanupInterningPools();
  }

  protected abstract String getRootDirectoryNameForSetup();

  @ForOverride
  protected boolean useVirtualSourceRoot() {
    return false;
  }

  protected abstract void performAdditionalClientSetup(MockToolsConfig mockToolsConfig)
      throws IOException;

  private Path createDir(String pathName) throws IOException {
    Path dir = fileSystem.getPath(pathName);
    dir.createDirectoryAndParents();
    return dir;
  }

  @Override
  public void maybeHandleDiffs() throws AbruptExitException, InterruptedException {
    if (skyframeExecutor.hasDiffAwareness()) {
      skyframeExecutor.handleDiffsForTesting(getReporter());
    }
  }

  @Override
  public PathFragment getIgnoredSubdirectoriesFile() {
    return ignoredSubdirectoriesFile;
  }

  @ForOverride
  protected QueryEnvironmentFactory makeQueryEnvironmentFactory() {
    return new QueryEnvironmentFactory();
  }

  @Override
  public Path getRootDirectory() {
    return rootDirectory;
  }

  @Override
  public void clearAllFiles() throws IOException {
    rootDirectory.deleteTreesBelow();
  }

  @Override
  public void writeFile(String fileName, String... lines) throws IOException {
    Path file = rootDirectory.getRelative(fileName);
    if (file.exists()) {
      throw new IOException("Could not create scratch file (file exists) " + fileName);
    }
    file.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(file, Joiner.on('\n').join(lines));
  }

  @Override
  public void overwriteFile(String fileName, String... lines) throws IOException {
    Path file = rootDirectory.getRelative(fileName);
    file.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(file, Joiner.on('\n').join(lines));
  }

  @Override
  public void ensureSymbolicLink(String link, String target) throws IOException {
    Path linkPath = rootDirectory.getRelative(link);
    Path targetPath = rootDirectory.getRelative(target);
    linkPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.ensureSymbolicLink(linkPath, targetPath);
  }

  @Override
  public AbstractBlazeQueryEnvironment<Target> getQueryEnvironment() {
    return queryEnvironmentFactory.create(
        skyframeExecutor.getQueryTransitivePackagePreloader(),
        skyframeExecutor,
        pkgManager,
        pkgManager,
        targetParser,
        mainRepoTargetParser,
        /* relativeWorkingDirectory= */ PathFragment.EMPTY_FRAGMENT,
        keepGoing,
        /* strictScope= */ true,
        orderedResults,
        universeScope,
        /* loadingPhaseThreads= */ 1,
        /* labelFilter= */ ALL_LABELS,
        getReporter(),
        this.settings,
        getExtraQueryFunctions(),
        pkgManager.getPackagePath(),
        /* useGraphlessQuery= */ false,
        LabelPrinter.legacy());
  }

  protected abstract Iterable<QueryFunction> getExtraQueryFunctions();

  @Override
  public ResultAndTargets<Target> evaluateQuery(String query)
      throws QueryException, InterruptedException {
    try (AbstractBlazeQueryEnvironment<Target> env = getQueryEnvironment()) {
      return evaluateQuery(query, env);
    }
  }

  public static ResultAndTargets<Target> evaluateQuery(
      String query, AbstractBlazeQueryEnvironment<Target> env)
      throws QueryException, InterruptedException {
    AggregateAllOutputFormatterCallback<Target, ?> callback =
        QueryUtil.newOrderedAggregateAllOutputFormatterCallback(env);
    QueryEvalResult queryEvalResult;
    try {
      queryEvalResult =
          env.evaluateQuery(env.transformParsedQuery(QueryParser.parse(query, env)), callback);
    } catch (IOException e) {
      // Should be impossible since AggregateAllOutputFormatterCallback doesn't throw IOException.
      throw new IllegalStateException(e);
    } catch (QuerySyntaxException e) {
      // Expect valid query syntax in tests.
      throw new IllegalArgumentException(e);
    }
    return new ResultAndTargets<>(
        queryEvalResult, new OrderedThreadSafeImmutableSet(env, callback.getResult()));
  }

  @Override
  public Set<Target> evaluateQueryRaw(String query) throws QueryException, InterruptedException {
    Set<Target> result = new LinkedHashSet<>();
    ThreadSafeOutputFormatterCallback<Target> callback =
        new ThreadSafeOutputFormatterCallback<>() {
          @Override
          public synchronized void processOutput(Iterable<Target> partialResult) {
            Iterables.addAll(result, partialResult);
          }
        };
    try (AbstractBlazeQueryEnvironment<Target> env = getQueryEnvironment()) {
      try {
        env.evaluateQuery(env.transformParsedQuery(QueryParser.parse(query, env)), callback);
      } catch (IOException e) {
        // Should be impossible since the callback we passed in above doesn't throw IOException.
        throw new IllegalStateException(e);
      } catch (QuerySyntaxException e) {
        // Expect valid query syntax in tests.
        throw new IllegalArgumentException(e);
      }
    }
    return result;
  }

  @Override
  public RepositoryName getToolsRepository() {
    return toolsRepository;
  }

  @Override
  public String getLabel(Target target) {
    return target.getLabel().toString();
  }

  @Override
  public void addModule(ModuleKey key, String... moduleFileLines) {
    registry.addModule(key, moduleFileLines);
  }

  private void initTargetPatternEvaluator(ConfiguredRuleClassProvider ruleClassProvider) {
    this.toolsRepository = ruleClassProvider.getToolsRepository();
    if (skyframeExecutor != null) {
      cleanUp();
    }
    skyframeExecutor = createSkyframeExecutor(ruleClassProvider);
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);

    packageOptions.defaultVisibility = RuleVisibility.PRIVATE;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    packageOptions.packagePath = ImmutableList.of(rootDirectory.getPathString());

    BuildLanguageOptions buildLanguageOptions = Options.getDefaults(BuildLanguageOptions.class);
    buildLanguageOptions.experimentalGoogleLegacyApi = !analysisMock.isThisBazel();
    // TODO(b/256127926): Delete once flipped.
    buildLanguageOptions.experimentalEnableSclDialect = true;
    buildLanguageOptions.experimentalDormantDeps = true;
    buildLanguageOptions.incompatibleAutoloadExternally = ImmutableList.of();

    ImmutableList<BuildFileName> buildFilesByPriority = skyframeExecutor.getBuildFilesByPriority();
    PathPackageLocator packageLocator =
        useVirtualSourceRoot()
            ? PathPackageLocator.createWithoutExistenceCheck(
                /* outputBase= */ null,
                ImmutableList.of(directories.getVirtualSourceRoot()),
                buildFilesByPriority)
            : PathPackageLocator.create(
                directories.getOutputBase(),
                packageOptions.packagePath,
                getReporter(),
                directories.getWorkspace().asFragment(),
                rootDirectory,
                buildFilesByPriority);
    try {
      skyframeExecutor.sync(
          getReporter(),
          packageLocator,
          UUID.randomUUID(),
          ImmutableMap.of(),
          ImmutableMap.of(),
          new TimestampGranularityMonitor(BlazeClock.instance()),
          QuiescingExecutorsImpl.forTesting(),
          FakeOptions.builder().put(packageOptions).put(buildLanguageOptions).build(),
          /* commandName= */ "query");
    } catch (InterruptedException | AbruptExitException e) {
      throw new IllegalStateException(e);
    }
    pkgManager = skyframeExecutor.getPackageManager();
    targetParser = new SkyframeTargetPatternEvaluator(skyframeExecutor);
  }

  @Override
  public void useRuleClassProvider(ConfiguredRuleClassProvider ruleClassProvider) {
    initTargetPatternEvaluator(ruleClassProvider);
  }

  public void setSyscallCache(SyscallCache syscallCache) {
    this.delegatingSyscallCache.setDelegate(syscallCache);
  }

  protected SkyframeExecutor createSkyframeExecutor(ConfiguredRuleClassProvider ruleClassProvider) {
    var extraPrecomputedValues =
        ImmutableList.<PrecomputedValue.Injected>builder()
            .addAll(analysisMock.getPrecomputedValues())
            .add(
                PrecomputedValue.injected(
                    ModuleFileFunction.REGISTRIES, ImmutableSet.of(registry.getUrl())))
            .build();
    PackageFactory pkgFactory =
        ((PackageFactoryBuilderWithSkyframeForTesting)
                TestPackageFactoryBuilderFactory.getInstance().builder(directories))
            .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
            .setExtraPrecomputeValues(extraPrecomputedValues)
            .build(ruleClassProvider, fileSystem);
    SkyframeExecutor skyframeExecutor =
        BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(pkgFactory)
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
            .setSyscallCache(delegatingSyscallCache)
            .build();
    skyframeExecutor.injectExtraPrecomputedValues(extraPrecomputedValues);
    SkyframeExecutorTestHelper.process(skyframeExecutor);
    return skyframeExecutor;
  }

  @Override
  public void assertPackageNotLoaded(String packageName) throws Exception {
    MemoizingEvaluator evaluator = skyframeExecutor.getEvaluator();
    SkyKey key = PackageIdentifier.createInMainRepo(packageName);
    if (evaluator.getExistingValue(key) != null
        || evaluator.getExistingErrorForTesting(key) != null) {
      throw new IllegalStateException("Package was loaded: " + packageName);
    }
  }

  @Override
  public Path getModuleRoot() {
    return moduleRoot;
  }

  /**
   * A wrapper to maintain an ordered copy of set of targets which also respect equality rules
   * defined by {@link ThreadSafeMutableSet}.
   */
  private static class OrderedThreadSafeImmutableSet extends AbstractSet<Target> {
    private final ThreadSafeMutableSet<Target> targetSet;
    private final List<Target> orderedTargetList;

    private OrderedThreadSafeImmutableSet(QueryEnvironment<Target> env, Set<Target> targets) {
      this.targetSet = env.createThreadSafeMutableSet();
      this.orderedTargetList = new ArrayList<>(targets.size());

      // The order is determined by implementation of iterator on the source set of targets, which
      // can be deterministic or non-deterministic.
      for (Target target : targets) {
        if (targetSet.add(target)) {
          orderedTargetList.add(target);
        }
      }
    }

    @Override
    public Iterator<Target> iterator() {
      return orderedTargetList.iterator();
    }

    @Override
    public int size() {
      return targetSet.size();
    }

    @Override
    public boolean add(Target element) {
      throw new IllegalStateException("Add operation on immutable set is not supported.");
    }

    @Override
    public boolean contains(Object obj) {
      return targetSet.contains(obj);
    }

    @Override
    public boolean remove(Object obj) {
      throw new IllegalStateException("Remove operation on immutable set is not supported.");
    }
  }
}
