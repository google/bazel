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
// blaze.cc: bootstrap and client code for Blaze server.
//
// Responsible for:
// - extracting the Python, C++ and Java components.
// - starting the server or finding the existing one.
// - client options parsing.
// - passing the argv array, and printing the out/err streams.
// - signal handling.
// - exiting with the right error/WTERMSIG code.
// - debugger + profiler support.
// - mutual exclusion between batch invocations.
#include "src/main/cpp/blaze.h"

#include <assert.h>
#include <fcntl.h>
#include <grpc/grpc.h>
#include <grpcpp/channel.h>
#include <grpcpp/client_context.h>
#include <grpcpp/create_channel.h>
#include <grpcpp/security/credentials.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <chrono>  // NOLINT (gRPC requires this)
#include <map>
#include <memory>
#include <mutex>  // NOLINT
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <thread>  // NOLINT
#include <unordered_set>
#include <utility>
#include <vector>

#if !defined(_WIN32)
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "src/main/cpp/archive_utils.h"
#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/option_processor.h"
#include "src/main/cpp/server_process_info.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/bazel_log_handler.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/numbers.h"
#include "src/main/cpp/util/path.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/cpp/workspace_layout.h"
#include "src/main/protobuf/command_server.grpc.pb.h"

using blaze_util::GetLastErrorString;

#if !defined(_WIN32)
extern char **environ;
#endif

namespace blaze {

using command_server::CommandServer;
using std::map;
using std::set;
using std::string;
using std::vector;

// The following is a treatise on how the interaction between the client and the
// server works.
//
// First the client acquires filesystem locks on the install and output bases
// (using fcntl() on Unix and LockFileEx() on Windows). The install base is
// locked in shared mode, because two simultaneous commands may run against the
// same install base. The output base is locked in exclusive mode, because two
// simultaneous commands may not run against the same output base. The client
// will busy-wait until both locks are available; however, if --noblock_for_lock
// is set, it will immediately exit if the output base lock cannot be obtained.
//
// Then the client verifies whether it has already extracted itself into the
// install base and that it hasn't been tampered with. Otherwise, it extracts
// itself. Since the install base is not locked exclusively, an atomic rename is
// used to prevent simultaneous extraction attempts from clashing.
//
// Then the client tries to connect to the currently executing server and
// kills it if at least one of the following conditions is true:
//
// - The server is of the wrong version (as determined by the
//   $OUTPUT_BASE/install symlink)
// - The server has different startup options than the client wants
// - The client wants to run the command in batch mode
//
// Then, if needed, the client adjusts the install link to indicate which
// version of the server it is running.
//
// In batch mode, the client then simply executes the server while taking
// care that the install and output base locks are held until it finishes.
//
// If in server mode, the client starts up a server if needed then sends the
// command to the client and streams back stdout and stderr. The client-side
// locks are released after the command has been sent; since the server may
// outlive the client, it must implement its own locking anyway.

// Synchronization between the client and the server is a little precarious
// because the client needs to know the PID of the server and it is not
// available using a Java API and we don't have JNI on Windows at the moment,
// so the server can't just communicate this over the communication channel.
// Thus, a PID file is used, but care needs to be taken that the contents of
// this PID file are right.
//
// Upon server startup, the PID file is written before the client spawns the
// server. Thus, when the client can connect, it can be certain that the PID
// file is up to date.
//
// Upon server shutdown, the PID file is deleted using a server shutdown hook.
// However, this happens *after* the server stopped listening, so it's possible
// that a client has already started up a server and written a new PID file.
// In order to avoid this, when the client starts up a new server, it reads the
// contents of the PID file and kills the process indicated in it (it could do
// with a bit more care, since PIDs can be reused, but for now, we just believe
// the PID file)
//
// Some more interesting scenarios:
//
// - The server receives a kill signal and it does not have a chance to delete
//   the PID file: the client cannot connect, reads the PID file, kills the
//   process indicated in it and starts up a new server.
//
// - The server stopped accepting connections but hasn't quit yet and a new
//   client comes around: the new client will kill the server based on the
//   PID file before a new server is started up.
//
// Alternative implementations:
//
// - Don't deal with PIDs at all. This would make it impossible for the client
//   to deliver a SIGKILL to the server after three SIGINTs. It would only be
//   possible with gRPC anyway.
//
// - Have the server check that the PID file contains the correct things
//   before deleting them: there is a window of time between checking the file
//   and deleting it in which a new server can overwrite the PID file. The
//   output base lock cannot be acquired, either, because when starting up a
//   new server, the client already holds it.
//
// - Delete the PID file before stopping to accept connections: then a client
//   could come about after deleting the PID file but before stopping accepting
//   connections. It would also not be resilient against a dead server that
//   left a PID file around.

// String string representation of RestartReason.
static const char *ReasonString(RestartReason reason) {
  switch (reason) {
    case NO_RESTART:
      return "no_restart";
    case NO_DAEMON:
      return "no_daemon";
    case NEW_VERSION:
      return "new_version";
    case NEW_OPTIONS:
      return "new_options";
    case PID_FILE_BUT_NO_SERVER:
      return "pid_file_but_no_server";
    case SERVER_VANISHED:
      return "server_vanished";
    case SERVER_UNRESPONSIVE:
      return "server_unresponsive";
  }

  BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
      << "unknown RestartReason (" << reason << ").";
  // Cannot actually reach this, but it makes the compiler happy.
  return "unknown";
}

class BlazeServer final {
 public:
  explicit BlazeServer(const StartupOptions &startup_options);

  // Acquires locks for the install and output bases this server is running in.
  // Returns the time spent waiting for locks.
  DurationMillis AcquireLocks();

  // Whether there is an active connection to a server.
  bool Connected() const { return client_.get(); }

  // Connect to the server. Returns if the connection was successful. Only
  // call this when this object is in disconnected state. If it returns true,
  // this object will be in connected state.
  bool Connect();

  // Send the command line to the server and forward whatever it says to stdout
  // and stderr. Returns the desired exit code. Only call this when the server
  // is in connected state.
  unsigned int Communicate(
      const std::string &command, const std::vector<std::string> &command_args,
      const std::string &invocation_policy,
      const std::vector<RcStartupFlag> &original_startup_options,
      const LoggingInfo &logging_info, DurationMillis client_startup_duration,
      std::optional<DurationMillis> extract_data_duration,
      std::optional<DurationMillis> command_wait_duration);

  // Disconnects and kills an existing server. Only call this when this object
  // is in connected state.
  void KillRunningServer();

  // Cancel the currently running command. If there is no command currently
  // running, the result is unspecified. When called, this object must be in
  // connected state.
  void Cancel();

  // Returns information about the actual server process and its configuration.
  const ServerProcessInfo &ProcessInfo() const { return process_info_; }

 private:
  std::optional<LockHandle> install_base_lock_;
  std::optional<LockHandle> output_base_lock_;

  enum CancelThreadAction { NOTHING, JOIN, CANCEL, COMMAND_ID_RECEIVED };

  std::unique_ptr<CommandServer::Stub> client_;
  std::string request_cookie_;
  std::string response_cookie_;
  std::string command_id_;

  // protects command_id_ . Although we always set it before making the cancel
  // thread do something with it, the mutex is still useful because it provides
  // a memory fence.
  std::mutex cancel_thread_mutex_;

  // Pipe that the main thread sends actions to and the cancel thread receives
  // actions from.
  std::unique_ptr<blaze_util::IPipe> pipe_;

  void ReleaseLocks();
  bool TryConnect(CommandServer::Stub *client);
  void CancelThread();
  void SendAction(CancelThreadAction action);
  void SendCancelMessage();

  ServerProcessInfo process_info_;
  const int connect_timeout_secs_;
  const bool batch_;
  const bool block_for_lock_;
  const bool quiet_;
  const bool preemptible_;
  const bool lock_install_base_;
  const blaze_util::Path install_base_;
  const blaze_util::Path output_base_;
};

////////////////////////////////////////////////////////////////////////
// Global Variables
static BlazeServer *blaze_server;

// TODO(laszlocsomor) 2016-11-24: release the `blaze_server` object. Currently
// nothing deletes it. Be careful that some functions may call exit(2) or
// _exit(2) (attributed with ATTRIBUTE_NORETURN) meaning we have to delete the
// objects before those.

DurationMillis BlazeServer::AcquireLocks() {
  DurationMillis wait_time;

  if (lock_install_base_) {
    // Take a shared lock on the install base, because two simultaneous commands
    // may run against the same install base. The lock file is a sibling of the
    // install base directory to make it possible to synchronize concurrent
    // creation through an atomic rename. An exclusive lock is only required to
    // run automatic garbage collection of stale install bases.
    // Always block waiting for the lock: in the event that the install base is
    // currently being garbage collected, we want to recreate it.
    if (install_base_lock_.has_value()) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "AcquireLocks() called but the install base lock is already held.";
    }
    blaze_util::Path install_base_parent = install_base_.GetParent();
    blaze_util::MakeDirectories(install_base_parent, 0777);
    auto install_base_result = blaze::AcquireLock(
        "install base",
        install_base_parent.GetRelative(install_base_.GetBaseName() + ".lock"),
        LockMode::kShared, batch_, /* block= */ true);
    install_base_lock_ = install_base_result.first;
    wait_time += install_base_result.second;
  }

  // Take an exclusive lock on the output base, because two simultaneous
  // commands may not run against the same output base.
  if (output_base_lock_.has_value()) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "AcquireLocks() called but the output base lock is already held.";
  }
  auto output_base_result =
      blaze::AcquireLock("output base", output_base_.GetRelative("lock"),
                         LockMode::kExclusive, batch_, block_for_lock_);
  output_base_lock_ = output_base_result.first;
  wait_time += output_base_result.second;

  return wait_time;
}

void BlazeServer::ReleaseLocks() {
  if (!output_base_lock_.has_value()) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "ReleaseLocks() called but the output base lock has already been "
           "released.";
  }
  blaze::ReleaseLock(*output_base_lock_);
  output_base_lock_ = std::nullopt;

  if (lock_install_base_) {
    if (!install_base_lock_.has_value()) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "ReleaseLocks() called but the install base lock has already been "
             "released.";
    }
    blaze::ReleaseLock(*install_base_lock_);
    install_base_lock_ = std::nullopt;
  }
}

////////////////////////////////////////////////////////////////////////
// Logic

static map<string, EnvVarValue> PrepareEnvironmentForJvm();

// Escapes colons by replacing them with '_C' and underscores by replacing
// them with '_U'. E.g. "name:foo_bar" becomes "name_Cfoo_Ubar"
static string EscapeForOptionSource(const string &input) {
  string result = input;
  blaze_util::Replace("_", "_U", &result);
  blaze_util::Replace(":", "_C", &result);
  return result;
}

// Returns the JVM command argument array.
static vector<string> GetServerExeArgs(const blaze_util::Path &jvm_path,
                                       const string &server_jar_path,
                                       const vector<string> &archive_contents,
                                       const string &install_md5,
                                       const WorkspaceLayout &workspace_layout,
                                       const string &workspace,
                                       const StartupOptions &startup_options) {
  vector<string> result;

  // e.g. A Blaze server process running in ~/src/build_root (where there's a
  // ~/src/build_root/WORKSPACE file) will appear in ps(1) as "blaze(src)".
  result.push_back(startup_options.GetLowercaseProductName() + "(" +
                   workspace_layout.GetPrettyWorkspaceName(workspace) + ")");
  startup_options.AddJVMArgumentPrefix(jvm_path.GetParent().GetParent(),
                                       &result);

  // com.google.devtools.build.lib.unsafe.StringUnsafe uses reflection to
  // access private fields in java.lang.String. The Bazel server requires Java
  // 11, so this option is known to be supported.
  result.push_back("--add-opens=java.base/java.lang=ALL-UNNAMED");

  result.push_back("-Xverify:none");

  vector<string> user_options = startup_options.host_jvm_args;

  // Add JVM arguments particular to building blaze64 and particular JVM
  // versions.
  string error;
  blaze_exit_code::ExitCode jvm_args_exit_code =
      startup_options.AddJVMArguments(startup_options.GetServerJavabase(),
                                      &result, user_options, &error);
  if (jvm_args_exit_code != blaze_exit_code::SUCCESS) {
    BAZEL_DIE(jvm_args_exit_code) << error;
  }

  // We put all directories on java.library.path that contain .so/.dll files.
  set<string> java_library_paths;
  std::stringstream java_library_path;
  java_library_path << "-Djava.library.path=";
  blaze_util::Path real_install_dir =
      blaze_util::Path(startup_options.install_base);

  for (const auto &it : archive_contents) {
    if (IsSharedLibrary(it)) {
      string libpath(real_install_dir.GetRelative(blaze_util::Dirname(it))
                         .AsJvmArgument());
      // Only add the library path if it's not added yet.
      if (java_library_paths.insert(libpath).second) {
        if (java_library_paths.size() > 1) {
          java_library_path << kListSeparator;
        }
        java_library_path << libpath;
      }
    }
  }
  result.push_back(java_library_path.str());

  // TODO: Investigate whether this still has any effect. File name encoding
  // is governed by sun.jnu.encoding in JDKs with JEP 400, which can't be
  // influenced by setting a property.
  result.push_back("-Dfile.encoding=ISO-8859-1");
  // Force into the root locale to ensure consistent behavior of string
  // operations across machines (e.g. in the tr_TR locale, capital ASCII 'I'
  // turns into a special Unicode 'i' when converted to lower case).
  // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html#ROOT
  result.push_back("-Duser.country=");
  result.push_back("-Duser.language=");
  result.push_back("-Duser.variant=");

  // Allow more files to be watched per directory than the default limit of 500.
  // The limit of 10,000 is arbitrary, but should be sufficient for most cases
  // and can always be increased by the user if necessary.
  // https://github.com/openjdk/jdk/blob/2faf8b8d582183275b1fdc92313a1c63c1753e80/src/java.base/share/classes/sun/nio/fs/AbstractWatchKey.java#L40
  result.push_back("-Djdk.nio.file.WatchService.maxEventsPerPoll=10000");

#if defined(_WIN32)
  // See and use more than 64 CPUs on Windows.
  // https://bugs.openjdk.org/browse/JDK-6942632
  result.push_back("-XX:+IgnoreUnrecognizedVMOptions");
  result.push_back("-XX:+UseAllWindowsProcessorGroups");
  result.push_back("-XX:-IgnoreUnrecognizedVMOptions");
#endif

  if (startup_options.host_jvm_debug) {
    BAZEL_LOG(USER)
        << "Running host JVM under debugger (listening on TCP port 5005).";
    // Start JVM so that it listens for a connection from a
    // JDWP-compliant debugger:
    result.push_back("-Xdebug");
    result.push_back("-Xrunjdwp:transport=dt_socket,server=y,address=5005");
  }
  result.insert(result.end(), user_options.begin(), user_options.end());

  startup_options.AddJVMArgumentSuffix(real_install_dir, server_jar_path,
                                       &result);

  // JVM arguments are complete. Now pass in Blaze startup options.
  // Note that we always use the --flag=ARG form (instead of the --flag ARG
  // one) so that BlazeRuntime#splitStartupOptions has an easy job.

  // TODO(b/152047869): Test that whatever the list constructed after this
  // line is actually a list of parseable startup options.
  if (!startup_options.batch) {
    result.push_back("--max_idle_secs=" +
                     blaze_util::ToString(startup_options.max_idle_secs));
    if (startup_options.shutdown_on_low_sys_mem) {
      result.push_back("--shutdown_on_low_sys_mem");
    } else {
      result.push_back("--noshutdown_on_low_sys_mem");
    }
  } else {
    // --batch must come first in the arguments to Java main() because
    // the code expects it to be at args[0] if it's been set.
    result.push_back("--batch");
  }

  if (startup_options.command_port != 0) {
    result.push_back("--command_port=" +
                     blaze_util::ToString(startup_options.command_port));
  }

  result.push_back("--connect_timeout_secs=" +
                   blaze_util::ToString(startup_options.connect_timeout_secs));

  result.push_back("--output_user_root=" +
                   startup_options.output_user_root.AsCommandLineArgument());
  result.push_back("--install_base=" +
                   startup_options.install_base.AsCommandLineArgument());
  result.push_back("--install_md5=" + install_md5);
  if (startup_options.lock_install_base) {
    // This flag is not user-settable. Its sole purpose is to alter the behavior
    // for Blaze and Bazel. Do *not* explicitly set it to disabled, because at
    // Google we rely on the ability to run the client code against a server
    // built before the flag was added.
    result.push_back("--lock_install_base");
  }
  result.push_back("--output_base=" +
                   startup_options.output_base.AsCommandLineArgument());
  result.push_back("--workspace_directory=" +
                   blaze_util::ConvertPath(workspace));
  if (startup_options.autodetect_server_javabase) {
    result.push_back("--default_system_javabase=" + GetSystemJavabase());
  }

  if (!startup_options.server_jvm_out.IsEmpty()) {
    result.push_back("--server_jvm_out=" +
                     startup_options.server_jvm_out.AsCommandLineArgument());
  }

  if (!startup_options.failure_detail_out.IsEmpty()) {
    result.push_back(
        "--failure_detail_out=" +
        startup_options.failure_detail_out.AsCommandLineArgument());
  }

  if (!startup_options.digest_function.empty()) {
    // Only include this if a value is requested - we rely on the empty case
    // being "null" to set the programmatic default in the server.
    result.push_back("--digest_function=" + startup_options.digest_function);
  }
  if (!startup_options.unix_digest_hash_attribute_name.empty()) {
    result.push_back("--unix_digest_hash_attribute_name=" +
                     startup_options.unix_digest_hash_attribute_name);
  }
  if (startup_options.idle_server_tasks) {
    result.push_back("--idle_server_tasks");
  } else {
    result.push_back("--noidle_server_tasks");
  }

  if (startup_options.write_command_log) {
    result.push_back("--write_command_log");
  } else {
    result.push_back("--nowrite_command_log");
  }

  if (startup_options.fatal_event_bus_exceptions) {
    result.push_back("--fatal_event_bus_exceptions");
  } else {
    result.push_back("--nofatal_event_bus_exceptions");
  }
  if (startup_options.windows_enable_symlinks) {
    result.push_back("--windows_enable_symlinks");
  } else {
    result.push_back("--nowindows_enable_symlinks");
  }
  // We use this syntax so that the logic in AreStartupOptionsDifferent() that
  // decides whether the server needs killing is simpler. This is parsed by
  // the Java code where --noclient_debug and --client_debug=false are
  // equivalent. Note that --client_debug false (separated by space) won't
  // work either, because the logic in AreStartupOptionsDifferent() assumes
  // that every argument is in the --arg=value form.
  if (startup_options.client_debug) {
    result.push_back("--client_debug=true");
  } else {
    result.push_back("--client_debug=false");
  }

  // These flags are passed to the java process only for Blaze reporting
  // purposes; the real interpretation of the jvm flags occurs when we set up
  // the java command line.
  if (!startup_options.GetExplicitServerJavabase().IsEmpty()) {
    result.push_back(
        "--server_javabase=" +
        startup_options.GetExplicitServerJavabase().AsCommandLineArgument());
  }
  if (startup_options.host_jvm_debug) {
    result.push_back("--host_jvm_debug");
  }
  if (!startup_options.host_jvm_args.empty()) {
    for (const auto &arg : startup_options.host_jvm_args) {
      result.push_back("--host_jvm_args=" + arg);
    }
  }

  // Pass in invocation policy as a startup argument for batch mode only.
  if (startup_options.batch && !startup_options.invocation_policy.empty()) {
    result.push_back("--invocation_policy=" +
                     startup_options.invocation_policy);
  }

  result.push_back("--product_name=" + startup_options.product_name);

#ifdef __linux__
  if (!startup_options.cgroup_parent.empty()) {
    result.push_back("--experimental_cgroup_parent=" +
                     startup_options.cgroup_parent);
  }

  if (startup_options.run_in_user_cgroup) {
    result.push_back("--experimental_run_in_user_cgroup");
  }
#endif

  startup_options.AddExtraOptions(&result);

  // The option sources are transmitted in the following format:
  // --option_sources=option1:source1:option2:source2:...
  string option_sources = "--option_sources=";
  bool first = true;
  for (const auto &it : startup_options.option_sources) {
    if (!first) {
      option_sources += ":";
    }

    first = false;
    option_sources += EscapeForOptionSource(it.first) + ":" +
                      EscapeForOptionSource(it.second);
  }

  result.push_back(option_sources);
  return result;
}

// Add common command options for logging to the given argument array.
static void AddLoggingArgs(
    const LoggingInfo &logging_info,
    const DurationMillis client_startup_duration,
    const std::optional<DurationMillis> extract_data_duration,
    const std::optional<DurationMillis> command_wait_duration,
    vector<string> *args) {
  // The time in ms the launcher spends before sending the request to the blaze
  // server.
  args->push_back("--startup_time=" +
                  blaze_util::ToString(client_startup_duration.millis));

  // The time in ms a command had to wait on a busy Blaze server process.
  // This is part of startup_time.
  if (command_wait_duration.has_value()) {
    args->push_back("--command_wait_time=" +
                    blaze_util::ToString(command_wait_duration->millis));
  }

  // The time in ms spent on extracting the new blaze version.
  // This is part of startup_time.
  if (extract_data_duration.has_value()) {
    args->push_back("--extract_data_time=" +
                    blaze_util::ToString(extract_data_duration->millis));
  }

  if (logging_info.restart_reason != NO_RESTART) {
    args->push_back(string("--restart_reason=") +
                    ReasonString(logging_info.restart_reason));
  }
  args->push_back(string("--binary_path=") + logging_info.binary_path);
}

// Join the elements of the specified array with NUL's (\0's), akin to the
// format of /proc/$PID/cmdline.
static string GetArgumentString(const vector<string> &argument_array) {
  string result;
  blaze_util::JoinStrings(argument_array, '\0', &result);
  return result;
}

static void EnsureServerDir(const blaze_util::Path &server_dir) {
  // The server dir has the connection info - don't allow access by other users.
  if (!blaze_util::MakeDirectories(server_dir, 0700)) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "server directory '" << server_dir.AsPrintablePath()
        << "' could not be created: " << GetLastErrorString();
  }
}

// Do a chdir into the workspace, and die if it fails.
static const void GoToWorkspace(const WorkspaceLayout &workspace_layout,
                                const string &workspace) {
  if (workspace_layout.InWorkspace(workspace) &&
      !blaze_util::ChangeDirectory(workspace)) {
    BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
        << "changing directory into " << workspace
        << " failed: " << GetLastErrorString();
  }
}

static const bool IsServerMode(const string &command) {
  return "exec-server" == command;
}

// Replace this process with the blaze server. Does not exit.
static void RunServerMode(
    const blaze_util::Path &server_exe, const vector<string> &server_exe_args,
    const blaze_util::Path &server_dir, const WorkspaceLayout &workspace_layout,
    const string &workspace, const OptionProcessor &option_processor,
    const StartupOptions &startup_options, BlazeServer *server) {
  if (startup_options.batch) {
    BAZEL_DIE(blaze_exit_code::BAD_ARGV)
        << "exec-server command is not compatible with --batch";
  }

  BAZEL_LOG(INFO) << "Running in server mode.";

  if (server->Connected()) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "exec-server failed, please shut down existing server pid="
        << server->ProcessInfo().server_pid_ << " and retry.";
  }

  EnsureServerDir(server_dir);

  blaze_util::WriteFile(blaze::GetProcessIdAsString(),
                        server_dir.GetRelative("server.pid.txt"));
  blaze_util::WriteFile(GetArgumentString(server_exe_args),
                        server_dir.GetRelative("cmdline"));

  GoToWorkspace(workspace_layout, workspace);

  SetScheduling(startup_options.batch_cpu_scheduling,
                startup_options.io_nice_level);

  {
    WithEnvVars env_obj(PrepareEnvironmentForJvm());
#ifdef __linux__
    bool run_in_user_cgroup = startup_options.run_in_user_cgroup;
#else
    bool run_in_user_cgroup = false;
#endif
    ExecuteServerJvm(server_exe, server_exe_args, run_in_user_cgroup);
  }
}

// Replace this process with blaze in standalone/batch mode.
// The batch mode blaze process handles the command and exits.
static void RunBatchMode(
    const blaze_util::Path &server_exe, const vector<string> &server_exe_args,
    const WorkspaceLayout &workspace_layout, const string &workspace,
    const OptionProcessor &option_processor,
    const StartupOptions &startup_options, LoggingInfo *logging_info,
    const std::optional<DurationMillis> extract_data_duration,
    const std::optional<DurationMillis> command_wait_duration,
    BlazeServer *server) {
  if (server->Connected()) {
    server->KillRunningServer();
  }

  const DurationMillis client_startup_duration(logging_info->start_time_ms,
                                               GetMillisecondsMonotonic());

  BAZEL_LOG(INFO) << "Starting " << startup_options.product_name
                  << " in batch mode.";

  const string command = option_processor.GetCommand();
  const vector<string> command_arguments =
      option_processor.GetCommandArguments();

  if (!command_arguments.empty() && command == "shutdown") {
    string product = startup_options.GetLowercaseProductName();
    BAZEL_LOG(WARNING)
        << "Running command \"shutdown\" in batch mode.  Batch mode is "
           "triggered\nwhen not running "
        << startup_options.product_name
        << " within a workspace. If you intend to shutdown an\nexisting "
        << startup_options.product_name << " server, run \"" << product
        << " shutdown\" from the directory where\nit was started.";
  }

  vector<string> jvm_args_vector = server_exe_args;

  if (!command.empty()) {
    jvm_args_vector.push_back(command);
    AddLoggingArgs(*logging_info, client_startup_duration,
                   extract_data_duration, command_wait_duration,
                   &jvm_args_vector);
  }

  jvm_args_vector.insert(jvm_args_vector.end(), command_arguments.begin(),
                         command_arguments.end());

  GoToWorkspace(workspace_layout, workspace);

  SetScheduling(startup_options.batch_cpu_scheduling,
                startup_options.io_nice_level);

  {
    WithEnvVars env_obj(PrepareEnvironmentForJvm());
#ifdef __linux__
    bool run_in_user_cgroup = startup_options.run_in_user_cgroup;
#else
    bool run_in_user_cgroup = false;
#endif
    ExecuteServerJvm(server_exe, jvm_args_vector, run_in_user_cgroup);
  }
}

static void WriteFileToStderrOrDie(const blaze_util::Path &path) {
#if defined(_WIN32) || defined(__CYGWIN__)
  FILE *fp = _wfopen(path.AsNativePath().c_str(), L"r");
#else
  FILE *fp = fopen(path.AsNativePath().c_str(), "r");
#endif

  if (fp == nullptr) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "opening " << path.AsPrintablePath()
        << " failed: " << GetLastErrorString();
  }
  char buffer[255];
  int num_read;
  while ((num_read = fread(buffer, 1, sizeof buffer, fp)) > 0) {
    if (ferror(fp)) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "failed to read from '" << path.AsPrintablePath()
          << "': " << GetLastErrorString();
    }
    fwrite(buffer, 1, num_read, stderr);
  }
  fclose(fp);
}

// After connecting to the Blaze server, return its PID, or -1 if there was an
// error.
static int GetServerPid(const blaze_util::Path &server_dir) {
  // Note: there is no race here on startup since the server creates
  // the pid file strictly before it binds the socket.
  blaze_util::Path pid_file = server_dir.GetRelative(kServerPidFile);
  string bufstr;
  int result;
  if (!blaze_util::ReadFile(pid_file, &bufstr, 32) ||
      !blaze_util::safe_strto32(bufstr, &result)) {
    return -1;
  }

  return result;
}

// Connect to the server process or exit if it doesn't work out.
static void ConnectOrDie(const OptionProcessor &option_processor,
                         const StartupOptions &startup_options,
                         const int server_pid,
                         BlazeServerStartup *server_startup,
                         BlazeServer *server) {
  // Give the server two minutes to start up. That's enough to connect with a
  // debugger.
  const auto start_time = std::chrono::system_clock::now();
  const auto try_until_time =
      start_time +
      std::chrono::seconds(startup_options.local_startup_timeout_secs);
  // Print an update at most once every 10 seconds if we are still trying to
  // connect.
  const auto min_message_interval = std::chrono::seconds(10);
  auto last_message_time = start_time;
  while (std::chrono::system_clock::now() < try_until_time) {
    const auto attempt_time = std::chrono::system_clock::now();
    const auto next_attempt_time =
        attempt_time + std::chrono::milliseconds(100);

    if (server->Connect()) {
      return;
    }

    if (attempt_time >= (last_message_time + min_message_interval)) {
      auto elapsed_time = std::chrono::duration_cast<std::chrono::seconds>(
          attempt_time - start_time);
      BAZEL_LOG(USER) << "... still trying to connect to local "
                      << startup_options.product_name << " server ("
                      << server_pid << ") after " << elapsed_time.count()
                      << " seconds ...";
      last_message_time = attempt_time;
    }

    std::this_thread::sleep_until(next_attempt_time);
    if (!server_startup->IsStillAlive()) {
      option_processor.PrintStartupOptionsProvenanceMessage();
      if (server->ProcessInfo().jvm_log_file_append_) {
        // Don't dump the log if we were appending - the user should know where
        // to find it, and who knows how much content they may have accumulated.
        BAZEL_LOG(USER)
            << "Server crashed during startup. See "
            << server->ProcessInfo().jvm_log_file_.AsPrintablePath();
      } else {
        BAZEL_LOG(USER)
            << "Server crashed during startup. Now printing "
            << server->ProcessInfo().jvm_log_file_.AsPrintablePath();
        WriteFileToStderrOrDie(server->ProcessInfo().jvm_log_file_);
      }
      exit(blaze_exit_code::INTERNAL_ERROR);
    }
  }
  BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
      << "couldn't connect to server (" << server_pid << ") after "
      << startup_options.local_startup_timeout_secs << " seconds.";
}

// Ensures that any server previously associated with `server_dir` is no longer
// running.
static void EnsurePreviousServerProcessTerminated(
    const blaze_util::Path &server_dir, const StartupOptions &startup_options,
    LoggingInfo *logging_info) {
  int server_pid = GetServerPid(server_dir);
  if (server_pid > 0) {
    if (VerifyServerProcess(server_pid, startup_options.output_base)) {
      if (KillServerProcess(server_pid, startup_options.output_base)) {
        BAZEL_LOG(USER) << "Killed non-responsive server process (pid="
                        << server_pid << ")";
        logging_info->SetRestartReasonIfNotSet(SERVER_UNRESPONSIVE);
      } else {
        logging_info->SetRestartReasonIfNotSet(SERVER_VANISHED);
      }
    } else {
      logging_info->SetRestartReasonIfNotSet(PID_FILE_BUT_NO_SERVER);
    }
  }
}

// Starts up a new server and connects to it. Exits if it didn't work out.
static void StartServerAndConnect(
    const blaze_util::Path &server_exe, const vector<string> &server_exe_args,
    const blaze_util::Path &server_dir, const WorkspaceLayout &workspace_layout,
    const string &workspace, const OptionProcessor &option_processor,
    const StartupOptions &startup_options, LoggingInfo *logging_info,
    BlazeServer *server, const string &build_label) {
  // Delete the old command_port file if it already exists. Otherwise we might
  // run into the race condition that we read the old command_port file before
  // the new server has written the new file and we try to connect to the old
  // port, run into a timeout and try again.
  (void)blaze_util::UnlinkPath(server_dir.GetRelative("command_port"));

  // Delete the OOM file if it exists (e.g. leftover from a previous server that
  // OOmed). Otherwise in the future we might incorrectly think this server
  // OOMed.
  if (blaze_util::UnlinkPath(GetOOMFilePath(startup_options.output_base))) {
    BAZEL_LOG(INFO) << "Deleted old OOM file.";
  }

  EnsureServerDir(server_dir);

  // Really make sure there's no other server running in this output base (even
  // an unresponsive one), as that could cause major problems.
  EnsurePreviousServerProcessTerminated(server_dir, startup_options,
                                        logging_info);

  // cmdline file is used to validate the server running in this server_dir.
  // There's no server running now so we're safe to unconditionally write this.
  blaze_util::WriteFile(GetArgumentString(server_exe_args),
                        server_dir.GetRelative("cmdline"));

  // Do this here instead of in the daemon so the user can see if it fails.
  GoToWorkspace(workspace_layout, workspace);

  logging_info->SetRestartReasonIfNotSet(NO_DAEMON);

  SetScheduling(startup_options.batch_cpu_scheduling,
                startup_options.io_nice_level);

  BAZEL_LOG(USER) << "Starting local " << startup_options.product_name
                  << " server (" << build_label << ")"
                  << " and connecting to it...";
  BlazeServerStartup *server_startup;
  const int server_pid = ExecuteDaemon(
      server_exe, server_exe_args, PrepareEnvironmentForJvm(),
      server->ProcessInfo().jvm_log_file_,
      server->ProcessInfo().jvm_log_file_append_, startup_options.install_base,
      server_dir, startup_options, &server_startup);

  ConnectOrDie(option_processor, startup_options, server_pid, server_startup,
               server);

  delete server_startup;
}

static bool IsVolatileArg(const string &arg) {
  // TODO(ccalvarin) when --batch is gone and the startup_options field in the
  // gRPC message is always set, there is no reason for client options that are
  // not used at server startup to be part of the startup command line. The
  // server command line difference logic can be simplified then.
  static const std::set<string> volatile_startup_options = {
      "--option_sources=", "--max_idle_secs=", "--connect_timeout_secs=",
      "--local_startup_timeout_secs=", "--client_debug=", "--preemptible=",
      // Internally, -XX:HeapDumpPath is set automatically via the user's TMPDIR
      // environment variable. Since that can change based on the shell, we
      // tolerate changes to it. Note that an explicit setting of
      // -XX:HeapDumpPath via --host_jvm_args *will* trigger a restart.
      "-XX:HeapDumpPath="};

  // Split arg based on the first "=" if one exists in arg.
  const string::size_type eq_pos = arg.find_first_of('=');
  const string stripped_arg =
      (eq_pos == string::npos) ? arg : arg.substr(0, eq_pos + 1);

  return volatile_startup_options.count(stripped_arg);
}

// Returns true if the server needs to be restarted to accommodate changes
// between the two argument lists.
static bool AreStartupOptionsDifferent(
    const vector<string> &running_server_args,
    const vector<string> &requested_args) {
  // We need not worry about one side missing an argument and the other side
  // having the default value, since this command line is the canonical one for
  // this version of Bazel: either the default value is listed explicitly or it
  // is not, but this has nothing to do with the user's command line: it is
  // defined by GetServerExeArgs(). Same applies for argument ordering.
  bool options_different = false;
  if (running_server_args.size() != requested_args.size()) {
    BAZEL_LOG(INFO) << "The new command line has a different length from the "
                       "running server's.";
    options_different = true;
  }

  // Facts and implications:
  // (a) We already verified (with EnsureCorrectRunningVersion) that the old and
  //     new server versions are the same. Therefore we know that
  //     'running_server_args' and 'requested_args' follow the same ordering for
  //     flags, the same logic of deduplicating vs. not deduplicating flags, the
  //     same format of canonicalizing flags, etc.
  // (b) Some startup flags may come from user bazelrc files.
  // (c) Because of (b), the ordering of flags doesn't matter, because if the
  //     user flips two "startup" lines in their bazelrc, that doesn't change
  //     the effective set of startup flags.
  // (d) Because of (b), some flags may have repeated values (e.g
  //     --host_jvm_args="foo" twice) so we cannot simply use two sets and take
  //     the set difference, but must consider the occurrences of each flag.
  std::unordered_multiset<string> old_args, new_args;
  for (const string &a : running_server_args) {
    if (!IsVolatileArg(a)) {
      old_args.insert(a);
    }
  }
  for (const string &a : requested_args) {
    if (!IsVolatileArg(a)) {
      auto it = old_args.find(a);
      if (it != old_args.end()) {
        old_args.erase(it);  // remove one instance
      } else {
        new_args.insert(a);
      }
    }
  }

  if (!old_args.empty()) {
    BAZEL_LOG(INFO) << "Args from the running server that are not "
                       "included in the current request:";
    for (const string &a : old_args) {
      BAZEL_LOG(INFO) << "  " << a;
    }
  }
  if (!new_args.empty()) {
    BAZEL_LOG(INFO) << "Args from the current request that were not "
                       "included when creating the server:";
    for (const string &a : new_args) {
      BAZEL_LOG(INFO) << "  " << a;
    }
  }

  return options_different || !old_args.empty() || !new_args.empty();
}

// Kills the running Blaze server, if any, if the startup options do not match.
// Returns true if the server has been killed.
static bool KillRunningServerIfDifferentStartupOptions(
    const StartupOptions &startup_options,
    const vector<string> &server_exe_args, LoggingInfo *logging_info,
    BlazeServer *server) {
  if (!server->Connected()) {
    return false;
  }

  blaze_util::Path cmdline_path =
      startup_options.output_base.GetRelative("server/cmdline");
  string old_joined_arguments;

  // No, /proc/$PID/cmdline does not work, because it is limited to 4K. Even
  // worse, its behavior differs slightly between kernels (in some, when longer
  // command lines are truncated, the last 4 bytes are replaced with
  // "..." + NUL.
  blaze_util::ReadFile(cmdline_path, &old_joined_arguments);
  vector<string> old_arguments = blaze_util::Split(old_joined_arguments, '\0');

  // These strings contain null-separated command line arguments. If they are
  // the same, the server can stay alive, otherwise, it needs shuffle off this
  // mortal coil.
  if (AreStartupOptionsDifferent(old_arguments, server_exe_args)) {
    logging_info->restart_reason = NEW_OPTIONS;
    BAZEL_LOG(WARNING) << "Running " << startup_options.product_name
                       << " server needs to be killed, because the startup "
                          "options are different.";
    server->KillRunningServer();
    return true;
  }
  return false;
}

// Kills the old running server if it is not the same version as us,
// dealing with various combinations of installation scheme
// (installation symlink and older MD5_MANIFEST contents).
// This function requires that the installation be complete, and the
// server lock acquired.
static void EnsureCorrectRunningVersion(const StartupOptions &startup_options,
                                        LoggingInfo *logging_info,
                                        BlazeServer *server) {
  // Read the previous installation's semaphore symlink in output_base. If the
  // target dirs don't match, or if the symlink was not present, then kill any
  // running servers. Lastly, symlink to our installation so others know which
  // installation is running.
  const blaze_util::Path install_base_symlink =
      startup_options.output_base.GetRelative("install");
  blaze_util::Path prev_install_base;
  if (!blaze_util::ReadDirectorySymlink(install_base_symlink,
                                        &prev_install_base) ||
      !blaze_util::ArePathsEquivalent(prev_install_base,
                                      startup_options.install_base)) {
    if (server->Connected()) {
      BAZEL_LOG(INFO)
          << "Killing running server because it is using another version of "
          << startup_options.product_name;
      server->KillRunningServer();
      logging_info->restart_reason = NEW_VERSION;
    }

    blaze_util::UnlinkPath(install_base_symlink);
    if (!SymlinkDirectories(startup_options.install_base,
                            install_base_symlink)) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "failed to create installation symlink '"
          << install_base_symlink.AsPrintablePath()
          << "': " << GetLastErrorString();
    }

    // Update the mtime of the install base so that cleanup tools can
    // find install bases that haven't been used for a long time.
    // Ignore permissions errors (i.e. if the install base is not writable).
    if (!SetMtimeToNowIfPossible(
            blaze_util::Path(startup_options.install_base))) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "failed to set timestamp on '"
          << startup_options.install_base.AsPrintablePath()
          << "': " << GetLastErrorString();
    }
  }
}

static void CancelServer() { blaze_server->Cancel(); }

// Runs the launcher in client/server mode. Ensures that there's indeed a
// running server, then forwards the user's command to the server and the
// server's response back to the user. Does not return - exits via exit or
// signal.
static ATTRIBUTE_NORETURN void RunClientServerMode(
    const blaze_util::Path &server_exe, const vector<string> &server_exe_args,
    const blaze_util::Path &server_dir, const WorkspaceLayout &workspace_layout,
    const string &workspace, const OptionProcessor &option_processor,
    const StartupOptions &startup_options, LoggingInfo *logging_info,
    const std::optional<DurationMillis> extract_data_duration,
    const std::optional<DurationMillis> command_wait_duration,
    BlazeServer *server, const string &build_label) {
  while (true) {
    if (!server->Connected()) {
      StartServerAndConnect(server_exe, server_exe_args, server_dir,
                            workspace_layout, workspace, option_processor,
                            startup_options, logging_info, server, build_label);
    }

    // Check for the case when the workspace directory deleted and then gets
    // recreated while the server is running.

    std::unique_ptr<blaze_util::Path> server_cwd =
        GetProcessCWD(server->ProcessInfo().server_pid_);
    // If server_cwd is nullptr, GetProcessCWD failed. This notably occurs when
    // running under Docker because then readlink(/proc/[pid]/cwd) returns
    // EPERM.
    // Docker issue #6687 (https://github.com/docker/docker/issues/6687) fixed
    // this, but one still needs the --cap-add SYS_PTRACE command line flag, at
    // least according to the discussion on Docker issue #6800
    // (https://github.com/docker/docker/issues/6687), and even then, it's a
    // non-default Docker flag. Given that this occurs only in very weird
    // cases, it's better to assume that everything is alright if we can't get
    // the cwd.

    if (server_cwd != nullptr &&
        (*server_cwd != blaze_util::Path(workspace) ||  // changed
         server_cwd->Contains(" (deleted)"))) {         // deleted.
      // There's a distant possibility that the two paths look the same yet are
      // actually different because the two processes have different mount
      // tables.
      BAZEL_LOG(INFO) << "Server's cwd moved or deleted ("
                      << server_cwd->AsPrintablePath() << ").";
      server->KillRunningServer();
    } else {
      break;
    }
  }

  BAZEL_LOG(INFO) << "Connected (server pid="
                  << server->ProcessInfo().server_pid_ << ").";

  // Wall clock time since process startup.
  const DurationMillis client_startup_duration(logging_info->start_time_ms,
                                               GetMillisecondsMonotonic());

  SignalHandler::Get().Install(startup_options.product_name,
                               startup_options.output_base,
                               &server->ProcessInfo(), CancelServer);
  SignalHandler::Get().PropagateSignalOrExit(server->Communicate(
      option_processor.GetCommand(), option_processor.GetCommandArguments(),
      startup_options.invocation_policy,
      startup_options.original_startup_options_, *logging_info,
      client_startup_duration, extract_data_duration, command_wait_duration));
}

// Parse the options.
static void ParseOptionsOrDie(const string &cwd, const string &workspace,
                              OptionProcessor &option_processor, int argc,
                              const char *const *argv) {
  std::string error;
  std::vector<std::string> args(argv, argv + argc);
  const blaze_exit_code::ExitCode parse_exit_code =
      option_processor.ParseOptions(args, workspace, cwd, &error);

  if (parse_exit_code != blaze_exit_code::SUCCESS) {
    option_processor.PrintStartupOptionsProvenanceMessage();
    BAZEL_DIE(parse_exit_code) << error;
  }
}

static string GetCanonicalCwd() {
  string result = blaze_util::MakeCanonical(blaze_util::GetCwd().c_str());
  if (result.empty()) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "blaze_util::MakeCanonical('" << blaze_util::GetCwd()
        << "') failed: " << GetLastErrorString();
  }
  return result;
}

static void PrepareDirectories(StartupOptions *startup_options) {
  blaze::CreateSecureDirectory(
      blaze_util::Path(startup_options->output_user_root));

  if (!blaze_util::PathExists(startup_options->output_base)) {
    if (!blaze_util::MakeDirectories(startup_options->output_base, 0777)) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "Output base directory '"
          << startup_options->output_base.AsPrintablePath()
          << "' could not be created: " << GetLastErrorString();
    }
  } else {
    if (!blaze_util::IsDirectory(startup_options->output_base)) {
      BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
          << "Output base directory '"
          << startup_options->output_base.AsPrintablePath()
          << "' could not be created. It exists but is not a directory.";
    }
  }
  if (!blaze_util::CanAccessDirectory(startup_options->output_base)) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "Output base directory '"
        << startup_options->output_base.AsPrintablePath()
        << "' must be readable and writable.";
  }

  ExcludePathFromBackup(startup_options->output_base);

  startup_options->output_base = startup_options->output_base.Canonicalize();
  if (startup_options->output_base.IsEmpty()) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "Output base directory could not be canonicalized: "
        << GetLastErrorString();
  }
}

// Prepares the environment to be suitable to start a JVM.
// Changes made to the environment in this function *will not* be part
// of '--client_env'.
static map<string, EnvVarValue> PrepareEnvironmentForJvm() {
  map<string, EnvVarValue> result;

  // Make sure all existing environment variables appear as part of the
  // resulting map unless they are overridden below by UNSET values.
  //
  // Even though the map we return is intended to represent a "delta" of
  // environment variables to modify the current process, we may actually use
  // such map to configure a process from scratch (via interfaces like execvpe
  // or posix_spawn), so we need to inherit any untouched variables.
  for (char **entry = environ; *entry != nullptr; entry++) {
    const std::string var_value = *entry;
    std::string::size_type equals = var_value.find('=');
    if (equals == std::string::npos) {
      // Ignore possibly-bad environment. We don't control what we see in this
      // global variable, so it could be invalid.
      continue;
    }
    const std::string var = var_value.substr(0, equals);
    const std::string value = var_value.substr(equals + 1);
    result[var] = EnvVarValue(EnvVarAction::SET, value);
  }

  if (blaze::ExistsEnv("LD_ASSUME_KERNEL")) {
    // Fix for bug: if ulimit -s and LD_ASSUME_KERNEL are both
    // specified, the JVM fails to create threads.  See thread_stack_regtest.
    // This is also provoked by LD_LIBRARY_PATH=/usr/lib/debug,
    // or anything else that causes the JVM to use LinuxThreads.
    BAZEL_LOG(WARNING) << "ignoring LD_ASSUME_KERNEL in environment.";
    result["LD_ASSUME_KERNEL"] = EnvVarValue(EnvVarAction::UNSET, "");
  }

  if (blaze::ExistsEnv("LD_PRELOAD")) {
    BAZEL_LOG(WARNING) << "ignoring LD_PRELOAD in environment.";
    result["LD_PRELOAD"] = EnvVarValue(EnvVarAction::UNSET, "");
  }

  // These would override --host_jvm_args
  for (const auto &var :
       {"_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS"}) {
    if (blaze::ExistsEnv(var)) {
      BAZEL_LOG(WARNING) << "ignoring " << var << " in environment.";
      result[var] = EnvVarValue(EnvVarAction::UNSET, "");
    }
  }

  // TODO(bazel-team):  We've also seen a failure during loading (creating
  // threads?) when ulimit -Hs 8192.  Characterize that and check for it here.

  // Make the JVM use ISO-8859-1 for parsing its command line because "blaze
  // run" doesn't handle non-ASCII command line arguments. This is apparently
  // the most reliable way to select the platform default encoding.
  //
  // On Linux, only do this if the locale is available to avoid the JVM
  // falling back to ASCII-only mode.

  const char *want_locale = "en_US.ISO-8859-1";
  bool override_locale = true;
#ifndef _WIN32
  locale_t iso_locale = newlocale(LC_CTYPE_MASK, want_locale, (locale_t)0);
  if (iso_locale == 0) {
    // ISO-8859-1 locale not available, use whatever the user has defined.
    override_locale = false;
  } else {
    freelocale(iso_locale);
  }
#endif

  if (override_locale) {
    result["LANG"] = EnvVarValue(EnvVarAction::SET, want_locale);
    result["LANGUAGE"] = EnvVarValue(EnvVarAction::SET, want_locale);
    result["LC_ALL"] = EnvVarValue(EnvVarAction::SET, want_locale);
    result["LC_CTYPE"] = EnvVarValue(EnvVarAction::SET, want_locale);
  }

  return result;
}

static string CheckAndGetBinaryPath(const string &cwd, const string &argv0) {
  if (blaze_util::IsAbsolute(argv0)) {
    return argv0;
  } else {
    string abs_path = blaze_util::JoinPath(cwd, argv0);
    string resolved_path = blaze_util::MakeCanonical(abs_path.c_str());
    if (!resolved_path.empty()) {
      return resolved_path;
    } else {
      // This happens during our integration tests, but thats okay, as we won't
      // log the invocation anyway.
      return abs_path;
    }
  }
}

static int GetExitCodeForAbruptExit(const blaze_util::Path &output_base) {
  BAZEL_LOG(INFO) << "Looking for a custom exit-code file.";
  blaze_util::Path exit_code_file_path = GetAbruptExitFilePath(output_base);
  if (blaze_util::PathExists(exit_code_file_path)) {
    std::string content;
    if (!blaze_util::ReadFile(exit_code_file_path, &content)) {
      BAZEL_LOG(INFO) << "Unable to read the custom exit-code file. "
                      << "Exiting with an INTERNAL_ERROR.";
      return blaze_exit_code::INTERNAL_ERROR;
    }
    int custom_exit_code;
    if (!blaze_util::safe_strto32(content, &custom_exit_code)) {
      BAZEL_LOG(INFO) << "Content of custom exit-code file not an int: "
                      << content << "Exiting with an INTERNAL_ERROR.";
      return blaze_exit_code::INTERNAL_ERROR;
    }
    BAZEL_LOG(INFO) << "Read exit code " << custom_exit_code
                    << " from custom exit-code file. Exiting accordingly.";
    return custom_exit_code;
  }
  BAZEL_LOG(INFO) << "No custom exit-code file found. Looking for an OOM file.";
  if (blaze_util::PathExists(GetOOMFilePath(output_base))) {
    BAZEL_LOG(INFO) << "The JVM wrote the OOM file. Exiting with OOM_ERROR.";
    return blaze_exit_code::OOM_ERROR;
  }
  BAZEL_LOG(INFO) << "Unable to determine why the server exited abruptly. "
                  << "Exiting with INTERNAL_ERROR.";
  return blaze_exit_code::INTERNAL_ERROR;
}

void PrintBazelLeaf() {
  // Bazel's basil leaf and B-shaped logo at initial release, back in 2015. The
  // new heart shaped logo was unveiled in 2017:
  // https://blog.bazel.build/2017/07/05/new-logo-and-homepage.html
  const string leaf =
      ".:                                                                    \n"
      "+: `:`                                                                \n"
      "o:  -o:`                                                              \n"
      "o:   `:+/-.`                                                          \n"
      "o:     `./oo+:-.``                                                    \n"
      "o:        ``-/+soo//-`                                                \n"
      "oo.            ..:+osso:-`                                            \n"
      "oo.  `.``          `-:+sss+.`                                         \n"
      "oo.  `://:.`           `-+sys:`                                       \n"
      "oo.      .://:.`          `:oys:`                                     \n"
      "oo+         .:+o/:`         `:syo-                                    \n"
      "ooo            -/oo+:         `:ss-                                   \n"
      "ooo              `:oso-.        -ys-                                  \n"
      "ooo:               `:sss+.       :ss                                  \n"
      ".oo/                 .osss-       :s/                                 \n"
      ".oo+.                 `:sss+-      oo.                                \n"
      " :oo/`                  -osss-     -s-                                \n"
      " `+oo/                   .osss+.   `s-       `...:/://::::-           \n"
      "  `+oo/`                  .ossso.   :-   `-/+oosos+sssssss-           \n"
      "   `:+o+.`                 -ossso`  `` `:ossssssssssssssso.           \n"
      "     `:+oo:.`               :ssss+`   .ossssssssssssssooo/            \n"
      "       `:/+++/-.``           +ssss:   +sssssssssssoooooo+`            \n"
      "          `-:++o+/:::````    -oooos` .osssoooooooooooooo-             \n"
      "               ...-::::::---``ooooo- /oooo//ooooooooooo-              \n"
      "                             `ooooo+`/oo//ooooooooooo+.               \n"
      "                              /ooooo+oo/:oooooooooo/:.                \n"
      "                              .ooooooo-.//+oooo//-.                   \n"
      "                              .oooooo-     ````                       \n"
      "                              .ooooo/`                                \n"
      "                             `+/+oo+                                  \n"
      "                             `++++++   `.--//////////:..``            \n"
      "                             `+++++/`-:+++++++++++++++++//:.`         \n"
      "                             `++++++/++++++++++++++++++++++//-.       \n"
      "                             .++++++++++++/::.......-://+++++//:`     \n"
      "                             /+++++++++/--`           `.-////////.`   \n"
      "                             /+++++++/-`                 `-///////-   \n"
      "                            `///////:`                     `-//////-  \n"
      "                            -//////-                         .//////. \n"
      "                            -/////-                           ://///: \n"
      "                           `//////.                           `////::.\n"
      "                           ./////:`                           `/:::::.\n"
      "                           .//////.                           `::::::.\n"
      "                           `::////.                           `:::::- \n"
      "                            -::::::`                         `::::::- \n"
      "                            `:::::::`                       `-::::::` \n"
      "                             `:::::::.                     `-::::::.  \n"
      "                              `:::::::-.                ``-:-::::-.   \n"
      "                               `.:::::::--``         ``.--::-::--`    \n"
      "                                 .-::::::::---.....---::::----.`      \n"
      "                                  ``.-::::::::::::-::--------`        \n"
      "                                      `.----------------..`           \n"
      "                                         ```.........```             \n";

  printf("%s\n", leaf.c_str());
}

void PrintVersionInfo(const string &self_path, const string &product_name) {
  string build_label;
  ExtractBuildLabel(self_path, &build_label);
  printf("%s %s\n", product_name.c_str(), build_label.c_str());
}

static void RunLauncher(const string &self_path,
                        const vector<string> &archive_contents,
                        const string &install_md5,
                        const StartupOptions &startup_options,
                        const OptionProcessor &option_processor,
                        const WorkspaceLayout &workspace_layout,
                        const string &workspace, LoggingInfo *logging_info) {
  blaze_server = new BlazeServer(startup_options);

  const std::optional<DurationMillis> command_wait_duration =
      blaze_server->AcquireLocks();
  const uint64_t wait_ms =
      command_wait_duration.has_value() ? command_wait_duration->millis : 0;
  BAZEL_LOG(INFO) << "Acquired the client lock, waited " << wait_ms
                  << " milliseconds";

  WarnFilesystemType(startup_options.output_base);

  const std::optional<DurationMillis> extract_data_duration = ExtractData(
      self_path, archive_contents, install_md5, startup_options, logging_info);

  blaze_server->Connect();

  if (!startup_options.batch && "shutdown" == option_processor.GetCommand() &&
      !blaze_server->Connected()) {
    // TODO(b/134525510): Connected() can return false when the server process
    // is alive but unresponsive, so bailing early here might not always be the
    // right thing to do.
    return;
  }

  EnsureCorrectRunningVersion(startup_options, logging_info, blaze_server);

  const blaze_util::Path jvm_path = startup_options.GetJvm();
  const string server_jar_path = GetServerJarPath(archive_contents);

  const blaze_util::Path server_exe =
      startup_options.GetExe(jvm_path, server_jar_path);

  vector<string> server_exe_args =
      GetServerExeArgs(jvm_path, server_jar_path, archive_contents, install_md5,
                       workspace_layout, workspace, startup_options);
#if defined(__OpenBSD__)
  // When spawning the server's JVM process, we normally set argv[0] to
  // "bazel(workspace)". On OpenBSD, doing so causes the JVM process to fail
  // during startup; ld.so fails to find a shared library that exists in
  // /usr/local/jdk-1.8.0/jre/lib/amd64. Setting LD_LIBRARY_PATH does not help,
  // but setting argv[0] to the JVM binary's path
  // (/usr/local/jdk-1.8.0/bin/java) allows the JVM process to run. The JVM
  // process apparently tries to compute a path to where the shared libraries
  // should be, via a relative path from the JVM executable's path -- but
  // OpenBSD does not provide a way for a process to determine a path to its
  // own executable, and so the JVM falls back to searching the PATH for
  // argv[0], which of course fails when argv[0] looks like "bazel(workspace)".
  //
  // TODO(aldersondrive): This hack is unnecessary on FreeBSD, but the relevant
  // OpenJDK code doesn't seem to include anything FreeBSD-specific.
  // Investigate why and possibly remove this.
  server_exe_args[0] = server_exe.AsNativePath();
#endif

  if (KillRunningServerIfDifferentStartupOptions(
          startup_options, server_exe_args, logging_info, blaze_server) &&
      "shutdown" == option_processor.GetCommand()) {
    return;
  }

  const blaze_util::Path server_dir =
      blaze_util::Path(startup_options.output_base).GetRelative("server");
  if (IsServerMode(option_processor.GetCommand())) {
    RunServerMode(server_exe, server_exe_args, server_dir, workspace_layout,
                  workspace, option_processor, startup_options, blaze_server);
  } else if (startup_options.batch) {
    RunBatchMode(server_exe, server_exe_args, workspace_layout, workspace,
                 option_processor, startup_options, logging_info,
                 extract_data_duration, command_wait_duration, blaze_server);
  } else {
    string build_label;
    ExtractBuildLabel(self_path, &build_label);
    RunClientServerMode(server_exe, server_exe_args, server_dir,
                        workspace_layout, workspace, option_processor,
                        startup_options, logging_info, extract_data_duration,
                        command_wait_duration, blaze_server, build_label);
  }
}

int Main(int argc, const char *const *argv, WorkspaceLayout *workspace_layout,
         OptionProcessor *option_processor, uint64_t start_time) {
  blaze_util::InitializeStdOutErrForUtf8();

  // Logging must be set first to assure no log statements are missed.
  std::unique_ptr<blaze_util::BazelLogHandler> default_handler(
      new blaze_util::BazelLogHandler());
  blaze_util::SetLogHandler(std::move(default_handler));

  const string self_path = GetSelfPath(argv[0]);

  if (argc == 2 && strcmp(argv[1], "leaf") == 0) {
    PrintBazelLeaf();
    return blaze_exit_code::SUCCESS;
  }

  if (argc == 2 && strcmp(argv[1], "--version") == 0) {
    PrintVersionInfo(self_path, option_processor->GetLowercaseProductName());
    return blaze_exit_code::SUCCESS;
  }

  string cwd = GetCanonicalCwd();
  LoggingInfo logging_info(CheckAndGetBinaryPath(cwd, argv[0]), start_time);

  blaze::SetupStdStreams();
  if (argc == 1 && blaze::WarnIfStartedFromDesktop()) {
    // Only check and warn for from-desktop start if there were no args.
    // In this case the user probably clicked Bazel's icon (as opposed to either
    // starting it from a terminal, or as a subprocess with args, or on Windows
    // from a ".lnk" file with some args).
    return blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR;
  }

  // Best-effort operation to raise the resource limits from soft to hard.  We
  // do this early during the main program instead of just before execing the
  // Blaze server binary, because it's easier (for testing purposes) and because
  // the Blaze client also benefits from this (e.g. during installation).
  UnlimitResources();

#if defined(_WIN32) || defined(__CYGWIN__)
  // Must be done before command line parsing.
  // ParseOptionsOrDie already populate --client_env, so detect bash before it
  // happens.
  (void)DetectBashAndExportBazelSh();
#endif  // if defined(_WIN32) || defined(__CYGWIN__)

  if (blaze::IsRunningWithinTest()) {
    BAZEL_LOG(USER) << "$TEST_TMPDIR defined, some defaults will be overridden";
  }

  const string workspace = workspace_layout->GetWorkspace(cwd);
  ParseOptionsOrDie(cwd, workspace, *option_processor, argc, argv);
  StartupOptions *startup_options = option_processor->GetParsedStartupOptions();
  startup_options->MaybeLogStartupOptionWarnings();

  if (startup_options->client_debug) {
    SetDebugLog(blaze_util::LOGGINGDETAIL_DEBUG);
  } else if (startup_options->quiet) {
    SetDebugLog(blaze_util::LOGGINGDETAIL_QUIET);
  } else {
    SetDebugLog(blaze_util::LOGGINGDETAIL_USER);
  }

  // If client_debug was false, this is ignored, so it's accurate.
  BAZEL_LOG(INFO) << "Debug logging requested, sending all client log "
                     "statements to stderr";

  BAZEL_LOG(INFO) << "Running (pid=" << GetProcessIdAsString() << ")";

  if (startup_options->unlimit_coredumps) {
    UnlimitCoredumps();
  }

  // Only start a server when in a workspace because otherwise we won't do more
  // than emit a help message.
  if (!workspace_layout->InWorkspace(workspace)) {
    startup_options->batch = true;
    BAZEL_LOG(WARNING) << "Invoking " << startup_options->product_name
                       << " in batch mode since it is not invoked from within"
                       << " a workspace (below a directory having a"
                       << " MODULE.bazel file).";
  }

  vector<string> archive_contents;
  string install_md5;
  DetermineArchiveContents(self_path, &archive_contents, &install_md5);

  startup_options->UpdateConfiguration(
      install_md5, workspace, IsServerMode(option_processor->GetCommand()));

  PrepareDirectories(startup_options);

  RunLauncher(self_path, archive_contents, install_md5, *startup_options,
              *option_processor, *workspace_layout, workspace, &logging_info);
  return 0;
}

BlazeServer::BlazeServer(const StartupOptions &startup_options)
    : process_info_(startup_options.output_base,
                    startup_options.server_jvm_out),
      connect_timeout_secs_(startup_options.connect_timeout_secs),
      batch_(startup_options.batch),
      block_for_lock_(startup_options.block_for_lock),
      quiet_(startup_options.quiet),
      preemptible_(startup_options.preemptible),
      lock_install_base_(startup_options.lock_install_base),
      install_base_(startup_options.install_base),
      output_base_(startup_options.output_base) {
  pipe_.reset(blaze_util::CreatePipe());
  if (!pipe_) {
    BAZEL_DIE(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR)
        << "Couldn't create pipe: " << GetLastErrorString();
  }
}

bool BlazeServer::TryConnect(CommandServer::Stub *client) {
  grpc::ClientContext context;
  context.set_deadline(std::chrono::system_clock::now() +
                       std::chrono::seconds(connect_timeout_secs_));

  command_server::PingRequest request;
  command_server::PingResponse response;
  request.set_cookie(request_cookie_);

  BAZEL_LOG(INFO) << "Trying to connect to server (timeout: "
                  << connect_timeout_secs_ << " secs)...";
  grpc::Status status = client->Ping(&context, request, &response);

  if (!status.ok() || response.cookie() != response_cookie_) {
    BAZEL_LOG(INFO) << "Connection to server failed: (" << status.error_code()
                    << ") " << status.error_message().c_str() << "\n";
    return false;
  }

  return true;
}

bool BlazeServer::Connect() {
  assert(!Connected());

  blaze_util::Path server_dir = output_base_.GetRelative("server");

  command_server::ServerInfo server_info;
  std::string bytes;
  if (!blaze_util::ReadFile(server_dir.GetRelative("server_info.rawproto"),
                            &bytes) ||
      !server_info.ParseFromString(bytes)) {
    return false;
  }

  const std::string port = server_info.address();
  const std::string ipv4_prefix = "127.0.0.1:";
  const std::string ipv6_prefix_1 = "[0:0:0:0:0:0:0:1]:";
  const std::string ipv6_prefix_2 = "[::1]:";

  // Make sure that we are being directed to localhost
  if (port.compare(0, ipv4_prefix.size(), ipv4_prefix) &&
      port.compare(0, ipv6_prefix_1.size(), ipv6_prefix_1) &&
      port.compare(0, ipv6_prefix_2.size(), ipv6_prefix_2)) {
    return false;
  }

  request_cookie_ = server_info.request_cookie();
  response_cookie_ = server_info.response_cookie();

  const pid_t server_pid = server_info.pid();
  if (server_pid < 0) {
    return false;
  }

  if (!VerifyServerProcess(server_pid, output_base_)) {
    return false;
  }

  grpc::ChannelArguments channel_args;
  // Bazel client and server always run on the same machine and communicate
  // locally over gRPC; so we want to ignore any configured proxies when setting
  // up a gRPC channel to the server.
  channel_args.SetInt(GRPC_ARG_ENABLE_HTTP_PROXY, 0);
  std::shared_ptr<grpc::Channel> channel(grpc::CreateCustomChannel(
      port, grpc::InsecureChannelCredentials(), channel_args));
  std::unique_ptr<CommandServer::Stub> client(CommandServer::NewStub(channel));

  if (!TryConnect(client.get())) {
    return false;
  }

  this->client_ = std::move(client);
  process_info_.server_pid_ = server_pid;
  return true;
}

// Cancellation works as follows:
//
// When the user presses Ctrl-C, a SIGINT is delivered to the client, which is
// translated into a BlazeServer::Cancel() call. Since it's not a good idea to
// do significant work in signal handlers, all it does is write a byte to an
// unnamed pipe.
//
// This unnamed pipe is used to communicate with the cancel thread. Whenever
// something interesting happens, a byte is written into it, which is read by
// the cancel thread. These commands are available:
//
// - NOP
// - JOIN. The cancel thread needs to be terminated.
// - CANCEL. If the command ID is already available, a cancel request is sent.
// - COMMAND_ID_RECEIVED. The client learned the command ID from the server.
//   If there is a pending cancellation request, it is acted upon.
//
// The only data the cancellation thread shares with the main thread is the
// file descriptor for receiving commands and command_id_, the latter of which
// is protected by a mutex, which mainly serves as a memory fence.
//
// The cancellation thread is joined at the end of the execution of the command.
// The main thread wakes it up just so that it can finish (using the JOIN
// action)
//
// It's conceivable that the server is busy and thus it cannot service the
// cancellation request. In that case, we simply ignore the failure and the both
// the server and the client go on as if nothing had happened (except that this
// Ctrl-C still counts as a SIGINT, three of which result in a SIGKILL being
// delivered to the server)
void BlazeServer::CancelThread() {
  bool running = true;
  bool cancel = false;
  bool command_id_received = false;
  while (running) {
    char buf;

    int error;
    int bytes_read = pipe_->Receive(&buf, 1, &error);
    if (bytes_read < 0 && error == blaze_util::IPipe::INTERRUPTED) {
      continue;
    } else if (bytes_read != 1) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "Cannot communicate with cancel thread: " << GetLastErrorString();
    }

    switch (buf) {
      case CancelThreadAction::NOTHING:
        break;

      case CancelThreadAction::JOIN:
        running = false;
        break;

      case CancelThreadAction::COMMAND_ID_RECEIVED:
        command_id_received = true;
        if (cancel) {
          SendCancelMessage();
          cancel = false;
        }
        break;

      case CancelThreadAction::CANCEL:
        if (command_id_received) {
          SendCancelMessage();
        } else {
          cancel = true;
        }
        break;
    }
  }
}

void BlazeServer::SendCancelMessage() {
  std::unique_lock<std::mutex> lock(cancel_thread_mutex_);

  command_server::CancelRequest request;
  request.set_cookie(request_cookie_);
  request.set_command_id(command_id_);
  grpc::ClientContext context;
  context.set_deadline(std::chrono::system_clock::now() +
                       std::chrono::seconds(10));
  command_server::CancelResponse response;
  // There isn't a lot we can do if this request fails
  grpc::Status status = client_->Cancel(&context, request, &response);
  if (!status.ok()) {
    BAZEL_LOG(USER) << "\nCould not interrupt server: (" << status.error_code()
                    << ") " << status.error_message().c_str() << "\n";
  }
}

// This will wait indefinitely until the server shuts down
void BlazeServer::KillRunningServer() {
  assert(Connected());

  std::unique_ptr<grpc::ClientContext> context(new grpc::ClientContext);
  command_server::RunRequest request;
  command_server::RunResponse response;
  request.set_cookie(request_cookie_);
  request.set_block_for_lock(block_for_lock_);
  request.set_client_description("pid=" + blaze::GetProcessIdAsString() +
                                 " (for shutdown)");
  request.add_arg("shutdown");
  BAZEL_LOG(INFO) << "Shutting running server with RPC request";
  std::unique_ptr<grpc::ClientReader<command_server::RunResponse>> reader(
      client_->Run(context.get(), request));

  // TODO(b/111179585): Swallowing these responses loses potential messages from
  // the server, which may be useful in understanding why a shutdown failed.
  // However, we don't want to spam the user in case the shutdown works
  // perfectly fine, so we discard the information. For --noblock_for_lock, this
  // means that we don't output the PID of the competing client, which isn't
  // great. We could either store the stderr_output returned by the server and
  // output it in the case of a failed shutdown, or we could add a
  // special-cased field in RunResponse for this purpose.
  while (reader->Read(&response)) {
  }

  grpc::Status status = reader->Finish();
  reader.reset();
  context.reset();  // necessary for destroying client_ below to be effective
  if (status.ok()) {
    // Check the final message from the server to see if it exited because
    // another command holds the client lock.
    if (response.finished()) {
      if (response.exit_code() == blaze_exit_code::LOCK_HELD_NOBLOCK_FOR_LOCK) {
        assert(!block_for_lock_);
        BAZEL_DIE(blaze_exit_code::LOCK_HELD_NOBLOCK_FOR_LOCK)
            << "Exiting because the lock is held and --noblock_for_lock was "
               "given.";
      }
    }

    // If for any reason the shutdown request failed to initiate a termination,
    // this is a bug. Yes, this means the server won't be forced to shut down,
    // which might be the preferred behavior, but it will help identify the bug.
    assert(response.termination_expected());
  }

  // Eagerly disconnect to let the server stop promptly.  Otherwise it may
  // wait $GRPC_CLIENT_CHANNEL_BACKUP_POLL_INTERVAL_MS until we go away.
  // See http://b/143860035.
  client_.reset();

  // Wait for the server process to terminate (if we know the server PID).
  // If it does not terminate itself gracefully within 1m, terminate it.
  if (process_info_.server_pid_ > 0 &&
      !AwaitServerProcessTermination(process_info_.server_pid_, output_base_,
                                     kPostShutdownGracePeriodSeconds)) {
    if (!status.ok()) {
      BAZEL_LOG(WARNING)
          << "Shutdown request failed, server still alive: (error code: "
          << status.error_code() << ", error message: '"
          << status.error_message() << "', log file: '"
          << process_info_.jvm_log_file_.AsPrintablePath() << "')";
    }
    KillServerProcess(process_info_.server_pid_, output_base_);
  }
}

unsigned int BlazeServer::Communicate(
    const string &command, const vector<string> &command_args,
    const string &invocation_policy,
    const vector<RcStartupFlag> &original_startup_options,
    const LoggingInfo &logging_info,
    const DurationMillis client_startup_duration,
    const std::optional<DurationMillis> extract_data_duration,
    const std::optional<DurationMillis> command_wait_duration) {
  assert(Connected());
  assert(process_info_.server_pid_ > 0);

  vector<string> arg_vector;
  if (!command.empty()) {
    arg_vector.push_back(command);
    AddLoggingArgs(logging_info, client_startup_duration, extract_data_duration,
                   command_wait_duration, &arg_vector);
  }

  arg_vector.insert(arg_vector.end(), command_args.begin(), command_args.end());

  command_server::RunRequest request;
  request.set_cookie(request_cookie_);
  request.set_block_for_lock(block_for_lock_);
  request.set_quiet(quiet_);
  request.set_preemptible(preemptible_);
  request.set_client_description("pid=" + blaze::GetProcessIdAsString());
  for (const string &arg : arg_vector) {
    request.add_arg(arg);
  }
  if (!invocation_policy.empty()) {
    request.set_invocation_policy(invocation_policy);
  }

  for (const auto &startup_option : original_startup_options) {
    command_server::StartupOption *proto_option_field =
        request.add_startup_options();
    request.add_startup_options();
    proto_option_field->set_source(startup_option.source);
    proto_option_field->set_option(startup_option.value);
  }

  std::unique_ptr<grpc::ClientContext> context(new grpc::ClientContext);
  command_server::RunResponse response;
  std::unique_ptr<grpc::ClientReader<command_server::RunResponse>> reader(
      client_->Run(context.get(), request));

  // Release the client-side locks, as the server may outlive the client and
  // must implement its own locking of the install and output bases.
  // This may result in two "waiting for lock" messages, one emitted by client
  // during server startup, and another emitted by the server. This is harmless.
  BAZEL_LOG(INFO)
      << "Released the client-side locks on the install and output bases";
  ReleaseLocks();

  std::thread cancel_thread(&BlazeServer::CancelThread, this);
  bool command_id_set = false;
  bool pipe_broken = false;
  command_server::RunResponse final_response;
  bool finished = false;
  bool finished_warning_emitted = false;

  while (reader->Read(&response)) {
    if (finished && !finished_warning_emitted) {
      BAZEL_LOG(USER) << "\nServer returned messages after reporting exit code";
      finished_warning_emitted = true;
    }

    if (response.cookie() != response_cookie_) {
      BAZEL_LOG(USER) << "\nServer response cookie invalid, exiting";
      return blaze_exit_code::INTERNAL_ERROR;
    }

    const char *broken_pipe_name = nullptr;

    if (response.finished()) {
      final_response = response;
      finished = true;
    }

    if (!response.standard_output().empty()) {
      size_t size = response.standard_output().size();
      if (blaze_util::WriteToStdOutErr(response.standard_output().c_str(), size,
                                       /* to_stdout */ true) ==
          blaze_util::WriteResult::BROKEN_PIPE) {
        broken_pipe_name = "standard output";
      }
    }

    if (!response.standard_error().empty()) {
      size_t size = response.standard_error().size();
      if (blaze_util::WriteToStdOutErr(response.standard_error().c_str(), size,
                                       /* to_stdout */ false) ==
          blaze_util::WriteResult::BROKEN_PIPE) {
        broken_pipe_name = "standard error";
      }
    }

    if (broken_pipe_name != nullptr && !pipe_broken) {
      pipe_broken = true;
      BAZEL_LOG(USER) << "\nCannot write to " << broken_pipe_name
                      << "; exiting...\n";
      Cancel();
    }

    if (!command_id_set && !response.command_id().empty()) {
      std::unique_lock<std::mutex> lock(cancel_thread_mutex_);
      command_id_ = response.command_id();
      command_id_set = true;
      SendAction(CancelThreadAction::COMMAND_ID_RECEIVED);
    }
  }

  grpc::Status status = reader->Finish();
  reader.reset();
  context.reset();  // necessary for destroying client_ below to be effective

  // If the server claims it is shutting down (eg the command was "shutdown"),
  // wait for it to exit.
  if (final_response.termination_expected()) {
    // Eagerly disconnect to let the server stop promptly.  Otherwise it may
    // wait $GRPC_CLIENT_CHANNEL_BACKUP_POLL_INTERVAL_MS until we go away.
    // See http://b/143860035.
    client_.reset();
    if (!AwaitServerProcessTermination(process_info_.server_pid_, output_base_,
                                       kPostShutdownGracePeriodSeconds)) {
      KillServerProcess(process_info_.server_pid_, output_base_);
    }
  }

  SendAction(CancelThreadAction::JOIN);
  cancel_thread.join();

  if (!status.ok()) {
    BAZEL_LOG(USER) << "\nServer terminated abruptly (error code: "
                    << status.error_code() << ", error message: '"
                    << status.error_message() << "', log file: '"
                    << process_info_.jvm_log_file_.AsPrintablePath() << "')\n";
    return GetExitCodeForAbruptExit(output_base_);
  } else if (!finished) {
    BAZEL_LOG(USER)
        << "\nServer finished RPC without an explicit exit code (log file: '"
        << process_info_.jvm_log_file_.AsPrintablePath() << "')\n";
    return GetExitCodeForAbruptExit(output_base_);
  } else if (final_response.has_exec_request() &&
             final_response.exec_request().should_exec()) {
    const command_server::ExecRequest &request = final_response.exec_request();
    if (request.argv_size() < 1) {
      BAZEL_LOG(USER)
          << "\nServer requested exec() but did not pass a binary to execute\n";
      return blaze_exit_code::INTERNAL_ERROR;
    }

    // Clear environment variables before setting the requested ones so that
    // users can still explicitly override the clearing.
    for (const auto &variable_name : request.environment_variable_to_clear()) {
      UnsetEnv(variable_name);
    }

    vector<string> argv(request.argv().begin(), request.argv().end());
    for (const auto &variable : request.environment_variable()) {
      SetEnv(variable.name(), variable.value());
    }

    if (!blaze_util::ChangeDirectory(request.working_directory())) {
      BAZEL_DIE(blaze_exit_code::INTERNAL_ERROR)
          << "changing directory into " << request.working_directory()
          << " failed: " << GetLastErrorString();
    }

    // Execute the requested program, but before doing so, flush everything
    // we still have to say.
    fflush(nullptr);
    ExecuteRunRequest(blaze_util::Path(request.argv(0)), argv);
  }

  if (final_response.has_failure_detail()) {
    BAZEL_LOG(INFO) << "failure_detail: "
                    << final_response.failure_detail().DebugString();
  }

  // We'll exit with exit code SIGPIPE on Unixes due to PropagateSignalOnExit()
  return pipe_broken ? blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR
                     : final_response.exit_code();
}

void BlazeServer::SendAction(CancelThreadAction action) {
  char msg = action;
  if (!pipe_->Send(&msg, 1)) {
    blaze::SigPrintf(
        "\nCould not interrupt server (cannot write to client pipe)\n\n");
  }
}

void BlazeServer::Cancel() {
  assert(Connected());
  SendAction(CancelThreadAction::CANCEL);
}

}  // namespace blaze
