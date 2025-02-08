// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/** A mutable implementation of StarlarkList. */
final class MutableStarlarkList<E> extends StarlarkList<E> {

  // The implementation strategy is similar to ArrayList,
  // but without the extra indirection of using ArrayList.

  // elems[0:size] holds the logical elements, and elems[size:] are not used.
  // elems.getClass() == Object[].class. This is necessary to avoid ArrayStoreException.
  private int size;
  private int iteratorCount; // number of active iterators (unused once frozen)
  Object[] elems = StarlarkList.EMPTY_ARRAY; // elems[i] == null  iff  i >= size

  /** Final except for {@link #unsafeShallowFreeze}; must not be modified any other way. */
  private Mutability mutability;

  MutableStarlarkList(@Nullable Mutability mutability, Object[] elems, int size) {
    Preconditions.checkArgument(elems.getClass() == Object[].class);
    this.elems = elems;
    this.size = size;
    this.mutability = mutability == null ? Mutability.IMMUTABLE : mutability;
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
  public Mutability mutability() {
    return mutability;
  }

  @Override
  public void unsafeShallowFreeze() {
    Mutability.Freezable.checkUnsafeShallowFreezePrecondition(this);
    this.mutability = Mutability.IMMUTABLE;
  }

  @Override
  public ImmutableList<E> getImmutableList() {
    // Optimization: a frozen array needn't be copied.
    // If the entire array is full, we can wrap it directly.
    if (elems.length == size && mutability().isFrozen()) {
      return Tuple.wrapImmutable(elems);
    }

    return ImmutableList.copyOf(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public E get(int i) {
    Preconditions.checkElementIndex(i, size);
    return (E) elems[i]; // unchecked
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean contains(Object o) {
    // StarlarkList contains only valid Starlark objects (which are non-null)
    if (o == null) {
      return false;
    }
    for (int i = 0; i < size; i++) {
      Object elem = elems[i];
      if (o.equals(elem)) {
        return true;
      }
    }
    return false;
  }

  // Postcondition: elems.length >= mincap.
  private void grow(int mincap) {
    int oldcap = elems.length;
    if (oldcap < mincap) {
      int newcap = oldcap + (oldcap >> 1); // grow by at least 50%
      if (newcap < mincap) {
        newcap = mincap;
      }
      elems = Arrays.copyOf(elems, newcap);
    }
  }

  // Grow capacity enough to insert given number of elements
  private void growAdditional(int additional) throws EvalException {
    int mincap = addSizesAndFailIfExcessive(size, additional);
    grow(mincap);
  }

  @Override
  public void addElement(E element) throws EvalException {
    Starlark.checkMutable(this);
    growAdditional(1);
    elems[size++] = element;
  }

  @Override
  public void addElementAt(int index, E element) throws EvalException {
    Starlark.checkMutable(this);
    growAdditional(1);
    System.arraycopy(elems, index, elems, index + 1, size - index);
    elems[index] = element;
    size++;
  }

  @Override
  public void addElements(Iterable<? extends E> elements) throws EvalException {
    Starlark.checkMutable(this);
    if (elements instanceof MutableStarlarkList<?> that) {
      // (safe even if this == that)
      growAdditional(that.size);
      System.arraycopy(that.elems, 0, this.elems, this.size, that.size);
      this.size += that.size;
    } else if (elements instanceof Collection<?> that) {
      // collection of known size
      growAdditional(that.size());
      for (Object x : that) {
        elems[size++] = x;
      }
    } else {
      // iterable
      for (Object x : elements) {
        growAdditional(1);
        elems[size++] = x;
      }
    }
  }

  @Override
  public void removeElementAt(int index) throws EvalException {
    Starlark.checkMutable(this);
    int n = size - index - 1;
    if (n > 0) {
      System.arraycopy(elems, index + 1, elems, index, n);
    }
    elems[--size] = null; // aid GC
  }

  @Override
  public void setElementAt(int index, E value) throws EvalException {
    Starlark.checkMutable(this);
    Preconditions.checkArgument(index < size);
    elems[index] = value;
  }

  @Override
  public void clearElements() throws EvalException {
    Starlark.checkMutable(this);
    for (int i = 0; i < size; i++) {
      elems[i] = null; // aid GC
    }
    size = 0;
  }

  /** Returns a new array of class Object[] containing the list elements. */
  @Override
  public Object[] toArray() {
    return size != 0 ? Arrays.copyOf(elems, size, Object[].class) : EMPTY_ARRAY;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T[] toArray(T[] a) {
    if (a.length < size) {
      return (T[]) Arrays.copyOf(elems, size, a.getClass());
    } else {
      System.arraycopy(elems, 0, a, 0, size);
      Arrays.fill(a, size, a.length, null);
      return a;
    }
  }

  @Override
  Object[] elems() {
    return elems;
  }

  @Override
  public StarlarkList<E> unsafeOptimizeMemoryLayout() {
    Preconditions.checkState(mutability.isFrozen());
    // Canonicalize our Mutability, on the off-chance the old one may be freed.
    mutability = Mutability.IMMUTABLE;
    if (elems.length > size) {
      // shrink the Object array
      elems = Arrays.copyOf(elems, size);
    }
    // Give the caller an immutable specialization of StarlarkList.
    return wrap(mutability, elems);
  }
}
