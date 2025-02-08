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
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.MockObjcSupport;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test case for the use of Apple platforms and the macOS crosstool. */
@RunWith(JUnit4.class)
public class ApplePlatformsToolchainSelectionTest extends ObjcRuleTestCase {

  @Override
  protected boolean platformBasedToolchains() {
    return true;
  }

  private static final ToolchainTypeInfo CPP_TOOLCHAIN_TYPE =
      ToolchainTypeInfo.create(
          Label.parseCanonicalUnchecked(
              TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type"));

  @Test
  public void testMacOsToolchainSetup() throws Exception {
    // Verify the macOS toolchain and its associated cpp toolchain.
    ConfiguredTarget darwinToolchain =
        getConfiguredTarget("//tools/build_defs/apple/toolchains:darwin_x86_64_any");
    assertThat(darwinToolchain).isNotNull();
    DeclaredToolchainInfo darwinToolchainInfo =
        PlatformProviderUtils.declaredToolchainInfo(darwinToolchain);
    assertThat(darwinToolchainInfo).isNotNull();
    assertThat(darwinToolchainInfo.resolvedToolchainLabel())
        .isEqualTo(
            Label.parseCanonicalUnchecked(
                "//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":cc-compiler-darwin_x86_64"));
    assertThat(darwinToolchainInfo.toolchainType()).isEqualTo(CPP_TOOLCHAIN_TYPE);

    // Verify the macOS platform.
    ConfiguredTarget darwinPlatform =
        getConfiguredTarget(TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":darwin_x86_64");
    PlatformInfo darwinPlatformInfo = PlatformProviderUtils.platform(darwinPlatform);
    assertThat(darwinPlatformInfo).isNotNull();
  }

  @Test
  public void testIosDeviceToolchainSetup() throws Exception {
    // Verify the iOS 64 bit device toolchain and its associated cpp toolchain.
    ConfiguredTarget iosDeviceToolchain =
        getConfiguredTarget("//tools/build_defs/apple/toolchains:ios_arm64_any");
    assertThat(iosDeviceToolchain).isNotNull();
    DeclaredToolchainInfo iosDeviceToolchainInfo =
        PlatformProviderUtils.declaredToolchainInfo(iosDeviceToolchain);
    assertThat(iosDeviceToolchainInfo).isNotNull();
    assertThat(iosDeviceToolchainInfo.resolvedToolchainLabel())
        .isEqualTo(
            Label.parseCanonicalUnchecked(
                "//" + MockObjcSupport.DEFAULT_OSX_CROSSTOOL_DIR + ":cc-compiler-ios_arm64"));
    assertThat(iosDeviceToolchainInfo.toolchainType()).isEqualTo(CPP_TOOLCHAIN_TYPE);

    // Verify the iOS 64 bit device platform.
    ConfiguredTarget iosDevicePlatform =
        getConfiguredTarget(TestConstants.APPLE_PLATFORM_PACKAGE_ROOT + ":ios_arm64");
    PlatformInfo iosDevicePlatformInfo = PlatformProviderUtils.platform(iosDevicePlatform);
    assertThat(iosDevicePlatformInfo).isNotNull();
  }

}
