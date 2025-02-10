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
package com.google.devtools.build.lib.concurrent;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.concurrent.RequestBatcher.RequestResponse;
import com.google.devtools.build.lib.unsafe.UnsafeProvider;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import sun.misc.Unsafe;

@RunWith(JUnit4.class)
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
public final class RequestBatcherTest {
  private static final Cleaner cleaner = Cleaner.create();

  @Test
  public void simpleSubmit_executes() throws Exception {
    // This covers Step 1A in the documentation.
    var batcher =
        RequestBatcher.<Request, Response>create(
            commonPool(),
            requests -> immediateFuture(respondTo(requests)),
            /* targetWorkerCount= */ 1);
    ListenableFuture<Response> response = batcher.submit(new Request(1));
    assertThat(response.get()).isEqualTo(new Response(1));
  }

  @Test
  public void queueOverflow_sleeps() throws Exception {
    // This covers the overflow case of Step 1B in the documentation.

    var multiplexer = new SettableMultiplexer();
    var batcher =
        RequestBatcher.<Request, Response>create(
            commonPool(), multiplexer, /* targetWorkerCount= */ 1);
    ListenableFuture<Response> response0 = batcher.submit(new Request(0));
    BatchedRequestResponses requestResponses0 = multiplexer.queue.take();
    // The first worker is busy until requestResponse0 is populated.

    var responses = new ArrayList<ListenableFuture<Response>>();
    // With the single available worker being busy, we can completely fill the queue.
    for (int i = 0; i < ConcurrentFifo.MAX_ELEMENTS; i++) {
      responses.add(batcher.submit(new Request(i + 1)));
    }

    // The next request triggers a queue overflow. Since this ends up blocking, we do it in another
    // thread.
    var overflowStarting = new CountDownLatch(1);
    var overflowAdded = new CountDownLatch(1);
    // A new thread needs must used here instead of commonPool because there are test environments
    // where commonPool has only a single thread.
    new Thread(
            () -> {
              overflowStarting.countDown();
              responses.add(batcher.submit(new Request(ConcurrentFifo.MAX_ELEMENTS + 1)));
              overflowAdded.countDown();
            })
        .start();

    // The following assertion will occasionally fail if the overflow submit above does not block.
    overflowStarting.await();
    assertThat(responses).hasSize(ConcurrentFifo.MAX_ELEMENTS);

    // Responding to the first batch enables the overflowing element to enter the queue.
    requestResponses0.setSimpleResponses();
    assertThat(response0.get()).isEqualTo(new Response(0));
    overflowAdded.await();
    assertThat(responses).hasSize(ConcurrentFifo.MAX_ELEMENTS + 1);

    // Responds to all remaining batches.
    int batchSize = RequestBatcher.BATCH_SIZE + 1;
    int batchCount = responses.size() / batchSize;
    assertThat(responses).hasSize(batchCount * batchSize);

    for (int i = 0; i < batchCount; i++) {
      multiplexer.queue.take().setSimpleResponses();
    }

    for (int i = 0; i < ConcurrentFifo.MAX_ELEMENTS + 1; i++) {
      assertThat(responses.get(i).get()).isEqualTo(new Response(i + 1));
    }
  }

  @Test
  public void submitWithWorkersFull_enqueuesThenExecutes() throws Exception {
    // This covers Step 1B, Step 2A and Step 3 of the documentation.

    var multiplexer = new SettableMultiplexer();
    var batcher =
        RequestBatcher.<Request, Response>create(
            commonPool(), multiplexer, /* targetWorkerCount= */ 1);
    ListenableFuture<Response> response1 = batcher.submit(new Request(1));
    BatchedRequestResponses requestResponses1 = multiplexer.queue.take();

    ListenableFuture<Response> response2 = batcher.submit(new Request(2));
    // The first batch is not yet complete. The 2nd request waits in an internal queue. Ideally, we
    // could make a stronger assertion here, that the 2nd batch executes only after the first one is
    // done.
    assertThat(multiplexer.queue).isEmpty();

    requestResponses1.setSimpleResponses();

    // With the first batch done, the worker picks up the enqueued 2nd request and executes it.
    BatchedRequestResponses requestResponses2 = multiplexer.queue.take();
    requestResponses2.setSimpleResponses();

    assertThat(response1.get()).isEqualTo(new Response(1));
    assertThat(response2.get()).isEqualTo(new Response(2));
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void concurrentWorkCompletion_startsNewWorker() throws Exception {
    // This covers Step 1B and Step 2B of the documentation.

    // This test uses fakes to achieve the narrow set of conditions needed to reach this code path.
    long baseAddress = createPaddedBaseAddress(4);

    var queueDrainingExecutor = new FakeExecutor();
    var fifo =
        new FakeConcurrentFifo(
            getAlignedAddress(baseAddress, /* offset= */ 1),
            getAlignedAddress(baseAddress, /* offset= */ 2),
            getAlignedAddress(baseAddress, /* offset= */ 3));
    long countersAddress = getAlignedAddress(baseAddress, /* offset= */ 0);
    var batcher =
        new RequestBatcher<Request, Response>(
            /* responseDistributionExecutor= */ commonPool(),
            /* queueDrainingExecutor= */ queueDrainingExecutor,
            requests -> immediateFuture(respondTo(requests)),
            /* targetWorkerCount= */ 1,
            countersAddress,
            fifo);
    cleaner.register(batcher, new AddressFreer(baseAddress));

    // Submits a request. This starts a worker to run the batch, but it gets blocked on
    // `queueDrainingExecutor` and can't continue.
    ListenableFuture<Response> response1 = batcher.submit(new Request(1));

    // Submits a 2nd request. This request observes that there are enough active workers so it tries
    // to enqueue an element. It gets blocked at the queue.
    @SuppressWarnings("FutureReturnValueIgnored")
    var response2 = SettableFuture.<ListenableFuture<Response>>create();
    commonPool().execute(() -> response2.set(batcher.submit(new Request(2))));
    // Waits until the 2nd request starts enqueuing.
    fifo.tryAppendTokens.acquireUninterruptibly();

    // Allows the 1st worker to continue. This calls an enqueued `continueToNextBatchOrBecomeIdle`
    // invocation that will cause the 1st worker to go idle.
    queueDrainingExecutor.queue.take().run();

    assertThat(response1.get()).isEqualTo(new Response(1));

    // Verifies that there's absolutely nothing inflight in the batcher.
    assertThat(UNSAFE.getIntVolatile(null, countersAddress)).isEqualTo(0);

    // Allows the 2nd request to enqueue and complete processing.
    fifo.appendPermits.release();
    queueDrainingExecutor.queue.take().run();

    assertThat(response2.get().get()).isEqualTo(new Response(2));
  }

  @Test
  public void randomRaces_executeCorrectly() throws Exception {
    var batcher =
        RequestBatcher.<Request, Response>create(
            commonPool(),
            requests -> immediateFuture(respondTo(requests)),
            /* targetWorkerCount= */ 4);

    var results = new ConcurrentLinkedQueue<ListenableFuture<Void>>();
    final int requestCount = 4_000_000;
    var allStarted = new CountDownLatch(requestCount);
    for (int i = 0; i < requestCount; i++) {
      final int iForCapture = i;
      commonPool()
          .execute(
              () -> {
                results.add(
                    Futures.transformAsync(
                        batcher.submit(new Request(iForCapture)),
                        response ->
                            response.x() == iForCapture
                                ? immediateVoidFuture()
                                : immediateFailedFuture(
                                    new AssertionError(
                                        String.format(
                                            "expected %d got %s", iForCapture, response))),
                        directExecutor()));
                allStarted.countDown();
              });
    }
    allStarted.await();
    // Throws ExecutionException if there are any errors.
    var unused = Futures.whenAllSucceed(results).call(() -> null, directExecutor()).get();
  }

  private static class FakeExecutor implements Executor {
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    @Override
    public void execute(Runnable runnable) {
      queue.add(runnable);
    }
  }

  private static class FakeConcurrentFifo
      extends ConcurrentFifo<RequestResponse<Request, Response>> {
    private final ConcurrentLinkedQueue<RequestResponse<Request, Response>> queue =
        new ConcurrentLinkedQueue<>();

    private final Semaphore tryAppendTokens = new Semaphore(0);
    private final Semaphore appendPermits = new Semaphore(0);

    private FakeConcurrentFifo(long sizeAddress, long appendIndexAddress, long takeIndexAddress) {
      super(RequestResponse.class, sizeAddress, appendIndexAddress, takeIndexAddress);
    }

    @Override
    boolean tryAppend(RequestResponse<Request, Response> task) {
      tryAppendTokens.release();
      appendPermits.acquireUninterruptibly();
      queue.add(task);
      return true;
    }

    @Override
    RequestResponse<Request, Response> take() {
      return queue.poll();
    }
  }

  private static class SettableMultiplexer
      implements RequestBatcher.Multiplexer<Request, Response> {
    private final LinkedBlockingQueue<BatchedRequestResponses> queue = new LinkedBlockingQueue<>();

    @Override
    public ListenableFuture<List<Response>> execute(List<Request> requests) {
      var responses = SettableFuture.<List<Response>>create();
      queue.add(new BatchedRequestResponses(requests, responses));
      return responses;
    }
  }

  private record BatchedRequestResponses(
      List<Request> requests, SettableFuture<List<Response>> responses) {
    private void setSimpleResponses() {
      responses().set(respondTo(requests()));
    }
  }

  private record Request(int x) {}

  private record Response(int x) {}

  private static List<Response> respondTo(List<Request> requests) {
    return Lists.transform(requests, request -> new Response(request.x()));
  }

  private static final Unsafe UNSAFE = UnsafeProvider.unsafe();
}
