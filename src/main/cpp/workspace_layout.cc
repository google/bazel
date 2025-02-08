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

#include "src/main/cpp/workspace_layout.h"

#include <assert.h>

#include <optional>
#include <string>
#include <vector>

#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "absl/strings/match.h"

namespace blaze {

using std::string;
using std::vector;

bool WorkspaceLayout::InWorkspace(const string &workspace) const {
  for (auto boundaryFileName :
       {"MODULE.bazel", "REPO.bazel", "WORKSPACE.bazel", "WORKSPACE"}) {
    auto boundaryFilePath = blaze_util::JoinPath(workspace, boundaryFileName);
    if (blaze_util::PathExists(boundaryFilePath) &&
        !blaze_util::IsDirectory(boundaryFilePath))
      return true;
  }
  return false;
}

string WorkspaceLayout::GetWorkspace(const string &cwd) const {
  assert(!cwd.empty());
  string workspace = cwd;

  do {
    if (InWorkspace(workspace)) {
      return workspace;
    }
    workspace = blaze_util::Dirname(workspace);
  } while (!workspace.empty() && !blaze_util::IsRootDirectory(workspace));
  return "";
}

string WorkspaceLayout::GetPrettyWorkspaceName(
    const std::string &workspace) const {
  // e.g. A Bazel server process running in ~/src/myproject (where there's a
  // ~/src/myproject/WORKSPACE file) will appear in ps(1) as "bazel(myproject)".
  return blaze_util::Basename(workspace);
}

std::string WorkspaceLayout::GetWorkspaceRcPath(
    const std::string &workspace,
    const std::vector<std::string> &startup_args) const {
  // TODO(b/36168162): Rename and remove the tools/ prefix. See
  // https://github.com/bazelbuild/bazel/issues/4502#issuecomment-372697374
  // for the final set of bazelrcs we want to have.
  return blaze_util::JoinPath(workspace, "tools/bazel.rc");
}

std::optional<std::string> WorkspaceLayout::ResolveWorkspaceRelativeRcFilePath(
    const string &workspace, const string &import_path) const {
  // Strip off the "%workspace%/" prefix and prepend the true workspace path.
  // In theory this could use alternate search paths for blazerc files.
  assert(absl::StartsWith(import_path, kWorkspacePrefix));
  string resolved_path = blaze_util::JoinPath(
      workspace, import_path.substr(kWorkspacePrefixLength));
  if (blaze_util::PathExists(resolved_path)) {
    return resolved_path;
  }
  return std::nullopt;
}

}  // namespace blaze
