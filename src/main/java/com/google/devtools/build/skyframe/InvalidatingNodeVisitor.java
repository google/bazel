// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor;
import com.google.devtools.build.lib.concurrent.NamedForkJoinPool;
import com.google.devtools.build.lib.concurrent.QuiescingExecutor;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.NodeEntry.MarkedDirtyResult;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.errorprone.annotations.ForOverride;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A visitor that is useful for invalidating transitive dependencies of Skyframe nodes.
 *
 * <p>Interruptibility: It is safe to interrupt the invalidation process at any time. Consider a
 * graph and a set of modified nodes. Then the reverse transitive closure of the modified nodes is
 * the set of dirty nodes. We provide interruptibility by making sure that the following invariant
 * holds at any time:
 *
 * <p>If a node is dirty, but not removed (or marked as dirty) yet, then either it or any of its
 * transitive dependencies must be in the {@link #pendingVisitations} set. Furthermore, reverse dep
 * pointers must always point to existing nodes.
 *
 * <p>Thread-safety: This class should only be instantiated and called on a single thread, but
 * internally it spawns many worker threads to process the graph. The thread-safety of the workers
 * on the graph can be delicate, and is documented below. Moreover, no other modifications to the
 * graph can take place while invalidation occurs.
 */
public abstract class InvalidatingNodeVisitor<GraphT extends QueryableGraph> {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // Default thread count is equal to the number of cores to exploit
  // that level of hardware parallelism, since invalidation should be CPU-bound.
  // We may consider increasing this in the future.
  @VisibleForTesting
  static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();

  private static final int EXPECTED_PENDING_SET_SIZE = DEFAULT_THREAD_COUNT * 8;
  private static final int EXPECTED_VISITED_SET_SIZE = 1024;

  private static final ErrorClassifier errorClassifier =
      new ErrorClassifier() {
        @Override
        protected ErrorClassification classifyException(Exception e) {
          return e instanceof RuntimeException
              ? ErrorClassification.CRITICAL_AND_LOG
              : ErrorClassification.NOT_CRITICAL;
        }
      };

  protected final GraphT graph;
  protected final DirtyAndInflightTrackingProgressReceiver progressReceiver;
  // Aliased to InvalidationState.pendingVisitations.
  protected final Set<Pair<SkyKey, InvalidationType>> pendingVisitations;
  protected final QuiescingExecutor executor;

  protected InvalidatingNodeVisitor(
      GraphT graph,
      DirtyAndInflightTrackingProgressReceiver progressReceiver,
      InvalidationState state) {
    this.executor =
        new AbstractQueueVisitor(
            /* parallelism= */ DEFAULT_THREAD_COUNT,
            /* keepAliveTime= */ 15,
            /* units= */ TimeUnit.SECONDS,
            ExceptionHandlingMode.FAIL_FAST,
            "skyframe-invalidator",
            errorClassifier);
    this.graph = Preconditions.checkNotNull(graph);
    this.progressReceiver = Preconditions.checkNotNull(progressReceiver);
    this.pendingVisitations = state.pendingValues;
  }

  protected InvalidatingNodeVisitor(
      GraphT graph,
      DirtyAndInflightTrackingProgressReceiver progressReceiver,
      InvalidationState state,
      ForkJoinPool forkJoinPool) {
    this.executor = ForkJoinQuiescingExecutor.newBuilder()
        .withOwnershipOf(forkJoinPool)
        .setErrorClassifier(errorClassifier)
        .build();
    this.graph = Preconditions.checkNotNull(graph);
    this.progressReceiver = Preconditions.checkNotNull(progressReceiver);
    this.pendingVisitations = state.pendingValues;
  }

  private static final Duration MIN_TIME_FOR_LOGGING = Duration.ofMillis(10);

  /** Initiates visitation and waits for completion. */
  final void run() throws InterruptedException {
    try (AutoProfiler ignored =
        GoogleAutoProfilerUtils.logged(
            "invalidation of " + pendingVisitations.size() + " nodes", MIN_TIME_FOR_LOGGING)) {
      // Make a copy to avoid concurrent modification confusing us as to which nodes were passed by
      // the caller, and which are added by other threads during the run. Since no tasks have been
      // started yet, this is thread-safe.
      runInternal(ImmutableList.copyOf(pendingVisitations));
    }
    Preconditions.checkState(
        pendingVisitations.isEmpty(),
        "All dirty nodes should have been processed: %s",
        pendingVisitations);
  }

  @ForOverride
  protected void runInternal(ImmutableList<Pair<SkyKey, InvalidationType>> pendingList)
      throws InterruptedException {
    try (AutoProfiler ignored =
        GoogleAutoProfilerUtils.logged("invalidation enqueuing", MIN_TIME_FOR_LOGGING)) {
      for (Pair<SkyKey, InvalidationType> visitData : pendingList) {
        executor.execute(() -> visit(ImmutableList.of(visitData.first), visitData.second));
      }
    }
    try {
      executor.awaitQuiescence(/*interruptWorkers=*/ true);
    } catch (IllegalStateException e) {
      // TODO(mschaller): Remove this wrapping after debugging the invalidation-after-OOMing-eval
      // problem. The wrapping provides a stack trace showing what caused the invalidation.
      throw new IllegalStateException(e);
    }
  }

  @VisibleForTesting
  final CountDownLatch getInterruptionLatchForTestingOnly() {
    return executor.getInterruptionLatchForTestingOnly();
  }

  /** Enqueues nodes for invalidation. Elements of {@code keys} may not exist in the graph. */
  @ThreadSafe
  abstract void visit(Collection<SkyKey> keys, InvalidationType invalidationType);

  @VisibleForTesting
  enum InvalidationType {
    /** The node is dirty and must be recomputed. */
    CHANGED,
    /** The node is dirty, but may be marked clean later during change pruning. */
    DIRTIED,
    /** The node is deleted. */
    DELETED
  }

  /**
   * Invalidation state object that keeps track of which nodes need to be invalidated, but have not
   * been dirtied/deleted yet. This supports interrupts - by only deleting a node from this set
   * when all its parents have been invalidated, we ensure that no information is lost when an
   * interrupt comes in.
   */
  static class InvalidationState {
    private final Set<Pair<SkyKey, InvalidationType>> pendingValues =
        Collections.newSetFromMap(
            new ConcurrentHashMap<>(EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT));
    private final InvalidationType defaultUpdateType;

    private InvalidationState(InvalidationType defaultUpdateType) {
      this.defaultUpdateType = Preconditions.checkNotNull(defaultUpdateType);
    }

    void update(Iterable<SkyKey> diff) {
      Iterables.addAll(
          pendingValues, Iterables.transform(diff, skyKey -> Pair.of(skyKey, defaultUpdateType)));
    }

    @VisibleForTesting
    boolean isEmpty() {
      return pendingValues.isEmpty();
    }

    @VisibleForTesting
    Set<Pair<SkyKey, InvalidationType>> getInvalidationsForTesting() {
      return ImmutableSet.copyOf(pendingValues);
    }
  }

  static final class DirtyingInvalidationState extends InvalidationState {
    public DirtyingInvalidationState() {
      super(InvalidationType.CHANGED);
    }
  }

  static final class DeletingInvalidationState extends InvalidationState {
    private ConcurrentHashMap<SkyKey, Boolean> doneKeysWithRdepsToRemove;
    private ConcurrentHashMap<SkyKey, Boolean> visitedKeysAcrossInterruptions;

    DeletingInvalidationState() {
      super(InvalidationType.DELETED);
      initializeFields();
    }

    private void initializeFields() {
      doneKeysWithRdepsToRemove =
          new ConcurrentHashMap<>(EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT);
      visitedKeysAcrossInterruptions =
          new ConcurrentHashMap<>(EXPECTED_PENDING_SET_SIZE, .75f, DEFAULT_THREAD_COUNT);
    }

    @Override
    boolean isEmpty() {
      return super.isEmpty() && doneKeysWithRdepsToRemove.isEmpty();
    }

    void clear() {
      initializeFields();
    }
  }

  /** A node-deleting implementation. */
  static final class DeletingNodeVisitor extends InvalidatingNodeVisitor<InMemoryGraph> {
    private final Set<SkyKey> visited = Sets.newConcurrentHashSet();
    private final boolean traverseGraph;
    private final DeletingInvalidationState state;

    DeletingNodeVisitor(
        InMemoryGraph graph,
        DirtyAndInflightTrackingProgressReceiver progressReceiver,
        DeletingInvalidationState state,
        boolean traverseGraph) {
      super(
          graph,
          progressReceiver,
          state,
          NamedForkJoinPool.newNamedPool("deleting node visitor", DEFAULT_THREAD_COUNT));
      this.traverseGraph = traverseGraph;
      this.state = state;
    }

    @Override
    protected void runInternal(ImmutableList<Pair<SkyKey, InvalidationType>> pendingList)
        throws InterruptedException {
      try (AutoProfiler ignored =
          GoogleAutoProfilerUtils.logged(
              "invalidation enqueuing for " + pendingList.size() + " nodes",
              MIN_TIME_FOR_LOGGING)) {
        // To avoid contention and scheduling too many jobs for our #cpus, we start
        // DEFAULT_THREAD_COUNT jobs, each processing a chunk of the pending visitations.
        long listSize = pendingList.size();
        long numThreads = min(DEFAULT_THREAD_COUNT, listSize);
        for (long i = 0; i < numThreads; i++) {
          // Use long multiplication to avoid possible overflow, as numThreads * listSize might be
          // larger than max int.
          int startIndex = (int) ((i * listSize) / numThreads);
          int endIndex = (int) (((i + 1) * listSize) / numThreads);
          executor.execute(
              () ->
                  visit(
                      Collections2.transform(
                          pendingList.subList(startIndex, endIndex), Pair::getFirst),
                      InvalidationType.DELETED));
        }
      }
      try (AutoProfiler ignored =
          GoogleAutoProfilerUtils.logged("invalidation graph traversal", MIN_TIME_FOR_LOGGING)) {
        executor.awaitQuiescence(/*interruptWorkers=*/ true);
      }
      ConcurrentHashMap.KeySetView<SkyKey, Boolean> deletedKeys =
          state.visitedKeysAcrossInterruptions.keySet();
      // TODO(b/150299871): this is uninterruptible.
      try (AutoProfiler ignored =
          GoogleAutoProfilerUtils.logged(
              "reverse dep removal of "
                  + deletedKeys.size()
                  + " deleted rdeps from "
                  + state.doneKeysWithRdepsToRemove.size()
                  + " deps",
              MIN_TIME_FOR_LOGGING)) {
        state.doneKeysWithRdepsToRemove.forEachEntry(
            /*parallelismThreshold=*/ 1024,
            e -> {
              NodeEntry entry = graph.get(null, Reason.RDEP_REMOVAL, e.getKey());
              if (entry != null) {
                entry.removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys);
              }
            });
        state.clear();
      }
    }

    @Override
    public void visit(Collection<SkyKey> keys, InvalidationType invalidationType) {
      Preconditions.checkState(invalidationType == InvalidationType.DELETED, keys);
      ImmutableList.Builder<SkyKey> unvisitedKeysBuilder = ImmutableList.builder();
      for (SkyKey key : keys) {
        if (visited.add(key)) {
          unvisitedKeysBuilder.add(key);
        }
      }
      ImmutableList<SkyKey> unvisitedKeys = unvisitedKeysBuilder.build();
      for (SkyKey key : unvisitedKeys) {
        pendingVisitations.add(Pair.of(key, InvalidationType.DELETED));
      }
      NodeBatch entries = graph.getBatch(null, Reason.INVALIDATION, unvisitedKeys);
      for (SkyKey key : unvisitedKeys) {
        executor.execute(
            () -> {
              NodeEntry entry = entries.get(key);
              Pair<SkyKey, InvalidationType> invalidationPair =
                  Pair.of(key, InvalidationType.DELETED);
              if (entry == null) {
                pendingVisitations.remove(invalidationPair);
                return;
              }

              if (traverseGraph) {
                // Propagate deletion upwards.
                visit(entry.getAllReverseDepsForNodeBeingDeleted(), InvalidationType.DELETED);

                // Unregister this node as an rdep from its direct deps, since reverse dep edges
                // cannot point to non-existent nodes. To know whether the child has this node as an
                // "in-progress" rdep to be signaled, or just as a known rdep, we look at the deps
                // that this node declared during its last (presumably interrupted) evaluation. If a
                // dep is in this set, then it was notified to signal this node, and so the rdep
                // will be an in-progress rdep, if the dep itself isn't done. Otherwise it will be a
                // normal rdep. That information is used to remove this node as an rdep from the
                // correct list of rdeps in the child -- because of our compact storage of rdeps,
                // checking which list contains this parent could be expensive.
                Iterable<SkyKey> directDeps;
                try {
                  directDeps =
                      entry.isDone()
                          ? entry.getDirectDeps()
                          : entry.getAllDirectDepsForIncompleteNode();
                } catch (InterruptedException e) {
                  throw new IllegalStateException(
                      "Deletion cannot happen on a graph that may have blocking operations: "
                          + key
                          + ", "
                          + entry,
                      e);
                }
                // No need to do reverse dep surgery on nodes that are deleted/about to be deleted
                // anyway.
                Map<SkyKey, ? extends NodeEntry> depMap =
                    graph.getBatchMap(
                        key,
                        Reason.INVALIDATION,
                        Iterables.filter(
                            directDeps,
                            k ->
                                !state.visitedKeysAcrossInterruptions.containsKey(k)
                                    && !pendingVisitations.contains(
                                        Pair.of(k, InvalidationType.DELETED))));
                if (!depMap.isEmpty()) {
                  for (Map.Entry<SkyKey, ? extends NodeEntry> directDepEntry : depMap.entrySet()) {
                    NodeEntry dep = directDepEntry.getValue();
                    if (dep == null) {
                      continue;
                    }
                    if (dep.isDone()) {
                      state.doneKeysWithRdepsToRemove.putIfAbsent(
                          directDepEntry.getKey(), Boolean.TRUE);
                      continue;
                    }
                    try {
                      dep.removeReverseDep(key);
                    } catch (InterruptedException e) {
                      throw new IllegalStateException(
                          "Deletion cannot happen on a graph that may have blocking operations: "
                              + key
                              + ", "
                              + entry,
                          e);
                    }
                  }
                }
              }

              // Allow custom key-specific logic to update dirtiness status.
              progressReceiver.deleted(key);
              // Actually remove the node.
              graph.remove(key);

              // Remove the node from the set and add it to global visited as the last operation.
              state.visitedKeysAcrossInterruptions.put(key, Boolean.TRUE);
              pendingVisitations.remove(invalidationPair);
            });
      }
    }
  }

  /** A node-dirtying implementation. */
  static final class DirtyingNodeVisitor extends InvalidatingNodeVisitor<QueryableGraph> {
    private static final int SAFE_STACK_DEPTH = 1 << 9;

    private final Set<SkyKey> changed =
        Collections.newSetFromMap(
            new ConcurrentHashMap<>(EXPECTED_VISITED_SET_SIZE, .75f, DEFAULT_THREAD_COUNT));
    private final Set<SkyKey> dirtied =
        Collections.newSetFromMap(
            new ConcurrentHashMap<>(EXPECTED_VISITED_SET_SIZE, .75f, DEFAULT_THREAD_COUNT));

    DirtyingNodeVisitor(
        QueryableGraph graph,
        DirtyAndInflightTrackingProgressReceiver progressReceiver,
        InvalidationState state) {
      super(graph, progressReceiver, state);
    }

    @Override
    void visit(Collection<SkyKey> keys, InvalidationType invalidationType) {
      Preconditions.checkState(invalidationType != InvalidationType.DELETED, keys);
      visit(keys, invalidationType, /* depthForOverflowCheck= */ 0, null);
    }

    /**
     * Queues a task to dirty the nodes named by {@param keys}. May be called from multiple threads.
     * It is possible that the same node is enqueued many times. However, we require that a node is
     * only actually marked dirty/changed once, with two exceptions:
     *
     * <p>(1) If a node is marked dirty, it can subsequently be marked changed. This can occur if,
     * for instance, FileValue workspace/foo/foo.cc is marked dirty because FileValue workspace/foo
     * is marked changed (and every FileValue depends on its parent). Then FileValue
     * workspace/foo/foo.cc is itself changed (this can even happen on the same build).
     *
     * <p>(2) If a node is going to be marked both dirty and changed, as, for example, in the
     * previous case if both workspace/foo/foo.cc and workspace/foo have been changed in the same
     * build, the thread marking workspace/foo/foo.cc dirty may race with the one marking it
     * changed, and so try to mark it dirty after it has already been marked changed. In that case,
     * the {@link NodeEntry} ignores the second marking.
     *
     * <p>The invariant that we do not process a (SkyKey, InvalidationType) pair twice is enforced
     * by the {@link #changed} and {@link #dirtied} sets.
     *
     * <p>The "invariant" is also enforced across builds by checking to see if the entry is already
     * marked changed, or if it is already marked dirty and we are just going to mark it dirty
     * again.
     *
     * <p>If either of the above tests shows that we have already started a task to mark this entry
     * dirty/changed, or that it is already marked dirty/changed, we do not continue this task.
     */
    @ThreadSafe
    private void visit(
        Collection<SkyKey> keys,
        InvalidationType invalidationType,
        int depthForOverflowCheck,
        @Nullable SkyKey enqueueingKeyForExistenceCheck) {
      // Code from here until pendingVisitations#add is called below must be uninterruptible.
      boolean isChanged = (invalidationType == InvalidationType.CHANGED);
      Set<SkyKey> setToCheck = isChanged ? changed : dirtied;
      ArrayList<SkyKey> keysToGet = new ArrayList<>(keys.size());
      for (SkyKey key : keys) {
        if (setToCheck.add(key)) {
          if (isChanged && key.functionName().getHermeticity() == FunctionHermeticity.HERMETIC) {
            throw new IllegalStateException(
                "Nodes with hermetic functions cannot be marked 'changed': "
                    + "%s function:%s hermeticity:%s"
                        .formatted(key, key.functionName(), key.functionName().getHermeticity()));
          }
          keysToGet.add(key);
        }
      }
      for (SkyKey key : keysToGet) {
        pendingVisitations.add(Pair.of(key, invalidationType));
      }
      Map<SkyKey, ? extends NodeEntry> entries;
      try {
        entries = graph.getBatchMap(null, Reason.INVALIDATION, keysToGet);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // This can only happen if the main thread has been interrupted, and so the
        // AbstractQueueVisitor is shutting down. We haven't yet removed the pending visitations, so
        // we can resume next time.
        return;
      }
      if (enqueueingKeyForExistenceCheck != null && entries.size() != keysToGet.size()) {
        Set<SkyKey> missingKeys = Sets.difference(ImmutableSet.copyOf(keysToGet), entries.keySet());
        throw new IllegalStateException(
            String.format(
                "key(s) %s not in the graph, but enqueued for dirtying by %s",
                Iterables.limit(missingKeys, 10), enqueueingKeyForExistenceCheck));
      }
      // We take a deeper thread stack in exchange for less contention in the executor.
      int lastIndex = keysToGet.size() - 1;
      if (lastIndex == -1) {
        return;
      }
      for (int i = 0; i < lastIndex; i++) {
        SkyKey key = keysToGet.get(i);
        executor.execute(() -> dirtyKeyAndVisitParents(key, entries, invalidationType, 0));
      }
      SkyKey lastParent = keysToGet.get(lastIndex);
      if (depthForOverflowCheck > SAFE_STACK_DEPTH) {
        logger.atInfo().atMostEvery(1, MINUTES).log(
            "Stack depth too deep to safely recurse for %s (%s)",
            lastParent, enqueueingKeyForExistenceCheck);
        executor.execute(() -> dirtyKeyAndVisitParents(lastParent, entries, invalidationType, 0));
        return;
      }
      if (!Thread.interrupted()) {
        // Emulate what would happen if we'd submitted this to the executor: skip on interrupt.
        dirtyKeyAndVisitParents(lastParent, entries, invalidationType, depthForOverflowCheck + 1);
      }
    }

    private void dirtyKeyAndVisitParents(
        SkyKey key,
        Map<SkyKey, ? extends NodeEntry> entries,
        InvalidationType invalidationType,
        int depthForOverflowCheck) {
      NodeEntry entry = entries.get(key);

      if (entry == null) {
        pendingVisitations.remove(Pair.of(key, invalidationType));
        return;
      }

      boolean isChanged = invalidationType == InvalidationType.CHANGED;
      if (entry.isChanged() || (!isChanged && entry.isDirty())) {
        // If this node is already marked changed, or we are only marking this node
        // dirty, and it already is, move along.
        pendingVisitations.remove(Pair.of(key, invalidationType));
        return;
      }

      var dirtyType = isChanged ? DirtyType.CHANGE : DirtyType.DIRTY;

      // This entry remains in the graph in this dirty state until it is re-evaluated.
      MarkedDirtyResult markedDirtyResult;
      try {
        markedDirtyResult = entry.markDirty(dirtyType);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // This can only happen if the main thread has been interrupted, and so the
        // AbstractQueueVisitor is shutting down. We haven't yet removed the pending
        // visitation, so we can resume next time.
        return;
      }
      if (markedDirtyResult == null) {
        // Another thread has already dirtied this node. Don't do anything in this thread.
        pendingVisitations.remove(Pair.of(key, invalidationType));
        return;
      }

      progressReceiver.dirtied(key, dirtyType);
      pendingVisitations.remove(Pair.of(key, invalidationType));

      // Propagate dirtiness upwards and mark this node dirty/changed. Reverse deps should
      // only be marked dirty (because only a dependency of theirs has changed).
      visit(
          markedDirtyResult.getReverseDepsUnsafe(),
          InvalidationType.DIRTIED,
          depthForOverflowCheck,
          key);
    }
  }
}
