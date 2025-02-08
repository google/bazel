// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper.attributeOrNull;

import com.google.devtools.build.lib.analysis.AliasProvider.TargetMode;
import com.google.devtools.build.lib.analysis.RuleContext.PrerequisiteValidator;
import com.google.devtools.build.lib.analysis.configuredtargets.PackageGroupConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.FunctionSplitTransitionAllowlist;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.MacroInstance;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StarlarkAspectClass;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import javax.annotation.Nullable;

/**
 * A base implementation of {@link PrerequisiteValidator} that performs common checks based on
 * definitions of what is considered the same logical package and what is considered "experimental"
 * code, which has may have relaxed checks for visibility and deprecation.
 */
public abstract class CommonPrerequisiteValidator implements PrerequisiteValidator {
  @Override
  public void validate(
      RuleContext.Builder contextBuilder,
      ConfiguredTargetAndData prerequisite,
      Attribute attribute) {
    validateDirectPrerequisiteLocation(contextBuilder, prerequisite);
    validateDirectPrerequisiteVisibility(contextBuilder, prerequisite, attribute);
    validateDirectPrerequisiteForTestOnly(contextBuilder, prerequisite);
    validateDirectPrerequisiteForDeprecation(
        contextBuilder, contextBuilder.getRule(), prerequisite, contextBuilder.forAspect());
  }

  /**
   * Returns whether two packages are considered the same for purposes of deprecation warnings.
   * Dependencies within the same package do not print deprecation warnings; a package in the
   * javatests directory may also depend on its corresponding java package without a warning.
   */
  // TODO: #19922 - Rename this method to not imply that it is symmetric across its arguments.
  public abstract boolean isSameLogicalPackage(
      PackageIdentifier thisPackage, PackageIdentifier prerequisitePackage);

  /**
   * Returns whether a package is considered experimental. Packages outside of experimental may not
   * depend on packages that are experimental.
   */
  protected abstract boolean packageUnderExperimental(PackageIdentifier packageIdentifier);

  protected abstract boolean checkVisibilityForExperimental(RuleContext.Builder context);

  protected abstract boolean checkVisibilityForToolchains(
      RuleContext.Builder context, Label prerequisite);

  protected abstract boolean allowExperimentalDeps(RuleContext.Builder context);

  private void validateDirectPrerequisiteVisibility(
      RuleContext.Builder context, ConfiguredTargetAndData prerequisite, Attribute attribute) {
    String attrName = attribute.getName();
    Rule rule = context.getRule();

    checkForMisplacedPackageGroups(context, prerequisite, attribute, attrName, rule);

    // We don't check the visibility of late-bound attributes, because it would break some
    // features.
    if (Attribute.isAnalysisDependent(attrName)) {
      return;
    }

    // Determine whether we should check toolchain target visibility.
    if (attrName.equals(RuleContext.TOOLCHAIN_ATTR_NAME)
        && !checkVisibilityForToolchains(context, prerequisite.getTargetLabel())) {
      return;
    }

    // Only verify visibility of implicit dependencies of the current aspect.
    // Dependencies of other aspects as well as the rule itself are checked when they are
    // evaluated.
    Aspect mainAspect = context.getMainAspect();
    if (mainAspect != null) {
      if (!attribute.isImplicit()
          || !mainAspect.getDefinition().getAttributes().containsKey(attrName)) {
        return;
      }
    }

    // Normally visibility is validated with respect to the location of the consuming target. But
    // implicit attributes of Starlark-defined rules and aspects get validated primarily with
    // respect to the .bzl where the rule or aspect is exported, with the location of the target
    // serving only as a fallback for backwards compatibility purposes.
    //
    // (We don't do the same for default values of non-implicit attributes. That would introduce a
    // semantic difference between omitting the attribute (allowing it to be populated by default),
    // vs. explicitly passing in a value that happens to be the same as its default.)
    boolean validateWithRespectToAttributeDefinition =
        attribute.isImplicit() && context.isStarlarkRuleOrAspect();
    // Also, the special $config_dependencies attribute is always validated as a normal dependency
    // even though it's technically implicit.
    validateWithRespectToAttributeDefinition &=
        !attribute.getName().equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE);

    if (!validateWithRespectToAttributeDefinition) {
      // Normal case: The attribute must be visible from the target.
      if (!isVisibleToDeclaration(prerequisite, rule)) {
        handleVisibilityConflict(context, prerequisite, rule.getLabel());
      }
    } else {
      // Determine the label of the .bzl where the rule or (main) aspect was exported.
      Label implicitDefinition;
      if (mainAspect != null) {
        StarlarkAspectClass aspectClass = (StarlarkAspectClass) mainAspect.getAspectClass();
        // Never null since we already checked that the aspect is Starlark-defined.
        implicitDefinition = checkNotNull(aspectClass.getExtensionLabel());
      } else {
        // Never null since we already checked that the rule is a Starlark rule.
        implicitDefinition =
            checkNotNull(rule.getRuleClassObject().getRuleDefinitionEnvironmentLabel());
      }
      // Validate with respect to the defining .bzl.
      if (!isVisibleToLocation(prerequisite, implicitDefinition.getPackageIdentifier())) {
        // Failed. Validate with respect to the target anyway, for backwards compatibility.
        // TODO(bazel-team): When can this fallback be removed?
        if (!isVisibleToDeclaration(prerequisite, rule)) {
          // True failure. In the error message, always suggest making the prerequisite visible from
          // the definition, not the target.
          handleVisibilityConflict(context, prerequisite, implicitDefinition);
        }
      }
    }
  }

  /**
   * Returns whether {@code prerequisite} is visible to {@code consumingDeclaration}, which can be
   * either a {@link Rule} target or a {@link MacroInstance}.
   *
   * <p>In general, this passes if {@code consumingDeclaration}'s location is allowed by {@code
   * prerequisite}'s visibility provider or the same-logical-package condition.
   *
   * <p>In this context, the "location" of a target means the package containing the defining bzl
   * (i.e. export label) of the symbolic macro that directly declares the target; or the target's
   * package if it was not declared within any symbolic macro.
   *
   * <p>As a special case, if {@code consumingDeclaration} was directly created by a symbolic macro
   * that takes in the {@code prerequisite}'s label (not following {@code alias}es), then before
   * running the above logic we first substitute the symbolic macro for {@code
   * consumingDeclaration}. This reflects how the usage of the prerequisite was not really by the
   * given declaration but rather its parent.
   */
  // TODO: #19922 - Consider replacing use of Object with a new interface abstracting Rule and
  // MacroInstance.
  private boolean isVisibleToDeclaration(
      ConfiguredTargetAndData prerequisite, Object consumingDeclaration) {
    Package pkg;
    @Nullable MacroInstance declaringMacro;
    if (consumingDeclaration instanceof Rule target) {
      pkg = target.getPackage();
      declaringMacro = target.getDeclaringMacro();
    } else if (consumingDeclaration instanceof MacroInstance macroInstance) {
      pkg = macroInstance.getPackage();
      declaringMacro = macroInstance.getParent();
    } else {
      throw new IllegalArgumentException(
          "Expected a Rule or MacroInstance, got " + consumingDeclaration.getClass().getName());
    }

    // Visibility delegation: If we're directly declared by a macro that took this prereq as an
    // argument from its own caller, then our location is moot, and it's the macro's usage that we
    // have to validate instead.
    if (declaringMacro != null) {
      // Don't conflate an alias with its target.
      Label prereqLabel = AliasProvider.getDependencyLabel(prerequisite.getConfiguredTarget());
      boolean[] declaringMacroWasGivenPrereqByCaller = {false};
      declaringMacro.visitExplicitAttributeLabels(
          label -> declaringMacroWasGivenPrereqByCaller[0] |= label.equals(prereqLabel));
      if (declaringMacroWasGivenPrereqByCaller[0]) {
        return isVisibleToDeclaration(prerequisite, declaringMacro);
      }
    }

    PackageIdentifier ruleTargetLocation =
        declaringMacro != null
            // Macro's location.
            ? declaringMacro.getMacroClass().getDefiningBzlLabel().getPackageIdentifier()
            // BUILD file's location.
            : pkg.getPackageIdentifier();

    return isVisibleToLocation(prerequisite, ruleTargetLocation);
  }

  /**
   * Returns whether {@code prerequisite} is visible to {@code location}, based on {@code
   * prerequisite}'s visibility provider and the same-logical-package condition.
   */
  private boolean isVisibleToLocation(
      ConfiguredTargetAndData prerequisite, PackageIdentifier location) {
    VisibilityProvider visibility =
        prerequisite.getConfiguredTarget().getProvider(VisibilityProvider.class);

    // For prerequisite targets that are created in symbolic macros, the visibility provider is
    // authoritative and we can move on to checking its package specifications one by one.
    //
    // For prerequisite targets that are *not* created in symbolic macros, the visibility provider
    // does not necessarily list the target's own declaration location (which is the same as the
    // package it lives in). In addition, the target should be visible to other packages that are
    // same-logical-package as this location, a property that doesn't apply to targets created in
    // symbolic macros. Calling isSameLogicalPackage() takes care of both of these checks. Note that
    // we don't need to worry about the package's default_visibility at this stage because
    // it is already accounted for at loading time by the target's getVisibility() accessor.
    //
    // TODO: #19922 - The same-logical-package logic should also be applied in the loading phase, to
    // the propagated visibility attribute inside symbolic macros, so that it applies to targets
    // exported from symbolic macros (i.e. targets that pass `visibility = visibility`).
    if (!visibility.isCreatedInSymbolicMacro()) {
      if (isSameLogicalPackage(
          location,
          // In the case of a prerequisite that is an alias rule, we check whether we can see the
          // alias itself, not the actual target it points to. In other words, alias re-exports
          // targets under its own visibility.
          AliasProvider.getDependencyLabel(prerequisite.getConfiguredTarget())
              .getPackageIdentifier())) {
        return true;
      }
    }

    // Not same-package / same-logical-package. Check the actual visibility contents.
    for (PackageGroupContents specification : visibility.getVisibility().toList()) {
      if (specification.containsPackage(location)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Registers an attribute error if a {@code package_group} target is detected in a context where
   * it is not allowed.
   */
  private void checkForMisplacedPackageGroups(
      RuleContext.Builder context,
      ConfiguredTargetAndData prerequisite,
      Attribute attribute,
      String attrName,
      Rule rule) {
    // TODO(bazel-team): The instanceof check seems pretty suspect, and should maybe be phrased in
    // terms of a provider check that would work with the `alias` rule. Then again, the string
    // matching on PackageSpecification[Provider|Info] is probably more suspect.
    if (prerequisite.getConfiguredTarget().unwrapIfMerged()
        instanceof PackageGroupConfiguredTarget) {
      Attribute configuredAttribute = RawAttributeMapper.of(rule).getAttributeDefinition(attrName);
      if (configuredAttribute == null) { // handles aspects
        configuredAttribute = attribute;
      }
      String description = configuredAttribute.getRequiredProviders().getDescription();
      boolean containsPackageSpecificationProvider =
          description.contains("PackageSpecificationProvider")
              || description.contains("PackageSpecificationInfo");
      // TODO(plf): Add the PackageSpecificationProvider to the 'visibility' attribute.
      if (!attrName.equals("visibility")
          && !attrName.equals(FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME)
          && !containsPackageSpecificationProvider) {
        context.attributeError(
            attrName,
            "in "
                + attrName
                + " attribute of "
                + rule.getRuleClass()
                + " rule "
                + rule.getLabel()
                + ": "
                + AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITH_KIND)
                + " is misplaced here (they are only allowed in the visibility attribute)");
      }
    }
  }

  private void handleVisibilityConflict(
      RuleContext.Builder context, ConfiguredTargetAndData prerequisite, Label rule) {
    if (packageUnderExperimental(rule.getPackageIdentifier())
        && !checkVisibilityForExperimental(context)) {
      return;
    }

    if (!context.getConfiguration().checkVisibility()) {
      String errorMessage =
          String.format(
              "Target '%s' violates visibility of "
                  + "%s. Continuing because --nocheck_visibility is active",
              rule, AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITHOUT_KIND));
      context.ruleWarning(errorMessage);
    } else {
      // Visibility error:
      //   target '//land:land' is not visible from
      //   target '//red_delicious:apple'
      // Recommendation: ...
      String errorMessage =
          String.format(
              "Visibility error:\n"
                  + "%s is not visible from\n"
                  + "target '%s'\n"
                  + "Recommendation: modify the visibility declaration if you think the dependency"
                  + " is legitimate. For more info see https://bazel.build/concepts/visibility",
              AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITHOUT_KIND), rule);

      if (prerequisite.getTargetKind().equals(InputFile.targetKind())) {
        errorMessage +=
            ". To set the visibility of that source file target, use the exports_files() function";
      }
      context.ruleError(errorMessage);
    }
  }

  private void validateDirectPrerequisiteLocation(
      RuleContext.Builder context, ConfiguredTargetAndData prerequisite) {
    Rule rule = context.getRule();
    Label prerequisiteLabel = prerequisite.getTargetLabel();

    if (packageUnderExperimental(prerequisiteLabel.getPackageIdentifier())
        && !packageUnderExperimental(rule.getLabel().getPackageIdentifier())) {
      String message =
          "non-experimental target '"
              + rule.getLabel()
              + "' depends on experimental target '"
              + prerequisiteLabel
              + "'";
      if (allowExperimentalDeps(context)) {
        context.ruleWarning(
            message + " (ignored due to --experimental_deps_ok;" + " do not submit)");
      } else {
        context.ruleError(
            message
                + " (you may not check in such a dependency,"
                + " though you can test "
                + "against it by passing --experimental_deps_ok)");
      }
    }
  }

  /** Checks if the given prerequisite is deprecated and prints a warning if so. */
  private void validateDirectPrerequisiteForDeprecation(
      RuleErrorConsumer errors,
      Rule rule,
      ConfiguredTargetAndData prerequisite,
      boolean forAspect) {
    if (forAspect || attributeOrNull(rule, "deprecation", Type.STRING) != null) {
      // No warning for aspects because the base target would already have the warning.
      // No warning if the current target is already deprecated.
      return;
    }

    String warning = prerequisite.getDeprecationWarning();
    if (warning == null) {
      return; // No warning if it's not deprecated.
    }

    PackageIdentifier thisPackage = rule.getLabel().getPackageIdentifier();
    Label prerequisiteLabel = prerequisite.getTargetLabel();
    PackageIdentifier thatPackage = prerequisiteLabel.getPackageIdentifier();
    // TODO: #19922 - What to do about this check, when one or both targets are in a macro?
    if (isSameLogicalPackage(thisPackage, thatPackage)) {
      return; // Doesn't report deprecation edges within a package.
    }

    Label generatingRuleLabel = prerequisite.getGeneratingRuleLabel();
    if (generatingRuleLabel != null) {
      errors.ruleWarning(
          "target '"
              + rule.getLabel()
              + "' depends on the output file "
              + prerequisiteLabel
              + " of a deprecated rule "
              + generatingRuleLabel
              + "': "
              + warning);
    } else {
      errors.ruleWarning(
          "target '"
              + rule.getLabel()
              + "' depends on deprecated target '"
              + prerequisiteLabel
              + "': "
              + warning);
    }
  }

  /** Check that the dependency is not test-only, or the current rule is test-only. */
  private void validateDirectPrerequisiteForTestOnly(
      RuleContext.Builder context, ConfiguredTargetAndData prerequisite) {
    Rule rule = context.getRule();

    if (rule.getRuleClassObject().getAdvertisedProviders().canHaveAnyProvider()) {
      // testonly-ness will be checked directly between the depender and the target of the alias;
      // getTarget() called by the depender will not return the alias rule, but its actual target
      return;
    }
    if (!prerequisite.isTestOnly() || isTestOnlyRule(rule)) {
      return;
    }

    String message;
    Label generatingRuleLabel = prerequisite.getGeneratingRuleLabel();
    if (generatingRuleLabel == null) {
      message =
          "non-test target '"
              + rule.getLabel()
              + "' depends on testonly "
              + AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITHOUT_KIND)
              + " and doesn't have testonly attribute set";
    } else if (context.getConfiguration().checkTestonlyForOutputFiles()) {
      message =
          "non-test target '"
              + rule.getLabel()
              + "' depends on the output file "
              + AliasProvider.describeTargetWithAliases(prerequisite, TargetMode.WITHOUT_KIND)
              + " of a testonly rule "
              + generatingRuleLabel
              + " and doesn't have testonly attribute set";
    } else {
      return;
    }

    PackageIdentifier thisPackage = rule.getLabel().getPackageIdentifier();
    if (packageUnderExperimental(thisPackage)) {
      context.ruleWarning(message);
    } else {
      context.ruleError(message);
    }
  }

  private static boolean isTestOnlyRule(Rule rule) {
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    return mapper.has("testonly", Type.BOOLEAN) && mapper.get("testonly", Type.BOOLEAN);
  }
}
