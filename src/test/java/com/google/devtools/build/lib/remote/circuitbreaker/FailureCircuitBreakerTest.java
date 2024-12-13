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
package com.google.devtools.build.lib.remote.circuitbreaker;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.remote.Retrier.CircuitBreaker.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FailureCircuitBreakerTest {

  @Test
  public void testRecordFailure_circuitTrips() throws InterruptedException {
    final int failureRateThreshold = 10;
    final int windowInterval = 100;
    FailureCircuitBreaker failureCircuitBreaker =
        new FailureCircuitBreaker(failureRateThreshold, windowInterval);

    List<Runnable> listOfSuccessAndFailureCalls = new ArrayList<>();
    for (int index = 0; index < failureRateThreshold; index++) {
      listOfSuccessAndFailureCalls.add(failureCircuitBreaker::recordFailure);
    }

    for (int index = 0; index < failureRateThreshold * 9; index++) {
      listOfSuccessAndFailureCalls.add(failureCircuitBreaker::recordSuccess);
    }

    Collections.shuffle(listOfSuccessAndFailureCalls);

    // make calls equals to threshold number of not ignored failure calls in parallel.
    listOfSuccessAndFailureCalls.stream().parallel().forEach(Runnable::run);
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS);

    // Sleep for windowInterval + 5ms.
    Thread.sleep(windowInterval + 5 /*to compensate any delay*/);

    // make calls equals to threshold number of not ignored failure calls in parallel.
    listOfSuccessAndFailureCalls.stream().parallel().forEach(Runnable::run);
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS);

    // Sleep for less than windowInterval.
    Thread.sleep(windowInterval - 5);
    failureCircuitBreaker.recordFailure();
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS);
  }

  @Test
  public void testRecordFailure_minCallCriteriaNotMet() throws InterruptedException {
    final int failureRateThreshold = 0;
    final int windowInterval = 100;
    final int minCallToComputeFailure =
        CircuitBreakerFactory.DEFAULT_MIN_CALL_COUNT_TO_COMPUTE_FAILURE_RATE;
    FailureCircuitBreaker failureCircuitBreaker =
        new FailureCircuitBreaker(failureRateThreshold, windowInterval);

    // make success calls, failure call and number of total calls less than
    // minCallToComputeFailure.
    IntStream.range(0, minCallToComputeFailure - 2)
        .parallel()
        .forEach(i -> failureCircuitBreaker.recordSuccess());
    failureCircuitBreaker.recordFailure();
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS);

    // Sleep for less than windowInterval.
    Thread.sleep(windowInterval - 50);
    failureCircuitBreaker.recordFailure();
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS);
  }

  @Test
  public void testRecordFailure_minFailCriteriaNotMet() throws InterruptedException {
    final int failureRateThreshold = 10;
    final int windowInterval = 100;
    final int minFailToComputeFailure =
        CircuitBreakerFactory.DEFAULT_MIN_FAIL_COUNT_TO_COMPUTE_FAILURE_RATE;
    FailureCircuitBreaker failureCircuitBreaker =
        new FailureCircuitBreaker(failureRateThreshold, windowInterval);

    // make number of failure calls less than minFailToComputeFailure.
    IntStream.range(0, minFailToComputeFailure - 1)
        .parallel()
        .forEach(i -> failureCircuitBreaker.recordFailure());
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.ACCEPT_CALLS);

    // Sleep for less than windowInterval.
    Thread.sleep(windowInterval - 50);
    failureCircuitBreaker.recordFailure();
    assertThat(failureCircuitBreaker.state()).isEqualTo(State.REJECT_CALLS);
  }
}
