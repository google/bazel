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
package com.google.devtools.build.lib.vfs;

import com.google.common.collect.Lists;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.util.StringEncoding;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A FileSystem that does not use any JNI and hence, does not require a shared library be present at
 * execution.
 *
 * <p>Note: Blaze profiler tasks are defined on the system call level - thus we do not distinguish
 * (from profiling perspective) between different methods on this class that end up doing stat()
 * system call - they all are associated with the VFS_STAT task.
 */
@ThreadSafe
public class JavaIoFileSystem extends AbstractFileSystemWithCustomStat {
  private static final LinkOption[] NO_LINK_OPTION = new LinkOption[0];
  // This isn't generally safe; we rely on the file system APIs not modifying the array.
  private static final LinkOption[] NOFOLLOW_LINKS_OPTION =
      new LinkOption[] { LinkOption.NOFOLLOW_LINKS };

  private final Clock clock;

  protected static final String ERR_IS_DIRECTORY = " (Is a directory)";
  protected static final String ERR_DIRECTORY_NOT_EMPTY = " (Directory not empty)";
  protected static final String ERR_FILE_EXISTS = " (File exists)";
  protected static final String ERR_NO_SUCH_FILE_OR_DIR = " (No such file or directory)";
  protected static final String ERR_NOT_A_DIRECTORY = " (Not a directory)";

  public JavaIoFileSystem(DigestHashFunction hashFunction) {
    super(hashFunction);
    this.clock = new JavaClock();
  }

  @Override
  protected File getIoFile(PathFragment path) {
    return new File(StringEncoding.internalToPlatform(path.getPathString()));
  }

  /**
   * Returns a {@link java.nio.file.Path} representing the same path as provided {@code path}.
   *
   * <p>Note: while it's possible to use {@link #getIoFile(PathFragment)} in combination with {@link
   * File#toPath()} to achieve essentially the same, using this method is preferable because it
   * avoids extra allocations and does not lose track of the underlying Java filesystem, which is
   * useful for some in-memory filesystem implementations like JimFS.
   */
  @Override
  protected java.nio.file.Path getNioPath(PathFragment path) {
    return Paths.get(StringEncoding.internalToPlatform(path.getPathString()));
  }

  private LinkOption[] linkOpts(boolean followSymlinks) {
    return followSymlinks ? NO_LINK_OPTION : NOFOLLOW_LINKS_OPTION;
  }

  @Override
  protected Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    File file = getIoFile(path);
    String[] entries;
    long startTime = Profiler.nanoTimeMaybe();
    try {
      entries = file.list();
      if (entries == null) {
        if (file.exists()) {
          throw new IOException(path + ERR_NOT_A_DIRECTORY);
        } else {
          throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
        }
      }
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_DIR, file.getPath());
    }
    return Lists.transform(Arrays.asList(entries), StringEncoding::platformToInternal);
  }

  @Override
  protected boolean exists(PathFragment path, boolean followSymlinks) {
    long startTime = Profiler.nanoTimeMaybe();
    try {
      java.nio.file.Path nioPath = getNioPath(path);
      return Files.exists(nioPath, linkOpts(followSymlinks));
    } catch (InvalidPathException e) {
      return false;
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, path.toString());
    }
  }

  @Override
  protected boolean isReadable(PathFragment path) throws IOException {
    File file = getIoFile(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      if (!file.exists()) {
        throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
      }
      return file.canRead();
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath());
    }
  }

  @Override
  protected boolean isWritable(PathFragment path) throws IOException {
    File file = getIoFile(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      if (!file.exists()) {
        if (linkExists(file)) {
          throw new IOException(path + ERR_PERMISSION_DENIED);
        } else {
          throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
        }
      }
      return file.canWrite();
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath());
    }
  }

  @Override
  protected boolean isExecutable(PathFragment path) throws IOException {
    File file = getIoFile(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      if (!file.exists()) {
        throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
      }
      return file.canExecute();
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath());
    }
  }

  @Override
  protected void setReadable(PathFragment path, boolean readable) throws IOException {
    File file = getIoFile(path);
    if (!file.exists()) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    if (!file.setReadable(readable) && readable) {
      throw new IOException(String.format("Failed to make %s readable", path));
    }
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    File file = getIoFile(path);
    if (!file.exists()) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    if (!file.setWritable(writable) && writable) {
      throw new IOException(String.format("Failed to make %s writable", path));
    }
  }

  @Override
  protected void setExecutable(PathFragment path, boolean executable) throws IOException {
    File file = getIoFile(path);
    if (!file.exists()) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    if (!file.setExecutable(executable) && executable) {
      throw new IOException(String.format("Failed to make %s executable", path));
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
    File file = getIoFile(path);
    if (file.mkdir()) {
      return true;
    }

    if (fileIsSymbolicLink(file)) {
      throw new IOException(path + ERR_FILE_EXISTS);
    }
    if (file.isDirectory()) {
      return false; // directory already existed
    } else if (file.exists()) {
      throw new IOException(path + ERR_FILE_EXISTS);
    } else if (!file.getParentFile().exists()) {
      throw new FileNotFoundException(path.getParentDirectory() + ERR_NO_SUCH_FILE_OR_DIR);
    }
    // Parent directory apparently exists - try to create our directory again.
    if (file.mkdir()) {
      return true; // Everything is fine finally.
    } else if (!file.getParentFile().canWrite()) {
      throw new FileAccessException(path + ERR_PERMISSION_DENIED);
    } else {
      // Parent exists, is writable, yet we can't create our directory.
      throw new FileNotFoundException(path.getParentDirectory() + ERR_NOT_A_DIRECTORY);
    }
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    java.nio.file.Path nioPath = getNioPath(path);
    try {
      Files.createDirectories(nioPath);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      // Files.createDirectories will handle this case normally, but if the existing
      // file is a symlink to a directory then it still throws. Swallow this.
      if (!isDirectory(path, /*followSymlinks=*/ true)) {
        throw e;
      }
    }
  }

  private boolean linkExists(File file) {
    String shortName = file.getName();
    File parentFile = file.getParentFile();
    if (parentFile == null) {
      return false;
    }
    String[] filenames = parentFile.list();
    if (filenames == null) {
      return false;
    }
    for (String name : filenames) {
      if (name.equals(shortName)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void createSymbolicLink(PathFragment linkPath, PathFragment targetFragment)
      throws IOException {
    java.nio.file.Path nioPath = getNioPath(linkPath);
    try {
      Files.createSymbolicLink(
          nioPath,
          Paths.get(StringEncoding.internalToPlatform(targetFragment.getSafePathString())));
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw new IOException(linkPath + ERR_FILE_EXISTS, e);
    } catch (java.nio.file.AccessDeniedException e) {
      throw new IOException(linkPath + ERR_PERMISSION_DENIED, e);
    } catch (java.nio.file.NoSuchFileException e) {
      throw new FileNotFoundException(linkPath + ERR_NO_SUCH_FILE_OR_DIR);
    }
  }

  @Override
  protected PathFragment readSymbolicLink(PathFragment path) throws IOException {
    java.nio.file.Path nioPath = getNioPath(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      String link = Files.readSymbolicLink(nioPath).toString();
      return PathFragment.create(StringEncoding.platformToInternal(link));
    } catch (java.nio.file.NotLinkException e) {
      throw new NotASymlinkException(path, e);
    } catch (java.nio.file.NoSuchFileException e) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_READLINK, path.getPathString());
    }
  }

  @Override
  public void renameTo(PathFragment sourcePath, PathFragment targetPath) throws IOException {
    java.nio.file.Path source = getNioPath(sourcePath);
    java.nio.file.Path target = getNioPath(targetPath);
    // Replace NIO exceptions with the types used by the native Unix filesystem implementation where
    // necessary.
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (NoSuchFileException originalException) {
      FileNotFoundException newException =
          new FileNotFoundException(originalException.getMessage() + ERR_NO_SUCH_FILE_OR_DIR);
      newException.initCause(originalException);
      throw newException;
    } catch (AccessDeniedException originalException) {
      FileAccessException newException =
          new FileAccessException(originalException.getMessage() + ERR_PERMISSION_DENIED);
      newException.initCause(originalException);
      throw newException;
    } catch (FileSystemException e) {
      // Rewrite exception messages to be identical to the ones produced by the native Unix
      // filesystem implementation. Bazel forces the root locale for the JVM, so the error messages
      // should be stable.
      String filesPart = sourcePath + " -> " + targetPath;
      throw switch (e.getReason()) {
        case "Directory not empty" -> new IOException(filesPart + ERR_DIRECTORY_NOT_EMPTY, e);
        case "Not a directory" -> new IOException(filesPart + ERR_NOT_A_DIRECTORY, e);
        case "Is a directory" -> new IOException(filesPart + ERR_IS_DIRECTORY, e);
        default -> e;
      };
    }
  }

  @Override
  protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    long startTime = Profiler.nanoTimeMaybe();
    try {
      return stat(path, followSymlinks).getSize();
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, path.getPathString());
    }
  }

  @Override
  protected boolean delete(PathFragment path) throws IOException {
    java.nio.file.Path nioPath = getNioPath(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      return Files.deleteIfExists(nioPath);
    } catch (java.nio.file.DirectoryNotEmptyException e) {
      throw new IOException(path.getPathString() + ERR_DIRECTORY_NOT_EMPTY, e);
    } catch (java.nio.file.AccessDeniedException e) {
      throw new IOException(path.getPathString() + ERR_PERMISSION_DENIED, e);
    } catch (java.nio.file.AtomicMoveNotSupportedException
        | java.nio.file.FileAlreadyExistsException
        | java.nio.file.FileSystemLoopException
        | java.nio.file.NoSuchFileException
        | java.nio.file.NotDirectoryException
        | java.nio.file.NotLinkException e) {
      // All known but unexpected subclasses of FileSystemException.
      throw new IOException(path.getPathString() + ": unexpected FileSystemException", e);
    } catch (java.nio.file.FileSystemException e) {
      // Files.deleteIfExists() throws FileSystemException on Linux if a path component is a file.
      // We caught all known subclasses of FileSystemException so `e` is either an unknown
      // subclass or it is indeed a "Not a directory" error. Non-English JDKs may use a different
      // error message than "Not a directory", so we should not look for that text. Checking the
      // parent directory if it's indeed a directory is unrealiable, because another process may
      // modify it concurrently... but we have no better choice.
      if (e.getClass().equals(java.nio.file.FileSystemException.class)
          && !nioPath.getParent().toFile().isDirectory()) {
        // Hopefully the try-block failed because a parent directory was in fact not a directory.
        // Theoretically it's possible that the try-block failed for some other reason and all
        // parent directories were indeed directories, but another process changed a parent
        // directory into a file after the try-block failed but before this catch-block started, and
        // we return false here losing the real exception in `e`, but we cannot know.
        return false;
      } else {
        throw new IOException(path.getPathString() + ": unexpected FileSystemException", e);
      }
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_DELETE, path.getPathString());
    }
  }

  @Override
  protected long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    File file = getIoFile(path);
    long startTime = Profiler.nanoTimeMaybe();
    try {
      return stat(path, followSymlinks).getLastModifiedTime();
    } finally {
      profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, file.getPath());
    }
  }

  protected boolean fileIsSymbolicLink(File file) {
    return Files.isSymbolicLink(file.toPath());
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    File file = getIoFile(path);
    if (!file.setLastModified(
        newTime == Path.NOW_SENTINEL_TIME ? clock.currentTimeMillis() : newTime)) {
      if (!file.exists()) {
        throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
      } else if (!file.getParentFile().canWrite()) {
        throw new FileAccessException(path.getParentDirectory() + ERR_PERMISSION_DENIED);
      } else {
        throw new FileAccessException(path + ERR_PERMISSION_DENIED);
      }
    }
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

  /**
   * Returns the status of a file. See {@link Path#stat(Symlinks)} for specification.
   *
   * <p>The default implementation of this method is a "lazy" one, based on other accessor methods
   * such as {@link #isFile}, etc. Subclasses may provide more efficient specializations. However,
   * we still try to follow Unix-like semantics of failing fast in case of non-existent files (or in
   * case of permission issues).
   */
  @Override
  protected FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    java.nio.file.Path nioPath = getNioPath(path);
    final BasicFileAttributes attributes;
    try {
      attributes =
          Files.readAttributes(nioPath, BasicFileAttributes.class, linkOpts(followSymlinks));
    } catch (java.nio.file.FileSystemException e) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    FileStatus status =
        new FileStatus() {
          @Override
          public boolean isFile() {
            return attributes.isRegularFile() || isSpecialFile();
          }

          @Override
          public boolean isSpecialFile() {
            return attributes.isOther();
          }

          @Override
          public boolean isDirectory() {
            return attributes.isDirectory();
          }

          @Override
          public boolean isSymbolicLink() {
            return attributes.isSymbolicLink();
          }

          @Override
          public long getSize() {
            return attributes.size();
          }

          @Override
          public long getLastModifiedTime() {
            return attributes.lastModifiedTime().toMillis();
          }

          @Override
          public long getLastChangeTime() {
            // This is the best we can do with Java NIO...
            return attributes.lastModifiedTime().toMillis();
          }

          @Override
          public long getNodeId() {
            // TODO(bazel-team): Consider making use of attributes.fileKey().
            return -1;
          }
        };

    return status;
  }

  @Override
  @Nullable
  protected FileStatus statIfFound(PathFragment path, boolean followSymlinks) {
    try {
      return stat(path, followSymlinks);
    } catch (FileNotFoundException e) {
      // JavaIoFileSystem#stat (incorrectly) only throws FileNotFoundException (because it calls
      // #getLastModifiedTime, which can only throw a FileNotFoundException), so we always hit this
      // codepath. Thus, this method will incorrectly not throw an exception for some filesystem
      // errors.
      return null;
    } catch (IOException e) {
      // If this codepath is ever hit, then this method should be rewritten to properly distinguish
      // between not-found exceptions and others.
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    Files.createLink(getNioPath(linkPath), getNioPath(originalPath));
  }
}
