// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A deterministic writer writes bytes to an output stream. The same byte stream is written on every
 * invocation of writeOutputFile().
 */
public interface DeterministicWriter {
  /**
   * @throws IOException only if out throws an IOException
   */
  void writeOutputFile(OutputStream out) throws IOException;

  /**
   * Returns the contents that would be written, as a {@link ByteString}. Used when the caller wants
   * a {@link ByteString} in the end, to avoid making unnecessary copies.
   */
  default ByteString getBytes() throws IOException {
    ByteString.Output out = ByteString.newOutput();
    writeOutputFile(out);
    return out.toByteString();
  }
}
