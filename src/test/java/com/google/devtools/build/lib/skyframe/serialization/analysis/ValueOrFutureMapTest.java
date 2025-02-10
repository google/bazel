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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ValueOrFutureMapTest {
  private sealed interface ValueOrFuture permits Value, FutureValue {}

  private record Value(String text) implements ValueOrFuture {}

  private static final class FutureValue
      extends SettableFutureKeyedValue<FutureValue, String, Value> implements ValueOrFuture {
    private FutureValue(String key, BiConsumer<String, Value> consumer) {
      super(key, consumer);
    }
  }

  @Test
  public void incomplete_fails() throws Exception {
    var map =
        new ValueOrFutureMap<>(
            new ConcurrentHashMap<String, ValueOrFuture>(),
            FutureValue::new,
            future -> future, // a faulty, no-op populator
            FutureValue.class);
    var result = (FutureValue) map.getValueOrFuture("key");

    var thrown = assertThrows(ExecutionException.class, result::get);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("future was unexpectedly unset");
  }

  @Test
  public void immediatePopulatorResult_returnsImmediateValue() throws Exception {
    var value = new Value("value");
    var map =
        new ValueOrFutureMap<>(
            new ConcurrentHashMap<String, ValueOrFuture>(),
            FutureValue::new,
            // populator that just returns value
            future -> {
              assertThat(future.key()).isEqualTo("key");
              return future.completeWith(value);
            },
            FutureValue.class);

    var result = map.getValueOrFuture("key");
    assertThat(result).isSameInstanceAs(value);
  }

  @Test
  public void asyncCompletion_propagates() throws Exception {
    var settable = SettableFuture.<Value>create();
    var map =
        new ValueOrFutureMap<>(
            new ConcurrentHashMap<String, ValueOrFuture>(),
            FutureValue::new,
            future -> {
              assertThat(future.key()).isEqualTo("key");
              return future.completeWith(settable);
            },
            FutureValue.class);

    var result = (FutureValue) map.getValueOrFuture("key");
    assertThat(result.isDone()).isFalse();

    var value = new Value("value");
    settable.set(value);

    assertThat(result.get()).isSameInstanceAs(value);

    // After completion, the map contains the value directly, not wrapped by the future.
    assertThat(map.getValueOrFuture("key")).isSameInstanceAs(value);
  }

  @Test
  public void sameKey_returnsCachedValue() throws Exception {
    var called = new AtomicBoolean(false);
    var value = new Value("value");
    var map =
        new ValueOrFutureMap<>(
            new ConcurrentHashMap<String, ValueOrFuture>(),
            FutureValue::new,
            future -> {
              assertThat(future.key()).isEqualTo("key");
              // Asserts that the populator is called just once.
              assertThat(called.compareAndSet(false, true)).isTrue();
              return future.completeWith(value);
            },
            FutureValue.class);

    var result = map.getValueOrFuture("key");
    assertThat(result).isSameInstanceAs(value);

    assertThat(called.get()).isTrue();

    var result2 = map.getValueOrFuture("key");
    assertThat(result2).isSameInstanceAs(value);
  }
}
