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

#include <stdio.h>
#include <stdlib.h>

// Must be included before anything else.
#include "src/tools/singlejar/port.h"

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"
#include "src/tools/singlejar/input_jar.h"
#include "src/tools/singlejar/options.h"
#include "src/tools/singlejar/output_jar.h"
#include "src/tools/singlejar/test_util.h"
#include "googletest/include/gtest/gtest.h"

#if !defined(JAR_TOOL_PATH)
#error "The path to jar tool has to be defined via -DJAR_TOOL_PATH="
#endif

#ifdef _WIN32
#define unlink _unlink
#define CMD_SEPARATOR "&"
#else
#define CMD_SEPARATOR ";"
#endif

namespace {

using rules_cc::cc::runfiles::Runfiles;
using singlejar_test_util::CreateTextFile;
using singlejar_test_util::GetEntryContents;
using singlejar_test_util::OutputFilePath;
using singlejar_test_util::RunCommand;
using singlejar_test_util::VerifyZip;

using std::string;

#if !defined(DATA_DIR_TOP)
#define DATA_DIR_TOP
#endif

const char kPathLibData1[] =
    "io_bazel/src/tools/singlejar/libdata1.jar";
const char kPathLibData2[] =
    "io_bazel/src/tools/singlejar/libdata2.jar";

static bool HasSubstr(const string &s, const string &what) {
  return string::npos != s.find(what);
}

static bool EndsWith(const string &s, const string &what) {
  return what.size() <= s.size() && s.substr(s.size() - what.size()) == what;
}

// A subclass of the OutputJar which concatenates the contents of each
// entry in the data/ directory from the input archives.
class CustomOutputJar : public OutputJar {
 public:
  ~CustomOutputJar() override {}
  void ExtraHandler(const std::string & /*input_jar_path*/, const CDH *cdh,
                    const std::string *input_jar_aux_label) override {
    auto file_name = cdh->file_name();
    auto file_name_length = cdh->file_name_length();
    if (file_name_length > 0 && file_name[file_name_length - 1] != '/' &&
        begins_with(file_name, file_name_length, "tools/singlejar/data/")) {
      // The contents of the data/<FILE> on the output is the
      // concatenation of the data/<FILE> files from all inputs.
      std::string metadata_file_path(file_name, file_name_length);
      if (NewEntry(metadata_file_path)) {
        ExtraCombiner(metadata_file_path, new Concatenator(metadata_file_path));
      }
    }
  }
};

class OutputJarSimpleTest : public ::testing::Test {
 protected:
  void SetUp() override { runfiles.reset(Runfiles::CreateForTest()); }

  void CreateOutput(const string &out_path, const std::vector<string> &args) {
    const char *option_list[100] = {"--output", out_path.c_str(),
                                    "--build_target", "//some/target"};
    int nargs = 4;
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

  string CompressionOptionsTestingJar(const string &compression_option) {
    string cp_res_path =
        CreateTextFile("cp_res", "line1\nline2\nline3\nline4\n");
    string out_path = OutputFilePath("out.jar");
    CreateOutput(
        out_path,
        {compression_option, "--sources",
         runfiles
             ->Rlocation(
                 "io_bazel/src/tools/singlejar/libtest1.jar")
             .c_str(),
         runfiles
             ->Rlocation(
                 "io_bazel/src/tools/singlejar/stored.jar")
             .c_str(),
         "--resources", cp_res_path, "--deploy_manifest_lines",
         "property1: value1", "property2: value2"});
    return out_path;
  }

  OutputJar output_jar_;
  Options options_;
  std::unique_ptr<Runfiles> runfiles;
};

// No inputs at all.
TEST_F(OutputJarSimpleTest, Empty) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {});
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  int entry_count = 0;
  const LH *lh;
  const CDH *cdh;
  const uint8_t cafe_extra_field[] = {0xFE, 0xCA, 0, 0};
  while ((cdh = input_jar.NextEntry(&lh))) {
    ++entry_count;
    ASSERT_TRUE(cdh->is()) << "No expected tag in the Central Directory Entry.";
    ASSERT_NE(nullptr, lh) << "No local header.";
    ASSERT_TRUE(lh->is()) << "No expected tag in the Local Header.";
    EXPECT_EQ(lh->file_name_string(), cdh->file_name_string());
    if (!cdh->no_size_in_local_header()) {
      EXPECT_EQ(lh->compressed_file_size(), cdh->compressed_file_size())
          << "Entry: " << lh->file_name_string();
      EXPECT_EQ(lh->uncompressed_file_size(), cdh->uncompressed_file_size())
          << "Entry: " << cdh->file_name_string();
    }
    // Verify that each entry has a reasonable timestamp.
    EXPECT_EQ(lh->last_mod_file_date(), cdh->last_mod_file_date())
        << "Entry: " << lh->file_name_string();
    EXPECT_EQ(lh->last_mod_file_time(), cdh->last_mod_file_time())
        << "Entry: " << lh->file_name_string();
    uint16_t dos_time = lh->last_mod_file_time();
    uint16_t dos_date = lh->last_mod_file_date();

    // Current time, rounded to even number of seconds because MSDOS timestamp
    // does this, too.
    time_t now = (time(nullptr) + 1) & ~1;
    struct tm tm_now;
    localtime_r(&now, &tm_now);
    char now_time_str[50];
    strftime(now_time_str, sizeof(now_time_str), "%c", &tm_now);

    // Unpack MSDOS file timestamp. See the comment about its format in
    // output_jar.cc.
    struct tm tm;
    tm.tm_sec = (dos_time & 31) << 1;
    tm.tm_min = (dos_time >> 5) & 63;
    tm.tm_hour = (dos_time >> 11) & 31;
    tm.tm_mday = (dos_date & 31);
    tm.tm_mon = ((dos_date >> 5) & 15) - 1;
    tm.tm_year = ((dos_date >> 9) & 127) + 80;
    tm.tm_isdst = tm_now.tm_isdst;
    time_t entry_time = mktime(&tm);
    char entry_time_str[50];
    strftime(entry_time_str, sizeof(entry_time_str), "%c", &tm);

    // Without --normalize option all the entries should have reasonably
    // current timestamp (which we arbitrarily choose to be <5 minutes).
    EXPECT_GE(now, entry_time) << now_time_str << " vs. " << entry_time_str;
    EXPECT_LE(now, entry_time + 300) << now_time_str << " vs. "
                                     << entry_time_str;

    // The first entry should be for the META-INF/ directory, and it should
    // contain a single extra field 0xCAFE. Although
    // https://bugs.openjdk.java.net/browse/JDK-6808540 claims that this extra
    // field is optional, 'file' utility in Linux relies on to distinguish
    // jar from zip.
    if (entry_count == 1) {
      ASSERT_EQ("META-INF/", lh->file_name_string());
      ASSERT_EQ(4, lh->extra_fields_length());
      ASSERT_EQ(0, memcmp(cafe_extra_field, lh->extra_fields(), 4));
      ASSERT_EQ(4, cdh->extra_fields_length());
      ASSERT_EQ(0, memcmp(cafe_extra_field, cdh->extra_fields(), 4));
    }
  }
  input_jar.Close();
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "\r\n",
      manifest);
  string build_properties = GetEntryContents(out_path, "build-data.properties");
  EXPECT_PRED2(HasSubstr, build_properties, "build.target=//some/target");
}

// Source jars.
TEST_F(OutputJarSimpleTest, Source) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(
      out_path,
      {"--sources",
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest1.jar")
           .c_str(),
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest2.jar")
           .c_str()});
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  int file_count = 0;
  while ((cdh = input_jar.NextEntry(&lh))) {
    ASSERT_TRUE(cdh->is()) << "No expected tag in the Central Directory Entry.";
    ASSERT_NE(nullptr, lh) << "No local header.";
    ASSERT_TRUE(lh->is()) << "No expected tag in the Local Header.";
    EXPECT_EQ(lh->file_name_string(), cdh->file_name_string());
    if (!cdh->no_size_in_local_header()) {
      EXPECT_EQ(lh->compressed_file_size(), cdh->compressed_file_size())
          << "Entry: " << lh->file_name_string();
      EXPECT_EQ(lh->uncompressed_file_size(), cdh->uncompressed_file_size())
          << "Entry: " << cdh->file_name_string();
    }
    if (lh->file_name()[lh->file_name_length() - 1] != '/') {
      ++file_count;
    }
  }
  ASSERT_LE(4, file_count);
  input_jar.Close();
}

// Verify --java_launcher argument
TEST_F(OutputJarSimpleTest, JavaLauncher) {
  string out_path = OutputFilePath("out.jar");
  std::string launcher_path = runfiles->Rlocation(
      "io_bazel/src/tools/singlejar/libtest1.jar");
  CreateOutput(out_path, {"--java_launcher", launcher_path});
  // check that the offset of the first entry equals launcher size.
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path.c_str()));
  const LH *lh;
  const CDH *cdh;
  cdh = input_jar.NextEntry(&lh);
  ASSERT_NE(nullptr, cdh);
  struct stat statbuf;
  ASSERT_EQ(0, stat(launcher_path.c_str(), &statbuf));
  EXPECT_TRUE(cdh->is());
  EXPECT_TRUE(lh->is());
  EXPECT_EQ(static_cast<uint64_t>(statbuf.st_size), cdh->local_header_offset());
  input_jar.Close();
}

// --cds_archive option
TEST_F(OutputJarSimpleTest, CDSArchive) {
  string out_path = OutputFilePath("out.jar");
  string launcher_path = CreateTextFile("launcher", "Dummy");
  string cds_archive_path = CreateTextFile("classes.jsa", "Dummy");
  CreateOutput(out_path, {"--java_launcher", launcher_path,
                          "--cds_archive", cds_archive_path});

  // check META-INF/MANIFEST.MF attribute
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  size_t pagesize;
#ifndef _WIN32
  pagesize = sysconf(_SC_PAGESIZE);
#else
  SYSTEM_INFO si;
  GetSystemInfo(&si);
  pagesize = si.dwPageSize;
#endif
  char attr[128];
  snprintf(attr, sizeof(attr), "Jsa-Offset: %ld", pagesize);
  EXPECT_PRED2(HasSubstr, manifest, attr);

  // check build-data.properties entry
  string build_properties = GetEntryContents(out_path, "build-data.properties");
  char prop[4096];
  snprintf(prop, sizeof(prop), "\ncds.archive=%s\n",
           cds_archive_path.c_str());
  EXPECT_PRED2(HasSubstr, build_properties, prop);
}

// --jdk_lib_modules option
TEST_F(OutputJarSimpleTest, JDKLibModules) {
  string out_path = OutputFilePath("out.jar");
  string launcher_path = CreateTextFile("launcher", "Dummy");
  string jdk_lib_modules_path = CreateTextFile("modules", "Dummy");
  CreateOutput(out_path, {"--java_launcher", launcher_path,
                          "--jdk_lib_modules", jdk_lib_modules_path});

  // Test META-INF/MANIFEST.MF attributes.
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  size_t pagesize;
#ifndef _WIN32
  pagesize = sysconf(_SC_PAGESIZE);
#else
  SYSTEM_INFO si;
  GetSystemInfo(&si);
  pagesize = si.dwPageSize;
#endif
  struct stat statbuf;
  stat(jdk_lib_modules_path.c_str(), &statbuf);
  size_t modules_size = statbuf.st_size;

  char offset_attr[128];
  snprintf(offset_attr, sizeof(offset_attr),
           "JDK-Lib-Modules-Offset: %ld", pagesize);
  EXPECT_PRED2(HasSubstr, manifest, offset_attr);

  char size_attr[128];
  snprintf(size_attr, sizeof(size_attr),
           "JDK-Lib-Modules-Size: %ld", modules_size);
  EXPECT_PRED2(HasSubstr, manifest, size_attr);
}

// --cds_archive & --jdk_lib_modules options
TEST_F(OutputJarSimpleTest, CDSAndJDKLibModules) {
  string cds_data = "cafebabe";
  string modules_data = "deadbeef";
  string out_path = OutputFilePath("out.jar");
  string launcher_path = CreateTextFile("launcher", "Dummy");
  string cds_archive_path = CreateTextFile("classes.jsa", cds_data.c_str());
  string jdk_lib_modules_path = CreateTextFile("modules", modules_data.c_str());
  CreateOutput(out_path, {"--java_launcher", launcher_path,
                          "--cds_archive", cds_archive_path,
                          "--jdk_lib_modules", jdk_lib_modules_path});

  FILE *fp = fopen(out_path.c_str(), "r");
  ASSERT_NE(nullptr, fp);

  // Test META-INF/MANIFEST.MF attributes.
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  size_t pagesize;
#ifndef _WIN32
  pagesize = sysconf(_SC_PAGESIZE);
#else
  SYSTEM_INFO si;
  GetSystemInfo(&si);
  pagesize = si.dwPageSize;
#endif
  size_t page_aligned_cds_offset = pagesize;
  char buf[8];
  size_t buf_len = sizeof(buf);
  struct stat statbuf;
  stat(jdk_lib_modules_path.c_str(), &statbuf);
  size_t modules_size = statbuf.st_size;

  char cds_attr[128];
  snprintf(cds_attr, sizeof(cds_attr), "Jsa-Offset: %ld",
           page_aligned_cds_offset);
  EXPECT_PRED2(HasSubstr, manifest, cds_attr);

  fseek(fp, page_aligned_cds_offset, 0);
  fread(buf, 1, buf_len, fp);
  ASSERT_EQ(cds_data, string(buf, buf_len));

  size_t page_aligned_modules_offset = pagesize * 2;
  char modules_offset_attr[128];
  snprintf(modules_offset_attr, sizeof(modules_offset_attr),
           "JDK-Lib-Modules-Offset: %ld",
           page_aligned_modules_offset);
  EXPECT_PRED2(HasSubstr, manifest, modules_offset_attr);
  char modules_size_attr[128];
  snprintf(modules_size_attr, sizeof(modules_size_attr),
           "JDK-Lib-Modules-Size: %ld", modules_size);
  EXPECT_PRED2(HasSubstr, manifest, modules_size_attr);

  fseek(fp, page_aligned_modules_offset, 0);
  fread(buf, 1, buf_len, fp);
  ASSERT_EQ(modules_data, string(buf, buf_len));

  fclose(fp);
}

// --main_class option.
TEST_F(OutputJarSimpleTest, MainClass) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--main_class", "com.google.my.Main"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Main-Class: com.google.my.Main\r\n"
      "\r\n",
      manifest);
}

// --output_jar_creator option
TEST_F(OutputJarSimpleTest, CreatedByFieldTest) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--output_jar_creator", "SingleJarTestValue 123.456"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: SingleJarTestValue 123.456\r\n"
      "\r\n",
      manifest);
}

// --deploy_manifest_lines option.
TEST_F(OutputJarSimpleTest, DeployManifestLines) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--deploy_manifest_lines", "property1: foo", "property2: bar"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "property1: foo\r\n"
      "property2: bar\r\n"
      "\r\n",
      manifest);
}

// --extra_build_info option
TEST_F(OutputJarSimpleTest, ExtraBuildInfo) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--extra_build_info", "property1=value1",
                          "--extra_build_info", "property2=value2"});
  string build_properties = GetEntryContents(out_path, "build-data.properties");
  EXPECT_PRED2(HasSubstr, build_properties, "\nproperty1=value1\n");
  EXPECT_PRED2(HasSubstr, build_properties, "\nproperty2=value2\n");
}

// --build_info_file and --extra_build_info options.
TEST_F(OutputJarSimpleTest, BuildInfoFile) {
  string build_info_path1 =
      CreateTextFile("buildinfo1", "property11=value11\nproperty12=value12\n");
  string build_info_path2 =
      CreateTextFile("buildinfo2", "property21=value21\nproperty22=value22\n");

  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--build_info_file", build_info_path1,
                          "--extra_build_info", "property=value",
                          "--build_info_file", build_info_path2.c_str()});
  string build_properties = GetEntryContents(out_path, "build-data.properties");
  EXPECT_PRED2(HasSubstr, build_properties, "property11=value11\n");
  EXPECT_PRED2(HasSubstr, build_properties, "property12=value12\n");
  EXPECT_PRED2(HasSubstr, build_properties, "property21=value21\n");
  EXPECT_PRED2(HasSubstr, build_properties, "property22=value22\n");
  EXPECT_PRED2(HasSubstr, build_properties, "property=value\n");
}

// --resources option.
TEST_F(OutputJarSimpleTest, Resources) {
  string res11_path = CreateTextFile("res11", "res11.line1\nres11.line2\n");
  string res11_spec = res11_path + ":res1";

  string res12_path = CreateTextFile("res12", "res12.line1\nres12.line2\n");
  string res12_spec = res12_path + ":res1";

  string res2_path = CreateTextFile("res2", "res2.line1\nres2.line2\n");

  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--resources", res11_spec, res12_spec, res2_path});

  // The output should have 'res1' entry containing the concatenation of the
  // 'res11' and 'res12' files.
  string res1 = GetEntryContents(out_path, "res1");
  EXPECT_EQ("res11.line1\nres11.line2\nres12.line1\nres12.line2\n", res1);

  // The output should have res2 path entry and contents.
  string res2 = GetEntryContents(out_path, res2_path);
  EXPECT_EQ("res2.line1\nres2.line2\n", res2);
}

TEST_F(OutputJarSimpleTest, ResourcesParentDirectories) {
  string res1_path = CreateTextFile("res1", "res1.line1\nres1.line2\n");
  string res2_path = CreateTextFile("res2", "res2.line1\nres2.line2\n");

  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--exclude_build_data", "--resources",
                          res1_path + ":the/resources/res1",
                          res2_path + ":the/resources2/res2"});

  string res1 = GetEntryContents(out_path, "the/resources/res1");
  EXPECT_EQ("res1.line1\nres1.line2\n", res1);

  string res2 = GetEntryContents(out_path, "the/resources2/res2");
  EXPECT_EQ("res2.line1\nres2.line2\n", res2);

  // The output should contain entries for parent directories
  std::vector<string> expected_entries(
      {"META-INF/", "META-INF/MANIFEST.MF", "the/", "the/resources/",
       "the/resources/res1", "the/resources2/", "the/resources2/res2"});
  std::vector<string> jar_entries;
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    jar_entries.push_back(cdh->file_name_string());
  }
  input_jar.Close();
  EXPECT_EQ(expected_entries, jar_entries);
}

TEST_F(OutputJarSimpleTest, ResourcesDirectories) {
  string dir_path = OutputFilePath("resource_dir");
  blaze_util::MakeDirectories(dir_path, 0777);

  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--exclude_build_data", "--resources", dir_path + ":the/dir"});

  // The output should contain entries for the directory
  std::vector<string> expected_entries({
      "META-INF/", "META-INF/MANIFEST.MF", "the/", "the/dir/",
  });
  std::vector<string> jar_entries;
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    jar_entries.push_back(cdh->file_name_string());
  }
  input_jar.Close();
  EXPECT_EQ(expected_entries, jar_entries);
}

// --classpath_resources
TEST_F(OutputJarSimpleTest, ClasspathResources) {
  string res1_path = OutputFilePath("cp_res");
  ASSERT_TRUE(blaze_util::WriteFile("line1\nline2\n", res1_path));
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--classpath_resources", res1_path.c_str()});
  string res = GetEntryContents(out_path, "cp_res");
  EXPECT_EQ("line1\nline2\n", res);
}

// Duplicate entries for --resources or --classpath_resources
TEST_F(OutputJarSimpleTest, DuplicateResources) {
  string cp_res_path = CreateTextFile("cp_res", "line1\nline2\n");

  string res1_path = CreateTextFile("res1", "resline1\nresline2\n");
  string res1_spec = res1_path + ":foo";

  string res2_path = CreateTextFile("res2", "line3\nline4\n");
  string res2_spec = res2_path + ":foo";

  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--warn_duplicate_resources", "--resources", res1_spec,
                res2_spec, "--classpath_resources", cp_res_path, cp_res_path});

  string cp_res = GetEntryContents(out_path, "cp_res");
  EXPECT_EQ("line1\nline2\n", cp_res);

  string foo = GetEntryContents(out_path, "foo");
  EXPECT_EQ("resline1\nresline2\n", foo);
}

// Extra combiners
TEST_F(OutputJarSimpleTest, ExtraCombiners) {
  string resolvedLibDataPath1 = runfiles->Rlocation(kPathLibData1);
  string resolvedLibDataPath2 = runfiles->Rlocation(kPathLibData2);
  string out_path = OutputFilePath("out.jar");
  const char kEntry[] = "tools/singlejar/data/extra_file1";
  output_jar_.ExtraCombiner(kEntry, new Concatenator(kEntry));
  CreateOutput(out_path, {"--sources", resolvedLibDataPath1.c_str(),
                          resolvedLibDataPath2.c_str()});
  string contents1 = GetEntryContents(resolvedLibDataPath1.c_str(), kEntry);
  string contents2 = GetEntryContents(resolvedLibDataPath2.c_str(), kEntry);
  EXPECT_EQ(contents1 + contents2, GetEntryContents(out_path, kEntry));
}

// Test ExtraHandler override.
TEST_F(OutputJarSimpleTest, ExtraHandler) {
  string resolvedLibDataPath1 = runfiles->Rlocation(kPathLibData1);
  string resolvedLibDataPath2 = runfiles->Rlocation(kPathLibData2);
  string out_path = OutputFilePath("out.jar");
  const char kEntry[] = "tools/singlejar/data/extra_file1";
  const char *option_list[] = {"--output", out_path.c_str(), "--sources",
                               resolvedLibDataPath1.c_str(),
                               resolvedLibDataPath2.c_str()};
  CustomOutputJar custom_output_jar;
  options_.ParseCommandLine(arraysize(option_list), option_list);
  ASSERT_EQ(0, custom_output_jar.Doit(&options_));
  EXPECT_EQ(0, VerifyZip(out_path));

  string contents1 = GetEntryContents(resolvedLibDataPath1.c_str(), kEntry);
  string contents2 = GetEntryContents(resolvedLibDataPath2.c_str(), kEntry);
  EXPECT_EQ(contents1 + contents2, GetEntryContents(out_path, kEntry));
}

// --include_headers
TEST_F(OutputJarSimpleTest, IncludeHeaders) {
  string resolvedLibDataPath1 = runfiles->Rlocation(kPathLibData1);
  string out_path = OutputFilePath("out.jar");
  CreateOutput(
      out_path,
      {"--sources",
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest1.jar")
           .c_str(),
       resolvedLibDataPath1.c_str(), "--include_prefixes",
       "tools/singlejar/data"});
  std::vector<string> expected_entries(
      {"META-INF/", "META-INF/MANIFEST.MF", "build-data.properties",
       "tools/singlejar/data/", "tools/singlejar/data/extra_file1",
       "tools/singlejar/data/extra_file2"});
  std::vector<string> jar_entries;
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    jar_entries.push_back(cdh->file_name_string());
  }
  input_jar.Close();
  EXPECT_EQ(expected_entries, jar_entries);
}

// --normalize
TEST_F(OutputJarSimpleTest, Normalize) {
  // Creates output jar containing entries from all possible sources:
  //  * archives created by java_library rule, by jar tool, by zip
  //  * resource files
  //  * classpath resource files
  //  *
  string out_path = OutputFilePath("out.jar");
  string testjar_path = OutputFilePath("testinput.jar");
  {
    std::string jar_tool_path = runfiles->Rlocation(JAR_TOOL_PATH);
    string textfile_path = CreateTextFile("jar_testinput.txt", "jar_inputtext");
    string classfile_path = CreateTextFile("JarTestInput.class", "Dummy");
    unlink(testjar_path.c_str());
    ASSERT_EQ(
        0, RunCommand(jar_tool_path.c_str(), "-cf", testjar_path.c_str(),
                      textfile_path.c_str(), classfile_path.c_str(), nullptr));
  }

  string testzip_path = OutputFilePath("testinput.zip");
  {
    string textfile_path = CreateTextFile("zip_testinput.txt", "zip_inputtext");
    string classfile_path = CreateTextFile("ZipTestInput.class", "Dummy");
    unlink(testzip_path.c_str());
    ASSERT_EQ(
        0, RunCommand("zip", "-m", testzip_path.c_str(), textfile_path.c_str(),
                      classfile_path.c_str(), nullptr));
  }

  string resource_path = CreateTextFile("resource", "resource_text");
  string cp_resource_path = CreateTextFile("cp_resource", "cp_resource_text");

  // TODO(asmundak): check the following generated entries, too:
  //  * services
  //  * spring.schemas
  //  * spring.handlers
  //  * protobuf.meta
  //  * extra combiner

  CreateOutput(
      out_path,
      {"--normalize", "--sources",
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest1.jar")
           .c_str(),
       testjar_path, testzip_path, "--resources", resource_path,
       "--classpath_resources", cp_resource_path});

  // Scan all entries, verify that *.class entries have timestamp
  // 01/01/2010 00:00:02 and the rest have the timestamp of 01/01/2010 00:00:00.
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = cdh->file_name_string();
    EXPECT_EQ(lh->last_mod_file_date(), cdh->last_mod_file_date())
        << entry_name << " modification date";
    EXPECT_EQ(lh->last_mod_file_time(), cdh->last_mod_file_time())
        << entry_name << " modification time";
    EXPECT_EQ(15393, cdh->last_mod_file_date())
        << entry_name << " modification date should be 01/01/2010";
    auto n = entry_name.size() - strlen(".class");
    if (0 == strcmp(entry_name.c_str() + n, ".class")) {
      EXPECT_EQ(1, cdh->last_mod_file_time())
          << entry_name
          << " modification time for .class entry should be 00:00:02";
    } else {
      EXPECT_EQ(0, cdh->last_mod_file_time())
          << entry_name
          << " modification time for non .class entry should be 00:00:00";
    }
    // Zip creates Unix timestamps, too. Check that normalization removes them.
    ASSERT_EQ(nullptr, cdh->unix_time_extra_field())
        << entry_name << ": CDH should not have Unix Time extra field";
    ASSERT_EQ(nullptr, lh->unix_time_extra_field())
        << entry_name << ": LH should not have Unix Time extra field";
  }
  input_jar.Close();
}

// --add_missing_directories
TEST_F(OutputJarSimpleTest, AddMissingDirectories) {
  string out_path = OutputFilePath("out.jar");
  string testjar_path = OutputFilePath("testinput.jar");

  std::string jar_tool_path = runfiles->Rlocation(JAR_TOOL_PATH);
  string textfile_path =
      CreateTextFile("a/b/jar_testinput.txt", "jar_inputtext");
  string classfile1_path = CreateTextFile("a/c/Foo.class", "Dummy");
  string classfile2_path = CreateTextFile("c/Foo.class", "Dummy");
  string classfile3_path = CreateTextFile("c/Bar.class", "Dummy");
  unlink(testjar_path.c_str());
  ASSERT_EQ(
      0, RunCommand(jar_tool_path.c_str(), "-cf", testjar_path.c_str(),
                    textfile_path.c_str(), classfile1_path.c_str(),
                    classfile2_path.c_str(), classfile3_path.c_str(), nullptr));

  CreateOutput(out_path, {"--normalize", "--add_missing_directories",
                          "--sources", testjar_path});

  // Scan all entries, verify that *.class entries have timestamp
  // 01/01/2010 00:00:02 and the rest have the timestamp of 01/01/2010 00:00:00.
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  bool seen_a = false;
  bool seen_ab = false;
  bool seen_ac = false;
  bool seen_c = false;
  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = cdh->file_name_string();
    EXPECT_EQ(lh->last_mod_file_date(), cdh->last_mod_file_date())
        << entry_name << " modification date";
    EXPECT_EQ(lh->last_mod_file_time(), cdh->last_mod_file_time())
        << entry_name << " modification time";
    EXPECT_EQ(15393, cdh->last_mod_file_date())
        << entry_name << " modification date should be 01/01/2010";
    auto n = entry_name.size() - strlen(".class");
    if (0 == strcmp(entry_name.c_str() + n, ".class")) {
      EXPECT_EQ(1, cdh->last_mod_file_time())
          << entry_name
          << " modification time for .class entry should be 00:00:02";
    } else {
      EXPECT_EQ(0, cdh->last_mod_file_time())
          << entry_name
          << " modification time for non .class entry should be 00:00:00";
    }
    // Zip creates Unix timestamps, too. Check that normalization removes them.
    ASSERT_EQ(nullptr, cdh->unix_time_extra_field())
        << entry_name << ": CDH should not have Unix Time extra field";
    ASSERT_EQ(nullptr, lh->unix_time_extra_field())
        << entry_name << ": LH should not have Unix Time extra field";

    if (EndsWith(entry_name, "/a/")) {
      EXPECT_FALSE(seen_a) << "a/ duplicate";
      seen_a = true;
    } else if (EndsWith(entry_name, "/a/b/")) {
      EXPECT_FALSE(seen_ab) << "a/b/ duplicate";
      seen_ab = true;
    } else if (EndsWith(entry_name, "/a/c/")) {
      EXPECT_FALSE(seen_ac) << "a/c/ duplicate";
      seen_ac = true;
    } else if (EndsWith(entry_name, "/c/")) {
      EXPECT_FALSE(seen_c) << "c/ duplicate";
      seen_c = true;
    }
  }
  EXPECT_TRUE(seen_a) << "a/ entry missing";
  EXPECT_TRUE(seen_ab) << "a/b/ entry missing";
  EXPECT_TRUE(seen_ac) << "a/c/ entry missing";
  EXPECT_TRUE(seen_c) << "c/ entry missing";
  input_jar.Close();
}

// The files names META-INF/services/<something> are concatenated.
// The files named META-INF/spring.handlers are concatenated.
// The files named META-INF/spring.schemas are concatenated.
TEST_F(OutputJarSimpleTest, Services) {
  CreateTextFile("META-INF/services/spi.DateProvider",
                 "my.DateProviderImpl1\n");
  CreateTextFile("META-INF/services/spi.TimeProvider",
                 "my.TimeProviderImpl1\n");
  CreateTextFile("META-INF/spring.handlers", "handler1\n");
  CreateTextFile("META-INF/spring.schemas", "schema1\n");

  // We have to be in the output directory if we want to have entries in the
  // archive to start with META-INF. The resulting zip will contain 4 entries:
  //   META-INF/services/spi.DateProvider
  //   META-INF/services/spi.TimeProvider
  //   META-INF/spring.handlers
  //   META-INF/spring.schemas
  string out_dir = OutputFilePath("");
  ASSERT_EQ(0, RunCommand("cd", out_dir.c_str(), CMD_SEPARATOR, "zip", "-mr",
                          "testinput1.zip", "META-INF", nullptr));
  string zip1_path = OutputFilePath("testinput1.zip");

  // Create the second zip, with 3 files:
  //   META-INF/services/spi.DateProvider.
  //   META-INF/spring.handlers
  //   META-INF/spring.schemas
  CreateTextFile("META-INF/services/spi.DateProvider",
                 "my.DateProviderImpl2\n");
  CreateTextFile("META-INF/spring.handlers", "handler2\n");
  CreateTextFile("META-INF/spring.schemas", "schema2\n");
  ASSERT_EQ(0, RunCommand("cd ", out_dir.c_str(), CMD_SEPARATOR, "zip", "-mr",
                          "testinput2.zip", "META-INF", nullptr));
  string zip2_path = OutputFilePath("testinput2.zip");

  // The output jar should contain two service entries. The contents of the
  // META-INF/services/spi.DateProvider should be the concatenation of the
  // contents of this entry from both archives. And it should also contain
  // spring.handlers and spring.schemas entries.
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--sources", zip1_path, zip2_path});
  EXPECT_EQ("my.DateProviderImpl1\n" "my.DateProviderImpl2\n",
            GetEntryContents(out_path, "META-INF/services/spi.DateProvider"));
  EXPECT_EQ("my.TimeProviderImpl1\n",
            GetEntryContents(out_path, "META-INF/services/spi.TimeProvider"));

  EXPECT_EQ("schema1\n" "schema2\n",
            GetEntryContents(out_path, "META-INF/spring.schemas"));
  EXPECT_EQ("handler1\n" "handler2\n",
            GetEntryContents(out_path, "META-INF/spring.handlers"));
}

// Test that in the absence of the compression option all the plain files in
// the output archive are not compressed but just stored.
TEST_F(OutputJarSimpleTest, NoCompressionOption) {
  string out_path = CompressionOptionsTestingJar("");
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = lh->file_name_string();
    // Each file entry is compressed, each directory entry is uncompressed.
    EXPECT_EQ(lh->compression_method(), cdh->compression_method());
    EXPECT_EQ(Z_NO_COMPRESSION, lh->compression_method())
        << "Entry " << entry_name << " should be stored.";
  }
  input_jar.Close();
}

// Test --compression option. If enabled, all file entries are compressed
// while all directory entries remain uncompressed.
TEST_F(OutputJarSimpleTest, CompressionOption) {
  string out_path = CompressionOptionsTestingJar("--compression");
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = lh->file_name_string();
    // Each file entry is compressed, each directory entry is uncompressed.
    EXPECT_EQ(lh->compression_method(), cdh->compression_method());
    if (lh->file_name()[lh->file_name_length() - 1] != '/') {
      EXPECT_EQ(Z_DEFLATED, lh->compression_method())
          << "File entry " << entry_name << " should be compressed.";
    } else {
      EXPECT_EQ(Z_NO_COMPRESSION, lh->compression_method())
          << "Directory entry " << entry_name << " should be stored.";
    }
  }
  input_jar.Close();
}

// Test --dontchangecompression option. If enabled, existing file entries are
// copied as is, and created entries are compressed.
// Test --compression option. If enabled, all file entries are compressed
// while all directory entries remain uncompressed.
TEST_F(OutputJarSimpleTest, DontChangeCompressionOption) {
  string out_path = CompressionOptionsTestingJar("--dont_change_compression");
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  std::string kStoredEntry = DATA_DIR_TOP "src/tools/singlejar/output_jar.cc";

  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = lh->file_name_string();
    EXPECT_EQ(lh->compression_method(), cdh->compression_method());
    if (lh->file_name()[lh->file_name_length() - 1] != '/') {
      // All created file entries are compressed, and so are all the file
      // entries from the input jar created by the java_library rule. Only
      // the file entries from the 'stored_jar' should be uncompressed, and
      // it contains a single one:
      if (entry_name == kStoredEntry) {
        EXPECT_EQ(Z_NO_COMPRESSION, lh->compression_method())
            << "File entry " << entry_name << " should be stored.";
      } else {
        EXPECT_EQ(Z_DEFLATED, lh->compression_method())
            << "File entry " << entry_name << " should be compressed.";
      }
    } else {
      EXPECT_EQ(Z_NO_COMPRESSION, lh->compression_method())
          << "Directory entry " << entry_name << " should be stored.";
    }
  }
  input_jar.Close();
}

const char kBuildDataFile[] = "build-data.properties";

// Test --exclude_build_data option when none of the source archives contain
// build-data.properties file: no such file in the output archive.
TEST_F(OutputJarSimpleTest, ExcludeBuildData1) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--exclude_build_data"});
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    string entry_name = lh->file_name_string();
    EXPECT_NE(kBuildDataFile, lh->file_name_string());
  }
  input_jar.Close();
}

// Test --exclude_build_data option when a source archive contains
// build-data.properties file, it should be then copied to the output.
TEST_F(OutputJarSimpleTest, ExcludeBuildData2) {
  string out_dir = OutputFilePath("");
  string testzip_path = OutputFilePath("testinput.zip");
  string buildprop_path = CreateTextFile(kBuildDataFile, "build: foo");
  unlink(testzip_path.c_str());
  ASSERT_EQ(0, RunCommand("cd ", out_dir.c_str(), CMD_SEPARATOR, "zip", "-m",
                          "testinput.zip", kBuildDataFile, nullptr));
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--exclude_build_data", "--sources", testzip_path});
  EXPECT_EQ("build: foo", GetEntryContents(out_path, kBuildDataFile));
}

// Test that the entries with suffixes in --nocompressed_suffixes are
// not compressed. This applies both to the source archives' entries and
// standalone files.
TEST_F(OutputJarSimpleTest, Nocompress) {
  string res1_path =
      CreateTextFile("resource.foo", "line1\nline2\nline3\nline4\n");
  string res2_path =
      CreateTextFile("resource.bar", "line1\nline2\nline3\nline4\n");
  string out_path = OutputFilePath("out.jar");
  CreateOutput(
      out_path,
      {"--compression", "--sources",
       runfiles
           ->Rlocation(
               "io_bazel/src/tools/singlejar/libtest1.jar")
           .c_str(),
       "--resources", res1_path, res2_path, "--nocompress_suffixes", ".foo",
       ".h"});
  InputJar input_jar;
  ASSERT_TRUE(input_jar.Open(out_path));
  const LH *lh;
  const CDH *cdh;
  while ((cdh = input_jar.NextEntry(&lh))) {
    const char *entry_name_end = lh->file_name() + lh->file_name_length();
    if (!strncmp(entry_name_end - 4, ".foo", 4) ||
        !strncmp(entry_name_end - 2, ".h", 2)) {
      EXPECT_EQ(Z_NO_COMPRESSION, lh->compression_method())
          << "Expected " << lh->file_name_string() << " uncompressed";
    } else if (!strncmp(entry_name_end - 3, ".cc", 3) ||
               !strncmp(entry_name_end - 4, ".bar", 4)) {
      EXPECT_EQ(Z_DEFLATED, lh->compression_method())
          << "Expected " << lh->file_name_string() << " compressed";
    }
  }
  input_jar.Close();
}

// --multi_release option.
TEST_F(OutputJarSimpleTest, MultiRelease) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--multi_release"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Multi-Release: true\r\n"
      "\r\n",
      manifest);
}

// --multi_release option doesn't override --deploy_manifest_lines.
TEST_F(OutputJarSimpleTest, MultiReleaseManifestLines) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--multi_release", "--deploy_manifest_lines",
                          "Multi-Release: false"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "\r\n",
      manifest);
}

// --hermetic_java_home
TEST_F(OutputJarSimpleTest, HermeticJavaHome) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--hermetic_java_home", "foo/bar/java_home"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Hermetic-Java-Home: foo/bar/java_home\r\n"
      "\r\n",
      manifest);
}

// --add_exports and --add_opens options combines with --deploy_manifest_lines.
TEST_F(OutputJarSimpleTest, AddExportsManifestLines) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--add_exports", "foo/com.export", "--add_opens",
                "foo/com.open", "--deploy_manifest_lines",
                "Add-Exports: bar/com.export", "Add-Opens: bar/com.open"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Add-Exports: bar/com.export\r\n"
      "  foo/com.export\r\n"
      "Add-Opens: bar/com.open\r\n"
      "  foo/com.open\r\n"
      "\r\n",
      manifest);
}

// Deduplicate --add_exports
TEST_F(OutputJarSimpleTest, AddExportsDuplicate) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path,
               {"--add_exports", "foo/export", "--add_exports", "foo/export"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Add-Exports: foo/export\r\n"
      "\r\n",
      manifest);
}

// Tokenize, sort, and deduplicate existing Add-Exports lines
TEST_F(OutputJarSimpleTest, AddExportsTokenize) {
  string out_path = OutputFilePath("out.jar");
  CreateOutput(out_path, {"--deploy_manifest_lines",
                          "Add-Exports: foo/export bar/export foo/export"});
  string manifest = GetEntryContents(out_path, "META-INF/MANIFEST.MF");
  EXPECT_EQ(
      "Manifest-Version: 1.0\r\n"
      "Created-By: singlejar\r\n"
      "Add-Exports: bar/export\r\n"
      "  foo/export\r\n"
      "\r\n",
      manifest);
}

}  // namespace
