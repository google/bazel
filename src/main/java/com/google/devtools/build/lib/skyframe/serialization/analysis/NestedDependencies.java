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
import static com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;
import java.util.Collection;

/**
 * A representation of a recursively composable set of {@link FileSystemDependencies}.
 *
 * <p>This corresponds to a previously serialized {@link
 * com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes} instance, but this
 * implementation is mostly decoupled from Bazel code.
 */
final class NestedDependencies
    implements FileSystemDependencies, FileDependencyDeserializer.NestedDependenciesOrFuture {
  // While formally possible, we don't anticipate analysisDependencies being empty often. `sources`
  // could be frequently empty.
  static final FileDependencies[] EMPTY_SOURCES = new FileDependencies[0];

  private final FileSystemDependencies[] analysisDependencies;
  private final FileDependencies[] sources;

  NestedDependencies(FileSystemDependencies[] analysisDependencies, FileDependencies[] sources) {
    checkArgument(
        analysisDependencies.length >= 1 || sources.length >= 1,
        "analysisDependencies and sources both empty");
    this.analysisDependencies = analysisDependencies;
    this.sources = sources;
  }

  NestedDependencies(
      Collection<? extends FileSystemDependencies> analysisDependencies,
      Collection<FileDependencies> sources) {
    this(
        analysisDependencies.toArray(FileSystemDependencies[]::new),
        sources.toArray(FileDependencies[]::new));
  }

  int analysisDependenciesCount() {
    return analysisDependencies.length;
  }

  FileSystemDependencies getAnalysisDependency(int index) {
    return analysisDependencies[index];
  }

  int sourcesCount() {
    return sources.length;
  }

  FileDependencies getSource(int index) {
    return sources[index];
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("analysisDependencies", Arrays.asList(analysisDependencies))
        .add("sources", Arrays.asList(sources))
        .toString();
  }
}
