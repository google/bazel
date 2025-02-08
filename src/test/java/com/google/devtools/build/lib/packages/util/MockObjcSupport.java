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

package com.google.devtools.build.lib.packages.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.io.IOException;
import java.util.stream.Stream;

/** Creates mock BUILD files required for the objc rules. */
public final class MockObjcSupport {

  private static final ImmutableList<String> OSX_ARCHS =
      ImmutableList.of(
          "x64_windows",
          "ios_x86_64",
          "ios_i386",
          "ios_armv7",
          "ios_arm64",
          "ios_arm64e",
          "darwin_x86_64",
          "watchos_i386",
          "watchos_x86_64",
          "watchos_armv7k",
          "watchos_arm64_32",
          "tvos_x86_64",
          "tvos_arm64");

  public static final String DEFAULT_OSX_CROSSTOOL_DIR = "tools/osx/crosstool";
  private static final String MOCK_OSX_TOOLCHAIN_CONFIG_PATH =
      "com/google/devtools/build/lib/packages/util/mock/osx_cc_toolchain_config.bzl";

  /** The label of the {@code xcode_config} target used in test to enumerate available xcodes. */
  public static final String XCODE_VERSION_CONFIG =
      TestConstants.TOOLS_REPOSITORY + "//tools/objc:host_xcodes";

  /** The build label for the mock OSX crosstool configuration. */
  public static final String DEFAULT_OSX_CROSSTOOL =
      "//" + DEFAULT_OSX_CROSSTOOL_DIR + ":crosstool";

  public static final String DEFAULT_XCODE_VERSION = "7.3.1";
  public static final String DEFAULT_IOS_SDK_VERSION = "8.4";

  public static final String APPLE_SIMULATOR_PLATFORM_PACKAGE =
      TestConstants.APPLE_PLATFORM_PACKAGE_ROOT
          + (TestConstants.PRODUCT_NAME.equals("bazel") ? "" : "/simulator");

  public static final String DARWIN_X86_64 =
      TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":darwin_x86_64";
  public static final String IOS_X86_64 = APPLE_SIMULATOR_PLATFORM_PACKAGE + ":ios_x86_64";
  public static final String IOS_ARM64 = TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":ios_arm64";

  public static final String IOS_ARM64E = TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":ios_arm64e";
  public static final String IOS_ARMV7 =
      TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":ios_armv7"; // legacy for testing
  public static final String IOS_I386 =
      APPLE_SIMULATOR_PLATFORM_PACKAGE + ":ios_i386"; // legacy for testing
  public static final String WATCHOS_ARMV7K =
      TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":watchos_armv7k";

  public static final String WATCHOS_ARM64_32 =
      TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":watchos_arm64_32";

  public static ImmutableList<String> requiredObjcPlatformFlags(String... args) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .addAll(requiredObjcPlatformFlagsNoXcodeConfig(args))
        .add("--xcode_version_config=" + MockObjcSupport.XCODE_VERSION_CONFIG)
        .build();
  }

  /** Returns the set of flags required to build objc libraries using the mock OSX crosstool. */
  public static ImmutableList<String> requiredObjcCrosstoolFlags(String... args) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .addAll(requiredObjcCrosstoolFlagsNoXcodeConfig(args))
        .add("--xcode_version_config=" + MockObjcSupport.XCODE_VERSION_CONFIG)
        .build();
  }

  public static ImmutableList<String> requiredObjcPlatformFlagsNoXcodeConfig(String... args) {
    ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(Stream.of(args).collect(toImmutableList()));
    if (Stream.of(args).noneMatch(arg -> arg.startsWith("--platforms="))) {
      argsBuilder.add("--platforms=" + MockObjcSupport.DARWIN_X86_64);
    }

    // Set a crosstool_top that is compatible with Apple transitions. Currently, even though this
    // references the old cc_toolchain_suite, it's still required of cc builds even when the
    // incompatible_enable_cc_toolchain_resolution flag is active.
    argsBuilder.add("--apple_crosstool_top=" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL);

    argsBuilder.add("--incompatible_enable_apple_toolchain_resolution");

    return argsBuilder.build();
  }

  /**
   * Returns the set of flags required to build objc libraries using the mock OSX crosstool except
   * for --xcode_version_config.
   */
  public static ImmutableList<String> requiredObjcCrosstoolFlagsNoXcodeConfig(String... args) {

    ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(Stream.of(args).collect(toImmutableList()));
    if (Stream.of(args).noneMatch(arg -> arg.startsWith("--platforms="))) {
      argsBuilder.add("--platforms=" + MockObjcSupport.DARWIN_X86_64);
    }

    // TODO(b/68751876): Set --apple_crosstool_top and --crosstool_top using the
    // AppleCrosstoolTransition
    argsBuilder
        .add("--apple_crosstool_top=" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL)
        .add("--crosstool_top=" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL);
    return argsBuilder.build();
  }

  /**
   * Sets up the support for building ObjC. Any partial toolchain line will be merged into every
   * toolchain stanza in the crosstool loaded from file.
   */
  public static void setup(MockToolsConfig config) throws IOException {

    // Create default, simple Apple toolchains based on the default Apple Crosstool.
    config.create(
        "tools/build_defs/apple/toolchains/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "toolchain(",
        "  name = 'darwin_x86_64_any',",
        "  toolchain = '//"
            + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR
            + ":cc-compiler-darwin_x86_64',",
        "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
        "  target_compatible_with = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:osx',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
        "  ],",
        ")",
        "toolchain(",
        "  name = 'ios_arm64_any',",
        "  toolchain = '//"
            + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR
            + ":cc-compiler-ios_arm64',",
        "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
        "  target_compatible_with = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64',",
        "  ],",
        ")");

    // Create default Apple target platforms.
    // Any device, simulator or maccatalyst platforms created by Apple tests should consider
    // building on one of these targets as parents, to ensure that the proper constraints are set.
    config.create(
        TestConstants.APPLE_PLATFORM_PATH + "/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "licenses(['notice'])",
        "platform(",
        "  name = 'darwin_x86_64',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:osx',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
        "  ],",
        ")",
        "platform(",
        "  name = 'ios_arm64',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64',",
        "  ],",
        ")",
        "platform(",
        "  name = 'ios_arm64e',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64e',",
        "  ],",
        ")",
        "platform(", // legacy platform only used to support tests
        "  name = 'ios_armv7',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7',",
        "  ],",
        ")",
        "platform(",
        "  name = 'watchos_armv7k',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7k',",
        "  ],",
        ")",
        "platform(",
        "  name = 'watchos_arm64_32',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64_32',",
        "  ],",
        ")");

    String[] simulatorPlatforms = {
      "platform(",
      "  name = 'ios_x86_64',",
      "  constraint_values = [",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
      "  ],",
      ")",
      "platform(",
      "  name = 'ios_i386',", // legacy platform only used to support tests
      "  constraint_values = [",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios',",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_32',",
      "  ],",
      ")",
      "platform(",
      "  name = 'watchos_x86_64',",
      "  constraint_values = [",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos',",
      "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
      "  ],",
      ")"
    };

    if (TestConstants.PRODUCT_NAME.equals("bazel")) {
      config.append(TestConstants.APPLE_PLATFORM_PATH + "/BUILD", simulatorPlatforms);
    } else {
      config.create(TestConstants.APPLE_PLATFORM_PATH + "/simulator/BUILD", simulatorPlatforms);
    }

    for (String tool : ImmutableSet.of("gcov", "testrunner", "mcov", "libtool")) {
      config.create(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/objc/" + tool);
    }
    config.create(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/objc/BUILD",
        getPyLoad("py_binary"),
        "package(default_visibility=['//visibility:public'])",
        "exports_files(glob(['**']))",
        "filegroup(name = 'default_provisioning_profile', srcs = ['foo.mobileprovision'])",
        "filegroup(name = 'xctest_infoplist', srcs = ['xctest.plist'])",
        "xcode_config(name = 'host_xcodes',",
        "  default = ':version7_3_1',",
        "  versions = [':version7_3_1', ':version5_0', ':version7_3', ':version5_8', ':version5'])",
        "xcode_version(",
        "  name = 'version7_3_1',",
        "  version = '" + DEFAULT_XCODE_VERSION + "',",
        "  default_ios_sdk_version = \"" + DEFAULT_IOS_SDK_VERSION + "\",",
        ")",
        "xcode_version(",
        "  name = 'version7_3',",
        "  version = '7.3',",
        ")",
        "xcode_version(",
        "  name = 'version5_0',",
        "  version = '5.0',",
        ")",
        "xcode_version(",
        "  name = 'version5_8',",
        "  version = '5.8',",
        ")",
        "xcode_version(",
        "  name = 'version5',",
        "  version = '5',",
        ")");
    // If the bazel tools repository is not in the workspace, also create a workspace tools/objc
    // package with a few lingering dependencies.
    // TODO(b/64537078): Move these dependencies underneath the tools workspace.
    if (TestConstants.TOOLS_REPOSITORY_SCRATCH.length() > 0) {
      config.create(
          "tools/objc/BUILD",
          """
          package(default_visibility = ["//visibility:public"])

          exports_files(glob(["**"]))

          filegroup(
              name = "default_provisioning_profile",
              srcs = ["foo.mobileprovision"],
          )

          filegroup(
              name = "xctest_infoplist",
              srcs = ["xctest.plist"],
          )
          """);
    }
    config.create(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/objc/foo.mobileprovision", "No such luck");
    config.create(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/objc/xctest.plist");
    setupCcToolchainConfig(config);
  }

  public static void setupCcToolchainConfig(
      MockToolsConfig config, CcToolchainConfig.Builder ccToolchainConfig) throws IOException {
    if (config.isRealFileSystem()) {
      config.linkTools(DEFAULT_OSX_CROSSTOOL_DIR);
    } else {
      CcToolchainConfig toolchainConfig = ccToolchainConfig.build();
      ImmutableList.Builder<CcToolchainConfig> toolchainConfigBuilder = ImmutableList.builder();
      toolchainConfigBuilder.add(toolchainConfig);
      if (!toolchainConfig.getTargetCpu().equals("darwin_x86_64")) {
        toolchainConfigBuilder.add(darwinX86_64().build());
      }

      new Crosstool(
              config,
              DEFAULT_OSX_CROSSTOOL_DIR,
              Label.parseCanonicalUnchecked("@bazel_tools//tools/osx"))
          .setCcToolchainFile(readCcToolchainConfigFile())
          .setSupportedArchs(OSX_ARCHS)
          .setToolchainConfigs(toolchainConfigBuilder.build())
          .setSupportsHeaderParsing(true)
          .writeOSX();
    }
  }

  public static void setupCcToolchainConfig(MockToolsConfig config) throws IOException {
    if (config.isRealFileSystem()) {
      config.linkTools(DEFAULT_OSX_CROSSTOOL_DIR);
    } else {
      new Crosstool(
              config,
              DEFAULT_OSX_CROSSTOOL_DIR,
              Label.parseCanonicalUnchecked("@bazel_tools//tools/osx"))
          .setCcToolchainFile(readCcToolchainConfigFile())
          .setSupportedArchs(OSX_ARCHS)
          .setToolchainConfigs(getDefaultCcToolchainConfigs())
          .setSupportsHeaderParsing(true)
          .writeOSX();
    }
  }

  private static ImmutableList<CcToolchainConfig> getDefaultCcToolchainConfigs() {
    return ImmutableList.of(
        darwinX86_64().build(),
        x64_windows().build(),
        ios_arm64().build(),
        ios_arm64e().build(),
        ios_armv7().build(),
        ios_i386().build(),
        iosX86_64().build(),
        tvos_arm64().build(),
        tvosX86_64().build(),
        watchos_armv7k().build(),
        watchos_arm64_32().build(),
        watchos_i386().build(),
        watchosX86_64().build());
  }

  public static CcToolchainConfig.Builder darwinX86_64() {
    return CcToolchainConfig.builder()
        .withCpu("darwin_x86_64")
        .withCompiler("compiler")
        .withToolchainIdentifier("darwin_x86_64")
        .withHostSystemName("x86_64-apple-macosx")
        .withTargetSystemName("x86_64-apple-macosx")
        .withTargetLibc("macosx")
        .withAbiVersion("darwin_x86_64")
        .withAbiLibcVersion("darwin_x86_64")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:osx");
  }

  public static CcToolchainConfig.Builder x64_windows() {
    return CcToolchainConfig.builder()
        .withCpu("x64_windows")
        .withCompiler("compiler")
        .withToolchainIdentifier("x64_windows")
        .withHostSystemName("x86_64-apple-macosx")
        .withTargetSystemName("x86_64-apple-macosx")
        .withTargetLibc("macosx")
        .withAbiVersion("darwin_x86_64")
        .withAbiLibcVersion("darwin_x86_64")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/<xcode_version>/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/<xcode_version>/Contents/Developer/Platforms/<platform>.platform/Developer/SDKs",
            "/usr/include");
  }

  public static CcToolchainConfig.Builder ios_arm64() {
    return CcToolchainConfig.builder()
        .withCpu("ios_arm64")
        .withCompiler("compiler")
        .withToolchainIdentifier("ios_arm64")
        .withHostSystemName("x86_64-apple-macosx")
        .withTargetSystemName("arm64-apple-ios")
        .withTargetLibc("ios")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios");
  }

  public static CcToolchainConfig.Builder ios_arm64e() {
    return CcToolchainConfig.builder()
        .withCpu("ios_arm64e")
        .withCompiler("compiler")
        .withToolchainIdentifier("ios_arm64e")
        .withHostSystemName("x86_64e-apple-macosx")
        .withTargetSystemName("arm64e-apple-ios")
        .withTargetLibc("ios")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64e",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios");
  }

  public static CcToolchainConfig.Builder ios_armv7() {
    return CcToolchainConfig.builder()
        .withCpu("ios_armv7")
        .withCompiler("compiler")
        .withToolchainIdentifier("ios_armv7")
        .withHostSystemName("x86_64-apple-macosx")
        .withTargetSystemName("armv7-apple-ios")
        .withTargetLibc("ios")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios");
  }

  public static CcToolchainConfig.Builder ios_i386() {
    return CcToolchainConfig.builder()
        .withCpu("ios_i386")
        .withCompiler("compiler")
        .withToolchainIdentifier("ios_i386")
        .withHostSystemName("x86_64-apple-macosx")
        .withTargetSystemName("i386-apple-ios")
        .withTargetLibc("ios")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_32",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios");
  }

  public static CcToolchainConfig.Builder iosX86_64() {
    return CcToolchainConfig.builder()
        .withCpu("ios_x86_64")
        .withCompiler("compiler")
        .withToolchainIdentifier("ios_x86_64")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("x86_64-apple-ios")
        .withTargetLibc("ios")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:ios");
  }

  public static CcToolchainConfig.Builder tvos_arm64() {
    return CcToolchainConfig.builder()
        .withCpu("tvos_arm64")
        .withCompiler("compiler")
        .withToolchainIdentifier("tvos_arm64")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("arm64-apple-tvos")
        .withTargetLibc("tvos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/AppleTVOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:tvos");
  }

  public static CcToolchainConfig.Builder tvosX86_64() {
    return CcToolchainConfig.builder()
        .withCpu("tvos_x86_64")
        .withCompiler("compiler")
        .withToolchainIdentifier("tvos_x86_64")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("x86_64-apple-tvos")
        .withTargetLibc("tvos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/AppleTVSimulator.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:tvos");
  }

  public static CcToolchainConfig.Builder watchos_armv7k() {
    return CcToolchainConfig.builder()
        .withCpu("watchos_armv7k")
        .withCompiler("compiler")
        .withToolchainIdentifier("watchos_armv7k")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("armv7k-apple-watchos")
        .withTargetLibc("watchos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:armv7k",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos");
  }

  @SuppressWarnings("MemberName") // Following style of other mock toolchain config methods.
  public static CcToolchainConfig.Builder watchos_arm64_32() {
    return CcToolchainConfig.builder()
        .withCpu("watchos_arm64_32")
        .withCompiler("compiler")
        .withToolchainIdentifier("watchos_arm64_32")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("arm64_32-apple-watchos")
        .withTargetLibc("watchos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/WatchOS.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:arm64_32",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos");
  }

  public static CcToolchainConfig.Builder watchos_i386() {
    return CcToolchainConfig.builder()
        .withCpu("watchos_i386")
        .withCompiler("compiler")
        .withToolchainIdentifier("watchos_i386")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("i386-apple-watchos")
        .withTargetLibc("watchos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_32",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos");
  }

  @SuppressWarnings("MemberName") // Following style of other mock toolchain config methods.
  public static CcToolchainConfig.Builder watchosX86_64() {
    return CcToolchainConfig.builder()
        .withCpu("watchos_x86_64")
        .withCompiler("compiler")
        .withToolchainIdentifier("watchos_x86_64")
        .withHostSystemName("x86_64-apple-ios")
        .withTargetSystemName("x86_64-apple-watchos")
        .withTargetLibc("watchos")
        .withAbiVersion("local")
        .withAbiLibcVersion("local")
        .withCcTargetOs("apple")
        .withSysroot("")
        .withCxxBuiltinIncludeDirectories(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/",
            "/Applications/Xcode.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode-beta.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.2.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_7.3.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/Applications/Xcode_8.2.1.app/Contents/Developer/Platforms/WatchSimulator.platform/Developer/SDKs",
            "/usr/include")
        .withToolchainTargetConstraints(
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64",
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:watchos");
  }

  /** Test setup for the Apple SDK targets that are used in tests. */
  public static void setupAppleSdks(MockToolsConfig config) throws IOException {
    config.create(
        "third_party/apple_sdks/BUILD",
        "package(default_visibility=['//visibility:public'])\n"
            + "licenses([\"notice\"])\n"
            + "filegroup(name = \"apple_sdk_compile\")");
  }

  public static String readCcToolchainConfigFile() throws IOException {
    return ResourceLoader.readFromResources(MOCK_OSX_TOOLCHAIN_CONFIG_PATH);
  }
}
