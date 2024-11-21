// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.TestLogHandler;
import com.google.devtools.build.lib.bugreport.BugReport.BlazeRuntimeInterface;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Crash.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.CustomExitCodePublisher;
import com.google.devtools.build.lib.util.CustomFailureDetailPublisher;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.protobuf.ExtensionRegistry;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link BugReport}.
 *
 * <p>Uses {@link ExitProhibitingSecurityManager} to exercise attempting to halt the JVM without
 * aborting the whole test.
 */
// TODO(b/222158599): Remove handling for GoogleTestSecurityManager.
@RunWith(TestParameterInjector.class)
public final class BugReportTest {

  private static final ExitCode EXIT_CODE_BLAZE_OOMING = ExitCode.OOM_ERROR;
  private static final Code FAILURE_DETAIL_CODE_BLAZE_OOMING = Code.CRASH_OOM;

  @TestParameter private boolean oomDetectorOverride;

  @Before
  public void maybeSetOomDetector() {
    if (oomDetectorOverride) {
      CrashFailureDetails.setOomDetector(() -> true);
    }
  }

  @After
  public void restoreDefaultOomDetector() {
    if (oomDetectorOverride) {
      CrashFailureDetails.setOomDetector(() -> false);
    }
  }

  private enum CrashType {
    CRASH(ExitCode.BLAZE_INTERNAL_ERROR, Code.CRASH_UNKNOWN) {
      @Override
      Throwable createThrowable() {
        return new IllegalStateException("Crashed");
      }
    },
    OOM(ExitCode.OOM_ERROR, Code.CRASH_OOM) {
      @Override
      Throwable createThrowable() {
        return new OutOfMemoryError("Java heap space");
      }
    };

    private final ExitCode expectedExitCode;
    private final Code expectedFailureDetailCode;

    CrashType(ExitCode expectedExitCode, Code expectedFailureDetailCode) {
      this.expectedExitCode = expectedExitCode;
      this.expectedFailureDetailCode = expectedFailureDetailCode;
    }

    abstract Throwable createThrowable();
  }

  private enum ExceptionType {
    FATAL(
        new RuntimeException("fatal exception"),
        /* isFatal= */ true,
        Level.SEVERE,
        "myProductName crashed with args: arg foo"),
    NONFATAL(
        new IllegalStateException("bug report"),
        /* isFatal= */ false,
        Level.WARNING,
        "myProductName had a non fatal error with args: arg foo"),
    OOM(
        new OutOfMemoryError("Java heap space"),
        /* isFatal= */ true,
        Level.SEVERE,
        "myProductName OOMError: arg foo");

    @SuppressWarnings("ImmutableEnumChecker") // I'm pretty sure no one will mutate this Throwable.
    private final Throwable throwable;

    @SuppressWarnings("ImmutableEnumChecker") // Same here.
    private final Level level;

    private final boolean isFatal;

    private final String expectedMessage;

    ExceptionType(Throwable throwable, boolean isFatal, Level level, String expectedMessage) {
      this.throwable = throwable;
      this.isFatal = isFatal;
      this.level = level;
      this.expectedMessage = expectedMessage;
    }

    private String getExpectedMessage() {
      return expectedMessage;
    }

    private String getExpectedMessageWhileOoming() {
      return "While OOMing, " + expectedMessage;
    }
  }

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private final BlazeRuntimeInterface mockRuntime = mock(BlazeRuntimeInterface.class);

  private Path exitCodeFile;
  private Path failureDetailFile;

  @Before
  public void setup() throws Exception {
    when(mockRuntime.getProductName()).thenReturn("myProductName");
    BugReport.setRuntime(mockRuntime);

    exitCodeFile = tmp.newFolder().toPath().resolve("exit_code_to_use_on_abrupt_exit");
    failureDetailFile = tmp.newFolder().toPath().resolve("failure_detail");

    CustomExitCodePublisher.setAbruptExitStatusFileDir(exitCodeFile.getParent().toString());
    CustomFailureDetailPublisher.setFailureDetailFilePath(failureDetailFile.toString());
  }

  @After
  public void resetPublishers() {
    CustomExitCodePublisher.resetAbruptExitStatusFile();
    CustomFailureDetailPublisher.resetFailureDetailFilePath();
  }

  @Test
  public void logException(@TestParameter ExceptionType exceptionType) {
    TestLogHandler handler = new TestLogHandler();
    Logger logger = Logger.getLogger("build.lib.bugreport");
    logger.addHandler(handler);
    LoggingUtil.installRemoteLoggerForTesting(immediateFuture(logger));

    BugReport.logException(
        exceptionType.throwable, exceptionType.isFatal, ImmutableList.of("arg", "foo"));
    LogRecord got = handler.getStoredLogRecords().get(0);
    if (oomDetectorOverride) {
      assertThat(got.getMessage()).isEqualTo(exceptionType.getExpectedMessageWhileOoming());
    } else {
      assertThat(got.getMessage()).isEqualTo(exceptionType.getExpectedMessage());
    }
    assertThat(got.getThrown()).isSameInstanceAs(exceptionType.throwable);
    assertThat(got.getLevel()).isEqualTo(exceptionType.level);
  }

  @Test
  public void convenienceMethod(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);
    SecurityException exitException =
        assertThrows(
            SecurityException.class,
            () -> BugReport.handleCrash(Crash.from(t), CrashContext.halt()));
    int code = haltCode(exitException);
    assertThat(code).isEqualTo(expectedExitCode(crashType).getNumericExitCode());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyExitCodeWritten(
        oomDetectorOverride
            ? EXIT_CODE_BLAZE_OOMING.getNumericExitCode()
            : crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void halt(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);

    SecurityException exitException =
        assertThrows(
            SecurityException.class,
            () -> BugReport.handleCrash(Crash.from(t), CrashContext.halt()));
    int code = haltCode(exitException);
    ExitCode expectedExitCode = expectedExitCode(crashType);
    assertThat(code).isEqualTo(expectedExitCode.getNumericExitCode());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);
    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyExitCodeWritten(
        oomDetectorOverride
            ? EXIT_CODE_BLAZE_OOMING.getNumericExitCode()
            : crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void keepAlive(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyNoExitCodeWritten();
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void haltOrReturnIfCrashInProgress_otherCrashInProgress_returnsEagerly(
      @TestParameter CrashType crashType) throws Throwable {
    // Arrange:
    // A first thread will crash with CrashContext.halt(). We mock out the BlazeRuntimeInterface to
    // force this thread to block while holding the BugReport global lock.
    CountDownLatch cleanupBegunLatch = new CountDownLatch(1);
    CountDownLatch cleanupMayFinishLatch = new CountDownLatch(1);
    doAnswer(
            (inv) -> {
              cleanupBegunLatch.countDown();
              cleanupMayFinishLatch.await();
              return null;
            })
        .when(mockRuntime)
        .cleanUpForCrash(any(DetailedExitCode.class));

    Throwable firstThrown = new IllegalStateException("second crash in background thread");
    ThrowingRunnable doFirstCrash =
        () -> BugReport.handleCrash(Crash.from(firstThrown), CrashContext.halt());
    AtomicReference<SecurityException> firstCrashThrownRef = new AtomicReference<>(null);
    TestThread firstCrashThread =
        new TestThread(
            () -> firstCrashThrownRef.set(assertThrows(SecurityException.class, doFirstCrash)));
    firstCrashThread.start();
    cleanupBegunLatch.await();

    // Act:
    // Try to crash on a second thread, with a `haltOrReturnIfCrashInProgress` CrashContext. This
    // should return without throwing because the BugReport global lock is held.
    Throwable secondThrown = crashType.createThrowable();
    CrashContext haltOrReturnCtx = CrashContext.haltOrReturnIfCrashInProgress();
    ThrowingRunnable doSecondCrash =
        () -> BugReport.handleCrash(Crash.from(secondThrown), haltOrReturnCtx);
    doSecondCrash.run();

    // Assert:
    // Allow the first crashing thread to finish, then confirm that the
    // `CrashContext.haltOrReturnIfCrashInProgress()` will halt when BugReport's lock is free.
    cleanupMayFinishLatch.countDown();
    firstCrashThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);

    SecurityException firstException = firstCrashThrownRef.get();
    int firstCode = haltCode(firstException);
    ExitCode expectedExitCode =
        oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : ExitCode.BLAZE_INTERNAL_ERROR;
    assertThat(firstCode).isEqualTo(expectedExitCode.getNumericExitCode());

    SecurityException secondException = assertThrows(SecurityException.class, doSecondCrash);
    int secondCode = haltCode(secondException);
    assertThat(secondCode).isEqualTo(expectedExitCode(crashType).getNumericExitCode());
  }

  @Test
  public void customContext_setUpFront(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);

    BugReport.handleCrash(
        Crash.from(t),
        CrashContext.keepAlive().withExtraOomInfo("Build fewer targets!").reportingTo(handler));
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));
    if (oomDetectorOverride || crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("ran out of memory and crashed.");
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  @Test
  public void customContext_filledInByRuntime(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
    doAnswer(
            inv ->
                inv.getArgument(0, CrashContext.class)
                    .withExtraOomInfo("Build fewer targets!")
                    .reportingTo(handler))
        .when(mockRuntime)
        .fillInCrashContext(any());

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));

    if (oomDetectorOverride || crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("ran out of memory and crashed.");
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  private void verifyExitCodeWritten(int exitCode) throws Exception {
    assertThat(Files.readAllLines(exitCodeFile)).containsExactly(String.valueOf(exitCode));
  }

  private void verifyNoExitCodeWritten() {
    assertThat(exitCodeFile.toFile().exists()).isFalse();
  }

  private void verifyFailureDetailWritten(FailureDetail expected) throws Exception {
    assertThat(
            FailureDetail.parseFrom(
                Files.readAllBytes(failureDetailFile), ExtensionRegistry.getEmptyRegistry()))
        .isEqualTo(expected);
  }

  private static FailureDetail createExpectedFailureDetail(
      Throwable t, CrashType crashType, boolean oomDetectorOverride) {
    FailureDetails.Crash.Builder crash =
        FailureDetails.Crash.newBuilder()
            .setCode(
                oomDetectorOverride
                    ? FAILURE_DETAIL_CODE_BLAZE_OOMING
                    : crashType.expectedFailureDetailCode)
            .addCauses(
                FailureDetails.Throwable.newBuilder()
                    .setThrowableClass(t.getClass().getName())
                    .setMessage(t.getMessage())
                    .addAllStackTrace(
                        Lists.transform(
                            Arrays.asList(t.getStackTrace()), StackTraceElement::toString)));
    if (oomDetectorOverride && crashType == CrashType.CRASH) {
      crash.setOomDetectorOverride(true);
    }
    return FailureDetail.newBuilder()
        .setMessage(String.format("Crashed: (%s) %s", t.getClass().getName(), t.getMessage()))
        .setCrash(crash)
        .build();
  }

  private ExitCode expectedExitCode(CrashType crashType) {
    return oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode;
  }

  private static final Pattern SECURITY_EXCEPTION_MESSAGE_PATTERN =
      Pattern.compile("Intercepted call to Runtime\\.halt with status (\\d+)");

  private static int haltCode(SecurityException exitException) {
    String message = exitException.getMessage();
    assertThat(message).matches(SECURITY_EXCEPTION_MESSAGE_PATTERN);
    Matcher matcher = SECURITY_EXCEPTION_MESSAGE_PATTERN.matcher(message);
    assertThat(matcher.matches()).isTrue();
    return Integer.parseInt(matcher.group(1));
  }
}
