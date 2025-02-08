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

#include <fcntl.h>

#include <memory>

#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/path.h"
#include "googletest/include/gtest/gtest.h"
#include "src/main/cpp/util/file_platform.h"

namespace blaze {

class WorkspaceLayoutTest : public ::testing::Test {
 protected:
  WorkspaceLayoutTest()
      : build_root_(blaze_util::JoinPath(blaze::GetPathEnv("TEST_TMPDIR"),
                                         "build_root")),
        workspace_layout_(new WorkspaceLayout()) {}

  void SetUp() override {
    ASSERT_TRUE(blaze_util::MakeDirectories(build_root_, 0755));
  }

  void TearDown() override {
    blaze_util::RemoveRecursively(blaze_util::Path(build_root_));
  }

  const std::string build_root_;
  const std::unique_ptr<WorkspaceLayout> workspace_layout_;
};

TEST_F(WorkspaceLayoutTest, GetWorkspace) {
  ASSERT_TRUE(blaze_util::WriteFile(
      "", blaze_util::JoinPath(build_root_, "WORKSPACE"), 0755));
  const auto foobar = blaze_util::JoinPath(build_root_, "foo/bar");
  ASSERT_TRUE(blaze_util::MakeDirectories(foobar, 0755));

  // "" is returned when there's no workspace path.
  ASSERT_EQ("", workspace_layout_->GetWorkspace("foo/bar"));
  ASSERT_FALSE(workspace_layout_->InWorkspace("foo/bar"));

  ASSERT_EQ(build_root_, workspace_layout_->GetWorkspace(build_root_));
  ASSERT_TRUE(workspace_layout_->InWorkspace(build_root_));

  ASSERT_EQ(build_root_, workspace_layout_->GetWorkspace(foobar));
  ASSERT_FALSE(workspace_layout_->InWorkspace(foobar));

  // Now write a REPO.bazel file in foo/bar. It becomes the new workspace root.
  ASSERT_TRUE(blaze_util::WriteFile(
      "", blaze_util::JoinPath(foobar, "REPO.bazel"), 0755));
  ASSERT_EQ(foobar, workspace_layout_->GetWorkspace(foobar));
  ASSERT_TRUE(workspace_layout_->InWorkspace(foobar));
}

}  // namespace blaze
