#ifndef SRC_TOOLS_SINGLEJAR_TEST_UTIL_H_
#define SRC_TOOLS_SINGLEJAR_TEST_UTIL_H_
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

#include <string>

#include "rules_cc/cc/runfiles/runfiles.h"

namespace singlejar_test_util {
  using std::string;

  // Allocate a file with given name and size. The contents is zeroes.
  bool AllocateFile(const string& name, size_t size);

  // Combine the passed arguments to a shell command and run it.
  // E.g. calling RunCommand("cmd", "arg1", "arg2", nullptr) results in
  // running 'cmd arg1 arg2'.
  // Returns command's return code.
  int RunCommand(const char *cmd, ...);

  // List zip file contents.
  void LsZip(const char *zip_name);

  // Return the full path to a file in a temporary directory.
  std::string OutputFilePath(const string& relpath);

  // Verify given archive contents by running 'zip -Tv' on it,
  // returning its exit code (0 means success). Diagnostics goes
  // tp stdout/stderr.
  int VerifyZip(const string& zip_path);

  // Read the contents of the given archive entry and return it as string.
  string GetEntryContents(const string &zip_path, const string& entry_name);

  // Create a file in the output directory with given contents,
  // return file's path.
  string CreateTextFile(const string& file_path, const char *contents);

}  // namespace singlejar_test_util
#endif  //  SRC_TOOLS_SINGLEJAR_TEST_UTIL_H_
