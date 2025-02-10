// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfigurationMakeVariableContext;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for calculating the sysroot that require building configured targets. */
@RunWith(JUnit4.class)
public final class CppSysrootTest extends BuildViewTestCase {

  @Before
  public void writeDummyLibrary() throws Exception {
    scratch.file("dummy/BUILD", "cc_library(name='library')");
  }

  void testCCFlagsContainsSysroot(
      BuildConfigurationValue config, String sysroot, boolean shouldContain) throws Exception {

    RuleContext ruleContext =
        getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), config));
    ConfigurationMakeVariableContext context =
        new ConfigurationMakeVariableContext(
            ruleContext.getTarget().getPackage(),
            config,
            ruleContext.getDefaultTemplateVariableProviders(),
            ImmutableList.of(new CcCommon.CcFlagsSupplier(ruleContext)));
    if (shouldContain) {
      assertThat(context.lookupVariable("CC_FLAGS")).contains("--sysroot=" + sysroot);
    } else {
      assertThat(context.lookupVariable("CC_FLAGS")).doesNotContain("--sysroot=" + sysroot);
    }
  }

  CcToolchainProvider getCcToolchainProvider(BuildConfigurationValue configuration)
      throws Exception {
    // use dummy library to get C++ toolchain from toolchain resolution
    RuleContext ruleContext =
        getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), configuration));
    return Preconditions.checkNotNull(CppHelper.getToolchain(ruleContext));
  }

  @Test
  public void testHostGrteTop() throws Exception {
    scratch.file(
        "a/grte/top/BUILD",
        """
        filegroup(name = "everything")

        cc_library(name = "library")
        """);
    useConfiguration("--host_grte_top=//a/grte/top");
    BuildConfigurationValue target = getTargetConfiguration();
    CcToolchainProvider targetCcProvider = getCcToolchainProvider(target);
    BuildConfigurationValue exec = getExecConfiguration();
    CcToolchainProvider hostCcProvider = getCcToolchainProvider(exec);

    testCCFlagsContainsSysroot(exec, "a/grte/top", true);
    assertThat(hostCcProvider.getSysroot().equals(targetCcProvider.getSysroot())).isFalse();
  }

  @Test
  public void testOverrideHostGrteTop() throws Exception {
    scratch.file("a/grte/top/BUILD", "filegroup(name='everything')");
    scratch.file("b/grte/top/BUILD", "filegroup(name='everything')");
    useConfiguration("--grte_top=//a/grte/top", "--host_grte_top=//b/grte/top");
    BuildConfigurationValue target = getTargetConfiguration();
    CcToolchainProvider targetCcProvider = getCcToolchainProvider(target);
    BuildConfigurationValue exec = getExecConfiguration();
    CcToolchainProvider hostCcProvider = getCcToolchainProvider(exec);

    assertThat(targetCcProvider.getSysroot()).isEqualTo("a/grte/top");
    assertThat(hostCcProvider.getSysroot()).isEqualTo("b/grte/top");

    testCCFlagsContainsSysroot(target, "a/grte/top", true);
    testCCFlagsContainsSysroot(target, "b/grte/top", false);
    testCCFlagsContainsSysroot(exec, "b/grte/top", true);
    testCCFlagsContainsSysroot(exec, "a/grte/top", false);
  }

  @Test
  public void testGrteTopAlias() throws Exception {
    scratch.file("a/grte/top/BUILD", "filegroup(name='everything')");
    scratch.file("b/grte/top/BUILD", "alias(name='everything', actual='//a/grte/top:everything')");
    useConfiguration("--grte_top=//b/grte/top");
    BuildConfigurationValue target = getTargetConfiguration();
    CcToolchainProvider targetCcProvider = getCcToolchainProvider(target);

    assertThat(targetCcProvider.getSysroot()).isEqualTo("a/grte/top");

    testCCFlagsContainsSysroot(target, "a/grte/top", true);
    testCCFlagsContainsSysroot(target, "b/grte/top", false);
  }

  @Test
  public void testSysroot() throws Exception {
    // BuildConfigurationValue shouldn't provide a sysroot option by default.
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    BuildConfigurationValue config = getTargetConfiguration();
    testCCFlagsContainsSysroot(config, "/usr/grte/v1", true);

    scratch.file("a/grte/top/BUILD", "filegroup(name='everything')");
    // BuildConfigurationValue should work with label grte_top options.
    useConfiguration(
        "--platforms=" + TestConstants.PLATFORM_LABEL, "--grte_top=//a/grte/top:everything");
    config = getTargetConfiguration();
    testCCFlagsContainsSysroot(config, "a/grte/top", true);
  }

  @Test
  public void testSysrootInFeatureConfigBlocksLegacySysroot() throws Exception {
    AnalysisMock.get()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder().withActionConfigs("sysroot_in_action_config"));
    scratch.overwriteFile("a/grte/top/BUILD", "filegroup(name='everything')");
    useConfiguration("--grte_top=//a/grte/top:everything");
    RuleContext ruleContext =
        getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), targetConfig));
    ConfigurationMakeVariableContext context =
        new ConfigurationMakeVariableContext(
            ruleContext.getTarget().getPackage(),
            targetConfig,
            ruleContext.getDefaultTemplateVariableProviders(),
            ImmutableList.of(new CcCommon.CcFlagsSupplier(ruleContext)));
    assertThat(context.lookupVariable("CC_FLAGS"))
        .contains("fc-start --sysroot=a/grte/top-from-feature fc-end");
    assertThat(context.lookupVariable("CC_FLAGS")).doesNotContain("--sysroot=a/grte/top fc");
  }

  @Test
  public void testSysrootWithExecConfig() throws Exception {
    // The exec BuildConfigurationValue shouldn't provide a sysroot option by default.
    for (String platform :
        new String[] {TestConstants.PLATFORM_LABEL, TestConstants.PIII_PLATFORM_LABEL}) {
      useConfiguration("--platforms=" + platform);
      BuildConfigurationValue config = getExecConfiguration();
      testCCFlagsContainsSysroot(config, "/usr/grte/v1", true);
    }
    // The exec BuildConfigurationValue should work with label grte_top options.
    scratch.file("a/grte/top/BUILD", "filegroup(name='everything')");
    for (String platform :
        new String[] {TestConstants.PLATFORM_LABEL, TestConstants.PIII_PLATFORM_LABEL}) {
      useConfiguration("--platforms=" + platform, "--host_grte_top=//a/grte/top");
      BuildConfigurationValue config = getExecConfiguration();
      testCCFlagsContainsSysroot(config, "a/grte/top", true);

      // "--grte_top" does *not* set the exec grte_top,
      // so we don't get "a/grte/top" here, but instead the default "/usr/grte/v1"
      useConfiguration("--platforms=" + platform, "--grte_top=//a/grte/top");
      config = getExecConfiguration();
      testCCFlagsContainsSysroot(config, "/usr/grte/v1", true);
    }
  }

  @Test
  public void testConfigurableSysroot() throws Exception {
    scratch.file(
        "test/config_setting/BUILD",
        "config_setting(name='defines', values={'define': 'override_grte_top=1'})");
    scratch.file("a/grte/top/BUILD", "filegroup(name='everything')");
    scratch.file("b/grte/top/BUILD", "filegroup(name='everything')");
    scratch.file(
        "c/grte/top/BUILD",
        """
        alias(
            name = "everything",
            actual = select(
                {
                    "//test/config_setting:defines": "//a/grte/top:everything",
                    "//conditions:default": "//b/grte/top:everything",
                },
            ),
        )
        """);
    useConfiguration("--grte_top=//c/grte/top:everything");
    CcToolchainProvider ccProvider = getCcToolchainProvider(getTargetConfiguration());
    assertThat(ccProvider.getSysroot()).isEqualTo("b/grte/top");

    useConfiguration("--grte_top=//c/grte/top:everything", "--define=override_grte_top=1");
    ccProvider = getCcToolchainProvider(getTargetConfiguration());
    assertThat(ccProvider.getSysroot()).isEqualTo("a/grte/top");
  }
}
