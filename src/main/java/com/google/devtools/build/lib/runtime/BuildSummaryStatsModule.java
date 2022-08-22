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
package com.google.devtools.build.lib.runtime;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ProfilerStartedEvent;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.ExecutionFinishedEvent;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Duration;

/**
 * Blaze module for the build summary message that reports various stats to the user.
 */
public class BuildSummaryStatsModule extends BlazeModule {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private ActionKeyContext actionKeyContext;
  private CriticalPathComputer criticalPathComputer;
  private EventBus eventBus;
  private Reporter reporter;
  private boolean enabled;

  private boolean statsSummary;
  private long commandStartMillis;
  private long executionStartMillis;
  private long executionEndMillis;
  private SpawnStats spawnStats;
  private Path profilePath;
  private static final long UNKNOWN_CPU_TIME = -1;
  private Duration cpuUserTimeForActions;
  private Duration cpuSystemTimeForActions;
  private Duration cpuTimeForBazelJvm;

  @Override
  public void beforeCommand(CommandEnvironment env) {
    this.reporter = env.getReporter();
    this.eventBus = env.getEventBus();
    this.actionKeyContext = env.getSkyframeExecutor().getActionKeyContext();
    commandStartMillis = env.getCommandStartTime();
    this.spawnStats = new SpawnStats();
    eventBus.register(this);
  }

  @Override
  public void afterCommand() {
    this.criticalPathComputer = null;
    this.eventBus = null;
    this.reporter = null;
    this.spawnStats = null;
  }

  @Override
  public void executorInit(CommandEnvironment env, BuildRequest request, ExecutorBuilder builder) {
    enabled = env.getOptions().getOptions(ExecutionOptions.class).enableCriticalPathProfiling;
    statsSummary = env.getOptions().getOptions(ExecutionOptions.class).statsSummary;
    cpuUserTimeForActions = Duration.ofMillis(0);
    cpuSystemTimeForActions = Duration.ofMillis(0);
    cpuTimeForBazelJvm = Duration.ofMillis(UNKNOWN_CPU_TIME);
  }

  @Subscribe
  public void executionPhaseStarting(ExecutionStartingEvent event) {
    // TODO(ulfjack): Make sure to use the same clock as for commandStartMillis.
    executionStartMillis = BlazeClock.instance().currentTimeMillis();
    if (enabled) {
      criticalPathComputer = new CriticalPathComputer(actionKeyContext, BlazeClock.instance());
      eventBus.register(criticalPathComputer);
    }
  }

  @Subscribe
  public void profileStarting(ProfilerStartedEvent event) {
    this.profilePath = event.getProfilePath();
  }

  @Subscribe
  public void executionPhaseFinish(@SuppressWarnings("unused") ExecutionFinishedEvent event) {
    executionEndMillis = BlazeClock.instance().currentTimeMillis();
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionResultReceived(ActionResultReceivedEvent event) {
    spawnStats.countActionResult(event.getActionResult());
    Optional<Duration> cpuUserTimeForActionsDuration = event.getActionResult().cumulativeCommandExecutionUserTime();
    cpuUserTimeForActions = addCPUTime(cpuUserTimeForActionsDuration, cpuUserTimeForActions);
    Optional<Duration> cpuSystemTimeForActionsDuration = event.getActionResult().cumulativeCommandExecutionSystemTime();
    cpuSystemTimeForActions = addCPUTime(cpuSystemTimeForActionsDuration, cpuSystemTimeForActions);
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionCompletion(ActionCompletionEvent event) {
    spawnStats.incrementActionCount();
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) {
    try {
      // We might want to make this conditional on a flag; it can sometimes be a bit of a nuisance.
      List<String> items = new ArrayList<>();
      items.add(String.format("Elapsed time: %.3fs", event.getResult().getElapsedSeconds()));
      event.getResult().getBuildToolLogCollection()
          .addDirectValue(
              "elapsed time",
              String.format(
                  "%f", event.getResult().getElapsedSeconds()).getBytes(StandardCharsets.UTF_8));

      AggregatedCriticalPath criticalPath = AggregatedCriticalPath.EMPTY;
      if (criticalPathComputer != null) {
        try (SilentCloseable c =
            Profiler.instance().profile(ProfilerTask.CRITICAL_PATH, "Critical path")) {
          criticalPath = criticalPathComputer.aggregate();
          items.add(criticalPath.toStringSummaryNoRemote());
          event.getResult().getBuildToolLogCollection()
              .addDirectValue(
                  "critical path", criticalPath.toString().getBytes(StandardCharsets.UTF_8));
          logger.atInfo().log("%s", criticalPath);
          logger.atInfo().log(
              "Slowest actions:\n  %s",
              Joiner.on("\n  ").join(criticalPathComputer.getSlowestComponents()));
          // We reverse the critical path because the profiler expect events ordered by the time
          // when the actions were executed while critical path computation is stored in the reverse
          // way.
          for (CriticalPathComponent stat : criticalPath.components().reverse()) {
            Profiler.instance()
                .logSimpleTaskDuration(
                    stat.getStartTimeNanos(),
                    stat.getElapsedTime(),
                    ProfilerTask.CRITICAL_PATH_COMPONENT,
                    stat.prettyPrintAction());
          }
        }
      }
      if (profilePath != null) {
        // This leads to missing the afterCommand profiles of the other modules in the profile.
        // Since the BEP currently shuts down at the BuildCompleteEvent, we cannot just move posting
        // the BuildToolLogs to afterCommand of this module.
        try {
          if(Profiler.getProcessCpuTimeMaybe() != null) {
            cpuTimeForBazelJvm = Duration.ofMillis(Profiler.getProcessCpuTimeMaybe().toMillis());
          }
          Profiler.instance().stop();
          event
              .getResult()
              .getBuildToolLogCollection()
              .addLocalFile(profilePath.getBaseName(), profilePath);
        } catch (IOException e) {
          reporter.handle(Event.error("Error while writing profile file: " + e.getMessage()));
        }
      }

      ImmutableMap<String, Integer> spawnSummary = spawnStats.getSummary();
      String spawnSummaryString = SpawnStats.convertSummaryToString(spawnSummary);
      if (statsSummary) {
        reporter.handle(Event.info(spawnSummaryString));
        reporter.handle(
            Event.info(
                String.format(
                    "Total action wall time %.2fs", spawnStats.getTotalWallTimeMillis() / 1000.0)));
        if (criticalPath != AggregatedCriticalPath.EMPTY) {
          reporter.handle(Event.info(criticalPath.getNewStringSummary()));
        }
        long now = event.getResult().getStopTime();
        long executionTime = executionEndMillis - executionStartMillis;
        long overheadTime = now - commandStartMillis - executionTime;
        reporter.handle(
            Event.info(
                String.format(
                    "Elapsed time %.2fs (preparation %.2fs, execution %.2fs)",
                    (now - commandStartMillis) / 1000.0,
                    overheadTime / 1000.0,
                    executionTime / 1000.0)));
        reporter.handle(
            Event.info(
                String.format(
                    "CPU time %s (user %s, system %s, bazel jvm %s)",
                    formatCpuTime(sumCpuTimes(cpuUserTimeForActions.toMillis(), cpuSystemTimeForActions.toMillis(), cpuTimeForBazelJvm.toMillis())),
                    formatCpuTime(cpuUserTimeForActions.toMillis()),
                    formatCpuTime(cpuSystemTimeForActions.toMillis()),
                    formatCpuTime(cpuTimeForBazelJvm.toMillis()))));
      } else {
        reporter.handle(Event.info(Joiner.on(", ").join(items)));
        reporter.handle(Event.info(spawnSummaryString));
      }

      event
          .getResult()
          .getBuildToolLogCollection()
          .addDirectValue("process stats", spawnSummaryString.getBytes(StandardCharsets.UTF_8));
    } finally {
      if (criticalPathComputer != null) {
        eventBus.unregister(criticalPathComputer);
        criticalPathComputer = null;
      }
      profilePath = null;
      cpuUserTimeForActions = Duration.ofMillis(0);
      cpuSystemTimeForActions = Duration.ofMillis(0);
      cpuTimeForBazelJvm = Duration.ofMillis(UNKNOWN_CPU_TIME);
    }
  }

  private static String formatCpuTime(long milliseconds) {
    if (milliseconds == UNKNOWN_CPU_TIME) {
      return "???s";
    } else {
      return String.format("%.2fs", milliseconds / 1000.0);
    }
  }

  //If anyone of the CPU time(cpuUserTimeActions, cpuSystemTimeActions and cpuTimeBazelJvm) is UNKNOWN, then the total CPU time become UNKNOWN.
  private static long sumCpuTimes(long cpuUserTimeActions, long cpuSystemTimeActions, long cpuTimeBazelJvm) {
    if ((cpuUserTimeActions == UNKNOWN_CPU_TIME) || (cpuSystemTimeActions == UNKNOWN_CPU_TIME) || (cpuTimeBazelJvm == UNKNOWN_CPU_TIME)) {
      return UNKNOWN_CPU_TIME;
    } else {
      return cpuUserTimeActions + cpuSystemTimeActions + cpuTimeBazelJvm;
    }
  }
  
  private static Duration addCPUTime(Optional<Duration> sumDuration, Duration termDuration) {
    if(sumDuration.isPresent() && (termDuration.toMillis() !=  UNKNOWN_CPU_TIME)) {
      termDuration = termDuration.plus(sumDuration.get());
    } else {
      termDuration = Duration.ofMillis(UNKNOWN_CPU_TIME);
    }
    return termDuration;
  }
}
