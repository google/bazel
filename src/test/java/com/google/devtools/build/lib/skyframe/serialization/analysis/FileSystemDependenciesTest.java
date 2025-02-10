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
import static com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges.CLIENT_CHANGE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges.NO_MATCH;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FileSystemDependenciesTest {

  @Test
  public void fileDependencies_findEarliestMatch_matchesClientChange() {
    var changes = new VersionedChanges(ImmutableList.of("abc/def"));
    var dependencies = FileDependencies.builder("abc/def").build();

    assertThat(dependencies.findEarliestMatch(changes, 0)).isEqualTo(CLIENT_CHANGE);
  }

  @Test
  public void fileDependencies_findEarliestMatch_honorsValidityHorizon() {
    var changes = new VersionedChanges(ImmutableList.of());
    changes.registerFileChange("abc/def", 100);

    var dependencies = FileDependencies.builder("abc/def").build();

    assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100);
    assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH);
  }

  @Test
  public void fileDependencies_withMultiplePaths_findEarliestMatch_honorsValidityHorizon() {
    var changes = new VersionedChanges(ImmutableList.of());
    changes.registerFileChange("abc/def", 100);
    changes.registerFileChange("foo/bar", 99);

    var dependencies = FileDependencies.builder("abc/def").addPath("foo/bar").build();

    assertThat(dependencies.findEarliestMatch(changes, 98)).isEqualTo(99);
    assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100);
    assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH);
  }

  @Test
  public void listingDependencies_findEarliestMatch_matchesClientChange() {
    var changes = new VersionedChanges(ImmutableList.of("abc/def"));
    var dependencies = new ListingDependencies(FileDependencies.builder("abc").build());

    assertThat(dependencies.findEarliestMatch(changes, 0)).isEqualTo(CLIENT_CHANGE);
  }

  @Test
  public void listingDependencies_findEarliestMatch_honorsValidityHorizon() {
    var changes = new VersionedChanges(ImmutableList.of());
    changes.registerFileChange("abc/def", 99);
    changes.registerFileChange("abc/ghi", 100);

    var dependencies = new ListingDependencies(FileDependencies.builder("abc").build());

    assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH);
    assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100);
    assertThat(dependencies.findEarliestMatch(changes, 98)).isEqualTo(99);
  }
}
