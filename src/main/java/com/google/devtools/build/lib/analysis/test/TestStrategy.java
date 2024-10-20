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

package com.google.devtools.build.lib.analysis.test;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction.ResolvedPaths;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.StreamedTestOutput;
import com.google.devtools.build.lib.exec.TestLogHelper;
import com.google.devtools.build.lib.exec.TestXmlOutputParser;
import com.google.devtools.build.lib.exec.TestXmlOutputParserException;
import com.google.devtools.build.lib.runtime.TestSummaryOptions;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TestAction;
import com.google.devtools.build.lib.server.FailureDetails.TestAction.Code;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** A strategy for executing a {@link TestRunnerAction}. */
public abstract class TestStrategy implements TestActionContext {
  private static class AttemptGroupImpl implements AttemptGroup {
    private boolean cancelled;
    private final Set<Thread> runningThreads;

    private AttemptGroupImpl() {
      cancelled = false;
      runningThreads = new HashSet<>();
    }

    @Override
    public synchronized void register() throws InterruptedException {
      Verify.verify(runningThreads.add(Thread.currentThread()));

      if (cancelled) {
        throw new InterruptedException();
      }
    }

    @Override
    public synchronized void unregister() {
      Verify.verify(runningThreads.remove(Thread.currentThread()));
    }

    @Override
    public synchronized boolean cancelled() {
      return cancelled;
    }

    @Override
    public synchronized void cancelOthers() {
      if (cancelled) {
        return;
      }

      cancelled = true;

      for (Thread thread : runningThreads) {
        if (thread != Thread.currentThread()) {
          thread.interrupt();
        }
      }
    }
  }

  private final ConcurrentHashMap<ShardKey, AttemptGroupImpl> cancelGroups =
      new ConcurrentHashMap<>();

  /**
   * Ensures that all directories used to run test are in the correct state and their content will
   * not result in stale files.
   */
  protected void prepareFileSystem(TestRunnerAction testAction, Path execRoot, Path tmpDir)
      throws IOException {
    if (tmpDir != null) {
      recreateDirectory(tmpDir);
    }

    ResolvedPaths resolvedPaths = testAction.resolve(execRoot);
    if (testAction.isCoverageMode()) {
      recreateDirectory(resolvedPaths.getCoverageDirectory());
    }

    resolvedPaths.getBaseDir().createDirectoryAndParents();
    resolvedPaths.getUndeclaredOutputsDir().createDirectoryAndParents();
    resolvedPaths.getUndeclaredOutputsAnnotationsDir().createDirectoryAndParents();
    resolvedPaths.getSplitLogsDir().createDirectoryAndParents();
  }

  /**
   * Ensures that all directories used to run test are in the correct state and their content will
   * not result in stale files. Only use this if no local tmp and working directory are required.
   */
  protected void prepareFileSystem(TestRunnerAction testAction, Path execRoot) throws IOException {
    prepareFileSystem(testAction, execRoot, null);
  }

  /** Removes directory if it exists and recreates it. */
  private void recreateDirectory(Path directory) throws IOException {
    directory.deleteTree();
    directory.createDirectoryAndParents();
  }

  public static final PathFragment TEST_TMP_ROOT = PathFragment.create("_tmp");

  // Used for generating unique temporary directory names. Contains the next numeric index for every
  // executable base name.
  private final Map<String, Integer> tmpIndex = new HashMap<>();
  protected final ExecutionOptions executionOptions;
  protected final TestSummaryOptions testSummaryOptions;
  protected final BinTools binTools;

  public TestStrategy(
      ExecutionOptions executionOptions, TestSummaryOptions testSummaryOptions, BinTools binTools) {
    this.executionOptions = executionOptions;
    this.testSummaryOptions = testSummaryOptions;
    this.binTools = binTools;
  }

  @Override
  public final boolean isTestKeepGoing() {
    return executionOptions.testKeepGoing;
  }

  @Override
  public final AttemptGroup getAttemptGroup(ActionOwner owner, int shardNum) {
    ShardKey key = new ShardKey(owner, shardNum);
    return cancelGroups.computeIfAbsent(key, k -> new AttemptGroupImpl());
  }

  /**
   * Generates a command line to run for the test action, taking into account coverage and {@code
   * --run_under} settings.
   *
   * <p>Basically {@link #expandedArgsFromAction}, but throws {@link ExecException} instead. This
   * should be used in action execution.
   *
   * @param testAction The test action.
   * @return the command line as string list.
   * @throws ExecException if {@link #expandedArgsFromAction} throws
   */
  public static ImmutableList<String> getArgs(TestRunnerAction testAction)
      throws ExecException, InterruptedException {
    try {
      return expandedArgsFromAction(testAction);
    } catch (CommandLineExpansionException e) {
      throw new UserExecException(
          e,
          FailureDetail.newBuilder()
              .setMessage(Strings.nullToEmpty(e.getMessage()))
              .setTestAction(TestAction.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE))
              .build());
    }
  }

  /**
   * Generates a command line to run for the test action, taking into account coverage and {@code
   * --run_under} settings.
   *
   * @param testAction The test action.
   * @return the command line as string list.
   * @throws CommandLineExpansionException
   */
  public static ImmutableList<String> expandedArgsFromAction(TestRunnerAction testAction)
      throws CommandLineExpansionException, InterruptedException {
    List<String> args = Lists.newArrayList();

    Artifact testSetup = testAction.getTestSetupScript();
    args.add(testSetup.getExecPath().getCallablePathString());

    if (testAction.isCoverageMode()) {
      args.add(testAction.getCollectCoverageScript().getExecPathString());
    }

    TestTargetExecutionSettings execSettings = testAction.getExecutionSettings();

    // Insert the command prefix specified by the "--run_under=<command-prefix>" option, if any.
    if (execSettings.getRunUnder() != null) {
      addRunUnderArgs(testAction, args);
    }

    // Execute the test using the alias in the runfiles tree, as mandated by the Test Encyclopedia.
    args.add(execSettings.getExecutable().getRunfilesPath().getCallablePathString());
    Iterables.addAll(args, execSettings.getArgs().arguments());
    return ImmutableList.copyOf(args);
  }

  private static void addRunUnderArgs(TestRunnerAction testAction, List<String> args) {
    TestTargetExecutionSettings execSettings = testAction.getExecutionSettings();
    if (execSettings.getRunUnderExecutable() != null) {
      args.add(execSettings.getRunUnderExecutable().getRunfilesPath().getCallablePathString());
    } else {
      if (execSettings.needsShell(testAction.isExecutedOnWindows())) {
        // TestActionBuilder constructs TestRunnerAction with a 'null' shell only when none is
        // required. Something clearly went wrong.
        Preconditions.checkNotNull(testAction.getShExecutableMaybe(), "%s", testAction);
        String shellExecutable = testAction.getShExecutableMaybe().getPathString();
        args.add(shellExecutable);
        args.add("-c");
        args.add("\"$@\"");
        args.add(shellExecutable); // Sets $0.
      }
      args.add(execSettings.getRunUnder().getCommand());
    }
    args.addAll(testAction.getExecutionSettings().getRunUnder().getOptions());
  }

  /**
   * Returns the number of attempts specific test action can be retried.
   *
   * <p>For rules with "flaky = 1" attribute, this method will return 3 unless --flaky_test_attempts
   * option is given and specifies another value.
   */
  @VisibleForTesting /* protected */
  public int getTestAttempts(TestRunnerAction action) {
    return action.getTestProperties().isFlaky()
        ? getTestAttemptsForFlakyTest(action)
        : getTestAttempts(action, /* defaultTestAttempts= */ 1);
  }

  private int getTestAttempts(TestRunnerAction action, int defaultTestAttempts) {
    Label testLabel = action.getOwner().getLabel();
    return getTestAttemptsPerLabel(executionOptions, testLabel, defaultTestAttempts);
  }

  public int getTestAttemptsForFlakyTest(TestRunnerAction action) {
    return getTestAttempts(action, /* defaultTestAttempts= */ 3);
  }

  private static int getTestAttemptsPerLabel(
      ExecutionOptions options, Label label, int defaultTestAttempts) {
    // Check from the last provided, so that the last option provided takes precedence.
    for (PerLabelOptions perLabelAttempts : Lists.reverse(options.testAttempts)) {
      if (perLabelAttempts.isIncluded(label)) {
        String attempts = Iterables.getOnlyElement(perLabelAttempts.getOptions());
        if ("default".equals(attempts)) {
          return defaultTestAttempts;
        }
        return Integer.parseInt(attempts);
      }
    }
    return defaultTestAttempts;
  }

  /**
   * Returns timeout value in seconds that should be used for the given test action. We always use
   * the "categorical timeouts" which are based on the --test_timeout flag. A rule picks its timeout
   * but ends up with the same effective value as all other rules in that bucket.
   */
  protected static final Duration getTimeout(TestRunnerAction testAction) {
    BuildConfigurationValue configuration = testAction.getConfiguration();
    return configuration
        .getFragment(TestConfiguration.class)
        .getTestTimeout()
        .get(testAction.getTestProperties().getTimeout());
  }

  /*
   * Finalize test run: persist the result, and post on the event bus.
   */
  protected void postTestResult(ActionExecutionContext actionExecutionContext, TestResult result)
      throws IOException {
    result.getTestAction().saveCacheStatus(actionExecutionContext, result.getData());
    actionExecutionContext.getEventHandler().post(result);
  }

  /**
   * Returns a unique name for a temporary directory a test could use.
   *
   * <p>Since each test within single Blaze run must have a unique TEST_TMPDIR, we will use rule
   * name and a unique (within single Blaze request) number to generate directory name.
   *
   * <p>This does not create the directory.
   */
  protected String getTmpDirName(PathFragment execPath) {
    String basename = execPath.getBaseName();

    synchronized (tmpIndex) {
      int index = tmpIndex.containsKey(basename) ? tmpIndex.get(basename) : 1;
      tmpIndex.put(basename, index + 1);
      return basename + "_" + index;
    }
  }

  public static String getTmpDirName(TestRunnerAction action) {
    Fingerprint digest = new Fingerprint();
    digest.addPath(action.getExecutionSettings().getExecutable().getExecPath());
    digest.addInt(action.getShardNum());
    digest.addInt(action.getRunNumber());
    // Truncate the string to 32 character to avoid exceeding path length limit on Windows and macOS
    return digest.hexDigestAndReset().substring(0, 32);
  }

  /** Parse a test result XML file into a {@link TestCase}. */
  @Nullable
  protected TestCase parseTestResult(Path resultFile) {
    /* xml files. We avoid parsing it unnecessarily, since test results can potentially consume
    a large amount of memory. */
    switch (executionOptions.testSummary) {
      case TESTCASE:
      case DETAILED:
      case DETAILED_FORMATTED:
        try (InputStream fileStream = resultFile.getInputStream()) {
          return new TestXmlOutputParser().parseXmlIntoTestResult(fileStream);
        } catch (IOException | TestXmlOutputParserException e) {
          return null;
        }
      default:
        return null;
    }
  }

  /**
   * Outputs test result to the stdout after test has finished (e.g. for --test_output=all or
   * --test_output=errors). Will also try to group output lines together (up to 10000 lines) so
   * parallel test outputs will not get interleaved.
   */
  protected void processTestOutput(
      ActionExecutionContext actionExecutionContext,
      TestResultData testResultData,
      String testName,
      @Nullable Path testLog)
      throws IOException {
    boolean isPassed = testResultData.getTestPassed();
    try {
      if (testResultData.getStatus() != BlazeTestStatus.INCOMPLETE
          && TestLogHelper.shouldOutputTestLog(executionOptions.testOutput, isPassed)) {
        TestLogHelper.writeTestLog(
            testLog,
            testName,
            actionExecutionContext.getFileOutErr().getOutputStream(),
            executionOptions.maxTestOutputBytes);
      }
    } finally {
      if (isPassed) {
        actionExecutionContext.getEventHandler().handle(Event.of(EventKind.PASS, null, testName));
      } else {
        PathFragment testLogPathToOutput = null;
        if (testLog != null) {
          testLogPathToOutput =
              testSummaryOptions.printRelativeTestLogPaths
                  ? testLog
                      .asFragment()
                      .relativeTo(actionExecutionContext.getExecRoot().asFragment())
                  : testLog.asFragment();
        }
        if (testResultData.hasStatusDetails()) {
          actionExecutionContext
              .getEventHandler()
              .handle(Event.error(testName + ": " + testResultData.getStatusDetails()));
        }
        if (testResultData.getStatus() == BlazeTestStatus.TIMEOUT) {
          String message =
              String.format(
                  "%s%s",
                  testName,
                  testLogPathToOutput != null ? " (see " + testLogPathToOutput + ")" : "");
          actionExecutionContext
              .getEventHandler()
              .handle(Event.of(EventKind.TIMEOUT, null, message));
        } else if (testResultData.getStatus() == BlazeTestStatus.INCOMPLETE) {
          actionExecutionContext
              .getEventHandler()
              .handle(Event.of(EventKind.CANCELLED, null, testName));
        } else {
          TerminationStatus ts =
              TerminationStatus.builder()
                  .setWaitResponse(testResultData.getExitCode())
                  .setTimedOut(testResultData.getStatus() == BlazeTestStatus.TIMEOUT)
                  .build();
          String message =
              String.format(
                  "%s (%s)%s",
                  testName,
                  ts.toShortString(),
                  testLogPathToOutput != null ? " (see " + testLogPathToOutput + ")" : "");
          actionExecutionContext.getEventHandler().handle(Event.of(EventKind.FAIL, null, message));
        }
      }
    }
  }

  /**
   * Returns a temporary directory for all tests in a workspace to use. Individual tests should
   * create child directories to actually use.
   *
   * <p>This either dynamically generates a directory name or uses the directory specified by
   * --test_tmpdir. This does not create the directory.
   */
  public static Path getTmpRoot(Path workspace, Path execRoot, ExecutionOptions executionOptions) {
    return executionOptions.testTmpDir != null
        ? workspace.getRelative(executionOptions.testTmpDir).getRelative(TEST_TMP_ROOT)
        : execRoot.getRelative(TEST_TMP_ROOT);
  }

  /**
   * For an given environment, returns a subset containing all variables in the given list if they
   * are defined in the given environment.
   */
  @VisibleForTesting
  public static Map<String, String> getMapping(
      Iterable<String> variables, Map<String, String> environment) {
    Map<String, String> result = new HashMap<>();
    for (String var : variables) {
      if (environment.containsKey(var)) {
        result.put(var, environment.get(var));
      }
    }
    return result;
  }

  protected static void closeSuppressed(Throwable e, @Nullable Closeable c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (IOException e2) {
      e.addSuppressed(e2);
    }
  }

  protected Closeable createStreamedTestOutput(OutErr outErr, Path testLogPath) throws IOException {
    return new StreamedTestOutput(outErr, testLogPath);
  }

  private static final class ShardKey {
    private final ActionOwner owner;
    private final int shard;

    ShardKey(ActionOwner owner, int shard) {
      this.owner = Preconditions.checkNotNull(owner);
      this.shard = shard;
    }

    @Override
    public int hashCode() {
      return Objects.hash(owner, shard);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ShardKey s)) {
        return false;
      }
      return owner.equals(s.owner) && shard == s.shard;
    }
  }
}
