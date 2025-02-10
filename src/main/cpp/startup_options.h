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
#ifndef BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_
#define BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_

#if defined(__APPLE__)
#include <sys/qos.h>
#endif

#include <functional>
#include <map>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/path_platform.h"

namespace blaze {

// A startup flag tagged with its origin, either an rc file or the empty
// string for the ones specified in the command line.
// For instance, RcStartupFlag("somepath/.bazelrc", "--foo") is used to
// represent that the line "startup --foo" was found when parsing
// "somepath/.bazelrc".
struct RcStartupFlag {
  const std::string source;
  const std::string value;
  RcStartupFlag(const std::string &source_arg, const std::string &value_arg)
      : source(source_arg), value(value_arg) {}
};

// This class defines the startup options accepted by all versions Bazel, and
// holds the parsed values. These options and their defaults must be kept in
// sync with those in
// src/main/java/com/google/devtools/build/lib/runtime/BlazeServerStartupOptions.java.
// The latter are (usually) purely decorative (they affect the help message,
// which displays the defaults).  The actual defaults are defined
// in the constructor.
//
// Note that this class is not thread-safe.
//
// TODO(bazel-team): The encapsulation is not quite right -- there are some
// places in blaze.cc where some of these fields are explicitly modified. Their
// names also don't conform to the style guide.
class StartupOptions {
 public:
  virtual ~StartupOptions();

  // Process an ordered list of RcStartupFlags using ProcessArg.
  blaze_exit_code::ExitCode ProcessArgs(
      const std::vector<RcStartupFlag> &rcstartup_flags, std::string *error);

  // Adds any other options needed to result.
  //
  // TODO(jmmv): Now that we support site-specific options via subclasses of
  // StartupOptions, the "ExtraOptions" concept makes no sense; remove it.
  virtual void AddExtraOptions(std::vector<std::string> *result) const;

  // Once startup options have been parsed, warn the user if certain options
  // might combine in surprising ways.
  virtual void MaybeLogStartupOptionWarnings() const = 0;

  // Returns the path to the JVM. This should be called after parsing
  // the startup options.
  virtual blaze_util::Path GetJvm() const;

  // Returns the executable used to start the Blaze server, typically the given
  // JVM.
  virtual blaze_util::Path GetExe(const blaze_util::Path &jvm,
                                  const std::string &jar_path) const;

  // Adds JVM prefix flags to be set. These will be added before all other
  // JVM flags.
  virtual void AddJVMArgumentPrefix(const blaze_util::Path &javabase,
                                    std::vector<std::string> *result) const;

  // Adds JVM suffix flags. These will be added after all other JVM flags, and
  // just before the Blaze server startup flags.
  virtual void AddJVMArgumentSuffix(const blaze_util::Path &real_install_dir,
                                    const std::string &jar_path,
                                    std::vector<std::string> *result) const;

  // Adds JVM tuning flags for Blaze.
  //
  // Returns the exit code after this operation. "error" will be set to a
  // descriptive string for any value other than blaze_exit_code::SUCCESS.
  blaze_exit_code::ExitCode AddJVMArguments(
      const blaze_util::Path &server_javabase, std::vector<std::string> *result,
      const std::vector<std::string> &user_options, std::string *error) const;

  // Checks whether "arg" is a valid nullary option (e.g. "--master_bazelrc" or
  // "--nomaster_bazelrc").
  //
  // Returns true, if "arg" looks like either a valid nullary option or a
  // potentially valid unary option. In this case, "result" will be populated
  // with true iff "arg" is definitely a valid nullary option.
  //
  // Returns false, if "arg" looks like an attempt to pass a value to nullary
  // option (e.g. "--nullary_option=idontknowwhatimdoing"). In this case,
  // "error" will be populated with a user-friendly error message.
  //
  // Therefore, callers of this function should look at the return value and
  // then either look at "result" (on true) or "error" (on false).
  bool MaybeCheckValidNullary(const std::string &arg, bool *result,
                              std::string *error) const;

  // Checks whether the argument is a valid unary option.
  // E.g. --blazerc=foo, --blazerc foo.
  bool IsUnary(const std::string &arg) const;

  std::string GetLowercaseProductName() const;

  // The capitalized name of this binary.
  const std::string product_name;

  // If supplied, alternate location to write the blaze server's jvm's stdout.
  // Otherwise a default path in the output base is used.
  blaze_util::Path server_jvm_out;

  // If supplied, alternate location to write a serialized failure_detail proto.
  // Otherwise a default path in the output base is used.
  blaze_util::Path failure_detail_out;

  // A directory suitable for storing cached files.
  // This contains the default locations of the install and output bases, as
  // well as the repository cache.
  // Defaults to a system-dependent, user-specific directory.
  // See UpdateConfiguration().
  blaze_util::Path output_user_root;

  // The directory where build outputs are placed.
  // Defaults to a <md5(workspace)> subdirectory of output_user_root.
  // See UpdateConfiguration().
  blaze_util::Path output_base;

  // The directory where the decompressed Bazel server is installed.
  // Defaults to an install/<md5(install)> subdirectory of output_user_root.
  // See UpdateConfiguration().
  blaze_util::Path install_base;

  // Whether the install base should be locked before use.
  // Not user-settable, only used for client/server communication.
  // Hardcoded to true for Bazel, false for Blaze.
  bool lock_install_base;

  // Override more finegrained rc file flags and ignore them all.
  bool ignore_all_rc_files;

  // Block for the Blaze server lock. Otherwise,
  // quit with non-0 exit code if lock can't
  // be acquired immediately.
  bool block_for_lock;

  bool host_jvm_debug;

  bool autodetect_server_javabase;

  std::vector<std::string> host_jvm_args;

  bool batch;

  // From the man page: "This policy is useful for workloads that are
  // non-interactive, but do not want to lower their nice value, and for
  // workloads that want a deterministic scheduling policy without
  // interactivity causing extra preemptions (between the workload's tasks)."
  bool batch_cpu_scheduling;

  // If negative, don't mess with ionice. Otherwise, set a level from 0-7
  // for best-effort scheduling. 0 is highest priority, 7 is lowest.
  int io_nice_level;

  int max_idle_secs;

  bool shutdown_on_low_sys_mem;

  bool oom_more_eagerly;

  int oom_more_eagerly_threshold;

  bool write_command_log;

  // No-op.
  // TODO: b/375052752 - Remove this after the 6 month compatibility window ends
  //   in April 2025.
  bool watchfs;

  // Temporary flag for enabling EventBus exceptions to be fatal.
  bool fatal_event_bus_exceptions;

  // A string to string map specifying where each option comes from. If the
  // value is empty, it was on the command line, if it is a string, it comes
  // from a blazerc file, if a key is not present, it is the default.
  std::map<std::string, std::string> option_sources;

  // Returns the embedded JDK, or an empty string.
  blaze_util::Path GetEmbeddedJavabase() const;

  // The source of truth for the server javabase.
  enum class JavabaseType {
    UNKNOWN,
    // An explicit --server_javabase startup option.
    EXPLICIT,
    // The embedded JDK.
    EMBEDDED,
    // The default system JVM.
    SYSTEM
  };

  // Returns the server javabase and its source of truth. This should be called
  // after parsing the --server_javabase option.
  std::pair<blaze_util::Path, JavabaseType> GetServerJavabaseAndType() const;

  // Returns the server javabase. This should be called after parsing the
  // --server_javabase option.
  blaze_util::Path GetServerJavabase() const;

  // Returns the explicit value of the --server_javabase startup option or the
  // empty string if it was not specified on the command line.
  blaze_util::Path GetExplicitServerJavabase() const;

  // Updates the parsed startup options to fill in defaults.
  void UpdateConfiguration(const std::string &install_md5,
                           const std::string &workspace, bool server_mode);

  // Port to start up the gRPC command server on. If 0, let the kernel choose.
  int command_port;

  // Connection timeout for each gRPC connection attempt.
  int connect_timeout_secs;

  // Local server startup timeout duration.
  int local_startup_timeout_secs;

  // Invocation policy proto, or an empty string.
  std::string invocation_policy;
  // Invocation policy can only be specified once.
  bool have_invocation_policy_;

  // Whether to emit as little output as possible.
  bool quiet;

  // Whether to output addition debugging information in the client.
  bool client_debug;

  // Whether the resulting command will be preempted if a subsequent command is
  // run.
  bool preemptible;

  // Value of the java.util.logging.FileHandler.formatter Java property.
  std::string java_logging_formatter;

  // The hash function to use when computing file digests.
  std::string digest_function;

  std::string unix_digest_hash_attribute_name;

  bool idle_server_tasks;

  // The startup options as received from the user and rc files, tagged with
  // their origin. This is populated by ProcessArgs.
  std::vector<RcStartupFlag> original_startup_options_;

#if defined(__APPLE__)
  // The QoS class to apply to the Bazel server process.
  qos_class_t macos_qos_class;
#endif

  // Whether to raise the soft coredump limit to the hard one or not.
  bool unlimit_coredumps;

#ifdef __linux__
  std::string cgroup_parent;

  // If enabled, the Bazel server will be run with systemd-run, and the user
  // will own the cgroup.
  bool run_in_user_cgroup;
#endif

  // Whether to create symbolic links on Windows for files. Requires
  // developer mode to be enabled.
  bool windows_enable_symlinks;

 protected:
  // Constructor for subclasses only so that site-specific extensions of this
  // class can override the product name. The product_name must be capitalized,
  // as in "Bazel".
  StartupOptions(const std::string &product_name, bool lock_install_base);

  // Returns the default output root location.
  virtual blaze_util::Path GetDefaultOutputRoot() const = 0;

  // Checks extra fields when processing arg.
  //
  // Returns the exit code after processing the argument. "error" will
  // contain a descriptive string for any return value other than
  // blaze_exit_code::SUCCESS.
  //
  // TODO(jmmv): Now that we support site-specific options via subclasses of
  // StartupOptions, the "ExtraOptions" concept makes no sense; remove it.
  virtual blaze_exit_code::ExitCode ProcessArgExtra(
      const char *arg, const char *next_arg, const std::string &rcfile,
      const char **value, bool *is_processed, std::string *error) = 0;

  // Checks whether the given javabase contains a java executable and runtime.
  // On success, returns blaze_exit_code::SUCCESS. On error, prints an error
  // message and returns an appropriate exit code with which the client should
  // terminate.
  blaze_exit_code::ExitCode SanityCheckJavabase(
      const blaze_util::Path &javabase,
      StartupOptions::JavabaseType javabase_type) const;

  // Returns the absolute path to the user's local JDK install, to be used as
  // the default target javabase and as a fall-back host_javabase. This is not
  // the embedded JDK.
  virtual blaze_util::Path GetSystemJavabase() const;

  // Adds JVM logging-related flags for Bazel.
  //
  // This is called by StartupOptions::AddJVMArguments and is a separate method
  // so that subclasses of StartupOptions can override it.
  virtual void AddJVMLoggingArguments(std::vector<std::string> *result) const;

  // Adds JVM memory tuning flags for Bazel.
  //
  // This is called by StartupOptions::AddJVMArguments and is a separate method
  // so that subclasses of StartupOptions can override it.
  virtual blaze_exit_code::ExitCode AddJVMMemoryArguments(
      const blaze_util::Path &server_javabase, std::vector<std::string> *result,
      const std::vector<std::string> &user_options, std::string *error) const;

  virtual std::string GetRcFileBaseName() const = 0;

  void RegisterUnaryStartupFlag(const std::string &flag_name);

  // Register a nullary startup flag.
  // Both '--flag_name' and '--noflag_name' will be registered as valid nullary
  // flags. 'value' is the pointer to the boolean that will receive the flag's
  // value.
  void RegisterNullaryStartupFlag(const std::string &flag_name, bool *value);

  // Same as RegisterNullaryStartupFlag, but these flags are forbidden in
  // .bazelrc files.
  void RegisterNullaryStartupFlagNoRc(const std::string &flag_name,
                                      bool *value);

  typedef std::function<void(bool)> SpecialNullaryFlagHandler;

  void RegisterSpecialNullaryStartupFlag(const std::string &flag_name,
                                         SpecialNullaryFlagHandler handler);

  // Override the flag name to use in the 'option_sources' map.
  void OverrideOptionSourcesKey(const std::string &flag_name,
                                const std::string &new_name);

 private:
  // Prevent copying and moving the object to avoid invalidating pointers to
  // members (in all_nullary_startup_flags_ for example).
  StartupOptions() = delete;
  StartupOptions(const StartupOptions &) = delete;
  StartupOptions &operator=(const StartupOptions &) = delete;
  StartupOptions(StartupOptions &&) = delete;
  StartupOptions &operator=(StartupOptions &&) = delete;

  // Parses a single argument, either from the command line or from the .blazerc
  // "startup" options.
  //
  // rcfile should be an empty string if the option being parsed does not come
  // from a blazerc.
  //
  // Sets "is_space_separated" true if arg is unary and uses the "--foo bar"
  // style, so its value is in next_arg.
  //
  // Sets "is_space_separated" false if arg is either nullary
  // (e.g. "--[no]batch") or is unary but uses the "--foo=bar" style.
  //
  // Returns the exit code after processing the argument. "error" will contain
  // a descriptive string for any return value other than
  // blaze_exit_code::SUCCESS.
  blaze_exit_code::ExitCode ProcessArg(const std::string &arg,
                                       const std::string &next_arg,
                                       const std::string &rcfile,
                                       bool *is_space_separated,
                                       std::string *error);

  // The server javabase as provided on the commandline.
  blaze_util::Path explicit_server_javabase_;

  // The default server javabase to be used and its source of truth (computed
  // lazily). Not guarded by a mutex - StartupOptions is not thread-safe.
  mutable std::pair<blaze_util::Path, JavabaseType> default_server_javabase_;

  // Startup flags that don't expect a value, e.g. "home_rc".
  // Valid uses are "--home_rc" are "--nohome_rc".
  // Keys are positive and negative flag names (e.g. "--home_rc" and
  // "--nohome_rc"), values are pointers to the boolean to mutate.
  std::unordered_map<std::string, bool *> all_nullary_startup_flags_;

  // Subset of 'all_nullary_startup_flags_'.
  // Contains positive and negative names (e.g. "--home_rc" and
  // "--nohome_rc") of flags that must not appear in .bazelrc files.
  std::unordered_set<std::string> no_rc_nullary_startup_flags_;

  // Subset of 'all_nullary_startup_flags_'.
  // Contains positive and negative names (e.g. "--home_rc" and
  // "--nohome_rc") of flags that have a special handler.
  // Can be used for tri-state flags where omitting the flag completely means
  // leaving the tri-state as "auto".
  std::unordered_map<std::string, SpecialNullaryFlagHandler>
      special_nullary_startup_flags_;

  // Startup flags that expect a value, e.g. "bazelrc".
  // Valid uses are "--bazelrc=foo" and "--bazelrc foo".
  // Keys are flag names (e.g. "--bazelrc"), values are pointers to the string
  // to mutate.
  std::unordered_set<std::string> valid_unary_startup_flags_;

  // Startup flags that use an alternative key name in the 'option_sources' map.
  // For example, "--[no]master_bazelrc" uses "blazerc" as the map key.
  std::unordered_map<std::string, std::string> option_sources_key_override_;
};

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_STARTUP_OPTIONS_H_
