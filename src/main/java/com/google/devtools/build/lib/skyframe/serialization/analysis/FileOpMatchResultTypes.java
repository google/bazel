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

import static com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch.NO_MATCH_RESULT;

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import java.util.function.BiConsumer;

/** Container for {@link DeltaDepotValidator#matches(FileOpDependency, int)} result types. */
final class FileOpMatchResultTypes {

  /** {@link DeltaDepotValidator#matches(FileOpDependency, int)} result type. */
  sealed interface FileOpMatchResultOrFuture permits FileOpMatchResult, FutureFileOpMatchResult {}

  /** An immediate result. */
  sealed interface FileOpMatchResult extends FileOpMatchResultOrFuture
      permits NoMatch, FileOpMatch {
    static FileOpMatchResult create(int version) {
      return version == VersionedChanges.NO_MATCH ? NO_MATCH_RESULT : new FileOpMatch(version);
    }

    int version();
  }

  /** A result signalling a match. */
  record FileOpMatch(int version) implements FileOpMatchResult {}

  /** A future result. */
  static final class FutureFileOpMatchResult
      extends SettableFutureKeyedValue<
          FileOpMatchResultTypes.FutureFileOpMatchResult, FileOpDependency, FileOpMatchResult>
      implements FileOpMatchResultOrFuture {
    FutureFileOpMatchResult(
        FileOpDependency key, BiConsumer<FileOpDependency, FileOpMatchResult> consumer) {
      super(key, consumer);
    }
  }

  private FileOpMatchResultTypes() {}
}
