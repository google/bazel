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
package com.google.devtools.build.lib.unix;

import com.google.devtools.build.lib.vfs.FileStatus;

/**
 * An implementation of {@link FileStatus} backed by the result of a stat(2) system call.
 *
 * <p>This class is optimized for memory usage. Fields not required by Bazel are omitted.
 */
public final class UnixFileStatus implements FileStatus {

  private final int mode;
  private final long mtime; // milliseconds since Unix epoch
  private final long ctime; // milliseconds since Unix epoch
  private final long size;
  private final long ino;

  /** Constructs a UnixFileStatus instance. (Called only from ErrnoUnixFileStatus and JNI code.) */
  UnixFileStatus(int mode, long mtime, long ctime, long size, long ino) {
    this.mode = mode;
    this.mtime = mtime;
    this.ctime = ctime;
    this.size = size;
    this.ino = ino;
  }

  @Override
  public long getNodeId() {
    // TODO(tjgq): Consider deriving this value from both st_dev and st_ino.
    return ino;
  }

  @Override
  public boolean isFile() {
    return isFile(mode);
  }

  public static boolean isFile(int mode) {
    int type = mode & S_IFMT;
    return type == S_IFREG || isSpecialFile(mode);
  }

  @Override
  public boolean isSpecialFile() {
    return isSpecialFile(mode);
  }

  public static boolean isSpecialFile(int mode) {
    int type = mode & S_IFMT;
    return type == S_IFSOCK || type == S_IFBLK || type == S_IFCHR || type == S_IFIFO;
  }

  @Override
  public boolean isDirectory() {
    return isDirectory(mode);
  }

  public static boolean isDirectory(int mode) {
    int type = mode & S_IFMT;
    return type == S_IFDIR;
  }

  @Override
  public boolean isSymbolicLink() {
    return isSymbolicLink(mode);
  }

  public static boolean isSymbolicLink(int mode) {
    int type = mode & S_IFMT;
    return type == S_IFLNK;
  }

  @Override
  public int getPermissions() {
    return mode & S_IRWXA;
  }

  @Override
  public long getSize() {
    return size;
  }

  @Override
  public long getLastModifiedTime() {
    return mtime;
  }

  @Override
  public long getLastChangeTime() {
    return ctime;
  }

  ////////////////////////////////////////////////////////////////////////

  @Override
  public String toString() {
    return String.format(
        "UnixFileStatus(mode=0%06o,mtime=%d,ctime=%d,size=%d,ino=%d)",
        mode, mtime, ctime, size, ino);
  }

  ////////////////////////////////////////////////////////////////////////
  // Platform-specific details. These fields are public so that they can
  // be used from other packages. See POSIX and/or Linux manuals for details.
  //
  // These need to be kept in sync with the native code and system call
  // interface.  (The unit tests ensure that.)  Of course, this decoding could
  // be done in the JNI code to ensure maximum portability, but (a) we don't
  // expect we'll need that any time soon, and (b) that would require eager
  // rather than on-demand bitmunging of all attributes.  In any case, it's not
  // part of the interface so it can be easily changed later if necessary.

  public static final int S_IFMT =   0170000; // mask: filetype bitfields
  public static final int S_IFSOCK = 0140000; // socket
  public static final int S_IFLNK =  0120000; // symbolic link
  public static final int S_IFREG =  0100000; // regular file
  public static final int S_IFBLK =  0060000; // block device
  public static final int S_IFDIR =  0040000; // directory
  public static final int S_IFCHR =  0020000; // character device
  public static final int S_IFIFO =  0010000; // fifo
  public static final int S_ISUID =  0004000; // set UID bit
  public static final int S_ISGID =  0002000; // set GID bit (see below)
  public static final int S_ISVTX =  0001000; // sticky bit (see below)
  public static final int S_IRWXA =  00777; // mask: all permissions
  public static final int S_IRWXU =  00700; // mask: file owner permissions
  public static final int S_IRUSR =  00400; // owner has read permission
  public static final int S_IWUSR =  00200; // owner has write permission
  public static final int S_IXUSR =  00100; // owner has execute permission
  public static final int S_IRWXG =  00070; // mask: group permissions
  public static final int S_IRGRP =  00040; // group has read permission
  public static final int S_IWGRP =  00020; // group has write permission
  public static final int S_IXGRP =  00010; // group has execute permission
  public static final int S_IRWXO =  00007; // mask: other permissions
  public static final int S_IROTH =  00004; // others have read permission
  public static final int S_IWOTH =  00002; // others have write permisson
  public static final int S_IXOTH = 00001; // others have execute permission
  public static final int S_IEXEC = 00111; // owner, group, world execute
}
