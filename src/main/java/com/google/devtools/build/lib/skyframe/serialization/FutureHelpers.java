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
import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/** Helpers for serialization futures. */
public final class FutureHelpers {

  /**
   * Waits for {@code future} and returns the result.
   *
   * <p>Handles exceptions by converting them into {@link SerializationException}.
   */
  @VisibleForTesting // package-private
  @CanIgnoreReturnValue // may be called for side effects
  public static <T> T waitForSerializationFuture(ListenableFuture<T> future)
      throws SerializationException {
    try {
      // TODO: b/297857068 - revisit whether this should handle to interrupts. As of 02/09/24,
      // serialization doesn't handle interrupts so introducing them here could lead to unforseen
      // problems.
      return getUninterruptibly(future);
    } catch (ExecutionException e) {
      throw asSerializationException(e.getCause());
    }
  }

  /**
   * Gets the done value of {@code future}.
   *
   * <p>Handles exceptions by converting them into {@link SerializationException}.
   */
  static <T> T getDoneSerializationFuture(ListenableFuture<T> future)
      throws SerializationException {
    try {
      return Futures.getDone(future);
    } catch (ExecutionException e) {
      throw asSerializationException(e.getCause());
    }
  }

  /**
   * Waits for {@code future} and returns the result.
   *
   * <p>Handles exceptions by converting them into {@link SerializationException}. The {@link
   * SerializationException} may have a {@link MissingFingerprintValueException} cause.
   *
   * <p>The {@link MissingFingerprintValueException} needs special case handling anywhere this
   * method is used. Outside of test code, this is only possible for via {@link
   * SharedValueDeserializationContext#deserializeWithSharedValues}, which explicitly handles it.
   */
  @CanIgnoreReturnValue // may be called for side effects
  static <T> T waitForDeserializationFuture(ListenableFuture<T> future)
      throws SerializationException {
    try {
      // TODO: b/297857068 - revisit whether this should handle to interrupts. As of 02/09/24,
      // serialization doesn't handle interrupts so introducing them here could lead to unforseen
      // problems.
      return getUninterruptibly(future);
    } catch (ExecutionException e) {
      throw asDeserializationException(e.getCause());
    }
  }

  /**
   * Gets the done value of {@code future}.
   *
   * <p>May throw an {@link IllegalStateException} if the future is not done. See {@link
   * #waitForDeserializationFuture} for a description the {@link SerializationException}.
   */
  static <T> T getDoneDeserializationFuture(ListenableFuture<T> future)
      throws SerializationException {
    try {
      return Futures.getDone(future);
    } catch (ExecutionException e) {
      throw asDeserializationException(e.getCause());
    }
  }

  /**
   * Reports any errors that occur on {@code combiner}.
   *
   * <p>Used when a future value is going to be discarded, but it would be inappropriate to ignore
   * possible errors.
   */
  static void reportAnyFailures(Futures.FutureCombiner<?> combiner) {
    Futures.addCallback(
        combiner.call(() -> null, directExecutor()), FAILURE_REPORTING_CALLBACK, directExecutor());
  }

  static final FutureCallback<Void> FAILURE_REPORTING_CALLBACK =
      new FutureCallback<>() {
        @Override
        public void onSuccess(Void unused) {}

        @Override
        public void onFailure(Throwable t) {
          BugReporter.defaultInstance().sendBugReport(t);
        }
      };

  private static SerializationException asDeserializationException(Throwable cause) {
    if (cause instanceof MissingFingerprintValueException) {
      return new SerializationException(cause);
    }
    return asSerializationException(cause);
  }

  private static SerializationException asSerializationException(Throwable cause) {
    if (cause instanceof SerializationException) {
      return new SerializationException(cause);
    }
    if (cause instanceof IOException) {
      return new SerializationException("serialization I/O error", cause);
    }
    return new SerializationException("unexpected serialization error", cause);
  }

  private FutureHelpers() {}
}
