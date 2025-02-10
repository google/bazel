// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * The Starlark class defines the most important entry points, constants, and functions needed by
 * all clients of the Starlark interpreter.
 */
public final class Starlark {

  private Starlark() {} // uninstantiable

  /** The Starlark None value. */
  public static final NoneType NONE = NoneType.NONE;

  /**
   * A sentinel value passed to optional parameters of StarlarkMethod-annotated methods to indicate
   * that no argument value was supplied.
   */
  public static final Object UNBOUND = new UnboundMarker();

  /** A type representing no argument passed to {@code StarlarkMethod}s */
  @Immutable
  public static final class UnboundMarker implements StarlarkValue {
    private UnboundMarker() {}

    @Override
    public String toString() {
      return "<unbound>";
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<unbound>");
    }
  }

  /**
   * The universal bindings predeclared in every Starlark file, such as None, True, len, and range.
   */
  public static final ImmutableMap<String, Object> UNIVERSE = makeUniverse();

  /**
   * An {@code IllegalArgumentException} subclass for when a non-Starlark object is encountered in a
   * context where a Starlark value ({@code String}, {@code Boolean}, or {@code StarlarkValue}) was
   * expected.
   */
  public static final class InvalidStarlarkValueException extends IllegalArgumentException {
    @Nullable private final Class<?> invalidClass;

    public Class<?> getInvalidClass() {
      return invalidClass;
    }

    private InvalidStarlarkValueException(@Nullable Class<?> invalidClass) {
      super("invalid Starlark value: " + (invalidClass == null ? "null" : invalidClass));
      this.invalidClass = invalidClass;
    }
  }

  private static ImmutableMap<String, Object> makeUniverse() {
    ImmutableMap.Builder<String, Object> env = ImmutableMap.builder();
    env //
        .put("False", false)
        .put("True", true)
        .put("None", NONE);
    addMethods(env, new MethodLibrary());
    return env.build();
  }

  /**
   * Reports whether the argument is a legal Starlark value: a string, boolean, or StarlarkValue.
   */
  public static boolean valid(Object x) {
    return x instanceof String || x instanceof Boolean || x instanceof StarlarkValue;
  }

  /**
   * Returns {@code x} if it is a {@link #valid} Starlark value, otherwise throws
   * InvalidStarlarkValueException.
   */
  public static <T> T checkValid(T x) {
    if (!valid(x)) {
      throw new InvalidStarlarkValueException(x == null ? null : x.getClass());
    }
    return x;
  }

  /** Reports whether {@code x} is Java null or Starlark None. */
  public static boolean isNullOrNone(Object x) {
    return x == null || x == NONE;
  }

  /** Reports whether a Starlark value is assumed to be deeply immutable. */
  // TODO(adonovan): eliminate the concept of querying for immutability. It is currently used for
  // only one purpose, the precondition for adding an element to a Depset, but Depsets should check
  // hashability, like Dicts. (Similarly, querying for hashability should go: just attempt to hash a
  // value, and be prepared for it to fail.) In practice, a value may be immutable, either
  // inherently (e.g. string) or because it has become frozen, but we don't need to query for it.
  // Just attempt a mutation and be prepared for it to fail.
  // It is inefficient and potentially inconsistent to ask before doing.
  //
  // The main obstacle is that although depsets disallow (say) lists as keys even when frozen,
  // they permit a tuple of lists, or a struct containing lists, and many users exploit this.
  public static boolean isImmutable(Object x) {
    // NB: This is used as the basis for accepting objects in Depsets,
    // as well as for accepting objects as keys for Starlark dicts.

    if (x instanceof String || x instanceof Boolean) {
      return true;
    } else if (x instanceof StarlarkValue) {
      return ((StarlarkValue) x).isImmutable();
    } else {
      throw new InvalidStarlarkValueException(x.getClass());
    }
  }

  /**
   * Returns normally if the Starlark value is hashable and thus suitable as a dict key.
   *
   * @throws EvalException otherwise.
   */
  public static void checkHashable(Object x) throws EvalException {
    if (x instanceof String) {
      // Strings are the most common dict keys. Check them first, since `instanceof StarlarkValue`
      // (an interface) is slower than `instanceof String` (a final class).
    } else if (x instanceof StarlarkValue) {
      ((StarlarkValue) x).checkHashable();
    } else {
      // Throw if the type is bad. Otherwise it's a Boolean, which is hashable.
      Starlark.checkValid(x);
    }
  }

  /**
   * Converts a Java value {@code x} to a Starlark one, if x is not already a valid Starlark value.
   * An Integer, Long, or BigInteger is converted to a Starlark int, a double is converted to a
   * Starlark float, a Java List or Map is converted to a Starlark list or dict, respectively, and
   * null becomes {@link #NONE}. Any other non-Starlark value causes the function to throw
   * InvalidStarlarkValueException.
   *
   * <p>Elements of Lists and Maps must be valid Starlark values; they are not recursively
   * converted. (This avoids excessive unintended deep copying.)
   *
   * <p>This function is applied to the results of StarlarkMethod-annotated Java methods.
   */
  public static Object fromJava(Object x, @Nullable Mutability mutability) {
    if (x == null) {
      return NONE;
    } else if (valid(x)) {
      return x;
    } else if (x instanceof Number) {
      if (x instanceof Integer) {
        return StarlarkInt.of((Integer) x);
      } else if (x instanceof Long) {
        return StarlarkInt.of((Long) x);
      } else if (x instanceof BigInteger) {
        return StarlarkInt.of((BigInteger) x);
      } else if (x instanceof Double) {
        return StarlarkFloat.of((double) x);
      }
    } else if (x instanceof List) {
      return StarlarkList.copyOf(mutability, (List<?>) x);
    } else if (x instanceof Map) {
      return Dict.copyOf(mutability, (Map<?, ?>) x);
    }
    throw new InvalidStarlarkValueException(x.getClass());
  }

  /**
   * Converts a Starlark method's bound, non-None parameter value to a Java Optional wrapping that
   * value, and an unbound or None value to an empty Optional.
   *
   * <p>This is typically used in {@link StarlarkMethod} implementations, with a parameter whose
   * {@link Param#allowedTypes} is set to be {@code {T}} or {@code {NoneType, T}}.
   *
   * @throws ClassCastException if value is bound and non-None but is not of the expected class
   */
  public static <T> Optional<T> toJavaOptional(Object x, Class<T> expectedClass) {
    if (x == Starlark.UNBOUND || x == Starlark.NONE) {
      return Optional.empty();
    } else {
      return Optional.of(expectedClass.cast(x));
    }
  }

  /**
   * Returns the truth value of a valid Starlark value, as if by the Starlark expression {@code
   * bool(x)}.
   */
  public static boolean truth(Object x) {
    if (x instanceof Boolean) {
      return (Boolean) x;
    } else if (x instanceof StarlarkValue) {
      return ((StarlarkValue) x).truth();
    } else if (x instanceof String) {
      return !((String) x).isEmpty();
    } else {
      throw new InvalidStarlarkValueException(x.getClass());
    }
  }

  /**
   * Checks whether the Freezable Starlark value is frozen or temporarily immutable due to active
   * iterators.
   *
   * @throws EvalException if the value is not mutable.
   */
  public static void checkMutable(Mutability.Freezable x) throws EvalException {
    if (x.mutability().isFrozen()) {
      throw errorf("trying to mutate a frozen %s value", type(x));
    }
    if (x.updateIteratorCount(0)) {
      throw errorf("%s value is temporarily immutable due to active for-loop iteration", type(x));
    }
  }

  /**
   * Returns an iterable view of {@code x} if it is an iterable Starlark value; throws EvalException
   * otherwise.
   *
   * <p>Whereas the interpreter temporarily freezes the iterable value by bracketing {@code for}
   * loops and comprehensions in calls to {@link Freezable#updateIteratorCount}, iteration using
   * this method does not freeze the value. Callers should exercise care not to mutate the
   * underlying object during iteration.
   */
  public static Iterable<?> toIterable(Object x) throws EvalException {
    if (x instanceof StarlarkIterable) {
      return (Iterable<?>) x;
    }
    throw errorf("type '%s' is not iterable", type(x));
  }

  /**
   * Returns a new array of class Object[] containing the elements of Starlark iterable value {@code
   * x}. A Starlark value is iterable if it implements {@link StarlarkIterable}.
   */
  public static Object[] toArray(Object x) throws EvalException {
    // Specialize Sequence and Dict to avoid allocation and/or indirection.
    if (x instanceof Sequence) {
      // The returned array type must be exactly Object[],
      // not a subclass, so calling toArray() is not enough.
      return ((Sequence<?>) x).toArray(EMPTY);
    } else if (x instanceof Dict) {
      return ((Dict<?, ?>) x).keySet().toArray();
    } else {
      return Iterables.toArray(toIterable(x), Object.class);
    }
  }

  private static final Object[] EMPTY = {};

  /**
   * Returns the length of a Starlark string, sequence (such as a list or tuple), dict, or other
   * iterable, as if by the Starlark expression {@code len(x)}, or -1 if the value is valid but has
   * no length.
   */
  public static int len(Object x) {
    if (x instanceof String) {
      return ((String) x).length();
    } else if (x instanceof Sequence) {
      return ((Sequence) x).size();
    } else if (x instanceof Dict) {
      return ((Dict) x).size();
    } else if (x instanceof StarlarkSet) {
      return ((StarlarkSet) x).size();
    } else if (x instanceof StarlarkIterable) {
      // Iterables.size runs in constant time if x implements Collection.
      return Iterables.size((Iterable<?>) x);
    } else {
      checkValid(x);
      return -1; // valid but not a sequence
    }
  }

  /** Returns the name of the type of a value as if by the Starlark expression {@code type(x)}. */
  public static String type(Object x) {
    return classType(x.getClass());
  }

  /**
   * Returns the name of the type of instances of class c.
   *
   * <p>This function accepts any class, not just those of legal Starlark values, and may be used
   * for reporting error messages involving arbitrary Java classes, for example at the interface
   * between Starlark and Java.
   */
  public static String classType(Class<?> c) {
    // Check for "direct hits" first to avoid needing to scan for annotations.
    if (c.equals(String.class)) {
      return "string";
    } else if (StarlarkInt.class.isAssignableFrom(c)) {
      return "int";
    } else if (c.equals(Boolean.class)) {
      return "bool";
    } else if (c.equals(StarlarkFloat.class)) {
      return "float";
    }

    // Shortcut for the most common types.
    // These cases can be handled by `getStarlarkBuiltin`
    // but `getStarlarkBuiltin` is quite expensive.
    if (StarlarkList.class.isAssignableFrom(c)) {
      return "list";
    } else if (Tuple.class.isAssignableFrom(c)) {
      return "tuple";
    } else if (c.equals(Dict.class)) {
      return "dict";
    } else if (c.equals(NoneType.class)) {
      return "NoneType";
    } else if (c.equals(StarlarkFunction.class)) {
      return "function";
    } else if (c.equals(RangeList.class)) {
      return "range";
    } else if (c.equals(UnboundMarker.class)) {
      return "unbound";
    }

    // Abstract types, often used as parameter types.
    // Note == not isAssignableFrom: we don't want any
    // concrete types to inherit these names.
    if (c == StarlarkIterable.class) {
      return "iterable";
    } else if (c == Sequence.class) {
      return "sequence";
    } else if (c == StarlarkCallable.class) {
      return "callable";
    } else if (c == Structure.class) {
      return "structure";
    }

    StarlarkBuiltin module = StarlarkAnnotations.getStarlarkBuiltin(c);
    if (module != null) {
      return module.name();
    }

    if (c.equals(Object.class)) {
      // "unknown" is another unfortunate choice.
      // Object.class does mean "unknown" when talking about the type parameter
      // of a collection (List<Object>), but it also means "any" when used
      // as an argument to Sequence.cast, and more generally it means "value".
      return "unknown";

    } else if (List.class.isAssignableFrom(c)) {
      // Any class of java.util.List that isn't a Sequence.
      return "List";

    } else if (Map.class.isAssignableFrom(c)) {
      // Any class of java.util.Map that isn't a Dict.
      return "Map";

    } else if (c.equals(Integer.class)) {
      // Integer is not a legal Starlark value, but it does appear as
      // the return type for many built-in functions.
      return "int";

    } else if (c == void.class) {
      // Built-in void methods return None to Starlark.
      return "NoneType";

    } else if (c == boolean.class) {
      // Built-in function may return boolean.
      return "bool";

    } else {
      String simpleName = c.getSimpleName();
      return simpleName.isEmpty() ? c.getName() : simpleName;
    }
  }

  /**
   * Returns the name of the type of instances of {@code c} after being converted to Starlark values
   * by {@link #fromJava}, or "unknown" for {@code Object.class}, since that is used as a wildcard
   * type by evaluation machinery.
   *
   * <p>Note that {@code void.class} is treated as "NoneType" since void methods will return None to
   * Starlark.
   *
   * @throws InvalidStarlarkValueException if {@code c} is not {@code Object.class} and {@link
   *     #fromJava} would throw for instances of {@code c}.
   */
  public static String classTypeFromJava(Class<?> c) {
    if (c.equals(
            void.class) // Method.invoke on void-returning methods returns null; we treat it as None
        || c.equals(String.class)
        || c.equals(boolean.class)
        || c.equals(Boolean.class)
        || StarlarkValue.class.isAssignableFrom(c)
        || c.equals(Object.class)) {
      return classType(c);
    } else if (c.equals(int.class)
        || c.equals(Integer.class)
        || c.equals(long.class)
        || c.equals(Long.class)
        || BigInteger.class.isAssignableFrom(c)) {
      return classType(StarlarkInt.class);
    } else if (c.equals(double.class) || c.equals(Double.class)) {
      return classType(StarlarkFloat.class);
    } else if (List.class.isAssignableFrom(c)) {
      return classType(StarlarkList.class);
    } else if (Map.class.isAssignableFrom(c)) {
      return classType(Dict.class);
    }
    throw new InvalidStarlarkValueException(c);
  }

  /**
   * The ordering relation over (some) Starlark values.
   *
   * <p>Starlark values are ordered as follows.
   *
   * <ul>
   *   <li>{@code False < True}.
   *   <li>int values are ordered according to mathematical tradition.
   *   <li>float values are ordered according to IEEE 754, with the exception of NaN values: all NaN
   *       values compare equal to each other and greater than +Inf. The zero values 0.0 and -0.0
   *       compare equal.
   *   <li>int and float values may be compared. The comparison is mathematically exact, even if
   *       neither argument may be exactly converted to the type of the other. This is the only
   *       permitted case of comparisons between values of different types. NaN values compare
   *       greater than all integers.
   *   <li>Strings are ordered lexicographically by their elements (chars). So too are lists and
   *       tuples, though lists are not comparable with tuples.
   *   <li>If x implements Comparable, its {@code compareTo(y)} method may be called to determine
   *       the comparison if x and y have the same {@link #type}, though not necessary the same Java
   *       class.
   *   <li>Ordered comparison of any other values is an error (ClassCastException).
   * </ul>
   *
   * <p>This method defines a strict weak ordering that is consistent with {@link Object#equals}.
   */
  public static final Ordering<Object> ORDERING =
      new Ordering<Object>() {
        @Override
        public int compare(Object x, Object y) {
          return compareUnchecked(x, y);
        }
      };

  /**
   * Defines the strict weak ordering of Starlark values used for sorting and the comparison
   * operators. Throws ClassCastException on failure.
   */
  static int compareUnchecked(Object x, Object y) {
    if (sameType(x, y)) {
      // Ordered? e.g. string, int, bool, float.
      if (x instanceof Comparable) {
        @SuppressWarnings("unchecked")
        Comparable<Object> xcomp = (Comparable<Object>) x;
        return xcomp.compareTo(y);
      }

    } else {
      // different types

      if (x instanceof StarlarkFloat && y instanceof StarlarkInt) {
        // float < int
        double xf = ((StarlarkFloat) x).toDouble();
        return Double.isNaN(xf) ? +1 : -StarlarkInt.compareIntAndDouble((StarlarkInt) y, xf);
      } else if (x instanceof StarlarkInt && y instanceof StarlarkFloat) {
        // int < float
        double yf = ((StarlarkFloat) y).toDouble();
        return Double.isNaN(yf) ? -1 : StarlarkInt.compareIntAndDouble((StarlarkInt) x, yf);
      }
    }

    throw new ClassCastException(
        String.format("unsupported comparison: %s <=> %s", Starlark.type(x), Starlark.type(y)));
  }

  private static boolean sameType(Object x, Object y) {
    return x.getClass() == y.getClass() || Starlark.type(x).equals(Starlark.type(y));
  }

  /** Returns the string form of a value as if by the Starlark expression {@code str(x)}. */
  public static String str(Object x, StarlarkSemantics semantics) {
    return new Printer().str(x, semantics).toString();
  }

  /** Returns the string form of a value as if by the Starlark expression {@code repr(x)}. */
  public static String repr(Object x) {
    return new Printer().repr(x).toString();
  }

  /** Returns a string formatted as if by the Starlark expression {@code pattern % arguments}. */
  public static String format(StarlarkSemantics semantics, String pattern, Object... arguments) {
    Printer pr = new Printer();
    Printer.format(pr, semantics, pattern, arguments);
    return pr.toString();
  }

  /** Returns a string formatted as if by the Starlark expression {@code pattern % arguments}. */
  public static String formatWithList(
      StarlarkSemantics semantics, String pattern, List<?> arguments) {
    Printer pr = new Printer();
    Printer.formatWithList(pr, semantics, pattern, arguments);
    return pr.toString();
  }

  /**
   * Returns a Starlark doc string with each line trimmed and dedented to the minimal common
   * indentation level (except for the first line, which is always fully trimmed), and with leading
   * and trailing empty lines removed, following the PEP-257 algorithm. See
   * https://peps.python.org/pep-0257/#handling-docstring-indentation
   *
   * <p>For whitespace trimming, we use the same definition of whitespace as the Starlark {@code
   * string.strip} method.
   *
   * <p>Following PEP-257, we expand tabs in the doc string with tab size 8 before dedenting.
   * Starlark does not use tabs for indentation, but Starlark string values may contain tabs, so we
   * choose to expand them for consistency with Python.
   *
   * <p>The intent is to turn documentation strings like
   *
   * <pre>
   *     """Heading
   *
   *     Details paragraph
   *     """
   * </pre>
   *
   * and
   *
   * <pre>
   *     """
   *     Heading
   *
   *     Details paragraph
   *     """
   * </pre>
   *
   * into the desired "Heading\n\nDetails paragraph" form, and avoid the risk of documentation
   * processors interpreting indented parts of the original string as special formatting (e.g. code
   * blocks in the case of Markdown).
   */
  public static String trimDocString(String docString) {
    ImmutableList<String> lines = expandTabs(docString, 8).lines().collect(toImmutableList());
    if (lines.isEmpty()) {
      return "";
    }
    // First line is special: we fully strip it and ignore it for leading spaces calculation
    String firstLineTrimmed = StringModule.INSTANCE.strip(lines.get(0), NONE);
    Iterable<String> subsequentLines = Iterables.skip(lines, 1);
    int minLeadingSpaces = Integer.MAX_VALUE;
    for (String line : subsequentLines) {
      String strippedLeading = StringModule.INSTANCE.lstrip(line, NONE);
      if (!strippedLeading.isEmpty()) {
        int leadingSpaces = line.length() - strippedLeading.length();
        minLeadingSpaces = min(leadingSpaces, minLeadingSpaces);
      }
    }
    if (minLeadingSpaces == Integer.MAX_VALUE) {
      minLeadingSpaces = 0;
    }

    StringBuilder result = new StringBuilder();
    result.append(firstLineTrimmed);
    for (String line : subsequentLines) {
      // Length check ensures we ignore leading empty lines
      if (result.length() > 0) {
        result.append("\n");
      }
      if (line.length() > minLeadingSpaces) {
        result.append(StringModule.INSTANCE.rstrip(line.substring(minLeadingSpaces), NONE));
      }
    }
    // Remove trailing empty lines
    return StringModule.INSTANCE.rstrip(result.toString(), NONE);
  }

  /**
   * Expands tab characters to one or more spaces, producing the same indentation level at any given
   * point on any given line as would be expected when rendering the string with a given tab size; a
   * Java port of Python's {@code str.expandtabs}.
   */
  static String expandTabs(String line, int tabSize) {
    if (!line.contains("\t")) {
      // Don't alloc in the fast case.
      return line;
    }
    checkArgument(tabSize > 0);
    StringBuilder result = new StringBuilder();
    int col = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      switch (c) {
        case '\n':
        case '\r':
          result.append(c);
          col = 0;
          break;
        case '\t':
          int spaces = tabSize - col % tabSize;
          for (int j = 0; j < spaces; j++) {
            result.append(' ');
          }
          col += spaces;
          break;
        default:
          result.append(c);
          col++;
      }
    }
    return result.toString();
  }

  /** Returns a slice of a sequence as if by the Starlark operation {@code x[start:stop:step]}. */
  public static Object slice(
      Mutability mu, Object x, Object startObj, Object stopObj, Object stepObj)
      throws EvalException {
    int n;
    if (x instanceof String) {
      n = ((String) x).length();
    } else if (x instanceof Sequence) {
      n = ((Sequence) x).size();
    } else {
      throw errorf("invalid slice operand: %s", type(x));
    }

    int start;
    int stop;
    int step;

    // step
    if (stepObj == NONE) {
      step = 1;
    } else {
      step = toInt(stepObj, "slice step");
      if (step == 0) {
        throw errorf("slice step cannot be zero");
      }
    }

    // start, stop
    if (step > 0) {
      // positive stride: default indices are [0:n].
      if (startObj == NONE) {
        start = 0;
      } else {
        start = EvalUtils.toIndex(toInt(startObj, "start index"), n);
      }

      if (stopObj == NONE) {
        stop = n;
      } else {
        stop = EvalUtils.toIndex(toInt(stopObj, "stop index"), n);
      }

      if (stop < start) {
        stop = start; // => empty result
      }

    } else {
      // negative stride: default indices are effectively [n-1:-1],
      // though to get this effect using explicit indices requires
      // [n-1:-1-n:-1] because of the treatment of negative values.
      if (startObj == NONE) {
        start = n - 1;
      } else {
        start = toInt(startObj, "start index");
        if (start < 0) {
          start += n;
        }
        if (start >= n) {
          start = n - 1;
        }
      }

      if (stopObj == NONE) {
        stop = -1;
      } else {
        stop = toInt(stopObj, "stop index");
        if (stop < 0) {
          stop += n;
        }
        if (stop < -1) {
          stop = -1;
        }
      }

      if (start < stop) {
        start = stop; // => empty result
      }
    }

    // slice operation
    if (x instanceof String) {
      return StringModule.slice((String) x, start, stop, step);
    } else {
      return ((Sequence<?>) x).getSlice(mu, start, stop, step);
    }
  }

  /**
   * Returns the signed 32-bit value of a Starlark int. Throws an exception including {@code what}
   * if x is not a Starlark int or its value is not exactly representable as a Java int.
   *
   * @throws IllegalArgumentException if x is an Integer, which is not a Starlark value.
   */
  public static int toInt(Object x, String what) throws EvalException {
    if (x instanceof StarlarkInt) {
      return ((StarlarkInt) x).toInt(what);
    }
    if (x instanceof Integer) {
      throw new IllegalArgumentException("Integer is not a legal Starlark value");
    }
    throw errorf("got %s for %s, want int", type(x), what);
  }

  /**
   * Calls the function-like value {@code fn} in the specified thread, passing it the given
   * positional and named arguments, as if by the Starlark expression {@code fn(*args, **kwargs)}.
   *
   * <p>See also {@link #fastcall}.
   */
  public static Object call(
      StarlarkThread thread, Object fn, List<Object> args, Map<String, Object> kwargs)
      throws EvalException, InterruptedException {
    StarlarkCallable callable = getStarlarkCallable(thread, fn);
    StarlarkCallable.ArgumentProcessor argumentProcessor =
        requestArgumentProcessor(thread, callable);
    for (Object arg : args) {
      argumentProcessor.addPositionalArg(arg);
    }
    for (Map.Entry<String, Object> e : kwargs.entrySet()) {
      argumentProcessor.addNamedArg(e.getKey(), Starlark.checkValid(e.getValue()));
    }
    return callViaArgumentProcessor(thread, callable, argumentProcessor);
  }

  /**
   * Calls the function-like value {@code fn} in the specified thread, passing it the given
   * positional and named arguments in the "fastcall" array representation.
   *
   * <p>The caller must not subsequently modify or even inspect the two arrays.
   *
   * <p>If the call throws an unchecked throwable, regardless of whether it originates in a
   * user-defined built-in function or a bug in the interpreter itself, the throwable is wrapped by
   * {@link UncheckedEvalException} (for {@link RuntimeException}) or {@link UncheckedEvalError}
   * (for {@link Error}). The {@linkplain Throwable#getStackTrace stack trace} will reflect the
   * Starlark call stack rather than the Java call stack. The original throwable (and the Java call
   * stack) may be retrieved using {@link Throwable#getCause}.
   */
  // TODO(b/380824219): Remove this method once callWithArguments has been implemented on all
  // StarlarkCallable implementations that currently implement fastcall, plus a default
  // implementation in StarlarkCallable that forwards to StarlarkCallable.call().
  public static Object fastcall(
      StarlarkThread thread, StarlarkCallable callable, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {

    // LINT.IfChange(fastcall)
    thread.push(callable);
    try {
      return callable.fastcall(thread, positional, named);
    } catch (UncheckedEvalException | UncheckedEvalError ex) {
      throw ex; // already wrapped
    } catch (RuntimeException ex) {
      throw new UncheckedEvalException(ex, thread);
    } catch (Error ex) {
      throw new UncheckedEvalError(ex, thread);
    } catch (EvalException ex) {
      // If this exception was newly thrown, set its stack.
      throw ex.ensureStack(thread);
    } finally {
      thread.pop();
    }
    // LINT.ThenChange(:positionalOnlyCall)
  }

  /**
   * Calls the a function-like value in the specified thread via the given ArgumentProcessor which
   * previously has been returned by {@link #requestArgumentProcessor} and has been populated with
   * the arguments.
   *
   * <p>If the call throws an unchecked throwable, regardless of whether it originates in a
   * user-defined built-in function or a bug in the interpreter itself, the throwable is wrapped by
   * {@link UncheckedEvalException} (for {@link RuntimeException}) or {@link UncheckedEvalError}
   * (for {@link Error}). The {@linkplain Throwable#getStackTrace stack trace} will reflect the
   * Starlark call stack rather than the Java call stack. The original throwable (and the Java call
   * stack) may be retrieved using {@link Throwable#getCause}.
   */
  public static Object callViaArgumentProcessor(
      StarlarkThread thread,
      StarlarkCallable callable,
      StarlarkCallable.ArgumentProcessor argumentProcessor)
      throws EvalException, InterruptedException {
    thread.push(callable);
    try {
      return argumentProcessor.call(thread);
    } catch (UncheckedEvalException | UncheckedEvalError ex) {
      throw ex; // already wrapped
    } catch (RuntimeException ex) {
      throw new UncheckedEvalException(ex, thread);
    } catch (Error ex) {
      throw new UncheckedEvalError(ex, thread);
    } catch (EvalException ex) {
      // If this exception was newly thrown, set its stack.
      throw ex.ensureStack(thread);
    } finally {
      thread.pop();
    }
  }

  /**
   * Calls the function-like value {@code fn} in the specified thread, passing it only positional
   * arguments in the "fastcall" array representation.
   *
   * <p>The caller must not subsequently modify or even inspect the array.
   *
   * <p>If the call throws an unchecked throwable, regardless of whether it originates in a
   * user-defined built-in function or a bug in the interpreter itself, the throwable is wrapped by
   * {@link UncheckedEvalException} (for {@link RuntimeException}) or {@link UncheckedEvalError}
   * (for {@link Error}). The {@linkplain Throwable#getStackTrace stack trace} will reflect the
   * Starlark call stack rather than the Java call stack. The original throwable (and the Java call
   * stack) may be retrieved using {@link Throwable#getCause}.
   */
  public static Object positionalOnlyCall(StarlarkThread thread, Object fn, Object... positional)
      throws EvalException, InterruptedException {
    StarlarkCallable callable = getStarlarkCallable(thread, fn);

    // LINT.IfChange(positionalOnlyCall)
    thread.push(callable);
    try {
      return callable.positionalOnlyCall(thread, positional);
    } catch (UncheckedEvalException | UncheckedEvalError ex) {
      throw ex; // already wrapped
    } catch (RuntimeException ex) {
      throw new UncheckedEvalException(ex, thread);
    } catch (Error ex) {
      throw new UncheckedEvalError(ex, thread);
    } catch (EvalException ex) {
      // If this exception was newly thrown, set its stack.
      throw ex.ensureStack(thread);
    } finally {
      thread.pop();
    }
    // LINT.ThenChange(:fastcall)
  }

  static StarlarkCallable getStarlarkCallable(StarlarkThread thread, Object fn)
      throws EvalException {
    StarlarkCallable callable;
    if (fn instanceof StarlarkCallable starlarkCallable) {
      callable = starlarkCallable;
    } else {
      // @StarlarkMethod(selfCall)?
      MethodDescriptor desc =
          CallUtils.getSelfCallMethodDescriptor(thread.getSemantics(), fn.getClass());
      if (desc == null) {
        throw errorf("'%s' object is not callable", type(fn));
      }
      callable = new BuiltinFunction(fn, desc.getName(), desc);
    }
    return callable;
  }

  public static StarlarkCallable.ArgumentProcessor requestArgumentProcessor(
      StarlarkThread thread, StarlarkCallable callable) throws EvalException {
    return callable.requestArgumentProcessor(thread);
  }

  /**
   * Decorates a {@link RuntimeException} with its Starlark stack, to help maintainers locate
   * problematic source expressions.
   *
   * <p>The original exception can be retrieved using {@link #getCause}.
   */
  public static final class UncheckedEvalException extends RuntimeException {

    private UncheckedEvalException(RuntimeException cause, StarlarkThread thread) {
      super(createUncheckedEvalMessage(cause, thread), cause);
      thread.fillInStackTrace(this);
    }
  }

  /**
   * Decorates an {@link Error} with its Starlark stack, to help maintainers locate problematic
   * source expressions.
   *
   * <p>The original exception can be retrieved using {@link #getCause}.
   */
  public static final class UncheckedEvalError extends Error {

    private UncheckedEvalError(Error cause, StarlarkThread thread) {
      super(createUncheckedEvalMessage(cause, thread), cause);
      thread.fillInStackTrace(this);
    }
  }

  private static String createUncheckedEvalMessage(Throwable cause, StarlarkThread thread) {
    String msg = cause.getClass().getSimpleName() + " thrown during Starlark evaluation";
    String context = thread.getContextDescription();
    return isNullOrEmpty(context) ? msg : msg + " (" + context + ")";
  }

  /**
   * Returns a new EvalException with no location and an error message produced by Java-style string
   * formatting ({@code String.format(format, args)}). Use {@code errorf("%s", msg)} to produce an
   * error message from a non-constant expression {@code msg}.
   */
  @FormatMethod
  @CheckReturnValue // don't forget to throw it
  public static EvalException errorf(String format, Object... args) {
    return new EvalException(String.format(format, args));
  }

  // --- methods related to attributes (fields and methods) ---

  /**
   * Reports whether the value {@code x} has a field or method of the given name, as if by the
   * Starlark expression {@code hasattr(x, name)}.
   */
  public static boolean hasattr(StarlarkSemantics semantics, Object x, String name)
      throws EvalException {
    return (x instanceof Structure && ((Structure) x).getValue(name) != null)
        || CallUtils.getAnnotatedMethods(semantics, x.getClass()).containsKey(name);
  }

  /**
   * Returns the named field or method of value {@code x}, as if by the Starlark expression {@code
   * getattr(x, name, defaultValue)}. If the value has no such attribute, getattr returns {@code
   * defaultValue} if non-null, or throws an EvalException otherwise.
   */
  public static Object getattr(
      Mutability mu,
      StarlarkSemantics semantics,
      Object x,
      String name,
      @Nullable Object defaultValue)
      throws EvalException, InterruptedException {
    // StarlarkMethod-annotated field or method?
    MethodDescriptor method = CallUtils.getAnnotatedMethods(semantics, x.getClass()).get(name);
    if (method != null) {
      if (method.isStructField()) {
        return method.callField(x, semantics, mu);
      } else {
        return new BuiltinFunction(x, name, method);
      }
    }

    // user-defined field?
    if (x instanceof Structure struct) {
      Object field = struct.getValue(semantics, name);
      if (field != null) {
        return Starlark.checkValid(field);
      }

      if (defaultValue != null) {
        return defaultValue;
      }

      String error = struct.getErrorMessageForUnknownField(name);
      if (error != null) {
        throw Starlark.errorf("%s", error);
      }

    } else if (defaultValue != null) {
      return defaultValue;
    }

    throw Starlark.errorf(
        "'%s' value has no field or method '%s'%s",
        Starlark.type(x), name, SpellChecker.didYouMean(name, dir(mu, semantics, x)));
  }

  /**
   * Returns a new sorted list containing the names of the Starlark-accessible fields and methods of
   * the specified value, as if by the Starlark expression {@code dir(x)}.
   */
  public static StarlarkList<String> dir(Mutability mu, StarlarkSemantics semantics, Object x) {
    // Order the fields alphabetically.
    Set<String> fields = new TreeSet<>();
    if (x instanceof Structure) {
      fields.addAll(((Structure) x).getFieldNames());
    }
    fields.addAll(CallUtils.getAnnotatedMethods(semantics, x.getClass()).keySet());
    return StarlarkList.copyOf(mu, fields);
  }

  // --- methods related to StarlarkMethod-annotated classes ---

  /**
   * Returns the value of the named field of Starlark value {@code x}, as defined by a Java method
   * with a {@code StarlarkMethod(structField=true)} annotation.
   *
   * <p>Most callers should use {@link #getattr} instead.
   */
  public static Object getAnnotatedField(StarlarkSemantics semantics, Object x, String name)
      throws EvalException, InterruptedException {
    return CallUtils.getAnnotatedField(semantics, x, name);
  }

  /**
   * Returns the names of the fields of Starlark value {@code x}, as defined by Java methods with
   * {@code StarlarkMethod(structField=true)} annotations under the specified semantics.
   *
   * <p>Most callers should use {@link #dir} instead.
   */
  public static ImmutableSet<String> getAnnotatedFieldNames(StarlarkSemantics semantics, Object x) {
    return CallUtils.getAnnotatedFieldNames(semantics, x);
  }

  /**
   * Returns a map of Java methods and corresponding StarlarkMethod annotations for each annotated
   * Java method of the specified class. Elements are ordered by Java method name, which is not
   * necessarily the same as the Starlark attribute name. The set of enabled methods is determined
   * by {@link StarlarkSemantics#DEFAULT}. Excludes the {@code selfCall} method, if any.
   *
   * <p>Most callers should use {@link #dir} and {@link #getattr} instead.
   */
  // TODO(adonovan): move to StarlarkAnnotations; it's a static property of the annotations.
  public static ImmutableMap<Method, StarlarkMethod> getMethodAnnotations(Class<?> clazz) {
    ImmutableMap.Builder<Method, StarlarkMethod> result = ImmutableMap.builder();
    for (MethodDescriptor desc :
        CallUtils.getAnnotatedMethods(StarlarkSemantics.DEFAULT, clazz).values()) {
      result.put(desc.getMethod(), desc.getAnnotation());
    }
    return result.build();
  }

  /**
   * Returns the {@code StarlarkMethod(selfCall=true)}-annotated Java method of the specified Java
   * class that is called when Starlark calls an instance of that class like a function. It returns
   * null if no such method exists.
   */
  @Nullable
  public static Method getSelfCallMethod(StarlarkSemantics semantics, Class<?> clazz) {
    return CallUtils.getSelfCallMethod(semantics, clazz);
  }

  /** Equivalent to {@code addMethods(env, v, StarlarkSemantics.DEFAULT)}. */
  public static void addMethods(ImmutableMap.Builder<String, Object> env, Object v) {
    addMethods(env, v, StarlarkSemantics.DEFAULT);
  }

  /**
   * Adds to the environment {@code env} all Starlark methods of value {@code v}, filtered by the
   * given semantics. Starlark methods are Java methods of {@code v} with a {@link StarlarkMethod}
   * annotation whose {@code structField} and {@code selfCall} flags are both false.
   *
   * @throws IllegalArgumentException if any method annotation's {@link StarlarkMethod#structField}
   *     flag is true.
   */
  public static void addMethods(
      ImmutableMap.Builder<String, Object> env, Object v, StarlarkSemantics semantics) {
    Class<?> cls = v.getClass();
    // TODO(adonovan): rather than silently skip the selfCall method, reject it.
    for (Map.Entry<String, MethodDescriptor> e :
        CallUtils.getAnnotatedMethods(semantics, cls).entrySet()) {
      String name = e.getKey();

      // We cannot accept fields, as they are inherently problematic:
      // what if the Java method call fails, or gets interrupted?
      if (e.getValue().isStructField()) {
        throw new IllegalArgumentException(
            String.format("addMethods(%s): method %s has structField=true", cls.getName(), name));
      }

      // We use the 2-arg (desc=null) BuiltinFunction constructor instead of passing
      // the descriptor that CallUtils.getAnnotatedMethod would return,
      // because most calls to addMethods implicitly pass StarlarkSemantics.DEFAULT,
      // which is probably the wrong semantics for the later call.
      //
      // The effect is that the default semantics determine which method names are
      // statically available in the environment, but the thread's semantics determine
      // the dynamic behavior of the method call; this includes a run-time check for
      // whether the method was disabled by the semantics.
      env.put(name, new BuiltinFunction(v, name));
    }
  }

  /**
   * Parses the input as a file, resolves it in the specified module environment, compiles it, and
   * executes it in the specified thread. On success it returns None, unless the file's final
   * statement is an expression, in which case its value is returned.
   *
   * @throws SyntaxError.Exception if there were (static) scanner, parser, or resolver errors.
   * @throws EvalException if there was a (dynamic) evaluation error.
   * @throws InterruptedException if the Java thread was interrupted during evaluation.
   */
  public static Object execFile(
      ParserInput input, FileOptions options, Module module, StarlarkThread thread)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    StarlarkFile file = StarlarkFile.parse(input, options);
    Program prog = Program.compileFile(file, module);
    return execFileProgram(prog, module, thread);
  }

  /** Variant of {@link #execFile} that creates a module for the given predeclared environment. */
  // TODO(adonovan): is this needed?
  public static Object execFile(
      ParserInput input,
      FileOptions options,
      Map<String, Object> predeclared,
      StarlarkThread thread)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    Module module = Module.withPredeclared(thread.getSemantics(), predeclared);
    return execFile(input, options, module, thread);
  }

  /**
   * Executes a compiled Starlark file (as obtained from {@link Program#compileFile}) in the given
   * StarlarkThread. On success it returns None, unless the file's final statement is an expression,
   * in which case its value is returned.
   *
   * @throws EvalException if there was a (dynamic) evaluation error.
   * @throws InterruptedException if the Java thread was interrupted during evaluation.
   */
  public static Object execFileProgram(Program prog, Module module, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Resolver.Function rfn = prog.getResolvedFunction();

    // A given Module may be passed to execFileProgram multiple times in sequence,
    // for different compiled Programs. (This happens in the REPL, and in
    // EvaluationTestCase scenarios. It is not true of the go.starlark.net
    // implementation, and it complicates things significantly.
    // It would be nice to stop doing that.)
    //
    // Therefore StarlarkFunctions from different Programs (files) but initializing
    // the same Module need different mappings from the Program's numbering of
    // globals to the Module's numbering of globals, and to access a global requires
    // two array lookups.
    int[] globalIndex = module.getIndicesOfGlobals(rfn.getGlobals());

    if (module.getDocumentation() == null) {
      String documentation = rfn.getDocumentation();
      if (documentation != null) {
        module.setDocumentation(Starlark.trimDocString(documentation));
      }
    }

    StarlarkFunction toplevel =
        new StarlarkFunction(
            rfn,
            module,
            globalIndex,
            /* defaultValues= */ Tuple.empty(),
            /* freevars= */ Tuple.empty(),
            thread.getNextIdentityToken());
    return Starlark.positionalOnlyCall(thread, toplevel);
  }

  /**
   * Parses the input as an expression, resolves it in the specified module environment, compiles
   * it, evaluates it, and returns its value.
   *
   * @throws SyntaxError.Exception if there were (static) scanner, parser, or resolver errors.
   * @throws EvalException if there was a (dynamic) evaluation error.
   * @throws InterruptedException if the Java thread was interrupted during evaluation.
   */
  public static Object eval(
      ParserInput input, FileOptions options, Module module, StarlarkThread thread)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    StarlarkFunction fn = newExprFunction(input, options, module, thread.getNextIdentityToken());
    return Starlark.positionalOnlyCall(thread, fn);
  }

  /** Variant of {@link #eval} that creates a module for the given predeclared environment. */
  // TODO(adonovan): is this needed?
  public static Object eval(
      ParserInput input,
      FileOptions options,
      Map<String, Object> predeclared,
      StarlarkThread thread)
      throws SyntaxError.Exception, EvalException, InterruptedException {
    Module module = Module.withPredeclared(thread.getSemantics(), predeclared);
    return eval(input, options, module, thread);
  }

  /**
   * Parses the input as an expression, resolves it in the specified module environment, and returns
   * a callable no-argument Starlark function value that computes and returns the value of the
   * expression.
   *
   * @throws SyntaxError.Exception if there were scanner, parser, or resolver errors.
   */
  private static StarlarkFunction newExprFunction(
      ParserInput input,
      FileOptions options,
      Module module,
      SymbolGenerator.Symbol<?> referenceIdentity)
      throws SyntaxError.Exception {
    Expression expr = Expression.parse(input);
    Program prog = Program.compileExpr(expr, module, options);
    Resolver.Function rfn = prog.getResolvedFunction();
    int[] globalIndex = module.getIndicesOfGlobals(rfn.getGlobals()); // see execFileProgram
    return new StarlarkFunction(
        rfn,
        module,
        globalIndex,
        /* defaultValues= */ Tuple.empty(),
        /* freevars= */ Tuple.empty(),
        referenceIdentity);
  }

  /**
   * Starts the CPU profiler with the specified sampling period, writing a pprof profile to {@code
   * out}. All running Starlark threads are profiled. May be called concurrent with Starlark
   * execution.
   *
   * @throws IllegalStateException exception if the Starlark profiler is already running or if the
   *     operating system's profiling resources for this process are already in use.
   */
  public static boolean startCpuProfile(OutputStream out, Duration period) {
    return CpuProfiler.start(out, period);
  }

  /**
   * Stops the profiler and waits for the log to be written. Throws an unchecked exception if the
   * profiler was not already started by a prior call to {@link #startCpuProfile}.
   */
  public static void stopCpuProfile() throws IOException {
    CpuProfiler.stop();
  }
}
