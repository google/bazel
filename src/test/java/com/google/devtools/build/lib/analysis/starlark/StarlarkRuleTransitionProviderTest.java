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
package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for StarlarkRuleTransitionProvider. */
@RunWith(TestParameterInjector.class)
public final class StarlarkRuleTransitionProviderTest extends BuildViewTestCase {

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.build();
  }

  @Test
  public void testBadReturnTypeFromTransition() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return "cpu=k8"

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    var collector = new AnalysisRootCauseCollector();
    eventBus.register(collector);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("transition function returned string, want dict or list of dicts");

    // Verifies that the AnalysisRootCauseEvent has a no associated configuration. In this case,
    // the error occurs during a transition, so no configuration has been determined.
    AnalysisRootCauseEvent rootCause = getOnlyElement(collector.rootCauses);
    assertThat(rootCause.getConfigurations()).isEmpty();
  }

  @Test
  public void testOutputOnlyTransition() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  public void testInputAndOutputTransition() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            foo = settings["//command_line_option:foo"].replace("pre", "post")
            return {
                "//command_line_option:foo": foo,
            }

        my_transition = transition(
            implementation = _impl,
            inputs = ["//command_line_option:foo"],
            outputs = ["//command_line_option:foo"],
        )
        """);

    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  public void testBuildSettingCannotTransition() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            build_setting = config.string(),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Build setting rules cannot use the `cfg` param to apply transitions to themselves");
  }

  @Test
  public void testBadCfgInput() throws Exception {
    scratch.file(
        "test/rules.bzl", "my_rule = rule(implementation = lambda ctx: [], cfg = 'my_transition')");
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "`cfg` must be set to a transition object initialized by the transition() function.");
  }

  @Test
  public void testMultipleReturnConfigs() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return [
                {"//command_line_option:foo": "split_one"},
                {"//command_line_option:foo": "split_two"},
            ]

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Rule transition only allowed to return a single transitioned configuration.");
  }

  @Test
  public void testCanDoBadStuffWithParameterizedTransitionsAndSelects() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            if (attr.my_configurable_attr):
                return {"//command_line_option:foo": "true"}
            else:
                return {"//command_line_option:foo": "false"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "my_configurable_attr": attr.bool(default = False),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(
            name = "test",
            my_configurable_attr = select({
                "//conditions:default": False,
                ":true-config": True,
            }),
        )

        config_setting(
            name = "true-config",
            values = {"foo": "true"},
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "No attribute 'my_configurable_attr'. "
            + "Either this attribute does not exist for this rule or the attribute "
            + "was not resolved because it is set by a select that reads flags the transition "
            + "may set.");
  }

  @Test
  public void testTransitionReadsInvalidConfiguredAttribute() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            if (hasattr(attr, "my_configurable_attr")):
                return {"//command_line_option:bool": "true"}
            else:
                return {"//command_line_option:bool": "false"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")
        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "my_configurable_attr": attr.bool(default = False),
            },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        # Both of these can be true at once.
        config_setting(
            name = 'dup1',
            values = {'compilation_mode': 'opt'},
        )
        config_setting(
            name = 'dup2',
            values = {'define': 'foo=bar'},
        )

        my_rule(
            name = "test",
            # This select has an error, so the transition should not see the attribute.
            my_configurable_attr = select({
                ":dup1": True,
                ":dup2": False,
                "//conditions:default": False,
            }),
        )
        """);

    useConfiguration("-c", "opt", "--define", "foo=bar");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    // The error is from rule analysis, not from the transition.
    assertContainsEvent("Illegal ambiguous match on configurable attribute");
  }

  @Test
  public void testLabelTypedAttrReturnsLabelNotDep() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            if attr.dict_attr[Label("//test:key")] == "value":
                return {"//command_line_option:foo": "post-transition"}
            else:
                return {"//command_line_option:foo": "uh-oh"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
            attrs = {
                "dict_attr": attr.label_keyed_string_dict(),
            },
        )
        simple_rule = rule(_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule", "simple_rule")

        my_rule(
            name = "test",
            dict_attr = {":key": "value"},
        )

        simple_rule(name = "key")
        """);

    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  private static final String CUTE_ANIMAL_DEFAULT =
      "cows produce more milk when they listen to soothing music";

  private void writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);

    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);

    scratch.file(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");
  }

  @Test
  public void testTransitionOnBuildSetting_fromDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins mate for life");
  }

  @Test
  public void testTransitionOnBuildSetting_fromCommandLine() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins_mate_for_life");
  }

  @Test
  public void testTransitionOnBuildSetting_badValue() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": 24}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "expected value of type 'string' for //test:cute-animal-fact, but got 24 (int)");
  }

  @Test
  public void testTransitionOnBuildSetting_noSuchTarget() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:i-am-not-real": "imaginary-friend"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:i-am-not-real"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "no such target '//test:i-am-not-real': target "
            + "'i-am-not-real' not declared in package 'test'");
  }

  @Test
  public void testTransitionOnBuildSetting_noSuchPackage() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//i-am-not-real": "imaginary-friend"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//i-am-not-real"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "no such package 'i-am-not-real': BUILD file not found in any of the following"
            + " directories");
  }

  @Test
  public void testTransitionOnBuildSetting_notABuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        non_build_setting = rule(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "non_build_setting")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        non_build_setting(name = "cute-animal-fact")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on '//test:cute-animal-fact' which is not a build setting");
  }

  @Test
  public void testTransitionOnBuildSetting_dontStoreDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _transition_impl(settings, attr):",
        "  return {'//test:cute-animal-fact': '" + CUTE_ANIMAL_DEFAULT + "'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [],",
        "  outputs = ['//test:cute-animal-fact']",
        ")");
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration("--//test:cute-animal-fact=cats_cant_taste_sugar");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().getStarlarkOptions())
        .doesNotContainKey(Label.parseCanonicalUnchecked("//test:cute-animal-fact"));
  }

  @Test
  public void testTransitionOnBuildSetting_readingUnreadableBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            old_value = settings["//command_line_option:unreadable_by_starlark"]
            fail("This line should be unreachable.")

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//command_line_option:unreadable_by_starlark"],
            outputs = ["//command_line_option:unreadable_by_starlark"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        Pattern.compile(
            "test/transitions.bzl:1:5: before calling _transition_impl: Input build setting"
                + " //command_line_option:unreadable_by_starlark is of type class"
                + " \\S*UnreadableStringBox, which is unreadable in Starlark. Please submit a"
                + " feature request."));
  }

  @Test
  public void testTransitionOnBuildSetting_writingUnreadableBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:unreadable_by_starlark": "post-transition",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:unreadable_by_starlark"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration("--unreadable_by_starlark=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).unreadableByStarlark)
        .isEqualTo(DummyTestOptions.UnreadableStringBox.create("post-transition"));
  }

  @Test
  public void testTransitionReadsBuildSetting_fromDefault() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            new_value = settings["//test:cute-animal-fact"].replace("cows", "platypuses")
            return {"//test:cute-animal-fact": new_value}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("platypuses produce more milk when they listen to soothing music");
  }

  @Test
  public void testTransitionReadsBuildSetting_fromCommandLine() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            now_true = settings["//test:cute-animal-fact"].replace("FALSE", "TRUE")
            return {"//test:cute-animal-fact": now_true}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    useConfiguration("--//test:cute-animal-fact=rats_are_ticklish_FALSE");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("rats_are_ticklish_TRUE");
  }

  @Test
  public void testTransitionReadsBuildSetting_notABuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:cute-animal-fact"],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        non_build_setting = rule(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "non_build_setting")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        non_build_setting(name = "cute-animal-fact")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on '//test:cute-animal-fact' which is not a build setting");
  }

  @Test
  public void testTransitionReadsBuildSetting_noSuchTarget() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": settings["//test:cute-animal-fact"] + " <- TRUE"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:i-am-not-real"],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "no such target '//test:i-am-not-real': target "
            + "'i-am-not-real' not declared in package 'test'");
  }

  @Test
  public void testAliasedBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'fact', actual = ':cute-animal-fact')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    useConfiguration("--//test:cute-animal-fact=rats_are_ticklish");

    ImmutableMap<Label, Object> starlarkOptions =
        getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions();
    assertThat(starlarkOptions.get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins_mate_for_life");
    assertThat(starlarkOptions).doesNotContainKey(Label.parseCanonicalUnchecked("//test:fact"));
    assertThat(starlarkOptions.keySet())
        .containsExactly(Label.parseCanonicalUnchecked("//test:cute-animal-fact"));
  }

  @Test
  public void testAliasedBuildSetting_chainedAliases() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins_mate_for_life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'fact', actual = ':alias2')",
        "alias(name = 'alias2', actual = ':cute-animal-fact')",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    useConfiguration("--//test:cute-animal-fact=rats_are_ticklish");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(
            configuration
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .isEqualTo("puffins_mate_for_life");
  }

  @Test
  public void testAliasedBuildSetting_configuredActualValue() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:fact"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(",
        "  name = 'fact',",
        "  actual = select({",
        "    '//conditions:default': ':cute-animal-fact',",
        "    ':true-config': 'other-cute-animal-fact',",
        "  })",
        ")",
        "config_setting(",
        "  name = 'true-config',",
        "  values = {'foo': 'true'},",
        ")",
        "string_flag(",
        "  name = 'cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")",
        "string_flag(",
        "  name = 'other-cute-animal-fact',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "attempting to transition on aliased build setting '//test:fact', the actual value of"
            + " which uses select().");
  }

  @Test
  public void testAliasedBuildSetting_cyclicalAliases() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:alias1": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:alias1"],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        alias(
            name = "alias1",
            actual = ":alias2",
        )

        alias(
            name = "alias2",
            actual = ":alias1",
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Dependency cycle involving '//test:alias1' detected in aliased build settings");
  }

  @Test
  public void testAliasedBuildSetting_setAliasAndActual() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//test:alias": "puffins mate for life",
                "//test:actual": "cats cannot taste sugar",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//test:alias",
                "//test:actual",
            ],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'alias', actual = ':actual')",
        "string_flag(",
        "  name = 'actual',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "Dependency cycle involving '//test:actual' detected in aliased build settings");
  }

  @Test
  public void testAliasedBuildSetting_outputReturnMismatch() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//test:actual": "cats cannot taste sugar",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//test:alias",
            ],
        )
        """);
    writeRulesBuildSettingsAndBUILDforBuildSettingTransitionTests();
    scratch.overwriteFile(
        "test/BUILD",
        "load('//test:rules.bzl', 'my_rule')",
        "load('//test:build_settings.bzl', 'string_flag')",
        "my_rule(name = 'test')",
        "alias(name = 'alias', actual = ':actual')",
        "string_flag(",
        "  name = 'actual',",
        "  build_setting_default = '" + CUTE_ANIMAL_DEFAULT + "',",
        ")");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("transition function returned undeclared output '//test:actual'");
  }

  @Test
  public void testOneParamTransitionFunctionApiFails() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("_impl() accepts no more than 1 positional argument but got 2");
  }

  @Test
  public void testCannotTransitionOnExperimentalFlag() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:experimental_something_something": True}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:experimental_something_something"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Cannot transition on --experimental_* or --incompatible_* options");
  }

  @Test
  public void testTransitionIsCheckedAgainstDefaultAllowlist() throws Exception {
    scratch.overwriteFile(
        TestConstants.TOOLS_REPOSITORY_SCRATCH
            + "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [],",
        ")");
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        def _impl(ctx):
            return []

        my_rule = rule(
            implementation = _impl,
            cfg = my_transition,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--foo=pre-transition");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Non-allowlisted use of Starlark transition");
  }

  @Test
  public void testNoNullOptionValues() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            if settings["//command_line_option:nullable_option"] == None:
                return {"//command_line_option:foo": "post-transition"}
            else:
                return {"//command_line_option:foo": settings["//command_line_option:foo"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [
                "//command_line_option:foo",
                "//command_line_option:nullable_option",
            ],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--nullable_option=", "--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  public void testAllowlistOnRuleNotTargets() throws Exception {
    // allowlists //test/...
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "neverland/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);
    scratch.file("test/BUILD");
    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration =
        getConfiguration(getConfiguredTarget("//neverland:test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  // TODO(blaze-configurability): We probably want to eventually turn this off. Flip this test when
  // this isn't allowed anymore.
  @Test
  public void testAllowlistOnTargetsStillWorks() throws Exception {
    // allowlists //test/...
    scratch.file(
        "neverland/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:foo": "post-transition"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:foo"],
        )
        """);
    scratch.file(
        "neverland/rules.bzl",
        """
        load("//neverland:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//neverland:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);
    scratch.file("neverland/BUILD");
    useConfiguration("--foo=pre-transition");

    BuildConfigurationValue configuration = getConfiguration(getConfiguredTarget("//test"));
    assertThat(configuration.getOptions().get(DummyTestOptions.class).foo)
        .isEqualTo("post-transition");
  }

  @Test
  @TestParameters({
    "{"
        + "returnLine: 'return []',"
        + "returnLine: 'return {}',"
        + "returnLine: 'return None',"
        + "returnLine: 'pass',"
        + "}"
  })
  public void noopReturnValues(String returnLine) throws Exception {
    scratch.file(
        "test/transitions.bzl",
        "def _impl(settings, attr):",
        "  " + returnLine,
        "my_transition = transition(implementation = _impl, inputs = [],",
        "  outputs = ['//command_line_option:foo'])");
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);
    // --trim_test_configuration means only the top-level configuration has TestOptions.
    assertConfigurationsEqual(
        getConfiguration(getConfiguredTarget("//test")),
        targetConfig,
        ImmutableSet.of(TestOptions.class));
  }

  @Test
  public void composingTransitionReportsAllStarlarkErrors() throws Exception {
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(implementation = _impl, build_setting = config.string(flag = True))
        """);
    scratch.file(
        "test/transitions.bzl",
        """
        def _attr_impl(settings, attr):
            return {"//test:attr_transition_output_flag1": "not default"}

        attr_transition = transition(
            implementation = _attr_impl,
            inputs = [],
            outputs = [
                "//test:attr_transition_output_flag1",
                "//test:attr_transition_output_flag2",
            ],
        )

        def _self_impl(settings, attr):
            return {"//test:self_transition_output_flag1": "not default"}

        self_transition = transition(
            implementation = _self_impl,
            inputs = [],
            outputs = [
                "//test:self_transition_output_flag1",
                "//test:self_transition_output_flag2",
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "attr_transition", "self_transition")

        def _impl(ctx):
            return []

        rule_with_attr_transition = rule(
            implementation = _impl,
            attrs = {
                "deps": attr.label_list(cfg = attr_transition),
            },
        )
        rule_with_self_transition = rule(
            implementation = _impl,
            cfg = self_transition,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "rule_with_attr_transition", "rule_with_self_transition")

        string_flag(
            name = "attr_transition_output_flag1",
            build_setting_default = "",
        )

        string_flag(
            name = "attr_transition_output_flag2",
            build_setting_default = "",
        )

        string_flag(
            name = "self_transition_output_flag1",
            build_setting_default = "",
        )

        string_flag(
            name = "self_transition_output_flag2",
            build_setting_default = "",
        )

        rule_with_attr_transition(
            name = "buildme",
            deps = [":adep"],
        )

        rule_with_self_transition(name = "adep")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:buildme");
    assertContainsEvent(
        "transition outputs [//test:attr_transition_output_flag2] were not defined by transition "
            + "function");
    // While _self_impl is in error as it does not define //test:self_transition_output_flag2,
    // evaluation stops at the faulty _attr_impl definition so it does not cause an error.
  }

  @Test
  public void testTransitionOnDefine() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:define": "chonky=true"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:define"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("Starlark transition on --define not supported - try using build settings");
  }

  @Test
  public void successfulTypeConversionOfNativeListOption() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:platforms": ["//test:my_platform"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        """);

    getConfiguredTarget("//test");
    assertNoEvents();
  }

  @Test
  public void successfulTypeConversionOfNativeListOption_unambiguousLabels() throws Exception {
    setBuildLanguageOptions("--incompatible_unambiguous_label_stringification");

    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='rules_x',version='1.0')");
    registry.addModule(createModuleKey("rules_x", "1.0"), "module(name='rules_x', version='1.0')");
    scratch.file("modules/rules_x+1.0/REPO.bazel");
    scratch.file("modules/rules_x+1.0/BUILD");
    scratch.file(
        "modules/rules_x+1.0/defs.bzl",
        """
        def _tr_impl(settings, attr):
            return {"//command_line_option:platforms": [Label("@@//test:my_platform")]}

        my_transition = transition(
            implementation = _tr_impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);

    scratch.file(
        "test/BUILD",
        """
        load("@rules_x//:defs.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        """);

    invalidatePackages();

    getConfiguredTarget("//test");
    assertNoEvents();
  }

  // Regression test for b/170729565
  @Test
  public void testSetBooleanNativeOptionWithStarlarkBoolean() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:bool": True}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:bool"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);
    useConfiguration("--bool=false");
    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(DummyTestOptions.class).bool).isTrue();
  }

  // Regression test for b/170729565
  @Test
  public void testSetBooleanNativeOptionWithItself() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:bool": settings["//command_line_option:bool"]}

        my_transition = transition(
            implementation = _impl,
            inputs = ["//command_line_option:bool"],
            outputs = ["//command_line_option:bool"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);
    useConfiguration("--bool=false");
    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(DummyTestOptions.class).bool).isFalse();
  }

  @Test
  public void failedTypeConversionOfNativeListOption() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:platforms": ["this is not a valid label::"]}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent("invalid target name ':': target names may not contain ':'");
  }

  @Test
  public void successfulTypeConversionOfNativeListOptionEmptyList() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:fission": []}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:fission"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        """);

    ConfiguredTarget ct = getConfiguredTarget("//test");
    assertNoEvents();
    assertThat(getConfiguration(ct).getOptions().get(CppOptions.class).fissionModes).isEmpty();
  }

  @Test
  public void failedTypeConversionOfNativeListOptionNone() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:copt": None}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "'None' value not allowed for List-type option 'copt'. Please use '[]' instead if trying"
            + " to set option to empty value.");
  }

  @Test
  public void starlarkPatchTransitionRequiredFragments() throws Exception {
    // All Starlark rule transitions are patch transitions, while all Starlark attribute transitions
    // are split transitions.
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            # --copt is a C++ option.
            return {"//command_line_option:copt": []}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        platform(name = "my_platform")

        my_rule(name = "test")
        """);

    ConfiguredTargetAndData ct = getConfiguredTargetAndData("//test");
    assertNoEvents();
    Rule testTarget = (Rule) ct.getTargetForTesting();
    ConfigurationTransition ruleTransition =
        testTarget
            .getRuleClassObject()
            .getTransitionFactory()
            .create(RuleTransitionData.create(testTarget, null, ""));
    RequiredConfigFragmentsProvider.Builder requiredFragments =
        RequiredConfigFragmentsProvider.builder();
    ruleTransition.addRequiredFragments(
        requiredFragments, ct.getConfiguration().getBuildOptionDetails());
    assertThat(requiredFragments.build().optionsClasses()).containsExactly(CppOptions.class);
  }

  /**
   * Unit test for an invalid output directory from a mnemonic via a dep transition. Integration
   * test for top-level transition in //src/test/shell/integration:starlark_configurations_test#
   * test_invalid_mnemonic_from_transition_top_level. Has to be an integration test because the
   * error is emitted in BuildTool.
   */
  @Test
  public void invalidMnemonicFromDepTransition() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _impl(settings, attr):
            return {"//command_line_option:cpu": "//bad:cpu"}

        my_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "bottom")

        genrule(
            name = "test",
            srcs = [":bottom"],
            outs = ["out"],
            cmd = "touch $@",
        )
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test:test")).isNull();
    assertContainsEvent("'//bad:cpu' is invalid as part of a path: must not contain /");
  }

  @Test
  public void testTransitionOnAllowMultiplesBuildSettingRequiresList() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": "puffins mate for life"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "cute-animal-fact",
            build_setting_default = "cats can't taste sugar",
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test");
    assertContainsEvent(
        "'//test:cute-animal-fact' allows multiple values and must be set in transition using a"
            + " starlark list instead of single value");
  }

  @Test
  public void testTransitionOnAllowMultiplesBuildSetting() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//test:cute-animal-fact": ["puffins mate for life"]}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:cute-animal-fact"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "cute-animal-fact",
            build_setting_default = "cats can't taste sugar",
        )
        """);

    Map<Label, Object> starlarkOptions =
        getConfiguration(getConfiguredTarget("//test")).getOptions().getStarlarkOptions();
    assertNoEvents();
    assertThat(
            (List<?>) starlarkOptions.get(Label.parseCanonicalUnchecked("//test:cute-animal-fact")))
        .containsExactly("puffins mate for life");
  }

  @Test
  public void testTransitionOnAllowMultiplesBuildSettingAlwaysSeesListValue() throws Exception {
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            setting_type = type(settings["//test:multiple_flag"])
            if setting_type != type([]):
                fail("Expected setting to be a list, got %s" % setting_type)
            return {}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:multiple_flag"],
            outputs = ["//test:multiple_flag"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/build_settings.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True, allow_multiple = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:build_settings.bzl", "string_flag")
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")

        string_flag(
            name = "multiple_flag",
            build_setting_default = "",
        )
        """);

    // Starlark option at is default value.
    getConfiguredTarget("//test");

    useConfiguration("--//test:multiple_flag=foo");
    getConfiguredTarget("//test");

    useConfiguration("--//test:multiple_flag=foo,bar");
    getConfiguredTarget("//test");
  }

  /**
   * Changing --cpu implicitly changes the target platform. Test that the old value of --platforms
   * gets cleared out (platform mappings can then kick in to set --platforms correctly).
   */
  @Test
  public void testImplicitPlatformsChange() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])");
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {"//command_line_option:cpu": "ppc"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--platforms=//platforms:my_platform");
    // When --platforms is empty and no platform mapping triggers, PlatformMappingValue sets
    // --platforms to PlatformOptions.computeTargetPlatform(), which defaults to the host.
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseCanonicalUnchecked(TestConstants.PLATFORM_LABEL));
  }

  @Test
  public void testExplicitPlatformsChange() throws Exception {
    scratch.file(
        "platforms/BUILD",
        """
        platform(
            name = "my_platform",
            constraint_values = [],
        )

        platform(
            name = "my_other_platform",
            constraint_values = [],
        )
        """);
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:cpu": "ppc",
                "//command_line_option:platforms": ["//platforms:my_other_platform"],
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:cpu",
                "//command_line_option:platforms",
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--platforms=//platforms:my_platform");
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_other_platform"));
  }

  /* If the transition doesn't change --cpu, it doesn't constitute a platform change. */
  @Test
  public void testNoPlatformChange() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'my_platform', constraint_values = [])");
    scratch.file(
        "test/transitions.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:foo": "blah",
            }

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:foo",
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        load("//test:transitions.bzl", "my_transition")

        my_rule = rule(implementation = lambda ctx: [], cfg = my_transition)
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:rules.bzl", "my_rule")

        my_rule(name = "test")
        """);

    useConfiguration("--platforms=//platforms:my_platform");
    assertThat(
            getConfiguration(getConfiguredTarget("//test:test"))
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseCanonicalUnchecked("//platforms:my_platform"));
  }

  @Test
  public void testTransitionsStillTriggerWhenOnlyRuleAttributesChange() throws Exception {
    scratch.file(
        "test/defs.bzl",
        """
        def _transition_impl(settings, attr):
            return {
                "//command_line_option:foo": attr.my_attr,
            }

        _my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = [
                "//command_line_option:foo",
            ],
        )

        def _rule_impl(ctx):
            return []

        my_rule = rule(
            implementation = _rule_impl,
            cfg = _my_transition,
            attrs = {
                "my_attr": attr.string(),
            },
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "buildme",
            my_attr = "first build",
        )
        """);
    assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(DummyTestOptions.class)
                .foo)
        .isEqualTo("first build");

    scratch.overwriteFile(
        "test/BUILD",
        """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "buildme",
            my_attr = "second build",
        )
        """);
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter,
        ModifiedFileSet.builder().modify(PathFragment.create("test/BUILD")).build(),
        Root.fromPath(rootDirectory));

    assertThat(
            getConfiguration(getConfiguredTarget("//test:buildme"))
                .getOptions()
                .get(DummyTestOptions.class)
                .foo)
        .isEqualTo("second build");
  }

  private static class AnalysisRootCauseCollector {
    private final ArrayList<AnalysisRootCauseEvent> rootCauses = new ArrayList<>();

    @Subscribe
    public void rootCause(AnalysisRootCauseEvent event) {
      rootCauses.add(event);
    }
  }
}
