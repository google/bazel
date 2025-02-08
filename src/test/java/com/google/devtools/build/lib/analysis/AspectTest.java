// Copyright 2015 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.ACTION_LISTENER;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.analysis.util.TestAspects.AspectApplyingToFiles;
import com.google.devtools.build.lib.analysis.util.TestAspects.AspectInfo;
import com.google.devtools.build.lib.analysis.util.TestAspects.DummyRuleFactory;
import com.google.devtools.build.lib.analysis.util.TestAspects.ExtraAttributeAspect;
import com.google.devtools.build.lib.analysis.util.TestAspects.RuleInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for aspect creation and merging with configured targets.
 *
 * <p>Uses the complete analysis machinery and depends on custom rules so that behaviors related to
 * aspects can be tested even if they aren't used by regular rules.
 */
@RunWith(JUnit4.class)
public class AspectTest extends AnalysisTestCase {

  private void pkg(String name, String... contents) throws Exception {
    scratch.file(name + "/BUILD", contents);
  }

  @Test
  public void testAspectAppliedToAliasWithSelect() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE);
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=select({'//conditions:default': ':c'}))",
        "base(name='c')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:c", "rule //a:a");
  }

  @Test
  public void testAspectAppliedToChainedAliases() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE);
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=':c')",
        "alias(name='c', actual=':d')",
        "alias(name='d', actual=':e')",
        "base(name='e')");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:e", "rule //a:a");
  }

  @Test
  public void testAspectAppliedToChainedAliasesAndSelect() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE);
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=select({'//conditions:default': ':c'}))",
        "alias(name='c', actual=select({'//conditions:default': ':d'}))",
        "base(name='d')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:d", "rule //a:a");
  }

  @Test
  public void fileProviderMerged() throws Exception {
    setRulesAvailableInTests(
        TestAspects.BASE_RULE, TestAspects.FILE_PROVIDER_ASPECT_REQUIRING_RULE);
    pkg("a", "file_provider_aspect(name='a', dep=':b')", "filegroup(name='b', srcs=['source'])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    NestedSet<Artifact> filesToBuild = a.getProvider(FileProvider.class).getFilesToBuild();
    assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
        .containsExactly("file_provider_aspect_file", "source");
  }

  @Test
  public void providersOfAspectAreMergedIntoDependency() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE);
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "aspect(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:b", "rule //a:a");
  }

  @Test
  public void aspectPropagatedThroughAliasRule() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
        TestAspects.ASPECT_REQUIRING_PROVIDER_RULE);

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b_alias'])",
        "alias(name = 'b_alias', actual = ':b')",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("rule //a:a", "aspect //a:b");
  }

  @Test
  public void aspectPropagatedThroughAliasRuleAndHonestRules() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
        TestAspects.ASPECT_REQUIRING_PROVIDER_RULE);

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "alias(name = 'b_alias', actual = ':b')",
        "honest(name='b', foo=[':c'])",
        "honest(name='c', foo=[])"
    );

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("rule //a:a", "aspect //a:b", "aspect //a:c");
  }

  @Test
  public void aspectCreatedIfAdvertisedProviderIsPresent() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
        TestAspects.ASPECT_REQUIRING_PROVIDER_RULE);

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("rule //a:a", "aspect //a:b");
  }

  @Test
  public void aspectCreatedIfAtLeastOneSetOfAdvertisedProvidersArePresent() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
        TestAspects.HONEST_RULE_2, TestAspects.ASPECT_REQUIRING_PROVIDER_SETS_RULE);

    pkg("a",
        "aspect_requiring_provider_sets(name='a', foo=[':b', ':c'])",
        "honest(name='b', foo=[])",
        "honest2(name='c', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("rule //a:a", "aspect //a:b", "aspect //a:c");
  }

  @Test
  public void aspectWithParametrizedDefinition() throws Exception {
    setRulesAvailableInTests(
        TestAspects.BASE_RULE,
        TestAspects.HONEST_RULE,
        TestAspects.PARAMETERIZED_DEFINITION_ASPECT_RULE);

    pkg(
        "a",
        "honest(name='q', foo=[])",
        "parametrized_definition_aspect(name='a', foo=[':b'], baz='//a:q')",
        "honest(name='c', foo=[])",
        "honest(name='b', foo=[':c'])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(TestAspects.RuleInfo.class).getData().toList())
        .containsExactly(
            "rule //a:a",
            "aspect //a:b data //a:q $dep:[ //a:q]",
            "aspect //a:c data //a:q $dep:[ //a:q]");
  }

  @Test
  public void aspectInError() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ERROR_ASPECT_RULE,
        TestAspects.SIMPLE_RULE);

    pkg("a",
        "simple(name='a', foo=[':b'])",
        "error_aspect(name='b', foo=[':c'])",
        "simple(name='c')");

    reporter.removeHandler(failFastHandler);
    // getConfiguredTarget() uses a separate code path that does not hit
    // SkyframeBuildView#configureTargets
    assertThrows(ViewCreationFailedException.class, () -> update("//a:a"));
    assertContainsEvent("Aspect error");
  }

  @Test
  public void transitiveAspectInError() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ERROR_ASPECT_RULE,
        TestAspects.SIMPLE_RULE);

    pkg("a",
        "error_aspect(name='a', foo=[':b'])",
        "error_aspect(name='b', bar=[':c'])",
        "error_aspect(name='c', bar=[':d'])",
        "error_aspect(name='d')");

    reporter.removeHandler(failFastHandler);
    // getConfiguredTarget() uses a separate code path that does not hit
    // SkyframeBuildView#configureTargets
    assertThrows(ViewCreationFailedException.class, () -> update("//a:a"));
    assertContainsEvent("Aspect error");
  }

  @Test
  public void aspectDependenciesDontShowDeprecationWarnings() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.EXTRA_ATTRIBUTE_ASPECT_RULE);

    pkg("extra", "base(name='extra', deprecation='bad aspect')");

    pkg("a",
        "rule_with_extra_deps_aspect(name='a', foo=[':b'])",
        "base(name='b')");

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("bad aspect", 0);
  }

  @Test
  public void aspectDependsOnPackageGroup() throws Exception {
    setRulesAvailableInTests(
        TestAspects.BASE_RULE, TestAspects.PACKAGE_GROUP_ATTRIBUTE_ASPECT_RULE);
    pkg("extra", "package_group(name='extra')");
    pkg("a", "rule_with_package_group_deps_aspect(name='a', foo=[':b'])", "base(name='b')");

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("bad aspect", 0);
  }

  @Test
  public void aspectWithComputedAttribute() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.COMPUTED_ATTRIBUTE_ASPECT_RULE);

    pkg("a", "rule_with_computed_deps_aspect(name='a', foo=[':b'])", "base(name='b')");

    getConfiguredTarget("//a:a");
  }

  @Test
  public void ruleDependencyDeprecationWarningsAbsentDuringAspectEvaluations() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE);

    pkg("a", "aspect(name='a', foo=['//b:b'])");
    pkg("b", "aspect(name='b', bar=['//d:d'])");
    pkg("d", "base(name='d', deprecation='bad rule')");

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("bad rule", 1);
  }

  @Test
  public void aspectWarningsFilteredByOutputFiltersForAssociatedRules() throws Exception {
    if (getInternalTestExecutionMode() != TestConstants.InternalTestExecutionMode.NORMAL) {
      // TODO(b/67651960): fix or justify disabling.
      return;
    }
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.WARNING_ASPECT_RULE);
    pkg("a", "warning_aspect(name='a', foo=['//b:b', '//c:c'])");
    pkg("b", "base(name='b')");
    pkg("c", "base(name='c')");

    reporter.setOutputFilter(RegexOutputFilter.forPattern(Pattern.compile("^//b:")));

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("Aspect warning on //b:b", 1);
    assertContainsEventWithFrequency("Aspect warning on //c:c", 0);
  }

  @Test
  public void sameTargetInDifferentAttributes() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE,
        TestAspects.SIMPLE_RULE);
    pkg("a",
        "aspect(name='a', foo=[':b'], bar=[':b'])",
        "aspect(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:b", "rule //a:a");
  }

  @Test
  public void sameTargetInDifferentAttributesWithDifferentAspects() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.MULTI_ASPECT_RULE,
        TestAspects.SIMPLE_RULE);
    pkg("a",
        "multi_aspect(name='a', foo=':b', bar=':b')",
        "simple(name='b')");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList()).containsExactly("foo", "bar");
  }

  @Test
  public void informationFromBaseRulePassedToAspect() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
        TestAspects.ASPECT_REQUIRING_PROVIDER_RULE);
    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'], baz='hello')",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("rule //a:a", "aspect //a:b data hello");
  }

  /**
   * Rule definitions to be used in emptyAspectAttributesAreAvailableInRuleContext().
   */
  public static class EmptyAspectAttributesAreAvailableInRuleContext {
    public static final MockRule TEST_RULE =
        () ->
            MockRule.ancestor(TestAspects.BASE_RULE.getClass())
                .factory(DummyRuleFactory.class)
                .define(
                    "testrule",
                    (builder, env) ->
                        builder.add(
                            attr("foo", LABEL_LIST)
                                .legacyAllowAnyFileType()
                                .aspect(AspectWithEmptyLateBoundAttribute.INSTANCE)));

    public static class AspectWithEmptyLateBoundAttribute extends NativeAspectClass
      implements ConfiguredAspectFactory {
      static final AspectWithEmptyLateBoundAttribute INSTANCE =
          new AspectWithEmptyLateBoundAttribute();

      private AspectWithEmptyLateBoundAttribute() {}

      @Override
      public AspectDefinition getDefinition(AspectParameters params) {
        return new AspectDefinition.Builder(this)
            .add(attr(":late", LABEL).value(LateBoundDefault.alwaysNull()))
            .build();
      }

      @Override
      public ConfiguredAspect create(
          Label targetLabel,
          ConfiguredTarget ct,
          RuleContext ruleContext,
          AspectParameters parameters,
          RepositoryName toolsRepository)
          throws InterruptedException, ActionConflictException {
        Object lateBoundPrereq = ruleContext.getPrerequisite(":late");
        return new ConfiguredAspect.Builder(ruleContext)
            .addProvider(
                AspectInfo.class,
                new AspectInfo(
                    NestedSetBuilder.create(
                        Order.STABLE_ORDER, lateBoundPrereq != null ? "non-empty" : "empty")))
            .build();
      }
    }
  }

  /**
   * An Aspect has a late-bound attribute with no value (that is, a LateBoundDefault whose
   * getDefault() returns `null`). Test that this attribute is available in the RuleContext which is
   * provided to the Aspect's `create()` method.
   */
  @Test
  public void emptyAspectAttributesAreAvailableInRuleContext() throws Exception {
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(
            TestAspects.SIMPLE_ASPECT,
            EmptyAspectAttributesAreAvailableInRuleContext.AspectWithEmptyLateBoundAttribute
                .INSTANCE),
        ImmutableList.of(
            TestAspects.BASE_RULE, EmptyAspectAttributesAreAvailableInRuleContext.TEST_RULE));
    pkg("a",
        "testrule(name='a', foo=[':b'])",
        "testrule(name='b')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData().toList()).contains("empty");
  }

  /**
   * Rule definitions to be used in extraActionsAreEmitted().
   */
  public static class ExtraActionsAreEmitted {
    public static final MockRule TEST_RULE =
        () ->
            MockRule.ancestor(TestAspects.BASE_RULE.getClass())
                .factory(DummyRuleFactory.class)
                .define(
                    "testrule",
                    (builder, env) ->
                        builder
                            .add(
                                attr("foo", LABEL_LIST)
                                    .legacyAllowAnyFileType()
                                    .aspect(AspectThatRegistersAction.INSTANCE))
                            .add(
                                attr(":action_listener", LABEL_LIST)
                                    .cfg(ExecutionTransitionFactory.createFactory())
                                    .value(ACTION_LISTENER)));

    public static class AspectThatRegistersAction extends NativeAspectClass
      implements ConfiguredAspectFactory {

      static final AspectThatRegistersAction INSTANCE = new AspectThatRegistersAction();

      private AspectThatRegistersAction() {}

      @Override
      public AspectDefinition getDefinition(AspectParameters params) {
        return new AspectDefinition.Builder(this).build();
      }

      @Override
      public ConfiguredAspect create(
          Label targetLabel,
          ConfiguredTarget ct,
          RuleContext ruleContext,
          AspectParameters parameters,
          RepositoryName toolsRepository)
          throws InterruptedException, ActionConflictException {
        ruleContext.registerAction(new NullAction(ruleContext.createOutputArtifact()));
        return new ConfiguredAspect.Builder(ruleContext).build();
      }
    }
  }

  /**
   * Test that actions registered in an Aspect are reported as extra-actions on the attached rule.
   * AspectThatRegistersAction registers a NullAction, whose mnemonic is "Null". We have an
   * action_listener that targets that mnemonic, which makes sure the Aspect machinery will expose
   * an ExtraActionArtifactsProvider.
   * The rule //a:a doesn't have an aspect, so the only action we get is the one on //a:b
   * (which does have an aspect).
   */
  @Test
  public void extraActionsAreEmitted() throws Exception {
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(
            TestAspects.SIMPLE_ASPECT, ExtraActionsAreEmitted.AspectThatRegistersAction.INSTANCE),
        ImmutableList.of(TestAspects.BASE_RULE, ExtraActionsAreEmitted.TEST_RULE));
    useConfiguration("--experimental_action_listener=//extra_actions:listener");
    scratch.file(
        "extra_actions/BUILD",
        """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Null"],
        )
        """);
    pkg("a",
        "testrule(name='a', foo=[':b'])",
        "testrule(name='b')");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    NestedSet<Artifact.DerivedArtifact> extraActionArtifacts =
        a.getProvider(ExtraActionArtifactsProvider.class).getTransitiveExtraActionArtifacts();
    for (Artifact artifact : extraActionArtifacts.toList()) {
      assertThat(artifact.getOwnerLabel()).isEqualTo(Label.create("@//a", "b"));
    }
  }

  @Test
  public void aspectPropagatesToAllAttributes() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.ALL_ATTRIBUTES_ASPECT_RULE);
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "simple(name='c', foo=[], txt='more text')",
        "all_attributes_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:a", "aspect //a:b", "aspect //a:c", "rule //a:x");
  }

  /**
   * Tests that when --experimental_extra_action_top_level_only, Blaze reports extra-actions for
   * actions registered by Aspects injected by a top-level rule. Because we can't know whether an
   * aspect was injected by a top-level target or one of its children, we approximate it by only
   * reporting extra-actions from Aspects that the top-level target could have injected.
   *
   * <p>Here, injector1() and injector2() inject aspects into their children. null_rule() just
   * passes the aspects to its children. The test makes sure that actions registered by aspect1
   * (injected by injector1()) are reported to the extra-action mechanism. Actions registered by
   * aspect2 (from injector2) are not reported, because the target under test (//x:a) doesn't inject
   * aspect2.
   */
  @Test
  public void extraActionsAreEmitted_topLevel() throws Exception {
    useConfiguration(
        "--experimental_action_listener=//pkg1:listener",
        "--experimental_extra_action_top_level_only");

    scratch.file(
        "x/BUILD",
        """
        load(":extension.bzl", "injector1", "injector2", "null_rule")

        injector1(
            name = "a",
            deps = [":b"],
        )

        null_rule(
            name = "b",
            deps = [":c"],
        )

        null_rule(
            name = "c",
            deps = [":d"],
        )

        injector2(
            name = "d",
            extra_deps = [":e"],
        )

        null_rule(name = "e")
        """);

    scratch.file(
        "x/extension.bzl",
        """
        def _aspect_impl(target, ctx):
            ctx.actions.do_nothing(mnemonic = "Mnemonic")
            return []

        aspect1 = aspect(_aspect_impl, attr_aspects = ["deps"])
        aspect2 = aspect(_aspect_impl, attr_aspects = ["extra_deps"])

        def _rule_impl(ctx):
            return []

        injector1 = rule(_rule_impl, attrs = {"deps": attr.label_list(aspects = [aspect1])})
        null_rule = rule(_rule_impl, attrs = {"deps": attr.label_list()})
        injector2 = rule(
            _rule_impl,
            attrs = {"extra_deps": attr.label_list(aspects = [aspect2])},
        )
        """);

    scratch.file(
        "pkg1/BUILD",
        """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Mnemonic"],
        )
        """);

    // Check: //x:d injects an aspect which produces some extra-action.
    {
      AnalysisResult analysisResult = update("//x:d");

      // Get owners of all extra-action artifacts.
      List<Label> extraArtifactOwners = new ArrayList<>();
      for (Artifact artifact : analysisResult.getArtifactsToBuild()) {
        if (artifact.getRootRelativePathString().endsWith(".xa")) {
          extraArtifactOwners.add(artifact.getOwnerLabel());
        }
      }
      assertThat(extraArtifactOwners).containsExactly(Label.create("@//x", "e"));
    }

    // Actual test: //x:a reports actions registered by the aspect it injects.
    {
      AnalysisResult analysisResult = update("//x:a");

      // Get owners of all extra-action artifacts.
      List<Label> extraArtifactOwners = new ArrayList<>();
      for (Artifact artifact : analysisResult.getArtifactsToBuild()) {
        if (artifact.getRootRelativePathString().endsWith(".xa")) {
          extraArtifactOwners.add(artifact.getOwnerLabel());
        }
      }
      assertThat(extraArtifactOwners)
          .containsExactly(
              Label.create("@//x", "b"), Label.create("@//x", "c"), Label.create("@//x", "d"));
    }
  }

  @Test
  public void extraActionsFromDifferentAspectsDontConflict() throws Exception {
    useConfiguration(
        "--experimental_action_listener=//pkg1:listener",
        "--experimental_extra_action_top_level_only");

    scratch.file(
        "x/BUILD",
        """
        load(":extension.bzl", "injector1", "injector2", "null_rule")

        injector2(
            name = "i2_a",
            deps = [":i1_a"],
        )

        injector1(
            name = "i1_a",
            param = "a",
            deps = [":n"],
        )

        injector1(
            name = "i1_b",
            param = "b",
            deps = [":n"],
        )

        injector2(
            name = "i2",
            deps = [":n"],
        )

        null_rule(name = "n")
        """);

    scratch.file(
        "x/extension.bzl",
        """
        def _aspect_impl(target, ctx):
            ctx.actions.do_nothing(mnemonic = "Mnemonic")
            return []

        aspect1 = aspect(
            _aspect_impl,
            attr_aspects = ["deps"],
            attrs = {"param": attr.string(values = ["a", "b"])},
        )
        aspect2 = aspect(_aspect_impl, attr_aspects = ["deps"])

        def _rule_impl(ctx):
            return []

        injector1 = rule(
            _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [aspect1]), "param": attr.string()},
        )
        injector2 = rule(_rule_impl, attrs = {"deps": attr.label_list(aspects = [aspect2])})
        null_rule = rule(_rule_impl, attrs = {"deps": attr.label_list()})
        """);

    scratch.file(
        "pkg1/BUILD",
        """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Mnemonic"],
        )
        """);

    update("//x:i1_a", "//x:i1_b", "//x:i2", "//x:i2_a");

    // Implicitly check that update() didn't throw an exception because of two actions producing
    // the same outputs.
  }

  @Test
  public void sharedArtifactsInAspect() throws Exception {
    scratch.file(
        "foo/shared_aspect.bzl",
        """
        def _shared_aspect_impl(target, ctx):
            shared_file = ctx.actions.declare_file("shared_file")
            ctx.actions.write(output = shared_file, content = "Shared content")
            lib = ctx.rule.attr.lib
            if lib:
                result = depset([shared_file], transitive = [ctx.rule.attr.lib.prov])
            else:
                result = depset([shared_file])
            return struct(prov = result)

        shared_aspect = aspect(
            implementation = _shared_aspect_impl,
            attr_aspects = ["lib"],
        )

        def _rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _rule_impl,
            attrs = {"lib": attr.label(
                providers = ["prov"],
                aspects = [shared_aspect],
            )},
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":shared_aspect.bzl", "shared_aspect", "simple_rule")

        simple_rule(
            name = "top_rule",
            lib = ":first_dep",
        )

        simple_rule(
            name = "first_dep",
            lib = ":second_dep",
        )

        simple_rule(name = "second_dep")
        """);
    // Confirm that load is successful and doesn't crash.
    update("//foo:top_rule");
  }

  @Test
  public void aspectPropagatesToAllAttributesImplicit() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.IMPLICIT_DEP_RULE, TestAspects.ALL_ATTRIBUTES_ASPECT_RULE);
    scratch.file(
        "extra/BUILD",
        "simple(name ='extra')"
    );
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "implicit_dep(name='c')",
        "all_attributes_aspect(name='x', foo=[':a'])");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly(
            "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //extra:extra", "rule //a:x");
  }


  @Test
  public void aspectPropagatesToAllAttributesLateBound() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.LATE_BOUND_DEP_RULE, TestAspects.ALL_ATTRIBUTES_ASPECT_RULE);

    scratch.file(
        "extra/BUILD",
        "simple(name ='extra')"
    );
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "late_bound_dep(name='c')",
        "all_attributes_aspect(name='x', foo=[':a'])");
    useConfiguration("--plugin=//extra:extra");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly(
            "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //extra:extra", "rule //a:x");
  }

  /**
   * Ensures an aspect with attr = '*' doesn't try to propagate to its own implicit attributes.
   * Doing so leads to a dependency cycle.
   */
  @Test
  public void aspectWithAllAttributesDoesNotPropagateToOwnImplicitAttributes() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT_RULE);
    pkg(
        "a",
        "simple(name='tool')",
        "simple(name='a')",
        "all_attributes_with_tool_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly("aspect //a:a", "rule //a:x");
  }

  /**
   * Makes sure the aspect *will* propagate to its implicit attributes if there is a "regular"
   * dependency path to it (i.e. not through its own implicit attributes).
   */
  @Test
  public void aspectWithAllAttributesPropagatesToItsToolIfThereIsPath() throws Exception {
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT_RULE);
    pkg(
        "a",
        "simple(name='tool')",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "simple(name='c', foo=[':tool'], txt='more text')",
        "all_attributes_with_tool_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData().toList())
        .containsExactly(
            "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //a:tool", "rule //a:x");
  }

  @Test
  public void aspectTruthInAdvertisement() throws Exception {
    reporter.removeHandler(failFastHandler); // expect errors
    setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
        TestAspects.FALSE_ADVERTISEMENT_ASPECT_RULE);
    pkg(
        "a",
        "simple(name = 's')",
        "false_advertisement_aspect(name = 'x', deps = [':s'])"
    );
    try {
      update("//a:x");
    } catch (ViewCreationFailedException e) {
      // expected.
    }
    assertContainsEvent(
        "Aspect 'FalseAdvertisementAspect', applied to '//a:s',"
            + " does not provide advertised provider 'RequiredProvider'");
    assertContainsEvent(
        "Aspect 'FalseAdvertisementAspect', applied to '//a:s',"
            + " does not provide advertised provider 'advertised_provider'");
  }

  @Test
  public void aspectApplyingToFiles() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspectApplyingToFiles), ImmutableList.of());
    pkg(
        "a",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])");

    var collector = new AspectConfiguredCollector();
    eventBus.register(collector);

    AnalysisResult analysisResult =
        update(
            eventBus,
            defaultFlags(),
            ImmutableList.of(aspectApplyingToFiles.getName()),
            "//a:x_deploy.jar");
    ConfiguredAspect aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    AspectApplyingToFiles.Provider provider =
        aspect.getProvider(AspectApplyingToFiles.Provider.class);
    Label label = Label.parseCanonicalUnchecked("//a:x_deploy.jar");
    assertThat(provider.getLabel()).isEqualTo(label);

    // Verifies that the AspectConfiguredEvent declares the corresponding AspectCompleteEvent.
    AspectConfiguredEvent configuredEvent = getOnlyElement(collector.events);
    BuildEventId targetCompletedId = getOnlyElement(configuredEvent.getChildrenEvents());
    AspectKey key = getOnlyElement(analysisResult.getAspectsMap().keySet());
    assertThat(targetCompletedId)
        .isEqualTo(
            BuildEventIdUtil.aspectCompleted(
                label,
                BuildEventIdUtil.configurationId(key.getConfigurationKey()),
                "AspectApplyingToFiles"));
  }

  @Test
  public void aspectApplyingToSourceFilesIgnored() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspectApplyingToFiles), ImmutableList.of());
    pkg(
        "a",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])");
    scratch.file("a/x.java", "");
    AnalysisResult analysisResult = update(new EventBus(), defaultFlags(),
        ImmutableList.of(aspectApplyingToFiles.getName()),
        "//a:x.java");
    ConfiguredAspect aspect = Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    assertThat(aspect.getProvider(AspectApplyingToFiles.Provider.class)).isNull();
  }

  @Test
  public void aspectApplyingToPackageGroupIgnored() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspectApplyingToFiles), ImmutableList.of());
    pkg("b");
    pkg(
        "a",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "package_group(name = 'group', packages = ['//b'])",
        "java_binary(name = 'x', main_class = 'x.F', srcs = ['x.java'], visibility = [':group'])");
    scratch.file("a/x.java", "");

    // This exercises a code path that crashes if the PackageGroup is matched as an aspect provider.
    AnalysisResult analysisResult =
        update(
            new EventBus(),
            defaultFlags(),
            ImmutableList.of(aspectApplyingToFiles.getName()),
            "//a:group");
    assertThat(analysisResult.getAspectsMap()).hasSize(1);
  }

  @Test
  public void duplicateTopLevelAspects_duplicateAspectsNotAllowed() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspectApplyingToFiles), ImmutableList.of());
    pkg(
        "a",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])");
    reporter.removeHandler(failFastHandler);

    assertThrows(
        ViewCreationFailedException.class,
        () ->
            update(
                new EventBus(),
                defaultFlags(),
                ImmutableList.of(aspectApplyingToFiles.getName(), aspectApplyingToFiles.getName()),
                "//a:x_deploy.jar"));
    assertContainsEvent("Aspect AspectApplyingToFiles has already been added");
  }

  @Test
  public void aspectWithExtraAttribute_ignoredForOutputFile() throws Exception {
    ExtraAttributeAspect aspect =
        new ExtraAttributeAspect("//nonexistent", /*applyToFiles=*/ false);
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspect), ImmutableList.of());
    scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')");

    AnalysisResult analysisResult =
        update(new EventBus(), defaultFlags(), ImmutableList.of(aspect.getName()), "//a");

    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    assertThat(configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey())).isNull();
  }

  @Test
  public void aspectWithExtraAttributeApplyToFiles_outputFile_hasResolvedAttribute()
      throws Exception {
    ExtraAttributeAspect aspect = new ExtraAttributeAspect("//extra", /*applyToFiles=*/ true);
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(aspect), ImmutableList.of(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE));
    scratch.file("extra/BUILD", "simple(name='extra')");
    scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')");

    AnalysisResult analysisResult =
        update(new EventBus(), defaultFlags(), ImmutableList.of(aspect.getName()), "//a");

    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkInfo provider =
        (StarlarkInfo) configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey());
    assertThat(provider.getValue("label")).isEqualTo("//extra:extra");
  }

  @Test
  public void aspectWithExtraAttributeApplyToFiles_ignoredForSourceFile() throws Exception {
    ExtraAttributeAspect aspect = new ExtraAttributeAspect("//nonexistent", /*applyToFiles=*/ true);
    setRulesAndAspectsAvailableInTests(ImmutableList.of(aspect), ImmutableList.of());
    scratch.file("a/BUILD", "exports_files(['a.txt'])");
    scratch.file("a/a.txt", "hello");

    AnalysisResult analysisResult =
        update(new EventBus(), defaultFlags(), ImmutableList.of(aspect.getName()), "//a:a.txt");

    ConfiguredAspect configuredAspect =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    assertThat(configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey())).isNull();
  }

  @Test
  public void aspectWithExtraAttributeApplyToFilesAndNot_outputFile_onlyApplyToFilesIsResolved()
      throws Exception {
    ExtraAttributeAspect aspectApplies =
        new ExtraAttributeAspect("//extra", /*applyToFiles=*/ true);
    ExtraAttributeAspect aspectDoesNotApply =
        new ExtraAttributeAspect("//nonexistent", /*applyToFiles=*/ false);
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(aspectApplies, aspectDoesNotApply),
        ImmutableList.of(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE));
    scratch.file("extra/BUILD", "simple(name='extra')");
    scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')");

    AnalysisResult analysisResult =
        update(
            new EventBus(),
            defaultFlags(),
            ImmutableList.of(aspectApplies.getName(), aspectDoesNotApply.getName()),
            "//a");

    assertThat(analysisResult.getAspectsMap()).hasSize(2);
    StarlarkInfo provider =
        (StarlarkInfo)
            getAspectByName(analysisResult.getAspectsMap(), aspectApplies.getName())
                .get(ExtraAttributeAspect.PROVIDER.getKey());
    assertThat(provider.getValue("label")).isEqualTo("//extra:extra");
    assertThat(
            getAspectByName(analysisResult.getAspectsMap(), aspectDoesNotApply.getName())
                .getProviders()
                .getProviderCount())
        .isEqualTo(0);
  }

  @Test
  public void aspectWithExtraAttributeDependsOnNotApplicable_usesItsOwnAttribute()
      throws Exception {
    ExtraAttributeAspect aspectApplies =
        new ExtraAttributeAspect(
            "//extra",
            /* applyToFiles= */ true,
            StarlarkProviderIdentifier.forKey(ExtraAttributeAspect.PROVIDER.getKey()));
    ExtraAttributeAspect aspectDoesNotApply =
        new ExtraAttributeAspect("//extra:extra2", /*applyToFiles=*/ false);
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(aspectApplies, aspectDoesNotApply),
        ImmutableList.of(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE));
    scratch.file(
        "extra/BUILD",
        """
        simple(name = "extra")

        simple(name = "extra2")
        """);
    scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')");

    AnalysisResult analysisResult =
        update(
            new EventBus(),
            defaultFlags(),
            ImmutableList.of(aspectDoesNotApply.getName(), aspectApplies.getName()),
            "//a");

    assertThat(analysisResult.getAspectsMap()).hasSize(2);
    StarlarkInfo provider =
        (StarlarkInfo)
            getAspectByName(analysisResult.getAspectsMap(), aspectApplies.getName())
                .get(ExtraAttributeAspect.PROVIDER.getKey());
    assertThat(provider.getValue("label")).isEqualTo("//extra:extra");
    assertThat(
            getAspectByName(analysisResult.getAspectsMap(), aspectDoesNotApply.getName())
                .getProviders()
                .getProviderCount())
        .isEqualTo(0);
  }

  @Test
  public void sameConfiguredAttributeOnAspectAndRule() throws Exception {
    scratch.file(
        "a/a.bzl",
        """
        def _a_impl(t, ctx):
            return [DefaultInfo()]

        def _r_impl(ctx):
            return [DefaultInfo()]

        a = aspect(
            implementation = _a_impl,
            attrs = {"_f": attr.label(
                default = configuration_field(
                    fragment = "cpp",
                    name = "cc_toolchain",
                ),
            )},
        )
        r = rule(
            implementation = _r_impl,
            attrs = {
                "_f": attr.label(
                    default = configuration_field(
                        fragment = "cpp",
                        name = "cc_toolchain",
                    ),
                ),
                "dep": attr.label(aspects = [a]),
            },
        )
        """);

    scratch.file(
        "a/BUILD",
        """
        load(":a.bzl", "r")

        r(name = "r")
        """);

    setRulesAndAspectsAvailableInTests(ImmutableList.of(), ImmutableList.of());
    getConfiguredTarget("//a:r");
  }

  @Test
  public void topLevelConflictDetected() throws Exception {
    String bzlFileTemplate =
        String.join(
            "\n",
            "def _aspect1_impl(target, ctx):",
            "  outfile = ctx.actions.declare_file('aspect.out')",
            "  ctx.actions.run_shell(",
            "    outputs = [outfile],",
            "    progress_message = 'Action for aspect 1',",
            "    command = 'echo \"1\" > ' + outfile.path,",
            "  )",
            "  return [OutputGroupInfo(files = [outfile])]",
            "def _aspect2_impl(target, ctx):",
            "  outfile = ctx.actions.declare_file('aspect.out')",
            "  ctx.actions.run_shell(",
            "    outputs = [outfile],",
            "    progress_message = 'Action for aspect 2',",
            "    command = 'echo \"%s\" > ' + outfile.path,",
            "  )",
            "  return [OutputGroupInfo(files = [outfile])]",
            "aspect1 = aspect(implementation = _aspect1_impl)",
            "aspect2 = aspect(implementation = _aspect2_impl)");
    scratch.file("foo/aspect.bzl", String.format(bzlFileTemplate, "2"));
    scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.sh'])");
    // Expect errors.
    reporter.removeHandler(failFastHandler);
    ViewCreationFailedException exception =
        assertThrows(
            ViewCreationFailedException.class,
            () ->
                update(
                    new EventBus(),
                    defaultFlags(),
                    ImmutableList.of("//foo:aspect.bzl%aspect1", "//foo:aspect.bzl%aspect2"),
                    "//foo:foo"));
    assertThat(exception)
        .hasMessageThat()
        .containsMatch("file 'foo/aspect.out' is generated by these conflicting actions:");
    MoreAsserts.assertContainsEvent(
        eventCollector,
        Pattern.compile(
            "Aspects: \\[//foo:aspect.bzl%aspect[12]], \\[//foo:aspect.bzl%aspect[12]]"),
        EventKind.ERROR);

    // Fix bzl file so actions are shared: analysis should succeed now.
    scratch.overwriteFile("foo/aspect.bzl", String.format(bzlFileTemplate, "1"));
    reporter.addHandler(failFastHandler);
    AnalysisResult result =
        update(
            new EventBus(),
            defaultFlags(),
            ImmutableList.of("//foo:aspect.bzl%aspect1", "//foo:aspect.bzl%aspect2"),
            "//foo:foo");
    assertThat(result.getAspectsMap()).hasSize(2);

    // Break bzl file again: we should notice.
    scratch.overwriteFile("foo/aspect.bzl", String.format(bzlFileTemplate, "2"));
    // Expect errors.
    reporter.removeHandler(failFastHandler);
    assertThrows(
        ViewCreationFailedException.class,
        () ->
            update(
                new EventBus(),
                defaultFlags(),
                ImmutableList.of("//foo:aspect.bzl%aspect1", "//foo:aspect.bzl%aspect2"),
                "//foo:foo"));
    MoreAsserts.assertContainsEvent(
        eventCollector,
        Pattern.compile(
            "Aspects: \\[//foo:aspect.bzl%aspect[12]], \\[//foo:aspect.bzl%aspect[12]]"),
        EventKind.ERROR);
  }

  @Test
  public void conflictBetweenTargetAndAspect() throws Exception {
    scratch.file(
        "foo/aspect.bzl",
        """
        def _aspect_impl(target, ctx):
            outfile = ctx.actions.declare_file("conflict.out")
            ctx.actions.run_shell(
                outputs = [outfile],
                progress_message = "Action for aspect",
                command = 'echo "aspect" > ' + outfile.path,
            )
            return [OutputGroupInfo(files = [outfile])]

        def _rule_impl(ctx):
            outfile = ctx.actions.declare_file("conflict.out")
            ctx.actions.run_shell(
                outputs = [outfile],
                progress_message = "Action for target",
                command = 'echo "target" > ' + outfile.path,
            )
            return [DefaultInfo(files = depset([outfile]))]

        my_aspect = aspect(implementation = _aspect_impl)
        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load("//foo:aspect.bzl", "my_aspect", "my_rule")

        my_rule(
            name = "foo",
            deps = [":dep"],
        )

        filegroup(
            name = "dep",
            srcs = ["dep.sh"],
        )
        """);
    // Expect errors.
    reporter.removeHandler(failFastHandler);
    ViewCreationFailedException exception =
        assertThrows(ViewCreationFailedException.class, () -> update("//foo:foo"));
    assertThat(exception)
        .hasMessageThat()
        .containsMatch("file 'foo/conflict.out' is generated by these conflicting actions");
    MoreAsserts.assertContainsEvent(
        eventCollector,
        Pattern.compile(
            "Aspects: (\\[], \\[//foo:aspect.bzl%my_aspect]|\\[//foo:aspect.bzl%my_aspect], \\[])"),
        EventKind.ERROR);
  }

  @Test
  public void aspectDuplicatesRuleProviderError() throws Exception {
    setRulesAndAspectsAvailableInTests(ImmutableList.of(), ImmutableList.of());
    scratch.file(
        "aspect/build_defs.bzl",
        """
        DuplicateInfo = provider(fields=[])
        def _aspect_impl(target, ctx):
            return [DuplicateInfo()]

        returns_duplicate_aspect = aspect(implementation = _aspect_impl)

        def _duplicate_rule_impl(ctx):
          return [DefaultInfo(), DuplicateInfo()]

        duplicate_rule = rule(implementation = _duplicate_rule_impl, attrs = {})

        def _rule_impl(ctx):
            pass

        duplicate_aspect_applying_rule = rule(
            implementation = _rule_impl,
            attrs = {"to": attr.label(aspects = [returns_duplicate_aspect])},
        )
        """);
    scratch.file(
        "aspect/BUILD",
        """
        load("build_defs.bzl", "duplicate_aspect_applying_rule", "duplicate_rule")

        duplicate_rule(name = "duplicate")

        duplicate_aspect_applying_rule(
            name = "applies_aspect",
            to = ":duplicate",
        )
        """);
    assertThat(
            assertThrows(
                AssertionError.class, () -> getConfiguredTarget("//aspect:applies_aspect")))
        .hasMessageThat()
        .contains("Provider DuplicateInfo provided twice");
  }

  @Test
  public void instrumentedFilesInfoFromBaseRuleAndAspectUsesAspect() throws Exception {
    scratch.file(
        "aspect/build_defs.bzl",
        """
        def _instrumented_files_info_aspect_impl(target, ctx):
            return [coverage_common.instrumented_files_info(ctx, source_attributes = ["a"])]

        instrumented_files_info_aspect = aspect(
            implementation = _instrumented_files_info_aspect_impl,
        )

        def _no_instrumented_files_info_aspect_impl(target, ctx):
            return []

        no_instrumented_files_info_aspect = aspect(
            implementation = _no_instrumented_files_info_aspect_impl,
        )

        def _applies_aspect_impl(ctx):
            return coverage_common.instrumented_files_info(ctx, dependency_attributes = ["to"])

        instrumented_files_info_aspect_rule = rule(
            implementation = _applies_aspect_impl,
            attrs = {"to": attr.label(aspects = [instrumented_files_info_aspect])},
        )

        no_instrumented_files_info_aspect_rule = rule(
            implementation = _applies_aspect_impl,
            attrs = {"to": attr.label(aspects = [no_instrumented_files_info_aspect])},
        )

        def _base_rule_impl(ctx):
            return [coverage_common.instrumented_files_info(ctx, source_attributes = ["b"])]

        base_rule = rule(
            implementation = _base_rule_impl,
            attrs = {"a": attr.label(allow_files = True), "b": attr.label(allow_files = True)},
        )

        def _base_rule_no_coverage_impl(ctx):
            return []

        base_rule_no_coverage = rule(
            implementation = _base_rule_no_coverage_impl,
            attrs = {"a": attr.label(allow_files = True), "b": attr.label(allow_files = True)},
        )
        """);
    scratch.file(
        "aspect/BUILD",
        """
        load(
            "build_defs.bzl",
            "base_rule",
            "base_rule_no_coverage",
            "instrumented_files_info_aspect_rule",
            "no_instrumented_files_info_aspect_rule",
        )

        base_rule(
            name = "rule_target",
            # Ends up in coverage sources when instrumented_files_info_aspect is applied
            a = "a",
            # Ends up in coverage sources for the base rule's InstrumentedFilesInfo is used
            b = "b",
        )

        instrumented_files_info_aspect_rule(
            name = "duplicate_instrumented_file_info",
            to = ":rule_target",
        )

        no_instrumented_files_info_aspect_rule(
            name = "instrumented_file_info_from_base_target",
            to = ":rule_target",
        )

        base_rule_no_coverage(
            name = "rule_target_no_coverage",
            # Ends up in coverage sources when instrumented_files_info_aspect is applied
            a = "a",
            # Ends up in coverage sources never
            b = "b",
        )

        instrumented_files_info_aspect_rule(
            name = "instrumented_files_info_only_from_aspect",
            to = ":rule_target_no_coverage",
        )

        no_instrumented_files_info_aspect_rule(
            name = "no_instrumented_files_info",
            to = ":rule_target_no_coverage",
        )
        """);
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=.*");
    update();
    assertThat(getInstrumentedFiles("//aspect:rule_target")).containsExactly("b");
    assertThat(getInstrumentedFiles("//aspect:duplicate_instrumented_file_info"))
        .containsExactly("a");
    assertThat(getInstrumentedFiles("//aspect:instrumented_file_info_from_base_target"))
        .containsExactly("b");
    assertThat(getInstrumentedFiles("//aspect:rule_target_no_coverage")).isEmpty();
    assertThat(getInstrumentedFiles("//aspect:instrumented_files_info_only_from_aspect"))
        .containsExactly("a");
    assertThat(getInstrumentedFiles("//aspect:no_instrumented_files_info")).isEmpty();
  }

  private List<String> getInstrumentedFiles(String label) throws InterruptedException {
    return ActionsTestUtil.baseArtifactNames(
        getConfiguredTarget(label)
            .get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
            .getInstrumentedFiles());
  }

  @Test
  public void aspectSeesAspectHintsAttributeOnNativeRule() throws Exception {
    setupAspectHints();
    scratch.file(
        "aspect_hints/BUILD",
        """
        load("//aspect_hints:hints.bzl", "hint")
        load("//aspect_hints:hints_counter.bzl", "count_hints")

        hint(
            name = "my_hint",
            hints_cnt = 3,
        )

        cc_library(
            name = "lib1",
            deps = [":lib2"],
        )

        cc_library(
            name = "lib2",
            aspect_hints = [":my_hint"],
        )

        count_hints(
            name = "cnt",
            deps = [":lib1"],
        )
        """);
    update();

    ConfiguredTarget a = getConfiguredTarget("//aspect_hints:cnt");
    StarlarkInt info = (StarlarkInt) getHintsCntInfo(a).getValue("cnt");

    assertThat(info.truncateToInt()).isEqualTo(3);
  }

  @Test
  public void aspectSeesAspectHintsAttributeOnStarlarkRule() throws Exception {
    setupAspectHints();
    setupStarlarkRule();
    scratch.file(
        "aspect_hints/BUILD",
        """
        load("//aspect_hints:custom_rule.bzl", "custom_rule")
        load("//aspect_hints:hints.bzl", "hint")
        load("//aspect_hints:hints_counter.bzl", "count_hints")

        hint(
            name = "my_hint",
            hints_cnt = 2,
        )

        custom_rule(
            name = "lib1",
            deps = [":lib2"],
        )

        custom_rule(
            name = "lib2",
            aspect_hints = [":my_hint"],
        )

        count_hints(
            name = "cnt",
            deps = [":lib1"],
        )
        """);
    update();

    ConfiguredTarget a = getConfiguredTarget("//aspect_hints:cnt");
    StarlarkInt info = (StarlarkInt) getHintsCntInfo(a).getValue("cnt");

    assertThat(info.truncateToInt()).isEqualTo(2);
  }

  @Test
  public void ruleDepsVisibilityNotAffectNativeAspect() throws Exception {
    setRulesAndAspectsAvailableInTests(
        ImmutableList.of(TestAspects.ALL_ATTRIBUTES_ASPECT), ImmutableList.of());
    scratch.file("defs/BUILD");
    scratch.file(
        "defs/build_defs.bzl",
        """
        def _rule_impl(ctx):
            pass

        implicit_dep_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "_tool": attr.label(default = "//tool:tool"),
                "deps": attr.label_list(),
            },
        )
        """);
    scratch.file("tool/BUILD", "filegroup(name='tool', visibility = ['//defs:__pkg__'])");
    scratch.file(
        "pkg/BUILD",
        """
        load("//defs:build_defs.bzl", "implicit_dep_rule")

        implicit_dep_rule(name = "y")

        implicit_dep_rule(
            name = "x",
            deps = [":y"],
        )
        """);

    AnalysisResult result =
        update(
            new EventBus(),
            defaultFlags(),
            ImmutableList.of(TestAspects.ALL_ATTRIBUTES_ASPECT.getName()),
            "//pkg:x");

    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void nativeAspectFailIfDepsNotVisible() throws Exception {
    scratch.file("tool/BUILD", "filegroup(name='tool', visibility = ['//visibility:private'])");
    ExtraAttributeAspect extraAttributeAspect = new ExtraAttributeAspect("//tool:tool", false);
    setRulesAndAspectsAvailableInTests(ImmutableList.of(extraAttributeAspect), ImmutableList.of());
    scratch.file(
        "pkg/build_defs.bzl",
        """
        def _rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _rule_impl,
        )
        """);
    scratch.file(
        "pkg/BUILD",
        """
        load("//pkg:build_defs.bzl", "simple_rule")

        simple_rule(name = "x")
        """);
    reporter.removeHandler(failFastHandler);

    assertThrows(
        ViewCreationFailedException.class,
        () ->
            update(
                new EventBus(),
                defaultFlags(),
                ImmutableList.of(extraAttributeAspect.getName()),
                "//pkg:x"));
    assertContainsEvent(
        "ExtraAttributeAspect_//tool:tool_false aspect on simple_rule rule //pkg:x: "
            + "Visibility error:\n"
            + "target '//tool:tool' is not visible from\n"
            + "target '//pkg:x'");
  }

  private void setupAspectHints() throws Exception {
    scratch.file(
        "aspect_hints/hints.bzl",
        """
        HintInfo = provider(fields = ["hints_cnt"])

        def _hint_impl(ctx):
            return [HintInfo(hints_cnt = ctx.attr.hints_cnt)]

        hint = rule(
            implementation = _hint_impl,
            attrs = {"hints_cnt": attr.int(default = 0)},
        )
        """);
    scratch.file(
        "aspect_hints/hints_counter.bzl",
        """
        load("//aspect_hints:hints.bzl", "HintInfo")

        HintsCntInfo = provider(fields = ["cnt"])

        def _my_aspect_impl(target, ctx):
            transitive_hints = 0
            for dep in ctx.rule.attr.deps:
                transitive_hints = transitive_hints + dep[HintsCntInfo].cnt

            hints = 0
            for hint in ctx.rule.attr.aspect_hints:
                hints = hints + hint[HintInfo].hints_cnt

            return [HintsCntInfo(cnt = hints + transitive_hints)]

        my_aspect = aspect(
            implementation = _my_aspect_impl,
            attr_aspects = ["deps"],
        )

        def _count_hints_impl(ctx):
            hints = 0
            for dep in ctx.attr.deps:
                hints = hints + dep[HintsCntInfo].cnt
            return [HintsCntInfo(cnt = hints)]

        count_hints = rule(
            implementation = _count_hints_impl,
            attrs = {
                "deps": attr.label_list(aspects = [my_aspect]),
            },
        )
        """);
  }

  private void setupStarlarkRule() throws Exception {
    scratch.file(
        "aspect_hints/custom_rule.bzl",
        """
        def _custom_rule_impl(ctx):
            return []

        custom_rule = rule(
            implementation = _custom_rule_impl,
            attrs = {
                "deps": attr.label_list(),
            },
        )
        """);
  }

  private static StructImpl getHintsCntInfo(ConfiguredTarget configuredTarget)
      throws LabelSyntaxException {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//aspect_hints:hints_counter.bzl")), "HintsCntInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  private static ConfiguredAspect getAspectByName(
      ImmutableMap<AspectKey, ConfiguredAspect> aspectMap, String name) {
    return aspectMap.entrySet().stream()
        .filter(e -> e.getKey().getAspectName().equals(name))
        .map(Map.Entry::getValue)
        .collect(onlyElement());
  }

  private static class AspectConfiguredCollector {
    private final ArrayList<AspectConfiguredEvent> events = new ArrayList<>();

    @Subscribe
    public void configuredEvent(AspectConfiguredEvent event) {
      events.add(event);
    }
  }
}
