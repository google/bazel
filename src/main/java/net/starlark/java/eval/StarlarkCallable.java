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

import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.starlark.java.syntax.Location;

/**
 * The StarlarkCallable interface is implemented by all Starlark values that may be called from
 * Starlark like a function, including built-in functions and methods, Starlark functions, and
 * application-defined objects (such as rules, aspects, and providers in Bazel).
 *
 * <p>It defines two methods: {@code fastcall}, for performance, or {@code call} for convenience. By
 * default, {@code fastcall} delegates to {@code call}, and call throws an exception, so an
 * implementer may override either one.
 */
public interface StarlarkCallable extends StarlarkValue {

  /**
   * Defines the "convenient" implementation of function calling for a callable value.
   *
   * <p>Do not call this function directly. Use the {@link Starlark#call} function to make a call,
   * as it handles necessary book-keeping such as maintenance of the call stack, exception handling,
   * and so on.
   *
   * <p>The default implementation throws an EvalException.
   *
   * <p>See {@link Starlark#fastcall} for basic information about function calls.
   *
   * @param thread the StarlarkThread in which the function is called
   * @param args a tuple of the arguments passed by position
   * @param kwargs a new, mutable dict of the arguments passed by keyword. Iteration order is
   *     determined by keyword order in the call expression.
   */
  default Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
      throws EvalException, InterruptedException {
    throw Starlark.errorf("function %s not implemented", getName());
  }

  /**
   * Defines the "fast" implementation of function calling for a callable value.
   *
   * <p>Do not call this function directly. Use the {@link Starlark#call} or {@link
   * Starlark#fastcall} function to make a call, as it handles necessary book-keeping such as
   * maintenance of the call stack, exception handling, and so on.
   *
   * <p>The fastcall implementation takes ownership of the two arrays, and may retain them
   * indefinitely or modify them. The caller must not modify or even access the two arrays after
   * making the call.
   *
   * <p>This method defines the low-level or "fast" calling convention. A more convenient interface
   * is provided by the {@link #call} method, which provides a signature analogous to {@code def
   * f(*args, **kwargs)}, or possibly the "self-call" feature of the {@link StarlarkMethod#selfCall}
   * annotation mechanism.
   *
   * <p>The default implementation forwards the call to {@code call}, after rejecting any duplicate
   * named arguments. Other implementations of this method should similarly reject duplicates.
   *
   * <p>See {@link Starlark#fastcall} for basic information about function calls.
   *
   * @param thread the StarlarkThread in which the function is called
   * @param positional a list of positional arguments
   * @param named a list of named arguments, as alternating Strings/Objects. May contain dups.
   */
  default Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    LinkedHashMap<String, Object> kwargs = Maps.newLinkedHashMapWithExpectedSize(named.length >> 1);
    for (int i = 0; i < named.length; i += 2) {
      if (kwargs.put((String) named[i], named[i + 1]) != null) {
        throw Starlark.errorf("%s got multiple values for parameter '%s'", this, named[i]);
      }
    }
    return call(thread, Tuple.of(positional), Dict.wrap(thread.mutability(), kwargs));
  }

  /**
   * Defines the "fast" implementation variant of function calling with only positional arguments.
   *
   * <p>Do not call this function directly. Use the {@link Starlark#easycall} function to make a
   * call, as it handles necessary book-keeping such as maintenance of the call stack, exception
   * handling, and so on.
   *
   * <p>The fastcall implementation takes ownership of the {@code positional} array, and may retain
   * it indefinitely or modify it. The caller must not modify or even access the array after making
   * the call.
   *
   * <p>The default implementation forwards the call to {@code call}.
   *
   * @param thread the StarlarkThread in which the function is called
   * @param positional a list of positional arguments
   */
  default Object positionalOnlyCall(StarlarkThread thread, Object... positional)
      throws EvalException, InterruptedException {
    ArgumentProcessor argumentProcessor = requestArgumentProcessor(thread);
    // TODO(b/380824219): Consider adding am addPositionalArgs(Object[] positional) method to
    // ArgumentProcessor. See also b/380824219#comment23
    for (Object value : positional) {
      argumentProcessor.addPositionalArg(value);
    }
    return argumentProcessor.call(thread);
  }

  /**
   * Defines a helper object for invoking a StarlarkCallable.
   *
   * <p>An ArgumentProcessor implementation is returned by {@link #requestArgumentProcessor}. The
   * ArgumentProcessor implementation must then be used to first place the arguments, and then its
   * method {@link #call} is used to make the invocation.
   */
  abstract static class ArgumentProcessor {
    protected final StarlarkThread thread;

    public ArgumentProcessor(StarlarkThread thread) {
      this.thread = thread;
    }

    public abstract void addPositionalArg(Object value) throws EvalException;

    public abstract void addNamedArg(String name, Object value) throws EvalException;

    public abstract StarlarkCallable getCallable();

    public abstract Object call(StarlarkThread thread) throws EvalException, InterruptedException;

    /**
     * Throws a given {@code EvalException} from inside {@link #addPositionalArg} or {@link
     * #addNamedArg}.
     *
     * <p>In the Starlark evaluation model, the work of ArgumentProcessor is logically part of the
     * callable's evaluation, so the stack trace for any exceptions thrown during argument
     * processing needs to contain the name and location of the callable. This method pushes the
     * stack before throwing the exception, ensuring that the stack trace is as expected.
     */
    protected void pushCallableAndThrow(EvalException e) throws EvalException {
      thread.push(getCallable());
      throw e;
    }
  }

  /**
   * A default implementation of ArgumentProcessor that simply stores the arguments in a list and a
   * LinkedHashMap and then passes them to the StarlarkCallable.call() method.
   */
  static final class DefaultArgumentProcessor extends ArgumentProcessor {
    private final StarlarkCallable owner;
    private final ArrayList<Object> positional;
    private final LinkedHashMap<String, Object> named;

    DefaultArgumentProcessor(StarlarkCallable owner, StarlarkThread thread) {
      super(thread);
      this.owner = owner;
      this.positional = new ArrayList<>();
      this.named = Maps.newLinkedHashMapWithExpectedSize(0);
    }

    @Override
    public void addPositionalArg(Object value) throws EvalException {
      positional.add(value);
    }

    @Override
    public void addNamedArg(String name, Object value) throws EvalException {
      if (named.put(name, value) != null) {
        pushCallableAndThrow(
            Starlark.errorf("%s got multiple values for parameter '%s'", owner, name));
      }
    }

    @Override
    public StarlarkCallable getCallable() {
      return owner;
    }

    @Override
    public Object call(StarlarkThread thread) throws EvalException, InterruptedException {
      return owner.call(
          thread, Tuple.wrap(positional.toArray()), Dict.wrap(thread.mutability(), named));
    }
  }

  /**
   * Returns a FasterCall implementation if the callable supports fasterCall invocations, else null.
   */
  default ArgumentProcessor requestArgumentProcessor(StarlarkThread thread) throws EvalException {
    return new DefaultArgumentProcessor(this, thread);
  }

  /** Returns the form this callable value should take in a stack trace. */
  String getName();

  /**
   * Returns the location of the definition of this callable value, or BUILTIN if it was not defined
   * in Starlark code.
   */
  default Location getLocation() {
    return Location.BUILTIN;
  }
}
