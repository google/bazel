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
package com.google.devtools.build.lib.query2.testutil;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.testutil.TestConstants.PLATFORM_LABEL;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link PostAnalysisQueryEnvironment}. */
public abstract class PostAnalysisQueryTest<T> extends AbstractQueryTest<T> {

  // Also filter out platform dependencies.
  @Override
  protected String getDependencyCorrection() {
    return " - deps(" + PLATFORM_LABEL + ")";
  }

  static final String DEFAULT_UNIVERSE = "DEFAULT_UNIVERSE";

  @Before
  public final void disableOrderedResults() {
    helper.setOrderedResults(false);
  }

  @Before
  public final void setMockToolsConfig() {
    this.mockToolsConfig = getHelper().getMockToolsConfig();
  }

  /**
   * In production, cquery constructs the universe by parsing targets from the query expression and
   * building them at the top level. If this is not viable (e.g. component functions) or not desired
   * (e.g. somepath(//foo-built-in-target, //bar-built-in-host), the user must specify the
   * --universe_scope flag. Enforce the same behavior in this test by initializing universe scope to
   * an invalid target expression.
   */
  @Override
  protected String getDefaultUniverseScope() {
    return DEFAULT_UNIVERSE;
  }

  protected PostAnalysisQueryHelper<T> getHelper() {
    return (PostAnalysisQueryHelper<T>) helper;
  }

  /**
   * At the end of each eval, reset the universe scope to the default if the test doesn't use a
   * single universe scope.
   */
  @Override
  protected Set<T> eval(String query) throws Exception {
    maybeParseUniverseScope(query);
    Set<T> queryResult = super.eval(query);
    if (!getHelper().isWholeTestUniverse()) {
      helper.setUniverseScope(getDefaultUniverseScope());
    }
    return queryResult;
  }

  @Override
  protected EvalThrowsResult evalThrows(String query, boolean unconditionallyThrows)
      throws Exception {
    maybeParseUniverseScope(query);
    EvalThrowsResult queryResult = super.evalThrows(query, unconditionallyThrows);
    if (!getHelper().isWholeTestUniverse()) {
      helper.setUniverseScope(getDefaultUniverseScope());
    }
    return queryResult;
  }

  // Parse the universe if the universe has not been set manually through the helper.
  private void maybeParseUniverseScope(String query) throws Exception {
    if (!getHelper()
        .getUniverseScopeAsStringList()
        .equals(Collections.singletonList(getDefaultUniverseScope()))) {
      return;
    }
    QueryExpression expression = QueryParser.parse(query, getDefaultFunctions());
    Set<String> targetPatternSet = new LinkedHashSet<>();
    expression.collectTargetPatterns(targetPatternSet);
    if (!targetPatternSet.isEmpty()) {
      StringBuilder universeScope = new StringBuilder();
      for (String target : targetPatternSet) {
        universeScope.append(target).append(",");
      }
      helper.setUniverseScope(universeScope.toString());
    }
  }

  protected abstract HashMap<String, QueryFunction> getDefaultFunctions();

  protected abstract BuildConfigurationValue getConfiguration(T target);

  @Override
  protected boolean testConfigurableAttributes() {
    // ConfiguredTargetQuery knows the actual configuration, so it doesn't falsely overapproximate.
    return false;
  }

  @Override
  @Test
  public void testTargetLiteralWithMissingTargets() {
    getHelper().turnOffFailFast();
    TargetParsingException e =
        assertThrows(TargetParsingException.class, super::testTargetLiteralWithMissingTargets);
    assertThat(e)
        .hasMessageThat()
        .matches(
            TestUtils.createMissingTargetAssertionString(
                /* target= */ "b",
                /* packageStr= */ "a",
                helper.getRootDirectory().getPathString(),
                ""));
    assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
        .isEqualTo(FailureDetails.PackageLoading.Code.TARGET_MISSING);
  }

  @Override
  @Test
  public void testBadTargetLiterals() throws Exception {
    getHelper().turnOffFailFast();
    TargetParsingException e =
        assertThrows(TargetParsingException.class, super::testBadTargetLiterals);
    checkResultofBadTargetLiterals(e.getMessage(), e.getDetailedExitCode().getFailureDetail());
  }

  @SuppressWarnings("TruthIncompatibleType")
  @Override
  @Test
  public void testNoImplicitDeps() throws Exception {
    MockRule ruleWithImplicitDeps =
        () ->
            MockRule.define(
                "implicit_deps_rule",
                attr("explicit", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE),
                attr("explicit_with_default", LABEL)
                    .value(Label.parseCanonicalUnchecked("//test:explicit_with_default"))
                    .allowedFileTypes(FileTypeSet.ANY_FILE),
                attr("$implicit", LABEL).value(Label.parseCanonicalUnchecked("//test:implicit")),
                attr(":latebound", LABEL)
                    .value(
                        Attribute.LateBoundDefault.fromConstantForTesting(
                            Label.parseCanonicalUnchecked("//test:latebound"))));
    helper.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build());

    writeFile(
        "test/BUILD",
        """
        implicit_deps_rule(
            name = "my_rule",
            explicit = ":explicit",
            explicit_with_default = ":explicit_with_default",
        )

        cc_library(name = "explicit")

        cc_library(name = "explicit_with_default")

        cc_library(name = "implicit")

        cc_library(name = "latebound")
        """);

    final String implicits = "//test:implicit + //test:latebound";
    final String explicits = "//test:my_rule + //test:explicit + //test:explicit_with_default";

    // Check for implicit dependencies (late bound attributes, implicit attributes, platforms)
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(
            evalToListOfStrings(explicits + " + " + implicits + " + " + PLATFORM_LABEL));

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(evalToListOfStrings(explicits));
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsNoneIn(evalToListOfStrings(implicits));
  }

  @Test
  public void testNoImplicitDepsOnOutputFile() throws Exception {
    writeFile(
        "test/BUILD",
        """
load(":defs.bzl", "my_dep", "my_rule")
my_rule(
    name = "buildme",
    explicit_deps = [":explicit_dep"],
    output = "foo.out",
)
my_dep(name = "explicit_dep")
my_dep(name = "implicit_dep")
""");

    writeFile(
        "test/defs.bzl",
        """
my_dep = rule(
    implementation = lambda ctx: [],
    attrs = {},
)

def _impl(ctx):
    ctx.actions.write(ctx.outputs.output, "hello!")

my_rule = rule(
    implementation = _impl,
    attrs = {
        "output": attr.output(),
        "explicit_deps": attr.label_list(),
        "_implicit_deps": attr.label_list(default = ["//test:implicit_dep"]),
    },
)
""");

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(evalToListOfStrings("deps(//test:foo.out)"))
        .containsAtLeast("//test:foo.out", "//test:buildme", "//test:explicit_dep");
    assertThat(evalToListOfStrings("deps(//test:foo.out)")).doesNotContain("//test:implicit_dep");
  }

  @Test
  public void testImplicitDepsOnOutputFile() throws Exception {
    writeFile(
        "test/BUILD",
        """
load(":defs.bzl", "my_dep", "my_rule")
my_rule(
    name = "buildme",
    explicit_deps = [":explicit_dep"],
    output = "foo.out",
)
my_dep(name = "explicit_dep")
my_dep(name = "implicit_dep")
""");

    writeFile(
        "test/defs.bzl",
        """
my_dep = rule(
    implementation = lambda ctx: [],
    attrs = {},
)

def _impl(ctx):
    ctx.actions.write(ctx.outputs.output, "hello!")

my_rule = rule(
    implementation = _impl,
    attrs = {
        "output": attr.output(),
        "explicit_deps": attr.label_list(),
        "_implicit_deps": attr.label_list(default = ["//test:implicit_dep"]),
    },
)
""");

    helper.setQuerySettings();
    assertThat(evalToListOfStrings("deps(//test:foo.out)"))
        .containsAtLeast(
            "//test:foo.out", "//test:buildme", "//test:explicit_dep", "//test:implicit_dep");
  }

  @Test
  public void testNoImplicitDeps_toolchains() throws Exception {
    MockRule ruleWithImplicitDeps =
        () ->
            MockRule.define(
                "implicit_toolchain_deps_rule",
                (builder, env) ->
                    builder.addToolchainTypes(
                        ToolchainTypeRequirement.create(
                            Label.parseCanonicalUnchecked("//test:toolchain_type"))));
    helper.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build());

    writeFile(
        "test/toolchain.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo()
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
        )
        """);
    writeFile(
        "test/BUILD",
        """
        load(":toolchain.bzl", "test_toolchain")

        implicit_toolchain_deps_rule(
            name = "my_rule",
        )

        toolchain_type(name = "toolchain_type")

        toolchain(
            name = "toolchain",
            toolchain = ":toolchain_impl",
            toolchain_type = ":toolchain_type",
        )

        test_toolchain(name = "toolchain_impl")
        """);
    ((PostAnalysisQueryHelper<T>) helper).useConfiguration("--extra_toolchains=//test:toolchain");

    String implicits = "//test:toolchain_impl";
    String explicits = "//test:my_rule";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeast(explicits, implicits, evalToString(PLATFORM_LABEL));

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    ImmutableList<String> filteredDeps = evalToListOfStrings("deps(//test:my_rule)");
    assertThat(filteredDeps).contains(explicits);
    assertThat(filteredDeps).doesNotContain(implicits);
  }

  private void writeSimpleToolchain() throws Exception {
    writeFile(
        "test/toolchain_def.bzl",
        """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        test_toolchain = rule(
            implementation = _impl,
        )
        """);
    writeFile(
        "test/BUILD",
        """
        load("//test:toolchain_def.bzl", "test_toolchain")

        toolchain_type(name = "toolchain_type")

        toolchain(
            name = "toolchain",
            toolchain = ":toolchain_impl",
            toolchain_type = "//test:toolchain_type",
        )

        test_toolchain(name = "toolchain_impl")
        """);
  }

  @Test
  public void testNoImplicitDeps_starlark_toolchains() throws Exception {
    writeSimpleToolchain();
    writeFile(
        "test/rule/rule.bzl",
        """
        def _impl(ctx):
            return []

        implicit_toolchain_deps_rule = rule(
            implementation = _impl,
            toolchains = ["//test:toolchain_type"],
        )
        """);
    writeFile(
        "test/rule/BUILD",
        """
        load(":rule.bzl", "implicit_toolchain_deps_rule")

        implicit_toolchain_deps_rule(
            name = "my_rule",
        )
        """);
    ((PostAnalysisQueryHelper<T>) helper).useConfiguration("--extra_toolchains=//test:toolchain");

    String implicits = "//test:toolchain_impl";
    String explicits = "//test/rule:my_rule";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test/rule:my_rule)"))
        .containsAtLeast(explicits, implicits);

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    ImmutableList<String> filteredDeps = evalToListOfStrings("deps(//test/rule:my_rule)");
    assertThat(filteredDeps).contains(explicits);
    assertThat(filteredDeps).doesNotContain(implicits);
  }

  @Test
  public void testNoImplicitDeps_cc_toolchains() throws Exception {
    writeFile(
        "test/toolchain/toolchain_config.bzl",
        """
        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                toolchain_identifier = "mock-llvm-toolchain-k8",
                host_system_name = "mock-system-name-for-k8",
                target_system_name = "mock-target-system-name-for-k8",
                target_cpu = "k8",
                target_libc = "mock-libc-for-k8",
                compiler = "mock-compiler-for-k8",
                abi_libc_version = "mock-abi-libc-for-k8",
                abi_version = "mock-abi-version-for-k8",
            )

        cc_toolchain_config = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        """);
    writeFile(
        "test/toolchain/BUILD",
        "load(':toolchain_config.bzl', 'cc_toolchain_config')",
        "cc_toolchain_config(name = 'some-cc-toolchain-config')",
        "filegroup(name = 'nothing', srcs = [])",
        "cc_toolchain(",
        "    name = 'some_cc_toolchain_impl',",
        "    all_files = ':nothing',",
        "    as_files = ':nothing',",
        "    compiler_files = ':nothing',",
        "    dwp_files = ':nothing',",
        "    linker_files = ':nothing',",
        "    objcopy_files = ':nothing',",
        "    strip_files = ':nothing',",
        "    toolchain_config = ':some-cc-toolchain-config',",
        ")",
        "toolchain(",
        "    name = 'some_cc_toolchain',",
        "    toolchain = ':some_cc_toolchain_impl',",
        "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
        ")");
    writeFile(
        "test/BUILD",
        """
        cc_library(
            name = "my_rule",
            srcs = ["whatever.cpp"],
        )
        """);
    ((PostAnalysisQueryHelper<T>) helper)
        .useConfiguration("--extra_toolchains=//test/toolchain:some_cc_toolchain");

    String implicits = "//test/toolchain:some_cc_toolchain_impl";
    String explicits = "//test:my_rule";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeast(explicits, implicits, evalToString(PLATFORM_LABEL));

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    ImmutableList<String> filteredDeps = evalToListOfStrings("deps(//test:my_rule)");
    assertThat(filteredDeps).contains(explicits);
    assertThat(filteredDeps).doesNotContain(implicits);
  }

  // Regression test for b/148550864
  @Test
  public void testNoImplicitDeps_platformDeps() throws Exception {
    MockRule simpleRule = () -> MockRule.define("simple_rule");
    helper.useRuleClassProvider(setRuleClassProviders(simpleRule).build());

    writeFile(
        "test/BUILD",
        """
        simple_rule(name = "my_rule")

        platform(name = "host_platform")

        platform(name = "execution_platform")
        """);

    ((PostAnalysisQueryHelper<T>) helper)
        .useConfiguration(
            "--host_platform=//test:host_platform",
            "--extra_execution_platforms=//test:execution_platform");

    // Check for platform dependencies
    assertThat(evalToListOfStrings("deps(//test:my_rule)"))
        .containsAtLeastElementsIn(
            evalToListOfStrings("//test:execution_platform + //test:host_platform"));
    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(evalToListOfStrings("deps(//test:my_rule)")).containsExactly("//test:my_rule");
  }

  //  Regression test for b/275502129.
  @Test
  public void testNoImplicitDepsFromAutoExecGroups_autoExecGroupsEnabled() throws Exception {
    writeSimpleToolchain();
    writeFile(
        "test/aeg/defs.bzl",
        """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test:toolchain_type"],
        )
        """);
    writeFile(
        "test/aeg/BUILD",
        """
        load("//test/aeg:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    ((PostAnalysisQueryHelper<T>) helper)
        .useConfiguration("--incompatible_auto_exec_groups", "--extra_toolchains=//test:all");

    String implicits = "//test:toolchain_impl";
    String explicits = "//test/aeg:custom_rule_name";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test/aeg:custom_rule_name)"))
        .containsAtLeast(explicits, implicits);

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    ImmutableList<String> filteredDeps = evalToListOfStrings("deps(//test/aeg:custom_rule_name)");
    assertThat(filteredDeps).contains(explicits);
    assertThat(filteredDeps).doesNotContain(implicits);
  }

  //  Regression test for b/275502129.
  @Test
  public void testNoImplicitDepsFromCustomExecGroups_autoExecGroupsEnabled() throws Exception {
    writeSimpleToolchain();
    writeFile(
        "test/aeg/defs.bzl",
        """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            exec_groups = {
                "custom_exec_group": exec_group(
                    toolchains = ["//test:toolchain_type"],
                ),
            },
        )
        """);
    writeFile(
        "test/aeg/BUILD",
        """
        load("//test/aeg:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        """);
    ((PostAnalysisQueryHelper<T>) helper)
        .useConfiguration("--incompatible_auto_exec_groups", "--extra_toolchains=//test:all");

    String implicits = "//test:toolchain_impl";
    String explicits = "//test/aeg:custom_rule_name";

    // Check for implicit toolchain dependencies
    assertThat(evalToListOfStrings("deps(//test/aeg:custom_rule_name)"))
        .containsAtLeast(explicits, implicits);

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    ImmutableList<String> filteredDeps = evalToListOfStrings("deps(//test/aeg:custom_rule_name)");
    assertThat(filteredDeps).contains(explicits);
    assertThat(filteredDeps).doesNotContain(implicits);
  }

  @Override
  @Test
  public void testNoImplicitDeps_computedDefault() throws Exception {
    MockRule computedDefaultRule =
        () ->
            MockRule.define(
                "computed_default_rule",
                attr("conspiracy", Type.STRING).value("space jam was a documentary"),
                attr("dep", LABEL)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .value(
                        new Attribute.ComputedDefault("conspiracy") {
                          @Override
                          public Object getDefault(AttributeMap rule) {
                            return rule.get("conspiracy", Type.STRING)
                                    .equals("space jam was a documentary")
                                ? Label.parseCanonicalUnchecked("//test:foo")
                                : null;
                          }
                        }));

    helper.useRuleClassProvider(setRuleClassProviders(computedDefaultRule).build());

    writeFile(
        "test/BUILD",
        """
        cc_library(name = "foo")

        computed_default_rule(name = "my_rule")
        """);

    String target = "//test:my_rule";

    assertThat(evalToListOfStrings("deps(" + target + ")")).contains("//test:foo");
    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertThat(eval("deps(" + target + ")")).isEqualTo(eval(target));
  }

  @Override
  @Test
  public void testLet() throws Exception {
    getHelper().setWholeTestUniverseScope("//a,//b,//c,//d");
    super.testLet();
  }

  @Override
  @Test
  public void testSet() throws Exception {
    getHelper().setWholeTestUniverseScope("//a:*,//b:*,//c:*,//d:*");
    super.testSet();
  }

  /** Attribute transition factory on --foo */
  protected static class FooPatchAttrTransitionFactory
      implements TransitionFactory<AttributeTransitionData> {
    String toOption;
    String name;

    public FooPatchAttrTransitionFactory(String toOption, String name) {
      this.toOption = toOption;
      this.name = name;
    }

    public FooPatchAttrTransitionFactory(String toOption) {
      this(toOption, "FooPatchAttrTransitionFactory");
    }

    @Override
    public ConfigurationTransition create(AttributeTransitionData unused) {
      return new FooPatchTransition(this.toOption, this.name);
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ATTRIBUTE;
    }
  }

  /** Rule transition factory on --foo */
  protected static class FooPatchRuleTransitionFactory
      implements TransitionFactory<RuleTransitionData> {
    String toOption;
    String name;

    public FooPatchRuleTransitionFactory(String toOption, String name) {
      this.toOption = toOption;
      this.name = name;
    }

    public FooPatchRuleTransitionFactory(String toOption) {
      this(toOption, "FooPatchRuleTransitionFactory");
    }

    @Override
    public ConfigurationTransition create(RuleTransitionData unused) {
      return new FooPatchTransition(this.toOption, this.name);
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.RULE;
    }
  }

  /** PatchTransition on --foo */
  protected static class FooPatchTransition implements PatchTransition {
    String toOption;
    String name;

    public FooPatchTransition(String toOption, String name) {
      this.toOption = toOption;
      this.name = name;
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
      return ImmutableSet.of(DummyTestOptions.class);
    }

    @Override
    public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
      BuildOptionsView result = options.clone();
      result.get(DummyTestOptions.class).foo = toOption;
      return result.underlying();
    }
  }

  /** Split transition factory on --foo */
  protected static class FooSplitTransitionFactory
      implements TransitionFactory<AttributeTransitionData> {
    String toOption1;
    String toOption2;

    public FooSplitTransitionFactory(String toOption1, String toOptions2) {
      this.toOption1 = toOption1;
      this.toOption2 = toOptions2;
    }

    @Override
    public ConfigurationTransition create(AttributeTransitionData data) {
      return new SplitTransition() {
        @Override
        public String getName() {
          return "FooSplitTransitionFactory";
        }

        @Override
        public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
          return ImmutableSet.of(DummyTestOptions.class);
        }

        @Override
        public ImmutableMap<String, BuildOptions> split(
            BuildOptionsView options, EventHandler eventHandler) {
          BuildOptionsView result1 = options.clone();
          BuildOptionsView result2 = options.clone();
          result1.get(DummyTestOptions.class).foo = toOption1;
          result2.get(DummyTestOptions.class).foo = toOption2;
          return ImmutableMap.of("result1", result1.underlying(), "result2", result2.underlying());
        }
      };
    }

    @Override
    public TransitionType transitionType() {
      return TransitionType.ATTRIBUTE;
    }

    @Override
    public boolean isSplit() {
      return true;
    }
  }

  @Test
  public void testMultipleTopLevelConfigurations() throws Exception {
    MockRule transitionedRule =
        () ->
            MockRule.define(
                "transitioned_rule",
                (builder, env) ->
                    builder.cfg(new FooPatchRuleTransitionFactory("SET BY PATCH")).build());

    MockRule untransitionedRule = () -> MockRule.define("untransitioned_rule");

    helper.useRuleClassProvider(
        setRuleClassProviders(transitionedRule, untransitionedRule).build());

    writeFile(
        "test/BUILD",
        """
        transitioned_rule(name = "transitioned_rule")

        untransitioned_rule(name = "untransitioned_rule")
        """);

    Set<T> result = eval("//test:transitioned_rule+//test:untransitioned_rule");

    assertThat(result).hasSize(2);

    Iterator<T> resultIterator = result.iterator();
    assertThat(getConfiguration(resultIterator.next()))
        .isNotEqualTo(getConfiguration(resultIterator.next()));
  }

  @Test
  public abstract void testMultipleTopLevelConfigurations_nullConfigs() throws Exception;

  @Test
  public void testMultipleTopLevelConfigurations_multipleConfigsPrefersTopLevel() throws Exception {
    MockRule ruleWithTransitionAndDep =
        () ->
            MockRule.define(
                "rule_with_transition_and_dep",
                (builder, env) ->
                    builder
                        .cfg(new FooPatchRuleTransitionFactory("SET BY PATCH"))
                        .addAttribute(
                            attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE).build())
                        .build());

    MockRule simpleRule = () -> MockRule.define("simple_rule");

    helper.useRuleClassProvider(
        setRuleClassProviders(ruleWithTransitionAndDep, simpleRule).build());

    writeFile(
        "test/BUILD",
        """
        rule_with_transition_and_dep(
            name = "top-level",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        """);

    helper.setUniverseScope("//test:*");

    // `//test:dep` has two configurations.
    assertThat(eval("//test:dep")).hasSize(2);
  }

  @Test
  public void inconsistentSkyQueryIncremental() throws Exception {
    getHelper().setSyscallCache(TestUtils.makeDisappearingFileCache("bar/BUILD"));
    getHelper().turnOffFailFast();
    writeFile("foo/BUILD");
    writeFile("bar/BUILD");
    getHelper().setUniverseScope("//bar/...");
    TargetParsingException targetParsingException =
        assertThrows(TargetParsingException.class, () -> eval("set()"));
    assertThat(
            targetParsingException
                .getDetailedExitCode()
                .getFailureDetail()
                .getPackageLoading()
                .getCode())
        .isEqualTo(FailureDetails.PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR);
    getHelper().setUniverseScope("//foo/...");
    QueryException queryException = assertThrows(QueryException.class, () -> eval("bar"));
    assertThat(queryException.getFailureDetail().getTargetPatterns().getCode())
        .isEqualTo(FailureDetails.TargetPatterns.Code.CANNOT_DETERMINE_TARGET_FROM_FILENAME);
  }

  @Test
  public void labelPointsToMultipleConfiguredTargets() throws Exception {}

  private void writeSimpleTarget() throws Exception {
    MockRule simpleRule =
        () ->
            MockRule.define(
                "simple_rule", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE));
    helper.useRuleClassProvider(setRuleClassProviders(simpleRule).build());

    writeFile("test/BUILD", "simple_rule(name = 'target')");
  }

  @Test
  public void aliasMinus() throws Exception {
    MockRule simpleRule =
        () ->
            MockRule.define(
                "simple_rule", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE));
    helper.useRuleClassProvider(setRuleClassProviders(simpleRule).build());

    writeFile(
        "p/BUILD",
        "simple_rule(name = 'dep')",
        "alias(name = 'alias', actual = 'dep')",
        "simple_rule(name = 'user', dep = ':alias')");
    assertThat(evalToString("deps(//p:alias) - deps(//p:dep)")).isEqualTo("//p:alias");
    // The following assertion fails if the expression `//p:alias` doesn't represent two configured
    // targets -- one configured without TestOptions (trimmed) and the other configured with one
    // (untrimmed). The untrimmed configured target is from the top-level expression, whereas the
    // trimmed one is from `//p:user`'s dependency.
    assertThat(evalToString("deps(//p:user) - deps(//p:alias)")).isEqualTo("//p:user");
  }

  @Test
  public void testVisibleFunctionDoesNotWork() throws Exception {
    writeSimpleTarget();
    EvalThrowsResult result = evalThrows("visible(//test:target, //test:*)", true);
    assertThat(result.getMessage()).isEqualTo("visible() is not supported on configured targets");
    assertConfigurableQueryCode(result.getFailureDetail(), Code.VISIBLE_FUNCTION_NOT_SUPPORTED);
  }

  @Test
  public void testSiblingsFunctionDoesNotWork() throws Exception {
    writeSimpleTarget();
    EvalThrowsResult result = evalThrows("siblings(//test:target)", true);
    assertThat(result.getMessage()).isEqualTo("siblings() not supported for post analysis queries");
    assertConfigurableQueryCode(result.getFailureDetail(), Code.SIBLINGS_FUNCTION_NOT_SUPPORTED);
  }

  @Test
  public void testBuildfilesFunctionDoesNotWork() throws Exception {
    writeSimpleTarget();
    EvalThrowsResult result = evalThrows("buildfiles(//test:target)", true);
    assertThat(result.getMessage())
        .isEqualTo("buildfiles() doesn't make sense for the configured target graph");
    assertConfigurableQueryCode(result.getFailureDetail(), Code.BUILDFILES_FUNCTION_NOT_SUPPORTED);
  }

  @Override
  @Test
  public void testGenqueryScope() throws Exception {
    runGenqueryScopeTest(true);
  }

  // LabelListAttr not currently supported.
  @Override
  public void testLabelsOperator() {}

  // Wants to get the query environment without evaluation -- not worth it.
  @Override
  @Test
  public void testEqualityOfOrderedThreadSafeImmutableSet() {}

  // The actual crosstool-related targets depended on are not the nominal crosstool label the test
  // expects.

  // "Extended rules" don't play nicely with actual analysis.
  @Override
  public void testNoDepsOnAspectAttributeWhenAspectMissing() {}

  @Override
  public void testNoDepsOnAspectAttributeWithNoImpicitDeps() {}

  @Override
  public void testHaveDepsOnAspectsAttributes() {}

  // Can't handle loading-phase errors.
  @Override
  public void testStrictTestSuiteWithFile() {}

  @Override
  public void testTestsOperatorReportsMissingTargets() {}

  @Override
  public void testCycleInStarlark() {}

  @Override
  public void testCycleInStarlarkParentDir() {}

  @Override
  public void testCycleInSubpackage() {}

  @Override
  public void testRegression1309697() {}

  @Override
  public void badRuleInDeps() {}

  @Override
  public void boundedRdepsWithError() {}

  // Can't handle cycles.
  @Override
  public void testDotDotDotWithCycle() {}

  @Override
  public void testDotDotDotWithUnrelatedCycle() {}

  // ...
  @Override
  public void testQueryTimeLoadingTargetsBelowNonPackageDirectory() {}

  @Override
  public void testQueryTimeLoadingOfTargetsBelowPackageHappyPath() {}

  @Override
  public void testQueryTimeLoadingTargetsBelowMissingPackage() {}

  // These tests clear the universe, getting rid of mock tools that are needed for analysis. Disable
  // at least for now. Other than testSlashSlashDotDotDot, they're only testing visibility anyway.

  @Override
  public void testSlashSlashDotDotDot() {}

  @Override
  public void testVisible_default_private() {}

  @Override
  public void testVisible_default_public() {}

  @Override
  public void testPackageGroupAllBeneath() {}

  @Override
  public void testVisible_java_javatests() {}

  @Override
  public void testVisible_java_javatests_different_package() {}

  @Override
  public void testVisible_javatests_java() {}

  @Override
  public void testVisible_package_group() {}

  @Override
  public void testVisible_package_group_include() {}

  @Override
  public void testVisible_package_group_invisible() {}

  @Override
  public void testVisible_private_same_package() {}

  @Override
  public void testVisible_simple_different_subpackages() {}

  @Override
  public void testVisible_simple_package() {}

  @Override
  public void testVisible_simple_private() {}

  @Override
  public void testVisible_simple_public() {}

  @Override
  public void testVisible_simple_subpackages() {}

  // test_suite rules aren't supported, since they're not configured targets.

  @Override
  public void testTestsOperatorFiltersByNegativeTag() {}

  @Override
  public void testTestsOperatorCrossesPackages() {}

  @Override
  public void testTestsOperatorHandlesCyclesGracefully() {}

  @Override
  public void testTestSuiteInTestsAttributeAndViceVersa() {}

  @Override
  public void testAmbiguousAllResolvesToTestSuiteNamedAll() {}

  @Override
  public void testTestSuiteWithFile() {}

  @Override
  public void testTestsOperatorFiltersByTagSizeAndEnv() {}

  @Override
  public void testTestsOperatorExpandsTestsAndExcludesNonTests() {}

  // buildfiles() operator.
  @Override
  public void testBuildFiles() {}

  @Override
  public void testBuildFilesDoesNotReturnVisibilityOfBUILD() {}

  @Override
  public void testBuildFilesDoesNotReturnVisibilityOfRule() {}

  @Override
  public void testBuildfilesOfBuildfiles() {}

  @Override
  public void testBuildfilesWithDuplicates() {}

  @Override
  public void bzlPackageBadDueToBrokenLoad() {}

  @Override
  public void bzlPackageBadDueToBrokenSyntax() {}

  @Override
  public void testBuildfilesContainingScl() {}

  @Override
  public void buildfilesBazel() {}

  @Override
  public void testTargetsFromBuildfilesAndRealTargets() {}

  // siblings() operator.

  @Override
  public void testSiblings_duplicatePackages() {}

  @Override
  public void testSiblings_samePackageRdeps() {}

  @Override
  public void testSiblings_matchesTargetNamedAll() {}

  @Override
  public void testSiblings_simple() {}

  @Override
  public void testSiblings_withBuildfiles() {}

  // same_pkg_direct_rdeps() operator.

  @Override
  public void testSamePackageRdeps_simple() throws Exception {}

  @Override
  public void testSamePackageRdeps_duplicate() throws Exception {}

  @Override
  public void testSamePackageRdeps_two() throws Exception {}

  @Override
  public void testSamePackageRdeps_twoPackages() throws Exception {}

  @Override
  public void testSamePackageRdeps_crissCross() throws Exception {}

  // We eagerly load all packages, so can't test that we don't load one.
  @Override
  @Test
  public void testWildcardsDontLoadUnnecessaryPackages() {}

  @Override
  @Test
  public void boundedDepsWithError() {}

  // Query needs a graph.
  @Override
  @Test
  public void testGraphOrderOfWildcards() {}

  // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
  // would never occur because the visibility error would occur first.
  @Override
  @Test
  public void testVisibleWithNonPackageGroupVisibility() throws Exception {}

  // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
  // would never occur because the visibility error would occur first.
  @Override
  @Test
  public void testVisibleWithPackageGroupWithNonPackageGroupIncludes() throws Exception {}

  // We don't support --nodep_deps=false.
  @Override
  @Test
  public void testNodepDeps_false() throws Exception {}

  // package_group instances have a null configuration and are filtered out by --host_deps=false.
  @Override
  @Test
  public void testDefaultVisibilityReturnedInDeps_nonEmptyDependencyFilter() throws Exception {}

  protected static void assertConfigurableQueryCode(FailureDetail failureDetail, Code code) {
    assertThat(failureDetail.getConfigurableQuery().getCode()).isEqualTo(code);
  }
}
