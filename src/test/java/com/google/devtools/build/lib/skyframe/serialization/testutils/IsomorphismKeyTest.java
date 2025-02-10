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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.IsomorphismKey.areIsomorphismKeysEqual;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class IsomorphismKeyTest {

  @Test
  public void comparingTrivialKeys_comparesTrue() {
    var key1 = new IsomorphismKey("a");
    var key2 = new IsomorphismKey("a");

    assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue();
  }

  @Test
  public void differentFingerprints_comparesFalse() {
    var key1 = new IsomorphismKey("a");
    var key2 = new IsomorphismKey("b");

    assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse();
  }

  @Test
  public void sameCyclicStructure_comparesTrue() {
    var key1 = new IsomorphismKey("a");
    var key2 = new IsomorphismKey("a");

    key1.addLink(key1);
    key2.addLink(key2);

    assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue();
  }

  @Test
  public void differentStructure_comparesFalse() {
    var key1 = new IsomorphismKey("a");
    var key2 = new IsomorphismKey("a");

    key1.addLink(key1);

    assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse();
  }

  @Test
  public void nestedStructure_comparesTrue() {
    var key1 = new IsomorphismKey("a");
    var b1 = new IsomorphismKey("b");
    var c1 = new IsomorphismKey("c");
    var d1 = new IsomorphismKey("d");

    key1.addLink(b1);
    key1.addLink(c1);
    b1.addLink(d1);
    c1.addLink(d1);

    var key2 = new IsomorphismKey("a");
    var b2 = new IsomorphismKey("b");
    var c2 = new IsomorphismKey("c");
    var d2 = new IsomorphismKey("d");

    key2.addLink(b2);
    key2.addLink(c2);
    b2.addLink(d2);
    c2.addLink(d2);

    assertThat(areIsomorphismKeysEqual(key1, key2)).isTrue();
  }

  @Test
  public void slightlyDifferentStructure_comparesFalse() {
    var key1 = new IsomorphismKey("a");
    var b1 = new IsomorphismKey("b");
    var c1 = new IsomorphismKey("c");
    var d1 = new IsomorphismKey("d");

    key1.addLink(b1);
    key1.addLink(c1);
    b1.addLink(d1);
    c1.addLink(d1);

    var key2 = new IsomorphismKey("a");
    var b2 = new IsomorphismKey("b");
    var c2 = new IsomorphismKey("c");
    var d2 = new IsomorphismKey("d");
    var d2prime = new IsomorphismKey("d");

    key2.addLink(b2);
    key2.addLink(c2);
    b2.addLink(d2);
    c2.addLink(d2prime);

    assertThat(areIsomorphismKeysEqual(key1, key2)).isFalse();
  }
}
