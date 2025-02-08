// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test;

import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.common.options.Options;

/**
 * Trimming transition factory which removes the test config fragment and certain options that are
 * only relevant for tests when entering a non-test rule.
 */
public final class TestTrimmingTransitionFactory implements TransitionFactory<RuleTransitionData> {

  private static final ImmutableSet<String> TEST_OPTIONS =
      ImmutableSet.copyOf(Options.getDefaults(TestOptions.class).asMap().keySet());

  private static final Label TRANSITIVE_CONFIG_TO_TRIGGER_SKIP =
      Label.parseCanonicalUnchecked("//command_line_option/fragment:test");

  /**
   * Trimming transition which removes the test config fragment if --trim_test_configuration is on.
   *
   * <p>At the moment, need to know the value of the testonly attribute from the underlying rule. So
   * the factory, which has access to attributes but not the configuration, attaches the appropriate
   * TestTrimmingTransition, which will have access to the configuration.
   */
  public static class TestTrimmingTransition implements PatchTransition {
    // These are essentially a cache of the two versions of the transition depending on if
    // the associated rule is testonly = true or not.
    private static final TestTrimmingTransition TESTONLY_TRUE = new TestTrimmingTransition(true);
    private static final TestTrimmingTransition TESTONLY_FALSE = new TestTrimmingTransition(false);
    @VisibleForTesting public static final TestTrimmingTransition INSTANCE = TESTONLY_FALSE;

    private final boolean testonly;

    private TestTrimmingTransition(boolean testonly) {
      this.testonly = testonly;
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
      return TestTrimmingLogic.REQUIRED_FRAGMENTS;
    }

    @Override
    public BuildOptions patch(BuildOptionsView originalOptions, EventHandler eventHandler)
        throws InterruptedException {
      var originalTestOptions = originalOptions.get(TestOptions.class);
      if (originalTestOptions == null) {
        // nothing to do, already trimmed this fragment
        return originalOptions.underlying();
      }
      if (!originalTestOptions.trimTestConfiguration
          || (originalTestOptions.experimentalRetainTestConfigurationAcrossTestonly && testonly)) {
        // nothing to do, trimming is disabled
        return originalOptions.underlying();
      }
      // No context needed, use the constant Boolean.TRUE.
      return TestTrimmingLogic.trim(originalOptions);
    }
  }

  @Override
  public PatchTransition create(RuleTransitionData ruleData) {
    RuleClass ruleClass = ruleData.rule().getRuleClassObject();
    if (ruleClass
            .getConfigurationFragmentPolicy()
            .isLegalConfigurationFragment(TestConfiguration.class)
        || AliasProvider.mayBeAlias(ruleData.rule())) {
      // If Test rule, no need to trim here.
      // If Alias rule, might point to test rule so don't trim yet.
      return NoTransition.INSTANCE;
    }

    // TODO(blaze-configurability-team): Needing special logic for config_setting implies
    //   getConfigurationFragmentPolicy is not accurate for config_setting, which is bad.
    // That said, config_setting on test options should be banned regardless of what rule type
    // consumes them.
    for (String referencedOptions : ruleClass.getOptionReferenceFunction().apply(ruleData.rule())) {
      if (TEST_OPTIONS.contains(referencedOptions)) {
        // Test-option-referencing config_setting; no need to trim here.
        return NoTransition.INSTANCE;
      }
    }

    // Non-test rule. Trim it!
    // Use an attribute mapper to ensure attributes are resolved to expected types
    // these attributes are defined in BaseRuleClasses
    NonconfigurableAttributeMapper attrs = NonconfigurableAttributeMapper.of(ruleData.rule());

    // Skip trimming when transitive_configs has magic value.
    if (attrs.has(BaseRuleClasses.TAGGED_TRIMMING_ATTR, NODEP_LABEL_LIST)) {
      for (Label entry : attrs.get(BaseRuleClasses.TAGGED_TRIMMING_ATTR, NODEP_LABEL_LIST)) {
        if (entry.equals(TRANSITIVE_CONFIG_TO_TRIGGER_SKIP)) {
          return NoTransition.INSTANCE;
        }
      }
    }

    // Only skip testonly = true when --experimental_retain_test_configuration_across_testonly
    //   so have to defer decision until actually have a config.
    if (attrs.has("testonly", BOOLEAN) && attrs.get("testonly", BOOLEAN)) {
      return TestTrimmingTransition.TESTONLY_TRUE;
    }
    return TestTrimmingTransition.TESTONLY_FALSE;
  }

  @Override
  public TransitionType transitionType() {
    return TransitionType.RULE;
  }
}
