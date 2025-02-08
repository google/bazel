// Copyright 2022 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.profiler.Profiler.TaskData;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** Writes the profile in Json Trace file format. */
class JsonTraceFileWriter implements Runnable {
  protected final Queue<TraceData> queue;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition condition = lock.newCondition();
  // 1_000_000 is a randomly chosen value that is large enough to ensure that:
  //   1. If the speed of producers is slower than consumer (normal cases), they don't get overhead
  //      on posting new events.
  //   2. Otherwise (e.g. with --noslim_profile and --record_full_profiler_data), it eventually
  //      slowed down the producers to avoid OOM.
  private final Semaphore availableEventSlots = new Semaphore(1_000_000);
  protected final Thread thread;
  protected IOException savedException;

  private final OutputStream outStream;
  private final long profileStartTimeNanos;
  private final ThreadLocal<Boolean> metadataPosted = ThreadLocal.withInitial(() -> Boolean.FALSE);
  private final boolean slimProfile;
  private final UUID buildID;
  private final String outputBase;

  private static final long SLIM_PROFILE_EVENT_THRESHOLD = 10_000;
  private static final long SLIM_PROFILE_MAXIMAL_PAUSE_NS = Duration.ofMillis(100).toNanos();
  private static final long SLIM_PROFILE_MAXIMAL_DURATION_NS = Duration.ofMillis(250).toNanos();

  private static final TaskData POISON_PILL =
      new TaskData(
          /* threadId= */ 0, /* startTimeNanos= */ 0, /* eventType= */ null, "poison pill");

  JsonTraceFileWriter(
      OutputStream outStream,
      long profileStartTimeNanos,
      boolean slimProfile,
      String outputBase,
      UUID buildID) {
    this.queue = new ConcurrentLinkedQueue<>();
    this.thread = new Thread(this, "profile-writer-thread");
    this.outStream = outStream;
    this.profileStartTimeNanos = profileStartTimeNanos;
    this.slimProfile = slimProfile;
    this.buildID = buildID;
    this.outputBase = outputBase;
  }

  public void shutdown() throws IOException {
    // Add poison pill to queue and then wait for writer thread to shut down.
    queue.add(POISON_PILL);
    notifyConsumer(/* force= */ true);

    try {
      thread.join();
    } catch (InterruptedException e) {
      thread.interrupt();
      Thread.currentThread().interrupt();
    }
    if (savedException != null) {
      throw savedException;
    }
  }

  public void start() {
    thread.start();
  }

  public void enqueue(TraceData data) {
    // We assign a virtual lane for virtual thread and the metadata for the virtual lane is posted
    // at creation time.
    if (!Thread.currentThread().isVirtual() && !metadataPosted.get()) {
      metadataPosted.set(Boolean.TRUE);
      availableEventSlots.acquireUninterruptibly(2);
      queue.add(new ThreadMetadata());
    } else {
      availableEventSlots.acquireUninterruptibly();
    }
    queue.add(data);
    // Not forcing notification to avoid blocking on the lock. This might cause this signal fail to
    // be sent if the consumer is holding the lock -- either it is consuming the event queue or
    // starting to wait on the condition. For the former case, it's fine. For the latter case, we
    // will fail to notify the consumer, but the assumption is that we have events in continuous so
    // that the next event can notify the consumer.
    notifyConsumer(/* force= */ false);
  }

  private static final class MergedEvent {
    int count = 0;
    long startTimeNanos;
    long endTimeNanos;
    TaskData data;

    /*
     * Tries to merge an additional event, i.e. if the event is close enough to the already merged
     * event.
     *
     * Returns null, if merging was possible.
     * If not mergeable, returns the TaskData of the previously merged events and clears the
     * internal data structures.
     */
    @Nullable
    TaskData maybeMerge(TaskData data) {
      long startTimeNanos = data.startTimeNanos;
      long endTimeNanos = startTimeNanos + data.durationNanos;
      if (count > 0 && startTimeNanos >= this.startTimeNanos && endTimeNanos <= this.endTimeNanos) {
        // Skips child tasks.
        return null;
      }
      if (count == 0) {
        this.data = data;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        count++;
        return null;
      } else if (startTimeNanos <= this.endTimeNanos + SLIM_PROFILE_MAXIMAL_PAUSE_NS) {
        this.endTimeNanos = endTimeNanos;
        count++;
        return null;
      } else {
        TaskData ret = getAndReset();
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.data = data;
        count = 1;
        return ret;
      }
    }

    // Returns a TaskData object representing the merged data and clears internal data structures.
    TaskData getAndReset() {
      TaskData ret;
      if (data == null || count <= 1) {
        ret = data;
      } else {
        ret =
            new TaskData(
                data.threadId,
                this.startTimeNanos,
                this.endTimeNanos - this.startTimeNanos,
                "merged " + count + " events");
      }
      count = 0;
      data = null;
      return ret;
    }
  }

  private static boolean isCandidateForMerging(TaskData data) {
    return data.durationNanos > 0
        && data.durationNanos < SLIM_PROFILE_MAXIMAL_DURATION_NS
        && data.type != ProfilerTask.CRITICAL_PATH_COMPONENT;
  }

  private void notifyConsumer(boolean force) {
    boolean locked;
    if (force) {
      lock.lock();
      locked = true;
    } else {
      locked = lock.tryLock();
    }
    if (locked) {
      try {
        condition.signal();
      } finally {
        lock.unlock();
      }
    }
  }

  @GuardedBy("lock")
  private TraceData takeData() throws InterruptedException {
    TraceData data;
    while ((data = queue.poll()) == null) {
      condition.await();
    }
    availableEventSlots.release();
    return data;
  }

  /**
   * Saves all gathered information from taskQueue queue to the file. Method is invoked internally
   * by the Timer-based thread and at the end of profiling session.
   */
  @Override
  public void run() {
    lock.lock();
    try {
      boolean receivedPoisonPill = false;
      try (JsonWriter writer =
          new JsonWriter(
              // The buffer size of 262144 is chosen at random.
              // Bazel internally stores strings as raw bytes encoded in ISO_8859_1, so we use the
              // same encoding here to also write out raw bytes.
              new OutputStreamWriter(new BufferedOutputStream(outStream, 262144), ISO_8859_1))) {
        var startDate = Instant.now();
        writer.beginObject();
        writer.name("otherData");
        writer.beginObject();
        writer.name("bazel_version").value(BlazeVersionInfo.instance().getReleaseName());
        writer.name("build_id").value(buildID.toString());
        writer.name("output_base").value(outputBase);
        writer.name("date").value(startDate.toString());
        writer.name("profile_start_ts").value(startDate.toEpochMilli());
        writer.endObject();
        writer.name("traceEvents");
        writer.beginArray();

        // Generate metadata event for the critical path as thread 0 in disguise.
        ThreadMetadata criticalPathMetadata =
            ThreadMetadata.createFakeThreadMetadataForCriticalPath();
        criticalPathMetadata.writeTraceData(writer, profileStartTimeNanos);

        HashMap<Long, MergedEvent> eventsPerThread = new HashMap<>();
        int eventCount = 0;
        TraceData data;
        while ((data = takeData()) != POISON_PILL) {
          Preconditions.checkNotNull(data);
          eventCount++;

          if (slimProfile
              && eventCount > SLIM_PROFILE_EVENT_THRESHOLD
              && data instanceof TaskData taskData
              && isCandidateForMerging((TaskData) data)) {
            eventsPerThread.putIfAbsent(taskData.threadId, new MergedEvent());
            TaskData mergedTaskData = eventsPerThread.get(taskData.threadId).maybeMerge(taskData);
            if (mergedTaskData != null) {
              mergedTaskData.writeTraceData(writer, profileStartTimeNanos);
            }
          } else {
            data.writeTraceData(writer, profileStartTimeNanos);
          }
        }
        for (JsonTraceFileWriter.MergedEvent value : eventsPerThread.values()) {
          TaskData taskData = value.getAndReset();
          if (taskData != null) {
            taskData.writeTraceData(writer, profileStartTimeNanos);
          }
        }
        receivedPoisonPill = true;
        writer.setIndent("  ");
        writer.endArray();
        writer.endObject();
      } catch (IOException e) {
        this.savedException = e;
        if (!receivedPoisonPill) {
          while (takeData() != POISON_PILL) {
            // We keep emptying the queue, but we can't write anything.
          }
        }
      }
    } catch (InterruptedException e) {
      // Exit silently.
    } finally {
      lock.unlock();
    }
  }
}
