// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.SubprocessBuilder.StreamAction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Utility functions for the {@code windows-sandbox}. */
public final class WindowsSandboxUtil {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * Checks if the given Windows sandbox binary is available and is valid.
   *
   * @param binary path to the Windows sandbox binary
   * @return true if the binary looks good, false otherwise
   */
  public static boolean isAvailable(PathFragment binary) {
    Subprocess process;
    try {
      process =
          new SubprocessBuilder()
              .setArgv(ImmutableList.of(binary.getPathString(), "-h"))
              .setStdout(StreamAction.STREAM)
              .redirectErrorStream(true)
              .setWorkingDirectory(new File("."))
              .start();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Windows sandbox binary at %s seems to be missing", binary);
      return false;
    }

    ByteArrayOutputStream outErrBytes = new ByteArrayOutputStream();
    try {
      ByteStreams.copy(process.getInputStream(), outErrBytes);
    } catch (IOException e) {
      try {
        outErrBytes.write(("Failed to read stdout: " + e).getBytes(StandardCharsets.UTF_8));
      } catch (IOException e2) {
        // Should not really have happened. There is nothing we can do.
      }
    }
    String outErr = outErrBytes.toString().replaceFirst("\n$", "");

    process.waitForUninterruptibly();
    int exitCode = process.exitValue();
    if (exitCode == 0) {
      // TODO(rongjiecomputer): Validate the version number and ensure we support it. Would be nice
      // to reuse
      // the DottedVersion logic from the Apple rules.
      return true;
    } else {
      logger.atWarning().log(
          "Windows sandbox binary at %s returned non-zero exit code %d and output %s",
          binary, exitCode, outErr);
      return false;
    }
  }

  /** Returns a new command line builder for the {@code windows-sandbox} tool. */
  public static CommandLineBuilder commandLineBuilder(
      PathFragment windowsSandboxPath, List<String> commandArguments) {
    return new CommandLineBuilder(windowsSandboxPath, commandArguments);
  }

  /**
   * A builder class for constructing the full command line to run a command using the {@code
   * windows-sandbox} tool.
   */
  public static class CommandLineBuilder {
    private final PathFragment windowsSandboxPath;
    private Path workingDirectory;
    private Duration timeout;
    private Duration killDelay;
    private Path stdoutPath;
    private Path stderrPath;
    private Set<Path> writableFilesAndDirectories = ImmutableSet.of();
    private Map<PathFragment, Path> readableFilesAndDirectories = new TreeMap<>();
    private Set<Path> inaccessiblePaths = ImmutableSet.of();
    private boolean useDebugMode = false;
    private List<String> commandArguments = ImmutableList.of();

    private CommandLineBuilder(PathFragment windowsSandboxPath, List<String> commandArguments) {
      this.windowsSandboxPath = windowsSandboxPath;
      this.commandArguments = commandArguments;
    }

    /** Sets the working directory to use, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setWorkingDirectory(Path workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /** Sets the timeout for the command run using the {@code windows-sandbox} tool. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setTimeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Sets the kill delay for commands run using the {@code windows-sandbox} tool that exceed their
     * timeout.
     */
    @CanIgnoreReturnValue
    public CommandLineBuilder setKillDelay(Duration killDelay) {
      this.killDelay = killDelay;
      return this;
    }

    /** Sets the path to use for redirecting stdout, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setStdoutPath(Path stdoutPath) {
      this.stdoutPath = stdoutPath;
      return this;
    }

    /** Sets the path to use for redirecting stderr, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setStderrPath(Path stderrPath) {
      this.stderrPath = stderrPath;
      return this;
    }

    /** Sets the files or directories to make writable for the sandboxed process, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setWritableFilesAndDirectories(
        Set<Path> writableFilesAndDirectories) {
      this.writableFilesAndDirectories = writableFilesAndDirectories;
      return this;
    }

    /** Sets the files or directories to make readable for the sandboxed process, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setReadableFilesAndDirectories(
        Map<PathFragment, Path> readableFilesAndDirectories) {
      this.readableFilesAndDirectories = readableFilesAndDirectories;
      return this;
    }

    /** Sets the files or directories to make inaccessible for the sandboxed process, if any. */
    @CanIgnoreReturnValue
    public CommandLineBuilder setInaccessiblePaths(Set<Path> inaccessiblePaths) {
      this.inaccessiblePaths = inaccessiblePaths;
      return this;
    }

    /** Sets whether to enable debug mode (e.g. to print debugging messages). */
    @CanIgnoreReturnValue
    public CommandLineBuilder setUseDebugMode(boolean useDebugMode) {
      this.useDebugMode = useDebugMode;
      return this;
    }

    /**
     * Builds the command line to invoke a specific command using the {@code windows-sandbox} tool.
     */
    public ImmutableList<String> build() {
      Preconditions.checkNotNull(this.windowsSandboxPath, "windowsSandboxPath is required");
      Preconditions.checkState(!this.commandArguments.isEmpty(), "commandArguments are required");

      ImmutableList.Builder<String> commandLineBuilder = ImmutableList.builder();

      commandLineBuilder.add(windowsSandboxPath.getPathString());
      if (workingDirectory != null) {
        commandLineBuilder.add("-W", workingDirectory.getPathString());
      }
      if (timeout != null) {
        commandLineBuilder.add("-T", Long.toString(timeout.toSeconds()));
      }
      if (killDelay != null) {
        commandLineBuilder.add("-t", Long.toString(killDelay.toSeconds()));
      }
      if (stdoutPath != null) {
        commandLineBuilder.add("-l", stdoutPath.getPathString());
      }
      if (stderrPath != null) {
        commandLineBuilder.add("-L", stderrPath.getPathString());
      }
      for (Path writablePath : writableFilesAndDirectories) {
        commandLineBuilder.add("-w", writablePath.getPathString());
      }
      for (Path readablePath : readableFilesAndDirectories.values()) {
        commandLineBuilder.add("-r", readablePath.getPathString());
      }
      for (Path writablePath : inaccessiblePaths) {
        commandLineBuilder.add("-b", writablePath.getPathString());
      }
      if (useDebugMode) {
        commandLineBuilder.add("-D");
      }
      commandLineBuilder.add("--");
      commandLineBuilder.addAll(commandArguments);

      return commandLineBuilder.build();
    }
  }
}
