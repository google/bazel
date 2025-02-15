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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for Starlark interaction with the objc_* rules. */
@RunWith(JUnit4.class)
public class ObjcStarlarkTest extends ObjcRuleTestCase {
  private static final Provider.Key APPLE_EXECUTABLE_BINARY_PROVIDER_KEY =
      new StarlarkProvider.Key(
          keyForBuild(Label.parseCanonicalUnchecked("//test_starlark:apple_binary_starlark.bzl")),
          "AppleExecutableBinaryInfo");

  @Before
  public void setupMyInfo() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStarlarkRuleCanDependOnNativeAppleRule() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            library_to_link = dep[CcInfo].linking_context.linker_inputs.to_list()[0].libraries[0]
            return MyInfo(
                found_hdrs = dep[CcInfo].compilation_context.headers.to_list(),
                found_libs = [library_to_link.static_library],
            )

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[CcInfo]],
                ),
            },
        )
        """);
    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            hdrs = ["b.h"],
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);
    List<Artifact> starlarkHdrs = (List<Artifact>) myInfo.getValue("found_hdrs");
    List<Artifact> starlarkLibraries = (List<Artifact>) myInfo.getValue("found_libs");

    assertThat(ActionsTestUtil.baseArtifactNames(starlarkHdrs)).contains("b.h");
    assertThat(ActionsTestUtil.baseArtifactNames(starlarkLibraries)).contains("liblib.a");
  }

  @Test
  public void testStarlarkProviderRetrievalNoneIfNoProvider() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            objc_provider = dep[apple_common.Objc]  # this is line 3
            return []

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        """);
    scratch.file("test_starlark/apple_starlark/a.cc");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")
        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        cc_library(
            name = "lib",
            srcs = ["a.cc"],
            hdrs = ["b.h"],
        )
        """);
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () -> getConfiguredTarget("//test_starlark/apple_starlark:my_target"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "apple_starlark/BUILD:4:8: in my_rule rule //test_starlark/apple_starlark:my_target:");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "File \"/workspace/test_starlark/rule/apple_rules.bzl\", line 3, column 24, in"
                + " my_rule_impl");
    assertThat(e)
        .hasMessageThat()
        .contains(
            "<target //test_starlark/apple_starlark:lib> (rule 'cc_library') "
                + "doesn't contain declared provider 'ObjcInfo'");
  }

  @Test
  public void testStarlarkProviderCanCheckForExistenceOfObjcProvider() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def my_rule_impl(ctx):
            cc_has_provider = apple_common.Objc in ctx.attr.deps[0]
            objc_has_provider = apple_common.Objc in ctx.attr.deps[1]
            return MyInfo(cc_has_provider = cc_has_provider, objc_has_provider = objc_has_provider)

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        """);
    scratch.file("test_starlark/apple_starlark/a.cc");
    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [
                ":cc_lib",
                ":objc_lib",
            ],
        )

        objc_library(
            name = "objc_lib",
            srcs = ["a.m"],
        )

        cc_library(
            name = "cc_lib",
            srcs = ["a.cc"],
        )
        """);
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);
    boolean ccResult = (boolean) myInfo.getValue("cc_has_provider");
    boolean objcResult = (boolean) myInfo.getValue("objc_has_provider");
    assertThat(ccResult).isFalse();
    assertThat(objcResult).isTrue();
  }

  @Test
  public void testStarlarkExportsObjcProviderToNativeRule() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            return [dep[apple_common.Objc], dep[CcInfo]]

        swift_library = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[apple_common.Objc, CcInfo]],
                ),
            },
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_library")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )

        swift_library(
            name = "my_target",
            deps = [":lib"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = [":my_target"],
        )
        """);

    ConfiguredTarget binaryTarget = getConfiguredTarget("//test_starlark/apple_starlark:bin");
    StructImpl executableProvider =
        (StructImpl) binaryTarget.get(APPLE_EXECUTABLE_BINARY_PROVIDER_KEY);
    CcLinkingContext ccLinkingContext =
        executableProvider.getValue("cc_info", CcInfo.class).getCcLinkingContext();

    assertThat(
            Artifact.toRootRelativePaths(
                ccLinkingContext.getStaticModeParamsForDynamicLibraryLibraries()))
        .contains("test_starlark/apple_starlark/liblib.a");
  }

  @Test
  public void testStarlarkLinkBinaryInRootPackage() throws Exception {
    scratch.file("a.m");
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "BUILD",
        """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "macos",
            deps = [":lib"],
        )
        """);

    assertThat(getConfiguredTarget("//:bin")).isNotNull();
  }

  @Test
  public void testObjcRuleCanDependOnArbitraryStarlarkRuleThatProvidesCcInfo() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        def my_rule_impl(ctx):
            return [CcInfo()]

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {},
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    addAppleBinaryStarlarkRule(scratch);
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            deps = [":my_target"],
        )

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = [":lib"],
        )
        """);

    ConfiguredTarget libTarget = getConfiguredTarget("//test_starlark/apple_starlark:lib");
    assertThat(libTarget.get(CcInfo.PROVIDER)).isNotNull();
  }

  @Test
  public void testStarlarkCanAccessAppleConfiguration() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
            cpu = ctx.fragments.apple.single_arch_cpu
            platform = ctx.fragments.apple.single_arch_platform
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
            dead_code_report = ctx.attr._dead_code_report
            env = apple_common.target_apple_env(xcode_config, platform)
            xcode_version = xcode_config.xcode_version()
            sdk_version = xcode_config.sdk_version_for_platform(platform)
            single_arch_platform = ctx.fragments.apple.single_arch_platform
            single_arch_cpu = ctx.fragments.apple.single_arch_cpu
            platform_type = single_arch_platform.platform_type
            return MyInfo(
                cpu = cpu,
                env = env,
                xcode_version = str(xcode_version),
                sdk_version = str(sdk_version),
                single_arch_platform = str(single_arch_platform),
                single_arch_cpu = str(single_arch_cpu),
                platform_type = str(platform_type),
                dead_code_report = str(dead_code_report),
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
            attrs = {
                "_xcode_config": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
                "_dead_code_report": attr.label(
                    default = configuration_field(
                        fragment = "j2objc",
                        name = "dead_code_report",
                    ),
                ),
            },
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
           name="my_target",
        )
        """);

    useConfiguration("--apple_platform_type=ios", "--ios_multi_cpus=x86_64", "--xcode_version=7.3");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    Object iosCpu = myInfo.getValue("cpu");
    @SuppressWarnings("unchecked")
    Map<String, String> env = (Map<String, String>) myInfo.getValue("env");
    Object sdkVersion = myInfo.getValue("sdk_version");

    assertThat(iosCpu).isEqualTo("x86_64");
    assertThat(env).containsEntry("APPLE_SDK_PLATFORM", "iPhoneSimulator");
    assertThat(env).containsEntry("APPLE_SDK_VERSION_OVERRIDE", "8.4");
    assertThat(sdkVersion).isEqualTo("8.4");
    assertThat(myInfo.getValue("xcode_version")).isEqualTo("7.3");
    assertThat(myInfo.getValue("single_arch_platform")).isEqualTo("ios_simulator");
    assertThat(myInfo.getValue("single_arch_cpu")).isEqualTo("x86_64");
    assertThat(myInfo.getValue("platform_type")).isEqualTo("ios");
    assertThat(myInfo.getValue("dead_code_report")).isEqualTo("None");
  }

  @Test
  public void testDefaultJ2objcDeadCodeReport() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            dead_code_report = ctx.attr._dead_code_report
            return MyInfo(
                dead_code_report = str(dead_code_report),
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["j2objc"],
            attrs = {
                "_dead_code_report": attr.label(
                    default = configuration_field(
                        fragment = "j2objc",
                        name = "dead_code_report",
                    ),
                ),
            },
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration();
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    assertThat(getMyInfoFromTarget(starlarkTarget).getValue("dead_code_report")).isEqualTo("None");
  }

  @Test
  public void testCustomJ2objcDeadCodeReport() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def dead_code_report_impl(ctx):
            return MyInfo(foo = "bar")

        def swift_binary_impl(ctx):
            dead_code_report = ctx.attr._dead_code_report[MyInfo].foo
            return MyInfo(
                dead_code_report = dead_code_report,
            )

        dead_code_report = rule(
            implementation = dead_code_report_impl,
        )
        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["j2objc"],
            attrs = {
                "_dead_code_report": attr.label(
                    default = configuration_field(
                        fragment = "j2objc",
                        name = "dead_code_report",
                    ),
                ),
            },
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "dead_code_report", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )

        dead_code_report(name = "dead_code_report")
        """);

    useConfiguration("--j2objc_dead_code_report=//test_starlark/apple_starlark:dead_code_report");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    assertThat(getMyInfoFromTarget(starlarkTarget).getValue("dead_code_report")).isEqualTo("bar");
  }

  @Test
  public void testStarlarkCanAccessJ2objcTranslationFlags() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            j2objc_flags = ctx.fragments.j2objc.translation_flags
            return MyInfo(
                j2objc_flags = j2objc_flags,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["j2objc"],
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration("--j2objc_translation_flags=-DTestJ2ObjcFlag");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    @SuppressWarnings("unchecked")
    List<String> flags =
        (List<String>) getMyInfoFromTarget(starlarkTarget).getValue("j2objc_flags");
    assertThat(flags).contains("-DTestJ2ObjcFlag");
    assertThat(flags).doesNotContain("-unspecifiedFlag");
  }

  @Test
  public void testStarlarkCanAccessApplePlatformNames() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            platform = ctx.fragments.apple.single_arch_platform
            return MyInfo(
                name = platform.name_in_plist,
            )

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["apple"],
        )
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--ios_multi_cpus=x86_64", "--apple_platform_type=ios");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    Object name = getMyInfoFromTarget(starlarkTarget).getValue("name");
    assertThat(name).isEqualTo("iPhoneSimulator");
  }

  @Test
  public void testStarlarkCanAccessAppleToolchain() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            apple_toolchain = apple_common.apple_toolchain()
            sdk_dir = apple_toolchain.sdk_dir()
            platform_developer_framework_dir = \\
                apple_toolchain.platform_developer_framework_dir(ctx.fragments.apple)
            return MyInfo(
                platform_developer_framework_dir = platform_developer_framework_dir,
                sdk_dir = sdk_dir,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration("--apple_platform_type=ios", "--ios_multi_cpus=x86_64");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    String platformDevFrameworksDir = (String) myInfo.getValue("platform_developer_framework_dir");
    String sdkDir = (String) myInfo.getValue("sdk_dir");

    assertThat(platformDevFrameworksDir)
        .isEqualTo(
            "__BAZEL_XCODE_DEVELOPER_DIR__"
                + "/Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks");
    assertThat(sdkDir).isEqualTo("__BAZEL_XCODE_SDKROOT__");
  }

  @Test
  public void testStarlarkCanAccessSdkAndMinimumOs() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
load("//myinfo:myinfo.bzl", "MyInfo")

def swift_binary_impl(ctx):
    xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
    ios_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.ios_device)
    watchos_sdk_version = xcode_config.sdk_version_for_platform(
        apple_common.platform.watchos_device)
    tvos_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.tvos_device)
    macos_sdk_version = xcode_config.sdk_version_for_platform(apple_common.platform.macos)
    ios_minimum_os = xcode_config.minimum_os_for_platform_type(apple_common.platform_type.ios)
    watchos_minimum_os = xcode_config.minimum_os_for_platform_type(
        apple_common.platform_type.watchos)
    tvos_minimum_os = xcode_config.minimum_os_for_platform_type(apple_common.platform_type.tvos)
    visionos_minimum_os = xcode_config.minimum_os_for_platform_type(
        apple_common.platform_type.visionos)
    return MyInfo(
        ios_sdk_version = str(ios_sdk_version),
        watchos_sdk_version = str(watchos_sdk_version),
        tvos_sdk_version = str(tvos_sdk_version),
        macos_sdk_version = str(macos_sdk_version),
        ios_minimum_os = str(ios_minimum_os),
        watchos_minimum_os = str(watchos_minimum_os),
        tvos_minimum_os = str(tvos_minimum_os),
        visionos_minimum_os = str(visionos_minimum_os),
    )

swift_binary = rule(
    implementation = swift_binary_impl,
    fragments = ["apple"],
    attrs = {"_xcode_config": attr.label(default = configuration_field(
        fragment = "apple",
        name = "xcode_config_label",
    ))},
)
""");

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration(
        "--ios_sdk_version=1.1",
        "--ios_minimum_os=1.0",
        "--watchos_sdk_version=2.1",
        "--watchos_minimum_os=2.0",
        "--tvos_sdk_version=3.1",
        "--tvos_minimum_os=3.0",
        "--macos_sdk_version=4.1",
        "--minimum_os_version=5.1");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1");
    assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.0");
    assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1");
    assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.0");
    assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1");
    assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.0");
    assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1");
    assertThat(myInfo.getValue("visionos_minimum_os")).isEqualTo("5.1");

    useConfiguration(
        "--ios_sdk_version=1.1",
        "--watchos_sdk_version=2.1",
        "--tvos_sdk_version=3.1",
        "--macos_sdk_version=4.1");
    starlarkTarget = getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    myInfo = getMyInfoFromTarget(starlarkTarget);

    assertThat(myInfo.getValue("ios_sdk_version")).isEqualTo("1.1");
    assertThat(myInfo.getValue("ios_minimum_os")).isEqualTo("1.1");
    assertThat(myInfo.getValue("watchos_sdk_version")).isEqualTo("2.1");
    assertThat(myInfo.getValue("watchos_minimum_os")).isEqualTo("2.1");
    assertThat(myInfo.getValue("tvos_sdk_version")).isEqualTo("3.1");
    assertThat(myInfo.getValue("tvos_minimum_os")).isEqualTo("3.1");
    assertThat(myInfo.getValue("macos_sdk_version")).isEqualTo("4.1");
  }

  @Test
  public void testStarlarkCanAccessObjcConfiguration() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            compilation_mode_copts = ctx.fragments.objc.copts_for_current_compilation_mode
            ios_simulator_device = ctx.fragments.objc.ios_simulator_device
            ios_simulator_version = ctx.fragments.objc.ios_simulator_version
            signing_certificate_name = ctx.fragments.objc.signing_certificate_name
            return MyInfo(
                compilation_mode_copts = compilation_mode_copts,
                ios_simulator_device = ios_simulator_device,
                ios_simulator_version = str(ios_simulator_version),
                signing_certificate_name = signing_certificate_name,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("test_starlark/objc_starlark/a.m");
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration(
        "--compilation_mode=opt",
        "--ios_simulator_device='iPhone 6'",
        "--ios_simulator_version=8.4",
        "--ios_signing_cert_name='Apple Developer'");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/objc_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    @SuppressWarnings("unchecked")
    List<String> compilationModeCopts = (List<String>) myInfo.getValue("compilation_mode_copts");
    Object iosSimulatorDevice = myInfo.getValue("ios_simulator_device");
    Object iosSimulatorVersion = myInfo.getValue("ios_simulator_version");
    Object signingCertificateName = myInfo.getValue("signing_certificate_name");

    assertThat(compilationModeCopts).containsExactlyElementsIn(ObjcConfiguration.OPT_COPTS);
    assertThat(iosSimulatorDevice).isEqualTo("'iPhone 6'");
    assertThat(iosSimulatorVersion).isEqualTo("8.4");
    assertThat(signingCertificateName).isEqualTo("'Apple Developer'");
  }

  @Test
  public void testStarlarkCanAccessObjcConfigurationForDevice() throws Exception {
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            compilation_mode_copts = ctx.fragments.objc.copts_for_current_compilation_mode
            ios_device = ctx.fragments.objc.ios_device
            signing_certificate_name = ctx.fragments.objc.signing_certificate_name
            return MyInfo(
                compilation_mode_copts = compilation_mode_copts,
                ios_device = ios_device,
                signing_certificate_name = signing_certificate_name,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("examples/objc_starlark/a.m");
    scratch.file(
        "examples/objc_starlark/BUILD",
        """
        load("//examples/rule:objc_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
        )
        """);

    useConfiguration(
        "--compilation_mode=opt",
        "--ios_multi_cpus=arm64",
        "--ios_device='11111111-1111-1111-1111-11111111111'",
        "--ios_signing_cert_name='Apple Developer'");
    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/objc_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    @SuppressWarnings("unchecked")
    List<String> compilationModeCopts = (List<String>) myInfo.getValue("compilation_mode_copts");
    Object iosDevice = myInfo.getValue("ios_device");
    Object signingCertificateName = myInfo.getValue("signing_certificate_name");

    assertThat(compilationModeCopts).containsExactlyElementsIn(ObjcConfiguration.OPT_COPTS);
    assertThat(iosDevice).isEqualTo("'11111111-1111-1111-1111-11111111111'");
    assertThat(signingCertificateName).isEqualTo("'Apple Developer'");
  }

  @Test
  public void testSigningCertificateNameCanReturnNone() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def my_rule_impl(ctx):
            signing_certificate_name = ctx.fragments.objc.signing_certificate_name
            return MyInfo(
                signing_certificate_name = str(signing_certificate_name),
            )

        my_rule = rule(
            implementation = my_rule_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("test_starlark/objc_starlark/a.m");
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/objc_starlark:my_target");

    Object signingCertificateName =
        getMyInfoFromTarget(starlarkTarget).getValue("signing_certificate_name");
    assertThat(signingCertificateName).isEqualTo("None");
  }

  @Test
  public void testUsesDebugEntitlementsIsTrueIfCompilationModeIsNotOpt() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("test_starlark/objc_starlark/a.m");
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--compilation_mode=dbg");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isTrue();
  }

  @Test
  public void testUsesDebugEntitlementsIsFalseIfFlagIsExplicitlyFalse() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("test_starlark/objc_starlark/a.m");
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--compilation_mode=dbg", "--nodevice_debug_entitlements");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isFalse();
  }

  @Test
  public void testUsesDebugEntitlementsIsFalseIfCompilationModeIsOpt() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def test_rule_impl(ctx):
            uses_device_debug_entitlements = ctx.fragments.objc.uses_device_debug_entitlements
            return MyInfo(
                uses_device_debug_entitlements = uses_device_debug_entitlements,
            )

        test_rule = rule(
            implementation = test_rule_impl,
            fragments = ["objc"],
        )
        """);

    scratch.file("test_starlark/objc_starlark/a.m");
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--compilation_mode=opt");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/objc_starlark:my_target");

    boolean usesDeviceDebugEntitlements =
        (boolean) getMyInfoFromTarget(starlarkTarget).getValue("uses_device_debug_entitlements");
    assertThat(usesDeviceDebugEntitlements).isFalse();
  }

  private ConfiguredTarget createObjcProviderStarlarkTarget(String... implLines) throws Exception {
    String[] impl =
        ObjectArrays.concat(
            ObjectArrays.concat("def swift_binary_impl(ctx):", implLines),
            new String[] {
              "swift_binary = rule(",
              "implementation = swift_binary_impl,",
              "attrs = {",
              "   'deps': attr.label_list(",
              "allow_files = False, mandatory = False, providers = [[apple_common.Objc]])",
              "})"
            },
            String.class);

    scratch.file("test_starlark/rule/BUILD");
    scratch.file("test_starlark/rule/objc_rules.bzl", impl);
    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )
        """);

    return getConfiguredTarget("//test_starlark/objc_starlark:my_target");
  }

  @Test
  public void testStarlarkCanCreateObjcProviderFromScratch() throws Exception {
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   file = ctx.actions.declare_file('foo.m')",
            "   ctx.actions.run_shell(outputs=[file], command='echo')",
            "   created_provider = apple_common.new_objc_provider(source=depset([file]))",
            "   return [created_provider]");

    StarlarkInfo dependerProvider = getObjcInfo(starlarkTarget);
    ImmutableList<Artifact> sources = getSource(dependerProvider);
    assertThat(ActionsTestUtil.baseArtifactNames(sources)).containsExactly("foo.m");
  }

  @Test
  public void testStarlarkCanCreateObjcProviderWithStrictDeps() throws Exception {
    ConfiguredTarget starlarkTarget =
        createObjcProviderStarlarkTarget(
            "   strict_includes = depset(['path'])",
            "   created_provider = apple_common.new_objc_provider(strict_include=strict_includes)",
            "   return [created_provider, CcInfo()]");

    StarlarkInfo starlarkProvider = getObjcInfo(starlarkTarget);
    assertThat(getStrictInclude(starlarkProvider)).containsExactly("path");

    scratch.file(
        "test_starlark/objc_starlark2/BUILD",
        """
        objc_library(
            name = "direct_dep",
            deps = ["//test_starlark/objc_starlark:my_target"],
        )
        """);

    StarlarkInfo starlarkProviderDirectDepender =
        getObjcInfo(getConfiguredTarget("//test_starlark/objc_starlark2:direct_dep"));
    assertThat(getStrictInclude(starlarkProviderDirectDepender)).isEmpty();
  }

  @Test
  public void testStarlarkCanCreateObjcProviderFromObjcProvider() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/objc_rules.bzl",
        """
        def library_impl(ctx):
            lib = ctx.label.name + ".a"
            file = ctx.actions.declare_file(lib)
            ctx.actions.run_shell(outputs = [file], command = "echo")
            return [apple_common.new_objc_provider(j2objc_library = depset([file]))]

        library = rule(implementation = library_impl)

        def binary_impl(ctx):
            dep = ctx.attr.deps[0]
            lib = ctx.label.name + ".a"
            file = ctx.actions.declare_file(lib)
            ctx.actions.run_shell(outputs = [file], command = "echo")
            created_provider = apple_common.new_objc_provider(
                providers = [dep[apple_common.Objc]],
                j2objc_library = depset([file]),
            )
            return [created_provider]

        binary = rule(
            implementation = binary_impl,
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[apple_common.Objc]],
                ),
            },
        )
        """);

    scratch.file(
        "test_starlark/objc_starlark/BUILD",
        """
        load("//test_starlark/rule:objc_rules.bzl", "binary", "library")

        package(default_visibility = ["//visibility:public"])

        binary(
            name = "bin",
            deps = [":lib"],
        )

        library(
            name = "lib",
        )
        """);

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//test_starlark/objc_starlark:bin");

    StarlarkInfo dependerProvider = getObjcInfo(starlarkTarget);
    ImmutableList<Artifact> libraries =
        Depset.cast(
                dependerProvider.getValue("j2objc_library"),
                Artifact.class,
                "dependerProvider value j2objc_library")
            .toList();

    assertThat(ActionsTestUtil.baseArtifactNames(libraries)).containsExactly("lib.a", "bin.a");
  }

  @Test
  public void testStarlarkErrorOnBadObjcProviderInputKey() throws Exception {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                createObjcProviderStarlarkTarget(
                    "   created_provider = apple_common.new_objc_provider(foo=depset(['bar']))",
                    "   return created_provider"));
    assertThat(e).hasMessageThat().contains("got unexpected keyword argument: foo");
  }

  @Test
  public void testEmptyObjcProviderKeysArePresent() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def swift_binary_impl(ctx):
            objc_provider = ctx.attr.deps[0][apple_common.Objc]
            return MyInfo(
                empty_value = objc_provider.j2objc_library,
            )

        swift_binary = rule(
            implementation = swift_binary_impl,
            fragments = ["apple"],
            attrs = {
                "deps": attr.label_list(
                    allow_files = False,
                    mandatory = False,
                    providers = [[apple_common.Objc]],
                ),
            },
        )
        """);

    scratch.file("test_starlark/apple_starlark/a.m");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "swift_binary")

        package(default_visibility = ["//visibility:public"])

        swift_binary(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
        )
        """);
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    NestedSet<Artifact> emptyValue =
        Depset.cast(
            getMyInfoFromTarget(starlarkTarget).getValue("empty_value"),
            Artifact.class,
            "provider \"empty_value\"'s j2objc_library");
    assertThat(emptyValue.toList()).isEmpty();
  }

  @Test
  public void testStarlarkCanAccessAndUseApplePlatformTypes() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            apple = ctx.fragments.apple
            ios_platform = apple.multi_arch_platform(apple_common.platform_type.ios)
            watchos_platform = apple.multi_arch_platform(apple_common.platform_type.watchos)
            tvos_platform = apple.multi_arch_platform(apple_common.platform_type.tvos)
            return MyInfo(
                ios_platform = str(ios_platform),
                watchos_platform = str(watchos_platform),
                tvos_platform = str(tvos_platform),
            )

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["apple"],
        )
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--ios_multi_cpus=arm64,armv7", "--watchos_cpus=armv7k", "--tvos_cpus=arm64");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StructImpl myInfo = getMyInfoFromTarget(starlarkTarget);

    Object iosPlatform = myInfo.getValue("ios_platform");
    Object watchosPlatform = myInfo.getValue("watchos_platform");
    Object tvosPlatform = myInfo.getValue("tvos_platform");

    assertThat(iosPlatform).isEqualTo("ios_device");
    assertThat(watchosPlatform).isEqualTo("watchos_device");
    assertThat(tvosPlatform).isEqualTo("tvos_device");
  }

  @Test
  public void testPlatformIsDeviceReturnsTrueForDevicePlatforms() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            apple = ctx.fragments.apple
            platform = apple.multi_arch_platform(apple_common.platform_type.ios)
            return MyInfo(
                is_device = platform.is_device,
            )

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["apple"],
        )
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    useConfiguration("--ios_multi_cpus=arm64,armv7");
    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    Boolean isDevice = (Boolean) getMyInfoFromTarget(starlarkTarget).getValue("is_device");
    assertThat(isDevice).isTrue();
  }

  @Test
  public void testPlatformIsDeviceReturnsFalseForSimulatorPlatforms() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            apple = ctx.fragments.apple
            platform = apple.multi_arch_platform(apple_common.platform_type.ios)
            return MyInfo(
                is_device = platform.is_device,
            )

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["apple"],
        )
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    Boolean isDevice = (Boolean) getMyInfoFromTarget(starlarkTarget).getValue("is_device");
    assertThat(isDevice).isFalse();
  }

  @Test
  public void testStarlarkWithRunMemleaksEnabled() throws Exception {
    useConfiguration("--ios_memleaks");
    checkStarlarkRunMemleaksWithExpectedValue(true);
  }

  @Test
  public void testStarlarkWithRunMemleaksDisabled() throws Exception {
    checkStarlarkRunMemleaksWithExpectedValue(false);
  }

  @Test
  public void testDottedVersion() throws Exception {
    scratch.file("test_starlark/rule/BUILD", "exports_files(['test_artifact'])");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            version = apple_common.dotted_version("5.4")
            return MyInfo(
                version = version,
            )

        test_rule = rule(implementation = _test_rule_impl)
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    DottedVersion version = (DottedVersion) getMyInfoFromTarget(starlarkTarget).getValue("version");
    assertThat(version).isEqualTo(DottedVersion.fromString("5.4"));
  }

  @Test
  public void testDottedVersion_invalid() throws Exception {
    scratch.file("test_starlark/rule/BUILD", "exports_files(['test_artifact'])");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            version = apple_common.dotted_version("hello")
            return MyInfo(
                version = version,
            )

        test_rule = rule(implementation = _test_rule_impl)
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    AssertionError e =
        assertThrows(
            AssertionError.class,
            () -> getConfiguredTarget("//test_starlark/apple_starlark:my_target"));
    assertThat(e)
        .hasMessageThat()
        .contains("Dotted version components must all start with the form");
  }

  /**
   * This test verifies that its possible to use the Starlark constructor of ObjcProvider as a
   * provider key to obtain the provider. This test only needs to exist as long as there are two
   * methods of retrieving ObjcProvider (which is true for legacy reasons). This is the 'new' method
   * of retrieving ObjcProvider.
   */
  @Test
  public void testObjcProviderStarlarkConstructor() throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        def my_rule_impl(ctx):
            dep = ctx.attr.deps[0]
            objc_provider = dep[apple_common.Objc]
            return objc_provider

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {
                "deps": attr.label_list(allow_files = False, mandatory = False),
            },
        )
        """);
    scratch.file("test_starlark/apple_starlark/a.cc");
    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
            deps = [":lib"],
        )

        objc_library(
            name = "lib",
            srcs = ["a.m"],
            hdrs = ["a.h"],
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");
    StarlarkInfo dependerProvider = getObjcInfo(starlarkTarget);
    assertThat(dependerProvider).isNotNull();
  }

  private void checkStarlarkRunMemleaksWithExpectedValue(boolean expectedValue) throws Exception {
    scratch.file("test_starlark/rule/BUILD");
    scratch.file(
        "test_starlark/rule/apple_rules.bzl",
        """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _test_rule_impl(ctx):
            return MyInfo(run_memleaks = ctx.fragments.objc.run_memleaks)

        test_rule = rule(
            implementation = _test_rule_impl,
            fragments = ["objc"],
            attrs = {},
        )
        """);

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        load("//test_starlark/rule:apple_rules.bzl", "test_rule")

        package(default_visibility = ["//visibility:public"])

        test_rule(
            name = "my_target",
        )
        """);

    ConfiguredTarget starlarkTarget =
        getConfiguredTarget("//test_starlark/apple_starlark:my_target");

    boolean runMemleaks = (boolean) getMyInfoFromTarget(starlarkTarget).getValue("run_memleaks");
    assertThat(runMemleaks).isEqualTo(expectedValue);
  }

  @Test
  public void testDisallowSDKFrameworkAttribute() throws Exception {
    useConfiguration("--incompatible_disallow_sdk_frameworks_attributes");

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        objc_library(
            name = "lib",
            srcs = ["a.m"],
            sdk_frameworks = [
                "Accelerate",
                "GLKit",
            ],
        )
        """);
    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//test_starlark/apple_starlark:lib"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR /workspace/test_starlark/apple_starlark/BUILD:1:13: "
                + "in objc_library rule //test_starlark/apple_starlark:lib:");

    assertContainsEvent(
        "sdk_frameworks attribute is disallowed. Use explicit dependencies instead.");
  }

  @Test
  public void testDisallowWeakSDKFrameworksAttribute() throws Exception {
    useConfiguration("--incompatible_disallow_sdk_frameworks_attributes");

    scratch.file(
        "test_starlark/apple_starlark/BUILD",
        """
        objc_library(
            name = "lib",
            srcs = ["a.m"],
            weak_sdk_frameworks = ["XCTest"],
        )
        """);
    AssertionError e =
        assertThrows(
            AssertionError.class, () -> getConfiguredTarget("//test_starlark/apple_starlark:lib"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "ERROR /workspace/test_starlark/apple_starlark/BUILD:1:13: "
                + "in objc_library rule //test_starlark/apple_starlark:lib:");

    assertContainsEvent(
        "weak_sdk_frameworks attribute is disallowed. Use explicit dependencies instead.");
  }
}
