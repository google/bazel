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

/*
 * Check processing jar with preamble.
 *
 * A jar/zip file may contain a preambleL: an arbitrary data before
 * the actual entries, and the decompressors (e.g., unzip) can handle
 * this. This feature can be used to create "self-extracting"
 * archives: use 'cat' command to prepend a binary implementing the
 * extractor to a zip archive, and then run 'zip -A' on the result to
 * adjust the entry offsets stored in the zip archive. Actually, some
 * of the archive reading code works even if 'zip -A' wasn't run.
 */

#include <errno.h>
#ifndef _WIN32
#include <unistd.h>
#endif
#include <memory>
#include <string>

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"
#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/test_util.h"
#include "googletest/include/gtest/gtest.h"

using rules_cc::cc::runfiles::Runfiles;
using singlejar_test_util::AllocateFile;
using singlejar_test_util::OutputFilePath;
using singlejar_test_util::RunCommand;

namespace {

const char kEmptyJar[] =
    "io_bazel/src/tools/singlejar/data/empty.zip";

void VerifyEmpty(const std::string &jar_path) {
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(jar_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    ADD_FAILURE() << "There should not be any entries in " << jar_path;
  }
  input_jar.Close();
}

// Check that empty zip file (i.e., a valid zip file with no entries) is
// handled correctly.
TEST(InputJarBadjarTest, EmptyZipFile) {
  std::unique_ptr<Runfiles> runfiles(Runfiles::CreateForTest());
  VerifyEmpty(runfiles->Rlocation(kEmptyJar).c_str());
}

// Preambled empty zip.
TEST(InputJarPreambledTest, Empty) {
  std::unique_ptr<Runfiles> runfiles(Runfiles::CreateForTest());
  std::string out_path = OutputFilePath("empty.jwp");
  std::string exe_path = OutputFilePath("exe");
  ASSERT_TRUE(AllocateFile(exe_path, 100));
  ASSERT_EQ(0, RunCommand("cat", exe_path.c_str(),
                          runfiles->Rlocation(kEmptyJar).c_str(), ">",
                          out_path.c_str(), nullptr));
  VerifyEmpty(out_path);
}

}  // namespace
