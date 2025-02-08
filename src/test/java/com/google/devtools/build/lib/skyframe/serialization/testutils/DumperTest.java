// Copyright 2023 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructure;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class DumperTest {
  private static final String NAMESPACE = DumperTest.class.getCanonicalName();

  @Test
  public void testNull() {
    assertThat(dumpStructure(null)).isEqualTo("null");
  }

  @Test
  public void testWeakReference() {
    Integer referent = 10;
    var ref = new WeakReference<>(referent);
    assertThat(dumpStructure(ref)).isEqualTo("java.lang.ref.WeakReference");
  }

  @Test
  public void testInlinedTypes() {
    assertThat(dumpStructure(Byte.valueOf((byte) 0x10))).isEqualTo("16");
    assertThat(dumpStructure(Short.valueOf((short) 12345))).isEqualTo("12345");
    assertThat(dumpStructure(Integer.valueOf(65536))).isEqualTo("65536");
    assertThat(dumpStructure(Long.valueOf(4294967296L))).isEqualTo("4294967296");
    assertThat(dumpStructure(Float.valueOf(0.01f))).isEqualTo("0.01");
    assertThat(dumpStructure(Double.valueOf(12.123456789))).isEqualTo("12.123456789");
    assertThat(dumpStructure(Boolean.TRUE)).isEqualTo("true");
    assertThat(dumpStructure(Character.valueOf('c'))).isEqualTo("c");

    assertThat(dumpStructure("text")).isEqualTo("text");
    assertThat(dumpStructure(Thread.class)).isEqualTo("class java.lang.Thread");

    // Lambdas are also inlined because they qualify as sythetic.
    Runnable lambda = () -> {};
    assertThat(lambda.getClass().isSynthetic()).isTrue();
    // The string representation of lambdas is not stable, and has additional variability across
    // JDK versions. It should always start with the namespace.
    assertThat(dumpStructure(lambda)).matches(NAMESPACE + ".*");
  }

  @Test
  public void testByteArray() {
    byte[] bytes = new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    // Byte array output is special cased.
    assertThat(dumpStructure(bytes)).isEqualTo("byte[](0) [DEADBEEF]");
  }

  @Test
  public void nestedByteArrays() {
    byte[] bytes1 = new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
    byte[] bytes2 = new byte[] {(byte) 0xFA, (byte) 0xCE, (byte) 0xCA, (byte) 0xFE};

    byte[][] nestedBytes = new byte[][] {bytes1, bytes2, bytes1, null};

    assertThat(dumpStructure(nestedBytes))
        .isEqualTo(
            "byte[][](0) [\n"
                + "  byte[](1) [DEADBEEF]\n"
                + "  byte[](2) [FACECAFE]\n"
                + "  byte[](1)\n" // backreference
                + "  null\n"
                + "]");
  }

  @Test
  public void testInlineArray() {
    String[] input = new String[] {"abc", "def", "hij"};
    // Arrays declared with types that are inlined (such as String) are special cased.
    assertThat(dumpStructure(input)).isEqualTo("java.lang.String[](0) [abc, def, hij]");
  }

  @Test
  public void testMap() {
    var input = new LinkedHashMap<Object, Object>();
    input.put("abc", "def");
    input.put(10, 12.5);
    input.put(null, 'c');
    input.put("k1", null);
    input.put(new Position(5, 10), "Value");

    assertThat(dumpStructure(input))
        .isEqualTo(
            "java.util.LinkedHashMap(0) [\n"
                + "  key=abc\n"
                + "  value=def\n"
                + "  key=10\n"
                + "  value=12.5\n"
                + "  key=null\n"
                + "  value=c\n"
                + "  key=k1\n"
                + "  value=null\n"
                + ("  key=" + NAMESPACE + ".Position(1) [\n")
                + "    x=5\n"
                + "    y=10\n"
                + "  ]\n"
                + "  value=Value\n"
                + "]");
  }

  @Test
  public void testCollection() {
    var input = new ArrayList<Object>();
    input.add("abc");
    input.add(10);
    input.add(new Position(12, 24));
    input.add(input); // cyclic
    input.add(null);

    assertThat(dumpStructure(input))
        .isEqualTo(
            "java.util.ArrayList(0) [\n"
                + "  abc\n"
                + "  10\n"
                + ("  " + NAMESPACE + ".Position(1) [\n")
                + "    x=12\n"
                + "    y=24\n"
                + "  ]\n"
                + "  java.util.ArrayList(0)\n" // cyclic backreference
                + "  null\n"
                + "]");
  }

  @Test
  public void testPlainFields() {
    assertThat(dumpStructure(new ExamplePojo()))
        .isEqualTo(
            NAMESPACE
                + ".ExamplePojo(0) [\n"
                + "  booleanValue=false\n"
                + "  byteValue=16\n"
                + "  charValue=c\n"
                + "  classValue=interface java.lang.Runnable\n"
                + "  doubleValue=12.123456789\n"
                + "  floatValue=0.01\n"
                + "  intValue=65536\n"
                + "  longValue=4294967296\n"
                + "  nullClass=null\n"
                + "  nullString=null\n"
                + "  shortValue=12345\n"
                + "  stringValue=text\n"
                + "]");
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class ExamplePojo {
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
  public void testShadowedFields() {
    assertThat(dumpStructure(new ShadowedFieldsGrandchild()))
        .isEqualTo(
            NAMESPACE
                + ".ShadowedFieldsGrandchild(0) [\n"
                + "  shadowed=1\n"
                + "  shadowed=2\n"
                + "  shadowed=3\n"
                + "]");
  }

  enum DeduplicationMode {
    REFERENCE_DEDUPLICATION {
      @Override
      String dumpStructure(@Nullable ObjectCodecRegistry registry, Object obj) {
        return Dumper.dumpStructure(registry, obj);
      }
    },
    VALUE_DEDUPLICATION {
      @Override
      String dumpStructure(@Nullable ObjectCodecRegistry registry, Object obj) {
        return Dumper.dumpStructureWithEquivalenceReduction(registry, obj);
      }
    };

    abstract String dumpStructure(@Nullable ObjectCodecRegistry registry, Object obj);
  }

  @Test
  public void dump_handlesReferenceConstants(@TestParameter DeduplicationMode mode) {
    String subject = "constant";
    var registry = ObjectCodecRegistry.newBuilder().addReferenceConstant(subject).build();
    assertThat(mode.dumpStructure(registry, subject))
        .isEqualTo("java.lang.String[SERIALIZATION_CONSTANT:1]");
  }

  @Test
  public void dump_handlesReference_withoutReferenceConstant(
      @TestParameter DeduplicationMode mode, @TestParameter boolean emptyRegistry) {
    String subject = "constant";
    ObjectCodecRegistry registry = emptyRegistry ? ObjectCodecRegistry.newBuilder().build() : null;
    assertThat(mode.dumpStructure(registry, subject)).isEqualTo("constant");
  }

  @Test
  public void dump_handlesMultipleReferenceConstants(@TestParameter DeduplicationMode mode) {
    String constant1 = "constant1";
    Integer constant2 = 256;
    ObjectCodecRegistry registry =
        ObjectCodecRegistry.newBuilder()
            .addReferenceConstant(constant1)
            .addReferenceConstant(constant2)
            .build();
    var subject = ImmutableList.of(constant1, "a", constant2, constant1);
    assertThat(mode.dumpStructure(registry, subject))
        .isEqualTo(
            """
com.google.common.collect.ImmutableList(0) [
  java.lang.String[SERIALIZATION_CONSTANT:1]
  a
  java.lang.Integer[SERIALIZATION_CONSTANT:2]
  java.lang.String[SERIALIZATION_CONSTANT:1]
]\
""");
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class ShadowedFields {
    private final int shadowed = 1;
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class ShadowedFieldsChild extends ShadowedFields {
    private final int shadowed = 2;
  }

  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class ShadowedFieldsGrandchild extends ShadowedFieldsChild {
    private final int shadowed = 3;
  }

  @Test
  public void testComposition() {
    // This test verifies that cross-nesting of the special cased types works as expected.
    var input = new ArrayList<Object>();

    // An array that contains an array, map, iterable and simple object.
    var arrayInput =
        new Object[] {
          new String[] {"abc", "def"},
          ImmutableMap.<Object, Object>of(10, true, 12, 0),
          ImmutableList.<Object>of(false, 0, -1),
          new Position(15, 64)
        };
    input.add(arrayInput);

    // A map that contains an array, map, iterable and simple object.
    var mapInput = new LinkedHashMap<Object, Object>();
    mapInput.put(new Position[] {}, ImmutableMap.<Object, Object>of(0, false, 1, true, 2, false));
    mapInput.put(ImmutableList.<Object>of("xyz", 0.1), new Position(1, 3));
    input.add(mapInput);

    // An iterable that contains an array, map, iterable and simple object.
    var iterableInput = new ArrayList<Object>();
    iterableInput.add(new int[] {1, 2, 3, 4, 5});
    iterableInput.add(ImmutableMap.<String, String>of("a", "A", "b", "B", "c", "C"));
    iterableInput.add(ImmutableList.<Object>of("a", 1, 0.1));
    iterableInput.add(new Position(-1, 5));
    input.add(iterableInput);

    // An object that contains an array, map, iterable and simple object.
    input.add(new CompositeObject());

    assertThat(dumpStructure(input))
        .isEqualTo(
            "java.util.ArrayList(0) [\n"
                + "  java.lang.Object[](1) [\n"
                + "    java.lang.String[](2) [abc, def]\n"
                + "    com.google.common.collect.RegularImmutableMap(3) [\n"
                + "      key=10\n"
                + "      value=true\n"
                + "      key=12\n"
                + "      value=0\n"
                + "    ]\n"
                + "    com.google.common.collect.ImmutableList(4) [\n"
                + "      false\n"
                + "      0\n"
                + "      -1\n"
                + "    ]\n"
                + ("    " + NAMESPACE + ".Position(5) [\n")
                + "      x=15\n"
                + "      y=64\n"
                + "    ]\n"
                + "  ]\n"
                + "  java.util.LinkedHashMap(6) [\n"
                + ("    key=" + NAMESPACE + ".Position[](7) []\n")
                + "    value=com.google.common.collect.RegularImmutableMap(8) [\n"
                + "      key=0\n"
                + "      value=false\n"
                + "      key=1\n"
                + "      value=true\n"
                + "      key=2\n"
                + "      value=false\n"
                + "    ]\n"
                + "    key=com.google.common.collect.ImmutableList(9) [\n"
                + "      xyz\n"
                + "      0.1\n"
                + "    ]\n"
                + ("    value=" + NAMESPACE + ".Position(10) [\n")
                + "      x=1\n"
                + "      y=3\n"
                + "    ]\n"
                + "  ]\n"
                + "  java.util.ArrayList(11) [\n"
                + "    int[](12) [1, 2, 3, 4, 5]\n"
                + "    com.google.common.collect.RegularImmutableMap(13) [\n"
                + "      key=a\n"
                + "      value=A\n"
                + "      key=b\n"
                + "      value=B\n"
                + "      key=c\n"
                + "      value=C\n"
                + "    ]\n"
                + "    com.google.common.collect.ImmutableList(14) [\n"
                + "      a\n"
                + "      1\n"
                + "      0.1\n"
                + "    ]\n"
                + ("    " + NAMESPACE + ".Position(15) [\n")
                + "      x=-1\n"
                + "      y=5\n"
                + "    ]\n"
                + "  ]\n"
                + ("  " + NAMESPACE + ".CompositeObject(16) [\n")
                + "    doubles=double[](17) [0.1, 0.2, 0.3]\n"
                + "    iterable=com.google.common.collect.ImmutableList(18) [\n"
                + "      interface java.lang.Runnable\n"
                + "      class java.lang.Thread\n"
                + "    ]\n"
                + "    map=com.google.common.collect.RegularImmutableMap(19) [\n"
                + "      key=x\n"
                + "      value=0\n"
                + "      key=y\n"
                + "      value=1\n"
                + "    ]\n"
                + ("    obj=" + NAMESPACE + ".Position(20) [\n")
                + "      x=12\n"
                + "      y=13\n"
                + "    ]\n"
                + "  ]\n"
                + "]");
  }

  /** A class that contains an array, map, iterable and simple object. */
  @SuppressWarnings({"UnusedVariable", "FieldCanBeStatic"})
  private static class CompositeObject {
    private final double[] doubles = new double[] {0.1, 0.2, 0.3};
    private final ImmutableMap<String, Integer> map = ImmutableMap.of("x", 0, "y", 1);
    private final ImmutableList<Class<?>> iterable = ImmutableList.of(Runnable.class, Thread.class);
    private final Position obj = new Position(12, 13);
  }

  @Test
  public void equivalenceReduction() {
    var subject = ImmutableList.of(new Position(4, 5), new Position(4, 5));

    assertThat(dumpStructure(subject))
        .isEqualTo(
            """
com.google.common.collect.ImmutableList(0) [
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1) [
    x=4
    y=5
  ]
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(2) [
    x=4
    y=5
  ]
]\
""");

    // With equivalence reduction, the duplicate position is turned into a backreference.
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo(
            """
com.google.common.collect.ImmutableList(0) [
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1) [
    x=4
    y=5
  ]
  com.google.devtools.build.lib.skyframe.serialization.testutils.DumperTest.Position(1)
]\
""");
  }

  @Test
  public void equivalentCycles() {
    // `cycle1` and `cycle2` are equivalent, but different references.

    // Note that both `cycle1` and `cycle2` have strong self-symmetry and exercise a special
    // fallback codepath in `Fingerprinter.handleStronglyConnectedComponent`.
    var cycle1 = new ArrayList<Object>();
    var one = new ArrayList<Object>();
    cycle1.add(one);
    one.add(cycle1);

    var cycle2 = new ArrayList<Object>();
    var two = new ArrayList<Object>();
    cycle2.add(two);
    two.add(cycle2);

    var subject = ImmutableList.of(cycle1, cycle2);

    assertThat(dumpStructure(subject))
        .isEqualTo(
            """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                java.util.ArrayList(2) [
                  java.util.ArrayList(1)
                ]
              ]
              java.util.ArrayList(3) [
                java.util.ArrayList(4) [
                  java.util.ArrayList(3)
                ]
              ]
            ]\
            """);
    // Equivalence reduction deduplicates the 2nd cycle.
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo(
            """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                java.util.ArrayList(1)
              ]
              java.util.ArrayList(1)
            ]\
            """);
  }

  @Test
  public void emptyArray_dumps() {
    var subject = new Integer[0];
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo("java.lang.Integer[](0) []");
  }

  @Test
  public void nullContainingArray_dumps() {
    var subject = new Object[] {null, true, null, false};
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo(
            """
java.lang.Object[](0) [
  null
  true
  null
  false
]\
""");
  }

  @Test
  public void rotations_areDeduplicated() {
    // `cycle1` and `cycle2` are isomorphic, but rotated.
    var cycle1 = new ArrayList<>();
    var one = new ArrayList<>();
    cycle1.add(1);
    cycle1.add(one);
    one.add(2);
    one.add(cycle1);

    var cycle2 = new ArrayList<>();
    var two = new ArrayList<>();
    cycle2.add(2);
    cycle2.add(two);
    two.add(1);
    two.add(cycle2);

    var subject = ImmutableList.of(cycle1, cycle2);
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo(
            """
com.google.common.collect.ImmutableList(0) [
  java.util.ArrayList(1) [
    1
    java.util.ArrayList(2) [
      2
      java.util.ArrayList(1)
    ]
  ]
  java.util.ArrayList(2)
]\
""");
  }

  @Test
  public void referenceRotationsDeduplicated() {
    // This test case contrasts the previous test case. The rotation can't be deduplicated by
    // fingerprint, but it can still be deduplicated by reference.

    var cycle = new ArrayList<Object>();
    var one = new ArrayList<Object>();
    cycle.add('A');
    cycle.add(one);
    one.add('B');
    one.add(cycle);

    var subject = ImmutableList.of(cycle, one);
    assertThat(dumpStructureWithEquivalenceReduction(subject))
        .isEqualTo(
            """
            com.google.common.collect.ImmutableList(0) [
              java.util.ArrayList(1) [
                A
                java.util.ArrayList(2) [
                  B
                  java.util.ArrayList(1)
                ]
              ]
              java.util.ArrayList(2)
            ]\
            """);
  }

  /** An arbitrary class used as test data. */
  private record Position(int x, int y) {}
}
