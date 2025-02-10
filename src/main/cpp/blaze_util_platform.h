#include "src/main/cpp/util/path_platform.h"
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

#ifndef BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_PLATFORM_H_
#define BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_PLATFORM_H_

#include <stdint.h>

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/server_process_info.h"
#include "src/main/cpp/util/port.h"

namespace blaze {

namespace embedded_binaries {

// Dumps embedded binaries that were extracted from the Bazel zip to disk.
// The platform-specific implementations may use multi-threaded I/O.
class Dumper {
 public:
  // Requests to write the `data` of `size` bytes to disk under `path`.
  // The actual writing may happen asynchronously.
  // `path` must be an absolute path. All of its parent directories will be
  // created.
  // The caller retains ownership of `data` and may release it immediately after
  // this method returns.
  // Callers may call this method repeatedly, but only from the same thread
  // (this method is not thread-safe).
  // If writing fails, this method sets a flag in the `Dumper`, and `Finish`
  // will return false. Subsequent `Dump` calls will have no effect.
  virtual void Dump(const void* data, const size_t size,
                    const blaze_util::Path& path) = 0;

  // Finishes dumping data.
  //
  // This method may block in case the Dumper is asynchronous and some async
  // writes are still in progress.
  // Subsequent `Dump` calls after this method have no effect.
  //
  // Returns true if there were no errors in any of the `Dump` calls.
  // Returns false if any of the `Dump` calls failed, and if `error` is not
  // null then puts an error message in `error`.
  virtual bool Finish(std::string* error) = 0;

  // Destructor. Subclasses should make sure it calls `Finish(nullptr)`.
  virtual ~Dumper() {}

 protected:
  Dumper() {}
};

// Creates a new Dumper. The caller takes ownership of the returned object.
// Returns nullptr upon failure and puts an error message in `error` (if `error`
// is not nullptr).
Dumper* Create(std::string* error = nullptr);

}  // namespace embedded_binaries

class StartupOptions;

class SignalHandler {
 public:
  typedef void (*Callback)();

  static SignalHandler& Get() { return INSTANCE; }
  const ServerProcessInfo* GetServerProcessInfo() const {
    return server_process_info_;
  }
  const std::string& GetProductName() const { return product_name_; }
  const blaze_util::Path& GetOutputBase() const { return output_base_; }
  void CancelServer() { cancel_server_(); }
  void Install(const std::string& product_name,
               const blaze_util::Path& output_base,
               const ServerProcessInfo* server_process_info,
               Callback cancel_server);
  ATTRIBUTE_NORETURN void PropagateSignalOrExit(int exit_code);

 private:
  static SignalHandler INSTANCE;

  std::string product_name_;
  blaze_util::Path output_base_;
  const ServerProcessInfo* server_process_info_;
  Callback cancel_server_;

  SignalHandler() : server_process_info_(nullptr), cancel_server_(nullptr) {}
};

// A signal-safe version of fprintf(stderr, ...).
void SigPrintf(const char* format, ...);

std::string GetProcessIdAsString();

// Locates a file named `executable` in the PATH. Returns a path to the first
// matching file, or an empty string if `executable` is not found on the PATH.
std::string Which(const std::string& executable);

// Gets an absolute path to the binary being executed that is guaranteed to be
// readable.
std::string GetSelfPath(const char* argv0);

// Returns a directory suitable for storing cached files.
std::string GetCacheDir();

// Returns the current user's home directory, or the empty string if unknown.
// On Linux/macOS, this is $HOME. On Windows this is %USERPROFILE%.
std::string GetHomeDir();

// Warn about dubious filesystem types, such as NFS, case-insensitive (?).
void WarnFilesystemType(const blaze_util::Path& output_base);

// Returns elapsed milliseconds since some unspecified start of time.
// The results are monotonic, i.e. subsequent calls to this method never return
// a value less than a previous result.
uint64_t GetMillisecondsMonotonic();

// Set cpu and IO scheduling properties. Note that this can take ~50ms
// on Linux, so it should only be called when necessary.
void SetScheduling(bool batch_cpu_scheduling, int io_nice_level);

// Returns the current working directory of the specified process, or nullptr
// if the directory is unknown.
std::unique_ptr<blaze_util::Path> GetProcessCWD(int pid);

bool IsSharedLibrary(const std::string& filename);

// Returns the absolute path to the user's local JDK install, to be used as
// the default target javabase and as a fall-back host_javabase. This is not
// the embedded JDK.
std::string GetSystemJavabase();

// Return the path to the JVM binary relative to a javabase, e.g. "bin/java".
std::string GetJavaBinaryUnderJavabase();

// Start the Bazel server's JVM in the current directory.
//
// Note on Windows: 'server_jvm_args' is NOT expected to be escaped for
// CreateProcessW, and 'run_in_user_cgroup' is ignored.
//
// This function does not return on success.
ATTRIBUTE_NORETURN void ExecuteServerJvm(
    const blaze_util::Path& exe,
    const std::vector<std::string>& server_jvm_args, bool run_in_user_cgroup);

// Execute the "bazel run" request in the current directory.
//
// Note on Windows: 'run_request_args' IS expected to be escaped for
// CreateProcessW.
//
// This function does not return on success.
ATTRIBUTE_NORETURN void ExecuteRunRequest(
    const blaze_util::Path& exe,
    const std::vector<std::string>& run_request_args);

class BlazeServerStartup {
 public:
  virtual ~BlazeServerStartup() {}
  virtual bool IsStillAlive() = 0;
};

// Starts a daemon process with its standard output and standard error
// redirected (and conditionally appended) to the file "daemon_output". Sets
// server_startup to an object that can be used to query if the server is
// still alive. The PID of the daemon started is written into server_dir,
// both as a symlink (for legacy reasons) and as a file, and returned to the
// caller.
int ExecuteDaemon(
    const blaze_util::Path& exe, const std::vector<std::string>& args_vector,
    const std::map<std::string, EnvVarValue>& env,
    const blaze_util::Path& daemon_output, const bool daemon_output_append,
    const blaze_util::Path& binaries_dir, const blaze_util::Path& server_dir,
    const StartupOptions& options, BlazeServerStartup** server_startup);

// A character used to separate paths in a list.
extern const char kListSeparator;

// Create a symlink to directory ``target`` at location ``link``.
// Returns true on success, false on failure.
// Implemented via junctions on Windows.
bool SymlinkDirectories(const blaze_util::Path& target,
                        const blaze_util::Path& link);

typedef uintptr_t LockHandle;

enum class LockMode {
  kShared,
  kExclusive,
};

// Acquires a `mode` lock on `path`, creating it if doesn't yet exist.
// If `block` is true, busy-wait until the lock becomes available.
// If `batch_mode` is false, release the lock on exec.
// The `path` is guaranteed to exist when this function returns; if it is
// deleted concurrently with obtaining the lock, we recreate it and try again.
// This makes it safe to delete the file under an exclusive lock.
// The `name` argument is used in human-readable error messages.
// Returns a handle that can be subsequently passed to ReleaseLock as well as
// the time spent waiting for the lock, if any.
// Crashes if an error occurs while attempting to obtain the lock.
std::pair<LockHandle, DurationMillis> AcquireLock(const std::string& name,
                                                  const blaze_util::Path& path,
                                                  LockMode mode,
                                                  bool batch_mode, bool block);

// Releases a lock previously obtained from AcquireLock.
void ReleaseLock(LockHandle lock_handle);

// Verifies whether the server process still exists. Returns true if it does.
bool VerifyServerProcess(int pid, const blaze_util::Path& output_base);

// Kills a server process based on its PID.
// Returns true if the server process was found and killed.
// WARNING! This function can be called from a signal handler!
bool KillServerProcess(int pid, const blaze_util::Path& output_base);

// Wait for approximately the specified number of milliseconds. The actual
// amount of time waited may be more or less because of interrupts or system
// clock resolution.
void TrySleep(unsigned int milliseconds);

// Mark path as being excluded from backups (if supported by operating system).
void ExcludePathFromBackup(const blaze_util::Path& path);

// Returns the canonical form of the base dir given a root and a hashable
// string. The resulting dir is composed of the root + md5(hashable)
blaze_util::Path GetHashedBaseDir(const blaze_util::Path& root,
                                  const std::string& hashable);

// Creates a directory while making sure it is owned by the current user and
// not accessible to anyone else.
void CreateSecureDirectory(const blaze_util::Path& path);

std::string GetEnv(const std::string& name);

std::string GetPathEnv(const std::string& name);

bool ExistsEnv(const std::string& name);

void SetEnv(const std::string& name, const std::string& value);

void UnsetEnv(const std::string& name);

// Returns true and prints a warning if Bazel was started by clicking its icon.
// This is typical on Windows. Other platforms should return false, unless they
// wish to handle this case too.
bool WarnIfStartedFromDesktop();

// Ensure we have open file descriptors for stdin/stdout/stderr.
void SetupStdStreams();

std::string GetUserName();

// Returns true iff the current terminal is running inside an Emacs.
bool IsEmacsTerminal();

// Returns true iff both stdout and stderr support color and cursor movement.
// This is used to determine whether or not to use stylized output, which relies
// on both stdout and stderr being standard terminals to avoid confusing UI
// issues (ie one stream deleting a line the other intended to be displayed).
bool IsStandardTerminal();

// Returns the number of columns of the terminal to which stdout is connected,
// or 80 if there is no such terminal.
int GetTerminalColumns();

// Gets the system-wide explicit limit for the given resource.
//
// The resource is one of the RLIMIT_* constants defined in sys/resource.h.
// Returns 0 if the limit could not be fetched and returns -1 if the function
// is not implemented for this platform.
//
// It is OK to call this function with a parameter of -1 to check if the
// function is implemented for the platform.
int32_t GetExplicitSystemLimit(const int resource);

// Raises soft system resource limits to hard limits in an attempt to let
// large builds work. This is a best-effort operation and may or may not be
// implemented for a given platform. Returns true if all limits were properly
// raised; false otherwise.
bool UnlimitResources();

// Raises the soft coredump limit to the hard limit in an attempt to let
// coredumps work. This is a best-effort operation and may or may not be
// implemented for a given platform. Returns true if all limits were properly
// raised; false otherwise.
bool UnlimitCoredumps();

#if defined(_WIN32) || defined(__CYGWIN__)
std::string DetectBashAndExportBazelSh();
#endif  // if defined(_WIN32) || defined(__CYGWIN__)

// This function has no effect on Unix platforms.
// On Windows, this function looks into PATH to find python.exe, if python
// binary is found then add
// --default_override=0:build=--python_path=<python/path> into options.
void EnsurePythonPathOption(std::vector<std::string>* options);

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_PLATFORM_H_
