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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import build.bazel.remote.execution.v2.ExecutionCapabilities;
import build.bazel.remote.execution.v2.ExecutionStage.Value;
import build.bazel.remote.execution.v2.LogFile;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionOutputDirectoryHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.AbstractSpawnStrategy;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RemoteLocalFallbackRegistry;
import com.google.devtools.build.lib.exec.SpawnCheckingCacheEvent;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.SpawnSchedulingEvent;
import com.google.devtools.build.lib.exec.util.FakeOwner;
import com.google.devtools.build.lib.remote.CombinedCache.CachedActionResult;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.common.RemotePathResolver.SiblingRepositoryLayoutResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.FakeSpawnExecutionContext;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.TempPathGenerator;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link com.google.devtools.build.lib.remote.RemoteSpawnRunner} */
@RunWith(JUnit4.class)
public class RemoteSpawnRunnerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private RemoteOutputChecker remoteOutputChecker; // download nothing by default.

  private final Reporter reporter = new Reporter(new EventBus());
  private static final ImmutableMap<String, String> NO_CACHE =
      ImmutableMap.of(ExecutionRequirements.NO_CACHE, "");
  private ListeningScheduledExecutorService retryService;

  private FileSystem fs;
  private Path execRoot;
  private ArtifactRoot artifactRoot;
  private TempPathGenerator tempPathGenerator;
  private Path logDir;
  private DigestUtil digestUtil;
  private FakeActionInputFileCache fakeFileCache;
  private FileOutErr outErr;

  private RemoteOptions remoteOptions;

  @Mock private RemoteExecutionCache cache;

  @Mock private RemoteExecutionClient executor;

  @Mock private SpawnRunner localRunner;

  // The action key of the Spawn returned by newSimpleSpawn().
  private static final String SIMPLE_ACTION_ID =
      "31aea267dc597b047a9b6993100415b6406f82822318dc8988e4164a535b51ee";

  private final ServerCapabilities remoteExecutorCapabilities =
      ServerCapabilities.newBuilder()
          .setLowApiVersion(ApiVersion.low.toSemVer())
          .setHighApiVersion(ApiVersion.high.toSemVer())
          .setExecutionCapabilities(ExecutionCapabilities.newBuilder().setExecEnabled(true).build())
          .build();

  @Before
  public final void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/exec/root");
    execRoot.createDirectoryAndParents();
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "outputs");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(fs.getPath("/execroot/_tmp/actions/remote"));
    logDir = fs.getPath("/server-logs");
    fakeFileCache = new FakeActionInputFileCache(execRoot);

    Path stdout = fs.getPath("/tmp/stdout");
    Path stderr = fs.getPath("/tmp/stderr");
    stdout.getParentDirectory().createDirectoryAndParents();
    stderr.getParentDirectory().createDirectoryAndParents();
    outErr = new FileOutErr(stdout, stderr);

    remoteOptions = Options.getDefaults(RemoteOptions.class);

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
    when(cache.hasRemoteCache()).thenReturn(true);
    doReturn(remoteExecutorCapabilities).when(cache).getRemoteServerCapabilities();
    when(executor.getServerCapabilities()).thenReturn(remoteExecutorCapabilities);
    when(cache.remoteActionCacheSupportsUpdate()).thenReturn(true);
  }

  @After
  public void afterEverything() throws InterruptedException {
    retryService.shutdownNow();
    retryService.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void nonCachableSpawnsShouldNotBeCached_remote() throws Exception {
    // Test that if a spawn is marked "NO_CACHE" then it's not fetched from a remote cache.
    // It should be executed remotely, but marked non-cacheable to remote execution, so that
    // the action result is not saved in the remote cache.

    remoteOptions.remoteAcceptCached = true;
    remoteOptions.remoteLocalFallback = false;
    remoteOptions.remoteUploadLocalResults = true;
    remoteOptions.remoteResultCachePriority = 1;
    remoteOptions.remoteExecutionPriority = 2;

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);

    Spawn spawn = simpleSpawnWithExecutionInfo(NO_CACHE);
    SpawnExecutionContext policy = getSpawnContext(spawn);

    runner.exec(spawn, policy);

    ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            requestCaptor.capture(),
            any(OperationObserver.class));
    assertThat(requestCaptor.getValue().getSkipCacheLookup()).isTrue();
    assertThat(requestCaptor.getValue().getResultsCachePolicy().getPriority()).isEqualTo(1);
    assertThat(requestCaptor.getValue().getExecutionPolicy().getPriority()).isEqualTo(2);
    // TODO(olaola): verify that the uploaded action has the doNotCache set.

    verify(service, never()).lookupCache(any());
    verify(service, never()).uploadOutputs(any(), any(), any());
    verifyNoMoreInteractions(localRunner);
  }

  private FakeSpawnExecutionContext getSpawnContext(Spawn spawn) {
    AbstractSpawnStrategy fakeLocalStrategy =
        new AbstractSpawnStrategy(execRoot, localRunner, new ExecutionOptions()) {};
    ClassToInstanceMap<ActionContext> actionContextRegistry =
        ImmutableClassToInstanceMap.of(RemoteLocalFallbackRegistry.class, (_spawn) -> fakeLocalStrategy);

    var actionInputFetcher =
        new RemoteActionInputFetcher(
            new Reporter(new EventBus()),
            "none",
            "none",
            cache,
            execRoot,
            tempPathGenerator,
            remoteOutputChecker,
            ActionOutputDirectoryHelper.createForTesting(),
            OutputPermissions.READONLY);

    var actionFileSystem =
        new RemoteActionFileSystem(
            fs,
            execRoot.asFragment(),
            artifactRoot.getRoot().asPath().relativeTo(execRoot).getPathString(),
            new ActionInputMap(0),
            ImmutableList.of(),
            StaticInputMetadataProvider.empty(),
            actionInputFetcher);

    return new FakeSpawnExecutionContext(
        spawn, fakeFileCache, execRoot, outErr, actionContextRegistry, actionFileSystem);
  }

  @Test
  public void nonCachableSpawnsShouldNotBeCached_localFallback() throws Exception {
    // Test that if a non-cachable spawn is executed locally due to the local fallback,
    // that its result is not uploaded to the remote cache.

    remoteOptions.remoteAcceptCached = true;
    remoteOptions.remoteLocalFallback = true;
    remoteOptions.remoteUploadLocalResults = true;

    RemoteSpawnRunner runner = newSpawnRunner();

    // Throw an IOException to trigger the local fallback.
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(IOException.class);

    Spawn spawn = simpleSpawnWithExecutionInfo(NO_CACHE);
    SpawnExecutionContext policy = getSpawnContext(spawn);

    runner.exec(spawn, policy);

    verify(localRunner).exec(spawn, policy);
    verify(cache).getRemoteServerCapabilities();
    verify(cache).ensureInputsPresent(any(), any(), any(), anyBoolean(), any());
    verify(cache, atLeastOnce()).hasRemoteCache();
    verify(cache, atLeastOnce()).hasDiskCache();
    verifyNoMoreInteractions(cache);
  }

  @Test
  public void cachableSpawnsShouldBeCached_localFallback() throws Exception {
    // Test that if a cacheable spawn is executed locally due to the local fallback,
    // that its result is uploaded to the remote cache.

    remoteOptions.remoteAcceptCached = true;
    remoteOptions.remoteLocalFallback = true;
    remoteOptions.remoteUploadLocalResults = true;

    RemoteSpawnRunner runner = spy(newSpawnRunner());
    RemoteExecutionService service = runner.getRemoteExecutionService();
    doNothing().when(service).uploadOutputs(any(), any(), any());

    // Throw an IOException to trigger the local fallback.
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(IOException.class);

    SpawnResult res =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setExitCode(0)
            .setRunnerName("test")
            .build();
    when(localRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(res);

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.status()).isEqualTo(Status.SUCCESS);
    verify(localRunner).exec(eq(spawn), eq(policy));
    verify(runner)
        .execLocallyAndUpload(any(), eq(spawn), eq(policy), /* uploadLocalResults= */ eq(true));
    verify(service).uploadOutputs(any(), eq(res), any());
  }

  @Test
  public void failedLocalActionShouldNotBeUploaded() throws Exception {
    // Test that the outputs of a locally executed action that failed are not uploaded.

    remoteOptions.remoteLocalFallback = true;
    remoteOptions.remoteUploadLocalResults = true;

    RemoteSpawnRunner runner = spy(newSpawnRunner());
    RemoteExecutionService service = runner.getRemoteExecutionService();

    // Throw an IOException to trigger the local fallback.
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(IOException.class);

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = Mockito.mock(SpawnResult.class);
    when(res.exitCode()).thenReturn(1);
    when(res.status()).thenReturn(Status.EXECUTION_FAILED);
    when(localRunner.exec(eq(spawn), eq(policy))).thenReturn(res);

    assertThat(runner.exec(spawn, policy)).isSameInstanceAs(res);

    verify(localRunner).exec(eq(spawn), eq(policy));
    verify(runner)
        .execLocallyAndUpload(any(), eq(spawn), eq(policy), /* uploadLocalResults= */ eq(true));
    verify(service, never()).uploadOutputs(any(), any(), any());
  }

  @Test
  public void treatFailedCachedActionAsCacheMiss_local() throws Exception {
    // Test that bazel treats failed cache action as a cache miss and attempts to execute action
    // locally

    remoteOptions.remoteLocalFallback = true;
    remoteOptions.remoteUploadLocalResults = true;

    CachedActionResult failedAction =
        CachedActionResult.remote(ActionResult.newBuilder().setExitCode(1).build());
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(failedAction);

    RemoteSpawnRunner runner = spy(newSpawnRunner());
    RemoteExecutionService service = runner.getRemoteExecutionService();
    // Throw an IOException to trigger the local fallback.
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(IOException.class);
    doNothing().when(service).uploadOutputs(any(), any(), any());

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult succeeded =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setExitCode(0)
            .setRunnerName("test")
            .build();
    when(localRunner.exec(eq(spawn), eq(policy))).thenReturn(succeeded);

    SpawnResult result = runner.exec(spawn, policy);

    verify(localRunner).exec(eq(spawn), eq(policy));
    verify(runner)
        .execLocallyAndUpload(any(), eq(spawn), eq(policy), /* uploadLocalResults= */ eq(true));
    verify(service).uploadOutputs(any(), eq(result), any());
    verify(service, never()).downloadOutputs(any(), any());
  }

  @Test
  public void treatFailedCachedActionAsCacheMiss_remote() throws Exception {
    // Test that bazel treats failed cache action as a cache miss and attempts to execute action
    // remotely

    CachedActionResult failedAction =
        CachedActionResult.remote(ActionResult.newBuilder().setExitCode(1).build());
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(failedAction);

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);
    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    runner.exec(spawn, policy);

    verify(service).executeRemotely(any(), eq(false), any());
  }

  @Test
  public void noRemoteExecutorFallbackFails() throws Exception {
    // Errors from the fallback runner should be propagated out of the remote runner.

    remoteOptions.remoteUploadLocalResults = true;
    remoteOptions.remoteLocalFallback = true;

    RemoteSpawnRunner runner = newSpawnRunner();
    // Trigger local fallback
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException());

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);

    IOException err = new IOException("local execution error");
    when(localRunner.exec(eq(spawn), eq(policy))).thenThrow(err);

    IOException e = assertThrows(IOException.class, () -> runner.exec(spawn, policy));
    assertThat(e).isSameInstanceAs(err);

    verify(localRunner).exec(eq(spawn), eq(policy));
  }

  @Test
  public void remoteCacheErrorFallbackFails() throws Exception {
    // Errors from the fallback runner should be propagated out of the remote runner.

    remoteOptions.remoteUploadLocalResults = true;
    remoteOptions.remoteLocalFallback = true;

    RemoteSpawnRunner runner = newSpawnRunner();
    // Trigger local fallback
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException());

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenThrow(new IOException());

    IOException err = new IOException("local execution error");
    when(localRunner.exec(eq(spawn), eq(policy))).thenThrow(err);

    IOException e = assertThrows(IOException.class, () -> runner.exec(spawn, policy));
    assertThat(e).isSameInstanceAs(err);

    verify(localRunner).exec(eq(spawn), eq(policy));
  }

  @Test
  public void testLocalFallbackFailureRemoteExecutorFailure() throws Exception {
    remoteOptions.remoteLocalFallback = true;

    RemoteSpawnRunner runner = newSpawnRunner();

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException());

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    IOException err = new IOException("local execution error");
    when(localRunner.exec(eq(spawn), eq(policy))).thenThrow(err);

    IOException e = assertThrows(IOException.class, () -> runner.exec(spawn, policy));
    assertThat(e).isSameInstanceAs(err);

    verify(localRunner).exec(eq(spawn), eq(policy));
  }

  @Test
  public void testHumanReadableServerLogsSavedForFailingAction() throws Exception {
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    Digest logDigest = digestUtil.computeAsUtf8("bla");
    Path logPath = logDir.getRelative(SIMPLE_ACTION_ID).getRelative("logname");
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .putServerLogs(
                "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build())
            .setResult(ActionResult.newBuilder().setExitCode(31).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(resp);
    SettableFuture<Void> completed = SettableFuture.create();
    completed.set(null);
    when(cache.downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest)))
        .thenReturn(completed);

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).maybeDownloadServerLogs(any(), eq(resp), eq(logDir));
    verify(cache).downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest));
  }

  @Test
  public void testHumanReadableServerLogsSavedForFailingActionWithSiblingRepositoryLayout()
      throws Exception {
    RemoteSpawnRunner runner = newSpawnRunner(new SiblingRepositoryLayoutResolver(execRoot));
    RemoteExecutionService service = runner.getRemoteExecutionService();
    Digest logDigest = digestUtil.computeAsUtf8("bla");
    Path logPath =
        logDir
            .getRelative("e0a5a3561464123504c1240b3587779cdfd6adee20f72aa136e388ecfd570c12")
            .getRelative("logname");
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .putServerLogs(
                "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build())
            .setResult(ActionResult.newBuilder().setExitCode(31).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(resp);
    SettableFuture<Void> completed = SettableFuture.create();
    completed.set(null);
    when(cache.downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest)))
        .thenReturn(completed);

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).maybeDownloadServerLogs(any(), eq(resp), eq(logDir));
    verify(cache).downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest));
  }

  @Test
  public void testHumanReadableServerLogsSavedForFailingActionWithStatus() throws Exception {
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    Digest logDigest = digestUtil.computeAsUtf8("bla");
    Path logPath = logDir.getRelative(SIMPLE_ACTION_ID).getRelative("logname");
    com.google.rpc.Status timeoutStatus =
        com.google.rpc.Status.newBuilder().setCode(Code.DEADLINE_EXCEEDED.getNumber()).build();
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .putServerLogs(
                "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build())
            .setStatus(timeoutStatus)
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException(new ExecutionStatusException(resp.getStatus(), resp)));
    SettableFuture<Void> completed = SettableFuture.create();
    completed.set(null);
    when(cache.downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest)))
        .thenReturn(completed);

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.TIMEOUT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).maybeDownloadServerLogs(any(), eq(resp), eq(logDir));
    verify(cache).downloadFile(any(RemoteActionExecutionContext.class), eq(logPath), eq(logDigest));
  }

  @Test
  public void testNonHumanReadableServerLogsNotSaved() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    Digest logDigest = digestUtil.computeAsUtf8("bla");
    ActionResult result = ActionResult.newBuilder().setExitCode(31).build();
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .putServerLogs("logname", LogFile.newBuilder().setDigest(logDigest).build())
            .setResult(result)
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(resp);

    Spawn spawn = newSimpleSpawn();
    FakeSpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult res = runner.exec(spawn, policy);

    // asset
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).maybeDownloadServerLogs(any(), eq(resp), eq(logDir));
    verify(cache, never())
        .downloadFile(any(RemoteActionExecutionContext.class), any(Path.class), any(Digest.class));
  }

  @Test
  public void testServerLogsNotSavedForSuccessfulAction() throws Exception {
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    Digest logDigest = digestUtil.computeAsUtf8("bla");
    ActionResult result = ActionResult.newBuilder().setExitCode(0).build();
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .putServerLogs(
                "logname", LogFile.newBuilder().setHumanReadable(true).setDigest(logDigest).build())
            .setResult(result)
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(resp);

    Spawn spawn = newSimpleSpawn();
    FakeSpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(resp)));
    verify(service).maybeDownloadServerLogs(any(), eq(resp), eq(logDir));
    verify(cache, never())
        .downloadFile(any(RemoteActionExecutionContext.class), any(Path.class), any(Digest.class));
  }

  @Test
  public void cacheDownloadFailureTriggersRemoteExecution() throws Exception {
    // If downloading a cached action fails, remote execution should be tried.

    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    CachedActionResult cachedResult =
        CachedActionResult.remote(ActionResult.newBuilder().setExitCode(0).build());
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(cachedResult);
    Exception downloadFailure =
        new BulkTransferException(new CacheNotFoundException(Digest.getDefaultInstance()));
    doThrow(downloadFailure)
        .when(service)
        .downloadOutputs(any(), eq(RemoteActionResult.createFromCache(cachedResult)));
    ActionResult execResult = ActionResult.newBuilder().setExitCode(31).build();
    ExecuteResponse succeeded = ExecuteResponse.newBuilder().setResult(execResult).build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);
    doReturn(null)
        .when(service)
        .downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(succeeded)));

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult res = runner.exec(spawn, policy);

    // assert
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);
    assertThat(res.exitCode()).isEqualTo(31);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
  }

  @Test
  public void resultsDownloadFailureTriggersRemoteExecutionWithSkipCacheLookup() throws Exception {
    // If downloading an action result fails, remote execution should be retried
    // with skip cache lookup enabled

    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    ActionResult cachedResult = ActionResult.newBuilder().setExitCode(0).build();
    ActionResult execResult = ActionResult.newBuilder().setExitCode(31).build();
    ExecuteResponse cachedResponse =
        ExecuteResponse.newBuilder().setResult(cachedResult).setCachedResult(true).build();
    ExecuteResponse executedResponse = ExecuteResponse.newBuilder().setResult(execResult).build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(cachedResponse)
        .thenReturn(executedResponse);
    Exception downloadFailure =
        new BulkTransferException(new CacheNotFoundException(Digest.getDefaultInstance()));
    doThrow(downloadFailure)
        .when(service)
        .downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(cachedResponse)));
    doReturn(null)
        .when(service)
        .downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(executedResponse)));

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult res = runner.exec(spawn, policy);

    // assert
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);
    assertThat(res.exitCode()).isEqualTo(31);

    ArgumentCaptor<ExecuteRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteRequest.class);
    verify(executor, times(2))
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            requestCaptor.capture(),
            any(OperationObserver.class));
    List<ExecuteRequest> requests = requestCaptor.getAllValues();
    // first request should have been executed without skip cache lookup
    assertThat(requests.get(0).getSkipCacheLookup()).isFalse();
    // second should have been executed with skip cache lookup
    assertThat(requests.get(1).getSkipCacheLookup()).isTrue();
  }

  @Test
  public void testRemoteExecutionTimeout() throws Exception {
    // If remote execution times out the SpawnResult status should be TIMEOUT.

    remoteOptions.remoteLocalFallback = false;

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    ActionResult cachedResult = ActionResult.newBuilder().setExitCode(0).build();
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .setResult(cachedResult)
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                    .build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException(new ExecutionStatusException(resp.getStatus(), resp)));

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.TIMEOUT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(resp)));
  }

  @Test
  public void testRemoteExecutionTimeoutTimings() throws Exception {
    // If remote execution times out the SpawnResult should still have the start and wall times
    // reported correctly.

    remoteOptions.remoteLocalFallback = false;

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    com.google.protobuf.Duration oneSecond = Durations.fromMillis(1000);
    Timestamp executionStart = Timestamp.getDefaultInstance();
    Timestamp executionCompleted = Timestamps.add(executionStart, oneSecond);
    ExecutedActionMetadata executedMetadata =
        ExecutedActionMetadata.newBuilder()
            .setExecutionStartTimestamp(executionStart)
            .setExecutionCompletedTimestamp(executionCompleted)
            .build();

    ActionResult cachedResult =
        ActionResult.newBuilder().setExitCode(0).setExecutionMetadata(executedMetadata).build();
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .setResult(cachedResult)
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                    .build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException(new ExecutionStatusException(resp.getStatus(), resp)));

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.TIMEOUT);
    assertThat(res.getWallTimeInMs()).isEqualTo(1000);
    assertThat(res.getStartTime())
        .isEqualTo(Instant.ofEpochSecond(executionStart.getSeconds(), executionStart.getNanos()));

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(resp)));
  }

  @Test
  public void testRemoteExecutionTimeoutDoesNotTriggerFallback() throws Exception {
    // If remote execution times out the SpawnResult status should be TIMEOUT, regardess of local
    // fallback option.

    remoteOptions.remoteLocalFallback = true;

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    ActionResult cachedResult = ActionResult.newBuilder().setExitCode(0).build();
    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .setResult(cachedResult)
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(Code.DEADLINE_EXCEEDED.getNumber())
                    .build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException(new ExecutionStatusException(resp.getStatus(), resp)));

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.TIMEOUT);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(resp)));
    verify(localRunner, never()).exec(eq(spawn), eq(policy));
  }

  @Test
  public void testRemoteExecutionCommandFailureDoesNotTriggerFallback() throws Exception {
    remoteOptions.remoteLocalFallback = true;

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    ExecuteResponse failed =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(33).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(failed);

    Spawn spawn = newSimpleSpawn();

    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.NON_ZERO_EXIT);
    assertThat(res.exitCode()).isEqualTo(33);

    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(failed)));
    verify(localRunner, never()).exec(eq(spawn), eq(policy));
  }

  @Test
  public void testExitCode_executorfailure() throws Exception {
    // If we get a failure due to the remote cache not working, the exit code should be
    // ExitCode.REMOTE_ERROR.

    remoteOptions.remoteLocalFallback = false;

    RemoteSpawnRunner runner = newSpawnRunner();

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException("reasons"));

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.getNumericExitCode());
    assertThat(result.getFailureMessage()).contains("reasons");
  }

  @Test
  public void testExitCode_executionfailure() throws Exception {
    // If we get a failure due to the remote executor not working, the exit code should be
    // ExitCode.REMOTE_ERROR.

    remoteOptions.remoteLocalFallback = false;

    RemoteSpawnRunner runner = newSpawnRunner();

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenThrow(new IOException("reasons"));

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.getNumericExitCode());
    assertThat(result.getFailureMessage()).contains("reasons");
  }

  @Test
  public void testExitCode_remoteMessage() throws Exception {
    remoteOptions.remoteLocalFallback = false;

    RemoteSpawnRunner runner = newSpawnRunner();

    ExecutionStatusException cause =
        new ExecutionStatusException(
            com.google.rpc.Status.getDefaultInstance(),
            ExecuteResponse.newBuilder().setMessage("beep and indeed boop").build());

    when(cache.downloadActionResult(
            any(RemoteActionExecutionContext.class),
            any(ActionKey.class),
            /* inlineOutErr= */ eq(false),
            /* inlineOutputFiles= */ eq(ImmutableSet.of())))
        .thenReturn(null);
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenThrow(new IOException("reasons", cause));

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(ExitCode.REMOTE_ERROR.getNumericExitCode());
    assertThat(result.getFailureMessage()).contains("beep and indeed boop");
  }

  @Test
  public void testMaterializeParamFiles() throws Exception {
    testParamFilesAreMaterializedForFlag("--materialize_param_files");
  }

  @Test
  public void testMaterializeParamFilesIsImpliedBySubcommands() throws Exception {
    testParamFilesAreMaterializedForFlag("--subcommands");
  }

  private void testParamFilesAreMaterializedForFlag(String flag) throws Exception {
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    ExecutionOptions executionOptions = Options.parse(ExecutionOptions.class, flag).getOptions();
    executionOptions.materializeParamFiles = true;
    RemoteExecutionService remoteExecutionService =
        new RemoteExecutionService(
            directExecutor(),
            reporter,
            /* verboseFailures= */ true,
            execRoot,
            RemotePathResolver.createDefault(execRoot),
            "build-req-id",
            "command-id",
            digestUtil,
            remoteOptions,
            cache,
            executor,
            tempPathGenerator,
            /* captureCorruptedOutputsDir= */ null,
            remoteOutputChecker,
            mock(OutputService.class),
            Sets.newConcurrentHashSet());
    RemoteSpawnRunner runner =
        new RemoteSpawnRunner(
            execRoot,
            remoteOptions,
            executionOptions,
            /* verboseFailures= */ true,
            /* cmdlineReporter= */ null,
            retryService,
            logDir,
            remoteExecutionService,
            digestUtil);

    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);

    ImmutableList<String> args = ImmutableList.of("--foo", "--bar");
    ParamFileActionInput input =
        new ParamFileActionInput(
            PathFragment.create("out/param_file"), args, ParameterFileType.UNQUOTED, ISO_8859_1);
    Spawn spawn =
        new SimpleSpawn(
            new FakeOwner("foo", "bar", "//dummy:label"),
            /* arguments= */ ImmutableList.of(),
            /* environment= */ ImmutableMap.of(),
            /* executionInfo= */ ImmutableMap.of(),
            /* inputs= */ NestedSetBuilder.create(Order.STABLE_ORDER, input),
            /* outputs= */ ImmutableSet.<ActionInput>of(),
            ResourceSet.ZERO);
    SpawnExecutionContext policy = getSpawnContext(spawn);
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);
    Path paramFile = execRoot.getRelative("out/param_file");
    assertThat(paramFile.exists()).isTrue();
    try (InputStream inputStream = paramFile.getInputStream()) {
      assertThat(
              new String(ByteStreams.toByteArray(inputStream), StandardCharsets.UTF_8).split("\n"))
          .asList()
          .containsExactly("--foo", "--bar");
    }
  }

  @Test
  public void testDownloadMinimalOnCacheHit() throws Exception {
    // arrange
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;

    ActionResult succeededAction = ActionResult.newBuilder().setExitCode(0).build();
    RemoteActionResult actionResult =
        RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction));

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    doReturn(actionResult).when(service).lookupCache(any());
    doReturn(null).when(service).downloadOutputs(any(), any());

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(service).downloadOutputs(any(), eq(actionResult));
  }

  @Test
  public void testDownloadMinimalOnCacheMiss() throws Exception {
    // arrange
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;

    ActionResult succeededAction = ActionResult.newBuilder().setExitCode(0).build();
    ExecuteResponse succeeded = ExecuteResponse.newBuilder().setResult(succeededAction).build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    doReturn(null).when(service).downloadOutputs(any(), any());

    Spawn spawn = newSimpleSpawn();
    FakeSpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class), any(), any(OperationObserver.class));
    verify(service).downloadOutputs(any(), eq(RemoteActionResult.createFromResponse(succeeded)));
  }

  @Test
  public void testDownloadMinimalIoError() throws Exception {
    // arrange
    remoteOptions.remoteOutputsMode = RemoteOutputsMode.MINIMAL;

    ActionResult succeededAction = ActionResult.newBuilder().setExitCode(0).build();
    RemoteActionResult cachedActionResult =
        RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction));
    IOException downloadFailure = new IOException("downloadMinimal failed");

    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    doReturn(RemoteActionResult.createFromCache(CachedActionResult.remote(succeededAction)))
        .when(service)
        .lookupCache(any());
    doThrow(downloadFailure).when(service).downloadOutputs(any(), eq(cachedActionResult));

    Spawn spawn = newSimpleSpawn();
    FakeSpawnExecutionContext policy = getSpawnContext(spawn);

    // act
    SpawnResult result = runner.exec(spawn, policy);
    assertThat(result.getFailureMessage()).isEqualTo(downloadFailure.getMessage());

    // assert
    verify(service).downloadOutputs(any(), eq(cachedActionResult));
  }

  @Test
  public void testDigest() throws Exception {
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();

    ExecuteResponse resp =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();
    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(resp);

    Spawn spawn = newSimpleSpawn();
    FakeSpawnExecutionContext policy = getSpawnContext(spawn);

    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    ArgumentCaptor<RemoteAction> requestCaptor = ArgumentCaptor.forClass(RemoteAction.class);

    verify(service)
        .executeRemotely(requestCaptor.capture(), anyBoolean(), any(OperationObserver.class));

    assertThat(policy.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(requestCaptor.getValue().getActionKey()));

    assertThat(res.getDigest())
        .isEqualTo(digestUtil.asSpawnLogProto(requestCaptor.getValue().getActionKey()));
  }

  @Test
  public void accountingDisabledWithoutWorker() {
    SpawnMetrics.Builder spawnMetrics = Mockito.mock(SpawnMetrics.Builder.class);
    RemoteSpawnRunner.spawnMetricsAccounting(
        spawnMetrics, ExecutedActionMetadata.getDefaultInstance());
    verifyNoMoreInteractions(spawnMetrics);
  }

  @Test
  public void accountingAddsDurationsForStages() {
    SpawnMetrics.Builder builder =
        SpawnMetrics.Builder.forRemoteExec()
            .setQueueTimeInMs(1 * 1000)
            .setSetupTimeInMs(2 * 1000)
            .setExecutionWallTimeInMs(2 * 1000)
            .setProcessOutputsTimeInMs(2 * 1000);
    Timestamp queued = Timestamp.getDefaultInstance();
    com.google.protobuf.Duration oneSecond = Durations.fromMillis(1000);
    Timestamp workerStart = Timestamps.add(queued, oneSecond);
    Timestamp executionStart = Timestamps.add(workerStart, oneSecond);
    Timestamp executionCompleted = Timestamps.add(executionStart, oneSecond);
    Timestamp outputUploadStart = Timestamps.add(executionCompleted, oneSecond);
    Timestamp outputUploadComplete = Timestamps.add(outputUploadStart, oneSecond);
    ExecutedActionMetadata executedMetadata =
        ExecutedActionMetadata.newBuilder()
            .setWorker("test worker")
            .setQueuedTimestamp(queued)
            .setWorkerStartTimestamp(workerStart)
            .setExecutionStartTimestamp(executionStart)
            .setExecutionCompletedTimestamp(executionCompleted)
            .setOutputUploadStartTimestamp(outputUploadStart)
            .setOutputUploadCompletedTimestamp(outputUploadComplete)
            .build();
    RemoteSpawnRunner.spawnMetricsAccounting(builder, executedMetadata);
    SpawnMetrics spawnMetrics = builder.build();
    // remote queue time is accumulated
    assertThat(spawnMetrics.queueTimeInMs()).isEqualTo(2 * 1000L);
    // setup time is substituted
    assertThat(spawnMetrics.setupTimeInMs()).isEqualTo(1 * 1000L);
    // execution time is unspecified, assume substituted
    assertThat(spawnMetrics.executionWallTimeInMs()).isEqualTo(1 * 1000L);
    // ProcessOutputs time is unspecified, assume substituted
    assertThat(spawnMetrics.processOutputsTimeInMs()).isEqualTo(1 * 1000L);
  }

  @Test
  public void shouldReportCheckingCacheBeforeScheduling() throws Exception {
    // Prepare a faked/mocked remote SpawnExecutionContext.
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenAnswer(
            invocationOnMock -> {
              OperationObserver receiver = invocationOnMock.getArgument(2);
              receiver.onNext(Operation.getDefaultInstance());
              return succeeded;
            });

    doReturn(null).when(service).downloadOutputs(any(), any());

    // Run the faked spawn.
    SpawnResult res = runner.exec(spawn, policy);

    // Verify expected behavior with mocked remote SpawnExecutionContext.
    assertThat(res.status()).isEqualTo(Status.SUCCESS);
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnCheckingCacheEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  @Test
  public void shouldReportExecutingStatusWithoutMetadata() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenAnswer(
            invocationOnMock -> {
              OperationObserver receiver = invocationOnMock.getArgument(2);
              verify(policy, never()).report(SpawnExecutingEvent.create("remote"));
              receiver.onNext(Operation.getDefaultInstance());
              return succeeded;
            });

    doReturn(null).when(service).downloadOutputs(any(), any());

    // act
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  @Test
  public void shouldReportExecutingStatusAfterGotExecutingStageFromMetadata() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenAnswer(
            invocationOnMock -> {
              OperationObserver receiver = invocationOnMock.getArgument(2);
              Operation queued =
                  Operation.newBuilder()
                      .setMetadata(
                          Any.pack(
                              ExecuteOperationMetadata.newBuilder().setStage(Value.QUEUED).build()))
                      .build();
              receiver.onNext(queued);
              verify(policy, never()).report(SpawnExecutingEvent.create("remote"));

              Operation executing =
                  Operation.newBuilder()
                      .setMetadata(
                          Any.pack(
                              ExecuteOperationMetadata.newBuilder()
                                  .setStage(Value.EXECUTING)
                                  .build()))
                      .build();
              receiver.onNext(executing);

              return succeeded;
            });

    doReturn(null).when(service).downloadOutputs(any(), any());

    // act
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  @Test
  public void shouldIgnoreInvalidMetadata() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenAnswer(
            invocationOnMock -> {
              OperationObserver receiver = invocationOnMock.getArgument(2);
              Operation operation =
                  Operation.newBuilder()
                      .setMetadata(
                          // Anything that is not ExecutionOperationMetadata
                          Any.pack(Operation.getDefaultInstance()))
                      .build();
              receiver.onNext(operation);
              return succeeded;
            });

    doReturn(null).when(service).downloadOutputs(any(), any());

    // act
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  @Test
  public void shouldReportExecutingStatusIfNoExecutingStatusFromMetadata() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenAnswer(
            invocationOnMock -> {
              OperationObserver receiver = invocationOnMock.getArgument(2);
              Operation completed =
                  Operation.newBuilder()
                      .setMetadata(
                          Any.pack(
                              ExecuteOperationMetadata.newBuilder()
                                  .setStage(Value.COMPLETED)
                                  .build()))
                      .build();
              receiver.onNext(completed);
              return succeeded;
            });
    doReturn(null).when(service).downloadOutputs(any(), any());

    // act
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  @Test
  public void shouldReportExecutingStatusEvenNoOperationFromServer() throws Exception {
    // arrange
    RemoteSpawnRunner runner = newSpawnRunner();
    RemoteExecutionService service = runner.getRemoteExecutionService();
    ExecuteResponse succeeded =
        ExecuteResponse.newBuilder()
            .setResult(ActionResult.newBuilder().setExitCode(0).build())
            .build();

    Spawn spawn = newSimpleSpawn();
    SpawnExecutionContext policy = mock(SpawnExecutionContext.class);
    when(policy.getTimeout()).thenReturn(Duration.ZERO);

    when(executor.executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class)))
        .thenReturn(succeeded);
    doReturn(null).when(service).downloadOutputs(any(), any());

    // act
    SpawnResult res = runner.exec(spawn, policy);
    assertThat(res.status()).isEqualTo(Status.SUCCESS);

    // assert
    verify(executor)
        .executeRemotely(
            any(RemoteActionExecutionContext.class),
            any(ExecuteRequest.class),
            any(OperationObserver.class));
    InOrder reportOrder = inOrder(policy);
    reportOrder.verify(policy, times(1)).report(SpawnSchedulingEvent.create("remote"));
    reportOrder.verify(policy, times(1)).report(SpawnExecutingEvent.create("remote"));
  }

  private static Spawn newSimpleSpawn(Artifact... outputs) {
    return simpleSpawnWithExecutionInfo(ImmutableMap.of(), outputs);
  }

  private static SimpleSpawn simpleSpawnWithExecutionInfo(
      ImmutableMap<String, String> executionInfo, Artifact... outputs) {
    return new SimpleSpawn(
        new FakeOwner("foo", "bar", "//dummy:label"),
        /* arguments= */ ImmutableList.of(),
        /* environment= */ ImmutableMap.of(),
        /* executionInfo= */ executionInfo,
        /* inputs= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /* outputs= */ ImmutableSet.copyOf(outputs),
        ResourceSet.ZERO);
  }

  private RemoteSpawnRunner newSpawnRunner() {
    return newSpawnRunner(executor, RemotePathResolver.createDefault(execRoot));
  }

  private RemoteSpawnRunner newSpawnRunner(RemotePathResolver remotePathResolver) {
    return newSpawnRunner(executor, remotePathResolver);
  }

  private RemoteSpawnRunner newSpawnRunner(
      @Nullable RemoteExecutionClient executor, RemotePathResolver remotePathResolver) {
    RemoteExecutionService service =
        spy(
            new RemoteExecutionService(
                directExecutor(),
                reporter,
                /* verboseFailures= */ true,
                execRoot,
                remotePathResolver,
                "build-req-id",
                "command-id",
                digestUtil,
                remoteOptions,
                cache,
                executor,
                tempPathGenerator,
                /* captureCorruptedOutputsDir= */ null,
                remoteOutputChecker,
                mock(OutputService.class),
                Sets.newConcurrentHashSet()));

    return new RemoteSpawnRunner(
        execRoot,
        remoteOptions,
        Options.getDefaults(ExecutionOptions.class),
        /* verboseFailures= */ false,
        reporter,
        retryService,
        logDir,
        service,
        digestUtil);
  }
}
