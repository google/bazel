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
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.skyframe.SkyKey;

import com.google.errorprone.annotations.ForOverride;
import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Stateful class for providing additional context to a single deserialization "session". This class
 * is thread-safe so long as {@link #deserializer} is null. If it is not null, this class is not
 * thread-safe and should only be accessed on a single thread for deserializing one serialized
 * object (that may contain other serialized objects inside it).
 */
public abstract class DeserializationContext implements AsyncDeserializationContext {
  private final ObjectCodecRegistry registry;
  private final ImmutableClassToInstanceMap<Object> dependencies;

  DeserializationContext(
      ObjectCodecRegistry registry, ImmutableClassToInstanceMap<Object> dependencies) {
    this.registry = registry;
    this.dependencies = dependencies;
  }

  @Nullable
  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  public final <T> T deserialize(CodedInputStream codedIn)
      throws IOException, SerializationException {
    return (T) makeSynchronous(processTagAndDeserialize(codedIn));
  }

  /**
   * Deserializes into {@code parent} using {@code setter}.
   *
   * <p>This allows custom processing of the deserialized object.
   */
  @Override
  public <T> void deserialize(CodedInputStream codedIn, T parent, FieldSetter<? super T> setter)
      throws IOException, SerializationException {
    Object value = deserialize(codedIn);
    if (value == null) {
      return;
    }
    setter.set(parent, value);
  }

  // TODO: b/386384684 - remove Unsafe usage
  @Override
  @SuppressWarnings("SunApi") // TODO: b/331765692 - delete this
  public void deserialize(CodedInputStream codedIn, Object parent, long offset)
      throws IOException, SerializationException {
    unsafe().putObject(parent, offset, deserialize(codedIn));
  }

  @Override
  public void deserialize(CodedInputStream codedIn, Object parent, long offset, Runnable done)
      throws IOException, SerializationException {
    deserialize(codedIn, parent, offset);
    done.run();
  }

  @Override
  public void deserializeArrayElement(CodedInputStream codedIn, Object[] arr, int index)
      throws IOException, SerializationException {
    Object value = deserialize(codedIn);
    if (value == null) {
      return;
    }
    arr[index] = value;
  }

  @Override
  public <T> void getSharedValue(
      CodedInputStream codedIn,
      @Nullable Object distinguisher,
      DeferredObjectCodec<?> codec,
      T parent,
      FieldSetter<? super T> setter)
      throws IOException, SerializationException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> void getSkyValue(SkyKey key, T parent, FieldSetter<? super T> setter)
      throws SerializationException {
    throw new UnsupportedOperationException("Only supported by SharedValueDeserializationContext");
  }

  @Override
  public final <T> T getDependency(Class<T> type) {
    return checkNotNull(dependencies.getInstance(type), "Missing dependency of type %s", type);
  }

  /** Returns a copy of the context with reset state. */
  // TODO: b/297857068 - Only the NestedSetCodecWithStore requires this method. Delete it when it is
  // no longer needed.
  public abstract DeserializationContext getFreshContext();

  final ObjectCodecRegistry getRegistry() {
    return registry;
  }

  final ImmutableClassToInstanceMap<Object> getDependencies() {
    return dependencies;
  }

  /**
   * Deserializes from {@code codedIn} using {@code codec}.
   *
   * <p>This extension point allows the implementation optionally apply memoization logic.
   *
   * <p>Returns either a deserialized value or a {@link ListenableFuture}. A {@link
   * ListenableFuture} is only possible for {@link SharedValueDeserializationContext}.
   */
  @ForOverride
  abstract Object deserializeAndMaybeMemoize(ObjectCodec<?> codec, CodedInputStream codedIn)
      throws SerializationException, IOException;

  /**
   * Reads the tag and uses its value to deserialize the next value.
   *
   * <ul>
   *   <li>null, if the value was null;
   *   <li>a {@link ListenableFuture} that produces the value; or
   *   <li>the value directly.
   * </ul>
   *
   * <p>{@link ListenableFuture} is only possible for {@link SharedValueDeserializationContext}.
   */
  @Nullable
  final Object processTagAndDeserialize(CodedInputStream codedIn)
      throws SerializationException, IOException {
    int tag = codedIn.readSInt32();
    if (tag == 0) {
      return null;
    }
    if (tag < 0) {
      // Subtracts 1 to undo transformation from SerializationContext to avoid null.
      return getMemoizedBackReference(-tag - 1);
    }
    Object constant = registry.maybeGetConstantByTag(tag);
    if (constant != null) {
      return constant;
    }
    // Performs deserialization using the specified codec.
    return deserializeAndMaybeMemoize(registry.getCodecDescriptorByTag(tag).codec(), codedIn);
  }

  @Nullable
  final Object maybeGetConstantByTag(int tag) {
    return registry.maybeGetConstantByTag(tag);
  }

  abstract Object getMemoizedBackReference(int memoIndex);

  /**
   * Returns the result value.
   *
   * <p>In the {@link SharedValueDeserializationContext}, {@link
   * DeserializationContext#deserializeAndMaybeMemoize} may produce futures. This method is
   * overridden to unwrap them.
   */
  @SuppressWarnings("CanIgnoreReturnValueSuggester")
  @ForOverride
  Object makeSynchronous(Object obj) throws SerializationException {
    return obj;
  }
}
