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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.round;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * A meter used to limit the number of concurrent actions. Use {@link #acquireUninterruptibly()}
 * before executing the action and use {@link #release()} after the action is completed.
 *
 * <p>The meter is initialized with {@code minActiveAction} and {@code maxActiveAction}. At any
 * given time, the meter makes sure {@link #acquireUninterruptibly()} returns immediately if the
 * current number of concurrent actions is less than {@code minActiveAction}, or waits until it is
 * below {@code maxActiveAction} after other threads call {@link #release()}.
 *
 * <p>When the current number of concurrent actions is between {@code minActiveAction} and {@code
 * maxActiveAction}, the meter measures current heap memory usage to determine whether {@link
 * #acquireUninterruptibly()} should wait based on a heuristic algorithm:
 *
 * <ul>
 *   <li>Since Java is a GC language, before a GC event, Bazel can only allocate memories.
 *   <li>Assuming during execution phrase, the majority of memory allocations are for action
 *       execution.
 *   <li>Assuming after the action is completed, the majority of memory allocations by that action
 *       can be collected.
 *   <li>The meter tracks the number of completed actions between GC events, it also knows how much
 *       memory was collected by a GC event. Thus it can estimate how much memory is used by an
 *       action.
 *   <li>Based on the memory usage and the remaining size of the heap, the meter can estimate how
 *       many actions can be executed before next GC.
 *   <li>It uses {@code minActiveAction} to make sure the execution phrase can make progress in case
 *       it over-estimates the memory usage. This can happen with skymeld when execution phrase is
 *       mixed with analysis phrase so that the first assumption is wrong.
 *   <li>In case of under-estimate, it will mostly bounded by {@code maxActiveAction} and the next
 *       GC event should adjust the estimation.
 * </ul>
 *
 * <p>If {@code minActiveAction} is equal to {@code maxActiveAction}, the meter behaves like a
 * {@link Semaphore} whose permits is initialized to {@code maxActiveAction}.
 */
public class ActionConcurrencyMeter implements NotificationListener {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition cond = lock.newCondition();
  private final AtomicInteger activeAction = new AtomicInteger(0);
  private final AtomicInteger maxTotalActionSinceLastGc = new AtomicInteger(0);
  private final AtomicInteger totalActionSinceLastGc = new AtomicInteger(0);
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  private final MemoryMXBean memoryBean;
  private final ImmutableList<GarbageCollectorMXBean> garbageCollectorBeans;
  private final int minActiveAction;
  private final Semaphore maxActiveActionSemaphore;
  private final boolean enabled;

  public ActionConcurrencyMeter(int minActiveAction, int maxActiveAction) {
    this(
        ManagementFactory.getMemoryMXBean(),
        ManagementFactory.getGarbageCollectorMXBeans(),
        minActiveAction,
        maxActiveAction);
  }

  private ActionConcurrencyMeter(
      MemoryMXBean memoryBean,
      Iterable<GarbageCollectorMXBean> mxBeans,
      int minActiveAction,
      int maxActiveAction) {
    checkArgument(minActiveAction > 0);
    checkArgument(minActiveAction <= maxActiveAction);

    this.garbageCollectorBeans = ImmutableList.copyOf(mxBeans);
    this.memoryBean = memoryBean;
    this.minActiveAction = minActiveAction;
    this.maxActiveActionSemaphore = new Semaphore(maxActiveAction);
    this.enabled = maxActiveAction > minActiveAction;

    if (enabled) {
      for (var mxBean : this.garbageCollectorBeans) {
        ((NotificationEmitter) mxBean).addNotificationListener(this, null, null);
      }
    }
  }

  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      throw new IllegalStateException("Already stopped");
    }
    if (enabled) {
      for (var mxBean : garbageCollectorBeans) {
        try {
          ((NotificationEmitter) mxBean).removeNotificationListener(this);
        } catch (ListenerNotFoundException e) {
          throw new AssertionError("Unexpected ListenerNotFoundException", e);
        }
      }
    }
  }

  /** Acquire a permit to execute an action, blocking until one is available. */
  public void acquireUninterruptibly() {
    checkState(!stopped.get(), "Already stopped");

    maxActiveActionSemaphore.acquireUninterruptibly();

    // If current number of active actions exceeds the min watermark, queue the action.
    if (activeAction.incrementAndGet() > minActiveAction) {
      activeAction.decrementAndGet();

      lock.lock();
      try {
        // Queue the action until:
        //    1. number of active actions is below the min watermark, or
        //    2. we are allowed to schedule more actions based on memory estimation.
        while (true) {
          int currentActiveAction = activeAction.incrementAndGet();
          if (currentActiveAction <= minActiveAction) {
            break;
          }

          if (enabled && totalActionSinceLastGc.get() < maxTotalActionSinceLastGc.get()) {
            break;
          }

          activeAction.decrementAndGet();
          cond.awaitUninterruptibly();
        }
      } finally {
        lock.unlock();
      }
    }

    totalActionSinceLastGc.incrementAndGet();
  }

  /**
   * Releases a permit, allowing other threads blocking on {@link #acquireUninterruptibly()} to
   * continue.
   */
  public void release() {
    // If current number of active actions is below the minimal watermark, wake up one action in the
    // queue.
    if (activeAction.decrementAndGet() < minActiveAction) {
      lock.lock();
      try {
        cond.signal();
      } finally {
        lock.unlock();
      }
    }

    maxActiveActionSemaphore.release();
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (!notification
        .getType()
        .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
      return;
    }

    long collectedMemoryBytes = 0;

    var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
    var gcInfo = info.getGcInfo();
    Map<String, Long> usedMemoryUsageBeforeGc = new HashMap<>();
    for (var entry : gcInfo.getMemoryUsageBeforeGc().entrySet()) {
      usedMemoryUsageBeforeGc.put(entry.getKey(), entry.getValue().getUsed());
    }
    for (var entry : gcInfo.getMemoryUsageAfterGc().entrySet()) {
      Long before = usedMemoryUsageBeforeGc.remove(entry.getKey());
      if (before == null) {
        before = 0L;
      }
      collectedMemoryBytes += before - entry.getValue().getUsed();
    }
    for (var entry : usedMemoryUsageBeforeGc.entrySet()) {
      collectedMemoryBytes += entry.getValue();
    }

    // Ignore this GC event if no memory is collected.
    if (collectedMemoryBytes <= 0) {
      return;
    }

    long heapMemoryUsedBytes;
    long heapMemoryMaxBytes;
    try {
      var heapMemoryUsage = memoryBean.getHeapMemoryUsage();
      heapMemoryUsedBytes = heapMemoryUsage.getUsed();
      heapMemoryMaxBytes = heapMemoryUsage.getMax();
      if (heapMemoryMaxBytes < 0) {
        heapMemoryMaxBytes = heapMemoryUsage.getCommitted();
      }
    } catch (IllegalArgumentException e) {
      // The JVM may report committed > max. See b/180619163.
      return;
    }

    // Leave some headroom in case of underestimation to avoid triggering too many GCs. 0.8 is an
    // arbitrary chosen value.
    double heapMemoryMaxBytesRatio = 0.8;
    long heapMemoryAvailableBytes =
        max(0, round((double) heapMemoryMaxBytes * heapMemoryMaxBytesRatio - heapMemoryUsedBytes));

    int currentActiveAction = activeAction.get();
    // currentActiveAction might be out of sync with activeAction, but it's fine for our purpose:
    // it's an estimation anyway.
    int doneAction = totalActionSinceLastGc.getAndSet(currentActiveAction) - currentActiveAction;
    int additionalActions = 0;
    double estimatedBytesPerAction = 0;
    if (doneAction > 0) {
      estimatedBytesPerAction = ((double) collectedMemoryBytes / doneAction);
      double estimatedAdditionalActions = heapMemoryAvailableBytes / estimatedBytesPerAction;
      additionalActions = (int) estimatedAdditionalActions;
    }
    int newMaxTotalActionSinceLastGc = currentActiveAction + additionalActions;
    maxTotalActionSinceLastGc.set(newMaxTotalActionSinceLastGc);

    lock.lock();
    try {
      cond.signalAll();
    } finally {
      lock.unlock();
    }

    logger.atInfo().log(
        "Collected %.1f MB memory over %s actions, %.1f MB / action",
        (double) collectedMemoryBytes / 1024 / 1024,
        doneAction,
        estimatedBytesPerAction / 1024 / 1024);
  }
}
