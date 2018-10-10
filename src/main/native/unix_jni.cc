// Copyright 2014 The Bazel Authors. All rights reserved.
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

#include "src/main/native/unix_jni.h"

#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>
#include <utime.h>

#include <string>
#include <vector>

#include "src/main/native/macros.h"
#include "src/main/cpp/util/md5.h"
#include "src/main/cpp/util/port.h"

using blaze_util::Md5Digest;

////////////////////////////////////////////////////////////////////////
// Latin1 <--> java.lang.String conversion functions.
// Derived from similar routines in Sun JDK.  See:
// j2se/src/solaris/native/java/io/UnixFileSystem_md.c
// j2se/src/share/native/common/jni_util.c
//
// Like the Sun JDK in its usual configuration, we assume all UNIX
// filenames are Latin1 encoded.

/**
 * Returns a new Java String for the specified Latin1 characters.
 */
static jstring NewStringLatin1(JNIEnv *env, const char *str) {
    int len = strlen(str);
    jchar buf[512];
    jchar *str1;

    if (len > 512) {
      str1 = new jchar[len];
    } else {
      str1 = buf;
    }

    for (int i = 0; i < len ; i++) {
      str1[i] = (unsigned char) str[i];
    }
    jstring result = env->NewString(str1, len);
    if (str1 != buf) {
      delete[] str1;
    }
    return result;
}

static jfieldID String_coder_field;
static jfieldID String_value_field;

static bool CompactStringsEnabled(JNIEnv *env) {
  if (jclass klass = env->FindClass("java/lang/String")) {
    if (jfieldID csf = env->GetStaticFieldID(klass, "COMPACT_STRINGS", "Z")) {
      if (env->GetStaticBooleanField(klass, csf)) {
        if ((String_coder_field = env->GetFieldID(klass, "coder", "B"))) {
          if ((String_value_field = env->GetFieldID(klass, "value", "[B"))) {
            return true;
          }
        }
      }
    }
  }
  env->ExceptionClear();
  return false;
}

/**
 * Returns a nul-terminated Latin1-encoded byte array for the
 * specified Java string, or null on failure.  Unencodable characters
 * are replaced by '?'.  Must be followed by a call to
 * ReleaseStringLatin1Chars.
 */
static char *GetStringLatin1Chars(JNIEnv *env, jstring jstr) {
  jint len = env->GetStringLength(jstr);

  // Fast path for latin1 strings.
  static bool cs_enabled = CompactStringsEnabled(env);
  const int LATIN1 = 0;
  if (cs_enabled && env->GetByteField(jstr, String_coder_field) == LATIN1) {
    char *result = new char[len + 1];
    if (jobject jvalue = env->GetObjectField(jstr, String_value_field)) {
      env->GetByteArrayRegion((jbyteArray)jvalue, 0, len, (jbyte *)result);
    }
    result[len] = 0;
    return result;
  }

  const jchar *str = env->GetStringCritical(jstr, NULL);
  if (str == NULL) {
    return NULL;
  }

  char *result = new char[len + 1];
  for (int i = 0; i < len; i++) {
    jchar unicode = str[i];  // (unsigned)
    result[i] = unicode <= 0x00ff ? unicode : '?';
  }

  result[len] = 0;
  env->ReleaseStringCritical(jstr, str);
  return result;
}

/**
 * Release the Latin1 chars returned by a prior call to
 * GetStringLatin1Chars.
 */
static void ReleaseStringLatin1Chars(const char *s) {
  delete[] s;
}

////////////////////////////////////////////////////////////////////////

// See unix_jni.h.
void PostException(JNIEnv *env, int error_number, const std::string& message) {
  // Keep consistent with package-info.html!
  //
  // See /usr/include/asm/errno.h for UNIX error messages.
  // Select the most appropriate Java exception for a given UNIX error number.
  // (Consistent with errors generated by java.io package.)
  const char *exception_classname;
  switch (error_number) {
    case EFAULT:  // Illegal pointer--not likely
    case EBADF:   // Bad file number
      exception_classname = "java/lang/IllegalArgumentException";
      break;
    case ETIMEDOUT:  // Local socket timed out
      exception_classname = "java/net/SocketTimeoutException";
      break;
    case ENOENT:  // No such file or directory
      exception_classname = "java/io/FileNotFoundException";
      break;
    case EACCES:  // Permission denied
      exception_classname =
          "com/google/devtools/build/lib/vfs/FileAccessException";
      break;
    case EPERM:   // Operation not permitted
      exception_classname =
          "com/google/devtools/build/lib/unix/FilePermissionException";
      break;
    case EINTR:   // Interrupted system call
      exception_classname = "java/io/InterruptedIOException";
      break;
    case ENOMEM:  // Out of memory
      exception_classname = "java/lang/OutOfMemoryError";
      break;
    case ENOSYS:   // Function not implemented
    case ENOTSUP:  // Operation not supported on transport endpoint
                   // (aka EOPNOTSUPP)
      exception_classname = "java/lang/UnsupportedOperationException";
      break;
    case ENAMETOOLONG:  // File name too long
    case ENODATA:    // No data available
    case EINVAL:     // Invalid argument
    case EMULTIHOP:  // Multihop attempted
    case ENOLINK:    // Link has been severed
    case EIO:        // I/O error
    case EAGAIN:     // Try again
    case EFBIG:      // File too large
    case EPIPE:      // Broken pipe
    case ENOSPC:     // No space left on device
    case EXDEV:      // Cross-device link
    case EROFS:      // Read-only file system
    case EEXIST:     // File exists
    case EMLINK:     // Too many links
    case ELOOP:      // Too many symbolic links encountered
    case EISDIR:     // Is a directory
    case ENOTDIR:    // Not a directory
    case ENOTEMPTY:  // Directory not empty
    case EBUSY:      // Device or resource busy
    case ENFILE:     // File table overflow
    case EMFILE:     // Too many open files
    default:
      exception_classname = "java/io/IOException";
  }
  jclass exception_class = env->FindClass(exception_classname);
  if (exception_class != NULL) {
     env->ThrowNew(exception_class, message.c_str());
  } else {
    abort();  // panic!
  }
}

// Throws RuntimeExceptions for IO operations which fail unexpectedly.
// See package-info.html.
// Returns true iff an exception was thrown.
static bool PostRuntimeException(JNIEnv *env, int error_number,
                                 const char *file_path) {
  const char *exception_classname;
  switch (error_number) {
    case EFAULT:   // Illegal pointer--not likely
    case EBADF:    // Bad file number
      exception_classname = "java/lang/IllegalArgumentException";
      break;
    case ENOMEM:   // Out of memory
      exception_classname = "java/lang/OutOfMemoryError";
      break;
    case ENOTSUP:  // Operation not supported on transport endpoint
                   // (aka EOPNOTSUPP)
      exception_classname = "java/lang/UnsupportedOperationException";
      break;
    default:
      exception_classname = NULL;
  }

  if (exception_classname == NULL) {
    return false;
  }

  jclass exception_class = env->FindClass(exception_classname);
  if (exception_class != NULL) {
     std::string message(file_path);
     message += " (";
     message += ErrorMessage(error_number);
     message += ")";
     env->ThrowNew(exception_class, message.c_str());
     return true;
  } else {
    abort();  // panic!
    return false;  // Not reachable.
  }
}

// See unix_jni.h.
void PostFileException(JNIEnv *env, int error_number, const char *filename) {
  ::PostException(env, error_number,
                  std::string(filename) + " (" + ErrorMessage(error_number)
                  + ")");
}

// See unix_jni.h.
void PostSystemException(JNIEnv *env, int error_number, const char *function,
                         const char *name) {
  ::PostException(env, error_number, std::string(function) + "(" +
                                         std::string(name) + ")" + " (" +
                                         ErrorMessage(error_number) + ")");
}

// TODO(bazel-team): split out all the FileSystem class's native methods
// into a separate source file, fsutils.cc.

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_readlink(JNIEnv *env,
                                                     jclass clazz,
                                                     jstring path) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  char target[PATH_MAX] = "";
  jstring r = NULL;
  if (readlink(path_chars, target, arraysize(target)) == -1) {
    ::PostFileException(env, errno, path_chars);
  } else {
    r = NewStringLatin1(env, target);
  }
  ReleaseStringLatin1Chars(path_chars);
  return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_chmod(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path,
                                                  jint mode) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  if (chmod(path_chars, static_cast<int>(mode)) == -1) {
    ::PostFileException(env, errno, path_chars);
  }
  ReleaseStringLatin1Chars(path_chars);
}

static void link_common(JNIEnv *env,
                        jstring oldpath,
                        jstring newpath,
                        int (*link_function)(const char *, const char *)) {
  const char *oldpath_chars = GetStringLatin1Chars(env, oldpath);
  const char *newpath_chars = GetStringLatin1Chars(env, newpath);
  if (link_function(oldpath_chars, newpath_chars) == -1) {
    ::PostFileException(env, errno, newpath_chars);
  }
  ReleaseStringLatin1Chars(oldpath_chars);
  ReleaseStringLatin1Chars(newpath_chars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_link(JNIEnv *env,
                                                 jclass clazz,
                                                 jstring oldpath,
                                                 jstring newpath) {
  link_common(env, oldpath, newpath, ::link);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_symlink(JNIEnv *env,
                                                    jclass clazz,
                                                    jstring oldpath,
                                                    jstring newpath) {
  link_common(env, oldpath, newpath, ::symlink);
}

static jobject NewFileStatus(JNIEnv *env,
                             const portable_stat_struct &stat_ref) {
  static jclass file_status_class = NULL;
  if (file_status_class == NULL) {  // note: harmless race condition
    jclass local = env->FindClass("com/google/devtools/build/lib/unix/FileStatus");
    CHECK(local != NULL);
    file_status_class = static_cast<jclass>(env->NewGlobalRef(local));
  }

  static jmethodID method = NULL;
  if (method == NULL) {  // note: harmless race condition
    method = env->GetMethodID(file_status_class, "<init>", "(IIIIIIIJIJ)V");
    CHECK(method != NULL);
  }

  return env->NewObject(
      file_status_class, method, stat_ref.st_mode,
      StatSeconds(stat_ref, STAT_ATIME), StatNanoSeconds(stat_ref, STAT_ATIME),
      StatSeconds(stat_ref, STAT_MTIME), StatNanoSeconds(stat_ref, STAT_MTIME),
      StatSeconds(stat_ref, STAT_CTIME), StatNanoSeconds(stat_ref, STAT_CTIME),
      static_cast<jlong>(stat_ref.st_size),
      static_cast<int>(stat_ref.st_dev), static_cast<jlong>(stat_ref.st_ino));
}

static jobject NewErrnoFileStatus(JNIEnv *env,
                                  int saved_errno,
                                  const portable_stat_struct &stat_ref) {
  static jclass errno_file_status_class = NULL;
  if (errno_file_status_class == NULL) {  // note: harmless race condition
    jclass local = env->FindClass("com/google/devtools/build/lib/unix/ErrnoFileStatus");
    CHECK(local != NULL);
    errno_file_status_class = static_cast<jclass>(env->NewGlobalRef(local));
  }

  static jmethodID no_error_ctor = NULL;
  if (no_error_ctor == NULL) {  // note: harmless race condition
    no_error_ctor = env->GetMethodID(errno_file_status_class,
                                     "<init>", "(IIIIIIIJIJ)V");
    CHECK(no_error_ctor != NULL);
  }

  static jmethodID errorno_ctor = NULL;
  if (errorno_ctor == NULL) {  // note: harmless race condition
    errorno_ctor = env->GetMethodID(errno_file_status_class, "<init>", "(I)V");
    CHECK(errorno_ctor != NULL);
  }

  if (saved_errno != 0) {
    return env->NewObject(errno_file_status_class, errorno_ctor, saved_errno);
  }
  return env->NewObject(
      errno_file_status_class, no_error_ctor, stat_ref.st_mode,
      StatSeconds(stat_ref, STAT_ATIME), StatNanoSeconds(stat_ref, STAT_ATIME),
      StatSeconds(stat_ref, STAT_MTIME), StatNanoSeconds(stat_ref, STAT_MTIME),
      StatSeconds(stat_ref, STAT_CTIME), StatNanoSeconds(stat_ref, STAT_CTIME),
      static_cast<jlong>(stat_ref.st_size), static_cast<int>(stat_ref.st_dev),
      static_cast<jlong>(stat_ref.st_ino));
}

static void SetIntField(JNIEnv *env,
                        const jclass &clazz,
                        const jobject &object,
                        const char *name,
                        int val) {
  jfieldID fid = env->GetFieldID(clazz, name, "I");
  CHECK(fid != NULL);
  env->SetIntField(object, fid, val);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_ErrnoFileStatus_00024ErrnoConstants_initErrnoConstants(  // NOLINT
  JNIEnv *env, jobject errno_constants) {
  jclass clazz = env->GetObjectClass(errno_constants);
  SetIntField(env, clazz, errno_constants, "ENOENT", ENOENT);
  SetIntField(env, clazz, errno_constants, "EACCES", EACCES);
  SetIntField(env, clazz, errno_constants, "ELOOP", ELOOP);
  SetIntField(env, clazz, errno_constants, "ENOTDIR", ENOTDIR);
  SetIntField(env, clazz, errno_constants, "ENAMETOOLONG", ENAMETOOLONG);
}

static jobject StatCommon(JNIEnv *env,
                          jstring path,
                          int (*stat_function)(const char *, portable_stat_struct *),
                          bool should_throw) {
  portable_stat_struct statbuf;
  const char *path_chars = GetStringLatin1Chars(env, path);
  int r;
  int saved_errno = 0;
  while ((r = stat_function(path_chars, &statbuf)) == -1 && errno == EINTR) { }
  if (r == -1) {
    // Save errno immediately, before we do any other syscalls
    saved_errno = errno;

    // EACCES ENOENT ENOTDIR ELOOP -> IOException
    // ENAMETOOLONGEFAULT          -> RuntimeException
    // ENOMEM                      -> OutOfMemoryError

    if (PostRuntimeException(env, saved_errno, path_chars)) {
      ::ReleaseStringLatin1Chars(path_chars);
      return NULL;
    } else if (should_throw) {
      ::PostFileException(env, saved_errno, path_chars);
      ::ReleaseStringLatin1Chars(path_chars);
      return NULL;
    }
  }
  ::ReleaseStringLatin1Chars(path_chars);

  return should_throw
    ? NewFileStatus(env, statbuf)
    : NewErrnoFileStatus(env, saved_errno, statbuf);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    stat
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_stat(JNIEnv *env,
                                                 jclass clazz,
                                                 jstring path) {
  return ::StatCommon(env, path, portable_stat, true);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    lstat
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_lstat(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path) {
  return ::StatCommon(env, path, portable_lstat, true);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    statNullable
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_errnoStat(JNIEnv *env,
                                                      jclass clazz,
                                                      jstring path) {
  return ::StatCommon(env, path, portable_stat, false);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    lstatNullable
 * Signature: (Ljava/lang/String;)Lcom/google/devtools/build/lib/unix/FileStatus;
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_errnoLstat(JNIEnv *env,
                                                       jclass clazz,
                                                       jstring path) {
  return ::StatCommon(env, path, portable_lstat, false);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    utime
 * Signature: (Ljava/lang/String;ZII)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_utime(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path,
                                                  jboolean now,
                                                  jint modtime) {
  const char *path_chars = GetStringLatin1Chars(env, path);
#if defined(__linux) && (!defined(__GLIBC__) || __GLIBC_PREREQ(2, 6))
  struct timespec spec[2] = {{0, UTIME_OMIT}, {modtime, now ? UTIME_NOW : 0}};
  if (::utimensat(AT_FDCWD, path_chars, spec, 0) == -1) {
    ::PostFileException(env, errno, path_chars);
  }
#else
  struct utimbuf buf = { modtime, modtime };
  struct utimbuf *bufptr = now ? NULL : &buf;
  if (::utime(path_chars, bufptr) == -1) {
    // EACCES ENOENT EMULTIHOP ELOOP EINTR
    // ENOTDIR ENOLINK EPERM EROFS   -> IOException
    // EFAULT ENAMETOOLONG           -> RuntimeException
    ::PostFileException(env, errno, path_chars);
  }
#endif
  ReleaseStringLatin1Chars(path_chars);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    umask
 * Signature: (I)I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_umask(JNIEnv *env,
                                                  jclass clazz,
                                                  jint new_umask) {
  return ::umask(new_umask);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    mkdir
 * Signature: (Ljava/lang/String;I)Z
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_mkdir(JNIEnv *env,
                                                  jclass clazz,
                                                  jstring path,
                                                  jint mode) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  jboolean result = true;
  if (::mkdir(path_chars, mode) == -1) {
    // EACCES ENOENT ELOOP
    // ENOSPC ENOTDIR EPERM EROFS     -> IOException
    // EFAULT ENAMETOOLONG            -> RuntimeException
    // ENOMEM                         -> OutOfMemoryError
    // EEXIST                         -> return false
    if (errno == EEXIST) {
      result = false;
    } else {
      ::PostFileException(env, errno, path_chars);
    }
  }
  ReleaseStringLatin1Chars(path_chars);
  return result;
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    mkdirs
 * Signature: (Ljava/lang/String;I)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_mkdirs(JNIEnv *env,
                                                                jclass clazz,
                                                                jstring path,
                                                                int mode) {
  char *path_chars = GetStringLatin1Chars(env, path);
  portable_stat_struct statbuf;
  int len;
  char *p;

  // First, check if the directory already exists and early-out.
  if (portable_stat(path_chars, &statbuf) == 0) {
    if (!S_ISDIR(statbuf.st_mode)) {
      // Exists but is not a directory.
      ::PostFileException(env, ENOTDIR, path_chars);
    }
    goto cleanup;
  } else if (errno != ENOENT) {
    ::PostFileException(env, errno, path_chars);
    goto cleanup;
  }

  // Find the first directory that already exists and leave a pointer just past
  // it.
  len = strlen(path_chars);
  p = path_chars + len - 1;
  for (; p > path_chars; --p) {
    if (*p == '/') {
      *p = 0;
      int res = portable_stat(path_chars, &statbuf);
      *p = '/';
      if (res == 0) {
        // Exists and must be a directory, or the initial stat would have failed
        // with ENOTDIR.
        break;
      } else if (errno != ENOENT) {
        ::PostFileException(env, errno, path_chars);
        goto cleanup;
      }
    }
  }
  // p now points at the '/' after the last directory that exists.
  // Successively create each directory
  for (const char *end = path_chars + len; p < end; ++p) {
    if (*p == '/') {
      *p = 0;
      int res = ::mkdir(path_chars, mode);
      *p = '/';
      // EEXIST is fine, just means we're racing to create the directory.
      // Note that somebody could have raced to create a file here, but that
      // will get handled by a ENOTDIR by a subsequent mkdir call.
      if (res != 0 && errno != EEXIST) {
        ::PostFileException(env, errno, path_chars);
        goto cleanup;
      }
    }
  }
  if (::mkdir(path_chars, mode) != 0) {
    if (errno != EEXIST) {
      ::PostFileException(env, errno, path_chars);
      goto cleanup;
    }
    if (portable_stat(path_chars, &statbuf) != 0) {
      ::PostFileException(env, errno, path_chars);
      goto cleanup;
    }
    if (!S_ISDIR(statbuf.st_mode)) {
      // Exists but is not a directory.
      ::PostFileException(env, ENOTDIR, path_chars);
      goto cleanup;
    }
  }
cleanup:
  ReleaseStringLatin1Chars(path_chars);
}

static jobject NewDirents(JNIEnv *env,
                          jobjectArray names,
                          jbyteArray types) {
  // See http://java.sun.com/docs/books/jni/html/fldmeth.html#26855
  static jclass dirents_class = NULL;
  if (dirents_class == NULL) {  // note: harmless race condition
    jclass local = env->FindClass("com/google/devtools/build/lib/unix/NativePosixFiles$Dirents");
    CHECK(local != NULL);
    dirents_class = static_cast<jclass>(env->NewGlobalRef(local));
  }

  static jmethodID ctor = NULL;
  if (ctor == NULL) {  // note: harmless race condition
    ctor = env->GetMethodID(dirents_class, "<init>", "([Ljava/lang/String;[B)V");
    CHECK(ctor != NULL);
  }

  return env->NewObject(dirents_class, ctor, names, types);
}

static char GetDirentType(struct dirent *entry,
                          int dirfd,
                          bool follow_symlinks) {
  switch (entry->d_type) {
    case DT_REG:
      return 'f';
    case DT_DIR:
      return 'd';
    case DT_LNK:
      if (!follow_symlinks) {
        return 's';
      }
      FALLTHROUGH_INTENDED;
    case DT_UNKNOWN:
      portable_stat_struct statbuf;
      if (portable_fstatat(dirfd, entry->d_name, &statbuf, 0) == 0) {
        if (S_ISREG(statbuf.st_mode)) return 'f';
        if (S_ISDIR(statbuf.st_mode)) return 'd';
      }
      // stat failed or returned something weird; fall through
      FALLTHROUGH_INTENDED;
    default:
      return '?';
  }
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    readdir
 * Signature: (Ljava/lang/String;Z)Lcom/google/devtools/build/lib/unix/Dirents;
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_readdir(JNIEnv *env,
                                                    jclass clazz,
                                                    jstring path,
                                                    jchar read_types) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  DIR *dirh;
  while ((dirh = ::opendir(path_chars)) == NULL && errno == EINTR) { }
  if (dirh == NULL) {
    // EACCES EMFILE ENFILE ENOENT ENOTDIR -> IOException
    // ENOMEM                              -> OutOfMemoryError
    ::PostFileException(env, errno, path_chars);
  }
  ReleaseStringLatin1Chars(path_chars);
  if (dirh == NULL) {
    return NULL;
  }
  int fd = dirfd(dirh);

  std::vector<std::string> entries;
  std::vector<jbyte> types;
  for (;;) {
    // Clear errno beforehand.  Because readdir() is not required to clear it at
    // EOF, this is the only way to reliably distinguish EOF from error.
    errno = 0;
    struct dirent *entry = ::readdir(dirh);
    if (entry == NULL) {
      if (errno == 0) break;  // EOF
      // It is unclear whether an error can also skip some records.
      // That does not appear to happen with glibc, at least.
      if (errno == EINTR) continue;  // interrupted by a signal
      if (errno == EIO) continue;  // glibc returns this on transient errors
      // Otherwise, this is a real error we should report.
      ::PostFileException(env, errno, path_chars);
      ::closedir(dirh);
      return NULL;
    }
    // Omit . and .. from results.
    if (entry->d_name[0] == '.') {
      if (entry->d_name[1] == '\0') continue;
      if (entry->d_name[1] == '.' && entry->d_name[2] == '\0') continue;
    }
    entries.push_back(entry->d_name);
    if (read_types != 'n') {
      types.push_back(GetDirentType(entry, fd, read_types == 'f'));
    }
  }

  if (::closedir(dirh) < 0 && errno != EINTR) {
    ::PostFileException(env, errno, path_chars);
    return NULL;
  }

  size_t len = entries.size();
  jclass jlStringClass = env->GetObjectClass(path);
  jobjectArray names_obj = env->NewObjectArray(len, jlStringClass, NULL);
  if (names_obj == NULL && env->ExceptionOccurred()) {
    return NULL;  // async exception!
  }

  for (size_t ii = 0; ii < len; ++ii) {
    jstring s = NewStringLatin1(env, entries[ii].c_str());
    if (s == NULL && env->ExceptionOccurred()) {
      return NULL;  // async exception!
    }
    env->SetObjectArrayElement(names_obj, ii, s);
  }

  jbyteArray types_obj = NULL;
  if (read_types != 'n') {
    CHECK(len == types.size());
    types_obj = env->NewByteArray(len);
    CHECK(types_obj);
    if (len > 0) {
      env->SetByteArrayRegion(types_obj, 0, len, &types[0]);
    }
  }

  return NewDirents(env, names_obj, types_obj);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    rename
 * Signature: (Ljava/lang/String;Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_rename(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring oldpath,
                                                   jstring newpath) {
  const char *oldpath_chars = GetStringLatin1Chars(env, oldpath);
  const char *newpath_chars = GetStringLatin1Chars(env, newpath);
  if (::rename(oldpath_chars, newpath_chars) == -1) {
    // EISDIR EXDEV ENOTEMPTY EEXIST EBUSY
    // EINVAL EMLINK ENOTDIR EACCES EPERM
    // ENOENT EROFS ELOOP ENOSPC           -> IOException
    // EFAULT ENAMETOOLONG                 -> RuntimeException
    // ENOMEM                              -> OutOfMemoryError
    std::string filename(std::string(oldpath_chars) + " -> " + newpath_chars);
    ::PostFileException(env, errno, filename.c_str());
  }
  ReleaseStringLatin1Chars(oldpath_chars);
  ReleaseStringLatin1Chars(newpath_chars);
}

static bool delete_common(JNIEnv *env,
                          jstring path,
                          int (*delete_function)(const char *),
                          bool (*error_function)(int)) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  if (path_chars == NULL) {
      return false;
  }
  bool ok = delete_function(path_chars) != -1;
  if (!ok) {
    if (!error_function(errno)) {
      ::PostFileException(env, errno, path_chars);
    }
  }
  ReleaseStringLatin1Chars(path_chars);
  return ok;
}

static bool unlink_err(int err) { return err == ENOENT; }
static bool remove_err(int err) { return err == ENOENT || err == ENOTDIR; }

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    unlink
 * Signature: (Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT bool JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_unlink(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring path) {
  return ::delete_common(env, path, ::unlink, ::unlink_err);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    remove
 * Signature: (Ljava/lang/String;)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT bool JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_remove(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring path) {
  return ::delete_common(env, path, ::remove, ::remove_err);
}

/*
 * Class:     com.google.devtools.build.lib.unix.NativePosixFiles
 * Method:    mkfifo
 * Signature: (Ljava/lang/String;I)V
 * Throws:    java.io.IOException
 */
extern "C" JNIEXPORT void JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_mkfifo(JNIEnv *env,
                                                   jclass clazz,
                                                   jstring path,
                                                   jint mode) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  if (mkfifo(path_chars, mode) == -1) {
    ::PostFileException(env, errno, path_chars);
  }
  ReleaseStringLatin1Chars(path_chars);
}

////////////////////////////////////////////////////////////////////////
// Linux extended file attributes

typedef ssize_t getxattr_func(const char *path, const char *name,
                              void *value, size_t size, bool *attr_not_found);

static jbyteArray getxattr_common(JNIEnv *env,
                                  jstring path,
                                  jstring name,
                                  getxattr_func getxattr) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  const char *name_chars = GetStringLatin1Chars(env, name);

  // TODO(bazel-team): on ERANGE, try again with larger buffer.
  jbyte value[4096];
  jbyteArray result = NULL;
  bool attr_not_found = false;
  ssize_t size = getxattr(path_chars, name_chars, value, arraysize(value),
                          &attr_not_found);
  if (size == -1) {
    if (!attr_not_found) {
      ::PostFileException(env, errno, path_chars);
    }
  } else {
    result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, value);
  }
  ReleaseStringLatin1Chars(path_chars);
  ReleaseStringLatin1Chars(name_chars);
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_getxattr(JNIEnv *env,
                                                     jclass clazz,
                                                     jstring path,
                                                     jstring name) {
  return ::getxattr_common(env, path, name, ::portable_getxattr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_lgetxattr(JNIEnv *env,
                                                      jclass clazz,
                                                      jstring path,
                                                      jstring name) {
  return ::getxattr_common(env, path, name, ::portable_lgetxattr);
}


// Computes MD5 digest of "file", writes result in "result", which
// must be of length Md5Digest::kDigestLength.  Returns zero on success, or
// -1 (and sets errno) otherwise.
static int md5sumAsBytes(const char *file,
                         jbyte result[Md5Digest::kDigestLength]) {
  Md5Digest digest;
  // OPT: Using a 32k buffer would give marginally better performance,
  // but what is the stack size here?
  jbyte buf[8192];
  int fd;
  while ((fd = open(file, O_RDONLY)) == -1 && errno == EINTR) { }
  if (fd == -1) {
    return -1;
  }
  for (ssize_t len = read(fd, buf, arraysize(buf));
       len != 0;
       len = read(fd, buf, arraysize(buf))) {
    if (len == -1) {
      if (errno == EINTR) {
        continue;
      } else {
        int read_errno = errno;
        close(fd);  // prefer read() errors over close().
        errno = read_errno;
        return -1;
      }
    }
    digest.Update(buf, len);
  }
  if (close(fd) < 0 && errno != EINTR) {
    return -1;
  }
  digest.Finish(reinterpret_cast<unsigned char*>(result));
  return 0;
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixFiles_md5sumAsBytes(
    JNIEnv *env, jclass clazz, jstring path) {
  const char *path_chars = GetStringLatin1Chars(env, path);
  jbyte value[Md5Digest::kDigestLength];
  jbyteArray result = NULL;
  if (md5sumAsBytes(path_chars, value) == 0) {
    result = env->NewByteArray(Md5Digest::kDigestLength);
    env->SetByteArrayRegion(result, 0, Md5Digest::kDigestLength, value);
  } else {
    ::PostFileException(env, errno, path_chars);
  }
  ReleaseStringLatin1Chars(path_chars);
  return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_google_devtools_build_lib_unix_NativePosixSystem_sysctlbynameGetLong(
    JNIEnv *env, jclass clazz, jstring name) {
  const char *name_chars = GetStringLatin1Chars(env, name);
  long r;
  size_t len = sizeof(r);
  if (portable_sysctlbyname(name_chars, &r, &len) == -1) {
    ::PostSystemException(env, errno, "sysctlbyname", name_chars);
  }
  ReleaseStringLatin1Chars(name_chars);
  return (jlong)r;
}
