// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.CommandLines.ExpandedCommandLines;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CommandLines}. */
@RunWith(TestParameterInjector.class)
public class CommandLinesTest {

  private final ArtifactExpander artifactExpander = null;
  private final PathFragment execPath = PathFragment.create("output.txt");
  private static final CommandLineLimits NO_LIMIT = new CommandLineLimits(10000);

  @Test
  public void expand_simpleCommandLine_returnsCorrectCommandLine() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(CommandLine.of(ImmutableList.of("--foo", "--bar")))
            .build();

    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.getParamFiles()).isEmpty();
  }

  @Test
  public void expand_commandLineFromArguments_returnsCorrectCommandLine() throws Exception {
    CommandLines commandLines = CommandLines.of(ImmutableList.of("--foo", "--bar"));

    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.getParamFiles()).isEmpty();
  }

  @Test
  public void expand_concatCommandLines_returnsConcatenatedArguments() throws Exception {
    CommandLines commandLines =
        CommandLines.concat(
            CommandLine.of(ImmutableList.of("--before")),
            CommandLines.of(ImmutableList.of("--foo", "--bar")));

    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("--before", "--foo", "--bar");
    assertThat(expanded.arguments()).containsExactly("--before", "--foo", "--bar");
    assertThat(expanded.getParamFiles()).isEmpty();
  }

  @Test
  public void expand_paramFileUseAlways_returnsCommandLineWithParamFile() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(
                CommandLine.of(ImmutableList.of("--foo", "--bar")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build())
            .build();

    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.arguments()).containsExactly("@output.txt-0.params");
    assertThat(expanded.getParamFiles()).hasSize(1);
    assertThat(expanded.getParamFiles().get(0).getArguments())
        .containsExactly("--foo", "--bar")
        .inOrder();
  }

  @Test
  public void expand_paramFileCommandWithinLimits_returnsNoParamFile() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(
                CommandLine.of(ImmutableList.of("--foo", "--bar")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build())
            .build();

    // Set max length to longer than command line, no param file needed
    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(expanded.arguments()).containsExactly("--foo", "--bar").inOrder();
    assertThat(expanded.getParamFiles()).isEmpty();
  }

  @Test
  public void expand_paramFileCommandOverLimits_returnsParamFile() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(
                CommandLine.of(ImmutableList.of("--foo", "--bar")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build())
            .build();

    // Set max length to 0, spill to param file is forced
    ExpandedCommandLines expanded =
        commandLines.expand(
            artifactExpander, execPath, new CommandLineLimits(0), PathMapper.NOOP, 0);

    assertThat(expanded.arguments()).containsExactly("@output.txt-0.params");
    assertThat(expanded.getParamFiles()).hasSize(1);
    assertThat(expanded.getParamFiles().get(0).getArguments())
        .containsExactly("--foo", "--bar")
        .inOrder();
  }

  @Test
  public void expand_mixOfCommandLinesAndParamFiles_returnsCorrectCommandLines() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(CommandLine.of(ImmutableList.of("a", "b")))
            .addCommandLine(
                CommandLine.of(ImmutableList.of("c", "d")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build())
            .addCommandLine(CommandLine.of(ImmutableList.of("e", "f")))
            .addCommandLine(
                CommandLine.of(ImmutableList.of("g", "h")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(true).build())
            .build();

    ExpandedCommandLines expanded =
        commandLines.expand(artifactExpander, execPath, NO_LIMIT, PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("a", "b", "c", "d", "e", "f", "g", "h");
    assertThat(expanded.arguments())
        .containsExactly("a", "b", "@output.txt-0.params", "e", "f", "@output.txt-1.params");
    assertThat(expanded.getParamFiles()).hasSize(2);
    assertThat(expanded.getParamFiles().get(0).getArguments()).containsExactly("c", "d").inOrder();
    assertThat(expanded.getParamFiles().get(0).getExecPathString())
        .isEqualTo("output.txt-0.params");
    assertThat(expanded.getParamFiles().get(1).getArguments()).containsExactly("g", "h").inOrder();
    assertThat(expanded.getParamFiles().get(1).getExecPathString())
        .isEqualTo("output.txt-1.params");
  }

  @Test
  public void expand_commandsWithParamFilesSecondExceedsLimits_returnsParamFileForSecondOnly()
      throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(
                CommandLine.of(ImmutableList.of("a", "b")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build())
            .addCommandLine(
                CommandLine.of(ImmutableList.of("c", "d")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).setUseAlways(false).build())
            .build();

    ExpandedCommandLines expanded =
        commandLines.expand(
            artifactExpander, execPath, new CommandLineLimits(4), PathMapper.NOOP, 0);

    assertThat(commandLines.allArguments()).containsExactly("a", "b", "c", "d").inOrder();
    assertThat(expanded.arguments()).containsExactly("a", "b", "@output.txt-0.params").inOrder();
    assertThat(expanded.getParamFiles()).hasSize(1);
    assertThat(expanded.getParamFiles().get(0).getArguments()).containsExactly("c", "d").inOrder();
  }

  /** Filtering of flag and positional arguments with flagsOnly. */
  @Test
  public void expand_flagsOnly_movesOnlyDashDashPrefixedFlagsToParamFile() throws Exception {
    CommandLines commandLines =
        CommandLines.builder()
            .addCommandLine(
                CommandLine.of(ImmutableList.of("--a", "1", "--b=c", "-2")),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED)
                    .setUseAlways(true)
                    .setFlagsOnly(true)
                    .build())
            .build();

    ExpandedCommandLines expanded =
        commandLines.expand(
            artifactExpander, execPath, new CommandLineLimits(4), PathMapper.NOOP, 0);
    assertThat(commandLines.allArguments()).containsExactly("--a", "1", "--b=c", "-2");
    assertThat(expanded.arguments()).containsExactly("1", "-2", "@output.txt-0.params");
    assertThat(expanded.getParamFiles()).hasSize(1);
    assertThat(expanded.getParamFiles().get(0).getArguments())
        .containsExactly("--a", "--b=c")
        .inOrder();
  }

  @Test
  public void expand_onlyExecutableArgProcessedForPathMapping(
      @TestParameter({"0", "1", "2", "3"}) int numNonExecutableArgs,
      @TestParameter boolean normalizedExecutablePath,
      @TestParameter boolean mappableNonExecutablePath)
      throws Exception {
    CommandLines.Builder builder = CommandLines.builder();
    String executableArg =
        normalizedExecutablePath
            ? "bazel-out/k8-fastbuild/bin/my_binary"
            : "bazel-out/some/path/../my_binary";
    String nonExecutableArg =
        mappableNonExecutablePath ? "bazel-out/k8-fastbuild/bin/unrelated" : "hello/../world";
    builder.addSingleArgument(executableArg);
    for (int i = 0; i < numNonExecutableArgs; i++) {
      builder.addSingleArgument(nonExecutableArg);
    }
    CommandLines commandLines = builder.build();
    PathMapper pathMapper =
        execPath ->
            execPath.startsWith(PathFragment.create("bazel-out"))
                ? PathFragment.create("mapped").getRelative(execPath)
                : execPath;

    String expectedExecutableArg =
        normalizedExecutablePath ? "mapped/bazel-out/k8-fastbuild/bin/my_binary" : executableArg;
    Iterable<String> expectedArgs =
        Iterables.concat(
            ImmutableList.of(expectedExecutableArg),
            Collections.nCopies(numNonExecutableArgs, nonExecutableArg));
    assertThat(commandLines.allArguments(pathMapper))
        .containsExactlyElementsIn(expectedArgs)
        .inOrder();
  }
}
