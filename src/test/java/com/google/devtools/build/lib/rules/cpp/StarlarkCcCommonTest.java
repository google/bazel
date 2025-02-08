// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.baseArtifactNames;
import static com.google.devtools.build.lib.rules.cpp.SolibSymlinkAction.MAX_FILENAME_LENGTH;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.AnalysisTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.packages.util.ResourceLoader;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ActionConfig;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvEntry;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.EnvSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag.SingleChunkFlag;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagGroup;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FlagSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.VariableWithValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.WithFeatureSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValueParser;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.MakeVariable;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.ToolPath;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the {@code cc_common} Starlark module. */
@RunWith(JUnit4.class)
public class StarlarkCcCommonTest extends BuildViewTestCase {

  private static final String REDACTED_ARTIFACT_PATH =
      "tools/build_defs/build_info/redacted_file.h";
  private static final String NON_REDACTED_ARTIFACT_PATH =
      "tools/build_defs/build_info/volatile_file.h";

  @Before
  public void setUp() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private static StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget)
      throws LabelSyntaxException {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  private static Iterable<Artifact> getArtifactsFromMyInfo(ConfiguredTarget target, String field)
      throws Exception {
    StructImpl myInfo = getMyInfoFromTarget(target);
    @SuppressWarnings("unchecked")
    Iterable<Artifact> artifacts = (Iterable<Artifact>) myInfo.getValue(field);
    return artifacts;
  }

  private LinkCommandLine getLinkCommandLine(SpawnAction cppLinkAction) {
    var commandLines = cppLinkAction.getCommandLines().unpack();
    assertThat(commandLines).hasSize(2);
    assertThat(commandLines.get(1).commandLine).isInstanceOf(LinkCommandLine.class);
    return (LinkCommandLine) commandLines.get(1).commandLine;
  }

  @Test
  public void testAllFiles() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  return [MyInfo(all_files = toolchain.all_files)]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  toolchains = use_cc_toolchain()",
        ");");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    Depset allFiles = (Depset) getMyInfoFromTarget(r).getValue("all_files");
    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    assertThat(allFiles.getSet(Artifact.class)).isEqualTo(toolchain.getAllFiles());
  }

  @Test
  public void testRuntimeLib() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "CruleInfo = provider(fields=['static', 'dynamic'])",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  return [CruleInfo(",
        "    static = toolchain.static_runtime_lib(feature_configuration = feature_configuration),",
        "    dynamic = toolchain.dynamic_runtime_lib(",
        "      feature_configuration = feature_configuration),",
        "  )]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");

    // 1. Build without static_link_cpp_runtimes
    ConfiguredTarget r = getConfiguredTarget("//a:r");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.create(r.getLabel().getPackageIdentifier(), "rule.bzl")),
            "CruleInfo");
    StarlarkInfo cruleInfo = (StarlarkInfo) r.get(key);
    Depset staticRuntimeLib = (Depset) cruleInfo.getValue("static");
    Depset dynamicRuntimeLib = (Depset) cruleInfo.getValue("dynamic");

    assertThat(staticRuntimeLib.getSet(Artifact.class).toList()).isEmpty();
    assertThat(dynamicRuntimeLib.getSet(Artifact.class).toList()).isEmpty();

    // 2. Build with static_link_cpp_runtimes
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES));
    invalidatePackages();
    r = getConfiguredTarget("//a:r");
    cruleInfo = (StarlarkInfo) r.get(key);
    staticRuntimeLib = (Depset) cruleInfo.getValue("static");
    dynamicRuntimeLib = (Depset) cruleInfo.getValue("dynamic");

    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    assertThat(staticRuntimeLib.getSet(Artifact.class))
        .isEqualTo(toolchain.getStaticRuntimeLinkInputs());
    assertThat(dynamicRuntimeLib.getSet(Artifact.class))
        .isEqualTo(toolchain.getDynamicRuntimeLinkInputs());
  }

  @Test
  public void testGetValuesFromCcToolchain() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  return [MyInfo(",
        "    dynamic_runtime_solib_dir = toolchain.dynamic_runtime_solib_dir,",
        "    toolchain_id = toolchain.toolchain_id,",
        "  )]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    StructImpl info = getMyInfoFromTarget(r);
    String dynamicRuntimeSolibDir = (String) info.getValue("dynamic_runtime_solib_dir");
    String toolchainId = (String) info.getValue("toolchain_id");

    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);

    assertThat(dynamicRuntimeSolibDir)
        .isEqualTo(toolchain.getDynamicRuntimeSolibDir().getPathString());
    assertThat(toolchainId).isEqualTo(toolchain.getToolchainIdentifier());
  }

  @Test
  public void testGetToolForAction() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  return [MyInfo(",
        "    action_tool_path = cc_common.get_tool_for_action(",
        "        feature_configuration = feature_configuration,",
        "        action_name = 'c++-compile'))]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    String actionToolPath = (String) getMyInfoFromTarget(r).getValue("action_tool_path");
    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrThrowEvalException(
            ImmutableSet.of(),
            ImmutableSet.of(),
            Language.CPP,
            toolchain,
            ruleContext.getFragment(CppConfiguration.class));
    assertThat(actionToolPath)
        .isEqualTo(featureConfiguration.getToolPathForAction(CppActionNames.CPP_COMPILE));
  }

  @Test
  public void testExecutionRequirements() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(MockCcSupport.CPP_COMPILE_ACTION_WITH_REQUIREMENTS));
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                requirements = cc_common.get_execution_requirements(
                    feature_configuration = feature_configuration,
                    action_name = "yolo_action_with_requirements",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked")
    Sequence<String> requirements =
        (Sequence<String>) getMyInfoFromTarget(r).getValue("requirements");
    assertThat(requirements).containsExactly("requires-yolo");
  }

  @Test
  public void testFeatureConfigurationWithAdditionalEnabledFeature() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures("foo_feature"));
    useConfiguration();
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                requested_features = ["foo_feature"],
            )
            return [MyInfo(
                foo_feature_enabled = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "foo_feature",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    boolean fooFeatureEnabled = (boolean) getMyInfoFromTarget(r).getValue("foo_feature_enabled");
    assertThat(fooFeatureEnabled).isTrue();
  }

  @Test
  public void testFeatureConfigurationWithAdditionalUnsupportedFeature() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures("foo_feature"));
    useConfiguration("--features=foo_feature");
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                unsupported_features = ["foo_feature"],
            )
            return [MyInfo(
                foo_feature_enabled = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "foo_feature",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    boolean fooFeatureEnabled = (boolean) getMyInfoFromTarget(r).getValue("foo_feature_enabled");
    assertThat(fooFeatureEnabled).isFalse();
  }

  @Test
  public void testGetCommandLine() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  return [MyInfo(",
        "    command_line = cc_common.get_memory_inefficient_command_line(",
        "        feature_configuration = feature_configuration,",
        "        action_name = 'c++-link-executable',",
        "        variables = cc_common.empty_variables()))]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked")
    Sequence<String> commandLine =
        (Sequence<String>) getMyInfoFromTarget(r).getValue("command_line");
    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrThrowEvalException(
            ImmutableSet.of(),
            ImmutableSet.of(),
            Language.CPP,
            toolchain,
            ruleContext.getFragment(CppConfiguration.class));
    assertThat(commandLine)
        .containsExactlyElementsIn(
            featureConfiguration.getCommandLine("c++-link-executable", CcToolchainVariables.EMPTY));
  }

  @Test
  public void testGetEnvironment() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  return [MyInfo(",
        "    environment_variables = cc_common.get_environment_variables(",
        "        feature_configuration = feature_configuration,",
        "        action_name = 'c++-compile',",
        "        variables = cc_common.empty_variables()))]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");

    ConfiguredTarget r = getConfiguredTarget("//a:r");
    @SuppressWarnings("unchecked")
    Map<String, String> environmentVariables =
        (Map<String, String>) getMyInfoFromTarget(r).getValue("environment_variables");
    RuleContext ruleContext = getRuleContext(r);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrThrowEvalException(
            ImmutableSet.of(),
            ImmutableSet.of(),
            Language.CPP,
            toolchain,
            ruleContext.getFragment(CppConfiguration.class));
    assertThat(environmentVariables)
        .containsExactlyEntriesIn(
            featureConfiguration.getEnvironmentVariables(
                CppActionNames.CPP_COMPILE, CcToolchainVariables.EMPTY, PathMapper.NOOP));
  }

  @Test
  public void testActionIsEnabled() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                enabled_action = cc_common.action_is_enabled(
                    feature_configuration = feature_configuration,
                    action_name = "c-compile",
                ),
                disabled_action = cc_common.action_is_enabled(
                    feature_configuration = feature_configuration,
                    action_name = "wololoo",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    StructImpl myInfo = getMyInfoFromTarget(getConfiguredTarget("//a:r"));
    boolean enabledActionIsEnabled = (boolean) myInfo.getValue("enabled_action");
    boolean disabledActionIsDisabled = (boolean) myInfo.getValue("disabled_action");
    assertThat(enabledActionIsEnabled).isTrue();
    assertThat(disabledActionIsDisabled).isFalse();
  }

  @Test
  public void testIsEnabled() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                enabled_feature = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "libraries_to_link",
                ),
                disabled_feature = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "wololoo",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    StructImpl myInfo = getMyInfoFromTarget(getConfiguredTarget("//a:r"));

    boolean enabledFeatureIsEnabled = (boolean) myInfo.getValue("enabled_feature");
    boolean disabledFeatureIsDisabled = (boolean) myInfo.getValue("disabled_feature");
    assertThat(enabledFeatureIsEnabled).isTrue();
    assertThat(disabledFeatureIsDisabled).isFalse();
  }

  @Test
  public void testFeatureConfigurationRequiresCtx() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(cc_toolchain = toolchain)

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);
    useConfiguration("--incompatible_require_ctx_in_configure_features");
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//a:r");
    assertContainsEvent("Mandatory parameter 'ctx' of cc_common.configure_features is missing");
  }

  @Test
  public void testActionNames() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        """);
    scratch.overwriteFile("tools/build_defs/cc/BUILD");
    scratch.overwriteFile(
        "tools/build_defs/cc/action_names.bzl",
        ResourceLoader.readFromResources(
            TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/action_names.bzl"));

    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load(
            "//tools/build_defs/cc:action_names.bzl",
            "ASSEMBLE_ACTION_NAME",
            "CC_FLAGS_MAKE_VARIABLE_ACTION_NAME",
            "CPP_COMPILE_ACTION_NAME",
            "CPP_HEADER_PARSING_ACTION_NAME",
            "CPP_LINK_DYNAMIC_LIBRARY_ACTION_NAME",
            "CPP_LINK_EXECUTABLE_ACTION_NAME",
            "CPP_LINK_NODEPS_DYNAMIC_LIBRARY_ACTION_NAME",
            "CPP_LINK_STATIC_LIBRARY_ACTION_NAME",
            "CPP_MODULE_CODEGEN_ACTION_NAME",
            "CPP_MODULE_COMPILE_ACTION_NAME",
            "C_COMPILE_ACTION_NAME",
            "LINKSTAMP_COMPILE_ACTION_NAME",
            "LTO_BACKEND_ACTION_NAME",
            "LTO_INDEXING_ACTION_NAME",
            "PREPROCESS_ASSEMBLE_ACTION_NAME",
            "STRIP_ACTION_NAME",
        )

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                c_compile_action_name = C_COMPILE_ACTION_NAME,
                cpp_compile_action_name = CPP_COMPILE_ACTION_NAME,
                linkstamp_compile_action_name = LINKSTAMP_COMPILE_ACTION_NAME,
                cc_flags_make_variable_action_name_action_name =
                    CC_FLAGS_MAKE_VARIABLE_ACTION_NAME,
                cpp_module_codegen_action_name = CPP_MODULE_CODEGEN_ACTION_NAME,
                cpp_header_parsing_action_name = CPP_HEADER_PARSING_ACTION_NAME,
                cpp_module_compile_action_name = CPP_MODULE_COMPILE_ACTION_NAME,
                assemble_action_name = ASSEMBLE_ACTION_NAME,
                preprocess_assemble_action_name = PREPROCESS_ASSEMBLE_ACTION_NAME,
                lto_indexing_action_name = LTO_INDEXING_ACTION_NAME,
                lto_backend_action_name = LTO_BACKEND_ACTION_NAME,
                cpp_link_executable_action_name = CPP_LINK_EXECUTABLE_ACTION_NAME,
                cpp_link_dynamic_library_action_name = CPP_LINK_DYNAMIC_LIBRARY_ACTION_NAME,
                cpp_link_nodeps_dynamic_library_action_name =
                    CPP_LINK_NODEPS_DYNAMIC_LIBRARY_ACTION_NAME,
                cpp_link_static_library_action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
                strip_action_name = STRIP_ACTION_NAME,
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    assertThat(getTarget("//a:r")).isNotNull();

    StructImpl myInfo = getMyInfoFromTarget(getConfiguredTarget("//a:r"));

    assertThat(myInfo.getValue("c_compile_action_name")).isEqualTo(CppActionNames.C_COMPILE);
    assertThat(myInfo.getValue("cpp_compile_action_name")).isEqualTo(CppActionNames.CPP_COMPILE);
    assertThat(myInfo.getValue("linkstamp_compile_action_name"))
        .isEqualTo(CppActionNames.LINKSTAMP_COMPILE);
    assertThat(myInfo.getValue("cc_flags_make_variable_action_name_action_name"))
        .isEqualTo(CppActionNames.CC_FLAGS_MAKE_VARIABLE);
    assertThat(myInfo.getValue("cpp_module_codegen_action_name"))
        .isEqualTo(CppActionNames.CPP_MODULE_CODEGEN);
    assertThat(myInfo.getValue("cpp_header_parsing_action_name"))
        .isEqualTo(CppActionNames.CPP_HEADER_PARSING);
    assertThat(myInfo.getValue("cpp_module_compile_action_name"))
        .isEqualTo(CppActionNames.CPP_MODULE_COMPILE);
    assertThat(myInfo.getValue("assemble_action_name")).isEqualTo(CppActionNames.ASSEMBLE);
    assertThat(myInfo.getValue("preprocess_assemble_action_name"))
        .isEqualTo(CppActionNames.PREPROCESS_ASSEMBLE);
    assertThat(myInfo.getValue("lto_indexing_action_name")).isEqualTo(CppActionNames.LTO_INDEXING);
    assertThat(myInfo.getValue("lto_backend_action_name")).isEqualTo(CppActionNames.LTO_BACKEND);
    assertThat(myInfo.getValue("cpp_link_executable_action_name"))
        .isEqualTo(CppActionNames.CPP_LINK_EXECUTABLE);
    assertThat(myInfo.getValue("cpp_link_dynamic_library_action_name"))
        .isEqualTo(CppActionNames.CPP_LINK_DYNAMIC_LIBRARY);
    assertThat(myInfo.getValue("cpp_link_nodeps_dynamic_library_action_name"))
        .isEqualTo(CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY);
    assertThat(myInfo.getValue("cpp_link_static_library_action_name"))
        .isEqualTo(CppActionNames.CPP_LINK_STATIC_LIBRARY);
    assertThat(myInfo.getValue("strip_action_name")).isEqualTo(CppActionNames.STRIP);
  }

  @Test
  public void testCompileBuildVariablesWithSourceFile() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "source_file = 'foo/bar/hello'",
                ")"))
        .containsAtLeast("-c", "foo/bar/hello")
        .inOrder();
  }

  @Test
  public void testCompileBuildVariablesWithOutputFile() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "output_file = 'foo/bar/hello.o'",
                ")"))
        .containsAtLeast("-o", "foo/bar/hello.o")
        .inOrder();
  }

  @Test
  public void testCompileBuildVariablesForIncludes() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "include_directories = depset(['foo/bar/include'])",
                ")"))
        .contains("-Ifoo/bar/include");
  }

  @Test
  public void testCompileBuildVariablesForFrameworkIncludes() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "framework_include_directories = depset(['foo/bar'])",
                ")"))
        .contains("-Ffoo/bar");
  }

  @Test
  public void testCompileBuildVariablesForDefines() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "preprocessor_defines = depset(['DEBUG_FOO'])",
                ")"))
        .contains("-DDEBUG_FOO");
  }

  @Test
  public void testCompileBuildVariablesForPic() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC));
    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "use_pic = True",
                ")"))
        .contains("-fPIC");
  }

  @Test
  public void testUserCompileFlags() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_compile_flags = ['-foo']",
                ")"))
        .contains("-foo");
  }

  @Test
  public void testCompileBuildVariablesForDummyLtoBackendAction() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.THIN_LTO));
    useConfiguration("--features=thin_lto");
    Sequence<String> commandLine =
        commandLineForVariables(
            CppActionNames.LTO_BACKEND,
            "cc_common.create_compile_variables(",
            "feature_configuration = feature_configuration,",
            "cc_toolchain = toolchain,",
            "thinlto_input_bitcode_file = 'path/to/input',",
            "thinlto_output_object_file = 'path/to/output',",
            "thinlto_index = '/dev/null'",
            ")");

    assertThat(commandLine)
        .containsAtLeast(
            "thinlto_index=/dev/null",
            "thinlto_output_object_file=path/to/output",
            "thinlto_input_bitcode_file=path/to/input");
  }

  @Test
  public void testCompileBuildVariablesWithVariablesExtension() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures("check_additional_variables_feature"));
    useConfiguration("--features=check_additional_variables_feature");
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "    feature_configuration = feature_configuration,",
                "    cc_toolchain = toolchain,",
                "    variables_extension = {",
                "        'string_variable': 'foo',",
                "        'list_variable': ['bar', 'baz']",
                "    }",
                ")"))
        .containsAtLeast("--my_string=foo", "--my_list_element=bar", "--my_list_element=baz");
  }

  @Test
  public void testEmptyLinkVariables() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_link_flags = [ '-foo' ],",
                ")"))
        .contains("-foo");
  }

  @Test
  public void testEmptyLinkVariablesContainSysroot() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withSysroot("/foo/bar/sysroot"));
    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                ")"))
        .contains("--sysroot=/foo/bar/sysroot");
  }

  @Test
  public void testLibrarySearchDirectoriesLinkVariables() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures("library_search_directories"));
    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "library_search_directories = depset([ 'a', 'b', 'c' ]),",
                ")"))
        .containsAtLeast("--library=a", "--library=b", "--library=c")
        .inOrder();
  }

  @Test
  public void testRuntimeLibrarySearchDirectoriesLinkVariables() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures("runtime_library_search_directories"));
    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "runtime_library_search_directories = depset([ 'a', 'b', 'c' ]),",
                ")"))
        .containsAtLeast("--runtime_library=a", "--runtime_library=b", "--runtime_library=c")
        .inOrder();
  }

  @Test
  public void testUserLinkFlagsLinkVariables() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_link_flags = [ '-avocado' ],",
                ")"))
        .contains("-avocado");
  }

  @Test
  public void testIfsoRelatedVariablesAreNotExposed() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures("uses_ifso_variables"));
    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                ")"))
        .doesNotContain("--generate_interface_library_was_available");
  }

  @Test
  public void testOutputFileLinkVariables() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "output_file = 'foo/bar/executable',",
                ")"))
        .contains("foo/bar/executable");
  }

  @Test
  public void testParamFileLinkVariables() throws Exception {
    AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig);
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "param_file = 'foo/bar/params',",
                ")"))
        .contains("@foo/bar/params");
  }

  @Test
  public void testMustKeepDebugLinkVariables() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures("strip_debug_symbols"));

    useConfiguration();
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "must_keep_debug = False,",
                ")"))
        .contains("-strip_stuff");
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "must_keep_debug = True,",
                ")"))
        .doesNotContain("-strip_stuff");
  }

  @Test
  public void testIsLinkingDynamicLibraryLinkVariables() throws Exception {
    useConfiguration("--linkopt=-pie");
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_linking_dynamic_library = True,",
                "user_link_flags = [ '-pie' ],",
                ")"))
        .doesNotContain("-pie");
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_linking_dynamic_library = False,",
                "user_link_flags = [ '-pie' ],",
                ")"))
        .contains("-pie");
  }

  @Test
  public void testIsUsingLinkerLinkVariables() throws Exception {
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_using_linker = True,",
                "user_link_flags = [ '-i_dont_want_to_see_this_on_archiver_command_line' ],",
                ")"))
        .contains("-i_dont_want_to_see_this_on_archiver_command_line");
    assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_using_linker = False,",
                "user_link_flags = [ '-i_dont_want_to_see_this_on_archiver_command_line' ],",
                ")"))
        .doesNotContain("-i_dont_want_to_see_this_on_archiver_command_line");
  }

  private Sequence<String> commandLineForVariables(String actionName, String... variables)
      throws Exception {
    return commandLineForVariables(actionName, 0, variables);
  }

  // This method is only there to change the package to fix multiple runs of this method in a single
  // test.
  // TODO(b/109917616): Remove pkgSuffix argument when bzl files are not cached within single test
  private Sequence<String> commandLineForVariables(
      String actionName, int pkgSuffix, String... variables) throws Exception {
    scratch.file(
        "a" + pkgSuffix + "/BUILD",
        "load(':rule.bzl', 'crule')",
        "cc_toolchain_alias(name='alias')",
        "crule(name='r')");

    scratch.file(
        "a" + pkgSuffix + "/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "    requested_features = ctx.features,",
        "  )",
        "  variables = " + Joiner.on("\n").join(variables),
        "  return [MyInfo(",
        "    command_line = cc_common.get_memory_inefficient_command_line(",
        "        feature_configuration = feature_configuration,",
        "        action_name = '" + actionName + "',",
        "        variables = variables))]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    '_cc_toolchain': attr.label(default=Label('//a" + pkgSuffix + ":alias'))",
        "  },",
        "  fragments = ['cpp'],",
        ");");

    /* Calling {@link #getTarget} to get loading errors */
    getTarget("//a" + pkgSuffix + ":r");
    ConfiguredTarget r = getConfiguredTarget("//a" + pkgSuffix + ":r");
    if (r == null) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Sequence<String> result = (Sequence<String>) getMyInfoFromTarget(r).getValue("command_line");
    return result;
  }

  @Test
  public void testCcCompilationProvider() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        cc_library(
            name = "lib",
            srcs = ["lib.cc"],
            hdrs = ["lib.h"],
            deps = ["r"],
        )

        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            defines = ["DEP1"],
            includes = ["dep1/baz"],
            local_defines = ["LOCALDEP1"],
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            defines = ["DEP2"],
            includes = ["dep2/qux"],
        )

        crule(name = "r")
        """);
    scratch.overwriteFile("tools/build_defs/cc/BUILD", "");
    scratch.file(
        "tools/build_defs/cc/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context(
                headers = depset([ctx.file._header]),
                direct_textual_headers = [ctx.file._textual_header],
                direct_public_headers = [ctx.file._public_header],
                direct_private_headers = [ctx.file._private_header],
                system_includes = depset([ctx.attr._system_include]),
                includes = depset([ctx.attr._include]),
                quote_includes = depset([ctx.attr._quote_include]),
                framework_includes = depset([ctx.attr._framework_include]),
                defines = depset([ctx.attr._define]),
            )
            cc_infos = [CcInfo(compilation_context = compilation_context)]
            for dep in ctx.attr._deps:
                cc_infos.append(dep[CcInfo])
            merged_cc_info = cc_common.merge_cc_infos(cc_infos = cc_infos)
            return [
                merged_cc_info,
                MyInfo(
                    merged_headers = merged_cc_info.compilation_context.headers,
                    textual_headers = compilation_context.direct_textual_headers,
                    public_headers = compilation_context.direct_public_headers,
                    private_headers = compilation_context.direct_private_headers,
                    merged_system_includes = merged_cc_info.compilation_context.system_includes,
                    merged_includes = merged_cc_info.compilation_context.includes,
                    merged_quote_includes = merged_cc_info.compilation_context.quote_includes,
                    merged_framework_includes =
                        merged_cc_info.compilation_context.framework_includes,
                    merged_defines = merged_cc_info.compilation_context.defines,
                ),
            ]

        crule = rule(
            _impl,
            attrs = {
                "_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:header.h"),
                ),
                "_textual_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:textual_header.h"),
                ),
                "_public_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:public_header.h"),
                ),
                "_private_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:private_header.h"),
                ),
                "_system_include": attr.string(default = "foo/bar"),
                "_include": attr.string(default = "baz/qux"),
                "_quote_include": attr.string(default = "quux/abc"),
                "_framework_include": attr.string(default = "fuux/fgh"),
                "_define": attr.string(default = "MYDEFINE"),
                "_local_define": attr.string(default = "MYLOCALDEFINE"),
                "_deps": attr.label_list(default = ["//a:dep1", "//a:dep2"]),
            },
            fragments = ["cpp"],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//a:lib");
    CcCompilationContext ccCompilationContext = lib.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(
            ccCompilationContext.getDeclaredIncludeSrcs().toList().stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("lib.h", "header.h", "dep1.h", "dep2.h");

    StructImpl myInfo = getMyInfoFromTarget(getConfiguredTarget("//a:r"));

    List<Artifact> mergedHeaders =
        ((Depset) myInfo.getValue("merged_headers")).getSet(Artifact.class).toList();
    assertThat(
            mergedHeaders.stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .containsAtLeast("header.h", "dep1.h", "dep2.h");

    @SuppressWarnings("unchecked")
    List<Artifact> textualHeaders =
        ((Sequence<Artifact>) myInfo.getValue("textual_headers")).getImmutableList();
    assertThat(
            textualHeaders.stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .contains("textual_header.h");
    @SuppressWarnings("unchecked")
    List<Artifact> publicHeaders =
        ((Sequence<Artifact>) myInfo.getValue("public_headers")).getImmutableList();
    assertThat(
            publicHeaders.stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .contains("public_header.h");
    @SuppressWarnings("unchecked")
    List<Artifact> privateHeaders =
        ((Sequence<Artifact>) myInfo.getValue("private_headers")).getImmutableList();
    assertThat(
            privateHeaders.stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .contains("private_header.h");

    List<String> mergedDefines =
        ((Depset) myInfo.getValue("merged_defines")).getSet(String.class).toList();
    assertThat(mergedDefines).containsAtLeast("MYDEFINE", "DEP1", "DEP2");
    assertThat(mergedDefines).doesNotContain("LOCALDEP1");

    List<String> mergedSystemIncludes =
        ((Depset) myInfo.getValue("merged_system_includes")).getSet(String.class).toList();
    assertThat(mergedSystemIncludes).containsAtLeast("foo/bar", "a/dep1/baz", "a/dep2/qux");

    List<String> mergedIncludes =
        ((Depset) myInfo.getValue("merged_includes")).getSet(String.class).toList();
    assertThat(mergedIncludes).contains("baz/qux");

    List<String> mergedQuoteIncludes =
        ((Depset) myInfo.getValue("merged_quote_includes")).getSet(String.class).toList();
    assertThat(mergedQuoteIncludes).contains("quux/abc");

    List<String> mergedFrameworkIncludes =
        ((Depset) myInfo.getValue("merged_framework_includes")).getSet(String.class).toList();
    assertThat(mergedFrameworkIncludes).contains("fuux/fgh");
  }

  @Test
  public void testCcCompilationProviderDefaultValues() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        crule(name = "r")
        """);
    scratch.overwriteFile("tools/build_defs/cc/BUILD", "");
    scratch.file(
        "tools/build_defs/cc/rule.bzl",
        """
        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context()

        crule = rule(
            _impl,
            fragments = ["cpp"],
        )
        """);

    assertThat(getConfiguredTarget("//a:r")).isNotNull();
  }

  @Test
  public void testCcCompilationProviderInvalidValues() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "a/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        crule(name = "r")
        """);
    scratch.overwriteFile("tools/build_defs/cc/BUILD", "");
    scratch.file(
        "tools/build_defs/cc/rule.bzl",
        """
        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context(headers = [])

        crule = rule(
            _impl,
            fragments = ["cpp"],
        )
        """);

    getConfiguredTarget("//a:r");
    assertContainsEvent("for headers, got list, want a depset of File");
  }

  @Test
  public void testCreateCompilationOutputs_invalidDepset() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/BUILD",
        """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        """);
    scratch.file(
        "test/my_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                objects = depset([1, 2]),
                pic_objects = depset([1, 2]),
            )

        my_rule = rule(
            _impl,
        )
        """);

    assertThat(getConfiguredTarget("//test:x")).isNull();
    assertContainsEvent("for 'objects', got a depset of 'int', expected a depset of 'File'");
  }

  @Test
  public void testCreateCompilationOutputs_empty() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        """);
    scratch.file(
        "test/my_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs()

        my_rule = rule(
            _impl,
        )
        """);

    assertThat(getConfiguredTarget("//test:x")).isNotNull();
  }

  @Test
  public void testCreateLtoCompilationContextIsPrivate() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        """);
    scratch.file(
        "test/my_rule.bzl",
        """
        def _impl(ctx):
            obj = ctx.actions.declare_file("foo.o")
            thin_link_obj = ctx.actions.declare_file("foo.indexing.o")
            ctx.actions.write(obj, "this string is not a valid object file")
            ctx.actions.write(thin_link_obj, "this string is not a valid thin link object file")
            cc_common.create_lto_compilation_context(objects = {obj: (thin_link_obj, ["-O3"])})

        my_rule = rule(
            _impl,
        )
        """);
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//test:x"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCreateLtoCompilationContext() throws Exception {
    String rustPrefix = "rust/private";
    scratch.file(
        rustPrefix + "/BUILD",
        """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        """);
    scratch.file(
        rustPrefix + "/my_rule.bzl",
        """
        def _impl(ctx):
            obj = ctx.actions.declare_file("foo.o")
            thin_link_obj = ctx.actions.declare_file("foo.indexing.o")
            ctx.actions.write(obj, "this string is not a valid object file")
            ctx.actions.write(thin_link_obj, "this string is not a valid thin link object file")
            cc_common.create_lto_compilation_context(objects = {obj: (thin_link_obj, ["-O3"])})

        my_rule = rule(
            _impl,
        )
        """);
    getConfiguredTarget("//" + rustPrefix + ":x");
    assertNoEvents();
  }

  @Test
  public void testCcLinkingContextOnWindows() throws Exception {
    if (!AnalysisMock.get().isThisBazel()) {
      return;
    }
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                    CppRuleClasses.TARGETS_WINDOWS,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    doTestCcLinkingContext(
        ImmutableList.of("a.a", "libdep2.a", "b.rlib", "c.a", "d.a", "libdep1.a"),
        ImmutableList.of("a.pic.a", "b.rlib", "c.pic.a", "e.pic.a"),
        // The suffix of dynamic library is calculated based on repository name and package path
        // to avoid conflicts with dynamic library from other packages.
        ImmutableList.of("a.so", "libdep2_61.so", "b.so", "e.so", "libdep1_61.so"));
  }

  @Test
  public void testCcLinkingContext() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.PIC,
                    CppRuleClasses.SUPPORTS_PIC,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    doTestCcLinkingContext(
        ImmutableList.of("a.a", "b.rlib", "c.a", "d.a"),
        ImmutableList.of("a.pic.a", "libdep2.a", "b.rlib", "c.pic.a", "e.pic.a", "libdep1.a"),
        ImmutableList.of("a.so", "liba_Slibdep2.so", "b.so", "e.so", "liba_Slibdep1.so"));
  }

  @Test
  public void testCcLinkingContextForExperimentalCcSharedLibrary() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.PIC,
                    CppRuleClasses.SUPPORTS_PIC,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    doTestCcLinkingContext(
        ImmutableList.of("a.a", "b.rlib", "c.a", "d.a"),
        ImmutableList.of("a.pic.a", "libdep2.a", "b.rlib", "c.pic.a", "e.pic.a", "libdep1.a"),
        ImmutableList.of("a.so", "liba_Slibdep2.so", "b.so", "e.so", "liba_Slibdep1.so"));
  }

  /** TODO(#8118): This test can go away once flag is flipped. */
  @Test
  public void testIncompatibleDepsetForLibrariesToLinkGetter() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.PIC,
                    CppRuleClasses.SUPPORTS_PIC,
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER));
    this.setBuildLanguageOptions("--incompatible_depset_for_libraries_to_link_getter");
    setUpCcLinkingContextTest();
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    StructImpl info = ((StructImpl) getMyInfoFromTarget(a).getValue("info"));

    Depset librariesToLink = info.getValue("libraries_to_link", Depset.class);
    assertThat(
            librariesToLink.toList(LibraryToLink.class).stream()
                .filter(x -> x.getStaticLibrary() != null)
                .map(x -> x.getStaticLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("a.a", "b.rlib", "c.a", "d.a");
    assertThat(
            librariesToLink.toList(LibraryToLink.class).stream()
                .filter(x -> x.getPicStaticLibrary() != null)
                .map(x -> x.getPicStaticLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("a.pic.a", "libdep2.a", "b.rlib", "c.pic.a", "e.pic.a", "libdep1.a");
    assertThat(
            librariesToLink.toList(LibraryToLink.class).stream()
                .filter(x -> x.getDynamicLibrary() != null)
                .map(x -> x.getDynamicLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("a.so", "liba_Slibdep2.so", "b.so", "e.so", "liba_Slibdep1.so");
  }

  private static String getSolibRelativePath(Artifact library, CcToolchainProvider toolchain)
      throws EvalException {
    return library.getRootRelativePath().relativeTo(toolchain.getSolibDirectory()).toString();
  }

  @Test
  public void testSolibLinkDefault() throws Exception {
    setUpCcLinkingContextTest();
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            interface_library = "a.ifso",
        )
        """);
    ConfiguredTarget a = getConfiguredTarget("//foo:a");
    RuleContext ruleContext = getRuleContext(a);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    StructImpl info = ((StructImpl) getMyInfoFromTarget(a).getValue("info"));
    Depset librariesToLink = info.getValue("libraries_to_link", Depset.class);
    ImmutableList.Builder<String> dynamicLibSolibRelativePathsBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> interfaceLibSolibRelativePathsBuilder = ImmutableList.builder();
    for (LibraryToLink libraryToLink : librariesToLink.toList(LibraryToLink.class)) {
      if (libraryToLink.getDynamicLibrary() != null) {
        dynamicLibSolibRelativePathsBuilder.add(
            getSolibRelativePath(libraryToLink.getDynamicLibrary(), toolchain));
      }
      if (libraryToLink.getInterfaceLibrary() != null) {
        interfaceLibSolibRelativePathsBuilder.add(
            getSolibRelativePath(libraryToLink.getInterfaceLibrary(), toolchain));
      }
    }
    assertThat(dynamicLibSolibRelativePathsBuilder.build())
        .containsExactly("_U_S_Sfoo_Ca___Ufoo/a.so");
    assertThat(interfaceLibSolibRelativePathsBuilder.build())
        .containsExactly("_U_S_Sfoo_Ca___Ufoo/a.ifso");
  }

  @Test
  public void testSolibLinkCustom() throws Exception {
    setUpCcLinkingContextTest();
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            dynamic_library_symlink_path = "custom/libcustom.so",
            interface_library = "a.ifso",
            interface_library_symlink_path = "libcustom.ifso",
        )
        """);
    ConfiguredTarget a = getConfiguredTarget("//foo:a");
    RuleContext ruleContext = getRuleContext(a);
    CcToolchainProvider toolchain = CppHelper.getToolchain(ruleContext);
    StructImpl info = ((StructImpl) getMyInfoFromTarget(a).getValue("info"));
    Depset librariesToLink = info.getValue("libraries_to_link", Depset.class);
    String solibDir = toolchain.getSolibDirectory();
    assertThat(
            librariesToLink.toList(LibraryToLink.class).stream()
                .filter(x -> x.getDynamicLibrary() != null)
                .map(
                    x ->
                        x.getDynamicLibrary()
                            .getRootRelativePath()
                            .relativeTo(solibDir)
                            .toString()))
        .containsExactly("custom/libcustom.so");
    assertThat(
            librariesToLink.toList(LibraryToLink.class).stream()
                .filter(x -> x.getInterfaceLibrary() != null)
                .map(
                    x ->
                        x.getInterfaceLibrary()
                            .getRootRelativePath()
                            .relativeTo(solibDir)
                            .toString()))
        .containsExactly("libcustom.ifso");
  }

  @Test
  public void testReallyLongSolibLink() throws Exception {
    setUpCcLinkingContextTest();

    String longpath =
        "this/is/a/really/really/really/really/really/really/really/really/really/really/"
            + "really/really/really/really/really/really/really/really/really/really/really/"
            + "really/really/long/path/that/generates/really/long/solib/link/path";
    scratch.file(
        longpath + "/BUILD",
        "load('//tools/build_defs/cc:rule.bzl', 'crule')",
        "crule(name='a',",
        "   dynamic_library = 'a.so',",
        ")");

    ConfiguredTarget a = getConfiguredTarget("//" + longpath + ":a");
    StructImpl info = ((StructImpl) getMyInfoFromTarget(a).getValue("info"));
    Depset librariesToLink = info.getValue("libraries_to_link", Depset.class);
    ImmutableList<String> dynamicLibraryParentDirectories =
        librariesToLink.toList(LibraryToLink.class).stream()
            .filter(x -> x.getDynamicLibrary() != null)
            .map(
                x -> x.getDynamicLibrary().getRootRelativePath().getParentDirectory().getBaseName())
            .collect(ImmutableList.toImmutableList());
    for (String dynamicLibraryParentDirectory : dynamicLibraryParentDirectories) {
      assertThat(dynamicLibraryParentDirectory.length()).isLessThan(MAX_FILENAME_LENGTH + 1);
    }
  }

  private void doTestCcLinkingContext(
      List<String> staticLibraryList,
      List<String> picStaticLibraryList,
      List<String> dynamicLibraryList)
      throws Exception {
    useConfiguration("--features=-supports_interface_shared_libraries");
    this.setBuildLanguageOptions("--incompatible_depset_for_libraries_to_link_getter");
    setUpCcLinkingContextTest();
    ConfiguredTarget a = getConfiguredTarget("//a:a");

    StructImpl info = ((StructImpl) getMyInfoFromTarget(a).getValue("info"));
    @SuppressWarnings("unchecked")
    Sequence<String> userLinkFlags =
        (Sequence<String>) info.getValue("user_link_flags", Sequence.class);
    assertThat(userLinkFlags.getImmutableList())
        .containsExactly("-la", "-lc2", "-DEP2_LINKOPT", "-lc1", "-lc2", "-DEP1_LINKOPT");
    Depset additionalInputs = info.getValue("additional_inputs", Depset.class);
    assertThat(additionalInputs.toList(Artifact.class).stream().map(Artifact::getFilename))
        .containsAtLeast("b.lds", "d.lds"); // On Windows also .def files
    Depset linkstamps = info.getValue("linkstamps", Depset.class);
    assertThat(
            artifactsToStrings(
                linkstamps.toList(Linkstamp.class).stream()
                    .map(Linkstamp::getArtifact)
                    .collect(ImmutableList.toImmutableList())))
        .containsExactly("src a/linkstamp.cc");
    Collection<LibraryToLink> librariesToLink =
        info.getValue("libraries_to_link", Depset.class).toList(LibraryToLink.class);
    assertThat(
            librariesToLink.stream()
                .filter(x -> x.getStaticLibrary() != null)
                .map(x -> x.getStaticLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactlyElementsIn(staticLibraryList);
    assertThat(
            librariesToLink.stream()
                .filter(x -> x.getPicStaticLibrary() != null)
                .map(x -> x.getPicStaticLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactlyElementsIn(picStaticLibraryList);
    assertThat(
            librariesToLink.stream()
                .filter(x -> x.getDynamicLibrary() != null)
                .map(x -> x.getDynamicLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactlyElementsIn(dynamicLibraryList);
    assertThat(
            librariesToLink.stream()
                .filter(x -> x.getInterfaceLibrary() != null)
                .map(x -> x.getInterfaceLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .containsExactly("a.ifso");
    Artifact staticLibrary = info.getValue("static_library", Artifact.class);
    assertThat(staticLibrary.getFilename()).isEqualTo("a.a");
    Artifact picStaticLibrary = info.getValue("pic_static_library", Artifact.class);
    assertThat(picStaticLibrary.getFilename()).isEqualTo("a.pic.a");
    Artifact dynamicLibrary = info.getValue("dynamic_library", Artifact.class);
    assertThat(dynamicLibrary.getFilename()).isEqualTo("a.so");
    Artifact interfaceLibrary = info.getValue("interface_library", Artifact.class);
    assertThat(interfaceLibrary.getFilename()).isEqualTo("a.ifso");
    boolean alwayslink = info.getValue("alwayslink", Boolean.class);
    assertThat(alwayslink).isTrue();

    ConfiguredTarget bin = getConfiguredTarget("//a:bin");
    assertThat(bin).isNotNull();
  }

  private void setUpCcLinkingContextTest() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            interface_library = "a.ifso",
            pic_static_library = "a.pic.a",
            static_library = "a.a",
            user_link_flags = [
                "-la",
                "-lc2",
            ],
            deps = [
                ":b",
                ":c",
                ":dep2",
            ],
            alwayslink = True,
        )

        crule(
            name = "b",
            additional_inputs = ["b.lds"],
            dynamic_library = "b.so",
            pic_static_library = "b.rlib",
            static_library = "b.rlib",
            deps = [
                ":c",
                ":d",
            ],
        )

        crule(
            name = "c",
            pic_static_library = "c.pic.a",
            static_library = "c.a",
            user_link_flags = [
                "-lc1",
                "-lc2",
            ],
        )

        crule(
            name = "d",
            additional_inputs = ["d.lds"],
            static_library = "d.a",
            deps = [":e"],
            alwayslink = True,
        )

        crule(
            name = "e",
            dynamic_library = "e.so",
            pic_static_library = "e.pic.a",
            deps = [":dep1"],
        )

        cc_toolchain_alias(name = "alias")

        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            linkopts = ["-DEP1_LINKOPT"],
            linkstamp = "linkstamp.cc",
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            linkopts = ["-DEP2_LINKOPT"],
        )
        """);
    scratch.file("a/lib.a", "");
    scratch.file("a/lib.so", "");
    scratch.overwriteFile("tools/build_defs/cc/BUILD", "");
    scratch.file(
        "tools/build_defs/cc/rule.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "load('"
            + TestConstants.RULES_CC
            + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')",
        "linker_input = cc_common.create_linker_input(",
        "                 owner=Label('//toplevel'),",
        "                 user_link_flags=[['-first_flag'], ['-second_flag']])",
        "top_linking_context_smoke = cc_common.create_linking_context(",
        "   linker_inputs=depset([linker_input]))",
        "def _create(ctx, feature_configuration, static_library, pic_static_library,",
        "  dynamic_library,",
        "  interface_library, dynamic_library_symlink_path, interface_library_symlink_path,",
        "  alwayslink, objects, pic_objects):",
        "  return cc_common.create_library_to_link(",
        "    actions=ctx.actions, feature_configuration=feature_configuration, ",
        "    cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo], ",
        "    static_library=static_library, pic_static_library=pic_static_library,",
        "    dynamic_library=dynamic_library, interface_library=interface_library,",
        "    dynamic_library_symlink_path=dynamic_library_symlink_path,",
        "    interface_library_symlink_path=interface_library_symlink_path,",
        "    alwayslink=alwayslink, ",
        "    )",
        "def _impl(ctx):",
        "  toolchain = find_cc_toolchain(ctx)",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  library_to_link = _create(ctx, feature_configuration, ctx.file.static_library, ",
        "     ctx.file.pic_static_library, ctx.file.dynamic_library, ctx.file.interface_library,",
        "     ctx.attr.dynamic_library_symlink_path,",
        "     ctx.attr.interface_library_symlink_path,",
        "     ctx.attr.alwayslink, ctx.files.objects, ctx.files.pic_objects)",
        "  linker_input = cc_common.create_linker_input(",
        "                   owner=ctx.label,",
        "                   libraries=depset([library_to_link]),",
        "                   user_link_flags=depset(ctx.attr.user_link_flags),",
        "                   additional_inputs=depset(ctx.files.additional_inputs))",
        "  linking_context = cc_common.create_linking_context(",
        "     linker_inputs=depset([linker_input]))",
        "  cc_infos = [CcInfo(linking_context=linking_context)]",
        "  for dep in ctx.attr.deps:",
        "      cc_infos.append(dep[CcInfo])",
        "  merged_cc_info = cc_common.merge_cc_infos(cc_infos=cc_infos)",
        "  merged_libraries = []",
        "  merged_additional_inputs = []",
        "  merged_user_link_flags = []",
        "  merged_linkstamps = []",
        "  for l in merged_cc_info.linking_context.linker_inputs.to_list():",
        "      merged_libraries.extend(l.libraries)",
        "      merged_additional_inputs.extend(l.additional_inputs)",
        "      merged_user_link_flags.extend(l.user_link_flags)",
        "      merged_linkstamps.extend(l.linkstamps)",
        "  linkstamps_linker_input = cc_common.create_linker_input(",
        "                   owner=ctx.label,",
        "                   linkstamps=depset(merged_linkstamps))",
        "  return [",
        "     MyInfo(",
        "         info = struct(",
        "             cc_info = merged_cc_info,",
        "             user_link_flags = merged_user_link_flags,",
        "             additional_inputs = depset(merged_additional_inputs),",
        "             libraries_to_link = depset(merged_libraries),",
        "             linkstamps = depset(linkstamps_linker_input.linkstamps),",
        "             static_library = library_to_link.static_library,",
        "             pic_static_library = library_to_link.pic_static_library,",
        "             dynamic_library = library_to_link.dynamic_library,",
        "             interface_library = library_to_link.interface_library,",
        "             alwayslink = library_to_link.alwayslink,",
        "             objects = library_to_link.objects,",
        "             pic_objects = library_to_link.pic_objects),",
        "      ),",
        "      merged_cc_info]",
        "crule = rule(",
        "  _impl,",
        "  attrs = { ",
        "    'user_link_flags' : attr.string_list(),",
        "    'additional_inputs': attr.label_list(allow_files=True),",
        "    'static_library': attr.label(allow_single_file=True),",
        "    'pic_static_library': attr.label(allow_single_file=True),",
        "    'dynamic_library': attr.label(allow_single_file=True),",
        "    'dynamic_library_symlink_path': attr.string(),",
        "    'interface_library': attr.label(allow_single_file=True),",
        "    'interface_library_symlink_path': attr.string(),",
        "    'objects': attr.label_list(allow_files=True),",
        "    'pic_objects': attr.label_list(allow_files=True),",
        "    'alwayslink': attr.bool(),",
        "    '_cc_toolchain': attr.label(default=Label('//a:alias')),",
        "    'deps': attr.label_list(),",
        "  },",
        "  fragments = ['cpp'],",
        "  toolchains = use_cc_toolchain()",
        ");");
  }

  private void loadCcToolchainConfigLib() throws IOException {
    scratch.appendFile("tools/cpp/BUILD", "");
    scratch.overwriteFile(
        "tools/cpp/cc_toolchain_config_lib.bzl",
        ResourceLoader.readFromResources(
            TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"));
  }

  @Test
  public void testVariableWithValue() throws Exception {
    loadCcToolchainConfigLib();
    createVariableWithValueRule("one", /* name= */ "None", /* value= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of variable_with_value should be a string, found NoneType");

    createVariableWithValueRule("two", /* name= */ "'abc'", /* value= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of variable_with_value should be a string, found NoneType");

    createVariableWithValueRule("three", /* name= */ "''", /* value= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of variable_with_value must be a nonempty string");

    createVariableWithValueRule("four", /* name= */ "'abc'", /* value= */ "''");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of variable_with_value must be a nonempty string");

    createVariableWithValueRule("five", /* name= */ "'abc'", /* value= */ "'def'");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    assertThat(variable).isNotNull();
    VariableWithValue v = CcModule.variableWithValueFromStarlark(variable);
    assertThat(v).isNotNull();
    assertThat(v.variable).isEqualTo("abc");
    assertThat(v.value).isEqualTo("def");

    createEnvEntryRule("six", /* key= */ "'abc'", /* value= */ "'def'");
    t = getConfiguredTarget("//six:a");
    StarlarkInfo envEntry = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    EvalException ee =
        assertThrows(EvalException.class, () -> CcModule.variableWithValueFromStarlark(envEntry));
    assertThat(ee)
        .hasMessageThat()
        .contains("Expected object of type 'variable_with_value', received 'env_entry");
  }

  private void createVariableWithValueRule(String pkg, String name, String value)
      throws IOException {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'variable_with_value')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(variable = variable_with_value(",
        "       name = " + name + ",",
        "       value = " + value + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomVariableWithValue_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomVariableWithValueRule("one", /* name= */ "None", /* value= */ "None");
    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.variableWithValueFromStarlark(variable));
    assertThat(e)
        .hasMessageThat()
        .contains("'name' parameter of variable_with_value must be a nonempty string.");
  }

  @Test
  public void testCustomVariableWithValue_string_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomVariableWithValueRule("two", /* name= */ "'abc'", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.variableWithValueFromStarlark(variable));
    assertThat(e)
        .hasMessageThat()
        .contains("'value' parameter of variable_with_value must be a nonempty string.");
  }

  @Test
  public void testCustomVariableWithValue_string_struct() throws Exception {
    loadCcToolchainConfigLib();
    createCustomVariableWithValueRule("three", /* name= */ "'abc'", /* value= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.variableWithValueFromStarlark(variable));
    assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomVariableWithValue_boolean_string() throws Exception {
    loadCcToolchainConfigLib();
    createCustomVariableWithValueRule("four", /* name= */ "True", /* value= */ "'abc'");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.variableWithValueFromStarlark(variable));
    assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.");
  }

  private void createCustomVariableWithValueRule(String pkg, String name, String value)
      throws IOException {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(variable = struct(",
        "       name = " + name + ",",
        "       value = " + value + ",",
        "       type_name = 'variable_with_value'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testEnvEntry_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvEntryRule("one", "None", /* value= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("key parameter of env_entry should be a string, found NoneType");
  }

  @Test
  public void testEnvEntry_string_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvEntryRule("two", "'abc'", /* value= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of env_entry should be a string, found NoneType");
  }

  @Test
  public void testEnvEntry_emptyString_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvEntryRule("three", "''", /* value= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e).hasMessageThat().contains("key parameter of env_entry must be a nonempty string");
  }

  @Test
  public void testEnvEntry_string_emptyString() throws Exception {
    loadCcToolchainConfigLib();
    createEnvEntryRule("four", "'abc'", /* value= */ "''");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of env_entry must be a nonempty string");
  }

  @Test
  public void testEnvEntry_string_string() throws Exception {
    loadCcToolchainConfigLib();
    createEnvEntryRule("five", "'abc'", /* value= */ "'def'");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo entryProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    assertThat(entryProvider).isNotNull();
    EnvEntry entry = CcModule.envEntryFromStarlark(entryProvider);
    assertThat(entry).isNotNull();
    StringValueParser parser = new StringValueParser("def");
    assertThat(entry).isEqualTo(new EnvEntry("abc", parser.getChunks(), ImmutableSet.of()));
  }

  @Test
  public void testEnvEntryVariable_string_string() throws Exception {
    loadCcToolchainConfigLib();
    createVariableWithValueRule("six", /* name= */ "'abc'", /* value= */ "'def'");
    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envEntryFromStarlark(variable));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'env_entry', received 'variable_with_value");
  }

  private void createEnvEntryRule(String pkg, String key, String value) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'env_entry')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(entry = env_entry(",
        "       key = " + key + ",",
        "       value = " + value + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomEnvEntry_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvEntryRule("one", /* key= */ "None", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo entry = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    EvalException e = assertThrows(EvalException.class, () -> CcModule.envEntryFromStarlark(entry));
    assertThat(e)
        .hasMessageThat()
        .contains("'key' parameter of env_entry must be a nonempty string.");
  }

  @Test
  public void testCustomEnvEntry_string_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvEntryRule("two", /* key= */ "'abc'", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo entry = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    EvalException e =
        assertThrows(
            "Should have failed because of empty string.",
            EvalException.class,
            () -> CcModule.envEntryFromStarlark(entry));
    assertThat(e)
        .hasMessageThat()
        .contains("'value' parameter of env_entry must be a nonempty string.");
  }

  @Test
  public void testCustomEnvEntry_string_struct() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvEntryRule("three", /* key= */ "'abc'", /* value= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo entry = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    EvalException e = assertThrows(EvalException.class, () -> CcModule.envEntryFromStarlark(entry));
    assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomEnvEntry_boolean_string() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvEntryRule("four", /* key= */ "True", /* value= */ "'abc'");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo entry = (StarlarkInfo) getMyInfoFromTarget(t).getValue("entry");
    EvalException e = assertThrows(EvalException.class, () -> CcModule.envEntryFromStarlark(entry));
    assertThat(e).hasMessageThat().contains("Field 'key' is not of 'java.lang.String' type.");
  }

  private void createCustomEnvEntryRule(String pkg, String key, String value) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(entry = struct(",
        "       key = " + key + ",",
        "       value = " + value + ",",
        "       type_name = 'env_entry'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testToolPath_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createToolPathRule("one", /* name= */ "None", "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of tool_path should be a string, found NoneType");
  }

  @Test
  public void testToolPath_string_none() throws Exception {
    loadCcToolchainConfigLib();
    createToolPathRule("two", /* name= */ "'abc'", "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("path parameter of tool_path should be a string, found NoneType");
  }

  @Test
  public void testToolPath_emptyString_none() throws Exception {
    loadCcToolchainConfigLib();
    createToolPathRule("three", /* name= */ "''", "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of tool_path must be a nonempty string");
  }

  @Test
  public void testToolPath_string_emptyString() throws Exception {
    loadCcToolchainConfigLib();
    createToolPathRule("four", /* name= */ "'abc'", "''");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("path parameter of tool_path must be a nonempty string");
  }

  @Test
  public void testToolPath_string_escapedString() throws Exception {
    loadCcToolchainConfigLib();
    createToolPathRule("five", /* name= */ "'abc'", "'/d/e/f'");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo toolPathProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("toolpath");
    assertThat(toolPathProvider).isNotNull();
    Pair<String, String> toolPath = CcModule.toolPathFromStarlark(toolPathProvider);
    assertThat(toolPath).isNotNull();
    assertThat(toolPath.first).isEqualTo("abc");
    assertThat(toolPath.second).isEqualTo("/d/e/f");
  }

  @Test
  public void testToolPath_string_string() throws Exception {
    loadCcToolchainConfigLib();
    createVariableWithValueRule("six", /* name= */ "'abc'", /* value= */ "'def'");
    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolPathFromStarlark(variable));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'tool_path', received 'variable_with_value");
  }

  private void createToolPathRule(String pkg, String name, String path) throws IOException {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'tool_path')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(toolpath = tool_path(",
        "       name = " + name + ",",
        "       path = " + path + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomToolPath_name_mustBeNonEmpty() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolPathRule("one", /* name= */ "None", /* path= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo toolPath = (StarlarkInfo) getMyInfoFromTarget(t).getValue("toolpath");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolPathFromStarlark(toolPath));
    assertThat(e)
        .hasMessageThat()
        .contains("'name' parameter of tool_path must be a nonempty string.");
  }

  @Test
  public void testCustomToolPath_path_mustBeNonEmpty() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolPathRule("two", /* name= */ "'abc'", /* path= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo toolPath = (StarlarkInfo) getMyInfoFromTarget(t).getValue("toolpath");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolPathFromStarlark(toolPath));
    assertThat(e)
        .hasMessageThat()
        .contains("'path' parameter of tool_path must be a nonempty string.");
  }

  @Test
  public void testCustomToolPath_path_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolPathRule("three", /* name= */ "'abc'", /* path= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo toolPath = (StarlarkInfo) getMyInfoFromTarget(t).getValue("toolpath");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolPathFromStarlark(toolPath));
    assertThat(e).hasMessageThat().contains("Field 'path' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomToolPath_name_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolPathRule("four", /* name= */ "True", /* path= */ "'abc'");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo toolPath = (StarlarkInfo) getMyInfoFromTarget(t).getValue("toolpath");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolPathFromStarlark(toolPath));
    assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.");
  }

  private void createCustomToolPathRule(String pkg, String name, String path) throws IOException {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'tool_path')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(toolpath = struct(",
        "       name = " + name + ",",
        "       path = " + path + ",",
        "       type_name = 'tool_path'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testMakeVariable() throws Exception {
    loadCcToolchainConfigLib();
    createMakeVariablerule("one", /* name= */ "None", /* value= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of make_variable should be a string, found NoneType");

    createMakeVariablerule("two", /* name= */ "'abc'", /* value= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of make_variable should be a string, found NoneType");

    createMakeVariablerule("three", /* name= */ "''", /* value= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of make_variable must be a nonempty string");

    createMakeVariablerule("four", /* name= */ "'abc'", /* value= */ "''");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("value parameter of make_variable must be a nonempty string");

    createMakeVariablerule("five", /* name= */ "'abc'", /* value= */ "'val'");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo makeVariableProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    assertThat(makeVariableProvider).isNotNull();
    Pair<String, String> makeVariable = CcModule.makeVariableFromStarlark(makeVariableProvider);
    assertThat(makeVariable).isNotNull();
    assertThat(makeVariable.first).isEqualTo("abc");
    assertThat(makeVariable.second).isEqualTo("val");

    createVariableWithValueRule("six", /* name= */ "'abc'", /* value= */ "'def'");
    t = getConfiguredTarget("//six:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException ee =
        assertThrows(EvalException.class, () -> CcModule.makeVariableFromStarlark(variable));
    assertThat(ee)
        .hasMessageThat()
        .contains("Expected object of type 'make_variable', received 'variable_with_value");
  }

  private void createMakeVariablerule(String pkg, String name, String value) throws IOException {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'make_variable')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(variable = make_variable(",
        "       name = " + name + ",",
        "       value = " + value + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomMakeVariable_none_none() throws Exception {
    createCustomMakeVariableRule("one", /* name= */ "None", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo makeVariableProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.makeVariableFromStarlark(makeVariableProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("'name' parameter of make_variable must be a nonempty string.");
  }

  @Test
  public void testCustomMakeVariable_string_none() throws Exception {
    createCustomMakeVariableRule("two", /* name= */ "'abc'", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo makeVariableProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.makeVariableFromStarlark(makeVariableProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("'value' parameter of make_variable must be a nonempty string.");
  }

  @Test
  public void testCustomMakeVariable_list_none() throws Exception {
    createCustomMakeVariableRule("three", /* name= */ "[]", /* value= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo makeVariableProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.makeVariableFromStarlark(makeVariableProvider));
    assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomMakeVariable_string_boolean() throws Exception {
    createCustomMakeVariableRule("four", /* name= */ "'abc'", /* value= */ "True");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo makeVariableProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.makeVariableFromStarlark(makeVariableProvider));
    assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.");
  }

  private void createCustomMakeVariableRule(String pkg, String name, String value)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(variable = struct(",
        "       name = " + name + ",",
        "       value = " + value + ",",
        "       type_name = 'make_variable'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testWithFeatureSet() throws Exception {
    loadCcToolchainConfigLib();
    createWithFeatureSetRule("one", /* features= */ "None", /* notFeatures= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("features parameter of with_feature_set should be a list, found NoneType");

    createWithFeatureSetRule("two", /* features= */ "['abc']", /* notFeatures= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("not_features parameter of with_feature_set should be a list, found NoneType");

    createWithFeatureSetRule("three", /* features= */ "'asdf'", /* notFeatures= */ "None");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("features parameter of with_feature_set should be a list, found string");

    createWithFeatureSetRule("four", /* features= */ "['abc']", /* notFeatures= */ "'def'");

    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("not_features parameter of with_feature_set should be a list, found string");

    createWithFeatureSetRule(
        "five", /* features= */ "['f1', 'f2']", /* notFeatures= */ "['nf1', 'nf2']");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo withFeatureSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("wfs");
    assertThat(withFeatureSetProvider).isNotNull();
    WithFeatureSet withFeatureSet = CcModule.withFeatureSetFromStarlark(withFeatureSetProvider);
    assertThat(withFeatureSet).isNotNull();
    assertThat(withFeatureSet.getFeatures()).containsExactly("f1", "f2");
    assertThat(withFeatureSet.getNotFeatures()).containsExactly("nf1", "nf2");

    createVariableWithValueRule("six", /* name= */ "'abc'", /* value= */ "'def'");
    t = getConfiguredTarget("//six:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException ee =
        assertThrows(EvalException.class, () -> CcModule.withFeatureSetFromStarlark(variable));
    assertThat(ee)
        .hasMessageThat()
        .contains("Expected object of type 'with_feature_set', received 'variable_with_value");
  }

  private void createWithFeatureSetRule(String pkg, String features, String notFeatures)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(wfs = with_feature_set(",
        "       features = " + features + ",",
        "       not_features = " + notFeatures + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomWithFeatureSet_struct_none() throws Exception {
    createCustomWithFeatureSetRule("one", /* features= */ "struct()", /* notFeatures= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo withFeatureSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("wfs");
    assertThat(withFeatureSetProvider).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.withFeatureSetFromStarlark(withFeatureSetProvider));
    assertThat(e).hasMessageThat().contains("for features, got struct, want sequence");
  }

  @Test
  public void testCustomWithFeatureSet_listOfString_struct() throws Exception {
    createCustomWithFeatureSetRule("two", /* features= */ "['abc']", /* notFeatures= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo withFeatureSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("wfs");
    assertThat(withFeatureSetProvider).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.withFeatureSetFromStarlark(withFeatureSetProvider));
    assertThat(e).hasMessageThat().contains("for not_features, got struct, want sequence");
  }

  @Test
  public void testCustomWithFeatureSet_listOfStruct_emptyList() throws Exception {
    createCustomWithFeatureSetRule("three", /* features= */ "[struct()]", /* notFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo withFeatureSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("wfs");
    assertThat(withFeatureSetProvider).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.withFeatureSetFromStarlark(withFeatureSetProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of features, got element of type struct, want string");
  }

  @Test
  public void testCustomWithFeatureSet_emptyList_listOfStruct() throws Exception {
    createCustomWithFeatureSetRule("four", /* features= */ "[]", /* notFeatures= */ "[struct()]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo withFeatureSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("wfs");
    assertThat(withFeatureSetProvider).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.withFeatureSetFromStarlark(withFeatureSetProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of not_features, got element of type struct, want string");
  }

  private void createCustomWithFeatureSetRule(String pkg, String features, String notFeatures)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(wfs = struct(",
        "       features = " + features + ",",
        "       not_features = " + notFeatures + ",",
        "       type_name = 'with_feature_set'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testEnvSet_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "one", /* actions= */ "['a1']", /* envEntries= */ "None", /* withFeatures= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("env_entries parameter of env_set should be a list, found NoneType");
  }

  @Test
  public void testEnvSet_list_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "two", /* actions= */ "['a1']", /* envEntries= */ "['abc']", /* withFeatures= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("with_features parameter of env_set should be a list, found NoneType");
  }

  @Test
  public void testEnvSet_string_none() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "three", /* actions= */ "['a1']", /* envEntries= */ "'asdf'", /* withFeatures= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("env_entries parameter of env_set should be a list, found string");
  }

  @Test
  public void testEnvSet_list_string() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "four", /* actions= */ "['a1']", /* envEntries= */ "['abc']", /* withFeatures= */ "'def'");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("with_features parameter of env_set should be a list, found string");
  }

  @Test
  public void testEnvSet_envEntry_emptyList() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "five",
        /* actions= */ "['a1']",
        /* envEntries= */ "[env_entry(key = 'a', value = 'b'),"
            + "variable_with_value(name = 'a', value = 'b')]",
        /* withFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'env_entry', received 'variable_with_value'");
  }

  @Test
  public void testEnvSet_emptyList_emptyList() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule("six", /* actions= */ "[]", /* envEntries= */ "[]", /* withFeatures= */ "[]");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//six:a"));
    assertThat(e).hasMessageThat().contains("actions parameter of env_set must be a nonempty list");
  }

  @Test
  public void testEnvSet_envEntry_featureSet() throws Exception {
    loadCcToolchainConfigLib();
    createEnvSetRule(
        "seven",
        /* actions= */ "['a1']",
        /* envEntries= */ "[env_entry(key = 'a', value = 'b')]",
        /* withFeatures= */ "[with_feature_set(features = ['a'])]");

    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EnvSet envSet = CcModule.envSetFromStarlark(envSetProvider);
    assertThat(envSet).isNotNull();
  }

  @Test
  public void testEnvSet_string_string() throws Exception {
    loadCcToolchainConfigLib();
    createVariableWithValueRule("eight", /* name= */ "'abc'", /* value= */ "'def'");
    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo variable = (StarlarkInfo) getMyInfoFromTarget(t).getValue("variable");
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(variable));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'env_set', received 'variable_with_value");
  }

  private void createEnvSetRule(String pkg, String actions, String envEntries, String withFeatures)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(envset = env_set(",
        "       actions = " + actions + ",",
        "       env_entries = " + envEntries + ",",
        "       with_features = " + withFeatures + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomEnvSet_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvSetRule(
        "one", /* actions= */ "[]", /* envEntries= */ "None", /* withFeatures= */ "None");
    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e).hasMessageThat().contains("actions parameter of env_set must be a nonempty list");
  }

  @Test
  public void testCustomEnvSet_struct_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvSetRule(
        "two", /* actions= */ "['a1']", /* envEntries= */ "struct()", /* withFeatures= */ "None");
    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e).hasMessageThat().contains("for env_entries, got struct, want sequence");
  }

  @Test
  public void testCustomEnvSet_structList_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvSetRule(
        "three",
        /* actions= */ "['a1']",
        /* envEntries= */ "[struct()]",
        /* withFeatures= */ "None");
    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'env_entry', received 'struct'");
  }

  @Test
  public void testCustomEnvSet_envEntry_string() throws Exception {
    loadCcToolchainConfigLib();
    createCustomEnvSetRule(
        "four",
        /* actions= */ "['a1']",
        /* envEntries= */ "[env_entry(key = 'a', value = 'b')]",
        /* withFeatures= */ "'a'");
    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e).hasMessageThat().contains("for with_features, got string, want sequence");
  }

  @Test
  public void testCustomEnvSet_envEntry_envEntry() throws Exception {
    loadCcToolchainConfigLib();

    createCustomEnvSetRule(
        "five",
        /* actions= */ "['a1']",
        /* envEntries= */ "[env_entry(key = 'a', value = 'b')]",
        /* withFeatures= */ "[env_entry(key = 'a', value = 'b')]");
    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo envSetProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("envset");
    assertThat(envSetProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.envSetFromStarlark(envSetProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'with_feature_set', received 'env_entry'.");
  }

  private void createCustomEnvSetRule(
      String pkg, String actions, String envEntries, String withFeatures) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_entry', 'with_feature_set', 'variable_with_value')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(envset = struct(",
        "       actions = " + actions + ",",
        "       env_entries = " + envEntries + ",",
        "       with_features = " + withFeatures + ",",
        "       type_name = 'env_set'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testFlagGroup_flagGroup_notListofFlags() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "one",
        /* flags= */ "[]",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "None",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("flag_group must contain either a list of flags or a list of flag_groups");
  }

  @Test
  public void testFlagGroup_iterateOver_notString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "two",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "struct(val = 'a')",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "None",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("iterate_over parameter of flag_group should be a string, found struct");
  }

  @Test
  public void testFlagGroup_expandIfTrue_notString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "three",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "struct(val = 'a')",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "None",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("expand_if_true parameter of flag_group should be a string, found struct");
  }

  @Test
  public void testFlagGroup_expandIfFalse_notString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "four",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "struct(val = 'a')",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "None",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//four:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("expand_if_false parameter of flag_group should be a string, found struct");
  }

  @Test
  public void testFlagGroup_expandIfAvailable_notString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "five",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "struct(val = 'a')",
        /* expandIfNotAvailable= */ "None",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//five:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("expand_if_available parameter of flag_group should be a string, found struct");
  }

  @Test
  public void testFlagGroup_expandIfNotAvailable_notString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "six",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "struct(val = 'a')",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//six:a"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "expand_if_not_available parameter of flag_group should be a string, found struct");
  }

  @Test
  public void testFlagGroup_flagGroup_cannotContainFlagAndGroup() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "seven",
        /* flags= */ "['a']",
        /* flagGroups= */ "['b']",
        /* iterateOver= */ "None",
        /* expandIfTrue= */ "None",
        /* expandIfFalse= */ "None",
        /* expandIfAvailable= */ "None",
        /* expandIfNotAvailable= */ "struct(val = 'a')",
        /* expandIfEqual= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//seven:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("flag_group must not contain both a flag and another flag_group");
  }

  @Test
  public void testFlagGroup_expandIfEqual_notStarlarkInfo() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "eight",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "'a'",
        /* expandIfTrue= */ "'b'",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Field 'expand_if_equal' is not of "
                + "'com.google.devtools.build.lib.packages.StarlarkInfo' type.");
  }

  @Test
  public void testFlagGroup() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "nine",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a']), flag_group(flags = ['b'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//nine:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    FlagGroup f = CcModule.flagGroupFromStarlark(flagGroupProvider);
    assertThat(f).isNotNull();
  }

  @Test
  public void testFlagGroup_flagGroup_notStruct() throws Exception {
    loadCcToolchainConfigLib();
    createFlagGroupRule(
        "ten",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a']), struct(value = 'a')]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//ten:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'flag_group', received 'struct'");
  }

  private void createFlagGroupRule(
      String pkg,
      String flags,
      String flagGroups,
      String iterateOver,
      String expandIfTrue,
      String expandIfFalse,
      String expandIfAvailable,
      String expandIfNotAvailable,
      String expandIfEqual)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(flaggroup = flag_group(",
        "       flags = " + flags + ",",
        "       flag_groups = " + flagGroups + ",",
        "       expand_if_true = " + expandIfTrue + ",",
        "       expand_if_false = " + expandIfFalse + ",",
        "       expand_if_available = " + expandIfAvailable + ",",
        "       expand_if_not_available = " + expandIfNotAvailable + ",",
        "       expand_if_equal = " + expandIfEqual + ",",
        "       iterate_over = " + iterateOver + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testSingleChunkFlagIsUsed() throws Exception {
    loadCcToolchainConfigLib();

    createCustomFlagGroupRule(
        "single_chunk_flag",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//single_chunk_flag:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    FlagGroup flagGroup = CcModule.flagGroupFromStarlark(flagGroupProvider);
    assertThat(flagGroup.getExpandables()).isNotEmpty();
    assertThat(flagGroup.getExpandables().get(0)).isInstanceOf(SingleChunkFlag.class);
  }

  @Test
  public void testCustomFlagGroup_iterateOver_notString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "one",
        /* flags= */ "['a']",
        /* flagGroups= */ "[]",
        /* iterateOver= */ "struct()",
        /* expandIfTrue= */ "'b'",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'iterate_over' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFlagGroup_expandIfTrue_notString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "two",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a']), flag_group(flags = ['b'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "struct()",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'expand_if_true' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFlagGroup_expandIfFalse_notString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "three",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "True",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'expand_if_false' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFlagGroup_expandIfAvailable_notString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "four",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "struct()",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'expand_if_available' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFlagGroup_expandIfNotAvailable_notString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "five",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "3",
        /* expandIfEqual= */ "variable_with_value(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'expand_if_not_available' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFlagGroup_expandIfEqual_notStruct() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagGroupRule(
        "six",
        /* flags= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* iterateOver= */ "''",
        /* expandIfTrue= */ "''",
        /* expandIfFalse= */ "''",
        /* expandIfAvailable= */ "''",
        /* expandIfNotAvailable= */ "''",
        /* expandIfEqual= */ "struct(name = 'a', value = 'b')");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo flagGroupProvider = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flaggroup");
    assertThat(flagGroupProvider).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.flagGroupFromStarlark(flagGroupProvider));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'variable_with_value', received 'struct'.");
  }

  private void createCustomFlagGroupRule(
      String pkg,
      String flags,
      String flagGroups,
      String iterateOver,
      String expandIfTrue,
      String expandIfFalse,
      String expandIfAvailable,
      String expandIfNotAvailable,
      String expandIfEqual)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(flaggroup = struct(",
        "       flags = " + flags + ",",
        "       flag_groups = " + flagGroups + ",",
        "       expand_if_true = " + expandIfTrue + ",",
        "       expand_if_false = " + expandIfFalse + ",",
        "       expand_if_available = " + expandIfAvailable + ",",
        "       expand_if_not_available = " + expandIfNotAvailable + ",",
        "       expand_if_equal = " + expandIfEqual + ",",
        "       iterate_over = " + iterateOver + ",",
        "       type_name = 'flag_group'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testTool_path_mustBeNonEmpty() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule("one", /* path= */ "''", /* withFeatures= */ "[]", /* requirements= */ "[]");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e).hasMessageThat().contains("path parameter of tool must be a nonempty string");
  }

  @Test
  public void testTool_withFeatures_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule("two", /* path= */ "'a'", /* withFeatures= */ "None", /* requirements= */ "[]");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("with_features parameter of tool should be a list, found NoneType");
  }

  @Test
  public void testTool_executionRequirements_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule(
        "three", /* path= */ "'a'", /* withFeatures= */ "[]", /* requirements= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("execution_requirements parameter of tool should be a list, found NoneType");
  }

  @Test
  public void testTool_withFeatures_mustBeWithFeatureSet() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule(
        "four",
        /* path= */ "'a'",
        /* withFeatures= */ "[struct(val = 'a')]",
        /* requirements= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'with_feature_set', received 'struct'");
  }

  @Test
  public void testTool_requirements_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule(
        "five",
        /* path= */ "'a'",
        /* withFeatures= */ "[]",
        /* requirements= */ "[struct(val = 'a')]");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of execution_requirements, got element of type struct, want string");
  }

  @Test
  public void testTool() throws Exception {
    loadCcToolchainConfigLib();
    createToolRule(
        "six",
        /* path= */ "'/a/b/c'",
        /* withFeatures= */ "[with_feature_set(features = ['a'])]",
        /* requirements= */ "['a', 'b']");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    Tool tool = CcModule.toolFromStarlark(toolStruct);
    assertThat(tool.getExecutionRequirements()).containsExactly("a", "b");
    assertThat(tool.getToolPathString(PathFragment.EMPTY_FRAGMENT)).isEqualTo("/a/b/c");
    assertThat(tool.getWithFeatureSetSets())
        .contains(new WithFeatureSet(ImmutableSet.of("a"), ImmutableSet.of()));
  }

  private void createToolRule(String pkg, String path, String withFeatures, String requirements)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'tool')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(tool = tool(",
        "       path = " + path + ",",
        "       with_features = " + withFeatures + ",",
        "       execution_requirements = " + requirements + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomTool_path_nonEmpty() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "one", /* path= */ "''", /* withFeatures= */ "[]", /* requirements= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e).hasMessageThat().contains("The 'path' field of tool must be a nonempty string.");
  }

  @Test
  public void testCustomTool_path_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "two", /* path= */ "struct()", /* withFeatures= */ "[]", /* requirements= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e).hasMessageThat().contains("Field 'path' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomTool_withFeatures_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "three", /* path= */ "'a'", /* withFeatures= */ "struct()", /* requirements= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e).hasMessageThat().contains("for with_features, got struct, want sequence");
  }

  @Test
  public void testCustomTool_withFeatures_mustBeWithFeatureSet() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "four",
        /* path= */ "'a'",
        /* withFeatures= */ "[struct(val = 'a')]",
        /* requirements= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'with_feature_set', received 'struct'");
  }

  @Test
  public void testCustomTool_executionRequirements_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "five", /* path= */ "'a'", /* withFeatures= */ "[]", /* requirements= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("for execution_requirements, got string, want sequence");
  }

  @Test
  public void testCustomTool_executionRequirements_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomToolRule(
        "six", /* path= */ "'a'", /* withFeatures= */ "[]", /* requirements= */ "[struct()]");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo toolStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("tool");
    assertThat(toolStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.toolFromStarlark(toolStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of execution_requirements, got element of type struct, want string");
  }

  private void createCustomToolRule(
      String pkg, String path, String withFeatures, String requirements) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(tool = struct(",
        "       path = " + path + ",",
        "       with_features = " + withFeatures + ",",
        "       execution_requirements = " + requirements + ",",
        "       type_name = 'tool'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testFlagSet_withFeatures_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "two", /* actions= */ "['a']", /* flagGroups= */ "[]", /* withFeatures= */ "None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("with_features parameter of flag_set should be a list, found NoneType");
  }

  @Test
  public void testFlagSet_flagGroups_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "three", /* actions= */ "['a']", /* flagGroups= */ "None", /* withFeatures= */ "[]");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//three:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("flag_groups parameter of flag_set should be a list, found NoneType");
  }

  @Test
  public void testFlagSet_actions_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "four",
        /* actions= */ "['a', struct(val = 'a')]",
        /* flagGroups= */ "[]",
        /* withFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ null));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 1 of actions, got element of type struct, want string");
  }

  @Test
  public void testFlagSet_flagGroups_mustBeFlagGroup() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "five",
        /* actions= */ "['a']",
        /* flagGroups= */ "[flag_group(flags = ['a']), struct(value = 'a')]",
        /* withFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ null));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'flag_group', received 'struct'");
  }

  @Test
  public void testFlagSet_withFeatures_mustBeWithFeatureSet() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "six",
        /* actions= */ "['a']",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* withFeatures= */ "[struct(val = 'a')]");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ null));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'with_feature_set', received 'struct'");
  }

  @Test
  public void testFlagSet() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "seven",
        /* actions= */ "['a']",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* withFeatures= */ "[with_feature_set(features = ['a'])]");
    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    FlagSet f = CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ null);
    assertThat(f).isNotNull();
  }

  @Test
  public void testFlagSet_actionConfig_notActionList() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "eight",
        /* actions= */ "['a']",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* withFeatures= */ "[struct(val = 'a')]");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ "action"));
    assertThat(e)
        .hasMessageThat()
        .contains("Thus, you must not specify action lists in an action_config's flag set.");
  }

  @Test
  public void testFlagSet_emptyAction() throws Exception {
    loadCcToolchainConfigLib();
    createFlagSetRule(
        "nine",
        /* actions= */ "[]",
        /* flagGroups= */ "[flag_group(flags = ['a'])]",
        /* withFeatures= */ "[with_feature_set(features = ['a'])]");
    ConfiguredTarget t = getConfiguredTarget("//nine:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    FlagSet f = CcModule.flagSetFromStarlark(flagSetStruct, /* actionName= */ "action");
    assertThat(f).isNotNull();
    assertThat(f.getActions()).containsExactly("action");
  }

  private void createFlagSetRule(String pkg, String actions, String flagGroups, String withFeatures)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group',",
        "   'flag_set')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(flagset = flag_set(",
        "       flag_groups = " + flagGroups + ",",
        "       actions = " + actions + ",",
        "       with_features = " + withFeatures + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomFlagSet() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagSetRule(
        "one", /* actions= */ "[]", /* flagGroups= */ "[]", /* withFeatures= */ "[]");

    ConfiguredTarget target = getConfiguredTarget("//one:a");
    StarlarkInfo flagSet = (StarlarkInfo) getMyInfoFromTarget(target).getValue("flagset");
    assertThat(flagSet).isNotNull();
    FlagSet flagSetObject = CcModule.flagSetFromStarlark(flagSet, /* actionName */ null);
    assertThat(flagSetObject).isNotNull();
  }

  @Test
  public void testCustomFlagSet_flagGroups_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagSetRule(
        "two", /* actions= */ "['a']", /* flagGroups= */ "struct()", /* withFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName */ null));
    assertThat(e).hasMessageThat().contains("for flag_groups, got struct, want sequence");
  }

  @Test
  public void testCustomFlagSet_withFeatures_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagSetRule(
        "three", /* actions= */ "['a']", /* flagGroups= */ "[]", /* withFeatures= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName */ null));
    assertThat(e).hasMessageThat().contains("for with_features, got struct, want sequence");
  }

  @Test
  public void testCustomFlagSet_actions_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFlagSetRule(
        "four", /* actions= */ "struct()", /* flagGroups= */ "[]", /* withFeatures= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo flagSetStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("flagset");
    assertThat(flagSetStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () -> CcModule.flagSetFromStarlark(flagSetStruct, /* actionName */ null));
    assertThat(e).hasMessageThat().contains("for actions, got struct, want sequence");
  }

  private void createCustomFlagSetRule(
      String pkg, String actions, String flagGroups, String withFeatures) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
        "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group',",
        "   'flag_set')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(flagset = struct(",
        "       flag_groups = " + flagGroups + ",",
        "       actions = " + actions + ",",
        "       with_features = " + withFeatures + ",",
        "       type_name = 'flag_set'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testActionConfig_actionName_mustBeNonEmpty() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "one",
        /* actionName= */ "''",
        /* enabled= */ "True",
        /* tools= */ "[]",
        /* flagSets= */ "[]",
        /* implies= */ "[]");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//one:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("name parameter of action_config must be a nonempty string");
  }

  @Test
  public void testActionConfig_enabled_mustBeBool() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "two",
        /* actionName= */ "'actionname'",
        /* enabled= */ "['asd']",
        /* tools= */ "[]",
        /* flagSets= */ "[]",
        /* implies= */ "[]");
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("enabled parameter of action_config should be a bool, found list");
  }

  @Test
  public void testActionConfig_tools_mustBeTool() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "three",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[with_feature_set(features = ['a'])]",
        /* flagSets= */ "[]",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'tool', received 'with_feature_set'");
  }

  @Test
  public void testActionConfig_flagSets_mustBeFlagSet() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "four",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[tool(path = 'a/b/c')]",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e).hasMessageThat().contains("Expected object of type 'flag_set', received 'tool'");
  }

  @Test
  public void testActionConfig_implies_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "five",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "flag_set(actions = ['a', 'b'])");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//five:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("implies parameter of action_config should be a list, found struct");
  }

  @Test
  public void testActionConfig_implies_mustContainString() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "six",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "[flag_set(actions = ['a', 'b'])]");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of implies, got element of type struct, want string");
  }

  @Test
  public void testActionConfig_implies_mustContainString_notStruct() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "seven",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "[flag_set(actions = ['a', 'b'])]");

    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of implies, got element of type struct, want string");
  }

  @Test
  public void testActionConfig() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "eight",
        /* actionName= */ "'actionname32._++-'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[flag_set(flag_groups=[flag_group(flags=['a'])])]",
        /* implies= */ "['a', 'b']");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    ActionConfig a = CcModule.actionConfigFromStarlark(actionConfigStruct);
    assertThat(a).isNotNull();
    assertThat(a.getActionName()).isEqualTo("actionname32._++-");
    assertThat(a.getImplies()).containsExactly("a", "b").inOrder();
    assertThat(Iterables.getOnlyElement(a.getFlagSets()).getActions())
        .containsExactly("actionname32._++-");
  }

  @Test
  public void testActionConfig_actionName_validChars_notUpper() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "nine",
        /* actionName= */ "'Upper'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "[flag_set(actions = ['a', 'b'])]");

    ConfiguredTarget t = getConfiguredTarget("//nine:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "An action_config's name must consist solely "
                + "of lowercase ASCII letters, digits, '.', '_', '+', and '-', got 'Upper'");
  }

  @Test
  public void testActionConfig_actionName_validChars_notWhitespace() throws Exception {
    loadCcToolchainConfigLib();
    createActionConfigRule(
        "ten",
        /* actionName= */ "'white\tspace'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "[flag_set(actions = ['a', 'b'])]");

    ConfiguredTarget t = getConfiguredTarget("//ten:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "An action_config's name must consist solely "
                + "of lowercase ASCII letters, digits, '.', '_', '+', and '-', "
                + "got 'white\tspace'");
  }

  private void createActionConfigRule(
      String pkg, String actionName, String enabled, String tools, String flagSets, String implies)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set',",
        "             'tool', 'flag_set', 'action_config', 'flag_group')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(config = action_config(",
        "       action_name = " + actionName + ",",
        "       enabled = " + enabled + ",",
        "       tools = " + tools + ",",
        "       flag_sets = " + flagSets + ",",
        "       implies = " + implies + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomActionConfig_actionName_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomActionConfigRule(
        "one",
        /* actionName= */ "struct()",
        /* enabled= */ "True",
        /* tools= */ "[]",
        /* flagSets= */ "[]",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'action_name' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomActionConfig_enabled_mustBeBool() throws Exception {
    loadCcToolchainConfigLib();
    createCustomActionConfigRule(
        "two",
        /* actionName= */ "'actionname'",
        /* enabled= */ "['asd']",
        /* tools= */ "[]",
        /* flagSets= */ "[]",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e).hasMessageThat().contains("Field 'enabled' is not of 'java.lang.Boolean' type.");
  }

  @Test
  public void testCustomActionConfig_tools_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomActionConfigRule(
        "three",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "struct()",
        /* flagSets= */ "[]",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e).hasMessageThat().contains("for tools, got struct, want sequence");
  }

  @Test
  public void testCustomActionConfig_flagSets_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomActionConfigRule(
        "four",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "True",
        /* implies= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e).hasMessageThat().contains("for flag_sets, got bool, want sequence");
  }

  @Test
  public void testCustomActionConfig_implies_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomActionConfigRule(
        "five",
        /* actionName= */ "'actionname'",
        /* enabled= */ "True",
        /* tools= */ "[tool(path = 'a/b/c')]",
        /* flagSets= */ "[]",
        /* implies= */ "flag_set(actions = ['a', 'b'])");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo actionConfigStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("config");
    assertThat(actionConfigStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class, () -> CcModule.actionConfigFromStarlark(actionConfigStruct));
    assertThat(e).hasMessageThat().contains("for implies, got struct, want sequence");
  }

  private void createCustomActionConfigRule(
      String pkg, String actionName, String enabled, String tools, String flagSets, String implies)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set',",
        "             'tool', 'flag_set', 'action_config', )",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(config = struct(",
        "       action_name = " + actionName + ",",
        "       enabled = " + enabled + ",",
        "       tools = " + tools + ",",
        "       flag_sets = " + flagSets + ",",
        "       implies = " + implies + ",",
        "       type_name = 'action_config'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testFeature_name_mustBeNonempty() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "one",
        /* name= */ "''",
        /* enabled= */ "False",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    String msg = e.getMessage();
    assertThat(msg).contains("A feature must either have a nonempty 'name' field or be enabled.");
    assertThat(msg)
        .contains(
            "in FeatureInfo instantiated at /workspace/tools/cpp/cc_toolchain_config_lib.bzl:");
  }

  @Test
  public void testFeature_enabled_mustBeBool() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "two",
        /* name= */ "'featurename'",
        /* enabled= */ "None",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//two:a"));
    assertThat(e)
        .hasMessageThat()
        .contains("enabled parameter of feature should be a bool, found NoneType");
  }

  @Test
  public void testFeature_flagSets_mustBeFlagSet() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "three",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[struct()]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected object of type 'flag_set', received 'struct'");
  }

  @Test
  public void testFeature_envSets_mustBeEnvSet() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "four",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",
        /* envSets= */ "[tool(path = 'a/b/c')]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("Expected object of type 'env_set', received 'tool'");
  }

  @Test
  public void testFeature_something_mustBeFeatureSet() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "five",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",
        /* envSets= */ "[env_set(actions = ['a1'], "
            + "env_entries = [env_entry(key = 'a', value = 'b')])]",
        /* requires= */ "[tool(path = 'a/b/c')]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("expected object of type 'feature_set'");
  }

  @Test
  public void testFeature_implies_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "six",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",
        /* envSets= */ "[env_set(actions = ['a1'], "
            + "env_entries = [env_entry(key = 'a', value = 'b')])]",
        /* requires= */ "[feature_set(features = ['f1', 'f2'])]",
        /* implies= */ "[tool(path = 'a/b/c')]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of implies, got element of type struct, want string");
  }

  @Test
  public void testFeature_provides_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "seven",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",
        /* envSets= */ "[env_set(actions = ['a1'], "
            + "env_entries = [env_entry(key = 'a', value = 'b')])]",
        /* requires= */ "[feature_set(features = ['f1', 'f2'])]",
        /* implies= */ "['a', 'b', 'c']",
        /* provides= */ "[struct()]");

    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("at index 0 of provides, got element of type struct, want string");
  }

  @Test
  public void testFeature() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "eight",
        /* name= */ "'featurename32+.-_'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",
        /* envSets= */ "[env_set(actions = ['a1'], "
            + "env_entries = [env_entry(key = 'a', value = 'b')])]",
        /* requires= */ "[feature_set(features = ['f1', 'f2'])]",
        /* implies= */ "['a', 'b', 'c']",
        /* provides= */ "['a', 'b', 'c']");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    Feature a = CcModule.featureFromStarlark(featureStruct);
    assertThat(a).isNotNull();
  }

  @Test
  public void testFeature_name_validCharacters_notUpper() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "nine",
        /* name= */ "'UpperCase'",
        /* enabled= */ "False",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//nine:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "A feature's name must consist solely of lowercase ASCII letters, digits, "
                + "'.', '_', '+', and '-', got 'UpperCase'");
  }

  @Test
  public void testFeature_name_validCharacters_notWhitespace() throws Exception {
    loadCcToolchainConfigLib();
    createFeatureRule(
        "ten",
        /* name= */ "'white space'",
        /* enabled= */ "False",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//ten:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "A feature's name must consist solely of "
                + "lowercase ASCII letters, digits, '.', '_', '+', and '-', got 'white space");
  }

  private void createFeatureRule(
      String pkg,
      String name,
      String enabled,
      String flagSets,
      String envSets,
      String requires,
      String implies,
      String provides)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'feature_set',",
        "             'flag_set', 'flag_group', 'tool', 'env_set', 'env_entry', 'feature')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(f = feature(",
        "       name = " + name + ",",
        "       enabled = " + enabled + ",",
        "       flag_sets = " + flagSets + ",",
        "       env_sets = " + envSets + ",",
        "       requires = " + requires + ",",
        "       implies = " + implies + ",",
        "       provides = " + provides + "))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomFeature_name_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "one",
        /* name= */ "struct()",
        /* enabled= */ "False",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomFeature_enabled_mustBeBool() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "two",
        /* name= */ "'featurename'",
        /* enabled= */ "struct()",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("Field 'enabled' is not of 'java.lang.Boolean' type.");
  }

  @Test
  public void testCustomFeature_flagSets_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "three",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "struct()",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("for flag_sets, got struct, want sequence");
  }

  @Test
  public void testCustomFeature_envSets_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "four",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[]",
        /* envSets= */ "struct()",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("for env_sets, got struct, want sequence");
  }

  @Test
  public void testCustomFeature_requires_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "five",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "struct()",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("for requires, got struct, want sequence");
  }

  @Test
  public void testCustomFeature_implies_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "six",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "struct()",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("for implies, got struct, want sequence");
  }

  @Test
  public void testCustomFeature_provides_mustBeList() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "seven",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e).hasMessageThat().contains("for provides, got struct, want sequence");
  }

  @Test
  public void testCustomFeature_flagSet_musthaveActions() throws Exception {
    loadCcToolchainConfigLib();
    createCustomFeatureRule(
        "eight",
        /* name= */ "'featurename'",
        /* enabled= */ "True",
        /* flagSets= */ "[flag_set()]",
        /* envSets= */ "[]",
        /* requires= */ "[]",
        /* implies= */ "[]",
        /* provides= */ "[]");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo featureStruct = (StarlarkInfo) getMyInfoFromTarget(t).getValue("f");
    assertThat(featureStruct).isNotNull();
    EvalException e =
        assertThrows(EvalException.class, () -> CcModule.featureFromStarlark(featureStruct));
    assertThat(e)
        .hasMessageThat()
        .contains("A flag_set that belongs to a feature must have nonempty 'actions' parameter.");
  }

  private void createCustomFeatureRule(
      String pkg,
      String name,
      String enabled,
      String flagSets,
      String envSets,
      String requires,
      String implies,
      String provides)
      throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'feature_set',",
        "             'flag_set', 'flag_group', 'tool', 'env_set', 'env_entry', 'feature')",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(f = struct(",
        "       name = " + name + ",",
        "       enabled = " + enabled + ",",
        "       flag_sets = " + flagSets + ",",
        "       env_sets = " + envSets + ",",
        "       requires = " + requires + ",",
        "       implies = " + implies + ",",
        "       provides = " + provides + ",",
        "       type_name = 'feature'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCustomArtifactNamePattern_categoryName_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "one", /* categoryName= */ "struct()", /* extension= */ "'a'", /* prefix= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//one:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e)
        .hasMessageThat()
        .contains("Field 'category_name' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomArtifactNamePattern_extension_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "two",
        /* categoryName= */ "'static_library'",
        /* extension= */ "struct()",
        /* prefix= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//two:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e).hasMessageThat().contains("Field 'extension' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomArtifactNamePattern_prefix_mustBeString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "three",
        /* categoryName= */ "'static_library'",
        /* extension= */ "'.a'",
        /* prefix= */ "struct()");

    ConfiguredTarget t = getConfiguredTarget("//three:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e).hasMessageThat().contains("Field 'prefix' is not of 'java.lang.String' type.");
  }

  @Test
  public void testCustomArtifactNamePattern_categoryName_mustBeNonempty() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "four", /* categoryName= */ "''", /* extension= */ "'.a'", /* prefix= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//four:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e)
        .hasMessageThat()
        .contains("The 'category_name' field of artifact_name_pattern must be a nonempty string.");
  }

  @Test
  public void testCustomArtifactNamePattern_emptyString_emptyString() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "five", /* categoryName= */ "'executable'", /* extension= */ "''", /* prefix= */ "''");

    ConfiguredTarget t = getConfiguredTarget("//five:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    AtomicBoolean called = new AtomicBoolean(false);
    CcModule.artifactNamePatternFromStarlark(
        artifactNamePatternStruct, (c, p, ext) -> called.set(true));
    assertThat(called.get()).isTrue();
  }

  @Test
  public void testCustomArtifactNamePattern_none_none() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "six", /* categoryName= */ "'executable'", /* extension= */ "None", /* prefix= */ "None");

    ConfiguredTarget t = getConfiguredTarget("//six:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    AtomicBoolean called = new AtomicBoolean(false);
    CcModule.artifactNamePatternFromStarlark(
        artifactNamePatternStruct, (c, p, ext) -> called.set(true));
    assertThat(called.get()).isTrue();
  }

  @Test
  public void testCustomArtifactNamePattern_categoryName_unknown() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "seven", /* categoryName= */ "'unknown'", /* extension= */ "'.a'", /* prefix= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//seven:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e).hasMessageThat().contains("Artifact category unknown not recognized");
  }

  @Test
  public void testCustomArtifactNamePattern_fileExtension_unknown() throws Exception {
    loadCcToolchainConfigLib();
    createCustomArtifactNamePatternRule(
        "eight",
        /* categoryName= */ "'static_library'",
        /* extension= */ "'a'",
        /* prefix= */ "'a'");

    ConfiguredTarget t = getConfiguredTarget("//eight:a");
    StarlarkInfo artifactNamePatternStruct =
        (StarlarkInfo) getMyInfoFromTarget(t).getValue("namepattern");
    assertThat(artifactNamePatternStruct).isNotNull();
    EvalException e =
        assertThrows(
            EvalException.class,
            () ->
                CcModule.artifactNamePatternFromStarlark(
                    artifactNamePatternStruct, (c, p, ext) -> {}));
    assertThat(e).hasMessageThat().contains("Unrecognized file extension 'a'");
  }

  private void createCustomArtifactNamePatternRule(
      String pkg, String categoryName, String extension, String prefix) throws Exception {
    scratch.file(
        pkg + "/foo.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "   return [MyInfo(namepattern = struct(",
        "       category_name = " + categoryName + ",",
        "       extension = " + extension + ",",
        "       prefix = " + prefix + ",",
        "       type_name = 'artifact_name_pattern'))]",
        "crule = rule(implementation = _impl)");
    scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')");
  }

  @Test
  public void testCcToolchainInfoFromStarlark() throws Exception {
    loadCcToolchainConfigLib();
    scratch.file(
        "foo/crosstool.bzl",
        """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [feature(name = "featureone")],
                action_configs = [action_config(action_name = "action", enabled = True)],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                cxx_builtin_include_directories = ["dir1", "dir2", "dir3"],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
                tool_paths = [tool_path(name = "name1", path = "path1")],
                cc_target_os = "os",
                builtin_sysroot = "sysroot",
                make_variables = [make_variable(name = "acs", value = "asd")],
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    assertThat(ccToolchainConfigInfo).isNotNull();
  }

  @Test
  public void testCcToolchainInfoFromStarlarkRequiredToolchainIdentifier() throws Exception {
    setupStarlarkRuleForStringFieldsTesting("toolchain_identifier");
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:r"));
    assertThat(e)
        .hasMessageThat()
        .contains("missing 1 required keyword-only argument: toolchain_identifier");
  }

  @Test
  public void testCcToolchainInfoFromStarlarkRequiredCompiler() throws Exception {
    setupStarlarkRuleForStringFieldsTesting("compiler");
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:r"));
    assertThat(e).hasMessageThat().contains("missing 1 required keyword-only argument: compiler");
  }

  @Test
  public void testCcToolchainInfoFromStarlarkAllRequiredStringsPresent() throws Exception {
    setupStarlarkRuleForStringFieldsTesting("");
    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    assertThat(ccToolchainConfigInfo).isNotNull();
  }

  private void setupStarlarkRuleForStringFieldsTesting(String fieldToExclude) throws Exception {
    ImmutableList<String> fields =
        ImmutableList.of(
            "toolchain_identifier = 'identifier'",
            "host_system_name = 'host_system_name'",
            "target_system_name = 'target_system_name'",
            "target_cpu = 'target_cpu'",
            "target_libc = 'target_libc'",
            "compiler = 'compiler'",
            "abi_version = 'abi'",
            "abi_libc_version = 'abi_libc'");

    scratch.file(
        "foo/crosstool.bzl",
        "def _impl(ctx):",
        "    return cc_common.create_cc_toolchain_config_info(",
        "    ctx = ctx,",
        Joiner.on(",\n")
            .join(fields.stream().filter(el -> !el.startsWith(fieldToExclude + " =")).toArray()),
        ")",
        "cc_toolchain_config_rule = rule(",
        "    implementation = _impl,",
        "    attrs = {},",
        "    provides = [CcToolchainConfigInfo], ",
        ")");
    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);
  }

  @Test
  public void testCcToolchainInfoFromStarlarkNoLegacyFeatures() throws Exception {
    loadCcToolchainConfigLib();
    scratch.file(
        "foo/crosstool.bzl",
        """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "no_legacy_features"),
                    feature(name = "custom_feature"),
                ],
                action_configs = [action_config(action_name = "custom_action")],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    ImmutableSet<String> featureNames =
        ccToolchainConfigInfo.getFeatures().stream()
            .map(Feature::getName)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<String> actionConfigNames =
        ccToolchainConfigInfo.getActionConfigs().stream()
            .map(ActionConfig::getActionName)
            .collect(ImmutableSet.toImmutableSet());
    assertThat(featureNames).containsExactly("no_legacy_features", "custom_feature");
    assertThat(actionConfigNames).containsExactly("custom_action");
  }

  @Test
  public void testCcToolchainInfoFromStarlarkWithLegacyFeatures() throws Exception {
    loadCcToolchainConfigLib();
    scratch.file(
        "foo/crosstool.bzl",
        """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "custom_feature"),
                    feature(name = "legacy_compile_flags"),
                    feature(name = "fdo_optimize"),
                    feature(name = "default_compile_flags"),
                ],
                action_configs = [action_config(action_name = "custom-action")],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    ImmutableList<String> featureNames =
        ccToolchainConfigInfo.getFeatures().stream()
            .map(Feature::getName)
            .collect(ImmutableList.toImmutableList());
    ImmutableSet<String> actionConfigNames =
        ccToolchainConfigInfo.getActionConfigs().stream()
            .map(ActionConfig::getActionName)
            .collect(ImmutableSet.toImmutableSet());
    // fdo_optimize should not be re-added to the list of features by legacy behavior
    assertThat(featureNames).containsNoDuplicates();
    // legacy_compile_flags should appear first in the list of features, followed by
    // default_compile_flags.
    assertThat(featureNames)
        .containsAtLeast(
            "legacy_compile_flags", "default_compile_flags", "custom_feature", "fdo_optimize")
        .inOrder();
    // assemble is one of the action_configs added as a legacy behavior, therefore it needs to be
    // prepended to the action configs defined by the user.
    assertThat(actionConfigNames).containsAtLeast("assemble", "custom-action").inOrder();
  }

  @Test
  public void testCcToolchainInfoToProto() throws Exception {
    loadCcToolchainConfigLib();
    scratch.file(
        "foo/crosstool.bzl",
        """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "featureone", enabled = True),
                    feature(
                        name = "sysroot",
                        flag_sets = [
                            flag_set(
                                actions = ["assemble", "preprocess-assemble"],
                                flag_groups = [
                                    flag_group(
                                        expand_if_available = "available",
                                        expand_if_not_available = "notavailable",
                                        expand_if_true = "true",
                                        expand_if_false = "false",
                                        expand_if_equal = variable_with_value(
                                            name = "variable",
                                            value = "value",
                                        ),
                                        iterate_over = "iterate_over",
                                        flag_groups = [
                                            flag_group(flags =
                                                           [
                                                               "foo%{libraries_to_link}bar",
                                                               '-D__DATE__="redacted"',
                                                           ]),
                                        ],
                                    ),
                                    flag_group(flags = ["a", "b"]),
                                ],
                            ),
                        ],
                        implies = ["imply2", "imply1"],
                        provides = ["provides2", "provides1"],
                        requires = [
                            feature_set(features = ["r1", "r2"]),
                            feature_set(features = ["r3"]),
                        ],
                        env_sets = [
                            env_set(
                                actions = ["a1", "a2"],
                                env_entries = [
                                    env_entry(key = "k1", value = "v1"),
                                    env_entry(key = "k2", value = "v2"),
                                ],
                                with_features = [
                                    with_feature_set(
                                        features = ["f1", "f2"],
                                        not_features = ["nf1", "nf2"],
                                    ),
                                ],
                            ),
                            env_set(actions = ["a1", "a2"]),
                        ],
                    ),
                ],
                action_configs = [
                    action_config(action_name = "action_one", enabled = True),
                    action_config(
                        action_name = "action_two",
                        tools = [tool(
                            path = "fake/path",
                            with_features = [
                                with_feature_set(
                                    features = ["a"],
                                    not_features = ["b", "c"],
                                ),
                                with_feature_set(features = ["b"]),
                            ],
                            execution_requirements = ["a", "b", "c"],
                        )],
                        implies = ["compiler_input_flags", "compiler_output_flags"],
                        flag_sets = [flag_set(
                            flag_groups = [flag_group(flags = ["a"])],
                        )],
                    ),
                ],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                cxx_builtin_include_directories = ["dir1", "dir2", "dir3"],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
                tool_paths = [tool_path(name = "name1", path = "path1")],
                make_variables = [make_variable(name = "variable", value = "--a -b -c")],
                builtin_sysroot = "sysroot",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);

    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    assertThat(ccToolchainConfigInfo).isNotNull();
    CToolchain.Builder toolchainBuilder = CToolchain.newBuilder();
    TextFormat.merge(ccToolchainConfigInfo.getProto(), toolchainBuilder);
    CToolchain toolchain = toolchainBuilder.build();

    assertThat(toolchain.getCxxBuiltinIncludeDirectoryList())
        .containsExactly("dir1", "dir2", "dir3");
    assertThat(toolchain.getToolchainIdentifier()).isEqualTo("toolchain");
    assertThat(toolchain.getHostSystemName()).isEqualTo("host");
    assertThat(toolchain.getTargetSystemName()).isEqualTo("target");
    assertThat(toolchain.getTargetCpu()).isEqualTo("cpu");
    assertThat(toolchain.getTargetLibc()).isEqualTo("libc");
    assertThat(toolchain.getCompiler()).isEqualTo("compiler");
    assertThat(toolchain.getAbiLibcVersion()).isEqualTo("abi_libc");
    assertThat(toolchain.getAbiVersion()).isEqualTo("abi");
    ToolPath toolPath = Iterables.getOnlyElement(toolchain.getToolPathList());
    assertThat(toolPath.getName()).isEqualTo("name1");
    assertThat(toolPath.getPath()).isEqualTo("path1");
    MakeVariable makeVariable = Iterables.getOnlyElement(toolchain.getMakeVariableList());
    assertThat(makeVariable.getName()).isEqualTo("variable");
    assertThat(makeVariable.getValue()).isEqualTo("--a -b -c");
    assertThat(toolchain.getBuiltinSysroot()).isEqualTo("sysroot");
    assertThat(
            toolchain.getFeatureList().stream()
                .map(CToolchain.Feature::getName)
                .collect(ImmutableList.toImmutableList()))
        .containsAtLeast("featureone", "sysroot")
        .inOrder();
    // check for legacy features
    assertThat(toolchain.getFeatureCount()).isGreaterThan(2);
    CToolchain.Feature feature =
        toolchain.getFeatureList().stream()
            .filter(f -> f.getName().equals("sysroot"))
            .findFirst()
            .get();
    assertThat(feature.getName()).isEqualTo("sysroot");
    assertThat(feature.getImpliesList()).containsExactly("imply2", "imply1").inOrder();
    assertThat(feature.getProvidesList()).containsExactly("provides2", "provides1").inOrder();
    assertThat(feature.getRequires(0).getFeatureList()).containsExactly("r1", "r2");
    assertThat(feature.getRequires(1).getFeatureList()).containsExactly("r3");
    assertThat(feature.getEnvSetCount()).isEqualTo(2);
    CToolchain.EnvSet envSet = feature.getEnvSet(0);
    assertThat(envSet.getActionList()).containsExactly("a1", "a2");
    assertThat(envSet.getEnvEntry(0).getKey()).isEqualTo("k1");
    assertThat(envSet.getEnvEntry(0).getValue()).isEqualTo("v1");
    assertThat(Iterables.getOnlyElement(envSet.getWithFeatureList()).getFeatureList())
        .containsExactly("f1", "f2");
    assertThat(Iterables.getOnlyElement(envSet.getWithFeatureList()).getNotFeatureList())
        .containsExactly("nf1", "nf2");
    CToolchain.FlagSet flagSet = Iterables.getOnlyElement(feature.getFlagSetList());
    assertThat(flagSet.getActionList()).containsExactly("assemble", "preprocess-assemble");
    assertThat(flagSet.getFlagGroupCount()).isEqualTo(2);
    CToolchain.FlagGroup flagGroup = flagSet.getFlagGroup(0);
    assertThat(flagGroup.getExpandIfAllAvailableList()).containsExactly("available");
    assertThat(flagGroup.getExpandIfNoneAvailableList()).containsExactly("notavailable");
    assertThat(flagGroup.getIterateOver()).isEqualTo("iterate_over");
    assertThat(flagGroup.getExpandIfTrue()).isEqualTo("true");
    assertThat(flagGroup.getExpandIfFalse()).isEqualTo("false");
    CToolchain.VariableWithValue variableWithValue = flagGroup.getExpandIfEqual();
    assertThat(variableWithValue.getVariable()).isEqualTo("variable");
    assertThat(variableWithValue.getValue()).isEqualTo("value");
    assertThat(flagGroup.getFlagGroup(0).getFlagList())
        .containsExactly("foo%{libraries_to_link}bar", "-D__DATE__=\"redacted\"")
        .inOrder();

    assertThat(
            toolchain.getActionConfigList().stream()
                .map(CToolchain.ActionConfig::getActionName)
                .collect(ImmutableList.toImmutableList()))
        .containsAtLeast("action_one", "action_two")
        .inOrder();

    // legacy crosstool patching adds the missing action_configs as well
    assertThat(toolchain.getActionConfigCount()).isGreaterThan(2);

    CToolchain.ActionConfig actionConfig =
        toolchain.getActionConfigList().stream()
            .filter(config -> config.getConfigName().equals("action_two"))
            .findFirst()
            .get();
    assertThat(actionConfig.getImpliesList())
        .containsExactly("compiler_input_flags", "compiler_output_flags")
        .inOrder();
    CToolchain.Tool tool = Iterables.getOnlyElement(actionConfig.getToolList());
    assertThat(tool.getToolPath()).isEqualTo("fake/path");
    assertThat(tool.getExecutionRequirementList()).containsExactly("a", "b", "c");
    assertThat(tool.getWithFeature(0).getFeatureList()).containsExactly("a");
    assertThat(tool.getWithFeature(0).getNotFeatureList()).containsExactly("b", "c");
    // When we create an ActionConfig from a proto we put the action name as the only element of its
    // FlagSet.action lists. So when we convert back to proto, we need to remove it.
    assertThat(
            ccToolchainConfigInfo.getActionConfigs().stream()
                .filter(config -> config.getName().equals("action_two"))
                .findFirst()
                .get()
                .getFlagSets()
                .get(0)
                .getActions())
        .containsExactly("action_two");
    CToolchain.FlagSet actionConfigFlagSet =
        Iterables.getOnlyElement(actionConfig.getFlagSetList());
    assertThat(actionConfigFlagSet.getActionCount()).isEqualTo(0);

    // Missing artifact name patterns are filled in too
    assertThat(toolchain.getArtifactNamePatternCount()).isGreaterThan(1);
    assertThat(toolchain.getArtifactNamePattern(0))
        .isEqualTo(
            CToolchain.ArtifactNamePattern.newBuilder()
                .setCategoryName("static_library")
                .setPrefix("prefix")
                .setExtension(".a")
                .build());
  }

  // Tests that default None values in non-required fields do not cause trouble while building
  // the CToolchain
  @Test
  public void testCcToolchainInfoToProtoBareMinimum() throws Exception {
    loadCcToolchainConfigLib();
    scratch.file(
        "foo/crosstool.bzl",
        """
        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);

    ConfiguredTarget target = getConfiguredTarget("//foo:r");
    assertThat(target).isNotNull();
    CcToolchainConfigInfo ccToolchainConfigInfo =
        (CcToolchainConfigInfo) target.get(CcToolchainConfigInfo.PROVIDER.getKey());
    assertThat(ccToolchainConfigInfo).isNotNull();
    CToolchain.Builder toolchainBuilder = CToolchain.newBuilder();
    TextFormat.merge(ccToolchainConfigInfo.getProto(), toolchainBuilder);
    assertNoEvents();
  }

  @Test
  public void testWrongElementTypeInListParameter_features() throws Exception {
    getBasicCcToolchainConfigInfoWithAdditionalParameter(
        "features = ['string_instead_of_feature']");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:r");
    assertContainsEvent(
        "'features' parameter of cc_common.create_cc_toolchain_config_info() contains an element"
            + " of type 'string' instead of a 'FeatureInfo' provider.");
  }

  @Test
  public void testWrongElementTypeInListParameter_actionConfigs() throws Exception {
    getBasicCcToolchainConfigInfoWithAdditionalParameter("action_configs = [None]");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:r");
    assertContainsEvent(
        "'action_configs' parameter of cc_common.create_cc_toolchain_config_info() contains an"
            + " element of type 'NoneType' instead of a 'ActionConfigInfo' provider.");
  }

  @Test
  public void testWrongElementTypeInListParameter_artifactNamePatterns() throws Exception {
    getBasicCcToolchainConfigInfoWithAdditionalParameter("artifact_name_patterns = [1]");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:r");
    assertContainsEvent(
        "'artifact_name_patterns' parameter of cc_common.create_cc_toolchain_config_info()"
            + " contains an element of type 'int' instead of a 'ArtifactNamePatternInfo'"
            + " provider.");
  }

  @Test
  public void testWrongElementTypeInListParameter_makeVariables() throws Exception {
    getBasicCcToolchainConfigInfoWithAdditionalParameter("make_variables = [True]");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:r");
    assertContainsEvent(
        "'make_variables' parameter of cc_common.create_cc_toolchain_config_info() contains an"
            + " element of type 'bool' instead of a 'MakeVariableInfo' provider.");
  }

  @Test
  public void testWrongElementTypeInListParameter_toolPaths() throws Exception {
    getBasicCcToolchainConfigInfoWithAdditionalParameter("tool_paths = [{}]");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:r");
    assertContainsEvent(
        "'tool_paths' parameter of cc_common.create_cc_toolchain_config_info() contains an element"
            + " of type 'dict' instead of a 'ToolPathInfo' provider.");
  }

  private void getBasicCcToolchainConfigInfoWithAdditionalParameter(String s) throws Exception {
    scratch.file(
        "foo/crosstool.bzl",
        "def _impl(ctx):",
        "    return cc_common.create_cc_toolchain_config_info(",
        "                ctx = ctx,",
        "                toolchain_identifier = 'toolchain',",
        "                host_system_name = 'host',",
        "                target_system_name = 'target',",
        "                target_cpu = 'cpu',",
        "                target_libc = 'libc',",
        "                compiler = 'compiler',",
        "                abi_libc_version = 'abi_libc',",
        "                abi_version = 'abi',",
        "                " + s + ",",
        "        )",
        "cc_toolchain_config_rule = rule(",
        "    implementation = _impl,",
        "    attrs = {},",
        "    provides = [CcToolchainConfigInfo], ",
        ")");

    scratch.file(
        "foo/BUILD",
        """
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        """);
  }

  @Test
  public void testGetLegacyCcFlagsMakeVariable() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withMakeVariables(Pair.of("CC_FLAGS", "-test-cflag1 -testcflag2")));

    loadCcToolchainConfigLib();
    scratch.file(
        "a/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            cc_flags = cc_common.legacy_cc_flags_make_variable_do_not_use(
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                cc_flags = cc_flags,
            )]

        cc_flags = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "cc_flags")

        cc_toolchain_alias(name = "alias")

        cc_flags(name = "r")
        """);

    String ccFlags =
        (String) getMyInfoFromTarget(getConfiguredTarget("//a:r")).getValue("cc_flags");

    assertThat(ccFlags).isEqualTo("-test-cflag1 -testcflag2");
  }

  @Test
  public void testWrongExtensionThrowsError() throws Exception {
    setUpCcLinkingContextTest();
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.ifso",
            dynamic_library_symlink_path = "a.lib",
            interface_library = "a.so",
            interface_library_symlink_path = "a.dll",
            pic_static_library = "a.pic.o",
            static_library = "a.o",
        )
        """);
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:bin"));
    assertThat(e)
        .hasMessageThat()
        .contains("'a.o' does not have any of the allowed extensions .a, .lib, .pic.a, or .rlib");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "'a.pic.o' does not have any of the allowed extensions .a, .lib, .pic.a, or .rlib");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "'a.ifso' does not have any of the allowed extensions .so, .dylib, .dll, .pyd, .wasm,"
                + " .tgt, or .vpi");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "'a.lib' does not have any of the allowed extensions .so, .dylib, .dll, .pyd, .wasm,"
                + " .tgt, or .vpi");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "'a.dll' does not have any of the allowed extensions .ifso, .tbd, .lib, or .dll.a");
  }

  @Test
  public void testCcOutputsMerging() throws Exception {
    setupCcOutputsTest();
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            object1 = "object1.o",
            object2 = "object2.o",
            pic_object1 = "pic_object1.o",
            pic_object2 = "pic_object2.o",
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    CcCompilationOutputs compilationOutputs =
        (CcCompilationOutputs) getMyInfoFromTarget(target).getValue("compilation_outputs");
    assertThat(
            AnalysisTestUtil.artifactsToStrings(
                targetConfig, compilationOutputs.getObjectFiles(/* usePic= */ true)))
        .containsExactly("src foo/pic_object1.o", "src foo/pic_object2.o");
    assertThat(
            AnalysisTestUtil.artifactsToStrings(
                targetConfig, compilationOutputs.getObjectFiles(/* usePic= */ false)))
        .containsExactly("src foo/object1.o", "src foo/object2.o");
  }

  @Test
  public void testObjectsWrongExtension() throws Exception {
    doTestCcOutputsWrongExtension("object1", "objects");
  }

  @Test
  public void testPicObjectsWrongExtension() throws Exception {
    doTestCcOutputsWrongExtension("pic_object1", "pic_objects");
  }

  @Test
  public void testObjectsRightExtension() throws Exception {
    doTestCcOutputsRightExtension("object1");
  }

  @Test
  public void testPicObjectsRightExtension() throws Exception {
    doTestCcOutputsRightExtension("pic_object1");
  }

  @Test
  public void testCreateOnlyPic() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", String.join("", "disallow_nopic_outputs=True"));
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(getFilenamesToBuild(target)).doesNotContain("starlark_lib.o");
    assertThat(getFilenamesToBuild(target)).contains("starlark_lib.pic.o");
  }

  @Test
  public void testCreateOnlyNoPic() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", String.join("", "disallow_pic_outputs=True"));
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(getFilenamesToBuild(target)).contains("starlark_lib.o");
    assertThat(getFilenamesToBuild(target)).doesNotContain("starlark_lib.pic.o");
  }

  @Test
  public void testCreatePicAndNoPic() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC));
    createFilesForTestingCompilation(scratch, "tools/build_defs/foo", "");
    useConfiguration("--compilation_mode=opt");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(getFilenamesToBuild(target)).contains("starlark_lib.pic.o");
    assertThat(getFilenamesToBuild(target)).contains("starlark_lib.o");
  }

  @Test
  public void testDoNotCreateEitherPicOrNoPic() throws Exception {
    createFilesForTestingCompilation(
        scratch,
        "tools/build_defs/foo",
        String.join("", "disallow_nopic_outputs=True, disallow_pic_outputs=True"));
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:bin");
    assertContainsEvent("Either PIC or no PIC actions have to be created.");
  }

  @Test
  public void testCreateStaticLibraries() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER, CppRuleClasses.SUPPORTS_PIC));
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", /* linkProviderLines= */ "");
    assertThat(getConfiguredTarget("//foo:starlark_lib")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(
            getFilesToBuild(target).toList().stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .contains("libstarlark_lib.a");
  }

  @Test
  public void testDoNotCreateStaticLibraries() throws Exception {
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", "disallow_static_libraries=True");
    assertThat(getConfiguredTarget("//foo:starlark_lib")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(
            getFilesToBuild(target).toList().stream()
                .map(Artifact::getFilename)
                .collect(ImmutableList.toImmutableList()))
        .doesNotContain("libstarlark_lib.a");
  }

  private static ImmutableList<String> getFilenamesToBuild(ConfiguredTarget target) {
    return getFilesToBuild(target).toList().stream()
        .map(Artifact::getFilename)
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void testCcNativeRuleDependingOnStarlarkDefinedRule() throws Exception {
    createFiles(scratch, "tools/build_defs/cc");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
  }

  @Test
  public void testUserCompileFlagsInRulesApi() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "user_compile_flags=['-COMPILATION_OPTION']");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).contains("-COMPILATION_OPTION");
  }

  @Test
  public void testIncludeDirs() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "includes=['foo/bar', 'baz/qux']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).containsAtLeast("-Ifoo/bar", "-Ibaz/qux");
  }

  @Test
  public void testSystemIncludeDirs() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "system_includes=['foo/bar', 'baz/qux']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments())
        .containsAtLeast("-isystem", "foo/bar", "-isystem", "baz/qux")
        .inOrder();
  }

  @Test
  public void testQuoteIncludeDirs() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "quote_includes=['foo/bar', 'baz/qux']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments())
        .containsAtLeast("-iquote", "foo/bar", "-iquote", "baz/qux")
        .inOrder();
  }

  @Test
  public void testFrameworkIncludeDirs() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "framework_includes=['foo/bar', 'baz/qux']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).containsAtLeast("-Ffoo/bar", "-Fbaz/qux").inOrder();
  }

  @Test
  public void testDefines() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "defines=['DEFINE1', 'DEFINE2']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).containsAtLeast("-DDEFINE1", "-DDEFINE2");
  }

  @Test
  public void testLocalDefines() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "local_defines=['DEFINE1', 'DEFINE2']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).containsAtLeast("-DDEFINE1", "-DDEFINE2");
  }

  @Test
  public void testIncludePrefix() throws Exception {
    createFilesForTestingCompilation(
        scratch, "third_party/tools/build_defs/foo", "include_prefix='prefix'");
    scratch.file(
        "third_party/bar/BUILD",
        """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["private_starlark_lib.h"],
            public_hdrs = ["starlark_lib.h"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//third_party/bar:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);
    assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
        .contains(
            "bin third_party/bar/_virtual_includes/starlark_lib_suffix/prefix/starlark_lib.h");
  }

  @Test
  public void testStripIncludePrefix() throws Exception {
    createFilesForTestingCompilation(
        scratch, "third_party/tools/build_defs/foo", "strip_include_prefix='v1'");
    scratch.file(
        "third_party/bar/BUILD",
        """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//third_party/bar:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);
    assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
        .contains("bin third_party/bar/_virtual_includes/starlark_lib_suffix/starlark_lib.h");
  }

  @Test
  public void testStripIncludePrefixIncludePath() throws Exception {
    createFilesForTestingCompilation(
        scratch, "third_party/tools/build_defs/foo", "strip_include_prefix='v1'");
    scratch.file(
        "third_party/bar/BUILD",
        """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//third_party/bar:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);

    assertThat(ccInfo.getCcCompilationContext().getIncludeDirs())
        .containsExactly(
            getTargetConfiguration()
                .getBinFragment(RepositoryName.MAIN)
                .getRelative("third_party/bar/_virtual_includes/starlark_lib_suffix"));
  }

  @Test
  public void testStripIncludePrefixAndIncludePrefix() throws Exception {
    createFilesForTestingCompilation(
        scratch,
        "third_party/tools/build_defs/foo",
        "strip_include_prefix='v1', include_prefix='prefix'");
    scratch.file(
        "third_party/bar/BUILD",
        """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//third_party/bar:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);
    assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
        .contains(
            "bin third_party/bar/_virtual_includes/starlark_lib_suffix/prefix/starlark_lib.h");
  }

  @Test
  public void testStripIncludePrefixAndIncludePrefixIncludePath() throws Exception {
    createFilesForTestingCompilation(
        scratch,
        "third_party/tools/build_defs/foo",
        "strip_include_prefix='v1', include_prefix='prefix'");
    scratch.file(
        "third_party/bar/BUILD",
        "load('//third_party/tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
        "cc_starlark_library(",
        "    name = 'starlark_lib',",
        "    srcs = ['starlark_lib.cc'],",
        "    public_hdrs = ['v1/starlark_lib.h'],",
        "    private_hdrs = ['v1/private_starlark_lib.h'],",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//third_party/bar:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);
    assertThat(ccInfo.getCcCompilationContext().getIncludeDirs())
        .containsExactly(
            getTargetConfiguration()
                .getBinFragment(RepositoryName.MAIN)
                .getRelative("third_party/bar/_virtual_includes/starlark_lib_suffix"));
  }

  @Test
  public void testHeaders() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", /* compileProviderLines= */ "");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CcInfo ccInfo = target.get(CcInfo.PROVIDER);
    assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDeclaredIncludeSrcs()))
        .containsAtLeast(
            "src foo/dep2.h", "src foo/starlark_lib.h", "src foo/private_starlark_lib.h");
    assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getTextualHdrs()))
        .containsExactly("src foo/textual_hdr.h");
  }

  @Test
  public void testCompileOutputHasSuffix() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", /* compileProviderLines= */ "");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    assertThat(artifactsToStrings(getFilesToBuild(target)))
        .contains("bin foo/_objs/starlark_lib_suffix/starlark_lib.o");
  }

  @Test
  public void testCompilationContexts() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", /* compileProviderLines= */ "");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(action.getArguments()).containsAtLeast("-DDEFINE_DEP1", "-DDEFINE_DEP2");
  }

  @Test
  public void testLinkingOutputs() throws Exception {
    createFiles(scratch, "tools/build_defs/foo");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    @SuppressWarnings("unchecked")
    Sequence<LibraryToLink> libraries =
        (Sequence<LibraryToLink>) getMyInfoFromTarget(target).getValue("libraries");
    assertThat(
            libraries.stream()
                .map(x -> x.getResolvedSymlinkDynamicLibrary().getFilename())
                .collect(ImmutableList.toImmutableList()))
        .contains("libstarlark_lib.so");
  }

  @Test
  public void testUserLinkFlags() throws Exception {
    createFilesForTestingLinking(
        scratch, "tools/build_defs/foo", "user_link_flags=['-LINKING_OPTION']");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    assertThat(target.get(CcInfo.PROVIDER).getCcLinkingContext().getFlattenedUserLinkFlags())
        .contains("-LINKING_OPTION");
  }

  @Test
  public void testLinkingContexts() throws Exception {
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", /* linkProviderLines= */ "");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    SpawnAction action =
        (SpawnAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), "bin"));
    assertThat(action.getArguments()).containsAtLeast("-DEP1_LINKOPT", "-DEP2_LINKOPT");
  }

  @Test
  public void testAlwayslinkTrue() throws Exception {
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", "alwayslink=True");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    assertThat(
            target.get(CcInfo.PROVIDER).getCcLinkingContext().getLibraries().toList().stream()
                .filter(LibraryToLink::getAlwayslink)
                .collect(ImmutableList.toImmutableList()))
        .hasSize(1);
  }

  @Test
  public void testAlwayslinkFalse() throws Exception {
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", "alwayslink=False");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    assertThat(
            target.get(CcInfo.PROVIDER).getCcLinkingContext().getLibraries().toList().stream()
                .filter(LibraryToLink::getAlwayslink)
                .collect(ImmutableList.toImmutableList()))
        .isEmpty();
  }

  @Test
  public void testAdditionalLinkingInputs() throws Exception {
    createFilesForTestingLinking(
        scratch, "tools/build_defs/foo", "additional_inputs=ctx.files._additional_inputs");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    assertThat(target.get(CcInfo.PROVIDER).getCcLinkingContext().getNonCodeInputs().toList())
        .hasSize(1);
  }

  @Test
  public void testAdditionalCompilationInputs() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "additional_inputs=ctx.files._additional_compiler_inputs");
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    assertThat(target).isNotNull();
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));
    assertThat(artifactsToStrings(action.getMandatoryInputs()))
        .contains("src foo/extra_compiler_input");
  }

  @Test
  public void testPossibleSrcsExtensions() throws Exception {
    doTestPossibleExtensionsOfSrcsAndHdrs(
        "srcs", CppFileTypes.ALL_C_CLASS_SOURCE.including(CppFileTypes.ASSEMBLER).getExtensions());
  }

  @Test
  public void testPossiblePrivateHdrExtensions() throws Exception {
    doTestPossibleExtensionsOfSrcsAndHdrs("private_hdrs", CppFileTypes.CPP_HEADER.getExtensions());
  }

  @Test
  public void testPossiblePublicHdrExtensions() throws Exception {
    doTestPossibleExtensionsOfSrcsAndHdrs("public_hdrs", CppFileTypes.CPP_HEADER.getExtensions());
  }

  @Test
  public void testTreeArtifactSrcs() throws Exception {
    doTestTreeAtrifactInSrcsAndHdrs("srcs");
  }

  @Test
  public void testTreeArtifactPrivateHdrs() throws Exception {
    doTestTreeAtrifactInSrcsAndHdrs("private_hdrs");
  }

  @Test
  public void testTreeArtifactPublicHdrs() throws Exception {
    doTestTreeAtrifactInSrcsAndHdrs("public_hdrs");
  }

  private static void createFilesForTestingCompilation(
      Scratch scratch, String bzlFilePath, String compileProviderLines) throws Exception {
    createFiles(scratch, bzlFilePath, compileProviderLines, "");
  }

  private static void createFilesForTestingLinking(
      Scratch scratch, String bzlFilePath, String linkProviderLines) throws Exception {
    createFiles(scratch, bzlFilePath, "", linkProviderLines);
  }

  private static void createFiles(Scratch scratch, String bzlFilePath) throws Exception {
    createFiles(scratch, bzlFilePath, "", "");
  }

  private static void createFiles(
      Scratch scratch, String bzlFilePath, String compileProviderLines, String linkProviderLines)
      throws Exception {
    String fragments = "    fragments = ['google_cpp', 'cpp'],";
    if (AnalysisMock.get().isThisBazel()) {
      fragments = "    fragments = ['cpp'],";
    }
    scratch.overwriteFile(bzlFilePath + "/BUILD");
    scratch.file(
        bzlFilePath + "/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _cc_aspect_impl(target, ctx):",
        "    if ctx.attr._cc_toolchain:",
        "      toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "    else:",
        "      toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "    feature_configuration = cc_common.configure_features(",
        "        ctx = ctx,",
        "        cc_toolchain = toolchain,",
        "        requested_features = ctx.features,",
        "        unsupported_features = ctx.disabled_features,",
        "    )",
        "    (compilation_context, compilation_outputs) = cc_common.compile(",
        "        actions = ctx.actions,",
        "        feature_configuration = feature_configuration,",
        "        cc_toolchain = toolchain,",
        "        name = ctx.label.name + '_aspect',",
        "        srcs = ctx.rule.files.srcs,",
        "        public_hdrs = ctx.rule.files.hdrs,",
        "    )",
        "    (linking_context, linking_outputs) = (",
        "        cc_common.create_linking_context_from_compilation_outputs(",
        "            actions = ctx.actions,",
        "            feature_configuration = feature_configuration,",
        "            name = ctx.label.name + '_aspect',",
        "            cc_toolchain = toolchain,",
        "            compilation_outputs = compilation_outputs,",
        "        )",
        "    )",
        "    return []",
        "_cc_aspect = aspect(",
        "    implementation = _cc_aspect_impl,",
        "    attrs = {",
        "        '_cc_toolchain': attr.label(default ="
            + " '@bazel_tools//tools/cpp:current_cc_toolchain'),",
        "    },",
        fragments,
        "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
        ")",
        "def _cc_starlark_library_impl(ctx):",
        "    dep_compilation_contexts = []",
        "    dep_linking_contexts = []",
        "    for dep in ctx.attr._deps:",
        "        dep_compilation_contexts.append(dep[CcInfo].compilation_context)",
        "        dep_linking_contexts.append(dep[CcInfo].linking_context)",
        "    if ctx.attr._my_cc_toolchain:",
        "      toolchain = ctx.attr._my_cc_toolchain[cc_common.CcToolchainInfo]",
        "    else:",
        "      toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "    feature_configuration = cc_common.configure_features(",
        "        ctx = ctx,",
        "        cc_toolchain=toolchain,",
        "        requested_features = ctx.features,",
        "        unsupported_features = ctx.disabled_features)",
        "    foo_dict = {'string_variable': 'foo',",
        "            'string_sequence_variable' : ['foo'],",
        "            'string_depset_variable': depset(['foo'])}",
        "    (compilation_context, compilation_outputs) = cc_common.compile(",
        "        actions=ctx.actions,",
        "        feature_configuration=feature_configuration,",
        "        cc_toolchain=toolchain,",
        "        srcs=ctx.files.srcs,",
        "        name=ctx.label.name + '_suffix',",
        "        compilation_contexts = dep_compilation_contexts,",
        "        public_hdrs=ctx.files.public_hdrs,",
        "        textual_hdrs=ctx.files.textual_hdrs,",
        "        private_hdrs=ctx.files.private_hdrs" + (compileProviderLines.isEmpty() ? "" : ","),
        "        " + compileProviderLines,
        "    )",
        "    (linking_context,",
        "     linking_outputs) = cc_common.create_linking_context_from_compilation_outputs(",
        "        actions=ctx.actions,",
        "        feature_configuration=feature_configuration,",
        "        compilation_outputs=compilation_outputs,",
        "        name = ctx.label.name,",
        "        linking_contexts = dep_linking_contexts,",
        "        cc_toolchain=toolchain" + (linkProviderLines.isEmpty() ? "" : ","),
        "        " + linkProviderLines,
        "    )",
        "    files_to_build = []",
        "    files_to_build.extend(compilation_outputs.pic_objects)",
        "    files_to_build.extend(compilation_outputs.objects)",
        "    library_to_link = None",
        "    if len(ctx.files.srcs) > 0:",
        "        library_to_link = linking_outputs.library_to_link",
        "        if library_to_link.pic_static_library != None:",
        "            files_to_build.append(library_to_link.pic_static_library)",
        "        if library_to_link.static_library != None:",
        "            files_to_build.append(library_to_link.static_library)",
        "        if library_to_link.dynamic_library != None:",
        "            files_to_build.append(library_to_link.dynamic_library)",
        "    return [MyInfo(libraries=[library_to_link]),",
        "            DefaultInfo(files=depset(files_to_build)),",
        "            CcInfo(compilation_context=compilation_context,",
        "                   linking_context=linking_context)]",
        "cc_starlark_library = rule(",
        "    implementation = _cc_starlark_library_impl,",
        "    attrs = {",
        "      'srcs': attr.label_list(allow_files=True),",
        "      'public_hdrs': attr.label_list(allow_files=True),",
        "      'textual_hdrs': attr.label_list(allow_files=True),",
        "      'private_hdrs': attr.label_list(allow_files=True),",
        "      '_additional_inputs': attr.label_list(allow_files=True,"
            + " default=['//foo:script.lds']),",
        "      '_additional_compiler_inputs': attr.label_list(allow_files=True,"
            + " default=['//foo:extra_compiler_input']),",
        "      '_deps': attr.label_list(default=['//foo:dep1', '//foo:dep2']),",
        "      'aspect_deps': attr.label_list(aspects=[_cc_aspect]),",
        "      '_my_cc_toolchain': attr.label(default =",
        "          configuration_field(fragment = 'cpp', name = 'cc_toolchain'))",
        "    },",
        fragments,
        "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
        ")");
    scratch.file(
        "foo/BUILD",
        "load('//" + bzlFilePath + ":extension.bzl', 'cc_starlark_library')",
        "cc_library(",
        "    name = 'dep1',",
        "    srcs = ['dep1.cc'],",
        "    hdrs = ['dep1.h'],",
        "    defines = ['DEFINE_DEP1'],",
        "    linkopts = ['-DEP1_LINKOPT'],",
        ")",
        "cc_library(",
        "    name = 'dep2',",
        "    srcs = ['dep2.cc'],",
        "    hdrs = ['dep2.h'],",
        "    defines = ['DEFINE_DEP2'],",
        "    linkopts = ['-DEP2_LINKOPT'],",
        ")",
        "cc_starlark_library(",
        "    name = 'starlark_lib',",
        "    srcs = ['starlark_lib.cc'],",
        "    public_hdrs = ['starlark_lib.h'],",
        "    textual_hdrs = ['textual_hdr.h'],",
        "    private_hdrs = ['private_starlark_lib.h'],",
        ")",
        "cc_binary(",
        "    name = 'bin',",
        "    deps = ['starlark_lib'],",
        ")");
  }

  @Test
  public void testTransitiveLinkWithDeps() throws Exception {
    setupTestTransitiveLink(scratch, "linking_contexts = dep_linking_contexts");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    Artifact executable = (Artifact) getMyInfoFromTarget(target).getValue("executable");
    assertThat(artifactsToStrings(getGeneratingAction(executable).getInputs()))
        .containsAtLeast("bin foo/libdep1.a", "bin foo/libdep2.a");
  }

  @Test
  public void testTransitiveLinkForDynamicLibrary() throws Exception {
    setupTestTransitiveLink(scratch, "output_type = 'dynamic_library'");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    LibraryToLink library = (LibraryToLink) getMyInfoFromTarget(target).getValue("library");
    assertThat(library).isNotNull();
    Object executable = getMyInfoFromTarget(target).getValue("executable");
    assertThat(Starlark.isNullOrNone(executable)).isTrue();
  }

  @Test
  public void testCustomNameOutputArtifact() throws Exception {
    createCcBinRule(
        scratch,
        /* internalApi= */ true,
        "output_type = 'dynamic_library'",
        " main_output=ctx.actions.declare_file('custom_name.so')");
    scratch.file(
        "foo/BUILD",
        """
        load("//bazel_internal/test_rules/cc:extension.bzl", "cc_bin")

        cc_bin(
            name = "bin",
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();

    LibraryToLink library = (LibraryToLink) getMyInfoFromTarget(target).getValue("library");
    Artifact dynamicLibrary = library.getResolvedSymlinkDynamicLibrary();
    if (dynamicLibrary == null) {
      dynamicLibrary = library.getDynamicLibrary();
    }

    assertThat(dynamicLibrary).isNotNull();
    assertThat(dynamicLibrary.getFilename()).isEqualTo("custom_name.so");
  }

  @Test
  public void testCustomNameOutputArtifactRaisesError() throws Exception {
    setupTestTransitiveLink(scratch, "output_type = 'dynamic_library'", " main_output=None");

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:bin"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testInterfaceLibraryProducedForTransitiveLinkOnWindows() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.TARGETS_WINDOWS,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY));
    setupTestTransitiveLink(scratch, "output_type = 'dynamic_library'");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    LibraryToLink library = (LibraryToLink) getMyInfoFromTarget(target).getValue("library");
    assertThat(library).isNotNull();
    assertThat(library.getDynamicLibrary()).isNotNull();
    assertThat(library.getInterfaceLibrary()).isNotNull();
  }

  @Test
  public void testEmitInterfaceLibrary() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                    CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                    CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY));
    setupTestTransitiveLinkInternal(
        scratch,
        /* internalApi= */ true,
        "output_type = 'dynamic_library'",
        "emit_interface_shared_library = True");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    LibraryToLink library = (LibraryToLink) getMyInfoFromTarget(target).getValue("library");
    assertThat(library).isNotNull();
    assertThat(library.getDynamicLibrary()).isNotNull();
    assertThat(library.getInterfaceLibrary()).isNotNull();
  }

  @Test
  public void testTransitiveLinkForExecutable() throws Exception {
    setupTestTransitiveLink(scratch, "output_type = 'executable'");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    Artifact executable = (Artifact) getMyInfoFromTarget(target).getValue("executable");
    assertThat(executable).isNotNull();
    Object library = getMyInfoFromTarget(target).getValue("library");
    assertThat(Starlark.isNullOrNone(library)).isTrue();
  }

  @Test
  public void testTransitiveLinkWithCompilationOutputs() throws Exception {
    setupTestTransitiveLink(scratch, "compilation_outputs=objects");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    Artifact executable = (Artifact) getMyInfoFromTarget(target).getValue("executable");
    assertThat(artifactsToStrings(getGeneratingAction(executable).getInputs()))
        .contains("src foo/file.o");
  }

  @Test
  public void testLinkStampExpliciltyEnabledOverridesNoStampFlag() throws Exception {
    useConfiguration(
        "--nostamp",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    setupTestTransitiveLink(scratch, "stamp=1", "linking_contexts=dep_linking_contexts");
    assertStampEnabled(getLinkstampCompileAction("//foo:bin"));
  }

  @Test
  public void testLinkExplicitlyDisabledOverridesStampFlag() throws Exception {
    useConfiguration(
        "--nostamp",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    setupTestTransitiveLink(scratch, "stamp=0", "linking_contexts=dep_linking_contexts");
    assertStampDisabled(getLinkstampCompileAction("//foo:bin"));
  }

  @Test
  public void testLinkStampUseFlagStamp() throws Exception {
    useConfiguration(
        "--stamp",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    setupTestTransitiveLink(scratch, "stamp=-1", "linking_contexts=dep_linking_contexts");
    assertStampEnabled(getLinkstampCompileAction("//foo:bin"));
  }

  @Test
  public void testLinkStampUseFlagNoStamp() throws Exception {
    useConfiguration(
        "--nostamp",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    setupTestTransitiveLink(scratch, "stamp=-1", "linking_contexts=dep_linking_contexts");
    assertStampDisabled(getLinkstampCompileAction("//foo:bin"));
  }

  @Test
  public void testLinkStampDisabledByDefaultDespiteStampFlag() throws Exception {
    useConfiguration(
        "--stamp",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--experimental_platform_in_output_dir",
        String.format(
            "--experimental_override_name_platform_in_output_dir=%s=k8",
            TestConstants.PLATFORM_LABEL));
    setupTestTransitiveLink(scratch, "linking_contexts=dep_linking_contexts");
    assertStampDisabled(getLinkstampCompileAction("//foo:bin"));
  }

  @Test
  public void testLinkStampInvalid() throws Exception {
    setupTestTransitiveLink(scratch, "stamp=2");
    checkError(
        "//foo:bin",
        "stamp value 2 is not supported, must be 0 (disabled), 1 (enabled), or -1 (default)");
  }

  private CppCompileAction getLinkstampCompileAction(String label)
      throws LabelSyntaxException, EvalException {
    ConfiguredTarget target = getConfiguredTarget(label);
    Artifact executable = (Artifact) getMyInfoFromTarget(target).getValue("executable");
    SpawnAction generatingAction = (SpawnAction) getGeneratingAction(executable);
    Artifact compiledLinkstamp =
        ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "version.o");
    CppCompileAction linkstampCompileAction =
        (CppCompileAction) getGeneratingAction(compiledLinkstamp);
    assertThat(linkstampCompileAction.getMnemonic()).isEqualTo("CppLinkstampCompile");
    return linkstampCompileAction;
  }

  public String getBuildInfoFile(String commonPath) {
    if (AnalysisMock.get().isThisBazel()) {
      return getRelativeOutputPath() + "/k8-fastbuild/bin/external/bazel_tools/" + commonPath;
    } else {
      return getRelativeOutputPath() + "/k8-fastbuild/bin/" + commonPath;
    }
  }

  private void assertStampEnabled(CppCompileAction linkstampAction)
      throws CommandLineExpansionException {
    assertThat(linkstampAction.getArguments())
        .contains(getBuildInfoFile(NON_REDACTED_ARTIFACT_PATH));
  }

  private void assertStampDisabled(CppCompileAction linkstampAction)
      throws CommandLineExpansionException {
    assertThat(linkstampAction.getArguments()).contains(getBuildInfoFile(REDACTED_ARTIFACT_PATH));
  }

  @Test
  public void testApiWithAspectsOnTargetsInExternalRepos() throws Exception {
    if (!AnalysisMock.get().isThisBazel()) {
      return;
    }
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", /* compileProviderLines= */ "");
    scratch.appendFile(
        "MODULE.bazel",
        "bazel_dep(name = 'r')",
        "local_path_override(module_name = 'r', path = '/r')");
    scratch.file("/r/MODULE.bazel", "module(name = 'r')");
    scratch.file(
        "/r/p/BUILD",
        """
        cc_library(
            name = "a",
            srcs = ["a.cc"],
        )
        """);
    invalidatePackages();
    scratch.file(
        "b/BUILD",
        """
        load("//tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "b",
            srcs = ["b.cc"],
            aspect_deps = ["@r//p:a"],
        )
        """);
    assertThat(getConfiguredTarget("//b:b")).isNotNull();
  }

  @Test
  public void testObjectsApi() throws Exception {
    useConfiguration("--compilation_mode=opt");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC));

    scratchObjectsProvidingRule();

    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo");
    LibraryToLink fooLibrary =
        Iterables.getOnlyElement(
            getConfiguredTarget("//foo:dep")
                .get(CcInfo.PROVIDER)
                .getCcLinkingContext()
                .getLibraries()
                .toList());
    StarlarkInfo fooInfo =
        (StarlarkInfo) getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key));

    assertThat(fooLibrary.getObjectFiles()).isEqualTo(fooInfo.getValue("objects"));
    assertThat(fooLibrary.getPicObjectFiles()).isEqualTo(fooInfo.getValue("pic_objects"));
  }

  @Test
  public void testObjectsApiNeverReturningNones() throws Exception {
    scratchObjectsProvidingRule();

    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo");

    // Default toolchain is without PIC support, so pic_objects should be None
    StarlarkInfo fooInfoForPic =
        (StarlarkInfo) getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key));

    Object picObjects = fooInfoForPic.getValue("pic_objects");
    assertThat(picObjects).isNotEqualTo(Starlark.NONE);
    assertThat((List) picObjects).isEmpty();

    // With PIC and the default compilation_mode which is fastbuild C++ rules only produce PIC
    // objects.
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC));
    invalidatePackages();
    StarlarkInfo fooInfoForNoPic =
        (StarlarkInfo) getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key));

    Object objects = fooInfoForNoPic.getValue("objects");
    assertThat(objects).isNotEqualTo(Starlark.NONE);
    assertThat((List) objects).isEmpty();
  }

  @Test
  public void testLtoBitcodeFilesApi() throws Exception {
    useConfiguration("--compilation_mode=opt", "--features=thin_lto");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.THIN_LTO, CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC));

    scratchObjectsProvidingRule();

    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo");
    LibraryToLink fooLibrary =
        Iterables.getOnlyElement(
            getConfiguredTarget("//foo:dep")
                .get(CcInfo.PROVIDER)
                .getCcLinkingContext()
                .getLibraries()
                .toList());
    StarlarkInfo fooInfo =
        (StarlarkInfo) getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key));

    assertThat(ImmutableList.copyOf(fooLibrary.getLtoCompilationContext().getBitcodeFiles()))
        .isEqualTo(fooInfo.getValue("lto_bitcode_files"));
    assertThat(fooLibrary.getLtoCompilationContext().getBitcodeFiles()).isNotEmpty();

    assertThat(ImmutableList.copyOf(fooLibrary.getPicLtoCompilationContext().getBitcodeFiles()))
        .isEqualTo(fooInfo.getValue("pic_lto_bitcode_files"));
    assertThat(fooLibrary.getPicLtoCompilationContext().getBitcodeFiles()).isNotEmpty();
  }

  @Test
  public void testLtoBitcodeFilesApiNeverReturningNones() throws Exception {
    // We do not add --features=thin_lto for this test.
    useConfiguration("--compilation_mode=opt");
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                // We do not enable the THIN_LTO feature for this test.
                .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC));

    scratchObjectsProvidingRule();

    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo");
    StarlarkInfo fooInfo =
        (StarlarkInfo) getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key));

    Object picLtoBitcodeFiles = fooInfo.getValue("pic_lto_bitcode_files");
    assertThat(picLtoBitcodeFiles).isNotEqualTo(Starlark.NONE);
    assertThat((List) picLtoBitcodeFiles).isEmpty();

    Object ltoBitcodeFiles = fooInfo.getValue("lto_bitcode_files");
    assertThat(ltoBitcodeFiles).isNotEqualTo(Starlark.NONE);
    assertThat((List) ltoBitcodeFiles).isEmpty();
  }

  private void scratchObjectsProvidingRule() throws IOException {
    scratch.file(
        "foo/BUILD",
        """
        load(":foo.bzl", "foo")

        foo(
            name = "foo",
            dep = ":dep",
        )

        cc_library(
            name = "dep",
            srcs = ["dep.cc"],
        )
        """);
    scratch.file(
        "foo/foo.bzl",
        """
        FooInfo = provider(
            fields = ["objects", "pic_objects", "lto_bitcode_files", "pic_lto_bitcode_files"],
        )

        def _foo_impl(ctx):
            lib = ctx.attr.dep[CcInfo].linking_context.linker_inputs.to_list()[0].libraries[0]
            return [FooInfo(
                objects = lib.objects,
                pic_objects = lib.pic_objects,
                lto_bitcode_files = lib.lto_bitcode_files,
                pic_lto_bitcode_files = lib.pic_lto_bitcode_files,
            )]

        foo = rule(
            implementation = _foo_impl,
            attrs = {
                "dep": attr.label(),
            },
        )
        """);
  }

  private void doTestPossibleExtensionsOfSrcsAndHdrs(String attrName, List<String> extensions)
      throws Exception {
    createFiles(scratch, "tools/build_defs/foo");
    reporter.removeHandler(failFastHandler);

    for (String extension : extensions) {
      scratch.deleteFile("bar/BUILD");
      scratch.file(
          "bar/BUILD",
          "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
          "cc_starlark_library(",
          "    name = 'starlark_lib',",
          "    " + attrName + " = ['file" + extension + "'],",
          ")");
      getConfiguredTarget("//bar:starlark_lib");
      assertNoEvents();
    }
  }

  private void doTestTreeAtrifactInSrcsAndHdrs(String attrName) throws Exception {
    createFiles(scratch, "tools/build_defs/foo");
    reporter.removeHandler(failFastHandler);

    scratch.file(
        "bar/create_tree_artifact.bzl",
        """
        def _impl(ctx):
            tree = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(
                outputs = [tree],
                inputs = [],
                arguments = [tree.path],
                command = "mkdir $1",
            )
            return [DefaultInfo(files = depset([tree]))]

        create_tree_artifact = rule(implementation = _impl)
        """);
    scratch.file(
        "bar/BUILD",
        "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
        "load(':create_tree_artifact.bzl', 'create_tree_artifact')",
        "create_tree_artifact(name = 'tree_artifact')",
        "cc_starlark_library(",
        "    name = 'starlark_lib',",
        "    " + attrName + " = [':tree_artifact'],",
        ")");
    getConfiguredTarget("//bar:starlark_lib");
    assertNoEvents();
  }

  private void doTestCcOutputsWrongExtension(String attrName, String paramName) throws Exception {
    setupCcOutputsTest();
    scratch.file(
        "foo/BUILD",
        "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
        "cc_starlark_library(",
        "    name = 'starlark_lib',",
        "    " + attrName + " = 'object.cannotpossiblybevalid',",
        ")");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//foo:starlark_lib");
    assertContainsEvent(
        "has wrong extension. The list of possible extensions for '" + paramName + "'");
  }

  private void doTestCcOutputsRightExtension(String paramName) throws Exception {
    setupCcOutputsTest();
    reporter.removeHandler(failFastHandler);

    for (String extension : Link.OBJECT_FILETYPES.getExtensions()) {
      scratch.deleteFile("foo/BUILD");
      scratch.file(
          "foo/BUILD",
          "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
          "cc_starlark_library(",
          "    name = 'starlark_lib',",
          "    " + paramName + " = 'object1" + extension + "',",
          ")");
      getConfiguredTarget("//foo:starlark_lib");
      assertNoEvents();
    }
  }

  private void setupCcOutputsTest() throws Exception {
    scratch.overwriteFile("tools/build_defs/foo/BUILD");
    scratch.file(
        "tools/build_defs/foo/extension.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _cc_starlark_library_impl(ctx):
            objects = []
            pic_objects = []
            if ctx.file.object1 != None:
                objects.append(ctx.file.object1)
            if ctx.file.pic_object1 != None:
                pic_objects.append(ctx.file.pic_object1)
            c1 = cc_common.create_compilation_outputs(
                objects = depset(objects),
                pic_objects = depset(pic_objects),
            )
            objects = []
            pic_objects = []
            if ctx.file.object2 != None:
                objects.append(ctx.file.object2)
            if ctx.file.pic_object2 != None:
                pic_objects.append(ctx.file.pic_object2)
            c2 = cc_common.create_compilation_outputs(
                objects = depset(objects),
                pic_objects = depset(pic_objects),
            )
            compilation_outputs = cc_common.merge_compilation_outputs(
                compilation_outputs = [c1, c2],
            )
            return [MyInfo(compilation_outputs = compilation_outputs)]

        cc_starlark_library = rule(
            implementation = _cc_starlark_library_impl,
            attrs = {
                "object1": attr.label(allow_single_file = True),
                "pic_object1": attr.label(allow_single_file = True),
                "object2": attr.label(allow_single_file = True),
                "pic_object2": attr.label(allow_single_file = True),
            },
        )
        """);
  }

  private static void createCcBinRule(
      Scratch scratch, boolean internalApi, String... additionalLines) throws Exception {
    String fragments = "    fragments = ['google_cpp', 'cpp'],";
    if (AnalysisMock.get().isThisBazel()) {
      fragments = "    fragments = ['cpp'],";
    }
    scratch.overwriteFile("tools/build_defs/BUILD");

    String extensionDirectory = "tools/build_defs";
    if (internalApi) {
      extensionDirectory = "bazel_internal/test_rules/cc";
      scratch.overwriteFile(extensionDirectory + "/BUILD", "");
    }
    scratch.file(
        extensionDirectory + "/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _cc_bin_impl(ctx):",
        "    if ctx.attr._cc_toolchain:",
        "      toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "    else:",
        "      toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "    feature_configuration = cc_common.configure_features(",
        "      ctx = ctx,",
        "      cc_toolchain = toolchain,",
        "    )",
        "    dep_linking_contexts = []",
        "    for dep in ctx.attr.deps:",
        "        dep_linking_contexts.append(dep[CcInfo].linking_context)",
        "    objects = cc_common.create_compilation_outputs(objects=depset(ctx.files.objects),",
        "        pic_objects=depset(ctx.files.pic_objects))",
        "    foo_dict = {'string_variable': 'foo',",
        "            'string_sequence_variable' : ['foo'],",
        "            'string_depset_variable': depset(['foo'])}",
        "    linking_outputs = cc_common.link(",
        "        actions=ctx.actions,",
        "        feature_configuration=feature_configuration,",
        "        name = ctx.label.name,",
        "        cc_toolchain=toolchain,",
        "        " + Joiner.on(",\n        ").join(additionalLines),
        "    )",
        "    return [",
        "      MyInfo(",
        "          library=linking_outputs.library_to_link,",
        "          executable=linking_outputs.executable",
        "      ),",
        "    ]",
        "cc_bin = rule(",
        "    implementation = _cc_bin_impl,",
        "    attrs = {",
        "      'objects': attr.label_list(allow_files=True),",
        "      'pic_objects': attr.label_list(allow_files=True),",
        "      'deps': attr.label_list(),",
        "      '_cc_toolchain': attr.label(default =",
        "          configuration_field(fragment = 'cpp', name = 'cc_toolchain')),",
        "      'additional_outputs': attr.output_list(),",
        "    },",
        fragments,
        "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
        ")");
  }

  private static void setupTestTransitiveLink(Scratch scratch, String... additionalLines)
      throws Exception {
    setupTestTransitiveLinkInternal(scratch, /* internalApi= */ false, additionalLines);
  }

  private static void setupTestTransitiveLinkInternal(
      Scratch scratch, boolean internalApi, String... additionalLines) throws Exception {
    createCcBinRule(scratch, internalApi, additionalLines);
    String bzlPath;
    if (internalApi) {
      bzlPath = "bazel_internal/test_rules/cc";
    } else {
      bzlPath = "tools/build_defs";
    }
    scratch.file(
        "foo/BUILD",
        "load(\"//" + bzlPath + ":extension.bzl\", \"cc_bin\")",
        """
        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            defines = ["DEP1"],
            includes = ["dep1/baz"],
            linkstamp = "version.cc",
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            defines = ["DEP2"],
            includes = ["dep2/qux"],
        )

        cc_bin(
            name = "bin",
            objects = ["file.o"],
            pic_objects = ["file.pic.o"],
            deps = [
                ":dep1",
                ":dep2",
            ],
        )
        """);
  }

  private static void setupDirectHeaderExtractionSupport(Scratch scratch) throws Exception {
    scratch.file(
        "direct/cc_info_extractor.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _cc_info_extractor_impl(ctx):
            compilation_context = ctx.attr.dep[CcInfo].compilation_context
            return [MyInfo(
                direct_headers = compilation_context.direct_headers,
                direct_public_headers = compilation_context.direct_public_headers,
                direct_private_headers = compilation_context.direct_private_headers,
                direct_textual_headers = compilation_context.direct_textual_headers,
            )]

        cc_info_extractor = rule(
            _cc_info_extractor_impl,
            attrs = {
                "dep": attr.label(providers = [[CcInfo]]),
            },
        )
        """);
    scratch.file(
        "direct/BUILD",
        """
        load("//direct:cc_info_extractor.bzl", "cc_info_extractor")

        cc_info_extractor(
            name = "foo",
            dep = "//direct/libs:foo_lib",
        )

        cc_info_extractor(
            name = "bar",
            dep = "//direct/libs:bar_lib",
        )
        """);
  }

  private static void setupCcLibraryDirectPropagationTestTargets(Scratch scratch) throws Exception {
    scratch.file(
        "direct/libs/BUILD",
        """
        cc_library(
            name = "foo_lib",
            srcs = [
                "foo.cc",
                "foo_impl.h",
            ],
            hdrs = ["foo.h"],
            textual_hdrs = ["foo.def"],
        )

        cc_library(
            name = "bar_lib",
            hdrs = ["bar.h"],
            textual_hdrs = ["bar.def"],
            deps = [":foo_lib"],
        )
        """);
  }

  @Test
  public void testCcLibraryPropagatesCcInfoWithDirectHeaders() throws Exception {
    setupDirectHeaderExtractionSupport(scratch);
    setupCcLibraryDirectPropagationTestTargets(scratch);

    ConfiguredTarget fooTarget = getConfiguredTarget("//direct:foo");
    Iterable<Artifact> fooDirectHeaders = getArtifactsFromMyInfo(fooTarget, "direct_headers");
    assertThat(baseArtifactNames(fooDirectHeaders)).containsExactly("foo.h", "foo_impl.h");

    Iterable<Artifact> fooDirectPublicHeaders =
        getArtifactsFromMyInfo(fooTarget, "direct_public_headers");
    assertThat(baseArtifactNames(fooDirectPublicHeaders)).containsExactly("foo.h");

    Iterable<Artifact> fooDirectPrivateHeaders =
        getArtifactsFromMyInfo(fooTarget, "direct_private_headers");
    assertThat(baseArtifactNames(fooDirectPrivateHeaders)).containsExactly("foo_impl.h");

    ConfiguredTarget barTarget = getConfiguredTarget("//direct:bar");
    Iterable<Artifact> barDirectHeaders = getArtifactsFromMyInfo(barTarget, "direct_headers");
    assertThat(baseArtifactNames(barDirectHeaders)).containsExactly("bar.h");

    Iterable<Artifact> barDirectPublicHeaders =
        getArtifactsFromMyInfo(barTarget, "direct_public_headers");
    assertThat(baseArtifactNames(barDirectPublicHeaders)).containsExactly("bar.h");

    Iterable<Artifact> barDirectPrivateHeaders =
        getArtifactsFromMyInfo(barTarget, "direct_private_headers");
    assertThat(barDirectPrivateHeaders).isEmpty();
  }

  @Test
  public void testCcLibraryPropagatesCcInfoWithDirectTextualHeaders() throws Exception {
    setupDirectHeaderExtractionSupport(scratch);
    setupCcLibraryDirectPropagationTestTargets(scratch);

    ConfiguredTarget fooTarget = getConfiguredTarget("//direct:foo");
    Iterable<Artifact> fooDirectTextualHeaders =
        getArtifactsFromMyInfo(fooTarget, "direct_textual_headers");
    assertThat(baseArtifactNames(fooDirectTextualHeaders)).containsExactly("foo.def");

    ConfiguredTarget barTarget = getConfiguredTarget("//direct:bar");
    Iterable<Artifact> barDirectTextualHeaders =
        getArtifactsFromMyInfo(barTarget, "direct_textual_headers");
    assertThat(baseArtifactNames(barDirectTextualHeaders)).containsExactly("bar.def");
  }

  /** Fixes #10580 */
  @Test
  public void testMixedLinkerInputsWithOwnerAndWithout() throws Exception {
    setBuildLanguageOptions("--noincompatible_require_linker_input_cc_api");
    setUpCcLinkingContextTest();
    scratch.file(
        "foo/BUILD",
        """
        load(":rule.bzl", "crule")

        crule(name = "a")
        """);
    scratch.file(
        "foo/rule.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            linker_input = cc_common.create_linker_input(
                owner = ctx.label,
                user_link_flags = ["-l"],
            )
            linking_context = cc_common.create_linking_context(
                linker_inputs = depset([linker_input]),
            )
            linking_context = cc_common.create_linking_context(
                libraries_to_link = [],
            )
            cc_info = CcInfo(linking_context = linking_context)
            if cc_info.linking_context.linker_inputs.to_list()[0] == linker_input:
                pass
            return [cc_info]

        crule = rule(
            _impl,
            attrs = {
            },
            fragments = ["cpp"],
        )
        """);

    assertThat(getConfiguredTarget("//foo:a")).isNotNull();
    assertNoEvents();
  }

  @Test
  public void testMergeCcInfosWithDirects() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES));

    scratch.file(
        "direct/cc_merger.bzl",
        """
        def _cc_merger_impl(ctx):
            direct_cc_infos = [dep[CcInfo] for dep in ctx.attr.exports]
            cc_infos = [dep[CcInfo] for dep in ctx.attr.deps]
            return [cc_common.merge_cc_infos(
                direct_cc_infos = direct_cc_infos,
                cc_infos = cc_infos,
            )]

        cc_merger = rule(
            _cc_merger_impl,
            attrs = {
                "deps": attr.label_list(providers = [[CcInfo]]),
                "exports": attr.label_list(providers = [[CcInfo]]),
            },
        )
        """);
    scratch.file(
        "direct/BUILD",
        """
        load("//direct:cc_merger.bzl", "cc_merger")

        cc_library(
            name = "public1",
            srcs = [
                "public1.cc",
                "public1_impl.h",
            ],
            hdrs = ["public1.h"],
            textual_hdrs = ["public1.inc"],
        )

        cc_library(
            name = "public2",
            srcs = [
                "public2.cc",
                "public2_impl.h",
            ],
            hdrs = ["public2.h"],
            textual_hdrs = ["public2.inc"],
        )

        cc_library(
            name = "private",
            srcs = [
                "private.cc",
                "private_impl.h",
            ],
            hdrs = ["private.h"],
            textual_hdrs = ["private.inc"],
        )

        cc_merger(
            name = "merge",
            exports = [
                ":public1",
                ":public2",
            ],
            deps = [":private"],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//direct:merge");
    CcCompilationContext ccCompilationContext = lib.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(
            baseArtifactNames(
                ccCompilationContext.getExportingModuleMaps().stream()
                    .map(CppModuleMap::getArtifact)
                    .collect(ImmutableList.toImmutableList())))
        .containsExactly("public1.cppmap", "public2.cppmap");
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
        .containsExactly("public1.h", "public2.h");
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
        .containsExactly("public1_impl.h", "public2_impl.h");
    assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs()))
        .containsExactly("public1.inc", "public2.inc");
  }

  @Test
  public void testMergeCompilationContexts() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES));

    scratch.file(
        "direct/cc_merger.bzl",
        """
        def _cc_merger_impl(ctx):
            compilation_contexts = [dep[CcInfo].compilation_context for dep in ctx.attr.deps]
            merged_context = cc_common.merge_compilation_contexts(
                compilation_contexts = compilation_contexts,
            )
            return [CcInfo(compilation_context = merged_context)]

        cc_merger = rule(
            _cc_merger_impl,
            attrs = {
                "deps": attr.label_list(providers = [[CcInfo]]),
            },
        )
        """);
    scratch.file(
        "direct/BUILD",
        """
        load("//direct:cc_merger.bzl", "cc_merger")

        cc_library(
            name = "public1",
            srcs = [
                "public1.cc",
                "public1_impl.h",
            ],
            hdrs = ["public1.h"],
            textual_hdrs = ["public1.inc"],
        )

        cc_library(
            name = "public2",
            srcs = [
                "public2.cc",
                "public2_impl.h",
            ],
            hdrs = ["public2.h"],
            textual_hdrs = ["public2.inc"],
        )

        cc_merger(
            name = "merge",
            deps = [
                ":public1",
                ":public2",
            ],
        )
        """);

    ConfiguredTarget lib = getConfiguredTarget("//direct:merge");
    CcCompilationContext ccCompilationContext = lib.get(CcInfo.PROVIDER).getCcCompilationContext();
    assertThat(
            baseArtifactNames(
                ccCompilationContext.getExportingModuleMaps().stream()
                    .map(CppModuleMap::getArtifact)
                    .collect(ImmutableList.toImmutableList())))
        .containsExactly("public1.cppmap", "public2.cppmap");
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
        .containsExactly("public1.h", "public2.h");
    assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
        .containsExactly("public1_impl.h", "public2_impl.h");
    assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs()))
        .containsExactly("public1.inc", "public2.inc");
  }

  private void setupDebugPackageProviderTest(String fission) throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO));
    useConfiguration(fission);
    scratch.file(
        "a/rule.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.run_shell(
                inputs = [ctx.executable.cc_binary],
                tools = [],
                outputs = [out],
                command = "cp %s %s" % (ctx.executable.cc_binary.path, out.path),
            )
            wrapped_defaultinfo = ctx.attr.cc_binary[DefaultInfo]
            runfiles = ctx.runfiles(files = [out])
            wrapped_default_runfiles = wrapped_defaultinfo.default_runfiles.files.to_list()
            if ctx.executable.cc_binary in wrapped_default_runfiles:
                wrapped_default_runfiles.remove(ctx.executable.cc_binary)
            result = [
                DefaultInfo(
                    executable = out,
                    files = depset([out]),
                    runfiles = runfiles.merge(ctx.runfiles(files = wrapped_default_runfiles)),
                ),
            ]
            if ctx.file.stripped_file:
                wrapped_dbginfo = ctx.attr.cc_binary[DebugPackageInfo]
                result.append(
                    DebugPackageInfo(
                        target_label = ctx.label,
                        stripped_file =
                            ctx.file.stripped_file if wrapped_dbginfo.stripped_file else None,
                        unstripped_file = out,
                        dwp_file = ctx.file.dwp_file if wrapped_dbginfo.dwp_file else None,
                    ),
                )
            return result

        wrapped_binary = rule(
            _impl,
            attrs = {
                "cc_binary": attr.label(
                    allow_single_file = True,
                    mandatory = True,
                    executable = True,
                    cfg = "target",
                ),
                "stripped_file": attr.label(
                    allow_single_file = True,
                    default = None,
                ),
                "dwp_file": attr.label(
                    allow_single_file = True,
                    default = None,
                ),
            },
            executable = True,
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "wrapped_binary")

        wrapped_binary(
            name = "w",
            cc_binary = ":native_binary",
            dwp_file = ":w.dwp",
            stripped_file = ":w.stripped",
        )

        wrapped_binary(
            name = "w.stripped",
            cc_binary = ":native_binary.stripped",
        )

        wrapped_binary(
            name = "w.dwp",
            cc_binary = ":native_binary.dwp",
        )

        cc_binary(
            name = "native_binary",
            srcs = ["main.cc"],
        )
        """);
    scratch.file("a/main.cc", "int main() {}");
  }

  @Test
  public void testDebugPackageProviderFissionDisabled() throws Exception {
    setupDebugPackageProviderTest("--fission=no");
    ConfiguredTarget target = getConfiguredTarget("//a:w");
    assertNoEvents();
    assertThat(target).isNotNull();
    DebugPackageProvider debugPackageProvider = target.get(DebugPackageProvider.PROVIDER);
    assertThat(debugPackageProvider.getStrippedArtifact().getFilename()).isEqualTo("w.stripped");
    assertThat(debugPackageProvider.getUnstrippedArtifact().getFilename()).isEqualTo("w");
    assertThat(debugPackageProvider.getDwpArtifact()).isNull();
  }

  @Test
  public void testDebugPackageProviderFissionEnabled() throws Exception {
    setupDebugPackageProviderTest("--fission=yes");
    ConfiguredTarget target = getConfiguredTarget("//a:w");
    assertNoEvents();
    assertThat(target).isNotNull();
    DebugPackageProvider debugPackageProvider = target.get(DebugPackageProvider.PROVIDER);
    assertThat(debugPackageProvider.getStrippedArtifact().getFilename()).isEqualTo("w.stripped");
    assertThat(debugPackageProvider.getUnstrippedArtifact().getFilename()).isEqualTo("w");
    assertThat(debugPackageProvider.getDwpArtifact().getFilename()).isEqualTo("w.dwp");
  }

  @Test
  public void testCcDebugContextDisabled() throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load("//my_rules:rule.bzl", "cc_compile_rule")

        cc_compile_rule(
            name = "b_lib",
            srcs = ["b_lib.cc"],
        )
        """);
    scratch.file("my_rules/BUILD");
    scratch.file(
        "my_rules/rule.bzl",
        """
        def _impl(ctx):
            comp_context = cc_common.create_compilation_context()
            comp_outputs = cc_common.create_compilation_outputs()
            debug_info = cc_common.create_debug_context(comp_outputs)
            return [CcInfo(compilation_context = comp_context, debug_info = debug_info)]

        cc_compile_rule = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = [".cc"]),
            },
        )
        """);
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:b_lib"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCcDebugContext() throws Exception {
    useConfiguration("--fission=yes");
    scratch.file(
        "b/BUILD",
        """
        load("//bazel_internal/test_rules/cc:rule.bzl", "cc_compile_rule")

        cc_toolchain_alias(name = "alias")

        cc_compile_rule(
            name = "b_lib",
            srcs = ["b_lib.cc"],
        )
        """);
    scratch.file("bazel_internal/test_rules/cc/BUILD");
    scratch.file(
        "bazel_internal/test_rules/cc/rule.bzl",
        """
        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                requested_features = ctx.features + ["per_object_debug_info"],
                unsupported_features = ctx.disabled_features,
            )
            (comp_context, comp_outputs) = cc_common.compile(
                name = ctx.label.name,
                actions = ctx.actions,
                feature_configuration = feature_configuration,
                cc_toolchain = toolchain,
                srcs = ctx.files.srcs,
            )
            debug_info = cc_common.create_debug_context(comp_outputs)
            return [CcInfo(compilation_context = comp_context, debug_context = debug_info)]

        cc_compile_rule = rule(
            implementation = _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//b:alias")),
                "srcs": attr.label_list(allow_files = [".cc"]),
            },
            fragments = ["cpp"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//b:b_lib");
    assertThat(
            target
                .get(CcInfo.PROVIDER)
                .getCcDebugInfoContext()
                .getTransitiveDwoFiles()
                .toList()
                .stream()
                .map(Artifact::getFilename))
        .containsExactly("b_lib.dwo");
    assertThat(
            target.get(CcInfo.PROVIDER).getCcDebugInfoContext().getTransitivePicDwoFiles().toList())
        .isEmpty();
  }

  @Test
  public void testExpandedCppConfigurationApiBlocked() throws Exception {
    List<String> cppConfigurationOptions =
        ImmutableList.of(
            "strip_opts()",
            "build_test_dwp()",
            "grte_top()",
            "experimental_cc_implementation_deps()",
            "experimental_cpp_modules()",
            "share_native_deps()",
            "experimental_platform_cc_test()");
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "cpp_config_rule")

        cpp_config_rule(name = "custom")
        """);
    for (String option : cppConfigurationOptions) {
      scratch.overwriteFile(
          "foo/custom_rule.bzl",
          "def _impl(ctx):",
          "  ctx.fragments.cpp." + option,
          "  return []",
          "cpp_config_rule = rule(",
          "  implementation = _impl,",
          "  fragments = [\"cpp\"],",
          ")");
      invalidatePackages();

      AssertionError e =
          assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedCompileApiBlocked() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load(":module_map.bzl", "module_map")

        module_map(
            name = "module_map",
            file = "a_file.txt",
        )
        """);

    scratch.file(
        "bazel_internal/test_rules/cc/module_map.bzl",
        """
        ModuleMapInfo = provider(fields = ["module_map", "file"])

        def _impl(ctx):
            module_map = cc_common.create_module_map(
                file = ctx.file.file,
                umbrella_header = ctx.file.file,
                name = "module",
            )
            return [ModuleMapInfo(module_map = module_map, file = ctx.file.file)]

        module_map = rule(
            _impl,
            attrs = {
                "file": attr.label(allow_single_file = True),
            },
            fragments = ["cpp"],
        )
        """);

    String compileCall =
        "cc_common.compile(actions = ctx.actions, feature_configuration = feature_configuration,"
            + " name = 'name', cc_toolchain = toolchain, ";
    List<String> calls =
        ImmutableList.of(
            "cc_common.create_module_map(file=file, umbrella_header=file, name='name')",
            "module_map.file()",
            "module_map.umbrella_header()",
            compileCall + " module_map = module_map)",
            compileCall + " additional_module_maps = [module_map])",
            compileCall + "additional_exported_hdrs = [])",
            compileCall + "propagate_module_map_to_compile_action = True)",
            compileCall + "do_not_generate_module_map = True)",
            compileCall + "code_coverage_enabled = True)",
            compileCall + "separate_module_headers = [])",
            compileCall + "hdrs_checking_mode = 'strict')");
    scratch.overwriteFile(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(
            name = "r",
            deps = ["//bazel_internal/test_rules/cc:module_map"],
        )
        """);

    for (String call : calls) {
      scratch.overwriteFile(
          "a/rule.bzl",
          "load('//bazel_internal/test_rules/cc:module_map.bzl', 'ModuleMapInfo')",
          "CruleInfo = provider()",
          "def _impl(ctx):",
          "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
          "  feature_configuration = cc_common.configure_features(",
          "    ctx = ctx,",
          "    cc_toolchain = toolchain,",
          "  )",
          "  module_map = ctx.attr.deps[0][ModuleMapInfo].module_map",
          "  file = ctx.attr.deps[0][ModuleMapInfo].file",
          "  " + call,
          "  return [CruleInfo()]",
          "crule = rule(",
          "  _impl,",
          "  attrs = { ",
          "    'deps': attr.label_list(),",
          "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
          "  },",
          "  fragments = ['cpp'],",
          ");");
      initializeSkyframeExecutor();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:r"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedCcCompilationContextApiBlocked() throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load(
            "//my_rules:rule.bzl",
            "additional_inputs_rule",
            "method_rule",
            "param_2_rule",
            "transitive_modules_rule",
        )

        param_2_rule(
            name = "p2",
        )

        method_rule(
            name = "m",
        )

        additional_inputs_rule(
            name = "ai",
        )

        transitive_modules_rule(
            name = "tm",
        )
        """);
    scratch.file("my_rules/BUILD");
    scratch.file(
        "my_rules/rule.bzl",
        """
        def _m_impl(ctx):
            comp_context = cc_common.create_compilation_context()
            comp_context.transitive_compilation_prerequisites()
            return [CcInfo(compilation_context = comp_context)]

        def _p2_impl(ctx):
            comp_context = cc_common.create_compilation_context(purpose = "testing")
            return [CcInfo(compilation_context = comp_context)]

        def _additional_inputs_impl(ctx):
            comp_context = cc_common.create_compilation_context()
            comp_context.additional_inputs()

        def _transitive_modules_impl(ctx):
            comp_context = cc_common.create_compilation_context()
            comp_context.transitive_modules(use_pic = True)

        method_rule = rule(
            implementation = _m_impl,
        )
        param_2_rule = rule(
            implementation = _p2_impl,
        )
        additional_inputs_rule = rule(
            implementation = _additional_inputs_impl,
        )
        transitive_modules_rule = rule(
            implementation = _transitive_modules_impl,
        )
        """);
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:m"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:p2"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:ai"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:tm"));
    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testExpandedLinkApiRaisesError() throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load("//b:rule.bzl", "link_rule")

        cc_toolchain_alias(name = "alias")

        link_rule(name = "foo")
        """);
    String callFormatString =
        "cc_common.link(name='test', actions=ctx.actions,"
            + "feature_configuration=feature_configuration, cc_toolchain=toolchain, %s)";
    ImmutableList<String> calls =
        ImmutableList.of(
            String.format(callFormatString, "link_artifact_name_suffix='test'"),
            String.format(callFormatString, "never_link=False"),
            String.format(callFormatString, "test_only_target=False"),
            String.format(callFormatString, "always_link=False"),
            String.format(callFormatString, "additional_linkstamp_defines=[]"),
            String.format(callFormatString, "whole_archive=False"),
            String.format(callFormatString, "native_deps=False"),
            String.format(callFormatString, "only_for_dynamic_libs=False"),
            String.format(callFormatString, "emit_interface_shared_library=True"));
    for (String call : calls) {
      scratch.overwriteFile(
          "b/rule.bzl",
          "def _impl(ctx):",
          "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
          "  feature_configuration = cc_common.configure_features(",
          "    ctx = ctx,",
          "    cc_toolchain = toolchain,",
          "  )",
          "  " + call,
          "  return [DefaultInfo()]",
          "link_rule = rule(",
          "  implementation = _impl,",
          "  attrs = {",
          "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
          "  },",
          "  fragments = ['cpp'],",
          ")");
      initializeSkyframeExecutor();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedCcCompilationOutputsApiRaisesError() throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load("//b:rule.bzl", "cc_rule")

        cc_rule(
            name = "foo",
        )
        """);
    ImmutableList<String> calls =
        ImmutableList.of(
            "comp_outputs.temps()",
            "comp_outputs.files_to_compile(parse_headers=False, use_pic=True)",
            "comp_outputs.header_tokens()",
            "comp_outputs.module_files()");
    for (String call : calls) {
      scratch.overwriteFile(
          "b/rule.bzl",
          "def _impl(ctx):",
          "  comp_outputs = cc_common.create_compilation_outputs()",
          "  " + call,
          "  return [DefaultInfo()]",
          "cc_rule = rule(",
          "  implementation = _impl,",
          ")");
      invalidatePackages();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedLtoAndFdoApiRaisesError() throws Exception {
    useConfiguration("--fdo_optimize=/pkg/profile.afdo", "--compilation_mode=opt");
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load(":lto_backend_artifacts.bzl", "lto_backend_artifacts")

        lto_backend_artifacts(
            name = "lto_backend_artifacts",
            file = "a_file.txt",
        )
        """);

    scratch.file(
        "bazel_internal/test_rules/cc/lto_backend_artifacts.bzl",
        """
        LtoBackendArtifactsInfo = provider(fields = [
            "lto_backend_artifacts",
            "fdo_context",
            "branch_fdo_profile",
        ])

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            fdo_context = toolchain._fdo_context
            branch_fdo_profile = fdo_context.branch_fdo_profile
            lto_backend_artifacts = cc_common.create_lto_backend_artifacts(
                ctx = ctx,
                lto_output_root_prefix = ctx.label.package,
                lto_obj_root_prefix = ctx.label.package,
                bitcode_file = ctx.file.file,
                feature_configuration = feature_configuration,
                cc_toolchain = toolchain,
                fdo_context = fdo_context,
                use_pic = True,
                should_create_per_object_debug_info = False,
                argv = [],
            )
            return [LtoBackendArtifactsInfo(
                lto_backend_artifacts = lto_backend_artifacts,
                fdo_context = fdo_context,
                branch_fdo_profile = branch_fdo_profile,
            )]

        lto_backend_artifacts = rule(
            _impl,
            attrs = {
                "file": attr.label(allow_single_file = True),
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        """);

    ImmutableList<String> calls =
        ImmutableList.of(
            "library_to_link.shared_non_lto_backends()",
            "library_to_link.pic_shared_non_lto_backends()",
            "lto_backend_artifacts_info.lto_backend_artifacts.object_file()");
    scratch.overwriteFile(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        cc_library(
            name = "clib",
            srcs = ["clib.cc"],
            hdrs = ["clib.h"],
        )

        crule(
            name = "r",
            cc_dep = ":clib",
            lto_dep = "//bazel_internal/test_rules/cc:lto_backend_artifacts",
        )
        """);

    for (String call : calls) {
      scratch.overwriteFile(
          "a/rule.bzl",
          "load('//bazel_internal/test_rules/cc:lto_backend_artifacts.bzl',",
          "             'LtoBackendArtifactsInfo')",
          "CruleInfo = provider()",
          "def _impl(ctx):",
          "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
          "  feature_configuration = cc_common.configure_features(",
          "    ctx = ctx,",
          "    cc_toolchain = toolchain,",
          "  )",
          "  library_to_link = (ctx.attr.cc_dep[CcInfo].linking_context",
          "                                     .linker_inputs.to_list()[0].libraries[0])",
          "  lto_backend_artifacts_info = ctx.attr.lto_dep[LtoBackendArtifactsInfo]",
          "  " + call,
          "  return [CruleInfo()]",
          "crule = rule(",
          "  _impl,",
          "  attrs = { ",
          "    'lto_dep': attr.label(),",
          "    'cc_dep': attr.label(),",
          "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
          "  },",
          "  fragments = ['cpp'],",
          ");");
      invalidatePackages();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:r"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedLibraryToLinkApiRaisesError() throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load("//b:rule.bzl", "cc_rule")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
        )

        cc_toolchain_alias(name = "alias")

        cc_rule(
            name = "foo",
            cc_dep = ":cc_dep",
        )
        """);
    ImmutableList<String> calls =
        ImmutableList.of(
            "cc_common.create_library_to_link(actions=ctx.actions,"
                + "feature_configuration=feature_configuration, cc_toolchain=toolchain,"
                + " must_keep_debug=False)",
            "library_to_link.must_keep_debug()",
            "cc_common.create_library_to_link(actions=ctx.actions,"
                + "feature_configuration=feature_configuration, cc_toolchain=toolchain,"
                + " lto_compilation_context=None)");
    for (String call : calls) {
      scratch.overwriteFile(
          "b/rule.bzl",
          "def _impl(ctx):",
          "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
          "  feature_configuration = cc_common.configure_features(",
          "    ctx = ctx,",
          "    cc_toolchain = toolchain,",
          "  )",
          "  library_to_link = (ctx.attr.cc_dep[CcInfo].linking_context",
          "                                     .linker_inputs.to_list()[0].libraries[0])",
          "  " + call,
          "  return [DefaultInfo()]",
          "cc_rule = rule(",
          "  implementation = _impl,",
          "  attrs = { ",
          "    'cc_dep': attr.label(),",
          "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
          "  },",
          "  fragments = ['cpp'],",
          ")");
      invalidatePackages();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testExpandedLinkstampApiRaisesError() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load(":linkstamps.bzl", "linkstamps")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
            linkstamp = "stamp.cc",
        )

        linkstamps(
            name = "linkstamps",
            deps = [":cc_dep"],
        )
        """);

    scratch.file(
        "bazel_internal/test_rules/cc/linkstamps.bzl",
        """
        LinkstampsInfo = provider(fields = ["linkstamps"])

        def _impl(ctx):
            linkstamps = ctx.attr.deps[0][CcInfo].linking_context.linkstamps().to_list()
            return [LinkstampsInfo(linkstamps = linkstamps)]

        linkstamps = rule(
            _impl,
            attrs = {
                "deps": attr.label_list(),
            },
        )
        """);

    scratch.file(
        "b/BUILD",
        """
        load("//b:rule.bzl", "cc_rule")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
        )

        cc_toolchain_alias(name = "alias")

        cc_rule(
            name = "foo",
            cc_dep = ":cc_dep",
            file = "file.cc",
            linkstamps_dep = "//bazel_internal/test_rules/cc:linkstamps",
        )
        """);
    List<String> calls =
        new ArrayList<>(
            Arrays.asList("linkstamp.file()", "linkstamp.hdrs()", "linking_context.linkstamps()"));
    if (!analysisMock.isThisBazel()) {
      calls.add(
          "cc_common.register_linkstamp_compile_action(actions=ctx.actions,cc_toolchain=toolchain,"
              + " feature_configuration=feature_configuration, "
              + " source_file=file, output_file=file,"
              + " compilation_inputs=depset([]), inputs_for_validation=depset([]),"
              + " label_replacement='', output_replacement='')");
    }
    for (String call : calls) {
      scratch.overwriteFile(
          "b/rule.bzl",
          "load('//bazel_internal/test_rules/cc:linkstamps.bzl',",
          "             'LinkstampsInfo')",
          "def _impl(ctx):",
          "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
          "  feature_configuration = cc_common.configure_features(",
          "    ctx = ctx,",
          "    cc_toolchain = toolchain,",
          "  )",
          "  linkstamp = ctx.attr.linkstamps_dep[LinkstampsInfo].linkstamps[0]",
          "  linking_context = ctx.attr.cc_dep[CcInfo].linking_context",
          "  file = ctx.file.file",
          "  " + call,
          "  return [DefaultInfo()]",
          "cc_rule = rule(",
          "  implementation = _impl,",
          "  attrs = { ",
          "    'cc_dep': attr.label(),",
          "    'linkstamps_dep': attr.label(),",
          "    '_cc_toolchain': attr.label(default=Label('//b:alias')),",
          "    'file': attr.label(allow_single_file=True),",
          "  },",
          "  fragments = ['cpp'],",
          ")");
      invalidatePackages();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
      assertThat(e).hasMessageThat().contains("cannot use private API");
    }
  }

  @Test
  public void testVariableExtensionCompileApi() throws Exception {
    createFilesForTestingCompilation(
        scratch, "tools/build_defs/foo", "variables_extension = foo_dict");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    CppCompileAction action =
        (CppCompileAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o"));

    var unused1 =
        action
            .getCompileCommandLine()
            .getVariables()
            .getSequenceVariable("string_sequence_variable", PathMapper.NOOP);
    var unused2 =
        action
            .getCompileCommandLine()
            .getVariables()
            .getStringVariable("string_variable", PathMapper.NOOP);
    var unused3 =
        action
            .getCompileCommandLine()
            .getVariables()
            .getSequenceVariable("string_depset_variable", PathMapper.NOOP);
  }

  @Test
  public void testVariableExtensionLinkingContextApi() throws Exception {
    createFilesForTestingLinking(scratch, "tools/build_defs/foo", "variables_extension = foo_dict");
    assertThat(getConfiguredTarget("//foo:bin")).isNotNull();
    ConfiguredTarget target = getConfiguredTarget("//foo:starlark_lib");
    SpawnAction action =
        (SpawnAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".a"));

    var unused1 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getSequenceVariable("string_sequence_variable", PathMapper.NOOP);
    var unused2 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getStringVariable("string_variable", PathMapper.NOOP);
    var unused3 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getSequenceVariable("string_depset_variable", PathMapper.NOOP);
  }

  @Test
  public void testVariableExtensionLinkApi() throws Exception {
    setupTestTransitiveLink(
        scratch, "output_type = 'executable'", "variables_extension = foo_dict");
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    SpawnAction action =
        (SpawnAction)
            getGeneratingAction((Artifact) getMyInfoFromTarget(target).getValue("executable"));

    var unused1 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getSequenceVariable("string_sequence_variable", PathMapper.NOOP);
    var unused2 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getStringVariable("string_variable", PathMapper.NOOP);
    var unused3 =
        getLinkCommandLine(action)
            .getBuildVariables()
            .getSequenceVariable("string_depset_variable", PathMapper.NOOP);
  }

  @Test
  public void testVariablesExtensionInvalidValueTypesThrowsError() throws Exception {
    String rulePkg = "b";
    scratch.overwriteFile(rulePkg + "/BUILD", "");
    scratch.overwriteFile(
        "b/BUILD",
        "load('//" + rulePkg + ":rule.bzl', 'cc_rule')",
        "cc_library(name='cc_dep', srcs=['cc_dep.cc'])",
        "cc_toolchain_alias(name='alias')",
        "cc_rule(name='foo', cc_dep=':cc_dep')");
    for (String value : ImmutableList.of("1", "[1]", "depset([1])")) {
      scratch.overwriteFile(
          rulePkg + "/rule.bzl",
          getVariablesExtensionStarlarkRule("compile", "dict = {'variable': " + value + "}"));
      invalidatePackages();
      AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
      assertThat(e).hasMessageThat().contains("Trying to build UserVariableExtension");
    }
  }

  @Test
  public void testAdditionalLinkingOutputsAppearAsOutputsOfLinkAction() throws Exception {
    createCcBinRule(
        scratch, /* internalApi= */ false, "additional_outputs=ctx.outputs.additional_outputs");
    scratch.file(
        "foo/BUILD",
        """
        load("//tools/build_defs:extension.bzl", "cc_bin")

        cc_bin(
            name = "bin",
            additional_outputs = [":bin.map"],
            objects = ["file.o"],
            pic_objects = ["file.pic.o"],
        )
        """);
    ConfiguredTarget target = getConfiguredTarget("//foo:bin");
    assertThat(target).isNotNull();
    SpawnAction action =
        (SpawnAction) getGeneratingAction(artifactByPath(getFilesToBuild(target), ".map"));
    assertThat(artifactsToStrings(action.getOutputs())).contains("bin foo/bin.map");
  }

  private String getVariablesExtensionStarlarkRule(String call, String dictionaryEntries) {
    return Joiner.on("\n")
        .join(
            "def _impl(ctx):",
            "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  " + dictionaryEntries,
            "  cc_common." + call + "(",
            "      actions = ctx.actions,",
            "      feature_configuration = feature_configuration,",
            "      cc_toolchain = toolchain,",
            "      name = ctx.label.name + '_aspect',",
            "      variables_extension = dict,",
            "  )",
            "  return [DefaultInfo()]",
            "cc_rule = rule(",
            "  implementation = _impl,",
            "  attrs = { ",
            "    'cc_dep': attr.label(),",
            "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            ")");
  }

  @Test
  public void testMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink()
      throws Exception {
    scratch.file(
        "b/BUILD",
        """
        load("//b:rule.bzl", "cc_rule")

        cc_toolchain_alias(name = "alias")

        cc_rule(name = "foo")
        """);

    runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
        "None,", "feature_configuration,", "ctx.file._artifact,", "None,");
    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
    assertThat(e)
        .hasMessageThat()
        .contains("If you pass 'dynamic_library', you must also pass a 'cc_toolchain'");

    runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
        "toolchain,", "None,", "ctx.file._artifact,", "None,");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
    assertThat(e)
        .hasMessageThat()
        .contains("If you pass 'dynamic_library', you must also pass a 'feature_configuration'");

    runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
        "None,", "feature_configuration,", "None,", "ctx.file._artifact,");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
    assertThat(e)
        .hasMessageThat()
        .contains("If you pass 'interface_library', you must also pass a 'cc_toolchain'");

    runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
        "toolchain,", "None,", "None,", "ctx.file._artifact,");
    e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//b:foo"));
    assertThat(e)
        .hasMessageThat()
        .contains("If you pass 'interface_library', you must also pass a 'feature_configuration'");
  }

  private void runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
      String toolchain, String featureConfiguration, String dynamicLibrary, String interfaceLibrary)
      throws Exception {
    scratch.overwriteFile(
        "b/rule.bzl",
        "def _impl(ctx):",
        "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "  feature_configuration = cc_common.configure_features(",
        "    ctx = ctx,",
        "    cc_toolchain = toolchain,",
        "  )",
        "  cc_common.create_library_to_link(",
        "    actions=ctx.actions, ",
        "    cc_toolchain = " + toolchain,
        "    feature_configuration = " + featureConfiguration,
        "    dynamic_library =" + dynamicLibrary,
        "    interface_library =" + interfaceLibrary,
        "  )",
        "  return [DefaultInfo()]",
        "cc_rule = rule(",
        "  implementation = _impl,",
        "  attrs = { ",
        "    '_artifact': attr.label(allow_single_file=True, default=Label('//b:foo.soifso')),",
        "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
        "  },",
        "  fragments = ['cpp'],",
        ")");
    invalidatePackages();
  }

  @Test
  public void testCcInternalIsNotAccessibleFromOutsideBuiltins() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load(":rule.bzl", "crule")

        crule(name = "r")
        """);

    scratch.file(
        "a/rule.bzl",
        """
        def _impl(ctx):
            cc_internal
            return DefaultInfo()

        crule = rule(
            _impl,
        )
        """);

    AssertionError e = assertThrows(AssertionError.class, () -> getConfiguredTarget("//a:r"));
    assertThat(e).hasMessageThat().contains("name 'cc_internal' is not defined");
  }

  @Test
  public void testExtendedBuildConfigurationApiBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "build_config_rule")

        build_config_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            ctx.configuration.stamp_binaries()
            return []

        build_config_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("file '//foo:custom_rule.bzl' cannot use private API");
  }

  @Test
  public void testExtendedCcCompilationOutputsLtoCompilationContextBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "compilation_outputs_rule")

        compilation_outputs_rule(name = "custom")
        """);

    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs().lto_compilation_context()
            return []

        compilation_outputs_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testExtendedCcCompilationOutputsDwoFilesBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "compilation_outputs_rule")

        compilation_outputs_rule(name = "custom")
        """);

    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs().dwo_files()
            return []

        compilation_outputs_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testExtendedCcCompilationOutputsPicDwoFilesBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "compilation_outputs_rule")

        compilation_outputs_rule(name = "custom")
        """);

    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs().pic_dwo_files()
            return []

        compilation_outputs_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testExtendedCcLinkingContextApiBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "cc_linking_context_rule")

        cc_linking_context_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_linking_context(linker_inputs = depset()).extra_link_time_libraries()
            return []

        cc_linking_context_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testExtendedCcLinkingOutputsApiBlocked() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "cc_linking_outputs_rule")

        cc_toolchain_alias(name = "alias")

        cc_linking_outputs_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_linking_outputs = cc_common.link(
                actions = ctx.actions,
                feature_configuration = feature_configuration,
                cc_toolchain = cc_toolchain,
                name = ctx.label.name,
            )
            cc_linking_outputs.all_lto_artifacts()
            return []

        cc_linking_outputs_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testToolRequirementForActionIsNotAccessibleFromOutsideBuiltins() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.get_tool_requirement_for_action(
                feature_configuration = feature_configuration,
                action_name = "test",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCreateCompilationOutputsPrivateParameterIsNotAccessibleFromOutsideBuiltins()
      throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                objects = None,
                pic_objects = None,
                lto_compilation_context = None,
            )
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testGetCompileBuildVariablesStripOptsNotAccessibleFromOutsideBuiltins()
      throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_compile_variables(
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                strip_opts = [],
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testGetCompileBuildVariablesInputFileNotAccessibleFromOutsideBuiltins()
      throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_compile_variables(
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                input_file = "",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCreateLinkingContextFromCompilationOutputsStampNotAccessibleFromOutsideBuiltins()
      throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_linking_context_from_compilation_outputs(
                actions = ctx.actions,
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                compilation_outputs = cc_common.create_compilation_outputs(),
                name = "test",
                stamp = 0,
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testLinkUseTestOnlyFlagNotAccessibleFromOutsideBuiltins() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.link(
                actions = ctx.actions,
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                use_test_only_flags = True,
                name = "test",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        """);
    invalidatePackages();

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCheckPrivateApiCanOnlyBeCalledFromCcCommonBzl() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.check_private_api(allowlist = [])
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e)
        .hasMessageThat()
        .contains("'struct' value has no field or method 'check_private_api'");
  }

  @Test
  public void testCheckPrivateApiAllowlistBlocksPrivateParameter() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_context(purpose = "whatever")
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testCheckPrivateApiAllowlistAllowsPrivateParameter() throws Exception {
    scratch.overwriteFile(
        "tools/build_defs/android/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.overwriteFile(
        "tools/build_defs/android/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_context(purpose = "whatever")
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);

    getConfiguredTarget("//tools/build_defs/android:custom");
  }

  @Test
  public void testDwoObjectsAllowlistBlocksPrivateParameter() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs(dwo_objects = depset())
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testPicDwoObjectsAllowlistBlocksPrivateParameter() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "foo/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs(pic_dwo_objects = depset())
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:custom"));

    assertThat(e).hasMessageThat().contains("cannot use private API");
  }

  @Test
  public void testPicDwoObjectsAllowlistAllowsPrivateParameter() throws Exception {
    scratch.file(
        "bazel_internal/test_rules/cc/BUILD",
        """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);
    scratch.file(
        "bazel_internal/test_rules/cc/custom_rule.bzl",
        """
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                dwo_objects = depset(),
                pic_dwo_objects = depset(),
            )
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        """);
    getConfiguredTarget("//bazel_internal/test_rules/cc:custom");
  }

  // grep_includes is not supported by Bazel.
  @Test
  public void testGrepIncludesIsSetToNullInsideCcToolchain() throws Exception {
    if (!AnalysisMock.get().isThisBazel()) {
      return;
    }
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "cc_skylark_library")

        cc_skylark_library(
            name = "skylark_lib",
        )
        """);
    scratch.file(
        "foo/extension.bzl",
        "def _cc_skylark_library_impl(ctx):",
        "    if ctx.attr._cc_toolchain:",
        "      toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
        "    else:",
        "      toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
        "    return [toolchain]",
        "cc_skylark_library = rule(",
        "    implementation = _cc_skylark_library_impl,",
        "    attrs = {",
        "      '_cc_toolchain': attr.label(default =",
        "          configuration_field(fragment = 'cpp', name = 'cc_toolchain')),",
        "    },",
        "    fragments = ['cpp'],",
        "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
        ")");

    ConfiguredTarget target = getConfiguredTarget("//foo:skylark_lib");
    CcToolchainProvider toolchainProvider = target.get(CcToolchainProvider.PROVIDER);
    Artifact grepIncludes = toolchainProvider.getGrepIncludes();

    assertThat(grepIncludes).isNull();
  }
}
