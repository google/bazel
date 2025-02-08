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

#include <wchar.h>
#include <windows.h>

#include <memory>
#include <string>
#include <vector>

#include "gtest/gtest.h"
#include "src/main/cpp/util/path.h"
#include "src/main/native/windows/util.h"
#include "rules_cc/cc/runfiles/runfiles.h"

namespace {

// Asserts argument escaping for subprocesses.
//
// For each pair in 'args', this method:
// 1. asserts that WindowsEscapeArg(pair.first) == pair.second
// 2. asserts that passing pair.second to a subprocess results in the subprocess
//    receiving pair.first
//
// The method performs the second assertion by running "printarg.exe" (a
// data-dependency of this test) once for each argument.
void AssertSubprocessReceivesArgsAsIntended(
    const std::vector<std::pair<std::wstring, std::wstring> >& args) {
  // Assert that the WindowsEscapeArg produces what we expect.
  for (const auto& i : args) {
    ASSERT_EQ(bazel::windows::WindowsEscapeArg(i.first), i.second);
  }

  // Create a Runfiles object.
  std::string error;
  std::unique_ptr<rules_cc::cc::runfiles::Runfiles> runfiles(
      rules_cc::cc::runfiles::Runfiles::CreateForTest(&error));
  ASSERT_NE(runfiles.get(), nullptr) << error;

  // Look up the path of the printarg.exe utility.
  std::string printarg =
      runfiles->Rlocation("io_bazel/src/test/native/windows/printarg.exe");
  ASSERT_NE(printarg, "");

  // Convert printarg.exe's path to a wchar_t Windows path.
  std::wstring wprintarg;
  bool success =
      blaze_util::AsAbsoluteWindowsPath(printarg, &wprintarg, &error);
  ASSERT_TRUE(success) << error;

  // SECURITY_ATTRIBUTES for inheritable HANDLEs.
  SECURITY_ATTRIBUTES sa;
  sa.nLength = sizeof(sa);
  sa.lpSecurityDescriptor = nullptr;
  sa.bInheritHandle = TRUE;

  // Open /dev/null that will be redirected into the subprocess' stdin.
  bazel::windows::AutoHandle devnull(
      CreateFileW(L"NUL", GENERIC_READ,
                  FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &sa,
                  OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
  ASSERT_TRUE(devnull.IsValid());

  // Create a pipe that the subprocess' stdout will be redirected to.
  HANDLE pipe_read_h, pipe_write_h;
  if (!CreatePipe(&pipe_read_h, &pipe_write_h, &sa, 0x10000)) {
    DWORD err = GetLastError();
    ASSERT_EQ(err, 0);
  }
  bazel::windows::AutoHandle pipe_read(pipe_read_h), pipe_write(pipe_write_h);

  // Duplicate stderr, where the subprocess' stderr will be redirected to.
  HANDLE stderr_h;
  if (!DuplicateHandle(GetCurrentProcess(), GetStdHandle(STD_ERROR_HANDLE),
                       GetCurrentProcess(), &stderr_h, 0, TRUE,
                       DUPLICATE_SAME_ACCESS)) {
    DWORD err = GetLastError();
    ASSERT_EQ(err, 0);
  }
  bazel::windows::AutoHandle stderr_dup(stderr_h);

  // Create the attribute object for the process creation. This object describes
  // exactly which handles the subprocess shall inherit.
  STARTUPINFOEXW startupInfo;
  std::unique_ptr<bazel::windows::AutoAttributeList> attrs;
  std::wstring werror;
  ASSERT_TRUE(bazel::windows::AutoAttributeList::Create(
      devnull, pipe_write, stderr_dup, &attrs, &werror));
  attrs->InitStartupInfoExW(&startupInfo);

  // MSDN says the maximum command line is 32767 characters, with a null
  // terminator that is exactly 2^15 (= 0x8000).
  static constexpr size_t kMaxCmdline = 0x8000;
  wchar_t cmdline[kMaxCmdline];

  // Copy printarg.exe's escaped path into the 'cmdline', and append a space.
  // We will append arguments to this command line in the for-loop below.
  wprintarg = bazel::windows::WindowsEscapeArg(wprintarg);
  wcsncpy(cmdline, wprintarg.c_str(), wprintarg.size());
  wchar_t* pcmdline = cmdline + wprintarg.size();
  *pcmdline++ = L' ';

  // Run a subprocess for each of the arguments and assert that the argument
  // arrived to the subprocess as intended.
  for (const auto& i : args) {
    // We already asserted for every element that WindowsEscapeArg(i.first)
    // produces the same output as i.second, so just use i.second instead of
    // converting i.first again.
    wcsncpy(pcmdline, i.second.c_str(), i.second.size());
    pcmdline[i.second.size()] = 0;

    // Run the subprocess.
    PROCESS_INFORMATION processInfo;
    BOOL ok = CreateProcessW(
        nullptr, cmdline, nullptr, nullptr, TRUE,
        CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT, nullptr,
        nullptr, &startupInfo.StartupInfo, &processInfo);
    if (!ok) {
      DWORD err = GetLastError();
      ASSERT_EQ(err, 0);
    }
    CloseHandle(processInfo.hThread);
    bazel::windows::AutoHandle process(processInfo.hProcess);

    // Wait for the subprocess to exit. Timeout is 5 seconds, which should be
    // more than enough for the subprocess to finish.
    ASSERT_EQ(WaitForSingleObject(process, 5000), WAIT_OBJECT_0);

    // The subprocess printed its argv[1] (without a newline) to its stdout,
    // which is redirected into the pipe.
    // Let's write a null-terminator to the pipe to separate the output from the
    // output of the subsequent subprocess. The null-terminator also yields
    // null-terminated strings in the pipe, making it easy to read them out
    // later.
    DWORD dummy;
    ASSERT_TRUE(WriteFile(pipe_write, "\0", 1, &dummy, nullptr));
  }

  // Read the output of the subprocesses from the pipe. They are divided by
  // null-terminators, so 'buf' will contain a sequence of null-terminated
  // strings.  We close the writing end so that ReadFile won't block until the
  // desired amount of bytes is available.
  DWORD total_output_len;
  char buf[0x10000];
  pipe_write = INVALID_HANDLE_VALUE;
  if (!ReadFile(pipe_read, buf, 0x10000, &total_output_len, nullptr)) {
    DWORD err = GetLastError();
    ASSERT_EQ(err, 0);
  }

  // Assert that the subprocesses produced exactly the *unescaped* arguments.
  size_t start = 0;
  for (const auto& arg : args) {
    // Assert that there was enough data produced by the subprocesses.
    ASSERT_LT(start, total_output_len);

    // Find the output of the corresponding subprocess. Since all subprocesses
    // printed into the same pipe and we added null-terminators between them,
    // the output is already there, conveniently as a null-terminated string.
    std::string actual_arg(buf + start);
    start += actual_arg.size() + 1;

    // 'args' contains wchar_t strings, but the subprocesses printed ASCII
    // (char) strings. To compare, we convert arg.first to a char-string.
    std::string expected_arg;
    expected_arg.reserve(arg.first.size());
    for (const auto& wc : arg.first) {
      expected_arg.append(1, static_cast<char>(wc));
    }

    // Assert that the subprocess printed exactly the *unescaped* argument.
    EXPECT_EQ(expected_arg, actual_arg);
  }
}

TEST(ProcessTest, WindowsEscapeArgTest) {
  AssertSubprocessReceivesArgsAsIntended({
      {L"", L"\"\""},
      {L" ", L"\" \""},
      {L"\"", L"\"\\\"\""},
      {L"\"\\", L"\"\\\"\\\\\""},
      {L"\\", L"\\"},
      {L"\\\"", L"\"\\\\\\\"\""},
      {L"with space", L"\"with space\""},
      {L"with^caret", L"with^caret"},
      {L"space ^caret", L"\"space ^caret\""},
      {L"caret^ space", L"\"caret^ space\""},
      {L"with\"quote", L"\"with\\\"quote\""},
      {L"with\\backslash", L"with\\backslash"},
      {L"one\\ backslash and \\space", L"\"one\\ backslash and \\space\""},
      {L"two\\\\backslashes", L"two\\\\backslashes"},
      {L"two\\\\ backslashes \\\\and space",
       L"\"two\\\\ backslashes \\\\and space\""},
      {L"one\\\"x", L"\"one\\\\\\\"x\""},
      {L"two\\\\\"x", L"\"two\\\\\\\\\\\"x\""},
      {L"a \\ b", L"\"a \\ b\""},
      {L"a \\\" b", L"\"a \\\\\\\" b\""},
      {L"A", L"A"},
      {L"\"a\"", L"\"\\\"a\\\"\""},
      {L"B C", L"\"B C\""},
      {L"\"b c\"", L"\"\\\"b c\\\"\""},
      {L"D\"E", L"\"D\\\"E\""},
      {L"\"d\"e\"", L"\"\\\"d\\\"e\\\"\""},
      {L"C:\\F G", L"\"C:\\F G\""},
      {L"\"C:\\f g\"", L"\"\\\"C:\\f g\\\"\""},
      {L"C:\\H\"I", L"\"C:\\H\\\"I\""},
      {L"\"C:\\h\"i\"", L"\"\\\"C:\\h\\\"i\\\"\""},
      {L"C:\\J\\\"K", L"\"C:\\J\\\\\\\"K\""},
      {L"\"C:\\j\\\"k\"", L"\"\\\"C:\\j\\\\\\\"k\\\"\""},
      {L"C:\\L M ", L"\"C:\\L M \""},
      {L"\"C:\\l m \"", L"\"\\\"C:\\l m \\\"\""},
      {L"C:\\N O\\", L"\"C:\\N O\\\\\""},
      {L"\"C:\\n o\\\"", L"\"\\\"C:\\n o\\\\\\\"\""},
      {L"C:\\P Q\\ ", L"\"C:\\P Q\\ \""},
      {L"\"C:\\p q\\ \"", L"\"\\\"C:\\p q\\ \\\"\""},
      {L"C:\\R\\S\\", L"C:\\R\\S\\"},
      {L"C:\\R x\\S\\", L"\"C:\\R x\\S\\\\\""},
      {L"\"C:\\r\\s\\\"", L"\"\\\"C:\\r\\s\\\\\\\"\""},
      {L"\"C:\\r x\\s\\\"", L"\"\\\"C:\\r x\\s\\\\\\\"\""},
      {L"C:\\T U\\W\\", L"\"C:\\T U\\W\\\\\""},
      {L"\"C:\\t u\\w\\\"", L"\"\\\"C:\\t u\\w\\\\\\\"\""},
  });
}

}  // namespace
