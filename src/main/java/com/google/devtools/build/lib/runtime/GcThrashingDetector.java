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

package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.bugreport.Crash;
import com.google.devtools.build.lib.bugreport.CrashContext;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nullable;

/**
 * Listens for {@link MemoryPressureEvent} to detect GC thrashing.
 *
 * <p>For each {@link Limit}, maintains a sliding window of the timestamps of consecutive full GCs
 * within {@link Limit#period} where {@link MemoryPressureEvent#percentTenuredSpaceUsed} was more
 * than {@link #threshold}. If {@link Limit#count} consecutive over-threshold full GCs within {@link
 * Limit#period} are observed, calls {@link BugReporter#handleCrash} with an {@link
 * OutOfMemoryError}.
 *
 * <p>Manual GCs do not contribute to the limit. This is to avoid OOMing on GCs manually triggered
 * for memory metrics.
 */
final class GcThrashingDetector {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  record Limit(Duration period, int count) {
    Limit {
      requireNonNull(period, "period");
      checkArgument(
          !period.isNegative() && !period.isZero(), "period must be positive: %s", period);
      checkArgument(count > 0, "count must be positive: %s", count);
    }

    static Limit of(Duration period, int count) {
      return new Limit(period, count);
    }
  }

  /** If enabled in {@link MemoryPressureOptions}, creates a {@link GcThrashingDetector}. */
  @Nullable
  static GcThrashingDetector createForCommand(MemoryPressureOptions options) {
    if (options.gcThrashingLimits.isEmpty() || options.gcThrashingThreshold == 100) {
      return null;
    }

    return new GcThrashingDetector(
        options.gcThrashingThreshold,
        options.gcThrashingLimits,
        BlazeClock.instance(),
        BugReporter.defaultInstance());
  }

  private final int threshold;
  private final ImmutableList<SingleLimitTracker> trackers;
  private final Clock clock;
  private final BugReporter bugReporter;

  @VisibleForTesting
  GcThrashingDetector(int threshold, List<Limit> limits, Clock clock, BugReporter bugReporter) {
    this.threshold = threshold;
    this.trackers = limits.stream().map(SingleLimitTracker::new).collect(toImmutableList());
    this.clock = clock;
    this.bugReporter = bugReporter;
  }

  // This is called from MemoryPressureListener on a single memory-pressure-listener-0 thread, so it
  // should never be called concurrently, but mark it synchronized for good measure.
  synchronized void handle(MemoryPressureEvent event) {
    if (event.percentTenuredSpaceUsed() < threshold) {
      for (var tracker : trackers) {
        tracker.underThresholdGc();
      }
      return;
    }

    if (!event.wasFullGc() || event.wasManualGc()) {
      return;
    }

    Instant now = clock.now();
    for (var tracker : trackers) {
      tracker.overThresholdGc(now);
    }
  }

  /** Tracks GC history for a single {@link Limit}. */
  private final class SingleLimitTracker {
    private final Duration period;
    private final int count;
    private final Queue<Instant> window;

    SingleLimitTracker(Limit limit) {
      this.period = limit.period();
      this.count = limit.count();
      this.window = new ArrayDeque<>(count);
    }

    void underThresholdGc() {
      window.clear();
    }

    void overThresholdGc(Instant now) {
      Instant periodStart = now.minus(period);
      while (!window.isEmpty() && window.element().isBefore(periodStart)) {
        window.remove();
      }
      window.add(now);

      if (window.size() == count) {
        OutOfMemoryError oom =
            new OutOfMemoryError(
                String.format(
                    "GcThrashingDetector forcing exit: the tenured space has been more than %s%%"
                        + " occupied after %s consecutive full GCs within the past %s seconds.",
                    threshold, count, period.toSeconds()));
        logger.atInfo().log("Calling handleCrash");
        bugReporter.handleCrash(Crash.from(oom), CrashContext.halt());
      }
    }
  }
}
