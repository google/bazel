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
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion.CONSTANT_FOR_TESTING;
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.NoCachedData.NO_CACHED_DATA;
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.ObservedFutureStatus.DONE;
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.ObservedFutureStatus.NOT_DONE;
import static com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.Restart.RESTART;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.FakeInvalidationDataHelper.prependFakeInvalidationData;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.ObservedFutureStatus;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievedValue;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializationState;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializationStateProvider;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.WaitingForFutureLookupContinuation;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.WaitingForFutureResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.WaitingForFutureValueBytes;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.WaitingForLookupContinuation;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.testutils.GetRecordingStore;
import com.google.devtools.build.skyframe.IntVersion;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class SkyValueRetrieverTest implements SerializationStateProvider {
  /** Default implementation that errors if any keys are requested. */
  private static final EnvironmentForUtilities NO_LOOKUP_ENVIRONMENT =
      new EnvironmentForUtilities(
          k -> {
            throw new IllegalStateException("no requests expected, got " + k);
          });

  private ObjectCodecs codecs = new ObjectCodecs();

  private SerializationState state = SkyValueRetriever.INITIAL_STATE;

  private enum InitialQueryCases {
    IMMEDIATE_EMPTY_VALUE,
    IMMEDIATE_MISSING_VALUE,
    FUTURE_VALUE
  }

  /**
   * Test case covering {@link SkyValueRetriever.InitialQuery} and the beginning of {@link
   * WaitingForFutureValueBytes}.
   */
  @Test
  public void initialQueryState_progressesToWaiting(@TestParameter InitialQueryCases testCase)
      throws Exception {
    FingerprintValueService fingerprintValueService =
        switch (testCase) {
          case IMMEDIATE_EMPTY_VALUE, IMMEDIATE_MISSING_VALUE ->
              FingerprintValueService.createForTesting();
          // The GetRecordingStore returns a ListenableFuture response that requires explicit
          // setting. Not setting the future is enough to elicit a Restart.RESTART response.
          case FUTURE_VALUE -> FingerprintValueService.createForTesting(new GetRecordingStore());
        };

    var key = new TrivialKey("a");
    SerializationResult<ByteString> keyBytes =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, key, /* profileCollector= */ null);
    assertThat(keyBytes.getFutureToBlockWritesOn()).isNull();

    if (testCase.equals(InitialQueryCases.IMMEDIATE_EMPTY_VALUE)) {
      assertThat(
              fingerprintValueService
                  .put(fingerprintValueService.fingerprint(keyBytes.getObject()), new byte[0])
                  .get())
          .isNull();
    }

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    if (testCase.equals(InitialQueryCases.FUTURE_VALUE)) {
      assertThat(state).isInstanceOf(WaitingForFutureValueBytes.class);
      assertThat(result).isEqualTo(RESTART);
    } else {
      assertThat(state).isSameInstanceAs(NO_CACHED_DATA);
      assertThat(result).isSameInstanceAs(NO_CACHED_DATA);
    }
  }

  @Test
  public void waitingForFutureValueBytes_returnsImmediateValue() throws Exception {
    // Exercises a scenario without shared values so value is available immediately.
    var fingerprintValueService = FingerprintValueService.createForTesting();

    var key = new TrivialKey("a");
    var value = new TrivialValue("abc");
    uploadKeyValuePair(key, value, fingerprintValueService);

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(((RetrievedValue) result).value()).isEqualTo(value);
    assertThat(state).isInstanceOf(RetrievedValue.class);
  }

  @Test
  public void waitingForFutureValueBytes_missingFingerprintReturnsNoCachedData() throws Exception {
    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            FingerprintValueService.createForTesting(),
            new TrivialKey("a"), // nothing is uploaded to the service
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isSameInstanceAs(NO_CACHED_DATA);
    assertThat(state).isSameInstanceAs(NO_CACHED_DATA);
  }

  @Test
  public void waitingForFutureValueBytes_withMatchingDistinguisher_returnsImmediateValue()
      throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();

    var key = new TrivialKey("a");
    var value = new TrivialValue("abc");
    var version =
        new FrontierNodeVersion(
            /* topLevelConfigChecksum= */ "42",
            /* directoryMatcherStringRepr= */ "some_string",
            /* blazeInstallMD5= */ HashCode.fromInt(42),
            /* evaluatingVersion= */ IntVersion.of(9000),
            /* clientId= */ Optional.of(new ClientId("for/testing", 123)));
    uploadKeyValuePair(key, version, value, fingerprintValueService);

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            fingerprintValueService,
            key,
            /* stateProvider= */ this,
            /* frontierNodeVersion= */ version);

    assertThat(((RetrievedValue) result).value()).isEqualTo(value);
  }

  @Test
  public void waitingForFutureValueBytes_withNonMatchingDistinguisher_returnsNoCachedData()
      throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();

    var key = new TrivialKey("a");
    var value = new TrivialValue("abc");
    var version =
        new FrontierNodeVersion(
            /* topLevelConfigChecksum= */ "42",
            /* directoryMatcherStringRepr= */ "some_string",
            /* blazeInstallMD5= */ HashCode.fromInt(42),
            /* evaluatingVersion= */ IntVersion.of(1234),
            /* clientId= */ Optional.of(new ClientId("for/testing", 123)));
    uploadKeyValuePair(key, version, value, fingerprintValueService);

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            FingerprintValueService.createForTesting(),
            new TrivialKey("a"), // same key..
            /* stateProvider= */ this,
            /* frontierNodeVersion= */ new FrontierNodeVersion(
                /* topLevelConfigChecksum= */ "9000",
                /* directoryMatcherStringRepr= */ "another_string",
                /* blazeInstallMD5= */ HashCode.fromInt(9000),
                /* evaluatingVersion= */ IntVersion.of(5678),
                /* clientId= */ Optional.of(new ClientId("for/testing", 123))));

    assertThat(result).isSameInstanceAs(NO_CACHED_DATA);
  }

  @Test
  public void tryRetrieve_withoutRestarts_returnsValue() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();
    codecs = codecs.withCodecOverridesForTesting(ImmutableList.of(new TrivialValueSharingCodec()));

    var key = new TrivialKey("a");
    var value = new TrivialValue("abc");
    uploadKeyValuePair(key, value, fingerprintValueService);

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::alwaysDoneDependOnFuture,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(((RetrievedValue) result).value()).isEqualTo(value);
  }

  @Test
  public void tryRetrieve_withAllFutureRestarts_completes() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();
    codecs = codecs.withCodecOverridesForTesting(ImmutableList.of(new TrivialValueSharingCodec()));

    var key = new TrivialKey("a");
    var value = new TrivialValue("abc");
    uploadKeyValuePair(key, value, fingerprintValueService);

    var capturingShim = new CapturingShim();

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            capturingShim,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);
    // The underlying future bytes are set immediately by the in-memory FingerprintValueStore, but
    // the `capturingShim` returns `NOT_DONE`, triggering a restart.
    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureValueBytes.class);

    // Calling `tryRetrieve` a 2nd time triggers another fetch, this time for the shared value.
    result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            capturingShim,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureLookupContinuation.class);

    // Waits for the future to be set (from an executor thread) before restarting.
    Object unused = capturingShim.captured.get();

    // Calling `tryRetrieve` again progresses through both WaitingForFutureLookupContinuation and
    // WaitingForLookupContinuation.
    result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            capturingShim,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureResult.class);

    // Waits for the future to be set (from an executor thread) before restarting.
    unused = capturingShim.captured.get();

    // The final invocation produces the value.
    result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(((RetrievedValue) result).value()).isEqualTo(value);
  }

  @Test
  public void tryRetrieve_withSkyframeRestart_completes() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();

    var key = new ExampleKey("a");
    var value = new ExampleValue(key, 10);
    uploadKeyValuePair(key, value, fingerprintValueService);

    var capturedKey = new SkyKey[1];

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            new EnvironmentForUtilities(
                k -> {
                  assertThat(capturedKey[0]).isNull();
                  capturedKey[0] = k;
                  return null;
                }),
            SkyValueRetrieverTest::alwaysDoneDependOnFuture,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(capturedKey[0]).isEqualTo(key);
    assertThat(state).isInstanceOf(WaitingForLookupContinuation.class);

    result =
        SkyValueRetriever.tryRetrieve(
            new EnvironmentForUtilities(
                k -> {
                  assertThat(k).isEqualTo(key);
                  return value;
                }),
            SkyValueRetrieverTest::alwaysDoneDependOnFuture,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(((RetrievedValue) result).value()).isEqualTo(value);
  }

  @Test
  public void retrievalError_throwsException() throws Exception {
    var recordingStore = new GetRecordingStore();
    var fingerprintValueService = FingerprintValueService.createForTesting(recordingStore);

    var key = new TrivialKey("k");
    var value = new TrivialValue("v");
    uploadKeyValuePair(key, value, fingerprintValueService);

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            SkyValueRetrieverTest::dependOnFutureImpl,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureValueBytes.class);

    var error = new IOException();
    recordingStore.pollRequest().response().setException(error);

    var thrown =
        assertThrows(
            SerializationException.class,
            () ->
                SkyValueRetriever.tryRetrieve(
                    NO_LOOKUP_ENVIRONMENT,
                    SkyValueRetrieverTest::dependOnFutureImpl,
                    codecs,
                    fingerprintValueService,
                    key,
                    (SerializationStateProvider) this,
                    /* frontierNodeVersion= */ CONSTANT_FOR_TESTING));

    assertThat(thrown).hasMessageThat().contains("getting value bytes for " + key);
    assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error);
  }

  @Test
  public void sharedValueRetrievalError_throwsException() throws Exception {
    var recordingStore = new GetRecordingStore();
    var fingerprintValueService = FingerprintValueService.createForTesting(recordingStore);

    codecs = codecs.withCodecOverridesForTesting(ImmutableList.of(new TrivialValueSharingCodec()));

    var key = new TrivialKey("k");
    var value = new TrivialValue("v");
    uploadKeyValuePair(key, value, fingerprintValueService);

    var capturingShim = new CapturingShim();

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            capturingShim,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureValueBytes.class);

    recordingStore.pollRequest().complete();

    result =
        SkyValueRetriever.tryRetrieve(
            NO_LOOKUP_ENVIRONMENT,
            capturingShim,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(state).isInstanceOf(WaitingForFutureLookupContinuation.class);

    var error = new IOException();
    recordingStore.pollRequest().response().setException(error);

    // Waits for the injected error to propagate through an executor thread.
    assertThrows(ExecutionException.class, () -> capturingShim.captured.get());

    var thrown =
        assertThrows(
            SerializationException.class,
            () ->
                SkyValueRetriever.tryRetrieve(
                    NO_LOOKUP_ENVIRONMENT,
                    capturingShim,
                    codecs,
                    fingerprintValueService,
                    key,
                    (SerializationStateProvider) this,
                    /* frontierNodeVersion= */ CONSTANT_FOR_TESTING));

    assertThat(thrown).hasMessageThat().contains("waiting for all owned shared values for " + key);
    assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error);
  }

  @Test
  public void skyframeLookupError_throwsException() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();

    var key = new ExampleKey("a");
    var value = new ExampleValue(key, 10);
    uploadKeyValuePair(key, value, fingerprintValueService);

    var capturedKey = new SkyKey[1];

    RetrievalResult result =
        SkyValueRetriever.tryRetrieve(
            new EnvironmentForUtilities(
                k -> {
                  assertThat(capturedKey[0]).isNull();
                  capturedKey[0] = k;
                  return null;
                }),
            SkyValueRetrieverTest::alwaysDoneDependOnFuture,
            codecs,
            fingerprintValueService,
            key,
            (SerializationStateProvider) this,
            /* frontierNodeVersion= */ CONSTANT_FOR_TESTING);

    assertThat(result).isEqualTo(RESTART);
    assertThat(capturedKey[0]).isEqualTo(key);
    assertThat(state).isInstanceOf(WaitingForLookupContinuation.class);

    var error = new Exception();

    var thrown =
        assertThrows(
            SerializationException.class,
            () ->
                SkyValueRetriever.tryRetrieve(
                    new EnvironmentForUtilities(
                        k -> {
                          assertThat(k).isEqualTo(key);
                          return error;
                        }),
                    SkyValueRetrieverTest::alwaysDoneDependOnFuture,
                    codecs,
                    fingerprintValueService,
                    key,
                    (SerializationStateProvider) this,
                    /* frontierNodeVersion= */ CONSTANT_FOR_TESTING));

    assertThat(thrown)
        .hasMessageThat()
        .contains("skyframe dependency error during deserialization for " + key);
    assertThat(thrown).hasCauseThat().isInstanceOf(SkyframeDependencyException.class);
    assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error);
  }

  @Test
  public void exceptionWhileWaitingForResult_throwsException() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();

    codecs = codecs.withCodecOverridesForTesting(ImmutableList.of(new FaultyTrivialValueCodec()));

    var key = new TrivialKey("k");
    var value = new TrivialValue("v");
    uploadKeyValuePair(key, value, fingerprintValueService);

    var thrown =
        assertThrows(
            SerializationException.class,
            () ->
                SkyValueRetriever.tryRetrieve(
                    NO_LOOKUP_ENVIRONMENT,
                    SkyValueRetrieverTest::alwaysDoneDependOnFuture,
                    codecs,
                    fingerprintValueService,
                    key,
                    (SerializationStateProvider) this,
                    /* frontierNodeVersion= */ CONSTANT_FOR_TESTING));

    assertThat(thrown).hasMessageThat().contains("waiting for deserialization result for " + key);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("error setting value");
  }

  @Test
  public void frontierNodeVersions_areEqual_ifTupleComponentsAreEqual() {
    var first =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());
    var second =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isEqualTo(second);
  }

  @Test
  public void frontierNodeVersions_areNotEqual_ifTopLevelConfigChecksumIsDifferent() {
    var first =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());
    var second =
        new FrontierNodeVersion(
            "CHANGED", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void frontierNodeVersions_areNotEqual_ifActiveDirectoriesAreDifferent() {
    var first =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());
    var second =
        new FrontierNodeVersion(
            "foo", "CHANGED", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void frontierNodeVersions_areNotEqual_ifBlazeInstallMD5IsDifferent() {
    var first =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());
    var second =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(9000), IntVersion.of(9000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void frontierNodeVersions_areNotEqual_ifEvaluatingVersionIsDifferent() {
    var first =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(42), IntVersion.of(9000), Optional.empty());
    var second =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(9000), IntVersion.of(10000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void frontierNodeVersions_areEqual_evenIfSnapshotIsDifferent() {
    var first =
        new FrontierNodeVersion(
            "foo",
            "bar",
            HashCode.fromInt(42),
            IntVersion.of(9000),
            Optional.of(new ClientId("changed", 123)));
    var second =
        new FrontierNodeVersion(
            "foo", "bar", HashCode.fromInt(9000), IntVersion.of(9000), Optional.empty());

    assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint());
    assertThat(first).isNotEqualTo(second);
  }

  // ---------- Begin SerializationStateProvider implementation ----------
  @Override
  public SerializationState getSerializationState() {
    return state;
  }

  @Override
  public void setSerializationState(SerializationState state) {
    this.state = state;
  }

  // ---------- End SerializationStateProvider implementation ----------

  private void uploadKeyValuePair(
      SkyKey key, SkyValue value, FingerprintValueService fingerprintValueService)
      throws SerializationException, InterruptedException, ExecutionException {
    uploadKeyValuePair(key, CONSTANT_FOR_TESTING, value, fingerprintValueService);
  }

  private void uploadKeyValuePair(
      SkyKey key,
      FrontierNodeVersion version,
      SkyValue value,
      FingerprintValueService fingerprintValueService)
      throws SerializationException, InterruptedException, ExecutionException {
    SerializationResult<ByteString> keyBytes =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, key, /* profileCollector= */ null);
    ListenableFuture<?> writeStatus = keyBytes.getFutureToBlockWritesOn();
    if (writeStatus != null) {
      var unused = writeStatus.get();
    }

    SerializationResult<ByteString> valueBytes =
        codecs.serializeMemoizedAndBlocking(
            fingerprintValueService, value, /* profileCollector= */ null);
    writeStatus = keyBytes.getFutureToBlockWritesOn();
    if (writeStatus != null) {
      var unused = writeStatus.get();
    }

    var unused =
        fingerprintValueService
            .put(
                fingerprintValueService.fingerprint(
                    version.concat(keyBytes.getObject().toByteArray())),
                prependFakeInvalidationData(valueBytes.getObject()).toByteArray())
            .get();
  }

  private static ObservedFutureStatus dependOnFutureImpl(ListenableFuture<?> future) {
    return future.isDone() ? DONE : NOT_DONE;
  }

  private static ObservedFutureStatus alwaysDoneDependOnFuture(ListenableFuture<?> future) {
    // Although the in-memory FingerprintValueStore is synchronous, the returned bytes are
    // processed asynchronously on an executor. There are 3 places where this may be called and 2
    // where the future might still be unset.
    //
    // 1. At the end of WaitingForFutureValueBytes, there's a wait for the
    //    SkyframeLookupContinuation to become available. That happens on the executor
    //    thread that processes the shared bytes.
    // 2. At the end of WaitingForLookupContinuation, there's a small wait for the final result.
    //    This wait corresponds to setting the shared value in the parent and happens on the
    //    executor thread after 1 so the caller might observe an unset future.
    try {
      var unused = future.get();
    } catch (ExecutionException | InterruptedException e) {
      // Exceptions are ignored here but handled by the next state, which always calls getDone.
    }
    return DONE;
  }

  private static class CapturingShim implements SkyValueRetriever.DependOnFutureShim {
    private ListenableFuture<?> captured;

    @Override
    public ObservedFutureStatus dependOnFuture(ListenableFuture<?> future) {
      this.captured = future;
      return NOT_DONE;
    }
  }

  @AutoCodec
  @VisibleForSerialization
  record TrivialKey(String text) implements SkyKey {
    @Override
    public SkyFunctionName functionName() {
      throw new UnsupportedOperationException();
    }
  }

  @AutoCodec
  @VisibleForSerialization
  record TrivialValue(String text) implements SkyValue {}

  private static final DeferredObjectCodec<TrivialValue> TRIVIAL_SKY_VALUE_CODEC =
      new SkyValueRetrieverTest_TrivialValue_AutoCodec();

  private static final class TrivialValueSharingCodec extends DeferredObjectCodec<TrivialValue> {
    @Override
    public Class<TrivialValue> getEncodedClass() {
      return TrivialValue.class;
    }

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public void serialize(
        SerializationContext context, TrivialValue value, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(value, /* distinguisher= */ null, TRIVIAL_SKY_VALUE_CODEC, codedOut);
    }

    @Override
    public DeferredValue<TrivialValue> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      var builder = SimpleDeferredValue.<TrivialValue>create();
      context.getSharedValue(
          codedIn,
          /* distinguisher= */ null,
          TRIVIAL_SKY_VALUE_CODEC,
          builder,
          SimpleDeferredValue::set);
      return builder;
    }
  }

  private static final class FaultyTrivialValueCodec extends DeferredObjectCodec<TrivialValue> {
    @Override
    public Class<TrivialValue> getEncodedClass() {
      return TrivialValue.class;
    }

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public void serialize(
        SerializationContext context, TrivialValue obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(obj, /* distinguisher= */ null, TRIVIAL_SKY_VALUE_CODEC, codedOut);
    }

    @Override
    public DeferredValue<TrivialValue> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      var builder = SimpleDeferredValue.<TrivialValue>create();
      context.getSharedValue(
          codedIn,
          /* distinguisher= */ null,
          TRIVIAL_SKY_VALUE_CODEC,
          builder,
          (b, v) -> {
            throw new SerializationException("error setting value");
          });
      return builder;
    }
  }
}
