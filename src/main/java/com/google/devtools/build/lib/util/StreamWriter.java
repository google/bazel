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
package com.google.devtools.build.lib.util;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A {@link StreamWriter} writes to an {@link OutputStream} deterministically.
 */
public interface StreamWriter {
  /**
   * Writes the fake file to an OutputStream. MUST be deterministic, in that multiple calls to write
   * the same StreamWriter must write identical bytes.
   *
   * @throws IOException only if out throws an IOException
   */
  void writeTo(OutputStream out) throws IOException;
}
