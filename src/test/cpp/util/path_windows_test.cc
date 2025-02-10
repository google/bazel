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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <stdio.h>
#include <string.h>

#include <algorithm>
#include <memory>
#include <string>

#include "gtest/gtest.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/util.h"
#include "src/test/cpp/util/test_util.h"
#include "src/test/cpp/util/windows_test_util.h"

#if !defined(_WIN32) && !defined(__CYGWIN__)
#error("This test should only be run on Windows")
#endif  // !defined(_WIN32) && !defined(__CYGWIN__)

namespace blaze_util {

using std::string;
using std::unique_ptr;
using std::wstring;

TEST(PathWindowsTest, TestDirname) {
  ASSERT_EQ("", Dirname(""));
  ASSERT_EQ("/", Dirname("/"));
  ASSERT_EQ("", Dirname("foo"));
  ASSERT_EQ("/", Dirname("/foo"));
  ASSERT_EQ("/foo", Dirname("/foo/"));
  ASSERT_EQ("foo", Dirname("foo/bar"));
  ASSERT_EQ("foo/bar", Dirname("foo/bar/baz"));
  ASSERT_EQ("\\", Dirname("\\foo"));
  ASSERT_EQ("\\foo", Dirname("\\foo\\"));
  ASSERT_EQ("foo", Dirname("foo\\bar"));
  ASSERT_EQ("foo\\bar", Dirname("foo\\bar\\baz"));
  ASSERT_EQ("foo\\bar/baz", Dirname("foo\\bar/baz\\qux"));
  ASSERT_EQ("c:/", Dirname("c:/"));
  ASSERT_EQ("c:\\", Dirname("c:\\"));
  ASSERT_EQ("c:/", Dirname("c:/foo"));
  ASSERT_EQ("c:\\", Dirname("c:\\foo"));
  ASSERT_EQ("\\\\?\\c:\\", Dirname("\\\\?\\c:\\"));
  ASSERT_EQ("\\\\?\\c:\\", Dirname("\\\\?\\c:\\foo"));
}

TEST(PathWindowsTest, TestBasename) {
  ASSERT_EQ("", Basename(""));
  ASSERT_EQ("", Basename("/"));
  ASSERT_EQ("foo", Basename("foo"));
  ASSERT_EQ("foo", Basename("/foo"));
  ASSERT_EQ("", Basename("/foo/"));
  ASSERT_EQ("bar", Basename("foo/bar"));
  ASSERT_EQ("baz", Basename("foo/bar/baz"));
  ASSERT_EQ("foo", Basename("\\foo"));
  ASSERT_EQ("", Basename("\\foo\\"));
  ASSERT_EQ("bar", Basename("foo\\bar"));
  ASSERT_EQ("baz", Basename("foo\\bar\\baz"));
  ASSERT_EQ("qux", Basename("foo\\bar/baz\\qux"));
  ASSERT_EQ("", Basename("c:/"));
  ASSERT_EQ("", Basename("c:\\"));
  ASSERT_EQ("foo", Basename("c:/foo"));
  ASSERT_EQ("foo", Basename("c:\\foo"));
  ASSERT_EQ("", Basename("\\\\?\\c:\\"));
  ASSERT_EQ("foo", Basename("\\\\?\\c:\\foo"));
}

TEST(PathWindowsTest, TestIsAbsolute) {
  ASSERT_FALSE(IsAbsolute(""));
  ASSERT_TRUE(IsAbsolute("/"));
  ASSERT_TRUE(IsAbsolute("/foo"));
  ASSERT_TRUE(IsAbsolute("\\"));
  ASSERT_TRUE(IsAbsolute("\\foo"));
  ASSERT_FALSE(IsAbsolute("c:"));
  ASSERT_TRUE(IsAbsolute("c:/"));
  ASSERT_TRUE(IsAbsolute("c:\\"));
  ASSERT_TRUE(IsAbsolute("c:\\foo"));
  ASSERT_TRUE(IsAbsolute("\\\\?\\c:\\"));
  ASSERT_TRUE(IsAbsolute("\\\\?\\c:\\foo"));
}

TEST(PathWindowsTest, TestIsRootDirectory) {
  ASSERT_FALSE(IsRootDirectory(""));
  ASSERT_TRUE(IsRootDirectory("/"));
  ASSERT_FALSE(IsRootDirectory("/foo"));
  ASSERT_TRUE(IsRootDirectory("\\"));
  ASSERT_FALSE(IsRootDirectory("\\foo"));
  ASSERT_FALSE(IsRootDirectory("c:"));
  ASSERT_TRUE(IsRootDirectory("c:/"));
  ASSERT_TRUE(IsRootDirectory("c:\\"));
  ASSERT_FALSE(IsRootDirectory("c:\\foo"));
  ASSERT_TRUE(IsRootDirectory("\\\\?\\c:\\"));
  ASSERT_FALSE(IsRootDirectory("\\\\?\\c:\\foo"));
}

TEST(PathWindowsTest, TestAsWindowsPath) {
  SetEnvironmentVariableA("BAZEL_SH", "c:\\some\\long/path\\bin\\bash.exe");
  wstring actual;

  // Null and empty input produces empty result.
  ASSERT_TRUE(AsWindowsPath("", &actual, nullptr));
  ASSERT_EQ(wstring(L""), actual);

  // If the path has a "\\?\" prefix, AsWindowsPath assumes it's a correct
  // Windows path. If it's not, the Windows API function that we pass the path
  // to will fail anyway.
  ASSERT_TRUE(AsWindowsPath("\\\\?\\anything/..", &actual, nullptr));
  ASSERT_EQ(wstring(L"\\\\?\\anything/.."), actual);

  // Trailing slash or backslash is removed.
  ASSERT_TRUE(AsWindowsPath("foo/", &actual, nullptr));
  ASSERT_EQ(wstring(L"foo"), actual);
  ASSERT_TRUE(AsWindowsPath("foo\\", &actual, nullptr));
  ASSERT_EQ(wstring(L"foo"), actual);

  // Slashes are converted to backslash.
  ASSERT_TRUE(AsWindowsPath("foo/bar", &actual, nullptr));
  ASSERT_EQ(wstring(L"foo\\bar"), actual);
  ASSERT_TRUE(AsWindowsPath("c:/", &actual, nullptr));
  ASSERT_EQ(wstring(L"c:\\"), actual);
  ASSERT_TRUE(AsWindowsPath("c:\\", &actual, nullptr));
  ASSERT_EQ(wstring(L"c:\\"), actual);

  // Invalid paths
  string error;
  ASSERT_FALSE(AsWindowsPath("c:", &actual, &error));
  EXPECT_TRUE(error.find("working-directory relative paths") != string::npos);
  ASSERT_FALSE(AsWindowsPath("c:foo", &actual, &error));
  EXPECT_TRUE(error.find("working-directory relative paths") != string::npos);
  ASSERT_FALSE(AsWindowsPath("\\\\foo", &actual, &error));
  EXPECT_TRUE(error.find("network paths") != string::npos);

  // /dev/null and NUL produce NUL.
  ASSERT_TRUE(AsWindowsPath("/dev/null", &actual, nullptr));
  ASSERT_EQ(wstring(L"NUL"), actual);
  ASSERT_TRUE(AsWindowsPath("Nul", &actual, nullptr));
  ASSERT_EQ(wstring(L"NUL"), actual);

  // MSYS path with drive letter.
  ASSERT_FALSE(AsWindowsPath("/c", &actual, &error));
  EXPECT_TRUE(error.find("Unix-style") != string::npos);
  ASSERT_FALSE(AsWindowsPath("/c/", &actual, &error));
  EXPECT_TRUE(error.find("Unix-style") != string::npos);

  // Absolute-on-current-drive path gets a drive letter.
  ASSERT_TRUE(AsWindowsPath("\\foo", &actual, nullptr));
  ASSERT_EQ(wstring(1, GetCwd()[0]) + L":\\foo", actual);

  // Even for long paths, AsWindowsPath doesn't add a "\\?\" prefix (it's the
  // caller's duty to do so).
  wstring wlongpath(L"dummy_long_path\\");
  string longpath("dummy_long_path/");
  while (longpath.size() <= MAX_PATH) {
    wlongpath += wlongpath;
    longpath += longpath;
  }
  wlongpath.pop_back();  // remove trailing "\"
  ASSERT_TRUE(AsWindowsPath(longpath, &actual, nullptr));
  ASSERT_EQ(wlongpath, actual);
}

TEST(PathWindowsTest, TestAsAbsoluteWindowsPath) {
  SetEnvironmentVariableA("BAZEL_SH", "c:\\some\\long/path\\bin\\bash.exe");
  wstring actual;

  ASSERT_TRUE(AsAbsoluteWindowsPath("c:/", &actual, nullptr));
  ASSERT_EQ(L"\\\\?\\c:\\", actual);

  ASSERT_TRUE(AsAbsoluteWindowsPath(L"c:/", &actual, nullptr));
  ASSERT_EQ(L"\\\\?\\c:\\", actual);

  ASSERT_TRUE(AsAbsoluteWindowsPath("c:/..\\non-existent//", &actual, nullptr));
  ASSERT_EQ(L"\\\\?\\c:\\non-existent", actual);

  ASSERT_TRUE(
      AsAbsoluteWindowsPath(L"c:/..\\non-existent//", &actual, nullptr));
  ASSERT_EQ(L"\\\\?\\c:\\non-existent", actual);

  WCHAR cwd[MAX_PATH];
  wstring cwdw(CstringToWstring(GetCwd()));
  wstring expected =
      wstring(L"\\\\?\\") + cwdw +
      ((cwdw.back() == L'\\') ? L"non-existent" : L"\\non-existent");
  ASSERT_TRUE(AsAbsoluteWindowsPath("non-existent", &actual, nullptr));
  ASSERT_EQ(actual, expected);

  ASSERT_TRUE(AsAbsoluteWindowsPath(L"non-existent", &actual, nullptr));
  ASSERT_EQ(actual, expected);
}

TEST(PathWindowsTest, TestAsShortWindowsPath) {
  string actual;
  ASSERT_TRUE(AsShortWindowsPath("/dev/null", &actual, nullptr));
  ASSERT_EQ(string("NUL"), actual);

  ASSERT_TRUE(AsShortWindowsPath("nul", &actual, nullptr));
  ASSERT_EQ(string("NUL"), actual);

  ASSERT_TRUE(AsShortWindowsPath("C://", &actual, nullptr));
  ASSERT_EQ(string("c:\\"), actual);

  string error;
  ASSERT_FALSE(AsShortWindowsPath("/C//", &actual, &error));
  EXPECT_TRUE(error.find("Unix-style") != string::npos);

  // The A drive usually doesn't exist but AsShortWindowsPath should still work.
  // Here we even have multiple trailing slashes, that should be handled too.
  ASSERT_TRUE(AsShortWindowsPath("A://", &actual, nullptr));
  ASSERT_EQ(string("a:\\"), actual);

  // Assert that we can shorten the TEST_TMPDIR.
  char buf[MAX_PATH] = {0};
  DWORD len = ::GetEnvironmentVariableA("TEST_TMPDIR", buf, MAX_PATH);
  string tmpdir = buf;
  ASSERT_GT(tmpdir.size(), 0);
  string short_tmpdir;
  ASSERT_TRUE(AsShortWindowsPath(tmpdir, &short_tmpdir, nullptr));
  ASSERT_LT(0, short_tmpdir.size());
  ASSERT_TRUE(PathExists(short_tmpdir));

  // Assert that a trailing "/" doesn't change the shortening logic and it will
  // be stripped from the result.
  ASSERT_TRUE(AsShortWindowsPath(tmpdir + "/", &actual, nullptr));
  ASSERT_EQ(actual, short_tmpdir);
  ASSERT_NE(actual.back(), '/');
  ASSERT_NE(actual.back(), '\\');

  // Assert shortening another long path, and that the result is lowercased.
  string dirname(JoinPath(short_tmpdir, "LONGpathNAME"));
  ASSERT_EQ(0, mkdir(dirname.c_str()));
  ASSERT_TRUE(PathExists(dirname));
  ASSERT_TRUE(AsShortWindowsPath(dirname, &actual, nullptr));
  ASSERT_EQ(short_tmpdir + "\\longpa~1", actual);

  // Assert shortening non-existent paths.
  ASSERT_TRUE(AsShortWindowsPath(JoinPath(tmpdir, "NonExistent/FOO"), &actual,
                                 nullptr));
  ASSERT_EQ(short_tmpdir + "\\nonexistent\\foo", actual);
}

TEST(PathWindowsTest, TestMsysRootRetrieval) {
  wstring actual;

  // We just need "bin/<something>" or "usr/bin/<something>".
  // Forward slashes are converted to backslashes.
  SetEnvironmentVariableA("BAZEL_SH", "c:/foo\\bin/some_bash.exe");

  string error;
  ASSERT_FALSE(AsWindowsPath("/blah", &actual, &error));
  EXPECT_TRUE(error.find("Unix-style") != string::npos);

  SetEnvironmentVariableA("BAZEL_SH", "c:/msys64/usr/bin/bash.exe");
  ASSERT_FALSE(AsWindowsPath("/blah", &actual, &error));
  EXPECT_TRUE(error.find("Unix-style") != string::npos);
}

TEST(PathWindowsTest, IsWindowsDevNullTest) {
  ASSERT_TRUE(IsDevNull("nul"));
  ASSERT_TRUE(IsDevNull("NUL"));
  ASSERT_TRUE(IsDevNull("nuL"));
  ASSERT_TRUE(IsDevNull("/dev/null"));
  ASSERT_FALSE(IsDevNull("/Dev/Null"));
  ASSERT_FALSE(IsDevNull("dev/null"));
  ASSERT_FALSE(IsDevNull("/dev/nul"));
  ASSERT_FALSE(IsDevNull("/dev/nulll"));
  ASSERT_FALSE(IsDevNull("nu"));
  ASSERT_FALSE(IsDevNull((char *) nullptr));
  ASSERT_FALSE(IsDevNull(""));
}

TEST(PathWindowsTest, ConvertPathTest) {
  EXPECT_EQ("c:\\foo", ConvertPath("C:\\FOO"));
  EXPECT_EQ("c:\\", ConvertPath("c:/"));
  EXPECT_EQ("c:\\foo\\bar", ConvertPath("c:/../foo\\BAR\\.\\"));
}

TEST(PathWindowsTest, MakeAbsolute) {
  EXPECT_EQ("c:\\foo\\bar", MakeAbsolute("C:\\foo\\BAR"));
  EXPECT_EQ("c:\\foo\\bar", MakeAbsolute("C:/foo/bar"));
  EXPECT_EQ("c:\\foo\\bar", MakeAbsolute("C:\\foo\\bar\\"));
  EXPECT_EQ("c:\\foo\\bar", MakeAbsolute("C:/foo/bar/"));
  EXPECT_EQ(blaze_util::ToLower(blaze_util::GetCwd()) + "\\foo",
            MakeAbsolute("foo"));

  EXPECT_EQ("nul", MakeAbsolute("NUL"));
  EXPECT_EQ("nul", MakeAbsolute("Nul"));
  EXPECT_EQ("nul", MakeAbsolute("nul"));
  EXPECT_EQ("nul", MakeAbsolute("/dev/null"));

  EXPECT_EQ("", MakeAbsolute(""));
}

TEST(PathWindowsTest, MakeAbsoluteAndResolveEnvvars_WithTmpdir) {
  // We cannot test the system-default paths like %ProgramData% because these
  // are wiped from the test environment. TestTmpdir is set by Bazel though,
  // so serves as a fine substitute.
  char buf[MAX_PATH] = {0};
  DWORD len = ::GetEnvironmentVariableA("TEST_TMPDIR", buf, MAX_PATH);
  const std::string tmpdir = buf;
  const std::string expected_tmpdir_bar = ConvertPath(tmpdir + "\\bar");

  EXPECT_EQ(expected_tmpdir_bar,
            MakeAbsoluteAndResolveEnvvars("%TEST_TMPDIR%\\bar"));
  EXPECT_EQ(expected_tmpdir_bar,
            MakeAbsoluteAndResolveEnvvars("%Test_Tmpdir%\\bar"));
  EXPECT_EQ(expected_tmpdir_bar,
            MakeAbsoluteAndResolveEnvvars("%test_tmpdir%\\bar"));
  EXPECT_EQ(expected_tmpdir_bar,
            MakeAbsoluteAndResolveEnvvars("%test_tmpdir%/bar"));
}

TEST(PathWindowsTest, MakeAbsoluteAndResolveEnvvars_LongPaths) {
  const std::string long_path = "c:\\" + std::string(MAX_PATH, 'a');
  blaze::SetEnv("long", long_path);

  EXPECT_EQ(long_path, MakeAbsoluteAndResolveEnvvars("%long%"));
}

}  // namespace blaze_util
