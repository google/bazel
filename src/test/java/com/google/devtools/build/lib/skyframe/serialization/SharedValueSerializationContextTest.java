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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.devtools.build.lib.skyframe.serialization.NotNestedSet.NestedArrayCodec;
import com.google.devtools.build.lib.skyframe.serialization.NotNestedSet.NotNestedSetCodec;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SharedValueSerializationContextTest {
  private static final int CONCURRENCY = 20;

  private final ForkJoinPool executor = new ForkJoinPool(CONCURRENCY);
  private final Random rng = new Random(0);

  private static final class PutRecordingStore implements FingerprintValueStore {
    private final ArrayList<SettableWriteStatus> putResponses = new ArrayList<>();

    @Override
    public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
      var response = new SettableWriteStatus();
      synchronized (putResponses) {
        putResponses.add(response);
      }
      return response;
    }

    @Override
    public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
      throw new UnsupportedOperationException();
    }

    private void completeAllResponses() {
      for (SettableWriteStatus response : putResponses) {
        response.markSuccess();
      }
    }
  }

  @Test
  public void resultDoesNotBlockOnPut() throws Exception {
    // The result is available prior to completion of the put operations and that completion of the
    // put operations propagates to the SerializationResult's future.
    PutRecordingStore store = new PutRecordingStore();
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(store);
    ObjectCodecs codecs = createObjectCodecs();

    // Creates a diamond.
    //   a
    //  / \
    // b   c
    //  \ /
    //   d
    Object[] d = createRandomLeafArray();
    Object[] c = new Object[] {d};
    Object[] b = new Object[] {d};
    Object[] a = new Object[] {b, c};
    NotNestedSet diamond = new NotNestedSet(a);
    SerializationResult<ByteString> result =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, diamond, /* profileCollector= */ null);

    // 4 remote arrays were written because d is memoized via the cache, despite the fact that d
    // occurs twice in the traversal.
    ArrayList<SettableWriteStatus> responses = store.putResponses;
    assertThat(responses).hasSize(4);

    ListenableFuture<Void> writeStatus = result.getFutureToBlockWritesOn();
    assertThat(writeStatus).isNotNull();
    assertThat(writeStatus.isDone()).isFalse();

    // Sets some, but not all of the responses.
    for (int i = 0; i < 2; i++) {
      responses.get(i).markSuccess();
    }
    assertThat(writeStatus.isDone()).isFalse(); // not yet done

    // Sets the remaining responses.
    for (int i = 2; i < responses.size(); i++) {
      responses.get(i).markSuccess();
    }
    assertThat(writeStatus.isDone()).isTrue(); // write status future completes
  }

  @Test
  public void writeStatusPropagatesToSecondCaller() throws Exception {
    // When a shared value is serialized by two different callers, the 2nd caller's
    // SerializationResult.futureToBlockWritingOn also waits for writes to complete.
    PutRecordingStore store = new PutRecordingStore();
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(store);
    ObjectCodecs codecs = createObjectCodecs();

    Object[] shared = createRandomLeafArray();
    NotNestedSet set1 = new NotNestedSet(shared);
    NotNestedSet set2 = new NotNestedSet(shared);

    SerializationResult<ByteString> result1 =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, set1, /* profileCollector= */ null);
    ListenableFuture<Void> writeStatus1 = result1.getFutureToBlockWritesOn();
    assertThat(writeStatus1.isDone()).isFalse();

    assertThat(store.putResponses).hasSize(1);

    SerializationResult<ByteString> result2 =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, set2, /* profileCollector= */ null);
    ListenableFuture<Void> writeStatus2 = result2.getFutureToBlockWritesOn();
    assertThat(writeStatus2.isDone()).isFalse();

    // The store only observes 1 put because it is shared between set1 and set2.
    assertThat(store.putResponses).hasSize(1);

    // Completing the response causes both of the write statuses to complete.
    store.completeAllResponses();
    assertThat(writeStatus1.isDone()).isTrue();
    assertThat(writeStatus2.isDone()).isTrue();
  }

  @Test
  public void multipleSharedValues_requestedInParallel() throws Exception {
    // Serialization does not block on blocked fingerprint computations in another thread.
    NestedArrayCodec arrayCodec = new NestedArrayCodec();
    ObjectCodecs codecs =
        new ObjectCodecs(
            AutoRegistry.get().getBuilder().add(new NotNestedSetCodec(arrayCodec)).build());
    FingerprintValueService fingerprintValueService = FingerprintValueService.createForTesting();

    Object[] sharedArray = createRandomLeafArray();
    CountDownLatch sharedEntered = new CountDownLatch(1);
    CountDownLatch sharedBlocker = new CountDownLatch(1);
    arrayCodec.injectSerializeDelay(sharedArray, sharedEntered, sharedBlocker);

    // Serializes `sharedArray`, which is registered to block on `sharedBlocker`.
    ListenableFuture<SerializationResult<ByteString>> first =
        serializeWithExecutor(codecs, fingerprintValueService, new NotNestedSet(sharedArray));
    sharedEntered.await(); // Waits for the above thread take ownership of `sharedArray`.

    Object[] myArray = createRandomLeafArray();
    CountDownLatch myArrayEntered = new CountDownLatch(1);
    // Does not block serialization of `myArray`, but uses `myArrayEntered` to determine that
    // serialization of `myArray` has started.
    arrayCodec.injectSerializeDelay(myArray, myArrayEntered, new CountDownLatch(0));

    ListenableFuture<SerializationResult<ByteString>> second =
        serializeWithExecutor(
            codecs, fingerprintValueService, new NotNestedSet(new Object[] {sharedArray, myArray}));

    // Completing the line below means that the serialization of `myArray` can start even though
    // serialization of `sharedArray` is blocked.
    myArrayEntered.await();

    // Neither is done due to being blocked by `sharedBlocker`.
    assertThat(first.isDone()).isFalse();
    assertThat(second.isDone()).isFalse();

    sharedBlocker.countDown(); // unblocks serialization of `sharedArray`

    // Serialization succeeds now that it is unblocked.
    SerializationResult<ByteString> unusedFirstResult = first.get();
    SerializationResult<ByteString> unusedSecondResult = second.get();
  }

  @Test
  public void concurrentSharing_waitsForCompleteBytes() throws Exception {
    // Under parallel sharing, serialization blocks until all fingerprints are computed.

    // Counts down every FingerprintValueStore.put.
    CountDownLatch arrived = new CountDownLatch(CONCURRENCY);
    // For each FingerprintValueStore.put, a CountDownLatch(1) is added to the end of this queue.
    // The put thread blocks, awaiting its associated latch.
    ConcurrentLinkedDeque<CountDownLatch> putPermits = new ConcurrentLinkedDeque<>();
    // Responses returned by the FingerprintValueStore.
    var putResponses = new ArrayList<SettableWriteStatus>();

    var blockingStore =
        new FingerprintValueStore() {
          @Override
          public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
            var response = new SettableWriteStatus();
            synchronized (putResponses) {
              putResponses.add(response);
            }
            CountDownLatch permit = new CountDownLatch(1);
            putPermits.offerLast(permit);
            arrived.countDown();
            try {
              permit.await();
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return response;
          }

          @Override
          public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
            throw new UnsupportedOperationException();
          }
        };
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(blockingStore);

    ArrayList<Object[]> sharedArrays = new ArrayList<>(CONCURRENCY);
    for (int i = 0; i < CONCURRENCY; i++) {
      sharedArrays.add(createRandomLeafArray());
    }

    ObjectCodecs codecs = createObjectCodecs();

    ArrayList<ListenableFuture<SerializationResult<ByteString>>> results =
        new ArrayList<>(CONCURRENCY);
    for (int i = 0; i < CONCURRENCY; i++) {
      Object[] arrays = new Object[CONCURRENCY];
      for (int j = 0; j < CONCURRENCY; j++) {
        arrays[(i + j) % CONCURRENCY] = sharedArrays.get(j);
      }
      NotNestedSet set = new NotNestedSet(arrays);
      // Each thread will acquire ownership of a unique `sharedArrays` element then block when it
      // hits the `putPermits`.
      results.add(serializeWithExecutor(codecs, fingerprintValueService, set));
    }
    // When the following await has succeeded, each thread has acquired ownership of one of the
    // `sharedArrays`.
    arrived.await();

    // Verifies that all SerializationResults are blocked (due to incomplete fingerprints).
    for (ListenableFuture<SerializationResult<ByteString>> result : results) {
      assertThat(result.isDone()).isFalse();
    }

    // Unblocks all but 1 of the threads.
    for (int i = 0; i < CONCURRENCY - 1; i++) {
      putPermits.pollFirst().countDown();
    }
    // Since the permits are ordered, the first element of the queue is the one associated with a
    // put of one of the `sharedArrays`. It doesn't happen in the current implementation, but more
    // permits could be added by the unblocking above, which is why this distinction matters.
    CountDownLatch lastSharedPut = putPermits.pollFirst();
    assertThat(lastSharedPut).isNotNull();

    // Even with all but 1 of the shared puts complete, all results are still blocked since all
    // threads require all fingerprints.
    for (ListenableFuture<SerializationResult<ByteString>> result : results) {
      assertThat(result.isDone()).isFalse();
    }

    lastSharedPut.countDown(); // Releases the remaining put.

    // Releasing the putPermits above unblocks additional serialization work for the top-level
    // nested arrays. Unblocks the additional resulting puts.
    for (int i = 0; i < CONCURRENCY; i++) {
      waitForLastPermit(putPermits).countDown();
    }

    // Everything succeeds once all the threads wake up from being blocked and complete.
    List<SerializationResult<ByteString>> resultList = Futures.successfulAsList(results).get();

    // Even with all the results available, the write status futures are still not done because
    // `putResponses` have not been set.
    for (SerializationResult<ByteString> result : resultList) {
      assertThat(result.getFutureToBlockWritesOn().isDone()).isFalse();
    }

    // There's 2 for each subject: its top-level array and its owned element of `sharedArrays`.
    assertThat(putResponses).hasSize(CONCURRENCY * 2);

    // Setting all the responses completes the result write status futures.
    for (SettableWriteStatus response : putResponses) {
      response.markSuccess();
    }
    for (SerializationResult<ByteString> result : resultList) {
      assertThat(result.getFutureToBlockWritesOn().isDone()).isTrue();
    }
  }

  @Test
  public void errorInSharedPut() throws Exception {
    // When a shared value is serialized in error by one thread, another thread serializing the
    // same shared value reports the same error.
    FingerprintValueService fingerprintValueService = FingerprintValueService.createForTesting();
    var codecs =
        new ObjectCodecs(
            ObjectCodecRegistry.newBuilder().add(new FaultySharedValueExampleCodec()).build());
    var subject1 = new SharedValueExample(10);
    var thrown1 =
        assertThrows(
            SerializationException.class,
            () ->
                codecs.serializeMemoizedAndBlocking(
                    fingerprintValueService, subject1, /* profileCollector= */ null));

    var subject2 = new SharedValueExample(subject1.sharedData());
    var thrown2 =
        assertThrows(
            SerializationException.class,
            () ->
                codecs.serializeMemoizedAndBlocking(
                    fingerprintValueService, subject2, /* profileCollector= */ null));
    assertThat(thrown2).hasCauseThat().isSameInstanceAs(thrown1);
  }

  /** Test data for {@link #errorInSharedPut}. */
  private record SharedValueExample(Integer sharedData) {}

  private static class FaultySharedValueExampleCodec
      extends DeferredObjectCodec<SharedValueExample> {
    @Override
    public Class<SharedValueExample> getEncodedClass() {
      return SharedValueExample.class;
    }

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public void serialize(
        SerializationContext context, SharedValueExample obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(
          obj.sharedData(), /* distinguisher= */ null, FaultySerializationCodec.INSTANCE, codedOut);
    }

    @Override
    public DeferredValue<SharedValueExample> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn) {
      throw new AssertionError("not reachable");
    }
  }

  private static class FaultySerializationCodec extends DeferredObjectCodec<Integer> {
    private static final FaultySerializationCodec INSTANCE = new FaultySerializationCodec();

    @Override
    public Class<Integer> getEncodedClass() {
      return Integer.class;
    }

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public void serialize(SerializationContext context, Integer obj, CodedOutputStream codedOut)
        throws SerializationException {
      throw new SerializationException("injected error");
    }

    @Override
    public DeferredValue<Integer> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn) {
      throw new AssertionError("not reachable");
    }
  }

  private ListenableFuture<SerializationResult<ByteString>> serializeWithExecutor(
      ObjectCodecs codecs, FingerprintValueService fingerprintValueService, Object subject) {
    var task =
        ListenableFutureTask.create(
            () ->
                codecs.serializeMemoizedAndBlocking(
                    fingerprintValueService, subject, /* profileCollector= */ null));
    executor.execute(task);
    return task;
  }

  private Object[] createRandomLeafArray() {
    return NotNestedSet.createRandomLeafArray(rng, Random::nextInt);
  }

  private static final long POLL_MS = 100;

  private static CountDownLatch waitForLastPermit(ConcurrentLinkedDeque<CountDownLatch> deque)
      throws InterruptedException {
    CountDownLatch latch;
    while ((latch = deque.pollLast()) == null) {
      Thread.sleep(POLL_MS);
    }
    return latch;
  }

  private static ObjectCodecs createObjectCodecs() {
    return new ObjectCodecs(
        AutoRegistry.get().getBuilder().add(new NotNestedSetCodec(new NestedArrayCodec())).build());
  }
}
