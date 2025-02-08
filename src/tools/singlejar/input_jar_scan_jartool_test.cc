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

/* Verify that InputJar can scan zip/jar files created by JDK's jar tool.  */

#include "src/tools/singlejar/port.h"

// Suppress [build/include_order] linter errors, because port.h must be the
// topmost header file as it defines types used by later-included headers.
#include <stdarg.h>  // NOLINT
#include <stdlib.h>  // NOLINT

#include "src/tools/singlejar/input_jar_scan_entries_test.h"

using rules_cc::cc::runfiles::Runfiles;

class JartoolCreator {
 public:
  static void SetUpTestCase() {
    std::unique_ptr<Runfiles> runfiles(Runfiles::CreateForTest());
    static std::string jar_path_str = runfiles->Rlocation(JAR_TOOL_PATH);
    jar_path_ = jar_path_str.c_str();
  }

  static void TearDownTestCase() {}

  static int Jar(bool compress, const char *output_jar, ...) {
    std::string command(jar_path_);
    if (access(output_jar, F_OK) == 0) {
      command += compress ? " -uf " : " -u0f ";
    } else {
      command += compress ? " -cf " : " -c0f ";
    }
    command += output_jar;
    va_list paths;
    va_start(paths, output_jar);
    char *path;
    while ((path = va_arg(paths, char *))) {
      command += ' ';
      command += path;
    }
    return system(command.c_str());
  }
  static const char *jar_path_;
};

const char *JartoolCreator::jar_path_ = nullptr;

typedef testing::Types<JartoolCreator> Creators;
INSTANTIATE_TYPED_TEST_SUITE_P(Jartool, InputJarScanEntries, Creators);
