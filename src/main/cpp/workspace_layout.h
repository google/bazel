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

#ifndef BAZEL_SRC_MAIN_CPP_WORKSPACE_LAYOUT_H_
#define BAZEL_SRC_MAIN_CPP_WORKSPACE_LAYOUT_H_

#include <optional>
#include <string>
#include <vector>

namespace blaze {

// Provides methods to compute paths related to the workspace.
class WorkspaceLayout {
 public:
  virtual ~WorkspaceLayout() = default;

  // Given the working directory, returns the nearest enclosing directory with a
  // workspace boundary file in it.  If there is no such enclosing directory,
  // returns "".
  //
  // E.g., if there was a WORKSPACE file in foo/bar/build_root:
  // GetWorkspace('foo/bar') --> ''
  // GetWorkspace('foo/bar/build_root') --> 'foo/bar/build_root'
  // GetWorkspace('foo/bar/build_root/biz') --> 'foo/bar/build_root'
  //
  // The returned path is relative or absolute depending on whether cwd was
  // relative or absolute.
  virtual std::string GetWorkspace(const std::string& cwd) const;

  // Given a result returned from GetWorkspace, returns a pretty workspace name
  // that can e.g. be used in the process title of the Bazel server.
  virtual std::string GetPrettyWorkspaceName(
      const std::string& workspace) const;

  // Returns if workspace is a valid build workspace.
  virtual bool InWorkspace(const std::string& workspace) const;

  // Returns the path of the workspace rc file.
  virtual std::string GetWorkspaceRcPath(
      const std::string& workspace,
      const std::vector<std::string>& startup_args) const;

  // Resolves a %workspace%-relative import into an actual path.
  // Returns nullopt if it could not be resolved into an existing file.
  virtual std::optional<std::string> ResolveWorkspaceRelativeRcFilePath(
      const std::string& workspace, const std::string& import_path) const;

  static constexpr const char kWorkspacePrefix[] = "%workspace%/";
  static constexpr int kWorkspacePrefixLength = sizeof kWorkspacePrefix - 1;
};

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_WORKSPACE_LAYOUT_H_
