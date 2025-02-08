// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.actions.AnalysisGraphStatsEvent;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.TotalAndConfiguredTargetOnlyMetric;
import com.google.devtools.build.lib.actions.cache.PostableActionCacheStats;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.AnalysisPhaseStartedEvent;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary.ActionData;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary.RunnerCount;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ArtifactMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics.AspectCount;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics.RuleClassCount;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.CumulativeMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.DynamicExecutionMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.MemoryMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.MemoryMetrics.GarbageMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.NetworkMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.PackageMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.TargetMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.TimingMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerPoolMetrics;
import com.google.devtools.build.lib.buildtool.CommandPrecompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionPhaseCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionStartingEvent;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter;
import com.google.devtools.build.lib.dynamic.DynamicExecutionFinishedEvent;
import com.google.devtools.build.lib.metrics.MetricsModule.Options;
import com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.PeakHeap;
import com.google.devtools.build.lib.packages.metrics.ExtremaPackageMetricsRecorder;
import com.google.devtools.build.lib.packages.metrics.PackageLoadMetrics;
import com.google.devtools.build.lib.packages.metrics.PackageMetricsPackageLoadingListener;
import com.google.devtools.build.lib.packages.metrics.PackageMetricsRecorder;
import com.google.devtools.build.lib.profiler.MemoryProfiler;
import com.google.devtools.build.lib.profiler.NetworkMetricsCollector;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.SpawnStats;
import com.google.devtools.build.lib.skyframe.ExecutionFinishedEvent;
import com.google.devtools.build.lib.skyframe.SkyKeyStats;
import com.google.devtools.build.lib.skyframe.SkyframeStats;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.SomeExecutionStartedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetPendingExecutionEvent;
import com.google.devtools.build.lib.worker.WorkerProcessMetrics;
import com.google.devtools.build.lib.worker.WorkerProcessMetricsCollector;
import com.google.devtools.build.lib.worker.WorkerProcessStatus;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyframeGraphStatsEvent;
import com.google.devtools.build.skyframe.SkyframeGraphStatsEvent.EvaluationStats;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.util.Durations;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.stream.Stream;

class MetricsCollector {

  private final CommandEnvironment env;
  private final boolean recordMetricsForAllMnemonics;
  private final boolean recordSkyframeMetrics;
  // For ActionSummary.
  private final ConcurrentHashMap<String, ActionStats> actionStatsMap = new ConcurrentHashMap<>();

  // For CumulativeMetrics.
  private final AtomicInteger numAnalyses;
  private final AtomicInteger numBuilds;

  private final ActionSummary.Builder actionSummary = ActionSummary.newBuilder();
  private final TargetMetrics.Builder targetMetrics = TargetMetrics.newBuilder();
  private final PackageMetrics.Builder packageMetrics = PackageMetrics.newBuilder();
  private final TimingMetrics.Builder timingMetrics = TimingMetrics.newBuilder();
  private final ArtifactMetrics.Builder artifactMetrics = ArtifactMetrics.newBuilder();
  private final BuildGraphMetrics.Builder buildGraphMetrics = BuildGraphMetrics.newBuilder();
  private final DynamicExecutionStats dynamicExecutionStats = new DynamicExecutionStats();
  private final SpawnStats spawnStats = new SpawnStats();
  // Skymeld-specific: we don't have an ExecutionStartingEvent for skymeld, so we have to use
  // TopLevelTargetExecutionStartedEvent. This AtomicBoolean is so that we only account for the
  // build once.
  private final AtomicBoolean buildAccountedFor;

  // Identify when the actual actions execution starts (excluding workspace status actions).
  private final AtomicBoolean executionStarted;

  @CanIgnoreReturnValue
  private MetricsCollector(
      CommandEnvironment env, AtomicInteger numAnalyses, AtomicInteger numBuilds) {
    this.env = env;
    Options options = env.getOptions().getOptions(Options.class);
    this.recordMetricsForAllMnemonics = options != null && options.recordMetricsForAllMnemonics;
    this.recordSkyframeMetrics = options != null && options.recordSkyframeMetrics;
    this.numAnalyses = numAnalyses;
    this.numBuilds = numBuilds;
    env.getEventBus().register(this);
    WorkerProcessMetricsCollector.instance().setClock(env.getClock());
    this.buildAccountedFor = new AtomicBoolean();
    this.executionStarted = new AtomicBoolean();
  }

  static void installInEnv(
      CommandEnvironment env, AtomicInteger numAnalyses, AtomicInteger numBuilds) {
    new MetricsCollector(env, numAnalyses, numBuilds);
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logAnalysisStartingEvent(AnalysisPhaseStartedEvent event) {
    numAnalyses.getAndIncrement();
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onAnalysisPhaseComplete(AnalysisPhaseCompleteEvent event) {
    TotalAndConfiguredTargetOnlyMetric actionsConstructed = event.getActionsConstructed();
    actionSummary
        .setActionsCreated(actionsConstructed.total())
        .setActionsCreatedNotIncludingAspects(actionsConstructed.configuredTargetsOnly());
    TotalAndConfiguredTargetOnlyMetric targetsConfigured = event.getTargetsConfigured();
    targetMetrics
        .setTargetsConfigured(targetsConfigured.total())
        .setTargetsConfiguredNotIncludingAspects(targetsConfigured.configuredTargetsOnly());
    timingMetrics.setAnalysisPhaseTimeInMs(event.getTimeInMs());

    packageMetrics.setPackagesLoaded(event.getPkgManagerStats().getPackagesSuccessfullyLoaded());

    if (PackageMetricsPackageLoadingListener.getInstance().getPublishPackageMetricsInBep()) {
      PackageMetricsRecorder recorder =
          PackageMetricsPackageLoadingListener.getInstance().getPackageMetricsRecorder();
      if (recorder != null) {
        Stream<PackageLoadMetrics> metrics = recorder.getPackageLoadMetrics().stream();

        if (recorder.getRecorderType() == PackageMetricsRecorder.Type.ONLY_EXTREMES) {
          ExtremaPackageMetricsRecorder extremaPackageMetricsRecorder =
              (ExtremaPackageMetricsRecorder) recorder;
          // Safeguard: we have 5 metrics, so print at most 5 times the number of packages as being
          // tracked per metric.
          metrics = metrics.limit(5L * extremaPackageMetricsRecorder.getNumPackagesToTrack());
        }
        metrics.forEach(packageMetrics::addPackageLoadMetrics);
      }
    }

    ImmutableMap<String, Integer> actionsConstructedByMnemonic =
        event.getActionsConstructedByMnemonic();
    for (var entry : actionsConstructedByMnemonic.entrySet()) {
      ActionStats actionStats = actionStatsMap.computeIfAbsent(entry.getKey(), ActionStats::new);
      actionStats.numActionsRegistered.addAndGet(entry.getValue());
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logAnalysisGraphStats(AnalysisGraphStatsEvent event) {
    // Check only one event per build. No proto3 check for presence, so check for not-default value.
    if (buildGraphMetrics.getActionLookupValueCount() > 0) {
      BugReport.sendBugReport(
          new IllegalStateException(
              "Already initialized build graph metrics builder: "
                  + buildGraphMetrics
                  + ", "
                  + event.getBuildGraphMetrics()));
    }
    buildGraphMetrics.mergeFrom(event.getBuildGraphMetrics());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logExecutionStartingEvent(ExecutionStartingEvent event) {
    numBuilds.getAndIncrement();
  }

  // Skymeld-specific: we don't have an ExecutionStartingEvent for skymeld, so we have to use
  // TopLevelTargetExecutionStartedEvent
  @Subscribe
  public synchronized void handleExecutionPhaseStart(
      @SuppressWarnings("unused") TopLevelTargetPendingExecutionEvent event) {
    if (buildAccountedFor.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
      numBuilds.getAndIncrement();
    }
  }

  @Subscribe
  public void onSomeExecutionStarted(SomeExecutionStartedEvent event) {
    if (event.countedInExecutionTime()) {
      if (executionStarted.compareAndSet(false, true)) {
        Duration elapsedWallTime = Profiler.getProfileElapsedTime();
        if (elapsedWallTime != null) {
          timingMetrics.setActionsExecutionStartInMs(elapsedWallTime.toMillis());
        }
      }
    }
  }

  @Subscribe
  public void handleExecutionPhaseComplete(ExecutionPhaseCompleteEvent event) {
    timingMetrics.setExecutionPhaseTimeInMs(event.getTimeInMs());
  }

  @SuppressWarnings("unused")
  @Subscribe
  @AllowConcurrentEvents
  public void onActionComplete(ActionCompletionEvent event) {
    ActionStats actionStats =
        actionStatsMap.computeIfAbsent(event.getAction().getMnemonic(), ActionStats::new);
    actionStats.numActionsExecuted.incrementAndGet();
    actionStats.firstStarted.accumulate(event.getRelativeActionStartTimeNanos());
    actionStats.lastEnded.accumulate(BlazeClock.nanoTime());
    spawnStats.incrementActionCount();
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionResultReceived(ActionResultReceivedEvent event) {
    spawnStats.countActionResult(event.getActionResult());
    ActionStats actionStats =
        actionStatsMap.computeIfAbsent(event.getAction().getMnemonic(), ActionStats::new);
    int systemTime = event.getActionResult().cumulativeCommandExecutionSystemTimeInMs();
    if (systemTime > 0) {
      actionStats.systemTime.addAndGet(systemTime);
    }
    int userTime = event.getActionResult().cumulativeCommandExecutionUserTimeInMs();
    if (userTime > 0) {
      actionStats.userTime.addAndGet(userTime);
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onExecutionComplete(ExecutionFinishedEvent event) {
    artifactMetrics
        .setSourceArtifactsRead(event.sourceArtifactsRead())
        .setOutputArtifactsSeen(event.outputArtifactsSeen())
        .setOutputArtifactsFromActionCache(event.outputArtifactsFromActionCache())
        .setTopLevelArtifacts(event.topLevelArtifacts());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onDynamicExecutionFinishedEvent(DynamicExecutionFinishedEvent event) {
    dynamicExecutionStats.update(
        event.getMnemonic(),
        event.getLocalBranchName(),
        event.getRemoteBranchName(),
        event.getWinnerBranchType());
  }

  private ImmutableList<BuildMetrics.EvaluationStat> toEvaluationStats(
      ImmutableMap<SkyFunctionName, Integer> map) {
    return map.entrySet().stream()
        .map(
            e ->
                BuildMetrics.EvaluationStat.newBuilder()
                    .setSkyfunctionName(e.getKey().getName())
                    .setCount(e.getValue())
                    .build())
        .collect(toImmutableList());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onSkyframeGraphStats(SkyframeGraphStatsEvent event) {
    EvaluationStats evaluationStats = event.getEvaluationStats();
    buildGraphMetrics.addAllDirtiedValues(toEvaluationStats(evaluationStats.dirtied()));
    buildGraphMetrics.addAllChangedValues(toEvaluationStats(evaluationStats.changed()));
    buildGraphMetrics.addAllBuiltValues(toEvaluationStats(evaluationStats.built()));
    buildGraphMetrics.addAllCleanedValues(toEvaluationStats(evaluationStats.cleaned()));
    buildGraphMetrics.addAllEvaluatedValues(toEvaluationStats(evaluationStats.evaluated()));
    buildGraphMetrics.setPostInvocationSkyframeNodeCount(event.getGraphSize());
  }

  // This needs to be done in CommandPrecompleteEvent because the metrics are reported on the BEP,
  // which is closed in BlazeModule.afterCommand().
  @SuppressWarnings("unused")
  @Subscribe
  public void onCommandPrecompleteEvent(CommandPrecompleteEvent event) {
    env.getEventBus().post(new BuildMetricsEvent(createBuildMetrics()));
  }

  @SuppressWarnings("unused")
  @Subscribe
  private void logActionCacheStatistics(PostableActionCacheStats stats) {
    actionSummary.setActionCacheStatistics(stats.asProto());
  }

  private BuildMetrics createBuildMetrics() {
    ImmutableList<WorkerProcessMetrics> workerProcessMetrics =
        WorkerProcessMetricsCollector.instance().collectMetrics();
    // Restrict the number of WorkerMetrics that we report based on a predefined prioritization so
    // that we don't spam the BEP in the event that something like a kill-create cycle happens.
    ImmutableList<WorkerMetrics> workerMetrics =
        WorkerProcessMetricsCollector.limitWorkerMetricsToPublish(
            workerProcessMetrics.stream()
                .map(WorkerProcessMetrics::toProto)
                .collect(toImmutableList()),
            WorkerProcessMetricsCollector.MAX_PUBLISHED_WORKER_METRICS);

    addSkyframeStats(buildGraphMetrics);

    BuildMetrics.Builder buildMetrics =
        BuildMetrics.newBuilder()
            .setActionSummary(finishActionSummary())
            .setMemoryMetrics(createMemoryMetrics())
            .setTargetMetrics(targetMetrics.build())
            .setPackageMetrics(packageMetrics.build())
            .setTimingMetrics(finishTimingMetrics())
            .setCumulativeMetrics(createCumulativeMetrics())
            .setArtifactMetrics(artifactMetrics.build())
            .setBuildGraphMetrics(buildGraphMetrics.build())
            .addAllWorkerMetrics(workerMetrics)
            .setWorkerPoolMetrics(createWorkerPoolMetrics(workerProcessMetrics))
            .setDynamicExecutionMetrics(dynamicExecutionStats.toMetrics());

    NetworkMetrics networkMetrics = NetworkMetricsCollector.instance().collectMetrics();
    if (networkMetrics != null) {
      buildMetrics.setNetworkMetrics(networkMetrics);
    }

    return buildMetrics.build();
  }

  private ActionData buildActionData(ActionStats actionStats) {
    NanosToMillisSinceEpochConverter nanosToMillisSinceEpochConverter =
        BlazeClock.createNanosToMillisSinceEpochConverter();
    ActionData.Builder builder =
        ActionData.newBuilder()
            .setMnemonic(actionStats.mnemonic)
            .setFirstStartedMs(
                nanosToMillisSinceEpochConverter.toEpochMillis(
                    actionStats.firstStarted.longValue()))
            .setLastEndedMs(
                nanosToMillisSinceEpochConverter.toEpochMillis(actionStats.lastEnded.longValue()))
            .setActionsExecuted(actionStats.numActionsExecuted.get())
            .setActionsCreated(actionStats.numActionsRegistered.get());
    long systemTime = actionStats.systemTime.get();
    if (systemTime > 0) {
      builder.setSystemTime(Durations.fromMillis(systemTime));
    }
    long userTime = actionStats.userTime.get();
    if (userTime > 0) {
      builder.setUserTime(Durations.fromMillis(userTime));
    }
    return builder.build();
  }

  private static final int MAX_ACTION_DATA = 20;

  private ActionSummary finishActionSummary() {
    Stream<ActionStats> actionStatsStream = actionStatsMap.values().stream();

    if (!recordMetricsForAllMnemonics) {
      actionStatsStream =
          actionStatsStream
              .sorted(Comparator.comparingLong(a -> -a.numActionsExecuted.get()))
              .limit(MAX_ACTION_DATA);
    }

    actionStatsStream.forEach(action -> actionSummary.addActionData(buildActionData(action)));

    ImmutableMap<String, Integer> spawnSummary = spawnStats.getSummary();
    actionSummary.setActionsExecuted(spawnSummary.getOrDefault("total", 0));
    spawnSummary
        .entrySet()
        .forEach(
            e -> {
              RunnerCount.Builder builder = RunnerCount.newBuilder();
              builder.setName(e.getKey()).setCount(e.getValue());
              String execKind = spawnStats.getExecKindFor(e.getKey());
              if (execKind != null) {
                builder.setExecKind(execKind);
              }
              actionSummary.addRunnerCount(builder.build());
            });
    return actionSummary.build();
  }

  private void addSkyframeStats(BuildGraphMetrics.Builder builder) {
    // short-circuit if not requested
    if (!recordSkyframeMetrics) {
      return;
    }

    // NOTE: This can potentially unintentionally consume a pending Exception by
    // calling getSkyframeStats, with our Reporter which ends up consuming the
    // analysis failure unintentionally.  So if our CommandEnvironment has a
    // pending exception, don't touch the Skyframe executor.
    if (env.getPendingException() != null) {
      return;
    }

    // getSkyframeStats return Nullable for unsupported implementations, so
    // ensure we get stats before proceeding.
    SkyframeStats skyframeStats = env.getSkyframeExecutor().getSkyframeStats(env.getReporter());
    if (skyframeStats == null) {
      return;
    }

    Stream<SkyKeyStats> ruleActionStats = skyframeStats.ruleStats().stream();
    Stream<SkyKeyStats> aspectActionStats = skyframeStats.aspectStats().stream();

    ruleActionStats.forEach(
        a ->
            builder.addRuleClass(
                RuleClassCount.newBuilder()
                    .setKey(a.getKey())
                    .setRuleClass(a.getName())
                    .setCount(a.getCount())
                    .setActionCount(a.getActionCount())
                    .build()));
    aspectActionStats.forEach(
        a ->
            builder.addAspect(
                AspectCount.newBuilder()
                    .setKey(a.getKey())
                    .setAspectName(a.getName())
                    .setCount(a.getCount())
                    .setActionCount(a.getActionCount())
                    .build()));
  }

  private MemoryMetrics createMemoryMetrics() {
    MemoryMetrics.Builder memoryMetrics = MemoryMetrics.newBuilder();
    if (MemoryProfiler.instance().getHeapUsedMemoryAtFinish() > 0) {
      memoryMetrics.setUsedHeapSizePostBuild(MemoryProfiler.instance().getHeapUsedMemoryAtFinish());
    }
    PostGCMemoryUseRecorder.get()
        .getPeakPostGcHeap()
        .map(PeakHeap::bytes)
        .ifPresent(memoryMetrics::setPeakPostGcHeapSize);

    if (memoryMetrics.getPeakPostGcHeapSize() < memoryMetrics.getUsedHeapSizePostBuild()) {
      // If we just did a GC and computed the heap size, update the one we got from the GC
      // notification (which may arrive too late for this specific GC).
      memoryMetrics.setPeakPostGcHeapSize(memoryMetrics.getUsedHeapSizePostBuild());
    }

    PostGCMemoryUseRecorder.get()
        .getPeakPostGcHeapTenuredSpace()
        .map(PeakHeap::bytes)
        .ifPresent(memoryMetrics::setPeakPostGcTenuredSpaceHeapSize);

    Map<String, Long> garbageStats = PostGCMemoryUseRecorder.get().getGarbageStats();
    for (Map.Entry<String, Long> garbageEntry : garbageStats.entrySet()) {
      GarbageMetrics.Builder garbageMetrics = GarbageMetrics.newBuilder();
      garbageMetrics.setType(garbageEntry.getKey()).setGarbageCollected(garbageEntry.getValue());
      memoryMetrics.addGarbageMetrics(garbageMetrics.build());
    }

    return memoryMetrics.build();
  }

  private CumulativeMetrics createCumulativeMetrics() {
    return CumulativeMetrics.newBuilder()
        .setNumAnalyses(numAnalyses.get())
        .setNumBuilds(numBuilds.get())
        .build();
  }

  private TimingMetrics finishTimingMetrics() {
    Duration elapsedWallTime = Profiler.getProfileElapsedTime();
    if (elapsedWallTime != null) {
      timingMetrics.setWallTimeInMs(elapsedWallTime.toMillis());
    }
    Duration cpuTime = Profiler.getServerProcessCpuTime();
    if (cpuTime != null) {
      timingMetrics.setCpuTimeInMs(cpuTime.toMillis());
    }
    return timingMetrics.build();
  }

  /** Creates the WorkerPoolMetrics by aggregating the collected WorkerProcessMetrics. */
  static WorkerPoolMetrics createWorkerPoolMetrics(
      ImmutableList<WorkerProcessMetrics> collectedWorkerProcessMetrics) {
    HashMap<Integer, WorkerPoolStats> aggregatedPoolStats = new HashMap<>();
    for (WorkerProcessMetrics wpm : collectedWorkerProcessMetrics) {
      WorkerPoolStats poolStats =
          aggregatedPoolStats.computeIfAbsent(
              wpm.getWorkerKeyHash(), (hash) -> new WorkerPoolStats(wpm.getMnemonic(), hash));
      poolStats.update(wpm);
    }
    return WorkerPoolMetrics.newBuilder()
        .addAllWorkerPoolStats(
            aggregatedPoolStats.values().stream()
                .map(WorkerPoolStats::build)
                .collect(toImmutableList()))
        .build();
  }

  private static class WorkerPoolStats {
    private int createdCount;
    private int destroyedCount;
    private int evictedCount;
    private int userExecExceptionDestroyedCount;
    private int ioExceptionDestroyedCount;
    private int interruptedExceptionDestroyedCount;
    private int unknownDestroyedCount;
    private int aliveCount;
    private final String mnemonic;

    private final int hash;

    WorkerPoolStats(String mnemonic, int hash) {
      this.mnemonic = mnemonic;
      this.hash = hash;
    }

    void update(WorkerProcessMetrics wpm) {
      int numWorkers = wpm.getWorkerIds().size();
      if (wpm.isNewlyCreated()) {
        createdCount += numWorkers;
      }
      WorkerProcessStatus status = wpm.getStatus();
      if (status.isKilled()) {
        switch (status.get()) {
          // If the process is killed due to a specific reason, we attribute the cause to all
          // workers of that process (plural in the case of multiplex workers).

          case KILLED_UNKNOWN -> unknownDestroyedCount += numWorkers;
          case KILLED_DUE_TO_INTERRUPTED_EXCEPTION ->
              interruptedExceptionDestroyedCount += numWorkers;
          case KILLED_DUE_TO_IO_EXCEPTION -> ioExceptionDestroyedCount += numWorkers;
          case KILLED_DUE_TO_MEMORY_PRESSURE -> evictedCount += numWorkers;
          case KILLED_DUE_TO_USER_EXEC_EXCEPTION -> userExecExceptionDestroyedCount += numWorkers;
          default -> {}
        }
        destroyedCount += numWorkers;
      } else {
        aliveCount += numWorkers;
      }
    }

    public WorkerPoolMetrics.WorkerPoolStats build() {
      return WorkerPoolMetrics.WorkerPoolStats.newBuilder()
          .setMnemonic(mnemonic)
          .setHash(hash)
          .setCreatedCount(createdCount)
          .setDestroyedCount(destroyedCount)
          .setEvictedCount(evictedCount)
          .setUserExecExceptionDestroyedCount(userExecExceptionDestroyedCount)
          .setIoExceptionDestroyedCount(ioExceptionDestroyedCount)
          .setInterruptedExceptionDestroyedCount(interruptedExceptionDestroyedCount)
          .setUnknownDestroyedCount(unknownDestroyedCount)
          .setAliveCount(aliveCount)
          .build();
    }
  }

  private static class ActionStats {

    final LongAccumulator firstStarted;
    final LongAccumulator lastEnded;
    final AtomicLong numActionsExecuted;
    final AtomicLong numActionsRegistered;
    final String mnemonic;
    final AtomicLong systemTime;
    final AtomicLong userTime;

    ActionStats(String mnemonic) {
      this.mnemonic = mnemonic;
      firstStarted = new LongAccumulator(Math::min, Long.MAX_VALUE);
      lastEnded = new LongAccumulator(Math::max, 0);
      numActionsExecuted = new AtomicLong();
      numActionsRegistered = new AtomicLong();
      systemTime = new AtomicLong();
      userTime = new AtomicLong();
    }
  }

  /* Collects stats about dynamic execution races  of remote vs local branches **/
  static class DynamicExecutionStats {
    // Mapping from tuple <mnemonic, local branch name, remote branch name> to pair of numbers,
    // which represents corresponding number of wins of local and remote branches.
    final ConcurrentHashMap<RaceIdentifier, RaceWinners> branchWinners;

    public DynamicExecutionStats() {
      this.branchWinners = new ConcurrentHashMap<>();
    }

    public void update(String menemonic, String localName, String remoteName, DynamicMode winner) {

      branchWinners.compute(
          RaceIdentifier.create(menemonic, localName, remoteName),
          (k, oldValue) -> {
            RaceWinners newValue = new RaceWinners(/* localWins= */ 0, /* remoteWins= */ 0);

            if (oldValue != null) {
              newValue = oldValue;
            }

            switch (winner) {
              case LOCAL -> newValue.incrementLocalWins();
              case REMOTE -> newValue.incrementRemoteWins();
            }

            return newValue;
          });
    }

    static class RaceWinners {
      private int localWins;
      private int remoteWins;

      RaceWinners(int localWins, int remoteWins) {
        this.localWins = localWins;
        this.remoteWins = remoteWins;
      }

      public int getLocalWins() {
        return localWins;
      }

      public int getRemoteWins() {
        return remoteWins;
      }

      public void incrementLocalWins() {
        localWins++;
      }

      public void incrementRemoteWins() {
        remoteWins++;
      }
    }

    record RaceIdentifier(String mnemonic, String localName, String remoteName) {
      RaceIdentifier {
        requireNonNull(mnemonic, "mnemonic");
        requireNonNull(localName, "localName");
        requireNonNull(remoteName, "remoteName");
      }

      static RaceIdentifier create(String mnemonic, String localName, String remoteName) {
        return new RaceIdentifier(mnemonic, localName, remoteName);
      }
    }

    public DynamicExecutionMetrics toMetrics() {
      DynamicExecutionMetrics.Builder builder = DynamicExecutionMetrics.newBuilder();
      for (RaceIdentifier raceIdentifier : branchWinners.keySet()) {
        RaceWinners raceWinners = branchWinners.get(raceIdentifier);
        builder.addRaceStatistics(
            DynamicExecutionMetrics.RaceStatistics.newBuilder()
                .setMnemonic(raceIdentifier.mnemonic())
                .setLocalRunner(raceIdentifier.localName())
                .setRemoteRunner(raceIdentifier.remoteName())
                .setLocalWins(raceWinners.getLocalWins())
                .setRemoteWins(raceWinners.getRemoteWins())
                .build());
      }

      return builder.build();
    }
  }
}
