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

import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS;
import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS_WITH_LOOPBACK;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A builder class for constructing the full command line to run a command using the {@code
 * linux-sandbox} tool.
 */
public class LinuxSandboxCommandLineBuilder {
  private final Path linuxSandboxPath;
  private Path hermeticSandboxPath;
  private Path workingDirectory;
  private Duration timeout;
  private Duration killDelay;
  private boolean persistentProcess;
  private Path stdoutPath;
  private Path stderrPath;
  private Set<Path> writableFilesAndDirectories = ImmutableSet.of();
  private ImmutableSet<PathFragment> tmpfsDirectories = ImmutableSet.of();
  private Map<Path, Path> bindMounts = ImmutableMap.of();
  private Path statisticsPath;
  private boolean useFakeHostname = false;
  private NetworkNamespace createNetworkNamespace = NetworkNamespace.NO_NETNS;
  private boolean useFakeRoot = false;
  private boolean useFakeUsername = false;
  private boolean enablePseudoterminal = false;
  private String sandboxDebugPath = null;
  private boolean sigintSendsSigterm = false;
  private Set<java.nio.file.Path> cgroupsDirs = ImmutableSet.of();

  private LinuxSandboxCommandLineBuilder(Path linuxSandboxPath) {
    this.linuxSandboxPath = linuxSandboxPath;
  }

  /** Returns a new command line builder for the {@code linux-sandbox} tool. */
  public static LinuxSandboxCommandLineBuilder commandLineBuilder(Path linuxSandboxPath) {
    return new LinuxSandboxCommandLineBuilder(linuxSandboxPath);
  }

  /**
   * Sets the sandbox path to chroot to, required for the hermetic linux sandbox to figure out where
   * the working directory is.
   */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setHermeticSandboxPath(Path sandboxPath) {
    this.hermeticSandboxPath = sandboxPath;
    return this;
  }

  /** Sets the working directory to use, if any. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setWorkingDirectory(Path workingDirectory) {
    this.workingDirectory = workingDirectory;
    return this;
  }

  /** Sets the timeout for the command run using the {@code linux-sandbox} tool. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setTimeout(Duration timeout) {
    this.timeout = timeout;
    return this;
  }

  /**
   * Sets the kill delay for commands run using the {@code linux-sandbox} tool that exceed their
   * timeout.
   */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setKillDelay(Duration killDelay) {
    this.killDelay = killDelay;
    return this;
  }

  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setPersistentProcess(boolean persistentProcess) {
    this.persistentProcess = persistentProcess;
    return this;
  }

  /** Sets the path to use for redirecting stdout, if any. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setStdoutPath(Path stdoutPath) {
    this.stdoutPath = stdoutPath;
    return this;
  }

  /** Sets the path to use for redirecting stderr, if any. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setStderrPath(Path stderrPath) {
    this.stderrPath = stderrPath;
    return this;
  }

  /** Sets the files or directories to make writable for the sandboxed process, if any. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setWritableFilesAndDirectories(
      Set<Path> writableFilesAndDirectories) {
    this.writableFilesAndDirectories = writableFilesAndDirectories;
    return this;
  }

  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder addWritablePath(Path writablePath) {
    if (this.writableFilesAndDirectories == null) {
      this.writableFilesAndDirectories = new HashSet<>();
    }
    this.writableFilesAndDirectories.add(writablePath);
    return this;
  }

  /** Sets the directories where to mount an empty tmpfs, if any. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setTmpfsDirectories(
      ImmutableSet<PathFragment> tmpfsDirectories) {
    this.tmpfsDirectories = tmpfsDirectories;
    return this;
  }

  /**
   * Sets the sources and targets of files or directories to explicitly bind-mount in the sandbox,
   * if any.
   */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setBindMounts(Map<Path, Path> bindMounts) {
    this.bindMounts = bindMounts;
    return this;
  }

  /** Sets the path for writing execution statistics (e.g. resource usage). */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setStatisticsPath(Path statisticsPath) {
    this.statisticsPath = statisticsPath;
    return this;
  }

  /** Sets whether to use a fake 'localhost' hostname inside the sandbox. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setUseFakeHostname(boolean useFakeHostname) {
    this.useFakeHostname = useFakeHostname;
    return this;
  }

  /** Sets whether and how to create a new network namespace. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setCreateNetworkNamespace(
      NetworkNamespace createNetworkNamespace) {
    this.createNetworkNamespace = createNetworkNamespace;
    return this;
  }

  /** Sets whether to pretend to be 'root' inside the namespace. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setUseFakeRoot(boolean useFakeRoot) {
    this.useFakeRoot = useFakeRoot;
    return this;
  }

  /** Sets whether to use a fake 'nobody' username inside the sandbox. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setUseFakeUsername(boolean useFakeUsername) {
    this.useFakeUsername = useFakeUsername;
    return this;
  }

  /**
   * Sets whether to set group to 'tty' and make /dev/pts writable inside the sandbox in order to
   * enable the use of pseudoterminals.
   */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setEnablePseudoterminal(boolean enablePseudoterminal) {
    this.enablePseudoterminal = enablePseudoterminal;
    return this;
  }

  /** Sets the output path for sandbox debugging messages. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setSandboxDebugPath(String sandboxDebugPath) {
    this.sandboxDebugPath = sandboxDebugPath;
    return this;
  }

  /**
   * Sets the directory to be used for cgroups. Cgroups can be used to set limits on resource usage
   * of a subprocess tree, and to gather statistics. Requires cgroups v2 and systemd. This directory
   * must be under {@code /sys/fs/cgroup} and the user running Bazel must have write permissions to
   * this directory, its parent directory, and the cgroup directory for the Bazel process.
   */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder setCgroupsDirs(Set<java.nio.file.Path> cgroupsDirs) {
    this.cgroupsDirs = cgroupsDirs;
    return this;
  }

  /** Incorporates settings from a spawn's execution info. */
  @CanIgnoreReturnValue
  public LinuxSandboxCommandLineBuilder addExecutionInfo(Map<String, String> executionInfo) {
    if (executionInfo.containsKey(ExecutionRequirements.GRACEFUL_TERMINATION)) {
      sigintSendsSigterm = true;
    }
    return this;
  }

  /** Builds the command line to invoke a specific command using the {@code linux-sandbox} tool. */
  public ImmutableList<String> buildForCommand(List<String> commandArguments) {
    Preconditions.checkState(
        !(this.useFakeUsername && this.useFakeRoot),
        "useFakeUsername and useFakeRoot are exclusive");

    ImmutableList.Builder<String> commandLineBuilder = ImmutableList.builder();

    commandLineBuilder.add(linuxSandboxPath.getPathString());
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
    for (PathFragment tmpfsPath : tmpfsDirectories) {
      commandLineBuilder.add("-e", tmpfsPath.getPathString());
    }
    for (Path bindMountTarget : bindMounts.keySet()) {
      Path bindMountSource = bindMounts.get(bindMountTarget);
      commandLineBuilder.add("-M", bindMountSource.getPathString());
      // The file is mounted in a custom location inside the sandbox.
      if (!bindMountSource.equals(bindMountTarget)) {
        commandLineBuilder.add("-m", bindMountTarget.getPathString());
      }
    }
    if (statisticsPath != null) {
      commandLineBuilder.add("-S", statisticsPath.getPathString());
    }
    if (hermeticSandboxPath != null) {
      commandLineBuilder.add("-h", hermeticSandboxPath.getPathString());
    }
    if (useFakeHostname) {
      commandLineBuilder.add("-H");
    }
    if (createNetworkNamespace == NETNS_WITH_LOOPBACK) {
      commandLineBuilder.add("-N");
    } else if (createNetworkNamespace == NETNS) {
      commandLineBuilder.add("-n");
    }
    if (useFakeRoot) {
      commandLineBuilder.add("-R");
    }
    if (useFakeUsername) {
      commandLineBuilder.add("-U");
    }
    if (enablePseudoterminal) {
      commandLineBuilder.add("-P");
    }
    if (sandboxDebugPath != null) {
      commandLineBuilder.add("-D", sandboxDebugPath);
    }
    if (sigintSendsSigterm) {
      commandLineBuilder.add("-i");
    }
    if (persistentProcess) {
      commandLineBuilder.add("-p");
    }
    for (java.nio.file.Path dir : cgroupsDirs) {
      commandLineBuilder.add("-C", dir.toString());
    }
    commandLineBuilder.add("--");
    commandLineBuilder.addAll(commandArguments);

    return commandLineBuilder.build();
  }

  /** Enum for the possibilities for creating a network namespace in the sandbox. */
  public enum NetworkNamespace {
    /** No network namespace will be created, sandboxed processes can access the network freely. */
    NO_NETNS,
    /** A fresh network namespace will be created. */
    NETNS,
    /** A fresh network namespace will be created, and a loopback device will be set up in it. */
    NETNS_WITH_LOOPBACK,
  }
}
