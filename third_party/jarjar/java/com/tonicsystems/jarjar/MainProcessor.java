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
import com.tonicsystems.jarjar.util.JarProcessorChain;
import com.tonicsystems.jarjar.util.JarTransformerChain;
import com.tonicsystems.jarjar.util.RemappingClassTransformer;
import com.tonicsystems.jarjar.util.StandaloneJarProcessor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MainProcessor implements JarProcessor {
  private final boolean verbose;
  private final JarProcessorChain chain;
  private final KeepProcessor kp;
  private final Map<String, String> renames = new HashMap<>();

  public MainProcessor(List<PatternElement> patterns, boolean verbose, boolean skipManifest) {
    this.verbose = verbose;
    List<Zap> zapList = new ArrayList<>();
    List<Rule> ruleList = new ArrayList<>();
    List<Keep> keepList = new ArrayList<>();
    for (PatternElement pattern : patterns) {
      if (pattern instanceof Zap) {
        zapList.add((Zap) pattern);
      } else if (pattern instanceof Rule) {
        ruleList.add((Rule) pattern);
      } else if (pattern instanceof Keep) {
        keepList.add((Keep) pattern);
      }
    }

    PackageRemapper pr = new PackageRemapper(ruleList, verbose);
    kp = keepList.isEmpty() ? null : new KeepProcessor(keepList);

    List<JarProcessor> processors = new ArrayList<>();
    processors.add(new ManifestProcessor(pr, skipManifest));
    if (kp != null) {
      processors.add(kp);
    }
    processors.add(new ZapProcessor(zapList));
    processors.add(
        new JarTransformerChain(
            new RemappingClassTransformer[] {new RemappingClassTransformer(pr)}));
    processors.add(new ResourceProcessor(pr));
    processors.add(new ServiceProcessor(pr));
    chain = new JarProcessorChain(processors.toArray(new JarProcessor[0]));
  }

  public void strip(File file) throws IOException {
    if (kp == null) {
      return;
    }
    Set<String> excludes = getExcludes();
    if (!excludes.isEmpty()) {
      StandaloneJarProcessor.run(file, file, new ExcludeProcessor(excludes, verbose));
    }
  }

  /**
   * Returns the <code>.class</code> files to delete. As well the root-parameter as the rename ones
   * are taken in consideration, so that the concerned files are not listed in the result.
   *
   * @return the paths of the files in the jar-archive, including the <code>.class</code> suffix
   */
  private Set<String> getExcludes() {
    Set<String> result = new HashSet<>();
    for (String exclude : kp.getExcludes()) {
      String name = exclude + ".class";
      String renamed = renames.get(name);
      result.add((renamed != null) ? renamed : name);
    }
    return result;
  }

  /**
   * @param struct
   * @return <code>true</code> if the entry is to include in the output jar
   * @throws IOException
   */
  @Override
  public boolean process(EntryStruct struct) throws IOException {
    String name = struct.name;
    boolean keepIt = chain.process(struct);
    if (keepIt) {
      if (!name.equals(struct.name)) {
        if (kp != null) {
          renames.put(name, struct.name);
        }
        if (verbose) {
          System.err.println("Renamed " + name + " -> " + struct.name);
        }
      }
    } else {
      if (verbose) {
        System.err.println("Removed " + name);
      }
    }
    return keepIt;
  }
}
