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

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Regular implementation of {@link AbstractValueOrFutureMap}.
 *
 * <p>{@link getValueOrFuture} only requires a key parameter.
 */
final class ValueOrFutureMap<
        KeyT, ValueOrFutureT, ValueT extends ValueOrFutureT, FutureT extends ValueOrFutureT>
    extends AbstractValueOrFutureMap<KeyT, ValueOrFutureT, ValueT, FutureT> {

  private final Function<FutureT, ValueOrFutureT> populator;

  /**
   * Constructor.
   *
   * @param populator function completes its provided settable {@code FutureT} instance and returns
   *     a {@link ValueOrFutureT} instance. If {@code populator} returns an immediate {@code
   *     ValueT}, it will also be returned immediately by {@link #getValueOrFuture} instead of the
   *     future. However, it's fine for {@code populator} to return its {@code FutureT} input.
   * @throws IllegalArgumentException if {@code FutureT} is not a subclass of {@link
   *     SettableFutureKeyedValue}
   */
  ValueOrFutureMap(
      ConcurrentMap<KeyT, ValueOrFutureT> map,
      BiFunction<KeyT, BiConsumer<KeyT, ValueT>, FutureT> futureValueFactory,
      Function<FutureT, ValueOrFutureT> populator,
      Class<FutureT> futureType) {
    super(map, futureValueFactory, futureType);
    this.populator = populator;
  }

  ValueOrFutureT getValueOrFuture(KeyT key) {
    ValueOrFutureT result = getOrCreateValueForSubclasses(key);
    if (futureType().isInstance(result)) {
      // futureType() is an instance of SettableFutureKeyedValue, as verified in the superclass
      // constructor, so this cast is valid. Generics are not flexible enough to capture this
      // relationship.
      var future = (SettableFutureKeyedValue<?, ?, ?>) result;
      if (future.tryTakeOwnership()) {
        try {
          return populator.apply(futureType().cast(result));
        } finally {
          future.verifyComplete();
        }
      }
    }
    return result;
  }
}
