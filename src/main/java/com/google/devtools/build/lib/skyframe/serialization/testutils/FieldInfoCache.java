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

import com.google.common.collect.ImmutableList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/** A cache for {@link FieldInfo}. */
final class FieldInfoCache {
  private static final ConcurrentHashMap<Class<?>, ClassInfo> classInfoCache =
      new ConcurrentHashMap<>();

  private static final ClosedClassInfo CLOSED_CLASS_INFO = new ClosedClassInfo();

  /**
   * Returns the {@link FieldInfo} list for the given {@code type}.
   *
   * <p>{@code type} must be in an accessible module or this will error.
   */
  static ImmutableList<FieldInfo> getFieldInfo(Class<?> type) {
    return switch (getClassInfo(type)) {
      case FieldInfoList(ImmutableList<FieldInfo> fieldInfo) -> fieldInfo;
      case ClassInfo unused ->
          throw new IllegalStateException("type in different, unopened module: " + type);
    };
  }

  static ClassInfo getClassInfo(Class<?> type) {
    return classInfoCache.computeIfAbsent(type, FieldInfoCache::getClassInfoUncached);
  }

  sealed interface ClassInfo permits ClosedClassInfo, FieldInfoList {}

  record FieldInfoList(ImmutableList<FieldInfo> fields) implements ClassInfo {}

  /** A class in a different module without add-opens where reflection is blocked. */
  record ClosedClassInfo() implements ClassInfo {}

  sealed interface FieldInfo permits PrimitiveInfo, ObjectInfo {}

  private abstract static class AbstractFieldInfo {
    final String name;
    final VarHandle handle;

    private AbstractFieldInfo(Field field, MethodHandles.Lookup privateLookup) {
      this.name = field.getName();
      try {
        this.handle = privateLookup.unreflectVarHandle(field);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    String name() {
      return name;
    }
  }

  static final class PrimitiveInfo extends AbstractFieldInfo implements FieldInfo {
    private PrimitiveInfo(Field field, MethodHandles.Lookup lookup) {
      super(field, lookup);
    }

    String getText(Object parent) {
      return handle.get(parent).toString();
    }
  }

  static final class ObjectInfo extends AbstractFieldInfo implements FieldInfo {
    private ObjectInfo(Field field, MethodHandles.Lookup privateLookup) {
      super(field, privateLookup);
    }

    Object getFieldValue(Object parent) {
      return handle.get(parent);
    }
  }

  private static ClassInfo getClassInfoUncached(Class<?> type) {
    MethodHandles.Lookup baseLookup = MethodHandles.lookup();

    var fieldInfo = ImmutableList.<FieldInfo>builder();
    for (Class<?> next = type; next != null; next = next.getSuperclass()) {
      MethodHandles.Lookup privateLookup;
      try {
        privateLookup = MethodHandles.privateLookupIn(next, baseLookup);
      } catch (ReflectiveOperationException e) {
        // This can happen if the class is in a different module without add-opens.
        return CLOSED_CLASS_INFO;
      }
      Field[] declaredFields = next.getDeclaredFields();
      var classFields = new ArrayList<Field>(declaredFields.length);
      for (Field field : next.getDeclaredFields()) {
        if ((field.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) != 0) {
          continue; // Skips any static or transient fields.
        }
        classFields.add(field);
      }
      classFields.stream()
          // Sorts by name for determinism. Shadowed fields always have separate entries because
          // they occur at different levels in the inheritance hierarchy.
          //
          // Reverses the order here, then reverses it again below. This makes superclass fields
          // appear before subclass fields.
          .sorted(Comparator.comparing(Field::getName).reversed())
          .map(
              field -> {
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive()) {
                  return new PrimitiveInfo(field, privateLookup);
                }
                return new ObjectInfo(field, privateLookup);
              })
          .forEach(fieldInfo::add);
    }
    return new FieldInfoList(fieldInfo.build().reverse());
  }

  private FieldInfoCache() {}
}
