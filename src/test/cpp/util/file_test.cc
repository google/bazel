#include <stdlib.h>
// Copyright 2014 The Bazel Authors. All rights reserved.
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
#include <string.h>

#include <algorithm>
#include <map>
#include <memory>
#include <thread>  // NOLINT (to silence Google-internal linter)
#include <vector>

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/test/cpp/util/test_util.h"
#include "googletest/include/gtest/gtest.h"
#include "absl/strings/match.h"

namespace blaze_util {

using std::string;

TEST(FileTest, TestSingleThreadedPipe) {
  std::unique_ptr<IPipe> pipe(CreatePipe());
  char buffer[50] = {0};
  ASSERT_TRUE(pipe->Send("hello", 5));
  int error = -1;
  ASSERT_EQ(3, pipe->Receive(buffer, 3, &error));
  ASSERT_TRUE(pipe->Send(" world", 6));
  ASSERT_EQ(5, pipe->Receive(buffer + 3, 5, &error));
  ASSERT_EQ(IPipe::SUCCESS, error);
  ASSERT_EQ(3, pipe->Receive(buffer + 8, 40, &error));
  ASSERT_EQ(IPipe::SUCCESS, error);
  ASSERT_EQ(0, strncmp(buffer, "hello world", 11));
}

TEST(FileTest, TestMultiThreadedPipe) {
  std::unique_ptr<IPipe> pipe(CreatePipe());
  char buffer[50] = {0};
  std::thread writer_thread([&pipe]() {
    ASSERT_TRUE(pipe->Send("hello", 5));
    ASSERT_TRUE(pipe->Send(" world", 6));
  });

  // Wait for all data to be fully written to the pipe.
  writer_thread.join();

  int error = -1;
  ASSERT_EQ(3, pipe->Receive(buffer, 3, &error));
  ASSERT_EQ(IPipe::SUCCESS, error);
  ASSERT_EQ(5, pipe->Receive(buffer + 3, 5, &error));
  ASSERT_EQ(IPipe::SUCCESS, error);
  ASSERT_EQ(3, pipe->Receive(buffer + 8, 40, &error));
  ASSERT_EQ(IPipe::SUCCESS, error);
  ASSERT_EQ(0, strncmp(buffer, "hello world", 11));
}

TEST(FileTest, TestReadFileIntoString) {
  const char* tempdir = getenv("TEST_TMPDIR");
  ASSERT_NE(nullptr, tempdir);
  ASSERT_NE(0, tempdir[0]);

  std::string filename(JoinPath(tempdir, "test.readfile"));
  AutoFileStream fh(fopen(filename.c_str(), "wt"));
  EXPECT_TRUE(fh.IsOpen());
  ASSERT_EQ(size_t(11), fwrite("hello world", 1, 11, fh));
  fh.Close();

  std::string actual;
  ASSERT_TRUE(ReadFile(filename, &actual));
  ASSERT_EQ(std::string("hello world"), actual);

  ASSERT_TRUE(ReadFile(filename, &actual, 5));
  ASSERT_EQ(std::string("hello"), actual);

  ASSERT_TRUE(ReadFile("/dev/null", &actual, 42));
  ASSERT_EQ(std::string(""), actual);
}

TEST(FileTest, TestReadFileIntoBuffer) {
  const char* tempdir = getenv("TEST_TMPDIR");
  EXPECT_NE(nullptr, tempdir);
  EXPECT_NE(0, tempdir[0]);

  std::string filename(JoinPath(tempdir, "test.readfile"));
  AutoFileStream fh(fopen(filename.c_str(), "wt"));
  EXPECT_TRUE(fh.IsOpen());
  EXPECT_EQ(size_t(11), fwrite("hello world", 1, 11, fh));
  fh.Close();

  char buffer[30];
  memset(buffer, 0, 30);
  ASSERT_TRUE(ReadFile(filename, buffer, 5));
  ASSERT_EQ(string("hello"), string(buffer));

  memset(buffer, 0, 30);
  ASSERT_TRUE(ReadFile(filename, buffer, 30));
  ASSERT_EQ(string("hello world"), string(buffer));

  buffer[0] = 'x';
  ASSERT_TRUE(ReadFile("/dev/null", buffer, 42));
  ASSERT_EQ('x', buffer[0]);
}

TEST(FileTest, TestWriteFile) {
  const char* tempdir = getenv("TEST_TMPDIR");
  ASSERT_NE(nullptr, tempdir);
  ASSERT_NE(0, tempdir[0]);

  std::string filename(JoinPath(tempdir, "test.writefile"));

  ASSERT_TRUE(WriteFile("hello", 3, filename));

  char buf[6] = {0};
  AutoFileStream fh(fopen(filename.c_str(), "rt"));
  EXPECT_TRUE(fh.IsOpen());
  fflush(fh);
  ASSERT_EQ(size_t(3), fread(buf, 1, 5, fh));
  fh.Close();
  ASSERT_EQ(std::string(buf), std::string("hel"));

  ASSERT_TRUE(WriteFile("hello", 5, filename));
  fh = fopen(filename.c_str(), "rt");
  EXPECT_TRUE(fh.IsOpen());
  memset(buf, 0, 6);
  ASSERT_EQ(size_t(5), fread(buf, 1, 5, fh));
  fh.Close();
  ASSERT_EQ(std::string(buf), std::string("hello"));

  ASSERT_TRUE(WriteFile("hello", 5, "/dev/null"));
  ASSERT_EQ(0, remove(filename.c_str()));
}

TEST(FileTest, TestLargeFileWrite) {
  // Buffer over the write limit (2,147,479,552 for Linux, INT32_MAX for MacOS).
  const size_t size = 4000000000;
  std::unique_ptr<char[]> buffer(new char[size]);
  std::fill(buffer.get(), buffer.get() + size, '\0');

  ASSERT_TRUE(WriteFile(buffer.get(), size, "/dev/null"));
}

TEST(FileTest, TestMtimeHandling) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tempdir_cstr, nullptr);
  ASSERT_NE(tempdir_cstr[0], 0);
  Path tempdir(tempdir_cstr);

  // Assert that a directory is always untampered with. (We do
  // not care about directories' mtimes.)
  ASSERT_TRUE(IsUntampered(tempdir));
  // Create a new file, assert its mtime is not in the future.
  Path file = tempdir.GetRelative("foo.txt");
  ASSERT_TRUE(WriteFile("hello", 5, file));
  ASSERT_FALSE(IsUntampered(file));
  // Set the file's mtime to the future, assert that it's so.
  ASSERT_TRUE(SetMtimeToDistantFuture(file));
  ASSERT_TRUE(IsUntampered(file));
  // Overwrite the file, resetting its mtime, assert that
  // IsUntampered notices.
  ASSERT_TRUE(WriteFile("world", 5, file));
  ASSERT_FALSE(IsUntampered(file));
  // Set it to the future again so we can reset it using SetToNow.
  ASSERT_TRUE(SetMtimeToDistantFuture(file));
  ASSERT_TRUE(IsUntampered(file));
  // Assert that SetToNow resets the timestamp.
  ASSERT_TRUE(SetMtimeToNow(file));
  ASSERT_FALSE(IsUntampered(file));
  // Delete the file and assert that we can no longer set or query its mtime.
  ASSERT_TRUE(UnlinkPath(file));
  ASSERT_FALSE(SetMtimeToNow(file));
  ASSERT_FALSE(SetMtimeToDistantFuture(file));
  ASSERT_FALSE(IsUntampered(file));
}

TEST(FileTest, TestCreateSiblingTempDir) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  EXPECT_NE(tempdir_cstr, nullptr);
  EXPECT_NE(tempdir_cstr[0], 0);
  Path tempdir(tempdir_cstr);

  Path input_in_existing = tempdir.GetRelative("other");
  Path output_in_existing = CreateSiblingTempDir(input_in_existing);
  ASSERT_NE(input_in_existing, output_in_existing);
  ASSERT_EQ(input_in_existing.GetParent(), output_in_existing.GetParent());
  ASSERT_TRUE(absl::StartsWith(output_in_existing.GetBaseName(),
                               input_in_existing.GetBaseName() + ".tmp."));
  EXPECT_TRUE(PathExists(output_in_existing));

  Path missing_dir = tempdir.GetRelative("doesntexistyet");
  ASSERT_FALSE(PathExists(missing_dir));
  Path input_in_missing = missing_dir.GetRelative("other");
  Path output_in_missing = CreateSiblingTempDir(input_in_missing);
  ASSERT_NE(input_in_missing, output_in_missing);
  ASSERT_EQ(input_in_missing.GetParent(), output_in_missing.GetParent());
  ASSERT_TRUE(absl::StartsWith(output_in_missing.GetBaseName(),
                               input_in_missing.GetBaseName() + ".tmp."));
  EXPECT_TRUE(PathExists(output_in_missing));
}

TEST(FileTest, TestRenameDirectory) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  EXPECT_NE(tempdir_cstr, nullptr);
  EXPECT_NE(tempdir_cstr[0], 0);
  Path tempdir(tempdir_cstr);

  Path dir1 = tempdir.GetRelative("test_rename_dir/dir1");
  Path dir2 = tempdir.GetRelative("test_rename_dir/dir2");
  EXPECT_TRUE(MakeDirectories(dir1, 0700));
  Path file1 = dir1.GetRelative("file1.txt");
  EXPECT_TRUE(WriteFile("hello", 5, file1));

  ASSERT_EQ(RenameDirectory(dir1, dir2), kRenameDirectorySuccess);
  ASSERT_EQ(RenameDirectory(dir1, dir2), kRenameDirectoryFailureOtherError);
  EXPECT_TRUE(MakeDirectories(dir1, 0700));
  EXPECT_TRUE(WriteFile("hello", 5, file1));
  ASSERT_EQ(RenameDirectory(dir2, dir1), kRenameDirectoryFailureNotEmpty);
}

class MockDirectoryEntryConsumer : public DirectoryEntryConsumer {
 public:
  void Consume(const Path& path, bool is_directory) override {
    entries[path] = is_directory;
  }

  std::map<Path, bool> entries;
};

TEST(FileTest, ForEachDirectoryEntryTest) {
  const char* tmpdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tmpdir_cstr, nullptr);
  Path tmpdir(tmpdir_cstr);
  ASSERT_TRUE(PathExists(tmpdir));
  //   $TEST_TMPDIR/
  //      foo/
  //        bar/
  //          file3.txt
  //        file1.txt
  //        file2.txt
  Path rootdir = tmpdir.GetRelative("foo");
  Path file1 = rootdir.GetRelative("file1.txt");
  Path file2 = rootdir.GetRelative("file2.txt");
  Path subdir = rootdir.GetRelative("bar");
  Path file3 = subdir.GetRelative("file3.txt");

  ASSERT_TRUE(MakeDirectories(subdir, 0700));
  ASSERT_TRUE(WriteFile("hello", 5, file1));
  ASSERT_TRUE(WriteFile("hello", 5, file2));
  ASSERT_TRUE(WriteFile("hello", 5, file3));

  std::map<Path, bool> expected;
  expected[file1] = false;
  expected[file2] = false;
  expected[subdir] = true;

  MockDirectoryEntryConsumer consumer;
  ForEachDirectoryEntry(rootdir, &consumer);
  EXPECT_EQ(consumer.entries, expected);
}

TEST(FilePosixTest, GetAllFilesUnder) {
  const char* tmpdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tmpdir_cstr, nullptr);
  Path tmpdir(tmpdir_cstr);
  ASSERT_TRUE(PathExists(tmpdir));

  Path root = tmpdir.GetRelative("FilePosixTest.GetAllFilesUnder.root");
  Path file1 = root.GetRelative("file1");
  Path dir1 = root.GetRelative("dir1");
  Path dir2 = root.GetRelative("dir2");
  Path dir3 = dir1.GetRelative("dir3");
  Path file2 = dir1.GetRelative("file2");
  Path file3 = dir2.GetRelative("file3");
  Path file4 = dir3.GetRelative("file4");
  Path file5 = dir3.GetRelative("file5");

  ASSERT_TRUE(MakeDirectories(root, 0700));
  ASSERT_TRUE(MakeDirectories(dir1, 0700));
  ASSERT_TRUE(MakeDirectories(dir2, 0700));
  ASSERT_TRUE(MakeDirectories(dir3, 0700));
  ASSERT_TRUE(WriteFile("", file1));
  ASSERT_TRUE(WriteFile("", file2));
  ASSERT_TRUE(WriteFile("", file3));
  ASSERT_TRUE(WriteFile("", file4));
  ASSERT_TRUE(WriteFile("", file5));

  std::vector<Path> result;
  GetAllFilesUnder(root, &result);
  std::sort(result.begin(), result.end());

  std::vector<Path> expected({file4, file5, file2, file3, file1});
  EXPECT_EQ(expected, result);

  EXPECT_TRUE(RemoveRecursively(root));
}

TEST(FileTest, IsDevNullTest) {
  ASSERT_TRUE(IsDevNull("/dev/null"));
  ASSERT_FALSE(IsDevNull("dev/null"));
  ASSERT_FALSE(IsDevNull("/dev/nul"));
  ASSERT_FALSE(IsDevNull("/dev/nulll"));
  ASSERT_FALSE(IsDevNull((char *) nullptr));
  ASSERT_FALSE(IsDevNull(""));
}

TEST(FileTest, TestRemoveRecursively) {
  const char* tempdir_cstr = getenv("TEST_TMPDIR");
  ASSERT_NE(tempdir_cstr, nullptr);
  Path tempdir(tempdir_cstr);
  ASSERT_TRUE(PathExists(tempdir));

  Path non_existent_dir = tempdir.GetRelative("test_rmr_non_existent");
  EXPECT_TRUE(RemoveRecursively(non_existent_dir));
  EXPECT_FALSE(PathExists(non_existent_dir));

  Path empty_dir = tempdir.GetRelative("test_rmr_empty_dir");
  EXPECT_TRUE(MakeDirectories(empty_dir, 0700));
  EXPECT_TRUE(RemoveRecursively(empty_dir));
  EXPECT_FALSE(PathExists(empty_dir));

  Path dir_with_content = tempdir.GetRelative("test_rmr_dir_w_content");
  EXPECT_TRUE(MakeDirectories(dir_with_content, 0700));
  EXPECT_TRUE(WriteFile("junkdata", 8, dir_with_content.GetRelative("file")));
  Path subdir = dir_with_content.GetRelative("dir");
  EXPECT_TRUE(MakeDirectories(subdir, 0700));
  Path subsubdir = subdir.GetRelative("dir");
  EXPECT_TRUE(MakeDirectories(subsubdir, 0700));
  EXPECT_TRUE(WriteFile("junkdata", 8, subsubdir.GetRelative("deep_file")));
  EXPECT_TRUE(RemoveRecursively(dir_with_content));
  EXPECT_FALSE(PathExists(dir_with_content));

  Path regular_file = tempdir.GetRelative("test_rmr_regular_file");
  EXPECT_TRUE(WriteFile("junkdata", 8, regular_file));
  EXPECT_TRUE(RemoveRecursively(regular_file));
  EXPECT_FALSE(PathExists(regular_file));
}

}  // namespace blaze_util
