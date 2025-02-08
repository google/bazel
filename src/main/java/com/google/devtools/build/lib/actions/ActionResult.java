// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Holds the result(s) of an action's execution.
 *
 * @param spawnResults Returns the SpawnResults for the action.
 */
@SuppressWarnings("GoodTime")
public record ActionResult(ImmutableList<SpawnResult> spawnResults) {
  public ActionResult {
    requireNonNull(spawnResults, "spawnResults");
  }

  /** An empty ActionResult used by Actions that don't have any metadata to return. */
  public static final ActionResult EMPTY = ActionResult.create(ImmutableList.of());

  /** Returns a builder that can be used to construct a {@link ActionResult} object. */
  public static Builder builder() {
    return new AutoBuilder_ActionResult_Builder();
  }

  /**
   * Returns the cumulative total of long values taken from a series of {@link SpawnResult}s.
   *
   * @param getSpawnResultLongValue a selector that returns a long value for each {@link
   *     SpawnResult} being considered
   * @return the total, or null if no spawn results contained this long value
   */
  private Long getCumulativeLong(Function<SpawnResult, Long> getSpawnResultLongValue) {
    Long longTotal = null;
    for (SpawnResult spawnResult : spawnResults()) {
      Long longValue = getSpawnResultLongValue.apply(spawnResult);
      if (longValue != null) {
        if (longTotal == null) {
          longTotal = longValue;
        } else {
          longTotal += longValue;
        }
      }
    }
    return longTotal;
  }

  /**
   * Returns the cumulative total of int values taken from a series of {@link SpawnResult}s.
   *
   * @param getSpawnResultIntValue a selector that returns an int value for each {@link SpawnResult}
   *     being considered
   * @return the total value of this values
   */
  private int getCumulativeInt(Function<SpawnResult, Integer> getSpawnResultIntValue) {
    int intTotal = 0;
    for (SpawnResult spawnResult : spawnResults()) {
      intTotal += getSpawnResultIntValue.apply(spawnResult);
    }
    return intTotal;
  }

  /**
   * Returns the cumulative command execution wall time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeCommandExecutionWallTimeInMs() {
    return getCumulativeInt(SpawnResult::getWallTimeInMs);
  }

  /**
   * Returns the cumulative command execution user time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeCommandExecutionUserTimeInMs() {
    return getCumulativeInt(SpawnResult::getUserTimeInMs);
  }

  /**
   * Returns the cumulative command execution system time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeCommandExecutionSystemTimeInMs() {
    return getCumulativeInt(SpawnResult::getSystemTimeInMs);
  }

  /**
   * Returns the cumulative number of block input operations for the {@link Action}.
   *
   * @return the cumulative measurement, or null in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  @Nullable
  public Long cumulativeCommandExecutionBlockInputOperations() {
    return getCumulativeLong(SpawnResult::getNumBlockInputOperations);
  }

  /**
   * Returns the cumulative number of block output operations for the {@link Action}.
   *
   * @return the cumulative measurement, or null in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  @Nullable
  public Long cumulativeCommandExecutionBlockOutputOperations() {
    return getCumulativeLong(SpawnResult::getNumBlockOutputOperations);
  }

  /**
   * Returns the cumulative number of involuntary context switches for the {@link Action}.
   *
   * @return the cumulative measurement, or null in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  @Nullable
  public Long cumulativeCommandExecutionInvoluntaryContextSwitches() {
    return getCumulativeLong(SpawnResult::getNumInvoluntaryContextSwitches);
  }

  /**
   * Returns the cumulative number of involuntary context switches for the {@link Action}. The
   * spawns on one action could execute simultaneously, so the sum of spawn's memory usage is better
   * estimation.
   *
   * @return the cumulative measurement, or null in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  @Nullable
  public Long cumulativeCommandExecutionMemoryInKb() {
    return getCumulativeLong(SpawnResult::getMemoryInKb);
  }

  /**
   * Returns the cumulative spawns total time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsTotalTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().totalTimeInMs());
  }

  /**
   * Returns the cumulative spawns parse time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsParseTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().parseTimeInMs());
  }

  /**
   * Returns the cumulative spawns network time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsNetworkTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().networkTimeInMs());
  }

  /**
   * Returns the cumulative spawns fetch time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsFetchTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().fetchTimeInMs());
  }

  /**
   * Returns the cumulative spawns queue time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsQueueTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().queueTimeInMs());
  }

  /**
   * Returns the cumulative spawns setup time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsSetupTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().setupTimeInMs());
  }

  /**
   * Returns the cumulative spawns upload time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeSpawnsUploadTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().uploadTimeInMs());
  }

  /**
   * Returns the cumulative spawns execution wall time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeExecutionWallTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().executionWallTimeInMs());
  }

  /**
   * Returns the cumulative spawns process output time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeProcessOutputTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().processOutputsTimeInMs());
  }

  /**
   * Returns the cumulative spawns retry time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeRetryTimeInMs() {
    return getCumulativeInt(s -> s.getMetrics().retryTimeInMs());
  }

  /**
   * Indicates whether the action had at least one locally executed spawn.
   *
   * @return true if at the action had at least one locally executed spawn
   */
  public boolean locallyExecuted() {
    boolean locallyExecuted = false;
    for (SpawnResult spawnResult : spawnResults()) {
      locallyExecuted |= !spawnResult.wasRemote();
    }
    return locallyExecuted;
  }

  /**
   * Returns the cumulative command execution CPU time for the {@link Action}.
   *
   * @return the cumulative measurement, or zero in case of execution errors or when the measurement
   *     is not implemented for the current platform
   */
  public int cumulativeCommandExecutionCpuTimeInMs() {
    int userTime = cumulativeCommandExecutionUserTimeInMs();
    int systemTime = cumulativeCommandExecutionSystemTimeInMs();

    // If userTime or systemTime is nondefined (=0), then it will not change a result
    return userTime + systemTime;
  }

  /** Creates an ActionResult given a list of SpawnResults. */
  public static ActionResult create(List<SpawnResult> spawnResults) {
    if (spawnResults == null) {
      return EMPTY;
    } else {
      return builder().setSpawnResults(ImmutableList.copyOf(spawnResults)).build();
    }
  }

  /** Builder for a {@link ActionResult} instance, which is immutable once built. */
  @AutoBuilder
  public abstract static class Builder {

    /** Sets the SpawnResults for the action. */
    public abstract Builder setSpawnResults(ImmutableList<SpawnResult> spawnResults);

    /** Builds and returns an ActionResult object. */
    public abstract ActionResult build();
  }
}
