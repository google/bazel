// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.actions.EventReportingArtifacts.ReportedArtifacts;
import com.google.devtools.build.lib.analysis.BuildInfoEvent;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.extra.ExtraAction;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventstream.AbortedEvent;
import com.google.devtools.build.lib.buildeventstream.BuildCompletingEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted.AbortReason;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithConfiguration;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.ChainableEvent;
import com.google.devtools.build.lib.buildeventstream.LastBuildEvent;
import com.google.devtools.build.lib.buildeventstream.NullConfiguration;
import com.google.devtools.build.lib.buildeventstream.ProgressEvent;
import com.google.devtools.build.lib.buildeventstream.transports.BuildEventStreamOptions;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.NoAnalyzeEvent;
import com.google.devtools.build.lib.buildtool.buildevent.NoExecutionEvent;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent;
import com.google.devtools.build.lib.runtime.CountingArtifactGroupNamer.LatchedGroupName;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

/**
 * Streamer in charge of listening to {@link BuildEvent} and post them to each of the {@link
 * BuildEventTransport}.
 */
@ThreadSafe
public class BuildEventStreamer {
  /** Return value for {@link #routeBuildEvent}. */
  private enum RetentionDecision {
    BUFFERED,
    DISCARD,
    POST
  }

  private final Collection<BuildEventTransport> transports;
  private final BuildEventStreamOptions besOptions;
  private final OutputGroupFileModes outputGroupFileModes;
  private final boolean publishTargetSummaries;

  @GuardedBy("this")
  private Set<BuildEventId> announcedEvents;

  @GuardedBy("this")
  private final Set<BuildEventId> postedEvents = new HashSet<>();

  @GuardedBy("this")
  private final Set<BuildEventId> configurationsPosted = new HashSet<>();

  @GuardedBy("this")
  private List<Pair<String, String>> bufferedStdoutStderrPairs = new ArrayList<>();

  // Use LinkedHashMultimap to maintain a FIFO ordering of pending events.
  // This is important in case of Skymeld, so that the TestAttempt events are resolved in the
  // correct order.
  @GuardedBy("this")
  private final SetMultimap<BuildEventId, BuildEvent> pendingEvents = LinkedHashMultimap.create();

  @GuardedBy("this")
  private int progressCount;

  private final CountingArtifactGroupNamer artifactGroupNamer;
  private final String oomMessage;
  private OutErrProvider outErrProvider;

  @GuardedBy("this")
  private final Set<AbortReason> abortReasons = new LinkedHashSet<>();

  // Will be set to true if the build was invoked through "bazel test", "bazel coverage", or
  // "bazel run".
  private boolean isCommandToSkipBuildCompleteEvent;

  // After #buildComplete is called, contains the set of events that the streamer is expected to
  // process. The streamer will fully close after seeing them. This field is null until
  // #buildComplete is called.
  // Thread-safety note: in the final, sequential phase of the build, we ignore any events that are
  // announced by events posted after #buildComplete is called.
  private Set<BuildEventId> finalEventsToCome = null;

  // True, if we already closed the stream.
  @GuardedBy("this")
  private boolean closed;

  /**
   * The current state of buffered progress events.
   *
   * <p>The typical case in which stdout and stderr alternate is output of the form:
   *
   * <pre>
   * INFO: From Executing genrule //:genrule:
   * &lt;genrule stdout output>
   * [123 / 1234] 16 actions running
   * </pre>
   *
   * We want the relative order of the stdout and stderr output to be preserved on a best-effort
   * basis and thus flush the two streams into a progress event before we are seeing a second type
   * transition. We are free to declare either stdout or stderr to come first. We choose stderr here
   * as that provides the more natural split in the example above: The INFO line and the stdout of
   * the action it precedes both end up in the same progress event.
   */
  enum ProgressBufferState {
    ACCEPT_STDERR_AND_STDOUT {
      @Override
      ProgressBufferState nextStateOnStderr() {
        return ACCEPT_STDERR_AND_STDOUT;
      }

      @Override
      ProgressBufferState nextStateOnStdout() {
        return ACCEPT_STDOUT;
      }
    },
    ACCEPT_STDOUT {
      @Override
      ProgressBufferState nextStateOnStderr() {
        return REQUIRE_FLUSH;
      }

      @Override
      ProgressBufferState nextStateOnStdout() {
        return ACCEPT_STDOUT;
      }
    },
    REQUIRE_FLUSH {
      @Override
      ProgressBufferState nextStateOnStderr() {
        return REQUIRE_FLUSH;
      }

      @Override
      ProgressBufferState nextStateOnStdout() {
        return REQUIRE_FLUSH;
      }
    };

    abstract ProgressBufferState nextStateOnStderr();

    abstract ProgressBufferState nextStateOnStdout();
  }

  private final AtomicReference<ProgressBufferState> progressBufferState =
      new AtomicReference<>(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT);

  /** Holds the futures for the closing of each transport */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>> closeFuturesMap =
      ImmutableMap.of();

  /**
   * Holds the half-close futures for the upload of each transport. The completion of the half-close
   * indicates that the client has sent all of the data to the server and is just waiting for
   * acknowledgement. The client must still keep the data buffered locally in case acknowledgement
   * fails.
   */
  private ImmutableMap<BuildEventTransport, ListenableFuture<Void>> halfCloseFuturesMap =
      ImmutableMap.of();

  /** Provider for stdout and stderr output. */
  public interface OutErrProvider {
    /**
     * Return the chunks of stdout that were produced since the last call to this function (or the
     * beginning of the build, for the first call). It is the responsibility of the class
     * implementing this interface to properly synchronize with simultaneously written output.
     */
    Iterable<String> getOut();

    /**
     * Return the chunks of stderr that were produced since the last call to this function (or the
     * beginning of the build, for the first call). It is the responsibility of the class
     * implementing this interface to properly synchronize with simultaneously written output.
     */
    Iterable<String> getErr();
  }

  /** Creates a new build event streamer. */
  private BuildEventStreamer(
      Collection<BuildEventTransport> transports,
      BuildEventStreamOptions options,
      OutputGroupFileModes outputGroupFileModes,
      boolean publishTargetSummaries,
      CountingArtifactGroupNamer artifactGroupNamer,
      String oomMessage) {
    this.transports = transports;
    this.besOptions = options;
    this.outputGroupFileModes = outputGroupFileModes;
    this.publishTargetSummaries = publishTargetSummaries;
    this.announcedEvents = null;
    this.progressCount = 0;
    this.artifactGroupNamer = artifactGroupNamer;
    this.oomMessage = oomMessage;
  }

  @ThreadCompatible
  public void registerOutErrProvider(OutErrProvider outErrProvider) {
    this.outErrProvider = outErrProvider;
  }

  // This exists to nop out the announcement of new events after #buildComplete
  private synchronized void maybeRegisterAnnouncedEvent(BuildEventId id) {
    if (finalEventsToCome != null) {
      return;
    }

    announcedEvents.add(id);
  }

  // This exists to nop out the announcement of new events after #buildComplete
  private synchronized void maybeRegisterAnnouncedEvents(Collection<BuildEventId> ids) {
    if (finalEventsToCome != null) {
      return;
    }

    announcedEvents.addAll(ids);
  }

  /**
   * Post a new event to all transports; simultaneously keep track of the events we announce to
   * still come.
   *
   * <p>Moreover, link unannounced events to the progress stream; we only expect failure events to
   * come before their parents.
   */
  // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
  // will be running under the synchronized block.
  @SuppressWarnings("GuardedBy")
  private void post(BuildEvent event) {
    List<BuildEvent> linkEvents = null;
    BuildEventId id = event.getEventId();
    List<BuildEvent> flushEvents = null;
    boolean lastEvent = false;

    synchronized (this) {
      if (announcedEvents == null) {
        announcedEvents = new HashSet<>();
        // The very first event of a stream is implicitly announced by the convention that
        // a complete stream has to have at least one entry. In this way we keep the invariant
        // that the set of posted events is always a subset of the set of announced events.
        maybeRegisterAnnouncedEvent(id);
        if (!event.getChildrenEvents().contains(ProgressEvent.INITIAL_PROGRESS_UPDATE)) {
          BuildEvent progress = ProgressEvent.progressChainIn(progressCount, event.getEventId());
          linkEvents = ImmutableList.of(progress);
          progressCount++;
          maybeRegisterAnnouncedEvents(progress.getChildrenEvents());
          // the new first event in the stream, implicitly announced by the fact that complete
          // stream may not be empty.
          maybeRegisterAnnouncedEvent(progress.getEventId());
          postedEvents.add(progress.getEventId());
        }

        if (!bufferedStdoutStderrPairs.isEmpty()) {
          flushEvents = new ArrayList<>(bufferedStdoutStderrPairs.size());
          for (Pair<String, String> outErrPair : bufferedStdoutStderrPairs) {
            flushEvents.add(flushStdoutStderrEvent(outErrPair.getFirst(), outErrPair.getSecond()));
          }
        }
        bufferedStdoutStderrPairs = null;
      } else {
        if (!announcedEvents.contains(id)) {
          Iterable<String> allOut = ImmutableList.of();
          Iterable<String> allErr = ImmutableList.of();
          if (outErrProvider != null) {
            allOut = orEmpty(outErrProvider.getOut());
            allErr = orEmpty(outErrProvider.getErr());
            progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT);
          }
          linkEvents = new ArrayList<>();
          List<BuildEvent> finalLinkEvents = linkEvents;
          consumeAsPairsofStrings(
              allOut,
              allErr,
              (out, err) -> {
                BuildEvent progressEvent =
                    ProgressEvent.progressChainIn(progressCount, id, out, err);
                finalLinkEvents.add(progressEvent);
                progressCount++;
                maybeRegisterAnnouncedEvents(progressEvent.getChildrenEvents());
                postedEvents.add(progressEvent.getEventId());
              });
        }
      }

      if (event instanceof BuildInfoEvent) {
        // The specification for BuildInfoEvent says that there may be many such events,
        // but all except the first one should be ignored.
        if (postedEvents.contains(id)) {
          return;
        }
      }

      postedEvents.add(id);
      maybeRegisterAnnouncedEvents(event.getChildrenEvents());
      // We keep as an invariant that postedEvents is a subset of announced events, so this is a
      // cheaper test for equality
      if (announcedEvents.size() == postedEvents.size()) {
        lastEvent = true;
      }
    }

    BuildEvent mainEvent = event;
    if (lastEvent) {
      mainEvent = new LastBuildEvent(event);
    }

    for (BuildEventTransport transport : transports) {
      if (linkEvents != null) {
        for (BuildEvent linkEvent : linkEvents) {
          transport.sendBuildEvent(linkEvent);
        }
      }
      transport.sendBuildEvent(mainEvent);
    }

    if (flushEvents != null) {
      for (BuildEvent flushEvent : flushEvents) {
        for (BuildEventTransport transport : transports) {
          transport.sendBuildEvent(flushEvent);
        }
      }
    }
  }

  /**
   * If some events are blocked on the absence of a build_started event, generate such an event;
   * moreover, make that artificial start event announce all events blocked on it, as well as the
   * {@link BuildCompletingEvent} that caused the early end of the stream.
   */
  private synchronized void clearMissingStartEvent(BuildEventId id) {
    if (pendingEvents.containsKey(BuildEventIdUtil.buildStartedId())) {
      ImmutableSet.Builder<BuildEventId> children = ImmutableSet.builder();
      children.add(ProgressEvent.INITIAL_PROGRESS_UPDATE);
      children.add(id);
      children.addAll(
          pendingEvents.get(BuildEventIdUtil.buildStartedId()).stream()
              .map(BuildEvent::getEventId)
              .collect(ImmutableSet.toImmutableSet()));
      buildEvent(
          new AbortedEvent(
              BuildEventIdUtil.buildStartedId(),
              children.build(),
              getLastAbortReason(),
              getAbortReasonDetails()));
    }
  }

  /** Clear pending events by generating aborted events for all their requisits. */
  private synchronized void clearPendingEvents() {
    while (!pendingEvents.isEmpty()) {
      BuildEventId id = pendingEvents.keySet().iterator().next();
      buildEvent(new AbortedEvent(id, getLastAbortReason(), getAbortReasonDetails()));
    }
  }

  /**
   * Clear all events that are still announced; events not naturally closed by the expected event
   * normally only occur if the build is aborted.
   */
  private synchronized void clearAnnouncedEvents(Collection<BuildEventId> dontclear) {
    if (announcedEvents != null) {
      // create a copy of the identifiers to clear, as the post method
      // will change the set of already announced events.
      Set<BuildEventId> ids;
      synchronized (this) {
        ids = Sets.difference(announcedEvents, postedEvents);
      }
      for (BuildEventId id : ids) {
        if (!dontclear.contains(id)) {
          post(new AbortedEvent(id, getLastAbortReason(), getAbortReasonDetails()));
        }
      }
    }
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  public void closeOnAbort(AbortReason reason) {
    close(checkNotNull(reason));
  }

  public void close() {
    close(/* reason= */ null);
  }

  private synchronized void close(@Nullable AbortReason reason) {
    if (closed) {
      return;
    }
    closed = true;
    if (reason != null) {
      addAbortReason(reason);
    }

    if (finalEventsToCome == null) {
      // This should only happen if there's a crash. Try to clean up as best we can.
      clearEventsAndPostFinalProgress(null);
    }

    ImmutableMap.Builder<BuildEventTransport, ListenableFuture<Void>> closeFuturesMapBuilder =
        ImmutableMap.builder();
    for (BuildEventTransport transport : transports) {
      closeFuturesMapBuilder.put(transport, transport.close());
    }
    closeFuturesMap = closeFuturesMapBuilder.buildOrThrow();

    ImmutableMap.Builder<BuildEventTransport, ListenableFuture<Void>> halfCloseFuturesMapBuilder =
        ImmutableMap.builder();
    for (BuildEventTransport transport : transports) {
      halfCloseFuturesMapBuilder.put(transport, transport.getHalfCloseFuture());
    }
    halfCloseFuturesMap = halfCloseFuturesMapBuilder.buildOrThrow();
  }

  private void maybeReportArtifactSet(CompletionContext ctx, NestedSet<?> set) {
    try (LatchedGroupName lockedName = artifactGroupNamer.maybeName(set)) {
      if (lockedName == null) {
        return;
      }
      set = NamedArtifactGroup.expandSet(ctx, set);
      // Invariant: all leaf successors ("direct elements") of set are ExpandedArtifacts.

      // We only split if the max number of entries is at least 2 (it must be at least a binary
      // tree). The method throws for smaller values.
      if (besOptions.maxNamedSetEntries >= 2) {
        // We only split the event after naming it to avoid splitting the same node multiple times.
        // Note that the artifactGroupNames keeps references to the individual pieces, so this can
        // double the memory consumption of large nested sets.
        set = set.splitIfExceedsMaximumSize(besOptions.maxNamedSetEntries);
      }

      for (NestedSet<?> succ : set.getNonLeaves()) {
        maybeReportArtifactSet(ctx, succ);
      }
      post(new NamedArtifactGroup(lockedName.getName(), ctx, set));
    }
  }

  private void maybeReportConfiguration(@Nullable BuildEvent configuration) {
    BuildEvent event = configuration == null ? NullConfiguration.INSTANCE : configuration;
    BuildEventId id = event.getEventId();
    synchronized (this) {
      if (configurationsPosted.add(id)) {
        post(event);
      }
    }
  }

  @Subscribe
  public void buildInterrupted(BuildInterruptedEvent event) {
    addAbortReason(AbortReason.USER_INTERRUPTED);
  }

  @Subscribe
  public void noAnalyze(NoAnalyzeEvent event) {
    addAbortReason(AbortReason.NO_ANALYZE);
  }

  @Subscribe
  public void noExecution(NoExecutionEvent event) {
    addAbortReason(AbortReason.NO_BUILD);
  }

  @Subscribe
  @AllowConcurrentEvents
  public void buildEvent(BuildEvent event) {
    if (finalEventsToCome != null) {
      synchronized (this) {
        BuildEventId id = event.getEventId();
        if (finalEventsToCome.contains(id)) {
          finalEventsToCome.remove(id);
        } else {
          return;
        }
      }
    }

    switch (routeBuildEvent(event)) {
      case DISCARD:
        // Check if there are pending events waiting on this event
        maybePostPendingEventsBeforeDiscarding(event);
        return; // bail: we're dropping this event
      case BUFFERED:
        // Bail: the event was buffered and the BuildEventStreamer is now responsible for eventually
        // posting (or discarding) it
        return;
      case POST:
        break; // proceed
    }

    if (event instanceof BuildStartingEvent buildStartingEvent) {
      BuildRequest buildRequest = buildStartingEvent.request();
      isCommandToSkipBuildCompleteEvent =
          buildRequest.getCommandName().equals("test")
              || buildRequest.getCommandName().equals("coverage")
              || buildRequest.getCommandName().equals("run");
    }

    if (event instanceof BuildEventWithConfiguration buildEventWithConfiguration) {
      for (BuildEvent configuration : buildEventWithConfiguration.getConfigurations()) {
        maybeReportConfiguration(configuration);
      }
    }

    if (event instanceof EventReportingArtifacts eventReportingArtifacts) {
      ReportedArtifacts reportedArtifacts =
          eventReportingArtifacts.reportedArtifacts(outputGroupFileModes);
      for (NestedSet<Artifact> artifactSet : reportedArtifacts.artifacts) {
        maybeReportArtifactSet(reportedArtifacts.completionContext, artifactSet);
      }
    }

    if (event instanceof BuildCompletingEvent
        && !event.getEventId().equals(BuildEventIdUtil.buildStartedId())) {
      clearMissingStartEvent(event.getEventId());
    }

    if (event instanceof BuildConfigurationEvent) {
      maybeReportConfiguration(event);
    } else {
      post(event);
    }

    // Reconsider all events blocked by the event just posted.
    Set<BuildEvent> blockedEventsFifo;
    synchronized (this) {
      blockedEventsFifo = pendingEvents.removeAll(event.getEventId());
    }
    for (BuildEvent freedEvent : blockedEventsFifo) {
      buildEvent(freedEvent);
    }

    // Special-case handling for subclasses of `BuildCompletingEvent`.
    //
    // For most commands, exactly one `BuildCompletingEvent` will be posted to the EventBus. If the
    // command is "run" or "test", a non-crashing/catastrophic `BuildCompleteEvent` will be followed
    // by a RunBuildCompleteEvent/TestingCompleteEvent.
    if (event instanceof BuildCompleteEvent buildCompleteEvent) {
      if (isCrash(buildCompleteEvent) || isCatastrophe(buildCompleteEvent)) {
        if (isOom(buildCompleteEvent)) {
          addAbortReason(AbortReason.OUT_OF_MEMORY);
        } else {
          addAbortReason(AbortReason.INTERNAL);
        }
      } else if (isIncomplete(buildCompleteEvent)) {
        addAbortReason(AbortReason.INCOMPLETE);
      }
    }

    if (event instanceof BuildCompletingEvent) {
      buildComplete(event);
    }

    if (event instanceof NoBuildEvent noBuildEvent) {
      if (!noBuildEvent.separateFinishedEvent()) {
        buildComplete(event);
      }
    }

    if (finalEventsToCome != null && finalEventsToCome.isEmpty()) {
      close();
    }
  }

  private static boolean isCrash(BuildCompleteEvent event) {
    return event.getResult().getUnhandledThrowable() != null || isOom(event);
  }

  private static boolean isCatastrophe(BuildCompleteEvent event) {
    return event.getResult().wasCatastrophe();
  }

  private static boolean isIncomplete(BuildCompleteEvent event) {
    return !event.getResult().getSuccess()
        && !event.getResult().wasCatastrophe()
        && event.getResult().getStopOnFirstFailure();
  }

  private static boolean isOom(BuildCompleteEvent event) {
    return event.getResult().getDetailedExitCode().getExitCode().equals(ExitCode.OOM_ERROR);
  }

  /**
   * Given an event that will be discarded (not buffered), publishes any events waiting on the given
   * event.
   *
   * @param event event that is being discarded (not buffered)
   */
  private void maybePostPendingEventsBeforeDiscarding(BuildEvent event) {
    if (publishTargetSummaries && isVacuousTestSummary(event)) {
      // Target summaries should "post after" test summaries, but we can't a priori know whether
      // test summaries will be vacuous (as that depends on test execution progress). So check for
      // and publish any pending (target summary) events here. If we don't do this then
      // clearPendingEvents() will publish "aborted" test_summary events for the very events we're
      // discarding here (b/184580877), followed by the pending target_summary events, which is not
      // only confusing but also delays target_summary events until the end of the build.
      //
      // Technically it seems we should do this with all events we're dropping but that would be
      // a lot of extra locking e.g. for every ActionExecutedEvent and it's only necessary to
      // check for this where events are configured to "post after" events that may be discarded.
      BuildEventId eventId = event.getEventId();
      Set<BuildEvent> blockedEventsFifo;
      synchronized (this) {
        blockedEventsFifo = pendingEvents.removeAll(eventId);
        // Pretend we posted this event so a target summary arriving after this test summary (which
        // is common) doesn't get erroneously buffered in bufferUntilPrerequisitesReceived().
        postedEvents.add(eventId);
      }
      for (BuildEvent freedEvent : blockedEventsFifo) {
        buildEvent(freedEvent);
      }
    }
  }

  private synchronized BuildEvent flushStdoutStderrEvent(String out, String err) {
    BuildEvent updateEvent = ProgressEvent.progressUpdate(progressCount, out, err);
    progressCount++;
    maybeRegisterAnnouncedEvents(updateEvent.getChildrenEvents());
    postedEvents.add(updateEvent.getEventId());
    return updateEvent;
  }

  /** Whether the given output type can be written without first flushing the streamer. */
  boolean canBufferProgressWrite(boolean isStderr) {
    var newState =
        progressBufferState.updateAndGet(
            isStderr
                ? ProgressBufferState::nextStateOnStderr
                : ProgressBufferState::nextStateOnStdout);
    return newState != ProgressBufferState.REQUIRE_FLUSH;
  }

  // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
  // will be running under the synchronized block.
  @SuppressWarnings("GuardedBy")
  void flush() {
    List<BuildEvent> updateEvents = null;
    synchronized (this) {
      Iterable<String> allOut = ImmutableList.of();
      Iterable<String> allErr = ImmutableList.of();
      if (outErrProvider != null) {
        allOut = orEmpty(outErrProvider.getOut());
        allErr = orEmpty(outErrProvider.getErr());
        progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT);
      }
      if (Iterables.isEmpty(allOut) && Iterables.isEmpty(allErr)) {
        // Nothing to flush; avoid generating an unneeded progress event.
        return;
      }
      if (finalEventsToCome != null) {
        // If we've already announced the final events, we cannot add more progress events. Stdout
        // and stderr are truncated from the event log.
        consumeAsPairsofStrings(allOut, allErr, (s1, s2) -> {});
      } else if (announcedEvents != null) {
        updateEvents = new ArrayList<>();
        List<BuildEvent> finalUpdateEvents = updateEvents;
        consumeAsPairsofStrings(
            allOut, allErr, (s1, s2) -> finalUpdateEvents.add(flushStdoutStderrEvent(s1, s2)));
      } else {
        consumeAsPairsofStrings(
            allOut, allErr, (s1, s2) -> bufferedStdoutStderrPairs.add(Pair.of(s1, s2)));
      }
    }
    if (updateEvents != null) {
      for (BuildEvent updateEvent : updateEvents) {
        for (BuildEventTransport transport : transports) {
          transport.sendBuildEvent(updateEvent);
        }
      }
    }
  }

  // Returns the given Iterable, or an empty list if null.
  private static <T> Iterable<T> orEmpty(Iterable<T> original) {
    return original == null ? ImmutableList.of() : original;
  }

  // Given a pair of iterables and {@link BiConsumer}s, emit a sequence of pairs to the consumers.
  // Given the leftIterables [L1, L2, ... LN], and the rightIterable [R1, R2, ... RM], the consumers
  // will see this sequence of calls:
  //  biConsumer.accept(L1, null);
  //  biConsumer.accept(L2, null);
  //  ....
  //  biConsumer.accept(L(N-1), null);
  //  biConsumer.accept(LN, R1);
  //  biConsumer.accept(null, R2);
  //  ...
  //  biConsumer.accept(null, R(M-1);
  //  lastConsumer.accept(null, RM);
  //
  // The lastConsumer is always called exactly once, even if both Iterables are empty.
  @VisibleForTesting
  static <T> void consumeAsPairs(
      Iterable<T> leftIterable,
      Iterable<T> rightIterable,
      BiConsumer<T, T> biConsumer,
      BiConsumer<T, T> lastConsumer) {
    if (Iterables.isEmpty(leftIterable) && Iterables.isEmpty(rightIterable)) {
      lastConsumer.accept(null, null);
      return;
    }

    Iterator<T> leftIterator = leftIterable.iterator();
    Iterator<T> rightIterator = rightIterable.iterator();
    while (leftIterator.hasNext()) {
      T left = leftIterator.next();
      boolean lastT = !leftIterator.hasNext();
      T right = (lastT && rightIterator.hasNext()) ? rightIterator.next() : null;
      boolean lastItem = lastT && !rightIterator.hasNext();
      (lastItem ? lastConsumer : biConsumer).accept(left, right);
    }

    while (rightIterator.hasNext()) {
      T right = rightIterator.next();
      (!rightIterator.hasNext() ? lastConsumer : biConsumer).accept(null, right);
    }
  }

  private static void consumeAsPairsofStrings(
      Iterable<String> leftIterable,
      Iterable<String> rightIterable,
      BiConsumer<String, String> biConsumer,
      BiConsumer<String, String> lastConsumer) {
    consumeAsPairs(
        leftIterable,
        rightIterable,
        (s1, s2) -> biConsumer.accept(nullToEmpty(s1), nullToEmpty(s2)),
        (s1, s2) -> lastConsumer.accept(nullToEmpty(s1), nullToEmpty(s2)));
  }

  private static void consumeAsPairsofStrings(
      Iterable<String> leftIterable,
      Iterable<String> rightIterable,
      BiConsumer<String, String> biConsumer) {
    consumeAsPairsofStrings(leftIterable, rightIterable, biConsumer, biConsumer);
  }

  // @GuardedBy annotation is doing lexical analysis that doesn't understand the closures below
  // will be running under the synchronized block.
  @SuppressWarnings("GuardedBy")
  private synchronized void clearEventsAndPostFinalProgress(ChainableEvent event) {
    clearPendingEvents();
    Iterable<String> allOut = ImmutableList.of();
    Iterable<String> allErr = ImmutableList.of();
    if (outErrProvider != null) {
      allOut = orEmpty(outErrProvider.getOut());
      allErr = orEmpty(outErrProvider.getErr());
      progressBufferState.set(ProgressBufferState.ACCEPT_STDERR_AND_STDOUT);
    }
    consumeAsPairsofStrings(
        allOut,
        allErr,
        (s1, s2) -> post(flushStdoutStderrEvent(s1, s2)),
        (s1, s2) -> post(ProgressEvent.finalProgressUpdate(progressCount++, s1, s2)));
    clearAnnouncedEvents(event == null ? ImmutableList.of() : event.getChildrenEvents());
  }

  private synchronized void buildComplete(ChainableEvent event) {
    clearEventsAndPostFinalProgress(event);

    finalEventsToCome = new HashSet<>(announcedEvents);
    finalEventsToCome.removeAll(postedEvents);
    if (finalEventsToCome.isEmpty()) {
      close();
    }
  }

  /** Returns whether a {@link BuildEvent} should be ignored or was buffered. */
  private RetentionDecision routeBuildEvent(BuildEvent event) {
    if (event instanceof ActionExecutedEvent actionExecutedEvent
        && !shouldPublishActionExecutedEvent(actionExecutedEvent)) {
      return RetentionDecision.DISCARD;
    }

    if (bufferUntilPrerequisitesReceived(event)) {
      return RetentionDecision.BUFFERED;
    }

    if (isVacuousTestSummary(event)) {
      return RetentionDecision.DISCARD;
    }

    if (isCommandToSkipBuildCompleteEvent
        && event instanceof BuildCompleteEvent buildCompleteEvent) {
      // In case of "bazel test" or "bazel run" ignore the BuildCompleteEvent, as it will be
      // followed by a TestingCompleteEvent (or RunBuildCompleteEvent) that contains the correct
      // exit code.
      return isCrash(buildCompleteEvent) ? RetentionDecision.POST : RetentionDecision.DISCARD;
    }

    if (event instanceof TargetParsingCompleteEvent parsingCompleteEvent) {
      // If there is only one pattern and we have one failed pattern, then we already posted a
      // pattern expanded error, so we don't post the completion event.
      // TODO(b/109727414): This is brittle. It would be better to always post one PatternExpanded
      // event for each pattern given on the command line instead of one event for all of them
      // combined.
      boolean discard =
          parsingCompleteEvent.getOriginalTargetPattern().size() == 1
              && !parsingCompleteEvent.getFailedTargetPatterns().isEmpty();
      return discard ? RetentionDecision.DISCARD : RetentionDecision.POST;
    }

    return RetentionDecision.POST;
  }

  /** Returns whether an {@link ActionExecutedEvent} should be published. */
  private boolean shouldPublishActionExecutedEvent(ActionExecutedEvent event) {
    if (besOptions.publishAllActions) {
      return true;
    }
    if (event.getException() != null) {
      // Publish failed actions
      return true;
    }
    return event.getAction() instanceof ExtraAction;
  }

  private synchronized boolean bufferUntilPrerequisitesReceived(BuildEvent event) {
    if (!(event instanceof BuildEventWithOrderConstraint buildEventWithOrderConstraint)) {
      return false;
    }
    // Check if all prerequisite events are posted already.
    for (BuildEventId prerequisiteId : buildEventWithOrderConstraint.postedAfter()) {
      if (!postedEvents.contains(prerequisiteId)) {
        pendingEvents.put(prerequisiteId, event);
        return true;
      }
    }
    return false;
  }

  /** Return true if the test summary contains no actual test runs. */
  private static boolean isVacuousTestSummary(BuildEvent event) {
    return event instanceof TestSummary testSummary && testSummary.totalRuns() == 0;
  }

  /**
   * Returns the map from BEP transports to their corresponding closing future.
   *
   * <p>If this method is called before calling {@link #close()} then it will return an empty map.
   */
  public synchronized ImmutableMap<BuildEventTransport, ListenableFuture<Void>>
      getCloseFuturesMap() {
    return closeFuturesMap;
  }

  /**
   * Returns the map from BEP transports to their corresponding half-close futures.
   *
   * <p>Half-close indicates that all client-side data is transmitted but still waiting on
   * server-side acknowledgement. The client must buffer the information in case the server fails to
   * acknowledge.
   *
   * <p>If this method is called before calling {@link #close()} then it will return an empty map.
   */
  public synchronized ImmutableMap<BuildEventTransport, ListenableFuture<Void>> getHalfClosedMap() {
    return halfCloseFuturesMap;
  }

  /**
   * Stores the {@link AbortReason} for later reporting on BEP pending events.
   *
   * <p>In case of multiple abort reasons:
   *
   * <ul>
   *   <li>Only the most recent reason will be reported as the main {@link AbortReason} in BEP.
   *   <li>All previous reasons will appear in the {@link Aborted#getDescription} message.
   * </ul>
   */
  private synchronized void addAbortReason(AbortReason reason) {
    abortReasons.add(reason);
  }

  /**
   * Returns the most recent {@link AbortReason} or {@link AbortReason#UNKNOWN} if no reason was
   * set.
   */
  private synchronized AbortReason getLastAbortReason() {
    return Iterables.getLast(abortReasons, AbortReason.UNKNOWN);
  }

  /**
   * Returns a detailed message explaining the most recent {@link AbortReason} (and possibly
   * previous reasons).
   */
  private synchronized String getAbortReasonDetails() {
    if (abortReasons.size() == 1
        && Iterables.getOnlyElement(abortReasons) == AbortReason.OUT_OF_MEMORY) {
      return BugReport.constructOomExitMessage(oomMessage);
    }
    return abortReasons.size() > 1 ? "Multiple abort reasons reported: " + abortReasons : "";
  }

  /** A builder for {@link BuildEventStreamer}. */
  public static final class Builder {
    private Set<BuildEventTransport> buildEventTransports;
    private BuildEventStreamOptions besStreamOptions;
    private OutputGroupFileModes outputGroupFileModes = OutputGroupFileModes.DEFAULT;
    private boolean publishTargetSummaries;
    private CountingArtifactGroupNamer artifactGroupNamer;
    private String oomMessage;

    @CanIgnoreReturnValue
    public Builder buildEventTransports(Set<BuildEventTransport> value) {
      this.buildEventTransports = value;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder besStreamOptions(BuildEventStreamOptions value) {
      this.besStreamOptions = value;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputGroupFileModes(OutputGroupFileModes outputGroupFileModes) {
      this.outputGroupFileModes = outputGroupFileModes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder publishTargetSummaries(boolean publishTargetSummaries) {
      this.publishTargetSummaries = publishTargetSummaries;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder artifactGroupNamer(CountingArtifactGroupNamer value) {
      this.artifactGroupNamer = value;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder oomMessage(String oomMessage) {
      this.oomMessage = oomMessage;
      return this;
    }

    public BuildEventStreamer build() {
      return new BuildEventStreamer(
          checkNotNull(buildEventTransports),
          checkNotNull(besStreamOptions),
          outputGroupFileModes,
          publishTargetSummaries,
          checkNotNull(artifactGroupNamer),
          nullToEmpty(oomMessage));
    }
  }
}
