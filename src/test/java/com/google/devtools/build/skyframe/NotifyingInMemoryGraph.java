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

import com.google.devtools.build.skyframe.NotifyingHelper.Order;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** {@link NotifyingHelper} that additionally implements the {@link InMemoryGraph} interface. */
class NotifyingInMemoryGraph extends NotifyingHelper.NotifyingProcessableGraph
    implements InMemoryGraph {
  NotifyingInMemoryGraph(InMemoryGraph delegate, NotifyingHelper.Listener graphListener) {
    super(delegate, graphListener);
  }

  @Override
  public NodeBatch createIfAbsentBatch(
      @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    try {
      return super.createIfAbsentBatch(requestor, reason, keys);
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Nullable
  @Override
  public NodeEntry get(@Nullable SkyKey requestor, Reason reason, SkyKey key) {
    try {
      return super.get(requestor, reason, key);
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Map<SkyKey, ? extends NodeEntry> getBatchMap(
      @Nullable SkyKey requestor, Reason reason, Iterable<? extends SkyKey> keys) {
    try {
      return super.getBatchMap(requestor, reason, keys);
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Map<SkyKey, SkyValue> getValues() {
    notifyingHelper.graphListener.accept(
        // Be gentle to tests that assume the key is not null
        /* key= */ () -> SkyFunctionName.FOR_TESTING,
        NotifyingHelper.EventType.GET_VALUES,
        NotifyingHelper.Order.BEFORE,
        /* context= */ null);
    return ((InMemoryGraph) delegate).getValues();
  }

  @Override
  public void remove(SkyKey key) {
    notifyingHelper.graphListener.accept(
        key, NotifyingHelper.EventType.REMOVE, Order.BEFORE, /* context= */ null);
    delegate.remove(key);
  }

  @Override
  public int valuesSize() {
    return ((InMemoryGraph) delegate).valuesSize();
  }

  @Override
  public Map<SkyKey, SkyValue> getDoneValues() {
    return ((InMemoryGraph) delegate).getDoneValues();
  }

  @Override
  public Collection<InMemoryNodeEntry> getAllNodeEntries() {
    return ((InMemoryGraph) delegate).getAllNodeEntries();
  }

  @Override
  public void parallelForEach(Consumer<InMemoryNodeEntry> consumer) {
    ((InMemoryGraph) delegate).parallelForEach(consumer);
  }

  @Override
  public void cleanupInterningPools() {
    ((InMemoryGraph) delegate).cleanupInterningPools();
  }

  @Override
  public void removeIfDone(SkyKey key) {
    ((InMemoryGraph) delegate).removeIfDone(key);
  }

  @Override
  @Nullable
  public InMemoryNodeEntry getIfPresent(SkyKey key) {
    return ((InMemoryGraph) delegate).getIfPresent(key);
  }

  @Override
  public void shrinkNodeMap() {
    ((InMemoryGraph) delegate).shrinkNodeMap();
  }
}
