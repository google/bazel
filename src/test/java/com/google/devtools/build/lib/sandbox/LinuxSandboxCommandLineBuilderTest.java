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

package com.google.devtools.build.lib.sandbox;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS_WITH_LOOPBACK;
import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NO_NETNS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LinuxSandboxCommandLineBuilderTest}. */
@RunWith(JUnit4.class)
public final class LinuxSandboxCommandLineBuilderTest {
  private FileSystem testFS;

  @Before
  public final void createFileSystem() {
    testFS = new InMemoryFileSystem(DigestHashFunction.SHA256);
  }

  @Test
  public void testLinuxSandboxCommandLineBuilder_fakeRootAndFakeUsernameAreExclusive() {
    Path linuxSandboxPath = testFS.getPath("/linux-sandbox");
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, flo");

    Exception e =
        assertThrows(
            IllegalStateException.class,
            () ->
                LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
                    .setUseFakeRoot(true)
                    .setUseFakeUsername(true)
                    .buildForCommand(commandArguments));
    assertThat(e).hasMessageThat().contains("exclusive");
  }

  @Test
  public void testLinuxSandboxCommandLineBuilder_buildsWithoutOptionalArguments() {
    Path linuxSandboxPath = testFS.getPath("/linux-sandbox");

    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, max");

    ImmutableList<String> expectedCommandLine =
        ImmutableList.<String>builder()
            .add(linuxSandboxPath.getPathString())
            .add("--")
            .addAll(commandArguments)
            .build();

    List<String> commandLine =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
            .buildForCommand(commandArguments);

    assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder();
  }

  @Test
  public void testLinuxSandboxCommandLineBuilder_buildsWithOptionalArguments() {
    Path linuxSandboxPath = testFS.getPath("/linux-sandbox");

    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, tom");

    Duration timeout = Duration.ofSeconds(10);
    Duration killDelay = Duration.ofSeconds(2);

    Path sandboxDebugPath = testFS.getPath("/debug.out");
    Path statisticsPath = testFS.getPath("/stats.out");

    Path workingDirectory = testFS.getPath("/all-work-and-no-play");
    Path stdoutPath = testFS.getPath("/stdout.txt");
    Path stderrPath = testFS.getPath("/stderr.txt");

    // These two flags are exclusive.
    boolean useFakeUsername = true;
    boolean useFakeRoot = false;

    boolean createNetworkNamespace = true;
    boolean useFakeHostname = true;

    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workDir = fileSystem.getPath("/work");
    Path concreteDir = workDir.getRelative("concrete");
    Path sandboxDir = workDir.getRelative("sandbox");

    Path bindMountSource1 = concreteDir.getRelative("bindMountSource1");
    Path bindMountSource2 = concreteDir.getRelative("bindMountSource2");
    Path mountDir = sandboxDir.getRelative("mount");
    Path bindMountTarget1 = mountDir.getRelative("bindMountTarget1");
    Path bindMountTarget2 = mountDir.getRelative("bindMountTarget2");
    Path bindMountSameSourceAndTarget = mountDir.getRelative("bindMountSourceAndTarget");

    Path writableDir1 = sandboxDir.getRelative("writable1");
    Path writableDir2 = sandboxDir.getRelative("writable2");

    PathFragment tmpfsDir1 = sandboxDir.asFragment().getRelative("tmpfs1");
    PathFragment tmpfsDir2 = sandboxDir.asFragment().getRelative("tmpfs2");

    ImmutableSet<Path> writableFilesAndDirectories = ImmutableSet.of(writableDir1, writableDir2);

    ImmutableSet<PathFragment> tmpfsDirectories = ImmutableSet.of(tmpfsDir1, tmpfsDir2);

    ImmutableMap<Path, Path> bindMounts =
        ImmutableSortedMap.<Path, Path>naturalOrder()
            .put(bindMountSameSourceAndTarget, bindMountSameSourceAndTarget)
            .put(bindMountTarget1, bindMountSource1)
            .put(bindMountTarget2, bindMountSource2)
            .buildOrThrow();

    Path cgroupsDir = fileSystem.getPath("/sys/fs/cgroups/something");

    ImmutableList<String> expectedCommandLine =
        ImmutableList.<String>builder()
            .add(linuxSandboxPath.getPathString())
            .add("-W", workingDirectory.getPathString())
            .add("-T", Long.toString(timeout.toSeconds()))
            .add("-t", Long.toString(killDelay.toSeconds()))
            .add("-l", stdoutPath.getPathString())
            .add("-L", stderrPath.getPathString())
            .add("-w", writableDir1.getPathString())
            .add("-w", writableDir2.getPathString())
            .add("-e", tmpfsDir1.getPathString())
            .add("-e", tmpfsDir2.getPathString())
            .add("-M", bindMountSameSourceAndTarget.getPathString())
            .add("-M", bindMountSource1.getPathString())
            .add("-m", bindMountTarget1.getPathString())
            .add("-M", bindMountSource2.getPathString())
            .add("-m", bindMountTarget2.getPathString())
            .add("-S", statisticsPath.getPathString())
            .add("-H")
            .add("-N")
            .add("-U")
            .add("-D", sandboxDebugPath.getPathString())
            .add("-p")
            .add("-C", cgroupsDir.toString())
            .add("--")
            .addAll(commandArguments)
            .build();

    List<String> commandLine =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
            .setWorkingDirectory(workingDirectory)
            .setStdoutPath(stdoutPath)
            .setStderrPath(stderrPath)
            .setTimeout(timeout)
            .setKillDelay(killDelay)
            .setWritableFilesAndDirectories(writableFilesAndDirectories)
            .setTmpfsDirectories(tmpfsDirectories)
            .setBindMounts(bindMounts)
            .setUseFakeHostname(useFakeHostname)
            .setCreateNetworkNamespace(createNetworkNamespace ? NETNS_WITH_LOOPBACK : NO_NETNS)
            .setUseFakeRoot(useFakeRoot)
            .setStatisticsPath(statisticsPath)
            .setUseFakeUsername(useFakeUsername)
            .setSandboxDebugPath(sandboxDebugPath.getPathString())
            .setPersistentProcess(true)
            .setCgroupsDirs(ImmutableSet.of(cgroupsDir.getPathFile().toPath()))
            .buildForCommand(commandArguments);

    assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder();
  }
}
