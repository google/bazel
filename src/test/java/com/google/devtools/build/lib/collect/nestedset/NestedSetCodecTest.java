// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.getFingerprintForTesting;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateFailedWriteStatus;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.PutOperation;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationDependencyProvider;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.util.io.AnsiTerminal.Color;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Tests for {@link NestedSet} serialization. */
@RunWith(JUnit4.class)
public final class NestedSetCodecTest {

  @Test
  public void testAutoCodecedCodec() throws Exception {
    ObjectCodecs objectCodecs =
        new ObjectCodecs(
            AutoRegistry.get().getBuilder().setAllowDefaultCodec(true).build(),
            ImmutableClassToInstanceMap.of());
    NestedSetCodecTestUtils.checkCodec(objectCodecs, false, false);
  }

  @Test
  public void testCodecWithInMemoryNestedSetStore() throws Exception {
    ObjectCodecs objectCodecs = createCodecs(NestedSetStore.inMemory());
    NestedSetCodecTestUtils.checkCodec(objectCodecs, true, true);
  }

  @Test
  public void onlyOneReadPerArray() throws Exception {
    NestedSet<String> base = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b");
    NestedSet<String> top = NestedSetBuilder.fromNestedSet(base).add("c").build();

    AtomicInteger reads = new AtomicInteger();
    FingerprintValueStore fingerprintValueStore =
        new FingerprintValueStore() {
          final FingerprintValueStore delegate = FingerprintValueStore.inMemoryStore();

          @Override
          public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
            return delegate.put(fingerprint, serializedBytes);
          }

          @Override
          public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
            reads.incrementAndGet();
            return delegate.get(fingerprint);
          }
        };
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(fingerprintValueStore);

    ObjectCodecs serializer = createCodecs(createStore(fingerprintValueStore));
    ByteString serializedBase =
        serializer
            .serializeMemoizedAndBlocking(
                fingerprintValueService, base, /* profileCollector= */ null)
            .getObject();
    ByteString serializedTop =
        serializer
            .serializeMemoizedAndBlocking(
                fingerprintValueService, top, /* profileCollector= */ null)
            .getObject();

    // When deserializing top, we should perform 2 reads, one for each array in [[a, b], c].
    // Deliberately recreates the store to avoid getting a cached value.
    ObjectCodecs deserializer = createCodecs(createStore(fingerprintValueStore));
    NestedSet<?> deserializedTop = (NestedSet<?>) deserializer.deserializeMemoized(serializedTop);
    assertThat(deserializedTop.toList()).containsExactly("a", "b", "c");
    assertThat(reads.get()).isEqualTo(2);

    // When deserializing base, we should not need to perform any additional reads since we have
    // already read [a, b] and it is still in memory.
    GcFinalization.awaitFullGc();
    NestedSet<?> deserializedBase = (NestedSet<?>) deserializer.deserializeMemoized(serializedBase);
    assertThat(deserializedBase.toList()).containsExactly("a", "b");
    assertThat(reads.get()).isEqualTo(2);
  }

  @Test
  public void missingNestedSetException_hiddenUntilNestedSetIsConsumed() throws Exception {
    Throwable missingNestedSetException =
        new MissingFingerprintValueException(getFingerprintForTesting("fingerprint"));
    FingerprintValueStore fingerprintValueStore =
        new FingerprintValueStore() {
          @Override
          public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
            return immediateWriteStatus();
          }

          @Override
          public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
            return immediateFailedFuture(missingNestedSetException);
          }
        };
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(fingerprintValueStore);
    BugReporter bugReporter = mock(BugReporter.class);
    ObjectCodecs serializer = createCodecs(createStore(fingerprintValueStore));
    ObjectCodecs deserializer =
        createCodecs(createStoreWithBugReporter(fingerprintValueStore, bugReporter));

    NestedSet<?> serialized = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b");
    ByteString result =
        serializer
            .serializeMemoizedAndBlocking(
                fingerprintValueService, serialized, /* profileCollector= */ null)
            .getObject();
    Object deserialized = deserializer.deserializeMemoized(result);

    assertThat(deserialized).isInstanceOf(NestedSet.class);
    assertThrows(
        MissingFingerprintValueException.class, ((NestedSet<?>) deserialized)::toListInterruptibly);
    verify(bugReporter).sendNonFatalBugReport(missingNestedSetException);
  }

  @Test
  public void exceptionOnPut_propagatedToFutureToBlockWritesOn() throws Exception {
    Exception e = new Exception("Something went wrong");
    FingerprintValueStore fingerprintValueStore =
        new FingerprintValueStore() {
          @Override
          public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
            return immediateFailedWriteStatus(e);
          }

          @Override
          public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
            throw new UnsupportedOperationException();
          }
        };
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(fingerprintValueStore);
    ObjectCodecs codecs = createCodecs(createStore(fingerprintValueStore));

    NestedSet<?> serialized = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b");
    SerializationResult<ByteString> result =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, serialized, /* profileCollector= */ null);
    Future<Void> futureToBlockWritesOn = result.getFutureToBlockWritesOn();
    Exception thrown = assertThrows(ExecutionException.class, futureToBlockWritesOn::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(e);
  }

  @Test
  public void exceptionOnGet_hiddenUntilNestedSetIsConsumed() throws Exception {
    Exception e = new Exception("Something went wrong");
    FingerprintValueStore fingerprintValueStore =
        new FingerprintValueStore() {
          @Override
          public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
            return immediateWriteStatus();
          }

          @Override
          public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
            return immediateFailedFuture(e);
          }
        };
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(fingerprintValueStore);
    ObjectCodecs serializer = createCodecs(createStore(fingerprintValueStore));
    // Creates a separate deserializer so it does not see cached entries from the serializer.
    ObjectCodecs deserializer = createCodecs(createStore(fingerprintValueStore));

    NestedSet<?> serialized = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b");
    ByteString result =
        serializer
            .serializeMemoizedAndBlocking(
                fingerprintValueService, serialized, /* profileCollector= */ null)
            .getObject();
    Object deserialized =
        deserializer.deserializeMemoizedAndBlocking(fingerprintValueService, result);

    assertThat(deserialized).isInstanceOf(NestedSet.class);
    Exception thrown = assertThrows(RuntimeException.class, ((NestedSet<?>) deserialized)::toList);
    assertThat(thrown).hasMessageThat().contains("Something went wrong");
  }

  /**
   * Tests that serialization of a {@code NestedSet<NestedSet<String>>} waits on the writes of the
   * inner NestedSets.
   */
  @Test
  public void testNestedNestedSetSerialization() throws Exception {
    FingerprintValueStore mockStorage = mock(FingerprintValueStore.class);
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(mockStorage);
    var innerWrite = new SettableWriteStatus();
    var outerWrite = new SettableWriteStatus();
    when(mockStorage.put(any(), any()))
        // The write of the inner NestedSet {"a", "b"}
        .thenReturn(innerWrite)
        // The write of the inner NestedSet {"c", "d"}
        .thenReturn(innerWrite)
        // The write of the outer NestedSet {{"a", "b"}, {"c", "d"}}
        .thenReturn(outerWrite);
    ObjectCodecs objectCodecs = createCodecs(createStore(mockStorage));

    NestedSet<NestedSet<String>> nestedNestedSet =
        NestedSetBuilder.create(
            Order.STABLE_ORDER,
            NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b"),
            NestedSetBuilder.create(Order.STABLE_ORDER, "c", "d"));

    SerializationResult<ByteString> result =
        objectCodecs.serializeMemoizedAndBlocking(
            fingerprintValueService, nestedNestedSet, /* profileCollector= */ null);
    outerWrite.markSuccess();
    assertThat(result.getFutureToBlockWritesOn().isDone()).isFalse();
    innerWrite.markSuccess();
    assertThat(result.getFutureToBlockWritesOn().isDone()).isTrue();
  }

  @Test
  public void testNestedNestedSetsWithCommonDependencyWaitOnSameInnerFuture() throws Exception {
    FingerprintValueStore mockStorage = mock(FingerprintValueStore.class);
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(mockStorage);
    var sharedInnerWrite = new SettableWriteStatus();
    var outerWrite = new SettableWriteStatus();
    when(mockStorage.put(any(), any()))
        // The write of the shared inner NestedSet {"a", "b"}
        .thenReturn(sharedInnerWrite)
        // The write of the inner NestedSet {"c", "d"}
        .thenReturn(immediateWriteStatus())
        // The write of the outer NestedSet {{"a", "b"}, {"c", "d"}}
        .thenReturn(outerWrite)
        // The write of the inner NestedSet {"e", "f"}
        .thenReturn(immediateWriteStatus());
    ObjectCodecs objectCodecs = createCodecs(createStore(mockStorage));

    NestedSet<String> sharedInnerNestedSet = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b");
    NestedSet<NestedSet<String>> nestedNestedSet1 =
        NestedSetBuilder.create(
            Order.STABLE_ORDER,
            sharedInnerNestedSet,
            NestedSetBuilder.create(Order.STABLE_ORDER, "c", "d"));
    NestedSet<NestedSet<String>> nestedNestedSet2 =
        NestedSetBuilder.create(
            Order.STABLE_ORDER,
            sharedInnerNestedSet,
            NestedSetBuilder.create(Order.STABLE_ORDER, "e", "f"));

    SerializationResult<ByteString> result1 =
        objectCodecs.serializeMemoizedAndBlocking(
            fingerprintValueService, nestedNestedSet1, /* profileCollector= */ null);
    SerializationResult<ByteString> result2 =
        objectCodecs.serializeMemoizedAndBlocking(
            fingerprintValueService, nestedNestedSet2, /* profileCollector= */ null);
    outerWrite.markSuccess();
    assertThat(result1.getFutureToBlockWritesOn().isDone()).isFalse();
    assertThat(result2.getFutureToBlockWritesOn().isDone()).isFalse();
    sharedInnerWrite.markSuccess();
    assertThat(result1.getFutureToBlockWritesOn().isDone()).isTrue();
    assertThat(result2.getFutureToBlockWritesOn().isDone()).isTrue();
  }

  @Test
  public void testSingletonNestedSetSerializedWithoutStore() throws Exception {
    NestedSetStore mockNestedSetStore = mock(NestedSetStore.class);
    when(mockNestedSetStore.computeFingerprintAndStore(any(), any()))
        .thenThrow(new AssertionError("NestedSetStore should not have been used"));

    ObjectCodecs objectCodecs = createCodecs(mockNestedSetStore);
    NestedSet<String> singletonNestedSet =
        new NestedSetBuilder<String>(Order.STABLE_ORDER).add("a").build();
    objectCodecs.serialize(singletonNestedSet);
  }

  @Test
  public void serializationWeaklyCachesNestedSet() throws Exception {
    // Avoid NestedSetBuilder.wrap/create - they use their own cache which interferes with what
    // we're testing.
    NestedSet<?> nestedSet = NestedSetBuilder.stableOrder().add("a").add("b").build();
    FingerprintValueStore storageEndpoint = FingerprintValueStore.inMemoryStore();
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(storageEndpoint);
    ObjectCodecs codecs = createCodecs(createStore(storageEndpoint));
    var unused =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, nestedSet, /* profileCollector= */ null);
    WeakReference<?> ref = new WeakReference<>(nestedSet);
    nestedSet = null;
    GcFinalization.awaitClear(ref);
  }

  @Test
  public void testDeserializationInParallel() throws Exception {
    FingerprintValueStore nestedSetFingerprintValueStore =
        spy(FingerprintValueStore.inMemoryStore());
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(nestedSetFingerprintValueStore);
    NestedSetSerializationCache emptyNestedSetCache = mock(NestedSetSerializationCache.class);
    NestedSetStore nestedSetStore =
        createStoreWithCache(nestedSetFingerprintValueStore, emptyNestedSetCache);
    ObjectCodecs objectCodecs = createCodecs(nestedSetStore);

    NestedSet<String> subset1 =
        new NestedSetBuilder<String>(Order.STABLE_ORDER).add("a").add("b").build();
    SettableFuture<byte[]> subset1Future = SettableFuture.create();
    NestedSet<String> subset2 =
        new NestedSetBuilder<String>(Order.STABLE_ORDER).add("c").add("d").build();
    SettableFuture<byte[]> subset2Future = SettableFuture.create();
    NestedSet<String> set =
        new NestedSetBuilder<String>(Order.STABLE_ORDER)
            .addTransitive(subset1)
            .addTransitive(subset2)
            .build();

    // We capture the arguments to #put() during serialization, so as to correctly mock results for
    // #get()
    var fingerprintCaptor = ArgumentCaptor.forClass(PackedFingerprint.class);
    PackedFingerprint fingerprint =
        nestedSetStore
            .computeFingerprintAndStore(
                (Object[]) set.getChildren(),
                objectCodecs.getSharedValueSerializationContextForTesting(fingerprintValueService))
            .fingerprint();
    verify(nestedSetFingerprintValueStore, times(3)).put(fingerprintCaptor.capture(), any());
    doReturn(subset1Future)
        .when(nestedSetFingerprintValueStore)
        .get(fingerprintCaptor.getAllValues().get(0));
    doReturn(subset2Future)
        .when(nestedSetFingerprintValueStore)
        .get(fingerprintCaptor.getAllValues().get(1));
    when(emptyNestedSetCache.putFutureIfAbsent(any(), any(), any())).thenReturn(null);

    @SuppressWarnings("unchecked")
    ListenableFuture<Object[]> deserializationFuture =
        (ListenableFuture<Object[]>)
            nestedSetStore.getContentsAndDeserialize(
                fingerprint,
                objectCodecs.getSharedValueDeserializationContextForTesting(
                    fingerprintValueService));
    // At this point, we expect deserializationFuture to be waiting on both of the underlying
    // fetches, which should have both been started.
    assertThat(deserializationFuture.isDone()).isFalse();
    verify(nestedSetFingerprintValueStore, times(3)).get(any());

    // Once the underlying fetches complete, we expect deserialization to complete.
    subset1Future.set(ByteString.copyFrom("mock bytes", Charset.defaultCharset()).toByteArray());
    subset2Future.set(ByteString.copyFrom("mock bytes", Charset.defaultCharset()).toByteArray());
    assertThat(deserializationFuture.isDone()).isTrue();
  }

  @Test
  public void racingDeserialization() throws Exception {
    FingerprintValueStore nestedSetFingerprintValueStore = mock(FingerprintValueStore.class);
    NestedSetSerializationCache nestedSetCache =
        spy(new NestedSetSerializationCache(BugReporter.defaultInstance()));
    NestedSetStore nestedSetStore =
        createStoreWithCache(nestedSetFingerprintValueStore, nestedSetCache);
    DeserializationContext deserializationContext = mock(DeserializationContext.class);
    PackedFingerprint fingerprint = getFingerprintForTesting("fingerprint");
    // Future never completes, so we don't have to exercise that code in NestedSetStore.
    SettableFuture<byte[]> storageFuture = SettableFuture.create();
    when(nestedSetFingerprintValueStore.get(fingerprint)).thenReturn(storageFuture);
    CountDownLatch fingerprintRequested = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              fingerprintRequested.countDown();
              @SuppressWarnings("unchecked")
              ListenableFuture<Object[]> result =
                  (ListenableFuture<Object[]>) invocation.callRealMethod();
              fingerprintRequested.await();
              return result;
            })
        .when(nestedSetCache)
        .putFutureIfAbsent(eq(fingerprint), any(), any());
    AtomicReference<ListenableFuture<Object[]>> asyncResult = new AtomicReference<>();
    Thread asyncThread =
        new Thread(
            () -> {
              try {
                @SuppressWarnings("unchecked")
                ListenableFuture<Object[]> asyncContents =
                    (ListenableFuture<Object[]>)
                        nestedSetStore.getContentsAndDeserialize(
                            fingerprint, deserializationContext);
                asyncResult.set(asyncContents);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            });
    asyncThread.start();
    @SuppressWarnings("unchecked")
    ListenableFuture<Object[]> result =
        (ListenableFuture<Object[]>)
            nestedSetStore.getContentsAndDeserialize(fingerprint, deserializationContext);
    asyncThread.join();
    verify(nestedSetFingerprintValueStore).get(eq(fingerprint));
    assertThat(result).isSameInstanceAs(asyncResult.get());
    assertThat(result.isDone()).isFalse();
  }

  @Test
  public void racingSerialization() throws Exception {
    // Exercises calling serialization twice for the same contents, concurrently, in 2 threads.
    FingerprintValueStore fingerprintValueStore = spy(FingerprintValueStore.inMemoryStore());
    NestedSetSerializationCache nestedSetCache =
        spy(new NestedSetSerializationCache(BugReporter.defaultInstance()));
    NestedSetStore nestedSetStore = createStoreWithCache(fingerprintValueStore, nestedSetCache);
    SerializationContext serializationContext =
        new ObjectCodecs()
            .getSharedValueSerializationContextForTesting(
                FingerprintValueService.createForTesting(fingerprintValueStore));
    Object[] contents = {"contents"};
    // NestedSet serialization of a `contents` Object[] performs the following steps in sequence.
    // 1. Checks if the fingerprint is already available via
    //    NestedSetSerializationCache.fingerprintForContents for `contents`.
    //    (If the fingerprint is already available, the computation is short-circuited.)
    // 2. Serializes to bytes and computes a fingerprint for those bytes.
    // 3. Puts the fingerprint into the cache.
    //
    // The latch here ensures that both threads do not short circuit in step 1.
    CountDownLatch fingerprintRequested = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              PutOperation result = (PutOperation) invocation.callRealMethod();
              assertThat(result).isNull();
              // Allows the other thread to progress only after checking for the fingerprint.
              fingerprintRequested.countDown();
              // Waits for the other thread to finish checking the fingerprint before proceeding.
              fingerprintRequested.await();
              return null;
            })
        .when(nestedSetCache)
        .fingerprintForContents(contents);
    AtomicReference<PutOperation> asyncResult = new AtomicReference<>();
    Thread asyncThread =
        new Thread(
            () -> {
              try {
                asyncResult.set(
                    nestedSetStore.computeFingerprintAndStore(contents, serializationContext));
              } catch (IOException | SerializationException e) {
                throw new IllegalStateException(e);
              }
            });
    asyncThread.start();
    PutOperation result = nestedSetStore.computeFingerprintAndStore(contents, serializationContext);
    asyncThread.join();

    verify(fingerprintValueStore).put(any(), any());
    assertThat(result).isSameInstanceAs(asyncResult.get());
  }

  @Test
  public void writeFuturesWaitForTransitiveWrites() throws Exception {
    FingerprintValueStore mockWriter = mock(FingerprintValueStore.class);
    NestedSetStore store = createStore(mockWriter);
    SerializationContext serializationContext =
        createCodecs(store)
            .getSharedValueSerializationContextForTesting(
                FingerprintValueService.createForTesting());

    var bottomReadFuture = new SettableWriteStatus();
    var middleReadFuture = new SettableWriteStatus();
    var topReadFuture = new SettableWriteStatus();
    when(mockWriter.put(any(), any()))
        .thenReturn(bottomReadFuture, middleReadFuture, topReadFuture);

    NestedSet<String> bottom =
        NestedSetBuilder.<String>stableOrder().add("bottom1").add("bottom2").build();
    NestedSet<String> middle =
        NestedSetBuilder.<String>stableOrder()
            .add("middle1")
            .add("middle2")
            .addTransitive(bottom)
            .build();
    NestedSet<String> top =
        NestedSetBuilder.<String>stableOrder()
            .add("top1")
            .add("top2")
            .addTransitive(middle)
            .build();

    ListenableFuture<Void> bottomWriteFuture =
        NestedSetCodecTestUtils.writeToStoreFuture(store, bottom, serializationContext);
    ListenableFuture<Void> middleWriteFuture =
        NestedSetCodecTestUtils.writeToStoreFuture(store, middle, serializationContext);
    ListenableFuture<Void> topWriteFuture =
        NestedSetCodecTestUtils.writeToStoreFuture(store, top, serializationContext);
    assertThat(bottomWriteFuture.isDone()).isFalse();
    assertThat(middleWriteFuture.isDone()).isFalse();
    assertThat(topWriteFuture.isDone()).isFalse();

    topReadFuture.markSuccess();
    middleReadFuture.markSuccess();
    assertThat(bottomWriteFuture.isDone()).isFalse();
    assertThat(middleWriteFuture.isDone()).isFalse();
    assertThat(topWriteFuture.isDone()).isFalse();

    bottomReadFuture.markSuccess();
    assertThat(bottomWriteFuture.isDone()).isTrue();
    assertThat(middleWriteFuture.isDone()).isTrue();
    assertThat(topWriteFuture.isDone()).isTrue();
  }

  record ColorfulThing(String thing, Color color) {
    ColorfulThing {
      requireNonNull(thing, "thing");
      requireNonNull(color, "color");
    }

    static ColorfulThing of(String thing, Color color) {
      return new ColorfulThing(thing, color);
    }
  }

  @Test
  public void cacheContext_disambiguatesIdenticalSerializedRepresentation() throws Exception {
    // Serializes ColorfulThing without color, reading the color as a deserialization dependency.
    class BlackAndWhiteCodec implements ObjectCodec<ColorfulThing> {
      @Override
      public Class<ColorfulThing> getEncodedClass() {
        return ColorfulThing.class;
      }

      @Override
      public void serialize(
          SerializationContext context, ColorfulThing obj, CodedOutputStream codedOut)
          throws SerializationException, IOException {
        context.serialize(obj.thing(), codedOut);
      }

      @Override
      public ColorfulThing deserialize(DeserializationContext context, CodedInputStream codedIn)
          throws SerializationException, IOException {
        String thing = context.deserialize(codedIn);
        Color color = context.getDependency(Color.class);
        return ColorfulThing.of(thing, color);
      }
    }

    FingerprintValueStore fingerprintValueStore = FingerprintValueStore.inMemoryStore();
    FingerprintValueService fingerprintValueService =
        FingerprintValueService.createForTesting(fingerprintValueStore);

    ObjectCodecs codecs =
        createCodecs(
            createStoreWithCacheContext(
                fingerprintValueStore, ctx -> ctx.getDependency(Color.class)),
            new BlackAndWhiteCodec());

    List<String> stuff = ImmutableList.of("bird", "paint", "shoes");
    NestedSet<ColorfulThing> redStuff =
        NestedSetBuilder.wrap(
            Order.STABLE_ORDER,
            Lists.transform(stuff, thing -> ColorfulThing.of(thing, Color.RED)));
    NestedSet<ColorfulThing> blueStuff =
        NestedSetBuilder.wrap(
            Order.STABLE_ORDER,
            Lists.transform(stuff, thing -> ColorfulThing.of(thing, Color.BLUE)));

    ObjectCodecs redCodecs =
        codecs.withDependencyOverridesForTesting(
            ImmutableClassToInstanceMap.of(Color.class, Color.RED));
    ByteString redSerialized =
        redCodecs
            .serializeMemoizedAndBlocking(
                fingerprintValueService, redStuff, /* profileCollector= */ null)
            .getObject();
    ObjectCodecs blueCodecs =
        codecs.withDependencyOverridesForTesting(
            ImmutableClassToInstanceMap.of(Color.class, Color.BLUE));
    ByteString blueSerialized =
        blueCodecs
            .serializeMemoizedAndBlocking(
                fingerprintValueService, blueStuff, /* profileCollector= */ null)
            .getObject();
    assertThat(redSerialized).isEqualTo(blueSerialized);

    Object redDeserialized =
        redCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, redSerialized);
    Object blueDeserialized =
        blueCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, blueSerialized);
    assertThat(redDeserialized).isSameInstanceAs(redStuff);
    assertThat(blueDeserialized).isSameInstanceAs(blueStuff);

    // Test that we can deserialize in a context that was not previously serialized.
    ObjectCodecs greenCodecs =
        codecs.withDependencyOverridesForTesting(
            ImmutableClassToInstanceMap.of(Color.class, Color.GREEN));
    Object greenDeserialized =
        greenCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, redSerialized);
    assertThat(greenDeserialized).isInstanceOf(NestedSet.class);
    assertThat(((NestedSet<?>) greenDeserialized).toList())
        .isEqualTo(Lists.transform(stuff, thing -> ColorfulThing.of(thing, Color.GREEN)));
  }

  private static NestedSetStore createStore(FingerprintValueStore fingerprintValueStore) {
    return createStoreWithBugReporter(fingerprintValueStore, BugReporter.defaultInstance());
  }

  private static NestedSetStore createStoreWithBugReporter(
      FingerprintValueStore fingerprintValueStore, BugReporter bugReporter) {
    return new NestedSetStore(
        fingerprintValueStore, directExecutor(), bugReporter, NestedSetStore.NO_CONTEXT);
  }

  private static NestedSetStore createStoreWithCache(
      FingerprintValueStore fingerprintValueStore, NestedSetSerializationCache cache) {
    return new NestedSetStore(
        fingerprintValueStore, directExecutor(), cache, NestedSetStore.NO_CONTEXT);
  }

  private static NestedSetStore createStoreWithCacheContext(
      FingerprintValueStore fingerprintValueStore,
      Function<SerializationDependencyProvider, ?> cacheContextFn) {
    return new NestedSetStore(
        fingerprintValueStore, directExecutor(), BugReporter.defaultInstance(), cacheContextFn);
  }

  private static ObjectCodecs createCodecs(NestedSetStore store, ObjectCodec<?>... codecs) {
    ObjectCodecRegistry.Builder registry =
        AutoRegistry.get()
            .getBuilder()
            .setAllowDefaultCodec(true)
            .add(new NestedSetCodecWithStore(store));
    for (ObjectCodec<?> codec : codecs) {
      registry.add(codec);
    }
    return new ObjectCodecs(registry.build(), /*dependencies=*/ ImmutableClassToInstanceMap.of());
  }
}
