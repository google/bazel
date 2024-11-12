// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.collect.nestedset;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.GlobalMethods;
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Debug;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.FrozenDict;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * A Depset is a Starlark value that wraps a {@link NestedSet}.
 *
 * <p>A NestedSet has a type parameter that describes, at compile time, the elements of the set. By
 * contrast, a Depset has a value, {@link #getElementType}, that describes the elements during
 * execution. This type symbol permits the element type of a Depset value to be queried, after the
 * type parameter has been erased, without visiting each element of the often-vast data structure.
 *
 * <p>For depsets constructed by Starlark code, the element type of a non-empty {@code Depset} is
 * determined by its first element. All elements must have the same type. An empty depset has type
 * {@code ElementType.EMPTY}, and may be combined with any other depset.
 *
 * <p>Every call to {@code depset} returns a distinct instance equal to no other.
 */
@StarlarkBuiltin(
    name = "depset",
    category = DocCategory.BUILTIN,
    doc =
        "<p>A specialized data structure that supports efficient merge operations and has a"
            + " defined traversal order. Commonly used for accumulating data from transitive"
            + " dependencies in rules and aspects. For more information see <a"
            + " href=\"/extending/depsets\">here</a>."
            + " <p>The elements of a depset must be hashable and all of the same type (as"
            + " defined by the built-in type(x) function), but depsets are not simply"
            + " hash sets and do not support fast membership tests."
            + " If you need a general set datatype, you can"
            + " simulate one using a dictionary where all keys map to <code>True</code>."
            + "<p>Depsets are immutable. They should be created using their <a"
            + " href=\"../globals/bzl.html#depset\">constructor function</a> and merged or"
            + " augmented with other depsets via the <code>transitive</code> argument. "
            + "<p>The <code>order</code> parameter determines the"
            + " kind of traversal that is done to convert the depset to an iterable. There are"
            + " four possible values:"
            + "<ul><li><code>\"default\"</code> (formerly"
            + " <code>\"stable\"</code>): Order is unspecified (but"
            + " deterministic).</li>"
            + "<li><code>\"postorder\"</code> (formerly"
            + " <code>\"compile\"</code>): A left-to-right post-ordering. Precisely, this"
            + " recursively traverses all children leftmost-first, then the direct elements"
            + " leftmost-first.</li>"
            + "<li><code>\"preorder\"</code> (formerly"
            + " <code>\"naive_link\"</code>): A left-to-right pre-ordering. Precisely, this"
            + " traverses the direct elements leftmost-first, then recursively traverses the"
            + " children leftmost-first.</li>"
            + "<li><code>\"topological\"</code> (formerly"
            + " <code>\"link\"</code>): A topological ordering from the root down to the leaves."
            + " There is no left-to-right guarantee.</li>"
            + "</ul>"
            + "<p>Two depsets may only be merged if"
            + " either both depsets have the same order, or one of them has"
            + " <code>\"default\"</code> order. In the latter case the resulting depset's order"
            + " will be the same as the other's order."
            + "<p>Depsets may contain duplicate values but"
            + " these will be suppressed when iterating (using <code>to_list()</code>). Duplicates"
            + " may interfere with the ordering semantics.")
@Immutable
public final class Depset implements StarlarkValue, Debug.ValueWithDebugAttributes {
  // The value of elemClass is set to getTypeClass(actualElemClass)
  // null is used for empty Depset-s
  @Nullable private final Class<?> elemClass;
  private final NestedSet<?> set;

  Depset(@Nullable Class<?> elemClass, NestedSet<?> set) {
    this.elemClass = elemClass;
    this.set = set;
  }

  private static void checkElement(Object x, boolean strict) throws EvalException {
    // Historically the requirement for a depset element was isImmutable(x).
    // However, this check is neither necessary not sufficient.
    // It is unnecessary because elements need only be hashable,
    // as with dicts, whose keys may be mutable so long as mutations
    // don't affect the hash code. (Elements of a NestedSet must be
    // hashable because a hash-based set is used to de-duplicate
    // elements during iteration.)
    // And it is insufficient because some values are immutable
    // but not Starlark-hashable, such as frozen lists.
    // NestedSet calls its hashCode method regardless.
    //
    // TODO(adonovan): use this check instead:
    //   EvalUtils.checkHashable(x);
    // and delete the StarlarkValue.isImmutable and Starlark.isImmutable.
    // Unfortunately this is a breaking change because some users
    // construct depsets whose elements contain lists of strings,
    // which are Starlark-unhashable even if frozen.
    // TODO(adonovan): also remove StarlarkList.hashCode.
    if (strict && !Starlark.isImmutable(x)) {
      // TODO(adonovan): improve this error message to include type(x).
      throw Starlark.errorf("depset elements must not be mutable values");
    }

    // Even the looser regime forbids the top-level class to be list or dict.
    if (x instanceof StarlarkList || (x instanceof Dict && !(x instanceof FrozenDict))) {
      throw Starlark.errorf("depsets cannot contain items of type '%s'", Starlark.type(x));
    }
  }

  /** Returns a Depset that wraps the specified NestedSet. */
  // TODO(adonovan): enforce that we never construct a Depset with a StarlarkType
  // that represents a non-Starlark type (e.g. NestedSet<PathFragment>).
  // One way to do that is to disallow constructing StarlarkTypes for classes
  // that would fail Starlark.valid; however remains the problem that
  // Object.class means "any Starlark value" but in fact allows any Java value.
  public static <T> Depset of(Class<T> elemClass, NestedSet<T> set) {
    Preconditions.checkNotNull(elemClass, "elemClass cannot be null");
    if (set.isEmpty()) {
      return set.getOrder().emptyDepset();
    }
    return new Depset(ElementType.getTypeClass(elemClass), set);
  }

  /**
   * Checks that an element with {@code newElemType} is permitted in a set of {@code
   * existingElemType}.
   *
   * <p>{@code existingElemType} may be null, corresponding to a set that does not yet have any
   * elements.
   *
   * <p>Both Class-es should be returned by getTypeClass(cls).
   *
   * @return the (non-null) element type for a new set that adds the element
   * @throws EvalException if the new type is not permitted
   */
  private static Class<?> checkType(@Nullable Class<?> existingElemType, Class<?> newElemType)
      throws EvalException {
    // An initially empty depset (existingElemType == null) takes its type from the first element
    // added.
    // Otherwise, the types of the item and depset must match exactly.
    Preconditions.checkNotNull(newElemType);
    if (existingElemType == null || existingElemType == newElemType) {
      return newElemType;
    }
    throw Starlark.errorf(
        "cannot add an item of type '%s' to a depset of '%s'",
        ElementType.of(newElemType), ElementType.of(existingElemType));
  }

  /**
   * Returns the embedded {@link NestedSet}, first asserting that its elements are instances of the
   * given class, which must be a valid Starlark type (or Object.class). Only the top-level class is
   * verified.
   *
   * <p>If you do not specifically need the {@code NestedSet} and you are going to flatten it
   * anyway, prefer {@link #toList} to make your intent clear.
   *
   * @param type a {@link Class} representing the expected type of the elements
   * @return the {@code NestedSet}, with the appropriate generic type
   * @throws TypeException if the type does not accurately describe all elements
   */
  public <T> NestedSet<T> getSet(Class<T> type) throws TypeException {
    ElementType elemType = getElementType();
    if (!set.isEmpty() && !elemType.canBeCastTo(type)) {
      throw new TypeException(
          String.format(
              "got a depset of '%s', expected a depset of '%s'",
              elemType, Starlark.classType(type)));
    }
    @SuppressWarnings("unchecked")
    NestedSet<T> res = (NestedSet<T>) set;
    return res;
  }

  /**
   * Returns the embedded {@link NestedSet} without asserting the type of its elements---and thus
   * cannot fail. To validate the type of elements in the set, call {@link #getSet(Class)} instead.
   */
  public NestedSet<?> getSet() {
    return set;
  }

  /**
   * Returns an ImmutableList containing the set elements, asserting that each element is an
   * instance of class {@code type}. Requires traversing the entire graph of the underlying
   * NestedSet.
   *
   * @param type a {@link Class} representing the expected type of the elements, which must be a
   *     valid Starlark type (or Object.class)
   * @throws TypeException if the type does not accurately describe all elements
   */
  public <T> ImmutableList<T> toList(Class<T> type) throws TypeException {
    return getSet(type).toList();
  }

  /**
   * Returns an ImmutableList containing the set elements. Requires traversing the entire graph of
   * the underlying NestedSet.
   */
  public ImmutableList<?> toList() {
    return set.toList();
  }

  /**
   * Casts a non-null Starlark value {@code x} to a {@code Depset} and returns its underlying {@code
   * NestedSet<T>} (where {@code type} reifies {@code T}).
   *
   * <p>It may be assumed that all elements of the depset are of type {@code T}, but no actual
   * iteration takes place.
   *
   * <p>If {@code x} is not a depset or does not have the right element type, this throws an {@code
   * EvalException} whose message includes {@code what}, ideally a string literal, as a description
   * of the role of {@code x}.
   *
   * @throws IllegalArgumentException if {@code type} is not a valid Starlark type (or Object.class)
   */
  public static <T> NestedSet<T> cast(Object x, Class<T> type, String what) throws EvalException {
    if (!(x instanceof Depset)) {
      throw Starlark.errorf(
          "for %s, got %s, want a depset of %s", what, Starlark.type(x), Starlark.classType(type));
    }
    try {
      return ((Depset) x).getSet(type);
    } catch (TypeException ex) {
      throw Starlark.errorf("for '%s', %s", what, ex.getMessage());
    }
  }

  /** Like {@link #cast}, but if x is None, returns an empty stable-order NestedSet. */
  public static <T> NestedSet<T> noneableCast(Object x, Class<T> type, String what)
      throws EvalException {
    if (x == Starlark.NONE) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    return cast(x, type, what);
  }

  public boolean isEmpty() {
    return set.isEmpty();
  }

  @Override
  public boolean truth() {
    return !set.isEmpty();
  }

  public ElementType getElementType() {
    if (elemClass == null) {
      return ElementType.EMPTY;
    }
    return ElementType.of(elemClass);
  }

  @Nullable
  public Class<?> getElementClass() {
    return elemClass;
  }

  @Override
  public String toString() {
    return Starlark.repr(this);
  }

  public Order getOrder() {
    return set.getOrder();
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public void repr(Printer printer) {
    printer.append("depset(");
    printer.printList(set.toList(), "[", ", ", "]");
    Order order = getOrder();
    if (order != Order.STABLE_ORDER) {
      printer.append(", order = ");
      printer.repr(order.getStarlarkName());
    }
    printer.append(")");
  }

  @Override
  public ImmutableList<Debug.DebugAttribute> getDebugAttributes() {
    return ImmutableList.of(
        new Debug.DebugAttribute("order", getOrder().getStarlarkName()),
        new Debug.DebugAttribute("directs", set.getLeaves()),
        new Debug.DebugAttribute("transitives", set.getNonLeaves()));
  }

  @StarlarkMethod(
      name = "to_list",
      doc =
          "Returns a list of the elements, without duplicates, in the depset's traversal order. "
              + "Note that order is unspecified (but deterministic) for elements that were added "
              + "more than once to the depset. Order is also unspecified for <code>\"default\""
              + "</code>-ordered depsets, and for elements of child depsets whose order differs "
              + "from that of the parent depset. The list is a copy; modifying it has no effect "
              + "on the depset and vice versa.",
      useStarlarkThread = true)
  public StarlarkList<Object> toListForStarlark(StarlarkThread thread) throws EvalException {
    return StarlarkList.copyOf(thread.mutability(), this.toList());
  }

  /** Create a Depset from the given direct and transitive components. */
  public static Depset fromDirectAndTransitive(
      Order order, List<Object> direct, List<Depset> transitive, boolean strict)
      throws EvalException {
    NestedSetBuilder<Object> builder = new NestedSetBuilder<>(order);
    Class<?> type = null;

    // Check direct elements' type is equal to elements already added.
    for (Object x : direct) {
      // Historically, checkElement was called only by some depset constructors,
      // but not this one, depset(direct=[x]).
      // This was a regrettable oversight that allowed users to put mutable values
      // such as lists into depsets, doubly so because we have just forced our
      // users to migrate away from the legacy constructor which applied the check.
      //
      // We are currently discovering and fixing existing violations, for example
      // marking the relevant Starlark types as immutable where appropriate
      // (e.g. ConfiguredTarget), but violations are numerous so we must
      // suppress the checkElement call below and reintroduce it as a breaking change.
      // See b/144992997 or github.com/bazelbuild/bazel/issues/10289.
      checkElement(x, /*strict=*/ strict);

      Class<?> xt = ElementType.getTypeClass(x.getClass());
      type = checkType(type, xt);
    }
    builder.addAll(direct);

    // Add transitive sets, checking that type is equal to elements already added.
    for (Depset x : transitive) {
      if (!x.isEmpty()) {
        type = checkType(type, x.elemClass);
        if (!order.isCompatible(x.getOrder())) {
          throw Starlark.errorf(
              "Order '%s' is incompatible with order '%s'",
              order.getStarlarkName(), x.getOrder().getStarlarkName());
        }
        builder.addTransitive(x.getSet());
      }
    }

    if (builder.isEmpty()) {
      return builder.getOrder().emptyDepset();
    }
    NestedSet<Object> set = builder.build();
    // If the nested set was optimized to one of the transitive elements, reuse the corresponding
    // depset.
    for (Depset x : transitive) {
      if (x.getSet() == set) {
        return x;
      }
    }

    return new Depset(type, set);
  }

  /** An exception thrown when validation fails on the type of elements of a nested set. */
  public static class TypeException extends Exception {
    TypeException(String message) {
      super(message);
    }
  }

  /**
   * A ElementType represents the type of elements in a Depset.
   *
   * <p>Call {@link #of} to obtain the ElementType for a Java class. The class must be a legal
   * Starlark value class, such as String, Boolean, or a subclass of StarlarkValue.
   *
   * <p>An element type represents only the top-most type identifier of an element value. That is,
   * an element type may represent "list" but not "list of string".
   */
  // TODO(adonovan): consider deleting this class entirely and using Class directly.
  // Depset.getElementType would need to document "null means empty",
  // but almost every caller just wants to stringify it.
  @Immutable
  public static final class ElementType {

    @Nullable private final Class<?> cls; // null => empty depset

    private ElementType(@Nullable Class<?> cls) {
      this.cls = cls;
    }

    /** The element type of the empty depset. */
    public static final ElementType EMPTY = new ElementType(null);

    /** The element type of a depset of strings. */
    public static final ElementType STRING = of(String.class);

    @Override
    public String toString() {
      return cls == null ? "empty" : Starlark.classType(cls);
    }

    /**
     * Returns the type symbol for a depset whose elements are instances of {@code cls}.
     *
     * @throws IllegalArgumentException if {@code cls} is not a legal Starlark value class.
     */
    public static ElementType of(Class<?> cls) {
      return new ElementType(getTypeClass(cls));
    }

    // If cls is a valid Starlark type, returns the canonical Java class for that
    // Starlark type (which may be an ancestor); otherwise throws IllegalArgumentException.
    //
    // If cls is String or Boolean, cls is returned. Otherwise, the
    // @StarlarkBuiltin-annotated ancestor of cls is returned if it exists (it may
    // be cls itself), or cls is returned if there is no such ancestor.
    //
    // TODO(adonovan): consider publishing something like this as Starlark.typeClass.
    private static Class<?> getTypeClass(Class<?> cls) {
      if (cls == String.class || cls == Boolean.class) {
        return cls; // fast path for common case
      }
      if (cls == StarlarkInt.class) {
        // StarlarkInt doesn't currently have a StarlarkBuiltin annotation
        // because stardoc can't handle a type and a function with the same name.
        return cls;
      }
      Class<?> superclass = StarlarkAnnotations.getParentWithStarlarkBuiltin(cls);
      if (superclass != null) {
        return superclass;
      }
      if (!StarlarkValue.class.isAssignableFrom(cls)) {
        throw new IllegalArgumentException(
            "invalid Depset element type: "
                + cls.getName()
                + " is not a subclass of StarlarkValue");
      }
      return cls;
    }

    // Called by precondition check of Depset.getSet conversion.
    //
    // Fails if cls is neither Object.class nor a valid Starlark value class.
    // One might expect that if a ElementType canBeCastTo Integer, then it can
    // also be cast to Number, but this is not the case: getTypeClass throws IAE if
    // passed a supertype of a Starlark class that is not itself a valid Starlark
    // value class. As a special case, Object.class is permitted,
    // and represents "any value".
    //
    // This leads one to wonder why canBeCastTo calls getTypeClass at all.
    // The answer is that it is yet another hack to support starlarkbuildapi.
    // For example, (FileApi).canBeCastTo(Artifact.class) reports true,
    // because a Depset whose elements are nominally of type FileApi is assumed
    // to actually contain only elements of class Artifact. If there were
    // a second implementation of FileAPI, the operation would be unsafe.
    //
    // TODO(adonovan): once starlarkbuildapi has been deleted, eliminate the
    // getTypeClass calls here and in ElementType.of, and remove the special
    // case for Object.class since isAssignableFrom will allow any supertype
    // of the element type, whether or not it is a Starlark value class.
    private boolean canBeCastTo(Class<?> cls) {
      return this.cls == null
          || cls == Object.class // historical exception
          || getTypeClass(cls).isAssignableFrom(this.cls);
    }

    @Override
    public int hashCode() {
      return cls == null ? 0 : cls.hashCode();
    }

    @Override
    public boolean equals(Object that) {
      return that instanceof ElementType && this.cls == ((ElementType) that).cls;
    }
  }

  /** Implementation of the {@code depset()} callable. */
  private static Depset depset(
      String orderString, Object direct, Object transitive, StarlarkSemantics semantics)
      throws EvalException {
    Order order;
    try {
      order = Order.parse(orderString);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(ex);
    }

    Depset result =
        fromDirectAndTransitive(
            order,
            Sequence.noneableCast(direct, Object.class, "direct"),
            Sequence.noneableCast(transitive, Depset.class, "transitive"),
            semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS));

    // check depth limit
    int depth = result.getSet().getApproxDepth();
    int limit = semantics.get(BuildLanguageOptions.NESTED_SET_DEPTH_LIMIT);
    if (depth > limit) {
      throw Starlark.errorf("depset depth %d exceeds limit (%d)", depth, limit);
    }

    return result;
  }

  // Delegate equality to the underlying NestedSet. Otherwise, it's possible to create multiple
  // Depset instances wrapping the same NestedSet that aren't equal to each other.

  @Override
  public int hashCode() {
    return set.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Depset && set.equals(((Depset) other).set);
  }

  /** The user-facing API to the {@code depset} callable. */
  @GlobalMethods(environment = {Environment.BUILD, Environment.BZL})
  public static final class DepsetLibrary {

    private DepsetLibrary() {}

    public static final DepsetLibrary INSTANCE = new DepsetLibrary();

    @StarlarkMethod(
        name = "depset",
        doc =
            "Creates a <a href=\"../builtins/depset.html\">depset</a>. The <code>direct</code>"
                + " parameter is a list of direct elements of the depset, and"
                + " <code>transitive</code> parameter is a list of depsets whose elements become"
                + " indirect elements of the created depset. The order in which elements are"
                + " returned when the depset is converted to a list is specified by the"
                + " <code>order</code> parameter. See the <a"
                + " href=\"https://bazel.build/extending/depsets\">Depsets overview</a> for more"
                + " information.\n" //
                + "<p>All"
                + " elements (direct and indirect) of a depset must be of the same type, as"
                + " obtained by the expression <code>type(x)</code>.\n" //
                + "<p>Because a hash-based set is used to eliminate duplicates during iteration,"
                + " all elements of a depset should be hashable. However, this invariant is not"
                + " currently checked consistently in all constructors. Use the"
                + " --incompatible_always_check_depset_elements flag to enable consistent"
                + " checking; this will be the default behavior in future releases;  see <a"
                + " href='https://github.com/bazelbuild/bazel/issues/10313'>Issue 10313</a>.\n" //
                + "<p>In addition, elements must currently be immutable, though this restriction"
                + " will be relaxed in future.\n" //
                + "<p> The order of the created depset should be <i>compatible</i> with the order"
                + " of its <code>transitive</code> depsets. <code>\"default\"</code> order is"
                + " compatible with any other order, all other orders are only compatible with"
                + " themselves.",
        parameters = {
          // TODO(cparsons): Make 'order' keyword-only.
          @Param(
              name = "direct",
              defaultValue = "None",
              named = true,
              allowedTypes = {
                @ParamType(type = Sequence.class),
                @ParamType(type = NoneType.class),
              },
              doc = "A list of <i>direct</i> elements of a depset. "),
          @Param(
              name = "order",
              defaultValue = "\"default\"",
              doc =
                  "The traversal strategy for the new depset. See "
                      + "<a href=\"../builtins/depset.html\">here</a> for the possible values.",
              named = true),
          @Param(
              name = "transitive",
              named = true,
              positional = false,
              allowedTypes = {
                @ParamType(type = Sequence.class, generic1 = Depset.class),
                @ParamType(type = NoneType.class),
              },
              doc = "A list of depsets whose elements will become indirect elements of the depset.",
              defaultValue = "None"),
        },
        useStarlarkThread = true)
    public Depset depset(
        Object direct, String orderString, Object transitive, StarlarkThread thread)
        throws EvalException {
      return Depset.depset(orderString, direct, transitive, thread.getSemantics());
    }
  }
}
