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
package com.google.devtools.build.lib.concurrent;

import static com.google.common.base.MoreObjects.toStringHelper;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.unsafe.UnsafeProvider;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import sun.misc.Unsafe;

/**
 * A fixed-capacity concurrent FIFO.
 *
 * <p>This class is a higher performance, nearly garbage-free, but less flexible substitute for
 * {@link ConcurrentLinkedQueue},
 *
 * <ul>
 *   <li>The queue capacity is fixed.
 *   <li>The client must guarantee not to take more than it has added.
 *   <li>The client must have an fallback if {@link tryAppend} fails.
 * </ul>
 *
 * <p>This class is inspired by Morrison, Adam, and Yehuda Afek. "Fast concurrent queues for x86
 * processors." Proceedings of the 18th ACM SIGPLAN symposium on Principles and practice of parallel
 * programming. 2013.
 */
// TODO: b/386384684 - remove Unsafe usage
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
class ConcurrentFifo<T> { // Non-final for testing only.
  private static final Integer SKIP_SLOW_APPENDER = 1;

  /** The power of 2 backing array capacity. */
  @VisibleForTesting static final int CAPACITY = 1 << 20;

  /** AND with this mask performs modulo {@link #CAPACITY}. */
  static final int CAPACITY_MASK = CAPACITY - 1;

  /**
   * Maximum number of elements the FIFO can contain, one less than {@link #CAPACITY}.
   *
   * <p>While the backing array's size is a power of 2, this is one less than that, to improve the
   * efficiency of bits used to represent the number of elements enqueued. For example, the number
   * of bits needed to represent the element count for a queue of size 256 is 9, but only 8 bits are
   * needed for a queue of size 255.
   */
  @VisibleForTesting static final int MAX_ELEMENTS = CAPACITY - 1;

  /**
   * Circular buffer containing tasks and skip metadata.
   *
   * <p>The algorithm assigns to each caller of {@link #tryAppend} or {@link #take} a monotonically
   * increasing index (not including {@link #tryAppend} calls that would exceed capacity). {@link
   * #take} cannot be called more times than successful {@link #tryAppend} calls by client
   * restriction. Thus each taker is assigned to a previous appender by matching index.
   *
   * <p>The naive algorithm based on the above would not be lock-free due to slow or descheduled
   * threads. For example, consider a taker assigned to an index where the corresponding appender's
   * thread has been descheduled before writing its task to the queue. When the taker observes its
   * assigned queue position, it does not see a value. The converse scenario is also possible. An
   * appender could see an occupied queue position while expecting {@code null} when a taker on the
   * same cyclic offset from a previous epoch is slow.
   *
   * <p>To resolve these situations, the threads that encounter them actively place a skip marker
   * into the queue that needs to be consumed by its counterpart. A taker that observes a null value
   * places a {@link Integer} {@code 1} to mark it, then skips to the next index. On seeing the
   * marker, the appender decrements it (with {@code 1} transitioning back to {@code null}). The
   * taker, when skipping to the next index, can expect to find a value there because an incomplete
   * append does not count as a successful one so there should be an extra complete append at a
   * subsequent index. Likewise, appenders that have looped all the way around to the same offset,
   * should expect to find an empty queue position due to capacity constraints.
   *
   * <p>Appenders that observe a value when expecting an empty position wrap the value with {@link
   * ElementWithSkippedAppends} then skip to the next available index. Slow takers decrement the
   * counts on the wrappers then skip to the next available index.
   *
   * <p>The skip marker has a count because the number of threads that could potentially be
   * descheduled at a particular index is only limited by the queue capacity, though more than one
   * should be extremely rare.
   *
   * <p>Each queue position contains one of the following.
   *
   * <ul>
   *   <li>{@code null} is an empty position.
   *   <li>{@link T} is a position containing a task.
   *   <li>{@link Integer} is a count of takers that skipped the position because they observed a
   *       null value. The count corresponds to slow appenders at the position.
   *   <li>{@link ElementWithSkippedAppends} is a task with a count of appenders that skipped the
   *       position due to it being still occupied with a task. The count corresponds to slow takers
   *       assigned to the position.
   * </ul>
   *
   * <p>Correctness of the algorithm is straightforward. Anytime a taker skips a position, it adds a
   * count so that the same number of appenders skip that position and vice versa. Therefore the
   * number of takers and appenders skipping any given position stays balanced so the take and
   * append indices stay synchronized.
   */
  private final Object[] queue = new Object[CAPACITY];

  private final Class<? super T> elementType;

  /**
   * Address of int index for appending; incremented by appending.
   *
   * <p>The actual array offset is the value modulo {@link #CAPACITY}.
   */
  private final long appendIndexAddress;

  /**
   * Address of int index for taking; incremented by taking.
   *
   * <p>The actual array offset is the value modulo {@link #CAPACITY}.
   */
  private final long takeIndexAddress;

  /**
   * The queue contains no more than this many tasks.
   *
   * <p>This is incremented before appending and decremented after taking.
   */
  private final long sizeAddress;

  /**
   * Constructor.
   *
   * <p>The caller owns the memory associated with the provided addresses.
   *
   * @param sizeAddress padded location of the {@code int} size of this queue.
   * @param takeIndexAddress padded location of the {@code int} take index.
   * @param appendIndexAddress padded location of the {@code int} append index.
   */
  // TODO: b/386384684 - remove Unsafe usage
  ConcurrentFifo(
      Class<? super T> elementType,
      long sizeAddress,
      long appendIndexAddress,
      long takeIndexAddress) {
    this.elementType = elementType;
    this.sizeAddress = sizeAddress;
    this.appendIndexAddress = appendIndexAddress;
    this.takeIndexAddress = takeIndexAddress;

    // Explicitly initializes the memory at the provided addresses.
    UNSAFE.putInt(null, sizeAddress, 0);
    UNSAFE.putInt(null, appendIndexAddress, 0);
    UNSAFE.putInt(null, takeIndexAddress, 0);
  }

  /**
   * Tries to insert a task into the queue.
   *
   * @return true if successful, false if it would have exceeded the capacity.
   */
  // TODO: b/386384684 - remove Unsafe usage
  boolean tryAppend(T task) {
    // Optimistically increases size, and rolls back if it exceeds capacity.
    if (UNSAFE.getAndAddInt(null, sizeAddress, 1) >= MAX_ELEMENTS) {
      UNSAFE.getAndAddInt(null, sizeAddress, -1);
      return false;
    }

    do {
      int offset = getQueueOffset(UNSAFE.getAndAddInt(null, appendIndexAddress, 1));
      // In the common case, we can avoid an extra read.
      if (UNSAFE.compareAndSwapObject(queue, offset, null, task)) {
        return true;
      }
      do {
        // A plain read is sufficient here because this is always preceded by a failed CAS of the
        // same memory location.
        Object snapshot = UNSAFE.getObject(queue, offset);
        // It's possible that the taker outraced the snapshot above.
        if (snapshot == null) {
          if (UNSAFE.compareAndSwapObject(queue, offset, null, task)) {
            return true;
          }
          continue; // Refreshes the snapshot.
        }
        // There's some slowness that has to be resolved.
        Object target;
        if (snapshot instanceof Integer) {
          // There were previous takes without corresponding appends. Acknowledges a taker that
          // skipped this offset.
          int newCount = ((Integer) snapshot) - 1;
          target = newCount == 0 ? null : newCount;
        } else if (elementType.isInstance(snapshot)) {
          // A taker was slow.
          @SuppressWarnings("unchecked")
          T castSnapshot = (T) snapshot;
          target = new ElementWithSkippedAppends<T>(castSnapshot, /* skippedAppendCount= */ 1);
        } else {
          // Multiple takers are slow. This should be very rare. Increments the skip count.
          target = ((ElementWithSkippedAppends) snapshot).incrementSkips();
        }
        if (UNSAFE.compareAndSwapObject(queue, offset, snapshot, target)) {
          break; // Success, skips to next.
        } // Otherwise refreshes the snapshot.
      } while (true);
    } while (true);
  }

  /**
   * Takes an available task.
   *
   * <p>This must not be called more times than {@link #tryAppend} has succeeded.
   */
  // TODO: b/386384684 - remove Unsafe usage
  T take() {
    do {
      int offset = getQueueOffset(UNSAFE.getAndAddInt(null, takeIndexAddress, 1));
      do {
        // A plain read is sufficient here.
        // 1. The initial read is supported by the client. In most cases, the client establishes the
        //    necessary happens-before relationship in honoring the constraint of no more takes than
        //    successful appends.
        // 2. On subsequent reads, this immediately follows a failed CAS of the same memory
        //    location, which refreshes the memory.
        Object snapshot = UNSAFE.getObject(queue, offset);
        if (elementType.isInstance(snapshot)) {
          // Attempts to take ownership of the task.
          if (UNSAFE.compareAndSwapObject(queue, offset, snapshot, null)) {
            UNSAFE.getAndAddInt(null, sizeAddress, -1);
            @SuppressWarnings("unchecked")
            T castSnapshot = (T) snapshot;
            return castSnapshot;
          }
        } else {
          Object target;
          if (snapshot == null) {
            target = SKIP_SLOW_APPENDER;
          } else if (snapshot instanceof Integer) {
            // Increments the count due to multiple slow appenders, which should be very rare.
            target = ((Integer) snapshot).intValue() + 1;
          } else {
            // There have been appends without corresponding takes. Acknowledges one skip.
            target = ((ElementWithSkippedAppends) snapshot).decrementSkips();
          }
          if (UNSAFE.compareAndSwapObject(queue, offset, snapshot, target)) {
            break; // Success, skips to next.
          } // Otherwise refreshes the snapshot.
        }
      } while (true);
    } while (true);
  }

  // TODO: b/386384684 - remove Unsafe usage
  int size() {
    return UNSAFE.getIntVolatile(null, sizeAddress);
  }

  // TODO: b/386384684 - remove Unsafe usage
  void clear() {
    UNSAFE.putInt(null, sizeAddress, 0);
    UNSAFE.putInt(null, appendIndexAddress, 0);
    UNSAFE.putInt(null, takeIndexAddress, 0);
    Arrays.fill(queue, null);
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public String toString() {
    int appendIndex = UNSAFE.getIntVolatile(null, appendIndexAddress);
    int takeIndex = UNSAFE.getIntVolatile(null, takeIndexAddress);
    var helper =
        toStringHelper(this)
            .add("size", UNSAFE.getIntVolatile(null, sizeAddress))
            .add("appendIndex", String.format("%d (%d)", appendIndex, appendIndex & CAPACITY_MASK))
            .add("takeIndex", String.format("%d (%d)", takeIndex, takeIndex & CAPACITY_MASK));
    StringBuilder buf = new StringBuilder("[");
    for (int i = 0; i < CAPACITY; ++i) {
      if (i > 0) {
        buf.append(',');
      }
      if (i % 10 == 0) {
        buf.append(i).append(':');
      }
      var elt = queue[i];
      if (elt == null) {
        buf.append('0');
      } else if (elementType.isInstance(elt)) {
        buf.append('1');
      } else if (elt instanceof Integer) {
        buf.append('S').append(elt);
      } else {
        buf.append('T').append(((ElementWithSkippedAppends) elt).skippedAppendCount());
      }
    }
    helper.add("queue", buf.append(']').toString());
    return helper.toString();
  }

  @VisibleForTesting
  Object[] getQueueForTesting() {
    return queue;
  }

  private static int getQueueOffset(int index) {
    return ELEMENTS_BASE + ELEMENTS_SCALE * (index & CAPACITY_MASK);
  }

  @VisibleForTesting
  record ElementWithSkippedAppends<T>(T element, int skippedAppendCount) {

    private Object decrementSkips() {
      if (skippedAppendCount <= 1) {
        return element;
      }
      return new ElementWithSkippedAppends<>(element, skippedAppendCount - 1);
    }

    private ElementWithSkippedAppends<T> incrementSkips() {
      return new ElementWithSkippedAppends<T>(element, skippedAppendCount + 1);
    }
  }

  private static final Unsafe UNSAFE = UnsafeProvider.unsafe();

  private static final int ELEMENTS_BASE = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
  private static final int ELEMENTS_SCALE = Unsafe.ARRAY_OBJECT_INDEX_SCALE;
}
