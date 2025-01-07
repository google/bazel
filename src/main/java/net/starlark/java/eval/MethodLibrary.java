// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.Streams.stream;
import static java.util.Comparator.comparing;

import com.google.common.base.Ascii;
import com.google.common.base.Throwables;
import com.google.common.collect.Ordering;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/** The universal predeclared functions of core Starlark. */
class MethodLibrary {

  @StarlarkMethod(
      name = "min",
      doc =
          "Returns the smallest one of all given arguments. If only one positional argument is"
              + " provided, it must be a non-empty iterable. It is an error if elements are not"
              + " comparable (for example int with string), or if no arguments are given."
              + "<pre class=\"language-python\">\n" //
              + "min(2, 5, 4) == 2\n"
              + "min([5, 6, 3]) == 3\n"
              + "min(\"six\", \"three\", \"four\", key = len) == \"six\"  # the shortest\n"
              + "min([2, -2, -1, 1], key = abs) == -1  # the first encountered with minimal key"
              + " value\n"
              + "</pre>",
      extraPositionals = @Param(name = "args", doc = "The elements to be checked."),
      parameters = {
        @Param(
            name = "key",
            named = true,
            positional = false,
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None")
      },
      useStarlarkThread = true)
  public Object min(Object key, Sequence<?> args, StarlarkThread thread)
      throws EvalException, InterruptedException {
    return findExtreme(
        args,
        Starlark.toJavaOptional(key, StarlarkCallable.class),
        Starlark.ORDERING.reverse(),
        thread);
  }

  @StarlarkMethod(
      name = "max",
      doc =
          "Returns the largest one of all given arguments. If only one positional argument is"
              + " provided, it must be a non-empty iterable.It is an error if elements are not"
              + " comparable (for example int with string), or if no arguments are given."
              + "<pre class=\"language-python\">\n" //
              + "max(2, 5, 4) == 5\n"
              + "max([5, 6, 3]) == 6\n"
              + "max(\"two\", \"three\", \"four\", key = len) ==\"three\"  # the longest\n"
              + "max([1, -1, -2, 2], key = abs) == -2  # the first encountered with maximal key"
              + " value\n"
              + "</pre>",
      extraPositionals = @Param(name = "args", doc = "The elements to be checked."),
      parameters = {
        @Param(
            name = "key",
            named = true,
            positional = false,
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None")
      },
      useStarlarkThread = true)
  public Object max(Object key, Sequence<?> args, StarlarkThread thread)
      throws EvalException, InterruptedException {
    return findExtreme(
        args, Starlark.toJavaOptional(key, StarlarkCallable.class), Starlark.ORDERING, thread);
  }

  /** Returns the maximum element from this list, as determined by maxOrdering. */
  private static Object findExtreme(
      Sequence<?> args,
      Optional<StarlarkCallable> keyFn,
      Ordering<Object> maxOrdering,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    // Args can either be a list of items to compare, or a singleton list whose element is an
    // iterable of items to compare. In either case, there must be at least one item to compare.
    Iterable<?> items = (args.size() == 1) ? Starlark.toIterable(args.get(0)) : args;
    try {
      if (keyFn.isPresent()) {
        try {
          return stream(items)
              .map(value -> ValueWithComparisonKey.make(value, keyFn.get(), thread))
              .max(comparing(ValueWithComparisonKey::getComparisonKey, maxOrdering))
              .get()
              .getValue();
        } catch (ValueWithComparisonKey.KeyCallException ex) {
          Throwables.throwIfInstanceOf(ex.getCause(), EvalException.class);
          Throwables.throwIfInstanceOf(ex.getCause(), InterruptedException.class);
          throw new AssertionError("Got invalid ValueWithComparisonKey.KeyCallException", ex);
        }
      } else {
        return maxOrdering.max(items);
      }
    } catch (ClassCastException ex) {
      throw new EvalException(ex.getMessage()); // e.g. unsupported comparison: int <=> string
    } catch (NoSuchElementException ex) {
      throw new EvalException("expected at least one item", ex);
    }
  }

  /**
   * Original value decorated with its comparison key; storing the comparison key alongside the
   * value ensures that we call the comparison key computation function only once per original value
   * (which is important in case the function has side effects).
   */
  private static final class ValueWithComparisonKey {
    private final Object value;
    private final Object comparisonKey;

    private ValueWithComparisonKey(Object value, Object comparisonKey) {
      this.value = value;
      this.comparisonKey = comparisonKey;
    }

    /**
     * @throws KeyCallException wrapping the exception thrown by the underlying {@link
     *     Starlark#positionalOnlyCall} call if it threw.
     */
    static ValueWithComparisonKey make(
        Object value, StarlarkCallable keyFn, StarlarkThread thread) {
      try {
        return new ValueWithComparisonKey(value, Starlark.positionalOnlyCall(thread, keyFn, value));
      } catch (EvalException | InterruptedException ex) {
        throw new KeyCallException(ex);
      }
    }

    Object getValue() {
      return value;
    }

    Object getComparisonKey() {
      return comparisonKey;
    }

    /**
     * An unchecked exception wrapping an exception thrown by {@link Starlark#positionalOnlyCall}.
     */
    private static final class KeyCallException extends RuntimeException {
      KeyCallException(Exception cause) {
        super(cause);
      }
    }
  }

  @StarlarkMethod(
      name = "abs",
      doc =
          "Returns the absolute value of a number (a non-negative number with the same magnitude)."
              + "<pre class=\"language-python\">abs(-2.3) == 2.3</pre>",
      parameters = {
        @Param(
            name = "x",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = StarlarkFloat.class),
            },
            doc = "A number (int or float)")
      })
  public Object abs(Object x) throws EvalException {
    if (x instanceof StarlarkInt starlarkInt) {
      if (starlarkInt.signum() < 0) {
        return StarlarkInt.uminus(starlarkInt);
      }
      return x;
    }

    double value = ((StarlarkFloat) x).toDouble();
    return StarlarkFloat.of(Math.abs(value));
  }

  @StarlarkMethod(
      name = "all",
      doc =
          "Returns true if all elements evaluate to True or if the collection is empty. "
              + "Elements are converted to boolean using the <a href=\"#bool\">bool</a> function."
              + "<pre class=\"language-python\">all([\"hello\", 3, True]) == True\n"
              + "all([-1, 0, 1]) == False</pre>",
      parameters = {@Param(name = "elements", doc = "A string or a collection of elements.")})
  public boolean all(Object collection) throws EvalException {
    return !hasElementWithBooleanValue(collection, false);
  }

  @StarlarkMethod(
      name = "any",
      doc =
          "Returns true if at least one element evaluates to True. "
              + "Elements are converted to boolean using the <a href=\"#bool\">bool</a> function."
              + "<pre class=\"language-python\">any([-1, 0, 1]) == True\n"
              + "any([False, 0, \"\"]) == False</pre>",
      parameters = {@Param(name = "elements", doc = "A string or a collection of elements.")})
  public boolean any(Object collection) throws EvalException {
    return hasElementWithBooleanValue(collection, true);
  }

  private static boolean hasElementWithBooleanValue(Object seq, boolean value)
      throws EvalException {
    for (Object x : Starlark.toIterable(seq)) {
      if (Starlark.truth(x) == value) {
        return true;
      }
    }
    return false;
  }

  @StarlarkMethod(
      name = "sorted",
      doc =
          "Returns a new sorted list containing all the elements of the supplied iterable"
              + " sequence. An error may occur if any pair of elements x, y may not be compared"
              + " using x < y. The elements are sorted into ascending order, unless the reverse"
              + " argument is True, in which case the order is descending.\n"
              + " Sorting is stable: elements that compare equal retain their original relative"
              + " order.\n" //
              + "<pre class=\"language-python\">\n" //
              + "sorted([3, 5, 4]) == [3, 4, 5]\n" //
              + "sorted([3, 5, 4], reverse = True) == [5, 4, 3]\n" //
              + "sorted([\"two\", \"three\", \"four\"], key = len) == [\"two\", \"four\","
              + " \"three\"]  # sort by length\n" //
              + "</pre>",
      parameters = {
        @Param(name = "iterable", doc = "The iterable sequence to sort."),
        @Param(
            name = "key",
            named = true,
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            doc = "An optional function applied to each element before comparison.",
            defaultValue = "None"),
        @Param(
            name = "reverse",
            doc = "Return results in descending order.",
            named = true,
            defaultValue = "False",
            positional = false)
      },
      useStarlarkThread = true)
  public StarlarkList<?> sorted(
      StarlarkIterable<?> iterable, Object key, boolean reverse, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Object[] array = Starlark.toArray(iterable);
    Comparator<Object> order = reverse ? Starlark.ORDERING.reversed() : Starlark.ORDERING;

    // no key?
    if (key == Starlark.NONE) {
      try {
        Arrays.sort(array, order);
      } catch (ClassCastException ex) {
        throw Starlark.errorf("%s", ex.getMessage());
      }
      return StarlarkList.wrap(thread.mutability(), array);
    }

    // The user provided a key function.
    // We must call it exactly once per element, in order,
    // so use the decorate/sort/undecorate pattern.
    StarlarkCallable keyfn = (StarlarkCallable) key;

    // decorate
    for (int i = 0; i < array.length; i++) {
      Object v = array[i];
      Object k = Starlark.positionalOnlyCall(thread, keyfn, v);
      array[i] = new Object[] {k, v};
    }

    class KeyComparator implements Comparator<Object> {
      EvalException e;

      @Override
      public int compare(Object x, Object y) {
        Object xkey = ((Object[]) x)[0];
        Object ykey = ((Object[]) y)[0];
        try {
          return order.compare(xkey, ykey);
        } catch (ClassCastException e) {
          if (this.e == null) {
            this.e = new EvalException(e.getMessage());
          }
          return 0; // may cause Arrays.sort to fail; see below
        }
      }
    }

    // sort
    KeyComparator comp = new KeyComparator();
    try {
      Arrays.sort(array, comp);
    } catch (IllegalArgumentException unused) {
      // Arrays.sort failed because comp violated the Comparator contract.
      if (comp.e == null) {
        // There was no exception from order.compare.
        // Likely the application defined a Comparable type whose
        // compareTo is not a strict weak order.
        throw new IllegalStateException("sort: element ordering is not self-consistent");
      }
    }

    // Sort completed, possibly with deferred errors.
    if (comp.e != null) {
      throw comp.e;
    }

    // undecorate
    for (int i = 0; i < array.length; i++) {
      array[i] = ((Object[]) array[i])[1];
    }

    return StarlarkList.wrap(thread.mutability(), array);
  }

  private static void reverse(Object[] array) {
    for (int i = 0, j = array.length - 1; i < j; i++, j--) {
      Object tmp = array[i];
      array[i] = array[j];
      array[j] = tmp;
    }
  }

  @StarlarkMethod(
      name = "reversed",
      doc =
          "Returns a new, unfrozen list that contains the elements of the original iterable"
              + " sequence in reversed order.<pre class=\"language-python\">reversed([3, 5, 4]) =="
              + " [4, 5, 3]</pre>",
      parameters = {
        @Param(name = "sequence", doc = "The iterable sequence (e.g. list) to be reversed."),
      },
      useStarlarkThread = true)
  public StarlarkList<?> reversed(StarlarkIterable<?> sequence, StarlarkThread thread)
      throws EvalException {
    Object[] array = Starlark.toArray(sequence);
    reverse(array);
    return StarlarkList.wrap(thread.mutability(), array);
  }

  @StarlarkMethod(
      name = "tuple",
      doc =
          "Returns a tuple with the same elements as the given iterable value."
              + "<pre class=\"language-python\">tuple([1, 2]) == (1, 2)\n"
              + "tuple((2, 3, 2)) == (2, 3, 2)\n"
              + "tuple({5: \"a\", 2: \"b\", 4: \"c\"}) == (5, 2, 4)</pre>",
      parameters = {@Param(name = "x", defaultValue = "()", doc = "The object to convert.")})
  public Tuple tuple(StarlarkIterable<?> x) throws EvalException {
    if (x instanceof Tuple) {
      return (Tuple) x;
    }
    return Tuple.wrap(Starlark.toArray(x));
  }

  @StarlarkMethod(
      name = "list",
      doc =
          "Returns a new list with the same elements as the given iterable value."
              + "<pre class=\"language-python\">list([1, 2]) == [1, 2]\n"
              + "list((2, 3, 2)) == [2, 3, 2]\n"
              + "list({5: \"a\", 2: \"b\", 4: \"c\"}) == [5, 2, 4]</pre>",
      parameters = {@Param(name = "x", defaultValue = "[]", doc = "The object to convert.")},
      useStarlarkThread = true)
  public StarlarkList<?> list(StarlarkIterable<?> x, StarlarkThread thread) throws EvalException {
    return StarlarkList.wrap(thread.mutability(), Starlark.toArray(x));
  }

  @StarlarkMethod(
      name = "len",
      doc =
          "Returns the length of a string, sequence (such as a list or tuple), dict, set, or other"
              + " iterable.",
      parameters = {@Param(name = "x", doc = "The value whose length to report.")},
      useStarlarkThread = true)
  public int len(Object x, StarlarkThread thread) throws EvalException {
    int len = Starlark.len(x);
    if (len < 0) {
      throw Starlark.errorf("%s is not iterable", Starlark.type(x));
    }
    return len;
  }

  @StarlarkMethod(
      name = "str",
      doc =
          "Converts any object to string. This is useful for debugging."
              + "<pre class=\"language-python\">str(\"ab\") == \"ab\"\n"
              + "str(8) == \"8\"</pre>",
      parameters = {@Param(name = "x", doc = "The object to convert.")},
      useStarlarkThread = true)
  public String str(Object x, StarlarkThread thread) throws EvalException {
    return Starlark.str(x, thread.getSemantics());
  }

  @StarlarkMethod(
      name = "repr",
      doc =
          "Converts any object to a string representation. This is useful for debugging.<br>"
              + "<pre class=\"language-python\">repr(\"ab\") == '\"ab\"'</pre>",
      parameters = {@Param(name = "x", doc = "The object to convert.")})
  public String repr(Object x) {
    return Starlark.repr(x);
  }

  @StarlarkMethod(
      name = "bool",
      doc =
          "Constructor for the bool type. "
              + "It returns <code>False</code> if the object is <code>None</code>, <code>False"
              + "</code>, an empty string (<code>\"\"</code>), the number <code>0</code>, or an "
              + "empty collection (e.g. <code>()</code>, <code>[]</code>). "
              + "Otherwise, it returns <code>True</code>.",
      parameters = {@Param(name = "x", defaultValue = "False", doc = "The variable to convert.")})
  public boolean bool(Object x) throws EvalException {
    return Starlark.truth(x);
  }

  @StarlarkMethod(
      name = "float",
      doc =
          "Returns x as a float value. " //
              + "<ul><li>If <code>x</code> is already a float, <code>float</code> returns it"
              + " unchanged. " //
              + "<li>If <code>x</code> is a bool, <code>float</code> returns 1.0 for True and 0.0"
              + " for False. " //
              + "<li>If <code>x</code> is an int, <code>float</code> returns the nearest"
              + " finite floating-point value to x, or an error if the magnitude is too large. " //
              + "<li>If <code>x</code> is a string, it must be a valid floating-point literal, or"
              + " be equal (ignoring case) to <code>NaN</code>, <code>Inf</code>, or"
              + " <code>Infinity</code>, optionally preceded by a <code>+</code> or <code>-</code>"
              + " sign. " //
              + "</ul>" //
              + "Any other value causes an error. With no argument, <code>float()</code> returns"
              + " 0.0.",
      parameters = {
        @Param(name = "x", doc = "The value to convert.", defaultValue = "unbound"),
      })
  public StarlarkFloat floatForStarlark(Object x) throws EvalException {
    if (x instanceof String s) {
      if (s.isEmpty()) {
        throw Starlark.errorf("empty string");
      }

      double d;
      switch (Ascii.toLowerCase(s.charAt(s.length() - 1))) {
        case 'n':
        case 'f':
        case 'y': // {,+,-}{NaN,Inf,Infinity}
          // non-finite
          if (Ascii.equalsIgnoreCase(s, "nan")
              || Ascii.equalsIgnoreCase(s, "+nan")
              || Ascii.equalsIgnoreCase(s, "-nan")) {
            d = Double.NaN;
          } else if (Ascii.equalsIgnoreCase(s, "inf")
              || Ascii.equalsIgnoreCase(s, "+inf")
              || Ascii.equalsIgnoreCase(s, "+infinity")) {
            d = Double.POSITIVE_INFINITY;
          } else if (Ascii.equalsIgnoreCase(s, "-inf") || Ascii.equalsIgnoreCase(s, "-infinity")) {
            d = Double.NEGATIVE_INFINITY;
          } else {
            throw Starlark.errorf("invalid float literal: %s", s);
          }
          break;
        default:
          // finite
          try {
            d = Double.parseDouble(s);
            if (!Double.isFinite(d)) {
              // parseDouble accepts signed "NaN" and "Infinity" (case sensitive)
              // but we already handled those cases, so this indicates
              // a large number rounded to infinity.
              throw Starlark.errorf("floating-point number too large");
            }
          } catch (NumberFormatException unused) {
            throw Starlark.errorf("invalid float literal: %s", s);
          }
          break;
      } // switch
      return StarlarkFloat.of(d);

    } else if (x instanceof Boolean) {
      return StarlarkFloat.of(((Boolean) x).booleanValue() ? 1 : 0);

    } else if (x instanceof StarlarkInt) {
      return StarlarkFloat.of(((StarlarkInt) x).toFiniteDouble());

    } else if (x instanceof StarlarkFloat) {
      return (StarlarkFloat) x;

    } else if (x == Starlark.UNBOUND) {
      return StarlarkFloat.of(0.0);

    } else {
      throw Starlark.errorf("got %s, want string, int, float, or bool", Starlark.type(x));
    }
  }

  @StarlarkMethod(
      name = "int",
      doc =
          "Returns x as an int value."
              + "<ul>"
              + "<li>If <code>x</code> is already an int, <code>int</code> returns it unchanged." //
              + "<li>If <code>x</code> is a bool, <code>int</code> returns 1 for True and 0 for"
              + " False." //
              + "<li>If <code>x</code> is a string, it must have the format "
              + "    <code>&lt;sign&gt;&lt;prefix&gt;&lt;digits&gt;</code>. "
              + "    <code>&lt;sign&gt;</code> is either <code>\"+\"</code>, <code>\"-\"</code>, "
              + "    or empty (interpreted as positive). <code>&lt;digits&gt;</code> are a "
              + "    sequence of digits from 0 up to <code>base</code> - 1, where the letters a-z "
              + "    (or equivalently, A-Z) are used as digits for 10-35. In the case where "
              + "    <code>base</code> is 2/8/16, <code>&lt;prefix&gt;</code> is optional and may "
              + "    be 0b/0o/0x (or equivalently, 0B/0O/0X) respectively; if the "
              + "    <code>base</code> is any other value besides these bases or the special value "
              + "    0, the prefix must be empty. In the case where <code>base</code> is 0, the "
              + "    string is interpreted as an integer literal, in the sense that one of the "
              + "    bases 2/8/10/16 is chosen depending on which prefix if any is used. If "
              + "    <code>base</code> is 0, no prefix is used, and there is more than one digit, "
              + "    the leading digit cannot be 0; this is to avoid confusion between octal and "
              + "    decimal. The magnitude of the number represented by the string must be within "
              + "    the allowed range for the int type." //
              + "<li>If <code>x</code> is a float, <code>int</code> returns the integer value of"
              + "    the float, rounding towards zero. It is an error if x is non-finite (NaN or"
              + "    infinity)."
              + "</ul>" //
              + "This function fails if <code>x</code> is any other type, or if the value is a "
              + "string not satisfying the above format. Unlike Python's <code>int</code> "
              + "function, this function does not allow zero arguments, and does "
              + "not allow extraneous whitespace for string arguments.<p>" //
              + "Examples:<pre class=\"language-python\">int(\"123\") == 123\n"
              + "int(\"-123\") == -123\n"
              + "int(\"+123\") == 123\n"
              + "int(\"FF\", 16) == 255\n"
              + "int(\"0xFF\", 16) == 255\n"
              + "int(\"10\", 0) == 10\n"
              + "int(\"-0x10\", 0) == -16\n"
              + "int(\"-0x10\", 0) == -16\n"
              + "int(\"123.456\") == 123\n"
              + "</pre>",
      parameters = {
        @Param(name = "x", doc = "The string to convert."),
        @Param(
            name = "base",
            defaultValue = "unbound",
            doc =
                "The base used to interpret a string value; defaults to 10. Must be between 2 "
                    + "and 36 (inclusive), or 0 to detect the base as if <code>x</code> were an "
                    + "integer literal. This parameter must not be supplied if the value is not a "
                    + "string.",
            named = true)
      })
  public StarlarkInt intForStarlark(Object x, Object baseO) throws EvalException {
    if (x instanceof String) {
      int base = baseO == Starlark.UNBOUND ? 10 : Starlark.toInt(baseO, "base");
      try {
        return StarlarkInt.parse((String) x, base);
      } catch (NumberFormatException ex) {
        throw Starlark.errorf("%s", ex.getMessage());
      }
    }

    if (baseO != Starlark.UNBOUND) {
      throw Starlark.errorf("can't convert non-string with explicit base");
    }
    if (x instanceof Boolean) {
      return StarlarkInt.of(((Boolean) x).booleanValue() ? 1 : 0);
    } else if (x instanceof StarlarkInt) {
      return (StarlarkInt) x;
    } else if (x instanceof StarlarkFloat) {
      try {
        return StarlarkInt.ofFiniteDouble(((StarlarkFloat) x).toDouble());
      } catch (IllegalArgumentException unused) {
        throw Starlark.errorf("can't convert float %s to int", x);
      }
    }
    throw Starlark.errorf("got %s, want string, int, float, or bool", Starlark.type(x));
  }

  @StarlarkMethod(
      name = "dict",
      doc =
          "Creates a <a href=\"../core/dict.html\">dictionary</a> from an optional positional "
              + "argument and an optional set of keyword arguments. In the case where the same key "
              + "is given multiple times, the last value will be used. Entries supplied via "
              + "keyword arguments are considered to come after entries supplied via the "
              + "positional argument.",
      parameters = {
        @Param(
            name = "pairs",
            defaultValue = "[]",
            doc = "A dict, or an iterable whose elements are each of length 2 (key, value)."),
      },
      extraKeywords = @Param(name = "kwargs", doc = "Dictionary of additional entries."),
      useStarlarkThread = true)
  public Dict<?, ?> dict(Object pairs, Dict<String, Object> kwargs, StarlarkThread thread)
      throws EvalException {
    // common case: dict(k=v, ...)
    if (pairs instanceof StarlarkList && ((StarlarkList) pairs).isEmpty()) {
      return kwargs;
    }
    Dict<Object, Object> dict = Dict.of(thread.mutability());
    Dict.update("dict", dict, pairs, kwargs);
    return dict;
  }

  @StarlarkMethod(
      name = "set",
      doc =
          """
Creates a new <a href=\"../core/set.html\">set</a> containing the unique elements of a given
iterable, preserving iteration order.

<p>If called with no argument, <code>set()</code> returns a new empty set.

<p>For example,
<pre class=language-python>
set()                          # an empty set
set([3, 1, 1, 2])              # set([3, 1, 2]), a set of three elements
set({"k1": "v1", "k2": "v2"})  # set(["k1", "k2"]), a set of two elements
</pre>
""",
      parameters = {
        @Param(
            name = "elements",
            defaultValue = "[]",
            doc = "A set, a sequence of hashable values, or a dict."),
      },
      useStarlarkThread = true)
  public StarlarkSet<Object> set(Object elements, StarlarkThread thread) throws EvalException {
    // Ordinarily we would use StarlarkMethod#enableOnlyWithFlag, but this doesn't work for
    // top-level symbols, so enforce it here instead.
    if (!thread.getSemantics().getBool(StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
      throw Starlark.errorf("Use of set() requires --experimental_enable_starlark_set");
    }
    return StarlarkSet.checkedCopyOf(thread.mutability(), elements);
  }

  @StarlarkMethod(
      name = "enumerate",
      doc =
          "Returns a list of pairs (two-element tuples), with the index (int) and the item from"
              + " the input sequence.\n<pre class=\"language-python\">"
              + "enumerate([24, 21, 84]) == [(0, 24), (1, 21), (2, 84)]</pre>\n",
      parameters = {
        // Note Python uses 'sequence' keyword instead of 'list'. We may want to change tihs
        // some day.
        @Param(name = "list", doc = "input sequence.", named = true),
        @Param(name = "start", doc = "start index.", defaultValue = "0", named = true),
      },
      useStarlarkThread = true)
  public StarlarkList<?> enumerate(Object input, StarlarkInt startI, StarlarkThread thread)
      throws EvalException {
    int start = Starlark.toInt(startI, "start");
    Object[] array = Starlark.toArray(input);
    for (int i = 0; i < array.length; i++) {
      array[i] = Tuple.pair(StarlarkInt.of(i + start), array[i]); // update in place
    }
    return StarlarkList.wrap(thread.mutability(), array);
  }

  @StarlarkMethod(
      name = "hash",
      doc =
          "Return a hash value for a string. This is computed deterministically using the same "
              + "algorithm as Java's <code>String.hashCode()</code>, namely: "
              + "<pre class=\"language-python\">s[0] * (31^(n-1)) + s[1] * (31^(n-2)) + ... + "
              + "s[n-1]</pre> Hashing of values besides strings is not currently supported.",
      // Deterministic hashing is important for the consistency of builds, hence why we
      // promise a specific algorithm. This is in contrast to Java (Object.hashCode()) and
      // Python, which promise stable hashing only within a given execution of the program.
      parameters = {@Param(name = "value", doc = "String value to hash.")})
  public int hash(String value) throws EvalException {
    return value.hashCode();
  }

  @StarlarkMethod(
      name = "range",
      doc =
          "Creates a list where items go from <code>start</code> to <code>stop</code>, using a "
              + "<code>step</code> increment. If a single argument is provided, items will "
              + "range from 0 to that element."
              + "<pre class=\"language-python\">range(4) == [0, 1, 2, 3]\n"
              + "range(3, 9, 2) == [3, 5, 7]\n"
              + "range(3, 0, -1) == [3, 2, 1]</pre>",
      parameters = {
        @Param(
            name = "start_or_stop",
            doc =
                "Value of the start element if stop is provided, "
                    + "otherwise value of stop and the actual start is 0"),
        @Param(
            name = "stop_or_none",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "optional index of the first item <i>not</i> to be included in the resulting "
                    + "list; generation of the list stops before <code>stop</code> is reached."),
        @Param(
            name = "step",
            defaultValue = "1",
            doc = "The increment (default is 1). It may be negative.")
      },
      useStarlarkThread = true)
  public Sequence<StarlarkInt> range(
      StarlarkInt startOrStop, Object stopOrNone, StarlarkInt stepI, StarlarkThread thread)
      throws EvalException {
    int start;
    int stop;
    if (stopOrNone == Starlark.NONE) {
      start = 0;
      stop = startOrStop.toInt("stop");
    } else {
      start = startOrStop.toInt("start");
      stop = Starlark.toInt(stopOrNone, "stop");
    }
    int step = stepI.toInt("step");
    if (step == 0) {
      throw Starlark.errorf("step cannot be 0");
    }
    // TODO(adonovan): support arbitrary integers.
    return new RangeList(start, stop, step);
  }

  /** Returns true if the object has a field of the given name, otherwise false. */
  @StarlarkMethod(
      name = "hasattr",
      doc =
          "Returns True if the object <code>x</code> has an attribute or method of the given "
              + "<code>name</code>, otherwise False. Example:<br>"
              + "<pre class=\"language-python\">hasattr(ctx.attr, \"myattr\")</pre>",
      parameters = {
        @Param(name = "x", doc = "The object to check."),
        @Param(name = "name", doc = "The name of the attribute.")
      },
      useStarlarkThread = true)
  public boolean hasattr(Object obj, String name, StarlarkThread thread) throws EvalException {
    return Starlark.hasattr(thread.getSemantics(), obj, name);
  }

  @StarlarkMethod(
      name = "getattr",
      doc =
          "Returns the struct's field of the given name if it exists. If not, it either returns "
              + "<code>default</code> (if specified) or raises an error. "
              + "<code>getattr(x, \"foobar\")</code> is equivalent to <code>x.foobar</code>."
              + "<pre class=\"language-python\">getattr(ctx.attr, \"myattr\")\n"
              + "getattr(ctx.attr, \"myattr\", \"mydefault\")</pre>",
      parameters = {
        @Param(name = "x", doc = "The struct whose attribute is accessed."),
        @Param(name = "name", doc = "The name of the struct attribute."),
        @Param(
            name = "default",
            defaultValue = "unbound",
            doc =
                "The default value to return in case the struct "
                    + "doesn't have an attribute of the given name.")
      },
      useStarlarkThread = true)
  public Object getattr(Object obj, String name, Object defaultValue, StarlarkThread thread)
      throws EvalException, InterruptedException {
    return Starlark.getattr(
        thread.mutability(),
        thread.getSemantics(),
        obj,
        name,
        defaultValue == Starlark.UNBOUND ? null : defaultValue);
  }

  @StarlarkMethod(
      name = "dir",
      doc =
          "Returns a list of strings: the names of the attributes and "
              + "methods of the parameter object.",
      parameters = {@Param(name = "x", doc = "The object to check.")},
      useStarlarkThread = true)
  public StarlarkList<?> dir(Object object, StarlarkThread thread) throws EvalException {
    return Starlark.dir(thread.mutability(), thread.getSemantics(), object);
  }

  @StarlarkMethod(
      name = "fail",
      doc = "Causes execution to fail with an error.",
      parameters = {
        // TODO(adonovan): remove. See https://github.com/bazelbuild/starlark/issues/47.
        @Param(
            name = "msg",
            doc =
                "Deprecated: use positional arguments instead. "
                    + "This argument acts like an implicit leading positional argument.",
            defaultValue = "None",
            positional = false,
            named = true),
        // TODO(adonovan): remove. See https://github.com/bazelbuild/starlark/issues/47.
        @Param(
            name = "attr",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "Deprecated. Causes an optional prefix containing this string to be added to the"
                    + " error message.",
            positional = false,
            named = true),
        @Param(
            name = "sep",
            defaultValue = "\" \"",
            named = true,
            positional = false,
            doc = "The separator string between the objects, default is space (\" \").")
      },
      extraPositionals =
          @Param(
              name = "args",
              doc =
                  "A list of values, formatted with debugPrint (which is equivalent to str by"
                      + " default) and joined with sep (defaults to \" \"), that appear in the"
                      + " error message."),
      useStarlarkThread = true)
  public void fail(Object msg, Object attr, String sep, Tuple args, StarlarkThread thread)
      throws EvalException {
    Printer printer = new Printer();
    boolean needSeparator = false;
    if (attr != Starlark.NONE) {
      printer.append("attribute ").append((String) attr).append(":");
      needSeparator = true;
    }
    // msg acts like a leading element of args.
    if (msg != Starlark.NONE) {
      if (needSeparator) {
        printer.append(sep);
      }
      printer.debugPrint(msg, thread);
      needSeparator = true;
    }
    for (Object arg : args) {
      if (needSeparator) {
        printer.append(sep);
      }
      printer.debugPrint(arg, thread);
      needSeparator = true;
    }
    throw Starlark.errorf("%s", printer.toString());
  }

  @StarlarkMethod(
      name = "print",
      doc =
          "Prints <code>args</code> as debug output. It will be prefixed with the string <code>"
              + "\"DEBUG\"</code> and the location (file and line number) of this call. The "
              + "exact way in which the arguments are converted to strings is unspecified and may "
              + "change at any time. In particular, it may be different from (and more detailed "
              + "than) the formatting done by <a href='#str'><code>str()</code></a> and <a "
              + "href='#repr'><code>repr()</code></a>."
              + "<p>Using <code>print</code> in production code is discouraged due to the spam it "
              + "creates for users. For deprecations, prefer a hard error using <a href=\"#fail\">"
              + "<code>fail()</code></a> whenever possible.",
      parameters = {
          @Param(
              name = "sep",
              defaultValue = "\" \"",
              named = true,
              positional = false,
              doc = "The separator string between the objects, default is space (\" \")."),
          @Param(
              name = "with_trace",
              defaultValue = "False",
              named = true,
              positional = false,
              doc = "If True, a full stack trace is printed similar to <code>fail()</code>.")
      },
      // NB: as compared to Python3, we're missing optional named-only arguments 'end' and 'file'
      extraPositionals = @Param(name = "args", doc = "The objects to print."),
      useStarlarkThread = true)
  public void print(String sep, boolean withTrace, Sequence<?> args, StarlarkThread thread)
      throws EvalException {
    Printer p = new Printer();
    String separator = "";
    for (Object x : args) {
      p.append(separator);
      p.debugPrint(x, thread);
      separator = sep;
    }
    // The PRINT_TEST_MARKER key is used in tests to verify the effects of command-line options.
    // See starlark_flag_test.sh, which runs bazel with --internal_starlark_flag_test_canary.
    if (thread.getSemantics().getBool(StarlarkSemantics.PRINT_TEST_MARKER)) {
      p.append("<== Starlark flag test ==>");
    }

    String toPrint;
    if (withTrace) {
      toPrint = EvalException.formatCallStack(
          thread.getCallStack(), p.toString(), EvalException.newSourceReader(), /*isPrint=*/true);
    } else {
      toPrint = p.toString();
    }
    thread.getPrintHandler().print(thread, toPrint);
  }

  @StarlarkMethod(
      name = "type",
      doc =
          "Returns the type name of its argument. This is useful for debugging and "
              + "type-checking. Examples:"
              + "<pre class=\"language-python\">"
              + "type(2) == \"int\"\n"
              + "type([1]) == \"list\"\n"
              + "type(struct(a = 2)) == \"struct\""
              + "</pre>"
              + "This function might change in the future. To write Python-compatible code and "
              + "be future-proof, use it only to compare return values: "
              + "<pre class=\"language-python\">"
              + "if type(x) == type([]):  # if x is a list"
              + "</pre>",
      parameters = {@Param(name = "x", doc = "The object to check type of.")})
  public String type(Object object) {
    // There is no 'type' type in Starlark, so we return a string with the type name.
    return Starlark.type(object);
  }

  @StarlarkMethod(
      name = "zip",
      doc =
          "Returns a <code>list</code> of <code>tuple</code>s, where the i-th tuple contains "
              + "the i-th element from each of the argument sequences or iterables. The list has "
              + "the size of the shortest input. With a single iterable argument, it returns a "
              + "list of 1-tuples. With no arguments, it returns an empty list. Examples:"
              + "<pre class=\"language-python\">"
              + "zip()  # == []\n"
              + "zip([1, 2])  # == [(1,), (2,)]\n"
              + "zip([1, 2], [3, 4])  # == [(1, 3), (2, 4)]\n"
              + "zip([1, 2], [3, 4, 5])  # == [(1, 3), (2, 4)]</pre>",
      extraPositionals = @Param(name = "args", doc = "lists to zip."),
      useStarlarkThread = true)
  public StarlarkList<?> zip(Sequence<?> args, StarlarkThread thread) throws EvalException {
    StarlarkList<Tuple> result = StarlarkList.newList(thread.mutability());
    int ncols = args.size();
    if (ncols > 0) {
      Iterator<?>[] iterators = new Iterator<?>[ncols];
      for (int i = 0; i < ncols; i++) {
        iterators[i] = Starlark.toIterable(args.get(i)).iterator();
      }
      rows:
      for (; ; ) {
        Object[] elem = new Object[ncols];
        for (int i = 0; i < ncols; i++) {
          Iterator<?> it = iterators[i];
          if (!it.hasNext()) {
            break rows;
          }
          elem[i] = it.next();
        }
        result.addElement(Tuple.wrap(elem));
      }
    }
    return result;
  }

  /** Starlark bool type. */
  @StarlarkBuiltin(
      name = "bool",
      category = "core",
      doc =
          "A type to represent booleans. There are only two possible values: "
              + "True and False. Any value can be converted to a boolean using the "
              + "<a href=\"../globals/all.html#bool\">bool</a> function.")
  static final class BoolModule implements StarlarkValue {} // (documentation only)
}
