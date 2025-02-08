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

package com.google.devtools.build.lib.skyframe;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MapBasedActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.Sharder;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An artifact conflict finder used in noskymeld mode. */
class ArtifactConflictFinder {
  static final Precomputed<ImmutableMap<ActionAnalysisMetadata, ActionConflictException>>
      ACTION_CONFLICTS = new Precomputed<>("action_conflicts");
  // Action graph construction is CPU-bound.
  static final int NUM_JOBS = Runtime.getRuntime().availableProcessors();

  private ArtifactConflictFinder() {}

  /**
   * Find conflicts between generated artifacts. There are two ways to have conflicts. First, if two
   * (unshareable) actions generate the same output artifact, this will result in an {@link
   * ActionConflictException}. Second, if one action generates an artifact whose path is a prefix of
   * another artifact's path, those two artifacts cannot exist simultaneously in the output tree.
   * This causes an {@link ActionConflictException}.
   *
   * <p>This method must be called if a new action was added to the graph this build, so whenever a
   * new configured target was analyzed this build. It is somewhat expensive (~1s range for a medium
   * build as of 2014), so it should only be called when necessary.
   */
  static ActionConflictsAndStats findAndStoreArtifactConflicts(
      Sharder<ActionLookupValue> actionLookupValues,
      int actionCount,
      ActionKeyContext actionKeyContext)
      throws InterruptedException {
    ConcurrentMap<ActionAnalysisMetadata, ActionConflictException> temporaryBadActionMap =
        new ConcurrentHashMap<>();

    // Use the action count to presize - all actions have at least one output artifact.
    MapBasedActionGraph actionGraph = new MapBasedActionGraph(actionKeyContext, actionCount);
    List<Artifact> artifacts = new ArrayList<>(actionCount);

    constructActionGraphAndArtifactList(
        actionGraph,
        Collections.synchronizedList(artifacts),
        actionLookupValues,
        temporaryBadActionMap);

    Map<ActionAnalysisMetadata, ActionConflictException> actionsWithArtifactPrefixConflict =
        Actions.findArtifactPrefixConflicts(actionGraph, artifacts);
    for (Map.Entry<ActionAnalysisMetadata, ActionConflictException> actionExceptionPair :
        actionsWithArtifactPrefixConflict.entrySet()) {
      temporaryBadActionMap.put(actionExceptionPair.getKey(), actionExceptionPair.getValue());
    }
    return ActionConflictsAndStats.create(
        ImmutableMap.copyOf(temporaryBadActionMap),
        actionGraph.getSize());
  }

  /**
   * Simultaneously construct an action graph for all the actions in Skyframe and a map from {@link
   * com.google.devtools.build.lib.vfs.PathFragment}s to their respective {@link Artifact}s. We do
   * this in a threadpool to save around 1.5 seconds on a mid-sized build versus a single-threaded
   * operation.
   */
  private static void constructActionGraphAndArtifactList(
      MutableActionGraph actionGraph,
      List<Artifact> artifacts,
      Sharder<ActionLookupValue> actionShards,
      ConcurrentMap<ActionAnalysisMetadata, ActionConflictException> badActionMap)
      throws InterruptedException {
    ExecutorService executor =
        Executors.newFixedThreadPool(
            NUM_JOBS,
            new ThreadFactoryBuilder().setNameFormat("ActionLookupValue Processor %d").build());
    for (List<ActionLookupValue> shard : actionShards) {
      executor.execute(() -> actionRegistration(shard, actionGraph, artifacts, badActionMap));
    }
    if (ExecutorUtil.interruptibleShutdown(executor)) {
      throw new InterruptedException();
    }
  }

  private static void actionRegistration(
      List<ActionLookupValue> values,
      MutableActionGraph actionGraph,
      List<Artifact> allArtifacts,
      ConcurrentMap<ActionAnalysisMetadata, ActionConflictException> badActionMap) {
    // Accumulated and added to the shared list at the end to reduce contention.
    List<Artifact> myArtifacts = new ArrayList<>(values.size());

    for (ActionLookupValue value : values) {
      if (value.getNumActions() == 0) {
        // For remote analysis caching enabled builds with cache hits, deserialized
        // ActionLookupValues do not contain actions.
        //
        // The full check will be delayed to the cache writing build, or any build
        // that doesn't deserialize any remote ActionLookupValues.
        continue;
      }

      for (ActionAnalysisMetadata action : value.getActions()) {
        try {
          actionGraph.registerAction(action);
        } catch (ActionConflictException e) {
          // It may be possible that we detect a conflict for the same action more than once, if
          // that action belongs to multiple aspect values. In this case we will harmlessly
          // overwrite the badActionMap entry.
          badActionMap.put(action, e);
          // We skip the rest of the loop, and do not add the path->artifact mapping for this
          // artifact below -- we don't need to check it since this action is already in
          // error.
          continue;
        } catch (InterruptedException e) {
          // Bail.
          Thread.currentThread().interrupt();
          return;
        }
        myArtifacts.addAll(action.getOutputs());
      }
    }

    allArtifacts.addAll(myArtifacts);
  }

  record ActionConflictsAndStats(
      ImmutableMap<ActionAnalysisMetadata, ActionConflictException> conflicts,
      int outputArtifactCount) {
    ActionConflictsAndStats {
      requireNonNull(conflicts, "conflicts");
    }

    static ActionConflictsAndStats create(
        ImmutableMap<ActionAnalysisMetadata, ActionConflictException> conflicts,
        int artifactCount) {
      return new ActionConflictsAndStats(conflicts, artifactCount);
    }
  }
}
