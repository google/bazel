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

#include "src/main/cpp/option_processor.h"

#include <memory>

#include "src/main/cpp/bazel_startup_options.h"
#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/option_processor-internal.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/workspace_layout.h"
#include "googletest/include/gtest/gtest.h"

namespace blaze {

class OptionProcessorTest : public ::testing::Test {
 protected:
  OptionProcessorTest()
      : workspace_(
            blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"), "testdir")),
        cwd_("cwd"),
        workspace_layout_(new WorkspaceLayout()) {}

  ~OptionProcessorTest() override {}

  void SetUp() override {
    ASSERT_TRUE(blaze_util::MakeDirectories(workspace_, 0755));
    option_processor_.reset(new OptionProcessor(
        workspace_layout_.get(), std::make_unique<BazelStartupOptions>()));
  }

  void TearDown() override {
    blaze_util::RemoveRecursively(blaze_util::Path(workspace_));
  }

  void FailedSplitCommandLineTest(const std::vector<std::string>& args,
                                  const std::string& expected_error) const {
    std::string error;
    const std::unique_ptr<CommandLine> result =
        option_processor_->SplitCommandLine(args, &error);
    ASSERT_EQ(expected_error, error);
    ASSERT_EQ(nullptr, result);
  }

  void SuccessfulSplitCommandLineTest(const std::vector<std::string>& args,
                                      const CommandLine& expected) const {
    std::string error;
    const std::unique_ptr<CommandLine> result =
        option_processor_->SplitCommandLine(args, &error);

    ASSERT_EQ("", error);
    EXPECT_EQ(expected.path_to_binary, result->path_to_binary);
    EXPECT_EQ(expected.startup_args, result->startup_args);
    EXPECT_EQ(expected.command, result->command);
    EXPECT_EQ(expected.command_args, result->command_args);
  }

  void HelpArgIsInterpretedAsACommand(const std::string& arg) {
    const std::vector<std::string> args = {"bazel", arg};
    std::string error;
    ASSERT_EQ(blaze_exit_code::SUCCESS,
              option_processor_->ParseOptions(args, workspace_, cwd_, &error))
        << error;
    ASSERT_EQ("", error);

    EXPECT_EQ(arg, option_processor_->GetCommand());
    EXPECT_EQ(std::vector<std::string>({}),
              option_processor_->GetExplicitCommandArguments());
  }

  const std::string workspace_;
  const std::string cwd_;
  const std::unique_ptr<WorkspaceLayout> workspace_layout_;
  std::unique_ptr<OptionProcessor> option_processor_;
};

TEST_F(OptionProcessorTest, CanParseOptions) {
  const std::vector<std::string> args = {"bazel",
                                         "--ignore_all_rc_files",
                                         "--host_jvm_args=MyParam",
                                         "--nobatch",
                                         "command",
                                         "--flag",
                                         "//my:target",
                                         "--flag2=42"};
  std::string error;
  ASSERT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error))
      << error;

  ASSERT_EQ("", error);
#if defined(_WIN32) || defined(__CYGWIN__)
  ASSERT_EQ(size_t(2),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  const std::string win_unix_root("-Dbazel.windows_unix_root=");
  const std::string host_jvm_args_0 =
      option_processor_->GetParsedStartupOptions()->host_jvm_args[0];
  EXPECT_EQ(host_jvm_args_0.find(win_unix_root), 0) << host_jvm_args_0;
  EXPECT_GT(host_jvm_args_0.size(), win_unix_root.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[1]);
#else   // ! (defined(_WIN32) || defined(__CYGWIN__))
  ASSERT_EQ(size_t(1),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[0]);
#endif  // defined(_WIN32) || defined(__CYGWIN__)
  EXPECT_FALSE(option_processor_->GetParsedStartupOptions()->batch);

  EXPECT_EQ("command", option_processor_->GetCommand());

  EXPECT_EQ(std::vector<std::string>({"--flag", "//my:target", "--flag2=42"}),
            option_processor_->GetExplicitCommandArguments());
}

TEST_F(OptionProcessorTest, CanParseHelpCommandSurroundedByOtherArgs) {
  const std::vector<std::string> args = {"bazel",
                                         "--ignore_all_rc_files",
                                         "--host_jvm_args=MyParam",
                                         "--nobatch",
                                         "help",
                                         "--flag",
                                         "//my:target",
                                         "--flag2=42"};
  std::string error;
  ASSERT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error))
      << error;

  ASSERT_EQ("", error);
#if defined(_WIN32) || defined(__CYGWIN__)
  ASSERT_EQ(size_t(2),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  const std::string win_unix_root("-Dbazel.windows_unix_root=");
  const std::string host_jvm_args_0 =
      option_processor_->GetParsedStartupOptions()->host_jvm_args[0];
  EXPECT_EQ(host_jvm_args_0.find(win_unix_root), 0) << host_jvm_args_0;
  EXPECT_GT(host_jvm_args_0.size(), win_unix_root.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[1]);
#else   // ! (defined(_WIN32) || defined(__CYGWIN__))
  ASSERT_EQ(size_t(1),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[0]);
#endif  // defined(_WIN32) || defined(__CYGWIN__)
  EXPECT_FALSE(option_processor_->GetParsedStartupOptions()->batch);

  EXPECT_EQ("help", option_processor_->GetCommand());

  EXPECT_EQ(std::vector<std::string>({"--flag", "//my:target", "--flag2=42"}),
            option_processor_->GetExplicitCommandArguments());
}

TEST_F(OptionProcessorTest, CanParseHelpCommand) {
  HelpArgIsInterpretedAsACommand("help");
}

TEST_F(OptionProcessorTest, CanParseHelpShortFlag) {
  HelpArgIsInterpretedAsACommand("-h");
}

TEST_F(OptionProcessorTest, CanParseHelpFlag) {
  HelpArgIsInterpretedAsACommand("-help");
}

TEST_F(OptionProcessorTest, CanParseEmptyArgs) {
  const std::vector<std::string> args = {"bazel"};
  std::string error;
  ASSERT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error))
                << error;
  ASSERT_EQ("", error);

  EXPECT_EQ("", option_processor_->GetCommand());

  EXPECT_EQ(std::vector<std::string>({}),
            option_processor_->GetExplicitCommandArguments());
}

TEST_F(OptionProcessorTest, CanParseDifferentStartupArgs) {
  const std::vector<std::string> args = {"bazel",
                                         "--nobatch",
                                         "--ignore_all_rc_files",
                                         "--host_jvm_args=MyParam",
                                         "--host_jvm_args",
                                         "42"};
  std::string error;
  ASSERT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error))
                << error;
  ASSERT_EQ("", error);

#if defined(_WIN32) || defined(__CYGWIN__)
  ASSERT_EQ(size_t(3),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  const std::string win_unix_root("-Dbazel.windows_unix_root=");
  const std::string host_jvm_args_0 =
      option_processor_->GetParsedStartupOptions()->host_jvm_args[0];
  EXPECT_EQ(host_jvm_args_0.find(win_unix_root), 0) << host_jvm_args_0;
  EXPECT_GT(host_jvm_args_0.size(), win_unix_root.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[1]);
  EXPECT_EQ("42",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[2]);
#else   // ! (defined(_WIN32) || defined(__CYGWIN__))
  ASSERT_EQ(size_t(2),
            option_processor_->GetParsedStartupOptions()->host_jvm_args.size());
  EXPECT_EQ("MyParam",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[0]);
  EXPECT_EQ("42",
            option_processor_->GetParsedStartupOptions()->host_jvm_args[1]);
#endif  // defined(_WIN32) || defined(__CYGWIN__)

  EXPECT_EQ("", option_processor_->GetCommand());

  EXPECT_EQ(std::vector<std::string>({}),
            option_processor_->GetExplicitCommandArguments());
}

TEST_F(OptionProcessorTest, SplitCommandLineWithEmptyArgs) {
  FailedSplitCommandLineTest({}, "Unable to split command line, args is empty");
}

TEST_F(OptionProcessorTest, SplitCommandLineWithAllParams) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "--ignore_all_rc_files", "build", "--bar", ":mytarget"},
      CommandLine("bazel", {"--ignore_all_rc_files"}, "build",
                  {"--bar", ":mytarget"}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithAbsolutePathToBinary) {
  SuccessfulSplitCommandLineTest(
      {"mybazel", "build", ":mytarget"},
      CommandLine("mybazel", {}, "build", {":mytarget"}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithUnaryStartupWithEquals) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "--bazelrc=foo", "build", ":mytarget"},
      CommandLine("bazel", {"--bazelrc=foo"}, "build", {":mytarget"}));
}

TEST_F(OptionProcessorTest,
       SplitCommandLineWithUnaryStartupWithoutEquals) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "--bazelrc", "foo", "build", ":mytarget"},
      CommandLine("bazel", {"--bazelrc=foo"}, "build", {":mytarget"}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithIncompleteUnaryOption) {
  FailedSplitCommandLineTest(
      {"bazel", "--bazelrc"},
      "Startup option '--bazelrc' expects a value.\n"
      "Usage: '--bazelrc=somevalue' or '--bazelrc somevalue'.\n"
      "  For more info, run 'bazel help startup_options'.");
}

TEST_F(OptionProcessorTest, SplitCommandLineWithMultipleStartup) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "--bazelrc", "foo", "--ignore_all_rc_files", "build",
       ":mytarget"},
      CommandLine("bazel", {"--bazelrc=foo", "--ignore_all_rc_files"}, "build",
                  {":mytarget"}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithNoStartupArgs) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "build", ":mytarget"},
      CommandLine("bazel", {}, "build", {":mytarget"}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithNoCommandArgs) {
  SuccessfulSplitCommandLineTest({"bazel", "build"},
                                 CommandLine("bazel", {}, "build", {}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithBlazeHelp) {
  SuccessfulSplitCommandLineTest({"bazel", "help"},
                                 CommandLine("bazel", {}, "help", {}));

  SuccessfulSplitCommandLineTest({"bazel", "-h"},
                                 CommandLine("bazel", {}, "-h", {}));

  SuccessfulSplitCommandLineTest({"bazel", "-help"},
                                 CommandLine("bazel", {}, "-help", {}));

  SuccessfulSplitCommandLineTest({"bazel", "--help"},
                                 CommandLine("bazel", {}, "--help", {}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithBlazeVersion) {
  SuccessfulSplitCommandLineTest({"bazel", "version"},
                                 CommandLine("bazel", {}, "version", {}));
}

TEST_F(OptionProcessorTest, SplitCommandLineWithMultipleCommandArgs) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "build", "--foo", "-s", ":mytarget"},
      CommandLine("bazel", {}, "build", {"--foo", "-s", ":mytarget"}));
}

TEST_F(OptionProcessorTest,
       SplitCommandLineFailsWithDashDashInStartupArgs) {
  FailedSplitCommandLineTest(
      {"bazel", "--"},
      "Unknown startup option: '--'.\n"
      "  For more info, run 'bazel help startup_options'.");
}

TEST_F(OptionProcessorTest, SplitCommandLineWithDashDash) {
  SuccessfulSplitCommandLineTest(
      {"bazel", "--ignore_all_rc_files", "build", "--b", "--", ":mytarget"},
      CommandLine("bazel", {"--ignore_all_rc_files"}, "build",
                  {"--b", "--", ":mytarget"}));
}

TEST_F(OptionProcessorTest, TestDedupePathsOmitsInvalidPath) {
  std::vector<std::string> input = {"foo"};
  std::vector<std::string> expected = {};
  ASSERT_EQ(expected, internal::DedupeBlazercPaths(input));
}

TEST_F(OptionProcessorTest, TestDedupePathsOmitsEmptyPath) {
  std::vector<std::string> input = {""};
  std::vector<std::string> expected = {};
  ASSERT_EQ(expected, internal::DedupeBlazercPaths(input));
}

TEST_F(OptionProcessorTest, TestDedupePathsWithDifferentFiles) {
  std::string foo_path = blaze_util::JoinPath(workspace_, "foo");
  std::string bar_path = blaze_util::JoinPath(workspace_, "bar");

  ASSERT_TRUE(blaze_util::WriteFile("foo", foo_path));
  ASSERT_TRUE(blaze_util::WriteFile("bar", bar_path));

  std::vector<std::string> input = {foo_path, bar_path};
  ASSERT_EQ(input, internal::DedupeBlazercPaths(input));
}

TEST_F(OptionProcessorTest, TestDedupePathsWithSameFile) {
  std::string foo_path = blaze_util::JoinPath(workspace_, "foo");

  ASSERT_TRUE(blaze_util::WriteFile("foo", foo_path));

  std::vector<std::string> input = {foo_path, foo_path};
  std::vector<std::string> expected = {foo_path};
  ASSERT_EQ(expected, internal::DedupeBlazercPaths(input));
}

TEST_F(OptionProcessorTest, TestDedupePathsWithRelativePath) {
  std::string dir(blaze_util::JoinPath(workspace_, "dir"));
  std::string foo_path(blaze_util::JoinPath(dir, "foo"));
  std::string relative_foo_path(blaze_util::JoinPath(dir, "../dir/foo"));

  ASSERT_TRUE(blaze_util::MakeDirectories(dir, 0755));
  ASSERT_TRUE(blaze_util::WriteFile("foo", foo_path));

  std::vector<std::string> input = {foo_path, relative_foo_path};
  std::vector<std::string> expected = {foo_path};
  ASSERT_EQ(expected, internal::DedupeBlazercPaths(input));
}

#if !defined(_WIN32) && !defined(__CYGWIN__)
static bool Symlink(const std::string& old_path, const std::string& new_path) {
  return symlink(old_path.c_str(), new_path.c_str()) == 0;
}

TEST_F(OptionProcessorTest, TestDedupePathsWithSymbolicLink) {
  std::string foo_path = blaze_util::JoinPath(workspace_, "foo");
  std::string sym_foo_path = blaze_util::JoinPath(workspace_, "sym_foo");

  ASSERT_TRUE(blaze_util::WriteFile("foo", foo_path));
  ASSERT_TRUE(Symlink(foo_path, sym_foo_path));
  std::vector<std::string> input = {foo_path, sym_foo_path};
  std::vector<std::string> expected = {foo_path};
  ASSERT_EQ(expected, internal::DedupeBlazercPaths(input));
}


TEST_F(OptionProcessorTest,
       SplitCommandLineFailsWithDeprecatedOptionInStartupArgs) {
  FailedSplitCommandLineTest(
      {"bazel", "--nomaster_bazelrc"},
      "Unknown startup option: '--nomaster_bazelrc'.\n"
      "  For more info, run 'bazel help startup_options'.");
}
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

}  // namespace blaze
