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

#ifndef BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_INTERNAL_H_
#define BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_INTERNAL_H_

#include <algorithm>
#include <set>

#include "src/main/cpp/rc_file.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"

namespace blaze {
namespace internal {

// Returns the deduped set of bazelrc paths (with respect to its canonical form)
// preserving the original order.
// All paths in the result were verified to exist (otherwise their canonical
// form couldn't have been computed). The paths that cannot be resolved are
// omitted.
std::vector<std::string> DedupeBlazercPaths(
    const std::vector<std::string>& paths);

// Given the set of already-ready files, warns if any of the newly loaded_rcs
// are duplicates. All paths are expected to be canonical.
void WarnAboutDuplicateRcFiles(const std::set<std::string>& read_files,
                               const std::deque<std::string>& loaded_rcs);

// Get the legacy list of rc files that would have been loaded - this is to
// provide a useful warning if files are being ignored that were loaded in a
// previous version of Bazel.
// TODO(b/3616816): Remove this once the warning is no longer useful.
std::set<std::string> GetOldRcPaths(
    const WorkspaceLayout* workspace_layout, const std::string& workspace,
    const std::string& cwd, const std::string& path_to_binary,
    const std::vector<std::string>& startup_args);

// Returns what the "user bazelrc" would have been in the legacy rc list.
std::string FindLegacyUserBazelrc(const char* cmd_line_rc_file,
                                  const std::string& workspace);

std::string FindSystemWideRc();

std::string FindRcAlongsideBinary(const std::string& cwd,
                                  const std::string& path_to_binary);

blaze_exit_code::ExitCode ParseErrorToExitCode(RcFile::ParseError parse_error);

std::vector<std::string> GetProcessedEnv();

}  // namespace internal
}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_INTERNAL_H_
