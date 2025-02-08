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

namespace {

using rules_cc::cc::runfiles::Runfiles;

void Verify(const std::string &path) {
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    ASSERT_TRUE(cdh->is())
        << "No expected tag in the Central Directory Entry.";
    ASSERT_NE(nullptr, lh) << "No local header.";
    ASSERT_TRUE(lh->is()) << "No expected tag in the Local Header.";
    EXPECT_EQ(lh->file_name_string(), cdh->file_name_string());
    if (!cdh->no_size_in_local_header()) {
      EXPECT_EQ(lh->compressed_file_size(), cdh->compressed_file_size())
          << "Entry: " << lh->file_name_string();
      EXPECT_EQ(lh->uncompressed_file_size(), cdh->uncompressed_file_size())
          << "Entry: " << cdh->file_name_string();
    }
  }
  input_jar.Close();
}

// Archive not containing 64-bit End of Central Directory/Locator with preamble.
TEST(InputJarPreambledTest, Small) {
  std::unique_ptr<Runfiles> runfiles(Runfiles::CreateForTest());
  std::string out_path = singlejar_test_util::OutputFilePath("out.jwp");
  std::string exe_path = singlejar_test_util::OutputFilePath("exe");
  ASSERT_TRUE(singlejar_test_util::AllocateFile(exe_path, 100));
  ASSERT_EQ(
      0,
      singlejar_test_util::RunCommand(
          "cat", exe_path.c_str(),
          runfiles
              ->Rlocation(
                  "io_bazel/src/tools/singlejar/libtest1.jar")
              .c_str(),
          ">", out_path.c_str(), nullptr));
  Verify(out_path);
}

// Same as above with zip -A applied to the file.
TEST(InputJarPreambledTest, SmallAdjusted) {
  std::unique_ptr<Runfiles> runfiles(Runfiles::CreateForTest());
  std::string out_path = singlejar_test_util::OutputFilePath("out.jwp");
  std::string exe_path = singlejar_test_util::OutputFilePath("exe");
  ASSERT_TRUE(singlejar_test_util::AllocateFile(exe_path, 100));
  ASSERT_EQ(
      0,
      singlejar_test_util::RunCommand(
          "cat", exe_path.c_str(),
          runfiles
              ->Rlocation(
                  "io_bazel/src/tools/singlejar/libtest1.jar")
              .c_str(),
          ">", out_path.c_str(), nullptr));
  ASSERT_EQ(0, singlejar_test_util::RunCommand("zip", "-A", out_path.c_str(),
                                               nullptr));
  Verify(out_path);
}

// 64-bit Zip file with preamble
TEST(InputJarPreambledTest, Huge) {
  std::string file4g = singlejar_test_util::OutputFilePath("file4g");
  ASSERT_TRUE(singlejar_test_util::AllocateFile(file4g, 0x10000000F));
  std::string huge_jar = singlejar_test_util::OutputFilePath("huge.jar");
  ASSERT_EQ(0, singlejar_test_util::RunCommand("zip", "-0m", huge_jar.c_str(),
                                               file4g.c_str(), nullptr));
  std::string exe_path = singlejar_test_util::OutputFilePath("exe");
  std::string out_path = singlejar_test_util::OutputFilePath("out.jwp");
  ASSERT_EQ(0,
            singlejar_test_util::RunCommand("cat", exe_path.c_str(),
                                            huge_jar.c_str(),
                                            ">", out_path.c_str(), nullptr));
  Verify(out_path);
}

}  // namespace
