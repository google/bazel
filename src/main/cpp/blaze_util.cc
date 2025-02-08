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

#include "src/main/cpp/blaze_util.h"

#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <cassert>
#include <iostream>

#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/numbers.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/port.h"
#include "src/main/cpp/util/strings.h"

#include "absl/base/log_severity.h"
#include "absl/log/globals.h"
#include "absl/log/initialize.h"

namespace blaze {

using std::map;
using std::string;
using std::vector;

const char kServerPidFile[] = "server.pid.txt";

const unsigned int kPostShutdownGracePeriodSeconds = 60;

const unsigned int kPostKillGracePeriodSeconds = 10;

const char* GetUnaryOption(const char *arg,
                           const char *next_arg,
                           const char *key) {
  const char *value = blaze_util::var_strprefix(arg, key);
  if (value == nullptr) {
    return nullptr;
  } else if (value[0] == '=') {
    return value + 1;
  } else if (value[0]) {
    return nullptr;  // trailing garbage in key name
  }

  return next_arg;
}

bool GetNullaryOption(const char *arg, const char *key) {
  const char *value = blaze_util::var_strprefix(arg, key);
  if (value == nullptr) {
    return false;
  } else if (value[0] == '=') {
    BAZEL_DIE(blaze_exit_code::BAD_ARGV)
        << "In argument '" << arg << "': option '" << key
        << "' does not take a value.";
  } else if (value[0]) {
    return false;  // trailing garbage in key name
  }

  return true;
}

std::vector<std::string> GetAllUnaryOptionValues(
    const vector<string>& args, const char* key,
    const char* ignore_after_value) {
  vector<std::string> values;
  for (vector<string>::size_type i = 0; i < args.size(); ++i) {
    if (args[i] == "--") {
      // "--" means all remaining args aren't options
      return values;
    }

    const char* next_arg = args[std::min(i + 1, args.size() - 1)].c_str();
    const char* result = GetUnaryOption(args[i].c_str(), next_arg, key);
    if (result != nullptr) {
      // 'key' was found and 'result' has its value.
      values.push_back(result);

      if (ignore_after_value != nullptr &&
          strcmp(result, ignore_after_value) == 0) {
        break;
      }
    }

    // This is a pointer comparison, so equality means that the result must be
    // from the next arg instead of happening to match the value from
    // "--key=<value>" string, in which case we need to advance the index to
    // skip the next arg for later iterations.
    if (result == next_arg) {
      i++;
    }
  }

  return values;
}

bool SearchNullaryOption(const vector<string>& args,
                         const string& flag_name,
                         const bool default_value) {
  const string positive_flag = "--" + flag_name;
  const string negative_flag = "--no" + flag_name;
  bool result = default_value;
  for (vector<string>::size_type i = 0; i < args.size(); i++) {
    if (args[i] == "--") {
      break;
    }
    if (GetNullaryOption(args[i].c_str(), positive_flag.c_str())) {
      result = true;
    } else if (GetNullaryOption(args[i].c_str(), negative_flag.c_str())) {
      result = false;
    }
  }
  return result;
}

bool IsArg(const string& arg) {
  return blaze_util::starts_with(arg, "-") && (arg != "--help")
      && (arg != "-help") && (arg != "-h");
}

std::string AbsolutePathFromFlag(const std::string& value) {
  if (value.empty()) {
    return blaze_util::GetCwd();
  } else if (!value.empty() && value[0] == '~') {
    return blaze_util::JoinPath(GetHomeDir(), value.substr(1));
  } else {
    return blaze_util::MakeAbsolute(value);
  }
}

void LogWait(unsigned int elapsed_seconds, unsigned int wait_seconds) {
  SigPrintf("WARNING: Waiting for server process to terminate "
            "(waited %d seconds, waiting at most %d)\n",
            elapsed_seconds, wait_seconds);
}

bool AwaitServerProcessTermination(int pid, const blaze_util::Path& output_base,
                                   unsigned int wait_seconds) {
  uint64_t st = GetMillisecondsMonotonic();
  const unsigned int first_seconds = 5;
  bool logged_first = false;
  const unsigned int second_seconds = 10;
  bool logged_second = false;
  const unsigned int third_seconds = 30;
  bool logged_third = false;

  while (VerifyServerProcess(pid, output_base)) {
    TrySleep(100);
    uint64_t elapsed_millis = GetMillisecondsMonotonic() - st;
    if (!logged_first && elapsed_millis > first_seconds * 1000) {
      LogWait(first_seconds, wait_seconds);
      logged_first = true;
    }
    if (!logged_second && elapsed_millis > second_seconds * 1000) {
      LogWait(second_seconds, wait_seconds);
      logged_second = true;
    }
    if (!logged_third && elapsed_millis > third_seconds * 1000) {
      LogWait(third_seconds, wait_seconds);
      logged_third = true;
    }
    if (elapsed_millis > wait_seconds * 1000) {
      SigPrintf("INFO: Waited %d seconds for server process (pid=%d) to"
                " terminate.\n",
                wait_seconds, pid);
      return false;
    }
  }
  return true;
}

void SetDebugLog(blaze_util::LoggingDetail detail) {
  if (detail == blaze_util::LOGGINGDETAIL_DEBUG) {
    blaze_util::SetLoggingDetail(blaze_util::LOGGINGDETAIL_DEBUG, &std::cerr);
    absl::SetStderrThreshold(absl::LogSeverityAtLeast::kInfo);
  } else {
    blaze_util::SetLoggingDetail(detail, nullptr);

    // Disable absl debug logging, since that gets printed to stderr due to us
    // not setting up a log file. We don't use absl but one of our dependencies
    // might (as of 2024Q2, gRPC does).
    //
    // Future improvements to this approach:
    // * Disable absl logging ASAP, not just here after handling
    //   --client_debug=false.
    // * Use the same approach for handling --client_debug=true that we do for
    //   BAZEL_LOG of first redirecting all messages to an inmemory string, and
    //   then writing that string to stderr. We could use a absl::LogSink to
    //   achieve this.
    absl::InitializeLog();
    absl::SetStderrThreshold(absl::LogSeverityAtLeast::kInfinity);
  }
}

bool IsRunningWithinTest() { return ExistsEnv("TEST_TMPDIR"); }

blaze_util::Path GetOOMFilePath(const blaze_util::Path& output_base) {
  return output_base.GetRelative("blaze_oomed");
}

blaze_util::Path GetAbruptExitFilePath(const blaze_util::Path& output_base) {
  // It would make more sense for this file to be in the "server" subdirectory,
  // but changing that would require migrating invokers of Blaze.
  return output_base.GetRelative("exit_code_to_use_on_abrupt_exit");
}

void WithEnvVars::SetEnvVars(const map<string, EnvVarValue>& vars) {
  for (const auto& var : vars) {
    switch (var.second.action) {
      case EnvVarAction::UNSET:
        UnsetEnv(var.first);
        break;

      case EnvVarAction::SET:
        SetEnv(var.first, var.second.value);
        break;

      default:
        assert(false);
    }
  }
}

WithEnvVars::WithEnvVars(const map<string, EnvVarValue>& vars) {
  for (const auto& v : vars) {
    if (ExistsEnv(v.first)) {
      _old_values[v.first] = EnvVarValue(EnvVarAction::SET, GetEnv(v.first));
    } else {
      _old_values[v.first] = EnvVarValue(EnvVarAction::UNSET, "");
    }
  }

  SetEnvVars(vars);
}

WithEnvVars::~WithEnvVars() {
  SetEnvVars(_old_values);
}

}  // namespace blaze
