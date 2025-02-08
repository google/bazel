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

import static com.google.common.base.MoreObjects.toStringHelper;

/** Type representing a directory listing operation. */
final class ListingDependencies
    implements FileSystemDependencies.FileOpDependency,
        FileDependencyDeserializer.ListingDependenciesOrFuture {
  private final FileDependencies realDirectory;

  ListingDependencies(FileDependencies realDirectory) {
    this.realDirectory = realDirectory;
  }

  /**
   * Determines if this listing is invalidated by anything in {@code changes}.
   *
   * <p>The caller should ensure the following.
   *
   * <ul>
   *   <li>This listing is known to be valid at {@code validityHorizon} (VH).
   *   <li>All changes over the range {@code (VH, VC])} are registered with {@code changes} before
   *       calling this method. (VC is the synced version of the cache reader.)
   * </ul>
   *
   * <p>See description of {@link VersionedChanges} for more details.
   *
   * @return the earliest version where a matching (invalidating) change is identified, otherwise
   *     {@link VersionedChanges#NO_MATCH}.
   */
  int findEarliestMatch(VersionedChanges changes, int validityHorizon) {
    return changes.matchListingChange(realDirectory.resolvedPath(), validityHorizon);
  }

  FileDependencies realDirectory() {
    return realDirectory;
  }

  @Override
  public String toString() {
    return toStringHelper(this).add("realDirectory", realDirectory).toString();
  }
}
