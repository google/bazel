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

package com.google.devtools.build.lib.worker;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Implementation of the WorkerPool.
 *
 * <p>TODO(b/323880131): Remove documentation once we completely remove the legacy implementation.
 *
 * <p>This implementation flattens this to have a single {@code WorkerKeyPool} for each worker key
 * (we don't need the indirection in referencing both mnemonic and worker key since the mnemonic is
 * part of the key). Additionally, it bakes in pool shrinking logic so that we can handle concurrent
 * calls.
 */
public class WorkerPoolImpl implements WorkerPool {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Unless otherwise specified, the max number of workers per WorkerKey. */
  private static final int DEFAULT_MAX_SINGLEPLEX_WORKERS = 4;

  /** Unless otherwise specified, the max number of multiplex workers per WorkerKey. */
  private static final int DEFAULT_MAX_MULTIPLEX_WORKERS = 8;

  private final WorkerFactory factory;

  private final ImmutableMap<String, Integer> singleplexMaxInstances;
  private final ImmutableMap<String, Integer> multiplexMaxInstances;
  private final ConcurrentHashMap<WorkerKey, WorkerKeyPool> pools = new ConcurrentHashMap<>();

  public WorkerPoolImpl(WorkerFactory factory, WorkerPoolConfig config) {
    this.factory = factory;
    this.singleplexMaxInstances =
        getMaxInstances(config.getWorkerMaxInstances(), DEFAULT_MAX_SINGLEPLEX_WORKERS);
    this.multiplexMaxInstances =
        getMaxInstances(config.getWorkerMaxMultiplexInstances(), DEFAULT_MAX_MULTIPLEX_WORKERS);
  }

  private static ImmutableMap<String, Integer> getMaxInstances(
      List<Entry<String, Integer>> maxInstances, int defaultMaxWorkers) {
    LinkedHashMap<String, Integer> newConfigBuilder = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : maxInstances) {
      if (entry.getValue() != null) {
        newConfigBuilder.put(entry.getKey(), entry.getValue());
      } else if (entry.getKey() != null) {
        newConfigBuilder.put(entry.getKey(), defaultMaxWorkers);
      }
    }
    return ImmutableMap.copyOf(newConfigBuilder);
  }

  @Override
  public int getMaxTotalPerKey(WorkerKey key) {
    return getPool(key).getEffectiveMax();
  }

  @Override
  public int getNumActive(WorkerKey key) {
    return getPool(key).getNumActive();
  }

  @Override
  public boolean hasAvailableQuota(WorkerKey key) {
    return getPool(key).hasAvailableQuota();
  }

  @Override
  public ImmutableSet<Integer> evictWorkers(ImmutableSet<Integer> workerIdsToEvict)
      throws InterruptedException {
    // TODO: Without having the Worker objects themselves, we can't directly pass the worker to the
    // pool to be evicted.
    ImmutableSet<Integer> evictedWorkerIds =
        pools.values().stream()
            .flatMap(p -> p.evictWorkers(workerIdsToEvict).stream())
            .collect(toImmutableSet());
    return workerIdsToEvict.stream().filter(evictedWorkerIds::contains).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<Integer> getIdleWorkers() throws InterruptedException {
    return pools.values().stream()
        .flatMap(p -> p.getIdleWorkers().stream())
        .collect(toImmutableSet());
  }

  @Override
  public Worker borrowWorker(WorkerKey key) throws IOException, InterruptedException {
    return getPool(key).borrowWorker(key);
  }

  @Override
  public void returnWorker(WorkerKey key, Worker obj) {
    getPool(key).returnWorker(key, /* worker= */ obj);
  }

  @Override
  public void invalidateWorker(Worker worker) throws InterruptedException {
    getPool(worker.getWorkerKey()).invalidateWorker(worker, worker.getStatus().isPendingEviction());
  }

  @Override
  public void reset() {
    for (WorkerKeyPool pool : pools.values()) {
      pool.reset();
    }
  }

  @Override
  public void close() {
    for (WorkerKeyPool pool : pools.values()) {
      pool.close();
    }
  }

  private WorkerKeyPool getPool(WorkerKey key) {
    return pools.computeIfAbsent(key, this::createPool);
  }

  private WorkerKeyPool createPool(WorkerKey key) {
    if (key.isMultiplex()) {
      return new WorkerKeyPool(
          key,
          getMaxWorkerInstances(
              multiplexMaxInstances, key.getMnemonic(), DEFAULT_MAX_MULTIPLEX_WORKERS));
    }
    return new WorkerKeyPool(
        key,
        getMaxWorkerInstances(
            singleplexMaxInstances, key.getMnemonic(), DEFAULT_MAX_SINGLEPLEX_WORKERS));
  }

  private Integer getMaxWorkerInstances(
      ImmutableMap<String, Integer> maxInstances, String mnemonic, int defaultMaxInstances) {
    if (maxInstances.containsKey(mnemonic)) {
      return maxInstances.get(mnemonic);
    }
    // Empty-string contains the user-specified worker maximum instances.
    return maxInstances.getOrDefault("", defaultMaxInstances);
  }

  /**
   * Actual pool implementation that handles the borrowing, returning and invalidation of workers of
   * a single worker key.
   *
   * <p>The following describes how the key features of the pool and how they work in tandem with
   * each other:
   *
   * <ul>
   *   <li>Borrowing a worker: If quota is available, the pool returns an already existing idle
   *       worker or creates a new worker. If quota is not available, it creates a {@code
   *       PendingWorkerRequest} in the waiting queue and waits on it.
   *   <li>Returning worker: If there are pending requests in the waiting queue, directly hand the
   *       worker over to that request, signalling to the waiting thread to proceed. Otherwise,
   *       returns the worker back to the pool.
   *   <li>Invalidating worker: Destroys this worker and removes it from the pool. The pool is
   *       optionally shrunk, which reduces the maximum number of workers that can be in the pool
   *       (to a minimum of 1). If the pool is not shrunk, the destruction of this worker represents
   *       a freeing up of quota, in this case it signals for any pending request to continue and
   *       effectively taking over this quota.
   * </ul>
   */
  private class WorkerKeyPool {

    private final WorkerKey key;
    private final int max;
    // The number of workers in use.
    private final AtomicInteger acquired = new AtomicInteger(0);
    // The number by which the overall quota is shrunk by.
    private final AtomicInteger shrunk = new AtomicInteger(0);

    private final BlockingDeque<Worker> idleWorkers = new LinkedBlockingDeque<>();

    /**
     * The waiting queue is meant to provide fairness in borrowing from the pool (first come first
     * serve), any freeing up of quota (either through returning or invalidating a worker) will
     * service requests this queue first.
     *
     * <p>With workers as a resource, workers are only borrowed when they are available, so this
     * doesn't get used, i.e. there shouldn't be any borrowers waiting here, where the {@code
     * ResourceManager} handles the proper synchronization to ensure that workers are borrowed
     * together with its allocated resources.
     *
     * <p>Regardless, this implementation is still included to ensure correctness such that multiple
     * threads can still borrow concurrently, without needing to check how many workers are actually
     * available (blocking if unavailable).
     */
    private final BlockingDeque<PendingWorkerRequest> waitingQueue = new LinkedBlockingDeque<>();

    private final Set<Worker> activeSet = new HashSet<>();

    public WorkerKeyPool(WorkerKey key, int max) {
      this.key = key;
      this.max = max;
    }

    private synchronized Set<Integer> evictWorkers(Set<Integer> workerIdsToEvict) {
      Set<Integer> evictedWorkerIds = new HashSet<>();
      for (Worker worker : idleWorkers) {
        if (workerIdsToEvict.contains(worker.getWorkerId())) {
          evictedWorkerIds.add(worker.getWorkerId());
          worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE);
          // Currently when evicting idle workers, we do not shrink the pool. The pool is only
          // shrunk when we have to postpone invalidation of the worker.
          invalidateWorker(worker, /* shouldShrinkPool= */ false);
          idleWorkers.remove(worker);
          logger.atInfo().log(
              "Evicted %s worker (id %d, key hash %d).",
              worker.getWorkerKey().getMnemonic(),
              worker.getWorkerId(),
              worker.getWorkerKey().hashCode());
        }
        // TODO(b/323880131): Move postponing of invalidation from {@code WorkerLifecycleManager}
        // here, since all we need to do is to update the statuses. We keep it like this for now
        // to preserve the existing behavior.
      }
      return evictedWorkerIds;
    }

    private synchronized int getNumActive() {
      return acquired.get();
    }

    private synchronized int getEffectiveMax() {
      return max - shrunk.get();
    }

    private synchronized boolean hasAvailableQuota() {
      return getEffectiveMax() - getNumActive() > 0;
    }

    // Callers should atomically check to confirm that workers are available before calling this
    // method or risk being blocked waiting for a worker to be available.
    private Worker borrowWorker(WorkerKey key) throws IOException, InterruptedException {
      Worker worker = null;
      PendingWorkerRequest pendingReq = null;
      // We don't want to hold the lock on the pool while creating or waiting for a worker or quota
      // to be available.
      synchronized (this) {
        while (!idleWorkers.isEmpty()) {
          // LIFO: It's better to re-use a worker as often as possible and keep it hot, in order to
          // profit from JIT optimizations as much as possible.
          worker = idleWorkers.takeLast();
          // We need to validate with the passed in `key` rather than `worker.getWorkerKey()`
          // because the former can contain a different combined files hash if the files changed.
          if (factory.validateWorker(key, worker)) {
            acquired.incrementAndGet();
            break;
          }
          invalidateWorker(worker, /* shouldShrinkPool= */ false);
          worker = null;
        }

        if (worker == null) {
          // If we were unable to get an idle worker, then either create or wait for one.
          if (hasAvailableQuota()) {
            // No idle workers, but we have space to create another.
            acquired.incrementAndGet();
          } else {
            pendingReq = new PendingWorkerRequest();
            waitingQueue.add(pendingReq);
          }
        }
      }

      if (pendingReq != null) {
        // Wait until the resources are available. We cannot do this while synchronized because that
        // would deadlock by blocking other threads from returning and thus freeing up quota for
        // this to proceed.
        worker = pendingReq.await();
      }

      if (worker == null) {
        worker = factory.create(key);
      }

      activeSet.add(worker);
      return worker;
    }

    private synchronized void returnWorker(WorkerKey key, Worker worker) {
      if (!factory.validateWorker(key, worker)) {
        invalidateWorker(worker, true);
        return;
      }

      activeSet.remove(worker);

      PendingWorkerRequest pendingReq = waitingQueue.poll();
      if (pendingReq != null) {
        // Pass the worker directly to the waiting thread.
        pendingReq.signal(worker);
      } else {
        acquired.decrementAndGet();
        idleWorkers.addLast(worker);
      }
    }

    private synchronized void invalidateWorker(Worker worker, boolean shouldShrinkPool) {
      factory.destroyWorker(worker.getWorkerKey(), worker);

      if (idleWorkers.contains(worker)) {
        idleWorkers.remove(worker);
        return;
      }

      // If it isn't idle, then we're destroying an active worker.
      activeSet.remove(worker);

      // We don't want to shrink the pool to 0.
      if (shouldShrinkPool && getEffectiveMax() > 1) {
        // When shrinking, there is no effective change in the availability, so there is no need to
        // signal a waiting thread to proceed.
        acquired.decrementAndGet();
        shrunk.incrementAndGet();
        return;
      }

      PendingWorkerRequest pendingReq = waitingQueue.poll();
      if (pendingReq == null) {
        // Since there is no pending request, we free up this quota.
        acquired.decrementAndGet();
      } else {
        // Since there is a pending request, hold onto this quota (and do not decrement acquired) so
        // that other threads aren't able to borrow before this pending request (thus creating a
        // race condition).
        pendingReq.signal(null);
      }
    }

    /**
     * It is not important that we synchronize here, the {@code WorkerLifecycleManager} takes the
     * idle workers and figures out (non-atomically with respect to this instance) which workers to
     * evict. So it is possible that an idle worker gets acquired before it decides to evict a
     * previously idle worker.
     */
    public Set<Integer> getIdleWorkers() {
      return idleWorkers.stream().map(Worker::getWorkerId).collect(toImmutableSet());
    }

    private void reset() {
      shrunk.set(0);
      logger.atInfo().log(
          "clearing shrunk values for %s (key hash %d) worker pool",
          key.getMnemonic(), key.hashCode());
    }

    // Destroys all workers created in this pool.
    private synchronized void close() {
      for (Worker worker : idleWorkers) {
        factory.destroyWorker(worker.getWorkerKey(), worker);
      }
      for (Worker worker : activeSet) {
        logger.atInfo().log(
            "Interrupting and shutting down active worker %s (id %d) due to pool shutdown",
            key.getMnemonic(), worker.getWorkerId());
        factory.destroyWorker(worker.getWorkerKey(), worker);
      }
    }
  }

  /**
   * Used to pass workers from threads that are returning the worker to the pool, bypassing the
   * queue.
   */
  private static class PendingWorkerRequest {
    final CountDownLatch latch = new CountDownLatch(1);
    @Nullable volatile Worker worker = null;

    /** Returns a worker instance that has been freed, or null if the worker needs to be created. */
    @Nullable
    public Worker await() throws InterruptedException {
      latch.await();
      return worker;
    }

    /**
     * Signals to the thread #await(ing) to proceed. When calling this, the {@code
     * WorkerKeyPool.acquired} quota associated to this worker should not be released because that
     * allows for race conditions with other threads attempting to borrow from the pool.
     */
    public void signal(@Nullable Worker worker) {
      this.worker = worker;
      latch.countDown();
    }
  }
}
