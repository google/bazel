// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.metrics;

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.clock.Clock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Helps to collect information about all process using ps command. Works for Linux and MacOS
 * systems.
 */
public class PsInfoCollector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  // Updates snapshots no more than once per interval. Running ps is somewhat slow and should not be
  // done too often.
  private static final Duration MIN_COLLECTION_INTERVAL = Duration.ofMillis(500);
  private static final PsInfoCollector instance = new PsInfoCollector();

  public static PsInfoCollector instance() {
    return instance;
  }

  private PsSnapshot currentPsSnapshot;

  // prevent construction
  private PsInfoCollector() {}

  /**
   * If ps snapshot was outdated will update it, and then returns resource consumption snapshot of
   * processes subtrees based on collected ps snapshot.
   */
  public synchronized ResourceSnapshot collectResourceUsage(
      ImmutableSet<Long> processIds, Clock clock) {
    Instant now = clock.now();
    if (currentPsSnapshot == null
        || Duration.between(currentPsSnapshot.collectionTime(), now)
                .compareTo(MIN_COLLECTION_INTERVAL)
            > 0
        || currentPsSnapshot.processPidsHash() != processIds.hashCode()) {

      updatePsSnapshot(clock, processIds);
    }

    ImmutableMap.Builder<Long, Integer> pidToMemoryInKb = ImmutableMap.builder();
    for (Long pid : processIds) {
      PsInfo psInfo = currentPsSnapshot.pidToPsInfo().get(pid);
      if (psInfo == null) {
        continue;
      }
      pidToMemoryInKb.put(pid, collectMemoryUsageOfDescendants(psInfo, currentPsSnapshot));
    }

    return ResourceSnapshot.create(
        pidToMemoryInKb.buildOrThrow(), currentPsSnapshot.collectionTime());
  }

  /** Updates current snapshot of all processes state, using ps command. */
  private void updatePsSnapshot(Clock clock, ImmutableSet<Long> processIds) {
    ImmutableMap<Long, PsInfo> pidToPsInfo = collectDataFromPs();

    ImmutableSetMultimap<Long, PsInfo> pidToChildrenPsInfo =
        pidToPsInfo.values().stream()
            .collect(toImmutableSetMultimap(PsInfo::parentPid, Function.identity()));

    currentPsSnapshot =
        new PsSnapshot(pidToPsInfo, pidToChildrenPsInfo, clock.now(), processIds.hashCode());
  }

  /** Collects memory usage for every process. */
  @VisibleForTesting
  ImmutableMap<Long, PsInfo> collectDataFromPs() {
    try {
      Process psProcess = buildPsProcess();
      return collectDataFromPsProcess(psProcess);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error while executing command ps");
      return ImmutableMap.of();
    }
  }

  static ImmutableMap<Long, PsInfo> collectDataFromPsProcess(Process psProcess) {
    BufferedReader psOutput =
        new BufferedReader(new InputStreamReader(psProcess.getInputStream(), UTF_8));

    ImmutableMap.Builder<Long, PsInfo> psInfos = ImmutableMap.builder();

    try {
      // The output of the above ps command looks similar to this:
      // PID     PPID   RSS
      // 211706  1      222972
      // 2612333 211706 6180
      // We skip over the first line (the header) and then parse the PID and the resident memory
      // size in kilobytes.
      String output = null;
      boolean isFirst = true;
      while ((output = psOutput.readLine()) != null) {
        if (isFirst) {
          isFirst = false;
          continue;
        }
        List<String> line = Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(output);
        if (line.size() != 3) {
          logger.atWarning().log("Unexpected length of split line %s %d", output, line.size());
          continue;
        }

        long pid = Long.parseLong(line.get(0));
        long parentPid = Long.parseLong(line.get(1));
        int memoryInKb = Integer.parseInt(line.get(2));

        psInfos.put(pid, new PsInfo(pid, parentPid, memoryInKb));
      }
    } catch (IllegalArgumentException | IOException e) {
      logger.atWarning().withCause(e).log("Error while parsing psOutput: %s", psOutput);
    }

    return psInfos.buildOrThrow();
  }

  private static Process buildPsProcess() throws IOException {
    return new ProcessBuilder("ps", "-e", "-o", "pid,ppid,rss").start();
  }

  /** Recursively collects total memory usage of all descendants of the process. */
  private static int collectMemoryUsageOfDescendants(PsInfo psInfo, PsSnapshot psSnapshot) {
    int currentMemoryInKb = psInfo.memoryInKb();
    for (PsInfoCollector.PsInfo childrenPsInfo :
        psSnapshot.pidToChildrenPsInfo().get(psInfo.pid())) {
      currentMemoryInKb += collectMemoryUsageOfDescendants(childrenPsInfo, psSnapshot);
    }

    return currentMemoryInKb;
  }

  /** Parsed information about process collected after ps command call. */
  record PsInfo(long pid, long parentPid, int memoryInKb) {}

  /** Contains structurized information from ps command. */
  private record PsSnapshot(
      ImmutableMap<Long, PsInfo> pidToPsInfo,
      ImmutableSetMultimap<Long, PsInfo> pidToChildrenPsInfo,
      Instant collectionTime,
      long processPidsHash) {
    public PsSnapshot {
      Objects.requireNonNull(pidToPsInfo);
      Objects.requireNonNull(pidToChildrenPsInfo);
      Objects.requireNonNull(collectionTime);
    }
  }
}
