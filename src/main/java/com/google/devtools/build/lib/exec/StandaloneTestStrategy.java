// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.exec;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.test.TestAttempt;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction.ResolvedPaths;
import com.google.devtools.build.lib.analysis.test.TestStrategy;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult.ExecutionInfo;
import com.google.devtools.build.lib.buildeventstream.TestFileNameConstants;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.runtime.TestSummaryOptions;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TestAction;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Runs TestRunnerAction actions. */
// TODO(bazel-team): add tests for this strategy.
public class StandaloneTestStrategy extends TestStrategy {
  private static final ImmutableMap<String, String> ENV_VARS =
      ImmutableMap.<String, String>builder()
          .put("TZ", "UTC")
          .put("TEST_SRCDIR", TestPolicy.RUNFILES_DIR)
          // TODO(lberki): Remove JAVA_RUNFILES and PYTHON_RUNFILES.
          .put("JAVA_RUNFILES", TestPolicy.RUNFILES_DIR)
          .put("PYTHON_RUNFILES", TestPolicy.RUNFILES_DIR)
          .put("RUNFILES_DIR", TestPolicy.RUNFILES_DIR)
          .put("TEST_TMPDIR", TestPolicy.TEST_TMP_DIR)
          .put("RUN_UNDER_RUNFILES", "1")
          .buildOrThrow();

  public static final TestPolicy DEFAULT_LOCAL_POLICY = new TestPolicy(ENV_VARS);

  private final Path tmpDirRoot;

  public StandaloneTestStrategy(
      ExecutionOptions executionOptions,
      TestSummaryOptions testSummaryOptions,
      BinTools binTools,
      Path tmpDirRoot) {
    super(executionOptions, testSummaryOptions, binTools);
    this.tmpDirRoot = tmpDirRoot;
  }

  @Override
  public TestRunnerSpawn createTestRunnerSpawn(
      TestRunnerAction action, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    if (action.getExecutionSettings().getInputManifest() == null) {
      throw createTestExecException(
          TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
          "cannot run local tests with --nobuild_runfile_manifests");
    }
    Map<String, String> testEnvironment =
        createEnvironment(
            actionExecutionContext, action, tmpDirRoot, executionOptions.splitXmlGeneration);

    Map<String, String> executionInfo = new TreeMap<>(action.getExecutionInfo());
    if (!action.shouldAcceptCachedResult()) {
      // TODO(tjgq): We want to reject a previously cached result, but not prevent the result of the
      // current execution from being uploaded. We should introduce a separate execution requirement
      // for this.
      executionInfo.put(ExecutionRequirements.NO_CACHE, "");
    }
    executionInfo.put(
        ExecutionRequirements.TIMEOUT, Long.toString(action.getTimeout().toSeconds()));

    SimpleSpawn.LocalResourcesSupplier localResourcesSupplier =
        () ->
            action
                .getTestProperties()
                .getLocalResourceUsage(
                    action.getOwner().getLabel(), executionOptions.usingLocalTestJobs());

    Spawn spawn =
        new SimpleSpawn(
            action,
            getArgs(action),
            ImmutableMap.copyOf(testEnvironment),
            ImmutableMap.copyOf(executionInfo),
            ImmutableMap.of(),
            /* inputs= */ action.getInputs(),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableSet.copyOf(action.getSpawnOutputs()),
            /* mandatoryOutputs= */ ImmutableSet.of(),
            localResourcesSupplier);
    Path execRoot = actionExecutionContext.getExecRoot();
    ArtifactPathResolver pathResolver = actionExecutionContext.getPathResolver();
    Path tmpDir = pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)));
    return new StandaloneTestRunnerSpawn(action, actionExecutionContext, spawn, tmpDir, execRoot);
  }

  private static ImmutableMultimap<String, Path> renameOutputs(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      ImmutableMultimap<String, Path> testOutputs,
      int attemptId)
      throws IOException {
    // Rename outputs
    String namePrefix =
        FileSystemUtils.removeExtension(action.getTestLog().getExecPath().getBaseName());
    Path testRoot = actionExecutionContext.getInputPath(action.getTestLog()).getParentDirectory();
    Path attemptsDir = testRoot.getChild(namePrefix + "_attempts");
    attemptsDir.createDirectory();
    String attemptPrefix = "attempt_" + attemptId;
    Path testLog = attemptsDir.getChild(attemptPrefix + ".log");

    // Get the normal test output paths, and then update them to use "attempt_N" names, and
    // attemptDir, before adding them to the outputs.
    ImmutableMultimap.Builder<String, Path> testOutputsBuilder = ImmutableMultimap.builder();
    for (Map.Entry<String, Path> testOutput : testOutputs.entries()) {
      // e.g. /testRoot/test.dir/file, an example we follow throughout this loop's comments.
      Path testOutputPath = testOutput.getValue();
      Path destinationPath;
      if (testOutput.getKey().equals(TestFileNameConstants.TEST_LOG)) {
        // The rename rules for the test log are different than for all the other files.
        destinationPath = testLog;
      } else {
        // e.g. test.dir/file
        PathFragment relativeToTestDirectory = testOutputPath.relativeTo(testRoot);

        // e.g. attempt_1.dir/file
        String destinationPathFragmentStr =
            relativeToTestDirectory.getSafePathString().replaceFirst("test", attemptPrefix);
        PathFragment destinationPathFragment = PathFragment.create(destinationPathFragmentStr);

        // e.g. /attemptsDir/attempt_1.dir/file
        destinationPath = attemptsDir.getRelative(destinationPathFragment);
        destinationPath.getParentDirectory().createDirectoryAndParents();
      }

      // Move to the destination.
      testOutputPath.renameTo(destinationPath);

      testOutputsBuilder.put(testOutput.getKey(), destinationPath);
    }
    return testOutputsBuilder.build();
  }

  private StandaloneProcessedAttemptResult processFailedTestAttempt(
      int attemptId,
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      StandaloneTestResult result)
      throws IOException {
    return processTestAttempt(
        attemptId, /*isLastAttempt=*/ false, actionExecutionContext, action, result);
  }

  private void finalizeTest(
      TestRunnerAction action,
      ActionExecutionContext actionExecutionContext,
      StandaloneTestResult standaloneTestResult,
      List<ProcessedAttemptResult> failedAttempts)
      throws IOException {
    StandaloneProcessedAttemptResult lastAttempt =
        processTestAttempt(
            failedAttempts.size() + 1,
            /* isLastAttempt= */ true,
            actionExecutionContext,
            action,
            standaloneTestResult);

    TestResultData.Builder dataBuilder = standaloneTestResult.testResultDataBuilder();
    for (ProcessedAttemptResult failedAttempt : failedAttempts) {
      TestResultData failedAttemptData =
          ((StandaloneProcessedAttemptResult) failedAttempt).testResultData();
      dataBuilder.addAllFailedLogs(failedAttemptData.getFailedLogsList());
      dataBuilder.addTestTimes(failedAttemptData.getTestTimes(0));
      dataBuilder.addAllTestProcessTimes(failedAttemptData.getTestProcessTimesList());
    }
    if (dataBuilder.getStatus() == BlazeTestStatus.PASSED && !failedAttempts.isEmpty()) {
      dataBuilder.setStatus(BlazeTestStatus.FLAKY);
    }
    TestResultData data = dataBuilder.build();
    TestResult result =
        new TestResult(
            action,
            data,
            lastAttempt.testOutputs(),
            false,
            standaloneTestResult.primarySystemFailure());
    postTestResult(actionExecutionContext, result);
  }

  private StandaloneProcessedAttemptResult processTestAttempt(
      int attemptId,
      boolean isLastAttempt,
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      StandaloneTestResult result)
      throws IOException {
    ImmutableMultimap<String, Path> testOutputs =
        action.getTestOutputsMapping(
            actionExecutionContext.getPathResolver(), actionExecutionContext.getExecRoot());
    if (!isLastAttempt) {
      testOutputs = renameOutputs(actionExecutionContext, action, testOutputs, attemptId);
    }

    // Recover the test log path, which may have been renamed, and add it to the data builder.
    Path renamedTestLog = null;
    for (Map.Entry<String, Path> pair : testOutputs.entries()) {
      if (TestFileNameConstants.TEST_LOG.equals(pair.getKey())) {
        Preconditions.checkState(renamedTestLog == null, "multiple test_log matches");
        renamedTestLog = pair.getValue();
      }
    }

    TestResultData.Builder dataBuilder = result.testResultDataBuilder();
    // If the test log path does not exist, mark the test as incomplete
    if (renamedTestLog == null) {
      dataBuilder.setStatus(BlazeTestStatus.INCOMPLETE);
    }

    if (dataBuilder.getStatus() == BlazeTestStatus.PASSED) {
      dataBuilder.setPassedLog(renamedTestLog.toString());
    } else if (dataBuilder.getStatus() != BlazeTestStatus.INCOMPLETE) {
      dataBuilder.addFailedLogs(renamedTestLog.toString());
    }

    if (!result.spawnResults().isEmpty()) {
      dataBuilder.setExitCode(result.spawnResults().get(0).exitCode());
    }

    // Add the test log to the output
    TestResultData data = dataBuilder.build();
    actionExecutionContext
        .getEventHandler()
        .post(
            TestAttempt.forExecutedTestResult(
                action, data, attemptId, testOutputs, result.executionInfo(), isLastAttempt));
    processTestOutput(actionExecutionContext, data, action.getTestName(), renamedTestLog);
    return new StandaloneProcessedAttemptResult(data, testOutputs);
  }

  private static Map<String, String> setupEnvironment(
      TestRunnerAction action,
      Map<String, String> clientEnv,
      Path execRoot,
      Path runfilesDir,
      Path tmpDir) {
    PathFragment relativeTmpDir;
    if (tmpDir.startsWith(execRoot)) {
      relativeTmpDir = tmpDir.relativeTo(execRoot);
    } else {
      relativeTmpDir = tmpDir.asFragment();
    }
    return DEFAULT_LOCAL_POLICY.computeTestEnvironment(
        action, clientEnv, runfilesDir.relativeTo(execRoot), relativeTmpDir);
  }

  private TestAttemptResult beginTestAttempt(
      TestRunnerAction testAction,
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      Path execRoot)
      throws ExecException, IOException, InterruptedException {
    ResolvedPaths resolvedPaths = testAction.resolve(execRoot);
    Path out = actionExecutionContext.getInputPath(testAction.getTestLog());
    Path err = resolvedPaths.getTestStderr();
    FileOutErr testOutErr = new FileOutErr(out, err);
    Closeable streamed = null;
    if (executionOptions.testOutput.equals(ExecutionOptions.TestOutputFormat.STREAMED)) {
      streamed =
          createStreamedTestOutput(
              Reporter.outErrForReporter(actionExecutionContext.getEventHandler()), out);
    }

    long startTimeMillis = actionExecutionContext.getClock().currentTimeMillis();
    SpawnStrategyResolver resolver = actionExecutionContext.getContext(SpawnStrategyResolver.class);

    return runTestAttempt(
        testAction,
        actionExecutionContext,
        spawn,
        resolver,
        resolvedPaths,
        testOutErr,
        streamed,
        startTimeMillis);
  }

  private static void appendCoverageLog(FileOutErr coverageOutErr, FileOutErr outErr)
      throws IOException {
    writeOutFile(coverageOutErr.getErrorPath(), outErr.getOutputPath());
    writeOutFile(coverageOutErr.getOutputPath(), outErr.getOutputPath());
  }

  private static void writeOutFile(Path inFilePath, Path outFilePath) throws IOException {
    FileStatus stat = inFilePath.statNullable();
    if (stat != null) {
      try {
        if (stat.getSize() > 0) {
          if (outFilePath.exists()) {
            outFilePath.setWritable(true);
          }
          try (OutputStream out = outFilePath.getOutputStream(true);
              InputStream in = inFilePath.getInputStream()) {
            ByteStreams.copy(in, out);
          }
        }
      } finally {
        inFilePath.delete();
      }
    }
  }

  private static BuildEventStreamProtos.TestResult.ExecutionInfo extractExecutionInfo(
      SpawnResult spawnResult, TestResultData.Builder result) {
    BuildEventStreamProtos.TestResult.ExecutionInfo.Builder executionInfo =
        BuildEventStreamProtos.TestResult.ExecutionInfo.newBuilder();

    if (spawnResult.isCacheHit()) {
      result.setRemotelyCached(true);
      executionInfo.setCachedRemotely(true);
    }

    String strategy = spawnResult.getRunnerName();
    if (strategy != null) {
      executionInfo.setStrategy(strategy);
      result.setIsRemoteStrategy(strategy.equals("remote"));
    }

    if (spawnResult.getExecutorHostName() != null) {
      executionInfo.setHostname(spawnResult.getExecutorHostName());
    }

    SpawnMetrics sm = spawnResult.getMetrics();
    executionInfo.setTimingBreakdown(
        BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
            .setName("totalTime")
            .setTime(toProtoDuration(sm.totalTimeInMs()))
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("parseTime")
                    .setTime(toProtoDuration(sm.parseTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("fetchTime")
                    .setTime(toProtoDuration(sm.fetchTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("queueTime")
                    .setTime(toProtoDuration(sm.queueTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("uploadTime")
                    .setTime(toProtoDuration(sm.uploadTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("setupTime")
                    .setTime(toProtoDuration(sm.setupTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("executionWallTime")
                    .setTime(toProtoDuration(sm.executionWallTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("processOutputsTime")
                    .setTime(toProtoDuration(sm.processOutputsTimeInMs()))
                    .build())
            .addChild(
                BuildEventStreamProtos.TestResult.ExecutionInfo.TimingBreakdown.newBuilder()
                    .setName("networkTime")
                    .setTime(toProtoDuration(sm.networkTimeInMs()))
                    .build())
            .build());

    return executionInfo.build();
  }

  private static Duration toProtoDuration(int timeInMs) {
    return Durations.fromMillis(timeInMs);
  }

  /**
   * A spawn to generate a test.xml file from the test log. This is only used if the test does not
   * generate a test.xml file itself.
   */
  private static Spawn createXmlGeneratingSpawn(
      TestRunnerAction action, ImmutableMap<String, String> testEnv, SpawnResult result) {
    ImmutableList<String> args =
        ImmutableList.of(
            action
                .getTestXmlGeneratorScript()
                .getExecPath()
                .getCallablePathStringForOs(action.getExecutionSettings().getExecutionOs()),
            action.getTestLog().getExecPathString(),
            action.getXmlOutputPath().getPathString(),
            Integer.toString(result.getWallTimeInMs() / 1000),
            Integer.toString(result.exitCode()));
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    // "PATH" and "TEST_BINARY" are also required, they should always be set in testEnv.
    Preconditions.checkArgument(testEnv.containsKey("PATH"));
    Preconditions.checkArgument(testEnv.containsKey("TEST_BINARY"));
    envBuilder.putAll(testEnv).put("TEST_NAME", action.getTestName());
    // testEnv only contains TEST_SHARD_INDEX and TEST_TOTAL_SHARDS if the test action is sharded,
    // we need to set the default value when the action isn't sharded.
    if (!action.isSharded()) {
      envBuilder.put("TEST_SHARD_INDEX", "0");
      envBuilder.put("TEST_TOTAL_SHARDS", "0");
    }
    return new SimpleSpawn(
        action,
        args,
        envBuilder.buildOrThrow(),
        // Pass the execution info of the action which is identical to the supported tags set on the
        // test target. In particular, this does not set the test timeout on the spawn.
        action.getExecutionInfo(),
        ImmutableMap.of(),
        /* inputs= */ NestedSetBuilder.create(
            Order.STABLE_ORDER, action.getTestXmlGeneratorScript(), action.getTestLog()),
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.of(ActionInputHelper.fromPath(action.getXmlOutputPath())),
        /* mandatoryOutputs= */ null,
        SpawnAction.DEFAULT_RESOURCE_SET);
  }

  private static Spawn createCoveragePostProcessingSpawn(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      List<ActionInput> expandedCoverageDir,
      Path tmpDirRoot,
      boolean splitXmlGeneration) {
    ImmutableList<String> args =
        ImmutableList.of(action.getCollectCoverageScript().getExecPathString());

    Map<String, String> testEnvironment =
        createEnvironment(actionExecutionContext, action, tmpDirRoot, splitXmlGeneration);

    testEnvironment.put("TEST_SHARD_INDEX", Integer.toString(action.getShardNum()));
    testEnvironment.put(
        "TEST_TOTAL_SHARDS", Integer.toString(action.getExecutionSettings().getTotalShards()));
    testEnvironment.put("TEST_NAME", action.getTestName());
    testEnvironment.put("IS_COVERAGE_SPAWN", "1");

    return new SimpleSpawn(
        action,
        args,
        ImmutableMap.copyOf(testEnvironment),
        action.getExecutionInfo(),
        /* filesetMappings= */ ImmutableMap.of(),
        /* inputs= */ NestedSetBuilder.<ActionInput>compileOrder()
            .addTransitive(action.getInputs())
            .addAll(expandedCoverageDir)
            .add(action.getCollectCoverageScript())
            .add(action.getCoverageManifest())
            .addTransitive(action.getLcovMergerFilesToRun().build())
            .build(),
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.of(
            ActionInputHelper.fromPath(action.getCoverageData().getExecPath())),
        /* mandatoryOutputs= */ null,
        SpawnAction.DEFAULT_RESOURCE_SET);
  }

  private static Map<String, String> createEnvironment(
      ActionExecutionContext actionExecutionContext,
      TestRunnerAction action,
      Path tmpDirRoot,
      boolean splitXmlGeneration) {
    Path execRoot = actionExecutionContext.getExecRoot();
    ArtifactPathResolver pathResolver = actionExecutionContext.getPathResolver();
    Path runfilesDir = pathResolver.convertPath(action.getExecutionSettings().getRunfilesDir());
    Path tmpDir = pathResolver.convertPath(tmpDirRoot.getChild(TestStrategy.getTmpDirName(action)));
    Map<String, String> testEnvironment =
        setupEnvironment(
            action, actionExecutionContext.getClientEnv(), execRoot, runfilesDir, tmpDir);
    if (splitXmlGeneration) {
      testEnvironment.put("EXPERIMENTAL_SPLIT_XML_GENERATION", "1");
    }
    return testEnvironment;
  }

  @Override
  public TestResult newCachedTestResult(
      Path execRoot,
      TestRunnerAction action,
      TestResultData cachedResult,
      ImmutableMultimap<String, Path> testOutputs) {
    return new TestResult(
        action, cachedResult, testOutputs, /* cached= */ true, execRoot, /* systemFailure= */ null);
  }

  @VisibleForTesting
  record StandaloneProcessedAttemptResult(
      TestResultData testResultData, ImmutableMultimap<String, Path> testOutputs)
      implements ProcessedAttemptResult {}

  private final class StandaloneTestRunnerSpawn implements TestRunnerSpawn {
    private final TestRunnerAction testAction;
    private final ActionExecutionContext actionExecutionContext;
    private final Spawn spawn;
    private final Path tmpDir;
    private final Path execRoot;

    StandaloneTestRunnerSpawn(
        TestRunnerAction testAction,
        ActionExecutionContext actionExecutionContext,
        Spawn spawn,
        Path tmpDir,
        Path execRoot) {
      this.testAction = testAction;
      this.actionExecutionContext = actionExecutionContext;
      this.spawn = spawn;
      this.tmpDir = tmpDir;
      this.execRoot = execRoot;
    }

    @Override
    public ActionExecutionContext getActionExecutionContext() {
      return actionExecutionContext;
    }

    @Override
    public TestAttemptResult execute() throws InterruptedException, IOException, ExecException {
      prepareFileSystem(testAction, execRoot, tmpDir);
      return beginTestAttempt(testAction, spawn, actionExecutionContext, execRoot);
    }

    @Override
    public int getMaxAttempts(TestAttemptResult firstTestAttemptResult) {
      return getTestAttempts(testAction);
    }

    @Override
    public StandaloneProcessedAttemptResult finalizeFailedTestAttempt(
        TestAttemptResult testAttemptResult, int attempt) throws IOException {
      return processFailedTestAttempt(
          attempt, actionExecutionContext, testAction, (StandaloneTestResult) testAttemptResult);
    }

    @Override
    public void finalizeTest(
        TestAttemptResult finalResult, List<ProcessedAttemptResult> failedAttempts)
        throws IOException {
      StandaloneTestStrategy.this.finalizeTest(
          testAction, actionExecutionContext, (StandaloneTestResult) finalResult, failedAttempts);
    }

    @Override
    public void finalizeCancelledTest(List<ProcessedAttemptResult> failedAttempts)
        throws IOException {
      TestResultData.Builder builder =
          TestResultData.newBuilder()
              .setCachable(false)
              .setTestPassed(false)
              .setStatus(BlazeTestStatus.INCOMPLETE);
      StandaloneTestResult standaloneTestResult =
          StandaloneTestResult.builder()
              .setSpawnResults(ImmutableList.of())
              .setTestResultDataBuilder(builder)
              .setExecutionInfo(ExecutionInfo.getDefaultInstance())
              .build();
      finalizeTest(standaloneTestResult, failedAttempts);
    }
  }

  private static TestExecException createTestExecException(
      TestAction.Code errorCode, String errorMessage) {
    return new TestExecException(
        errorMessage,
        FailureDetail.newBuilder()
            .setTestAction(TestAction.newBuilder().setCode(errorCode))
            .setMessage(errorMessage)
            .build());
  }

  private TestAttemptResult runTestAttempt(
      TestRunnerAction testAction,
      ActionExecutionContext actionExecutionContext,
      Spawn spawn,
      SpawnStrategyResolver resolver,
      ResolvedPaths resolvedPaths,
      FileOutErr fileOutErr,
      Closeable streamed,
      long startTimeMillis)
      throws InterruptedException, ExecException, IOException {

    ImmutableList<SpawnResult> spawnResults;

    // We have two protos to represent test attempts:
    // 1. com.google.devtools.build.lib.view.test.TestStatus.TestResultData represents both
    //    failed attempts and finished tests. Bazel stores this to disk to persist cached test
    //    result information across server restarts.
    // 2. com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult
    //    represents only individual attempts (failed or not). Bazel reports this as an event to
    //    the Build Event Protocol, but never saves it to disk.
    //
    // The TestResult proto is always constructed from a TestResultData instance, either one
    // that is created right here, or one that is read back from disk.
    TestResultData.Builder testResultDataBuilder;
    try {
      spawnResults = resolver.exec(spawn, actionExecutionContext.withFileOutErr(fileOutErr));
      testResultDataBuilder = TestResultData.newBuilder();
      testResultDataBuilder.setCachable(true).setTestPassed(true).setStatus(BlazeTestStatus.PASSED);
    } catch (SpawnExecException e) {
      if (e.isCatastrophic()) {
        closeSuppressed(e, streamed);
        closeSuppressed(e, fileOutErr);
        throw e;
      }
      if (!e.getSpawnResult().setupSuccess()) {
        closeSuppressed(e, streamed);
        closeSuppressed(e, fileOutErr);
        // Rethrow as the test could not be run and thus there's no point in retrying.
        throw e;
      }
      spawnResults = ImmutableList.of(e.getSpawnResult());
      testResultDataBuilder = TestResultData.newBuilder();
      testResultDataBuilder
          .setCachable(e.getSpawnResult().status().isConsideredUserError())
          .setTestPassed(false)
          .setStatus(e.hasTimedOut() ? BlazeTestStatus.TIMEOUT : BlazeTestStatus.FAILED);
    } catch (InterruptedException e) {
      closeSuppressed(e, streamed);
      closeSuppressed(e, fileOutErr);
      throw e;
    }
    long endTimeMillis = actionExecutionContext.getClock().currentTimeMillis();

    // Check TEST_PREMATURE_EXIT_FILE file (and always delete it)
    if (actionExecutionContext
            .getPathResolver()
            .convertPath(resolvedPaths.getExitSafeFile())
            .delete()
        && testResultDataBuilder.getTestPassed()) {
      testResultDataBuilder
          .setCachable(false)
          .setTestPassed(false)
          .setStatus(BlazeTestStatus.FAILED);
      fileOutErr
          .getErrorStream()
          .write(
              "-- Test exited prematurely (TEST_PREMATURE_EXIT_FILE exists) --\n".getBytes(UTF_8));
    }

    // Do not override a more informative test failure with a generic failure due to the missing
    // shard file, which may have been caused by the test failing before the runner had a chance to
    // touch the file
    if (testResultDataBuilder.getTestPassed() && testAction.isSharded()) {
      if (testAction.checkShardingSupport()
          && !actionExecutionContext
              .getPathResolver()
              .convertPath(resolvedPaths.getTestShard())
              .exists()) {
        TestExecException e =
            createTestExecException(
                TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
                "Sharding requested, but the test runner did not advertise support for it by "
                    + "touching TEST_SHARD_STATUS_FILE. Either remove the 'shard_count' attribute, "
                    + "use a test runner that supports sharding or temporarily disable this check "
                    + "via --noincompatible_check_sharding_support.");
        closeSuppressed(e, streamed);
        closeSuppressed(e, fileOutErr);
        throw e;
      }
    }

    // SpawnActionContext guarantees the first entry to correspond to the spawn passed in (there
    // may be additional entries due to tree artifact handling).
    SpawnResult primaryResult = spawnResults.get(0);

    // The SpawnResult of a remotely cached or remotely executed action may not have walltime
    // set. We fall back to the time measured here for backwards compatibility.
    long durationMillis = endTimeMillis - startTimeMillis;
    durationMillis =
        (primaryResult.getWallTimeInMs() != 0 ? primaryResult.getWallTimeInMs() : durationMillis);

    testResultDataBuilder
        .setStartTimeMillisEpoch(startTimeMillis)
        .addTestTimes(durationMillis)
        .addTestProcessTimes(durationMillis)
        .setRunDurationMillis(durationMillis)
        .setHasCoverage(testAction.isCoverageMode());

    if (testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing()) {
      if (testAction.getCoverageDirectoryTreeArtifact() == null) {
        // Otherwise we'll get a NPE https://github.com/bazelbuild/bazel/issues/13185
        TestExecException e =
            createTestExecException(
                TestAction.Code.LOCAL_TEST_PREREQ_UNMET,
                "coverageDirectoryTreeArtifact is null:"
                    + " --experimental_split_coverage_postprocessing depends on"
                    + " --experimental_fetch_all_coverage_outputs being enabled");
        closeSuppressed(e, streamed);
        closeSuppressed(e, fileOutErr);
        throw e;
      }
      var unused =
          actionExecutionContext
              .getOutputMetadataStore()
              .getOutputMetadata(testAction.getCoverageDirectoryTreeArtifact());

      ImmutableSet<? extends ActionInput> expandedCoverageDir =
          actionExecutionContext
              .getOutputMetadataStore()
              .getTreeArtifactValue((SpecialArtifact) testAction.getCoverageDirectoryTreeArtifact())
              .getChildren();
      ImmutableSet<ActionInput> coverageSpawnMetadata =
          ImmutableSet.<ActionInput>builder()
              .addAll(expandedCoverageDir)
              .add(testAction.getCoverageDirectoryTreeArtifact())
              .build();

      Spawn coveragePostProcessingSpawn =
          createCoveragePostProcessingSpawn(
              actionExecutionContext,
              testAction,
              ImmutableList.copyOf(expandedCoverageDir),
              tmpDirRoot,
              executionOptions.splitXmlGeneration);
      SpawnStrategyResolver spawnStrategyResolver =
          actionExecutionContext.getContext(SpawnStrategyResolver.class);

      Path testRoot =
          actionExecutionContext.getInputPath(testAction.getTestLog()).getParentDirectory();

      Path out = testRoot.getChild("coverage.log");
      Path err = testRoot.getChild("coverage.err");
      FileOutErr coverageOutErr = new FileOutErr(out, err);
      ActionExecutionContext coverageActionExecutionContext =
          actionExecutionContext
              .withFileOutErr(coverageOutErr)
              .withOutputsAsInputs(coverageSpawnMetadata);

      writeOutFile(coverageOutErr.getErrorPath(), coverageOutErr.getOutputPath());
      appendCoverageLog(coverageOutErr, fileOutErr);
      try {
        spawnStrategyResolver.exec(coveragePostProcessingSpawn, coverageActionExecutionContext);
      } catch (SpawnExecException e) {
        if (e.isCatastrophic()) {
          closeSuppressed(e, streamed);
          closeSuppressed(e, fileOutErr);
          throw e;
        }
        if (!e.getSpawnResult().setupSuccess()) {
          closeSuppressed(e, streamed);
          closeSuppressed(e, fileOutErr);
          // Rethrow as the test could not be run and thus there's no point in retrying.
          throw e;
        }
        testResultDataBuilder
            .setCachable(e.getSpawnResult().status().isConsideredUserError())
            .setTestPassed(false)
            .setStatus(e.hasTimedOut() ? BlazeTestStatus.TIMEOUT : BlazeTestStatus.FAILED);
      } catch (ExecException | InterruptedException e) {
        closeSuppressed(e, streamed);
        closeSuppressed(e, fileOutErr);
        throw e;
      }
    }

    Verify.verify(
        !(testAction.isCoverageMode() && testAction.getSplitCoveragePostProcessing())
            || actionExecutionContext
                .getPathResolver()
                .convertPath(testAction.getCoverageData().getPath())
                .exists());
    Verify.verifyNotNull(spawnResults);
    Verify.verifyNotNull(testResultDataBuilder);

    try {
      if (!fileOutErr.hasRecordedOutput()) {
        // Make sure that the test.log exists.Spaw
        FileSystemUtils.touchFile(fileOutErr.getOutputPath());
      }
      fileOutErr.close();
      // Append any error output to the test.log. This is very rare.
      //
      // Only write after the error output stream has been closed. Otherwise, Bazel cannot delete
      // test.err file on Windows. See https://github.com/bazelbuild/bazel/issues/20741.
      writeOutFile(fileOutErr.getErrorPath(), fileOutErr.getOutputPath());
      if (streamed != null) {
        streamed.close();
      }
    } catch (IOException e) {
      throw new EnvironmentalExecException(e, Code.TEST_OUT_ERR_IO_EXCEPTION);
    }

    Path xmlOutputPath = resolvedPaths.getXmlOutputPath();

    // If the test did not create a test.xml, and --experimental_split_xml_generation is enabled,
    // then we run a separate action to create a test.xml from test.log. We do this as a spawn
    // rather than doing it locally in-process, as the test.log file may only exist remotely (when
    // remote execution is enabled), and we do not want to have to download it.
    if (executionOptions.splitXmlGeneration
        && fileOutErr.getOutputPath().exists()
        && !xmlOutputPath.exists()) {
      Spawn xmlGeneratingSpawn =
          createXmlGeneratingSpawn(testAction, spawn.getEnvironment(), spawnResults.get(0));
      SpawnStrategyResolver spawnStrategyResolver =
          actionExecutionContext.getContext(SpawnStrategyResolver.class);
      // We treat all failures to generate the test.xml here as catastrophic, and won't rerun
      // the test if this fails. We redirect the output to a temporary file.
      FileOutErr xmlSpawnOutErr = actionExecutionContext.getFileOutErr().childOutErr();

      ActionExecutionContext xmlActionExecutionContext =
          actionExecutionContext
              .withFileOutErr(xmlSpawnOutErr)
              .withOutputsAsInputs(ImmutableList.of(testAction.getTestLog()));
      try {

        ImmutableList<SpawnResult> xmlSpawnResults =
            spawnStrategyResolver.exec(xmlGeneratingSpawn, xmlActionExecutionContext);
        spawnResults =
            ImmutableList.<SpawnResult>builder()
                .addAll(spawnResults)
                .addAll(xmlSpawnResults)
                .build();
      } catch (InterruptedException | ExecException e) {
        closeSuppressed(e, xmlSpawnOutErr);
        throw e;
      }
    }

    TestCase details = parseTestResult(xmlOutputPath);
    if (details != null) {
      testResultDataBuilder.setTestCase(details);
    }

    BuildEventStreamProtos.TestResult.ExecutionInfo executionInfo =
        extractExecutionInfo(spawnResults.get(0), testResultDataBuilder);
    return StandaloneTestResult.builder()
        .setSpawnResults(spawnResults)
        // We return the TestResultData.Builder rather than the finished TestResultData
        // instance, as we may have to rename the output files in case the test needs to be
        // rerun (if it failed here _and_ is marked flaky _and_ the number of flaky attempts
        // is larger than 1).
        .setTestResultDataBuilder(testResultDataBuilder)
        .setExecutionInfo(executionInfo)
        .build();
  }
}
