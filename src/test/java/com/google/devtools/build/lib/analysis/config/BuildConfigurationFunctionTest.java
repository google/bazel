// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BuildConfigurationFunction}'s special behaviors. */
@RunWith(JUnit4.class)
public final class BuildConfigurationFunctionTest extends BuildViewTestCase {

  @Before
  public void setupMyInfo() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private static StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget)
      throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  private void writeAllowlistFile() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        """);
  }

  private void writeBuildSettingsBzl() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        BuildSettingInfo = provider(fields = ["value"])

        def _impl(ctx):
            return [BuildSettingInfo(value = ctx.build_setting_value)]

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
  }

  private CoreOptions getCoreOptions(ConfiguredTarget target) {
    return getConfiguration(target).getOptions().get(CoreOptions.class);
  }

  private String getMnemonic(ConfiguredTarget target) {
    return getConfiguration(target).getMnemonic();
  }

  @Test
  public void testDiffAgainstBaselineOutputScheme_hasHash() throws Exception {
    writeAllowlistFile();
    writeBuildSettingsBzl();
    scratch.file(
        "test/transitions.bzl",
        """
        def _foo_impl(settings, attr):
            return {"//test:foo": "transitioned"}

        foo_transition = transition(
            implementation = _foo_impl,
            inputs = [],
            outputs = ["//test:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = foo_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule", "simple")

        string_flag(
            name = "foo",
            build_setting_default = "default",
        )

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        """);

    useConfiguration("--experimental_output_directory_naming_scheme=diff_against_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getMnemonic(test)).doesNotContain("-ST-");
    assertThat(getCoreOptions(test).affectedByStarlarkTransition).isEmpty();

    @SuppressWarnings("unchecked")
    ConfiguredTarget dep =
        Iterables.getOnlyElement(
            (List<ConfiguredTarget>) getMyInfoFromTarget(test).getValue("dep"));

    assertThat(getMnemonic(dep))
        .endsWith(
            OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                ImmutableList.of("//test:foo=transitioned")));
    assertThat(getCoreOptions(dep).affectedByStarlarkTransition).isEmpty();
  }

  @Test
  public void testDiffAgainstBaselineOutputScheme_avoidHashForInExplicitOutputPath()
      throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        """
        def _opt_impl(settings, attr):
            return {"//command_line_option:compilation_mode": "opt"}

        opt_transition = transition(
            implementation = _opt_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "opt_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = opt_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule", "simple")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        simple(name = "dep")
        """);

    useConfiguration(
        "--compilation_mode=fastbuild",
        "--experimental_output_directory_naming_scheme=diff_against_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getConfiguration(test).getMnemonic()).contains("fastbuild");
    assertThat(getMnemonic(test)).doesNotContain("-ST-");
    assertThat(getCoreOptions(test).affectedByStarlarkTransition).isEmpty();

    @SuppressWarnings("unchecked")
    ConfiguredTarget dep =
        Iterables.getOnlyElement(
            (List<ConfiguredTarget>) getMyInfoFromTarget(test).getValue("dep"));

    assertThat(getConfiguration(dep).getMnemonic()).contains("opt");
    assertThat(getMnemonic(dep)).doesNotContain("-ST-");
    assertThat(getCoreOptions(dep).affectedByStarlarkTransition).isEmpty();
  }

  @Test
  public void testDiffAgainstBaselineOutputScheme_abaAvoidsHash() throws Exception {
    writeAllowlistFile();
    writeBuildSettingsBzl();
    scratch.file(
        "test/transitions.bzl",
        """
        def _toggle_impl(settings, attr):
            if (settings["//test:foo"] != "default"):
                return {"//test:foo": "default"}
            else:
                return {"//test:foo": "transitioned"}

        toggle_foo_transition = transition(
            implementation = _toggle_impl,
            inputs = ["//test:foo"],
            outputs = ["//test:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "toggle_foo_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = toggle_foo_transition),
            },
        )

        def _basic_impl(ctx):
            return []

        simple = rule(_basic_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule", "simple")

        string_flag(
            name = "foo",
            build_setting_default = "default",
        )

        my_rule(
            name = "test",
            dep = ":middle",
        )

        my_rule(
            name = "middle",
            dep = ":root",
        )

        simple(name = "root")
        """);

    useConfiguration("--experimental_output_directory_naming_scheme=diff_against_dynamic_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getMnemonic(test)).doesNotContain("-ST-");
    assertThat(getCoreOptions(test).affectedByStarlarkTransition).isEmpty();

    @SuppressWarnings("unchecked")
    ConfiguredTarget middle =
        Iterables.getOnlyElement(
            (List<ConfiguredTarget>) getMyInfoFromTarget(test).getValue("dep"));

    assertThat(getMnemonic(middle))
        .endsWith(
            OutputPathMnemonicComputer.transitionDirectoryNameFragment(
                ImmutableList.of("//test:foo=transitioned")));
    assertThat(getCoreOptions(middle).affectedByStarlarkTransition).isEmpty();

    @SuppressWarnings("unchecked")
    ConfiguredTarget root =
        Iterables.getOnlyElement(
            (List<ConfiguredTarget>) getMyInfoFromTarget(middle).getValue("dep"));

    assertThat(getMnemonic(test)).doesNotContain("-ST-");
    assertThat(getCoreOptions(test).affectedByStarlarkTransition).isEmpty();

    assertThat(getConfiguration(test)).isEqualTo(getConfiguration(root));
    assertThat(getConfiguration(test)).isNotEqualTo(getConfiguration(middle));

    // This should be implied by everything else but as a final check....
    assertThat(getConfiguration(test).getMnemonic())
        .isEqualTo(getConfiguration(root).getMnemonic());
  }

  @Test
  public void testPlatformExplicitInOutputDirAndDynamicBaseline_withPlatformMappings()
      throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        """
        def _platform_impl(settings, attr):
            return {"//command_line_option:platforms": [attr.platform]}

        platform_transition = transition(
            implementation = _platform_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "platform_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )

        def _basic_impl(ctx):
            return []

        as_platform = rule(
            implementation = _basic_impl,
            cfg = platform_transition,
            attrs = {
                "platform": attr.label(default = "//platforms:alpha"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "as_platform", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        as_platform(
            name = "dep",
            platform = "//platforms:beta",
        )
        """);
    scratch.file(
        "platforms/BUILD",
        """
        platform(name = "alpha")

        platform(name = "beta")
        """);
    scratch.file(
        "tools/platform_mappings",
        "platforms:",
        "  //platforms:alpha",
        "    --cpu=alpha",
        "  //platforms:beta",
        "    --cpu=beta",
        "flags:",
        "  --cpu=alpha",
        "    //platforms:alpha",
        "  --cpu=beta",
        "    //platforms:beta");

    useConfiguration(
        "--compilation_mode=fastbuild",
        "--platforms=//platforms:alpha",
        "--platform_mappings=tools/platform_mappings",
        "--experimental_platform_in_output_dir",
        "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
        "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha",
        "--experimental_override_name_platform_in_output_dir=//platforms:beta=beta",
        "--experimental_output_directory_naming_scheme=diff_against_dynamic_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getMnemonic(test)).contains("alpha-fastbuild");
    assertThat(getMnemonic(test)).doesNotContain("-ST-");

    ConfiguredTarget dep = (ConfiguredTarget) getMyInfoFromTarget(test).getValue("dep");

    assertThat(getMnemonic(dep)).contains("beta-fastbuild");
    assertThat(getMnemonic(dep)).doesNotContain("-ST-");

    // Verify platform_mappings applied properly
    assertThat(getConfiguration(test).getCpu()).isEqualTo("alpha");
    assertThat(getConfiguration(dep).getCpu()).isEqualTo("beta");
  }

  @Test
  public void testPlatformExplicitInOutputDirAndDynamicBaseline_withMorePlatformMappings()
      throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/transitions.bzl",
        """
        def _platform_impl(settings, attr):
            return {"//command_line_option:platforms": [attr.platform]}

        platform_transition = transition(
            implementation = _platform_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("//test:transitions.bzl", "platform_transition")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(),
            },
        )

        def _basic_impl(ctx):
            return []

        as_platform = rule(
            implementation = _basic_impl,
            cfg = platform_transition,
            attrs = {
                "platform": attr.label(default = "//platforms:alpha"),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "as_platform", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        as_platform(
            name = "dep",
            platform = "//platforms:beta",
        )
        """);
    scratch.file(
        "platforms/BUILD",
        """
        platform(name = "alpha")

        platform(name = "beta")
        """);

    // Test just wants to transition some options not usually explicitly in the output path
    // so if those options are changed/removed, just replace them here.
    scratch.file(
        "tools/platform_mappings",
        "platforms:",
        "  //platforms:alpha",
        "    --cpu=alpha",
        "    --use_ijars=false",
        "    --dynamic_mode=default",
        "  //platforms:beta",
        "    --cpu=beta",
        "    --use_ijars=true",
        "    --dynamic_mode=off");

    useConfiguration(
        "--compilation_mode=fastbuild",
        "--platforms=//platforms:alpha",
        "--platform_mappings=tools/platform_mappings",
        "--experimental_platform_in_output_dir",
        "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
        "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha",
        "--experimental_override_name_platform_in_output_dir=//platforms:beta=beta",
        "--experimental_output_directory_naming_scheme=diff_against_dynamic_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getMnemonic(test)).contains("alpha-fastbuild");
    assertThat(getMnemonic(test)).doesNotContain("-ST-");

    ConfiguredTarget dep = (ConfiguredTarget) getMyInfoFromTarget(test).getValue("dep");

    assertThat(getMnemonic(dep)).contains("beta-fastbuild");
    assertThat(getMnemonic(dep)).doesNotContain("-ST-");

    // Verify platform_mappings applied properly
    assertThat(getConfiguration(test).getCpu()).isEqualTo("alpha");
    assertThat(getConfiguration(test).getFragment(CppConfiguration.class).getDynamicModeFlag())
        .isEqualTo(CppConfiguration.DynamicMode.DEFAULT);
    assertThat(getConfiguration(test).getFragment(JavaConfiguration.class).getUseIjars()).isFalse();
    assertThat(getConfiguration(dep).getCpu()).isEqualTo("beta");
    assertThat(getConfiguration(dep).getFragment(CppConfiguration.class).getDynamicModeFlag())
        .isEqualTo(CppConfiguration.DynamicMode.OFF);
    assertThat(getConfiguration(dep).getFragment(JavaConfiguration.class).getUseIjars()).isTrue();
  }

  @Test
  public void testPlatformExplicitInOutputDirAndDynamicBaseline_withExecConfigDep()
      throws Exception {
    writeAllowlistFile();
    scratch.file(
        "test/rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            return MyInfo(dep = ctx.attr.dep)

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = 'exec'),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":dep",
        )

        my_rule(
            name = "dep",
        )
        """);
    scratch.file(
        "platforms/BUILD",
        """
        platform(name = "alpha")
        """);

    useConfiguration(
        "--compilation_mode=fastbuild",
        "--platforms=//platforms:alpha",
        "--host_platform=//platforms:alpha",
        "--experimental_platform_in_output_dir",
        "--noexperimental_use_platforms_in_output_dir_legacy_heuristic",
        "--experimental_override_name_platform_in_output_dir=//platforms:alpha=alpha-override",
        "--experimental_output_directory_naming_scheme=diff_against_dynamic_baseline");
    ConfiguredTarget test = getConfiguredTarget("//test");

    assertThat(getMnemonic(test)).contains("alpha-override-fastbuild");
    assertThat(getMnemonic(test)).doesNotContain("-ST-");

    ConfiguredTarget dep = (ConfiguredTarget) getMyInfoFromTarget(test).getValue("dep");

    // The platform name override is used in dep with exec config
    assertThat(getMnemonic(dep)).contains("alpha-override-opt-exec");
    assertThat(getMnemonic(dep)).doesNotContain("-ST-");
  }
}
