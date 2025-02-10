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

/**
 * Union type for {@link FileDependencies}, {@link ListingDependencies} and {@link
 * NestedDependencies}.
 *
 * <p>At a structural level {@link FileDependencies} and {@link ListingDependencies} are very
 * similar. {@link ListingDependencies} could be modeled plainly as {@link FileDependencies}. The
 * crucial difference is that {@link FileDependencies#containsMatch(ImmutableSet<String>)} takes a
 * set of files and {@link ListingDependencies#matchesAnyDirectories(ImmutableSet<String>)} takes a
 * set of directory names so the two types are deliberately separated.
 */
sealed interface FileSystemDependencies
    permits FileSystemDependencies.FileOpDependency, NestedDependencies {
  /** Dependencies, excluding nested dependencies. */
  sealed interface FileOpDependency extends FileSystemDependencies
      permits FileDependencies, ListingDependencies {}
}
