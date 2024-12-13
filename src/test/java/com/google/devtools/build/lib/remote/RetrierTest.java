// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.Retrier.Backoff;
import com.google.devtools.build.lib.remote.Retrier.CircuitBreaker;
import com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State;
import com.google.devtools.build.lib.remote.Retrier.CircuitBreakerException;
import com.google.devtools.build.lib.remote.Retrier.ZeroBackoff;
import com.google.devtools.build.lib.testutil.TestUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link Retrier}.
 */
@RunWith(JUnit4.class)
public class RetrierTest {

  @Mock
  private CircuitBreaker alwaysOpen;

  private static final Predicate<Exception> RETRY_ALL = (e) -> true;
  private static final Predicate<Exception> RETRY_NONE = (e) -> false;

  private ListeningScheduledExecutorService retryService;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(alwaysOpen.state()).thenReturn(State.ACCEPT_CALLS);

    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  @After
  public void afterEverything() throws InterruptedException {
    retryService.shutdownNow();
    retryService.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void retryShouldWork_failure() throws Exception {
    // Test that a call is retried according to the backoff.
    // All calls fail.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, alwaysOpen);
    AtomicInteger numCalls = new AtomicInteger();
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                r.execute(
                    () -> {
                      numCalls.incrementAndGet();
                      throw new Exception("call failed");
                    }));
    assertThat(e).hasMessageThat().isEqualTo("call failed");

    assertThat(numCalls.get()).isEqualTo(3);
    verify(alwaysOpen, times(3)).recordFailure();
    verify(alwaysOpen, never()).recordSuccess();
  }

  @Test
  public void retryShouldWorkNoRetries_failure() throws Exception {
    // Test that a non-retriable error is not retried.
    // All calls fail.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/2);
    Retrier r = new Retrier(s, RETRY_NONE, retryService, alwaysOpen);
    AtomicInteger numCalls = new AtomicInteger();
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                r.execute(
                    () -> {
                      numCalls.incrementAndGet();
                      throw new Exception("call failed");
                    }));
    assertThat(e).hasMessageThat().isEqualTo("call failed");

    assertThat(numCalls.get()).isEqualTo(1);
    verify(alwaysOpen, never()).recordFailure();
    verify(alwaysOpen, times(1)).recordSuccess();
  }

  @Test
  public void retryShouldWork_success() throws Exception {
    // Test that a call is retried according to the backoff.
    // The last call succeeds.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, alwaysOpen);
    AtomicInteger numCalls = new AtomicInteger();
    int val = r.execute(() -> {
      numCalls.incrementAndGet();
      if (numCalls.get() == 3) {
        return 1;
      }
      throw new Exception("call failed");
    });
    assertThat(val).isEqualTo(1);

    verify(alwaysOpen, times(2)).recordFailure();
    verify(alwaysOpen, times(1)).recordSuccess();
  }

  @Test
  public void nestedRetriesShouldWork() throws Exception {
    // Test that nested calls using retries compose as expected.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/1);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, alwaysOpen);

    AtomicInteger attemptsLvl0 = new AtomicInteger();
    AtomicInteger attemptsLvl1 = new AtomicInteger();
    AtomicInteger attemptsLvl2 = new AtomicInteger();
    try {
      r.execute(
          () -> {
            attemptsLvl0.incrementAndGet();
            return r.execute(
                () -> {
                  attemptsLvl1.incrementAndGet();
                  return r.execute(
                      () -> {
                        attemptsLvl2.incrementAndGet();
                        throw new Exception("failure message");
                      });
                });
          });
    } catch (Exception e) {
      assertThat(e).hasMessageThat().isEqualTo("failure message");
      assertThat(attemptsLvl0.get()).isEqualTo(2);
      assertThat(attemptsLvl1.get()).isEqualTo(4);
      assertThat(attemptsLvl2.get()).isEqualTo(8);
    }
  }

  @Test
  public void circuitBreakerShouldTrip() throws Exception {
    // Test that a circuit breaker can trip.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/3);
    TripAfterNCircuitBreaker cb = new TripAfterNCircuitBreaker(/*maxConsecutiveFailures=*/2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, cb);

    assertThrows(
        CircuitBreakerException.class,
        () ->
            r.execute(
                () -> {
                  throw new Exception("call failed");
                }));

    assertThat(cb.state()).isEqualTo(State.REJECT_CALLS);
    assertThat(cb.consecutiveFailures).isEqualTo(2);
  }

  @Test
  public void circuitBreakerCanRecover() throws Exception {
    // Test that a circuit breaker can recover from REJECT_CALLS to ACCEPT_CALLS by
    // utilizing the TRIAL_CALL state.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/3);
    TripAfterNCircuitBreaker cb = new TripAfterNCircuitBreaker(/*maxConsecutiveFailures=*/2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, cb);

    cb.trialCall();

    assertThat(cb.state()).isEqualTo(State.TRIAL_CALL);

    int val = r.execute(() -> 10);
    assertThat(val).isEqualTo(10);
    assertThat(cb.state()).isEqualTo(State.ACCEPT_CALLS);
  }

  @Test
  public void circuitBreakerHalfOpenIsNotRetried() throws Exception {
    // Test that a call executed in TRIAL_CALL state is not retried
    // in case of failure.

    Supplier<Backoff> s  = () -> new ZeroBackoff(/*maxRetries=*/3);
    TripAfterNCircuitBreaker cb = new TripAfterNCircuitBreaker(/*maxConsecutiveFailures=*/2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, cb);

    cb.trialCall();

    try {
      r.execute(
          () -> {
            throw new Exception("call failed");
          });
    } catch (Exception expected) {
      // Intentionally left empty.
    }

    assertThat(cb.consecutiveFailures).isEqualTo(1);
  }

  @Test
  public void interruptsShouldNotBeRetried_flag() throws Exception {
    // Test that a call is not executed / retried if the current thread
    // is interrupted.

    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 3);
    TripAfterNCircuitBreaker cb = new TripAfterNCircuitBreaker(/*maxConsecutiveFailures=*/ 2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, cb);

    AtomicInteger numCalls = new AtomicInteger();
    Thread.currentThread().interrupt();
    assertThrows(
        InterruptedException.class,
        () ->
            r.execute(
                () -> {
                  numCalls.incrementAndGet();
                  return 10;
                }));
    assertThat(numCalls.get()).isEqualTo(0);
  }

  @Test
  public void interruptsShouldNotBeRetried_exception() throws Exception {
    // Test that a call is not retried if an InterruptedException is thrown.

    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 3);
    TripAfterNCircuitBreaker cb = new TripAfterNCircuitBreaker(/*maxConsecutiveFailures=*/ 2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, cb);

    AtomicInteger numCalls = new AtomicInteger();
    assertThrows(
        InterruptedException.class,
        () ->
            r.execute(
                () -> {
                  numCalls.incrementAndGet();
                  throw new InterruptedException();
                }));
    assertThat(numCalls.get()).isEqualTo(1);
  }

  @Test
  public void asyncRetryExhaustRetries() throws Exception {
    // Test that a call is retried according to the backoff.
    // All calls fail.

    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 2);
    Retrier r = new Retrier(s, RETRY_ALL, retryService, alwaysOpen);
    AtomicInteger numCalls = new AtomicInteger();
    ListenableFuture<Void> res =
        r.executeAsync(
            () -> {
              numCalls.incrementAndGet();
              throw new Exception("call failed");
            });
    ExecutionException e = assertThrows(ExecutionException.class, () -> res.get());
    assertThat(numCalls.get()).isEqualTo(3);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("call failed");
  }

  @Test
  public void asyncRetryNonRetriable() throws Exception {
    // Test that a call is retried according to the backoff.
    // All calls fail.

    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 2);
    Retrier r = new Retrier(s, RETRY_NONE, retryService, alwaysOpen);
    AtomicInteger numCalls = new AtomicInteger();
    ListenableFuture<Void> res =
        r.executeAsync(
            () -> {
              numCalls.incrementAndGet();
              throw new Exception("call failed");
            });
    ExecutionException e = assertThrows(ExecutionException.class, () -> res.get());
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("call failed");
    assertThat(numCalls.get()).isEqualTo(1);
  }

  @Test
  public void asyncRetryEmptyError() throws Exception {
    // Test that a call is retried according to the backoff.
    // All calls fail.

    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 2);
    Retrier r = new Retrier(s, RETRY_NONE, retryService, alwaysOpen);
    ListenableFuture<Void> res =
        r.executeAsync(
            () -> {
              throw new Exception("");
            });
    ExecutionException e = assertThrows(ExecutionException.class, () -> res.get());
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("");
  }

  @Test
  public void testCircuitBreakerFailureAndSuccessCallOnDifferentGrpcError() {
    int maxRetries = 2;
    Supplier<Backoff> s = () -> new ZeroBackoff(maxRetries);
    List<Status> retriableGrpcError =
        Arrays.asList(Status.ABORTED, Status.UNKNOWN, Status.DEADLINE_EXCEEDED);
    List<Status> nonRetriableGrpcError =
        Arrays.asList(Status.NOT_FOUND, Status.OUT_OF_RANGE, Status.ALREADY_EXISTS);
    TripAfterNCircuitBreaker cb =
        new TripAfterNCircuitBreaker(retriableGrpcError.size() * (maxRetries + 1));
    Retrier r = new Retrier(s, RemoteRetrier.RETRIABLE_GRPC_ERRORS, retryService, cb);

    int expectedConsecutiveFailures = 0;

    for (Status status : retriableGrpcError) {
      ListenableFuture<Void> res =
          r.executeAsync(
              () -> {
                throw new StatusRuntimeException(status);
              });
      expectedConsecutiveFailures += maxRetries + 1;
      assertThrows(ExecutionException.class, res::get);
      assertThat(cb.consecutiveFailures).isEqualTo(expectedConsecutiveFailures);
    }

    assertThat(cb.state).isEqualTo(State.REJECT_CALLS);
    cb.trialCall();

    for (Status status : nonRetriableGrpcError) {
      ListenableFuture<Void> res =
          r.executeAsync(
              () -> {
                throw new StatusRuntimeException(status);
              });
      assertThat(cb.consecutiveFailures).isEqualTo(0);
      assertThrows(ExecutionException.class, res::get);
    }
    assertThat(cb.state).isEqualTo(State.ACCEPT_CALLS);
  }

  @Test
  public void testCircuitBreakerFailureAndSuccessCallOnNonRetriableGrpcError() {
    Supplier<Backoff> s = () -> new ZeroBackoff(/*maxRetries=*/ 2);
    List<Status> nonRetriableFailure =
        Arrays.asList(Status.PERMISSION_DENIED, Status.UNIMPLEMENTED, Status.DATA_LOSS);
    List<Status> nonRetriableSuccess =
        Arrays.asList(Status.NOT_FOUND, Status.OUT_OF_RANGE, Status.ALREADY_EXISTS);
    TripAfterNCircuitBreaker cb =
        new TripAfterNCircuitBreaker(nonRetriableFailure.size());
    Retrier r = new Retrier(s, RemoteRetrier.RETRIABLE_GRPC_ERRORS, retryService, cb, RemoteRetrier.GRPC_SUCCESS_CODES);

    int expectedConsecutiveFailures = 0;

    for (Status status : nonRetriableFailure) {
      ListenableFuture<Void> res =
          r.executeAsync(
              () -> {
                throw new StatusRuntimeException(status);
              });
      expectedConsecutiveFailures += 1;
      assertThrows(ExecutionException.class, res::get);
      assertThat(cb.consecutiveFailures).isEqualTo(expectedConsecutiveFailures);
    }

    assertThat(cb.state).isEqualTo(State.REJECT_CALLS);
    cb.trialCall();

    for (Status status : nonRetriableSuccess) {
      ListenableFuture<Void> res =
          r.executeAsync(
              () -> {
                throw new StatusRuntimeException(status);
              });
      assertThat(cb.consecutiveFailures).isEqualTo(0);
      assertThrows(ExecutionException.class, res::get);
    }
    assertThat(cb.state).isEqualTo(State.ACCEPT_CALLS);
  }

  /** Simple circuit breaker that trips after N consecutive failures. */
  @ThreadSafe
  private static class TripAfterNCircuitBreaker implements CircuitBreaker {

    private final int maxConsecutiveFailures;

    private State state = State.ACCEPT_CALLS;
    private int consecutiveFailures;

    TripAfterNCircuitBreaker(int maxConsecutiveFailures) {
      this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    @Override
    public synchronized State state() {
      return state;
    }

    @Override
    public synchronized void recordFailure() {
      consecutiveFailures++;
      if (consecutiveFailures >= maxConsecutiveFailures) {
        state = State.REJECT_CALLS;
      }
    }

    @Override
    public synchronized void recordSuccess() {
      consecutiveFailures = 0;
      state = State.ACCEPT_CALLS;
    }

    void trialCall() {
      state = State.TRIAL_CALL;
    }
  }
}
