// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.collect.Extrema;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.PredicateBasedStatRecorder.RecorderAndPredicate;
import com.google.devtools.build.lib.profiler.StatRecorder.VfsHeuristics;
import com.google.gson.stream.JsonWriter;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

/**
 * Blaze internal profiler. Provides facility to report various Blaze tasks and store them
 * (asynchronously) in the file for future analysis.
 *
 * <p>Implemented as singleton so any caller should use Profiler.instance() to obtain reference.
 *
 * <p>Internally, profiler uses two data structures - ThreadLocal task stack to track nested tasks
 * and single ConcurrentLinkedQueue to gather all completed tasks.
 *
 * <p>Also, due to the nature of the provided functionality (instrumentation of all Blaze
 * components), build.lib.profiler package will be used by almost every other Blaze package, so
 * special attention should be paid to avoid any dependencies on the rest of the Blaze code,
 * including build.lib.util and build.lib.vfs. This is important because build.lib.util and
 * build.lib.vfs contain Profiler invocations and any dependency on those two packages would create
 * circular relationship.
 *
 * <p>
 *
 * @see ProfilerTask enum for recognized task types.
 */
@ThreadSafe
@SuppressWarnings("GoodTime") // This code is very performance sensitive.
public final class Profiler {
  /** The profiler (a static singleton instance). Inactive by default. */
  private static final Profiler instance = new Profiler();

  private static final int HISTOGRAM_BUCKETS = 20;

  private static final Duration ACTION_COUNT_BUCKET_DURATION = Duration.ofMillis(200);

  /** File format enum. */
  public enum Format {
    JSON_TRACE_FILE_FORMAT,
    JSON_TRACE_FILE_COMPRESSED_FORMAT
  }

  /** A task that was very slow. */
  public static final class SlowTask implements Comparable<SlowTask> {
    final long durationNanos;
    final String description;
    final ProfilerTask type;

    private SlowTask(TaskData taskData) {
      this.durationNanos = taskData.durationNanos;
      this.description = taskData.description;
      this.type = taskData.type;
    }

    @Override
    public int compareTo(SlowTask other) {
      long delta = durationNanos - other.durationNanos;
      if (delta < 0) { // Very clumsy
        return -1;
      } else if (delta > 0) {
        return 1;
      } else {
        return 0;
      }
    }

    public long getDurationNanos() {
      return durationNanos;
    }

    public String getDescription() {
      return description;
    }

    public ProfilerTask getType() {
      return type;
    }
  }

  /**
   * Container for the single task record.
   *
   * <p>Class itself is not thread safe, but all access to it from Profiler methods is.
   */
  @ThreadCompatible
  static class TaskData implements TraceData {
    final long threadId;
    final long startTimeNanos;
    final ProfilerTask type;
    final String description;

    long durationNanos;

    TaskData(
        long threadId,
        long startTimeNanos,
        long durationNanos,
        ProfilerTask eventType,
        String description) {
      this.threadId = threadId;
      this.startTimeNanos = startTimeNanos;
      this.durationNanos = durationNanos;
      this.type = eventType;
      this.description = checkNotNull(description);
    }

    TaskData(long threadId, long startTimeNanos, ProfilerTask eventType, String description) {
      this(threadId, startTimeNanos, /* durationNanos= */ -1, eventType, description);
    }

    TaskData(long threadId, long startTimeNanos, long durationNanos, String description) {
      this.type = ProfilerTask.UNKNOWN;
      this.threadId = threadId;
      this.startTimeNanos = startTimeNanos;
      this.durationNanos = durationNanos;
      this.description = description;
    }

    @Override
    public String toString() {
      return "Thread " + threadId + ", type " + type + ", " + description;
    }

    @Override
    public void writeTraceData(JsonWriter jsonWriter, long profileStartTimeNanos)
        throws IOException {
      String eventType = durationNanos == 0 ? "i" : "X";
      jsonWriter.setIndent("  ");
      jsonWriter.beginObject();
      jsonWriter.setIndent("");
      if (type == null) {
        jsonWriter.setIndent("    ");
      } else {
        jsonWriter.name("cat").value(type.description);
      }
      jsonWriter.name("name").value(description);
      jsonWriter.name("ph").value(eventType);
      jsonWriter
          .name("ts")
          .value(TimeUnit.NANOSECONDS.toMicros(startTimeNanos - profileStartTimeNanos));
      if (durationNanos != 0) {
        jsonWriter.name("dur").value(TimeUnit.NANOSECONDS.toMicros(durationNanos));
      }
      jsonWriter.name("pid").value(1);

      if (this instanceof ActionTaskData actionTaskData) {
        if (actionTaskData.primaryOutputPath != null) {
          // Primary outputs are non-mergeable, thus incompatible with slim profiles.
          jsonWriter.name("out").value(actionTaskData.primaryOutputPath);
        }
        if (actionTaskData.targetLabel != null
            || actionTaskData.mnemonic != null
            || actionTaskData.configuration != null) {
          jsonWriter.name("args");
          jsonWriter.beginObject();
          if (actionTaskData.targetLabel != null) {
            jsonWriter.name("target").value(actionTaskData.targetLabel);
          }
          if (actionTaskData.mnemonic != null) {
            jsonWriter.name("mnemonic").value(actionTaskData.mnemonic);
          }
          if (actionTaskData.configuration != null) {
            jsonWriter.name("configuration").value(actionTaskData.configuration);
          }
          jsonWriter.endObject();
        }
      }
      if (type == ProfilerTask.CRITICAL_PATH_COMPONENT) {
        jsonWriter.name("args");
        jsonWriter.beginObject();
        jsonWriter.name("tid").value(threadId);
        jsonWriter.endObject();
      }
      jsonWriter
          .name("tid")
          .value(
              type == ProfilerTask.CRITICAL_PATH_COMPONENT
                  ? ThreadMetadata.CRITICAL_PATH_THREAD_ID
                  : threadId);
      jsonWriter.endObject();

      countActions();
    }

    private void countActions() {
      long endTimeNanos = startTimeNanos + durationNanos;
      if (instance.countAction(type)) {
        TimeSeries actionCountTimeSeries = instance.actionCountTimeSeriesRef.get();
        if (actionCountTimeSeries != null) {
          actionCountTimeSeries.addRange(
              Duration.ofNanos(startTimeNanos), Duration.ofNanos(endTimeNanos));
        }
      }

      if (type == ProfilerTask.ACTION_CHECK) {
        TimeSeries actionCacheCountTimeSeries = instance.actionCacheCountTimeSeriesRef.get();
        if (actionCacheCountTimeSeries != null) {
          actionCacheCountTimeSeries.addRange(
              Duration.ofNanos(startTimeNanos), Duration.ofNanos(endTimeNanos));
        }
      }

      if (type == ProfilerTask.LOCAL_ACTION_COUNTS) {
        TimeSeries localActionCountTimeSeries = instance.localActionCountTimeSeriesRef.get();
        if (localActionCountTimeSeries != null) {
          localActionCountTimeSeries.addRange(
              Duration.ofNanos(startTimeNanos), Duration.ofNanos(endTimeNanos));
        }
      }
    }
  }

  /**
   * Similar to TaskData, specific for profiled actions. Depending on options, adds additional
   * action specific information such as primary output path and target label. This is only meant to
   * be used for ProfilerTask.ACTION.
   */
  static final class ActionTaskData extends TaskData {
    @Nullable final String primaryOutputPath;
    @Nullable final String targetLabel;
    @Nullable final String mnemonic;
    @Nullable final String configuration;

    ActionTaskData(
        long threadId,
        long startTimeNanos,
        long durationNanos,
        ProfilerTask eventType,
        @Nullable String mnemonic,
        String description,
        @Nullable String primaryOutputPath,
        @Nullable String targetLabel,
        @Nullable String configuration) {
      super(threadId, startTimeNanos, durationNanos, eventType, description);
      this.primaryOutputPath = primaryOutputPath;
      this.targetLabel = targetLabel;
      this.mnemonic = mnemonic;
      this.configuration = configuration;
    }
  }

  /**
   * Aggregator class that keeps track of the slowest tasks of the specified type.
   *
   * <p><code>extremaAggregators</p> is sharded so that all threads need not compete for the same
   * lock if they do the same operation at the same time. Access to an individual {@link Extrema}
   * is synchronized on the {@link Extrema} instance itself.
   */
  private static final class SlowestTaskAggregator {
    private static final int SHARDS = 16;
    private static final int SIZE = 30;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final Extrema<SlowTask>[] extremaAggregators = new Extrema[SHARDS];

    SlowestTaskAggregator() {
      for (int i = 0; i < SHARDS; i++) {
        extremaAggregators[i] = Extrema.max(SIZE);
      }
    }

    // @ThreadSafe
    void add(TaskData taskData) {
      Extrema<SlowTask> extrema = extremaAggregators[(int) (taskData.threadId % SHARDS)];
      synchronized (extrema) {
        extrema.aggregate(new SlowTask(taskData));
      }
    }

    // @ThreadSafe
    void clear() {
      for (int i = 0; i < SHARDS; i++) {
        Extrema<SlowTask> extrema = extremaAggregators[i];
        synchronized (extrema) {
          extrema.clear();
        }
      }
    }

    // @ThreadSafe
    ImmutableList<SlowTask> getSlowestTasks() {
      // This is slow, but since it only happens during the end of the invocation, it's OK.
      Extrema<SlowTask> mergedExtrema = Extrema.max(SIZE);
      for (int i = 0; i < SHARDS; i++) {
        Extrema<SlowTask> extrema = extremaAggregators[i];
        synchronized (extrema) {
          for (SlowTask task : extrema.getExtremeElements()) {
            mergedExtrema.aggregate(task);
          }
        }
      }
      return mergedExtrema.getExtremeElements();
    }
  }

  private Clock clock;
  private Set<ProfilerTask> profiledTasks;
  private volatile boolean active = false;
  private volatile boolean recordAllDurations = false;
  private Duration profileCpuStartTime = Duration.ZERO;
  private Duration profileCpuEndTime = Duration.ZERO;
  private Duration profileStartTime = Duration.ZERO;
  private Duration profileEndTime = Duration.ZERO;

  /**
   * The reference to the current writer, if any. If the referenced writer is null, then disk writes
   * are disabled. This can happen when slowest task recording is enabled.
   */
  private final AtomicReference<JsonTraceFileWriter> writerRef = new AtomicReference<>();

  private final SlowestTaskAggregator[] slowestTasks =
      new SlowestTaskAggregator[ProfilerTask.values().length];

  @VisibleForTesting
  final StatRecorder[] tasksHistograms = new StatRecorder[ProfilerTask.values().length];

  /** Collects local cpu usage data (if enabled). */
  private LocalResourceCollector localResourceCollector;

  private final AtomicReference<TimeSeries> actionCountTimeSeriesRef;
  private final AtomicReference<TimeSeries> actionCacheCountTimeSeriesRef;
  private final AtomicReference<TimeSeries> localActionCountTimeSeriesRef;

  private Duration actionCountStartTime;
  private boolean collectTaskHistograms;
  private boolean includePrimaryOutput;
  private boolean includeTargetLabel;
  private boolean includeConfiguration;

  private Profiler() {
    actionCountTimeSeriesRef = new AtomicReference<>();
    actionCacheCountTimeSeriesRef = new AtomicReference<>();
    localActionCountTimeSeriesRef = new AtomicReference<>();
    initHistograms();
    for (ProfilerTask task : ProfilerTask.values()) {
      if (task.collectsSlowestInstances) {
        slowestTasks[task.ordinal()] = new SlowestTaskAggregator();
      }
    }
  }

  private void initHistograms() {
    for (ProfilerTask task : ProfilerTask.values()) {
      if (task.isVfs()) {
        Map<String, ? extends Predicate<? super String>> vfsHeuristics =
            VfsHeuristics.vfsTypeHeuristics;
        List<RecorderAndPredicate> recorders = new ArrayList<>(vfsHeuristics.size());
        for (Map.Entry<String, ? extends Predicate<? super String>> e : vfsHeuristics.entrySet()) {
          recorders.add(
              new RecorderAndPredicate(
                  new SingleStatRecorder(task + " " + e.getKey(), HISTOGRAM_BUCKETS),
                  e.getValue()));
        }
        tasksHistograms[task.ordinal()] = new PredicateBasedStatRecorder(recorders);
      } else {
        tasksHistograms[task.ordinal()] = new SingleStatRecorder(task, HISTOGRAM_BUCKETS);
      }
    }
  }

  /**
   * Returns task histograms. This must be called between calls to {@link #start} and {@link #stop},
   * or the returned recorders are all empty. Note that the returned recorders may still be modified
   * concurrently (but at least they are thread-safe, so that's good).
   *
   * <p>The stat recorders are indexed by {@code ProfilerTask#ordinal}.
   */
  // TODO(ulfjack): This returns incomplete data by design. Maybe we should return the histograms on
  // stop instead? However, this is currently only called from one location in a module, and that
  // can't call stop itself. What to do?
  public synchronized ImmutableList<StatRecorder> getTasksHistograms() {
    Preconditions.checkState(isActive());
    return ImmutableList.copyOf(tasksHistograms);
  }

  public static Profiler instance() {
    return instance;
  }

  /**
   * Returns the nanoTime of the current profiler instance, or an arbitrary constant if not active.
   */
  public static long nanoTimeMaybe() {
    if (instance.isActive()) {
      return instance.clock.nanoTime();
    }
    return -1;
  }

  // Returns the elapsed wall clock time since the profile has been started.
  public static Duration getProfileElapsedTime() {
    Duration endTime =
        instance.isActive() ? Duration.ofNanos(instance.clock.nanoTime()) : instance.profileEndTime;

    return endTime.minus(instance.profileStartTime);
  }

  private static Duration getProcessCpuTime() {
    OperatingSystemMXBean bean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    return Duration.ofNanos(bean.getProcessCpuTime());
  }

  // Returns the CPU time since the profile has been started.
  public static Duration getServerProcessCpuTime() {
    Duration cpuEndTime = instance.isActive() ? getProcessCpuTime() : instance.profileCpuEndTime;
    return cpuEndTime.minus(instance.profileCpuStartTime);
  }

  /**
   * Enable profiling.
   *
   * <p>Subsequent calls to beginTask/endTask will be recorded in the provided output stream. Please
   * note that stream performance is extremely important and buffered streams should be utilized.
   *
   * @param profiledTasks which of {@link ProfilerTask}s to track
   * @param stream output stream to store profile data. Note: passing unbuffered stream object
   *     reference may result in significant performance penalties
   * @param recordAllDurations iff true, record all tasks regardless of their duration; otherwise
   *     some tasks may get aggregated if they finished quick enough
   * @param clock a {@code BlazeClock.instance()}
   * @param execStartTimeNanos execution start time in nanos obtained from {@code clock.nanoTime()}
   */
  public synchronized void start(
      ImmutableSet<ProfilerTask> profiledTasks,
      OutputStream stream,
      Format format,
      String outputBase,
      UUID buildID,
      boolean recordAllDurations,
      Clock clock,
      long execStartTimeNanos,
      boolean slimProfile,
      boolean includePrimaryOutput,
      boolean includeTargetLabel,
      boolean includeConfiguration,
      boolean collectTaskHistograms,
      LocalResourceCollector localResourceCollector)
      throws IOException {
    checkState(!active, "Profiler already active");

    initHistograms();

    this.profiledTasks = profiledTasks.isEmpty() ? profiledTasks : EnumSet.copyOf(profiledTasks);
    this.clock = clock;
    this.actionCountStartTime = Duration.ofNanos(clock.nanoTime());
    this.actionCountTimeSeriesRef.set(
        new TimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION));
    this.actionCacheCountTimeSeriesRef.set(
        new TimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION));
    this.localActionCountTimeSeriesRef.set(
        new TimeSeries(actionCountStartTime, ACTION_COUNT_BUCKET_DURATION));
    this.collectTaskHistograms = collectTaskHistograms;
    this.includePrimaryOutput = includePrimaryOutput;
    this.includeTargetLabel = includeTargetLabel;
    this.includeConfiguration = includeConfiguration;
    this.recordAllDurations = recordAllDurations;

    JsonTraceFileWriter writer = null;
    if (stream != null && format != null) {
      writer =
          switch (format) {
            case JSON_TRACE_FILE_FORMAT ->
                new JsonTraceFileWriter(
                    stream, execStartTimeNanos, slimProfile, outputBase, buildID);
            case JSON_TRACE_FILE_COMPRESSED_FORMAT ->
                new JsonTraceFileWriter(
                    new GZIPOutputStream(stream),
                    execStartTimeNanos,
                    slimProfile,
                    outputBase,
                    buildID);
          };
      writer.start();
    }
    this.writerRef.set(writer);

    // Activate profiler.
    profileStartTime = Duration.ofNanos(execStartTimeNanos);
    profileCpuStartTime = getProcessCpuTime();
    active = true;

    this.localResourceCollector = localResourceCollector;
    // Start collecting Bazel and system-wide CPU metric collection.
    this.localResourceCollector.start();
  }

  /**
   * Returns task histograms. This must be called between calls to {@link #start} and {@link #stop},
   * or the returned list is empty.
   */
  // TODO(ulfjack): This returns incomplete data by design. Also see getTasksHistograms.
  public synchronized Iterable<SlowTask> getSlowestTasks() {
    List<Iterable<SlowTask>> slowestTasksByType = new ArrayList<>();

    for (SlowestTaskAggregator aggregator : slowestTasks) {
      if (aggregator != null) {
        slowestTasksByType.add(aggregator.getSlowestTasks());
      }
    }

    return Iterables.concat(slowestTasksByType);
  }

  private void collectActionCounts() {
    long threadId = Thread.currentThread().getId();
    var traceData =
        new TraceData() {
          @Override
          public void writeTraceData(JsonWriter jsonWriter, long profileStartTimeNanos)
              throws IOException {
            Duration endTime = Duration.ofNanos(clock.nanoTime());
            int len =
                (int) endTime.minus(actionCountStartTime).dividedBy(ACTION_COUNT_BUCKET_DURATION)
                    + 1;
            Map<CounterSeriesTask, double[]> counterSeriesMap = new LinkedHashMap<>();
            TimeSeries actionCountTimeSeries = actionCountTimeSeriesRef.get();
            if (actionCountTimeSeries != null) {
              double[] actionCountValues = actionCountTimeSeries.toDoubleArray(len);
              actionCountTimeSeriesRef.set(null);
              counterSeriesMap.put(
                  new CounterSeriesTask("action count", "action", /* color= */ null),
                  actionCountValues);
            }
            TimeSeries actionCacheCountTimeSeries = actionCacheCountTimeSeriesRef.get();
            if (actionCacheCountTimeSeries != null) {
              double[] actionCacheCountValues = actionCacheCountTimeSeries.toDoubleArray(len);
              actionCacheCountTimeSeriesRef.set(null);
              counterSeriesMap.put(
                  new CounterSeriesTask(
                      "action cache count", "local action cache", /* color= */ null),
                  actionCacheCountValues);
            }
            if (!counterSeriesMap.isEmpty()) {
              logCounters(
                  jsonWriter,
                  profileStartTimeNanos,
                  counterSeriesMap,
                  actionCountStartTime,
                  ACTION_COUNT_BUCKET_DURATION);
            }

            Map<CounterSeriesTask, double[]> localCounterSeriesMap = new LinkedHashMap<>();
            TimeSeries localActionCountTimeSeries = localActionCountTimeSeriesRef.get();
            if (localActionCountTimeSeries != null) {
              double[] localActionCountValues = localActionCountTimeSeries.toDoubleArray(len);
              localActionCountTimeSeriesRef.set(null);
              localCounterSeriesMap.put(
                  new CounterSeriesTask(
                      "action count (local)",
                      "local action",
                      CounterSeriesTask.Color.DETAILED_MEMORY_DUMP),
                  localActionCountValues);
            }
            if (hasNonZeroValues(localCounterSeriesMap)) {
              logCounters(
                  jsonWriter,
                  profileStartTimeNanos,
                  localCounterSeriesMap,
                  actionCountStartTime,
                  ACTION_COUNT_BUCKET_DURATION);
            }
          }

          private void logCounters(
              JsonWriter jsonWriter,
              long profileStartTimeNanos,
              Map<CounterSeriesTask, double[]> counterSeriesMap,
              Duration profileStart,
              Duration bucketDuration)
              throws IOException {
            var counterSeriesTraceData =
                new CounterSeriesTraceData(
                    threadId, counterSeriesMap, profileStart, bucketDuration);
            counterSeriesTraceData.writeTraceData(jsonWriter, profileStartTimeNanos);
          }
        };
    JsonTraceFileWriter writer = writerRef.get();
    if (writer != null) {
      writer.enqueue(traceData);
    }
  }

  private boolean hasNonZeroValues(Map<CounterSeriesTask, double[]> countersSeriesMap) {
    return countersSeriesMap.values().stream()
        .flatMapToDouble(Arrays::stream)
        .anyMatch(v -> v != 0);
  }

  /**
   * Disable profiling and complete profile file creation. Subsequent calls to beginTask/endTask
   * will no longer be recorded in the profile.
   */
  public synchronized void stop() throws IOException {
    if (!active) {
      return;
    }

    collectActionCounts();
    localResourceCollector.stop();

    // Log a final event to update the duration of ProfilePhase.FINISH.
    logEvent(ProfilerTask.INFO, "Finishing");
    try {
      JsonTraceFileWriter writer = writerRef.getAndSet(null);
      if (writer != null) {
        writer.shutdown();
        writer = null;
      }
    } finally {
      profileCpuEndTime = getProcessCpuTime();
      profileEndTime = Duration.ofNanos(clock.nanoTime());
      active = false;
    }
  }

  /**
   * Clears the records the profiler instance keeps.
   *
   * <p>Should always be called between a {@link #stop()} and a subsequent {@link #start}.
   */
  public synchronized void clear() {
    Preconditions.checkState(!active);

    Arrays.fill(tasksHistograms, null);
    profileStartTime = Duration.ZERO;
    profileEndTime = Duration.ZERO;
    profileCpuStartTime = Duration.ZERO;
    profileCpuEndTime = Duration.ZERO;

    for (SlowestTaskAggregator aggregator : slowestTasks) {
      if (aggregator != null) {
        aggregator.clear();
      }
    }

    multiLaneGenerator.reset();
  }

  /** Returns true iff profiling is currently enabled. */
  public boolean isActive() {
    return active;
  }

  public boolean isProfiling(ProfilerTask type) {
    return profiledTasks.contains(type);
  }

  /**
   * Unless --record_full_profiler_data is given we drop small tasks and add their time to the
   * parents duration.
   */
  private boolean wasTaskSlowEnoughToRecord(ProfilerTask type, long duration) {
    return (recordAllDurations || duration >= type.minDuration);
  }

  /** Interface for collecting counter series */
  public interface CounterSeriesCollector {
    void collect(double deltaNanos, BiConsumer<CounterSeriesTask, Double> consumer);
  }

  public void registerCounterSeriesCollector(CounterSeriesCollector collector) {
    if (!isActive()) {
      return;
    }

    localResourceCollector.registerCounterSeriesCollector(collector);
  }

  /** Adds a whole action count series to the writer bypassing histogram and subtask creation. */
  public void logCounters(
      Map<CounterSeriesTask, double[]> counterSeriesMap,
      Duration profileStart,
      Duration bucketDuration) {
    JsonTraceFileWriter currentWriter = writerRef.get();
    if (isActive() && currentWriter != null) {
      CounterSeriesTraceData counterSeriesTraceData =
          new CounterSeriesTraceData(
              Thread.currentThread().threadId(), counterSeriesMap, profileStart, bucketDuration);
      currentWriter.enqueue(counterSeriesTraceData);
    }
  }

  /**
   * Adds task directly to the main queue bypassing task stack. Used for simple tasks that are known
   * to not have any subtasks.
   *
   * @param startTimeNanos task start time (obtained through {@link Profiler#nanoTimeMaybe()})
   * @param duration task duration
   * @param type task type
   * @param description task description. May be stored until end of build.
   */
  private void logTask(long startTimeNanos, long duration, ProfilerTask type, String description) {
    var lane = borrowLane();
    try {
      checkNotNull(description);
      checkState(!description.isEmpty(), "No description -> not helpful");
      if (duration < 0) {
        // See note in Clock#nanoTime, which is used by Profiler#nanoTimeMaybe.
        duration = 0;
      }

      StatRecorder statRecorder = tasksHistograms[type.ordinal()];
      if (collectTaskHistograms && statRecorder != null) {
        statRecorder.addStat((int) Duration.ofNanos(duration).toMillis(), description);
      }

      if (isActive() && startTimeNanos >= 0 && isProfiling(type)) {
        // Store instance fields as local variables so they are not nulled out from under us by
        // #clear.
        JsonTraceFileWriter currentWriter = writerRef.get();
        if (wasTaskSlowEnoughToRecord(type, duration)) {
          TaskData data = new TaskData(getLaneId(lane), startTimeNanos, type, description);
          data.durationNanos = duration;
          if (currentWriter != null) {
            currentWriter.enqueue(data);
          }

          SlowestTaskAggregator aggregator = slowestTasks[data.type.ordinal()];
          if (aggregator != null) {
            aggregator.add(data);
          }
        }
      }
    } finally {
      releaseLane(lane);
    }
  }

  /**
   * Used externally to submit simple task (one that does not have any subtasks). Depending on the
   * minDuration attribute of the task type, task may be just aggregated into the parent task and
   * not stored directly.
   *
   * @param startTimeNanos task start time (obtained through {@link Profiler#nanoTimeMaybe()})
   * @param type task type
   * @param description task description. May be stored until the end of the build.
   */
  public void logSimpleTask(long startTimeNanos, ProfilerTask type, String description) {
    if (clock != null) {
      logTask(startTimeNanos, clock.nanoTime() - startTimeNanos, type, description);
    }
  }

  /**
   * Used externally to submit simple task (one that does not have any subtasks). Depending on the
   * minDuration attribute of the task type, task may be just aggregated into the parent task and
   * not stored directly.
   *
   * <p>Note that start and stop time must both be acquired from the same clock instance.
   *
   * @param startTimeNanos task start time
   * @param stopTimeNanos task stop time
   * @param type task type
   * @param description task description. May be stored until the end of the build.
   */
  public void logSimpleTask(
      long startTimeNanos, long stopTimeNanos, ProfilerTask type, String description) {
    logTask(startTimeNanos, stopTimeNanos - startTimeNanos, type, description);
  }

  /**
   * Used externally to submit simple task (one that does not have any subtasks). Depending on the
   * minDuration attribute of the task type, task may be just aggregated into the parent task and
   * not stored directly.
   *
   * @param startTimeNanos task start time (obtained through {@link Profiler#nanoTimeMaybe()})
   * @param duration the duration of the task
   * @param type task type
   * @param description task description. May be stored until the end of the build.
   */
  public void logSimpleTaskDuration(
      long startTimeNanos, Duration duration, ProfilerTask type, String description) {
    logTask(startTimeNanos, duration.toNanos(), type, description);
  }

  /** Used to log "events" happening at a specific time - tasks with zero duration. */
  public void logEventAtTime(long atTimeNanos, ProfilerTask type, String description) {
    logTask(atTimeNanos, 0, type, description);
  }

  /** Used to log "events" - tasks with zero duration. */
  @VisibleForTesting
  void logEvent(ProfilerTask type, String description) {
    logEventAtTime(clock.nanoTime(), type, description);
  }

  private SilentCloseable reallyProfile(ProfilerTask type, String description) {
    final long startTimeNanos = clock.nanoTime();
    var lane = borrowLane();
    return () -> {
      try {
        completeTask(getLaneId(lane), startTimeNanos, type, description);
      } finally {
        releaseLane(lane);
      }
    };
  }

  /**
   * Records the beginning of a task as specified, and returns a {@link SilentCloseable} instance
   * that ends the task. This lets the system do the work of ending the task, with the compiler
   * giving a warning if the returned instance is not closed.
   *
   * <p>Use of this method allows to support nested task monitoring. For tasks that are known to not
   * have any subtasks, logSimpleTask() should be used instead.
   *
   * <p>Use like this:
   *
   * <pre>{@code
   * try (SilentCloseable c = Profiler.instance().profile(type, "description")) {
   *   // Your code here.
   * }
   * }</pre>
   *
   * @param type predefined task type - see ProfilerTask for available types.
   * @param description task description. May be stored until the end of the build.
   */
  public SilentCloseable profile(ProfilerTask type, String description) {
    return (isActive() && isProfiling(type)) ? reallyProfile(type, description) : NOP;
  }

  /**
   * Version of {@link #profile(ProfilerTask, String)} that avoids creating string unless actually
   * profiling.
   */
  public SilentCloseable profile(ProfilerTask type, Supplier<String> description) {
    return (isActive() && isProfiling(type)) ? reallyProfile(type, description.get()) : NOP;
  }

  /**
   * Records the beginning of a task as specified, and returns a {@link SilentCloseable} instance
   * that ends the task. This lets the system do the work of ending the task, with the compiler
   * giving a warning if the returned instance is not closed.
   *
   * <p>Use of this method allows to support nested task monitoring. For tasks that are known to not
   * have any subtasks, logSimpleTask() should be used instead.
   *
   * <p>This is a convenience method that uses {@link ProfilerTask#INFO}.
   *
   * <p>Use like this:
   *
   * <pre>{@code
   * try (SilentCloseable c = Profiler.instance().profile("description")) {
   *   // Your code here.
   * }
   * }</pre>
   *
   * @param description task description. May be stored until the end of the build.
   */
  public SilentCloseable profile(String description) {
    return profile(ProfilerTask.INFO, description);
  }

  /**
   * Similar to {@link #profile}, but specific to action-related events. Takes an extra argument:
   * primaryOutput.
   */
  public SilentCloseable profileAction(
      ProfilerTask type,
      String mnemonic,
      String description,
      String primaryOutput,
      String targetLabel,
      String configuration) {
    checkNotNull(description);
    if (isActive() && isProfiling(type)) {
      final long startTimeNanos = clock.nanoTime();
      var lane = borrowLane();
      return () -> {
        try {
          completeAction(
              getLaneId(lane),
              startTimeNanos,
              type,
              description,
              mnemonic,
              includePrimaryOutput ? primaryOutput : null,
              includeTargetLabel ? targetLabel : null,
              includeConfiguration ? configuration : null);
        } finally {
          releaseLane(lane);
        }
      };
    } else {
      return NOP;
    }
  }

  private static final SilentCloseable NOP = () -> {};

  private boolean countAction(ProfilerTask type) {
    return type == ProfilerTask.ACTION || type == ProfilerTask.DISCOVER_INPUTS;
  }

  public void completeTask(long startTimeNanos, ProfilerTask type, String description) {
    var lane = borrowLane();
    try {
      completeTask(getLaneId(lane), startTimeNanos, type, description);
    } finally {
      releaseLane(lane);
    }
  }

  /** Records the end of the task. */
  private void completeTask(
      long laneId, long startTimeNanos, ProfilerTask type, String description) {
    if (isActive()) {
      long endTimeNanos = clock.nanoTime();
      long duration = endTimeNanos - startTimeNanos;
      if (wasTaskSlowEnoughToRecord(type, duration)) {
        recordTask(new TaskData(laneId, startTimeNanos, duration, type, description));
      }
    }
  }

  private void completeAction(
      long threadId,
      long startTimeNanos,
      ProfilerTask type,
      String description,
      String mnemonic,
      @Nullable String primaryOutput,
      @Nullable String targetLabel,
      @Nullable String configuration) {
    if (isActive()) {
      long endTimeNanos = clock.nanoTime();
      long duration = endTimeNanos - startTimeNanos;
      boolean shouldRecordTask = wasTaskSlowEnoughToRecord(type, duration);
      if (shouldRecordTask) {
        recordTask(
            new ActionTaskData(
                threadId,
                startTimeNanos,
                duration,
                type,
                mnemonic,
                description,
                primaryOutput,
                targetLabel,
                configuration));
      }
    }
  }

  private void recordTask(TaskData data) {
    JsonTraceFileWriter writer = writerRef.get();
    if (writer != null) {
      writer.enqueue(data);
    }

    SlowestTaskAggregator aggregator = slowestTasks[data.type.ordinal()];
    if (aggregator != null) {
      aggregator.add(data);
    }
  }

  /** Convenience method to log phase marker tasks. */
  public void markPhase(ProfilePhase phase) throws InterruptedException {
    MemoryProfiler.instance().markPhase(phase);
    if (isActive() && isProfiling(ProfilerTask.PHASE)) {
      logEvent(ProfilerTask.PHASE, phase.description);
    }
  }

  private final AtomicLong nextLaneId = new AtomicLong(1_000_000);
  private final MultiLaneGenerator multiLaneGenerator = new MultiLaneGenerator();

  private class MultiLaneGenerator {
    private final Map<String, LaneGenerator> laneGenerators = Maps.newConcurrentMap();

    private Lane acquire(String prefix) {
      checkState(isActive());
      var laneGenerator =
          laneGenerators.computeIfAbsent(prefix, unused -> new LaneGenerator(prefix));
      return laneGenerator.acquire();
    }

    private void release(Lane lane) {
      checkState(isActive());
      var laneGenerator = lane.laneGenerator;
      laneGenerator.release(lane);
    }

    private void reset() {
      multiLaneGenerator.laneGenerators.clear();
    }
  }

  private static class Lane implements Comparable<Lane> {
    private final LaneGenerator laneGenerator;
    private final long id;
    private int refCount;

    private Lane(LaneGenerator laneGenerator, long id) {
      this.laneGenerator = laneGenerator;
      this.id = id;
    }

    @Override
    public int compareTo(Lane o) {
      return Long.compare(id, o.id);
    }
  }

  private class LaneGenerator {
    private final String prefix;

    private final Queue<Lane> availableLanes = new ConcurrentLinkedQueue<>();

    private final AtomicInteger count = new AtomicInteger(0);

    private LaneGenerator(String prefix) {
      this.prefix = prefix;
    }

    public Lane acquire() {
      var lane = availableLanes.poll();
      // It might create more virtual lanes, but it's fine for our purpose.
      if (lane == null) {
        lane = new Lane(this, nextLaneId.getAndIncrement());

        int newLaneIndex = count.getAndIncrement();
        String newLaneName = prefix + newLaneIndex + " (Virtual)";
        var threadMetadata = new ThreadMetadata(newLaneName, lane.id);
        var writer = Profiler.this.writerRef.get();
        if (writer != null) {
          writer.enqueue(threadMetadata);
        }
      }
      return lane;
    }

    public void release(Lane lane) {
      availableLanes.offer(lane);
    }
  }

  private final ThreadLocal<String> virtualThreadPrefix =
      ThreadLocal.withInitial(this::guessThreadPrefix);
  private final ThreadLocal<Lane> borrowedLane =
      ThreadLocal.withInitial(
          () -> {
            var prefix = virtualThreadPrefix.get();
            var lane = multiLaneGenerator.acquire(prefix);
            checkState(lane.refCount == 0);
            return lane;
          });

  @Nullable
  private Lane borrowLane() {
    if (!Thread.currentThread().isVirtual() || !isActive()) {
      return null;
    }

    var lane = borrowedLane.get();
    lane.refCount += 1;
    return lane;
  }

  private long getLaneId(@Nullable Lane lane) {
    if (lane == null) {
      return Thread.currentThread().threadId();
    }
    return lane.id;
  }

  private void releaseLane(@Nullable Lane lane) {
    if (lane == null) {
      return;
    }
    lane.refCount -= 1;
    if (lane.refCount == 0) {
      borrowedLane.remove();
      multiLaneGenerator.release(lane);
    }
  }

  private String guessThreadPrefix() {
    var currentThread = Thread.currentThread();
    checkState(currentThread.isVirtual());
    var threadName = currentThread.getName();

    // Assume the thread name has format "prefix%d"
    for (int i = threadName.length() - 1; i > 0; i--) {
      var ch = threadName.charAt(i);
      if (ch < '0' || ch > '9') {
        if (i < threadName.length() - 1) {
          return threadName.substring(0, i + 1);
        }
      }
    }

    return "Other";
  }
}
