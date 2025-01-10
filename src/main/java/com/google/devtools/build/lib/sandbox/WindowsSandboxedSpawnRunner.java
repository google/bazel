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

package com.google.devtools.build.lib.sandbox;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.WindowsLocalEnvProvider;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;

/** Spawn runner that uses BuildXL Sandbox APIs to execute a local subprocess. */
final class WindowsSandboxedSpawnRunner extends AbstractSandboxSpawnRunner {

  private final SandboxHelpers helpers;
  private final Path execRoot;
  private final PathFragment windowsSandbox;
  private final LocalEnvProvider localEnvProvider;
  private final Duration timeoutKillDelay;

  /**
   * Creates a sandboxed spawn runner that uses the {@code windows-sandbox} tool.
   *
   * @param helpers common tools and state across all spawns during sandboxed execution
   * @param cmdEnv the command environment to use
   * @param timeoutKillDelay an additional grace period before killing timing out commands
   * @param windowsSandboxPath path to windows-sandbox binary
   */
  WindowsSandboxedSpawnRunner(
      SandboxHelpers helpers,
      CommandEnvironment cmdEnv,
      Duration timeoutKillDelay,
      PathFragment windowsSandboxPath) {
    super(cmdEnv);
    this.helpers = helpers;
    this.execRoot = cmdEnv.getExecRoot();
    this.windowsSandbox = windowsSandboxPath;
    this.timeoutKillDelay = timeoutKillDelay;
    this.localEnvProvider = new WindowsLocalEnvProvider(cmdEnv.getClientEnv());
  }

  @Override
  protected SandboxedSpawn prepareSpawn(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException, InterruptedException {
    Path tmpDir = createActionTemp(execRoot);
    Path commandTmpDir = tmpDir.getRelative("work");
    commandTmpDir.createDirectory();
    ImmutableMap<String, String> environment =
        localEnvProvider.rewriteLocalEnv(
            spawn.getEnvironment(), binTools, commandTmpDir.getPathString());

    SandboxInputs readablePaths =
        helpers.processInputFiles(
            context.getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true),
            execRoot);

    ImmutableSet.Builder<Path> writablePaths = ImmutableSet.builder();
    writablePaths.addAll(getWritableDirs(execRoot, environment));
    for (ActionInput output : spawn.getOutputFiles()) {
      writablePaths.add(execRoot.getRelative(output.getExecPath()));
    }

    Duration timeout = context.getTimeout();

    if (!readablePaths.getSymlinks().isEmpty()) {
      throw new IOException(
          "Windows sandbox does not support unresolved symlinks yet ("
              + Joiner.on(", ").join(readablePaths.getSymlinks().keySet())
              + ")");
    }

    WindowsSandboxUtil.CommandLineBuilder commandLineBuilder =
        WindowsSandboxUtil.commandLineBuilder(windowsSandbox, spawn.getArguments())
            .setWritableFilesAndDirectories(writablePaths.build())
            .setReadableFilesAndDirectories(readablePaths.getFiles())
            .setInaccessiblePaths(getInaccessiblePaths())
            .setUseDebugMode(getSandboxOptions().sandboxDebug)
            .setKillDelay(timeoutKillDelay);

    if (!timeout.isZero()) {
      commandLineBuilder.setTimeout(timeout);
    }

    return new WindowsSandboxedSpawn(
        execRoot, environment, commandLineBuilder.build(), spawn.getMnemonic());
  }

  private static Path createActionTemp(Path execRoot) throws IOException {
    return execRoot.createTempDirectory("windows-sandbox.");
  }

  @Override
  public String getName() {
    return "windows-sandbox";
  }
}
