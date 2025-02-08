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

package com.google.devtools.build.lib.shell;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Command}s that are run using the {@code linux-sandbox}. */
@RunWith(JUnit4.class)
public final class CommandUsingLinuxSandboxTest {
  private FileSystem testFS;
  private Path runfilesDir;

  @Before
  public final void createFileSystem() {
    testFS = new UnixFileSystem(DigestHashFunction.SHA256, /*hashAttributeName=*/ "");
    runfilesDir = testFS.getPath(BlazeTestUtils.runfilesDir());
  }

  private Path getLinuxSandboxPath() {
    return runfilesDir.getRelative(TestConstants.LINUX_SANDBOX_PATH);
  }

  private Path getCpuTimeSpenderPath() {
    return runfilesDir.getRelative(TestConstants.CPU_TIME_SPENDER_PATH);
  }

  @Test
  public void testCommand_echo() throws Exception {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "colorless green ideas");

    Command command = new Command(commandArguments.toArray(new String[0]));
    CommandResult commandResult = command.execute();

    assertThat(commandResult.terminationStatus().success()).isTrue();
    assertThat(commandResult.stdoutStream().toString()).contains("colorless green ideas");
  }

  @Test
  public void testLinuxSandboxedCommand_echo() throws Exception {
    // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);
    // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
    assumeTrue(OS.getCurrent() != OS.DARWIN);

    ImmutableList<String> commandArguments = ImmutableList.of("echo", "sleep furiously");

    List<String> fullCommandLine =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(getLinuxSandboxPath())
            .buildForCommand(commandArguments);

    Command command = new Command(fullCommandLine.toArray(new String[0]));
    CommandResult commandResult = command.execute();

    assertThat(commandResult.terminationStatus().success()).isTrue();
    assertThat(commandResult.stdoutStream().toString()).contains("sleep furiously");
  }

  private void checkLinuxSandboxStatistics(Duration userTimeToSpend, Duration systemTimeToSpend)
      throws IOException, CommandException, InterruptedException {
    ImmutableList<String> commandArguments =
        ImmutableList.of(
            getCpuTimeSpenderPath().getPathString(),
            Long.toString(userTimeToSpend.toSeconds()),
            Long.toString(systemTimeToSpend.toSeconds()));

    Path outputDir = TestUtils.createUniqueTmpDir(testFS);
    Path statisticsFilePath = outputDir.getRelative("stats.out");

    List<String> fullCommandLine =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(getLinuxSandboxPath())
            .setStatisticsPath(statisticsFilePath)
            .buildForCommand(commandArguments);

    ExecutionStatisticsTestUtil.executeCommandAndCheckStatisticsAboutCpuTimeSpent(
        userTimeToSpend, systemTimeToSpend, fullCommandLine, statisticsFilePath);
  }

  @Test
  public void testLinuxSandboxedCommand_withStatistics_spendUserTime()
      throws CommandException, IOException, InterruptedException {
    // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);
    // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
    assumeTrue(OS.getCurrent() != OS.DARWIN);

    Duration userTimeToSpend = Duration.ofSeconds(10);
    Duration systemTimeToSpend = Duration.ZERO;

    checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend);
  }

  @Test
  public void testLinuxSandboxedCommand_withStatistics_spendSystemTime()
      throws CommandException, IOException, InterruptedException {
    // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);
    // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
    assumeTrue(OS.getCurrent() != OS.DARWIN);

    Duration userTimeToSpend = Duration.ZERO;
    Duration systemTimeToSpend = Duration.ofSeconds(10);

    checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend);
  }

  @Test
  public void testLinuxSandboxedCommand_withStatistics_spendUserAndSystemTime()
      throws CommandException, IOException, InterruptedException {
    // TODO(b/62588075) Currently no linux-sandbox tool support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);
    // TODO(b/62588075) Currently no linux-sandbox tool support in MacOS.
    assumeTrue(OS.getCurrent() != OS.DARWIN);

    Duration userTimeToSpend = Duration.ofSeconds(10);
    Duration systemTimeToSpend = Duration.ofSeconds(10);

    checkLinuxSandboxStatistics(userTimeToSpend, systemTimeToSpend);
  }
}
