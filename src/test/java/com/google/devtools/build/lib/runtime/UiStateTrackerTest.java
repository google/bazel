// Copyright 2016 The Bazel Authors. All rights reserved.
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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionProgressEvent;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.ActionUploadFinishedEvent;
import com.google.devtools.build.lib.actions.ActionUploadStartedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.RunningActionEvent;
import com.google.devtools.build.lib.actions.ScanningActionEvent;
import com.google.devtools.build.lib.actions.SchedulingActionEvent;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadProgressEvent;
import com.google.devtools.build.lib.buildeventstream.AnnounceBuildEventTransportsEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransportClosedEvent;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.ExecutionProgressReceiver;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent;
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.runtime.SkymeldUiStateTracker.BuildStatus;
import com.google.devtools.build.lib.runtime.UiStateTracker.StrategyIds;
import com.google.devtools.build.lib.skyframe.AnalysisProgressReceiver;
import com.google.devtools.build.lib.skyframe.ConfigurationPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.LoadingPhaseStartedEvent;
import com.google.devtools.build.lib.skyframe.PackageProgressReceiver;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TestAnalyzedEvent;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.LoggingTerminalWriter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;

/** Tests {@link UiStateTracker}. */
@RunWith(TestParameterInjector.class)
public class UiStateTrackerTest extends FoundationTestCase {

  @TestParameter boolean isSkymeld;
  static final RepositoryMapping MOCK_REPO_MAPPING =
      RepositoryMapping.createAllowingFallback(ImmutableMap.of("main", RepositoryName.MAIN));

  private UiStateTracker getUiStateTracker(ManualClock clock) {
    if (isSkymeld) {
      return new SkymeldUiStateTracker(clock);
    } else {
      return new UiStateTracker(clock);
    }
  }

  private UiStateTracker getUiStateTracker(ManualClock clock, int targetWidth) {
    if (isSkymeld) {
      return new SkymeldUiStateTracker(clock, targetWidth);
    } else {
      return new UiStateTracker(clock, targetWidth);
    }
  }

  @Test
  public void testStrategyIds_getId_idsAreBitmasks() {
    StrategyIds strategyIds = new StrategyIds();
    Integer id1 = strategyIds.getId("foo");
    Integer id2 = strategyIds.getId("bar");
    Integer id3 = strategyIds.getId("baz");

    assertThat(id1).isGreaterThan(0);
    assertThat(id2).isGreaterThan(0);
    assertThat(id3).isGreaterThan(0);

    assertThat(id1 & id2).isEqualTo(0);
    assertThat(id1 & id3).isEqualTo(0);
    assertThat(id2 & id3).isEqualTo(0);
  }

  @Test
  public void testStrategyIds_getId_idsAreReusedIfAlreadyExist() {
    StrategyIds strategyIds = new StrategyIds();
    Integer id1 = strategyIds.getId("foo");
    Integer id2 = strategyIds.getId("bar");
    Integer id3 = strategyIds.getId("foo");

    assertThat(id1).isNotEqualTo(id2);
    assertThat(id1).isEqualTo(id3);
  }

  @Test
  public void testStrategyIds_getId_exhaustIds() {
    StrategyIds strategyIds = new StrategyIds();
    Set<Integer> ids = new HashSet<>();
    StringBuilder name = new StringBuilder();
    for (; ; ) {
      name.append('a');
      Integer id = strategyIds.getId(name.toString());
      if (id.equals(strategyIds.fallbackId)) {
        break;
      }
      ids.add(id);
    }
    assertThat(ids).hasSize(Integer.SIZE - 1); // Minus 1 for FALLBACK_NAME.

    assertThat(strategyIds.getId("some")).isEqualTo(strategyIds.fallbackId);
    assertThat(strategyIds.getId("more")).isEqualTo(strategyIds.fallbackId);
  }

  @Test
  public void testStrategyIds_formatNames_fallbackExistsByDefault() {
    StrategyIds strategyIds = new StrategyIds();
    assertThat(strategyIds.formatNames(strategyIds.fallbackId))
        .isEqualTo(StrategyIds.FALLBACK_NAME);
  }

  @Test
  public void testStrategyIds_formatNames_oneHasNoComma() {
    StrategyIds strategyIds = new StrategyIds();
    Integer id1 = strategyIds.getId("abc");
    assertThat(strategyIds.formatNames(id1)).isEqualTo("abc");
  }

  @Test
  public void testStrategyIds_formatNames() {
    StrategyIds strategyIds = new StrategyIds();
    Integer id1 = strategyIds.getId("abc");
    Integer id2 = strategyIds.getId("xyz");
    Integer id3 = strategyIds.getId("def");

    // Names are not sorted alphabetically but their order is stable based on prior getId calls.
    assertThat(strategyIds.formatNames(id1 | id2)).isEqualTo("abc, xyz");
    assertThat(strategyIds.formatNames(id1 | id3)).isEqualTo("abc, def");
    assertThat(strategyIds.formatNames(id2 | id3)).isEqualTo("xyz, def");
    assertThat(strategyIds.formatNames(id1 | id2 | id3)).isEqualTo("abc, xyz, def");
  }

  private Action mockAction(String progressMessage, String primaryOutput) {
    Path path = outputBase.getRelative(PathFragment.create(primaryOutput));
    Artifact artifact =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path);

    Action action = mock(Action.class);
    when(action.getProgressMessage(eq(MOCK_REPO_MAPPING))).thenReturn(progressMessage);
    when(action.getPrimaryOutput()).thenReturn(artifact);

    verify(action, never()).getProgressMessage(AdditionalMatchers.not(eq(MOCK_REPO_MAPPING)));
    verify(action, never()).getProgressMessage();
    return action;
  }

  private ActionOwner dummyActionOwner() throws LabelSyntaxException {
    return ActionOwner.createDummy(
        Label.parseCanonical("//foo:a"),
        new Location("dummy-file", 0, 0),
        /* targetKind= */ "",
        /* buildConfigurationMnemonic= */ "",
        /* configurationChecksum= */ "",
        new BuildConfigurationEvent(
            BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
            BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
        /* isToolConfiguration= */ true,
        /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
        /* aspectDescriptors= */ ImmutableList.of(),
        /* execProperties= */ ImmutableMap.of());
  }

  private void simulateExecutionPhase(UiStateTracker uiStateTracker) {
    uiStateTracker.loadingComplete(
        new LoadingPhaseCompleteEvent(ImmutableSet.of(), ImmutableSet.of(), MOCK_REPO_MAPPING));
    if (this.isSkymeld) {
      // SkymeldUiStateTracker needs to be in the configuration phase before the execution phase.
      ((SkymeldUiStateTracker) uiStateTracker)
          .setBuildStatusForTestingOnly(BuildStatus.ANALYSIS_COMPLETE);
      uiStateTracker.executionPhaseStarted();
    } else {
      String unused = uiStateTracker.analysisComplete();
    }
    uiStateTracker.progressReceiverAvailable(
        new ExecutionProgressReceiverAvailableEvent(dummyExecutionProgressReceiver()));
  }

  private ExecutionProgressReceiver dummyExecutionProgressReceiver() {
    return new ExecutionProgressReceiver(0, null);
  }

  private static int longestLine(String output) {
    int maxLength = 0;
    for (String line : output.split("\n")) {
      maxLength = Math.max(maxLength, line.length());
    }
    return maxLength;
  }

  @Test
  public void testLoadingActivity() throws IOException {
    // During loading phase, state and activity, as reported by the PackageProgressReceiver,
    // should be visible in the progress bar.
    String loadingState = "42 packages loaded";
    String loadingActivity = "currently loading //src/foo/bar and 17 more";
    PackageProgressReceiver progress = mock(PackageProgressReceiver.class);
    when(progress.progressState()).thenReturn(new Pair<>(loadingState, loadingActivity));

    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);

    stateTracker.loadingStarted(new LoadingPhaseStartedEvent(progress));

    // When it is just loading packages.
    LoggingTerminalWriter terminalWriterLoading =
        new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriterLoading);
    String loadingOutput = terminalWriterLoading.getTranscript();

    assertThat(loadingOutput).contains("Loading");
    assertThat(loadingOutput).contains(loadingState);
    assertThat(loadingOutput).contains(loadingActivity);

    // When it is configuring targets.
    stateTracker.loadingComplete(
        new LoadingPhaseCompleteEvent(ImmutableSet.of(), ImmutableSet.of(), MOCK_REPO_MAPPING));
    String additionalMessage = "5 targets";
    stateTracker.additionalMessage = additionalMessage;
    String analysisProgressString = "5 targets and 0 aspects configured";
    AnalysisProgressReceiver analysisProgressReceiver = mock(AnalysisProgressReceiver.class);
    when(analysisProgressReceiver.getProgressString()).thenReturn(analysisProgressString);
    stateTracker.configurationStarted(new ConfigurationPhaseStartedEvent(analysisProgressReceiver));

    LoggingTerminalWriter terminalWriterLoadingConfiguration =
        new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriterLoadingConfiguration);
    String loadingConfigurationOutput = terminalWriterLoadingConfiguration.getTranscript();
    assertThat(loadingConfigurationOutput).contains("Analyzing");
    assertThat(loadingConfigurationOutput).contains(additionalMessage);
    assertThat(loadingConfigurationOutput).contains(loadingState);
    assertThat(loadingConfigurationOutput).contains(loadingActivity);
    // It should contain the analysis progress string along with the loading information.
    assertThat(loadingConfigurationOutput).contains(analysisProgressString);
  }

  @Test
  public void testActionVisible() throws IOException {
    // If there is only one action running, it should be visible
    // somewhere in the progress bar, and also the short version thereof.

    String message = "Building foo";
    ManualClock clock = new ManualClock();
    clock.advanceMillis(120000);

    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(mockAction(message, "bar/foo"), 123456789));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertWithMessage("Action message '" + message + "' should be present in output: " + output)
        .that(output.contains(message))
        .isTrue();

    terminalWriter = new LoggingTerminalWriter();
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertWithMessage(
            "Action message '" + message + "' should be present in short output: " + output)
        .that(output.contains(message))
        .isTrue();
  }

  @Test
  public void testCompletedActionNotShown() throws IOException {
    // Completed actions should not be reported in the progress bar, nor in the
    // short progress bar.

    String messageFast = "Running quick action";
    String messageSlow = "Running slow action";

    ManualClock clock = new ManualClock();
    clock.advanceMillis(120000);
    Action fastAction = mockAction(messageFast, "foo/fast");
    Action slowAction = mockAction(messageSlow, "bar/slow");
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(fastAction, 123456789));
    stateTracker.actionStarted(new ActionStartedEvent(slowAction, 123456999));

    ActionLookupData actionLookupData = ActionLookupData.create(mock(ActionLookupKey.class), 1);
    stateTracker.actionCompletion(
        new ActionCompletionEvent(
            20, clock.nanoTime(), fastAction, new FakeActionInputFileCache(), actionLookupData));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertWithMessage(
            "Completed action '" + messageFast + "' should not be present in output: " + output)
        .that(output.contains(messageFast))
        .isFalse();
    assertWithMessage(
            "Only running action '" + messageSlow + "' should be present in output: " + output)
        .that(output.contains(messageSlow))
        .isTrue();

    terminalWriter = new LoggingTerminalWriter();
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertWithMessage(
            "Completed action '"
                + messageFast
                + "' should not be present in short output: "
                + output)
        .that(output.contains(messageFast))
        .isFalse();
    assertWithMessage(
            "Only running action '"
                + messageSlow
                + "' should be present in short output: "
                + output)
        .that(output.contains(messageSlow))
        .isTrue();
  }

  @Test
  public void testOldestActionVisible() throws IOException {
    // The earliest-started action is always visible somehow in the progress bar
    // and its short version.

    String messageOld = "Running the first-started action";

    ManualClock clock = new ManualClock();
    clock.advanceMillis(120000);
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(
        new ActionStartedEvent(mockAction(messageOld, "bar/foo"), 123456789));
    for (int i = 0; i < 30; i++) {
      stateTracker.actionStarted(
          new ActionStartedEvent(
              mockAction("Other action " + i, "some/other/actions/number" + i), 123456790 + i));
    }

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertWithMessage(
            "Longest running action '" + messageOld + "' should be visible in output: " + output)
        .that(output.contains(messageOld))
        .isTrue();

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertWithMessage(
            "Longest running action '"
                + messageOld
                + "' should be visible in short output: "
                + output)
        .that(output.contains(messageOld))
        .isTrue();
  }

  @Test
  public void testSampleSize() throws IOException {
    // Verify that the number of actions shown in the progress bar can be set as sample size.
    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(123));
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(2));

    // Start 10 actions (numbered 0 to 9).
    for (int i = 0; i < 10; i++) {
      clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
      Action action = mockAction("Performing action A" + i + ".", "action_A" + i + ".out");
      stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    }

    // For various sample sizes verify the progress bar
    for (int i = 1; i < 11; i++) {
      stateTracker.setProgressSampleSize(i);
      LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
      stateTracker.writeProgressBar(terminalWriter);
      String output = terminalWriter.getTranscript();
      assertWithMessage("Action " + (i - 1) + " should still be shown in the output: '" + output)
          .that(output.contains("A" + (i - 1) + "."))
          .isTrue();
      assertWithMessage("Action " + i + " should not be shown in the output: " + output)
          .that(output.contains("A" + i + "."))
          .isFalse();
      if (i < 10) {
        assertWithMessage("Ellipsis symbol should be shown in output: " + output)
            .that(output.contains("..."))
            .isTrue();
      } else {
        assertWithMessage("Ellipsis symbol should not be shown in output: " + output)
            .that(output.contains("..."))
            .isFalse();
      }
    }
  }

  @Test
  public void testTimesShown() throws IOException {
    // For sufficiently long running actions, the time that has passed since their start is shown.
    // In the short version of the progress bar, this should be true at least for the oldest action.

    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(123));
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(2));

    stateTracker.actionStarted(
        new ActionStartedEvent(mockAction("First action", "foo"), clock.nanoTime()));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(7));
    stateTracker.actionStarted(
        new ActionStartedEvent(mockAction("Second action", "bar"), clock.nanoTime()));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(20));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertWithMessage("Runtime of first action should be visible in output: " + output)
        .that(output.contains("27s"))
        .isTrue();
    assertWithMessage("Runtime of second action should be visible in output: " + output)
        .that(output.contains("20s"))
        .isTrue();

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertWithMessage("Runtime of first action should be visible in short output: " + output)
        .that(output.contains("27s"))
        .isTrue();
  }

  @Test
  public void initialProgressBarTimeIndependent() {
    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(123));
    UiStateTracker stateTracker = getUiStateTracker(clock);
    stateTracker.buildStarted();

    assertWithMessage("Initial progress status should be time independent")
        .that(stateTracker.progressBarTimeDependent())
        .isFalse();
  }

  @Test
  public void runningActionTimeIndependent() {
    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(123));
    UiStateTracker stateTracker = getUiStateTracker(clock);
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(
        new ActionStartedEvent(mockAction("Some action", "foo"), clock.nanoTime()));

    assertWithMessage("Progress bar showing a running action should be time dependent")
        .that(stateTracker.progressBarTimeDependent())
        .isTrue();
  }

  @Test
  public void testCountVisible() throws Exception {
    // The test count should be visible in the status bar, as well as the short status bar
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    TestFilteringCompleteEvent filteringComplete = mock(TestFilteringCompleteEvent.class);
    Label labelA = Label.parseCanonical("//foo/bar:baz");
    ConfiguredTarget targetA = mock(ConfiguredTarget.class);
    when(targetA.getLabel()).thenReturn(labelA);
    ConfiguredTarget targetB = mock(ConfiguredTarget.class);
    when(filteringComplete.getTestTargets()).thenReturn(ImmutableSet.of(targetA, targetB));
    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getTarget()).thenReturn(targetA);
    when(testSummary.getLabel()).thenReturn(labelA);

    stateTracker.testFilteringComplete(filteringComplete);
    stateTracker.testSummary(testSummary);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertWithMessage("Test count should be visible in output: " + output)
        .that(output.contains(" 1 / 2 tests"))
        .isTrue();

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertWithMessage("Test count should be visible in short output: " + output)
        .that(output.contains(" 1 / 2 tests"))
        .isTrue();
  }

  @Test
  public void testPassedVisible() throws Exception {
    // The last test should still be visible in the long status bar, and colored as ok if it passed.
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    TestFilteringCompleteEvent filteringComplete = mock(TestFilteringCompleteEvent.class);
    Label labelA = Label.parseCanonical("//foo/bar:baz");
    ConfiguredTarget targetA = mock(ConfiguredTarget.class);
    when(targetA.getLabel()).thenReturn(labelA);
    ConfiguredTarget targetB = mock(ConfiguredTarget.class);
    when(filteringComplete.getTestTargets()).thenReturn(ImmutableSet.of(targetA, targetB));
    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getStatus()).thenReturn(BlazeTestStatus.PASSED);
    when(testSummary.getTarget()).thenReturn(targetA);
    when(testSummary.getLabel()).thenReturn(labelA);

    stateTracker.testFilteringComplete(filteringComplete);
    stateTracker.testSummary(testSummary);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter();
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    String expected = LoggingTerminalWriter.OK + labelA;
    assertWithMessage(
            "Sequence '" + expected + "' should be present in colored progress bar: " + output)
        .that(output.contains(expected))
        .isTrue();
  }

  @Test
  public void testFailedVisible() throws Exception {
    // The last test should still be visible in the long status bar, and colored as fail if it
    // did not pass.
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    TestFilteringCompleteEvent filteringComplete = mock(TestFilteringCompleteEvent.class);
    Label labelA = Label.parseCanonical("//foo/bar:baz");
    ConfiguredTarget targetA = mock(ConfiguredTarget.class);
    when(targetA.getLabel()).thenReturn(labelA);
    ConfiguredTarget targetB = mock(ConfiguredTarget.class);
    when(filteringComplete.getTestTargets()).thenReturn(ImmutableSet.of(targetA, targetB));
    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getStatus()).thenReturn(BlazeTestStatus.FAILED);
    when(testSummary.getTarget()).thenReturn(targetA);
    when(testSummary.getLabel()).thenReturn(labelA);

    stateTracker.testFilteringComplete(filteringComplete);
    stateTracker.testSummary(testSummary);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter();
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    String expected = LoggingTerminalWriter.FAIL + labelA;
    assertWithMessage(
            "Sequence '" + expected + "' should be present in colored progress bar: " + output)
        .that(output.contains(expected))
        .isTrue();
  }

  @Test
  public void testSensibleShortening() throws Exception {
    // Verify that in the typical case, we shorten the progress message by shortening
    // the path implicit in it, that can also be extracted from the label. In particular,
    // the parts
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 70);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    Action action =
        mockAction(
            "Building some/very/very/long/path/for/some/library/directory/foo.jar (42 source"
                + " files)",
            "some/very/very/long/path/for/some/library/directory/foo.jar");
    Label label =
        Label.parseCanonical("//some/very/very/long/path/for/some/library/directory:libfoo");
    ActionOwner owner =
        ActionOwner.createDummy(
            label,
            new Location("dummy-file", 0, 0),
            /* targetKind= */ "dummy-target-kind",
            /* buildConfigurationMnemonic= */ "dummy-mnemonic",
            /* configurationChecksum= */ "fedcba",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
            /* aspectDescriptors= */ ImmutableList.of(),
            /* execProperties= */ ImmutableMap.of());
    when(action.getOwner()).thenReturn(owner);

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(3));
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(5));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage("Progress bar should contain 'Building ', but was:\n" + output)
        .that(output.contains("Building "))
        .isTrue();
    assertWithMessage(
            "Progress bar should contain 'foo.jar (42 source files)', but was:\n" + output)
        .that(output.contains("foo.jar (42 source files)"))
        .isTrue();
  }

  @Test
  public void testActionStrategyVisible() throws Exception {
    // verify that, if a strategy was reported for a shown action, it is visible
    // in the progress bar.
    String strategy = "verySpecialStrategy";
    String primaryOutput = "some/path/to/a/file";

    ManualClock clock = new ManualClock();
    Path path = outputBase.getRelative(PathFragment.create(primaryOutput));
    Artifact artifact =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path);
    Action action = mockAction("Some random action", primaryOutput);
    when(action.getOwner()).thenReturn(dummyActionOwner());
    when(action.getPrimaryOutput()).thenReturn(artifact);

    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.runningAction(new RunningActionEvent(action, strategy));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage("Output should mention strategy '" + strategy + "', but was: " + output)
        .that(output.contains(strategy))
        .isTrue();
  }

  private Action createDummyAction(String progressMessage) throws LabelSyntaxException {
    String primaryOutput = "some/path/to/a/file";
    Path path = outputBase.getRelative(PathFragment.create(primaryOutput));
    Artifact artifact =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path);
    Action action = mockAction(progressMessage, primaryOutput);
    when(action.getOwner()).thenReturn(dummyActionOwner());
    when(action.getPrimaryOutput()).thenReturn(artifact);
    return action;
  }

  @Test
  public void actionProgress_visible() throws Exception {
    // arrange
    ManualClock clock = new ManualClock();
    Action action = createDummyAction("Some random action");
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 70);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.actionProgress(
        ActionProgressEvent.create(action, "action-id", "action progress", false));
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    // act
    stateTracker.writeProgressBar(terminalWriter);

    // assert
    String output = terminalWriter.getTranscript();
    assertThat(output).contains("action progress");
  }

  @Test
  public void actionProgress_withTooSmallWidth_progressSkipped() throws Exception {
    // arrange
    ManualClock clock = new ManualClock();
    Action action = createDummyAction("Some random action");
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 30);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.actionProgress(
        ActionProgressEvent.create(action, "action-id", "action progress", false));
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    // act
    stateTracker.writeProgressBar(terminalWriter);

    // assert
    String output = terminalWriter.getTranscript();
    assertThat(output).doesNotContain("action progress");
  }

  @Test
  public void actionProgress_withSmallWidth_progressShortened() throws Exception {
    // arrange
    ManualClock clock = new ManualClock();
    Action action = createDummyAction("some action");
    // The targetWidth needs to be small enough to cause shortening to occur.
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 40);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.actionProgress(
        ActionProgressEvent.create(action, "action-id", "action progress", false));
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    // act
    stateTracker.writeProgressBar(terminalWriter);

    // assert
    String output = terminalWriter.getTranscript();
    assertThat(output).contains("action p...");
  }

  @Test
  public void actionProgress_multipleProgress_displayInOrder() throws Exception {
    // arrange
    ManualClock clock = new ManualClock();
    Action action = createDummyAction("Some random action");
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 70);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.actionProgress(
        ActionProgressEvent.create(action, "action-id1", "action progress 1", false));
    stateTracker.actionProgress(
        ActionProgressEvent.create(action, "action-id2", "action progress 2", false));
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    // act
    stateTracker.writeProgressBar(terminalWriter);

    // assert
    String output = terminalWriter.getTranscript();
    assertThat(output).contains("action progress 1");
    assertThat(output).doesNotContain("action progress 2");
  }

  @Test
  public void testMultipleActionStrategiesVisibleForDynamicScheduling() throws Exception {
    String strategy1 = "strategy1";
    String strategy2 = "stratagy2";
    String primaryOutput = "some/path/to/a/file";

    ManualClock clock = new ManualClock();
    Path path = outputBase.getRelative(PathFragment.create(primaryOutput));
    Artifact artifact =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path);
    Action action = mockAction("Some random action", primaryOutput);
    when(action.getOwner()).thenReturn(dummyActionOwner());
    when(action.getPrimaryOutput()).thenReturn(artifact);

    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    stateTracker.runningAction(new RunningActionEvent(action, strategy1));
    stateTracker.runningAction(new RunningActionEvent(action, strategy2));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage(
            "Output should mention strategies '"
                + strategy1
                + "' and '"
                + strategy2
                + "', but was: "
                + output)
        .that(output.contains(strategy1 + ", " + strategy2))
        .isTrue();
  }

  @Test
  public void testActionCountsWithDynamicScheduling() throws Exception {
    String primaryOutput1 = "some/path/to/a/file";
    String primaryOutput2 = "some/path/to/b/file";

    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    Path path1 = outputBase.getRelative(PathFragment.create(primaryOutput1));
    Artifact artifact1 =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path1);
    Action action1 = mockAction("First random action", primaryOutput1);
    when(action1.getOwner()).thenReturn(dummyActionOwner());
    when(action1.getPrimaryOutput()).thenReturn(artifact1);
    stateTracker.actionStarted(new ActionStartedEvent(action1, clock.nanoTime()));

    Path path2 = outputBase.getRelative(PathFragment.create(primaryOutput2));
    Artifact artifact2 =
        ActionsTestUtil.createArtifact(ArtifactRoot.asSourceRoot(Root.fromPath(outputBase)), path2);
    Action action2 = mockAction("First random action", primaryOutput1);
    when(action2.getOwner()).thenReturn(dummyActionOwner());
    when(action2.getPrimaryOutput()).thenReturn(artifact2);
    stateTracker.actionStarted(new ActionStartedEvent(action2, clock.nanoTime()));

    stateTracker.runningAction(new RunningActionEvent(action1, "strategy1"));
    stateTracker.schedulingAction(new SchedulingActionEvent(action2, "strategy1"));
    terminalWriter.reset();
    stateTracker.writeProgressBar(terminalWriter);
    assertThat(terminalWriter.getTranscript()).contains("2 actions, 1 running");

    stateTracker.runningAction(new RunningActionEvent(action1, "strategy2"));
    terminalWriter.reset();
    stateTracker.writeProgressBar(terminalWriter);
    assertThat(terminalWriter.getTranscript()).contains("2 actions, 1 running");

    stateTracker.runningAction(new RunningActionEvent(action2, "strategy1"));
    terminalWriter.reset();
    stateTracker.writeProgressBar(terminalWriter);
    assertThat(terminalWriter.getTranscript()).contains("2 actions running");

    stateTracker.runningAction(new RunningActionEvent(action2, "strategy2"));
    terminalWriter.reset();
    stateTracker.writeProgressBar(terminalWriter);
    assertThat(terminalWriter.getTranscript()).contains("2 actions running");
  }

  private void doTestOutputLength(boolean withTest, int actions) throws Exception {
    // If we target 70 characters, then there should be enough space to both,
    // keep the line limit, and show the local part of the running actions and
    // the passed test.
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 70);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);

    Action foobuildAction =
        mockAction(
            "Building"
                + " //src/some/very/long/path/long/long/long/long/long/long/long/foo/foobuild.jar",
            "src/some/very/long/path/long/long/long/long/long/long/long/foo/foobuild.jar");
    Action bazbuildAction =
        mockAction(
            "Building"
                + " //src/some/very/long/path/long/long/long/long/long/long/long/baz/bazbuild.jar",
            "src/some/very/long/path/long/long/long/long/long/long/long/baz/bazbuild.jar");

    Label bartestLabel =
        Label.parseCanonical(
            "//src/another/very/long/long/path/long/long/long/long/long/long/long/long/bars:bartest");
    ConfiguredTarget bartestTarget = mock(ConfiguredTarget.class);
    when(bartestTarget.getLabel()).thenReturn(bartestLabel);

    TestFilteringCompleteEvent filteringComplete = mock(TestFilteringCompleteEvent.class);
    when(filteringComplete.getTestTargets()).thenReturn(ImmutableSet.of(bartestTarget));

    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getStatus()).thenReturn(BlazeTestStatus.PASSED);
    when(testSummary.getTarget()).thenReturn(bartestTarget);
    when(testSummary.getLabel()).thenReturn(bartestLabel);

    if (actions >= 1) {
      stateTracker.actionStarted(new ActionStartedEvent(foobuildAction, 123456789));
    }
    if (actions >= 2) {
      stateTracker.actionStarted(new ActionStartedEvent(bazbuildAction, 123456900));
    }
    if (withTest) {
      stateTracker.testFilteringComplete(filteringComplete);
      stateTracker.testSummary(testSummary);
    }

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage(
            "Only lines with at most 70 chars should be present in the output:\n" + output)
        .that(longestLine(output) <= 70)
        .isTrue();
    if (actions >= 1) {
      assertWithMessage("Running action 'foobuild' should be mentioned in output:\n" + output)
          .that(output.contains("foobuild"))
          .isTrue();
    }
    if (actions >= 2) {
      assertWithMessage("Running action 'bazbuild' should be mentioned in output:\n" + output)
          .that(output.contains("bazbuild"))
          .isTrue();
    }
    if (withTest) {
      assertWithMessage("Passed test ':bartest' should be mentioned in output:\n" + output)
          .that(output.contains(":bartest"))
          .isTrue();
    }
  }

  @Test
  public void testOutputLength() throws Exception {
    for (int i = 0; i < 3; i++) {
      doTestOutputLength(true, i);
      doTestOutputLength(false, i);
    }
  }

  @Test
  public void testStatusShown() throws Exception {
    // Verify that for non-executing actions, at least the first 3 characters of the
    // status are shown.
    // Also verify that the number of running actions is reported correctly, if there is
    // more than one active action and not all are running.
    ManualClock clock = new ManualClock();
    clock.advanceMillis(120000);
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    Action actionFoo = mockAction("Building foo", "foo/foo");
    ActionOwner ownerFoo = dummyActionOwner();
    when(actionFoo.getOwner()).thenReturn(ownerFoo);
    Action actionBar = mockAction("Building bar", "bar/bar");
    ActionOwner ownerBar = dummyActionOwner();
    when(actionBar.getOwner()).thenReturn(ownerBar);
    LoggingTerminalWriter terminalWriter;
    String output;

    // Action foo being scanned.
    stateTracker.actionStarted(new ActionStartedEvent(actionFoo, 123456700));
    stateTracker.scanningAction(new ScanningActionEvent(actionFoo));

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Action foo being scanned should be visible in output:\n" + output)
        .that(output.contains("sca") || output.contains("Sca"))
        .isTrue();

    // Then action bar gets scheduled.
    stateTracker.actionStarted(new ActionStartedEvent(actionBar, 123456701));
    stateTracker.schedulingAction(new SchedulingActionEvent(actionBar, "bar-sandbox"));

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Action bar being scheduled should be visible in output:\n" + output)
        .that(output.contains("sch") || output.contains("Sch"))
        .isTrue();
    assertWithMessage("Action foo being scanned should still be visible in output:\n" + output)
        .that(output.contains("sca") || output.contains("Sca"))
        .isTrue();
    assertWithMessage("Indication that no actions are running is missing in output:\n" + output)
        .that(output.contains("0 running"))
        .isTrue();
    assertWithMessage("Total number of actions expected  in output:\n" + output)
        .that(output.contains("2 actions"))
        .isTrue();

    // Then foo starts.
    stateTracker.runningAction(new RunningActionEvent(actionFoo, "xyz-sandbox"));
    stateTracker.writeProgressBar(terminalWriter);

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Action foo's xyz-sandbox strategy should be shown in output:\n" + output)
        .that(output.contains("xyz-sandbox"))
        .isTrue();
    assertWithMessage("Action foo should no longer be analyzed in output:\n" + output)
        .that(output.contains("ana") || output.contains("Ana"))
        .isFalse();
    assertWithMessage("Action bar being scheduled should still be visible in output:\n" + output)
        .that(output.contains("sch") || output.contains("Sch"))
        .isTrue();
    assertWithMessage("Indication that one action is running is missing in output:\n" + output)
        .that(output.contains("1 running"))
        .isTrue();
    assertWithMessage("Total number of actions expected  in output:\n" + output)
        .that(output.contains("2 actions"))
        .isTrue();
  }

  @Test
  public void testTimerReset() throws Exception {
    // Verify that a change in an action state (e.g., from scheduling to executing) resets
    // the time associated with that action.

    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(123));
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(2));
    LoggingTerminalWriter terminalWriter;
    String output;

    Action actionFoo = mockAction("Building foo", "foo/foo");
    ActionOwner ownerFoo = dummyActionOwner();
    when(actionFoo.getOwner()).thenReturn(ownerFoo);
    Action actionBar = mockAction("Building bar", "bar/bar");
    ActionOwner ownerBar = dummyActionOwner();
    when(actionBar.getOwner()).thenReturn(ownerBar);

    stateTracker.actionStarted(new ActionStartedEvent(actionFoo, clock.nanoTime()));
    stateTracker.runningAction(new RunningActionEvent(actionFoo, "foo-sandbox"));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(7));
    stateTracker.actionStarted(new ActionStartedEvent(actionBar, clock.nanoTime()));
    stateTracker.schedulingAction(new SchedulingActionEvent(actionBar, "bar-sandbox"));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(21));

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Runtime of first action should be visible in output: " + output)
        .that(output.contains("28s"))
        .isTrue();
    assertWithMessage("Scheduling time of second action should be visible in output: " + output)
        .that(output.contains("21s"))
        .isTrue();

    stateTracker.runningAction(new RunningActionEvent(actionBar, "bar-sandbox"));
    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Runtime of first action should still be visible in output: " + output)
        .that(output.contains("28s"))
        .isTrue();
    assertWithMessage("Time of second action should no longer be visible in output: " + output)
        .that(output.contains("21s"))
        .isFalse();

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(30));
    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("New runtime of first action should be visible in output: " + output)
        .that(output.contains("58s"))
        .isTrue();
    assertWithMessage("Runtime of second action should be visible in output: " + output)
        .that(output.contains("30s"))
        .isTrue();
  }

  @Test
  public void testEarlyStatusHandledGracefully() throws Exception {
    // On the event bus, events sometimes are sent out of order; verify that we handle an
    // early message that an action is running gracefully.
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    Action actionFoo = mockAction("Building foo", "foo/foo");
    ActionOwner ownerFoo = dummyActionOwner();
    when(actionFoo.getOwner()).thenReturn(ownerFoo);
    LoggingTerminalWriter terminalWriter;
    String output;

    // Early status announcement
    stateTracker.runningAction(new RunningActionEvent(actionFoo, "foo-sandbox"));

    // Here we don't expect any particular output, just some description; in particular, we do
    // not expect the state tracker to hit an internal error.
    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Expected at least some status bar").that(output).isNotEmpty();

    // Action actually started
    stateTracker.actionStarted(new ActionStartedEvent(actionFoo, clock.nanoTime()));

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertWithMessage("Even a strategy announced early should be shown in output:\n" + output)
        .that(output.contains("foo-sandbox"))
        .isTrue();
  }

  @Test
  public void testExecutingActionsFirst() throws Exception {
    // Verify that executing actions, even if started late, are visible.
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    clock.advanceMillis(120000);

    for (int i = 0; i < 30; i++) {
      Action action = mockAction("Takes long to schedule number " + i, "long/startup" + i);
      ActionOwner owner = dummyActionOwner();
      when(action.getOwner()).thenReturn(owner);
      stateTracker.actionStarted(new ActionStartedEvent(action, 123456789 + i));
      stateTracker.schedulingAction(new SchedulingActionEvent(action, "xyz-sandbox"));
    }

    for (int i = 0; i < 3; i++) {
      Action action = mockAction("quickstart" + i, "pkg/quickstart" + i);
      ActionOwner owner = dummyActionOwner();
      when(action.getOwner()).thenReturn(owner);
      stateTracker.actionStarted(new ActionStartedEvent(action, 123457000 + i));
      stateTracker.runningAction(new RunningActionEvent(action, "xyz-sandbox"));

      LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
      stateTracker.writeProgressBar(terminalWriter);
      String output = terminalWriter.getTranscript();
      assertWithMessage("Action quickstart" + i + " should be visible in output:\n" + output)
          .that(output.contains("quickstart" + i))
          .isTrue();
      assertWithMessage("Number of running actions should be indicated in output:\n" + output)
          .that(output.contains((i + 1) + " running"))
          .isTrue();
    }
  }

  @Test
  public void testAggregation() throws Exception {
    // Assert that actions for the same test are aggregated so that an action afterwards
    // is still shown.
    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1234));
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 80);
    stateTracker.setProgressSampleSize(4);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);

    Label labelFooTest = Label.parseCanonical("//foo/bar:footest");
    ConfiguredTarget targetFooTest = mock(ConfiguredTarget.class);
    when(targetFooTest.getLabel()).thenReturn(labelFooTest);
    ActionOwner fooOwner =
        ActionOwner.createDummy(
            labelFooTest,
            new Location("dummy-file", 0, 0),
            /* targetKind= */ "dummy-target-kind",
            /* buildConfigurationMnemonic= */ "TestRunner",
            /* configurationChecksum= */ "abcdef",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
            /* aspectDescriptors= */ ImmutableList.of(),
            /* execProperties= */ ImmutableMap.of());

    Label labelBarTest = Label.parseCanonical("//baz:bartest");
    ConfiguredTarget targetBarTest = mock(ConfiguredTarget.class);
    when(targetBarTest.getLabel()).thenReturn(labelBarTest);
    ActionOwner barOwner =
        ActionOwner.createDummy(
            labelBarTest,
            new Location("dummy-file", 0, 0),
            /* targetKind= */ "dummy-target-kind",
            /* buildConfigurationMnemonic= */ "TestRunner",
            /* configurationChecksum= */ "abcdef",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
            /* aspectDescriptors= */ ImmutableList.of(),
            /* execProperties= */ ImmutableMap.of());

    Label labelBazTest = Label.parseCanonical("//baz:baztest");
    ConfiguredTarget targetBazTest = mock(ConfiguredTarget.class);
    when(targetBazTest.getLabel()).thenReturn(labelBazTest);
    ActionOwner bazOwner =
        ActionOwner.createDummy(
            labelBazTest,
            new Location("dummy-file", 0, 0),
            /* targetKind= */ "dummy-target-kind",
            /* buildConfigurationMnemonic= */ "NonTestAction",
            /* configurationChecksum= */ "fedcba",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
            /* aspectDescriptors= */ ImmutableList.of(),
            /* execProperties= */ ImmutableMap.of());

    TestFilteringCompleteEvent filteringComplete = mock(TestFilteringCompleteEvent.class);
    when(filteringComplete.getTestTargets())
        .thenReturn(ImmutableSet.of(targetFooTest, targetBarTest, targetBazTest));
    stateTracker.testFilteringComplete(filteringComplete);

    // First produce 10 actions for footest...
    for (int i = 0; i < 10; i++) {
      clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
      Action action = mockAction("Testing foo, shard " + i, "testlog_foo_" + i);
      when(action.getOwner()).thenReturn(fooOwner);
      stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    }
    // ...then produce 10 actions for bartest...
    for (int i = 0; i < 10; i++) {
      clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
      Action action = mockAction("Testing bar, shard " + i, "testlog_bar_" + i);
      when(action.getOwner()).thenReturn(barOwner);
      stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    }
    // ...run a completely unrelated action..
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.actionStarted(
        new ActionStartedEvent(mockAction("Other action", "other/action"), clock.nanoTime()));
    // ...and finally, run actions that are associated with baztest but are not a test.
    for (int i = 0; i < 10; i++) {
      clock.advanceMillis(1_000);
      Action action = mockAction("Doing something " + i, "someartifact_" + i);
      when(action.getOwner()).thenReturn(bazOwner);
      stateTracker.actionStarted(new ActionStartedEvent(action, clock.nanoTime()));
    }
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage("Progress bar should contain ':footest', but was:\n" + output)
        .that(output.contains(":footest"))
        .isTrue();
    assertWithMessage("Progress bar should contain ':bartest', but was:\n" + output)
        .that(output.contains(":bartest"))
        .isTrue();
    assertWithMessage("Progress bar should contain 'Other action', but was:\n" + output)
        .that(output.contains("Other action"))
        .isTrue();
    assertThat(output).doesNotContain("Testing //baz:baztest");
    assertThat(output).contains("Doing something");
  }

  @Test
  public void testSuffix() {
    assertThat(UiStateTracker.suffix("foobar", 3)).isEqualTo("bar");
    assertThat(UiStateTracker.suffix("foo", -2)).isEmpty();
    assertThat(UiStateTracker.suffix("foobar", 200)).isEqualTo("foobar");
  }

  @Test
  public void testDownloadShown_duringLoading() throws Exception {
    // Verify that, whenever a single download is running in loading phase, it is shown in the
    // status bar.
    ManualClock clock = new ManualClock();
    clock.advance(Duration.ofSeconds(1234));
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 80);

    URL url = new URL("http://example.org/first/dep");

    stateTracker.buildStarted();
    stateTracker.downloadProgress(new DownloadProgressEvent(url));
    clock.advance(Duration.ofSeconds(6));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertThat(output).contains(url.toString());
    assertThat(output).contains("6s");

    // Progress on the pending download should be reported appropriately
    clock.advance(Duration.ofSeconds(1));
    stateTracker.downloadProgress(new DownloadProgressEvent(url, 256));

    terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();

    assertThat(output).contains(url.toString());
    assertThat(output).contains("7s");
    assertThat(output).contains("256");

    // After finishing the download, it should no longer be reported.
    clock.advance(Duration.ofSeconds(1));
    stateTracker.downloadProgress(new DownloadProgressEvent(url, 256, true));

    terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();

    assertThat(output).doesNotContain("example.org");
  }

  @Test
  public void testDownloadShown_duringMainRepoMappingComputation() throws Exception {
    ManualClock clock = new ManualClock();
    clock.advance(Duration.ofSeconds(1234));
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 80);

    URL url = new URL("http://example.org/first/dep");

    stateTracker.mainRepoMappingComputationStarted();
    stateTracker.downloadProgress(new DownloadProgressEvent(url));
    clock.advance(Duration.ofSeconds(6));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertThat(output).contains(url.toString());
    assertThat(output).contains("6s");

    // Progress on the pending download should be reported appropriately
    clock.advance(Duration.ofSeconds(1));
    stateTracker.downloadProgress(new DownloadProgressEvent(url, 256));

    terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();

    assertThat(output).contains(url.toString());
    assertThat(output).contains("7s");
    assertThat(output).contains("256");

    // After finishing the download, it should no longer be reported.
    clock.advance(Duration.ofSeconds(1));
    stateTracker.downloadProgress(new DownloadProgressEvent(url, 256, true));

    terminalWriter = new LoggingTerminalWriter(/* discardHighlight= */ true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();

    assertThat(output).doesNotContain("example.org");
  }

  @Test
  public void testDownloadOutputLength() throws Exception {
    // Verify that URLs are shortened in a reasonable way, if the terminal is not wide enough
    // Also verify that the length is respected, even if only a download sample is shown.
    ManualClock clock = new ManualClock();
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1234));
    UiStateTracker stateTracker = getUiStateTracker(clock, /* targetWidth= */ 60);
    URL url = new URL("http://example.org/some/really/very/very/long/path/filename.tar.gz");

    stateTracker.buildStarted();
    stateTracker.downloadProgress(new DownloadProgressEvent(url));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(6));
    for (int i = 0; i < 10; i++) {
      stateTracker.downloadProgress(
          new DownloadProgressEvent(
              new URL(
                  "http://otherhost.example/another/also/length/path/to/another/download"
                      + i
                      + ".zip")));
      clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    }

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();

    assertWithMessage(
            "Only lines with at most 60 chars should be present in the output:\n" + output)
        .that(longestLine(output) <= 60)
        .isTrue();
    assertWithMessage("Output still should contain the filename, but was:\n" + output)
        .that(output.contains("filename.tar.gz"))
        .isTrue();
    assertWithMessage("Output still should contain the host name, but was:\n" + output)
        .that(output.contains("example.org"))
        .isTrue();
  }

  @Test
  public void testMultipleBuildEventProtocolTransports() throws Exception {
    // Verify that all announced transports are present in the progress bar
    // and that as transports are closed they disappear from the progress bar.
    // Verify that the wait duration is displayed.
    // Verify that after all transports have been closed, the build status is displayed.
    ManualClock clock = new ManualClock();
    BuildEventTransport transport1 = newBepTransport("BuildEventTransport1");
    BuildEventTransport transport2 = newBepTransport("BuildEventTransport2");
    BuildEventTransport transport3 = newBepTransport("BuildEventTransport3");
    BuildResult buildResult = new BuildResult(clock.currentTimeMillis());
    buildResult.setDetailedExitCode(DetailedExitCode.success());
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    buildResult.setStopTime(clock.currentTimeMillis());

    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 80);
    stateTracker.buildStarted();
    stateTracker.buildEventTransportsAnnounced(
        new AnnounceBuildEventTransportsEvent(ImmutableList.of(transport1, transport2)));
    stateTracker.buildEventTransportsAnnounced(
        new AnnounceBuildEventTransportsEvent(ImmutableList.of(transport3)));
    var unused = stateTracker.buildComplete(new BuildCompleteEvent(buildResult));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(true);

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertThat(output, containsString("1s"));
    assertThat(output, containsString("BuildEventTransport1"));
    assertThat(output, containsString("BuildEventTransport2"));
    assertThat(output, containsString("BuildEventTransport3"));

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.buildEventTransportClosed(new BuildEventTransportClosedEvent(transport1));
    terminalWriter = new LoggingTerminalWriter(true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertThat(output, containsString("2s"));
    assertThat(output, not(containsString("BuildEventTransport1")));
    assertThat(output, containsString("BuildEventTransport2"));
    assertThat(output, containsString("BuildEventTransport3"));

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.buildEventTransportClosed(new BuildEventTransportClosedEvent(transport3));
    terminalWriter = new LoggingTerminalWriter(true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertThat(output, containsString("3s"));
    assertThat(output, not(containsString("BuildEventTransport1")));
    assertThat(output, containsString("BuildEventTransport2"));
    assertThat(output, not(containsString("BuildEventTransport3")));

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.buildEventTransportClosed(new BuildEventTransportClosedEvent(transport2));
    terminalWriter = new LoggingTerminalWriter(true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertThat(output, not(containsString("3s")));
    assertThat(output, not(containsString("BuildEventTransport1")));
    assertThat(output, not(containsString("BuildEventTransport2")));
    assertThat(output, not(containsString("BuildEventTransport3")));
    assertThat(output.split("\\n")).hasLength(1);
  }

  @Test
  public void testBuildEventTransportsOnNarrowTerminal() throws IOException {
    // Verify that the progress bar contains useful information on a 60-character terminal.
    //   - Too long names should be shortened to reasonably long prefixes of the name.
    ManualClock clock = new ManualClock();
    BuildEventTransport transport1 = newBepTransport("A".repeat(61));
    BuildEventTransport transport2 = newBepTransport("BuildEventTransport");
    BuildResult buildResult = new BuildResult(clock.currentTimeMillis());
    buildResult.setDetailedExitCode(DetailedExitCode.success());
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(true);
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 60);
    stateTracker.buildStarted();
    stateTracker.buildEventTransportsAnnounced(
        new AnnounceBuildEventTransportsEvent(ImmutableList.of(transport1, transport2)));
    var unused = stateTracker.buildComplete(new BuildCompleteEvent(buildResult));
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertThat(longestLine(output)).isAtMost(60);
    assertThat(output, containsString("1s"));
    assertThat(output, containsString("A".repeat(30) + "..."));
    assertThat(output, containsString("BuildEventTransport"));

    clock.advanceMillis(TimeUnit.SECONDS.toMillis(1));
    stateTracker.buildEventTransportClosed(new BuildEventTransportClosedEvent(transport2));
    terminalWriter = new LoggingTerminalWriter(true);
    stateTracker.writeProgressBar(terminalWriter);
    output = terminalWriter.getTranscript();
    assertThat(longestLine(output)).isAtMost(60);
    assertThat(output, containsString("2s"));
    assertThat(output, containsString("A".repeat(30) + "..."));
    assertThat(output, not(containsString("BuildEventTransport")));
    assertThat(output.split("\\n")).hasLength(2);
  }

  private static BuildEventTransport newBepTransport(String name) {
    BuildEventTransport transport = mock(BuildEventTransport.class);
    when(transport.name()).thenReturn(name);
    return transport;
  }

  @Test
  public void testTotalFetchesReported() throws IOException {
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock, /*targetWidth=*/ 80);

    stateTracker.buildStarted();
    for (int i = 0; i < 30; i++) {
      stateTracker.downloadProgress(new FetchEvent("@repoFoo" + i));
    }
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(7));

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertThat(output, containsString("@repoFoo"));
    assertThat(output, containsString("7s"));
    assertThat(output, containsString("30 fetches"));
  }

  private static class FetchEvent implements FetchProgress {
    private final String id;

    FetchEvent(String id) {
      this.id = id;
    }

    @Override
    public String getResourceIdentifier() {
      return id;
    }

    @Override
    public String getProgress() {
      return "working...";
    }

    @Override
    public boolean isFinished() {
      return false;
    }
  }

  @Test
  public void waitingRemoteCacheMessage_beforeBuildComplete_invisible() throws IOException {
    ManualClock clock = new ManualClock();
    Action action = mockAction("Some action", "foo");
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionUploadStarted(
        ActionUploadStartedEvent.create(
            action, Store.AC, Digest.newBuilder().setHash("foo").setSizeBytes(1).build()));
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    stateTracker.writeProgressBar(terminalWriter);

    String output = terminalWriter.getTranscript();
    assertThat(output).doesNotContain("1 upload");
  }

  @Test
  public void waitingRemoteCacheMessage_afterBuildComplete_visible() throws IOException {
    ManualClock clock = new ManualClock();
    Action action = mockAction("Some action", "foo");
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    stateTracker.actionUploadStarted(
        ActionUploadStartedEvent.create(
            action, Store.AC, Digest.newBuilder().setHash("foo").setSizeBytes(1).build()));
    BuildResult buildResult = new BuildResult(clock.currentTimeMillis());
    buildResult.setDetailedExitCode(DetailedExitCode.success());
    buildResult.setStopTime(clock.currentTimeMillis());
    var unused = stateTracker.buildComplete(new BuildCompleteEvent(buildResult));
    clock.advanceMillis(Duration.ofSeconds(2).toMillis());
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    stateTracker.writeProgressBar(terminalWriter);

    String output = terminalWriter.getTranscript();
    assertThat(output).contains("1 upload");
  }

  @Test
  public void waitingRemoteCacheMessage_multipleUploadEvents_countCorrectly() throws IOException {
    Digest a = Digest.newBuilder().setHash("a").setSizeBytes(1).build();
    Digest b = Digest.newBuilder().setHash("b").setSizeBytes(2).build();
    Digest c = Digest.newBuilder().setHash("c").setSizeBytes(3).build();
    ManualClock clock = new ManualClock();
    Action action = mockAction("Some action", "foo");
    UiStateTracker stateTracker = getUiStateTracker(clock);
    stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.AC, a));
    BuildResult buildResult = new BuildResult(clock.currentTimeMillis());
    buildResult.setDetailedExitCode(DetailedExitCode.success());
    buildResult.setStopTime(clock.currentTimeMillis());
    var unused = stateTracker.buildComplete(new BuildCompleteEvent(buildResult));
    stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.CAS, b));
    stateTracker.actionUploadStarted(ActionUploadStartedEvent.create(action, Store.CAS, c));
    stateTracker.actionUploadFinished(ActionUploadFinishedEvent.create(action, Store.AC, a));
    clock.advanceMillis(Duration.ofSeconds(2).toMillis());
    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);

    stateTracker.writeProgressBar(terminalWriter);

    String output = terminalWriter.getTranscript();
    assertThat(output).contains("2 uploads");
  }

  @Test
  public void testTestAnalyzedEvent() throws Exception {
    // The test count should be visible in the status bar, as well as the short status bar
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    Label labelA = Label.parseCanonical("//foo:A");
    ConfiguredTarget targetA = mock(ConfiguredTarget.class);
    when(targetA.getLabel()).thenReturn(labelA);
    TestAnalyzedEvent testAnalyzedEventA =
        TestAnalyzedEvent.create(
            targetA, mock(BuildConfigurationValue.class), /*isSkipped=*/ false);
    Label labelB = Label.parseCanonical("//foo:B");
    ConfiguredTarget targetB = mock(ConfiguredTarget.class);
    when(targetB.getLabel()).thenReturn(labelB);
    TestAnalyzedEvent testAnalyzedEventB =
        TestAnalyzedEvent.create(
            targetB, mock(BuildConfigurationValue.class), /*isSkipped=*/ false);
    // Only targetA has finished running.
    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getTarget()).thenReturn(targetA);
    when(testSummary.getLabel()).thenReturn(labelA);

    stateTracker.singleTestAnalyzed(testAnalyzedEventA);
    stateTracker.singleTestAnalyzed(testAnalyzedEventB);
    stateTracker.testSummary(testSummary);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertThat(output).contains(" 1 / 2 tests");

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertThat(output).contains(" 1 / 2 tests");
  }

  @Test
  public void testTestAnalyzedEvent_repeated_noDuplicatedCount() throws Exception {
    // The test count should be visible in the status bar, as well as the short status bar
    ManualClock clock = new ManualClock();
    UiStateTracker stateTracker = getUiStateTracker(clock);
    // Mimic being at the execution phase.
    simulateExecutionPhase(stateTracker);
    Label labelA = Label.parseCanonical("//foo:A");
    ConfiguredTarget targetA = mock(ConfiguredTarget.class);
    when(targetA.getLabel()).thenReturn(labelA);
    TestAnalyzedEvent testAnalyzedEventA =
        TestAnalyzedEvent.create(
            targetA, mock(BuildConfigurationValue.class), /*isSkipped=*/ false);
    TestAnalyzedEvent testAnalyzedEventARepeated =
        TestAnalyzedEvent.create(
            targetA, mock(BuildConfigurationValue.class), /*isSkipped=*/ false);
    // Only targetA has finished running.
    TestSummary testSummary = mock(TestSummary.class);
    when(testSummary.getTarget()).thenReturn(targetA);
    when(testSummary.getLabel()).thenReturn(labelA);

    stateTracker.singleTestAnalyzed(testAnalyzedEventA);
    stateTracker.singleTestAnalyzed(testAnalyzedEventARepeated);
    stateTracker.testSummary(testSummary);

    LoggingTerminalWriter terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter);
    String output = terminalWriter.getTranscript();
    assertThat(output).contains(" 1 / 1 tests");

    terminalWriter = new LoggingTerminalWriter(/*discardHighlight=*/ true);
    stateTracker.writeProgressBar(terminalWriter, /* shortVersion=*/ true);
    output = terminalWriter.getTranscript();
    assertThat(output).contains(" 1 / 1 tests");
  }
}
