// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SnapshottableBiMap}. */
@RunWith(JUnit4.class)
public final class SnapshottableBiMapTest {
  // Dummy value type for maps under test. AutoValue for correct hash/equals behavior.
  record Value(String name, boolean tracked) {
    Value {
      requireNonNull(name, "name");
    }

    static Value trackedOf(String name) {
      return new Value(name, true);
    }

    static Value untrackedOf(String name) {
      return new Value(name, false);
    }

    static boolean track(Value value) {
      return value.tracked();
    }

  }

  private static <E> void verifyCollectionSizeAndContentsInOrder(
      Collection<E> collection, Collection<E> expected) {
    // Exhaustive testing of a collection's methods; we cannot rely on a minimal usual set of JUnit
    // helpers because we want to verify that the collection has valid Collection semantics.
    if (expected.isEmpty()) {
      assertThat(collection).isEmpty();
    } else {
      assertThat(collection).isNotEmpty();
    }
    assertThat(collection).hasSize(expected.size());
    assertThat(collection).containsExactlyElementsIn(expected).inOrder();
    for (E entry : expected) {
      // JUnit's containsExactlyElementsIn iterates over the collection under test, but doesn't call
      // its contains() method.
      assertThat(collection).contains(entry);
    }
  }

  private static <K, V> void verifyMapSizeAndContentsInOrder(Map<K, V> map, Map<K, V> expectedMap) {
    // Exhaustive testing of a map's methods; we cannot rely on a minimal usual set of JUnit helpers
    // because we want to verify that the map has valid Map semantics.
    if (expectedMap.isEmpty()) {
      assertThat(map).isEmpty();
    } else {
      assertThat(map).isNotEmpty();
    }

    assertThat(map).hasSize(expectedMap.size());
    assertThat(map).containsExactlyEntriesIn(expectedMap).inOrder();

    for (Map.Entry<K, V> entry : expectedMap.entrySet()) {
      assertThat(map.containsKey(entry.getKey()))
          .isTrue(); // JUnit's containsKey implementation does not explicitly call map.containsKey
      assertThat(map.containsValue(entry.getValue())).isTrue();
    }

    verifyCollectionSizeAndContentsInOrder(map.entrySet(), expectedMap.entrySet());
    verifyCollectionSizeAndContentsInOrder(map.keySet(), expectedMap.keySet());
    verifyCollectionSizeAndContentsInOrder(map.values(), expectedMap.values());
  }

  @SuppressWarnings("unchecked") // test-only convenience vararg transformation
  private static <K, V> void verifyMapSizeAndContentsInOrder(
      Map<K, V> map, K key0, V value0, Object... rest) {
    ImmutableMap.Builder<K, V> expectedBuilder = ImmutableMap.builder();
    expectedBuilder.put(key0, value0);
    Preconditions.checkArgument(
        rest.length % 2 == 0, "rest must be a flattened list of key-value pairs");
    for (int i = 0; i < rest.length; i += 2) {
      expectedBuilder.put((K) rest[i], (V) rest[i + 1]);
    }
    Map<K, V> expectedMap = expectedBuilder.build();
    verifyMapSizeAndContentsInOrder(map, expectedMap);
  }

  private static <K, V> void verifyMapDoesNotContainEntry(Map<K, V> map, K key, V value) {
    Map.Entry<K, V> entry = new AbstractMap.SimpleEntry<>(key, value);

    // Exhaustive testing of a map's methods; we cannot rely on a minimal usual set of JUnit helpers
    // because we want to verify that the map has valid Map semantics.
    assertThat(map.containsKey(key))
        .isFalse(); // JUnit's containsKey implementation does not explicitly call map.containsKeys
    assertThat(map.containsValue(value)).isFalse();
    assertThat(map.entrySet()).doesNotContain(entry);
    assertThat(map.keySet()).doesNotContain(key);
    assertThat(map.values()).doesNotContain(value);
  }

  private static <K, V> void verifyMapIsEmpty(Map<K, V> map) {
    verifyMapSizeAndContentsInOrder(map, ImmutableMap.of());
  }

  private static <E> void verifyIteratorDoesNotAllowDeletions(Iterator<E> iterator) {
    while (iterator.hasNext()) {
      iterator.next();
      assertThrows(UnsupportedOperationException.class, iterator::remove);
    }
  }

  private static <K, V> void verifyMapDoesNotAllowDeletions(Map<K, V> map) {
    for (Map.Entry<K, V> entry : map.entrySet()) {
      K key = entry.getKey();
      V value = entry.getValue();
      assertThrows(UnsupportedOperationException.class, () -> map.remove(key));
      assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove(key));
      assertThrows(UnsupportedOperationException.class, () -> map.values().remove(value));
      assertThrows(UnsupportedOperationException.class, () -> map.entrySet().remove(entry));
    }

    verifyIteratorDoesNotAllowDeletions(map.keySet().iterator());
    verifyIteratorDoesNotAllowDeletions(map.values().iterator());
    verifyIteratorDoesNotAllowDeletions(map.entrySet().iterator());

    assertThrows(UnsupportedOperationException.class, map::clear);
  }

  @SuppressWarnings("unchecked") // test-only convenience vararg transformation
  private static <K, V> void verifyBiMapSizeAndContentsInOrder(
      BiMap<K, V> bimap, K key0, V value0, Object... rest) {
    ImmutableBiMap.Builder<K, V> expectedBuilder = ImmutableBiMap.builder();
    expectedBuilder.put(key0, value0);
    Preconditions.checkArgument(
        rest.length % 2 == 0, "rest must be a flattened list of key-value pairs");
    for (int i = 0; i < rest.length; i += 2) {
      expectedBuilder.put((K) rest[i], (V) rest[i + 1]);
    }
    BiMap<K, V> expectedBiMap = expectedBuilder.buildOrThrow();
    verifyMapSizeAndContentsInOrder(bimap, expectedBiMap);
    verifyMapSizeAndContentsInOrder(bimap.inverse(), expectedBiMap.inverse());
  }

  private static <K, V> void verifyBiMapIsEmpty(BiMap<K, V> bimap) {
    verifyMapSizeAndContentsInOrder(bimap, ImmutableMap.of());
    verifyMapSizeAndContentsInOrder(bimap.inverse(), ImmutableMap.of());
  }

  @Test
  public void containsInsertedEntries() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    verifyBiMapIsEmpty(map);
    Value a = Value.trackedOf("a");
    Value b = Value.untrackedOf("b");
    Value c = Value.trackedOf("c");
    Value z = Value.trackedOf("z");

    map.put("a", a);
    verifyBiMapSizeAndContentsInOrder(map, "a", a);

    map.put("b", b);
    verifyBiMapSizeAndContentsInOrder(map, "a", a, "b", b);

    map.put("c", c);
    verifyBiMapSizeAndContentsInOrder(map, "a", a, "b", b, "c", c);

    // verify that the map's various contains*() methods don't always return true.
    verifyMapDoesNotContainEntry(map, "z", z);
  }

  @Test
  public void put_replacesEntries() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value trackedA = Value.trackedOf("a");
    Value replaceA = Value.trackedOf("replace a");
    Value untrackedB = Value.untrackedOf("b");
    Value replaceB = Value.untrackedOf("b");

    map.put("a", trackedA);
    map.put("a", replaceA);
    map.put("b", untrackedB);
    map.put("b", replaceB);
    verifyBiMapSizeAndContentsInOrder(map, "a", replaceA, "b", replaceB);
  }

  @Test
  public void put_nonUniqueValue_illegal() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value tracked = Value.trackedOf("a");
    Value untracked = Value.untrackedOf("b");

    map.put("a", tracked);
    assertThrows(IllegalArgumentException.class, () -> map.put("aa", tracked));
    map.put("b", untracked);
    assertThrows(IllegalArgumentException.class, () -> map.put("bb", untracked));
  }

  @Test
  public void put_replacingUntrackedWithTracked_legal() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value tracked = Value.trackedOf("a");
    Value untracked = Value.untrackedOf("A");

    map.getTrackedSnapshot(); // start tracking
    map.put("a", untracked);
    map.put("a", tracked);
    verifyBiMapSizeAndContentsInOrder(map, "a", tracked);
  }

  @Test
  public void put_replacingTrackedWithUntracked_illegal() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value tracked = Value.trackedOf("a");
    Value untracked = Value.untrackedOf("A");

    map.getTrackedSnapshot(); // start tracking
    map.put("a", tracked);
    assertThrows(IllegalArgumentException.class, () -> map.put("a", untracked));
  }

  @Test
  @SuppressWarnings("deprecation") // test verifying that deprecated methods don't work
  public void deletions_unsupported() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value value = Value.trackedOf("a");
    Value replacement = Value.trackedOf("replacement a");

    map.put("a", value);
    verifyMapDoesNotAllowDeletions(map);
    verifyMapDoesNotAllowDeletions(map.inverse());
    assertThrows(UnsupportedOperationException.class, () -> map.forcePut("a", replacement));
    assertThrows(UnsupportedOperationException.class, () -> map.inverse().forcePut(value, "aa"));
  }

  @Test
  public void getUnderlyingBiMap_returnsBiMapSupportingRemove() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value a = Value.trackedOf("a");
    Value b = Value.untrackedOf("b");
    Value c = Value.trackedOf("c");

    map.put("a", a);
    map.put("b", b);
    map.put("c", c);
    BiMap<String, Value> underlying = map.getUnderlyingBiMap();
    verifyBiMapSizeAndContentsInOrder(underlying, "a", a, "b", b, "c", c);

    underlying.remove("a");
    verifyBiMapSizeAndContentsInOrder(underlying, "b", b, "c", c);
  }

  @Test
  public void snapshot_containsExpectedEntries() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value trackedA = Value.trackedOf("a");
    Value untrackedB = Value.untrackedOf("b");
    Value trackedC = Value.trackedOf("c");
    Value z = Value.trackedOf("z");

    Map<String, Value> snapshot0 = map.getTrackedSnapshot();
    verifyMapIsEmpty(snapshot0);

    map.put("a", trackedA);
    Map<String, Value> snapshot1 = map.getTrackedSnapshot();
    verifyMapIsEmpty(snapshot0);
    verifyMapSizeAndContentsInOrder(snapshot1, "a", trackedA);

    map.put("b", untrackedB);
    Map<String, Value> snapshot2 = map.getTrackedSnapshot();
    verifyMapIsEmpty(snapshot0);
    verifyMapSizeAndContentsInOrder(snapshot1, "a", trackedA);
    verifyMapSizeAndContentsInOrder(snapshot2, "a", trackedA); // b is untracked

    map.put("c", Value.trackedOf("c"));
    Map<String, Value> snapshot3 = map.getTrackedSnapshot();
    verifyMapIsEmpty(snapshot0);
    verifyMapSizeAndContentsInOrder(snapshot1, "a", trackedA); // c was added after snapshot
    verifyMapSizeAndContentsInOrder(snapshot2, "a", trackedA);
    verifyMapSizeAndContentsInOrder(snapshot3, "a", trackedA, "c", trackedC);

    // verify that a snapshot's various contains*() methods don't always return true.
    verifyMapDoesNotContainEntry(snapshot1, "z", z);
    verifyMapDoesNotContainEntry(snapshot2, "z", z);
    verifyMapDoesNotContainEntry(snapshot3, "z", z);
  }

  @Test
  public void snapshot_isUnmodifiable() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    map.put("a", Value.trackedOf("a"));
    map.put("b", Value.untrackedOf("b"));
    map.put("c", Value.trackedOf("c"));
    Map<String, Value> snapshot = map.getTrackedSnapshot();

    verifyMapDoesNotAllowDeletions(snapshot);
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.put("a", Value.trackedOf("replace a")));
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.put("d", Value.trackedOf("d")));
  }

  @Test
  public void snapshot_containsReplacementsPerformedBeforeSnapshotCreation() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value trackedA = Value.trackedOf("a");
    Value replacementA = Value.trackedOf("replacement a");
    Value untrackedB = Value.untrackedOf("b");
    Value replacementB = Value.trackedOf("replacement b");

    map.put("a", trackedA);
    map.put("b", untrackedB);
    verifyMapSizeAndContentsInOrder(map, "a", trackedA, "b", untrackedB);
    map.put("a", replacementA);
    map.put("b", replacementB);
    verifyMapSizeAndContentsInOrder(map, "a", replacementA, "b", replacementB);

    Map<String, Value> snapshot = map.getTrackedSnapshot();
    verifyMapSizeAndContentsInOrder(snapshot, "a", replacementA, "b", replacementB);
  }

  @Test
  public void snapshot_afterReplacingEntryInSnapshot_containsReplacement() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value original = Value.trackedOf("a");
    Value replacement = Value.trackedOf("replacement a");

    map.put("a", original);
    Map<String, Value> snapshot = map.getTrackedSnapshot();
    verifyMapSizeAndContentsInOrder(snapshot, "a", original);

    map.put("a", replacement);
    verifyMapSizeAndContentsInOrder(snapshot, "a", replacement);
  }

  @Test
  public void snapshot_afterReplacingEntryNotInSnapshot_doesNotContainReplacement() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value untrackedA = Value.untrackedOf("a");
    Value replacementA = Value.trackedOf("replacement a");
    Value trackedB = Value.trackedOf("b");
    Value replacementB = Value.trackedOf("replacement b");

    map.put("a", untrackedA);
    Map<String, Value> snapshot = map.getTrackedSnapshot();
    verifyMapIsEmpty(snapshot);

    map.put("a", replacementA);
    map.put("b", trackedB);
    map.put("b", replacementB);
    verifyMapSizeAndContentsInOrder(map, "a", replacementA, "b", replacementB);
    verifyMapIsEmpty(snapshot);
  }

  @Test
  public void snapshot_containsReplacementEntries_inOriginalKeyInsertionOrder() {
    SnapshottableBiMap<String, Value> map = new SnapshottableBiMap<>(Value::track);
    Value a = Value.trackedOf("a");
    Value b = Value.trackedOf("b");
    Value replaceB = Value.trackedOf("replacement b");
    Value c = Value.trackedOf("c");
    Value replaceC = Value.trackedOf("replacement c");

    map.put("a", a);
    map.put("b", b);
    map.put("c", c);

    Map<String, Value> snapshot = map.getTrackedSnapshot();
    verifyMapSizeAndContentsInOrder(snapshot, "a", a, "b", b, "c", c);

    map.put("c", replaceC);
    map.put("b", replaceB);
    verifyMapSizeAndContentsInOrder(snapshot, "a", a, "b", replaceB, "c", replaceC);
  }
}
