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
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.SpawnCache.CacheHandle;
import com.google.devtools.build.lib.exec.SpawnInputExpander;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.disk.DiskCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.util.TempPathGenerator;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Tests for {@link RemoteSpawnCache}. */
@RunWith(JUnit4.class)
public class RemoteSpawnCacheTest {

  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker(
          new JavaClock(), "build", RemoteOutputsMode.MINIMAL, ImmutableList.of());

  private static final String BUILD_REQUEST_ID = "build-req-id";
  private static final String COMMAND_ID = "command-id";

  private FileSystem fs;
  private DigestUtil digestUtil;
  private Path execRoot;
  private TempPathGenerator tempPathGenerator;
  private SimpleSpawn simpleSpawn;
  private SpawnExecutionContext simplePolicy;
  @Mock private CombinedCache combinedCache;
  private FileOutErr outErr;

  private StoredEventHandler eventHandler = new StoredEventHandler();

  private Reporter reporter;
  private RemotePathResolver remotePathResolver;

  private static SpawnExecutionContext createSpawnExecutionContext(
      Spawn spawn, Path execRoot, FakeActionInputFileCache fakeFileCache, FileOutErr outErr) {
    return new SpawnExecutionContext() {
      @Nullable private com.google.devtools.build.lib.exec.Protos.Digest digest;

      @Override
      public int getId() {
        return 0;
      }

      @Override
      public void setDigest(com.google.devtools.build.lib.exec.Protos.Digest digest) {
        checkState(this.digest == null);
        this.digest = digest;
      }

      @Override
      @Nullable
      public com.google.devtools.build.lib.exec.Protos.Digest getDigest() {
        return digest;
      }

      @Override
      public ListenableFuture<Void> prefetchInputs() {
        return immediateVoidFuture();
      }

      @Override
      public void lockOutputFiles(int exitCode, String errorMessage, FileOutErr outErr) {}

      @Override
      public boolean speculating() {
        return false;
      }

      @Override
      public InputMetadataProvider getInputMetadataProvider() {
        return fakeFileCache;
      }

      @Override
      public ArtifactPathResolver getPathResolver() {
        return ArtifactPathResolver.forExecRoot(execRoot);
      }

      @Override
      public ArtifactExpander getArtifactExpander() {
        throw new UnsupportedOperationException();
      }

      @Override
      public SpawnInputExpander getSpawnInputExpander() {
        return new SpawnInputExpander(execRoot);
      }

      @Override
      public Duration getTimeout() {
        return Duration.ZERO;
      }

      @Override
      public FileOutErr getFileOutErr() {
        return outErr;
      }

      @Override
      public SortedMap<PathFragment, ActionInput> getInputMapping(
          PathFragment baseDirectory, boolean willAccessRepeatedly)
          throws ForbiddenActionInputException {
        return getSpawnInputExpander()
            .getInputMapping(
                spawn, treeArtifact -> ImmutableSortedSet.of(), fakeFileCache, baseDirectory);
      }

      @Override
      public void report(ProgressStatus progress) {}

      @Override
      public boolean isRewindingEnabled() {
        return false;
      }

      @Override
      public void checkForLostInputs() {}

      @Override
      public <T extends ActionContext> T getContext(Class<T> identifyingType) {
        throw new UnsupportedOperationException();
      }

      @Nullable
      @Override
      public FileSystem getActionFileSystem() {
        return null;
      }
    };
  }

  private static SimpleSpawn simpleSpawnWithExecutionInfo(
      ImmutableMap<String, String> executionInfo) {
    return new SimpleSpawn(
        new FakeOwner("Mnemonic", "Progress Message", "//dummy:label"),
        ImmutableList.of("/bin/echo", "Hi!"),
        ImmutableMap.of("VARIABLE", "value"),
        executionInfo,
        /* inputs= */ NestedSetBuilder.create(
            Order.STABLE_ORDER, ActionInputHelper.fromPath("input")),
        /* outputs= */ ImmutableSet.of(ActionInputHelper.fromPath("/random/file")),
        ResourceSet.ZERO);
  }

  private static SimpleSpawn simplePathMappedSpawn(String configSegment) {
    return simplePathMappedSpawn(
        configSegment, new FakeOwner("Mnemonic", "Progress Message", "//dummy:label"));
  }

  private static SimpleSpawn simplePathMappedSpawn(
      String configSegment, ActionExecutionMetadata owner) {
    String inputPath = "bazel-bin/%s/bin/input";
    String outputPath = "bazel-bin/%s/bin/output";
    return new SimpleSpawn(
        owner,
        ImmutableList.of("cp", inputPath.formatted("cfg"), outputPath.formatted("cfg")),
        ImmutableMap.of("VARIABLE", "value"),
        ImmutableMap.of(ExecutionRequirements.SUPPORTS_PATH_MAPPING, ""),
        /* filesetMappings= */ ImmutableMap.of(),
        /* inputs= */ NestedSetBuilder.create(
            Order.STABLE_ORDER, ActionInputHelper.fromPath(inputPath.formatted(configSegment))),
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.of(
            ActionInputHelper.fromPath(outputPath.formatted(configSegment))),
        /* mandatoryOutputs= */ null,
        ResourceSet.ZERO,
        execPath ->
            execPath.subFragment(0, 1).getRelative("cfg").getRelative(execPath.subFragment(2)));
  }

  private RemoteSpawnCache createRemoteSpawnCache() {
    return remoteSpawnCacheWithOptions(Options.getDefaults(RemoteOptions.class));
  }

  private RemoteSpawnCache remoteSpawnCacheWithOptions(RemoteOptions options) {
    RemoteExecutionService service =
        spy(
            new RemoteExecutionService(
                directExecutor(),
                reporter,
                /* verboseFailures= */ true,
                execRoot,
                remotePathResolver,
                BUILD_REQUEST_ID,
                COMMAND_ID,
                digestUtil,
                options,
                combinedCache,
                null,
                tempPathGenerator,
                /* captureCorruptedOutputsDir= */ null,
                DUMMY_REMOTE_OUTPUT_CHECKER,
                mock(OutputService.class),
                Sets.newConcurrentHashSet()));
    return new RemoteSpawnCache(
        execRoot, options, /* verboseFailures= */ true, service, digestUtil);
  }

  @Before
  public final void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    execRoot = fs.getPath("/exec/root");
    execRoot.createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"));
    FakeActionInputFileCache fakeFileCache = new FakeActionInputFileCache(execRoot);
    simpleSpawn = simpleSpawnWithExecutionInfo(ImmutableMap.of());

    Path stdout = fs.getPath("/tmp/stdout");
    Path stderr = fs.getPath("/tmp/stderr");
    stdout.getParentDirectory().createDirectoryAndParents();
    stderr.getParentDirectory().createDirectoryAndParents();
    outErr = new FileOutErr(stdout, stderr);
    reporter = new Reporter(new EventBus());
    eventHandler = new StoredEventHandler();
    reporter.addHandler(eventHandler);

    remotePathResolver = RemotePathResolver.createDefault(execRoot);
    simplePolicy = createSpawnExecutionContext(simpleSpawn, execRoot, fakeFileCache, outErr);

    fakeFileCache.createScratchInput(simpleSpawn.getInputFiles().getSingleton(), "xyz");

    when(combinedCache.hasRemoteCache()).thenReturn(true);
    when(combinedCache.remoteActionCacheSupportsUpdate()).thenReturn(true);
  }

  @Test
  public void cacheHit() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();
    RemoteExecutionService service = cache.getRemoteExecutionService();
    ArgumentCaptor<ActionKey> actionKeyCaptor = ArgumentCaptor.forClass(ActionKey.class);
    ActionResult actionResult = ActionResult.getDefaultInstance();
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            actionKeyCaptor.capture(),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenAnswer(
            new Answer<CachedActionResult>() {
              @Override
              public CachedActionResult answer(InvocationOnMock invocation) {
                RemoteActionExecutionContext context = invocation.getArgument(0);
                RequestMetadata meta = context.getRequestMetadata();
                assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID);
                assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID);
                return CachedActionResult.remote(actionResult);
              }
            });
    doAnswer(
            (Answer<Void>)
                invocation -> {
                  RemoteAction action = invocation.getArgument(0);
                  RemoteActionExecutionContext context = action.getRemoteActionExecutionContext();
                  RequestMetadata meta = context.getRequestMetadata();
                  assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID);
                  assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID);
                  return null;
                })
        .when(service)
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))));

    // act
    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);
    assertThat(entry.hasResult()).isTrue();
    SpawnResult result = entry.getResult();

    // assert
    // All other methods on RemoteActionCache have side effects, so we verify all of them.
    assertThat(simplePolicy.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()));
    verify(service)
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))));
    verify(service, never()).uploadOutputs(any(), any(), any());
    assertThat(result.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()));
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.isCacheHit()).isTrue();
    // We expect the CachedLocalSpawnRunner to _not_ write to outErr at all.
    assertThat(outErr.hasRecordedOutput()).isFalse();
    assertThat(outErr.hasRecordedStderr()).isFalse();
  }

  @Test
  public void cacheMiss() throws Exception {
    RemoteSpawnCache cache = createRemoteSpawnCache();
    RemoteExecutionService service = cache.getRemoteExecutionService();
    ArgumentCaptor<ActionKey> actionKeyCaptor = ArgumentCaptor.forClass(ActionKey.class);
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            actionKeyCaptor.capture(),
            anyBoolean(),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);

    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);

    assertThat(simplePolicy.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()));
    assertThat(entry.hasResult()).isFalse();
    SpawnResult result =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(Status.SUCCESS)
            .setRunnerName("test")
            .build();
    doNothing().when(service).uploadOutputs(any(), any(), any());
    entry.store(result);
    verify(service).uploadOutputs(any(), any(), any());
  }

  @Test
  public void noCacheSpawns() throws Exception {
    // Checks that spawns satisfying Spawns.mayBeCached=false are not looked up in the cache
    // (even if it is a local cache) and that the results/artifacts are not uploaded to the cache.

    RemoteOptions withLocalCache = Options.getDefaults(RemoteOptions.class);
    withLocalCache.diskCache = PathFragment.create("/etc/something/cache/here");
    for (var remoteOptions :
        ImmutableList.of(Options.getDefaults(RemoteOptions.class), withLocalCache)) {

      DiskCacheClient diskCacheClient = null;
      RemoteCacheClient remoteCacheClient = null;
      if (remoteOptions == withLocalCache) {
        diskCacheClient = mock(DiskCacheClient.class);
      } else {
        remoteCacheClient = mock(RemoteCacheClient.class);
      }
      combinedCache =
          spy(new CombinedCache(remoteCacheClient, diskCacheClient, remoteOptions, digestUtil));

      var remoteSpawnCache = remoteSpawnCacheWithOptions(remoteOptions);
      for (String requirement :
          ImmutableList.of(ExecutionRequirements.NO_CACHE, ExecutionRequirements.LOCAL)) {
        SimpleSpawn uncacheableSpawn =
            simpleSpawnWithExecutionInfo(ImmutableMap.of(requirement, ""));
        CacheHandle entry = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy);
        verify(remoteSpawnCache.getRemoteExecutionService(), never()).lookupCache(any());
        assertThat(simplePolicy.getDigest()).isNull();
        assertThat(entry.hasResult()).isFalse();
        SpawnResult result =
            new SpawnResult.Builder()
                .setExitCode(0)
                .setStatus(Status.SUCCESS)
                .setRunnerName("test")
                .build();
        entry.store(result);
        if (remoteOptions == withLocalCache) {
          verifyNoMoreInteractions(diskCacheClient);
        } else {
          verifyNoMoreInteractions(remoteCacheClient);
        }
      }
    }
  }

  @Test
  public void noRemoteCacheSpawns_remoteCache() throws Exception {
    // Checks that spawns satisfying Spawns.mayBeCachedRemotely=false are not looked up in the
    // remote cache, and that the results/artifacts are not uploaded to the remote cache.

    RemoteOptions remoteCacheOptions = Options.getDefaults(RemoteOptions.class);
    remoteCacheOptions.remoteCache = "https://somecache.com";
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    combinedCache =
        spy(
            new CombinedCache(
                remoteCacheClient, /* diskCacheClient= */ null, remoteCacheOptions, digestUtil));
    RemoteSpawnCache remoteSpawnCache = remoteSpawnCacheWithOptions(remoteCacheOptions);
    for (String requirement :
        ImmutableList.of(
            ExecutionRequirements.NO_CACHE,
            ExecutionRequirements.LOCAL,
            ExecutionRequirements.NO_REMOTE_CACHE,
            ExecutionRequirements.NO_REMOTE)) {
      SimpleSpawn uncacheableSpawn = simpleSpawnWithExecutionInfo(ImmutableMap.of(requirement, ""));
      CacheHandle entry = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy);
      verify(combinedCache, never())
          .downloadActionResult(
              any(RemoteActionExecutionContext.class),
              any(ActionKey.class),
              anyBoolean(),
              ArgumentMatchers.<Set<String>>any());
      assertThat(simplePolicy.getDigest()).isNull();
      assertThat(entry.hasResult()).isFalse();
      SpawnResult result =
          new SpawnResult.Builder()
              .setExitCode(0)
              .setStatus(Status.SUCCESS)
              .setRunnerName("test")
              .build();
      entry.store(result);
      verifyNoMoreInteractions(remoteCacheClient);
    }
  }

  @Test
  public void noRemoteCacheSpawns_combinedCache() throws Exception {
    // Checks that spawns satisfying Spawns.mayBeCachedRemotely=false are not looked up in the
    // remote cache, and that the results/artifacts are not uploaded to the remote cache.
    // The disk cache part of a combined cache is considered as a local cache hence spawns tagged
    // with NO_REMOTE can sill hit it.
    RemoteOptions combinedCacheOptions = Options.getDefaults(RemoteOptions.class);
    combinedCacheOptions.remoteCache = "https://somecache.com";
    combinedCacheOptions.diskCache = PathFragment.create("/etc/something/cache/here");
    RemoteSpawnCache remoteSpawnCache = remoteSpawnCacheWithOptions(combinedCacheOptions);
    RemoteCacheClient remoteCacheClient = mock(RemoteCacheClient.class);
    DiskCacheClient diskCacheClient = mock(DiskCacheClient.class);
    combinedCache =
        spy(
            new CombinedCache(
                remoteCacheClient, diskCacheClient, combinedCacheOptions, digestUtil));

    for (String requirement :
        ImmutableList.of(ExecutionRequirements.NO_CACHE, ExecutionRequirements.LOCAL)) {
      SimpleSpawn uncacheableSpawn = simpleSpawnWithExecutionInfo(ImmutableMap.of(requirement, ""));
      CacheHandle entry = remoteSpawnCache.lookup(uncacheableSpawn, simplePolicy);
      verify(combinedCache, never())
          .downloadActionResult(
              any(RemoteActionExecutionContext.class),
              any(ActionKey.class),
              /* inlineOutErr= */ eq(false),
              /* inlineOutputFiles= */ eq(ImmutableSet.of()));
      assertThat(simplePolicy.getDigest()).isNull();
      assertThat(entry.hasResult()).isFalse();
      SpawnResult result =
          new SpawnResult.Builder()
              .setExitCode(0)
              .setStatus(Status.SUCCESS)
              .setRunnerName("test")
              .build();
      entry.store(result);
      verifyNoMoreInteractions(remoteCacheClient);
    }
  }

  @Test
  public void noRemoteCacheStillUsesLocalCache() throws Exception {
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here");
    when(combinedCache.hasRemoteCache()).thenReturn(false);
    when(combinedCache.hasDiskCache()).thenReturn(true);
    RemoteSpawnCache cache = remoteSpawnCacheWithOptions(remoteOptions);
    ArgumentCaptor<ActionKey> actionKeyCaptor = ArgumentCaptor.forClass(ActionKey.class);
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            actionKeyCaptor.capture(),
            anyBoolean(),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    SimpleSpawn cacheableSpawn =
        simpleSpawnWithExecutionInfo(ImmutableMap.of(ExecutionRequirements.NO_REMOTE_CACHE, ""));

    cache.lookup(cacheableSpawn, simplePolicy);

    assertThat(simplePolicy.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()));
    verify(combinedCache)
        .downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of()));
  }

  @Test
  public void noRemoteExecStillUsesCache() throws Exception {
    RemoteSpawnCache cache = createRemoteSpawnCache();
    SimpleSpawn cacheableSpawn =
        simpleSpawnWithExecutionInfo(ImmutableMap.of(ExecutionRequirements.NO_REMOTE_EXEC, ""));
    ArgumentCaptor<ActionKey> actionKeyCaptor = ArgumentCaptor.forClass(ActionKey.class);
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            actionKeyCaptor.capture(),
            anyBoolean(),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);

    cache.lookup(cacheableSpawn, simplePolicy);

    assertThat(simplePolicy.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(actionKeyCaptor.getValue()));
    verify(combinedCache)
        .downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of()));
  }

  @Test
  public void failedActionsAreNotUploaded() throws Exception {
    // Only successful action results are uploaded to the remote cache.
    RemoteSpawnCache cache = createRemoteSpawnCache();
    RemoteExecutionService service = cache.getRemoteExecutionService();
    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);
    verify(combinedCache)
        .downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of()));
    assertThat(entry.hasResult()).isFalse();
    SpawnResult result =
        new SpawnResult.Builder()
            .setExitCode(1)
            .setStatus(Status.NON_ZERO_EXIT)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                    .build())
            .setRunnerName("test")
            .build();
    entry.store(result);
    verify(service, never()).uploadOutputs(any(), any(), any());
  }

  @Test
  public void printWarningIfDownloadFails() throws Exception {
    RemoteSpawnCache cache = createRemoteSpawnCache();
    RemoteExecutionService service = cache.getRemoteExecutionService();
    doThrow(new IOException(io.grpc.Status.UNAVAILABLE.asRuntimeException()))
        .when(combinedCache)
        .downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of()));

    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);
    assertThat(entry.hasResult()).isFalse();
    SpawnResult result =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(Status.SUCCESS)
            .setRunnerName("test")
            .build();

    doNothing().when(service).uploadOutputs(any(), any(), any());
    entry.store(result);
    verify(service).uploadOutputs(any(), eq(result), any());

    assertThat(eventHandler.getEvents()).hasSize(1);
    Event evt = eventHandler.getEvents().get(0);
    assertThat(evt.getKind()).isEqualTo(EventKind.WARNING);
    assertThat(evt.getMessage()).contains("UNAVAILABLE");
  }

  @Test
  public void orphanedCachedResultIgnored() throws Exception {
    RemoteSpawnCache cache = createRemoteSpawnCache();
    RemoteExecutionService service = cache.getRemoteExecutionService();
    Digest digest = digestUtil.computeAsUtf8("bla");
    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(OutputFile.newBuilder().setPath("/random/file").setDigest(digest))
            .build();
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenAnswer(
            new Answer<CachedActionResult>() {
              @Override
              public CachedActionResult answer(InvocationOnMock invocation) {
                RemoteActionExecutionContext context = invocation.getArgument(0);
                RequestMetadata meta = context.getRequestMetadata();
                assertThat(meta.getCorrelatedInvocationsId()).isEqualTo(BUILD_REQUEST_ID);
                assertThat(meta.getToolInvocationId()).isEqualTo(COMMAND_ID);
                return CachedActionResult.remote(actionResult);
              }
            });
    doThrow(new CacheNotFoundException(digest))
        .when(service)
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(actionResult))));

    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);
    assertThat(entry.hasResult()).isFalse();
    SpawnResult result =
        new SpawnResult.Builder()
            .setExitCode(0)
            .setStatus(Status.SUCCESS)
            .setRunnerName("test")
            .build();

    doNothing().when(service).uploadOutputs(any(), any(), any());
    entry.store(result);
    verify(service).uploadOutputs(any(), eq(result), any());
    assertThat(eventHandler.getEvents()).isEmpty(); // no warning is printed.
  }

  @Test
  public void failedCacheActionAsCacheMiss() throws Exception {
    RemoteSpawnCache cache = createRemoteSpawnCache();
    ActionResult actionResult = ActionResult.newBuilder().setExitCode(1).build();
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(actionResult));

    CacheHandle entry = cache.lookup(simpleSpawn, simplePolicy);

    assertThat(entry.hasResult()).isFalse();
  }

  @Test
  public void testDownloadMinimal() throws Exception {
    // arrange
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteSpawnCache cache = remoteSpawnCacheWithOptions(remoteOptions);

    ActionResult success = ActionResult.newBuilder().setExitCode(0).build();
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(success));
    doReturn(null).when(cache.getRemoteExecutionService()).downloadOutputs(any(), any());

    // act
    CacheHandle cacheHandle = cache.lookup(simpleSpawn, simplePolicy);

    // assert
    assertThat(cacheHandle.hasResult()).isTrue();
    assertThat(cacheHandle.getResult().exitCode()).isEqualTo(0);
    verify(cache.getRemoteExecutionService())
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(success))));
  }

  @Test
  public void testDownloadMinimalIoError() throws Exception {
    // arrange
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;
    RemoteSpawnCache cache = remoteSpawnCacheWithOptions(remoteOptions);

    IOException downloadFailure = new IOException("downloadMinimal failed");

    ActionResult success = ActionResult.newBuilder().setExitCode(0).build();
    when(combinedCache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(CachedActionResult.remote(success));
    doThrow(downloadFailure)
        .when(cache.getRemoteExecutionService())
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(success))));

    // act
    CacheHandle cacheHandle = cache.lookup(simpleSpawn, simplePolicy);

    // assert
    assertThat(cacheHandle.hasResult()).isFalse();
    verify(cache.getRemoteExecutionService())
        .downloadOutputs(
            any(), eq(RemoteActionResult.createFromCache(CachedActionResult.remote(success))));
    assertThat(eventHandler.getEvents().size()).isEqualTo(1);
    Event evt = eventHandler.getEvents().get(0);
    assertThat(evt.getKind()).isEqualTo(EventKind.WARNING);
    assertThat(evt.getMessage()).contains(downloadFailure.getMessage());
  }

  @Test
  public void pathMappedActionIsDeduplicated() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();

    SimpleSpawn firstSpawn = simplePathMappedSpawn("k8-fastbuild");
    FakeActionInputFileCache firstFakeFileCache = new FakeActionInputFileCache(execRoot);
    firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext firstPolicy =
        createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr);

    SimpleSpawn secondSpawn = simplePathMappedSpawn("k8-opt");
    FakeActionInputFileCache secondFakeFileCache = new FakeActionInputFileCache(execRoot);
    secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext secondPolicy =
        createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr);

    RemoteExecutionService remoteExecutionService = cache.getRemoteExecutionService();
    Mockito.doCallRealMethod().when(remoteExecutionService).waitForAndReuseOutputs(any(), any());
    // Simulate a very slow upload to the remote cache to ensure that the second spawn is
    // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
    // concurrency to this test.
    Mockito.doNothing().when(remoteExecutionService).uploadOutputs(any(), any(), any());

    // act
    try (CacheHandle firstCacheHandle = cache.lookup(firstSpawn, firstPolicy)) {
      Path outputFile = fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output");
      FileSystemUtils.writeContent(outputFile, "hello");
      firstCacheHandle.store(
          new SpawnResult.Builder()
              .setExitCode(0)
              .setStatus(Status.SUCCESS)
              .setRunnerName("test")
              .build());
    }
    CacheHandle secondCacheHandle = cache.lookup(secondSpawn, secondPolicy);

    // assert
    assertThat(secondCacheHandle.hasResult()).isTrue();
    assertThat(secondCacheHandle.getResult().getRunnerName()).isEqualTo("deduplicated");
    assertThat(
            FileSystemUtils.readContentToString(
                fs.getPath("/exec/root/bazel-bin/k8-opt/bin/output")))
        .isEqualTo("hello");
    assertThat(secondCacheHandle.willStore()).isFalse();
  }

  @Test
  public void pathMappedActionIsDeduplicatedWithSpawnOutputModification() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();

    ActionExecutionMetadata firstExecutionOwner =
        new FakeOwner("Mnemonic", "Progress Message", "//dummy:label") {
          @Override
          public boolean mayModifySpawnOutputsAfterExecution() {
            return true;
          }
        };
    SimpleSpawn firstSpawn = simplePathMappedSpawn("k8-fastbuild", firstExecutionOwner);
    FakeActionInputFileCache firstFakeFileCache = new FakeActionInputFileCache(execRoot);
    firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext firstPolicy =
        createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr);

    SimpleSpawn secondSpawn = simplePathMappedSpawn("k8-opt");
    FakeActionInputFileCache secondFakeFileCache = new FakeActionInputFileCache(execRoot);
    secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext secondPolicy =
        createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr);

    RemoteExecutionService remoteExecutionService = cache.getRemoteExecutionService();
    CountDownLatch enteredWaitForAndReuseOutputs = new CountDownLatch(1);
    CountDownLatch completeWaitForAndReuseOutputs = new CountDownLatch(1);
    CountDownLatch enteredUploadOutputs = new CountDownLatch(1);
    Set<Spawn> spawnsThatWaitedForOutputReuse = ConcurrentHashMap.newKeySet();
    Mockito.doAnswer(
            (Answer<SpawnResult>)
                invocation -> {
                  spawnsThatWaitedForOutputReuse.add(
                      ((RemoteAction) invocation.getArgument(0)).getSpawn());
                  enteredWaitForAndReuseOutputs.countDown();
                  completeWaitForAndReuseOutputs.await();
                  return (SpawnResult) invocation.callRealMethod();
                })
        .when(remoteExecutionService)
        .waitForAndReuseOutputs(any(), any());
    // Simulate a very slow upload to the remote cache to ensure that the second spawn is
    // deduplicated rather than a cache hit. This is a slight hack, but also avoids introducing
    // more concurrency to this test.
    Mockito.doAnswer(
            (Answer<Void>)
                invocation -> {
                  enteredUploadOutputs.countDown();
                  return null;
                })
        .when(remoteExecutionService)
        .uploadOutputs(any(), any(), any());

    // act
    // Simulate the first spawn writing to the output, but delay its completion.
    CacheHandle firstCacheHandle = cache.lookup(firstSpawn, firstPolicy);
    Path outputFile1 = fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output");
    FileSystemUtils.writeContent(outputFile1, "hello");

    // Start the second spawn and wait for it to deduplicate against the first one.
    AtomicReference<CacheHandle> secondCacheHandleRef = new AtomicReference<>();
    Thread lookupSecondSpawn =
        new Thread(
            () -> {
              try {
                secondCacheHandleRef.set(cache.lookup(secondSpawn, secondPolicy));
              } catch (InterruptedException
                  | IOException
                  | ExecException
                  | ForbiddenActionInputException e) {
                throw new IllegalStateException(e);
              }
            });
    lookupSecondSpawn.start();
    enteredWaitForAndReuseOutputs.await();

    // Complete the first spawn and immediately corrupt its outputs.
    Thread completeFirstSpawn =
        new Thread(
            () -> {
              try {
                firstCacheHandle.store(
                    new SpawnResult.Builder()
                        .setExitCode(0)
                        .setStatus(Status.SUCCESS)
                        .setRunnerName("test")
                        .build());
                Path outputFile = fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output");
                FileSystemUtils.writeContent(outputFile, "corrupted");
              } catch (IOException | ExecException | InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });
    completeFirstSpawn.start();
    // Make it more likely to detect races by waiting for the first spawn to (fake) upload its
    // outputs.
    enteredUploadOutputs.await();

    // Let the second spawn complete its output reuse.
    completeWaitForAndReuseOutputs.countDown();
    lookupSecondSpawn.join();
    CacheHandle secondCacheHandle = secondCacheHandleRef.get();

    completeFirstSpawn.join();

    // assert
    assertThat(spawnsThatWaitedForOutputReuse).containsExactly(secondSpawn);
    assertThat(secondCacheHandle.hasResult()).isTrue();
    assertThat(secondCacheHandle.getResult().getRunnerName()).isEqualTo("deduplicated");
    assertThat(
            FileSystemUtils.readContentToString(
                fs.getPath("/exec/root/bazel-bin/k8-opt/bin/output")))
        .isEqualTo("hello");
    assertThat(secondCacheHandle.willStore()).isFalse();
  }

  @Test
  public void pathMappedActionWithInMemoryOutputIsDeduplicated() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();

    SimpleSpawn firstSpawn = simplePathMappedSpawn("k8-fastbuild");
    FakeActionInputFileCache firstFakeFileCache = new FakeActionInputFileCache(execRoot);
    firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext firstPolicy =
        createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr);

    SimpleSpawn secondSpawn = simplePathMappedSpawn("k8-opt");
    FakeActionInputFileCache secondFakeFileCache = new FakeActionInputFileCache(execRoot);
    secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext secondPolicy =
        createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr);

    RemoteExecutionService remoteExecutionService = cache.getRemoteExecutionService();
    Mockito.doCallRealMethod().when(remoteExecutionService).waitForAndReuseOutputs(any(), any());
    // Simulate a very slow upload to the remote cache to ensure that the second spawn is
    // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
    // concurrency to this test.
    Mockito.doNothing().when(remoteExecutionService).uploadOutputs(any(), any(), any());

    // act
    try (CacheHandle firstCacheHandle = cache.lookup(firstSpawn, firstPolicy)) {
      firstCacheHandle.store(
          new SpawnResult.Builder()
              .setExitCode(0)
              .setStatus(Status.SUCCESS)
              .setRunnerName("test")
              .setInMemoryOutput(
                  firstSpawn.getOutputFiles().getFirst(), ByteString.copyFromUtf8("in-memory"))
              .build());
    }
    CacheHandle secondCacheHandle = cache.lookup(secondSpawn, secondPolicy);

    // assert
    ActionInput inMemoryOutput = secondSpawn.getOutputFiles().getFirst();
    assertThat(secondCacheHandle.hasResult()).isTrue();
    assertThat(secondCacheHandle.getResult().getRunnerName()).isEqualTo("deduplicated");
    assertThat(secondCacheHandle.getResult().getInMemoryOutput(inMemoryOutput).toStringUtf8())
        .isEqualTo("in-memory");
    assertThat(execRoot.getRelative(inMemoryOutput.getExecPath()).exists()).isFalse();
    assertThat(secondCacheHandle.willStore()).isFalse();
  }

  @Test
  public void deduplicatedActionWithNonZeroExitCodeIsACacheMiss() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();

    SimpleSpawn firstSpawn = simplePathMappedSpawn("k8-fastbuild");
    FakeActionInputFileCache firstFakeFileCache = new FakeActionInputFileCache(execRoot);
    firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext firstPolicy =
        createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr);

    SimpleSpawn secondSpawn = simplePathMappedSpawn("k8-opt");
    FakeActionInputFileCache secondFakeFileCache = new FakeActionInputFileCache(execRoot);
    secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext secondPolicy =
        createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr);

    RemoteExecutionService remoteExecutionService = cache.getRemoteExecutionService();
    Mockito.doCallRealMethod().when(remoteExecutionService).waitForAndReuseOutputs(any(), any());
    // Simulate a very slow upload to the remote cache to ensure that the second spawn is
    // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
    // concurrency to this test.
    Mockito.doNothing().when(remoteExecutionService).uploadOutputs(any(), any(), any());

    // act
    try (CacheHandle firstCacheHandle = cache.lookup(firstSpawn, firstPolicy)) {
      Path outputFile = fs.getPath("/exec/root/bazel-bin/k8-fastbuild/bin/output");
      FileSystemUtils.writeContent(outputFile, "hello");
      firstCacheHandle.store(
          new SpawnResult.Builder()
              .setExitCode(1)
              .setStatus(Status.NON_ZERO_EXIT)
              .setFailureDetail(
                  FailureDetail.newBuilder()
                      .setMessage("test spawn failed")
                      .setSpawn(
                          FailureDetails.Spawn.newBuilder()
                              .setCode(FailureDetails.Spawn.Code.NON_ZERO_EXIT))
                      .build())
              .setRunnerName("test")
              .build());
    }
    CacheHandle secondCacheHandle = cache.lookup(secondSpawn, secondPolicy);

    // assert
    assertThat(secondCacheHandle.hasResult()).isFalse();
    assertThat(secondCacheHandle.willStore()).isTrue();
  }

  @Test
  public void deduplicatedActionWithMissingOutputIsACacheMiss() throws Exception {
    // arrange
    RemoteSpawnCache cache = createRemoteSpawnCache();

    SimpleSpawn firstSpawn = simplePathMappedSpawn("k8-fastbuild");
    FakeActionInputFileCache firstFakeFileCache = new FakeActionInputFileCache(execRoot);
    firstFakeFileCache.createScratchInput(firstSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext firstPolicy =
        createSpawnExecutionContext(firstSpawn, execRoot, firstFakeFileCache, outErr);

    SimpleSpawn secondSpawn = simplePathMappedSpawn("k8-opt");
    FakeActionInputFileCache secondFakeFileCache = new FakeActionInputFileCache(execRoot);
    secondFakeFileCache.createScratchInput(secondSpawn.getInputFiles().getSingleton(), "xyz");
    SpawnExecutionContext secondPolicy =
        createSpawnExecutionContext(secondSpawn, execRoot, secondFakeFileCache, outErr);

    RemoteExecutionService remoteExecutionService = cache.getRemoteExecutionService();
    Mockito.doCallRealMethod().when(remoteExecutionService).waitForAndReuseOutputs(any(), any());
    // Simulate a very slow upload to the remote cache to ensure that the second spawn is
    // deduplicated rather than a cache hit. This is a slight hack, but also avoid introducing
    // concurrency to this test.
    Mockito.doNothing().when(remoteExecutionService).uploadOutputs(any(), any(), any());

    // act
    try (CacheHandle firstCacheHandle = cache.lookup(firstSpawn, firstPolicy)) {
      // Do not create the output.
      firstCacheHandle.store(
          new SpawnResult.Builder()
              .setExitCode(0)
              .setStatus(Status.SUCCESS)
              .setRunnerName("test")
              .build());
    }
    CacheHandle secondCacheHandle = cache.lookup(secondSpawn, secondPolicy);

    // assert
    assertThat(secondCacheHandle.hasResult()).isFalse();
    assertThat(secondCacheHandle.willStore()).isTrue();
  }
}
