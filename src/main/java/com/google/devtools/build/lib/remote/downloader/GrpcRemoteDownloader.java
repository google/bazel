// Copyright 2020 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.asset.v1.FetchBlobRequest;
import build.bazel.remote.asset.v1.FetchBlobResponse;
import build.bazel.remote.asset.v1.FetchGrpc;
import build.bazel.remote.asset.v1.FetchGrpc.FetchBlockingStub;
import build.bazel.remote.asset.v1.Qualifier;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.DigestFunction;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.Downloader;
import com.google.devtools.build.lib.bazel.repository.downloader.HashOutputStream;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.ReferenceCountedChannel;
import com.google.devtools.build.lib.remote.RemoteRetrier;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.util.Timestamps;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A Downloader implementation that uses Bazel's Remote Execution APIs to delegate downloads of
 * external files to a remote service.
 *
 * <p>See https://github.com/bazelbuild/remote-apis for more details on the exact capabilities and
 * semantics of the Remote Execution API.
 */
public class GrpcRemoteDownloader implements AutoCloseable, Downloader {

  private final String buildRequestId;
  private final String commandId;
  private final ReferenceCountedChannel channel;
  private final Optional<CallCredentials> credentials;
  private final RemoteRetrier retrier;
  private final RemoteCacheClient cacheClient;
  private final DigestFunction.Value defaultDigestFunction;
  private final RemoteOptions options;
  private final boolean verboseFailures;
  @Nullable private final Downloader fallbackDownloader;

  private final AtomicBoolean closed = new AtomicBoolean();

  // The `Qualifier::name` field uses well-known string keys to attach arbitrary
  // key-value metadata to download requests. These are the qualifier names
  // supported by Bazel.
  private static final String QUALIFIER_CHECKSUM_SRI = "checksum.sri";
  private static final String QUALIFIER_CANONICAL_ID = "bazel.canonical_id";

  // The `:` character is not permitted in an HTTP header name. So, we use it to
  // delimit the qualifier prefix which denotes an HTTP header qualifer from the
  // header name itself.
  private static final String QUALIFIER_HTTP_HEADER_PREFIX = "http_header:";
  // Same as HTTP_HEADER_PREFIX, but only apply for a specific URL.
  // The index starts from 0 and corresponds to the URL index in the request.
  // Server should prefer using the URL-specific header value over the generic header
  // value when both are present.
  private static final String QUALIFIER_HTTP_HEADER_URL_PREFIX = "http_header_url:";

  public GrpcRemoteDownloader(
      String buildRequestId,
      String commandId,
      ReferenceCountedChannel channel,
      Optional<CallCredentials> credentials,
      RemoteRetrier retrier,
      RemoteCacheClient cacheClient,
      DigestFunction.Value defaultDigestFunction,
      RemoteOptions options,
      boolean verboseFailures,
      @Nullable Downloader fallbackDownloader) {
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.channel = channel;
    this.credentials = credentials;
    this.retrier = retrier;
    this.cacheClient = cacheClient;
    this.defaultDigestFunction = defaultDigestFunction;
    this.options = options;
    this.verboseFailures = verboseFailures;
    this.fallbackDownloader = fallbackDownloader;
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    cacheClient.close();
    channel.release();
  }

  @Override
  public void download(
      List<URL> urls,
      Map<String, List<String>> headers,
      Credentials credentials,
      Optional<Checksum> checksum,
      String canonicalId,
      Path destination,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      Optional<String> type)
      throws IOException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "remote_downloader", null);
    RemoteActionExecutionContext remoteActionExecutionContext =
        RemoteActionExecutionContext.create(metadata);

    final FetchBlobRequest request =
        newFetchBlobRequest(
            options.remoteInstanceName,
            options.remoteDownloaderPropagateCredentials,
            urls,
            checksum,
            canonicalId,
            defaultDigestFunction,
            headers,
            credentials);
    try {
      FetchBlobResponse response =
          retrier.execute(
              () ->
                  channel.withChannelBlocking(
                      channel ->
                          fetchBlockingStub(remoteActionExecutionContext, channel)
                              .fetchBlob(request)));
      final Digest blobDigest = response.getBlobDigest();

      retrier.execute(
          () -> {
            try (OutputStream out = newOutputStream(destination, checksum)) {
              Utils.getFromFuture(
                  cacheClient.downloadBlob(remoteActionExecutionContext, blobDigest, out));
            }
            return null;
          });

    } catch (StatusRuntimeException | IOException e) {
      if (fallbackDownloader == null) {
        if (e instanceof StatusRuntimeException) {
          throw new IOException(e);
        }
        throw e;
      }
      eventHandler.handle(
          Event.warn("Remote Cache: " + Utils.grpcAwareErrorMessage(e, verboseFailures)));
      fallbackDownloader.download(
          urls,
          headers,
          credentials,
          checksum,
          canonicalId,
          destination,
          eventHandler,
          clientEnv,
          type);
    }
  }

  @VisibleForTesting
  static FetchBlobRequest newFetchBlobRequest(
      String instanceName,
      boolean remoteDownloaderPropagateCredentials,
      List<URL> urls,
      Optional<Checksum> checksum,
      String canonicalId,
      DigestFunction.Value defaultDigestFunction,
      Map<String, List<String>> headers,
      Credentials credentials)
      throws IOException {
    FetchBlobRequest.Builder requestBuilder =
        FetchBlobRequest.newBuilder().setInstanceName(instanceName);
    for (int i = 0; i < urls.size(); i++) {
      var url = urls.get(i);
      requestBuilder.addUris(url.toString());

      if (!remoteDownloaderPropagateCredentials) {
        continue;
      }

      try {
        var metadata = credentials.getRequestMetadata(url.toURI());
        for (var entry : metadata.entrySet()) {
          for (var value : entry.getValue()) {
            requestBuilder.addQualifiers(
                Qualifier.newBuilder()
                    .setName(QUALIFIER_HTTP_HEADER_URL_PREFIX + i + ":" + entry.getKey())
                    .setValue(value)
                    .build());
          }
        }
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
    }

    if (checksum.isPresent()) {
      requestBuilder.setDigestFunction(
          switch (checksum.get().getKeyType()) {
            case SHA1 -> DigestFunction.Value.SHA1;
            case SHA256 -> DigestFunction.Value.SHA256;
            case SHA384 -> DigestFunction.Value.SHA384;
            case SHA512 -> DigestFunction.Value.SHA512;
            case BLAKE3 -> DigestFunction.Value.BLAKE3;
          });
      requestBuilder.addQualifiers(
          Qualifier.newBuilder()
              .setName(QUALIFIER_CHECKSUM_SRI)
              .setValue(checksum.get().toSubresourceIntegrity())
              .build());
    } else {
      requestBuilder.setDigestFunction(defaultDigestFunction);
      // If no checksum is provided, never accept cached content.
      // Timestamp is offset by an hour to account for clock skew.
      requestBuilder.setOldestContentAccepted(
          Timestamps.fromMillis(
              BlazeClock.instance().now().plus(Duration.ofHours(1)).toEpochMilli()));
    }

    if (!Strings.isNullOrEmpty(canonicalId)) {
      requestBuilder.addQualifiers(
          Qualifier.newBuilder().setName(QUALIFIER_CANONICAL_ID).setValue(canonicalId).build());
    }

    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      // https://www.rfc-editor.org/rfc/rfc9110.html#name-field-order permits
      // merging the field-values with a comma.
      requestBuilder.addQualifiers(
          Qualifier.newBuilder()
              .setName(QUALIFIER_HTTP_HEADER_PREFIX + entry.getKey())
              .setValue(String.join(",", entry.getValue()))
              .build());
    }

    return requestBuilder.build();
  }

  private FetchBlockingStub fetchBlockingStub(
      RemoteActionExecutionContext context, Channel channel) {
    return FetchGrpc.newBlockingStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()))
        .withInterceptors(TracingMetadataUtils.newDownloaderHeadersInterceptor(options))
        .withCallCredentials(credentials.orElse(null))
        .withDeadlineAfter(options.remoteTimeout.toSeconds(), TimeUnit.SECONDS);
  }

  private OutputStream newOutputStream(Path destination, Optional<Checksum> checksum)
      throws IOException {
    OutputStream out = destination.getOutputStream();
    if (checksum.isPresent()) {
      out = new HashOutputStream(out, checksum.get());
    }
    return out;
  }

  @VisibleForTesting
  public ReferenceCountedChannel getChannel() {
    return channel;
  }
}
