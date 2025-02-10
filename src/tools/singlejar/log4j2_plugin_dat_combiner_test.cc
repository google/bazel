// Copyright 2024 The Bazel Authors. All rights reserved.
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

#include "src/tools/singlejar/log4j2_plugin_dat_combiner.h"

#include <fstream>
#include <string>

#include "googletest/include/gtest/gtest.h"
#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/test_util.h"
#include "rules_cc/cc/runfiles/runfiles.h"

namespace {

using rules_cc::cc::runfiles::Runfiles;

// A test fixture is used because test case setup is needed.
class Log4J2PluginDatCombinerTest : public ::testing::Test {
 public:
  void SetUp() override { runfiles.reset(Runfiles::CreateForTest()); }

 protected:
  static void SetUpTestCase() { ASSERT_EQ(0, chdir(getenv("TEST_TMPDIR"))); }

  static void TearDownTestCase() {}

  std::unique_ptr<Runfiles> runfiles;
};

// Test Log4J2PluginDatCombiner
TEST_F(Log4J2PluginDatCombinerTest, Log4J2PluginDatCombinerSimple) {
  const std::string plugins_cache_path =
      "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat";
  Log4J2PluginDatCombiner combiner(plugins_cache_path, false);

  InputJar input_jar1;
  ASSERT_TRUE(input_jar1.Open(
      runfiles
          ->Rlocation(
              "io_bazel/src/tools/singlejar/data/log4j2_plugins_set_1.jar")
          .c_str()));

  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar1.NextEntry(&lh))) {
    if (cdh->file_name_is(plugins_cache_path.c_str())) {
      ASSERT_TRUE(combiner.Merge(cdh, lh));
    }
  }

  InputJar input_jar2;
  ASSERT_TRUE(input_jar2.Open(
      runfiles
          ->Rlocation(
              "io_bazel/src/tools/singlejar/data/log4j2_plugins_set_2.jar")
          .c_str()));
  while ((cdh = input_jar2.NextEntry(&lh))) {
    if (cdh->file_name_is(plugins_cache_path.c_str())) {
      ASSERT_TRUE(combiner.Merge(cdh, lh));
    }
  }

  LH *entry = reinterpret_cast<LH *>(combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_DEFLATED, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();

  std::ifstream expected_file(
      runfiles->Rlocation(
          "io_bazel/src/tools/singlejar/data/log4j2_plugins_set_result.dat"),
      std::ios::binary);
  ASSERT_TRUE(expected_file.is_open());

  expected_file.seekg(0, std::ios::end);
  std::streampos expected_size = expected_file.tellg();
  expected_file.seekg(0, std::ios::beg);
  ASSERT_GT(expected_size, 0);
  char *expected_content = new char[expected_size];
  expected_file.read(expected_content, expected_size);
  ASSERT_TRUE(expected_file.good());
  expected_file.close();

  EXPECT_EQ(expected_size, original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is(plugins_cache_path.c_str()));
  Inflater inflater;
  inflater.DataToInflate(entry->data(), compressed_size);
  uint8_t buffer[256];
  ASSERT_EQ(Z_STREAM_END, inflater.Inflate((buffer), sizeof(buffer)));
  ASSERT_EQ(
      memcmp(reinterpret_cast<char *>(buffer), expected_content, expected_size),
      0)
      << "Byte arrays are not equal";
  free(reinterpret_cast<void *>(entry));
}

TEST_F(Log4J2PluginDatCombinerTest, Log4J2PluginDatCombinerDuplicate) {
  const std::string plugins_cache_path =
      "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat";
  Log4J2PluginDatCombiner combiner(plugins_cache_path, true);

  InputJar input_jar1;
  ASSERT_TRUE(input_jar1.Open(
      runfiles
          ->Rlocation(
              "io_bazel/src/tools/singlejar/data/log4j2_plugins_set_1.jar")
          .c_str()));

  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar1.NextEntry(&lh))) {
    if (cdh->file_name_is(plugins_cache_path.c_str())) {
      ASSERT_TRUE(combiner.Merge(cdh, lh));
    }
  }

  InputJar input_jar2;
  // We reuse log4j2_plugins_set_1.jar on purpose to cause a duplicate.
  ASSERT_TRUE(input_jar2.Open(
      runfiles
          ->Rlocation(
              "io_bazel/src/tools/singlejar/data/log4j2_plugins_set_1.jar")
          .c_str()));
  while ((cdh = input_jar2.NextEntry(&lh))) {
    if (cdh->file_name_is(plugins_cache_path.c_str())) {
      ASSERT_DEATH(combiner.Merge(cdh, lh), "present in multiple jars");
    }
  }
}

}  // anonymous namespace
