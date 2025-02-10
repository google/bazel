// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Spawn runner that uses Darwin (macOS) sandboxing to execute a process. */
final class DarwinSandboxedSpawnRunner extends AbstractSandboxSpawnRunner {

  /** Path to the {@code getconf} system tool to use. */
  @VisibleForTesting
  static String getconfBinary = "/usr/bin/getconf";

  /** Path to the {@code sandbox-exec} system tool to use. */
  @VisibleForTesting
  static String sandboxExecBinary = "/usr/bin/sandbox-exec";

  // Since checking if sandbox is supported is expensive, we remember what we've checked.
  private static Boolean isSupported = null;

  /**
   * Returns whether the darwin sandbox is supported on the local machine by running a small command
   * in it.
   */
  public static boolean isSupported(CommandEnvironment cmdEnv) throws InterruptedException {
    if (OS.getCurrent() != OS.DARWIN) {
      return false;
    }
    if (ProcessWrapper.fromCommandEnvironment(cmdEnv) == null) {
      return false;
    }
    if (isSupported == null) {
      isSupported = computeIsSupported();
    }
    return isSupported;
  }

  private static boolean computeIsSupported() throws InterruptedException {
    List<String> args = new ArrayList<>();
    args.add(sandboxExecBinary);
    args.add("-p");
    args.add("(version 1) (allow default)");
    args.add("/usr/bin/true");

    ImmutableMap<String, String> env = ImmutableMap.of();
    File cwd = new File("/usr/bin");

    Command cmd = new Command(args.toArray(new String[0]), env, cwd);
    try {
      cmd.execute(ByteStreams.nullOutputStream(), ByteStreams.nullOutputStream());
    } catch (CommandException e) {
      return false;
    }

    return true;
  }

  private final SandboxHelpers helpers;
  private final Path execRoot;
  private final boolean allowNetwork;
  private final ProcessWrapper processWrapper;
  private final Path sandboxBase;
  private final TreeDeleter treeDeleter;

  /**
   * The set of directories that always should be writable, independent of the Spawn itself.
   *
   * <p>We cache this, because creating it involves executing {@code getconf}, which is expensive.
   */
  private final ImmutableSet<Path> alwaysWritableDirs;
  private final LocalEnvProvider localEnvProvider;

  /**
   * Creates a sandboxed spawn runner that uses the {@code process-wrapper} tool and the MacOS
   * {@code sandbox-exec} binary.
   *
   * @param helpers common tools and state across all spawns during sandboxed execution
   * @param cmdEnv the command environment to use
   * @param sandboxBase path to the sandbox base directory
   */
  DarwinSandboxedSpawnRunner(
      SandboxHelpers helpers,
      CommandEnvironment cmdEnv,
      Path sandboxBase,
      TreeDeleter treeDeleter)
      throws IOException, InterruptedException {
    super(cmdEnv);
    this.helpers = helpers;
    this.execRoot = cmdEnv.getExecRoot();
    this.allowNetwork = helpers.shouldAllowNetwork(cmdEnv.getOptions());
    this.alwaysWritableDirs = getAlwaysWritableDirs(cmdEnv.getRuntime().getFileSystem());
    this.processWrapper = ProcessWrapper.fromCommandEnvironment(cmdEnv);
    this.localEnvProvider = LocalEnvProvider.forCurrentOs(cmdEnv.getClientEnv());
    this.sandboxBase = sandboxBase;
    this.treeDeleter = treeDeleter;
  }

  private static void addPathToSetIfExists(FileSystem fs, Set<Path> paths, String path)
      throws IOException {
    if (path != null) {
      addPathToSetIfExists(paths, fs.getPath(path));
    }
  }

  private static void addPathToSetIfExists(Set<Path> paths, Path path) throws IOException {
    if (path.exists()) {
      paths.add(path.resolveSymbolicLinks());
    }
  }

  private static ImmutableSet<Path> getAlwaysWritableDirs(FileSystem fs)
      throws IOException, InterruptedException {
    HashSet<Path> writableDirs = new HashSet<>();

    addPathToSetIfExists(fs, writableDirs, "/dev");
    addPathToSetIfExists(fs, writableDirs, "/tmp");
    addPathToSetIfExists(fs, writableDirs, "/private/tmp");
    addPathToSetIfExists(fs, writableDirs, "/private/var/tmp");

    // On macOS, processes may write to not only $TMPDIR but also to two other temporary
    // directories. We have to get their location by calling "getconf".
    addPathToSetIfExists(fs, writableDirs, getConfStr("DARWIN_USER_TEMP_DIR"));
    addPathToSetIfExists(fs, writableDirs, getConfStr("DARWIN_USER_CACHE_DIR"));
    // We don't add any value for $TMPDIR here, instead we compute its value later in
    // {@link #actuallyExec} and add it as a writable directory in
    // {@link AbstractSandboxSpawnRunner#getWritableDirs}.

    // ~/Library/Caches and ~/Library/Logs need to be writable (cf. issue #2231).
    Path homeDir = fs.getPath(StringEncoding.platformToInternal(System.getProperty("user.home")));
    addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Caches"));
    addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Logs"));

    // Certain Xcode tools expect to be able to write to this path.
    addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Developer"));

    return ImmutableSet.copyOf(writableDirs);
  }

  /** Returns the value of a POSIX or X/Open system configuration variable. */
  private static String getConfStr(String confVar) throws IOException, InterruptedException {
    String[] commandArr = new String[2];
    commandArr[0] = getconfBinary;
    commandArr[1] = confVar;
    Command cmd = new Command(commandArr);
    CommandResult res;
    try {
      res = cmd.execute();
    } catch (CommandException e) {
      throw new IOException("getconf failed", e);
    }
    return new String(res.getStdout(), UTF_8).trim();
  }

  @Override
  protected SandboxedSpawn prepareSpawn(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException, InterruptedException {
    // Each invocation of "exec" gets its own sandbox base.
    // Note that the value returned by context.getId() is only unique inside one given SpawnRunner,
    // so we have to prefix our name to turn it into a globally unique value.
    Path sandboxPath =
        sandboxBase.getRelative(getName()).getRelative(Integer.toString(context.getId()));
    sandboxPath.getParentDirectory().createDirectory();
    sandboxPath.createDirectory();

    // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
    // the normal execroot does.
    String workspaceName = execRoot.getBaseName();
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(workspaceName);
    sandboxExecRoot.getParentDirectory().createDirectory();
    sandboxExecRoot.createDirectory();

    ImmutableMap<String, String> environment =
        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");

    final HashSet<Path> writableDirs = new HashSet<>(alwaysWritableDirs);
    ImmutableSet<Path> extraWritableDirs = getWritableDirs(sandboxExecRoot, environment);
    writableDirs.addAll(extraWritableDirs);

    SandboxInputs inputs =
        helpers.processInputFiles(
            context.getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true),
            execRoot);
    SandboxOutputs outputs = helpers.getOutputs(spawn);

    final Path sandboxConfigPath = sandboxPath.getRelative("sandbox.sb");
    Duration timeout = context.getTimeout();

    ProcessWrapper.CommandLineBuilder processWrapperCommandLineBuilder =
        processWrapper
            .commandLineBuilder(spawn.getArguments())
            .addExecutionInfo(spawn.getExecutionInfo())
            .setTimeout(timeout);

    Path statisticsPath = sandboxPath.getRelative("stats.out");
    processWrapperCommandLineBuilder.setStatisticsPath(statisticsPath.asFragment());

    ImmutableList<String> commandLine =
        ImmutableList.<String>builder()
            .add(sandboxExecBinary)
            .add("-f")
            .add(sandboxConfigPath.getPathString())
            .addAll(processWrapperCommandLineBuilder.build())
            .build();

    boolean allowNetworkForThisSpawn =
        allowNetwork
            || Spawns.requiresNetwork(spawn, getSandboxOptions().defaultSandboxAllowNetwork);

    return new SymlinkedSandboxedSpawn(
        sandboxPath,
        sandboxExecRoot,
        commandLine,
        environment,
        inputs,
        outputs,
        writableDirs,
        treeDeleter,
        /* sandboxDebugPath= */ null,
        statisticsPath,
        /* interactiveDebugArguments= */ null,
        spawn.getMnemonic(),
        spawn.getTargetLabel()) {
      @Override
      public void createFileSystem() throws IOException, InterruptedException {
        super.createFileSystem();
        writeConfig(
            sandboxConfigPath,
            writableDirs,
            getInaccessiblePaths(),
            allowNetworkForThisSpawn,
            statisticsPath);
      }
    };
  }

  private static void writeConfig(
      Path sandboxConfigPath,
      Set<Path> writableDirs,
      Set<Path> inaccessiblePaths,
      boolean allowNetwork,
      @Nullable Path statisticsPath)
      throws IOException {
    try (PrintWriter out =
        new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(sandboxConfigPath.getOutputStream(), UTF_8)))) {
      // Note: In Apple's sandbox configuration language, the *last* matching rule wins.
      out.println("(version 1)");
      out.println("(debug deny)");
      out.println("(allow default)");
      out.println("(allow process-exec (with no-sandbox) (literal \"/bin/ps\"))");

      if (!allowNetwork) {
        out.println("(deny network*)");
        out.println("(allow network-inbound (local ip \"localhost:*\"))");
        out.println("(allow network* (remote ip \"localhost:*\"))");
        out.println("(allow network* (remote unix-socket))");
      }

      // By default, everything is read-only.
      out.println("(deny file-write*)");

      out.println("(allow file-write*");
      for (Path path : writableDirs) {
        out.println("    (subpath \"" + path.getPathString() + "\")");
      }
      if (statisticsPath != null) {
        out.println("    (literal \"" + statisticsPath.getPathString() + "\")");
      }
      out.println(")");

      if (!inaccessiblePaths.isEmpty()) {
        out.println("(deny file-read*");
        // The sandbox configuration file is not part of a cache key and sandbox-exec doesn't care
        // about ordering of paths in expressions, so it's fine if the iteration order is random.
        for (Path inaccessiblePath : inaccessiblePaths) {
          out.println("    (subpath \"" + inaccessiblePath + "\")");
        }
        out.println(")");
      }
    }
  }

  @Override
  public String getName() {
    return "darwin-sandbox";
  }
}
