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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.errorprone.annotations.DoNotCall;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Container for {@link WriteStatus} and its implementations.
 *
 * <p>The alternative of having {@link WriteStatus} as the top level type and its implementations as
 * inner classes, requires all the implementations to be public.
 */
public class WriteStatuses {
  /**
   * Represents future success or failure of a write operation.
   *
   * <p>This can act like an ordinary future, but has special case, memory saving handling for
   * aggregation.
   */
  // The ImmediateWriteStatus class should be singleton, so it's cleaner to not derive it from
  // AbstractFuture.
  @SuppressWarnings("ShouldNotSubclass")
  public sealed interface WriteStatus extends ListenableFuture<Void>
      permits ImmediateWriteStatus,
          ImmediateFailedWriteStatus,
          SettableWriteStatus,
          SparseAggregateWriteStatus,
          AggregateWriteStatus {}

  /** Returns the stateless, immediately successful write status. */
  public static WriteStatus immediateWriteStatus() {
    return ImmediateWriteStatus.INSTANCE;
  }

  /** Creates an immediately failed write status. */
  public static WriteStatus immediateFailedWriteStatus(Throwable cause) {
    return new ImmediateFailedWriteStatus(cause);
  }

  /**
   * Combines {@code writeStatuses} into a single future using <i>sparse</i> aggregation.
   *
   * <p>NB: This is not a general purpose aggregation and must only be used under certain
   * conditions. See {@link SparseaggregateWriteStatus} for details.
   */
  public static WriteStatus sparselyAggregateWriteStatuses(Collection<WriteStatus> writeStatuses) {
    if (writeStatuses.isEmpty()) {
      return immediateWriteStatus();
    }
    if (writeStatuses.size() == 1) {
      return writeStatuses.iterator().next();
    }
    return SparseAggregateWriteStatus.create(writeStatuses);
  }

  /** Combines {@code futures} into a single future (general purpose). */
  public static WriteStatus aggregateWriteStatuses(Collection<WriteStatus> writeStatuses) {
    if (writeStatuses.isEmpty()) {
      return immediateWriteStatus();
    }
    if (writeStatuses.size() == 1) {
      return writeStatuses.iterator().next();
    }
    return AggregateWriteStatus.create(writeStatuses);
  }

  /**
   * A reference-count based aggregator for {@link WriteStatus}es.
   *
   * <p><b>Sparsity:</b> when {@link addToAggregator} is called, only the first invocation creates a
   * callback and the rest are ignored. This is appropriate when all {@link WriteStatus}es are
   * ultimately aggregated into a single top-level future, e.g., the {@link
   * com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer}.
   *
   * <p>When a {@link com.google.common.util.concurrent.SettableFuture} with sparse edges is
   * desired, this class may be used by calling the methods {@link #notifyWriteSucceeded} and {@link
   * #notifyWriteFailed} appropriately. Since this class derives from {@link QuiescingFuture},
   * there's a pre-increment, so calling one of those two methods once is sufficient for setting the
   * value.
   */
  public static final class SparseAggregateWriteStatus extends QuiescingFuture<Void>
      implements WriteStatus, Runnable, FutureCallback<Void> {
    private volatile SparseAggregateWriteStatus listeningAggregate = null;

    /** Creates an aggregate that depends on all the statuses in {@code writeStatuses}. */
    private static SparseAggregateWriteStatus create(Iterable<WriteStatus> writeStatuses) {
      var aggregate = new SparseAggregateWriteStatus();
      for (WriteStatus writeStatus : writeStatuses) {
        if (writeStatus.isDone()) {
          try {
            var unusedNull = Futures.getDone(writeStatus);
            // Success would lead to an increment then decrement, which reduces to a no-op.
          } catch (ExecutionException e) {
            // InternalFutureFailureAccess might be more efficient, but failures should be rare.
            //
            // Increments the reference count for consistency. As per the contract of
            // QuiescingFuture, on overall failure, the reference count should not reach zero.
            aggregate.prepareForAddingWrite();
            aggregate.notifyException(e);
          } catch (CancellationException e) {
            aggregate.prepareForAddingWrite();
            aggregate.cancel(/* mayInterruptIfRunning= */ false); // nothing running
          }
          continue;
        }

        switch (writeStatus) {
          case SparseAggregateWriteStatus sparse:
            // The addToAggregator logic ensures that each SparseAggregateWriteStatus has at most
            // one SparseAggregateWriteStatus parent.
            sparse.addToAggregator(aggregate);
            break;
          default:
            aggregate.prepareForAddingWrite();
            Futures.addCallback(writeStatus, (FutureCallback<Void>) aggregate, directExecutor());
            break;
        }
      }
      aggregate.decrement(); // Cancels the pre-increment.
      return aggregate;
    }

    /**
     * Signals the successful completion of an aggregate component.
     *
     * <p>Only clients using the aggregate as a settable future call this.
     */
    public void notifyWriteSucceeded() {
      decrement();
    }

    /**
     * Signals the failure of an aggregate component.
     *
     * <p>Only clients using the aggregate as a settable future call this.
     */
    public void notifyWriteFailed(Throwable t) {
      if (t instanceof CancellationException) {
        cancel(/* mayInterruptIfRunning= */ false); // nothing running
        return;
      }
      notifyException(t);
    }

    @Override
    protected Void getValue() {
      return null;
    }

    /**
     * Prepares for the addition of a new write operation by incrementing the internal reference
     * count.
     *
     * <p>By incrementing *before* the write is actually added, we ensure that the reference count
     * accurately reflects the number of pending writes, even if some writes complete immediately.
     */
    private void prepareForAddingWrite() {
      increment();
    }

    private void addToAggregator(SparseAggregateWriteStatus aggregate) {
      // The CAS here accepts the first listener, and ignores any additional ones.
      if (LISTENING_AGGREGATOR_HANDLE.compareAndSet(this, null, aggregate)) {
        aggregate.prepareForAddingWrite();
        addListener((Runnable) this, directExecutor());
      }
    }

    /**
     * Implements the aggregate listener.
     *
     * @deprecated only for use by {@link #addToAggregator} listener processing
     */
    @Deprecated
    @Override
    @DoNotCall
    public void run() {
      try {
        var unusedNull = Futures.getDone(this);
        listeningAggregate.notifyWriteSucceeded();
      } catch (ExecutionException e) {
        listeningAggregate.notifyWriteFailed(e);
      } catch (CancellationException e) {
        listeningAggregate.cancel(/* mayInterruptIfRunning= */ false); // nothing running
      }
    }

    /**
     * Implementation of {@link FutureCallback<Void>}.
     *
     * @deprecated only for use by {@link #create} callback processing.
     */
    @Deprecated
    @Override
    @DoNotCall
    @SuppressWarnings("InlineMeSuggester")
    public void onSuccess(Void unused) {
      notifyWriteSucceeded();
    }

    /**
     * Implementation of {@link FutureCallback<Void>}.
     *
     * @deprecated only for use by {@link #create} callback processing.
     */
    @Deprecated
    @Override
    @DoNotCall
    @SuppressWarnings("InlineMeSuggester")
    public void onFailure(Throwable t) {
      if (t instanceof CancellationException) {
        cancel(/* mayInterruptIfRunning= */ false);
        return;
      }
      notifyWriteFailed(t);
    }

    private static final VarHandle LISTENING_AGGREGATOR_HANDLE;

    static {
      try {
        LISTENING_AGGREGATOR_HANDLE =
            MethodHandles.lookup()
                .findVarHandle(
                    SparseAggregateWriteStatus.class,
                    "listeningAggregate",
                    SparseAggregateWriteStatus.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }

  /**
   * A general purpose, reference-count-based {@link WriteStatus} aggregator.
   *
   * <p>Uses less memory in-flight than {@link Futures#whenAllSucceed} because it does not retain
   * the list of input futures and therefore also releases those futures earlier.
   *
   * <p>In contrast to {@link SparseAggregateWriteStatus} preserves all callback edges.
   */
  private static final class AggregateWriteStatus extends QuiescingFuture<Void>
      implements WriteStatus, FutureCallback<Void> {
    private static AggregateWriteStatus create(Iterable<WriteStatus> writeStatuses) {
      var aggregator = new AggregateWriteStatus();
      for (WriteStatus writeStatus : writeStatuses) {
        aggregator.increment();
        Futures.addCallback(writeStatus, (FutureCallback<Void>) aggregator, directExecutor());
      }
      aggregator.decrement(); // Clears the pre-increment.
      return aggregator;
    }

    @Override
    protected Void getValue() {
      return null;
    }

    /**
     * Implementation of {@link FutureCallback<Void>}.
     *
     * @deprecated only used by {@link #create} for callback processing
     */
    @Deprecated
    @Override
    public void onSuccess(Void unused) {
      decrement();
    }

    /**
     * Implementation of {@link FutureCallback<Void>}.
     *
     * @deprecated only used by {@link #create} for callback processing
     */
    @Deprecated
    @Override
    public void onFailure(Throwable t) {
      if (t instanceof CancellationException) {
        cancel(/* mayInterruptIfRunning= */ false); // nothing running
        return;
      }
      notifyException(t);
    }

    private AggregateWriteStatus() {}
  }

  /**
   * A settable {@link WriteStatus}, analogous to {@link
   * com.google.common.util.concurrent.SettableFuture}.
   */
  public static final class SettableWriteStatus extends AbstractFuture<Void>
      implements WriteStatus {
    public void markSuccess() {
      checkState(set(null), "attempted to markSuccess already set %s", this);
    }

    public void failWith(Throwable cause) {
      checkState(setException(cause), "attempted to failWith(%s) already set %s", cause, this);
    }

    public void completeWith(WriteStatus future) {
      checkState(setFuture(future), "attempted to completeWith(%s) already set %s", future, this);
    }
  }

  private static final class ImmediateWriteStatus implements WriteStatus {
    private static final ImmediateWriteStatus INSTANCE = new ImmediateWriteStatus();

    @Override
    public void addListener(Runnable listener, Executor executor) {
      executor.execute(listener); // Immediately executes listener.
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public Void get() {
      return null;
    }

    @Override
    public Void get(long timeout, TimeUnit unit) {
      return null;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }
  }

  private static final class ImmediateFailedWriteStatus implements WriteStatus {
    private final ExecutionException exception;

    private ImmediateFailedWriteStatus(Throwable cause) {
      this.exception = new ExecutionException(cause);
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
      executor.execute(listener); // Immediately executes listener.
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public Void get() throws ExecutionException {
      throw exception;
    }

    @Override
    public Void get(long timeout, TimeUnit unit) throws ExecutionException {
      return get();
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }
  }

  private WriteStatuses() {} // namespace only
}
