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

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SerializationContext}. */
@RunWith(TestParameterInjector.class)
public final class SerializationContextTest {

  private static final Object CONSTANT = new Object();

  record Example(String dataToSerialize) {
    Example {
      requireNonNull(dataToSerialize, "dataToSerialize");
    }

    static Example withData(String data) {
      return new Example(data);
    }
  }

  private final class ExampleCodec implements ObjectCodec<Example> {

    @Override
    public Class<Example> getEncodedClass() {
      return Example.class;
    }

    @Override
    public void serialize(SerializationContext context, Example obj, CodedOutputStream codedOut)
        throws IOException {
      exampleCodecSerializeCalls++;
      codedOut.writeStringNoTag(obj.dataToSerialize());
    }

    @Override
    public Example deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws IOException {
      exampleCodecDeserializeCalls++;
      return Example.withData(codedIn.readString());
    }
  }

  private final ObjectCodecRegistry registry =
      ObjectCodecRegistry.newBuilder()
          .addReferenceConstant(CONSTANT)
          .add(new ExampleCodec())
          .build();

  private int exampleCodecSerializeCalls = 0;

  @SuppressWarnings("UnusedVariable")
  private int exampleCodecDeserializeCalls = 0;

  @Test
  public void nullSerialize(@TestParameter boolean memoize) throws Exception {
    SerializationContext context = getSerializationContext(memoize);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytes);

    context.serialize(null, codedOut);
    codedOut.flush();

    CodedInputStream codedIn = CodedInputStream.newInstance(bytes.toByteArray());
    assertThat(codedIn.readSInt32()).isEqualTo(0);
    assertThat(codedIn.isAtEnd()).isTrue();
  }

  @Test
  public void constantSerialize(@TestParameter boolean memoize) throws Exception {
    SerializationContext context = getSerializationContext(memoize);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytes);

    context.serialize(CONSTANT, codedOut);
    codedOut.flush();

    CodedInputStream codedIn = CodedInputStream.newInstance(bytes.toByteArray());
    assertThat(codedIn.readSInt32()).isEqualTo(registry.maybeGetTagForConstant(CONSTANT));
    assertThat(codedIn.isAtEnd()).isTrue();
  }

  @Test
  public void descriptorSerialize() throws SerializationException, IOException {
    Example obj = Example.withData("data");
    SerializationContext context = getSerializationContext(/* memoizing= */ false);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytes);

    context.serialize(obj, codedOut);
    codedOut.flush();

    CodedInputStream codedIn = CodedInputStream.newInstance(bytes.toByteArray());
    assertThat(codedIn.readSInt32()).isEqualTo(registry.getCodecDescriptorForObject(obj).tag());
    assertThat(codedIn.readString()).isEqualTo(obj.dataToSerialize());
    assertThat(codedIn.isAtEnd()).isTrue();
  }

  @Test
  public void descriptorSerialize_memoizing() throws SerializationException, IOException {
    Example obj = Example.withData("data");
    SerializationContext context = getSerializationContext(/* memoizing= */ true);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytes);

    context.serialize(obj, codedOut);
    context.serialize(obj, codedOut);
    codedOut.flush();

    CodedInputStream codedIn = CodedInputStream.newInstance(bytes.toByteArray());
    assertThat(codedIn.readSInt32()).isEqualTo(registry.getCodecDescriptorForObject(obj).tag());
    assertThat(codedIn.readString()).isEqualTo(obj.dataToSerialize());
    assertThat(codedIn.isAtEnd()).isFalse();
    assertThat(exampleCodecSerializeCalls).isEqualTo(1);
  }

  @Test
  public void explicitlyAllowedClassCheck() throws SerializationException {
    SerializationContext context = getSerializationContext(/* memoizing= */ true);
    context.addExplicitlyAllowedClass(String.class);
    context.checkClassExplicitlyAllowed(String.class, "str");
    assertThrows(
        SerializationException.class, () -> context.checkClassExplicitlyAllowed(Integer.class, 0));
    // Explicitly registered classes do not carry over to a new context.
    assertThrows(
        SerializationException.class,
        () -> context.getFreshContext().checkClassExplicitlyAllowed(String.class, "str"));
  }

  @Test
  public void explicitlyAllowedClassCheckFailsIfNotMemoizing() {
    SerializationContext context = getSerializationContext(/* memoizing= */ false);
    assertThrows(
        SerializationException.class, () -> context.addExplicitlyAllowedClass(String.class));
  }

  @Test
  public void mismatchMemoizingRoundtrip() throws Exception {
    ObjectCodecRegistry registry =
        ObjectCodecRegistry.newBuilder().add(new ArrayListCodec()).build();
    ArrayList<Object> repeatedObject = new ArrayList<>();
    repeatedObject.add(null);
    repeatedObject.add(null);
    ArrayList<Object> container = new ArrayList<>();
    container.add(repeatedObject);
    ArrayList<Object> toSerialize = new ArrayList<>();
    toSerialize.add(repeatedObject);
    toSerialize.add(container);

    ObjectCodecs codecs = new ObjectCodecs(registry);
    ByteString bytes = codecs.serialize(toSerialize);
    assertThrows(RuntimeException.class, () -> codecs.deserializeMemoized(bytes));
  }

  private static final class ArrayListCodec implements ObjectCodec<ArrayList<?>> {
    @SuppressWarnings("unchecked")
    @Override
    public Class<ArrayList<?>> getEncodedClass() {
      return (Class<ArrayList<?>>) (Class<?>) ArrayList.class;
    }

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public void serialize(
        SerializationContext context, ArrayList<?> obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      codedOut.writeInt32NoTag(obj.size());
      for (Object item : obj) {
        context.serialize(item, codedOut);
      }
    }

    @Override
    public ArrayList<?> deserialize(DeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      int size = codedIn.readInt32();
      ArrayList<?> result = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        result.add(context.deserialize(codedIn));
      }
      return result;
    }
  }

  @Test
  public void getDependency() {
    SerializationContext context =
        new ObjectCodecs(registry, ImmutableClassToInstanceMap.of(String.class, "abc"))
            .getSerializationContextForTesting();
    assertThat(context.getDependency(String.class)).isEqualTo("abc");
  }

  @Test
  public void getDependency_notPresent() {
    SerializationContext context = getSerializationContext(/* memoizing= */ false);
    Exception e =
        assertThrows(NullPointerException.class, () -> context.getDependency(String.class));
    assertThat(e).hasMessageThat().contains("Missing dependency of type " + String.class);
  }

  @Test
  public void dependencyOverrides_alreadyPresent() {
    ObjectCodecs codecs =
        new ObjectCodecs(registry, ImmutableClassToInstanceMap.of(String.class, "abc"));
    ObjectCodecs overridden =
        codecs.withDependencyOverridesForTesting(
            ImmutableClassToInstanceMap.of(String.class, "xyz"));
    assertThat(overridden.getSerializationContextForTesting().getDependency(String.class))
        .isEqualTo("xyz");
  }

  @Test
  public void dependencyOverrides_new() {
    ObjectCodecs codecs =
        new ObjectCodecs(registry, ImmutableClassToInstanceMap.of(String.class, "abc"));
    ObjectCodecs overridden =
        codecs.withDependencyOverridesForTesting(ImmutableClassToInstanceMap.of(Integer.class, 1));
    assertThat(overridden.getSerializationContextForTesting().getDependency(Integer.class))
        .isEqualTo(1);
  }

  @Test
  public void dependencyOverrides_unchanged() {
    ObjectCodecs codecs =
        new ObjectCodecs(registry, ImmutableClassToInstanceMap.of(String.class, "abc"));
    ObjectCodecs overridden =
        codecs.withDependencyOverridesForTesting(ImmutableClassToInstanceMap.of(Integer.class, 1));
    assertThat(overridden.getSerializationContextForTesting().getDependency(String.class))
        .isEqualTo("abc");
  }

  private SerializationContext getSerializationContext(boolean memoizing) {
    ObjectCodecs codecs = new ObjectCodecs(registry);
    return (memoizing
        ? codecs.getMemoizingSerializationContextForTesting()
        : codecs.getSerializationContextForTesting());
  }
}
