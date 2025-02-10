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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.PathStrippable;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * A class that keeps a list of command lines and optional associated parameter file info.
 *
 * <p>This class is used by {@link com.google.devtools.build.lib.exec.SpawnRunner} implementations
 * to expand the command lines into a master argument list + any param files needed to be written.
 */
public abstract sealed class CommandLines {

  // A (hopefully) conservative estimate of how much long each param file arg would be
  // eg. the length of '@path/to/param_file'.
  private static final int PARAM_FILE_ARG_LENGTH_ESTIMATE = 512;
  private static final UUID PARAM_FILE_UUID =
      UUID.fromString("106c1389-88d7-4cc1-8f05-f8a61fd8f7b1");

  /** A simple tuple of a {@link CommandLine} and a {@link ParamFileInfo}. */
  public static class CommandLineAndParamFileInfo {
    public final CommandLine commandLine;
    @Nullable public final ParamFileInfo paramFileInfo;

    public CommandLineAndParamFileInfo(
        CommandLine commandLine, @Nullable ParamFileInfo paramFileInfo) {
      this.commandLine = commandLine;
      this.paramFileInfo = paramFileInfo;
    }
  }

  private CommandLines() {}

  /**
   * Expands this object into a single primary command line and (0-N) param files. The spawn runner
   * is expected to write these param files prior to execution of an action.
   *
   * @param artifactExpander The artifact expander to use.
   * @param paramFileBasePath Used to derive param file names. Often the first output of an action
   * @param pathMapper function to map configuration prefixes in output paths to more cache-friendly
   *     identifiers
   * @param limits The command line limits the host OS can support.
   * @return The expanded command line and its param files (if any).
   */
  public ExpandedCommandLines expand(
      ArtifactExpander artifactExpander,
      PathFragment paramFileBasePath,
      PathMapper pathMapper,
      CommandLineLimits limits)
      throws CommandLineExpansionException, InterruptedException {
    return expand(
        artifactExpander, paramFileBasePath, limits, pathMapper, PARAM_FILE_ARG_LENGTH_ESTIMATE);
  }

  @VisibleForTesting
  ExpandedCommandLines expand(
      ArtifactExpander artifactExpander,
      PathFragment paramFileBasePath,
      CommandLineLimits limits,
      PathMapper pathMapper,
      int paramFileArgLengthEstimate)
      throws CommandLineExpansionException, InterruptedException {
    ImmutableList<CommandLineAndParamFileInfo> commandLines = unpack();
    ImmutableList.Builder<String> arguments = ImmutableList.builder();
    ArrayList<ParamFileActionInput> paramFiles = new ArrayList<>(commandLines.size());
    int conservativeMaxLength = limits.maxLength - commandLines.size() * paramFileArgLengthEstimate;
    int cmdLineLength = 0;
    // We name based on the output, starting at <output>-0.params and then incrementing
    int paramFileNameSuffix = 0;
    for (CommandLineAndParamFileInfo pair : commandLines) {
      CommandLine commandLine = pair.commandLine;
      ParamFileInfo paramFileInfo = pair.paramFileInfo;
      ArgChunk chunk = commandLine.expand(artifactExpander, pathMapper);
      if (paramFileInfo == null) {
        arguments.addAll(chunk.arguments());
        cmdLineLength += chunk.totalArgLength();
      } else {
        boolean useParamFile = true;
        if (!paramFileInfo.always()) {
          int tentativeCmdLineLength = cmdLineLength + chunk.totalArgLength();
          if (tentativeCmdLineLength <= conservativeMaxLength) {
            arguments.addAll(chunk.arguments());
            cmdLineLength = tentativeCmdLineLength;
            useParamFile = false;
          }
        }
        if (useParamFile) {
          PathFragment paramFileExecPath =
              ParameterFile.derivePath(paramFileBasePath, Integer.toString(paramFileNameSuffix));
          ++paramFileNameSuffix;

          String paramArg =
              SingleStringArgFormatter.format(
                  paramFileInfo.getFlagFormatString(),
                  pathMapper.map(paramFileExecPath).getPathString());
          arguments.add(paramArg);
          cmdLineLength += paramArg.length() + 1;

          if (paramFileInfo.flagsOnly()) {
            // Move just the flags into the file, and keep the positional parameters on the command
            // line.
            paramFiles.add(
                new ParamFileActionInput(
                    paramFileExecPath,
                    ParameterFile.flagsOnly(chunk.arguments()),
                    paramFileInfo.getFileType()));
            for (String positionalArg : ParameterFile.nonFlags(chunk.arguments())) {
              arguments.add(positionalArg);
              cmdLineLength += positionalArg.length() + 1;
            }
          } else {
            paramFiles.add(
                new ParamFileActionInput(
                    paramFileExecPath, chunk.arguments(), paramFileInfo.getFileType()));
          }
        }
      }
    }
    return new ExpandedCommandLines(arguments.build(), paramFiles);
  }

  /**
   * Returns all arguments, including ones inside of param files.
   *
   * <p>Suitable for debugging and printing messages to users. This expands all command lines, so it
   * is potentially expensive.
   */
  public ImmutableList<String> allArguments()
      throws CommandLineExpansionException, InterruptedException {
    return allArguments(PathMapper.NOOP);
  }

  /** Variation of {@link #allArguments()} that supports output path stripping. */
  public ImmutableList<String> allArguments(PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    ImmutableList.Builder<String> arguments = ImmutableList.builder();
    for (CommandLineAndParamFileInfo pair : unpack()) {
      arguments.addAll(pair.commandLine.arguments(/* artifactExpander= */ null, pathMapper));
    }
    return arguments.build();
  }

  public void addToFingerprint(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      CoreOptions.OutputPathsMode effectiveOutputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException {
    ImmutableList<CommandLineAndParamFileInfo> commandLines = unpack();
    for (CommandLineAndParamFileInfo pair : commandLines) {
      CommandLine commandLine = pair.commandLine;
      ParamFileInfo paramFileInfo = pair.paramFileInfo;
      commandLine.addToFingerprint(
          actionKeyContext, artifactExpander, effectiveOutputPathsMode, fingerprint);
      if (paramFileInfo != null) {
        addParamFileInfoToFingerprint(paramFileInfo, fingerprint);
      }
    }
  }

  /**
   * Expanded command lines.
   *
   * <p>The spawn runner implementation is expected to ensure the param files are available once the
   * spawn is executed.
   */
  public static class ExpandedCommandLines {
    private final ImmutableList<String> arguments;
    private final List<ParamFileActionInput> paramFiles;

    ExpandedCommandLines(ImmutableList<String> arguments, List<ParamFileActionInput> paramFiles) {
      this.arguments = arguments;
      this.paramFiles = paramFiles;
    }

    /** Returns the primary command line of the command. */
    public ImmutableList<String> arguments() {
      return arguments;
    }

    /** Returns the param file action inputs needed to execute the command. */
    public List<ParamFileActionInput> getParamFiles() {
      return paramFiles;
    }
  }

  /** An in-memory param file virtual action input. */
  public static final class ParamFileActionInput extends VirtualActionInput {
    private final PathFragment paramFileExecPath;
    private final Iterable<String> arguments;
    private final ParameterFileType type;

    public ParamFileActionInput(
        PathFragment paramFileExecPath, Iterable<String> arguments, ParameterFileType type) {
      this.paramFileExecPath = paramFileExecPath;
      this.arguments = arguments;
      this.type = type;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
      ParameterFile.writeParameterFile(out, arguments, type);
    }

    @Override
    @CanIgnoreReturnValue
    public byte[] atomicallyWriteTo(Path outputPath) throws IOException {
      // This is needed for internal path wrangling reasons :(
      return super.atomicallyWriteTo(outputPath);
    }

    @Override
    public String getExecPathString() {
      return paramFileExecPath.getPathString();
    }

    @Override
    public PathFragment getExecPath() {
      return paramFileExecPath;
    }

    public Iterable<String> getArguments() {
      return arguments;
    }
  }

  /**
   * Unpacks the optimized storage format into a list of {@link CommandLineAndParamFileInfo}.
   *
   * <p>The returned {@link ImmutableList} and its {@link CommandLineAndParamFileInfo} elements are
   * not part of the optimized storage representation. Retaining them in an action would defeat the
   * memory optimizations made by {@link CommandLines}.
   */
  public abstract ImmutableList<CommandLineAndParamFileInfo> unpack();

  private static void addParamFileInfoToFingerprint(
      ParamFileInfo paramFileInfo, Fingerprint fingerprint) {
    fingerprint.addUUID(PARAM_FILE_UUID);
    fingerprint.addString(paramFileInfo.getFlagFormatString());
    fingerprint.addString(paramFileInfo.getFileType().toString());
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns an instance with a single command line. */
  public static CommandLines of(CommandLine commandLine) {
    return new OnePartCommandLines(commandLine);
  }

  /** Returns an instance with a single trivial command line. */
  public static CommandLines of(ImmutableList<String> args) {
    return new OnePartCommandLines(CommandLine.of(args));
  }

  public static CommandLines concat(CommandLine commandLine, CommandLines commandLines) {
    Builder builder = builder();
    builder.addCommandLine(commandLine);
    for (CommandLineAndParamFileInfo pair : commandLines.unpack()) {
      builder.addCommandLine(pair);
    }
    return builder.build();
  }

  /**
   * Builder for {@link CommandLines}.
   *
   * <p>Attempts to build the most memory-efficient {@link CommandLines} instance possible. Most
   * command lines are composed of 1-3 parts. Additionally, the first part is typically just an
   * executable or shell command and does not have an associated params file. If both of these
   * criteria are met, memory is saved by using one of the array-free subclasses. Otherwise, uses
   * {@link NPartCommandLines} which handles any arbitrary case.
   */
  public static class Builder {
    private Object part1; // Set to null when we need to use NPartCommandLines.
    private Object part2;
    private ParamFileInfo part2ParamFileInfo;
    private Object part3;
    private ParamFileInfo part3ParamFileInfo;
    private int parts = 0;
    private final List<Object> commandLines = new ArrayList<>();

    @CanIgnoreReturnValue
    public Builder addSingleArgument(Object argument) {
      checkArgument(
          !(argument instanceof ParamFileInfo)
              && !(argument instanceof CommandLineAndParamFileInfo),
          argument);
      return addInternal(argument, null);
    }

    @CanIgnoreReturnValue
    public Builder addCommandLine(CommandLine commandLine) {
      return addInternal(commandLine, null);
    }

    @CanIgnoreReturnValue
    public Builder addCommandLine(CommandLine commandLine, @Nullable ParamFileInfo paramFileInfo) {
      return addInternal(commandLine, paramFileInfo);
    }

    @CanIgnoreReturnValue
    public Builder addCommandLine(CommandLineAndParamFileInfo pair) {
      return addInternal(pair.commandLine, pair.paramFileInfo);
    }

    private Builder addInternal(Object part, @Nullable ParamFileInfo paramFileInfo) {
      parts++;
      if (parts == 1) {
        if (paramFileInfo == null) {
          part1 = part;
        }
      } else if (parts == 2) {
        part2 = part;
        part2ParamFileInfo = paramFileInfo;
      } else if (parts == 3) {
        part3 = part;
        part3ParamFileInfo = paramFileInfo;
      } else if (parts == 4) {
        part1 = null; // Destined to build an NPartCommandLines.
      }
      commandLines.add(part);
      if (paramFileInfo != null) {
        commandLines.add(paramFileInfo);
      }
      return this;
    }

    public CommandLines build() {
      if (part1 == null) {
        return new NPartCommandLines(commandLines.toArray());
      }
      if (parts == 1) {
        return new OnePartCommandLines(part1);
      }
      if (parts == 2) {
        return new TwoPartCommandLines(part1, part2, part2ParamFileInfo);
      }
      if (part2ParamFileInfo == null && part3ParamFileInfo == null) {
        return new ThreePartCommandLinesWithoutParamsFiles(part1, part2, part3);
      }
      return new ThreePartCommandLines(part1, part2, part2ParamFileInfo, part3, part3ParamFileInfo);
    }
  }

  private static CommandLine toExecutableCommandLine(Object obj) {
    return toCommandLine(obj, /* hasExecutablePath= */ true);
  }

  private static CommandLine toNonExecutableCommandLine(Object obj) {
    return toCommandLine(obj, /* hasExecutablePath= */ false);
  }

  private static CommandLine toCommandLine(Object obj, boolean hasExecutablePath) {
    return obj instanceof CommandLine commandLine
        ? commandLine
        : new SingletonCommandLine(obj, hasExecutablePath);
  }

  private static final class OnePartCommandLines extends CommandLines {
    private final Object part1;

    OnePartCommandLines(Object part1) {
      this.part1 = part1;
    }

    @Override
    public ImmutableList<CommandLineAndParamFileInfo> unpack() {
      return ImmutableList.of(
          new CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null));
    }
  }

  private static final class TwoPartCommandLines extends CommandLines {
    private final Object part1;
    private final Object part2;
    @Nullable private final ParamFileInfo part2ParamFileInfo;

    TwoPartCommandLines(Object part1, Object part2, @Nullable ParamFileInfo part2ParamFileInfo) {
      this.part1 = part1;
      this.part2 = part2;
      this.part2ParamFileInfo = part2ParamFileInfo;
    }

    @Override
    public ImmutableList<CommandLineAndParamFileInfo> unpack() {
      return ImmutableList.of(
          new CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
          new CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), part2ParamFileInfo));
    }
  }

  private static final class ThreePartCommandLinesWithoutParamsFiles extends CommandLines {
    private final Object part1;
    private final Object part2;
    private final Object part3;

    ThreePartCommandLinesWithoutParamsFiles(Object part1, Object part2, Object part3) {
      this.part1 = part1;
      this.part2 = part2;
      this.part3 = part3;
    }

    @Override
    public ImmutableList<CommandLineAndParamFileInfo> unpack() {
      return ImmutableList.of(
          new CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
          new CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), null),
          new CommandLineAndParamFileInfo(toNonExecutableCommandLine(part3), null));
    }
  }

  private static final class ThreePartCommandLines extends CommandLines {
    private final Object part1;
    private final Object part2;
    @Nullable private final ParamFileInfo part2ParamFileInfo;
    private final Object part3;
    @Nullable private final ParamFileInfo part3ParamFileInfo;

    ThreePartCommandLines(
        Object part1,
        Object part2,
        @Nullable ParamFileInfo part2ParamFileInfo,
        Object part3,
        @Nullable ParamFileInfo part3ParamFileInfo) {
      this.part1 = part1;
      this.part2 = part2;
      this.part2ParamFileInfo = part2ParamFileInfo;
      this.part3 = part3;
      this.part3ParamFileInfo = part3ParamFileInfo;
    }

    @Override
    public ImmutableList<CommandLineAndParamFileInfo> unpack() {
      return ImmutableList.of(
          new CommandLineAndParamFileInfo(toExecutableCommandLine(part1), null),
          new CommandLineAndParamFileInfo(toNonExecutableCommandLine(part2), part2ParamFileInfo),
          new CommandLineAndParamFileInfo(toNonExecutableCommandLine(part3), part3ParamFileInfo));
    }
  }

  private static final class NPartCommandLines extends CommandLines {

    /**
     * Stored as an {@code Object[]} to save memory. Elements in this array are either:
     *
     * <ul>
     *   <li>A {@link CommandLine}, optionally followed by a {@link ParamFileInfo}.
     *   <li>An arbitrary {@link Object} to be wrapped in a {@link SingletonCommandLine}.
     * </ul>
     */
    private final Object[] commandLines;

    NPartCommandLines(Object[] commandLines) {
      this.commandLines = commandLines;
    }

    @Override
    public ImmutableList<CommandLineAndParamFileInfo> unpack() {
      ImmutableList.Builder<CommandLineAndParamFileInfo> result = ImmutableList.builder();
      for (int i = 0; i < commandLines.length; i++) {
        Object obj = commandLines[i];
        CommandLine commandLine;
        ParamFileInfo paramFileInfo = null;

        if (obj instanceof CommandLine c) {
          commandLine = c;
          if (i + 1 < commandLines.length && commandLines[i + 1] instanceof ParamFileInfo) {
            paramFileInfo = (ParamFileInfo) commandLines[++i];
          }
        } else {
          commandLine = new SingletonCommandLine(obj, /* hasExecutablePath= */ i == 0);
        }

        result.add(new CommandLineAndParamFileInfo(commandLine, paramFileInfo));
      }
      return result.build();
    }
  }

  private static class SingletonCommandLine extends AbstractCommandLine {
    private final Object arg;
    private final boolean hasExecutablePath;

    SingletonCommandLine(Object arg, boolean hasExecutablePath) {
      this.arg = arg;
      this.hasExecutablePath = hasExecutablePath;
    }

    @Override
    public Iterable<String> arguments() {
      return arguments(null, PathMapper.NOOP);
    }

    @Override
    public Iterable<String> arguments(
        @Nullable ArtifactExpander artifactExpander, PathMapper pathMapper) {
      return ImmutableList.of(
          switch (arg) {
            case PathStrippable ps -> ps.expand(pathMapper::map);
            // StarlarkAction stores the executable path as a string to save memory, but it should
            // still be mapped just like a PathFragment. In this case, the string always represents
            // a normalized path, so if it isn't (e.g. because it is an absolute path, possibly for
            // another OS), don't normalize or map it.
            case String s when hasExecutablePath -> {
              PathFragment pathFragment = PathFragment.create(s);
              if (!pathFragment.getPathString().equals(s)) {
                yield s;
              }
              yield pathMapper.map(pathFragment).getPathString();
            }
            default -> CommandLineItem.expandToCommandLine(arg);
          });
    }
  }
}
