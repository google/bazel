// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.autocodec;

import static com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodecProcessor.InstantiatorKind.CONSTRUCTOR;
import static com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodecProcessor.InstantiatorKind.FACTORY_METHOD;
import static com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodecProcessor.InstantiatorKind.INTERNER;
import static com.google.devtools.build.lib.skyframe.serialization.autocodec.TypeOperations.findRelationWithGenerics;
import static com.google.devtools.build.lib.skyframe.serialization.autocodec.TypeOperations.getErasureAsMirror;
import static com.google.devtools.build.lib.skyframe.serialization.autocodec.TypeOperations.writeGeneratedClassToFile;
import static java.util.Objects.requireNonNull;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.Instantiator;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.Interner;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.TypeOperations.Relation;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Javac annotation processor (compiler plugin) for generating {@link ObjectCodec} implementations.
 *
 * <p>User code must never reference this class.
 */
@AutoService(Processor.class)
public class AutoCodecProcessor extends AbstractProcessor {
  /**
   * Passing {@code --javacopt=-Aautocodec_print_generated} to {@code blaze build} tells AutoCodec
   * to print the generated code.
   */
  private static final String PRINT_GENERATED_OPTION = "autocodec_print_generated";

  private ProcessingEnvironment env; // Captured from `init` method.

  @Override
  public Set<String> getSupportedOptions() {
    return ImmutableSet.of(PRINT_GENERATED_OPTION);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoCodec.class.getCanonicalName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported(); // Supports all versions of Java.
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.env = processingEnv;
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    try {
      processInternal(roundEnv);
    } catch (SerializationProcessingException e) {
      // Reporting a message with ERROR kind will fail compilation.
      env.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.getElement());
    }
    return false;
  }

  private void processInternal(RoundEnvironment roundEnv) throws SerializationProcessingException {
    for (Element element : roundEnv.getElementsAnnotatedWith(AutoCodec.class)) {
      AutoCodec annotation = element.getAnnotation(AutoCodec.class);
      TypeElement encodedType = (TypeElement) element;
      ResolvedInstantiator instantiator = determineInstantiator(encodedType);
      TypeSpec codecClass =
          switch (instantiator.kind()) {
            case CONSTRUCTOR, FACTORY_METHOD ->
                defineClassWithInstantiator(encodedType, instantiator.method(), annotation);
            case INTERNER ->
                defineClassWithInterner(encodedType, instantiator.method(), annotation);
          };

      JavaFile file = writeGeneratedClassToFile(element, codecClass, env);
      if (env.getOptions().containsKey(PRINT_GENERATED_OPTION)) {
        note("AutoCodec generated codec for " + element + ":\n" + file);
      }
    }
  }

  private TypeSpec defineClassWithInstantiator(
      TypeElement encodedType, ExecutableElement instantiator, AutoCodec annotation)
      throws SerializationProcessingException {
    return new DeferredObjectCodecGenerator(env, instantiator.getParameters())
        .defineCodec(encodedType, annotation, instantiator);
  }

  private TypeSpec defineClassWithInterner(
      TypeElement encodedType, ExecutableElement interner, AutoCodec annotation)
      throws SerializationProcessingException {
    return new InterningObjectCodecGenerator(env).defineCodec(encodedType, annotation, interner);
  }

  enum InstantiatorKind {
    CONSTRUCTOR,
    FACTORY_METHOD,
    INTERNER
  }

  record ResolvedInstantiator(InstantiatorKind kind, ExecutableElement method) {
    ResolvedInstantiator {
      requireNonNull(kind, "kind");
      requireNonNull(method, "method");
    }

    private static ResolvedInstantiator create(InstantiatorKind kind, ExecutableElement method) {
      return new ResolvedInstantiator(kind, method);
    }
  }

  /**
   * Determines the {@link ResolvedInstantiator} by scanning the constructors and methods for
   * annotations.
   *
   * <p>Identifies the {@link Instantiator} or {@link Interner} annotations, throwing an exception
   * if there are multiple. Falls back to checking for a unique constructor or throws otherwise.
   */
  private ResolvedInstantiator determineInstantiator(TypeElement encodedType)
      throws SerializationProcessingException {
    InstantiatorKind instantiatorKind = null;
    ExecutableElement markedMethod = null;

    List<ExecutableElement> constructors =
        ElementFilter.constructorsIn(encodedType.getEnclosedElements());

    for (ExecutableElement constructor : constructors) {
      if (hasInstantiatorAnnotation(constructor)) {
        if (markedMethod != null) {
          throw new SerializationProcessingException(
              encodedType,
              "%s has multiple constructors with the Instantiator annotation.",
              encodedType.getQualifiedName());
        }
        markedMethod = constructor;
        instantiatorKind = CONSTRUCTOR;
      }
    }

    for (ExecutableElement method : ElementFilter.methodsIn(encodedType.getEnclosedElements())) {
      if (hasInstantiatorAnnotation(method)) {
        verifyFactoryMethod(encodedType, method);
        if (markedMethod != null) {
          throw new SerializationProcessingException(
              encodedType,
              "%s has multiple Instantiator or Interner annotations: %s %s.",
              encodedType.getQualifiedName(),
              markedMethod.getSimpleName(),
              method.getSimpleName());
        }
        markedMethod = method;
        instantiatorKind = FACTORY_METHOD;
      }
      if (hasInternerAnnotation(method)) {
        verifyInterner(encodedType, method);
        if (markedMethod != null) {
          throw new SerializationProcessingException(
              encodedType,
              "%s has multiple Instantiator or Interner annotations: %s %s.",
              encodedType.getQualifiedName(),
              markedMethod.getSimpleName(),
              method.getSimpleName());
        }
        markedMethod = method;
        instantiatorKind = INTERNER;
      }
    }

    if (markedMethod != null) {
      return ResolvedInstantiator.create(instantiatorKind, markedMethod);
    }

    // If nothing is marked, see if there is a unique constructor.
    if (constructors.size() > 1) {
      throw new SerializationProcessingException(
          encodedType,
          "%s has multiple constructors but no Instantiator or Interner annotation.",
          encodedType.getQualifiedName());
    }
    // In Java, every class has at least one constructor, so this never fails.
    return ResolvedInstantiator.create(CONSTRUCTOR, constructors.get(0));
  }

  private static boolean hasInstantiatorAnnotation(ExecutableElement elt) {
    return elt.getAnnotation(Instantiator.class) != null;
  }

  private static boolean hasInternerAnnotation(ExecutableElement elt) {
    return elt.getAnnotation(Interner.class) != null;
  }

  private void verifyFactoryMethod(TypeElement encodedType, ExecutableElement elt)
      throws SerializationProcessingException {
    boolean success = elt.getModifiers().contains(Modifier.STATIC);
    if (success) {
      Relation equalityTest =
          findRelationWithGenerics(elt.getReturnType(), encodedType.asType(), env);
      success = equalityTest == Relation.EQUAL_TO || equalityTest == Relation.INSTANCE_OF;
    }
    if (!success) {
      throw new SerializationProcessingException(
          encodedType,
          "%s tags %s as an Instantiator, but it's not a valid factory method %s, %s",
          encodedType,
          elt.getSimpleName(),
          elt.getReturnType(),
          encodedType.asType());
    }
  }

  private void verifyInterner(TypeElement encodedType, ExecutableElement method)
      throws SerializationProcessingException {
    if (!method.getModifiers().contains(Modifier.STATIC)) {
      throw new SerializationProcessingException(
          encodedType, "%s is tagged @Interner, but it's not static.", method.getSimpleName());
    }
    List<? extends VariableElement> parameters = method.getParameters();
    if (parameters.size() != 1) {
      throw new SerializationProcessingException(
          encodedType,
          "%s is tagged @Interner, but it has %d parameters instead of 1.",
          method.getSimpleName(),
          parameters.size());
    }
    TypeMirror subjectType = getErasureAsMirror(encodedType, env);

    // The method should be able to accept a value of encodedType;
    TypeMirror parameterType = getErasureAsMirror(parameters.get(0).asType(), env);
    if (!env.getTypeUtils().isAssignable(subjectType, parameterType)) {
      throw new SerializationProcessingException(
          encodedType,
          "%s is tagged @Interner, but cannot accept a value of type %s because it is not"
              + " assignable to %s.",
          method.getSimpleName(),
          encodedType,
          parameterType);
    }

    // The method should return a value that can be assigned to encodedType.
    TypeMirror returnType = getErasureAsMirror(method.getReturnType(), env);
    if (!env.getTypeUtils().isAssignable(returnType, subjectType)) {
      throw new SerializationProcessingException(
          encodedType,
          "%s is tagged @Interner, but its return type %s cannot be assigned to type %s.",
          method.getSimpleName(),
          method.getReturnType(),
          encodedType);
    }
  }

  /** Emits a note to BUILD log during annotation processing for debugging. */
  private void note(String note) {
    env.getMessager().printMessage(Diagnostic.Kind.NOTE, note);
  }
}
