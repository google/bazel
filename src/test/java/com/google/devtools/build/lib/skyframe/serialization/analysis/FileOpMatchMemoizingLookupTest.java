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
import static com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch.NO_MATCH_RESULT;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class FileOpMatchMemoizingLookupTest {
  private final VersionedChanges changes = new VersionedChanges(ImmutableList.of());
  private final FileOpMatchMemoizingLookup lookup =
      new FileOpMatchMemoizingLookup(changes, new ConcurrentHashMap<>());

  @Test
  public void matchEmptyChanges_noMatch() {
    assertThat(getLookupResult(FileDependencies.builder("test_path").build(), 0))
        .isEqualTo(NO_MATCH_RESULT);
  }

  @Test
  public void matchingFileChange_inRangeValidityHorizon_matches() {
    changes.registerFileChange("abc/def", 100);

    var key = FileDependencies.builder("abc/def").build();
    assertThat(getLookupResult(key, 99)).isEqualTo(new FileOpMatch(100));
  }

  @Test
  public void matchingFileChange_outOfRangeValidityHorizon_doesNotMatch() {
    changes.registerFileChange("abc/def", 100);

    var key = FileDependencies.builder("abc/def").build();
    assertThat(getLookupResult(key, 100)).isEqualTo(NO_MATCH_RESULT);
  }

  @Test
  @TestParameters("{validityHorizon: 98, expectedMatchVersion: 99}")
  @TestParameters("{validityHorizon: 99, expectedMatchVersion: 100}")
  @TestParameters("{validityHorizon: 100, expectedMatchVersion: 101}")
  @TestParameters("{validityHorizon: 101, expectedMatchVersion: 2147483647}")
  public void matchingFileChange_withDependencies_aggregatesDependencies(
      int validityHorizon, int expectedMatchVersion) {
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);

    var key =
        FileDependencies.builder("abc/def")
            .addDependency(FileDependencies.builder("dep/a").build())
            .addDependency(FileDependencies.builder("dep/b").build())
            .addDependency(FileDependencies.builder("dep/c").build())
            .build();

    var expectedResult =
        expectedMatchVersion == VersionedChanges.NO_MATCH
            ? NO_MATCH_RESULT
            : new FileOpMatch(expectedMatchVersion);
    assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult);
  }

  @Test
  @TestParameters("{validityHorizon: 98, expectedMatchVersion: 99}")
  @TestParameters("{validityHorizon: 99, expectedMatchVersion: 100}")
  @TestParameters("{validityHorizon: 100, expectedMatchVersion: 101}")
  @TestParameters("{validityHorizon: 101, expectedMatchVersion: 2147483647}")
  public void matchingFileChange_withAsyncDependencies_aggregatesDependencies(
      int validityHorizon, int expectedMatchVersion) throws Exception {
    // This test covers the futures handling code path in AggregatingFutureFileOpMatchResult.
    changes.registerFileChange("dep/a", 99);
    changes.registerFileChange("dep/b", 100);
    changes.registerFileChange("dep/c", 101);

    var depA = new ControllableFileDependencies(ImmutableList.of("dep/a"), ImmutableList.of());
    var depB = new ControllableFileDependencies(ImmutableList.of("dep/b"), ImmutableList.of());
    var depC = new ControllableFileDependencies(ImmutableList.of("dep/c"), ImmutableList.of());
    var key =
        FileDependencies.builder("abc/def")
            .addDependency(depA)
            .addDependency(depB)
            .addDependency(depC)
            .build();

    new Thread(
            () -> {
              var unused = getLookupResult(depA, validityHorizon);
            })
        .start();
    new Thread(
            () -> {
              var unused = getLookupResult(depB, validityHorizon);
            })
        .start();
    new Thread(
            () -> {
              var unused = getLookupResult(depC, validityHorizon);
            })
        .start();

    // Waits for all the dependency threads have taken ownership of their entries.
    depA.awaitEarliestMatchEntered();
    depB.awaitEarliestMatchEntered();
    depC.awaitEarliestMatchEntered();

    var lookupResult = (FutureFileOpMatchResult) lookup.getValueOrFuture(key, validityHorizon);
    assertThat(lookupResult.isDone()).isFalse();

    // The lookupResult cannot complete until all the dependencies complete. Releases the
    // dependencies.
    depA.enable();
    depB.enable();
    depC.enable();

    var expectedResult =
        expectedMatchVersion == VersionedChanges.NO_MATCH
            ? NO_MATCH_RESULT
            : new FileOpMatch(expectedMatchVersion);
    assertThat(lookupResult.get()).isEqualTo(expectedResult);
  }

  @Test
  public void matchingListing_matchesContainedFileChange() {
    changes.registerFileChange("dir/a", 100);

    var key = new ListingDependencies(FileDependencies.builder("dir").build());
    assertThat(getLookupResult(key, 99)).isEqualTo(new FileOpMatch(100));
  }

  @Test
  public void matchListingChange_matchesDirectoryChange() {
    changes.registerFileChange("dir", 100);

    var key = new ListingDependencies(FileDependencies.builder("dir").build());
    assertThat(getLookupResult(key, 99)).isEqualTo(new FileOpMatch(100));
  }

  @Test
  public void matchListingChange_matchesDependencyChange() {
    changes.registerFileChange("dep/a", 100);

    var key =
        new ListingDependencies(
            FileDependencies.builder("dir")
                .addDependency(FileDependencies.builder("dep/a").build())
                .build());

    assertThat(getLookupResult(key, 99)).isEqualTo(new FileOpMatch(100));
  }

  private FileOpMatchResult getLookupResult(FileOpDependency key, int validityHorizon) {
    try {
      switch (lookup.getValueOrFuture(key, validityHorizon)) {
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
}
