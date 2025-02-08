// Copyright 2015 The Bazel Authors. All rights reserved.
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

#if defined(__FreeBSD__)
# define HAVE_PROCSTAT
# define STANDARD_JAVABASE "/usr/local/openjdk8"
#elif defined(__OpenBSD__)
# define STANDARD_JAVABASE "/usr/local/jdk-17"
#else
# error This BSD is not supported
#endif

#if !defined(DEFAULT_SYSTEM_JAVABASE)
# define DEFAULT_SYSTEM_JAVABASE STANDARD_JAVABASE
#endif

#include <errno.h>
#include <limits.h>
#include <pwd.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/param.h>
#include <sys/queue.h>
#include <sys/socket.h>
#include <sys/sysctl.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>
#if defined(HAVE_PROCSTAT)
# include <libprocstat.h>  // must be included after <sys/...> headers
#endif

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"

namespace blaze {

using blaze_util::GetLastErrorString;
using std::string;

// ${XDG_CACHE_HOME}/bazel, a.k.a. ~/.cache/bazel by default (which is the
// fallback when XDG_CACHE_HOME is not set)
string GetCacheDir() {
  string xdg_cache_home = GetPathEnv("XDG_CACHE_HOME");
  if (xdg_cache_home.empty()) {
    char buf[2048];
    struct passwd pwbuf;
    struct passwd *pw = nullptr;
    uid_t uid = getuid();
    int r = getpwuid_r(uid, &pwbuf, buf, 2048, &pw);
    if (r == 0 && pw != nullptr) {
      xdg_cache_home = blaze_util::JoinPath(pw->pw_dir, ".cache");
    } else {
      return "/tmp";
    }
  }

  return blaze_util::JoinPath(xdg_cache_home, "bazel");
}

void WarnFilesystemType(const blaze_util::Path &output_base) {
  struct statfs buf = {};
  if (statfs(output_base.AsNativePath().c_str(), &buf) < 0) {
    BAZEL_LOG(WARNING) << "couldn't get file system type information for '"
                       << output_base.AsPrintablePath()
                       << "': " << strerror(errno);
    return;
  }

  if (strcmp(buf.f_fstypename, "nfs") == 0) {
    BAZEL_LOG(WARNING) << "Output base '" << output_base.AsPrintablePath()
                       << "' is on NFS. This may lead to surprising failures "
                          "and undetermined behavior.";
  }
}

string GetSelfPath(const char* argv0) {
#if defined(__FreeBSD__)
  char buffer[PATH_MAX] = {};
  auto pid = getpid();
  if (kill(pid, 0) < 0) return "";
  auto procstat = procstat_open_sysctl();
  unsigned int n;
  auto p = procstat_getprocs(procstat, KERN_PROC_PID, pid, &n);
  if (p) {
    if (n != 1) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "expected exactly one process from procstat_getprocs, got " << n
          << ": " << GetLastErrorString();
    }
    auto r = procstat_getpathname(procstat, p, buffer, PATH_MAX);
    if (r != 0) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "procstat_getpathname failed: " << GetLastErrorString();
    }
    procstat_freeprocs(procstat, p);
  }
  procstat_close(procstat);
  return string(buffer);
#elif defined(__OpenBSD__)
  // OpenBSD does not provide a way for a running process to find a path to its
  // own executable, so we try to figure out a path by inspecting argv[0]. In
  // theory this is inadequate, since the parent process can set argv[0] to
  // anything, but in practice this is good enough.

  const std::string argv0str(argv0);

  // If argv[0] starts with a slash, it's an absolute path. Use it.
  if (argv0str.length() > 0 && argv0str[0] == '/') {
    return argv0str;
  }

  // Otherwise, if argv[0] contains a slash, then it's a relative path. Prepend
  // the current directory to form an absolute path.
  if (argv0str.length() > 0 && argv0str.find('/') != std::string::npos) {
    return blaze_util::GetCwd() + "/" + argv0str;
  }

  // Otherwise, try to find the executable by searching the PATH.
  const std::string from_search_path = Which(argv0);
  if (!from_search_path.empty()) {
    return from_search_path;
  }

  // None of the above worked. Give up.
  BAZEL_DIE(blaze_exit_code::BAD_ARGV)
      << "Unable to determine the location of this Bazel executable.";
  return "";  // Never executed. Needed so compiler does not complain.
#else
# error This BSD is not supported
#endif
}

uint64_t GetMillisecondsMonotonic() {
  struct timespec ts = {};
  if (clock_gettime(CLOCK_MONOTONIC, &ts)) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "error calling clock_gettime: " << GetLastErrorString();
  }
  return ts.tv_sec * 1000LL + (ts.tv_nsec / 1000000LL);
}

void SetScheduling(bool batch_cpu_scheduling, int io_nice_level) {
  // Stubbed out so we can compile.
}

std::unique_ptr<blaze_util::Path> GetProcessCWD(int pid) {
#if defined(HAVE_PROCSTAT)
  if (kill(pid, 0) < 0) {
    return nullptr;
  }
  auto procstat = procstat_open_sysctl();
  unsigned int n;
  auto p = procstat_getprocs(procstat, KERN_PROC_PID, pid, &n);
  string cwd;
  if (p) {
    if (n != 1) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "expected exactly one process from procstat_getprocs, got " << n
          << ": " << GetLastErrorString();
    }
    auto files = procstat_getfiles(procstat, p, false);
    filestat *entry;
    STAILQ_FOREACH(entry, files, next) {
      if (entry->fs_uflags & PS_FST_UFLAG_CDIR) {
        if (entry->fs_path) {
          cwd = entry->fs_path;
        } else {
          cwd = "";
        }
      }
    }
    procstat_freefiles(procstat, files);
    procstat_freeprocs(procstat, p);
  }
  procstat_close(procstat);
  return std::unique_ptr<blaze_util::Path>(new blaze_util::Path(cwd));
#else
  return nullptr;
#endif
}

bool IsSharedLibrary(const string &filename) {
  return blaze_util::ends_with(filename, ".so");
}

string GetSystemJavabase() {
  // If JAVA_HOME is defined, then use it as default.
  string javahome = GetPathEnv("JAVA_HOME");

  if (!javahome.empty()) {
    string javac = blaze_util::JoinPath(javahome, "bin/javac");
    if (access(javac.c_str(), X_OK) == 0) {
      return javahome;
    }
    BAZEL_LOG(WARNING)
        << "Ignoring JAVA_HOME, because it must point to a JDK, not a JRE.";
  }

  return DEFAULT_SYSTEM_JAVABASE;
}

int ConfigureDaemonProcess(posix_spawnattr_t *attrp,
                           const StartupOptions &options) {
  // No interesting platform-specific details to configure on this platform.
  return 0;
}

void WriteSystemSpecificProcessIdentifier(const blaze_util::Path &server_dir,
                                          pid_t server_pid) {}

bool VerifyServerProcess(int pid, const blaze_util::Path &output_base) {
  // TODO(lberki): This only checks for the process's existence, not whether
  // its start time matches. Therefore this might accidentally kill an
  // unrelated process if the server died and the PID got reused.
  return killpg(pid, 0) == 0;
}

// Not supported.
void ExcludePathFromBackup(const blaze_util::Path &path) {}

int32_t GetExplicitSystemLimit(const int resource) {
  return -1;
}

}  // namespace blaze
