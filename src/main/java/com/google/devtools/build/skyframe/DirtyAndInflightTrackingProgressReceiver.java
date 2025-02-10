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
package com.google.devtools.build.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A delegating {@link InflightTrackingProgressReceiver} that tracks both inflight and dirty keys.
 */
public class DirtyAndInflightTrackingProgressReceiver implements InflightTrackingProgressReceiver {

  protected final EvaluationProgressReceiver progressReceiver;
  private final Set<SkyKey> dirtyKeys = Sets.newConcurrentHashSet();
  private Set<SkyKey> inflightKeys = Sets.newConcurrentHashSet();
  private Set<SkyKey> unsuccessfullyRewoundKeys = Sets.newConcurrentHashSet();

  public DirtyAndInflightTrackingProgressReceiver(EvaluationProgressReceiver progressReceiver) {
    this.progressReceiver = checkNotNull(progressReceiver);
  }

  @Override
  public final void injected(SkyKey skyKey) {
    // This node was never evaluated, but is now clean and need not be re-evaluated.
    inflightKeys.remove(skyKey);
    removeFromDirtySet(skyKey);
  }

  @Override
  public void dirtied(SkyKey skyKey, DirtyType dirtyType) {
    progressReceiver.dirtied(skyKey, dirtyType);
    addToDirtySet(skyKey, dirtyType);
  }

  @Override
  public final void deleted(SkyKey skyKey) {
    progressReceiver.deleted(skyKey);
    // This key was removed from the graph, so no longer needs to be marked as dirty.
    removeFromDirtySet(skyKey);
  }

  @Override
  public final void enqueueing(SkyKey skyKey) {
    enqueueing(skyKey, false);
  }

  private void enqueueing(SkyKey skyKey, boolean afterError) {
    // We unconditionally add the key to the set of in-flight nodes even if evaluation is never
    // scheduled, because we still want to remove the previously created NodeEntry from the graph.
    // Otherwise we would leave the graph in a weird state (wasteful garbage in the best case and
    // inconsistent in the worst case).
    boolean newlyEnqueued = inflightKeys.add(skyKey);
    if (newlyEnqueued) {
      // All nodes enqueued for evaluation will be either verified clean, re-evaluated, or cleaned
      // up after being in-flight when an error happens in nokeep_going mode or in the event of an
      // interrupt. In any of these cases, they won't be dirty anymore. Note that we don't remove
      // from unsuccessfullyRewoundKeys here - that is only done when the key completes
      // successfully.
      dirtyKeys.remove(skyKey);
      if (!afterError) {
        // Only tell the external listener the node was enqueued if no there was neither an error
        // or interrupt.
        progressReceiver.enqueueing(skyKey);
      }
    }
  }

  @Override
  public void changePruned(SkyKey skyKey) {
    progressReceiver.changePruned(skyKey);
  }

  /**
   * Called when a node was requested to be enqueued but wasn't because either an interrupt or an
   * error (in nokeep_going mode) had occurred.
   */
  @Override
  public final void enqueueAfterError(SkyKey skyKey) {
    enqueueing(skyKey, true);
  }

  @Override
  public final void stateStarting(SkyKey skyKey, NodeState nodeState) {
    progressReceiver.stateStarting(skyKey, nodeState);
  }

  @Override
  public void stateEnding(SkyKey skyKey, NodeState nodeState) {
    progressReceiver.stateEnding(skyKey, nodeState);
  }

  @Override
  public void evaluated(
      SkyKey skyKey,
      EvaluationState state,
      @Nullable SkyValue newValue,
      @Nullable ErrorInfo newError,
      @Nullable GroupedDeps directDeps) {
    progressReceiver.evaluated(skyKey, state, newValue, newError, directDeps);

    // This key was either built or marked clean, so we can remove it from both the dirty and
    // inflight nodes.
    inflightKeys.remove(skyKey);

    if (state.succeeded()) {
      removeFromDirtySet(skyKey);
    } else {
      // Leave unsuccessful keys in unsuccessfullyRewoundKeys. Only remove them from dirtyKeys.
      dirtyKeys.remove(skyKey);
    }
  }

  /** Returns if the key is enqueued for evaluation. */
  @Override
  public final boolean isInflight(SkyKey skyKey) {
    return inflightKeys.contains(skyKey);
  }

  @Override
  public final void removeFromInflight(SkyKey skyKey) {
    inflightKeys.remove(skyKey);
  }

  @Override
  public final Set<SkyKey> getAndClearInflightKeys() {
    Set<SkyKey> keys = inflightKeys;
    inflightKeys = Sets.newConcurrentHashSet();
    return keys;
  }

  /**
   * Returns the set of all keys that were {@linkplain DirtyType#REWIND rewound} but did not
   * complete successfully, and resets the set to empty.
   *
   * <p>The returned set includes keys that were rewound and were either:
   *
   * <ul>
   *   <li>not yet enqueued
   *   <li>enqueued but not evaluated
   *   <li>evaluated to an error
   * </ul>
   */
  public final Set<SkyKey> getAndClearUnsuccessfullyRewoundKeys() {
    Set<SkyKey> keys = unsuccessfullyRewoundKeys;
    unsuccessfullyRewoundKeys = Sets.newConcurrentHashSet();
    return keys;
  }

  /**
   * Returns the set of all dirty keys that have not been enqueued. This is useful for garbage
   * collection, where we would not want to remove dirty nodes that are needed for evaluation (in
   * the downward transitive closure of the set of the evaluation's top level nodes).
   */
  final ImmutableSet<SkyKey> getUnenqueuedDirtyKeys() {
    return ImmutableSet.copyOf(dirtyKeys);
  }

  private void addToDirtySet(SkyKey skyKey, DirtyType dirtyType) {
    if (dirtyType == DirtyType.REWIND) {
      unsuccessfullyRewoundKeys.add(skyKey);
    } else {
      dirtyKeys.add(skyKey);
    }
  }

  private void removeFromDirtySet(SkyKey skyKey) {
    // A key will never be present in both sets because EvaluationProgressReceiver#dirtied is only
    // called after successful NodeEntry#markDirty calls, i.e. a call that transitioned the node
    // from done to dirty.
    if (!dirtyKeys.remove(skyKey)) {
      unsuccessfullyRewoundKeys.remove(skyKey);
    }
  }
}
