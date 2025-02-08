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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.concurrent.ConcurrentFifo.CAPACITY;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress;
import static com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;

import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.concurrent.ConcurrentFifo.ElementWithSkippedAppends;
import com.google.devtools.build.lib.unsafe.UnsafeProvider;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import sun.misc.Unsafe;

@RunWith(TestParameterInjector.class)
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
public final class ConcurrentFifoTest {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final int PARALLELISM = 10;

  private final ForkJoinPool executor = new ForkJoinPool(PARALLELISM);

  private long baseAddress;
  private long sizeAddress;
  private long appendIndexAddress;
  private long takeIndexAddress;

  private ConcurrentFifo<Runnable> queue;

  @Before
  public void setUp() {
    baseAddress = createPaddedBaseAddress(/* count= */ 3);
    sizeAddress = getAlignedAddress(baseAddress, /* offset= */ 0);
    appendIndexAddress = getAlignedAddress(baseAddress, /* offset= */ 1);
    takeIndexAddress = getAlignedAddress(baseAddress, /* offset= */ 2);
    queue = new ConcurrentFifo<>(Runnable.class, sizeAddress, appendIndexAddress, takeIndexAddress);
  }

  // TODO: b/386384684 - remove Unsafe usage
  @After
  public void freeMemory() {
    UNSAFE.freeMemory(baseAddress);
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void queue_initializesAddresss() {
    assertThat(UNSAFE.getInt(sizeAddress)).isEqualTo(0);
    assertThat(UNSAFE.getInt(appendIndexAddress)).isEqualTo(0);
    assertThat(UNSAFE.getInt(takeIndexAddress)).isEqualTo(0);
  }

  /**
   * Sets the starting address to ensure certain corner cases are exercised.
   *
   * <p>The queue isn't sensitive to the starting address as long as append and take start at the
   * same value.
   */
  private enum StartingAddressParameter {
    /** Does the queue work with default values? */
    ZERO(0),
    /** Does the queue work when overflowing positive values? */
    MAX_INT(Integer.MAX_VALUE),
    /** Does the queue work when overflowing unsigned integers? */
    ALL_ONES(0xFFFF_FFFF); // -1.

    private final int value;

    private StartingAddressParameter(int value) {
      this.value = value;
    }

    private int value() {
      return value;
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void queue_handlesConcurrentTasks(@TestParameter StartingAddressParameter startingAddress)
      throws InterruptedException {
    UNSAFE.putInt(null, appendIndexAddress, startingAddress.value());
    UNSAFE.putInt(null, takeIndexAddress, startingAddress.value());

    // Count for the inner loop within each thread that performs queue operations. This is
    // deliberately higher than the queue capacity to cover multiple epochs.
    final int inner = CAPACITY + 1;

    var untaken = Sets.<Runnable>newConcurrentHashSet();

    final int workerCount = PARALLELISM / 2; // Workers are either producers or consumers.

    // Each worker performs `inner` operations making the total number of consumer operations
    // `workerCount * inner`.
    CountDownLatch consumersDone = new CountDownLatch(workerCount * inner);
    Semaphore released = new Semaphore(0);
    for (int i = 0; i < workerCount; ++i) {
      int index = i;
      executor.execute(
          () -> {
            for (int j = 0; j < inner; ++j) {
              var task = new TaskWithId(index * inner + j);
              untaken.add(task);
              while (!queue.tryAppend(task)) {}
              released.release();
            }
          });

      executor.execute(
          () -> {
            for (int j = 0; j < inner; ++j) {
              try {
                released.acquire();
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              var task = queue.take();
              if (!untaken.remove(task)) {
                logger.atSevere().log("duplicate %s: %s\n", task, queue);
              }
              consumersDone.countDown();
            }
          });
    }

    if (!consumersDone.await(WAIT_TIMEOUT_SECONDS, SECONDS)) {
      fail("timed out: " + queue);
    }
    assertThat(untaken).isEmpty();
  }

  @Test
  public void queue_restrictsCapacity() {
    for (int i = 0; i < CAPACITY - 1; ++i) {
      assertThat(queue.tryAppend(new TaskWithId(i))).isTrue();
    }
    var task = new TaskWithId(CAPACITY - 1);

    // With CAPACITY-1 tasks added, the queue is full and cannot support any more elements.
    assertThat(queue.size()).isEqualTo(CAPACITY - 1);
    assertThat(queue.tryAppend(task)).isFalse();

    var first = (TaskWithId) queue.take();
    assertThat(first.id).isEqualTo(0);

    assertThat(queue.size()).isEqualTo(CAPACITY - 2);

    // After removing one task, the queue can accept another task again.
    assertThat(queue.tryAppend(task)).isTrue();
  }

  @Test
  public void queue_behavesAfterClear() {
    for (int i = 0; i < CAPACITY - 1; ++i) {
      assertThat(queue.tryAppend(new TaskWithId(i))).isTrue();
    }
    assertThat(queue.size()).isEqualTo(CAPACITY - 1);

    queue.clear();
    assertThat(queue.size()).isEqualTo(0);

    // Fully loads then empties the queue.
    for (int i = 0; i < CAPACITY - 1; ++i) {
      assertThat(queue.tryAppend(new TaskWithId(i + CAPACITY))).isTrue();
    }
    for (int i = 0; i < CAPACITY - 1; ++i) {
      assertThat(((TaskWithId) queue.take()).id).isEqualTo(i + CAPACITY);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void slowAppends_areSkippedByTake_thenUnmarkedByAppends() {
    // This test covers the state machine transitions that handle slow appenders observed by takers.
    // This test stacks two slow appends on the same offset, exposes them to take code then
    // "unwinds" it with real appends applied at those offsets. Descheduling threads is hard to
    // capture without mutilating the code so this fakes a lot of behavior.
    fakeSlowAppend();
    for (int i = 0; i < CAPACITY - 1; ++i) {
      assertThat(queue.tryAppend(new TaskWithId(i))).isTrue();
      assertThat(((TaskWithId) queue.take()).id).isEqualTo(i);
    }
    // The slow append has a skip marker.
    assertThat(queue.getQueueForTesting()[0]).isEqualTo(1);

    // Fakes a 2nd slow append that will eventually become a +2.
    fakeSlowAppend();

    // Does a real append so that take will receive something.
    var testTask = new TaskWithId(1234);
    assertThat(queue.tryAppend(testTask)).isTrue();
    // Take skips over the fake slow append and increments the skip marker.
    assertThat(queue.take()).isEqualTo(testTask);

    // Verifies that the skip marker has been incremented.
    assertThat(queue.getQueueForTesting()[0]).isEqualTo(2);

    // The next section verifies that a real append decrements the skip counter.

    // Fakes completion of the append by setting the index at the correct position and calling
    // tryAppend. The difference between this and having a real descheduled append is the index
    // after execution could be different from 1 + the one it starts on and it won't increment the
    // queue size again. Neither of these matter for this test.
    UNSAFE.putInt(null, appendIndexAddress, 2 * CAPACITY);
    testTask = new TaskWithId(5678);
    assertThat(queue.tryAppend(testTask)).isTrue();
    // Verifies the decrement from 2 down to 1.
    assertThat(queue.getQueueForTesting()[0]).isEqualTo(1);
    // Verifies that the actual append occurs in the next position.
    assertThat(queue.getQueueForTesting()[1]).isEqualTo(testTask);

    // Resets the index and the receiving location of the append and verifies that append decrements
    // from 1 down to null.
    UNSAFE.putInt(null, appendIndexAddress, 2 * CAPACITY);
    queue.getQueueForTesting()[1] = null;

    testTask = new TaskWithId(101);
    assertThat(queue.tryAppend(testTask)).isTrue();
    assertThat(queue.getQueueForTesting()[0]).isNull();
    assertThat(queue.getQueueForTesting()[1]).isEqualTo(testTask);
  }

  // Fakes a slow append by incrementing the size and append indices. These are the only visible
  // side effects of slow appends.
  // TODO: b/386384684 - remove Unsafe usage
  private void fakeSlowAppend() {
    UNSAFE.getAndAddInt(null, sizeAddress, 1);
    UNSAFE.getAndAddInt(null, appendIndexAddress, 1);
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void slowTakes_areSkippedByAppend_thenUnmarkedByTakes() {
    // This test covers the state machine transitions that handle slow takers observed by
    // appenders. Descheduled threads at precise moments is hard to model without mutilating the
    // code so this test fakes a lot of behavior to cover the applicable code paths.

    // Appends an initial task.
    var task0 = new TaskWithId(0);
    assertThat(queue.tryAppend(task0)).isTrue();

    // To simulate a slow take, rewinds the append index and appends again. Ordinarily, take should
    // consume the underlying task before another append.
    UNSAFE.putInt(null, appendIndexAddress, 0);
    var task1 = new TaskWithId(1);
    assertThat(queue.tryAppend(task1)).isTrue();

    // Verifies that append adds a wrapper to the task.
    var wrappedTask = (ElementWithSkippedAppends) queue.getQueueForTesting()[0];
    assertThat(wrappedTask.element()).isEqualTo(task0);
    // Verifies that the skip count is 1.
    assertThat(wrappedTask.skippedAppendCount()).isEqualTo(1);

    // Verifies that append in fact skips to the next index and appends there.
    assertThat(queue.getQueueForTesting()[1]).isEqualTo(task1);

    // Resets the position after the one being tested and rewinds the append index once more.
    queue.getQueueForTesting()[1] = null;
    UNSAFE.putInt(null, appendIndexAddress, 0);

    // Appends yet again (without an intervening take) to simulate a 2nd slow take. This should be
    // incredibly rare in the real world but can happen in theory because there's no certain
    // guarantees on thread scheduling.
    var task2 = new TaskWithId(2);
    assertThat(queue.tryAppend(task2)).isTrue();

    // Verifies that the skip count has been incremented to 2.
    wrappedTask = (ElementWithSkippedAppends) queue.getQueueForTesting()[0];
    assertThat(wrappedTask.element()).isEqualTo(task0);
    assertThat(wrappedTask.skippedAppendCount()).isEqualTo(2);
    // Verifies that the append actually skipped to the next index.
    assertThat(queue.getQueueForTesting()[1]).isEqualTo(task2);

    // The next part of the test verifies that take undoes the wrapping skip counting of append.

    // Take skips to the task in the next position when it observes the wrapper.
    assertThat(queue.take()).isEqualTo(task2);
    wrappedTask = (ElementWithSkippedAppends) queue.getQueueForTesting()[0];
    assertThat(wrappedTask.element()).isEqualTo(task0);
    // Take decrements the skip counter.
    assertThat(wrappedTask.skippedAppendCount()).isEqualTo(1);
    // Verifies that it took the task in the next position out of the queue.
    assertThat(queue.getQueueForTesting()[1]).isNull();

    // Replaces a task in the next position of the queue for take to consume.
    queue.getQueueForTesting()[1] = task2;
    // Resets the take indeox.
    UNSAFE.putInt(null, takeIndexAddress, 0);

    // Take indeed takes the task from the next available position when it sees the wrapper.
    assertThat(queue.take()).isEqualTo(task2);

    // Take has fully unwrapped the element.
    assertThat(queue.getQueueForTesting()[0]).isEqualTo(task0);
  }

  private static class TaskWithId implements Runnable {
    private final int id;

    private TaskWithId(int id) {
      this.id = id;
    }

    @Override
    public void run() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
      return id;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof TaskWithId)) {
        return false;
      }
      return this.id == ((TaskWithId) obj).id;
    }

    @Override
    public String toString() {
      return "T{" + id + "}";
    }
  }

  private static final Unsafe UNSAFE = UnsafeProvider.unsafe();
}
