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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TestUtils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ByteStreamUploader}. */
@RunWith(JUnit4.class)
public class ByteStreamUploaderTest {

  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  private static final int CHUNK_SIZE = 10;
  private static final String INSTANCE_NAME = "foo";

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private ListeningScheduledExecutorService retryService;

  private final String serverName = "Server for " + this.getClass();
  private Server server;
  private ReferenceCountedChannel referenceCountedChannel;
  private RemoteActionExecutionContext context;

  @Mock private Retrier.Backoff mockBackoff;

  @Before
  public final void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    server =
        InProcessServerBuilder.forName(serverName)
            .fallbackHandlerRegistry(serviceRegistry)
            .build()
            .start();
    referenceCountedChannel =
        new ReferenceCountedChannel(
            new ChannelConnectionWithServerCapabilitiesFactory() {
              @Override
              public Single<ChannelConnectionWithServerCapabilities> create() {
                return Single.just(
                    new ChannelConnectionWithServerCapabilities(
                        InProcessChannelBuilder.forName(serverName).build(),
                        Single.just(ServerCapabilities.getDefaultInstance())));
              }

              @Override
              public int maxConcurrency() {
                return 100;
              }
            });
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(
            "none",
            "none",
            DIGEST_UTIL.asActionKey(Digest.getDefaultInstance()).getDigest().getHash(),
            null);
    context = RemoteActionExecutionContext.create(metadata);

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  @After
  public void tearDown() throws Exception {
    referenceCountedChannel.release();
    retryService.shutdownNow();
    retryService.awaitTermination(
        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    server.shutdownNow();
    server.awaitTermination();
  }

  @Test
  public void singleBlobUploadShouldWork() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(TestUtils.newNoErrorByteStreamService(blob));

    uploadBlob(uploader, context, digest, chunker);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void singleChunkCompressedUploadAlreadyExists() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = {'A'};

    // Set a chunk size that should have no problem accommodating the compressed
    // blob, even though the blob most likely has a compression ratio >= 1.
    Chunker chunker =
        Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(100).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              private int numChunksReceived = 0;

              @Override
              public void onNext(WriteRequest writeRequest) {
                // This should be the first and only chunk written.
                numChunksReceived++;
                assertThat(numChunksReceived).isEqualTo(1);
                ByteString data = writeRequest.getData();
                assertThat(data.size()).isGreaterThan(0);
                assertThat(writeRequest.getFinishWrite()).isTrue();

                // On receiving the chunk, respond with a committed size of -1
                // to indicate that the blob already exists (per the remote API
                // spec) and close the stream.
                WriteResponse response = WriteResponse.newBuilder().setCommittedSize(-1).build();
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {}
            };
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void progressiveUploadShouldWork() throws Exception {
    Mockito.when(mockBackoff.getRetryAttempts()).thenReturn(0);
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            3,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {

          byte[] receivedData = new byte[blob.length];
          String receivedResourceName = null;
          boolean receivedComplete = false;
          long nextOffset = 0;
          long initialOffset = 0;
          boolean mustQueryWriteStatus = false;

          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {
                assertThat(mustQueryWriteStatus).isFalse();

                String resourceName = writeRequest.getResourceName();
                if (nextOffset == initialOffset) {
                  if (initialOffset == 0) {
                    receivedResourceName = resourceName;
                  }
                  assertThat(resourceName).startsWith(INSTANCE_NAME + "/uploads");
                  assertThat(resourceName).endsWith(String.valueOf(blob.length));
                } else {
                  assertThat(resourceName).isEmpty();
                }

                assertThat(writeRequest.getWriteOffset()).isEqualTo(nextOffset);

                ByteString data = writeRequest.getData();

                System.arraycopy(
                    data.toByteArray(), 0, receivedData, (int) nextOffset, data.size());

                nextOffset += data.size();
                receivedComplete = blob.length == nextOffset;
                assertThat(writeRequest.getFinishWrite()).isEqualTo(receivedComplete);

                if (initialOffset == 0) {
                  streamObserver.onError(Status.DEADLINE_EXCEEDED.asException());
                  mustQueryWriteStatus = true;
                  initialOffset = nextOffset;
                }
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                assertThat(nextOffset).isEqualTo(blob.length);
                assertThat(receivedData).isEqualTo(blob);

                WriteResponse response =
                    WriteResponse.newBuilder().setCommittedSize(nextOffset).build();
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            String resourceName = request.getResourceName();
            final long committedSize;
            final boolean complete;
            if (receivedResourceName != null && receivedResourceName.equals(resourceName)) {
              assertThat(mustQueryWriteStatus).isTrue();
              mustQueryWriteStatus = false;
              committedSize = nextOffset;
              complete = receivedComplete;
            } else {
              committedSize = 0;
              complete = false;
            }
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(committedSize)
                    .setComplete(complete)
                    .build());
            response.onCompleted();
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    // This test triggers one retry.
    Mockito.verify(mockBackoff, Mockito.times(1))
        .nextDelayMillis(any(StatusRuntimeException.class));
    Mockito.verify(mockBackoff, Mockito.times(1)).getRetryAttempts();
  }

  @Test
  public void progressiveCompressedUploadShouldWork() throws Exception {
    Mockito.when(mockBackoff.getRetryAttempts()).thenReturn(0);
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            300,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    int chunkSize = 1024;
    int skipSize = chunkSize + 1;
    byte[] blob = new byte[chunkSize * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker =
        Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(chunkSize).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          String receivedResourceName = null;
          boolean receivedComplete = false;
          long nextOffset = 0;
          long initialOffset = 0;
          boolean mustQueryWriteStatus = false;

          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {
                assertThat(mustQueryWriteStatus).isFalse();

                String resourceName = writeRequest.getResourceName();
                if (nextOffset == initialOffset) {
                  if (initialOffset == 0) {
                    receivedResourceName = resourceName;
                  }
                  assertThat(resourceName).startsWith(INSTANCE_NAME + "/uploads");
                  assertThat(resourceName).endsWith(String.valueOf(blob.length));
                } else {
                  assertThat(resourceName).isEmpty();
                }

                if (initialOffset == 0) {
                  streamObserver.onError(Status.DEADLINE_EXCEEDED.asException());
                  mustQueryWriteStatus = true;
                  initialOffset = skipSize;
                  nextOffset = initialOffset;
                } else {
                  ByteString data = writeRequest.getData();
                  try {
                    data.writeTo(output);
                  } catch (IOException e) {
                    streamObserver.onError(e);
                    return;
                  }
                  nextOffset += data.size();
                  receivedComplete = writeRequest.getFinishWrite();
                }
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                WriteResponse response =
                    WriteResponse.newBuilder().setCommittedSize(nextOffset).build();
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            String resourceName = request.getResourceName();
            final long committedSize;
            final boolean complete;
            if (receivedResourceName != null && receivedResourceName.equals(resourceName)) {
              assertThat(mustQueryWriteStatus).isTrue();
              mustQueryWriteStatus = false;
              committedSize = receivedComplete ? blob.length : skipSize;
              complete = receivedComplete;
            } else {
              committedSize = 0;
              complete = false;
            }
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(committedSize)
                    .setComplete(complete)
                    .build());
            response.onCompleted();
          }
        });

    uploadBlob(uploader, context, digest, chunker);
    byte[] decompressed = Zstd.decompress(output.toByteArray(), blob.length - skipSize);
    assertThat(Arrays.equals(decompressed, 0, decompressed.length, blob, skipSize, blob.length))
        .isTrue();

    // This test triggers one retry.
    Mockito.verify(mockBackoff, Mockito.times(1)).nextDelayMillis(any(Exception.class));
    Mockito.verify(mockBackoff, Mockito.times(1)).getRetryAttempts();
  }

  @Test
  public void progressiveCompressedUploadSeesAlreadyExistsAtTheEnd() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> new FixedBackoff(1, 0),
            e -> Status.fromThrowable(e).getCode() == Code.INTERNAL,
            retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            300,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    int chunkSize = 1024;
    byte[] blob = new byte[chunkSize * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker =
        Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(chunkSize).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {}

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                streamObserver.onError(Status.INTERNAL.asException());
              }
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(blob.length)
                    .setComplete(true)
                    .build());
            response.onCompleted();
          }
        });

    uploadBlob(uploader, context, digest, chunker);
  }

  @Test
  public void concurrentlyCompletedUploadIsNotRetried() throws Exception {
    // Test that after an upload has failed and the QueryWriteStatus call returns
    // that the upload has completed that we'll not retry the upload.
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            1,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    AtomicInteger numWriteCalls = new AtomicInteger(0);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            numWriteCalls.getAndIncrement();
            streamObserver.onError(Status.DEADLINE_EXCEEDED.asException());
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {}
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(blob.length)
                    .setComplete(true)
                    .build());
            response.onCompleted();
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    // This test should not have triggered any retries.
    assertThat(numWriteCalls.get()).isEqualTo(1);
  }

  @Test
  public void unimplementedQueryShouldRestartUpload() throws Exception {
    Mockito.when(mockBackoff.getRetryAttempts()).thenReturn(0);
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            3,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          boolean expireCall = true;
          boolean sawReset = false;

          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {
                if (expireCall) {
                  streamObserver.onError(Status.DEADLINE_EXCEEDED.asException());
                  expireCall = false;
                } else if (!sawReset && writeRequest.getWriteOffset() != 0) {
                  streamObserver.onError(Status.INVALID_ARGUMENT.asException());
                } else {
                  sawReset = true;
                  if (writeRequest.getFinishWrite()) {
                    long committedSize =
                        writeRequest.getWriteOffset() + writeRequest.getData().size();
                    streamObserver.onNext(
                        WriteResponse.newBuilder().setCommittedSize(committedSize).build());
                    streamObserver.onCompleted();
                  }
                }
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {}
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            response.onError(Status.UNIMPLEMENTED.asException());
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    // This test should have triggered a single retry, because it made
    // no progress.
    Mockito.verify(mockBackoff, Mockito.times(1)).nextDelayMillis(any(Exception.class));
  }

  @Test
  public void earlyWriteResponseShouldCompleteUpload() throws Exception {
    RemoteRetrier retrier = TestUtils.newRemoteRetrier(() -> mockBackoff, e -> false, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            3,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);
    // provide only enough data to write a single chunk
    InputStream in = new ByteArrayInputStream(blob, 0, CHUNK_SIZE);

    Chunker chunker =
        Chunker.builder().setInput(blob.length, () -> in).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            streamObserver.onNext(WriteResponse.newBuilder().setCommittedSize(blob.length).build());
            streamObserver.onCompleted();
            return new NoopStreamObserver();
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void incorrectCommittedSizeFailsCompletedUpload() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            3,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {}

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                WriteResponse response =
                    WriteResponse.newBuilder().setCommittedSize(blob.length + 1).build();
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }
            };
          }
        });

    try {
      uploadBlob(uploader, context, digest, chunker);
      fail("Should have thrown an exception.");
    } catch (IOException e) {
      // expected
    }

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void incorrectCommittedSizeDoesNotFailIncompleteUpload() throws Exception {
    RemoteRetrier retrier = TestUtils.newRemoteRetrier(() -> mockBackoff, e -> false, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            300,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            streamObserver.onNext(WriteResponse.newBuilder().setCommittedSize(CHUNK_SIZE).build());
            streamObserver.onCompleted();
            return new NoopStreamObserver();
          }
        });

    uploadBlob(uploader, context, digest, chunker);
  }

  @Test
  public void multipleBlobsUploadShouldWork() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    int numUploads = 10;
    Map<HashCode, byte[]> blobsByHash = Maps.newHashMap();
    Map<Digest, Chunker> chunkers = Maps.newHashMapWithExpectedSize(numUploads);
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      int blobSize = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
      Digest digest = DIGEST_UTIL.compute(blob);
      chunkers.put(digest, chunker);
      blobsByHash.put(HashCode.fromString(digest.getHash()), blob);
    }

    serviceRegistry.addService(new MaybeFailOnceUploadService(blobsByHash));

    uploadBlobs(uploader, context, chunkers);
  }

  @Test
  public void tooManyFilesIOException_adviseMaximumOpenFilesFlag() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);
    byte[] blob = new byte[CHUNK_SIZE];
    Chunker chunker = Mockito.mock(Chunker.class);
    Digest digest = DIGEST_UTIL.compute(blob);
    Mockito.doThrow(new IOException("Too many open files")).when(chunker).seek(0);
    Mockito.when(chunker.getUncompressedSize()).thenReturn(digest.getSizeBytes());
    serviceRegistry.addService(new MaybeFailOnceUploadService(ImmutableMap.of()));

    String newMessage =
        "An IOException was thrown because the process opened too many files. We recommend setting"
            + " --bep_maximum_open_remote_upload_files flag to a number lower than your system"
            + " default (run 'ulimit -a' for *nix-based operating systems). Original error message:"
            + " Too many open files";
    assertThat(
            assertThrows(IOException.class, () -> uploadBlob(uploader, context, digest, chunker)))
        .hasMessageThat()
        .isEqualTo(newMessage);
  }

  @Test
  public void availablePermitsOpenFileSemaphore_fewerPermitsThanUploads_endWithAllPermits()
      throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    // number of permits is less than number of uploads to affirm permit is released
    int maximumOpenFiles = 999;
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            maximumOpenFiles,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    assertThat(uploader.getOpenedFilePermits().availablePermits()).isEqualTo(999);

    CustomFileTracker customFileTracker = new CustomFileTracker(maximumOpenFiles);
    int numUploads = 1000;
    Map<HashCode, byte[]> blobsByHash = Maps.newHashMap();
    Map<Digest, Chunker> chunkers = Maps.newHashMapWithExpectedSize(numUploads);
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      int blobSize = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      Chunker chunker =
          TestChunker.builder(customFileTracker).setInput(blob).setChunkSize(CHUNK_SIZE).build();
      Digest digest = DIGEST_UTIL.compute(blob);
      chunkers.put(digest, chunker);
      blobsByHash.put(HashCode.fromString(digest.getHash()), blob);
    }

    serviceRegistry.addService(new MaybeFailOnceUploadService(blobsByHash));

    uploadBlobs(uploader, context, chunkers);

    assertThat(uploader.getOpenedFilePermits().availablePermits()).isEqualTo(maximumOpenFiles);
  }

  @Test
  public void noMaximumOpenFilesFlags_nullSemaphore() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);
    assertThat(uploader.getOpenedFilePermits()).isNull();

    int numUploads = 10;
    Map<HashCode, byte[]> blobsByHash = Maps.newHashMap();
    Map<Digest, Chunker> chunkers = Maps.newHashMapWithExpectedSize(numUploads);
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      int blobSize = rand.nextInt(CHUNK_SIZE * 10) + CHUNK_SIZE;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
      Digest digest = DIGEST_UTIL.compute(blob);
      chunkers.put(digest, chunker);
      blobsByHash.put(HashCode.fromString(digest.getHash()), blob);
    }

    serviceRegistry.addService(new MaybeFailOnceUploadService(blobsByHash));

    uploadBlobs(uploader, context, chunkers);
    assertThat(uploader.getOpenedFilePermits()).isNull();
  }

  @Test
  public void contextShouldBePreservedUponRetries() throws Exception {
    // We upload blobs with different context, and retry 3 times for each upload.
    // We verify that the correct metadata is passed to the server with every blob.
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(5, 0), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    List<String> toUpload = ImmutableList.of("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc");
    Map<Digest, Chunker> chunkers = Maps.newHashMapWithExpectedSize(toUpload.size());
    Map<String, Integer> uploadsFailed = Maps.newHashMap();
    for (String s : toUpload) {
      Chunker chunker = Chunker.builder().setInput(s.getBytes(UTF_8)).setChunkSize(3).build();
      Digest digest = DIGEST_UTIL.computeAsUtf8(s);
      chunkers.put(digest, chunker);
      uploadsFailed.put(digest.getHash(), 0);
    }

    BindableService bsService =
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            return new StreamObserver<WriteRequest>() {

              private String digestHash;

              @Override
              public void onNext(WriteRequest writeRequest) {
                String resourceName = writeRequest.getResourceName();
                if (!resourceName.isEmpty()) {
                  String[] components = resourceName.split("/");
                  assertThat(components).hasLength(6);
                  digestHash = components[4];
                }
                assertThat(digestHash).isNotNull();
                RequestMetadata meta = TracingMetadataUtils.fromCurrentContext();
                assertThat(meta.getCorrelatedInvocationsId()).isEqualTo("build-req-id");
                assertThat(meta.getToolInvocationId()).isEqualTo("command-id");
                assertThat(meta.getActionId()).isEqualTo(digestHash);
                assertThat(meta.getToolDetails().getToolName()).isEqualTo("bazel");
                assertThat(meta.getToolDetails().getToolVersion())
                    .isEqualTo(BlazeVersionInfo.instance().getVersion());
                synchronized (this) {
                  Integer numFailures = uploadsFailed.get(digestHash);
                  if (numFailures < 3) {
                    uploadsFailed.put(digestHash, numFailures + 1);
                    response.onError(Status.INTERNAL.asException());
                    return;
                  }
                }
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                response.onNext(WriteResponse.newBuilder().setCommittedSize(10).build());
                response.onCompleted();
              }
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(0)
                    .setComplete(false)
                    .build());
            response.onCompleted();
          }
        };
    serviceRegistry.addService(
        ServerInterceptors.intercept(
            bsService, new TracingMetadataUtils.ServerHeadersInterceptor()));

    List<ListenableFuture<Void>> uploads = new ArrayList<>();

    for (Map.Entry<Digest, Chunker> chunkerEntry : chunkers.entrySet()) {
      Digest actionDigest = chunkerEntry.getKey();
      RequestMetadata metadata =
          TracingMetadataUtils.buildMetadata(
              "build-req-id",
              "command-id",
              DIGEST_UTIL.asActionKey(actionDigest).getDigest().getHash(),
              null);
      RemoteActionExecutionContext remoteActionExecutionContext =
          RemoteActionExecutionContext.create(metadata);
      uploads.add(
          uploader.uploadBlobAsync(
              remoteActionExecutionContext, actionDigest, chunkerEntry.getValue()));
    }

    for (ListenableFuture<Void> upload : uploads) {
      upload.get();
    }
  }

  @Test
  public void customHeadersAreAttachedToRequest() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);

    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("Key1", Metadata.ASCII_STRING_MARSHALLER), "Value1");
    metadata.put(Metadata.Key.of("Key2", Metadata.ASCII_STRING_MARSHALLER), "Value2");

    referenceCountedChannel.release();
    referenceCountedChannel =
        new ReferenceCountedChannel(
            new ChannelConnectionWithServerCapabilitiesFactory() {
              @Override
              public Single<ChannelConnectionWithServerCapabilities> create() {
                return Single.just(
                    new ChannelConnectionWithServerCapabilities(
                        InProcessChannelBuilder.forName(serverName)
                            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
                            .build(),
                        Single.just(ServerCapabilities.getDefaultInstance())));
              }

              @Override
              public int maxConcurrency() {
                return 100;
              }
            });
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        ServerInterceptors.intercept(
            new ByteStreamImplBase() {
              @Override
              public StreamObserver<WriteRequest> write(
                  StreamObserver<WriteResponse> streamObserver) {
                return new StreamObserver<WriteRequest>() {
                  @Override
                  public void onNext(WriteRequest writeRequest) {}

                  @Override
                  public void onError(Throwable throwable) {
                    fail("onError should never be called.");
                  }

                  @Override
                  public void onCompleted() {
                    WriteResponse response =
                        WriteResponse.newBuilder().setCommittedSize(blob.length).build();
                    streamObserver.onNext(response);
                    streamObserver.onCompleted();
                  }
                };
              }
            },
            new ServerInterceptor() {
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                  ServerCall<ReqT, RespT> call,
                  Metadata metadata,
                  ServerCallHandler<ReqT, RespT> next) {
                assertThat(metadata.get(Metadata.Key.of("Key1", Metadata.ASCII_STRING_MARSHALLER)))
                    .isEqualTo("Value1");
                assertThat(metadata.get(Metadata.Key.of("Key2", Metadata.ASCII_STRING_MARSHALLER)))
                    .isEqualTo("Value2");
                assertThat(metadata.get(Metadata.Key.of("Key3", Metadata.ASCII_STRING_MARSHALLER)))
                    .isEqualTo(null);
                return next.startCall(call, metadata);
              }
            }));

    uploadBlob(uploader, context, digest, chunker);
  }

  @Test
  public void errorsShouldBeReported() throws IOException, InterruptedException {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 10), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            response.onError(Status.INTERNAL.asException());
            return new NoopStreamObserver();
          }
        });

    try {
      uploadBlob(uploader, context, digest, chunker);
      fail("Should have thrown an exception.");
    } catch (IOException e) {
      assertThat(RemoteRetrierUtils.causedByStatus(e, Code.INTERNAL)).isTrue();
    }
  }

  @Test
  public void failureInRetryExecutorShouldBeHandled() throws Exception {
    ListeningScheduledExecutorService retryService =
        MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 10), (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            // Immediately fail the call, so that it is retried.
            response.onError(Status.ABORTED.asException());
            return new NoopStreamObserver();
          }
        });

    retryService.shutdownNow();
    // Random very high timeout, as the test will timeout by itself.
    retryService.awaitTermination(1, TimeUnit.DAYS);
    assertThat(retryService.isShutdown()).isTrue();

    byte[] blob = new byte[1];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);
    try {
      uploadBlob(uploader, context, digest, chunker);
      fail("Should have thrown an exception.");
    } catch (IOException e) {
      assertThat(e).hasCauseThat().isInstanceOf(RejectedExecutionException.class);
    }
  }

  @Test
  public void resourceNameWithoutInstanceName() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            /* instanceName= */ null,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {
                // Test that the resource name doesn't start with an instance name.
                assertThat(writeRequest.getResourceName()).startsWith("uploads/");
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                response.onNext(WriteResponse.newBuilder().setCommittedSize(1).build());
                response.onCompleted();
              }
            };
          }
        });

    byte[] blob = new byte[1];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    uploadBlob(uploader, context, digest, chunker);
  }

  @Test
  public void resourceWithNewStyleDigestFunction() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            /* instanceName= */ null,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.BLAKE3);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {
                // Test that the resource name contains the digest function.
                assertThat(writeRequest.getResourceName()).contains("blobs/blake3/");
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                response.onNext(WriteResponse.newBuilder().setCommittedSize(1).build());
                response.onCompleted();
              }
            };
          }
        });

    byte[] blob = new byte[1];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    uploadBlob(uploader, context, digest, chunker);
  }

  @Test
  public void nonRetryableStatusShouldNotBeRetried() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> new FixedBackoff(1, 0), /* No Status is retriable. */ (e) -> false, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            /* instanceName= */ null,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    AtomicInteger numCalls = new AtomicInteger();

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            numCalls.incrementAndGet();
            response.onError(Status.INTERNAL.asException());
            return new NoopStreamObserver();
          }
        });

    byte[] blob = new byte[1];
    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    try {
      uploadBlob(uploader, context, digest, chunker);
      fail("Should have thrown an exception.");
    } catch (IOException e) {
      assertThat(numCalls.get()).isEqualTo(1);
    }
  }

  @Test
  public void unauthenticatedErrorShouldNotBeRetried() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> mockBackoff, RemoteRetrier.RETRIABLE_GRPC_ERRORS, retryService);

    AtomicInteger refreshTimes = new AtomicInteger();
    CallCredentialsProvider callCredentialsProvider =
        new CallCredentialsProvider() {
          @Nullable
          @Override
          public CallCredentials getCallCredentials() {
            return null;
          }

          @Override
          public void refresh() throws IOException {
            refreshTimes.incrementAndGet();
          }
        };
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            callCredentialsProvider,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    AtomicInteger numUploads = new AtomicInteger();
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            numUploads.incrementAndGet();

            streamObserver.onError(Status.UNAUTHENTICATED.asException());
            return new NoopStreamObserver();
          }
        });

    assertThrows(IOException.class, () -> uploadBlob(uploader, context, digest, chunker));

    assertThat(refreshTimes.get()).isEqualTo(1);
    assertThat(numUploads.get()).isEqualTo(2);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void shouldRefreshCredentialsOnAuthenticationError() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> mockBackoff, RemoteRetrier.RETRIABLE_GRPC_ERRORS, retryService);

    AtomicInteger refreshTimes = new AtomicInteger();
    CallCredentialsProvider callCredentialsProvider =
        new CallCredentialsProvider() {
          @Nullable
          @Override
          public CallCredentials getCallCredentials() {
            return null;
          }

          @Override
          public void refresh() throws IOException {
            refreshTimes.incrementAndGet();
          }
        };
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            callCredentialsProvider,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    AtomicInteger numUploads = new AtomicInteger();
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            numUploads.incrementAndGet();

            if (refreshTimes.get() == 0) {
              streamObserver.onError(Status.UNAUTHENTICATED.asException());
              return new NoopStreamObserver();
            }

            return new StreamObserver<WriteRequest>() {
              long nextOffset = 0;

              @Override
              public void onNext(WriteRequest writeRequest) {
                nextOffset += writeRequest.getData().size();
                boolean lastWrite = blob.length == nextOffset;
                assertThat(writeRequest.getFinishWrite()).isEqualTo(lastWrite);
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                assertThat(nextOffset).isEqualTo(blob.length);

                WriteResponse response =
                    WriteResponse.newBuilder().setCommittedSize(nextOffset).build();
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }
            };
          }
        });

    uploadBlob(uploader, context, digest, chunker);

    assertThat(refreshTimes.get()).isEqualTo(1);
    assertThat(numUploads.get()).isEqualTo(2);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);
  }

  @Test
  public void failureAfterUploadCompletes() throws Exception {
    AtomicInteger numUploads = new AtomicInteger();
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> mockBackoff, e -> e instanceof StatusRuntimeException, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE - 1];
    new Random().nextBytes(blob);

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            numUploads.incrementAndGet();
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {}

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                streamObserver.onNext(
                    WriteResponse.newBuilder().setCommittedSize(blob.length).build());
                streamObserver.onError(Status.UNAVAILABLE.asException());
              }
            };
          }

          @Override
          public void queryWriteStatus(
              QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
            response.onNext(
                QueryWriteStatusResponse.newBuilder()
                    .setCommittedSize(blob.length)
                    .setComplete(true)
                    .build());
            response.onCompleted();
          }
        });

    Chunker chunker = Chunker.builder().setInput(blob).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    uploadBlob(uploader, context, digest, chunker);

    assertThat(numUploads.get()).isEqualTo(1);
  }

  @Test
  public void testCompressedUploads() throws Exception {
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> mockBackoff, (e) -> true, retryService);
    ByteStreamUploader uploader =
        new ByteStreamUploader(
            INSTANCE_NAME,
            referenceCountedChannel,
            CallCredentialsProvider.NO_CREDENTIALS,
            /* callTimeoutSecs= */ 60,
            retrier,
            /* maximumOpenFiles= */ -1,
            /* digestFunction= */ DigestFunction.Value.SHA256);

    byte[] blob = new byte[CHUNK_SIZE * 2 + 1];
    new Random().nextBytes(blob);

    AtomicInteger numUploads = new AtomicInteger();

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              ByteArrayOutputStream baos = new ByteArrayOutputStream();
              String resourceName = null;

              @Override
              public void onNext(WriteRequest writeRequest) {
                if (!writeRequest.getResourceName().isEmpty()) {
                  if (resourceName != null) {
                    assertThat(resourceName).isEqualTo(writeRequest.getResourceName());
                  } else {
                    resourceName = writeRequest.getResourceName();
                    assertThat(resourceName).contains("/compressed-blobs/zstd/");
                  }
                }
                try {
                  writeRequest.getData().writeTo(baos);
                  if (writeRequest.getFinishWrite()) {
                    baos.close();
                  }
                } catch (IOException e) {
                  throw new AssertionError("I/O error on ByteArrayOutputStream.", e);
                }
              }

              @Override
              public void onError(Throwable throwable) {
                fail("onError should never be called.");
              }

              @Override
              public void onCompleted() {
                byte[] data = baos.toByteArray();
                try {
                  ZstdInputStream zis = new ZstdInputStream(new ByteArrayInputStream(data));
                  byte[] decompressed = ByteString.readFrom(zis).toByteArray();
                  zis.close();
                  Digest digest = DIGEST_UTIL.compute(decompressed);

                  assertThat(blob).hasLength(decompressed.length);
                  assertThat(resourceName).isNotNull();
                  assertThat(resourceName)
                      .endsWith(String.format("/%s/%s", digest.getHash(), digest.getSizeBytes()));

                  numUploads.incrementAndGet();
                } catch (IOException e) {
                  throw new AssertionError("Failed decompressing data.", e);
                } finally {
                  WriteResponse response =
                      WriteResponse.newBuilder().setCommittedSize(data.length).build();

                  streamObserver.onNext(response);
                  streamObserver.onCompleted();
                }
              }
            };
          }
        });

    Chunker chunker =
        Chunker.builder().setInput(blob).setCompressed(true).setChunkSize(CHUNK_SIZE).build();
    Digest digest = DIGEST_UTIL.compute(blob);

    uploadBlob(uploader, context, digest, chunker);

    // This test should not have triggered any retries.
    Mockito.verifyNoInteractions(mockBackoff);

    assertThat(numUploads.get()).isEqualTo(1);
  }

  /**
   * Uploads a BLOB, as provided by the {@link Chunker}, to the remote {@code ByteStream} service.
   * The call blocks until the upload is complete, or throws an {@link Exception} in case of error.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * @param digest the digest of the data to upload.
   * @param chunker the data to upload.
   * @throws IOException when reading of the {@link Chunker}s input source fails
   */
  private static void uploadBlob(
      ByteStreamUploader byteStreamUploader,
      RemoteActionExecutionContext context,
      Digest digest,
      Chunker chunker)
      throws IOException, InterruptedException {
    getFromFuture(byteStreamUploader.uploadBlobAsync(context, digest, chunker));
  }

  /**
   * Uploads a list of BLOBs concurrently to the remote {@code ByteStream} service. The call blocks
   * until the upload of all BLOBs is complete, or throws an {@link
   * com.google.devtools.build.lib.remote.common.BulkTransferException} if there are errors.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * @param chunkers the data to upload.
   * @throws IOException when reading of the {@link Chunker}s input source or uploading fails
   */
  private static void uploadBlobs(
      ByteStreamUploader byteStreamUploader,
      RemoteActionExecutionContext context,
      Map<Digest, Chunker> chunkers)
      throws IOException, InterruptedException {
    List<ListenableFuture<Void>> uploads = new ArrayList<>();

    for (Map.Entry<Digest, Chunker> chunkerEntry : chunkers.entrySet()) {
      uploads.add(
          byteStreamUploader.uploadBlobAsync(
              context, chunkerEntry.getKey(), chunkerEntry.getValue()));
    }

    waitForBulkTransfer(uploads, /* cancelRemainingOnInterrupt= */ true);
  }

  private static class NoopStreamObserver implements StreamObserver<WriteRequest> {
    @Override
    public void onNext(WriteRequest writeRequest) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onCompleted() {}
  }

  static class FixedBackoff implements Retrier.Backoff {

    private final int maxRetries;
    private final int delayMillis;

    private int retries;

    public FixedBackoff(int maxRetries, int delayMillis) {
      this.maxRetries = maxRetries;
      this.delayMillis = delayMillis;
    }

    @Override
    public long nextDelayMillis(Exception e) {
      if (retries < maxRetries) {
        retries++;
        return delayMillis;
      }
      return -1;
    }

    @Override
    public int getRetryAttempts() {
      return retries;
    }
  }

  /**
   * An byte stream service where an upload for a given blob may or may not fail on the first
   * attempt but is guaranteed to succeed on the second try.
   */
  static class MaybeFailOnceUploadService extends ByteStreamImplBase {

    private final Map<HashCode, byte[]> blobsByHash;
    private final Set<HashCode> uploadsFailedOnce = Collections.synchronizedSet(Sets.newHashSet());
    private final Random rand = new Random();

    MaybeFailOnceUploadService(Map<HashCode, byte[]> blobsByHash) {
      this.blobsByHash = blobsByHash;
    }

    @Override
    public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
      return new StreamObserver<WriteRequest>() {

        private HashCode digestHash;
        private byte[] receivedData;
        private long nextOffset;
        private boolean failed;

        @Override
        public void onNext(WriteRequest writeRequest) {
          if (nextOffset == 0) {
            String resourceName = writeRequest.getResourceName();
            assertThat(resourceName).isNotEmpty();

            String[] components = resourceName.split("/");
            assertThat(components).hasLength(6);
            digestHash = HashCode.fromString(components[4]);
            assertThat(blobsByHash).containsKey(digestHash);
            receivedData = new byte[Integer.parseInt(components[5])];
          }
          assertThat(digestHash).isNotNull();
          // An upload for a given blob has a 10% chance to fail once during its lifetime.
          // This is to exercise the retry mechanism a bit.
          boolean shouldFail = rand.nextInt(10) == 0 && !uploadsFailedOnce.contains(digestHash);
          if (shouldFail) {
            uploadsFailedOnce.add(digestHash);
            response.onError(Status.INTERNAL.asException());
            failed = true;
            return;
          }

          ByteString data = writeRequest.getData();
          System.arraycopy(data.toByteArray(), 0, receivedData, (int) nextOffset, data.size());
          nextOffset += data.size();

          boolean lastWrite = nextOffset == receivedData.length;
          assertThat(writeRequest.getFinishWrite()).isEqualTo(lastWrite);
        }

        @Override
        public void onError(Throwable throwable) {
          fail("onError should never be called.");
        }

        @Override
        public void onCompleted() {
          if (failed) {
            return;
          }
          byte[] expectedBlob = blobsByHash.get(digestHash);
          assertThat(receivedData).isEqualTo(expectedBlob);

          WriteResponse writeResponse =
              WriteResponse.newBuilder().setCommittedSize(receivedData.length).build();

          response.onNext(writeResponse);
          response.onCompleted();
        }
      };
    }

    @Override
    public void queryWriteStatus(
        QueryWriteStatusRequest request, StreamObserver<QueryWriteStatusResponse> response) {
      // force the client to reset the write
      response.onNext(
          QueryWriteStatusResponse.newBuilder().setCommittedSize(0).setComplete(false).build());
      response.onCompleted();
    }
  }

  /* Custom Chunker used to track number of open files */
  private static class TestChunker extends Chunker {

    TestChunker(Blob dataSupplier, long size, int chunkSize, boolean compressed) {
      super(dataSupplier, size, chunkSize, compressed);
    }

    public static Builder builder(CustomFileTracker customFileTracker) {
      return new TestChunkerBuilder(customFileTracker);
    }

    private static class TestChunkerBuilder extends Chunker.Builder {
      private final CustomFileTracker customFileTracker;

      TestChunkerBuilder(CustomFileTracker customFileTracker) {
        this.customFileTracker = customFileTracker;
      }

      @Override
      public Chunker.Builder setInput(byte[] existingData) {
        checkState(this.inputStream == null);
        this.size = existingData.length;
        return setInput(
            existingData.length,
            () -> new TestByteArrayInputStream(existingData, customFileTracker));
      }
    }
  }

  private static class TestByteArrayInputStream extends ByteArrayInputStream {
    private final CustomFileTracker customFileTracker;

    TestByteArrayInputStream(byte[] buf, CustomFileTracker customFileTracker) {
      super(buf);
      this.customFileTracker = customFileTracker;
      customFileTracker.incrementOpenFiles();
    }

    @Override
    public void close() throws IOException {
      super.close();
      customFileTracker.decrementOpenFiles();
    }
  }

  private static class CustomFileTracker {
    private final AtomicInteger openFiles = new AtomicInteger(0);
    private final int maxOpenFiles;

    CustomFileTracker(int maxOpenFiles) {
      this.maxOpenFiles = maxOpenFiles;
    }

    private void incrementOpenFiles() {
      openFiles.getAndIncrement();
      checkState(openFiles.get() <= maxOpenFiles);
    }

    private void decrementOpenFiles() {
      openFiles.getAndDecrement();
      checkState(openFiles.get() >= 0);
    }
  }
}
