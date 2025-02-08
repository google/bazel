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

import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public abstract class JarTransformer implements JarProcessor {
  @Override
  public boolean process(EntryStruct struct) throws IOException {
    if (struct.isClass()) {
      ClassReader reader;
      try {
        reader = new ClassReader(struct.data);
      } catch (RuntimeException e) {
        return true; // TODO?
      }
      GetNameClassWriter w = new GetNameClassWriter(ClassWriter.COMPUTE_MAXS);
      ClassVisitor visitor = transform(w);
      reader.accept(visitor, ClassReader.EXPAND_FRAMES);

      boolean updateData = true;
      if (visitor instanceof RemappingClassTransformer) {
        updateData = ((RemappingClassTransformer) visitor).didRemap();
      }
      if (updateData) {
        struct.data = w.toByteArray();
        struct.name = pathFromName(w.getClassName());
      }
    }
    return true;
  }

  protected abstract ClassVisitor transform(ClassVisitor v);

  private static String pathFromName(String className) {
    return className.replace('.', '/') + ".class";
  }
}
