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

package com.google.devtools.build.lib.packages;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Represents a use of a symbolic macro in a package.
 *
 * <p>There is one {@code MacroInstance} for each call to a {@link
 * StarlarkRuleClassFunctions#MacroFunction} that is executed during a package's evaluation. Just as
 * a {@link MacroClass} is analogous to a {@link RuleClass}, {@code MacroInstance} is analogous to a
 * {@link Rule} (i.e. a rule target).
 *
 * <p>Macro instance names are not guaranteed to be unique within a package; see {@link #getId}.
 */
public final class MacroInstance {

  // TODO: #19922 - If we want to save the cost of a field here, we can merge pkg and parent into a
  // single field of type Object, and walk up the parent hierarchy to answer getPackage() queries.
  private final Package pkg;

  @Nullable private final MacroInstance parent;

  private final MacroClass macroClass;

  private final int sameNameDepth;

  // Order isn't guaranteed, sort before dumping. You can use the schema map
  // MacroClass#getAttributes for a guaranteed order.
  private final ImmutableMap<String, Object> attrValues;

  // TODO(#19922): Consider switching to more optimized, indexed representation for attributes, as
  // in Rule.

  /**
   * Instantiates the given macro class with the given attribute values.
   *
   * <p>{@code attrValues} must have already been normalized based on the types of the attributes;
   * see {@link MacroClass#instantiateMacro}. Values for the {@code "name"} and {@code "visibility"}
   * attributes must exist with the correct types, and the {@code "visibility"} value must satisfy
   * {@link RuleVisibility#validate}.
   *
   * <p>{@code sameNameDepth} is the number of macro instances that this one is inside of that share
   * its name. For most instances it is 1, but for the main submacro of a parent macro it is one
   * more than the parent's depth.
   *
   * @throws EvalException if there's a problem with the attribute values (currently, only thrown if
   *     the {@code visibility} value is invalid)
   */
  // TODO: #19922 - Better encapsulate the invariant around attrValues, by either transitioning to
  // storing internal-typed values (the way Rules do) instead of Starlark-typed values, or else just
  // making the constructor private and moving instantiateMacro() to this class.
  public MacroInstance(
      Package pkg,
      @Nullable MacroInstance parent,
      MacroClass macroClass,
      Map<String, Object> attrValues,
      int sameNameDepth)
      throws EvalException {
    this.pkg = pkg;
    this.parent = parent;
    this.macroClass = macroClass;
    this.attrValues = ImmutableMap.copyOf(attrValues);
    Preconditions.checkArgument(sameNameDepth > 0);
    this.sameNameDepth = sameNameDepth;
    Preconditions.checkArgument(macroClass.getAttributes().keySet().equals(attrValues.keySet()));
  }

  /** Returns the package this instance was created in. */
  public Package getPackage() {
    return pkg;
  }

  /**
   * Returns the macro instance that instantiated this one, or null if this was created directly
   * during BUILD evaluation.
   */
  @Nullable
  public MacroInstance getParent() {
    return parent;
  }

  /** Returns the {@link MacroClass} (i.e. schema info) that this instance parameterizes. */
  public MacroClass getMacroClass() {
    return macroClass;
  }

  /**
   * The depth of this macro instance in a chain of nested macros having the same name.
   *
   * <p>1 for any macro that is not declared in a macro of the same name.
   *
   * <p>Used by {@link #getId}.
   */
  public int getSameNameDepth() {
    return sameNameDepth;
  }

  /**
   * Returns the id of this macro instance. The id is the name, concatenated with {@code ":n"} where
   * n is an integer distinguishing this from other macro instances of the same name in the package.
   *
   * <p>Within a package, two macro instances are not allowed to share the same name except when one
   * of them is the main submacro of the other. More generally, there may be a contiguous chain of
   * nested main submacros that all share the same name, but these may not share with any other
   * macro outside the chain. We allow this exception so that the build does not break if the rule
   * of a main target is refactored into a macro. The tradeoff of this design is that the name alone
   * is not enough to disambiguate between macros in the chain.
   *
   * <p>The number n is simply the depth of the macro in the chain of same-named macros, starting at
   * 1. For example, if we have a chain of macro expansions foo -> foo_bar -> foo_bar -> foo_bar ->
   * foo_bar_baz, then the ids of these macros are respectively "foo:1", "foo_bar:1", "foo_bar:2",
   * "foo_bar:3", "foo_bar_baz:1".
   *
   * <p>Note that ids only serve to canonically identify macro instances, and play no role in naming
   * or name conflict detection.
   */
  public String getId() {
    return getName() + ":" + sameNameDepth;
  }

  /**
   * Returns the name of this instance, as given in the {@code name = ...} attribute in the calling
   * BUILD file or macro.
   */
  public String getName() {
    // Type and existence enforced by RuleClass.NAME_ATTRIBUTE.
    return (String) Preconditions.checkNotNull(attrValues.get("name"));
  }

  /**
   * Returns the visibility of this macro instance, analogous to {@link Target#getActualVisibility}.
   *
   * <p>This value will be observed as the {@code visibility} parameter of the implementation
   * function. It is not necessarily the same as the {@code visibility} value passed in when
   * instantiating the macro, since the latter needs processing to add the call site's location and
   * possibly apply the package's default visibility.
   *
   * <p>It can be assumed that the returned list satisfies {@link RuleVisibility#validate}.
   */
  public ImmutableList<Label> getActualVisibility() {
    @SuppressWarnings("unchecked")
    List<Label> visibility = (List<Label>) Preconditions.checkNotNull(attrValues.get("visibility"));
    return ImmutableList.copyOf(visibility);
  }

  /**
   * Dictionary of attributes for this instance.
   *
   * <p>Contains all attributes, as seen after processing by {@link
   * MacroClass#instantiateAndAddMacro}.
   */
  public ImmutableMap<String, Object> getAttrValues() {
    return attrValues;
  }

  /**
   * Returns the package containing the .bzl file from which this macro instance's macro class was
   * exported.
   *
   * <p>This is considered to be the place where the macro's code lives, and is used as the place
   * where a target is instantiated for the purposes of Macro-Aware Visibility.
   */
  public PackageIdentifier getDefinitionPackage() {
    return macroClass.getDefiningBzlLabel().getPackageIdentifier();
  }

  /**
   * Visits all labels appearing in non-implicit attributes of {@link Type.LabelClass#DEPENDENCY}
   * label type, i.e. ignoring nodep labels.
   *
   * <p>This is useful for checking whether a given label was passed as an input to this macro by
   * the caller, which in turn is needed in order to decide whether the caller delegated a
   * visibility privilege to us.
   */
  public void visitExplicitAttributeLabels(Consumer<Label> consumer) {
    for (Attribute attribute : macroClass.getAttributes().values()) {
      String name = attribute.getName();
      Type<?> type = attribute.getType();
      if (name.startsWith("_")) {
        continue;
      }
      if (type.getLabelClass() != Type.LabelClass.DEPENDENCY) {
        continue;
      }
      Object value = attrValues.get(name);
      if (value == Starlark.NONE) {
        continue;
      }
      visitAttributeLabels(value, type, attribute, consumer);
    }
  }

  // Separate method needed to satisfy type system w.r.t. Type<T>.
  // `value` is either a T or SelectorList<T>.
  private static <T> void visitAttributeLabels(
      Object value, Type<T> type, Attribute attribute, Consumer<Label> consumer) {
    // The attribute value is stored as a Starlark value. Convert it to the internal type as would
    // be used in rules, so we can apply visitLabels() machinery to it. selectableConvert() will
    // yield either a T or a BuildType.SelectorList.
    Object convertedValue;
    try {
      convertedValue =
          BuildType.selectableConvert(
              type,
              value,
              "macro attribute (internal)",
              // No string -> Label conversion is being done here.
              /* context= */ null,
              // Macros always preserve selects as selects.
              /* simplifyUnconditionalSelects= */ false);
    } catch (ConversionException e) {
      // TODO: #19922 - The fact that we have to do this seems like a signal that we should
      // transition to storing macro attribute values as native-typed attributes in the future.
      throw new IllegalStateException("Could not convert macro attribute value internally", e);
    }

    // Unlike rules, null attribute values are disallowed here by construction (the attrValues
    // map won't tolerate them). It's unclear if the visitor can be passed null values like it can
    // for rules, so filter them out just in case.
    Type.LabelVisitor visitor =
        (label, unusedAttribute) -> {
          if (label != null) {
            consumer.accept(label);
          }
        };

    if (convertedValue instanceof SelectorList) {
      @SuppressWarnings("unchecked") // safe by precondition assumption
      SelectorList<T> selectorList = (SelectorList<T>) convertedValue;
      AggregatingAttributeMapper.visitLabelsInSelect(
          selectorList,
          attribute,
          type,
          visitor,
          /* rule= */ null, // safe because late-bound defaults aren't a thing for macros
          /* includeKeys= */ false,
          /* includeValues= */ true);
    } else {
      T castValue = type.cast(convertedValue);
      type.visitLabels(visitor, castValue, attribute);
    }
  }

  /**
   * Logical tuple of the package and id within the package. Used to label the Starlark evaluation
   * environment.
   */
  record UniqueId(PackageIdentifier packageId, String id) {
    UniqueId {
      requireNonNull(packageId, "packageId");
      requireNonNull(id, "id");
    }

    static UniqueId create(PackageIdentifier packageId, String id) {
      return new UniqueId(packageId, id);
    }
  }
}
