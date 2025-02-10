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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

abstract class StringReader extends ClassVisitor {
  private int line = -1;
  private String className;

  public StringReader() {
    super(Opcodes.ASM9);
  }

  public abstract void visitString(String className, String value, int line);

  private void handleObject(Object value) {
    if (value instanceof String) {
      visitString(className, (String) value, line);
    }
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
    line = -1;
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    handleObject(value);
    return new FieldVisitor(Opcodes.ASM9) {
      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return StringReader.this.visitAnnotation(desc, visible);
      }
    };
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    return new AnnotationVisitor(Opcodes.ASM9) {
      @Override
      public void visit(String name, Object value) {
        handleObject(value);
      }

      @Override
      public void visitEnum(String name, String desc, String value) {
        handleObject(value);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String name, String desc) {
        return this;
      }
    };
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    return new MethodVisitor(Opcodes.ASM9) {
      @Override
      public void visitLdcInsn(Object cst) {
        handleObject(cst);
      }

      @Override
      public void visitLineNumber(int line, Label start) {
        StringReader.this.line = line;
      }

      @Override
      public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        for (Object bsmArg : bsmArgs) {
          handleObject(bsmArg);
        }
      }

      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return StringReader.this.visitAnnotation(desc, visible);
      }

      @Override
      public AnnotationVisitor visitParameterAnnotation(
          int parameter, String desc, boolean visible) {
        return StringReader.this.visitAnnotation(desc, visible);
      }
    };
  }
}
