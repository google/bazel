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

package com.google.devtools.build.lib.analysis.test;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.test.TestActionContext.AttemptGroup;
import com.google.devtools.build.lib.analysis.test.TestActionContext.ProcessedAttemptResult;
import com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult;
import com.google.devtools.build.lib.analysis.test.TestActionContext.TestAttemptResult.Result;
import com.google.devtools.build.lib.analysis.test.TestActionContext.TestRunnerSpawn;
import com.google.devtools.build.lib.buildeventstream.TestFileNameConstants;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TestAction;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.devtools.common.options.TriState;
import com.google.protobuf.ExtensionRegistry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * An Action representing a test with the associated environment (runfiles, environment variables,
 * test result, etc). It consumes test executable and runfiles artifacts and produces test result
 * and test status artifacts.
 */
// Not final so that we can mock it in tests.
public class TestRunnerAction extends AbstractAction
    implements NotifyOnActionCacheHit, CommandAction {

  public static final PathFragment COVERAGE_TMP_ROOT = PathFragment.create("_coverage");

  private static final String UNDECLARED_OUTPUTS_ZIP_NAME = "outputs.zip";

  // Used for selecting subset of testcase / testmethods.
  private static final String TEST_BRIDGE_TEST_FILTER_ENV = "TESTBRIDGE_TEST_ONLY";

  private static final String GUID = "cc41f9d0-47a6-11e7-8726-eb6ce83a8cc8";
  public static final String MNEMONIC = "TestRunner";

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Artifact runfilesTree;
  private final Artifact testSetupScript;
  private final Artifact testXmlGeneratorScript;
  private final Artifact collectCoverageScript;
  private final BuildConfigurationValue configuration;
  private final TestConfiguration testConfiguration;
  private final Artifact testLog;
  private final Artifact cacheStatus;
  private final PathFragment testWarningsPath;
  private final PathFragment unusedRunfilesLogPath;
  @Nullable private final PathFragment shExecutable;
  private final PathFragment splitLogsPath;
  private final PathFragment splitLogsDir;
  private final PathFragment undeclaredOutputsAnnotationsDir;
  private final PathFragment undeclaredOutputsManifestPath;
  private final PathFragment undeclaredOutputsAnnotationsPath;
  private final PathFragment undeclaredOutputsAnnotationsPbPath;
  private final PathFragment xmlOutputPath;
  @Nullable private final PathFragment testShard;
  private final PathFragment testExitSafe;
  private final PathFragment testStderr;
  private final PathFragment testInfrastructureFailure;
  private final PathFragment baseDir;

  private final ImmutableSet<PathFragment> filesToDeleteBeforeExecution;
  private final ImmutableSet<PathFragment> directoriesToDeleteBeforeExecution;

  private final Artifact coverageData;
  @Nullable private final Artifact coverageDirectory;
  private final Artifact undeclaredOutputsDir;
  private final TestTargetProperties testProperties;
  private final TestTargetExecutionSettings executionSettings;
  private final int shardNum;
  private final int runNumber;
  private final String workspaceName;

  /**
   * Cached test result status used to minimize disk accesses. This field is set when test status is
   * retrieved from disk or saved to disk. This field is null if it has not been set yet. This field
   * is an empty optional when the file was not present on disk or there was a failure to read it.
   */
  @Nullable private Optional<TestResultData> cachedTestResultData;

  /** Any extra environment variables (and values) added by the rule that created this action. */
  private final ActionEnvironment extraTestEnv;

  /**
   * The set of environment variables that are inherited from the client environment. These are
   * handled explicitly by the ActionCacheChecker and so don't have to be included in the cache key.
   */
  private final Collection<String> requiredClientEnvVariables;

  private final boolean cancelConcurrentTestsOnSuccess;

  private final boolean splitCoveragePostProcessing;
  private final NestedSetBuilder<Artifact> lcovMergerFilesToRun;
  @Nullable private final Artifact lcovMergerRunfilesTree;

  // TODO(b/192694287): Remove once we migrate all tests from the allowlist.
  private final PackageSpecificationProvider networkAllowlist;

  private static ImmutableSet<Artifact> nonNullAsSet(Artifact... artifacts) {
    ImmutableSet.Builder<Artifact> builder = ImmutableSet.builder();
    for (Artifact artifact : artifacts) {
      if (artifact != null) {
        builder.add(artifact);
      }
    }
    return builder.build();
  }

  /**
   * Create new TestRunnerAction instance. Should not be called directly. Use {@link
   * TestActionBuilder} instead.
   *
   * @param shardNum The shard number. Must be 0 if totalShards == 0 (no sharding). Otherwise, must
   *     be >= 0 and < totalShards.
   * @param runNumber test run number
   */
  TestRunnerAction(
      ActionOwner owner,
      NestedSet<Artifact> inputs,
      Artifact runfilesTree,
      Artifact testSetupScript, // Must be in inputs
      Artifact testXmlGeneratorScript, // Must be in inputs
      @Nullable Artifact collectCoverageScript, // Must be in inputs, if not null
      Artifact testLog,
      Artifact cacheStatus,
      Artifact coverageArtifact,
      @Nullable Artifact coverageDirectory,
      Artifact undeclaredOutputsDir,
      TestTargetProperties testProperties,
      ActionEnvironment extraTestEnv,
      TestTargetExecutionSettings executionSettings,
      int shardNum,
      int runNumber,
      BuildConfigurationValue configuration,
      String workspaceName,
      @Nullable PathFragment shExecutable,
      boolean cancelConcurrentTestsOnSuccess,
      boolean splitCoveragePostProcessing,
      NestedSetBuilder<Artifact> lcovMergerFilesToRun,
      @Nullable Artifact lcovMergerRunfilesTree,
      PackageSpecificationProvider networkAllowlist) {
    super(
        owner,
        inputs,
        nonNullAsSet(
            testLog, cacheStatus, coverageArtifact, coverageDirectory, undeclaredOutputsDir));
    Preconditions.checkState((collectCoverageScript == null) == (coverageArtifact == null));
    this.runfilesTree = runfilesTree;
    this.testSetupScript = testSetupScript;
    this.testXmlGeneratorScript = testXmlGeneratorScript;
    this.collectCoverageScript = collectCoverageScript;
    this.configuration = checkNotNull(configuration);
    this.testConfiguration = checkNotNull(configuration.getFragment(TestConfiguration.class));
    this.testLog = testLog;
    this.cacheStatus = cacheStatus;
    this.coverageData = coverageArtifact;
    this.coverageDirectory = coverageDirectory;
    this.undeclaredOutputsDir = undeclaredOutputsDir;
    this.shardNum = shardNum;
    this.runNumber = runNumber;
    this.testProperties = checkNotNull(testProperties);
    this.executionSettings = checkNotNull(executionSettings);

    this.baseDir = cacheStatus.getExecPath().getParentDirectory();

    int totalShards = executionSettings.getTotalShards();
    Preconditions.checkState(
        (totalShards == 0 && shardNum == 0)
            || (totalShards > 0 && 0 <= shardNum && shardNum < totalShards));
    this.testExitSafe = baseDir.getChild("test.exited_prematurely");
    // testShard Path should be set only if sharding is enabled.
    this.testShard = totalShards > 1 ? baseDir.getChild("test.shard") : null;
    this.xmlOutputPath = baseDir.getChild("test.xml");
    this.testWarningsPath = baseDir.getChild("test.warnings");
    this.unusedRunfilesLogPath = baseDir.getChild("test.unused_runfiles_log");
    this.testStderr = baseDir.getChild("test.err");
    this.shExecutable = shExecutable;
    this.splitLogsDir = baseDir.getChild("test.raw_splitlogs");
    // See note in {@link #getSplitLogsPath} on the choice of file name.
    this.splitLogsPath = splitLogsDir.getChild("test.splitlogs");
    this.undeclaredOutputsAnnotationsDir = baseDir.getChild("test.outputs_manifest");
    this.undeclaredOutputsManifestPath = undeclaredOutputsAnnotationsDir.getChild("MANIFEST");
    this.undeclaredOutputsAnnotationsPath = undeclaredOutputsAnnotationsDir.getChild("ANNOTATIONS");
    this.undeclaredOutputsAnnotationsPbPath =
        undeclaredOutputsAnnotationsDir.getChild("ANNOTATIONS.pb");
    this.testInfrastructureFailure = baseDir.getChild("test.infrastructure_failure");
    this.workspaceName = workspaceName;

    this.extraTestEnv = extraTestEnv;
    this.requiredClientEnvVariables =
        LazySetConcatenation.from(
            configuration.getActionEnvironment().getInheritedEnv(),
            configuration.getTestActionEnvironment().getInheritedEnv(),
            this.extraTestEnv.getInheritedEnv());
    this.cancelConcurrentTestsOnSuccess = cancelConcurrentTestsOnSuccess;
    this.splitCoveragePostProcessing = splitCoveragePostProcessing;
    this.lcovMergerFilesToRun = lcovMergerFilesToRun;
    this.lcovMergerRunfilesTree = lcovMergerRunfilesTree;
    this.networkAllowlist = networkAllowlist;

    // Mark all possible test outputs for deletion before test execution.
    // TestRunnerAction potentially can create many more non-declared outputs - xml output, coverage
    // data file and logs for failed attempts. All those outputs are uniquely identified by the test
    // log base name with arbitrary prefix and extension.

    // We need to remove *.(xml|data|shard|warnings|zip) files if they are present.
    ImmutableSet.Builder<PathFragment> filesToDeleteBuilder =
        ImmutableSet.<PathFragment>builder()
            .add(
                xmlOutputPath,
                testWarningsPath,
                unusedRunfilesLogPath,
                testStderr,
                testExitSafe,
                testInfrastructureFailure,
                // We cannot use coverageData artifact since it may be null. Generate coverage name
                // instead.
                baseDir.getChild("coverage.dat"),
                baseDir.getChild("test.zip")); // Delete files fetched from remote execution.
    if (testShard != null) {
      filesToDeleteBuilder.add(testShard);
    }
    this.filesToDeleteBeforeExecution = filesToDeleteBuilder.build();
    this.directoriesToDeleteBeforeExecution =
        ImmutableSet.of(
            // Note that splitLogsPath points to a file inside the splitLogsDir so it's not
            // necessary to delete it explicitly.
            splitLogsDir,
            undeclaredOutputsDir.getExecPath(),
            undeclaredOutputsAnnotationsDir,
            baseDir.getRelative("test_attempts"));
  }

  public boolean allowLocalTests() {
    return testConfiguration.allowLocalTests();
  }

  @Override
  public boolean mayModifySpawnOutputsAfterExecution() {
    // Test actions modify test spawn outputs after execution:
    // - if there are multiple attempts (unavoidable);
    // - in all cases due to appending any stray stderr output to the test log in
    //   StandaloneTestStrategy.
    // TODO: Get rid of the second case and only return true if there are multiple attempts.
    return true;
  }

  public Artifact getRunfilesTree() {
    return runfilesTree;
  }

  @Override
  public final ActionEnvironment getEnvironment() {
    return configuration.getActionEnvironment();
  }

  @Nullable
  public Artifact getLcovMergerRunfilesTree() {
    return lcovMergerRunfilesTree;
  }

  public BuildConfigurationValue getConfiguration() {
    return configuration;
  }

  public final PathFragment getBaseDir() {
    return baseDir;
  }

  public boolean getSplitCoveragePostProcessing() {
    return splitCoveragePostProcessing;
  }

  public NestedSetBuilder<Artifact> getLcovMergerFilesToRun() {
    return lcovMergerFilesToRun;
  }

  public Artifact getCoverageDirectoryTreeArtifact() {
    return coverageDirectory;
  }

  @Override
  public boolean showsOutputUnconditionally() {
    return true;
  }

  public boolean checkShardingSupport() {
    return testConfiguration.checkShardingSupport();
  }

  public List<ActionInput> getSpawnOutputs() {
    final List<ActionInput> outputs = new ArrayList<>();
    outputs.add(ActionInputHelper.fromPath(getXmlOutputPath()));
    outputs.add(ActionInputHelper.fromPath(getExitSafeFile()));
    if (isSharded()) {
      outputs.add(ActionInputHelper.fromPath(getTestShard()));
    }
    outputs.add(ActionInputHelper.fromPath(getTestWarningsPath()));
    outputs.add(ActionInputHelper.fromPath(getSplitLogsPath()));
    outputs.add(ActionInputHelper.fromPath(getUnusedRunfilesLogPath()));
    outputs.add(ActionInputHelper.fromPath(getInfrastructureFailureFile()));
    if (testConfiguration.getZipUndeclaredTestOutputs()) {
      outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsZipPath()));
    } else {
      outputs.add(undeclaredOutputsDir);
    }
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsManifestPath()));
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsAnnotationsPath()));
    outputs.add(ActionInputHelper.fromPath(getUndeclaredOutputsAnnotationsPbPath()));
    if (isCoverageMode()) {
      if (!splitCoveragePostProcessing) {
        outputs.add(coverageData);
      }
      if (coverageDirectory != null) {
        outputs.add(coverageDirectory);
      }
    }
    return outputs;
  }

  /**
   * Returns the list of mappings from file name constants to output files. This method checks the
   * file system for existence of these output files, so it must only be used after test execution.
   */
  // TODO(ulfjack): Instead of going to local disk here, use SpawnResult (add list of files there).
  public ImmutableMultimap<String, Path> getTestOutputsMapping(
      ArtifactPathResolver resolver, Path execRoot) throws IOException {
    // TODO(tjgq): The existence checks below will incorrectly return false if the test action was
    // reconstructed from the action cache, as we don't populate the output filesystem on an action
    // cache hit. This is difficult to fix because some of the files below are produced by test
    // spawns, but not declared as action outputs, and only the latter are stored in the action
    // cache.
    ImmutableMultimap.Builder<String, Path> builder = ImmutableMultimap.builder();
    if (resolver.toPath(getTestLog()).exists()) {
      builder.put(TestFileNameConstants.TEST_LOG, resolver.toPath(getTestLog()));
    }
    if (getCoverageData() != null && resolver.toPath(getCoverageData()).exists()) {
      builder.put(TestFileNameConstants.TEST_COVERAGE, resolver.toPath(getCoverageData()));
    }
    if (execRoot != null) {
      ResolvedPaths resolvedPaths = resolve(execRoot);
      if (resolvedPaths.getTestStderr().exists()) {
        builder.put(TestFileNameConstants.TEST_STDERR, resolvedPaths.getTestStderr());
      }
      if (resolvedPaths.getXmlOutputPath().exists()) {
        builder.put(TestFileNameConstants.TEST_XML, resolvedPaths.getXmlOutputPath());
      }
      if (resolvedPaths.getSplitLogsPath().exists()) {
        builder.put(TestFileNameConstants.SPLIT_LOGS, resolvedPaths.getSplitLogsPath());
      }
      if (resolvedPaths.getTestWarningsPath().exists()) {
        builder.put(TestFileNameConstants.TEST_WARNINGS, resolvedPaths.getTestWarningsPath());
      }
      if (testConfiguration.getZipUndeclaredTestOutputs()
          && resolvedPaths.getUndeclaredOutputsZipPath().exists()) {
        builder.put(
            TestFileNameConstants.UNDECLARED_OUTPUTS_ZIP,
            resolvedPaths.getUndeclaredOutputsZipPath());
      }
      if (!testConfiguration.getZipUndeclaredTestOutputs()
          && resolvedPaths.getUndeclaredOutputsDir().exists()) {
        addAllFilesInUndeclaredOutputsDirectory(builder, resolvedPaths.getUndeclaredOutputsDir());
      }
      if (resolvedPaths.getUndeclaredOutputsManifestPath().exists()) {
        builder.put(
            TestFileNameConstants.UNDECLARED_OUTPUTS_MANIFEST,
            resolvedPaths.getUndeclaredOutputsManifestPath());
      }
      if (resolvedPaths.getUndeclaredOutputsAnnotationsPath().exists()) {
        builder.put(
            TestFileNameConstants.UNDECLARED_OUTPUTS_ANNOTATIONS,
            resolvedPaths.getUndeclaredOutputsAnnotationsPath());
      }
      if (resolvedPaths.getUndeclaredOutputsAnnotationsPbPath().exists()) {
        builder.put(
            TestFileNameConstants.UNDECLARED_OUTPUTS_ANNOTATIONS_PB,
            resolvedPaths.getUndeclaredOutputsAnnotationsPbPath());
      }
      if (resolvedPaths.getUnusedRunfilesLogPath().exists()) {
        builder.put(
            TestFileNameConstants.UNUSED_RUNFILES_LOG, resolvedPaths.getUnusedRunfilesLogPath());
      }
      if (resolvedPaths.getInfrastructureFailureFile().exists()) {
        builder.put(
            TestFileNameConstants.TEST_INFRASTRUCTURE_FAILURE,
            resolvedPaths.getInfrastructureFailureFile());
      }
    }
    return builder.build();
  }

  private static void addAllFilesInUndeclaredOutputsDirectory(
      ImmutableMultimap.Builder<String, Path> builder, Path undeclaredOutputsDir)
      throws IOException {
    ArrayDeque<Path> dirsToVisit = new ArrayDeque<>();
    dirsToVisit.add(undeclaredOutputsDir);
    while (!dirsToVisit.isEmpty()) {
      Path dir = dirsToVisit.pop();
      List<Dirent> sortedEntries = new ArrayList<>(dir.readdir(Symlinks.FOLLOW));
      sortedEntries.sort(Comparator.comparing(Dirent::getName));
      for (Dirent dirent : sortedEntries) {
        Path child = dir.getChild(dirent.getName());
        if (dirent.getType().equals(Dirent.Type.DIRECTORY)) {
          dirsToVisit.add(child);
        } else if (dirent.getType().equals(Dirent.Type.FILE)) {
          String name =
              TestFileNameConstants.UNDECLARED_OUTPUTS_DIR
                  + "/"
                  + child.relativeTo(undeclaredOutputsDir);
          builder.put(name, child);
        }
      }
    }
  }

  // Test actions are always distinguished by their target name, which must be unique.
  @Override
  public final boolean isShareable() {
    return false;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    // TODO(b/150305897): use addUUID?
    fp.addString(GUID);
    fp.addIterableStrings(executionSettings.getArgs().arguments());
    fp.addString(Strings.nullToEmpty(executionSettings.getTestFilter()));
    fp.addBoolean(executionSettings.getTestRunnerFailFast());
    RunUnder runUnder = executionSettings.getRunUnder();
    fp.addString(runUnder == null ? "" : runUnder.value());
    extraTestEnv.addTo(fp);
    // TODO(ulfjack): It might be better for performance to hash the action and test envs in config,
    // and only add a hash here.
    configuration.getActionEnvironment().addTo(fp);
    configuration.getTestActionEnvironment().addTo(fp);
    // The 'requiredClientEnvVariables' are handled by Skyframe and don't need to be added here.
    fp.addString(testProperties.getSize().toString());
    fp.addString(testProperties.getTimeout().toString());
    fp.addStrings(testProperties.getTags());
    fp.addBoolean(testProperties.isRemotable());
    fp.addInt(shardNum);
    fp.addInt(executionSettings.getTotalShards());
    fp.addInt(runNumber);
    fp.addInt(executionSettings.getTotalRuns());
    fp.addBoolean(configuration.isCodeCoverageEnabled());
    fp.addBoolean(testConfiguration.getZipUndeclaredTestOutputs());
    fp.addStringMap(getExecutionInfo());
  }

  /**
   * Returns whether the test should be executed unconditionally based on the test configuration,
   * the test properties, and the previous test result when known.
   */
  @Override
  public boolean executeUnconditionally() {
    // Note: isVolatile must return true if executeUnconditionally can ever return true
    // for this instance.
    return executeUnconditionally(
        testConfiguration.cacheTestResults(),
        this::maybeReadCacheStatus,
        testProperties.isExternal(),
        executionSettings.getTotalRuns());
  }

  @VisibleForTesting
  static boolean executeUnconditionally(
      TriState cacheTestResults,
      Supplier<Optional<TestResultData>> prevStatus, // lazy to avoid I/O if possible
      boolean isExternal,
      int runsPerTest) {
    if (!shouldAcceptCachedResult(cacheTestResults, isExternal, runsPerTest)) {
      return true;
    }
    Optional<TestResultData> status = prevStatus.get();
    if (status.isEmpty()) {
      // Execute unconditionally if a previous test result is not available.
      return true;
    }
    if (!status.get().getCachable()) {
      // Execute unconditionally if the previous test result was marked non-cacheable.
      // It seems that this can only happen with --experimental_cancel_concurrent_tests.
      return true;
    }
    if (cacheTestResults == TriState.AUTO && !status.get().getTestPassed()) {
      // Execute unconditionally if the previous test result was a failure, as otherwise we can
      // get stuck forever in the event of a flaky failure.
      return true;
    }
    return false;
  }

  @Override
  public boolean isVolatile() {
    return true;
  }

  /** Saves cache status to disk. */
  public void saveCacheStatus(ActionExecutionContext actionExecutionContext, TestResultData data)
      throws IOException {
    try (OutputStream out = actionExecutionContext.getInputPath(cacheStatus).getOutputStream()) {
      data.writeTo(out);
      // set unconditionally at the end of test action execution
      cachedTestResultData = Optional.of(data);
    } catch (IOException e) {
      cachedTestResultData = Optional.empty();
      throw e;
    }
  }

  /**
   * Sets cachedTestResultData, if not already set, to the cached status from disk or empty optional
   * if the file doesn't exist or if there is an error. Then returns cachedTestResultData.
   */
  @VisibleForTesting
  Optional<TestResultData> maybeReadCacheStatus() {
    try {
      if (cachedTestResultData == null) {
        TestResultData testing = readCacheStatus();
        cachedTestResultData = Optional.of(testing);
      }
    } catch (FileNotFoundException e) {
      cachedTestResultData = Optional.empty();
    } catch (IOException e) {
      logger.atInfo().log("Unexpected IOException thrown while reading cached status.");
      cachedTestResultData = Optional.empty();
    }
    return checkNotNull(cachedTestResultData);
  }

  @VisibleForTesting
  TestResultData readCacheStatus() throws IOException {
    try (InputStream in = cacheStatus.getPath().getInputStream()) {
      return TestResultData.parseFrom(in, ExtensionRegistry.getEmptyRegistry());
    }
  }

  /**
   * Returns whether a cached result should be accepted from a disk/remote cache, depending on the
   * test configuration and test properties.
   *
   * <p>This should *not* be used to determine whether to accept a cached result from the action
   * cache. Call {@link #executeUnconditionally} instead.
   *
   * <p>Unlike {@link #executeUnconditionally}, this decision does not depend on the previous test
   * result, as otherwise we wouldn't attempt to hit the disk/remote cache when the test has changed
   * from a failing to a passing state since the last execution without causing the action to be
   * reanalyzed (for example, by editing a source file into a passing state that has been previously
   * seen).
   *
   * <p>We're not concerned about a flaky failure becoming sticky in the disk/remote cache, because
   * it's impossible to solve this problem generally. In any case, this can only occur with a remote
   * execution implementation that caches failures, as we never upload them to a disk/remote cache
   * ourselves.
   */
  public boolean shouldAcceptCachedResult() {
    return shouldAcceptCachedResult(
        testConfiguration.cacheTestResults(),
        testProperties.isExternal(),
        executionSettings.getTotalRuns());
  }

  @VisibleForTesting
  static boolean shouldAcceptCachedResult(
      TriState cacheTestResults, boolean isExternal, int runsPerTest) {
    if (isExternal || cacheTestResults == TriState.NO) {
      return false;
    }
    if (cacheTestResults == TriState.AUTO && runsPerTest > 1) {
      return false;
    }
    return true;
  }

  @Override
  public boolean actionCacheHit(ActionCachedContext executor) {
    maybeReadCacheStatus();
    if (cachedTestResultData.isEmpty()) {
      executor.getEventHandler().handle(Event.warn(getErrorMessageOnCachedTestResultError()));
      return false;
    }
    try {
      ImmutableMultimap<String, Path> testOutputs =
          getTestOutputsMapping(executor.getPathResolver(), executor.getExecRoot());
      executor
          .getEventHandler()
          .post(
              executor
                  .getContext(TestActionContext.class)
                  .newCachedTestResult(
                      executor.getExecRoot(), this, cachedTestResultData.get(), testOutputs));
    } catch (IOException e) {
      logger.atInfo().log("%s", getErrorMessageOnNewCachedTestResultError(e.getMessage()));
      executor
          .getEventHandler()
          .handle(Event.warn(getErrorMessageOnNewCachedTestResultError(e.getMessage())));
      return false;
    }
    return true;
  }

  @VisibleForTesting
  String getErrorMessageOnNewCachedTestResultError(String exceptionMsg) {
    return getErrorMessageOnCachedTestResultError() + ": " + exceptionMsg;
  }

  @VisibleForTesting
  String getErrorMessageOnCachedTestResultError() {
    return "Cached test status was unexpectedly unavailable on disk: could be result of"
        + " expired authentication, bad disk, or modifications in the output tree."
        + " From "
        + describe();
  }

  @Override
  protected String getRawProgressMessage() {
    return "Testing " + getTestName();
  }

  @Override
  protected Iterable<PathFragment> getAdditionalPathOutputsToDelete() {
    return filesToDeleteBeforeExecution;
  }

  @Override
  protected Iterable<PathFragment> getDirectoryOutputsToDelete() {
    return directoriesToDeleteBeforeExecution;
  }

  void createEmptyOutputs(ActionExecutionContext context) throws IOException {
    for (Artifact output : TestRunnerAction.this.getOutputs()) {
      FileSystemUtils.touchFile(context.getInputPath(output));
    }
  }

  public void setupEnvVariables(Map<String, String> env) {
    env.put("TEST_TARGET", Label.print(getOwner().getLabel()));
    env.put("TEST_SIZE", getTestProperties().getSize().toString());
    env.put("TEST_TIMEOUT", Long.toString(getTimeout().toSeconds()));
    env.put("TEST_WORKSPACE", getRunfilesPrefix());
    env.put(
        "TEST_BINARY",
        getExecutionSettings()
            .getExecutable()
            .getRunfilesPath()
            .getCallablePathStringForOs(executionSettings.getExecutionOs()));

    // When we run test multiple times, set different TEST_RANDOM_SEED values for each run.
    // Don't override any previous setting.
    if (executionSettings.getTotalRuns() > 1 && !env.containsKey("TEST_RANDOM_SEED")) {
      env.put("TEST_RANDOM_SEED", Integer.toString(getRunNumber() + 1));
    }
    // TODO(b/184206260): Actually set TEST_RANDOM_SEED with random seed.
    // The above TEST_RANDOM_SEED has historically been set with the run number, but we should
    // explicitly set TEST_RUN_NUMBER to indicate the run number and actually set TEST_RANDOM_SEED
    // with a random seed. However, much code has come to depend on it being set to the run number
    // and this is an externally documented behavior. Modifying TEST_RANDOM_SEED should be done
    // carefully.
    if (executionSettings.getTotalRuns() > 1 && !env.containsKey("TEST_RUN_NUMBER")) {
      env.put("TEST_RUN_NUMBER", Integer.toString(getRunNumber() + 1));
    }

    String testFilter = getExecutionSettings().getTestFilter();
    if (testFilter != null) {
      env.put(TEST_BRIDGE_TEST_FILTER_ENV, testFilter);
    }
    if (testConfiguration.getTestRunnerFailFast()) {
      env.put("TESTBRIDGE_TEST_RUNNER_FAIL_FAST", "1");
    }

    env.put("TEST_WARNINGS_OUTPUT_FILE", getTestWarningsPath().getPathString());
    env.put("TEST_UNUSED_RUNFILES_LOG_FILE", getUnusedRunfilesLogPath().getPathString());

    env.put("TEST_LOGSPLITTER_OUTPUT_FILE", getSplitLogsPath().getPathString());

    if (testConfiguration.getZipUndeclaredTestOutputs()) {
      env.put("TEST_UNDECLARED_OUTPUTS_ZIP", getUndeclaredOutputsZipPath().getPathString());
    }

    env.put("TEST_UNDECLARED_OUTPUTS_DIR", undeclaredOutputsDir.getExecPathString());
    env.put("TEST_UNDECLARED_OUTPUTS_MANIFEST", getUndeclaredOutputsManifestPath().getPathString());
    env.put(
        "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS",
        getUndeclaredOutputsAnnotationsPath().getPathString());
    env.put(
        "TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR",
        getUndeclaredOutputsAnnotationsDir().getPathString());

    env.put("TEST_PREMATURE_EXIT_FILE", getExitSafeFile().getPathString());
    env.put("TEST_INFRASTRUCTURE_FAILURE_FILE", getInfrastructureFailureFile().getPathString());

    if (isSharded()) {
      env.put("TEST_SHARD_INDEX", Integer.toString(getShardNum()));
      env.put("TEST_TOTAL_SHARDS", Integer.toString(getExecutionSettings().getTotalShards()));
      env.put("TEST_SHARD_STATUS_FILE", getTestShard().getPathString());
    }
    env.put("XML_OUTPUT_FILE", getXmlOutputPath().getPathString());

    if (!configuration.runfilesEnabled()) {
      // If runfiles are disabled, tell remote-runtest.sh/local-runtest.sh about that.
      env.put("RUNFILES_MANIFEST_ONLY", "1");
    }

    if (isCoverageMode()) {
      // Instruct remote-runtest.sh/local-runtest.sh not to cd into the runfiles directory.
      // TODO(ulfjack): Find a way to avoid setting this variable.
      env.put("RUNTEST_PRESERVE_CWD", "1");

      env.put("COVERAGE_MANIFEST", getCoverageManifest().getExecPathString());
      env.put("COVERAGE_DIR", getCoverageDirectory().getPathString());
      env.put("COVERAGE_OUTPUT_FILE", getCoverageData().getExecPathString());
      env.put("SPLIT_COVERAGE_POST_PROCESSING", splitCoveragePostProcessing ? "1" : "0");
      env.put("IS_COVERAGE_SPAWN", "0");
    }
  }

  /**
   * Gets the test name in a user-friendly format. Will generally include the target name and
   * run/shard numbers, if applicable.
   */
  public String getTestName() {
    String suffix = getTestSuffix();
    String label = Label.print(getOwner().getLabel());
    return suffix.isEmpty() ? label : label + " " + suffix;
  }

  /**
   * Gets the test suffix in a user-friendly format, eg "(shard 1 of 7)". Will include the target
   * name and run/shard numbers, if applicable.
   */
  public String getTestSuffix() {
    int totalShards = executionSettings.getTotalShards();
    // Use a 1-based index for user friendliness.
    int runsPerTest = executionSettings.getTotalRuns();
    if (totalShards > 1 && runsPerTest > 1) {
      return String.format(
          "(shard %d of %d, run %d of %d)", shardNum + 1, totalShards, runNumber + 1, runsPerTest);
    } else if (totalShards > 1) {
      return String.format("(shard %d of %d)", shardNum + 1, totalShards);
    } else if (runsPerTest > 1) {
      return String.format("(run %d of %d)", runNumber + 1, runsPerTest);
    } else {
      return "";
    }
  }

  /** Returns the timeout for this test action, respecting the value of {@code --test_timeout}. */
  public Duration getTimeout() {
    return testConfiguration.getTestTimeout().get(testProperties.getTimeout());
  }

  public Artifact getTestLog() {
    return testLog;
  }

  /** Returns all environment variables which must be set in order to run this test. */
  public ActionEnvironment getExtraTestEnv() {
    return extraTestEnv;
  }

  @Override
  public Collection<String> getClientEnvironmentVariables() {
    return requiredClientEnvVariables;
  }

  public ResolvedPaths resolve(Path execRoot) {
    return new ResolvedPaths(execRoot);
  }

  public Artifact getCacheStatusArtifact() {
    return cacheStatus;
  }

  public PathFragment getTestStderrPath() {
    return testStderr;
  }

  public PathFragment getTestWarningsPath() {
    return testWarningsPath;
  }

  public PathFragment getUnusedRunfilesLogPath() {
    return unusedRunfilesLogPath;
  }

  public PathFragment getSplitLogsPath() {
    return splitLogsPath;
  }

  public Artifact getUndeclaredOutputsDir() {
    return undeclaredOutputsDir;
  }

  /** Returns path to the optional zip file of undeclared test outputs. */
  public PathFragment getUndeclaredOutputsZipPath() {
    return undeclaredOutputsDir.getExecPath().getChild(UNDECLARED_OUTPUTS_ZIP_NAME);
  }

  /** Returns path to the undeclared output manifest file. */
  public PathFragment getUndeclaredOutputsManifestPath() {
    return undeclaredOutputsManifestPath;
  }

  public PathFragment getUndeclaredOutputsAnnotationsDir() {
    return undeclaredOutputsAnnotationsDir;
  }

  /** Returns path to the undeclared output annotations file. */
  public PathFragment getUndeclaredOutputsAnnotationsPath() {
    return undeclaredOutputsAnnotationsPath;
  }

  /** Returns path to the undeclared output annotations file. */
  public PathFragment getUndeclaredOutputsAnnotationsPbPath() {
    return undeclaredOutputsAnnotationsPbPath;
  }

  public PathFragment getTestShard() {
    return testShard;
  }

  public PathFragment getExitSafeFile() {
    return testExitSafe;
  }

  public PathFragment getInfrastructureFailureFile() {
    return testInfrastructureFailure;
  }

  /** Returns path to the optionally created XML output file created by the test. */
  public PathFragment getXmlOutputPath() {
    return xmlOutputPath;
  }

  /** Returns coverage data artifact or null if code coverage was not requested. */
  @Nullable
  public Artifact getCoverageData() {
    return coverageData;
  }

  @Nullable
  public Artifact getCoverageManifest() {
    return getExecutionSettings().getInstrumentedFileManifest();
  }

  /** Returns true if coverage data should be gathered. */
  public boolean isCoverageMode() {
    return coverageData != null;
  }

  /**
   * Returns a directory to temporarily store coverage results for the given action relative to the
   * execution root. This directory is used to store all coverage results related to the test
   * execution with exception of the locally generated *.gcda files. Those are stored separately
   * using relative path within coverage directory.
   *
   * <p>If the coverageDirectory field is set, then its exec path is returned. This is a tree
   * artifact, meaning that all files in the corresponding directories are returned from sandboxed
   * or remote execution.
   *
   * <p>Otherwise, the directory name for the given test runner action is constructed as: {@code
   * [blaze-out/.../testlogs/]_coverage/target_path/test_log_name} where {@code test_log_name} is
   * usually a target name but potentially can include extra suffix, such as a shard number (if test
   * execution was sharded).
   */
  public PathFragment getCoverageDirectory() {
    if (coverageDirectory != null) {
      return coverageDirectory.getExecPath();
    }
    PathFragment coverageRoot = getTestLog().getRoot().getExecPath().getRelative(COVERAGE_TMP_ROOT);
    return coverageRoot.getRelative(
        FileSystemUtils.removeExtension(getTestLog().getRootRelativePath()));
  }

  public TestTargetProperties getTestProperties() {
    return testProperties;
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return mergeMaps(super.getExecutionInfo(), testProperties.getExecutionInfo());
  }

  public TestTargetExecutionSettings getExecutionSettings() {
    return executionSettings;
  }

  public boolean isSharded() {
    return testShard != null;
  }

  /**
   * Returns the shard number for this action. If getTotalShards() > 0, must be >= 0 and <
   * getTotalShards(). Otherwise, must be 0.
   */
  public int getShardNum() {
    return shardNum;
  }

  /** Returns run number. */
  public int getRunNumber() {
    return runNumber;
  }

  /** Returns the workspace name. */
  public String getRunfilesPrefix() {
    return workspaceName;
  }

  public PackageSpecificationProvider getNetworkAllowlist() {
    return networkAllowlist;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    TestActionContext context = actionExecutionContext.getContext(TestActionContext.class);
    return execute(actionExecutionContext, context);
  }

  @VisibleForTesting
  public ActionResult execute(
      ActionExecutionContext actionExecutionContext, TestActionContext testActionContext)
      throws ActionExecutionException, InterruptedException {

    List<SpawnResult> spawnResults = new ArrayList<>();
    List<ProcessedAttemptResult> failedAttempts = new ArrayList<>();
    TestRunnerSpawn testRunnerSpawn = null;
    AttemptGroup attemptGroup = null;

    try {
      try {
        testRunnerSpawn = testActionContext.createTestRunnerSpawn(this, actionExecutionContext);
        attemptGroup =
            cancelConcurrentTestsOnSuccess
                ? testActionContext.getAttemptGroup(getOwner(), shardNum)
                : AttemptGroup.NOOP;
        try {
          attemptGroup.register();
          var result =
              executeAllAttempts(
                  testRunnerSpawn,
                  testActionContext.isTestKeepGoing(),
                  attemptGroup,
                  spawnResults,
                  failedAttempts);

          // If the current test attempt is requested to be cancelled after it has finished, we need
          // to handle the interruption here and clear the interrupted status. Otherwise, the
          // interrupted status will be propagated to skyframe and the whole invocation will be
          // cancelled.
          if (Thread.interrupted()) {
            throw new InterruptedException();
          }

          return result;
        } finally {
          attemptGroup.unregister();
        }
      } catch (InterruptedException e) {
        if (!attemptGroup.cancelled()) {
          throw e;
        }

        testRunnerSpawn.finalizeCancelledTest(failedAttempts);
        createEmptyOutputs(testRunnerSpawn.getActionExecutionContext());
        return ActionResult.create(spawnResults);
      }
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, this);
    } catch (IOException e) {
      throw ActionExecutionException.fromExecException(
          new EnvironmentalExecException(e, Code.TEST_RUNNER_IO_EXCEPTION), this);
    }
  }

  @Override
  public String getMnemonic() {
    return MNEMONIC;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.copyOf(getOutputs());
  }

  Artifact getTestSetupScript() {
    return testSetupScript;
  }

  public Artifact getTestXmlGeneratorScript() {
    return testXmlGeneratorScript;
  }

  @Nullable
  public Artifact getCollectCoverageScript() {
    return collectCoverageScript;
  }

  @Nullable
  PathFragment getShExecutableMaybe() {
    return shExecutable;
  }

  @Override
  public List<String> getArguments() throws CommandLineExpansionException, InterruptedException {
    return TestStrategy.expandedArgsFromAction(this);
  }

  @Override
  public ImmutableMap<String, String> getIncompleteEnvironmentForTesting()
      throws ActionExecutionException {
    return getEnvironment().getFixedEnv();
  }

  @Override
  public NestedSet<Artifact> getPossibleInputsForTesting() {
    return getInputs();
  }

  /** The same set of paths as the parent test action, resolved against a given exec root. */
  public final class ResolvedPaths {
    private final Path execRoot;

    ResolvedPaths(Path execRoot) {
      this.execRoot = checkNotNull(execRoot);
    }

    private Path getPath(PathFragment relativePath) {
      return execRoot.getRelative(relativePath);
    }

    public final Path getBaseDir() {
      return getPath(baseDir);
    }

    /**
     * In rare cases, error messages will be printed to stderr instead of stdout. The test action is
     * responsible for appending anything in the stderr file to the real test.log.
     */
    public Path getTestStderr() {
      return getPath(testStderr);
    }

    public Path getTestWarningsPath() {
      return getPath(testWarningsPath);
    }

    public Path getSplitLogsPath() {
      return getPath(splitLogsPath);
    }

    public Path getUnusedRunfilesLogPath() {
      return getPath(unusedRunfilesLogPath);
    }

    /** Returns path to the directory containing the split logs (raw and proto file). */
    public Path getSplitLogsDir() {
      return getPath(splitLogsDir);
    }

    /** Returns path to the optional zip file of undeclared test outputs. */
    public Path getUndeclaredOutputsZipPath() {
      return getUndeclaredOutputsDir().getChild(UNDECLARED_OUTPUTS_ZIP_NAME);
    }

    /** Returns path to the directory to hold undeclared test outputs. */
    public Path getUndeclaredOutputsDir() {
      return getPath(undeclaredOutputsDir.getExecPath());
    }

    /** Returns path to the directory to hold undeclared output annotations parts. */
    public Path getUndeclaredOutputsAnnotationsDir() {
      return getPath(undeclaredOutputsAnnotationsDir);
    }

    /** Returns path to the undeclared output manifest file. */
    public Path getUndeclaredOutputsManifestPath() {
      return getPath(undeclaredOutputsManifestPath);
    }

    /** Returns path to the undeclared output annotations file. */
    public Path getUndeclaredOutputsAnnotationsPath() {
      return getPath(undeclaredOutputsAnnotationsPath);
    }

    /** Returns path to the undeclared output annotations pb file. */
    public Path getUndeclaredOutputsAnnotationsPbPath() {
      return getPath(undeclaredOutputsAnnotationsPbPath);
    }

    @Nullable
    public Path getTestShard() {
      return testShard == null ? null : getPath(testShard);
    }

    public Path getExitSafeFile() {
      return getPath(testExitSafe);
    }

    public Path getInfrastructureFailureFile() {
      return getPath(testInfrastructureFailure);
    }

    /** Returns path to the optionally created XML output file created by the test. */
    public Path getXmlOutputPath() {
      return getPath(xmlOutputPath);
    }

    public Path getCoverageDirectory() {
      return getPath(TestRunnerAction.this.getCoverageDirectory());
    }

    public Path getCoverageDataPath() {
      return getPath(getCoverageData().getExecPath());
    }
  }

  public ActionResult executeAllAttempts(
      TestRunnerSpawn testRunnerSpawn,
      boolean keepGoing,
      final AttemptGroup attemptGroup,
      List<SpawnResult> spawnResults,
      List<ProcessedAttemptResult> failedAttempts)
      throws ExecException, IOException, InterruptedException {
    int maxAttempts = 0;

    while (true) {
      TestAttemptResult result = testRunnerSpawn.execute();
      int actualMaxAttempts =
          failedAttempts.isEmpty() ? testRunnerSpawn.getMaxAttempts(result) : maxAttempts;
      Preconditions.checkState(actualMaxAttempts != 0);

      spawnResults.addAll(result.spawnResults());
      TestAttemptResult.Result testResult = result.result();
      if (testResult == TestAttemptResult.Result.PASSED) {
        attemptGroup.cancelOthers();
      } else {
        TestRunnerSpawnAndMaxAttempts nextRunnerAndAttempts =
            computeNextRunnerAndMaxAttempts(
                testResult,
                testRunnerSpawn,
                failedAttempts.size() + 1,
                actualMaxAttempts,
                spawnResults);
        if (nextRunnerAndAttempts != null) {
          failedAttempts.add(
              testRunnerSpawn.finalizeFailedTestAttempt(result, failedAttempts.size() + 1));

          // Change the phase here because we are executing a rerun of the failed attempt.
          testRunnerSpawn
              .getActionExecutionContext()
              .getEventHandler()
              .post(new SpawnExecutedEvent.ChangePhase(this));

          testRunnerSpawn = nextRunnerAndAttempts.spawn();
          maxAttempts = nextRunnerAndAttempts.maxAttempts();
          continue;
        }
      }
      testRunnerSpawn.finalizeTest(result, failedAttempts);

      if (!keepGoing && testResult != TestAttemptResult.Result.PASSED) {
        DetailedExitCode systemFailure = result.primarySystemFailure();
        if (systemFailure != null) {
          throw new TestExecException(
              "Test failed (system error), aborting: "
                  + systemFailure.getFailureDetail().getMessage(),
              systemFailure.getFailureDetail());
        }
        String errorMessage = "Test failed, aborting";
        throw new TestExecException(
            errorMessage,
            FailureDetail.newBuilder()
                .setTestAction(
                    TestAction.newBuilder().setCode(TestAction.Code.NO_KEEP_GOING_TEST_FAILURE))
                .setMessage(errorMessage)
                .build());
      }
      return ActionResult.create(spawnResults);
    }
  }

  /**
   * Method used to compute next runner and max attempts. Returns null if there if there is no
   * remaining attempts (including fallback runner).
   */
  @VisibleForTesting
  @Nullable
  static TestRunnerSpawnAndMaxAttempts computeNextRunnerAndMaxAttempts(
      TestAttemptResult.Result result,
      TestRunnerSpawn testRunnerSpawn,
      int numAttempts,
      int maxAttempts,
      List<SpawnResult> results)
      throws ExecException, InterruptedException {
    checkState(result != Result.PASSED, "Should not compute retry runner if last result passed");
    if (result.canRetry() && numAttempts < maxAttempts) {
      TestRunnerSpawn nextRunner = testRunnerSpawn.getFlakyRetryRunner(results);
      if (nextRunner != null) {
        return TestRunnerSpawnAndMaxAttempts.create(nextRunner, maxAttempts);
      }
    } else {
      TestRunnerSpawn nextRunner = testRunnerSpawn.getFallbackRunner();
      if (nextRunner != null) {
        // We only support one level of fallback, in which case maxAttempts gets *added* once. We
        // don't support a different number of max attempts for the fallback strategy.
        return TestRunnerSpawnAndMaxAttempts.create(nextRunner, numAttempts + maxAttempts);
      }
    }
    return null;
  }

  /** Value type used to store computed next runner and max attempts. */
  @VisibleForTesting
  record TestRunnerSpawnAndMaxAttempts(TestRunnerSpawn spawn, int maxAttempts) {
    TestRunnerSpawnAndMaxAttempts {
      requireNonNull(spawn, "spawn");
    }

    public static TestRunnerSpawnAndMaxAttempts create(TestRunnerSpawn spawn, int maxAttempts) {
      return new TestRunnerSpawnAndMaxAttempts(spawn, maxAttempts);
    }
  }

  private static class LazySetConcatenation extends AbstractCollection<String> {
    private final ImmutableSet<String> first;
    private final ImmutableSet<String> second;
    private final ImmutableSet<String> third;

    static Collection<String> from(
        ImmutableSet<String> first, ImmutableSet<String> second, ImmutableSet<String> third) {
      boolean firstEmpty = first.isEmpty();
      boolean secondEmpty = second.isEmpty();
      boolean thirdEmpty = third.isEmpty();
      if (firstEmpty && secondEmpty) {
        return third;
      }
      if (firstEmpty && thirdEmpty) {
        return second;
      }
      if (secondEmpty && thirdEmpty) {
        return first;
      }

      return new LazySetConcatenation(first, second, third);
    }

    private LazySetConcatenation(
        ImmutableSet<String> first, ImmutableSet<String> second, ImmutableSet<String> third) {
      this.first = first;
      this.second = second;
      this.third = third;
    }

    @Override
    public Iterator<String> iterator() {
      return Iterators.concat(first.iterator(), second.iterator(), third.iterator());
    }

    @Override
    public int size() {
      return first.size() + second.size() + third.size();
    }

    @Override
    public boolean isEmpty() {
      return false;
    }
  }
}
