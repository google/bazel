// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.testutil;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;

/**
 * Various constants required by the tests.
 */
public class TestConstants {
  private TestConstants() {}

  public static final String LOAD_PROTO_LANG_TOOLCHAIN =
      "load('@com_google_protobuf//bazel/toolchains:proto_lang_toolchain.bzl', 'proto_lang_toolchain')";

  public static final String PRODUCT_NAME = "bazel";

  /**
   * A list of all embedded binaries that go into the regular Bazel binary.
   */
  public static final ImmutableList<String> EMBEDDED_TOOLS = ImmutableList.of(
      "build-runfiles",
      "linux-sandbox",
      "process-wrapper",
      "xcode-locator");

  /**
   * Location in the bazel repo where embedded binaries come from.
   */
  public static final ImmutableList<String> EMBEDDED_SCRIPTS_PATHS = ImmutableList.of(
      "_main/src/main/tools");

  /**
   * Default workspace name.
   */
  public static final String WORKSPACE_NAME = "_main";

  /**
   * Name of a class with an INSTANCE field of type AnalysisMock to be used for analysis tests.
   */
  public static final String TEST_ANALYSIS_MOCK =
      "com.google.devtools.build.lib.analysis.mock.BazelAnalysisMock";

  /**
   * Directory where we can find bazel's Java tests, relative to a test's runfiles directory.
   */
  public static final String JAVATESTS_ROOT = "_main/src/test/java/";

  /** Location of the bazel repo relative to the workspace root */
  public static final String BAZEL_REPO_PATH = "";

  /** The file path in which to create files so that they end up under Bazel main repository. */
  public static final String BAZEL_REPO_SCRATCH = "../_main/";

  /** Relative path to the {@code process-wrapper} tool. */
  public static final String PROCESS_WRAPPER_PATH =
      "_main/src/main/tools/process-wrapper";

  /** Relative path to the {@code linux-sandbox} tool. */
  public static final String LINUX_SANDBOX_PATH =
      "_main/src/main/tools/linux-sandbox";

  /** Relative path to the {@code spend_cpu_time} testing tool. */
  public static final String CPU_TIME_SPENDER_PATH =
      "_main/src/test/shell/integration/spend_cpu_time";

  /**
   * Directory where we can find Bazel's own bootstrapping rules relative to a test's runfiles
   * directory, i.e. when //tools/build_rules:srcs is in a test's data.
   */
  public static final String BUILD_RULES_DATA_PATH = "_main/tools/build_rules/";

  public static final String TEST_RULE_CLASS_PROVIDER =
      "com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider";
  public static final String TEST_RULE_MODULE =
      "com.google.devtools.build.lib.bazel.rules.BazelRulesModule";
  public static final String TEST_STRATEGY_MODULE =
      "com.google.devtools.build.lib.bazel.rules.BazelStrategyModule";
  public static final String TEST_REAL_UNIX_FILE_SYSTEM =
      "com.google.devtools.build.lib.unix.UnixFileSystem";
  public static final String TEST_UNIX_HASH_ATTRIBUTE = "";

  public static final ImmutableList<String> IGNORED_MESSAGE_PREFIXES = ImmutableList.<String>of();

  /** The path in which the mock cc crosstool resides. */
  public static final String MOCK_CC_CROSSTOOL_PATH = "tools/cpp";

  /** The path in which the mock license rule resides. */
  public static final String MOCK_LICENSE_SCRATCH = "third_party/rules_license/";

  /** The workspace repository label under which built-in tools reside. */
  public static final RepositoryName TOOLS_REPOSITORY = RepositoryName.BAZEL_TOOLS;
  /** The file path in which to create files so that they end up under {@link #TOOLS_REPOSITORY}. */
  public static final String TOOLS_REPOSITORY_SCRATCH = "embedded_tools/";

  /** The directory in which rules_cc repo resides in execroot. */
  public static final String RULES_CC_REPOSITORY_EXECROOT = "external/" + RulesCcRepoName.CANONICAL_REPO_NAME + "/";
  /* Prefix for loads from rules_cc */
  public static final String RULES_CC = "@rules_cc//cc";

  /**
   * The repo/package rules_python is rooted at. If empty, builtin rules are used.
   */
  public static final String RULES_PYTHON_PACKAGE_ROOT = "@@rules_python+/";

  public static final String PYINFO_BZL = "@@rules_python+//python/private:py_info.bzl";

  public static final String PYRUNTIMEINFO_BZL = "@@rules_python+//python/private:py_runtime_info.bzl";

  // Constants used to determine how genrule pulls in the setup script.
  public static final String GENRULE_SETUP = "@bazel_tools//tools/genrule:genrule-setup.sh";
  public static final String GENRULE_SETUP_PATH = "genrule-setup.sh";

  public static final String STARLARK_EXEC_TRANSITION =
      "@_builtins//:common/builtin_exec_platforms.bzl%bazel_exec_transition";

  /**
   * Flags that must be set for Bazel to work properly, if the default values are unusable for some
   * reason.
   */
  public static final ImmutableList<String> PRODUCT_SPECIFIC_FLAGS =
      ImmutableList.of(
          "--platforms=@platforms//host",
          "--host_platform=@platforms//host",
          // TODO(#7849): Remove after flag flip.
          "--incompatible_use_toolchain_resolution_for_java_rules");

  public static final ImmutableList<String> PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS =
      ImmutableList.of(
          // Don't apply autoloads in unit tests, because not all repos are available
          "--incompatible_autoload_externally=");

  /** Partial query to filter out implicit dependencies of C/C++ rules. */
  public static final String CC_DEPENDENCY_CORRECTION =
      " - deps(" + TOOLS_REPOSITORY + "//tools/cpp:current_cc_toolchain)"
      + " - deps(" + TOOLS_REPOSITORY + "//tools/cpp:grep-includes)";

  public static final String APPLE_PLATFORM_PATH = "build_bazel_apple_support/platforms";
  public static final String APPLE_PLATFORM_PACKAGE_ROOT = "@@build_bazel_apple_support+//platforms";
  public static final String CONSTRAINTS_PACKAGE_ROOT = "@platforms//";

  public static final String PLATFORMS_PATH = "embedded_tools/platforms";
  public static final String CONSTRAINTS_PATH = "platforms_workspace";

  public static final String PLATFORM_LABEL = "@platforms//host";
  public static final String PIII_PLATFORM_LABEL = "@platforms//host:piii";

  /** The java toolchain type. */
  public static final String JAVA_TOOLCHAIN_TYPE = "@@bazel_tools//tools/jdk:toolchain_type";

  /** The cpp toolchain type. */
  public static final String CPP_TOOLCHAIN_TYPE = "@@bazel_tools//tools/cpp:toolchain_type";

  /** Whether blake3 can be used through JNI */
  public static final boolean BLAKE3_AVAILABLE = true;

  /** A choice of test execution mode, only varies internally. */
  public enum InternalTestExecutionMode {
    NORMAL
  }
}
