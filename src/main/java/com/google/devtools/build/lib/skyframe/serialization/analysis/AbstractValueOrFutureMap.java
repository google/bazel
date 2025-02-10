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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A concurrent map of recursively populated futures or values.
 *
 * <p>This map achieves two goals in conjunction with the {@link SettableFutureKeyedValue}.
 *
 * <ul>
 *   <li>It ensures that for each key, the future is created and populated only once (assuming that
 *       the entry remains cached).
 *   <li>When the future succeeds, the future is unwrapped and only the value is retained.
 * </ul>
 *
 * <p>In the case of an error, the future is retained, according to the {@link #map} implementation.
 *
 * <p>{@code FutureT} must be a subclass of {@link SettableFutureKeyedValue}. Unfortunately, this
 * additional constraint cannot be expressed using Java generics, but it is checked at runtime
 * during construction.
 */
abstract class AbstractValueOrFutureMap<
        KeyT, ValueOrFutureT, ValueT extends ValueOrFutureT, FutureT extends ValueOrFutureT>
    implements BiConsumer<KeyT, ValueT> {

  private final ConcurrentMap<KeyT, ValueOrFutureT> map;
  private final FutureValueFactory futureValueFactory;
  private final Class<FutureT> futureType;

  /**
   * Constructor.
   *
   * @param futureValueFactory creates appropriate instances of {@link SettableFutureKeyedValue}.
   *     The key and consumer parameters are provided for use in {@link SettableFutureKeyedValue}'s
   *     constructor.
   * @throws IllegalArgumentException if {@code FutureT} is not a subclass of {@link
   *     SettableFutureKeyedValue}
   */
  AbstractValueOrFutureMap(
      ConcurrentMap<KeyT, ValueOrFutureT> map,
      BiFunction<KeyT, BiConsumer<KeyT, ValueT>, FutureT> futureValueFactory,
      Class<FutureT> futureType) {
    this.map = map;
    this.futureValueFactory = new FutureValueFactory(futureValueFactory);
    checkArgument(
        SettableFutureKeyedValue.class.isAssignableFrom(futureType),
        "%s was not an instanceof SettableFutureKeyedValue",
        futureType);
    this.futureType = futureType;
  }

  Class<FutureT> futureType() {
    return futureType;
  }

  ValueOrFutureT getOrCreateValueForSubclasses(KeyT key) {
    return map.computeIfAbsent(key, futureValueFactory);
  }

  /**
   * @deprecated only used by {@link FutureValueFactory#apply}
   */
  @Override
  @Deprecated
  public void accept(KeyT key, ValueT value) {
    map.put(key, value);
  }

  private final class FutureValueFactory implements Function<KeyT, FutureT> {
    private final BiFunction<KeyT, BiConsumer<KeyT, ValueT>, FutureT> futureValueFactory;

    private FutureValueFactory(
        BiFunction<KeyT, BiConsumer<KeyT, ValueT>, FutureT> futureValueFactory) {
      this.futureValueFactory = futureValueFactory;
    }

    @Override
    public FutureT apply(KeyT key) {
      return futureValueFactory.apply(key, AbstractValueOrFutureMap.this);
    }
  }
}
