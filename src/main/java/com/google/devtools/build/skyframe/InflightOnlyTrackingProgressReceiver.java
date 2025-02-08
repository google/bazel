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

import com.google.common.collect.Sets;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A delegating {@link InflightTrackingProgressReceiver} that tracks inflight keys but not dirty
 * keys.
 *
 * <p>Suitable for non-incremental evaluations or evaluators that do not support deletion of dirty
 * nodes.
 */
public class InflightOnlyTrackingProgressReceiver implements InflightTrackingProgressReceiver {

  protected final EvaluationProgressReceiver progressReceiver;
  private Set<SkyKey> inflightKeys = Sets.newConcurrentHashSet();

  public InflightOnlyTrackingProgressReceiver(EvaluationProgressReceiver progressReceiver) {
    this.progressReceiver = checkNotNull(progressReceiver);
  }

  /** Called when a node is injected into the graph, and not evaluated. */
  @Override
  public final void injected(SkyKey skyKey) {
    // This node was never evaluated, but is now clean and need not be re-evaluated.
    inflightKeys.remove(skyKey);
  }

  @Override
  public final void dirtied(SkyKey skyKey, DirtyType dirtyType) {
    progressReceiver.dirtied(skyKey, dirtyType);
  }

  @Override
  public final void deleted(SkyKey skyKey) {
    progressReceiver.deleted(skyKey);
  }

  @Override
  public final void enqueueing(SkyKey skyKey) {
    if (inflightKeys.add(skyKey)) {
      // Only tell the external listener the node was enqueued if no there was neither an error
      // nor interrupt.
      progressReceiver.enqueueing(skyKey);
    }
  }

  @Override
  public final void enqueueAfterError(SkyKey skyKey) {
    inflightKeys.add(skyKey);
  }

  @Override
  public final void stateStarting(SkyKey skyKey, NodeState nodeState) {
    progressReceiver.stateStarting(skyKey, nodeState);
  }

  @Override
  public final void stateEnding(SkyKey skyKey, NodeState nodeState) {
    progressReceiver.stateEnding(skyKey, nodeState);
  }

  @Override
  public final void evaluated(
      SkyKey skyKey,
      EvaluationState state,
      @Nullable SkyValue newValue,
      @Nullable ErrorInfo newError,
      @Nullable GroupedDeps directDeps) {
    progressReceiver.evaluated(skyKey, state, newValue, newError, directDeps);

    // This key was either built or marked clean, so we can remove it from both the dirty and
    // inflight nodes.
    inflightKeys.remove(skyKey);
  }

  @Override
  public void changePruned(SkyKey skyKey) {
    progressReceiver.changePruned(skyKey);
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

  /** Returns the set of all keys that are enqueued for evaluation, and resets the set to empty. */
  @Override
  @CanIgnoreReturnValue
  public final Set<SkyKey> getAndClearInflightKeys() {
    Set<SkyKey> keys = inflightKeys;
    inflightKeys = Sets.newConcurrentHashSet();
    return keys;
  }
}
