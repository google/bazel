// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.errorprone.annotations.ForOverride;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Class that allows clients to be notified on each access of the graph. Clients can simply track
 * accesses, or they can block to achieve desired synchronization. Clients should call {@link
 * TrackingAwaiter#INSTANCE#assertNoErrors} at the end of tests in case exceptions were swallowed in
 * async threads.
 */
public class NotifyingHelper {
  public static MemoizingEvaluator.GraphTransformerForTesting makeNotifyingTransformer(
      Listener listener) {
    return new MemoizingEvaluator.GraphTransformerForTesting() {
      @Override
      public InMemoryGraph transform(InMemoryGraph graph) {
        return new NotifyingInMemoryGraph(graph, listener);
      }

      @Override
      public ProcessableGraph transform(ProcessableGraph graph) {
        return new NotifyingProcessableGraph(graph, listener);
      }
    };
  }

  final Listener graphListener;

  NotifyingHelper(Listener graphListener) {
    this.graphListener = new ErrorRecordingDelegatingListener(graphListener);
  }

  /** Subclasses should override if they wish to subclass {@link NotifyingNodeEntry}. */
  @Nullable
  @ForOverride
  NotifyingNodeEntry wrapEntry(SkyKey key, @Nullable NodeEntry entry) {
    return entry == null ? null : new NotifyingNodeEntry(key, entry);
  }

  static class NotifyingProcessableGraph implements ProcessableGraph {
    final ProcessableGraph delegate;
    final NotifyingHelper notifyingHelper;

    NotifyingProcessableGraph(ProcessableGraph delegate, Listener graphListener) {
      this(delegate, new NotifyingHelper(graphListener));
    }

    NotifyingProcessableGraph(ProcessableGraph delegate, NotifyingHelper notifyingHelper) {
      this.delegate = delegate;
      this.notifyingHelper = notifyingHelper;
    }

    @Nullable
    @Override
    public NodeEntry get(@Nullable SkyKey requestor, Reason reason, SkyKey key)
        throws InterruptedException {
      var node = delegate.get(requestor, reason, key);
      // Maintains behavior for tests written when all DEP_REQUESTED calls were made as batch
      // requests. Now there are optimizations in SkyFunctionEnvironment for looking up deps
      // individually, but older tests may be written to listen for a GET_BATCH event.
      if (reason == Reason.DEP_REQUESTED) {
        notifyingHelper.graphListener.accept(key, EventType.GET_BATCH, Order.BEFORE, reason);
      } else if (reason == Reason.EVALUATION) {
        notifyingHelper.graphListener.accept(key, EventType.EVALUATE, Order.BEFORE, node);
      }
      return notifyingHelper.wrapEntry(key, node);
    }

    @Override
    public LookupHint getLookupHint(SkyKey key) {
      return delegate.getLookupHint(key);
    }

    @Override
    public void remove(SkyKey key) {
      delegate.remove(key);
    }

    @Override
    public NodeBatch createIfAbsentBatch(
        @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys)
        throws InterruptedException {
      for (SkyKey key : keys) {
        notifyingHelper.graphListener.accept(key, EventType.CREATE_IF_ABSENT, Order.BEFORE, null);
      }
      NodeBatch batch = delegate.createIfAbsentBatch(requestor, reason, keys);
      return key -> notifyingHelper.wrapEntry(key, batch.get(key));
    }

    @Override
    public Map<SkyKey, ? extends NodeEntry> getBatchMap(
        @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys)
        throws InterruptedException {
      for (SkyKey key : keys) {
        notifyingHelper.graphListener.accept(key, EventType.GET_BATCH, Order.BEFORE, reason);
      }
      return Maps.transformEntries(
          delegate.getBatchMap(requestor, reason, keys), notifyingHelper::wrapEntry);
    }

    @Nullable
    @Override
    public ImmutableSet<SkyKey> prefetchDeps(
        SkyKey requestor, Set<SkyKey> oldDeps, GroupedDeps previouslyRequestedDeps)
        throws InterruptedException {
      return delegate.prefetchDeps(requestor, oldDeps, previouslyRequestedDeps);
    }

    @Override
    public DepsReport analyzeDepsDoneness(SkyKey parent, List<SkyKey> deps)
        throws InterruptedException {
      return delegate.analyzeDepsDoneness(parent, deps);
    }
  }

  /**
   * Graph/value entry events that the receiver can be informed of. When writing tests, feel free to
   * add additional events here if needed.
   */
  public enum EventType {
    CREATE_IF_ABSENT,
    EVALUATE,
    ADD_REVERSE_DEP,
    ADD_EXTERNAL_DEP,
    REMOVE_REVERSE_DEP,
    GET_BATCH,
    GET_VALUES,
    GET_TEMPORARY_DIRECT_DEPS,
    SIGNAL,
    SET_VALUE,
    MARK_DIRTY,
    MARK_CLEAN,
    IS_CHANGED,
    GET_LIFECYCLE_STATE,
    GET_VALUE_WITH_METADATA,
    IS_DIRTY,
    IS_READY,
    CHECK_IF_DONE,
    ADD_TEMPORARY_DIRECT_DEPS,
    GET_ALL_DIRECT_DEPS_FOR_INCOMPLETE_NODE,
    RESET_FOR_RESTART_FROM_SCRATCH,
    REMOVE,
  }

  /**
   * Whether the given event is about to happen or has just happened. For some events, both will be
   * published, for others, only one. When writing tests, if you need an additional one to be
   * published, feel free to add it.
   */
  public enum Order {
    BEFORE,
    AFTER
  }

  /** Receiver to be informed when an event for a given key occurs. */
  public interface Listener {
    @ThreadSafe
    void accept(SkyKey key, EventType type, Order order, @Nullable Object context);

    Listener NULL_LISTENER = (key, type, order, context) -> {};
  }

  private static class ErrorRecordingDelegatingListener implements Listener {
    private final Listener delegate;

    private ErrorRecordingDelegatingListener(Listener delegate) {
      this.delegate = delegate;
    }

    @Override
    public void accept(SkyKey key, EventType type, Order order, @Nullable Object context) {
      try {
        delegate.accept(key, type, order, context);
      } catch (Exception e) {
        TrackingAwaiter.INSTANCE.injectExceptionAndMessage(
            e,
            "In NotifyingGraph: "
                + Joiner.on(", ").join(key, type, order, context == null ? "null" : context));
        throw new IllegalStateException(e);
      }
    }
  }

  /** {@link NodeEntry} that informs a {@link Listener} of various method calls. */
  public class NotifyingNodeEntry extends DelegatingNodeEntry {
    private final SkyKey myKey;
    private final NodeEntry delegate;

    NotifyingNodeEntry(SkyKey key, NodeEntry delegate) {
      myKey = key;
      this.delegate = delegate;
    }

    @Override
    public NodeEntry getDelegate() {
      return delegate;
    }

    @Override
    public DependencyState addReverseDepAndCheckIfDone(SkyKey reverseDep)
        throws InterruptedException {
      graphListener.accept(myKey, EventType.ADD_REVERSE_DEP, Order.BEFORE, reverseDep);
      DependencyState result = super.addReverseDepAndCheckIfDone(reverseDep);
      graphListener.accept(myKey, EventType.ADD_REVERSE_DEP, Order.AFTER, reverseDep);
      return result;
    }

    @Override
    public void addExternalDep() {
      super.addExternalDep();
      graphListener.accept(myKey, EventType.ADD_EXTERNAL_DEP, Order.AFTER, null);
    }

    @Override
    public void removeReverseDep(SkyKey reverseDep) throws InterruptedException {
      graphListener.accept(myKey, EventType.REMOVE_REVERSE_DEP, Order.BEFORE, reverseDep);
      super.removeReverseDep(reverseDep);
      graphListener.accept(myKey, EventType.REMOVE_REVERSE_DEP, Order.AFTER, reverseDep);
    }

    @Override
    public GroupedDeps getTemporaryDirectDeps() {
      graphListener.accept(myKey, EventType.GET_TEMPORARY_DIRECT_DEPS, Order.BEFORE, null);
      return super.getTemporaryDirectDeps();
    }

    @Override
    public boolean signalDep(Version childVersion, @Nullable SkyKey childForDebugging) {
      graphListener.accept(myKey, EventType.SIGNAL, Order.BEFORE, childForDebugging);
      boolean result = super.signalDep(childVersion, childForDebugging);
      graphListener.accept(myKey, EventType.SIGNAL, Order.AFTER, childForDebugging);
      return result;
    }

    @Override
    public Set<SkyKey> setValue(
        SkyValue value, Version graphVersion, @Nullable Version maxTransitiveSourceVersion)
        throws InterruptedException {
      graphListener.accept(myKey, EventType.SET_VALUE, Order.BEFORE, value);
      Set<SkyKey> result = super.setValue(value, graphVersion, maxTransitiveSourceVersion);
      graphListener.accept(myKey, EventType.SET_VALUE, Order.AFTER, value);
      return result;
    }

    @Override
    public MarkedDirtyResult markDirty(DirtyType dirtyType) throws InterruptedException {
      graphListener.accept(myKey, EventType.MARK_DIRTY, Order.BEFORE, dirtyType);
      MarkedDirtyResult result = super.markDirty(dirtyType);
      graphListener.accept(
          myKey,
          EventType.MARK_DIRTY,
          Order.AFTER,
          MarkDirtyAfterContext.create(dirtyType, result != null));
      return result;
    }

    @Override
    public NodeValueAndRdepsToSignal markClean() throws InterruptedException {
      graphListener.accept(myKey, EventType.MARK_CLEAN, Order.BEFORE, this);
      NodeValueAndRdepsToSignal result = super.markClean();
      graphListener.accept(myKey, EventType.MARK_CLEAN, Order.AFTER, this);
      return result;
    }

    @Override
    public boolean isChanged() {
      graphListener.accept(myKey, EventType.IS_CHANGED, Order.BEFORE, this);
      return super.isChanged();
    }

    @Override
    public boolean isDirty() {
      graphListener.accept(myKey, EventType.IS_DIRTY, Order.BEFORE, this);
      return super.isDirty();
    }

    @Override
    public boolean isReadyToEvaluate() {
      graphListener.accept(myKey, EventType.IS_READY, Order.BEFORE, this);
      return super.isReadyToEvaluate();
    }

    @Override
    public LifecycleState getLifecycleState() {
      graphListener.accept(myKey, EventType.GET_LIFECYCLE_STATE, Order.BEFORE, this);
      LifecycleState lifecycleState = super.getLifecycleState();
      graphListener.accept(myKey, EventType.GET_LIFECYCLE_STATE, Order.AFTER, lifecycleState);
      return lifecycleState;
    }

    @Override
    public SkyValue getValueMaybeWithMetadata() throws InterruptedException {
      graphListener.accept(myKey, EventType.GET_VALUE_WITH_METADATA, Order.BEFORE, this);
      return super.getValueMaybeWithMetadata();
    }

    @Override
    public DependencyState checkIfDoneForDirtyReverseDep(SkyKey reverseDep)
        throws InterruptedException {
      graphListener.accept(myKey, EventType.CHECK_IF_DONE, Order.BEFORE, reverseDep);
      DependencyState dependencyState = super.checkIfDoneForDirtyReverseDep(reverseDep);
      graphListener.accept(myKey, EventType.CHECK_IF_DONE, Order.AFTER, reverseDep);
      return dependencyState;
    }

    @Override
    public void addSingletonTemporaryDirectDep(SkyKey dep) {
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.BEFORE, dep);
      super.addSingletonTemporaryDirectDep(dep);
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.AFTER, dep);
    }

    @Override
    public void addTemporaryDirectDepGroup(List<SkyKey> group) {
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.BEFORE, group);
      super.addTemporaryDirectDepGroup(group);
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.AFTER, group);
    }

    @Override
    public void addTemporaryDirectDepsInGroups(Set<SkyKey> deps, List<Integer> groupSizes) {
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.BEFORE, deps);
      super.addTemporaryDirectDepsInGroups(deps, groupSizes);
      graphListener.accept(myKey, EventType.ADD_TEMPORARY_DIRECT_DEPS, Order.AFTER, deps);
    }

    @Override
    public ImmutableSet<SkyKey> getAllDirectDepsForIncompleteNode() throws InterruptedException {
      graphListener.accept(
          myKey, EventType.GET_ALL_DIRECT_DEPS_FOR_INCOMPLETE_NODE, Order.BEFORE, this);
      return super.getAllDirectDepsForIncompleteNode();
    }

    @Override
    public void resetEvaluationFromScratch() {
      delegate.resetEvaluationFromScratch();
      graphListener.accept(myKey, EventType.RESET_FOR_RESTART_FROM_SCRATCH, Order.AFTER, this);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("delegate", delegate).toString();
    }
  }

  /**
   * A pair of {@link DirtyType} and a bit saying whether the dirtying was successful, emitted to
   * the graph listener as the context {@link Order#AFTER} a call to {@link EventType#MARK_DIRTY} a
   * node.
   */
  public record MarkDirtyAfterContext(DirtyType dirtyType, boolean actuallyDirtied) {
    public MarkDirtyAfterContext {
      requireNonNull(dirtyType, "dirtyType");
    }

    static MarkDirtyAfterContext create(DirtyType dirtyType, boolean actuallyDirtied) {
      return new MarkDirtyAfterContext(dirtyType, actuallyDirtied);
    }
  }
}
