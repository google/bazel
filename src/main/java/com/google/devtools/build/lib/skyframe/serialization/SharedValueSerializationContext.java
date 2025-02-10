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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.FutureHelpers.reportAnyFailures;
import static com.google.devtools.build.lib.skyframe.serialization.FutureHelpers.waitForSerializationFuture;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.aggregateWriteStatuses;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.sparselyAggregateWriteStatuses;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A {@link SerializationContext} that supports both memoization and shared subobjects.
 *
 * <p>Sharing subobjects occurs by means of uploading them asynchronously to a {@link
 * #fingerprintValueService} store. The status of these uploads may be observed through {@link
 * #createFutureToBlockWritingOn} and {@link SerializationResult#getFutureToBlockWritesOn}.
 */
abstract class SharedValueSerializationContext extends MemoizingSerializationContext {
  final FingerprintValueService fingerprintValueService;

  /**
   * Futures that represent writes to remote storage.
   *
   * <p>For consistency, the serialized bytes should not be published for other consumers until
   * these writes complete.
   */
  @Nullable // lazily initialized
  private ArrayList<WriteStatus> futuresToBlockWritingOn;

  /**
   * Futures that mark deferred data bytes when using {@link #putSharedValue}.
   *
   * <p>The serialized byte representation contains placeholders until these futures complete.
   */
  @Nullable // lazily initialized
  private ArrayList<FuturePut> futurePuts;

  @VisibleForTesting // private
  static SharedValueSerializationContext createForTesting(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService) {
    return new SharedValueSerializationContextImpl(
        codecRegistry, dependencies, fingerprintValueService);
  }

  private SharedValueSerializationContext(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService) {
    super(codecRegistry, dependencies);
    this.fingerprintValueService = fingerprintValueService;
  }

  /**
   * Serializes {@code subject} and returns a result that may have an associated future.
   *
   * <p>This method may block when the serialization of a shared child is performed by another
   * thread. It blocks until the fingerprints of such shared children become available. However,
   * this method does not block on uploads to the {@link FingerprintValueService}. The status of the
   * upload is provided by {@link SerializationResult#getFutureToBlockWritesOn}.
   */
  static SerializationResult<ByteString> serializeToResult(
      ObjectCodecRegistry codecRegistry,
      ImmutableClassToInstanceMap<Object> dependencies,
      FingerprintValueService fingerprintValueService,
      @Nullable Object subject,
      @Nullable ProfileCollector profileCollector)
      throws SerializationException {
    ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytesOut);
    SharedValueSerializationContext context =
        profileCollector == null
            ? new SharedValueSerializationContextImpl(
                codecRegistry, dependencies, fingerprintValueService)
            : new SharedValueSerializationProfilingContext(
                codecRegistry, dependencies, fingerprintValueService, profileCollector);
    try {
      context.serialize(subject, codedOut);
      codedOut.flush();
    } catch (IOException e) {
      throw new SerializationException("Failed to serialize: " + subject, e);
    }
    if (profileCollector != null) {
      context.getProfileRecorder().checkStackEmpty(subject);
    }
    return context.createResult(bytesOut.toByteArray());
  }

  @Override
  public final <T> void putSharedValue(
      T child,
      @Nullable Object distinguisher,
      DeferredObjectCodec<T> codec,
      CodedOutputStream codedOut)
      throws IOException, SerializationException {
    SettableFuture<PutOperation> putOperation = SettableFuture.create();
    Object previous =
        fingerprintValueService.getOrClaimPutOperation(child, distinguisher, putOperation);
    if (previous != null) {
      // Uses the previous result and discards `putOperation`.
      if (previous instanceof ListenableFuture) {
        // HINT: this is the only place where `FuturePut`s originate. The other caller of
        // `recordFuturePut` propagates them transitively.
        @SuppressWarnings("unchecked")
        ListenableFuture<PutOperation> inflight = (ListenableFuture<PutOperation>) previous;
        recordFuturePut(inflight, codedOut);
        return;
      }
      ((PackedFingerprint) previous).writeTo(codedOut);
      return;
    }

    try {
      putOwnedSharedValue(child, codec, codedOut, putOperation);
    } catch (SerializationException | IOException e) {
      putOperation.setException(e);
      throw e;
    } catch (RuntimeException | Error e) {
      // `putOperation` must always be set to avoid deadlock-like behaviors in its consumers.
      putOperation.setException(e);
      throw e;
    }
  }

  /**
   * Implementation of {@link #putSharedValue} when it owns the {@code putOperation}.
   *
   * <p>This method does the following.
   *
   * <ul>
   *   <li>Uploads {@code child}'s bytes into {@link #fingerprintValueService}.
   *   <li>Sets the result of the upload in {@code putOperation}.
   *   <li>Writes the fingerprint of {@code child}'s serialized bytes (or a placeholder) to {@code
   *       codedOut}.
   *   <li>Adds either a {@link #futuresToBlockWritingOn} entry on {@link #futurePuts} entry.
   * </ul>
   *
   * <p>There are two cases, in particular.
   *
   * <ul>
   *   <li><em>Serializing {@code child} causes recursive calls to {@link #putSharedValue} and some
   *       of those recursive calls are are still being processed in a different thread</em>: this
   *       results in placeholders being added to the {@code codedOut} and {@link #futurePuts}
   *       entries to be recorded.
   *   <li><em>Any other case, for example, serializing {@code child} does not recursively call
   *       {@link #putSharedValue} or if it does, the fingerprints are either already available or
   *       owned by this thread</em>: here, the serialized bytes of {@code child} are computed in
   *       the calling thread and become immediately available. The status of uploading the
   *       serialized {@code child} is added to {@link #futuresToBlockWritingOn}.
   * </ul>
   *
   * <p>When entries are added to {@link #futurePuts}, the caller must ensure that for every entry,
   * the associated fingerprint must be written to the output bytes at {@link FuturePut#offset}. to
   * update placeholders present in the serialized bytes.
   */
  private final <T> void putOwnedSharedValue(
      T child,
      DeferredObjectCodec<T> codec,
      CodedOutputStream codedOut,
      SettableFuture<PutOperation> putOperation)
      throws IOException, SerializationException {
    byte[] childBytes; // bytes of serialized child, maybe containing placeholders
    // This is initially populated with the `futuresToWriteBlockingOn` of the child. If the child
    // has `FuturePut`s, the write statuses of those puts will be added to this list.
    ArrayList<WriteStatus> childWriteStatuses;
    // Each entry of the following list corresponds to a `putSharedValue` call made by serialization
    // of `child`. Used to fill-in `childBytes` placeholders.
    ArrayList<FuturePut> childDeferredBytes;
    { // Serializes `child` with a fresh context, populating the above variables.
      ByteArrayOutputStream childStream = new ByteArrayOutputStream();
      CodedOutputStream childCodedOut = CodedOutputStream.newInstance(childStream);
      SharedValueSerializationContext childContext = getFreshContext();

      ProfileRecorder childRecorder = childContext.getProfileRecorder();
      if (childRecorder == null) {
        codec.serialize(childContext, child, childCodedOut);
      } else {
        childRecorder.pushLocation(codec);
        codec.serialize(childContext, child, childCodedOut);
        childRecorder.recordBytesAndPopLocation(/* startBytes= */ 0, childCodedOut);
      }

      childCodedOut.flush();
      childBytes = childStream.toByteArray();
      childWriteStatuses =
          childContext.futuresToBlockWritingOn == null
              ? new ArrayList<>()
              : childContext.futuresToBlockWritingOn;
      childDeferredBytes = childContext.futurePuts;
    }

    if (childDeferredBytes == null) {
      // There are no deferred bytes so `childBytes` is complete. Starts the upload.
      PackedFingerprint fingerprint = fingerprintValueService.fingerprint(childBytes);
      fingerprint.writeTo(codedOut); // Writes only the fingerprint to the stream.
      childWriteStatuses.add(fingerprintValueService.put(fingerprint, childBytes));

      WriteStatus writeStatus = sparselyAggregateWriteStatuses(childWriteStatuses);
      putOperation.set(new PutOperation(fingerprint, writeStatus));
      addFutureToBlockWritingOn(writeStatus);
      return;
    }

    // Ensures enough space for the subvalues and one additional for the put of `child` itself.
    childWriteStatuses.ensureCapacity(childWriteStatuses.size() + childDeferredBytes.size() + 1);
    var upload =
        new UploadOnceChildBytesReady(fingerprintValueService, childWriteStatuses, childBytes);
    upload.registerFuturePuts(childDeferredBytes);

    putOperation.setFuture(upload);
    recordFuturePut(upload, codedOut);
  }

  private static final class UploadOnceChildBytesReady extends WaitForChildBytes<PutOperation> {
    private final FingerprintValueService fingerprintValueService;

    private UploadOnceChildBytesReady(
        FingerprintValueService fingerprintValueService,
        List<WriteStatus> childWriteStatuses,
        byte[] childBytes) {
      super(childWriteStatuses, childBytes);
      this.fingerprintValueService = fingerprintValueService;
    }

    @Override
    protected PutOperation getValue() {
      // All placeholders are filled-in. Starts the upload.
      PackedFingerprint fingerprint = fingerprintValueService.fingerprint(childBytes);
      childWriteStatuses.add(fingerprintValueService.put(fingerprint, childBytes));
      return new PutOperation(fingerprint, sparselyAggregateWriteStatuses(childWriteStatuses));
    }
  }

  @Override // to narrow the return type
  public abstract SharedValueSerializationContext getFreshContext();

  @Override
  public final void addFutureToBlockWritingOn(WriteStatus future) {
    if (futuresToBlockWritingOn == null) {
      futuresToBlockWritingOn = new ArrayList<>();
    }
    futuresToBlockWritingOn.add(future);
  }

  @Override
  @Nullable
  public final WriteStatus createFutureToBlockWritingOn() {
    if (futurePuts == null) {
      if (futuresToBlockWritingOn == null) {
        return null;
      }
      return aggregateWriteStatuses(futuresToBlockWritingOn);
    }

    List<WriteStatus> writeStatuses = initializeCombinedWriteStatusesList();
    for (FuturePut futurePut : futurePuts) {
      var putStatus = new SettableWriteStatus();
      Futures.addCallback(
          futurePut.getFuturePut(), new PutOperationCallback(putStatus), directExecutor());
      writeStatuses.add(putStatus);
    }
    return aggregateWriteStatuses(writeStatuses);
  }

  private static final class PutOperationCallback implements FutureCallback<PutOperation> {
    private final SettableWriteStatus putStatus;

    private PutOperationCallback(SettableWriteStatus putStatus) {
      this.putStatus = putStatus;
    }

    @Override
    public void onSuccess(PutOperation put) {
      putStatus.completeWith(put.writeStatus());
    }

    @Override
    public void onFailure(Throwable t) {
      putStatus.failWith(t);
    }
  }

  /**
   * Records a deferred fingerprint value to be written to the serialized bytes.
   *
   * <p>Adds the corresponding entry to {@link #futurePuts} and inserts placeholder bytes into
   * {@code codedOut}.
   */
  private final void recordFuturePut(
      ListenableFuture<PutOperation> futurePut, CodedOutputStream codedOut) throws IOException {
    if (futurePuts == null) {
      futurePuts = new ArrayList<>();
    }
    futurePuts.add(new FuturePut(codedOut.getTotalBytesWritten(), futurePut));
    // Adds a placeholder for the real fingerprint to be filled in after the future completes.
    fingerprintValueService.fingerprintPlaceholder().writeTo(codedOut);
  }

  private final SerializationResult<ByteString> createResult(byte[] bytes)
      throws SerializationException {
    // TODO: b/297857068 - If ByteString.copyFrom overhead is excessive, use reflection to avoid it.
    if (futurePuts == null) {
      ByteString finalBytes = ByteString.copyFrom(bytes);
      return futuresToBlockWritingOn == null
          ? SerializationResult.createWithoutFuture(finalBytes)
          : SerializationResult.create(finalBytes, aggregateWriteStatuses(futuresToBlockWritingOn));
    }

    List<WriteStatus> childWriteStatuses = initializeCombinedWriteStatusesList();
    var childBytesDone = new WaitForChildByteCompletion(childWriteStatuses, bytes);
    childBytesDone.registerFuturePuts(futurePuts);
    // `futurePuts` are produced when multiple threads race to write the same value. This is
    // expected to be relatively rare and waiting here is for CPU bound serialization work only.
    //
    // TODO: b/297857068 - It's possible to expose this intermediate future to callers as a
    // SerializationResult<ListenableFuture<byte[]>>. Do so if the contention outweighs the extra
    // complexity.
    try {
      waitForSerializationFuture(childBytesDone);
    } catch (SerializationException e) {
      // An exception has occurred. Ensures that any additional errors from writing are handled
      // before throwing the exception.
      if (futuresToBlockWritingOn != null) {
        reportAnyFailures(Futures.whenAllSucceed(futuresToBlockWritingOn));
      }
      throw e;
    }
    return SerializationResult.create(
        ByteString.copyFrom(bytes), aggregateWriteStatuses(childWriteStatuses));
  }

  private static final class WaitForChildByteCompletion extends WaitForChildBytes<Void> {
    private WaitForChildByteCompletion(List<WriteStatus> childWriteStatuses, byte[] childBytes) {
      super(childWriteStatuses, childBytes);
    }

    @Override
    protected Void getValue() {
      return null;
    }
  }

  /**
   * Initializes a write status list including any {@link #futuresToBlockWritingOn} and sized for
   * the caller to add statuses from {@link #futurePuts}.
   *
   * <p>The caller must ensure that {@link #futurePuts} is non-null.
   */
  private List<WriteStatus> initializeCombinedWriteStatusesList() {
    if (futuresToBlockWritingOn == null) {
      return new ArrayList<>(futurePuts.size());
    }
    ArrayList<WriteStatus> statuses =
        new ArrayList<>(futurePuts.size() + futuresToBlockWritingOn.size());
    statuses.addAll(futuresToBlockWritingOn);
    return statuses;
  }

  /**
   * A tuple consisting of a stream offset and a pending {@link PutOperation}.
   *
   * <p>When {@link PutOperation} becomes available, its {@link PutOperation#fingerprint} should be
   * copied to the output bytes starting at {@link #offset}.
   */
  private static final class FuturePut {
    /** Where the fingerprint should be written when it becomes available. */
    private final int offset;

    /**
     * A {@link PutOperation} that may have not yet started.
     *
     * <p>This arises when associated bytes could be mid-computation in a different thread. The
     * future completes when those bytes are known and its asynchronous write to the {@link
     * #fingerprintValueService} has started. {@link PutOperation#writeStatus} provides the status
     * of that write.
     */
    private final ListenableFuture<PutOperation> futurePut;

    private FuturePut(int offset, ListenableFuture<PutOperation> futurePut) {
      this.offset = offset;
      this.futurePut = futurePut;
    }

    private ListenableFuture<PutOperation> getFuturePut() {
      return futurePut;
    }
  }

  private abstract static class WaitForChildBytes<T> extends QuiescingFuture<T> {
    final List<WriteStatus> childWriteStatuses;
    final byte[] childBytes;

    private WaitForChildBytes(List<WriteStatus> childWriteStatuses, byte[] childBytes) {
      this.childWriteStatuses = childWriteStatuses;
      this.childBytes = childBytes;
    }

    void registerFuturePuts(Iterable<FuturePut> futurePuts) {
      for (FuturePut futurePut : futurePuts) {
        increment();
        Futures.addCallback(
            futurePut.getFuturePut(), new PutStartedCallback(futurePut.offset), directExecutor());
      }
      decrement(); // everything is registered
    }

    private void acceptPutOperation(PutOperation put, int offset) {
      put.fingerprint().copyTo(childBytes, offset);
      synchronized (childWriteStatuses) {
        childWriteStatuses.add(put.writeStatus());
      }
      decrement();
    }

    private void notifyFailedPutOperation(Throwable t) {
      notifyException(t);
    }

    private final class PutStartedCallback implements FutureCallback<PutOperation> {
      private final int offset;

      private PutStartedCallback(int offset) {
        this.offset = offset;
      }

      @Override
      public void onSuccess(PutOperation put) {
        acceptPutOperation(put, offset);
      }

      @Override
      public void onFailure(Throwable t) {
        notifyFailedPutOperation(t);
      }
    }
  }

  private static final class SharedValueSerializationContextImpl
      extends SharedValueSerializationContext {
    private SharedValueSerializationContextImpl(
        ObjectCodecRegistry codecRegistry,
        ImmutableClassToInstanceMap<Object> dependencies,
        FingerprintValueService fingerprintValueService) {
      super(codecRegistry, dependencies, fingerprintValueService);
    }

    @Override
    public SharedValueSerializationContext getFreshContext() {
      return new SharedValueSerializationContextImpl(
          getCodecRegistry(), getDependencies(), fingerprintValueService);
    }

    @Override
    @Nullable
    public ProfileRecorder getProfileRecorder() {
      return null;
    }
  }

  private static final class SharedValueSerializationProfilingContext
      extends SharedValueSerializationContext {
    private final ProfileRecorder profileRecorder;

    private SharedValueSerializationProfilingContext(
        ObjectCodecRegistry codecRegistry,
        ImmutableClassToInstanceMap<Object> dependencies,
        FingerprintValueService fingerprintValueService,
        ProfileCollector profileCollector) {
      super(codecRegistry, dependencies, fingerprintValueService);
      this.profileRecorder = new ProfileRecorder(profileCollector);
    }

    @Override
    public SharedValueSerializationContext getFreshContext() {
      return new SharedValueSerializationProfilingContext(
          getCodecRegistry(),
          getDependencies(),
          fingerprintValueService,
          profileRecorder.getProfileCollector());
    }

    @Override
    public ProfileRecorder getProfileRecorder() {
      return profileRecorder;
    }
  }
}
