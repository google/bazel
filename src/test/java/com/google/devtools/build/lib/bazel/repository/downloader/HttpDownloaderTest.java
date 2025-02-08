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
package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.repository.downloader.DownloaderTestUtils.sendLines;
import static com.google.devtools.build.lib.bazel.repository.downloader.HttpParser.readHttpRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.authandtls.StaticCredentials;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.stubbing.Answer;

/** Tests for {@link HttpDownloader} */
@RunWith(JUnit4.class)
public class HttpDownloaderTest {

  @Rule public final TemporaryFolder workingDir = new TemporaryFolder();

  @Rule public final Timeout timeout = new Timeout(30, SECONDS);

  private final RepositoryCache repositoryCache = mock(RepositoryCache.class);
  // Scale timeouts down to make test fast.
  private final HttpDownloader httpDownloader = new HttpDownloader(0, Duration.ZERO, 8, .1f);
  private final DownloadManager downloadManager =
      new DownloadManager(repositoryCache, httpDownloader, httpDownloader);

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final ExtendedEventHandler eventHandler = mock(ExtendedEventHandler.class);
  private final JavaIoFileSystem fs;

  public HttpDownloaderTest() {
    fs = new JavaIoFileSystem(DigestHashFunction.SHA256);
  }

  @After
  public void after() {
    executor.shutdown();
  }

  @Test
  public void downloadFrom1UrlOk() throws IOException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 200 OK",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "hello");
                }
                return null;
              });

      Path resultingFile =
          download(
              downloadManager,
              Collections.singletonList(
                  new URL(String.format("http://localhost:%d/foo", server.getLocalPort()))),
              Collections.emptyMap(),
              Collections.emptyMap(),
              Optional.empty(),
              "testCanonicalId",
              Optional.empty(),
              fs.getPath(workingDir.newFile().getAbsolutePath()),
              eventHandler,
              Collections.emptyMap(),
              "testRepo");

      assertThat(new String(readFile(resultingFile), UTF_8)).isEqualTo("hello");
    }
  }

  @Test
  public void downloadFrom2UrlsFirstOk() throws IOException, InterruptedException {
    try (ServerSocket server1 = new ServerSocket(0, 1, InetAddress.getByName(null));
        ServerSocket server2 = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                while (!executor.isShutdown()) {
                  try (Socket socket = server1.accept()) {
                    readHttpRequest(socket.getInputStream());
                    sendLines(
                        socket,
                        "HTTP/1.1 200 OK",
                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                        "Connection: close",
                        "Content-Type: text/plain",
                        "",
                        "content1");
                  }
                }
                return null;
              });

      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError2 =
          executor.submit(
              () -> {
                while (!executor.isShutdown()) {
                  try (Socket socket = server2.accept()) {
                    readHttpRequest(socket.getInputStream());
                    sendLines(
                        socket,
                        "HTTP/1.1 200 OK",
                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                        "Connection: close",
                        "Content-Type: text/plain",
                        "",
                        "content2");
                  }
                }
                return null;
              });

      final List<URL> urls = new ArrayList<>(2);
      urls.add(new URL(String.format("http://localhost:%d/foo", server1.getLocalPort())));
      urls.add(new URL(String.format("http://localhost:%d/foo", server2.getLocalPort())));

      Path resultingFile =
          download(
              downloadManager,
              urls,
              Collections.emptyMap(),
              Collections.emptyMap(),
              Optional.empty(),
              "testCanonicalId",
              Optional.empty(),
              fs.getPath(workingDir.newFile().getAbsolutePath()),
              eventHandler,
              Collections.emptyMap(),
              "testRepo");

      assertThat(new String(readFile(resultingFile), UTF_8)).isEqualTo("content1");
    }
  }

  @Ignore("b/182150157")
  @Test
  public void downloadFrom2UrlsFirstSocketTimeoutOnBodyReadSecondOk()
      throws IOException, InterruptedException {
    try (ServerSocket server1 = new ServerSocket(0, 1, InetAddress.getByName(null));
        ServerSocket server2 = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                Socket socket = server1.accept();
                readHttpRequest(socket.getInputStream());

                sendLines(
                    socket,
                    "HTTP/1.1 200 OK",
                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                    "Connection: close",
                    "Content-Type: text/plain",
                    "",
                    "content1");

                // Never close the socket to cause SocketTimeoutException during body read on client
                // side.
                return null;
              });

      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError2 =
          executor.submit(
              () -> {
                while (!executor.isShutdown()) {
                  try (Socket socket = server2.accept()) {
                    readHttpRequest(socket.getInputStream());
                    sendLines(
                        socket,
                        "HTTP/1.1 200 OK",
                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                        "Connection: close",
                        "Content-Type: text/plain",
                        "",
                        "content2");
                  }
                }
                return null;
              });

      final List<URL> urls = new ArrayList<>(2);
      urls.add(new URL(String.format("http://localhost:%d/foo", server1.getLocalPort())));
      urls.add(new URL(String.format("http://localhost:%d/foo", server2.getLocalPort())));

      Path resultingFile =
          download(
              downloadManager,
              urls,
              Collections.emptyMap(),
              Collections.emptyMap(),
              Optional.empty(),
              "testCanonicalId",
              Optional.empty(),
              fs.getPath(workingDir.newFile().getAbsolutePath()),
              eventHandler,
              Collections.emptyMap(),
              "testRepo");

      assertThat(new String(readFile(resultingFile), UTF_8)).isEqualTo("content2");
    }
  }

  @Ignore("b/182150157")
  @Test
  public void downloadFrom2UrlsBothSocketTimeoutDuringBodyRead()
      throws IOException, InterruptedException {
    try (ServerSocket server1 = new ServerSocket(0, 1, InetAddress.getByName(null));
        ServerSocket server2 = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                Socket socket = server1.accept();
                readHttpRequest(socket.getInputStream());

                sendLines(
                    socket,
                    "HTTP/1.1 200 OK",
                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                    "Connection: close",
                    "Content-Type: text/plain",
                    "",
                    "content1");

                // Never close the socket to cause SocketTimeoutException during body read on client
                // side.
                return null;
              });

      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError2 =
          executor.submit(
              () -> {
                Socket socket = server1.accept();
                readHttpRequest(socket.getInputStream());

                sendLines(
                    socket,
                    "HTTP/1.1 200 OK",
                    "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                    "Connection: close",
                    "Content-Type: text/plain",
                    "",
                    "content2");

                // Never close the socket to cause SocketTimeoutException during body read on client
                // side.
                return null;
              });

      final List<URL> urls = new ArrayList<>(2);
      urls.add(new URL(String.format("http://localhost:%d/foo", server1.getLocalPort())));
      urls.add(new URL(String.format("http://localhost:%d/foo", server2.getLocalPort())));

      Path outputFile = fs.getPath(workingDir.newFile().getAbsolutePath());
      try {
        download(
            downloadManager,
            urls,
            Collections.emptyMap(),
            Collections.emptyMap(),
            Optional.empty(),
            "testCanonicalId",
            Optional.empty(),
            outputFile,
            eventHandler,
            Collections.emptyMap(),
            "testRepo");
        fail("Should have thrown");
      } catch (IOException expected) {
        assertThat(expected.getSuppressed()).hasLength(2);

        for (Throwable suppressed : expected.getSuppressed()) {
          assertThat(suppressed).isInstanceOf(IOException.class);
          assertThat(suppressed).hasCauseThat().isInstanceOf(SocketTimeoutException.class);
        }
      }
    }
  }

  private static byte[] readFile(Path path) throws IOException {
    final byte[] data = new byte[(int) path.getFileSize()];

    try (DataInputStream stream = new DataInputStream(path.getInputStream())) {
      stream.readFully(data);
    }

    return data;
  }

  @Test
  public void downloadOneUrl_ok() throws IOException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 200 OK",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "hello");
                }
                return null;
              });
      Path destination = fs.getPath(workingDir.newFile().getAbsolutePath());
      httpDownloader.download(
          Collections.singletonList(
              new URL(String.format("http://localhost:%d/foo", server.getLocalPort()))),
          Collections.emptyMap(),
          StaticCredentials.EMPTY,
          Optional.empty(),
          "testCanonicalId",
          destination,
          eventHandler,
          Collections.emptyMap(),
          Optional.empty());

      assertThat(new String(readFile(destination), UTF_8)).isEqualTo("hello");
    }
  }

  @Test
  public void downloadOneUrl_notFound() throws IOException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 404 Not Found",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "");
                }
                return null;
              });
      assertThrows(
          IOException.class,
          () ->
              httpDownloader.download(
                  Collections.singletonList(
                      new URL(String.format("http://localhost:%d/foo", server.getLocalPort()))),
                  Collections.emptyMap(),
                  StaticCredentials.EMPTY,
                  Optional.empty(),
                  "testCanonicalId",
                  fs.getPath(workingDir.newFile().getAbsolutePath()),
                  eventHandler,
                  Collections.emptyMap(),
                  Optional.empty()));
    }
  }

  @Test
  public void downloadTwoUrls_firstNotFoundAndSecondOk() throws IOException, InterruptedException {
    try (ServerSocket server1 = new ServerSocket(0, 1, InetAddress.getByName(null));
        ServerSocket server2 = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server1.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 404 Not Found",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "");
                }
                return null;
              });

      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError2 =
          executor.submit(
              () -> {
                while (!executor.isShutdown()) {
                  try (Socket socket = server2.accept()) {
                    readHttpRequest(socket.getInputStream());
                    sendLines(
                        socket,
                        "HTTP/1.1 200 OK",
                        "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                        "Connection: close",
                        "Content-Type: text/plain",
                        "",
                        "content2");
                  }
                }
                return null;
              });

      final List<URL> urls = new ArrayList<>(2);
      urls.add(new URL(String.format("http://localhost:%d/foo", server1.getLocalPort())));
      urls.add(new URL(String.format("http://localhost:%d/foo", server2.getLocalPort())));

      Path destination = fs.getPath(workingDir.newFile().getAbsolutePath());
      httpDownloader.download(
          urls,
          Collections.emptyMap(),
          StaticCredentials.EMPTY,
          Optional.empty(),
          "testCanonicalId",
          destination,
          eventHandler,
          Collections.emptyMap(),
          Optional.empty());

      assertThat(new String(readFile(destination), UTF_8)).isEqualTo("content2");
    }
  }

  @Test
  public void downloadAndReadOneUrl_ok() throws IOException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 200 OK",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "hello");
                }
                return null;
              });

      assertThat(
              new String(
                  httpDownloader.downloadAndReadOneUrl(
                      new URL(String.format("http://localhost:%d/foo", server.getLocalPort())),
                      StaticCredentials.EMPTY,
                      Optional.empty(),
                      eventHandler,
                      Collections.emptyMap()),
                  UTF_8))
          .isEqualTo("hello");
    }
  }

  @Test
  public void downloadAndReadOneUrl_notFound() throws IOException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 404 Not Found",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "");
                }
                return null;
              });

      assertThrows(
          IOException.class,
          () ->
              httpDownloader.downloadAndReadOneUrl(
                  new URL(String.format("http://localhost:%d/foo", server.getLocalPort())),
                  StaticCredentials.EMPTY,
                  Optional.empty(),
                  eventHandler,
                  Collections.emptyMap()));
    }
  }

  @Test
  public void downloadAndReadOneUrl_checksumProvided()
      throws IOException, Checksum.InvalidChecksumException, InterruptedException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 200 OK",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 5",
                      "",
                      "hello");
                }
                return null;
              });

      assertThat(
              new String(
                  httpDownloader.downloadAndReadOneUrl(
                      new URL(String.format("http://localhost:%d/foo", server.getLocalPort())),
                      StaticCredentials.EMPTY,
                      Optional.of(
                          Checksum.fromString(
                              RepositoryCache.KeyType.SHA256,
                              Hashing.sha256().hashString("hello", UTF_8).toString())),
                      eventHandler,
                      ImmutableMap.of()),
                  UTF_8))
          .isEqualTo("hello");
    }
  }

  @Test
  public void downloadAndReadOneUrl_checksumMismatch() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(null))) {
      @SuppressWarnings("unused")
      Future<?> possiblyIgnoredError =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  readHttpRequest(socket.getInputStream());
                  sendLines(
                      socket,
                      "HTTP/1.1 200 OK",
                      "Date: Fri, 31 Dec 1999 23:59:59 GMT",
                      "Connection: close",
                      "Content-Type: text/plain",
                      "Content-Length: 9",
                      "",
                      "malicious");
                }
                return null;
              });

      var e =
          assertThrows(
              UnrecoverableHttpException.class,
              () ->
                  httpDownloader.downloadAndReadOneUrl(
                      new URL(String.format("http://localhost:%d/foo", server.getLocalPort())),
                      StaticCredentials.EMPTY,
                      Optional.of(
                          Checksum.fromString(
                              RepositoryCache.KeyType.SHA256,
                              Hashing.sha256().hashUnencodedChars("hello").toString())),
                      eventHandler,
                      ImmutableMap.of()));
      assertThat(e).hasMessageThat().contains("Checksum was");
    }
  }

  @Test
  public void download_contentLengthMismatch_propagateErrorIfNotRetry() throws Exception {
    Downloader downloader = mock(Downloader.class);
    HttpDownloader httpDownloader = mock(HttpDownloader.class);
    DownloadManager downloadManager =
        new DownloadManager(repositoryCache, downloader, httpDownloader);
    // do not retry
    downloadManager.setRetries(0);
    AtomicInteger times = new AtomicInteger(0);
    byte[] data = "content".getBytes(UTF_8);
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  times.getAndIncrement();
                  throw new ContentLengthMismatchException(0, data.length);
                })
        .when(downloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());

    assertThrows(
        ContentLengthMismatchException.class,
        () ->
            download(
                downloadManager,
                ImmutableList.of(new URL("http://localhost")),
                Collections.emptyMap(),
                ImmutableMap.of(),
                Optional.empty(),
                "testCanonicalId",
                Optional.empty(),
                fs.getPath(workingDir.newFile().getAbsolutePath()),
                eventHandler,
                ImmutableMap.of(),
                "testRepo"));

    assertThat(times.get()).isEqualTo(1);
  }

  @Test
  public void download_contentLengthMismatch_retries() throws Exception {
    Downloader downloader = mock(Downloader.class);
    HttpDownloader httpDownloader = mock(HttpDownloader.class);
    int retries = 5;
    DownloadManager downloadManager =
        new DownloadManager(repositoryCache, downloader, httpDownloader);
    downloadManager.setRetries(retries);
    AtomicInteger times = new AtomicInteger(0);
    byte[] data = "content".getBytes(UTF_8);
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  if (times.getAndIncrement() < 3) {
                    throw new ContentLengthMismatchException(0, data.length);
                  }
                  Path output = invocationOnMock.getArgument(5, Path.class);
                  try (OutputStream outputStream = output.getOutputStream()) {
                    ByteStreams.copy(new ByteArrayInputStream(data), outputStream);
                  }

                  return null;
                })
        .when(downloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());

    Path result =
        download(
            downloadManager,
            ImmutableList.of(new URL("http://localhost")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Optional.empty(),
            "testCanonicalId",
            Optional.empty(),
            fs.getPath(workingDir.newFile().getAbsolutePath()),
            eventHandler,
            ImmutableMap.of(),
            "testRepo");

    assertThat(times.get()).isEqualTo(4);
    String content = new String(ByteStreams.toByteArray(result.getInputStream()), UTF_8);
    assertThat(content).isEqualTo("content");
  }

  @Test
  public void download_contentLengthMismatchWithOtherErrors_retries() throws Exception {
    Downloader downloader = mock(Downloader.class);
    HttpDownloader httpDownloader = mock(HttpDownloader.class);
    int retries = 5;
    DownloadManager downloadManager =
        new DownloadManager(repositoryCache, downloader, httpDownloader);
    downloadManager.setRetries(retries);
    AtomicInteger times = new AtomicInteger(0);
    byte[] data = "content".getBytes(UTF_8);
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  if (times.getAndIncrement() < 3) {
                    IOException e = new IOException();
                    e.addSuppressed(new ContentLengthMismatchException(0, data.length));
                    e.addSuppressed(new IOException());
                    throw e;
                  }
                  Path output = invocationOnMock.getArgument(5, Path.class);
                  try (OutputStream outputStream = output.getOutputStream()) {
                    ByteStreams.copy(new ByteArrayInputStream(data), outputStream);
                  }

                  return null;
                })
        .when(downloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());

    Path result =
        download(
            downloadManager,
            ImmutableList.of(new URL("http://localhost")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Optional.empty(),
            "testCanonicalId",
            Optional.empty(),
            fs.getPath(workingDir.newFile().getAbsolutePath()),
            eventHandler,
            ImmutableMap.of(),
            "testRepo");

    assertThat(times.get()).isEqualTo(4);
    String content = new String(result.getInputStream().readAllBytes(), UTF_8);
    assertThat(content).isEqualTo("content");
  }

  @Test
  public void download_socketException_retries() throws Exception {
    Downloader downloader = mock(Downloader.class);
    HttpDownloader httpDownloader = mock(HttpDownloader.class);
    int retries = 5;
    DownloadManager downloadManager =
        new DownloadManager(repositoryCache, downloader, httpDownloader);
    downloadManager.setRetries(retries);
    AtomicInteger times = new AtomicInteger(0);
    byte[] data = "content".getBytes(UTF_8);
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  if (times.getAndIncrement() < 3) {
                    throw new SocketException("Connection reset");
                  }
                  Path output = invocationOnMock.getArgument(5, Path.class);
                  try (OutputStream outputStream = output.getOutputStream()) {
                    ByteStreams.copy(new ByteArrayInputStream(data), outputStream);
                  }

                  return null;
                })
        .when(downloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());

    Path result =
        download(
            downloadManager,
            ImmutableList.of(new URL("http://localhost")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Optional.empty(),
            "testCanonicalId",
            Optional.empty(),
            fs.getPath(workingDir.newFile().getAbsolutePath()),
            eventHandler,
            ImmutableMap.of(),
            "testRepo");

    assertThat(times.get()).isEqualTo(4);
    String content = new String(ByteStreams.toByteArray(result.getInputStream()), UTF_8);
    assertThat(content).isEqualTo("content");
  }

  @Test
  public void download_socketExceptionWithOtherErrors_retries() throws Exception {
    Downloader downloader = mock(Downloader.class);
    HttpDownloader httpDownloader = mock(HttpDownloader.class);
    int retries = 5;
    DownloadManager downloadManager =
        new DownloadManager(repositoryCache, downloader, httpDownloader);
    downloadManager.setRetries(retries);
    AtomicInteger times = new AtomicInteger(0);
    byte[] data = "content".getBytes(UTF_8);
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  if (times.getAndIncrement() < 3) {
                    IOException e = new IOException();
                    e.addSuppressed(new SocketException("Connection reset"));
                    e.addSuppressed(new IOException());
                    throw e;
                  }
                  Path output = invocationOnMock.getArgument(5, Path.class);
                  try (OutputStream outputStream = output.getOutputStream()) {
                    ByteStreams.copy(new ByteArrayInputStream(data), outputStream);
                  }

                  return null;
                })
        .when(downloader)
        .download(any(), any(), any(), any(), any(), any(), any(), any(), any());

    Path result =
        download(
            downloadManager,
            ImmutableList.of(new URL("http://localhost")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Optional.empty(),
            "testCanonicalId",
            Optional.empty(),
            fs.getPath(workingDir.newFile().getAbsolutePath()),
            eventHandler,
            ImmutableMap.of(),
            "testRepo");

    assertThat(times.get()).isEqualTo(4);
    String content = new String(ByteStreams.toByteArray(result.getInputStream()), UTF_8);
    assertThat(content).isEqualTo("content");
  }

  public Path download(
      DownloadManager downloadManager,
      List<URL> originalUrls,
      Map<String, List<String>> headers,
      Map<URI, Map<String, List<String>>> authHeaders,
      Optional<Checksum> checksum,
      String canonicalId,
      Optional<String> type,
      Path output,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      String context)
      throws IOException, InterruptedException {
    Phaser downloadPhaser = new Phaser();
    try (@SuppressWarnings("AllowVirtualThreads")
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Path> future =
          downloadManager.startDownload(
              executorService,
              originalUrls,
              headers,
              authHeaders,
              checksum,
              canonicalId,
              type,
              output,
              eventHandler,
              clientEnv,
              context,
              downloadPhaser);
      Path downloadedPath = downloadManager.finalizeDownload(future);
      // Should not be in the download phase.
      assertThat(downloadPhaser.getPhase()).isNotEqualTo(0);
      return downloadedPath;
    }
  }
}
