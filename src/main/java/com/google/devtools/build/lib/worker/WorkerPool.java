// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableSet;
import java.io.IOException;

/**
 * A worker pool that spawns multiple workers and delegates work to them. Allows separate
 * configuration for singleplex and multiplex workers. While the configuration is per mnemonic, the
 * actual pools need to be per WorkerKey, as different WorkerKeys may imply different process
 * startup options.
 *
 * <p>This is useful when the worker cannot handle multiple parallel requests on its own and we need
 * to pre-fork a couple of them instead. Multiplex workers <em>can</em> handle multiple parallel
 * requests, but do so through WorkerProxy instances.
 */
public interface WorkerPool {

  /**
   * Returns the current maximum number of workers allowed in the pool.
   *
   * @param key the worker key.
   */
  int getMaxTotalPerKey(WorkerKey key);

  /**
   * Returns the number of active workers.
   *
   * @param key the worker key.
   */
  int getNumActive(WorkerKey key);

  /**
   * Returns whether there is quota available to create or use an existing worker.
   *
   * <p>It is essentially #getMaxTotalPerKey() - #getNumActive() > 0, but meant to be handled
   * atomically to prevent internal race conditions.
   *
   * @param key the worker key.
   * @return whether there is quota available to either get an existing or create a new worker.
   */
  boolean hasAvailableQuota(WorkerKey key);

  /**
   * Evicts specified workers from the pool, destroying them.
   *
   * <p>It is possible that not all specified workers get evicted if they become active.
   *
   * @param workerIdsToEvict the worker ids to attempt to evict.
   * @return a set of worker ids that were successfully evicted.
   */
  ImmutableSet<Integer> evictWorkers(ImmutableSet<Integer> workerIdsToEvict)
      throws InterruptedException;

  /** Returns the idle workers in the pool (note that these workers can still become active). */
  ImmutableSet<Integer> getIdleWorkers() throws InterruptedException;

  /**
   * Borrows a persistent worker from the pool, creating if necessary and blocking if unavailable.
   *
   * @param key the worker key.
   */
  Worker borrowWorker(WorkerKey key) throws IOException, InterruptedException;

  /**
   * Returns an active worker back to the pool.
   *
   * @param key the worker key.
   * @param worker the worker to be returned.
   */
  void returnWorker(WorkerKey key, Worker worker);

  /**
   * Invalidates the worker, thus destroying it.
   *
   * @param worker the worker to be invalidated.
   */
  void invalidateWorker(Worker worker) throws InterruptedException;

  void reset();

  void close();
}
