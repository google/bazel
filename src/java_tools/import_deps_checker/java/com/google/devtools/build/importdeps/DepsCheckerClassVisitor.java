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
package com.google.devtools.build.importdeps;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.importdeps.ClassInfo.MemberInfo;
import org.objectweb.asm.Opcodes;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import javax.annotation.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/** Checker to check whether a class has missing dependencies on its classpath. */
public class DepsCheckerClassVisitor extends ClassVisitor {

  private String internalName;
  private final ClassCache classCache;
  private final ResultCollector resultCollector;

  private final DepsCheckerAnnotationVisitor defaultAnnotationChecker =
      new DepsCheckerAnnotationVisitor();
  private final DepsCheckerFieldVisitor defaultFieldChecker = new DepsCheckerFieldVisitor();
  private final DepsCheckerMethodVisitor defaultMethodChecker = new DepsCheckerMethodVisitor();

  public DepsCheckerClassVisitor(ClassCache classCache, ResultCollector resultCollector) {
    super(Opcodes.ASM9);
    this.classCache = classCache;
    this.resultCollector = resultCollector;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    checkState(internalName == null, "Cannot reuse this class visitor %s", getClass());
    this.internalName = name;
    if (superName != null) {
      // module-info and java.lang.Object have null superName
      checkInternalName(superName);
    }
    checkInternalNameArray(interfaces);
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    checkDescriptor(desc);
    return defaultAnnotationChecker;
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String desc, String signature, Object value) {
    checkDescriptor(desc);
    return defaultFieldChecker;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    checkInternalNameArray(exceptions);
    checkDescriptor(desc);
    return defaultMethodChecker;
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      int typeRef, TypePath typePath, String desc, boolean visible) {
    checkDescriptor(desc);
    return defaultAnnotationChecker;
  }

  private void checkMember(String owner, String name, String desc) {
    try {
      if (checkInternalNameOrArrayDescriptor(owner)) {
        // The owner is an array descriptor.
        return; // Assume all methods of arrays exist by default.
      }
      checkDescriptor(desc);

      if (!resultCollector.getCheckMissingMembers()) {
        return;  // No point in doing the expensive stuff below
      }

      // TODO(kmb): Consider removing this entirely so we don't have to track members at all
      AbstractClassEntryState state = checkInternalName(owner);
      Optional<ClassInfo> classInfo = state.classInfo();
      if (!classInfo.isPresent()) {
        checkState(state.isMissingState(), "The state should be MissingState. %s", state);
        return; // The class is already missing.
      }
      MemberInfo member = MemberInfo.create(name, desc);
      if (!classInfo.get().containsMember(member)) {
        resultCollector.addMissingMember(classInfo.get(), member);
      }
    } catch (RuntimeException e) {
      System.err.printf(
          "A runtime exception occurred when checking the member: owner=%s, name=%s, desc=%s\n",
          owner, name, desc);
      throw e;
    }
  }

  private void checkDescriptor(String desc) {
    checkType(Type.getType(desc));
  }

  private void checkType(Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
      case Type.BYTE:
      case Type.CHAR:
      case Type.SHORT:
      case Type.INT:
      case Type.LONG:
      case Type.FLOAT:
      case Type.DOUBLE:
      case Type.VOID:
        return; // Ignore primitive types.
      case Type.ARRAY:
        checkType(type.getElementType());
        return;
      case Type.METHOD:
        for (Type argumentType : type.getArgumentTypes()) {
          checkType(argumentType);
        }
        checkType(type.getReturnType());
        return;
      case Type.OBJECT:
        checkInternalName(type.getInternalName());
        return;
      default:
        throw new UnsupportedOperationException("Unhandled type: " + type);
    }
  }

  /**
   * Checks the type, and returns {@literal true} if the type is an array descriptor, otherwise
   * {@literal false}
   */
  private boolean checkInternalNameOrArrayDescriptor(String type) {
    if (type.charAt(0) == '[') {
      checkDescriptor(type);
      return true;
    } else {
      checkInternalName(type);
      return false;
    }
  }

  private AbstractClassEntryState checkInternalName(String internalName) {
    checkArgument(
        internalName.length() > 0 && Character.isJavaIdentifierStart(internalName.charAt(0)),
        "The internal name is invalid. %s",
        internalName);
    AbstractClassEntryState state = classCache.getClassState(internalName);
    if (state.isMissingState()) {
      resultCollector.addMissingOrIncompleteClass(internalName, state);
    } else {
      if (state.isIncompleteState()) {
        state
            .asIncompleteState()
            .missingAncestors()
            .forEach(
                missingAncestor -> {
                  AbstractClassEntryState ancestorState = classCache.getClassState(missingAncestor);
                  checkState(
                      ancestorState.isMissingState(),
                      "The ancestor should be missing. %s",
                      ancestorState);
                  resultCollector.addMissingOrIncompleteClass(missingAncestor, ancestorState);
                  resultCollector.addMissingOrIncompleteClass(internalName, state);
                });
      }
      ClassInfo info = state.classInfo().get();
      if (!info.directDep()) {
        resultCollector.addIndirectDep(internalName, info.jarPath());
      }
    }
    return state;
  }

  private void checkInternalNameArray(@Nullable String[] internalNames) {
    if (internalNames == null) {
      return;
    }
    for (String internalName : internalNames) {
      checkInternalName(internalName);
    }
  }

  private static final ImmutableSet<Class<?>> PRIMITIVE_TYPES =
      ImmutableSet.of(
          Boolean.class,
          Byte.class,
          Short.class,
          Character.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          String.class);

  /** Annotation checker to check for missing classes in the annotation body. */
  private class DepsCheckerAnnotationVisitor extends AnnotationVisitor {

    DepsCheckerAnnotationVisitor() {
      super(Opcodes.ASM9);
    }

    @CanIgnoreReturnValue
    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      checkDescriptor(desc);
      return this; // Recursively reuse this annotation visitor.
    }

    @Override
    public void visit(String name, Object value) {
      if (value instanceof Type) {
        checkType(((Type) value)); // Class literals.
        return;
      }
      Class<?> clazz = value.getClass();
      if (PRIMITIVE_TYPES.contains(clazz)) {
        return;
      }
      checkState(
          clazz.isArray() && clazz.getComponentType().isPrimitive(),
          "Unexpected value %s of type %s",
          value,
          clazz);
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return this; // Recursively reuse this annotation visitor.
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      checkMember(Type.getType(desc).getInternalName(), value, desc);
    }
  }

  /** Field checker to check for missing classes in the field declaration. */
  private class DepsCheckerFieldVisitor extends FieldVisitor {

    DepsCheckerFieldVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }
  }

  /** Method visitor to check whether there are missing classes in the method body. */
  private class DepsCheckerMethodVisitor extends MethodVisitor {

    DepsCheckerMethodVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
      if ("Ljava/lang/Synthetic;".equals(desc)) {
        return null; // ASM sometimes makes up this annotation, so we can ignore it (b/78024300)
      }
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }

    @Override
    public void visitLocalVariable(
        String name, String desc, String signature, Label start, Label end, int index) {
      checkDescriptor(desc);
      super.visitLocalVariable(name, desc, signature, start, end, index);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      checkInternalNameOrArrayDescriptor(type);
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      checkMember(owner, name, desc);
      super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
      if (!isMethodHandle(opcode, owner, name) && !isVarHandle(opcode, owner, name)) {
        checkMember(owner, name, desc);
      }
      super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    private boolean isMethodHandle(int opcode, String owner, String name) {
      if (opcode != Opcodes.INVOKEVIRTUAL) {
        return false;
      }
      if (!owner.equals("java/lang/invoke/MethodHandle")) {
        return false;
      }
      switch (name) {
        case "invoke":
        case "invokeExact":
          break;
        default:
          return false;
      }
      return true;
    }

    private boolean isVarHandle(int opcode, String owner, String name) {
      if (opcode != Opcodes.INVOKEVIRTUAL) {
        return false;
      }
      if (!owner.equals("java/lang/invoke/VarHandle")) {
        return false;
      }
      switch (name) {
        case "compareAndExchange":
        case "compareAndExchangeAcquire":
        case "compareAndExchangeRelease":
        case "compareAndSet":
        case "get":
        case "getAcquire":
        case "getAndAdd":
        case "getAndAddAcquire":
        case "getAndAddRelease":
        case "getAndBitwiseAnd":
        case "getAndBitwiseAndAcquire":
        case "getAndBitwiseAndRelease":
        case "getAndBitwiseOr":
        case "getAndBitwiseOrAcquire":
        case "getAndBitwiseOrRelease":
        case "getAndBitwiseXor":
        case "getAndBitwiseXorAcquire":
        case "getAndBitwiseXorRelease":
        case "getAndSet":
        case "getAndSetAcquire":
        case "getAndSetRelease":
        case "getOpaque":
        case "getVolatile":
        case "set":
        case "setOpaque":
        case "setRelease":
        case "setVolatile":
        case "weakCompareAndSet":
        case "weakCompareAndSetAcquire":
        case "weakCompareAndSetPlain":
        case "weakCompareAndSetRelease":
          break;
        default:
          return false;
      }
      return true;
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
      checkDescriptor(desc);
      checkHandle(bsm);
      for (Object bsmArg : bsmArgs) {
        if (bsmArg instanceof Type) {
          checkType(((Type) bsmArg)); // Class literals.
          continue;
        }
        if (PRIMITIVE_TYPES.contains(bsmArg.getClass())) {
          checkType(Type.getType(bsmArg.getClass()));
          continue;
        }
        if (bsmArg instanceof Handle) {
          checkHandle((Handle) bsmArg);
          continue;
        }
        throw new UnsupportedOperationException("Unsupported bsmarg type: " + bsmArg);
      }
      super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
    }

    private void checkHandle(Handle handle) {
      checkMember(handle.getOwner(), handle.getName(), handle.getDesc());
    }

    @Override
    public void visitLdcInsn(Object value) {
      if (value instanceof Type) {
        checkType((Type) value); // Class literals
      } else if (value instanceof Handle) {
        checkHandle((Handle) value);
      } else {
        checkState(PRIMITIVE_TYPES.contains(value.getClass()));
      }
      super.visitLdcInsn(value);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
      checkDescriptor(desc);
      super.visitMultiANewArrayInsn(desc, dims);
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(
        int typeRef, TypePath typePath, String desc, boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(
        int typeRef,
        TypePath typePath,
        Label[] start,
        Label[] end,
        int[] index,
        String desc,
        boolean visible) {
      checkDescriptor(desc);
      return defaultAnnotationChecker;
    }
  }
}
