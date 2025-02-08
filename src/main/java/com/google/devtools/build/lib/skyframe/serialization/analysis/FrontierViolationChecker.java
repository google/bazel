// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.skyframe.SkyfocusOptions.FrontierViolationCheck.DISABLED_FOR_TESTING;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Skyfocus;
import com.google.devtools.build.lib.server.FailureDetails.Skyfocus.Code;
import com.google.devtools.build.lib.skyframe.SkyfocusOptions.FrontierViolationCheck;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider.DisabledDependenciesProvider;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.TestType;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A remote analysis caching frontier checker to enforce that changes in the client are only within
 * the active directories.
 */
public final class FrontierViolationChecker {

  /** Accumulator of cache hit counts that persists between invocations. */
  private static final AtomicInteger accumulatedCacheHits = new AtomicInteger(0);

  public static final String INVALIDATION_MESSAGE =
      "Invalidated downloaded in-memory state affected by remote analysis caching.";
  private static final String DETECTION_MESSAGE =
      "Detected filesystem changes outside of the target's project directories.";
  public static final String WARNING_MESSAGE =
      DETECTION_MESSAGE + " Remote analysis caching will be disabled. The filesystem changes are: ";
  public static final String STRICT_MESSAGE =
      DETECTION_MESSAGE
          + " Use --experimental_frontier_violation_check=warn or revert these"
          + " filesystem changes to continue: ";

  private FrontierViolationChecker() {}

  /**
   * Checks that all client-reported filesystem changes are located within the active directories.
   *
   * <p>For warn mode, if there are changes beneath the project frontier, disables and invalidates
   * (deletes) SkyValues that will be affected by them.
   *
   * <p>For strict mode, prevent the build from proceeding until the violation is reverted.
   */
  public static RemoteAnalysisCachingDependenciesProvider check(
      RemoteAnalysisCachingDependenciesProvider provider,
      FrontierViolationCheck check,
      EventHandler eventHandler,
      MemoizingEvaluator evaluator,
      String productName)
      throws AbruptExitException {
    Preconditions.checkArgument(provider.mode().requiresBackendConnectivity());

    if (check == DISABLED_FOR_TESTING) {
      checkState(TestType.isInTest());
      return provider;
    }

    var modifiedFileSet = provider.getDiffFromEvaluatingVersion();
    if (modifiedFileSet.treatEverythingAsModified()) {
      eventHandler.handle(
          Event.warn(
              "Detecting precise filesystem changes in your workspace is not supported, so remote"
                  + " analysis caching will be disabled."));
      markSerializableAnalysisAndExecutionPhaseKeysForDeletion(evaluator, eventHandler);
      return DisabledDependenciesProvider.INSTANCE;
    }

    Set<String> violations = new TreeSet<>();
    for (PathFragment modified : modifiedFileSet.modifiedSourceFiles()) {
      if (modified.segmentCount() == 1 && modified.getPathString().startsWith(productName + "-")) {
        // hacky way to bypass checks for workspace convenience symlinks
        continue;
      }
      if (!provider.withinActiveDirectories(
          PackageIdentifier.createInMainRepo(modified.getParentDirectory()))) {
        violations.add(modified.getPathString());
      }
    }

    if (violations.isEmpty()) {
      return provider;
    }

    int maxViolationsToPrint = 5;
    String violationsString =
        Joiner.on(", ").join(Iterables.limit(violations, maxViolationsToPrint));
    if (violations.size() > maxViolationsToPrint) {
      violationsString +=
          String.format(
              " and %s more (omitted to avoid spam).", violations.size() - maxViolationsToPrint);
    }
    return switch (check) {
      case WARN -> {
        eventHandler.handle(Event.warn(WARNING_MESSAGE + violationsString));
        markSerializableAnalysisAndExecutionPhaseKeysForDeletion(evaluator, eventHandler);
        yield DisabledDependenciesProvider.INSTANCE;
      }
      case STRICT -> {
        throw new AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(STRICT_MESSAGE + violationsString)
                    .setSkyfocus(Skyfocus.newBuilder().setCode(Code.NON_WORKING_SET_CHANGE).build())
                    .build()));
      }
      case DISABLED_FOR_TESTING -> throw new IllegalStateException("should have returned earlier.");
    };
  }

  public static void accumulateCacheHitsAcrossInvocations(
      RemoteAnalysisCachingEventListener listener) {
    accumulatedCacheHits.getAndAdd(listener.getAnalysisNodeCacheHits());
    accumulatedCacheHits.getAndAdd(listener.getExecutionNodeCacheHits());
  }

  /**
   * Marks all serializable analysis and execution phase nodes for deletion.
   *
   * <p>Triggers only when the number of tracked cache hits is non-zero.
   *
   * <p>These nodes and their UTC will be deleted before the start of the next Skyframe evaluation.
   */
  private static void markSerializableAnalysisAndExecutionPhaseKeysForDeletion(
      MemoizingEvaluator evaluator, EventHandler eventHandler) {
    if (accumulatedCacheHits.get() == 0) {
      return;
    }

    // Calling #invalidate is not supported for HERMETIC SkyFunctions, so we'd have to delete these
    // nodes instead.
    evaluator.delete(
        key ->
            switch (key) {
              case ActionLookupKey unused -> true;
              case ActionLookupData unused -> true;
              case Artifact unused -> true;
              default -> false;
            });
    accumulatedCacheHits.set(0);
    eventHandler.handle(Event.warn(INVALIDATION_MESSAGE));
  }
}
