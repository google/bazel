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

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.profiler.ProfilerTask.FETCH;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_DOWNLOAD;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_EXECUTION;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_PROCESS_TIME;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_QUEUE;
import static com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_SETUP;
import static com.google.devtools.build.lib.profiler.ProfilerTask.UPLOAD_TIME;
import static com.google.devtools.build.lib.remote.util.Utils.createSpawnResult;

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import build.bazel.remote.execution.v2.ExecutionStage.Value;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.BlazeClock.MillisSinceEpochToNanosConverter;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.AbstractSpawnStrategy;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RemoteLocalFallbackRegistry;
import com.google.devtools.build.lib.exec.SpawnCheckingCacheEvent;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnSchedulingEvent;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.RemoteExecutionService.ServerLogs;
import com.google.devtools.build.lib.remote.circuitbreaker.CircuitBreakerFactory;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.RetryInfo;
import io.grpc.Status.Code;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A client for the remote execution service. */
@ThreadSafe
public class RemoteSpawnRunner implements SpawnRunner {

  private static final SpawnCheckingCacheEvent SPAWN_CHECKING_CACHE_EVENT =
      SpawnCheckingCacheEvent.create("remote");

  private static final SpawnSchedulingEvent SPAWN_SCHEDULING_EVENT =
      SpawnSchedulingEvent.create("remote");

  private static final SpawnExecutingEvent SPAWN_EXECUTING_EVENT =
      SpawnExecutingEvent.create("remote");

  private final Path execRoot;
  private final RemoteOptions remoteOptions;
  private final ExecutionOptions executionOptions;
  private final boolean verboseFailures;
  @Nullable private final Reporter cmdlineReporter;
  private final RemoteRetrier retrier;
  private final Path logDir;
  private final RemoteExecutionService remoteExecutionService;
  private final DigestUtil digestUtil;

  // Used to ensure that a warning is reported only once.
  private final AtomicBoolean warningReported = new AtomicBoolean();

  RemoteSpawnRunner(
      Path execRoot,
      RemoteOptions remoteOptions,
      ExecutionOptions executionOptions,
      boolean verboseFailures,
      @Nullable Reporter cmdlineReporter,
      ListeningScheduledExecutorService retryService,
      Path logDir,
      RemoteExecutionService remoteExecutionService,
      DigestUtil digestUtil) {
    this.execRoot = execRoot;
    this.remoteOptions = remoteOptions;
    this.executionOptions = executionOptions;
    this.verboseFailures = verboseFailures;
    this.cmdlineReporter = cmdlineReporter;
    this.retrier = createExecuteRetrier(remoteOptions, retryService);
    this.logDir = logDir;
    this.remoteExecutionService = remoteExecutionService;
    this.digestUtil = digestUtil;
  }

  @VisibleForTesting
  RemoteExecutionService getRemoteExecutionService() {
    return remoteExecutionService;
  }

  @Override
  public String getName() {
    return "remote";
  }

  class ExecutingStatusReporter implements OperationObserver {
    private boolean reportedExecuting = false;
    private final SpawnExecutionContext context;

    ExecutingStatusReporter(SpawnExecutionContext context) {
      this.context = context;
    }

    @Override
    public void onNext(Operation o) throws IOException {
      if (!reportedExecuting) {
        if (o.getMetadata().is(ExecuteOperationMetadata.class)) {
          ExecuteOperationMetadata metadata =
              o.getMetadata().unpack(ExecuteOperationMetadata.class);
          if (metadata.getStage() == Value.EXECUTING) {
            reportExecuting();
          }
        } else {
          // If the server didn't return metadata, we can't know the accurate execution status, so
          // assuming that the action is accepted by the server and will be executed ASAP.
          reportExecuting();
        }
      }
    }

    public void reportExecuting() {
      context.report(SPAWN_EXECUTING_EVENT);
      reportedExecuting = true;
    }

    public void reportExecutingIfNot() {
      if (!reportedExecuting) {
        reportExecuting();
      }
    }
  }

  /**
   * Guess if an action failed due to a transient remote error or not.
   *
   * <p>As described in <a href="https://github.com/bazelbuild/bazel/issues/18319">#18319</a>, the
   * Remote Build protocol does not yet provide a way to distinguish between regular action crashes
   * and unexpected worker crashes. This provides a heuristic to retry on likely worker crashes.
   * The specific error code we may get depends on the remote executor implementation so we need to
   * be quite liberal here: Go funnels all non-regular exit codes into {@code -1} and the shell
   * returns {@code 128+SIGNO}.
   */
  private boolean isTransientError(RemoteAction action, RemoteActionResult result) {
    if (!remoteOptions.remoteExitSignalsAreTransientErrors) {
      return false;
    }

    boolean isTestAction = action.getSpawn().getMnemonic().equals("TestRunner");
    if (isTestAction) {
      return false;
    }

    return result.getExitCode() < 0 || result.getExitCode() > 127;
  }

  @Override
  public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException, ForbiddenActionInputException {
    Preconditions.checkArgument(
        remoteExecutionService.mayBeExecutedRemotely(spawn),
        "Spawn can't be executed remotely. This is a bug.");

    Stopwatch totalTime = Stopwatch.createStarted();
    boolean acceptCachedResult = remoteExecutionService.getReadCachePolicy(spawn).allowAnyCache();
    boolean uploadLocalResults = remoteExecutionService.getWriteCachePolicy(spawn).allowAnyCache();

    RemoteAction action = remoteExecutionService.buildRemoteAction(spawn, context);

    context.setDigest(digestUtil.asSpawnLogProto(action.getActionKey()));

    SpawnMetrics.Builder spawnMetrics =
        SpawnMetrics.Builder.forRemoteExec()
            .setInputBytes(action.getInputBytes())
            .setInputFiles(action.getInputFiles());

    maybeWriteParamFilesLocally(spawn);

    spawnMetrics.setParseTimeInMs((int) totalTime.elapsed().toMillis());

    Profiler prof = Profiler.instance();
    try {
      context.report(SPAWN_CHECKING_CACHE_EVENT);

      // Try to lookup the action in the action cache.
      RemoteActionResult cachedResult;
      try (SilentCloseable c = prof.profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit")) {
        cachedResult = acceptCachedResult ? remoteExecutionService.lookupCache(action) : null;
      }

      if (cachedResult != null) {
        if (cachedResult.getExitCode() != 0) {
          // Failed actions are treated as a cache miss mostly in order to avoid caching flaky
          // actions (tests).
          // Set acceptCachedResult to false in order to force the action re-execution
          acceptCachedResult = false;
        } else {
          try {
            return downloadAndFinalizeSpawnResult(
                action,
                cachedResult,
                /* cacheHit= */ true,
                cachedResult.cacheName(),
                spawn,
                totalTime,
                () -> action.getNetworkTime().getDuration(),
                spawnMetrics);
          } catch (BulkTransferException e) {
            if (!e.allCausedByCacheNotFoundException()) {
              throw e;
            }
            // No cache hit, so we fall through to local or remote execution.
            // We set acceptCachedResult to false in order to force the action re-execution.
            acceptCachedResult = false;
          }
        }
      }
    } catch (IOException e) {
      return execLocallyAndUploadOrFail(action, spawn, context, uploadLocalResults, e);
    }

    if (remoteOptions.remoteRequireCached) {
      return new SpawnResult.Builder()
          .setStatus(SpawnResult.Status.EXECUTION_DENIED)
          .setExitCode(1)
          .setFailureMessage(
              "Action must be cached due to --experimental_remote_require_cached but it is not")
          .setFailureDetail(
              FailureDetail.newBuilder()
                  .setSpawn(
                      FailureDetails.Spawn.newBuilder()
                          .setCode(FailureDetails.Spawn.Code.EXECUTION_DENIED))
                  .build())
          .setRunnerName("remote")
          .build();
    }

    AtomicBoolean useCachedResult = new AtomicBoolean(acceptCachedResult);
    AtomicBoolean forceUploadInput = new AtomicBoolean(false);
    try {
      return retrier.execute(
          () -> {
            // Upload the command and all the inputs into the remote cache.
            try (SilentCloseable c = prof.profile(UPLOAD_TIME, "upload missing inputs")) {
              Duration networkTimeStart = action.getNetworkTime().getDuration();
              Stopwatch uploadTime = Stopwatch.createStarted();
              // Upon retry, we force upload inputs
              remoteExecutionService.uploadInputsIfNotPresent(
                  action, forceUploadInput.getAndSet(true));

              // subtract network time consumed here to ensure wall clock during upload is not
              // double
              // counted, and metrics time computation does not exceed total time
              spawnMetrics.setUploadTimeInMs(
                  (int)
                      uploadTime
                          .elapsed()
                          .minus(action.getNetworkTime().getDuration().minus(networkTimeStart))
                          .toMillis());
            }

            context.report(SPAWN_SCHEDULING_EVENT);

            ExecutingStatusReporter reporter = new ExecutingStatusReporter(context);
            RemoteActionResult result;
            try (SilentCloseable c = prof.profile(REMOTE_EXECUTION, "execute remotely")) {
              result =
                  remoteExecutionService.executeRemotely(action, useCachedResult.get(), reporter);
            }
            // In case of replies from server contains metadata, but none of them has EXECUTING
            // status.
            // It's already late at this stage, but we should at least report once.
            reporter.reportExecutingIfNot();

            maybePrintExecutionMessages(context, result.getMessage(), result.success());

            profileAccounting(result.getExecutionMetadata());
            spawnMetricsAccounting(spawnMetrics, result.getExecutionMetadata());

            try (SilentCloseable c = prof.profile(REMOTE_DOWNLOAD, "download server logs")) {
              maybeDownloadServerLogs(action, result.getResponse());
            }

            if (isTransientError(action, result)) {
              // This contraption is required to convince the ExecuteRetrier that the failure can
              // be retried.
              com.google.rpc.Status synthesizedStatus = com.google.rpc.Status.newBuilder()
                  .setCode(com.google.rpc.Code.FAILED_PRECONDITION.getNumber())
                  .setMessage(
                      "Remote action seems to have terminated due to a signal (exit code "
                          + result.getExitCode()
                          + "); assuming worker crash to retry")
                  .addDetails(Any.pack(RetryInfo.newBuilder().build()))
                  .build();
              Exception cause = new ExecutionStatusException(synthesizedStatus, null);
              throw new IOException(cause);
            }

            try {
              return downloadAndFinalizeSpawnResult(
                  action,
                  result,
                  result.cacheHit(),
                  getName(),
                  spawn,
                  totalTime,
                  () -> action.getNetworkTime().getDuration(),
                  spawnMetrics);
            } catch (BulkTransferException e) {
              if (e.allCausedByCacheNotFoundException()) {
                // No cache hit, so if we retry this execution, we must no longer accept
                // cached results, it must be reexecuted
                useCachedResult.set(false);
              }
              throw e;
            }
          });
    } catch (IOException e) {
      return execLocallyAndUploadOrFail(action, spawn, context, uploadLocalResults, e);
    }
  }

  private static void profileAccounting(ExecutedActionMetadata executedActionMetadata) {
    MillisSinceEpochToNanosConverter converter =
        BlazeClock.createMillisSinceEpochToNanosConverter();

    logProfileTask(
        converter,
        executedActionMetadata.getQueuedTimestamp(),
        executedActionMetadata.getWorkerStartTimestamp(),
        REMOTE_QUEUE,
        "queue");
    logProfileTask(
        converter,
        executedActionMetadata.getWorkerStartTimestamp(),
        executedActionMetadata.getInputFetchStartTimestamp(),
        REMOTE_SETUP,
        "pre-fetch");
    logProfileTask(
        converter,
        executedActionMetadata.getInputFetchStartTimestamp(),
        executedActionMetadata.getInputFetchCompletedTimestamp(),
        FETCH,
        "fetch");
    logProfileTask(
        converter,
        executedActionMetadata.getInputFetchCompletedTimestamp(),
        executedActionMetadata.getExecutionStartTimestamp(),
        REMOTE_SETUP,
        "pre-execute");
    logProfileTask(
        converter,
        executedActionMetadata.getExecutionStartTimestamp(),
        executedActionMetadata.getExecutionCompletedTimestamp(),
        REMOTE_PROCESS_TIME,
        "execute");
    logProfileTask(
        converter,
        executedActionMetadata.getExecutionCompletedTimestamp(),
        executedActionMetadata.getOutputUploadStartTimestamp(),
        REMOTE_SETUP,
        "pre-upload");
    logProfileTask(
        converter,
        executedActionMetadata.getOutputUploadStartTimestamp(),
        executedActionMetadata.getOutputUploadCompletedTimestamp(),
        UPLOAD_TIME,
        "upload");
  }

  private static void logProfileTask(
      MillisSinceEpochToNanosConverter converter,
      Timestamp start,
      Timestamp end,
      ProfilerTask type,
      String description) {
    Profiler.instance()
        .logSimpleTask(
            converter.toNanos(Timestamps.toMillis(start)),
            converter.toNanos(Timestamps.toMillis(end)),
            type,
            description);
  }

  /** conversion utility for protobuf Timestamp difference to java.time.Duration */
  private static Duration between(Timestamp from, Timestamp to) {
    return Duration.ofNanos(Durations.toNanos(Timestamps.between(from, to)));
  }

  @VisibleForTesting
  static void spawnMetricsAccounting(
      SpawnMetrics.Builder spawnMetrics, ExecutedActionMetadata executionMetadata) {
    // Expect that a non-empty worker indicates that all fields are populated.
    // If the bounded sides of these checkpoints are default timestamps, i.e. unset,
    // the phase durations can be extremely large. Unset pairs, or a fully unset
    // collection of timestamps, will result in zeroed durations, and no metrics
    // contributions for a phase or phases.
    if (!executionMetadata.getWorker().isEmpty()) {
      // Accumulate queueTime from any previous attempts
      int remoteQueueTimeInMs =
          spawnMetrics.build().queueTimeInMs()
              + (int)
                  between(
                          executionMetadata.getQueuedTimestamp(),
                          executionMetadata.getWorkerStartTimestamp())
                      .toMillis();
      spawnMetrics.setQueueTimeInMs(remoteQueueTimeInMs);
      // setup time does not include failed attempts
      Duration setupTime =
          between(
              executionMetadata.getWorkerStartTimestamp(),
              executionMetadata.getExecutionStartTimestamp());
      spawnMetrics.setSetupTimeInMs((int) setupTime.toMillis());
      // execution time is unspecified for failures
      Duration executionWallTime =
          between(
              executionMetadata.getExecutionStartTimestamp(),
              executionMetadata.getExecutionCompletedTimestamp());
      spawnMetrics.setExecutionWallTimeInMs((int) executionWallTime.toMillis());
      // remoteProcessOutputs time is unspecified for failures
      Duration remoteProcessOutputsTime =
          between(
              executionMetadata.getOutputUploadStartTimestamp(),
              executionMetadata.getOutputUploadCompletedTimestamp());
      spawnMetrics.setProcessOutputsTimeInMs((int) remoteProcessOutputsTime.toMillis());
    }
  }

  private SpawnResult downloadAndFinalizeSpawnResult(
      RemoteAction action,
      RemoteActionResult result,
      boolean cacheHit,
      String cacheName,
      Spawn spawn,
      Stopwatch totalTime,
      Supplier<Duration> networkTime,
      SpawnMetrics.Builder spawnMetrics)
      throws ExecException, IOException, InterruptedException {
    Duration networkTimeStart = networkTime.get();
    Stopwatch fetchTime = Stopwatch.createStarted();

    InMemoryOutput inMemoryOutput;
    try (SilentCloseable c = Profiler.instance().profile(REMOTE_DOWNLOAD, "download outputs")) {
      inMemoryOutput = remoteExecutionService.downloadOutputs(action, result);
    }

    fetchTime.stop();
    totalTime.stop();
    Duration networkTimeEnd = networkTime.get();
    // subtract network time consumed here to ensure wall clock during fetch is not double
    // counted, and metrics time computation does not exceed total time
    return createSpawnResult(
        digestUtil,
        action.getActionKey(),
        result.getExitCode(),
        cacheHit,
        cacheName,
        inMemoryOutput,
        result.getExecutionMetadata().getExecutionStartTimestamp(),
        result.getExecutionMetadata().getExecutionCompletedTimestamp(),
        spawnMetrics
            .setFetchTimeInMs(
                (int) fetchTime.elapsed().minus(networkTimeEnd.minus(networkTimeStart)).toMillis())
            .setTotalTimeInMs((int) totalTime.elapsed().toMillis())
            .setNetworkTimeInMs((int) networkTimeEnd.toMillis())
            .build(),
        spawn.getMnemonic());
  }

  @Override
  public boolean canExec(Spawn spawn) {
    return remoteExecutionService.mayBeExecutedRemotely(spawn);
  }

  @Override
  public boolean handlesCaching() {
    return true;
  }

  private void maybePrintExecutionMessages(
      SpawnExecutionContext context, String message, boolean success) {
    FileOutErr outErr = context.getFileOutErr();
    boolean printMessage =
        remoteOptions.remotePrintExecutionMessages.shouldPrintMessages(success)
            && !message.isEmpty();
    if (printMessage) {
      outErr.printErr(message + "\n");
    }
  }

  private void maybeWriteParamFilesLocally(Spawn spawn) throws IOException {
    if (!executionOptions.shouldMaterializeParamFiles()) {
      return;
    }
    for (ActionInput actionInput : spawn.getInputFiles().toList()) {
      if (actionInput instanceof ParamFileActionInput) {
        ParamFileActionInput paramFileActionInput = (ParamFileActionInput) actionInput;
        paramFileActionInput.atomicallyWriteRelativeTo(execRoot, ".remote");
      }
    }
  }

  private void maybeDownloadServerLogs(RemoteAction action, ExecuteResponse resp)
      throws InterruptedException {
    try {
      ServerLogs serverLogs = remoteExecutionService.maybeDownloadServerLogs(action, resp, logDir);
      if (serverLogs.logCount > 0 && verboseFailures) {
        report(
            Event.info(
                "Server logs of failing action:\n   "
                    + (serverLogs.logCount > 1 ? serverLogs.directory : serverLogs.lastLogPath)));
      }
    } catch (IOException e) {
      reportOnce(Event.warn("Failed downloading server logs from the remote cache."));
    }
  }

  private SpawnResult execLocally(Spawn spawn, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException, ForbiddenActionInputException {
    RemoteLocalFallbackRegistry localFallbackRegistry =
        context.getContext(RemoteLocalFallbackRegistry.class);
    checkNotNull(localFallbackRegistry, "Expected a RemoteLocalFallbackRegistry to be registered");
    AbstractSpawnStrategy remoteLocalFallbackStrategy =
        localFallbackRegistry.getRemoteLocalFallbackStrategy();
    checkNotNull(
        remoteLocalFallbackStrategy,
        "A remote local fallback strategy must be set if using remote fallback.");
    return remoteLocalFallbackStrategy.getSpawnRunner().exec(spawn, context);
  }

  private SpawnResult execLocallyAndUploadOrFail(
      RemoteAction action,
      Spawn spawn,
      SpawnExecutionContext context,
      boolean uploadLocalResults,
      IOException cause)
      throws ExecException, InterruptedException, IOException, ForbiddenActionInputException {
    // Regardless of cause, if we are interrupted, we should stop without displaying a user-visible
    // failure/stack trace.
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    if (remoteOptions.remoteLocalFallback && !RemoteRetrierUtils.causedByExecTimeout(cause)) {
      return execLocallyAndUpload(action, spawn, context, uploadLocalResults);
    }
    return handleError(action, cause, context);
  }

  private SpawnResult handleError(
      RemoteAction action, IOException exception, SpawnExecutionContext context)
      throws ExecException, InterruptedException, IOException {
    boolean remoteCacheFailed = BulkTransferException.allCausedByCacheNotFoundException(exception);
    if (exception.getCause() instanceof ExecutionStatusException) {
      ExecutionStatusException e = (ExecutionStatusException) exception.getCause();
      RemoteActionResult result = null;
      if (e.getResponse() != null) {
        ExecuteResponse resp = e.getResponse();
        maybeDownloadServerLogs(action, resp);
        if (resp.hasResult()) {
          result = RemoteActionResult.createFromResponse(resp);
          try {
            remoteExecutionService.downloadOutputs(action, result);
          } catch (BulkTransferException bulkTransferEx) {
            exception.addSuppressed(bulkTransferEx);
          }
        }
      }
      if (e.isExecutionTimeout()) {
        maybePrintExecutionMessages(context, e.getResponse().getMessage(), /* success= */ false);
        SpawnResult.Builder resultBuilder =
            new SpawnResult.Builder()
                .setRunnerName(getName())
                .setStatus(Status.TIMEOUT)
                .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
                .setFailureDetail(
                    FailureDetail.newBuilder()
                        .setMessage("remote spawn timed out")
                        .setSpawn(
                            FailureDetails.Spawn.newBuilder()
                                .setCode(FailureDetails.Spawn.Code.TIMEOUT))
                        .build());
        if (result != null) {
          resultBuilder
              .setWallTimeInMs(
                  (int)
                      Duration.between(
                              Utils.timestampToInstant(
                                  result.getExecutionMetadata().getExecutionStartTimestamp()),
                              Utils.timestampToInstant(
                                  result.getExecutionMetadata().getExecutionCompletedTimestamp()))
                          .toMillis())
              .setStartTime(
                  Utils.timestampToInstant(
                      result.getExecutionMetadata().getExecutionStartTimestamp()));
        }
        return resultBuilder.build();
      }
    }
    final Status status;
    FailureDetails.Spawn.Code detailedCode;
    boolean catastrophe;
    if (RemoteRetrierUtils.causedByStatus(exception, Code.UNAVAILABLE)) {
      status = Status.EXECUTION_FAILED_CATASTROPHICALLY;
      detailedCode = FailureDetails.Spawn.Code.EXECUTION_FAILED;
      catastrophe = true;
    } else if (remoteCacheFailed) {
      status = Status.REMOTE_CACHE_FAILED;
      if (executionOptions.useNewExitCodeForLostInputs
          || executionOptions.remoteRetryOnTransientCacheError > 0) {
        detailedCode = FailureDetails.Spawn.Code.REMOTE_CACHE_EVICTED;
      } else {
        detailedCode = FailureDetails.Spawn.Code.REMOTE_CACHE_FAILED;
      }
      catastrophe = false;
    } else {
      status = Status.EXECUTION_FAILED;
      detailedCode = FailureDetails.Spawn.Code.EXECUTION_FAILED;
      catastrophe = false;
    }

    String errorMessage = Utils.grpcAwareErrorMessage(exception, verboseFailures);

    if (exception.getCause() instanceof ExecutionStatusException) {
      ExecutionStatusException e = (ExecutionStatusException) exception.getCause();
      if (e.getResponse() != null) {
        if (!e.getResponse().getMessage().isEmpty()) {
          errorMessage += "\n" + e.getResponse().getMessage();
        }
      }
    }

    return new SpawnResult.Builder()
        .setRunnerName(getName())
        .setStatus(status)
        .setExitCode(ExitCode.REMOTE_ERROR.getNumericExitCode())
        .setFailureMessage(errorMessage)
        .setFailureDetail(
            FailureDetail.newBuilder()
                .setMessage("remote spawn failed: " + errorMessage)
                .setSpawn(
                    FailureDetails.Spawn.newBuilder()
                        .setCode(detailedCode)
                        .setCatastrophic(catastrophe))
                .build())
        .build();
  }

  private Map<Path, Long> getInputCtimes(SortedMap<PathFragment, ActionInput> inputMap) {
    HashMap<Path, Long> ctimes = new HashMap<>();
    for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
      ActionInput input = e.getValue();
      if (input instanceof VirtualActionInput) {
        continue;
      }
      Path path = execRoot.getRelative(input.getExecPathString());
      try {
        ctimes.put(path, path.stat().getLastChangeTime());
      } catch (IOException ex) {
        // Put a token value indicating an exception; this is used so that if the exception
        // is raised both before and after the execution, it is ignored, but if it is raised only
        // one of the times, it triggers a remote cache upload skip.
        ctimes.put(path, -1L);
      }
    }
    return ctimes;
  }

  @VisibleForTesting
  SpawnResult execLocallyAndUpload(
      RemoteAction action, Spawn spawn, SpawnExecutionContext context, boolean uploadLocalResults)
      throws ExecException, IOException, ForbiddenActionInputException, InterruptedException {
    Map<Path, Long> ctimesBefore = getInputCtimes(action.getInputMap(true));
    SpawnResult result = execLocally(spawn, context);
    Map<Path, Long> ctimesAfter = getInputCtimes(action.getInputMap(true));
    uploadLocalResults =
        uploadLocalResults && Status.SUCCESS.equals(result.status()) && result.exitCode() == 0;
    if (!uploadLocalResults) {
      return result;
    }

    for (Map.Entry<Path, Long> e : ctimesBefore.entrySet()) {
      // Skip uploading to remote cache, because an input was modified during execution.
      if (!ctimesAfter.get(e.getKey()).equals(e.getValue())) {
        return result;
      }
    }

    remoteExecutionService.uploadOutputs(action, result, () -> {});
    return result;
  }

  private void reportOnce(Event evt) {
    if (warningReported.compareAndSet(false, true)) {
      report(evt);
    }
  }

  private void report(Event evt) {
    if (cmdlineReporter != null) {
      cmdlineReporter.handle(evt);
    }
  }

  private static RemoteRetrier createExecuteRetrier(
      RemoteOptions options, ListeningScheduledExecutorService retryService) {
    return new ExecuteRetrier(
        options.remoteMaxRetryAttempts,
        retryService,
        CircuitBreakerFactory.createCircuitBreaker(options));
  }
}
