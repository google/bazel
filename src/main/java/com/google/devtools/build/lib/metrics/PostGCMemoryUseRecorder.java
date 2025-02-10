// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.runtime.ServerBuilder;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.concurrent.GuardedBy;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Keeps track of the peak heap usage directly after a full GC by listening for GC notifications.
 *
 * <p>The idea behind this class is as follows. We assume that:
 *
 * <pre>
 * sizeof(heap used) = sizeof(data) + sizeof(garbage)
 * </pre>
 *
 * and that after a full GC sizeof(garbage) is close to 0.
 *
 * <p>This allows us to measure sizeof(data) by measuring sizeof(heap used) immediately after a full
 * GC.
 */
@SuppressWarnings("GoodTime") // Using long timestamps for consistency with code base.
public final class PostGCMemoryUseRecorder implements NotificationListener {
  private static PostGCMemoryUseRecorder instance = null;

  public static synchronized PostGCMemoryUseRecorder get() {
    if (instance == null) {
      instance =
          new PostGCMemoryUseRecorder(
              ManagementFactory.getGarbageCollectorMXBeans(), BugReporter.defaultInstance());
    }
    return instance;
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** The memory use and time of a build's peak post-GC heap. */
  public record PeakHeap(long bytes, long timestampMillis) {

    static PeakHeap create(long bytes, long timestampMillis) {
      return new PeakHeap(bytes, timestampMillis);
    }
  }

  @GuardedBy("this")
  private Optional<PeakHeap> peakHeapTotal = Optional.empty();

  @GuardedBy("this")
  private Optional<PeakHeap> peakHeapTenuredSpace = Optional.empty();

  // Set to true iff a GarbageCollectionNotification reported that we were using no memory.
  @GuardedBy("this")
  private boolean memoryUsageReportedZero = false;

  @GuardedBy("this")
  private Map<String, Long> garbageStats = new HashMap<>();

  private final BugReporter bugReporter;

  @VisibleForTesting
  PostGCMemoryUseRecorder(Iterable<GarbageCollectorMXBean> mxBeans, BugReporter bugReporter) {
    for (GarbageCollectorMXBean mxBean : mxBeans) {
      // The "Copy" collector only does minor collections.
      if ("Copy".equals(mxBean.getName())) {
        continue;
      }
      logger.atInfo().log("Listening for notifications from GC: %s", mxBean.getName());
      ((NotificationEmitter) mxBean).addNotificationListener(this, null, null);
    }
    this.bugReporter = bugReporter;
  }

  public synchronized Optional<PeakHeap> getPeakPostGcHeap() {
    return peakHeapTotal;
  }

  public synchronized Optional<PeakHeap> getPeakPostGcHeapTenuredSpace() {
    return peakHeapTenuredSpace;
  }

  public synchronized boolean wasMemoryUsageReportedZero() {
    return memoryUsageReportedZero;
  }

  /**
   * Returns the number of bytes garbage collected during this invocation. Broken down by GC space.
   */
  public synchronized ImmutableMap<String, Long> getGarbageStats() {
    return ImmutableSortedMap.copyOf(garbageStats);
  }

  public synchronized void reset() {
    peakHeapTotal = Optional.empty();
    peakHeapTenuredSpace = Optional.empty();
    memoryUsageReportedZero = false;
    garbageStats = new HashMap<>();
  }

  private synchronized void updatePostGCHeapMemoryUsed(
      long usedTotal, long usedTenuredSpace, long timestampMillis) {
    if (!peakHeapTotal.isPresent() || usedTotal > peakHeapTotal.get().bytes()) {
      peakHeapTotal = Optional.of(PeakHeap.create(usedTotal, timestampMillis));
    }
    if (!peakHeapTenuredSpace.isPresent()
        || usedTenuredSpace > peakHeapTenuredSpace.get().bytes()) {
      peakHeapTenuredSpace = Optional.of(PeakHeap.create(usedTenuredSpace, timestampMillis));
    }
  }

  private synchronized void updateMemoryUsageReportedZero(boolean value) {
    memoryUsageReportedZero = value;
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (!notification
        .getType()
        .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
      return;
    }

    GarbageCollectionNotificationInfo info =
        GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

    Map<String, Long> gcBefore = new HashMap<>();
    GcInfo gcInfo = info.getGcInfo();
    for (Map.Entry<String, MemoryUsage> memoryUsage : gcInfo.getMemoryUsageBeforeGc().entrySet()) {
      String kind = memoryUsage.getKey();
      gcBefore.put(kind, memoryUsage.getValue().getUsed());
    }
    synchronized (this) {
      for (Map.Entry<String, MemoryUsage> memoryUsage : gcInfo.getMemoryUsageAfterGc().entrySet()) {
        String kind = memoryUsage.getKey();
        long before = gcBefore.containsKey(kind) ? gcBefore.get(kind) : 0;
        long diff = before - memoryUsage.getValue().getUsed();
        // The difference is potentially negative when the JVM propagates objects from one GC space
        // to another. Discard these cases.
        if (diff > 0) {
          garbageStats.compute(kind, (k, v) -> v == null ? diff : v + diff);
        }
      }
    }

    if (wasStopTheWorldGc(info)) {
      long durationNs = info.getGcInfo().getDuration() * 1_000_000;
      long end = Profiler.nanoTimeMaybe();
      Profiler.instance()
          .logSimpleTask(
              end - durationNs,
              end,
              ProfilerTask.HANDLE_GC_NOTIFICATION,
              info.getGcAction().replaceFirst("^end of ", ""));
    }
    if (!GarbageCollectionMetricsUtils.isFullGc(info)) {
      return;
    }

    long usedTotal = 0;
    long usedTenuredSpace = 0;
    int tenuredSpaceEventCount = 0;
    for (Map.Entry<String, MemoryUsage> memoryUsageEntry :
        info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
      if (GarbageCollectionMetricsUtils.isTenuredSpace(memoryUsageEntry.getKey())) {
        usedTenuredSpace = memoryUsageEntry.getValue().getUsed();
        tenuredSpaceEventCount++;
      }
      usedTotal += memoryUsageEntry.getValue().getUsed();
    }
    if (tenuredSpaceEventCount > 1) {
      bugReporter.sendBugReport(
          new IllegalStateException(
              "More than one tenured space event was recorded during garbage collection."));
    }
    Map<String, MemoryUsage> mem = info.getGcInfo().getMemoryUsageAfterGc();
    updatePostGCHeapMemoryUsed(usedTotal, usedTenuredSpace, notification.getTimeStamp());
    if (usedTotal > 0) {
      logger.atInfo().log(
          "Memory use after full GC: %d total, %d tenured", usedTotal, usedTenuredSpace);
    } else {
      logger.atInfo().log(
          "Amount of memory used after GC incorrectly reported as %d by JVM with values %s",
          usedTotal, mem);
      updateMemoryUsageReportedZero(true);
    }
  }

  /** Module to support "blaze info peak-heap-size". */
  public static final class PostGCMemoryUseRecorderModule extends BlazeModule {
    @Override
    public void serverInit(OptionsParsingResult startupOptions, ServerBuilder builder) {
      builder.addInfoItems(new PeakMemInfoItem());
    }

    @Override
    public void beforeCommand(CommandEnvironment env) {
      if (!env.getCommandName().equals("info")) {
        PostGCMemoryUseRecorder.get().reset();
      }
    }
  }

  private static final class PeakMemInfoItem extends InfoItem {
    PeakMemInfoItem() {
      super(
          "peak-heap-size",
          "The peak amount of used memory in bytes after any full GC during the most recent"
              + " invocation.",
          /* hidden= */ true);
    }

    @Override
    public byte[] get(
        Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env) {
      return PostGCMemoryUseRecorder.get()
          .getPeakPostGcHeap()
          .map(peak -> print(StringUtilities.prettyPrintBytes(peak.bytes())))
          .orElseGet(() -> print("unknown"));
    }
  }

  /** Module to run a full GC after a build is complete on a Blaze server. * */
  public static final class GcAfterBuildModule extends BlazeModule {

    private boolean forceGc = false;

    /** Command options for forcing a GC after a build. * */
    public static class Options extends OptionsBase {
      @Option(
          name = "experimental_force_gc_after_build",
          defaultValue = "false",
          documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
          effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
          help =
              "If true calls System.gc() after a build to try and get a post-gc peak heap"
                  + " measurement.")
      public boolean experimentalForceGcAfterBuild;
    }

    @Override
    public ImmutableList<Class<? extends OptionsBase>> getCommonCommandOptions() {
      return ImmutableList.of(Options.class);
    }

    @Override
    public void beforeCommand(CommandEnvironment env) {
      Options options = env.getOptions().getOptions(Options.class);
      if (options != null
          && ("test".equals(env.getCommand().name()) || "build".equals(env.getCommand().name()))) {
        forceGc = options.experimentalForceGcAfterBuild;
      } else {
        forceGc = false;
      }
    }

    @Override
    public void afterCommand() {
      if (forceGc && !PostGCMemoryUseRecorder.get().getPeakPostGcHeap().isPresent()) {
        System.gc();
      }
    }
  }

  private static boolean wasStopTheWorldGc(GarbageCollectionNotificationInfo info) {
    // Weak heuristic to determine if this was a STW gc.
    return !"No GC".equals(info.getGcCause());
  }
}
