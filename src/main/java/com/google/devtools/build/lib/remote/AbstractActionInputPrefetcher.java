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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.RxFutures.toCompletable;
import static com.google.devtools.build.lib.remote.util.RxFutures.toListenableFuture;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.mergeBulkTransfer;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.LostInputsEvent;
import com.google.devtools.build.lib.remote.util.AsyncTaskCache;
import com.google.devtools.build.lib.util.TempPathGenerator;
import com.google.devtools.build.lib.vfs.FileSymlinkLoopException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import io.reactivex.rxjava3.core.Completable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Abstract implementation of {@link ActionInputPrefetcher} which implements the orchestration of
 * prefeching multiple inputs so subclasses can focus on prefetching / downloading single input.
 */
public abstract class AbstractActionInputPrefetcher implements ActionInputPrefetcher {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Reporter reporter;
  private final AsyncTaskCache.NoResult<Path> downloadCache = AsyncTaskCache.NoResult.create();
  private final TempPathGenerator tempPathGenerator;
  private final OutputPermissions outputPermissions;

  protected final Path execRoot;
  protected final RemoteOutputChecker remoteOutputChecker;

  private final ActionOutputDirectoryHelper outputDirectoryHelper;

  /** The state of a directory tracked by {@link DirectoryTracker}, as explained below. */
  enum DirectoryState {
    PERMANENTLY_WRITABLE,
    TEMPORARILY_WRITABLE,
    OUTPUT_PERMISSIONS
  }

  /**
   * Returns the metadata for an {@link ActionInput}.
   *
   * <p>This will generally call through to a {@link InputMetadataProvider} and ask for the metadata
   * of either an input or an output artifact.
   */
  @VisibleForTesting
  public interface MetadataSupplier {
    FileArtifactValue getMetadata(ActionInput actionInput) throws IOException, InterruptedException;
  }

  /**
   * Tracks directory permissions to minimize filesystem operations.
   *
   * <p>Throughout the prefetcher, {@link Path#setWritable} and {@link Path#chmod} calls on output
   * directories must go through the methods in this class.
   */
  private final class DirectoryTracker {
    private final ConcurrentHashMap<Path, DirectoryState> directoryStateMap =
        new ConcurrentHashMap<>();

    /**
     * Marks a directory as temporarily writable.
     *
     * <p>A temporarily writable directory may have its output permissions set by a later call to
     * {@link #setOutputPermissions}, unless {@link #setPermanentlyWritable} is called in the
     * interim.
     */
    void setTemporarilyWritable(Path dir) throws IOException {
      setWritable(dir, DirectoryState.TEMPORARILY_WRITABLE);
    }

    /**
     * Marks a directory as permanently writable.
     *
     * <p>A permanently writable directory will never have its output permissions set by a later
     * call to {@link #setOutputPermissions}.
     */
    void setPermanentlyWritable(Path dir) throws IOException {
      setWritable(dir, DirectoryState.PERMANENTLY_WRITABLE);
    }

    private void setWritable(Path dir, DirectoryState newState) throws IOException {
      AtomicReference<IOException> caughtException = new AtomicReference<>();

      directoryStateMap.compute(
          dir,
          (unusedKey, oldState) -> {
            if (oldState == DirectoryState.TEMPORARILY_WRITABLE
                || oldState == DirectoryState.PERMANENTLY_WRITABLE) {
              // Already writable, but must potentially upgrade from temporary to permanent.
              return newState == DirectoryState.PERMANENTLY_WRITABLE ? newState : oldState;
            }
            try {
              outputDirectoryHelper.createOutputDirectory(dir, execRoot);
              dir.setWritable(true);
            } catch (IOException e) {
              caughtException.set(e);
              return oldState;
            }
            return newState;
          });

      if (caughtException.get() != null) {
        throw caughtException.get();
      }
    }

    /**
     * Sets the output permissions on a directory.
     *
     * <p>If {@link #setPermanentlyWritable} has been previously called on this directory, or if no
     * {@link #setTemporarilyWritable} call has intervened since the last call to {@link
     * #setOutputPermissions}, this is a no-op. Otherwise, the output permissions are set.
     */
    void setOutputPermissions(Path dir) throws IOException {
      AtomicReference<IOException> caughtException = new AtomicReference<>();

      directoryStateMap.compute(
          dir,
          (unusedKey, oldState) -> {
            if (oldState == DirectoryState.OUTPUT_PERMISSIONS
                || oldState == DirectoryState.PERMANENTLY_WRITABLE) {
              // Either the output permissions have already been set, or we're not changing the
              // permissions ever again.
              return oldState;
            }
            try {
              dir.chmod(outputPermissions.getPermissionsMode());
            } catch (IOException e) {
              caughtException.set(e);
              return oldState;
            }
            return DirectoryState.OUTPUT_PERMISSIONS;
          });

      if (caughtException.get() != null) {
        throw caughtException.get();
      }
    }
  }

  private final DirectoryTracker directoryTracker = new DirectoryTracker();

  /** A symlink in the output tree. */
  record Symlink(PathFragment linkExecPath, PathFragment targetExecPath) {
    Symlink {
      requireNonNull(linkExecPath, "linkExecPath");
      requireNonNull(targetExecPath, "targetExecPath");
      checkArgument(!linkExecPath.equals(targetExecPath));
    }

    static Symlink of(PathFragment linkExecPath, PathFragment targetExecPath) {
      return new Symlink(linkExecPath, targetExecPath);
    }
  }

  protected AbstractActionInputPrefetcher(
      Reporter reporter,
      Path execRoot,
      TempPathGenerator tempPathGenerator,
      RemoteOutputChecker remoteOutputChecker,
      ActionOutputDirectoryHelper outputDirectoryHelper,
      OutputPermissions outputPermissions) {
    this.reporter = reporter;
    this.execRoot = execRoot;
    this.tempPathGenerator = tempPathGenerator;
    this.remoteOutputChecker = remoteOutputChecker;
    this.outputDirectoryHelper = outputDirectoryHelper;
    this.outputPermissions = outputPermissions;
  }

  private boolean shouldDownloadFile(Path path, FileArtifactValue metadata) {
    if (!path.exists()) {
      return true;
    }

    // In the most cases, skyframe should be able to detect source files modifications and delete
    // staled outputs before action execution. However, there are some cases where outputs are not
    // tracked by skyframe. We compare the digest here to make sure we don't use staled files.
    try {
      byte[] digest = path.getFastDigest();
      if (digest == null) {
        digest = path.getDigest();
      }
      return !Arrays.equals(digest, metadata.getDigest());
    } catch (IOException ignored) {
      return true;
    }
  }

  protected abstract boolean canDownloadFile(Path path, FileArtifactValue metadata);

  /**
   * Downloads file to the given path via its metadata.
   *
   * @param tempPath the temporary path which the input should be written to.
   */
  protected abstract ListenableFuture<Void> doDownloadFile(
      ActionExecutionMetadata action,
      Reporter reporter,
      Path tempPath,
      PathFragment execPath,
      FileArtifactValue metadata,
      Priority priority,
      Reason reason)
      throws IOException;

  protected void prefetchVirtualActionInput(VirtualActionInput input) throws IOException {}

  /**
   * Fetches remotely stored action outputs and stores them under their path in the output base.
   *
   * <p>The {@code inputs} may not contain any unexpanded directories.
   *
   * <p>This method is safe to be called concurrently from spawn runners before running any local
   * spawn.
   *
   * @return a future that is completed once all downloads have finished.
   */
  @Override
  public ListenableFuture<Void> prefetchFiles(
      ActionExecutionMetadata action,
      Iterable<? extends ActionInput> inputs,
      InputMetadataProvider metadataProvider,
      Priority priority,
      Reason reason) {
    return prefetchFilesInterruptibly(
        action, inputs, metadataProvider::getInputMetadata, priority, reason);
  }

  /**
   * Fetches remotely stored action outputs and stores them under their path in the output base.
   *
   * <p>The {@code inputs} may not contain any unexpanded directories.
   *
   * <p>This method is safe to be called concurrently from spawn runners before running any local
   * spawn.
   *
   * <p>This method is similar to #prefetchFiles() above, but note that {@code metadataSupplier} may
   * throw {@link InterruptedException}. If it does, this method will propagate this exception in
   * the returned future.
   *
   * @return a future that is completed once all downloads have finished.
   */
  @VisibleForTesting
  public ListenableFuture<Void> prefetchFilesInterruptibly(
      ActionExecutionMetadata action,
      Iterable<? extends ActionInput> inputs,
      MetadataSupplier metadataSupplier,
      Priority priority,
      Reason reason) {
    List<ActionInput> files = new ArrayList<>();

    for (ActionInput input : inputs) {
      // Source artifacts don't need to be fetched.
      if (input instanceof Artifact && ((Artifact) input).isSourceArtifact()) {
        continue;
      }

      // Skip empty tree artifacts (non-empty tree artifacts should have already been expanded).
      if (input.isDirectory()) {
        continue;
      }

      files.add(input);
    }

    if (files.isEmpty()) {
      return immediateVoidFuture();
    }

    // Collect the set of directories whose output permissions must be set at the end of this call.
    // This responsibility cannot lie with the downloading of an individual file, because multiple
    // files may be concurrently downloaded into the same directory within a single call to
    // prefetchFiles, and two concurrent calls to prefetchFiles may prefetch the same file. In the
    // latter case, the second call will have its downloads deduplicated against the first call, but
    // it must still synchronize on the output permissions having been set.
    Set<Path> dirsWithOutputPermissions = Sets.newConcurrentHashSet();

    // Using plain futures to avoid RxJava overheads.
    List<ListenableFuture<Void>> transfers = new ArrayList<>(files.size());
    try (var s = Profiler.instance().profile("compose prefetches")) {
      for (var file : files) {
        transfers.add(
            prefetchFile(
                action, dirsWithOutputPermissions, metadataSupplier, file, priority, reason));
      }
    }

    ListenableFuture<Void> mergedTransfer;
    try (var s = Profiler.instance().profile("mergeBulkTransfer")) {
      mergedTransfer = mergeBulkTransfer(transfers);
    }

    return Futures.transformAsync(
        mergedTransfer,
        unused -> {
          try {
            // Set output permissions on tree artifact subdirectories, matching the behavior of
            // SkyframeActionExecutor#checkOutputs for artifacts produced by local actions.
            for (Path dir : dirsWithOutputPermissions) {
              directoryTracker.setOutputPermissions(dir);
            }
          } catch (IOException e) {
            return immediateFailedFuture(e);
          }
          return immediateVoidFuture();
        },
        directExecutor());
  }

  private ListenableFuture<Void> prefetchFile(
      ActionExecutionMetadata action,
      Set<Path> dirsWithOutputPermissions,
      MetadataSupplier metadataSupplier,
      ActionInput input,
      Priority priority,
      Reason reason) {
    try {
      if (input instanceof VirtualActionInput virtualActionInput) {
        prefetchVirtualActionInput(virtualActionInput);
        return immediateVoidFuture();
      }

      PathFragment execPath = input.getExecPath();

      FileArtifactValue metadata = metadataSupplier.getMetadata(input);
      if (metadata == null || !canDownloadFile(execRoot.getRelative(execPath), metadata)) {
        return immediateVoidFuture();
      }

      @Nullable Symlink symlink = maybeGetSymlink(action, input, metadata, metadataSupplier);

      if (symlink != null) {
        checkState(execPath.startsWith(symlink.linkExecPath()));
        execPath =
            symlink.targetExecPath().getRelative(execPath.relativeTo(symlink.linkExecPath()));
      }

      @Nullable PathFragment treeRootExecPath = maybeGetTreeRoot(action, input, metadataSupplier);

      Completable result =
          downloadFileNoCheckRx(
              action,
              execRoot.getRelative(execPath),
              treeRootExecPath != null ? execRoot.getRelative(treeRootExecPath) : null,
              dirsWithOutputPermissions,
              input,
              metadata,
              priority,
              reason);

      if (symlink != null) {
        result = result.andThen(plantSymlink(symlink));
      }

      return toListenableFuture(result);
    } catch (IOException | InterruptedException e) {
      return immediateFailedFuture(e);
    }
  }

  /**
   * For an input belonging to a tree artifact, returns the prefetch exec path of the tree artifact
   * root. Otherwise, returns null.
   *
   * <p>Some artifacts (notably, those created by {@code ctx.actions.symlink}) are materialized in
   * the output tree as a symlink to another artifact, as indicated by the {@link
   * FileArtifactValue#getResolvedPath()} field in their metadata.
   */
  @Nullable
  private PathFragment maybeGetTreeRoot(
      ActionExecutionMetadata action, ActionInput input, MetadataSupplier metadataSupplier)
      throws IOException, InterruptedException {
    if (!(input instanceof TreeFileArtifact treeFile)) {
      return null;
    }
    SpecialArtifact treeArtifact = treeFile.getParent();
    FileArtifactValue treeMetadata = metadataSupplier.getMetadata(treeArtifact);
    if (treeMetadata == null) {
      if (!treeFile.isChildOfDeclaredDirectory() && action.getOutputs().contains(treeFile)) {
        // If this file is produced by an action template, the full tree artifact metadata might
        // not be available yet. However, we know with certainty that the file is not materialized
        // as a symlink.
        return null;
      }
      throw new IllegalStateException(
          String.format("input %s belongs to a tree artifact whose metadata is missing", treeFile));
    }
    PathFragment resolvedPath = treeMetadata.getResolvedPath();
    if (resolvedPath != null) {
      return resolvedPath.relativeTo(execRoot.asFragment());
    }
    return treeArtifact.getExecPath();
  }

  /**
   * Returns the symlink to be planted in the output tree for artifacts that are prefetched into a
   * different location.
   *
   * <p>Some artifacts (notably, those created by {@code ctx.actions.symlink}) are materialized in
   * the output tree as a symlink to another artifact, as indicated by the {@link
   * FileArtifactValue#getResolvedPath()} field in their (or their parent tree artifact's) metadata.
   */
  @Nullable
  private Symlink maybeGetSymlink(
      ActionExecutionMetadata action,
      ActionInput input,
      FileArtifactValue metadata,
      MetadataSupplier metadataSupplier)
      throws IOException, InterruptedException {
    if (input instanceof TreeFileArtifact treeFile) {
      SpecialArtifact treeArtifact = treeFile.getParent();
      FileArtifactValue treeMetadata = metadataSupplier.getMetadata(treeArtifact);
      if (treeMetadata == null) {
        if (!treeFile.isChildOfDeclaredDirectory() && action.getOutputs().contains(treeFile)) {
          // If this file is produced by an action template, the full tree artifact metadata might
          // not be available yet. However, we know with certainty that the file is not materialized
          // as a symlink.
          return null;
        }
        throw new IllegalStateException(
            String.format(
                "input %s belongs to a tree artifact whose metadata is missing", treeFile));
      }
      return maybeGetSymlink(action, treeArtifact, treeMetadata, metadataSupplier);
    }
    PathFragment execPath = input.getExecPath();
    PathFragment resolvedExecPath = execPath;
    if (metadata.getResolvedPath() != null) {
      resolvedExecPath = metadata.getResolvedPath().relativeTo(execRoot.asFragment());
    }
    if (!resolvedExecPath.equals(execPath)) {
      return Symlink.of(execPath, resolvedExecPath);
    }
    return null;
  }

  private Path resolveOneSymlink(Path path) throws IOException {
    var targetPathFragment = path.readSymbolicLink();
    if (targetPathFragment.isAbsolute()) {
      return path.getFileSystem().getPath(targetPathFragment);
    } else {
      return checkNotNull(path.getParentDirectory()).getRelative(targetPathFragment);
    }
  }

  private Path maybeResolveSymlink(Path path) throws IOException {
    // Potentially resolves a symlink to its target path. This differs from
    // Path#resolveSymbolicLinks() that:
    //   1. Path#resolveSymbolicLinks() checks each segment of the path, but we assume there is no
    //      intermediate symlink because they should've been already normalized for outputs.
    //   2. In case of dangling symlink, we return the target path instead of throwing
    //      FileNotFoundException because we want to download output to that target path.
    var maxAttempt = 32;
    while (path.isSymbolicLink() && maxAttempt-- > 0) {
      var resolvedPath = resolveOneSymlink(path);
      if (resolvedPath.asFragment().equals(path.asFragment())) {
        throw new FileSymlinkLoopException(path.asFragment());
      }
      path = resolvedPath;
    }
    if (maxAttempt <= 0) {
      throw new FileSymlinkLoopException(path.asFragment());
    }
    return path;
  }

  private Completable downloadFileNoCheckRx(
      ActionExecutionMetadata action,
      Path path,
      @Nullable Path treeRoot,
      Set<Path> dirsWithOutputPermissions,
      ActionInput actionInput,
      FileArtifactValue metadata,
      Priority priority,
      Reason reason) {
    // If the path to be prefetched is a non-dangling symlink, prefetch its target path instead.
    // Note that this only applies to symlinks created by spawns (or, currently, with the internal
    // version of BwoB); symlinks created in-process through an ActionFileSystem should have already
    // been canonicalized by maybeGetSymlink.
    try {
      if (treeRoot != null) {
        var treeRootRelativePath = path.relativeTo(treeRoot);
        treeRoot = maybeResolveSymlink(treeRoot);
        path = treeRoot.getRelative(treeRootRelativePath);
      } else {
        path = maybeResolveSymlink(path);
      }
    } catch (IOException e) {
      return Completable.error(e);
    }

    if (actionInput instanceof Artifact && ((Artifact) actionInput).isChildOfDeclaredDirectory()) {
      // Arrange for the output permissions to be set on every directory inside the tree artifact.
      // This must be done at assembly time to ensure that the permissions are set before the
      // prefetchFiles call returns, even when the actual downloads are deduplicated against a
      // concurrent call. See finalizeDownload for why we don't do so in other cases.
      checkNotNull(treeRoot);
      for (Path dir = path.getParentDirectory();
          dir.startsWith(treeRoot);
          dir = dir.getParentDirectory()) {
        if (!dirsWithOutputPermissions.add(dir)) {
          break;
        }
      }
    }

    Path finalPath = path;

    Completable download =
        usingTempPath(
            (tempPath, alreadyDeleted) ->
                toCompletable(
                        () ->
                            doDownloadFile(
                                action,
                                reporter,
                                tempPath,
                                finalPath.relativeTo(execRoot),
                                metadata,
                                priority,
                                reason),
                        directExecutor())
                    .doOnComplete(
                        () -> {
                          finalizeDownload(
                              metadata, tempPath, finalPath, dirsWithOutputPermissions);
                          alreadyDeleted.set(true);
                        })
                    .doOnError(
                        error -> {
                          if (error instanceof CacheNotFoundException cacheNotFoundException) {
                            reporter.post(
                                new LostInputsEvent(cacheNotFoundException.getMissingDigest()));
                          }
                        }));

    return downloadCache.executeIfNot(
        finalPath,
        Completable.defer(
            () -> {
              if (shouldDownloadFile(finalPath, metadata)) {
                return download;
              }
              return Completable.complete();
            }));
  }

  private void finalizeDownload(
      FileArtifactValue metadata, Path tmpPath, Path finalPath, Set<Path> dirsWithOutputPermissions)
      throws IOException {
    Path parentDir = checkNotNull(finalPath.getParentDirectory());

    // Ensure the parent directory exists and is writable. We cannot rely on this precondition to be
    // have been established by the execution of the owning action in a previous invocation, since
    // the output tree may have been externally modified in between invocations.
    if (dirsWithOutputPermissions.contains(parentDir)) {
      // The file belongs to a tree artifact created by an action that declared an output directory
      // (as opposed to an action template expansion). The output permissions should be set on the
      // parent directory after prefetching.
      directoryTracker.setTemporarilyWritable(parentDir);
    } else {
      // There are three cases:
      //   (1) The file does not belong to a tree artifact.
      //   (2) The file belongs to a tree artifact created by an action template expansion.
      //   (3) The file belongs to a tree artifact but we don't know it. This can occur when the
      //       file belongs to a tree artifact inside a fileset (see b/254844173).
      // In case (1), the parent directory is a package or a subdirectory of a package, and should
      // remain writable. In cases (2) and (3), even though we arguably ought to set the output
      // permissions on the parent directory to match the outcome of a locally executed action, we
      // choose not to do it and avoid the additional implementation complexity required to detect a
      // race condition between concurrent calls touching the same directory.
      directoryTracker.setPermanentlyWritable(parentDir);
    }

    // Set output permissions on files, matching the behavior of SkyframeActionExecutor#checkOutputs
    // for artifacts produced by local actions.
    tmpPath.chmod(outputPermissions.getPermissionsMode());
    FileSystemUtils.moveFile(tmpPath, finalPath);

    // Set the contents proxy when supported, to make future modification checks cheaper.
    metadata.setContentsProxy(FileContentsProxy.create(finalPath.stat()));
  }

  private interface TaskWithTempPath {
    Completable run(Path tempPath, AtomicBoolean alreadyDeleted);
  }

  /**
   * Runs a task with a temporary path.
   *
   * <p>The temporary path will be deleted once the task is done. Set {@code alreadyDeleted} to
   * signal that deletion is no longer needed.
   */
  private Completable usingTempPath(TaskWithTempPath task) {
    AtomicBoolean alreadyDeleted = new AtomicBoolean(false);
    return Completable.using(
        tempPathGenerator::generateTempPath,
        (tempPath) -> task.run(tempPath, alreadyDeleted),
        tempPath -> {
          if (!alreadyDeleted.get()) {
            deletePartialDownload(tempPath);
          }
        },
        // Clean up after the upstream is disposed to ensure tempPath won't be touched further.
        /* eager= */ false);
  }

  private void deletePartialDownload(Path path) {
    try {
      path.delete();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Failed to delete output file after incomplete download: %s", path);
    }
  }

  private Completable plantSymlink(Symlink symlink) {
    return downloadCache.executeIfNot(
        execRoot.getRelative(symlink.linkExecPath()),
        Completable.defer(
            () -> {
              Path link = execRoot.getRelative(symlink.linkExecPath());
              Path target = execRoot.getRelative(symlink.targetExecPath());
              // Delete the link path if it already exists. This is the case for tree artifacts,
              // whose root directory is created before the action runs.
              link.delete();
              link.createSymbolicLink(target);
              return Completable.complete();
            }));
  }

  public ImmutableSet<Path> downloadedFiles() {
    return downloadCache.getFinishedTasks();
  }

  public ImmutableSet<Path> downloadsInProgress() {
    return downloadCache.getInProgressTasks();
  }

  @VisibleForTesting
  public AsyncTaskCache.NoResult<Path> getDownloadCache() {
    return downloadCache;
  }

  public void shutdown() {
    downloadCache.shutdown();
    while (true) {
      try {
        downloadCache.awaitTermination();
        break;
      } catch (InterruptedException ignored) {
        downloadCache.shutdownNow();
      }
    }
  }

  public void finalizeAction(Action action, OutputMetadataStore outputMetadataStore)
      throws IOException, InterruptedException {
    List<Artifact> outputsToDownload = new ArrayList<>();
    for (Artifact output : action.getOutputs()) {
      if (outputMetadataStore.artifactOmitted(output)) {
        continue;
      }

      var metadata = outputMetadataStore.getOutputMetadata(output);
      if (!metadata.isRemote()) {
        continue;
      }

      if (output.isTreeArtifact()) {
        var children =
            outputMetadataStore.getTreeArtifactValue((SpecialArtifact) output).getChildren();
        for (var file : children) {
          if (remoteOutputChecker.shouldDownloadOutput(file)) {
            outputsToDownload.add(file);
          }
        }
      } else {
        if (remoteOutputChecker.shouldDownloadOutput(output)) {
          outputsToDownload.add(output);
        }
      }
    }

    if (!outputsToDownload.isEmpty()) {
      try (var s = Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "Download outputs")) {
        getFromFuture(
            prefetchFilesInterruptibly(
                action,
                outputsToDownload,
                outputMetadataStore::getOutputMetadata,
                Priority.HIGH,
                Reason.OUTPUTS));
      }
    }
  }

  public void flushOutputTree() throws InterruptedException {
    downloadCache.awaitInProgressTasks();
  }

  public RemoteOutputChecker getRemoteOutputChecker() {
    return remoteOutputChecker;
  }
}
