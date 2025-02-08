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

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

#include <memory>
#include <string>
#include <vector>

#include "src/main/cpp/bazel_startup_options.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/option_processor-internal.h"
#include "src/main/cpp/option_processor.h"
#include "src/main/cpp/rc_file.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/workspace_layout.h"
#include "googlemock/include/gmock/gmock.h"
#include "googletest/include/gtest/gtest.h"

namespace blaze {
using ::testing::ContainsRegex;
using ::testing::ElementsAre;
using ::testing::HasSubstr;
using ::testing::IsEmpty;
using ::testing::MatchesRegex;
using ::testing::Pointee;

#if defined(_WIN32) || defined(__CYGWIN__)
constexpr const char* kNullDevice = "NUL";
#else  // Assume POSIX if not Windows.
constexpr const char* kNullDevice = "/dev/null";
#endif

// Matches an RcFile's canonical source paths list.
MATCHER_P(CanonicalSourcePathsAre, paths_matcher, "") {
  return ExplainMatchResult(ElementsAre(paths_matcher),
                            arg.canonical_source_paths(), result_listener);
}

class RcFileTest : public ::testing::Test {
 protected:
  RcFileTest()
      : workspace_(blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"),
                                        "workspace")),
        cwd_(blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"), "cwd")),
        home_(blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"), "home")),
        binary_dir_(
            blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"), "bazeldir")),
        binary_path_(blaze_util::JoinPath(binary_dir_, "bazel")),
        workspace_layout_(new WorkspaceLayout()) {}

  void SetUp() override {
    ASSERT_TRUE(blaze_util::MakeDirectories(workspace_, 0755));
    ASSERT_TRUE(blaze_util::MakeDirectories(cwd_, 0755));
    ASSERT_TRUE(blaze_util::ChangeDirectory(cwd_));
    ASSERT_TRUE(blaze_util::MakeDirectories(home_, 0755));
#if defined(_WIN32)
    ASSERT_NE(::SetEnvironmentVariable("HOME", home_.c_str()), 0);
#else
    ASSERT_EQ(setenv("HOME", home_.c_str(), 1), 0);
#endif
#if defined(_WIN32) || defined(__CYGWIN__)
    // GetCwd returns a short path on Windows, so we store this expectation now
    // to keep assertions sane in the tests.
    std::string short_cwd;
    std::string error;
    ASSERT_TRUE(blaze_util::AsShortWindowsPath(cwd_, &short_cwd, &error))
        << error;
    cwd_ = short_cwd;

#endif

    ASSERT_TRUE(blaze_util::MakeDirectories(binary_dir_, 0755));
    option_processor_.reset(new OptionProcessor(
        workspace_layout_.get(), std::make_unique<BazelStartupOptions>(),
        "bazel.bazelrc"));
  }

  void TearDown() override {
    blaze_util::RemoveRecursively(blaze_util::Path(workspace_));
    blaze_util::RemoveRecursively(blaze_util::Path(cwd_));
    blaze_util::RemoveRecursively(blaze_util::Path(home_));
    blaze_util::RemoveRecursively(blaze_util::Path(binary_dir_));
  }

  bool SetUpSystemRcFile(const std::string& contents,
                         std::string* rcfile_path) const {
    const std::string system_rc_path =
        blaze_util::ConvertPath(blaze_util::JoinPath(cwd_, "bazel.bazelrc"));

    if (blaze_util::WriteFile(contents, system_rc_path, 0755)) {
      *rcfile_path = blaze_util::MakeCanonical(system_rc_path.c_str());
      return true;
    }
    return false;
  }

  bool SetUpWorkspaceRcFile(const std::string& contents,
                            std::string* rcfile_path) const {
    const std::string workspace_user_rc_path =
        blaze_util::JoinPath(workspace_, ".bazelrc");
    if (blaze_util::WriteFile(contents, workspace_user_rc_path, 0755)) {
      *rcfile_path =  blaze_util::MakeCanonical(workspace_user_rc_path.c_str());
      return true;
    }
    return false;
  }

  bool SetUpHomeRcFile(const std::string& contents,
                       std::string* rcfile_path) const {
    const std::string home_rc_path = blaze_util::JoinPath(home_, ".bazelrc");
    if (blaze_util::WriteFile(contents, home_rc_path, 0755)) {
      *rcfile_path = blaze_util::MakeCanonical(home_rc_path.c_str());
      return true;
    }
    return false;
  }

  bool SetUpLegacyMasterRcFileInWorkspace(const std::string& contents,
                                          std::string* rcfile_path) const {
    const std::string tools_dir = blaze_util::JoinPath(workspace_, "tools");
    const std::string workspace_rc_path =
        blaze_util::JoinPath(tools_dir, "bazel.rc");
    if (blaze_util::MakeDirectories(tools_dir, 0755) &&
        blaze_util::WriteFile(contents, workspace_rc_path, 0755)) {
      *rcfile_path = workspace_rc_path;
      return true;
    }
    return false;
  }

  bool SetUpLegacyMasterRcFileAlongsideBinary(const std::string& contents,
                                              std::string* rcfile_path) const {
    const std::string binary_rc_path =
        blaze_util::JoinPath(binary_dir_, "bazel.bazelrc");
    if (blaze_util::WriteFile(contents, binary_rc_path, 0755)) {
      *rcfile_path = binary_rc_path;
      return true;
    }
    return false;
  }

  const std::string workspace_;
  std::string cwd_;
  const std::string home_;
  const std::string binary_dir_;
  const std::string binary_path_;
  const std::unique_ptr<WorkspaceLayout> workspace_layout_;
  const std::string old_system_bazelrc_path_;
  std::unique_ptr<OptionProcessor> option_processor_;
};

using GetRcFileTest = RcFileTest;

TEST_F(GetRcFileTest, GetRcFilesLoadsAllDefaultBazelrcs) {
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("", &system_rc));
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("", &workspace_rc));
  std::string home_rc;
  ASSERT_TRUE(SetUpHomeRcFile("", &home_rc));

  const CommandLine cmd_line = CommandLine(binary_path_, {}, "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  // There should be 2 rc files: the system one and the workspace one. --bazelrc
  // is not passed and therefore is not relevant.
  EXPECT_THAT(parsed_rcs,
              ElementsAre(Pointee(CanonicalSourcePathsAre(system_rc)),
                          Pointee(CanonicalSourcePathsAre(workspace_rc)),
                          Pointee(CanonicalSourcePathsAre(home_rc))));
}

TEST_F(GetRcFileTest, GetRcFilesRespectsNoSystemRc) {
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("", &system_rc));
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("", &workspace_rc));
  std::string home_rc;
  ASSERT_TRUE(SetUpHomeRcFile("", &home_rc));

  const CommandLine cmd_line =
      CommandLine(binary_path_, {"--nosystem_rc"}, "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  EXPECT_THAT(parsed_rcs,
              ElementsAre(Pointee(CanonicalSourcePathsAre(workspace_rc)),
                          Pointee(CanonicalSourcePathsAre(home_rc))));
}

TEST_F(GetRcFileTest, GetRcFilesRespectsNoWorkspaceRc) {
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("", &system_rc));
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("", &workspace_rc));
  std::string home_rc;
  ASSERT_TRUE(SetUpHomeRcFile("", &home_rc));

  const CommandLine cmd_line =
      CommandLine(binary_path_, {"--noworkspace_rc"}, "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  EXPECT_THAT(parsed_rcs,
              ElementsAre(Pointee(CanonicalSourcePathsAre(system_rc)),
                          Pointee(CanonicalSourcePathsAre(home_rc))));
}

TEST_F(GetRcFileTest,
       GetRcFilesRespectsNoWorkspaceRcAndNoSystemAndNoHomeRcCombined) {
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("", &system_rc));
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("", &workspace_rc));
  std::string home_rc;
  ASSERT_TRUE(SetUpHomeRcFile("", &home_rc));

  const CommandLine cmd_line = CommandLine(
      binary_path_, {"--noworkspace_rc", "--nosystem_rc", "--nohome_rc"},
      "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  EXPECT_THAT(parsed_rcs, IsEmpty());
}

TEST_F(GetRcFileTest, GetRcFilesWarnsAboutIgnoredMasterRcFiles) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpLegacyMasterRcFileInWorkspace("", &workspace_rc));
  std::string binary_rc;
  ASSERT_TRUE(SetUpLegacyMasterRcFileAlongsideBinary("", &binary_rc));

  const CommandLine cmd_line = CommandLine(binary_path_, {}, "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;

  testing::internal::CaptureStderr();
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  // Expect that GetRcFiles outputs a warning about these files that are not
  // read as expected.
  EXPECT_THAT(output,
              HasSubstr("The following rc files are no longer being read"));
  EXPECT_THAT(output, HasSubstr(workspace_rc));
  EXPECT_THAT(output, HasSubstr(binary_rc));
}

TEST_F(GetRcFileTest, GetRcFilesReadsCommandLineRc) {
  const std::string cmdline_rc_path =
      blaze_util::JoinPath(workspace_, "mybazelrc");
  ASSERT_TRUE(
      blaze_util::MakeDirectories(blaze_util::Dirname(cmdline_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("", cmdline_rc_path, 0755));

  const CommandLine cmd_line = CommandLine(
      binary_path_, {"--bazelrc=" + cmdline_rc_path},
      "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);

  // Because of the variety of path representations in windows, this
  // equality test does not attempt to check the entire path.
  ASSERT_EQ(1, parsed_rcs.size());
  ASSERT_EQ(1, parsed_rcs[0]->canonical_source_paths().size());
  EXPECT_THAT(parsed_rcs[0]->canonical_source_paths()[0],
              HasSubstr("mybazelrc"));
}

TEST_F(GetRcFileTest, GetRcFilesAcceptsNullCommandLineRc) {
  const CommandLine cmd_line =
      CommandLine(binary_path_,
                  {"--nosystem_rc", "--noworkspace_rc", "--nohome_rc",
                   "--bazelrc=/dev/null"},
                  "build", {});
  std::string error = "check that this string is not modified";
  std::vector<std::unique_ptr<RcFile>> parsed_rcs;
  const blaze_exit_code::ExitCode exit_code =
      option_processor_->GetRcFiles(workspace_layout_.get(), workspace_, cwd_,
                                    &cmd_line, &parsed_rcs, &error);
  // /dev/null is not an error
  EXPECT_EQ(blaze_exit_code::SUCCESS, exit_code);
  EXPECT_EQ("check that this string is not modified", error);
  // but it does technically count as a file
  EXPECT_THAT(parsed_rcs,
              ElementsAre(Pointee(CanonicalSourcePathsAre(kNullDevice))));
}

class ParseOptionsTest : public RcFileTest {
 protected:
  void ParseOptionsAndCheckOutput(
      const std::vector<std::string>& args,
      const blaze_exit_code::ExitCode expected_exit_code,
      const std::string& expected_error_regex,
      const std::string& expected_output_regex) {
    std::string error;
    testing::internal::CaptureStderr();
    const blaze_exit_code::ExitCode exit_code =
        option_processor_->ParseOptions(args, workspace_, cwd_, &error);
    const std::string output = testing::internal::GetCapturedStderr();

    ASSERT_EQ(expected_exit_code, exit_code) << error;
    ASSERT_THAT(error, ContainsRegex(expected_error_regex));
    ASSERT_THAT(output, ContainsRegex(expected_output_regex));
  }
};

TEST_F(ParseOptionsTest, IgnoreAllRcFilesIgnoresAllMasterAndUserRcFiles) {
  // Put fake options in different expected rc files, to check that none of them
  // are read.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("startup --workspacefoo", &workspace_rc));
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("startup --systemfoo", &system_rc));
  const std::string cmdline_rc_path =
      blaze_util::JoinPath(workspace_, "mybazelrc");
  ASSERT_TRUE(
      blaze_util::MakeDirectories(blaze_util::Dirname(cmdline_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("startup --myfoo", cmdline_rc_path, 0755));

  const std::vector<std::string> args = {binary_path_, "--ignore_all_rc_files",
                                         "build"};
  // Expect no error due to the incorrect options, as non of them should have
  // been loaded.
  std::string error;
  EXPECT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error));
  ASSERT_EQ("", error);

  // Check that the startup options' provenance message contains nothing
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_EQ(output, "");
}

TEST_F(ParseOptionsTest, LaterIgnoreAllRcFilesValueWins) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("startup --workspacefoo", &workspace_rc));

  const std::vector<std::string> args = {binary_path_, "--ignore_all_rc_files",
                                         "--noignore_all_rc_files", "build"};
  std::string error;
  EXPECT_EQ(blaze_exit_code::BAD_ARGV,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error));
  ASSERT_EQ(
      "Unknown startup option: '--workspacefoo'.\n  For more info, run "
      "'bazel help startup_options'.",
      error);

  // Check that the startup options' provenance message contains the provenance
  // of the incorrect option.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(
      output,
      MatchesRegex("INFO: Reading 'startup' options from .*workspace.*bazelrc: "
                   "--workspacefoo\n"));
}

TEST_F(ParseOptionsTest, IgnoreAllRcFilesIgnoresCommandLineRcFileToo) {
  // Put fake options in different expected rc files, to check that none of them
  // are read.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("startup --workspacefoo", &workspace_rc));
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("startup --systemfoo", &system_rc));

  const std::string cmdline_rc_path =
      blaze_util::JoinPath(workspace_, "mybazelrc");
  ASSERT_TRUE(
      blaze_util::MakeDirectories(blaze_util::Dirname(cmdline_rc_path), 0755));
  ASSERT_TRUE(
      blaze_util::WriteFile("startup --cmdlinefoo", cmdline_rc_path, 0755));

  const std::vector<std::string> args = {binary_path_, "--ignore_all_rc_files",
                                         "--bazelrc=" + cmdline_rc_path,
                                         "build"};
  // Expect no error due to the incorrect options, as non of them should have
  // been loaded.
  std::string error;
  EXPECT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error));
  ASSERT_EQ("", error);

  // Check that the startup options' provenance message contains nothing
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_EQ(output, "");
}

TEST_F(ParseOptionsTest, CommandLineBazelrcHasUnknownOption) {
  const std::string cmdline_rc_path =
      blaze_util::JoinPath(workspace_, "mybazelrc");
  ASSERT_TRUE(
      blaze_util::MakeDirectories(blaze_util::Dirname(cmdline_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("startup --foo", cmdline_rc_path, 0755));

  const std::vector<std::string> args = {binary_path_,
                                         "--bazelrc=" + cmdline_rc_path,
                                         "build"};
  const std::string expected_error =
      "Unknown startup option: '--foo'.\n"
      "  For more info, run 'bazel help startup_options'.";
  std::string error;
  ASSERT_NE(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error))
      << error;
  ASSERT_EQ(expected_error, error);

  // Check that the startup options' provenance message contains the correct
  // information for the incorrect flag, and does not print the command-line
  // provided startup flags.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(output,
              MatchesRegex(
                  "INFO: Reading 'startup' options from .*mybazelrc: --foo\n"));
}

TEST_F(ParseOptionsTest, BazelrcHasUnknownOption) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("startup --foo", &workspace_rc));

  const std::vector<std::string> args = {binary_path_, "build"};

  // Expect no error due to the incorrect --foo.
  std::string error;
  EXPECT_EQ(blaze_exit_code::BAD_ARGV,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error));
  ASSERT_EQ(
      "Unknown startup option: '--foo'.\n"
      "  For more info, run 'bazel help startup_options'.",
      error);

  // Check that the startup options' provenance message contains nothing for the
  // master bazelrc.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(output, MatchesRegex("INFO: Reading 'startup' options from "
                                   ".*workspace.*bazelrc: --foo\n"));
}

TEST_F(ParseOptionsTest,
       IncorrectWorkspaceBazelrcIgnoredWhenNoWorkspaceRcIsPresent) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("startup --foo", &workspace_rc));

  const std::vector<std::string> args = {binary_path_, "--noworkspace_rc",
                                         "build"};

  // Expect no error due to the incorrect --foo.
  std::string error;
  EXPECT_EQ(blaze_exit_code::SUCCESS,
            option_processor_->ParseOptions(args, workspace_, cwd_, &error));
  ASSERT_EQ("", error);

  // Check that the startup options' provenance message contains nothing for the
  // master bazelrc.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_EQ(output, "");
}

TEST_F(ParseOptionsTest, PositiveOptionOverridesNegativeOption) {
  std::string workspace_rc;
  ASSERT_TRUE(
      SetUpWorkspaceRcFile("startup --max_idle_secs=123", &workspace_rc));

  const std::vector<std::string> args = {"bazel", "--noworkspace_rc",
                                         "--workspace_rc", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");

  EXPECT_EQ(123, option_processor_->GetParsedStartupOptions()->max_idle_secs);

  // Check that the startup options' provenance message contains the correct
  // information for the master bazelrc.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(output,
              MatchesRegex("INFO: Reading 'startup' options from "
                           ".*workspace.*bazelrc: --max_idle_secs=123\n"));
}

TEST_F(ParseOptionsTest, MultipleStartupArgsInMasterBazelrcWorksCorrectly) {
  // Add startup flags to the master bazelrc.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile(
      "startup --max_idle_secs=42\nstartup --io_nice_level=6", &workspace_rc));

  const std::vector<std::string> args = {binary_path_, "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");

  EXPECT_EQ(42, option_processor_->GetParsedStartupOptions()->max_idle_secs);
  EXPECT_EQ(6, option_processor_->GetParsedStartupOptions()->io_nice_level);

  // Check that the startup options get grouped together properly in the output
  // message.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(
      output,
      MatchesRegex("INFO: Reading 'startup' options from .*workspace.*bazelrc: "
                   "--max_idle_secs=42 --io_nice_level=6\n"));
}

TEST_F(ParseOptionsTest, CommandLineBazelrcHasPriorityOverDefaultBazelrc) {
  // Add startup flags to the workspace bazelrc.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile(
      "startup --max_idle_secs=42\nstartup --io_nice_level=6", &workspace_rc));

  // Override one of the master bazelrc's flags in the commandline rc.
  const std::string cmdline_rc_path =
      blaze_util::JoinPath(workspace_, "mybazelrc");
  ASSERT_TRUE(
      blaze_util::MakeDirectories(blaze_util::Dirname(cmdline_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("startup --max_idle_secs=123",
                                    cmdline_rc_path, 0755));

  const std::vector<std::string> args = {
      "bazel", "--bazelrc=" + cmdline_rc_path, "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");

  EXPECT_EQ(123, option_processor_->GetParsedStartupOptions()->max_idle_secs);
  EXPECT_EQ(6, option_processor_->GetParsedStartupOptions()->io_nice_level);

  // Check that the options are reported in the correct order in the provenance
  // message.
  testing::internal::CaptureStderr();
  option_processor_->PrintStartupOptionsProvenanceMessage();
  const std::string output = testing::internal::GetCapturedStderr();

  EXPECT_THAT(
      output,
      MatchesRegex("INFO: Reading 'startup' options from .*workspace.*bazelrc: "
                   "--max_idle_secs=42 --io_nice_level=6\n"
                   "INFO: Reading 'startup' options from .*mybazelrc: "
                   "--max_idle_secs=123\n"));
}

class BlazercImportTest : public ParseOptionsTest {
 protected:
  void TestBazelRcImportsMaintainsFlagOrdering(const std::string& import_type) {
    // Override one of the master bazelrc's flags in the custom bazelrc.
    const std::string imported_rc_path =
        blaze_util::JoinPath(workspace_, "myimportedbazelrc");
    ASSERT_TRUE(blaze_util::MakeDirectories(
        blaze_util::Dirname(imported_rc_path), 0755));
    ASSERT_TRUE(blaze_util::WriteFile(
        "startup --max_idle_secs=123\n"
          "startup --io_nice_level=4",
        imported_rc_path, 0755));

    // Add startup flags the imported bazelrc.
    std::string workspace_rc;
    ASSERT_TRUE(SetUpWorkspaceRcFile(
        "startup --max_idle_secs=42\n" +
          import_type + " " + imported_rc_path + "\n"
          "startup --io_nice_level=6",
        &workspace_rc));

    const std::vector<std::string> args = {"bazel", "build"};
    ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");

    EXPECT_EQ(123, option_processor_->GetParsedStartupOptions()->max_idle_secs);
    EXPECT_EQ(6, option_processor_->GetParsedStartupOptions()->io_nice_level);

    // Check that the options are reported in the correct order in the
    // provenance message, the imported file between the two master flags
    testing::internal::CaptureStderr();
    option_processor_->PrintStartupOptionsProvenanceMessage();
    const std::string output = testing::internal::GetCapturedStderr();

    EXPECT_THAT(
        output,
        MatchesRegex(
            "INFO: Reading 'startup' options from .*workspace.*bazelrc: "
            "--max_idle_secs=42\n"
            "INFO: Reading 'startup' options from .*myimportedbazelrc: "
            "--max_idle_secs=123 --io_nice_level=4\n"
            "INFO: Reading 'startup' options from .*workspace.*bazelrc: "
            "--io_nice_level=6\n"));
  }

  void TestThatDoubleImportsCauseAWarning(const std::string& import_type) {
    const std::string imported_rc_path =
        blaze_util::JoinPath(workspace_, "myimportedbazelrc");
    ASSERT_TRUE(blaze_util::WriteFile("", imported_rc_path, 0755));

    // Import the custom location twice.
    std::string workspace_rc;
    ASSERT_TRUE(SetUpWorkspaceRcFile(
        import_type + " " + imported_rc_path + "\n" +
          import_type + " " + imported_rc_path + "\n",
        &workspace_rc));

    const std::vector<std::string> args = {"bazel", "build"};
    ParseOptionsAndCheckOutput(
        args, blaze_exit_code::SUCCESS, "",
        "WARNING: Duplicate rc file: .*myimportedbazelrc is imported multiple "
        "times from .*workspace.*bazelrc\n");
  }

  void TestThatDoubleImportWithWorkspaceRelativeSyntaxCauseAWarning(
      const std::string& import_type) {
    const std::string imported_rc_path =
        blaze_util::JoinPath(workspace_, "myimportedbazelrc");
    ASSERT_TRUE(blaze_util::WriteFile("", imported_rc_path, 0755));

    // Import the custom location twice.
    std::string workspace_rc;
    ASSERT_TRUE(
        SetUpWorkspaceRcFile(
            import_type + " " + imported_rc_path + "\n" +
              import_type + " %workspace%/myimportedbazelrc\n",
            &workspace_rc));

    const std::vector<std::string> args = {"bazel", "build"};
    ParseOptionsAndCheckOutput(
        args, blaze_exit_code::SUCCESS, "",
        "WARNING: Duplicate rc file: .*myimportedbazelrc is imported multiple "
        "times from .*workspace.*bazelrc\n");
  }

  void TestThatDoubleImportWithExcessPathSyntaxCauseAWarning(
      const std::string& import_type) {
    const std::string imported_rc_path =
        blaze_util::JoinPath(workspace_, "myimportedbazelrc");
    ASSERT_TRUE(blaze_util::WriteFile("", imported_rc_path, 0755));

    // Import the custom location twice.
    std::string workspace_rc;
    ASSERT_TRUE(
        SetUpWorkspaceRcFile(
            import_type + " " + imported_rc_path + "\n" +
              import_type + " %workspace%///.//myimportedbazelrc\n",
            &workspace_rc));

    const std::vector<std::string> args = {"bazel", "build"};
    ParseOptionsAndCheckOutput(
        args, blaze_exit_code::SUCCESS, "",
        "WARNING: Duplicate rc file: .*myimportedbazelrc is imported multiple "
        "times from .*workspace.*bazelrc\n");
  }

  void TestThatDeepDoubleImportCausesAWarning(const std::string& import_type) {
    const std::string dual_imported_rc_path =
        blaze_util::JoinPath(workspace_, "dual_imported.bazelrc");
    ASSERT_TRUE(blaze_util::WriteFile("", dual_imported_rc_path, 0755));

    const std::string intermediate_import_1 =
        blaze_util::JoinPath(workspace_, "intermediate_import_1");
    ASSERT_TRUE(blaze_util::WriteFile(
        import_type + " " + dual_imported_rc_path,
        intermediate_import_1, 0755));

    const std::string intermediate_import_2 =
        blaze_util::JoinPath(workspace_, "intermediate_import_2");
    ASSERT_TRUE(blaze_util::WriteFile(
        import_type + " " + dual_imported_rc_path,
        intermediate_import_2, 0755));

    // Import the custom location twice.
    std::string workspace_rc;
    ASSERT_TRUE(SetUpWorkspaceRcFile(
        import_type + " " + intermediate_import_1 + "\n" +
          import_type + " " + intermediate_import_2 + "\n",
        &workspace_rc));

    const std::vector<std::string> args = {"bazel", "build"};
    ParseOptionsAndCheckOutput(
        args, blaze_exit_code::SUCCESS, "",
        "WARNING: Duplicate rc file: .*dual_imported.bazelrc is imported "
        "multiple times from .*workspace.*bazelrc\n");
  }

  void TestThatImportingAFileAndPassingItInCausesAWarning(
      const std::string& import_type) {
    const std::string imported_rc_path =
        blaze_util::JoinPath(workspace_, "myimportedbazelrc");
    ASSERT_TRUE(blaze_util::WriteFile("", imported_rc_path, 0755));

    // Import the custom location, and pass it in by flag.
    std::string workspace_rc;
    ASSERT_TRUE(
        SetUpWorkspaceRcFile(
            import_type + " " + imported_rc_path,
            &workspace_rc));

    const std::vector<std::string> args = {
        "bazel", "--bazelrc=" + imported_rc_path, "build"};
    ParseOptionsAndCheckOutput(
        args, blaze_exit_code::SUCCESS, "",
        "WARNING: Duplicate rc file: .*myimportedbazelrc is read multiple "
        "times, "
        "it is a standard rc file location but must have been unnecessarily "
        "imported earlier.\n");
  }
};

TEST_F(BlazercImportTest, BazelRcImportFailsForMissingFile) {
  const std::string missing_imported_rc_path =
      blaze_util::JoinPath(workspace_, "myimportedbazelrc");
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("import " + missing_imported_rc_path,
                                   &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(
      args, blaze_exit_code::INTERNAL_ERROR,
      "Unexpected error reading config file '.*myimportedbazelrc'", "");
}

TEST_F(BlazercImportTest, BazelRcTryImportDoesNotFailForMissingFile) {
  const std::string missing_imported_rc_path =
      blaze_util::JoinPath(workspace_, "tryimported.bazelrc");
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("try-import " + missing_imported_rc_path,
                                   &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");
}

// rc_file does not differentiate between non-existent and unreadable files. We
// don't necessarily want try-import to ignore unreadable files, but this test
// exists to make sure we don't change the behavior by accident. Any change that
// makes existent but unreadable files a failure with try-import should inform
// users.
TEST_F(BlazercImportTest, BazelRcTryImportDoesNotFailForUnreadableFile) {
  const std::string unreadable_rc_path =
      blaze_util::JoinPath(workspace_, "tryimported.bazelrc");
  ASSERT_TRUE(blaze_util::WriteFile("startup --max_idle_secs=123",
                                    unreadable_rc_path, 0222));
  std::string workspace_rc;
  ASSERT_TRUE(
      SetUpWorkspaceRcFile("try-import " + unreadable_rc_path, &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");
}

TEST_F(BlazercImportTest, BazelRcImportDoesNotFallBackToLiteralPlaceholder) {
  // Check that we don't fall back to interpreting the %workspace% placeholder
  // literally if an import statement cannot be resolved against it.

  const std::string literal_placeholder_rc_path =
      blaze_util::JoinPath(cwd_, "%workspace%/tryimported.bazelrc");
  ASSERT_TRUE(blaze_util::MakeDirectories(
      blaze_util::Dirname(literal_placeholder_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("import syntax error",
                                    literal_placeholder_rc_path, 0755));

  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("import %workspace%/tryimported.bazelrc",
                                   &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::BAD_ARGV,
                             "Nonexistent path in import declaration in config "
                             "file.*'import %workspace%/tryimported.bazelrc'",
                             "");
}

TEST_F(BlazercImportTest, BazelRcTryImportDoesNotFallBackToLiteralPlaceholder) {
  // Check that we don't fall back to interpreting the %workspace% placeholder
  // literally if a try-import statement cannot be resolved against it.

  const std::string literal_placeholder_rc_path =
      blaze_util::JoinPath(cwd_, "%workspace%/tryimported.bazelrc");
  ASSERT_TRUE(blaze_util::MakeDirectories(
      blaze_util::Dirname(literal_placeholder_rc_path), 0755));
  ASSERT_TRUE(blaze_util::WriteFile("import syntax error",
                                    literal_placeholder_rc_path, 0755));

  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("try-import %workspace%/tryimported.bazelrc",
                                   &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");
}

#if defined(_WIN32)
TEST_F(BlazercImportTest,
       BazelRcTryImportDoesNotFailForInvalidPosixPathOnWindows) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("try-import /mnt/shared/defaults.bazelrc",
                                   &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(args, blaze_exit_code::SUCCESS, "", "");
}
#endif

TEST_F(BlazercImportTest, BazelRcImportsMaintainsFlagOrdering) {
  TestBazelRcImportsMaintainsFlagOrdering("import");
}

TEST_F(BlazercImportTest, BazelRcTryImportsMaintainsFlagOrdering) {
  TestBazelRcImportsMaintainsFlagOrdering("try-import");
}

TEST_F(BlazercImportTest, DoubleImportsCauseAWarning) {
  TestThatDoubleImportsCauseAWarning("import");
}

TEST_F(BlazercImportTest, DoubleTryImportsCauseAWarning) {
  TestThatDoubleImportsCauseAWarning("try-import");
}

TEST_F(BlazercImportTest,
       DoubleImportWithWorkspaceRelativeSyntaxCauseAWarning) {
  TestThatDoubleImportWithWorkspaceRelativeSyntaxCauseAWarning("import");
}

TEST_F(BlazercImportTest,
       DoubleTryImportWithWorkspaceRelativeSyntaxCauseAWarning) {
  TestThatDoubleImportWithWorkspaceRelativeSyntaxCauseAWarning("try-import");
}

TEST_F(BlazercImportTest, DoubleImportWithExcessPathSyntaxCauseAWarning) {
  TestThatDoubleImportWithExcessPathSyntaxCauseAWarning("import");
}

TEST_F(BlazercImportTest, DoubleTryImportWithExcessPathSyntaxCauseAWarning) {
  TestThatDoubleImportWithExcessPathSyntaxCauseAWarning("try-import");
}

// The following tests unix-path semantics.
#if !defined(_WIN32) && !defined(__CYGWIN__)
TEST_F(BlazercImportTest,
       DoubleImportWithEnclosingDirectorySyntaxCauseAWarning) {
  const std::string imported_rc_path =
      blaze_util::JoinPath(workspace_, "myimportedbazelrc");
  ASSERT_TRUE(blaze_util::WriteFile("", imported_rc_path, 0755));

  ASSERT_TRUE(blaze_util::MakeDirectories(
      blaze_util::JoinPath(workspace_, "extra"), 0755));

  // Import the custom location twice.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile(
      "import " + imported_rc_path + "\n"
        "import %workspace%/extra/../myimportedbazelrc\n",
      &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(
      args, blaze_exit_code::SUCCESS, "",
      "WARNING: Duplicate rc file: .*myimportedbazelrc is imported multiple "
      "times from .*workspace.*bazelrc\n");
}
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

TEST_F(BlazercImportTest, DeepDoubleImportCausesAWarning) {
  TestThatDeepDoubleImportCausesAWarning("import");
}

TEST_F(BlazercImportTest, DeepDoubleTryImportCausesAWarning) {
  TestThatDeepDoubleImportCausesAWarning("try-import");
}

TEST_F(BlazercImportTest, ImportingAFileAndPassingItInCausesAWarning) {
  TestThatImportingAFileAndPassingItInCausesAWarning("import");
}

TEST_F(BlazercImportTest, TryImportingAFileAndPassingItInCausesAWarning) {
  TestThatImportingAFileAndPassingItInCausesAWarning("try-import");
}

// TODO(b/112908763): Somehow, in the following tests, we end with a relative
// path written in the import line on Windows. Figure out what's going on and
// reinstate these tests
#if !defined(_WIN32) && !defined(__CYGWIN__)
TEST_F(ParseOptionsTest, ImportingAPreviouslyLoadedStandardRcCausesAWarning) {
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("", &system_rc));

  // Import the system_rc extraneously.
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("import " + system_rc, &workspace_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(
      args, blaze_exit_code::SUCCESS, "",
      "WARNING: Duplicate rc file: .*bazel.bazelrc is read multiple "
      "times, most recently imported from .*workspace.*bazelrc\n");
}

TEST_F(ParseOptionsTest, ImportingStandardRcBeforeItIsLoadedCausesAWarning) {
  std::string workspace_rc;
  ASSERT_TRUE(SetUpWorkspaceRcFile("", &workspace_rc));

  // Import the workspace_rc extraneously.
  std::string system_rc;
  ASSERT_TRUE(SetUpSystemRcFile("import " + workspace_rc, &system_rc));

  const std::vector<std::string> args = {"bazel", "build"};
  ParseOptionsAndCheckOutput(
      args, blaze_exit_code::SUCCESS, "",
      "WARNING: Duplicate rc file: .*workspace.*bazelrc is read multiple "
      "times, it is a standard rc file location but must have been "
      "unnecessarily imported earlier.\n");
}
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

}  // namespace blaze
