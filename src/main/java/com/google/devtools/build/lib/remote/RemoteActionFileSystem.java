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
//

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.stream;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStatusWithMetadata;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.AbstractFileSystemWithCustomStat;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.FileInfo;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryContentInfo;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * An action filesystem suitable for use when building with disk/remote caching or execution.
 *
 * <p>It acts as a union filesystem over three different sources:
 *
 * <ul>
 *   <li>The action input map, providing read-only in-memory access to the metadata (but not the
 *       contents) of the action's declared inputs.
 *   <li>The remote output tree, an in-memory filesystem providing read/write access to the metadata
 *       (but not the contents) of remotely stored files injected during action execution.
 *   <li>The local filesystem, providing read/write access to the metadata and contents of files
 *       residing on disk, including the inputs and outputs of local spawns.
 * </ul>
 *
 * <p>Generally speaking, file operations consult the underlying sources in that order and operate
 * on the first result found, although some (e.g. readdir) collate information from all sources. The
 * contents of remotely stored files are transparently downloaded when an operation requires them.
 *
 * <p>Special care must be taken with operations that follow symlinks, as the symlink and its target
 * path may reside on different sources, with an arbitrary number of indirections in between. This
 * is required because some actions (notably SymlinkAction) may materialize an output as a symlink
 * to an input. Most operations call resolveSymbolicLinks upfront (which is able to canonicalize
 * paths taking every source into account) and only then delegate to the underlying sources.
 *
 * <p>The implementation assumes that an action never modifies its input paths, but may otherwise
 * modify any path in the output tree. Concurrent operations are supported as long as they don't
 * affect filesystem structure (i.e., create, move or delete paths). Otherwise, they might fail or
 * produce inconsistent results. No effort is made to detect irreconcilable differences between
 * sources, such as the same path existing in multiple underlying sources with different type or
 * contents.
 */
public class RemoteActionFileSystem extends AbstractFileSystemWithCustomStat
    implements PathCanonicalizer.Resolver {
  private final PathFragment execRoot;
  private final PathFragment outputBase;
  private final InputMetadataProvider fileCache;
  private final ActionInputMap inputArtifactData;
  private final TreeArtifactDirectoryCache inputTreeArtifactDirectoryCache;
  private final PathCanonicalizer pathCanonicalizer;
  private final ImmutableMap<PathFragment, Artifact> outputMapping;
  private final RemoteActionInputFetcher inputFetcher;
  private final FileSystem localFs;
  private final RemoteInMemoryFileSystem remoteOutputTree;

  @Nullable private ActionExecutionMetadata action = null;

  /** Describes how to handle symlinks when calling {@link #statInternal}. */
  private enum FollowMode {
    /** Canonicalize the entire path. This is equivalent to {@link Symlinks.FOLLOW}. */
    FOLLOW_ALL,
    /** Canonicalize the parent path. This is equivalent to {@link Symlinks.NOFOLLOW}. */
    FOLLOW_PARENT,
    /** Do not canonicalize. This is only used internally to resolve symlinks efficiently. */
    FOLLOW_NONE
  };

  /** Describes which sources to consider when calling {@link #statInternal}. */
  private enum StatSources {
    /** Consider all sources (action input map, remote output tree and local filesystem). */
    ALL,
    /** Consider only in-memory sources (action input map and remote output tree). */
    IN_MEMORY_ONLY,
  }

  private static final FileStatus DIRECTORY_FILE_STATUS =
      new FileStatus() {
        @Override
        public boolean isFile() {
          return false;
        }

        @Override
        public boolean isDirectory() {
          return true;
        }

        @Override
        public boolean isSymbolicLink() {
          return false;
        }

        @Override
        public boolean isSpecialFile() {
          return false;
        }

        @Override
        public long getSize() {
          return 0;
        }

        @Override
        public long getLastModifiedTime() {
          throw new UnsupportedOperationException();
        }

        @Override
        public long getLastChangeTime() {
          throw new UnsupportedOperationException();
        }

        @Override
        public long getNodeId() {
          throw new UnsupportedOperationException();
        }
      };

  /**
   * Caches the contents of intermediate subdirectories of tree artifact inputs, to speed up {@link
   * #stat} and {@link #readdir} operations. Note that actions are not expected to modify their
   * inputs.
   *
   * <p>Safe for concurrent access.
   */
  private class TreeArtifactDirectoryCache {
    private final Set<SpecialArtifact> cachedTrees = new HashSet<>();
    private final HashMap<PathFragment, HashSet<Dirent>> dirToEntries = new HashMap<>();

    @Nullable
    public synchronized Collection<Dirent> get(PathFragment execPath) {
      ensureCached(execPath);
      return dirToEntries.get(execPath);
    }

    private void ensureCached(PathFragment execPath) {
      TreeArtifactValue treeMetadata = inputArtifactData.getTreeMetadataForPrefix(execPath);
      if (treeMetadata == null || treeMetadata.getChildren().isEmpty()) {
        return;
      }
      SpecialArtifact parent = Iterables.getFirst(treeMetadata.getChildren(), null).getParent();
      if (!cachedTrees.contains(parent)) {
        insertTree(treeMetadata);
        cachedTrees.add(parent);
      }
    }

    private void insertTree(TreeArtifactValue treeMetadata) {
      for (TreeFileArtifact child : treeMetadata.getChildren()) {
        insertChild(child);
      }
    }

    private void insertChild(TreeFileArtifact child) {
      PathFragment treeRoot = child.getParent().getExecPath();
      PathFragment path = child.getExecPath();

      while (!path.equals(treeRoot)) {
        PathFragment parentPath = path.getParentDirectory();
        String name = path.getBaseName();
        Dirent.Type type =
            path.equals(child.getExecPath()) ? Dirent.Type.FILE : Dirent.Type.DIRECTORY;

        HashSet<Dirent> entries =
            dirToEntries.computeIfAbsent(parentPath, unused -> new HashSet<>());

        if (!entries.add(new Dirent(name, type))) {
          // Avoid wasted work on common prefixes.
          break;
        }

        path = parentPath;
      }
    }
  }

  public RemoteActionFileSystem(
      FileSystem localFs,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputArtifacts,
      InputMetadataProvider fileCache,
      RemoteActionInputFetcher inputFetcher) {
    super(localFs.getDigestFunction());
    this.execRoot = checkNotNull(execRootFragment, "execRootFragment");
    this.outputBase = execRoot.getRelative(checkNotNull(relativeOutputPath, "relativeOutputPath"));
    this.inputArtifactData = checkNotNull(inputArtifactData, "inputArtifactData");
    this.inputTreeArtifactDirectoryCache = new TreeArtifactDirectoryCache();
    this.pathCanonicalizer = new PathCanonicalizer(this);
    this.outputMapping =
        stream(outputArtifacts).collect(toImmutableMap(Artifact::getExecPath, a -> a));
    this.fileCache = checkNotNull(fileCache, "fileCache");
    this.inputFetcher = checkNotNull(inputFetcher, "inputFetcher");
    this.localFs = checkNotNull(localFs, "localFs");
    this.remoteOutputTree = new RemoteInMemoryFileSystem(getDigestFunction());
  }

  @Override
  public boolean supportsModifications(PathFragment path) {
    return localFs.supportsModifications(path);
  }

  @Override
  public boolean supportsSymbolicLinksNatively(PathFragment path) {
    return localFs.supportsSymbolicLinksNatively(path);
  }

  @Override
  public boolean supportsHardLinksNatively(PathFragment path) {
    return localFs.supportsHardLinksNatively(path);
  }

  @Override
  public boolean isFilePathCaseSensitive() {
    return localFs.isFilePathCaseSensitive();
  }

  @VisibleForTesting
  protected RemoteInMemoryFileSystem getRemoteOutputTree() {
    return remoteOutputTree;
  }

  @VisibleForTesting
  protected FileSystem getLocalFileSystem() {
    return localFs;
  }

  /** Returns whether a path is stored remotely. Follows symlinks. */
  boolean isRemote(Path path) throws IOException {
    return isRemote(path.asFragment());
  }

  private boolean isRemote(PathFragment path) throws IOException {
    // Files in the local filesystem are non-remote by definition, so stat only in-memory sources.
    var status = statInternal(path, FollowMode.FOLLOW_ALL, StatSources.IN_MEMORY_ONLY);
    return status instanceof FileStatusWithMetadata fileStatusWithMetadata
        && fileStatusWithMetadata.getMetadata().isRemote();
  }

  public void updateContext(ActionExecutionMetadata action) {
    this.action = action;
  }

  void injectRemoteFile(PathFragment path, byte[] digest, long size, Instant expirationTime)
      throws IOException {
    if (!isOutput(path)) {
      return;
    }
    var metadata =
        FileArtifactValue.createForRemoteFileWithMaterializationData(
            digest, size, /* locationIndex= */ 1, expirationTime);
    remoteOutputTree.injectFile(path, metadata);
  }

  @Override
  public String getFileSystemType(PathFragment path) {
    return "remoteActionFS";
  }

  @Override
  protected Path resolveSymbolicLinks(PathFragment path) throws IOException {
    return getPath(pathCanonicalizer.resolveSymbolicLinks(path));
  }

  @Override
  @Nullable
  public PathFragment resolveOneLink(PathFragment path) throws IOException {
    // The base implementation attempts to readSymbolicLink first and falls back to stat, but that
    // unnecessarily allocates a NotASymlinkException in the overwhelmingly likely non-symlink case.
    // It's more efficient to stat unconditionally.
    //
    // The parent path has already been canonicalized, so FOLLOW_NONE is effectively the same as
    // FOLLOW_PARENT, but much more efficient as it doesn't call stat recursively. Likewise for
    // readSymbolicLinkInternal instead of readSymbolicLink.
    var stat = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.ALL);
    if (stat == null) {
      throw new FileNotFoundException(path.getPathString() + " (No such file or directory)");
    }
    return stat.isSymbolicLink() ? readSymbolicLinkInternal(path) : null;
  }

  // Like resolveSymbolicLinks(), except that only the parent path is canonicalized.
  private PathFragment resolveSymbolicLinksForParent(PathFragment path) throws IOException {
    PathFragment parentPath = path.getParentDirectory();
    if (parentPath != null) {
      return resolveSymbolicLinks(parentPath).asFragment().getChild(path.getBaseName());
    }
    return path;
  }

  @Override
  protected boolean delete(PathFragment path) throws IOException {
    try {
      path = resolveSymbolicLinksForParent(path);
    } catch (FileNotFoundException ignored) {
      // Failure to delete a nonexistent path is not an error.
      return false;
    }

    // No action implementations call renameTo concurrently with other filesystem operations, so
    // there's no risk of a race condition below.
    pathCanonicalizer.clearPrefix(path);

    boolean deleted = localFs.getPath(path).delete();
    if (isOutput(path)) {
      deleted = remoteOutputTree.getPath(path).delete() || deleted;
    }

    return deleted;
  }

  @Override
  protected InputStream getInputStream(PathFragment path) throws IOException {
    downloadFileIfRemote(path);
    // TODO(tjgq): Consider only falling back to the local filesystem for source (non-output) files.
    // See getMetadata() for why this isn't currently possible.
    return localFs.getPath(path).getInputStream();
  }

  @Override
  protected OutputStream getOutputStream(PathFragment path, boolean append, boolean internal)
      throws IOException {
    return localFs.getPath(path).getOutputStream(append, internal);
  }

  @Override
  protected SeekableByteChannel createReadWriteByteChannel(PathFragment path) throws IOException {
    return localFs.getPath(path).createReadWriteByteChannel();
  }

  @Override
  public void setLastModifiedTime(PathFragment path, long newTime) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();

    FileNotFoundException remoteException = null;
    try {
      // We can't set mtime for a remote file, set mtime of in-memory file node instead.
      remoteOutputTree.setLastModifiedTime(path, newTime);
    } catch (FileNotFoundException e) {
      remoteException = e;
    }

    FileNotFoundException localException = null;
    try {
      localFs.getPath(path).setLastModifiedTime(newTime);
    } catch (FileNotFoundException e) {
      localException = e;
    }

    if (remoteException == null || localException == null) {
      return;
    }

    localException.addSuppressed(remoteException);
    throw localException;
  }

  @Override
  public byte[] getxattr(PathFragment path, String name, boolean followSymlinks)
      throws IOException {
    return localFs
        .getPath(path)
        .getxattr(name, followSymlinks ? Symlinks.FOLLOW : Symlinks.NOFOLLOW);
  }

  @Override
  @Nullable
  protected byte[] getFastDigest(PathFragment path) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    // Try to obtain a fast digest through a stat. This is only possible for in-memory files.
    // The parent path has already been canonicalized by resolveSymbolicLinks, so FOLLOW_NONE is
    // effectively the same as FOLLOW_PARENT, but more efficient.
    var status = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.IN_MEMORY_ONLY);
    if (status instanceof FileStatusWithDigest fileStatusWithDigest) {
      return fileStatusWithDigest.getDigest();
    }
    return localFs.getPath(path).getFastDigest();
  }

  @Override
  protected byte[] getDigest(PathFragment path) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    // Try to obtain a fast digest through a stat. This is only possible for in-memory files.
    // The parent path has already been canonicalized by resolveSymbolicLinks, so FOLLOW_NONE is
    // effectively the same as FOLLOW_PARENT, but more efficient.
    var status = statInternal(path, FollowMode.FOLLOW_NONE, StatSources.IN_MEMORY_ONLY);
    if (status instanceof FileStatusWithDigest fileStatusWithDigest) {
      return fileStatusWithDigest.getDigest();
    }
    return localFs.getPath(path).getDigest();
  }

  @Override
  protected boolean isReadable(PathFragment path) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      return localFs.getPath(path).isReadable();
    } catch (FileNotFoundException e) {
      // Remote files are always readable since we can't control their permissions.
      return true;
    }
  }

  @Override
  protected boolean isWritable(PathFragment path) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      return localFs.getPath(path).isWritable();
    } catch (FileNotFoundException e) {
      // Remote files are always writable since we can't control their permissions.
      return true;
    }
  }

  @Override
  protected boolean isExecutable(PathFragment path) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      return localFs.getPath(path).isExecutable();
    } catch (FileNotFoundException e) {
      // Remote files are always executable since we can't control their permissions.
      return true;
    }
  }

  @Override
  protected void setReadable(PathFragment path, boolean readable) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      localFs.getPath(path).setReadable(readable);
    } catch (FileNotFoundException e) {
      // Intentionally ignored.
    }
  }

  @Override
  public void setWritable(PathFragment path, boolean writable) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      localFs.getPath(path).setWritable(writable);
    } catch (FileNotFoundException e) {
      // Intentionally ignored.
    }
  }

  @Override
  protected void setExecutable(PathFragment path, boolean executable) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      localFs.getPath(path).setExecutable(executable);
    } catch (FileNotFoundException e) {
      // Intentionally ignored.
    }
  }

  @Override
  protected void chmod(PathFragment path, int mode) throws IOException {
    path = resolveSymbolicLinks(path).asFragment();
    try {
      localFs.getPath(path).chmod(mode);
    } catch (FileNotFoundException e) {
      // Intentionally ignored.
    }
  }

  @Override
  protected PathFragment readSymbolicLink(PathFragment path) throws IOException {
    return readSymbolicLinkInternal(resolveSymbolicLinksForParent(path));
  }

  // Like readSymbolicLink(), except that the parent path is assumed to be already canonical.
  private PathFragment readSymbolicLinkInternal(PathFragment path) throws IOException {
    if (path.startsWith(execRoot)) {
      var execPath = path.relativeTo(execRoot);
      var metadata = inputArtifactData.getMetadata(execPath);
      if (metadata != null && metadata.getType().isSymlink()) {
        return PathFragment.create(metadata.getUnresolvedSymlinkTarget());
      }
      if (metadata != null) {
        // Other input artifacts are never symlinks.
        throw new NotASymlinkException(path);
      }
      if (inputTreeArtifactDirectoryCache.get(execPath) != null) {
        // Tree artifacts never contain symlinks.
        throw new NotASymlinkException(path);
      }
    }

    if (isOutput(path)) {
      try {
        return remoteOutputTree.getPath(path).readSymbolicLink();
      } catch (FileNotFoundException e) {
        // Intentionally ignored.
      }
    }

    return localFs.getPath(path).readSymbolicLink();
  }

  @Override
  protected void createSymbolicLink(PathFragment linkPath, PathFragment targetFragment)
      throws IOException {
    linkPath = resolveSymbolicLinksForParent(linkPath);

    if (isOutput(linkPath)) {
      remoteOutputTree.getPath(linkPath).createSymbolicLink(targetFragment);
    }

    localFs.getPath(linkPath).createSymbolicLink(targetFragment);
  }

  @Override
  protected long getLastModifiedTime(PathFragment path, boolean followSymlinks) throws IOException {
    FileStatus stat = stat(path, followSymlinks);
    return stat.getLastModifiedTime();
  }

  @Override
  protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
    FileStatus stat = stat(path, followSymlinks);
    return stat.getSize();
  }

  @Override
  protected boolean exists(PathFragment path, boolean followSymlinks) {
    try {
      return statIfFound(path, followSymlinks) != null;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  protected FileStatus stat(PathFragment path, boolean followSymlinks) throws IOException {
    FileStatus stat = statIfFound(path, followSymlinks);
    if (stat == null) {
      throw new FileNotFoundException(path.getPathString() + " (No such file or directory)");
    }
    return stat;
  }

  @Nullable
  @Override
  protected FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
    return statInternal(
        path, followSymlinks ? FollowMode.FOLLOW_ALL : FollowMode.FOLLOW_PARENT, StatSources.ALL);
  }

  @Nullable
  @Override
  protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
    try {
      return statIfFound(path, followSymlinks);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Internal stat implementation.
   *
   * @param path the path to stat
   * @param followMode whether and how to canonicalize the path
   * @param statSources which sources to consider
   * @return the file status on success, or null if the file was not found in any of the sources
   *     under consideration
   * @throws IOException if an error other than file not found occurred
   */
  @Nullable
  private FileStatus statInternal(PathFragment path, FollowMode followMode, StatSources statSources)
      throws IOException {
    // Canonicalize the path.
    try {
      if (followMode == FollowMode.FOLLOW_ALL) {
        path = resolveSymbolicLinks(path).asFragment();
      } else if (followMode == FollowMode.FOLLOW_PARENT) {
        PathFragment parent = path.getParentDirectory();
        if (parent != null) {
          path = resolveSymbolicLinks(parent).asFragment().getChild(path.getBaseName());
        }
      }
    } catch (FileNotFoundException e) {
      return null;
    }

    // Since the path has been canonicalized, the operations below never need to follow symlinks.

    if (path.startsWith(execRoot)) {
      var execPath = path.relativeTo(execRoot);
      var metadata = inputArtifactData.getMetadata(execPath);
      if (metadata != null) {
        return statFromMetadata(metadata);
      }
      if (inputTreeArtifactDirectoryCache.get(execPath) != null) {
        return DIRECTORY_FILE_STATUS;
      }
    }

    FileStatus stat = remoteOutputTree.statIfFound(path, /* followSymlinks= */ false);
    if (stat != null) {
      return stat;
    }

    if (statSources == StatSources.ALL) {
      return localFs.getPath(path).statIfFound(Symlinks.NOFOLLOW);
    }

    return null;
  }

  private static FileStatusWithMetadata statFromMetadata(FileArtifactValue m) {
    return new FileStatusWithMetadata() {
      @Override
      public byte[] getDigest() {
        return m.getDigest();
      }

      @Override
      public boolean isFile() {
        return m.getType().isFile();
      }

      @Override
      public boolean isDirectory() {
        return m.getType().isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
        return m.getType().isSymlink();
      }

      @Override
      public boolean isSpecialFile() {
        return m.getType().isSpecialFile();
      }

      @Override
      public long getSize() {
        return m.getSize();
      }

      @Override
      public long getLastModifiedTime() {
        return m.getModifiedTime();
      }

      @Override
      public long getLastChangeTime() {
        return m.getModifiedTime();
      }

      @Override
      public long getNodeId() {
        throw new UnsupportedOperationException("Cannot get node id for " + m);
      }

      @Override
      public FileArtifactValue getMetadata() {
        return m;
      }
    };
  }

  @Nullable
  @VisibleForTesting
  ActionInput getInput(String execPath) {
    ActionInput input = inputArtifactData.getInput(execPath);
    if (input != null) {
      return input;
    }
    input = outputMapping.get(PathFragment.create(execPath));
    if (input != null) {
      return input;
    }
    if (!isOutput(execRoot.getRelative(execPath))) {
      return fileCache.getInput(execPath);
    }
    return null;
  }

  @Nullable
  @VisibleForTesting
  FileArtifactValue getInputMetadata(ActionInput input) {
    return inputArtifactData.getInputMetadata(input);
  }

  private void downloadFileIfRemote(PathFragment path) throws IOException {
    if (!isRemote(path)) {
      return;
    }
    PathFragment execPath = path.relativeTo(execRoot);
    try {
      ActionInput input = getInput(execPath.getPathString());
      if (input == null) {
        // For undeclared outputs, getInput returns null as there's no artifact associated with the
        // path. Therefore, we synthesize one here just so we're able to call prefetchFiles.
        input = ActionInputHelper.fromPath(execPath);
      }
      getFromFuture(
          inputFetcher.prefetchFiles(
              action,
              ImmutableList.of(input),
              inputArtifactData,
              Priority.CRITICAL,
              Reason.INPUTS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(String.format("Received interrupt while fetching file '%s'", path), e);
    }
  }

  private boolean isOutput(PathFragment path) {
    return path.startsWith(outputBase);
  }

  @Override
  public void renameTo(PathFragment srcPath, PathFragment dstPath) throws IOException {
    srcPath = resolveSymbolicLinksForParent(srcPath);
    dstPath = resolveSymbolicLinksForParent(dstPath);

    checkArgument(isOutput(srcPath), "srcPath must be an output path");
    checkArgument(isOutput(dstPath), "dstPath must be an output path");

    // No action implementations call renameTo concurrently with other filesystem operations, so
    // there's no risk of a race condition below.
    pathCanonicalizer.clearPrefix(srcPath);
    pathCanonicalizer.clearPrefix(dstPath);

    FileNotFoundException remoteException = null;
    try {
      remoteOutputTree.renameTo(srcPath, dstPath);
    } catch (FileNotFoundException e) {
      remoteException = e;
    }

    FileNotFoundException localException = null;
    try {
      localFs.renameTo(srcPath, dstPath);
    } catch (FileNotFoundException e) {
      localException = e;
    }

    if (remoteException == null || localException == null) {
      return;
    }

    localException.addSuppressed(remoteException);
    throw localException;
  }

  @Override
  public void createDirectoryAndParents(PathFragment path) throws IOException {
    localFs.createDirectoryAndParents(path);
    if (isOutput(path)) {
      remoteOutputTree.createDirectoryAndParents(path);
    }
  }

  @CanIgnoreReturnValue
  @Override
  public boolean createDirectory(PathFragment path) throws IOException {
    boolean created = localFs.createDirectory(path);
    if (isOutput(path)) {
      created = remoteOutputTree.createDirectory(path) || created;
    }
    return created;
  }

  @Override
  protected Collection<String> getDirectoryEntries(PathFragment path) throws IOException {
    return getDirectoryContents(path, /* followSymlinks= */ false, Dirent::getName);
  }

  @Override
  protected Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
      throws IOException {
    return getDirectoryContents(path, followSymlinks, Function.identity());
  }

  private <T extends Comparable<T>> ImmutableSortedSet<T> getDirectoryContents(
      PathFragment path, boolean followSymlinks, Function<Dirent, T> transformer)
      throws IOException {
    path = resolveSymbolicLinks(path).asFragment();

    HashMap<String, Dirent> entries = new HashMap<>();
    boolean exists = false;

    if (path.startsWith(execRoot)) {
      var execPath = path.relativeTo(execRoot);
      Collection<Dirent> treeEntries = inputTreeArtifactDirectoryCache.get(execPath);
      if (treeEntries != null) {
        for (var entry : treeEntries) {
          entries.put(entry.getName(), entry);
        }
        exists = true;
      }
    }

    // Since actions are assumed not to modify their inputs, a directory belonging to an input tree
    // artifact cannot also contain an output, so we can safely skip the other sources.
    if (!exists) {
      if (isOutput(path)) {
        try {
          for (var entry : remoteOutputTree.getPath(path).readdir(Symlinks.NOFOLLOW)) {
            entry = maybeFollowSymlinkForDirent(path, entry, followSymlinks);
            entries.put(entry.getName(), entry);
          }
          exists = true;
        } catch (FileNotFoundException ignored) {
          // Will be rethrown below if directory does not exist in any of the sources.
        }
      }

      try {
        for (var entry : localFs.getPath(path).readdir(Symlinks.NOFOLLOW)) {
          entry = maybeFollowSymlinkForDirent(path, entry, followSymlinks);
          entries.put(entry.getName(), entry);
        }
        exists = true;
      } catch (FileNotFoundException ignored) {
        // Will be rethrown below if directory does not exist in any of the sources.
      }
    }

    if (!exists) {
      throw new FileNotFoundException(path.getPathString() + " (No such file or directory)");
    }

    // Sort entries to get a deterministic order.
    ImmutableSortedSet.Builder<T> builder = ImmutableSortedSet.naturalOrder();
    for (var entry : entries.values()) {
      builder.add(transformer.apply(entry));
    }
    return builder.build();
  }

  private Dirent maybeFollowSymlinkForDirent(
      PathFragment dirPath, Dirent entry, boolean followSymlinks) {
    if (!followSymlinks || !entry.getType().equals(Dirent.Type.SYMLINK)) {
      return entry;
    }
    PathFragment path = dirPath.getChild(entry.getName());
    FileStatus st = statNullable(path, /* followSymlinks= */ true);
    return new Dirent(entry.getName(), direntFromStat(st));
  }

  @Override
  protected void createFSDependentHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    // Only called by the AbstractFileSystem#createHardLink base implementation, overridden below.
    throw new UnsupportedOperationException();
  }

  @Override
  protected void createHardLink(PathFragment linkPath, PathFragment originalPath)
      throws IOException {
    localFs.getPath(linkPath).createHardLink(getPath(originalPath));
  }

  static class RemoteInMemoryFileSystem extends InMemoryFileSystem {

    public RemoteInMemoryFileSystem(DigestHashFunction hashFunction) {
      super(hashFunction);
    }

    @Override
    protected synchronized OutputStream getOutputStream(
        PathFragment path, boolean append, boolean internal) throws IOException {
      // To get an output stream from remote file, we need to first stage it.
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    protected FileInfo newFile(Clock clock, PathFragment path) {
      return new RemoteInMemoryFileInfo(clock);
    }

    protected void injectFile(PathFragment path, FileArtifactValue metadata) throws IOException {
      checkArgument(metadata.isRemote(), "metadata is not remote: %s", metadata);
      createDirectoryAndParents(path.getParentDirectory());
      InMemoryContentInfo node = getOrCreateWritableInode(path);
      // If a node already exists but is not a regular file, throw an error.
      if (!(node instanceof RemoteInMemoryFileInfo remoteInMemoryFileInfo)) {
        throw new IOException("Could not inject into " + node);
      }

      remoteInMemoryFileInfo.set(metadata);
    }

    // Override for access within this class
    @Nullable
    @Override
    protected FileStatus statNullable(PathFragment path, boolean followSymlinks) {
      return super.statNullable(path, followSymlinks);
    }
  }

  static class RemoteInMemoryFileInfo extends FileInfo implements FileStatusWithMetadata {
    private FileArtifactValue metadata;

    RemoteInMemoryFileInfo(Clock clock) {
      super(clock);
    }

    private void set(FileArtifactValue metadata) {
      this.metadata = metadata;
    }

    @Override
    public OutputStream getOutputStream(boolean append) throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public InputStream getInputStream() throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public SeekableByteChannel createReadWriteByteChannel() throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public byte[] getxattr(String name) throws IOException {
      throw new IllegalStateException("Shouldn't be called directly");
    }

    @Override
    public byte[] getFastDigest() {
      return metadata.getDigest();
    }

    @Override
    public byte[] getDigest() throws IOException {
      return metadata.getDigest();
    }

    @Override
    public long getSize() {
      return metadata.getSize();
    }

    @Override
    public FileArtifactValue getMetadata() {
      return metadata;
    }
  }
}
