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

package com.google.devtools.build.lib.profiler;

import com.google.common.base.Ascii;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.LogSites;
import com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Utility for creating {@link AutoProfiler} instances from {@link GoogleLogger} instances. */
public class GoogleAutoProfilerUtils {
  private static final GoogleLogger selfLogger = GoogleLogger.forEnclosingClass();
  private static final String LOGGING_MESSAGE_TEMPLATE =
      "Spent %d " + Ascii.toLowerCase(TimeUnit.MILLISECONDS.toString()) + " doing %s";

  private GoogleAutoProfilerUtils() {}

  private static AutoProfiler logged(
      String description, GoogleLogger logger, Duration minTimeForLogging) {
    return AutoProfiler.create(makeReceiver(description, logger, minTimeForLogging));
  }

  public static AutoProfiler logged(String description, Duration minTimeForLogging) {
    return logged(description, selfLogger, minTimeForLogging);
  }

  public static AutoProfiler logged(String description) {
    return AutoProfiler.create(createSimpleLogger(description));
  }

  private static ElapsedTimeReceiver createSimpleLogger(String description) {
    return elapsedTimeNanos -> log(selfLogger, elapsedTimeNanos, description);
  }

  /**
   * Returns an {@link AutoProfiler} that, when closed, records the elapsed time using {@link
   * Profiler} and also logs it (in milliseconds) to the default logger.
   *
   * <p>The returned {@link AutoProfiler} is thread-safe.
   */
  public static AutoProfiler profiledAndLogged(
      String taskDescription, ProfilerTask profilerTaskType) {
    ElapsedTimeReceiver profilingReceiver =
        new AutoProfiler.ProfilingElapsedTimeReceiver(taskDescription, profilerTaskType);
    return AutoProfiler.create(
        new SequencedElapsedTimeReceiver(profilingReceiver, createSimpleLogger(taskDescription)));
  }

  /**
   * Returns an {@link AutoProfiler} that, when closed, will log if the operation exceeds provided
   * threshold and call the custom {@link ElapsedTimeReceiver} for any duration.
   */
  public static AutoProfiler loggedAndCustomReceiver(
      String taskDescription, Duration minTimeForLogging, ElapsedTimeReceiver customReceiver) {
    return AutoProfiler.create(
        new SequencedElapsedTimeReceiver(
            makeReceiver(taskDescription, selfLogger, minTimeForLogging), customReceiver));
  }

  private static ElapsedTimeReceiver makeReceiver(
      String description, GoogleLogger logger, Duration minTimeForLogging) {
    return new FloggerElapsedTimeReceiver(description, logger, minTimeForLogging);
  }

  /** {@link ElapsedTimeReceiver} that will not log a message if the time elapsed is too small. */
  private static class FloggerElapsedTimeReceiver implements ElapsedTimeReceiver {
    // Some classes in Google-internal Blaze use a specially configured logger. When those classes
    // record elapsed time using this library, they pass their logger in here, which we use instead
    // of this library's default selfLogger.
    private final GoogleLogger logger;
    private final String taskDescription;
    private final Duration minTimeForLogging;

    FloggerElapsedTimeReceiver(
        String taskDescription, GoogleLogger logger, Duration minTimeForLogging) {
      this.taskDescription = taskDescription;
      this.minTimeForLogging = minTimeForLogging;
      this.logger = logger;
    }

    @Override
    public final void accept(long elapsedTimeNanos) {
      // We avoid eagerly converting elapsedTimeNanos to a Duration to minimize garbage creation.
      if (elapsedTimeNanos < minTimeForLogging.toNanos()) {
        return;
      }
      log(logger, elapsedTimeNanos, taskDescription);
    }
  }

  private static void log(GoogleLogger logger, long elapsedTimeNanos, String taskDescription) {
    logger
        .atInfo()
        .withInjectedLogSite(LogSites.callerOf(AutoProfiler.class))
        .log(
            LOGGING_MESSAGE_TEMPLATE,
            // TODO(janakr): confirm that this doesn't show up as a source of garbage. Since it only
            //  happens when we're actually logging, it shouldn't.
            Duration.ofNanos(elapsedTimeNanos).toMillis(),
            taskDescription);
  }

  private static class SequencedElapsedTimeReceiver implements ElapsedTimeReceiver {
    private final ElapsedTimeReceiver firstReceiver;
    private final ElapsedTimeReceiver secondReceiver;

    private SequencedElapsedTimeReceiver(
        ElapsedTimeReceiver firstReceiver, ElapsedTimeReceiver secondReceiver) {
      this.firstReceiver = firstReceiver;
      this.secondReceiver = secondReceiver;
    }

    @Override
    public void accept(long elapsedTimeNanos) {
      firstReceiver.accept(elapsedTimeNanos);
      secondReceiver.accept(elapsedTimeNanos);
    }
  }
}
