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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.FutureHelpers.waitForDeserializationFuture;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult.QueryDepCallback;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Implementation that supports sharing of sub-objects between objects. */
final class SharedValueDeserializationContext extends MemoizingDeserializationContext {
  @VisibleForTesting // private
  static SharedValueDeserializationContext createForTesting(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService) {
    return new SharedValueDeserializationContext(
        codecRegistry, dependencies, fingerprintValueService, /* skyframeLookupCollector= */ null);
  }

  private final FingerprintValueService fingerprintValueService;

  /**
   * List of futures that must be resolved before this value is completely deserialized.
   *
   * <p>The synchronous {@link #deserialize(CodedInputStream)} overload includes waiting for these
   * futures to to be resolved. The top-level deserialization call typically uses that overload
   * while codec implementations use the ones in {@link AsyncDeserializationContext}.
   */
  @Nullable // Initialized lazily.
  private ArrayList<ListenableFuture<?>> readStatusFutures;

  /**
   * Tracks which {@link #readStatusFutures} were added, transitively, while deserializing a value.
   *
   * <p>When deserializing a value, {@link MemoizingDeserializationContext} calls {@link
   * #deserializeAndMaybeHandleDeferredValues}, then {@link #combineValueWithReadFutures}.
   *
   * <p>{@link #deserializeAndMaybeHandleDeferredValues} performs the following steps.
   *
   * <ul>
   *   <li>It notes the size of {@link #readStatusFutures} when it begins.
   *   <li>It initiates deserialization of the next value with a given {@link ObjectCodec}.
   *   <li>After that deserialization invocation completes, it sets {@code lastStartingReadCount} to
   *       the size it noted when it started.
   * </ul>
   *
   * <p>Consequently, the futures in {@link #readStatusFutures} with index greater than {@code
   * lastStartingReadCount} are ones added (transitively) by deserialization.
   *
   * <p>Next, {@link #combineValueWithReadFutures} uses {@code lastStartingReadCount} to determine
   * what futures were added and need to be complete before deserialization of the value is
   * complete.
   */
  private int lastStartingReadCount;

  @Nullable // non-null when Skyframe lookups are enabled
  private final SkyframeLookupCollector skyframeLookupCollector;

  private SharedValueDeserializationContext(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService,
      @Nullable SkyframeLookupCollector skyframeLookupCollector) {
    super(codecRegistry, dependencies);
    this.fingerprintValueService = fingerprintValueService;
    this.skyframeLookupCollector = skyframeLookupCollector;
  }

  static Object deserializeWithSharedValues(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService,
      ByteString bytes)
      throws SerializationException {
    try {
      return ObjectCodecs.deserializeStreamFully(
          bytes.newCodedInput(),
          new SharedValueDeserializationContext(
              codecRegistry,
              dependencies,
              fingerprintValueService,
              /* skyframeLookupCollector= */ null));
    } catch (SerializationException e) {
      Throwable cause = e.getCause();
      if (cause instanceof MissingFingerprintValueException) {
        // TODO: b/297857068 - eventually, callers of this should handle this by falling back on
        // local recomputation.
        throw new IllegalStateException("Not yet supported.", cause);
      }
      throw e;
    }
  }

  /**
   * Deserializes with possible Skyframe lookups via {@link #getSkyValue}.
   *
   * <p>This may return two distinct kinds of results.
   *
   * <ul>
   *   <li>An immediate nullable value that is the result of deserialization.
   *   <li>A {@code ListenableFuture<SkyframeLookupContinuation>} requiring further resolution.
   * </ul>
   */
  @Nullable
  @SuppressWarnings("FutureReturnValueIgnored") // client must check for ListenableFuture
  static Object deserializeWithSkyframe(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService,
      CodedInputStream codedIn)
      throws SerializationException {
    // Enabling aliasing of `codedIn` here might be better for performance but causes deserialized
    // values to differ subtly from the input values, complicating testing.
    //
    // TODO: b/335901349 - re-enable aliasing
    var lookupCollector = new SkyframeLookupCollector();
    var context =
        new SharedValueDeserializationContext(
            codecRegistry, dependencies, fingerprintValueService, lookupCollector);
    Object result;
    try {
      result = context.processTagAndDeserialize(codedIn);
    } catch (IOException e) {
      throw new SerializationException("Failed to deserialize data", e);
    }
    ObjectCodecs.checkInputFullyConsumed(codedIn, result);
    if (result == null) {
      return null;
    }
    if (!(result instanceof ListenableFuture<?> futureResult)) {
      return result;
    }
    lookupCollector.notifyFetchesInitialized();
    return Futures.transform(
        lookupCollector,
        lookups -> new SkyframeLookupContinuation(lookups, futureResult),
        directExecutor());
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  @SuppressWarnings("SunApi") // TODO: b/331765692 - delete this
  public void deserialize(CodedInputStream codedIn, Object parent, long offset)
      throws IOException, SerializationException {
    Object result = processTagAndDeserialize(codedIn);
    if (result == null) {
      return;
    }

    if (!(result instanceof ListenableFuture)) {
      unsafe().putObject(parent, offset, result);
      return;
    }

    addReadStatusFuture(
        Futures.transform(
            (ListenableFuture<?>) result,
            value -> {
              unsafe().putObject(parent, offset, value);
              return null;
            },
            directExecutor()));
  }

  @Override
  public <T> void deserialize(CodedInputStream codedIn, T parent, FieldSetter<? super T> setter)
      throws IOException, SerializationException {
    Object result = processTagAndDeserialize(codedIn);
    if (result == null) {
      return;
    }

    if (!(result instanceof ListenableFuture)) {
      setter.set(parent, result);
      return;
    }

    addReadStatusFuture(
        Futures.transformAsync(
            (ListenableFuture<?>) result,
            value -> {
              try {
                setter.set(parent, value);
              } catch (SerializationException e) {
                return immediateFailedFuture(e);
              }
              return immediateVoidFuture();
            },
            directExecutor()));
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  @SuppressWarnings("SunApi") // TODO: b/331765692 - delete this
  public void deserialize(CodedInputStream codedIn, Object parent, long offset, Runnable done)
      throws IOException, SerializationException {
    Object result = processTagAndDeserialize(codedIn);
    if (result == null) {
      done.run();
      return;
    }

    if (!(result instanceof ListenableFuture)) {
      unsafe().putObject(parent, offset, result);
      done.run();
      return;
    }

    addReadStatusFuture(
        Futures.transform(
            (ListenableFuture<?>) result,
            value -> {
              unsafe().putObject(parent, offset, value);
              done.run();
              return null;
            },
            directExecutor()));
  }

  @Override
  public void deserializeArrayElement(CodedInputStream codedIn, Object[] arr, int index)
      throws IOException, SerializationException {
    Object result = processTagAndDeserialize(codedIn);
    if (result == null) {
      return;
    }

    if (result instanceof ListenableFuture<?> futureResult) {
      addReadStatusFuture(
          Futures.transform(futureResult, value -> arr[index] = value, directExecutor()));
      return;
    }

    arr[index] = result;
  }

  @Override
  public <T> void getSharedValue(
      CodedInputStream codedIn,
      @Nullable Object distinguisher,
      DeferredObjectCodec<?> codec,
      T parent,
      FieldSetter<? super T> setter)
      throws IOException, SerializationException {
    PackedFingerprint fingerprint = PackedFingerprint.readFrom(codedIn);
    SettableFuture<Object> getOperation = SettableFuture.create();
    Object previous =
        fingerprintValueService.getOrClaimGetOperation(fingerprint, distinguisher, getOperation);
    if (previous != null) {
      // This object was previously requested. Discards `getOperation`.
      if (previous instanceof ListenableFuture<?> previousValue) {
        addReadStatusFuture(
            Futures.transformAsync(
                previousValue,
                value -> {
                  try {
                    setter.set(parent, value);
                  } catch (SerializationException e) {
                    return immediateFailedFuture(e);
                  }
                  return immediateVoidFuture();
                },
                directExecutor()));
        return;
      }
      setter.set(parent, previous);
      return;
    }

    // There is no previous result. Fetches the remote bytes and deserializes them.
    readValueForFingerprint(fingerprint, codec, parent, setter, getOperation);
    addReadStatusFuture(getOperation);
  }

  @Override
  public <T> void getSkyValue(SkyKey key, T parent, FieldSetter<? super T> setter) {
    var lookup = new SkyframeLookup<>(key, parent, setter);
    skyframeLookupCollector.addLookup(lookup);
    addReadStatusFuture(lookup);
  }

  private <T> void readValueForFingerprint(
      PackedFingerprint fingerprint,
      DeferredObjectCodec<?> codec,
      T parent,
      FieldSetter<? super T> setter,
      SettableFuture<Object> getOperation)
      throws IOException {
    if (skyframeLookupCollector != null) {
      skyframeLookupCollector.notifyFetchStarting();
    }
    try {
      Futures.addCallback(
          fingerprintValueService.get(fingerprint),
          new SharedBytesProcessor<>(codec, parent, setter, getOperation),
          // Switches to another executor to avoid performing serialization work on an an RPC
          // executor thread.
          fingerprintValueService.getExecutor());
    } catch (IOException
        // Avoids causing SettableFuture consumers to hang if when there are unexpected exceptions.
        | RuntimeException
        | Error e) {
      if (skyframeLookupCollector != null) {
        skyframeLookupCollector.notifyFetchException(e);
      }
      getOperation.setException(e);
      throw e;
    }
  }

  private class SharedBytesProcessor<T> implements FutureCallback<byte[]> {
    private final DeferredObjectCodec<?> codec;
    private final T parent;
    private final FieldSetter<? super T> setter;
    private final SettableFuture<Object> getOperation;

    private SharedBytesProcessor(
        DeferredObjectCodec<?> codec,
        T parent,
        FieldSetter<? super T> setter,
        SettableFuture<Object> getOperation) {
      this.codec = codec;
      this.parent = parent;
      this.setter = setter;
      this.getOperation = getOperation;
    }

    @Override
    public void onSuccess(byte[] bytes) {
      SharedValueDeserializationContext innerContext = getFreshContext();
      DeferredValue<?> deferred;
      try {
        deferred = codec.deserializeDeferred(innerContext, CodedInputStream.newInstance(bytes));
      } catch (SerializationException | IOException | RuntimeException | Error e) {
        if (skyframeLookupCollector != null) {
          skyframeLookupCollector.notifyFetchException(e);
        }
        getOperation.setException(e);
        return;
      }
      if (skyframeLookupCollector != null) {
        // The codec above is responsible for calling `getSkyValue` so any SkyKey directly requested
        // by this deserialization will be requested by this point and the notification can be sent.
        //
        // The inner reads could also call `getSkyValue` but have independent reference counting.
        skyframeLookupCollector.notifyFetchDone();
      }
      List<ListenableFuture<?>> innerReadStatusFutures = innerContext.readStatusFutures;
      if (innerReadStatusFutures == null || innerReadStatusFutures.isEmpty()) {
        Object result = deferred.call();
        try {
          setter.set(parent, result);
        } catch (SerializationException e) {
          getOperation.setException(e);
          return;
        }
        getOperation.set(result);
        return;
      }
      getOperation.setFuture(
          Futures.whenAllSucceed(innerReadStatusFutures)
              .call(
                  () -> {
                    Object result = deferred.call();
                    setter.set(parent, result);
                    return result;
                  },
                  directExecutor()));
    }

    @Override
    public void onFailure(Throwable t) {
      if (skyframeLookupCollector != null) {
        skyframeLookupCollector.notifyFetchException(t);
      }
      getOperation.setException(t);
    }
  }

  @Override
  public SharedValueDeserializationContext getFreshContext() {
    return new SharedValueDeserializationContext(
        getRegistry(), getDependencies(), fingerprintValueService, skyframeLookupCollector);
  }

  @Override
  Object makeSynchronous(Object obj) throws SerializationException {
    if (obj instanceof ListenableFuture<?> future) {
      return waitForDeserializationFuture(future);
    }
    return obj;
  }

  @Override
  Object deserializeAndMaybeHandleDeferredValues(ObjectCodec<?> codec, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int startingReadCount = readStatusFutures == null ? 0 : readStatusFutures.size();

    Object value =
        switch (codec) {
          // On other analogous codepaths, `ObjectCodec.safeCast' is applied to the resulting value.
          // Not all codecs have this property, notably DynamicCodec, but DeferredObjectCodec's type
          // parameters guarantee type of the deserialized value.
          case DeferredObjectCodec<?> deferredCodec ->
              deferredCodec.deserializeDeferred(this, codedIn);
          case InterningObjectCodec<?> interningCodec -> {
            Object initialValue = interningCodec.deserializeInterned(this, codedIn);
            @SuppressWarnings("unchecked")
            InterningObjectCodec<Object> castCodec = (InterningObjectCodec<Object>) interningCodec;
            yield new InterningDeferredValue(castCodec, codec.safeCast(initialValue));
          }
          default -> codec.safeCast(codec.deserialize(this, codedIn));
        };

    this.lastStartingReadCount = startingReadCount;
    return value;
  }

  private static class InterningDeferredValue implements DeferredValue<Object> {
    private final InterningObjectCodec<Object> codec;
    private final Object value;

    private InterningDeferredValue(InterningObjectCodec<Object> codec, Object value) {
      this.codec = codec;
      this.value = value;
    }

    @Override
    public Object call() {
      return codec.intern(value);
    }
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  Object combineValueWithReadFutures(Object value) {
    if (readStatusFutures == null) {
      return unwrapIfDeferredValue(value);
    }
    int length = readStatusFutures.size();
    if (length <= lastStartingReadCount) {
      return unwrapIfDeferredValue(value);
    }

    List<ListenableFuture<?>> futures = readStatusFutures.subList(lastStartingReadCount, length);
    Futures.FutureCombiner<?> combiner = Futures.whenAllSucceed(futures);
    futures.clear(); // clears this sublist from from `readStatusFutures`

    ListenableFuture<Object> futureValue;
    if (value instanceof DeferredValue) {
      @SuppressWarnings("unchecked")
      DeferredValue<Object> castValue = (DeferredValue<Object>) value;
      futureValue = combiner.call(castValue, directExecutor());
    } else {
      futureValue = combiner.call(() -> value, directExecutor());
    }
    readStatusFutures.add(futureValue);
    return futureValue;
  }

  private void addReadStatusFuture(ListenableFuture<?> readStatus) {
    if (readStatusFutures == null) {
      readStatusFutures = new ArrayList<>();
    }
    readStatusFutures.add(readStatus);
  }

  private static Object unwrapIfDeferredValue(Object value) {
    if (value instanceof DeferredValue) {
      @SuppressWarnings("unchecked")
      DeferredValue<Object> castValue = (DeferredValue<Object>) value;
      return castValue.call();
    }
    return value;
  }

  static final class SkyframeLookup<T> extends AbstractFuture<Void> implements QueryDepCallback {
    private final SkyKey key;
    private final T parent;
    private final FieldSetter<? super T> setter;

    /** Set true if the Skyframe dependency has an exception. */
    private boolean isFailed = false;

    private SkyframeLookup(SkyKey key, T parent, FieldSetter<? super T> setter) {
      this.key = key;
      this.parent = parent;
      this.setter = setter;
    }

    SkyKey getKey() {
      return key;
    }

    @Override
    public void acceptValue(SkyKey unusedKey, SkyValue value) {
      try {
        setter.set(parent, value);
        set(null);
      } catch (SerializationException e) {
        setException(e);
      }
    }

    @Override
    public boolean tryHandleException(SkyKey unusedKey, Exception e) {
      setException(new SkyframeDependencyException(e));
      return true;
    }

    boolean isFailed() {
      return isFailed;
    }

    void handleEviction(StateEvictedException exception) {
      // TODO: b/335901349 - fully handle this. Any retained instances of this future must be
      // cleared and anything that may be transitively depending on it must be cleaned up.
      setException(exception);
    }

    @Override
    @CanIgnoreReturnValue
    protected boolean setException(Throwable t) {
      this.isFailed = true;
      return super.setException(t);
    }
  }

  public static final class StateEvictedException extends Exception {}
}
