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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch.NO_MATCH_RESULT;

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import java.util.function.BiConsumer;

/** Container for {@link DeltaDepotValidator#matches(NestedDependencies, int)} result types. */
final class NestedMatchResultTypes {
  /** {@link DeltaDepotValidator#matches(NestedDependencies, int)} result type. */
  sealed interface NestedMatchResultOrFuture permits NestedMatchResult, FutureNestedMatchResult {}

  /** An immediate match result. */
  sealed interface NestedMatchResult extends NestedMatchResultOrFuture
      permits AnalysisMatch, SourceMatch, AnalysisAndSourceMatch, NoMatch {}

  static NestedMatchResult createNestedMatchResult(int analysisVersion, int sourceVersion) {
    if (analysisVersion <= sourceVersion) {
      // When checking for an analysis match, the source version is irrelevant. When checking for an
      // execution match, the analysis version is included. If the analysis version is less than the
      // source match, then it dominates. In both cases, the source version can be ignored.
      return analysisVersion == VersionedChanges.NO_MATCH
          ? NO_MATCH_RESULT
          : new AnalysisMatch(analysisVersion);
    }
    return analysisVersion == VersionedChanges.NO_MATCH
        ? new SourceMatch(sourceVersion)
        : new AnalysisAndSourceMatch(analysisVersion, sourceVersion);
  }

  /**
   * The delta matched the set of dependencies, meaning a <b>cache miss</b>.
   *
   * @param version the minimum version where the analysis match was observed
   */
  static record AnalysisMatch(int version) implements NestedMatchResult {}

  /**
   * The delta didn't match analysis dependencies, but matched source dependencies, indicating a
   * <b>cache miss</b>.
   *
   * @param sourceVersion the minimum version where the match was observed.
   */
  static record SourceMatch(int sourceVersion) implements NestedMatchResult {}

  /**
   * The delta matched both (analysis) dependencies and source dependencies.
   *
   * <p>{@code sourceVersion} must be <b>less than</b> {@code analysisVersion} for this to apply. An
   * analysis match would already invalidate the corresponding execution value, so if it matches
   * analysis first, the source match is irrelevant.
   *
   * @param analysisVersion the minimum version where an analysis match was observed
   * @param sourceVersion the minimum version where the source match was observed.
   */
  record AnalysisAndSourceMatch(int analysisVersion, int sourceVersion)
      implements NestedMatchResult {
    public AnalysisAndSourceMatch {
      checkArgument(
          sourceVersion < analysisVersion,
          "sourceVersion=%s must be less than analysisVersion=%s",
          sourceVersion,
          analysisVersion);
    }
  }

  /** A future match result. */
  static final class FutureNestedMatchResult
      extends SettableFutureKeyedValue<
          FutureNestedMatchResult, NestedDependencies, NestedMatchResult>
      implements NestedMatchResultOrFuture {
    FutureNestedMatchResult(
        NestedDependencies key, BiConsumer<NestedDependencies, NestedMatchResult> consumer) {
      super(key, consumer);
    }
  }

  private NestedMatchResultTypes() {}
}
