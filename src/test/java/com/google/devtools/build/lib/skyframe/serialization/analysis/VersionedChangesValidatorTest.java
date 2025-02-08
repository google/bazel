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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.FutureNestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResult;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.concurrent.ForkJoinPool;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class VersionedChangesValidatorTest {
  private static final int THREAD_COUNT = 10;

  private final VersionedChanges changes = new VersionedChanges(ImmutableList.of());
  private final VersionedChangesValidator validator =
      new VersionedChangesValidator(new ForkJoinPool(THREAD_COUNT), changes);

  @Test
  public void matchesFileOpDependency_noMatch() throws Exception {
    changes.registerFileChange("abc/def", 100);
    assertThat(getMatchResult(FileDependencies.builder("abc/def").build(), 100))
        .isEqualTo(NO_MATCH_RESULT);
  }

  @Test
  public void matchesFileOpDependency_match() throws Exception {
    changes.registerFileChange("abc/def", 100);
    assertThat(getMatchResult(FileDependencies.builder("abc/def").build(), 99))
        .isEqualTo(new FileOpMatch(100));
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
    assertThat(getMatchResult(key, validityHorizon)).isEqualTo(expectedResult);
  }

  @Test
  public void differentValidityHorizons_sameFileDependencies() throws Exception {
    // This test case models Scenario 1 in the class comment of VersionedChangesValidator. It's not
    // mechanically interesting. The interesting constraints are properties of the MTSV and VH.
    changes.registerFileChange("shared", 100);
    changes.registerFileChange("dep/a", 101);
    changes.registerFileChange("dep/b", 102);

    var keyA =
        new NestedDependencies(
            ImmutableList.of(
                FileDependencies.builder("shared").build(),
                FileDependencies.builder("dep/a").build()),
            ImmutableList.of());

    var keyB =
        new NestedDependencies(
            ImmutableList.of(
                FileDependencies.builder("shared").build(),
                FileDependencies.builder("dep/b").build()),
            ImmutableList.of());

    // "A" has dependencies 'shared' and 'dep/a'. It was marked valid at VH 105 and has MTSV 101.
    // There are no invalidating changes. This marks 'shared' as NO_MATCH_RESULT.
    //
    // At the MTSV of 101, "shared" was at version 100. Since the VH is 105, it means there can't be
    // any changes in "shared" on the interval [101, 105].
    assertThat(getMatchResult(keyA, 105)).isEqualTo(NO_MATCH_RESULT);

    // "B" has dependencies 'shared' and 'dep/b'. It was marked clean at VH 110 and has MTSV 102.
    // There are no invalidating changes. It uses cached NO_MATCH_RESULT from "A"'s traversal.
    //
    // At the MTSV of 102, "shared" was at version 100. Since the VH is 110, it means there can't be
    // any changes in "shared" on the interval [101, 110].
    assertThat(getMatchResult(keyB, 110)).isEqualTo(NO_MATCH_RESULT);
  }

  @Test
  public void staleCachedValue_ignoredForSameKeyButDifferentValidityHorizon() throws Exception {
    // This test case models Scenario 2 in the class comment of VersionedChangesValidator.
    changes.registerFileChange("dep", 101);

    // Looks up 'dep' at version 100 and observes the invalidation at 101.
    var key1 = FileDependencies.builder("dep").build();
    assertThat(getMatchResult(key1, 100)).isEqualTo(new FileOpMatch(101));

    // Looks up 'dep' at version 102 and does not observe the invalidation.
    //
    // Even though these keys are identical, the trick here is that FileDependencies is based on
    // reference equality. The references in the FileDependencyDeserializer will be different if the
    // (canonical) MTSVs are different.
    var key2 = FileDependencies.builder("dep").build();
    assertThat(getMatchResult(key2, 102)).isEqualTo(NO_MATCH_RESULT);
  }

  private FileOpMatchResult getMatchResult(FileOpDependency key, int validityHorizon) {
    try {
      switch (validator.matches(key, validityHorizon)) {
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

  private NestedMatchResult getMatchResult(NestedDependencies key, int validityHorizon) {
    try {
      switch (validator.matches(key, validityHorizon)) {
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
}
