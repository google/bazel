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

import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.packages.License.LicenseParsingException;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.packages.Type.DictType;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.packages.Type.ListType;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

/**
 * Collection of data types that are specific to building things, i.e. not inherent to Starlark.
 *
 * <p>BEFORE YOU ADD A NEW TYPE: See javadoc in {@link Type}.
 */
public final class BuildType {

  /**
   * The type of a label. Labels are not actually a first-class datatype in the build language, but
   * they are so frequently used in the definitions of attributes that it's worth treating them
   * specially (and providing support for resolution of relative-labels in the <code>convert()
   * </code> method).
   */
  @SerializationConstant
  public static final Type<Label> LABEL = new LabelType(LabelClass.DEPENDENCY);
  /** The type of a dictionary of {@linkplain #LABEL labels}. */
  @SerializationConstant
  public static final DictType<String, Label> LABEL_DICT_UNARY =
      DictType.create(Type.STRING, LABEL);
  /** The type of a dictionary keyed by {@linkplain #LABEL labels} with string values. */
  @SerializationConstant
  public static final DictType<Label, String> LABEL_KEYED_STRING_DICT =
      LabelKeyedDictType.create(Type.STRING);
  /** The type of a list of {@linkplain #LABEL labels}. */
  @SerializationConstant public static final ListType<Label> LABEL_LIST = ListType.create(LABEL);

  /** The type of a dictionary of {@linkplain #LABEL_LIST label lists}. */
  @SerializationConstant
  public static final DictType<String, List<Label>> LABEL_LIST_DICT =
      DictType.create(Type.STRING, LABEL_LIST);

  /**
   * This is a label type that does not cause dependencies. It is needed because certain rules want
   * to verify the type of a target referenced by one of their attributes, but if there was a
   * dependency edge there, it would be a circular dependency.
   */
  @SerializationConstant
  public static final Type<Label> NODEP_LABEL = new LabelType(LabelClass.NONDEP_REFERENCE);
  /** The type of a list of {@linkplain #NODEP_LABEL labels} that do not cause dependencies. */
  @SerializationConstant
  public static final ListType<Label> NODEP_LABEL_LIST = ListType.create(NODEP_LABEL);

  @SerializationConstant
  public static final Type<Label> DORMANT_LABEL =
      new LabelType(LabelClass.GENQUERY_SCOPE_REFERENCE);

  @SerializationConstant
  public static final ListType<Label> DORMANT_LABEL_LIST = ListType.create(DORMANT_LABEL);

  /**
   * This is a label type that causes dependencies, but the dependencies are NOT to be configured.
   * Does not say anything about whether the attribute of this type is itself configurable.
   *
   * <p>Without a special type to handle genquery.scope, configuring a genquery target ends up
   * configuring the transitive closure of genquery.scope. Since genquery rule implementation loads
   * the deps through TransitiveTargetFunction, it doesn't need them to be configured. Preventing
   * the dependencies of scope from being configured, lets us save some resources.
   */
  @SerializationConstant
  public static final Type<Label> GENQUERY_SCOPE_TYPE =
      new LabelType(LabelClass.GENQUERY_SCOPE_REFERENCE);

  /**
   * This is a label type that causes dependencies, but the dependencies are NOT to be configured.
   * Does not say anything about whether the attribute of this type is itself configurable.
   */
  @SerializationConstant
  public static final ListType<Label> GENQUERY_SCOPE_TYPE_LIST =
      ListType.create(GENQUERY_SCOPE_TYPE);

  /**
   * The type of a license. Like Label, licenses aren't first-class, but they're important enough to
   * justify early syntax error detection.
   */
  @SerializationConstant public static final Type<License> LICENSE = new LicenseType();
  /** The type of a single distribution. Only used internally, as a type symbol, not a converter. */
  @SerializationConstant
  static final Type<DistributionType> DISTRIBUTION =
      new Type<DistributionType>() {
        @Override
        public DistributionType cast(Object value) {
          return (DistributionType) value;
        }

        @Override
        public DistributionType convert(Object x, Object what, LabelConverter labelConverter) {
          throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public DistributionType getDefaultValue() {
          return null;
        }

        @Override
        public void visitLabels(
            LabelVisitor visitor, DistributionType value, @Nullable Attribute context) {}

        @Override
        public String toString() {
          return "distribution";
        }
      };
  /**
   * The type of a set of distributions. Distributions are not a first-class type, but they do
   * warrant early syntax checking.
   */
  @SerializationConstant
  public static final Type<Set<DistributionType>> DISTRIBUTIONS = new Distributions();
  /** The type of an output file, treated as a {@link #LABEL}. */
  @SerializationConstant public static final Type<Label> OUTPUT = new OutputType();

  private static final ImmutableMap<Type<?>, String> whyNotConfigurable =
      ImmutableMap.<Type<?>, String>builder()
          .put(LICENSE, "loading phase license checking logic assumes non-configurable values")
          .put(OUTPUT, "output paths are part of the static graph structure")
          .buildOrThrow();

  /** The type of a list of {@linkplain #OUTPUT outputs}. */
  @SerializationConstant public static final ListType<Label> OUTPUT_LIST = ListType.create(OUTPUT);

  /** The type of a TriState with values: true (x>0), false (x==0), auto (x<0). */
  @SerializationConstant public static final Type<TriState> TRISTATE = new TriStateType();

  private BuildType() {
    // Do not instantiate
  }

  /** Returns whether the specified type is a label type or not. */
  public static boolean isLabelType(Type<?> type) {
    return type.getLabelClass() != LabelClass.NONE;
  }

  /**
   * Variation of {@link Type#convert} that supports selector expressions for configurable
   * attributes (i.e. "{ config1: 'value1_of_orig_type', config2: 'value2_of_orig_type; }"). If x is
   * a selector expression, returns a {@link SelectorList} instance that contains key-mapped entries
   * of the native type. Else, returns the native type directly.
   *
   * <p>If {@code simplifyUnconditionalSelects} is true, then an unconditional select is simplified
   * to the select's value converted to a native value; and a concatenation of unconditional selects
   * (and direct values, if any) is simplified to a concatenation of the select's values and the
   * direct values converted to native values. In other words, {@code ["//x"] +
   * select("//conditions:default": ["//y"])} becomes {@code [Label("//x"), Label("//y")]}. If a
   * concatenation contains a non-unconditional select, the concatenation is not simplified.
   *
   * <p>Returns null iff {@code simplifyUnconditionalSelects} is true, {@code x} is {@code
   * select({"//conditions:default": None})}, and the {@code type.getDefaultValue()} is null.
   *
   * <p>The caller is responsible for casting the returned value appropriately.
   */
  @Nullable
  static <T> Object selectableConvert(
      Type<T> type,
      Object x,
      Object what,
      LabelConverter context,
      boolean simplifyUnconditionalSelects)
      throws ConversionException {
    if (x instanceof com.google.devtools.build.lib.packages.SelectorList selectorList) {
      List<Object> selectorListElements = selectorList.getElements();
      if (!simplifyUnconditionalSelects) {
        return new SelectorList<T>(selectorListElements, what, context, type);
      }
      if (selectorListElements.size() > 1 && type.concat(ImmutableList.of()) == null) {
        throw new ConversionException(
            String.format("type '%s' doesn't support select concatenation", type));
      }
      // Note: ArrayList, not ImmutableList, because we may insert a null into it; the default value
      // of an unconditional Selector<T> is null if the SelectorValue value is None and the native
      // type's default value is null.
      ArrayList<T> values = new ArrayList<>(selectorListElements.size());
      for (Object element : selectorListElements) {
        if (element instanceof SelectorValue selectorValue) {
          ImmutableMap<?, ?> dictionary = selectorValue.getDictionary();
          if (dictionary.size() != 1) {
            // Cannot simplify: selectorValue has multiple branches.
            return new SelectorList<T>(selectorListElements, what, context, type);
          }
          Selector<T> selector =
              new Selector<>(dictionary, what, context, type, selectorValue.getNoMatchError());
          if (!selector.isUnconditional()) {
            // Cannot simplify: the only branch is not the default condition.
            return new SelectorList<T>(selectorListElements, what, context, type);
          }
          values.add(selector.getDefault());
        } else {
          values.add(type.convert(element, what, context));
        }
      }
      if (values.size() == 1) {
        return values.getFirst();
      } else {
        return type.concat(values);
      }
    } else {
      return type.convert(x, what, context);
    }
  }

  /**
   * Converts the build-language-typed {@code buildLangValue} to a native value via {@link
   * BuildType#selectableConvert}. Canonicalizes the value's order if it is a {@link List} type and
   * {@code attr.isOrderIndependent()} returns {@code true}.
   *
   * <p>Returns null iff {@code simplifyUnconditionalSelects} is true, {@code buildLangValue} is
   * {@code select({"//conditions:default": None})}, and {@code attr.getType().getDefaultValue()} is
   * null.
   *
   * <p>Throws {@link ConversionException} if the conversion fails, or if {@code buildLangValue} is
   * a selector expression but {@code attr.isConfigurable()} is {@code false}.
   */
  @Nullable
  public static Object convertFromBuildLangType(
      String ruleClass,
      Attribute attr,
      Object buildLangValue,
      LabelConverter labelConverter,
      Interner<ImmutableList<?>> listInterner,
      boolean simplifyUnconditionalSelects)
      throws ConversionException {
    if ((buildLangValue instanceof com.google.devtools.build.lib.packages.SelectorList)
        && !attr.isConfigurable()) {
      throw new ConversionException(
          String.format("attribute \"%s\" is not configurable", attr.getName()));
    }

    Object converted =
        BuildType.selectableConvert(
            attr.getType(),
            buildLangValue,
            new AttributeConversionContext(attr.getName(), ruleClass),
            labelConverter,
            simplifyUnconditionalSelects);

    if (converted instanceof List<?>) {
      if (attr.isOrderIndependent()) {
        @SuppressWarnings("unchecked")
        List<? extends Comparable<?>> list = (List<? extends Comparable<?>>) converted;
        converted = Ordering.natural().sortedCopy(list);
      }
      // It's common for multiple rule instances in the same package to have the same value for some
      // attributes. As a concrete example, consider a package having several 'java_test' instances,
      // each with the same exact 'tags' attribute value.
      converted = listInterner.intern(ImmutableList.copyOf((List<?>) converted));
    }

    return converted;
  }

  /** Copies a Starlark SelectorList converting label strings to Label objects. */
  private static <T> Object copyAndLiftSelectorList(
      Type<T> type,
      com.google.devtools.build.lib.packages.SelectorList x,
      Object what,
      LabelConverter context)
      throws ConversionException {
    List<Object> elements = x.getElements();
    try {
      if (elements.size() > 1 && type.concat(ImmutableList.of()) == null) {
        throw new ConversionException(
            String.format("type '%s' doesn't support select concatenation", type));
      }

      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object elem : elements) {
        ImmutableMap.Builder<Label, Object> newMap = ImmutableMap.builder();
        if (elem instanceof SelectorValue) {
          for (var entry : ((SelectorValue) elem).getDictionary().entrySet()) {
            Label key = LABEL.convert(entry.getKey(), what, context);
            newMap.put(
                key,
                entry.getValue() == Starlark.NONE
                    ? Starlark.NONE
                    : type.copyAndLiftStarlarkValue(
                        entry.getValue(), new SelectBranchMessage(what, key), context));
          }
          builder.add(
              new SelectorValue(
                  newMap.buildKeepingLast(), ((SelectorValue) elem).getNoMatchError()));
        } else {
          Object directValue = type.copyAndLiftStarlarkValue(elem, what, context);
          builder.add(directValue);
        }
      }
      return com.google.devtools.build.lib.packages.SelectorList.of(builder.build());
    } catch (EvalException e) {
      throw new ConversionException(e.getMessage());
    }
  }

  /**
   * Copies a Starlark value to immutable ones and converts label strings to Label objects.
   *
   * <p>{@code attrOwner} is the name of the rule or macro on which the attribute is defined, e.g.
   * "cc_library".
   *
   * <p>All Starlark values are also type checked.
   *
   * <p>In comparison to {@link #convertFromBuildLangType} unordered attributes are not
   * canonicalized or interned.
   *
   * <p>Use the function before passing the values to initializers.
   *
   * @throws ConversionException if the {@code starlarkValue} doesn't match the type of attr or if
   *     {@code starlarkValue} is a selector expression but {@code attr.isConfigurable()} is {@code
   *     false}.
   */
  public static Object copyAndLiftStarlarkValue(
      String attrOwner, Attribute attr, Object starlarkValue, LabelConverter labelConverter)
      throws ConversionException {
    if (starlarkValue instanceof com.google.devtools.build.lib.packages.SelectorList) {
      if (!attr.isConfigurable()) {
        throw new ConversionException(
            String.format("attribute \"%s\" is not configurable", attr.getName()));
      }
      return copyAndLiftSelectorList(
          attr.getType(),
          (com.google.devtools.build.lib.packages.SelectorList) starlarkValue,
          new AttributeConversionContext(attr.getName(), attrOwner),
          labelConverter);
    } else {
      return attr.getType()
          .copyAndLiftStarlarkValue(
              starlarkValue,
              new AttributeConversionContext(attr.getName(), attrOwner),
              labelConverter);
    }
  }

  /**
   * If the given attribute type is non-configurable, returns the reason why. Otherwise, returns
   * {@code null}.
   */
  @Nullable
  public static String maybeGetNonConfigurableReason(Type<?> type) {
    return whyNotConfigurable.get(type);
  }

  /**
   * A pair of an attribute name and owner, with a toString that includes both.
   *
   * <p>This is used to defer stringifying this information until needed for an error message, so as
   * to avoid generating unnecessary garbage.
   */
  private static class AttributeConversionContext {
    private final String attrName;
    private final String attrOwner;

    /**
     * Constructs a new context object from a pair of strings.
     *
     * @param attrName an attribute name, such as "deps"
     * @param attrOwner a rule or macro on which the attribute is defined, e.g. "cc_library"
     */
    AttributeConversionContext(String attrName, String attrOwner) {
      this.attrName = attrName;
      this.attrOwner = attrOwner;
    }

    @Override
    public String toString() {
      return String.format("attribute '%s' of '%s'", attrName, attrOwner);
    }
  }

  private static class LabelType extends Type<Label> {
    private final LabelClass labelClass;

    LabelType(LabelClass labelClass) {
      this.labelClass = labelClass;
    }

    @Override
    public Label cast(Object value) {
      return (Label) value;
    }

    @Override
    public Label getDefaultValue() {
      return null; // Labels have no default value
    }

    @Override
    public void visitLabels(LabelVisitor visitor, Label value, @Nullable Attribute context) {
      visitor.visit(value, context);
    }

    @Override
    public String toString() {
      return "label";
    }

    @Override
    public LabelClass getLabelClass() {
      return labelClass;
    }

    @Override
    public Label convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      if (x instanceof Label) {
        return (Label) x;
      }
      if (!(x instanceof String)) {
        throw new ConversionException(Type.STRING, x, what);
      }
      try {
        if (labelConverter == null) {
          return Label.parseCanonical((String) x);
        }
        return labelConverter.convert((String) x);
      } catch (LabelSyntaxException e) {
        throw new ConversionException(
            "invalid label '" + x + "' in " + what + ": " + e.getMessage());
      }
    }
  }

  /**
   * Dictionary type specialized for label keys, which is able to detect collisions caused by the
   * fact that labels have multiple equivalent representations in Starlark code.
   */
  private static class LabelKeyedDictType<ValueT> extends DictType<Label, ValueT> {
    private LabelKeyedDictType(Type<ValueT> valueType) {
      super(LABEL, valueType, LabelClass.DEPENDENCY);
    }

    static <ValueT> LabelKeyedDictType<ValueT> create(Type<ValueT> valueType) {
      Preconditions.checkArgument(
          valueType.getLabelClass() == LabelClass.NONE
              || valueType.getLabelClass() == LabelClass.DEPENDENCY,
          "Values associated with label keys must not be labels themselves.");
      return new LabelKeyedDictType<>(valueType);
    }

    @Override
    public Map<Label, ValueT> convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      Map<Label, ValueT> result = super.convert(x, what, labelConverter);
      // The input is known to be a map because super.convert succeeded; otherwise, a
      // ConversionException would have been thrown.
      Map<?, ?> input = (Map<?, ?>) x;

      if (input.size() == result.size()) {
        // No collisions found. Exit early.
        return result;
      }
      // Look for collisions in order to produce a nicer error message.
      Map<Label, List<Object>> convertedFrom = new LinkedHashMap<>();
      for (Object original : input.keySet()) {
        Label label = LABEL.convert(original, what, labelConverter);
        convertedFrom.computeIfAbsent(label, k -> new ArrayList<>()).add(original);
      }
      Printer errorMessage = new Printer();
      errorMessage.append("duplicate labels");
      if (what != null) {
        errorMessage.append(" in ").append(what.toString());
      }
      errorMessage.append(':');
      boolean isFirstEntry = true;
      for (Map.Entry<Label, List<Object>> entry : convertedFrom.entrySet()) {
        if (entry.getValue().size() == 1) {
          continue;
        }
        if (isFirstEntry) {
          isFirstEntry = false;
        } else {
          errorMessage.append(',');
        }
        errorMessage.append(' ');
        errorMessage.append(entry.getKey().getCanonicalForm());
        errorMessage.append(" (as ");
        errorMessage.repr(entry.getValue());
        errorMessage.append(')');
      }
      throw new ConversionException(errorMessage.toString());
    }
  }

  /**
   * Like Label, LicenseType is a derived type, which is declared specially in order to allow syntax
   * validation. It represents the licenses, as described in {@link License}.
   */
  public static final class LicenseType extends Type<License> {
    @Override
    public License cast(Object value) {
      return (License) value;
    }

    @Override
    public License convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      try {
        List<String> licenseStrings = STRING_LIST.convert(x, what);
        return License.parseLicense(licenseStrings);
      } catch (LicenseParsingException e) {
        throw new ConversionException(e.getMessage());
      }
    }

    @Override
    public Object copyAndLiftStarlarkValue(
        Object x, Object what, @Nullable LabelConverter labelConverter) throws ConversionException {
      return STRING_LIST.copyAndLiftStarlarkValue(x, what, labelConverter);
    }

    @Override
    public License getDefaultValue() {
      return License.NO_LICENSE;
    }

    @Override
    public void visitLabels(LabelVisitor visitor, License value, @Nullable Attribute context) {}

    @Override
    public String toString() {
      return "license";
    }
  }

  /**
   * Like Label, Distributions is a derived type, which is declared specially in order to allow
   * syntax validation. It represents the declared distributions of a target, as described in {@link
   * License}.
   */
  private static final class Distributions extends Type<Set<DistributionType>> {
    @SuppressWarnings("unchecked")
    @Override
    public Set<DistributionType> cast(Object value) {
      return (Set<DistributionType>) value;
    }

    @Override
    public Set<DistributionType> convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      try {
        List<String> distribStrings = STRING_LIST.convert(x, what);
        return License.parseDistributions(distribStrings);
      } catch (LicenseParsingException e) {
        throw new ConversionException(e.getMessage());
      }
    }

    @Override
    public Set<DistributionType> getDefaultValue() {
      return Collections.emptySet();
    }

    @Override
    public void visitLabels(
        LabelVisitor visitor, Set<DistributionType> value, @Nullable Attribute context) {}

    @Override
    public String toString() {
      return "distributions";
    }

    @Override
    public Type<DistributionType> getListElementType() {
      return DISTRIBUTION;
    }
  }

  private static final class OutputType extends Type<Label> {
    @Override
    public Label cast(Object value) {
      return (Label) value;
    }

    @Nullable
    @Override
    public Label getDefaultValue() {
      return null;
    }

    @Override
    public void visitLabels(LabelVisitor visitor, Label value, @Nullable Attribute context) {
      visitor.visit(value, context);
    }

    @Override
    public LabelClass getLabelClass() {
      return LabelClass.OUTPUT;
    }

    @Override
    public String toString() {
      return "output";
    }

    @Override
    public Label convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      Label result = LABEL.convert(x, what, labelConverter);
      if (!result.getPackageIdentifier().equals(labelConverter.getBasePackage())) {
        throw new ConversionException("label '" + x + "' is not in the current package");
      }
      return result;
    }
  }

  /**
   * Holds an ordered collection of {@link Selector}s. This is used to support {@code attr =
   * rawValue + select(...) + select(...) + ..."} syntax. For consistency's sake, raw values are
   * stored as selects with only a default condition.
   */
  // TODO(adonovan): merge with packages.Selector{List,Value}.
  // We don't need three classes for the same concept.
  public static final class SelectorList<T> implements StarlarkValue {
    private final Type<T> originalType;
    private final List<Selector<T>> elements;

    @VisibleForTesting
    SelectorList(
        List<Object> x, Object what, @Nullable LabelConverter context, Type<T> originalType)
        throws ConversionException {
      if (x.size() > 1 && originalType.concat(ImmutableList.of()) == null) {
        throw new ConversionException(
            String.format("type '%s' doesn't support select concatenation", originalType));
      }

      ImmutableList.Builder<Selector<T>> builder = ImmutableList.builder();
      for (Object elem : x) {
        if (elem instanceof SelectorValue) {
          builder.add(new Selector<>(((SelectorValue) elem).getDictionary(), what,
              context, originalType, ((SelectorValue) elem).getNoMatchError()));
        } else {
          T directValue = originalType.convert(elem, what, context);
          builder.add(new Selector<>(ImmutableMap.of(Selector.DEFAULT_CONDITION_KEY, directValue),
              what, context, originalType));
        }
      }
      this.originalType = originalType;
      this.elements = builder.build();
    }

    SelectorList(List<Selector<T>> elements, Type<T> originalType) {
      this.elements = ImmutableList.copyOf(elements);
      this.originalType = originalType;
    }

    /**
     * Returns a syntactically order-preserved list of all values and selectors for this attribute.
     */
    public List<Selector<T>> getSelectors() {
      return elements;
    }

    /**
     * Returns the native Type for this attribute (i.e. what this would be if it wasn't a selector
     * list).
     */
    public Type<T> getOriginalType() {
      return originalType;
    }

    /** Returns the labels of all configurability keys across all selects in this expression. */
    public Set<Label> getKeyLabels() {
      ImmutableSet.Builder<Label> keys = ImmutableSet.builder();
      for (Selector<T> selector : elements) {
        selector.forEach(
            (label, value) -> {
              if (!Selector.isDefaultConditionLabel(label)) {
                keys.add(label);
              }
            });
      }
      return keys.build();
    }

    @Override
    public String toString() {
      return Starlark.repr(this);
    }

    @Override
    public void repr(Printer printer) {
      // Convert to a lib.packages.SelectorList to guarantee consistency with callers that serialize
      // directly on that type.
      List<SelectorValue> selectorValueList = new ArrayList<>();
      for (Selector<T> element : elements) {
        selectorValueList.add(new SelectorValue(element.mapCopy(), element.getNoMatchError()));
      }
      try {
        printer.repr(com.google.devtools.build.lib.packages.SelectorList.of(selectorValueList));
      } catch (EvalException e) {
        throw new IllegalStateException("this list should have been validated on creation", e);
      }
    }
  }

  /** Lazy string message to pass as the {@code what} when converting a select branch value. */
  private static final class SelectBranchMessage {
    private final Object what;
    private final Label key;

    SelectBranchMessage(Object what, Label key) {
      this.what = what;
      this.key = key;
    }

    @Override
    public String toString() {
      return String.format("each branch in select expression of %s (including '%s')", what, key);
    }
  }

  /**
   * Represents the entries in a single select expression (in the order they were initially
   * specified). Contains the configurability pattern (label) and value (objects of the attribute's
   * native type) of each entry.
   */
  public static final class Selector<T> {
    /** Value to use when none of an attribute's selection criteria match. */
    @VisibleForTesting
    public static final String DEFAULT_CONDITION_KEY = "//conditions:default";

    static final Label DEFAULT_CONDITION_LABEL =
        Label.parseCanonicalUnchecked(DEFAULT_CONDITION_KEY);

    private final Type<T> originalType;

    private final Label[] labels;

    // Can contain nulls, when an entry maps to None and the Type<T> has a null getDefaultValue().
    private final T[] values;

    private final Set<Label> conditionsWithDefaultValues;
    private final String noMatchError;
    private final int defaultConditionPos;

    /** Creates a new Selector using the default error message when no conditions match. */
    Selector(
        ImmutableMap<?, ?> x, Object what, @Nullable LabelConverter context, Type<T> originalType)
        throws ConversionException {
      this(x, what, context, originalType, "");
    }

    /** Creates a new Selector with a custom error message for when no conditions match. */
    Selector(
        ImmutableMap<?, ?> x,
        Object what,
        @Nullable LabelConverter context,
        Type<T> originalType,
        String noMatchError)
        throws ConversionException {
      this.originalType = originalType;
      Label[] labels = new Label[x.size()];
      @SuppressWarnings("unchecked")
      T[] values = (T[]) new Object[x.size()];
      ImmutableSet.Builder<Label> defaultValuesBuilder = ImmutableSet.builder();
      int pos = 0;
      int defaultConditionPos = -1;
      for (Map.Entry<?, ?> entry : x.entrySet()) {
        Label key = LABEL.convert(entry.getKey(), what, context);
        labels[pos] = key;
        T value;
        if (entry.getValue() == Starlark.NONE) {
          // { "//condition": None } is the same as not setting the value.
          value = originalType.getDefaultValue();
          defaultValuesBuilder.add(key);
        } else {
          Object selectBranch = what == null ? null : new SelectBranchMessage(what, key);
          value = originalType.convert(entry.getValue(), selectBranch, context);
        }
        if (key.equals(DEFAULT_CONDITION_LABEL)) {
          defaultConditionPos = pos;
        }
        values[pos] = value;
        pos++;
      }
      this.labels = labels;
      this.values = values;
      this.noMatchError = noMatchError;
      this.conditionsWithDefaultValues = defaultValuesBuilder.build();
      this.defaultConditionPos = defaultConditionPos;
    }

    /**
     * Create a new Selector from raw values. Defensive copies of the supplied arrays are <i>not</i>
     * made, so it is imperative that they are not modified following construction.
     */
    Selector(
        Label[] labels,
        T[] values,
        Type<T> originalType,
        String noMatchError,
        ImmutableSet<Label> conditionsWithDefaultValues,
        int defaultConditionPos) {
      this.labels = labels;
      this.values = values;
      this.originalType = originalType;
      this.noMatchError = noMatchError;
      this.conditionsWithDefaultValues = conditionsWithDefaultValues;
      this.defaultConditionPos = defaultConditionPos;
    }

    public boolean hasDefault() {
      return defaultConditionPos >= 0;
    }

    /** Returns the value to use when none of the attribute's selection keys match. */
    @Nullable
    public T getDefault() {
      return defaultConditionPos < 0 ? null : values[defaultConditionPos];
    }

    /**
     * Returns a new {@link ArrayList} containing all the values in the entries of this {@link
     * Selector}, in the same order they were initially specified.
     *
     * <p>Prefer using {@link #forEach} since that makes no allocations.
     */
    public ArrayList<T> valuesCopy() {
      // N.B. We can't use ImmutableList since we can have null values.
      ArrayList<T> result = Lists.newArrayListWithCapacity(getNumEntries());
      forEach((label, value) -> result.add(value));
      return result;
    }

    /**
     * Returns a new {@link LinkedHashMap} representing the branches of this {@link Selector}, in
     * the same order they were initially specified.
     *
     * <p>Prefer using {@link #forEach} since that makes no allocations.
     */
    public LinkedHashMap<Label, T> mapCopy() {
      // N.B. We can't use ImmutableMap since we can have null values. But we also want to respect
      // the ordering of our original map, so we use LinkedHashMap instead of HashMap.
      LinkedHashMap<Label, T> result = Maps.newLinkedHashMapWithExpectedSize(getNumEntries());
      forEach(result::put);
      return result;
    }

    /** Consumer for {@link #forEach}. */
    public interface SelectorEntryConsumer<T> {
      void accept(Label conditionLabel, @Nullable T value);
    }

    /**
     * Passes each entry to the provided {@code consumer}, in the same order they were initially
     * specified.
     */
    public void forEach(SelectorEntryConsumer<T> consumer) {
      for (int i = 0; i < labels.length; i++) {
        consumer.accept(labels[i], values[i]);
      }
    }

    /** Consumer for {@link #forEachExceptionally}. */
    interface ExceptionalSelectorEntryConsumer<T, E1 extends Exception, E2 extends Exception> {
      void accept(Label conditionLabel, @Nullable T value) throws E1, E2;
    }

    /**
     * Passes each entry to the provided {@code consumer}, in the same order they were initially
     * specified.
     */
    public <E1 extends Exception, E2 extends Exception> void forEachExceptionally(
        ExceptionalSelectorEntryConsumer<T, E1, E2> consumer) throws E1, E2 {
      for (int i = 0; i < labels.length; i++) {
        consumer.accept(labels[i], values[i]);
      }
    }

    /** Returns the number of entries. */
    public int getNumEntries() {
      return labels.length;
    }

    /**
     * Returns the native Type for this attribute (i.e. what this would be if it wasn't a selector
     * expression).
     */
    public Type<T> getOriginalType() {
      return originalType;
    }

    /**
     * Returns true if this selector has the structure: {"//conditions:default": ...}. That means
     * all values are always chosen.
     */
    public boolean isUnconditional() {
      return labels.length == 1 && defaultConditionPos >= 0;
    }

    /**
     * Returns true if an explicit value is set for the given condition, vs. { "//condition": None }
     * which means revert to the default.
     */
    public boolean isValueSet(Label condition) {
      return !conditionsWithDefaultValues.contains(condition);
    }

    /**
     * Returns a custom error message for this select when no condition matches, or an empty string
     * if no such message is declared.
     */
    public String getNoMatchError() {
      return noMatchError;
    }

    /**
     * Returns true for the default condition label, which is not intended to map to an actual
     * target.
     */
    public static boolean isDefaultConditionLabel(Label label) {
      return DEFAULT_CONDITION_LABEL.equals(label);
    }
  }

  /**
   * A TriState value is like a boolean attribute whose default value may be distinguished from
   * either of the possible explicitly assigned values. TriState attributes may be assigned the
   * values 0 (NO), 1 (YES), or None (AUTO). TriState is deprecated; use attr.int(values=[-1, 0, 1])
   * instead.
   */
  private static final class TriStateType extends Type<TriState> {
    @Override
    public TriState cast(Object value) {
      return (TriState) value;
    }

    @Override
    public TriState getDefaultValue() {
      return TriState.AUTO;
    }

    @Override
    public void visitLabels(LabelVisitor visitor, TriState value, @Nullable Attribute context) {}

    @Override
    public String toString() {
      return "tristate";
    }

    @Override
    public TriState convert(Object x, Object what, LabelConverter labelConverter)
        throws ConversionException {
      if (x instanceof TriState) {
        return (TriState) x;
      }
      if (x instanceof Boolean) {
        // TODO(adonovan): re-enable this under flag control; see b/116691720.
        // throw new ConversionException(this, x,
        //   "rule attribute (tristate is being replaced by "
        //       + "attr.int(values=[-1, 0, 1]), and it no longer accepts Boolean values; "
        //       + "instead, use 0 or 1, or None for the default)");
        return ((Boolean) x) ? TriState.YES : TriState.NO;
      }
      int xAsInteger = INTEGER.convert(x, what, labelConverter).toIntUnchecked();
      if (xAsInteger == -1) {
        return TriState.AUTO;
      } else if (xAsInteger == 1) {
        return TriState.YES;
      } else if (xAsInteger == 0) {
        return TriState.NO;
      }
      throw new ConversionException(this, x, "TriState values is not one of [-1, 0, 1]");
    }
  }
}
