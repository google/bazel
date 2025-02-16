// Copyright 2017 The Bazel Authors. All rights reserved.
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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.common.ProgressStatusListener.NO_ACTION;
import static com.google.devtools.build.lib.remote.util.Utils.bytesCountToDisplayString;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.exec.SpawnCheckingCacheEvent;
import com.google.devtools.build.lib.exec.SpawnProgressEvent;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.LazyFileOutputStream;
import com.google.devtools.build.lib.remote.common.DirectCopyOutputStream;
import com.google.devtools.build.lib.remote.common.OutputDigestMismatchException;
import com.google.devtools.build.lib.remote.common.ProgressStatusListener;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.AsyncTaskCache;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.RxFutures;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import io.netty.util.AbstractReferenceCounted;
import io.reactivex.rxjava3.core.Completable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Provides unified access to a disk cache, remote cache, or both.
 *
 * <p>The cache is reference counted. Initially, the reference count is 1. Use {@link #retain()} to
 * increase and {@link #release()} to decrease the reference count respectively. Once the reference
 * count is reached to 0, the underlying resources will be released (after pending I/O is finished).
 *
 * <p>Use {@link #awaitTermination()} to wait for pending I/O to finish. Use {@link #shutdownNow()}
 * to cancel all pending I/O and reject new requests.
 */
@ThreadSafety.ThreadSafe
public class CombinedCache extends AbstractReferenceCounted {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ListenableFuture<Void> COMPLETED_SUCCESS = immediateFuture(null);
  private static final ListenableFuture<byte[]> EMPTY_BYTES = immediateFuture(new byte[0]);
  private static final SpawnCheckingCacheEvent SPAWN_CHECKING_DISK_CACHE_EVENT =
      SpawnCheckingCacheEvent.create("disk-cache");
  private static final SpawnCheckingCacheEvent SPAWN_CHECKING_REMOTE_CACHE_EVENT =
      SpawnCheckingCacheEvent.create("remote-cache");

  private final CountDownLatch closeCountDownLatch = new CountDownLatch(1);
  protected final AsyncTaskCache.NoResult<Digest> casUploadCache = AsyncTaskCache.NoResult.create();

  @Nullable protected final RemoteCacheClient remoteCacheClient;
  @Nullable protected final DiskCacheClient diskCacheClient;
  protected final RemoteOptions options;
  protected final DigestUtil digestUtil;

  public CombinedCache(
      @Nullable RemoteCacheClient remoteCacheClient,
      @Nullable DiskCacheClient diskCacheClient,
      RemoteOptions options,
      DigestUtil digestUtil) {
    checkArgument(
        remoteCacheClient != null || diskCacheClient != null,
        "remoteCacheClient and diskCacheClient cannot be null at the same time");
    this.remoteCacheClient = remoteCacheClient;
    this.diskCacheClient = diskCacheClient;
    this.options = options;
    this.digestUtil = digestUtil;
  }

  public CacheCapabilities getRemoteCacheCapabilities() throws IOException {
    return getRemoteServerCapabilities().getCacheCapabilities();
  }

  public ListenableFuture<String> getRemoteAuthority() {
    if (remoteCacheClient == null) {
      return immediateFuture("");
    }
    return remoteCacheClient.getAuthority();
  }

  public ServerCapabilities getRemoteServerCapabilities() throws IOException {
    if (remoteCacheClient == null) {
      return ServerCapabilities.getDefaultInstance();
    }
    return remoteCacheClient.getServerCapabilities();
  }

  /**
   * Class to keep track of which cache (disk or remote) a given [cached] ActionResult comes from.
   */
  public record CachedActionResult(ActionResult actionResult, String cacheName) {
    @Nullable
    public static CachedActionResult remote(ActionResult actionResult) {
      if (actionResult == null) {
        return null;
      }
      return new CachedActionResult(actionResult, "remote");
    }

    @Nullable
    public static CachedActionResult disk(ActionResult actionResult) {
      if (actionResult == null) {
        return null;
      }
      return new CachedActionResult(actionResult, "disk");
    }
  }

  public CachedActionResult downloadActionResult(
      RemoteActionExecutionContext context,
      ActionKey actionKey,
      boolean inlineOutErr,
      Set<String> inlineOutputFiles)
      throws IOException, InterruptedException {
    var spawnExecutionContext = context.getSpawnExecutionContext();

    ListenableFuture<CachedActionResult> future = immediateFuture(null);

    if (diskCacheClient != null && context.getReadCachePolicy().allowDiskCache()) {
      // If Build without the Bytes is enabled, the future will likely return null
      // and fallback to remote cache because AC integrity check is enabled and referenced blobs are
      // probably missing from disk cache due to BwoB.
      //
      // TODO(chiwang): With lease service, instead of doing the integrity check against local
      // filesystem, we can check whether referenced blobs are alive in the lease service to
      // increase the cache-hit rate for disk cache.
      if (spawnExecutionContext != null) {
        spawnExecutionContext.report(SPAWN_CHECKING_DISK_CACHE_EVENT);
      }
      future =
          Futures.transform(
              diskCacheClient.downloadActionResult(actionKey),
              CachedActionResult::disk,
              directExecutor());
    }

    if (remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()) {
      future =
          Futures.transformAsync(
              future,
              (result) -> {
                if (result == null) {
                  if (spawnExecutionContext != null) {
                    spawnExecutionContext.report(SPAWN_CHECKING_REMOTE_CACHE_EVENT);
                  }
                  return Futures.transform(
                      downloadActionResultFromRemote(
                          context, actionKey, inlineOutErr, inlineOutputFiles),
                      CachedActionResult::remote,
                      directExecutor());
                } else {
                  return immediateFuture(result);
                }
              },
              directExecutor());
    }

    return getFromFuture(future);
  }

  private ListenableFuture<ActionResult> downloadActionResultFromRemote(
      RemoteActionExecutionContext context,
      ActionKey actionKey,
      boolean inlineOutErr,
      Set<String> inlineOutputFiles) {
    checkState(remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache());
    return Futures.transformAsync(
        remoteCacheClient.downloadActionResult(context, actionKey, inlineOutErr, inlineOutputFiles),
        (actionResult) -> {
          if (actionResult == null) {
            return immediateFuture(null);
          }

          if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
            return Futures.transform(
                diskCacheClient.uploadActionResult(actionKey, actionResult),
                v -> actionResult,
                directExecutor());
          }

          return immediateFuture(actionResult);
        },
        directExecutor());
  }

  /**
   * Returns a set of digests that the remote cache does not know about. The returned set is
   * guaranteed to be a subset of {@code digests}.
   */
  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
      RemoteActionExecutionContext context, Iterable<Digest> digests) {
    if (Iterables.isEmpty(digests)) {
      return immediateFuture(ImmutableSet.of());
    }

    ListenableFuture<ImmutableSet<Digest>> diskQuery = immediateFuture(ImmutableSet.of());
    if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
      diskQuery = diskCacheClient.findMissingDigests(digests);
    }

    ListenableFuture<ImmutableSet<Digest>> remoteQuery = immediateFuture(ImmutableSet.of());
    if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
      remoteQuery = remoteCacheClient.findMissingDigests(context, digests);
    }

    ListenableFuture<ImmutableSet<Digest>> diskQueryFinal = diskQuery;
    ListenableFuture<ImmutableSet<Digest>> remoteQueryFinal = remoteQuery;

    return Futures.whenAllSucceed(remoteQueryFinal, diskQueryFinal)
        .call(
            () ->
                ImmutableSet.<Digest>builder()
                    .addAll(remoteQueryFinal.get())
                    .addAll(diskQueryFinal.get())
                    .build(),
            directExecutor());
  }

  /** Returns whether the remote action cache supports updating action results. */
  public boolean remoteActionCacheSupportsUpdate() {
    try {
      return getRemoteCacheCapabilities().getActionCacheUpdateCapabilities().getUpdateEnabled();
    } catch (IOException ignored) {
      return false;
    }
  }

  /** Upload the action result to the remote cache. */
  public ListenableFuture<Void> uploadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, ActionResult actionResult) {
    ListenableFuture<Void> diskCacheFuture = Futures.immediateVoidFuture();
    if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
      diskCacheFuture = diskCacheClient.uploadActionResult(actionKey, actionResult);
    }

    ListenableFuture<Void> remoteCacheFuture = Futures.immediateVoidFuture();
    if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
      remoteCacheFuture = remoteCacheClient.uploadActionResult(context, actionKey, actionResult);
    }

    return Futures.whenAllSucceed(diskCacheFuture, remoteCacheFuture)
        .call(() -> null, directExecutor());
  }

  /**
   * Upload a local file to the remote cache.
   *
   * <p>Trying to upload the same file multiple times concurrently, results in only one upload being
   * performed.
   *
   * @param context the context for the action.
   * @param digest the digest of the file.
   * @param file the file to upload.
   */
  public ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path file) {
    return uploadFile(context, digest, file, /* force= */ false);
  }

  protected ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path file, boolean force) {
    if (digest.getSizeBytes() == 0) {
      return COMPLETED_SUCCESS;
    }

    ListenableFuture<Void> diskCacheFuture = Futures.immediateVoidFuture();
    if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
      diskCacheFuture = diskCacheClient.uploadFile(digest, file);
    }

    ListenableFuture<Void> remoteCacheFuture = Futures.immediateVoidFuture();
    if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
      Completable upload =
          casUploadCache.execute(
              digest,
              RxFutures.toCompletable(
                  () -> remoteCacheClient.uploadFile(context, digest, file), directExecutor()),
              force);
      remoteCacheFuture = RxFutures.toListenableFuture(upload);
    }

    return Futures.whenAllSucceed(diskCacheFuture, remoteCacheFuture)
        .call(() -> null, directExecutor());
  }

  /**
   * Upload sequence of bytes to the remote cache.
   *
   * <p>Trying to upload the same BLOB multiple times concurrently, results in only one upload being
   * performed.
   *
   * @param context the context for the action.
   * @param digest the digest of the file.
   * @param data the BLOB to upload.
   */
  public ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, ByteString data) {
    return uploadBlob(context, digest, data, /* force= */ false);
  }

  protected ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, ByteString data, boolean force) {
    if (digest.getSizeBytes() == 0) {
      return COMPLETED_SUCCESS;
    }

    ListenableFuture<Void> diskCacheFuture = Futures.immediateVoidFuture();
    if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
      diskCacheFuture = diskCacheClient.uploadBlob(digest, data);
    }

    ListenableFuture<Void> remoteCacheFuture = Futures.immediateVoidFuture();
    if (remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()) {
      Completable upload =
          casUploadCache.execute(
              digest,
              RxFutures.toCompletable(
                  () -> remoteCacheClient.uploadBlob(context, digest, data), directExecutor()),
              force);

      remoteCacheFuture = RxFutures.toListenableFuture(upload);
    }

    return Futures.whenAllSucceed(diskCacheFuture, remoteCacheFuture)
        .call(() -> null, directExecutor());
  }

  public ListenableFuture<byte[]> downloadBlob(
      RemoteActionExecutionContext context, Digest digest) {
    return downloadBlob(context, /* blobName= */ "", digest);
  }

  /**
   * Downloads a blob with content hash {@code digest} and stores its content in memory.
   *
   * @return a future that completes after the download completes (succeeds / fails). If successful,
   *     the content is stored in the future's {@code byte[]}.
   */
  public ListenableFuture<byte[]> downloadBlob(
      RemoteActionExecutionContext context, String blobName, Digest digest) {
    if (digest.getSizeBytes() == 0) {
      return EMPTY_BYTES;
    }
    ByteArrayOutputStream bOut = new ByteArrayOutputStream((int) digest.getSizeBytes());
    var download = downloadBlob(context, blobName, digest, bOut);
    return Futures.transform(download, (v) -> bOut.toByteArray(), directExecutor());
  }

  private ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, String blobName, Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return COMPLETED_SUCCESS;
    }
    var future = downloadBlob(context, digest, out);
    return Futures.catchingAsync(
        future,
        CacheNotFoundException.class,
        (cacheNotFoundException) -> {
          cacheNotFoundException.setFilename(blobName);
          return immediateFailedFuture(cacheNotFoundException);
        },
        directExecutor());
  }

  private ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, Digest digest, OutputStream out) {
    ListenableFuture<Void> future = immediateFailedFuture(new CacheNotFoundException(digest));

    if (diskCacheClient != null && context.getReadCachePolicy().allowDiskCache()) {
      future = diskCacheClient.downloadBlob(digest, out);
    }

    if (remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache()) {
      future =
          Futures.catchingAsync(
              future,
              CacheNotFoundException.class,
              (unused) -> downloadBlobFromRemote(context, digest, out),
              directExecutor());
    }

    return future;
  }

  private ListenableFuture<Void> downloadBlobFromRemote(
      RemoteActionExecutionContext context, Digest digest, OutputStream out) {
    checkState(remoteCacheClient != null && context.getReadCachePolicy().allowRemoteCache());

    if (diskCacheClient != null && context.getWriteCachePolicy().allowDiskCache()) {
      Path tempPath = diskCacheClient.getTempPath();
      LazyFileOutputStream tempOut = new LazyFileOutputStream(tempPath);
      ListenableFuture<Void> download = remoteCacheClient.downloadBlob(context, digest, tempOut);
      return cleanupTempFileOnError(
          Futures.transformAsync(
              download,
              (unused) -> {
                try {
                  // Fsync temp before we rename it to avoid data loss in the case of machine
                  // crashes (the OS may reorder the writes and the rename).
                  tempOut.syncIfPossible();
                  tempOut.close();
                  diskCacheClient.captureFile(tempPath, digest, Store.CAS);
                } catch (IOException e) {
                  return immediateFailedFuture(e);
                }
                return diskCacheClient.downloadBlob(digest, out);
              },
              directExecutor()),
          tempPath,
          tempOut);
    }

    return remoteCacheClient.downloadBlob(context, digest, out);
  }

  private static ListenableFuture<Void> cleanupTempFileOnError(
      ListenableFuture<Void> f, Path tempPath, OutputStream tempOut) {
    return Futures.catchingAsync(
        f,
        Exception.class,
        (rootCause) -> {
          try {
            tempOut.close();
          } catch (IOException e) {
            rootCause.addSuppressed(e);
          }
          try {
            tempPath.delete();
          } catch (IOException e) {
            rootCause.addSuppressed(e);
          }
          return immediateFailedFuture(rootCause);
        },
        directExecutor());
  }

  /** A reporter that reports download progresses. */
  public static class DownloadProgressReporter {
    private static final Pattern PATTERN = Pattern.compile("^bazel-out/[^/]+/[^/]+/");
    private final boolean includeFile;
    private final ProgressStatusListener listener;
    private final String id;
    private final String file;
    private final String totalSize;
    private final AtomicLong downloadedBytes = new AtomicLong(0);

    public DownloadProgressReporter(ProgressStatusListener listener, String file, long totalSize) {
      this(/* includeFile= */ true, listener, file, totalSize);
    }

    public DownloadProgressReporter(
        boolean includeFile, ProgressStatusListener listener, String file, long totalSize) {
      this.includeFile = includeFile;
      this.listener = listener;
      this.id = file;
      this.totalSize = bytesCountToDisplayString(totalSize);

      Matcher matcher = PATTERN.matcher(file);
      this.file = matcher.replaceFirst("");
    }

    void started() {
      reportProgress(false, false);
    }

    void downloadedBytes(int count) {
      downloadedBytes.addAndGet(count);
      reportProgress(true, false);
    }

    void finished() {
      reportProgress(true, true);
    }

    private void reportProgress(boolean includeBytes, boolean finished) {
      String progress;
      if (includeBytes) {
        if (includeFile) {
          progress =
              String.format(
                  "Downloading %s, %s / %s",
                  file, bytesCountToDisplayString(downloadedBytes.get()), totalSize);
        } else {
          progress =
              String.format("%s / %s", bytesCountToDisplayString(downloadedBytes.get()), totalSize);
        }
      } else {
        if (includeFile) {
          progress = String.format("Downloading %s", file);
        } else {
          progress = "";
        }
      }
      listener.onProgressStatus(SpawnProgressEvent.create(id, progress, finished));
    }
  }

  public ListenableFuture<Void> downloadFile(
      RemoteActionExecutionContext context,
      String outputPath,
      Path localPath,
      Digest digest,
      DownloadProgressReporter reporter)
      throws IOException {
    ListenableFuture<Void> f = downloadFile(context, localPath, digest, reporter);
    return Futures.catchingAsync(
        f,
        Throwable.class,
        (throwable) -> {
          if (throwable instanceof CacheNotFoundException cacheNotFoundException) {
            cacheNotFoundException.setFilename(outputPath);
          } else if (throwable instanceof OutputDigestMismatchException e) {
            e.setOutputPath(outputPath);
            e.setLocalPath(localPath);
          }
          return immediateFailedFuture(throwable);
        },
        directExecutor());
  }

  /** Downloads a file (that is not a directory). The content is fetched from the digest. */
  public ListenableFuture<Void> downloadFile(
      RemoteActionExecutionContext context, Path path, Digest digest) throws IOException {
    return downloadFile(
        context,
        path.getPathString(),
        path,
        digest,
        new DownloadProgressReporter(NO_ACTION, "", 0));
  }

  /** Downloads a file (that is not a directory). The content is fetched from the digest. */
  private ListenableFuture<Void> downloadFile(
      RemoteActionExecutionContext context,
      Path path,
      Digest digest,
      DownloadProgressReporter reporter)
      throws IOException {
    checkNotNull(path.getParentDirectory()).createDirectoryAndParents();
    if (digest.getSizeBytes() == 0) {
      // Handle empty file locally.
      FileSystemUtils.writeContent(path, new byte[0]);
      return COMPLETED_SUCCESS;
    }

    if (!options.remoteDownloadSymlinkTemplate.isEmpty()) {
      // Don't actually download files from the CAS. Instead, create a
      // symbolic link that points to a location where CAS objects may
      // be found. This could, for example, be a FUSE file system.
      path.createSymbolicLink(
          path.getRelative(
              options
                  .remoteDownloadSymlinkTemplate
                  .replace("{hash}", digest.getHash())
                  .replace("{size_bytes}", String.valueOf(digest.getSizeBytes()))));
      return COMPLETED_SUCCESS;
    }

    reporter.started();
    OutputStream out = new ReportingOutputStream(new LazyFileOutputStream(path), reporter);
    OutputStream out = new DirectCopyOutputStream(new ReportingOutputStream(new LazyFileOutputStream(path), reporter));

    ListenableFuture<Void> f = downloadBlob(context, digest, out);
    f.addListener(
        () -> {
          try {
            out.close();
          } catch (IOException e) {
            logger.atWarning().withCause(e).log(
                "Unexpected exception closing output stream after downloading %s/%d to %s",
                digest.getHash(), digest.getSizeBytes(), path);
          } finally {
            reporter.finished();
          }
        },
        directExecutor());
    return f;
  }

  /**
   * Download the stdout and stderr of an executed action.
   *
   * @param context the context for the action.
   * @param result the result of the action.
   * @param outErr the {@link OutErr} that the stdout and stderr will be downloaded to.
   */
  public final List<ListenableFuture<Void>> downloadOutErr(
      RemoteActionExecutionContext context, ActionResult result, OutErr outErr) {
    List<ListenableFuture<Void>> downloads = new ArrayList<>();
    if (!result.getStdoutRaw().isEmpty()) {
      try {
        result.getStdoutRaw().writeTo(outErr.getOutputStream());
        outErr.getOutputStream().flush();
      } catch (IOException e) {
        downloads.add(Futures.immediateFailedFuture(e));
      }
    } else if (result.hasStdoutDigest()) {
      downloads.add(
          downloadBlob(
              context,
              /* blobName= */ "<stdout>",
              result.getStdoutDigest(),
              outErr.getOutputStream()));
    }
    if (!result.getStderrRaw().isEmpty()) {
      try {
        result.getStderrRaw().writeTo(outErr.getErrorStream());
        outErr.getErrorStream().flush();
      } catch (IOException e) {
        downloads.add(Futures.immediateFailedFuture(e));
      }
    } else if (result.hasStderrDigest()) {
      downloads.add(
          downloadBlob(
              context,
              /* blobName= */ "<stderr>",
              result.getStderrDigest(),
              outErr.getErrorStream()));
    }
    return downloads;
  }

  public boolean hasRemoteCache() {
    return remoteCacheClient != null;
  }

  public boolean hasDiskCache() {
    return diskCacheClient != null;
  }

  @Override
  protected void deallocate() {
    if (diskCacheClient != null) {
      diskCacheClient.close();
    }
    casUploadCache.shutdown();
    if (remoteCacheClient != null) {
      remoteCacheClient.close();
    }

    closeCountDownLatch.countDown();
  }

  @Override
  public CombinedCache touch(Object o) {
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public CombinedCache retain() {
    super.retain();
    return this;
  }

  /** Waits for active network I/Os to finish. */
  public void awaitTermination() throws InterruptedException {
    casUploadCache.awaitTermination();
    closeCountDownLatch.await();
  }

  /** Shuts the cache down and cancels active network I/Os. */
  public void shutdownNow() {
    casUploadCache.shutdownNow();
  }

  public static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setRemoteExecution(RemoteExecution.newBuilder().setCode(detailedCode))
        .build();
  }

  /**
   * An {@link OutputStream} that reports all the write operations with {@link
   * DownloadProgressReporter}.
   */
  private static class ReportingOutputStream extends OutputStream {

    private final OutputStream out;
    private final DownloadProgressReporter reporter;

    ReportingOutputStream(OutputStream out, DownloadProgressReporter reporter) {
      this.out = out;
      this.reporter = reporter;
    }

    @Override
    public void write(byte[] b) throws IOException {
      out.write(b);
      reporter.downloadedBytes(b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      reporter.downloadedBytes(len);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      reporter.downloadedBytes(1);
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }
}
