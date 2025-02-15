// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands.info;

import static java.util.stream.Collectors.joining;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics.WorkerStats;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.worker.WorkerProcessMetricsCollector;
import java.util.List;

/** Info item for persistent worker metrics. */
public final class WorkerMetricsInfoItem extends InfoItem {
  public WorkerMetricsInfoItem() {
    super("worker_metrics", "persistent worker metrics", true);
  }

  @Override
  public byte[] get(
      Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env) {

    ImmutableList<WorkerMetrics> proto =
        WorkerProcessMetricsCollector.instance().getLiveWorkerMetrics();
    if (proto.isEmpty()) {
      return print("No persistent workers active.");
    } else {
      StringBuilder stringBuilder = new StringBuilder();
      for (WorkerMetrics workerMetrics : proto) {
        stringBuilder
            .append("- ")
            .append("pid ")
            .append(workerMetrics.getProcessId())
            .append(", ")
            .append(workerMetrics.getMnemonic())
            .append(" (hash: ")
            .append(Long.toHexString(workerMetrics.getWorkerKeyHash()))
            .append(")")
            .append(", ");
        List<WorkerStats> workerStats = workerMetrics.getWorkerStatsList();
        if (!workerStats.isEmpty()) {
          WorkerStats lastWorkerStats = Iterables.getLast(workerStats);
          long currentTimeMillis = env.getClock().currentTimeMillis();
          long diffSeconds =
              (currentTimeMillis - lastWorkerStats.getLastActionStartTimeInMs()) / 1000;
          long minutesAgo = diffSeconds / 60;
          long remainingSeconds = diffSeconds - 60 * minutesAgo;
          stringBuilder
              .append(lastWorkerStats.getWorkerMemoryInKb() / 1024)
              .append("MB, ")
              .append("last action ");
          if (minutesAgo > 0) {
            stringBuilder.append(minutesAgo).append("m ");
          }
          stringBuilder.append(remainingSeconds).append("s ago, ");
        }
        stringBuilder
            .append("executed ")
            .append(workerMetrics.getActionsExecuted())
            .append(" actions, ");
        if (workerMetrics.getIsSandbox()) {
          stringBuilder.append("sandboxed, ");
        }
        if (workerMetrics.getIsMultiplex()) {
          stringBuilder.append("multiplexed, ");
        }
        String workerIds =
            workerMetrics.getWorkerIdsList().stream()
                .sorted()
                .map(e -> e.toString())
                .collect(joining(", "));
        if (workerMetrics.getWorkerIdsList().size() == 1) {
          stringBuilder.append("id ").append(workerIds);
        } else {
          stringBuilder.append("ids [").append(workerIds).append("]");
        }
      }
      return print(stringBuilder.toString());
    }
  }
}
