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
package com.google.devtools.build.lib.runtime.commands.info;

import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.io.OutErr;
import java.io.IOException;

/** Prints {@link InfoItem}s to the console. */
public class StdoutInfoItemHandler implements InfoItemHandler {
  private final OutErr outErr;

  public StdoutInfoItemHandler(OutErr outErr) {
    this.outErr = outErr;
  }

  @Override
  public void addInfoItem(String key, byte[] value, boolean printKeys) throws IOException {
    if (printKeys) {
      outErr.getOutputStream().write(StringUnsafe.getInternalStringBytes(key));
      outErr.getOutputStream().write(':');
      outErr.getOutputStream().write(' ');
    }
    outErr.getOutputStream().write(value);
  }

  @Override
  public void close() throws IOException {
    outErr.getOutputStream().flush();
  }
}
