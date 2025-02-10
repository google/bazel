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
import java.util.Set;

class ExcludeProcessor implements JarProcessor {
  private final Set<String> excludes;
  private final boolean verbose;

  public ExcludeProcessor(Set<String> excludes, boolean verbose) {
    this.excludes = excludes;
    this.verbose = verbose;
  }

  @Override
  public boolean process(EntryStruct struct) throws IOException {
    boolean toKeep = !excludes.contains(struct.name);
    if (verbose && !toKeep) {
      System.err.println("Excluding " + struct.name);
    }
    return toKeep;
  }
}
