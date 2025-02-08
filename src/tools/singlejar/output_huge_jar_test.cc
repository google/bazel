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

#include <stdlib.h>

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"
#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/options.h"
#include "src/tools/singlejar/output_jar.h"
#include "src/tools/singlejar/test_util.h"
#include "googletest/include/gtest/gtest.h"

namespace {

using rules_cc::cc::runfiles::Runfiles;
using singlejar_test_util::AllocateFile;
using singlejar_test_util::OutputFilePath;
using singlejar_test_util::VerifyZip;

using std::string;

class OutputHugeJarTest : public ::testing::Test {
 protected:
  void SetUp() override { runfiles.reset(Runfiles::CreateForTest()); }

  void CreateOutput(const string &out_path, const std::vector<string> &args) {
    const char *option_list[100] = {"--output", out_path.c_str()};
    int nargs = 2;
    for (auto &arg : args) {
      if (arg.empty()) {
        continue;
      }
      option_list[nargs++] = arg.c_str();
      if (arg.find(' ') == string::npos) {
        fprintf(stderr, " '%s'", arg.c_str());
      } else {
        fprintf(stderr, " %s", arg.c_str());
      }
    }
    fprintf(stderr, "\n");
    options_.ParseCommandLine(nargs, option_list);
    ASSERT_EQ(0, output_jar_.Doit(&options_));
    EXPECT_EQ(0, VerifyZip(out_path));
  }

  OutputJar output_jar_;
  Options options_;
  std::unique_ptr<Runfiles> runfiles;
};

TEST_F(OutputHugeJarTest, EntryAbove4G) {
  // Verifies that an entry above 4G is handled correctly.

  // Have huge launcher, then the first jar entry will be above 4G.
  string launcher_path = OutputFilePath("launcher");
  ASSERT_TRUE(AllocateFile(launcher_path, 0x100000010));

  string out_path = OutputFilePath("out.jar");
  CreateOutput(
      out_path,
      {"--java_launcher", launcher_path, "--sources",
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest1.jar")
           .c_str()});
}

}  // namespace
