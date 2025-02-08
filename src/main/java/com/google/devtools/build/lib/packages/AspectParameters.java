// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Keep;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Objects of this class contain values of some attributes of rules. Used for passing this
 * information to the aspects.
 */
public final class AspectParameters {
  private final ImmutableMultimap<String, String> attributes;

  private AspectParameters(Multimap<String, String> attributes) {
    this.attributes = ImmutableMultimap.copyOf(attributes);
  }

  @SerializationConstant @VisibleForSerialization
  public static final AspectParameters EMPTY = new AspectParameters(ImmutableMultimap.of());

  private static AspectParameters create(ImmutableMultimap<String, String> attributes) {
    if (attributes.isEmpty()) {
      return EMPTY;
    }
    return new AspectParameters(attributes);
  }

  /** A builder for {@link AspectParameters} class. */
  public static class Builder {
    private final ImmutableMultimap.Builder<String, String> attributes =
        ImmutableMultimap.builder();

    /** Adds a new pair of attribute-value. */
    @CanIgnoreReturnValue
    public Builder addAttribute(String name, String value) {
      attributes.put(name, value);
      return this;
    }

    /**
     * Creates a new instance of {@link AspectParameters} class.
     */
    public AspectParameters build() {
      return create(attributes.build());
    }
  }

  /**
   * Returns collection of values for specified key, or an empty collection if key is missing.
   */
  public ImmutableCollection<String> getAttribute(String key) {
    return attributes.get(key);
  }

  public ImmutableMultimap<String, String> getAttributes() {
    return attributes;
  }

  /**
   * Similar to {@link #getAttribute}}, but asserts that there's only one value for the provided
   * key.
   * Uses Guava's {@link Iterables#getOnlyElement}, which may throw exceptions if there isn't
   * exactly one element.
   */
  public String getOnlyValueOfAttribute(String key) {
    return getOnlyElement(getAttribute(key));
  }

  public boolean isEmpty() {
    return this.equals(AspectParameters.EMPTY);
  }

  @Override
  @SuppressWarnings("UndefinedEquals") // ImmutableMultimap inherits equals from AbstractMultimap
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AspectParameters)) {
      return false;
    }
    AspectParameters that = (AspectParameters) other;
    return Objects.equals(this.attributes, that.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(attributes);
  }

  @Override
  public String toString() {
    return attributes.toString();
  }

  /**
   * This codec causes {@link AspectParameters} memoization to use {@link Object#equals}.
   *
   * <p>This improves determinism over memoization using reference quality, which can result in
   * different serialized representations of equivalent values.
   */
  @Keep
  private static final class Codec extends LeafObjectCodec<AspectParameters> {
    @Override
    public Class<AspectParameters> getEncodedClass() {
      return AspectParameters.class;
    }

    @Override
    public void serialize(
        LeafSerializationContext context, AspectParameters obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      ImmutableMap<String, Collection<String>> attributes = obj.attributes.asMap();
      codedOut.writeInt32NoTag(attributes.size());
      for (Map.Entry<String, Collection<String>> entry : attributes.entrySet()) {
        context.serializeLeaf(entry.getKey(), stringCodec(), codedOut);
        Collection<String> values = entry.getValue();
        codedOut.writeInt32NoTag(values.size());
        for (String value : values) {
          context.serializeLeaf(value, stringCodec(), codedOut);
        }
      }
    }

    @Override
    public AspectParameters deserialize(
        LeafDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      int size = codedIn.readInt32();
      var builder = ImmutableMultimap.<String, String>builder();
      for (int i = 0; i < size; i++) {
        String key = context.deserializeLeaf(codedIn, stringCodec());
        int valuesCount = codedIn.readInt32();
        for (int j = 0; j < valuesCount; j++) {
          String value = context.deserializeLeaf(codedIn, stringCodec());
          builder.put(key, value);
        }
      }
      return create(builder.build());
    }
  }
}
