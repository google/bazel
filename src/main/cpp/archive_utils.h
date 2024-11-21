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

#ifndef BAZEL_SRC_MAIN_CPP_ARCHIVE_UTILS_H_
#define BAZEL_SRC_MAIN_CPP_ARCHIVE_UTILS_H_

#include <optional>
#include <string>
#include <vector>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/logging.h"

namespace blaze {

// Determines the contents of the archive, storing the names of the contained
// files into `files` and the install md5 key into `install_md5`.
void DetermineArchiveContents(const std::string &archive_path,
                              std::vector<std::string> *files,
                              std::string *install_md5);

// The reason for a blaze server restart.
// Keep in sync with logging.proto.
enum RestartReason {
  NO_RESTART = 0,
  NO_DAEMON,
  NEW_VERSION,
  NEW_OPTIONS,
  PID_FILE_BUT_NO_SERVER,
  SERVER_VANISHED,
  SERVER_UNRESPONSIVE
};

// Encapsulates miscellaneous information reported to the server for logging and
// profiling purposes.
struct LoggingInfo {
 public:
  explicit LoggingInfo(const std::string &binary_path_,
                       const uint64_t start_time_ms_)
      : binary_path(binary_path_),
        start_time_ms(start_time_ms_),
        restart_reason(NO_RESTART) {}

  void SetRestartReasonIfNotSet(const RestartReason restart_reason_) {
    if (restart_reason == NO_RESTART) {
      restart_reason = restart_reason_;
    }
  }

  // Path of this binary.
  const std::string binary_path;

  // The time in ms the binary started up, measured from approximately the time
  // that "main" was called.
  const uint64_t start_time_ms;

  // The reason the server was restarted.
  RestartReason restart_reason;
};

// Extracts the archive and ensures success via calls to ExtractArchiveOrDie and
// BlessFiles. If the install base, the location the archive is unpacked,
// already exists, extraction is skipped. Kills the client if an error is
// encountered.
std::optional<DurationMillis> ExtractData(
    const std::string &self_path,
    const std::vector<std::string> &archive_contents,
    const std::string &expected_install_md5,
    const StartupOptions &startup_options, LoggingInfo *logging_info);

// Extracts the embedded data files in `archive_path` into `output_dir`.
// It's expected that `output_dir` already exists and that it's a directory.
// Fails if `expected_install_md5` doesn't match that contained in the archive,
// as this could indicate that the contents has unexpectedly changed.
void ExtractArchiveOrDie(const std::string &archive_path,
                         const std::string &product_name,
                         const std::string &expected_install_md5,
                         const blaze_util::Path &output_dir);

// Sets the timestamps of the extracted files to the future via
// blaze_util::IFileMtime::SetToDistanceFuture and ensures that the files we
// have written are actually on the disk. Later, the blaze client calls
// blaze_util::IFileMtime::IsUntampered to ensure the files were "blessed" with
// these distant mtimes.
void BlessFiles(const blaze_util::Path &embedded_binaries);

// Retrieves the build label (version string) from `archive_path` into
// `build_label`.
void ExtractBuildLabel(const std::string &archive_path,
                       std::string *build_label);

// Returns the server jar path from the archive contents.
std::string GetServerJarPath(const std::vector<std::string> &archive_contents);

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_ARCHIVE_UTILS_H_
