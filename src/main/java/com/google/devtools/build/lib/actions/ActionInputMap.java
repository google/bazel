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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Helper for {@link InputMetadataProvider} implementations.
 *
 * <p>Allows {@link FileArtifactValue} lookups by exec path or {@link ActionInput}. <i>Also</i>
 * allows {@link ActionInput} to be looked up by exec path.
 *
 * <p>This class implements a closed hash-map with the "links" of each bucket's linked list being
 * stored in a flat array to avoid memory allocations and garbage collection.
 *
 * <p>This class is thread-compatible.
 */
public final class ActionInputMap implements InputMetadataProvider, ActionInputMapSink {

  private static final Object PLACEHOLDER = new Object();

  /**
   * Trie-like data structure that mimics the filesystem for tree artifacts.
   *
   * <p>It is too expensive to store all tree children in the input map individually, so in order to
   * find a child's metadata, we need to find the parent. Sometimes it is necessary to look up an
   * input's metadata by exec path without even knowing whether it is a {@link TreeFileArtifact},
   * let alone how many directory levels up its parent is. This data structure supports efficient
   * lookups in such cases.
   */
  static final class TrieArtifact {

    // Values in this map are either TrieArtifact (for intermediate directory nodes) or
    // TreeArtifactValue (for terminal nodes). This saves memory by not creating a TrieArtifact for
    // terminal nodes. This optimization is safe because nested tree artifacts are forbidden.
    //
    // We special case when we have a single child in order to save memory. This way, we do not
    // allocate hash maps for path entries with a single child (prefixes of unbranched paths, e.g.
    // [a/b/c/d]/tree{1..n}).
    // Invariant: subFolders is an immutable map iff subFolders.size() <= 1.
    private Map<String, Object> subFolders = ImmutableMap.of();

    void add(PathFragment treeExecPath, TreeArtifactValue treeArtifactValue) {
      TrieArtifact current = this;
      Iterator<String> it = treeExecPath.segments().iterator();
      while (it.hasNext()) {
        String segment = it.next();
        Object next = current.subFolders.get(segment);

        if (it.hasNext()) {
          // Intermediate node.
          if (next == null) {
            var newNode = new TrieArtifact();
            current.put(segment, newNode);
            current = newNode;
          } else {
            current = (TrieArtifact) next;
          }
        } else if (next == null) {
          // Terminal node.
          current.put(segment, treeArtifactValue);
        }
      }
    }

    private void put(String name, Object val) {
      // Input path segments are commonly shared among actions, so intern before storing.
      name = name.intern();

      switch (subFolders.size()) {
        case 0 -> subFolders = ImmutableMap.of(name, val);
        case 1 -> {
          Map<String, Object> newMap = CompactHashMap.createWithExpectedSize(2);
          newMap.putAll(subFolders);
          newMap.put(name, val);
          subFolders = newMap;
        }
        default -> subFolders.put(name, val);
      }
    }

    @Nullable
    TreeArtifactValue findTreeArtifactNodeAtPrefix(PathFragment execPath) {
      TrieArtifact current = this;
      for (String segment : execPath.segments()) {
        Object next = current.subFolders.get(segment);
        if (next == null) {
          break;
        }
        if (next instanceof TreeArtifactValue val) {
          return val;
        }
        current = (TrieArtifact) next;
      }
      return null;
    }
  }

  private final BugReporter bugReporter;

  /** The number of elements contained in this map. */
  private int size;

  /**
   * The hash buckets. Values are indexes into the four arrays. The number of buckets is always the
   * smallest power of 2 that is larger than the number of elements.
   */
  private int[] table;

  /** Flat array of the next pointers that make up the linked list behind each hash bucket. */
  private int[] next;

  /**
   * The {@link ActionInput} keys stored in this map. For performance reasons, they need to be
   * stored as {@link Object}s as otherwise, the JVM does not seem to be as good optimizing the
   * store operations (maybe it does checks on the type being stored?).
   */
  private Object[] keys;

  /**
   * Extra storage for the execPathStrings of the values in {@link #keys}. This extra storage is
   * necessary for speed as otherwise, we'd need to cast to {@link ActionInput}, which is slow.
   */
  private Object[] paths;

  /**
   * The {@link FileArtifactValue} data stored in this map. Same as the other arrays, this is stored
   * as {@link Object} for performance reasons.
   */
  private Object[] values;

  private TrieArtifact treeArtifactsRoot = new TrieArtifact();

  private List<RunfilesTree> runfilesTrees = new ArrayList<>();

  @Deprecated
  @VisibleForTesting
  public ActionInputMap(int sizeHint) {
    this(BugReporter.defaultInstance(), sizeHint);
  }

  public ActionInputMap(BugReporter bugReporter, int sizeHint) {
    this.bugReporter = bugReporter;
    sizeHint = Math.max(1, sizeHint);
    int tableSize = Integer.highestOneBit(sizeHint) << 1;
    size = 0;

    table = new int[tableSize];
    Arrays.fill(table, -1);

    next = new int[sizeHint];
    keys = new Object[sizeHint];
    paths = new Object[sizeHint];
    values = new Object[sizeHint];
  }

  private int getIndex(String execPathString) {
    int hashCode = execPathString.hashCode();
    int index = hashCode & (table.length - 1);
    if (table[index] == -1) {
      return -1;
    }
    index = table[index];
    while (index != -1) {
      if (hashCode == paths[index].hashCode() && execPathString.equals(paths[index])) {
        return index;
      }
      index = next[index];
    }
    return -1;
  }

  @Nullable
  @Override
  public FileArtifactValue getInputMetadata(ActionInput input) {
    return getInputMetadataChecked(input);
  }

  @Nullable
  @Override
  public FileArtifactValue getInputMetadataChecked(ActionInput input) {
    if (isRunfilesTree(input)) {
      RunfilesArtifactValue runfilesMetadata = getRunfilesMetadata(input);
      return runfilesMetadata == null ? null : runfilesMetadata.getMetadata();
    }

    if (input instanceof TreeFileArtifact treeFileArtifact) {
      int treeIndex = getIndex(treeFileArtifact.getParent().getExecPathString());
      if (treeIndex != -1) {
        checkArgument(
            values[treeIndex] instanceof TreeArtifactValue,
            "Requested tree file artifact under non-tree/omitted tree artifact: %s",
            input);
        return ((TreeArtifactValue) values[treeIndex]).getChildValues().get(treeFileArtifact);
      }
    }
    int index = getIndex(input.getExecPathString());
    if (index != -1) {
      Object value = values[index];
      return value instanceof TreeArtifactValue treeValue
          ? treeValue.getMetadata()
          : (FileArtifactValue) value;
    }
    if (input instanceof Artifact) {
      // Non tree artifacts cannot overlap with tree files, therefore we can skip searching the
      // parents.
      return null;
    }

    // Check the trees in case input is a non-Artifact ActionInput pointing to a tree artifact file
    // (such as the ones resulting from a fileset expansion).
    FileArtifactValue result = getMetadataFromTreeArtifacts(input.getExecPath());

    if (result != null) {
      bugReporter.sendBugReport(
          new IllegalArgumentException(
              String.format(
                  "Tree artifact file: '%s' referred to as an action input", input.getExecPath())));
    }
    return result;
  }

  @Nullable
  @Override
  public RunfilesArtifactValue getRunfilesMetadata(ActionInput input) {
    Preconditions.checkArgument(isRunfilesTree(input));

    int index = getIndex(input.getExecPathString());
    if (index == -1) {
      return null;
    }

    return (RunfilesArtifactValue) values[index];
  }

  @Override
  public ImmutableList<RunfilesTree> getRunfilesTrees() {
    return ImmutableList.copyOf(runfilesTrees);
  }

  /**
   * Returns metadata for given path.
   *
   * <p>This method is less efficient than {@link #getInputMetadata(ActionInput)}, please use that
   * method instead of this one when looking up {@linkplain ActionInput action inputs}.
   */
  @Nullable
  public FileArtifactValue getMetadata(PathFragment execPath) {
    int index = getIndex(execPath.getPathString());
    if (index != -1) {
      Object value = values[index];
      return value instanceof TreeArtifactValue treeValue
          ? treeValue.getMetadata()
          : (FileArtifactValue) value;
    }

    // Fall back to searching the tree artifacts.
    return getMetadataFromTreeArtifacts(execPath);
  }

  @Nullable
  private FileArtifactValue getMetadataFromTreeArtifacts(PathFragment execPath) {
    TreeArtifactValue tree = treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath);
    if (tree == null) {
      return null;
    }

    Map.Entry<?, FileArtifactValue> entry = tree.findChildEntryByExecPath(execPath);
    return entry != null ? entry.getValue() : null;
  }

  /**
   * Returns the {@link TreeArtifactValue} for the given path, or {@code null} if no such tree
   * artifact exists.
   */
  @Nullable
  public TreeArtifactValue getTreeMetadata(PathFragment execPath) {
    int index = getIndex(execPath.getPathString());
    if (index < 0) {
      return null;
    }
    Object value = values[index];
    return value instanceof TreeArtifactValue treeValue ? treeValue : null;
  }

  /**
   * Returns the {@link TreeArtifactValue} for the shortest prefix of the given path, possibly the
   * path itself, that corresponds to a tree artifact; or {@code null} if no such tree artifact
   * exists.
   */
  @Nullable
  public TreeArtifactValue getTreeMetadataForPrefix(PathFragment execPath) {
    return treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath);
  }

  @Nullable
  @Override
  public ActionInput getInput(String execPathString) {
    int index = getIndex(execPathString);
    if (index != -1) {
      return (ActionInput) keys[index];
    }

    // Search ancestor paths since execPathString may point to a TreeFileArtifact within one of the
    // tree artifacts.
    PathFragment execPath = PathFragment.create(execPathString);
    TreeArtifactValue tree = treeArtifactsRoot.findTreeArtifactNodeAtPrefix(execPath);
    if (tree == null) {
      return null;
    }

    // We must return an entry from the map since a duplicate would not have the generating action
    // key set.
    Map.Entry<TreeFileArtifact, ?> entry = tree.findChildEntryByExecPath(execPath);
    return entry != null ? entry.getKey() : null;
  }

  /**
   * Returns count of unique, top-level {@linkplain ActionInput action inputs} in the map.
   *
   * <p>Top-level means that each tree artifact, counts as 1, irrespective of the number of children
   * it has.
   */
  public int sizeForDebugging() {
    return size;
  }

  @Override
  public void put(ActionInput input, FileArtifactValue metadata, @Nullable Artifact depOwner) {
    putWithNoDepOwner(input, metadata);
  }

  @Override
  public void putRunfilesMetadata(
      Artifact input, RunfilesArtifactValue metadata, @Nullable Artifact depOwner) {
    checkArgument(isRunfilesTree(input));

    int oldIndex = putIfAbsent(input, metadata);
    Preconditions.checkState(oldIndex == -1);

    runfilesTrees.add(metadata.getRunfilesTree());
  }

  @Override
  public void putTreeArtifact(
      SpecialArtifact tree, TreeArtifactValue treeArtifactValue, @Nullable Artifact depOwner) {
    // Use a placeholder value so that we don't have to create a new trie entry if the entry is
    // already in the map.
    int oldIndex = putIfAbsent(tree, PLACEHOLDER);
    if (oldIndex != -1) {
      checkArgument(
          isATreeArtifact((ActionInput) keys[oldIndex]),
          "Tried to overwrite file with a tree artifact: '%s' with the same exec path",
          tree);
      return;
    }

    treeArtifactsRoot.add(tree.getExecPath(), treeArtifactValue);
    values[size - 1] = treeArtifactValue;
  }

  public void putWithNoDepOwner(ActionInput input, FileArtifactValue metadata) {
    checkArgument(
        !isATreeArtifact(input),
        "Can't add tree artifact: %s using put -- please use putTreeArtifact for that",
        input);
    checkArgument(!isRunfilesTree(input));

    int oldIndex = putIfAbsent(input, metadata);
    checkArgument(
        oldIndex == -1 || !isATreeArtifact((ActionInput) keys[oldIndex]),
        "Tried to overwrite tree artifact with a file: '%s' with the same exec path",
        input);
  }

  private int putIfAbsent(ActionInput input, Object metadata) {
    Preconditions.checkNotNull(input);
    if (size >= keys.length) {
      resize();
    }
    String path = input.getExecPathString();
    int hashCode = path.hashCode();
    int index = hashCode & (table.length - 1);
    int nextIndex = table[index];
    if (nextIndex == -1) {
      table[index] = size;
    } else {
      do {
        index = nextIndex;
        if (hashCode == paths[index].hashCode() && Objects.equal(path, paths[index])) {
          return index;
        }
        nextIndex = next[index];
      } while (nextIndex != -1);
      next[index] = size;
    }
    next[size] = -1;
    keys[size] = input;
    paths[size] = input.getExecPathString();
    values[size] = metadata;
    size++;
    return -1;
  }

  @VisibleForTesting
  void clear() {
    Arrays.fill(table, -1);
    Arrays.fill(next, -1);
    Arrays.fill(keys, null);
    Arrays.fill(paths, null);
    Arrays.fill(values, null);
    size = 0;
    treeArtifactsRoot = new TrieArtifact();
    runfilesTrees = new ArrayList<>();
  }

  private void resize() {
    // Resize the data containers.
    keys = Arrays.copyOf(keys, size * 2);
    paths = Arrays.copyOf(paths, size * 2);
    values = Arrays.copyOf(values, size * 2);
    next = Arrays.copyOf(next, size * 2);

    // Resize and recreate the table and links if necessary. We can take shortcuts here as we know
    // there are no duplicate keys.
    if (table.length < size * 2) {
      table = new int[table.length * 2];
      next = new int[size * 2];
      Arrays.fill(table, -1);
      for (int i = 0; i < size; i++) {
        int index = paths[i].hashCode() & (table.length - 1);
        next[i] = table[index];
        table[index] = i;
      }
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("size", size)
        .add("all-files", sizeForDebugging())
        .add("first-fifty-keys", Arrays.stream(keys).limit(50).collect(toList()))
        .add("first-fifty-values", Arrays.stream(values).limit(50).collect(toList()))
        .add("first-fifty-paths", Arrays.stream(paths).limit(50).collect(toList()))
        .toString();
  }

  private static boolean isATreeArtifact(ActionInput input) {
    return input instanceof SpecialArtifact && ((SpecialArtifact) input).isTreeArtifact();
  }

  private static boolean isRunfilesTree(ActionInput input) {
    return input instanceof Artifact && ((Artifact) input).isRunfilesTree();
  }
}
