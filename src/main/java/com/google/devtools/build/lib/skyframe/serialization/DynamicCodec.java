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

import static com.google.devtools.build.lib.skyframe.serialization.CodecHelpers.readChar;
import static com.google.devtools.build.lib.skyframe.serialization.CodecHelpers.readShort;
import static com.google.devtools.build.lib.skyframe.serialization.CodecHelpers.writeChar;
import static com.google.devtools.build.lib.skyframe.serialization.CodecHelpers.writeShort;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A codec that serializes arbitrary types. */
@SuppressWarnings("SunApi") // TODO: b/331765692 - clean this up
public final class DynamicCodec extends AsyncObjectCodec<Object> {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Class<?> type;
  private final FieldHandler[] handlers;

  public DynamicCodec(Class<?> type) {
    this(type, getFieldHandlers(type));
  }

  @SuppressWarnings("AvoidObjectArrays") // less overhead
  private DynamicCodec(Class<?> type, FieldHandler[] handlers) {
    this.type = type;
    this.handlers = handlers;
  }

  /** Creates a codec instance with custom handlers for specified fields. */
  public static DynamicCodec createWithOverrides(
      Class<?> type, Map<Field, FieldHandler> overrides) {
    LinkedHashMap<Field, FieldHandler> handlers = getFieldHandlerMap(type);
    for (Map.Entry<Field, FieldHandler> override : overrides.entrySet()) {
      FieldHandler previous = handlers.put(override.getKey(), override.getValue());
      if (previous == null) {
        throw new IllegalArgumentException(
            String.format(
                "An override was specified for %s but no such field was present in the default"
                    + " dynamic codec for %s.",
                override.getKey(), type));
      }
    }
    return new DynamicCodec(type, handlers.values().toArray(FieldHandler[]::new));
  }

  @Override
  public Class<?> getEncodedClass() {
    return type;
  }

  @Override
  @SuppressWarnings("LogAndThrow") // Want the full stack trace.
  public void serialize(SerializationContext context, Object obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    for (FieldHandler handler : handlers) {
      try {
        handler.serialize(context, codedOut, obj);
      } catch (SerializationException e) {
        logger.atSevere().withCause(e).log(
            "Unserializable object and superclass: %s %s", obj, obj.getClass().getSuperclass());
        e.addTrail(type);
        throw e;
      }
    }
  }

  @Override
  @SuppressWarnings("LogAndThrow") // Want the full stack trace.
  public Object deserializeAsync(AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    Object instance;
    try {
      instance = unsafe().allocateInstance(type);
    } catch (ReflectiveOperationException e) {
      throw new SerializationException("Could not instantiate object of type: " + type, e);
    }
    context.registerInitialValue(instance);

    for (FieldHandler handler : handlers) {
      try {
        handler.deserialize(context, codedIn, instance);
      } catch (SerializationException e) {
        logger.atSevere().withCause(e).log(
            "Failed to deserialize object with superclass: %s %s",
            instance, instance.getClass().getSuperclass());
        e.addTrail(type);
        throw e;
      }
    }
    return instance;
  }

  /** Handles serialization of a field. */
  public interface FieldHandler {
    void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws SerializationException, IOException;

    void deserialize(AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws SerializationException, IOException;
  }

  /**
   * Computes the default {@link FieldHandler}s that would be used for the given type.
   *
   * <p>The entries are ordered by {@link FieldComparator} for determinism. The returned value is a
   * fresh copy that the caller may freely modify.
   */
  @SuppressWarnings("NonApiType") // type communicates fixed ordering
  public static <T> LinkedHashMap<Field, FieldHandler> getFieldHandlerMap(Class<T> type) {
    LinkedHashMap<Field, FieldHandler> handlers = new LinkedHashMap<>();
    for (Field field : getSerializableFields(type)) {
      handlers.put(field, getHandlerForField(field));
    }
    return handlers;
  }

  private static final class BooleanHandler implements FieldHandler {
    private final long offset;

    private BooleanHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeBoolNoTag(unsafe().getBoolean(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putBoolean(obj, offset, codedIn.readBool());
    }
  }

  private static final class ByteHandler implements FieldHandler {
    private final long offset;

    private ByteHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeRawByte(unsafe().getByte(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putByte(obj, offset, codedIn.readRawByte());
    }
  }

  private static final class ShortHandler implements FieldHandler {
    private final long offset;

    private ShortHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      writeShort(codedOut, unsafe().getShort(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putShort(obj, offset, readShort(codedIn));
    }
  }

  private static final class CharHandler implements FieldHandler {
    private final long offset;

    private CharHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      writeChar(codedOut, unsafe().getChar(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putChar(obj, offset, readChar(codedIn));
    }
  }

  private static final class IntHandler implements FieldHandler {
    private final long offset;

    private IntHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeInt32NoTag(unsafe().getInt(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putInt(obj, offset, codedIn.readInt32());
    }
  }

  private static final class LongHandler implements FieldHandler {
    private final long offset;

    private LongHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeInt64NoTag(unsafe().getLong(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putLong(obj, offset, codedIn.readInt64());
    }
  }

  private static final class FloatHandler implements FieldHandler {
    private final long offset;

    private FloatHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeFloatNoTag(unsafe().getFloat(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putFloat(obj, offset, codedIn.readFloat());
    }
  }

  private static final class DoubleHandler implements FieldHandler {
    private final long offset;

    private DoubleHandler(long offset) {
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException {
      codedOut.writeDoubleNoTag(unsafe().getDouble(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException {
      unsafe().putDouble(obj, offset, codedIn.readDouble());
    }
  }

  private static final class ObjectHandler implements FieldHandler, FieldSetter<Object> {
    private final Class<?> type;
    private final long offset;

    private ObjectHandler(Class<?> type, long offset) {
      this.type = type;
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException, SerializationException {
      context.serialize(unsafe().getObject(obj, offset), codedOut);
    }

    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException, SerializationException {
      context.deserialize(codedIn, obj, (FieldSetter<Object>) this);
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void set(Object target, Object fieldValue) throws SerializationException {
      if (!type.isInstance(fieldValue)) {
        throw new SerializationException(
            "Field "
                + fieldValue
                + " was not instance of "
                + type
                + " (was "
                + fieldValue.getClass()
                + ")");
      }
      unsafe().putObject(target, offset, fieldValue);
    }
  }

  private static final class ArrayHandler implements FieldHandler {
    private final ArrayProcessor arrayProcessor;
    private final Class<?> type;
    private final long offset;

    private ArrayHandler(Class<?> type, long offset) {
      this.arrayProcessor = ArrayProcessor.forType(type);
      this.type = type;
      this.offset = offset;
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void serialize(SerializationContext context, CodedOutputStream codedOut, Object obj)
        throws IOException, SerializationException {
      arrayProcessor.serialize(context, codedOut, type, unsafe().getObject(obj, offset));
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Override
    public void deserialize(
        AsyncDeserializationContext context, CodedInputStream codedIn, Object obj)
        throws IOException, SerializationException {
      unsafe().putObject(obj, offset, arrayProcessor.deserialize(context, codedIn, type));
    }
  }

  private static <T> FieldHandler[] getFieldHandlers(Class<T> type) {
    List<Field> fields = getSerializableFields(type);

    FieldHandler[] handlers = new FieldHandler[fields.size()];
    int i = 0;
    for (Field field : fields) {
      handlers[i++] = getHandlerForField(field);
    }
    return handlers;
  }

  private static <T> List<Field> getSerializableFields(Class<T> type) {
    ArrayList<Field> fields = new ArrayList<>();
    for (Class<? super T> next = type; next != null; next = next.getSuperclass()) {
      for (Field field : next.getDeclaredFields()) {
        if ((field.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) != 0) {
          continue; // Skips static or transient fields.
        }
        fields.add(field);
      }
    }
    // NB: it's tempting to try to simplify this by ordering by offset, but it looks like offsets
    // are not guaranteed to be stable, which is needed for deterministic serialization.
    Collections.sort(fields, new FieldComparator());
    return fields;
  }

  // TODO: b/386384684 - remove Unsafe usage
  private static FieldHandler getHandlerForField(Field field) {
    long offset = unsafe().objectFieldOffset(field);
    Class<?> fieldType = field.getType();
    if (fieldType.isPrimitive()) {
      if (fieldType.equals(boolean.class)) {
        return new BooleanHandler(offset);
      } else if (fieldType.equals(byte.class)) {
        return new ByteHandler(offset);
      } else if (fieldType.equals(short.class)) {
        return new ShortHandler(offset);
      } else if (fieldType.equals(char.class)) {
        return new CharHandler(offset);
      } else if (fieldType.equals(int.class)) {
        return new IntHandler(offset);
      } else if (fieldType.equals(long.class)) {
        return new LongHandler(offset);
      } else if (fieldType.equals(float.class)) {
        return new FloatHandler(offset);
      } else if (fieldType.equals(double.class)) {
        return new DoubleHandler(offset);
      } else {
        throw new UnsupportedOperationException(
            "Unexpected primitive field type " + fieldType + " for " + field.getDeclaringClass());
      }
    } else if (fieldType.isArray()) {
      return new ArrayHandler(fieldType, offset);
    }
    return new ObjectHandler(fieldType, offset);
  }

  private static final class FieldComparator implements Comparator<Field> {
    @Override
    public int compare(Field f1, Field f2) {
      int classCompare =
          f1.getDeclaringClass().getName().compareTo(f2.getDeclaringClass().getName());
      if (classCompare != 0) {
        return classCompare;
      }
      return f1.getName().compareTo(f2.getName());
    }
  }
}
