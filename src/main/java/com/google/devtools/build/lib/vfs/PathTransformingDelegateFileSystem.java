// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.vfs;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.Collection;

/**
 * FileSystem implementation which delegates all operations to a provided instance with a
 * transformed path.
 *
 * <p>Please consider using {@link DelegateFileSystem} if you don't need to transform the paths.
 */
public abstract class PathTransformingDelegateFileSystem extends FileSystem {

  protected final FileSystem delegateFs;

  public PathTransformingDelegateFileSystem(FileSystem delegateFs) {
    super(Preconditions.checkNotNull(delegateFs, "delegateFs").getDigestFunction());
    this.delegateFs = delegateFs;
  }

  @VisibleForTesting
  public FileSystem getDelegateFs() {
    return delegateFs;
  }

  @Override
  public boolean supportsModifications(PathFragment path) {
    return delegateFs.supportsModifications(toDelegatePath(path));
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return delegateFs.supportsSymbolicLinksNatively(toDelegatePath(path));
  }

  @Override
  public boolean supportsHardLinksNatively(PathFragment path) {
    return delegateFs.supportsHardLinksNatively(toDelegatePath(path));
  }

  @Override
  public boolean isFilePathCaseSensitive() {
    return delegateFs.isFilePathCaseSensitive();
  }

  @Override
  public boolean createDirectory(PathFragment path) throws IOException {
    return delegateFs.createDirectory(toDelegatePath(path));
  }

  @Override
  protected boolean createWritableDirectory(PathFragment path) throws IOException {
    return delegateFs.createWritableDirectory(toDelegatePath(path));
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    delegateFs.createDirectoryAndParents(toDelegatePath(path));
  }

  @Override
  protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    return delegateFs.getFileSize(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected boolean delete(PathFragment path) throws IOException {
    return delegateFs.delete(toDelegatePath(path));
  }

  @Override
  protected long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    return delegateFs.getLastModifiedTime(toDelegatePath(path), followSymlinks);
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    delegateFs.setLastModifiedTime(toDelegatePath(path), newTime);
  }

  @Override
  protected boolean isSymbolicLink(PathFragment path) {
    return delegateFs.isSymbolicLink(toDelegatePath(path));
  }

  @Override
  protected boolean isDirectory(PathFragment path, boolean followSymlinks) {
    return delegateFs.isDirectory(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected boolean isFile(PathFragment path, boolean followSymlinks) {
    return delegateFs.isFile(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected boolean isSpecialFile(PathFragment path, boolean followSymlinks) {
    return delegateFs.isSpecialFile(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected void createSymbolicLink(PathFragment linkPath, PathFragment targetFragment)
      throws IOException {
    delegateFs.createSymbolicLink(toDelegatePath(linkPath), targetFragment);
  }

  @Override
  protected PathFragment readSymbolicLink(PathFragment path) throws IOException {
    return fromDelegatePath(delegateFs.readSymbolicLink(toDelegatePath(path)));
  }

  @Override
  protected boolean exists(PathFragment path, boolean followSymlinks) {
    return delegateFs.exists(toDelegatePath(path), followSymlinks);
  }

  @Override
  public boolean exists(PathFragment path) {
    return delegateFs.exists(toDelegatePath(path));
  }

  @Override
  protected Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    return delegateFs.getDirectoryEntries(toDelegatePath(path));
  }

  @Override
  protected boolean isReadable(PathFragment path) throws IOException {
    return delegateFs.isReadable(toDelegatePath(path));
  }

  @Override
  protected void setReadable(PathFragment path, boolean readable) throws IOException {
    delegateFs.setReadable(toDelegatePath(path), readable);
  }

  @Override
  protected boolean isWritable(PathFragment path) throws IOException {
    return delegateFs.isWritable(toDelegatePath(path));
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    delegateFs.setWritable(toDelegatePath(path), writable);
  }

  @Override
  protected boolean isExecutable(PathFragment path) throws IOException {
    return delegateFs.isExecutable(toDelegatePath(path));
  }

  @Override
  protected void setExecutable(PathFragment path, boolean executable) throws IOException {
    delegateFs.setExecutable(toDelegatePath(path), executable);
  }

  @Override
  protected InputStream getInputStream(PathFragment path) throws IOException {
    return delegateFs.getInputStream(toDelegatePath(path));
  }

  @Override
  protected SeekableByteChannel createReadWriteByteChannel(PathFragment path) throws IOException {
    return delegateFs.createReadWriteByteChannel(toDelegatePath(path));
  }

  @Override
  protected OutputStream getOutputStream(PathFragment path, boolean append, boolean internal)
      throws IOException {
    return delegateFs.getOutputStream(toDelegatePath(path), append, internal);
  }

  @Override
  public void renameTo(PathFragment sourcePath, PathFragment targetPath) throws IOException {
    delegateFs.renameTo(toDelegatePath(sourcePath), toDelegatePath(targetPath));
  }

  @Override
  protected void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    delegateFs.createFSDependentHardLink(toDelegatePath(linkPath), toDelegatePath(originalPath));
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    return delegateFs.getFileSystemType(toDelegatePath(path));
  }

  @Override
  protected void deleteTree(PathFragment path) throws IOException {
    delegateFs.deleteTree(toDelegatePath(path));
  }

  @Override
  protected void deleteTreesBelow(PathFragment dir) throws IOException {
    delegateFs.deleteTreesBelow(toDelegatePath(dir));
  }

  @Override
  public byte[] getxattr(PathFragment path, String name, boolean followSymlinks)
      throws IOException {
    return delegateFs.getxattr(toDelegatePath(path), name, followSymlinks);
  }

  @Override
  protected byte[] getFastDigest(PathFragment path) throws IOException {
    return delegateFs.getFastDigest(toDelegatePath(path));
  }

  @Override
  protected byte[] getDigest(PathFragment path) throws IOException {
    return delegateFs.getDigest(toDelegatePath(path));
  }

  @Override
  protected PathFragment resolveOneLink(PathFragment path) throws IOException {
    return delegateFs.resolveOneLink(toDelegatePath(path));
  }

  @Override
  protected Path resolveSymbolicLinks(PathFragment path) throws IOException {
    return getPath(
        fromDelegatePath(delegateFs.resolveSymbolicLinks(toDelegatePath(path)).asFragment()));
  }

  @Override
  protected FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    return delegateFs.stat(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
    return delegateFs.statNullable(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    return delegateFs.statIfFound(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected PathFragment readSymbolicLinkUnchecked(PathFragment path) throws IOException {
    return delegateFs.readSymbolicLinkUnchecked(toDelegatePath(path));
  }

  @Override
  protected Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
      throws IOException {
    return delegateFs.readdir(toDelegatePath(path), followSymlinks);
  }

  @Override
  protected void chmod(PathFragment path, int mode) throws IOException {
    delegateFs.chmod(toDelegatePath(path), mode);
  }

  @Override
  protected void createHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    delegateFs.createHardLink(toDelegatePath(linkPath), toDelegatePath(originalPath));
  }

  @Override
  protected void prefetchPackageAsync(PathFragment path, int maxDirs) {
    delegateFs.prefetchPackageAsync(toDelegatePath(path), maxDirs);
  }

  @Override
  protected File getIoFile(PathFragment path) {
    return delegateFs.getIoFile(toDelegatePath(path));
  }

  @Override
  protected java.nio.file.Path getNioPath(PathFragment path) {
    return delegateFs.getNioPath(toDelegatePath(path));
  }

  /** Transform original path to a different one to be used with the {@code delegateFs}. */
  protected abstract PathFragment toDelegatePath(PathFragment path);

  /**
   * Transform a path from one to be used with {@code delegateFs} to original one.
   *
   * <p>We expect that for each {@code path}: {@code
   * fromDelegatePath(toDelegatePath(path)).equals(path)}.
   */
  protected abstract PathFragment fromDelegatePath(PathFragment delegatePath);
}
