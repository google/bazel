// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.dynamic;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.LOCAL;
import static com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.REMOTE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule.IgnoreFailureCheck;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * A spawn strategy that speeds up incremental builds while not slowing down full builds.
 *
 * <p>This strategy tries to run spawn actions on the local and remote machine at the same time, and
 * picks the spawn action that completes first. This gives the benefits of remote execution on full
 * builds, and local execution on incremental builds.
 *
 * <p>One might ask, why we don't run spawns on the workstation all the time and just "spill over"
 * actions to remote execution when there are no local resources available. This would work, except
 * that the cost of transferring action inputs and outputs from the local machine to and from remote
 * executors over the network is way too high - there is no point in executing an action locally and
 * save 0.5s of time, when it then takes us 5 seconds to upload the results to remote executors for
 * another action that's scheduled to run there.
 */
public class DynamicSpawnStrategy implements SpawnStrategy {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ListeningExecutorService executorService;
  private final DynamicExecutionOptions options;
  private final Function<Spawn, ExecutionPolicy> getExecutionPolicy;

  /**
   * Set to true by the first action that completes remotely. Until that happens, all local actions
   * are delayed by the amount given in {@link DynamicExecutionOptions#localExecutionDelay}.
   *
   * <p>This is a rather simple approach to make it possible to score a cache hit on remote
   * execution before even trying to start the action locally. This saves resources that would
   * otherwise be wasted by continuously starting and immediately killing local processes. One
   * possibility for improvement would be to establish a reporting mechanism from strategies back to
   * here, where we delay starting locally until the remote strategy tells us that the action isn't
   * a cache hit.
   */
  private final AtomicBoolean delayLocalExecution = new AtomicBoolean(false);

  private final Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution;

  /** A callback that allows checking if a given failure can be ignored on one branch. */
  private final IgnoreFailureCheck ignoreFailureCheck;

  /** Limit on how many threads we should use for dynamic execution. */
  private final ShrinkableSemaphore threadLimiter;

  /** Set of jobs that are waiting for local execution. */
  private final Deque<LocalBranch> waitingLocalJobs = new ArrayDeque<>();

  /**
   * Constructs a {@code DynamicSpawnStrategy}.
   *
   * @param executorService an {@link ExecutorService} that will be used to run Spawn actions.
   * @param options The options for dynamic execution.
   * @param getExecutionPolicy Function that will give an execution policy for a given {@link
   *     Spawn}.
   * @param getPostProcessingSpawnForLocalExecution A function that returns any post-processing
   *     spawns that should be run after finishing running a spawn locally.
   * @param numCpus The number of CPUs allowed for local execution (--local_resources).
   * @param jobs The maximum number of jobs (--jobs parameter).
   * @param ignoreFailureCheck A callback to check if a failure on one branch should be allowed to
   *     be ignored in favor of the other branch.
   */
  public DynamicSpawnStrategy(
      ExecutorService executorService,
      DynamicExecutionOptions options,
      Function<Spawn, ExecutionPolicy> getExecutionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution,
      int numCpus,
      int jobs,
      IgnoreFailureCheck ignoreFailureCheck) {
    this.executorService = MoreExecutors.listeningDecorator(executorService);
    this.options = options;
    this.getExecutionPolicy = getExecutionPolicy;
    this.getExtraSpawnForLocalExecution = getPostProcessingSpawnForLocalExecution;
    this.threadLimiter =
        new ShrinkableSemaphore(
            options.localLoadFactor > 0 ? numCpus : jobs, jobs, options.localLoadFactor);
    this.ignoreFailureCheck = ignoreFailureCheck;
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionContextRegistry.getContext(DynamicStrategyRegistry.class);

    return canExecLocal(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry)
        || canExecRemote(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry);
  }

  private static boolean canExecLocal(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry acr,
      DynamicStrategyRegistry dsr) {
    return getLocalStrategy(spawn, executionPolicy, acr, dsr) != null;
  }

  @Nullable
  private static SandboxedSpawnStrategy getLocalStrategy(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry acr,
      DynamicStrategyRegistry dsr) {
    if (!executionPolicy.canRunLocally()) {
      return null;
    }
    for (SandboxedSpawnStrategy s : dsr.getDynamicSpawnActionContexts(spawn, LOCAL)) {
      if ((s.canExec(spawn, acr) || s.canExecWithLegacyFallback(spawn, acr))) {
        return s;
      }
    }
    return null;
  }

  private static boolean canExecRemote(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry acr,
      DynamicStrategyRegistry dsr) {
    return getRemoteStrategy(spawn, executionPolicy, acr, dsr) != null;
  }

  @Nullable
  private static SandboxedSpawnStrategy getRemoteStrategy(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry acr,
      DynamicStrategyRegistry dsr) {
    if (!executionPolicy.canRunRemotely()) {
      return null;
    }

    for (SandboxedSpawnStrategy s : dsr.getDynamicSpawnActionContexts(spawn, REMOTE)) {
      if (s.canExec(spawn, acr)) {
        return s;
      }
    }
    return null;
  }

  @Override
  public ImmutableList<SpawnResult> exec(
      final Spawn spawn, final ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> nonDynamicResults =
        maybeExecuteNonDynamically(spawn, actionExecutionContext);
    if (nonDynamicResults != null) {
      return nonDynamicResults;
    }

    debugLog("Dynamic execution of %s beginning%n", getSpawnReadableId(spawn));
    // else both can exec. Fallthrough to below.

    AtomicReference<DynamicMode> strategyThatCancelled = new AtomicReference<>(null);

    LocalBranch localBranch =
        new LocalBranch(
            actionExecutionContext,
            spawn,
            strategyThatCancelled,
            options,
            ignoreFailureCheck,
            getExtraSpawnForLocalExecution,
            delayLocalExecution);
    RemoteBranch remoteBranch =
        new RemoteBranch(
            actionExecutionContext,
            spawn,
            strategyThatCancelled,
            options,
            ignoreFailureCheck,
            delayLocalExecution);
    localBranch.prepareFuture(remoteBranch);
    remoteBranch.prepareFuture(localBranch);
    synchronized (waitingLocalJobs) {
      waitingLocalJobs.add(localBranch);
      tryScheduleLocalJob();
    }
    remoteBranch.execute(executorService);

    ImmutableList<SpawnResult> results = null;
    try {
      results = waitBranches(localBranch, remoteBranch, spawn, options, actionExecutionContext);
      return results;
    } finally {
      checkState(localBranch.isDone());
      checkState(remoteBranch.isDone());

      if (results != null && !results.isEmpty()) {
        updateStrategyWinner(actionExecutionContext, spawn, results.get(0), strategyThatCancelled);
      }

      synchronized (waitingLocalJobs) {
        if (!waitingLocalJobs.remove(localBranch)) {
          threadLimiter.release();
          tryScheduleLocalJob();
        }
      }
      debugLog(
          "Dynamic execution of %s ended with local %s, remote %s%n",
          getSpawnReadableId(spawn),
          localBranch.isCancelled() ? "cancelled" : "done",
          remoteBranch.isCancelled() ? "cancelled" : "done");
    }
  }

  void updateStrategyWinner(
      ActionExecutionContext context,
      Spawn spawn,
      SpawnResult result,
      AtomicReference<DynamicMode> strategyThatCancelled) {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        context.getContext(DynamicStrategyRegistry.class);
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);

    // In case of remote runner, we could have "runner-name-cached" instead of "runner-name", in
    // this case we want more precise name of branch.
    String winner = result.getRunnerName();
    SandboxedSpawnStrategy localStrategy =
        getLocalStrategy(spawn, executionPolicy, context, dynamicStrategyRegistry);
    SandboxedSpawnStrategy remoteStrategy =
        getRemoteStrategy(spawn, executionPolicy, context, dynamicStrategyRegistry);

    if (localStrategy == null || remoteStrategy == null) {
      return;
    }

    String localName = localStrategy.toString();
    String remoteName = remoteStrategy.toString();

    DynamicMode winnerBranchType = null;
    if (strategyThatCancelled.get() == null) {
      return;
    }

    switch (strategyThatCancelled.get()) {
      case LOCAL -> {
        localName = winner;
        winnerBranchType = LOCAL;
      }
      case REMOTE -> {
        remoteName = winner;
        winnerBranchType = REMOTE;
      }
    }

    context
        .getEventHandler()
        .post(
            new DynamicExecutionFinishedEvent(
                spawn.getMnemonic(), localName, remoteName, winnerBranchType));
  }

  /**
   * Tries to schedule as many local jobs as are permitted by {@link #threadLimiter}. "Scheduling"
   * here means putting it on a thread and making it start the normal strategy execution, but it
   * will still have to wait for resources, so it may not execute for a while.
   */
  private void tryScheduleLocalJob() {
    synchronized (waitingLocalJobs) {
      threadLimiter.updateLoad(waitingLocalJobs.size());
      while (!waitingLocalJobs.isEmpty() && threadLimiter.tryAcquire()) {
        LocalBranch job;
        // TODO(b/120910324): Prioritize jobs where the remote branch has already failed.
        if (options.slowRemoteTime != null
            && options.slowRemoteTime.compareTo(Duration.ZERO) > 0
            && waitingLocalJobs.peekFirst().getAge().compareTo(options.slowRemoteTime) > 0) {
          job = waitingLocalJobs.pollFirst();
        } else {
          job = waitingLocalJobs.pollLast();
        }
        job.execute(executorService);
      }
    }
  }

  /**
   * Checks if this action should be executed dynamically, and if not executes it locally or
   * remotely as applicable, or throws an exception if it cannot be executed at all.
   *
   * @param spawn Spawn in the process of being executed.
   * @param actionExecutionContext Execution context
   * @return Results from execution if the action was executed (possibly empty) or null if this
   *     action can be executed dynamically.
   * @throws ExecException If we tried to execute and executed failed.
   * @throws InterruptedException If we tried to execute and got interrupted.
   */
  @Nullable
  @VisibleForTesting
  ImmutableList<SpawnResult> maybeExecuteNonDynamically(
      Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    Spawn postProcessingSpawn = getExtraSpawnForLocalExecution.apply(spawn).orElse(null);
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);
    ExecutionPolicy postProcessingSpawnExecutionPolicy =
        postProcessingSpawn == null ? null : getExecutionPolicy.apply(postProcessingSpawn);
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);

    boolean spawnLocalCanExec =
        canExecLocal(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry);
    boolean postProcessingSpawnLocalCanExec =
        postProcessingSpawn == null
            || canExecLocal(
                postProcessingSpawn,
                postProcessingSpawnExecutionPolicy,
                actionExecutionContext,
                dynamicStrategyRegistry);
    // To declare a spawn being executable in local, we need to make sure that the post-processing
    // spawn is also executable in local.
    boolean localCanExec = spawnLocalCanExec && postProcessingSpawnLocalCanExec;
    boolean remoteCanExec =
        canExecRemote(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry);

    if (!localCanExec && !remoteCanExec) {
      @SuppressWarnings("RedundantSetterCall")
      FailureDetail failure =
          FailureDetail.newBuilder()
              .setMessage(
                  getNoExecutableUserExecExceptionMessage(
                      spawn,
                      spawnLocalCanExec,
                      executionPolicy,
                      postProcessingSpawn,
                      postProcessingSpawnLocalCanExec,
                      postProcessingSpawnExecutionPolicy))
              // This use of `setDynamicExecution` was overwritten by a call to `setSpawn` below, as
              // they're in a oneof. This may be a bug! Please fix, or delete this redundant call.
              .setDynamicExecution(
                  DynamicExecution.newBuilder().setCode(Code.NO_USABLE_STRATEGY_FOUND).build())
              .setSpawn(
                  FailureDetails.Spawn.newBuilder()
                      .setCode(FailureDetails.Spawn.Code.NO_USABLE_STRATEGY_FOUND)
                      .build())
              .build();
      debugLog(
          "Dynamic execution of %s can be done neither locally nor remotely%n",
          getSpawnReadableId(spawn));
      throw new UserExecException(failure);
    } else if (!localCanExec && remoteCanExec) {
      String spawnExplanation =
          String.format(
              "Local execution policy of the spawn %s dynamic execution, local strategies of the"
                  + " spawn are %s",
              executionPolicy.canRunLocally() ? "allows" : "forbids",
              dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.LOCAL));
      String postProcessingSpawnExplanation =
          postProcessingSpawn == null
              ? "the post-processing spawn doesn't exist"
              : String.format(
                  "local execution policy of the post-processing spawn %s dynamic execution, local"
                      + " strategies of the post-processing spawn are %s",
                  postProcessingSpawnExecutionPolicy.canRunLocally() ? "allows" : "forbids",
                  dynamicStrategyRegistry.getDynamicSpawnActionContexts(
                      postProcessingSpawn, DynamicMode.LOCAL));
      debugLog(
          "Dynamic execution of %s can only be done remotely: %s. And %s.%n",
          getSpawnReadableId(spawn), spawnExplanation, postProcessingSpawnExplanation);
      return RemoteBranch.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution);
    } else if (localCanExec && !remoteCanExec) {
      debugLog(
          "Dynamic execution of %s can only be done locally: Remote execution policy %s it, "
              + "remote strategies are %s.%n",
          getSpawnReadableId(spawn),
          executionPolicy.canRunRemotely() ? "allows" : "forbids",
          dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, REMOTE));
      return LocalBranch.runLocally(
          spawn, actionExecutionContext, null, getExtraSpawnForLocalExecution);
    } else if (options.excludeTools) {
      if (spawn.getResourceOwner().getOwner().isBuildConfigurationForTool()) {
        return RemoteBranch.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution);
      }
    }
    return null;
  }

  /**
   * Returns an error string for being unable to execute locally and/or remotely the given execution
   * state.
   *
   * <p>Usage note, this method is only to be called after an impossible condition is already
   * detected by the caller, as all this does is give an error string to put in the exception.
   *
   * <p>When the spawn is executable in local but the post-processing spawn is not, it's also not
   * allowed to execute local actions. For this reason, we should log the information for both the
   * spawn and the post-processing spawn.
   *
   * @param spawn The action that needs to be executed.
   * @param spawnLocalCanExec Whether the spawn can be executed locally or not.
   * @param spawnExecutionPolicy The execution policy for the spawn.
   * @param postProcessingSpawn The action that needs to be executed following the spawn.
   * @param postProcessingSpawnLocalCanExec Whether the post-processing spawn can be executed
   *     locally or not.
   * @param postProcessingSpawnExecutionPolicy The execution policy for the post-processing spawn.
   */
  private static String getNoExecutableUserExecExceptionMessage(
      Spawn spawn,
      boolean spawnLocalCanExec,
      ExecutionPolicy spawnExecutionPolicy,
      Spawn postProcessingSpawn,
      boolean postProcessingSpawnLocalCanExec,
      ExecutionPolicy postProcessingSpawnExecutionPolicy) {
    // TODO(b/188402092): Consider using Spawn.toString() when the mnemonic is included in the
    // output unconditionally.
    StringBuilder msg = new StringBuilder();
    if (!spawnLocalCanExec) {
      msg.append("Spawn is not executable in local: ")
          .append(getSpawnNotExecutableReason(spawn, spawnExecutionPolicy));
    }
    if (!postProcessingSpawnLocalCanExec) {
      msg.append("Post-Processing Spawn is not executable in local: ")
          .append(
              getSpawnNotExecutableReason(postProcessingSpawn, postProcessingSpawnExecutionPolicy));
    }
    return msg.toString();
  }

  private static String getSpawnNotExecutableReason(
      Spawn spawn, ExecutionPolicy spawnExecutionPolicy) {
    StringBuilder msg = new StringBuilder();
    if (!spawnExecutionPolicy.canRunLocally() && !spawnExecutionPolicy.canRunRemotely()) {
      msg.append("Neither local nor remote execution allowed for action ");
    } else if (!spawnExecutionPolicy.canRunRemotely()) {
      msg.append(
          "No usable dynamic_local_strategy found (and remote execution disabled) for action ");
    } else if (!spawnExecutionPolicy.canRunLocally()) {
      msg.append(
          "No usable dynamic_remote_strategy found (and local execution disabled) for action ");
    } else {
      msg.append("No usable dynamic_local_strategy or dynamic_remote_strategy found for action ");
    }
    msg.append(spawn.getMnemonic()).append(". ");
    return msg.toString();
  }

  /**
   * Waits for the two branches of a spawn's execution to complete.
   *
   * <p>This guarantees that the two branches are stopped both on successful termination and on an
   * exception.
   *
   * @param localBranch the future running the local side of the spawn. This future must cancel
   *     {@code remoteBranch} at some point during its successful execution to guarantee
   *     termination. If we encounter an execution error, or if we are interrupted, then we handle
   *     such cancellation here.
   * @param remoteBranch the future running the remote side of the spawn. Same restrictions apply as
   *     in {@code localBranch}, but in the symmetric direction.
   * @param options the options relevant for dynamic execution
   * @param context execution context object
   * @return the result of the branch that terminates first
   * @throws ExecException the execution error of the spawn that terminated first
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @VisibleForTesting
  static ImmutableList<SpawnResult> waitBranches(
      LocalBranch localBranch,
      RemoteBranch remoteBranch,
      Spawn spawn,
      DynamicExecutionOptions options,
      ActionExecutionContext context)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> localResult;
    try {
      localResult = waitBranch(localBranch, options, context);
    } catch (ExecException | InterruptedException | RuntimeException e) {
      if (options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Cancelling remote branch of %s after local exception %s",
                        getSpawnReadableId(spawn), e.getMessage())));
      }
      remoteBranch.cancel();
      throw e;
    }

    ImmutableList<SpawnResult> remoteResult = waitBranch(remoteBranch, options, context);

    if (remoteResult != null && localResult != null) {
      throw new AssertionError(
          String.format(
              "Neither branch of %s cancelled the other one. Local was %s and remote was %s.",
              getSpawnReadableId(spawn), localBranch.branchState(), remoteBranch.branchState()));
    } else if (localResult != null) {
      return localResult;
    } else if (remoteResult != null) {
      return remoteResult;
    } else {
      // TODO(b/173153395): Sometimes gets thrown for currently unknown reasons.
      // (sometimes happens in relation to the whole dynamic execution being cancelled)
      throw new AssertionError(
          String.format(
              "Neither branch of %s completed. Local was %s and remote was %s.",
              getSpawnReadableId(spawn), localBranch.branchState(), remoteBranch.branchState()));
    }
  }

  /**
   * Waits for a branch (a spawn execution) to complete.
   *
   * @param branch the future running the spawn
   * @param options the options relevant for dynamic execution
   * @param context execution context object
   * @return the spawn result if the execution terminated successfully, or null if the branch was
   *     cancelled
   * @throws ExecException the execution error of the spawn if it failed
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @Nullable
  private static ImmutableList<SpawnResult> waitBranch(
      Branch branch, DynamicExecutionOptions options, ActionExecutionContext context)
      throws ExecException, InterruptedException {
    DynamicMode mode = branch.getMode();
    try {
      ImmutableList<SpawnResult> spawnResults = branch.getResults();
      if (spawnResults == null && options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Null results from %s branch of %s",
                        mode, getSpawnReadableId(branch.getSpawn()))));
      }
      return spawnResults;
    } catch (CancellationException e) {
      if (options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "CancellationException of %s branch of %s, returning null",
                        mode, getSpawnReadableId(branch.getSpawn()))));
      }
      return null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ExecException execException) {
        throw execException;
      } else if (cause instanceof InterruptedException) {
        // If the branch was interrupted, it might be due to a user interrupt or due to our request
        // for cancellation. Assume the latter here because if this was actually a user interrupt,
        // our own get() would have been interrupted as well. It makes no sense to propagate the
        // interrupt status across threads.
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Caught InterruptedException from ExecutionException for %s branch of %s,"
                            + " which may cause a crash.",
                        mode, getSpawnReadableId(branch.getSpawn()))));
        return null;
      } else {
        // Even though we cannot enforce this in the future's signature (but we do in Branch#call),
        // we only expect the exception types we validated above. Still, unchecked exceptions could
        // propagate, so just let them bubble up.
        Throwables.throwIfUnchecked(cause);
        throw new AssertionError(
            String.format(
                "Unexpected exception type %s from %s strategy.exec() for %s",
                cause.getClass().getName(), mode, getSpawnReadableId(branch.getSpawn())));
      }
    } catch (InterruptedException e) {
      branch.cancel();
      throw e;
    }
  }

  /**
   * Cancels and waits for a branch (a spawn execution) to terminate.
   *
   * <p>This is intended to be used as the body of the {@link
   * SandboxedSpawnStrategy.StopConcurrentSpawns} lambda passed to the spawn runners. Each strategy
   * may call this at most once.
   *
   * @param otherBranch The other branch, the one that should be cancelled.
   * @param cancellingBranch The branch that is performing the cancellation.
   * @param strategyThatCancelled name of the first strategy that executed this method, or a null
   *     reference if this is the first time this method is called. If not null, we expect the value
   *     referenced by this to be different than {@code cancellingStrategy}, or else we have a bug.
   * @param options The options for dynamic execution.
   * @param context The context of this action execution.
   * @throws InterruptedException if we get interrupted for any reason trying to cancel the future
   * @throws DynamicInterruptedException if we lost a race against another strategy trying to cancel
   *     us
   */
  static void stopBranch(
      Branch otherBranch,
      Branch cancellingBranch,
      AtomicReference<DynamicMode> strategyThatCancelled,
      DynamicExecutionOptions options,
      ActionExecutionContext context)
      throws InterruptedException {
    DynamicMode cancellingStrategy = cancellingBranch.getMode();
    if (cancellingBranch.isCancelled()) {
      throw new DynamicInterruptedException(
          String.format(
              "Execution of %s strategy was cancelled just before it could get the lock.",
              cancellingStrategy));
    }
    // This multi-step, unlocked access to "strategyThatCancelled" is valid because, for a given
    // value of "cancellingStrategy", we do not expect concurrent calls to this method. (If there
    // are, we are in big trouble.)
    DynamicMode current = strategyThatCancelled.get();
    if (cancellingStrategy.equals(current)) {
      throw new AssertionError(
          "stopBranch called more than once by "
              + cancellingStrategy
              + " on "
              + getSpawnReadableId(cancellingBranch.getSpawn()));
    } else {
      // Protect against the two branches from cancelling each other. The first branch to set the
      // reference to its own identifier wins and is allowed to issue the cancellation; the other
      // branch just has to give up execution.
      if (strategyThatCancelled.compareAndSet(null, cancellingStrategy)) {
        if (options.debugSpawnScheduler) {
          context
              .getEventHandler()
              .handle(
                  Event.info(
                      String.format(
                          "%s branch of %s finished and was %s",
                          strategyThatCancelled.get(),
                          getSpawnReadableId(cancellingBranch.getSpawn()),
                          cancellingBranch.isCancelled() ? "cancelled" : "not cancelled")));
        }

        try (SilentCloseable c =
            Profiler.instance()
                .profile(
                    ProfilerTask.DYNAMIC_LOCK,
                    () ->
                        String.format(
                            "Cancelling %s branch of %s",
                            cancellingStrategy.other(),
                            getSpawnReadableId(cancellingBranch.getSpawn())))) {

          if (!otherBranch.cancel()) {
            // This can happen if the other branch is local under local_lockfree and has returned
            // its result but not yet cancelled this branch, or if the other branch was already
            // cancelled for other reasons. In the latter case, we are good to continue.
            if (!otherBranch.isCancelled()) {
              throw new DynamicInterruptedException(
                  String.format(
                      "Execution of %s strategy stopped because %s strategy could not be cancelled",
                      cancellingStrategy, cancellingStrategy.other()));
            }
          }
          otherBranch.getDoneSemaphore().acquire();
        }
      } else {
        throw new DynamicInterruptedException(
            String.format(
                "Execution of %s strategy stopped because %s strategy finished first",
                cancellingStrategy, strategyThatCancelled.get()));
      }
    }
  }

  @FormatMethod
  private void stepLog(
      Level level, @Nullable Throwable cause, @FormatString String fmt, Object... args) {
    logger.at(level).withCause(cause).logVarargs(fmt, args);
  }

  @FormatMethod
  private void debugLog(String fmt, Object... args) {
    if (options.debugSpawnScheduler) {
      stepLog(Level.FINE, null, fmt, args);
    }
  }

  @Override
  public void usedContext(ActionContext.ActionContextRegistry actionContextRegistry) {
    actionContextRegistry
        .getContext(DynamicStrategyRegistry.class)
        .notifyUsedDynamic(actionContextRegistry);
  }

  @Override
  public String toString() {
    return "dynamic";
  }

  private static String getSpawnReadableId(Spawn spawn) {
    ActionExecutionMetadata action = spawn.getResourceOwner();
    Artifact primaryOutput = action.getPrimaryOutput();
    // In some cases, primary output could be null despite the method promises. And in that case, we
    // can't use action.prettyPrint as it assumes a non-null primary output.
    if (primaryOutput == null) {
      String label = "";
      if (action.getOwner() != null && action.getOwner().getLabel() != null) {
        label = " " + action.getOwner().getLabel().toString();
      }
      return spawn.getMnemonic() + label;
    }

    return primaryOutput.prettyPrint();
  }
}
