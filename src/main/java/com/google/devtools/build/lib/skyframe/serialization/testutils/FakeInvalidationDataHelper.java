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
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_EMPTY;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/**
 * Helper for injecting fake file invalidation data.
 *
 * <p>{@link com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer}
 * inserts invalidation data the serialized bytes of top-level serialized values. Tests that don't
 * use the frontier serializer can use {@link #prependFakeInvalidationData} to inject a stubbed
 * version of this data.
 */
public final class FakeInvalidationDataHelper {

  /** Prepends the {@code value} bytes with fake invalidation data. */
  public static ByteString prependFakeInvalidationData(ByteString value) {
    // We expect the DATA_TYPE_EMPTY ordinal to occupy only 1 byte.
    ByteString.Output output = ByteString.newOutput(value.size() + 1);
    CodedOutputStream codedOutput = CodedOutputStream.newInstance(output);
    try {
      codedOutput.writeEnumNoTag(DATA_TYPE_EMPTY.getNumber());
      codedOutput.flush(); // Important to flush before writing the original ByteString
      value.writeTo(output);
    } catch (IOException e) {
      throw new AssertionError(e); // No failure expected here.
    }
    return output.toByteString();
  }

  private FakeInvalidationDataHelper() {}
}
