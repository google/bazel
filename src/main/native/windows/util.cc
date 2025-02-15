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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include "src/main/native/windows/util.h"

#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

#include <algorithm>
#include <memory>
#include <sstream>
#include <string>

#include "src/main/native/windows/file.h"

namespace bazel {
namespace windows {

using std::wstring;
using std::wstringstream;

wstring MakeErrorMessage(const wchar_t* file, int line,
                         const wchar_t* failed_func, const wstring& func_arg,
                         const wstring& message) {
  wstringstream result;
  result << L"ERROR: " << file << L"(" << line << L"): " << failed_func << L"("
         << func_arg << L"): " << message;
  return result.str();
}

wstring MakeErrorMessage(const wchar_t* file, int line,
                         const wchar_t* failed_func, const wstring& func_arg,
                         DWORD error_code) {
  return MakeErrorMessage(file, line, failed_func, func_arg,
                          GetLastErrorString(error_code));
}

wstring GetLastErrorString(DWORD error_code) {
  if (error_code == 0) {
    return L"";
  }

  LPWSTR message = nullptr;
  DWORD size = FormatMessageW(
      FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS |
          FORMAT_MESSAGE_ALLOCATE_BUFFER,
      nullptr, error_code, LANG_USER_DEFAULT, (LPWSTR)&message, 0, nullptr);

  if (size == 0) {
    wstringstream err;
    DWORD format_message_error = GetLastError();
    err << L"Error code " << error_code
        << L"; cannot format message due to error code "
        << format_message_error;
    return err.str();
  }

  wstring result(message);
  HeapFree(GetProcessHeap(), LMEM_FIXED, message);
  return result;
}

bool AutoAttributeList::Create(HANDLE stdin_h, HANDLE stdout_h, HANDLE stderr_h,
                               std::unique_ptr<AutoAttributeList>* result,
                               wstring* error_msg) {
  if (stdin_h == INVALID_HANDLE_VALUE && stdout_h == INVALID_HANDLE_VALUE &&
      stderr_h == INVALID_HANDLE_VALUE) {
    result->reset(new AutoAttributeList());
    return true;
  }

  static constexpr DWORD kAttributeCount = 1;

  SIZE_T size = 0;
  // According to MSDN, the first call to InitializeProcThreadAttributeList is
  // expected to fail.
  InitializeProcThreadAttributeList(nullptr, kAttributeCount, 0, &size);
  SetLastError(ERROR_SUCCESS);

  std::unique_ptr<uint8_t[]> data(new uint8_t[size]);
  LPPROC_THREAD_ATTRIBUTE_LIST attrs =
      reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(data.get());
  if (!InitializeProcThreadAttributeList(attrs, kAttributeCount, 0, &size)) {
    if (error_msg) {
      DWORD err = GetLastError();
      *error_msg =
          MakeErrorMessage(WSTR(__FILE__), __LINE__,
                           L"InitializeProcThreadAttributeList", L"", err);
    }
    return false;
  }

  std::unique_ptr<AutoAttributeList> attr_list(
      new AutoAttributeList(std::move(data), stdin_h, stdout_h, stderr_h));
  if (!UpdateProcThreadAttribute(
          attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
          attr_list->handles_.ValidHandles(),
          attr_list->handles_.ValidHandlesCount() * sizeof(HANDLE), nullptr,
          nullptr)) {
    if (error_msg) {
      DWORD err = GetLastError();
      *error_msg = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                    L"UpdateProcThreadAttribute", L"", err);
    }
    return false;
  }
  *result = std::move(attr_list);
  return true;
}

AutoAttributeList::StdHandles::StdHandles()
    : valid_handles_(0),
      stdin_h_(INVALID_HANDLE_VALUE),
      stdout_h_(INVALID_HANDLE_VALUE),
      stderr_h_(INVALID_HANDLE_VALUE) {
  valid_handle_array_[0] = INVALID_HANDLE_VALUE;
  valid_handle_array_[1] = INVALID_HANDLE_VALUE;
  valid_handle_array_[2] = INVALID_HANDLE_VALUE;
}

AutoAttributeList::StdHandles::StdHandles(HANDLE stdin_h, HANDLE stdout_h,
                                          HANDLE stderr_h)
    : valid_handles_(0),
      stdin_h_(stdin_h),
      stdout_h_(stdout_h),
      stderr_h_(stderr_h) {
  valid_handle_array_[0] = INVALID_HANDLE_VALUE;
  valid_handle_array_[1] = INVALID_HANDLE_VALUE;
  valid_handle_array_[2] = INVALID_HANDLE_VALUE;
  if (stdin_h != INVALID_HANDLE_VALUE) {
    valid_handle_array_[valid_handles_++] = stdin_h;
  }
  if (stdout_h != INVALID_HANDLE_VALUE) {
    valid_handle_array_[valid_handles_++] = stdout_h;
  }
  if (stderr_h != INVALID_HANDLE_VALUE) {
    valid_handle_array_[valid_handles_++] = stderr_h;
  }
}

AutoAttributeList::AutoAttributeList(std::unique_ptr<uint8_t[]>&& data,
                                     HANDLE stdin_h, HANDLE stdout_h,
                                     HANDLE stderr_h)
    : data_(std::move(data)), handles_(stdin_h, stdout_h, stderr_h) {}

AutoAttributeList::~AutoAttributeList() {
  DeleteProcThreadAttributeList(*this);
}

AutoAttributeList::operator LPPROC_THREAD_ATTRIBUTE_LIST() const {
  return reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(data_.get());
}

void AutoAttributeList::InitStartupInfoExW(STARTUPINFOEXW* startup_info) const {
  ZeroMemory(startup_info, sizeof(STARTUPINFOEXW));
  startup_info->StartupInfo.cb = sizeof(STARTUPINFOEXW);
  if (InheritAnyHandles()) {
    startup_info->StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup_info->StartupInfo.hStdInput = handles_.StdIn();
    startup_info->StartupInfo.hStdOutput = handles_.StdOut();
    startup_info->StartupInfo.hStdError = handles_.StdErr();
    startup_info->lpAttributeList = *this;
  }
}

static void QuotePath(const wstring& path, wstring* result) {
  *result = wstring(L"\"") + path + L"\"";
}

static bool IsSeparator(WCHAR c) { return c == L'/' || c == L'\\'; }

static bool HasSeparator(const wstring& s) {
  return s.find_first_of(L'/') != wstring::npos ||
         s.find_first_of(L'\\') != wstring::npos;
}

static bool Contains(const wstring& s, const WCHAR* substr) {
  return s.find(substr) != wstring::npos;
}

wstring AsShortPath(wstring path, wstring *result, bool ensure_short) {
  // Using `MAX_PATH` - 4 (256) instead of `MAX_PATH` to fix
  // https://github.com/bazelbuild/bazel/issues/12310
  static const size_t kMaxPath = MAX_PATH - 4;

  if (path.empty()) {
    result->clear();
    return L"";
  }
  if (path[0] == '"') {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"AsShortPath", path,
                            L"path should not be quoted");
  }
  if (IsSeparator(path[0])) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"AsShortPath", path,
                            L"path is absolute without a drive letter");
  }
  if (Contains(path, L"/./") || Contains(path, L"\\.\\") ||
      Contains(path, L"/..") || Contains(path, L"\\..")) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"AsShortPath", path,
                            L"path is not normalized");
  }
  if (path.size() >= kMaxPath && !HasSeparator(path)) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"AsShortPath", path,
                            L"path is just a file name but too long");
  }
  if (HasSeparator(path) && !HasDriveSpecifierPrefix(path.c_str())) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"AsShortPath", path,
                            L"path is not absolute");
  }
  // At this point we know the path is either just a file name (shorter than
  // `kMaxPath`), or an absolute, normalized, Windows-style path (of any
  // length).

  std::replace(path.begin(), path.end(), '/', '\\');
  // Fast-track: the path is already short.
  if (path.size() < kMaxPath) {
    *result = path;
    return L"";
  }
  // At this point we know that the path is at least `kMaxPath` long and that
  // it's absolute, normalized, and Windows-style.

  wstring wlong = wstring(L"\\\\?\\") + path;

  // Experience shows that:
  // - GetShortPathNameW's result has a "\\?\" prefix if and only if the input
  //   did too (though this behavior is not documented on MSDN)
  // - CreateProcess{A,W} only accept an executable of `MAX_PATH` - 1 length
  // Therefore for our purposes the acceptable shortened length is
  // `kMaxPath` + 4 (null-terminated). That is, `kMaxPath` - 1 for the shortened
  // path, plus a potential "\\?\" prefix that's only there if `wlong` also had
  // it and which we'll omit from `result`, plus a null terminator.
  static const size_t kMaxShortPath = kMaxPath + 4;

  DWORD wshort_size = ::GetShortPathNameW(wlong.c_str(), nullptr, 0);
  if (wshort_size == 0) {
    DWORD err_code = GetLastError();
    wstring res = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                   L"GetShortPathNameW", wlong, err_code);
    return res;
  }

  if (ensure_short && (wshort_size >= kMaxShortPath)) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"GetShortPathNameW",
                            wlong, L"cannot shorten the path enough");
  }

  std::unique_ptr<WCHAR[]> wshort(new WCHAR[wshort_size]);
  if (wshort_size - 1 !=
      ::GetShortPathNameW(wlong.c_str(), wshort.get(), wshort_size)) {
    DWORD err_code = GetLastError();
    wstring res = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                   L"GetShortPathNameW", wlong, err_code);
    return res;
  }

  result->assign(wshort.get() + 4);
  return L"";
}

wstring AsExecutablePathForCreateProcess(wstring path, wstring *result,
                                         bool ensure_short) {
  if (path.empty()) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__,
                            L"AsExecutablePathForCreateProcess", path,
                            L"path should not be empty");
  }
  if (IsSeparator(path[0])) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__,
                            L"AsExecutablePathForCreateProcess", path,
                            L"path is absolute without a drive letter");
  }
  if (HasSeparator(path) && !HasDriveSpecifierPrefix(path.c_str())) {
    wstring cwd;
    DWORD err;
    if (!GetCwd(&cwd, &err)) {
      return MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"AsExecutablePathForCreateProcess", path, err);
    }
    path = cwd + L"\\" + path;
  }
  wstring error = AsShortPath(path, result, ensure_short);
  if (!error.empty()) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__,
                            L"AsExecutablePathForCreateProcess", path, error);
  }
  // Quote the path in case it's something like "c:\foo\app name.exe".
  // Do this unconditionally, there's no harm in quoting. Quotes are not
  // allowed inside paths so we don't need to escape quotes.
  QuotePath(*result, result);
  return L"";
}

}  // namespace windows
}  // namespace bazel
