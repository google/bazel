// Copyright 2017 The Bazel Authors. All rights reserved.
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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include "src/tools/launcher/util/launcher_util.h"

#include <windows.h>

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/native/windows/util.h"
#include "gtest/gtest.h"

namespace bazel {
namespace launcher {

using std::getenv;
using std::ios;
using std::ofstream;
using std::string;
using std::wstring;

class LaunchUtilTest : public ::testing::Test {
 protected:
  LaunchUtilTest() {}

  virtual ~LaunchUtilTest() {}

  void SetUp() override {
    char* tmpdir = getenv("TEST_TMPDIR");
    if (tmpdir != nullptr) {
      test_tmpdir = blaze_util::CstringToWstring(string(tmpdir));
    } else {
      tmpdir = getenv("TEMP");
      ASSERT_FALSE(tmpdir == nullptr);
      test_tmpdir = blaze_util::CstringToWstring(string(tmpdir));
    }
  }

  void TearDown() override {}

  wstring GetTmpDir() { return this->test_tmpdir; }

  // Create an empty file at path
  static void CreateEmptyFile(const wstring& path) {
    ofstream file_stream(path.c_str(), ios::out | ios::binary);
    file_stream.put('\0');
  }

 private:
  wstring test_tmpdir;
};

TEST_F(LaunchUtilTest, GetBinaryPathWithoutExtensionTest) {
  ASSERT_EQ(L"foo", GetBinaryPathWithoutExtension(L"foo.exe"));
  ASSERT_EQ(L"foo.sh", GetBinaryPathWithoutExtension(L"foo.sh.exe"));
  ASSERT_EQ(L"Foo", GetBinaryPathWithoutExtension(L"Foo.EXE"));
  ASSERT_EQ(L"Foo.Sh", GetBinaryPathWithoutExtension(L"Foo.Sh.EXE"));
  ASSERT_EQ(L"foo.sh", GetBinaryPathWithoutExtension(L"foo.sh"));
}

TEST_F(LaunchUtilTest, GetBinaryPathWithExtensionTest) {
  ASSERT_EQ(L"foo.exe", GetBinaryPathWithExtension(L"foo"));
  ASSERT_EQ(L"foo.exe", GetBinaryPathWithExtension(L"foo.exe"));
  ASSERT_EQ(L"Foo.EXE", GetBinaryPathWithExtension(L"Foo.EXE"));
  ASSERT_EQ(L"foo.sh.exe", GetBinaryPathWithExtension(L"foo.sh.exe"));
  ASSERT_EQ(L"Foo.Sh.EXE", GetBinaryPathWithExtension(L"Foo.Sh.EXE"));
  ASSERT_EQ(L"foo.sh.exe", GetBinaryPathWithExtension(L"foo.sh"));
}

TEST_F(LaunchUtilTest, BashEscapeArgTest) {
  ASSERT_EQ(L"\"\"", BashEscapeArg(L""));
  ASSERT_EQ(L"foo", BashEscapeArg(L"foo"));
  ASSERT_EQ(L"\"foo bar\"", BashEscapeArg(L"foo bar"));
  ASSERT_EQ(L"\"\\\"foo bar\\\"\"", BashEscapeArg(L"\"foo bar\""));
  ASSERT_EQ(L"foo\\\\bar", BashEscapeArg(L"foo\\bar"));
  ASSERT_EQ(L"foo\\\"bar", BashEscapeArg(L"foo\"bar"));
  ASSERT_EQ(L"C:\\\\foo\\\\bar\\\\", BashEscapeArg(L"C:\\foo\\bar\\"));
  ASSERT_EQ(L"\"C:\\\\foo foo\\\\bar\\\\\"",
            BashEscapeArg(L"C:\\foo foo\\bar\\"));
}

TEST_F(LaunchUtilTest, DoesFilePathExistTest) {
  wstring file1 = GetTmpDir() + L"/foo";
  wstring file2 = GetTmpDir() + L"/bar";
  CreateEmptyFile(file1);
  ASSERT_TRUE(DoesFilePathExist(file1.c_str()));
  ASSERT_FALSE(DoesFilePathExist(file2.c_str()));
}

TEST_F(LaunchUtilTest, DoesDirectoryPathExistTest) {
  wstring dir1 = GetTmpDir() + L"/dir1";
  wstring dir2 = GetTmpDir() + L"/dir2";
  CreateDirectoryW(dir1.c_str(), nullptr);
  ASSERT_TRUE(DoesDirectoryPathExist(dir1.c_str()));
  ASSERT_FALSE(DoesDirectoryPathExist(dir2.c_str()));
}

TEST_F(LaunchUtilTest, SetAndGetEnvTest) {
  ASSERT_TRUE(SetEnv(L"foo", L"bar"));
  wstring value;
  ASSERT_TRUE(GetEnv(L"foo", &value));
  ASSERT_EQ(value, L"bar");
  SetEnv(L"FOO", L"");
  ASSERT_FALSE(GetEnv(L"FOO", &value));
}

TEST_F(LaunchUtilTest, NormalizePathTest) {
  wstring value;
  ASSERT_TRUE(NormalizePath(L"C:\\foo\\bar\\", &value));
  ASSERT_EQ(L"c:\\foo\\bar", value);
  ASSERT_TRUE(NormalizePath(L"c:/foo/bar/", &value));
  ASSERT_EQ(L"c:\\foo\\bar", value);
  ASSERT_TRUE(NormalizePath(L"FoO\\\\bAr\\", &value));
  ASSERT_EQ(L"foo\\bar", value);
  ASSERT_TRUE(NormalizePath(L"X\\Y/Z\\", &value));
  ASSERT_EQ(L"x\\y\\z", value);
  ASSERT_TRUE(NormalizePath(L"c://foo//bar", &value));
  ASSERT_EQ(L"c:\\foo\\bar", value);
  ASSERT_FALSE(NormalizePath(L"c:foo\\bar", &value));
}

TEST_F(LaunchUtilTest, RelativeToTest) {
  wstring value;
  ASSERT_TRUE(RelativeTo(L"c:\\foo\\bar1", L"c:\\foo\\bar2", &value));
  ASSERT_EQ(L"..\\bar1", value);
  ASSERT_TRUE(RelativeTo(L"c:\\foo\\bar", L"c:\\", &value));
  ASSERT_EQ(L"foo\\bar", value);
  ASSERT_TRUE(RelativeTo(L"c:\\foo\\bar", L"c:\\foo\\bar", &value));
  ASSERT_EQ(L"", value);
  ASSERT_TRUE(RelativeTo(L"c:\\foo\\bar", L"c:\\foo", &value));
  ASSERT_EQ(L"bar", value);
  ASSERT_TRUE(RelativeTo(L"c:\\foo\\bar", L"c:\\foo\\ba", &value));
  ASSERT_EQ(L"..\\bar", value);
  ASSERT_TRUE(RelativeTo(L"c:\\", L"c:\\foo", &value));
  ASSERT_EQ(L"..\\", value);
  ASSERT_TRUE(RelativeTo(L"c:\\", L"c:\\a\\b\\c", &value));
  ASSERT_EQ(L"..\\..\\..\\", value);
  ASSERT_TRUE(RelativeTo(L"c:\\aa\\bb\\cc", L"c:\\a\\b", &value));
  ASSERT_EQ(L"..\\..\\aa\\bb\\cc", value);

  ASSERT_TRUE(RelativeTo(L"foo\\bar", L"foo\\bar", &value));
  ASSERT_EQ(L"", value);
  ASSERT_TRUE(RelativeTo(L"foo\\bar1", L"foo\\bar2", &value));
  ASSERT_EQ(L"..\\bar1", value);
  ASSERT_TRUE(RelativeTo(L"foo\\bar1", L"foo\\bar", &value));
  ASSERT_EQ(L"..\\bar1", value);
  ASSERT_TRUE(RelativeTo(L"foo\\bar1", L"foo", &value));
  ASSERT_EQ(L"bar1", value);
  ASSERT_TRUE(RelativeTo(L"foo\\bar1", L"fo", &value));
  ASSERT_EQ(L"..\\foo\\bar1", value);
  ASSERT_TRUE(RelativeTo(L"foo\\ba", L"foo\\bar", &value));
  ASSERT_EQ(L"..\\ba", value);
  ASSERT_TRUE(RelativeTo(L"foo", L"foo\\bar", &value));
  ASSERT_EQ(L"..\\", value);
  ASSERT_TRUE(RelativeTo(L"fo", L"foo\\bar", &value));
  ASSERT_EQ(L"..\\..\\fo", value);
  ASSERT_TRUE(RelativeTo(L"", L"foo\\bar", &value));
  ASSERT_EQ(L"..\\..\\", value);
  ASSERT_TRUE(RelativeTo(L"foo\\bar", L"", &value));
  ASSERT_EQ(L"foo\\bar", value);
  ASSERT_TRUE(RelativeTo(L"a\\b\\c", L"x\\y", &value));
  ASSERT_EQ(L"..\\..\\a\\b\\c", value);

  ASSERT_FALSE(RelativeTo(L"c:\\foo\\bar1", L"foo\\bar2", &value));
  ASSERT_FALSE(RelativeTo(L"c:foo\\bar1", L"c:\\foo\\bar2", &value));
  ASSERT_FALSE(RelativeTo(L"c:\\foo\\bar1", L"d:\\foo\\bar2", &value));
}

}  // namespace launcher
}  // namespace bazel
