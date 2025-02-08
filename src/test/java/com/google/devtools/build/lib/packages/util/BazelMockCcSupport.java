// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static java.lang.Integer.MAX_VALUE;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;

/**
 * Bazel implementation of {@link MockCcSupport}
 */
public final class BazelMockCcSupport extends MockCcSupport {
  public static final BazelMockCcSupport INSTANCE = new BazelMockCcSupport();

  /** Filter to remove implicit dependencies of C/C++ rules. */
  private static final boolean isNotCcLabel(String label) {
    return !label.startsWith("//tools/cpp");
  }

  private BazelMockCcSupport() {}

  private static final ImmutableList<String> CROSSTOOL_ARCHS =
      ImmutableList.of("piii", "k8", "armeabi-v7a", "ppc", "darwin_x86_64");

  @Override
  protected String getRealFilesystemCrosstoolTopPath() {
    // TODO(b/195425240): Make real-filesystem mode work.
    return "";
  }

  @Override
  protected String[] getRealFilesystemToolsToLink(String crosstoolTop) {
    // TODO(b/195425240): Make real-filesystem mode work.
    return new String[0];
  }

  @Override
  protected String[] getRealFilesystemToolsToCopy(String crosstoolTop) {
    // TODO(b/195425240): Make real-filesystem mode work.
    return new String[0];
  }

  @Override
  protected ImmutableList<String> getCrosstoolArchs() {
    return CROSSTOOL_ARCHS;
  }

  @Override
  public void setup(MockToolsConfig config) throws IOException {
    writeMacroFile(config);
    setupRulesCc(config);
    setupCcToolchainConfig(config, getToolchainConfigs());
    createParseHeadersAndLayeringCheckWhitelist(config);
    createStarlarkLooseHeadersWhitelist(config, "//...");
    config.append(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/cpp/BUILD",
        "alias(name='host_xcodes',actual='@local_config_xcode//:host_xcodes')");

    // Copies rules_cc from real @rules_cc
    config.create("third_party/bazel_rules/rules_cc/MODULE.bazel", "module(name='rules_cc')");
    Runfiles runfiles = Runfiles.preload().withSourceRepository("");
    PathFragment path = PathFragment.create(runfiles.rlocation("rules_cc/cc/defs.bzl"));
    config.copyDirectory(
        path.getParentDirectory(), "third_party/bazel_rules/rules_cc/cc", MAX_VALUE, true);
    config.overwrite("third_party/bazel_rules/rules_cc/cc/toolchains/BUILD");
    config.overwrite("third_party/bazel_rules/rules_cc/cc/common/BUILD");
  }

  @Override
  public Label getMockCrosstoolLabel() {
    return Label.parseCanonicalUnchecked("@bazel_tools//tools/cpp:toolchain");
  }

  @Override
  public String getMockCrosstoolPath() {
    return "embedded_tools/tools/cpp/";
  }

  @Override
  public Predicate<String> labelNameFilter() {
    return BazelMockCcSupport::isNotCcLabel;
  }

  @Override
  protected boolean shouldUseRealFileSystemCrosstool() {
    // TODO(b/195425240): Workaround for lack of real-filesystem support.
    return false;
  }

  private static ImmutableList<CcToolchainConfig> getToolchainConfigs() {
    ImmutableList.Builder<CcToolchainConfig> result = ImmutableList.builder();

    // Different from CcToolchainConfig.getDefault....
    result.add(CcToolchainConfig.builder().build());

    if (OS.getCurrent() == OS.DARWIN) {
      result.add(CcToolchainConfig.getCcToolchainConfigForCpu("darwin_x86_64"));
      result.add(CcToolchainConfig.getCcToolchainConfigForCpu("darwin_arm64"));
    }

    if (System.getProperty("os.arch").equals("s390x")) {
      result.add(CcToolchainConfig.getCcToolchainConfigForCpu("s390x"));
    }
    return result.build();
  }
}
