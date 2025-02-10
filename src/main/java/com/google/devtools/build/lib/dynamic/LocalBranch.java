// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule.IgnoreFailureCheck;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.util.io.FileOutErr;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The local version of a Branch. On top of normal Branch things, this handles delaying after remote
 * cache hits and passing the extra-spawn function.
 */
@VisibleForTesting
class LocalBranch extends Branch {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private RemoteBranch remoteBranch;
  private final IgnoreFailureCheck ignoreFailureCheck;
  private final Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution;
  private final AtomicBoolean delayLocalExecution;
  private final Instant creationTime = Instant.now();

  public LocalBranch(
      ActionExecutionContext actionExecutionContext,
      Spawn spawn,
      AtomicReference<DynamicMode> strategyThatCancelled,
      DynamicExecutionOptions options,
      IgnoreFailureCheck ignoreFailureCheck,
      Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution,
      AtomicBoolean delayLocalExecution) {
    super(actionExecutionContext, spawn, strategyThatCancelled, options);
    this.ignoreFailureCheck = ignoreFailureCheck;
    this.getExtraSpawnForLocalExecution = getExtraSpawnForLocalExecution;
    this.delayLocalExecution = delayLocalExecution;
  }

  @Override
  public DynamicMode getMode() {
    return LOCAL;
  }

  public Duration getAge() {
    return Duration.between(creationTime, Instant.now());
  }

  /**
   * Try to run the given spawn locally.
   *
   * <p>Precondition: At least one {@code dynamic_local_strategy} returns {@code true} from its
   * {@link SpawnStrategy#canExec canExec} method for the given {@code spawn}.
   */
  static ImmutableList<SpawnResult> runLocally(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns,
      Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> spawnResult =
        runSpawnLocally(spawn, actionExecutionContext, stopConcurrentSpawns);
    if (spawnResult.stream().anyMatch(result -> result.status() != Status.SUCCESS)) {
      return spawnResult;
    }

    Optional<Spawn> extraSpawn = getExtraSpawnForLocalExecution.apply(spawn);
    if (!extraSpawn.isPresent()) {
      return spawnResult;
    }

    // The remote branch was already cancelled -- we are holding the output lock during the
    // execution of the extra spawn.
    ImmutableList<SpawnResult> extraSpawnResult =
        runSpawnLocally(extraSpawn.get(), actionExecutionContext, null);
    return ImmutableList.<SpawnResult>builderWithExpectedSize(
            spawnResult.size() + extraSpawnResult.size())
        .addAll(spawnResult)
        .addAll(extraSpawnResult)
        .build();
  }

  private static ImmutableList<SpawnResult> runSpawnLocally(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns)
      throws ExecException, InterruptedException {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);

    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, LOCAL)) {
      if (strategy.canExec(spawn, actionExecutionContext)
          || strategy.canExecWithLegacyFallback(spawn, actionExecutionContext)) {
        ImmutableList<SpawnResult> results =
            strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns);
        if (results == null) {
          logger.atWarning().log(
              "Local strategy %s for %s target %s returned null, which it shouldn't do.",
              strategy, spawn.getMnemonic(), spawn.getResourceOwner().prettyPrint());
        }

        return results;
      }
    }
    throw new AssertionError("canExec passed but no usable local strategy for action " + spawn);
  }

  /** Sets up the {@link Future} used in the local branch to know what remote branch to cancel. */
  protected void prepareFuture(RemoteBranch remoteBranch) {
    // TODO(b/203094728): Maybe generify this method and move it up.
    this.remoteBranch = remoteBranch;
    future.addListener(
        () -> {
          if (starting.compareAndSet(true, false)) {
            // If the local branch got cancelled before even starting, we release its semaphore
            // for it.
            done.release();
          }
          if (!future.isCancelled()) {
            remoteBranch.cancel();
          }
          if (options.debugSpawnScheduler) {
            logger.atInfo().log(
                "In listener callback, the future of the local branch is %s",
                future.state().name());
            try {
              future.get();
            } catch (InterruptedException | ExecutionException e) {
              logger.atInfo().withCause(e).log(
                  "The future of the local branch failed with an exception.");
            }
          }
        },
        MoreExecutors.directExecutor());
  }

  @Override
  ImmutableList<SpawnResult> callImpl(ActionExecutionContext context)
      throws InterruptedException, ExecException {
    try {
      if (!starting.compareAndSet(true, false)) {
        // If we ever get here, it's because we were cancelled early and the listener
        // ran first. Just make sure that's the case.
        checkState(Thread.interrupted());
        throw new InterruptedException();
      }
      if (delayLocalExecution.get()) {
        try (SilentCloseable c = Profiler.instance().profile("delay local branch")) {
          Thread.sleep(options.localExecutionDelay);
        }
      }
      return runLocally(
          spawn,
          context,
          (exitCode, errorMessage, outErr) -> {
            if (!future.isCancelled()) {
              maybeIgnoreFailure(exitCode, errorMessage, outErr);
            }
            DynamicSpawnStrategy.stopBranch(
                remoteBranch, this, strategyThatCancelled, options, this.context);
          },
          getExtraSpawnForLocalExecution);
    } catch (DynamicInterruptedException e) {
      if (options.debugSpawnScheduler) {
        logger.atInfo().log(
            "Local branch of %s self-cancelling with %s: '%s'",
            spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage());
      }
      // This exception can be thrown due to races in stopBranch(), in which case
      // the branch that lost the race may not have been cancelled yet. Cancel it here
      // to prevent the listener from cross-cancelling.
      cancel();
      throw e;
    } catch (
        @SuppressWarnings("InterruptedExceptionSwallowed")
        Throwable e) {
      if (options.debugSpawnScheduler) {
        logger.atInfo().log(
            "Local branch of %s failed with %s: '%s'",
            spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage());
      }
      throw e;
    } finally {
      done.release();
    }
  }

  /**
   * Called when execution failed, to check if we should allow the other branch to continue instead
   * of failing.
   *
   * @throws DynamicInterruptedException if this failure can be ignored in favor of the result of
   *     the other branch.
   */
  protected void maybeIgnoreFailure(int exitCode, String errorMessage, FileOutErr outErr)
      throws DynamicInterruptedException {
    if (exitCode == 0 || ignoreFailureCheck == null) {
      return;
    }
    synchronized (spawn) {
      if (ignoreFailureCheck.canIgnoreFailure(
          spawn, context, exitCode, errorMessage, outErr, true)) {
        throw new DynamicInterruptedException(
            String.format(
                "Local branch of %s cancelling self in favor of remote.",
                spawn.getResourceOwner().prettyPrint()));
      }
    }
  }
}
