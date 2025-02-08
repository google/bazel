// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SpawnRunnerTestUtil.SpawnExecutionContextForTesting;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link LinuxSandboxedSpawnRunner}. */
@RunWith(TestParameterInjector.class)
public final class LinuxSandboxedSpawnRunnerTest extends SandboxedSpawnRunnerTestCase {

  /** Tree deleter to use by default for all tests. */
  private static final TreeDeleter treeDeleter = new SynchronousTreeDeleter();

  @Before
  public void assumeRunningOnLinux() {
    assume().that(OS.getCurrent()).isEqualTo(OS.LINUX);
  }

  @Test
  public void exec_echoCommand_executesSuccessfully() throws Exception {
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(createCommandEnvironment());
    Spawn spawn = new SpawnBuilder("echo", "echolalia").build();
    Path stdout = testRoot.getChild("stdout");
    SpawnExecutionContext policy = createSpawnExecutionContext(spawn, stdout);

    SpawnResult spawnResult = runner.exec(spawn, policy);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();
    assertThat(spawnResult.getWallTimeInMs()).isGreaterThan(0);
    assertThat(FileSystemUtils.readLines(stdout, UTF_8)).containsExactly("echolalia");
  }

  @Test
  public void exec_commandWithParamFiles_executesSuccessfully() throws Exception {
    CommandEnvironment commandEnvironment = createCommandEnvironment();
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(commandEnvironment);
    Spawn spawn =
        new SpawnBuilder("cp", "params/param-file", "out")
            .withInput(
                new ParamFileActionInput(
                    PathFragment.create("params/param-file"),
                    ImmutableList.of("--foo", "--bar"),
                    ParameterFileType.UNQUOTED))
            .withOutput("out")
            .build();
    SpawnExecutionContext policy = createSpawnExecutionContext(spawn);

    SpawnResult spawnResult = runner.exec(spawn, policy);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    Path paramFile = commandEnvironment.getExecRoot().getRelative("out");
    assertThat(paramFile.exists()).isTrue();
    assertThat(FileSystemUtils.readLines(paramFile, UTF_8))
        .containsExactly("--foo", "--bar")
        .inOrder();
  }

  @Test
  public void exec_spawnRunningBinTool_executesSuccessfully() throws Exception {
    CommandEnvironment commandEnvironment = createCommandEnvironment();
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(commandEnvironment);
    BinTools.PathActionInput pathActionInput =
        new BinTools.PathActionInput(
            new Scratch().file("/execRoot/tool", "#!/bin/bash", "echo hello > $1"),
            PathFragment.create("_bin/tool"));
    Artifact output =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asDerivedRoot(
                commandEnvironment.getExecRoot(), RootType.Output, "blaze-out"),
            commandEnvironment.getExecRoot().getRelative("blaze-out/output"));
    Spawn spawn =
        new SpawnBuilder("_bin/tool", output.getExecPathString())
            .withInput(pathActionInput)
            .withOutput(output)
            .build();
    SpawnExecutionContext policy = createSpawnExecutionContext(spawn);

    SpawnResult spawnResult = runner.exec(spawn, policy);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(FileSystemUtils.readLines(output.getPath(), UTF_8)).containsExactly("hello");
  }

  @Test
  public void exec_collectsExecutionStatistics() throws Exception {
    CommandEnvironment commandEnvironment = createCommandEnvironment();
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(commandEnvironment);
    Path cpuTimeSpenderPath =
        SpawnRunnerTestUtil.copyCpuTimeSpenderIntoPath(commandEnvironment.getExecRoot());
    Duration minimumWallTimeToSpend = Duration.ofSeconds(10);
    // Because of e.g. interference, wall time taken may be much larger than CPU time used.
    Duration maximumWallTimeToSpend = Duration.ofSeconds(40);
    Duration minimumUserTimeToSpend = minimumWallTimeToSpend;
    Duration maximumUserTimeToSpend = minimumUserTimeToSpend.plus(Duration.ofSeconds(2));
    Duration minimumSystemTimeToSpend = Duration.ZERO;
    Duration maximumSystemTimeToSpend = minimumSystemTimeToSpend.plus(Duration.ofSeconds(2));
    Spawn spawn =
        new SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                String.valueOf(minimumUserTimeToSpend.toSeconds()),
                String.valueOf(minimumSystemTimeToSpend.toSeconds()))
            .build();
    SpawnExecutionContextForTesting policy = createSpawnExecutionContext(spawn);

    SpawnResult spawnResult = runner.exec(spawn, policy);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();
    assertThat(spawnResult.getWallTimeInMs()).isAtLeast((int) minimumWallTimeToSpend.toMillis());
    assertThat(spawnResult.getWallTimeInMs()).isAtMost((int) maximumWallTimeToSpend.toMillis());
    assertThat(spawnResult.getUserTimeInMs()).isAtLeast((int) minimumUserTimeToSpend.toMillis());
    assertThat(spawnResult.getUserTimeInMs()).isAtMost((int) maximumUserTimeToSpend.toMillis());
    assertThat(spawnResult.getSystemTimeInMs())
        .isAtLeast((int) minimumSystemTimeToSpend.toMillis());
    assertThat(spawnResult.getSystemTimeInMs()).isAtMost((int) maximumSystemTimeToSpend.toMillis());
    assertThat(spawnResult.getNumBlockOutputOperations()).isAtLeast(0L);
    assertThat(spawnResult.getNumBlockInputOperations()).isAtLeast(0L);
    assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isAtLeast(0L);
  }

  @Test
  public void hermeticTmp_tmpCreatedAndMounted() throws Exception {
    runtimeWrapper.addOptions("--incompatible_sandbox_hermetic_tmp");
    CommandEnvironment commandEnvironment = createCommandEnvironment();
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(commandEnvironment);
    Spawn spawn = new SpawnBuilder().build();
    SandboxedSpawn sandboxedSpawn = runner.prepareSpawn(spawn, createSpawnExecutionContext(spawn));

    Path sandboxPath =
        sandboxedSpawn.getSandboxExecRoot().getParentDirectory().getParentDirectory();
    Path hermeticTmpPath = sandboxPath.getRelative("_hermetic_tmp");
    assertThat(hermeticTmpPath.isDirectory()).isTrue();

    assertThat(sandboxedSpawn).isInstanceOf(SymlinkedSandboxedSpawn.class);
    String args = String.join(" ", sandboxedSpawn.getArguments());
    assertThat(args).contains("-w /tmp");
    assertThat(args).contains("-M " + hermeticTmpPath + " -m /tmp");
  }

  @Test
  public void hermeticTmp_sandboxTmpfsOnTmp_tmpNotCreatedOrMounted() throws Exception {
    runtimeWrapper.addOptions("--incompatible_sandbox_hermetic_tmp", "--sandbox_tmpfs_path=/tmp");
    CommandEnvironment commandEnvironment = createCommandEnvironment();
    LinuxSandboxedSpawnRunner runner = setupSandboxAndCreateRunner(commandEnvironment);
    Spawn spawn = new SpawnBuilder().build();
    SandboxedSpawn sandboxedSpawn = runner.prepareSpawn(spawn, createSpawnExecutionContext(spawn));

    Path sandboxPath =
        sandboxedSpawn.getSandboxExecRoot().getParentDirectory().getParentDirectory();
    Path hermeticTmpPath = sandboxPath.getRelative("_hermetic_tmp");
    assertThat(hermeticTmpPath.isDirectory()).isFalse();

    assertThat(sandboxedSpawn).isInstanceOf(SymlinkedSandboxedSpawn.class);
    String args = String.join(" ", sandboxedSpawn.getArguments());
    assertThat(args).contains("-w /tmp");
    assertThat(args).contains("-e /tmp");
    assertThat(args).doesNotContain("-m /tmp");
  }

  private static LinuxSandboxedSpawnRunner setupSandboxAndCreateRunner(
      CommandEnvironment commandEnvironment) throws IOException {
    Path execRoot = commandEnvironment.getExecRoot();
    execRoot.createDirectory();

    SpawnRunnerTestUtil.copyLinuxSandboxIntoPath(execRoot);

    Path sandboxBase = execRoot.getRelative("sandbox");
    sandboxBase.createDirectory();

    return LinuxSandboxedStrategy.create(
        new SandboxHelpers(),
        commandEnvironment,
        sandboxBase,
        /*timeoutKillDelay=*/ Duration.ofSeconds(2),
        treeDeleter);
  }

  private SpawnExecutionContextForTesting createSpawnExecutionContext(Spawn spawn) {
    return createSpawnExecutionContext(spawn, testRoot.getChild("stdout"));
  }

  private SpawnExecutionContextForTesting createSpawnExecutionContext(Spawn spawn, Path stdout) {
    FileOutErr fileOutErr = new FileOutErr(stdout, testRoot.getChild("stderr"));
    return new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofMinutes(1));
  }

  private CommandEnvironment createCommandEnvironment() throws Exception {
    CommandEnvironment commandEnvironment = runtimeWrapper.newCommand();
    commandEnvironment.setWorkspaceName("workspace");
    commandEnvironment
        .getLocalResourceManager()
        .setAvailableResources(LocalHostCapacity.getLocalHostCapacity());
    return commandEnvironment;
  }
}
