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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileStatusWithMetadata;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.XattrProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Handler provided by {@link ActionExecutionFunction} which allows the execution engine to obtain
 * {@linkplain FileArtifactValue metadata} about outputs and to store metadata about them for
 * purposes of creating the final {@link ActionExecutionValue}.
 *
 * <p>The handler can be in one of two modes. After construction, it acts as a cache for output
 * metadata while {@link com.google.devtools.build.lib.actions.ActionCacheChecker} determines
 * whether the action needs to be executed. If the action needs to be executed (i.e. no action cache
 * hit), {@link #prepareForActionExecution} is called. This call switches the handler to a mode
 * where it accepts {@linkplain com.google.devtools.build.lib.actions.cache.OutputMetadataStore
 * injected output data}, or otherwise obtains metadata from the filesystem. Freshly created output
 * files are set read-only and executable <em>before</em> statting them to ensure that the stat's
 * ctime is up to date.
 *
 * <p>After action execution, {@link #getOutputMetadata} should be called on each of the action's
 * outputs (except those that were {@linkplain #artifactOmitted omitted}) to ensure that declared
 * outputs were in fact created and are valid.
 */
final class ActionOutputMetadataStore implements OutputMetadataStore {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Creates a new metadata handler. */
  static ActionOutputMetadataStore create(
      boolean archivedTreeArtifactsEnabled,
      OutputPermissions outputPermissions,
      ImmutableSet<Artifact> outputs,
      XattrProvider xattrProvider,
      TimestampGranularityMonitor tsgm,
      ArtifactPathResolver artifactPathResolver) {
    return new ActionOutputMetadataStore(
        archivedTreeArtifactsEnabled,
        outputPermissions,
        outputs,
        xattrProvider,
        tsgm,
        artifactPathResolver);
  }

  private final boolean archivedTreeArtifactsEnabled;
  private final OutputPermissions outputPermissions;

  private final XattrProvider xattrProvider;
  private final TimestampGranularityMonitor tsgm;
  private final ArtifactPathResolver artifactPathResolver;

  private final AtomicBoolean executionMode = new AtomicBoolean(false);

  private final ImmutableSet<Artifact> outputs;
  private final Set<Artifact> omittedOutputs = Sets.newConcurrentHashSet();
  private final ConcurrentMap<Artifact, FileArtifactValue> artifactData = new ConcurrentHashMap<>();
  private final ConcurrentMap<SpecialArtifact, TreeArtifactValue> treeArtifactData =
      new ConcurrentHashMap<>();

  private ActionOutputMetadataStore(
      boolean archivedTreeArtifactsEnabled,
      OutputPermissions outputPermissions,
      ImmutableSet<Artifact> outputs,
      XattrProvider xattrProvider,
      TimestampGranularityMonitor tsgm,
      ArtifactPathResolver artifactPathResolver) {
    this.archivedTreeArtifactsEnabled = archivedTreeArtifactsEnabled;
    this.outputPermissions = outputPermissions;
    this.outputs = checkNotNull(outputs);
    this.xattrProvider = xattrProvider;
    this.tsgm = checkNotNull(tsgm);
    this.artifactPathResolver = checkNotNull(artifactPathResolver);
  }

  private void putArtifactData(Artifact artifact, FileArtifactValue value) {
    Preconditions.checkArgument(
        !artifact.isTreeArtifact() && !artifact.isChildOfDeclaredDirectory(),
        "%s should be stored in a TreeArtifactValue",
        artifact);
    artifactData.put(artifact, value);
  }

  ImmutableMap<Artifact, FileArtifactValue> getAllArtifactData() {
    return ImmutableMap.copyOf(artifactData);
  }

  /**
   * Returns data for TreeArtifacts that was computed during execution. May contain copies of {@link
   * TreeArtifactValue#MISSING_TREE_ARTIFACT}.
   */
  ImmutableMap<Artifact, TreeArtifactValue> getAllTreeArtifactData() {
    return ImmutableMap.copyOf(treeArtifactData);
  }

  /**
   * If {@code value} represents an existing file, returns it as is, otherwise throws {@link
   * FileNotFoundException}.
   */
  private static FileArtifactValue checkExists(FileArtifactValue value, Artifact artifact)
      throws FileNotFoundException {
    if (FileArtifactValue.MISSING_FILE_MARKER.equals(value)) {
      throw new FileNotFoundException(artifact + " does not exist");
    }
    return checkNotNull(value, artifact);
  }

  /**
   * If {@code value} represents an existing tree artifact, returns it as is, otherwise throws
   * {@link FileNotFoundException}.
   */
  private static TreeArtifactValue checkExists(TreeArtifactValue value, Artifact artifact)
      throws FileNotFoundException {
    if (TreeArtifactValue.MISSING_TREE_ARTIFACT.equals(value)) {
      throw new FileNotFoundException(artifact + " does not exist");
    }
    return checkNotNull(value, artifact);
  }

  private boolean isKnownOutput(Artifact artifact) {
    return outputs.contains(artifact)
        || (artifact.hasParent() && outputs.contains(artifact.getParent()));
  }

  @Nullable
  @Override
  public FileArtifactValue getOutputMetadata(ActionInput actionInput)
      throws IOException, InterruptedException {
    Artifact artifact = (Artifact) actionInput;
    FileArtifactValue value;

    if (!isKnownOutput(artifact)) {
      return null;
    }

    if (artifact.isRunfilesTree()) {
      // Runfiles trees get a placeholder value, see the Javadoc of RUNFILES_TREE_MARKER as to why
      value = artifactData.get(artifact);
      if (value != null) {
        return checkExists(value, artifact);
      }
      putArtifactData(artifact, FileArtifactValue.RUNFILES_TREE_MARKER);
      return FileArtifactValue.RUNFILES_TREE_MARKER;
    }

    if (artifact.isTreeArtifact()) {
      TreeArtifactValue tree = getTreeArtifactValue((SpecialArtifact) artifact);
      return tree.getMetadata();
    }

    if (artifact.isChildOfDeclaredDirectory()) {
      TreeArtifactValue tree = getTreeArtifactValue(artifact.getParent());
      value = tree.getChildValues().getOrDefault(artifact, FileArtifactValue.MISSING_FILE_MARKER);
      return checkExists(value, artifact);
    }

    value = artifactData.get(artifact);
    if (value != null) {
      return checkExists(value, artifact);
    }

    // No existing metadata; this can happen if the output metadata is not injected after a spawn
    // is executed. SkyframeActionExecutor.checkOutputs calls this method for every output file of
    // the action, which hits this code path. Another possibility is that an action runs multiple
    // spawns, and a subsequent spawn requests the metadata of an output of a previous spawn.

    // If necessary, we first call chmod the output file. The FileArtifactValue may use a
    // FileContentsProxy, which is based on ctime (affected by chmod).
    if (executionMode.get()) {
      setPathPermissionsIfFile(artifactPathResolver.toPath(artifact));
    }

    value = constructFileArtifactValueFromFilesystem(artifact);
    putArtifactData(artifact, value);
    return checkExists(value, artifact);
  }

  @Override
  public TreeArtifactValue getTreeArtifactValue(SpecialArtifact artifact)
      throws IOException, InterruptedException {
    checkState(artifact.isTreeArtifact(), "%s is not a tree artifact", artifact);

    TreeArtifactValue value = treeArtifactData.get(artifact);
    if (value != null) {
      return checkExists(value, artifact);
    }

    value = constructTreeArtifactValueFromFilesystem(artifact);
    treeArtifactData.put(artifact, value);
    return checkExists(value, artifact);
  }

  private TreeArtifactValue constructTreeArtifactValueFromFilesystem(SpecialArtifact parent)
      throws IOException, InterruptedException {
    Path treeDir = artifactPathResolver.toPath(parent);
    boolean chmod = executionMode.get();

    FileStatus stat = treeDir.statIfFound(Symlinks.FOLLOW);

    // Make sure the tree artifact root exists and is a regular directory. Note that this is how the
    // action is initialized, so this should hold unless the action itself has deleted the root.
    if (stat == null || !stat.isDirectory()) {
      if (chmod) {
        setPathPermissionsIfFile(treeDir);
      }
      return TreeArtifactValue.MISSING_TREE_ARTIFACT;
    }

    AtomicBoolean anyRemote = new AtomicBoolean(false);

    TreeArtifactValue.Builder tree = TreeArtifactValue.newBuilder(parent);

    TreeArtifactValue.visitTree(
        treeDir,
        (parentRelativePath, type, traversedSymlink) -> {
          checkState(type == Dirent.Type.FILE || type == Dirent.Type.DIRECTORY);
          // Set the output permissions when the execution mode requires it, unless at least one
          // symlink was traversed on the way to this entry, as it might have led outside of the
          // root directory.
          if (chmod && !traversedSymlink) {
            setPathPermissions(treeDir.getRelative(parentRelativePath));
          }
          if (type == Dirent.Type.DIRECTORY) {
            return; // The final TreeArtifactValue does not contain child directories.
          }
          TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, parentRelativePath);
          FileArtifactValue metadata = constructFileArtifactValueFromFilesystem(child);
          // visitTree() uses multiple threads and putChild() is not thread-safe
          synchronized (tree) {
            if (metadata.isRemote()) {
              anyRemote.set(true);
            }
            tree.putChild(child, metadata);
          }
        });

    if (archivedTreeArtifactsEnabled) {
      ArchivedTreeArtifact archivedTreeArtifact = ArchivedTreeArtifact.createForTree(parent);
      FileStatus archivedStatNoFollow =
          artifactPathResolver.toPath(archivedTreeArtifact).statIfFound(Symlinks.NOFOLLOW);
      if (archivedStatNoFollow != null) {
        tree.setArchivedRepresentation(
            archivedTreeArtifact,
            constructFileArtifactValue(
                archivedTreeArtifact,
                FileStatusWithDigestAdapter.maybeAdapt(archivedStatNoFollow)));
      } else {
        logger.atInfo().atMostEvery(5, MINUTES).log(
            "Archived tree artifact: %s not created", archivedTreeArtifact);
      }
    }

    // If the artifact was materialized in the filesystem as as symlink to another artifact, record
    // the real path in the metadata so that it can be recreated as such later.
    // See {@link FileArtifactValue#getResolvedPath} for why this is useful.
    // TODO(tjgq): Actually check whether the path matches one of the action inputs. The presence
    // of a FileStatusWithMetadata happens to coincide, but seems a little brittle.
    if (stat instanceof FileStatusWithMetadata statWithMetadata && treeDir.isSymbolicLink()) {
      FileArtifactValue metadata = statWithMetadata.getMetadata();
      PathFragment resolvedPath = metadata.getResolvedPath();
      if (resolvedPath != null) {
        tree.setResolvedPath(resolvedPath);
      } else {
        tree.setResolvedPath(treeDir.resolveSymbolicLinks().asFragment());
      }
    }

    return tree.build();
  }

  @Override
  public void injectFile(Artifact output, FileArtifactValue metadata) {
    checkArgument(isKnownOutput(output), "%s is not a declared output of this action", output);
    checkArgument(
        !output.isTreeArtifact() && !output.isChildOfDeclaredDirectory(),
        "Tree artifacts and their children must be injected via injectTree: %s",
        output);

    putArtifactData(output, metadata);
  }

  @Override
  public void injectTree(SpecialArtifact output, TreeArtifactValue tree) {
    checkArgument(isKnownOutput(output), "%s is not a declared output of this action", output);
    checkArgument(output.isTreeArtifact(), "Output must be a tree artifact: %s", output);
    checkArgument(
        archivedTreeArtifactsEnabled == tree.getArchivedRepresentation().isPresent(),
        "Archived representation presence mismatched for: %s with archivedTreeArtifactsEnabled: %s",
        tree,
        archivedTreeArtifactsEnabled);

    treeArtifactData.put(output, tree);
  }

  @Override
  public void markOmitted(Artifact output) {
    checkState(executionMode.get(), "Tried to mark %s omitted outside of execution", output);
    omittedOutputs.add(output);
  }

  @Override
  public boolean artifactOmitted(Artifact artifact) {
    return omittedOutputs.contains(artifact);
  }

  @Override
  public void resetOutputs(Iterable<? extends Artifact> outputs) {
    checkState(
        executionMode.get(), "resetOutputs() should only be called from within a running action.");
    for (Artifact output : outputs) {
      omittedOutputs.remove(output);
      if (output.isTreeArtifact()) {
        treeArtifactData.remove(output);
      } else {
        artifactData.remove(output);
      }
    }
  }

  /**
   * Informs this handler that the action is about to be executed.
   *
   * <p>Any stale metadata cached from action cache checking is cleared.
   */
  void prepareForActionExecution() {
    checkState(!executionMode.getAndSet(true), "Already in execution mode");
    artifactData.clear();
    treeArtifactData.clear();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("outputs", outputs)
        .add("artifactData", artifactData)
        .add("treeArtifactData", treeArtifactData)
        .toString();
  }

  /** Constructs a new {@link FileArtifactValue} by reading from the file system. */
  private FileArtifactValue constructFileArtifactValueFromFilesystem(Artifact artifact)
      throws IOException {
    return constructFileArtifactValue(artifact, /* statNoFollow= */ null);
  }

  /** Constructs a new {@link FileArtifactValue}, optionally taking a known stat. */
  private FileArtifactValue constructFileArtifactValue(
      Artifact artifact, @Nullable FileStatusWithDigest statNoFollow) throws IOException {
    checkState(!artifact.isTreeArtifact(), "%s is a tree artifact", artifact);

    var statAndValue =
        fileArtifactValueFromArtifact(
            artifact,
            artifactPathResolver,
            statNoFollow,
            xattrProvider,
            // Prevent constant metadata artifacts from notifying the timestamp granularity monitor
            // and potentially delaying the build for no reason.
            artifact.isConstantMetadata() ? null : tsgm);
    var value = statAndValue.fileArtifactValue();

    // Ensure that we don't have both an injected digest and a digest from the filesystem.
    byte[] fileDigest = value.getDigest();

    FileStateType type = value.getType();

    if (!type.exists()) {
      // Nonexistent files should only occur before executing an action.
      throw new FileNotFoundException(artifact.prettyPrint() + " does not exist");
    }

    if (type.isSymlink()) {
      // We always create a FileArtifactValue for an unresolved symlink with a digest (calling
      // readlink() is easy, unlike checksumming a potentially huge file).
      checkNotNull(fileDigest, "%s missing digest", value);
      return value;
    }

    if (type.isFile() && fileDigest != null) {
      // The digest is in the file value and that is all that is needed for this file's metadata.
      return value;
    }

    if (type.isDirectory()) {
      // This branch is taken when the output of an action is a directory:
      //   - A Fileset (in this case, Blaze is correct)
      //   - A directory someone created in a local action (in this case, changes under the
      //     directory may not be detected since we use the mtime of the directory for
      //     up-to-dateness checks)
      //   - A symlink to a source directory due to Filesets
      return FileArtifactValue.createForDirectoryWithMtime(
          artifactPathResolver.toPath(artifact).getLastModifiedTime());
    }

    byte[] digest = null;
    if (type.isFile()) {
      // We don't have an injected digest and there is no digest in the file value (which attempts a
      // fast digest). Manually compute the digest instead.
      Path path = statAndValue.pathNoFollow();
      if (statAndValue.statNoFollow() != null
          && statAndValue.statNoFollow().isSymbolicLink()
          && statAndValue.realPath() != null) {
        // If the file is a symlink, we compute the digest using the target path so that it's
        // possible to hit the digest cache - we probably already computed the digest for the
        // target during previous action execution.
        path = statAndValue.realPath();
      }

      digest = DigestUtils.manuallyComputeDigest(path);
    }
    return FileArtifactValue.createFromInjectedDigest(value, digest);
  }

  /**
   * Constructs a {@link FileArtifactValue} for a regular (non-tree, non-runfiles tree) artifact for
   * the purpose of determining whether an existing {@link FileArtifactValue} is still valid.
   *
   * <p>The returned metadata may be compared with metadata present in an {@link
   * ActionExecutionValue} using {@link FileArtifactValue#couldBeModifiedSince} to check for
   * inter-build modifications.
   */
  static FileArtifactValue fileArtifactValueFromArtifact(
      Artifact artifact,
      @Nullable FileStatusWithDigest statNoFollow,
      XattrProvider xattrProvider,
      @Nullable TimestampGranularityMonitor tsgm)
      throws IOException {
    return fileArtifactValueFromArtifact(
            artifact,
            ArtifactPathResolver.IDENTITY,
            statNoFollow,
            xattrProvider,
            tsgm)
        .fileArtifactValue();
  }

  private static FileArtifactStatAndValue fileArtifactValueFromArtifact(
      Artifact artifact,
      ArtifactPathResolver artifactPathResolver,
      @Nullable FileStatusWithDigest statNoFollow,
      XattrProvider xattrProvider,
      @Nullable TimestampGranularityMonitor tsgm)
      throws IOException {
    checkState(!artifact.isTreeArtifact() && !artifact.isRunfilesTree(), artifact);

    Path pathNoFollow = artifactPathResolver.toPath(artifact);
    // If we expect a symlink, we can readlink it directly and handle errors appropriately - there
    // is no need for the stat below.
    if (artifact.isSymlink()) {
      var fileArtifactValue = FileArtifactValue.createForUnresolvedSymlink(pathNoFollow);
      return FileArtifactStatAndValue.create(
          pathNoFollow, /* realPath= */ null, statNoFollow, fileArtifactValue);
    }

    RootedPath rootedPathNoFollow =
        RootedPath.toRootedPath(
            artifactPathResolver.transformRoot(artifact.getRoot().getRoot()),
            artifact.getRootRelativePath());
    if (statNoFollow == null) {
      // Stat the file. All output artifacts of an action are deleted before execution, so if a file
      // exists, it was most likely created by the current action. There is a race condition here if
      // an external process creates (or modifies) the file between the deletion and this stat,
      // which we cannot solve.
      statNoFollow =
          FileStatusWithDigestAdapter.maybeAdapt(pathNoFollow.statIfFound(Symlinks.NOFOLLOW));
    }

    if (statNoFollow == null || !statNoFollow.isSymbolicLink()) {
      var fileArtifactValue =
          fileArtifactValueFromStat(rootedPathNoFollow, statNoFollow, xattrProvider, tsgm);
      return FileArtifactStatAndValue.create(
          pathNoFollow, /* realPath= */ null, statNoFollow, fileArtifactValue);
    }

    // We use FileStatus#isSymbolicLink over Path#isSymbolicLink to avoid the unnecessary stat
    // done by the latter.  We need to protect against symlink cycles since
    // ArtifactFileMetadata#value assumes it's dealing with a file that's not in a symlink cycle.
    Path realPath = pathNoFollow.resolveSymbolicLinks();
    if (realPath.equals(pathNoFollow)) {
      throw new IOException("symlink cycle");
    }

    RootedPath realRootedPath =
        RootedPath.toRootedPathMaybeUnderRoot(
            realPath,
            ImmutableList.of(artifactPathResolver.transformRoot(artifact.getRoot().getRoot())));

    // TODO(bazel-team): consider avoiding a 'stat' here when the symlink target hasn't changed
    // and is a source file (since changes to those are checked separately).
    FileStatus realStat = realRootedPath.asPath().statIfFound(Symlinks.NOFOLLOW);
    FileStatusWithDigest realStatWithDigest = FileStatusWithDigestAdapter.maybeAdapt(realStat);
    var fileArtifactValue =
        fileArtifactValueFromStat(realRootedPath, realStatWithDigest, xattrProvider, tsgm);

    // If the artifact was materialized in the filesystem as as symlink to another artifact, record
    // the real path in the metadata so that it can be recreated as such later.
    // See {@link FileArtifactValue#getResolvedPath} for why this is useful.
    // TODO(tjgq): Actually check whether the path matches one of the action inputs. The presence
    // of a FileStatusWithMetadata happens to coincide, but seems a little brittle.
    if (realStat instanceof FileStatusWithMetadata && fileArtifactValue.getResolvedPath() == null) {
      fileArtifactValue =
          FileArtifactValue.createFromExistingWithResolvedPath(
              fileArtifactValue, realRootedPath.asPath().asFragment());
    }

    return FileArtifactStatAndValue.create(pathNoFollow, realPath, statNoFollow, fileArtifactValue);
  }

  record FileArtifactStatAndValue(
      Path pathNoFollow,
      @Nullable Path realPath,
      @Nullable FileStatusWithDigest statNoFollow,
      FileArtifactValue fileArtifactValue) {
    FileArtifactStatAndValue {
      requireNonNull(pathNoFollow, "pathNoFollow");
      requireNonNull(fileArtifactValue, "fileArtifactValue");
    }

    public static FileArtifactStatAndValue create(
        Path pathNoFollow,
        @Nullable Path realPath,
        @Nullable FileStatusWithDigest statNoFollow,
        FileArtifactValue fileArtifactValue) {
      return new FileArtifactStatAndValue(pathNoFollow, realPath, statNoFollow, fileArtifactValue);
    }

  }

  private static FileArtifactValue fileArtifactValueFromStat(
      RootedPath rootedPath,
      FileStatusWithDigest stat,
      XattrProvider xattrProvider,
      @Nullable TimestampGranularityMonitor tsgm)
      throws IOException {
    if (stat == null) {
      return FileArtifactValue.MISSING_FILE_MARKER;
    }

    if (stat.isDirectory()) {
      return FileArtifactValue.createForDirectoryWithMtime(stat.getLastModifiedTime());
    }

    if (stat instanceof FileStatusWithMetadata fileStatusWithMetadata) {
      return fileStatusWithMetadata.getMetadata();
    }

    FileStateValue fileStateValue =
        FileStateValue.createWithStatNoFollow(rootedPath, stat, xattrProvider, tsgm);

    return FileArtifactValue.createForNormalFile(
        fileStateValue.getDigest(), fileStateValue.getContentsProxy(), stat.getSize());
  }

  private void setPathPermissionsIfFile(Path path) throws IOException {
    FileStatus stat = path.statIfFound(Symlinks.NOFOLLOW);
    if (stat != null
        && stat.isFile()
        && stat.getPermissions() != outputPermissions.getPermissionsMode()) {
      setPathPermissions(path);
    }
  }

  private void setPathPermissions(Path path) throws IOException {
    path.chmod(outputPermissions.getPermissionsMode());
  }
}
