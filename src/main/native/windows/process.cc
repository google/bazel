// Copyright 2019 The Bazel Authors. All rights reserved.
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

#include "src/main/native/windows/process.h"

#include <versionhelpers.h>
#include <windows.h>

#include <memory>
#include <sstream>

#include <codecvt>
#include <locale.h>
#include <string_view>

namespace bazel {
namespace windows {

template <typename T>
static std::wstring ToString(const T& e) {
  std::wstringstream s;
  s << e;
  return s.str();
}

static bool DupeHandle(HANDLE h, AutoHandle* out, std::wstring* error) {
  HANDLE dup;
  if (!DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &dup, 0,
                       TRUE, DUPLICATE_SAME_ACCESS)) {
    DWORD err = GetLastError();
    *error =
        MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DupeHandle", L"", err);
    return false;
  }
  *out = dup;
  return true;
}

static std::wstring CreateProcessWrapper(const std::wstring argv0,
                                         const std::wstring &commandline,
                                         std::wstring &out_path) {
  // Safety first, ensure the out_path is empty before we do any work
  out_path = L"";

  /*
    Save on possible collisions by using the argv0 name as a base for the
    wrapper file name.
    Argv0 is likely quoted, so we can use a string_view to cheaply manipulate
    it and get rid of the quote if needed.
  */
  auto const view = std::wstring_view(argv0);
  auto const pos = view.find_last_of('\\');
  auto stem = view.substr(pos == std::wstring::npos ? 0 : (pos + 1));
  if (stem.find(L"\"") != std::wstring::npos) {
    /*
     After removing the initial quote, there's no other place for the quote to
     be than at the end, so we can simply remove it and move on.
     */
    stem.remove_suffix(1);
  }

  std::wstring wrapper_file_name = std::wstring(stem) + L".wrapper.bat";
  HANDLE handle = ::CreateFileW(
      /* lpFileName */ wrapper_file_name.c_str(),
      /* dwDesiredAccess */ GENERIC_WRITE,
      /* dwShareMode */ FILE_SHARE_READ,
      /* lpSecurityAttributes */ nullptr,
      /* dwCreationDisposition */ CREATE_ALWAYS,
      /* dwFlagsAndAttributes */ FILE_ATTRIBUTE_NORMAL,
      /* hTemplateFile */ nullptr);

  if (handle == INVALID_HANDLE_VALUE) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateProcessWrapper",
                            wrapper_file_name,
                            L"Unable to create file wrapper");
  }

  /*
    Simply call the original command line
    Different powershells seem to struggle with widechars, so convert
    to normal char instead.
  */
  std::string contents =
      "@ECHO OFF\n\n" +
      std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(commandline);

  DWORD bytesWritten;
  if (!::WriteFile(handle, contents.data(), contents.length(), &bytesWritten,
                   nullptr)) {

    DWORD err_code = GetLastError();
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateProcessWrapper",
                            wrapper_file_name, err_code);
  }

  if (bytesWritten != contents.length()) {
    std::wstring res = MakeErrorMessage(
        WSTR(__FILE__), __LINE__, L"CreateProcessWrapper", wrapper_file_name,
        L"Unable to write all data to file wrapper");
    return res;
  }

  if (!::CloseHandle(handle)) {
    DWORD err_code = GetLastError();
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateProcessWrapper",
                            wrapper_file_name, err_code);
  }
  handle = INVALID_HANDLE_VALUE;

  out_path = wrapper_file_name;
  return L"";
}

bool WaitableProcess::Create(const std::wstring& argv0,
                             const std::wstring& argv_rest, void* env,
                             const std::wstring& wcwd, std::wstring* error) {
  // On Windows 7, the subprocess cannot inherit console handles via the
  // attribute list (CreateProcessW fails with ERROR_NO_SYSTEM_RESOURCES).
  // If we pass only invalid handles here, the Create method creates an
  // AutoAttributeList with 0 handles and initializes the STARTUPINFOEXW as
  // empty and disallowing handle inheritance. At the same time, CreateProcessW
  // specifies neither CREATE_NEW_CONSOLE nor DETACHED_PROCESS, so the
  // subprocess *does* inherit the console, which is exactly what we want, and
  // it works on all supported Windows versions.
  // Fixes https://github.com/bazelbuild/bazel/issues/8676
  return Create(argv0, argv_rest, env, wcwd, INVALID_HANDLE_VALUE,
                INVALID_HANDLE_VALUE, INVALID_HANDLE_VALUE, nullptr, true, true,
                error);
}

bool WaitableProcess::Create(const std::wstring& argv0,
                             const std::wstring& argv_rest, void* env,
                             const std::wstring& wcwd, HANDLE stdin_process,
                             HANDLE stdout_process, HANDLE stderr_process,
                             LARGE_INTEGER* opt_out_start_time,
                             std::wstring* error) {
  return Create(argv0, argv_rest, env, wcwd, stdin_process, stdout_process,
                stderr_process, opt_out_start_time, false, false, error);
}

bool WaitableProcess::Create(const std::wstring& argv0,
                             const std::wstring& argv_rest, void* env,
                             const std::wstring& wcwd, HANDLE stdin_process,
                             HANDLE stdout_process, HANDLE stderr_process,
                             LARGE_INTEGER* opt_out_start_time,
                             bool create_window, bool handle_signals,
                             std::wstring* error) {
  std::wstring cwd;
  std::wstring error_msg(AsShortPath(wcwd, &cwd));
  if (!error_msg.empty()) {
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, error_msg);
    return false;
  }

  std::wstring argv0short;
  error_msg = AsExecutablePathForCreateProcess(argv0, &argv0short, false);
  if (!error_msg.empty()) {
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, error_msg);
    return false;
  }

  /*
    If the short form of the executable is still well above MAX_PATH, then we
    cannot use it for CreateProcessW in any form.
    Instead, output a wrapper file into a known location that ends up calling
    the executable instead. This should side-step the MAX_PATH limitations.
  */
  std::wstring commandline =
      argv_rest.empty() ? argv0short : (argv0short + L" " + argv_rest);
  if (argv0short.length() >= MAX_PATH) {
    std::wstring wrapper_file_path;
    std::wstring error_msg(
        CreateProcessWrapper(argv0short, commandline, wrapper_file_path));
    if (!error_msg.empty()) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::Create", argv0, error_msg);
      return false;
    }

    // Redirect command line to call the wrapper instead
    commandline = L"cmd /c " + wrapper_file_path;
  }

  std::unique_ptr<WCHAR[]> mutable_commandline(
      new WCHAR[commandline.size() + 1]);
  wcsncpy(mutable_commandline.get(), commandline.c_str(),
          commandline.size() + 1);
  // MDSN says that the default for job objects is that breakaway is not
  // allowed. Thus, we don't need to do any more setup here.
  job_ = CreateJobObject(nullptr, nullptr);
  if (!job_.IsValid()) {
    DWORD err_code = GetLastError();
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, err_code);
    return false;
  }

  JOBOBJECT_EXTENDED_LIMIT_INFORMATION job_info = {0};
  job_info.BasicLimitInformation.LimitFlags =
      JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
  if (!SetInformationJobObject(job_, JobObjectExtendedLimitInformation,
                               &job_info, sizeof(job_info))) {
    DWORD err_code = GetLastError();
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, err_code);
    return false;
  }

  ioport_ = CreateIoCompletionPort(INVALID_HANDLE_VALUE, nullptr, 0, 1);
  if (!ioport_.IsValid()) {
    DWORD err_code = GetLastError();
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, err_code);
    return false;
  }
  JOBOBJECT_ASSOCIATE_COMPLETION_PORT port;
  port.CompletionKey = job_;
  port.CompletionPort = ioport_;
  if (!SetInformationJobObject(job_,
                               JobObjectAssociateCompletionPortInformation,
                               &port, sizeof(port))) {
    DWORD err_code = GetLastError();
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, err_code);
    return false;
  }

  std::unique_ptr<AutoAttributeList> attr_list;
  if (!AutoAttributeList::Create(stdin_process, stdout_process, stderr_process,
                                 &attr_list, &error_msg)) {
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", L"", error_msg);
    return false;
  }

  // kMaxCmdline value: see lpCommandLine parameter of CreateProcessW.
  static constexpr size_t kMaxCmdline = 32767;

  std::wstring cmd_sample = mutable_commandline.get();
  if (cmd_sample.size() > 500) {
    cmd_sample = cmd_sample.substr(0, 495) + L"(...)";
  }
  if (wcsnlen_s(mutable_commandline.get(), kMaxCmdline) == kMaxCmdline) {
    std::wstringstream error_msg;
    error_msg << L"command is longer than CreateProcessW's limit ("
              << kMaxCmdline << L" characters)";
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"CreateProcessWithExplicitHandles", cmd_sample,
                              error_msg.str().c_str());
    return false;
  }

  PROCESS_INFORMATION process_info = {0};
  STARTUPINFOEXW info;
  attr_list->InitStartupInfoExW(&info);
  if (!CreateProcessW(
          /* lpApplicationName */ nullptr,
          /* lpCommandLine */ mutable_commandline.get(),
          /* lpProcessAttributes */ nullptr,
          /* lpThreadAttributes */ nullptr,
          /* bInheritHandles */ attr_list->InheritAnyHandles() ? TRUE : FALSE,
          /* dwCreationFlags */ (create_window ? 0 : CREATE_NO_WINDOW) |
              (handle_signals ? 0
                              : CREATE_NEW_PROCESS_GROUP)  // So that Ctrl-Break
                                                           // isn't propagated
              | CREATE_SUSPENDED  // So that it doesn't start a new job itself
              | EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
          /* lpEnvironment */ env,
          /* lpCurrentDirectory */ cwd.empty() ? nullptr : cwd.c_str(),
          /* lpStartupInfo */ &info.StartupInfo,
          /* lpProcessInformation */ &process_info)) {
    DWORD err = GetLastError();

    std::wstring errmsg;
    if (err == ERROR_NO_SYSTEM_RESOURCES && !IsWindows8OrGreater() &&
        attr_list->HasConsoleHandle()) {
      errmsg =
          L"Unrecoverable error: host OS is Windows 7 and subprocess"
          " got an inheritable console handle";
    } else {
      errmsg = GetLastErrorString(err) + L" (error: " + ToString(err) + L")";
    }

    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateProcessW",
                              cmd_sample, errmsg);
    return false;
  }

  pid_ = process_info.dwProcessId;
  process_ = process_info.hProcess;
  AutoHandle thread(process_info.hThread);

  if (!AssignProcessToJobObject(job_, process_)) {
    BOOL is_in_job = false;
    if (IsProcessInJob(process_, nullptr, &is_in_job) && is_in_job &&
        !IsWindows8OrGreater()) {
      // Pre-Windows 8 systems don't support nested jobs, and Bazel is already
      // in a job.  We can't create nested jobs, so just revert to
      // TerminateProcess() and hope for the best. In batch mode, the launcher
      // puts Bazel in a job so that will take care of cleanup once the
      // command finishes.
      job_ = INVALID_HANDLE_VALUE;
      ioport_ = INVALID_HANDLE_VALUE;
    } else {
      DWORD err_code = GetLastError();
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::Create", argv0, err_code);
      return false;
    }
  }

  // Now that we put the process in a new job object, we can start executing
  // it
  if (ResumeThread(thread) == -1) {
    DWORD err_code = GetLastError();
    *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                              L"WaitableProcess::Create", argv0, err_code);
    return false;
  }

  if (opt_out_start_time) {
    QueryPerformanceCounter(opt_out_start_time);
  }
  *error = L"";
  return true;
}

int WaitableProcess::WaitFor(int64_t timeout_msec,
                             LARGE_INTEGER* opt_out_end_time,
                             std::wstring* error) {
  struct Defer {
    LARGE_INTEGER* t;
    Defer(LARGE_INTEGER* cnt) : t(cnt) {}
    ~Defer() {
      if (t) {
        QueryPerformanceCounter(t);
      }
    }
  } defer_query_end_time(opt_out_end_time);

  DWORD win32_timeout = timeout_msec < 0 ? INFINITE : timeout_msec;
  int result;
  switch (WaitForSingleObject(process_, win32_timeout)) {
    case WAIT_OBJECT_0:
      result = kWaitSuccess;
      break;

    case WAIT_TIMEOUT:
      result = kWaitTimeout;
      break;

    // Any other case is an error and should be reported back to Bazel.
    default:
      DWORD err_code = GetLastError();
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::WaitFor", ToString(pid_),
                                err_code);
      return kWaitError;
  }

  // Ensure that the process is really terminated (if WaitForSingleObject
  // above timed out, we have to explicitly kill it) and that it doesn't
  // leave behind any subprocesses.
  if (!Terminate(error)) {
    return kWaitError;
  }

  if (job_.IsValid()) {
    // Wait for the job object to complete, signalling that all subprocesses
    // have exited.
    DWORD CompletionCode;
    ULONG_PTR CompletionKey;
    LPOVERLAPPED Overlapped;
    while (GetQueuedCompletionStatus(ioport_, &CompletionCode,
                                     &CompletionKey, &Overlapped, INFINITE) &&
           !((HANDLE)CompletionKey == (HANDLE)job_ &&
             CompletionCode == JOB_OBJECT_MSG_ACTIVE_PROCESS_ZERO)) {
      // Still waiting...
    }

    job_ = INVALID_HANDLE_VALUE;
    ioport_ = INVALID_HANDLE_VALUE;
  }

  // Fetch and store the exit code in case Bazel asks us for it later,
  // because we cannot do this anymore after we closed the handle.
  GetExitCode(error);

  if (process_.IsValid()) {
    process_ = INVALID_HANDLE_VALUE;
  }

  return result;
}

int WaitableProcess::GetExitCode(std::wstring* error) {
  if (exit_code_ == STILL_ACTIVE) {
    if (!GetExitCodeProcess(process_, &exit_code_)) {
      DWORD err_code = GetLastError();
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::GetExitCode", ToString(pid_),
                                err_code);
      return -1;
    }
  }

  return exit_code_;
}

bool WaitableProcess::Terminate(std::wstring* error) {
  static constexpr UINT exit_code = 130;  // 128 + SIGINT, like on Linux

  if (job_.IsValid()) {
    if (!TerminateJobObject(job_, exit_code)) {
      DWORD err_code = GetLastError();
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::Terminate", ToString(pid_),
                                err_code);
      return false;
    }
  } else if (process_.IsValid()) {
    if (!TerminateProcess(process_, exit_code)) {
      DWORD err_code = GetLastError();
      std::wstring our_error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                                L"WaitableProcess::Terminate",
                                                ToString(pid_), err_code);

      // If the process exited, despite TerminateProcess having failed, we're
      // still happy and just ignore the error. It might have been a race
      // where the process exited by itself just before we tried to kill it.
      // However, if the process is *still* running at this point (evidenced
      // by its exit code still being STILL_ACTIVE) then something went
      // really unexpectedly wrong and we should report that error.
      if (GetExitCode(error) == STILL_ACTIVE) {
        // Restore the error message from TerminateProcess - it will be much
        // more helpful for debugging in case something goes wrong here.
        *error = our_error;
        return false;
      }
    }

    if (WaitForSingleObject(process_, INFINITE) != WAIT_OBJECT_0) {
      DWORD err_code = GetLastError();
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"WaitableProcess::Terminate", ToString(pid_),
                                err_code);
      return false;
    }
  }

  *error = L"";
  return true;
}

// Escape arguments for CreateProcessW.
//
// This algorithm is based on information found in
// http://daviddeley.com/autohotkey/parameters/parameters.htm
//
// The following source specifies a similar algorithm:
// https://blogs.msdn.microsoft.com/twistylittlepassagesallalike/2011/04/23/everyone-quotes-command-line-arguments-the-wrong-way/
// unfortunately I found this algorithm only after creating the one below, but
// fortunately they seem to do the same.
std::wstring WindowsEscapeArg(const std::wstring& s) {
  if (s.empty()) {
    return L"\"\"";
  } else {
    bool needs_escaping = false;
    for (const auto& c : s) {
      if (c == ' ' || c == '"') {
        needs_escaping = true;
        break;
      }
    }
    if (!needs_escaping) {
      return s;
    }
  }

  std::wostringstream result;
  result << L'"';
  int start = 0;
  for (int i = 0; i < s.size(); ++i) {
    char c = s[i];
    if (c == '"' || c == '\\') {
      // Copy the segment since the last special character.
      if (start >= 0) {
        result << s.substr(start, i - start);
        start = -1;
      }

      // Handle the current special character.
      if (c == '"') {
        // This is a quote character. Escape it with a single backslash.
        result << L"\\\"";
      } else {
        // This is a backslash (or the first one in a run of backslashes).
        // Whether we escape it depends on whether the run ends with a quote.
        int run_len = 1;
        int j = i + 1;
        while (j < s.size() && s[j] == '\\') {
          run_len++;
          j++;
        }
        if (j == s.size()) {
          // The run of backslashes goes to the end.
          // We have to escape every backslash with another backslash.
          for (int k = 0; k < run_len * 2; ++k) {
            result << L'\\';
          }
          break;
        } else if (j < s.size() && s[j] == '"') {
          // The run of backslashes is terminated by a quote.
          // We have to escape every backslash with another backslash, and
          // escape the quote with one backslash.
          for (int k = 0; k < run_len * 2; ++k) {
            result << L'\\';
          }
          result << L"\\\"";
          i += run_len;  // 'i' is also increased in the loop iteration step
        } else {
          // No quote found. Each backslash counts for itself, they must not be
          // escaped.
          for (int k = 0; k < run_len; ++k) {
            result << L'\\';
          }
          i += run_len - 1;  // 'i' is also increased in the loop iteration step
        }
      }
    } else {
      // This is not a special character. Start the segment if necessary.
      if (start < 0) {
        start = i;
      }
    }
  }
  // Save final segment after the last special character.
  if (start != -1) {
    result << s.substr(start);
  }
  result << L'"';
  return result.str();
}

}  // namespace windows
}  // namespace bazel
