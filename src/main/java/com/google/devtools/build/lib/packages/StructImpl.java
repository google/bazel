// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.Structure;

/**
 * An abstract base class for Starlark values that have fields, have an associated provider (type
 * symbol), and may be returned as the result of analysis from one target to another.
 *
 * <p>StructImpl does not specify how the fields are represented; subclasses must define {@code
 * getValue} and {@code getFieldNames}. For example, {@code NativeInfo} supplies fields from the
 * subclass's {@code StarlarkMethod(structField=true)} annotations, and {@code StarlarkInfo}
 * supplies fields from the map provided at its construction.
 *
 * <p>Two StructImpls are equivalent if they have the same provider and, for each field name
 * reported by {@code getFieldNames} their corresponding field values are equivalent, or accessing
 * them both returns an error.
 */
public abstract class StructImpl implements Info, Structure, StructApi {

  /**
   * Returns the result of {@link #getValue(String)}, cast as the given type, throwing {@link
   * EvalException} if the cast fails.
   */
  @Nullable
  public final <T> T getValue(String key, Class<T> type) throws EvalException {
    Object obj = getValue(key);
    if (obj == null) {
      return null;
    }
    try {
      return type.cast(obj);
    } catch (ClassCastException unused) {
      throw Starlark.errorf(
          "for %s field, got %s, want %s", key, Starlark.type(obj), Starlark.classType(type));
    }
  }

  /**
   * Returns the result of {@link #getValue(String)}, cast as the given type, throwing {@link
   * EvalException} if the cast fails. If the value is {@link Starlark#NONE}, returns null.
   */
  @Nullable
  public final <T> T getNoneableValue(String key, Class<T> type) throws EvalException {
    Object obj = getValue(key);
    if (obj == null || obj == Starlark.NONE) {
      return null;
    }
    try {
      return type.cast(obj);
    } catch (ClassCastException unused) {
      throw Starlark.errorf(
          "for %s field, got %s, want %s", key, Starlark.type(obj), Starlark.classType(type));
    }
  }

  /**
   * Returns the error message format to use for unknown fields.
   *
   * <p>By default, it is the one specified by the provider.
   */
  @Override
  public String getErrorMessageForUnknownField(String name) {
    return getProvider().getErrorMessageForUnknownField(name) + allAttributesSuffix();
  }

  final String allAttributesSuffix() {
    // TODO(adonovan): when is it appropriate for the error to show all attributes,
    // and when to show a single spelling suggestion (the default)?
    return "\nAvailable attributes: "
        + Joiner.on(", ").join(Ordering.natural().sortedCopy(getFieldNames()));
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof StructImpl)) {
      return false;
    }
    StructImpl other = (StructImpl) otherObject;
    if (this == other) {
      return true;
    }
    if (!this.getProvider().equals(other.getProvider())) {
      return false;
    }
    // Compare objects' fields and their values
    if (!this.getFieldNames().equals(other.getFieldNames())) {
      return false;
    }
    for (String field : getFieldNames()) {
      if (!Objects.equal(this.getValueOrNull(field), other.getValueOrNull(field))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    List<String> fields = new ArrayList<>(getFieldNames());
    Collections.sort(fields);
    List<Object> objectsToHash = new ArrayList<>();
    objectsToHash.add(getProvider());
    for (String field : fields) {
      objectsToHash.add(field);
      objectsToHash.add(getValueOrNull(field));
    }
    return Objects.hashCode(objectsToHash.toArray());
  }

  /**
   * Convert the object to string using Starlark syntax. The output tries to be reversible (but
   * there is no guarantee, it depends on the actual values).
   */
  @Override
  public void repr(Printer printer) {
    boolean first = true;
    printer.append("struct(");
    // Sort by key to ensure deterministic output.
    for (String fieldName : Ordering.natural().sortedCopy(getFieldNames())) {
      if (!first) {
        printer.append(", ");
      }
      first = false;
      printer.append(fieldName);
      printer.append(" = ");
      printer.repr(getValueOrNull(fieldName));
    }
    printer.append(")");
  }

  @Nullable
  private Object getValueOrNull(String name) {
    try {
      return getValue(name);
    } catch (EvalException e) {
      return null;
    }
  }

  @Override
  public String toString() {
    return Starlark.repr(this);
  }
}
