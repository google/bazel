// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalSpawnRunner;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.commands.events.CleanStartingEvent;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Sandbox;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.TriState;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** This module provides the Sandbox spawn strategy. */
public final class SandboxModule extends BlazeModule {

  private static final String MAC_INDEX_FILE = ".DS_Store";

  private static final ImmutableSet<String> SANDBOX_BASE_PERSISTENT_DIRS =
      ImmutableSet.of(
          MAC_INDEX_FILE,
          SandboxStash.SANDBOX_STASH_BASE,
          SandboxStash.TEMPORARY_SANDBOX_STASH_BASE,
          AsynchronousTreeDeleter.MOVED_TRASH_DIR);

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Tracks whether we are issuing the very first build within this Bazel server instance. */
  private static boolean firstBuild = true;

  /** Environment for the running command. */
  @Nullable private CommandEnvironment env;

  /** Path to the location of the sandboxes. */
  @Nullable private Path sandboxBase;

  /**
   * Collection of spawn runner instantiated during the executor setup.
   *
   * <p>We need this information to clean up the heavy subdirectories of the sandbox base on build
   * completion but to avoid wiping the whole sandbox base itself, which could be problematic across
   * builds.
   */
  private final Set<SandboxFallbackSpawnRunner> spawnRunners = new HashSet<>();

  /**
   * Handler to process expensive tree deletions, potentially outside of the critical path.
   *
   * <p>Sandboxing creates one separate tree for each action, and this tree is used to run the
   * action commands in. These trees are disjoint for all actions and have unique identifiers.
   * Therefore, there is no need for their deletion (which can be very expensive) to happen in the
   * critical path -- so if the user so wishes, we process those deletions asynchronously.
   */
  @Nullable private TreeDeleter treeDeleter;

  /**
   * Whether to remove the sandbox worker directories after a build or not. Useful for debugging to
   * inspect the state of files on failures.
   */
  private boolean shouldCleanupSandboxBase;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(SandboxOptions.class)
        : ImmutableList.of();
  }

  /** Computes the path to the sandbox base tree for the given running command. */
  private static Path computeSandboxBase(SandboxOptions options, CommandEnvironment env)
      throws IOException {
    if (options.sandboxBase.isEmpty()) {
      return env.getOutputBase().getRelative("sandbox");
    } else {
      String dirName =
          String.format(
              "%s-sandbox.%s",
              env.getRuntime().getProductName(),
              Fingerprint.getHexDigest(env.getOutputBase().toString()));
      FileSystem fileSystem = env.getRuntime().getFileSystem();
      if (OS.getCurrent() == OS.DARWIN) {
        // Don't resolve symlinks on macOS: See https://github.com/bazelbuild/bazel/issues/13766
        return fileSystem.getPath(options.sandboxBase).getRelative(dirName);
      }
      Path resolvedSandboxBase = fileSystem.getPath(options.sandboxBase).resolveSymbolicLinks();
      return resolvedSandboxBase.getRelative(dirName);
    }
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    // We can't assert that env is null because the Blaze runtime does not guarantee that
    // afterCommand() will be called if the command fails due to, e.g. a syntax error.
    this.env = env;
    env.getEventBus().register(this);

    // Don't attempt cleanup unless the executor is initialized.
    shouldCleanupSandboxBase = false;
  }

  @Override
  public void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder, CommandEnvironment env)
      throws AbruptExitException, InterruptedException {
    checkNotNull(env, "env not initialized; was beforeCommand called?");
    try {
      setup(env, registryBuilder);
    } catch (IOException e) {
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(String.format("Failed to initialize sandbox: %s", e.getMessage()))
                  .setSandbox(
                      Sandbox.newBuilder().setCode(Sandbox.Code.INITIALIZATION_FAILURE).build())
                  .build()),
          e);
    }
  }

  /**
   * Returns true if windows-sandbox should be used for this build.
   *
   * <p>Returns true if requested in ["auto", "yes"] and binary is valid. Throws an error if state
   * is "yes" and binary is not valid.
   *
   * @param requested whether windows-sandbox use was requested or not
   * @param binary path of the windows-sandbox binary to use, can be absolute or relative path
   * @return true if windows-sandbox can and should be used; false otherwise
   * @throws IOException if there are problems trying to determine the status of windows-sandbox
   */
  private static boolean shouldUseWindowsSandbox(TriState requested, PathFragment binary)
      throws IOException {
    switch (requested) {
      case AUTO:
        return WindowsSandboxUtil.isAvailable(binary);

      case NO:
        return false;

      case YES:
        if (!WindowsSandboxUtil.isAvailable(binary)) {
          throw new IOException(
              "windows-sandbox explicitly requested but \""
                  + binary
                  + "\" could not be found or is not valid");
        }
        return true;
    }
    throw new IllegalStateException("Not reachable");
  }

  private void setup(CommandEnvironment cmdEnv, SpawnStrategyRegistry.Builder builder)
      throws IOException, InterruptedException {
    SandboxOptions options = checkNotNull(env.getOptions().getOptions(SandboxOptions.class));
    sandboxBase = computeSandboxBase(options, env);
    Path trashBase = sandboxBase.getRelative(AsynchronousTreeDeleter.MOVED_TRASH_DIR);

    SandboxHelpers helpers = new SandboxHelpers();

    // Do not remove the sandbox base when --sandbox_debug was specified so that people can check
    // out the contents of the generated sandbox directories.
    shouldCleanupSandboxBase = !options.sandboxDebug;

    // If there happens to be any live tree deleter from a previous build and it's different than
    // the one we want now, leave it alone (i.e. don't attempt to wait for pending deletions). Its
    // deletions shouldn't overlap any new directories we create during this build (because the
    // identifiers in the subdirectories will be different).
    if (options.asyncTreeDeleteIdleThreads == 0) {
      if (!(treeDeleter instanceof SynchronousTreeDeleter)) {
        treeDeleter = new SynchronousTreeDeleter();
      }
    } else {
      if (!(treeDeleter instanceof AsynchronousTreeDeleter treeDeleter)
          || !treeDeleter.getTrashBase().equals(trashBase)) {
        if (treeDeleter != null) {
          treeDeleter.shutdown();
        }
        treeDeleter = new AsynchronousTreeDeleter(trashBase);
        firstBuild = true;
      }
    }
    try (SilentCloseable c = Profiler.instance().profile("SandboxStash.initialize")) {
      SandboxStash.initialize(env.getWorkspaceName(), sandboxBase, options, treeDeleter);
    }

    // SpawnExecutionPolicy#getId returns unique base directories for each sandboxed action during
    // the life of a Bazel server instance so we don't need to worry about stale directories from
    // previous builds. However, on the very first build of an instance of the server, we must
    // wipe old contents to avoid reusing stale directories.
    if (firstBuild && sandboxBase.exists()) {
      try (SilentCloseable c = Profiler.instance().profile("clean sandbox on first build")) {
        if (trashBase.exists()) {
          // Delete stale trash from a previous server instance.
          Path staleTrash = getStaleTrashDir(trashBase);
          trashBase.renameTo(staleTrash);
          trashBase.createDirectory();
          treeDeleter.deleteTree(staleTrash);
        } else {
          trashBase.createDirectory();
        }
        // We can delete other dirs asynchronously (if the flag is on).
        for (Path entry : sandboxBase.getDirectoryEntries()) {
          if (entry.getBaseName().equals(AsynchronousTreeDeleter.MOVED_TRASH_DIR)) {
            continue;
          }
          if (entry.getBaseName().equals(SandboxHelpers.INACCESSIBLE_HELPER_DIR)) {
            entry.deleteTree();
          } else if (entry.isDirectory()) {
            treeDeleter.deleteTree(entry);
          } else {
            entry.delete();
          }
        }
      } catch (IOException e) {
        // We have observed asynchronous deletion failing when running Bazel under Docker, see
        // #21719. Different RUN commands with `bazel build` will write to different layers in the
        // docker image. The overlay filesystem is different and the renaming of the directories
        // that we need to do for asynchronous deletion will fail. When that happens we fall back to
        // synchronous deletion here.
        sandboxBase.deleteTree();
      }
    }
    firstBuild = false;
    sandboxBase.createDirectoryAndParents();
    trashBase.createDirectory();

    PathFragment windowsSandboxPath = PathFragment.create(options.windowsSandboxPath);
    boolean windowsSandboxSupported;
    try (SilentCloseable c = Profiler.instance().profile("shouldUseWindowsSandbox")) {
      windowsSandboxSupported =
          shouldUseWindowsSandbox(options.useWindowsSandbox, windowsSandboxPath);
    }

    Duration timeoutKillDelay =
        cmdEnv.getOptions().getOptions(LocalExecutionOptions.class).getLocalSigkillGraceSeconds();

    boolean processWrapperSupported = ProcessWrapperSandboxedSpawnRunner.isSupported(cmdEnv);
    boolean linuxSandboxSupported = LinuxSandboxedSpawnRunner.isSupported(cmdEnv);
    boolean darwinSandboxSupported = DarwinSandboxedSpawnRunner.isSupported(cmdEnv);

    ExecutionOptions executionOptions =
        checkNotNull(cmdEnv.getOptions().getOptions(ExecutionOptions.class));
    // This works on most platforms, but isn't the best choice, so we put it first and let later
    // platform-specific sandboxing strategies become the default.
    if (processWrapperSupported) {
      SandboxFallbackSpawnRunner spawnRunner =
          withFallback(
              cmdEnv,
              new ProcessWrapperSandboxedSpawnRunner(helpers, cmdEnv, sandboxBase, treeDeleter));
      spawnRunners.add(spawnRunner);
      builder.registerStrategy(
          new ProcessWrapperSandboxedStrategy(cmdEnv.getExecRoot(), spawnRunner, executionOptions),
          "sandboxed",
          "processwrapper-sandbox");
    }

    if (options.enableDockerSandbox) {
      // This strategy uses Docker to execute spawns. It should work on all platforms that support
      // Docker.
      Path pathToDocker = getPathToDockerClient(cmdEnv);
      // DockerSandboxedSpawnRunner.isSupported is expensive! It runs docker as a subprocess, and
      // docker hangs sometimes.
      if (pathToDocker != null && DockerSandboxedSpawnRunner.isSupported(cmdEnv, pathToDocker)) {
        String defaultImage = options.dockerImage;
        boolean useCustomizedImages = options.dockerUseCustomizedImages;
        SandboxFallbackSpawnRunner spawnRunner =
            withFallback(
                cmdEnv,
                new DockerSandboxedSpawnRunner(
                    helpers,
                    cmdEnv,
                    pathToDocker,
                    sandboxBase,
                    defaultImage,
                    useCustomizedImages,
                    treeDeleter));
        spawnRunners.add(spawnRunner);
        builder.registerStrategy(
            new DockerSandboxedStrategy(cmdEnv.getExecRoot(), spawnRunner, executionOptions),
            "docker");
      }
    } else if (options.dockerVerbose) {
      cmdEnv
          .getReporter()
          .handle(
              Event.info(
                  "Docker sandboxing disabled. Use the '--experimental_enable_docker_sandbox'"
                      + " command line option to enable it"));
    }

    // This is the preferred sandboxing strategy on Linux.
    if (linuxSandboxSupported) {
      SandboxFallbackSpawnRunner spawnRunner =
          withFallback(
              cmdEnv,
              LinuxSandboxedStrategy.create(
                  helpers, cmdEnv, sandboxBase, timeoutKillDelay, treeDeleter));
      spawnRunners.add(spawnRunner);
      builder.registerStrategy(
          new LinuxSandboxedStrategy(cmdEnv.getExecRoot(), spawnRunner, executionOptions),
          "sandboxed",
          "linux-sandbox");
    }

    // This is the preferred sandboxing strategy on macOS.
    if (darwinSandboxSupported) {
      SandboxFallbackSpawnRunner spawnRunner =
          withFallback(
              cmdEnv, new DarwinSandboxedSpawnRunner(helpers, cmdEnv, sandboxBase, treeDeleter));
      spawnRunners.add(spawnRunner);
      builder.registerStrategy(
          new DarwinSandboxedStrategy(cmdEnv.getExecRoot(), spawnRunner, executionOptions),
          "sandboxed",
          "darwin-sandbox");
    }

    if (windowsSandboxSupported) {
      SandboxFallbackSpawnRunner spawnRunner =
          withFallback(
              cmdEnv,
              new WindowsSandboxedSpawnRunner(
                  helpers, cmdEnv, timeoutKillDelay, windowsSandboxPath));
      spawnRunners.add(spawnRunner);
      builder.registerStrategy(
          new WindowsSandboxedStrategy(cmdEnv.getExecRoot(), spawnRunner, executionOptions),
          "sandboxed",
          "windows-sandbox");
    }

    if (processWrapperSupported
        || linuxSandboxSupported
        || darwinSandboxSupported
        || windowsSandboxSupported) {
      // This makes the "sandboxed" strategy the default Spawn strategy, unless it is
      // overridden by a later BlazeModule.
      builder.setDefaultStrategies(ImmutableList.of("sandboxed"));
    }
  }

  @Nullable
  private static Path getPathToDockerClient(CommandEnvironment cmdEnv) {
    String path = cmdEnv.getClientEnv().getOrDefault("PATH", "");

    // TODO(philwo): Does this return the correct result if one of the elements intentionally ends
    // in white space?
    Splitter pathSplitter =
        Splitter.on(OS.getCurrent() == OS.WINDOWS ? ';' : ':').trimResults().omitEmptyStrings();

    FileSystem fs = cmdEnv.getRuntime().getFileSystem();

    for (String pathElement : pathSplitter.split(path)) {
      // Sometimes the PATH contains the non-absolute entry "." - this resolves it against the
      // current working directory.
      pathElement = new File(pathElement).getAbsolutePath();
      try {
        for (Path dentry : fs.getPath(pathElement).getDirectoryEntries()) {
          if (dentry.getBaseName().replace(".exe", "").equals("docker")) {
            return dentry;
          }
        }
      } catch (IOException e) {
        continue;
      }
    }

    return null;
  }

  private static SandboxFallbackSpawnRunner withFallback(
      CommandEnvironment env, AbstractSandboxSpawnRunner sandboxSpawnRunner) {
    SandboxOptions sandboxOptions = env.getOptions().getOptions(SandboxOptions.class);
    return new SandboxFallbackSpawnRunner(
        sandboxSpawnRunner,
        createFallbackRunner(env),
        env.getReporter(),
        sandboxOptions != null && sandboxOptions.legacyLocalFallback);
  }

  private static SpawnRunner createFallbackRunner(CommandEnvironment env) {
    LocalExecutionOptions localExecutionOptions =
        env.getOptions().getOptions(LocalExecutionOptions.class);
    return new LocalSpawnRunner(
        env.getExecRoot(),
        localExecutionOptions,
        env.getLocalResourceManager(),
        LocalEnvProvider.forCurrentOs(env.getClientEnv()),
        env.getBlazeWorkspace().getBinTools(),
        ProcessWrapper.fromCommandEnvironment(env),
        RunfilesTreeUpdater.forCommandEnvironment(env));
  }

  /**
   * A SpawnRunner that does sandboxing if possible, but might fall back to local execution if
   * ----incompatible_legacy_local_fallback is true and no other strategy has been usable. This is a
   * legacy functionality from before the strategies system was added, and can deceive the user into
   * thinking a build is hermetic when it isn't really. TODO(b/178356138): Flip flag to default to
   * false and then later remove this code entirely.
   */
  private static final class SandboxFallbackSpawnRunner implements SpawnRunner {
    private final SpawnRunner sandboxSpawnRunner;
    private final SpawnRunner fallbackSpawnRunner;
    private final ExtendedEventHandler reporter;
    private static final AtomicBoolean warningEmitted = new AtomicBoolean();
    private final boolean fallbackAllowed;

    SandboxFallbackSpawnRunner(
        SpawnRunner sandboxSpawnRunner,
        SpawnRunner fallbackSpawnRunner,
        ExtendedEventHandler reporter,
        boolean fallbackAllowed) {
      this.sandboxSpawnRunner = sandboxSpawnRunner;
      this.fallbackSpawnRunner = fallbackSpawnRunner;
      this.reporter = reporter;
      this.fallbackAllowed = fallbackAllowed;
    }

    @Override
    public String getName() {
      return "sandbox-fallback";
    }

    @Override
    public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
        throws InterruptedException, IOException, ExecException, ForbiddenActionInputException {
      if (sandboxSpawnRunner.canExec(spawn)) {
        return sandboxSpawnRunner.exec(spawn, context);
      } else {
        return fallbackSpawnRunner.exec(spawn, context);
      }
    }

    @Override
    public boolean canExec(Spawn spawn) {
      return sandboxSpawnRunner.canExec(spawn);
    }

    @Override
    public boolean canExecWithLegacyFallback(Spawn spawn) {
      boolean canExec = !sandboxSpawnRunner.canExec(spawn) && fallbackSpawnRunner.canExec(spawn);
      if (canExec) {
        // We give a warning to use strategies instead, whether or not we allow the fallback
        // to happen. This allows people to switch early, but also explains why the build fails
        // once we flip the flag. Unfortunately, we can't easily tell if the flag was explicitly
        // set, if we could we should omit the warnings in that case.
        if (warningEmitted.compareAndSet(false, true)) {
          reporter.handle(
              Event.warn(
                  String.format(
                      "%s uses implicit fallback from sandbox to local, which is deprecated"
                          + " because it is not hermetic. Prefer setting an explicit list of"
                          + " strategies, e.g., --strategy=%s=sandboxed,standalone",
                      spawn.getMnemonic(), spawn.getMnemonic())));
        }
      }
      return canExec && fallbackAllowed;
    }

    @Override
    public boolean handlesCaching() {
      return false;
    }

    @Override
    public void cleanupSandboxBase(Path sandboxBase, TreeDeleter treeDeleter) throws IOException {
      sandboxSpawnRunner.cleanupSandboxBase(sandboxBase, treeDeleter);
      if (fallbackSpawnRunner != null) {
        fallbackSpawnRunner.cleanupSandboxBase(sandboxBase, treeDeleter);
      }
    }

    public SpawnRunner getSandboxSpawnRunner() {
      return sandboxSpawnRunner;
    }
  }

  @Subscribe
  public void cleanStarting(@SuppressWarnings("unused") CleanStartingEvent event) {
    if (sandboxBase != null) {
      SandboxStash.clean(treeDeleter, sandboxBase);
    }
  }

  /**
   * If there is anything other than SANDBOX_BASE_PERSISTENT_DIRS in sandboxBase when we hit this
   * precondition then there is a programming error somewhere (or I made a wrong assumption that
   * wasn't caught by any of our tests).
   */
  private static void checkSandboxBaseTopOnlyContainsPersistentDirs(Path sandboxBase) {
    try {
      List<String> directoryEntries =
          sandboxBase.getDirectoryEntries().stream()
              .map(Path::getBaseName)
              .collect(Collectors.toList());
      // If sandbox initialization failed in-between creating the inaccessible dir/file and adding
      // the Linux sandboxing strategy to spawnRunners, then the sandbox base will be in a bad
      // state. We check for that here and clean up.
      if (directoryEntries.contains(SandboxHelpers.INACCESSIBLE_HELPER_DIR)) {
        Path inaccessibleHelperDir = sandboxBase.getChild(SandboxHelpers.INACCESSIBLE_HELPER_DIR);
        inaccessibleHelperDir.chmod(0700);
        directoryEntries.remove(SandboxHelpers.INACCESSIBLE_HELPER_DIR);
        inaccessibleHelperDir.deleteTree();
      }
      if (directoryEntries.contains(SandboxHelpers.INACCESSIBLE_HELPER_FILE)) {
        Path inaccessibleHelperFile = sandboxBase.getChild(SandboxHelpers.INACCESSIBLE_HELPER_FILE);
        directoryEntries.remove(SandboxHelpers.INACCESSIBLE_HELPER_FILE);
        inaccessibleHelperFile.delete();
      }

      if (!SANDBOX_BASE_PERSISTENT_DIRS.containsAll(directoryEntries)) {
        StringBuilder message =
            new StringBuilder(
                "Found unexpected entries in sandbox base. Please report this in"
                    + " https://github.com/bazelbuild/bazel/issues.");
        message.append(" The entries are: ");
        Joiner.on(", ").appendTo(message, directoryEntries);
        throw new IllegalStateException(message.toString());
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to clean up sandbox base %s", sandboxBase);
    }
  }

  @Override
  public void afterCommand() {
    checkNotNull(env, "env not initialized; was beforeCommand called?");

    SandboxOptions options = env.getOptions().getOptions(SandboxOptions.class);
    int asyncTreeDeleteThreads = options != null ? options.asyncTreeDeleteIdleThreads : 0;

    // If asynchronous deletions were requested, they may still be ongoing so let them be: trying
    // to delete the base tree synchronously could fail as we can race with those other deletions,
    // and scheduling an asynchronous deletion could race with future builds.
    if (asyncTreeDeleteThreads > 0 && treeDeleter instanceof AsynchronousTreeDeleter treeDeleter) {
      treeDeleter.setThreads(asyncTreeDeleteThreads);
    }
    // `treeDeleter` might not be an AsynchronousTreeDeleter if the user changed the option but
    // then interrupted the build before the start of the execution phase. But that's OK, there
    // will be nothing new to delete. See #13240.

    if (shouldCleanupSandboxBase) {
      try {
        checkNotNull(sandboxBase, "shouldCleanupSandboxBase implies sandboxBase has been set");
        for (SandboxFallbackSpawnRunner spawnRunner : spawnRunners) {
          spawnRunner.cleanupSandboxBase(sandboxBase, treeDeleter);
          sandboxBase.getChild(spawnRunner.getSandboxSpawnRunner().getName()).delete();
        }
      } catch (IOException e) {
        env.getReporter()
            .handle(Event.warn("Failed to delete contents of sandbox " + sandboxBase + ": " + e));
      }
      shouldCleanupSandboxBase = false;

      checkSandboxBaseTopOnlyContainsPersistentDirs(sandboxBase);
      // We intentionally keep sandboxBase around, without resetting it to null, in case we have
      // asynchronous deletions going on. In that case, we'd still want to retry this during
      // shutdown.
    }

    spawnRunners.clear();

    env.getEventBus().unregister(this);
    env = null;
  }

  private void commonShutdown() {
    // Try to clean up as much garbage as possible, if there happens to be any. This will delay
    // server termination but it's the nice thing to do. If the user gets impatient, they can always
    // kill us again.
    if (treeDeleter != null) {
      try {
        treeDeleter.shutdown();
      } finally {
        treeDeleter = null; // Avoid potential reexecution if we crash.
      }
    }

    SandboxStash.shutdown();
  }

  @Override
  public void blazeShutdown() {
    commonShutdown();
  }

  @Override
  public void blazeShutdownOnCrash(DetailedExitCode exitCode) {
    commonShutdown();
  }

  private Path getStaleTrashDir(Path trashBase) {
    int i = 0;
    while (trashBase.getParentDirectory().getChild("stale-trash-" + i++).exists()) {
      ;
    }
    return trashBase.getParentDirectory().getChild("stale-trash-" + --i);
  }
}
