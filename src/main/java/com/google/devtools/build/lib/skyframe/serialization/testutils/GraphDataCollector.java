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
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo;
import javax.annotation.Nullable;

/**
 * Collects data from an object graph driven by {@link GraphTraverser}.
 *
 * <p>The {@code label} parameter, common to many of the methods here, is a label that the parent
 * uses for a child object. For example, if the parent is an ordinary object and the child is one of
 * its members, the label is the field name. If the parent is a map, the label might be "key" or
 * "value".
 */
interface GraphDataCollector<SinkT extends GraphDataCollector.Sink> {
  /**
   * Receiver for child data, scoped to a particular parent.
   *
   * <p>The accept methods are specific to the policy implementation.
   */
  interface Sink {
    /** Called once all children have been traversed. */
    void completeAggregate();
  }

  void outputNull(@Nullable String label, SinkT sink);

  void outputSerializationConstant(@Nullable String label, Class<?> type, int tag, SinkT sink);

  void outputWeakReference(@Nullable String label, SinkT sink);

  void outputInlineObject(@Nullable String label, Class<?> type, Object obj, SinkT sink);

  void outputPrimitive(PrimitiveInfo info, Object parent, SinkT sink);

  /**
   * Metadata about an object.
   *
   * @param description describes the type of the object
   * @param traversalIndex the index at which this object was first encountered during traversal
   */
  record Descriptor(String description, int traversalIndex) {
    @Override
    public final String toString() {
      return description + "(" + traversalIndex + ")";
    }
  }

  /**
   * Checks whether {@code obj} already exists in the cache.
   *
   * <p>If it exists in the cache, populates {@code sink} appropriately and returns null. Otherwise,
   * returns a descriptor to be used (that does not include {@code label}).
   */
  @Nullable
  Descriptor checkCache(@Nullable String label, Class<?> type, Object obj, SinkT sink);

  void outputByteArray(@Nullable String label, Descriptor descriptor, byte[] bytes, SinkT sink);

  /**
   * Outputs an array of elements of inlined type.
   *
   * <p>{@code arr} could be an array of primitives, which cannot be cast to {@code Object[]}.
   */
  void outputInlineArray(@Nullable String label, Descriptor descriptor, Object arr, SinkT sink);

  void outputEmptyAggregate(@Nullable String label, Descriptor descriptor, Object obj, SinkT sink);

  /**
   * Non-empty maps, collections, arrays and ordinary objects are handled using this method.
   *
   * <p>Initializes the output of the aggregate, {@code obj}. The returned {@link SinkT} should be
   * used for output of {@code obj}'s children. {@link SinkT#completeAggregate} must be called after
   * these children are complete.
   *
   * @param descriptor a description of {@code obj}, for example, a text description of its type
   * @param sink where to write the aggregate
   */
  SinkT initAggregate(@Nullable String label, Descriptor descriptor, Object obj, SinkT sink);
}
