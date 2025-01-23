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

package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.worker.TestUtils.createWorkerKey;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.lib.worker.WorkerPoolImpl.WorkerPoolConfig;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.apache.commons.pool2.PooledObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit tests for the WorkerSpawnRunner. */
@RunWith(JUnit4.class)
public class WorkerSpawnRunnerTest {
  final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock ExtendedEventHandler reporter;
  @Mock LocalEnvProvider localEnvProvider;
  @Mock ResourceManager resourceManager;
  @Mock SpawnMetrics.Builder spawnMetrics;
  @Mock Spawn spawn;
  @Mock SpawnExecutionContext context;
  @Mock InputMetadataProvider inputFileCache;
  @Mock Worker worker;
  @Mock WorkerOptions options;
  @Mock WorkerMetricsCollector metricsCollector;
  @Mock ResourceManager.ResourceHandle resourceHandle;

  @Before
  public void setUp() throws InterruptedException, IOException, ExecException {
    when(spawn.getInputFiles()).thenReturn(NestedSetBuilder.emptySet(Order.COMPILE_ORDER));
    when(context.getArtifactExpander()).thenReturn((artifact, output) -> {});
    doNothing()
        .when(metricsCollector)
        .registerWorker(anyInt(), anyLong(), anyString(), anyBoolean(), anyBoolean(), anyInt());
    when(spawn.getLocalResources()).thenReturn(ResourceSet.createWithRamCpu(100, 1));
    when(resourceManager.acquireResources(any(), any(), any())).thenReturn(resourceHandle);
    when(resourceHandle.getWorker()).thenReturn(worker);
  }

  private WorkerPoolImpl createWorkerPool() {
    return new WorkerPoolImpl(
        new WorkerPoolConfig(
            new WorkerFactory(fs.getPath("/workerBase")) {
              @Override
              public Worker create(WorkerKey key) {
                return worker;
              }

              @Override
              public boolean validateObject(WorkerKey key, PooledObject<Worker> p) {
                return true;
              }
            },
            ImmutableList.of(),
            ImmutableList.of()));
  }

  @Test
  public void testExecInWorker_happyPath() throws ExecException, InterruptedException, IOException {
    WorkerSpawnRunner runner = createWorkerSpawnRunner(new WorkerOptions());
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(0))
        .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build());
    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
            SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(context).report(SpawnExecutingEvent.create("worker"));
    verify(resourceHandle).close();
    verify(resourceHandle, times(0)).invalidateAndClose();
    verify(context).lockOutputFiles(eq(0), eq("out"), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_virtualInputs_doesntQueryInputFileCache()
      throws ExecException, InterruptedException, IOException {
    Path execRoot = fs.getPath("/execRoot");
    Path workDir = execRoot.getRelative("workdir");

    WorkerSpawnRunner runner =
        new WorkerSpawnRunner(
            new SandboxHelpers(),
            execRoot,
            createWorkerPool(),
            reporter,
            localEnvProvider,
            /* binTools= */ null,
            resourceManager,
            /* runfilesTreeUpdater= */ null,
            new ExecutionOptions(),
            new WorkerOptions(),
            metricsCollector,
            new JavaClock());
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");

    SandboxHelper sandboxHelper = new SandboxHelper(execRoot, workDir);
    sandboxHelper.addAndCreateVirtualInput("input", "content");

    VirtualActionInput virtualActionInput =
        Iterables.getOnlyElement(
            sandboxHelper.getSandboxInputs().getVirtualInputDigests().keySet());

    when(worker.getResponse(0))
        .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build());
    when(spawn.getInputFiles())
        .thenAnswer(
            invocation ->
                NestedSetBuilder.create(Order.COMPILE_ORDER, (ActionInput) virtualActionInput));

    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            sandboxHelper.getSandboxInputs(),
            sandboxHelper.getSandboxOutputs(),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(inputFileCache, never()).getInputMetadata(virtualActionInput);
    verify(resourceHandle).close();
    verify(resourceHandle, times(0)).invalidateAndClose();
    verify(context).lockOutputFiles(eq(0), startsWith("out"), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_finishesAsyncOnInterrupt()
      throws InterruptedException, IOException, ExecException {
    WorkerSpawnRunner runner = createWorkerSpawnRunner(new WorkerOptions());
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(anyInt()))
        .thenThrow(new InterruptedException())
        .thenReturn(WorkResponse.newBuilder().setRequestId(2).build());
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));
    assertThat(logFile.exists()).isFalse();
    verify(context).report(SpawnExecutingEvent.create("worker"));
    verify(worker).putRequest(WorkRequest.newBuilder().setRequestId(0).build());
    verify(resourceHandle, times(0)).close();
    verify(resourceHandle).invalidateAndClose();
  }

  @Test
  public void testExecInWorker_sendsCancelMessageOnInterrupt()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = true;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    when(worker.isSandboxed()).thenReturn(true);
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    Semaphore secondResponseRequested = new Semaphore(0);
    // Fake that the getting the regular response gets interrupted and we then answer the cancel.
    when(worker.getResponse(anyInt()))
        .thenThrow(new InterruptedException())
        .thenAnswer(
            invocation -> {
              secondResponseRequested.release();
              return WorkResponse.newBuilder()
                  .setRequestId(invocation.getArgument(0))
                  .setWasCancelled(true)
                  .build();
            });
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));
    secondResponseRequested.acquire();
    assertThat(logFile.exists()).isFalse();
    verify(context).report(SpawnExecutingEvent.create("worker"));
    ArgumentCaptor<WorkRequest> argumentCaptor = ArgumentCaptor.forClass(WorkRequest.class);
    verify(worker, times(2)).putRequest(argumentCaptor.capture());
    assertThat(argumentCaptor.getAllValues().get(0))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build());
    assertThat(argumentCaptor.getAllValues().get(1))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).setCancel(true).build());
    // Wait until thread produced by WorkerSpawnRunner.finishWorkAsync is finshed and returned
    // resources via resourceHandle.
    Thread.sleep(50);
    verify(resourceHandle).close();
    verify(resourceHandle, times(0)).invalidateAndClose();
  }

  @Test
  public void testExecInWorker_unsandboxedDiesOnInterrupt()
      throws InterruptedException, IOException, ExecException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerCancellation = true;
    workerOptions.workerSandboxing = false;
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1"));
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(anyInt())).thenThrow(new InterruptedException());
    // Since this worker is not sandboxed, it will just get killed on interrupt.
    assertThrows(
        InterruptedException.class,
        () ->
            runner.execInWorker(
                spawn,
                key,
                context,
                new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                ImmutableList.of(),
                inputFileCache,
                spawnMetrics));

    assertThat(logFile.exists()).isFalse();
    verify(context).report(SpawnExecutingEvent.create("worker"));
    ArgumentCaptor<WorkRequest> argumentCaptor = ArgumentCaptor.forClass(WorkRequest.class);
    verify(worker).putRequest(argumentCaptor.capture());
    assertThat(argumentCaptor.getAllValues().get(0))
        .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build());
    verify(resourceHandle, times(0)).close();
    verify(resourceHandle).invalidateAndClose();
  }

  @Test
  public void testExecInWorker_noMultiplexWithDynamic()
      throws ExecException, InterruptedException, IOException {
    WorkerOptions workerOptions = new WorkerOptions();
    workerOptions.workerMultiplex = true;
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    // This worker key just so happens to be multiplex and require sandboxing.
    WorkerKey key = createWorkerKey(WorkerProtocolFormat.JSON, fs, true);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getResponse(0))
        .thenReturn(
            WorkResponse.newBuilder().setExitCode(0).setRequestId(0).setOutput("out").build());
    WorkResponse response =
        runner.execInWorker(
            spawn,
            key,
            context,
            new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
            SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
            ImmutableList.of(),
            inputFileCache,
            spawnMetrics);

    assertThat(response).isNotNull();
    assertThat(response.getExitCode()).isEqualTo(0);
    assertThat(response.getRequestId()).isEqualTo(0);
    assertThat(response.getOutput()).isEqualTo("out");
    assertThat(logFile.exists()).isFalse();
    verify(context).report(SpawnExecutingEvent.create("worker"));
    verify(resourceHandle).close();
    verify(resourceHandle, times(0)).invalidateAndClose();
    verify(context).lockOutputFiles(eq(0), startsWith("out"), ArgumentMatchers.isNull());
  }

  private void assertRecordedResponsethrowsException(String recordedResponse, String exceptionText)
      throws Exception {
    WorkerOptions workerOptions = new WorkerOptions();
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    WorkerKey key = createWorkerKey(fs, "mnem", false);
    Path logFile = fs.getPath("/worker.log");
    when(worker.getLogFile()).thenReturn(logFile);
    when(worker.getResponse(0)).thenThrow(new IOException("Bad protobuf"));
    when(worker.getRecordingStreamMessage()).thenReturn(recordedResponse);
    when(worker.getExitValue()).thenReturn(Optional.of(2));
    String workerLog = "Log from worker\n";
    FileSystemUtils.writeIsoLatin1(logFile, workerLog);
    UserExecException execException =
        assertThrows(
            UserExecException.class,
            () ->
                runner.execInWorker(
                    spawn,
                    key,
                    context,
                    new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()),
                    SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of()),
                    ImmutableList.of(),
                    inputFileCache,
                    spawnMetrics));

    assertThat(execException).hasMessageThat().contains(exceptionText);
    if (!recordedResponse.isEmpty()) {
      assertThat(execException)
          .hasMessageThat()
          .contains(logMarker("Exception details") + "java.io.IOException: Bad protobuf");

      assertThat(execException)
          .hasMessageThat()
          .contains(
              logMarker("Start of response") + recordedResponse + logMarker("End of response"));
    }
    assertThat(execException)
        .hasMessageThat()
        .contains(logMarker("Start of log, file at " + logFile.getPathString()) + workerLog);
    verify(context)
        .lockOutputFiles(
            eq(2), ArgumentMatchers.contains(exceptionText), ArgumentMatchers.isNull());
  }

  @Test
  public void testExecInWorker_showsLogFileInException() throws Exception {
    assertRecordedResponsethrowsException("Some text", "unparseable WorkResponse!\n");
  }

  @Test
  public void testExecInWorker_throwsWithEmptyResponse() throws Exception {
    assertRecordedResponsethrowsException("", "did not return a WorkResponse");
  }

  @Test
  public void testExpandArgument_expandsArgumentsRecursively()
      throws IOException, InterruptedException {
    WorkRequest.Builder requestBuilder = WorkRequest.newBuilder();
    FileSystemUtils.writeIsoLatin1(fs.getPath("/file"), "arg1\n@file2\nmulti arg\n");
    FileSystemUtils.writeIsoLatin1(fs.getPath("/file2"), "arg2\narg3");
    SandboxInputs inputs =
        new SandboxInputs(
            ImmutableMap.of(
                PathFragment.create("file"),
                fs.getPath("/file"),
                PathFragment.create("file2"),
                fs.getPath("/file2")),
            ImmutableMap.of(),
            ImmutableMap.of());
    WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder);
    assertThat(requestBuilder.getArgumentsList())
        .containsExactly("arg1", "arg2", "arg3", "multi arg", "");
  }

  @Test
  public void testExpandArgument_expandsOnlyProperArguments()
      throws IOException, InterruptedException {
    WorkRequest.Builder requestBuilder = WorkRequest.newBuilder();
    FileSystemUtils.writeIsoLatin1(fs.getPath("/file"), "arg1\n@@nonfile\n@foo//bar\narg2");
    SandboxInputs inputs =
        new SandboxInputs(
            ImmutableMap.of(PathFragment.create("file"), fs.getPath("/file")),
            ImmutableMap.of(),
            ImmutableMap.of());
    WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder);
    assertThat(requestBuilder.getArgumentsList())
        .containsExactly("arg1", "@@nonfile", "@foo//bar", "arg2");
  }

  @Test
  public void testExpandArgument_failsOnMissingFile() {
    WorkRequest.Builder requestBuilder = WorkRequest.newBuilder();
    SandboxInputs inputs =
        new SandboxInputs(
            ImmutableMap.of(PathFragment.create("file"), fs.getPath("/dir/file")),
            ImmutableMap.of(),
            ImmutableMap.of());
    IOException e =
        assertThrows(
            IOException.class,
            () -> WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder));
    assertThat(e).hasMessageThat().contains("file");
    assertThat(e).hasMessageThat().contains("/dir/file");
  }

  @Test
  public void testCanExec_checksRequirements() throws Exception {
    WorkerOptions workerOptions = new WorkerOptions();
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    when(spawn.getMnemonic()).thenReturn("Mnemonic");

    // Missing "supports-workers"
    when(spawn.getExecutionInfo()).thenReturn(ImmutableMap.of());
    assertThat(runner.canExec(spawn)).isFalse();

    // Missing toolFiles
    when(spawn.getExecutionInfo())
        .thenReturn(ImmutableMap.of(ExecutionRequirements.SUPPORTS_WORKERS, "1"));
    when(spawn.getToolFiles())
        .thenAnswer(
            (Answer<NestedSet<ActionInput>>)
                invocation -> NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    assertThat(runner.canExec(spawn)).isFalse();

    // Minimum requirements met
    NestedSet<ActionInput> toolFiles =
        NestedSetBuilder.create(
            Order.STABLE_ORDER,
            ActionInputHelper.fromPath("myTools/tool1"),
            ActionInputHelper.fromPath("myTools/tool2"));
    // Using `thenAnswer` to work around Mockito type capture issues.
    when(spawn.getToolFiles()).thenAnswer((Answer<NestedSet<ActionInput>>) invocation -> toolFiles);
    assertThat(runner.canExec(spawn)).isTrue();
  }

  @Test
  public void testCanExec_obeysAllowlist() throws Exception {
    WorkerOptions workerOptions = new WorkerOptions();
    WorkerSpawnRunner runner = createWorkerSpawnRunner(workerOptions);
    when(spawn.getMnemonic()).thenReturn("Mnemonic");
    NestedSet<ActionInput> toolFiles =
        NestedSetBuilder.create(
            Order.STABLE_ORDER,
            ActionInputHelper.fromPath("myTools/tool1"),
            ActionInputHelper.fromPath("myTools/tool2"));
    // Using `thenAnswer` to work around Mockito type capture issues.
    when(spawn.getToolFiles()).thenAnswer((Answer<NestedSet<ActionInput>>) invocation -> toolFiles);

    // Allowed due to no allowlist
    when(spawn.getExecutionInfo())
        .thenReturn(
            ImmutableMap.of(
                ExecutionRequirements.SUPPORTS_WORKERS,
                "1",
                ExecutionRequirements.WORKER_KEY_MNEMONIC,
                "WKM2"));
    assertThat(runner.canExec(spawn)).isTrue();

    workerOptions.allowlist = ImmutableList.of("WKM1", "Mnemonic");

    // Blocked by allowlist
    when(spawn.getExecutionInfo())
        .thenReturn(
            ImmutableMap.of(
                ExecutionRequirements.SUPPORTS_WORKERS,
                "1",
                ExecutionRequirements.WORKER_KEY_MNEMONIC,
                "WKM2"));
    assertThat(runner.canExec(spawn)).isFalse();

    // On allowlist
    when(spawn.getExecutionInfo())
        .thenReturn(
            ImmutableMap.of(
                ExecutionRequirements.SUPPORTS_WORKERS,
                "1",
                ExecutionRequirements.WORKER_KEY_MNEMONIC,
                "WKM1"));
    assertThat(runner.canExec(spawn)).isTrue();

    // On allowlist
    when(spawn.getExecutionInfo())
        .thenReturn(
            ImmutableMap.of(
                ExecutionRequirements.SUPPORTS_WORKERS,
                "1",
                ExecutionRequirements.WORKER_KEY_MNEMONIC,
                "WKM1"));
    assertThat(runner.canExec(spawn)).isTrue();
  }

  private WorkerSpawnRunner createWorkerSpawnRunner(WorkerOptions workerOptions) {
    return new WorkerSpawnRunner(
        new SandboxHelpers(),
        fs.getPath("/execRoot"),
        createWorkerPool(),
        reporter,
        localEnvProvider,
        /* binTools= */ null,
        resourceManager,
        /* runfilesTreeUpdater= */ null,
        new ExecutionOptions(),
        workerOptions,
        metricsCollector,
        new JavaClock());
  }

  @Test
  public void testExpandArgument_failsOnUndeclaredInput() {
    WorkRequest.Builder requestBuilder = WorkRequest.newBuilder();
    SandboxInputs inputs =
        new SandboxInputs(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    IOException e =
        assertThrows(
            IOException.class,
            () -> WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder));
    assertThat(e).hasMessageThat().contains("file");
    assertThat(e).hasMessageThat().contains("declared input");
  }

  private static String logMarker(String text) {
    return "---8<---8<--- " + text + " ---8<---8<---\n";
  }
}
