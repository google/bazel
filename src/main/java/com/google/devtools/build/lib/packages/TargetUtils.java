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

package com.google.devtools.build.lib.packages;

import static com.google.devtools.build.lib.packages.BuildType.TRISTATE;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.syntax.Location;

/**
 * Utility functions over Targets that don't really belong in the base {@link
 * Target} interface.
 */
public final class TargetUtils {

  // *_test / test_suite attribute that used to specify constraint keywords.
  private static final String CONSTRAINTS_ATTR = "tags";

  // We don't want to pollute the execution info with random things, and we also need to reserve
  // some internal tags that we don't allow to be set on targets. We also don't want to
  // exhaustively enumerate all the legal values here. Right now, only a ~small set of tags is
  // recognized by Bazel.
  private static boolean legalExecInfoKeys(String tag) {
    return tag.startsWith("block-")
        || tag.startsWith("requires-")
        || tag.startsWith("no-")
        || tag.startsWith("supports-")
        || tag.startsWith("disable-")
        || tag.startsWith("cpu:")
        || tag.equals(ExecutionRequirements.LOCAL)
        || tag.equals(ExecutionRequirements.WORKER_KEY_MNEMONIC)
        || tag.startsWith("resources:");
  }

  private TargetUtils() {} // Uninstantiable.

  public static boolean isTestRuleName(String name) {
    return name.endsWith("_test");
  }

  public static boolean isTestSuiteRuleName(String name) {
    return name.equals("test_suite");
  }

  /**
   * Returns true iff {@code target} is a {@code *_test} rule; excludes {@code
   * test_suite}.
   */
  public static boolean isTestRule(Target target) {
    return (target instanceof Rule) && isTestRuleName(((Rule) target).getRuleClass());
  }

  /**
   * Returns true iff {@code target} is a {@code test_suite} rule.
   */
  public static boolean isTestSuiteRule(Target target) {
    return target instanceof Rule && isTestSuiteRuleName(((Rule) target).getRuleClass());
  }

  /**
   * Returns true iff {@code target} is a {@code *_test} or {@code test_suite}.
   */
  public static boolean isTestOrTestSuiteRule(Target target) {
    return isTestRule (target) || isTestSuiteRule(target);
  }

  /**
   * Returns true if {@code target} has "manual" in the tags attribute and thus should be ignored by
   * command-line wildcards or by test_suite $implicit_tests attribute.
   */
  public static boolean hasManualTag(Target target) {
    return (target instanceof Rule) && hasConstraint((Rule) target, "manual");
  }

  /**
   * Returns true if test marked as "exclusive" by the appropriate keyword
   * in the tags attribute.
   *
   * Method assumes that passed target is a test rule, so usually it should be
   * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
   * undefined otherwise.
   */
  public static boolean isExclusiveTestRule(Rule rule) {
    return hasConstraint(rule, "exclusive");
  }

  /**
   * Returns true if test marked as "exclusive-if-local" by the appropriate keyword in the tags
   * attribute.
   *
   * <p>Method assumes that passed target is a test rule, so usually it should be used only after
   * isTestRule() or isTestOrTestSuiteRule(). Behavior is undefined otherwise.
   */
  public static boolean isExclusiveIfLocalTestRule(Rule rule) {
    return hasConstraint(rule, "exclusive-if-local");
  }
  /**
   * Returns true if test marked as "local" by the appropriate keyword
   * in the tags attribute.
   *
   * Method assumes that passed target is a test rule, so usually it should be
   * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
   * undefined otherwise.
   */
  public static boolean isLocalTestRule(Rule rule) {
    return hasConstraint(rule, "local")
        || NonconfigurableAttributeMapper.of(rule).get("local", Type.BOOLEAN);
  }

  /**
   * Returns true if test marked as "external" by the appropriate keyword
   * in the tags attribute.
   *
   * Method assumes that passed target is a test rule, so usually it should be
   * used only after isTestRule() or isTestOrTestSuiteRule(). Behavior is
   * undefined otherwise.
   */
  public static boolean isExternalTestRule(Rule rule) {
    return hasConstraint(rule, "external");
  }

  /**
   * Returns true if test marked as "no-testloasd" by the appropriate keyword in the tags attribute.
   *
   * <p>Method assumes that passed target is a test rule, so usually it should be used only after
   * isTestRule() or isTestOrTestSuiteRule(). Behavior is undefined otherwise.
   */
  public static boolean isNoTestloasdTestRule(Rule rule) {
    return hasConstraint(rule, "no-testloasd");
  }

  public static List<String> getStringListAttr(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    return NonconfigurableAttributeMapper.of((Rule) target).get(attrName, Types.STRING_LIST);
  }

  public static String getStringAttr(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    return NonconfigurableAttributeMapper.of((Rule) target).get(attrName, Type.STRING);
  }

  public static Iterable<String> getAttrAsString(Target target, String attrName) {
    Preconditions.checkArgument(target instanceof Rule);
    List<String> values = new ArrayList<>(); // May hold null values.
    Attribute attribute = ((Rule) target).getAttributeDefinition(attrName);
    if (attribute != null) {
      Type<?> attributeType = attribute.getType();
      for (Object attrValue :
          AggregatingAttributeMapper.of((Rule) target)
              .visitAttribute(attribute.getName(), attributeType)) {

        values.add(convertAttributeValue(attributeType, attrValue));
      }
    }
    return values;
  }

  @Nullable
  public static String convertAttributeValue(Type<?> attributeType, Object attrValue) {
    // Ugly hack to maintain backward 'attr' query compatibility for BOOLEAN and TRISTATE
    // attributes. These are internally stored as actual Boolean or TriState objects but were
    // historically queried as integers. To maintain compatibility, we inspect their actual
    // value and return the integer equivalent represented as a String. This code is the
    // opposite of the code in BooleanType and TriStateType respectively.
    if (attributeType == BOOLEAN) {
      return Type.BOOLEAN.cast(attrValue) ? "1" : "0";
    } else if (attributeType == TRISTATE) {
      return switch (BuildType.TRISTATE.cast(attrValue)) {
        case AUTO -> "-1";
        case NO -> "0";
        case YES -> "1";
      };
    } else {
      return attrValue == null ? null : attrValue.toString();
    }
  }

  /**
   * If the given target is a rule, returns its <code>deprecation<code/> value, or null if unset.
   */
  @Nullable
  public static String getDeprecation(Target target) {
    if (!(target instanceof Rule)) {
      return null;
    }
    Rule rule = (Rule) target;
    return rule.isAttrDefined("deprecation", Type.STRING)
        ? NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING)
        : null;
  }

  /**
   * Checks whether specified constraint keyword is present in the
   * tags attribute of the test or test suite rule.
   *
   * Method assumes that provided rule is a test or a test suite. Behavior is
   * undefined otherwise.
   */
  private static boolean hasConstraint(Rule rule, String keyword) {
    return NonconfigurableAttributeMapper.of(rule)
        .get(CONSTRAINTS_ATTR, Types.STRING_LIST)
        .contains(keyword);
  }

  /**
   * Returns the execution info from the tags declared on the target. These include only some tags
   * {@link #legalExecInfoKeys} as keys with empty values.
   */
  public static Map<String, String> getExecutionInfo(Rule rule) {
    // tags may contain duplicate values.
    Map<String, String> map = new HashMap<>();
    for (String tag :
        NonconfigurableAttributeMapper.of(rule).get(CONSTRAINTS_ATTR, Types.STRING_LIST)) {
      if (legalExecInfoKeys(tag)) {
        map.put(tag, "");
      }
    }
    return ImmutableMap.copyOf(map);
  }

  /**
   * Returns the execution info from the tags declared on the target. These include only some tags
   * {@link #legalExecInfoKeys} as keys with empty values.
   *
   * @param rule a rule instance to get tags from
   * @param allowTagsPropagation if set to true, tags will be propagated from a target to the
   *     actions' execution requirements, for more details {@see
   *     BuildLanguageOptions#experimentalAllowTagsPropagation}
   */
  public static ImmutableMap<String, String> getExecutionInfo(
      Rule rule, boolean allowTagsPropagation) {
    if (allowTagsPropagation) {
      return ImmutableMap.copyOf(getExecutionInfo(rule));
    } else {
      return ImmutableMap.of();
    }
  }

  /**
   * Returns the execution info, obtained from the rule's tags and the execution requirements
   * provided. Only supported tags are included into the execution info, see {@link
   * #legalExecInfoKeys}.
   *
   * @param executionRequirementsUnchecked execution_requirements of a rule, expected to be of a
   *     {@code Dict<String, String>} type, null or Starlark None.
   * @param rule a rule instance to get tags from
   * @param allowTagsPropagation if set to true, tags will be propagated from a target to the
   *     actions' execution requirements, for more details {@see
   *     StarlarkSematicOptions#experimentalAllowTagsPropagation}
   */
  public static ImmutableSortedMap<String, String> getFilteredExecutionInfo(
      @Nullable Object executionRequirementsUnchecked, Rule rule, boolean allowTagsPropagation)
      throws EvalException {
    Map<String, String> executionInfo =
        executionRequirementsUnchecked == null
            ? ImmutableMap.of()
            : TargetUtils.filter(
                Dict.noneableCast(
                    executionRequirementsUnchecked,
                    String.class,
                    String.class,
                    "execution_requirements"));

    if (allowTagsPropagation) {
      executionInfo = new HashMap<>(executionInfo); // Make mutable.
      Map<String, String> checkedTags = getExecutionInfo(rule);
      // merging filtered tags to the execution info map avoiding duplicates
      checkedTags.forEach(executionInfo::putIfAbsent);
    }

    return ImmutableSortedMap.copyOf(executionInfo);
  }

  /**
   * Returns the execution info. These include execution requirement tags ('block-*', 'requires-*',
   * 'no-*', 'supports-*', 'disable-*', 'local', and 'cpu:*') as keys with empty values.
   */
  private static Map<String, String> filter(Map<String, String> executionInfo) {
    return Maps.filterKeys(executionInfo, TargetUtils::legalExecInfoKeys);
  }

  /**
   * Returns the language part of the rule name (e.g. "foo" for foo_test or foo_binary).
   *
   * <p>In practice this is the part before the "_", if any, otherwise the entire rule class name.
   *
   * <p>Precondition: isTestRule(target) || isRunnableNonTestRule(target).
   */
  public static String getRuleLanguage(Target target) {
    return getRuleLanguage(((Rule) target).getRuleClass());
  }

  /**
   * Returns the language part of the rule name (e.g. "foo" for foo_test or foo_binary).
   *
   * <p>In practice this is the part before the "_", if any, otherwise the entire rule class name.
   */
  public static String getRuleLanguage(String ruleClass) {
    int index = ruleClass.lastIndexOf('_');
    // Chop off "_binary" or "_test".
    return index != -1 ? ruleClass.substring(0, index) : ruleClass;
  }

  private static boolean isExplicitDependency(Rule rule, Label label) {
    if (Iterables.contains(rule.getVisibilityDependencyLabels(), label)) {
      return true;
    }

    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    try {
      mapper.visitLabels(
          DependencyFilter.NO_IMPLICIT_DEPS,
          (Label depLabel, Attribute attribute) -> {
            if (label.equals(depLabel)) {
              throw StopIteration.INSTANCE;
            }
          });
    } catch (StopIteration e) {
      return true;
    }
    return false;
  }

  private static final class StopIteration extends RuntimeException {
    private static final StopIteration INSTANCE = new StopIteration();
  }

  /**
   * Returns a predicate to be used for test tag filtering, i.e., that only accepts tests that match
   * all of the required tags and none of the excluded tags.
   */
  public static Predicate<Target> tagFilter(List<String> tagFilterList) {
    Pair<Collection<String>, Collection<String>> tagLists =
        TestTargetUtils.sortTagsBySense(tagFilterList);
    final Collection<String> requiredTags = tagLists.first;
    final Collection<String> excludedTags = tagLists.second;
    return input -> {
      if (requiredTags.isEmpty() && excludedTags.isEmpty()) {
        return true;
      }

      if (!(input instanceof Rule)) {
        return requiredTags.isEmpty();
      }
      // Note that test_tags are those originating from the XX_test rule, whereas the requiredTags
      // and excludedTags originate from the command line or test_suite rule.
      // TODO(ulfjack): getRuleTags is inconsistent with TestFunction and other places that use
      // tags + size, but consistent with TestSuite.
      return TestTargetUtils.testMatchesFilters(
          ((Rule) input).getRuleTags(), requiredTags, excludedTags, false);
    };
  }

  /** Return {@link Location} for {@link Target} target, if it should not be null. */
  @Nullable
  public static Location getLocationMaybe(Target target) {
    return (target instanceof Rule) || (target instanceof InputFile) ? target.getLocation() : null;
  }

  /**
   * Return nicely formatted error message that {@link Label} label that was pointed to by {@link
   * Target} target did not exist, due to {@link NoSuchThingException} e.
   */
  public static String formatMissingEdge(
      @Nullable Target target, Label label, NoSuchThingException e, @Nullable Attribute attr) {
    // instanceof returns false if target is null (which is exploited here)
    if (target instanceof Rule rule) {
      if (isExplicitDependency(rule, label)) {
        return String.format("%s and referenced by '%s'", e.getMessage(), target.getLabel());
      } else {
        String additionalInfo = "";
        if (attr != null && !Strings.isNullOrEmpty(attr.getDoc())) {
          additionalInfo =
              String.format(
                  "\nDocumentation for implicit attribute %s of rules of type %s:\n%s",
                  attr.getPublicName(), rule.getRuleClass(), attr.getDoc());
        }
        // N.B. If you see this error message in one of our integration tests during development of
        // a change that adds a new implicit dependency when running Blaze, maybe you forgot to add
        // a new mock target to the integration test's setup.
        return String.format(
            "every rule of type %s implicitly depends upon the target '%s', but "
                + "this target could not be found because of: %s%s",
            rule.getRuleClass(), label, e.getMessage(), additionalInfo);
      }
    } else if (target instanceof InputFile) {
      return e.getMessage() + " (this is usually caused by a missing package group in the"
          + " package-level visibility declaration)";
    } else {
      if (target != null) {
        return String.format("in target '%s', no such label '%s': %s", target.getLabel(), label,
            e.getMessage());
      }
      return e.getMessage();
    }
  }

  public static String formatMissingEdge(
      @Nullable Target target, Label label, NoSuchThingException e) {
    return formatMissingEdge(target, label, e, null);
  }
}
