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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.unix.NativePosixFiles.Dirents;
import com.google.devtools.build.lib.unix.NativePosixFiles.ReadTypes;
import com.google.devtools.build.lib.unix.NativePosixFiles.StatErrorHandling;
import com.google.devtools.build.lib.util.Blocker;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.AbstractFileSystemWithCustomStat;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/** This class implements the FileSystem interface using direct calls to the UNIX filesystem. */
@ThreadSafe
public class UnixFileSystem extends AbstractFileSystemWithCustomStat {
  protected final String hashAttributeName;

  public UnixFileSystem(DigestHashFunction hashFunction, String hashAttributeName) {
    super(hashFunction);
    this.hashAttributeName = hashAttributeName;
  }

  public static Dirent.Type getDirentFromMode(int mode) {
    if (UnixFileStatus.isSpecialFile(mode)) {
      return Dirent.Type.UNKNOWN;
    } else if (UnixFileStatus.isFile(mode)) {
      return Dirent.Type.FILE;
    } else if (UnixFileStatus.isDirectory(mode)) {
      return Dirent.Type.DIRECTORY;
    } else if (UnixFileStatus.isSymbolicLink(mode)) {
      return Dirent.Type.SYMLINK;
    } else {
      return Dirent.Type.UNKNOWN;
    }
  }

  @Override
  protected Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    String name = path.getPathString();
    String[] entries;
    long startTime = Profiler.nanoTimeMaybe();
    try {
      entries = NativePosixFiles.readdir(name);
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_DIR, name);
    }
    return Arrays.asList(entries);
  }

  @Override
  @Nullable
  protected PathFragment resolveOneLink(PathFragment path) throws IOException {
    // Beware, this seemingly simple code belies the complex specification of
    // FileSystem.resolveOneLink().
    return stat(path, false).isSymbolicLink() ? readSymbolicLink(path) : null;
  }

  /**
   * Converts from {@link com.google.devtools.build.lib.unix.NativePosixFiles.Dirents.Type} to
   * {@link com.google.devtools.build.lib.vfs.Dirent.Type}.
   */
  private static Dirent.Type convertToDirentType(Dirents.Type type) {
    switch (type) {
      case FILE:
        return Dirent.Type.FILE;
      case DIRECTORY:
        return Dirent.Type.DIRECTORY;
      case SYMLINK:
        return Dirent.Type.SYMLINK;
      case UNKNOWN:
        return Dirent.Type.UNKNOWN;
      default:
        throw new IllegalArgumentException("Unknown type " + type);
    }
  }

  @Override
  protected Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
      throws IOException {
    String name = path.getPathString();
    long startTime = Profiler.nanoTimeMaybe();
    try {
      Dirents unixDirents =
          NativePosixFiles.readdir(name, followSymlinks ? ReadTypes.FOLLOW : ReadTypes.NOFOLLOW);
      Preconditions.checkState(unixDirents.hasTypes());
      List<Dirent> dirents = Lists.newArrayListWithCapacity(unixDirents.size());
      for (int i = 0; i < unixDirents.size(); i++) {
        dirents.add(
            new Dirent(unixDirents.getName(i), convertToDirentType(unixDirents.getType(i))));
      }
      return dirents;
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_DIR, name);
    }
  }

  @Override
  protected FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    String name = path.getPathString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return followSymlinks
          ? NativePosixFiles.stat(name, StatErrorHandling.ALWAYS_THROW)
          : NativePosixFiles.lstat(name, StatErrorHandling.ALWAYS_THROW);
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, name);
    }
  }

  // Like stat(), but returns null instead of throwing.
  // This is a performance optimization in the case where clients
  // catch and don't re-throw.
  @Override
  @Nullable
  protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
    String name = path.getPathString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return followSymlinks
          ? NativePosixFiles.stat(name, StatErrorHandling.NEVER_THROW)
          : NativePosixFiles.lstat(name, StatErrorHandling.NEVER_THROW);
    } catch (IOException e) {
      throw new IllegalStateException("unexpected exception", e);
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, name);
    }
  }

  @Override
  protected boolean exists(PathFragment path, boolean followSymlinks) {
    return statNullable(path, followSymlinks) != null;
  }

  /**
   * Return true iff the {@code stat} of {@code path} resulted in an {@code ENOENT} or {@code
   * ENOTDIR} error.
   */
  @Override
  @Nullable
  protected FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    String name = path.getPathString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return followSymlinks
          ? NativePosixFiles.stat(name, StatErrorHandling.THROW_UNLESS_NOT_FOUND)
          : NativePosixFiles.lstat(name, StatErrorHandling.THROW_UNLESS_NOT_FOUND);
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, name);
    }
  }

  @Override
  protected boolean isReadable(PathFragment path) throws IOException {
    return (stat(path, true).getPermissions() & 0400) != 0;
  }

  @Override
  protected boolean isWritable(PathFragment path) throws IOException {
    return (stat(path, true).getPermissions() & 0200) != 0;
  }

  @Override
  protected boolean isExecutable(PathFragment path) throws IOException {
    return (stat(path, true).getPermissions() & 0100) != 0;
  }

  /**
   * Adds or remove the bits specified in "permissionBits" to the permission mask of the file
   * specified by {@code path}. If the argument {@code add} is true, the specified permissions are
   * added, otherwise they are removed.
   *
   * @throws IOException if there was an error writing the file's metadata
   */
  private void modifyPermissionBits(PathFragment path, int permissionBits, boolean add)
      throws IOException {
    int oldMode = stat(path, true).getPermissions();
    int newMode = add ? (oldMode | permissionBits) : (oldMode & ~permissionBits);
    var comp = Blocker.begin();
    try {
      NativePosixFiles.chmod(path.toString(), newMode);
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  protected void setReadable(PathFragment path, boolean readable) throws IOException {
    modifyPermissionBits(path, 0400, readable);
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    modifyPermissionBits(path, 0200, writable);
  }

  @Override
  protected void setExecutable(PathFragment path, boolean executable) throws IOException {
    modifyPermissionBits(path, 0111, executable);
  }

  @Override
  protected void chmod(PathFragment path, int mode) throws IOException {
    var comp = Blocker.begin();
    try {
      NativePosixFiles.chmod(path.toString(), mode);
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  public boolean supportsModifications(PathFragment path) {
    return true;
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return true;
  }

  @Override
  public boolean supportsHardLinksNatively(PathFragment path) {
    return true;
  }

  @Override
  public boolean isFilePathCaseSensitive() {
    return true;
  }

  @Override
  public boolean createDirectory(PathFragment path) throws IOException {
    var comp = Blocker.begin();
    try {
      // Use 0777 so that the permissions can be overridden by umask(2).
      // Note: UNIX mkdir(2), FilesystemUtils.mkdir() and createDirectory all
      // have different ways of representing failure!
      if (NativePosixFiles.mkdir(path.toString(), 0777)) {
        return true; // successfully created
      }
    } finally {
      Blocker.end(comp);
    }

    // false => EEXIST: something is already in the way (file/dir/symlink)
    if (isDirectory(path, false)) {
      return false; // directory already existed
    } else {
      throw new IOException(path + " (File exists)");
    }
  }

  @Override
  protected boolean createWritableDirectory(PathFragment path) throws IOException {
    var comp = Blocker.begin();
    try {
      return NativePosixFiles.mkdirWritable(path.toString());
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    var comp = Blocker.begin();
    try {
      // Use 0777 so that the permissions can be overridden by umask(2).
      NativePosixFiles.mkdirs(path.toString(), 0777);
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  protected void createSymbolicLink(PathFragment linkPath, PathFragment targetFragment)
      throws IOException {
    var comp = Blocker.begin();
    try {
      NativePosixFiles.symlink(targetFragment.getSafePathString(), linkPath.toString());
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  protected PathFragment readSymbolicLink(PathFragment path) throws IOException {
    // Note that the default implementation of readSymbolicLinkUnchecked calls this method and thus
    // is optimal since we only make one system call in here.
    String name = path.toString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return PathFragment.create(NativePosixFiles.readlink(name));
    } catch (InvalidArgumentIOException e) {
      throw new NotASymlinkException(path, e);
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_READLINK, name);
    }
  }

  @Override
  public void renameTo(PathFragment sourcePath, PathFragment targetPath) throws IOException {
    var comp = Blocker.begin();
    try {
      NativePosixFiles.rename(sourcePath.toString(), targetPath.toString());
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    return stat(path, followSymlinks).getSize();
  }

  @Override
  protected boolean delete(PathFragment path) throws IOException {
    String name = path.toString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return NativePosixFiles.remove(name);
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_DELETE, name);
    }
  }

  @Override
  protected long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    return stat(path, followSymlinks).getLastModifiedTime();
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    var comp = Blocker.begin();
    try {
      NativePosixFiles.utimensat(path.toString(), newTime == Path.NOW_SENTINEL_TIME, newTime);
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  @Nullable
  public byte[] getxattr(PathFragment path, String name, boolean followSymlinks)
      throws IOException {
    String pathName = path.toString();
    long startTime = Profiler.nanoTimeMaybe();
    var comp = Blocker.begin();
    try {
      return followSymlinks
          ? NativePosixFiles.getxattr(pathName, name)
          : NativePosixFiles.lgetxattr(pathName, name);
    } catch (UnsupportedOperationException e) {
      // getxattr() syscall is not supported by the underlying filesystem (it returned ENOTSUP).
      // Per method contract, treat this as ENODATA.
      return null;
    } finally {
      Blocker.end(comp);
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_XATTR, pathName);
    }
  }

  @Override
  @Nullable
  protected byte[] getFastDigest(PathFragment path) throws IOException {
    // Attempt to obtain the digest from an extended attribute attached to the file. This is much
    // faster than reading and digesting the file's contents on the fly, especially for large files.
    return hashAttributeName.isEmpty() ? null : getxattr(path, hashAttributeName, true);
  }

  @Override
  protected byte[] getDigest(PathFragment path) throws IOException {
    String name = path.toString();
    long startTime = Profiler.nanoTimeMaybe();
    try {
      return super.getDigest(path);
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_MD5, name);
    }
  }

  @Override
  protected void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    var comp = Blocker.begin();
    try {
      NativePosixFiles.link(originalPath.toString(), linkPath.toString());
    } finally {
      Blocker.end(comp);
    }
  }

  @Override
  protected void deleteTreesBelow(PathFragment dir) throws IOException {
    if (isDirectory(dir, /*followSymlinks=*/ false)) {
      long startTime = Profiler.nanoTimeMaybe();
      var comp = Blocker.begin();
      try {
        NativePosixFiles.deleteTreesBelow(dir.toString());
      } finally {
        Blocker.end(comp);
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_DELETE, dir.toString());
      }
    }
  }

  @Override
  protected File getIoFile(PathFragment path) {
    return new File(StringEncoding.internalToPlatform(path.getPathString()));
  }

  @Override
  protected java.nio.file.Path getNioPath(PathFragment path) {
    return java.nio.file.Path.of(StringEncoding.internalToPlatform(path.getPathString()));
  }

  @Override
  protected InputStream createFileInputStream(PathFragment path) throws IOException {
    return new FileInputStream(StringEncoding.internalToPlatform(path.getPathString()));
  }

  protected OutputStream createFileOutputStream(PathFragment path, boolean append)
      throws FileNotFoundException {
    return createFileOutputStream(path, append, /* internal= */ false);
  }

  @Override
  protected OutputStream createFileOutputStream(PathFragment path, boolean append, boolean internal)
      throws FileNotFoundException {
    final String name = path.toString();
    if (!internal
        && profiler.isActive()
        && (profiler.isProfiling(ProfilerTask.VFS_WRITE)
            || profiler.isProfiling(ProfilerTask.VFS_OPEN))) {
      long startTime = Profiler.nanoTimeMaybe();
      var comp = Blocker.begin();
      try {
        return new ProfiledNativeFileOutputStream(NativePosixFiles.openWrite(name, append), name);
      } finally {
        Blocker.end(comp);
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_OPEN, name);
      }
    } else {
      var comp = Blocker.begin();
      try {
        return new NativeFileOutputStream(NativePosixFiles.openWrite(name, append));
      } finally {
        Blocker.end(comp);
      }
    }
  }

  private static class NativeFileOutputStream extends OutputStream {
    private final int fd;
    private boolean closed = false;

    NativeFileOutputStream(int fd) {
      this.fd = fd;
    }

    @Override
    protected void finalize() throws Throwable {
      close();
      super.finalize();
    }

    @Override
    public synchronized void close() throws IOException {
      if (!closed) {
        var comp = Blocker.begin();
        try {
          NativePosixFiles.close(fd, this);
          closed = true;
        } finally {
          Blocker.end(comp);
        }
      }
      super.close();
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) (b & 0xFF)});
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    @Override
    @SuppressWarnings(
        "UnsafeFinalization") // Finalizer invokes close; close and write are synchronized.
    public synchronized void write(byte[] b, int off, int len) throws IOException {
      if (closed) {
        throw new IOException("attempt to write to a closed Outputstream backed by a native file");
      }
      var comp = Blocker.begin();
      try {
        NativePosixFiles.write(fd, b, off, len);
      } finally {
        Blocker.end(comp);
      }
    }
  }

  private static final class ProfiledNativeFileOutputStream extends NativeFileOutputStream {
    private final String name;

    public ProfiledNativeFileOutputStream(int fd, String name) {
      super(fd);
      this.name = name;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
      long startTime = Profiler.nanoTimeMaybe();
      try {
        super.write(b, off, len);
      } finally {
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_WRITE, name);
      }
    }
  }
}
