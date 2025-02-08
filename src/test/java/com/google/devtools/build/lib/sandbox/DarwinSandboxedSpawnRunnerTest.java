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

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SpawnRunnerTestUtil.SpawnExecutionContextForTesting;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link DarwinSandboxedSpawnRunner}.
 *
 * <p>These tests do not require macOS to run because we have no easy means of expressing that
 * requirement. Instead, we just implement "unit-like" tests by mocking out the tools that this
 * spawn runner requires and rely on our shell-level integration tests to validate this properly.
 */
@RunWith(JUnit4.class)
public final class DarwinSandboxedSpawnRunnerTest extends SandboxedSpawnRunnerTestCase {

  /** Tree deleter to use by default for all tests. */
  private static final TreeDeleter treeDeleter = new SynchronousTreeDeleter();

  /** Environment for the running test. */
  private CommandEnvironment commandEnvironment;

  /** Path to the base of the sandbox to pass to the spawn runner. */
  private Path sandboxBase;

  /** Location of the real {@code getconf} binary; saved while the test is running. */
  private String oldGetconf;

  /** Location of the real {@code sandbox-exec} binary; saved while the test is running. */
  private String oldSandboxExec;

  @Before
  public void setUp() throws Exception {
    commandEnvironment = runtimeWrapper.newCommand();
    commandEnvironment.setWorkspaceName("workspace");
    commandEnvironment
        .getLocalResourceManager()
        .setAvailableResources(LocalHostCapacity.getLocalHostCapacity());

    Path execRoot = commandEnvironment.getExecRoot();
    execRoot.createDirectory();
    SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot);

    sandboxBase = execRoot.getRelative("sandbox");
    sandboxBase.createDirectory();

    // The mock getconf tool always prints an arbitrary path regardless of the arguments it
    // receives. We must print a syntactically-valid path, however, to not confuse the consumer
    // of this output.
    Path getconf = execRoot.getRelative("getconf");
    FileSystemUtils.writeContentAsLatin1(getconf, "#!/bin/sh\necho /tmp");
    getconf.setExecutable(true);
    oldGetconf = DarwinSandboxedSpawnRunner.getconfBinary;
    DarwinSandboxedSpawnRunner.getconfBinary = getconf.toString();

    // The mock sandbox-exec just executes the given command and returns its output.
    Path sandboxExec = execRoot.getRelative("sandbox-exec");
    FileSystemUtils.writeContentAsLatin1(sandboxExec,
        "#!/bin/sh\n"
        + "shift\n"  // Skip -f flag.
        + "shift\n"  // Skip target of -f flag.
        + "exec \"$@\"\n");  // Remaining arguments are the process-wrapper's ones.
    sandboxExec.setExecutable(true);
    oldSandboxExec = DarwinSandboxedSpawnRunner.sandboxExecBinary;
    DarwinSandboxedSpawnRunner.sandboxExecBinary = sandboxExec.toString();
  }

  @After
  public void tearDown() {
    DarwinSandboxedSpawnRunner.sandboxExecBinary = oldSandboxExec;
    DarwinSandboxedSpawnRunner.getconfBinary = oldGetconf;
  }

  private void doSimpleExecutionTest(DarwinSandboxedSpawnRunner runner) throws Exception {
    Spawn spawn = new SpawnBuilder("/bin/sh", "-c", "exit 42").build();

    FileOutErr fileOutErr =
        new FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"));
    SpawnExecutionContextForTesting policy =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofMinutes(1));

    SpawnResult spawnResult = runner.exec(spawn, policy);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.NON_ZERO_EXIT);
    assertThat(spawnResult.exitCode()).isEqualTo(42);
  }

  @Test
  public void testSimpleExecution() throws Exception {
    DarwinSandboxedSpawnRunner runner =
        new DarwinSandboxedSpawnRunner(
            new SandboxHelpers(),
            commandEnvironment,
            sandboxBase,
            treeDeleter);
    doSimpleExecutionTest(runner);
  }

  @Test
  public void testSupportsParamFiles() throws Exception {
    DarwinSandboxedSpawnRunner runner =
        new DarwinSandboxedSpawnRunner(
            new SandboxHelpers(),
            commandEnvironment,
            sandboxBase,
            treeDeleter);
    Spawn spawn =
        new SpawnBuilder("cp", "params/param-file", "out")
            .withInput(
                new ParamFileActionInput(
                    PathFragment.create("params/param-file"),
                    ImmutableList.of("--foo", "--bar"),
                    ParameterFileType.UNQUOTED))
            .withOutput("out")
            .build();
    FileOutErr fileOutErr =
        new FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"));
    SpawnExecutionContextForTesting policy =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofMinutes(1));
    SpawnResult spawnResult = runner.exec(spawn, policy);
    assertThat(spawnResult.status()).isEqualTo(Status.SUCCESS);
    Path paramFile = commandEnvironment.getExecRoot().getRelative("out");
    assertThat(paramFile.exists()).isTrue();
    try (InputStream inputStream = paramFile.getInputStream()) {
      assertThat(
              new String(ByteStreams.toByteArray(inputStream), StandardCharsets.UTF_8).split("\n"))
          .asList()
          .containsExactly("--foo", "--bar");
    }
  }
}
