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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SettableFutureKeyedValueTest {
  private record Value(String text) {}

  private static final class FutureValue
      extends SettableFutureKeyedValue<FutureValue, String, Value> {
    private FutureValue(String key, BiConsumer<String, Value> consumer) {
      super(key, consumer);
    }
  }

  @Test
  public void takingOwnership_occursExactlyOnce() throws Exception {
    var future = new FutureValue("key", (unusedA, unusedB) -> {});
    var tryOwnSuccessCount = new AtomicInteger(0);
    final int taskCount = 100;
    var allDone = new CountDownLatch(taskCount);
    for (int i = 0; i < taskCount; i++) {
      commonPool()
          .execute(
              () -> {
                if (future.tryTakeOwnership()) {
                  tryOwnSuccessCount.getAndIncrement();
                }
                allDone.countDown();
              });
    }
    allDone.await();
    assertThat(tryOwnSuccessCount.get()).isEqualTo(1);
  }

  @Test
  public void futureFails_ifUnset() {
    var future = new FutureValue("key", (unusedA, unusedB) -> {});
    future.verifyComplete();

    var thrown = assertThrows(ExecutionException.class, future::get);

    assertThat(thrown)
        .hasMessageThat()
        .contains("future was unexpectedly unset for key, look for unchecked exceptions");
  }

  @Test
  public void completeWithValue_propagates() throws Exception {
    var setValue = new AtomicReference<Value>();
    var future =
        new FutureValue(
            "key",
            (key, value) -> {
              assertThat(key).isEqualTo("key");
              assertThat(setValue.compareAndSet(null, value)).isTrue();
            });
    var value = new Value("value");

    assertThat(future.completeWith(value)).isEqualTo(value);
    assertThat(setValue.get()).isEqualTo(value);

    future.verifyComplete();
    assertThat(future.get()).isEqualTo(value);
  }

  @Test
  public void completeWithFuture_propagates() throws Exception {
    var setValue = new AtomicReference<Value>();
    var future =
        new FutureValue(
            "key",
            (key, value) -> {
              assertThat(key).isEqualTo("key");
              assertThat(setValue.compareAndSet(null, value)).isTrue();
            });
    var value = new Value("value");

    assertThat(future.completeWith(immediateFuture(value)).get()).isEqualTo(value);
    assertThat(setValue.get()).isEqualTo(value);

    future.verifyComplete();
    assertThat(future.get()).isEqualTo(value);
  }

  @Test
  public void failWith_propagates() {
    var setValue = new AtomicReference<Value>();
    var future =
        new FutureValue(
            "key",
            (key, value) -> {
              assertThat(key).isEqualTo("key");
              assertThat(setValue.compareAndSet(null, value)).isTrue();
            });

    FutureValue result = future.failWith(new IllegalStateException("injected failure"));
    var thrown = assertThrows(ExecutionException.class, result::get);

    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("injected failure");

    assertThat(setValue.get()).isNull();

    future.verifyComplete();
    var thrown2 = assertThrows(ExecutionException.class, future::get);
    assertThat(thrown2).hasCauseThat().isSameInstanceAs(thrown.getCause());
  }
}
