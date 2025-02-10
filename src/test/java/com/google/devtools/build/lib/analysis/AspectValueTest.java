// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping.roundTripMemoized;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.analysis.util.TestAspects.AttributeAspect;
import com.google.devtools.build.lib.analysis.util.TestAspects.ExtraAttributeAspect;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AspectValue}. */
@RunWith(JUnit4.class)
public final class AspectValueTest extends AnalysisTestCase {

  @Test
  public void keyEquality() throws Exception {
    update();
    BuildConfigurationValue c1 = getTargetConfiguration();
    BuildConfigurationValue c2 = getExecConfiguration();
    Label l1 = Label.parseCanonical("//a:l1");
    Label l1b = Label.parseCanonical("//a:l1");
    Label l2 = Label.parseCanonical("//a:l2");
    AspectParameters i1 = new AspectParameters.Builder()
        .addAttribute("foo", "bar")
        .build();
    AspectParameters i1b = new AspectParameters.Builder()
        .addAttribute("foo", "bar")
        .build();
    AspectParameters i2 = new AspectParameters.Builder()
        .addAttribute("foo", "baz")
        .build();
    AttributeAspect a1 = TestAspects.ATTRIBUTE_ASPECT;
    AttributeAspect a1b = TestAspects.ATTRIBUTE_ASPECT;
    ExtraAttributeAspect a2 = TestAspects.EXTRA_ATTRIBUTE_ASPECT;

    // label: //a:l1 or //a:l2
    // baseConfiguration: target or exec
    // aspect: Attribute or ExtraAttribute
    // parameters: bar or baz

    new EqualsTester()
        .addEqualityGroup(
            createKey(l1, c1, a1, i1),
            createKey(l1, c1, a1, i1b),
            createKey(l1, c1, a1b, i1),
            createKey(l1, c1, a1b, i1b),
            createKey(l1b, c1, a1, i1),
            createKey(l1b, c1, a1, i1b),
            createKey(l1b, c1, a1b, i1),
            createKey(l1b, c1, a1b, i1b))
        .addEqualityGroup(
            createKey(l1, c1, a1, i2),
            createKey(l1, c1, a1b, i2),
            createKey(l1b, c1, a1, i2),
            createKey(l1b, c1, a1b, i2))
        .addEqualityGroup(
            createKey(l1, c1, a2, i1),
            createKey(l1, c1, a2, i1b),
            createKey(l1b, c1, a2, i1),
            createKey(l1b, c1, a2, i1b))
        .addEqualityGroup(createKey(l1, c1, a2, i2), createKey(l1b, c1, a2, i2))
        .addEqualityGroup(
            createKey(l1, c2, a1, i1),
            createKey(l1, c2, a1, i1b),
            createKey(l1, c2, a1b, i1),
            createKey(l1, c2, a1b, i1b),
            createKey(l1b, c2, a1, i1),
            createKey(l1b, c2, a1, i1b),
            createKey(l1b, c2, a1b, i1),
            createKey(l1b, c2, a1b, i1b))
        .addEqualityGroup(
            createKey(l1, c2, a1, i2),
            createKey(l1, c2, a1b, i2),
            createKey(l1b, c2, a1, i2),
            createKey(l1b, c2, a1b, i2))
        .addEqualityGroup(
            createKey(l1, c2, a2, i1),
            createKey(l1, c2, a2, i1b),
            createKey(l1b, c2, a2, i1),
            createKey(l1b, c2, a2, i1b))
        .addEqualityGroup(createKey(l1, c2, a2, i2), createKey(l1b, c2, a2, i2))
        .addEqualityGroup(
            createKey(l2, c1, a1, i1),
            createKey(l2, c1, a1, i1b),
            createKey(l2, c1, a1b, i1),
            createKey(l2, c1, a1b, i1b))
        .addEqualityGroup(createKey(l2, c1, a1, i2), createKey(l2, c1, a1b, i2))
        .addEqualityGroup(createKey(l2, c1, a2, i1), createKey(l2, c1, a2, i1b))
        .addEqualityGroup(createKey(l2, c1, a2, i2))
        .addEqualityGroup(
            createKey(l2, c2, a1, i1),
            createKey(l2, c2, a1, i1b),
            createKey(l2, c2, a1b, i1),
            createKey(l2, c2, a1b, i1b))
        .addEqualityGroup(createKey(l2, c2, a1, i2), createKey(l2, c2, a1b, i2))
        .addEqualityGroup(createKey(l2, c2, a2, i1), createKey(l2, c2, a2, i1b))
        .addEqualityGroup(createKey(l2, c2, a2, i2))
        .addEqualityGroup(
            createDerivedKey(l1, c1, a1, i1, a2, i2), createDerivedKey(l1, c1, a1, i1b, a2, i2))
        .addEqualityGroup(
            createDerivedKey(l1, c1, a2, i1, a1, i2), createDerivedKey(l1, c1, a2, i1b, a1, i2))
        .testEquals();
  }

  @Test
  public void roundTrippingEmptyAspectParameters_outputsSingleInstance() throws Exception {
    var subject =
        ImmutableList.of(
            new AspectParameters.Builder().build(), new AspectParameters.Builder().build());
    // Empty parameters has its own singleton serialization constant.
    assertThat(subject.get(0)).isSameInstanceAs(subject.get(1));
    var deserialized = roundTripMemoized(subject, AutoRegistry.get());
    // It's preserved by round tripping.
    assertThat(deserialized.get(0)).isSameInstanceAs(subject.get(0));
    assertThat(deserialized.get(1)).isSameInstanceAs(subject.get(0));
  }

  @Test
  public void roundTripping_mergesEquivalentAspectParameters() throws Exception {
    var subject =
        ImmutableList.of(
            new AspectParameters.Builder().addAttribute("abc", "def").build(),
            new AspectParameters.Builder().addAttribute("abc", "def").build());
    assertThat(subject.get(0)).isNotSameInstanceAs(subject.get(1));
    var deserialized = roundTripMemoized(subject, AutoRegistry.get());
    assertThat(deserialized.get(0)).isSameInstanceAs(deserialized.get(1));
  }

  private static AspectKey createKey(
      Label label,
      BuildConfigurationValue baseConfiguration,
      NativeAspectClass aspectClass,
      AspectParameters parameters) {
    return AspectKeyCreator.createAspectKey(
        AspectDescriptor.of(aspectClass, parameters),
        ConfiguredTargetKey.builder().setLabel(label).setConfiguration(baseConfiguration).build());
  }

  private static SkyKey createDerivedKey(
      Label label,
      BuildConfigurationValue baseConfiguration,
      NativeAspectClass aspectClass1,
      AspectParameters parameters1,
      NativeAspectClass aspectClass2,
      AspectParameters parameters2) {
    AspectKey baseKey = createKey(label, baseConfiguration, aspectClass1, parameters1);
    return AspectKeyCreator.createAspectKey(
        AspectDescriptor.of(aspectClass2, parameters2),
        ImmutableList.of(baseKey),
        ConfiguredTargetKey.builder().setLabel(label).setConfiguration(baseConfiguration).build());
  }
}
