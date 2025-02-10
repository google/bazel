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

import com.google.common.collect.ImmutableList;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/**
 * An {@link AsyncObjectCodec} for {@link ImmutableList}.
 *
 * <p>This codec is necessary because {@link ImmutableList}:
 *
 * <ul>
 *   <li>has a number of hidden subclasses; and
 *   <li>marks important fields transient.
 * </ul>
 */
@SuppressWarnings("rawtypes") // Intentional erasure of ImmutableList.
class ImmutableListCodec extends AsyncObjectCodec<ImmutableList> {
  private static final Class<? extends ImmutableList> SINGLETON_IMMUTABLE_LIST_CLASS =
      ImmutableList.<Integer>of(0).getClass();
  private static final Class<? extends ImmutableList> REGULAR_IMMUTABLE_LIST_CLASS =
      ImmutableList.<Integer>of(0, 1).getClass();

  private static final long ELEMENT_OFFSET;
  private static final long ARRAY_OFFSET;

  static {
    try {
      ELEMENT_OFFSET = getFieldOffset(SINGLETON_IMMUTABLE_LIST_CLASS, "element");
      ARRAY_OFFSET = getFieldOffset(REGULAR_IMMUTABLE_LIST_CLASS, "array");
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Class<ImmutableList> getEncodedClass() {
    return ImmutableList.class;
  }

  @Override
  public void serialize(
      SerializationContext context, ImmutableList object, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    codedOut.writeInt32NoTag(object.size());
    for (Object obj : object) {
      context.serialize(obj, codedOut);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public ImmutableList deserializeAsync(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws IOException, SerializationException {
    int size = codedIn.readInt32();
    if (size == 0) {
      return ImmutableList.of();
    }

    ImmutableList list;
    if (size == 1) {
      try {
        list = (ImmutableList) unsafe().allocateInstance(SINGLETON_IMMUTABLE_LIST_CLASS);
      } catch (InstantiationException e) {
        throw new SerializationException(
            "could not instantiate " + SINGLETON_IMMUTABLE_LIST_CLASS, e);
      }
      context.registerInitialValue(list);

      context.deserialize(codedIn, list, ELEMENT_OFFSET);
      return list;
    }

    try {
      list = (ImmutableList) unsafe().allocateInstance(REGULAR_IMMUTABLE_LIST_CLASS);
    } catch (InstantiationException e) {
      throw new SerializationException("could not instantiate " + REGULAR_IMMUTABLE_LIST_CLASS, e);
    }
    context.registerInitialValue(list);

    Object[] elements = new Object[size];
    deserializeObjectArray(context, codedIn, elements, size);

    unsafe().putObject(list, ARRAY_OFFSET, elements);

    return list;
  }
}
