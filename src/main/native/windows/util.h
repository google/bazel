// Copyright 2016 The Bazel Authors. All rights reserved.
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

#ifndef BAZEL_SRC_MAIN_NATIVE_WINDOWS_UTIL_H__
#define BAZEL_SRC_MAIN_NATIVE_WINDOWS_UTIL_H__

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>

#include <memory>
#include <string>

namespace bazel {
namespace windows {

using std::wstring;

// A wrapper for the `HANDLE` type that calls CloseHandle in its d'tor.
// WARNING: do not use for HANDLE returned by FindFirstFile; those must be
// closed with FindClose (otherwise they aren't closed properly).
class AutoHandle {
 public:
  explicit AutoHandle(HANDLE handle = INVALID_HANDLE_VALUE) : handle_(handle) {}
  AutoHandle(const AutoHandle&) = delete;
  AutoHandle(AutoHandle&& other) = delete;
  AutoHandle& operator=(const AutoHandle&) = delete;
  AutoHandle& operator=(AutoHandle&& other) = delete;

  ~AutoHandle() {
    if (IsValid()) {
      ::CloseHandle(handle_);
    }
  }

  AutoHandle(AutoHandle* other) : handle_(other->handle_) {
    other->handle_ = INVALID_HANDLE_VALUE;
  }

  bool IsValid() const {
    return handle_ != INVALID_HANDLE_VALUE && handle_ != nullptr;
  }

  AutoHandle& operator=(const HANDLE& rhs) {
    if (IsValid()) {
      ::CloseHandle(handle_);
    }
    handle_ = rhs;
    return *this;
  }

  operator HANDLE() const { return handle_; }

 private:
  HANDLE handle_;
};

class AutoAttributeList {
 public:
  AutoAttributeList() {}

  static bool Create(HANDLE stdin_h, HANDLE stdout_h, HANDLE stderr_h,
                     std::unique_ptr<AutoAttributeList>* result,
                     std::wstring* error_msg = nullptr);
  ~AutoAttributeList();

  bool InheritAnyHandles() const { return handles_.ValidHandlesCount() > 0; }

  void InitStartupInfoExW(STARTUPINFOEXW* startup_info) const;

  bool HasConsoleHandle() const { return handles_.HasConsoleHandle(); }

 private:
  class StdHandles {
   public:
    StdHandles();
    StdHandles(HANDLE stdin_h, HANDLE stdout_h, HANDLE stderr_h);
    size_t ValidHandlesCount() const { return valid_handles_; }
    HANDLE* ValidHandles() { return valid_handle_array_; }
    HANDLE StdIn() const { return stdin_h_; }
    HANDLE StdOut() const { return stdout_h_; }
    HANDLE StdErr() const { return stderr_h_; }

    bool HasConsoleHandle() const {
      for (size_t i = 0; i < valid_handles_; ++i) {
        if (GetFileType(valid_handle_array_[i]) == FILE_TYPE_CHAR) {
          return true;
        }
      }
      return false;
    }

   private:
    size_t valid_handles_;
    HANDLE valid_handle_array_[3];
    HANDLE stdin_h_;
    HANDLE stdout_h_;
    HANDLE stderr_h_;
  };

  AutoAttributeList(std::unique_ptr<uint8_t[]>&& data, HANDLE stdin_h,
                    HANDLE stdout_h, HANDLE stderr_h);
  AutoAttributeList(const AutoAttributeList&) = delete;
  AutoAttributeList& operator=(const AutoAttributeList&) = delete;

  operator LPPROC_THREAD_ATTRIBUTE_LIST() const;

  std::unique_ptr<uint8_t[]> data_;
  StdHandles handles_;
};

#define WSTR1(x) L##x
#define WSTR(x) WSTR1(x)

wstring MakeErrorMessage(const wchar_t* file, int line,
                         const wchar_t* failed_func, const wstring& func_arg,
                         const wstring& message);
wstring MakeErrorMessage(const wchar_t* file, int line,
                         const wchar_t* failed_func, const wstring& func_arg,
                         DWORD error_code);
wstring GetLastErrorString(DWORD error_code);

// Same as `AsExecutablePathForCreateProcess` except it won't quote the result.
wstring AsShortPath(wstring path, wstring* result, bool ensure_short = true);

// Computes a path suitable as the executable part in CreateProcessA's cmdline.
//
// The null-terminated executable path for CreateProcessA has to fit into
// MAX_PATH, therefore the limit for the executable's path is MAX_PATH - 1
// (not including null terminator). This method attempts to convert the input
// `path` to a short format to fit it into the MAX_PATH - 1 limit.
//
// `path` must be either an absolute, normalized, Windows-style path with drive
// letter (e.g. "c:\foo\bar.exe", but no "\foo\bar.exe"), or must be just a file
// name (e.g. "cmd.exe") that's shorter than MAX_PATH (without null-terminator).
// In both cases, `path` must be unquoted.
//
// If this function succeeds, it returns an empty string (indicating no error),
// and sets `result` to the resulting path, which is always quoted, and is
// always at most MAX_PATH + 1 long (MAX_PATH - 1 without null terminator, plus
// two quotes). If there's any error, this function returns the error message.
//
// If `path` is at most MAX_PATH - 1 long (not including null terminator), the
// result will be that (plus quotes).
// Otherwise this method attempts to compute an 8dot3 style short name for
// `path`, and if that succeeds and the result is at most MAX_PATH - 1 long (not
// including null terminator), then that will be the result (plus quotes).

// If ensure_short is set to true, this function fails and returns an error message when
// the path is still too long after 8dot3 shortening.
// If ensure_short is set to false, the input path is simply returned and the caller must check
// the length of the path.
wstring AsExecutablePathForCreateProcess(wstring path, wstring* result, bool ensure_short = true);

}  // namespace windows
}  // namespace bazel

#endif  // BAZEL_SRC_MAIN_NATIVE_WINDOWS_UTIL_H__
