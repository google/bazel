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

import static com.google.devtools.build.lib.skyframe.serialization.ClassCodec.classCodec;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;
import static sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;


import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serialize {@link EnumMap}. Subclasses of {@link EnumMap} will crash at runtime because currently
 * there are no "benign" subclasses of {@link EnumMap} in the Bazel codebase that can be used where
 * an {@link EnumMap} was expected.
 */
// TODO: b/386384684 - remove Unsafe usage
@SuppressWarnings({"rawtypes", "unchecked"})
class EnumMapCodec extends AsyncObjectCodec<EnumMap> {
  /** Used to retrieve the hidden {@link EnumMap#keyType} field. */
  private static final long KEY_TYPE_OFFSET;

  static {
    try {
      KEY_TYPE_OFFSET = unsafe().objectFieldOffset(EnumMap.class.getDeclaredField("keyType"));
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Class<EnumMap> getEncodedClass() {
    return EnumMap.class;
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public void serialize(SerializationContext context, EnumMap obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    if (!obj.getClass().equals(EnumMap.class)) {
      throw new SerializationException(
          "Cannot serialize subclasses of EnumMap: " + obj.getClass() + " (" + obj + ")");
    }
    classCodec()
        .serialize(context, ((Class<?>) unsafe().getObject(obj, KEY_TYPE_OFFSET)), codedOut);

    codedOut.writeInt32NoTag(obj.size());
    if (obj.isEmpty()) {
      return;
    }

    for (Object next : obj.entrySet()) {
      Map.Entry entry = (Map.Entry) next;
      codedOut.writeInt32NoTag(((Enum) entry.getKey()).ordinal());
      context.serialize(entry.getValue(), codedOut);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public EnumMap deserializeAsync(AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    Class clazz = classCodec().deserialize(context, codedIn);
    int size = codedIn.readInt32();
    EnumMap result = new EnumMap(clazz);
    context.registerInitialValue(result);

    MapBuffer buffer = new MapBuffer(result, size);

    Enum[] enums = (Enum[]) clazz.getEnumConstants();
    for (int i = 0; i < size; i++) {
      int ordinal = codedIn.readInt32();
      buffer.setEnum(i, enums[ordinal]);
      context.deserialize(
          codedIn,
          buffer.values,
          ARRAY_OBJECT_BASE_OFFSET + ARRAY_OBJECT_INDEX_SCALE * i,
          /* done= */ (Runnable) buffer);
    }
    return result;
  }

  /** Buffers the entry elements and populates the map once all values are done. */
  private static class MapBuffer implements Runnable {
    private final EnumMap result;
    private final Enum[] enums;
    private final Object[] values;

    private final AtomicInteger remaining;

    private MapBuffer(EnumMap result, int size) {
      this.result = result;
      this.enums = new Enum[size];
      this.values = new Object[size];
      this.remaining = new AtomicInteger(size);
    }

    @Override
    public void run() {
      if (remaining.decrementAndGet() == 0) {
        for (int i = 0; i < enums.length; i++) {
          result.put(enums[i], values[i]);
        }
      }
    }

    private void setEnum(int index, Enum enumKey) {
      enums[index] = enumKey;
    }
  }
}
