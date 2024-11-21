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

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <utime.h>

#include <string>

#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"

namespace blaze_util {

using std::string;

// Runs "stat" on `path`. Returns -1 and sets errno if stat fails or
// `path` isn't a directory. If check_perms is true, this will also
// make sure that `path` is owned by the current user and has `mode`
// permissions (observing the umask). It attempts to run chmod to
// correct the mode if necessary. If `path` is a symlink, this will
// check ownership of the link, not the underlying directory.
static bool GetDirectoryStat(const string &path, mode_t mode,
                             bool check_perms) {
  struct stat filestat = {};
  if (stat(path.c_str(), &filestat) == -1) {
    return false;
  }

  if (!S_ISDIR(filestat.st_mode)) {
    errno = ENOTDIR;
    return false;
  }

  if (check_perms) {
    // If this is a symlink, run checks on the link. (If we did lstat above
    // then it would return false for ISDIR).
    struct stat linkstat = {};
    if (lstat(path.c_str(), &linkstat) != 0) {
      return false;
    }
    if (linkstat.st_uid != geteuid()) {
      // The directory isn't owned by me.
      errno = EACCES;
      return false;
    }

    mode_t mask = umask(022);
    umask(mask);
    mode = (mode & ~mask);
    if ((filestat.st_mode & 0777) != mode && chmod(path.c_str(), mode) == -1) {
      // errno set by chmod.
      return false;
    }
  }
  return true;
}

static bool MakeDirectories(const string &path, mode_t mode, bool childmost) {
  if (path.empty() || IsRootDirectory(path)) {
    errno = EACCES;
    return false;
  }

  bool stat_succeeded = GetDirectoryStat(path, mode, childmost);
  if (stat_succeeded) {
    return true;
  }

  if (errno == ENOENT) {
    // Path does not exist, attempt to create its parents, then it.
    string parent = Dirname(path);
    if (!MakeDirectories(parent, mode, false)) {
      // errno set by stat.
      return false;
    }

    if (mkdir(path.c_str(), mode) == -1) {
      if (errno == EEXIST) {
        if (childmost) {
          // If there are multiple bazel calls at the same time then the
          // directory could be created between the MakeDirectories and mkdir
          // calls. This is okay, but we still have to check the permissions.
          return GetDirectoryStat(path, mode, childmost);
        } else {
          // If this isn't the childmost directory, we don't care what the
          // permissions were. If it's not even a directory then that error will
          // get caught when we attempt to create the next directory down the
          // chain.
          return true;
        }
      }
      // errno set by mkdir.
      return false;
    }
    return true;
  }

  return stat_succeeded;
}

Path CreateSiblingTempDir(const Path &other_path) {
  // Need parent to exist first.
  Path parent = other_path.GetParent();
  if (!blaze_util::PathExists(parent) &&
      !blaze_util::MakeDirectories(parent, 0777)) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "couldn't create '" << parent.AsPrintablePath()
        << "': " << blaze_util::GetLastErrorString();
  }

  std::string path(other_path.AsNativePath() + ".tmp.XXXXXX");
  if (mkdtemp(&path.data()[0]) == nullptr) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "could not create temporary directory under "
        << parent.AsPrintablePath() << " to extract install base into ("
        << GetLastErrorString() << ")";
  }

  // There's no better way to get the current umask than to set and reset it.
  const mode_t um = umask(0);
  umask(um);
  chmod(path.c_str(), 0777 & ~um);

  return Path(path);
}

static bool RemoveDirRecursively(const Path &path) {
  DIR *dir;
  if ((dir = opendir(path.AsNativePath().c_str())) == nullptr) {
    return false;
  }

  struct dirent *ent;
  while ((ent = readdir(dir)) != nullptr) {
    string name = ent->d_name;
    if (name == "." || name == "..") {
      continue;
    }

    if (!RemoveRecursively(path.GetRelative(name))) {
      closedir(dir);
      return false;
    }
  }

  if (closedir(dir) != 0) {
    return false;
  }

  return rmdir(path.AsNativePath().c_str()) == 0;
}

bool RemoveRecursively(const Path &path) {
  struct stat stat_buf;
  if (lstat(path.AsNativePath().c_str(), &stat_buf) == -1) {
    // Non-existent is good enough.
    return errno == ENOENT;
  }

  if (S_ISDIR(stat_buf.st_mode) && !S_ISLNK(stat_buf.st_mode)) {
    return RemoveDirRecursively(path);
  } else {
    return UnlinkPath(path);
  }
}

class PosixPipe : public IPipe {
 public:
  PosixPipe(int recv_socket, int send_socket)
      : _recv_socket(recv_socket), _send_socket(send_socket) {}

  PosixPipe() = delete;

  virtual ~PosixPipe() {
    close(_recv_socket);
    close(_send_socket);
  }

  bool Send(const void *buffer, int size) override {
    return size >= 0 && write(_send_socket, buffer, size) == size;
  }

  int Receive(void *buffer, int size, int *error) override {
    if (size < 0) {
      if (error != nullptr) {
        *error = IPipe::OTHER_ERROR;
      }
      return -1;
    }
    int result = read(_recv_socket, buffer, size);
    if (error != nullptr) {
      *error = result >= 0 ? IPipe::SUCCESS
                           : ((errno == EINTR) ? IPipe::INTERRUPTED
                                               : IPipe::OTHER_ERROR);
    }
    return result;
  }

 private:
  int _recv_socket;
  int _send_socket;
};

IPipe* CreatePipe() {
  int fd[2];
  if (pipe(fd) < 0) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "pipe() failed: " << GetLastErrorString();
  }

  if (fcntl(fd[0], F_SETFD, FD_CLOEXEC) == -1) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "fcntl(F_SETFD, FD_CLOEXEC) failed: " << GetLastErrorString();
  }

  if (fcntl(fd[1], F_SETFD, FD_CLOEXEC) == -1) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "fcntl(F_SETFD, FD_CLOEXEC) failed: " << GetLastErrorString();
  }

  return new PosixPipe(fd[0], fd[1]);
}

int ReadFromHandle(file_handle_type fd, void *data, size_t size, int *error) {
  int result = read(fd, data, size);
  if (error != nullptr) {
    if (result >= 0) {
      *error = ReadFileResult::SUCCESS;
    } else {
      if (errno == EINTR) {
        *error = ReadFileResult::INTERRUPTED;
      } else if (errno == EAGAIN) {
        *error = ReadFileResult::AGAIN;
      } else {
        *error = ReadFileResult::OTHER_ERROR;
      }
    }
  }
  return result;
}

bool ReadFile(const string &filename, string *content, string *error_message,
              int max_size) {
  int fd = open(filename.c_str(), O_RDONLY);
  if (fd == -1) {
    if (error_message != nullptr) {
      *error_message = blaze_util::GetLastErrorString();
    }
    return false;
  }
  bool result = ReadFrom(fd, content, max_size);
  close(fd);
  return result;
}

bool ReadFile(const string &filename, string *content, int max_size) {
  return ReadFile(filename, content, /* error_message= */nullptr, max_size);
}

bool ReadFile(const Path &path, std::string *content, int max_size) {
  return ReadFile(
    path.AsNativePath(), content, /* error_message= */nullptr, max_size);
}

bool ReadFile(const string &filename, void *data, size_t size) {
  int fd = open(filename.c_str(), O_RDONLY);
  if (fd == -1) return false;
  bool result = ReadFrom(fd, data, size);
  close(fd);
  return result;
}

bool ReadFile(const Path &filename, void *data, size_t size) {
  return ReadFile(filename.AsNativePath(), data, size);
}

bool WriteFile(const void *data, size_t size, const string &filename,
               unsigned int perm) {
  UnlinkPath(filename);  // We don't care about the success of this.
  int fd = open(filename.c_str(), O_CREAT | O_WRONLY | O_TRUNC, perm);
  if (fd == -1) {
    return false;
  }
  const char *const char_data = reinterpret_cast<const char *>(data);
  size_t written = 0;
  while (written < size) {
    // write fails with EINVAL on MacOs for count > INT32_MAX.
    auto result = write(fd, char_data + written,
                        std::min<size_t>(INT32_MAX, size - written));
    if (result == -1) {
      close(fd);
      return false;
    }
    written += result;
  }
  return close(fd) == 0;  // Can fail on NFS.
}

bool WriteFile(const void *data, size_t size, const Path &path,
               unsigned int perm) {
  return WriteFile(data, size, path.AsNativePath(), perm);
}

void InitializeStdOutErrForUtf8() {}

int WriteToStdOutErr(const void *data, size_t size, bool to_stdout) {
  size_t r = fwrite(data, 1, size, to_stdout ? stdout : stderr);
  return (r == size) ? WriteResult::SUCCESS
                     : ((errno == EPIPE) ? WriteResult::BROKEN_PIPE
                                         : WriteResult::OTHER_ERROR);
}

int RenameDirectory(const Path &old_path, const Path &new_path) {
  if (rename(old_path.AsNativePath().c_str(),
             new_path.AsNativePath().c_str()) == 0) {
    return kRenameDirectorySuccess;
  } else {
    if (errno == ENOTEMPTY || errno == EEXIST) {
      return kRenameDirectoryFailureNotEmpty;
    } else {
      return kRenameDirectoryFailureOtherError;
    }
  }
}

bool ReadDirectorySymlink(const blaze_util::Path &symlink,
                          blaze_util::Path *result) {
  char buf[PATH_MAX + 1];
  int len = readlink(symlink.AsNativePath().c_str(), buf, PATH_MAX);
  if (len < 0) {
    return false;
  }

  buf[len] = 0;
  *result = Path(buf);
  return true;
}

bool UnlinkPath(const string &file_path) {
  return unlink(file_path.c_str()) == 0;
}

bool UnlinkPath(const Path &file_path) {
  return UnlinkPath(file_path.AsNativePath());
}

bool PathExists(const string& path) {
  return access(path.c_str(), F_OK) == 0;
}

bool PathExists(const Path &path) { return PathExists(path.AsNativePath()); }

string MakeCanonical(const char *path) {
  char *resolved_path = realpath(path, nullptr);
  if (resolved_path == nullptr) {
    return "";
  } else {
    string ret = resolved_path;
    free(resolved_path);
    return ret;
  }
}

static bool CanAccess(const string &path, bool read, bool write, bool exec) {
  int mode = 0;
  if (read) {
    mode |= R_OK;
  }
  if (write) {
    mode |= W_OK;
  }
  if (exec) {
    mode |= X_OK;
  }
  return access(path.c_str(), mode) == 0;
}

bool CanReadFile(const std::string &path) {
  return !IsDirectory(path) && CanAccess(path, true, false, false);
}

bool CanReadFile(const Path &path) {
  return CanReadFile(path.AsNativePath());
}

bool CanExecuteFile(const std::string &path) {
  return !IsDirectory(path) && CanAccess(path, false, false, true);
}

bool CanExecuteFile(const Path &path) {
  return CanExecuteFile(path.AsNativePath());
}

bool CanAccessDirectory(const std::string &path) {
  return IsDirectory(path) && CanAccess(path, true, true, true);
}

bool CanAccessDirectory(const Path &path) {
  return CanAccessDirectory(path.AsNativePath());
}

bool IsDirectory(const string& path) {
  struct stat buf;
  return stat(path.c_str(), &buf) == 0 && S_ISDIR(buf.st_mode);
}

bool IsDirectory(const Path &path) { return IsDirectory(path.AsNativePath()); }

void SyncFile(const string& path) {
  const char* file_path = path.c_str();
  int fd = open(file_path, O_RDONLY);
  if (fd < 0) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "failed to open '" << file_path
        << "' for syncing: " << GetLastErrorString();
  }
  if (fsync(fd) < 0) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "failed to sync '" << file_path << "': " << GetLastErrorString();
  }
  close(fd);
}

void SyncFile(const Path &path) { SyncFile(path.AsNativePath()); }

static time_t GetNow() {
  time_t result = time(nullptr);
  if (result == -1) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "time(nullptr) failed: " << GetLastErrorString();
  }
  return result;
}

static time_t GetYearsInFuture(int years) {
  return GetNow() + 3600 * 24 * 365 * years;
}

static bool SetMtime(const Path &path, time_t mtime) {
  struct utimbuf times = {mtime, mtime};
  return utime(path.AsNativePath().c_str(), &times) == 0;
}

static const time_t kNearFuture = GetYearsInFuture(9);
static const time_t kDistantFuture = GetYearsInFuture(10);

bool IsUntampered(const Path &path) {
  struct stat buf;
  if (stat(path.AsNativePath().c_str(), &buf)) {
    return false;
  }

  // Compare with kNearFuture, not kDistantFuture.
  // This way we don't need to worry about a potentially unreliable equality
  // check if precision isn't preserved.
  return S_ISDIR(buf.st_mode) || (buf.st_mtime > kNearFuture);
}

bool SetMtimeToNow(const Path &path) {
  time_t now = GetNow();
  return SetMtime(path, now);
}

bool SetMtimeToNowIfPossible(const Path &path) {
  bool okay = SetMtimeToNow(path);
  if (!okay) {
    // `SetToNow`/`Set` are backed by `utime(2)` which can return `EROFS` and
    // `EPERM` when there's a permissions issue:
    if (errno == EROFS || errno == EPERM) {
      okay = true;
    }
  }

  return okay;
}

bool SetMtimeToDistantFuture(const Path &path) {
  return SetMtime(path, kDistantFuture);
}

// mkdir -p path. Returns true if the path was created or already exists and
// could
// be chmod-ed to exactly the given permissions. If final part of the path is a
// symlink, this ensures that the destination of the symlink has the desired
// permissions. It also checks that the directory or symlink is owned by us.
// On failure, this returns false and sets errno.
bool MakeDirectories(const string &path, unsigned int mode) {
  return MakeDirectories(path, mode, true);
}

bool MakeDirectories(const Path &path, unsigned int mode) {
  return MakeDirectories(path.AsNativePath(), mode);
}

string GetCwd() {
  char cwdbuf[PATH_MAX];
  if (getcwd(cwdbuf, sizeof cwdbuf) == nullptr) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "getcwd() failed: " << GetLastErrorString();
  }
  return string(cwdbuf);
}

bool ChangeDirectory(const string& path) {
  return chdir(path.c_str()) == 0;
}

void ForEachDirectoryEntry(const Path &path, DirectoryEntryConsumer *consumer) {
  DIR *dir;
  struct dirent *ent;

  if ((dir = opendir(path.AsNativePath().c_str())) == nullptr) {
    // This is not a directory or it cannot be opened.
    return;
  }

  while ((ent = readdir(dir)) != nullptr) {
    string name(ent->d_name);
    if (name == "." || name == "..") {
      continue;
    }

    Path child_path = path.GetRelative(name);
    bool is_directory;
// 'd_type' field isn't part of the POSIX spec.
#ifdef _DIRENT_HAVE_D_TYPE
    if (ent->d_type != DT_UNKNOWN) {
      is_directory = (ent->d_type == DT_DIR);
    } else  // NOLINT (the brace is on the next line)
#endif
      {
        struct stat buf;
        if (lstat(child_path.AsNativePath().c_str(), &buf) == -1) {
          BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
              << "stat failed for filename '" << child_path.AsPrintablePath()
              << "': " << GetLastErrorString();
        }
        is_directory = S_ISDIR(buf.st_mode);
      }

      consumer->Consume(child_path, is_directory);
  }

    closedir(dir);
}

}  // namespace blaze_util
