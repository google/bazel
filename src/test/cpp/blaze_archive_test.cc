// Copyright 2023 The Bazel Authors. All rights reserved.
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
#include <stdlib.h>

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "file/base/filesystem.h"
#include "file/base/helpers.h"
#include "file/base/path.h"
#include "file/util/temp_path.h"
#include "file/zipfile/zipfilewriter.h"
#include "src/main/cpp/archive_utils.h"
#include "src/main/cpp/bazel_startup_options.h"
#include "googlemock/include/gmock/gmock.h"
#include "googletest/include/gtest/gtest.h"
#include "absl/status/statusor.h"
#include "absl/time/time.h"
#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file_platform.h"
#include "util/task/status_macros.h"

using ::testing::Gt;
using ::testing::status::IsOkAndHolds;

namespace blaze {

static std::vector<std::pair<std::string, std::string>>
get_archive_path_to_contents(std::string expected_install_md5) {
  std::vector<std::pair<std::string, std::string>> archive_path_to_contents;
  archive_path_to_contents.push_back(std::make_pair("foo", "foo content"));
  archive_path_to_contents.push_back(std::make_pair("bar", "bar content"));
  archive_path_to_contents.push_back(
      std::make_pair("path/to/subdir/baz", "baz content"));
  archive_path_to_contents.push_back(
      std::make_pair("install_base_key", expected_install_md5));
  return archive_path_to_contents;
}

static std::vector<std::string> get_archive_paths() {
  std::vector<std::string> archive_paths;
  archive_paths.push_back("foo");
  archive_paths.push_back("bar");
  archive_paths.push_back("path/to/subdir/baz");
  return archive_paths;
}

static absl::StatusOr<std::string> MakeZipAndReturnInstallBase(
    absl::string_view path, std::vector<std::pair<std::string, std::string>>
                                blaze_zip_file_to_contents) {
  ASSIGN_OR_RETURN(auto writer,
                   file_zipfile::ZipfileWriter::Create(path, file::Defaults()));
  for (const auto file_and_contents : blaze_zip_file_to_contents) {
    writer->AddFileFromString(file_and_contents.first,
                              file_and_contents.second);
  }
  RETURN_IF_ERROR(writer->CloseWithStatus(file::Defaults()));
  return "install_base";
}

static void set_startup_options(BazelStartupOptions &startup_options,
                                std::string blaze_path,
                                std::string output_dir) {
  std::string error;
  const std::string install_base_flag = "--install_base=" + output_dir;
  const std::vector<RcStartupFlag> flags{
      RcStartupFlag("somewhere", install_base_flag)};
  const blaze_exit_code::ExitCode ec =
      startup_options.ProcessArgs(flags, &error);
  ASSERT_EQ(ec, blaze_exit_code::SUCCESS)
      << "ProcessArgs failed with error " << error;
}

auto get_mtime = [](absl::string_view path) -> absl::StatusOr<absl::Time> {
  ASSIGN_OR_RETURN(
      const auto stat,
      file::Stat(path, file::StatMask(tech::file::STAT_MTIME_NSECS)));
  return absl::FromUnixNanos(stat.mtime_nsecs());
};

class BlazeArchiveTest : public ::testing::Test {
 protected:
  BlazeArchiveTest() {}

  virtual ~BlazeArchiveTest() {}

  void SetUp() override {
    expected_install_md5 = "expected_install_md5";
    blaze_zip_file_to_contents =
        get_archive_path_to_contents(expected_install_md5);
    archive_contents = get_archive_paths();
    blaze_path = file::JoinPath(temp_.path(), "blaze");
    ASSERT_OK_AND_ASSIGN(
        install_base,
        MakeZipAndReturnInstallBase(blaze_path, blaze_zip_file_to_contents));
    output_dir = file::JoinPath(temp_.path(), install_base);
  }

  const TempPath temp_{TempPath::Local};

  std::vector<std::pair<std::string, std::string>> blaze_zip_file_to_contents;
  std::vector<std::string> archive_contents;

  std::string expected_install_md5;
  std::string blaze_path;
  std::string install_base;
  std::string output_dir;
};

TEST_F(BlazeArchiveTest, TestZipExtractionAndFarOutMTimes) {
  BazelStartupOptions startup_options;
  set_startup_options(startup_options, blaze_path, output_dir);
  LoggingInfo logging_info(blaze_path, blaze::GetMillisecondsMonotonic());

  std::optional<DurationMillis> extraction_time =
      ExtractData(blaze_path, archive_contents, expected_install_md5,
                  startup_options, &logging_info);

  ASSERT_TRUE(extraction_time.has_value());

  const std::string foo_path = file::JoinPath(output_dir, "foo");
  const std::string bar_path = file::JoinPath(output_dir, "bar");
  const std::string baz_path = file::JoinPath(output_dir, "path/to/subdir/baz");

  EXPECT_THAT(file::GetContents(foo_path, file::Defaults()),
              IsOkAndHolds("foo content"));
  EXPECT_THAT(file::GetContents(bar_path, file::Defaults()),
              IsOkAndHolds("bar content"));
  EXPECT_THAT(file::GetContents(baz_path, file::Defaults()),
              IsOkAndHolds("baz content"));

  EXPECT_TRUE(IsUntampered(blaze_util::Path(foo_path)));
  EXPECT_TRUE(IsUntampered(blaze_util::Path(bar_path)));
  EXPECT_TRUE(IsUntampered(blaze_util::Path(baz_path)));

  const auto far_future = absl::Now() + absl::Hours(24 * 365 * 9);
  EXPECT_THAT(get_mtime(foo_path), IsOkAndHolds(Gt(far_future)));
  EXPECT_THAT(get_mtime(bar_path), IsOkAndHolds(Gt(far_future)));
  EXPECT_THAT(get_mtime(baz_path), IsOkAndHolds(Gt(far_future)));
}

TEST_F(BlazeArchiveTest, TestNoDataExtractionIfInstallBaseExists) {
  BazelStartupOptions startup_options;
  set_startup_options(startup_options, blaze_path, output_dir);
  LoggingInfo logging_info(blaze_path, blaze::GetMillisecondsMonotonic());

  std::optional<DurationMillis> extraction_time_one =
      ExtractData(blaze_path, archive_contents, expected_install_md5,
                  startup_options, &logging_info);
  ASSERT_TRUE(extraction_time_one.has_value());

  std::optional<DurationMillis> extraction_time_two =
      ExtractData(blaze_path, archive_contents, expected_install_md5,
                  startup_options, &logging_info);
  ASSERT_FALSE(extraction_time_two.has_value());
}
}  // namespace blaze
