// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/** Converts an existing {@link DeferredObjectCodec} into a shared value codec. */
public final class ValueSharingAdapter<T> extends DeferredObjectCodec<T> {
  private final DeferredObjectCodec<T> baseCodec;

  public ValueSharingAdapter(DeferredObjectCodec<T> baseCodec) {
    this.baseCodec = baseCodec;
  }

  @Override
  public boolean autoRegister() {
    return false;
  }

  @Override
  public Class<? extends T> getEncodedClass() {
    return baseCodec.getEncodedClass();
  }

  @Override
  public void serialize(SerializationContext context, T obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    context.putSharedValue(obj, /* distinguisher= */ null, baseCodec, codedOut);
  }

  @Override
  public DeferredValue<T> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    var builder = SimpleDeferredValue.<T>create();
    context.getSharedValue(
        codedIn, /* distinguisher= */ null, baseCodec, builder, SimpleDeferredValue::set);
    return builder;
  }
}
