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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.createNestedMatchResult;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch.NO_MATCH_RESULT;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges.NO_MATCH;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.AnalysisAndSourceMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.AnalysisMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.FutureNestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.SourceMatch;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class NestedMatchMemoizingLookupTest {
  private static final int THREAD_COUNT = 10;

  private final VersionedChanges changes = new VersionedChanges(ImmutableList.of());
  private final FileOpMatchMemoizingLookup fileOpMatches =
      new FileOpMatchMemoizingLookup(changes, new ConcurrentHashMap<>());
  private final NestedMatchMemoizingLookup lookup =
      new NestedMatchMemoizingLookup(
          new ForkJoinPool(THREAD_COUNT), fileOpMatches, new ConcurrentHashMap<>());

  @Test
  public void matchingNested_inRangeValidityHorizon_matches() {
    changes.registerFileChange("abc/def", 100);

    var key = createNestedDependencies(FileDependencies.builder("abc/def").build());
    assertThat(getLookupResult(key, 99)).isEqualTo(new AnalysisMatch(100));
  }

  @Test
  public void matchingNested_outOfRangeValidityHorizon_doesNotMatch() {
    changes.registerFileChange("abc/def", 100);

    var key = createNestedDependencies(FileDependencies.builder("abc/def").build());
    assertThat(getLookupResult(key, 100)).isEqualTo(NO_MATCH_RESULT);
  }

  @Test
  @TestParameters("{validityHorizon: 97, expectedAnalysisMatch: 99, expectedSourceMatch: 98}")
  @TestParameters(
      "{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
  public void matchingNested_withDependencies_aggregatesDependencies(
      int validityHorizon, int expectedAnalysisMatch, int expectedSourceMatch) {
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);
    changes.registerFileChange("src/a", 98);

    var key =
        new NestedDependencies(
            ImmutableList.of(
                FileDependencies.builder("dep/a").build(),
                FileDependencies.builder("dep/b").build(),
                FileDependencies.builder("dep/c").build()),
            ImmutableList.of(FileDependencies.builder("src/a").build()));

    NestedMatchResult expectedResult =
        createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch);
    assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters(
      "{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
  @TestParameters(
      "{validityHorizon: 102, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
  public void matchingNested_withAsyncDependencies_aggregatesDependencies(
      int validityHorizon, int expectedAnalysisMatch, int expectedSourceMatch) throws Exception {
    // This test covers the futures handling code path in AggregatingFutureFileOpMatchResult.
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);
    changes.registerFileChange("src/a", 102);

    var depA = new ControllableFileDependencies(ImmutableList.of("dep/a"), ImmutableList.of());
    var depB = new ControllableFileDependencies(ImmutableList.of("dep/b"), ImmutableList.of());
    var depC = new ControllableFileDependencies(ImmutableList.of("dep/c"), ImmutableList.of());
    var srcA = new ControllableFileDependencies(ImmutableList.of("src/a"), ImmutableList.of());
    var key = new NestedDependencies(ImmutableList.of(depA, depB, depC), ImmutableList.of(srcA));

    var pool = new ForkJoinPool(4); // one for each dependency and source
    pool.execute(
        () -> {
          var unused = getLookupResult(depA, validityHorizon);
        });
    pool.execute(
        () -> {
          var unused = getLookupResult(depB, validityHorizon);
        });
    pool.execute(
        () -> {
          var unused = getLookupResult(depC, validityHorizon);
        });
    pool.execute(
        () -> {
          var unused = getLookupResult(srcA, validityHorizon);
        });

    // Waits for all the dependency threads to take ownership of their entries.
    depA.awaitEarliestMatchEntered();
    depB.awaitEarliestMatchEntered();
    depC.awaitEarliestMatchEntered();
    srcA.awaitEarliestMatchEntered();

    var lookupResult = (FutureNestedMatchResult) lookup.getValueOrFuture(key, validityHorizon);
    assertThat(lookupResult.isDone()).isFalse();

    // The lookupResult cannot complete until all the dependencies complete. Releases the
    // dependencies.
    depA.enable();
    depB.enable();
    depC.enable();
    srcA.enable();

    NestedMatchResult expectedResult =
        createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch);
    assertThat(lookupResult.get()).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters(
      "{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
  public void matchingNested_withNestedDependencies_aggregatesDependencies(
      int validityHorizon, int expectedAnalysisMatch, int expectedSourceMatch) {
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);
    changes.registerFileChange("src/a", 102);

    var nestedDep =
        new NestedDependencies(
            ImmutableList.of(
                FileDependencies.builder("dep/b").build(),
                FileDependencies.builder("dep/c").build()),
            ImmutableList.of(FileDependencies.builder("src/a").build()));

    var key =
        new NestedDependencies(
            ImmutableList.of(FileDependencies.builder("dep/a").build(), nestedDep),
            ImmutableList.of());

    NestedMatchResult expectedResult =
        createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch);
    assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters(
      "{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
  @TestParameters(
      "{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
  @TestParameters(
      "{validityHorizon: 102, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
  public void matchingNested_withAsyncNestedDependencies_aggregatesDependencies(
      int validityHorizon, int expectedAnalysisMatch, int expectedSourceMatch) throws Exception {
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);
    changes.registerFileChange("src/a", 102);

    var nestedDep =
        new NestedDependencies(
            ImmutableList.of(
                FileDependencies.builder("dep/b").build(),
                FileDependencies.builder("dep/c").build()),
            ImmutableList.of(FileDependencies.builder("src/a").build()));

    var key =
        new NestedDependencies(
            ImmutableList.of(FileDependencies.builder("dep/a").build(), nestedDep),
            ImmutableList.of());

    NestedMatchResult expectedResult =
        createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch);

    // Spawns THREAD_COUNT threads to test parallel nested dependency lookups.
    var executor = new ForkJoinPool(THREAD_COUNT);
    var latch = new CountDownLatch(THREAD_COUNT);
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.execute(
          () -> {
            switch (lookup.getValueOrFuture(key, validityHorizon)) {
              case NestedMatchResult value:
                assertThat(value).isEqualTo(expectedResult);
                break;
              case FutureNestedMatchResult future:
                try {
                  assertThat(future.get()).isEqualTo(expectedResult);
                } catch (Exception e) {
                  if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                  }
                  throw new AssertionError(e);
                }
            }
            latch.countDown();
          });
    }
    latch.await();
  }

  @Test
  public void createNestedMatchResult_analysisVersionNoMatch_sourceVersionPositive_sourceMatch() {
    NestedMatchResult result = createNestedMatchResult(NO_MATCH, 5);
    assertThat(result).isEqualTo(new SourceMatch(5));
  }

  @Test
  public void createNestedMatchResult_analysisVersionLessEqualSourceVersion_analysisMatch() {
    NestedMatchResult result = createNestedMatchResult(10, 20);
    assertThat(result).isEqualTo(new AnalysisMatch(10));
  }

  @Test
  public void createNestedMatchResult_analysisVersionGreaterSourceVersion_analysisNonNoMatch() {
    NestedMatchResult result = createNestedMatchResult(20, 5);
    assertThat(result).isEqualTo(new AnalysisAndSourceMatch(20, 5));
  }

  @Test
  public void createNestedMatchResult_analysisVersionGreaterSourceVersion_analysisAndSourceMatch() {
    NestedMatchResult result = createNestedMatchResult(20, 10);
    assertThat(result).isEqualTo(new AnalysisAndSourceMatch(20, 10));
  }

  @Test
  public void createNestedMatchResult_analysisVersionEqualSourceVersion_analysisMatch() {
    NestedMatchResult result = createNestedMatchResult(10, 10);
    assertThat(result).isEqualTo(new AnalysisMatch(10));
  }

  @Test
  public void createNestedMatchResult_analysisVersionNoMatch_sourceVersionNoMatch_noMatchResult() {
    NestedMatchResult result = createNestedMatchResult(NO_MATCH, NO_MATCH);
    assertThat(result).isEqualTo(NO_MATCH_RESULT);
  }

  private NestedMatchResult getLookupResult(NestedDependencies key, int validityHorizon) {
    try {
      switch (lookup.getValueOrFuture(key, validityHorizon)) {
        case NestedMatchResult result:
          return result;
        case FutureNestedMatchResult future:
          return future.get();
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError(e);
    }
  }

  private FileOpMatchResult getLookupResult(FileOpDependency key, int validityHorizon) {
    try {
      switch (fileOpMatches.getValueOrFuture(key, validityHorizon)) {
        case FileOpMatchResult result:
          return result;
        case FutureFileOpMatchResult future:
          return future.get();
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError(e);
    }
  }

  private static NestedDependencies createNestedDependencies(FileDependencies fileDependency) {
    return new NestedDependencies(
        new FileSystemDependencies[] {fileDependency}, NestedDependencies.EMPTY_SOURCES);
  }
}
