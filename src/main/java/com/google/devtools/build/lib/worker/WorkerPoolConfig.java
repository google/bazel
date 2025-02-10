// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.worker;

import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * Describes the configuration of worker pool, e.g. number of maximal instances and priority of the
 * workers.
 */
public class WorkerPoolConfig {
  private final List<Entry<String, Integer>> workerMaxInstances;
  private final List<Entry<String, Integer>> workerMaxMultiplexInstances;

  public WorkerPoolConfig(
      List<Entry<String, Integer>> workerMaxInstances,
      List<Entry<String, Integer>> workerMaxMultiplexInstances) {
    this.workerMaxInstances = workerMaxInstances;
    this.workerMaxMultiplexInstances = workerMaxMultiplexInstances;
  }

  public List<Entry<String, Integer>> getWorkerMaxInstances() {
    return workerMaxInstances;
  }

  public List<Entry<String, Integer>> getWorkerMaxMultiplexInstances() {
    return workerMaxMultiplexInstances;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkerPoolConfig that)) {
      return false;
    }
    return workerMaxInstances.equals(that.workerMaxInstances)
        && workerMaxMultiplexInstances.equals(that.workerMaxMultiplexInstances);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workerMaxInstances, workerMaxMultiplexInstances);
  }
}
