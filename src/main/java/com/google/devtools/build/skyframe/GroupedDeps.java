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
package com.google.devtools.build.skyframe;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedCollection;
import java.util.Set;
import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
import org.checkerframework.framework.qual.LiteralKind;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Encapsulates Skyframe dependencies, preserving the groups in which they were requested.
 *
 * <p>This class itself does no duplicate checking, although it is expected that a {@code
 * GroupedDeps} instance contains no duplicates - Skyframe is responsible for only adding keys which
 * are not already present.
 *
 * <p>{@link #equals} is sensitive the order of groups, but is insensitive to the order of elements
 * within a group.
 */
public class GroupedDeps implements Iterable<List<SkyKey>> {

  /**
   * Indicates that the annotated element is a compressed {@link GroupedDeps}, so that it can be
   * safely passed to {@link #decompress} and friends.
   */
  @SubtypeOf(DefaultObject.class)
  @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
  @QualifierForLiterals(LiteralKind.NULL)
  public @interface Compressed {}

  /** Default annotation for type-safety checks of {@link Compressed}. */
  @DefaultQualifierInHierarchy
  @SubtypeOf({})
  @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
  private @interface DefaultObject {}

  /** The total number of deps. */
  private int size;

  /**
   * The deps and group delimiters. Each element is either a {@link SkyKey} or {@link Integer}.
   * Integers represent the start of a new group and indicate how many elements are in the group.
   * Singleton groups have no preceding integer.
   *
   * <p>The group sizes are redundant with the indices stored in {@link #groupIndices}, but they are
   * stored here nonetheless so that {@link #compress} can simply convert this list to an array.
   */
  private final ArrayList<Object> elements;

  /**
   * Indices into {@link #elements} for each group, maintained to provide constant time access to
   * groups in {@link #getDepGroup}. The first group has no entry in this list since it always
   * starts at index 0. Otherwise, the starting index for group {@code i} in {@link #elements} is
   * stored in this list at index {@code i - 1}. For multi-element groups, the starting index refers
   * to the position of the {@link Integer} representing the size of the group.
   */
  private final ArrayList<Integer> groupIndices;

  private final CollectionView collectionView = new CollectionView();

  public GroupedDeps() {
    this(0, newSmallArrayList());
  }

  private GroupedDeps(int size, ArrayList<Object> elements) {
    this(size, elements, newSmallArrayList());
  }

  private GroupedDeps(int size, ArrayList<Object> elements, ArrayList<Integer> groupIndices) {
    this.size = size;
    this.elements = elements;
    this.groupIndices = groupIndices;
  }

  /**
   * Creates a new {@link ArrayList} with single-element capacity.
   *
   * <p>Many Skyframe nodes have only 0 or 1 dep. Pre-sizing small reduces garbage.
   */
  private static <T> ArrayList<T> newSmallArrayList() {
    return new ArrayList<>(1);
  }

  /**
   * Adds a new group with a single element.
   *
   * <p>The caller must ensure that the given element is not already present.
   */
  public void appendSingleton(SkyKey key) {
    markNextGroup();
    elements.add(key);
    size++;
  }

  /**
   * Adds a new group.
   *
   * <p>The caller must ensure that the new group is duplicate-free and does not contain any
   * elements which are already present.
   */
  public void appendGroup(List<SkyKey> group) {
    appendNextGroup(group.iterator(), group.size());
  }

  /**
   * Adds possibly many new groups.
   *
   * <p>The iteration order of the given deps along with the {@code groupSizes} parameter dictate
   * how deps are grouped. For example, if {@code deps = {a,b,c}} and {@code groupSizes = [2, 1]},
   * then there will be two groups: {@code [a,b]} and {@code [c]}. The sum of {@code groupSizes}
   * must equal the size of {@code deps}. Note that it only makes sense to call this method with a
   * set implementation that has a stable iteration order.
   *
   * <p>The caller must ensure that the given set of deps does not contain any elements which are
   * already present.
   */
  public void appendGroups(Set<SkyKey> deps, List<Integer> groupSizes) {
    elements.ensureCapacity(elements.size() + deps.size());
    if (isEmpty()) {
      groupIndices.ensureCapacity(groupSizes.size() - 1);
    } else {
      groupIndices.ensureCapacity(groupIndices.size() + groupSizes.size());
    }
    Iterator<SkyKey> it = deps.iterator();
    for (Integer size : groupSizes) {
      appendNextGroup(it, size);
    }
    checkArgument(
        !it.hasNext(), "size(deps) != sum(groupSizes) (deps=%s, groupSizes=%s)", deps, groupSizes);
  }

  private void appendNextGroup(Iterator<SkyKey> it, Integer groupSize) {
    if (groupSize == 0) {
      return;
    }
    if (groupSize == 1) {
      appendSingleton(it.next());
      return;
    }
    markNextGroup();
    elements.ensureCapacity(elements.size() + groupSize + 1);
    elements.add(groupSize);
    for (int i = 0; i < groupSize; i++) {
      elements.add(it.next());
    }
    size += groupSize;
  }

  private void markNextGroup() {
    if (!isEmpty()) {
      groupIndices.add(elements.size());
    }
  }

  /**
   * Removes the elements in {@code toRemove} from this {@code GroupedDeps}. Takes time proportional
   * to the number of deps, so should not be called often.
   *
   * <p>Should not be called during iteration.
   */
  public void remove(Set<SkyKey> toRemove) {
    if (toRemove.isEmpty()) {
      return;
    }
    GroupedDeps newDeps = new GroupedDeps();
    for (List<SkyKey> group : this) {
      List<SkyKey> newGroup = new ArrayList<>(group.size());
      for (SkyKey key : group) {
        if (!toRemove.contains(key)) {
          newGroup.add(key);
        }
      }
      newDeps.appendGroup(newGroup);
    }

    checkArgument(
        newDeps.size == size - toRemove.size(),
        "Requested removal of absent element(s) (toRemove=%s, elements=%s)",
        toRemove,
        elements);

    size = newDeps.size;
    elements.clear();
    elements.addAll(newDeps.elements);
    groupIndices.clear();
    groupIndices.addAll(newDeps.groupIndices);
  }

  /**
   * Returns the group at position {@code i} as an unmodifiable list.
   *
   * <p>The returned list is a live view of the backing list, so should not be used after a
   * subsequent call to {@link #remove}.
   */
  @SuppressWarnings("unchecked") // Cast of sublist containing only SkyKeys to List<SkyKey>.
  public List<SkyKey> getDepGroup(int i) {
    int index = i == 0 ? 0 : groupIndices.get(i - 1);
    Object obj = elements.get(index);
    if (obj instanceof SkyKey skyKey) {
      return ImmutableList.of(skyKey);
    }
    int groupSize = (int) obj;
    List<?> slice = elements.subList(index + 1, index + 1 + groupSize);
    return (List<SkyKey>) Collections.unmodifiableList(slice);
  }

  /** Returns the number of dependency groups. */
  public int numGroups() {
    return isEmpty() ? 0 : groupIndices.size() + 1;
  }

  /**
   * Returns the number of individual dependencies, as opposed to the number of groups -- equivalent
   * to adding up the sizes of each dependency group.
   */
  public int numElements() {
    return size;
  }

  public static int numElements(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return 0;
      case SINGLETON:
        return 1;
      case MULTIPLE:
        Object[] arr = (Object[]) compressed;
        int size = 0;
        int i = 0;
        while (i < arr.length) {
          Object obj = arr[i++];
          if (obj instanceof SkyKey) {
            size++;
          } else {
            int groupSize = (int) obj;
            size += groupSize;
            i += groupSize;
          }
        }
        return size;
    }
    throw new AssertionError(compressed);
  }

  private enum CompressionCase {
    EMPTY,
    SINGLETON,
    MULTIPLE
  }

  private static CompressionCase compressionCase(@Compressed Object compressed) {
    if (compressed == EMPTY_COMPRESSED) {
      return CompressionCase.EMPTY;
    }
    if (compressed instanceof SkyKey) {
      return CompressionCase.SINGLETON;
    }
    checkArgument(compressed.getClass().isArray(), compressed);
    return CompressionCase.MULTIPLE;
  }

  /**
   * Converts a compressed {@code GroupedDeps} into an {@link Iterable}. Equivalent to calling
   * {@link #decompress} and then {@link #getAllElementsAsIterable}, but more efficient.
   */
  public static Iterable<SkyKey> compressedToIterable(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return ImmutableList.of();
      case SINGLETON:
        return ImmutableList.of((SkyKey) compressed);
      case MULTIPLE:
        List<Object> elements = Arrays.asList((Object[]) compressed);
        return () -> new UngroupedIterator(elements);
    }
    throw new AssertionError(compressed);
  }

  /**
   * Casts an {@code Object} which is known to be {@link Compressed}.
   *
   * <p>This method should only be used when it is not possible to enforce the type via annotations.
   */
  public static @Compressed Object castAsCompressed(Object obj) {
    checkArgument(obj == EMPTY_COMPRESSED || obj instanceof SkyKey || obj.getClass().isArray());
    return (@Compressed Object) obj;
  }

  /** Returns true if this list contains no elements. */
  public boolean isEmpty() {
    return elements.isEmpty();
  }

  /** Determines whether the given compressed {@code GroupedDeps} is empty. */
  public static boolean isEmpty(@Compressed Object compressed) {
    return compressed == EMPTY_COMPRESSED;
  }

  /**
   * Returns true if this list contains the given key. May take time proportional to list size. Call
   * {@link #toSet} instead and use the result if doing multiple contains checks and this is not a
   * {@link WithHashSet}.
   */
  public boolean contains(SkyKey key) {
    return elements.contains(key);
  }

  @SerializationConstant static final @Compressed Object EMPTY_COMPRESSED = new Object();

  /**
   * Returns a memory-efficient representation of dependency groups.
   *
   * <p>The compressed representation does not support mutation or random access to dep groups. If
   * this functionality is needed, use {@link #decompress}.
   */
  public @Compressed Object compress() {
    switch (numElements()) {
      case 0:
        return EMPTY_COMPRESSED;
      case 1:
        return elements.get(0);
      default:
        return elements.toArray();
    }
  }

  public ImmutableSet<SkyKey> toSet() {
    ImmutableSet.Builder<SkyKey> builder = ImmutableSet.builderWithExpectedSize(size);
    for (Object obj : elements) {
      if (obj instanceof SkyKey skyKey) {
        builder.add(skyKey);
      }
    }
    return builder.build();
  }

  /** Reconstitutes a compressed representation returned by {@link #compress}. */
  public static GroupedDeps decompress(@Compressed Object compressed) {
    switch (compressionCase(compressed)) {
      case EMPTY:
        return new GroupedDeps();
      case SINGLETON:
        return new GroupedDeps(1, Lists.newArrayList(compressed));
      case MULTIPLE:
        // Count the size and reconstruct groupIndices.
        Object[] arr = (Object[]) compressed;
        int size = 0;
        ArrayList<Integer> groupIndices = newSmallArrayList();
        int i = 0;
        while (i < arr.length) {
          if (i > 0) {
            groupIndices.add(i);
          }
          Object obj = arr[i++];
          if (obj instanceof SkyKey) {
            size++;
          } else {
            int groupSize = (int) obj;
            size += groupSize;
            i += groupSize;
          }
        }
        return new GroupedDeps(size, Lists.newArrayList(arr), groupIndices);
    }
    throw new AssertionError(compressed);
  }

  @Override
  public int hashCode() {
    // Hashing requires getting an order-independent hash for each element of this.elements. That
    // is too expensive for a hash code.
    throw new UnsupportedOperationException("Should not need to get hash for " + this);
  }

  /**
   * Checks that two dep groups (neither of which may contain duplicates) have the same elements,
   * regardless of order.
   */
  private static boolean checkUnorderedEqualityOfGroups(List<SkyKey> group1, List<SkyKey> group2) {
    if (group1.size() != group2.size()) {
      return false;
    }
    // The order-sensitive comparison usually returns true. When it does, the CompactHashSet doesn't
    // need to be constructed.
    return group1.equals(group2) || CompactHashSet.create(group1).containsAll(group2);
  }

  /**
   * A grouping-unaware view which does not support modifications.
   *
   * <p>This is implemented as a {@code Collection} so that calling {@link Iterables#size} on the
   * return value of {@link #getAllElementsAsIterable} will take constant time.
   */
  private final class CollectionView extends AbstractCollection<SkyKey>
      implements SequencedCollection<SkyKey> {

    @Override
    public Iterator<SkyKey> iterator() {
      return new UngroupedIterator(elements);
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public SequencedCollection<SkyKey> reversed() {
      throw new UnsupportedOperationException();
    }
  }

  /** An iterator that loops through every element in each group. */
  private static final class UngroupedIterator implements Iterator<SkyKey> {
    private final List<Object> elements;
    private int i = 0;

    private UngroupedIterator(List<Object> elements) {
      this.elements = elements;
      advanceIfSizeMarker();
    }

    @Override
    public boolean hasNext() {
      return i < elements.size();
    }

    @Override
    public SkyKey next() {
      SkyKey next = (SkyKey) elements.get(i++);
      advanceIfSizeMarker();
      return next;
    }

    private void advanceIfSizeMarker() {
      if (i < elements.size() && elements.get(i) instanceof Integer) {
        i++;
      }
    }
  }

  @ThreadHostile
  public Collection<SkyKey> getAllElementsAsIterable() {
    return collectionView;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GroupedDeps that)) {
      return false;
    }
    // Fast paths for inequality.
    if (this.size != that.size
        || this.elements.size() != that.elements.size()
        || this.numGroups() != that.numGroups()) {
      return false;
    }
    // We must check the deps, ignoring the ordering of deps in the same group.
    Iterator<List<SkyKey>> thisIt = this.iterator();
    Iterator<List<SkyKey>> thatIt = that.iterator();
    while (thisIt.hasNext()) {
      if (!checkUnorderedEqualityOfGroups(thisIt.next(), thatIt.next())) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("size", size).add("elements", elements).toString();
  }

  /**
   * Iterator that returns the next group in elements for each call to {@link #next}. A custom
   * iterator is needed here because, to optimize memory, we store single-element lists as elements
   * internally, and so they must be wrapped before they're returned.
   */
  private class GroupedIterator implements Iterator<List<SkyKey>> {
    private int i = 0;

    @Override
    public boolean hasNext() {
      return i < numGroups();
    }

    @Override
    public List<SkyKey> next() {
      return getDepGroup(i++);
    }
  }

  @Override
  public Iterator<List<SkyKey>> iterator() {
    return new GroupedIterator();
  }

  /**
   * A {@link GroupedDeps} which keeps a {@link HashSet} of its elements up to date, resulting in a
   * higher memory cost and faster {@link #contains} operations.
   */
  public static final class WithHashSet extends GroupedDeps {
    private final HashSet<SkyKey> set = new LinkedHashSet<>();

    @Override
    public void appendSingleton(SkyKey key) {
      super.appendSingleton(key);
      set.add(key);
    }

    @Override
    public void appendGroup(List<SkyKey> group) {
      super.appendGroup(group);
      set.addAll(group);
    }

    @Override
    public void appendGroups(Set<SkyKey> deps, List<Integer> groupSizes) {
      super.appendGroups(deps, groupSizes);
      set.addAll(deps);
    }

    @Override
    public void remove(Set<SkyKey> toRemove) {
      super.remove(toRemove);
      set.removeAll(toRemove);
    }

    @Override
    public boolean contains(SkyKey needle) {
      return set.contains(needle);
    }

    @Override
    public ImmutableSet<SkyKey> toSet() {
      return ImmutableSet.copyOf(set);
    }
  }
}
