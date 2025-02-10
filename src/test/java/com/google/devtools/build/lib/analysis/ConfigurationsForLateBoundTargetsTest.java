// Copyright 2017 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests <target, sourceConfig> -> <dep, depConfig> relationships over latebound attributes.
 *
 * <p>Ideally these tests would be in {@link
 * com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest}. But that's a Skyframe test
 * (ConfiguredTargetFunction is a Skyframe function). And the Skyframe library doesn't know anything
 * about latebound attributes. So we need to place these properly under the analysis package.
 */
@RunWith(JUnit4.class)
public class ConfigurationsForLateBoundTargetsTest extends AnalysisTestCase {
  private static final TransitionFactory<AttributeTransitionData>
      CHANGE_FOO_FLAG_TRANSITION_FACTORY =
          new TransitionFactory<>() {
            @Override
            public ConfigurationTransition create(AttributeTransitionData unused) {
              return new PatchTransition() {
                @Override
                public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
                  return ImmutableSet.of(LateBoundSplitUtil.TestOptions.class);
                }

                @Override
                public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
                  BuildOptionsView toOptions = options.clone();
                  toOptions.get(LateBoundSplitUtil.TestOptions.class).fooFlag = "PATCHED!";
                  return toOptions.underlying();
                }
              };
            }

            @Override
            public TransitionType transitionType() {
              return TransitionType.ATTRIBUTE;
            }
          };

  /** Rule definition with a latebound dependency. */
  private static final RuleDefinition LATE_BOUND_DEP_RULE =
      (MockRule)
          () ->
              MockRule.define(
                  "rule_with_latebound_attr",
                  (builder, env) ->
                      builder
                          .add(
                              attr(":latebound_attr", LABEL)
                                  .value(
                                      Attribute.LateBoundDefault.fromConstantForTesting(
                                          Label.parseCanonicalUnchecked("//foo:latebound_dep")))
                                  .cfg(CHANGE_FOO_FLAG_TRANSITION_FACTORY))
                          .requiresConfigurationFragments(LateBoundSplitUtil.TestFragment.class));

  @Before
  public void setupCustomLateBoundRules() throws Exception {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addRuleDefinition(LateBoundSplitUtil.RULE_WITH_TEST_FRAGMENT);
    builder.addConfigurationFragment(LateBoundSplitUtil.TestFragment.class);
    builder.addRuleDefinition(LATE_BOUND_DEP_RULE);
    useRuleClassProvider(builder.build());
  }

  @Test
  public void lateBoundAttributeInTargetConfiguration() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        rule_with_latebound_attr(
            name = "foo",
        )

        rule_with_test_fragment(
            name = "latebound_dep",
        )
        """);
    update("//foo:foo");
    assertThat(getConfiguredTarget("//foo:foo", getTargetConfiguration())).isNotNull();
    ConfiguredTarget dep =
        Iterables.getOnlyElement(
            SkyframeExecutorTestUtils.getExistingConfiguredTargets(
                skyframeExecutor, Label.parseCanonical("//foo:latebound_dep")));
    assertThat(getConfiguration(dep)).isNotEqualTo(getTargetConfiguration());
    assertThat(LateBoundSplitUtil.getOptions(getConfiguration(dep)).fooFlag).isEqualTo("PATCHED!");
  }

  @Test
  public void lateBoundAttributeInExecConfiguration() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.out"],
            cmd = "echo hi > $@",
            tools = [":foo"],
        )

        rule_with_latebound_attr(
            name = "foo",
        )

        rule_with_test_fragment(
            name = "latebound_dep",
        )
        """);
    update("//foo:gen");
    assertThat(getConfiguredTarget("//foo:foo", getExecConfiguration())).isNotNull();
    // TODO(b/203203933) Fix LateboundDefault-s to return exec configuration
    ImmutableList<ConfiguredTarget> deps =
        ImmutableList.copyOf(
            SkyframeExecutorTestUtils.getExistingConfiguredTargets(
                skyframeExecutor, Label.parseCanonical("//foo:latebound_dep")));
    assertThat(deps).hasSize(1);
    assertThat(deps.stream().allMatch(d -> getConfiguration(d).isExecConfiguration())).isTrue();
  }
}
