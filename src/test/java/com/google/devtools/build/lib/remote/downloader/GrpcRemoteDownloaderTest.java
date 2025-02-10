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

package com.google.devtools.build.lib.remote.downloader;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import build.bazel.remote.asset.v1.FetchBlobRequest;
import build.bazel.remote.asset.v1.FetchBlobResponse;
import build.bazel.remote.asset.v1.FetchGrpc.FetchImplBase;
import build.bazel.remote.asset.v1.Qualifier;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.authandtls.StaticCredentials;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache.KeyType;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.Downloader;
import com.google.devtools.build.lib.bazel.repository.downloader.UnrecoverableHttpException;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.ChannelConnectionWithServerCapabilitiesFactory;
import com.google.devtools.build.lib.remote.ReferenceCountedChannel;
import com.google.devtools.build.lib.remote.RemoteRetrier;
import com.google.devtools.build.lib.remote.RemoteRetrier.ExponentialBackoff;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.remote.util.TestUtils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GrpcRemoteDownloader}. */
@RunWith(JUnit4.class)
public class GrpcRemoteDownloaderTest {

  private static final ManualClock clock = new ManualClock();

  // Use an unusual default to verify that the hash function used to generate a given Checksum is
  // propagated correctly.
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA1);

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private final String fakeServerName = "fake server for " + getClass();
  private Server fakeServer;
  private RemoteActionExecutionContext context;
  private ListeningScheduledExecutorService retryService;

  @Before
  public final void setUp() throws Exception {
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(
            "none",
            "none",
            DIGEST_UTIL.asActionKey(Digest.getDefaultInstance()).getDigest().getHash(),
            null);
    context = RemoteActionExecutionContext.create(metadata);

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));

    BlazeClock.setClock(clock);
  }

  @After
  public void tearDown() throws Exception {
    retryService.shutdownNow();
    retryService.awaitTermination(
        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }

  private GrpcRemoteDownloader newDownloader(RemoteCacheClient cacheClient) throws IOException {
    return newDownloader(cacheClient, /* fallbackDownloader= */ null);
  }

  private GrpcRemoteDownloader newDownloader(
      RemoteCacheClient cacheClient, @Nullable Downloader fallbackDownloader) throws IOException {
    final RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    final RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(
            () -> new ExponentialBackoff(remoteOptions),
            RemoteRetrier.RETRIABLE_GRPC_ERRORS,
            retryService);
    final ReferenceCountedChannel channel =
        new ReferenceCountedChannel(
            new ChannelConnectionWithServerCapabilitiesFactory() {
              @Override
              public Single<ChannelConnectionWithServerCapabilities> create() {
                ManagedChannel ch =
                    InProcessChannelBuilder.forName(fakeServerName).directExecutor().build();
                return Single.just(
                    new ChannelConnectionWithServerCapabilities(
                        ch, Single.just(ServerCapabilities.getDefaultInstance())));
              }

              @Override
              public int maxConcurrency() {
                return 100;
              }
            });
    return new GrpcRemoteDownloader(
        "none",
        "none",
        channel.retain(),
        Optional.<CallCredentials>empty(),
        retrier,
        cacheClient,
        DIGEST_UTIL.getDigestFunction(),
        remoteOptions,
        /* verboseFailures= */ false,
        fallbackDownloader);
  }

  private static byte[] downloadBlob(
      GrpcRemoteDownloader downloader, URL url, Optional<Checksum> checksum)
      throws IOException, InterruptedException {
    final List<URL> urls = ImmutableList.of(url);

    final String canonicalId = "";
    final ExtendedEventHandler eventHandler = mock(ExtendedEventHandler.class);
    final Map<String, String> clientEnv = ImmutableMap.of();

    Scratch scratch = new Scratch();
    final Path destination = scratch.resolve("output file path");
    downloader.download(
        urls,
        ImmutableMap.of(),
        StaticCredentials.EMPTY,
        checksum,
        canonicalId,
        destination,
        eventHandler,
        clientEnv,
        Optional.<String>empty());

    try (InputStream in = destination.getInputStream()) {
      return ByteStreams.toByteArray(in);
    }
  }

  @Test
  public void testDownload() throws Exception {
    final byte[] content = "example content".getBytes(UTF_8);
    final Digest contentDigest = DIGEST_UTIL.compute(content);

    serviceRegistry.addService(
        new FetchImplBase() {
          @Override
          public void fetchBlob(
              FetchBlobRequest request, StreamObserver<FetchBlobResponse> responseObserver) {
            assertThat(request)
                .isEqualTo(
                    FetchBlobRequest.newBuilder()
                        .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                        .setOldestContentAccepted(
                            Timestamps.fromMillis(clock.advance(Duration.ofHours(1))))
                        .addUris("http://example.com/content.txt")
                        .build());
            responseObserver.onNext(
                FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build());
            responseObserver.onCompleted();
          }
        });

    final RemoteCacheClient cacheClient = new InMemoryCacheClient();
    final GrpcRemoteDownloader downloader = newDownloader(cacheClient);

    getFromFuture(cacheClient.uploadBlob(context, contentDigest, ByteString.copyFrom(content)));
    final byte[] downloaded =
        downloadBlob(
            downloader, new URL("http://example.com/content.txt"), Optional.<Checksum>empty());

    assertThat(downloaded).isEqualTo(content);
  }

  @Test
  public void testDownloadFallback() throws Exception {
    final byte[] content = "example content".getBytes(UTF_8);
    serviceRegistry.addService(
        new FetchImplBase() {
          @Override
          public void fetchBlob(
              FetchBlobRequest request, StreamObserver<FetchBlobResponse> responseObserver) {
            responseObserver.onError(new IOException("io error"));
          }
        });
    final RemoteCacheClient cacheClient = new InMemoryCacheClient();
    Downloader fallbackDownloader = mock(Downloader.class);
    doAnswer(
            invocation -> {
              List<URL> urls = invocation.getArgument(0);
              if (urls.equals(ImmutableList.of(new URL("http://example.com/content.txt")))) {
                Path output = invocation.getArgument(5);
                FileSystemUtils.writeContent(output, content);
              }
              return null;
            })
        .when(fallbackDownloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());
    final GrpcRemoteDownloader downloader = newDownloader(cacheClient, fallbackDownloader);

    final byte[] downloaded =
        downloadBlob(
            downloader, new URL("http://example.com/content.txt"), Optional.<Checksum>empty());

    assertThat(downloaded).isEqualTo(content);
  }

  @Test
  public void testPropagateChecksum() throws Exception {
    final byte[] content = "example content".getBytes(UTF_8);
    final DigestUtil digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    final Digest contentDigest = digestUtil.compute(content);

    serviceRegistry.addService(
        new FetchImplBase() {
          @Override
          public void fetchBlob(
              FetchBlobRequest request, StreamObserver<FetchBlobResponse> responseObserver) {
            assertThat(request)
                .isEqualTo(
                    FetchBlobRequest.newBuilder()
                        .setDigestFunction(digestUtil.getDigestFunction())
                        .addUris("http://example.com/content.txt")
                        .addQualifiers(
                            Qualifier.newBuilder()
                                .setName("checksum.sri")
                                .setValue("sha256-ot7ke6YmiSXal3UKt0K69n8C4vtUziPUmftmpbAiKQM="))
                        .build());
            responseObserver.onNext(
                FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build());
            responseObserver.onCompleted();
          }
        });

    final RemoteCacheClient cacheClient = new InMemoryCacheClient();
    final GrpcRemoteDownloader downloader = newDownloader(cacheClient);

    getFromFuture(cacheClient.uploadBlob(context, contentDigest, ByteString.copyFrom(content)));
    final byte[] downloaded =
        downloadBlob(
            downloader,
            new URL("http://example.com/content.txt"),
            Optional.of(Checksum.fromString(KeyType.SHA256, contentDigest.getHash())));

    assertThat(downloaded).isEqualTo(content);
  }

  @Test
  public void testRejectChecksumMismatch() throws Exception {
    final byte[] content = "example content".getBytes(UTF_8);
    final DigestUtil digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    final Digest contentDigest = digestUtil.compute(content);

    serviceRegistry.addService(
        new FetchImplBase() {
          @Override
          public void fetchBlob(
              FetchBlobRequest request, StreamObserver<FetchBlobResponse> responseObserver) {
            assertThat(request)
                .isEqualTo(
                    FetchBlobRequest.newBuilder()
                        .setDigestFunction(digestUtil.getDigestFunction())
                        .addUris("http://example.com/content.txt")
                        .addQualifiers(
                            Qualifier.newBuilder()
                                .setName("checksum.sri")
                                .setValue("sha256-ot7ke6YmiSXal3UKt0K69n8C4vtUziPUmftmpbAiKQM="))
                        .build());
            responseObserver.onNext(
                FetchBlobResponse.newBuilder().setBlobDigest(contentDigest).build());
            responseObserver.onCompleted();
          }
        });

    final RemoteCacheClient cacheClient = new InMemoryCacheClient();
    final GrpcRemoteDownloader downloader = newDownloader(cacheClient);

    getFromFuture(
        cacheClient.uploadBlob(context, contentDigest, ByteString.copyFromUtf8("wrong content")));

    IOException e =
        assertThrows(
            UnrecoverableHttpException.class,
            () ->
                downloadBlob(
                    downloader,
                    new URL("http://example.com/content.txt"),
                    Optional.of(Checksum.fromString(KeyType.SHA256, contentDigest.getHash()))));

    assertThat(e).hasMessageThat().contains(contentDigest.getHash());
    assertThat(e).hasMessageThat().contains(digestUtil.computeAsUtf8("wrong content").getHash());
  }

  @Test
  public void testFetchBlobRequest() throws Exception {
    FetchBlobRequest request =
        GrpcRemoteDownloader.newFetchBlobRequest(
            "instance name",
            false,
            ImmutableList.of(
                new URL("http://example.com/a"),
                new URL("http://example.com/b"),
                new URL("file:/not/limited/to/http")),
            Optional.<Checksum>of(
                Checksum.fromSubresourceIntegrity(
                    "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")),
            "canonical ID",
            DIGEST_UTIL.getDigestFunction(),
            ImmutableMap.of(
                "Authorization", ImmutableList.of("Basic Zm9vOmJhcg=="),
                "X-Custom-Token", ImmutableList.of("foo", "bar")),
            StaticCredentials.EMPTY);

    assertThat(request)
        .isEqualTo(
            FetchBlobRequest.newBuilder()
                .setInstanceName("instance name")
                .setDigestFunction(DigestFunction.Value.SHA256)
                .addUris("http://example.com/a")
                .addUris("http://example.com/b")
                .addUris("file:/not/limited/to/http")
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("checksum.sri")
                        .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .addQualifiers(
                    Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID"))
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("http_header:Authorization")
                        .setValue("Basic Zm9vOmJhcg=="))
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("http_header:X-Custom-Token")
                        .setValue("foo,bar"))
                .build());
  }

  @Test
  public void testFetchBlobRequest_withCredentialsPropagation() throws Exception {
    var shouldPropagateCredentials = true;
    var url = new URL("http://example.com/a");

    Credentials credentials = mock(Credentials.class);
    when(credentials.hasRequestMetadata()).thenReturn(true);
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("CredKey", singletonList("CredValue"));
    when(credentials.getRequestMetadata(url.toURI())).thenReturn(headers);

    FetchBlobRequest request =
        GrpcRemoteDownloader.newFetchBlobRequest(
            "instance name",
            shouldPropagateCredentials,
            ImmutableList.of(url),
            Optional.<Checksum>of(
                Checksum.fromSubresourceIntegrity(
                    "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")),
            "canonical ID",
            DIGEST_UTIL.getDigestFunction(),
            ImmutableMap.of(),
            credentials);

    assertThat(request)
        .isEqualTo(
            FetchBlobRequest.newBuilder()
                .setInstanceName("instance name")
                .setDigestFunction(DigestFunction.Value.SHA256)
                .addUris("http://example.com/a")
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("http_header_url:0:CredKey")
                        .setValue("CredValue"))
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("checksum.sri")
                        .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .addQualifiers(
                    Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID"))
                .build());
  }

  @Test
  public void testFetchBlobRequest_withoutCredentialsPropagation() throws Exception {
    var shouldPropagateCredentials = false;
    var url = new URI("http://example.com/a").toURL();

    Credentials credentials = mock(Credentials.class);
    when(credentials.hasRequestMetadata()).thenReturn(true);
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("CredKey", ImmutableList.of("CredValue"));
    when(credentials.getRequestMetadata(url.toURI())).thenReturn(headers);

    FetchBlobRequest request =
        GrpcRemoteDownloader.newFetchBlobRequest(
            "instance name",
            shouldPropagateCredentials,
            ImmutableList.of(url),
            Optional.<Checksum>of(
                Checksum.fromSubresourceIntegrity(
                    "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")),
            "canonical ID",
            DIGEST_UTIL.getDigestFunction(),
            ImmutableMap.of(),
            credentials);

    assertThat(request)
        .isEqualTo(
            FetchBlobRequest.newBuilder()
                .setInstanceName("instance name")
                .setDigestFunction(DigestFunction.Value.SHA256)
                .addUris("http://example.com/a")
                .addQualifiers(
                    Qualifier.newBuilder()
                        .setName("checksum.sri")
                        .setValue("sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .addQualifiers(
                    Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID"))
                .build());
  }

  @Test
  public void testFetchBlobRequest_withoutChecksum() throws Exception {
    FetchBlobRequest request =
        GrpcRemoteDownloader.newFetchBlobRequest(
            "instance name",
            false,
            ImmutableList.of(new URI("http://example.com/").toURL()),
            Optional.<Checksum>empty(),
            "canonical ID",
            DIGEST_UTIL.getDigestFunction(),
            ImmutableMap.of(),
            StaticCredentials.EMPTY);

    assertThat(request)
        .isEqualTo(
            FetchBlobRequest.newBuilder()
                .setInstanceName("instance name")
                .setDigestFunction(DIGEST_UTIL.getDigestFunction())
                .setOldestContentAccepted(Timestamps.fromMillis(clock.advance(Duration.ofHours(1))))
                .addUris("http://example.com/")
                .addQualifiers(
                    Qualifier.newBuilder().setName("bazel.canonical_id").setValue("canonical ID"))
                .build());
  }
}
