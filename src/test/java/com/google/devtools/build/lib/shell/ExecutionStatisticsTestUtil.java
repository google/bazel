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

import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Utilities to assist with testing execution statistics generated via the {@code process-wrapper}
 * and {@code linux-sandbox} tools.
 */
public class ExecutionStatisticsTestUtil {
  /**
   * Executes a command and checks that the execution statistics timing info for that command
   * satisfy certain constraints.
   *
   * @param userTimeToSpend a lower bound for how much CPU user execution time was expected
   * @param systemTimeToSpend a lower bound for how much CPU system execution time was expected
   * @param fullCommandLine the command to execute, including any wrappers used (like linux-sandbox)
   * @param statisticsFilePath where the execution statistics file will be generated (to be read)
   */
  public static void executeCommandAndCheckStatisticsAboutCpuTimeSpent(
      Duration userTimeToSpend,
      Duration systemTimeToSpend,
      List<String> fullCommandLine,
      Path statisticsFilePath)
      throws CommandException, IOException, InterruptedException {
    Duration userTimeLowerBound = userTimeToSpend;
    Duration userTimeUpperBound = userTimeToSpend.plusSeconds(9);
    Duration systemTimeLowerBound = systemTimeToSpend;

    // TODO(b/110456205) This check fails under very heavy load, investigate why and re-enable it
    // Duration systemTimeUpperBound = systemTimeToSpend.plusSeconds(9);

    Command command = new Command(fullCommandLine.toArray(new String[0]));
    CommandResult commandResult = command.execute();
    assertThat(commandResult.terminationStatus().success()).isTrue();

    Optional<ExecutionStatistics.ResourceUsage> resourceUsage =
        ExecutionStatistics.getResourceUsage(statisticsFilePath);
    assertThat(resourceUsage).isPresent();

    Duration userTime = resourceUsage.get().getUserExecutionTime();
    assertThat(userTime).isAtLeast(userTimeLowerBound);
    assertThat(userTime).isAtMost(userTimeUpperBound);

    Duration systemTime = resourceUsage.get().getSystemExecutionTime();
    assertThat(systemTime).isAtLeast(systemTimeLowerBound);

    // TODO(b/110456205) This check fails under very heavy load, investigate why and re-enable it
    // assertThat(systemTime).isAtMost(systemTimeUpperBound);
  }
}
