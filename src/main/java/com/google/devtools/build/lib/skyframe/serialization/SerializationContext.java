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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.CodecDescriptor;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.errorprone.annotations.ForOverride;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * API provided to {@link ObjectCodec#serialize} implementations.
 *
 * <p>Implementations may be stateful or stateless. The {@link ImmutableSerializationContext} is
 * thread safe and it has rather flexible usage.
 *
 * <p>The two stateful contexts, {@link MemoizingSerializationContext} and {@link
 * SharedValueSerializationContext} are tightly coupled to the output bytes. Deserializing memoized
 * streams requires the deserializer to know all the previously serialized values. In practice, it
 * only makes sense to tie the lifetime of a {@link CodedOutputStream} to the lifetime of a {@link
 * MemoizingSerializationContext}.
 */
public abstract class SerializationContext implements LeafSerializationContext {
  private final ObjectCodecRegistry codecRegistry;
  private final ImmutableClassToInstanceMap<Object> dependencies;

  SerializationContext(
      ObjectCodecRegistry codecRegistry, ImmutableClassToInstanceMap<Object> dependencies) {
    this.codecRegistry = codecRegistry;
    this.dependencies = dependencies;
  }

  /** Serializes {@code obj} into {@code codedOut}. */
  public final void serialize(@Nullable Object object, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    if (writeIfNullOrConstant(object, codedOut)) {
      return;
    }
    CodecDescriptor descriptor = codecRegistry.getCodecDescriptorForObject(object);
    @SuppressWarnings("unchecked")
    ObjectCodec<Object> castCodec = (ObjectCodec<Object>) descriptor.codec();
    ProfileRecorder recorder = getProfileRecorder();
    if (recorder == null) {
      serializeImpl(descriptor, castCodec, object, codedOut);
      return;
    }
    int startBytes = codedOut.getTotalBytesWritten();
    recorder.pushLocation(castCodec);
    serializeImpl(descriptor, castCodec, object, codedOut);
    recorder.recordBytesAndPopLocation(startBytes, codedOut);
  }

  private void serializeImpl(
      CodecDescriptor descriptor,
      ObjectCodec<Object> castCodec,
      Object object,
      CodedOutputStream codedOut)
      throws IOException, SerializationException {
    if (writeBackReferenceIfMemoized(object, codedOut, castCodec instanceof LeafObjectCodec)) {
      return;
    }
    codedOut.writeSInt32NoTag(descriptor.tag());
    serializeWithCodec(castCodec, object, codedOut);
  }

  /**
   * Serializes {@code child} with {@code codec} into a key-value store.
   *
   * <p>This globally memoizes {@code child} by <em>reference</em>.
   *
   * <p>NOTE: This is only supported by {@link SharedValueSerializationContext}.
   *
   * @param child <em>non-null</em> object to be serialized
   * @param distinguisher an optional distinguisher see {@link
   *     FingerprintValueCache.FingerprintWithDistinguisher}
   */
  public <T> void putSharedValue(
      T child,
      @Nullable Object distinguisher,
      DeferredObjectCodec<T> codec,
      CodedOutputStream codedOut)
      throws IOException, SerializationException {
    throw new UnsupportedOperationException();
  }

  @Override
  public final <T> T getDependency(Class<T> type) {
    return checkNotNull(dependencies.getInstance(type), "Missing dependency of type %s", type);
  }

  // TODO: b/297857068 - only the NestedSetCodecWithStore and HeaderInfoCodec call the following
  // 3 methods. Delete or hide them when they are no longer needed.

  /**
   * Returns a copy of the context with reset state.
   *
   * <p>This is useful in determining a canonical serialized representation of a subgraph when
   * memoization is enabled. Codecs should typically not need to call this.
   */
  public abstract SerializationContext getFreshContext();

  /**
   * Registers a {@link ListenableFuture} that must complete successfully before the serialized
   * bytes generated using this context can be written remotely.
   *
   * <p>NOTE: This is only supported by {@link SharedValueSerializationContext}.
   */
  public void addFutureToBlockWritingOn(WriteStatus future) {
    throw new UnsupportedOperationException();
  }

  /**
   * Creates a future that succeeds when all futures stored in this context via {@link
   * #addFutureToBlockWritingOn} have succeeded, or null if no such futures were stored.
   *
   * <p>NOTE: This is only supported by {@link SharedValueSerializationContext} and only used by
   * {@link com.google.devtools.build.lib.collect.nestedset.NestedSetStore}.
   */
  @Nullable
  public WriteStatus createFutureToBlockWritingOn() {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds an explicitly allowed class for this serialization context, which must be a memoizing
   * context. Must be called by any codec that transitively serializes an object whose codec calls
   * {@link #checkClassExplicitlyAllowed}.
   *
   * <p>Normally called by codecs for {@link com.google.devtools.build.skyframe.SkyValue} subclasses
   * that know they may encounter an object that is expensive to serialize, like {@link
   * com.google.devtools.build.lib.skyframe.PackageValue} and {@link
   * com.google.devtools.build.lib.packages.Package} or {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTargetValue} and {@link
   * com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget}.
   *
   * <p>In case of an unexpected failure from {@link #checkClassExplicitlyAllowed}, it should first
   * be determined if the inclusion of the expensive object is legitimate, before it is whitelisted
   * using this method.
   */
  public abstract void addExplicitlyAllowedClass(Class<?> allowedClass)
      throws SerializationException;

  /**
   * Asserts during serialization that the encoded class of this codec has been explicitly
   * whitelisted for serialization (using {@link #addExplicitlyAllowedClass}). Codecs for objects
   * that are expensive to serialize and that should only be encountered in a limited number of
   * types of {@link com.google.devtools.build.skyframe.SkyValue}s should call this method to check
   * that the object is being serialized as part of an expected {@link
   * com.google.devtools.build.skyframe.SkyValue}, like {@link
   * com.google.devtools.build.lib.packages.Package} inside {@link
   * com.google.devtools.build.lib.skyframe.PackageValue}.
   */
  public abstract <T> void checkClassExplicitlyAllowed(Class<T> allowedClass, T objectForDebugging)
      throws SerializationException;

  // The following methods are abstract to allow different behaviors depending on whether
  // memoization is enabled.

  /**
   * Serializes {@code obj} using {@code codec} into {@code codedOut}.
   *
   * <p>In contrast to {@link #serialize(Object, CodedOutputStream)}, this does not handle nulls,
   * reference constants or backreferences.
   */
  @ForOverride
  abstract void serializeWithCodec(
      ObjectCodec<Object> codec, Object obj, CodedOutputStream codedOut)
      throws SerializationException, IOException;

  /**
   * Attempts to serialize {@code obj} as a backreference to an already serialized object.
   *
   * <p>Never succeeds if memoization is disabled.
   *
   * @param isLeafType true if the codec used for {@code obj} would be an instance of {@link
   *     LeafObjectCodec}
   * @return true if {@code obj} was serialized to {@code codedOut} as a backreference
   */
  @ForOverride
  abstract boolean writeBackReferenceIfMemoized(
      Object obj, CodedOutputStream codedOut, boolean isLeafType) throws IOException;

  public abstract boolean isMemoizing();

  final boolean writeIfNullOrConstant(@Nullable Object object, CodedOutputStream codedOut)
      throws IOException {
    if (object == null) {
      codedOut.writeSInt32NoTag(0);
      return true;
    }
    Integer tag = codecRegistry.maybeGetTagForConstant(object);
    if (tag != null) {
      codedOut.writeSInt32NoTag(tag);
      return true;
    }
    return false;
  }

  final ObjectCodecRegistry getCodecRegistry() {
    return codecRegistry;
  }

  final ImmutableClassToInstanceMap<Object> getDependencies() {
    return dependencies;
  }

  @Nullable
  abstract ProfileRecorder getProfileRecorder();
}
