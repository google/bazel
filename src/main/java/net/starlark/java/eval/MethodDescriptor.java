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

package net.starlark.java.eval;

import static java.util.Arrays.stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.errorprone.annotations.CheckReturnValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;

/**
 * A value class to store Methods with their corresponding {@link StarlarkMethod} annotation
 * metadata. This is needed because the annotation is sometimes in a superclass.
 *
 * <p>The annotation metadata is duplicated in this class to avoid usage of Java dynamic proxies
 * which are ~7× slower.
 */
final class MethodDescriptor {
  private final Method method;
  private final StarlarkMethod annotation;

  private final String name;
  private final String doc;
  private final boolean documented;
  private final boolean structField;
  private final ParamDescriptor[] parameters;
  private final boolean extraPositionals;
  private final boolean extraKeywords;
  private final boolean selfCall;
  private final boolean allowReturnNones;
  private final boolean useStarlarkThread;
  private final boolean useStarlarkSemantics;
  private final boolean positionalsReusableAsJavaArgsVectorIfArgumentCountValid;

  private enum HowToHandleReturn {
    NULL_TO_NONE, // any Starlark value; null -> None
    ERROR_ON_NULL, // any Starlark value; null -> error
    STARLARK_INT_OF_INT, // Java int -> StarlarkInt
    FROM_JAVA, // Starlark.fromJava conversion (List, Map, various Numbers, null perhaps)
  }

  private final HowToHandleReturn howToHandleReturn;

  private MethodDescriptor(
      Method method,
      StarlarkMethod annotation,
      String name,
      String doc,
      boolean documented,
      boolean structField,
      ParamDescriptor[] parameters,
      boolean extraPositionals,
      boolean extraKeywords,
      boolean selfCall,
      boolean allowReturnNones,
      boolean trustReturnsValid,
      boolean useStarlarkThread,
      boolean useStarlarkSemantics) {
    this.method = method;
    this.annotation = annotation;
    this.name = name;
    this.doc = doc;
    this.documented = documented;
    this.structField = structField;
    this.parameters = parameters;
    this.extraPositionals = extraPositionals;
    this.extraKeywords = extraKeywords;
    this.selfCall = selfCall;
    this.allowReturnNones = allowReturnNones;
    this.useStarlarkThread = useStarlarkThread;
    this.useStarlarkSemantics = useStarlarkSemantics;

    Class<?> ret = method.getReturnType();
    if (ret == void.class || ret == boolean.class) {
      // * `void` function returns `null`
      // * `boolean` function never returns `null`
      // We could have specialized enum variant, but null check is cheap.
      howToHandleReturn = HowToHandleReturn.NULL_TO_NONE;
    } else if (StarlarkValue.class.isAssignableFrom(ret)
        || String.class == ret
        || Boolean.class == ret
        || trustReturnsValid) {
      howToHandleReturn =
          allowReturnNones ? HowToHandleReturn.NULL_TO_NONE : HowToHandleReturn.ERROR_ON_NULL;
    } else if (ret == int.class) {
      howToHandleReturn = HowToHandleReturn.STARLARK_INT_OF_INT;
    } else {
      howToHandleReturn = HowToHandleReturn.FROM_JAVA;
    }

    this.positionalsReusableAsJavaArgsVectorIfArgumentCountValid =
        !extraKeywords
            && !extraPositionals
            && !useStarlarkSemantics
            && !useStarlarkThread
            && stream(parameters).allMatch(MethodDescriptor::paramUsableAsPositionalWithoutChecks);
  }

  private static boolean paramUsableAsPositionalWithoutChecks(ParamDescriptor param) {
    return param.isPositional()
        && param.disabledByFlag() == null
        && param.getAllowedClasses() == null;
  }

  /** Returns the StarlarkMethod annotation corresponding to this method. */
  StarlarkMethod getAnnotation() {
    return annotation;
  }

  /** @return Starlark method descriptor for provided Java method and signature annotation. */
  static MethodDescriptor of(
      Method method, StarlarkMethod annotation, StarlarkSemantics semantics) {
    // This happens when the interface is public but the implementation classes
    // have reduced visibility.
    method.setAccessible(true);

    Class<?>[] paramClasses = method.getParameterTypes();
    Param[] paramAnnots = annotation.parameters();
    ParamDescriptor[] params = new ParamDescriptor[paramAnnots.length];
    Arrays.setAll(params, i -> ParamDescriptor.of(paramAnnots[i], paramClasses[i], semantics));

    return new MethodDescriptor(
        method,
        annotation,
        annotation.name(),
        annotation.doc(),
        annotation.documented(),
        annotation.structField(),
        params,
        !annotation.extraPositionals().name().isEmpty(),
        !annotation.extraKeywords().name().isEmpty(),
        annotation.selfCall(),
        annotation.allowReturnNones(),
        annotation.trustReturnsValid(),
        annotation.useStarlarkThread(),
        annotation.useStarlarkSemantics());
  }

  private static final Object[] EMPTY = {};

  /** Calls this method, which must have {@code structField=true}. */
  Object callField(Object obj, StarlarkSemantics semantics, @Nullable Mutability mu)
      throws EvalException, InterruptedException {
    if (!structField) {
      throw new IllegalStateException("not a struct field: " + name);
    }
    Object[] args = useStarlarkSemantics ? new Object[] {semantics} : EMPTY;
    return call(obj, args, mu);
  }

  /**
   * Invokes this method using {@code obj} as a target and {@code args} as Java arguments.
   *
   * <p>Methods with {@code void} return type return {@code None} following Python convention.
   *
   * <p>The Mutability is used if it is necessary to allocate a Starlark copy of a Java result.
   */
  Object call(Object obj, Object[] args, @Nullable Mutability mu)
      throws EvalException, InterruptedException {
    Preconditions.checkNotNull(obj);
    Object result;
    try {
      result = method.invoke(obj, args);
    } catch (IllegalAccessException ex) {
      // "Can't happen": the annotated processor ensures that annotated methods are accessible.
      throw new IllegalStateException(ex);

    } catch (IllegalArgumentException ex) {
      // "Can't happen": unexpected type mismatch.
      // Show details to aid debugging (see e.g. b/162444744).
      StringBuilder buf = new StringBuilder();
      buf.append(
          String.format(
              "IllegalArgumentException (%s) in Starlark call of %s, obj=%s (%s), args=[",
              ex.getMessage(), method, Starlark.repr(obj), Starlark.type(obj)));
      String sep = "";
      for (Object arg : args) {
        buf.append(String.format("%s%s (%s)", sep, Starlark.repr(arg), Starlark.type(arg)));
        sep = ", ";
      }
      buf.append(']');
      throw new IllegalArgumentException(buf.toString());

    } catch (InvocationTargetException ex) {
      Throwable e = ex.getCause();
      if (e == null) {
        throw new IllegalStateException(e);
      }
      // Don't intercept unchecked exceptions.
      Throwables.throwIfUnchecked(e);
      if (e instanceof EvalException) {
        throw (EvalException) e;
      } else if (e instanceof InterruptedException) {
        throw (InterruptedException) e;
      } else {
        // All other checked exceptions (e.g. LabelSyntaxException) are reported to Starlark.
        throw new EvalException(e);
      }
    }

    // This switch is an optimization to reduce the overhead
    // of an unconditional null check and fromJava call.
    switch (howToHandleReturn) {
      case NULL_TO_NONE:
        return result != null ? result : Starlark.NONE;
      case ERROR_ON_NULL:
        if (result == null) {
          throw methodInvocationReturnedNull(args);
        }
        return result;
      case STARLARK_INT_OF_INT:
        return StarlarkInt.of((Integer) result);
      case FROM_JAVA:
        if (result == null && !allowReturnNones) {
          throw methodInvocationReturnedNull(args);
        }
        return Starlark.fromJava(result, mu);
    }
    throw new IllegalStateException("unreachable: " + howToHandleReturn);
  }

  @CheckReturnValue // don't forget to throw it
  private NullPointerException methodInvocationReturnedNull(Object[] args) {
    return new NullPointerException(
        "method invocation returned null: " + getName() + Tuple.of(args));
  }

  /** @see StarlarkMethod#name() */
  String getName() {
    return name;
  }

  Method getMethod() {
    return method;
  }

  /** @see StarlarkMethod#structField() */
  boolean isStructField() {
    return structField;
  }

  /** @see StarlarkMethod#useStarlarkThread() */
  boolean isUseStarlarkThread() {
    return useStarlarkThread;
  }

  /** @see StarlarkMethod#useStarlarkSemantics() */
  boolean isUseStarlarkSemantics() {
    return useStarlarkSemantics;
  }

  /** @return {@code true} if this method accepts extra arguments ({@code *args}) */
  boolean acceptsExtraArgs() {
    return extraPositionals;
  }

  /** @see StarlarkMethod#extraKeywords() */
  boolean acceptsExtraKwargs() {
    return extraKeywords;
  }

  /** @see StarlarkMethod#parameters() */
  ParamDescriptor[] getParameters() {
    return parameters;
  }

  /** Returns the index of the named parameter or -1 if not found. */
  int getParameterIndex(String name) {
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i].getName().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /** @see StarlarkMethod#documented() */
  boolean isDocumented() {
    return documented;
  }

  /** @see StarlarkMethod#doc() */
  String getDoc() {
    return doc;
  }

  /** @see StarlarkMethod#selfCall() */
  boolean isSelfCall() {
    return selfCall;
  }

  /**
   * Returns true if we may directly reuse the Starlark positionals vector as the Java {@code args}
   * vector passed to {@link #call} as long as the Starlark call was made with a valid number of
   * arguments.
   *
   * <p>More precisely, this means that we do not need to insert extra values into the args vector
   * (such as ones corresponding to {@code *args}, {@code **kwargs}, or {@code self} in Starlark),
   * and all Starlark parameters are simple positional parameters which cannot be disabled by a flag
   * and do not require type checking.
   */
  boolean isPositionalsReusableAsJavaArgsVectorIfArgumentCountValid() {
    return positionalsReusableAsJavaArgsVectorIfArgumentCountValid;
  }
}
