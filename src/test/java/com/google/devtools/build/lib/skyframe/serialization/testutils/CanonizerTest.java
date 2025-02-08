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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.computeIdentifiers;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer.fingerprintString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CanonizerTest {
  // This string is very long:
  // com.google.devtools.build.lib.skyframe.serialization.testutils.CanonizerTest.
  // and ruins the spacing of assertions.
  private static final String NAMESPACE = CanonizerTest.class.getCanonicalName() + ".";

  @Test
  public void inlinedValues_leaveNoFingerprints() {
    String contents = "contents";
    var subject =
        new TypeWithInlinedData(
            (byte) 0x10,
            (short) 12345,
            65536,
            4294967296L,
            0.01f,
            12.123456789,
            true,
            'c',
            "text",
            new WeakReference<Object>(contents),
            ImmutableList.of("x"));

    var identifiers = new IdentityHashMap<Object, Object>();
    IsomorphismKey key = Canonizer.computePartitions(/* registry= */ null, subject, identifiers);
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                NAMESPACE
                    + "TypeWithInlinedData, boolValue=java.lang.Boolean:true,"
                    + " byteValue=java.lang.Byte:16, charValue=java.lang.Character:c,"
                    + " doubleValue=java.lang.Double:12.123456789, floatValue=java.lang.Float:0.01,"
                    + " intValue=java.lang.Integer:65536, longValue=java.lang.Long:4294967296,"
                    + " nonInlinedValue=TESTUTILS_CANONIZER_PLACEHOLDER,"
                    + " shortValue=java.lang.Short:12345, stringValue=java.lang.String:text,"
                    + " weakReferenceValue=java.lang.ref.WeakReference"));

    assertThat(key.getLinksCount()).isEqualTo(1);
    var nonInlinedValueKey = key.getLink(0);
    assertThat(nonInlinedValueKey.fingerprint())
        .isEqualTo(
            fingerprintString("com.google.common.collect.ImmutableList, java.lang.String:x"));
    assertThat(nonInlinedValueKey.getLinksCount()).isEqualTo(0);

    assertThat(computeBreakdown(identifiers))
        .isEqualTo(
            new Breakdown(
                // There are no fingerprints.
                ImmutableMap.of(),
                // There's a partition for `subject` and the `nonInlinedValue` field.
                ImmutableSet.of(
                    ImmutableSet.of(subject), ImmutableSet.of(subject.nonInlinedValue))));
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeLocal"})
  private static final class TypeWithInlinedData {
    private final Byte byteValue;
    private final Short shortValue;
    private final Integer intValue;
    private final Long longValue;
    private final Float floatValue;
    private final Double doubleValue;
    private final Boolean boolValue;
    private final Character charValue;
    private final String stringValue;
    private final WeakReference<Object> weakReferenceValue;

    /** A non-inlined value for contrast. */
    private final ImmutableList<Object> nonInlinedValue;

    private TypeWithInlinedData(
        byte byteValue,
        short shortValue,
        int intValue,
        long longValue,
        float floatValue,
        double doubleValue,
        boolean boolValue,
        char charValue,
        String stringValue,
        WeakReference<Object> weakReferenceValue,
        ImmutableList<Object> nonInlinedValue) {
      this.byteValue = byteValue;
      this.shortValue = shortValue;
      this.intValue = intValue;
      this.longValue = longValue;
      this.floatValue = floatValue;
      this.doubleValue = doubleValue;
      this.boolValue = boolValue;
      this.charValue = charValue;
      this.stringValue = stringValue;
      this.weakReferenceValue = weakReferenceValue;
      this.nonInlinedValue = nonInlinedValue;
    }
  }

  @Test
  public void specialCaseArrays() {
    var subject =
        new TypeWithSpecialArrays(
            new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
            new String[] {"abc", "def", "hij"});

    var identifiers = new IdentityHashMap<Object, Object>();
    IsomorphismKey key = Canonizer.computePartitions(/* registry= */ null, subject, identifiers);
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                NAMESPACE
                    + "TypeWithSpecialArrays, byteArray=63deb80b9d484a1af76057b22fa1f403,"
                    + " stringArray=9efba83af9eba70ede40159aa606209c"));
    assertThat(key.getLinksCount()).isEqualTo(0);

    assertThat(computeBreakdown(identifiers))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(
                    // byte[] inlines as hex.
                    subject.byteArray,
                    fingerprintString("byte[]: [DEADBEEF]"),
                    // String[] values inline.
                    subject.stringArray,
                    fingerprintString(
                        "java.lang.String[]: [java.lang.String:abc, java.lang.String:def,"
                            + " java.lang.String:hij]")),
                ImmutableSet.of(ImmutableSet.of(subject))));
  }

  private static final class TypeWithSpecialArrays {
    private final byte[] byteArray;
    private final String[] stringArray;

    private TypeWithSpecialArrays(byte[] byteArray, String[] stringArray) {
      this.byteArray = byteArray;
      this.stringArray = stringArray;
    }
  }

  @Test
  public void placeholder_correctlyFingerprints() {
    var subject = new Object[] {new Position(1, 2), new Position(3, 4), new Position(1, 2)};

    IsomorphismKey key =
        Canonizer.computePartitions(/* registry= */ null, subject, new IdentityHashMap<>());
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                "java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER,"
                    + " TESTUTILS_CANONIZER_PLACEHOLDER, TESTUTILS_CANONIZER_PLACEHOLDER"));
    assertThat(key.getLinksCount()).isEqualTo(3);
    assertThat(key.getLink(0).fingerprint())
        .isEqualTo(fingerprintString(NAMESPACE + "Position, x=1, y=2"));
    assertThat(key.getLink(1).fingerprint())
        .isEqualTo(fingerprintString(NAMESPACE + "Position, x=3, y=4"));
    assertThat(key.getLink(2).fingerprint())
        .isEqualTo(fingerprintString(NAMESPACE + "Position, x=1, y=2"));
  }

  @Test
  public void selfReferenceArray_reduces() {
    Object[] subject = new Object[1];
    subject[0] = subject;

    var identifiers = new IdentityHashMap<Object, Object>();
    IsomorphismKey key = Canonizer.computePartitions(/* registry= */ null, subject, identifiers);

    assertThat(key.fingerprint())
        .isEqualTo(fingerprintString("java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER"));
    assertThat(key.getLinksCount()).isEqualTo(1);
    assertThat(key.getLink(0)).isEqualTo(key); // The key is cyclic.

    assertThat(computeBreakdown(identifiers))
        .isEqualTo(new Breakdown(ImmutableMap.of(), ImmutableSet.of(ImmutableSet.of(subject))));
  }

  @Test
  public void symmetricalArrays_reduce() {
    Object[] subject = new Object[1];
    Object[] reflection = new Object[] {subject};
    subject[0] = reflection;

    var identifiers = new IdentityHashMap<Object, Object>();
    IsomorphismKey key = Canonizer.computePartitions(/* registry= */ null, subject, identifiers);

    assertThat(key.fingerprint())
        .isEqualTo(fingerprintString("java.lang.Object[], TESTUTILS_CANONIZER_PLACEHOLDER"));
    assertThat(key.getLinksCount()).isEqualTo(1);
    assertThat(key.getLink(0)).isEqualTo(key); // The key is cyclic.

    assertThat(computeBreakdown(identifiers))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(), ImmutableSet.of(ImmutableSet.of(subject, reflection))));
  }

  @Test
  public void distinctLocalFingerprintArrayCycle_fullyPartitions() {
    var pointA = new Position(0, 1);
    var pointB = new Position(1, 2);

    // Constructs a cycle, a1 -> a2 -> b -> a1.
    // (a1, a2) will have the same local fingerprint, but b1's local fingerprint is unique.
    Object[] a1 = new Object[2];
    Object[] a2 = new Object[2];
    Object[] b = new Object[2];

    a1[0] = pointA;
    a1[1] = a2;
    a2[0] = pointA;
    a2[1] = b;

    b[0] = pointB;
    b[1] = a1;

    var breakdown =
        new Breakdown(
            ImmutableMap.of(),
            ImmutableSet.of(
                ImmutableSet.of(pointA),
                ImmutableSet.of(pointB),
                ImmutableSet.of(a1),
                ImmutableSet.of(a2),
                ImmutableSet.of(b)));

    // Verifies that partitioning is independent of starting node.
    for (Object[] root : ImmutableList.of(a1, a2, b)) {
      assertThat(computeBreakdown(root)).isEqualTo(breakdown);
    }
  }

  @Test
  public void localFingerprintIndistinguishableArrayCycle_fullyPartitions() {
    var pointA = new Position(0, 1);
    var pointB = new Position(1, 2);

    // Constructs a cycle, a1 -> a2 -> a3 -> b1 -> b2 -> a1.
    // (a1, a2, a3) and (b1, b2) have the same local fingerprints so local fingerprinting is not
    // enough to resolve this cycle. However, the cycle is slightly asymmetrical so every element
    // receives its own partition.
    Object[] a1 = new Object[2];
    Object[] a2 = new Object[2];
    Object[] a3 = new Object[2];
    Object[] b1 = new Object[2];
    Object[] b2 = new Object[2];

    a1[0] = pointA;
    a2[0] = pointA;
    a3[0] = pointA;
    b1[0] = pointB;
    b2[0] = pointB;

    a1[1] = a2;
    a2[1] = a3;
    a3[1] = b1;
    b1[1] = b2;
    b2[1] = a1;

    var breakdown =
        new Breakdown(
            ImmutableMap.of(),
            ImmutableSet.of(
                ImmutableSet.of(pointA),
                ImmutableSet.of(pointB),
                ImmutableSet.of(a1),
                ImmutableSet.of(a2),
                ImmutableSet.of(a3),
                ImmutableSet.of(b1),
                ImmutableSet.of(b2)));

    // Verifies that partitioning is independent of starting node.
    for (Object[] root : ImmutableList.of(a1, a2, a3, b1, b2)) {
      assertThat(computeBreakdown(root)).isEqualTo(breakdown);
    }
  }

  @Test
  public void overlySymmetricalArrayCycle_reduces() {
    var pointA = new Position(0, 1);
    var pointB = new Position(1, 2);

    // Constructs the cycle a1 -> b1 -> a2 -> b2 -> a1.
    // This cycle is perfectly symmetrical so it reduces.
    Object[] a1 = new Object[2];
    Object[] a2 = new Object[2];
    Object[] b1 = new Object[2];
    Object[] b2 = new Object[2];

    a1[0] = pointA;
    a2[0] = pointA;
    b1[0] = pointB;
    b2[0] = pointB;

    a1[1] = b1;
    b1[1] = a2;
    a2[1] = b2;
    b2[1] = a1;

    var breakdown =
        new Breakdown(
            ImmutableMap.of(),
            ImmutableSet.of(
                ImmutableSet.of(pointA),
                ImmutableSet.of(pointB),
                ImmutableSet.of(a1, a2),
                ImmutableSet.of(b1, b2)));

    // Verifies that partitioning is independent of starting node.
    for (Object[] root : ImmutableList.of(a1, a2, b1, b2)) {
      assertThat(computeBreakdown(root)).isEqualTo(breakdown);
    }
  }

  @Test
  public void map() {
    var subject = new LinkedHashMap<Object, Object>();
    subject.put("abc", "def");
    subject.put(10, 12.5);
    subject.put(null, 'c');
    subject.put("k1", null);
    var position = new Position(5, 10);
    subject.put(position, "Value");

    assertThat(computeBreakdown(subject))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(),
                ImmutableSet.of(ImmutableSet.of(position), ImmutableSet.of(subject))));
  }

  @Test
  public void symmetricalMapCycle_reduces() {
    // Constructs the cycle a1 -> b1 -> a2 -> b2 -> a1.
    // This cycle is perfectly symmetrical so it is reduced.
    var a1 = new LinkedHashMap<String, Object>();
    var b1 = new LinkedHashMap<String, Object>();
    var a2 = new LinkedHashMap<String, Object>();
    var b2 = new LinkedHashMap<String, Object>();

    a1.put("A", b1);
    b1.put("B", a2);
    a2.put("A", b2);
    b2.put("B", a1);

    IsomorphismKey aKey =
        Canonizer.computePartitions(/* registry= */ null, a1, new IdentityHashMap<>());
    assertThat(aKey.fingerprint())
        .isEqualTo(
            fingerprintString(
                "java.util.LinkedHashMap, key=java.lang.String:A,"
                    + " value=TESTUTILS_CANONIZER_PLACEHOLDER"));
    assertThat(aKey.getLinksCount()).isEqualTo(1);
    IsomorphismKey bKey = aKey.getLink(0);
    assertThat(bKey.fingerprint())
        .isEqualTo(
            fingerprintString(
                "java.util.LinkedHashMap, key=java.lang.String:B,"
                    + " value=TESTUTILS_CANONIZER_PLACEHOLDER"));
    assertThat(bKey.getLinksCount()).isEqualTo(1);
    assertThat(bKey.getLink(0)).isEqualTo(aKey); // The key has a cyclic structure.

    // Since it is a cycle, it is independent of starting node.
    for (LinkedHashMap<String, Object> root : ImmutableList.of(a1, a2, b1, b2)) {
      // These checks are manual because the cyclic hash maps cannot be hashed.
      IdentityHashMap<Object, Object> identifiers = computeIdentifiers(/* registry= */ null, root);
      assertThat(identifiers).hasSize(4);
      assertThat(ImmutableSet.copyOf(identifiers.values())).hasSize(2); // There are 2 partitions.

      assertThat(identifiers.get(a1)).isEqualTo(identifiers.get(a2));
      assertThat(identifiers.get(b1)).isEqualTo(identifiers.get(b2));
    }
  }

  @Test
  public void collection_partitions() {
    var subject = new ArrayList<Object>();
    subject.add("abc");
    subject.add(10);
    var position = new Position(12, 24);
    subject.add(position);
    subject.add(subject); // Creates a cycle.
    subject.add(null);

    var identifiers = new IdentityHashMap<Object, Object>();
    IsomorphismKey key = Canonizer.computePartitions(/* registry= */ null, subject, identifiers);

    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                "java.util.ArrayList, java.lang.String:abc, java.lang.Integer:10,"
                    + " TESTUTILS_CANONIZER_PLACEHOLDER, TESTUTILS_CANONIZER_PLACEHOLDER, null"));

    assertThat(key.getLinksCount()).isEqualTo(2);

    var positionKey = key.getLink(0);
    assertThat(positionKey.fingerprint())
        .isEqualTo(fingerprintString(NAMESPACE + "Position, x=12, y=24"));
    assertThat(positionKey.getLinksCount()).isEqualTo(0);

    assertThat(key.getLink(1)).isEqualTo(key); // The key reflects the cyclic structure.

    // These checks are performed element-wise because the cyclic ArrayList cannot be hashed.
    assertThat(identifiers).hasSize(2);
    var subjectId = identifiers.get(subject);
    assertThat(subjectId).isInstanceOf(Canonizer.Partition.class);
    var positionId = identifiers.get(position);
    assertThat(positionId).isInstanceOf(Canonizer.Partition.class);

    assertThat(subjectId).isNotEqualTo(positionId);
  }

  @Test
  public void plainObject() {
    var subject = new ExamplePlainObject();
    IsomorphismKey key =
        Canonizer.computePartitions(/* registry= */ null, subject, new IdentityHashMap<>());
    assertThat(key.getLinksCount()).isEqualTo(0);
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                NAMESPACE
                    + "ExamplePlainObject,"
                    + " booleanValue=false, byteValue=16, charValue=c,"
                    + " classValue=java.lang.Class:interface java.lang.Runnable,"
                    + " doubleValue=12.123456789, floatValue=0.01, intValue=65536,"
                    + " longValue=4294967296, nullClass=null, nullString=null, shortValue=12345,"
                    + " stringValue=java.lang.String:text"));
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class ExamplePlainObject {
    private final boolean booleanValue = false;
    private final byte byteValue = (byte) 0x10;
    private final short shortValue = (short) 12345;
    private final char charValue = 'c';
    private final int intValue = 65536;
    private final long longValue = 4294967296L;
    private final float floatValue = 0.01f;
    private final double doubleValue = 12.123456789;
    private final String stringValue = "text";
    private final String nullString = null;
    private final Class<?> classValue = Runnable.class;
    private final Class<?> nullClass = null;
  }

  @Test
  public void singleReferenceConstant() {
    String constant = "constant";
    ObjectCodecRegistry registry =
        ObjectCodecRegistry.newBuilder().addReferenceConstant(constant).build();

    // As expected, there are no identifiers for fully inlined objects.
    assertThat(computeIdentifiers(registry, constant)).isEmpty();
    assertThat(Canonizer.computePartitions(registry, constant, new IdentityHashMap<>())).isNull();

    Object[] subject = new Object[] {constant};
    IsomorphismKey key = Canonizer.computePartitions(registry, subject, new IdentityHashMap<>());

    assertThat(key.getLinksCount()).isEqualTo(0);
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString("java.lang.Object[], java.lang.String[SERIALIZATION_CONSTANT:1]"));
  }

  @Test
  public void singleReferenceConstant_defaultRegistry() {
    String constant = "constant";
    ObjectCodecRegistry registry = ObjectCodecRegistry.newBuilder().build();

    Object[] subject = new Object[] {constant};
    IsomorphismKey key = Canonizer.computePartitions(registry, subject, new IdentityHashMap<>());
    assertThat(key.getLinksCount()).isEqualTo(0);
    assertThat(key.fingerprint())
        .isEqualTo(fingerprintString("java.lang.Object[], java.lang.String:constant"));
  }

  @Test
  public void singleReferenceConstant_nullRegistry() {
    String constant = "constant";
    Object[] subject = new Object[] {constant};

    IsomorphismKey key =
        Canonizer.computePartitions(/* registry= */ null, subject, new IdentityHashMap<>());
    assertThat(key.getLinksCount()).isEqualTo(0);
    assertThat(key.fingerprint())
        .isEqualTo(fingerprintString("java.lang.Object[], java.lang.String:constant"));
  }

  @Test
  public void multipleReferenceConstants() {
    String constant1 = "constant1";
    Integer constant2 = 256;
    ObjectCodecRegistry registry =
        ObjectCodecRegistry.newBuilder()
            .addReferenceConstant(constant1)
            .addReferenceConstant(constant2)
            .build();
    var subject = ImmutableList.of(constant1, "a", constant2, constant1);
    IsomorphismKey key = Canonizer.computePartitions(registry, subject, new IdentityHashMap<>());
    assertThat(key.getLinksCount()).isEqualTo(0);
    assertThat(key.fingerprint())
        .isEqualTo(
            fingerprintString(
                "com.google.common.collect.ImmutableList,"
                    + " java.lang.String[SERIALIZATION_CONSTANT:1], java.lang.String:a,"
                    + " java.lang.Integer[SERIALIZATION_CONSTANT:2],"
                    + " java.lang.String[SERIALIZATION_CONSTANT:1]"));
  }

  @Test
  public void cyclicComplex_reduces() {
    Node zero = new Node(0);
    Node one = new Node(0);
    Node two = new Node(0);
    Node three = new Node(0);
    Node four = new Node(0);

    // The example consists of two overlapping cycles.
    // 0 -> 1 -> 2 -> 0
    zero.left = one;
    one.left = two;
    two.left = zero;
    // 0 -> 1 -> 3 -> 4 -> 0
    one.right = three;
    three.left = four;
    four.left = zero;

    var breakdown =
        new Breakdown(
            ImmutableMap.of(),
            ImmutableSet.of(
                ImmutableSet.of(zero),
                ImmutableSet.of(one),
                // 2 and 4 are equivalent because they both transition to 0 on left.
                ImmutableSet.of(two, four),
                ImmutableSet.of(three)));

    // Verifies that all rotations of the cyclic complex result in the same breakdown.
    for (Node rotated : new Node[] {zero, one, two, three, four}) {
      assertThat(computeBreakdown(rotated)).isEqualTo(breakdown);
    }
  }

  @Test
  public void childCycle_reduces() {
    Node zero = new Node(0);
    Node one = new Node(0);
    Node two = new Node(0);
    Node three = new Node(0);
    Node four = new Node(0);
    Node five = new Node(0);

    // The example consists of one cycle hanging off from another one.
    // 0 -> 1 -> 2 -> 0
    zero.left = one;
    one.left = two;
    two.left = zero;
    // 1 -> 3 -> 4 -> 5 -> 3
    one.right = three;
    three.left = four;
    four.left = five;
    five.left = three;

    assertThat(computeBreakdown(zero))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(),
                ImmutableSet.of(
                    ImmutableSet.of(zero),
                    ImmutableSet.of(one),
                    ImmutableSet.of(two),
                    ImmutableSet.of(three, four, five))));
  }

  @Test
  public void peerCycles_reduce() {
    Node zero = new Node(0);
    Node one = new Node(0);
    Node two = new Node(0);
    Node three = new Node(0);
    Node four = new Node(0);
    Node five = new Node(0);

    // The example consists of two cycles hanging off a common root, 0. The cycles at the leaves are
    // identical in structure.
    // (left) 0 -> 1 -> 2 -> 1
    zero.left = one;
    one.left = two;
    two.left = one;
    // (right) 0 -> 3 -> 4 -> 5 -> 4
    zero.right = three;
    three.left = four;
    four.left = five;
    five.left = four;

    assertThat(computeBreakdown(zero))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(),
                ImmutableSet.of(
                    ImmutableSet.of(zero),
                    // It's clear that the cycles (1, 2) and (4, 5) are indistuishable. Furthermore,
                    // 1 is indistinguishable from 2. Somewhat surprisingly, 3 is also
                    // indistinguishable. There are only two local fingerprints in this graph, call
                    // them fp0 and fp1. So node 3 looks looks like (fp1 · fp1*)?, which is
                    // indistinguishable from fp1*.
                    ImmutableSet.of(one, two, three, four, five))));
  }

  @Test
  public void unlabeledCyclicComplex_reduces() {
    Node zero = new Node(0);
    Node one = new Node(0);
    Node two = new Node(0);
    Node three = new Node(0);
    Node four = new Node(0);

    // The example consists of a somewhat complex graph.
    //    0
    //  ↗ ↓ ↖
    //  | 1  \
    //  |↙ ↘  \
    //  2   3  |
    //   ↖  ↓ /
    //      4
    // Note that (0, 2, 3) and (1, 4) are indistinguishable by local fingerprint. Let fp0 and fp1
    // be the local fingerprints, respectively. There's symmetry here, but it's hard to see at first
    // glance.
    //
    // 1 and 4 are equivalent. From either 1 or 4, taking the left branch, two hops through an fp0
    // node are required to reach 1 again. Taking the right branch, one hop is needed to reach 1 or
    // 4 again. Equivalence of 0 and 3 follows from that.
    zero.left = one;
    one.left = two;
    one.right = three;
    two.left = zero;
    three.left = four;
    four.left = two;
    four.right = zero;

    var breakdown =
        new Breakdown(
            ImmutableMap.of(),
            ImmutableSet.of(
                ImmutableSet.of(zero, three), ImmutableSet.of(one, four), ImmutableSet.of(two)));

    // Verfies all starting points produce the identical fingerprint maps.
    for (Node rotated : new Node[] {zero, one, two, three, four}) {
      assertThat(computeBreakdown(rotated)).isEqualTo(breakdown);
    }
  }

  @Test
  public void depthOverlappingRecursion_reduces() {
    // Sets up a graph consisting of a cycle with another cycle hanging off of it.
    //   A -> B -> C -> A            (first cycle)
    //        B -> D -> E -> F -> D  (second cycle)
    var a = new Object[2];
    var b = new Object[3];
    var c = new Object[2];
    var d = new Object[2];
    var e = new Object[2];
    var f = new Object[2];

    a[0] = "A";
    b[0] = "B";
    c[0] = "C";
    d[0] = "D"; // D, E, and F are locally ambiguous.
    e[0] = "D";
    f[0] = "D";

    a[1] = b;
    b[1] = c;
    b[2] = d;
    c[1] = a;
    d[1] = e;
    e[1] = f;
    f[1] = d;

    assertThat(computeBreakdown(a))
        .isEqualTo(
            new Breakdown(
                ImmutableMap.of(),
                ImmutableSet.of(
                    ImmutableSet.of(a),
                    ImmutableSet.of(b),
                    ImmutableSet.of(c),
                    ImmutableSet.of(d, e, f))));
  }

  private record Breakdown(
      ImmutableMap<Object, String> inlineFingerprints,
      ImmutableSet<ImmutableSet<Object>> partitions) {}

  /** Computes identifiers, then separates fingerprinted objects from partitions. */
  private static Breakdown computeBreakdown(Object subject) {
    return computeBreakdown(computeIdentifiers(/* registry= */ null, subject));
  }

  private static Breakdown computeBreakdown(IdentityHashMap<Object, Object> identifiers) {
    var fingerprints = ImmutableMap.<Object, String>builder();
    var partitions = new HashMap<Object, HashSet<Object>>();
    for (Map.Entry<Object, Object> entry : identifiers.entrySet()) {
      Object id = entry.getValue();
      Object obj = entry.getKey();
      if (id instanceof String fingerprint) {
        fingerprints.put(obj, fingerprint);
        continue;
      }
      partitions.computeIfAbsent(id, unused -> new HashSet<>()).add(obj);
    }
    return new Breakdown(
        fingerprints.buildOrThrow(),
        partitions.values().stream().map(ImmutableSet::copyOf).collect(toImmutableSet()));
  }

  /** Class for demonstrating reference cycles. */
  @SuppressWarnings("UnusedVariable")
  private static final class Node {
    private final int id;
    @Nullable private Node left;
    @Nullable private Node right;

    private Node(int id) {
      this.id = id;
    }

    @Override
    public String toString() {
      return Integer.toString(id);
    }
  }

  /** An arbitrary class used as test data. */
  private record Position(int x, int y) {}
}
