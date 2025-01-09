// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static java.lang.Math.max;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Specific retry logic for remote execution/caching. */
public class RemoteRetrier extends Retrier {

  @Nullable
  private static Status fromException(Exception e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof StatusRuntimeException statusRuntimeException) {
        return statusRuntimeException.getStatus();
      }
    }
    return null;
  }

  public static final Predicate<? super Exception> RETRIABLE_GRPC_ERRORS =
      e -> {
        Status s = fromException(e);
        if (s == null) {
          // It's not a gRPC error.
          return false;
        }
        return switch (s.getCode()) {
          case CANCELLED -> !Thread.currentThread().isInterrupted();
          case UNKNOWN, DEADLINE_EXCEEDED, ABORTED, INTERNAL, UNAVAILABLE, RESOURCE_EXHAUSTED ->
              true;
          default -> false;
        };
      };

  public static final Predicate<? super Exception> RETRIABLE_GRPC_EXEC_ERRORS =
      e -> {
        if (RETRIABLE_GRPC_ERRORS.test(e)) {
          return true;
        }
        return RemoteRetrierUtils.causedByStatus(e, Status.Code.NOT_FOUND);
      };

  public static final Predicate<? super Exception> GRPC_SUCCESS_CODES =
      e -> {
        Status s = fromException(e);
        if (s == null) {
          // It's not a gRPC error.
          return false;
        }
        switch (s.getCode()) {
          case INVALID_ARGUMENT:
          case NOT_FOUND:
          case ALREADY_EXISTS:
          case OUT_OF_RANGE:
            return true;
          default:
            return false;
        }
      };

  public RemoteRetrier(
      RemoteOptions options,
      Predicate<? super Exception> shouldRetry,
      ListeningScheduledExecutorService retryScheduler,
      CircuitBreaker circuitBreaker) {
    super(
        options.remoteMaxRetryAttempts > 0
            ? () -> new ExponentialBackoff(options)
            : () -> RETRIES_DISABLED,
        shouldRetry,
        retryScheduler,
        circuitBreaker);
  }

  public RemoteRetrier(
      RemoteOptions options,
      Predicate<? super Exception> shouldRetry,
      ListeningScheduledExecutorService retryScheduler,
      CircuitBreaker circuitBreaker,
      Predicate<? super Exception> isSuccess) {
    super(
        options.remoteMaxRetryAttempts > 0
            ? () -> new ExponentialBackoff(options)
            : () -> RETRIES_DISABLED,
        shouldRetry,
        retryScheduler,
        circuitBreaker,
        isSuccess);
  }

  public RemoteRetrier(
      Supplier<Backoff> backoff,
      Predicate<? super Exception> shouldRetry,
      ListeningScheduledExecutorService retryScheduler,
      CircuitBreaker circuitBreaker) {
    super(backoff, shouldRetry, retryScheduler, circuitBreaker);
  }

  @VisibleForTesting
  public RemoteRetrier(
      Supplier<Backoff> backoff,
      Predicate<? super Exception> shouldRetry,
      ListeningScheduledExecutorService retryScheduler,
      CircuitBreaker circuitBreaker,
      Sleeper sleeper) {
    super(backoff, shouldRetry, retryScheduler, circuitBreaker, sleeper);
  }

  /**
   * Execute a callable with retries. {@link IOException} and {@link InterruptedException} are
   * propagated directly to the caller. All other exceptions are wrapped in {@link
   * RuntimeException}.
   */
  @Override
  public <T> T execute(Callable<T> call) throws IOException, InterruptedException {
    return execute(call, newBackoff());
  }

  /**
   * Execute a callable with retries and given {@link Backoff}. {@link IOException} and {@link
   * InterruptedException} are propagated directly to the caller. All other exceptions are wrapped
   * in {@link RuntimeException}.
   */
  @Override
  public <T> T execute(Callable<T> call, Backoff backoff) throws IOException, InterruptedException {
    try {
      return super.execute(call, backoff);
    } catch (Exception e) {
      Throwables.throwIfInstanceOf(e, IOException.class);
      Throwables.throwIfInstanceOf(e, InterruptedException.class);
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }

  /** Backoff strategy that backs off exponentially. */
  public static class ExponentialBackoff implements Backoff {

    private final long maxMillis;
    private long nextDelayMillis;
    private int attempts = 0;
    private final double multiplier;
    private final double jitter;
    private final int maxAttempts;

    /**
     * Creates a Backoff supplier for an optionally jittered exponential backoff. The supplier is
     * ThreadSafe (non-synchronized calls to get() are fine), but the returned Backoff is not.
     *
     * @param initial The initial backoff duration.
     * @param max The maximum backoff duration.
     * @param multiplier The amount the backoff should increase in each iteration. Must be >1.
     * @param jitter The amount the backoff should be randomly varied (0-1), with 0 providing no
     *     jitter, and 1 providing a duration that is 0-200% of the non-jittered duration.
     * @param maxAttempts Maximal times to attempt a retry 0 means no retries.
     */
    ExponentialBackoff(Duration initial, Duration max, double multiplier, double jitter,
        int maxAttempts) {
      Preconditions.checkArgument(multiplier > 1, "multipler must be > 1");
      Preconditions.checkArgument(jitter >= 0 && jitter <= 1, "jitter must be in the range (0, 1)");
      Preconditions.checkArgument(maxAttempts >= 0, "maxAttempts must be >= 0");
      nextDelayMillis = initial.toMillis();
      maxMillis = max(max.toMillis(), nextDelayMillis);
      this.multiplier = multiplier;
      this.jitter = jitter;
      this.maxAttempts = maxAttempts;
    }

    public ExponentialBackoff(RemoteOptions options) {
      this(
          /* initial= */ Duration.ofMillis(100),
          /* max= */ options.remoteRetryMaxDelay,
          /* multiplier= */ 2,
          /* jitter= */ 0.1,
          options.remoteMaxRetryAttempts);
    }

    @Override
    public long nextDelayMillis(Exception e) {
      if (attempts == maxAttempts) {
        return -1;
      }
      attempts++;
      double jitterRatio = jitter * (ThreadLocalRandom.current().nextDouble(2.0) - 1);
      long result = (long) (nextDelayMillis * (1 + jitterRatio));
      // Advance current by the non-jittered result.
      nextDelayMillis = (long) (nextDelayMillis * multiplier);
      if (nextDelayMillis > maxMillis) {
        nextDelayMillis = maxMillis;
      }
      return result;
    }

    @Override
    public int getRetryAttempts() {
      return attempts;
    }
  }

  static class ProgressiveBackoff implements Backoff {

    private final Supplier<Backoff> backoffSupplier;
    private Backoff currentBackoff = null;
    private int retries = 0;

    /**
     * Creates a resettable Backoff for progressive reads. After a reset, the nextDelay returned
     * indicates an immediate retry. Initially and after indicating an immediate retry, a delegate
     * is generated to provide nextDelay until reset.
     *
     * @param backoffSupplier Delegate Backoff generator
     */
    ProgressiveBackoff(Supplier<Backoff> backoffSupplier) {
      this.backoffSupplier = backoffSupplier;
      currentBackoff = backoffSupplier.get();
    }

    public void reset() {
      if (currentBackoff != null) {
        retries += currentBackoff.getRetryAttempts();
      }
      currentBackoff = null;
    }

    @Override
    public long nextDelayMillis(Exception e) {
      if (currentBackoff == null) {
        currentBackoff = backoffSupplier.get();
        retries++;
        return 0;
      }
      return currentBackoff.nextDelayMillis(e);
    }

    @Override
    public int getRetryAttempts() {
      int retryAttempts = retries;
      if (currentBackoff != null) {
        retryAttempts += currentBackoff.getRetryAttempts();
      }
      return retryAttempts;
    }
  }
}
