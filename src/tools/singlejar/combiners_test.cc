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

#include "src/tools/singlejar/combiners.h"

#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/test_util.h"
#include "src/tools/singlejar/zip_headers.h"
#include "src/tools/singlejar/zlib_interface.h"
#include "googletest/include/gtest/gtest.h"

namespace {

using rules_cc::cc::runfiles::Runfiles;

static const char kTag1Contents[] = "<tag1>Contents1</tag1>";
static const char kTag2Contents[] = "<tag2>Contents2</tag2>";
static const char kCombinedXmlContents[] =
    "<toplevel>\n<tag1>Contents1</tag1><tag2>Contents2</tag2></toplevel>\n";
static const char kConcatenatedContents[] =
    "<tag1>Contents1</tag1>\n<tag2>Contents2</tag2>";
const char kCombinedManifestContents[] = "Multi-Release: true\r\n\r\n";
const char kCombinedManifestContentsDisabled[] = "\r\n";
const uint8_t kPoison = 0xFA;

// A test fixture is used because test case setup is needed.
class CombinersTest : public ::testing::Test {
 public:
  void SetUp() override { runfiles.reset(Runfiles::CreateForTest()); }

 protected:
  static void SetUpTestCase() {
    ASSERT_EQ(0, chdir(getenv("TEST_TMPDIR")));
    ASSERT_TRUE(CreateFile("tag1.xml", kTag1Contents));
    ASSERT_TRUE(CreateFile("tag2.xml", kTag2Contents));
    ASSERT_EQ(0, system("zip -qm combiners.zip tag1.xml tag2.xml"));
  }

  static void TearDownTestCase() { remove("xmls.zip"); }

  static bool CreateFile(const char *filename, const char *contents) {
    FILE *fp = fopen(filename, "wb");
    size_t contents_size = strlen(contents);
    if (fp == nullptr || fwrite(contents, contents_size, 1, fp) != 1 ||
        fclose(fp)) {
      perror(filename);
      return false;
    }
    return true;
  }

  std::unique_ptr<Runfiles> runfiles;
};

// Test Concatenator.
TEST_F(CombinersTest, ConcatenatorSmall) {
  InputJar input_jar;
  Concatenator concatenator("concat");
  ASSERT_TRUE(input_jar.Open("combiners.zip"));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    if (cdh->file_name_is("tag1.xml") || cdh->file_name_is("tag2.xml")) {
      ASSERT_TRUE(concatenator.Merge(cdh, lh));
    }
  }

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(concatenator.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_DEFLATED, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kConcatenatedContents), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is("concat"));
  EXPECT_EQ(0, entry->extra_fields_length());

  // Decompress and check contents.
  Inflater inflater;
  inflater.DataToInflate(entry->data(), compressed_size);
  uint8_t buffer[256];
  ASSERT_EQ(Z_STREAM_END, inflater.Inflate((buffer), sizeof(buffer)));
  EXPECT_EQ(kConcatenatedContents,
            std::string(reinterpret_cast<char *>(buffer), original_size));
  free(reinterpret_cast<void *>(entry));

  // And if we just copy instead of compress:
  entry = reinterpret_cast<LH *>(concatenator.OutputEntry(false));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  original_size = entry->uncompressed_file_size();
  compressed_size = entry->compressed_file_size();
  EXPECT_EQ(compressed_size, original_size);
  EXPECT_EQ(
      kConcatenatedContents,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  EXPECT_TRUE(entry->file_name_is("concat"));
  EXPECT_EQ(0, entry->extra_fields_length());
  free(reinterpret_cast<void *>(entry));
}

// Tests that Concatenator creates huge (>4GB original/compressed sizes)
// correctly. This test is slow.
TEST_F(CombinersTest, ConcatenatorHuge) {
  Concatenator concatenator("huge");

  // Append 5,000,000,000 bytes to the concatenator.
  const int kBufSize = 1000000;
  char *buf = reinterpret_cast<char *>(malloc(kBufSize));
  memset(buf, kPoison, kBufSize);
  for (int i = 0; i < 5000; ++i) {
    concatenator.Append(buf, kBufSize);
  }
  free(buf);

  // Now hope that we have enough memory :-)
  LH *entry = reinterpret_cast<LH *>(concatenator.OutputEntry(true));
  ASSERT_NE(nullptr, entry);
  ASSERT_TRUE(entry->is());
  ASSERT_EQ(20, entry->version());
  EXPECT_EQ(Z_DEFLATED, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  ASSERT_EQ(5000000000UL, original_size);
  ASSERT_LE(compressed_size, original_size);
  free(reinterpret_cast<void *>(entry));
}

// Test NullCombiner.
TEST_F(CombinersTest, NullCombiner) {
  NullCombiner null_combiner;
  ASSERT_TRUE(null_combiner.Merge(nullptr, nullptr));
  ASSERT_EQ(nullptr, null_combiner.OutputEntry(true));
  ASSERT_EQ(nullptr, null_combiner.OutputEntry(false));
}

// Test XmlCombiner.
TEST_F(CombinersTest, XmlCombiner) {
  InputJar input_jar;
  XmlCombiner xml_combiner("combined.xml", "toplevel");
  XmlCombiner xml_combiner2("combined2.xml", "toplevel");
  ASSERT_TRUE(input_jar.Open("combiners.zip"));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    if (cdh->file_name_is("tag1.xml") || cdh->file_name_is("tag2.xml")) {
      ASSERT_TRUE(xml_combiner.Merge(cdh, lh));
      ASSERT_TRUE(xml_combiner2.Merge(cdh, lh));
    }
  }

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(xml_combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_DEFLATED, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kCombinedXmlContents), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is("combined.xml"));
  EXPECT_EQ(0, entry->extra_fields_length());

  // Decompress and check contents.
  Inflater inflater;
  inflater.DataToInflate(entry->data(), compressed_size);
  uint8_t buffer[256];
  ASSERT_EQ(Z_STREAM_END, inflater.Inflate((buffer), sizeof(buffer)));
  EXPECT_EQ(kCombinedXmlContents,
            std::string(reinterpret_cast<char *>(buffer), original_size));
  free(reinterpret_cast<void *>(entry));

  // And for the combiner that just copies out:
  entry = reinterpret_cast<LH *>(xml_combiner2.OutputEntry(false));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  original_size = entry->uncompressed_file_size();
  compressed_size = entry->compressed_file_size();
  EXPECT_EQ(compressed_size, original_size);
  EXPECT_EQ(
      kCombinedXmlContents,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  EXPECT_TRUE(entry->file_name_is("combined2.xml"));
  EXPECT_EQ(0, entry->extra_fields_length());
  free(reinterpret_cast<void *>(entry));
}

// Test PropertyCombiner.
TEST_F(CombinersTest, PropertyCombiner) {
  static char kProperties[] =
      "name=value\n"
      "name_str=value_str\n";
  PropertyCombiner property_combiner("properties");
  property_combiner.AddProperty("name", "value");
  property_combiner.AddProperty(std::string("name_str"),
                                std::string("value_str"));

  // Merge should not be called.
  ASSERT_FALSE(property_combiner.Merge(nullptr, nullptr));

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(property_combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_DEFLATED, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kProperties), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_EQ("properties", entry->file_name_string());
  EXPECT_EQ(0, entry->extra_fields_length());

  // Decompress and check contents.
  Inflater inflater;
  inflater.DataToInflate(entry->data(), compressed_size);
  uint8_t buffer[256];
  ASSERT_EQ(Z_STREAM_END, inflater.Inflate((buffer), sizeof(buffer)));
  EXPECT_EQ(kProperties,
            std::string(reinterpret_cast<char *>(buffer), original_size));
  free(reinterpret_cast<void *>(entry));

  // Create output, verify Local Header contents.
  entry = reinterpret_cast<LH *>(property_combiner.OutputEntry(false));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  original_size = entry->uncompressed_file_size();
  compressed_size = entry->compressed_file_size();
  EXPECT_EQ(compressed_size, original_size);
  EXPECT_EQ(
      kProperties,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  EXPECT_EQ("properties", entry->file_name_string());
  EXPECT_EQ(0, entry->extra_fields_length());
  free(reinterpret_cast<void *>(entry));
}

// Test ManifestCombiner.
TEST_F(CombinersTest, ManifestCombiner) {
  InputJar input_jar;
  ManifestCombiner manifest_combiner("META-INF/MANIFEST.MF");
  ASSERT_TRUE(
      input_jar.Open(runfiles
                         ->Rlocation("io_bazel/src/tools/"
                                     "singlejar/data/multi_release.jar")
                         .c_str()));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    if (cdh->file_name_is("META-INF/MANIFEST.MF")) {
      ASSERT_TRUE(manifest_combiner.Merge(cdh, lh));
    }
  }

  // check that Multi-Release is de-duped, e.g. if present both in deps and
  // deploy_manifest_lines
  manifest_combiner.AppendLine("Multi-Release: true");

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(manifest_combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kCombinedManifestContents), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is("META-INF/MANIFEST.MF"));
  EXPECT_EQ(0, entry->extra_fields_length());

  // Check contents.
  EXPECT_EQ(
      kCombinedManifestContents,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  free(reinterpret_cast<void *>(entry));
}

// Test ManifestCombiner.
TEST_F(CombinersTest, ManifestCombinerJarOnly) {
  InputJar input_jar;
  ManifestCombiner manifest_combiner("META-INF/MANIFEST.MF");
  ASSERT_TRUE(
      input_jar.Open(runfiles
                         ->Rlocation("io_bazel/src/tools/"
                                     "singlejar/data/multi_release.jar")
                         .c_str()));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    if (cdh->file_name_is("META-INF/MANIFEST.MF")) {
      ASSERT_TRUE(manifest_combiner.Merge(cdh, lh));
    }
  }

  // check that Multi-Release is ignored if present only in deps

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(manifest_combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kCombinedManifestContentsDisabled), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is("META-INF/MANIFEST.MF"));
  EXPECT_EQ(0, entry->extra_fields_length());

  // Check contents.
  EXPECT_EQ(
      kCombinedManifestContentsDisabled,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  free(reinterpret_cast<void *>(entry));
}

TEST_F(CombinersTest, ManifestCombinerFalse) {
  InputJar input_jar;
  ManifestCombiner manifest_combiner("META-INF/MANIFEST.MF");
  ASSERT_TRUE(
      input_jar.Open(runfiles
                         ->Rlocation("io_bazel/src/tools/"
                                     "singlejar/data/multi_release.jar")
                         .c_str()));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    if (cdh->file_name_is("META-INF/MANIFEST.MF")) {
      ASSERT_TRUE(manifest_combiner.Merge(cdh, lh));
    }
  }

  // check that deploy_manifest_lines can disable the setting in input jars
  manifest_combiner.AppendLine("Multi-Release: false");

  // Create output, verify Local Header contents.
  LH *entry = reinterpret_cast<LH *>(manifest_combiner.OutputEntry(true));
  EXPECT_TRUE(entry->is());
  EXPECT_EQ(20, entry->version());
  EXPECT_EQ(Z_NO_COMPRESSION, entry->compression_method());
  uint64_t original_size = entry->uncompressed_file_size();
  uint64_t compressed_size = entry->compressed_file_size();
  EXPECT_EQ(strlen(kCombinedManifestContentsDisabled), original_size);
  EXPECT_LE(compressed_size, original_size);
  EXPECT_TRUE(entry->file_name_is("META-INF/MANIFEST.MF"));
  EXPECT_EQ(0, entry->extra_fields_length());

  // Check contents.
  EXPECT_EQ(
      kCombinedManifestContentsDisabled,
      std::string(reinterpret_cast<char *>(entry->data()), original_size));
  free(reinterpret_cast<void *>(entry));
}

}  // anonymous namespace
