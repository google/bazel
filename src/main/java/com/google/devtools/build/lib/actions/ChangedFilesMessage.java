// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Set;

/**
 * A message sent conveying a set of changed files. This is sent over the event bus if a build is
 * discovered to have changed files. If many files have changed, the set of changed files will be
 * empty, but {@link #changedFileCount} will still return the correct number.
 *
 * @param changedFiles Returns a set with one PathFragment for each file that was changed since the
 *     last build, or an empty set if the set is unknown or would be too big.
 * @param changedFileCount Returns the number of changed files. This will always be the correct
 *     number of files, even when {@link #changedFiles} might return an empty set, because the
 *     actual set would be too big.
 * @param invalidatedFileValueCount Returns the number of {@link FileValue} nodes invalidated in the
 *     build.
 *     <p>This is different from {@link #changedFiles} , in particular a single file change can
 *     result with multiple {@linkplain FileValue FVs} .
 */
public record ChangedFilesMessage(
    ImmutableSet<PathFragment> changedFiles, int changedFileCount, int invalidatedFileValueCount) {
  public ChangedFilesMessage {
    requireNonNull(changedFiles, "changedFiles");
  }

  public static ChangedFilesMessage create(
      Set<PathFragment> changedFiles, int changedFileCount, int invalidatedFileValueCount) {
    return new ChangedFilesMessage(
        ImmutableSet.copyOf(changedFiles), changedFileCount, invalidatedFileValueCount);
  }
}
