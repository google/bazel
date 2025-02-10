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

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1  // for F_OFD_SETLK on Linux
#endif

#define _WITH_DPRINTF

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pwd.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cassert>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/md5.h"
#include "src/main/cpp/util/numbers.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"

namespace blaze {

using blaze_util::GetLastErrorString;

using std::set;
using std::string;
using std::vector;

namespace embedded_binaries {

class PosixDumper : public Dumper {
 public:
  static PosixDumper* Create(string* error);
  ~PosixDumper() { Finish(nullptr); }
  void Dump(const void* data, size_t size,
            const blaze_util::Path& path) override;
  bool Finish(string* error) override;

 private:
  PosixDumper() : was_error_(false) {}

  set<blaze_util::Path> dir_cache_;
  string error_msg_;
  bool was_error_;
};

Dumper* Create(string* error) { return PosixDumper::Create(error); }

PosixDumper* PosixDumper::Create(string* error) { return new PosixDumper(); }

void PosixDumper::Dump(const void* data, const size_t size,
                       const blaze_util::Path& path) {
  if (was_error_) {
    return;
  }

  blaze_util::Path parent = path.GetParent();
  // Performance optimization: memoize the paths we already created a
  // directory for, to spare a stat in attempting to recreate an already
  // existing directory.
  if (dir_cache_.insert(parent).second) {
    if (!blaze_util::MakeDirectories(parent, 0777)) {
      was_error_ = true;
      string msg = GetLastErrorString();
      error_msg_ =
          string("couldn't create '") + path.AsPrintablePath() + "': " + msg;
    }
  }

  if (was_error_) {
    return;
  }

  if (!blaze_util::WriteFile(data, size, path, 0755)) {
    was_error_ = true;
    string msg = GetLastErrorString();
    error_msg_ = string("Failed to write zipped file '") +
                 path.AsPrintablePath() + "': " + msg;
  }
}

bool PosixDumper::Finish(string* error) {
  if (was_error_ && error) {
    *error = error_msg_;
  }
  return !was_error_;
}

}  // namespace embedded_binaries

SignalHandler SignalHandler::INSTANCE;

// The number of the last received signal that should cause the client
// to shutdown.  This is saved so that the client's WTERMSIG can be set
// correctly.  (Currently only SIGPIPE uses this mechanism.)
static volatile sig_atomic_t signal_handler_received_signal = 0;

// Signal handler.
static void handler(int signum) {
  int saved_errno = errno;

  static volatile sig_atomic_t sigint_count = 0;

  switch (signum) {
    case SIGINT:
      if (++sigint_count >= 3) {
        SigPrintf(
            "\n%s caught third interrupt signal; server killed. (This may be "
            "expensive, see https://bazel.build/advanced/performance/"
            "iteration-speed#avoid-ctrl-c.)\n\n",
            SignalHandler::Get().GetProductName().c_str());
        if (SignalHandler::Get().GetServerProcessInfo()->server_pid_ != -1) {
          KillServerProcess(
              SignalHandler::Get().GetServerProcessInfo()->server_pid_,
              SignalHandler::Get().GetOutputBase());
        }
        _exit(1);
      }
      SigPrintf(
          "\n%s caught interrupt signal; cancelling pending invocation.\n\n",
          SignalHandler::Get().GetProductName().c_str());
      SignalHandler::Get().CancelServer();
      break;
    case SIGTERM:
      SigPrintf(
          "\n%s caught terminate signal; cancelling pending invocation.\n\n",
          SignalHandler::Get().GetProductName().c_str());
      SignalHandler::Get().CancelServer();
      break;
    case SIGPIPE:
      signal_handler_received_signal = SIGPIPE;
      break;
    case SIGQUIT:
      SigPrintf("\nSending SIGQUIT to JVM process %d (see %s).\n\n",
                SignalHandler::Get().GetServerProcessInfo()->server_pid_,
                SignalHandler::Get()
                    .GetServerProcessInfo()
                    ->jvm_log_file_.AsNativePath()
                    .c_str());
      kill(SignalHandler::Get().GetServerProcessInfo()->server_pid_, SIGQUIT);
      break;
  }

  errno = saved_errno;
}

void SignalHandler::Install(const string& product_name,
                            const blaze_util::Path& output_base,
                            const ServerProcessInfo* server_process_info,
                            SignalHandler::Callback cancel_server) {
  product_name_ = product_name;
  output_base_ = output_base;
  server_process_info_ = server_process_info;
  cancel_server_ = cancel_server;

  // Unblock all signals.
  sigset_t sigset;
  sigemptyset(&sigset);
  sigprocmask(SIG_SETMASK, &sigset, nullptr);

  // SIGWINCH is reserved for Bazel server internal use and cannot be passed to
  // it. The JVM is not attached to a terminal, making a signal insufficient to
  // react to window size change event anyway.
  signal(SIGINT, handler);
  signal(SIGTERM, handler);
  signal(SIGPIPE, handler);
  signal(SIGQUIT, handler);
}

ATTRIBUTE_NORETURN void SignalHandler::PropagateSignalOrExit(int exit_code) {
  if (signal_handler_received_signal) {
    // Kill ourselves with the same signal, so that callers see the
    // right WTERMSIG value.
    signal(signal_handler_received_signal, SIG_DFL);
    raise(signal_handler_received_signal);
    exit(1);  // (in case raise didn't kill us for some reason)
  } else {
    exit(exit_code);
  }
}

string GetProcessIdAsString() { return blaze_util::ToString(getpid()); }

string GetHomeDir() { return GetPathEnv("HOME"); }

string GetJavaBinaryUnderJavabase() { return "bin/java"; }

string Which(const string& executable) {
  const string path = GetPathEnv("PATH");
  if (path.empty()) {
    return "";
  }

  const vector<string> pieces = blaze_util::Split(path, ':');
  for (string piece : pieces) {
    if (piece.empty()) {
      piece = ".";
    }

    struct stat file_stat;
    const string candidate = blaze_util::JoinPath(piece, executable);
    if (access(candidate.c_str(), X_OK) == 0 &&
        stat(candidate.c_str(), &file_stat) == 0 &&
        S_ISREG(file_stat.st_mode)) {
      return candidate;
    }
  }
  return "";
}

// Converter of C++ data structures to a C-style array of strings.
//
// The primary consumer of this class is the execv family of functions
// (including posix_spawn) which require mutable arrays as their inputs even
// if they do not modify them.
class CharPP {
 public:
  // Constructs a new CharPP from a list of arguments.
  explicit CharPP(const vector<string>& args) {
    charpp_ = static_cast<char**>(malloc(sizeof(char*) * (args.size() + 1)));
    size_t i = 0;
    for (; i < args.size(); i++) {
      charpp_[i] = strdup(args[i].c_str());
    }
    charpp_[i] = nullptr;
  }

  // Constructs a new CharPP from a list of environment variables.
  explicit CharPP(const std::map<string, EnvVarValue>& env) {
    charpp_ = static_cast<char**>(malloc(sizeof(char*) * (env.size() + 1)));
    size_t i = 0;
    for (const auto& iter : env) {
      const string& var = iter.first;
      const EnvVarValue& value = iter.second;

      switch (value.action) {
        case SET:
          charpp_[i] = strdup((var + "=" + value.value).c_str());
          i++;
          break;

        case UNSET:
          break;

        default:
          assert(false);
      }
    }
    charpp_[i] = nullptr;
  }

  // Deletes all memory held by the CharPP.
  ~CharPP() {
    for (char** ptr = charpp_; *ptr != nullptr; ptr++) {
      free(*ptr);
    }
    free(charpp_);
  }

  // Obtains the raw pointer to the array of strings.
  char** get() { return charpp_; }

  // Prevent copies as we manually manage memory.
  CharPP(const CharPP&) = delete;
  CharPP& operator=(const CharPP&) = delete;

 private:
  char** charpp_;
};

ATTRIBUTE_NORETURN static void ExecuteProgram(const blaze_util::Path& exe,
                                              const vector<string>& args_vector,
                                              const bool run_in_user_cgroup) {
  BAZEL_LOG(INFO) << "Invoking binary " << exe.AsPrintablePath() << " in "
                  << blaze_util::GetCwd();

  // TODO(jmmv): This execution does not respect any settings we might apply
  // to the server process with ConfigureDaemonProcess when executed in the
  // background as a daemon.  Because we use that function to lower the
  // priority of Bazel on macOS from a QoS perspective, this could have
  // adverse scheduling effects on any tools invoked via ExecuteProgram.
  CharPP argv(args_vector);
  execv(exe.AsNativePath().c_str(), argv.get());
  string err = GetLastErrorString();
  BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
      << "execv of '" << exe.AsPrintablePath() << "' failed: " << err;
  // TODO(jmmv): Mark BAZEL_DIE as ATTRIBUTE_NORETURN so that the following
  // code is not necessary.
  std::abort();  // Not reachable.
}

void ExecuteServerJvm(const blaze_util::Path& exe,
                      const std::vector<string>& server_jvm_args,
                      const bool run_in_user_cgroup) {
  ExecuteProgram(exe, server_jvm_args, run_in_user_cgroup);
}

void ExecuteRunRequest(const blaze_util::Path& exe,
                       const std::vector<string>& run_request_args) {
  ExecuteProgram(exe, run_request_args,
                 /* run_in_user_cgroup= */ false);
}

const char kListSeparator = ':';

bool SymlinkDirectories(const blaze_util::Path& target,
                        const blaze_util::Path& link) {
  return symlink(target.AsNativePath().c_str(), link.AsNativePath().c_str()) ==
         0;
}

// Notifies the client about the death of the server process by keeping a socket
// open in the server. If the server dies for any reason, the socket will be
// closed, which can be detected by the client.
class SocketBlazeServerStartup : public BlazeServerStartup {
 public:
  SocketBlazeServerStartup(int pipe_fd);
  virtual ~SocketBlazeServerStartup();
  virtual bool IsStillAlive();

 private:
  int fd;
};

SocketBlazeServerStartup::SocketBlazeServerStartup(int fd) : fd(fd) {}

SocketBlazeServerStartup::~SocketBlazeServerStartup() { close(fd); }

bool SocketBlazeServerStartup::IsStillAlive() {
  struct pollfd pfd;
  pfd.fd = fd;
  pfd.events = POLLIN;
  int result;
  do {
    result = poll(&pfd, 1, 0);
  } while (result < 0 && errno == EINTR);
  if (result == 0) {
    // Timeout, server is still alive
    return true;
  } else {
    // Whether it's an error or pfd.revents & POLLHUP > 0, we assume child is
    // dead.
    return false;
  }
}

// Sets platform-specific attributes for the creation of the daemon process.
//
// Returns zero on success or -1 on error, in which case errno is set to the
// corresponding error details.
int ConfigureDaemonProcess(posix_spawnattr_t* attrp,
                           const StartupOptions& options);

void WriteSystemSpecificProcessIdentifier(const blaze_util::Path& server_dir,
                                          pid_t server_pid);

int ExecuteDaemon(
    const blaze_util::Path& exe, const std::vector<string>& args_vector,
    const std::map<string, EnvVarValue>& env,
    const blaze_util::Path& daemon_output, const bool daemon_output_append,
    const blaze_util::Path& binaries_dir, const blaze_util::Path& server_dir,
    const StartupOptions& options, BlazeServerStartup** server_startup) {
  const blaze_util::Path pid_file = server_dir.GetRelative(kServerPidFile);
  const string daemonize = binaries_dir.GetRelative("daemonize").AsNativePath();

  std::vector<string> daemonize_args = {"daemonize", "-l",
                                        daemon_output.AsNativePath(), "-p",
                                        pid_file.AsNativePath()};
  if (daemon_output_append) {
    daemonize_args.push_back("-a");
  }
#ifdef __linux__
  if (!options.cgroup_parent.empty()) {
    daemonize_args.push_back("-c");
    daemonize_args.push_back(options.cgroup_parent);
  }
  if (options.run_in_user_cgroup) {
    daemonize_args.push_back("-s");
    daemonize_args.push_back(
        server_dir.GetRelative("systemd-wrapper.sh").AsNativePath());
  }
#endif
  daemonize_args.push_back("--");
  daemonize_args.push_back(exe.AsNativePath());
  std::copy(args_vector.begin(), args_vector.end(),
            std::back_inserter(daemonize_args));

  int fds[2];

  if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds)) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "socket creation failed: " << GetLastErrorString();
  }

  posix_spawn_file_actions_t file_actions;
  if (posix_spawn_file_actions_init(&file_actions) != 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to create posix_spawn_file_actions: "
        << GetLastErrorString();
  }
  if (posix_spawn_file_actions_addclose(&file_actions, fds[0]) != 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to modify posix_spawn_file_actions: "
        << GetLastErrorString();
  }

  posix_spawnattr_t attrp;
  if (posix_spawnattr_init(&attrp) != 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to create posix_spawnattr: " << GetLastErrorString();
  }
  if (ConfigureDaemonProcess(&attrp, options) == -1) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to modify posix_spawnattr: " << GetLastErrorString();
  }

  pid_t transient_pid;
  if (posix_spawn(&transient_pid, daemonize.c_str(), &file_actions, &attrp,
                  CharPP(daemonize_args).get(), CharPP(env).get()) != 0) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to execute JVM via " << daemonize << ": "
        << GetLastErrorString();
  }
  close(fds[1]);

  posix_spawnattr_destroy(&attrp);
  posix_spawn_file_actions_destroy(&file_actions);

  // Wait for daemonize to exit. This guarantees that the pid file exists.
  int status;
  if (waitpid(transient_pid, &status, 0) == -1) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "waitpid failed: " << GetLastErrorString();
  }
  if (!WIFEXITED(status) || WEXITSTATUS(status) != EXIT_SUCCESS) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "daemonize didn't exit cleanly: " << GetLastErrorString();
  }

  std::ifstream pid_reader(pid_file.AsNativePath());
  if (!pid_reader) {
    string err = GetLastErrorString();
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to open " << pid_file.AsPrintablePath() << ": " << err;
  }
  pid_t server_pid;
  pid_reader >> server_pid;
  if (pid_reader.fail()) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "Failed to read pid from: " << pid_file.AsPrintablePath();
  }

  WriteSystemSpecificProcessIdentifier(server_dir, server_pid);

  *server_startup = new SocketBlazeServerStartup(fds[0]);
  return server_pid;
}

blaze_util::Path GetHashedBaseDir(const blaze_util::Path& root,
                                  const string& hashable) {
  unsigned char buf[blaze_util::Md5Digest::kDigestLength];
  blaze_util::Md5Digest digest;
  digest.Update(hashable.data(), hashable.size());
  digest.Finish(buf);
  return root.GetRelative(digest.String());
}

void CreateSecureDirectory(const blaze_util::Path& path) {
  struct stat fileinfo = {};

  if (!blaze_util::MakeDirectories(path, 0755)) {
    string err = GetLastErrorString();
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "mkdir('" << path.AsPrintablePath() << "'): " << err;
  }

  // The path already exists.
  // Check ownership and mode, and verify that it is a directory.

  if (lstat(path.AsNativePath().c_str(), &fileinfo) < 0) {
    string err = GetLastErrorString();
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "lstat('" << path.AsPrintablePath() << "'): " << err;
  }

  if (fileinfo.st_uid != geteuid()) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "'" << path.AsPrintablePath() << "' is not owned by me";
  }

  if ((fileinfo.st_mode & 022) != 0) {
    int new_mode = fileinfo.st_mode & (~022);
    if (chmod(path.AsNativePath().c_str(), new_mode) < 0) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "'" << path.AsPrintablePath() << "' has mode "
          << (fileinfo.st_mode & 07777) << ", chmod to " << new_mode
          << " failed";
    }
  }

  if (stat(path.AsNativePath().c_str(), &fileinfo) < 0) {
    string err = GetLastErrorString();
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "stat('" << path.AsPrintablePath() << "'): " << err;
  }

  if (!S_ISDIR(fileinfo.st_mode)) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "'" << path.AsPrintablePath() << "' is not a directory";
  }

  ExcludePathFromBackup(path);
}

string GetEnv(const string& name) {
  char* result = getenv(name.c_str());
  return result != nullptr ? string(result) : "";
}

string GetPathEnv(const string& name) { return GetEnv(name); }

bool ExistsEnv(const string& name) { return getenv(name.c_str()) != nullptr; }

void SetEnv(const string& name, const string& value) {
  setenv(name.c_str(), value.c_str(), 1);
}

void UnsetEnv(const string& name) { unsetenv(name.c_str()); }

bool WarnIfStartedFromDesktop() { return false; }

void SetupStdStreams() {
  // Set non-buffered output mode for stderr/stdout. The server already
  // line-buffers messages where it makes sense, so there's no need to do set
  // line-buffering here. On the other hand the server sometimes sends binary
  // output (when for example a query returns results as proto), in which case
  // we must not perform line buffering on the client side. So turn off
  // buffering here completely.
  setvbuf(stdout, nullptr, _IONBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);

  // Ensure we have three open fds.  Otherwise we can end up with
  // bizarre things like stdout going to the lock file, etc.
  if (fcntl(STDIN_FILENO, F_GETFL) == -1) open("/dev/null", O_RDONLY);
  if (fcntl(STDOUT_FILENO, F_GETFL) == -1) open("/dev/null", O_WRONLY);
  if (fcntl(STDERR_FILENO, F_GETFL) == -1) open("/dev/null", O_WRONLY);
}

// A signal-safe version of fprintf(stderr, ...).
//
// WARNING: any output from the blaze client may be interleaved
// with output from the blaze server.  In --curses mode,
// the Blaze server often erases the previous line of output.
// So, be sure to end each such message with TWO newlines,
// otherwise it may be erased by the next message from the
// Blaze server.
// Also, it's a good idea to start each message with a newline,
// in case the Blaze server has written a partial line.
void SigPrintf(const char* format, ...) {
  char buf[1024];
  va_list ap;
  va_start(ap, format);
  int r = vsnprintf(buf, sizeof buf, format, ap);
  va_end(ap);
  if (write(STDERR_FILENO, buf, r) <= 0) {
    // We don't care, just placate the compiler.
  }
}

static bool TryLock(int fd, LockMode mode, const string& name) {
  struct flock lock = {};
  lock.l_type = static_cast<short>(  // NOLINT (short is the right type)
      mode == LockMode::kShared ? F_RDLCK : F_WRLCK);
  // Locking is advisory, so any range works as long as it overlaps with the
  // ones requested by other processes.
  lock.l_whence = SEEK_SET;
  lock.l_start = 0;
  lock.l_len = 1;
  // Prefer OFD locks when available. POSIX locks can be lost "accidentally"
  // due to any close() on the lock file, and are not reliably preserved
  // across execve() on Linux, which we need for --batch mode.
#ifdef F_OFD_SETLK
  if (fcntl(fd, F_OFD_SETLK, &lock) == 0) {
    return true;
  }
  if (errno != EINVAL) {
    if (errno != EACCES && errno != EAGAIN) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "fcntl failed for " << name << " lock: " << GetLastErrorString();
    }
    return false;
  }
  // Fall back to POSIX locks on EINVAL.
#endif
  if (fcntl(fd, F_SETLK, &lock) == 0) {
    return true;
  }
  if (errno != EACCES && errno != EAGAIN) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "fctnl failed for " << name << " lock: " << GetLastErrorString();
  }
  return false;
}

static bool StillExists(int fd, const string& name) {
  struct stat st;
  if (fstat(fd, &st) < 0) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "fstat failed for " << name << " lock: " << GetLastErrorString();
  }
  return st.st_nlink > 0;
}

static string ReadOwnerInformation(int fd, const string& name) {
  // Assume that 4KB are sufficient to fit the owner information.
  string buffer(4096, 0);
  ssize_t r = pread(fd, &buffer[0], buffer.size(), 0);
  if (r < 0) {
    BAZEL_LOG(WARNING) << "pread failed for " << name
                       << " lock: " << GetLastErrorString();
    r = 0;
  }
  buffer.resize(r);
  return buffer;
}

static void WriteOwnerInformation(int fd) {
  (void)ftruncate(fd, 0);
  lseek(fd, 0, SEEK_SET);
  // Locking is advisory, so it doesn't matter that this may overflow the
  // locked range.
  dprintf(fd, "pid=%d\nowner=client\n", getpid());
  string cwd = blaze_util::GetCwd();
  dprintf(fd, "cwd=%s\n", cwd.c_str());
  const char* tty = ttyname(STDIN_FILENO);  // NOLINT (single-threaded)
  if (tty != nullptr) {
    dprintf(fd, "tty=%s\n", tty);
  }
}

std::pair<LockHandle, DurationMillis> AcquireLock(const std::string& name,
                                                  const blaze_util::Path& path,
                                                  LockMode mode,
                                                  bool batch_mode, bool block) {
  const uint64_t start_time = GetMillisecondsMonotonic();
  bool multiple_attempts = false;
  string owner;

  while (true) {
    int flags = O_CREAT | (mode == LockMode::kShared ? O_RDONLY : O_RDWR);
    // Keep server from inheriting a useless fd if we are not in batch mode.
    if (!batch_mode) {
      flags |= O_CLOEXEC;
    }

    int fd = open(path.AsNativePath().c_str(), flags, 0644);
    if (fd < 0) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "open failed for " << name << " lock: " << GetLastErrorString();
    }

    // Attempt to take the lock.
    if (TryLock(fd, mode, name)) {
      // Check that the lock file was not concurrently deleted.
      if (StillExists(fd, name)) {
        // If taking an exclusive lock, identify ourselves in the lock file.
        // The contents are printed for human consumption when another client
        // fails to take the lock, but not parsed otherwise.
        if (mode == LockMode::kExclusive) {
          WriteOwnerInformation(fd);
        }
        // If we succeeded on the first try, report zero wait time to avoid
        // unnecessary noise in the logs. We are interested in how long it took
        // for other commands to complete, not how fast acquiring a lock is.
        const uint64_t end_time = GetMillisecondsMonotonic();
        const auto wait_time = multiple_attempts
                                   ? DurationMillis(start_time, end_time)
                                   : DurationMillis();
        return std::make_pair(static_cast<LockHandle>(fd), wait_time);
      }
    }

    // Someone else holds the lock. Obtain the identity of the current lock
    // owner and print it out.
    string new_owner = ReadOwnerInformation(fd, name);
    if (new_owner != owner) {
      owner = new_owner;
      BAZEL_LOG(USER) << "Another command holds the " << name << " lock: \n"
                      << owner;
      if (block) {
        BAZEL_LOG(USER) << "Waiting for it to complete...";
        fflush(stderr);
      }
    }

    if (!block) {
      BAZEL_DIE(blaze_exit_code::LOCK_HELD_NOBLOCK_FOR_LOCK)
          << "Exiting because the " << name
          << " lock is held and --noblock_for_lock was given.";
    }

    multiple_attempts = true;

    close(fd);
    TrySleep(500);
  }
}

void ReleaseLock(LockHandle lock_handle) {
  close(static_cast<int>(lock_handle));
}

bool KillServerProcess(int pid, const blaze_util::Path& output_base) {
  // Kill the process and make sure it's dead before proceeding.
  errno = 0;
  if (killpg(pid, SIGKILL) == -1) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "Attempted to kill stale server process (pid=" << pid
        << ") using SIGKILL: " << GetLastErrorString();
  }
  if (!AwaitServerProcessTermination(pid, output_base,
                                     kPostKillGracePeriodSeconds)) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "Attempted to kill stale server process (pid=" << pid
        << ") using SIGKILL, but it did not die in a timely fashion.";
  }
  return true;
}

void TrySleep(unsigned int milliseconds) {
  time_t seconds_part = (time_t)(milliseconds / 1000);
  long nanoseconds_part = ((long)(milliseconds % 1000)) * 1000 * 1000;
  struct timespec sleeptime = {seconds_part, nanoseconds_part};
  nanosleep(&sleeptime, nullptr);
}

string GetUserName() {
  string user = GetEnv("USER");
  if (!user.empty()) {
    return user;
  }
  errno = 0;
  passwd* pwent = getpwuid(getuid());  // NOLINT (single-threaded)
  if (pwent == nullptr || pwent->pw_name == nullptr) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "$USER is not set, and unable to look up name of current user: "
        << GetLastErrorString();
  }
  return pwent->pw_name;
}

bool IsEmacsTerminal() {
  string emacs = GetEnv("EMACS");
  // GNU Emacs <25.1 (and ~all non-GNU emacsen) set EMACS=t, but >=25.1 doesn't
  // do that and instead sets INSIDE_EMACS=<stuff> (where <stuff> can look like
  // e.g. "25.1.1,comint").  So we check both variables for maximum
  // compatibility.
  return emacs == "t" || ExistsEnv("INSIDE_EMACS");
}

bool IsStandardTerminal() {
  string term = GetEnv("TERM");
  bool isEmacs = IsEmacsTerminal();

  // Emacs 22+ terminal emulation uses 'eterm-color' as its terminfo name and,
  // more importantly, supports color in terminals.
  // see https://github.com/emacs-mirror/emacs/blob/master/etc/NEWS.22#L331-L333
  if (isEmacs && term == "eterm-color") {
    return true;
  }
  if (term.empty() || term == "dumb" || term == "emacs" ||
      term == "xterm-mono" || term == "symbolics" || term == "9term" ||
      isEmacs) {
    return false;
  }
  return isatty(STDOUT_FILENO) && isatty(STDERR_FILENO);
}

int GetTerminalColumns() {
  struct winsize ws;
  if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != -1) {
    return ws.ws_col;
  }
  string columns_env = GetEnv("COLUMNS");
  if (!columns_env.empty()) {
    char* endptr;
    int columns = blaze_util::strto32(columns_env.c_str(), &endptr, 10);
    if (*endptr == '\0') {  // $COLUMNS is a valid number
      return columns;
    }
  }
  return 80;  // default if not a terminal.
}

// Raises a resource limit to the maximum allowed value.
//
// This function raises the limit of the resource given in "resource" from its
// soft limit to its hard limit. If the hard limit is unlimited and
// allow_infinity is false, uses the kernel-level limit because setting the
// soft limit to unlimited may not work.
//
// Note that this is a best-effort operation. Any failure during this process
// will result in a warning but execution will continue.
static bool UnlimitResource(const int resource, const bool allow_infinity) {
  struct rlimit rl;
  if (getrlimit(resource, &rl) == -1) {
    BAZEL_LOG(WARNING) << "failed to get resource limit " << resource << ": "
                       << strerror(errno);
    return false;
  }

  if (rl.rlim_cur == rl.rlim_max) {
    // Nothing to do. Return early to prevent triggering any warnings caused by
    // the code below. This way, we will only show warnings the first time the
    // Blaze server is started and not on each command invocation.
    return true;
  }

  const rlim_t explicit_limit = GetExplicitSystemLimit(resource);
  if (explicit_limit <= 0) {
    // If not implemented (-1) or on an error (0), do nothing and try to
    // increase the soft limit to the hard one. This might fail, but it's good
    // to try anyway.
    rl.rlim_cur = rl.rlim_max;
  } else {
    if ((rl.rlim_max == RLIM_INFINITY && !allow_infinity) ||
        rl.rlim_max > explicit_limit) {
      rl.rlim_cur = explicit_limit;
    } else {
      rl.rlim_cur = rl.rlim_max;
    }
  }

  if (setrlimit(resource, &rl) == -1) {
    BAZEL_LOG(WARNING) << "failed to raise resource limit " << resource
                       << " to " << static_cast<intmax_t>(rl.rlim_cur) << ": "
                       << strerror(errno);
    return false;
  }

  return true;
}

bool UnlimitResources() {
  bool success = true;
  success &= UnlimitResource(RLIMIT_NOFILE, false);
  success &= UnlimitResource(RLIMIT_NPROC, false);
  return success;
}

bool UnlimitCoredumps() { return UnlimitResource(RLIMIT_CORE, true); }

void EnsurePythonPathOption(vector<string>* options) {
  // do nothing.
}

}  // namespace blaze
