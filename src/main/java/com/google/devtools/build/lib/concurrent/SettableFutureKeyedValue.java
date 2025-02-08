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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * A specialized settable future designed for use within recursive future-valued {@link
 * ConcurrentMap}s.
 *
 * <p>This class optimizes scenarios where a map stores either futures or computed values. It aims
 * to save memory by discarding futures and directly replacing them with their computed values upon
 * successful completion. The generic type parameters facilitate this in a type-safe manner.
 *
 * <p>Consider a key type {@code KeyT} and a sealed interface {@code FutureOrValueT} that permits
 * either a future {@code FutureT} or a concrete value {@code ValueT}. The corresponding map would
 * be of type {@code ConcurrentMap<KeyT, FutureOrValueT>}. {@code FutureT} would be a subclass of
 * {@code SettableFutureKeyedValue<FutureT, KeyT, ValueT>} and implement {@code FutureOrValueT}.
 *
 * <p><b>Typical Usage:</b>
 *
 * <ol>
 *   <li>Utilize {@link ConcurrentMap#computeIfAbsent} to retrieve an existing or create a new
 *       {@code FutureT} instance.
 *   <li>Call {@link #tryTakeOwnership} to establish ownership of the future.
 *   <li>If ownership is not established, return the existing {@code FutureT} instance.
 *   <li>If ownership is acquired, the owning thread is responsible for populating the {@code
 *       FutureT} by invoking either {@link #completeWith} or {@link #failWith(Throwable)}.
 *   <li>The completion methods return a {@code ValueT} or a {@code FutureT}. The caller should
 *       directly return this value.
 * </ol>
 *
 * <p>Note that {@code <T>} is a Curiously Recurring Template Pattern (CRTP) parameter. It enables
 * {@link #completeWith(ListenableFuture)} and {@link #failWith(Throwable)} to return the exact
 * {@code FutureT} type.
 *
 * <p>This class is declared as abstract solely to accommodate the type configuration described
 * above, despite having no abstract methods or overridable behavior.
 *
 * @param <T> The concrete type of the future, following the CRTP (e.g., {@code MyFuture}).
 * @param <K> The type of the key used in the map.
 * @param <V> The type of the value that the future will eventually hold.
 */
public abstract class SettableFutureKeyedValue<T extends SettableFutureKeyedValue<T, K, V>, K, V>
    extends AbstractFuture<V> implements FutureCallback<V> {
  private final K key;
  private final BiConsumer<K, V> consumer;

  /** Used to establish exactly-once ownership of this future with {@link #tryTakeOwnership}. */
  @SuppressWarnings({"UnusedVariable", "FieldCanBeFinal"}) // set with OWNED_HANDLE
  private boolean owned = false;

  /** See comment at {@link #verifyComplete}. */
  private boolean isSet = false;

  /**
   * Creates the future.
   *
   * @param key The key associated with this future in the map.
   * @param consumer A consumer that accepts the key and the computed value upon successful
   *     completion. This is typically used to update the map with the final value, discarding the
   *     future. Abstracting this as a consumer accomodates storing values directly in the key,
   *     which is cheaper than a separate map when applicable.
   */
  protected SettableFutureKeyedValue(K key, BiConsumer<K, V> consumer) {
    this.key = key;
    this.consumer = consumer;
  }

  /** The map key associated with this value. */
  public final K key() {
    return key;
  }

  /**
   * Returns true once.
   *
   * <p>When using {@link com.github.benmanes.caffeine.cache.Cache#get} with future values and a
   * mapping function, there's a need to determine which thread owns the future. This method
   * provides such a mechanism.
   *
   * <p>When this returns true, the caller must call either {@link #completeWith} or {@link
   * #failWith}.
   */
  public final boolean tryTakeOwnership() {
    return OWNED_HANDLE.compareAndSet(this, false, true);
  }

  /** Completes this future with a successfully computed value. */
  @SuppressWarnings("CanIgnoreReturnValueSuggester") // caller should handle return value
  public final V completeWith(V value) {
    checkState(set(value), "already set %s", this);
    consumer.accept(key, value);
    isSet = true;
    return value;
  }

  /**
   * Completes this future with the result of another future.
   *
   * <p>This method is used when the computation of the value involves another asynchronous
   * operation. The provided future's result will be used to complete this future, either
   * successfully or exceptionally.
   */
  public final T completeWith(ListenableFuture<V> future) {
    checkState(setFuture(future), "already set %s", this);
    Futures.addCallback(future, this, directExecutor());
    isSet = true;
    @SuppressWarnings("unchecked")
    var result = (T) this;
    return result;
  }

  /** Completes this future with an exception. */
  public final T failWith(Throwable e) {
    // The return value could be false if there are multiple errors.
    if (setException(e)) {
      isSet = true;
    }
    @SuppressWarnings("unchecked")
    var result = (T) this;
    return result;
  }

  /**
   * Verifies that this future has been completed, either successfully or exceptionally.
   *
   * <p>This method should be called in a {@code finally} block after attempting to complete the
   * future. It helps detect situations where the future was inadvertently left incomplete, which
   * could lead to subtle bugs or deadlocks.
   *
   * <p>Note: This check is distinct from checking if the future is done. A future can be completed
   * with another future that is still in progress.
   */
  public final void verifyComplete() {
    if (!isSet) {
      checkState(
          setException(
              new IllegalStateException(
                  "future was unexpectedly unset for " + key + ", look for unchecked exceptions")),
          this);
    }
  }

  /**
   * Implementation of {@link FutureCallback<V>}.
   *
   * @deprecated only for use by {@link #completeWith(ListenableFuture<V>)}
   */
  @Override
  @Deprecated
  public final void onSuccess(V value) {
    consumer.accept(key, value); // discards the future wrapper
  }

  /**
   * Implementation of {@link FutureCallback<V>}.
   *
   * @deprecated do not use
   */
  @Override
  @Deprecated
  public final void onFailure(Throwable t) {
    // Keeps the error in the future.
  }

  private static final VarHandle OWNED_HANDLE;

  static {
    try {
      OWNED_HANDLE =
          MethodHandles.lookup()
              .findVarHandle(SettableFutureKeyedValue.class, "owned", boolean.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
