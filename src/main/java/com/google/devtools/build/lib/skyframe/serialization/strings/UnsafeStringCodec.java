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

package com.google.devtools.build.lib.skyframe.serialization.strings;

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/**
 * A high-performance {@link ObjectCodec} for {@link String} objects specialized for Strings in
 * JDK9+, where a String can be represented as a byte array together with a single byte (0 or 1) for
 * Latin-1 or UTF16 encoding.
 */
public final class UnsafeStringCodec extends LeafObjectCodec<String> {
  /**
   * An instance to use for delegation by other codecs.
   *
   * <p>The default constructor is left intact to allow the usual codec registration mechanisms to
   * work.
   */
  private static final UnsafeStringCodec INSTANCE = new UnsafeStringCodec();

  private static final StringUnsafe STRING_UNSAFE = StringUnsafe.getInstance();

  public static UnsafeStringCodec stringCodec() {
    return INSTANCE;
  }

  @Override
  public Class<String> getEncodedClass() {
    return String.class;
  }

  @Override
  public void serialize(LeafSerializationContext context, String obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    byte coder = STRING_UNSAFE.getCoder(obj);
    byte[] value = STRING_UNSAFE.getByteArray(obj);
    // Optimize for the case that coder == 0, in which case we can just write the length here,
    // potentially using just one byte. If coder != 0, we'll use 4 bytes, but that's vanishingly
    // rare.
    if (coder == 0) {
      codedOut.writeInt32NoTag(value.length);
    } else if (coder == 1) {
      codedOut.writeInt32NoTag(-value.length);
    } else {
      throw new SerializationException("Unexpected coder value: " + coder + " for " + obj);
    }
    codedOut.writeRawBytes(value);
  }

  @Override
  public String deserialize(LeafDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int length = codedIn.readInt32();
    byte coder;
    if (length >= 0) {
      coder = 0;
    } else {
      coder = 1;
      length = -length;
    }
    byte[] value = codedIn.readRawBytes(length);
    return STRING_UNSAFE.newInstance(value, coder);
  }
}
