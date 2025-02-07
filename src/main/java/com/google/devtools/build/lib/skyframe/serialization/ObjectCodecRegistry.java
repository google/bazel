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

package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static java.util.Comparator.comparing;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.io.Serializable;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Registry class for handling {@link ObjectCodec} mappings. Codecs are indexed by {@link String}
 * classifiers and assigned deterministic numeric identifiers for more compact on-the-wire
 * representation if desired.
 */
public class ObjectCodecRegistry {
  /** Creates a new, empty builder. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private final boolean allowDefaultCodec;

  private final ConcurrentMap<Class<?>, CodecDescriptor> classMappedCodecs;
  private final ImmutableList<CodecDescriptor> tagMappedCodecs;

  private final int referenceConstantsStartTag;
  private final IdentityHashMap<Object, Integer> referenceConstantsMap;
  private final ImmutableList<Object> referenceConstants;

  /** This is sorted, but we need index-based access. */
  private final ImmutableList<String> classNames;

  private final IdentityHashMap<String, Supplier<CodecDescriptor>> dynamicCodecs;

  @Nullable private final byte[] checksum;

  private ObjectCodecRegistry(
      ImmutableSet<ObjectCodec<?>> memoizingCodecs,
      ImmutableList<Object> referenceConstants,
      ImmutableSortedSet<String> classNames,
      ImmutableList<String> excludedClassNamePrefixes,
      boolean allowDefaultCodec,
      boolean computeChecksum)
      throws IOException, NoSuchAlgorithmException {
    // Mimic what com.google.devtools.build.lib.util.Fingerprint does. Using it directly would
    // require untangling a circular dependency.
    MessageDigest messageDigest = null;
    CodedOutputStream checksum = null;
    if (computeChecksum) {
      messageDigest = MessageDigest.getInstance("SHA-256");
      checksum =
          CodedOutputStream.newInstance(
              new DigestOutputStream(ByteStreams.nullOutputStream(), messageDigest),
              /*bufferSize=*/ 1024);
      checksum.writeBoolNoTag(allowDefaultCodec);
    }
    this.allowDefaultCodec = allowDefaultCodec;

    int nextTag = 1; // 0 is reserved for null.
    this.classMappedCodecs =
        new ConcurrentHashMap<>(
            memoizingCodecs.size(), 0.75f, Runtime.getRuntime().availableProcessors());
    ImmutableList.Builder<CodecDescriptor> tagMappedMemoizingCodecsBuilder =
        ImmutableList.builderWithExpectedSize(memoizingCodecs.size());
    nextTag =
        processCodecs(
            memoizingCodecs, nextTag, tagMappedMemoizingCodecsBuilder, classMappedCodecs, checksum);
    this.tagMappedCodecs = tagMappedMemoizingCodecsBuilder.build();

    referenceConstantsStartTag = nextTag;
    referenceConstantsMap = new IdentityHashMap<>();
    for (Object constant : referenceConstants) {
      referenceConstantsMap.put(constant, nextTag);
      addToChecksum(checksum, nextTag, constant.getClass().getName());
      nextTag++;
    }
    this.referenceConstants = referenceConstants;

    this.classNames =
        classNames.stream()
            .filter((str) -> isAllowed(str, excludedClassNamePrefixes))
            .collect(toImmutableList());
    this.dynamicCodecs = createDynamicCodecs(this.classNames, nextTag, checksum);
    if (computeChecksum) {
      checksum.flush();
      this.checksum = messageDigest.digest();
    } else {
      this.checksum = null;
    }
  }

  public CodecDescriptor getCodecDescriptorForObject(Object obj)
      throws SerializationException.NoCodecException {
    Class<?> type = obj.getClass();
    CodecDescriptor descriptor = getCodecDescriptor(type);
    if (descriptor != null) {
      return descriptor;
    }
    if (!allowDefaultCodec) {
      throw new SerializationException.NoCodecException(
          "No codec available for " + type + " and default fallback disabled");
    }
    if (obj instanceof Enum) {
      // Enums must be serialized using declaring class.
      type = ((Enum<?>) obj).getDeclaringClass();
    }
    return getDynamicCodecDescriptor(type.getName(), type);
  }

  /**
   * Returns a {@link CodecDescriptor} for the given type or null if none found.
   *
   * <p>Also checks if there are codecs for a superclass of the given type.
   */
  @Nullable
  private CodecDescriptor getCodecDescriptor(Class<?> type) {
    for (Class<?> nextType = type; nextType != null; nextType = nextType.getSuperclass()) {
      CodecDescriptor result = classMappedCodecs.get(nextType);
      if (result != null) {
        if (nextType != type) {
          classMappedCodecs.put(type, result);
        }
        return result;
      }
    }
    return null;
  }

  @Nullable
  Object maybeGetConstantByTag(int tag) {
    if (referenceConstantsStartTag <= tag
        && tag < referenceConstantsStartTag + referenceConstants.size()) {
      return referenceConstants.get(tag - referenceConstantsStartTag);
    }
    return null;
  }

  @Nullable
  public Integer maybeGetTagForConstant(Object object) {
    return referenceConstantsMap.get(object);
  }

  /** Returns the {@link CodecDescriptor} associated with the supplied tag. */
  CodecDescriptor getCodecDescriptorByTag(int tag) throws SerializationException.NoCodecException {
    int tagOffset = tag - 1; // 0 reserved for null
    if (tagOffset < 0) {
      throw new SerializationException.NoCodecException("No codec available for tag " + tag);
    }
    if (tagOffset < tagMappedCodecs.size()) {
      return tagMappedCodecs.get(tagOffset);
    }

    tagOffset -= tagMappedCodecs.size();
    tagOffset -= referenceConstants.size();
    if (!allowDefaultCodec || tagOffset < 0 || tagOffset >= classNames.size()) {
      throw new SerializationException.NoCodecException("No codec available for tag " + tag);
    }
    return getDynamicCodecDescriptor(classNames.get(tagOffset), /*type=*/ null);
  }

  /**
   * Returns a checksum computed from the tag mappings that make up this registry.
   *
   * <p>The checksum can be used to ensure consistent serialization semantics across servers.
   *
   * <p>Returns {@code null} if this instance was not configured to compute a checksum via {@link
   * Builder#computeChecksum(boolean)}.
   */
  @Nullable
  public byte[] getChecksum() {
    return checksum == null ? null : checksum.clone();
  }

  /**
   * Creates a builder using the current contents of this registry.
   *
   * <p>This is much more efficient than scanning multiple times.
   */
  public Builder getBuilder() {
    Builder builder = newBuilder();
    builder.setAllowDefaultCodec(allowDefaultCodec);
    for (Map.Entry<Class<?>, CodecDescriptor> entry : classMappedCodecs.entrySet()) {
      builder.add(entry.getValue().codec());
    }

    for (Object constant : referenceConstants) {
      builder.addReferenceConstant(constant);
    }

    for (String className : classNames) {
      builder.addClassName(className);
    }
    return builder;
  }

  ImmutableList<String> classNames() {
    return classNames;
  }

  /**
   * Describes encoding logic.
   *
   * @param tag Unique identifier for the associated codec. Intended to be used as a compact
   *     on-the-wire representation of an encoded object's type. Returns a value ≥ 1. 0 is a special
   *     tag representing null while negative numbers are reserved for backreferences.
   * @param codec The underlying codec.
   */
  record CodecDescriptor(int tag, ObjectCodec<?> codec) {
    CodecDescriptor {
      // Check that the tag is not a reserved value.
      Preconditions.checkArgument(tag >= 1);
    }
  }

  /** Builder for {@link ObjectCodecRegistry}. */
  public static class Builder {
    private final SequencedMap<Class<?>, ObjectCodec<?>> codecs = new LinkedHashMap<>();
    private final ImmutableList.Builder<Object> referenceConstantsBuilder = ImmutableList.builder();
    private final ImmutableSortedSet.Builder<String> classNames = ImmutableSortedSet.naturalOrder();
    private final ImmutableList.Builder<String> excludedClassNamePrefixes = ImmutableList.builder();
    private boolean allowDefaultCodec = true;
    private boolean computeChecksum = false;

    /**
     * Adds the given codec. If a codec for this codec's encoded class already exists in the
     * registry, it is overwritten.
     */
    @CanIgnoreReturnValue
    public Builder add(ObjectCodec<?> codec) {
      codecs.put(codec.getEncodedClass(), codec);
      return this;
    }

    /**
     * Set whether or not we allow fallback to java serialization when no matching codec is found.
     */
    @CanIgnoreReturnValue
    public Builder setAllowDefaultCodec(boolean allowDefaultCodec) {
      this.allowDefaultCodec = allowDefaultCodec;
      return this;
    }

    /**
     * Adds a constant value by reference. Any value encountered during serialization which {@code
     * == object} will be replaced by {@code object} upon deserialization. Interned objects and
     * effective singletons are ideal for reference constants.
     *
     * <p>These constants should be interned or effectively interned: it should not be possible to
     * create objects that should be considered equal in which one has an element of this list and
     * the other does not, since that would break bit-for-bit equality of the objects' serialized
     * bytes when used in {@link com.google.devtools.build.skyframe.SkyKey}s.
     *
     * <p>Note that even {@link Boolean} does not satisfy this constraint, since {@code new
     * Boolean(true)} is allowed, but upon deserialization, when a {@code boolean} is boxed to a
     * {@link Boolean}, it will always be {@link Boolean#TRUE} or {@link Boolean#FALSE}.
     *
     * <p>The same is not true for an empty {@link ImmutableList}, since an empty non-{@link
     * ImmutableList} will not serialize to an {@link ImmutableList}, and so won't be deserialized
     * to an empty {@link ImmutableList}. If an object has a list field, and one codepath passes in
     * an empty {@link ArrayList} and another passes in an empty {@link ImmutableList}, and two
     * objects constructed in this way can be considered equal, then those two objects already do
     * not serialize bit-for-bit identical disregarding this list of constants, since the list
     * object's codec will be different for the two objects.
     */
    @CanIgnoreReturnValue
    public Builder addReferenceConstant(Object object) {
      referenceConstantsBuilder.add(object);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addReferenceConstants(Iterable<?> referenceConstants) {
      referenceConstantsBuilder.addAll(referenceConstants);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addClassName(String className) {
      classNames.add(className);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder excludeClassNamePrefix(String classNamePrefix) {
      excludedClassNamePrefixes.add(classNamePrefix);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder computeChecksum(boolean computeChecksum) {
      this.computeChecksum = computeChecksum;
      return this;
    }

    public ObjectCodecRegistry build() {
      try {
        return new ObjectCodecRegistry(
            ImmutableSet.copyOf(codecs.values()),
            referenceConstantsBuilder.build(),
            classNames.build(),
            excludedClassNamePrefixes.build(),
            allowDefaultCodec,
            computeChecksum);
      } catch (IOException | NoSuchAlgorithmException e) {
        throw new IllegalStateException("Unexpected exception while building codec registry", e);
      }
    }
  }

  private static int processCodecs(
      Iterable<? extends ObjectCodec<?>> memoizingCodecs,
      int nextTag,
      ImmutableList.Builder<CodecDescriptor> tagMappedCodecsBuilder,
      ConcurrentMap<Class<?>, CodecDescriptor> codecsBuilder,
      @Nullable CodedOutputStream checksum)
      throws IOException {
    // First, register all codecs and their monotonically increasing tag numbers in a stable
    // alphabetic sort order, using their primary encoded class as the key.
    var sortedCodecDescriptors =
        Streams.mapWithIndex(
                // Sort the codecs by their primary encoded class name.
                stream(memoizingCodecs).sorted(comparing(o -> o.getEncodedClass().getName())),
                // Then create a codec descriptor for each codec.
                (codec, idx) ->
                    // idx is small enough to be casted from long to int without loss of
                    // information.
                    new CodecDescriptor((int) idx + nextTag, codec))
            .collect(toImmutableList());

    // Then, perform checksumming and check that there's a unique codec descriptor for each encoded
    // class.
    for (CodecDescriptor codecDescriptor : sortedCodecDescriptors) {
      addToChecksum(checksum, codecDescriptor.tag(), codecDescriptor.codec().getClass().getName());

      CodecDescriptor previousCodecDescriptor =
          codecsBuilder.put(codecDescriptor.codec().getEncodedClass(), codecDescriptor);
      Preconditions.checkState(
          previousCodecDescriptor == null,
          "found duplicate codec descriptor for %s, was: %s, new: %s",
          codecDescriptor.codec().getEncodedClass(),
          previousCodecDescriptor,
          codecDescriptor);
    }

    // Finally, for all codec descriptors, map their additional encoded classes, and overwrite
    // any existing descriptor mappings.
    for (CodecDescriptor codecDescriptor : sortedCodecDescriptors) {
      for (Class<?> otherClass : codecDescriptor.codec().additionalEncodedClasses()) {
        codecsBuilder.put(otherClass, codecDescriptor);
      }
    }

    // Append all new descriptors into the builder.
    tagMappedCodecsBuilder.addAll(sortedCodecDescriptors);

    return nextTag + sortedCodecDescriptors.size();
  }

  private static IdentityHashMap<String, Supplier<CodecDescriptor>> createDynamicCodecs(
      ImmutableList<String> classNames, int nextTag, @Nullable CodedOutputStream checksum)
      throws IOException {
    IdentityHashMap<String, Supplier<CodecDescriptor>> dynamicCodecs =
        new IdentityHashMap<>(classNames.size());
    for (String className : classNames) {
      int tag = nextTag++;
      dynamicCodecs.put(
          className, Suppliers.memoize(() -> createDynamicCodecDescriptor(tag, className)));
      addToChecksum(checksum, tag, className);
    }
    return dynamicCodecs;
  }

  private static void addToChecksum(@Nullable CodedOutputStream checksum, int tag, String className)
      throws IOException {
    if (checksum != null) {
      checksum.writeInt32NoTag(tag);

      // Trim class names of lambdas to the enclosing class. The lambda class itself is named
      // nondeterministically.
      int lambdaIndex = className.indexOf("$$Lambda");
      if (lambdaIndex != -1) {
        className = className.substring(0, lambdaIndex);
      }
      checksum.writeStringNoTag(className);
    }
  }

  private static boolean isAllowed(
      String className, ImmutableList<String> excludedClassNamePefixes) {
    for (String excludedClassNamePrefix : excludedClassNamePefixes) {
      if (className.startsWith(excludedClassNamePrefix)) {
        return false;
      }
    }
    return true;
  }

  /** For enums, this method must only be called for the declaring class. */
  @Nullable
  private static CodecDescriptor createDynamicCodecDescriptor(int tag, String className) {
    try {
      Class<?> type = Class.forName(className);
      if (type.isEnum()) {
        return createCodecDescriptorForEnum(tag, type);
      }
      if (MessageLite.class.isAssignableFrom(type)) {
        return createCodecDescriptorForProto(tag, type);
      }
      return new CodecDescriptor(tag, new DynamicCodec(type));
    } catch (ReflectiveOperationException e) {
      new SerializationException("Could not create codec for type: " + className, e)
          .printStackTrace();
      return null;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CodecDescriptor createCodecDescriptorForEnum(int tag, Class<?> enumType) {
    return new CodecDescriptor(tag, new EnumCodec(enumType));
  }

  @SuppressWarnings("unchecked")
  private static CodecDescriptor createCodecDescriptorForProto(int tag, Class<?> protoType) {
    return new CodecDescriptor(tag, new MessageLiteCodec((Class<? extends MessageLite>) protoType));
  }

  private CodecDescriptor getDynamicCodecDescriptor(String className, @Nullable Class<?> type)
      throws SerializationException.NoCodecException {
    Supplier<CodecDescriptor> supplier = dynamicCodecs.get(className);
    if (supplier != null) {
      CodecDescriptor descriptor = supplier.get();
      if (descriptor == null) {
        throw new SerializationException.NoCodecException(
            "There was a problem creating a codec for " + className + ". Check logs for details",
            type);
      }
      return descriptor;
    }
    if (type != null && LambdaCodec.isProbablyLambda(type)) {
      if (Serializable.class.isAssignableFrom(type)) {
        // LambdaCodec is hidden away as a codec for Serializable. This avoids special-casing it in
        // all places we look up a codec, and doesn't clash with anything else because Serializable
        // is an interface, not a class.
        return classMappedCodecs.get(Serializable.class);
      } else {
        throw new SerializationException.NoCodecException(
            "No default codec available for "
                + className
                + ". If this is a lambda, try casting it to (type & Serializable), like "
                + "(Supplier<String> & Serializable)",
            type);
      }
    }
    throw new SerializationException.NoCodecException(
        "No default codec available for " + className, type);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("checksum", checksum)
        .add("allowDefaultCodec", allowDefaultCodec)
        .add("classMappedCodecs.size", classMappedCodecs.size())
        .add("tagMappedCodecs.size", tagMappedCodecs.size())
        .add("referenceConstantsStartTag", referenceConstantsStartTag)
        .add("referenceConstants.size", referenceConstants.size())
        .add("classNames.size", classNames.size())
        .add("dynamicCodecs.size", dynamicCodecs.size())
        .toString();
  }
}
