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

import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.util.Sleeper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.WillClose;

/**
 * Class for establishing connections to HTTP servers for downloading files.
 *
 * <p>This class must be used in conjunction with {@link HttpConnectorMultiplexer}.
 *
 * <p>Instances are thread safe and can be reused.
 */
@ThreadSafe
class HttpConnector {

  private static final int MAX_ATTEMPTS = 8;
  private static final int MAX_REDIRECTS = 40;
  private static final int MIN_RETRY_DELAY_MS = 100;
  private static final int MIN_CONNECT_TIMEOUT_MS = 1000;
  private static final int MAX_CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 20000;
  private static final ImmutableSet<String> COMPRESSED_EXTENSIONS =
      ImmutableSet.of("bz2", "gz", "jar", "tgz", "war", "xz", "zip");
  private static final String USER_AGENT_VALUE =
      "bazel/" + BlazeVersionInfo.instance().getVersion();

  private final Locale locale;
  private final EventHandler eventHandler;
  private final ProxyHelper proxyHelper;
  private final Sleeper sleeper;
  private final float timeoutScaling;
  private final int maxAttempts;
  private final Duration maxRetryTimeout;

  HttpConnector(
      Locale locale,
      EventHandler eventHandler,
      ProxyHelper proxyHelper,
      Sleeper sleeper,
      float timeoutScaling,
      int maxAttempts,
      Duration maxRetryTimeout) {
    this.locale = locale;
    this.eventHandler = eventHandler;
    this.proxyHelper = proxyHelper;
    this.sleeper = sleeper;
    this.timeoutScaling = timeoutScaling;
    this.maxAttempts = maxAttempts > 0 ? maxAttempts : MAX_ATTEMPTS;
    this.maxRetryTimeout = maxRetryTimeout;
  }

  HttpConnector(
      Locale locale,
      EventHandler eventHandler,
      ProxyHelper proxyHelper,
      Sleeper sleeper,
      float timeoutScaling) {
    this(locale, eventHandler, proxyHelper, sleeper, timeoutScaling, 0, Duration.ZERO);
  }

  HttpConnector(
      Locale locale, EventHandler eventHandler, ProxyHelper proxyHelper, Sleeper sleeper) {
    this(locale, eventHandler, proxyHelper, sleeper, 1.0f);
  }

  private int scale(int unscaled) {
    return Math.round(unscaled * timeoutScaling);
  }

  URLConnection connect(
      URL originalUrl, Function<URL, ImmutableMap<String, List<String>>> requestHeaders)
      throws IOException {

    if (Thread.interrupted()) {
      throw new InterruptedIOException();
    }
    URL url = originalUrl;
    if (HttpUtils.isProtocol(url, "file")) {
      return url.openConnection();
    }
    List<Throwable> suppressions = new ArrayList<>();
    int retries = 0;
    int redirects = 0;
    int connectTimeout = scale(MIN_CONNECT_TIMEOUT_MS);
    while (true) {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection)
            url.openConnection(proxyHelper.createProxyIfNeeded(url));
        // TODO(zecke): Revise once https://bugs.openjdk.java.net/browse/JDK-8163921 is fixed.
        connection.addRequestProperty("Accept", "text/html, image/gif, image/jpeg, */*");
        boolean isAlreadyCompressed =
            COMPRESSED_EXTENSIONS.contains(HttpUtils.getExtension(url.getPath()))
                || COMPRESSED_EXTENSIONS.contains(HttpUtils.getExtension(originalUrl.getPath()));
        connection.setInstanceFollowRedirects(false);
        for (Map.Entry<String, List<String>> entry : requestHeaders.apply(url).entrySet()) {
          if (isAlreadyCompressed && Ascii.equalsIgnoreCase(entry.getKey(), "Accept-Encoding")) {
            // We're not going to ask for compression if we're downloading a file that already
            // appears to be compressed.
            continue;
          }
          String key = entry.getKey();
          for (String value : entry.getValue()) {
            connection.addRequestProperty(key, value);
          }
        }
        if (connection.getRequestProperty("User-Agent") == null) {
          connection.setRequestProperty("User-Agent", USER_AGENT_VALUE);
        }
        connection.setConnectTimeout(connectTimeout);
        // The read timeout is always large because it stays in effect after this method.
        connection.setReadTimeout(scale(READ_TIMEOUT_MS));
        // Java tries to abstract HTTP error responses for us. We don't want that. So we're going
        // to try and undo any IOException that doesn't appear to be a legitimate I/O exception.
        int code;
        try {
          connection.connect();
          code = connection.getResponseCode();
        } catch (FileNotFoundException ignored) {
          code = connection.getResponseCode();
        } catch (UnknownHostException e) {
          String message = "Unknown host: " + e.getMessage();
          eventHandler.handle(Event.progress(message));
          throw new UnrecoverableHttpException(message);
        } catch (IllegalArgumentException e) {
          // This will happen if the user does something like specify a port greater than 2^16-1.
          throw new UnrecoverableHttpException(e.getMessage());
        } catch (IOException e) {
          // Some HTTP error status codes are converted to IOExceptions, which we can only
          // disambiguate from other IOExceptions by checking the exception message. We need to be
          // careful because some exceptions (e.g., SocketTimeoutException) may have a null message.
          if (e.getMessage() == null || !e.getMessage().startsWith("Server returned")) {
            throw e;
          }
          code = connection.getResponseCode();
        }
        // 206 means partial content and only happens if caller specified Range. See RFC7233 § 4.1.
        if (code == 200 || code == 206) {
          return connection;
        } else if (code == 301 || code == 302 || code == 303 || code == 307) {
          readAllBytesAndClose(connection.getInputStream());
          if (++redirects == MAX_REDIRECTS) {
            eventHandler.handle(Event.progress("Redirect loop detected in " + originalUrl));
            throw new UnrecoverableHttpException("Redirect loop detected");
          }
          url = HttpUtils.getLocation(connection);
          if (code == 301) {
            originalUrl = url;
          }
        } else if (code == 403) {
          // jart@ has noticed BitBucket + Amazon AWS downloads frequently flake with this code.
          throw new IOException(describeHttpResponse(connection));
        } else if (code == 408) {
          // The 408 (Request Timeout) status code indicates that the server did not receive a
          // complete request message within the time that it was prepared to wait. Server SHOULD
          // send the "close" connection option (Section 6.1 of [RFC7230]) in the response, since
          // 408 implies that the server has decided to close the connection rather than continue
          // waiting.  If the client has an outstanding request in transit, the client MAY repeat
          // that request on a new connection. Quoth RFC7231 § 6.5.7
          throw new IOException(describeHttpResponse(connection));
        } else if (code == 429) {
          // The 429 (Too Many Requests) status code could result from Bazel temporarily overloading
          // the server and is typically resolved by retrying.
          throw new IOException(describeHttpResponse(connection));
        } else if (code < 500          // 4xx means client seems to have erred quoth RFC7231 § 6.5
                    || code == 501     // Server doesn't support function quoth RFC7231 § 6.6.2
                    || code == 505) {  // Server refuses to support version quoth RFC7231 § 6.6.6
          // This is a permanent error so we're not going to retry.
          readAllBytesAndClose(connection.getErrorStream());
          if (code == 404 || code == 410) {
            // For Not Found, we throw a separate unrecoverable exception so that callers can
            // distinguish between the resource being not found and the server being unavailable.
            throw new FileNotFoundException(describeHttpResponse(connection));
          }
          throw new UnrecoverableHttpException(describeHttpResponse(connection));
        } else {
          // However we will retry on some 5xx errors, particularly 500, 502 and 503.
          throw new IOException(describeHttpResponse(connection));
        }
      } catch (UnrecoverableHttpException | FileNotFoundException e) {
        throw e;
      } catch (IllegalArgumentException e) {
        throw new UnrecoverableHttpException(e.getMessage());
      } catch (IOException e) {
        if (connection != null) {
          // If we got here, it means we might not have consumed the entire payload of the
          // response, if any. So we're going to force this socket to disconnect and not be
          // reused. This is particularly important if multiple threads end up establishing
          // connections to multiple mirrors simultaneously for a large file. We don't want to
          // download that large file twice.
          connection.disconnect();
        }
        // We don't respect the Retry-After header (RFC7231 § 7.1.3) because it's rarely used and
        // tends to be too conservative when it is. We're already being good citizens by using
        // exponential backoff with jitter. Furthermore RFC law didn't use the magic word "MUST".
        double rawTimeout = Math.scalb((double) MIN_RETRY_DELAY_MS, retries);
        if (!maxRetryTimeout.isZero()) {
          rawTimeout = Math.min(rawTimeout, (double) maxRetryTimeout.toMillis());
        }
        int timeout = (int) ((0.75 + Math.random() / 2) * rawTimeout);
        if (e instanceof SocketTimeoutException) {
          eventHandler.handle(Event.progress("Timeout connecting to " + url));
          connectTimeout = Math.min(connectTimeout * 2, scale(MAX_CONNECT_TIMEOUT_MS));
          // If we got connect timeout, we're already doing exponential backoff, so no point
          // in sleeping too.
          timeout = 1;
        } else if (e instanceof InterruptedIOException) {
          // Please note that SocketTimeoutException is a subtype of InterruptedIOException.
          throw e;
        }
        if (++retries == maxAttempts) {
          if (e instanceof SocketTimeoutException) {
            // SocketTimeoutExceptions are InterruptedIOExceptions; however they do not signify
            // an external interruption, but simply a failed download due to some server timing
            // out. So rethrow them as ordinary IOExceptions.
            e = new IOException(e.getMessage(), e);
          } else {
            eventHandler
                .handle(Event.progress(format("Error connecting to %s: %s", url, e.getMessage())));
          }
          for (Throwable suppressed : suppressions) {
            e.addSuppressed(suppressed);
          }
          throw e;
        }
        // Java 7 allows us to create a tree of all errors that led to the ultimate failure.
        suppressions.add(e);
        eventHandler.handle(
            Event.progress(format("Failed to connect to %s trying again in %,dms", url, timeout)));
        url = originalUrl;
        try {
          sleeper.sleepMillis(timeout);
        } catch (InterruptedException translated) {
          throw new InterruptedIOException();
        }
      } catch (RuntimeException e) {
        if (connection != null) {
          connection.disconnect();
        }
        eventHandler.handle(Event.progress(format("Unknown error connecting to %s: %s", url, e)));
        throw e;
      }
    }
  }

  private String describeHttpResponse(HttpURLConnection connection) throws IOException {
    return format(
        "%s returned %d %s",
        connection.getRequestMethod(),
        connection.getResponseCode(),
        Strings.nullToEmpty(connection.getResponseMessage()));
  }

  private String format(String format, Object... args) {
    return String.format(locale, format, args);
  }

  // Exhausts all bytes in an HTTP to make it easier for Java infrastructure to reuse sockets.
  private static void readAllBytesAndClose(
      @WillClose @Nullable InputStream stream)
          throws IOException {
    if (stream != null) {
      ByteStreams.exhaust(stream);
      stream.close();
    }
  }
}
