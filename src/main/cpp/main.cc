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

#include <memory>

#include "src/main/cpp/bazel_startup_options.h"
#include "src/main/cpp/blaze.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/option_processor.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/cpp/workspace_layout.h"

int main_impl(int argc, char **argv) {
  uint64_t start_time = blaze::GetMillisecondsMonotonic();
  std::unique_ptr<blaze::WorkspaceLayout> workspace_layout =
      std::make_unique<blaze::WorkspaceLayout>();
  std::unique_ptr<blaze::StartupOptions> startup_options(
      std::make_unique<blaze::BazelStartupOptions>());
  return blaze::Main(argc, argv, workspace_layout.get(),
                     new blaze::OptionProcessor(workspace_layout.get(),
                                                std::move(startup_options)),
                     start_time);
}

#ifdef _WIN32
// Define wmain to support Unicode command line arguments on Windows
// regardless of the current code page.
int wmain(int argc, wchar_t **argv) {
  std::vector<std::string> args;
  for (int i = 0; i < argc; ++i) {
    args.push_back(blaze_util::WstringToCstring(argv[i]));
  }
  std::vector<char *> c_args;
  for (const std::string &arg : args) {
    c_args.push_back(const_cast<char *>(arg.c_str()));
  }
  c_args.push_back(nullptr);
  // Account for the null terminator.
  return main_impl(c_args.size() - 1, c_args.data());
}
#else
int main(int argc, char **argv) { return main_impl(argc, argv); }
#endif
