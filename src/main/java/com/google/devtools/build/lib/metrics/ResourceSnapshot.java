// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;

/**
 * Contains a snapshot of the resource usage of multiple processes.
 *
 * @param pidToMemoryInKb Overall memory consumption by all descendant processes including initial
 *     process.
 * @param collectionTime Time when this snapshot was collected.
 */
public record ResourceSnapshot(
    ImmutableMap<Long, Integer> pidToMemoryInKb, Instant collectionTime) {
  public ResourceSnapshot {
    requireNonNull(pidToMemoryInKb, "pidToMemoryInKb");
    requireNonNull(collectionTime, "collectionTime");
  }

  public static ResourceSnapshot create(
      ImmutableMap<Long, Integer> pidToMemoryInKb, Instant collectionTime) {
    return new ResourceSnapshot(pidToMemoryInKb, collectionTime);
  }

  public static ResourceSnapshot createEmpty(Instant collectionTime) {
    return new ResourceSnapshot(ImmutableMap.of(), collectionTime);
  }
}
