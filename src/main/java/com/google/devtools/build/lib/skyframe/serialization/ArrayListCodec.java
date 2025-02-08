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

import static com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor.deserializeObjectArray;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.getFieldOffset;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;


import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Codec for {@link ArrayList}.
 *
 * <p>This is needed because {@link ArrayList} marks its {@code elementData} field transient and
 * even if it weren't, it uses an array slightly larger than its size.
 */
@SuppressWarnings({"rawtypes", "NonApiType"})
class ArrayListCodec extends AsyncObjectCodec<ArrayList> {
  private static final long ELEMENT_DATA_OFFSET;
  private static final long SIZE_OFFSET;

  @Override
  public Class<ArrayList> getEncodedClass() {
    return ArrayList.class;
  }

  @Override
  public void serialize(SerializationContext context, ArrayList list, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    codedOut.writeInt32NoTag(list.size());
    for (Object item : list) {
      context.serialize(item, codedOut);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public ArrayList deserializeAsync(AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int length = codedIn.readInt32();
    if (length < 0) {
      throw new SerializationException("Expected non-negative length: " + length);
    }

    if (length == 0) {
      ArrayList empty = new ArrayList(/* initialCapacity= */ 0);
      context.registerInitialValue(empty);
      return empty;
    }

    ArrayList list;
    try {
      list = (ArrayList) unsafe().allocateInstance(ArrayList.class);
    } catch (InstantiationException e) {
      throw new SerializationException("could not instantiate ArrayList", e);
    }
    context.registerInitialValue(list);

    // Sets the elementData directly, then reflectively inserts it into the ArrayList. ArrayList's
    // public API doesn't provide an efficient way to populate elementData by offset.
    Object[] elementData = new Object[length];
    deserializeObjectArray(context, codedIn, elementData, length);

    unsafe().putObject(list, ELEMENT_DATA_OFFSET, elementData);
    unsafe().putInt(list, SIZE_OFFSET, length);

    return list;
  }

  static {
    try {
      ELEMENT_DATA_OFFSET = getFieldOffset(ArrayList.class, "elementData");
      SIZE_OFFSET = getFieldOffset(ArrayList.class, "size");
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
