// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy.StopConcurrentSpawns;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestFileOutErr;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link DynamicSpawnStrategy}. */
@RunWith(JUnit4.class)
public class DynamicSpawnStrategyUnitTest {

  private static final SpawnResult SUCCESSFUL_SPAWN_RESULT =
      new SpawnResult.Builder().setRunnerName("test").setStatus(Status.SUCCESS).build();
  private static final SpawnResult SUCCESSFUL_LOCAL_SPAWN_RESULT =
      new SpawnResult.Builder().setRunnerName("local").setStatus(Status.SUCCESS).build();
  private static final SpawnResult SUCCESSFUL_REMOTE_SPAWN_RESULT =
      new SpawnResult.Builder().setRunnerName("remote").setStatus(Status.SUCCESS).build();
  private static final FailureDetail FAILURE_DETAIL =
      FailureDetail.newBuilder().setExecution(Execution.getDefaultInstance()).build();

  private ExecutorService executorServiceForCleanup;

  private Function<Spawn, Optional<Spawn>> mockGetPostProcessingSpawn;
  private ExtendedEventHandler reporter;

  private Scratch scratch;
  private Path execDir;
  private ArtifactRoot rootDir;
  private Artifact output1;
  private Artifact output2;
  private final List<DynamicExecutionFinishedEvent> events = new ArrayList<>();

  @Before
  @SuppressWarnings("unchecked")
  public void initMocks() throws IOException {
    scratch = new Scratch();
    execDir = scratch.dir("/base/exec");
    rootDir = ArtifactRoot.asDerivedRoot(execDir, RootType.Output, "root");
    output1 =
        Artifact.DerivedArtifact.create(
            rootDir,
            rootDir.getExecPath().getRelative("dir/output1.txt"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER);
    output2 =
        Artifact.DerivedArtifact.create(
            rootDir,
            rootDir.getExecPath().getRelative("dir/output2.txt"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER);
    reporter = mock(ExtendedEventHandler.class);
    events.clear();
    doAnswer(
            (Answer<Void>)
                inv -> {
                  Object event = inv.getArgument(0);
                  if (event instanceof DynamicExecutionFinishedEvent newEvent) {
                    events.add(newEvent);
                  }
                  return null;
                })
        .when(reporter)
        .post(any());
    mockGetPostProcessingSpawn = mock(Function.class);
  }

  @After
  public void stopExecutorService() throws InterruptedException {
    if (executorServiceForCleanup != null) {
      executorServiceForCleanup.shutdown();
      assertThat(
              executorServiceForCleanup.awaitTermination(
                  TestUtils.WAIT_TIMEOUT_MILLISECONDS, MILLISECONDS))
          .isTrue();
    }
  }

  @Test
  public void exec_remoteOnlySpawn_doesNotExecLocalPostProcessingSpawn() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.empty());
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> remoteSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(remote.exec(remoteSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
    verify(local, never()).exec(any(), any(), any());
    assertThat(remoteSpawnCaptor.getAllValues()).containsExactly(spawn);
  }

  @Test
  public void exec_remoteOnlySpawn_noneCanExec_fails() throws Exception {
    Spawn spawn =
        new SpawnBuilder().withMnemonic("TheThing").withOwnerPrimaryOutput(output1).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.REMOTE_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.empty());
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Spawn is not executable in local: No usable dynamic_remote_strategy found (and local"
                + " execution disabled) for action TheThing. ");
    assertThat(thrown).hasMessageThat().doesNotContain("dynamic_local_strategy");
    verifyNoInteractions(local);
  }

  @Test
  public void exec_localOnlySpawn_runsLocalPostProcessingSpawn() throws Exception {
    Spawn spawn = new SpawnBuilder("command").withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
    verifyNoInteractions(remote);
    assertThat(localSpawnCaptor.getAllValues())
        .containsExactly(spawn, postProcessingSpawn)
        .inOrder();
  }

  @Test
  public void exec_localOnlySpawn_noneCanExec_fails() throws Exception {
    Spawn spawn =
        new SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Spawn is not executable in local: No usable dynamic_local_strategy found (and remote"
                + " execution disabled) for action ThisMnemonic1. Post-Processing Spawn is not"
                + " executable in local: No usable dynamic_local_strategy found (and remote"
                + " execution disabled) for action ThatMnemonic2. ");
    assertThat(thrown).hasMessageThat().doesNotContain("dynamic_remote_strategy");
    verifyNoInteractions(remote);
  }

  @Test
  public void exec_localOnlySpawnWithNonExecutablePostProcessingSpawn_doesNotExecLocalSpawn()
      throws Exception {
    Spawn spawn =
        new SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));

    SandboxedSpawnStrategy local = createMockSpawnStrategy();

    ActionExecutionContext actionExecutionContext = mock(ActionExecutionContext.class);
    when(actionExecutionContext.getFileOutErr()).thenReturn(new TestFileOutErr());
    when(actionExecutionContext.getContext(DynamicStrategyRegistry.class))
        .thenReturn(
            new DynamicStrategyRegistry() {
              @Override
              public ImmutableList<SandboxedSpawnStrategy> getDynamicSpawnActionContexts(
                  Spawn spawn, DynamicMode dynamicMode) {
                if (spawn.getMnemonic().equals("ThisMnemonic1")) {
                  return ImmutableList.of(local);
                }
                return ImmutableList.of();
              }

              @Override
              public void notifyUsedDynamic(ActionContextRegistry actionContextRegistry) {}
            });
    when(actionExecutionContext.withFileOutErr(any())).thenReturn(actionExecutionContext);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Post-Processing Spawn is not executable in local: No usable dynamic_local_strategy"
                + " found (and remote execution disabled) for action ThatMnemonic2. ");
    assertThat(thrown).hasMessageThat().doesNotContain("dynamic_remote_strategy");
  }

  @Test
  public void exec_failedLocalSpawn_doesNotExecLocalPostProcessingSpawn() throws Exception {
    testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
        new SpawnResult.Builder()
            .setRunnerName("test")
            .setStatus(Status.TIMEOUT)
            .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
            .setFailureDetail(FAILURE_DETAIL)
            .build());
  }

  @Test
  public void exec_nonZeroExitCodeLocalSpawn_doesNotExecLocalPostProcessingSpawn()
      throws Exception {
    testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(
        new SpawnResult.Builder()
            .setRunnerName("test")
            .setStatus(Status.EXECUTION_FAILED)
            .setExitCode(123)
            .setFailureDetail(FAILURE_DETAIL)
            .build());
  }

  private void testExecFailedLocalSpawnDoesNotExecLocalPostProcessingSpawn(SpawnResult failedResult)
      throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn = createMockSpawn();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(
            ExecutionPolicy.LOCAL_EXECUTION_ONLY, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenReturn(ImmutableList.of(failedResult));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(failedResult);
    assertThat(localSpawnCaptor.getAllValues()).containsExactly(spawn);
    verify(remote, never()).exec(any(), any(), any());
    verifyNoInteractions(postProcessingSpawn);
  }

  @Test
  public void exec_runAnywhereSpawn_runsLocalPostProcessingSpawn() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /* stopConcurrentSpawns= */ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    when(local.exec(eq(postProcessingSpawn), any(), /* stopConcurrentSpawns= */ isNull()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
              throw new AssertionError("Timed out waiting for interruption");
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_runAnywhereSpawn_localWins() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(any())).thenReturn(Optional.empty());
    SandboxedSpawnStrategy local = createMockSpawnStrategy("local");
    SandboxedSpawnStrategy remote = createMockSpawnStrategy("remote");
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    Semaphore remoteDone = new Semaphore(0);
    when(remote.exec(eq(spawn), any(), isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              remoteDone.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_REMOTE_SPAWN_RESULT);
            });
    when(local.exec(eq(spawn), any(), isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_LOCAL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_LOCAL_SPAWN_RESULT);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getWinnerBranchType()).isEqualTo(DynamicMode.LOCAL);
    assertThat(events.get(0).getRemoteBranchName()).isEqualTo("remote");
    assertThat(events.get(0).getLocalBranchName()).isEqualTo("local");
  }

  @Test
  public void exec_runAnywhereSpawn_remoteWins() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(any())).thenReturn(Optional.empty());
    SandboxedSpawnStrategy local = createMockSpawnStrategy("local");
    SandboxedSpawnStrategy remote = createMockSpawnStrategy("remote");
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    Semaphore localDone = new Semaphore(0);
    when(remote.exec(eq(spawn), any(), isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_REMOTE_SPAWN_RESULT);
            });
    when(local.exec(eq(spawn), any(), isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              localDone.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(0, "", null);
              return ImmutableList.of(SUCCESSFUL_LOCAL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_REMOTE_SPAWN_RESULT);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getWinnerBranchType()).isEqualTo(DynamicMode.REMOTE);
    assertThat(events.get(0).getRemoteBranchName()).isEqualTo("remote");
    assertThat(events.get(0).getLocalBranchName()).isEqualTo("local");
  }

  @Test
  public void exec_runAnywhereSpawn_allowsIgnoringFailure() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        new DynamicSpawnStrategy(
            executorServiceForCleanup,
            new DynamicExecutionOptions(),
            ignored -> ExecutionPolicy.ANYWHERE,
            ignored -> Optional.empty(),
            10,
            10,
            (s, context, exitCode, errorMsg, outErr, isLocal) ->
                isLocal && errorMsg.contains("Ignorable"));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /* stopConcurrentSpawns= */ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(1, "Ignorable failure", null);
              // We should never get here, so return a different result from remote.
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              Thread.sleep(10);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_runAnywhereSpawn_notAlwaysIgnoringFailure() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        new DynamicSpawnStrategy(
            executorServiceForCleanup,
            new DynamicExecutionOptions(),
            ignored -> ExecutionPolicy.ANYWHERE,
            ignored -> Optional.empty(),
            10,
            10,
            (s, context, exitCode, errorMsg, outErr, isLocal) ->
                isLocal && errorMsg.contains("Ignorable"));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    // Make sure that local execution does not win the race before remote starts.
    Semaphore remoteStarted = new Semaphore(0);
    Semaphore localDone = new Semaphore(0);
    // Only the first spawn should be able to stop the concurrent remote execution (get the output
    // lock).
    when(local.exec(eq(spawn), any(), /* stopConcurrentSpawns= */ isNotNull()))
        .thenAnswer(
            invocation -> {
              remoteStarted.acquire();
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              stopConcurrentSpawns.stop(1, "Not an ignorable failure", null);
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              remoteStarted.release();
              localDone.acquire();
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);
    localDone.release();
    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
  }

  @Test
  public void exec_runAnywhereSpawn_excludeTools_onlyRemote() throws Exception {
    Spawn spawn =
        new SpawnBuilder()
            .withMnemonic("TheThing")
            .withOwnerPrimaryOutput(output1)
            .withProgressMessage("Building the thing")
            .setBuiltForToolConfiguration(true)
            .build();
    DynamicExecutionOptions options = new DynamicExecutionOptions();
    options.excludeTools = true;
    options.localExecutionDelay = 0;
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, (s) -> Optional.empty(), options);

    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenReturn(ImmutableList.of(SUCCESSFUL_SPAWN_RESULT));
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    when(actionExecutionContext.getEventHandler()).thenReturn(reporter);

    ImmutableList<SpawnResult> spawnResults =
        dynamicSpawnStrategy.maybeExecuteNonDynamically(spawn, actionExecutionContext);

    assertWithMessage("Should have been executed remote-only").that(spawnResults).isNotNull();
    assertThat(spawnResults).containsExactly(SUCCESSFUL_SPAWN_RESULT);
    verify(local, never()).exec(any(), any(), any());
  }

  @Test
  public void waitBranches_givesDebugOutputIfBothCancelled() throws Exception {
    Spawn spawn =
        new SpawnBuilder()
            .withOwnerPrimaryOutput(new SourceArtifact(rootDir, PathFragment.create("/foo"), null))
            .build();
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);
    AtomicReference<DynamicMode> strategyThatCancelled = new AtomicReference<>();
    DynamicExecutionOptions options = new DynamicExecutionOptions();
    LocalBranch localBranch =
        new LocalBranch(
            actionExecutionContext, spawn, strategyThatCancelled, options, null, null, null);
    RemoteBranch remoteBranch =
        new RemoteBranch(actionExecutionContext, spawn, strategyThatCancelled, options, null, null);
    localBranch.prepareFuture(remoteBranch);
    remoteBranch.prepareFuture(localBranch);
    localBranch.cancel();
    remoteBranch.cancel();
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                DynamicSpawnStrategy.waitBranches(
                    localBranch,
                    remoteBranch,
                    spawn,
                    new DynamicExecutionOptions(),
                    actionExecutionContext));
    assertThat(error).hasMessageThat().contains("Neither branch of /foo completed.");
  }

  @Test
  public void exec_runAnywhereSpawn_localCantExec_runsRemote() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn = createMockSpawn();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy();
    when(remote.exec(eq(spawn), any(), any()))
        .thenAnswer(
            invocation -> {
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(0, "", null);
              }
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT);
    // Never runs anything as it says it can't execute anything at all.
    verify(local, never()).exec(any(), any(), any());
    verifyNoInteractions(postProcessingSpawn);
  }

  @Test
  public void exec_runAnywhereSpawn_remoteCantExec_runsLocal() throws Exception {
    Spawn spawn = new SpawnBuilder().withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder("extra_command").withOwnerPrimaryOutput(output2).build();
    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy();
    ArgumentCaptor<Spawn> localSpawnCaptor = ArgumentCaptor.forClass(Spawn.class);
    when(local.exec(localSpawnCaptor.capture(), any(), any()))
        .thenAnswer(
            invocation -> {
              StopConcurrentSpawns stopConcurrentSpawns = invocation.getArgument(2);
              if (stopConcurrentSpawns != null) {
                stopConcurrentSpawns.stop(0, "", null);
              }
              return ImmutableList.of(SUCCESSFUL_SPAWN_RESULT);
            });
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    ImmutableList<SpawnResult> results = dynamicSpawnStrategy.exec(spawn, actionExecutionContext);

    assertThat(results).containsExactly(SUCCESSFUL_SPAWN_RESULT, SUCCESSFUL_SPAWN_RESULT);
    assertThat(localSpawnCaptor.getAllValues())
        .containsExactly(spawn, postProcessingSpawn)
        .inOrder();
    verify(remote, never()).exec(any(), any(), any());
  }

  @Test
  public void exec_runAnywhereSpawn_noneCanExec_fails() throws Exception {
    Spawn spawn =
        new SpawnBuilder().withMnemonic("ThisMnemonic1").withOwnerPrimaryOutput(output1).build();
    Spawn postProcessingSpawn =
        new SpawnBuilder().withMnemonic("ThatMnemonic2").withOwnerPrimaryOutput(output2).build();

    DynamicSpawnStrategy dynamicSpawnStrategy =
        createDynamicSpawnStrategy(ExecutionPolicy.ANYWHERE, mockGetPostProcessingSpawn);
    when(mockGetPostProcessingSpawn.apply(spawn)).thenReturn(Optional.of(postProcessingSpawn));
    SandboxedSpawnStrategy local = createMockSpawnStrategy(false);
    SandboxedSpawnStrategy remote = createMockSpawnStrategy(false);
    ActionExecutionContext actionExecutionContext = createMockActionExecutionContext(local, remote);

    UserExecException thrown =
        assertThrows(
            UserExecException.class,
            () -> dynamicSpawnStrategy.exec(spawn, actionExecutionContext));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Spawn is not executable in local: No usable dynamic_local_strategy or"
                + " dynamic_remote_strategy found for action ThisMnemonic1. Post-Processing Spawn"
                + " is not executable in local: No usable dynamic_local_strategy or"
                + " dynamic_remote_strategy found for action ThatMnemonic2. ");
  }

  private DynamicSpawnStrategy createDynamicSpawnStrategy(
      ExecutionPolicy executionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution) {
    return createDynamicSpawnStrategy(
        executionPolicy, getPostProcessingSpawnForLocalExecution, new DynamicExecutionOptions());
  }

  private DynamicSpawnStrategy createDynamicSpawnStrategy(
      ExecutionPolicy executionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution,
      DynamicExecutionOptions options) {
    checkState(
        executorServiceForCleanup == null,
        "Creating the DynamicSpawnStrategy twice in the same test is not supported.");
    executorServiceForCleanup = Executors.newCachedThreadPool();
    return new DynamicSpawnStrategy(
        executorServiceForCleanup,
        options,
        ignored -> executionPolicy,
        getPostProcessingSpawnForLocalExecution,
        10,
        10,
        null);
  }

  private static ActionExecutionContext createMockActionExecutionContext(
      SandboxedSpawnStrategy localStrategy, SandboxedSpawnStrategy remoteStrategy) {
    ActionExecutionContext actionExecutionContext = mock(ActionExecutionContext.class);
    when(actionExecutionContext.getFileOutErr()).thenReturn(new TestFileOutErr());
    when(actionExecutionContext.getContext(DynamicStrategyRegistry.class))
        .thenReturn(
            new DynamicStrategyRegistry() {
              @Override
              public ImmutableList<SandboxedSpawnStrategy> getDynamicSpawnActionContexts(
                  Spawn spawn, DynamicMode dynamicMode) {
                switch (dynamicMode) {
                  case LOCAL:
                    return ImmutableList.of(localStrategy);
                  case REMOTE:
                    return ImmutableList.of(remoteStrategy);
                }
                throw new AssertionError("Unexpected mode: " + dynamicMode);
              }

              @Override
              public void notifyUsedDynamic(ActionContextRegistry actionContextRegistry) {}
            });
    when(actionExecutionContext.withFileOutErr(any())).thenReturn(actionExecutionContext);
    return actionExecutionContext;
  }

  private static Spawn createMockSpawn() {
    return mock(Spawn.class);
  }

  private static SandboxedSpawnStrategy createMockSpawnStrategy()
      throws InterruptedException, ExecException {
    return createMockSpawnStrategy(true);
  }

  private static SandboxedSpawnStrategy createMockSpawnStrategy(boolean canExec)
      throws InterruptedException, ExecException {
    SandboxedSpawnStrategy strategy = mock(SandboxedSpawnStrategy.class);
    when(strategy.canExec(any(), any())).thenReturn(canExec);
    when(strategy.exec(any(), any())).thenThrow(UnsupportedOperationException.class);
    return strategy;
  }

  private static SandboxedSpawnStrategy createMockSpawnStrategy(String name)
      throws InterruptedException, ExecException {
    SandboxedSpawnStrategy strategy = mock(SandboxedSpawnStrategy.class);
    when(strategy.canExec(any(), any())).thenReturn(true);
    when(strategy.toString()).thenReturn(name);
    when(strategy.exec(any(), any())).thenThrow(UnsupportedOperationException.class);
    return strategy;
  }
}
