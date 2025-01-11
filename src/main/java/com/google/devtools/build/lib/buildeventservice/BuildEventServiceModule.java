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
package com.google.devtools.build.lib.buildeventservice;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceOptions.BesUploadMode;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient;
import com.google.devtools.build.lib.buildeventstream.AnnounceBuildEventTransportsEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted.AbortReason;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransportClosedEvent;
import com.google.devtools.build.lib.buildeventstream.LocalFilesArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.transports.BinaryFormatFileTransport;
import com.google.devtools.build.lib.buildeventstream.transports.BuildEventStreamOptions;
import com.google.devtools.build.lib.buildeventstream.transports.JsonFormatFileTransport;
import com.google.devtools.build.lib.buildeventstream.transports.TextFormatFileTransport;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.network.ConnectivityStatus;
import com.google.devtools.build.lib.network.ConnectivityStatus.Status;
import com.google.devtools.build.lib.network.ConnectivityStatusProvider;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactory;
import com.google.devtools.build.lib.runtime.BuildEventStreamer;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.CommonCommandOptions;
import com.google.devtools.build.lib.runtime.CountingArtifactGroupNamer;
import com.google.devtools.build.lib.runtime.InstrumentationOutput;
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.runtime.SynchronizedOutputStream;
import com.google.devtools.build.lib.runtime.TargetSummaryPublisher;
import com.google.devtools.build.lib.runtime.UiOptions;
import com.google.devtools.build.lib.server.FailureDetails.BuildProgress;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.AnsiTerminal.Color;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.util.JsonFormat.TypeRegistry;
import com.google.protobuf.util.Timestamps;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Module responsible for the Build Event Transport (BEP) and Build Event Service (BES)
 * functionality.
 */
public abstract class BuildEventServiceModule<OptionsT extends BuildEventServiceOptions>
    extends BlazeModule {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * TargetComplete BEP events scale with the value of --runs_per_tests, thus setting a very large
   * value for can result in BEP events that are too big for BES to handle.
   */
  @VisibleForTesting static final int RUNS_PER_TEST_LIMIT = 100000;

  private BuildEventProtocolOptions bepOptions;
  private AuthAndTLSOptions authTlsOptions;
  private BuildEventStreamOptions besStreamOptions;
  private boolean uiUsesColor;
  private boolean isRunsPerTestOverTheLimit;
  private BuildEventArtifactUploaderFactory uploaderFactoryToCleanup;

  private BuildEventOutputStreamFactory buildEventOutputStreamFactory;

  /**
   * Holds the close futures for the upload of each transport with timeouts attached to them using
   * {@link #constructCloseFuturesMapWithTimeouts(ImmutableMap)} obtained from {@link
   * BuildEventTransport#getTimeout()}.
   */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>> closeFuturesWithTimeoutsMap =
      ImmutableMap.of();

  /**
   * Holds the half-close futures for the upload of each transport with timeouts attached to them
   * using {@link #constructCloseFuturesMapWithTimeouts(ImmutableMap)} obtained from {@link
   * BuildEventTransport#getTimeout()}.
   *
   * <p>The completion of the half-close indicates that the client has sent all of the data to the
   * server and is just waiting for acknowledgement. The client must still keep the data buffered
   * locally in case acknowledgement fails.
   */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>>
      halfCloseFuturesWithTimeoutsMap = ImmutableMap.of();

  // TODO(lpino): Use Optional instead of @Nullable for the members below.
  @Nullable private OutErr outErr;
  @Nullable private ImmutableSet<BuildEventTransport> bepTransports;
  @Nullable private String buildRequestId;
  @Nullable private String invocationId;
  @Nullable private Reporter reporter;
  @Nullable private BuildEventStreamer streamer;
  @Nullable private ConnectivityStatusProvider connectivityProvider;
  private static final String CONNECTIVITY_CACHE_KEY = "BES";

  protected OptionsT besOptions;

  /** Defines format of the build event file. */
  enum BuildEventFileType {
    TEXT,
    JSON,
    BINARY
  }

  protected void reportCommandLineError(EventHandler commandLineReporter, Exception exception) {
    // Don't hide unchecked exceptions as part of the error reporting.
    Throwables.throwIfUnchecked(exception);
    commandLineReporter.handle(Event.error(exception.getMessage()));
  }

  /** Maximum duration Bazel waits for the previous invocation to finish before cancelling it. */
  protected Duration getMaxWaitForPreviousInvocation() {
    return Duration.ofSeconds(5);
  }

  /** Report errors in the command line and possibly fail the build. */
  private void reportError(
      EventHandler commandLineReporter,
      ModuleEnvironment moduleEnvironment,
      String msg,
      Exception exception,
      BuildProgress.Code besCode) {
    // Don't hide unchecked exceptions as part of the error reporting.
    Throwables.throwIfUnchecked(exception);

    logger.atSevere().withCause(exception).log("%s", msg);
    reportCommandLineError(commandLineReporter, exception);
    moduleEnvironment.exit(createAbruptExitException(exception, msg, besCode));
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommonCommandOptions() {
    return ImmutableList.of(
        optionsClass(),
        AuthAndTLSOptions.class,
        BuildEventStreamOptions.class,
        BuildEventProtocolOptions.class);
  }

  // Resets the maps tracking the state of closing/half-closing BES transports.
  private void resetPendingUploads() {
    closeFuturesWithTimeoutsMap = ImmutableMap.of();
    halfCloseFuturesWithTimeoutsMap = ImmutableMap.of();
  }

  // Cancels and interrupts any in-flight threads closing BES transports, then resets the maps
  // tracking in-flight close operations.
  private void cancelAndResetPendingUploads() {
    closeFuturesWithTimeoutsMap
        .values()
        .forEach(closeFuture -> closeFuture.cancel(/* mayInterruptIfRunning= */ true));
    resetPendingUploads();
  }

  private void removeFromPendingUploads(
      Map<BuildEventTransport, ListenableFuture<Void>> transportFutures) {
    transportFutures
        .values()
        .forEach(closeFuture -> closeFuture.cancel(/* mayInterruptIfRunning= */ true));
    closeFuturesWithTimeoutsMap =
        closeFuturesWithTimeoutsMap.entrySet().stream()
            .filter(entry -> !transportFutures.containsKey(entry.getKey()))
            .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    halfCloseFuturesWithTimeoutsMap =
        halfCloseFuturesWithTimeoutsMap.entrySet().stream()
            .filter(entry -> !transportFutures.containsKey(entry.getKey()))
            .collect(toImmutableMap(Entry::getKey, Entry::getValue));
  }

  private static boolean isTimeoutException(ExecutionException e) {
    return e.getCause() instanceof TimeoutException;
  }

  private void waitForPreviousInvocation(boolean isShutdown) {
    if (closeFuturesWithTimeoutsMap.isEmpty()) {
      return;
    }

    ConnectivityStatus status = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY);
    if (status.status != ConnectivityStatus.Status.OK) {
      reporter.handle(
          Event.info(
              String.format(
                  "The Build Event Protocol encountered a connectivity problem: %s. Cancelling"
                      + " previous background uploads",
                  status)));
      cancelAndResetPendingUploads();
      return;
    }

    ImmutableMap<BuildEventTransport, ListenableFuture<Void>> waitingFutureMap =
        closeFuturesWithTimeoutsMap.entrySet().stream()
            .map(
                entry -> {
                  var transport = entry.getKey();
                  var closeFuture = entry.getValue();
                  ListenableFuture<Void> future = closeFuture;
                  if (transport.getBesUploadMode() == BesUploadMode.FULLY_ASYNC) {
                    future =
                        isShutdown ? closeFuture : halfCloseFuturesWithTimeoutsMap.get(transport);
                    if (future == null) {
                      future = closeFuture;
                    }
                  }
                  return new SimpleEntry<>(transport, future);
                })
            .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    ImmutableMap<BuildEventTransport, ListenableFuture<Void>> cancelCloseFutures =
        closeFuturesWithTimeoutsMap.entrySet().stream()
            .filter(
                entry -> {
                  var transport = entry.getKey();
                  return transport.getBesUploadMode() != BesUploadMode.FULLY_ASYNC;
                })
            .collect(toImmutableMap(Entry::getKey, Entry::getValue));

    Stopwatch stopwatch = Stopwatch.createStarted();
    try {
      // TODO(b/234994611): It would be better to report before we wait, but the current
      //  infrastructure does not support that. At least we can report it afterwards.
      Uninterruptibles.getUninterruptibly(
          Futures.allAsList(waitingFutureMap.values()),
          getMaxWaitForPreviousInvocation().toMillis(),
          MILLISECONDS);
      long waitedMillis = stopwatch.elapsed().toMillis();
      if (waitedMillis > 100) {
        reporter.handle(
            Event.info(
                String.format(
                    "Waited for the background upload of the Build Event Protocol for "
                        + "%d.%03d seconds.",
                    waitedMillis / 1000, waitedMillis % 1000)));
      }
    } catch (TimeoutException exception) {
      long waitedMillis = stopwatch.elapsed().toMillis();
      String msg =
          String.format(
              "The background upload of the Build Event Protocol for the previous invocation "
                  + "failed to complete in %d.%03d seconds. "
                  + "Cancelling and starting a new invocation...",
              waitedMillis / 1000, waitedMillis % 1000);
      reporter.handle(Event.warn(msg));
      logger.atWarning().withCause(exception).log("%s", msg);
      cancelCloseFutures = closeFuturesWithTimeoutsMap;
    } catch (ExecutionException e) {
      String msg;
      // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
      // times out.
      if (isTimeoutException(e)) {
        msg =
            "The background upload of the Build Event Protocol for the previous invocation "
                + "failed due to a network timeout. Ignoring the failure and starting a new "
                + "invocation...";
      } else {
        msg =
            String.format(
                "The background upload of the Build Event Protocol for the previous invocation "
                    + "failed with the following exception: '%s'. "
                    + "Ignoring the failure and starting a new invocation...",
                e.getMessage());
      }
      reporter.handle(Event.warn(msg));
      logger.atWarning().withCause(e).log("%s", msg);
      cancelCloseFutures = closeFuturesWithTimeoutsMap;
    } finally {
      cancelCloseFutures
          .values()
          .forEach(closeFuture -> closeFuture.cancel(/* mayInterruptIfRunning= */ true));
      resetPendingUploads();
    }
  }

  @Override
  public void beforeCommand(CommandEnvironment cmdEnv) throws AbruptExitException {
    this.invocationId = cmdEnv.getCommandId().toString();
    this.buildRequestId = cmdEnv.getBuildRequestId();
    this.reporter = cmdEnv.getReporter();

    this.connectivityProvider =
        Preconditions.checkNotNull(
            cmdEnv.getRuntime().getBlazeModule(ConnectivityStatusProvider.class),
            "No ConnectivityStatusProvider found in modules list");

    OptionsParsingResult parsingResult = cmdEnv.getOptions();
    this.besOptions = Preconditions.checkNotNull(parsingResult.getOptions(optionsClass()));
    this.bepOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(BuildEventProtocolOptions.class));
    this.authTlsOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(AuthAndTLSOptions.class));
    this.besStreamOptions =
        Preconditions.checkNotNull(parsingResult.getOptions(BuildEventStreamOptions.class));
    this.isRunsPerTestOverTheLimit =
        parsingResult.getOptions(TestOptions.class) != null
            && parsingResult.getOptions(TestOptions.class).runsPerTest.stream()
                .anyMatch(
                    (perLabelOptions) ->
                        Integer.parseInt(Iterables.getOnlyElement(perLabelOptions.getOptions()))
                            > RUNS_PER_TEST_LIMIT);
    this.uiUsesColor =
        Preconditions.checkNotNull(parsingResult.getOptions(UiOptions.class)).useColor();

    ConnectivityStatus status = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY);
    String buildEventUploadStrategy =
        status.status.equals(ConnectivityStatus.Status.OK)
            ? this.bepOptions.buildEventUploadStrategy
            : "local";

    buildEventOutputStreamFactory = createBuildEventOutputStreamFactory(cmdEnv);
    CountingArtifactGroupNamer artifactGroupNamer = new CountingArtifactGroupNamer();

    // We need to wait for the previous invocation before we check the list of allowed commands to
    // allow completing previous runs using BES, for example:
    //   bazel build (..run with async BES..)
    //   bazel info <-- Doesn't run with BES unless we wait before checking {@code allowedCommands}.
    boolean commandIsShutdown = "shutdown".equals(cmdEnv.getCommandName());
    waitForPreviousInvocation(commandIsShutdown);
    if (commandIsShutdown && uploaderFactoryToCleanup != null) {
      uploaderFactoryToCleanup.shutdown();
    }

    if (!allowedCommands(besOptions).contains(cmdEnv.getCommandName())) {
      // Exit early if the running command isn't supported.
      return;
    }

    BuildEventArtifactUploaderFactory uploaderFactory =
        cmdEnv
            .getRuntime()
            .getBuildEventArtifactUploaderFactoryMap()
            .select(buildEventUploadStrategy);
    ThrowingBuildEventArtifactUploaderSupplier uploaderSupplier =
        new ThrowingBuildEventArtifactUploaderSupplier(() -> uploaderFactory.create(cmdEnv));
    this.uploaderFactoryToCleanup = uploaderFactory;

    try {
      bepTransports = createBepTransports(cmdEnv, uploaderSupplier, artifactGroupNamer);
    } catch (IOException e) {
      cmdEnv
          .getBlazeModuleEnvironment()
          .exit(
              createAbruptExitException(
                  e,
                  "Could not create BEP transports.",
                  BuildProgress.Code.BES_INITIALIZATION_ERROR));
      return;
    }
    if (bepTransports.isEmpty()) {
      // Exit early if there are no transports to stream to. However, report that the set of
      // transports has been determined so that interested parties always get this event if there
      // was no error during setting up the transports.
      reporter.post(new AnnounceBuildEventTransportsEvent(bepTransports));
      return;
    }

    if (bepOptions.publishTargetSummary) {
      cmdEnv
          .getEventBus()
          .register(
              new TargetSummaryPublisher(
                  cmdEnv.getEventBus(), cmdEnv::withMergedAnalysisAndExecutionSourceOfTruth));
    }

    streamer =
        new BuildEventStreamer.Builder()
            .buildEventTransports(bepTransports)
            .besStreamOptions(besStreamOptions)
            .outputGroupFileModes(bepOptions.getOutputGroupFileModesMapping())
            .publishTargetSummaries(bepOptions.publishTargetSummary)
            .artifactGroupNamer(artifactGroupNamer)
            .oomMessage(parsingResult.getOptions(CommonCommandOptions.class).oomMessage)
            .build();

    cmdEnv.getEventBus().register(streamer);
    registerOutAndErrOutputStreams();

    // This event should probably be posted in a more general place (e.g. {@link BuildTool};
    // however, so far the BES module is the only module that requires extra work after the build
    // so we post it here until it's needed for other modules.
    reporter.post(new AnnounceBuildEventTransportsEvent(bepTransports));
  }

  private void registerOutAndErrOutputStreams() {
    int bufferSize = besOptions.besOuterrBufferSize;
    int chunkSize = besOptions.besOuterrChunkSize;
    SynchronizedOutputStream out =
        new SynchronizedOutputStream(bufferSize, chunkSize, /* isStderr= */ false);
    SynchronizedOutputStream err =
        new SynchronizedOutputStream(bufferSize, chunkSize, /* isStderr= */ true);

    this.outErr = OutErr.create(out, err);
    streamer.registerOutErrProvider(
        new BuildEventStreamer.OutErrProvider() {
          @Override
          public Iterable<String> getOut() {
            return out.readAndReset();
          }

          @Override
          public Iterable<String> getErr() {
            return err.readAndReset();
          }
        });
    err.registerStreamer(streamer);
    out.registerStreamer(streamer);
  }

  @Override
  public OutErr getOutputListener() {
    return outErr;
  }

  private void forceShutdownBuildEventStreamer(AbortReason reason) {
    streamer.closeOnAbort(reason);
    closeFuturesWithTimeoutsMap =
        constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap());
    try {
      logger.atInfo().log("Closing pending build event transports");
      ListenableFuture<List<Void>> besClosedFuture =
          Futures.allAsList(closeFuturesWithTimeoutsMap.values());
      if (reason == AbortReason.OUT_OF_MEMORY) {
        // GC thrashing during severe OOMs may prevent future completion, so don't wait forever.
        // We do want to wait in case this is a "benign" OOM - a brief high-water-mark - because
        // then we can preserve that information in the BEP being uploaded to BES.
        besClosedFuture.get(besOptions.besOomFinishUploadTimeout.toMillis(), MILLISECONDS);
      } else {
        Uninterruptibles.getUninterruptibly(besClosedFuture);
      }
    } catch (ExecutionException | TimeoutException | InterruptedException e) {
      // TimeoutException and InterruptedException only thrown while crashing with OUT_OF_MEMORY.
      logger.atSevere().withCause(e).log("Failed to close a build event transport");
    } finally {
      cancelAndResetPendingUploads();
    }
  }

  @Override
  public void blazeShutdownOnCrash(DetailedExitCode exitCode) {
    if (streamer != null) {
      logger.atWarning().log("Attempting to close BES streamer on crash");
      forceShutdownBuildEventStreamer(
          exitCode.getExitCode().equals(ExitCode.OOM_ERROR)
              ? AbortReason.OUT_OF_MEMORY
              : AbortReason.INTERNAL);
      uploaderFactoryToCleanup.shutdown();
    }
  }

  @Override
  public void blazeShutdown() {
    if (closeFuturesWithTimeoutsMap.isEmpty()) {
      return;
    }

    try {
      Uninterruptibles.getUninterruptibly(
          Futures.allAsList(closeFuturesWithTimeoutsMap.values()),
          getMaxWaitForPreviousInvocation().toSeconds(),
          TimeUnit.SECONDS);
    } catch (TimeoutException | ExecutionException exception) {
      logger.atWarning().withCause(exception).log(
          "Encountered Exception when closing BEP transports in Blaze's shutting down sequence");
    } finally {
      cancelAndResetPendingUploads();
      if (uploaderFactoryToCleanup != null) {
        uploaderFactoryToCleanup.shutdown();
      }
    }
  }

  private void waitForBuildEventTransportsToClose(
      Map<BuildEventTransport, ListenableFuture<Void>> transportFutures)
      throws AbruptExitException {
    final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("bes-notify-ui-%d").build());
    try {
      // Notify the UI handler when a transport finished closing.
      transportFutures.forEach(
          (bepTransport, closeFuture) ->
              closeFuture.addListener(
                  () -> {
                    reporter.post(new BuildEventTransportClosedEvent(bepTransport));
                  },
                  executor));

      try (AutoProfiler p =
          GoogleAutoProfilerUtils.logged(
              "waiting for BES close for invocation " + this.invocationId)) {
        Uninterruptibles.getUninterruptibly(Futures.allAsList(transportFutures.values()));
      }
    } catch (CancellationException e) {
      // This is expected if the upload needs to be cancelled for some reason, e.g. an error
      // interrupting the build.
    } catch (ExecutionException e) {
      // Futures.withTimeout wraps the TimeoutException in an ExecutionException when the future
      // times out.
      if (isTimeoutException(e)) {
        throw createAbruptExitException(
            e,
            "The Build Event Protocol upload timed out.",
            BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR);
      }

      Throwables.throwIfInstanceOf(e.getCause(), AbruptExitException.class);
      throw new RuntimeException(
          String.format(
              "Unexpected Exception '%s' when closing BEP transports, this is a bug.",
              e.getCause().getMessage()),
          e);
    } finally {
      removeFromPendingUploads(transportFutures);
      executor.shutdown();
    }
  }

  private static ImmutableMap<BuildEventTransport, ListenableFuture<Void>>
      constructCloseFuturesMapWithTimeouts(
          ImmutableMap<BuildEventTransport, ListenableFuture<Void>> bepTransportToCloseFuturesMap) {
    ImmutableMap.Builder<BuildEventTransport, ListenableFuture<Void>> builder =
        ImmutableMap.builder();

    bepTransportToCloseFuturesMap.forEach(
        (bepTransport, closeFuture) -> {
          final ListenableFuture<Void> closeFutureWithTimeout;
          if (bepTransport.getTimeout().isZero() || bepTransport.getTimeout().isNegative()) {
            closeFutureWithTimeout = closeFuture;
          } else {
            final ScheduledExecutorService timeoutExecutor =
                Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactoryBuilder()
                        .setNameFormat("bes-close-" + bepTransport.name() + "-%d")
                        .build());

            // Make sure to avoid propagating the cancellation to the enclosing future since
            // we handle cancellation ourselves in this class.
            // Futures.withTimeout may cancel the enclosing future when the timeout is
            // reached.
            final ListenableFuture<Void> enclosingFuture =
                Futures.nonCancellationPropagating(closeFuture);

            ListenableFuture<Void> timeoutFuture =
                Futures.withTimeout(
                    enclosingFuture,
                    bepTransport.getTimeout().toMillis(),
                    MILLISECONDS,
                    timeoutExecutor);
            timeoutFuture.addListener(timeoutExecutor::shutdown, MoreExecutors.directExecutor());

            // Cancellation is not propagated to the `closeFuture` for the reasons above. But in
            // order to cancel the returned future by our explicit mechanism elsewhere in this
            // class, we need to delegate the `cancel` to `closeFuture` so that cancellation
            // from Futures.withTimeout is ignored and cancellation from our mechanism is properly
            // handled.
            closeFutureWithTimeout =
                new SimpleForwardingListenableFuture<>(timeoutFuture) {
                  @Override
                  public boolean cancel(boolean mayInterruptIfRunning) {
                    return closeFuture.cancel(mayInterruptIfRunning);
                  }
                };
          }
          builder.put(bepTransport, closeFutureWithTimeout);
        });

    return builder.buildOrThrow();
  }

  private void closeBepTransports() throws AbruptExitException {
    closeFuturesWithTimeoutsMap =
        constructCloseFuturesMapWithTimeouts(streamer.getCloseFuturesMap());
    halfCloseFuturesWithTimeoutsMap =
        constructCloseFuturesMapWithTimeouts(streamer.getHalfClosedMap());
    Map<BuildEventTransport, ListenableFuture<Void>> blockingTransportFutures = new HashMap<>();
    for (Map.Entry<BuildEventTransport, ListenableFuture<Void>> entry :
        closeFuturesWithTimeoutsMap.entrySet()) {
      BuildEventTransport bepTransport = entry.getKey();
      boolean besUploadModeIsSynchronous =
          bepTransport.getBesUploadMode() == BesUploadMode.WAIT_FOR_UPLOAD_COMPLETE;
      if (!bepTransport.mayBeSlow() || besUploadModeIsSynchronous) {
        blockingTransportFutures.put(bepTransport, entry.getValue());
      } else {
        // When running asynchronously notify the UI immediately since we won't wait for the
        // uploads to close.
        reporter.post(new BuildEventTransportClosedEvent(bepTransport));
      }
    }
    if (!blockingTransportFutures.isEmpty()) {
      waitForBuildEventTransportsToClose(blockingTransportFutures);
    }
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    if (streamer != null) {
      if (!streamer.isClosed()) {
        // This should not occur, but close with an internal error if a {@link BuildEventStreamer}
        // bug manifests as an unclosed streamer.
        logger.atWarning().log("Attempting to close BES streamer after command");
        reporter.handle(Event.warn("BES was not properly closed"));
        forceShutdownBuildEventStreamer(AbortReason.INTERNAL);
      }

      closeBepTransports();

      if (!Strings.isNullOrEmpty(besOptions.besBackend)) {
        constructAndMaybeReportInvocationIdUrl();
      } else if (!bepTransports.isEmpty()) {
        reporter.handle(Event.info("Build Event Protocol files produced successfully."));
      }
    }

    // besStreamOptions can be null if we are crashing. Don't crash here too.
    if (besStreamOptions != null && !besStreamOptions.keepBackendConnections) {
      clearBesClient();
    } else if (besStreamOptions == null) {
      BugReport.sendNonFatalBugReport(
          new NullPointerException("besStreamOptions null: in a crash?"));
    }
  }

  @Override
  public void commandComplete() {
    this.outErr = null;
    this.bepTransports = null;
    this.invocationId = null;
    this.buildRequestId = null;
    this.reporter = null;
    this.streamer = null;
    this.buildEventOutputStreamFactory = null;
  }

  private void constructAndMaybeReportInvocationIdUrl() {
    if (!getInvocationIdPrefix().isEmpty()) {
      StringBuilder msg = new StringBuilder();
      msg.append("Streaming build results to: ");
      if (uiUsesColor) {
        msg.append(new String(Color.CYAN.getEscapeSeq(), StandardCharsets.US_ASCII));
      }
      msg.append(getInvocationIdPrefix());
      msg.append(invocationId);
      if (uiUsesColor) {
        msg.append(new String(Color.DEFAULT.getEscapeSeq(), StandardCharsets.US_ASCII));
      }

      reporter.handle(Event.info(msg.toString()));
    }
  }

  private void constructAndMaybeReportBuildRequestIdUrl() {
    if (!getBuildRequestIdPrefix().isEmpty()) {
      reporter.handle(
          Event.info(
              "See "
                  + getBuildRequestIdPrefix()
                  + buildRequestId
                  + " for more information about your request."));
    }
  }

  private void logIds() {
    logger.atInfo().log(
        "Streaming Build Event Protocol to '%s' with build_request_id: '%s'"
            + " and invocation_id: '%s'",
        besOptions.besBackend, buildRequestId, invocationId);
  }

  @Nullable
  private BuildEventServiceTransport createBesTransport(
      CommandEnvironment cmdEnv,
      ThrowingBuildEventArtifactUploaderSupplier uploaderSupplier,
      CountingArtifactGroupNamer artifactGroupNamer)
      throws IOException {
    if (Strings.isNullOrEmpty(besOptions.besBackend)) {
      clearBesClient();
      return null;
    }

    if (isRunsPerTestOverTheLimit) {
      String msg =
          String.format(
              "The value of --runs_per_test is bigger than %d and it will produce build events "
                  + "that are too big for the Build Event Service to handle.",
              RUNS_PER_TEST_LIMIT);
      reportError(
          reporter,
          cmdEnv.getBlazeModuleEnvironment(),
          msg,
          new OptionsParsingException(msg),
          BuildProgress.Code.BES_RUNS_PER_TEST_LIMIT_UNSUPPORTED);
      return null;
    }

    logIds();

    ConnectivityStatus status = connectivityProvider.getStatus(CONNECTIVITY_CACHE_KEY);
    if (status.status != Status.OK) {
      clearBesClient();
      String message =
          String.format(
              "Build Event Service uploads disabled due to a connectivity problem: %s", status);
      reporter.handle(Event.warn(message));
      logger.atWarning().log("%s", message);
      return null;
    }

    final BuildEventServiceClient besClient;
    try {
      besClient = getBesClient(cmdEnv, besOptions, authTlsOptions);
    } catch (IOException | OptionsParsingException e) {
      reportError(
          reporter,
          cmdEnv.getBlazeModuleEnvironment(),
          e.getMessage(),
          e,
          BuildProgress.Code.BES_INITIALIZATION_ERROR);
      return null;
    }

    BuildEventServiceProtoUtil besProtoUtil =
        new BuildEventServiceProtoUtil.Builder()
            .buildRequestId(buildRequestId)
            .invocationId(invocationId)
            .projectId(besOptions.instanceName)
            .commandName(cmdEnv.getCommandName())
            .keywords(getBesKeywords(besOptions, cmdEnv.getRuntime().getStartupOptionsProvider()))
            .checkPrecedingLifecycleEvents(besOptions.besCheckPrecedingLifecycleEvents)
            .attemptNumber(cmdEnv.getAttemptNumber())
            .build();

    return new BuildEventServiceTransport.Builder()
        .localFileUploader(uploaderSupplier.get())
        .besClient(besClient)
        .besOptions(besOptions)
        .besProtoUtil(besProtoUtil)
        .artifactGroupNamer(artifactGroupNamer)
        .bepOptions(bepOptions)
        .clock(cmdEnv.getRuntime().getClock())
        .eventBus(cmdEnv.getEventBus())
        .commandStartTime(Timestamps.fromMillis(cmdEnv.getCommandStartTime()))
        .build();
  }

  /**
   * Returns the JSON type registry, used to resolve {@code Any} type names at serialization time.
   *
   * <p>Intended to be overridden by custom build tools with a subclassed {@link
   * BuildEventServiceModule} to add additional Any types to be produced.
   */
  protected TypeRegistry makeJsonTypeRegistry() {
    return TypeRegistry.newBuilder().add(SpawnExec.getDescriptor()).build();
  }

  private ImmutableSet<BuildEventTransport> createBepTransports(
      CommandEnvironment cmdEnv,
      ThrowingBuildEventArtifactUploaderSupplier uploaderSupplier,
      CountingArtifactGroupNamer artifactGroupNamer)
      throws IOException {
    ImmutableSet.Builder<BuildEventTransport> bepTransportsBuilder = new ImmutableSet.Builder<>();

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventTextFile)) {
      try {
        BufferedOutputStream bepTextOutputStream =
            buildEventOutputStreamFactory.create(
                BuildEventFileType.TEXT, besStreamOptions.buildEventTextFile);
        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventTextFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new TextFormatFileTransport(
                bepTextOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer,
                besStreamOptions.buildEventTextFileUploadMode));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            reporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventTextFile
                + "'. Omitting --build_event_text_file.",
            exception,
            BuildProgress.Code.BES_LOCAL_WRITE_ERROR);
      }
    }

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventBinaryFile)) {
      try {
        BufferedOutputStream bepBinaryOutputStream =
            buildEventOutputStreamFactory.create(
                BuildEventFileType.BINARY, besStreamOptions.buildEventBinaryFile);
        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventBinaryFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new BinaryFormatFileTransport(
                bepBinaryOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer,
                besStreamOptions.buildEventBinaryFileUploadMode));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            reporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventBinaryFile
                + "'. Omitting --build_event_binary_file.",
            exception,
            BuildProgress.Code.BES_LOCAL_WRITE_ERROR);
      }
    }

    if (!Strings.isNullOrEmpty(besStreamOptions.buildEventJsonFile)) {
      try {
        BufferedOutputStream bepJsonOutputStream =
            buildEventOutputStreamFactory.create(
                BuildEventFileType.JSON, besStreamOptions.buildEventJsonFile);
        BuildEventArtifactUploader localFileUploader =
            besStreamOptions.buildEventJsonFilePathConversion
                ? uploaderSupplier.get()
                : new LocalFilesArtifactUploader();
        bepTransportsBuilder.add(
            new JsonFormatFileTransport(
                bepJsonOutputStream,
                bepOptions,
                localFileUploader,
                artifactGroupNamer,
                makeJsonTypeRegistry(),
                besStreamOptions.buildEventJsonFileUploadMode));
      } catch (IOException exception) {
        // TODO(b/125216340): Consider making this a warning instead of an error once the
        //  associated bug has been resolved.
        reportError(
            reporter,
            cmdEnv.getBlazeModuleEnvironment(),
            "Unable to write to '"
                + besStreamOptions.buildEventJsonFile
                + "'. Omitting --build_event_json_file.",
            exception,
            BuildProgress.Code.BES_LOCAL_WRITE_ERROR);
      }
    }

    BuildEventServiceTransport besTransport =
        createBesTransport(cmdEnv, uploaderSupplier, artifactGroupNamer);
    if (besTransport != null) {
      constructAndMaybeReportInvocationIdUrl();
      constructAndMaybeReportBuildRequestIdUrl();
      bepTransportsBuilder.add(besTransport);
    }

    return bepTransportsBuilder.build();
  }

  private static AbruptExitException createAbruptExitException(
      Exception e, String message, BuildProgress.Code besCode) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message + " " + e.getMessage())
                .setBuildProgress(BuildProgress.newBuilder().setCode(besCode).build())
                .build()),
        e);
  }

  protected abstract Class<OptionsT> optionsClass();

  protected abstract BuildEventServiceClient getBesClient(
      CommandEnvironment env, OptionsT besOptions, AuthAndTLSOptions authAndTLSOptions)
      throws IOException, OptionsParsingException;

  protected abstract void clearBesClient();

  protected abstract Set<String> allowedCommands(OptionsT besOptions);

  @VisibleForTesting
  void setBuildEventOutputStreamFactory(BuildEventOutputStreamFactory factory) {
    this.buildEventOutputStreamFactory = factory;
  }

  protected ImmutableSet<String> getBesKeywords(
      OptionsT besOptions, @Nullable OptionsParsingResult startupOptionsProvider) {
    List<String> userKeywords = besOptions.besKeywords;
    List<String> systemKeywords = besOptions.besSystemKeywords;
    ImmutableSet.Builder<String> keywords =
        ImmutableSet.builderWithExpectedSize(userKeywords.size() + systemKeywords.size());
    for (String userKeyword : userKeywords) {
      keywords.add("user_keyword=" + userKeyword);
    }
    keywords.addAll(systemKeywords);
    return keywords.build();
  }

  /** A prefix used when printing the invocation ID in the command line */
  protected abstract String getInvocationIdPrefix();

  /** A prefix used when printing the build request ID in the command line */
  protected abstract String getBuildRequestIdPrefix();

  // TODO(b/115961387): This method shouldn't exist. It only does because some tests are relying on
  //  the transport creation logic of this module directly.
  @VisibleForTesting
  ImmutableSet<BuildEventTransport> getBepTransports() {
    return bepTransports;
  }

  private static class ThrowingBuildEventArtifactUploaderSupplier {
    private final Callable<BuildEventArtifactUploader> callable;
    @Nullable private BuildEventArtifactUploader memoizedValue;
    @Nullable private IOException exception;

    ThrowingBuildEventArtifactUploaderSupplier(Callable<BuildEventArtifactUploader> callable) {
      this.callable = callable;
    }

    BuildEventArtifactUploader get() throws IOException {
      boolean needsInitialization = memoizedValue == null;
      if (needsInitialization && exception == null) {
        try {
          memoizedValue = callable.call();
        } catch (IOException e) {
          exception = e;
        } catch (Exception e) {
          Throwables.throwIfUnchecked(e);
          throw new IllegalStateException(e);
        }
      }
      if (memoizedValue != null) {
        if (!needsInitialization) {
          memoizedValue.retain();
        }
        return memoizedValue;
      }
      throw exception;
    }
  }

  @VisibleForTesting
  BuildEventOutputStreamFactory createBuildEventOutputStreamFactory(CommandEnvironment env) {
    return new BuildEventOutputStreamFactoryImpl(env);
  }

  @VisibleForTesting
  interface BuildEventOutputStreamFactory {
    BufferedOutputStream create(BuildEventFileType eventFileType, String filePath)
        throws IOException;
  }

  private static class BuildEventOutputStreamFactoryImpl implements BuildEventOutputStreamFactory {
    private final CommandEnvironment cmdEnv;

    BuildEventOutputStreamFactoryImpl(CommandEnvironment cmdEnv) {
      this.cmdEnv = cmdEnv;
    }

    @Override
    public BufferedOutputStream create(BuildEventFileType eventFileType, String filePath)
        throws IOException {
      String buildEventFileName = "";
      switch (eventFileType) {
        case TEXT:
          buildEventFileName = "build_event_text_file";
          break;
        case BINARY:
          buildEventFileName = "build_event_binary_file";
          break;
        case JSON:
          buildEventFileName = "build_event_json_file";
          break;
      }
      InstrumentationOutput output =
          cmdEnv
              .getRuntime()
              .getInstrumentationOutputFactory()
              .createInstrumentationOutput(
                  buildEventFileName,
                  PathFragment.create(filePath),
                  DestinationRelativeTo.WORKSPACE_OR_HOME,
                  cmdEnv,
                  cmdEnv.getReporter(),
                  /* append= */ null,
                  /* internal= */ null,
                  /* createParent= */ true);
      return new BufferedOutputStream(output.createOutputStream());
    }
  }
}
