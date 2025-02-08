// Copyright 2016 The Bazel Authors. All rights reserved.
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

#include "src/main/cpp/startup_options.h"

#include <stdlib.h>

#include <memory>

#include "src/main/cpp/blaze_util_platform.h"
#ifdef __linux
#include "src/main/cpp/util/file_platform.h"
#endif  // __linux
#include "src/main/cpp/workspace_layout.h"
#include "src/test/cpp/test_util.h"
#include "googletest/include/gtest/gtest.h"

namespace blaze {

// Minimal StartupOptions class for testing.
class FakeStartupOptions : public StartupOptions {
 public:
  FakeStartupOptions()
      : StartupOptions("Bazel", /* lock_install_base= */ true) {}
  blaze_exit_code::ExitCode ProcessArgExtra(
      const char *arg, const char *next_arg, const std::string &rcfile,
      const char **value, bool *is_processed, std::string *error) override {
    *is_processed = false;
    return blaze_exit_code::SUCCESS;
  }
  void MaybeLogStartupOptionWarnings() const override {}

 protected:
  blaze_util::Path GetDefaultOutputRoot() const override {
    return blaze_util::Path("/output_root");
  }

  std::string GetRcFileBaseName() const override { return ".bazelrc"; }
};

class StartupOptionsTest : public ::testing::Test {
 protected:
  void SetUp() override {
    // This knowingly ignores the possibility of these environment variables
    // being unset because we expect our test runner to set them in all cases.
    // Otherwise, we'll crash here, but this keeps our code simpler.
    old_home_ = GetHomeDir();
    old_test_tmpdir_ = GetPathEnv("TEST_TMPDIR");

    ReinitStartupOptions();
  }

  void TearDown() override {
    SetEnv("HOME", old_home_);
    SetEnv("TEST_TMPDIR", old_test_tmpdir_);
  }

  // Recreates startup_options_ after changes to the environment.
  void ReinitStartupOptions() {
    startup_options_ = std::make_unique<FakeStartupOptions>();
  }

 private:
  std::unique_ptr<WorkspaceLayout> workspace_layout_;

 protected:
  std::unique_ptr<StartupOptions> startup_options_;

 private:
  std::string old_home_;
  std::string old_test_tmpdir_;
};

TEST_F(StartupOptionsTest, ProductName) {
  ASSERT_EQ("Bazel", startup_options_->product_name);
}

TEST_F(StartupOptionsTest, JavaLoggingOptions) {
  ASSERT_EQ("com.google.devtools.build.lib.util.SingleLineFormatter",
            startup_options_->java_logging_formatter);
}

TEST_F(StartupOptionsTest, OutputUserRootTildeExpansion) {
#if defined(_WIN32)
  std::string home_str = "C:/nonexistent/home/";
#else
  std::string home_str = "/nonexistent/home/";
#endif

  SetEnv("HOME", home_str);

  blaze_util::Path home(home_str);

  std::string error;

  {
    const std::vector<RcStartupFlag> flags{
        RcStartupFlag("somewhere", "--output_user_root=~/test"),
    };

    const blaze_exit_code::ExitCode ec =
        startup_options_->ProcessArgs(flags, &error);

    ASSERT_EQ(blaze_exit_code::SUCCESS, ec)
        << "ProcessArgs failed with error " << error;

    EXPECT_EQ(home.GetRelative("test"), startup_options_->output_user_root);
  }

  {
    const std::vector<RcStartupFlag> flags{
        RcStartupFlag("somewhere", "--output_user_root=~"),
    };

    const blaze_exit_code::ExitCode ec =
        startup_options_->ProcessArgs(flags, &error);

    ASSERT_EQ(blaze_exit_code::SUCCESS, ec)
        << "ProcessArgs failed with error " << error;

    EXPECT_EQ(home, startup_options_->output_user_root);
  }
}

TEST_F(StartupOptionsTest, EmptyFlagsAreInvalidTest) {
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

TEST_F(StartupOptionsTest, ProcessSpaceSeparatedArgsTest) {
  std::string error;
  const std::vector<RcStartupFlag> flags{
      RcStartupFlag("somewhere", "--max_idle_secs"),
      RcStartupFlag("somewhere", "42")};

  const blaze_exit_code::ExitCode ec =
      startup_options_->ProcessArgs(flags, &error);
  ASSERT_EQ(blaze_exit_code::SUCCESS, ec)
      << "ProcessArgs failed with error " << error;
  EXPECT_EQ(42, startup_options_->max_idle_secs);

  EXPECT_EQ("somewhere", startup_options_->original_startup_options_[0].source);
  EXPECT_EQ("--max_idle_secs=42",
            startup_options_->original_startup_options_[0].value);
}

TEST_F(StartupOptionsTest, ProcessEqualsSeparatedArgsTest) {
  std::string error;
  const std::vector<RcStartupFlag> flags{
      RcStartupFlag("somewhere", "--max_idle_secs=36")};

  const blaze_exit_code::ExitCode ec =
      startup_options_->ProcessArgs(flags, &error);
  ASSERT_EQ(ec, blaze_exit_code::SUCCESS)
      << "ProcessArgs failed with error " << error;
  EXPECT_EQ(36, startup_options_->max_idle_secs);

  EXPECT_EQ("somewhere", startup_options_->original_startup_options_[0].source);
  EXPECT_EQ("--max_idle_secs=36",
            startup_options_->original_startup_options_[0].value);
}

TEST_F(StartupOptionsTest, ProcessIncorrectArgValueTest) {
  std::string error;
  const std::vector<RcStartupFlag> flags{
      RcStartupFlag("somewhere", "--max_idle_secs=notANumber")};

  const blaze_exit_code::ExitCode ec =
      startup_options_->ProcessArgs(flags, &error);
  ASSERT_EQ(blaze_exit_code::BAD_ARGV, ec)
      << "ProcessArgs failed with the wrong error " << error;

  // Even for a failing args processing step, expect the original value
  // to be stored.
  EXPECT_EQ("somewhere", startup_options_->original_startup_options_[0].source);
  EXPECT_EQ("--max_idle_secs=notANumber",
            startup_options_->original_startup_options_[0].value);
}

TEST_F(StartupOptionsTest, ProcessArgsWithMultipleArgstest) {
  const std::vector<RcStartupFlag> flags{
      RcStartupFlag("somewhere", "--max_idle_secs=36"),
      RcStartupFlag("somewhereElse", "--nowrite_command_log")};

  std::string error;
  const blaze_exit_code::ExitCode ec =
      startup_options_->ProcessArgs(flags, &error);
  ASSERT_EQ(ec, blaze_exit_code::SUCCESS)
      << "ProcessArgs failed with error " << error;
  EXPECT_EQ(36, startup_options_->max_idle_secs);
  EXPECT_FALSE(startup_options_->write_command_log);

  EXPECT_EQ("somewhere", startup_options_->original_startup_options_[0].source);
  EXPECT_EQ("--max_idle_secs=36",
            startup_options_->original_startup_options_[0].value);

  EXPECT_EQ("somewhereElse",
            startup_options_->original_startup_options_[1].source);
  EXPECT_EQ("--nowrite_command_log",
            startup_options_->original_startup_options_[1].value);
}

}  // namespace blaze
