// Copyright 2017 The Bazel Authors. All rights reserved.
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

#ifndef STRICT
#define STRICT
#endif

#ifndef NOMINMAX
#define NOMINMAX
#endif

#ifndef UNICODE
#define UNICODE
#endif

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#include <processenv.h>
#include <shellapi.h>
#include <winbase.h>

#include <memory>
#include <string>

#include "src/tools/launcher/bash_launcher.h"
#include "src/tools/launcher/java_launcher.h"
#include "src/tools/launcher/launcher.h"
#include "src/tools/launcher/python_launcher.h"
#include "src/tools/launcher/util/data_parser.h"
#include "src/tools/launcher/util/launcher_util.h"

static constexpr const char* BINARY_TYPE = "binary_type";

using bazel::launcher::BashBinaryLauncher;
using bazel::launcher::BinaryLauncherBase;
using bazel::launcher::die;
using bazel::launcher::GetBinaryPathWithExtension;
using bazel::launcher::GetWindowsLongPath;
using bazel::launcher::JavaBinaryLauncher;
using bazel::launcher::LaunchDataParser;
using bazel::launcher::PythonBinaryLauncher;
using std::make_unique;
using std::unique_ptr;

static std::wstring GetExecutableFileName() {
  // https://docs.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation
  constexpr std::wstring::size_type maximum_file_name_length = 0x8000;
  std::wstring buffer(maximum_file_name_length, L'\0');
  DWORD length = GetModuleFileNameW(nullptr, &buffer.front(), buffer.size());
  if (length == 0 || length >= buffer.size()) {
    die(L"Failed to obtain executable filename");
  }
  return buffer.substr(0, length);
}

#if defined(__MINGW32__) && not defined(BAZEL_MINGW_UNICODE)
// MinGW requires linkopt=-municode to use wmain as entry point.
// The below allows fallback to main when BAZEL_MINGW_UNICODE is not defined.
// Otherwise, to use wmain directly, one needs to use both linkopt=-municode and
// copt=-DBAZEL_MINGW_UNICODE.
int wmain(int argc, wchar_t* argv[]);
int main() {
  int argc = 0;
  wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (argv == nullptr) {
    die(L"CommandLineToArgvW failed.");
  }
  int result = wmain(argc, argv);
  LocalFree(argv);
  return result;
}
#endif

int wmain(int argc, wchar_t* argv[]) {
  // In case the given binary path is a shortened Windows 8dot3 path, we convert
  // it back to its long path form so that path manipulations (e.g. appending
  // ".runfiles") work as expected. Note that GetExecutableFileName may return a
  // path different from argv[0].
  const std::wstring launcher_path =
      GetWindowsLongPath(GetExecutableFileName());
  LaunchDataParser::LaunchInfo launch_info;
  if (!LaunchDataParser::GetLaunchInfo(launcher_path, &launch_info)) {
    die(L"Failed to parse launch info.");
  }

  auto result = launch_info.find(BINARY_TYPE);
  if (result == launch_info.end()) {
    die(L"Cannot find key \"%hs\" from launch data.", BINARY_TYPE);
  }

  unique_ptr<BinaryLauncherBase> binary_launcher;

  if (result->second == L"Python") {
    binary_launcher = make_unique<PythonBinaryLauncher>(
        launch_info, launcher_path, argc, argv);
  } else if (result->second == L"Bash") {
    binary_launcher =
        make_unique<BashBinaryLauncher>(launch_info, launcher_path, argc, argv);
  } else if (result->second == L"Java") {
    binary_launcher =
        make_unique<JavaBinaryLauncher>(launch_info, launcher_path, argc, argv);
  } else {
    die(L"Unknown binary type, cannot launch anything.");
  }

  return binary_launcher->Launch();
}
