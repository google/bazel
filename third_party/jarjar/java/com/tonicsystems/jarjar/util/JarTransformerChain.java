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

package com.tonicsystems.jarjar.util;

import org.objectweb.asm.ClassVisitor;

public class JarTransformerChain extends JarTransformer {
  private final RemappingClassTransformer[] chain;

  public JarTransformerChain(RemappingClassTransformer[] chain) {
    this.chain = chain.clone();
    for (int i = chain.length - 1; i > 0; i--) {
      chain[i - 1].setTarget(chain[i]);
    }
  }

  @Override
  protected ClassVisitor transform(ClassVisitor v) {
    chain[chain.length - 1].setTarget(v);
    return chain[0];
  }
}
