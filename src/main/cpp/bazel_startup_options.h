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
#ifndef BAZEL_SRC_MAIN_CPP_BAZEL_STARTUP_OPTIONS_H_
#define BAZEL_SRC_MAIN_CPP_BAZEL_STARTUP_OPTIONS_H_

#include <string>

#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/exit_code.h"

namespace blaze {

// BazelStartupOptions contains the startup options that are Bazel-specific.
class BazelStartupOptions : public StartupOptions {
 public:
  explicit BazelStartupOptions(const WorkspaceLayout *workspace_layout);

  void AddExtraOptions(std::vector<std::string> *result) const override;

  blaze_exit_code::ExitCode ProcessArgExtra(
      const char *arg, const char *next_arg, const std::string &rcfile,
      const char **value, bool *is_processed, std::string *error) override;

  void MaybeLogStartupOptionWarnings() const override;

 protected:
  std::string GetRcFileBaseName() const override { return ".bazelrc"; }

 private:
  std::string user_low_priority_bazelrc_;
  std::string user_bazelrc_;
  bool use_system_rc;
  bool use_workspace_rc;
  bool use_home_rc;
};

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_BAZEL_STARTUP_OPTIONS_H_
