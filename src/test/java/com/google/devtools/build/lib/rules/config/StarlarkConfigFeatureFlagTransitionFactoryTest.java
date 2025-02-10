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
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Starlark-integration Tests for the ConfigFeatureFlagTransitionFactory. */
@RunWith(JUnit4.class)
public final class StarlarkConfigFeatureFlagTransitionFactoryTest extends BuildViewTestCase {

  @Before
  public final void initializeSkyframExecutor() throws Exception {
    AnalysisMock analysisMock = AnalysisMock.get();

    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    ImmutableSortedSet<Class<? extends FragmentOptions>> buildOptionClasses =
        ruleClassProvider.getFragmentRegistry().getOptionsClasses();

    SequencedSkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    BuildOptions defaultBuildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone();
    skyframeExecutor.injectExtraPrecomputedValues(
        new ImmutableList.Builder<PrecomputedValue.Injected>()
            .add(
                PrecomputedValue.injected(
                    PrecomputedValue.BASELINE_CONFIGURATION, defaultBuildOptions))
            .addAll(analysisMock.getPrecomputedValues())
            .build());
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder().addRuleDefinition(new FeatureFlagSetterRule());
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  private void setupRulesBzl() throws Exception {
    scratch.file("rules/BUILD", "");
    scratch.file(
        "rules/rule.bzl",
        """
        def _blank_impl(ctx):
            return []

        def _check_impl(ctx):
            if ctx.attr.succeed:
                return []
            else:
                fail("Rule has failed intentionally.")

        feature_flag_setter = rule(
            attrs = {
                "flag_values": attr.label_keyed_string_dict(),
                "deps": attr.label_list(),
            },
            cfg = config_common.config_feature_flag_transition("flag_values"),
            implementation = _blank_impl,
        )
        check_something = rule(
            attrs = {
                "succeed": attr.bool(),
            },
            implementation = _check_impl,
        )
        """);
  }

  @Test
  public void setsFeatureFlagSuccessfully() throws Exception {
    setupRulesBzl();
    scratch.file(
        "foo/BUILD",
        """
        load("//rules:rule.bzl", "check_something", "feature_flag_setter")

        config_feature_flag(
            name = "fruit",
            allowed_values = [
                "orange",
                "apple",
                "lemon",
            ],
            default_value = "orange",
        )

        config_setting(
            name = "is_apple",
            flag_values = {":fruit": "apple"},
            transitive_configs = [":fruit"],
        )

        feature_flag_setter(
            name = "top",
            flag_values = {":fruit": "apple"},
            transitive_configs = [":fruit"],
            deps = [":some_dep"],
        )

        check_something(
            name = "some_dep",
            succeed = select({
                ":is_apple": True,
                "//conditions:default": False,
            }),
            transitive_configs = [":fruit"],
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            packages = ["//foo/..."],
        )

        package_group(
            name = "config_feature_flag_setter",
            packages = ["//rules/..."],
        )
        """);
    assertThat(getConfiguredTarget("//foo:top")).isNotNull();
    assertNoEvents();
  }

  @Test
  public void failsWhenFeatureFlagSuccessfullySetToBadValue() throws Exception {
    // This is mostly a test of the testing infrastructure itself.
    // Want to ensure check_something isn't spuriously passing for whatever reason.
    setupRulesBzl();
    scratch.file(
        "foo/BUILD",
        """
        load("//rules:rule.bzl", "check_something", "feature_flag_setter")

        config_feature_flag(
            name = "fruit",
            allowed_values = [
                "orange",
                "apple",
                "lemon",
            ],
            default_value = "orange",
        )

        config_setting(
            name = "is_apple",
            flag_values = {":fruit": "apple"},
            transitive_configs = [":fruit"],
        )

        feature_flag_setter(
            name = "top",
            flag_values = {":fruit": "orange"},
            transitive_configs = [":fruit"],
            deps = [":some_dep"],
        )

        check_something(
            name = "some_dep",
            succeed = select({
                ":is_apple": True,
                "//conditions:default": False,
            }),
            transitive_configs = [":fruit"],
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            packages = ["//foo/..."],
        )

        package_group(
            name = "config_feature_flag_setter",
            packages = ["//rules/..."],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:top");
    assertContainsEvent("Error in fail: Rule has failed intentionally.");
  }

  @Test
  public void failsWhenInstanceNotInAllowlist() throws Exception {
    setupRulesBzl();
    scratch.file(
        "bar/BUILD",
        """
        config_feature_flag(
            name = "fruit",
            allowed_values = [
                "orange",
                "apple",
                "lemon",
            ],
            default_value = "orange",
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load("//rules:rule.bzl", "feature_flag_setter")

        feature_flag_setter(
            name = "top",
            flag_values = {"//bar:fruit": "apple"},
            transitive_configs = ["//bar:fruit"],
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            packages = ["//bar/..."],
        )

        package_group(
            name = "config_feature_flag_setter",
            packages = ["//rules/..."],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:top");
    assertContainsEvent("the attribute flag_values is not available in this package");
  }

  @Test
  public void failsWhenRuleClassNotInSetterAllowlist() throws Exception {
    setupRulesBzl();
    scratch.file(
        "foo/BUILD",
        """
        load("//rules:rule.bzl", "feature_flag_setter")

        config_feature_flag(
            name = "fruit",
            allowed_values = [
                "orange",
                "apple",
                "lemon",
            ],
            default_value = "orange",
        )

        feature_flag_setter(
            name = "top",
            flag_values = {":fruit": "apple"},
            transitive_configs = [":fruit"],
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/config_feature_flag/BUILD",
        """
        package_group(
            name = "config_feature_flag",
            packages = ["//foo/..."],
        )

        package_group(
            name = "config_feature_flag_setter",
            packages = [],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:top");
    assertContainsEvent("rule class is not allowed access to feature flags setter transition");
  }
}
