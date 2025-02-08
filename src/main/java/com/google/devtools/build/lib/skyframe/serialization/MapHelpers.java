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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.base.Preconditions.checkArgument;
import static sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET;
import static sun.misc.Unsafe.ARRAY_OBJECT_INDEX_SCALE;


import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Helpers for {@link Map} serialization. */
@SuppressWarnings("rawtypes")
final class MapHelpers {

  /**
   * Serializes the map's entries.
   *
   * <p>Note: this does not include the map's size.
   */
  static void serializeMapEntries(SerializationContext context, Map map, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    for (Object next : map.entrySet()) {
      Map.Entry entry = (Map.Entry) next;
      context.serialize(entry.getKey(), codedOut);
      try {
        context.serialize(entry.getValue(), codedOut);
      } catch (SerializationException | IOException e) {
        throw SerializationException.propagate(
            String.format(
                "Exception while serializing value of type %s for key '%s'",
                entry.getValue().getClass().getName(), entry.getKey()),
            e);
      }
    }
  }

  /**
   * Deserializes map entries into the given {@code keys} and {@code values}.
   *
   * <p>There's no direct indication of when the deserialization is complete so this should be used
   * with a {@link DeferredObjectCodec}.
   */
  // TODO: b/386384684 - remove Unsafe usage
  static void deserializeMapEntries(
      AsyncDeserializationContext context, CodedInputStream codedIn, Object[] keys, Object[] values)
      throws SerializationException, IOException {
    int size = keys.length;
    checkArgument(values.length == size, "%s %s", keys.length, values.length);
    long offset = ARRAY_OBJECT_BASE_OFFSET;
    for (int i = 0; i < size; i++) {
      // Ensures that keys are fully deserialized.
      context.deserialize(codedIn, keys, offset);
      try {
        context.deserialize(codedIn, values, offset);
      } catch (SerializationException | IOException e) {
        Object key = keys[i];
        if (key != null) {
          throw SerializationException.propagate(
              String.format("Exception while deserializing value for key '%s'", key), e);
        }
        throw e;
      }
      offset += ARRAY_OBJECT_INDEX_SCALE;
    }
  }

  /**
   * Populates the map entries with a {@code done} callback.
   *
   * <p>The {@code done} callback is called when all the entries have been deserialized. The {@code
   * keys} are fully deserialized when the {@code done} callback is called. The {@code values}
   * references will all be available but they might only be partially deserialized.
   */
  // TODO: b/386384684 - remove Unsafe usage
  static void deserializeMapEntries(
      AsyncDeserializationContext context,
      CodedInputStream codedIn,
      Object[] keys,
      Object[] values,
      Runnable done)
      throws SerializationException, IOException {
    int size = keys.length;
    checkArgument(values.length == size, "%s %s", keys.length, values.length);
    ReferenceCounter countDown = new ReferenceCounter(2 * size, done);
    long offset = ARRAY_OBJECT_BASE_OFFSET;
    for (int i = 0; i < size; i++) {
      context.deserialize(codedIn, keys, offset, /* done= */ countDown);
      try {
        context.deserialize(codedIn, values, offset, /* done= */ countDown);
      } catch (SerializationException | IOException e) {
        Object key = keys[i];
        if (key != null) {
          throw SerializationException.propagate(
              String.format("Exception while deserializing value for key '%s'", key), e);
        }
        throw e;
      }
      offset += ARRAY_OBJECT_INDEX_SCALE;
    }
  }

  private static class ReferenceCounter implements Runnable {
    private final AtomicInteger remaining;
    private final Runnable done;

    private ReferenceCounter(int size, Runnable done) {
      this.remaining = new AtomicInteger(size);
      this.done = done;
    }

    @Override
    public void run() {
      if (remaining.decrementAndGet() == 0) {
        done.run();
      }
    }
  }

  private MapHelpers() {}
}
