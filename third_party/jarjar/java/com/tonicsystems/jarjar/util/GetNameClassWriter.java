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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class GetNameClassWriter extends ClassVisitor {
  private String className;

  public GetNameClassWriter(int flags) {
    super(Opcodes.ASM9, new ClassWriter(flags));
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    className = name;
    super.visit(version, access, name, signature, superName, interfaces);
  }

  public String getClassName() {
    return className;
  }

  public byte[] toByteArray() {
    return ((ClassWriter) cv).toByteArray();
  }
}
