// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.platform;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link DeclaredToolchainInfo}. */
@RunWith(JUnit4.class)
public class DeclaredToolchainInfoTest extends BuildViewTestCase {

  @Test
  public void toolchainInfo_overlappingConstraintsError() throws Exception {
    ConstraintSettingInfo setting1 =
        ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:basic"));
    ConstraintSettingInfo setting2 =
        ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:complex"));

    DeclaredToolchainInfo.Builder builder = DeclaredToolchainInfo.builder();

    builder.addExecConstraints(
        ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:value1")));
    builder.addExecConstraints(
        ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:value2")));

    builder.addTargetConstraints(
        ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value3")));
    builder.addTargetConstraints(
        ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value4")));
    builder.addTargetConstraints(
        ConstraintValueInfo.create(setting2, Label.parseCanonicalUnchecked("//constraint:value5")));

    DeclaredToolchainInfo.DuplicateConstraintException exception =
        assertThrows(DeclaredToolchainInfo.DuplicateConstraintException.class, builder::build);
    assertThat(exception.execConstraintsException()).isNotNull();
    assertThat(exception.execConstraintsException())
        .hasMessageThat()
        .contains(
            "Duplicate constraint values detected: "
                + "constraint_setting //constraint:basic has "
                + "[//constraint:value1, //constraint:value2]");
    assertThat(exception.targetConstraintsException()).isNotNull();
    assertThat(exception.targetConstraintsException())
        .hasMessageThat()
        .contains(
            "Duplicate constraint values detected: "
                + "constraint_setting //constraint:complex has "
                + "[//constraint:value3, //constraint:value4, //constraint:value5]");
  }

  @Test
  public void toolchainInfo_equalsTester() throws Exception {
    ConstraintSettingInfo setting1 =
        ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:setting1"));
    ConstraintValueInfo constraint1 =
        ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:foo"));
    ConstraintValueInfo constraint2 =
        ConstraintValueInfo.create(setting1, Label.parseCanonicalUnchecked("//constraint:bar"));

    new EqualsTester()
        .addEqualityGroup(
            // Base case.
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc1")))
                .addExecConstraints(ImmutableList.of(constraint1))
                .addTargetConstraints(ImmutableList.of(constraint2))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain1"))
                .build(),
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc1")))
                .addExecConstraints(ImmutableList.of(constraint1))
                .addTargetConstraints(ImmutableList.of(constraint2))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain1"))
                .build())
        .addEqualityGroup(
            // Different type.
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc2")))
                .addExecConstraints(ImmutableList.of(constraint1))
                .addTargetConstraints(ImmutableList.of(constraint2))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain1"))
                .build())
        .addEqualityGroup(
            // Different constraints.
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc1")))
                .addExecConstraints(ImmutableList.of(constraint2))
                .addTargetConstraints(ImmutableList.of(constraint1))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain1"))
                .build())
        .addEqualityGroup(
            // Different toolchain label.
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc1")))
                .addExecConstraints(ImmutableList.of(constraint1))
                .addTargetConstraints(ImmutableList.of(constraint2))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def2"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain1"))
                .build())
        .addEqualityGroup(
            // Different target label.
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:tc1")))
                .addExecConstraints(ImmutableList.of(constraint1))
                .addTargetConstraints(ImmutableList.of(constraint2))
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"))
                .targetLabel(Label.parseCanonicalUnchecked("//toolchain:toolchain2"))
                .build())
        .testEquals();
  }
}
