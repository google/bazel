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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table.Cell;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/** Codec for {@link ImmutableTable}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ImmutableTableCodec extends DeferredObjectCodec<ImmutableTable> {

  @Override
  public Class<ImmutableTable> getEncodedClass() {
    return ImmutableTable.class;
  }

  @Override
  public void serialize(
      SerializationContext context, ImmutableTable object, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    ImmutableSet<Cell> cellSet = object.cellSet();
    codedOut.writeInt32NoTag(cellSet.size());
    for (Cell cell : cellSet) {
      context.serialize(cell.getRowKey(), codedOut);
      context.serialize(cell.getColumnKey(), codedOut);
      context.serialize(cell.getValue(), codedOut);
    }
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  public DeferredValue<ImmutableTable> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int size = codedIn.readInt32();
    if (size < 0) {
      throw new SerializationException("Expected non-negative size: " + size);
    }
    if (size == 0) {
      return ImmutableTable::of;
    }

    EntryBuffer buffer = new EntryBuffer(size);
    long offset = ARRAY_OBJECT_BASE_OFFSET;
    for (int i = 0; i < size; i++) {
      context.deserialize(codedIn, buffer.rowKeys, offset);
      context.deserialize(codedIn, buffer.columnKeys, offset);
      context.deserialize(codedIn, buffer.values, offset);
      offset += ARRAY_OBJECT_INDEX_SCALE;
    }
    return buffer;
  }

  private static final class EntryBuffer implements DeferredValue<ImmutableTable> {
    private final Object[] rowKeys;
    private final Object[] columnKeys;
    private final Object[] values;

    private EntryBuffer(int size) {
      this.rowKeys = new Object[size];
      this.columnKeys = new Object[size];
      this.values = new Object[size];
    }

    @Override
    public ImmutableTable call() {
      ImmutableTable.Builder builder = ImmutableTable.builder();
      for (int i = 0; i < size(); i++) {
        builder.put(
            /* rowKey= */ rowKeys[i], /* columnKey= */ columnKeys[i], /* value= */ values[i]);
      }
      return builder.buildOrThrow();
    }

    private int size() {
      return rowKeys.length;
    }
  }
}
