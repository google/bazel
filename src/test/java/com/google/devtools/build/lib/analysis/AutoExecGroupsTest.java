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

package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.cpp.CppRuleClasses.CPP_LINK_EXEC_GROUP;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ObjectArrays;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.analysis.actions.LazyWritePathsFileAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.java.JavaCompileAction;
import com.google.devtools.build.lib.rules.java.JavaGenJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for automatic exec groups. */
@RunWith(TestParameterInjector.class)
public class AutoExecGroupsTest extends BuildViewTestCase {
  /**
   * Sets up two toolchains types, each with a single toolchain implementation and a single
   * exec_compatible_with platform.
   *
   * <p>toolchain_type_1 -> foo_toolchain -> exec_compatible_with platform_1; toolchain_type_1 ->
   * baz_toolchain -> exec_compatible_with platform_2; toolchain_type_2 -> bar_toolchain ->
   * exec_compatible_with platform_2
   */
  public void createToolchainsAndPlatforms() throws Exception {
    scratch.overwriteFile(
        "rule/test_toolchain.bzl",
        """
        def _impl(ctx):
            return [platform_common.ToolchainInfo(
                tool = ctx.executable._tool,
                files_to_run = ctx.attr._tool[DefaultInfo].files_to_run,
            )]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "_tool": attr.label(
                    default = "//toolchain:b_tool",
                    executable = True,
                    cfg = "exec",
                ),
            },
        )
        """);
    scratch.overwriteFile(
        "rule/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_runtime")
        exports_files(["test_toolchain/bzl"])

        toolchain_type(name = "toolchain_type_1")

        toolchain_type(name = "toolchain_type_2")

        java_runtime(
            name = "jvm-k8",
            srcs = [
                "k8/a",
                "k8/b",
            ],
            java_home = "k8",
        )
        """);
    scratch.overwriteFile(
        "toolchain/BUILD",
        """
        load("//rule:test_toolchain.bzl", "test_toolchain")

        genrule(
            name = "a_tool",
            outs = ["atool"],
            cmd = "",
            executable = True,
        )

        genrule(
            name = "b_tool",
            outs = ["btool"],
            cmd = "",
            executable = True,
        )

        test_toolchain(
            name = "foo",
        )

        toolchain(
            name = "foo_toolchain",
            exec_compatible_with = ["//platforms:constraint_1"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        toolchain(
            name = "baz_toolchain",
            exec_compatible_with = ["//platforms:constraint_2"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        toolchain(
            name = "qux_toolchain",
            exec_compatible_with = ["//platforms:constraint_3"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        test_toolchain(
            name = "bar",
        )

        toolchain(
            name = "bar_toolchain",
            exec_compatible_with = ["//platforms:constraint_2"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":bar",
            toolchain_type = "//rule:toolchain_type_2",
        )

        toolchain(
            name = "quz_toolchain",
            exec_compatible_with = ["//platforms:constraint_4"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":bar",
            toolchain_type = "//rule:toolchain_type_2",
        )
        """);

    scratch.overwriteFile(
        "platforms/BUILD",
        """
        constraint_setting(name = "setting")

        constraint_value(
            name = "constraint_1",
            constraint_setting = ":setting",
        )

        constraint_value(
            name = "constraint_2",
            constraint_setting = ":setting",
        )

        constraint_value(
            name = "constraint_3",
            constraint_setting = ":setting",
        )

        constraint_value(
            name = "constraint_4",
            constraint_setting = ":setting",
        )

        platform(
            name = "platform_1",
            constraint_values = [":constraint_1"],
        )

        platform(
            name = "platform_2",
            constraint_values = [":constraint_2"],
            exec_properties = {
                "watermelon.ripeness": "unripe",
                "watermelon.color": "red",
            },
        )

        platform(
            name = "platform_3",
            constraint_values = [":constraint_3"],
        )

        platform(
            name = "platform_4",
            constraint_values = [":constraint_4"],
        )
        """);

    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withToolchainTargetConstraints()
                .withToolchainExecConstraints()
                .withCpu("fake"));
  }

  @Before
  public void setup() throws Exception {
    useConfiguration();
  }

  @Override
  public void useConfiguration(String... args) throws Exception {
    // These need to be defined before the configuration is parsed.
    createToolchainsAndPlatforms();
    String[] flags = {
      "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain,//toolchain:baz_toolchain,//toolchain:quz_toolchain,toolchain:qux_toolchain",
      "--platforms=//platforms:platform_1",
      "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2,//platforms:platform_3,//platforms:platform_4",
      "--incompatible_enable_cc_toolchain_resolution"
    };

    super.useConfiguration(ObjectArrays.concat(flags, args, String.class));
  }

  /**
   * Creates custom rule which produces action with `actionParameters`, adds `extraAttributes`,
   * defines `toolchains`, and adds custom exec groups from `execGroups`. Depending on
   * `actionRunCommand` parameter, `actions.run` or `actions.run_shell` is created.
   */
  private void createCustomRule(
      String action,
      String actionParameters,
      String extraAttributes,
      String toolchains,
      String execGroups,
      String execCompatibleWith)
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file(ctx.label.name + '_dummy_output.jar')",
        "  " + action + "(",
        actionParameters,
        "    outputs = [output_jar],",
        action.equals("ctx.actions.run")
            ? (actionParameters.contains("executable =") // avoid adding executable parameter twice
                ? ""
                : "executable = ctx.toolchains['//rule:toolchain_type_1'].tool,")
            : "    command = 'echo',",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_tool': attr.label(default = '//toolchain:a_tool', cfg = 'exec', executable = True),",
        "    '_nonexecutable_tool': attr.label(default = '//toolchain:b_tool', cfg = 'exec'),",
        extraAttributes,
        "  },",
        "  exec_groups = {",
        execGroups,
        "  },",
        "  toolchains = " + toolchains + ",",
        execCompatibleWith,
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_disabledAndAttributeFalse_disabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "'_use_auto_exec_groups': attr.bool(default = False),",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_disabledAndAttributeTrue_enabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "'_use_auto_exec_groups': attr.bool(default = True),",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isNotEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_disabledAndAttributeNotSet_disabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups=False");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_enabledAndAttributeFalse_disabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "'_use_auto_exec_groups': attr.bool(default = False),",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_enabledAndAttributeTrue_enabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "'_use_auto_exec_groups': attr.bool(default = True)",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isNotEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void automaticExecutionGroups_enabledAndAttributeNotSet_enabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();

    assertThat(execGroups).isNotEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void getToolchainInfoAndContext_automaticExecGroupsEnabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    RuleContext ruleContext = getRuleContext(target);
    ImmutableMap<ToolchainTypeInfo, ToolchainInfo> defaultExecGroupToolchains =
        ruleContext.getToolchainContext().toolchains();
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"));

    assertThat(defaultExecGroupToolchains).isEmpty();
    assertThat(toolchainInfo).isNotNull();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void getToolchainInfoAndContext_automaticExecGroupsDisabled(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups=False");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    RuleContext ruleContext = getRuleContext(target);
    ImmutableMap<ToolchainTypeInfo, ToolchainInfo> defaultExecGroupToolchains =
        ruleContext.getToolchainContext().toolchains();
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"));

    assertThat(defaultExecGroupToolchains).isNotEmpty();
    assertThat(toolchainInfo).isNotNull();
  }

  @Test
  public void toolInExecutableIdentified_noToolchainParameter_noError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable = ctx.executable._tool, ",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void toolWithFilesToRunExecutable_noToolchainParameter_reportsError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable ="
            + " ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  public void toolWithFilesToRunExecutable_toolchainParameterSetToNone_noError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "toolchain = None,"
            + " executable = ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void toolWithFilesToRunExecutable_noToolchains_noError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ " executable ="
            + " ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",
        /* extraAttributes= */ "",
        /* toolchains= */ "[]",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void toolInExecutableUnidentified_noToolchainParameter_reportsError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, ",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  public void toolWithFilesToRunInExecutableUnidentified_noToolchainParameter_reportsError()
      throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable ="
            + " ctx.toolchains['//rule:toolchain_type_1'].files_to_run, ",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolInToolsUnidentified_noToolchainParameter_reportsError(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "tools = [ctx.toolchains['//rule:toolchain_type_1'].tool],",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolWithFilesToRunInToolsUnidentified_noToolchainParameter_reportsError(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "tools = [ctx.toolchains['//rule:toolchain_type_1'].files_to_run],",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void depsetInTools_noToolchainParameter_reportsError(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "tools = [depset([ctx.executable._tool])], ",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
            + " the toolchain parameter. If you're not using a toolchain, set it to 'None'.");
  }

  @Test
  public void toolInExecutableUnidentified_toolchainParameter_noError() throws Exception {
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, "
            + "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void toolInExecutableUnidentified_toolchainParameterNone_noError() throws Exception {
    // Setting toolchain parameter that doesn't match what is used in the executable is technically
    // an error. However, we cannot detect this error at analysis time.
    // It's possible to construct a correct case where executable is from a provider from a
    // dependency that is not a toolchain (like proto_lang_toolchain). In this case the user should
    // set `toolchain = None` (because we wouldn't/couldn't detect where executable is coming from)
    createCustomRule(
        /* action= */ "ctx.actions.run",
        /* actionParameters= */ "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, "
            + "toolchain = None,",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void execGroupSetOnAction_noToolchainParameter_noError() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            output_jar = ctx.actions.declare_file("test_" + ctx.label.name + ".jar")
            ctx.actions.run(
                outputs = [output_jar],
                executable = ctx.toolchains["//rule:toolchain_type_2"].tool,
                exec_group = "custom_exec_group",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            exec_groups = {
                "custom_exec_group": exec_group(toolchains = ["//rule:toolchain_type_2"]),
            },
            toolchains = ["//rule:toolchain_type_2"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void twoToolchains_createTwoExecutionGroups(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ExecGroupCollection execGroups = getRuleContext(target).getExecGroups();

    assertThat(execGroups.execGroups().keySet())
        .containsExactly("//rule:toolchain_type_1", "//rule:toolchain_type_2");
    ExecGroup execGroupTT1 = execGroups.getExecGroup("//rule:toolchain_type_1");
    assertThat(execGroupTT1.toolchainTypes())
        .containsExactly(
            ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_1")));
    assertThat(execGroupTT1.execCompatibleWith()).isEmpty();
    ExecGroup execGroupTT2 = execGroups.getExecGroup("//rule:toolchain_type_2");
    assertThat(execGroupTT2.toolchainTypes())
        .containsExactly(
            ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_2")));
    assertThat(execGroupTT2.execCompatibleWith()).isEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void twoToolchains_threeToolchainContexts(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableSet<String> toolchainContextsKeys =
        getRuleContext(target).getToolchainContexts().contextMap().keySet();

    assertThat(toolchainContextsKeys)
        .containsExactly(
            ExecGroup.DEFAULT_EXEC_GROUP_NAME,
            "//rule:toolchain_type_1",
            "//rule:toolchain_type_2");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void defaultExecGroupHasNoToolchains(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ResolvedToolchainContext defaultExecGroupContext =
        getRuleContext(target).getToolchainContexts().getDefaultToolchainContext();

    assertThat(defaultExecGroupContext).isNotNull();
    assertThat(defaultExecGroupContext.toolchainTypes()).isEmpty();
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void defaultExecGroupHasBasicExecutionPlatform(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ResolvedToolchainContext defaultExecGroupContext =
        getRuleContext(target).getToolchainContexts().getDefaultToolchainContext();

    assertThat(defaultExecGroupContext).isNotNull();
    assertThat(defaultExecGroupContext.executionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolchainParameterAsLabel_correctParsingOfToolchain(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = Label('@//rule:toolchain_type_1'),",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action generatedAction = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar");

    assertThat(generatedAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolchainParameterAsString_correctParsingOfToolchain(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '@//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action generatedAction = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar");

    assertThat(generatedAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolchainParameterAsString_syntaxErrorInParsingOfToolchain(String action)
      throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = 'rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "invalid label 'rule:toolchain_type_1': absolute label must begin with '@'" + " or '//'");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void independentExecPlatformForAction_toolchainType1(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action generatedAction = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar");

    assertThat(generatedAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void independentExecPlatformForAction_toolchainType2(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_2',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action generatedAction = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar");

    assertThat(generatedAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void ctxToolchains_automaticExecGroupsEnabled() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            toolchain_info = ctx.toolchains["//rule:toolchain_type_1"]
            if toolchain_info == None:
                fail("Toolchain info is None.")
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertNoEvents();
  }

  @Test
  public void ctxToolchains_automaticExecGroupsEnabled_wrongToolchainError() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            toolchain_info = ctx.toolchains["//rule:wrong_toolchain_type"]
            if toolchain_info == None:
                fail("Toolchain info is None.")
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "In custom_rule rule //test:custom_rule_name, toolchain type //rule:wrong_toolchain_type"
            + " was requested but only types [//rule:toolchain_type_1] are configured");
  }

  @Test
  public void ctxToolchainsPrint_automaticExecGroupsEnabled() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            print(ctx.toolchains)
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent("<toolchain_context.resolved_labels: //rule:toolchain_type_1>");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void toolchainNotDefinedButUsedInAction(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ (action.equals("ctx.actions.run")
                ? "executable = ctx.executable._tool, "
                : "")
            + "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "[]",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent("Action declared for non-existent toolchain '//rule:toolchain_type_1'");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customExecGroupsAndToolchain(String action) throws Exception {
    String customExecGroups =
        "    'custom_exec_group': exec_group(\n"
            + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
            + "      toolchains = ['//rule:toolchain_type_1'],\n"
            + "    ),\n";
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1', "
            + "exec_group = 'custom_exec_group',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ customExecGroups,
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();
    Action generatedAction = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar");

    assertThat(execGroups.keySet()).containsExactly("//rule:toolchain_type_1", "custom_exec_group");
    assertThat(generatedAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customExecGroups_execCompatibleWith(String action) throws Exception {
    String customExecGroups =
        "    'custom_exec_group': exec_group(\n"
            + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
            + "      toolchains = ['//rule:toolchain_type_1'],\n"
            + "    ),\n";
    String executable =
        action.equals("ctx.actions.run") ? "executable = ctx.executable._tool," : "";
    String execCompatibleWith = "  exec_compatible_with = ['//platforms:constraint_1'],";
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "exec_group = 'custom_exec_group'," + executable,
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ customExecGroups,
        /* execCompatibleWith= */ execCompatibleWith);
    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_properties = {"custom_exec_group.mem": "64"},
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    ImmutableMap<String, ExecGroup> execGroups =
        getRuleContext(target).getExecGroups().execGroups();
    Action ruleAction = (Action) ((RuleConfiguredTarget) target).getActions().get(0);

    assertThat(execGroups.keySet()).containsExactly("custom_exec_group", "//rule:toolchain_type_1");
    assertThat(execGroups.get("custom_exec_group").toolchainTypes())
        .containsExactly(
            ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_1")));
    assertThat(execGroups.get("custom_exec_group").execCompatibleWith())
        .isEqualTo(ImmutableSet.of(Label.parseCanonical("//platforms:constraint_1")));
    assertThat(ruleAction.getOwner().getExecProperties()).containsExactly("mem", "64");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customRule_execCompatibleWith(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_compatible_with = ["//platforms:constraint_2"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action ruleAction = (Action) ((RuleConfiguredTarget) target).getActions().get(0);

    assertThat(ruleAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customRule_execGroupCompatibleWith(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",
        /* execGroups= */ "  'custom_exec_group': exec_group(),",
        /* execCompatibleWith= */ "");
    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_group_compatible_with = {
              "custom_exec_group": ["//platforms:constraint_2"],
              "//rule:toolchain_type_1": ["//platforms:constraint_3"],
              "@//rule:toolchain_type_2": ["//platforms:constraint_4"],
            },
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");

    assertThat(getRuleContext(target).getExecGroups().execGroups().keySet())
        .containsExactly("custom_exec_group", "//rule:toolchain_type_1", "//rule:toolchain_type_2");
    assertThat(getRuleContext(target).getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
    assertThat(getRuleContext(target).getExecutionPlatform("custom_exec_group").label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
    assertThat(getRuleContext(target).getExecutionPlatform("//rule:toolchain_type_1").label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_3"));
    assertThat(getRuleContext(target).getExecutionPlatform("//rule:toolchain_type_2").label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_4"));
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customRule_invalidExecGroupCompatibleWith(String action) throws Exception {
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_1',",
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_1']",
        /* execGroups= */ "",
        /* execCompatibleWith= */ "");
    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_group_compatible_with = {
              "//rule:toolchain_type_2": ["//platforms:constraint_3"],
            },
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "Tried to set execution constraints for non-existent exec groups: //rule:toolchain_type_2"
            + " (did you mean '//rule:toolchain_type_1'?)");
  }

  @Test
  @TestParameters({
    "{action: ctx.actions.run}",
    "{action: ctx.actions.run_shell}",
  })
  public void customExecGroupsAndToolchain_notCompatibleError(String action) throws Exception {
    String customExecGroups =
        "    'custom_exec_group': exec_group(\n"
            + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
            + "      toolchains = ['//rule:toolchain_type_1'],\n"
            + "    ),\n";
    createCustomRule(
        /* action= */ action,
        /* actionParameters= */ "toolchain = '//rule:toolchain_type_2', "
            + "exec_group = 'custom_exec_group',"
            + (action.equals("ctx.actions.run")
                ? "executable = ctx.toolchains['//rule:toolchain_type_2'].tool, "
                : ""),
        /* extraAttributes= */ "",
        /* toolchains= */ "['//rule:toolchain_type_2']",
        /* execGroups= */ customExecGroups,
        /* execCompatibleWith= */ "");
    useConfiguration("--incompatible_auto_exec_groups");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:custom_rule_name");

    assertContainsEvent(
        "`toolchain` and `exec_group` parameters inside actions.{run, run_shell} are not"
            + " compatible; use one of them or define `toolchain` which is compatible with the"
            + " exec_group (already exists inside the `exec_group`)");
  }

  @Test
  public void
      javaCommonCompile_automaticExecGroupsEnabled_optimizationJarActionExecutesOnFirstPlatform()
          throws Exception {
    scratch.file("java/com/google/optimizationtest/config.txt");
    scratch.file(
        "java/com/google/optimizationtest/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "optimizer",
            srcs = ["Foo.java"],
        )

        exports_files(["config.txt"])
        """);
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_use_auto_exec_groups': attr.bool(default = True),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration(
        "--experimental_local_java_optimizations",
        "--experimental_bytecode_optimizers=Optimizer=//java/com/google/optimizationtest:optimizer",
        "--experimental_local_java_optimization_configuration=//java/com/google/optimizationtest:config.txt");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action action = getGeneratingAction(target, "test/lib_custom_rule_name.jar");

    assertThat(action.getMnemonic()).isEqualTo("Optimizer");
    assertThat(action.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void
      javaCommonCompile_automaticExecGroupsDisabled_optimizationJarActionExecutesOnSecondPlatform()
          throws Exception {
    scratch.file("java/com/google/optimizationtest/config.txt");
    scratch.file(
        "java/com/google/optimizationtest/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "optimizer",
            srcs = ["Foo.java"],
        )

        exports_files(["config.txt"])
        """);
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration(
        "--experimental_local_java_optimizations",
        "--experimental_bytecode_optimizers=Optimizer=//java/com/google/optimizationtest:optimizer",
        "--experimental_local_java_optimization_configuration=//java/com/google/optimizationtest:config.txt",
        "--incompatible_auto_exec_groups=False");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action action = getGeneratingAction(target, "test/lib_custom_rule_name.jar");

    assertThat(action.getMnemonic()).isEqualTo("Optimizer");
    assertThat(action.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsEnabled_outputActionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action action = getGeneratingAction(target, "test/lib_custom_rule_name.jar");

    assertThat(action.getMnemonic()).isEqualTo("Javac");
    assertThat(action.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsDisabled_outputActionExecutesOnSecondPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups=False");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    Action action = getGeneratingAction(target, "test/lib_custom_rule_name.jar");

    assertThat(action.getMnemonic()).isEqualTo("Javac");
    assertThat(action.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsEnabled_javaInfoActionsExecuteOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl',"
            + " 'java_common', 'JavaInfo', 'JavaPluginInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    plugins = [ctx.attr._plugins[JavaPluginInfo]],",
        "  )",
        "  return [java_info]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_plugins': attr.label(",
        "      default = Label('//test:test_plugin'),",
        "    ),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_plugin")
        load("//test:defs.bzl", "custom_rule")

        java_plugin(
            name = "test_plugin",
            processor_class = "GeneratedProcessor",
        )

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    JavaInfo javaInfo = JavaInfo.getJavaInfo(target);
    Action genSrcOutputAction =
        getGeneratingAction(javaInfo.getOutputJars().getAllSrcOutputJars().get(0));
    JavaGenJarsProvider javaGenJarsProvider = javaInfo.getGenJarsProvider();
    Action genClassAction = getGeneratingAction(javaGenJarsProvider.getGenClassJar());
    Action genSourceAction = getGeneratingAction(javaGenJarsProvider.getGenSourceJar());

    assertThat(genSrcOutputAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
    assertThat(genClassAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
    assertThat(genSourceAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsDisabled_javaInfoActionsExecuteOnSecondPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl',"
            + " 'java_common', 'JavaInfo', 'JavaPluginInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    plugins = [ctx.attr._plugins[JavaPluginInfo]],",
        "  )",
        "  return [java_info]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    '_plugins': attr.label(",
        "      default = Label('//test:test_plugin'),",
        "    ),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_plugin")
        load("//test:defs.bzl", "custom_rule")

        java_plugin(
            name = "test_plugin",
            processor_class = "GeneratedProcessor",
        )

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups=False");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    JavaInfo javaInfo = JavaInfo.getJavaInfo(target);
    Action genSrcOutputAction =
        getGeneratingAction(javaInfo.getOutputJars().getAllSrcOutputJars().get(0));
    JavaGenJarsProvider javaGenJarsProvider = javaInfo.getGenJarsProvider();
    Action genClassAction = getGeneratingAction(javaGenJarsProvider.getGenClassJar());
    Action genSourceAction = getGeneratingAction(javaGenJarsProvider.getGenSourceJar());

    assertThat(genSrcOutputAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
    assertThat(genClassAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
    assertThat(genSourceAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsEnabled_lazyActionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    source_files = ctx.files.srcs,",
        "  )",
        "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "  },",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["Main.java"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups", "--collect_code_coverage");

    ImmutableList<Action> actions =
        getActions("//test:custom_rule_name", LazyWritePathsFileAction.class);

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void javaCommonCompile_automaticExecGroupsDisabled_lazyActionExecutesOnSecondPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    source_files = ctx.files.srcs,",
        "  )",
        "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=['.java']),",
        "  },",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["Main.java"],
        )
        """);
    useConfiguration("--collect_code_coverage", "--incompatible_auto_exec_groups=False");

    ImmutableList<Action> actions =
        getActions("//test:custom_rule_name", LazyWritePathsFileAction.class);

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void
      javaCommonCompile_automaticExecGroupsEnabled_javaResourceActionsExecuteOnFirstPlatform()
          throws Exception {
    scratch.file(
        "bazel_internal/test_rules/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    resources = ctx.files.resources,",
        "  )",
        "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  attrs = {",
        "    'resources': attr.label_list(allow_files = True),",
        "  },",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/BUILD",
        """
        load("//bazel_internal/test_rules:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            resources = ["Resources.java"],
        )
        """);
    useConfiguration(
        "--incompatible_auto_exec_groups", "--experimental_turbine_annotation_processing");

    ImmutableList<Action> actions = getActions("//bazel_internal/test_rules:custom_rule_name");
    ImmutableList<Action> javaResourceActions =
        actions.stream()
            .filter(action -> action.getMnemonic().equals("JavaResourceJar"))
            .collect(toImmutableList());

    assertThat(javaResourceActions).hasSize(1);
    assertThat(javaResourceActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void
      javaCommonCompile_automaticExecGroupsDisabled_javaResourceActionsExecuteOnSecondPlatform()
          throws Exception {
    scratch.file(
        "bazel_internal/test_rules/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  java_info = java_common.compile(",
        "    ctx,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    resources = ctx.files.resources,",
        "  )",
        "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  attrs = {",
        "    'resources': attr.label_list(allow_files = True),",
        "  },",
        "  provides = [JavaInfo],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/BUILD",
        """
        load("//bazel_internal/test_rules:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            resources = ["Resources.java"],
        )
        """);
    useConfiguration(
        "--experimental_turbine_annotation_processing", "--incompatible_auto_exec_groups=False");

    ImmutableList<Action> actions = getActions("//bazel_internal/test_rules:custom_rule_name");
    ImmutableList<Action> javaResourceActions =
        actions.stream()
            .filter(action -> action.getMnemonic().equals("JavaResourceJar"))
            .collect(toImmutableList());

    assertThat(javaResourceActions).hasSize(1);
    assertThat(javaResourceActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }

  @Test
  public void javaCommonBuildIjar_automaticExecGroupsEnabled_ijarActionsExecuteOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  ctx.actions.run(",
        "    outputs = [output_jar],",
        "    executable = ctx.toolchains['//rule:toolchain_type_2'].tool,",
        "    toolchain = '//rule:toolchain_type_2',",
        "  )",
        "  compile_jar = java_common.run_ijar(",
        "    actions = ctx.actions,",
        "    jar = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> actions =
        getActions("//test:custom_rule_name").stream()
            .filter(action -> action.getMnemonic().equals("JavaIjar"))
            .collect(toImmutableList());

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void javaCommonPackSources_automaticExecGroupsEnabled_sourceActionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  source_jar = java_common.pack_sources(",
        "    ctx.actions,",
        "    output_source_jar = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> actions =
        getActions("//test:custom_rule_name").stream()
            .filter(action -> action.getMnemonic().equals("JavaSourceJar"))
            .collect(toImmutableList());

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void javaCommonStampJar_automaticExecGroupsEnabled_actionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
        "  ctx.actions.run(",
        "    outputs = [output_jar],",
        "    executable = ctx.toolchains['//rule:toolchain_type_2'].tool,",
        "    toolchain = '//rule:toolchain_type_2',",
        "  )",
        "  source_jar = java_common.stamp_jar(",
        "    ctx.actions,",
        "    jar = output_jar,",
        "    target_label = ctx.label,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> actions =
        getActions("//test:custom_rule_name").stream()
            .filter(action -> action.getMnemonic().equals("JavaIjar"))
            .collect(toImmutableList());

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getProgressMessage())
        .matches("Stamping target label into jar .*/lib_custom_rule_name.jar");
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonLink_cppLinkExecGroupNotDefined_cppLinkActionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "     unsupported_features = ctx.disabled_features,",
        "  )",
        "  linking_outputs = cc_common.link(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  exec_groups = { ",
        "    '" + CPP_LINK_EXEC_GROUP + "': exec_group(toolchains = _use_cpp_toolchain()),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> actions = getActions("//test:custom_rule_name", "CppLink");

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonLink_cppLinkExecGroupDefined_cppLinkActionExecutesOnFirstPlatform()
      throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "     unsupported_features = ctx.disabled_features,",
        "  )",
        "  linking_outputs = cc_common.link(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  exec_groups = { ",
        "    '" + CPP_LINK_EXEC_GROUP + "': exec_group(toolchains = _use_cpp_toolchain()),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> actions = getActions("//test:custom_rule_name", "CppLink");

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonLink_cppLTOActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  linking_outputs = cc_common.link(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    linking_contexts = [dep[CcInfo].linking_context for dep in ctx.attr.deps if"
            + " CcInfo in dep]",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        cc_library(
            name = "dep",
            srcs = ["dep.cc"],
        )

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            deps = ["dep"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups", "--features=thin_lto");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.THIN_LTO, CppRuleClasses.SUPPORTS_START_END_LIB)
                .withToolchainTargetConstraints("@@//platforms:constraint_1")
                .withToolchainExecConstraints("@@//platforms:constraint_1"));

    ImmutableList<Action> cppLtoActions = getActions("//test:custom_rule_name", "CppLTOIndexing");

    assertThat(cppLtoActions).hasSize(1);
    assertThat(cppLtoActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonLink_linkstampCompileActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  linking_outputs = cc_common.link(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    linking_contexts = [dep[CcInfo].linking_context for dep in ctx.attr.deps if"
            + " CcInfo in dep]",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'deps': attr.label_list(),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        cc_library(
            name = "dep",
            linkstamp = "stamp.cc",
        )

        custom_rule(
            name = "custom_rule_name",
            deps = ["dep"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> cppCompileActions =
        getActions("//bazel_internal/test_rules/cc:custom_rule_name", "CppLinkstampCompile");

    assertThat(cppCompileActions).hasSize(1);
    assertThat(cppCompileActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonCompile_cppCompileActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  (compilation_context, compilation_outputs) = cc_common.compile(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    srcs = ctx.files.srcs,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<Action> cppCompileActions =
        getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction.class);

    assertThat(cppCompileActions).hasSize(1);
    assertThat(cppCompileActions.get(0).getProgressMessage())
        .isEqualTo("Compiling bazel_internal/test_rules/cc/custom.cc");
    assertThat(cppCompileActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonCompile_moduleActionsExecuteOnFirstPlatform() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  (compilation_context, compilation_outputs) = cc_common.compile(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    srcs = ctx.files.srcs,",
        "    public_hdrs = ctx.files.hdrs,",
        "    separate_module_headers = ctx.files.hdrs,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "    'hdrs': attr.label_list(allow_files = ['.h']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups", "--features=header_modules");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
                .withToolchainTargetConstraints("@@//platforms:constraint_1")
                .withToolchainExecConstraints("@@//platforms:constraint_1"));

    ImmutableList<Action> cppCompileActions =
        getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction.class);
    ImmutableList<Action> moduleActions =
        cppCompileActions.stream()
            .filter(action -> action.getProgressMessage().contains("custom_rule_name.cppmap"))
            .collect(toImmutableList());

    assertThat(moduleActions).hasSize(2);
    assertThat(moduleActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
    assertThat(moduleActions.get(1).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonCompile_codeGenModuleActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  (compilation_context, compilation_outputs) = cc_common.compile(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    srcs = ctx.files.srcs,",
        "    public_hdrs = ctx.files.hdrs,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "    'hdrs': attr.label_list(allow_files = ['.h']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        """);
    useConfiguration(
        "--incompatible_auto_exec_groups",
        "--features=header_modules",
        "--features=header_module_codegen");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
                .withToolchainTargetConstraints("@@//platforms:constraint_1")
                .withToolchainExecConstraints("@@//platforms:constraint_1"));

    ImmutableList<Action> cppCompileActions =
        getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction.class);
    ImmutableList<Action> codeGenCompileActions =
        cppCompileActions.stream()
            .filter(action -> action.getProgressMessage().contains("custom_rule_name.pcm"))
            .collect(toImmutableList());

    assertThat(codeGenCompileActions).hasSize(1);
    assertThat(codeGenCompileActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonCompile_compileHeaderActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  (compilation_context, compilation_outputs) = cc_common.compile(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    srcs = ctx.files.srcs,",
        "    private_hdrs = ctx.files.hdrs,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "    'hdrs': attr.label_list(allow_files = ['.h']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups", "--features=parse_headers");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.PARSE_HEADERS)
                .withToolchainTargetConstraints("@@//platforms:constraint_1")
                .withToolchainExecConstraints("@@//platforms:constraint_1"));

    ImmutableList<Action> cppCompileActions =
        getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction.class);
    ImmutableList<Action> compileHeaderActions =
        cppCompileActions.stream()
            .filter(
                action ->
                    action
                        .getProgressMessage()
                        .equals("Compiling bazel_internal/test_rules/cc/custom.h"))
            .collect(toImmutableList());

    assertThat(compileHeaderActions).hasSize(1);
    assertThat(compileHeaderActions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void ccCommonCompile_treeArtifactActionExecutesOnFirstPlatform() throws Exception {
    scratch.file(
        "test/defs.bzl",
        "def _use_cpp_toolchain():",
        "   return [",
        "      config_common.toolchain_type('"
            + TestConstants.CPP_TOOLCHAIN_TYPE
            + "', mandatory = False),",
        "   ]",
        "def _ta_impl(ctx):",
        "    tree = ctx.actions.declare_directory('dir')",
        "    ctx.actions.run_shell(",
        "        outputs = [tree],",
        "        inputs = [],",
        "        arguments = [tree.path],",
        "        command = 'mkdir $1',",
        "    )",
        "    return [DefaultInfo(files = depset([tree]))]",
        "create_tree_artifact = rule(implementation = _ta_impl)",
        "def _impl(ctx):",
        "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "  feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = cc_toolchain,",
        "      requested_features = ctx.features,",
        "      unsupported_features = ctx.disabled_features,",
        "  )",
        "  (compilation_context, compilation_outputs) = cc_common.compile(",
        "    name = ctx.label.name,",
        "    actions = ctx.actions,",
        "    feature_configuration = feature_configuration,",
        "    cc_toolchain = cc_toolchain,",
        "    srcs = ctx.files.srcs,",
        "  )",
        "  return []",
        "custom_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files = ['.cc']),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
        "  fragments = ['cpp']",
        ")");
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "create_tree_artifact", "custom_rule")

        package(default_visibility = ["//visibility:public"])

        create_tree_artifact(name = "tree_artifact")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["tree_artifact"],
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ImmutableList<ActionAnalysisMetadata> actions =
        ((RuleConfiguredTarget) getConfiguredTarget("//test:custom_rule_name")).getActions();

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_1"));
  }

  @Test
  public void testToolchainAsAlias() throws Exception {
    scratch.file(
        "test/alias/BUILD",
        """
        alias(
            name = "alias_toolchain_type_1",
            actual = "//rule:toolchain_type_1",
        )
        alias(
            name = "alias_toolchain_type_2",
            actual = "//rule:toolchain_type_2",
        )
        """);
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type_1",
             "//test/alias:alias_toolchain_type_2"],
            exec_groups = {
                "custom_exec_group": exec_group(
                    toolchains = ["//rule:toolchain_type_1"],
                ),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        package(default_visibility = ["//visibility:public"])

        custom_rule(
            name = "custom_rule_name",
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    ConfiguredTarget target = getConfiguredTarget("//test:custom_rule_name");
    RuleContext ruleContext = getRuleContext(target);
    ToolchainInfo realToolchainInfo1 =
        ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"));
    ToolchainInfo aliasToolchainInfo1 =
        ruleContext.getToolchainInfo(Label.parseCanonical("//test/alias:alias_toolchain_type_1"));

    assertThat(realToolchainInfo1).isEqualTo(aliasToolchainInfo1);

    ToolchainInfo realToolchainInfo2 =
        ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_2"));
    ToolchainInfo aliasToolchainInfo2 =
        ruleContext.getToolchainInfo(Label.parseCanonical("//test/alias:alias_toolchain_type_2"));

    assertThat(realToolchainInfo2).isEqualTo(aliasToolchainInfo2);
  }

  @Test
  public void testHeaderCompilationAction_automaticExecGroupsEnabled() throws Exception {
    scratch.file(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/turbine_canary_deploy.jar");
    scratch.file(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/tzdata.jar");
    scratch.overwriteFile(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime',"
            + " 'java_toolchain')",
        "load(",
        "    ':java_toolchain_alias.bzl',",
        "    'java_toolchain_alias',",
        "    'java_runtime_alias',",
        "    'java_host_runtime_alias',",
        ")",
        "toolchain_type(name = 'toolchain_type')",
        "java_toolchain_alias(name='current_java_toolchain')",
        "java_plugins_flag_alias(name = 'java_plugins_flag_alias')",
        "filegroup(name = 'message_translations')",
        "java_toolchain(name = 'toolchain',",
        "    source_version = '6',",
        "    target_version = '6',",
        "    bootclasspath = ['rt.jar'],",
        "    xlint = ['toto'],",
        "    javacopts =['-Xmaxerrs 500'],",
        "    compatible_javacopts = {",
        "        'appengine': ['-XDappengineCompatible'],",
        "        'android': ['-XDandroidCompatible'],",
        "    },",
        "    tools = [':javac_canary.jar'],",
        "    javabuilder = [':JavaBuilder_deploy.jar'],",
        "    jacocorunner = ':jacocorunner.jar',",
        "    header_compiler = [':turbine_canary_deploy.jar'],",
        "    header_compiler_direct = [':turbine_graal'],",
        "    singlejar = ['singlejar'],",
        "    ijar = ['ijar'],",
        "    genclass = ['GenClass_deploy.jar'],",
        "    timezone_data = 'tzdata.jar',",
        "    java_runtime = ':jvm-k8',",
        "    exec_compatible_with = ['@@//platforms:constraint_2'],",
        ")",
        "java_runtime(",
        "    name = 'jvm-k8',",
        "    srcs = [",
        "        'k8/a', ",
        "        'k8/b',",
        "    ], ",
        "    java_home = 'k8',",
        ")",
        "toolchain(",
        "    name = 'java_toolchain',",
        "    toolchain = ':toolchain',",
        "    toolchain_type = '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "',",
        "    exec_compatible_with = ['@@//platforms:constraint_2'],",
        ")");
    scratch.file(
        "foo/custom_rule.bzl",
        "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo',"
            + " 'JavaPluginInfo')",
        "def _impl(ctx):",
        "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
        "  compilation_provider = java_common.compile(",
        "    ctx,",
        "    source_files = ctx.files.srcs,",
        "    output = output_jar,",
        "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
        "    deps = [p[JavaInfo] for p in ctx.attr.deps],",
        "    plugins = [p[JavaPluginInfo] for p in ctx.attr.plugins],",
        "    enable_annotation_processing = False,",
        "  )",
        "  return [DefaultInfo(files = depset([output_jar])), compilation_provider]",
        "java_custom_library = rule(",
        "  implementation = _impl,",
        "  outputs = {",
        "    'my_output': 'lib%{name}.jar'",
        "  },",
        "  attrs = {",
        "    'srcs': attr.label_list(allow_files=True),",
        "    'deps': attr.label_list(providers=[JavaInfo]),",
        "    'plugins': attr.label_list(providers=[JavaPluginInfo]),",
        "  },",
        "  toolchains = ['//rule:toolchain_type_1', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
        "  fragments = ['java']",
        ")");
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load(":custom_rule.bzl", "java_custom_library")

        java_plugin(
            name = "processor",
            srcs = ["processor.java"],
            data = ["processor_data.txt"],
            generates_api = 1,  # so Turbine would normally run it
            processor_class = "Foo",
        )

        java_library(
            name = "exports_processor",
            exported_plugins = [":processor"],
        )

        java_custom_library(
            name = "custom",
            srcs = ["custom.java"],
            plugins = [":processor"],
            deps = [":exports_processor"],
        )

        java_custom_library(
            name = "custom_noproc",
            srcs = ["custom.java"],
        )
        """);
    useConfiguration(
        "--java_header_compilation=true",
        "--experimental_java_header_input_pruning",
        "--incompatible_auto_exec_groups=True");

    ConfiguredTarget custom = getConfiguredTarget("//foo:custom");
    ConfiguredTarget customNoproc = getConfiguredTarget("//foo:custom_noproc");
    JavaCompileAction turbineAction =
        (JavaCompileAction) getGeneratingAction(getBinArtifact("libcustom-hjar.jar", custom));
    SpawnAction turbineActionNoProc =
        (SpawnAction)
            getGeneratingAction(getBinArtifact("libcustom_noproc-hjar.jar", customNoproc));

    assertThat(turbineAction.getMnemonic()).isEqualTo("JavacTurbine");
    assertThat(turbineAction.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
    assertThat(turbineActionNoProc.getMnemonic()).isEqualTo("Turbine");
    assertThat(turbineActionNoProc.getOwner().getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonical("//platforms:platform_2"));
  }
}
