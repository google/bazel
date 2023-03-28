// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Strings.isNullOrEmpty;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheFutureStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.CountingOutputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.MissingDigestsFinder;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestOutputStream;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.zstd.ZstdDecompressingOutputStream;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcCacheClient implements RemoteCacheClient, MissingDigestsFinder {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final CallCredentialsProvider callCredentialsProvider;
  private final ReferenceCountedChannel channel;
  private final RemoteOptions options;
  private final DigestUtil digestUtil;
  private final RemoteRetrier retrier;
  private final ByteStreamUploader uploader;
  private final int maxMissingBlobsDigestsPerMessage;

  private AtomicBoolean closed = new AtomicBoolean();

  @VisibleForTesting
  public GrpcCacheClient(
      ReferenceCountedChannel channel,
      CallCredentialsProvider callCredentialsProvider,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil) {
    this.callCredentialsProvider = callCredentialsProvider;
    this.channel = channel;
    this.options = options;
    this.digestUtil = digestUtil;
    this.retrier = retrier;
    this.uploader =
        new ByteStreamUploader(
            options.remoteInstanceName,
            channel,
            callCredentialsProvider,
            options.remoteTimeout.getSeconds(),
            retrier,
            options.maximumOpenFiles);
    maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    Preconditions.checkState(
        maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small.");
  }

  private int computeMaxMissingBlobsDigestsPerMessage() {
    final int overhead =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .setDigestFunction(digestUtil.getDigestFunction())
            .build()
            .getSerializedSize();
    final int tagSize =
        FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(Digest.getDefaultInstance())
                .build()
                .getSerializedSize()
            - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
    // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
    final int digestSize = digestUtil.compute(new byte[] {1}).getSerializedSize() + tagSize;
    return (options.maxOutboundMessageSize - overhead) / digestSize;
  }

  private ContentAddressableStorageFutureStub casFutureStub(
      RemoteActionExecutionContext context, Channel channel) {
    return ContentAddressableStorageGrpc.newFutureStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub(RemoteActionExecutionContext context, Channel channel) {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private ActionCacheFutureStub acFutureStub(
      RemoteActionExecutionContext context, Channel channel) {
    return ActionCacheGrpc.newFutureStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()),
            new NetworkTimeInterceptor(context::getNetworkTime))
        .withCallCredentials(callCredentialsProvider.getCallCredentials())
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    channel.release();
  }

  /** Returns true if 'options.remoteCache' uses 'grpc' or an empty scheme */
  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    if (isNullOrEmpty(options.remoteCache)) {
      return false;
    }
    // TODO(ishikhman): add proper URI validation/parsing for remote options
    return !(Ascii.toLowerCase(options.remoteCache).startsWith("http://")
        || Ascii.toLowerCase(options.remoteCache).startsWith("https://"));
  }

  @Override
  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
      RemoteActionExecutionContext context, Iterable<Digest> digests) {
    if (Iterables.isEmpty(digests)) {
      return Futures.immediateFuture(ImmutableSet.of());
    }
    // Need to potentially split the digests into multiple requests.
    FindMissingBlobsRequest.Builder requestBuilder =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .setDigestFunction(digestUtil.getDigestFunction());
    List<ListenableFuture<FindMissingBlobsResponse>> getMissingDigestCalls = new ArrayList<>();
    for (Digest digest : digests) {
      requestBuilder.addBlobDigests(digest);
      if (requestBuilder.getBlobDigestsCount() == maxMissingBlobsDigestsPerMessage) {
        getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()));
        requestBuilder.clearBlobDigests();
      }
    }

    if (requestBuilder.getBlobDigestsCount() > 0) {
      getMissingDigestCalls.add(getMissingDigests(context, requestBuilder.build()));
    }

    ListenableFuture<ImmutableSet<Digest>> success =
        Futures.whenAllSucceed(getMissingDigestCalls)
            .call(
                () -> {
                  ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
                  for (ListenableFuture<FindMissingBlobsResponse> callFuture :
                      getMissingDigestCalls) {
                    result.addAll(callFuture.get().getMissingBlobDigestsList());
                  }
                  return result.build();
                },
                MoreExecutors.directExecutor());

    RequestMetadata requestMetadata = context.getRequestMetadata();
    return Futures.catchingAsync(
        success,
        RuntimeException.class,
        (e) ->
            Futures.immediateFailedFuture(
                new IOException(
                    String.format(
                        "findMissingBlobs(%d) for %s: %s",
                        requestBuilder.getBlobDigestsCount(),
                        requestMetadata.getActionId(),
                        e.getMessage()),
                    e)),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
      RemoteActionExecutionContext context, FindMissingBlobsRequest request) {
    return Utils.refreshIfUnauthenticatedAsync(
        () ->
            retrier.executeAsync(
                () ->
                    channel.withChannelFuture(
                        channel -> casFutureStub(context, channel).findMissingBlobs(request))),
        callCredentialsProvider);
  }

  private ListenableFuture<CachedActionResult> handleStatus(
      ListenableFuture<ActionResult> download) {
    ListenableFuture<CachedActionResult> cachedActionResult =
        Futures.transform(download, CachedActionResult::remote, MoreExecutors.directExecutor());
    return Futures.catchingAsync(
        cachedActionResult,
        StatusRuntimeException.class,
        (sre) ->
            sre.getStatus().getCode() == Code.NOT_FOUND
                // Return null to indicate that it was a cache miss.
                ? Futures.immediateFuture(null)
                : Futures.immediateFailedFuture(new IOException(sre)),
        MoreExecutors.directExecutor());
  }

  @Override
  public ListenableFuture<CachedActionResult> downloadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, boolean inlineOutErr) {
    GetActionResultRequest request =
        GetActionResultRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .setDigestFunction(digestUtil.getDigestFunction())
            .setActionDigest(actionKey.getDigest())
            .setInlineStderr(inlineOutErr)
            .setInlineStdout(inlineOutErr)
            .build();
    return Utils.refreshIfUnauthenticatedAsync(
        () ->
            retrier.executeAsync(
                () ->
                    handleStatus(
                        channel.withChannelFuture(
                            channel -> acFutureStub(context, channel).getActionResult(request)))),
        callCredentialsProvider);
  }

  @Override
  public ListenableFuture<Void> uploadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, ActionResult actionResult) {
    ListenableFuture<ActionResult> upload =
        Utils.refreshIfUnauthenticatedAsync(
            () ->
                retrier.executeAsync(
                    () ->
                        Futures.catchingAsync(
                            channel.withChannelFuture(
                                channel ->
                                    acFutureStub(context, channel)
                                        .updateActionResult(
                                            UpdateActionResultRequest.newBuilder()
                                                .setInstanceName(options.remoteInstanceName)
                                                .setDigestFunction(digestUtil.getDigestFunction())
                                                .setActionDigest(actionKey.getDigest())
                                                .setActionResult(actionResult)
                                                .build())),
                            StatusRuntimeException.class,
                            (sre) -> Futures.immediateFailedFuture(new IOException(sre)),
                            MoreExecutors.directExecutor())),
            callCredentialsProvider);

    return Futures.transform(upload, ac -> null, MoreExecutors.directExecutor());
  }

  @Override
  public ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return Futures.immediateVoidFuture();
    }

    @Nullable Supplier<Digest> digestSupplier = null;
    if (options.remoteVerifyDownloads) {
      DigestOutputStream digestOut = digestUtil.newDigestOutputStream(out);
      digestSupplier = digestOut::digest;
      out = digestOut;
    }

    return downloadBlob(context, digest, new CountingOutputStream(out), digestSupplier);
  }

  private ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context,
      Digest digest,
      CountingOutputStream out,
      @Nullable Supplier<Digest> digestSupplier) {
    ProgressiveBackoff progressiveBackoff = new ProgressiveBackoff(retrier::newBackoff);
    ListenableFuture<Long> downloadFuture =
        Utils.refreshIfUnauthenticatedAsync(
            () ->
                retrier.executeAsync(
                    () ->
                        channel.withChannelFuture(
                            channel ->
                                requestRead(
                                    context,
                                    progressiveBackoff,
                                    digest,
                                    out,
                                    digestSupplier,
                                    channel)),
                    progressiveBackoff),
            callCredentialsProvider);

    return Futures.catchingAsync(
        Futures.transform(downloadFuture, bytesWritten -> null, MoreExecutors.directExecutor()),
        StatusRuntimeException.class,
        (e) -> Futures.immediateFailedFuture(new IOException(e)),
        MoreExecutors.directExecutor());
  }

  public static String getResourceName(String instanceName, Digest digest, boolean compressed) {
    String resourceName = "";
    if (!instanceName.isEmpty()) {
      resourceName += instanceName + "/";
    }
    resourceName += compressed ? "compressed-blobs/zstd/" : "blobs/";
    return resourceName + DigestUtil.toString(digest);
  }

  private ListenableFuture<Long> requestRead(
      RemoteActionExecutionContext context,
      ProgressiveBackoff progressiveBackoff,
      Digest digest,
      CountingOutputStream rawOut,
      @Nullable Supplier<Digest> digestSupplier,
      Channel channel) {
    String resourceName =
        getResourceName(options.remoteInstanceName, digest, options.cacheCompression);
    SettableFuture<Long> future = SettableFuture.create();
    OutputStream out;
    try {
      out = options.cacheCompression ? new ZstdDecompressingOutputStream(rawOut) : rawOut;
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }
    bsAsyncStub(context, channel)
        .read(
            ReadRequest.newBuilder()
                .setResourceName(resourceName)
                .setReadOffset(rawOut.getCount())
                .build(),
            new ClientResponseObserver<ReadRequest, ReadResponse>() {
              @Override
              public void beforeStart(ClientCallStreamObserver<ReadRequest> requestStream) {
                future.addListener(
                    () -> {
                      if (future.isCancelled()) {
                        requestStream.cancel("canceled by user", null);
                      }
                    },
                    MoreExecutors.directExecutor());
              }

              @Override
              public void onNext(ReadResponse readResponse) {
                ByteString data = readResponse.getData();
                try {
                  data.writeTo(out);
                } catch (IOException e) {
                  // Cancel the call.
                  throw new VerifyException(e);
                }
                // reset the stall backoff because we've made progress or been kept alive
                progressiveBackoff.reset();
              }

              @Override
              public void onError(Throwable t) {
                if (rawOut.getCount() == digest.getSizeBytes()) {
                  // If the file was fully downloaded, it doesn't matter if there was an
                  // error at
                  // the end of the stream.
                  logger.atInfo().withCause(t).log(
                      "ignoring error because file was fully received");
                  onCompleted();
                  return;
                }
                releaseOut();
                Status status = Status.fromThrowable(t);
                if (status.getCode() == Status.Code.NOT_FOUND) {
                  future.setException(new CacheNotFoundException(digest));
                } else {
                  future.setException(t);
                }
              }

              @Override
              public void onCompleted() {
                try {
                  try {
                    out.flush();
                  } finally {
                    releaseOut();
                  }
                  if (digestSupplier != null) {
                    Utils.verifyBlobContents(digest, digestSupplier.get());
                  }
                } catch (IOException e) {
                  future.setException(e);
                } catch (RuntimeException e) {
                  logger.atWarning().withCause(e).log("Unexpected exception");
                  future.setException(e);
                }
                future.set(rawOut.getCount());
              }

              private void releaseOut() {
                if (out instanceof ZstdDecompressingOutputStream) {
                  try {
                    ((ZstdDecompressingOutputStream) out).closeShallow();
                  } catch (IOException e) {
                    logger.atWarning().withCause(e).log("failed to cleanly close output stream");
                  }
                }
              }
            });
    return future;
  }

  @Override
  public ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path path) {
    return uploadChunker(
        context,
        digest,
        Chunker.builder()
            .setInput(digest.getSizeBytes(), path)
            .setCompressed(options.cacheCompression)
            .build());
  }

  @Override
  public ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, ByteString data) {
    return uploadChunker(
        context,
        digest,
        Chunker.builder()
            .setInput(data.toByteArray())
            .setCompressed(options.cacheCompression)
            .build());
  }

  ListenableFuture<Void> uploadChunker(
      RemoteActionExecutionContext context, Digest digest, Chunker chunker) {
    ListenableFuture<Void> f = uploader.uploadBlobAsync(context, digest, chunker);
    f.addListener(
        () -> {
          try {
            chunker.reset();
          } catch (IOException e) {
            logger.atWarning().withCause(e).log(
                "failed to reset chunker uploading %s/%d", digest.getHash(), digest.getSizeBytes());
          }
        },
        MoreExecutors.directExecutor());
    return f;
  }

  Retrier getRetrier() {
    return this.retrier;
  }
}
