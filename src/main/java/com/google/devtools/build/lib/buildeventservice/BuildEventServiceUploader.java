// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.v1.BuildStatus.Result.COMMAND_FAILED;
import static com.google.devtools.build.v1.BuildStatus.Result.COMMAND_SUCCEEDED;
import static com.google.devtools.build.v1.BuildStatus.Result.UNKNOWN_STATUS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.AckReceivedCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.EventLoopCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.OpenStreamCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.SendBuildEventCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.SendLastBuildEventCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.SendRegularBuildEventCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.SendRequestBuildEventCommand;
import com.google.devtools.build.lib.buildeventservice.BuildEventServiceUploaderCommands.StreamCompleteCommand;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient.StreamContext;
import com.google.devtools.build.lib.buildeventstream.AbortedEvent;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildCompletingEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.LargeBuildEventSerializedEvent;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.server.FailureDetails.BuildProgress;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Sleeper;
import com.google.devtools.build.v1.BuildStatus.Result;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishLifecycleEventRequest;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.concurrent.GuardedBy;

/**
 * Uploader of Build Events to the Build Event Service (BES).
 *
 * <p>The purpose is of this class is to manage the interaction between the BES client and the BES
 * server. It implements the event loop pattern based on the commands defined by {@link
 * BuildEventServiceUploaderCommands}.
 */
// TODO(lpino): This class should be package-private but there are unit tests that are in the
//  different packages and rely on this.
@VisibleForTesting
public final class BuildEventServiceUploader implements Runnable {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final BuildEventServiceClient besClient;
  private final BuildEventArtifactUploader buildEventUploader;
  private final BuildEventServiceProtoUtil besProtoUtil;
  private final BuildEventProtocolOptions buildEventProtocolOptions;
  private final boolean publishLifecycleEvents;
  private final Sleeper sleeper;
  private final Clock clock;
  private final ArtifactGroupNamer namer;
  private final EventBus eventBus;
  // `commandStartTime` is an instant in time determined by the build tool's native launcher and
  // matches `BuildStartingEvent.getRequest().getStartTime()`.
  private final Timestamp commandStartTime;
  // `eventStreamStartTime` is an instant *after* `commandStartTime` indicating when the
  // BuildEventServiceUploader was initialized to begin reporting build events. This instant should
  // be *before* the event_time for any BuildEvents uploaded after they are received via
  // `#enqueueEvent(BuildEvent)`.
  private final Timestamp eventStreamStartTime;
  private boolean startedClose = false;

  private final ScheduledExecutorService timeoutExecutor =
      MoreExecutors.listeningDecorator(
          Executors.newSingleThreadScheduledExecutor(
              new ThreadFactoryBuilder().setNameFormat("bes-uploader-timeout-%d").build()));

  /**
   * The event queue contains two types of events: - Build events, sorted by sequence number, that
   * should be sent to the server - Command events that are used by {@link #publishBuildEvents()} to
   * change state.
   */
  private final BlockingDeque<EventLoopCommand> eventQueue = new LinkedBlockingDeque<>();

  /**
   * Computes sequence numbers for build events. As per the BES protocol, sequence numbers must be
   * consecutive monotonically increasing natural numbers.
   */
  private final AtomicLong nextSeqNum = new AtomicLong(1);

  private final Object lock = new Object();

  @GuardedBy("lock")
  private Result buildStatus = UNKNOWN_STATUS;

  private final SettableFuture<Void> closeFuture = SettableFuture.create();
  private final SettableFuture<Void> halfCloseFuture = SettableFuture.create();

  /**
   * The thread that calls the lifecycle RPCs and does the build event upload. It's started lazily
   * on the first call to {@link #enqueueEvent(BuildEvent)} or {@link #close()} (which ever comes
   * first).
   */
  @GuardedBy("lock")
  private Thread uploadThread;

  @GuardedBy("lock")
  private boolean interruptCausedByCancel;

  private StreamContext streamContext;

  private BuildEventServiceUploader(
      BuildEventServiceClient besClient,
      BuildEventArtifactUploader localFileUploader,
      BuildEventServiceProtoUtil besProtoUtil,
      BuildEventProtocolOptions buildEventProtocolOptions,
      boolean publishLifecycleEvents,
      Sleeper sleeper,
      Clock clock,
      ArtifactGroupNamer namer,
      EventBus eventBus,
      Timestamp commandStartTime) {
    this.besClient = besClient;
    this.buildEventUploader = localFileUploader;
    this.besProtoUtil = besProtoUtil;
    this.buildEventProtocolOptions = buildEventProtocolOptions;
    this.publishLifecycleEvents = publishLifecycleEvents;
    this.sleeper = sleeper;
    this.clock = clock;
    this.namer = namer;
    this.eventBus = eventBus;
    this.commandStartTime = commandStartTime;
    this.eventStreamStartTime = currentTime();
    // Ensure the half-close future is closed once the upload is complete. This is usually a no-op,
    // but makes sure we half-close in case of error / interrupt.
    closeFuture.addListener(
        () -> halfCloseFuture.setFuture(closeFuture), MoreExecutors.directExecutor());
  }

  BuildEventArtifactUploader getBuildEventUploader() {
    return buildEventUploader;
  }

  /** Enqueues an event for uploading to a BES backend. */
  void enqueueEvent(BuildEvent event) {
    // This needs to happen outside a synchronized block as it may trigger
    // stdout/stderr and lead to a deadlock. See b/109725432
    ListenableFuture<PathConverter> localFileUploadFuture =
        buildEventUploader.uploadReferencedLocalFiles(event.referencedLocalFiles());

    // The generation of the sequence number and the addition to the {@link #eventQueue} should be
    // atomic since BES expects the events in that exact order.
    // More details can be found in b/131393380.
    // TODO(bazel-team): Consider relaxing this invariant by having a more relaxed order.
    synchronized (lock) {
      if (startedClose) {
        return;
      }
      // BuildCompletingEvent marks the end of the build in the BEP event stream.
      if (event instanceof BuildCompletingEvent buildCompletingEvent) {
        ExitCode exitCode = buildCompletingEvent.getExitCode();
        if (exitCode != null && exitCode.getNumericExitCode() == 0) {
          buildStatus = COMMAND_SUCCEEDED;
        } else {
          buildStatus = COMMAND_FAILED;
        }
      } else if (event instanceof AbortedEvent && event.getEventId().hasBuildFinished()) {
        // An AbortedEvent with a build finished ID means we are crashing.
        buildStatus = COMMAND_FAILED;
      }
      ensureUploadThreadStarted();

      // TODO(b/131393380): {@link #nextSeqNum} doesn't need to be an AtomicInteger if it's
      //  always used under lock. It would be cleaner and more performant to update the sequence
      //  number when we take the item off the queue.
      eventQueue.addLast(
          new SendRegularBuildEventCommand(
              event,
              localFileUploadFuture,
              nextSeqNum.getAndIncrement(),
              Timestamps.fromMillis(clock.currentTimeMillis())));
    }
  }

  /**
   * Gracefully stops the BES upload. All events enqueued before the call to close will be uploaded
   * and events enqueued after the call will be discarded.
   *
   * <p>The returned future completes when the upload completes. It's guaranteed to never fail.
   */
  public ListenableFuture<Void> close() {
    ensureUploadThreadStarted();

    // The generation of the sequence number and the addition to the {@link #eventQueue} should be
    // atomic since BES expects the events in that exact order.
    // More details can be found in b/131393380.
    // TODO(bazel-team): Consider relaxing this invariant by having a more relaxed order.
    synchronized (lock) {
      if (startedClose) {
        return closeFuture;
      }
      startedClose = true;
      // Enqueue the last event which will terminate the upload.
      // TODO(b/131393380): {@link #nextSeqNum} doesn't need to be an AtomicInteger if it's
      //  always used under lock. It would be cleaner and more performant to update the sequence
      //  number when we take the item off the queue.
      eventQueue.addLast(
          new SendLastBuildEventCommand(nextSeqNum.getAndIncrement(), currentTime()));
    }

    final SettableFuture<Void> finalCloseFuture = closeFuture;
    closeFuture.addListener(
        () -> {
          // Make sure to cancel any pending uploads if the closing is cancelled.
          if (finalCloseFuture.isCancelled()) {
            closeOnCancel();
          }
        },
        MoreExecutors.directExecutor());

    return closeFuture;
  }

  private void closeOnCancel() {
    synchronized (lock) {
      interruptCausedByCancel = true;
      closeNow();
    }
  }

  /** Stops the upload immediately. Enqueued events that have not been sent yet will be lost. */
  private void closeNow() {
    synchronized (lock) {
      if (uploadThread != null) {
        if (uploadThread.isInterrupted()) {
          return;
        }
        uploadThread.interrupt();
      }
    }
  }

  ListenableFuture<Void> getHalfCloseFuture() {
    return halfCloseFuture;
  }

  private DetailedExitCode logAndSetException(
      String message, BuildProgress.Code bpCode, Throwable cause) {
    logger.atSevere().log("%s", message);
    DetailedExitCode detailedExitCode =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message + " " + besClient.userReadableError(cause))
                .setBuildProgress(BuildProgress.newBuilder().setCode(bpCode).build())
                .build());
    closeFuture.setException(new AbruptExitException(detailedExitCode, cause));
    return detailedExitCode;
  }

  @Override
  public void run() {
    try {
      if (publishLifecycleEvents) {
        publishLifecycleEvent(besProtoUtil.buildEnqueued(commandStartTime));
        publishLifecycleEvent(besProtoUtil.invocationStarted(eventStreamStartTime));
      }

      try {
        publishBuildEvents();
      } finally {
        if (publishLifecycleEvents) {
          Result buildStatus;
          synchronized (lock) {
            buildStatus = this.buildStatus;
          }
          publishLifecycleEvent(besProtoUtil.invocationFinished(currentTime(), buildStatus));
          publishLifecycleEvent(besProtoUtil.buildFinished(currentTime(), buildStatus));
        }
      }
      eventBus.post(BuildEventServiceAvailabilityEvent.ofSuccess());
    } catch (InterruptedException e) {
      synchronized (lock) {
        Preconditions.checkState(
            interruptCausedByCancel, "Unexpected interrupt on BES uploader thread");
      }
    } catch (DetailedStatusException e) {
      boolean isTransient = shouldRetryStatus(e.getStatus());
      ExitCode exitCode =
          isTransient
              ? ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR
              : ExitCode.PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR;
      DetailedExitCode detailedExitCode = logAndSetException(e.extendedMessage, e.bpCode, e);
      eventBus.post(
          new BuildEventServiceAvailabilityEvent(exitCode, detailedExitCode.getFailureDetail()));
    } catch (LocalFileUploadException e) {
      Throwables.throwIfUnchecked(e.getCause());
      DetailedExitCode detailedExitCode =
          logAndSetException(
              "The Build Event Protocol local file upload failed:",
              BuildProgress.Code.BES_UPLOAD_LOCAL_FILE_ERROR,
              e.getCause());
      eventBus.post(
          new BuildEventServiceAvailabilityEvent(
              ExitCode.TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR,
              detailedExitCode.getFailureDetail()));
    } catch (Throwable e) {
      closeFuture.setException(e);
      logger.atSevere().log("BES upload failed due to a RuntimeException / Error. This is a bug.");
      throw e;
    } finally {
      buildEventUploader.release();
      MoreExecutors.shutdownAndAwaitTermination(timeoutExecutor, 0, TimeUnit.MILLISECONDS);
      closeFuture.set(null);
    }
  }

  private BuildEventStreamProtos.BuildEvent createSerializedRegularBuildEvent(
      PathConverter pathConverter, SendRegularBuildEventCommand buildEvent)
      throws InterruptedException {

    BuildEventContext ctx =
        new BuildEventContext() {
          private final OutputGroupFileModes outputGroupModes =
              buildEventProtocolOptions.getOutputGroupFileModesMapping();

          @Override
          public PathConverter pathConverter() {
            return pathConverter;
          }

          @Override
          public ArtifactGroupNamer artifactGroupNamer() {
            return namer;
          }

          @Override
          public BuildEventProtocolOptions getOptions() {
            return buildEventProtocolOptions;
          }

          @Override
          public OutputGroupFileMode getFileModeForOutputGroup(String outputGroup) {
            return outputGroupModes.getMode(outputGroup);
          }
        };
    BuildEventStreamProtos.BuildEvent serializedBepEvent = buildEvent.getEvent().asStreamProto(ctx);

    // TODO(lpino): Remove this logging once we can make every single event smaller than 1MB
    // as protobuf recommends.
    if (serializedBepEvent.getSerializedSize()
        > LargeBuildEventSerializedEvent.SIZE_OF_LARGE_BUILD_EVENTS_IN_BYTES) {
      eventBus.post(
          new LargeBuildEventSerializedEvent(
              serializedBepEvent.getId().toString(), serializedBepEvent.getSerializedSize()));
    }

    return serializedBepEvent;
  }

  private void publishBuildEvents()
      throws DetailedStatusException, LocalFileUploadException, InterruptedException {
    eventQueue.addFirst(new OpenStreamCommand());

    // Every build event sent to the server needs to be acknowledged by it. This queue stores
    // the build events that have been sent and still have to be acknowledged by the server.
    // The build events are stored in the order they were sent.
    Deque<SendRequestBuildEventCommand> ackQueue = new ArrayDeque<>();
    boolean lastEventSent = false;
    int acksReceived = 0;
    int retryAttempt = 0;

    try {
      // {@link BuildEventServiceUploaderCommands#OPEN_STREAM} is the first event and opens a
      // bidi streaming RPC for sending build events and receiving ACKs.
      // {@link BuildEventServiceUploaderCommands#SEND_REGULAR_BUILD_EVENT} sends a build event to
      // the server. Sending of the Nth build event does
      // does not wait for the ACK of the N-1th build event to have been received.
      // {@link BuildEventServiceUploaderCommands#SEND_LAST_BUILD_EVENT} sends the last build event
      // and half closes the RPC.
      // {@link BuildEventServiceUploaderCommands#ACK_RECEIVED} is executed for every ACK from
      // the server and checks that the ACKs are in the correct order.
      // {@link BuildEventServiceUploaderCommands#STREAM_COMPLETE} checks that all build events
      // have been sent and all ACKs have been received. If not it invokes a retry logic that may
      // decide to re-send every build event for which an ACK has not been received. If so, it
      // adds an OPEN_STREAM event.
      while (true) {
        EventLoopCommand event = eventQueue.takeFirst();
        switch (event.type()) {
          case OPEN_STREAM -> {
            {
              // Invariant: the eventQueue only contains events of type SEND_REGULAR_BUILD_EVENT
              // or SEND_LAST_BUILD_EVENT
              logger.atInfo().log("Starting publishBuildEvents: eventQueue=%d", eventQueue.size());
              streamContext =
                  besClient.openStream(
                      (ack) -> eventQueue.addLast(new AckReceivedCommand(ack.getSequenceNumber())));
              addStreamStatusListener(
                  streamContext.getStatus(),
                  (status) -> eventQueue.addLast(new StreamCompleteCommand(status)));
            }
          }
          case SEND_REGULAR_BUILD_EVENT -> {
            {
              // Invariant: the eventQueue may contain events of any type
              SendRegularBuildEventCommand buildEvent = (SendRegularBuildEventCommand) event;

              PathConverter pathConverter = waitForUploads(buildEvent);

              BuildEventStreamProtos.BuildEvent serializedRegularBuildEvent =
                  createSerializedRegularBuildEvent(pathConverter, buildEvent);

              PublishBuildToolEventStreamRequest request =
                  besProtoUtil.bazelEvent(
                      buildEvent.getSequenceNumber(),
                      buildEvent.getCreationTime(),
                      Any.pack(serializedRegularBuildEvent));
              ackQueue.addLast(new SendRequestBuildEventCommand(request));
              streamContext.sendOverStream(request);
            }
            break;

          case SEND_REQUEST_BUILD_EVENT:
            {
              SendRequestBuildEventCommand buildEvent = (SendRequestBuildEventCommand) event;
              PublishBuildToolEventStreamRequest request = buildEvent.getRequest();
              ackQueue.addLast(buildEvent);
              streamContext.sendOverStream(request);
            }
          }
          case SEND_LAST_BUILD_EVENT -> {
            {
              // Invariant: the eventQueue may contain events of any type
              SendBuildEventCommand lastEvent = (SendLastBuildEventCommand) event;
              lastEventSent = true;
              PublishBuildToolEventStreamRequest request =
                  besProtoUtil.streamFinished(
                      lastEvent.getSequenceNumber(), lastEvent.getCreationTime());
              ackQueue.addLast(new SendRequestBuildEventCommand(request));
              streamContext.sendOverStream(request);
              streamContext.halfCloseStream();
              halfCloseFuture.set(null);
              logger.atInfo().log("BES uploader is half-closed");
            }
          }
          case ACK_RECEIVED -> {
            {
              // Invariant: the eventQueue may contain events of any type
              AckReceivedCommand ackEvent = (AckReceivedCommand) event;
              if (!ackQueue.isEmpty()) {
                SendRequestBuildEventCommand expected = ackQueue.removeFirst();
                long actualSeqNum = ackEvent.getSequenceNumber();
                if (expected.getSequenceNumber() == actualSeqNum) {
                  acksReceived++;
                } else {
                  ackQueue.addFirst(expected);
                  String message =
                      String.format(
                          "Expected ACK with seqNum=%d but received ACK with seqNum=%d",
                          expected.getSequenceNumber(), actualSeqNum);
                  logger.atInfo().log("%s", message);
                  streamContext.abortStream(Status.FAILED_PRECONDITION.withDescription(message));
                }
              } else {
                String message =
                    String.format(
                        "Received ACK (seqNum=%d) when no ACK was expected",
                        ackEvent.getSequenceNumber());
                logger.atInfo().log("%s", message);
                streamContext.abortStream(Status.FAILED_PRECONDITION.withDescription(message));
              }
            }
          }
          case STREAM_COMPLETE -> {
            {
              // Invariant: the eventQueue only contains events of type SEND_REGULAR_BUILD_EVENT
              // or SEND_LAST_BUILD_EVENT
              streamContext = null;
              StreamCompleteCommand completeEvent = (StreamCompleteCommand) event;
              Status streamStatus = completeEvent.status();
              if (streamStatus.isOk()) {
                if (lastEventSent && ackQueue.isEmpty()) {
                  logger.atInfo().log("publishBuildEvents was successful");
                  // Upload successful. Break out from the while(true) loop.
                  return;
                } else {
                  Status status =
                      lastEventSent
                          ? ackQueueNotEmptyStatus(ackQueue.size())
                          : lastEventNotSentStatus();
                  BuildProgress.Code bpCode =
                      lastEventSent
                          ? BuildProgress.Code.BES_STREAM_COMPLETED_WITH_UNACK_EVENTS_ERROR
                          : BuildProgress.Code.BES_STREAM_COMPLETED_WITH_UNSENT_EVENTS_ERROR;
                  throw withFailureDetail(status.asException(), bpCode, status.getDescription());
                }
              } else if (lastEventSent && ackQueue.isEmpty()) {
                throw withFailureDetail(
                    streamStatus.asException(),
                    BuildProgress.Code.BES_STREAM_COMPLETED_WITH_REMOTE_ERROR,
                    streamStatus.getDescription());
              }

              if (!shouldRetryStatus(streamStatus) || shouldStartNewInvocation(streamStatus)) {
                String message =
                    String.format("Not retrying publishBuildEvents: status='%s'", streamStatus);
                logger.atInfo().log("%s", message);
                BuildProgress.Code detailedCode =
                    shouldStartNewInvocation(streamStatus)
                        ? BuildProgress.Code.BES_UPLOAD_TIMEOUT_ERROR
                        : BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE;
                throw withFailureDetail(streamStatus.asException(), detailedCode, message);
              }
              if (retryAttempt == buildEventProtocolOptions.besUploadMaxRetries) {
                String message =
                    String.format(
                        "Not retrying publishBuildEvents, no more attempts left: status='%s'",
                        streamStatus);
                logger.atInfo().log("%s", message);
                throw withFailureDetail(
                    streamStatus.asException(),
                    BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE,
                    message);
              }

              // Retry logic
              // Adds events from the ackQueue to the front of the eventQueue, so that the
              // events in the eventQueue are sorted by sequence number (ascending).
              SendRequestBuildEventCommand unacked;
              while ((unacked = ackQueue.pollLast()) != null) {
                eventQueue.addFirst(unacked);
              }

              long sleepMillis = retrySleepMillis(retryAttempt);
              logger.atInfo().log(
                  "Retrying stream: status='%s', sleepMillis=%d", streamStatus, sleepMillis);
              sleeper.sleepMillis(sleepMillis);

              // If we made progress, meaning the server ACKed events that we sent, then reset
              // the retry counter to 0.
              if (acksReceived > 0) {
                retryAttempt = 0;
              } else {
                retryAttempt++;
              }
              acksReceived = 0;
              eventQueue.addFirst(new OpenStreamCommand());
            }
          }
        }
      }
    } catch (InterruptedException | LocalFileUploadException e) {
      int limit = 30;
      logger.atInfo().log(
          "Publish interrupt. Showing up to %d items from queues: ack_queue_size: %d, "
              + "ack_queue: %s, event_queue_size: %d, event_queue: %s",
          limit,
          ackQueue.size(),
          Iterables.limit(ackQueue, limit),
          eventQueue.size(),
          Iterables.limit(eventQueue, limit));
      if (streamContext != null) {
        streamContext.abortStream(Status.CANCELLED);
      }
      throw e;
    } finally {
      logger.atInfo().log("About to cancel all local file uploads");
      try (AutoProfiler ignored =
          GoogleAutoProfilerUtils.logged("local file upload cancellation")) {
        // Cancel all local file uploads that may still be running
        // of events that haven't been uploaded.
        EventLoopCommand event;
        while ((event = ackQueue.pollFirst()) != null) {
          if (event instanceof SendRegularBuildEventCommand sendRegularBuildEventCommand) {
            cancelLocalFileUpload(sendRegularBuildEventCommand);
          }
        }
        while ((event = eventQueue.pollFirst()) != null) {
          if (event instanceof SendRegularBuildEventCommand sendRegularBuildEventCommand) {
            cancelLocalFileUpload(sendRegularBuildEventCommand);
          }
        }
      }
    }
  }

  private void cancelLocalFileUpload(SendRegularBuildEventCommand event) {
    ListenableFuture<PathConverter> localFileUploaderFuture = event.localFileUploadProgress();
    if (!localFileUploaderFuture.isDone()) {
      localFileUploaderFuture.cancel(true);
    }
  }

  /** Sends a {@link PublishLifecycleEventRequest} to the BES backend. */
  private void publishLifecycleEvent(PublishLifecycleEventRequest request)
      throws DetailedStatusException, InterruptedException {
    int retryAttempt = 0;
    StatusException cause = null;
    while (retryAttempt <= this.buildEventProtocolOptions.besUploadMaxRetries) {
      try {
        besClient.publish(request);
        return;
      } catch (StatusException e) {
        if (!shouldRetryStatus(e.getStatus()) || shouldStartNewInvocation(e.getStatus())) {
          String message =
              String.format("Not retrying publishLifecycleEvent: status='%s'", e.getStatus());
          logger.atInfo().log("%s", message);
          throw withFailureDetail(e, BuildProgress.Code.BES_STREAM_NOT_RETRYING_FAILURE, message);
        }

        cause = e;

        long sleepMillis = retrySleepMillis(retryAttempt);
        logger.atInfo().log(
            "Retrying publishLifecycleEvent: status='%s', sleepMillis=%d",
            e.getStatus(), sleepMillis);
        sleeper.sleepMillis(sleepMillis);
        retryAttempt++;
      }
    }

    // All retry attempts failed
    throw withFailureDetail(
        cause,
        BuildProgress.Code.BES_UPLOAD_RETRY_LIMIT_EXCEEDED_FAILURE,
        String.format("All %d retry attempts failed.", retryAttempt - 1));
  }

  private void ensureUploadThreadStarted() {
    synchronized (lock) {
      if (uploadThread == null) {
        uploadThread = new Thread(this, "bes-uploader");
        uploadThread.start();
      }
    }
  }

  @SuppressWarnings("LogAndThrow") // Not confident in BES's error-handling.
  private PathConverter waitForUploads(SendRegularBuildEventCommand orderedBuildEvent)
      throws LocalFileUploadException, InterruptedException {
    try {
      // Wait for the local file and pending remote uploads to complete.
      buildEventUploader
          .waitForRemoteUploads(orderedBuildEvent.getEvent().remoteUploads(), timeoutExecutor)
          .get();
      return orderedBuildEvent.localFileUploadProgress().get();
    } catch (ExecutionException e) {
      logger.atWarning().withCause(e).log(
          "Failed to upload files referenced by build event: %s", e.getMessage());
      Throwables.throwIfUnchecked(e.getCause());
      throw new LocalFileUploadException(e.getCause());
    }
  }

  private Timestamp currentTime() {
    return Timestamps.fromMillis(clock.currentTimeMillis());
  }

  private static Status lastEventNotSentStatus() {
    return Status.FAILED_PRECONDITION.withDescription(
        "Server closed stream with status OK but not all events have been sent");
  }

  private static Status ackQueueNotEmptyStatus(int ackQueueSize) {
    return Status.FAILED_PRECONDITION.withDescription(
        String.format(
            "Server closed stream with status OK but not all ACKs have been"
                + " received (ackQueue=%d)",
            ackQueueSize));
  }

  private static void addStreamStatusListener(
      ListenableFuture<Status> stream, Consumer<Status> onDone) {
    Futures.addCallback(
        stream,
        new FutureCallback<Status>() {
          @Override
          public void onSuccess(Status result) {
            onDone.accept(result);
          }

          @Override
          public void onFailure(Throwable t) {}
        },
        MoreExecutors.directExecutor());
  }

  private static boolean shouldRetryStatus(Status status) {
    return !status.getCode().equals(Code.INVALID_ARGUMENT);
  }

  private static boolean shouldStartNewInvocation(Status status) {
    return status.getCode().equals(Code.FAILED_PRECONDITION);
  }

  private long retrySleepMillis(int attempt) {
    Preconditions.checkArgument(attempt >= 0, "attempt must be nonnegative: %s", attempt);
    // This somewhat matches the backoff used for gRPC connection backoffs.
    return (long)
        (this.buildEventProtocolOptions.besUploadRetryInitialDelay.toMillis()
            * Math.pow(1.6, attempt));
  }

  private DetailedStatusException withFailureDetail(
      StatusException exception, BuildProgress.Code bpCode, String message) {
    return new DetailedStatusException(
        exception, bpCode, message + " " + besClient.userReadableError(exception));
  }

  /** Thrown when encountered problems while uploading build event artifacts. */
  private class LocalFileUploadException extends Exception {
    LocalFileUploadException(Throwable cause) {
      super(cause);
    }
  }

  static class Builder {
    private BuildEventServiceClient besClient;
    private BuildEventArtifactUploader localFileUploader;
    private BuildEventServiceProtoUtil besProtoUtil;
    private BuildEventProtocolOptions bepOptions;
    private boolean publishLifecycleEvents;
    private Sleeper sleeper;
    private Clock clock;
    private ArtifactGroupNamer artifactGroupNamer;
    private EventBus eventBus;
    private Timestamp commandStartTime;

    @CanIgnoreReturnValue
    Builder besClient(BuildEventServiceClient value) {
      this.besClient = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder localFileUploader(BuildEventArtifactUploader value) {
      this.localFileUploader = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder besProtoUtil(BuildEventServiceProtoUtil value) {
      this.besProtoUtil = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder bepOptions(BuildEventProtocolOptions value) {
      this.bepOptions = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder publishLifecycleEvents(boolean value) {
      this.publishLifecycleEvents = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder sleeper(Sleeper value) {
      this.sleeper = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder artifactGroupNamer(ArtifactGroupNamer value) {
      this.artifactGroupNamer = value;
      return this;
    }

    @CanIgnoreReturnValue
    Builder eventBus(EventBus value) {
      this.eventBus = value;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder commandStartTime(Timestamp value) {
      this.commandStartTime = value;
      return this;
    }

    BuildEventServiceUploader build() {
      return new BuildEventServiceUploader(
          checkNotNull(besClient),
          checkNotNull(localFileUploader),
          checkNotNull(besProtoUtil),
          checkNotNull(bepOptions),
          publishLifecycleEvents,
          checkNotNull(sleeper),
          checkNotNull(clock),
          checkNotNull(artifactGroupNamer),
          checkNotNull(eventBus),
          checkNotNull(commandStartTime));
    }
  }

  /**
   * A wrapper Exception class that contains the {@link StatusException}, the {@link
   * BuildProgress.Code}, and a message.
   */
  static class DetailedStatusException extends StatusException {
    private final BuildProgress.Code bpCode;
    private final String extendedMessage;

    DetailedStatusException(
        StatusException statusException, BuildProgress.Code bpCode, String message) {
      super(statusException.getStatus(), statusException.getTrailers());
      this.bpCode = bpCode;
      this.extendedMessage = "The Build Event Protocol upload failed: " + message;
    }
  }
}
