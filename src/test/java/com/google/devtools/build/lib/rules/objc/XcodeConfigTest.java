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
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Starlark;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the {@code xcode_config} rule. */
@RunWith(JUnit4.class)
public class XcodeConfigTest extends BuildViewTestCase {
  private static final Provider.Key XCODE_VERSION_INFO_PROVIDER_KEY =
      new StarlarkProvider.Key(
          keyForBuiltins(Label.parseCanonicalUnchecked("@_builtins//:common/xcode/providers.bzl")),
          "XcodeVersionInfo");

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase();

  @Test
  public void testEmptyConfig_noVersionFlag() throws Exception {
    scratch.file("xcode/BUILD", "xcode_config(name = 'foo',)");
    useConfiguration("--xcode_version_config=//xcode:foo");

    assertIosSdkVersion(AppleCommandLineOptions.DEFAULT_IOS_SDK_VERSION);
  }

  @Test
  public void testDefaultVersion() throws Exception {
    BuildFileBuilder fileBuilder = new BuildFileBuilder();
    fileBuilder
        .addExplicitVersion("version512", "5.1.2", true)
        .addExplicitVersion("version84", "8.4", false)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testMutualAndExplicitXcodesThrows() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            local_versions = ":local",
            remote_versions = ":remote",
            versions = [
                ":version512",
                ":version84",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("'versions' may not be set if '[local,remote]_versions' is set");
  }

  @Test
  public void testMutualAndDefaultThrows() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("'default' may not be set if '[local,remote]_versions' is set.");
  }

  @Test
  public void testNoLocalXcodesThrows() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("if 'remote_versions' are set, you must also set 'local_versions'");
  }

  @Test
  public void testAcceptFlagForMutuallyAvailable() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=8.4", "--xcode_version_config=//xcode:foo");
    assertXcodeVersion("8.4");
    assertAvailability("both");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    assertNoEvents();
  }

  @Test
  public void testPreferFlagOverMutuallyAvailable() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=5.1.2", "--xcode_version_config=//xcode:foo");
    assertXcodeVersion("5.1.2");
    assertAvailability("remote");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN,
            ExecutionRequirements.NO_LOCAL,
            ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "--xcode_version=5.1.2 specified, but it is not available locally. Your build"
            + " will fail if any actions require a local Xcode.");
  }

  @Test
  public void testPreferMutual_choosesLocalDefaultOverNewest() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version512", "5.1.2", true)
        .addLocalVersion("version84", "8.4", false)
        .write(scratch, "xcode/BUILD");

    useConfiguration(
        "--experimental_prefer_mutual_xcode=true", "--xcode_version_config=//xcode:foo");
    assertXcodeVersion("5.1.2");
    assertAvailability("both");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testWarnWithExplicitLocalOnlyVersion() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=8.4", "--xcode_version_config=//xcode:foo");
    assertXcodeVersion("8.4");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN,
            ExecutionRequirements.NO_REMOTE,
            ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "--xcode_version=8.4 specified, but it is not available remotely. Actions"
            + " requiring Xcode will be run locally, which could make your build"
            + " slower.");
  }

  @Test
  public void testPreferLocalDefaultIfNoMutualNoFlagDifferentMainVersion() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");
    assertXcodeVersion("8.4");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN,
            ExecutionRequirements.NO_REMOTE,
            ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "Using a local Xcode version, '8.4', since there are no"
            + " remotely available Xcodes on this machine. Consider downloading one of the"
            + " remotely available Xcode versions (5.1.2)");
  }

  @Test
  public void testPreferLocalDefaultIfNoMutualNoFlagDifferentBuildAlias() throws Exception {
    // Version 10.0 of different builds are not matched
    new BuildFileBuilder()
        .addRemoteVersion("version10", "10.0", true, "10.0.0.101ff", "10.0")
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0.0.10C504", "10.0")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");
    assertXcodeVersion("10.0.0.10C504");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN,
            ExecutionRequirements.NO_REMOTE,
            ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "Using a local Xcode version, '10.0.0.10C504', since there are no"
            + " remotely available Xcodes on this machine. Consider downloading one of the"
            + " remotely available Xcode versions (10.0)");
  }

  @Test
  public void testPreferLocalDefaultIfNoMutualNoFlagDifferentFullVersion() throws Exception {
    // Version 10.0 of different builds are not matched
    new BuildFileBuilder()
        .addRemoteVersion("version10", "10.0.0.101ff", true, "10.0", "10.0.0.101ff")
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0.0.10C504", "10.0")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");
    assertXcodeVersion("10.0.0.10C504");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN,
            ExecutionRequirements.NO_REMOTE,
            ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "Using a local Xcode version, '10.0.0.10C504', since there are no"
            + " remotely available Xcodes on this machine. Consider downloading one of the"
            + " remotely available Xcode versions (10.0.0.101ff)");
  }

  @Test
  public void testChooseNewestMutualXcode() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version92", "9.2", true)
        .addRemoteVersion("version10", "10", false, "10.0.0.10C504")
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version9", "9", true)
        .addLocalVersion("version84", "8.4", false)
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", false, "10.0")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");
    assertXcodeVersion("10");
    assertAvailability("both");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    assertNoEvents();
  }

  @Test
  public void testPreferMutualXcodeFalseOverridesMutual() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version10", "10", true, "10.0.0.10C504")
        .addLocalVersion("version84", "8.4", true)
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", false, "10.0")
        .write(scratch, "xcode/BUILD");

    useConfiguration(
        "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false");
    assertXcodeVersion("8.4");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testLocalDefaultCanBeMutuallyAvailable() throws Exception {
    // Passing "--experimental_prefer_mutual_xcode=false" allows toggling between Xcode versions
    // using xcode-select. This test ensures that if the version from xcode-select is available
    // remotely, both local and remote execution are enabled.
    new BuildFileBuilder()
        .addRemoteVersion("version10", "10", true, "10.0.0.10C504")
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10.0")
        .write(scratch, "xcode/BUILD");

    useConfiguration(
        "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false");
    assertXcodeVersion("10");
    assertAvailability("both");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    assertNoEvents();
  }

  @Test
  public void testPreferLocalDefaultOverDifferentBuild() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version10", "10", true, "10.0.0.10C1ff")
        .addLocalVersion("version10.0.0.10C504", "10.0.0.10C504", true, "10")
        .write(scratch, "xcode/BUILD");

    useConfiguration(
        "--xcode_version_config=//xcode:foo", "--experimental_prefer_mutual_xcode=false");
    assertXcodeVersion("10.0.0.10C504");
    assertAvailability("local");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    assertContainsEvent(
        "Using a local Xcode version, '10.0.0.10C504', since there are no"
            + " remotely available Xcodes on this machine. Consider downloading one of the"
            + " remotely available Xcode versions (10)");
  }

  @Test
  public void testInvalidXcodeFromMutualThrows() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true)
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=6");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "--xcode_version=6 specified, but '6' is not an available Xcode version."
            + " Locally available versions: [8.4]. Remotely available versions:"
            + " [5.1.2, 8.4].");
  }

  @Test
  public void xcodeVersionConfigConstructor() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        result = provider()

        def _impl(ctx):
            return [result(xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "1.1",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "1.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            ))]

        my_rule = rule(_impl, attrs = {"dep": attr.label()})
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        """);
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:test");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//foo:extension.bzl")), "result"));
    StructImpl actual = info.getValue("xcode_version", StructImpl.class);
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.IOS_DEVICE)
                .toString())
        .isEqualTo("1.1");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.IOS_SIMULATOR)
                .toString())
        .isEqualTo("1.1");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.IOS)
                .toString())
        .isEqualTo("1.2");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.CATALYST)
                .toString())
        .isEqualTo("1.2");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.WATCHOS_DEVICE)
                .toString())
        .isEqualTo("1.3");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.WATCHOS_SIMULATOR)
                .toString())
        .isEqualTo("1.3");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.WATCHOS)
                .toString())
        .isEqualTo("1.4");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.TVOS_DEVICE)
                .toString())
        .isEqualTo("1.5");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.TVOS_SIMULATOR)
                .toString())
        .isEqualTo("1.5");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.TVOS)
                .toString())
        .isEqualTo("1.6");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.MACOS).toString())
        .isEqualTo("1.7");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.CATALYST)
                .toString())
        .isEqualTo("1.7");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.MACOS)
                .toString())
        .isEqualTo("1.8");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.VISIONOS_DEVICE)
                .toString())
        .isEqualTo("1.9");
    assertThat(
            callProviderMethod(actual, "sdk_version_for_platform", ApplePlatform.VISIONOS_SIMULATOR)
                .toString())
        .isEqualTo("1.9");
    assertThat(
            callProviderMethod(
                    actual, "minimum_os_for_platform_type", ApplePlatform.PlatformType.VISIONOS)
                .toString())
        .isEqualTo("1.10");
    assertThat(callProviderMethod(actual, "xcode_version").toString()).isEqualTo("1.11");
    assertThat(callProviderMethod(actual, "availability")).isEqualTo("unknown");
    assertThat(callProviderMethod(actual, "execution_info"))
        .isEqualTo(ImmutableMap.of("requires-darwin", "", "supports-xcode-requirements-set", ""));
  }

  @Test
  public void xcodeVersionConfig_throwsOnBadInput() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        result = provider()

        def _impl(ctx):
            return [result(xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "not a valid dotted version",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "1.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            ))]

        my_rule = rule(_impl, attrs = {"dep": attr.label()})
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        """);
    assertNoEvents();
    assertThrows(AssertionError.class, () -> getConfiguredTarget("//foo:test"));
    assertContainsEvent("Dotted version components must all start with the form");
    assertContainsEvent("got 'not a valid dotted version'");
  }

  @Test
  public void xcodeVersionConfig_exposesExpectedAttributes() throws Exception {
    scratch.file(
        "foo/extension.bzl",
        """
        result = provider()

        def _impl(ctx):
            xcode_version = apple_common.XcodeVersionConfig(
                ios_sdk_version = "1.1",
                ios_minimum_os_version = "1.2",
                watchos_sdk_version = "1.3",
                watchos_minimum_os_version = "2.4",
                tvos_sdk_version = "1.5",
                tvos_minimum_os_version = "1.6",
                macos_sdk_version = "1.7",
                macos_minimum_os_version = "1.8",
                visionos_sdk_version = "1.9",
                visionos_minimum_os_version = "1.10",
                xcode_version = "1.11",
                availability = "UNKNOWN",
                xcode_version_flag = "0.0",
                include_xcode_execution_info = False,
            )
            return [result(
                xcode_version = xcode_version.xcode_version(),
                min_os = xcode_version.minimum_os_for_platform_type(
                    ctx.fragments.apple.single_arch_platform.platform_type,
                ),
            )]

        my_rule = rule(
            _impl,
            attrs = {"dep": attr.label()},
            fragments = ["apple"],
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":extension.bzl", "my_rule")

        my_rule(name = "test")
        """);
    assertNoEvents();
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:test");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//foo:extension.bzl")), "result"));
    assertThat(info.getValue("xcode_version").toString()).isEqualTo("1.11");
    assertThat(info.getValue("min_os").toString()).isEqualTo("1.8");
  }

  @Test
  public void testConfigAlias_configSetting() throws Exception {
    scratch.file("starlark/BUILD");
    scratch.file(
        "starlark/version_retriever.bzl",
        """
        def _version_retriever_impl(ctx):
            xcode_properties = ctx.attr.dep[apple_common.XcodeProperties]
            version = xcode_properties.xcode_version
            return [config_common.FeatureFlagInfo(value = version)]

        version_retriever = rule(
            implementation = _version_retriever_impl,
            attrs = {"dep": attr.label()},
        )
        """);

    scratch.file(
        "xcode/BUILD",
        """
        load("//starlark:version_retriever.bzl", "version_retriever")

        version_retriever(
            name = "flag_propagator",
            dep = ":alias",
        )

        xcode_config(
            name = "config",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
                ":version12",
            ],
        )

        xcode_config_alias(
            name = "alias",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "six",
                "6",
            ],
            version = "6.4",
        )

        xcode_version(
            name = "version12",
            version = "12",
        )

        config_setting(
            name = "xcode_5_1_2",
            flag_values = {":flag_propagator": "5.1.2"},
        )

        config_setting(
            name = "xcode_6_4",
            flag_values = {":flag_propagator": "6.4"},
        )

        genrule(
            name = "gen",
            srcs = [],
            outs = ["out"],
            cmd = select({
                ":xcode_5_1_2": "5.1.2",
                ":xcode_6_4": "6.4",
                "//conditions:default": "none",
            }),
        )
        """);

    useConfiguration("--xcode_version_config=//xcode:config");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("5.1.2");

    useConfiguration("--xcode_version_config=//xcode:config", "--xcode_version=6.4");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("6.4");

    useConfiguration("--xcode_version_config=//xcode:config", "--xcode_version=6");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("6.4");

    useConfiguration("--xcode_version_config=//xcode:config", "--xcode_version=12");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("none");
  }

  @Test
  public void testDefaultVersion_configSetting() throws Exception {
    scratch.file("starlark/BUILD");
    scratch.file(
        "starlark/version_retriever.bzl",
        """
        def _version_retriever_impl(ctx):
            xcode_properties = ctx.attr.dep[apple_common.XcodeProperties]
            version = xcode_properties.xcode_version
            return [config_common.FeatureFlagInfo(value = version)]

        version_retriever = rule(
            implementation = _version_retriever_impl,
            attrs = {"dep": attr.label()},
        )
        """);

    scratch.file(
        "xcode/BUILD",
        """
        load("//starlark:version_retriever.bzl", "version_retriever")

        version_retriever(
            name = "flag_propagator",
            dep = ":alias",
        )

        xcode_config_alias(
            name = "alias",
        )

        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            version = "6.4",
        )

        config_setting(
            name = "xcode_5_1_2",
            flag_values = {":flag_propagator": "5.1.2"},
        )

        config_setting(
            name = "xcode_6_4",
            flag_values = {":flag_propagator": "6.4"},
        )

        genrule(
            name = "gen",
            srcs = [],
            outs = ["out"],
            cmd = select({
                ":xcode_5_1_2": "5.1.2",
                ":xcode_6_4": "6.4",
                "//conditions:default": "none",
            }),
        )
        """);

    useConfiguration("--xcode_version_config=//xcode:foo");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("5.1.2");

    useConfiguration("--xcode_version_config=//xcode:foo", "--xcode_version=6.4");
    assertThat(getMapper("//xcode:gen").get("cmd", Type.STRING)).isEqualTo("6.4");
  }

  @Test
  public void testValidVersion() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=5.1.2", "--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testValidAlias_dottedVersion() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "5")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=5", "--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testValidAlias_nonNumerical() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "valid_version")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=valid_version", "--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testInvalidXcodeSpecified() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true)
        .addExplicitVersion("version84", "8.4", false)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=6");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "--xcode_version=6 specified, but '6' is not an available Xcode version. "
            + "If you believe you have '6' installed");
  }

  @Test
  public void testRequiresDefault() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            versions = [":version512"],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("default version must be specified");
  }

  @Test
  public void testDuplicateAliases_definedVersion() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "5")
        .addExplicitVersion("version5", "5.0", false, "5")
        .write(scratch, "xcode/BUILD");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "'5' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)");
  }

  @Test
  public void testDuplicateAliases_withinAvailableXcodes() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true, "5")
        .addRemoteVersion("version5", "5.0", false, "5")
        .addLocalVersion("version5", "5.0", true, "5")
        .write(scratch, "xcode/BUILD");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "'5' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)");
  }

  @Test
  public void testVersionAliasedToItself() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "5.1.2")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testDuplicateVersionNumbers() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true)
        .addExplicitVersion("version5", "5.1.2", false, "5")
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version=5");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "'5.1.2' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)");
  }

  @Test
  public void testVersionConflictsWithAlias() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true)
        .addExplicitVersion("version5", "5.0", false, "5.1.2")
        .write(scratch, "xcode/BUILD");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "'5.1.2' is registered to multiple labels (@@//xcode:version512, @@//xcode:version5)");
  }

  @Test
  public void testDefaultIosSdkVersion() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43.0",
            version = "6.4",
        )
        """);
    useConfiguration("--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertIosSdkVersion("7.1");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));
  }

  @Test
  public void testDefaultSdkVersions() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "101",
            default_macos_sdk_version = "104",
            default_tvos_sdk_version = "103",
            default_watchos_sdk_version = "102",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43.0",
            version = "6.4",
        )
        """);
    useConfiguration("--xcode_version_config=//xcode:foo");

    assertXcodeVersion("5.1.2");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    ImmutableMap<ApplePlatform, String> platformToVersion =
        ImmutableMap.<ApplePlatform, String>builder()
            .put(ApplePlatform.IOS_SIMULATOR, "101")
            .put(ApplePlatform.WATCHOS_SIMULATOR, "102")
            .put(ApplePlatform.TVOS_SIMULATOR, "103")
            .put(ApplePlatform.MACOS, "104")
            .build();
    for (ApplePlatform platform : platformToVersion.keySet()) {
      DottedVersion version = DottedVersion.fromString(platformToVersion.get(platform));
      assertThat(getSdkVersionForPlatform(platform)).isEqualTo(version);
      assertThat(getMinimumOsVersionForPlatform(platform)).isEqualTo(version);
    }
  }

  @Test
  public void testDefaultSdkVersions_selectedXcode() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "43",
            default_macos_sdk_version = "46",
            default_tvos_sdk_version = "45",
            default_watchos_sdk_version = "44",
            version = "6.4",
        )
        """);
    useConfiguration("--xcode_version=6", "--xcode_version_config=//xcode:foo");

    assertXcodeVersion("6.4");
    assertAvailability("unknown");
    assertHasRequirements(
        ImmutableList.of(
            ExecutionRequirements.REQUIRES_DARWIN, ExecutionRequirements.REQUIREMENTS_SET));

    ImmutableMap<ApplePlatform, String> platformToVersion =
        ImmutableMap.<ApplePlatform, String>builder()
            .put(ApplePlatform.IOS_SIMULATOR, "43")
            .put(ApplePlatform.WATCHOS_SIMULATOR, "44")
            .put(ApplePlatform.TVOS_SIMULATOR, "45")
            .put(ApplePlatform.MACOS, "46")
            .build();
    for (ApplePlatform platform : platformToVersion.keySet()) {
      DottedVersion version = DottedVersion.fromString(platformToVersion.get(platform));
      assertThat(getSdkVersionForPlatform(platform)).isEqualTo(version);
      assertThat(getMinimumOsVersionForPlatform(platform)).isEqualTo(version);
    }
  }

  @Test
  public void testOverrideDefaultSdkVersions() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [
                ":version512",
                ":version64",
            ],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            default_ios_sdk_version = "7.1",
            version = "5.1.2",
        )

        xcode_version(
            name = "version64",
            aliases = [
                "6.0",
                "foo",
                "6",
            ],
            default_ios_sdk_version = "101",
            default_macos_sdk_version = "104",
            default_tvos_sdk_version = "103",
            default_watchos_sdk_version = "102",
            version = "6.4",
        )
        """);
    useConfiguration(
        "--xcode_version=6",
        "--xcode_version_config=//xcode:foo",
        "--ios_sdk_version=15.3",
        "--watchos_sdk_version=15.4",
        "--tvos_sdk_version=15.5",
        "--macos_sdk_version=15.6");

    assertXcodeVersion("6.4");
    assertAvailability("unknown");
    ImmutableMap<ApplePlatform, String> platformToVersion =
        ImmutableMap.<ApplePlatform, String>builder()
            .put(ApplePlatform.IOS_SIMULATOR, "15.3")
            .put(ApplePlatform.WATCHOS_SIMULATOR, "15.4")
            .put(ApplePlatform.TVOS_SIMULATOR, "15.5")
            .put(ApplePlatform.MACOS, "15.6")
            .build();
    for (ApplePlatform platform : platformToVersion.keySet()) {
      DottedVersion version = DottedVersion.fromString(platformToVersion.get(platform));
      assertThat(getSdkVersionForPlatform(platform)).isEqualTo(version);
      assertThat(getMinimumOsVersionForPlatform(platform)).isEqualTo(version);
    }
  }

  @Test
  public void testXcodeVersionFromStarlarkByAlias() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load("//x:r.bzl", "r")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            default = ":v",
            versions = [":v"],
        )

        xcode_version(
            name = "v",
            default_ios_sdk_version = "1.0",
            default_macos_sdk_version = "3.0",
            default_tvos_sdk_version = "2.0",
            default_watchos_sdk_version = "4.0",
            version = "0.0",
        )

        r(name = "r")
        """);
    scratch.file(
        "x/r.bzl",
        """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.ios)
            tvos = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.tvos)
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
                execution_info = conf.execution_info(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//x:a"))},
            fragments = ["apple"],
        )
        """);

    useConfiguration(
        "--xcode_version_config=//x:c", "--tvos_sdk_version=2.5", "--watchos_minimum_os=4.5");
    ConfiguredTarget r = getConfiguredTarget("//x:r");
    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//x:r.bzl")), "MyInfo");
    StructImpl info = (StructImpl) r.get(key);

    assertThat(info.getValue("xcode").toString()).isEqualTo("0.0");
    assertThat(info.getValue("ios_sdk").toString()).isEqualTo("1.0");
    assertThat(info.getValue("tvos_sdk").toString()).isEqualTo("2.5");
    assertThat(info.getValue("macos_min").toString()).isEqualTo("3.0");
    assertThat(info.getValue("watchos_min").toString()).isEqualTo("4.5");
    assertThat(info.getValue("availability").toString()).isEqualTo("unknown");
    assertThat((Map<?, ?>) info.getValue("execution_info"))
        .containsKey(ExecutionRequirements.REQUIRES_DARWIN);
    assertThat((Map<?, ?>) info.getValue("execution_info"))
        .containsKey(ExecutionRequirements.REQUIREMENTS_SET);
  }

  @Test
  public void testMutualXcodeFromStarlarkByAlias() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load("//x:r.bzl", "r")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [
                ":version512",
                ":version84",
            ],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )

        r(name = "r")
        """);
    scratch.file(
        "x/r.bzl",
        """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.ios)
            tvos = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.tvos)
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
                execution_info = conf.execution_info(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//x:a"))},
            fragments = ["apple"],
        )
        """);

    useConfiguration("--xcode_version_config=//x:c");
    ConfiguredTarget r = getConfiguredTarget("//x:r");
    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//x:r.bzl")), "MyInfo");
    StructImpl info = (StructImpl) r.get(key);
    assertThat((Map<?, ?>) info.getValue("execution_info"))
        .containsKey(ExecutionRequirements.REQUIRES_DARWIN);
    assertThat((Map<?, ?>) info.getValue("execution_info"))
        .containsKey(ExecutionRequirements.REQUIREMENTS_SET);
  }

  @Test
  public void testLocalXcodeFromStarlarkByAlias() throws Exception {
    scratch.file(
        "x/BUILD",
        """
        load("//x:r.bzl", "r")

        xcode_config_alias(name = "a")

        xcode_config(
            name = "c",
            local_versions = ":local",
            remote_versions = ":remote",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version84",
            version = "8.4",
        )

        available_xcodes(
            name = "remote",
            default = ":version512",
            versions = [":version512"],
        )

        available_xcodes(
            name = "local",
            default = ":version84",
            versions = [":version84"],
        )

        r(name = "r")
        """);
    scratch.file(
        "x/r.bzl",
        """
        MyInfo = provider()

        def _impl(ctx):
            conf = ctx.attr._xcode[apple_common.XcodeVersionConfig]
            ios = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.ios)
            tvos = ctx.fragments.apple.multi_arch_platform(apple_common.platform_type.tvos)
            return MyInfo(
                xcode = conf.xcode_version(),
                ios_sdk = conf.sdk_version_for_platform(ios),
                tvos_sdk = conf.sdk_version_for_platform(tvos),
                macos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.macos,
                ),
                watchos_min = conf.minimum_os_for_platform_type(
                    apple_common.platform_type.watchos,
                ),
                availability = conf.availability(),
            )

        r = rule(
            implementation = _impl,
            attrs = {"_xcode": attr.label(default = Label("//x:a"))},
            fragments = ["apple"],
        )
        """);

    useConfiguration("--xcode_version_config=//x:c");
    ConfiguredTarget r = getConfiguredTarget("//x:r");
    Provider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//x:r.bzl")), "MyInfo");
    StructImpl info = (StructImpl) r.get(key);

    assertThat(info.getValue("xcode").toString()).isEqualTo("8.4");
    assertThat(info.getValue("availability").toString()).isEqualTo("local");
  }

  @Test
  public void testDefaultWithoutVersion() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
                "5.1.2",
            ],
            version = "5.1.2",
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent(
        "default label '@@//xcode:version512' must be contained in versions attribute");
  }

  @Test
  public void testVersionDoesNotContainDefault() throws Exception {
    scratch.file(
        "xcode/BUILD",
        """
        xcode_config(
            name = "foo",
            default = ":version512",
            versions = [":version6"],
        )

        xcode_version(
            name = "version512",
            aliases = [
                "5",
                "5.1",
                "5.1.2",
            ],
            version = "5.1.2",
        )

        xcode_version(
            name = "version6",
            version = "6.0",
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("must be contained in versions attribute");
  }

  // Verifies that the --xcode_version_config configuration value can be accessed via the
  // configuration_field() Starlark method and used in a Starlark rule.
  @Test
  public void testConfigurationFieldForRule() throws Exception {
    scratch.file(
        "x/provider_grabber.bzl",
        """
        def _impl(ctx):
            conf = ctx.attr._xcode_dep[apple_common.XcodeVersionConfig]
            return [conf]

        provider_grabber = rule(
            implementation = _impl,
            attrs = {
                "_xcode_dep": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
            },
            fragments = ["apple"],
        )
        """);

    scratch.file(
        "x/BUILD",
        """
        load("//x:provider_grabber.bzl", "provider_grabber")

        xcode_config(
            name = "config1",
            default = ":version1",
            versions = [":version1"],
        )

        xcode_config(
            name = "config2",
            default = ":version2",
            versions = [":version2"],
        )

        xcode_version(
            name = "version1",
            version = "1.0",
        )

        xcode_version(
            name = "version2",
            version = "2.0",
        )

        provider_grabber(name = "provider_grabber")
        """);

    useConfiguration("--xcode_version_config=//x:config1");
    assertXcodeVersion("1.0", "//x:provider_grabber");

    useConfiguration("--xcode_version_config=//x:config2");
    assertXcodeVersion("2.0", "//x:provider_grabber");
  }

  // Verifies that the --xcode_version_config configuration value can be accessed via the
  // configuration_field() Starlark method and used in a Starlark aspect.
  @Test
  public void testConfigurationFieldForAspect() throws Exception {
    scratch.file(
        "x/provider_grabber.bzl",
        """
        def _aspect_impl(target, ctx):
            conf = ctx.attr._xcode_dep[apple_common.XcodeVersionConfig]
            return [conf]

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = {
                "_xcode_dep": attr.label(
                    default = configuration_field(
                        fragment = "apple",
                        name = "xcode_config_label",
                    ),
                ),
            },
            fragments = ["apple"],
        )

        def _rule_impl(ctx):
            conf = ctx.attr.dep[0][apple_common.XcodeVersionConfig]
            return [conf]

        provider_grabber = rule(
            implementation = _rule_impl,
            attrs = {"dep": attr.label_list(
                mandatory = True,
                allow_files = True,
                aspects = [MyAspect],
            )},
        )
        """);

    scratch.file(
        "x/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load("//x:provider_grabber.bzl", "provider_grabber")

        xcode_config(
            name = "config1",
            default = ":version1",
            versions = [":version1"],
        )

        xcode_config(
            name = "config2",
            default = ":version2",
            versions = [":version2"],
        )

        xcode_version(
            name = "version1",
            version = "1.0",
        )

        xcode_version(
            name = "version2",
            version = "2.0",
        )

        java_library(
            name = "fake_lib",
        )

        provider_grabber(
            name = "provider_grabber",
            dep = [":fake_lib"],
        )
        """);

    useConfiguration("--xcode_version_config=//x:config1");
    assertXcodeVersion("1.0", "//x:provider_grabber");

    useConfiguration("--xcode_version_config=//x:config2");
    assertXcodeVersion("2.0", "//x:provider_grabber");
  }

  @Test
  public void testExplicitXcodesModeNoFlag() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "5", "5.1")
        .addExplicitVersion("version64", "6.4", false, "6.0", "foo", "6")
        .write(scratch, "xcode/BUILD");
    getConfiguredTarget("//xcode:foo");
    assertXcodeVersion("5.1.2");
  }

  @Test
  public void testExplicitXcodesModeWithFlag() throws Exception {
    new BuildFileBuilder()
        .addExplicitVersion("version512", "5.1.2", true, "5", "5.1")
        .addExplicitVersion("version64", "6.4", false, "6.0", "foo", "6")
        .write(scratch, "xcode/BUILD");
    useConfiguration("--xcode_version=6.4");
    getConfiguredTarget("//xcode:foo");
    assertXcodeVersion("6.4");
  }

  @Test
  public void testAvailableXcodesModeNoFlag() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true, "5", "5.1")
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");

    useConfiguration("--xcode_version_config=//xcode:foo");
    getConfiguredTarget("//xcode:foo");
    assertXcodeVersion("8.4");
  }

  @Test
  public void testAvailableXcodeModesDifferentAlias() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version5", "5.1", true, "5")
        .addLocalVersion("version5.1.2", "5.1.2", true, "5")
        .write(scratch, "xcode/BUILD");
    useConfiguration("--xcode_version=5");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//xcode:foo");
    assertContainsEvent("Xcode version 5 was selected");
    assertContainsEvent("This corresponds to local Xcode version 5.1.2");
  }

  @Test
  public void testAvailableXcodeModesDifferentAliasFullySpecified() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version5", "5.1", true, "5")
        .addLocalVersion("version5.1.2", "5.1.2", true, "5")
        .write(scratch, "xcode/BUILD");
    useConfiguration("--xcode_version=5.1.2");
    getConfiguredTarget("//xcode:foo");
    assertXcodeVersion("5.1.2");
    assertAvailability("local");
  }

  @Test
  public void testAvailableXcodesModeWithFlag() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version512", "5.1.2", true, "5", "5.1")
        .addRemoteVersion("version84", "8.4", false)
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");
    useConfiguration("--xcode_version=5.1.2");
    getConfiguredTarget("//xcode:foo");
    assertXcodeVersion("5.1.2");
  }

  @Test
  public void testXcodeWithExtensionMatchingRemote() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version0", "0.0", true, "0.0-unstable")
        .addLocalVersion("version84", "8.4", true)
        .write(scratch, "xcode/BUILD");
    useConfiguration(
        "--xcode_version=0.0-unstable", "--experimental_include_xcode_execution_requirements=true");
    getConfiguredTarget("//xcode:foo");

    assertAvailability("remote");
    assertHasRequirementsWithValues(
        ImmutableMap.of(
            ExecutionRequirements.REQUIRES_XCODE + ":0.0", "",
            ExecutionRequirements.REQUIRES_XCODE_LABEL + ":unstable", ""));
  }

  @Test
  public void testXcodeVersionWithExtensionMatchingRemoteAndLocal() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version0.x", "0.0", true, "0.0-unstable")
        .addLocalVersion("version0", "0.0", true, "0.0", "0.0.1")
        .write(scratch, "xcode/BUILD");
    useConfiguration(
        "--xcode_version=0.0-unstable", "--experimental_include_xcode_execution_requirements=true");
    getConfiguredTarget("//xcode:foo");

    assertAvailability("remote");
    assertHasRequirementsWithValues(
        ImmutableMap.of(
            ExecutionRequirements.REQUIRES_XCODE + ":0.0", "",
            ExecutionRequirements.REQUIRES_XCODE_LABEL + ":unstable", ""));
  }

  @Test
  public void testXcodeVersionWithNoExtension() throws Exception {
    new BuildFileBuilder()
        .addRemoteVersion("version00-remote", "0.0", true, "0.0", "0.0-beta")
        .addLocalVersion("version00", "0.0", true, "0.0")
        .write(scratch, "xcode/BUILD");
    useConfiguration(
        "--xcode_version=0.0", "--experimental_include_xcode_execution_requirements=true");
    getConfiguredTarget("//xcode:foo");

    assertAvailability("both");
    assertHasRequirementsWithValues(
        ImmutableMap.of(ExecutionRequirements.REQUIRES_XCODE + ":0.0", ""));
    assertDoesNotHaveRequirements(
        ImmutableList.of(ExecutionRequirements.REQUIRES_XCODE_LABEL + ":"));
  }

  private DottedVersion getSdkVersionForPlatform(ApplePlatform platform) throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget("//xcode:foo");
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    return (DottedVersion) callProviderMethod(provider, "sdk_version_for_platform", platform);
  }

  private DottedVersion getMinimumOsVersionForPlatform(ApplePlatform platform) throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget("//xcode:foo");
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    return (DottedVersion)
        callProviderMethod(provider, "minimum_os_for_platform_type", platform.getType());
  }

  private void assertXcodeVersion(String version) throws Exception {
    assertXcodeVersion(version, "//xcode:foo");
  }

  private void assertXcodeVersion(String version, String providerTargetLabel) throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget(providerTargetLabel);
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    assertThat(callProviderMethod(provider, "xcode_version"))
        .isEqualTo(DottedVersion.fromString(version));
  }

  private void assertAvailability(String availability) throws Exception {
    assertAvailability(availability, "//xcode:foo");
  }

  private void assertAvailability(String availability, String providerTargetLabel)
      throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget(providerTargetLabel);
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    assertThat(callProviderMethod(provider, "availability")).isEqualTo(availability);
  }

  private void assertHasRequirements(List<String> executionRequirements) throws Exception {
    assertHasRequirements(executionRequirements, "//xcode:foo");
  }

  private void assertHasRequirements(List<String> executionRequirements, String providerTargetLabel)
      throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget(providerTargetLabel);
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    for (String requirement : executionRequirements) {
      assertThat(requirement).isIn(getExecutionInfo(provider).keySet());
    }
  }

  private void assertDoesNotHaveRequirements(List<String> executionRequirements) throws Exception {
    assertDoesNotHaveRequirements(executionRequirements, "//xcode:foo");
  }

  private void assertDoesNotHaveRequirements(
      List<String> executionRequirements, String providerTargetLabel) throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget(providerTargetLabel);
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    for (String requirement : executionRequirements) {
      assertThat(requirement).isNotIn(getExecutionInfo(provider));
    }
  }

  private void assertHasRequirementsWithValues(Map<String, String> executionRequirements)
      throws Exception {
    assertHasRequirementsWithValues(executionRequirements, "//xcode:foo");
  }

  private void assertHasRequirementsWithValues(
      Map<String, String> executionRequirements, String providerTargetLabel) throws Exception {
    ConfiguredTarget xcodeConfig = getConfiguredTarget(providerTargetLabel);
    StructImpl provider = (StructImpl) xcodeConfig.get(XCODE_VERSION_INFO_PROVIDER_KEY);
    for (Map.Entry<String, String> requirement : executionRequirements.entrySet()) {
      Dict<String, Object> actual = getExecutionInfo(provider);
      assertThat(requirement.getKey()).isIn(actual.keySet());
      assertThat(actual.getOrDefault(requirement.getKey(), "")).isEqualTo(requirement.getValue());
    }
  }

  private void assertIosSdkVersion(String version) throws Exception {
    assertThat(getSdkVersionForPlatform(ApplePlatform.IOS_SIMULATOR))
        .isEqualTo(DottedVersion.fromString(version));
  }

  private Object callProviderMethod(StructImpl provider, String methodName, Object... positional)
      throws Exception {
    return Starlark.call(
        ev.getStarlarkThread(),
        provider.getValue(methodName),
        ImmutableList.copyOf(positional),
        ImmutableMap.of());
  }

  private Dict<String, Object> getExecutionInfo(StructImpl provider) throws Exception {
    return Dict.cast(
        callProviderMethod(provider, "execution_info"),
        String.class,
        Object.class,
        "execution_info");
  }

  /** Returns a ConfiguredAttributeMapper bound to the given rule with the target configuration. */
  private ConfiguredAttributeMapper getMapper(String label) throws Exception {
    ConfiguredTargetAndData ctad = getConfiguredTargetAndData(label);
    return getMapperFromConfiguredTargetAndTarget(ctad);
  }
}
