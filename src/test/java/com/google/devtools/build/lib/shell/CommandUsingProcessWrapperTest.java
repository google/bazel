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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Command}s that are wrapped using the {@code process-wrapper}. */
@RunWith(JUnit4.class)
public final class CommandUsingProcessWrapperTest {

  private final FileSystem fs =
      new UnixFileSystem(DigestHashFunction.SHA256, /* hashAttributeName= */ "");

  private static ProcessWrapper getProcessWrapper() {
    PathFragment path =
        PathFragment.create(BlazeTestUtils.runfilesDir())
            .getRelative(TestConstants.PROCESS_WRAPPER_PATH);
    return new ProcessWrapper(
        path,
        ActionInputHelper.fromPath(path),
        /* killDelay= */ null,
        /* gracefulSigterm= */ false);
  }

  private static String getCpuTimeSpenderPath() {
    return BlazeTestUtils.runfilesDir() + "/" + TestConstants.CPU_TIME_SPENDER_PATH;
  }

  @Test
  public void testCommand_echo() throws Exception {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "worker bees can leave");

    Command command = new Command(commandArguments.toArray(new String[0]));
    CommandResult commandResult = command.execute();

    assertThat(commandResult.terminationStatus().success()).isTrue();
    assertThat(commandResult.stdoutStream().toString()).contains("worker bees can leave");
  }

  @Test
  public void testProcessWrappedCommand_echo() throws Exception {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "even drones can fly away");

    List<String> fullCommandLine = getProcessWrapper().commandLineBuilder(commandArguments).build();

    Command command = new Command(fullCommandLine.toArray(new String[0]));
    CommandResult commandResult = command.execute();

    assertThat(commandResult.terminationStatus().success()).isTrue();
    assertThat(commandResult.stdoutStream().toString()).contains("even drones can fly away");
  }

  private void checkProcessWrapperStatistics(Duration userTimeToSpend, Duration systemTimeToSpend)
      throws IOException, CommandException, InterruptedException {
    ImmutableList<String> commandArguments =
        ImmutableList.of(
            getCpuTimeSpenderPath(),
            Long.toString(userTimeToSpend.toSeconds()),
            Long.toString(systemTimeToSpend.toSeconds()));

    Path outputDir = TestUtils.createUniqueTmpDir(fs);
    Path statisticsFilePath = outputDir.getRelative("stats.out");

    List<String> fullCommandLine =
        getProcessWrapper()
            .commandLineBuilder(commandArguments)
            .setStatisticsPath(statisticsFilePath.asFragment())
            .build();

    ExecutionStatisticsTestUtil.executeCommandAndCheckStatisticsAboutCpuTimeSpent(
        userTimeToSpend, systemTimeToSpend, fullCommandLine, statisticsFilePath);
  }

  @Test
  public void testProcessWrappedCommand_withStatistics_spendUserTime() throws Exception {
    Duration userTimeToSpend = Duration.ofSeconds(10);
    Duration systemTimeToSpend = Duration.ZERO;

    checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend);
  }

  @Test
  public void testProcessWrappedCommand_withStatistics_spendSystemTime() throws Exception {
    Duration userTimeToSpend = Duration.ZERO;
    Duration systemTimeToSpend = Duration.ofSeconds(10);

    checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend);
  }

  @Test
  public void testProcessWrappedCommand_withStatistics_spendUserAndSystemTime() throws Exception {
    Duration userTimeToSpend = Duration.ofSeconds(10);
    Duration systemTimeToSpend = Duration.ofSeconds(10);

    checkProcessWrapperStatistics(userTimeToSpend, systemTimeToSpend);
  }
}
