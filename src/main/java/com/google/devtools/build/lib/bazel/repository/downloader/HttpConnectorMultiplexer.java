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

package com.google.devtools.build.lib.bazel.repository.downloader;

import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.authandtls.StaticCredentials;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Class for establishing HTTP connections.
 *
 * <p>This is the most amazing way to download files ever. It makes Bazel builds as reliable as
 * Blaze builds in Google's internal hermetically sealed repository. But this class isn't just
 * reliable. It's also fast. It even works on the worst Internet connections in the farthest corners
 * of the Earth. You are just not going to believe how fast and reliable this design is. It's
 * incredible. Your builds are never going to break again due to downloads. You're going to be so
 * happy. Your developer community is going to be happy. Mr. Jenkins will be happy too. Everyone is
 * going to have such a magnificent developer experience due to the product excellence of this
 * class.
 */
@ThreadSafe
final class HttpConnectorMultiplexer {

  private static final ImmutableMap<String, List<String>> REQUEST_HEADERS =
      ImmutableMap.of(
          "Accept-Encoding",
          ImmutableList.of("gzip"),
          "User-Agent",
          ImmutableList.of("Bazel/" + BlazeVersionInfo.instance().getReleaseName()));

  private final EventHandler eventHandler;
  private final HttpConnector connector;
  private final HttpStream.Factory httpStreamFactory;

  /**
   * Creates a new instance.
   *
   * <p>Instances are thread safe and can be reused.
   */
  HttpConnectorMultiplexer(
      EventHandler eventHandler, HttpConnector connector, HttpStream.Factory httpStreamFactory) {
    this.eventHandler = eventHandler;
    this.connector = connector;
    this.httpStreamFactory = httpStreamFactory;
  }

  public HttpStream connect(URL url, Optional<Checksum> checksum) throws IOException {
    return connect(url, checksum, ImmutableMap.of(), StaticCredentials.EMPTY, Optional.empty());
  }

  /**
   * Establishes reliable HTTP connection to a URL.
   *
   * <p>This routine supports HTTP redirects in an RFC compliant manner. It requests gzip content
   * encoding when appropriate in order to minimize bandwidth consumption when downloading
   * uncompressed files. It reports download progress. It enforces a SHA-256 checksum which
   * continues to be enforced even after this method returns.
   *
   * @param url the URL to conenct to. can be: file, http, or https
   * @param checksum checksum lazily checked on entire payload, or empty to disable
   * @param credentials the credentials
   * @param type extension, e.g. "tar.gz" to force on downloaded filename, or empty to not do this
   * @return an {@link InputStream} of response payload
   * @throws IOException if all mirrors are down and contains suppressed exception of each attempt
   * @throws InterruptedIOException if current thread is being cast into oblivion
   * @throws IllegalArgumentException if {@code urls} is empty or has an unsupported protocol
   */
  public HttpStream connect(
      URL url,
      Optional<Checksum> checksum,
      Map<String, List<String>> headers,
      Credentials credentials,
      Optional<String> type)
      throws IOException {
    Preconditions.checkArgument(HttpUtils.isUrlSupportedByDownloader(url));
    if (Thread.interrupted()) {
      throw new InterruptedIOException();
    }
    ImmutableMap.Builder<String, List<String>> baseHeaders = new ImmutableMap.Builder<>();
    baseHeaders.putAll(headers);
    // REQUEST_HEADERS should not be overridable by user provided headers
    baseHeaders.putAll(REQUEST_HEADERS);

    Function<URL, ImmutableMap<String, List<String>>> headerFunction =
        getHeaderFunction(baseHeaders.buildKeepingLast(), credentials, eventHandler);
    URLConnection connection = connector.connect(url, headerFunction);
    return httpStreamFactory.create(
        connection,
        url,
        checksum,
        (Throwable cause, ImmutableMap<String, List<String>> extraHeaders) -> {
          eventHandler.handle(
              Event.progress(String.format("Lost connection for %s due to %s", url, cause)));
          return connector.connect(
              connection.getURL(),
              newUrl ->
                  new ImmutableMap.Builder<String, List<String>>()
                      .putAll(headerFunction.apply(newUrl))
                      .putAll(extraHeaders)
                      .buildOrThrow());
        },
        type);
  }

  @VisibleForTesting
  static Function<URL, ImmutableMap<String, List<String>>> getHeaderFunction(
      Map<String, List<String>> baseHeaders, Credentials credentials, EventHandler eventHandler) {
    Preconditions.checkNotNull(baseHeaders);
    Preconditions.checkNotNull(credentials);

    return url -> {
      ImmutableMap.Builder<String, List<String>> headers = new ImmutableMap.Builder<>();
      headers.putAll(baseHeaders);
      try {
        headers.putAll(credentials.getRequestMetadata(url.toURI()));
      } catch (URISyntaxException | IOException e) {
        // If we can't convert the URL to a URI (because it is syntactically malformed), or fetching
        // credentials fails for any other reason, still try to do the connection, not adding
        // authentication information as we cannot look it up.
        eventHandler.handle(
            Event.warn("Error retrieving auth headers, continuing without: " + e.getMessage()));
      }
      return headers.buildKeepingLast();
    };
  }
}
