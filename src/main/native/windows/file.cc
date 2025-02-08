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

#include "src/main/native/windows/file.h"

#include <stdint.h>  // uint8_t
#include <versionhelpers.h>
#include <winbase.h>
#include <windows.h>
#include <winioctl.h>

#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "src/main/native/windows/util.h"

#ifndef IO_REPARSE_TAG_PROJFS
#define IO_REPARSE_TAG_PROJFS 0x9000001C
#endif

namespace bazel {
namespace windows {

using std::unique_ptr;
using std::wstring;

DWORD DetermineSymlinkPrivilegeFlag() {
  DWORD val = 0;
  DWORD valSize = sizeof(val);
  // Check if developer mode is disabled
  if (RegGetValueW(
          HKEY_LOCAL_MACHINE,
          L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\AppModelUnlock",
          L"AllowDevelopmentWithoutDevLicense", RRF_RT_DWORD, nullptr, &val,
          &valSize) != ERROR_SUCCESS ||
      val == 0) {
    return 0;
  }
  return SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE;
}

wstring AddUncPrefixMaybe(const wstring& path) {
  return path.empty() || IsDevNull(path.c_str()) || HasUncPrefix(path.c_str())
             ? path
             : (wstring(L"\\\\?\\") + path);
}

wstring RemoveUncPrefixMaybe(const wstring& path) {
  return bazel::windows::HasUncPrefix(path.c_str()) ? path.substr(4) : path;
}

bool IsAbsoluteNormalizedWindowsPath(const wstring& p) {
  if (p.empty()) {
    return false;
  }
  if (IsDevNull(p.c_str())) {
    return true;
  }
  if (p.find_first_of('/') != wstring::npos) {
    return false;
  }

  return HasDriveSpecifierPrefix(p.c_str()) && p.find(L".\\") != 0 &&
         p.find(L"\\.\\") == wstring::npos && p.find(L"\\.") != p.size() - 2 &&
         p.find(L"..\\") != 0 && p.find(L"\\..\\") == wstring::npos &&
         p.find(L"\\..") != p.size() - 3;
}

static wstring uint32asHexString(uint32_t value) {
  WCHAR attr_str[8];
  for (int i = 0; i < 8; ++i) {
    attr_str[7 - i] = L"0123456789abcdef"[value & 0xF];
    value >>= 4;
  }
  return wstring(attr_str, 8);
}

int IsSymlinkOrJunction(const WCHAR* path, bool* result, wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    if (error) {
      *error =
          MakeErrorMessage(WSTR(__FILE__), __LINE__, L"IsSymlinkOrJunction",
                           path, L"expected an absolute Windows path");
    }
    return IsSymlinkOrJunctionResult::kError;
  }

  DWORD attrs = ::GetFileAttributesW(path);
  if (attrs == INVALID_FILE_ATTRIBUTES) {
    DWORD err = GetLastError();
    if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
      return IsSymlinkOrJunctionResult::kDoesNotExist;
    }

    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"IsSymlinkOrJunction", path, err);
    }
    return IsSymlinkOrJunctionResult::kError;
  } else {
    *result = (attrs & FILE_ATTRIBUTE_REPARSE_POINT);
    return IsSymlinkOrJunctionResult::kSuccess;
  }
}

int GetChangeTime(const WCHAR* path, bool follow_reparse_points,
                  int64_t* result, wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"GetChangeTime",
                                path, L"expected an absolute Windows path");
    }
    return GetChangeTimeResult::kError;
  }

  AutoHandle handle;
  DWORD flags = FILE_FLAG_BACKUP_SEMANTICS;
  if (!follow_reparse_points) {
    flags |= FILE_FLAG_OPEN_REPARSE_POINT;
  }
  handle = CreateFileW(path, 0,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       nullptr, OPEN_EXISTING, flags, nullptr);

  if (!handle.IsValid()) {
    DWORD err = GetLastError();
    if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
      return GetChangeTimeResult::kDoesNotExist;
    } else if (err == ERROR_ACCESS_DENIED) {
      return GetChangeTimeResult::kAccessDenied;
    }
    if (error) {
      *error =
          MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateFileW", path, err);
    }
    return GetChangeTimeResult::kError;
  }

  FILE_BASIC_INFO info;
  if (!GetFileInformationByHandleEx(handle, FileBasicInfo, (LPVOID)&info,
                                    sizeof(FILE_BASIC_INFO))) {
    DWORD err = GetLastError();
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"GetFileInformationByHandleEx", path, err);
    }
    return GetChangeTimeResult::kError;
  }

  *result = info.ChangeTime.QuadPart;
  return GetChangeTimeResult::kSuccess;
}

wstring GetLongPath(const WCHAR* path, unique_ptr<WCHAR[]>* result) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"GetLongPath", path,
                            L"expected an absolute Windows path");
  }

  std::wstring wpath(AddUncPrefixMaybe(path));
  DWORD size = ::GetLongPathNameW(wpath.c_str(), nullptr, 0);
  if (size == 0) {
    DWORD err_code = GetLastError();
    return MakeErrorMessage(WSTR(__FILE__), __LINE__, L"GetLongPathNameW", path,
                            err_code);
  }
  result->reset(new WCHAR[size]);
  ::GetLongPathNameW(wpath.c_str(), result->get(), size);
  return L"";
}

#pragma pack(push, 4)
// See https://msdn.microsoft.com/en-us/windows/desktop/ff552012
typedef struct _REPARSE_DATA_BUFFER {
  ULONG ReparseTag;
  USHORT ReparseDataLength;
  USHORT Reserved;
  union {
    struct {
      USHORT SubstituteNameOffset;
      USHORT SubstituteNameLength;
      USHORT PrintNameOffset;
      USHORT PrintNameLength;
      ULONG Flags;
      WCHAR PathBuffer[1];
    } SymbolicLinkReparseBuffer;
    struct {
      USHORT SubstituteNameOffset;
      USHORT SubstituteNameLength;
      USHORT PrintNameOffset;
      USHORT PrintNameLength;
      WCHAR PathBuffer[1];
    } MountPointReparseBuffer;
    struct {
      UCHAR DataBuffer[1];
    } GenericReparseBuffer;
  } DUMMYUNIONNAME;
} REPARSE_DATA_BUFFER, *PREPARSE_DATA_BUFFER;
#pragma pack(pop)

int CreateJunction(const wstring& junction_name, const wstring& junction_target,
                   wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(junction_name)) {
    if (error) {
      *error = MakeErrorMessage(
          WSTR(__FILE__), __LINE__, L"CreateJunction", junction_name,
          L"expected an absolute Windows path for junction_name");
    }
    return CreateJunctionResult::kError;
  }
  if (!IsAbsoluteNormalizedWindowsPath(junction_target)) {
    if (error) {
      *error = MakeErrorMessage(
          WSTR(__FILE__), __LINE__, L"CreateJunction", junction_target,
          L"expected an absolute Windows path for junction_target");
    }
    return CreateJunctionResult::kError;
  }

  const WCHAR* target = HasUncPrefix(junction_target.c_str())
                            ? junction_target.c_str() + 4
                            : junction_target.c_str();
  const size_t target_size = HasUncPrefix(junction_target.c_str())
                                 ? junction_target.size() - 4
                                 : junction_target.size();
  // The entire REPARSE_DATA_BUFFER cannot be larger than
  // MAXIMUM_REPARSE_DATA_BUFFER_SIZE bytes.
  //
  // The structure's layout is:
  //   [8 bytes] : ReparseTag, ReparseDataLength, Reserved
  //   [8 bytes] : MountPointReparseBuffer members before PathBuffer
  //   ---- start of MountPointReparseBuffer.PathBuffer ----
  //   [4 WCHARs]             : "\??\" prefix
  //   [target.size() WCHARs] : junction target name
  //   [1 WCHAR]              : null-terminator
  //   [target.size() WCHARs] : junction target displayed name
  //   [1 WCHAR]              : null-terminator
  // The sum of these must not exceed MAXIMUM_REPARSE_DATA_BUFFER_SIZE.
  // We can rearrange this to get the limit for target.size().
  static const size_t kMaxJunctionTargetLen =
      ((MAXIMUM_REPARSE_DATA_BUFFER_SIZE -
        offsetof(REPARSE_DATA_BUFFER, MountPointReparseBuffer.PathBuffer)) /
           sizeof(WCHAR) -
       /* one "\??\" prefix */ 4 -
       /* two null terminators */ 2) /
      /* two copies of the string are stored */ 2;
  if (target_size > kMaxJunctionTargetLen) {
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateJunction",
                                target, L"target path is too long");
    }
    return CreateJunctionResult::kTargetNameTooLong;
  }
  const wstring name = HasUncPrefix(junction_name.c_str())
                           ? junction_name
                           : (wstring(L"\\\\?\\") + junction_name);

  // Junctions are directories, so create a directory.
  // If CreateDirectoryW succeeds, we'll try to set the junction's target.
  // If CreateDirectoryW fails, we don't care about the exact reason -- could be
  // that the directory already exists, or we have no access to create a
  // directory, or the path was invalid to begin with. Either way set `create`
  // to false, meaning we'll just attempt to open the path for metadata-reading
  // and check if it's a junction pointing to the desired target.
  bool create = CreateDirectoryW(name.c_str(), nullptr) != 0;

  AutoHandle handle;
  if (create) {
    handle = CreateFileW(
        name.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, nullptr,
        OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr);
  }

  if (!handle.IsValid()) {
    // We can't open the directory for writing: either we didn't even try to
    // (`create` was false), or the path disappeared, or it turned into a file,
    // or another process holds it open without write-sharing.
    // Either way, don't try to create the junction, just try opening it without
    // any read or write access (we can still read its metadata) and maximum
    // sharing, and check its target.
    create = false;
    handle = CreateFileW(
        name.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (!handle.IsValid()) {
      // We can't open the directory at all: either it disappeared, or it turned
      // into a file, or the path is invalid, or another process holds it open
      // without any sharing. Give up.
      DWORD err = GetLastError();
      if (err == ERROR_SHARING_VIOLATION) {
        // The junction is held open by another process.
        return CreateJunctionResult::kAccessDenied;
      } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
        // Meanwhile the directory disappeared or one of its parent directories
        // disappeared.
        return CreateJunctionResult::kDisappeared;
      }

      // The path seems to exist yet we cannot open it for metadata-reading.
      // Report as much information as we have, then give up.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateFileW",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }
  }

  // We have an open handle to the file! It may still be other than a junction,
  // so check its attributes.
  BY_HANDLE_FILE_INFORMATION info;
  if (!GetFileInformationByHandle(handle, &info)) {
    DWORD err = GetLastError();
    // Some unknown error occurred.
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"GetFileInformationByHandle", name, err);
    }
    return CreateJunctionResult::kError;
  }

  if (info.dwFileAttributes == INVALID_FILE_ATTRIBUTES) {
    DWORD err = GetLastError();
    // Some unknown error occurred.
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                L"GetFileInformationByHandle", name, err);
    }
    return CreateJunctionResult::kError;
  }

  if (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) {
    // The path already exists and it's a junction. Do not overwrite, just check
    // its target.
    create = false;
  }

  if (create) {
    if (!(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
      // Even though we managed to create the directory and it didn't exist
      // before, another process changed it in the meantime so it's no longer a
      // directory.
      create = false;
      if (!(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        // The path is no longer a directory, and it's not a junction either.
        // Though this is a case for kAlreadyExistsButNotJunction, let's instead
        // print the attributes and return kError, to give more information to
        // the user.
        if (error) {
          *error = MakeErrorMessage(
              WSTR(__FILE__), __LINE__, L"GetFileInformationByHandle", name,
              wstring(L"attrs=0x") + uint32asHexString(info.dwFileAttributes));
        }
        return CreateJunctionResult::kError;
      }
    }
  }

  if (!create) {
    // The path already exists. Check if it's a junction.
    if (!(info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
      return CreateJunctionResult::kAlreadyExistsButNotJunction;
    }
  }

  uint8_t reparse_buffer_bytes[MAXIMUM_REPARSE_DATA_BUFFER_SIZE];
  PREPARSE_DATA_BUFFER reparse_buffer =
      reinterpret_cast<PREPARSE_DATA_BUFFER>(reparse_buffer_bytes);
  if (create) {
    // The junction doesn't exist yet, and we have an open handle to the
    // candidate directory with write access and no sharing. Proceed to turn the
    // directory into a junction.

    memset(reparse_buffer_bytes, 0, MAXIMUM_REPARSE_DATA_BUFFER_SIZE);

    reparse_buffer->MountPointReparseBuffer.SubstituteNameOffset = 0;
    reparse_buffer->MountPointReparseBuffer.SubstituteNameLength =
        (4 + target_size) * sizeof(WCHAR);
    reparse_buffer->MountPointReparseBuffer.PrintNameOffset =
        reparse_buffer->MountPointReparseBuffer.SubstituteNameLength +
        /* null-terminator */ sizeof(WCHAR);
    reparse_buffer->MountPointReparseBuffer.PrintNameLength =
        target_size * sizeof(WCHAR);

    reparse_buffer->ReparseTag = IO_REPARSE_TAG_MOUNT_POINT;
    reparse_buffer->ReparseDataLength =
        4 * sizeof(USHORT) +
        reparse_buffer->MountPointReparseBuffer.SubstituteNameLength +
        reparse_buffer->MountPointReparseBuffer.PrintNameLength +
        /* 2 null-terminators */ (2 * sizeof(WCHAR));
    reparse_buffer->Reserved = 0;

    // "\??\" is meaningful to the kernel, it's a synomym for the "\DosDevices\"
    // object path. (NOT to be confused with "\\?\" which is meaningful for the
    // Win32 API.) We need to use this prefix to tell the kernel where the
    // reparse point is pointing to.
    memcpy((uint8_t*)reparse_buffer->MountPointReparseBuffer.PathBuffer +
               reparse_buffer->MountPointReparseBuffer.SubstituteNameOffset,
           L"\\??\\", 4 * sizeof(WCHAR));
    memcpy((uint8_t*)reparse_buffer->MountPointReparseBuffer.PathBuffer +
               reparse_buffer->MountPointReparseBuffer.SubstituteNameOffset +
               4 * sizeof(WCHAR),
           target,
           reparse_buffer->MountPointReparseBuffer.SubstituteNameLength -
               4 * sizeof(WCHAR));

    // In addition to their target, junctions also have another string which is
    // a user-visible name of where the junction points, as listed by "dir".
    // This can be any string and won't affect the usability of the junction.
    // MKLINK uses the target path without the "\??\" prefix as the display
    // name, so let's do that here too. This is also in line with how UNIX
    // behaves. Using a dummy or fake display name would be misleading, it would
    // make the output of `dir` look like:
    //   2017-01-18  01:37 PM    <JUNCTION>     juncname [dummy string]
    memcpy((uint8_t*)reparse_buffer->MountPointReparseBuffer.PathBuffer +
               reparse_buffer->MountPointReparseBuffer.PrintNameOffset,
           target, reparse_buffer->MountPointReparseBuffer.PrintNameLength);

    DWORD bytes_returned;
    if (!::DeviceIoControl(
            handle, FSCTL_SET_REPARSE_POINT, reparse_buffer,
            reparse_buffer->ReparseDataLength +
                offsetof(REPARSE_DATA_BUFFER, GenericReparseBuffer.DataBuffer),
            nullptr, 0, &bytes_returned, nullptr)) {
      DWORD err = GetLastError();
      if (err == ERROR_DIR_NOT_EMPTY) {
        return CreateJunctionResult::kAlreadyExistsButNotJunction;
      }
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeviceIoControl",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }
  } else {
    // The junction already exists. Check if it points to the right target.

    DWORD bytes_returned;
    if (!::DeviceIoControl(handle, FSCTL_GET_REPARSE_POINT, nullptr, 0,
                           reparse_buffer, MAXIMUM_REPARSE_DATA_BUFFER_SIZE,
                           &bytes_returned, nullptr)) {
      DWORD err = GetLastError();
      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeviceIoControl",
                                  name, err);
      }
      return CreateJunctionResult::kError;
    }

    WCHAR* actual_target =
        reparse_buffer->MountPointReparseBuffer.PathBuffer +
        reparse_buffer->MountPointReparseBuffer.SubstituteNameOffset +
        /* "\??\" prefix */ 4;
    if (reparse_buffer->MountPointReparseBuffer.SubstituteNameLength !=
            (/* "\??\" prefix */ 4 + target_size) * sizeof(WCHAR) ||
        _wcsnicmp(actual_target, target, target_size) != 0) {
      return CreateJunctionResult::kAlreadyExistsWithDifferentTarget;
    }
  }

  return CreateJunctionResult::kSuccess;
}

int CreateSymlink(const wstring& symlink_name, const wstring& symlink_target,
                   wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(symlink_name)) {
    if (error) {
      *error = MakeErrorMessage(
          WSTR(__FILE__), __LINE__, L"CreateSymlink", symlink_name,
          L"expected an absolute Windows path for symlink_name");
    }
    return CreateSymlinkResult::kError;
  }
  if (!IsAbsoluteNormalizedWindowsPath(symlink_target)) {
    if (error) {
      *error = MakeErrorMessage(
          WSTR(__FILE__), __LINE__, L"CreateSymlink", symlink_target,
          L"expected an absolute Windows path for symlink_target");
    }
    return CreateSymlinkResult::kError;
  }

  const wstring name = AddUncPrefixMaybe(symlink_name);
  const wstring target = AddUncPrefixMaybe(symlink_target);

  DWORD attrs = GetFileAttributesW(target.c_str());
  if ((attrs != INVALID_FILE_ATTRIBUTES) &&
      (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
    // Instead of creating a symlink to a directory use a Junction.
    return CreateSymlinkResult::kTargetIsDirectory;
  }

  if (!CreateSymbolicLinkW(name.c_str(), target.c_str(),
                           symlinkPrivilegeFlag)) {
    if (GetLastError() == ERROR_INVALID_PARAMETER) {
      // We are on a version of Windows that does not support this flag.
      // Retry without the flag and return to error handling if necessary.
      if (CreateSymbolicLinkW(name.c_str(), target.c_str(), 0)) {
        return CreateSymlinkResult::kSuccess;
      }
    }
    *error = MakeErrorMessage(
        WSTR(__FILE__), __LINE__, L"CreateSymlink", symlink_target,
        GetLastError() == ERROR_PRIVILEGE_NOT_HELD
            ? L"createSymbolicLinkW failed (permission denied). Either "
              "Windows developer mode or admin privileges are required."
            : L"createSymbolicLinkW failed");
    return CreateSymlinkResult::kError;
  }
  return CreateSymlinkResult::kSuccess;
}

int ReadSymlinkOrJunction(const wstring& path, wstring* result,
                          wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    if (error) {
      *error = MakeErrorMessage(
          WSTR(__FILE__), __LINE__, L"ReadSymlinkOrJunction", path,
          L"expected an absolute Windows path for 'path'");
    }
    return ReadSymlinkOrJunctionResult::kError;
  }

  AutoHandle handle(CreateFileW(
      AddUncPrefixMaybe(path).c_str(), 0,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
      OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS,
      nullptr));
  if (!handle.IsValid()) {
    DWORD err = GetLastError();
    if (err == ERROR_SHARING_VIOLATION) {
      // The path is held open by another process.
      return ReadSymlinkOrJunctionResult::kAccessDenied;
    } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
      // Path or a parent directory does not exist.
      return ReadSymlinkOrJunctionResult::kDoesNotExist;
    }

    // The path seems to exist yet we cannot open it for metadata-reading.
    // Report as much information as we have, then give up.
    if (error) {
      *error =
          MakeErrorMessage(WSTR(__FILE__), __LINE__, L"CreateFileW", path, err);
    }
    return ReadSymlinkOrJunctionResult::kError;
  }

  uint8_t raw_buf[MAXIMUM_REPARSE_DATA_BUFFER_SIZE];
  PREPARSE_DATA_BUFFER buf = reinterpret_cast<PREPARSE_DATA_BUFFER>(raw_buf);
  DWORD bytes_returned;
  if (!::DeviceIoControl(handle, FSCTL_GET_REPARSE_POINT, nullptr, 0, buf,
                         MAXIMUM_REPARSE_DATA_BUFFER_SIZE, &bytes_returned,
                         nullptr)) {
    DWORD err = GetLastError();
    if (err == ERROR_NOT_A_REPARSE_POINT) {
      return ReadSymlinkOrJunctionResult::kNotALink;
    }

    // Some unknown error occurred.
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeviceIoControl",
                                path, err);
    }
    return ReadSymlinkOrJunctionResult::kError;
  }

  switch (buf->ReparseTag) {
    case IO_REPARSE_TAG_SYMLINK: {
      wchar_t* p =
          (wchar_t*)(((uint8_t*)buf->SymbolicLinkReparseBuffer.PathBuffer) +
                     buf->SymbolicLinkReparseBuffer.SubstituteNameOffset);
      *result = wstring(p, buf->SymbolicLinkReparseBuffer.SubstituteNameLength /
                               sizeof(WCHAR));
      return ReadSymlinkOrJunctionResult::kSuccess;
    }
    case IO_REPARSE_TAG_MOUNT_POINT: {
      wchar_t* p =
          (wchar_t*)(((uint8_t*)buf->MountPointReparseBuffer.PathBuffer) +
                     buf->MountPointReparseBuffer.SubstituteNameOffset);
      *result = wstring(
          p, buf->MountPointReparseBuffer.SubstituteNameLength / sizeof(WCHAR));
      return ReadSymlinkOrJunctionResult::kSuccess;
    }
    case IO_REPARSE_TAG_PROJFS: {
      // Virtual File System for Git
      return ReadSymlinkOrJunctionResult::kNotALink;
    }
    default:
      return ReadSymlinkOrJunctionResult::kUnknownLinkType;
  }
}

struct DirectoryStatus {
  enum {
    kDoesNotExist = 0,
    kDirectoryEmpty = 1,
    kDirectoryNotEmpty = 2,
    kChildMarkedForDeletionExists = 3,
  };
};

// Check whether the directory and its child elements truly exist, or are marked
// for deletion. The result could be:
// 1. The give path doesn't exist
// 2. The directory is empty
// 3. The directory contains valid files or dirs, so not empty
// 4. The directory contains only files or dirs marked for deletion.
int CheckDirectoryStatus(const wstring& path) {
  static const wstring kDot(L".");
  static const wstring kDotDot(L"..");
  bool found_valid_file = false;
  bool found_child_marked_for_deletion = false;
  WIN32_FIND_DATAW metadata;
  HANDLE handle = ::FindFirstFileW((path + L"\\*").c_str(), &metadata);
  if (handle == INVALID_HANDLE_VALUE) {
    return DirectoryStatus::kDoesNotExist;
  }
  do {
    if (kDot != metadata.cFileName && kDotDot != metadata.cFileName) {
      std::wstring child = path + L"\\" + metadata.cFileName;
      DWORD attributes = GetFileAttributesW(child.c_str());
      if (attributes != INVALID_FILE_ATTRIBUTES) {
        // If there is a valid file under the directory,
        // then the directory is truely not empty.
        // We should just return kDirectoryNotEmpty.
        found_valid_file = true;
        break;
      } else {
        DWORD error_code = GetLastError();
        // If the file or directory is in deleting process,
        // GetFileAttributesW returns ERROR_ACCESS_DENIED,
        // If it's already deleted at the time we check,
        // GetFileAttributesW returns ERROR_FILE_NOT_FOUND.
        // If GetFileAttributesW fails with other reason, we consider there is a
        // valid file that we cannot open, thus return kDirectoryNotEmpty
        if (error_code != ERROR_ACCESS_DENIED &&
            error_code != ERROR_FILE_NOT_FOUND) {
          found_valid_file = true;
          break;
        } else if (error_code == ERROR_ACCESS_DENIED) {
          found_child_marked_for_deletion = true;
        }
      }
    }
  } while (::FindNextFileW(handle, &metadata));
  ::FindClose(handle);
  if (found_valid_file) {
    return DirectoryStatus::kDirectoryNotEmpty;
  }
  if (found_child_marked_for_deletion) {
    return DirectoryStatus::kChildMarkedForDeletionExists;
  }
  return DirectoryStatus::kDirectoryEmpty;
}

int GetResultFromErrorCode(const wchar_t* function_name, const wstring& path,
                           DWORD err, wstring* error) {
  if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
    // The file disappeared, or one of its parent directories disappeared,
    // or one of its parent directories is no longer a directory.
    return DeletePathResult::kDoesNotExist;
  }
  if (err == ERROR_ACCESS_DENIED || err == ERROR_SHARING_VIOLATION) {
    return DeletePathResult::kAccessDenied;
  }
  // Some unknown error occurred.
  if (error) {
     *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                               function_name, path, err);
  }
  return DeletePathResult::kError;
}

int DeletePath(const wstring& path, wstring* error) {
  if (!IsAbsoluteNormalizedWindowsPath(path)) {
    if (error) {
      *error = MakeErrorMessage(WSTR(__FILE__), __LINE__, L"DeletePath", path,
                                L"expected an absolute Windows path");
    }
    return DeletePathResult::kError;
  }

  const std::wstring winpath(AddUncPrefixMaybe(path));
  const wchar_t* wpath = winpath.c_str();

  DWORD attr = GetFileAttributesW(wpath);
  DWORD err;
  if (attr == INVALID_FILE_ATTRIBUTES) {
    return GetResultFromErrorCode(L"GetFileAttributesW", path,
                                  GetLastError(), error);
  }

  if (attr & FILE_ATTRIBUTE_READONLY) {
    // Remove the read-only attribute.
    attr &= ~FILE_ATTRIBUTE_READONLY;
    if (!SetFileAttributesW(wpath, attr)) {
      return GetResultFromErrorCode(L"SetFileAttributesW", path, GetLastError(),
                                    error);
    }
  }

  if (attr & FILE_ATTRIBUTE_DIRECTORY) {
    // It's a directory or a junction, RemoveDirectoryW should be used.
    //
    // Sometimes a deleted directory lingers in its parent dir
    // after the deleting handle has already been closed.
    // In this case we check the content of the parent directory,
    // if we don't find any valid file, we try to delete it again after 5 ms.
    // But we don't want to hang infinitely because another application
    // can hold the handle for a long time. So try at most 20 times,
    // which means a process time of 100-120ms.
    // Inspired by
    // https://github.com/Alexpux/Cygwin/commit/28fa2a72f810670a0562ea061461552840f5eb70
    // Useful link: https://stackoverflow.com/questions/31606978
    int count;
    for (count = 0; count < 20 && !RemoveDirectoryW(wpath); ++count) {
      // Failed to delete the directory.
      err = GetLastError();
      if (err == ERROR_SHARING_VIOLATION || err == ERROR_ACCESS_DENIED) {
        // The junction or directory is in use by another process, or we have
        // no permission to delete it.
        return DeletePathResult::kAccessDenied;
      } else if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
        // The directory or one of its parent directories disappeared or is no
        // longer a directory.
        return DeletePathResult::kDoesNotExist;
      } else if (err == ERROR_DIR_NOT_EMPTY) {
        // We got ERROR_DIR_NOT_EMPTY error, but maybe the child files and
        // dirs are already marked for deletion, let's check the status of the
        // child elements to see if we should retry the delete operation.
        switch (CheckDirectoryStatus(winpath)) {
          case DirectoryStatus::kDirectoryNotEmpty:
            // The directory is truely not empty.
            return DeletePathResult::kDirectoryNotEmpty;
          case DirectoryStatus::kDirectoryEmpty:
            // If no children are pending deletion then the directory is now
            // empty. We can try deleting it again without waiting.
            continue;
          case DirectoryStatus::kChildMarkedForDeletionExists:
            // If all child elements are marked for deletion, then wait 5 ms for
            // the system to delete the files and try deleting the directory
            // again.
            Sleep(5L);
            continue;
          case DirectoryStatus::kDoesNotExist:
            // This case should never happen, because ERROR_DIR_NOT_EMPTY
            // means the directory exists. But if it does happen, return an
            // error message.
            if (error) {
              *error =
                  MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                   L"RemoveDirectoryW", path, GetLastError());
            }
            return DeletePathResult::kError;
        }
      }

      // Some unknown error occurred.
      if (error) {
        *error = MakeErrorMessage(WSTR(__FILE__), __LINE__,
                                  L"RemoveDirectoryW", path, err);
      }
      return DeletePathResult::kError;
    }

    if (count == 20) {
      // After trying 20 times, the "deleted" sub-directories or files still
      // won't go away, so just return kDirectoryNotEmpty error.
      return DeletePathResult::kDirectoryNotEmpty;
    }
  } else {
    // It's a regular file or symlink, DeleteFileW should be used.
    if (!DeleteFileW(wpath)) {
      // Failed to delete the file or symlink.
      return GetResultFromErrorCode(L"DeleteFileW", path,
                                    GetLastError(), error);
    }
  }

  return DeletePathResult::kSuccess;
}

template <typename C>
std::basic_string<C> NormalizeImpl(const std::basic_string<C>& p) {
  if (p.empty()) {
    return p;
  }
  typedef std::basic_string<C> Str;
  static const Str kDot(1, '.');
  static const Str kDotDot(2, '.');
  std::vector<std::pair<typename Str::size_type, typename Str::size_type> >
      segments;
  typename Str::size_type seg_start = Str::npos;
  bool first = true;
  bool abs = false;
  bool starts_with_dot = false;
  for (typename Str::size_type i = HasUncPrefix(p.c_str()) ? 4 : 0;
       i <= p.size(); ++i) {
    if (seg_start == Str::npos) {
      if (i < p.size() && p[i] != '/' && p[i] != '\\') {
        seg_start = i;
      }
    } else {
      if (i == p.size() || (p[i] == '/' || p[i] == '\\')) {
        // The current character ends a segment.
        typename Str::size_type len = i - seg_start;
        if (first) {
          first = false;
          abs = len == 2 &&
                ((p[seg_start] >= 'A' && p[seg_start] <= 'Z') ||
                 (p[seg_start] >= 'a' && p[seg_start] <= 'z')) &&
                p[seg_start + 1] == ':';
          segments.push_back(std::make_pair(seg_start, len));
          starts_with_dot = !abs && p.compare(seg_start, len, kDot) == 0;
        } else {
          if (p.compare(seg_start, len, kDot) == 0) {
            if (segments.empty()) {
              // Retain "." if that is the first (and possibly only segment).
              segments.push_back(std::make_pair(seg_start, len));
              starts_with_dot = true;
            }
          } else {
            if (starts_with_dot) {
              // Delete the existing "." if that was the only path segment.
              segments.clear();
              starts_with_dot = false;
            }
            if (p.compare(seg_start, len, kDotDot) == 0) {
              if (segments.empty() ||
                  p.compare(segments.back().first, segments.back().second,
                            kDotDot) == 0) {
                // Preserve ".." if the path is relative and there are only ".."
                // segment(s) at the front.
                segments.push_back(std::make_pair(seg_start, len));
              } else if (!abs || segments.size() > 1) {
                // Remove the last segment unless the path is already at the
                // root directory.
                segments.pop_back();
              }  // Ignore ".." otherwise.
            } else {
              // This is a normal path segment, i.e. neither "." nor ".."
              segments.push_back(std::make_pair(seg_start, len));
            }
          }
        }
        // Indicate that there's no segment started.
        seg_start = Str::npos;
      }
    }
  }
  std::basic_stringstream<C> res;
  first = true;
  for (const auto& i : segments) {
    Str s = p.substr(i.first, i.second);
    if (first) {
      first = false;
    } else {
      res << '\\';
    }
    res << s;
  }
  if (abs && segments.size() == 1) {
    res << '\\';
  }
  return res.str();
}

std::string Normalize(const std::string& p) { return NormalizeImpl(p); }

std::wstring Normalize(const std::wstring& p) { return NormalizeImpl(p); }

bool GetCwd(std::wstring* result, DWORD* err_code) {
  // Maximum path is 32767 characters, with null terminator that is 0x8000.
  static constexpr DWORD kMaxPath = 0x8000;
  WCHAR buf[kMaxPath];
  DWORD len = GetCurrentDirectoryW(kMaxPath, buf);
  if (len > 0 && len < kMaxPath) {
    *result = buf;
    return true;
  } else {
    if (err_code) {
      *err_code = GetLastError();
    }
    return false;
  }
}

}  // namespace windows
}  // namespace bazel
