// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.worker;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics.WorkerStatus;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.metrics.CgroupsInfoCollector;
import com.google.devtools.build.lib.metrics.PsInfoCollector;
import com.google.devtools.build.lib.metrics.ResourceSnapshot;
import com.google.devtools.build.lib.sandbox.Cgroup;
import com.google.devtools.build.lib.util.OS;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Collects and populates system metrics about persistent workers. */
public class WorkerProcessMetricsCollector {

  /** The metrics collector (a static singleton instance). Inactive by default. */
  private static final WorkerProcessMetricsCollector instance = new WorkerProcessMetricsCollector();

  private final PsInfoCollector psInfoCollector;
  private final CgroupsInfoCollector cgroupsInfoCollector;

  private Clock clock;

  /**
   * Mapping of worker process ids to their process metrics. This contains all workers that have
   * been alive at any point during the build.
   */
  private final Map<Long, WorkerProcessMetrics> pidToWorkerProcessMetrics =
      new ConcurrentHashMap<>();

  private final Map<Long, Cgroup> pidToCgroups = new ConcurrentHashMap<>();

  private boolean useCgroupsOnLinux = false;

  private WorkerProcessMetricsCollector() {
    psInfoCollector = PsInfoCollector.instance();
    cgroupsInfoCollector = CgroupsInfoCollector.instance();
  }

  @VisibleForTesting
  WorkerProcessMetricsCollector(
      PsInfoCollector psInfoCollector, CgroupsInfoCollector cgroupsInfoCollector) {
    this.psInfoCollector = psInfoCollector;
    this.cgroupsInfoCollector = cgroupsInfoCollector;
  }

  public static WorkerProcessMetricsCollector instance() {
    return instance;
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  public void setUseCgroupsOnLinux(boolean useCgroupsOnLinux) {
    this.useCgroupsOnLinux = useCgroupsOnLinux;
  }

  ResourceSnapshot collectResourceUsage() {
    // Only collect for process we know are alive.
    ImmutableSet<Long> alivePids =
        pidToWorkerProcessMetrics.entrySet().stream()
            .filter(e -> !e.getValue().getStatus().isKilled())
            .map(e -> e.getKey())
            .collect(toImmutableSet());
    return collectResourceUsage(OS.getCurrent(), alivePids);
  }

  /**
   * Collects memory usage of all ancestors of processes by pid. If a pid does not allow collecting
   * memory usage, it is silently ignored.
   */
  @VisibleForTesting
  public ResourceSnapshot collectResourceUsage(OS os, ImmutableSet<Long> alivePids) {
    // TODO(b/181317827): Support Windows.
    if (alivePids.isEmpty()) {
      return ResourceSnapshot.createEmpty(clock.now());
    }
    if (os.equals(OS.DARWIN)) {
      return psInfoCollector.collectResourceUsage(alivePids, clock);
    }
    if (os.equals(OS.LINUX)) {
      if (useCgroupsOnLinux) {
        // Remove the killed pids so that we only collect from the cgroups that are alive.
        for (long pid : ImmutableSet.copyOf(pidToCgroups.keySet())) {
          if (!alivePids.contains(pid)) {
            pidToCgroups.remove(pid);
          }
        }
        return cgroupsInfoCollector.collectResourceUsage(pidToCgroups, clock);
      }
      // Default to using ps if cgroups is not enabled.
      return psInfoCollector.collectResourceUsage(alivePids, clock);
    }
    return ResourceSnapshot.createEmpty(clock.now());
  }

  public ImmutableList<WorkerProcessMetrics> getLiveWorkerProcessMetrics() {
    return collectMetrics().stream()
        .filter(m -> !m.getStatus().isKilled())
        .collect(toImmutableList());
  }

  public ImmutableList<WorkerProcessMetrics> collectMetrics() {
    ResourceSnapshot resourceSnapshot = collectResourceUsage();

    ImmutableMap<Long, Integer> pidToMemoryInKb = resourceSnapshot.pidToMemoryInKb();

    Instant collectionTime = resourceSnapshot.collectionTime();

    ImmutableList.Builder<WorkerProcessMetrics> workerMetrics = new ImmutableList.Builder<>();
    for (Map.Entry<Long, WorkerProcessMetrics> entry : pidToWorkerProcessMetrics.entrySet()) {
      WorkerProcessMetrics workerMetric = entry.getValue();

      if (workerMetric.getStatus().isKilled()) {
        // If it was previously killed by Bazel, we don't do anything.
        workerMetrics.add(workerMetric);
        continue;
      }

      Long pid = workerMetric.getProcessId();
      int memoryInKb = pidToMemoryInKb.getOrDefault(pid, 0);

      if (memoryInKb == 0) {
        // If it is not measurable, not killed by Bazel but has executed actions, then we assume
        // that something has happened to the worker process that is not accounted for by Bazel
        // and set this to KILLED_UNKNOWN. If a separate thread comes along to update the status
        // with a more specific reason why it is killed, then we allow such an update.
        if (workerMetric.getActionsExecuted() > 0) {
          workerMetric.getStatus().maybeUpdateStatus(WorkerProcessStatus.Status.KILLED_UNKNOWN);
        }
        // We want to add the worker metric even if it is not measurable.
        workerMetrics.add(workerMetric);
        continue;
      }

      // If it is measurable, we want to update the collected metrics.
      workerMetric.addCollectedMetrics(
          /* memoryInKb= */ memoryInKb, /* collectionTime= */ collectionTime);
      workerMetrics.add(workerMetric);
    }

    return workerMetrics.build();
  }

  public void onWorkerFinishExecution(long processId) {
    WorkerProcessMetrics wpm = pidToWorkerProcessMetrics.get(processId);
    if (wpm == null) {
      return;
    }
    wpm.incrementActionsExecuted();
  }

  public static final int MAX_PUBLISHED_WORKER_METRICS = 50;

  /** Returns a prioritized and limited list of WorkerMetrics to be published to the BEP. */
  public static ImmutableList<WorkerMetrics> limitWorkerMetricsToPublish(
      ImmutableList<WorkerMetrics> metrics, int limit) {
    return metrics.stream()
        .sorted(new WorkerMetricsPublishComparator())
        .limit(limit)
        .sorted(Comparator.comparingInt(m -> m.getWorkerIdsList().get(0)))
        .collect(toImmutableList());
  }

  /**
   * Because we log all worker processes that have been alive at any point during the build, the
   * size of this list might grow out of hand if there is some issue with the build (e.g.
   * kill-create cycles). As such, we enforce rules to prioritize WorkerMetrics before limiting: (1)
   * Prioritize WorkerStatuses ALIVE, then KILLED_DUE_TO_MEMORY_PRESSURE, then all remaining worker
   * statuses. (2) Then prioritize by decreasing memory usage and (3) limit to a fixed number.
   */
  @VisibleForTesting
  public static class WorkerMetricsPublishComparator implements Comparator<WorkerMetrics> {

    private int getWorkerStatusPriority(WorkerMetrics.WorkerStatus status) {
      // Lower value is prioritized.
      if (status == WorkerStatus.ALIVE) {
        return 0;
      } else if (status == WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE) {
        return 1;
      }
      return 2;
    }

    @Override
    public int compare(WorkerMetrics m1, WorkerMetrics m2) {
      int s1 = getWorkerStatusPriority(m1.getWorkerStatus());
      int s2 = getWorkerStatusPriority(m2.getWorkerStatus());
      if (s1 != s2) {
        return Integer.compare(s1, s2);
      }
      return Integer.compare(
          m2.getWorkerStats(0).getWorkerMemoryInKb(), m1.getWorkerStats(0).getWorkerMemoryInKb());
    }
  }

  public ImmutableList<WorkerMetrics> getLiveWorkerMetrics() {
    return getLiveWorkerProcessMetrics().stream()
        .map(WorkerProcessMetrics::toProto)
        .collect(toImmutableList());
  }

  public void clear() {
    pidToWorkerProcessMetrics.clear();
  }

  @VisibleForTesting
  Map<Long, WorkerProcessMetrics> getPidToWorkerProcessMetrics() {
    return pidToWorkerProcessMetrics;
  }

  /**
   * Initializes workerIdToWorkerProperties for workers. If worker metrics already exists for this
   * worker, only updates the last call time and maybe adds the multiplex worker id.
   */
  public synchronized void registerWorker(
      int workerId,
      long processId,
      WorkerProcessStatus status,
      String mnemonic,
      boolean isMultiplex,
      boolean isSandboxed,
      int workerKeyHash,
      @Nullable Cgroup cgroup) {
    WorkerProcessMetrics workerMetric =
        pidToWorkerProcessMetrics.computeIfAbsent(
            processId,
            (pid) ->
                new WorkerProcessMetrics(
                    workerId,
                    processId,
                    status,
                    mnemonic,
                    isMultiplex,
                    isSandboxed,
                    workerKeyHash));
    if (cgroup != null) {
      pidToCgroups.putIfAbsent(processId, cgroup);
    }
    workerMetric.setLastCallTime(Instant.ofEpochMilli(clock.currentTimeMillis()));
    workerMetric.maybeAddWorkerId(workerId, status);
  }

  /** Removes all WorkerProcessMetrics that were marked as killed. */
  public void clearKilledWorkerProcessMetrics() {
    List<Long> pidsToRemove = new ArrayList<>();
    for (Map.Entry<Long, WorkerProcessMetrics> entry : pidToWorkerProcessMetrics.entrySet()) {
      if (entry.getValue().getStatus().isKilled()) {
        pidsToRemove.add(entry.getKey());
      }
    }
    pidToWorkerProcessMetrics.keySet().removeAll(pidsToRemove);
  }

  /** To reset states in each WorkerProcessMetric before each command where applicable. */
  public void beforeCommand() {
    pidToWorkerProcessMetrics.values().forEach(m -> m.onBeforeCommand());
  }
}
