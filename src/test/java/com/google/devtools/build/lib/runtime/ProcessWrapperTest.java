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

package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProcessWrapper}. */
@RunWith(JUnit4.class)
public final class ProcessWrapperTest {

  @Test
  public void testProcessWrapperCommandLineBuilder_buildsWithoutOptionalArguments() {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, world");

    ImmutableList<String> expectedCommandLine =
        ImmutableList.<String>builder().add("/some/path").addAll(commandArguments).build();

    ProcessWrapper processWrapper =
        new ProcessWrapper(
            PathFragment.create("/some/path"),
            ActionInputHelper.fromPath("/some/path"),
            /* killDelay= */ null,
            /* gracefulSigterm= */ false);
    List<String> commandLine = processWrapper.commandLineBuilder(commandArguments).build();

    assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder();
  }

  @Test
  public void testProcessWrapperCommandLineBuilder_buildsWithOptionalArguments() {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, world");

    Duration timeout = Duration.ofSeconds(10);
    Duration killDelay = Duration.ofSeconds(2);
    PathFragment overrideProcessWrapperPath = PathFragment.create("/override/process-wrapper");
    PathFragment stdoutPath = PathFragment.create("/stdout.txt");
    PathFragment stderrPath = PathFragment.create("/stderr.txt");
    PathFragment statisticsPath = PathFragment.create("/stats.out");

    ImmutableList<String> expectedCommandLine =
        ImmutableList.<String>builder()
            .add(overrideProcessWrapperPath.getPathString())
            .add("--timeout=" + timeout.toSeconds())
            .add("--kill_delay=" + killDelay.toSeconds())
            .add("--stdout=" + stdoutPath)
            .add("--stderr=" + stderrPath)
            .add("--stats=" + statisticsPath)
            .add("--graceful_sigterm")
            .addAll(commandArguments)
            .build();

    ProcessWrapper processWrapper =
        new ProcessWrapper(
            PathFragment.create("/path/process-wrapper"),
            ActionInputHelper.fromPath("/path/process-wrapper"),
            killDelay,
            /* gracefulSigterm= */ true);

    List<String> commandLine =
        processWrapper
            .commandLineBuilder(commandArguments)
            .overrideProcessWrapperPath(overrideProcessWrapperPath)
            .setTimeout(timeout)
            .setStdoutPath(stdoutPath)
            .setStderrPath(stderrPath)
            .setStatisticsPath(statisticsPath)
            .build();

    assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder();
  }

  @Test
  public void testProcessWrapperCommandLineBuilder_withExecutionInfo() {
    ImmutableList<String> commandArguments = ImmutableList.of("echo", "hello, world");

    ProcessWrapper processWrapper =
        new ProcessWrapper(
            PathFragment.create("/some/path"),
            ActionInputHelper.fromPath("/some/path"),
            /* killDelay= */ null,
            /* gracefulSigterm= */ false);
    ProcessWrapper.CommandLineBuilder builder = processWrapper.commandLineBuilder(commandArguments);

    ImmutableList<String> expectedWithoutExecutionInfo =
        ImmutableList.<String>builder().add("/some/path").addAll(commandArguments).build();
    assertThat(builder.build()).containsExactlyElementsIn(expectedWithoutExecutionInfo).inOrder();

    ImmutableList<String> expectedWithExecutionInfo =
        ImmutableList.<String>builder()
            .add("/some/path")
            .add("--graceful_sigterm")
            .addAll(commandArguments)
            .build();
    builder.addExecutionInfo(ImmutableMap.of(ExecutionRequirements.GRACEFUL_TERMINATION, "1"));
    assertThat(builder.build()).containsExactlyElementsIn(expectedWithExecutionInfo).inOrder();
  }
}
