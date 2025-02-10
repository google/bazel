// Copyright 2025 The Bazel Authors. All rights reserved.
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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.aggregateWriteStatuses;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateFailedWriteStatus;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.sparselyAggregateWriteStatuses;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SparseAggregateWriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class WriteStatusesTest {

  @Test
  public void immediateWriteStatus_isDone() throws Exception {
    WriteStatus status = immediateWriteStatus();

    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isFalse();
    assertThat(status.cancel(true)).isFalse();
    assertThat(status.cancel(false)).isFalse();

    assertThat(status.get()).isNull();
    assertThat(status.get(0, SECONDS)).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void failedWriteStatus_isDone() throws Exception {
    var exception = new Exception();
    WriteStatus status = immediateFailedWriteStatus(exception);

    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isFalse();
    assertThat(status.cancel(true)).isFalse();
    assertThat(status.cancel(false)).isFalse();

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void settableWriteStatus_markSuccess_isDone() throws Exception {
    var status = new SettableWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    status.markSuccess();
    assertThat(setOnRun.isSet).isTrue();

    assertThat(status.isDone()).isTrue();
    assertThat(status.get()).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void settableWriteStatus_failWith() throws Exception {
    var status = new SettableWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var exception = new Exception();
    status.failWith(exception);
    assertThat(setOnRun.isSet).isTrue();
    assertThat(status.isDone()).isTrue();

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void settableWriteStatus_completeWith_successfulFuture() throws Exception {
    var status = new SettableWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    status.completeWith(immediateWriteStatus());
    assertThat(setOnRun.isSet).isTrue();

    assertThat(status.isDone()).isTrue();
    assertThat(status.get()).isNull();
    assertThat(status.get(0, SECONDS)).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void settableWriteStatus_completeWith_failingFuture() throws Exception {
    var status = new SettableWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var exception = new Exception();
    status.completeWith(immediateFailedWriteStatus(exception));

    assertThat(status.isDone()).isTrue();
    assertThat(setOnRun.isSet).isTrue();

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void aggregateWriteStatus_waitsForInputs() throws Exception {
    var input = new SettableWriteStatus();

    var status = aggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    input.markSuccess();
    assertThat(setOnRun.isSet).isTrue();
    assertThat(status.isDone()).isTrue();

    assertThat(status.get()).isNull();
    assertThat(status.get(0, SECONDS)).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void aggregateWriteStatus_failsOnFailedInput() throws Exception {
    var input = new SettableWriteStatus();

    var status = aggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var exception = new Exception();
    input.failWith(exception);
    assertThat(setOnRun.isSet).isTrue();

    assertThat(status.isDone()).isTrue();
    assertListenerExecutesImmediately(status);

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);
  }

  @Test
  public void aggregateWriteStatus_multipleFailedInputs() throws Exception {
    var input = new SettableWriteStatus();

    var exception1 = new Exception();
    var status =
        aggregateWriteStatuses(ImmutableList.of(input, immediateFailedWriteStatus(exception1)));

    assertThat(status.isDone()).isTrue();
    assertListenerExecutesImmediately(status);

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1);

    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1);

    Exception exception2 = new Exception();
    input.failWith(exception2);

    thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1);
    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception1);
  }

  @Test
  public void aggregateWriteStatus_alreadyCancelledInput_propagates() throws Exception {
    var input = new SettableWriteStatus();
    input.cancel(/* mayInterruptIfRunning= */ false);
    assertThat(input.isCancelled()).isTrue();
    var unused = assertThrows(CancellationException.class, input::get);

    var status = aggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));

    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isTrue();
    unused = assertThrows(CancellationException.class, status::get);
  }

  @Test
  public void aggregateWriteStatus_cancellingInput_propagates() throws Exception {
    var input = new SettableWriteStatus();

    var status = aggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));

    assertThat(status.isDone()).isFalse();

    input.cancel(/* mayInterruptIfRunning= */ false);

    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isTrue();
    var unused = assertThrows(CancellationException.class, status::get);
  }

  // This test case and the following one exercise the use of SparseAggregateWriteStatus as a
  // SettableFuture.
  @Test
  public void sparseAggregate_notifyWriteSucceeded_completes() throws Exception {
    var status = new SparseAggregateWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    status.notifyWriteSucceeded();
    assertThat(setOnRun.isSet).isTrue();

    assertThat(status.isDone()).isTrue();
    assertThat(status.get()).isNull();
    assertThat(status.get(0, SECONDS)).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void sparseAggregate_notifyWriteFailed_completes() throws Exception {
    var status = new SparseAggregateWriteStatus();

    assertThat(status.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var exception = new Exception();
    status.notifyWriteFailed(exception);

    assertThat(setOnRun.isSet).isTrue();
    assertThat(status.isDone()).isTrue();

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);
    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void sparseAggregate_empty_isImmediate() throws Exception {
    assertThat(sparselyAggregateWriteStatuses(ImmutableList.of()))
        .isSameInstanceAs(immediateWriteStatus());
  }

  @Test
  public void sparseAggregate_singleton_forwards() throws Exception {
    var inner = new SettableWriteStatus();
    assertThat(sparselyAggregateWriteStatuses(ImmutableList.of(inner))).isSameInstanceAs(inner);
  }

  @Test
  public void sparseAggregate_doneInputs_isDone() throws Exception {
    var status =
        sparselyAggregateWriteStatuses(
            ImmutableList.of(immediateWriteStatus(), immediateWriteStatus()));

    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isFalse();
    assertThat(status.cancel(true)).isFalse();
    assertThat(status.cancel(false)).isFalse();

    assertThat(status.get()).isNull();
    assertThat(status.get(0, SECONDS)).isNull();

    assertListenerExecutesImmediately(status);
  }

  @Test
  public void sparseAggregate_sparseleyPropagatesSuccess() throws Exception {
    var status = new SparseAggregateWriteStatus();

    // Constructing the aggregate requires at least 2 inputs to avoid short-circuit behavior.
    var aggregate1 =
        sparselyAggregateWriteStatuses(ImmutableList.of(status, immediateWriteStatus()));
    assertThat(aggregate1.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var aggregate2 =
        sparselyAggregateWriteStatuses(ImmutableList.of(status, immediateWriteStatus()));
    // The edge from `status` to `aggregate2` is dropped for sparsity. The only child of aggregate2
    // is immediateWriteStatus, which is already done. It completes inside
    // SparseAggregateWriteStatus.create once the pre-increment is cancelled.
    assertThat(aggregate2.isDone()).isTrue();

    status.notifyWriteSucceeded();
    assertThat(setOnRun.isSet).isTrue();
    assertThat(aggregate1.isDone()).isTrue();
  }

  @Test
  public void sparseAggregate_sparseleyPropagatesException() throws Exception {
    var status = new SparseAggregateWriteStatus();

    // Constructing the aggregate requires at least 2 inputs to avoid short-circuit behavior.
    var aggregate1 =
        sparselyAggregateWriteStatuses(ImmutableList.of(status, immediateWriteStatus()));
    assertThat(aggregate1.isDone()).isFalse();
    var setOnRun = new SetOnRun();
    status.addListener(setOnRun, directExecutor());
    assertThat(setOnRun.isSet).isFalse();

    var aggregate2 =
        sparselyAggregateWriteStatuses(ImmutableList.of(status, immediateWriteStatus()));
    // The edge from `status` to `aggregate2` is dropped for sparsity.
    assertThat(aggregate2.isDone()).isTrue();

    var exception = new Exception();
    status.notifyWriteFailed(exception);
    assertThat(setOnRun.isSet).isTrue();
    assertThat(aggregate1.isDone()).isTrue();

    var thrown = assertThrows(ExecutionException.class, status::get);
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);
    thrown = assertThrows(ExecutionException.class, () -> status.get(0, SECONDS));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(exception);
  }

  @Test
  public void sparseAggregate_cancelledInput_propagates() throws Exception {
    var cancelledInput = new SettableWriteStatus();
    cancelledInput.cancel(/* mayInterruptIfRunning= */ false);

    var status =
        sparselyAggregateWriteStatuses(ImmutableList.of(cancelledInput, immediateWriteStatus()));
    assertThat(status.isDone()).isTrue();
    assertThat(status.isCancelled()).isTrue();
    var unused = assertThrows(CancellationException.class, status::get);

    var settable = new SettableWriteStatus();
    settable.completeWith(status);
    assertThat(settable.isCancelled()).isTrue();
    unused = assertThrows(CancellationException.class, settable::get);
  }

  @Test
  public void sparseAggregate_cancellingInput_propagatesSparsely() throws Exception {
    var input = new SparseAggregateWriteStatus();

    var consumer1 = sparselyAggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));
    assertThat(consumer1.isDone()).isFalse();

    var consumer2 = sparselyAggregateWriteStatuses(ImmutableList.of(input, immediateWriteStatus()));
    assertThat(consumer2.isDone()).isTrue(); // input ignored due to sparse aggregation
    assertThat(consumer2.isCancelled()).isFalse();

    input.cancel(/* mayInterruptIfRunning= */ false);
    assertThat(input.isCancelled()).isTrue();
    var unused = assertThrows(CancellationException.class, input::get);

    assertThat(consumer1.isCancelled()).isTrue();
    unused = assertThrows(CancellationException.class, consumer1::get);
  }

  private static void assertListenerExecutesImmediately(WriteStatus status) {
    var captured = new AtomicReference<Runnable>();
    Executor capturingExecutor =
        command -> assertThat(captured.compareAndSet(null, command)).isTrue();

    Runnable runnable = () -> {};
    status.addListener(runnable, capturingExecutor);
    assertThat(captured.get()).isSameInstanceAs(runnable);
  }

  private static class SetOnRun implements Runnable {
    private boolean isSet = false;

    @Override
    public void run() {
      isSet = true;
    }
  }
}
