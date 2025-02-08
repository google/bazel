// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.ExecutionProgressReceiver;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.runtime.SkymeldUiStateTracker.BuildStatus;
import com.google.devtools.build.lib.skyframe.AnalysisProgressReceiver;
import com.google.devtools.build.lib.skyframe.ConfigurationPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.LoadingPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.PackageProgressReceiver;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.LoggingTerminalWriter;
import com.google.devtools.build.lib.util.io.PositionAwareAnsiTerminalWriter;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link SkymeldUiStateTracker}. */
@RunWith(JUnit4.class)
public class SkymeldUiStateTrackerTest extends FoundationTestCase {

  @Test
  public void buildStarted_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_NOT_STARTED);
    uiStateTracker.buildStarted();
    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_STARTED);
  }

  @Test
  public void loadingStarted_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    uiStateTracker.loadingStarted(
        new LoadingPhaseStartedEvent(mock(PackageProgressReceiver.class)));

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.TARGET_PATTERN_PARSING);
  }

  @Test
  public void loadingComplete_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    uiStateTracker.loadingComplete(
        new LoadingPhaseCompleteEvent(
            ImmutableSet.of(), ImmutableSet.of(), RepositoryMapping.ALWAYS_FALLBACK));

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.LOADING_COMPLETE);
  }

  @Test
  public void configurationStarted_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    uiStateTracker.configurationStarted(
        new ConfigurationPhaseStartedEvent(mock(AnalysisProgressReceiver.class)));

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.CONFIGURATION);
  }

  @Test
  public void analysisAndExecution_stateChangesAndWriteProgressBar() throws IOException {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);
    String additionalMessage = "5 targets";
    uiStateTracker.setBuildStatusForTestingOnly(BuildStatus.CONFIGURATION);
    uiStateTracker.additionalMessage = additionalMessage;

    // First we need to set up the state tracker to already be analysing.
    String loadingState = "42 packages loaded";
    String loadingActivity = "currently loading //src/foo/bar and 17 more";
    uiStateTracker.packageProgressReceiver =
        mockPackageProgressReceiver(loadingState, loadingActivity);

    String analysisProgressString = "5 targets and 0 aspects configured";
    uiStateTracker.analysisProgressReceiver = mockAnalysisProgressReceiver(analysisProgressString);

    // Mock starting execution while configuring (before analysis complete).
    ExecutionProgressReceiver executionProgressReceiver = new ExecutionProgressReceiver(0, null);
    uiStateTracker.progressReceiverAvailable(
        new ExecutionProgressReceiverAvailableEvent(executionProgressReceiver));

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.ANALYSIS_AND_EXECUTION);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    uiStateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    // Should write analysis and execution information.
    assertThat(output).contains("Analyzing");
    assertThat(output).contains(additionalMessage);
    assertThat(output).contains(loadingState);
    assertThat(output).contains(loadingActivity);
    assertThat(output).contains(analysisProgressString);
    assertThat(output).doesNotContain("[0 / 0]");
  }

  @Test
  public void executionFromAnalysisAndExecution_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    uiStateTracker.analysisComplete();

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.EXECUTION);
  }

  @Test
  public void buildCompleted_stateChanges() {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);

    BuildResult buildResult = new BuildResult(clock.currentTimeMillis());
    buildResult.setDetailedExitCode(DetailedExitCode.success());
    clock.advanceMillis(SECONDS.toMillis(1));
    buildResult.setStopTime(clock.currentTimeMillis());
    var unused = uiStateTracker.buildComplete(new BuildCompleteEvent(buildResult));

    assertThat(uiStateTracker.getBuildStatus()).isEqualTo(BuildStatus.BUILD_COMPLETED);
  }

  @Test
  public void testWriteBaseProgress() throws IOException {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);
    String status = "status";
    String message = "hello";

    uiStateTracker.buildStarted();
    uiStateTracker.ok = true;
    LoggingTerminalWriter okTerminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ false);
    uiStateTracker.writeBaseProgress(
        status, message, new PositionAwareAnsiTerminalWriter(okTerminalWriter));
    assertOutputContainsBaseProgress(
        okTerminalWriter.getTranscript(), status, message, /*ok=*/ true);

    uiStateTracker.ok = false;
    LoggingTerminalWriter notOkTerminalWriter =
        new LoggingTerminalWriter(/*discardHighlight=*/ false);
    uiStateTracker.writeBaseProgress(
        status, message, new PositionAwareAnsiTerminalWriter(notOkTerminalWriter));
    assertOutputContainsBaseProgress(
        notOkTerminalWriter.getTranscript(), status, message, /*ok=*/ false);
  }

  @Test
  public void testWriteLoadingAnalysisPhaseProgress() throws IOException {
    ManualClock clock = new ManualClock();
    SkymeldUiStateTracker uiStateTracker = new SkymeldUiStateTracker(clock);
    uiStateTracker.ok = true;
    String status = "status";
    String message = "message";
    String loadingState = "42 packages loaded";
    String loadingActivity = "currently loading //src/foo/bar and 17 more";
    String analysisProgressString = "5 targets and 0 aspects configured";

    // Mock starting loading.
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ false);
    uiStateTracker.packageProgressReceiver =
        mockPackageProgressReceiver(loadingState, loadingActivity);

    // Output should only contain loading-related output.
    uiStateTracker.writeLoadingAnalysisPhaseProgress(
        status,
        message,
        new PositionAwareAnsiTerminalWriter(terminalWriter),
        /*shortVersion=*/ false);
    String loadingOutput = terminalWriter.getTranscript();
    assertOutputContainsBaseProgress(loadingOutput, status, message, /*ok=*/ true);
    assertThat(loadingOutput).contains("(" + loadingState + ")");
    assertThat(loadingOutput).contains(loadingActivity);
    assertThat(loadingOutput).doesNotContain(analysisProgressString);

    terminalWriter.reset();
    // When there is an empty message (only happens during target pattern parsing).
    uiStateTracker.writeLoadingAnalysisPhaseProgress(
        status,
        /*message=*/ "",
        new PositionAwareAnsiTerminalWriter(terminalWriter),
        /*shortVersion=*/ false);
    String emptyMessageLoadingOutput = terminalWriter.getTranscript();
    assertOutputContainsBaseProgress(
        emptyMessageLoadingOutput, status, /*message=*/ "", /*ok=*/ true);
    // The loading state should not be parenthesized.
    assertThat(emptyMessageLoadingOutput).doesNotContain("(" + loadingState + ")");
    assertThat(emptyMessageLoadingOutput).contains(loadingState);
    assertThat(emptyMessageLoadingOutput).contains(loadingActivity);
    assertThat(emptyMessageLoadingOutput).doesNotContain(analysisProgressString);

    terminalWriter.reset();
    // When writing as a short version.
    uiStateTracker.writeLoadingAnalysisPhaseProgress(
        status,
        message,
        new PositionAwareAnsiTerminalWriter(terminalWriter),
        /*shortVersion=*/ true);
    String shortVersionLoadingOutput = terminalWriter.getTranscript();
    assertOutputContainsBaseProgress(shortVersionLoadingOutput, status, message, /*ok=*/ true);
    // Output should only contain the loading state but not the activity.
    assertThat(shortVersionLoadingOutput).contains(loadingState);
    assertThat(shortVersionLoadingOutput).doesNotContain(loadingActivity);
    assertThat(emptyMessageLoadingOutput).doesNotContain(analysisProgressString);

    terminalWriter.reset();
    // Mock starting configuration.
    uiStateTracker.analysisProgressReceiver = mockAnalysisProgressReceiver(analysisProgressString);

    // Output should contain both loading and analysis related output.
    uiStateTracker.writeLoadingAnalysisPhaseProgress(
        status,
        message,
        new PositionAwareAnsiTerminalWriter(terminalWriter),
        /*shortVersion=*/ false);
    String loadingAnalysisOutput = terminalWriter.getTranscript();
    assertOutputContainsBaseProgress(loadingAnalysisOutput, status, message, /*ok=*/ true);
    assertThat(loadingAnalysisOutput).contains(loadingState);
    assertThat(loadingAnalysisOutput).contains(loadingActivity);
    assertThat(loadingAnalysisOutput).contains(analysisProgressString);
  }

  private static void assertOutputContainsBaseProgress(
      String output, String status, String message, boolean ok) {
    String okIndicator = ok ? LoggingTerminalWriter.OK : LoggingTerminalWriter.FAIL;
    assertThat(output)
        .contains(okIndicator + status + ":" + LoggingTerminalWriter.NORMAL + " " + message);
  }

  private static PackageProgressReceiver mockPackageProgressReceiver(
      String state, String activity) {
    PackageProgressReceiver packageProgressReceiver = mock(PackageProgressReceiver.class);
    when(packageProgressReceiver.progressState()).thenReturn(new Pair<>(state, activity));
    return packageProgressReceiver;
  }

  private static AnalysisProgressReceiver mockAnalysisProgressReceiver(String progress) {
    AnalysisProgressReceiver analysisProgressReceiver = mock(AnalysisProgressReceiver.class);
    when(analysisProgressReceiver.getProgressString()).thenReturn(progress);
    return analysisProgressReceiver;
  }
}
