// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileStateValue.RegularFileStateValueWithContentsProxy;
import com.google.devtools.build.lib.actions.FileStateValue.RegularFileStateValueWithDigest;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.io.FileSymlinkException;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.FileType;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** A {@link SkyFunction} to build {@link RecursiveFilesystemTraversalValue}s. */
public final class RecursiveFilesystemTraversalFunction implements SkyFunction {
  private static final byte[] MISSING_FINGERPRINT =
      new BigInteger(1, "NonexistentFileStateValue".getBytes(UTF_8)).toByteArray();

  @SerializationConstant @VisibleForSerialization
  static final HasDigest NON_EXISTENT_HAS_DIGEST = () -> MISSING_FINGERPRINT;

  private static final FileInfo NON_EXISTENT_FILE_INFO =
      new FileInfo(FileType.NONEXISTENT, NON_EXISTENT_HAS_DIGEST, null, null);

  /** The exception that {@link RecursiveFilesystemTraversalFunctionException} wraps. */
  public static class RecursiveFilesystemTraversalException extends Exception {

    /**
     * Categories of errors that prevent normal {@link RecursiveFilesystemTraversalFunction}
     * evaluation.
     */
    public enum Type {
      /**
       * The traversal encountered a subdirectory with a BUILD file but is not allowed to recurse
       * into it.
       */
      CANNOT_CROSS_PACKAGE_BOUNDARY,

      /** A dangling symlink was dereferenced. */
      DANGLING_SYMLINK,

      /** A file operation failed. */
      FILE_OPERATION_FAILURE,

      /** A generated directory's root-relative path conflicts with a package's path. */
      GENERATED_PATH_CONFLICT,

      /** A file/directory visited was part of a symlink cycle or infinite expansion. */
      SYMLINK_CYCLE_OR_INFINITE_EXPANSION,

      /** The filesystem told us inconsistent information. */
      INCONSISTENT_FILESYSTEM,

      /** The filesystem threw a {@link DetailedIOException}. */
      DETAILED_IO_EXCEPTION,

      /** A traversal of a source directory was requested. */
      CANNOT_TRAVERSE_SOURCE_DIRECTORY,
    }

    private final RecursiveFilesystemTraversalException.Type type;

    RecursiveFilesystemTraversalException(String message, DetailedIOException cause) {
      super(message, cause);
      this.type = RecursiveFilesystemTraversalException.Type.DETAILED_IO_EXCEPTION;
    }

    RecursiveFilesystemTraversalException(
        String message, RecursiveFilesystemTraversalException.Type type) {
      super(message);
      checkArgument(type != Type.DETAILED_IO_EXCEPTION);
      this.type = type;
    }

    public RecursiveFilesystemTraversalException.Type getType() {
      return type;
    }
  }

  /**
   * Thrown when a dangling symlink is attempted to be dereferenced.
   *
   * <p>Note: this class is not identical to the one in com.google.devtools.build.lib.view.fileset
   * and it's not easy to merge the two because of the dependency structure. The other one will
   * probably be removed along with the rest of the legacy Fileset code.
   */
  static final class DanglingSymlinkException extends RecursiveFilesystemTraversalException {
    DanglingSymlinkException(String path, String unresolvedLink) {
      super(
          String.format(
              "Found dangling symlink: %s, unresolved path: \"%s\"", path, unresolvedLink),
          RecursiveFilesystemTraversalException.Type.DANGLING_SYMLINK);
      checkArgument(path != null && !path.isEmpty());
      checkArgument(unresolvedLink != null && !unresolvedLink.isEmpty());
    }
  }

  /** Exception type thrown by {@link RecursiveFilesystemTraversalFunction#compute}. */
  private static final class RecursiveFilesystemTraversalFunctionException extends
      SkyFunctionException {
    RecursiveFilesystemTraversalFunctionException(RecursiveFilesystemTraversalException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  private final SyscallCache syscallCache;

  RecursiveFilesystemTraversalFunction(SyscallCache syscallCache) {
    this.syscallCache = syscallCache;
  }

  @Nullable
  @Override
  public RecursiveFilesystemTraversalValue compute(SkyKey skyKey, Environment env)
      throws RecursiveFilesystemTraversalFunctionException, InterruptedException {
    TraversalRequest traversal = (TraversalRequest) skyKey.argument();
    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.FILESYSTEM_TRAVERSAL, traversal.root().toString())) {
      // Stat the traversal root.
      FileInfo rootInfo = lookUpFileInfo(env, traversal, syscallCache);
      if (rootInfo == null) {
        return null;
      }

      if (!rootInfo.type.exists()) {
        // May be a dangling symlink or a non-existent file. Handle gracefully.
        if (rootInfo.type.isSymlink()) {
          return RecursiveFilesystemTraversalValue.of(
              ResolvedFileFactory.danglingSymlink(
                  traversal.root().asRootedPath(),
                  rootInfo.unresolvedSymlinkTarget,
                  rootInfo.metadata));
        } else {
          return RecursiveFilesystemTraversalValue.EMPTY;
        }
      }

      if (rootInfo.type.isFile()) {
        return resultForFileRoot(traversal.root().asRootedPath(), rootInfo);
      }
      if (rootInfo.type.isDirectory() && rootInfo.metadata instanceof TreeArtifactValue value) {
        ImmutableList.Builder<RecursiveFilesystemTraversalValue> traversalValues =
            ImmutableList.builderWithExpectedSize(value.getChildValues().size());
        for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry
            : value.getChildValues().entrySet()) {
          RootedPath path =
              RootedPath.toRootedPath(traversal.root().getRootPart(), entry.getKey().getPath());
          traversalValues.add(
              resultForFileRoot(
                  path,
                  // TreeArtifact can't have symbolic inside. So the assumption for FileType.FILE
                  // is always true.
                  new FileInfo(FileType.FILE, entry.getValue(), path, null)));
        }
        return resultForDirectory(traversal, rootInfo, traversalValues.build());
      }

      // Otherwise the root is a directory or a symlink to one.
      PkgLookupResult pkgLookupResult = checkIfPackage(env, traversal, rootInfo, syscallCache);
      if (pkgLookupResult == null) {
        return null;
      }
      traversal = pkgLookupResult.traversal;

      if (pkgLookupResult.isConflicting()) {
        // The traversal was requested for an output directory whose root-relative path conflicts
        // with a source package. We can't handle that, bail out.
        throw createGeneratedPathConflictException(traversal);
      } else if (pkgLookupResult.isPackage() && !traversal.skipTestingForSubpackage()) {
        // The traversal was requested for a directory that defines a package which we should not
        // traverse and should complain loudly (display an error).
        String msg =
            traversal.errorInfo()
                + " crosses package boundary into package rooted at "
                + traversal.root().getRelativePart().getPathString();
        throw new RecursiveFilesystemTraversalFunctionException(
            new RecursiveFilesystemTraversalException(
                msg, RecursiveFilesystemTraversalException.Type.CANNOT_CROSS_PACKAGE_BOUNDARY));
      }

      // We are free to traverse this directory.
      ImmutableList<RecursiveFilesystemTraversalValue> subdirTraversals =
          traverseChildren(env, traversal);
      if (subdirTraversals == null) {
        return null;
      }
      return resultForDirectory(traversal, rootInfo, subdirTraversals);
    } catch (IOException | BuildFileNotFoundException e) {
      String message =
          String.format(
              "Error while traversing directory %s: %s",
              traversal.root().getRelativePart(), e.getMessage());

      if (e instanceof DetailedIOException detailedException) {
        throw new RecursiveFilesystemTraversalFunctionException(
            new RecursiveFilesystemTraversalException(message, detailedException));
      }

      // Trying to stat the starting point of this root may have failed with a symlink cycle or
      // trying to get a package lookup value may have failed due to a symlink cycle.
      RecursiveFilesystemTraversalException.Type exceptionType =
          RecursiveFilesystemTraversalException.Type.FILE_OPERATION_FAILURE;
      if (e instanceof InconsistentFilesystemException) {
        exceptionType = RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM;
      }
      if (e instanceof FileSymlinkException) {
        exceptionType =
            RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION;
      }
      if (e instanceof DetailedException) {
        FailureDetails.PackageLoading.Code code =
            ((DetailedException) e)
                .getDetailedExitCode()
                .getFailureDetail()
                .getPackageLoading()
                .getCode();
        if (code == FailureDetails.PackageLoading.Code.SYMLINK_CYCLE_OR_INFINITE_EXPANSION) {
          exceptionType =
              RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION;
        }
      }
      throw new RecursiveFilesystemTraversalFunctionException(
          new RecursiveFilesystemTraversalException(message, exceptionType));
    }
  }

  private static RecursiveFilesystemTraversalFunctionException createGeneratedPathConflictException(
      TraversalRequest traversal) {
    String message =
        String.format(
            "Generated directory %s conflicts with package under the same path. "
                + "Additional info: %s",
            traversal.root().getRelativePart().getPathString(), traversal.errorInfo());
    return new RecursiveFilesystemTraversalFunctionException(
        new RecursiveFilesystemTraversalException(
            message, RecursiveFilesystemTraversalException.Type.GENERATED_PATH_CONFLICT));
  }

  private static final class FileInfo {
    final FileType type;
    final HasDigest metadata;
    @Nullable final RootedPath realPath;
    @Nullable final PathFragment unresolvedSymlinkTarget;

    FileInfo(
        FileType type,
        HasDigest metadata,
        @Nullable RootedPath realPath,
        @Nullable PathFragment unresolvedSymlinkTarget) {
      checkNotNull(metadata.getDigest(), metadata);
      this.type = checkNotNull(type);
      this.metadata = metadata;
      this.realPath = realPath;
      this.unresolvedSymlinkTarget = unresolvedSymlinkTarget;
    }

    @Override
    public String toString() {
      if (type.isSymlink()) {
        return String.format("(%s: link_value=%s, real_path=%s)", type,
            unresolvedSymlinkTarget.getPathString(), realPath);
      } else {
        return String.format("(%s: real_path=%s)", type, realPath);
      }
    }
  }

  @Nullable
  private static FileInfo lookUpFileInfo(
      Environment env, TraversalRequest traversal, SyscallCache syscallCache)
      throws IOException, InterruptedException {
    if (traversal.isRootGenerated()) {
      HasDigest fsVal = null;
      if (traversal.root().getOutputArtifact() != null) {
        Artifact artifact = traversal.root().getOutputArtifact();
        SkyKey artifactKey = Artifact.key(artifact);
        SkyValue value = env.getValue(artifactKey);
        if (env.valuesMissing()) {
          return null;
        }

        if (value instanceof FileArtifactValue || value instanceof TreeArtifactValue) {
          fsVal = (HasDigest) value;
        } else if (value instanceof ActionExecutionValue actionExecutionValue) {
          fsVal = actionExecutionValue.getExistingFileArtifactValue(artifact);
        } else {
          return NON_EXISTENT_FILE_INFO;
        }
      }
      RootedPath realPath = traversal.root().asRootedPath();
      if (traversal.strictOutputFiles()) {
        checkNotNull(fsVal, "Strict Fileset output tree has null FileArtifactValue");
        return new FileInfo(
            fsVal instanceof TreeArtifactValue ? FileType.DIRECTORY : FileType.FILE,
            fsVal,
            realPath,
            null);
      } else {
        // FileArtifactValue does not currently track symlinks. If it did, we could potentially
        // remove some of the filesystem operations we're doing here.
        Path path = traversal.root().asPath();
        FileStateValue fileState =
            FileStateValue.create(traversal.root().asRootedPath(), syscallCache, null);
        if (fileState.getType() == FileStateType.NONEXISTENT) {
          throw new IOException("Missing file: " + path);
        }
        FileStatus followStat = path.statIfFound(Symlinks.FOLLOW);
        FileType type;
        PathFragment unresolvedLinkTarget = null;
        if (followStat == null) {
          type = FileType.DANGLING_SYMLINK;
          if (fileState.getType() != FileStateType.SYMLINK) {
            throw new IOException("Expected symlink for " + path + ", but got: " + fileState);
          }
          unresolvedLinkTarget = path.readSymbolicLink();
        } else if (fileState.getType() == FileStateType.REGULAR_FILE) {
          type = FileType.FILE;
        } else if (fileState.getType() == FileStateType.DIRECTORY) {
          type = FileType.DIRECTORY;
        } else {
          unresolvedLinkTarget = path.readSymbolicLink();
          realPath =
              RootedPath.toRootedPath(
                  Root.absoluteRoot(path.getFileSystem()), path.resolveSymbolicLinks());
          type = followStat.isFile() ? FileType.SYMLINK_TO_FILE : FileType.SYMLINK_TO_DIRECTORY;
        }
        if (fsVal == null) {
          fsVal = fileState;
        }
        return new FileInfo(
            type, withDigest(fsVal, path, syscallCache), realPath, unresolvedLinkTarget);
      }
    } else {
      // Stat the file.
      RootedPath rootedPath = traversal.root().asRootedPath();
      FileValue fileValue =
          (FileValue) env.getValueOrThrow(FileValue.key(rootedPath), IOException.class);

      if (env.valuesMissing()) {
        return null;
      }
      return toFileInfo(rootedPath, fileValue, env, traversal.root().asPath(), syscallCache);
    }
  }

  @Nullable
  private static FileInfo toFileInfo(
      RootedPath rootedPath,
      FileValue fileValue,
      Environment env,
      Path path,
      SyscallCache syscallCache)
      throws IOException, InterruptedException {
    if (fileValue.unboundedAncestorSymlinkExpansionChain() != null) {
      SkyKey uniquenessKey =
          FileSymlinkInfiniteExpansionUniquenessFunction.key(
              fileValue.unboundedAncestorSymlinkExpansionChain());
      env.getValue(uniquenessKey);
      if (env.valuesMissing()) {
        return null;
      }

      throw new FileSymlinkInfiniteExpansionException(
          fileValue.pathToUnboundedAncestorSymlinkExpansionChain(),
          fileValue.unboundedAncestorSymlinkExpansionChain());
    }

    if (!fileValue.exists()) {
      // If it doesn't exist, or it's a dangling symlink, we still want to handle that gracefully.
      return new FileInfo(
          fileValue.isSymlink() ? FileType.DANGLING_SYMLINK : FileType.NONEXISTENT,
          withDigest(fileValue.realFileStateValue(), null, syscallCache),
          null,
          fileValue.isSymlink() ? fileValue.getUnresolvedLinkTarget() : null);
    }

    // If it exists, it may either be a symlink or a file/directory.
    PathFragment unresolvedLinkTarget = null;
    FileType type;
    if (fileValue.isSymlink()) {
      unresolvedLinkTarget = fileValue.getUnresolvedLinkTarget();
      type = fileValue.isDirectory() ? FileType.SYMLINK_TO_DIRECTORY : FileType.SYMLINK_TO_FILE;
    } else {
      type = fileValue.isDirectory() ? FileType.DIRECTORY : FileType.FILE;
    }
    return new FileInfo(
        type,
        withDigest(fileValue.realFileStateValue(), path, syscallCache),
        fileValue.realRootedPath(rootedPath),
        unresolvedLinkTarget);
  }

  /**
   * Transform the HasDigest to the appropriate type based on the current state of the digest. If
   * fsVal is type RegularFileStateValue or FileArtifactValue and has a valid digest value, then we
   * want to convert it to a new FileArtifactValue type. Otherwise if they are of the two
   * forementioned types but do not have a digest, then we will create a FileArtifactValue using its
   * {@link Path}. Otherwise we will fingerprint the digest and return it as a new {@link
   * HasDigest.ByteStringDigest} object.
   *
   * @param fsVal - the HasDigest value that was in the graph.
   * @param path - the Path of the digest.
   * @return transformed HasDigest value based on the digest field and object type.
   */
  @VisibleForTesting
  static HasDigest withDigest(HasDigest fsVal, Path path, XattrProvider syscallCache)
      throws IOException {
    if (fsVal instanceof FileStateValue fsv) {
      if (fsv instanceof RegularFileStateValueWithDigest rfsv) {
        return FileArtifactValue.createForVirtualActionInput(rfsv.getDigest(), rfsv.getSize());
      } else if (fsv instanceof RegularFileStateValueWithContentsProxy rfsv) {
        return FileArtifactValue.createForNormalFileUsingPath(path, rfsv.getSize(), syscallCache);
      }

      return new HasDigest.ByteStringDigest(fsv.getValueFingerprint());
    } else if (fsVal instanceof FileArtifactValue fav) {
      if (fav.getDigest() != null) {
        return fav;
      }

      // In the case there is a directory, the HasDigest value should not be converted. Otherwise,
      // if the HasDigest value is a file, convert it using the Path and size values.
      return fav.getType().isFile()
          ? FileArtifactValue.createForNormalFileUsingPath(path, fav.getSize(), syscallCache)
          : new HasDigest.ByteStringDigest(fav.getValueFingerprint());
    }
    return fsVal;
  }

  private static final class PkgLookupResult {
    private enum Type {
      CONFLICT, DIRECTORY, PKG
    }

    private final PkgLookupResult.Type type;
    final TraversalRequest traversal;
    final FileInfo rootInfo;

    /** Result for a generated directory that conflicts with a source package. */
    static PkgLookupResult conflict(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(PkgLookupResult.Type.CONFLICT, traversal, rootInfo);
    }

    /** Result for a source or generated directory (not a package). */
    static PkgLookupResult directory(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(PkgLookupResult.Type.DIRECTORY, traversal, rootInfo);
    }

    /** Result for a package, i.e. a directory  with a BUILD file. */
    static PkgLookupResult pkg(TraversalRequest traversal, FileInfo rootInfo) {
      return new PkgLookupResult(PkgLookupResult.Type.PKG, traversal, rootInfo);
    }

    private PkgLookupResult(
        PkgLookupResult.Type type, TraversalRequest traversal, FileInfo rootInfo) {
      this.type = checkNotNull(type);
      this.traversal = checkNotNull(traversal);
      this.rootInfo = checkNotNull(rootInfo);
    }

    boolean isPackage() {
      return type == PkgLookupResult.Type.PKG;
    }

    boolean isConflicting() {
      return type == PkgLookupResult.Type.CONFLICT;
    }

    @Override
    public String toString() {
      return String.format("(%s: info=%s, traversal=%s)", type, rootInfo, traversal);
    }
  }

  /**
   * Checks whether the {@code traversal}'s path refers to a package directory.
   *
   * @return the result of the lookup; it contains potentially new {@link TraversalRequest} and
   *     {@link FileInfo} so the caller should use these instead of the old ones (this happens when
   *     a package is found, but under a different root than expected)
   */
  @Nullable
  private static PkgLookupResult checkIfPackage(
      Environment env, TraversalRequest traversal, FileInfo rootInfo, SyscallCache syscallCache)
      throws IOException, InterruptedException, BuildFileNotFoundException {
    checkArgument(
        rootInfo.type.exists() && !rootInfo.type.isFile(), "{%s} {%s}", traversal, rootInfo);
    // PackageLookupFunction/dependencies can only throw IOException, BuildFileNotFoundException,
    // and RepositoryFetchException, and RepositoryFetchException is not in play here. Note that
    // run-of-the-mill circular symlinks will *not* throw here, and will trigger later errors during
    // the recursive traversal.
    PackageLookupValue pkgLookup =
        (PackageLookupValue)
            env.getValueOrThrow(
                PackageLookupValue.key(traversal.root().getRelativePart()),
                BuildFileNotFoundException.class,
                IOException.class);
    if (env.valuesMissing()) {
      return null;
    }

    if (pkgLookup.packageExists()) {
      if (traversal.isRootGenerated()) {
        // The traversal's root was a generated directory, but its root-relative path conflicts with
        // an existing package.
        return PkgLookupResult.conflict(traversal, rootInfo);
      } else {
        // The traversal's root was a source directory and it defines a package.
        Root pkgRoot = pkgLookup.getRoot();
        if (!pkgRoot.equals(traversal.root().getRootPart())) {
          // However the root of this package is different from what we expected. stat() the real
          // BUILD file of that package.
          traversal = traversal.forChangedRootPath(pkgRoot);
          rootInfo = lookUpFileInfo(env, traversal, syscallCache);
          Verify.verify(rootInfo.type.exists(), "{%s} {%s}", traversal, rootInfo);
        }
        return PkgLookupResult.pkg(traversal, rootInfo);
      }
    } else {
      // The traversal's root was a directory (source or generated one), no package exists under the
      // same root-relative path.
      return PkgLookupResult.directory(traversal, rootInfo);
    }
  }

  /**
   * Creates results for a file or for a symlink that points to one.
   *
   * <p>A symlink may be direct (points to a file) or transitive (points at a direct or transitive
   * symlink).
   */
  private static RecursiveFilesystemTraversalValue resultForFileRoot(RootedPath path, FileInfo info)
      throws InconsistentFilesystemException {
    if (!info.type.isFile() || !info.type.exists()) {
      throw new InconsistentFilesystemException(
          String.format(
              "We were previously told %s was an existing file but it's actually %s", path, info));
    }

    if (info.type.isSymlink()) {
      return RecursiveFilesystemTraversalValue.of(
          ResolvedFileFactory.symlinkToFile(
              info.realPath, path, info.unresolvedSymlinkTarget, info.metadata));
    } else {
      return RecursiveFilesystemTraversalValue.of(
          ResolvedFileFactory.regularFile(path, info.metadata));
    }
  }

  private static RecursiveFilesystemTraversalValue resultForDirectory(
      TraversalRequest traversal,
      FileInfo rootInfo,
      ImmutableList<RecursiveFilesystemTraversalValue> subdirTraversals) {
    // Collect transitive closure of files in subdirectories.
    NestedSetBuilder<ResolvedFile> paths = NestedSetBuilder.stableOrder();
    for (RecursiveFilesystemTraversalValue child : subdirTraversals) {
      paths.addTransitive(child.getTransitiveFiles());
    }
    ResolvedFile root;
    if (rootInfo.type.isSymlink()) {
      NestedSet<ResolvedFile> children = paths.build();
      root =
          ResolvedFileFactory.symlinkToDirectory(
              rootInfo.realPath,
              traversal.root().asRootedPath(),
              rootInfo.unresolvedSymlinkTarget,
              hashDirectorySymlink(children, rootInfo.metadata));
      paths = NestedSetBuilder.<ResolvedFile>stableOrder().addTransitive(children).add(root);
    } else {
      root = ResolvedFileFactory.directory(rootInfo.realPath);
      if (traversal.emitEmptyDirectoryNodes() && paths.isEmpty()) {
        paths.add(root);
      }
    }
    return RecursiveFilesystemTraversalValue.of(root, paths.build());
  }

  private static HasDigest hashDirectorySymlink(
      NestedSet<ResolvedFile> children, HasDigest metadata) {
    // If the root is a directory symlink, the associated FileStateValue does not change when the
    // linked directory's contents change, so we can't use the FileStateValue as metadata like we
    // do with other ResolvedFile kinds. Instead we compute a metadata hash from the child
    // elements and return that as the ResolvedFile's metadata hash.
    Fingerprint fp = new Fingerprint();
    fp.addBytes(metadata.getDigest());
    for (ResolvedFile file : children.toList()) {
      fp.addPath(file.getNameInSymlinkTree());
      fp.addBytes(file.getMetadata().getDigest());
    }
    byte[] result = fp.digestAndReset();
    return new HasDigest.ByteStringDigest(result);
  }

  /** Requests Skyframe to compute the dependent values and returns them. */
  @Nullable
  private ImmutableList<RecursiveFilesystemTraversalValue> traverseChildren(
      Environment env, TraversalRequest traversal)
      throws InterruptedException, RecursiveFilesystemTraversalFunctionException, IOException {
    return traversal.isRootGenerated()
        ? traverseGeneratedChildren(env, traversal)
        : traverseSourceChildren(env, traversal);
  }

  @Nullable
  private ImmutableList<RecursiveFilesystemTraversalValue> traverseGeneratedChildren(
      Environment env, TraversalRequest traversal)
      throws InterruptedException, RecursiveFilesystemTraversalFunctionException, IOException {
    // If we're dealing with an output file, read the directory directly instead of creating
    // filesystem nodes under the output tree.
    Collection<Dirent> dirents = traversal.root().asPath().readdir(Symlinks.FOLLOW);
    if (dirents.isEmpty()) {
      return ImmutableList.of();
    }
    List<Dirent> sortedDirents = new ArrayList<>(dirents);
    Collections.sort(sortedDirents);

    ImmutableList.Builder<RecursiveFilesystemTraversalValue> childValues =
        ImmutableList.builderWithExpectedSize(dirents.size());
    for (Dirent dirent : sortedDirents) {
      RecursiveFilesystemTraversalValue childValue =
          compute(traversal.forChildEntry(dirent.getName()), env);
      if (childValue != null) {
        childValues.add(childValue);
      }
    }
    return env.valuesMissing() ? null : childValues.build();
  }

  @Nullable
  private ImmutableList<RecursiveFilesystemTraversalValue> traverseSourceChildren(
      Environment env, TraversalRequest traversal) throws InterruptedException, IOException {
    DirectoryListingValue dirListingValue =
        (DirectoryListingValue)
            env.getValueOrThrow(
                DirectoryListingValue.key(traversal.root().asRootedPath()), IOException.class);
    if (dirListingValue == null) {
      return null;
    }
    Dirents dirents = dirListingValue.getDirents();
    if (dirents.size() == 0) {
      return ImmutableList.of();
    }

    List<SkyKey> childKeys = new ArrayList<>(dirents.size());
    for (Dirent dirent : dirents) {
      TraversalRequest childRequest = traversal.forChildEntry(dirent.getName());
      // For source files, request the FileValue directly instead of another TraversalRequest. This
      // makes the base case of the recursive traversal a directory with no subdirectories instead
      // of a file, greatly reducing the number of skyframe nodes for directories with many files.
      if (dirent.getType() == Dirent.Type.FILE) {
        childKeys.add(FileValue.key(childRequest.root().asRootedPath()));
      } else {
        childKeys.add(childRequest);
      }
    }

    SkyframeLookupResult result = env.getValuesAndExceptions(childKeys);
    ImmutableList.Builder<RecursiveFilesystemTraversalValue> childValues =
        ImmutableList.builderWithExpectedSize(childKeys.size());
    for (SkyKey key : childKeys) {
      SkyValue value = result.get(key);
      if (value == null) {
        continue;
      }
      if (key instanceof FileKey fileKey) {
        FileInfo fileInfo =
            toFileInfo(
                fileKey.argument(),
                (FileValue) value,
                env,
                fileKey.argument().asPath(),
                syscallCache);
        if (fileInfo != null) {
          childValues.add(resultForFileRoot(fileKey.argument(), fileInfo));
        }
      } else {
        childValues.add((RecursiveFilesystemTraversalValue) value);
      }
    }
    return env.valuesMissing() ? null : childValues.build();
  }
}
