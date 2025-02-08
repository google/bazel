// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;

import com.google.common.collect.Sets;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ObjectCodec} for {@link HashSet} that returns {@link LinkedHashSet} for determinism.
 *
 * <p>This type transformation is safe because {@link LinkedHashSet} is a subclass of {@link
 * HashSet}.
 */
@SuppressWarnings({"rawtypes", "unchecked", "NonApiType"})
final class HashSetCodec extends AsyncObjectCodec<HashSet> {
  @Override
  public Class<HashSet> getEncodedClass() {
    return HashSet.class;
  }

  @Override
  public void serialize(SerializationContext context, HashSet obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    codedOut.writeInt32NoTag(obj.size());
    for (Object object : obj) {
      context.serialize(object, codedOut);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public HashSet deserializeAsync(AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int size = codedIn.readInt32();
    LinkedHashSet set = Sets.newLinkedHashSetWithExpectedSize(size);
    context.registerInitialValue(set);

    if (size == 0) {
      return set;
    }

    ElementBuffer buffer = new ElementBuffer(set, size);
    for (int i = 0; i < size; i++) {
      context.deserialize(
          codedIn,
          buffer.elements,
          ARRAY_OBJECT_BASE_OFFSET + ARRAY_OBJECT_INDEX_SCALE * i,
          /* done= */ (Runnable) buffer);
    }

    return set;
  }

  /**
   * Buffers the elements and populates the set once all are available.
   *
   * <p>This approach is implicitly thread-safe.
   */
  private static class ElementBuffer implements Runnable {
    private final LinkedHashSet set;
    private final Object[] elements;

    private final AtomicInteger remaining;

    private ElementBuffer(LinkedHashSet set, int size) {
      this.set = set;
      this.elements = new Object[size];

      this.remaining = new AtomicInteger(size);
    }

    @Override
    public void run() {
      if (remaining.decrementAndGet() == 0) {
        Collections.addAll(set, elements);
      }
    }
  }
}
