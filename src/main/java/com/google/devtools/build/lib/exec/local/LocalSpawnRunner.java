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
package com.google.devtools.build.lib.exec.local;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceManager.ResourceHandle;
import com.google.devtools.build.lib.actions.ResourceManager.ResourcePriority;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnSchedulingEvent;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.util.NetUtil;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * A class that runs local commands. Each request follows state transitions from "parsing" to
 * completion.
 */
@ThreadSafe
public class LocalSpawnRunner implements SpawnRunner {
  private static final Joiner SPACE_JOINER = Joiner.on(' ');
  private static final String UNHANDLED_EXCEPTION_MSG = "Unhandled exception running a local spawn";
  private static final int LOCAL_EXEC_ERROR = -1;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Path execRoot;
  private final ResourceManager resourceManager;

  private final String hostName;

  private final LocalExecutionOptions localExecutionOptions;

  @Nullable private final ProcessWrapper processWrapper;

  private final LocalEnvProvider localEnvProvider;
  private final BinTools binTools;

  private final RunfilesTreeUpdater runfilesTreeUpdater;
  private final boolean handlesCaching;

  public LocalSpawnRunner(
      Path execRoot,
      ExecutionOptions executionOptions,
      LocalExecutionOptions localExecutionOptions,
      ResourceManager resourceManager,
      LocalEnvProvider localEnvProvider,
      BinTools binTools,
      ProcessWrapper processWrapper,
      RunfilesTreeUpdater runfilesTreeUpdater) {
    this.execRoot = execRoot;
    this.processWrapper = processWrapper;
    this.localExecutionOptions = Preconditions.checkNotNull(localExecutionOptions);
    this.hostName = NetUtil.getCachedShortHostName();
    this.resourceManager = resourceManager;
    this.localEnvProvider = localEnvProvider;
    this.binTools = binTools;
    this.runfilesTreeUpdater = runfilesTreeUpdater;
    this.handlesCaching = !executionOptions.useRemoteCacheForCacheUnawareSpawns;
  }

  @Override
  public String getName() {
    return "local";
  }

  @Override
  public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
      throws IOException, InterruptedException, ExecException, ForbiddenActionInputException {

    SpawnMetrics.Builder spawnMetrics = SpawnMetrics.Builder.forLocalExec();
    Stopwatch totalTimeStopwatch = Stopwatch.createStarted();
    Stopwatch setupTimeStopwatch = Stopwatch.createStarted();
    try (var s = Profiler.instance().profile("updateRunfiles")) {
      runfilesTreeUpdater.updateRunfiles(
          spawn.getRunfilesSupplier(), spawn.getEnvironment(), context.getFileOutErr());
    }
    if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
      context.prefetchInputsAndWait();
    }
    spawnMetrics.addSetupTimeInMs((int) setupTimeStopwatch.elapsed().toMillis());

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.LOCAL_EXECUTION, spawn.getResourceOwner().getMnemonic())) {
      ActionExecutionMetadata owner = spawn.getResourceOwner();
      context.report(SpawnSchedulingEvent.create(getName()));

      Stopwatch queueStopwatch = Stopwatch.createStarted();
      try (ResourceHandle handle =
          resourceManager.acquireResources(
              owner,
              spawn.getLocalResources(),
              context.speculating()
                  ? ResourcePriority.DYNAMIC_STANDALONE
                  : ResourcePriority.LOCAL)) {
        spawnMetrics.setQueueTimeInMs((int) queueStopwatch.elapsed().toMillis());
        context.report(SpawnExecutingEvent.create(getName()));
        if (!localExecutionOptions.localLockfreeOutput) {
          // Without local-lockfree, we grab the lock before running the action, so we can't
          // check for failures while taking the lock.
          context.lockOutputFiles(0, "", context.getFileOutErr());
        }
        var result = new SubprocessHandler(spawn, context, spawnMetrics, totalTimeStopwatch).run();
        if (result.exitCode() != 0
            && localExecutionOptions.localLockfreeOutput
            && context.speculating()) {
          // We aren't going to write any output, but we should either abort the remote branch early
          // or let it finish if this error can be ignored. If the latter, this call will throw
          // DynamicInterruptedException.
          context.lockOutputFiles(result.exitCode(), "", context.getFileOutErr());
        }
        return result;
      }
    }
  }

  @Override
  public boolean canExec(Spawn spawn) {
    return true;
  }

  @Override
  public boolean handlesCaching() {
    return handlesCaching;
  }

  protected Path createActionTemp(Path execRoot) throws IOException {
    return execRoot.getRelative(
        Files.createTempDirectory(
                java.nio.file.Paths.get(execRoot.getPathString()), "local-spawn-runner.")
            .getFileName()
            .toString());
  }

  private final class SubprocessHandler {
    private final Spawn spawn;
    private final SpawnExecutionContext context;
    private final SpawnMetrics.Builder spawnMetrics;
    private final Stopwatch totalTimeStopwatch;

    private final long creationTime = System.currentTimeMillis();
    private long stateStartTime = creationTime;
    private State currentState = State.INITIALIZING;
    private final Map<State, Long> stateTimes = new EnumMap<>(State.class);

    /**
     * If true, the local subprocess has already started, which means we need to clean up the output
     * tree once we get interrupted.
     */
    private boolean needCleanup = false;

    private final int id;

    public SubprocessHandler(
        Spawn spawn,
        SpawnExecutionContext context,
        SpawnMetrics.Builder spawnMetrics,
        Stopwatch totalTimeStopwatch) {
      Preconditions.checkArgument(!spawn.getArguments().isEmpty());
      this.spawn = spawn;
      this.totalTimeStopwatch = totalTimeStopwatch;
      this.context = context;
      this.spawnMetrics = spawnMetrics;
      this.id = context.getId();
      setState(State.PARSING);
    }

    public SpawnResult run()
        throws InterruptedException, IOException, ForbiddenActionInputException {
      if (localExecutionOptions.localRetriesOnCrash == 0) {
        return runOnce();
      } else {
        int attempts = 0;
        while (true) {
          // Assume that any exceptions from runOnce() come from the Java side of things, not the
          // subprocess, so let them bubble up on first occurrence. In particular, we need this to
          // be true for InterruptedException to ensure that the dynamic scheduler can stop us
          // quickly.
          Stopwatch rertyStopwatch = Stopwatch.createStarted();
          SpawnResult result = runOnce();
          if (attempts == localExecutionOptions.localRetriesOnCrash
              || !TerminationStatus.crashed(result.exitCode())) {
            return result;
          }
          stepLog(
              SEVERE,
              "Retrying crashed subprocess due to exit code %s (attempt %s)",
              result.exitCode(),
              attempts);
          Thread.sleep(attempts * 1000L);
          spawnMetrics.addRetryTimeInMs(
              result.exitCode(), (int) rertyStopwatch.elapsed().toMillis());
          attempts++;
        }
      }
    }

    private SpawnResult runOnce()
        throws InterruptedException, IOException, ForbiddenActionInputException {
      try {
        return start();
      } catch (InterruptedException | InterruptedIOException e) {
        maybeCleanupOnInterrupt();
        // Logging the exception causes a lot of noise in builds using the dynamic scheduler, and
        // the information is not very interesting, so avoid that.
        stepLog(SEVERE, "Interrupted (and cleanup finished)");
        throw e;
      } catch (Error e) {
        stepLog(SEVERE, e, UNHANDLED_EXCEPTION_MSG);
        throw e;
      } catch (ForbiddenActionInputException e) {
        stepLog(WARNING, e, "Bad input file");
        throw e;
      } catch (IOException e) {
        stepLog(SEVERE, e, "Local I/O error");
        throw e;
      } catch (RuntimeException e) {
        stepLog(SEVERE, e, UNHANDLED_EXCEPTION_MSG);
        throw new RuntimeException(UNHANDLED_EXCEPTION_MSG, e);
      }
    }

    @FormatMethod
    private void stepLog(Level level, @FormatString String fmt, Object... args) {
      stepLog(level, /*cause=*/ null, fmt, args);
    }

    @FormatMethod
    private void stepLog(
        Level level, @Nullable Throwable cause, @FormatString String fmt, Object... args) {
      String msg = String.format(fmt, args);
      String toLog = String.format("%s (#%d %s)", msg, id, desc());
      logger.at(level).withCause(cause).log("%s", toLog);
    }

    private String desc() {
      String progressMessage = spawn.getResourceOwner().getProgressMessage();
      return progressMessage != null
          ? progressMessage
          : "ActionType=" + spawn.getResourceOwner().getMnemonic();
    }

    private void setState(State newState) {
      long now = System.currentTimeMillis();
      long stepDelta = now - stateStartTime;
      stateStartTime = now;

      Long stateTimeBoxed = stateTimes.get(currentState);
      long stateTime = (stateTimeBoxed == null) ? 0 : stateTimeBoxed;
      stateTimes.put(currentState, stateTime + stepDelta);

      currentState = newState;
    }

    private String debugCmdString() {
      String cmd = SPACE_JOINER.join(spawn.getArguments());
      if (cmd.length() > 500) {
        // Shrink argstr by replacing middle of string with "...".
        return cmd.substring(0, 250) + "..." + cmd.substring(cmd.length() - 250);
      }
      return cmd;
    }

    /** Parse the request and run it locally. */
    private SpawnResult start()
        throws InterruptedException, IOException, ForbiddenActionInputException {
      logger.atInfo().log("starting local subprocess #%d, argv: %s", id, debugCmdString());

      SpawnResult.Builder spawnResultBuilder =
          getSpawnResultBuilder(context).setExecutorHostname(hostName);

      FileOutErr outErr = context.getFileOutErr();
      String actionType = spawn.getResourceOwner().getMnemonic();
      if (localExecutionOptions.allowedLocalAction != null
          && !localExecutionOptions
              .allowedLocalAction
              .regexPattern()
              .matcher(actionType)
              .matches()) {
        setState(State.PERMANENT_ERROR);
        outErr
            .getErrorStream()
            .write(
                ("Action type "
                        + actionType
                        + " is not allowed to run locally due to regex filter: "
                        + localExecutionOptions.allowedLocalAction.regexPattern()
                        + "\n")
                    .getBytes(UTF_8));
        spawnMetrics.setTotalTimeInMs((int) totalTimeStopwatch.elapsed().toMillis());
        return spawnResultBuilder
            .setStatus(Status.EXECUTION_DENIED)
            .setExitCode(LOCAL_EXEC_ERROR)
            .setFailureDetail(
                makeFailureDetail(LOCAL_EXEC_ERROR, Status.EXECUTION_DENIED, actionType))
            .setSpawnMetrics(spawnMetrics.build())
            .build();
      }

      spawnMetrics.setInputFiles(spawn.getInputFiles().memoizedFlattenAndGetSize());
      Stopwatch setupTimeStopwatch = Stopwatch.createStarted();
      for (ActionInput input : spawn.getInputFiles().toList()) {
        if (input instanceof VirtualActionInput) {
          VirtualActionInput virtualActionInput = (VirtualActionInput) input;
          Path outputPath = execRoot.getRelative(virtualActionInput.getExecPath());
          if (outputPath.exists()) {
            outputPath.delete();
          }
          outputPath.getParentDirectory().createDirectoryAndParents();
          try (OutputStream outputStream = outputPath.getOutputStream()) {
            virtualActionInput.writeTo(outputStream);
          }
          // Some of the virtual inputs are tools run as part of the execution, hence we need to set
          // executable flag.
          outputPath.setExecutable(true);
        }
      }

      stepLog(INFO, "running locally");
      setState(State.LOCAL_ACTION_RUNNING);

      Path tmpDir = createActionTemp(execRoot);
      Path statisticsPath = null;
      try {
        Path commandTmpDir = tmpDir.getRelative("work");
        commandTmpDir.createDirectory();
        ImmutableMap<String, String> environment =
            localEnvProvider.rewriteLocalEnv(
                spawn.getEnvironment(), binTools, commandTmpDir.getPathString());

        SubprocessBuilder subprocessBuilder = new SubprocessBuilder();
        subprocessBuilder.setWorkingDirectory(execRoot.getPathFile());
        subprocessBuilder.setStdout(outErr.getOutputPath().getPathFile());
        subprocessBuilder.setStderr(outErr.getErrorPath().getPathFile());
        subprocessBuilder.setEnv(environment);
        ImmutableList<String> args;
        if (processWrapper != null) {
          // If the process wrapper is enabled, we use its timeout feature, which first interrupts
          // the subprocess and only kills it after a grace period so that the subprocess can output
          // a stack trace, test log or similar, which is incredibly helpful for debugging.
          ProcessWrapper.CommandLineBuilder commandLineBuilder =
              processWrapper
                  .commandLineBuilder(spawn.getArguments())
                  .addExecutionInfo(spawn.getExecutionInfo())
                  .setTimeout(context.getTimeout());
          statisticsPath = tmpDir.getRelative("stats.out");
          commandLineBuilder.setStatisticsPath(statisticsPath);
          args = ImmutableList.copyOf(commandLineBuilder.build());
        } else {
          subprocessBuilder.setTimeoutMillis(context.getTimeout().toMillis());
          args = spawn.getArguments();
        }
        // SubprocessBuilder does not accept relative paths for the first argument, even though
        // Command does. We sometimes get relative paths here, so we need to handle it.
        File argv0 = new File(args.get(0));
        if (!argv0.isAbsolute() && argv0.getParent() != null) {
          List<String> newArgs = new ArrayList<>(args);
          newArgs.set(0, new File(execRoot.getPathFile(), newArgs.get(0)).getAbsolutePath());
          args = ImmutableList.copyOf(newArgs);
        }
        subprocessBuilder.setArgv(args);
        spawnMetrics.addSetupTimeInMs((int) setupTimeStopwatch.elapsed().toMillis());

        spawnResultBuilder.setStartTime(Instant.now());
        Stopwatch executionStopwatch = Stopwatch.createStarted();
        TerminationStatus terminationStatus;
        try (SilentCloseable c =
            Profiler.instance()
                .profile(ProfilerTask.LOCAL_PROCESS_TIME, spawn.getResourceOwner().getMnemonic())) {
          needCleanup = true;
          Subprocess subprocess = subprocessBuilder.start();
          try {
            subprocess.getOutputStream().close();
            subprocess.waitFor();
            terminationStatus =
                new TerminationStatus(subprocess.exitValue(), subprocess.timedout());
          } catch (InterruptedException | IOException e) {
            subprocess.destroyAndWait();
            throw e;
          }
          if (Thread.interrupted()) {
            stepLog(SEVERE, "Interrupted but didn't throw; status %s", terminationStatus);
            throw new InterruptedException();
          }
        } catch (InterruptedIOException e) {
          throw new InterruptedException(e.getMessage());
        } catch (IOException e) {
          String msg = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
          setState(State.PERMANENT_ERROR);
          outErr
              .getErrorStream()
              .write(
                  ("Action failed to execute: java.io.IOException: " + msg + "\n").getBytes(UTF_8));
          outErr.getErrorStream().flush();
          spawnMetrics.setTotalTimeInMs((int) totalTimeStopwatch.elapsed().toMillis());
          return spawnResultBuilder
              .setStatus(Status.EXECUTION_FAILED)
              .setExitCode(LOCAL_EXEC_ERROR)
              .setFailureDetail(
                  makeFailureDetail(LOCAL_EXEC_ERROR, Status.EXECUTION_FAILED, actionType))
              .setSpawnMetrics(spawnMetrics.build())
              .build();
        }
        setState(State.SUCCESS);
        // TODO(b/62588075): Calculate wall time inside commands instead?
        Duration wallTime = executionStopwatch.elapsed();
        spawnMetrics.setExecutionWallTimeInMs((int) wallTime.toMillis());

        boolean wasTimeout =
            terminationStatus.timedOut()
                || (processWrapper != null && wasTimeout(context.getTimeout(), wallTime));
        int exitCode =
            wasTimeout ? SpawnResult.POSIX_TIMEOUT_EXIT_CODE : terminationStatus.getRawExitCode();
        Status status =
            wasTimeout ? Status.TIMEOUT : (exitCode == 0 ? Status.SUCCESS : Status.NON_ZERO_EXIT);
        spawnResultBuilder
            .setStatus(status)
            .setExitCode(exitCode)
            .setWallTimeInMs((int) wallTime.toMillis());
        if (status != Status.SUCCESS) {
          spawnResultBuilder.setFailureDetail(makeFailureDetail(exitCode, status, actionType));
        }
        if (statisticsPath != null) {
          spawnResultBuilder.setResourceUsageFromProto(statisticsPath);
        }
        spawnMetrics.setTotalTimeInMs((int) totalTimeStopwatch.elapsed().toMillis());
        spawnResultBuilder.setSpawnMetrics(spawnMetrics.build());
        return spawnResultBuilder.build();
      } finally {
        // Delete the temp directory tree, so the next action that this thread executes will get a
        // fresh, empty temp directory.
        // File deletion tends to be slow on Windows, so deleting this tree may take several
        // seconds. Delete it after having measured the wallTime.
        try {
          tmpDir.deleteTree();
        } catch (IOException ignored) {
          // We can't handle this exception in any meaningful way, nor should we, but let's log it.
          stepLog(
              WARNING,
              "failed to delete temp directory '%s'; this might indicate that the action "
                  + "created subprocesses that didn't terminate and hold files open in that "
                  + "directory",
              tmpDir);
        }
      }
    }

    private boolean wasTimeout(Duration timeout, Duration wallTime) {
      return !timeout.isZero() && wallTime.compareTo(timeout) > 0;
    }

    /**
     * Clean up any known side-effects that the running spawn may have had on the output tree.
     *
     * <p>This is supposed to leave the output tree as it was right after {@link
     * com.google.devtools.build.lib.skyframe.SkyframeActionExecutor} created the output directories
     * for the spawn, which means that any outputs have to be deleted but any top-level directory
     * for tree artifacts has to be kept behind (and empty).
     */
    private void maybeCleanupOnInterrupt() {
      if (!localExecutionOptions.localLockfreeOutput) {
        // If we don't allow lockfree executions of local subprocesses, there is no need to clean up
        // anything: we would have already locked the output tree upfront, so we "own" it.
        return;
      }
      if (!needCleanup) {
        // If the subprocess has not yet started, there is no need to worry about checking on-disk
        // state.
        return;
      }

      for (ActionInput output : spawn.getOutputFiles()) {
        Path path = context.getPathResolver().toPath(output);
        try {
          if (path.exists()) {
            stepLog(INFO, "Clearing output %s after interrupt", path);
            if (output instanceof Artifact && ((Artifact) output).isTreeArtifact()) {
              path.deleteTreesBelow();
            } else {
              path.deleteTree();
            }
          }
        } catch (IOException e) {
          stepLog(SEVERE, e, "Cannot delete local output %s after interrupt", path);
        }
      }
    }
  }

  private static FailureDetail makeFailureDetail(int exitCode, Status status, String actionType) {
    FailureDetails.Spawn.Builder spawnFailure = FailureDetails.Spawn.newBuilder();
    switch (status) {
      case SUCCESS:
        throw new AssertionError("makeFailureDetail() called with Status == SUCCESS");
      case NON_ZERO_EXIT:
        spawnFailure.setCode(Code.NON_ZERO_EXIT).setSpawnExitCode(exitCode);
        break;
      case TIMEOUT:
        spawnFailure.setCode(Code.TIMEOUT);
        break;
      case OUT_OF_MEMORY:
        spawnFailure.setCode(Code.OUT_OF_MEMORY);
        break;
      case EXECUTION_FAILED:
        spawnFailure.setCode(Code.EXECUTION_FAILED);
        break;
      case EXECUTION_FAILED_CATASTROPHICALLY:
        spawnFailure.setCode(Code.EXECUTION_FAILED).setCatastrophic(true);
        break;
      case EXECUTION_DENIED:
        spawnFailure.setCode(Code.EXECUTION_DENIED);
        break;
      case EXECUTION_DENIED_CATASTROPHICALLY:
        spawnFailure.setCode(Code.EXECUTION_DENIED).setCatastrophic(true);
        break;
      case REMOTE_CACHE_FAILED:
        spawnFailure.setCode(Code.REMOTE_CACHE_FAILED);
        break;
    }
    return FailureDetail.newBuilder()
        .setMessage("local spawn failed for " + actionType)
        .setSpawn(spawnFailure)
        .build();
  }

  private enum State {
    INITIALIZING,
    PARSING,
    PREFETCHING_LOCAL_INPUTS,
    LOCAL_ACTION_RUNNING,
    PERMANENT_ERROR,
    SUCCESS;
  }
}
