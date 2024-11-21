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
package com.google.devtools.build.lib.bugreport;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.CustomExitCodePublisher;
import com.google.devtools.build.lib.util.CustomFailureDetailPublisher;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.TestType;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Utility methods for handling crashes: we log the crash, optionally send a bug report, and then
 * terminate the jvm.
 *
 * <p>Note, code in this class must be extremely robust. There's nothing worse than a crash-handler
 * that itself crashes!
 */
public final class BugReport {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  static final BugReporter REPORTER_INSTANCE = new DefaultBugReporter();

  // TODO(b/232094803): Replace the static state with instance variables and allow custom overrides
  //  for testing.
  private static final BlazeVersionInfo VERSION_INFO = BlazeVersionInfo.instance();

  private static BlazeRuntimeInterface runtime = null;

  @SuppressWarnings("StaticAssignmentOfThrowable")
  @GuardedBy("lock")
  @Nullable
  private static Throwable lastCrashingThrowable = null;

  /**
   * Global lock held while reporting a crash.
   *
   * <p>Holding a global lock isn't ideal, but it ensures that concurrent crashes produce coherent
   * bug reports to the user, logs, and bug-reporting backend.
   */
  private static final ReentrantLock lock = new ReentrantLock(/* fair= */ true);

  private static final boolean SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST =
      TestType.isInTest() && System.getenv("ENABLE_BUG_REPORT_LOGGING_IN_TEST") == null;

  private BugReport() {}

  /**
   * This is a narrow interface for {@link BugReport}'s usage of BlazeRuntime. It lives in this
   * file, for the sake of avoiding a build-time cycle.
   */
  public interface BlazeRuntimeInterface {
    String getProductName();

    void fillInCrashContext(CrashContext ctx);

    /**
     * Perform all possible clean-up before crashing, posting events etc. so long as crashing isn't
     * significantly delayed or another crash isn't triggered.
     */
    void cleanUpForCrash(DetailedExitCode exitCode);
  }

  public static void setRuntime(BlazeRuntimeInterface newRuntime) {
    Preconditions.checkNotNull(newRuntime);
    Preconditions.checkState(
        runtime == null || TestType.isInTest(), "runtime already set: %s, %s", runtime, newRuntime);
    runtime = newRuntime;
  }

  private static String getProductName() {
    return runtime != null ? runtime.getProductName() : "<unknown>";
  }

  /**
   * Returns the last crashing throwable passed to {@link #handleCrash} and clears the stored value.
   */
  @SuppressWarnings("StaticAssignmentOfThrowable")
  @Nullable
  public static Throwable getAndResetLastCrashingThrowableIfInTest() {
    if (TestType.isInTest()) {
      // Instead of the jvm having been halted, we might have a saved Throwable.
      lock.lock();
      try {
        Throwable result = lastCrashingThrowable;
        lastCrashingThrowable = null;
        return result;
      } finally {
        lock.unlock();
      }
    }
    return null;
  }

  /**
   * In tests, throws if a there was a {@link #handleCrash} call since the last time this method or
   * {@link #getAndResetLastCrashingThrowableIfInTest()} was called.
   *
   * <p>This method exists because Runtime#halt is disabled. Thus, the main thread should call this
   * method whenever it is about to block on thread completion that might hang because of a failed
   * or ignored crash.
   */
  public static void maybePropagateLastCrashIfInTest() {
    if (TestType.isInTest()) {
      // Instead of the jvm having been halted, we might have a saved Throwable.
      lock.lock();
      try {
        Throwable lastUnprocessedThrowableInTest = getAndResetLastCrashingThrowableIfInTest();
        if (lastUnprocessedThrowableInTest != null) {
          throw new IllegalStateException(
              "Unprocessed throwable detected in test", lastUnprocessedThrowableInTest);
        }
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * Used when an unexpected state is encountered that is not a problem in itself: the program can
   * continue running with no issues for the user, but some assumption of the programmer was wrong.
   * Use this instead of {@link #sendBugReport} if the issue will not be a high priority to debug
   * (such as an improperly transformed exception in Skyframe).
   *
   * <p>Since this is an unexpected state, it will fail if called during a test: either this state
   * can be reached and the call to this method should be deleted, or this points to a separate bug
   * that should be fixed so that this state isn't reached.
   */
  @FormatMethod
  public static void logUnexpected(@FormatString String message, Object... args) {
    if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
      sendBugReport(message, args);
    } else {
      logger
          .atWarning()
          .atMostEvery(50, MILLISECONDS)
          .logVarargs("Unexpected state: " + message, args);
    }
  }

  /** See {@link #logUnexpected(String, Object...)}. */
  @FormatMethod
  public static void logUnexpected(Exception e, @FormatString String message, Object... args) {
    if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
      sendBugReport(new IllegalStateException(String.format(message, args), e));
    } else {
      logger
          .atWarning()
          .atMostEvery(50, MILLISECONDS)
          .withCause(e)
          .logVarargs("Unexpected state: " + message, args);
    }
  }

  /**
   * Convenience method for {@link #sendBugReport(Throwable)}, sending a bug report with a default
   * {@link IllegalStateException}.
   */
  @FormatMethod
  public static void sendBugReport(@FormatString String message, Object... args) {
    sendBugReport(new IllegalStateException(String.format(message, args)));
  }

  /**
   * Convenience method for {@linkplain #sendBugReport(Throwable, List, String...) sending a bug
   * report} without additional arguments.
   */
  public static void sendBugReport(Throwable exception) {
    sendBugReport(exception, /*args=*/ ImmutableList.of());
  }

  /**
   * Logs the unhandled exception with a special prefix signifying that this was a crash.
   *
   * @param exception the unhandled exception to display.
   * @param args additional values to record in the message.
   * @param values Additional string values to clarify the exception.
   */
  public static void sendBugReport(Throwable exception, List<String> args, String... values) {
    sendBugReportInternal(exception, /*isFatal=*/ true, filterArgs(args), values);
  }

  /** Logs the bug report, indicating it is not a crash. */
  public static void sendNonFatalBugReport(Throwable exception) {
    sendBugReportInternal(exception, /*isFatal=*/ false, /*args=*/ ImmutableList.of());
  }

  private static void sendBugReportInternal(
      Throwable exception, boolean isFatal, List<String> args, String... values) {
    if (SHOULD_NOT_SEND_BUG_REPORT_BECAUSE_IN_TEST) {
      Throwables.throwIfUnchecked(exception);
      throw new IllegalStateException(
          "Bug reports in tests should crash: " + args + ", " + Arrays.toString(values), exception);
    }
    if (!VERSION_INFO.isReleasedBlaze()) {
      logger.atInfo().log("(Not a released binary; not logged.)");
      return;
    }

    logException(exception, isFatal, filterArgs(args), values);
  }

  /**
   * Convenience method equivalent to calling {@code BugReport.handleCrash(Crash.from(throwable),
   * CrashContext.halt().withArgs(args)}.
   *
   * <p>Halts the JVM and does not return.
   */
  public static RuntimeException handleCrash(Throwable throwable, String... args) {
    handleCrash(Crash.from(throwable), CrashContext.halt().withArgs(args));
    throw new IllegalStateException("Should have halted", throwable);
  }

  /**
   * Handles a {@link Crash} according to the given {@link CrashContext}.
   *
   * <p>In the case of {@link CrashContext#halt}, the JVM is {@linkplain Runtime#halt halted}.
   * Otherwise, for {@link CrashContext#keepAlive}, returns {@code null}, in which case the caller
   * is responsible for shutting down the server.
   */
  @SuppressWarnings("StaticAssignmentOfThrowable")
  public static void handleCrash(Crash crash, CrashContext ctx) {
    int numericExitCode = crash.getDetailedExitCode().getExitCode().getNumericExitCode();
    Throwable throwable = crash.getThrowable();
    if (runtime != null) {
      runtime.fillInCrashContext(ctx);
    }
    // Multiple concurrent crashes may deadlock if certain background threads crash while another
    // crash is already being reported. In these cases, log loudly and return eagerly.
    if (ctx.returnIfCrashInProgress()) {
      if (!lock.tryLock()) {
        logger.atSevere().withCause(throwable).log(
            "Crash already in progress, not reporting to avoid deadlock: %s", ctx);
        return;
      }
    } else {
      lock.lock();
    }
    try {
      try {
        logger.atSevere().withCause(throwable).log("Handling crash with %s", ctx);

        if (TestType.isInTest()) {
          lastCrashingThrowable = throwable;
        }

        String crashMsg;
        String heapDumpPath;
        // Might be a wrapped OOM - the detailed exit code reflects the root cause.
        boolean isOom = crash.getDetailedExitCode().getExitCode().equals(ExitCode.OOM_ERROR);
        if (isOom) {
          crashMsg = constructOomExitMessage(ctx.getExtraOomInfo());
          heapDumpPath = ctx.getHeapDumpPath();
          if (heapDumpPath != null) {
            crashMsg += " An attempt will be made to write a heap dump to " + heapDumpPath + ".";
          }
        } else {
          crashMsg = getProductName() + " crashed due to an internal error.";
          heapDumpPath = null;
        }
        crashMsg += " Printing stack trace:\n" + Throwables.getStackTraceAsString(throwable);
        ctx.getEventHandler().handle(Event.fatal(crashMsg));

        try {
          // Emit exit data before sending a bug report. Bug reports involve an RPC, and given that
          // we are crashing, who knows if it will complete. It's more important that we write
          // exit code and failure detail information so that the crash can be handled correctly.
          emitExitData(crash, ctx, numericExitCode, heapDumpPath);
          // Skip sending a bug report if the crash is an OOM - attempting an RPC while out of
          // memory can cause issues. Also, don't try to send a bug report during a crash in a test,
          // it will throw itself.
          if (ctx.shouldSendBugReport() && !isOom && !TestType.isInTest()) {
            sendBugReport(throwable, ctx.getArgs());
          }
        } finally {
          if (ctx.shouldHaltJvm()) {
            // Avoid shutdown deadlock issues: If an application shutdown hook crashes, it will
            // trigger our Blaze crash handler (this method). Calling System#exit() here, would
            // therefore induce a deadlock. This call would block on the shutdown sequence
            // completing, but the shutdown sequence would in turn be blocked on this thread
            // finishing. Instead, exit fast via halt().
            halt(numericExitCode);
          }
        }
      } finally {
        lock.unlock();
      }
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Threw while crashing");
      System.err.println(
          "ERROR: A crash occurred while "
              + getProductName()
              + " was trying to handle a crash! Please file a bug against "
              + getProductName()
              + " and include the information below.");

      System.err.println("Original uncaught exception:");
      throwable.printStackTrace(System.err);

      System.err.println("Exception encountered during BugReport#handleCrash:");
      t.printStackTrace(System.err);
    } finally {
      if (ctx.shouldHaltJvm()) {
        halt(numericExitCode);
      }
    }
    if (!ctx.shouldHaltJvm()) {
      return;
    }
    logger.atSevere().log("Failed to crash in handleCrash");
    throw new IllegalStateException("Should have halted", throwable);
  }

  private static void halt(int numericExitCode) {
    if (TestType.getTestType() == TestType.UNKNOWN_TEST) {
      // Only intercept halt in unit tests. In shell integration tests, we do want to halt.
      throw new SecurityException(
          "Intercepted call to Runtime.halt with status " + numericExitCode);
    } else {
      Runtime.getRuntime().halt(numericExitCode);
    }
  }

  /**
   * Writes exit status files, dumps heap if requested, and calls {@link
   * BlazeRuntimeInterface#cleanUpForCrash}.
   */
  private static void emitExitData(
      Crash crash, CrashContext ctx, int numericExitCode, @Nullable String heapDumpPath) {
    // Writing the exit code status file is only necessary if we are halting. Otherwise, the
    // caller is responsible for an orderly shutdown with the proper exit code.
    if (ctx.shouldHaltJvm()) {
      if (CustomExitCodePublisher.maybeWriteExitStatusFile(numericExitCode)) {
        logger.atInfo().log("Wrote exit status file.");
      } else {
        logger.atWarning().log("Did not write exit status file; check stderr for errors.");
      }
    }

    if (CustomFailureDetailPublisher.maybeWriteFailureDetailFile(
        crash.getDetailedExitCode().getFailureDetail())) {
      logger.atInfo().log("Wrote failure detail file.");
    } else {
      logger.atWarning().log("Did not write failure detail file; check stderr for errors.");
    }

    if (heapDumpPath != null) {
      logger.atInfo().log("Attempting to dump heap to %s", heapDumpPath);
      try {
        dumpHeap(heapDumpPath);
        logger.atInfo().log("Heap dump complete");
      } catch (Throwable t) { // Catch anything so we don't forgo the OOM.
        logger.atWarning().withCause(t).log("Heap dump failed");
      }
    }

    if (runtime != null) {
      runtime.cleanUpForCrash(crash.getDetailedExitCode());
      logger.atInfo().log("Cleaned up runtime.");
    } else {
      logger.atInfo().log("No runtime to clean.");
    }
  }

  public static String constructOomExitMessage(@Nullable String extraInfo) {
    String msg = getProductName() + " ran out of memory and crashed.";
    return isNullOrEmpty(extraInfo) ? msg : msg + " " + extraInfo;
  }

  private static void dumpHeap(String path) throws IOException {
    HotSpotDiagnosticMXBean mxBean =
        ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(),
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean.class);
    mxBean.dumpHeap(path, /*live=*/ true);
  }

  /**
   * Filters {@code args} by removing superfluous items:
   *
   * <ul>
   *   <li>The client's environment variables may contain sensitive data, so we filter it out.
   *   <li>{@code --default_override} is spammy.
   * </ul>
   */
  @Nullable
  private static ImmutableList<String> filterArgs(Iterable<String> args) {
    if (args == null) {
      return null;
    }

    ImmutableList.Builder<String> filteredArgs = ImmutableList.builder();
    for (String arg : args) {
      if (arg != null
          && !arg.startsWith("--client_env=")
          && !arg.startsWith("--default_override=")) {
        filteredArgs.add(arg);
      }
    }
    return filteredArgs.build();
  }

  /**
   * Logs the exception. Because this method is only called in a blaze release, this will result in
   * a report being sent to a remote logging service.
   *
   * <p>TODO(b/232094803): Make this method private and replace the tests with ones calling public
   * methods like {@link #sendBugReport(Throwable)} directly.
   */
  @VisibleForTesting
  static void logException(
      Throwable exception, boolean isCrash, List<String> args, String... values) {
    logger.atSevere().withCause(exception).log("Exception");
    String preamble =
        CrashFailureDetails.oomDetected() ? "While OOMing, " + getProductName() : getProductName();
    Level level = isCrash ? Level.SEVERE : Level.WARNING;
    if (!isCrash) {
      preamble += " had a non fatal error with args: ";
    } else if (exception instanceof OutOfMemoryError) {
      preamble += " OOMError: ";
    } else {
      preamble += " crashed with args: ";
    }

    logger.atInfo().log("Calling logToRemote");
    LoggingUtil.logToRemote(level, preamble + Joiner.on(' ').join(args), exception, values);
    logger.atInfo().log("Call to logToRemote complete");
  }

  private static final class DefaultBugReporter implements BugReporter {

    @Override
    public void sendBugReport(Throwable exception, List<String> args, String... values) {
      BugReport.sendBugReport(exception, args, values);
    }

    @Override
    public void sendNonFatalBugReport(Throwable exception) {
      BugReport.sendNonFatalBugReport(exception);
    }

    @Override
    public void handleCrash(Crash crash, CrashContext ctx) {
      BugReport.handleCrash(crash, ctx);
    }
  }
}
