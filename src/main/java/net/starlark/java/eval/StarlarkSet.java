// Copyright 2024 The Bazel Authors. All rights reserved.
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
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/** A finite, mutable set of Starlark values. */
@StarlarkBuiltin(
    name = "set",
    category = "core",
    doc =
        """
The built-in set type. A set is a mutable, iterable collection of unique values &ndash; the set's
<em>elements</em>. The <a href="../globals/all#type">type name</a> of a set is <code>"set"</code>.

<p>Sets provide constant-time operations to insert, remove, or check for the presence of a value.
Sets are implemented using a hash table, and therefore, just like keys of a
<a href="../dict">dictionary</a>, elements of a set must be hashable. A value may be used as an
element of a set if and only if it may be used as a key of a dictionary.

<p>Sets may be constructed using the <a href="../globals/all#set"><code>set()</code></a> built-in
function, which returns a new set containing the unique elements of its optional argument, which
must be an iterable. Calling <code>set()</code> without an argument constructs an empty set. Sets
have no literal syntax.

<p>The <code>in</code> and <code>not in</code> operations check whether a value is (or is not) in a
set:

<pre class=language-python>
s = set(["a", "b", "c"])
"a" in s  # True
"z" in s  # False
</pre>

<p>A set is iterable, and thus may be used as the operand of a <code>for</code> loop, a list
comprehension, and the various built-in functions that operate on iterables. Its length can be
retrieved using the <a href="../globals/all#len"><code>len()</code></a> built-in function, and the
order of iteration is the order in which elements were first added to the set:

<pre class=language-python>
s = set(["z", "y", "z", "y"])
len(s)       # prints 2
s.add("x")
len(s)       # prints 3
for e in s:
    print e  # prints "z", "y", "x"
</pre>

<p>A set used in Boolean context is true if and only if it is non-empty.

<pre class=language-python>
s = set()
"non-empty" if s else "empty"  # "empty"
t = set(["x", "y"])
"non-empty" if t else "empty"  # "non-empty"
</pre>

<p>Sets may be compared for equality or inequality using <code>==</code> and <code>!=</code>. A set
<code>s</code> is equal to <code>t</code> if and only if <code>t</code> is a set containing the same
elements; iteration order is not significant. In particular, a set is <em>not</em> equal to the list
of its elements. Sets are not ordered with respect to other sets, and an attempt to compare two sets
using <code>&lt;</code>, <code>&lt;=</code>, <code>&gt;</code>, <code>&gt;=</code>, or to sort a
sequence of sets, will fail.

<pre class=language-python>
set() == set()              # True
set() != []                 # True
set([1, 2]) == set([2, 1])  # True
set([1, 2]) != [1, 2]       # True
</pre>

<p>The <code>|</code> operation on two sets returns the union of the two sets: a set containing the
elements found in either one or both of the original sets.

<pre class=language-python>
set([1, 2]) | set([3, 2])  # set([1, 2, 3])
</pre>

<p>The <code>&amp;</code> operation on two sets returns the intersection of the two sets: a set
containing only the elements found in both of the original sets.

<pre class=language-python>
set([1, 2]) &amp; set([2, 3])  # set([2])
set([1, 2]) &amp; set([3, 4])  # set()
</pre>

<p>The <code>-</code> operation on two sets returns the difference of the two sets: a set containing
the elements found in the left-hand side set but not the right-hand side set.

<pre class=language-python>
set([1, 2]) - set([2, 3])  # set([1])
set([1, 2]) - set([3, 4])  # set([1, 2])
</pre>

<p>The <code>^</code> operation on two sets returns the symmetric difference of the two sets: a set
containing the elements found in exactly one of the two original sets, but not in both.

<pre class=language-python>
set([1, 2]) ^ set([2, 3])  # set([1, 3])
set([1, 2]) ^ set([3, 4])  # set([1, 2, 3, 4])
</pre>

<p>In each of the above operations, the elements of the resulting set retain their order from the
two operand sets, with all elements that were drawn from the left-hand side ordered before any
element that was only present in the right-hand side.

<p>The corresponding augmented assignments, <code>|=</code>, <code>&amp;=</code>, <code>-=</code>,
and <code>^=</code>, modify the left-hand set in place.

<pre class=language-python>
s = set([1, 2])
s |= set([2, 3, 4])     # s now equals set([1, 2, 3, 4])
s &amp;= set([0, 1, 2, 3])  # s now equals set([1, 2, 3])
s -= set([0, 1])        # s now equals set([2, 3])
s ^= set([3, 4])        # s now equals set([2, 4])
</pre>

<p>Like all mutable values in Starlark, a set can be frozen, and once frozen, all subsequent
operations that attempt to update it will fail.
""")
public final class StarlarkSet<E> extends AbstractSet<E>
    implements Mutability.Freezable, StarlarkMembershipTestable, StarlarkIterable<E> {

  private static final StarlarkSet<?> EMPTY = new StarlarkSet<>(ImmutableSet.of());

  // Either LinkedHashSet<E> or ImmutableSet<E>.
  private final Set<E> contents;
  // Number of active iterators (unused once frozen).
  private transient int iteratorCount; // transient for serialization by Bazel

  /** Final except for {@link #unsafeShallowFreeze}; must not be modified any other way. */
  private Mutability mutability;

  @SuppressWarnings("NonApiType")
  private StarlarkSet(Mutability mutability, LinkedHashSet<E> contents) {
    checkNotNull(mutability);
    checkArgument(mutability != Mutability.IMMUTABLE);
    this.mutability = mutability;
    this.contents = contents;
  }

  private StarlarkSet(ImmutableSet<E> contents) {
    // An immutable set might as well store its contents as an ImmutableSet, since ImmutableSet
    // both is more memory-efficient than LinkedHashSet and also it has the requisite deterministic
    // iteration order.
    this.mutability = Mutability.IMMUTABLE;
    this.contents = contents;
  }

  @Override
  public boolean truth() {
    return !isEmpty();
  }

  @Override
  public boolean isImmutable() {
    return mutability().isFrozen();
  }

  @Override
  public boolean updateIteratorCount(int delta) {
    if (mutability().isFrozen()) {
      return false;
    }
    if (delta > 0) {
      iteratorCount++;
    } else if (delta < 0) {
      iteratorCount--;
    }
    return iteratorCount > 0;
  }

  @Override
  public void checkHashable() throws EvalException {
    // Even a frozen set is unhashable.
    throw Starlark.errorf("unhashable type: 'set'");
  }

  @Override
  public int hashCode() {
    return contents.hashCode();
  }

  @Override
  public void repr(Printer printer) {
    if (isEmpty()) {
      printer.append("set()");
    } else {
      printer.printList(this, "set([", ", ", "])");
    }
  }

  @Override
  public String toString() {
    return Starlark.repr(this);
  }

  @Override
  public boolean equals(Object o) {
    return contents.equals(o);
  }

  @Override
  public Iterator<E> iterator() {
    if (contents instanceof ImmutableSet) {
      return contents.iterator();
    } else {
      // Prohibit mutation through Iterator.remove().
      return Collections.unmodifiableSet(contents).iterator();
    }
  }

  @Override
  public int size() {
    return contents.size();
  }

  @Override
  public boolean isEmpty() {
    return contents.isEmpty();
  }

  @Override
  public Object[] toArray() {
    return contents.toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return contents.toArray(a);
  }

  @Override
  public boolean contains(Object o) {
    return contents.contains(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return contents.containsAll(c);
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object element) {
    return contents.contains(element);
  }

  /** Returns an immutable empty set. */
  // Safe because the empty singleton is immutable.
  @SuppressWarnings("unchecked")
  public static <E> StarlarkSet<E> empty() {
    return (StarlarkSet<E>) EMPTY;
  }

  /** Returns a new empty set with the specified mutability. */
  public static <E> StarlarkSet<E> of(@Nullable Mutability mu) {
    if (mu == null) {
      mu = Mutability.IMMUTABLE;
    }
    if (mu == Mutability.IMMUTABLE) {
      return empty();
    } else {
      return new StarlarkSet<>(mu, Sets.newLinkedHashSetWithExpectedSize(1));
    }
  }

  /**
   * Returns a set with the specified mutability containing the entries of {@code elements}. Tries
   * to elide copying if {@code elements} is immutable.
   *
   * @param elements a collection of elements, which must be Starlark-hashable (note that this
   *     method assumes but does not verify their hashability), to add to the new set.
   */
  public static <E> StarlarkSet<E> copyOf(
      @Nullable Mutability mu, Collection<? extends E> elements) {
    if (elements.isEmpty()) {
      return of(mu);
    }

    if (mu == null) {
      mu = Mutability.IMMUTABLE;
    }

    if (mu == Mutability.IMMUTABLE) {
      if (elements instanceof ImmutableSet) {
        elements.forEach(Starlark::checkValid);
        @SuppressWarnings("unchecked")
        ImmutableSet<E> immutableSet = (ImmutableSet<E>) elements;
        return new StarlarkSet<>(immutableSet);
      }

      if (elements instanceof StarlarkSet && ((StarlarkSet<?>) elements).isImmutable()) {
        @SuppressWarnings("unchecked")
        StarlarkSet<E> starlarkSet = (StarlarkSet<E>) elements;
        return starlarkSet;
      }

      ImmutableSet.Builder<E> immutableSetBuilder =
          ImmutableSet.builderWithExpectedSize(elements.size());
      elements.forEach(e -> immutableSetBuilder.add(Starlark.checkValid(e)));
      return new StarlarkSet<>(immutableSetBuilder.build());
    } else {
      LinkedHashSet<E> linkedHashSet = Sets.newLinkedHashSetWithExpectedSize(elements.size());
      elements.forEach(e -> linkedHashSet.add(Starlark.checkValid(e)));
      return new StarlarkSet<>(mu, linkedHashSet);
    }
  }

  private static <E> StarlarkSet<E> wrapOrImmutableCopy(Mutability mu, LinkedHashSet<E> elements) {
    checkNotNull(mu);
    if (mu == Mutability.IMMUTABLE) {
      return elements.isEmpty() ? empty() : new StarlarkSet<>(ImmutableSet.copyOf(elements));
    } else {
      return new StarlarkSet<>(mu, elements);
    }
  }

  /**
   * A variant of {@link #copyOf} intended to be used from Starlark. Unlike {@link #copyOf}, this
   * method does verify that the elements being added to the set are Starlark-hashable.
   *
   * @param elements a collection of elements to add to the new set, or a map whose keys will be
   *     added to the new set.
   */
  public static StarlarkSet<Object> checkedCopyOf(@Nullable Mutability mu, Object elements)
      throws EvalException {
    @SuppressWarnings("unchecked")
    Collection<Object> collection =
        (Collection<Object>) toHashableCollection(elements, "set constructor argument");
    return copyOf(mu, collection);
  }

  /**
   * Returns an immutable set containing the entries of {@code elements}. Tries to elide copying if
   * {@code elements} is already immutable.
   *
   * @param elements a collection of elements, which must be Starlark-hashable (note that this
   *     method assumes but does not verify their hashability), to add to the new set.
   */
  public static <E> StarlarkSet<E> immutableCopyOf(Collection<? extends E> elements) {
    return copyOf(null, elements);
  }

  @Override
  public Mutability mutability() {
    return mutability;
  }

  @Override
  public void unsafeShallowFreeze() {
    Mutability.Freezable.checkUnsafeShallowFreezePrecondition(this);
    this.mutability = Mutability.IMMUTABLE;
  }

  @StarlarkMethod(
      name = "issubset",
      doc =
          """
Returns true of this set is a subset of another.

<p>Note that a set is always considered to be a subset of itself.

<p>For example,
<pre class=language-python>
set([1, 2]).issubset([1, 2, 3])  # True
set([1, 2]).issubset([1, 2])     # True
set([1, 2]).issubset([2, 3])     # False
</pre>
""",
      parameters = {
        @Param(name = "other", doc = "A set, a sequence of hashable elements, or a dict.")
      })
  public boolean isSubset(Object other) throws EvalException {
    return toHashableCollection(other, "issubset argument").containsAll(this.contents);
  }

  @StarlarkMethod(
      name = "issuperset",
      doc =
          """
Returns true of this set is a superset of another.

<p>Note that a set is always considered to be a superset of itself.

<p>For example,
<pre class=language-python>
set([1, 2, 3]).issuperset([1, 2])     # True
set([1, 2, 3]).issuperset([1, 2, 3])  # True
set([1, 2, 3]).issuperset([2, 3, 4])  # False
</pre>
""",
      parameters = {
        @Param(name = "other", doc = "A set, a sequence of hashable elements, or a dict.")
      })
  public boolean isSuperset(Object other) throws EvalException {
    return contents.containsAll(toHashableCollection(other, "issuperset argument"));
  }

  @StarlarkMethod(
      name = "isdisjoint",
      doc =
          """
Returns true if this set has no elements in common with another.

<p>For example,
<pre class=language-python>
set([1, 2]).isdisjoint([3, 4])  # True
set().isdisjoint(set())         # True
set([1, 2]).isdisjoint([2, 3])  # False
</pre>
""",
      parameters = {
        @Param(name = "other", doc = "A set, a sequence of hashable elements, or a dict.")
      })
  public boolean isDisjoint(Object other) throws EvalException {
    return Collections.disjoint(this.contents, toHashableCollection(other, "isdisjoint argument"));
  }

  /**
   * Intended for use from Starlark; if used from Java, the caller should ensure that the elements
   * to be added are instances of {@code E}.
   */
  @StarlarkMethod(
      name = "update",
      doc =
          """
Adds the elements found in others to this set.

<p>For example,
<pre class=language-python>
s = set()
s.update([1, 2])          # None; s is set([1, 2])
s.update([2, 3], [3, 4])  # None; s is set([1, 2, 3, 4])
</pre>

<p>If <code>s</code> and <code>t</code> are sets, <code>s.update(t)</code> is equivalent to
<code>s |= t</code>; however, note that the <code>|=</code> augmented assignment requires both sides
to be sets, while the <code>update</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>update</code> without any arguments; this leaves the set
unchanged.
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."))
  public void update(Tuple others) throws EvalException {
    Starlark.checkMutable(this);
    for (Object other : others) {
      @SuppressWarnings("unchecked")
      Collection<? extends E> otherCollection =
          (Collection<? extends E>) toHashableCollection(other, "update argument");
      contents.addAll(otherCollection);
    }
  }

  @StarlarkMethod(
      name = "add",
      doc =
          """
Adds an element to the set.

<p>It is permissible to <code>add</code> a value already present in the set; this leaves the set
unchanged.

<p>If you need to add multiple elements to a set, see <a href="#update"><code>update</code></a> or
the <code>|=</code> augmented assignment operation.
""",
      parameters = {@Param(name = "element", doc = "Element to add.")})
  public void addElement(E element) throws EvalException {
    Starlark.checkMutable(this);
    Starlark.checkHashable(element);
    contents.add(element);
  }

  @StarlarkMethod(
      name = "remove",
      doc =
          """
Removes an element, which must be present in the set, from the set.

<p><code>remove</code> fails if the element was not present in the set. If you don't want to fail on
an attempt to remove a non-present element, use <a href="#discard"><code>discard</code></a> instead.
If you need to remove multiple elements from a set, see
<a href="#difference_update"><code>difference_update</code></a> or the <code>-=</code> augmented
assignment operation.
""",
      parameters = {
        @Param(
            name = "element",
            doc = "Element to remove. Must be an element of the set (and hashable).")
      })
  public void removeElement(E element) throws EvalException {
    Starlark.checkMutable(this);
    Starlark.checkHashable(element);
    if (!contents.remove(element)) {
      throw Starlark.errorf("element %s not found in set", Starlark.repr(element));
    }
  }

  @StarlarkMethod(
      name = "discard",
      doc =
          """
Removes an element from the set if it is present.

<p>It is permissible to <code>discard</code> a value not present in the set; this leaves the set
unchanged. If you want to fail on an attempt to remove a non-present element, use
<a href="#remove"><code>remove</code></a> instead. If you need to remove multiple elements from a
set, see <a href="#difference_update"><code>difference_update</code></a> or the <code>-=</code>
augmented assignment operation.

<p>For example,
<pre class=language-python>
s = set(["x", "y"])
s.discard("y")  # None; s == set(["x"])
s.discard("y")  # None; s == set(["x"])
</pre>
""",
      parameters = {@Param(name = "element", doc = "Element to discard. Must be hashable.")})
  public void discard(E element) throws EvalException {
    Starlark.checkMutable(this);
    Starlark.checkHashable(element);
    contents.remove(element);
  }

  @StarlarkMethod(
      name = "pop",
      doc =
          """
Removes and returns the first element of the set (in iteration order, which is the order in which
elements were first added to the set).

<p>Fails if the set is empty.

<p>For example,
<pre class=language-python>
s = set([3, 1, 2])
s.pop()  # 3; s == set([1, 2])
s.pop()  # 1; s == set([2])
s.pop()  # 2; s == set()
s.pop()  # error: empty set
</pre>
""")
  public E pop() throws EvalException {
    Starlark.checkMutable(this);
    if (isEmpty()) {
      throw Starlark.errorf("set is empty");
    }
    E element = contents.iterator().next();
    contents.remove(element);
    return element;
  }

  @StarlarkMethod(name = "clear", doc = "Removes all the elements of the set.")
  public void clearElements() throws EvalException {
    Starlark.checkMutable(this);
    contents.clear();
  }

  @StarlarkMethod(
      name = "union",
      doc =
          """
Returns a new mutable set containing the union of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.union(t)</code> is equivalent to
<code>s | t</code>; however, note that the <code>|</code> operation requires both sides to be sets,
while the <code>union</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>union</code> without any arguments; this returns a copy of the
set.

<p>For example,
<pre class=language-python>
set([1, 2]).union([2, 3])                    # set([1, 2, 3])
set([1, 2]).union([2, 3], {3: "a", 4: "b"})  # set([1, 2, 3, 4])
</pre>
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."),
      useStarlarkThread = true)
  public StarlarkSet<?> union(Tuple others, StarlarkThread thread) throws EvalException {
    LinkedHashSet<Object> newContents = new LinkedHashSet<>(contents);
    for (Object other : others) {
      newContents.addAll(toHashableCollection(other, "union argument"));
    }
    return wrapOrImmutableCopy(thread.mutability(), newContents);
  }

  @StarlarkMethod(
      name = "intersection",
      doc =
          """
Returns a new mutable set containing the intersection of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.intersection(t)</code> is equivalent to
<code>s &amp; t</code>; however, note that the <code>&amp;</code> operation requires both sides to
be sets, while the <code>intersection</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>intersection</code> without any arguments; this returns a copy of
the set.

<p>For example,
<pre class=language-python>
set([1, 2]).intersection([2, 3])             # set([2])
set([1, 2, 3]).intersection([0, 1], [1, 2])  # set([1])
</pre>
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."),
      useStarlarkThread = true)
  public StarlarkSet<?> intersection(Tuple others, StarlarkThread thread) throws EvalException {
    LinkedHashSet<Object> newContents = new LinkedHashSet<>(contents);
    for (Object other : others) {
      newContents.retainAll(toHashableCollection(other, "intersection argument"));
    }
    return wrapOrImmutableCopy(thread.mutability(), newContents);
  }

  @StarlarkMethod(
      name = "intersection_update",
      doc =
          """
Removes any elements not found in all others from this set.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.intersection_update(t)</code> is
equivalent to <code>s &amp;= t</code>; however, note that the <code>&amp;=</code> augmented
assignment requires both sides to be sets, while the <code>intersection_update</code> method also
accepts sequences and dicts.

<p>It is permissible to call <code>intersection_update</code> without any arguments; this leaves the
set unchanged.

<p>For example,
<pre class=language-python>
s = set([1, 2, 3, 4])
s.intersection_update([0, 1, 2])       # None; s is set([1, 2])
s.intersection_update([0, 1], [1, 2])  # None; s is set([1])
</pre>
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."))
  public void intersectionUpdate(Tuple others) throws EvalException {
    Starlark.checkMutable(this);
    for (Object other : others) {
      contents.retainAll(toHashableCollection(other, "intersection_update argument"));
    }
  }

  @StarlarkMethod(
      name = "difference",
      doc =
          """
Returns a new mutable set containing the difference of this set with others.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.difference(t)</code> is equivalent to
<code>s - t</code>; however, note that the <code>-</code> operation requires both sides to be sets,
while the <code>difference</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>difference</code> without any arguments; this returns a copy of
the set.

<p>For example,
<pre class=language-python>
set([1, 2, 3]).difference([2])             # set([1, 3])
set([1, 2, 3]).difference([0, 1], [3, 4])  # set([2])
</pre>
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."),
      useStarlarkThread = true)
  public StarlarkSet<?> difference(Tuple others, StarlarkThread thread) throws EvalException {
    LinkedHashSet<Object> newContents = new LinkedHashSet<>(contents);
    for (Object other : others) {
      newContents.removeAll(toHashableCollection(other, "difference argument"));
    }
    return wrapOrImmutableCopy(thread.mutability(), newContents);
  }

  @StarlarkMethod(
      name = "difference_update",
      doc =
          """
Removes any elements found in any others from this set.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.difference_update(t)</code> is equivalent
to <code>s -= t</code>; however, note that the <code>-=</code> augmented assignment requires both
sides to be sets, while the <code>difference_update</code> method also accepts sequences and dicts.

<p>It is permissible to call <code>difference_update</code> without any arguments; this leaves the
set unchanged.

<p>For example,
<pre class=language-python>
s = set([1, 2, 3, 4])
s.difference_update([2])             # None; s is set([1, 3, 4])
s.difference_update([0, 1], [4, 5])  # None; s is set([3])
</pre>
""",
      extraPositionals =
          @Param(name = "others", doc = "Sets, sequences of hashable elements, or dicts."))
  public void differenceUpdate(Tuple others) throws EvalException {
    Starlark.checkMutable(this);
    for (Object other : others) {
      contents.removeAll(toHashableCollection(other, "intersection_update argument"));
    }
  }

  @StarlarkMethod(
      name = "symmetric_difference",
      doc =
          """
Returns a new mutable set containing the symmetric difference of this set with another set,
sequence, or dict.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.symmetric_difference(t)</code> is
equivalent to <code>s ^ t</code>; however, note that the <code>^</code> operation requires both
sides to be sets, while the <code>symmetric_difference</code> method also accepts a sequence or a
dict.

<p>For example,
<pre class=language-python>
set([1, 2]).symmetric_difference([2, 3])  # set([1, 3])
</pre>
""",
      parameters = {
        @Param(name = "other", doc = "A set, a sequence of hashable elements, or a dict.")
      },
      useStarlarkThread = true)
  public StarlarkSet<?> symmetricDifference(Object other, StarlarkThread thread)
      throws EvalException {
    LinkedHashSet<Object> newContents = new LinkedHashSet<>(contents);
    for (Object element : toHashableCollection(other, "symmetric_difference argument")) {
      if (contents.contains(element)) {
        newContents.remove(element);
      } else {
        newContents.add(element);
      }
    }
    return wrapOrImmutableCopy(thread.mutability(), newContents);
  }

  /**
   * Intended for use from Starlark; if used from Java, the caller should ensure that the elements
   * to be added are instances of {@code E}.
   */
  @StarlarkMethod(
      name = "symmetric_difference_update",
      doc =
          """
Returns a new mutable set containing the symmetric difference of this set with another set,
sequence, or dict.

<p>If <code>s</code> and <code>t</code> are sets, <code>s.symmetric_difference_update(t)</code> is
equivalent to `s ^= t<code>; however, note that the </code>^=` augmented assignment requires both
sides to be sets, while the <code>symmetric_difference_update</code> method also accepts a sequence
or a dict.

<p>For example,
<pre class=language-python>
s = set([1, 2])
s.symmetric_difference_update([2, 3])  # None; s == set([1, 3])
</pre>
""",
      parameters = {
        @Param(name = "other", doc = "A set, a sequence of hashable elements, or a dict.")
      })
  public void symmetricDifferenceUpdate(Object other) throws EvalException {
    Starlark.checkMutable(this);
    ImmutableSet<E> originalContents = ImmutableSet.copyOf(contents);
    for (Object element : toHashableCollection(other, "symmetric_difference_update argument")) {
      if (originalContents.contains(element)) {
        contents.remove(element);
      } else {
        @SuppressWarnings("unchecked")
        E castElement = (E) element;
        contents.add(castElement);
      }
    }
  }

  /**
   * Verifies that {@code other} is either a collection of Starlark-hashable elements or a map with
   * Starlark-hashable keys.
   *
   * <p>Note that in the Starlark language spec, this notion is referred to as an "iterable
   * sequence" of hashable elements; but our {@link Dict} doesn't implement {@link Sequence}, and in
   * any case, we may need to operate on native Java collections and maps which don't implement
   * {@link StarlarkIterable} or {@link Sequence}.
   *
   * @return {@code other} if it is a collection, or the key set of {@code other} if it is a map.
   */
  private static Collection<?> toHashableCollection(Object other, String what)
      throws EvalException {
    if (other instanceof Collection<?> collection) {
      // Assume that elements of a StarlarkSet have already been checked to be hashable.
      if (!(collection instanceof StarlarkSet)) {
        for (Object element : collection) {
          Starlark.checkHashable(element);
        }
      }
      return collection;
    } else if (other instanceof Map<?, ?> map) {
      Set<?> keySet = map.keySet();
      // Assume that keys of a Dict have already been checked to be hashable.
      if (!(map instanceof Dict)) {
        for (Object element : keySet) {
          Starlark.checkHashable(element);
        }
      }
      return keySet;
    }
    // The Java Starlark interpreter doesn't have a "sized iterable" interface - so we enumerate the
    // types we expect.
    throw Starlark.errorf(
        "for %s got value of type '%s', want a set, sequence, or dict", what, Starlark.type(other));
  }

  // Prohibit Java Set mutators.

  /**
   * @deprecated use {@link #addElement} instead.
   */
  @Deprecated
  @Override
  public boolean add(E e) {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated use {@link #update} instead.
   */
  @Deprecated
  @Override
  public boolean addAll(Collection<? extends E> c) {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated use {@link #clearElements} instead.
   */
  @Deprecated
  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated use {@link #removeElement} instead.
   */
  @Deprecated
  @Override
  public boolean remove(Object o) {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated use {@link #differenceUpdate} instead.
   */
  @Deprecated
  @Override
  public boolean removeAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated use {@link #intersectionUpdate} instead.
   */
  @Deprecated
  @Override
  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }
}
