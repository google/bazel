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
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Concise description of an object, used for comparison with other objects.
 *
 * <p>Each instance corresponds to a partition found by partition refinement. It represents a set of
 * objects that have equivalent local fingerprints and equivalent links to other partitions.
 *
 * <p>Preserving the key can help with debugging and testing. It is possible to reduce this key to a
 * fingerprint if needed.
 */
public class IsomorphismKey {
  /**
   * The local fingerprint of a partition.
   *
   * <p>See {@link Canonizer.Node#localFingerprint}.
   */
  private final String fingerprint;

  /**
   * The edges to other partitions.
   *
   * <p>This is necessarily mutable because the underlying graphs are cyclic.
   */
  private final ArrayList<IsomorphismKey> links = new ArrayList<>();

  IsomorphismKey(String fingerprint) {
    this.fingerprint = fingerprint;
  }

  String fingerprint() {
    return fingerprint;
  }

  int getLinksCount() {
    return links.size();
  }

  IsomorphismKey getLink(int i) {
    return links.get(i);
  }

  /**
   * Adds a link.
   *
   * <p>Only used by {@link Canonizer} during construction (and testing).
   */
  void addLink(IsomorphismKey key) {
    links.add(key);
  }

  /**
   * Compares two {@link IsomorphismKey}s using joint depth-first-search.
   *
   * <p>Depth first search is sufficient because {@link IsomorphismKey}s are canonically structured.
   *
   * @return true if the objects that the keys are derived from are equivalent.
   */
  public static boolean areIsomorphismKeysEqual(IsomorphismKey objA, IsomorphismKey objB) {
    return IsomorphismKey.areKeysEqual(
        objA,
        objB,
        Collections.newSetFromMap(new IdentityHashMap<>()),
        Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static boolean areKeysEqual(
      IsomorphismKey objA,
      IsomorphismKey objB,
      Set<IsomorphismKey> visitedA,
      Set<IsomorphismKey> visitedB) {
    boolean objAIsNew = visitedA.add(objA);
    boolean objBIsNew = visitedB.add(objB);
    if (objAIsNew != objBIsNew) {
      return false;
    }
    if (!objAIsNew) {
      return true;
    }

    if (!objA.fingerprint.equals(objB.fingerprint)) {
      return false;
    }

    int size = objA.links.size();
    if (objB.links.size() != size) {
      return false;
    }

    for (int i = 0; i < size; i++) {
      if (!areKeysEqual(objA.links.get(i), objB.links.get(i), visitedA, visitedB)) {
        return false;
      }
    }
    return true;
  }
}
