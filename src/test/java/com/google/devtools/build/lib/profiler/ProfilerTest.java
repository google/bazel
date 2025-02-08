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
package com.google.devtools.build.lib.profiler;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.profiler.Profiler.Format.JSON_TRACE_FILE_FORMAT;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.profiler.Profiler.SlowTask;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.worker.WorkerProcessMetrics;
import com.google.devtools.build.lib.worker.WorkerProcessMetricsCollector;
import com.google.devtools.build.lib.worker.WorkerProcessStatus;
import com.google.devtools.build.skyframe.InMemoryGraph;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the profiler. */
@RunWith(JUnit4.class)
public final class ProfilerTest {

  private final Profiler profiler = Profiler.instance();
  private final ManualClock clock = new ManualClock();

  @Before
  public void setManualClock() {
    BlazeClock.setClock(clock);
    profiler.clear();
  }

  @AfterClass
  public static void resetBlazeClock() {
    BlazeClock.setClock(new JavaClock());
  }

  @After
  public void forceStopToAvoidPoisoningTheProfiler() {
    // If a test does not stop the profiler, e.g., due to a test failure, all subsequent tests fail
    // because the profiler is still running, so we force-stop the profiler here.
    try {
      profiler.stop();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static ImmutableSet<ProfilerTask> getAllProfilerTasks() {
    return ImmutableSet.copyOf(ProfilerTask.values());
  }

  private static ImmutableSet<ProfilerTask> getSlowestProfilerTasks() {
    ImmutableSet.Builder<ProfilerTask> profiledTasksBuilder = ImmutableSet.builder();
    for (ProfilerTask profilerTask : ProfilerTask.values()) {
      if (profilerTask.collectsSlowestInstances()) {
        profiledTasksBuilder.add(profilerTask);
      }
    }
    return profiledTasksBuilder.build();
  }

  private ByteArrayOutputStream start(ImmutableSet<ProfilerTask> tasks, Profiler.Format format)
      throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    profiler.start(
        tasks,
        buffer,
        format,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        BlazeClock.instance(),
        BlazeClock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    return buffer;
  }

  private void startUnbuffered(ImmutableSet<ProfilerTask> tasks) throws IOException {
    profiler.start(
        tasks,
        null,
        null,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        BlazeClock.instance(),
        BlazeClock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
  }

  @Test
  public void testProfilerActivation() throws Exception {
    assertThat(profiler.isActive()).isFalse();
    start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);
    assertThat(profiler.isActive()).isTrue();

    profiler.stop();
    assertThat(profiler.isActive()).isFalse();
  }

  @Test
  public void testProfiler() throws Exception {
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);
    profiler.logSimpleTask(BlazeClock.instance().nanoTime(), ProfilerTask.PHASE, "profiler start");
    try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION, "complex task")) {
      profiler.logEvent(ProfilerTask.PHASE, "event1");
      try (SilentCloseable c2 = profiler.profile(ProfilerTask.ACTION_CHECK, "complex subtask")) {
        // next task takes less than 10 ms and should be only aggregated
        profiler.logSimpleTask(BlazeClock.instance().nanoTime(), ProfilerTask.VFS_STAT, "stat1");
        long startTime = BlazeClock.instance().nanoTime();
        clock.advanceMillis(20);
        // this one will take at least 20 ms and should be present
        profiler.logSimpleTask(startTime, ProfilerTask.VFS_STAT, "stat2");
      }
    }
    profiler.stop();
    // all other calls to profiler should be ignored
    profiler.logEvent(ProfilerTask.PHASE, "should be ignored");

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    List<TraceEvent> filteredEvents = removeCounterEvents(jsonProfile.getTraceEvents());
    assertThat(filteredEvents)
        .hasSize(
            2 /* thread names */
                + 2 /* thread indices */
                + 2 /* build phase marker */
                + 1 /* VFS event, the first is too short */
                + 2 /* action + action dependency checking */
                + 1 /* finishing */);

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "thread_name".equals(traceEvent.name()))
                .collect(Collectors.toList()))
        .hasSize(2);

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "thread_sort_index".equals(traceEvent.name()))
                .collect(Collectors.toList()))
        .hasSize(2);

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> ProfilerTask.PHASE.description.equals(traceEvent.category()))
                .collect(Collectors.toList()))
        .hasSize(2);

    TraceEvent vfsStat =
        Iterables.getOnlyElement(
            jsonProfile.getTraceEvents().stream()
                .filter(
                    traceEvent -> ProfilerTask.VFS_STAT.description.equals(traceEvent.category()))
                .collect(Collectors.toList()));
    assertThat(vfsStat.duration()).isEqualTo(Duration.ofMillis(20));

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(
                    traceEvent ->
                        traceEvent.category() != null && traceEvent.category().startsWith("action"))
                .collect(Collectors.toList()))
        .hasSize(2);

    assertThat(Iterables.filter(jsonProfile.getTraceEvents(), t -> t.name().equals("action count")))
        .hasSize(1);

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "Finishing".equals(traceEvent.name()))
                .collect(Collectors.toList()))
        .hasSize(1);
  }

  @Test
  public void testProfilerRecordingAllEvents() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    profiler.start(
        getAllProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        clock,
        clock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    try (SilentCloseable c = profiler.profile(ProfilerTask.ACTION, "action task")) {
      // Next task takes less than 10 ms but should be recorded anyway.
      long before = clock.nanoTime();
      clock.advanceMillis(1);
      profiler.logSimpleTask(before, ProfilerTask.VFS_STAT, "stat1");
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    List<TraceEvent> filteredEvents = removeCounterEvents(jsonProfile.getTraceEvents());
    assertThat(filteredEvents)
        .hasSize(
            2 /* thread names */
                + 2 /* thread sort indices */
                + 1 /* VFS */
                + 1 /* action */
                + 1 /* finishing */);

    TraceEvent vfsStat =
        Iterables.getOnlyElement(
            jsonProfile.getTraceEvents().stream()
                .filter(
                    traceEvent -> ProfilerTask.VFS_STAT.description.equals(traceEvent.category()))
                .collect(Collectors.toList()));
    assertThat(vfsStat.duration().toMillis()).isLessThan(ProfilerTask.VFS_STAT.minDuration);
    // There is only one action count event since we only let the clock run for 1ms.
    assertThat(Iterables.filter(jsonProfile.getTraceEvents(), t -> t.name().equals("action count")))
        .hasSize(1);
  }

  @Test
  public void testProfilerWorkerMetrics() throws Exception {
    if (OS.getCurrent() != OS.LINUX && OS.getCurrent() != OS.DARWIN) {
      // We disable the WorkerMemoryUsageCollector on Windows, so we should skip the test if the
      // current OS is not Linux and Darwin.
      return;
    }
    Instant collectionTime = BlazeClock.instance().now();
    WorkerProcessMetrics workerMetric1 =
        new WorkerProcessMetrics(
            /* workerId= */ 1,
            /* processId= */ 1,
            /* status= */ new WorkerProcessStatus(),
            /* mnemonic= */ "dummy1",
            /* isMultiplex= */ true,
            /* isSandbox= */ true,
            /* workerKeyHash= */ 1);
    workerMetric1.addCollectedMetrics(1024, collectionTime);

    WorkerProcessMetrics workerMetric2 =
        new WorkerProcessMetrics(
            /* workerId= */ 2,
            /* processId= */ 2,
            /* status= */ new WorkerProcessStatus(),
            /* mnemonic= */ "dummy2",
            /* isMultiplex= */ false,
            /* isSandbox= */ false,
            /* workerKeyHash= */ 2);
    workerMetric2.addCollectedMetrics(2048, collectionTime);

    ImmutableList<WorkerProcessMetrics> workerMetrics =
        ImmutableList.of(workerMetric1, workerMetric2);
    WorkerProcessMetricsCollector workerProcessMetricsCollector =
        mock(WorkerProcessMetricsCollector.class);
    var metricsCollected = new CountDownLatch(1);
    when(workerProcessMetricsCollector.getLiveWorkerProcessMetrics())
        .thenAnswer(
            unused -> {
              metricsCollected.countDown();
              return workerMetrics;
            });

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    profiler.start(
        getAllProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        clock,
        clock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            workerProcessMetricsCollector,
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ true,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    metricsCollected.await(10, TimeUnit.SECONDS);
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    ImmutableList<TraceEvent> usageEvents =
        jsonProfile.getTraceEvents().stream()
            .filter(e -> e.name().contains("Workers memory usage"))
            .collect(toImmutableList());
    assertThat(usageEvents).hasSize(1);
  }

  @Test
  public void testProfilerRecordingOnlySlowestEvents() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    profiler.start(
        getSlowestProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        BlazeClock.instance(),
        BlazeClock.instance().nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    profiler.logSimpleTask(10000, 20000, ProfilerTask.VFS_STAT, "stat");
    // Unlike the VFS_STAT event above, the remote execution event will not be recorded since we
    // don't record the slowest remote exec events (see ProfilerTask.java).
    profiler.logSimpleTask(20000, 30000, ProfilerTask.REMOTE_EXECUTION, "remote execution");

    assertThat(profiler.isProfiling(ProfilerTask.VFS_STAT)).isTrue();
    assertThat(profiler.isProfiling(ProfilerTask.REMOTE_EXECUTION)).isFalse();

    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    List<TraceEvent> filteredEvents = removeCounterEvents(jsonProfile.getTraceEvents());
    assertThat(filteredEvents).hasSize(2 /*threads */ + 2 /*threads sort index */ + 1 /*VFS */);

    assertThat(
            filteredEvents.stream()
                .filter(
                    traceEvent ->
                        !"thread_name".equals(traceEvent.name())
                            && !"thread_sort_index".equals(traceEvent.name()))
                .collect(Collectors.toList()))
        .hasSize(1);
  }

  @Test
  public void testSlowestTasks() throws Exception {
    startUnbuffered(getAllProfilerTasks());
    profiler.logSimpleTaskDuration(
        Profiler.nanoTimeMaybe(), Duration.ofSeconds(10), ProfilerTask.LOCAL_PARSE, "foo");
    Iterable<SlowTask> slowestTasks = profiler.getSlowestTasks();
    assertThat(slowestTasks).hasSize(1);
    SlowTask task = slowestTasks.iterator().next();
    assertThat(task.type).isEqualTo(ProfilerTask.LOCAL_PARSE);
    profiler.stop();
  }

  @Test
  public void testGetSlowestTasksCapped() throws Exception {
    startUnbuffered(getSlowestProfilerTasks());

    // Add some fast tasks - these shouldn't show up in the slowest.
    for (int i = 0; i < 30; i++) {
      profiler.logSimpleTask(
          /* startTimeNanos= */ 1,
          /* stopTimeNanos= */ ProfilerTask.VFS_STAT.minDuration + 10,
          ProfilerTask.VFS_STAT,
          "stat");
    }

    // Add some slow tasks we expect to show up in the slowest.
    List<Long> expectedSlowestDurations = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      long fakeDuration = ProfilerTask.VFS_STAT.minDuration + i + 10_000;
      profiler.logSimpleTask(
          /* startTimeNanos= */ 1,
          /* stopTimeNanos= */ fakeDuration + 1,
          ProfilerTask.VFS_STAT,
          "stat");
      expectedSlowestDurations.add(fakeDuration);
    }

    // Sprinkle in a whole bunch of fast tasks from different thread ids - necessary because
    // internally aggregation is sharded across several aggregators, sharded by thread id.
    // It's possible all these threads wind up in the same shard, we'll take our chances.
    ImmutableList.Builder<Thread> threadsBuilder = ImmutableList.builder();
    try {
      for (int i = 0; i < 32; i++) {
        Thread thread =
            new Thread(
                () -> {
                  for (int j = 0; j < 100; j++) {
                    profiler.logSimpleTask(
                        /* startTimeNanos= */ 1,
                        /* stopTimeNanos= */ ProfilerTask.VFS_STAT.minDuration + j + 1,
                        ProfilerTask.VFS_STAT,
                        "stat");
                  }
                });
        threadsBuilder.add(thread);
        thread.start();
      }
    } finally {
      threadsBuilder
          .build()
          .forEach(
              t -> {
                try {
                  t.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                } catch (InterruptedException e) {
                  t.interrupt();
                  // This'll go ahead and interrupt all the others. The thread we just interrupted
                  // is
                  // lightweight enough that it's reasonable to assume it'll exit.
                  Thread.currentThread().interrupt();
                }
              });
    }

    ImmutableList<SlowTask> slowTasks = ImmutableList.copyOf(profiler.getSlowestTasks());
    assertThat(slowTasks).hasSize(30);

    ImmutableList<Long> slowestDurations =
        slowTasks.stream().map(SlowTask::getDurationNanos).collect(toImmutableList());
    assertThat(slowestDurations).containsExactlyElementsIn(expectedSlowestDurations);
  }

  @Test
  public void testProfilerRecordsNothing() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    profiler.start(
        ImmutableSet.of(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        BlazeClock.instance(),
        BlazeClock.instance().nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    profiler.logSimpleTask(10000, 20000, ProfilerTask.VFS_STAT, "stat");

    assertThat(ProfilerTask.VFS_STAT.collectsSlowestInstances()).isTrue();
    assertThat(profiler.isProfiling(ProfilerTask.VFS_STAT)).isFalse();

    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));

    // Filter out thread metadata and counter data and make sure that the remaining list is empty,
    // i.e. there is no individual task recorded.
    List<TraceEvent> traceEvents =
        jsonProfile.getTraceEvents().stream()
            .filter(traceEvent -> !"M".equals(traceEvent.type()) && !"C".equals(traceEvent.type()))
            .collect(toImmutableList());
    assertThat(traceEvents).isEmpty();
  }

  @Test
  public void testConcurrentProfiling() throws Exception {
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);

    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < 10000; i++) {
                Profiler.instance().logEvent(ProfilerTask.INFO, "thread1");
              }
            });
    Thread thread2 =
        new Thread(
            () -> {
              for (int i = 0; i < 10000; i++) {
                Profiler.instance().logEvent(ProfilerTask.INFO, "thread2");
              }
            });

    try (SilentCloseable c = profiler.profile(ProfilerTask.PHASE, "main task")) {
      profiler.logEvent(ProfilerTask.INFO, "starting threads");
      thread1.start();
      thread2.start();
      thread2.join();
      thread1.join();
      profiler.logEvent(ProfilerTask.INFO, "joined");
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    assertThat(removeCounterEvents(jsonProfile.getTraceEvents()))
        .hasSize(
            4 /* thread names */
                + 4 /* thread indices */
                + 1 /* main task phase marker */
                + 2 /* starting, joining events */
                + 2 * 10000 /* thread1/thread2 events */
                + 1 /* finishing */);

    long tid1 =
        jsonProfile.getTraceEvents().stream()
            .filter(traceEvent -> "thread1".equals(traceEvent.name()))
            .map(TraceEvent::threadId)
            .distinct()
            .collect(onlyElement());
    long tid2 =
        jsonProfile.getTraceEvents().stream()
            .filter(traceEvent -> "thread2".equals(traceEvent.name()))
            .map(TraceEvent::threadId)
            .distinct()
            .collect(onlyElement());
    assertThat(tid1).isNotEqualTo(tid2);
    assertThat(tid1).isEqualTo(thread1.getId());
    assertThat(tid2).isEqualTo(thread2.getId());
  }

  @Test
  public void testPhaseTasks() throws Exception {
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);
    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < 100; i++) {
                Profiler.instance().logEvent(ProfilerTask.INFO, "thread1");
              }
            });
    profiler.markPhase(ProfilePhase.INIT); // Empty phase.
    profiler.markPhase(ProfilePhase.TARGET_PATTERN_EVAL);
    thread1.start();
    thread1.join();
    clock.advanceMillis(1);
    profiler.markPhase(ProfilePhase.ANALYZE);
    Thread thread2 =
        new Thread(
            () -> {
              try (SilentCloseable c = profiler.profile(ProfilerTask.INFO, "complex task")) {
                for (int i = 0; i < 100; i++) {
                  Profiler.instance().logEvent(ProfilerTask.INFO, "thread2a");
                }
              }
              try {
                profiler.markPhase(ProfilePhase.EXECUTE);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              for (int i = 0; i < 100; i++) {
                Profiler.instance().logEvent(ProfilerTask.INFO, "thread2b");
              }
            });
    thread2.start();
    thread2.join();
    profiler.logEvent(ProfilerTask.INFO, "last task");
    clock.advanceMillis(1);
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    List<TraceEvent> filteredEvents = removeCounterEvents(jsonProfile.getTraceEvents());
    assertThat(filteredEvents)
        .hasSize(
            4 /* thread names */
                + 4 /* threads sort index */
                + 4 /* build phase marker */
                + 3 * 100 /* thread1, thread2a, thread2b */
                + 1 /* complex task */
                + 1 /* last task */
                + 1 /* finishing */);
    assertThat(getTraceEventsForPhase(ProfilePhase.INIT, filteredEvents)).isEmpty();
    assertThat(getTraceEventsForPhase(ProfilePhase.TARGET_PATTERN_EVAL, filteredEvents))
        .hasSize(100); // thread1
    assertThat(getTraceEventsForPhase(ProfilePhase.ANALYZE, filteredEvents))
        .hasSize(101); // complex task and thread2a
    assertThat(getTraceEventsForPhase(ProfilePhase.EXECUTE, filteredEvents))
        .hasSize(102); // thread2b + last task + finishing
  }

  // Filter out counter series events such as CPU usage/memory usage/load average events. These are
  // non-deterministic depending on the duration of the profile.
  private static ImmutableList<TraceEvent> removeCounterEvents(List<TraceEvent> events) {
    return events.stream().filter(e -> !"C".equals(e.type())).collect(toImmutableList());
  }

  /**
   * Extracts all events for a given phase.
   *
   * <p>Excludes thread_name and thread_sort_index events.
   */
  private static List<TraceEvent> getTraceEventsForPhase(
      ProfilePhase phase, List<TraceEvent> traceEvents) {
    List<TraceEvent> filteredEvents = new ArrayList<>();
    boolean foundPhase = false;
    for (TraceEvent traceEvent : traceEvents) {
      if (ProfilerTask.PHASE.description.equals(traceEvent.category())) {
        if (foundPhase) {
          break;
        } else if (phase.description.equals(traceEvent.name())) {
          foundPhase = true;
          continue;
        }
      }
      if (foundPhase
          && !"thread_name".equals(traceEvent.name())
          && !"thread_sort_index".equals(traceEvent.name())) {
        filteredEvents.add(traceEvent);
      }
    }
    return filteredEvents;
  }

  @Test
  public void testResilenceToNonDecreasingNanoTimes() throws Exception {
    final long initialNanoTime = BlazeClock.instance().nanoTime();
    final AtomicInteger numNanoTimeCalls = new AtomicInteger(0);
    Clock badClock =
        new Clock() {
          @Override
          public long currentTimeMillis() {
            return BlazeClock.instance().currentTimeMillis();
          }

          @Override
          public long nanoTime() {
            return initialNanoTime - numNanoTimeCalls.addAndGet(1);
          }
        };
    profiler.start(
        getAllProfilerTasks(),
        new ByteArrayOutputStream(),
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        badClock,
        initialNanoTime,
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    profiler.logSimpleTask(badClock.nanoTime(), ProfilerTask.INFO, "some task");
    profiler.stop();
  }

  @Test
  public void testTaskHistograms() throws Exception {
    startUnbuffered(getAllProfilerTasks());
    profiler.logSimpleTaskDuration(
        Profiler.nanoTimeMaybe(), Duration.ofSeconds(10), ProfilerTask.INFO, "foo");
    ImmutableList<StatRecorder> histograms = profiler.getTasksHistograms();
    StatRecorder infoStatRecorder = histograms.get(ProfilerTask.INFO.ordinal());
    assertThat(infoStatRecorder.isEmpty()).isFalse();
    // This is the only provided API to get the contents of the StatRecorder.
    assertThat(infoStatRecorder.toString()).contains("'INFO'");
    assertThat(infoStatRecorder.toString()).contains("Count: 1");
    assertThat(infoStatRecorder.toString()).contains("[8192..16384 ms]");
    // The stop() call is here because the histograms are cleared in the stop call. See the
    // documentation of {@link Profiler#getTasksHistograms}.
    profiler.stop();
  }

  @Test
  public void testIOExceptionInOutputStreamBinaryFormat() throws Exception {
    OutputStream failingOutputStream =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("Expected failure.");
          }
        };
    profiler.start(
        getAllProfilerTasks(),
        failingOutputStream,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        BlazeClock.instance(),
        BlazeClock.instance().nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    profiler.logSimpleTaskDuration(
        Profiler.nanoTimeMaybe(), Duration.ofSeconds(10), ProfilerTask.INFO, "foo");
    IOException expected = assertThrows(IOException.class, profiler::stop);
    assertThat(expected).hasMessageThat().isEqualTo("Expected failure.");
  }

  @Test
  public void testIOExceptionInOutputStreamJsonFormat() throws Exception {
    OutputStream failingOutputStream =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("Expected failure.");
          }
        };
    profiler.start(
        getAllProfilerTasks(),
        failingOutputStream,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        BlazeClock.instance(),
        BlazeClock.instance().nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    profiler.logSimpleTaskDuration(
        Profiler.nanoTimeMaybe(), Duration.ofSeconds(10), ProfilerTask.INFO, "foo");
    IOException expected = assertThrows(IOException.class, profiler::stop);
    assertThat(expected).hasMessageThat().isEqualTo("Expected failure.");
  }

  @Test
  public void testPrimaryOutputForAction() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    profiler.start(
        getAllProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        clock,
        clock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ true,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION, /* mnemonic */
            null,
            "test",
            "foo.out",
            "", /* configuration */
            null)) {
      profiler.logEvent(ProfilerTask.PHASE, "event1");
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "foo.out".equals(traceEvent.primaryOutputPath()))
                .collect(Collectors.toList()))
        .hasSize(1);
  }

  @Test
  public void testTargetLabelForAction() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    profiler.start(
        getAllProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        clock,
        clock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ true,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION, /* mnemonic */
            null,
            "test",
            "foo.out",
            "//foo:bar", /* configuration */
            null)) {
      profiler.logEvent(ProfilerTask.PHASE, "event1");
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "//foo:bar".equals(traceEvent.targetLabel()))
                .collect(Collectors.toList()))
        .hasSize(1);
  }

  @Test
  public void testTargetConfigurationForAction() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    profiler.start(
        getAllProfilerTasks(),
        buffer,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        true,
        clock,
        clock.nanoTime(),
        /* slimProfile= */ false,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ true,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION, /* mnemonic */ null, "test", "foo.out", "//foo:bar", "012345")) {
      profiler.logEvent(ProfilerTask.PHASE, "event1");
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));

    assertThat(
            jsonProfile.getTraceEvents().stream()
                .filter(traceEvent -> "012345".equals(traceEvent.configuration()))
                .collect(Collectors.toList()))
        .hasSize(1);
  }

  private ByteArrayOutputStream getJsonProfileOutputStream(boolean slimProfile) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    profiler.start(
        getAllProfilerTasks(),
        outputStream,
        JSON_TRACE_FILE_FORMAT,
        "dummy_output_base",
        UUID.randomUUID(),
        false,
        BlazeClock.instance(),
        BlazeClock.instance().nanoTime(),
        slimProfile,
        /* includePrimaryOutput= */ false,
        /* includeTargetLabel= */ false,
        /* includeConfiguration */ false,
        /* collectTaskHistograms= */ true,
        new CollectLocalResourceUsage(
            BugReporter.defaultInstance(),
            WorkerProcessMetricsCollector.instance(),
            ResourceManager.instance(),
            InMemoryGraph.create(),
            /* collectWorkerDataInProfiler= */ false,
            /* collectLoadAverage= */ false,
            /* collectSystemNetworkUsage= */ false,
            /* collectResourceManagerEstimation= */ false,
            /* collectPressureStallIndicators= */ false,
            /* collectSkyframeCounts= */ false));
    long curTime = Profiler.nanoTimeMaybe();
    for (int i = 0; i < 100_000; i++) {
      Duration duration;
      if (i % 100 == 0) {
        duration = Duration.ofSeconds(1);
      } else {
        duration = Duration.ofMillis(i % 250);
      }
      profiler.logSimpleTaskDuration(curTime, duration, ProfilerTask.INFO, "foo");
      curTime += duration.toNanos();
    }
    profiler.stop();
    return outputStream;
  }

  @Test
  public void testSlimProfileSize() throws Exception {
    ByteArrayOutputStream fatOutputStream = getJsonProfileOutputStream(/* slimProfile= */ false);
    String fatOutput = fatOutputStream.toString();
    assertThat(fatOutput).doesNotContain("merged");

    ByteArrayOutputStream slimOutputStream = getJsonProfileOutputStream(/* slimProfile= */ true);
    String slimOutput = slimOutputStream.toString();
    assertThat(slimOutput).contains("merged");

    long fatProfileLen = fatOutputStream.size();
    long slimProfileLen = slimOutputStream.size();
    assertThat(fatProfileLen).isAtLeast(8 * slimProfileLen);

    long fatProfileLineCount = fatOutput.split("\n").length;
    long slimProfileLineCount = slimOutput.split("\n").length;
    assertThat(fatProfileLineCount).isAtLeast(8 * slimProfileLineCount);
  }

  @Test
  public void testProfileMnemonicIncluded() throws Exception {
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION, /* mnemonic */
            null,
            "without mnemonic",
            "",
            "", /* configuration */
            null)) {
      clock.advanceMillis(100);
    }
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION, "foo", "with mnemonic", "", "", /* configuration */ null)) {
      clock.advanceMillis(100);
    }
    profiler.stop();

    // Make sure both actions were registered
    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    List<TraceEvent> traceEvents = jsonProfile.getTraceEvents();
    List<String> names = traceEvents.stream().map(TraceEvent::name).collect(Collectors.toList());
    assertThat(names).contains("without mnemonic");
    assertThat(names).contains("with mnemonic");

    TraceEvent withoutMnemonic = null;
    TraceEvent withMnemonic = null;
    for (TraceEvent traceEvent : traceEvents) {
      String name = traceEvent.name();
      if (name.equals("without mnemonic")) {
        withoutMnemonic = traceEvent;
      } else if (name.equals("with mnemonic")) {
        withMnemonic = traceEvent;
      }
    }

    // Make sure both events were profiled
    assertThat(withoutMnemonic).isNotNull();
    assertThat(withMnemonic).isNotNull();

    // Make sure that one has been assigned a mnemonics field in args and the other hasn't
    String mnemonicWithoutMnemonic = withoutMnemonic.mnemonic();
    String mnemonicWithMnemonic = withMnemonic.mnemonic();
    assertThat(mnemonicWithoutMnemonic).isNull();
    assertThat(mnemonicWithMnemonic).isNotNull();

    // Make sure the mnemonic has been set to the specified value
    assertThat(mnemonicWithMnemonic).isEqualTo("foo");
  }

  @Test
  public void testActionCacheHitsCounted() throws Exception {
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);
    // The setup here is one action and one action check taking 200ms (the bucket duration),
    // another action check for 300ms, so spilling over in the next bucket.
    try (SilentCloseable c =
        profiler.profileAction(
            ProfilerTask.ACTION_CHECK,
            "bar action",
            "with mnemonic",
            "",
            "", /* configuration */
            null)) {
      try (SilentCloseable c2 =
              profiler.profileAction(
                  ProfilerTask.ACTION, /* mnemonic */
                  null,
                  "foo action",
                  "",
                  "", /* configuration */
                  null);
          SilentCloseable c3 =
              profiler.profileAction(
                  ProfilerTask.ACTION_CHECK, /* mnemonic */
                  null,
                  "bar action",
                  "",
                  "", /* configuration */
                  null)) {
        clock.advanceMillis(200);
      }
      clock.advanceMillis(100);
    }
    profiler.stop();

    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    Object[] actionCountEvents =
        jsonProfile.getTraceEvents().stream()
            .filter(e -> "action count".equals(e.name()))
            .toArray();

    assertThat(actionCountEvents).hasLength(2);

    TraceEvent first = (TraceEvent) actionCountEvents[0];
    assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID);
    assertThat(first.threadId()).isEqualTo(Thread.currentThread().getId());
    // Two cache hit checks and one executed action.
    assertThat(first.args()).containsExactly("action", 1.0, "local action cache", 2.0);

    TraceEvent second = (TraceEvent) actionCountEvents[1];
    assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID);
    assertThat(first.threadId()).isEqualTo(Thread.currentThread().getId());
    // One of the cache hit checks spilled over and used half of the second bucket.
    assertThat(second.args()).containsExactly("action", 0.0, "local action cache", 0.5);
  }

  @Test
  public void testLocalActionsCounted() throws Exception {
    // Given 2 subsequent local actions with length of 200 ms and 100ms
    ByteArrayOutputStream buffer = start(getAllProfilerTasks(), JSON_TRACE_FILE_FORMAT);

    long startTime1 = clock.nanoTime();
    clock.advanceMillis(200);
    profiler.completeTask(startTime1, ProfilerTask.LOCAL_ACTION_COUNTS, "resource usage 1");
    long startTime2 = clock.nanoTime();
    clock.advanceMillis(100);
    profiler.completeTask(startTime2, ProfilerTask.LOCAL_ACTION_COUNTS, "resource usage 2");

    // When profiler builded the profile.
    profiler.stop();

    // Then find 2 records of local actions of length 1 and 0.5 (because size of window is 200 ms)
    JsonProfile jsonProfile = new JsonProfile(new ByteArrayInputStream(buffer.toByteArray()));
    Object[] actionCountEvents =
        jsonProfile.getTraceEvents().stream()
            .filter(e -> e.name().equals("action count (local)"))
            .toArray();

    assertThat(actionCountEvents).hasLength(2);

    TraceEvent first = (TraceEvent) actionCountEvents[0];
    assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID);
    assertThat(first.threadId()).isEqualTo(Thread.currentThread().getId());
    assertThat(first.args()).containsExactly("local action", 1.0);

    TraceEvent second = (TraceEvent) actionCountEvents[1];
    assertThat(first.processId()).isEqualTo(CounterSeriesTraceData.PROCESS_ID);
    assertThat(first.threadId()).isEqualTo(Thread.currentThread().getId());
    assertThat(second.args()).containsExactly("local action", 0.5);
  }
}
