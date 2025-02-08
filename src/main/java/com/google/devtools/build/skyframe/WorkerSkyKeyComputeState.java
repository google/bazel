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

package com.google.devtools.build.skyframe;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A {@link SkyKeyComputeState} that manages a non-Skyframe virtual worker thread that persists
 * across different invocations of a SkyFunction.
 *
 * <p>The worker thread uses a {@link SkyFunction.Environment} object acquired from the host thread.
 * When a new Skyframe dependency is needed, the worker thread itself does not need to restart;
 * instead, it can signal the host thread to restart to get a fresh Environment object.
 *
 * <p>Similar to other implementations of {@link SkyKeyComputeState}, this avoids redoing expensive
 * work when a new Skyframe dependency is needed; but because it holds on to an entire worker
 * thread, this class is more suited to cases where the intermediate result of expensive work cannot
 * be easily serialized (in particular, if there's an ongoing Starlark evaluation, as is the case in
 * repo fetching).
 */
public class WorkerSkyKeyComputeState<T> implements SkyKeyComputeState {

  /**
   * A semaphore with 0 or 1 permit. The worker can release a permit either when it's finished
   * (successfully or otherwise), or to indicate that the host thread should return {@code null},
   * causing a Skyframe restart. In the latter case, the worker will immediately block on {@code
   * delegateEnvQueue}, waiting for the host thread to send a fresh {@link SkyFunction.Environment}
   * over.
   */
  // A Semaphore is useful here because, crucially, releasing a permit never blocks and thus cannot
  // be interrupted.
  private final Semaphore signalSemaphore = new Semaphore(0);

  /**
   * The channel for the host Skyframe thread to send fresh {@link SkyFunction.Environment} objects
   * back to the worker thread.
   */
  // We use an ArrayBlockingQueue of size 1 instead of a SynchronousQueue, so that if the worker
  // gets interrupted before the host thread restarts, the host thread doesn't hang forever.
  private final BlockingQueue<SkyFunction.Environment> delegateEnvQueue =
      new ArrayBlockingQueue<>(1);

  /**
   * This future holds on to the worker thread in order to cancel it when necessary; it also serves
   * to tell whether a worker thread is already running.
   */
  @GuardedBy("this")
  @Nullable
  private ListenableFuture<T> workerFuture = null;

  /** The executor service that manages the worker thread. */
  // We hold on to this alongside `workerFuture` because it offers a convenient mechanism to make
  // sure the worker thread has shut down (with its blocking `close()` method).
  @GuardedBy("this")
  @Nullable
  private ListeningExecutorService workerExecutorService = null;

  /**
   * Represents work that will will be performed on the worker thread, yielding a result of type
   * {@code T}. The worker thread should exclusively use the provided {@code workerEnv} for Skyframe
   * access.
   */
  @FunctionalInterface
  public interface WorkerCallable<T> {
    T call(Environment workerEnv) throws Exception;
  }

  /**
   * Starts a worker performing the given {@link WorkerCallable}, or if such a worker already exists
   * and is waiting for a Skyframe restart, sends over a fresh Environment and asks it to continue
   * its work. This method blocks until the worker thread finishes (either successfully or
   * otherwise), <em>or</em> until the worker needs a Skyframe restart, in which case the worker
   * will suspend itself and wait for the next invocation of this method by a restarted host
   * SkyFunction with a fresh Environment.
   *
   * @param env The Skyframe Environment of the host SkyFunction.
   * @param workerThreadName The name of the worker thread to be started by this method, if one
   *     doesn't already exist.
   * @param workerCallable The work to be performed on the worker thread. Note that code in this
   *     callable should exclusively use the Environment passed to {@link
   *     WorkerCallable#call(Environment)}, <em>not</em> the original host Environment.
   * @return If the worker finishes successfully, this method returns whatever {@code
   *     workerCallable} returns. If the worker needs a Skyframe restart, returns null.
   * @throws InterruptedException if the caller (host) thread is interrupted.
   * @throws CancellationException if the worker thread is interrupted (most likely by {@link
   *     #close()})
   * @throws ExecutionException if the worker callable throws an exception.
   */
  @Nullable
  public T startOrContinueWork(
      Environment env, String workerThreadName, WorkerCallable<T> workerCallable)
      throws InterruptedException, CancellationException, ExecutionException {
    ListenableFuture<T> workerFuture = getOrStartWorker(workerThreadName, workerCallable);
    try {
      delegateEnvQueue.put(env);
      signalSemaphore.acquire();
      if (!workerFuture.isDone()) {
        // This means that the worker is still running, and expecting a fresh Environment. Return
        // null to trigger a Skyframe restart, but *don't* shut down the worker executor.
        return null;
      }
      return workerFuture.get();
    } finally {
      if (workerFuture.isDone()) {
        // Unless we know the worker is waiting on a fresh Environment, we should *always* shut
        // down the worker executor by the time we finish executing (successfully or otherwise).
        // This ensures that 1) no background work happens without our knowledge, and 2) if the
        // SkyFunction is re-entered for any reason (for example b/330892334 and
        // https://github.com/bazelbuild/bazel/issues/21238), we know we'll need to create a new
        // worker from scratch.
        close();
      }
    }
  }

  /**
   * Returns the worker future, or if a worker is not already running, starts a worker thread
   * running the given callable. This makes sure to release a permit on the {@code signalSemaphore}
   * when the worker finishes, successfully or otherwise. This may only be called from the host
   * Skyframe thread.
   */
  @SuppressWarnings("AllowVirtualThreads")
  private synchronized ListenableFuture<T> getOrStartWorker(
      String workerThreadName, WorkerCallable<T> workerCallable) {
    if (workerFuture != null) {
      return workerFuture;
    }
    // We reset the state object back to its very initial state, since the host SkyFunction may have
    // been re-entered (for example b/330892334 and
    // https://github.com/bazelbuild/bazel/issues/21238), and/or the previous worker thread may have
    // been interrupted while the host SkyFunction was inactive.
    workerExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(workerThreadName).factory()));
    signalSemaphore.drainPermits();
    delegateEnvQueue.clear();

    // Start the worker.
    workerFuture =
        workerExecutorService.submit(
            () -> {
              var workerEnv =
                  new WorkerSkyFunctionEnvironment(
                      delegateEnvQueue.take(), this::signalForFreshEnv);
              return workerCallable.call(workerEnv);
            });
    workerFuture.addListener(signalSemaphore::release, directExecutor());
    return workerFuture;
  }

  /**
   * Releases a permit on the {@code signalSemaphore} and immediately expect a fresh Environment
   * back. This may only be called from the worker thread.
   */
  private SkyFunction.Environment signalForFreshEnv() throws InterruptedException {
    signalSemaphore.release();
    return delegateEnvQueue.take();
  }

  /**
   * Closes the state object, and blocks until all pending async work is finished. The state object
   * will reset to a clean slate after this method finishes.
   */
  // This may be called from any thread, including the host Skyframe thread and the
  // high-memory-pressure listener thread.
  @Override
  public synchronized void close() {
    if (workerFuture != null) {
      workerFuture.cancel(true);
    }
    workerFuture = null;
    if (workerExecutorService != null) {
      workerExecutorService.close(); // This blocks
    }
  }
}
