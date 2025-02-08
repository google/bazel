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

#include "src/tools/launcher/java_launcher.h"

#include <memory>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/process.h"
#include "src/tools/launcher/util/launcher_util.h"

namespace bazel {
namespace launcher {

using std::getline;
using std::string;
using std::vector;
using std::wofstream;
using std::wostringstream;
using std::wstring;
using std::wstringstream;

// The runfile path of java binary, eg. local_jdk/bin/java.exe
static constexpr const char* JAVA_BIN_PATH = "java_bin_path";
static constexpr const char* JAR_BIN_PATH = "jar_bin_path";
static constexpr const char* CLASSPATH = "classpath";
static constexpr const char* JAVA_START_CLASS = "java_start_class";
static constexpr const char* JVM_FLAGS = "jvm_flags";

// Check if a string start with a certain prefix.
// If it's true, store the substring without the prefix in value.
// If value is quoted, then remove the quotes.
static bool GetFlagValue(const wstring& str, const wstring& prefix,
                         wstring* value_ptr) {
  if (str.compare(0, prefix.length(), prefix)) {
    return false;
  }
  wstring& value = *value_ptr;
  value = str.substr(prefix.length());
  int len = value.length();
  if (len >= 2 && value[0] == L'"' && value[len - 1] == L'"') {
    value = value.substr(1, len - 2);
  }
  return true;
}

// Parses one launcher flag and updates this object's state accordingly.
//
// Returns true if the flag is a valid launcher flag; false otherwise.
bool JavaBinaryLauncher::ProcessWrapperArgument(const wstring& argument) {
  wstring flag_value;
  if (argument.compare(L"--debug") == 0) {
    wstring default_jvm_debug_port;
    if (GetEnv(L"DEFAULT_JVM_DEBUG_PORT", &default_jvm_debug_port)) {
      this->jvm_debug_port = default_jvm_debug_port;
    } else {
      this->jvm_debug_port = L"5005";
    }
  } else if (GetFlagValue(argument, L"--debug=", &flag_value)) {
    this->jvm_debug_port = flag_value;
  } else if (GetFlagValue(argument, L"--main_advice=", &flag_value)) {
    this->main_advice = flag_value;
  } else if (GetFlagValue(argument, L"--main_advice_classpath=", &flag_value)) {
    this->main_advice_classpath = flag_value;
  } else if (GetFlagValue(argument, L"--jvm_flag=", &flag_value)) {
    this->jvm_flags_cmdline.push_back(flag_value);
  } else if (GetFlagValue(argument, L"--jvm_flags=", &flag_value)) {
    wstringstream flag_value_ss(flag_value);
    wstring item;
    while (getline(flag_value_ss, item, L' ')) {
      this->jvm_flags_cmdline.push_back(item);
    }
  } else if (argument.compare(L"--singlejar") == 0) {
    this->singlejar = true;
  } else if (argument.compare(L"--print_javabin") == 0) {
    this->print_javabin = true;
  } else if (GetFlagValue(argument, L"--classpath_limit=", &flag_value)) {
    this->classpath_limit = std::stoi(flag_value);
  } else {
    return false;
  }
  return true;
}

vector<wstring> JavaBinaryLauncher::ProcessesCommandLine() {
  vector<wstring> args;
  bool first = 1;
  for (const auto& arg : this->GetCommandlineArguments()) {
    // Skip the first argument.
    if (first) {
      first = 0;
      continue;
    }
    wstring flag_value;
    // TODO(pcloudy): Should rename this flag to --native_launcher_flag.
    // But keep it as it is for now to be consistent with the shell script
    // launcher.
    if (GetFlagValue(arg, L"--wrapper_script_flag=", &flag_value)) {
      if (!ProcessWrapperArgument(flag_value)) {
        die(L"invalid wrapper argument '%s'", arg.c_str());
      }
    } else if (!args.empty() || !ProcessWrapperArgument(arg)) {
      args.push_back(arg);
    }
  }
  return args;
}

// Return an absolute normalized path for the directory of manifest jar
static wstring GetManifestJarDir(const wstring& binary_base_path) {
  wstring abs_manifest_jar_dir;
  std::size_t slash = binary_base_path.find_last_of(L"/\\");
  if (slash == wstring::npos) {
    abs_manifest_jar_dir = L"";
  } else {
    abs_manifest_jar_dir = binary_base_path.substr(0, slash);
  }
  if (!blaze_util::IsAbsolute(binary_base_path)) {
    abs_manifest_jar_dir = blaze_util::GetCwdW() + L"\\" + abs_manifest_jar_dir;
  }
  wstring result;
  if (!NormalizePath(abs_manifest_jar_dir, &result)) {
    die(L"GetManifestJarDir Failed");
  }
  return result;
}

static void WriteJarClasspath(const wstring& jar_path,
                              wostringstream* manifest_classpath) {
  *manifest_classpath << L' ';
  for (const auto& x : jar_path) {
    if (isalnum(x) || x == L'.' || x == L'-' || x == L'_' || x == L'~') {
      *manifest_classpath << x;
    } else if (x == L'\\') {
      *manifest_classpath << L"/";
    } else {
      // Replace the character with its 2-digit hexadecimal representation.
      char buffer[4];
      snprintf(buffer, sizeof(buffer), "%02X", x);
      *manifest_classpath << L"%" << buffer;
    }
  }
}

wstring JavaBinaryLauncher::GetJunctionBaseDir() {
  wstring binary_base_path = GetBinaryPathWithExtension(GetLauncherPath());
  wstring result;
  if (!NormalizePath(binary_base_path + L".j", &result)) {
    die(L"Failed to get normalized junction base directory.");
  }
  return result;
}

void JavaBinaryLauncher::DeleteJunctionBaseDir() {
  wstring junction_base_dir_norm = GetJunctionBaseDir();
  if (!DoesDirectoryPathExist(junction_base_dir_norm.c_str())) {
    return;
  }
  vector<wstring> junctions;
  blaze_util::GetAllFilesUnderW(junction_base_dir_norm, &junctions);
  for (const auto& junction : junctions) {
    if (!DeleteDirectoryByPath(junction.c_str())) {
      PrintError(L"Failed to delete junction directory: %hs",
                 GetLastErrorString().c_str());
    }
  }
  if (!DeleteDirectoryByPath(junction_base_dir_norm.c_str())) {
    PrintError(L"Failed to delete junction directory: %hs",
               GetLastErrorString().c_str());
  }
}

wstring JavaBinaryLauncher::CreateClasspathJar(const wstring& classpath) {
  wstring binary_base_path = GetBinaryPathWithoutExtension(GetLauncherPath());
  wstring abs_manifest_jar_dir_norm = GetManifestJarDir(binary_base_path);

  wostringstream manifest_classpath;
  manifest_classpath << L"Class-Path:";
  wstringstream classpath_ss(classpath);
  wstring path, path_norm;

  // A set to store all junctions created.
  // The key is the target path, the value is the junction path.
  std::unordered_map<wstring, wstring> jar_dirs;
  wstring junction_base_dir_norm = GetJunctionBaseDir();
  int junction_count = 0;
  // Make sure the junction base directory doesn't exist already.
  DeleteJunctionBaseDir();
  blaze_util::MakeDirectoriesW(junction_base_dir_norm, 0755);

  while (getline(classpath_ss, path, L';')) {
    if (blaze_util::IsAbsolute(path)) {
      if (!NormalizePath(path, &path_norm)) {
        die(L"CreateClasspathJar failed");
      }

      // If two paths are under different drives, we should create a junction to
      // the jar's directory
      if (path_norm[0] != abs_manifest_jar_dir_norm[0]) {
        wstring jar_dir = GetParentDirFromPath(path_norm);
        wstring jar_base_name = GetBaseNameFromPath(path_norm);
        wstring junction;
        auto search = jar_dirs.find(jar_dir);
        if (search == jar_dirs.end()) {
          junction = junction_base_dir_norm + L"\\" +
                     std::to_wstring(junction_count++);

          wstring error;
          if (bazel::windows::CreateJunction(junction, jar_dir, &error) !=
              bazel::windows::CreateJunctionResult::kSuccess) {
            die(L"CreateClasspathJar failed: %s", error.c_str());
          }

          jar_dirs.insert(std::make_pair(jar_dir, junction));
        } else {
          junction = search->second;
        }
        path_norm = junction + L"\\" + jar_base_name;
      }

      if (!RelativeTo(path_norm, abs_manifest_jar_dir_norm, &path)) {
        die(L"CreateClasspathJar failed");
      }
    }
    WriteJarClasspath(path, &manifest_classpath);
  }

  wstring rand_id = L"-" + GetRandomStr(10);
  // Enable long path support for jar_manifest_file_path.
  wstring jar_manifest_file_path =
      binary_base_path + rand_id + L".jar_manifest";
  blaze_util::AddUncPrefixMaybe(&jar_manifest_file_path);
#if (__cplusplus >= 201703L)
  wofstream jar_manifest_file{std::filesystem::path(jar_manifest_file_path)};
#else
  wofstream jar_manifest_file(jar_manifest_file_path);
#endif
  jar_manifest_file << L"Manifest-Version: 1.0\n";
  // No line in the MANIFEST.MF file may be longer than 72 bytes.
  // A space prefix indicates the line is still the content of the last
  // attribute.
  wstring manifest_classpath_str = manifest_classpath.str();
  for (size_t i = 0; i < manifest_classpath_str.length(); i += 71) {
    if (i > 0) {
      jar_manifest_file << L" ";
    }
    jar_manifest_file << manifest_classpath_str.substr(i, 71) << "\n";
  }
  jar_manifest_file.close();
  if (jar_manifest_file.fail()) {
    die(L"Couldn't write jar manifest file: %s",
        jar_manifest_file_path.c_str());
  }

  // Create the command for generating classpath jar.
  wstring manifest_jar_path = binary_base_path + rand_id + L"-classpath.jar";
  wstring jar_bin = this->Rlocation(this->GetLaunchInfoByKey(JAR_BIN_PATH));
  vector<wstring> arguments;
  arguments.push_back(L"cvfm");
  arguments.push_back(manifest_jar_path);
  arguments.push_back(jar_manifest_file_path);

  if (this->LaunchProcess(jar_bin, arguments, /* suppressOutput */ true) != 0) {
    die(L"Couldn't create classpath jar: %s", manifest_jar_path.c_str());
  }

  // Delete jar_manifest_file after classpath jar is created.
  DeleteFileByPath(jar_manifest_file_path.c_str());

  return manifest_jar_path;
}

ExitCode JavaBinaryLauncher::Launch() {
  // Parse the original command line.
  vector<wstring> remaining_args = this->ProcessesCommandLine();

  // Set JAVA_RUNFILES
  wstring java_runfiles;
  if (!GetEnv(L"JAVA_RUNFILES", &java_runfiles)) {
    java_runfiles = this->GetRunfilesPath();
  }
  SetEnv(L"JAVA_RUNFILES", java_runfiles);

  // Print Java binary path if needed
  wstring java_bin = this->Rlocation(this->GetLaunchInfoByKey(JAVA_BIN_PATH),
                                     /*has_workspace_name =*/true);
  if (this->print_javabin ||
      this->GetLaunchInfoByKey(JAVA_START_CLASS) == L"--print_javabin") {
    wprintf(L"%s\n", java_bin.c_str());
    return 0;
  }

  wostringstream classpath;

  // Run deploy jar if needed, otherwise generate the CLASSPATH by rlocation.
  if (this->singlejar) {
    wstring deploy_jar =
        GetBinaryPathWithoutExtension(GetLauncherPath()) + L"_deploy.jar";
    if (!DoesFilePathExist(deploy_jar.c_str())) {
      die(L"Option --singlejar was passed, but %s does not exist.\n  (You may "
          "need to build it explicitly.)",
          deploy_jar.c_str());
    }
    classpath << deploy_jar << L';';
  } else {
    wstring path;
    // Add main advice classpath if exists
    if (!this->main_advice_classpath.empty()) {
      wstringstream main_advice_classpath_ss(this->main_advice_classpath);
      while (getline(main_advice_classpath_ss, path, L';')) {
        classpath << this->Rlocation(path) << L';';
      }
    }
    wstringstream classpath_ss(this->GetLaunchInfoByKey(CLASSPATH));
    while (getline(classpath_ss, path, L';')) {
      classpath << this->Rlocation(path) << L';';
    }
  }

  // Set jvm debug options
  wostringstream jvm_debug_flags;
  if (!this->jvm_debug_port.empty()) {
    wstring jvm_debug_suspend;
    if (!GetEnv(L"DEFAULT_JVM_DEBUG_SUSPEND", &jvm_debug_suspend)) {
      jvm_debug_suspend = L"y";
    }
    jvm_debug_flags << L"-agentlib:jdwp=transport=dt_socket,server=y";
    jvm_debug_flags << L",suspend=" << jvm_debug_suspend;
    jvm_debug_flags << L",address=" << jvm_debug_port;

    wstring value;
    if (GetEnv(L"PERSISTENT_TEST_RUNNER", &value) && value == L"true") {
      jvm_debug_flags << L",quiet=y";
    }
  }

  // Get jvm flags from JVM_FLAGS environment variable and JVM_FLAGS launch info
  vector<wstring> jvm_flags;
  wstring jvm_flags_env;
  GetEnv(L"JVM_FLAGS", &jvm_flags_env);
  wstring flag;
  wstringstream jvm_flags_env_ss(jvm_flags_env);
  while (getline(jvm_flags_env_ss, flag, L' ')) {
    jvm_flags.push_back(flag);
  }
  wstringstream jvm_flags_launch_info_ss(this->GetLaunchInfoByKey(JVM_FLAGS));
  while (getline(jvm_flags_launch_info_ss, flag, L'\t')) {
    jvm_flags.push_back(flag);
  }

  // Check if TEST_TMPDIR is available to use for scratch.
  wstring test_tmpdir;
  if (GetEnv(L"TEST_TMPDIR", &test_tmpdir) &&
      DoesDirectoryPathExist(test_tmpdir.c_str())) {
    jvm_flags.push_back(L"-Djava.io.tmpdir=" + test_tmpdir);
  }

  // Construct the final command line arguments
  vector<wstring> arguments;
  // Add classpath flags
  arguments.push_back(L"-classpath");
  // Check if CLASSPATH is over classpath length limit.
  // If it does, then we create a classpath jar to pass CLASSPATH value.
  wstring classpath_str = classpath.str();
  wstring classpath_jar = L"";
  if (classpath_str.length() > this->classpath_limit) {
    classpath_jar = CreateClasspathJar(classpath_str);
    arguments.push_back(classpath_jar);
  } else {
    arguments.push_back(classpath_str);
  }
  // Add JVM debug flags
  wstring jvm_debug_flags_str = jvm_debug_flags.str();
  if (!jvm_debug_flags_str.empty()) {
    arguments.push_back(jvm_debug_flags_str);
  }
  // Add JVM flags parsed from env and launch info.
  for (const auto& arg : jvm_flags) {
    arguments.push_back(arg);
  }
  // Add JVM flags parsed from command line.
  for (const auto& arg : this->jvm_flags_cmdline) {
    arguments.push_back(arg);
  }
  // Add main advice class
  if (!this->main_advice.empty()) {
    arguments.push_back(this->main_advice);
  }
  // Add java start class
  arguments.push_back(this->GetLaunchInfoByKey(JAVA_START_CLASS));
  // Add the remaininng arguments, they will be passed to the program.
  for (const auto& arg : remaining_args) {
    arguments.push_back(arg);
  }

  vector<wstring> escaped_arguments;
  // Quote the arguments if having spaces
  for (const auto& arg : arguments) {
    escaped_arguments.push_back(bazel::windows::WindowsEscapeArg(arg));
  }

  ExitCode exit_code = this->LaunchProcess(java_bin, escaped_arguments);

  // Delete classpath jar file after execution.
  if (!classpath_jar.empty()) {
    DeleteFileByPath(classpath_jar.c_str());
    DeleteJunctionBaseDir();
  }

  return exit_code;
}

}  // namespace launcher
}  // namespace bazel
