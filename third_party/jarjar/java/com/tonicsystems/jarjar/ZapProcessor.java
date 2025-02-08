/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar;

import com.tonicsystems.jarjar.util.EntryStruct;
import com.tonicsystems.jarjar.util.JarProcessor;
import java.io.IOException;
import java.util.List;

class ZapProcessor implements JarProcessor {
  private final List<Wildcard> wildcards;

  public ZapProcessor(List<Zap> zapList) {
    wildcards = PatternElement.createWildcards(zapList);
  }

  @Override
  public boolean process(EntryStruct struct) throws IOException {
    String name = struct.name;
    if (struct.isClass()) {
      return !zap(name.substring(0, name.length() - 6));
    }
    return true;
  }

  private boolean zap(String desc) {
    // TODO: optimize
    for (Wildcard wildcard : wildcards) {
      if (wildcard.matches(desc)) {
        return true;
      }
    }
    return false;
  }
}
