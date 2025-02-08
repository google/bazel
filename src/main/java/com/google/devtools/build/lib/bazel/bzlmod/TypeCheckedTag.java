// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableCollection;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Structure;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/**
 * A {@link Tag} whose attribute values have been type-checked against the attribute schema define
 * in the {@link TagClass}.
 */
@StarlarkBuiltin(name = "bazel_module_tag", documented = false)
public class TypeCheckedTag implements Structure {
  private final TagClass tagClass;
  private final Object[] attrValues;
  private final boolean devDependency;

  // The properties below are only used for error reporting.
  private final Location location;
  private final String tagClassName;

  private TypeCheckedTag(
      TagClass tagClass,
      Object[] attrValues,
      boolean devDependency,
      Location location,
      String tagClassName) {
    this.tagClass = tagClass;
    this.attrValues = attrValues;
    this.devDependency = devDependency;
    this.location = location;
    this.tagClassName = tagClassName;
  }

  /** Creates a {@link TypeCheckedTag}. */
  public static TypeCheckedTag create(
      TagClass tagClass, Tag tag, LabelConverter labelConverter, String moduleDisplayString)
      throws ExternalDepsException {
    Object[] attrValues = new Object[tagClass.attributes().size()];
    for (Map.Entry<String, Object> attrValue : tag.getAttributeValues().attributes().entrySet()) {
      Integer attrIndex = tagClass.attributeIndices().get(attrValue.getKey());
      if (attrIndex == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, unknown attribute %s provided%s",
            tag.getLocation(),
            attrValue.getKey(),
            SpellChecker.didYouMean(attrValue.getKey(), tagClass.attributeIndices().keySet()));
      }
      Attribute attr = tagClass.attributes().get(attrIndex);
      Object nativeValue;
      try {
        nativeValue =
            attr.getType().convert(attrValue.getValue(), attr.getPublicName(), labelConverter);
      } catch (ConversionException e) {
        throw ExternalDepsException.withCauseAndMessage(
            Code.BAD_MODULE,
            e,
            "in tag at %s, error converting value for attribute %s",
            tag.getLocation(),
            attr.getPublicName());
      }

      // Check that the value is actually allowed.
      if (attr.checkAllowedValues() && !attr.getAllowedValues().apply(nativeValue)) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, the value for attribute %s %s",
            tag.getLocation(),
            attr.getPublicName(),
            attr.getAllowedValues().getErrorReason(nativeValue));
      }

      attrValues[attrIndex] = Attribute.valueToStarlark(nativeValue);
    }

    // Check that all mandatory attributes have been specified, and fill in default values.
    // Along the way, verify that labels in the attribute values refer to visible repos only.
    for (int i = 0; i < attrValues.length; i++) {
      Attribute attr = tagClass.attributes().get(i);
      if (attr.isMandatory() && attrValues[i] == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, mandatory attribute %s isn't being specified",
            tag.getLocation(),
            attr.getPublicName());
      }
      if (attrValues[i] == null) {
        attrValues[i] = Attribute.valueToStarlark(attr.getDefaultValueUnchecked());
      }
      try {
        AttributeValues.validateSingleAttr(
            attr.getPublicName(),
            attrValues[i],
            String.format("to the %s", moduleDisplayString),
            String.format("tag '%s'", tag.getTagName()));
      } catch (EvalException e) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE, "in tag at %s: %s", tag.getLocation(), e.getMessage());
      }
    }
    return new TypeCheckedTag(
        tagClass, attrValues, tag.isDevDependency(), tag.getLocation(), tag.getTagName());
  }

  /**
   * Whether the tag was specified on an extension proxy created with <code>dev_dependency=True
   * </code>.
   */
  public boolean isDevDependency() {
    return devDependency;
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Nullable
  @Override
  public Object getValue(String name) throws EvalException {
    Integer attrIndex = tagClass.attributeIndices().get(name);
    if (attrIndex == null) {
      return null;
    }
    return attrValues[attrIndex];
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return tagClass.attributeIndices().keySet();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return "unknown attribute " + field;
  }

  @Override
  public void debugPrint(Printer printer, StarlarkThread thread) {
    printer.append(String.format("'%s' tag at %s", tagClassName, location));
  }
}
