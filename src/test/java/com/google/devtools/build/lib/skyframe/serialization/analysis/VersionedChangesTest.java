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
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VersionedChangesTest {
  @Test
  public void clientFileChange_matchesFiles() {
    var changes = new VersionedChanges(ImmutableList.of("abc", "def"));

    assertThat(changes.matchFileChange("abc", 0)).isEqualTo(CLIENT_CHANGE);
    assertThat(changes.matchFileChange("def", 0)).isEqualTo(CLIENT_CHANGE);
  }

  @Test
  public void clientFileChange_matchesListing() {
    var changes = new VersionedChanges(ImmutableList.of("abc/def"));

    assertThat(changes.matchFileChange("abc/def", 0)).isEqualTo(CLIENT_CHANGE);
    assertThat(changes.matchListingChange("abc/def", 0)).isEqualTo(NO_MATCH);
    assertThat(changes.matchFileChange("abc", 0)).isEqualTo(NO_MATCH);
    assertThat(changes.matchListingChange("abc", 0)).isEqualTo(CLIENT_CHANGE);
  }

  @Test
  public void registerChange_matches() {
    var changes = new VersionedChanges(ImmutableList.of());
    changes.registerFileChange("abc/def", 10);

    assertThat(changes.matchFileChange("abc/def", 10)).isEqualTo(NO_MATCH);
    assertThat(changes.matchFileChange("abc/def", 9)).isEqualTo(10);

    assertThat(changes.matchListingChange("abc", 10)).isEqualTo(NO_MATCH);
    assertThat(changes.matchListingChange("abc", 9)).isEqualTo(10);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_exactMatch() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 6)).isEqualTo(6);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_noMatchLarger() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 12))
        .isEqualTo(NO_MATCH);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_noMatchSmaller() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 1)).isEqualTo(2);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_inBetweenMatch() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 7)).isEqualTo(8);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_emptyArray() {
    int[] versions = {};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 5))
        .isEqualTo(NO_MATCH);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_nullArray() {
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(null, 5))
        .isEqualTo(NO_MATCH);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_firstMatch() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 2)).isEqualTo(2);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_lastMatch() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, 10)).isEqualTo(10);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_veryLargeMinVersion() {
    int[] versions = {2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, Integer.MAX_VALUE))
        .isEqualTo(NO_MATCH);
  }

  @Test
  public void findMinimumVersionGreaterThanOrEqualTo_negativeMinVersion() {
    int[] versions = {-2, 2, 4, 6, 8, 10};
    assertThat(VersionedChanges.findMinimumVersionGreaterThanOrEqualTo(versions, -5)).isEqualTo(-2);
  }

  @Test
  public void insertChange_newPath() {
    var changes = new ConcurrentHashMap<String, int[]>();
    VersionedChanges.insertChange("file1.txt", 1, changes);
    assertThat(changes.get("file1.txt")).isEqualTo(new int[] {1});
  }

  @Test
  public void insertChange_existingPath_insertNewVersion() {
    ConcurrentHashMap<String, int[]> changes = createChangesMap("file1.txt", 1, 3, 5);
    VersionedChanges.insertChange("file1.txt", 4, changes);
    assertThat(changes.get("file1.txt")).isEqualTo(new int[] {1, 3, 4, 5});
  }

  @Test
  public void insertChange_existingPath_duplicateVersion() {
    ConcurrentHashMap<String, int[]> changes = createChangesMap("file1.txt", 1, 3, 5);
    VersionedChanges.insertChange("file1.txt", 3, changes);
    assertThat(changes.get("file1.txt")).isEqualTo(new int[] {1, 3, 5});
  }

  @Test
  public void insertChange_multiplePaths() {
    var changes = new ConcurrentHashMap<String, int[]>();
    VersionedChanges.insertChange("file1.txt", 2, changes);
    VersionedChanges.insertChange("file2.txt", 1, changes);
    VersionedChanges.insertChange("file1.txt", 1, changes);
    VersionedChanges.insertChange("file2.txt", 3, changes);

    assertThat(changes.get("file1.txt")).isEqualTo(new int[] {1, 2});
    assertThat(changes.get("file2.txt")).isEqualTo(new int[] {1, 3});
  }

  @Test
  public void insertSorted_emptyArray() {
    int[] result = VersionedChanges.insertSorted(new int[] {}, 5);
    assertThat(result).isEqualTo(new int[] {5});
  }

  @Test
  public void insertSorted_insertAtBeginning() {
    int[] result = VersionedChanges.insertSorted(new int[] {2, 4, 6}, 1);
    assertThat(result).isEqualTo(new int[] {1, 2, 4, 6});
  }

  @Test
  public void insertSorted_insertInMiddle() {
    int[] result = VersionedChanges.insertSorted(new int[] {2, 4, 6}, 3);
    assertThat(result).isEqualTo(new int[] {2, 3, 4, 6});
  }

  @Test
  public void insertSorted_insertAtEnd() {
    int[] result = VersionedChanges.insertSorted(new int[] {2, 4, 6}, 7);
    assertThat(result).isEqualTo(new int[] {2, 4, 6, 7});
  }

  @Test
  public void insertSorted_duplicate() {
    int[] original = new int[] {2, 4, 6};
    int[] result = VersionedChanges.insertSorted(original, 4);
    assertThat(result).isSameInstanceAs(original);
  }

  @Test
  public void getParentDirectory_validPath() {
    assertThat(VersionedChanges.getParentDirectory("a/b/c.txt")).isEqualTo("a/b");
  }

  @Test
  public void getParentDirectory_rootPath() {
    assertThat(VersionedChanges.getParentDirectory("/")).isEmpty();
  }

  @Test
  public void getParentDirectory_emptyPath() {
    assertThat(VersionedChanges.getParentDirectory("")).isEmpty();
  }

  @Test
  public void getParentDirectory_noSlash() {
    assertThat(VersionedChanges.getParentDirectory("file.txt")).isEmpty();
  }

  private static ConcurrentHashMap<String, int[]> createChangesMap(String path, int... versions) {
    var changes = new ConcurrentHashMap<String, int[]>();
    changes.put(path, versions);
    return changes;
  }
}
