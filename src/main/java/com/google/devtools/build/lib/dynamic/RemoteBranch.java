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
import static com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.REMOTE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy.StopConcurrentSpawns;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule.IgnoreFailureCheck;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.util.io.FileOutErr;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * The remove version of Branch. On top of the usual stop handles setting {@link
 * #delayLocalExecution} when getting a cache hit.
 */
@VisibleForTesting
class RemoteBranch extends Branch {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private LocalBranch localBranch;
  private final IgnoreFailureCheck ignoreFailureCheck;
  private final AtomicBoolean delayLocalExecution;

  public RemoteBranch(
      ActionExecutionContext actionExecutionContext,
      Spawn spawn,
      AtomicReference<DynamicMode> strategyThatCancelled,
      DynamicExecutionOptions options,
      IgnoreFailureCheck ignoreFailureCheck,
      AtomicBoolean delayLocalExecution) {
    super(actionExecutionContext, spawn, strategyThatCancelled, options);
    this.ignoreFailureCheck = ignoreFailureCheck;
    this.delayLocalExecution = delayLocalExecution;
  }

  @Override
  public DynamicMode getMode() {
    return REMOTE;
  }

  /**
   * Try to run the given spawn remotely. If successful, updates {@link #delayLocalExecution} if
   * there was a cache hit among the results.
   *
   * <p>Precondition: At least one {@code dynamic_remote_strategy} returns {@code true} from its
   * {@link SpawnStrategy#canExec canExec} method for the given {@code spawn}.
   */
  static ImmutableList<SpawnResult> runRemotely(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable StopConcurrentSpawns stopConcurrentSpawns,
      AtomicBoolean delayLocalExecution)
      throws ExecException, InterruptedException {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);

    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, REMOTE)) {
      if (strategy.canExec(spawn, actionExecutionContext)) {
        ImmutableList<SpawnResult> results =
            strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns);
        if (results == null) {
          actionExecutionContext
              .getEventHandler()
              .handle(
                  Event.warn(
                      String.format(
                          "Remote strategy %s for %s target %s returned null, which it shouldn't"
                              + " do.",
                          strategy, spawn.getMnemonic(), spawn.getResourceOwner().prettyPrint())));
        }
        for (SpawnResult r : results) {
          if (r.isCacheHit()) {
            delayLocalExecution.set(true);
            break;
          }
        }
        return results;
      }
    }
    throw new AssertionError("canExec passed but no usable remote strategy for action " + spawn);
  }

  /** Sets up the future for this branch, once the other branch is available. */
  public void prepareFuture(LocalBranch localBranch) {
    this.localBranch = localBranch;
    future.addListener(
        () -> {
          if (starting.compareAndSet(true, false)) {
            // If the remote branch got cancelled before even starting, we release its semaphore
            // for it.
            done.release();
          }
          if (!future.isCancelled()) {
            localBranch.cancel();
          }
          if (options.debugSpawnScheduler) {
            logger.atInfo().log(
                "In listener callback, the future of the remote branch is %s",
                future.state().name());
            try {
              future.get();
            } catch (InterruptedException | ExecutionException e) {
              logger.atInfo().withCause(e).log(
                  "The future of the remote branch failed with an exception.");
            }
          }
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public ImmutableList<SpawnResult> callImpl(ActionExecutionContext context)
      throws InterruptedException, ExecException {
    if (localBranch == null) {
      throw new IllegalStateException("Initialize not called");
    }
    try {
      if (!starting.compareAndSet(true, false)) {
        // If we ever get here, it's because we were cancelled early and the listener
        // ran first. Just make sure that's the case.
        checkState(Thread.interrupted());
        throw new InterruptedException();
      }
      return runRemotely(
          spawn,
          context,
          (exitCode, errorMessage, outErr) -> {
            if (!future.isCancelled()) {
              maybeIgnoreFailure(exitCode, errorMessage, outErr);
            }
            DynamicSpawnStrategy.stopBranch(
                localBranch, this, strategyThatCancelled, options, this.context);
          },
          delayLocalExecution);
    } catch (DynamicInterruptedException e) {
      if (options.debugSpawnScheduler) {
        logger.atInfo().log(
            "Remote branch of %s self-cancelling with %s: '%s'",
            spawn.getResourceOwner().prettyPrint(), e.getClass().getSimpleName(), e.getMessage());
      }
      // This exception can be thrown due to races in stopBranch(), in which case
      // the branch that lost the race may not have been cancelled yet. Cancel it here
      // to prevent the listener from cross-cancelling.
      future.cancel(true);
      throw e;
    } catch (
        @SuppressWarnings("InterruptedExceptionSwallowed")
        Throwable e) {
      if (options.debugSpawnScheduler) {
        logger.atInfo().log(
            "Remote branch of %s failed with %s: '%s'",
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
  protected synchronized void maybeIgnoreFailure(
      int exitCode, String errorMessage, FileOutErr outErr) throws DynamicInterruptedException {
    if (exitCode == 0 || ignoreFailureCheck == null) {
      return;
    }
    synchronized (spawn) {
      if (ignoreFailureCheck.canIgnoreFailure(
          spawn, context, exitCode, errorMessage, outErr, false)) {
        throw new DynamicInterruptedException(
            String.format(
                "Remote branch of %s cancelling self in favor of local.",
                spawn.getResourceOwner().prettyPrint()));
      }
    }
  }
}
