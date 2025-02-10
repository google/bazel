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
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import static com.google.common.hash.Hashing.murmur3_128;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.getTypeName;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A utility for determining compact, canonical representations of arbitrary objects.
 *
 * <p>The {@code Canonizer} provides a more robust solution for generating consistent fingerprints,
 * even for complex, potentially cyclic data structures.
 *
 * <p>The core of this utility is a new object canonization algorithm that enables consistent
 * fingerprinting by transforming objects into a canonical form. The algorithm leverages the ability
 * to consistently order edges within the object graph by their labels. This ordered-edge property
 * is crucial for mitigating the combinatorial explosion that typically makes general graph
 * canonization intractable. It is assumed that all data is ordered, which is a valid assumption in
 * the context of serialization where this utility is intended to be used.
 *
 * <p>While graph canonization is generally considered a very difficult problem (often associated
 * with the complexity classes NP-complete or Graph Isomorphism), the implemented algorithm exhibits
 * surprisingly good performance in practice. This is due, in part, to a preprocessing step that
 * uses local fingerprinting to partition the object graph into smaller components. Empirical
 * observations suggest that these partitions are usually very small.
 *
 * <p><b>Algorithm Description: Object Canonization via Partition Refinement</b>
 *
 * <p><i>Graph Traversal and Node Creation:</i> uses the {@link GraphDataCollector} to traverse the
 * graph and create {@link Node} instances for non-inlined objects. Inlined objects are fully
 * incorporated into {@link Node#localFingerprint}. {@link Node#completeAggregate} triggers
 * computation of the local fingerprint, a hash of its type and its children's representations
 * (either fingerprints or placeholders for child nodes).
 *
 * <p><i>Partition Refinement:</i> starts with an initial {@link Partition} containing all the
 * nodes. It repeatedly calls {@link Partition#refine} on partitions from {@link #dirtyPartitions}
 * until it is empty. The {@link Partition#refine} method computes a {@link PartitionKey} for each
 * node in the partition and uses it for splitting. Splitting moves nodes to new partitions, which
 * causes any node that was previously pointing at it to mark its partition dirty.
 *
 * <p><b>Key Concepts:</b>
 *
 * <ul>
 *   <li><i>Partition Refinement:</i> The core of the algorithm is the iterative refinement of
 *       partitions. Each partition starts as a set of potentially equivalent objects. The {@code
 *       refine()} method splits partitions based on the {@link PartitionKey} of their members.
 *   <li><i>Partition Key:</i> The {@link PartitionKey} of a {@link Node} is computed from its
 *       {@code localFingerprint} and the set of {@link Partition}s its children belong to. This
 *       ensures that nodes with different structures or that are connected to different equivalence
 *       classes will have different keys.
 *   <li><i>Local Fingerprint:</i> The {@code localFingerprint} of a {@link Node} is a hash that
 *       summarizes the node's type and its immediate children's representations (either
 *       fingerprints for leaf nodes or placeholders for other nodes). It does not depend on the
 *       specific identities of the child nodes, only their equivalence classes (partitions).
 *   <li><i>Ordered Edges:</i> The algorithm assumes that edges are ordered (e.g., based on field
 *       names or array indices). This ordering is crucial for consistently computing the {@code
 *       localFingerprint} and {@link PartitionKey}.
 *   <li><i>Dirty Queue:</i> The {@code dirtyPartitions} queue ensures that partitions are
 *       re-examined whenever one of their members' keys might have changed due to changes in child
 *       partitions.
 * </ul>
 *
 * <p><b>Complexity:</b>
 *
 * <p>The current implementation has a theoretical worst-case time complexity of O(|N|^2 log |N|),
 * where |N| is the number of nodes in the object graph. This is because, in the worst case, the
 * {@code refine()} method might re-process all nodes in a large partition in each iteration.
 * However, due to the use of local fingerprinting in the preprocessing step, the practical
 * performance is closer to O(|N|^2 log |P|), where |P| is the size of the largest partition.
 */
public final class Canonizer implements GraphDataCollector<Canonizer.Node> {
  private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

  /**
   * Reference-based identifiers for all objects.
   *
   * <p>The key is the traversed object. The value is a {@link String} if the object is one of the
   * special cased inline objects, {@link #outputByteArray}, {@link #outputInlineArray} or {@link
   * #outputEmptyAggregate}. Otherwise, the value is a {@link Partition}.
   */
  private final IdentityHashMap<Object, Object> identifiers;

  private final IdentityHashMap<Object, Node> nodes = new IdentityHashMap<>();

  /** A special sentinel node. */
  private final Node rootNode = new Node(/* obj= */ null, "root");

  /** A partition of {@link #nodes}. */
  private final ArrayList<Partition> partitions = new ArrayList<>();

  private final ArrayDeque<Partition> dirtyPartitions = new ArrayDeque<>();

  /**
   * Reflectively traverses {@code obj} and determines identifiers for traversed objects.
   *
   * @param registry if provided, used to lookup serialization constants
   * @param identifiers (output parameter) map from traversed objects to an object with identity
   *     equality semantics. When two objects have the same identifier, they are equivalent. (See
   *     {@link #identifiers} for implementation details of the identifier values).
   * @return a succinct, representation of {@code obj}, suitable for comparison. Null if no
   *     partitions are created, which can be the case if {@code obj} is completely handled as an
   *     inline object, or by {@link #outputByteArray}, {@link #outputInlineArray} or {@link
   *     #outputEmptyAggregate}.
   */
  @Nullable
  public static IsomorphismKey computePartitions(
      @Nullable ObjectCodecRegistry registry,
      Object obj,
      IdentityHashMap<Object, Object> identifiers) {
    var canonizer = new Canonizer(identifiers);
    new GraphTraverser<>(registry, canonizer)
        .traverseObject(/* label= */ null, obj, canonizer.rootNode);
    canonizer.runPartitionRefinement();
    canonizer.updateIdentifiersFromPartitions(identifiers);

    Node rootNode = canonizer.nodes.get(obj);
    if (rootNode == null) {
      return null; // Purely inline input.
    }
    Partition rootPartition = canonizer.nodes.get(obj).partition;
    return canonizer.computeIsomorphismKey(rootPartition);
  }

  static IdentityHashMap<Object, Object> computeIdentifiers(
      @Nullable ObjectCodecRegistry registry, Object obj) {
    var identifiers = new IdentityHashMap<Object, Object>();
    var unusedKey = computePartitions(registry, obj, identifiers);
    return identifiers;
  }

  private Canonizer(IdentityHashMap<Object, Object> identifiers) {
    this.identifiers = identifiers;
  }

  private void runPartitionRefinement() {
    Partition partition = new Partition(ImmutableList.copyOf(nodes.values()));
    partitions.add(partition);
    do {
      partition.enqueued = false;
      partition.refine();
    } while ((partition = dirtyPartitions.poll()) != null);
  }

  private void updateIdentifiersFromPartitions(IdentityHashMap<Object, Object> identifiers) {
    for (Partition partition : partitions) {
      for (Node node : partition.members) {
        identifiers.put(node.obj, partition);
      }
    }
  }

  IsomorphismKey computeIsomorphismKey(Partition root) {
    // Initializes all the IsomorphismKeys.
    IdentityHashMap<Partition, IsomorphismKey> keys = new IdentityHashMap<>();
    for (Partition partition : partitions) {
      keys.put(partition, new IsomorphismKey(partition.getLocalFingerprint()));
    }

    // Populates the connections between keys, now that all the keys have been constructed.
    for (Map.Entry<Partition, IsomorphismKey> entry : keys.entrySet()) {
      // By definition, partition members must be equivalent in their connections so it sufficies to
      // take one arbitrarily.
      Node representative = entry.getKey().members.get(0);
      for (ChildEdge child : representative.children) {
        switch (child) {
          case LeafChild leaf:
            break;
          case NodeChild node:
            entry.getValue().addLink(keys.get(node.getPartition()));
            break;
        }
      }
    }

    return keys.get(root); // Returns the root key.
  }

  @VisibleForTesting
  class Partition {
    private ImmutableList<Node> members;

    /** Status bit to avoid double-enqueuing. */
    private boolean enqueued = false;

    private Partition(ImmutableList<Node> members) {
      this.members = members;
      for (Node node : members) {
        node.setPartition(this);
      }
    }

    @VisibleForTesting
    String getLocalFingerprint() {
      // By definition, all members have the same local fingerprint, so taking the first suffices.
      return members.get(0).localFingerprint;
    }

    /**
     * Refines a partition, splitting its members by key.
     *
     * <p>Procedes in the following two phases.
     *
     * <ol>
     *   <li>Recomputes the keys of all members. If a node's key changes, moves it to the correct
     *       partition, creating a new one if needed.
     *   <li>Notifies parents of all the changed members, marking the partitions of those parents
     *       dirty.
     * </ol>
     */
    @SuppressWarnings("ReferenceEquality") // used to track largest group when iterating
    private void refine() {
      // The asymptotic bounds of the implementation can be improved here by carefully tracking
      // which specific nodes require a partition key computation.

      // Splits the nodes by key.
      var groups = new HashMap<PartitionKey, ArrayList<Node>>();
      ArrayList<Node> largestGroup = null;
      for (Node node : members) {
        ArrayList<Node> group =
            groups.computeIfAbsent(node.computePartitionKey(), unused -> new ArrayList<>());
        group.add(node);
        if (largestGroup == null || group.size() > largestGroup.size()) {
          largestGroup = group;
        }
      }

      if (groups.size() == 1) {
        return; // This partition is still valid because no splitting occurred.
      }

      // Creates partitions for all the groups and transfers nodes to the new partitions.
      for (ArrayList<Node> group : groups.values()) {
        if (group == largestGroup) {
          members = ImmutableList.copyOf(group); // This partition becomes the largest group.
          continue;
        }
        partitions.add(new Partition(ImmutableList.copyOf(group)));
      }

      // Notifies parents of changes in a second phase, after all partitions have been updated.
      for (ArrayList<Node> group : groups.values()) {
        if (group == largestGroup) {
          continue;
        }
        for (Node node : group) {
          node.notifyParentsOfPartitionChange();
        }
      }
    }

    private void enqueue() {
      if (enqueued) {
        return; // Already enqueued.
      }
      if (members.size() < 2) {
        return; // This partition can't be split further.
      }
      dirtyPartitions.offer(this);
      enqueued = true;
    }

    @Override
    public String toString() {
      return "Partition(id=" + hashCode() + ", size=" + members.size() + ")";
    }
  }

  /** An ephemeral object serving as the basis for splitting partitions. */
  record PartitionKey(String localFingerprint, ImmutableList<Partition> edges) {}

  /**
   * Corresponds to an object reached by traversal from the initial root object.
   *
   * <p>Maintains connections to parents to update them on partition change, which will modifies the
   * parent's {@link PartitionKey}.
   *
   * <p>Maintains connections to children in order to compute a {@link PartitionKey}.
   */
  static class Node implements GraphDataCollector.Sink {
    private static final String CHILD_NODE_PLACEHOLDER = "TESTUTILS_CANONIZER_PLACEHOLDER";

    @Nullable // null only for the root sentinel node
    private final Object obj;
    private final String descriptor;

    /**
     * The local fingerprint of {@link #obj}.
     *
     * <p>This incorporates a full description of {@link #obj}'s inline fields, and labels and
     * placeholders for where other objects connect. It does not contain any information about the
     * specific target of those connections, so remains independent of child changes.
     */
    @Nullable // initialized in `completeAggregate`
    private String localFingerprint;

    private final ArrayList<Node> parents = new ArrayList<>();
    private final ArrayList<ChildEdge> children = new ArrayList<>();

    private Partition partition;

    private Node(@Nullable Object obj, String descriptor) {
      this.obj = obj;
      this.descriptor = descriptor;
    }

    @Override
    public String toString() {
      return descriptor + '(' + partition + ')';
    }

    private void addChild(@Nullable String label, Node childNode) {
      if (obj == null) { // The root sentinel node has no edges.
        return;
      }

      children.add(new NodeChild(label, childNode));
      childNode.parents.add(this);
    }

    private void addLeafChild(@Nullable String label, String representation) {
      children.add(new LeafChild(prependLabel(label, representation)));
    }

    private PartitionKey computePartitionKey() {
      var childPartitions = ImmutableList.<Partition>builder();
      for (ChildEdge child : children) {
        switch (child) {
          case LeafChild leaf:
            break;
          case NodeChild node:
            childPartitions.add(node.getPartition());
            break;
        }
      }
      return new PartitionKey(localFingerprint, childPartitions.build());
    }

    private void setPartition(Partition partition) {
      this.partition = partition;
    }

    private void notifyParentsOfPartitionChange() {
      for (Node parent : parents) {
        parent.markDirty();
      }
    }

    private void markDirty() {
      partition.enqueue(); // Enqueues this node's partition.
    }

    @Override
    public void completeAggregate() {
      // With all the children added, it is possible to determine `localFingerprint`.
      var description = new StringBuilder(descriptor);
      for (ChildEdge child : children) {
        description.append(", ");
        switch (child) {
          case LeafChild leaf:
            description.append(leaf.labeledRepresentation());
            break;
          case NodeChild node:
            if (node.label != null) {
              description.append(node.label);
            }
            description.append(CHILD_NODE_PLACEHOLDER);
            break;
        }
      }
      this.localFingerprint = fingerprintString(description.toString());
    }
  }

  private static sealed interface ChildEdge permits NodeChild, LeafChild {}

  /**
   * Child having an immediate representation, like an inline value or constant.
   *
   * @param labeledRepresentation the leaf's label, together with its representation, often a
   *     fingerprint, but sometimes a simple inline string representation.
   */
  private record LeafChild(String labeledRepresentation) implements ChildEdge {}

  private static final class NodeChild implements ChildEdge {
    @Nullable private final String label;
    private final Node node;

    private NodeChild(@Nullable String label, Node node) {
      this.label = label;
      this.node = node;
    }

    private Partition getPartition() {
      return node.partition;
    }
  }

  @Override
  public void outputNull(@Nullable String label, Node parentNode) {
    parentNode.addLeafChild(label, "null");
  }

  @Override
  public void outputSerializationConstant(
      @Nullable String label, Class<?> type, int tag, Node parentNode) {
    String representation = getTypeName(type) + "[SERIALIZATION_CONSTANT:" + tag + ']';
    parentNode.addLeafChild(label, representation);
  }

  @Override
  public void outputWeakReference(@Nullable String label, Node parentNode) {
    parentNode.addLeafChild(label, WeakReference.class.getCanonicalName());
  }

  @Override
  public void outputInlineObject(
      @Nullable String label, Class<?> type, Object obj, Node parentNode) {
    // Emits the type, even for inline values. This avoids a possible ambiguities. For example, "-1"
    // could be a backreference, String, Integer, or other things if there were no type prefix.
    parentNode.addLeafChild(label, getTypeName(type) + ':' + obj);
  }

  @Override
  public void outputPrimitive(PrimitiveInfo info, Object parent, Node parentNode) {
    parentNode.addLeafChild(info.name() + '=', info.getText(parent));
  }

  @Override
  @Nullable
  public Descriptor checkCache(@Nullable String label, Class<?> type, Object obj, Node parentNode) {
    // During traversal, only String fingerprints are added to identifiers.
    String fingerprint = (String) identifiers.get(obj);
    if (fingerprint != null) {
      parentNode.addLeafChild(label, fingerprint);
      return null;
    }

    Node node = nodes.get(obj);
    if (node != null) {
      parentNode.addChild(label, node);
      return null;
    }

    return new Descriptor(getTypeName(type), nodes.size());
  }

  @Override
  public void outputByteArray(
      @Nullable String label, Descriptor descriptor, byte[] bytes, Node parentNode) {
    String representation = descriptor.description() + ": [" + HEX_FORMAT.formatHex(bytes) + ']';
    String fingerprint = fingerprintString(representation);
    identifiers.put(bytes, fingerprint);
    parentNode.addLeafChild(label, fingerprint);
  }

  @Override
  public void outputInlineArray(
      @Nullable String label, Descriptor descriptor, Object arr, Node parentNode) {
    StringBuilder representation = new StringBuilder(descriptor.description()).append(": [");
    int length = Array.getLength(arr);
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        representation.append(", ");
      }
      Object elt = Array.get(arr, i);
      if (elt != null) {
        representation.append(getTypeName(elt.getClass())).append(':');
      }
      representation.append(elt);
    }
    representation.append(']');
    String fingerprint = fingerprintString(representation.toString());
    identifiers.put(arr, fingerprint);
    parentNode.addLeafChild(label, fingerprint);
  }

  @Override
  public void outputEmptyAggregate(
      @Nullable String label, Descriptor descriptor, Object obj, Node parentNode) {
    String representation = descriptor.description() + " []";
    String fingerprint = fingerprintString(representation);
    identifiers.put(obj, fingerprint);
    parentNode.addLeafChild(label, fingerprint);
  }

  @Override
  public Node initAggregate(
      @Nullable String label, Descriptor descriptor, Object obj, Node parentNode) {
    var node = new Node(obj, descriptor.description());
    nodes.put(obj, node);
    parentNode.addChild(label, node);
    return node;
  }

  private static String prependLabel(@Nullable String label, String description) {
    return label == null ? description : label + description;
  }

  @VisibleForTesting
  static String fingerprintString(String text) {
    // Dumper relies on reference equality of these strings.
    return murmur3_128().hashUnencodedChars(text).toString().intern();
  }
}
