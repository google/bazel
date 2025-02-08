// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.FileTypeSet;
import javax.annotation.Nullable;

/**
 * Helper functions for visiting the {@link Label}s of the loading-phase deps of a {@link Target}
 * that are entailed by the values of the {@link Target}'s attributes. Notably, this does *not*
 * include aspect-entailed deps.
 */
public final class LabelVisitationUtils {

  // An attribute which symbolizes the "toolchains" parameter of rule class definitions
  // (user-specified via the `toolchains` parameter of the starlark rule() function). This is so
  // that labels specified in this `toolchains` parameter may be treated the same as dependencies
  // defined on an implicit rule attribute. This "fake" attribute uses an obscure placeholder name
  // to prevent dependencies on this implementation detail.
  private static final Attribute TOOLCHAIN_TYPE_ATTR_FOR_FILTERING =
      Attribute.attr("_hidden_toolchain_types", BuildType.LABEL_LIST)
          .allowedFileTypes(FileTypeSet.NO_FILE)
          .build();

  private LabelVisitationUtils() {}

  /** Interface for processing the {@link Label} of dep, one at a time. */
  public interface LabelProcessor {
    /**
     * Processes the {@link Label} of a single dep.
     *
     * @param from the {@link Target} that has the dep.
     * @param attribute if non-{@code null}, the {@link Attribute} whose value entailed the dep.
     * @param to the {@link Label} of the dep.
     */
    void process(Target from, @Nullable Attribute attribute, Label to);
  }

  /**
   * Visits the loading-phase deps of {@code target} that satisfy {@code edgeFilter}, feeding each
   * one to {@code labelProcessor} in a streaming manner.
   */
  public static void visitTarget(
      Target target, DependencyFilter edgeFilter, LabelProcessor labelProcessor) {
    if (target instanceof OutputFile) {
      labelProcessor.process(
          target, /*attribute=*/ null, ((OutputFile) target).getGeneratingRule().getLabel());
      visitTargetVisibility(target, /*attribute=*/ null, labelProcessor);
      return;
    }

    if (target instanceof InputFile) {
      visitTargetVisibility(target, /*attribute=*/ null, labelProcessor);
      return;
    }

    if (target instanceof Rule rule) {
      visitRuleVisibility(rule, edgeFilter, labelProcessor);
      visitRule(rule, edgeFilter, labelProcessor);
      visitRuleToolchains(rule, edgeFilter, labelProcessor);
      return;
    }

    if (target instanceof PackageGroup) {
      visitPackageGroup((PackageGroup) target, labelProcessor);
    }
  }

  private static void visitTargetVisibility(
      Target target, @Nullable Attribute attribute, LabelProcessor labelProcessor) {
    for (Label label : target.getVisibilityDependencyLabels()) {
      labelProcessor.process(target, attribute, label);
    }
  }

  private static void visitRuleVisibility(
      Rule rule, DependencyFilter edgeFilter, LabelProcessor labelProcessor) {
    RuleClass ruleClass = rule.getRuleClassObject();
    Integer index = ruleClass.getAttributeIndex("visibility");
    if (index == null) {
      return;
    }
    Attribute visibilityAttribute = ruleClass.getAttribute(index);
    if (visibilityAttribute.getType() != BuildType.NODEP_LABEL_LIST) {
      return;
    }
    if (edgeFilter.test(rule, visibilityAttribute)) {
      visitTargetVisibility(rule, visibilityAttribute, labelProcessor);
    }
  }

  private static void visitRuleToolchains(
      Rule rule, DependencyFilter edgeFilter, LabelProcessor labelProcessor) {
    RuleClass ruleClass = rule.getRuleClassObject();
    if (edgeFilter.test(rule, TOOLCHAIN_TYPE_ATTR_FOR_FILTERING)) {
      for (ToolchainTypeRequirement t : ruleClass.getToolchainTypes()) {
        labelProcessor.process(rule, TOOLCHAIN_TYPE_ATTR_FOR_FILTERING, t.toolchainType());
      }
    }
  }

  private static void visitRule(
      Rule rule, DependencyFilter edgeFilter, LabelProcessor labelProcessor) {
    AggregatingAttributeMapper.of(rule)
        .visitLabels(
            edgeFilter,
            (Label label, Attribute attribute) -> {
              if (label == null) {
                return;
              }
              labelProcessor.process(rule, attribute, label);
            });
  }

  private static void visitPackageGroup(PackageGroup packageGroup, LabelProcessor labelProcessor) {
    for (Label label : packageGroup.getIncludes()) {
      labelProcessor.process(packageGroup, /*attribute=*/ null, label);
    }
  }
}
