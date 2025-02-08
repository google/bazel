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

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Stores file and listing changes and versions for a given reader.
 *
 * <p>Some brief definitions.
 *
 * <ul>
 *   <li>{@link FileSystemDependencies} <b>node</b>: a (nested) set of files and listings
 *       representing the dependencies of a cached value.
 *   <li><b>client version (VC)</b>: synced version of the client performing cache lookups.
 *   <li><b>max transitive source version (MTSV)</b>: the canonical version of a <i>node</i> equal
 *       to the first version at which a node obtains its current value.
 *   <li><b>validity horizon (VH)</b>: the last version where the <i>node</i> is known to be valid.
 * </ul>
 *
 * <p>VC is per reader, while MTSV and VH are both per node.
 *
 * <p>Before calling {@link #matchFileChange} or {@link #matchListingChange}, the client <b>must</b>
 * ensure the following:
 *
 * <ul>
 *   <li>All client changes are registered via the {@code clientFileChanges} constructor parameter.
 *   <li>All depot changes in the range (VH, VC] have been registered with {@link
 *       #registerFileChange}. (This range <i>excludes</i> VH and includes VC).
 * </ul>
 *
 * <p>Note that if VH ≥ VC, (VH, VC] is empty and no depot changes need to registered. Only changes
 * in the client must be considered. A special case is when the client is synced to the same version
 * as the writer of the cache entry. Then VH = VC and the range is empty.
 *
 * <h2>Node Validity Range</h2>
 *
 * <p>Every {@link FileSystemDependencies} node has a <i>dynamic</i> range of validity. The lower
 * bound is the node's <b>maximum transitive source version (MTSV)</b>, which is the maximum version
 * at which any of the node's dependencies changed. MTSVs are canonical.
 *
 * <p>While the lower bound is uniquely determined, the upper bound may be unknown. For example, the
 * invalidating change may not have occurred yet. Instead, there is an increasing <b>validity
 * horizon (VH)</b>, initially equal to MTSV. It is determined by lazily probing for invalidating
 * changes. If a probe finds no invalidating changes, VH increases to the probed version. Otherwise,
 * a specific invalidating change number can be identified, which is the value returned by {@link
 * #matchFileChange} or {@link #matchListingChange}. This invalidating change number can be used to
 * update VH, marking it closed.
 *
 * <p>The validity range [MTSV, VH] <i>includes</i> its endpoints.
 */
final class VersionedChanges {
  /**
   * Sentinel value indicating that there was no match.
   *
   * <p>Most of the versioning logic here aggregates versions by taking the minimum. This choice of
   * sentinel value makes it always aggregate out when combined with non-sentinel values.
   */
  static final int NO_MATCH = Integer.MAX_VALUE;

  /**
   * Sentinel version indicating a change in the client.
   *
   * <p>This high value makes client changes a lower priority for match than checked-in changes.
   */
  static final int CLIENT_CHANGE = Integer.MAX_VALUE - 1;

  // TODO: b/364831651 - if sorted int[] does not scale, it can be replaced with TreeSet<Integer>
  // but we expect the number of changes per entry to be small.
  private final ConcurrentHashMap<String, int[]> fileChanges = new ConcurrentHashMap<>();

  /** Contains all the parent directories of {@link fileChanges} for efficient lookup. */
  private final ConcurrentHashMap<String, int[]> listingChanges = new ConcurrentHashMap<>();

  VersionedChanges(Iterable<String> clientFileChanges) {
    for (var change : clientFileChanges) {
      registerFileChange(change, CLIENT_CHANGE);
    }
  }

  /**
   * Checks for a change to {@code path} with at least version {@code validityHorizon}.
   *
   * <p>This method is thread safe.
   *
   * @param validityHorizon the VH (see class description for more details) of the current node
   *     being checked for invalidating changes.
   * @return the smallest version greater than {@code validityHorizon} if a match is found and
   *     {@link #NO_MATCH} otherwise. Returns {@link #CLIENT_CHANGE} if a change in the client is
   *     the only match.
   */
  int matchFileChange(String path, int validityHorizon) {
    // Finds a version beyond the known validity horizon.
    return findMinimumVersionGreaterThanOrEqualTo(fileChanges.get(path), validityHorizon + 1);
  }

  /**
   * Checks for a change to a listing of {@code path} with at least version {@code validityHorizon}.
   *
   * <p>Parameters and return value have the same meaning as {@link #matchFileChange}, but this
   * method is for listings instead of files.
   *
   * <p>This method is thread safe.
   */
  int matchListingChange(String path, int validityHorizon) {
    // Finds a version beyond the known validity horizon.
    return findMinimumVersionGreaterThanOrEqualTo(listingChanges.get(path), validityHorizon + 1);
  }

  /**
   * Adds a file and change, and induces a corresponding listing change.
   *
   * <p>It's safe to call this concurrently with {@link matchFileChange} and {@link
   * matchListingChange}. However, concurrent calls to this method for the same path are not safe.
   *
   * <p>This is sufficient for singly-threaded updates.
   */
  void registerFileChange(String path, int version) {
    insertChange(path, version, fileChanges);
    insertChange(getParentDirectory(path), version, listingChanges);
  }

  @VisibleForTesting
  static int findMinimumVersionGreaterThanOrEqualTo(@Nullable int[] versions, int minVersion) {
    if (versions == null) {
      return NO_MATCH;
    }

    int index = Arrays.binarySearch(versions, minVersion);
    if (index >= 0) {
      return minVersion; // Exact match.
    }

    // If not found, binarySearch returns (-(insertion point) - 1), where the insertion point is
    // the index of the first element greater than the key.
    //
    // For example, if there is no exact match for `3` in `[1,2,4,5]`, then the insertion point is
    // 2 (index of the element `4`), and `binarySearch` will return `-(2)-1`. Given that we want to
    // return the minimum version greater than `minVersion`, we need to return the version at the
    // insertion point.
    index = -index - 1;
    if (index >= versions.length) {
      return NO_MATCH; // All versions earlier than minVersion.
    }
    return versions[index];
  }

  @VisibleForTesting
  static void insertChange(String path, int version, ConcurrentHashMap<String, int[]> changes) {
    int[] versions = changes.get(path);
    if (versions == null) {
      versions = new int[] {version};
    } else {
      int[] newVersions = insertSorted(versions, version);
      if (newVersions == versions) {
        return; // unchanged
      }
      versions = newVersions;
    }
    changes.put(path, versions);
  }

  @VisibleForTesting
  static int[] insertSorted(int[] versions, int newVersion) {
    int index = Arrays.binarySearch(versions, newVersion);
    if (index >= 0) {
      return versions; // Duplicate. Returns the original.
    }

    // If not found, binarySearch returns (-(insertion point) - 1). This calculates the correct
    // insertion point.
    index = -index - 1;

    int[] newVersions = new int[versions.length + 1];
    System.arraycopy(versions, 0, newVersions, 0, index);
    newVersions[index] = newVersion;
    System.arraycopy(versions, index, newVersions, index + 1, versions.length - index);
    return newVersions;
  }

  @VisibleForTesting
  static String getParentDirectory(String path) {
    int directoryEnd = path.lastIndexOf('/');
    if (directoryEnd == -1) {
      return "";
    }
    return path.substring(0, directoryEnd);
  }
}
