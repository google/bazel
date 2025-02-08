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

import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.shouldInline;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.getFieldInfo;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.FieldInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.ObjectInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

/** Traverses an object graph, feeding information into a given {@link GraphDataCollector}. */
final class GraphTraverser<SinkT extends GraphDataCollector.Sink> {
  @Nullable private final ObjectCodecRegistry registry;
  private final GraphDataCollector<SinkT> collector;

  GraphTraverser(@Nullable ObjectCodecRegistry registry, GraphDataCollector<SinkT> collector) {
    this.registry = registry;
    this.collector = collector;
  }

  void traverseObject(@Nullable String label, Object obj, SinkT sink) {
    if (obj == null) {
      collector.outputNull(label, sink);
      return;
    }

    Class<?> type = obj.getClass();

    if (registry != null) {
      Integer maybeConstantTag = registry.maybeGetTagForConstant(obj);
      if (maybeConstantTag != null) {
        collector.outputSerializationConstant(label, type, maybeConstantTag, sink);
        return;
      }
    }

    if (WeakReference.class.isAssignableFrom(type)) {
      collector.outputWeakReference(label, sink);
      return;
    }

    if (shouldInline(type)) {
      collector.outputInlineObject(label, type, obj, sink);
      return;
    }

    Descriptor descriptor = collector.checkCache(label, type, obj, sink);
    if (descriptor == null) {
      return; // cache hit
    }

    if (type.isArray()) {
      traverseArray(label, descriptor, type, obj, sink);
      return;
    }

    if (obj instanceof Map) {
      traverseMapEntries(label, descriptor, (Map<?, ?>) obj, sink);
      return;
    }

    if (obj instanceof Collection) {
      traverseCollectionElements(label, descriptor, (Collection<?>) obj, sink);
      return;
    }

    traverseObjectFields(label, descriptor, type, obj, sink);
  }

  private void traverseArray(
      @Nullable String label, Descriptor descriptor, Class<?> type, Object obj, SinkT sink) {
    var componentType = type.getComponentType();
    if (componentType.equals(byte.class)) {
      collector.outputByteArray(label, descriptor, (byte[]) obj, sink);
      return;
    }

    // In theory, there could be special casing WeakReferences here, to match the handling in
    // `traverseObject`. However, since Java does not support generic arrays we don't expect to
    // encounter an array of WeakReferences.

    if (shouldInline(componentType)) {
      collector.outputInlineArray(label, descriptor, obj, sink);
      return;
    }

    Object[] arr = (Object[]) obj;
    if (arr.length == 0) {
      collector.outputEmptyAggregate(label, descriptor, obj, sink);
      return;
    }

    SinkT subSink = collector.initAggregate(label, descriptor, obj, sink);
    for (int i = 0; i < arr.length; i++) {
      traverseObject(/* label= */ null, arr[i], subSink);
    }
    subSink.completeAggregate();
  }

  private void traverseMapEntries(
      @Nullable String label, Descriptor descriptor, Map<?, ?> map, SinkT sink) {
    if (map.isEmpty()) {
      collector.outputEmptyAggregate(label, descriptor, map, sink);
      return;
    }

    SinkT subSink = collector.initAggregate(label, descriptor, map, sink);
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      traverseObject(/* label= */ "key=", entry.getKey(), subSink);
      traverseObject(/* label= */ "value=", entry.getValue(), subSink);
    }
    subSink.completeAggregate();
  }

  private void traverseCollectionElements(
      @Nullable String label, Descriptor descriptor, Collection<?> collection, SinkT sink) {
    if (collection.isEmpty()) {
      collector.outputEmptyAggregate(label, descriptor, collection, sink);
      return;
    }

    SinkT subSink = collector.initAggregate(label, descriptor, collection, sink);
    for (var next : collection) {
      traverseObject(/* label= */ null, next, subSink);
    }
    subSink.completeAggregate();
  }

  private void traverseObjectFields(
      @Nullable String label, Descriptor descriptor, Class<?> type, Object obj, SinkT sink) {
    ImmutableList<FieldInfo> fieldInfo = getFieldInfo(type);
    if (fieldInfo.isEmpty()) {
      collector.outputEmptyAggregate(label, descriptor, obj, sink);
      return;
    }

    SinkT subSink = collector.initAggregate(label, descriptor, obj, sink);
    for (FieldInfo info : fieldInfo) {
      switch (info) {
        case PrimitiveInfo primitiveInfo:
          collector.outputPrimitive(primitiveInfo, obj, subSink);
          break;
        case ObjectInfo objectInfo:
          traverseObject(objectInfo.name() + "=", objectInfo.getFieldValue(obj), subSink);
      }
    }
    subSink.completeAggregate();
  }
}
