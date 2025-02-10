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

package com.google.devtools.build.lib.metrics.criticalpath;

import com.google.common.base.Preconditions;
import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.StackSize;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionChangePrunedEvent;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionStartedEvent;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.AggregatedSpawnMetrics;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.actions.DiscoveredInputsEvent;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewoundEvent;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Computes the critical path in the action graph based on events published to the event bus.
 *
 * <p>After instantiation, this object needs to be registered on the event bus to work.
 */
@ThreadSafe
public class CriticalPathComputer {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Number of top actions to record. */
  static final int SLOWEST_COMPONENTS_SIZE = 30;

  private static final int LARGEST_MEMORY_COMPONENTS_SIZE = 20;
  private static final int LARGEST_INPUT_SIZE_COMPONENTS_SIZE = 20;
  private static final int LARGEST_INPUT_COUNT_COMPONENTS_SIZE = 20;

  /** Selects and returns the longer of two components (the first may be {@code null}). */
  private static final BinaryOperator<CriticalPathComponent> SELECT_LONGER_COMPONENT =
      (a, b) ->
          a == null || a.getAggregatedElapsedTime().compareTo(b.getAggregatedElapsedTime()) < 0
              ? b
              : a;

  private final AtomicInteger idGenerator = new AtomicInteger();
  // outputArtifactToComponent is accessed from multiple event handlers.
  private final ConcurrentMap<Artifact, CriticalPathComponent> outputArtifactToComponent =
      Maps.newConcurrentMap();
  private final ActionKeyContext actionKeyContext;
  @Nullable private final WalkableGraph graph;

  /** Maximum critical path found. */
  private final AtomicReference<CriticalPathComponent> maxCriticalPath = new AtomicReference<>();

  public CriticalPathComputer(ActionKeyContext actionKeyContext, @Nullable WalkableGraph graph) {
    this.actionKeyContext = actionKeyContext;
    this.graph = graph;
  }

  /**
   * Creates a critical path component for an action.
   *
   * @param action the action for the critical path component
   * @param relativeStartNanos time when the action started to run in nanos. Only meant to be used
   *     for computing time differences.
   */
  private CriticalPathComponent createComponent(Action action, long relativeStartNanos) {
    return new CriticalPathComponent(idGenerator.getAndIncrement(), action, relativeStartNanos);
  }

  /**
   * Return the critical path stats for the current command execution.
   *
   * <p>This method allows us to calculate lazily the aggregate statistics of the critical path,
   * avoiding the memory and cpu penalty for doing it for all the actions executed.
   */
  public AggregatedCriticalPath aggregate() {
    CriticalPathComponent criticalPath = getMaxCriticalPath();
    if (criticalPath == null) {
      return AggregatedCriticalPath.EMPTY;
    }

    ImmutableList.Builder<CriticalPathComponent> components = ImmutableList.builder();
    AggregatedSpawnMetrics.Builder metricsBuilder = new AggregatedSpawnMetrics.Builder();
    CriticalPathComponent child = criticalPath;

    while (child != null) {
      AggregatedSpawnMetrics childSpawnMetrics = child.getSpawnMetrics();
      if (childSpawnMetrics != null) {
        metricsBuilder.addDurations(childSpawnMetrics);
        metricsBuilder.addNonDurations(childSpawnMetrics);
      }
      components.add(child);
      child = child.getChild();
    }

    return new AggregatedCriticalPath(
        (int) criticalPath.getAggregatedElapsedTime().toMillis(),
        metricsBuilder.build(),
        components.build());
  }

  public Map<Artifact, CriticalPathComponent> getCriticalPathComponentsMap() {
    return outputArtifactToComponent;
  }

  /** Changes the phase of the action */
  @Subscribe
  @AllowConcurrentEvents
  public void nextCriticalPathPhase(SpawnExecutedEvent.ChangePhase phase) {
    CriticalPathComponent stats =
        outputArtifactToComponent.get(phase.getAction().getPrimaryOutput());
    if (stats != null) {
      stats.changePhase();
    }
  }

  /** Adds spawn metrics to the action stats. */
  @Subscribe
  @AllowConcurrentEvents
  public void spawnExecuted(SpawnExecutedEvent event) {
    ActionAnalysisMetadata action = event.getActionMetadata();
    Artifact primaryOutput = action.getPrimaryOutput();
    if (primaryOutput == null) {
      // Despite the documentation to the contrary, the SpawnIncludeScanner creates an
      // ActionExecutionMetadata instance that returns a null primary output. That said, this
      // class is incorrect wrt. multiple Spawns in a single action. See b/111583707.
      return;
    }
    CriticalPathComponent stats =
        Preconditions.checkNotNull(outputArtifactToComponent.get(primaryOutput));

    SpawnResult spawnResult = event.getSpawnResult();
    stats.addSpawnResult(
        spawnResult.getMetrics(),
        spawnResult.getRunnerName(),
        spawnResult.getRunnerSubtype(),
        spawnResult.wasRemote());
  }

  /** Returns the list of components using the most memory. */
  public List<CriticalPathComponent> getLargestMemoryComponents() {
    return uniqueActions()
        .collect(
            Comparators.greatest(
                LARGEST_MEMORY_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) ->
                        c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::memoryEstimate))));
  }

  /** Returns the list of components with the largest input sizes. */
  public List<CriticalPathComponent> getLargestInputSizeComponents() {
    return uniqueActions()
        .collect(
            Comparators.greatest(
                LARGEST_INPUT_SIZE_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) -> c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputBytes))));
  }

  /** Returns the list of components with the largest input counts. */
  public List<CriticalPathComponent> getLargestInputCountComponents() {
    return uniqueActions()
        .collect(
            Comparators.greatest(
                LARGEST_INPUT_COUNT_COMPONENTS_SIZE,
                Comparator.comparingLong(
                    (c) -> c.getSpawnMetrics().getMaxNonDuration(0, SpawnMetrics::inputFiles))));
  }

  /** Returns the list of slowest components. */
  public List<CriticalPathComponent> getSlowestComponents() {
    return uniqueActions()
        .collect(
            Comparators.greatest(
                SLOWEST_COMPONENTS_SIZE,
                Comparator.comparingLong(CriticalPathComponent::getElapsedTimeNanos)));
  }

  private Stream<CriticalPathComponent> uniqueActions() {
    return outputArtifactToComponent.entrySet().stream()
        .filter(e -> e.getValue().isPrimaryOutput(e.getKey()))
        .map(Map.Entry::getValue);
  }

  /** Creates a CriticalPathComponent and adds the duration of input discovery and changes phase. */
  @Subscribe
  @AllowConcurrentEvents
  public void discoverInputs(DiscoveredInputsEvent event) throws InterruptedException {
    CriticalPathComponent stats =
        tryAddComponent(createComponent(event.getAction(), event.getStartTimeNanos()));
    stats.addSpawnResult(event.getMetrics(), null, "", /* wasRemote= */ false);
    stats.changePhase();
  }

  /**
   * Record an action that has started to run. If the CriticalPathComponent has not been created,
   * initialize it and then start running.
   *
   * @param event information about the started action
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionStarted(ActionStartedEvent event) throws InterruptedException {
    Action action = event.getAction();
    tryAddComponent(createComponent(action, event.getNanoTimeStart())).startRunning();
  }

  /**
   * Try to add the component to the map of critical path components. If there is an existing
   * component for its primary output it uses that to update the rest of the outputs.
   *
   * @return The component to be used for updating the time stats.
   */
  @SuppressWarnings("ReferenceEquality")
  private CriticalPathComponent tryAddComponent(CriticalPathComponent newComponent)
      throws InterruptedException {
    Action newAction = newComponent.getAction();
    Artifact primaryOutput = newAction.getPrimaryOutput();
    CriticalPathComponent storedComponent =
        outputArtifactToComponent.putIfAbsent(primaryOutput, newComponent);
    if (storedComponent != null) {
      Action oldAction = storedComponent.getAction();
      // TODO(b/120663721) Replace this fragile reference equality check with something principled.
      if (oldAction != newAction && !Actions.canBeShared(actionKeyContext, newAction, oldAction)) {
        throw new IllegalStateException(
            "Duplicate output artifact found for unsharable actions."
                + "This can happen if a previous event registered the action.\n"
                + "Old action: "
                + oldAction
                + "\n\nNew action: "
                + newAction
                + "\n\nArtifact: "
                + primaryOutput
                + "\n");
      }
    } else {
      storedComponent = newComponent;
    }
    // Try to insert the existing component for the rest of the outputs even if we failed to be
    // the ones inserting the component so that at the end of this method we guarantee that all the
    // outputs have a component.
    for (Artifact output : newAction.getOutputs()) {
      if (output == primaryOutput) {
        continue;
      }
      CriticalPathComponent old = outputArtifactToComponent.putIfAbsent(output, storedComponent);
      // If two actions run concurrently maybe we find a component by primary output but we are
      // the first updating the rest of the outputs.
      Preconditions.checkState(
          old == null || old == storedComponent, "Inconsistent state for %s", newAction);
    }
    return storedComponent;
  }

  /**
   * Record an action that was not executed because it was in the (disk) cache. This is needed so
   * that we can calculate correctly the dependencies tree if we have some cached actions in the
   * middle of the critical path.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionCached(CachedActionEvent event) throws InterruptedException {
    Action action = event.getAction();
    CriticalPathComponent component =
        tryAddComponent(createComponent(action, event.getNanoTimeStart()));
    finalizeActionStat(
        event.getNanoTimeStart(), event.getNanoTimeFinish(), action, component, "action cache hit");
  }

  /**
   * Records the elapsed time stats for the action. For each input artifact, it finds the real
   * dependent artifacts and records the critical path stats.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionComplete(ActionCompletionEvent event) throws InterruptedException {
    Action action = event.getAction();
    CriticalPathComponent component =
        Preconditions.checkNotNull(
            outputArtifactToComponent.get(action.getPrimaryOutput()), action);
    finalizeActionStat(
        event.getRelativeActionStartTimeNanos(), event.getFinishTimeNanos(), action, component, "");
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionChangePruned(ActionChangePrunedEvent event) throws InterruptedException {
    if (graph == null) {
      return;
    }

    var actionLookupData = event.actionLookupData();
    if (!(Actions.getAction(graph, actionLookupData) instanceof Action action)) {
      return;
    }

    var component = tryAddComponent(createComponent(action, event.finishTimeNanos()));
    finalizeActionStat(
        event.finishTimeNanos(), event.finishTimeNanos(), action, component, "change pruned");
  }

  /**
   * Record that the failed rewound action is no longer running. The action may or may not start
   * again later.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void actionRewound(ActionRewoundEvent event) {
    Action action = event.getFailedRewoundAction();
    CriticalPathComponent component =
        Preconditions.checkNotNull(outputArtifactToComponent.get(action.getPrimaryOutput()));
    component.finishActionExecution(
        event.getRelativeActionStartTimeNanos(),
        event.getRelativeActionFinishTimeNanos(),
        "action rewound");
  }

  /** Maximum critical path component found during the build. */
  CriticalPathComponent getMaxCriticalPath() {
    return maxCriticalPath.get();
  }

  private void finalizeActionStat(
      long startTimeNanos,
      long finishTimeNanos,
      Action action,
      CriticalPathComponent component,
      String finalizeReason) {
    for (Artifact input : action.getInputs().toList()) {
      addArtifactDependency(component, input, finishTimeNanos);
    }
    if (Duration.ofNanos(finishTimeNanos - startTimeNanos).compareTo(Duration.ofMillis(-5)) < 0) {
      // See note in {@link Clock#nanoTime} about non increasing subsequent #nanoTime calls.
      logger.atWarning().withStackTrace(StackSize.MEDIUM).log(
          "Negative duration time for [%s] %s with start: %s, finish: %s.",
          action.getMnemonic(), action.getPrimaryOutput(), startTimeNanos, finishTimeNanos);
    }
    component.finishActionExecution(startTimeNanos, finishTimeNanos, finalizeReason);
    maxCriticalPath.accumulateAndGet(component, SELECT_LONGER_COMPONENT);
  }

  /** If "input" is a generated artifact, link its critical path to the one we're building. */
  private void addArtifactDependency(
      CriticalPathComponent actionStats, Artifact input, long componentFinishNanos) {
    CriticalPathComponent depComponent = outputArtifactToComponent.get(input);

    // Typically, the dep component should already be finished since its output was used as an input
    // for a just-completed action. However, we tolerate it still running for (a) action rewinding
    // and (b) the rare case that an action depending on a previously-cached shared action sees a
    // different shared action that is in the midst of being an action cache hit.
    if (depComponent != null && !depComponent.isRunning()) {
      actionStats.addDepInfo(depComponent, componentFinishNanos);
    }
  }
}
