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

#include "src/main/cpp/bazel_startup_options.h"

#include <stdlib.h>

#include <memory>

#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/test/cpp/test_util.h"
#include "googletest/include/gtest/gtest.h"

namespace blaze {

class BazelStartupOptionsTest : public ::testing::Test {
 protected:
  void SetUp() override {
    // This knowingly ignores the possibility of these environment variables
    // being unset because we expect our test runner to set them in all cases.
    // Otherwise, we'll crash here, but this keeps our code simpler.
    old_test_tmpdir_ = GetPathEnv("TEST_TMPDIR");

    ReinitStartupOptions();
  }

  void TearDown() override { SetEnv("TEST_TMPDIR", old_test_tmpdir_); }

  // Recreates startup_options_ after changes to the environment.
  void ReinitStartupOptions() {
    startup_options_ = std::make_unique<BazelStartupOptions>();
  }

  // Calls UpdateConfiguration with some default values.
  void UpdateConfiguration() {
    startup_options_->UpdateConfiguration("deadbeef", "workspace", false);
  }

 protected:
  std::unique_ptr<BazelStartupOptions> startup_options_;

 private:
  std::string old_test_tmpdir_;
};

TEST_F(BazelStartupOptionsTest, ProductName) {
  ASSERT_EQ("Bazel", startup_options_->product_name);
}

TEST_F(BazelStartupOptionsTest, JavaLoggingOptions) {
  ASSERT_EQ("com.google.devtools.build.lib.util.SingleLineFormatter",
            startup_options_->java_logging_formatter);
}

TEST_F(BazelStartupOptionsTest, EmptyFlagsAreInvalid) {
  {
    bool result;
    std::string error;
    EXPECT_TRUE(startup_options_->MaybeCheckValidNullary("", &result, &error));
    EXPECT_FALSE(result);
  }

  {
    bool result;
    std::string error;
    EXPECT_TRUE(
        startup_options_->MaybeCheckValidNullary("--", &result, &error));
    EXPECT_FALSE(result);
  }

  EXPECT_FALSE(startup_options_->IsUnary(""));
  EXPECT_FALSE(startup_options_->IsUnary("--"));
}

#ifdef __linux
TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnLinuxWithTestTmpdir) {
  SetEnv("USER", "gandalf");
  SetEnv("HOME", "/nonexistent/home");
  SetEnv("XDG_CACHE_HOME", "/nonexistent/cache");
  SetEnv("TEST_TMPDIR", "/nonexistent/tmpdir");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("/nonexistent/tmpdir/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(
      blaze_util::Path("/nonexistent/tmpdir/_bazel_gandalf/install/deadbeef"),
      startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("/nonexistent/tmpdir/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}

TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnLinuxWithXdgCacheHome) {
  SetEnv("USER", "gandalf");
  SetEnv("HOME", "/nonexistent/home");
  SetEnv("XDG_CACHE_HOME", "/nonexistent/cache");
  UnsetEnv("TEST_TMPDIR");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("/nonexistent/cache/bazel/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(blaze_util::Path(
                "/nonexistent/cache/bazel/_bazel_gandalf/install/deadbeef"),
            startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("/nonexistent/cache/bazel/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}

TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnLinuxWithHome) {
  SetEnv("USER", "gandalf");
  SetEnv("HOME", "/nonexistent/home");
  UnsetEnv("TEST_TMPDIR");
  UnsetEnv("XDG_CACHE_HOME");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("/nonexistent/home/.cache/bazel/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(
      blaze_util::Path(
          "/nonexistent/home/.cache/bazel/_bazel_gandalf/install/deadbeef"),
      startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("/nonexistent/home/.cache/bazel/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}

TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnLinuxNoShellExpansion) {
  SetEnv("USER", "gandalf");
  SetEnv("TEST_TMPDIR", "~/\"$foo/test\"");
  SetEnv("XDG_CACHE_HOME", "~/cache${bar}");
  SetEnv("HOME", "~/home$(echo baz)");

  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path(blaze_util::GetCwd() + "/~/\"$foo/test\"" +
                             "/_bazel_gandalf"),
            startup_options_->output_user_root);

  UnsetEnv("TEST_TMPDIR");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path(blaze_util::GetCwd() +
                             "/~/cache${bar}/bazel/_bazel_gandalf"),
            startup_options_->output_user_root);

  UnsetEnv("XDG_CACHE_HOME");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path(blaze_util::GetCwd() +
                             "/~/home$(echo baz)/.cache/bazel/_bazel_gandalf"),
            startup_options_->output_user_root);
}
#endif  // __linux

#ifdef __APPLE__
TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnDarwin) {
  SetEnv("USER", "gandalf");
  SetEnv("HOME", "/nonexistent/home");
  UnsetEnv("TEST_TMPDIR");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("/var/tmp/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(blaze_util::Path("/var/tmp/_bazel_gandalf/install/deadbeef"),
            startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("/var/tmp/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}
#endif  // __APPLE__

#if defined(__WIN32__) || defined(__CYGWIN__)
TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnWindowsWithHome) {
  SetEnv("USER", "gandalf");
  SetEnv("HOME", "C:\\Users\\gandalf");
  UnsetEnv("TEST_TMPDIR");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/install/deadbeef"),
            startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}

TEST_F(BazelStartupOptionsTest, UpdateConfigurationOnWindowsWithUserProfile) {
  UnsetEnv("HOME");
  SetEnv("USERPROFILE", "C:\\Users\\gandalf");
  UnsetEnv("TEST_TMPDIR");
  ReinitStartupOptions();
  UpdateConfiguration();

  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/_bazel_gandalf"),
            startup_options_->output_user_root);
  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/install/deadbeef"),
            startup_options_->install_base);
  ASSERT_EQ(blaze_util::Path("C:/Users/gandalf/_bazel_gandalf/"
                             "1629dee48cc4e53161f9b2be8614e062"),
            startup_options_->output_base);
}
#endif  // __WIN32__ || __CYGWIN__

// TODO(#4502 related cleanup) This test serves as a catalog of the valid
// options - make this test check that the list is complete, that no options are
// missing.
TEST_F(BazelStartupOptionsTest, ValidStartupFlags) {
  // IMPORTANT: Before modifying this test, please contact a Bazel core team
  // member that knows the Google-internal procedure for adding/deprecating
  // startup flags.
  const StartupOptions* options = startup_options_.get();
  ExpectValidNullaryOption(options, "batch");
  ExpectValidNullaryOption(options, "batch_cpu_scheduling");
  ExpectValidNullaryOption(options, "block_for_lock");
  ExpectValidNullaryOption(options, "client_debug");
  ExpectValidNullaryOption(options, "fatal_event_bus_exceptions");
  ExpectValidNullaryOption(options, "home_rc");
  ExpectValidNullaryOption(options, "host_jvm_debug");
  ExpectValidNullaryOption(options, "autodetect_server_javabase");
  ExpectValidNullaryOption(options, "ignore_all_rc_files");
  ExpectValidNullaryOption(options, "shutdown_on_low_sys_mem");
  ExpectValidNullaryOption(options, "system_rc");
  ExpectValidNullaryOption(options, "watchfs");
  ExpectValidNullaryOption(options, "workspace_rc");
  ExpectValidNullaryOption(options, "write_command_log");
  ExpectIsUnaryOption(options, "bazelrc");
  ExpectIsUnaryOption(options, "command_port");
  ExpectIsUnaryOption(options, "connect_timeout_secs");
  ExpectIsUnaryOption(options, "digest_function");
  ExpectIsUnaryOption(options, "host_jvm_args");
  ExpectIsUnaryOption(options, "install_base");
  ExpectIsUnaryOption(options, "invocation_policy");
  ExpectIsUnaryOption(options, "io_nice_level");
  ExpectIsUnaryOption(options, "local_startup_timeout_secs");
  ExpectIsUnaryOption(options, "macos_qos_class");
  ExpectIsUnaryOption(options, "max_idle_secs");
  ExpectIsUnaryOption(options, "output_base");
  ExpectIsUnaryOption(options, "output_user_root");
  ExpectIsUnaryOption(options, "server_javabase");
}

TEST_F(BazelStartupOptionsTest, BlazercFlagsAreNotAccepted) {
  {
    bool result;
    std::string error;
    EXPECT_TRUE(startup_options_->MaybeCheckValidNullary("--master_blazerc",
                                                         &result, &error));
    EXPECT_FALSE(result);
  }

  EXPECT_FALSE(startup_options_->IsUnary("--master_blazerc"));

  {
    bool result;
    std::string error;
    EXPECT_TRUE(
        startup_options_->MaybeCheckValidNullary("--blazerc", &result, &error));
    EXPECT_FALSE(result);
  }

  EXPECT_FALSE(startup_options_->IsUnary("--blazerc"));
}

TEST_F(BazelStartupOptionsTest, IgnoredBazelrcFlagWarns) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(), {"--bazelrc=somefile", "--ignore_all_rc_files"},
      "WARNING: Value of --bazelrc is ignored, since --ignore_all_rc_files is "
      "on.\n");
}

TEST_F(BazelStartupOptionsTest, IgnoredBazelrcFlagWarnsWhenAfterIgnore) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(), {"--ignore_all_rc_files", "--bazelrc=somefile"},
      "WARNING: Value of --bazelrc is ignored, since --ignore_all_rc_files is "
      "on.\n");
}

TEST_F(BazelStartupOptionsTest, IgnoredWorkspaceRcFlagWarns) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(), {"--workspace_rc", "--ignore_all_rc_files"},
      "WARNING: Explicit value of --workspace_rc is ignored, "
      "since --ignore_all_rc_files is on.\n");
}

TEST_F(BazelStartupOptionsTest, IgnoredWorkspaceRcFlagWarnsAfterIgnore) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(), {"--ignore_all_rc_files", "--workspace_rc"},
      "WARNING: Explicit value of --workspace_rc is ignored, "
      "since --ignore_all_rc_files is on.\n");
}

TEST_F(BazelStartupOptionsTest, MultipleIgnoredRcFlagsWarnOnceEach) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(),
      {"--workspace_rc", "--bazelrc=somefile", "--ignore_all_rc_files",
       "--bazelrc=thefinalfile", "--workspace_rc"},
      "WARNING: Value of --bazelrc is ignored, "
      "since --ignore_all_rc_files is on.\n"
      "WARNING: Explicit value of --workspace_rc is ignored, "
      "since --ignore_all_rc_files is on.\n");
}

TEST_F(BazelStartupOptionsTest, IgnoredNoMasterBazelrcDoesNotWarn) {
  // Warning for nomaster would feel pretty spammy - it's redundant, but the
  // behavior is as one would expect, so warning is unnecessary.
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(), {"--ignore_all_rc_files"},
      "");
}

TEST_F(BazelStartupOptionsTest, IgnoreOptionDoesNotWarnOnItsOwn) {
  ParseStartupOptionsAndExpectWarning(startup_options_.get(),
                                      {"--ignore_all_rc_files"}, "");
}

TEST_F(BazelStartupOptionsTest, NonIgnoredOptionDoesNotWarn) {
  ParseStartupOptionsAndExpectWarning(startup_options_.get(),
                                      {"--bazelrc=somefile"}, "");
}

TEST_F(BazelStartupOptionsTest, FinalValueOfIgnoreIsUsedForWarning) {
  ParseStartupOptionsAndExpectWarning(
      startup_options_.get(),
      {"--ignore_all_rc_files", "--noignore_all_rc_files"},
      "");
}

TEST_F(BazelStartupOptionsTest, LockInstallBase) {
  EXPECT_TRUE(startup_options_->lock_install_base);
}

}  // namespace blaze
