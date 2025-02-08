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

#ifndef BAZEL_SRC_MAIN_CPP_UTIL_FILE_PLATFORM_H_
#define BAZEL_SRC_MAIN_CPP_UTIL_FILE_PLATFORM_H_

#include <time.h>

#include <string>
#include <vector>

namespace blaze_util {

class Path;

class IPipe;

IPipe* CreatePipe();

// Checks if `path` is a file/directory in the embedded tools directory that
// was not tampered with.
// Returns true if `path` is a directory or directory symlink, or if `path` is
// a file with an mtime in the distant future.
// Returns false otherwise, or if querying the information failed.
bool IsUntampered(const Path &path);

// Sets the mtime of file under `path` to the current time.
// Returns true if the mtime was changed successfully.
bool SetMtimeToNow(const Path &path);

// Attempt to set the mtime of file under `path` to the current time.
// Returns true if the mtime was changed successfully OR if setting the mtime
// failed due to permissions errors.
bool SetMtimeToNowIfPossible(const Path &path);

// Sets the mtime of file under `path` to the distant future, which should be
// on the order of a decade.
// Returns true if the mtime was changed successfully.
bool SetMtimeToDistantFuture(const Path &path);

#if defined(_WIN32) || defined(__CYGWIN__)
// We cannot include <windows.h> because it #defines many symbols that conflict
// with our function names, e.g. GetUserName, SendMessage.
// Instead of typedef'ing HANDLE, let's use the actual type, void*. If that ever
// changes in the future and HANDLE would no longer be compatible with void*
// (very unlikely, given how fundamental this type is in Windows), then we'd get
// a compilation error.
typedef /* HANDLE */ void *file_handle_type;
#else   // !(defined(_WIN32) || defined(__CYGWIN__))
typedef int file_handle_type;
#endif  // defined(_WIN32) || defined(__CYGWIN__)

// Result of a `ReadFromHandle` operation.
//
// This is a platform-independent abstraction of `errno`. If you need to handle
// an errno value, add an entry here and update the platform-specific
// `ReadFromHandle` implementations accordingly.
struct ReadFileResult {
  enum Errors {
    SUCCESS = 0,
    OTHER_ERROR = 1,
    INTERRUPTED = 2,
    AGAIN = 3,
  };
};

int ReadFromHandle(file_handle_type handle, void *data, size_t size,
                   int *error);

// Replaces 'content' with contents of file 'filename'.
// If `max_size` is positive, the method reads at most that many bytes;
// otherwise the method reads the whole file.
// If fails to read content from the file, `error_message` can provide extra
// information about the failure.
// Returns false on error. Can be called from a signal handler.
bool ReadFile(const std::string &filename, std::string *content,
              std::string *error_message, int max_size = 0);
bool ReadFile(const std::string &filename, std::string *content,
              int max_size = 0);
bool ReadFile(const Path &path, std::string *content, int max_size = 0);

// Reads up to `size` bytes from the file `filename` into `data`.
// There must be enough memory allocated at `data`.
// Returns true on success, false on error.
bool ReadFile(const std::string &filename, void *data, size_t size);
bool ReadFile(const Path &filename, void *data, size_t size);

// Writes `size` bytes from `data` into file `filename` and chmods it to `perm`.
// Returns false on failure, sets errno.
bool WriteFile(const void *data, size_t size, const std::string &filename,
               unsigned int perm = 0644);

bool WriteFile(const void *data, size_t size, const Path &path,
               unsigned int perm = 0644);

// Result of a `WriteToStdOutErr` operation.
//
// This is a platform-independent abstraction of `errno`. If you need to handle
// an errno value, add an entry here and update the platform-specific
// `WriteToStdOutErr` implementations accordingly.
struct WriteResult {
  enum Errors {
    SUCCESS = 0,
    OTHER_ERROR = 1,  // some uncategorized error occurred
    BROKEN_PIPE = 2,  // EPIPE (reading end of the pipe is closed)
  };
};

// Initializes stdout and stderr for writing UTF-8 (best effort).
//
// This should be called once during startup.
void InitializeStdOutErrForUtf8();

// Writes `size` bytes from `data` into stdout/stderr.
// Writes to stdout if `to_stdout` is true, writes to stderr otherwise.
// Returns one of `WriteResult::Errors`.
//
// This is a platform-independent abstraction of `fwrite` with `errno` checking
// and awareness of pipes (i.e. in case stderr/stdout is connected to a pipe).
int WriteToStdOutErr(const void *data, size_t size, bool to_stdout);

enum RenameDirectoryResult {
  kRenameDirectorySuccess = 0,
  kRenameDirectoryFailureNotEmpty = 1,
  kRenameDirectoryFailureOtherError = 2,
};

// Renames the directory at `old_path` to `new_path`.
// Returns one of the RenameDirectoryResult enum values.
int RenameDirectory(const Path &old_path, const Path &new_path);

// Reads which directory a symlink points to. Puts the target of the symlink
// in ``result`` and returns if the operation was successful. Will not work on
// symlinks that don't point to directories on Windows.
bool ReadDirectorySymlink(const blaze_util::Path &symlink,
                          blaze_util::Path *result);

// Unlinks the file given by 'file_path'.
// Returns true on success. In case of failure sets errno.
bool UnlinkPath(const std::string &file_path);
bool UnlinkPath(const Path &file_path);

// Returns true if this path exists, following symlinks.
bool PathExists(const std::string& path);
bool PathExists(const Path &path);

// Returns the real, absolute path corresponding to `path`.
// The method resolves all symlink components of `path`.
// Returns the empty string upon error.
//
// This is a wrapper around realpath(3).
std::string MakeCanonical(const char *path);

// Returns true if `path` exists, is a file or symlink to one, and is readable.
// Follows symlinks.
bool CanReadFile(const std::string &path);
bool CanReadFile(const Path &path);

// Returns true if `path` exists, is a file or symlink to one, and is writable.
// Follows symlinks.
bool CanExecuteFile(const std::string &path);
bool CanExecuteFile(const Path &path);

// Returns true if `path` exists, is a directory or symlink/junction to one, and
// is both readable and writable.
// Follows symlinks/junctions.
bool CanAccessDirectory(const std::string &path);
bool CanAccessDirectory(const Path &path);

// Returns true if `path` refers to a directory or a symlink/junction to one.
bool IsDirectory(const std::string& path);
bool IsDirectory(const Path &path);

// Calls fsync() on the file (or directory) specified in 'file_path'.
// pdie() if syncing fails.
void SyncFile(const std::string& path);
void SyncFile(const Path &path);

// mkdir -p path. All newly created directories use the given mode.
// `mode` should be an octal permission mask, e.g. 0755.
// Returns false on failure, sets errno.
bool MakeDirectories(const std::string &path, unsigned int mode);
bool MakeDirectories(const Path &path, unsigned int mode);

// Creates a temporary directory as a sibling of the given path, and returns it.
// The name of the temporary name matches the given path followed by ".tmp." and
// a string unique to the current process.
Path CreateSiblingTempDir(const Path &other_path);

// Removes the specified path, recursively for a directory.
// Returns true iff the path doesn't exist after the call, including if it
// didn't exist to begin with. Does not follow symlinks.
bool RemoveRecursively(const std::string &path);
bool RemoveRecursively(const Path &path);

// Returns the current working directory.
// The path is platform-specific (e.g. Windows path of Windows) and absolute.
std::string GetCwd();

// Changes the current working directory to `path`, returns true upon success.
bool ChangeDirectory(const std::string& path);

// Interface to be implemented by ForEachDirectoryEntry clients.
class DirectoryEntryConsumer {
 public:
  virtual ~DirectoryEntryConsumer() {}

  // This method is called for each entry in a directory.
  // `name` is the full path of the entry.
  // `is_directory` is true if this entry is a directory (but false if this is a
  // symlink pointing to a directory).
  virtual void Consume(const Path &path, bool is_directory) = 0;
};

// Executes a function for each entry in a directory (except "." and "..").
//
// Returns true if the `path` referred to a directory or directory symlink,
// false otherwise.
//
// See DirectoryEntryConsumer for more details.
void ForEachDirectoryEntry(const Path &path, DirectoryEntryConsumer *consumer);

#if defined(_WIN32) || defined(__CYGWIN__)
std::wstring GetCwdW();
bool MakeDirectoriesW(const std::wstring &path, unsigned int mode);

// Check if `path` is a directory.
bool IsDirectoryW(const std::wstring &path);

// Interface to be implemented by ForEachDirectoryEntryW clients.
class DirectoryEntryConsumerW {
 public:
  virtual ~DirectoryEntryConsumerW() {}

  // This method is called for each entry in a directory.
  // `name` is the full path of the entry.
  // `is_directory` is true if this entry is a directory (but false if this is a
  // symlink pointing to a directory).
  virtual void Consume(const std::wstring &name, bool is_directory) = 0;
};

// Lists all files in `path` and all of its subdirectories.
//
// Does not follow symlinks / junctions.
//
// Populates `result` with the full paths of the files. Every entry will have
// `path` as its prefix. If `path` is a file, `result` contains just this
// file.
void GetAllFilesUnderW(const std::wstring &path,
                       std::vector<std::wstring> *result);
#endif  // defined(_WIN32) || defined(__CYGWIN__)

}  // namespace blaze_util

#endif  // BAZEL_SRC_MAIN_CPP_UTIL_FILE_PLATFORM_H_
