// Copyright 2024 The Bazel Authors. All rights reserved.
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

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <string>
#include <string_view>

#include "absl/strings/ascii.h"
#include "src/main/cpp/option_processor-internal.h"
#include "src/main/cpp/util/strings.h"

namespace blaze::internal {

#if defined(__CYGWIN__)

static void PreprocessEnvString(std::string* env_str) {
  int pos = env_str->find_first_of('=');
  if (pos == string::npos) {
    return;
  }
  std::string name = env_str->substr(0, pos);
  if (name == "PATH") {
    env_str->assign("PATH=" + env_str->substr(pos + 1));
  } else if (name == "TMP") {
    // A valid Windows path "c:/foo" is also a valid Unix path list of
    // ["c", "/foo"] so must use ConvertPath here. See GitHub issue #1684.
    env_str->assign("TMP=" + blaze_util::ConvertPath(env_str->substr(pos + 1)));
  }
}

#else  // not defined(__CYGWIN__)

static void PreprocessEnvString(std::string* env_str) {
  static constexpr const char* vars_to_uppercase[] = {
      "PATH", "SYSTEMROOT", "SYSTEMDRIVE", "TEMP", "TEMPDIR", "TMP"};

  std::size_t pos = env_str->find_first_of('=');
  if (pos == std::string::npos) {
    return;
  }

  std::string name = absl::AsciiStrToUpper(env_str->substr(0, pos));
  if (std::find(std::begin(vars_to_uppercase), std::end(vars_to_uppercase),
                name) != std::end(vars_to_uppercase)) {
    env_str->assign(name + "=" + env_str->substr(pos + 1));
  }
}

#endif  // defined(__CYGWIN__)

static bool IsValidEnvName(std::string_view s) {
  std::size_t first_equal = s.find('=');
  if (first_equal == 0) {
    // Skip over legacy environment variables that start with '=', e.g. '=C:'
    // These are set by cmd.exe and can't be parsed by the Bazel server if
    // passed into --client_env.
    return false;
  }
  std::string_view name = s.substr(0, first_equal);
  return std::all_of(name.begin(), name.end(), [](char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
           (c >= '0' && c <= '9') || c == '_' || c == '(' || c == ')';
  });
}

// Use GetEnvironmentStringsW to get the environment variables to support
// Unicode regardless of the current code page.
std::vector<std::string> GetProcessedEnv() {
  std::vector<std::string> processed_env;
  wchar_t* env = GetEnvironmentStringsW();
  if (env == nullptr) {
    return processed_env;
  }

  for (wchar_t* p = env; *p != L'\0'; p += wcslen(p) + 1) {
    std::string env_str = blaze_util::WstringToCstring(p);
    if (IsValidEnvName(env_str)) {
      PreprocessEnvString(&env_str);
      processed_env.push_back(std::move(env_str));
    }
  }

  FreeEnvironmentStringsW(env);
  return processed_env;
}

}  // namespace blaze::internal
