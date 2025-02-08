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

package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.skyframe.TransitiveTargetKey;
import com.google.devtools.build.lib.skyframe.TransitiveTargetValue;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests that late bound label declarations obey the invariant that the computed label is in the
 * transitive closure of the default label.
 */
@RunWith(JUnit4.class)
public class LateBoundAttributeTest extends BuildViewTestCase {

  /**
   * These attributes are only an exception because we can't easily test them: their default value
   * as determined by the {@link DependencyResolutionHelpers} depends on the rule, not just on the
   * rule class and configuration. The problem with the test below is that we don't instantiate
   * rules and the {@link #attributes} collection is just an empty mock.
   */
  private static final ImmutableSet<String> ATTRIBUTE_EXCEPTIONS =
      ImmutableSet.of(":computed_cc_rpc_libs");

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private AttributeMap attributes;

  @Test
  public void testInvariant() throws Exception {
    // This configuration makes java_proto_library and java_lite_proto_library's toolchain
    // attributes to have defaultValue == actualValue, which makes the test below skip them.
    // Otherwise, the test chokes because it tries to traverse the non-existent
    // tools/proto/toolchains/BUILD file.
    useConfiguration(
        "--proto_toolchain_for_java=//tools/proto/toolchains:java",
        "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite");

    new LabelChecker(getTargetConfiguration())
        .checkRuleClasses(ruleClassProvider.getRuleClassMap().values());

    new LabelChecker(getExecConfiguration())
        .checkRuleClasses(ruleClassProvider.getRuleClassMap().values());
  }

  private class LabelChecker {
    private final BuildConfigurationValue configuration;
    private boolean failed;

    LabelChecker(BuildConfigurationValue configuration) {
      this.configuration = configuration;
    }

    void checkRuleClasses(Iterable<RuleClass> ruleClasses) throws Exception {
      for (RuleClass ruleClass : ruleClasses) {
        checkRuleClass(ruleClass);
      }
      // If this fails you need to check your rule class declarations.
      assertThat(failed).isFalse();
    }

    private void checkRuleClass(RuleClass ruleClass) throws Exception {
      if (ruleClass.getName().startsWith("$")) {
        // Ignore abstract rule classes.
        return;
      }

      for (Attribute attribute : ruleClass.getAttributes()) {
        checkAttribute(ruleClass, attribute);
      }
    }

    private void checkAttribute(RuleClass ruleClass, Attribute attribute) throws Exception {
      String attributeName = attribute.getName();
      if (!Attribute.isAnalysisDependent(attributeName)) {
        return;
      }

      if (ATTRIBUTE_EXCEPTIONS.contains(attributeName)) {
        return;
      }

      if (attribute.getType() == BuildType.LABEL) {
        Label label;
        label =
            BuildType.LABEL.cast(
                DependencyResolutionHelpers.resolveLateBoundDefault(
                    null, attributes, attribute, configuration));
        if (label != null) {
          checkLabel(ruleClass, attribute, label);
        }
      } else if (attribute.getType() == BuildType.LABEL_LIST) {
        List<Label> labels;
        labels =
            BuildType.LABEL_LIST.cast(
                DependencyResolutionHelpers.resolveLateBoundDefault(
                    null, attributes, attribute, configuration));
        for (Label label : labels) {
          checkLabelList(ruleClass, attribute, label);
        }
      } else {
        throw new AssertionError("Unknown attribute: '" + attributeName + "'");
      }
    }

    /**
     * We check that the label set by the {@link DependencyResolutionHelpers} with the default
     * configuration is in the transitive closure of the default value set in the rule class.
     *
     * <p>Branches created using the result of {@code "blaze query deps(//target)"} only work if all
     * labels loaded by blaze during the loading phase are also returned by this query. The check
     * here is a bit stricter than that, and disallows omitting the label if another attribute
     * already sets the same label.
     */
    void checkLabel(RuleClass ruleClass, Attribute attribute, Label label) throws Exception {
      Label defaultValue;
      if (attribute.getDefaultValueUnchecked() instanceof LateBoundDefault<?, ?>) {
        defaultValue =
            BuildType.LABEL.cast(
                ((LateBoundDefault<?, ?>) attribute.getDefaultValueUnchecked()).getDefault(null));
      } else {
        defaultValue = (Label) attribute.getDefaultValueUnchecked();
      }
      if ((defaultValue == null) || !existsPath(defaultValue, label)) {
        System.err.println("in " + ruleClass.getName() + " attribute " + attribute.getName() + ":");
        System.err.println("  " + label + " is not in the transitive closure of " + defaultValue);
        failed = true;
      }
    }

    /**
     * Similar to {@link #checkLabel} except for we check that the label is reachable by *any*
     * value in the default value (doesn't need to be reachable by all values in the default).
     */
    @SuppressWarnings("unchecked")
    void checkLabelList(RuleClass ruleClass, Attribute attribute, Label label) throws Exception {
      List<Label> defaultValues;
      if (attribute.getDefaultValueUnchecked() instanceof LateBoundDefault<?, ?>) {
        defaultValues =
            BuildType.LABEL_LIST.cast(
                ((LateBoundDefault<?, ?>) attribute.getDefaultValueUnchecked()).getDefault(null));
      } else {
        defaultValues = (List<Label>) attribute.getDefaultValueUnchecked();
      }
      failed = true;
      if (defaultValues == null) {
        System.err.println("in " + ruleClass.getName() + " attribute " + attribute.getName() + ":");
        System.err.println(" no available default for this attribute");
      } else {
        for (Label defaultLabel : defaultValues) {
          if (existsPath(defaultLabel, label)) {
            failed = false;
          }
        }
        // label was not reachable from any label in the defaultValue
        if (failed) {
          System.out.println(
              "in " + ruleClass.getName() + " attribute " + attribute.getName() + ":");
          System.out.println(
              "  " + label + " is not in the transitive closure of "
                  + Arrays.toString(defaultValues.toArray()));
        }
      }
    }

    /**
     * Returns whether a path exists from the first given label to the second.
     */
    private boolean existsPath(Label from, Label to) throws Exception {
      return from.equals(to) || visitTransitively(from).toList().contains(to);
    }

    private NestedSet<Label> visitTransitively(Label label) throws InterruptedException {
      SkyKey key = TransitiveTargetKey.of(label);
      EvaluationContext evaluationContext =
          EvaluationContext.newBuilder().setParallelism(5).setEventHandler(reporter).build();
      EvaluationResult<SkyValue> result =
          getSkyframeExecutor().prepareAndGet(ImmutableSet.of(key), evaluationContext);
      TransitiveTargetValue value = (TransitiveTargetValue) result.get(key);
      boolean hasTransitiveError = (value == null) || value.encounteredLoadingError();
      if (result.hasError() || hasTransitiveError) {
        throw new RuntimeException(result.getError().getException());
      }
      return value.getTransitiveTargets();
    }
  }
}
