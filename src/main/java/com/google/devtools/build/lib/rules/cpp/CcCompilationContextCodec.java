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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.rules.cpp.HeaderInfoCodec.headerInfoCodec;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.getFieldOffset;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext.HeaderInfo;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter;
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec.FieldHandler;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

final class CcCompilationContextCodec extends AsyncObjectCodec<CcCompilationContext> {

  private final DynamicCodec delegate;

  private CcCompilationContextCodec() {
    try {
      // Overrides with custom `headerInfo` handler.
      this.delegate =
          DynamicCodec.createWithOverrides(
              CcCompilationContext.class,
              ImmutableMap.of(
                  CcCompilationContext.class.getDeclaredField("headerInfo"),
                  new RemoteHeaderInfoHandler(
                      getFieldOffset(CcCompilationContext.class, "headerInfo"))));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Class<CcCompilationContext> getEncodedClass() {
    return CcCompilationContext.class;
  }

  @Override
  public void serialize(
      SerializationContext context, CcCompilationContext obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    delegate.serialize(context, obj, codedOut);
  }

  @Override
  public CcCompilationContext deserializeAsync(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    return (CcCompilationContext) delegate.deserializeAsync(context, codedIn);
  }

  private static class RemoteHeaderInfoHandler
      implements FieldHandler, FieldSetter<CcCompilationContext> {
    private final long headerInfoOffset;

    private RemoteHeaderInfoHandler(long headerInfoOffset) {
      this.headerInfoOffset = headerInfoOffset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws SerializationException, IOException {
      context.putSharedValue(
          (HeaderInfo) unsafe().getObject(obj, headerInfoOffset),
          /* distinguisher= */ null,
          headerInfoCodec(),
          codedOut);
    }

    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws SerializationException, IOException {
      context.getSharedValue(
          codedIn,
          /* distinguisher= */ null,
          headerInfoCodec(),
          (CcCompilationContext) obj,
          (FieldSetter<CcCompilationContext>) this);
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void set(CcCompilationContext context, Object obj) {
      unsafe().putObject(context, headerInfoOffset, (HeaderInfo) obj);
    }
  }
}
