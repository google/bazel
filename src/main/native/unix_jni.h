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
//
// INTERNAL header file for use by C++ code in this package.

#ifndef BAZEL_SRC_MAIN_NATIVE_UNIX_JNI_H__
#define BAZEL_SRC_MAIN_NATIVE_UNIX_JNI_H__

#include <errno.h>
#include <jni.h>
#include <sys/stat.h>

#include <string>

namespace blaze_jni {

#define CHECK(condition) \
    do { \
      if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", \
                __FILE__, __LINE__, #condition); \
        abort(); \
      } \
    } while (0)

#define CHECK_EQ(a, b) CHECK((a) == (b))
#define CHECK_NEQ(a, b) CHECK((a) != (b))

#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__)
// stat64 is deprecated on OS X/BSD.
typedef struct stat portable_stat_struct;
#define portable_stat ::stat
#define portable_lstat ::lstat
#else
typedef struct stat64 portable_stat_struct;
#define portable_stat ::stat64
#define portable_lstat ::lstat64
#endif

#if !defined(ENODATA)
# if defined(ENOATTR)
#  define ENODATA ENOATTR
# else
#  error We do not know how to handle missing ENODATA
# endif
#endif

// Posts a JNI exception to the current thread with the specified
// message plus the standard UNIX error message for the error number.
// (Consistent with errors generated by java.io package.)
// The exception's class is determined by the specified UNIX error number.
extern void PostException(JNIEnv *env, int error_number,
                          const std::string &message);

// Returns the standard error message for a given UNIX error number.
extern std::string ErrorMessage(int error_number);

// Runs fstatat(2), if available, or sets errno to ENOSYS if not.
int portable_fstatat(int dirfd, char *name, portable_stat_struct *statbuf,
                     int flags);

// Encoding for different timestamps in a struct stat{}.
enum StatTimes {
  STAT_ATIME,  // access
  STAT_MTIME,  // modification
  STAT_CTIME,  // status change
};

// Returns seconds from a stat buffer.
int StatSeconds(const portable_stat_struct &statbuf, StatTimes t);

// Returns nanoseconds from a stat buffer.
int StatNanoSeconds(const portable_stat_struct &statbuf, StatTimes t);

// Runs getxattr(2). If the attribute is not found, returns -1 and sets
// attr_not_found to true. For all other errors, returns -1, sets attr_not_found
// to false and leaves errno set to the error code returned by the system.
ssize_t portable_getxattr(const char *path, const char *name, void *value,
                          size_t size, bool *attr_not_found);

// Runs lgetxattr(2). If the attribute is not found, returns -1 and sets
// attr_not_found to true. For all other errors, returns -1, sets attr_not_found
// to false and leaves errno set to the error code returned by the system.
ssize_t portable_lgetxattr(const char *path, const char *name, void *value,
                           size_t size, bool *attr_not_found);

// Run sysctlbyname(3), only available on darwin
int portable_sysctlbyname(const char *name_chars, void *mibp, size_t *sizep);

// Used to surround an region that we want sleep disabled for.
// push_disable_sleep to start the area.
// pop_disable_sleep to end the area.
// Note that this is a stack so sleep will not be reenabled until the stack
// is empty.
// Returns 0 on success.
// Returns -1 if sleep is not supported.
int portable_push_disable_sleep();
int portable_pop_disable_sleep();

// Starts up any infrastructure needed to do suspend monitoring.
// May be called more than once.
void portable_start_suspend_monitoring();

// These need to be kept in sync with constants in
// j/c/g/devtools/build/lib/buildtool/buildevent/SystemSuspensionEvent.java
typedef enum {
  SuspensionReasonSIGTSTP = 0,
  SuspensionReasonSIGCONT = 1,
  SuspensionReasonSleep = 2,
  SuspensionReasonWake = 3
} SuspensionReason;

// Declaration for callback function that is called by suspend monitoring
// when a suspension is detected.
extern void suspend_callback(SuspensionReason value);

// Returns the number of times that the system has received a memory pressure
// warning notification since Bazel started.
int portable_memory_pressure_warning_count();

// Returns the number of times that the system has received a memory pressure
// critical notification since Bazel started.
int portable_memory_pressure_critical_count();

}  // namespace blaze_jni

#endif  // BAZEL_SRC_MAIN_NATIVE_UNIX_JNI_H__
