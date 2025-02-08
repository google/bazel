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
package com.google.devtools.build.lib.skyframe.serialization;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/** A nested-set like class for codec testing. */
final class NotNestedSet {
  private static final Object[] EMPTY_CONTENTS = new Object[0];

  private Object[] contents; // mutable is more convenient in test code

  NotNestedSet(Object[] contents) {
    this.contents = contents;
  }

  private NotNestedSet() {}

  Object[] getContents() {
    return contents;
  }

  private static final int MAX_RANDOM_ELEMENTS = 5;

  /** Helper for constructing contents or nested contents. */
  static Object[] createRandomLeafArray(Random rng, Function<Random, Object> elementFactory) {
    int count = rng.nextInt(MAX_RANDOM_ELEMENTS - 1) + 1;
    Object[] array = new Object[count];
    for (int i = 0; i < count; i++) {
      array[i] = elementFactory.apply(rng);
    }
    return array;
  }

  /**
   * Edge density parameter.
   *
   * <p>Graph construction always selects for every node in layer N+1 some parent node in layer N.
   * After that, additional random edges are added. For any pair of nodes (x, y) in layers M, N with
   * {@code M < N}, this is the probability that x is an additional parent of y.
   */
  private static final double EXTRA_PARENT_PROBABILITY = 0.01;

  static NotNestedSet createRandom(
      Random rng, int maxLayers, int maxNodesPerLayer, Function<Random, Object> elementFactory) {
    // Creates a random DAG layer by layer.
    int layerCount = rng.nextInt(maxLayers - 1) + 1;

    // First creates the nodes of each layer.
    ArrayList<ArrayList<NodeBuilder>> layers = new ArrayList<>(layerCount);
    for (int i = 0; i < layerCount; i++) {
      int nodeCount = rng.nextInt(maxNodesPerLayer - 1) + 1;
      ArrayList<NodeBuilder> layer = new ArrayList<>(nodeCount);
      for (int j = 0; j < nodeCount; j++) {
        layer.add(new NodeBuilder());
      }
      layers.add(layer);
    }

    // Nexts populates edges.
    for (int i = layerCount - 1; i > 0; i--) {
      ArrayList<NodeBuilder> parentLayer = layers.get(i - 1);
      int parentLayerSize = parentLayer.size();
      int childLayerSize = layers.get(i).size();

      for (int childIndex = 0; childIndex < childLayerSize; childIndex++) {
        // Ensures that every node has at least one parent in the previous layer (except the top
        // layer).
        parentLayer.get(rng.nextInt(parentLayerSize)).addChild(i, childIndex);

        // For every previous node in every previous layer, possibly adds a random edge.
        for (int previousLayer = 0; previousLayer < i; previousLayer++) {
          for (NodeBuilder builder : layers.get(previousLayer)) {
            if (rng.nextDouble() < EXTRA_PARENT_PROBABILITY) {
              builder.addChild(i, childIndex);
            }
          }
        }
      }
    }

    // Uses the layers to build the result, bottom-up.
    for (int i = layerCount - 1; i >= 0; i--) {
      ArrayList<NodeBuilder> layer = layers.get(i);
      for (NodeBuilder builder : layer) {
        if (i == layerCount - 1) {
          builder.value = createRandomLeafArray(rng, elementFactory);
          continue;
        }
        // Inserts additional random elements into each non-leaf node.
        int randomElementCount = rng.nextInt(MAX_RANDOM_ELEMENTS);
        ArrayList<Object> values = new ArrayList<>(builder.children.size() + randomElementCount);
        for (Coordinate child : builder.children) {
          values.add(layers.get(child.layer()).get(child.index()).value);
        }
        for (int j = 0; j < randomElementCount; j++) {
          values.add(elementFactory.apply(rng));
        }
        Collections.shuffle(values, rng);
        builder.value = values.toArray(new Object[0]);
      }
    }

    ArrayList<NodeBuilder> topLayer = layers.get(0);
    Object[] root = new Object[topLayer.size()];
    for (int i = 0; i < topLayer.size(); i++) {
      root[i] = topLayer.get(i).value;
    }
    return new NotNestedSet(root);
  }

  private static final class NodeBuilder {
    private final HashSet<Coordinate> children = new HashSet<>();
    private Object[] value;

    private void addChild(int layer, int index) {
      children.add(Coordinate.create(layer, index));
    }
  }

  record Coordinate(int layer, int index) {
    private static Coordinate create(int layer, int index) {
      return new Coordinate(layer, index);
    }

  }

  private static void setContents(NotNestedSet set, Object contents) {
    set.contents = (Object[]) contents;
  }

  static final class NotNestedSetCodec extends AsyncObjectCodec<NotNestedSet> {
    private final NestedArrayCodec innerCodec;

    NotNestedSetCodec(NestedArrayCodec innerCodec) {
      this.innerCodec = innerCodec;
    }

    @Override
    public Class<NotNestedSet> getEncodedClass() {
      return NotNestedSet.class;
    }

    @Override
    public void serialize(
        SerializationContext context, NotNestedSet set, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(set.contents, /* distinguisher= */ null, innerCodec, codedOut);
    }

    @Override
    public NotNestedSet deserializeAsync(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      NotNestedSet value = new NotNestedSet();
      context.registerInitialValue(value);
      context.getSharedValue(
          codedIn, /* distinguisher= */ null, innerCodec, value, NotNestedSet::setContents);
      return value;
    }
  }

  static final class NotNestedSetDeferredCodec extends DeferredObjectCodec<NotNestedSet> {
    private final NestedArrayCodec innerCodec;

    NotNestedSetDeferredCodec(NestedArrayCodec innerCodec) {
      this.innerCodec = innerCodec;
    }

    @Override
    public Class<NotNestedSet> getEncodedClass() {
      return NotNestedSet.class;
    }

    @Override
    public void serialize(
        SerializationContext context, NotNestedSet set, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.putSharedValue(set.contents, /* distinguisher= */ null, innerCodec, codedOut);
    }

    @Override
    public DeferredValue<NotNestedSet> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      NotNestedSet value = new NotNestedSet();
      context.getSharedValue(
          codedIn, /* distinguisher= */ null, innerCodec, value, NotNestedSet::setContents);
      return () -> value;
    }
  }

  static final class NestedArrayCodec extends DeferredObjectCodec<Object[]> {
    @SuppressWarnings("ArrayAsKeyOfSetOrMap") // deliberate use of reference equality
    private final ConcurrentHashMap<Object[], InjectedDelay> serializeDelays =
        new ConcurrentHashMap<>();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<Object[]> getEncodedClass() {
      return Object[].class;
    }

    @Override
    public void serialize(
        SerializationContext context, Object[] nestedArray, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      InjectedDelay delay = serializeDelays.get(nestedArray);
      if (delay != null) {
        delay.entered.countDown();
        try {
          delay.waitFor.await();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
      int length = nestedArray.length;
      codedOut.writeInt32NoTag(length);
      for (int i = 0; i < length; i++) {
        Object child = nestedArray[i];
        if (child instanceof Object[]) {
          codedOut.writeBoolNoTag(true);
          context.putSharedValue(
              (Object[]) child, /* distinguisher= */ null, /* codec= */ this, codedOut);
        } else {
          codedOut.writeBoolNoTag(false);
          context.serialize(child, codedOut);
        }
      }
    }

    @Override
    public DeferredValue<Object[]> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      int length = codedIn.readInt32();
      if (length == 0) {
        return () -> EMPTY_CONTENTS;
      }
      Object[] values = new Object[length];
      for (int i = 0; i < length; i++) {
        final int indexForCapture = i;
        if (codedIn.readBool()) {
          context.getSharedValue(
              codedIn,
              /* distinguisher= */ null,
              /* codec= */ this,
              values,
              (array, value) -> array[indexForCapture] = value);
        } else {
          context.deserialize(codedIn, values, (array, value) -> array[indexForCapture] = value);
        }
      }
      return () -> values;
    }

    /**
     * Injects a controllable delay when serializing the specified {@code nestedArray}.
     *
     * @param entered {@link CountDownLatch#countDown} called on this latch when serialization of
     *     {@code nestedArray} is requested. This countdown occurs before the wait and used by the
     *     caller for coordination.
     * @param waitFor {@link CountDownLatch#await} is called on this latch to inject the delay (only
     *     after calling {@code entered.countDown()})
     */
    void injectSerializeDelay(
        Object[] nestedArray, CountDownLatch entered, CountDownLatch waitFor) {
      serializeDelays.put(nestedArray, new InjectedDelay(entered, waitFor));
    }
  }

  private static final class InjectedDelay {
    private final CountDownLatch entered;
    private final CountDownLatch waitFor;

    private InjectedDelay(CountDownLatch entered, CountDownLatch waitFor) {
      this.entered = entered;
      this.waitFor = waitFor;
    }
  }
}
