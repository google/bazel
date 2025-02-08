// Copyright 2025 The Bazel Authors. All rights reserved.
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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.createNestedMatchResult;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch.NO_MATCH_RESULT;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.AnalysisAndSourceMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.AnalysisMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.FutureNestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.SourceMatch;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Computes matching versions for {@link NestedDependencies} with memoization.
 *
 * <p>Uses a backing {@link FileOpMatchMemoizingLookup} instance for any {@link FileOpDependency}
 * lookups.
 *
 * <p>The {@code validityHorizon} (VH) parameter of {@link #getValueOrFuture} has subtle semantics,
 * but works correctly, even in the presence of multiple overlapping nodes at different versions and
 * VH values. See {@link VersionedChangesValidator} and {@link VersionedChanges} for more details.
 */
final class NestedMatchMemoizingLookup
    extends AbstractValueOrFutureMap<
        NestedDependencies, NestedMatchResultOrFuture, NestedMatchResult, FutureNestedMatchResult> {
  private final Executor executor;
  private final FileOpMatchMemoizingLookup fileOpMatches;

  NestedMatchMemoizingLookup(
      Executor executor,
      FileOpMatchMemoizingLookup fileOpMatches,
      ConcurrentMap<NestedDependencies, NestedMatchResultOrFuture> map) {
    super(map, FutureNestedMatchResult::new, FutureNestedMatchResult.class);
    this.executor = executor;
    this.fileOpMatches = fileOpMatches;
  }

  NestedMatchResultOrFuture getValueOrFuture(NestedDependencies key, int validityHorizon) {
    NestedMatchResultOrFuture result = getOrCreateValueForSubclasses(key);
    if (result instanceof FutureNestedMatchResult future && future.tryTakeOwnership()) {
      try {
        return populateFutureNestedMatchResult(validityHorizon, future);
      } finally {
        future.verifyComplete();
      }
    }
    return result;
  }

  private NestedMatchResultOrFuture populateFutureNestedMatchResult(
      int validityHorizon, FutureNestedMatchResult ownedFuture) {
    NestedDependencies nested = ownedFuture.key();
    var aggregator = new NestedFutureResultAggregator();
    for (int i = 0; i < nested.analysisDependenciesCount(); i++) {
      switch (nested.getAnalysisDependency(i)) {
        case FileOpDependency dependency:
          aggregator.addAnalysisResultOrFuture(
              fileOpMatches.getValueOrFuture(dependency, validityHorizon));
          break;
        case NestedDependencies child:
          // In a common case, the cache reader sends a single top-level request that traverses the
          // full set of dependencies and waits for that request to complete. Parallelizes
          // recursive traversal of child nodes to avoid being singly-threaded in this scenario.
          aggregator.signalNestedTaskAdded();
          executor.execute(
              () -> {
                switch (getValueOrFuture(child, validityHorizon)) {
                  case NestedMatchResult result:
                    aggregator.addNestedResult(result);
                    aggregator.signalNestedTaskComplete();
                    break;
                  case FutureNestedMatchResult future:
                    // The aggregator decrements when the future completes.
                    aggregator.addFutureNestedMatchResult(future);
                    break;
                }
              });
          break;
      }
    }
    for (int i = 0; i < nested.sourcesCount(); i++) {
      aggregator.addSourceResultOrFuture(
          fileOpMatches.getValueOrFuture(nested.getSource(i), validityHorizon));
    }
    aggregator.notifyAllDependenciesAdded();
    return ownedFuture.completeWith(aggregator);
  }

  private static final class NestedFutureResultAggregator
      extends QuiescingFuture<NestedMatchResult> {
    private volatile int earliestAnalysisMatch = VersionedChanges.NO_MATCH;
    private volatile int earliestSourceMatch = VersionedChanges.NO_MATCH;

    private void addAnalysisResultOrFuture(FileOpMatchResultOrFuture resultOrFuture) {
      switch (resultOrFuture) {
        case FileOpMatchResult result:
          updateAnalysisVersionIfEarlier(result.version());
          break;
        case FutureFileOpMatchResult future:
          increment();
          Futures.addCallback(
              future,
              new ResultCallback<FileOpMatchResult>() {
                @Override
                void processResult(FileOpMatchResult result) {
                  updateAnalysisVersionIfEarlier(result.version());
                }
              },
              directExecutor());
          break;
      }
    }

    private void addSourceResultOrFuture(FileOpMatchResultOrFuture resultOrFuture) {
      switch (resultOrFuture) {
        case FileOpMatchResult result:
          updateSourceVersionIfEarlier(result.version());
          break;
        case FutureFileOpMatchResult future:
          increment();
          Futures.addCallback(
              future,
              new ResultCallback<FileOpMatchResult>() {
                @Override
                void processResult(FileOpMatchResult result) {
                  updateSourceVersionIfEarlier(result.version());
                }
              },
              directExecutor());
          break;
      }
    }

    private void addNestedResult(NestedMatchResult result) {
      switch (result) {
        case NO_MATCH_RESULT:
          break;
        case AnalysisMatch(int version):
          updateAnalysisVersionIfEarlier(version);
          break;
        case SourceMatch(int version):
          updateSourceVersionIfEarlier(version);
          break;
        case AnalysisAndSourceMatch(int analysisVersion, int sourceVersion):
          updateAnalysisVersionIfEarlier(analysisVersion);
          updateSourceVersionIfEarlier(sourceVersion);
          break;
      }
    }

    private void addFutureNestedMatchResult(FutureNestedMatchResult future) {
      Futures.addCallback(
          future,
          new ResultCallback<NestedMatchResult>() {
            @Override
            void processResult(NestedMatchResult result) {
              addNestedResult(result);
            }
          },
          directExecutor());
    }

    private void signalNestedTaskAdded() {
      increment();
    }

    private void signalNestedTaskComplete() {
      decrement();
    }

    private void notifyAllDependenciesAdded() {
      decrement();
    }

    @Override
    protected NestedMatchResult getValue() {
      return createNestedMatchResult(earliestAnalysisMatch, earliestSourceMatch);
    }

    private void updateAnalysisVersionIfEarlier(int version) {
      int snapshot;
      do {
        snapshot = earliestAnalysisMatch;
      } while (version < snapshot && !ANALYSIS_MATCH_HANDLE.compareAndSet(this, snapshot, version));
    }

    private void updateSourceVersionIfEarlier(int version) {
      int snapshot;
      do {
        snapshot = earliestSourceMatch;
      } while (version < snapshot && !SOURCE_MATCH_HANDLE.compareAndSet(this, snapshot, version));
    }

    /** {@link FutureCallback} implementation that includes common future handling behavior. */
    private abstract class ResultCallback<T> implements FutureCallback<T> {
      abstract void processResult(T result);

      @Override
      public final void onSuccess(T result) {
        processResult(result);
        decrement();
      }

      @Override
      public final void onFailure(Throwable t) {
        notifyException(t);
      }
    }

    private static final VarHandle ANALYSIS_MATCH_HANDLE;
    private static final VarHandle SOURCE_MATCH_HANDLE;

    static {
      try {
        ANALYSIS_MATCH_HANDLE =
            MethodHandles.lookup()
                .findVarHandle(
                    NestedFutureResultAggregator.class, "earliestAnalysisMatch", int.class);
        SOURCE_MATCH_HANDLE =
            MethodHandles.lookup()
                .findVarHandle(
                    NestedFutureResultAggregator.class, "earliestSourceMatch", int.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
