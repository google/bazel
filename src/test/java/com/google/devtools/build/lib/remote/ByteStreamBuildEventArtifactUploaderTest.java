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
import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashCode;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.remote.ByteStreamUploaderTest.FixedBackoff;
import com.google.devtools.build.lib.remote.ByteStreamUploaderTest.MaybeFailOnceUploadService;
import com.google.devtools.build.lib.remote.common.MissingDigestsFinder;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.options.RemoteBuildEventUploadMode;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule;
import com.google.devtools.build.lib.remote.util.TestUtils;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.bazel.BazelHashFunctions;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.reactivex.rxjava3.core.Single;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Test for {@link ByteStreamBuildEventArtifactUploader}. */
@RunWith(JUnit4.class)
public class ByteStreamBuildEventArtifactUploaderTest {
  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  @Rule public final RxNoGlobalErrorsRule rxNoGlobalErrorsRule = new RxNoGlobalErrorsRule();

  private final Reporter reporter = new Reporter(new EventBus());
  private final StoredEventHandler eventHandler = new StoredEventHandler();

  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private ListeningScheduledExecutorService retryService;

  private Server server;
  private ChannelConnectionWithServerCapabilitiesFactory channelConnectionFactory;

  private final FileSystem fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);

  private final Path execRoot = fs.getPath("/execroot");
  private ArtifactRoot outputRoot;

  @Before
  public final void setUp() throws Exception {
    reporter.addHandler(eventHandler);

    String serverName = "Server for " + this.getClass();
    server =
        InProcessServerBuilder.forName(serverName)
            .fallbackHandlerRegistry(serviceRegistry)
            .build()
            .start();
    channelConnectionFactory =
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
        };

    outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
    outputRoot.getRoot().asPath().createDirectoryAndParents();

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  @After
  public void tearDown() throws Exception {

    retryService.shutdownNow();
    retryService.awaitTermination(
        com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    server.shutdownNow();
    server.awaitTermination();
  }

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void uploadsShouldWork() throws Exception {
    int numUploads = 2;
    Map<HashCode, byte[]> blobsByHash = new HashMap<>();
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      Path file = fs.getPath("/file" + i);
      OutputStream out = file.getOutputStream();
      int blobSize = rand.nextInt(100) + 1;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      out.write(blob);
      out.close();
      blobsByHash.put(HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob);
      filesToUpload.put(
          file, new LocalFile(file, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    }
    serviceRegistry.addService(new MaybeFailOnceUploadService(blobsByHash));

    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    for (Path file : filesToUpload.keySet()) {
      String hash = BaseEncoding.base16().lowerCase().encode(file.getDigest());
      long size = file.getFileSize();
      String conversion = pathConverter.apply(file);
      assertThat(conversion)
          .isEqualTo("bytestream://localhost/instance/blobs/" + hash + "/" + size);
    }

    artifactUploader.release();

    assertThat(combinedCache.refCnt()).isEqualTo(0);
    assertThat(refCntChannel.isShutdown()).isTrue();
  }

  @Test
  public void uploadsShouldWork_fewerPermitsThanUploads() throws Exception {
    int numUploads = 2;
    Map<HashCode, byte[]> blobsByHash = new HashMap<>();
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      Path file = fs.getPath("/file" + i);
      OutputStream out = file.getOutputStream();
      int blobSize = rand.nextInt(100) + 1;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      out.write(blob);
      out.close();
      blobsByHash.put(HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob);
      filesToUpload.put(
          file, new LocalFile(file, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    }
    serviceRegistry.addService(new MaybeFailOnceUploadService(blobsByHash));

    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    // number of permits is less than number of uploads to affirm permit is released
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    for (Path file : filesToUpload.keySet()) {
      String hash = BaseEncoding.base16().lowerCase().encode(file.getDigest());
      long size = file.getFileSize();
      String conversion = pathConverter.apply(file);
      assertThat(conversion)
          .isEqualTo("bytestream://localhost/instance/blobs/" + hash + "/" + size);
    }

    artifactUploader.release();

    assertThat(combinedCache.refCnt()).isEqualTo(0);
    assertThat(refCntChannel.isShutdown()).isTrue();
  }

  @Test
  public void testDirectoryNotUploaded() throws Exception {
    Path dir = fs.getPath("/dir");
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    filesToUpload.put(
        dir, new LocalFile(dir, LocalFileType.OUTPUT_DIRECTORY, /* artifactMetadata= */ null));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    assertThat(pathConverter.apply(dir)).isNull();
    artifactUploader.release();
  }

  @Test
  public void testSymlinkNotUploaded() throws Exception {
    Path sym = fs.getPath("/sym");
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    filesToUpload.put(
        sym, new LocalFile(sym, LocalFileType.OUTPUT_SYMLINK, /* artifactMetadata= */ null));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    assertThat(pathConverter.apply(sym)).isNull();
    artifactUploader.release();
  }

  @Test
  public void testUnknown_uploadedIfFile() throws Exception {
    Path file = fs.getPath("/file");
    file.getOutputStream().close();
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    filesToUpload.put(
        file, new LocalFile(file, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    String hash = BaseEncoding.base16().lowerCase().encode(file.getDigest());
    long size = file.getFileSize();
    String conversion = pathConverter.apply(file);
    assertThat(conversion).isEqualTo("bytestream://localhost/instance/blobs/" + hash + "/" + size);
    artifactUploader.release();
  }

  @Test
  public void testUnknown_uploadedIfFileBlake3() throws Exception {
    assumeNotNull(BazelHashFunctions.BLAKE3);

    FileSystem fs = new InMemoryFileSystem(new JavaClock(), BazelHashFunctions.BLAKE3);
    Path file = fs.getPath("/file");
    file.getOutputStream().close();
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    filesToUpload.put(
        file, new LocalFile(file, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    String hash = BaseEncoding.base16().lowerCase().encode(file.getDigest());
    long size = file.getFileSize();
    String conversion = pathConverter.apply(file);
    assertThat(conversion)
        .isEqualTo("bytestream://localhost/instance/blobs/blake3/" + hash + "/" + size);
    artifactUploader.release();
  }

  @Test
  public void testUnknown_notUploadedIfDirectory() throws Exception {
    Path dir = fs.getPath("/dir");
    dir.createDirectoryAndParents();
    var successfulTest = fs.getPath("/test_passed");
    successfulTest.createDirectory();
    var failedTest = fs.getPath("/test_failed");
    failedTest.createDirectory();
    var filesToUpload =
        ImmutableMap.of(
            dir,
            new LocalFile(dir, LocalFileType.OUTPUT, /* artifactMetadata= */ null),
            successfulTest,
            new LocalFile(
                successfulTest, LocalFileType.SUCCESSFUL_TEST_OUTPUT, /* artifactMetadata= */ null),
            failedTest,
            new LocalFile(
                failedTest, LocalFileType.FAILED_TEST_OUTPUT, /* artifactMetadata= */ null));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    PathConverter pathConverter = artifactUploader.upload(filesToUpload).get();
    assertThat(pathConverter.apply(dir)).isNull();
    assertThat(pathConverter.apply(successfulTest)).isNull();
    assertThat(pathConverter.apply(failedTest)).isNull();
    assertThat(eventHandler.getEvents()).isEmpty();
    artifactUploader.release();
  }

  @Test
  public void someUploadsFail_succeedsWithWarningMessages() throws Exception {
    // Test that if one of multiple file uploads fails, the upload future succeeds but the
    // error is reported correctly.

    int numUploads = 10;
    Map<HashCode, byte[]> blobsByHash = new HashMap<>();
    Map<Path, LocalFile> filesToUpload = new HashMap<>();
    Random rand = new Random();
    for (int i = 0; i < numUploads; i++) {
      Path file = fs.getPath("/file" + i);
      OutputStream out = file.getOutputStream();
      int blobSize = rand.nextInt(100) + 1;
      byte[] blob = new byte[blobSize];
      rand.nextBytes(blob);
      out.write(blob);
      out.flush();
      out.close();
      blobsByHash.put(HashCode.fromString(DIGEST_UTIL.compute(file).getHash()), blob);
      filesToUpload.put(
          file, new LocalFile(file, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    }
    String hashOfBlobThatShouldFail = blobsByHash.keySet().iterator().next().toString();
    serviceRegistry.addService(
        new MaybeFailOnceUploadService(blobsByHash) {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> response) {
            StreamObserver<WriteRequest> delegate = super.write(response);
            return new StreamObserver<WriteRequest>() {
              private boolean failed;

              @Override
              public void onNext(WriteRequest value) {
                if (value.getResourceName().contains(hashOfBlobThatShouldFail)) {
                  response.onError(Status.CANCELLED.asException());
                  failed = true;
                } else {
                  delegate.onNext(value);
                }
              }

              @Override
              public void onError(Throwable t) {
                delegate.onError(t);
              }

              @Override
              public void onCompleted() {
                if (failed) {
                  return;
                }
                delegate.onCompleted();
              }
            };
          }
        });

    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = newCombinedCache(refCntChannel, retrier);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    artifactUploader.upload(filesToUpload).get();

    assertThat(eventHandler.getEvents()).isNotEmpty();
    assertThat(eventHandler.getEvents().get(0).getMessage())
        .contains("Uploading BEP referenced local file /file");

    artifactUploader.release();

    assertThat(combinedCache.refCnt()).isEqualTo(0);
    assertThat(refCntChannel.isShutdown()).isTrue();
  }

  @Test
  public void remoteFileShouldNotBeUploaded_actionFs() throws Exception {
    // Test that we don't attempt to upload remotely stored file but convert the remote path
    // to a bytestream:// URI.

    // arrange

    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = spy(newCombinedCache(refCntChannel, retrier));
    RemoteActionInputFetcher actionInputFetcher = mock(RemoteActionInputFetcher.class);
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    ActionInputMap outputs = new ActionInputMap(2);
    Artifact artifact = createRemoteArtifact("file1.txt", "foo", outputs);

    RemoteActionFileSystem remoteFs =
        new RemoteActionFileSystem(
            fs,
            execRoot.asFragment(),
            outputRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
            outputs,
            ImmutableList.of(artifact),
            StaticInputMetadataProvider.empty(),
            actionInputFetcher);
    Path remotePath = remoteFs.getPath(artifact.getPath().getPathString());
    assertThat(remotePath.getFileSystem()).isEqualTo(remoteFs);
    LocalFile file = new LocalFile(remotePath, LocalFileType.OUTPUT, /* artifactMetadata= */ null);

    // act

    PathConverter pathConverter = artifactUploader.upload(ImmutableMap.of(remotePath, file)).get();

    FileArtifactValue metadata = outputs.getInputMetadata(artifact);
    Digest digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());

    // assert

    String conversion = pathConverter.apply(remotePath);
    assertThat(conversion)
        .isEqualTo(
            "bytestream://localhost/instance/blobs/"
                + digest.getHash()
                + "/"
                + digest.getSizeBytes());
    verify(combinedCache, times(0)).uploadFile(any(), any(), any());
    verify(combinedCache, times(0)).uploadBlob(any(), any(), any());
  }

  @Test
  public void remoteFileShouldNotBeUploaded_findMissingDigests() throws Exception {
    // Test that findMissingDigests is called to check which files exist remotely
    // and that those are not uploaded.

    // arrange
    Path remoteFile = fs.getPath("/remote-file");
    FileSystemUtils.writeContent(remoteFile, StandardCharsets.UTF_8, "hello world");
    Digest remoteDigest = DIGEST_UTIL.compute(remoteFile);
    Path localFile = fs.getPath("/local-file");
    FileSystemUtils.writeContent(localFile, StandardCharsets.UTF_8, "foo bar");
    Digest localDigest = DIGEST_UTIL.compute(localFile);

    StaticMissingDigestsFinder digestQuerier =
        Mockito.spy(new StaticMissingDigestsFinder(ImmutableSet.of(remoteDigest)));
    RemoteRetrier retrier =
        TestUtils.newRemoteRetrier(() -> new FixedBackoff(1, 0), (e) -> true, retryService);
    ReferenceCountedChannel refCntChannel = new ReferenceCountedChannel(channelConnectionFactory);
    CombinedCache combinedCache = spy(newCombinedCache(refCntChannel, retrier, digestQuerier));
    doAnswer(invocationOnMock -> Futures.immediateFuture(null))
        .when(combinedCache)
        .uploadFile(any(), any(), any());
    ByteStreamBuildEventArtifactUploader artifactUploader = newArtifactUploader(combinedCache);

    // act
    Map<Path, LocalFile> files =
        ImmutableMap.of(
            remoteFile,
            new LocalFile(remoteFile, LocalFileType.OUTPUT, /* artifactMetadata= */ null),
            localFile,
            new LocalFile(localFile, LocalFileType.OUTPUT, /* artifactMetadata= */ null));
    PathConverter pathConverter = artifactUploader.upload(files).get();

    // assert
    verify(digestQuerier).findMissingDigests(any(), any());
    verify(combinedCache).uploadFile(any(), eq(localDigest), any());
    assertThat(pathConverter.apply(remoteFile)).contains(remoteDigest.getHash());
    assertThat(pathConverter.apply(localFile)).contains(localDigest.getHash());
  }

  /** Returns a remote artifact and puts its metadata into the action input map. */
  private Artifact createRemoteArtifact(
      String pathFragment, String contents, ActionInputMap inputs) {
    Path p = outputRoot.getRoot().asPath().getRelative(pathFragment);
    Artifact a = ActionsTestUtil.createArtifact(outputRoot, p);
    byte[] b = contents.getBytes(StandardCharsets.UTF_8);
    HashCode h = HashCode.fromString(DIGEST_UTIL.compute(b).getHash());
    FileArtifactValue f =
        FileArtifactValue.createForRemoteFile(h.asBytes(), b.length, /* locationIndex= */ 1);
    inputs.putWithNoDepOwner(a, f);
    return a;
  }

  private CombinedCache newCombinedCache(ReferenceCountedChannel channel, RemoteRetrier retrier) {
    return newCombinedCache(channel, retrier, new AllMissingDigestsFinder());
  }

  private CombinedCache newCombinedCache(
      ReferenceCountedChannel channel,
      RemoteRetrier retrier,
      MissingDigestsFinder missingDigestsFinder) {
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteInstanceName = "instance";
    GrpcCacheClient cacheClient =
        spy(
            new GrpcCacheClient(
                channel,
                CallCredentialsProvider.NO_CREDENTIALS,
                remoteOptions,
                retrier,
                DIGEST_UTIL));
    doAnswer(
            invocationOnMock ->
                missingDigestsFinder.findMissingDigests(
                    invocationOnMock.getArgument(0), invocationOnMock.getArgument(1)))
        .when(cacheClient)
        .findMissingDigests(any(), any());

    return new CombinedCache(cacheClient, /* diskCacheClient= */ null, remoteOptions, DIGEST_UTIL);
  }

  private ByteStreamBuildEventArtifactUploader newArtifactUploader(CombinedCache combinedCache) {

    return new ByteStreamBuildEventArtifactUploader(
        MoreExecutors.directExecutor(),
        reporter,
        /* verboseFailures= */ true,
        combinedCache,
        /* remoteInstanceName= */ "",
        /* remoteBytestreamUriPrefix= */ "localhost/instance",
        /* buildRequestId= */ "none",
        /* commandId= */ "none",
        SyscallCache.NO_CACHE,
        RemoteBuildEventUploadMode.ALL);
  }

  private static class StaticMissingDigestsFinder implements MissingDigestsFinder {

    private final ImmutableSet<Digest> knownDigests;

    public StaticMissingDigestsFinder(ImmutableSet<Digest> knownDigests) {
      this.knownDigests = knownDigests;
    }

    @Override
    public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
        RemoteActionExecutionContext context, Iterable<Digest> digests) {
      ImmutableSet.Builder<Digest> missingDigests = ImmutableSet.builder();
      for (Digest digest : digests) {
        if (!knownDigests.contains(digest)) {
          missingDigests.add(digest);
        }
      }
      return Futures.immediateFuture(missingDigests.build());
    }
  }

  private static class AllMissingDigestsFinder implements MissingDigestsFinder {

    @Override
    public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(
        RemoteActionExecutionContext context, Iterable<Digest> digests) {
      return Futures.immediateFuture(ImmutableSet.copyOf(digests));
    }
  }
}
