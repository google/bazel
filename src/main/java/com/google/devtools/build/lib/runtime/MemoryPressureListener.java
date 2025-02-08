// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.metrics.GarbageCollectionMetricsUtils;
import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

@ThreadSafe
final class MemoryPressureListener implements NotificationListener {

  private final AtomicReference<EventBus> eventBus = new AtomicReference<>();
  private final AtomicReference<GcThrashingDetector> gcThrashingDetector = new AtomicReference<>();
  private final Executor executor;

  private MemoryPressureListener(Executor executor) {
    this.executor = executor;
  }

  static MemoryPressureListener create() {
    return createFromBeans(
        ManagementFactory.getGarbageCollectorMXBeans(),
        // Use a dedicated thread to broadcast memory pressure events. The service thread that calls
        // handleNotification for GC events is not a typical Java thread - it doesn't work with
        // debugger breakpoints and may not show up in thread dumps.
        Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("memory-pressure-listener-%d").build()));
  }

  @VisibleForTesting
  static MemoryPressureListener createFromBeans(
      List<GarbageCollectorMXBean> gcBeans,
      Executor executor) {
    ImmutableList<NotificationEmitter> tenuredGcEmitters = findTenuredCollectorBeans(gcBeans);
    if (tenuredGcEmitters.isEmpty()) {
      var names =
          gcBeans.stream()
              .map(GarbageCollectorMXBean::getMemoryPoolNames)
              .map(Arrays::asList)
              .collect(toImmutableList());
      throw new IllegalStateException(
          String.format(
              "Unable to find tenured collector from %s: names were %s.", gcBeans, names));
    }

    MemoryPressureListener memoryPressureListener = new MemoryPressureListener(executor);
    tenuredGcEmitters.forEach(e -> e.addNotificationListener(memoryPressureListener, null, null));
    return memoryPressureListener;
  }

  @VisibleForTesting
  static ImmutableList<NotificationEmitter> findTenuredCollectorBeans(
      List<GarbageCollectorMXBean> gcBeans) {
    ImmutableList.Builder<NotificationEmitter> builder = ImmutableList.builder();
    // Examine all collectors and register for notifications from those which collect the tenured
    // space. Normally there is one such collector.
    for (GarbageCollectorMXBean gcBean : gcBeans) {
      for (String name : gcBean.getMemoryPoolNames()) {
        if (GarbageCollectionMetricsUtils.isTenuredSpace(name)) {
          builder.add((NotificationEmitter) gcBean);
        }
      }
    }
    return builder.build();
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (!notification
        .getType()
        .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
      return;
    }

    GarbageCollectionNotificationInfo gcInfo =
        GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

    long tenuredSpaceUsedBytes = 0L;
    long tenuredSpaceMaxBytes = 0L;
    for (Map.Entry<String, MemoryUsage> memoryUsageEntry :
        gcInfo.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
      if (!GarbageCollectionMetricsUtils.isTenuredSpace(memoryUsageEntry.getKey())) {
        continue;
      }
      MemoryUsage space = memoryUsageEntry.getValue();
      if (space.getMax() == 0L) {
        // The collector sometimes passes us nonsense stats.
        continue;
      }
      tenuredSpaceUsedBytes = space.getUsed();
      tenuredSpaceMaxBytes = space.getMax();
      break;
    }
    if (tenuredSpaceMaxBytes == 0L) {
      return;
    }

    MemoryPressureEvent event =
        MemoryPressureEvent.newBuilder()
            .setWasManualGc(gcInfo.getGcCause().equals("System.gc()"))
            .setWasGcLockerInitiatedGc(gcInfo.getGcCause().equals("GCLocker Initiated GC"))
            .setWasFullGc(GarbageCollectionMetricsUtils.isFullGc(gcInfo))
            .setTenuredSpaceUsedBytes(tenuredSpaceUsedBytes)
            .setTenuredSpaceMaxBytes(tenuredSpaceMaxBytes)
            .build();
    executor.execute(() -> broadcast(event));
  }

  private void broadcast(MemoryPressureEvent event) {
    GcThrashingDetector gcThrashingDetector = this.gcThrashingDetector.get();
    if (gcThrashingDetector != null) {
      // Invoke the GcThrashingDetector directly instead of through the EventBus. This is because
      // the point of GcThrashingDetector is to [conditionally] crash Blaze, but if we crash in a
      // EventBus subscriber that means that CrashEvent and CommandCompleteEvent posted by
      // BugReporter#handleCrash never get handled because EventBus defers recursive posts until
      // after the posting subscriber has returned, but GcThrashingDetector halts the JVM and never
      // returns.
      gcThrashingDetector.handle(event);
    }

    // A null EventBus implies memory pressure event between commands with no active EventBus.
    EventBus eventBus = this.eventBus.get();
    if (eventBus != null) {
      eventBus.post(event);
    }
  }

  void setEventBus(@Nullable EventBus eventBus) {
    this.eventBus.set(eventBus);
  }

  void setGcThrashingDetector(@Nullable GcThrashingDetector gcThrashingDetector) {
    this.gcThrashingDetector.set(gcThrashingDetector);
  }
}
