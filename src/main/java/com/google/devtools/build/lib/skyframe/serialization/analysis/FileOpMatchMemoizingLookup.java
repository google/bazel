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
import static java.lang.Math.min;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentMap;

/**
 * Matches {@link FileOpDependency} instances representing cached value dependencies against {@link
 * #changes}, containing file system content changes.
 *
 * <p>The {@code validityHorizon} (VH) parameter of {@link #getValueOrFuture} has subtle semantics,
 * but works correctly, even in the presence of multiple overlapping nodes at different versions and
 * VH values. See {@link VersionedChangesValidator} and {@link VersionedChanges} for more details.
 */
final class FileOpMatchMemoizingLookup
    extends AbstractValueOrFutureMap<
        FileOpDependency, FileOpMatchResultOrFuture, FileOpMatchResult, FutureFileOpMatchResult> {
  private final VersionedChanges changes;

  FileOpMatchMemoizingLookup(
      VersionedChanges changes, ConcurrentMap<FileOpDependency, FileOpMatchResultOrFuture> map) {
    super(map, FutureFileOpMatchResult::new, FutureFileOpMatchResult.class);
    this.changes = changes;
  }

  VersionedChanges changes() {
    return changes;
  }

  FileOpMatchResultOrFuture getValueOrFuture(FileOpDependency key, int validityHorizon) {
    FileOpMatchResultOrFuture result = getOrCreateValueForSubclasses(key);
    if (result instanceof FutureFileOpMatchResult future && future.tryTakeOwnership()) {
      try {
        return populateFutureFileOpMatchResult(validityHorizon, future);
      } finally {
        future.verifyComplete();
      }
    }
    return result;
  }

  private FileOpMatchResultOrFuture populateFutureFileOpMatchResult(
      int validityHorizon, FutureFileOpMatchResult ownedFuture) {
    switch (ownedFuture.key()) {
      case FileDependencies file:
        return aggregateAnyAdditionalFileDependencies(
            file.findEarliestMatch(changes, validityHorizon), file, validityHorizon, ownedFuture);
      case ListingDependencies listing:
        // Matches the listing (files inside the directory changed).
        int version = listing.findEarliestMatch(changes, validityHorizon);
        // Then matches the directory itself.
        FileDependencies realDirectory = listing.realDirectory();
        return aggregateAnyAdditionalFileDependencies(
            min(version, realDirectory.findEarliestMatch(changes, validityHorizon)),
            realDirectory,
            validityHorizon,
            ownedFuture);
    }
  }

  private FileOpMatchResultOrFuture aggregateAnyAdditionalFileDependencies(
      int baseVersion,
      FileDependencies file,
      int validityHorizon,
      FutureFileOpMatchResult ownedFuture) {
    if (file.getDependencyCount() == 0) {
      return ownedFuture.completeWith(FileOpMatchResult.create(baseVersion));
    }
    var aggregator = new AggregatingFutureFileOpMatchResult(baseVersion);
    for (int i = 0; i < file.getDependencyCount(); i++) {
      aggregator.addDependency(getValueOrFuture(file.getDependency(i), validityHorizon));
    }
    aggregator.notifyAllDependenciesAdded();
    return ownedFuture.completeWith(aggregator);
  }

  private static final class AggregatingFutureFileOpMatchResult
      extends QuiescingFuture<FileOpMatchResult> implements FutureCallback<FileOpMatchResult> {
    private volatile FileOpMatchResult result;

    private AggregatingFutureFileOpMatchResult(int version) {
      this.result = FileOpMatchResult.create(version);
    }

    private void addDependency(FileOpMatchResultOrFuture resultOrFuture) {
      switch (resultOrFuture) {
        case FileOpMatchResult match:
          updateResult(match);
          break;
        case FutureFileOpMatchResult future:
          increment();
          Futures.addCallback(future, (FutureCallback<FileOpMatchResult>) this, directExecutor());
          break;
      }
    }

    private void notifyAllDependenciesAdded() {
      decrement();
    }

    private void updateResult(FileOpMatchResult newResult) {
      FileOpMatchResult snapshot;
      do {
        snapshot = result;
      } while (newResult.version() < snapshot.version()
          && !RESULT_HANDLE.compareAndSet(this, snapshot, newResult));
    }

    @Override
    protected FileOpMatchResult getValue() {
      return result;
    }

    /**
     * Implementation of {@link FutureCallback<FileOpMatchResult>}.
     *
     * @deprecated only for {@link #addDependency} futures callback processing.
     */
    @Deprecated
    @Override
    public void onSuccess(FileOpMatchResult result) {
      updateResult(result);
      decrement();
    }

    /**
     * Implementation of {@link FutureCallback<FileOpMatchResult>}.
     *
     * @deprecated only for {@link #addDependency} futures callback processing.
     */
    @Deprecated
    @Override
    public void onFailure(Throwable t) {
      notifyException(t);
    }

    private static final VarHandle RESULT_HANDLE;

    static {
      try {
        RESULT_HANDLE =
            MethodHandles.lookup()
                .findVarHandle(
                    AggregatingFutureFileOpMatchResult.class, "result", FileOpMatchResult.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
