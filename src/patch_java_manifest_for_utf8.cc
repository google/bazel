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

#include <stdio.h>
#include <stdlib.h>

#include <string>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

// This program patches the app manifest of the java.exe launcher to force its
// active code page to UTF-8. This is necessary because the launcher sets
// sun.jnu.encoding to the system code page, which by default is a legacy
// code page such as Cp1252 on Windows.
int wmain(int argc, wchar_t *argv[]) {
  if (argc != 2) {
    fwprintf(stderr, L"Usage: %ls <filename>\n", argv[0]);
    return 1;
  }

  // Read the app manifest (aka side-by-side or fusion manifest) from the
  // executable, which requires loading it as a "module".
  HMODULE exe = LoadLibraryExW(argv[1], nullptr, LOAD_LIBRARY_AS_DATAFILE);
  if (!exe) {
    fwprintf(stderr, L"Error loading file %ls\n", argv[1]);
    return 1;
  }
  HRSRC manifest_resource = FindResourceA(exe, MAKEINTRESOURCE(1), RT_MANIFEST);
  if (!manifest_resource) {
    fwprintf(stderr, L"Resource not found.\n");
    return 1;
  }
  HGLOBAL manifest_handle = LoadResource(exe, manifest_resource);
  if (!manifest_handle) {
    fwprintf(stderr, L"Error loading resource.\n");
    return 1;
  }
  LPVOID manifest_data = LockResource(manifest_handle);
  if (!manifest_data) {
    fwprintf(stderr, L"Error locking resource.\n");
    return 1;
  }
  DWORD manifest_len = SizeofResource(exe, manifest_resource);
  std::string manifest((char *) manifest_data, manifest_len);
  UnlockResource(manifest_handle);
  FreeResource(manifest_handle);
  FreeLibrary(exe);

  // Insert the activeCodePage element into the manifest at the end of the
  // windowsSettings element.
  // https://github.com/openjdk/jdk/blob/29882bfe7b7e76446a96862cd0a5e81c7e054415/src/java.base/windows/native/launcher/java.manifest#L43
  std::size_t insert_pos = manifest.find("</asmv3:windowsSettings>");
  if (insert_pos == std::wstring::npos) {
    fwprintf(stderr, L"End tag not found in manifest:\n%s", manifest.c_str());
    return 1;
  }
  std::string new_manifest = manifest.substr(0, insert_pos) +
      "<activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">UTF-8</activeCodePage>" +
      manifest.substr(insert_pos);

  // Write back the modified app manifest.
  HANDLE update_handle = BeginUpdateResourceW(argv[1], false);
  if (!update_handle) {
    fwprintf(stderr, L"Error opening file for update.\n");
    return 1;
  }
  if (!UpdateResourceA(update_handle,
                       MAKEINTRESOURCE(1),
                       RT_MANIFEST,
                       MAKELANGID(LANG_NEUTRAL, SUBLANG_NEUTRAL),
                       const_cast<char *>(new_manifest.c_str()),
                       new_manifest.size())) {
    fwprintf(stderr, L"Error updating resource.\n");
    return 1;
  }
  if (!EndUpdateResourceW(update_handle, false)) {
    fwprintf(stderr, L"Error finalizing update.\n");
    return 1;
  }

  return 0;
}
