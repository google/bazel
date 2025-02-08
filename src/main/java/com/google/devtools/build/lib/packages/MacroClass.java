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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL_LIST;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.TargetRecorder.MacroFrame;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.spelling.SpellChecker;

/**
 * Represents a symbolic macro, defined in a .bzl file, that may be instantiated during Package
 * evaluation.
 *
 * <p>This is analogous to {@link RuleClass}. In essence, a {@code MacroClass} consists of the
 * macro's schema and its implementation function.
 */
public final class MacroClass {

  /**
   * Names that users may not pass as keys of the {@code attrs} dict when calling {@code macro()}.
   *
   * <p>Of these, {@code name} is special cased as an actual attribute, and the rest do not exist.
   */
  // Keep in sync with `macro()`'s `attrs` user documentation in StarlarkRuleFunctionsApi.
  // But we should avoid adding new entries here, since it's a backwards-incompatible change.
  public static final ImmutableSet<String> RESERVED_MACRO_ATTR_NAMES =
      ImmutableSet.of("name", "visibility");

  /**
   * "visibility" attribute present on all symbolic macros.
   *
   * <p>This is similar to the visibility attribute for rules, but lacks the exec transitions.
   */
  public static final Attribute VISIBILITY_ATTRIBUTE =
      Attribute.attr("visibility", NODEP_LABEL_LIST)
          .orderIndependent()
          .nonconfigurable("special attribute integrated more deeply into Bazel's core logic")
          .build();

  private final String name;
  private final Label definingBzlLabel;
  private final StarlarkFunction implementation;
  // Implicit attributes are stored under their given name ("_foo"), not a mangled name ("$foo").
  private final ImmutableMap<String, Attribute> attributes;
  private final boolean isFinalizer;

  private MacroClass(
      String name,
      Label definingBzlLabel,
      StarlarkFunction implementation,
      ImmutableMap<String, Attribute> attributes,
      boolean isFinalizer) {
    this.name = name;
    this.definingBzlLabel = definingBzlLabel;
    this.implementation = implementation;
    this.attributes = attributes;
    this.isFinalizer = isFinalizer;
  }

  /** Returns the macro's exported name. */
  public String getName() {
    return name;
  }

  /** Returns the label of the .bzl file where the macro was exported. */
  public Label getDefiningBzlLabel() {
    return definingBzlLabel;
  }

  public StarlarkFunction getImplementation() {
    return implementation;
  }

  // NB: Order is preserved from what was passed to the constructor.
  public ImmutableMap<String, Attribute> getAttributes() {
    return attributes;
  }

  /**
   * Returns whether this symbolic macro is a finalizer. All finalizers are run deferred to the end
   * of the BUILD file's evaluation, rather than synchronously with their instantiation.
   */
  public boolean isFinalizer() {
    return isFinalizer;
  }

  /** Builder for {@link MacroClass}. */
  public static final class Builder {
    @Nullable private String name = null;
    @Nullable private Label definingBzlLabel = null;
    private final StarlarkFunction implementation;
    private final ImmutableMap.Builder<String, Attribute> attributes = ImmutableMap.builder();
    private boolean isFinalizer = false;

    public Builder(StarlarkFunction implementation) {
      this.implementation = implementation;

      addAttribute(RuleClass.NAME_ATTRIBUTE);
      addAttribute(VISIBILITY_ATTRIBUTE);
    }

    @CanIgnoreReturnValue
    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setDefiningBzlLabel(Label label) {
      this.definingBzlLabel = label;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addAttribute(Attribute attribute) {
      attributes.put(attribute.getName(), attribute);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setIsFinalizer() {
      this.isFinalizer = true;
      return this;
    }

    public MacroClass build() {
      Preconditions.checkNotNull(name);
      Preconditions.checkNotNull(definingBzlLabel);
      return new MacroClass(
          name,
          definingBzlLabel,
          implementation,
          attributes.buildOrThrow(),
          /* isFinalizer= */ isFinalizer);
    }
  }

  /**
   * Constructs and returns a new {@link MacroInstance} associated with this {@code MacroClass}.
   *
   * <p>See {@link #instantiateAndAddMacro}.
   */
  // TODO(#19922): Consider reporting multiple events instead of failing on the first one. See
  // analogous implementation in RuleClass#populateDefinedRuleAttributeValues.
  private MacroInstance instantiateMacro(Package.Builder pkgBuilder, Map<String, Object> kwargs)
      throws EvalException {
    // A word on edge cases:
    //   - If an attr is implicit but does not have a default specified, its value is just the
    //     default value for its attr type (e.g. `[]` for `attr.label_list()`).
    //   - If an attr is implicit but also mandatory, it's impossible to instantiate it without
    //     error.
    //   - If an attr is mandatory but also has a default, the default is meaningless.
    // These behaviors align with rule attributes.

    LinkedHashMap<String, Object> attrValues = new LinkedHashMap<>();

    // For each given attr value, validate that the attr exists and can be set.
    for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
      String attrName = entry.getKey();
      Object value = entry.getValue();
      Attribute attr = attributes.get(attrName);

      // Check for unknown attr.
      if (attr == null) {
        throw Starlark.errorf(
            "no such attribute '%s' in '%s' macro%s",
            attrName,
            name,
            SpellChecker.didYouMean(
                attrName,
                attributes.values().stream()
                    .filter(Attribute::isDocumented)
                    .map(Attribute::getName)
                    .collect(toImmutableList())));
      }

      // Setting an attr to None is the same as omitting it (except that it's still an error to set
      // an unknown attr to None). If the attr is optional, skip adding it to the map now but put it
      // in below when we realize it's missing.
      if (value == Starlark.NONE) {
        continue;
      }

      // Can't set implicit default.
      // (We don't check Attribute#isImplicit() because that assumes "_" -> "$" prefix mangling.)
      // TODO: #19922 - The lack of "_" -> "$" mangling may impact the future feature of inheriting
      // attributes from rules. We could consider just doing the mangling for macros too so they're
      // consistent.
      if (attr.getName().startsWith("_")) {
        throw Starlark.errorf("cannot set value of implicit attribute '%s'", attr.getName());
      }

      attrValues.put(attrName, value);
    }

    // Special processing of the "visibility" attribute.
    // TODO(brandjon): When we add introspection of attributes of symbolic macros, we'll want to
    // distinguish between the different types of visibility a la Target#getRawVisibility /
    // #getVisibility / #getActualVisibility.
    @Nullable MacroFrame parentMacroFrame = pkgBuilder.getCurrentMacroFrame();
    @Nullable Object rawVisibility = attrValues.get("visibility");
    RuleVisibility parsedVisibility;
    if (rawVisibility == null) {
      // Visibility wasn't explicitly supplied. If we're not in another symbolic macro, use the
      // package's default visibility, otherwise use private visibility.
      if (parentMacroFrame == null) {
        parsedVisibility = pkgBuilder.getPartialPackageArgs().defaultVisibility();
      } else {
        parsedVisibility = RuleVisibility.PRIVATE;
      }
    } else {
      @SuppressWarnings("unchecked")
      List<Label> liftedVisibility =
          (List<Label>)
              BuildType.copyAndLiftStarlarkValue(
                  name, VISIBILITY_ATTRIBUTE, rawVisibility, pkgBuilder.getLabelConverter());
      parsedVisibility = RuleVisibility.parse(liftedVisibility);
    }
    // Concatenate the visibility (as previously populated) with the instantiation site's location.
    PackageIdentifier instantiatingLoc =
        parentMacroFrame == null
            ? pkgBuilder.getPackageIdentifier()
            : parentMacroFrame.macroInstance.getDefinitionPackage();
    RuleVisibility actualVisibility = parsedVisibility.concatWithPackage(instantiatingLoc);
    attrValues.put("visibility", actualVisibility.getDeclaredLabels());

    // Populate defaults for the rest, and validate that no mandatory attr was missed.
    for (Attribute attr : attributes.values()) {
      if (attrValues.containsKey(attr.getName())) {
        continue;
      }
      if (attr.isMandatory()) {
        throw Starlark.errorf(
            "missing value for mandatory attribute '%s' in '%s' macro", attr.getName(), name);
      } else {
        Object defaultValue = attr.getDefaultValueUnchecked();
        // For attributes defined directly in this macro's `attrs` param, we've already validated
        // that the default is not a computed default, late-bound default, etc. But these may still
        // appear in inherited attributes. Those should be replaced by None as the default.
        //
        // We may also see a null default for LabelType, which also should be replaced by None.
        //
        // TODO(arostovtsev): All inherited attributes should be forced to have None defaults.
        if (defaultValue == null || shouldForceDefaultToNone(attr)) {
          // Set the default value as None if:
          defaultValue = Starlark.NONE;
        }
        attrValues.put(attr.getName(), defaultValue);
      }
    }

    // Normalize and validate all attr values. (E.g., convert strings to labels, promote
    // configurable attribute values to select()s, fail if bool was passed instead of label, ensure
    // values are immutable.) Note that this applies even to default values, although as a special
    // case None is never promoted to select().
    for (Map.Entry<String, Object> entry : ImmutableMap.copyOf(attrValues).entrySet()) {
      String attrName = entry.getKey();
      Object value = entry.getValue();
      Attribute attribute = attributes.get(attrName);
      if (value.equals(Starlark.NONE)) {
        // Don't promote None to select({"//conditions:default": None}).
        continue;
      }
      Object normalizedValue =
          // copyAndLiftStarlarkValue ensures immutability.
          BuildType.copyAndLiftStarlarkValue(
              name, attribute, value, pkgBuilder.getLabelConverter());
      // TODO(#19922): Validate that LABEL_LIST type attributes don't contain duplicates, to match
      // the behavior of rules. This probably requires factoring out logic from
      // AggregatingAttributeMapper.
      if (attribute.isConfigurable() && !(normalizedValue instanceof SelectorList)) {
        normalizedValue = SelectorList.wrapSingleValue(normalizedValue);
      }
      attrValues.put(attrName, normalizedValue);
    }

    // Type and existence enforced by RuleClass.NAME_ATTRIBUTE.
    String name = (String) Preconditions.checkNotNull(attrValues.get("name"));
    // Determine the id for this macro. If we're in another macro by the same name, increment the
    // number, otherwise use 1 for the number.
    int sameNameDepth =
        parentMacroFrame == null || !name.equals(parentMacroFrame.macroInstance.getName())
            ? 1
            : parentMacroFrame.macroInstance.getSameNameDepth() + 1;

    return pkgBuilder.createMacro(this, attrValues, sameNameDepth);
  }

  /**
   * Returns true if the given attribute's default value should be considered {@code None}.
   *
   * <p>This is the case for non-direct defaults and legacy licenses and distribs attributes,
   * because None may (depending on attribute type) violate type checking - and that is ok, since
   * the macro implementation will pass the None to the rule function, which will then set the
   * default as expected.
   */
  private static boolean shouldForceDefaultToNone(Attribute attr) {
    return attr.hasComputedDefault()
        || attr.isLateBound()
        || attr.isMaterializing()
        || attr.getType() == BuildType.LICENSE
        || attr.getType() == BuildType.DISTRIBUTIONS;
  }

  /**
   * Constructs a new {@link MacroInstance} associated with this {@code MacroClass}, adds it to the
   * package, and returns it.
   *
   * @param pkgBuilder The builder corresponding to the package in which this instance will live.
   * @param kwargs A map from attribute name to its given Starlark value, such as passed in a BUILD
   *     file (i.e., prior to attribute type conversion, {@code select()} promotion, default value
   *     substitution, or even validation that the attribute exists).
   */
  public MacroInstance instantiateAndAddMacro(
      Package.Builder pkgBuilder, Map<String, Object> kwargs) throws EvalException {
    MacroInstance macroInstance = instantiateMacro(pkgBuilder, kwargs);
    try {
      pkgBuilder.addMacro(macroInstance);
    } catch (NameConflictException e) {
      throw new EvalException(e);
    }
    return macroInstance;
  }

  /**
   * Executes a symbolic macro's implementation function, in a new Starlark thread, mutating the
   * given package under construction.
   */
  // TODO: #19922 - Take a new type, PackagePiece.Builder, in place of Package.Builder. PackagePiece
  // would represent the collection of targets/macros instantiated by expanding a single symbolic
  // macro.
  public static void executeMacroImplementation(
      MacroInstance macro, Package.Builder builder, StarlarkSemantics semantics)
      throws InterruptedException {
    // Ensure we're not expanding a (possibly indirect) recursive macro. This is morally analogous
    // to StarlarkThread#isRecursiveCall, except in this context, recursion is through the chain of
    // macro instantiations, which may or may not actually be concurrently executing on the stack
    // depending on whether the evaluation is eager or deferred.
    @Nullable String recursionMsg = getRecursionErrorMessage(macro);
    if (recursionMsg != null) {
      builder
          .getLocalEventHandler()
          .handle(Package.error(/* location= */ null, recursionMsg, Code.STARLARK_EVAL_ERROR));
      builder.setContainsErrors();
      // Don't try to evaluate this macro again.
      builder.markMacroComplete(macro);
      return;
    }

    try (Mutability mu =
        Mutability.create("macro", builder.getPackageIdentifier(), macro.getName())) {
      StarlarkThread thread =
          StarlarkThread.create(
              mu,
              semantics,
              /* contextDescription= */ "",
              SymbolGenerator.create(
                  MacroInstance.UniqueId.create(
                      macro.getPackage().getPackageIdentifier(), macro.getId())));
      thread.setPrintHandler(Event.makeDebugPrintHandler(builder.getLocalEventHandler()));

      // TODO: #19922 - Technically the embedded SymbolGenerator field should use a different key
      // than the one in the main BUILD thread, but that'll be fixed when we change the type to
      // PackagePiece.Builder.
      builder.storeInThread(thread);

      // TODO: #19922 - If we want to support creating analysis_test rules inside symbolic macros,
      // we'd need to call `thread.setThreadLocal(RuleDefinitionEnvironment.class,
      // ruleClassProvider)`. In that case we'll need to consider how to get access to the
      // ConfiguredRuleClassProvider. For instance, we could put it in the builder.

      MacroFrame childMacroFrame = new MacroFrame(macro);
      @Nullable MacroFrame parentMacroFrame = builder.setCurrentMacroFrame(childMacroFrame);
      try {
        Object returnValue =
            Starlark.call(
                thread,
                macro.getMacroClass().getImplementation(),
                /* args= */ ImmutableList.of(),
                /* kwargs= */ macro.getAttrValues());
        if (returnValue != Starlark.NONE) {
          throw Starlark.errorf(
              "macro '%s' may not return a non-None value (got %s)",
              macro.getName(), Starlark.repr(returnValue));
        }
      } catch (EvalException ex) { // from either call() or non-None return
        builder
            .getLocalEventHandler()
            .handle(
                Package.error(
                    /* location= */ null, ex.getMessageWithStack(), Code.STARLARK_EVAL_ERROR));
        builder.setContainsErrors();
      } finally {
        // Restore the previously running symbolic macro's state (if any).
        @Nullable MacroFrame top = builder.setCurrentMacroFrame(parentMacroFrame);
        Preconditions.checkState(top == childMacroFrame, "inconsistent macro stack state");
        // Mark the macro as having completed, even if it was in error (or interrupted?).
        builder.markMacroComplete(macro);
      }
    }
  }

  /**
   * If the instantiation of {@code macro} was recursive, i.e. if it was transitively declared by
   * another macro instance having the same macro class, then returns an error string identifying
   * this macro's name and a "traceback" of the instantiating macros. Otherwise, returns null.
   */
  @Nullable
  private static String getRecursionErrorMessage(MacroInstance macro) {
    MacroInstance ancestor = macro.getParent();
    boolean foundRecursion = false;
    boolean onImmediateParent = true;
    while (ancestor != null) {
      // TODO: #19922 - We're checking based on object identity here. If we need to worry about
      // macro classes being serialized and deserialized in a context that also does macro
      // evaluation, then we should use the more durable identifier of its definition label + name.
      if (ancestor.getMacroClass() == macro.getMacroClass()) {
        foundRecursion = true;
        break;
      }
      ancestor = ancestor.getParent();
      onImmediateParent = false;
    }
    if (!foundRecursion) {
      return null;
    }

    StringBuilder msg = new StringBuilder();
    msg.append(
        String.format(
            "macro '%s' is %s recursive call of '%s'. Macro instantiation traceback (most"
                + " recent call last):",
            macro.getName(), onImmediateParent ? "a direct" : "an indirect", ancestor.getName()));

    // Materialize the stack as an ArrayList, since we want to output it in reverse order (outermost
    // first).
    ArrayList<MacroInstance> allAncestors = new ArrayList<>();
    ancestor = macro;
    while (ancestor != null) {
      allAncestors.add(ancestor);
      ancestor = ancestor.getParent();
    }
    for (MacroInstance item : Lists.reverse(allAncestors)) {
      String pkg = item.getPackage().getPackageIdentifier().getCanonicalForm();
      String type =
          item.getMacroClass().getDefiningBzlLabel().getCanonicalForm()
              + "%"
              + item.getMacroClass().getName();
      msg.append(String.format("\n\tPackage %s, macro '%s' of type %s", pkg, item.getName(), type));
    }
    return msg.toString();
  }
}
