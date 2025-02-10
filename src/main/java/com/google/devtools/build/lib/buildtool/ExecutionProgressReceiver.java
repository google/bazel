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
package com.google.devtools.build.lib.buildtool;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionChangePrunedEvent;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog;
import com.google.devtools.build.lib.skyframe.AspectCompletionValue;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.BuildDriverKey;
import com.google.devtools.build.lib.skyframe.BuildDriverValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor;
import com.google.devtools.build.lib.skyframe.TargetCompletionValue;
import com.google.devtools.build.lib.skyframe.TopLevelAspectsValue;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.AspectBuiltEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetBuiltEvent;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.GroupedDeps;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Listener for executed actions and built artifacts. We use a listener so that we have an accurate
 * set of successfully run actions and built artifacts, even if the build is interrupted.
 */
public final class ExecutionProgressReceiver
    implements SkyframeActionExecutor.ProgressSupplier,
        SkyframeActionExecutor.ActionCompletedReceiver,
        EvaluationProgressReceiver {
  private static final ThreadLocal<NumberFormat> PROGRESS_MESSAGE_NUMBER_FORMATTER =
      ThreadLocal.withInitial(
          () -> {
            NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.ENGLISH);
            numberFormat.setGroupingUsed(true);
            return numberFormat;
          });

  private final Set<ActionLookupData> enqueuedActions = Sets.newConcurrentHashSet();
  private final Set<ActionLookupData> completedActions = Sets.newConcurrentHashSet();
  private final Set<ActionLookupData> ignoredActions = Sets.newConcurrentHashSet();
  private final EventBus eventBus;

  /** Number of exclusive tests. To be accounted for in progress messages. */
  private final int exclusiveTestsCount;

  /**
   * {@code builtTargets} is accessed through a synchronized set, and so no other access to it is
   * permitted while this receiver is active.
   */
  public ExecutionProgressReceiver(int exclusiveTestsCount, EventBus eventBus) {
    this.exclusiveTestsCount = exclusiveTestsCount;
    this.eventBus = eventBus;
  }

  @Override
  public void enqueueing(SkyKey skyKey) {
    if (skyKey.functionName().equals(SkyFunctions.ACTION_EXECUTION)) {
      ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
      if (!ignoredActions.contains(actionLookupData)) {
        // Remember all enqueued actions for the benefit of progress reporting.
        // We discover most actions early in the build, well before we start executing them.
        // Some of these will be cache hits and won't be executed, so we'll need to account for them
        // in the evaluated method too.
        enqueuedActions.add(actionLookupData);
      }
    }
  }

  @Override
  public void evaluated(
      SkyKey skyKey,
      EvaluationState state,
      @Nullable SkyValue newValue,
      @Nullable ErrorInfo newError,
      @Nullable GroupedDeps directDeps) {
    SkyFunctionName type = skyKey.functionName();
    if (type.equals(SkyFunctions.ACTION_EXECUTION)) {
      // Remember all completed actions, even those in error, regardless of having been cached or
      // really executed.
      actionCompleted((ActionLookupData) skyKey.argument());
      return;
    }

    if (!state.succeeded()) {
      return;
    }

    if (type.equals(SkyFunctions.TARGET_COMPLETION)) {
      ConfiguredTargetKey configuredTargetKey =
          ((TargetCompletionValue.TargetCompletionKey) skyKey).actionLookupKey();
      eventBus.post(TopLevelTargetBuiltEvent.create(configuredTargetKey));
      return;
    }

    if (type.equals(SkyFunctions.ASPECT_COMPLETION)) {
      AspectKeyCreator.AspectKey aspectKey =
          ((AspectCompletionValue.AspectCompletionKey) skyKey).actionLookupKey();
      eventBus.post(AspectBuiltEvent.create(aspectKey));
      return;
    }

    if (type.equals(SkyFunctions.BUILD_DRIVER)) {
      BuildDriverKey buildDriverKey = (BuildDriverKey) skyKey;
      // BuildDriverKeys are re-evaluated every build.
      BuildDriverValue buildDriverValue = (BuildDriverValue) Preconditions.checkNotNull(newValue);

      if (buildDriverValue.isSkipped()) {
        return;
      }

      if (buildDriverKey.isTopLevelAspectDriver()) {
        ((TopLevelAspectsValue) buildDriverValue.getWrappedSkyValue())
            .getTopLevelAspectsMap()
            .keySet()
            .forEach(x -> eventBus.post(AspectBuiltEvent.create(x)));
        return;
      }

      eventBus.post(
          TopLevelTargetBuiltEvent.create(
              ConfiguredTargetKey.fromConfiguredTarget(
                  ((ConfiguredTargetValue) buildDriverValue.getWrappedSkyValue())
                      .getConfiguredTarget())));
    }
  }

  @Override
  public void changePruned(SkyKey skyKey) {
    if (skyKey.functionName().equals(SkyFunctions.ACTION_EXECUTION)) {
      eventBus.post(
          new ActionChangePrunedEvent((ActionLookupData) skyKey.argument(), BlazeClock.nanoTime()));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method adds the action lookup data to {@link #completedActions} and notifies the {@link
   * #activityIndicator}.
   *
   * <p>We could do this only in the {@link EvaluationProgressReceiver#evaluated} method too, but as
   * it happens the action executor tells the reporter about the completed action before the node is
   * inserted into the graph, so the reporter would find out about the completed action sooner than
   * we could have updated {@link #completedActions}, which would result in incorrect numbers on the
   * progress messages. However we have to store completed actions in {@link
   * EvaluationProgressReceiver#evaluated} too, because that's the only place we get notified about
   * completed cached actions.
   */
  @Override
  public void actionCompleted(ActionLookupData actionLookupData) {
    if (!ignoredActions.contains(actionLookupData)) {
      enqueuedActions.add(actionLookupData);
      completedActions.add(actionLookupData);
    }
  }

  @Override
  public String getProgressString() {
    return String.format(
        "[%s / %s]",
        PROGRESS_MESSAGE_NUMBER_FORMATTER.get().format(completedActions.size()),
        PROGRESS_MESSAGE_NUMBER_FORMATTER
            .get()
            .format(exclusiveTestsCount + enqueuedActions.size()));
  }

  ActionExecutionInactivityWatchdog.InactivityMonitor createInactivityMonitor(
      final ActionExecutionStatusReporter statusReporter) {
    return new ActionExecutionInactivityWatchdog.InactivityMonitor() {

      @Override
      public boolean hasStarted() {
        return !enqueuedActions.isEmpty();
      }

      @Override
      public int getPending() {
        return statusReporter.getCount();
      }

      @Override
      public int waitForNextCompletion(int timeoutSeconds) throws InterruptedException {
        int before = completedActions.size();
        // Otherwise, wake up once per second to see whether something completed.
        for (int i = 0; i < timeoutSeconds; i++) {
          Thread.sleep(1000);
          int count = completedActions.size() - before;
          if (count > 0) {
            return count;
          }
        }
        return 0;
      }
    };
  }

  ActionExecutionInactivityWatchdog.InactivityReporter createInactivityReporter(
      final ActionExecutionStatusReporter statusReporter,
      final AtomicBoolean isBuildingExclusiveArtifacts) {
    return new ActionExecutionInactivityWatchdog.InactivityReporter() {
      @Override
      public void maybeReportInactivity() {
        // Do not report inactivity if we are currently running an exclusive test or a streaming
        // action (in practice only tests can stream and it implicitly makes them exclusive).
        if (!isBuildingExclusiveArtifacts.get()) {
          statusReporter.showCurrentlyExecutingActions(
              ExecutionProgressReceiver.this.getProgressString() + " ");
        }
      }
    };
  }

  public boolean hasActionsInFlight() {
    return completedActions.size() < exclusiveTestsCount + enqueuedActions.size();
  }
}
