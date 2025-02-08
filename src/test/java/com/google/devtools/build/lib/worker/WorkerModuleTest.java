// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat.JSON;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.RecordingExceptionHandler;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlazeWorkspace;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.AsynchronousTreeDeleter;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for WorkerModule. I bet you didn't see that coming, eh? */
@RunWith(JUnit4.class)
public class WorkerModuleTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock CommandEnvironment env;
  @Mock BuildRequest request;
  @Mock OptionsParsingResult startupOptionsProvider;

  private final FileSystem fs =
      new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
  private StoredEventHandler storedEventHandler;

  @Test
  public void buildStarting_createsPools()
      throws AbruptExitException, IOException, InterruptedException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));

    assertThat(storedEventHandler.getEvents()).isEmpty();
    assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isFalse();
    assertThat(module.workerPool).isNotNull();

    WorkerKey workerKey = WorkerTestUtils.createWorkerKey(JSON, fs);
    Worker worker = module.workerPool.borrowWorker(workerKey);

    assertThat(worker.workerKey).isEqualTo(workerKey);
    assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isTrue();
  }

  @Test
  public void buildStarting_noRestartOnSandboxChange() throws IOException, AbruptExitException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();

    Path workerDir = fs.getPath("/outputRoot/outputBase/bazel-workers");
    Path aLog = workerDir.getRelative("f.log");
    workerDir.createDirectoryAndParents();
    aLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT);
    WorkerPool oldPool = module.workerPool;
    options.workerSandboxing = !options.workerSandboxing;
    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();
    assertThat(module.workerPool).isSameInstanceAs(oldPool);
    assertThat(aLog.exists()).isTrue();
  }

  @Test
  public void buildStarting_restartsOnOutputbaseChanges() throws IOException, AbruptExitException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();

    // Log file from old root, doesn't get cleaned
    Path workerDir = fs.getPath("/outputRoot/outputBase/bazel-workers");
    Path oldLog = workerDir.getRelative("f.log");
    workerDir.createDirectoryAndParents();
    oldLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT);

    WorkerPool oldPool = module.workerPool;
    setupEnvironment("/otherRootDir");
    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Worker factory configuration has changed");
    assertThat(module.workerPool).isNotSameInstanceAs(oldPool);
    WorkerKey workerKey = WorkerTestUtils.createWorkerKey(fs, "mnemonic", false);
    module.workerFactory.create(workerKey);
    assertThat(fs.getPath("/otherRootDir/outputBase/bazel-workers").exists()).isTrue();
    assertThat(oldLog.exists()).isTrue();
  }

  @Test
  public void buildStarting_restartsOnUseCgroupsOnLinuxChanges()
      throws IOException, AbruptExitException, OptionsParsingException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options =
        Options.parse(WorkerOptions.class, "--noexperimental_worker_use_cgroups_on_linux")
            .getOptions();
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    // Check that new pools/factories are made with default options
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();

    WorkerPool oldPool = module.workerPool;
    WorkerOptions newOptions =
        Options.parse(WorkerOptions.class, "--experimental_worker_use_cgroups_on_linux")
            .getOptions();
    when(request.getOptions(WorkerOptions.class)).thenReturn(newOptions);

    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Worker factory configuration has changed");
    assertThat(module.workerPool).isNotSameInstanceAs(oldPool);
  }

  @Test
  public void buildStarting_clearsLogsOnFactoryCreation() throws IOException, AbruptExitException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    Path workerDir = fs.getPath("/outputRoot/outputBase/bazel-workers");
    workerDir.createDirectoryAndParents();
    Path oldLog = workerDir.getRelative("f.log");
    oldLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT);

    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));

    assertThat(storedEventHandler.getEvents()).isEmpty();
    assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isTrue();
    assertThat(oldLog.exists()).isFalse();
  }

  @Test
  public void buildStarting_restartsOnNumMultiplexWorkersChanges()
      throws IOException, AbruptExitException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;
    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    // Check that new pools/factories are made with default options
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();

    WorkerPool oldPool = module.workerPool;
    options.workerMaxMultiplexInstances = Lists.newArrayList(Maps.immutableEntry("foo", 42));
    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Worker pool configuration has changed");
    assertThat(module.workerPool).isNotSameInstanceAs(oldPool);
  }

  @Test
  public void buildStarting_restartsOnNumWorkersChanges() throws IOException, AbruptExitException {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;

    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    // Check that new pools/factories are made with default options
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).isEmpty();

    WorkerPool oldPool = module.workerPool;
    options.workerMaxInstances = Lists.newArrayList(Maps.immutableEntry("bar", 3));
    module.beforeCommand(env);
    module.buildStarting(BuildStartingEvent.create(env, request));
    assertThat(storedEventHandler.getEvents()).hasSize(1);
    assertThat(storedEventHandler.getEvents().get(0).getMessage())
        .contains("Worker pool configuration has changed");
    assertThat(module.workerPool).isNotSameInstanceAs(oldPool);
  }

  @Test
  public void buildStarting_survivesNoWorkerDir() throws Exception {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;

    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    Path workerDir = fs.getPath("/outputRoot/outputBase/bazel-workers");

    // Check that new pools/factories can be created without a worker dir.
    module.buildStarting(BuildStartingEvent.create(env, request));

    // But once we try to get a worker, it should fail. This forces a situation where we can't
    // have a workerDir.
    assertThat(workerDir.exists()).isFalse();
    workerDir.getParentDirectory().createDirectoryAndParents();
    workerDir.getParentDirectory().setWritable(false);

    // But an actual worker cannot be created.
    WorkerKey key = WorkerTestUtils.createWorkerKey(fs, "Work", /* proxied= */ false);
    assertThrows(IOException.class, () -> module.workerPool.borrowWorker(key));
  }

  @Test
  public void buildStarting_cleansStaleTrashDirCleanedOnFirstBuild() throws Exception {
    WorkerModule module = new WorkerModule();
    WorkerOptions options = WorkerOptions.DEFAULTS;

    when(request.getOptions(WorkerOptions.class)).thenReturn(options);
    setupEnvironment("/outputRoot");

    module.beforeCommand(env);
    Path workerDir = fs.getPath("/outputRoot/outputBase/bazel-workers");
    Path trashBase = workerDir.getChild(AsynchronousTreeDeleter.MOVED_TRASH_DIR);
    trashBase.createDirectoryAndParents();

    Path staleTrash = trashBase.getChild("random-trash");

    staleTrash.createDirectoryAndParents();
    module.buildStarting(BuildStartingEvent.create(env, request));
    // Trash is cleaned upon first build.
    assertThat(staleTrash.exists()).isFalse();

    staleTrash.createDirectoryAndParents();
    module.buildStarting(BuildStartingEvent.create(env, request));
    // Trash is not cleaned upon subsequent builds.
    assertThat(staleTrash.exists()).isTrue();
  }

  private void setupEnvironment(String rootDir) throws IOException, AbruptExitException {
    storedEventHandler = new StoredEventHandler();
    Path root = fs.getPath(rootDir);
    Path outputBase = root.getRelative("outputBase");
    outputBase.createDirectoryAndParents();
    when(env.getOutputBase()).thenReturn(outputBase);
    Path workspace = fs.getPath("/workspace");
    when(env.getWorkingDirectory()).thenReturn(workspace);
    ServerDirectories serverDirectories =
        new ServerDirectories(
            root.getRelative("userroot/install"), outputBase, root.getRelative("userroot"));
    BlazeRuntime blazeRuntime =
        new BlazeRuntime.Builder()
            .setProductName("bazel")
            .setServerDirectories(serverDirectories)
            .setStartupOptionsProvider(startupOptionsProvider)
            .build();
    when(env.getRuntime()).thenReturn(blazeRuntime);
    BlazeDirectories blazeDirectories =
        new BlazeDirectories(serverDirectories, null, null, "blaze");
    BlazeWorkspace blazeWorkspace =
        new BlazeWorkspace(
            blazeRuntime,
            blazeDirectories,
            /* skyframeExecutor= */ null,
            new RecordingExceptionHandler(),
            /* workspaceStatusActionFactory= */ null,
            BinTools.forUnitTesting(blazeDirectories, ImmutableList.of()),
            /* allocationTracker= */ null,
            /* syscallCache= */ null,
            /* analysisCodecRegistrySupplier= */ null,
            /* fingerprintValueServiceFactory= */ null,
            /* allowExternalRepositories= */ true);
    when(env.getBlazeWorkspace()).thenReturn(blazeWorkspace);
    when(env.getDirectories()).thenReturn(blazeDirectories);
    EventBus eventBus = new EventBus();
    when(env.getEventBus()).thenReturn(eventBus);
    when(env.getReporter()).thenReturn(new Reporter(eventBus, storedEventHandler));
    when(env.determineOutputFileSystem()).thenReturn("OutputFileSystem");
  }
}
