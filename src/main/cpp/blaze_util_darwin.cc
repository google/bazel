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

#include "src/main/cpp/blaze_util_platform.h"

#include <libproc.h>
#include <pthread/spawn.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <CoreFoundation/CoreFoundation.h>

#include <cerrno>
#include <cstdio>
#include <cstring>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/strings.h"

namespace blaze {

using blaze_util::GetLastErrorString;
using std::string;
using std::vector;

// A stack based class for RAII type handling of CF based types that need
// CFRelease called on them. Checks for nullptr before calling release.
template <typename T> class CFScopedReleaser {
 public:
  explicit CFScopedReleaser(T value) : value_(value) { }
  ~CFScopedReleaser() {
    if (isValid()) {
      CFRelease(value_);
    }
  }
  T get() { return value_; }
  operator T() { return value_; }
  bool isValid() { return value_ != nullptr; }

 private:
  T value_;

  CFScopedReleaser() { }
  CFScopedReleaser(const CFScopedReleaser&);
  CFScopedReleaser& operator=(CFScopedReleaser&);
};

// Convert a CFStringRef to a UTF8 encoded c string
static string UTF8StringFromCFStringRef(CFStringRef cf_string) {
  std::string utf8_string;
  if (cf_string) {
    CFIndex length = CFStringGetLength(cf_string);
    CFIndex max_size =
        CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1;
    vector<char> buffer(max_size);
    if (CFStringGetCString(cf_string, &buffer[0], max_size,
                           kCFStringEncodingUTF8)) {
      utf8_string = &buffer[0];
    }
  }
  return utf8_string;
}

// Extract description from a CFError.
static string DescriptionFromCFError(CFErrorRef cf_err) {
  if (!cf_err) {
    return "";
  }
  CFScopedReleaser<CFStringRef> cf_err_string(CFErrorCopyDescription(cf_err));
  return UTF8StringFromCFStringRef(cf_err_string);
}

string GetCacheDir() { return "/var/tmp"; }

void WarnFilesystemType(const blaze_util::Path &output_base) {
  // Check to see if we are on a non-local drive.
  CFScopedReleaser<CFURLRef> cf_url(CFURLCreateFromFileSystemRepresentation(
      kCFAllocatorDefault,
      reinterpret_cast<const UInt8 *>(output_base.AsNativePath().c_str()),
      output_base.AsNativePath().length(), true));
  CFBooleanRef cf_local = nullptr;
  CFErrorRef cf_error = nullptr;
  if (!cf_url.isValid() ||
      !CFURLCopyResourcePropertyForKey(cf_url, kCFURLVolumeIsLocalKey,
                                       &cf_local, &cf_error)) {
    CFScopedReleaser<CFErrorRef> cf_error_releaser(cf_error);
    BAZEL_LOG(WARNING) << "couldn't get file system type information for '"
                       << output_base.AsPrintablePath()
                       << "': " << DescriptionFromCFError(cf_error_releaser);
    return;
  }
  CFScopedReleaser<CFBooleanRef> cf_local_releaser(cf_local);
  if (!CFBooleanGetValue(cf_local_releaser)) {
    BAZEL_LOG(WARNING) << "Output base '" << output_base.AsPrintablePath()
                       << "' is on a non-local drive. This may lead to "
                          "surprising failures and undetermined behavior.";
  }
}

string GetSelfPath(const char* argv0) {
  char pathbuf[PROC_PIDPATHINFO_MAXSIZE] = {};
  int len = proc_pidpath(getpid(), pathbuf, sizeof(pathbuf));
  if (len == 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "error calling proc_pidpath: " << GetLastErrorString();
  }
  return string(pathbuf, len);
}

uint64_t GetMillisecondsMonotonic() {
  uint64_t nsec = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
  if (nsec == 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "error calling clock_gettime_nsec_np: " << GetLastErrorString();
  }
  return nsec / 1000000LL;
}

void SetScheduling(bool batch_cpu_scheduling, int io_nice_level) {
  // stubbed out so we can compile for Darwin.
}

std::unique_ptr<blaze_util::Path> GetProcessCWD(int pid) {
  struct proc_vnodepathinfo info = {};
  if (proc_pidinfo(
          pid, PROC_PIDVNODEPATHINFO, 0, &info, sizeof(info)) != sizeof(info)) {
    return nullptr;
  }
  return std::unique_ptr<blaze_util::Path>(
      new blaze_util::Path(string(info.pvi_cdir.vip_path)));
}

bool IsSharedLibrary(const string &filename) {
  return blaze_util::ends_with(filename, ".dylib");
}

string GetSystemJavabase() {
  string java_home = GetPathEnv("JAVA_HOME");
  if (!java_home.empty()) {
    string javac = blaze_util::JoinPath(java_home, "bin/javac");
    if (access(javac.c_str(), X_OK) == 0) {
      return java_home;
    }
    BAZEL_LOG(WARNING)
        << "Ignoring JAVA_HOME, because it must point to a JDK, not a JRE.";
  }

  // java_home will print a warning if no JDK could be found
  FILE *output = popen("/usr/libexec/java_home -v 1.8+ 2> /dev/null", "r");
  if (output == nullptr) {
    return "";
  }

  char buf[512];
  char *result = fgets(buf, sizeof(buf), output);
  pclose(output);
  if (result == nullptr) {
    return "";
  }

  string javabase = buf;
  if (javabase.empty()) {
    return "";
  }

  // The output ends with a \n, trim it off.
  return javabase.substr(0, javabase.length()-1);
}

int ConfigureDaemonProcess(posix_spawnattr_t *attrp,
                           const StartupOptions &options) {
  qos_class_t qos_class = options.macos_qos_class;
  if (qos_class != QOS_CLASS_UNSPECIFIED) {
    int err = posix_spawnattr_set_qos_class_np(attrp, qos_class);
    if (err != 0) {
      errno = err;
      return -1;
    }
  }
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

// Sets a flag on path to exclude the path from Apple's automatic backup service
// (Time Machine)
void ExcludePathFromBackup(const blaze_util::Path &path) {
  CFScopedReleaser<CFURLRef> cf_url(CFURLCreateFromFileSystemRepresentation(
      kCFAllocatorDefault,
      reinterpret_cast<const UInt8 *>(path.AsNativePath().c_str()),
      path.AsNativePath().length(), true));
  if (!cf_url.isValid()) {
    BAZEL_LOG(WARNING) << "unable to exclude '" << path.AsPrintablePath()
                       << "' from backups";
    return;
  }
  CFErrorRef cf_error = nullptr;
  if (!CFURLSetResourcePropertyForKey(cf_url, kCFURLIsExcludedFromBackupKey,
                                      kCFBooleanTrue, &cf_error)) {
    CFScopedReleaser<CFErrorRef> cf_error_releaser(cf_error);
    BAZEL_LOG(WARNING) << "unable to exclude '" << path.AsPrintablePath()
                       << "' from backups: "
                       << DescriptionFromCFError(cf_error_releaser);
    return;
  }
}

int32_t GetExplicitSystemLimit(const int resource) {
  const char* sysctl_name;
  switch (resource) {
    case RLIMIT_NOFILE:
      sysctl_name = "kern.maxfilesperproc";
      break;
    case RLIMIT_NPROC:
      sysctl_name = "kern.maxprocperuid";
      break;
    default:
      return 0;
  }

  int32_t limit;
  size_t len = sizeof(limit);
  if (sysctlbyname(sysctl_name, &limit, &len, nullptr, 0) == -1) {
    BAZEL_LOG(WARNING) << "failed to get value of sysctl " << sysctl_name
                       << ": " << std::strerror(errno);
    return 0;
  }
  if (len != sizeof(limit)) {
    BAZEL_LOG(WARNING) << "failed to get value of sysctl " << sysctl_name
                       << ": returned data length " << len
                       << " did not match expected size " << sizeof(limit);
    return 0;
  }
  return limit;
}

}   // namespace blaze.
