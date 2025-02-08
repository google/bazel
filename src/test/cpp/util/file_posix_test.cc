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
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <unistd.h>

#include <map>
#include <string>
#include <vector>

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/test/cpp/util/test_util.h"
#include "googletest/include/gtest/gtest.h"
#include "absl/strings/match.h"

namespace blaze_util {

using std::map;
using std::string;

static bool Symlink(const string& old_path, const string& new_path) {
  return symlink(old_path.c_str(), new_path.c_str()) == 0;
}

static bool Symlink(const Path& old_path, const Path& new_path) {
  return Symlink(old_path.AsNativePath(), new_path.AsNativePath());
}

static bool CreateEmptyFile(const string& path) {
  // From the man page of open (man 2 open):
  // int open(const char *pathname, int flags, mode_t mode);
  //
  // mode specifies the permissions to use in case a new file is created.
  // This argument must be supplied when O_CREAT is specified in flags;
  // if O_CREAT is not specified, then mode is ignored.
  int fd = open(path.c_str(), O_CREAT | O_WRONLY, 0700);
  if (fd == -1) {
    return false;
  }
  return close(fd) == 0;
}

TEST(FilePosixTest, MakeDirectories) {
  const char* tmp_dir = getenv("TEST_TMPDIR");
  ASSERT_STRNE(tmp_dir, nullptr);
  const char* test_src_dir = getenv("TEST_SRCDIR");
  ASSERT_STRNE(nullptr, test_src_dir);

  string dir = JoinPath(tmp_dir, "x/y/z");
  bool ok = MakeDirectories(dir, 0755);
  ASSERT_TRUE(ok);

  // Changing permissions on an existing dir should work.
  ok = MakeDirectories(dir, 0750);
  ASSERT_TRUE(ok);
  struct stat filestat = {};
  ASSERT_EQ(0, stat(dir.c_str(), &filestat));
  ASSERT_EQ(mode_t(0750), filestat.st_mode & 0777);

  // srcdir shouldn't be writable.
  // TODO(ulfjack): Fix this!
  //  string srcdir = JoinPath(test_src_dir, "x/y/z");
  //  ok = MakeDirectories(srcdir, 0755);
  //  ASSERT_FALSE(ok);
  //  ASSERT_EQ(EACCES, errno);

  // Can't make a dir out of a file.
  string non_dir = JoinPath(dir, "w");
  ASSERT_TRUE(CreateEmptyFile(non_dir));
  ok = MakeDirectories(non_dir, 0755);
  ASSERT_FALSE(ok);
  ASSERT_EQ(ENOTDIR, errno);

  // Valid symlink should work.
  string symlink = JoinPath(tmp_dir, "z");
  ASSERT_TRUE(Symlink(dir, symlink));
  ok = MakeDirectories(symlink, 0755);
  ASSERT_TRUE(ok);

  // Error: Symlink to a file.
  symlink = JoinPath(tmp_dir, "w");
  ASSERT_TRUE(Symlink(non_dir, symlink));
  ok = MakeDirectories(symlink, 0755);
  ASSERT_FALSE(ok);
  ASSERT_EQ(ENOTDIR, errno);

  // Error: Symlink to a dir with wrong perms.
  symlink = JoinPath(tmp_dir, "s");
  ASSERT_TRUE(Symlink("/", symlink));

  // These perms will force a chmod()
  // TODO(ulfjack): Fix this!
  //  ok = MakeDirectories(symlink, 0000);
  //  ASSERTFALSE(ok);
  //  ASSERT_EQ(EPERM, errno);

  // Edge cases.
  ASSERT_FALSE(MakeDirectories("", 0755));
  ASSERT_EQ(EACCES, errno);
  ASSERT_FALSE(MakeDirectories("/", 0755));
  ASSERT_EQ(EACCES, errno);
}

TEST(FilePosixTest, HammerMakeDirectories) {
  const char* tmp_dir = getenv("TEST_TMPDIR");
  ASSERT_STRNE(tmp_dir, nullptr);

  string path = JoinPath(tmp_dir, "x/y/z");
  // TODO(ulfjack): Fix this!
  //  ASSERT_LE(0, fork());
  //  ASSERT_TRUE(MakeDirectories(path, 0755));
}

TEST(FilePosixTest, PathExists) {
  ASSERT_FALSE(PathExists("/this/should/not/exist/mkay"));
  ASSERT_FALSE(PathExists("non.existent"));
  ASSERT_FALSE(PathExists(""));

  // /usr/bin/yes exists on Linux, Darwin, and MSYS
  ASSERT_TRUE(PathExists("/"));
  ASSERT_TRUE(PathExists("/usr"));
  ASSERT_TRUE(PathExists("/usr/"));
  ASSERT_TRUE(PathExists("/usr/bin/yes"));
}

TEST(FilePosixTest, CanAccess) {
  ASSERT_FALSE(CanReadFile("/this/should/not/exist/mkay"));
  ASSERT_FALSE(CanExecuteFile("/this/should/not/exist/mkay"));
  ASSERT_FALSE(CanAccessDirectory("/this/should/not/exist/mkay"));

  ASSERT_FALSE(CanReadFile("non.existent"));
  ASSERT_FALSE(CanExecuteFile("non.existent"));
  ASSERT_FALSE(CanAccessDirectory("non.existent"));

  const char* tmpdir = getenv("TEST_TMPDIR");
  ASSERT_NE(nullptr, tmpdir);
  ASSERT_NE(0, *tmpdir);

  string dir(JoinPath(tmpdir, "canaccesstest"));
  ASSERT_EQ(0, mkdir(dir.c_str(), 0700));

  ASSERT_FALSE(CanReadFile(dir));
  ASSERT_FALSE(CanExecuteFile(dir));
  ASSERT_TRUE(CanAccessDirectory(dir));

  string file(JoinPath(dir, "foo.txt"));
  AutoFileStream fh(fopen(file.c_str(), "wt"));
  EXPECT_TRUE(fh.IsOpen());
  ASSERT_LT(0, fprintf(fh, "hello"));
  fh.Close();

  ASSERT_TRUE(CanReadFile(file));
  ASSERT_FALSE(CanExecuteFile(file));
  ASSERT_FALSE(CanAccessDirectory(file));

  ASSERT_EQ(0, chmod(file.c_str(), 0100));
  ASSERT_FALSE(CanReadFile(file));
  ASSERT_TRUE(CanExecuteFile(file));
  ASSERT_FALSE(CanAccessDirectory(file));

  ASSERT_EQ(0, chmod(dir.c_str(), 0500));
  ASSERT_FALSE(CanReadFile(dir));
  ASSERT_FALSE(CanExecuteFile(dir));
  ASSERT_FALSE(CanAccessDirectory(dir));
  ASSERT_EQ(0, chmod(dir.c_str(), 0700));

  ASSERT_EQ(0, unlink(file.c_str()));
  ASSERT_EQ(0, rmdir(dir.c_str()));
}

TEST(FilePosixTest, ChangeDirectory) {
  // Retrieve the current working directory.
  char old_wd[PATH_MAX];
  ASSERT_EQ(old_wd, getcwd(old_wd, PATH_MAX));

  // Change to a different directory and assert it was successful.
  ASSERT_FALSE(blaze_util::ChangeDirectory("/non/existent/path"));
  ASSERT_TRUE(blaze_util::ChangeDirectory("/usr"));
  char new_wd[PATH_MAX];
  ASSERT_EQ(new_wd, getcwd(new_wd, PATH_MAX));
  ASSERT_EQ(string("/usr"), string(new_wd));

  // Change back to the original CWD.
  ASSERT_TRUE(blaze_util::ChangeDirectory(old_wd));
  ASSERT_EQ(new_wd, getcwd(new_wd, PATH_MAX));
  ASSERT_EQ(string(old_wd), string(new_wd));
}

class MockDirectoryEntryConsumer : public DirectoryEntryConsumer {
 public:
  void Consume(const Path& path, bool is_directory) override {
    entries[path] = is_directory;
  }

  map<Path, bool> entries;
};

TEST(FilePosixTest, ForEachDirectoryEntry) {
  // Get the test's temp dir.
  char* tmpdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tmpdir_cstr, nullptr);
  Path tmpdir(tmpdir_cstr);
  ASSERT_TRUE(PathExists(tmpdir));

  // Names of mock files and directories.
  Path root = tmpdir.GetRelative("FilePosixTest.ForEachDirectoryEntry.root");
  Path dir = root.GetRelative("dir");
  Path file = root.GetRelative("file");
  Path dir_sym = root.GetRelative("dir_sym");
  Path file_sym = root.GetRelative("file_sym");
  Path subfile = dir.GetRelative("subfile");
  Path subfile_through_sym = dir_sym.GetRelative("subfile");

  // Create mock directory, file, and symlinks.
  ASSERT_TRUE(MakeDirectories(root, 0700));
  ASSERT_TRUE(WriteFile("", file));
  ASSERT_TRUE(MakeDirectories(dir, 0700));
  ASSERT_TRUE(Symlink(dir, dir_sym));
  ASSERT_TRUE(Symlink(file, file_sym));
  ASSERT_TRUE(WriteFile("", subfile));

  // Actual test: list the directory.
  MockDirectoryEntryConsumer consumer1;
  ForEachDirectoryEntry(root, &consumer1);
  map<Path, bool> expected1;
  expected1[dir] = true;
  expected1[dir_sym] = false;
  expected1[file] = false;
  expected1[file_sym] = false;
  EXPECT_EQ(expected1, consumer1.entries);

  // Actual test: list a directory symlink.
  MockDirectoryEntryConsumer consumer2;
  ForEachDirectoryEntry(dir_sym, &consumer2);
  map<Path, bool> expected2;
  expected2[subfile_through_sym] = false;
  EXPECT_EQ(expected2, consumer2.entries);

  // Actual test: list a path that's actually a file, not a directory.
  MockDirectoryEntryConsumer consumer3;
  ForEachDirectoryEntry(file, &consumer3);
  EXPECT_TRUE(consumer3.entries.empty());

  // Cleanup: delete mock directory tree.
  EXPECT_TRUE(blaze_util::RemoveRecursively(root));
}

TEST(FileTest, TestRemoveRecursivelyPosix) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tempdir_cstr, nullptr);
  Path tempdir(tempdir_cstr);
  ASSERT_TRUE(PathExists(tempdir));

  Path unwritable_dir = tempdir.GetRelative("test_rmr_unwritable");
  EXPECT_TRUE(MakeDirectories(unwritable_dir, 0700));
  EXPECT_TRUE(WriteFile("junkdata", 8, unwritable_dir.GetRelative("file")));
  ASSERT_EQ(0, chmod(unwritable_dir.AsNativePath().c_str(), 0500));
  EXPECT_FALSE(RemoveRecursively(unwritable_dir));

  Path symlink_target_dir = tempdir.GetRelative("test_rmr_symlink_target_dir");
  EXPECT_TRUE(MakeDirectories(symlink_target_dir, 0700));
  Path symlink_target_dir_file = symlink_target_dir.GetRelative("file");
  EXPECT_TRUE(WriteFile("junkdata", 8, symlink_target_dir_file));
  Path symlink_dir = tempdir.GetRelative("test_rmr_symlink_dir");
  EXPECT_EQ(0, symlink(symlink_target_dir.AsNativePath().c_str(),
                       symlink_dir.AsNativePath().c_str()));
  EXPECT_TRUE(RemoveRecursively(symlink_dir));
  EXPECT_FALSE(PathExists(symlink_dir));
  EXPECT_TRUE(PathExists(symlink_target_dir));
  EXPECT_TRUE(PathExists(symlink_target_dir_file));

  Path dir_with_symlinks = tempdir.GetRelative("test_rmr_dir_w_symlinks");
  EXPECT_TRUE(MakeDirectories(dir_with_symlinks, 0700));
  Path file_symlink_target =
      tempdir.GetRelative("test_rmr_dir_w_symlinks_file");
  EXPECT_TRUE(WriteFile("junkdata", 8, file_symlink_target));
  EXPECT_EQ(
      0, symlink(file_symlink_target.AsNativePath().c_str(),
                 dir_with_symlinks.GetRelative("file").AsNativePath().c_str()));
  Path dir_symlink_target = tempdir.GetRelative("test_rmr_dir_w_symlinks_dir");
  EXPECT_TRUE(MakeDirectories(dir_symlink_target, 0700));
  Path dir_symlink_target_file = dir_symlink_target.GetRelative("file");
  EXPECT_TRUE(WriteFile("junkdata", 8, dir_symlink_target_file));
  EXPECT_EQ(
      0, symlink(dir_symlink_target.AsNativePath().c_str(),
                 dir_with_symlinks.GetRelative("dir").AsNativePath().c_str()));
  EXPECT_TRUE(RemoveRecursively(dir_with_symlinks));
  EXPECT_FALSE(PathExists(dir_with_symlinks));
  EXPECT_TRUE(PathExists(dir_symlink_target));
  EXPECT_TRUE(PathExists(dir_symlink_target_file));
  EXPECT_TRUE(PathExists(file_symlink_target));
}

TEST(FileTest, TestCreatSiblingTempDirDoesntClobberParentPerms) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tempdir_cstr, nullptr);
  Path tempdir(tempdir_cstr);
  ASSERT_TRUE(PathExists(tempdir));

  Path existing_parent_dir = tempdir.GetRelative("existing");
  ASSERT_TRUE(MakeDirectories(existing_parent_dir, 0700));
  Path other_dir = existing_parent_dir.GetRelative("other");
  Path temp_dir = CreateSiblingTempDir(other_dir);
  ASSERT_EQ(other_dir.GetParent(), temp_dir.GetParent());
  ASSERT_TRUE(absl::StartsWith(temp_dir.GetBaseName(),
                               other_dir.GetBaseName() + ".tmp."));
  EXPECT_TRUE(PathExists(temp_dir));
  struct stat filestat = {};
  ASSERT_EQ(0, stat(existing_parent_dir.AsNativePath().c_str(), &filestat));
  ASSERT_EQ(mode_t(0700), filestat.st_mode & 0777);
}

}  // namespace blaze_util
