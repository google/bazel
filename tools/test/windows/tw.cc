// Copyright 2018 The Bazel Authors. All rights reserved.
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

// Test wrapper implementation for Windows.
// Design:
// https://github.com/laszlocsomor/proposals/blob/win-test-runner/designs/2018-07-18-windows-native-test-runner.md

#include "tools/test/windows/tw.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <errno.h>
#include <limits.h>  // INT_MAX
#include <lmcons.h>  // UNLEN
#include <string.h>
#include <sys/types.h>
#include <wchar.h>
#include <windows.h>

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <functional>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "rules_cc/cc/runfiles/runfiles.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/path_platform.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/native/windows/file.h"
#include "src/main/native/windows/process.h"
#include "src/main/native/windows/util.h"
#include "third_party/ijar/common.h"
#include "third_party/ijar/platform_utils.h"
#include "third_party/ijar/zip.h"

namespace bazel {
namespace tools {
namespace test_wrapper {
namespace {

class Defer {
 public:
  explicit Defer(std::function<void()> f) : f_(f) {}
  ~Defer() { f_(); }

  void DoNow() {
    f_();
    f_ = kEmpty;
  }

 private:
  std::function<void()> f_;
  static const std::function<void()> kEmpty;
};

const std::function<void()> Defer::kEmpty = []() {};

// Streams data from an input to two outputs.
// Inspired by tee(1) in the GNU coreutils.
class TeeImpl : Tee {
 public:
  // Creates a background thread to stream data from `input` to the two outputs.
  // The thread terminates when ReadFile fails on the input (e.g. the input is
  // the reading end of a pipe and the writing end is closed) or when WriteFile
  // fails on one of the outputs (e.g. the same output handle is closed
  // elsewhere).
  static bool Create(bazel::windows::AutoHandle* input,
                     bazel::windows::AutoHandle* output1,
                     bazel::windows::AutoHandle* output2,
                     std::unique_ptr<Tee>* result);

 private:
  static DWORD WINAPI ThreadFunc(LPVOID lpParam);

  TeeImpl(bazel::windows::AutoHandle* input,
          bazel::windows::AutoHandle* output1,
          bazel::windows::AutoHandle* output2)
      : input_(input), output1_(output1), output2_(output2) {}
  TeeImpl(const TeeImpl&) = delete;
  TeeImpl& operator=(const TeeImpl&) = delete;

  bool MainFunc() const;

  bazel::windows::AutoHandle input_;
  bazel::windows::AutoHandle output1_;
  bazel::windows::AutoHandle output2_;
};

// Buffered input stream (based on a Windows HANDLE) with peek-ahead support.
//
// This class uses two consecutive "pages" where it buffers data from the
// underlying HANDLE (wrapped in an AutoHandle). Both pages are always loaded
// with data until there's no more data to read.
//
// The "active" page is the one where the read cursor is pointing. The other
// page is the next one to be read once the client moves the read cursor beyond
// the end of the active page.
//
// The client advances the read cursor with Advance(). When the cursor reaches
// the end of the active page, the other page becomes the active one (whose data
// is already buffered), and the old active page is loaded with new data from
// the underlying file.
class IFStreamImpl : IFStream {
 public:
  // Creates a new IFStream.
  //
  // If successful, then takes ownership of the HANDLE in 'handle', and returns
  // a new IFStream pointer. Otherwise leaves 'handle' alone and returns
  // nullptr.
  static IFStream* Create(HANDLE handle, DWORD page_size = 0x100000 /* 1 MB */);

  int Get() override;
  DWORD Peek(DWORD n, uint8_t* out) const override;

 private:
  HANDLE handle_;
  const std::unique_ptr<uint8_t[]> pages_;
  const DWORD page_size_;
  DWORD pos_, end_, next_size_;

  IFStreamImpl(HANDLE handle, std::unique_ptr<uint8_t[]>&& pages, DWORD n,
               DWORD page_size)
      : handle_(handle),
        pages_(std::move(pages)),
        page_size_(page_size),
        pos_(0),
        end_(n < page_size ? n : page_size),
        next_size_(n < page_size
                       ? 0
                       : (n < page_size * 2 ? n - page_size : page_size)) {}
};

// A lightweight path abstraction that stores a Unicode Windows path.
//
// The class allows extracting the underlying path as a (immutable) string so
// it's easy to pass the path to WinAPI functions, but the class does not allow
// mutating the unterlying path so it's safe to pass around Path objects.
class Path {
 public:
  Path() {}
  Path(const Path& other) : path_(other.path_) {}
  Path(Path&& other) : path_(std::move(other.path_)) {}
  Path& operator=(const Path& other) = default;
  const std::wstring& Get() const { return path_; }
  bool Set(const std::wstring& path);

  // Makes this path absolute.
  // Returns true if the path was changed (i.e. was not absolute before).
  // Returns false and has no effect if this path was empty or already absolute.
  bool Absolutize(const Path& cwd);

  Path Dirname() const;

 private:
  std::wstring path_;
};

struct UndeclaredOutputs {
  Path root;
  Path zip;
  Path manifest;
  Path annotations;
  Path annotations_dir;
};

struct Duration {
  static constexpr int kMax = INT_MAX;

  int seconds;

  bool FromString(const wchar_t* str);
};

enum class MainType { kTestWrapperMain, kXmlWriterMain };
enum class DeleteAfterwards { kEnabled, kDisabled };

void WriteStdout(const std::string& s) {
  DWORD written;
  WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), s.c_str(), s.size(), &written,
            nullptr);
}

void LogError(const int line) {
  std::stringstream ss;
  ss << "ERROR(" << __FILE__ << ":" << line << ")" << std::endl;
  WriteStdout(ss.str());
}

void LogError(const int line, const std::string& msg) {
  std::stringstream ss;
  ss << "ERROR(" << __FILE__ << ":" << line << ") " << msg << std::endl;
  WriteStdout(ss.str());
}

void LogError(const int line, const std::wstring& msg) {
  std::string acp_msg;
  if (blaze_util::WcsToAcp(msg, &acp_msg)) {
    LogError(line, acp_msg);
  }
}

void LogErrorWithValue(const int line, const std::string& msg, DWORD value) {
  std::stringstream ss;
  ss << "value: " << value << " (0x";
  ss.setf(std::ios_base::hex, std::ios_base::basefield);
  ss << std::setw(8) << std::setfill('0') << value << "): ";
  ss.setf(std::ios_base::dec, std::ios_base::basefield);
  ss << msg;
  LogError(line, ss.str());
}

void LogErrorWithValue(const int line, const std::wstring& msg, DWORD value) {
  std::string acp_msg;
  if (blaze_util::WcsToAcp(msg, &acp_msg)) {
    LogErrorWithValue(line, acp_msg, value);
  }
}

void LogErrorWithArg(const int line, const std::string& msg,
                     const std::string& arg) {
  std::stringstream ss;
  ss << msg << " (arg: " << arg << ")";
  LogError(line, ss.str());
}

void LogErrorWithArg(const int line, const std::string& msg,
                     const std::wstring& arg) {
  std::string acp_arg;
  if (blaze_util::WcsToAcp(arg, &acp_arg)) {
    LogErrorWithArg(line, msg, acp_arg);
  }
}

void LogErrorWithArg2(const int line, const std::string& msg,
                      const std::string& arg1, const std::string& arg2) {
  std::stringstream ss;
  ss << msg << " (arg1: " << arg1 << ", arg2: " << arg2 << ")";
  LogError(line, ss.str());
}

void LogErrorWithArg2(const int line, const std::string& msg,
                      const std::wstring& arg1, const std::wstring& arg2) {
  std::string acp_arg1, acp_arg2;
  if (blaze_util::WcsToAcp(arg1, &acp_arg1) &&
      blaze_util::WcsToAcp(arg2, &acp_arg2)) {
    LogErrorWithArg2(line, msg, acp_arg1, acp_arg2);
  }
}

void LogErrorWithArgAndValue(const int line, const std::string& msg,
                             const std::string& arg, DWORD value) {
  std::stringstream ss;
  ss << "value: " << value << " (0x";
  ss.setf(std::ios_base::hex, std::ios_base::basefield);
  ss << std::setw(8) << std::setfill('0') << value << "), arg: ";
  ss.setf(std::ios_base::dec, std::ios_base::basefield);
  ss << arg << ": " << msg;
  LogError(line, ss.str());
}

void LogErrorWithArgAndValue(const int line, const std::string& msg,
                             const std::wstring& arg, DWORD value) {
  std::string acp_arg;
  if (blaze_util::WcsToAcp(arg, &acp_arg)) {
    LogErrorWithArgAndValue(line, msg, acp_arg, value);
  }
}

std::wstring AddUncPrefixMaybe(const Path& p) {
  return bazel::windows::AddUncPrefixMaybe(p.Get());
}

std::wstring RemoveUncPrefixMaybe(const Path& p) {
  return bazel::windows::RemoveUncPrefixMaybe(p.Get());
}

inline bool CreateDirectories(const Path& path) {
  blaze_util::MakeDirectoriesW(AddUncPrefixMaybe(path), 0777);
  return true;
}

inline bool ToInt(const wchar_t* s, int* result) {
  return std::swscanf(s, L"%d", result) == 1;
}

bool WcsToAcp(const std::wstring& wcs, std::string* acp) {
  uint32_t err;
  if (!blaze_util::WcsToAcp(wcs, acp, &err)) {
    LogErrorWithArgAndValue(__LINE__, "Failed to convert string", wcs, err);
    return false;
  }
  return true;
}

// Converts a Windows-style path to a mixed (Unix-Windows) style.
// The path is mixed-style because it is a Windows path (begins with a drive
// letter) but uses forward slashes as directory separators.
// We must export envvars as mixed style path because some tools confuse the
// backslashes in Windows paths for Unix-style escape characters.
std::wstring AsMixedPath(const std::wstring& path) {
  std::wstring value = path;
  std::replace(value.begin(), value.end(), L'\\', L'/');
  return value;
}

bool IsReadableFile(const Path& p) {
  HANDLE h =
      CreateFileW(AddUncPrefixMaybe(p).c_str(), GENERIC_READ,
                  FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                  nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    return false;
  }
  CloseHandle(h);
  return true;
}

// Gets an environment variable's value.
// Returns:
// - true, if the envvar is defined and successfully fetched, or it's empty or
//   undefined
// - false, if some error occurred
bool GetEnv(const wchar_t* name, std::wstring* result) {
  static constexpr size_t kSmallBuf = MAX_PATH;
  WCHAR value[kSmallBuf];
  DWORD size = GetEnvironmentVariableW(name, value, kSmallBuf);
  DWORD err = GetLastError();
  if (size == 0 && err == ERROR_ENVVAR_NOT_FOUND) {
    result->clear();
    return true;
  } else if (0 < size && size < kSmallBuf) {
    *result = value;
    return true;
  } else if (size >= kSmallBuf) {
    std::unique_ptr<WCHAR[]> value_big(new WCHAR[size]);
    GetEnvironmentVariableW(name, value_big.get(), size);
    *result = value_big.get();
    return true;
  } else {
    LogErrorWithArgAndValue(__LINE__, "Failed to read envvar", name, err);
    return false;
  }
}

// Gets an environment variable's value as a Path.
// Returns:
// - true, if the envvar is defined and successfully fetched, or it's empty or
//   undefined
// - false, if some error occurred
bool GetPathEnv(const wchar_t* name, Path* result) {
  std::wstring value;
  if (!GetEnv(name, &value)) {
    LogError(__LINE__, name);
    return false;
  }
  return result->Set(value);
}

// Gets an environment variable's value as integer and as the original string.
// Returns:
// - true, if the envvar is defined and successfully fetched, or it's empty or
//   undefined (in that case 'as_int' will be 0 and 'as_wstr' empty)
// - false, if ToInt cannot parse the string to an int, or some error occurred
bool GetIntEnv(const wchar_t* name, std::wstring* as_wstr, int* as_int) {
  *as_int = 0;
  if (!GetEnv(name, as_wstr) ||
      (!as_wstr->empty() && !ToInt(as_wstr->c_str(), as_int))) {
    LogError(__LINE__, name);
    return false;
  }
  return true;
}

bool SetEnv(const wchar_t* name, const std::wstring& value) {
  if (SetEnvironmentVariableW(name, value.c_str()) != 0) {
    return true;
  } else {
    DWORD err = GetLastError();
    LogErrorWithArgAndValue(__LINE__, "Failed to set envvar", name, err);
    return false;
  }
}

bool SetPathEnv(const wchar_t* name, const Path& path) {
  return SetEnv(name, AsMixedPath(path.Get()));
}

bool UnsetEnv(const wchar_t* name) {
  if (SetEnvironmentVariableW(name, nullptr) != 0) {
    return true;
  } else {
    DWORD err = GetLastError();
    LogErrorWithArgAndValue(__LINE__, "Failed to unset envvar", name, err);
    return false;
  }
}

bool AddCurrentDirectoryToPATH() {
  std::wstring path;
  return GetEnv(L"PATH", &path) && SetEnv(L"PATH", L".;" + path);
}

bool GetCwd(Path* result) {
  static constexpr size_t kSmallBuf = MAX_PATH;
  WCHAR value[kSmallBuf];
  DWORD size = GetCurrentDirectoryW(kSmallBuf, value);
  DWORD err = GetLastError();
  if (size > 0 && size < kSmallBuf) {
    return result->Set(value);
  } else if (size >= kSmallBuf) {
    std::unique_ptr<WCHAR[]> value_big(new WCHAR[size]);
    GetCurrentDirectoryW(size, value_big.get());
    return result->Set(value_big.get());
  } else {
    LogErrorWithValue(__LINE__, "Failed to get current directory", err);
    return false;
  }
}

bool ChdirToRunfiles(const Path& abs_exec_root, const Path& abs_test_srcdir) {
  Path dir = abs_test_srcdir;
  std::wstring preserve_cwd;
  if (!GetEnv(L"RUNTEST_PRESERVE_CWD", &preserve_cwd)) {
    return false;
  }
  if (preserve_cwd.empty()) {
    std::wstring workspace;
    if (!GetEnv(L"TEST_WORKSPACE", &workspace)) {
      return false;
    }
    if (!workspace.empty()) {
      Path joined;
      if (!joined.Set(dir.Get() + L"\\" + workspace)) {
        LogErrorWithArg2(__LINE__, "Could not join paths", dir.Get(),
                         workspace);
        return false;
      }
      dir = joined;
    }
  } else {
    dir = abs_exec_root;
  }
  dir.Absolutize(abs_exec_root);

  // Non-sandboxed commands run in the exec_root, where they have access to the
  // entire source tree. By chdir'ing to the runfiles root, tests only have
  // direct access to their runfiles tree (if it exists), i.e. to their declared
  // dependencies.
  std::wstring coverage_dir;
  if (!GetEnv(L"COVERAGE_DIR", &coverage_dir) || coverage_dir.empty()) {
    if (!SetCurrentDirectoryW(dir.Get().c_str())) {
      DWORD err = GetLastError();
      LogErrorWithArgAndValue(__LINE__, "Could not chdir", dir.Get(), err);
      return false;
    }
  }
  return true;
}

// Set USER as required by the Bazel Test Encyclopedia.
bool ExportUserName() {
  std::wstring value;
  if (!GetEnv(L"USER", &value)) {
    return false;
  }
  if (!value.empty()) {
    // Respect the value passed by Bazel via --test_env.
    return true;
  }
  WCHAR buffer[UNLEN + 1];
  DWORD len = UNLEN + 1;
  if (GetUserNameW(buffer, &len) == 0) {
    DWORD err = GetLastError();
    LogErrorWithValue(__LINE__, "Failed to query user name", err);
    return false;
  }
  return SetEnv(L"USER", buffer);
}

// Gets a path envvar, and re-exports it as an absolute path.
// Returns:
// - true, if the envvar was defined, and was already absolute or was
//   successfully absolutized and re-exported
// - false, if the envvar was undefined or empty, or it could not be absolutized
//   or re-exported
bool ExportAbsolutePathEnv(const wchar_t* envvar, const Path& cwd,
                           Path* result) {
  if (!GetPathEnv(envvar, result)) {
    LogErrorWithArg(__LINE__, "Failed to get envvar", envvar);
    return false;
  }
  if (result->Get().empty()) {
    LogErrorWithArg(__LINE__, "Envvar was empty", envvar);
    return false;
  }
  if (result->Absolutize(cwd) && !SetPathEnv(envvar, *result)) {
    LogErrorWithArg2(__LINE__, "Failed to set absolutized envvar", envvar,
                     result->Get());
    return false;
  }
  return true;
}

// Set TEST_SRCDIR as required by the Bazel Test Encyclopedia.
bool ExportSrcPath(const Path& cwd, Path* result) {
  if (!ExportAbsolutePathEnv(L"TEST_SRCDIR", cwd, result)) {
    LogError(__LINE__, "Failed to export TEST_SRCDIR");
    return false;
  }
  return true;
}

// Set TEST_TMPDIR as required by the Bazel Test Encyclopedia.
bool ExportTmpPath(const Path& cwd, Path* result) {
  if (!ExportAbsolutePathEnv(L"TEST_TMPDIR", cwd, result)) {
    LogError(__LINE__, "Failed to export TEST_TMPDIR");
    return false;
  }
  // Create the test temp directory, which may not exist on the remote host when
  // doing a remote build.
  return CreateDirectories(*result);
}

// Set HOME as required by the Bazel Test Encyclopedia.
bool ExportHome(const Path& test_tmpdir) {
  Path home;
  if (!GetPathEnv(L"HOME", &home)) {
    return false;
  }
  if (blaze_util::IsAbsolute(home.Get())) {
    // Respect the user-defined HOME in case they set passed it with
    // --test_env=HOME or --test_env=HOME=C:\\foo
    return true;
  } else {
    // Set TEST_TMPDIR as required by the Bazel Test Encyclopedia.
    return SetPathEnv(L"HOME", test_tmpdir);
  }
}

bool ExportRunfiles(const Path& cwd, const Path& test_srcdir) {
  Path runfiles_dir;
  if (!GetPathEnv(L"RUNFILES_DIR", &runfiles_dir) ||
      (runfiles_dir.Absolutize(cwd) &&
       !SetPathEnv(L"RUNFILES_DIR", runfiles_dir))) {
    return false;
  }

  // TODO(ulfjack): Standardize on RUNFILES_DIR and remove the
  // {JAVA,PYTHON}_RUNFILES vars.
  Path java_rf, py_rf;
  if (!GetPathEnv(L"JAVA_RUNFILES", &java_rf) ||
      (java_rf.Absolutize(cwd) && !SetPathEnv(L"JAVA_RUNFILES", java_rf)) ||
      !GetPathEnv(L"PYTHON_RUNFILES", &py_rf) ||
      (py_rf.Absolutize(cwd) && !SetPathEnv(L"PYTHON_RUNFILES", py_rf))) {
    return false;
  }

  std::wstring mf_only_str;
  int mf_only_value = 0;
  if (!GetIntEnv(L"RUNFILES_MANIFEST_ONLY", &mf_only_str, &mf_only_value)) {
    return false;
  }
  if (mf_only_value == 1) {
    // If RUNFILES_MANIFEST_ONLY is set to 1 then test programs should use the
    // manifest file to find their runfiles.
    Path runfiles_mf;
    if (!runfiles_mf.Set(test_srcdir.Get() + L"\\MANIFEST") ||
        (IsReadableFile(runfiles_mf) &&
         !SetPathEnv(L"RUNFILES_MANIFEST_FILE", runfiles_mf))) {
      return false;
    }
  }

  return true;
}

bool ExportShardStatusFile(const Path& cwd) {
  Path status_file;
  if (!GetPathEnv(L"TEST_SHARD_STATUS_FILE", &status_file) ||
      (!status_file.Get().empty() && status_file.Absolutize(cwd) &&
       !SetPathEnv(L"TEST_SHARD_STATUS_FILE", status_file))) {
    return false;
  }

  return status_file.Get().empty() ||
         // The test shard status file is only set for sharded tests.
         CreateDirectories(status_file.Dirname());
}

bool ExportGtestVariables(const Path& test_tmpdir) {
  // # Tell googletest about Bazel sharding.
  std::wstring total_shards_str;
  int total_shards_value = 0;
  if (!GetIntEnv(L"TEST_TOTAL_SHARDS", &total_shards_str,
                 &total_shards_value)) {
    return false;
  }
  if (total_shards_value > 0) {
    std::wstring shard_index;
    std::wstring shard_status_file;
    if (!GetEnv(L"TEST_SHARD_STATUS_FILE", &shard_status_file) ||
        !GetEnv(L"TEST_SHARD_INDEX", &shard_index) ||
        !SetEnv(L"GTEST_SHARD_STATUS_FILE", shard_status_file) ||
        !SetEnv(L"GTEST_SHARD_INDEX", shard_index) ||
        !SetEnv(L"GTEST_TOTAL_SHARDS", total_shards_str)) {
      return false;
    }
  }
  return SetPathEnv(L"GTEST_TMP_DIR", test_tmpdir);
}

bool ExportMiscEnvvars(const Path& cwd) {
  // Add BAZEL_TEST environment variable.
  if (!SetEnv(L"BAZEL_TEST", L"1")) {
    return false;
  }

  for (const wchar_t* name :
       {L"TEST_INFRASTRUCTURE_FAILURE_FILE", L"TEST_LOGSPLITTER_OUTPUT_FILE",
        L"TEST_PREMATURE_EXIT_FILE", L"TEST_UNUSED_RUNFILES_LOG_FILE",
        L"TEST_WARNINGS_OUTPUT_FILE"}) {
    Path value;
    if (!GetPathEnv(name, &value) ||
        (value.Absolutize(cwd) && !SetPathEnv(name, value))) {
      return false;
    }
  }
  return true;
}

bool _GetFileListRelativeTo(const std::wstring& unc_root,
                            const std::wstring& subdir, int depth_limit,
                            std::vector<FileInfo>* result) {
  const std::wstring full_subdir =
      unc_root + (subdir.empty() ? L"" : (L"\\" + subdir)) + L"\\*";
  WIN32_FIND_DATAW info;
  HANDLE handle = FindFirstFileW(full_subdir.c_str(), &info);
  if (handle == INVALID_HANDLE_VALUE) {
    DWORD err = GetLastError();
    if (err == ERROR_FILE_NOT_FOUND) {
      // No files found, nothing to do.
      return true;
    }
    LogErrorWithArgAndValue(__LINE__, "Failed to list directory contents",
                            full_subdir, err);
    return false;
  }

  Defer close_handle([handle]() { FindClose(handle); });
  static const std::wstring kDot(1, L'.');
  static const std::wstring kDotDot(2, L'.');
  std::vector<std::wstring> subdirectories;
  while (true) {
    if (kDot != info.cFileName && kDotDot != info.cFileName) {
      std::wstring rel_path =
          subdir.empty() ? info.cFileName : (subdir + L"\\" + info.cFileName);
      if (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
        if (depth_limit != 0) {
          // depth_limit is negative ==> unlimited depth
          // depth_limit is zero     ==> do not recurse further
          // depth_limit is positive ==> recurse further
          subdirectories.push_back(rel_path);
        }
        result->push_back(FileInfo(rel_path));
      } else {
        if (info.nFileSizeHigh > 0 || info.nFileSizeLow > INT_MAX) {
          // devtools_ijar::Stat::total_size is declared as `int`, so the file
          // size limit is INT_MAX. Additionally we limit the files to be below
          // 4 GiB, not only because int is typically 4 bytes long, but also
          // because such huge files are unreasonably large as an undeclared
          // output.
          LogErrorWithArgAndValue(__LINE__, "File is too large to archive",
                                  rel_path, 0);
          return false;
        }

        result->push_back(FileInfo(rel_path,
                                   // File size is already validated to be
                                   // smaller than min(INT_MAX, 4 GiB)
                                   static_cast<int>(info.nFileSizeLow)));
      }
    }
    if (FindNextFileW(handle, &info) == 0) {
      DWORD err = GetLastError();
      if (err == ERROR_NO_MORE_FILES) {
        break;
      }
      LogErrorWithArgAndValue(__LINE__,
                              "Failed to get next element in directory",
                              unc_root + L"\\" + subdir, err);
      return false;
    }
  }
  close_handle.DoNow();

  if (depth_limit != 0) {
    // depth_limit is negative ==> unlimited depth
    // depth_limit is zero     ==> do not recurse further
    // depth_limit is positive ==> recurse further
    for (const auto& s : subdirectories) {
      if (!_GetFileListRelativeTo(
              unc_root, s, depth_limit > 0 ? depth_limit - 1 : depth_limit,
              result)) {
        return false;
      }
    }
  }
  return true;
}

bool GetFileListRelativeTo(const Path& root, std::vector<FileInfo>* result,
                           int depth_limit = -1) {
  if (!blaze_util::IsAbsolute(root.Get())) {
    LogError(__LINE__, "Root should be absolute");
    return false;
  }

  return _GetFileListRelativeTo(AddUncPrefixMaybe(root), std::wstring(),
                                depth_limit, result);
}

bool ToZipEntryPaths(const Path& root, const std::vector<FileInfo>& files,
                     ZipEntryPaths* result) {
  std::string acp_root;
  if (!WcsToAcp(AsMixedPath(RemoveUncPrefixMaybe(root)), &acp_root)) {
    LogErrorWithArg(__LINE__, "Failed to convert path", root.Get());
    return false;
  }

  // Convert all UTF-16 paths to ANSI paths.
  std::vector<std::string> acp_file_list;
  acp_file_list.reserve(files.size());
  for (const auto& e : files) {
    std::string acp_path;
    if (!WcsToAcp(AsMixedPath(e.RelativePath()), &acp_path)) {
      LogErrorWithArg(__LINE__, "Failed to convert path", e.RelativePath());
      return false;
    }
    if (e.IsDirectory()) {
      acp_path += "/";
    }
    acp_file_list.push_back(acp_path);
  }

  result->Create(acp_root, acp_file_list);
  return true;
}

bool CreateZipBuilder(const Path& zip, const ZipEntryPaths& entry_paths,
                      std::unique_ptr<devtools_ijar::ZipBuilder>* result) {
  const devtools_ijar::u8 estimated_size =
      devtools_ijar::ZipBuilder::EstimateSize(entry_paths.AbsPathPtrs(),
                                              entry_paths.EntryPathPtrs(),
                                              entry_paths.Size());

  if (estimated_size == 0) {
    LogError(__LINE__, "Failed to estimate zip size");
    return false;
  }

  std::string acp_zip;
  if (!WcsToAcp(zip.Get(), &acp_zip)) {
    LogErrorWithArg(__LINE__, "Failed to convert path", zip.Get());
    return false;
  }

  result->reset(
      devtools_ijar::ZipBuilder::Create(acp_zip.c_str(), estimated_size));
  if (result->get() == nullptr) {
    LogErrorWithValue(__LINE__, "Failed to create zip builder", errno);
    return false;
  }
  return true;
}

bool OpenFileForWriting(const Path& path, bazel::windows::AutoHandle* result) {
  HANDLE h = CreateFileW(AddUncPrefixMaybe(path).c_str(), GENERIC_WRITE,
                         FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                         CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    DWORD err = GetLastError();
    LogErrorWithArgAndValue(__LINE__, "Failed to open file", path.Get(), err);
    return false;
  }
  *result = h;
  return true;
}

bool OpenExistingFileForRead(const Path& abs_path,
                             bazel::windows::AutoHandle* result) {
  HANDLE h =
      CreateFileW(AddUncPrefixMaybe(abs_path).c_str(), GENERIC_READ,
                  FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                  nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    DWORD err = GetLastError();
    LogErrorWithArgAndValue(__LINE__, "Failed to open file", abs_path.Get(),
                            err);
    return false;
  }
  *result = h;
  return true;
}

bool CreateEmptyFile(const Path& path) {
  bazel::windows::AutoHandle handle;
  return OpenFileForWriting(path, &handle);
}

bool ReadFromFile(HANDLE handle, uint8_t* dest, DWORD max_read) {
  if (max_read == 0) {
    return true;
  }

  DWORD total_read = 0;
  DWORD read = 0;
  do {
    if (!ReadFile(handle, dest + total_read, max_read - total_read, &read,
                  nullptr)) {
      DWORD err = GetLastError();
      LogErrorWithValue(__LINE__, "Failed to read file", err);
      return false;
    }
    total_read += read;
  } while (read > 0 && total_read < max_read);
  return true;
}

bool WriteToFile(HANDLE output, const void* buffer, const size_t size) {
  // Write `size` many bytes to the output file.
  DWORD total_written = 0;
  while (total_written < size) {
    DWORD written;
    if (!WriteFile(output, static_cast<const uint8_t*>(buffer) + total_written,
                   size - total_written, &written, nullptr)) {
      DWORD err = GetLastError();
      LogErrorWithValue(__LINE__, "Failed to write file", err);
      return false;
    }
    total_written += written;
  }
  return true;
}

bool AppendFileTo(const Path& file, const size_t total_size, HANDLE output) {
  bazel::windows::AutoHandle input;
  if (!OpenExistingFileForRead(file, &input)) {
    LogErrorWithArg(__LINE__, "Failed to open file for reading", file.Get());
    return false;
  }

  const size_t buf_size = std::min<size_t>(total_size, /* 10 MB */ 10000000);
  std::unique_ptr<uint8_t[]> buffer(new uint8_t[buf_size]);

  while (true) {
    // Read at most `buf_size` many bytes from the input file.
    DWORD read = 0;
    if (!ReadFile(input, buffer.get(), buf_size, &read, nullptr)) {
      DWORD err = GetLastError();
      LogErrorWithArgAndValue(__LINE__, "Failed to read file", file.Get(), err);
      return false;
    }
    if (read == 0) {
      // Reached end of input file.
      return true;
    }
    if (!WriteToFile(output, buffer.get(), read)) {
      LogErrorWithArg(__LINE__, "Failed to write contents from file",
                      file.Get());
      return false;
    }
  }
  return true;
}

// Returns the MIME type of the file name.
// If the MIME type is unknown or an error occurs, the method returns
// "application/octet-stream".
std::string GetMimeType(const std::string& filename) {
  static constexpr char* kDefaultMimeType = "application/octet-stream";
  std::string::size_type pos = filename.find_last_of('.');
  if (pos == std::string::npos) {
    return kDefaultMimeType;
  }
  char data[1000];
  DWORD data_size = 1000 * sizeof(char);
  if (RegGetValueA(HKEY_CLASSES_ROOT, filename.c_str() + pos, "Content Type",
                   RRF_RT_REG_SZ, nullptr, data, &data_size) == ERROR_SUCCESS) {
    return data;
  }
  // The file extension is unknown, or it does not have a "Content Type" value,
  // or the value is too long. We don't care; just return the default.
  return kDefaultMimeType;
}

bool CreateUndeclaredOutputsManifestContent(const std::vector<FileInfo>& files,
                                            std::string* result) {
  std::stringstream stm;
  for (const auto& e : files) {
    if (!e.IsDirectory()) {
      // For each file, write a tab-separated line to the manifest with name
      // (relative to TEST_UNDECLARED_OUTPUTS_DIR), size, and mime type.
      // Example:
      //   foo.txt<TAB>9<TAB>text/plain
      //   bar/baz<TAB>2944<TAB>application/octet-stream
      std::string acp_path;
      if (!WcsToAcp(AsMixedPath(e.RelativePath()), &acp_path)) {
        return false;
      }

      stm << acp_path << "\t" << e.Size() << "\t" << GetMimeType(acp_path)
          << "\n";
    }
  }
  *result = stm.str();
  return true;
}

bool CreateUndeclaredOutputsManifest(const std::vector<FileInfo>& files,
                                     const Path& output) {
  std::string content;
  if (!CreateUndeclaredOutputsManifestContent(files, &content)) {
    LogErrorWithArg(__LINE__, "Failed to create manifest content for file",
                    output.Get());
    return false;
  }

  bazel::windows::AutoHandle handle;
  if (!OpenFileForWriting(output, &handle)) {
    LogErrorWithArg(__LINE__, "Failed to open file for writing", output.Get());
    return false;
  }

  if (!WriteToFile(handle, content.c_str(), content.size())) {
    LogErrorWithArg(__LINE__, "Failed to write file", output.Get());
    return false;
  }
  return true;
}

bool ExportXmlPath(const Path& cwd, Path* test_outerr, Path* xml_log) {
  if (!GetPathEnv(L"XML_OUTPUT_FILE", xml_log)) {
    LogError(__LINE__);
    return false;
  }
  xml_log->Absolutize(cwd);
  if (!test_outerr->Set(xml_log->Get() + L".log")) {
    LogError(__LINE__);
    return false;
  }
  std::wstring unix_result = AsMixedPath(xml_log->Get());
  return SetEnv(L"XML_OUTPUT_FILE", unix_result) &&
         // TODO(ulfjack): Update Gunit to accept XML_OUTPUT_FILE and drop the
         // GUNIT_OUTPUT env variable.
         SetEnv(L"GUNIT_OUTPUT", L"xml:" + unix_result) &&
         CreateDirectories(xml_log->Dirname()) && CreateEmptyFile(*test_outerr);
}

devtools_ijar::u4 GetZipAttr(const FileInfo& info) {
  // We use these hard-coded Unix permission masks because they are:
  // - stable, so the zip file is deterministic
  // - useful, because stat_to_zipattr expects a mode_t
  static constexpr mode_t kDirectoryMode = 040750;  // drwxr-x--- (directory)
  static constexpr mode_t kFileMode = 0100640;      // -rw-r----- (regular file)

  devtools_ijar::Stat file_stat;
  file_stat.total_size = info.Size();
  file_stat.is_directory = info.IsDirectory();
  file_stat.file_mode = info.IsDirectory() ? kDirectoryMode : kFileMode;
  return devtools_ijar::stat_to_zipattr(file_stat);
}

bool GetZipEntryPtr(devtools_ijar::ZipBuilder* zip_builder,
                    const char* entry_name, const devtools_ijar::u4 attr,
                    devtools_ijar::u1** result) {
  *result = zip_builder->NewFile(entry_name, attr);
  if (*result == nullptr) {
    LogErrorWithArg2(__LINE__, "Failed to add new zip entry for file",
                     entry_name, zip_builder->GetError());
    return false;
  }
  return true;
}

bool CreateZip(const Path& root, const std::vector<FileInfo>& files,
               const Path& abs_zip) {
  bool restore_oem_api = false;
  if (!AreFileApisANSI()) {
    // devtools_ijar::ZipBuilder uses the ANSI file APIs so we must set the
    // active code page to ANSI.
    SetFileApisToANSI();
    restore_oem_api = true;
  }
  Defer restore_file_apis([restore_oem_api]() {
    if (restore_oem_api) {
      SetFileApisToOEM();
    }
  });

  ZipEntryPaths zip_entry_paths;
  if (!ToZipEntryPaths(root, files, &zip_entry_paths)) {
    LogError(__LINE__, "Failed to create zip entry paths");
    return false;
  }

  std::unique_ptr<devtools_ijar::ZipBuilder> zip_builder;
  if (!CreateZipBuilder(abs_zip, zip_entry_paths, &zip_builder)) {
    LogError(__LINE__, "Failed to create zip builder");
    return false;
  }

  for (size_t i = 0; i < files.size(); ++i) {
    bazel::windows::AutoHandle handle;
    Path path;
    if (!path.Set(root.Get() + L"\\" + files[i].RelativePath()) ||
        (!files[i].IsDirectory() && !OpenExistingFileForRead(path, &handle))) {
      LogErrorWithArg(__LINE__, "Failed to open file for reading", path.Get());
      return false;
    }

    devtools_ijar::u1* dest;
    if (!GetZipEntryPtr(zip_builder.get(), zip_entry_paths.EntryPathPtrs()[i],
                        GetZipAttr(files[i]), &dest) ||
        (!files[i].IsDirectory() &&
         !ReadFromFile(handle, dest, files[i].Size()))) {
      LogErrorWithArg(__LINE__, "Failed to dump file into zip", path.Get());
      return false;
    }

    if (zip_builder->FinishFile(files[i].Size(), /* compress */ false,
                                /* compute_crc */ true) == -1) {
      LogErrorWithArg(__LINE__, "Failed to finish writing file to zip",
                      path.Get());
      return false;
    }
  }

  if (zip_builder->Finish() == -1) {
    LogErrorWithArg(__LINE__, "Failed to add file to zip",
                    zip_builder->GetError());
    return false;
  }

  return true;
}

bool GetAndUnexportUndeclaredOutputsEnvvars(const Path& cwd,
                                            UndeclaredOutputs* result) {
  // The test may only see TEST_UNDECLARED_OUTPUTS_DIR and
  // TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR, so keep those but unexport others.
  if (!GetPathEnv(L"TEST_UNDECLARED_OUTPUTS_ZIP", &(result->zip)) ||
      !UnsetEnv(L"TEST_UNDECLARED_OUTPUTS_ZIP") ||

      !GetPathEnv(L"TEST_UNDECLARED_OUTPUTS_MANIFEST", &(result->manifest)) ||
      !UnsetEnv(L"TEST_UNDECLARED_OUTPUTS_MANIFEST") ||

      !GetPathEnv(L"TEST_UNDECLARED_OUTPUTS_ANNOTATIONS",
                  &(result->annotations)) ||
      !UnsetEnv(L"TEST_UNDECLARED_OUTPUTS_ANNOTATIONS") ||

      !GetPathEnv(L"TEST_UNDECLARED_OUTPUTS_DIR", &(result->root)) ||

      !GetPathEnv(L"TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR",
                  &(result->annotations_dir))) {
    return false;
  }

  result->root.Absolutize(cwd);
  result->annotations_dir.Absolutize(cwd);
  result->zip.Absolutize(cwd);
  result->manifest.Absolutize(cwd);
  result->annotations.Absolutize(cwd);

  return SetPathEnv(L"TEST_UNDECLARED_OUTPUTS_DIR", result->root) &&
         SetPathEnv(L"TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR",
                    result->annotations_dir) &&
         CreateDirectories(result->root) &&
         CreateDirectories(result->annotations_dir);
}

bool PrintTestLogStartMarker() {
  std::wstring test_target;
  std::string acp_test_target;
  if (!GetEnv(L"TEST_TARGET", &test_target) ||
      !WcsToAcp(test_target, &acp_test_target)) {
    return false;
  }
  std::stringstream ss;
  if (test_target.empty()) {
    // According to the Bazel Test Encyclopedia, setting TEST_TARGET is
    // optional.
    ss << "Executing tests from unknown target\n";
  } else {
    ss << "Executing tests from " << acp_test_target << "\n";
  }

  // This header marks where --test_output=streamed will start being printed.
  ss << "---------------------------------------------------------------------"
        "--------\n";
  WriteStdout(ss.str());
  return true;
}

inline bool GetWorkspaceName(std::wstring* result) {
  return GetEnv(L"TEST_WORKSPACE", result) && !result->empty();
}

inline void ComputeRunfilePath(const std::wstring& test_workspace,
                               std::wstring* s) {
  if (s->size() >= 2 && (*s)[0] == L'.' && (*s)[1] == L'/') {
    s->erase(0, 2);
  }
  // Runfiles paths of external tests start with "../".
  if (s->find(L"../") == 0) {
    s->erase(0, 3);
  } else {
    *s = test_workspace + L"/" + *s;
  }
}

bool FindTestBinary(const Path& argv0, const Path& cwd, std::wstring test_path,
                    const Path& abs_test_srcdir, Path* result) {
  if (!blaze_util::IsAbsolute(test_path)) {
    std::string argv0_acp;
    if (!WcsToAcp(argv0.Get(), &argv0_acp)) {
      LogErrorWithArg(__LINE__, "Failed to convert path", argv0.Get());
      return false;
    }

    std::string error;
    std::unique_ptr<rules_cc::cc::runfiles::Runfiles> runfiles(
        rules_cc::cc::runfiles::Runfiles::Create(argv0_acp, &error));
    if (runfiles == nullptr) {
      LogError(__LINE__, "Failed to load runfiles");
      return false;
    }

    std::wstring workspace;
    if (!GetEnv(L"TEST_WORKSPACE", &workspace) || workspace.empty()) {
      LogError(__LINE__, "Failed to read %TEST_WORKSPACE%");
      return false;
    }

    ComputeRunfilePath(workspace, &test_path);

    Path test_bin_in_runfiles;
    if (!test_bin_in_runfiles.Set(abs_test_srcdir.Get() + L"\\" + test_path)) {
      LogErrorWithArg2(__LINE__, "Could not join paths", abs_test_srcdir.Get(),
                       test_path);
      return false;
    }

    std::wstring mf_only_str;
    int mf_only_value = 0;
    if (!GetIntEnv(L"RUNFILES_MANIFEST_ONLY", &mf_only_str, &mf_only_value)) {
      return false;
    }

    // If runfiles is enabled on Windows, we use the test binary in the runfiles
    // tree, which is consistent with the behavior on Linux and macOS.
    // Otherwise, we use Rlocation function to find the actual test binary
    // location.
    if (mf_only_value != 1 && IsReadableFile(test_bin_in_runfiles)) {
      test_path = test_bin_in_runfiles.Get();
    } else {
      std::string utf8_test_path;
      uint32_t err;
      if (!blaze_util::WcsToUtf8(test_path, &utf8_test_path, &err)) {
        LogErrorWithArgAndValue(__LINE__, "Failed to convert string to UTF-8",
                                test_path, err);
        return false;
      }

      std::string rloc = runfiles->Rlocation(utf8_test_path);
      if (!blaze_util::Utf8ToWcs(rloc, &test_path, &err)) {
        LogErrorWithArgAndValue(__LINE__, "Failed to convert string",
                                utf8_test_path, err);
      }
    }
  }

  if (!result->Set(test_path)) {
    LogErrorWithArg(__LINE__, "Failed to set path", test_path);
    return false;
  }

  (void)result->Absolutize(cwd);
  return true;
}

bool CreateCommandLine(const Path& path, const std::wstring& args,
                       std::unique_ptr<WCHAR[]>* result) {
  // kMaxCmdline value: see lpCommandLine parameter of CreateProcessW.
  static constexpr size_t kMaxCmdline = 32767;

  if (path.Get().size() + args.size() > kMaxCmdline) {
    LogErrorWithValue(__LINE__, L"Command is too long",
                      path.Get().size() + args.size());
    return false;
  }

  // Add an extra character for the final null-terminator.
  result->reset(new WCHAR[path.Get().size() + args.size() + 1]);

  wcsncpy(result->get(), path.Get().c_str(), path.Get().size());
  wcsncpy(result->get() + path.Get().size(), args.c_str(), args.size() + 1);
  return true;
}

bool StartSubprocess(const Path& path, const std::wstring& args,
                     const Path& outerr, std::unique_ptr<Tee>* tee,
                     LARGE_INTEGER* start_time,
                     bazel::windows::WaitableProcess* process) {
  SECURITY_ATTRIBUTES inheritable_handle_sa = {sizeof(SECURITY_ATTRIBUTES),
                                               nullptr, TRUE};

  // Create a pipe to stream the output of the subprocess to this process.
  // The subprocess inherits two copies of the writing end (one for stdout, one
  // for stderr). This process closes its copies of the handles.
  // This process keeps the reading end and streams data from the pipe to the
  // test log and to stdout.
  HANDLE pipe_read_h, pipe_write_h;
  if (!CreatePipe(&pipe_read_h, &pipe_write_h, &inheritable_handle_sa, 0)) {
    DWORD err = GetLastError();
    LogErrorWithValue(__LINE__, "CreatePipe", err);
    return false;
  }
  bazel::windows::AutoHandle pipe_read(pipe_read_h), pipe_write(pipe_write_h);

  // Duplicate the write end of the pipe.
  // The original will be connected to the stdout of the process, the duplicate
  // to stderr.
  HANDLE pipe_write_dup_h;
  if (!DuplicateHandle(GetCurrentProcess(), pipe_write, GetCurrentProcess(),
                       &pipe_write_dup_h, 0, TRUE, DUPLICATE_SAME_ACCESS)) {
    DWORD err = GetLastError();
    LogErrorWithValue(__LINE__, "DuplicateHandle", err);
    return false;
  }
  bazel::windows::AutoHandle pipe_write_dup(pipe_write_dup_h);

  // Open a readonly handle to NUL. The subprocess inherits this handle that's
  // connected to its stdin.
  bazel::windows::AutoHandle devnull_read(CreateFileW(
      L"NUL", GENERIC_READ,
      FILE_SHARE_WRITE | FILE_SHARE_READ | FILE_SHARE_DELETE,
      &inheritable_handle_sa, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
  if (devnull_read == INVALID_HANDLE_VALUE) {
    DWORD err = GetLastError();
    LogErrorWithValue(__LINE__, "CreateFileW", err);
    return false;
  }

  // Open a handle to the test log file. The "tee" thread will write everything
  // into it that the subprocess writes to the pipe.
  bazel::windows::AutoHandle test_outerr;
  if (!OpenFileForWriting(outerr, &test_outerr)) {
    LogErrorWithArg(__LINE__, "Failed to open file for writing", outerr.Get());
    return false;
  }

  // Duplicate stdout's handle, and pass it to the tee thread, who will own it
  // and close it in the end.
  HANDLE stdout_dup_h;
  if (!DuplicateHandle(GetCurrentProcess(), GetStdHandle(STD_OUTPUT_HANDLE),
                       GetCurrentProcess(), &stdout_dup_h, 0, FALSE,
                       DUPLICATE_SAME_ACCESS)) {
    DWORD err = GetLastError();
    LogErrorWithValue(__LINE__, "DuplicateHandle", err);
    return false;
  }
  bazel::windows::AutoHandle stdout_dup(stdout_dup_h);

  // Create the tee thread, and transfer ownerships of the `pipe_read`,
  // `test_outerr`, and `stdout_dup` handles.
  if (!TeeImpl::Create(&pipe_read, &test_outerr, &stdout_dup, tee)) {
    LogError(__LINE__);
    return false;
  }

  std::wstring werror;
  if (!process->Create(path.Get(), args, nullptr, L"", devnull_read, pipe_write,
                       pipe_write_dup, start_time, &werror)) {
    LogError(__LINE__, werror);
    return false;
  }
  return true;
}

bool RemoveRelativeRecursively(const Path& root,
                               const std::vector<FileInfo>& files) {
  Path path;
  for (const auto& file : files) {
    if (!(path.Set(file.RelativePath()) && path.Absolutize(root) &&
          blaze_util::RemoveRecursively(
              blaze_util::WstringToCstring(path.Get())))) {
      return false;
    }
  }
  return true;
}

bool ArchiveUndeclaredOutputs(const UndeclaredOutputs& undecl) {
  if (undecl.root.Get().empty() || undecl.zip.Get().empty()) {
    // TEST_UNDECLARED_OUTPUTS_DIR was undefined, so there's nothing to archive,
    // or TEST_UNDECLARED_OUTPUTS_ZIP was undefined as
    // --nozip_undeclared_test_outputs was specified.
    return true;
  }

  std::vector<FileInfo> files;
  if (!GetFileListRelativeTo(undecl.root, &files)) {
    return false;
  }
  if (files.empty()) {
    return true;
  }
  return CreateZip(undecl.root, files, undecl.zip) &&
         CreateUndeclaredOutputsManifest(files, undecl.manifest) &&
         RemoveRelativeRecursively(undecl.root, files);
}

// Creates the Undeclared Outputs Annotations file.
//
// This file is a concatenation of every *.part file directly under
// `undecl_annot_dir`. The file is written to `output`.
bool CreateUndeclaredOutputsAnnotations(const Path& undecl_annot_dir,
                                        const Path& output) {
  if (undecl_annot_dir.Get().empty()) {
    // The directory's environment variable
    // (TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR) was probably undefined, nothing
    // to do.
    return true;
  }

  std::vector<FileInfo> files;
  if (!GetFileListRelativeTo(undecl_annot_dir, &files, 0)) {
    LogErrorWithArg(__LINE__, "Failed to get directory contents",
                    undecl_annot_dir.Get());
    return false;
  }
  // There are no *.part files under `undecl_annot_dir`, nothing to do.
  if (files.empty()) {
    return true;
  }

  bazel::windows::AutoHandle handle;
  if (!OpenFileForWriting(output, &handle)) {
    LogErrorWithArg(__LINE__, "Failed to open file for writing", output.Get());
    return false;
  }

  for (const auto& e : files) {
    if (!e.IsDirectory() &&
        e.RelativePath().rfind(L".part") == e.RelativePath().size() - 5) {
      // Only consume "*.part" files.
      Path path;
      if (!path.Set(undecl_annot_dir.Get() + L"\\" + e.RelativePath()) ||
          !AppendFileTo(path, e.Size(), handle)) {
        LogErrorWithArg2(__LINE__, "Failed to append file to another",
                         path.Get(), output.Get());
        return false;
      }
    }
  }
  return true;
}

bool ParseArgs(int argc, wchar_t** argv, Path* out_argv0,
               std::wstring* out_test_path_arg, std::wstring* out_args) {
  if (!out_argv0->Set(argv[0])) {
    return false;
  }
  argc--;
  argv++;

  if (argc < 1) {
    LogError(__LINE__, "Usage: $0 <test_path> [test_args...]");
    return false;
  }

  *out_test_path_arg = argv[0];
  std::wstringstream stm;
  for (int i = 1; i < argc; i++) {
    stm << L' ' << bazel::windows::WindowsEscapeArg(argv[i]);
  }
  *out_args = stm.str();
  return true;
}

bool ParseXmlWriterArgs(int argc, wchar_t** argv, const Path& cwd,
                        Path* out_test_log, Path* out_xml_log,
                        Duration* out_duration, int* out_exit_code) {
  if (argc < 5) {
    LogError(__LINE__,
             "Usage: $0 <test_output_path> <xml_log_path>"
             " <duration_in_seconds> <exit_code>");
    return false;
  }
  if (!out_test_log->Set(argv[1]) || out_test_log->Get().empty()) {
    LogErrorWithArg(__LINE__, "Failed to parse test log path argument",
                    argv[1]);
    return false;
  }
  out_test_log->Absolutize(cwd);
  if (!out_xml_log->Set(argv[2]) || out_xml_log->Get().empty()) {
    LogErrorWithArg(__LINE__, "Failed to parse XML log path argument", argv[2]);
    return false;
  }
  out_xml_log->Absolutize(cwd);
  if (!out_duration->FromString(argv[3])) {
    LogErrorWithArg(__LINE__, "Failed to parse test duration argument",
                    argv[3]);
    return false;
  }
  if (!ToInt(argv[4], out_exit_code)) {
    LogErrorWithArg(__LINE__, "Failed to parse exit code argument", argv[4]);
    return false;
  }
  return true;
}

bool TeeImpl::Create(bazel::windows::AutoHandle* input,
                     bazel::windows::AutoHandle* output1,
                     bazel::windows::AutoHandle* output2,
                     std::unique_ptr<Tee>* result) {
  std::unique_ptr<TeeImpl> tee(new TeeImpl(input, output1, output2));
  bazel::windows::AutoHandle thread(
      CreateThread(nullptr, 0, ThreadFunc, tee.get(), 0, nullptr));
  if (!thread.IsValid()) {
    return false;
  }
  result->reset(tee.release());
  return true;
}

DWORD WINAPI TeeImpl::ThreadFunc(LPVOID lpParam) {
  return reinterpret_cast<TeeImpl*>(lpParam)->MainFunc() ? 0 : 1;
}

bool TeeImpl::MainFunc() const {
  static constexpr size_t kBufferSize = 0x10000;
  DWORD read;
  uint8_t content[kBufferSize];
  while (ReadFile(input_, content, kBufferSize, &read, nullptr)) {
    DWORD written;
    if (read > 0 && (!WriteFile(output1_, content, read, &written, nullptr) ||
                     !WriteFile(output2_, content, read, &written, nullptr))) {
      return false;
    }
  }
  return true;
}

int RunSubprocess(const Path& test_path, const std::wstring& args,
                  const Path& test_outerr, Duration* test_duration) {
  std::unique_ptr<Tee> tee;
  bazel::windows::WaitableProcess process;
  LARGE_INTEGER start, end, freq;
  if (!StartSubprocess(test_path, args, test_outerr, &tee, &start, &process)) {
    LogErrorWithArg(__LINE__, "Failed to start test process", test_path.Get());
    return 1;
  }

  std::wstring werror;
  int wait_res = process.WaitFor(-1, &end, &werror);
  if (wait_res != bazel::windows::WaitableProcess::kWaitSuccess) {
    LogErrorWithValue(__LINE__, werror, wait_res);
    return 1;
  }

  werror.clear();
  int result = process.GetExitCode(&werror);
  if (!werror.empty()) {
    LogError(__LINE__, werror);
    return 1;
  }

  QueryPerformanceFrequency(&freq);
  end.QuadPart -= start.QuadPart;
  decltype(LARGE_INTEGER::QuadPart) seconds;
  // Compute the number of seconds the test ran for.
  seconds = end.QuadPart / freq.QuadPart;
  // Check the remainder: if it's at least 0.5 seconds, round up.
  if ((end.QuadPart - seconds * freq.QuadPart) * 2 >= freq.QuadPart) {
    seconds += 1;
  }
  test_duration->seconds =
      (seconds > Duration::kMax) ? Duration::kMax : seconds;
  return result;
}

// Replace invalid XML characters and locate invalid CDATA sequences.
//
// The legal Unicode code points and ranges are U+0009, U+000A, U+000D,
// U+0020..U+D7FF, U+E000..U+FFFD, and U+10000..U+10FFFF.
//
// Assuming the input is UTF-8 encoded, that translates to the following
// regexps:
//   [\x9\xa\xd\x20-\x7f]                         <--- (9,A,D,20-7F)
//   [\xc0-\xdf][\x80-\xbf]                       <--- (0080-07FF)
//   [\xe0-\xec][\x80-\xbf][\x80-\xbf]            <--- (0800-CFFF)
//   [\xed][\x80-\x9f][\x80-\xbf]                 <--- (D000-D7FF)
//   [\xee][\x80-\xbf][\x80-\xbf]                 <--- (E000-EFFF)
//   [\xef][\x80-\xbe][\x80-\xbf]                 <--- (F000-FFEF)
//   [\xef][\xbf][\x80-\xbd]                      <--- (FFF0-FFFD)
//   [\xf0-\xf7][\x80-\xbf][\x80-\xbf][\x80-\xbf] <--- (010000-10FFFF)
//
// (See https://github.com/bazelbuild/bazel/issues/4691#issuecomment-408089257)
//
// Every octet-sequence matching one of these regexps will be left alone, all
// other octet-sequences will be replaced by '?' characters.
bool CdataEscape(IFStream* in, std::basic_ostream<char>* out) {
  int c0 = in->Get();
  uint8_t p[3];
  for (; c0 < 256; c0 = in->Get()) {
    if (c0 == ']' && in->Peek(2, p) == 2 && p[0] == ']' && p[1] == '>') {
      *out << "]]>]]<![CDATA[>";
      if (!out->good()) {
        return false;
      }
      (void)in->Get();
      (void)in->Get();
    } else if (c0 == 0x9 || c0 == 0xA || c0 == 0xD ||
               (c0 >= 0x20 && c0 <= 0x7F)) {
      // Matched legal single-octet sequence.
      *out << (char)c0;
      if (!out->good()) {
        return false;
      }
    } else if (c0 >= 0xC0 && c0 <= 0xDF && in->Peek(1, p) == 1 &&
               p[0] >= 0x80 && p[0] <= 0xBF) {
      // Matched legal double-octet sequence. Skip the next octet.
      *out << (char)c0 << (char)p[0];
      if (!out->good()) {
        return false;
      }
      (void)in->Get();
    } else if (in->Peek(2, p) == 2 &&
               ((c0 >= 0xE0 && c0 <= 0xEC && p[0] >= 0x80 && p[0] <= 0xBF &&
                 p[1] >= 0x80 && p[1] <= 0xBF) ||
                (c0 == 0xED && p[0] >= 0x80 && p[0] <= 0x9F && p[1] >= 0x80 &&
                 p[1] <= 0xBF) ||
                (c0 == 0xEE && p[0] >= 0x80 && p[0] <= 0xBF && p[1] >= 0x80 &&
                 p[1] <= 0xBF) ||
                (c0 == 0xEF && p[0] >= 0x80 && p[0] <= 0xBE && p[1] >= 0x80 &&
                 p[1] <= 0xBF) ||
                (c0 == 0xEF && p[0] == 0xBF && p[1] >= 0x80 && p[1] <= 0xBD))) {
      // Matched legal triple-octet sequence. Skip the next two octets.
      *out << (char)c0 << (char)p[0] << (char)p[1];
      if (!out->good()) {
        return false;
      }
      (void)in->Get();
      (void)in->Get();
    } else if (in->Peek(3, p) == 3 && c0 >= 0xF0 && c0 <= 0xF7 &&
               p[0] >= 0x80 && p[0] <= 0xBF && p[1] >= 0x80 && p[1] <= 0xBF &&
               p[2] >= 0x80 && p[2] <= 0xBF) {
      // Matched legal quadruple-octet sequence. Skip the next three octets.
      *out << (char)c0 << (char)p[0] << (char)p[1] << (char)p[2];
      if (!out->good()) {
        return false;
      }
      (void)in->Get();
      (void)in->Get();
      (void)in->Get();
    } else {
      // Illegal octet; replace.
      *out << (char)'?';
      if (!out->good()) {
        return false;
      }
    }
  }
  return c0 == IFStream::kIFStreamErrorEOF;
}

bool GetTestName(std::wstring* result) {
  if (!GetEnv(L"TEST_BINARY", result) || result->empty()) {
    LogError(__LINE__, L"Failed to get test name");
    return false;
  }
  if (result->size() >= 2 && (*result)[0] == '.' && (*result)[1] == '/') {
    result->erase(0, 2);
  } else if (result->size() >= 3 && (*result)[0] == '.' &&
             (*result)[1] == '.' && (*result)[2] == '/') {
    result->erase(0, 3);
  }

  // Ensure that test shards have unique names in the xml output, by including
  // the shard index in the test name.
  std::wstring total_shards_str;
  int total_shards = 0, shard_index = 0;
  if (!GetIntEnv(L"TEST_TOTAL_SHARDS", &total_shards_str, &total_shards)) {
    LogError(__LINE__);
    return false;
  }
  if (total_shards > 0) {
    std::wstring shard_index_str;
    if (!GetIntEnv(L"TEST_SHARD_INDEX", &shard_index_str, &shard_index) ||
        shard_index_str.empty()) {
      LogError(__LINE__);
      return false;
    }
    std::wstringstream stm;
    stm << *result << L"_shard_" << (shard_index + 1) << L"/"
        << total_shards_str;
    *result = stm.str();
  }

  return true;
}

std::string CreateErrorTag(int exit_code) {
  if (exit_code != 0) {
    std::stringstream ss;
    ss << "<error message=\"exited with error code " << exit_code
       << "\"></error>";
    return ss.str();
  } else {
    return std::string();
  }
}

bool ShouldCreateXml(const Path& xml_log, const MainType main_type,
                     bool* result) {
  *result = true;

  // If running from the xml generator binary, we should always create the xml
  // file.
  if (main_type == MainType::kXmlWriterMain) {
    return true;
  }

  DWORD attr = GetFileAttributesW(AddUncPrefixMaybe(xml_log).c_str());
  if (attr != INVALID_FILE_ATTRIBUTES) {
    // The XML file already exists, maybe the test framework wrote it.
    // Leave the file alone.
    *result = false;
    return true;
  }

  std::wstring split_xml_generation;
  if (!GetEnv(L"EXPERIMENTAL_SPLIT_XML_GENERATION", &split_xml_generation)) {
    LogError(__LINE__, "Failed to get %EXPERIMENTAL_SPLIT_XML_GENERATION%");
    return false;
  }
  if (split_xml_generation == L"1") {
    // Bazel generates the test xml as a separate action, so we don't have to
    // create it.
    *result = false;
  }

  return true;
}

bool CreateXmlLog(const Path& output, const Path& test_outerr,
                  const Duration duration, const int exit_code,
                  const DeleteAfterwards delete_afterwards,
                  const MainType main_type) {
  bool should_create_xml;
  if (!ShouldCreateXml(output, main_type, &should_create_xml)) {
    LogErrorWithArg(__LINE__, "Failed to decide if XML log is needed",
                    output.Get());
    return false;
  }
  if (!should_create_xml) {
    return true;
  }

  Defer delete_test_outerr([test_outerr, delete_afterwards]() {
    // Delete the test's outerr file after we have the XML file.
    // We don't care if this succeeds or not, because the outerr file is not a
    // declared output.
    if (delete_afterwards == DeleteAfterwards::kEnabled) {
      DeleteFileW(test_outerr.Get().c_str());
    }
  });

  std::wstring test_name;
  int errors = (exit_code == 0) ? 0 : 1;
  std::string error_msg = CreateErrorTag(exit_code);
  if (!GetTestName(&test_name)) {
    LogError(__LINE__);
    return false;
  }

  std::string acp_test_name;
  if (!WcsToAcp(test_name, &acp_test_name)) {
    LogError(__LINE__, test_name.c_str());
    return false;
  }

  bazel::windows::AutoHandle test_log;
  if (!OpenExistingFileForRead(test_outerr, &test_log)) {
    LogError(__LINE__, test_outerr.Get().c_str());
    return false;
  }

  std::unique_ptr<IFStream> istm(IFStreamImpl::Create(test_log));
  if (istm == nullptr) {
    LogError(__LINE__, test_outerr.Get().c_str());
    return false;
  }

  std::ofstream ostm(
      AddUncPrefixMaybe(output).c_str(),
      std::ios_base::out | std::ios_base::binary | std::ios_base::trunc);
  if (!ostm.is_open() || !ostm.good()) {
    LogError(__LINE__, output.Get().c_str());
    return false;
  }

  // Create XML file stub.
  ostm << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          "<testsuites>\n"
          "<testsuite name=\""
       << acp_test_name << "\" tests=\"1\" failures=\"0\" errors=\"" << errors
       << "\">\n"
          "<testcase name=\""
       << acp_test_name << "\" status=\"run\" duration=\"" << duration.seconds
       << "\" time=\"" << duration.seconds << "\">" << error_msg
       << "</testcase>\n"
          "<system-out><![CDATA[";
  if (!ostm.good()) {
    LogError(__LINE__, output.Get().c_str());
    return false;
  }

  // Encode test log to make it embeddable in CDATA.
  if (!CdataEscape(istm.get(), &ostm)) {
    LogError(__LINE__, output.Get().c_str());
    return false;
  }

  // Append CDATA end and closing tags.
  ostm << "]]></system-out>\n</testsuite>\n</testsuites>\n";
  if (!ostm.good()) {
    LogError(__LINE__, output.Get().c_str());
    return false;
  }
  return true;
}

bool Duration::FromString(const wchar_t* str) {
  int result;
  if (!ToInt(str, &result)) {
    LogErrorWithArg(__LINE__, "Failed to parse int from string", str);
    return false;
  }
  this->seconds = result;
  return true;
}

bool Path::Set(const std::wstring& path) {
  std::wstring result;
  std::string error;
  if (!blaze_util::AsWindowsPath(path, &result, &error)) {
    LogError(__LINE__, error);
    return false;
  }
  path_ = result;
  return true;
}

bool Path::Absolutize(const Path& cwd) {
  if (!path_.empty() && !blaze_util::IsAbsolute(path_)) {
    // Both paths are normalized, but this->path_ may begin with ".."s so we
    // must normalize after joining.
    // We wouldn't need full normalization, just normlize at the joined edges,
    // but let's keep the code simple and normalize fully. (AsWindowsPath in
    // Set normalizes.)
    return Set(cwd.path_ + L"\\" + path_);
  } else {
    return false;
  }
}

Path Path::Dirname() const {
  Path result;
  result.path_ = blaze_util::SplitPathW(path_).first;
  return result;
}

IFStream* IFStreamImpl::Create(HANDLE handle, DWORD page_size) {
  std::unique_ptr<uint8_t[]> data(new uint8_t[page_size * 2]);
  DWORD read;
  if (!ReadFile(handle, data.get(), page_size * 2, &read, nullptr)) {
    DWORD err = GetLastError();
    if (err == ERROR_BROKEN_PIPE) {
      read = 0;
    } else {
      LogErrorWithValue(__LINE__, "Failed to read from file", err);
      return nullptr;
    }
  }
  return new IFStreamImpl(handle, std::move(data), read, page_size);
}

int IFStreamImpl::Get() {
  if (pos_ == end_) {
    return kIFStreamErrorEOF;
  }

  int result = pages_[pos_];
  if (pos_ + 1 < end_) {
    pos_++;
    return result;
  }

  // Overwrite the *active* page: we are about to move off of it.
  DWORD offs = (pos_ < page_size_) ? 0 : page_size_;
  DWORD read;
  if (!ReadFile(handle_, pages_.get() + offs, page_size_, &read, nullptr)) {
    DWORD err = GetLastError();
    if (err == ERROR_BROKEN_PIPE) {
      // The stream is reading from a pipe, and there's no more data.
    } else {
      LogErrorWithValue(__LINE__, "Failed to read from file", err);
      return kIFStreamErrorIO;
    }
  }
  pos_ = (pos_ < page_size_) ? page_size_ : 0;
  end_ = pos_ + next_size_;
  next_size_ = read;
  return result;
}

DWORD IFStreamImpl::Peek(DWORD n, uint8_t* out) const {
  if (pos_ == end_) {
    return 0;
  }

  DWORD n1 = end_ - pos_;
  if (n1 > n) {
    n1 = n;  // all 'n' bytes are on the current page
  }
  memcpy(out, pages_.get() + pos_, n1);
  if (n1 == n) {
    return n;
  }

  DWORD offs = (pos_ < page_size_) ? page_size_ : 0;
  DWORD n2 = n - n1;  // how much is left to read
  if (n2 > next_size_) {
    n2 = next_size_;  // read no more than the other page's size
  }
  memcpy(out + n1, pages_.get() + offs, n2);
  return n1 + n2;
}

}  // namespace

void ZipEntryPaths::Create(const std::string& root,
                           const std::vector<std::string>& relative_paths) {
  size_ = relative_paths.size();

  size_t total_size = 0;
  for (const auto& e : relative_paths) {
    // Increase total size for absolute paths by <root> + "/" + <path> +
    // null-terminator.
    total_size += root.size() + 1 + e.size() + 1;
  }

  // Store all absolute paths in one continuous char array.
  abs_paths_.reset(new char[total_size]);

  // Store pointers in two arrays. The pointers point into `abs_path`.
  // We'll pass these paths to devtools_ijar::ZipBuilder::EstimateSize that
  // expects an array of char pointers. The last element must be NULL, so
  // allocate one extra pointer.
  abs_path_ptrs_.reset(new char*[relative_paths.size() + 1]);
  entry_path_ptrs_.reset(new char*[relative_paths.size() + 1]);

  char* p = abs_paths_.get();
  // Create all full paths (root + '/' + relative_paths[i] + '\0').
  //
  // If `root` is "c:/foo", then store the following:
  //
  // - Store each absolute path consecutively in `abs_paths_` (via `p`).
  //   Store paths with forward slashes and not backslashes, because we use them
  //   as zip entry paths, as well as paths we open with CreateFileA (which can
  //   convert these paths internally to Windows-style).
  //   Example: "c:/foo/bar.txt\0c:/foo/sub/baz.txt\0"
  //
  // - Store pointers in `abs_path_ptrs_`, pointing to the start of each
  //   string inside `abs_paths_`.
  //   Example: "c:/foo/bar.txt\0c:/foo/sub/baz.txt\0"
  //             ^ here          ^ here
  //
  // - Store pointers in `entry_path_ptrs_`, pointing to the start of each
  //   zip entry path inside `abs_paths_`, which is the part of each path
  //   that's relative to `root`.
  //   Example: "c:/foo/bar.txt\0c:/foo/sub/baz.txt\0"
  //                    ^ here          ^ here
  //
  // - Because the ZipBuilder requires that the file paths and zip entry paths
  //   are null-terminated arrays, we insert an extra null at their ends.
  for (size_t i = 0; i < relative_paths.size(); ++i) {
    abs_path_ptrs_.get()[i] = p;
    strncpy(p, root.c_str(), root.size());
    p += root.size();
    *p++ = '/';
    entry_path_ptrs_.get()[i] = p;
    strncpy(p, relative_paths[i].c_str(), relative_paths[i].size() + 1);
    p += relative_paths[i].size() + 1;
  }
  abs_path_ptrs_.get()[relative_paths.size()] = nullptr;
  entry_path_ptrs_.get()[relative_paths.size()] = nullptr;
}

int TestWrapperMain(int argc, wchar_t** argv) {
  Path argv0;
  std::wstring test_path_arg;
  Path test_path, exec_root, srcdir, tmpdir, test_outerr, xml_log;
  UndeclaredOutputs undecl;
  std::wstring args;
  if (!AddCurrentDirectoryToPATH() ||
      !ParseArgs(argc, argv, &argv0, &test_path_arg, &args) ||
      !PrintTestLogStartMarker() || !GetCwd(&exec_root) || !ExportUserName() ||
      !ExportSrcPath(exec_root, &srcdir) ||
      !FindTestBinary(argv0, exec_root, test_path_arg, srcdir, &test_path) ||
      !ChdirToRunfiles(exec_root, srcdir) ||
      !ExportTmpPath(exec_root, &tmpdir) || !ExportHome(tmpdir) ||
      !ExportRunfiles(exec_root, srcdir) || !ExportShardStatusFile(exec_root) ||
      !ExportGtestVariables(tmpdir) || !ExportMiscEnvvars(exec_root) ||
      !ExportXmlPath(exec_root, &test_outerr, &xml_log) ||
      !GetAndUnexportUndeclaredOutputsEnvvars(exec_root, &undecl)) {
    return 1;
  }

  Duration test_duration;
  int result = RunSubprocess(test_path, args, test_outerr, &test_duration);
  if (!CreateXmlLog(xml_log, test_outerr, test_duration, result,
                    DeleteAfterwards::kEnabled, MainType::kTestWrapperMain) ||
      !ArchiveUndeclaredOutputs(undecl) ||
      !CreateUndeclaredOutputsAnnotations(undecl.annotations_dir,
                                          undecl.annotations)) {
    return 1;
  }
  return result;
}

int XmlWriterMain(int argc, wchar_t** argv) {
  Path cwd, test_outerr, test_xml_log;
  Duration duration;
  int exit_code = 0;

  if (!GetCwd(&cwd) ||
      !ParseXmlWriterArgs(argc, argv, cwd, &test_outerr, &test_xml_log,
                          &duration, &exit_code) ||
      !CreateXmlLog(test_xml_log, test_outerr, duration, exit_code,
                    DeleteAfterwards::kDisabled, MainType::kXmlWriterMain)) {
    return 1;
  }

  return 0;
}

namespace testing {

bool TestOnly_GetEnv(const wchar_t* name, std::wstring* result) {
  return GetEnv(name, result);
}

bool TestOnly_GetFileListRelativeTo(const std::wstring& abs_root,
                                    std::vector<FileInfo>* result,
                                    int depth_limit) {
  Path root;
  return blaze_util::IsAbsolute(abs_root) && root.Set(abs_root) &&
         GetFileListRelativeTo(root, result, depth_limit);
}

bool TestOnly_ToZipEntryPaths(const std::wstring& abs_root,
                              const std::vector<FileInfo>& files,
                              ZipEntryPaths* result) {
  Path root;
  return blaze_util::IsAbsolute(abs_root) && root.Set(abs_root) &&
         ToZipEntryPaths(root, files, result);
}

bool TestOnly_CreateZip(const std::wstring& abs_root,
                        const std::vector<FileInfo>& files,
                        const std::wstring& abs_zip) {
  Path root, zip;
  return blaze_util::IsAbsolute(abs_root) && root.Set(abs_root) &&
         blaze_util::IsAbsolute(abs_zip) && zip.Set(abs_zip) &&
         CreateZip(root, files, zip);
}

std::string TestOnly_GetMimeType(const std::string& filename) {
  return GetMimeType(filename);
}

bool TestOnly_CreateUndeclaredOutputsManifest(
    const std::vector<FileInfo>& files, std::string* result) {
  return CreateUndeclaredOutputsManifestContent(files, result);
}

bool TestOnly_CreateUndeclaredOutputsAnnotations(
    const std::wstring& abs_root, const std::wstring& abs_output) {
  Path root, output;
  return blaze_util::IsAbsolute(abs_root) && root.Set(abs_root) &&
         blaze_util::IsAbsolute(abs_output) && output.Set(abs_output) &&
         CreateUndeclaredOutputsAnnotations(root, output);
}

bool TestOnly_AsMixedPath(const std::wstring& path, std::string* result) {
  Path p;
  return p.Set(path) && WcsToAcp(AsMixedPath(RemoveUncPrefixMaybe(p)), result);
}

bool TestOnly_CreateTee(bazel::windows::AutoHandle* input,
                        bazel::windows::AutoHandle* output1,
                        bazel::windows::AutoHandle* output2,
                        std::unique_ptr<Tee>* result) {
  return TeeImpl::Create(input, output1, output2, result);
}

bool TestOnly_CdataEncode(IFStream* in_stm, std::basic_ostream<char>* out_stm) {
  return CdataEscape(in_stm, out_stm);
}

IFStream* TestOnly_CreateIFStream(HANDLE handle, DWORD page_size) {
  return IFStreamImpl::Create(handle, page_size);
}

}  // namespace testing
}  // namespace test_wrapper
}  // namespace tools
}  // namespace bazel
