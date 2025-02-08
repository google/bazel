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

package com.google.devtools.build.lib.rules.platform;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link Toolchain}. */
@RunWith(JUnit4.class)
public class ToolchainTest extends BuildViewTestCase {

  @Before
  public void createConstraints() throws Exception {
    scratch.file(
        "constraint/BUILD",
        """
        constraint_setting(name = "basic")

        constraint_value(
            name = "foo",
            constraint_setting = ":basic",
        )

        constraint_value(
            name = "bar",
            constraint_setting = ":basic",
        )
        """);
  }

  @Test
  public void testToolchain() throws Exception {
    scratch.file(
        "toolchain/toolchain_def.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        toolchain_def = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        """);
    scratch.file(
        "toolchain/BUILD",
        """
        load(":toolchain_def.bzl", "toolchain_def")

        toolchain_type(name = "demo_toolchain")

        toolchain(
            name = "toolchain1",
            exec_compatible_with = ["//constraint:foo"],
            target_compatible_with = ["//constraint:bar"],
            toolchain = ":toolchain_def1",
            toolchain_type = ":demo_toolchain",
        )

        toolchain_def(
            name = "toolchain_def1",
            data = "foo",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//toolchain:toolchain1");
    assertThat(target).isNotNull();

    DeclaredToolchainInfo provider = PlatformProviderUtils.declaredToolchainInfo(target);
    assertThat(provider).isNotNull();
    assertThat(provider.toolchainType())
        .isEqualTo(
            ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:demo_toolchain")));

    ConstraintSettingInfo basicConstraintSetting =
        ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:basic"));
    assertThat(provider.execConstraints().get(basicConstraintSetting))
        .isEqualTo(
            ConstraintValueInfo.create(
                basicConstraintSetting, Label.parseCanonicalUnchecked("//constraint:foo")));
    assertThat(provider.targetConstraints().get(basicConstraintSetting))
        .isEqualTo(
            ConstraintValueInfo.create(
                basicConstraintSetting, Label.parseCanonicalUnchecked("//constraint:bar")));

    assertThat(provider.resolvedToolchainLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"));
    assertThat(provider.targetLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain1"));
  }

  @Test
  public void testToolchain_targetSetting_matching() throws Exception {
    useConfiguration("--compilation_mode=opt");
    scratch.file(
        "toolchain/toolchain_def.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        toolchain_def = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        """);
    scratch.file(
        "toolchain/BUILD",
        """
        load(":toolchain_def.bzl", "toolchain_def")

        toolchain_type(name = "demo_toolchain")

        config_setting(
            name = "optimised",
            values = {"compilation_mode": "opt"},
        )

        toolchain(
            name = "toolchain1",
            target_settings = [":optimised"],
            toolchain = ":toolchain_def1",
            toolchain_type = ":demo_toolchain",
        )

        toolchain_def(
            name = "toolchain_def1",
            data = "foo",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//toolchain:toolchain1");
    DeclaredToolchainInfo provider = PlatformProviderUtils.declaredToolchainInfo(target);

    assertThat(target).isNotNull();
    assertThat(provider).isNotNull();
    assertThat(provider.toolchainType())
        .isEqualTo(
            ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:demo_toolchain")));
    // Ensure target settings completely matches (and not just vacuously e.g. if somehow empty)
    assertThat(provider.targetSettings()).isNotEmpty();
    assertThat(
            provider.targetSettings().stream()
                .allMatch(x -> x.result().equals(ConfigMatchingProvider.MatchResult.MATCH)))
        .isTrue();
    assertThat(
            provider.targetSettings().stream()
                .anyMatch(x -> x.result() instanceof ConfigMatchingProvider.MatchResult.InError))
        .isFalse();
    assertThat(provider.resolvedToolchainLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"));
    assertThat(provider.targetLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain1"));
  }

  @Test
  public void testToolchain_targetSetting_nonmatching() throws Exception {
    useConfiguration("--compilation_mode=fastbuild");
    scratch.file(
        "toolchain/toolchain_def.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        toolchain_def = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        """);
    scratch.file(
        "toolchain/BUILD",
        """
        load(":toolchain_def.bzl", "toolchain_def")

        toolchain_type(name = "demo_toolchain")

        config_setting(
            name = "optimised",
            values = {"compilation_mode": "opt"},
        )

        toolchain(
            name = "toolchain1",
            target_settings = [":optimised"],
            toolchain = ":toolchain_def1",
            toolchain_type = ":demo_toolchain",
        )

        toolchain_def(
            name = "toolchain_def1",
            data = "foo",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//toolchain:toolchain1");
    DeclaredToolchainInfo provider = PlatformProviderUtils.declaredToolchainInfo(target);

    assertThat(target).isNotNull();
    assertThat(provider).isNotNull();
    assertThat(provider.toolchainType())
        .isEqualTo(
            ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//toolchain:demo_toolchain")));
    assertThat(
            provider.targetSettings().stream()
                .anyMatch(x -> x.result().equals(ConfigMatchingProvider.MatchResult.MATCH)))
        .isFalse();
    assertThat(provider.resolvedToolchainLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain_def1"));
    assertThat(provider.targetLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//toolchain:toolchain1"));
  }

  @Test
  public void ruleDefinitionIncorrectlyDependsOnToolchainInstance() throws Exception {
    scratch.file(
        "toolchain/toolchain_def.bzl",
        """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        toolchain_def = rule(
            implementation = _impl,
            attrs = {},
        )
        """);
    scratch.file(
        "toolchain/BUILD",
        """
        load(":toolchain_def.bzl", "toolchain_def")

        toolchain_type(name = "demo_toolchain")

        toolchain(
            name = "toolchain1",
            exec_compatible_with = ["//constraint:foo"],
            target_compatible_with = ["//constraint:bar"],
            toolchain = ":toolchain_def1",
            toolchain_type = ":demo_toolchain",
        )

        toolchain_def(name = "toolchain_def1")
        """);
    scratch.file(
        "rule/rule_def.bzl",
        """
        def _impl(ctx):
            pass

        my_rule = rule(
            implementation = _impl,
            toolchains = ["//toolchain:toolchain1"],
        )
        """);
    scratch.file(
        "rule/BUILD",
        """
        load("//rule:rule_def.bzl", "my_rule")

        my_rule(name = "me")
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    ConfiguredTarget target = getConfiguredTarget("//rule:me");
    assertThat(target).isNull();
    assertContainsEvent(
        "Target //toolchain:toolchain1 was referenced as a toolchain type, but is a toolchain "
            + "instance. Is the rule definition for the target you're building setting "
            + "\"toolchains =\" to a toolchain() instead of the expected toolchain_type()?");
  }
}
