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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.RxFutures.toCompletable;
import static com.google.devtools.build.lib.remote.util.RxFutures.toSingle;
import static com.google.devtools.build.lib.remote.util.RxUtils.mergeBulkTransfer;
import static com.google.devtools.build.lib.remote.util.RxUtils.toTransferResult;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.reverseOrder;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.SymlinkAbsolutePathStrategy;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionUploadFinishedEvent;
import com.google.devtools.build.lib.actions.ActionUploadStartedEvent;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.NamedForkJoinPool;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.RxUtils;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** UploadManifest adds output metadata to a {@link ActionResult}. */
public class UploadManifest {
  private static final Profiler profiler = Profiler.instance();

  private final DigestUtil digestUtil;
  private final RemotePathResolver remotePathResolver;
  private final ActionResult.Builder result;
  private final boolean allowAbsoluteSymlinks;
  private final ConcurrentHashMap<Digest, Path> digestToFile = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Digest, ByteString> digestToBlobs = new ConcurrentHashMap<>();
  @Nullable private ActionKey actionKey;
  private Digest stderrDigest;
  private Digest stdoutDigest;

  public static UploadManifest create(
      RemoteOptions remoteOptions,
      CacheCapabilities cacheCapabilities,
      DigestUtil digestUtil,
      RemotePathResolver remotePathResolver,
      ActionKey actionKey,
      Action action,
      Command command,
      Collection<Path> outputFiles,
      FileOutErr outErr,
      int exitCode,
      Instant startTime,
      int wallTimeInMs)
      throws ExecException, IOException, InterruptedException {
    ActionResult.Builder result = ActionResult.newBuilder();
    result.setExitCode(exitCode);

    UploadManifest manifest =
        new UploadManifest(
            digestUtil,
            remotePathResolver,
            result,
            /* allowAbsoluteSymlinks= */ cacheCapabilities
                .getSymlinkAbsolutePathStrategy()
                .equals(SymlinkAbsolutePathStrategy.Value.ALLOWED));
    manifest.addFiles(outputFiles);
    manifest.setStdoutStderr(outErr);
    manifest.addAction(actionKey, action, command);
    if (manifest.getStderrDigest() != null) {
      result.setStderrDigest(manifest.getStderrDigest());
    }
    if (manifest.getStdoutDigest() != null) {
      result.setStdoutDigest(manifest.getStdoutDigest());
    }

    // if wallTime is zero, than it's not set
    if (startTime != null && wallTimeInMs != 0) {
      Timestamp startTimestamp = instantToTimestamp(startTime);
      Timestamp completedTimestamp = instantToTimestamp(startTime.plusMillis(wallTimeInMs));
      result
          .getExecutionMetadataBuilder()
          .setWorkerStartTimestamp(startTimestamp)
          .setExecutionStartTimestamp(startTimestamp)
          .setExecutionCompletedTimestamp(completedTimestamp)
          .setWorkerCompletedTimestamp(completedTimestamp);
    }

    return manifest;
  }

  private static Timestamp instantToTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  /**
   * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
   * builder is populated through a call to {@link #addFiles(Collection)}.
   *
   * @param allowAbsoluteSymlinks whether the remote allows uploading absolute symlinks
   */
  @VisibleForTesting
  public UploadManifest(
      DigestUtil digestUtil,
      RemotePathResolver remotePathResolver,
      ActionResult.Builder result,
      boolean allowAbsoluteSymlinks) {
    this.digestUtil = digestUtil;
    this.remotePathResolver = remotePathResolver;
    this.result = result;
    this.allowAbsoluteSymlinks = allowAbsoluteSymlinks;
  }

  private void setStdoutStderr(FileOutErr outErr) throws IOException {
    if (outErr.getErrorPath().exists()) {
      stderrDigest = digestUtil.compute(outErr.getErrorPath());
      digestToFile.put(stderrDigest, outErr.getErrorPath());
    }
    if (outErr.getOutputPath().exists()) {
      stdoutDigest = digestUtil.compute(outErr.getOutputPath());
      digestToFile.put(stdoutDigest, outErr.getOutputPath());
    }
  }

  /**
   * Add a collection of files, directories or symlinks to the manifest.
   *
   * <p>Adding a directory has the effect of:
   *
   * <ol>
   *   <li>uploading a {@link Tree} protobuf message from which the whole structure of the
   *       directory, including the descendants, can be reconstructed.
   *   <li>uploading all of the non-directory descendant files.
   * </ol>
   *
   * <p>Note that the manifest describes the outcome of a spawn, not of an action. In particular,
   * it's possible for an output to be missing or to have been created with an unsuitable file type
   * for the corresponding {@link Artifact} (e.g., a directory where a file was expected, or a
   * non-symlink where a symlink was expected). Except for the oddity noted in the next paragraph,
   * outputs are always uploaded according to the filesystem state. A type mismatch may later cause
   * execution to fail, but that's an action-level concern.
   *
   * <p>For historical reasons, non-dangling absolute symlinks are uploaded as the file or directory
   * they point to. This is inconsistent with the treatment of non-dangling relative symlinks, which
   * are uploaded as such, but fixing it would now require an incompatible change.
   *
   * <p>All files are uploaded with the executable bit set, in accordance with input Merkle trees.
   * This does not affect correctness since we always set the output permissions to 0555 or 0755
   * after execution, both for cache hits and misses.
   */
  @VisibleForTesting
  void addFiles(Collection<Path> files) throws ExecException, IOException, InterruptedException {
    for (Path file : files) {
      // TODO(ulfjack): Maybe pass in a SpawnResult here, add a list of output files to that, and
      // rely on the local spawn runner to stat the files, instead of statting here.
      FileStatus statNoFollow = file.statIfFound(Symlinks.NOFOLLOW);
      // TODO(#6547): handle the case where the parent directory of the output file is an
      // output symlink.
      if (statNoFollow == null) {
        // Ignore missing outputs.
        continue;
      }
      if (statNoFollow.isFile() && !statNoFollow.isSpecialFile()) {
        Digest digest = digestUtil.compute(file, statNoFollow);
        addFile(digest, file);
        continue;
      }
      if (statNoFollow.isDirectory()) {
        addDirectory(file);
        continue;
      }
      if (statNoFollow.isSymbolicLink()) {
        PathFragment target = file.readSymbolicLink();
        // Need to resolve the symbolic link to know what to add, file or directory.
        FileStatus statFollow = file.statIfFound(Symlinks.FOLLOW);
        if (statFollow == null) {
          // Symlink uploaded as a symlink. Report it as a file since we don't know any better.
          if (target.isAbsolute()) {
            checkAbsoluteSymlinkAllowed(file, target);
          }
          addFileSymbolicLink(file, target);
          continue;
        }
        if (statFollow.isFile() && !statFollow.isSpecialFile()) {
          if (target.isAbsolute()) {
            // Symlink to file uploaded as a file.
            addFile(digestUtil.compute(file, statFollow), file);
          } else {
            // Symlink to file uploaded as a symlink.
            if (target.isAbsolute()) {
              checkAbsoluteSymlinkAllowed(file, target);
            }
            addFileSymbolicLink(file, target);
          }
          continue;
        }
        if (statFollow.isDirectory()) {
          if (target.isAbsolute()) {
            // Symlink to directory uploaded as a directory.
            addDirectory(file);
          } else {
            // Symlink to directory uploaded as a symlink.
            if (target.isAbsolute()) {
              checkAbsoluteSymlinkAllowed(file, target);
            }
            addDirectorySymbolicLink(file, target);
          }
          continue;
        }
      }
      // Special file or dereferenced symlink to special file.
      rejectSpecialFile(file);
    }
  }

  /**
   * Adds an action and command protos to upload. They need to be uploaded as part of the action
   * result.
   */
  private void addAction(RemoteCacheClient.ActionKey actionKey, Action action, Command command) {
    Preconditions.checkState(this.actionKey == null, "Already added an action");
    this.actionKey = actionKey;
    digestToBlobs.put(actionKey.getDigest(), action.toByteString());
    digestToBlobs.put(action.getCommandDigest(), command.toByteString());
  }

  /** Map of digests to file paths to upload. */
  public Map<Digest, Path> getDigestToFile() {
    return digestToFile;
  }

  /**
   * Map of digests to chunkers to upload. When the file is a regular, non-directory file it is
   * transmitted through {@link #getDigestToFile()}. When it is a directory, it is transmitted as a
   * {@link Tree} protobuf message through {@link #getDigestToBlobs()}.
   */
  public Map<Digest, ByteString> getDigestToBlobs() {
    return digestToBlobs;
  }

  @Nullable
  public Digest getStdoutDigest() {
    return stdoutDigest;
  }

  @Nullable
  public Digest getStderrDigest() {
    return stderrDigest;
  }

  private void addFileSymbolicLink(Path file, PathFragment target) {
    OutputSymlink outputSymlink =
        OutputSymlink.newBuilder()
            .setPath(internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
            .setTarget(internalToUnicode(target.toString()))
            .build();
    result.addOutputFileSymlinks(outputSymlink);
    result.addOutputSymlinks(outputSymlink);
  }

  private void addDirectorySymbolicLink(Path file, PathFragment target) {
    OutputSymlink outputSymlink =
        OutputSymlink.newBuilder()
            .setPath(internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
            .setTarget(internalToUnicode(target.toString()))
            .build();
    result.addOutputDirectorySymlinks(outputSymlink);
    result.addOutputSymlinks(outputSymlink);
  }

  private void addFile(Digest digest, Path file) {
    result
        .addOutputFilesBuilder()
        .setPath(internalToUnicode(remotePathResolver.localPathToOutputPath(file)))
        .setDigest(digest)
        .setIsExecutable(true);

    digestToFile.put(digest, file);
  }

  private static final class WrappedException extends RuntimeException {
    private final Exception wrapped;

    WrappedException(Exception wrapped) {
      super(wrapped);
      this.wrapped = wrapped;
    }

    Exception unwrap() {
      return wrapped;
    }
  }

  /** A thread pool shared by all {@link DirectoryBuilder} instances. */
  private static final ForkJoinPool VISITOR_POOL =
      NamedForkJoinPool.newNamedPool(
          "upload-manifest-directory-visitor", Runtime.getRuntime().availableProcessors());

  /**
   * A {@link DirectoryBuilder} constructs a {@link Tree} message for an output directory, doing as
   * much as possible in parallel.
   */
  private class DirectoryBuilder extends AbstractQueueVisitor {
    private final Path rootDir;

    // Directories found during the traversal, including the root.
    // Sorted in reverse so that children iterate before parents.
    private final SortedSet<Path> dirs =
        Collections.synchronizedSortedSet(new TreeSet<Path>(reverseOrder()));

    // Maps each directory found during the traversal to its subdirectories.
    private final SortedSetMultimap<Path, Path> dirToSubdirs =
        Multimaps.synchronizedSortedSetMultimap(TreeMultimap.create());

    // Maps each directory found during the traversal to its files.
    private final SortedSetMultimap<Path, FileNode> dirToFiles =
        Multimaps.synchronizedSortedSetMultimap(
            TreeMultimap.<Path, FileNode>create(naturalOrder(), comparing(FileNode::getName)));

    // Maps each directory found during the traversal to its symlinks.
    private final SortedSetMultimap<Path, SymlinkNode> dirToSymlinks =
        Multimaps.synchronizedSortedSetMultimap(
            TreeMultimap.<Path, SymlinkNode>create(
                naturalOrder(), comparing(SymlinkNode::getName)));

    DirectoryBuilder(Path rootDir) {
      super(
          VISITOR_POOL,
          ExecutorOwnership.SHARED,
          ExceptionHandlingMode.FAIL_FAST,
          ErrorClassifier.DEFAULT);
      this.rootDir = checkNotNull(rootDir);
    }

    /**
     * Returns a {@link Tree} message in wire format describing the directory contents, obeying the
     * requirements of the {@code OutputDirectory.is_topologically_sorted} field.
     */
    ByteString build() throws ExecException, IOException, InterruptedException {
      // Collect directory entries (subdirectories, files, symlinks) in parallel.
      // This is a major speedup for large tree artifacts with hundreds of thousands of files.
      execute(() -> visit(rootDir, Dirent.Type.DIRECTORY));
      try {
        awaitQuiescence(true);
      } catch (WrappedException e) {
        Throwables.throwIfInstanceOf(e.unwrap(), ExecException.class);
        Throwables.throwIfInstanceOf(e.unwrap(), IOException.class);
        throw new AssertionError("unexpected exception", e.unwrap());
      }

      // Compute the Directory message for every node, including the root. Since each directory
      // references its subdirectories by their digest, the messages must be computed in topological
      // order (children before parents). In addition, the contents of each Directory message must
      // be sorted, which is already ensured by the use of sorted maps.

      HashMap<Path, Digest> dirToDigest = new HashMap<>();
      LinkedHashSet<ByteString> dirBlobs = new LinkedHashSet<>();

      for (Path dir : dirs) {
        Directory.Builder builder = Directory.newBuilder();
        builder.addAllFiles(dirToFiles.get(dir));
        builder.addAllSymlinks(dirToSymlinks.get(dir));
        for (Path subdir : dirToSubdirs.get(dir)) {
          checkState(subdir.getParentDirectory().equals(dir));
          builder
              .addDirectoriesBuilder()
              .setName(subdir.getBaseName())
              .setDigest(dirToDigest.get(subdir));
        }
        ByteString dirBlob = builder.build().toByteString();

        dirToDigest.put(dir, digestUtil.compute(dirBlob.toByteArray()));
        dirBlobs.add(dirBlob);
      }

      // Convert individual Directory messages to a Tree message. As we want the records to be
      // topologically sorted (parents before children), we iterate over the directories in reverse
      // insertion order. We construct the message through direct byte manipulation to ensure that
      // the strict requirements on the encoding are observed.

      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(byteArrayOutputStream);
      int fieldNumber = TREE_ROOT_FIELD_NUMBER;
      for (ByteString directory : Lists.reverse(new ArrayList<>(dirBlobs))) {
        codedOutputStream.writeBytes(fieldNumber, directory);
        fieldNumber = TREE_CHILDREN_FIELD_NUMBER;
      }
      codedOutputStream.flush();

      return ByteString.copyFrom(byteArrayOutputStream.toByteArray());
    }

    private void visit(Path path, Dirent.Type type) {
      try {
        if (type == Dirent.Type.FILE) {
          visitAsFile(path);
          return;
        }
        if (type == Dirent.Type.DIRECTORY) {
          visitAsDirectory(path);
          for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
            Path childPath = path.getChild(dirent.getName());
            Dirent.Type childType = dirent.getType();
            execute(() -> visit(childPath, childType));
          }
          return;
        }
        if (type == Dirent.Type.SYMLINK) {
          PathFragment target = path.readSymbolicLink();
          FileStatus statFollow = path.statIfFound(Symlinks.FOLLOW);
          if (statFollow == null || !target.isAbsolute()) {
            // Symlink uploaded as a symlink.
            if (target.isAbsolute()) {
              checkAbsoluteSymlinkAllowed(path, target);
            }
            visitAsSymlink(path, target);
            return;
          }
          if (statFollow.isFile() && !statFollow.isSpecialFile()) {
            // Symlink to file uploaded as a file.
            execute(() -> visit(path, Dirent.Type.FILE));
            return;
          }
          if (statFollow.isDirectory()) {
            // Symlink to directory uploaded as a directory.
            execute(() -> visit(path, Dirent.Type.DIRECTORY));
            return;
          }
        }
        rejectSpecialFile(path);
      } catch (ExecException | IOException e) {
        // We can't throw checked exceptions here since AQV expects Runnables
        throw new WrappedException(e);
      }
    }

    private void visitAsDirectory(Path path) {
      dirs.add(path);
      if (!path.equals(rootDir)) {
        dirToSubdirs.put(path.getParentDirectory(), path);
      }
    }

    private void visitAsFile(Path path) throws IOException {
      Path parentPath = path.getParentDirectory();
      Digest digest = digestUtil.compute(path);
      FileNode node =
          FileNode.newBuilder()
              .setName(path.getBaseName())
              .setDigest(digest)
              .setIsExecutable(true)
              .build();
      digestToFile.put(digest, path);
      dirToFiles.put(parentPath, node);
    }

    private void visitAsSymlink(Path path, PathFragment target) {
      Path parentPath = path.getParentDirectory();
      SymlinkNode node =
          SymlinkNode.newBuilder().setName(path.getBaseName()).setTarget(target.toString()).build();
      dirToSymlinks.put(parentPath, node);
    }
  }

  // Field numbers of the 'root' and 'directory' fields in the Tree message.
  private static final int TREE_ROOT_FIELD_NUMBER =
      Tree.getDescriptor().findFieldByName("root").getNumber();
  private static final int TREE_CHILDREN_FIELD_NUMBER =
      Tree.getDescriptor().findFieldByName("children").getNumber();

  private void addDirectory(Path dir) throws ExecException, IOException, InterruptedException {
    ByteString treeBlob = new DirectoryBuilder(dir).build();
    Digest treeDigest = digestUtil.compute(treeBlob.toByteArray());

    result
        .addOutputDirectoriesBuilder()
        .setPath(internalToUnicode(remotePathResolver.localPathToOutputPath(dir)))
        .setTreeDigest(treeDigest)
        .setIsTopologicallySorted(true);

    digestToBlobs.put(treeDigest, treeBlob);
  }

  private void checkAbsoluteSymlinkAllowed(Path file, PathFragment target) throws IOException {
    if (!allowAbsoluteSymlinks) {
      throw new IOException(
          String.format(
              "Spawn output %s is an absolute symbolic link to %s, which is not allowed by"
                  + " the remote cache",
              file, target));
    }
  }

  private void rejectSpecialFile(Path path) throws ExecException {
    // TODO(tjgq): Consider treating special files as regular, following Skyframe.
    // (On the other hand, they seem to be only useful for testing purposes, so we might instead
    // want to forbid them entirely.)
    String message =
        String.format(
            "Spawn output %s is a special file. Only regular files, directories or symlinks may be "
                + "uploaded to a remote cache.",
            path);

    FailureDetail failureDetail =
        FailureDetail.newBuilder()
            .setMessage(message)
            .setRemoteExecution(RemoteExecution.newBuilder().setCode(Code.ILLEGAL_OUTPUT))
            .build();
    throw new UserExecException(failureDetail);
  }

  @VisibleForTesting
  ActionResult getActionResult() {
    return result.build();
  }

  /** Uploads outputs and action result (if exit code is 0) to remote cache. */
  public ActionResult upload(
      RemoteActionExecutionContext context,
      CombinedCache combinedCache,
      ExtendedEventHandler reporter)
      throws IOException, InterruptedException, ExecException {
    try {
      return uploadAsync(context, combinedCache, reporter).blockingGet();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        throwIfInstanceOf(cause, InterruptedException.class);
        throwIfInstanceOf(cause, IOException.class);
        throwIfInstanceOf(cause, ExecException.class);
      }
      throw e;
    }
  }

  private Completable upload(
      RemoteActionExecutionContext context, CombinedCache combinedCache, Digest digest) {
    Path file = digestToFile.get(digest);
    if (file != null) {
      return toCompletable(() -> combinedCache.uploadFile(context, digest, file), directExecutor());
    }

    ByteString blob = digestToBlobs.get(digest);
    if (blob == null) {
      String message = "FindMissingBlobs call returned an unknown digest: " + digest;
      return Completable.error(new IOException(message));
    }

    return toCompletable(() -> combinedCache.uploadBlob(context, digest, blob), directExecutor());
  }

  private static void reportUploadStarted(
      ExtendedEventHandler reporter,
      @Nullable ActionExecutionMetadata action,
      Store store,
      Iterable<Digest> digests) {
    if (action != null) {
      for (Digest digest : digests) {
        reporter.post(ActionUploadStartedEvent.create(action, store, digest));
      }
    }
  }

  private static void reportUploadFinished(
      ExtendedEventHandler reporter,
      @Nullable ActionExecutionMetadata action,
      Store store,
      Iterable<Digest> digests) {
    if (action != null) {
      for (Digest digest : digests) {
        reporter.post(ActionUploadFinishedEvent.create(action, store, digest));
      }
    }
  }

  /**
   * Returns a {@link Single} which upon subscription will upload outputs and action result (if exit
   * code is 0) to remote cache.
   */
  public Single<ActionResult> uploadAsync(
      RemoteActionExecutionContext context,
      CombinedCache combinedCache,
      ExtendedEventHandler reporter) {
    Collection<Digest> digests = new ArrayList<>();
    digests.addAll(digestToFile.keySet());
    digests.addAll(digestToBlobs.keySet());

    ActionExecutionMetadata action = context.getSpawnOwner();

    Flowable<RxUtils.TransferResult> bulkTransfers =
        toSingle(() -> findMissingDigests(context, combinedCache, digests), directExecutor())
            .doOnSubscribe(d -> reportUploadStarted(reporter, action, Store.CAS, digests))
            .doOnError(error -> reportUploadFinished(reporter, action, Store.CAS, digests))
            .doOnDispose(() -> reportUploadFinished(reporter, action, Store.CAS, digests))
            .doOnSuccess(
                missingDigests -> {
                  List<Digest> existedDigests =
                      digests.stream()
                          .filter(digest -> !missingDigests.contains(digest))
                          .collect(Collectors.toList());
                  reportUploadFinished(reporter, action, Store.CAS, existedDigests);
                })
            .flatMapPublisher(Flowable::fromIterable)
            .flatMapSingle(
                digest ->
                    toTransferResult(upload(context, combinedCache, digest))
                        .doFinally(
                            () ->
                                reportUploadFinished(
                                    reporter, action, Store.CAS, ImmutableList.of(digest))));
    Completable uploadOutputs = mergeBulkTransfer(bulkTransfers);

    ActionResult actionResult = result.build();
    Completable uploadActionResult = Completable.complete();
    if (actionResult.getExitCode() == 0 && actionKey != null) {
      uploadActionResult =
          toCompletable(
                  () -> combinedCache.uploadActionResult(context, actionKey, actionResult),
                  directExecutor())
              .doOnSubscribe(
                  d ->
                      reportUploadStarted(
                          reporter, action, Store.AC, ImmutableList.of(actionKey.getDigest())))
              .doFinally(
                  () ->
                      reportUploadFinished(
                          reporter, action, Store.AC, ImmutableList.of(actionKey.getDigest())));
    }

    return Completable.concatArray(uploadOutputs, uploadActionResult).toSingleDefault(actionResult);
  }

  private ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
      RemoteActionExecutionContext context,
      CombinedCache combinedCache,
      Collection<Digest> digests) {
    long startTime = Profiler.nanoTimeMaybe();

    var future = combinedCache.findMissingDigests(context, digests);

    if (profiler.isActive()) {
      future.addListener(
          () -> profiler.logSimpleTask(startTime, ProfilerTask.INFO, "findMissingDigests"),
          directExecutor());
    }

    return future;
  }
}
