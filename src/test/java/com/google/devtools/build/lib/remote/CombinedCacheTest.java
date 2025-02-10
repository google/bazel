// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.SpawnCheckingCacheEvent;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.remote.common.LostInputsEvent;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.Blob;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/** Tests for {@link CombinedCache}. */
@RunWith(JUnit4.class)
public class CombinedCacheTest {
  @Rule public final RxNoGlobalErrorsRule rxNoGlobalErrorsRule = new RxNoGlobalErrorsRule();

  private RemoteActionExecutionContext remoteActionExecutionContext;
  private FileSystem fs;
  private Path execRoot;
  ArtifactRoot artifactRoot;
  private final DigestUtil digestUtil =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
  private FakeActionInputFileCache fakeFileCache;

  private ListeningScheduledExecutorService retryService;
  private EventBus eventBus;
  private Reporter reporter;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata("none", "none", "action-id", null);
    Spawn spawn =
        new SimpleSpawn(
            new FakeOwner("foo", "bar", "//dummy:label"),
            /* arguments= */ ImmutableList.of(),
            /* environment= */ ImmutableMap.of(),
            /* executionInfo= */ ImmutableMap.of(),
            /* inputs= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            /* outputs= */ ImmutableSet.of(),
            ResourceSet.ZERO);
    SpawnExecutionContext spawnExecutionContext = mock(SpawnExecutionContext.class);
    remoteActionExecutionContext =
        RemoteActionExecutionContext.create(spawn, spawnExecutionContext, metadata);
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot/main");
    execRoot.createDirectoryAndParents();
    fakeFileCache = new FakeActionInputFileCache(execRoot);
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "outputs");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
    eventBus = new EventBus();
    reporter = new Reporter(eventBus);
  }

  @After
  public void afterEverything() throws InterruptedException {
    retryService.shutdownNow();
    retryService.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void testDownloadEmptyBlobAndFile() throws Exception {
    // Test that downloading an empty BLOB/file does not try to perform a download.

    // arrange
    Path file = fs.getPath("/execroot/file");
    InMemoryCombinedCache combinedCache = newCombinedCache();
    Digest emptyDigest = digestUtil.compute(new byte[0]);

    // act and assert
    assertThat(getFromFuture(combinedCache.downloadBlob(remoteActionExecutionContext, emptyDigest)))
        .isEmpty();

    try (OutputStream out = file.getOutputStream()) {
      getFromFuture(combinedCache.downloadFile(remoteActionExecutionContext, file, emptyDigest));
    }
    assertThat(file.exists()).isTrue();
    assertThat(file.getFileSize()).isEqualTo(0);
  }

  @Test
  public void downloadActionResult_reportsSpawnCheckingCacheEvent() throws Exception {
    var combinedCache = newCombinedCache();
    var unused =
        combinedCache.downloadActionResult(
            remoteActionExecutionContext,
            digestUtil.asActionKey(digestUtil.computeAsUtf8("key")),
            /* inlineOutErr= */ false,
            /* inlineOutputFiles= */ ImmutableSet.of());

    verify(remoteActionExecutionContext.getSpawnExecutionContext())
        .report(SpawnCheckingCacheEvent.create("remote-cache"));
  }

  @Test
  public void downloadFile_cancelled_cancelDownload() throws Exception {
    // Test that if a download future is cancelled, the download itself is also cancelled.

    // arrange
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    SettableFuture<Void> future = SettableFuture.create();
    // Return a future that never completes
    doAnswer(invocationOnMock -> future).when(remoteCacheClient).downloadBlob(any(), any(), any());
    CombinedCache remoteCache = newCombinedCache(remoteCacheClient);
    Digest digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content");
    Path file = execRoot.getRelative("file");

    // act
    ListenableFuture<Void> download =
        remoteCache.downloadFile(remoteActionExecutionContext, file, digest);
    download.cancel(/* mayInterruptIfRunning= */ true);

    // assert
    assertThat(future.isCancelled()).isTrue();
  }

  @Test
  public void downloadOutErr_empty_doNotPerformDownload() throws Exception {
    // Test that downloading empty stdout/stderr does not try to perform a download.

    InMemoryCombinedCache combinedCache = newCombinedCache();
    Digest emptyDigest = digestUtil.compute(new byte[0]);
    ActionResult.Builder result = ActionResult.newBuilder();
    result.setStdoutDigest(emptyDigest);
    result.setStderrDigest(emptyDigest);

    waitForBulkTransfer(
        combinedCache.downloadOutErr(
            remoteActionExecutionContext,
            result.build(),
            new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr"))),
        true);

    assertThat(combinedCache.getNumSuccessfulDownloads()).isEqualTo(0);
    assertThat(combinedCache.getNumFailedDownloads()).isEqualTo(0);
  }

  @Test
  public void testDownloadFileWithSymlinkTemplate() throws Exception {
    // Test that when a symlink template is provided, we don't actually download files to disk.
    // Instead, a symbolic link should be created that points to a location where the file may
    // actually be found. That location could, for example, be backed by a FUSE file system that
    // exposes the Content Addressable Storage.

    // arrange
    final ConcurrentMap<Digest, byte[]> cas = new ConcurrentHashMap<>();

    Digest helloDigest = digestUtil.computeAsUtf8("hello-contents");
    cas.put(helloDigest, "hello-contents".getBytes(StandardCharsets.UTF_8));

    Path file = fs.getPath("/execroot/symlink-to-file");
    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    options.remoteDownloadSymlinkTemplate = "/home/alice/cas/{hash}-{size_bytes}";
    InMemoryCombinedCache combinedCache = newCombinedCache(cas, options, digestUtil);

    // act
    getFromFuture(combinedCache.downloadFile(remoteActionExecutionContext, file, helloDigest));

    // assert
    assertThat(file.isSymbolicLink()).isTrue();
    assertThat(file.readSymbolicLink())
        .isEqualTo(
            PathFragment.create(
                "/home/alice/cas/a378b939ad2e1d470a9a28b34b0e256b189e85cb236766edc1d46ec3b6ca82e5-14"));
  }

  @Test
  public void upload_emptyBlobAndFile_doNotPerformUpload() throws Exception {
    // Test that uploading an empty BLOB/file does not try to perform an upload.
    InMemoryCombinedCache combinedCache = newCombinedCache();
    Digest emptyDigest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "");
    Path file = execRoot.getRelative("file");

    getFromFuture(
        combinedCache.uploadBlob(remoteActionExecutionContext, emptyDigest, ByteString.EMPTY));
    assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);

    getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, emptyDigest, file));
    assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void upload_deduplicationWorks() throws IOException {
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    AtomicInteger times = new AtomicInteger(0);
    doAnswer(
            invocationOnMock -> {
              times.incrementAndGet();
              return SettableFuture.create();
            })
        .when(remoteCacheClient)
        .uploadFile(any(), any(), any());
    CombinedCache combinedCache = newCombinedCache(remoteCacheClient);
    Digest digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content");
    Path file = execRoot.getRelative("file");

    var unused1 = combinedCache.uploadFile(remoteActionExecutionContext, digest, file);
    var unused2 = combinedCache.uploadFile(remoteActionExecutionContext, digest, file);

    assertThat(times.get()).isEqualTo(1);
  }

  @Test
  public void upload_failedUploads_doNotDeduplicate() throws Exception {
    AtomicBoolean failRequest = new AtomicBoolean(true);
    InMemoryCacheClient inMemoryCacheClient = new InMemoryCacheClient();
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    doAnswer(
            invocationOnMock -> {
              if (failRequest.getAndSet(false)) {
                return Futures.immediateFailedFuture(new IOException("Failed"));
              }
              return inMemoryCacheClient.uploadFile(
                  invocationOnMock.getArgument(0),
                  invocationOnMock.getArgument(1),
                  invocationOnMock.getArgument(2));
            })
        .when(remoteCacheClient)
        .uploadFile(any(), any(), any());
    doAnswer(
            invocationOnMock ->
                inMemoryCacheClient.findMissingDigests(
                    invocationOnMock.getArgument(0), invocationOnMock.getArgument(1)))
        .when(remoteCacheClient)
        .findMissingDigests(any(), any());
    CombinedCache combinedCache = newCombinedCache(remoteCacheClient);
    Digest digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content");
    Path file = execRoot.getRelative("file");
    assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableList.of(digest))))
        .containsExactly(digest);

    Exception thrown = null;
    try {
      getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, digest, file));
    } catch (IOException e) {
      thrown = e;
    }
    assertThat(thrown).isNotNull();
    assertThat(thrown).isInstanceOf(IOException.class);
    getFromFuture(combinedCache.uploadFile(remoteActionExecutionContext, digest, file));

    assertThat(
            getFromFuture(
                combinedCache.findMissingDigests(
                    remoteActionExecutionContext, ImmutableList.of(digest))))
        .isEmpty();
  }

  @Test
  public void ensureInputsPresent_missingInputs_sendLostInputsEvent() throws Exception {
    RemoteCacheClient cacheProtocol = spy(new InMemoryCacheClient());
    RemoteExecutionCache remoteCache = spy(newRemoteExecutionCache(cacheProtocol));
    remoteCache.setRemotePathChecker(
        (context, path) -> path.relativeTo(execRoot).equals(PathFragment.create("foo")));
    var lostInputsEvents = new ConcurrentLinkedQueue<LostInputsEvent>();
    eventBus.register(
        new Object() {
          @Subscribe
          @AllowConcurrentEvents
          public void onLostInputs(LostInputsEvent event) {
            lostInputsEvents.add(event);
          }
        });

    Path path = execRoot.getRelative("foo");
    FileSystemUtils.writeContentAsLatin1(path, "bar");
    SortedMap<PathFragment, Path> inputs = new TreeMap<>();
    inputs.put(PathFragment.create("foo"), path);
    MerkleTree merkleTree = MerkleTree.build(inputs, digestUtil);
    path.delete();

    assertThrows(
        IOException.class,
        () -> {
          remoteCache.ensureInputsPresent(
              remoteActionExecutionContext, merkleTree, ImmutableMap.of(), false, reporter);
        });

    assertThat(lostInputsEvents).hasSize(1);
  }

  @Test
  public void ensureInputsPresent_interruptedDuringUploadBlobs_cancelInProgressUploadTasks()
      throws Exception {
    // arrange
    RemoteCacheClient cacheProtocol = spy(new InMemoryCacheClient());
    RemoteExecutionCache remoteCache = spy(newRemoteExecutionCache(cacheProtocol));

    Deque<SettableFuture<Void>> futures = new ConcurrentLinkedDeque<>();
    CountDownLatch uploadBlobCalls = new CountDownLatch(2);
    doAnswer(
            invocationOnMock -> {
              SettableFuture<Void> future = SettableFuture.create();
              futures.add(future);
              uploadBlobCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadBlob(any(), any(), (Blob) any());
    doAnswer(
            invocationOnMock -> {
              SettableFuture<Void> future = SettableFuture.create();
              futures.add(future);
              uploadBlobCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadFile(any(), any(), any());

    Path path = execRoot.getRelative("foo");
    FileSystemUtils.writeContentAsLatin1(path, "bar");
    SortedMap<PathFragment, Path> inputs = new TreeMap<>();
    inputs.put(PathFragment.create("foo"), path);
    MerkleTree merkleTree = MerkleTree.build(inputs, digestUtil);

    CountDownLatch ensureInputsPresentReturned = new CountDownLatch(1);
    Thread thread =
        new Thread(
            () -> {
              try {
                remoteCache.ensureInputsPresent(
                    remoteActionExecutionContext, merkleTree, ImmutableMap.of(), false, reporter);
              } catch (IOException | InterruptedException ignored) {
                // ignored
              } finally {
                ensureInputsPresentReturned.countDown();
              }
            });

    // act
    thread.start();
    uploadBlobCalls.await();
    assertThat(futures).hasSize(2);
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).isNotEmpty();

    thread.interrupt();
    ensureInputsPresentReturned.await();

    // assert
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).isEmpty();
    assertThat(remoteCache.casUploadCache.getFinishedTasks()).isEmpty();
    for (SettableFuture<Void> future : futures) {
      assertThat(future.isCancelled()).isTrue();
    }
  }

  @Test
  public void
      ensureInputsPresent_multipleConsumers_interruptedOneDuringFindMissingBlobs_keepAndFinishInProgressUploadTasks()
          throws Exception {
    // arrange
    RemoteCacheClient cacheProtocol = spy(new InMemoryCacheClient());
    RemoteExecutionCache remoteCache = newRemoteExecutionCache(cacheProtocol);

    SettableFuture<ImmutableSet<Digest>> findMissingDigestsFuture = SettableFuture.create();
    CountDownLatch findMissingDigestsCalled = new CountDownLatch(1);
    doAnswer(
            invocationOnMock -> {
              findMissingDigestsCalled.countDown();
              return findMissingDigestsFuture;
            })
        .when(cacheProtocol)
        .findMissingDigests(any(), any());
    Deque<SettableFuture<Void>> futures = new ConcurrentLinkedDeque<>();
    CountDownLatch uploadBlobCalls = new CountDownLatch(2);
    doAnswer(
            invocationOnMock -> {
              SettableFuture<Void> future = SettableFuture.create();
              futures.add(future);
              uploadBlobCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadBlob(any(), any(), (Blob) any());
    doAnswer(
            invocationOnMock -> {
              SettableFuture<Void> future = SettableFuture.create();
              futures.add(future);
              uploadBlobCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadFile(any(), any(), any());

    Path path = execRoot.getRelative("foo");
    FileSystemUtils.writeContentAsLatin1(path, "bar");
    SortedMap<PathFragment, Path> inputs = new TreeMap<>();
    inputs.put(PathFragment.create("foo"), path);
    MerkleTree merkleTree = MerkleTree.build(inputs, digestUtil);

    CountDownLatch ensureInputsPresentReturned = new CountDownLatch(2);
    CountDownLatch ensureInterrupted = new CountDownLatch(1);
    Runnable work =
        () -> {
          try {
            remoteCache.ensureInputsPresent(
                remoteActionExecutionContext, merkleTree, ImmutableMap.of(), false, reporter);
          } catch (IOException ignored) {
            // ignored
          } catch (InterruptedException e) {
            ensureInterrupted.countDown();
          } finally {
            ensureInputsPresentReturned.countDown();
          }
        };
    Thread thread1 = new Thread(work);
    Thread thread2 = new Thread(work);
    thread1.start();
    thread2.start();
    findMissingDigestsCalled.await();

    // act
    thread1.interrupt();
    ensureInterrupted.await();
    findMissingDigestsFuture.set(ImmutableSet.copyOf(merkleTree.getAllDigests()));

    uploadBlobCalls.await();
    assertThat(futures).hasSize(2);

    // assert
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).hasSize(2);
    assertThat(remoteCache.casUploadCache.getFinishedTasks()).isEmpty();
    for (SettableFuture<Void> future : futures) {
      assertThat(future.isCancelled()).isFalse();
    }

    for (SettableFuture<Void> future : futures) {
      future.set(null);
    }
    ensureInputsPresentReturned.await();
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).isEmpty();
    assertThat(remoteCache.casUploadCache.getFinishedTasks()).hasSize(2);
  }

  @Test
  public void
      ensureInputsPresent_multipleConsumers_interruptedOneDuringUploadBlobs_keepInProgressUploadTasks()
          throws Exception {
    // arrange
    RemoteCacheClient cacheProtocol = spy(new InMemoryCacheClient());
    RemoteExecutionCache remoteCache = spy(newRemoteExecutionCache(cacheProtocol));

    ConcurrentLinkedDeque<SettableFuture<Void>> uploadBlobFutures = new ConcurrentLinkedDeque<>();
    Map<Path, SettableFuture<Void>> uploadFileFutures = Maps.newConcurrentMap();
    CountDownLatch uploadBlobCalls = new CountDownLatch(2);
    CountDownLatch uploadFileCalls = new CountDownLatch(3);
    doAnswer(
            invocationOnMock -> {
              SettableFuture<Void> future = SettableFuture.create();
              uploadBlobFutures.add(future);
              uploadBlobCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadBlob(any(), any(), (Blob) any());
    doAnswer(
            invocationOnMock -> {
              Path file = invocationOnMock.getArgument(2, Path.class);
              SettableFuture<Void> future = SettableFuture.create();
              uploadFileFutures.put(file, future);
              uploadFileCalls.countDown();
              return future;
            })
        .when(cacheProtocol)
        .uploadFile(any(), any(), any());

    Path foo = execRoot.getRelative("foo");
    FileSystemUtils.writeContentAsLatin1(foo, "foo");
    Path bar = execRoot.getRelative("bar");
    FileSystemUtils.writeContentAsLatin1(bar, "bar");
    Path qux = execRoot.getRelative("qux");
    FileSystemUtils.writeContentAsLatin1(qux, "qux");

    SortedMap<PathFragment, Path> input1 = new TreeMap<>();
    input1.put(PathFragment.create("foo"), foo);
    input1.put(PathFragment.create("bar"), bar);
    MerkleTree merkleTree1 = MerkleTree.build(input1, digestUtil);

    SortedMap<PathFragment, Path> input2 = new TreeMap<>();
    input2.put(PathFragment.create("bar"), bar);
    input2.put(PathFragment.create("qux"), qux);
    MerkleTree merkleTree2 = MerkleTree.build(input2, digestUtil);

    CountDownLatch ensureInputsPresentReturned = new CountDownLatch(2);
    CountDownLatch ensureInterrupted = new CountDownLatch(1);
    Thread thread1 =
        new Thread(
            () -> {
              try {
                remoteCache.ensureInputsPresent(
                    remoteActionExecutionContext, merkleTree1, ImmutableMap.of(), false, reporter);
              } catch (IOException ignored) {
                // ignored
              } catch (InterruptedException e) {
                ensureInterrupted.countDown();
              } finally {
                ensureInputsPresentReturned.countDown();
              }
            });
    Thread thread2 =
        new Thread(
            () -> {
              try {
                remoteCache.ensureInputsPresent(
                    remoteActionExecutionContext, merkleTree2, ImmutableMap.of(), false, reporter);
              } catch (InterruptedException | IOException ignored) {
                // ignored
              } finally {
                ensureInputsPresentReturned.countDown();
              }
            });

    // act
    thread1.start();
    thread2.start();
    uploadBlobCalls.await();
    uploadFileCalls.await();
    assertThat(uploadBlobFutures).hasSize(2);
    assertThat(uploadFileFutures).hasSize(3);
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).hasSize(5);

    thread1.interrupt();
    ensureInterrupted.await();

    // assert
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).hasSize(3);
    assertThat(remoteCache.casUploadCache.getFinishedTasks()).isEmpty();
    for (Map.Entry<Path, SettableFuture<Void>> entry : uploadFileFutures.entrySet()) {
      Path file = entry.getKey();
      SettableFuture<Void> future = entry.getValue();
      if (file.equals(foo)) {
        assertThat(future.isCancelled()).isTrue();
      } else {
        assertThat(future.isCancelled()).isFalse();
      }
    }

    for (SettableFuture<Void> future : uploadBlobFutures) {
      future.set(null);
    }
    for (SettableFuture<Void> future : uploadFileFutures.values()) {
      future.set(null);
    }
    ensureInputsPresentReturned.await();
    assertThat(remoteCache.casUploadCache.getInProgressTasks()).isEmpty();
    assertThat(remoteCache.casUploadCache.getFinishedTasks()).hasSize(3);
  }

  @Test
  public void ensureInputsPresent_uploadFailed_propagateErrors() throws Exception {
    RemoteCacheClient cacheProtocol = spy(new InMemoryCacheClient());
    doAnswer(invocationOnMock -> Futures.immediateFailedFuture(new IOException("upload failed")))
        .when(cacheProtocol)
        .uploadBlob(any(), any(), (Blob) any());
    doAnswer(invocationOnMock -> Futures.immediateFailedFuture(new IOException("upload failed")))
        .when(cacheProtocol)
        .uploadFile(any(), any(), any());
    RemoteExecutionCache remoteCache = spy(newRemoteExecutionCache(cacheProtocol));
    Path path = execRoot.getRelative("foo");
    FileSystemUtils.writeContentAsLatin1(path, "bar");
    SortedMap<PathFragment, Path> inputs = ImmutableSortedMap.of(PathFragment.create("foo"), path);
    MerkleTree merkleTree = MerkleTree.build(inputs, digestUtil);

    IOException e =
        assertThrows(
            IOException.class,
            () ->
                remoteCache.ensureInputsPresent(
                    remoteActionExecutionContext, merkleTree, ImmutableMap.of(), false, reporter));

    assertThat(e).hasMessageThat().contains("upload failed");
  }

  @Test
  public void shutdownNow_cancelInProgressUploads() throws Exception {
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    // Return a future that never completes
    doAnswer(invocationOnMock -> SettableFuture.create())
        .when(remoteCacheClient)
        .uploadFile(any(), any(), any());
    CombinedCache combinedCache = newCombinedCache(remoteCacheClient);
    Digest digest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "content");
    Path file = execRoot.getRelative("file");

    ListenableFuture<Void> upload =
        combinedCache.uploadFile(remoteActionExecutionContext, digest, file);
    assertThat(combinedCache.casUploadCache.getInProgressTasks()).contains(digest);
    combinedCache.shutdownNow();

    assertThat(upload.isCancelled()).isTrue();
  }

  private InMemoryCombinedCache newCombinedCache() {
    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    return new InMemoryCombinedCache(options, digestUtil);
  }

  private InMemoryCombinedCache newCombinedCache(
      Map<Digest, byte[]> casEntries, RemoteOptions options, DigestUtil digestUtil) {
    return new InMemoryCombinedCache(casEntries, options, digestUtil);
  }

  private CombinedCache newCombinedCache(RemoteCacheClient remoteCacheClient) {
    return new CombinedCache(
        remoteCacheClient,
        /* diskCacheClient= */ null,
        Options.getDefaults(RemoteOptions.class),
        digestUtil);
  }

  private RemoteExecutionCache newRemoteExecutionCache(RemoteCacheClient remoteCacheClient) {
    return new RemoteExecutionCache(
        remoteCacheClient,
        /* diskCacheClient= */ null,
        Options.getDefaults(RemoteOptions.class),
        digestUtil);
  }
}
