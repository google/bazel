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
package com.google.devtools.build.lib.pkgcache;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Objects;

/**
 * Utility class for predefined filtering policies.
 */
public final class FilteringPolicies {
  public static final FilteringPolicy NO_FILTER = new NoFilter();
  public static final FilteringPolicy FILTER_MANUAL = new FilterManual();
  public static final FilteringPolicy FILTER_TESTS = new FilterTests();
  public static final FilteringPolicy RULES_ONLY = new RulesOnly();

  /** Returns the result of applying y, if target passes x. */
  public static FilteringPolicy and(final FilteringPolicy x, final FilteringPolicy y) {
    if (x.equals(NO_FILTER)) {
      return y;
    }
    if (y.equals(NO_FILTER)) {
      return x;
    }
    return new AndFilteringPolicy(x, y);
  }

  public static FilteringPolicy ruleTypeExplicit(String ruleName) {
    return RuleTypeFilter.create(ruleName, /*keepExplicit=*/ true);
  }

  private FilteringPolicies() {
  }

  /** Base class for singleton filtering policies. */
  private abstract static class AbstractFilteringPolicy implements FilteringPolicy {
    private final int hashCode = getClass().getSimpleName().hashCode();

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (obj == this) {
        return true;
      }
      return getClass().equals(obj.getClass());
    }
  }

  private static class NoFilter extends AbstractFilteringPolicy {
    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      return true;
    }

    @Override
    public String toString() {
      return "[]";
    }
  }

  private static class FilterManual extends AbstractFilteringPolicy {
    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      return explicit || !TargetUtils.hasManualTag(target);
    }
  }

  private static class FilterTests extends AbstractFilteringPolicy {
    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      return TargetUtils.isTestOrTestSuiteRule(target)
          && FILTER_MANUAL.shouldRetain(target, explicit);
    }
  }

  private static class RulesOnly extends AbstractFilteringPolicy {
    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      // With the sibling repository layout in effect, TargetPatternFunction tries to recurse into
      // the special //external package even when there are no build rules. Prevent it by excluding
      // WORKSPACE-only rules in addition to checking if target is a Rule object.
      return target instanceof Rule && !((Rule) target).getRuleClassObject().getWorkspaceOnly();
    }
  }

  /** FilteringPolicy that only matches a specific rule name. */
  @AutoCodec
  record RuleTypeFilter(String ruleName, boolean keepExplicit) implements FilteringPolicy {
    RuleTypeFilter {
      requireNonNull(ruleName, "ruleName");
    }

    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      if (explicit && keepExplicit()) {
        return true;
      }

      if (target.getAssociatedRule().getRuleClass().equals(ruleName())) {
        return true;
      }

      return false;
    }

    private static RuleTypeFilter create(String ruleName, boolean keepExplicit) {
      return new RuleTypeFilter(ruleName, keepExplicit);
    }
  }

  /** FilteringPolicy for combining FilteringPolicies. */
  public static class AndFilteringPolicy implements FilteringPolicy {
    private final FilteringPolicy firstPolicy;
    private final FilteringPolicy secondPolicy;

    private AndFilteringPolicy(FilteringPolicy firstPolicy, FilteringPolicy secondPolicy) {
      this.firstPolicy = Preconditions.checkNotNull(firstPolicy);
      this.secondPolicy = Preconditions.checkNotNull(secondPolicy);
    }

    @Override
    public boolean shouldRetain(Target target, boolean explicit) {
      return firstPolicy.shouldRetain(target, explicit)
          && secondPolicy.shouldRetain(target, explicit);
    }

    public FilteringPolicy getFirstPolicy() {
      return firstPolicy;
    }

    public FilteringPolicy getSecondPolicy() {
      return secondPolicy;
    }

    @Override
    public int hashCode() {
      return Objects.hash(firstPolicy, secondPolicy);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof AndFilteringPolicy other)) {
        return false;
      }
      return other.firstPolicy.equals(firstPolicy) && other.secondPolicy.equals(secondPolicy);
    }

    @Override
    public String toString() {
      return String.format("and_filter(%s, %s)", firstPolicy, secondPolicy);
    }
  }
}
