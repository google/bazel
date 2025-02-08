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
package com.google.devtools.build.lib.rules.java;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ProviderCollection;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.testutil.TestConstants;
import net.starlark.java.eval.EvalException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the java_runtime rule. */
@RunWith(JUnit4.class)
public class JavaRuntimeTest extends BuildViewTestCase {
  @Before
  public final void initializeJvmPackage() throws Exception {
    scratch.file(
        "jvm/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_runtime")
        java_runtime(
            name = "jvm-k8",
            srcs = [
                "k8/a",
                "k8/b",
            ],
            java_home = "k8",
        )
        """);
  }

  private JavaRuntimeInfo getJavaRuntimeInfo(ProviderCollection collection)
      throws EvalException, RuleErrorException {
    ToolchainInfo toolchainInfo = collection.get(ToolchainInfo.PROVIDER);
    return JavaRuntimeInfo.wrap(toolchainInfo.getValue("java_runtime", Info.class));
  }

  @Test
  public void simple() throws Exception {
    ConfiguredTarget target = getConfiguredTarget("//jvm:jvm-k8");
    assertThat(ActionsTestUtil.prettyArtifactNames(getJavaRuntimeInfo(target).javaBaseInputs()))
        .containsExactly("jvm/k8/a", "jvm/k8/b");
    assertThat(
            ActionsTestUtil.prettyArtifactNames(
                target.getProvider(RunfilesProvider.class).getDataRunfiles().getArtifacts()))
        .containsExactly("jvm/k8/a", "jvm/k8/b");
  }

  @Test
  public void absoluteJavaHomeWithSrcs() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[':dummy'], java_home='/absolute/path')");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:jvm");
    assertContainsEvent("'java_home' with an absolute path requires 'srcs' to be empty.");
  }

  @Test
  public void absoluteJavaHomeWithJava() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', java='bin/java', java_home='/absolute/path')");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:jvm");
    assertContainsEvent("'java_home' with an absolute path requires 'java' to be empty.");
  }

  @Test
  public void binJavaPathName() throws Exception {
    scratch.file(
        "BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', java='java')");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//:jvm");
    assertContainsEvent("the path to 'java' must end in 'bin/java'.");
  }

  @Test
  public void absoluteJavaHome() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[], java_home='/absolute/path')");
    reporter.removeHandler(failFastHandler);
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    assertThat(getJavaRuntimeInfo(jvm).javaHome()).isEqualTo("/absolute/path");
  }

  @Test
  public void relativeJavaHome() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[], java_home='b/c')");
    reporter.removeHandler(failFastHandler);
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    assertThat(getJavaRuntimeInfo(jvm).javaHome()).isEqualTo("a/b/c");
  }

  @Test
  public void testRuntimeAlias() throws Exception {
    ConfiguredTarget reference =
        scratchConfiguredTarget(
            "a",
            "ref",
            "load('"
                + TestConstants.TOOLS_REPOSITORY
                + "//tools/jdk:java_toolchain_alias.bzl', 'java_runtime_alias')",
            "java_runtime_alias(name='ref')");
    assertThat(reference.get(ToolchainInfo.PROVIDER)).isNotNull();
    assertThat(reference.get(TemplateVariableInfo.PROVIDER.getKey())).isNotNull();
  }

  @Test
  public void javaHomeWithMakeVariables() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[], java_home='/opt/$(CMDLINE)')");
    useConfiguration("--define=CMDLINE=foo/bar");
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    assertThat(getJavaRuntimeInfo(jvm).javaHome()).isEqualTo("/opt/foo/bar");
  }

  @Test
  public void javaHomeWithInvalidMakeVariables() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[], java_home='/opt/$(WTF)')");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:jvm");
    assertContainsEvent("$(WTF) not defined");
  }

  @Test
  public void makeVariables() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', srcs=[], java_home='/foo/bar')");
    ImmutableMap<String, String> runtime = getConfiguredTarget("//a:jvm")
        .get(TemplateVariableInfo.PROVIDER).getVariables();
    assertThat(runtime.get("JAVABASE")).isEqualTo("/foo/bar");
    assertThat(runtime.get("JAVA")).startsWith("/foo/bar/bin/java");  // Windows has .exe suffix
  }

  @Test
  public void noSrcs() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_runtime')",
        "java_runtime(name='jvm', java_home='/opt/jvm')");
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    JavaRuntimeInfo provider = getJavaRuntimeInfo(jvm);
    assertThat(provider.javaHome()).isEqualTo("/opt/jvm");
    assertThat(provider.javaBaseInputs().toList()).isEmpty();
  }

  @Test
  public void invalidJavaBase() throws Exception {
    scratch.file(
        "a/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(name='a', srcs=['A.java'])",
        "filegroup(name='fg')",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':fg',",
        "    toolchain_type = '"
            + TestConstants.TOOLS_REPOSITORY
            + "//tools/jdk:runtime_toolchain_type',",
        ")");
    useConfiguration("--javabase=//a:fg", "--extra_toolchains=//a:all");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:a");
    assertContainsEvent("does not provide ToolchainInfo");
  }

  @Test
  public void javaHomeGenerated() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_runtime")
        genrule(
            name = "gen",
            outs = ["generated_java_home/bin/java"],
            cmd = "",
        )

        java_runtime(
            name = "jvm",
            java = "generated_java_home/bin/java",
            java_home = "generated_java_home",
        )
        """);
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    assertThat(getJavaRuntimeInfo(jvm).javaHome())
        .isEqualTo(getGenfilesArtifactWithNoOwner("a/generated_java_home").getExecPathString());
  }

  @Test
  public void javaRuntimeVersion_isAccessibleByNativeCode() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_runtime")
        genrule(
            name = "gen",
            outs = ["generated_java_home/bin/java"],
            cmd = "",
        )

        java_runtime(
            name = "jvm",
            java = "generated_java_home/bin/java",
            java_home = "generated_java_home",
            version = 234,
        )
        """);
    ConfiguredTarget jvm = getConfiguredTarget("//a:jvm");
    assertThat(getJavaRuntimeInfo(jvm).version()).isEqualTo(234);
  }

  @Test
  public void disallowLegacyJavabaseFlag() {
    InvalidConfigurationException e =
        assertThrows(
            InvalidConfigurationException.class,
            () ->
                useConfiguration(
                    "--experimental_disallow_legacy_java_toolchain_flags",
                    "--javabase=//no/such:label"));
    assertThat(e).hasMessageThat().contains("--javabase=//no/such:label is no longer supported");
  }

  @Test
  public void disallowLegacyHostJavabaseFlagbaseFlag() {
    InvalidConfigurationException e =
        assertThrows(
            InvalidConfigurationException.class,
            () ->
                useConfiguration(
                    "--experimental_disallow_legacy_java_toolchain_flags",
                    "--host_javabase=//no/such:label"));
    assertThat(e)
        .hasMessageThat()
        .contains("--host_javabase=//no/such:label is no longer supported");
  }

  @Test
  public void disallowLegacyJavabaseFlag_unset() throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _ht(settings, attrs):
            return {
                "//command_line_option:javabase": "//tools/jdk:jdk",
                "//command_line_option:host_javabase": "//tools/jdk:remote_jdk11",
                "//command_line_option:java_toolchain": "//tools/jdk:remote_toolchain",
                "//command_line_option:host_java_toolchain": "//tools/jdk:remote_toolchain",
            }

        ht = transition(
            implementation = _ht,
            inputs = [
                "//command_line_option:javabase",
                "//command_line_option:host_javabase",
                "//command_line_option:java_toolchain",
                "//command_line_option:host_java_toolchain",
            ],
            outputs = [
                "//command_line_option:javabase",
                "//command_line_option:host_javabase",
                "//command_line_option:java_toolchain",
                "//command_line_option:host_java_toolchain",
            ],
        )

        def _r(ctx):
            return [DefaultInfo(files = ctx.attr.d[0].files)]

        r = rule(
            attrs = {
                "d": attr.label(
                    cfg = ht,
                    mandatory = True,
                ),
            },
            implementation = _r,
        )
        """);
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//..."],
        )

        filegroup(
            name = "srcs",
            srcs = glob(["**"]),
            visibility = ["//tools/allowlists:__pkg__"],
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        load(":defs.bzl", "r")

        java_binary(
            name = "d",
            main_class = "D",
        )

        r(
            name = "t",
            d = ":d",
        )
        """);

    useConfiguration("--experimental_disallow_legacy_java_toolchain_flags");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//a:t");
    assertContainsEvent("--javabase=//tools/jdk:jdk is no longer supported");
  }
}
