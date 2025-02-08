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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResultOrFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Matches a set of changed files (represented by {@link VersionedChanges}) against the file system
 * dependencies of cached values to determine cache hits and misses.
 *
 * <p>This class compares file and directory listing changes with the dependencies of a cached value
 * to determine if the cached value is still valid.
 *
 * <ul>
 *   <li>A {@link NoMatch} result indicates a cache hit (the cached value is still valid).
 *   <li>A match result indicates a cache miss (the cached value is invalidated by changes).
 *   <li>Instances of this class cache match results and should be scoped to a specific client
 *       (e.g., a build) for correctness.
 * </ul>
 *
 * <p>This is driven by the {@link #matches} method, taking the following parameters.
 *
 * <ul>
 *   <li><b>{@code validityHorizon}</b>: Represents the last known version where a cached value's
 *       dependencies were valid (see {@link VersionedChanges} for a detailed definition).
 *   <li><b>{@link FileSystemDependencies}</b>: Represents files and directory listings that a
 *       cached value depends on.
 * </ul>
 *
 * <p><b>Caching and {@code validityHorizon}:</b>
 *
 * <p>The {@code validityHorizon} parameter of the {@link #matches} method plays a crucial role in
 * caching behavior, even though different {@code validityHorizon} values can be used for the same
 * {@link FileSystemDependencies} instance.
 *
 * <p><b>Scenario 1: Shared {@code FileSystemDependencies} nodes, different {@code
 * validityHorizon}s:</b>
 *
 * <ul>
 *   <li>Two different cached values evaluated at different versions might share the same {@link
 *       FileSystemDependencies} nodes if the underlying files haven't changed between those
 *       versions.
 *   <li>In such cases, the {@code matches} method might be called with different {@code
 *       validityHorizon} values for the same {@link FileSystemDependencies} object.
 *   <li>The existence of a newer {@code validityHorizon} (and the fact that the nodes are shared)
 *       implies that no relevant changes occurred between the older and newer versions.
 *   <li>This optimization relies on tracking specific file version numbers rather than just content
 *       hashes.
 * </ul>
 *
 * <p><b>Scenario 2: Stale vs. Up-to-Date Cached Values:</b>
 *
 * <ul>
 *   <li>An old, stale cached value might have overlapping file dependencies with a newer,
 *       up-to-date cached value.
 *   <li>Staleness implies a difference in their {@link FileSystemDependencies} nodes, which are
 *       used as cache keys. This allows for distinct cache entries despite the overlap.
 *   <li>The {@code validityHorizon} is essential here to prevent the up-to-date value from being
 *       incorrectly invalidated by older changes associated with the stale value. It effectively
 *       filters out changes that occurred before the up-to-date value was computed.
 * </ul>
 *
 * <p>In essence, {@code validityHorizon} ensures correctness when dealing with potentially
 * overlapping dependencies and allows for efficient caching by reusing results when possible.
 */
final class VersionedChangesValidator {
  private final FileOpMatchMemoizingLookup fileOpMatches;
  private final NestedMatchMemoizingLookup nestedMatches;

  VersionedChangesValidator(Executor executor, VersionedChanges changes) {
    this.fileOpMatches = new FileOpMatchMemoizingLookup(changes, new ConcurrentHashMap<>());
    this.nestedMatches =
        new NestedMatchMemoizingLookup(executor, fileOpMatches, new ConcurrentHashMap<>());
  }

  /** Changes in the cache reader used for invalidation. */
  VersionedChanges changes() {
    return fileOpMatches.changes();
  }

  /**
   * Determines if there are any matching dependencies in {@link #changes}.
   *
   * <p>The caller must ensure that the matching conditions required by {@link VersionedChanges} are
   * satisfied before calling this method. This may require performing lookups and calling {@link
   * VersionedChanges#registerFileChange} when earlier {@code validityHorizon} values are
   * discovered.
   *
   * @param validityHorizon the latest version where {@code dependency} is known to be valid
   */
  FileOpMatchResultOrFuture matches(FileOpDependency dependency, int validityHorizon) {
    return fileOpMatches.getValueOrFuture(dependency, validityHorizon);
  }

  /**
   * Determines if there are any matching dependencies in {@link #changes}.
   *
   * <p>The caller must ensure that the matching conditions required by {@link VersionedChanges} are
   * satisfied before calling this method.
   *
   * @param validityHorizon the latest version where {@code dependencies} is known to be valid
   */
  NestedMatchResultOrFuture matches(NestedDependencies dependencies, int validityHorizon) {
    return nestedMatches.getValueOrFuture(dependencies, validityHorizon);
  }
}
