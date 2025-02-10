// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.LostInputsExecException;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.SpawnCache.CacheHandle;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/** Abstract common ancestor for spawn strategies implementing the common parts. */
public abstract class AbstractSpawnStrategy implements SandboxedSpawnStrategy {

  /**
   * Last unique identifier assigned to a spawn by this strategy.
   *
   * <p>These identifiers must be unique per strategy within the context of a Bazel server instance
   * to avoid cross-contamination across actions in case we perform asynchronous deletions.
   */
  private static final AtomicInteger execCount = new AtomicInteger();

  private final SpawnInputExpander spawnInputExpander;
  private final SpawnRunner spawnRunner;
  private final ExecutionOptions executionOptions;

  protected AbstractSpawnStrategy(
      Path execRoot, SpawnRunner spawnRunner, ExecutionOptions executionOptions) {
    this.spawnInputExpander = new SpawnInputExpander(execRoot);
    this.spawnRunner = spawnRunner;
    this.executionOptions = executionOptions;
  }

  /**
   * Gets the {@link SpawnRunner} that this {@link AbstractSpawnStrategy} uses to actually run
   * spawns.
   *
   * <p>This is considered a stop-gap until we refactor the entire SpawnStrategy / SpawnRunner
   * mechanism to no longer need Spawn strategies.
   */
  public SpawnRunner getSpawnRunner() {
    return spawnRunner;
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    return spawnRunner.canExec(spawn);
  }

  @Override
  public boolean canExecWithLegacyFallback(
      Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    return spawnRunner.canExecWithLegacyFallback(spawn);
  }

  @Override
  public ImmutableList<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return exec(spawn, actionExecutionContext, null);
  }

  @Override
  public ImmutableList<SpawnResult> exec(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns)
      throws ExecException, InterruptedException {
    actionExecutionContext.maybeReportSubcommand(spawn, spawnRunner.getName());

    final Duration timeout = Spawns.getTimeout(spawn);
    SpawnExecutionContext context =
        new SpawnExecutionContextImpl(spawn, actionExecutionContext, stopConcurrentSpawns, timeout);

    // Avoid caching for runners which handle caching internally e.g. RemoteSpawnRunner.
    SpawnCache cache =
        spawnRunner.handlesCaching()
            ? SpawnCache.NO_CACHE
            : actionExecutionContext.getContext(SpawnCache.class);

    // In production, the getContext method guarantees that we never get null back. However, our
    // integration tests don't set it up correctly, so cache may be null in testing.
    if (cache == null) {
      cache = SpawnCache.NO_CACHE;
    }

    // Avoid using the remote cache of a dynamic execution setup for the local runner.
    if (context.speculating() && !cache.usefulInDynamicExecution()) {
      cache = SpawnCache.NO_CACHE;
    }
    SpawnResult spawnResult;
    ExecException ex = null;
    try (CacheHandle cacheHandle = cache.lookup(spawn, context)) {
      if (cacheHandle.hasResult()) {
        spawnResult = Preconditions.checkNotNull(cacheHandle.getResult());
      } else {
        Instant startTime =
            Instant.ofEpochMilli(actionExecutionContext.getClock().currentTimeMillis());
        // Actual execution.
        spawnResult = spawnRunner.exec(spawn, context);

        String spawnIdentifier = null;
        if (spawnResult.getDigest() != null) {
          spawnIdentifier = spawnResult.getDigest().getHash();
        }
        actionExecutionContext
            .getEventHandler()
            .post(
                new SpawnExecutedEvent(
                    spawn,
                    actionExecutionContext.getInputMetadataProvider(),
                    actionExecutionContext.getFileOutErr(),
                    spawnResult,
                    startTime,
                    spawnIdentifier));
        if (cacheHandle.willStore()) {
          cacheHandle.store(spawnResult);
        }
      }
    } catch (InterruptedIOException e) {
      throw new InterruptedException(e.getMessage());
    } catch (IOException e) {
      throw new EnvironmentalExecException(
          e,
          FailureDetail.newBuilder()
              .setMessage("Exec failed due to IOException")
              .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.EXEC_IO_EXCEPTION))
              .build());
    } catch (SpawnExecException e) {
      ex = e;
      spawnResult = e.getSpawnResult();
      // Log the Spawn and re-throw.
    } catch (ForbiddenActionInputException e) {
      throw new UserExecException(
          e,
          FailureDetail.newBuilder()
              .setMessage("Exec failed due to forbidden input")
              .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.FORBIDDEN_INPUT))
              .build());
    }

    SpawnLogContext spawnLogContext = actionExecutionContext.getContext(SpawnLogContext.class);
    if (spawnLogContext != null) {
      try {
        spawnLogContext.logSpawn(
            spawn,
            actionExecutionContext.getInputMetadataProvider(),
            context.getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ false),
            actionExecutionContext.getActionFileSystem() != null
                ? actionExecutionContext.getActionFileSystem()
                : actionExecutionContext.getExecRoot().getFileSystem(),
            context.getTimeout(),
            spawnResult);
      } catch (IOException e) {
        throw new EnvironmentalExecException(
            e,
            FailureDetail.newBuilder()
                .setMessage("IOException while logging spawn")
                .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.SPAWN_LOG_IO_EXCEPTION))
                .build());
      } catch (ForbiddenActionInputException e) {
        // Should have already been thrown during execution.
        throw new IllegalStateException("ForbiddenActionInputException while logging spawn", e);
      }
    }
    if (ex != null) {
      throw ex;
    }

    if (spawnResult.status() != Status.SUCCESS) {
      String cwd = actionExecutionContext.getExecRoot().getPathString();
      String resultMessage = spawnResult.getFailureMessage();
      String message =
          !Strings.isNullOrEmpty(resultMessage)
              ? resultMessage
              : CommandFailureUtils.describeCommandFailure(
                  executionOptions.verboseFailures, cwd, spawn);
      throw new SpawnExecException(message, spawnResult, /* forciblyRunRemotely= */ false);
    }
    return ImmutableList.of(spawnResult);
  }

  private final class SpawnExecutionContextImpl implements SpawnExecutionContext {
    private final Spawn spawn;
    private final ActionExecutionContext actionExecutionContext;
    @Nullable private final SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns;
    private final Duration timeout;

    private final int id = execCount.incrementAndGet();
    // Memoize the input mapping so that prefetchInputs can reuse it instead of recomputing it.
    // TODO(ulfjack): Guard against client modification of this map.
    private SortedMap<PathFragment, ActionInput> lazyInputMapping;
    private PathFragment inputMappingBaseDirectory;

    @Nullable private Digest digest;

    SpawnExecutionContextImpl(
        Spawn spawn,
        ActionExecutionContext actionExecutionContext,
        @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns,
        Duration timeout) {
      this.spawn = spawn;
      this.actionExecutionContext = actionExecutionContext;
      this.stopConcurrentSpawns = stopConcurrentSpawns;
      this.timeout = timeout;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public void setDigest(Digest digest) {
      if (this.digest != null) {
        checkArgument(
            this.digest.equals(digest),
            "setDigest was called more than once with different digests: %s vs %s",
            this.digest,
            digest);
      }
      this.digest = checkNotNull(digest);
    }

    @Override
    @Nullable
    public Digest getDigest() {
      return digest;
    }

    @Override
    public ListenableFuture<Void> prefetchInputs() throws ForbiddenActionInputException {
      if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
        return Futures.catchingAsync(
            actionExecutionContext
                .getActionInputPrefetcher()
                .prefetchFiles(
                    spawn.getResourceOwner(),
                    getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true)
                        .values(),
                    getInputMetadataProvider(),
                    Priority.MEDIUM,
                    Reason.INPUTS),
            BulkTransferException.class,
            (BulkTransferException e) -> {
              if (executionOptions.useNewExitCodeForLostInputs
                  || executionOptions.remoteRetryOnTransientCacheError > 0) {
                var message =
                    BulkTransferException.allCausedByCacheNotFoundException(e)
                        ? "Failed to fetch blobs because they do not exist remotely."
                        : "Failed to fetch blobs because of a remote cache error.";
                throw new EnvironmentalExecException(
                    e,
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setSpawn(
                            FailureDetails.Spawn.newBuilder().setCode(Code.REMOTE_CACHE_EVICTED))
                        .build());
              } else if (BulkTransferException.allCausedByCacheNotFoundException(e)) {
                throw new EnvironmentalExecException(
                    e,
                    FailureDetail.newBuilder()
                        .setMessage("Failed to fetch blobs because they do not exist remotely.")
                        .setSpawn(
                            FailureDetails.Spawn.newBuilder().setCode(Code.REMOTE_CACHE_FAILED))
                        .build());
              } else {
                throw e;
              }
            },
            directExecutor());
      }

      return immediateVoidFuture();
    }

    @Override
    public InputMetadataProvider getInputMetadataProvider() {
      return actionExecutionContext.getInputMetadataProvider();
    }

    @Override
    public <T extends ActionContext> T getContext(Class<T> identifyingType) {
      return actionExecutionContext.getContext(identifyingType);
    }

    @Override
    public ArtifactExpander getArtifactExpander() {
      return actionExecutionContext.getArtifactExpander();
    }

    @Override
    public ArtifactPathResolver getPathResolver() {
      return actionExecutionContext.getPathResolver();
    }

    @Override
    public SpawnInputExpander getSpawnInputExpander() {
      return spawnInputExpander;
    }

    @Override
    public void lockOutputFiles(int exitCode, String errorMessage, FileOutErr outErr)
        throws InterruptedException {
      if (stopConcurrentSpawns != null) {
        stopConcurrentSpawns.stop(exitCode, errorMessage, outErr);
      }
    }

    @Override
    public boolean speculating() {
      return stopConcurrentSpawns != null;
    }

    @Override
    public Duration getTimeout() {
      return timeout;
    }

    @Override
    public FileOutErr getFileOutErr() {
      return actionExecutionContext.getFileOutErr();
    }

    @Override
    public SortedMap<PathFragment, ActionInput> getInputMapping(
        PathFragment baseDirectory, boolean willAccessRepeatedly)
        throws ForbiddenActionInputException {
      // Return previously computed copy if present.
      if (lazyInputMapping != null && inputMappingBaseDirectory.equals(baseDirectory)) {
        return lazyInputMapping;
      }

      SortedMap<PathFragment, ActionInput> inputMapping;
      try (SilentCloseable c =
          Profiler.instance().profile("AbstractSpawnStrategy.getInputMapping")) {
        inputMapping =
            spawnInputExpander.getInputMapping(
                spawn,
                actionExecutionContext.getArtifactExpander(),
                actionExecutionContext.getInputMetadataProvider(),
                baseDirectory);
      }

      // Don't cache the input mapping if it is unlikely that it is used again.
      // This reduces memory usage in the case where remote caching/execution is
      // used, and the expected cache hit rate is high.
      if (willAccessRepeatedly) {
        inputMappingBaseDirectory = baseDirectory;
        lazyInputMapping = inputMapping;
      }
      return inputMapping;
    }

    @Override
    public void report(ProgressStatus progress) {
      ActionExecutionMetadata action = spawn.getResourceOwner();
      if (action.getOwner() == null) {
        return;
      }

      // TODO(djasper): This should not happen as per the contract of ActionExecutionMetadata, but
      // there are implementations that violate the contract. Remove when those are gone.
      if (action.getPrimaryOutput() == null) {
        return;
      }

      ExtendedEventHandler eventHandler = actionExecutionContext.getEventHandler();
      progress.postTo(eventHandler, action);
    }

    @Override
    public boolean isRewindingEnabled() {
      return actionExecutionContext.isRewindingEnabled();
    }

    @Override
    public void checkForLostInputs() throws LostInputsExecException {
      try {
        actionExecutionContext.checkForLostInputs();
      } catch (LostInputsActionExecutionException e) {
        throw e.toExecException();
      }
    }

    @Nullable
    @Override
    public FileSystem getActionFileSystem() {
      return actionExecutionContext.getActionFileSystem();
    }
  }
}
