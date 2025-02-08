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

package net.starlark.java.annot.processor;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.annotations.FormatMethod;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/**
 * Annotation processor for {@link StarlarkMethod}. See that class for requirements.
 *
 * <p>These properties can be relied upon at runtime without additional checks.
 */
@SupportedAnnotationTypes({
  "net.starlark.java.annot.StarlarkMethod",
  "net.starlark.java.annot.StarlarkBuiltin"
})
public class StarlarkMethodProcessor extends AbstractProcessor {

  private Types types;
  private Elements elements;
  private Messager messager;

  // A set containing a TypeElement for each class with a StarlarkMethod.selfCall annotation.
  private Set<Element> classesWithSelfcall;
  // A multimap where keys are class element, and values are the callable method names identified in
  // that class (where "method name" is StarlarkMethod.name).
  private SetMultimap<Element, String> processedClassMethods;

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    this.types = env.getTypeUtils();
    this.elements = env.getElementUtils();
    this.messager = env.getMessager();
    this.classesWithSelfcall = new HashSet<>();
    this.processedClassMethods = LinkedHashMultimap.create();
  }

  private TypeMirror getType(String canonicalName) {
    return elements.getTypeElement(canonicalName).asType();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    TypeMirror stringType = getType("java.lang.String");
    TypeMirror integerType = getType("java.lang.Integer");
    TypeMirror booleanType = getType("java.lang.Boolean");
    TypeMirror listType = getType("java.util.List");
    TypeMirror mapType = getType("java.util.Map");
    TypeMirror starlarkValueType = getType("net.starlark.java.eval.StarlarkValue");

    // Ensure StarlarkBuiltin-annotated classes implement StarlarkValue.
    for (Element cls : roundEnv.getElementsAnnotatedWith(StarlarkBuiltin.class)) {
      if (!types.isAssignable(cls.asType(), starlarkValueType)) {
        errorf(
            cls,
            "class %s has StarlarkBuiltin annotation but does not implement StarlarkValue",
            cls.getSimpleName());
      }
    }

    for (Element element : roundEnv.getElementsAnnotatedWith(StarlarkMethod.class)) {
      // Only methods are annotated with StarlarkMethod.
      // This is ensured by the @Target(ElementType.METHOD) annotation.
      ExecutableElement method = (ExecutableElement) element;
      if (!method.getModifiers().contains(Modifier.PUBLIC)) {
        errorf(method, "StarlarkMethod-annotated methods must be public.");
      }
      if (method.getModifiers().contains(Modifier.STATIC)) {
        errorf(method, "StarlarkMethod-annotated methods cannot be static.");
      }

      // Check the annotation itself.
      StarlarkMethod annot = method.getAnnotation(StarlarkMethod.class);
      if (annot.name().isEmpty()) {
        errorf(method, "StarlarkMethod.name must be non-empty.");
      }
      Element cls = method.getEnclosingElement();
      if (!processedClassMethods.put(cls, annot.name())) {
        errorf(method, "Containing class defines more than one method named '%s'.", annot.name());
      }
      if (annot.documented() && annot.doc().isEmpty()) {
        errorf(method, "The 'doc' string must be non-empty if 'documented' is true.");
      }
      if (annot.structField()) {
        checkStructFieldAnnotation(method, annot);
      } else if (annot.useStarlarkSemantics()) {
        errorf(
            method,
            "a StarlarkMethod-annotated method with structField=false may not also specify"
                + " useStarlarkSemantics. (Instead, set useStarlarkThread and call"
                + " getSemantics().)");
      }
      if (annot.selfCall() && !classesWithSelfcall.add(cls)) {
        errorf(method, "Containing class has more than one selfCall method defined.");
      }

      boolean hasFlag = false;
      if (!annot.enableOnlyWithFlag().isEmpty()) {
        if (!hasPlusMinusPrefix(annot.enableOnlyWithFlag())) {
          errorf(method, "enableOnlyWithFlag name must have a + or - prefix");
        }
        hasFlag = true;
      }
      if (!annot.disableWithFlag().isEmpty()) {
        if (!hasPlusMinusPrefix(annot.disableWithFlag())) {
          errorf(method, "disableWithFlag name must have a + or - prefix");
        }
        if (hasFlag) {
          errorf(
              method,
              "Only one of StarlarkMethod.enableOnlyWithFlag and StarlarkMethod.disableWithFlag"
                  + " may be specified.");
        }
        hasFlag = true;
      }

      if (annot.allowReturnNones() != (method.getAnnotation(Nullable.class) != null)) {
        errorf(method, "Method must be annotated with @Nullable iff allowReturnNones is set.");
      }

      checkParameters(method, annot);

      // Verify that result type, if final, might satisfy Starlark.fromJava.
      // (If the type is non-final we can't prove that all subclasses are invalid.)
      TypeMirror ret = method.getReturnType();
      if (ret.getKind() == TypeKind.DECLARED) {
        DeclaredType obj = (DeclaredType) ret;
        if (obj.asElement().getModifiers().contains(Modifier.FINAL)
            && !types.isSameType(ret, stringType)
            && !types.isSameType(ret, integerType)
            && !types.isSameType(ret, booleanType)
            && !types.isAssignable(obj, starlarkValueType)
            && !types.isAssignable(obj, listType)
            && !types.isAssignable(obj, mapType)) {
          errorf(
              method,
              "StarlarkMethod-annotated method %s returns %s, which has no legal Starlark values"
                  + " (see Starlark.fromJava)",
              method.getSimpleName(),
              ret);
        }
      }
    }

    // Returning false allows downstream processors to work on the same annotations
    return false;
  }

  // TODO(adonovan): obviate these checks by separating field/method interfaces.
  private void checkStructFieldAnnotation(ExecutableElement method, StarlarkMethod annot) {
    // useStructField is incompatible with special thread-related parameters,
    // because unlike a method, which is actively called within a thread,
    // a field is a passive part of a data structure that may be accessed
    // from Java threads that don't have anything to do with Starlark threads.
    // However, the StarlarkSemantics is available even to fields,
    // because it is a required parameter for all attribute-selection
    // operations x.f.
    //
    // Not having a thread forces implementations to assume Mutability=null,
    // which is not quite right. Perhaps one day we can abolish Mutability
    // in favor of a tracing approach as in go.starlark.net.
    if (annot.useStarlarkThread()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " useStarlarkThread");
    }
    if (!annot.extraPositionals().name().isEmpty()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " extraPositionals");
    }
    if (!annot.extraKeywords().name().isEmpty()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " extraKeywords");
    }
    if (annot.selfCall()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " selfCall=true");
    }
    int nparams = annot.parameters().length;
    if (nparams > 0) {
      errorf(
          method,
          "method %s is annotated structField=true but also has %d Param annotations",
          method.getSimpleName(),
          nparams);
    }
  }

  private void checkParameters(ExecutableElement method, StarlarkMethod annot) {
    List<? extends VariableElement> params = method.getParameters();

    boolean allowPositionalNext = true;
    boolean allowPositionalOnlyNext = true;
    boolean allowNonDefaultPositionalNext = true;
    boolean hasUndocumentedMethods = false;

    // Check @Param annotations match parameters.
    Param[] paramAnnots = annot.parameters();
    for (int i = 0; i < paramAnnots.length; i++) {
      Param paramAnnot = paramAnnots[i];
      if (i >= params.size()) {
        errorf(
            method,
            "method %s has %d Param annotations but only %d parameters",
            method.getSimpleName(),
            paramAnnots.length,
            params.size());
        return;
      }
      VariableElement param = params.get(i);

      checkParameter(param, paramAnnot);

      // Check parameter ordering.
      if (paramAnnot.positional()) {
        if (!allowPositionalNext) {
          errorf(
              param,
              "Positional parameter '%s' is specified after one or more non-positional parameters",
              paramAnnot.name());
        }
        if (!paramAnnot.named() && !allowPositionalOnlyNext) {
          errorf(
              param,
              "Positional-only parameter '%s' is specified after one or more named or undocumented"
                  + " parameters",
              paramAnnot.name());
        }
        if (paramAnnot.defaultValue().isEmpty()) { // There is no default value.
          if (!allowNonDefaultPositionalNext) {
            errorf(
                param,
                "Positional parameter '%s' has no default value but is specified after one "
                    + "or more positional parameters with default values",
                paramAnnot.name());
          }
        } else { // There is a default value.
          // No positional parameters without a default value can come after this parameter.
          allowNonDefaultPositionalNext = false;
        }
      } else { // Not positional.
        // No positional parameters can come after this parameter.
        allowPositionalNext = false;

        if (!paramAnnot.named()) {
          errorf(param, "Parameter '%s' must be either positional or named", paramAnnot.name());
        }
      }
      if (!paramAnnot.documented()) {
        hasUndocumentedMethods = true;
      }
      if (paramAnnot.named() || !paramAnnot.documented()) {
        // No positional-only parameters can come after this parameter.
        allowPositionalOnlyNext = false;
      }
    }

    if (hasUndocumentedMethods && !annot.extraKeywords().name().isEmpty()) {
      errorf(
          method,
          "Method '%s' has undocumented parameters but also allows extra keyword parameters",
          annot.name());
    }
    checkSpecialParams(method, annot);
  }

  // Checks consistency of a single parameter with its Param annotation.
  private void checkParameter(Element param, Param paramAnnot) {
    TypeMirror paramType = param.asType(); // type of the Java method parameter

    // Give helpful hint for parameter of type Integer.
    TypeMirror integerType = getType("java.lang.Integer");
    if (types.isSameType(paramType, integerType)) {
      errorf(
          param,
          "use StarlarkInt, not Integer for parameter '%s' (and see Starlark.toInt)",
          paramAnnot.name());
    }

    // Reject an entry of Param.allowedTypes if not assignable to the parameter variable.
    for (ParamType paramTypeAnnot : paramAnnot.allowedTypes()) {
      TypeMirror t = getParamTypeType(paramTypeAnnot);
      if (!types.isAssignable(t, types.erasure(paramType))) {
        errorf(
            param,
            "annotated allowedTypes entry %s of parameter '%s' is not assignable to variable of "
                + "type %s",
            t,
            paramAnnot.name(),
            paramType);
      }
    }

    // Reject generic types C<T> other than C<?>,
    // since reflective calls check only the toplevel class.
    if (paramType instanceof DeclaredType declaredType) {
      for (TypeMirror typeArg : declaredType.getTypeArguments()) {
        if (!(typeArg instanceof WildcardType)) {
          errorf(
              param,
              "parameter '%s' has generic type %s, but only wildcard type parameters are"
                  + " allowed. Type inference in a Starlark-exposed method is unsafe. See"
                  + " StarlarkMethod class documentation for details.",
              param.getSimpleName(),
              paramType);
        }
      }
    }

    // Check sense of flag-controlled parameters.
    boolean hasFlag = false;
    if (!paramAnnot.enableOnlyWithFlag().isEmpty()) {
      if (!hasPlusMinusPrefix(paramAnnot.enableOnlyWithFlag())) {
        errorf(param, "enableOnlyWithFlag name must have a + or - prefix");
      }
      hasFlag = true;
    }
    if (!paramAnnot.disableWithFlag().isEmpty()) {
      if (!hasPlusMinusPrefix(paramAnnot.disableWithFlag())) {
        errorf(param, "disableWithFlag name must have a + or - prefix");
      }
      if (hasFlag) {
        errorf(
            param,
            "Parameter '%s' has enableOnlyWithFlag and disableWithFlag set. At most one may be set",
            paramAnnot.name());
      }
      hasFlag = true;
    }
    if (hasFlag == paramAnnot.valueWhenDisabled().isEmpty()) {
      errorf(
          param,
          hasFlag
              ? "Parameter '%s' may be disabled by semantic flag, thus valueWhenDisabled must be"
                  + " set"
              : "Parameter '%s' has valueWhenDisabled set, but is always enabled",
          paramAnnot.name());
    }

    // Ensure positional arguments are documented.
    if (!paramAnnot.documented() && paramAnnot.positional()) {
      errorf(
          param, "Parameter '%s' must be documented because it is positional.", paramAnnot.name());
    }
  }

  private static boolean hasPlusMinusPrefix(String s) {
    return s.charAt(0) == '-' || s.charAt(0) == '+';
  }

  // Returns the logical type of ParamType.type.
  private static TypeMirror getParamTypeType(ParamType paramType) {
    // See explanation of this hack at Element.getAnnotation
    // and at https://stackoverflow.com/a/10167558.
    try {
      paramType.type();
      throw new IllegalStateException("unreachable");
    } catch (MirroredTypeException ex) {
      return ex.getTypeMirror();
    }
  }

  private void checkSpecialParams(ExecutableElement method, StarlarkMethod annot) {
    if (!annot.extraPositionals().enableOnlyWithFlag().isEmpty()
        || !annot.extraPositionals().disableWithFlag().isEmpty()) {
      errorf(method, "The extraPositionals parameter may not be toggled by semantic flag");
    }
    if (!annot.extraKeywords().enableOnlyWithFlag().isEmpty()
        || !annot.extraKeywords().disableWithFlag().isEmpty()) {
      errorf(method, "The extraKeywords parameter may not be toggled by semantic flag");
    }

    List<? extends VariableElement> params = method.getParameters();
    int index = annot.parameters().length;

    // insufficient parameters?
    int special = numExpectedSpecialParams(annot);
    if (index + special > params.size()) {
      errorf(
          method,
          "method %s is annotated with %d Params plus %d special parameters, but has only %d"
              + " parameter variables",
          method.getSimpleName(),
          index,
          special,
          params.size());
      return; // not safe to proceed
    }

    if (!annot.extraPositionals().name().isEmpty()) {
      VariableElement param = params.get(index++);
      // Allow any supertype of Tuple.
      TypeMirror tupleType =
          types.getDeclaredType(elements.getTypeElement("net.starlark.java.eval.Tuple"));
      if (!types.isAssignable(tupleType, param.asType())) {
        errorf(
            param,
            "extraPositionals special parameter '%s' has type %s, to which a Tuple cannot be"
                + " assigned",
            param.getSimpleName(),
            param.asType());
      }
    }

    if (!annot.extraKeywords().name().isEmpty()) {
      VariableElement param = params.get(index++);
      // Allow any supertype of Dict<String, Object>.
      TypeMirror dictOfStringObjectType =
          types.getDeclaredType(
              elements.getTypeElement("net.starlark.java.eval.Dict"),
              getType("java.lang.String"),
              getType("java.lang.Object"));
      if (!types.isAssignable(dictOfStringObjectType, param.asType())) {
        errorf(
            param,
            "extraKeywords special parameter '%s' has type %s, to which Dict<String, Object>"
                + " cannot be assigned",
            param.getSimpleName(),
            param.asType());
      }
    }

    if (annot.useStarlarkThread()) {
      VariableElement param = params.get(index++);
      TypeMirror threadType = getType("net.starlark.java.eval.StarlarkThread");
      if (!types.isSameType(threadType, param.asType())) {
        errorf(
            param,
            "for useStarlarkThread special parameter '%s', got type %s, want StarlarkThread",
            param.getSimpleName(),
            param.asType());
      }
    }

    if (annot.useStarlarkSemantics()) {
      VariableElement param = params.get(index++);
      TypeMirror semanticsType = getType("net.starlark.java.eval.StarlarkSemantics");
      if (!types.isSameType(semanticsType, param.asType())) {
        errorf(
            param,
            "for useStarlarkSemantics special parameter '%s', got type %s, want StarlarkSemantics",
            param.getSimpleName(),
            param.asType());
      }
    }

    // surplus parameters?
    if (index < params.size()) {
      errorf(
          params.get(index), // first surplus parameter
          "method %s is annotated with %d Params plus %d special parameters, yet has %d parameter"
              + " variables",
          method.getSimpleName(),
          annot.parameters().length,
          special,
          params.size());
    }
  }

  private static int numExpectedSpecialParams(StarlarkMethod annot) {
    int n = 0;
    n += annot.extraPositionals().name().isEmpty() ? 0 : 1;
    n += annot.extraKeywords().name().isEmpty() ? 0 : 1;
    n += annot.useStarlarkThread() ? 1 : 0;
    n += annot.useStarlarkSemantics() ? 1 : 0;
    return n;
  }

  // Reports a (formatted) error and fails the compilation.
  @FormatMethod
  private void errorf(Element e, String format, Object... args) {
    messager.printMessage(Diagnostic.Kind.ERROR, String.format(format, args), e);
  }
}
