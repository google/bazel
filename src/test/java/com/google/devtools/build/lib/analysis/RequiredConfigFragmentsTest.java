// Copyright 2020 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link RequiredConfigFragmentsProvider}. */
@RunWith(TestParameterInjector.class)
public final class RequiredConfigFragmentsTest extends BuildViewTestCase {

  public static final class AOptions extends FragmentOptions {
    @Option(
        name = "a_option",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN})
    public String aOption;
  }

  /**
   * Public for {@link com.google.devtools.build.lib.analysis.config.FragmentFactory}'s
   * reflection-based construction.
   */
  @RequiresOptions(options = {AOptions.class})
  public static final class TestFragmentA extends Fragment {
    public TestFragmentA(BuildOptions options) {}
  }

  /**
   * Public for {@link com.google.devtools.build.lib.analysis.config.FragmentFactory}'s
   * reflection-based construction.
   */
  public static final class TestFragmentB extends Fragment {
    public TestFragmentB(BuildOptions options) {}
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder()
            .addRuleDefinition(new RuleThatAttachesAspect())
            .addRuleDefinition(REQUIRES_FRAGMENT_A)
            .addRuleDefinition(REQUIRES_FRAGMENT_B)
            .addNativeAspectClass(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS)
            .addConfigurationFragment(TestFragmentA.class)
            .addConfigurationFragment(TestFragmentB.class);
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  private static final MockRule REQUIRES_FRAGMENT_A =
      () ->
          MockRule.define(
              "requires_fragment_a",
              (builder, env) ->
                  builder
                      .add(
                          attr("deps", BuildType.LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                      .requiresConfigurationFragments(TestFragmentA.class));

  private static final MockRule REQUIRES_FRAGMENT_B =
      () ->
          MockRule.define(
              "requires_fragment_b",
              (builder, env) -> builder.requiresConfigurationFragments(TestFragmentB.class));

  @Test
  public void provideTransitiveRequiredFragmentsMode() throws Exception {
    useConfiguration("--include_config_fragments_provider=transitive");
    scratch.file(
        "a/BUILD",
        """
        requires_fragment_b(name = "b")

        requires_fragment_a(
            name = "a",
            deps = [":b"],
        )
        """);

    RequiredConfigFragmentsProvider aTransitiveFragments =
        getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(aTransitiveFragments.fragmentClasses())
        .containsAtLeast(TestFragmentA.class, TestFragmentB.class);
  }

  @Test
  public void configSettingProvideTransitiveRequiresFragment() throws Exception {
    useConfiguration("--include_config_fragments_provider=transitive");
    scratch.file(
        "a/BUILD",
        """
        config_setting(
            name = "config_on_native",
            values = {"cpu": "foo"},
        )

        config_setting(
            name = "config_on_a",
            values = {"a_option": "foo"},
        )
        """);

    assertThat(
            getConfiguredTarget("//a:config_on_a")
                .getProvider(RequiredConfigFragmentsProvider.class)
                .optionsClasses())
        .contains(AOptions.class);
    assertThat(
            getConfiguredTarget("//a:config_on_native")
                .getProvider(RequiredConfigFragmentsProvider.class)
                .optionsClasses())
        .doesNotContain(AOptions.class);
  }

  @Test
  public void provideDirectRequiredFragmentsMode() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct");
    scratch.file(
        "a/BUILD",
        """
        requires_fragment_b(name = "b")

        requires_fragment_a(
            name = "a",
            deps = [":b"],
        )
        """);

    RequiredConfigFragmentsProvider aDirectFragments =
        getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(aDirectFragments.fragmentClasses()).contains(TestFragmentA.class);
    assertThat(aDirectFragments.fragmentClasses()).doesNotContain(TestFragmentB.class);
  }

  @Test
  public void configSettingProvideDirectRequiresFragment() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct");
    scratch.file(
        "a/BUILD",
        """
        config_setting(
            name = "config_on_native",
            values = {"cpu": "foo"},
        )

        config_setting(
            name = "config_on_a",
            values = {"a_option": "foo"},
        )
        """);

    assertThat(
            getConfiguredTarget("//a:config_on_a")
                .getProvider(RequiredConfigFragmentsProvider.class)
                .optionsClasses())
        .contains(AOptions.class);
    assertThat(
            getConfiguredTarget("//a:config_on_native")
                .getProvider(RequiredConfigFragmentsProvider.class)
                .optionsClasses())
        .doesNotContain(AOptions.class);
  }

  @Test
  public void requiresMakeVariablesSuppliedByDefine() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct", "--define", "myvar=myval");
    scratch.file(
        "a/BUILD",
        """
        genrule(
            name = "myrule",
            srcs = [],
            outs = ["myrule.out"],
            cmd = "echo $(myvar) $(COMPILATION_MODE) > $@",
        )
        """);
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:myrule").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.defines()).containsExactly("myvar");
  }

  @Test
  public void starlarkExpandMakeVariables() throws Exception {
    useConfiguration("--include_config_fragments_provider=direct", "--define=myvar=myval");
    scratch.file(
        "a/defs.bzl",
        """
        def _impl(ctx):
            print(ctx.expand_make_variables("dummy attribute", "string with $(myvar)!", {}))

        simple_rule = rule(
            implementation = _impl,
            attrs = {},
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//a:defs.bzl", "simple_rule")

        simple_rule(name = "simple")
        """);
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.defines()).containsExactly("myvar");
  }

  @Test
  public void starlarkCtxVar() throws Exception {
    useConfiguration(
        "--include_config_fragments_provider=direct", "--define=required_var=1,irrelevant_var=1");
    scratch.file(
        "a/defs.bzl",
        """
        def _impl(ctx):
            # Defined, so reported as required.
            if "required_var" not in ctx.var:
                fail("Missing required_var")

            # Not defined, so not reported as required.
            if "prohibited_var" in ctx.var:
                fail("Not allowed to set prohibited_var")

            # Present but not a define variable, so not reported as required.
            if "COMPILATION_MODE" not in ctx.var:
                fail("Missing COMPILATION_MODE")

        simple_rule = rule(
            implementation = _impl,
            attrs = {},
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//a:defs.bzl", "simple_rule")

        simple_rule(name = "simple")
        """);
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.defines()).containsExactly("required_var");
  }

  /**
   * Aspect that requires fragments both in its definition and through {@link
   * #addAspectImplSpecificRequiredConfigFragments}.
   */
  private static final class AspectWithConfigFragmentRequirements extends NativeAspectClass
      implements ConfiguredAspectFactory {
    private static final Class<JavaConfiguration> REQUIRED_FRAGMENT = JavaConfiguration.class;
    private static final String REQUIRED_DEFINE = "myvar";

    @Override
    public AspectDefinition getDefinition(AspectParameters params) {
      return new AspectDefinition.Builder(this)
          .requiresConfigurationFragments(REQUIRED_FRAGMENT)
          .build();
    }

    @Override
    public ConfiguredAspect create(
        Label targetLabel,
        ConfiguredTarget ct,
        RuleContext ruleContext,
        AspectParameters params,
        RepositoryName toolsRepository)
        throws ActionConflictException, InterruptedException {
      return new ConfiguredAspect.Builder(ruleContext).build();
    }

    @Override
    public void addAspectImplSpecificRequiredConfigFragments(
        RequiredConfigFragmentsProvider.Builder requiredFragments) {
      requiredFragments.addDefine(REQUIRED_DEFINE);
    }
  }

  private static final AspectWithConfigFragmentRequirements
      ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS = new AspectWithConfigFragmentRequirements();

  /** Rule that attaches {@link AspectWithConfigFragmentRequirements} to its deps. */
  public static final class RuleThatAttachesAspect
      implements RuleDefinition, RuleConfiguredTargetFactory {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(
              attr("deps", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.NO_FILE)
                  .aspect(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_that_attaches_aspect")
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .factoryClass(RuleThatAttachesAspect.class)
          .build();
    }

    @Override
    @Nullable
    public ConfiguredTarget create(RuleContext ruleContext)
        throws ActionConflictException, InterruptedException {
      return new RuleConfiguredTargetBuilder(ruleContext)
          .addProvider(RunfilesProvider.EMPTY)
          .build();
    }
  }

  @Test
  public void aspectRequiresFragments() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        rule_that_attaches_aspect(
            name = "parent",
            deps = [":dep"],
        )

        rule_that_attaches_aspect(name = "dep")
        """);
    useConfiguration("--include_config_fragments_provider=transitive");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:parent").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.fragmentClasses())
        .contains(AspectWithConfigFragmentRequirements.REQUIRED_FRAGMENT);
    assertThat(requiredFragments.defines())
        .containsExactly(AspectWithConfigFragmentRequirements.REQUIRED_DEFINE);
  }

  private void writeStarlarkTransitionsAndAllowList() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//a/...",
            ],
        )
        """);
    scratch.file(
        "transitions/defs.bzl",
        """
        def _java_write_transition_impl(settings, attr):
            return {"//command_line_option:javacopt": ["foo"]}

        java_write_transition = transition(
            implementation = _java_write_transition_impl,
            inputs = [],
            outputs = ["//command_line_option:javacopt"],
        )

        def _cpp_read_transition_impl(settings, attr):
            return {}

        cpp_read_transition = transition(
            implementation = _cpp_read_transition_impl,
            inputs = ["//command_line_option:copt"],
            outputs = [],
        )
        """);
    scratch.file("transitions/BUILD");
  }

  @Test
  public void starlarkRuleTransitionReadsFragment() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        """
        load("//transitions:defs.bzl", "cpp_read_transition")

        def _impl(ctx):
            pass

        has_cpp_aware_rule_transition = rule(
            implementation = _impl,
            cfg = cpp_read_transition,
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//a:defs.bzl", "has_cpp_aware_rule_transition")

        has_cpp_aware_rule_transition(name = "cctarget")
        """);
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:cctarget").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.optionsClasses()).contains(CppOptions.class);
    assertThat(requiredFragments.optionsClasses()).doesNotContain(JavaOptions.class);
  }

  @Test
  public void starlarkRuleTransitionWritesFragment() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        """
        load("//transitions:defs.bzl", "java_write_transition")

        def _impl(ctx):
            pass

        has_java_aware_rule_transition = rule(
            implementation = _impl,
            cfg = java_write_transition,
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//a:defs.bzl", "has_java_aware_rule_transition")

        has_java_aware_rule_transition(name = "javatarget")
        """);
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:javatarget").getProvider(RequiredConfigFragmentsProvider.class);
    assertThat(requiredFragments.optionsClasses()).contains(JavaOptions.class);
    assertThat(requiredFragments.optionsClasses()).doesNotContain(CppOptions.class);
  }

  @Test
  public void starlarkAttrTransition() throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        """
        load("//transitions:defs.bzl", "cpp_read_transition", "java_write_transition")

        def _impl(ctx):
            pass

        has_java_aware_attr_transition = rule(
            implementation = _impl,
            attrs = {
                "deps": attr.label_list(cfg = java_write_transition),
            },
        )
        has_cpp_aware_rule_transition = rule(
            implementation = _impl,
            cfg = cpp_read_transition,
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//a:defs.bzl", "has_cpp_aware_rule_transition", "has_java_aware_attr_transition")

        has_cpp_aware_rule_transition(name = "ccchild")

        has_java_aware_attr_transition(
            name = "javaparent",
            deps = [":ccchild"],
        )
        """);
    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:javaparent").getProvider(RequiredConfigFragmentsProvider.class);
    // We consider the attribute transition over the parent -> child edge a property of the parent.
    assertThat(requiredFragments.optionsClasses()).contains(JavaOptions.class);
    // But not the child's rule transition.
    assertThat(requiredFragments.optionsClasses()).doesNotContain(CppOptions.class);
  }

  @Test
  public void aspectInheritsTransitiveFragmentsFromBaseCT(
      @TestParameter({"DIRECT", "TRANSITIVE"}) IncludeConfigFragmentsEnum setting)
      throws Exception {
    writeStarlarkTransitionsAndAllowList();
    scratch.file(
        "a/defs.bzl",
        """
        A1Info = provider()

        def _a1_impl(target, ctx):
            return []

        a1 = aspect(implementation = _a1_impl)

        def _java_depender_impl(ctx):
            return []

        java_depender = rule(
            implementation = _java_depender_impl,
            fragments = ["java"],
            attrs = {},
        )

        def _r_impl(ctx):
            return []

        r = rule(
            implementation = _r_impl,
            attrs = {"dep": attr.label(aspects = [a1])},
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":defs.bzl", "java_depender", "r")

        java_depender(name = "lib")

        r(
            name = "r",
            dep = ":lib",
        )
        """);

    useConfiguration("--include_config_fragments_provider=" + setting);
    getConfiguredTarget("//a:r");
    RequiredConfigFragmentsProvider requiredFragments =
        getAspect("//a:defs.bzl%a1").getProvider(RequiredConfigFragmentsProvider.class);

    if (setting == IncludeConfigFragmentsEnum.TRANSITIVE) {
      assertThat(requiredFragments.fragmentClasses()).contains(JavaConfiguration.class);
    } else {
      assertThat(requiredFragments.fragmentClasses()).doesNotContain(JavaConfiguration.class);
    }
  }

  @Test
  public void aspectInheritsTransitiveFragmentsFromRequiredAspect(
      @TestParameter({"DIRECT", "TRANSITIVE"}) IncludeConfigFragmentsEnum setting)
      throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        A1Info = provider()

        def _a1_impl(target, ctx):
            return A1Info(var = ctx.var.get("my_var", "0"))

        a1 = aspect(implementation = _a1_impl, provides = [A1Info])

        A2Info = provider()

        def _a2_impl(target, ctx):
            return A2Info()

        a2 = aspect(implementation = _a2_impl, required_aspect_providers = [A1Info])

        def _simple_rule_impl(ctx):
            return []

        simple_rule = rule(
            implementation = _simple_rule_impl,
            attrs = {},
        )

        def _r_impl(ctx):
            return []

        r = rule(
            implementation = _r_impl,
            attrs = {"dep": attr.label(aspects = [a1, a2])},
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":defs.bzl", "r", "simple_rule")

        simple_rule(name = "lib")

        r(
            name = "r",
            dep = ":lib",
        )
        """);

    useConfiguration("--include_config_fragments_provider=" + setting, "--define", "my_var=1");
    getConfiguredTarget("//a:r");
    RequiredConfigFragmentsProvider requiredFragments =
        getAspect("//a:defs.bzl%a2").getProvider(RequiredConfigFragmentsProvider.class);

    if (setting == IncludeConfigFragmentsEnum.TRANSITIVE) {
      assertThat(requiredFragments.defines()).contains("my_var");
    } else {
      assertThat(requiredFragments.defines()).doesNotContain("my_var");
    }
  }

  @Test
  public void invalidStarlarkFragmentsFiltered() throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _my_rule_impl(ctx):
            pass

        my_rule = rule(implementation = _my_rule_impl, fragments = ["java", "doesnotexist"])
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "example")
        """);

    useConfiguration("--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:example").getProvider(RequiredConfigFragmentsProvider.class);

    assertThat(requiredFragments.fragmentClasses()).contains(JavaConfiguration.class);
  }

  @Test
  public void aspectInErrorWithAllowAnalysisFailures() throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _error_aspect_impl(target, ctx):
            fail(ctx.var["FAIL_MESSAGE"])

        error_aspect = aspect(implementation = _error_aspect_impl)

        def _my_rule_impl(ctx):
            pass

        my_rule = rule(
            implementation = _my_rule_impl,
            attrs = {"dep": attr.label(aspects = [error_aspect])},
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":defs.bzl", "error_aspect", "my_rule")

        my_rule(name = "a")

        my_rule(
            name = "b",
            dep = ":a",
        )
        """);

    useConfiguration(
        "--allow_analysis_failures",
        "--define=FAIL_MESSAGE=abc",
        "--include_config_fragments_provider=direct");
    getConfiguredTarget("//a:b");
    RequiredConfigFragmentsProvider requiredFragments =
        getAspect("//a:defs.bzl%error_aspect").getProvider(RequiredConfigFragmentsProvider.class);

    assertThat(requiredFragments.defines()).containsExactly("FAIL_MESSAGE");
  }

  @Test
  public void configuredTargetInErrorWithAllowAnalysisFailures() throws Exception {
    scratch.file(
        "a/defs.bzl",
        """
        def _error_rule_impl(ctx):
            fail(ctx.var["FAIL_MESSAGE"])

        error_rule = rule(implementation = _error_rule_impl)
        """);
    scratch.file(
        "a/BUILD",
        """
        load(":defs.bzl", "error_rule")

        error_rule(name = "error")
        """);

    useConfiguration(
        "--allow_analysis_failures",
        "--define=FAIL_MESSAGE=abc",
        "--include_config_fragments_provider=direct");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:error").getProvider(RequiredConfigFragmentsProvider.class);

    assertThat(requiredFragments.defines()).containsExactly("FAIL_MESSAGE");
  }

  @Test
  public void aliasWithSelectResolvesToConfigSetting() throws Exception {
    scratch.file(
        "a/BUILD",
        String.format(
            """
            config_setting(
                name = "define_x",
                define_values = {"x": "1"},
            )

            config_setting(
                name = "k8",
                constraint_values = ["%s"]
            )

            alias(
                name = "alias_to_setting",
                actual = select({":define_x": ":k8"}),
            )

            genrule(
                name = "gen",
                outs = ["gen.out"],
                cmd = select({":alias_to_setting": "touch $@"}),
            )
            """,
            TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64"));

    useConfiguration(
        "--define=x=1",
        "--platforms=" + TestConstants.PLATFORM_LABEL,
        "--include_config_fragments_provider=transitive");
    RequiredConfigFragmentsProvider requiredFragments =
        getConfiguredTarget("//a:gen").getProvider(RequiredConfigFragmentsProvider.class);

    assertThat(requiredFragments.defines()).containsExactly("x");
  }
}
