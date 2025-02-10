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
// blaze_util.h: Miscellaneous utility functions used by the blaze.cc
//               Blaze client.
//

#ifndef BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_H_
#define BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_H_

#include <stdint.h>
#include <sys/types.h>

#include <map>
#include <string>
#include <vector>

#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/path_platform.h"

namespace blaze {

extern const char kServerPidFile[];

// If 'arg' matches 'key=value', returns address of 'value'.
// If it matches 'key' alone, returns address of next_arg.
// Returns NULL otherwise.
const char* GetUnaryOption(const char *arg,
                           const char *next_arg,
                           const char *key);

// Returns true iff 'arg' equals 'key'.
// Dies with a syntax error if arg starts with 'key='.
// Returns false otherwise.
bool GetNullaryOption(const char *arg, const char *key);

// Searches for 'key' in 'args' using GetUnaryOption. Arguments found after '--'
// are omitted from the search.
// If 'ignore_after_value' is not nullptr, all values matching the 'key'
// following 'ignore_after_value' will be ignored. Returns the values of the
// 'key' flag iff it occurs in args. Returns empty vector otherwise.
std::vector<std::string> GetAllUnaryOptionValues(
    const std::vector<std::string>& args, const char* key,
    const char* ignore_after_value = nullptr);

// Searches for '--flag_name' and '--noflag_name' in 'args' using
// GetNullaryOption. Arguments found after '--' are omitted from the search.
// Returns true if '--flag_name' is a flag in args and '--noflag_name' does not
// appear after its last occurrence. If neither '--flag_name' nor
// '--noflag_name' appear, returns 'default_value'. Otherwise, returns false.
bool SearchNullaryOption(const std::vector<std::string>& args,
                         const std::string& flag_name,
                         const bool default_value);

// Returns true iff arg is a valid command line argument for bazel.
bool IsArg(const std::string& arg);

// Returns the flag value as an absolute path. For legacy reasons, it accepts
// the empty string as cwd.
// TODO(b/109874628): Assess if removing the empty string case would break
// legitimate uses, and if not, remove it.
std::string AbsolutePathFromFlag(const std::string& value);

// Wait to see if the server process terminates. Checks the server's status
// immediately, and repeats the check every 100ms until approximately
// wait_seconds elapses or the server process terminates. Returns true if a
// check sees that the server process terminated. Logs to stderr after 5, 10,
// and 30 seconds if the wait lasts that long.
bool AwaitServerProcessTermination(int pid, const blaze_util::Path& output_base,
                                   unsigned int wait_seconds);

// The number of seconds the client will wait for the server process to
// terminate itself after the client receives the final response from a command
// that shuts down the server. After waiting this time, if the server process
// remains, the client will forcibly kill the server.
extern const unsigned int kPostShutdownGracePeriodSeconds;

// The number of seconds the client will wait for the server process to
// terminate after the client forcibly kills the server. After waiting this
// time, if the server process remains, the client will die.
extern const unsigned int kPostKillGracePeriodSeconds;

// Control the output of debug information by debug_log.
// Revisit once client logging is fixed (b/32939567).
void SetDebugLog(blaze_util::LoggingDetail detail);

// Returns true if this Bazel instance is running inside of a Bazel test.
// This method observes the TEST_TMPDIR envvar.
bool IsRunningWithinTest();

// Gets the path to use for the file that Blaze's JVM creates to indicate it
// observed an OOM.
blaze_util::Path GetOOMFilePath(const blaze_util::Path& output_base);

// Gets the path to use for the file that Blaze writes before it abruptly exits
// and is unable to convey the exit code to use via the normal gRPC response.
blaze_util::Path GetAbruptExitFilePath(const blaze_util::Path& output_base);

// What WithEnvVar should do with an environment variable
enum EnvVarAction { UNSET, SET };

// What WithEnvVar should do with an environment variable
struct EnvVarValue {
  // What should be done with the given variable
  EnvVarAction action;

  // The value of the variable; ignored if action == UNSET
  std::string value;

  EnvVarValue() {}

  EnvVarValue(EnvVarAction action, const std::string& value)
      : action(action),
        value(value) {}
};

// While this class is in scope, the specified environment variables will be set
// to a specified value (or unset). When it leaves scope, changed variables will
// be set to their original values.
class WithEnvVars {
 private:
  std::map<std::string, EnvVarValue> _old_values;

  void SetEnvVars(const std::map<std::string, EnvVarValue>& vars);

 public:
  WithEnvVars(const std::map<std::string, EnvVarValue>& vars);
  ~WithEnvVars();
};

struct DurationMillis {
 public:
  uint64_t millis;

  DurationMillis() : millis(0) {}

  DurationMillis(const uint64_t start, const uint64_t end)
      : millis(ComputeDuration(start, end)) {}

  DurationMillis& operator+=(DurationMillis& other) {
    millis += other.millis;
    return *this;
  }

 private:
  static uint64_t ComputeDuration(const uint64_t start, const uint64_t end) {
    if (end < start) {
      BAZEL_LOG(WARNING) << "Invalid duration: start=" << start
                         << ", end=" << end;
      return 0;
    }
    return end - start;
  }
};

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_BLAZE_UTIL_H_
