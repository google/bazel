// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RunEnvironmentInfo;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.configuredtargets.FileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider;
import com.google.devtools.build.lib.analysis.test.AnalysisTestResultInfo;
import com.google.devtools.build.lib.analysis.test.BaselineCoverageAction;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.FunctionSplitTransitionAllowlist;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import java.io.IOException;
import java.util.List;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for Starlark. */
@RunWith(JUnit4.class)
public class StarlarkIntegrationTest extends BuildViewTestCase {
  protected boolean keepGoing() {
    return false;
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.build();
  }

  @Before
  public void setupMyInfo() throws IOException {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");

    scratch.file("myinfo/BUILD");
  }

  private StructImpl getMyInfoFromTarget(ConfiguredTarget configuredTarget) throws Exception {
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo");
    return (StructImpl) configuredTarget.get(key);
  }

  @Test
  public void testRemoteLabelAsDefaultAttributeValue() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def _impl(ctx):
          pass
        my_rule = rule(implementation = _impl,
            attrs = { 'dep' : attr.label_list(default=["@r//:t"]) })
        """);

    // We are only interested in whether the label string in the default value can be converted
    // to a proper Label without an exception (see GitHub issue #1442).
    // Consequently, we expect getTarget() to fail later since the repository does not exist.
    checkError(
        "test/starlark",
        "the_rule",
        "No repository visible as '@r'",
        "load('//test/starlark:extension.bzl', 'my_rule')",
        "",
        "my_rule(name='the_rule')");
  }

  @Test
  public void testMainRepoLabelWorkspaceRoot() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return [MyInfo(result = ctx.label.workspace_root)]
        my_rule = rule(implementation = _impl, attrs = { })
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'my_rule')
        my_rule(name='t')
        """);

    ConfiguredTarget myTarget = getConfiguredTarget("//test/starlark:t");
    String result = (String) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result).isEmpty();
  }

  @Test
  public void testExternalRepoLabelWorkspaceRoot_subdirRepoLayout() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "bazel_dep(name='r')", "local_path_override(module_name='r', path='/r')");

    scratch.file("/r/MODULE.bazel", "module(name='r')");
    scratch.file(
        "/r/test/starlark/extension.bzl",
        """
        load('@@//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return [MyInfo(result = ctx.label.workspace_root)]
        my_rule = rule(implementation = _impl, attrs = { })
        """);
    scratch.file(
        "/r/BUILD",
        """
        load('//:test/starlark/extension.bzl', 'my_rule')
        my_rule(name='t')
        """);

    // Required since we have a new WORKSPACE file.
    invalidatePackages(true);

    ConfiguredTarget myTarget = getConfiguredTarget("@@r+//:t");
    String result = (String) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result).isEqualTo("external/r+");
  }

  @Test
  public void testExternalRepoLabelWorkspaceRoot_siblingRepoLayout() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "bazel_dep(name='r')", "local_path_override(module_name='r', path='/r')");

    scratch.file("/r/MODULE.bazel", "module(name='r')");
    scratch.file(
        "/r/test/starlark/extension.bzl",
        """
        load('@@//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return [MyInfo(result = ctx.label.workspace_root)]
        my_rule = rule(implementation = _impl, attrs = { })
        """);
    scratch.file(
        "/r/BUILD",
        """
        load('//:test/starlark/extension.bzl', 'my_rule')
        my_rule(name='t')
        """);

    // Required since we have a new WORKSPACE file.
    invalidatePackages(true);

    setBuildLanguageOptions("--experimental_sibling_repository_layout");

    ConfiguredTarget myTarget = getConfiguredTarget("@@r+//:t");
    String result = (String) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result).isEqualTo("../r+");
  }

  @Test
  public void testSameMethodNames() throws Exception {
    // The alias feature of load() may hide the fact that two methods in the stack trace have the
    // same name. This is perfectly legal as long as these two methods are actually distinct.
    // Consequently, no "Recursion was detected" error must be thrown.
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//test/starlark:other.bzl', other_impl = 'impl')
        def impl(ctx):
          other_impl(ctx)
        empty = rule(implementation = impl)
        """);
    scratch.file(
        "test/starlark/other.bzl",
        """
        def impl(ctx):
          print('This rule does nothing')
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'empty')
        empty(name = 'test_target')
        """);

    getConfiguredTarget("//test/starlark:test_target");
  }

  private Rule getRuleForTarget(String targetName) throws Exception {
    ConfiguredTargetAndData target = getConfiguredTargetAndData("//test/starlark:" + targetName);
    return target.getTargetForTesting().getAssociatedRule();
  }

  @Test
  public void testMacroHasGeneratorAttributes() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def _impl(ctx):
          print('This rule does nothing')

        empty = rule(implementation = _impl)
        no_macro = rule(implementation = _impl)

        def macro(name, visibility=None):
          empty(name = name, visibility=visibility)
        def native_macro(name):
          native.cc_library(name = name + '_suffix')
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', macro_rule = 'macro', no_macro_rule = 'no_macro',
          native_macro_rule = 'native_macro')
        macro_rule(name = 'macro_target')
        no_macro_rule(name = 'no_macro_target')
        native_macro_rule(name = 'native_macro_target')
        """);

    Rule withMacro = getRuleForTarget("macro_target");
    assertThat(withMacro.getAttr("generator_name")).isEqualTo("macro_target");
    assertThat(withMacro.getAttr("generator_function")).isEqualTo("macro");
    assertThat(withMacro.getAttr("generator_location")).isEqualTo("test/starlark/BUILD:3:11");

    // Attributes are only set when the rule was created by a macro
    Rule noMacro = getRuleForTarget("no_macro_target");
    assertThat(noMacro.getAttr("generator_name")).isEqualTo("");
    assertThat(noMacro.getAttr("generator_function")).isEqualTo("");
    assertThat(noMacro.getAttr("generator_location")).isEqualTo("");

    Rule nativeMacro = getRuleForTarget("native_macro_target_suffix");
    assertThat(nativeMacro.getAttr("generator_name")).isEqualTo("native_macro_target");
    assertThat(nativeMacro.getAttr("generator_function")).isEqualTo("native_macro");
    assertThat(nativeMacro.getAttr("generator_location")).isEqualTo("test/starlark/BUILD:5:18");
  }

  @Test
  public void sanityCheckUserDefinedTestRule() throws Exception {
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _impl(ctx):
          output = ctx.outputs.out
          ctx.actions.write(output = output, content = 'hello', is_executable=True)
          return [DefaultInfo(executable = output)]

        fake_test = rule(
          implementation = _impl,
          test=True,
          attrs = {'_xcode_config': attr.label(default = configuration_field(
          fragment = 'apple', name = "xcode_config_label"))},
          outputs = {"out": "%{name}.txt"})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'fake_test')
        fake_test(name = 'test_name')
        """);
    getConfiguredTarget("//test/starlark:test_name");
  }

  @Test
  public void testOutputGroupsDeclaredProvider() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  f = ctx.attr.dep[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
        "  return [MyInfo(result = f),",
        "      OutputGroupInfo(my_group = f)]",
        "my_rule = rule(implementation = _impl,",
        "    attrs = { 'dep' : attr.label() })");
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl',  'my_rule')
        cc_binary(name = 'lib', data = ['a.txt'])
        my_rule(name='my', dep = ':lib')
        """);
    NestedSet<Artifact> hiddenTopLevelArtifacts =
        OutputGroupInfo.get(getConfiguredTarget("//test/starlark:lib"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL);
    ConfiguredTarget myTarget = getConfiguredTarget("//test/starlark:my");
    Depset result = (Depset) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result.getSet(Artifact.class).toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(OutputGroupInfo.get(myTarget).getOutputGroup("my_group").toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
  }


  @Test
  public void testOutputGroupsAsDictionary() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  f = ctx.attr.dep.output_groups['_hidden_top_level" + INTERNAL_SUFFIX + "']",
        "  has_key1 = '_hidden_top_level" + INTERNAL_SUFFIX + "' in ctx.attr.dep.output_groups",
        "  has_key2 = 'foobar' in ctx.attr.dep.output_groups",
        "  all_keys = [k for k in ctx.attr.dep.output_groups]",
        "  return [MyInfo(result = f, ",
        "                has_key1 = has_key1,",
        "                has_key2 = has_key2,",
        "                all_keys = all_keys),",
        "      OutputGroupInfo(my_group = f)]",
        "my_rule = rule(implementation = _impl,",
        "    attrs = { 'dep' : attr.label() })");
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl',  'my_rule')
        cc_binary(name = 'lib', data = ['a.txt'])
        my_rule(name='my', dep = ':lib')
        """);
    NestedSet<Artifact> hiddenTopLevelArtifacts =
        OutputGroupInfo.get(getConfiguredTarget("//test/starlark:lib"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL);
    ConfiguredTarget myTarget = getConfiguredTarget("//test/starlark:my");
    StructImpl myInfo = getMyInfoFromTarget(myTarget);
    Depset result = (Depset) myInfo.getValue("result");
    assertThat(result.getSet(Artifact.class).toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(OutputGroupInfo.get(myTarget).getOutputGroup("my_group").toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(myInfo.getValue("has_key1")).isEqualTo(Boolean.TRUE);
    assertThat(myInfo.getValue("has_key2")).isEqualTo(Boolean.FALSE);
    assertThat((Sequence) myInfo.getValue("all_keys"))
        .containsExactly(
            OutputGroupInfo.HIDDEN_TOP_LEVEL,
            OutputGroupInfo.COMPILATION_PREREQUISITES,
            OutputGroupInfo.FILES_TO_COMPILE,
            OutputGroupInfo.TEMP_FILES,
            OutputGroupInfo.VALIDATION,
            "module_files");
  }

  @Test
  public void testOutputGroupsAsDictionaryPipe() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  g = ctx.attr.dep.output_groups['_hidden_top_level" + INTERNAL_SUFFIX + "']",
        "  return [MyInfo(result = g),",
        "      OutputGroupInfo(my_group = g)]",
        "my_rule = rule(implementation = _impl,",
        "    attrs = { 'dep' : attr.label() })");
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl',  'my_rule')
        cc_binary(name = 'lib', data = ['a.txt'])
        my_rule(name='my', dep = ':lib')
        """);
    NestedSet<Artifact> hiddenTopLevelArtifacts =
        OutputGroupInfo.get(getConfiguredTarget("//test/starlark:lib"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL);
    ConfiguredTarget myTarget = getConfiguredTarget("//test/starlark:my");
    Depset result = (Depset) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result.getSet(Artifact.class).toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(OutputGroupInfo.get(myTarget).getOutputGroup("my_group").toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
  }

  @Test
  public void testOutputGroupsDeclaredProviderWithList() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        "load('//myinfo:myinfo.bzl', 'MyInfo')",
        "def _impl(ctx):",
        "  f = ctx.attr.dep[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
        "  g = f.to_list()",
        "  return [MyInfo(result = f),",
        "      OutputGroupInfo(my_group = g, my_empty_group = [])]",
        "my_rule = rule(implementation = _impl,",
        "    attrs = { 'dep' : attr.label() })");
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl',  'my_rule')
        cc_binary(name = 'lib', data = ['a.txt'])
        my_rule(name='my', dep = ':lib')
        """);
    NestedSet<Artifact> hiddenTopLevelArtifacts =
        OutputGroupInfo.get(getConfiguredTarget("//test/starlark:lib"))
            .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL);
    ConfiguredTarget myTarget = getConfiguredTarget("//test/starlark:my");
    Depset result = (Depset) getMyInfoFromTarget(myTarget).getValue("result");
    assertThat(result.getSet(Artifact.class).toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(OutputGroupInfo.get(myTarget).getOutputGroup("my_group").toList())
        .containsExactlyElementsIn(hiddenTopLevelArtifacts.toList());
    assertThat(OutputGroupInfo.get(myTarget).getOutputGroup("my_empty_group").toList()).isEmpty();
  }

  @Test
  public void testStackTraceErrorInFunction() throws Exception {
    runStackTraceTest(
        "  str.index(1)",
        // The error occurs in a built-in, so the backtrace omits the
        // final frame and instead prints "Error in index:".
        "Error in index: in call to index(), parameter 'sub' got value of type 'int', want"
            + " 'string'");
  }

  @Test
  public void testStackTraceMissingMethod() throws Exception {
    // The error occurs in a Starlark operator, so the backtrace includes
    // the final frame, with a source location. We report "Error: ...".
    runStackTraceTest(
        "  (None   ).index(1)", "Error: 'NoneType' value has no field or method 'index'");
  }

  // Precondition: 'expr' must have a 2-space indent and an error at column 12. Ugh.
  // TODO(adonovan): rewrite this and similar tests as assertions over the error data
  // structure, not its formatting.
  protected void runStackTraceTest(String expr, String errorMessage) throws Exception {
    reporter.removeHandler(failFastHandler);
    // The stack doesn't include source lines because we haven't told the relevant
    // call to EvalException.getMessageWithStack how to read from scratch.
    String expectedTrace =
        Joiner.on("\n")
            .join(
                "ERROR /workspace/test/starlark/BUILD:3:12: in custom_rule rule"
                    + " //test/starlark:cr: ",
                "Traceback (most recent call last):",
                "\tFile \"/workspace/test/starlark/extension.bzl\", line 6, column 6, in"
                    + " custom_rule_impl",
                "\t\tfoo()",
                "\tFile \"/workspace/test/starlark/extension.bzl\", line 9, column 6, in foo",
                "\t\tbar(2, 4)",
                "\tFile \"/workspace/test/starlark/extension.bzl\", line 11, column 8, in bar",
                "\t\tfirst(x, y, z)",
                "\tFile \"/workspace/test/starlark/functions.bzl\", line 2, column 9, in first",
                "\t\tsecond(a, b)",
                "\tFile \"/workspace/test/starlark/functions.bzl\", line 5, column 8, in second",
                "\t\tthird('legal')",
                "\tFile \"/workspace/test/starlark/functions.bzl\", line 7, column 12, in third",
                "\t\t" + expr.stripLeading(),
                errorMessage);
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//test/starlark:functions.bzl', 'first')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          ftb = depset(attr1)
          foo()
          return [MyInfo(provider_key = ftb)]
        def foo():
          bar(2, 4)
        def bar(x,y,z=1):
          first(x, y, z)
        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)})
        """);
    scratch.file(
        "test/starlark/functions.bzl",
        "def first(a, b, c):",
        "  second(a, b)",
        "  third(b)",
        "def second(a, b):",
        "  third('legal')",
        "def third(str):",
        expr); // 2-space indent, error at column 12
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    getConfiguredTarget("//test/starlark:cr");
    assertContainsEvent(expectedTrace);
  }

  @Test
  public void testFilesToBuild() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          ftb = depset(attr1)
          return [DefaultInfo(runfiles = ctx.runfiles(), files = ftb)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("a.txt");
  }

  @Test
  public void testRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          rf = ctx.runfiles(files = attr1)
          return [DefaultInfo(runfiles = rf)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .containsExactly("a.txt");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .containsExactly("a.txt");
  }

  @Test
  public void testAccessRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          runfiles = ctx.attr.x.default_runfiles.files
          return [DefaultInfo(files = runfiles)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'x': attr.label(allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        cc_library(name = 'lib', data = ['a.txt'])
        custom_rule(name = 'cr1', x = ':lib')
        custom_rule(name = 'cr2', x = 'b.txt')
        """);

    scratch.file("test/starlark/a.txt");
    scratch.file("test/starlark/b.txt");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr1");
    List<String> baseArtifactNames =
        ActionsTestUtil.baseArtifactNames(target.getProvider(FileProvider.class).getFilesToBuild());
    assertThat(baseArtifactNames).containsExactly("a.txt");

    target = getConfiguredTarget("//test/starlark:cr2");
    baseArtifactNames =
        ActionsTestUtil.baseArtifactNames(target.getProvider(FileProvider.class).getFilesToBuild());
    assertThat(baseArtifactNames).isEmpty();
  }

  @Test
  public void testStatefulRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          rf1 = ctx.runfiles(files = attr1)
          rf2 = ctx.runfiles()
          return [DefaultInfo(data_runfiles = rf1, default_runfiles = rf2)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory = True, allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    assertThat(target.getProvider(RunfilesProvider.class).getDefaultRunfiles().isEmpty()).isTrue();
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .containsExactly("a.txt");
  }

  @Test
  public void testExecutableGetsInRunfilesAndFilesToBuild() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          ctx.actions.write(output = ctx.outputs.executable, content = 'echo hello')
          rf = ctx.runfiles(ctx.files.data)
          return [DefaultInfo(runfiles = rf)]

        custom_rule = rule(implementation = custom_rule_impl, executable = True,
          attrs = {'data': attr.label_list(allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', data = [':a.txt'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .containsExactly("a.txt", "cr")
        .inOrder();
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("cr");
  }

  @Test
  public void testCannotSpecifyRunfilesWithDataOrDefaultRunfiles_struct() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          rf = ctx.runfiles()
          return DefaultInfo(runfiles = rf, default_runfiles = rf)

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    checkError(
        "test/starlark",
        "cr",
        "Cannot specify the provider 'runfiles' together with "
            + "'data_runfiles' or 'default_runfiles'",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr')");
  }

  @Test
  public void testCannotSpecifyRunfilesWithDataOrDefaultRunfiles_defaultInfo() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          rf = ctx.runfiles()
          return [DefaultInfo(runfiles = rf, default_runfiles = rf)]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    checkError(
        "test/starlark",
        "cr",
        "Cannot specify the provider 'runfiles' together with "
            + "'data_runfiles' or 'default_runfiles'",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr')");
  }

  @Test
  public void testDefaultInfoWithRunfilesConstructor() throws Exception {
    scratch.file(
        "pkg/BUILD",
        """
        filegroup(name = 'tryme',
                  srcs = [':tryme.sh'],
                  visibility = ['//visibility:public'],
        )
        """);

    scratch.file(
        "src/rulez.bzl",
        """
        def  _impl(ctx):
           info = DefaultInfo(runfiles = ctx.runfiles(files=[ctx.executable.dep]))
           if info.default_runfiles.files.to_list()[0] != ctx.executable.dep:
               fail('expected runfile to be in info.default_runfiles')
           return [info]
        r = rule(_impl,
                 attrs = {
                    'dep' : attr.label(executable = True, mandatory = True, cfg = 'exec'),
                 }
        )
        """);

    scratch.file(
        "src/BUILD",
        """
        load(':rulez.bzl', 'r')
        r(name = 'r_tools', dep = '//pkg:tryme')
        """);

    assertThat(getConfiguredTarget("//src:r_tools")).isNotNull();
  }

  @Test
  public void testDefaultInfoFilesAddedToCcBinaryTargetRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          out = ctx.actions.declare_file(ctx.attr.name + '.out')
          ctx.actions.write(out, 'foobar')
          return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        cc_binary(name = 'binary', data = [':cr'])
        """);

    useConfiguration("--incompatible_always_include_files_in_data");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:binary");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:binary");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .contains("cr.out");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .contains("cr.out");
  }

  @Test
  public void testDefaultInfoFilesAddedToJavaBinaryTargetRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          out = ctx.actions.declare_file(ctx.attr.name + '.out')
          ctx.actions.write(out, 'foobar')
          return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        java_binary(name = 'binary', data = [':cr'], srcs = ['Foo.java'], main_class = 'Foo')
        """);

    useConfiguration("--incompatible_always_include_files_in_data");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:binary");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:binary");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .contains("cr.out");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .contains("cr.out");
  }

  @Test
  public void testDefaultInfoFilesAddedToPyBinaryTargetRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          out = ctx.actions.declare_file(ctx.attr.name + '.out')
          ctx.actions.write(out, 'foobar')
          return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    scratch.file(
        "test/starlark/BUILD",
        getPyLoad("py_binary"),
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr')",
        "py_binary(name = 'binary', data = [':cr'], srcs = ['binary.py'])");

    useConfiguration("--incompatible_always_include_files_in_data");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:binary");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:binary");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .contains("cr.out");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .contains("cr.out");
  }

  @Test
  public void testDefaultInfoFilesAddedToShBinaryTargetRunfiles() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          out = ctx.actions.declare_file(ctx.attr.name + '.out')
          ctx.actions.write(out, 'foobar')
          return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        foo_binary(name = 'binary', data = [':cr'], srcs = ['script.sh'])
        """);

    useConfiguration("--incompatible_always_include_files_in_data");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:binary");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:binary");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDefaultRunfiles().getAllArtifacts()))
        .contains("cr.out");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(RunfilesProvider.class).getDataRunfiles().getAllArtifacts()))
        .contains("cr.out");
  }

  @Test
  public void testInstrumentedFilesProviderWithCodeCoverageDisabled() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return struct(instrumented_files=struct(
              extensions = ['txt'],
              source_attributes = ['attr1'],
              dependency_attributes = ['attr2']))

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {
              'attr1': attr.label_list(mandatory = True, allow_files=True),
              'attr2': attr.label_list(mandatory = True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test/starlark:extension.bzl', 'custom_rule')

        java_library(name='jl', srcs = [':A.java'])
        custom_rule(name = 'cr', attr1 = [':a.txt', ':a.random'], attr2 = [':jl'])
        """);

    useConfiguration("--nocollect_code_coverage");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    assertWithMessage("InstrumentedFilesInfo should be set.").that(provider).isNotNull();
    assertThat(ActionsTestUtil.baseArtifactNames(provider.getInstrumentedFiles())).isEmpty();
  }

  @Test
  public void testInstrumentedFilesProviderWithCodeCoverageEnabled() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return struct(instrumented_files=struct(
              extensions = ['txt'],
              source_attributes = ['attr1'],
              dependency_attributes = ['attr2']))

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {
              'attr1': attr.label_list(mandatory = True, allow_files=True),
              'attr2': attr.label_list(mandatory = True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test/starlark:extension.bzl', 'custom_rule')

        java_library(name='jl', srcs = [':A.java'])
        custom_rule(name = 'cr', attr1 = [':a.txt', ':a.random'], attr2 = [':jl'])
        """);

    useConfiguration("--collect_code_coverage");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    assertWithMessage("InstrumentedFilesInfo should be set.").that(provider).isNotNull();
    assertThat(ActionsTestUtil.baseArtifactNames(provider.getInstrumentedFiles()))
        .containsExactly("a.txt", "A.java");
  }

  @Test
  public void testInstrumentedFilesInfo_coverageDisabled() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return struct(instrumented_files=struct(
              extensions = ['txt'],
              source_attributes = ['attr1'],
              dependency_attributes = ['attr2']))

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {
              'attr1': attr.label_list(mandatory = True, allow_files=True),
              'attr2': attr.label_list(mandatory = True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test/starlark:extension.bzl', 'custom_rule')

        java_library(name='jl', srcs = [':A.java'])
        custom_rule(name = 'cr', attr1 = [':a.txt', ':a.random'], attr2 = [':jl'])
        """);

    useConfiguration("--nocollect_code_coverage");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    assertWithMessage("InstrumentedFilesInfo should be set.").that(provider).isNotNull();
    assertThat(ActionsTestUtil.baseArtifactNames(provider.getInstrumentedFiles())).isEmpty();
  }

  @Test
  public void testInstrumentedFilesInfo_coverageEnabled() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')

        def custom_rule_impl(ctx):
            metadata = ctx.actions.declare_file(ctx.label.name + '.metadata')
            ctx.actions.write(metadata, '')
            return [
                coverage_common.instrumented_files_info(
                    ctx,
                    extensions = ['txt'],
                    source_attributes = [
                        'label_src',
                        'label_list_srcs',
                        'dict_srcs',
        # Missing attrs are ignored (this allows common configuration for sets of rules where
        # only some define the specified attributes, e.g. *_library/binary).
                        'missing_src_attr',
                    ],
                    dependency_attributes = [
                        'label_dep',
                        'label_list_deps',
                        'dict_deps',
        # Missing attrs are ignored
                        'missing_dep_attr',
                    ],
                    metadata_files = [metadata],
                ),
            ]

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {
                'label_src': attr.label(allow_files=True),
                'label_list_srcs': attr.label_list(allow_files=True),
                'dict_srcs': attr.label_keyed_string_dict(allow_files=True),
        # Generally deps don't set allow_files=True, but want to assert that source files in
        # dependency_attributes are ignored, since source files don't provide
        # InstrumentedFilesInfo. (For example, files put directly into data are assumed to not be
        # source code that gets coverage instrumented.)
                'label_dep': attr.label(allow_files=True),
                'label_list_deps': attr.label_list(allow_files=True),
                'dict_deps': attr.label_keyed_string_dict(allow_files=True),
            },
        )

        def test_rule_impl(ctx):
          return [MyInfo(
        # The point of this is to assert that these fields can be read in analysistest.
        # Normally, this information wouldn't be forwarded via a different provider.
            instrumented_files = ctx.attr.target[InstrumentedFilesInfo].instrumented_files,
            metadata_files = ctx.attr.target[InstrumentedFilesInfo].metadata_files)]

        test_rule = rule(implementation = test_rule_impl,
          attrs = {'target': attr.label(mandatory = True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule', 'test_rule')

        cc_library(name='label_dep', srcs = [':label_dep.cc'])
        cc_library(name='label_list_dep', srcs = [':label_list_dep.cc'])
        cc_library(name='dict_dep', srcs = [':dict_dep.cc'])
        custom_rule(
            name = 'cr',
            label_src = ':label_src.txt',
        #   Check that srcs with the wrong extension are ignored.
            label_list_srcs = [':label_list_src.txt', ':label_list_src.ignored'],
            dict_srcs = {':dict_src.txt': ''},
            label_dep = ':label_dep',
        #   Check that files in dependency attributes are ignored.
            label_list_deps = [':label_list_dep', ':file_in_deps_is_ignored.txt'],
            dict_deps= {':dict_dep': ''},
        )
        test_rule(name = 'test', target = ':cr')
        """);

    useConfiguration("--collect_code_coverage");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:test");
    StructImpl myInfo = getMyInfoFromTarget(target);
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                ((Depset) myInfo.getValue("instrumented_files")).getSet(Artifact.class)))
        .containsExactly(
            "label_src.txt",
            "label_list_src.txt",
            "dict_src.txt",
            "label_dep.cc",
            "label_list_dep.cc",
            "dict_dep.cc");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                ((Depset) myInfo.getValue("metadata_files")).getSet(Artifact.class)))
        .containsExactly("label_dep.gcno", "label_list_dep.gcno", "dict_dep.gcno", "cr.metadata");
    ConfiguredTarget customRule = getConfiguredTarget("//test/starlark:cr");
    assertThat(
            ActionsTestUtil.baseArtifactNames(
                customRule
                    .get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
                    .getBaselineCoverageArtifacts()
                    .toList()
                    .stream()
                    .flatMap(
                        coverageArtifact ->
                            ((BaselineCoverageAction) getGeneratingAction(coverageArtifact))
                                .getInstrumentedFilesForTesting().toList().stream())
                    .toList()))
        .containsExactly(
            "label_src.txt",
            "label_list_src.txt",
            "dict_src.txt",
            "label_dep.cc",
            "label_list_dep.cc",
            "dict_dep.cc");
  }

  /**
   * Define a noop exec transition outside builtins to not interfere with tests that change the
   * builtins root.
   */
  @Before
  public void createNoopExecTransition() throws Exception {
    mockToolsConfig.create(
        "minimal_buildenv/platforms/BUILD", //
        "platform(name = 'default_host')");
    // No-op exec transition:
    scratch.overwriteFile("pkg2/BUILD", "");
    scratch.file(
        "pkg2/dummy_exec_platforms.bzl",
        "def _transition_impl(settings, attr):",
        "  return {",
        "      '//command_line_option:is exec configuration': True,",
        "      '//command_line_option:platforms': [],",
        // Need to propagate so we don't parse unparseable default @local_config_platform. Remember
        // the exec transition starts from defaults.
        "      '//command_line_option:host_platform':"
            + " settings['//command_line_option:host_platform'],",
        "      '//command_line_option:experimental_exec_config':"
            + " settings['//command_line_option:experimental_exec_config'],",
        "  }",
        "noop_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = [",
        "      '//command_line_option:host_platform',",
        "      '//command_line_option:experimental_exec_config',",
        "  ],",
        "  outputs = [",
        "      '//command_line_option:is exec configuration',",
        "      '//command_line_option:platforms',",
        "      '//command_line_option:experimental_exec_config',",
        "      '//command_line_option:host_platform',",
        "])");
  }

  @Test
  public void testInstrumentedFilesInfo_coverageSupportFiles_depset() throws Exception {
    scratch.file(
        // Package test is in the allowlist for coverage_support_files
        "test/starlark/extension.bzl",
        """
        def _impl(ctx):
          file1 = ctx.actions.declare_file(ctx.label.name + '.file1')
          ctx.actions.write(file1, '')
          file2 = ctx.actions.declare_file(ctx.label.name + '.file2')
          ctx.actions.write(file2, '')
          return coverage_common.instrumented_files_info(
              ctx,
              coverage_support_files = depset([file1, file2]),
          )

        custom_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'foo')
        """);

    useConfiguration(
        "--collect_code_coverage");

    assertThat(
            ActionsTestUtil.baseArtifactNames(
                getConfiguredTarget("//test/starlark:foo")
                    .get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
                    .getCoverageSupportFiles()))
        .containsExactly("foo.file1", "foo.file2");
  }

  @Test
  public void testInstrumentedFilesInfo_coverageSupportFiles_sequence() throws Exception {
    scratch.file(
        // Package test is in the allowlist for coverage_support_files
        "test/starlark/extension.bzl",
        """
        def _impl(ctx):
          file1 = ctx.actions.declare_file(ctx.label.name + '.file1')
          ctx.actions.write(file1, '')
          file2 = ctx.actions.declare_file(ctx.label.name + '.file2')
          ctx.actions.write(file2, '')
          return coverage_common.instrumented_files_info(
              ctx,
              coverage_support_files = [depset([file1]), file2],
          )

        custom_rule = rule(implementation = _impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'foo')
        """);
    scratch.file("test/starlark/bin.sh", "");

    useConfiguration("--collect_code_coverage");

    assertThat(
            ActionsTestUtil.baseArtifactNames(
                getConfiguredTarget("//test/starlark:foo")
                    .get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
                    .getCoverageSupportFiles()))
        .containsExactly("foo.file1", "foo.file2");
  }

  @Test
  public void testInstrumentedFilesInfo_coverageSupportAndEnvVarsArePrivateAPI() throws Exception {
    scratch.file(
        // Package foo is not in the allowlist for coverage_support_files
        "foo/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return [
            coverage_common.instrumented_files_info(
              ctx,
              coverage_support_files = ctx.files.srcs,
              coverage_environment = {'k1' : 'v1'},
            ),
          ]

        custom_rule = rule(
          implementation = custom_rule_impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True),
          },
        )
        """);
    scratch.file(
        "foo/starlark/BUILD",
        """
        load('//foo/starlark:extension.bzl', 'custom_rule')

        custom_rule(
          name = 'foo',
          srcs = ['src1.txt'],
        )
        """);
    reporter.removeHandler(failFastHandler);

    getConfiguredTarget("//foo/starlark:foo");

    assertContainsEvent("file '//foo/starlark:extension.bzl' cannot use private API");
  }

  @Test
  public void testTransitiveInfoProviders() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          ftb = depset(attr1)
          return [MyInfo(provider_key = ftb)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    RuleConfiguredTarget target = (RuleConfiguredTarget) getConfiguredTarget("//test/starlark:cr");

    assertThat(
            ActionsTestUtil.baseArtifactNames(
                ((Depset) getMyInfoFromTarget(target).getValue("provider_key"))
                    .getSet(Artifact.class)))
        .containsExactly("a.txt");
  }

  @Test
  public void testInstrumentedFilesForwardedFromDepsByDefault() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        # This wrapper doesn't configure InstrumentedFilesInfo.
        def wrapper_impl(ctx):
            return []

        wrapper = rule(implementation = wrapper_impl,
            attrs = {
                'srcs': attr.label_list(allow_files = True),
                'wrapped': attr.label(mandatory = True),
                'wrapped_list': attr.label_list(),
                # Exec deps aren't forwarded by default, since they don't provide code/binaries
                # executed at runtime.
                'tool': attr.label(cfg = 'exec', executable = True, mandatory = True),
            })
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'wrapper')

        cc_binary(name = 'tool', srcs = [':tool.cc'])
        cc_binary(name = 'wrapped', srcs = [':wrapped.cc'])
        cc_binary(name = 'wrapped_list', srcs = [':wrapped_list.cc'])
        wrapper(
            name = 'wrapper',
            srcs = ['ignored.cc'],
            wrapped = ':wrapped',
            wrapped_list = [':wrapped_list'],
            tool = ':tool',
        )
        cc_binary(name = 'outer', data = [':wrapper'])
        """);

    // By default, InstrumentedFilesInfo is forwarded from all dependencies. Coverage still needs to
    // be configured for rules that handle source files for languages which support coverage
    // instrumentation, but not every wrapper rule in the dependency chain needs to configure that
    // for coverage to work at all.
    useConfiguration("--collect_code_coverage");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:outer");
    InstrumentedFilesInfo provider = target.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR);
    assertWithMessage("InstrumentedFilesInfo should be set.").that(provider).isNotNull();
    assertThat(ActionsTestUtil.baseArtifactNames(provider.getInstrumentedFiles()))
        .containsExactly("wrapped.cc", "wrapped_list.cc");
  }

  @Test
  public void testMandatoryProviderMissing() throws Exception {
    scratch.file("test/starlark/BUILD");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def rule_impl(ctx):
          return []

        dependent_rule = rule(implementation = rule_impl)

        main_rule = rule(implementation = rule_impl,
            attrs = {'dependencies': attr.label_list(providers = ['some_provider'],
                allow_files=True)})
        """);

    checkError(
        "test",
        "b",
        "in dependencies attribute of main_rule rule //test:b: "
            + "'//test:a' does not have mandatory providers: 'some_provider'",
        "load('//test/starlark:extension.bzl', 'dependent_rule')",
        "load('//test/starlark:extension.bzl', 'main_rule')",
        "",
        "dependent_rule(name = 'a')",
        "main_rule(name = 'b', dependencies = [':a'])");
  }

  @Test
  public void testSpecialMandatoryProviderMissing() throws Exception {
    // Test that rules satisfy `providers = [...]` condition if a special provider that always
    // exists for all rules is requested.
    scratch.file(
        "test/ext/BUILD",
        """
        load('//test/starlark:extension.bzl', 'foobar')

        foobar(name = 'bar', visibility = ['//visibility:public'],)
        """);
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def rule_impl(ctx):
          pass

        foobar = rule(implementation = rule_impl)
        main_rule = rule(implementation = rule_impl, attrs = {
            'deps': attr.label_list(providers = [
                'files', 'data_runfiles', 'default_runfiles',
                'files_to_run', 'output_groups',
            ])
        })
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load(':extension.bzl', 'foobar', 'main_rule')

        foobar(name = 'foo')
        main_rule(name = 'main', deps = [':foo', '//test/ext:bar'])
        """);

    invalidatePackages();
    getConfiguredTarget("//test/starlark:main");
  }

  @Test
  public void testActions() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          output = ctx.outputs.o
          ctx.actions.run_shell(
            inputs = attr1,
            outputs = [output],
            command = 'echo')

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)},
          outputs = {'o': 'o.txt'})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    getConfiguredTarget("//test/starlark:cr");

    FileConfiguredTarget target = getFileConfiguredTarget("//test/starlark:o.txt");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            getGeneratingAction(target.getArtifact()).getInputs()))
        .containsExactly("a.txt");
  }

  @Test
  public void testRuleClassImplicitOutputFunction() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          files = [ctx.outputs.o]
          ctx.actions.run_shell(
            outputs = files,
            command = 'echo')
          ftb = depset(files)
          return [DefaultInfo(runfiles = ctx.runfiles(), files = ftb)]

        def output_func(name, public_attr, _private_attr):
          if _private_attr != None: return {}
          return {'o': name + '-' + public_attr + '.txt'}

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'public_attr': attr.string(),
                   '_private_attr': attr.label()},
          outputs = output_func)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', public_attr = 'bar')
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("cr-bar.txt");
  }

  @Test
  public void testRuleClassImplicitOutputFunctionDependingOnComputedAttribute() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          files = [ctx.outputs.o]
          ctx.actions.run_shell(
            outputs = files,
            command = 'echo')
          ftb = depset(files)
          return [DefaultInfo(runfiles = ctx.runfiles(), files = ftb)]

        def attr_func(public_attr):
          return public_attr

        def output_func(_private_attr):
          return {'o': _private_attr.name + '.txt'}

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'public_attr': attr.label(),
                   '_private_attr': attr.label(default = attr_func)},
          outputs = output_func)

        def empty_rule_impl(ctx):
          pass

        empty_rule = rule(implementation = empty_rule_impl)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule', 'empty_rule')

        empty_rule(name = 'foo')
        custom_rule(name = 'cr', public_attr = '//test/starlark:foo')
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("foo.txt");
  }

  @Test
  public void
      testRuleClassImplicitOutputFunctionAndComputedDefaultDependingOnConfigurableAttribute()
          throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          ctx.actions.write(ctx.outputs.o, 'foo')
          files = [ctx.outputs.o]
          ftb = depset(files)
          return [DefaultInfo(runfiles = ctx.runfiles(), files = ftb)]

        def computed_func(select_attr):
          return None

        def output_func(irrelevant_attr):
          return {'o': irrelevant_attr + '.txt'}

        custom_rule = rule(
          implementation = custom_rule_impl,
          attrs = {
            'select_attr': attr.string(),
            'irrelevant_attr': attr.string(),
            '_computed_attr': attr.label(default=computed_func),
          },
          outputs = output_func)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(
          name = 'cr',
          irrelevant_attr = 'foo',
          select_attr = select({"//conditions:default": "bar"}),
        )
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(
            ActionsTestUtil.baseArtifactNames(
                target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("foo.txt");
  }

  @Test
  public void testRuleClassImplicitOutputs() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          files = [ctx.outputs.lbl, ctx.outputs.list, ctx.outputs.str]
          print('==!=!=!=')
          print(files)
          ctx.actions.run_shell(
            outputs = files,
            command = 'echo')
          return [DefaultInfo(files = depset(files))]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {
            'attr1': attr.label(allow_files=True),
            'attr2': attr.label_list(allow_files=True),
            'attr3': attr.string(),
          },
          outputs = {
            'lbl': '%{attr1}.a',
            'list': '%{attr2}.b',
            'str': '%{attr3}.c',
        })
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(
          name='cr',
          attr1='f1.txt',
          attr2=['f2.txt'],
          attr3='f3.txt',
        )
        """);

    scratch.file("test/starlark/f1.txt");
    scratch.file("test/starlark/f2.txt");
    scratch.file("test/starlark/f3.txt");

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("f1.a", "f2.b", "f3.txt.c");
  }

  @Test
  public void testRuleClassImplicitOutputFunctionAndDefaultValue() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          ctx.actions.run_shell(
            outputs = [ctx.outputs.o],
            command = 'echo')
          return [DefaultInfo(runfiles = ctx.runfiles())]

        def output_func(attr1):
          return {'o': attr1 + '.txt'}

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.string(default='bar')},
          outputs = output_func)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = None)
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("bar.txt");
  }

  @Test
  public void testPrintProviderCollection() throws Exception {
    scratch.file(
        "test/starlark/rules.bzl",
        """
        FooInfo = provider()
        BarInfo = provider()

        def _top_level_rule_impl(ctx):
          print('My Dep Providers:', ctx.attr.my_dep)

        def _dep_rule_impl(ctx):
          providers = [
              FooInfo(),
              BarInfo(),
          ]
          return providers

        top_level_rule = rule(
            implementation=_top_level_rule_impl,
            attrs={'my_dep':attr.label()}
        )

        dep_rule = rule(
            implementation=_dep_rule_impl,
        )
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:rules.bzl', 'top_level_rule', 'dep_rule')

        top_level_rule(name = 'tl', my_dep=':d')

        dep_rule(name = 'd')
        """);

    getConfiguredTarget("//test/starlark:tl");
    assertContainsEvent(
        "My Dep Providers: <target //test/starlark:d, keys:[LicenseInfo, FooInfo, BarInfo,"
            + " OutputGroupInfo]>");
  }

  @Test
  public void testRuleClassImplicitOutputFunctionPrints() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          print('implementation', ctx.label)
          files = [ctx.outputs.o]
          ctx.actions.run_shell(
            outputs = files,
            command = 'echo')

        def output_func(name):
          print('output function', name)
          return {'o': name + '.txt'}

        custom_rule = rule(implementation = custom_rule_impl,
          outputs = output_func)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        """);

    getConfiguredTarget("//test/starlark:cr");
    assertContainsEvent("output function cr");
    assertContainsEvent("implementation //test/starlark:cr");
  }

  @Test
  public void testNoOutputAttrDefault() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def custom_rule_impl(ctx):
          out_file = ctx.actions.declare_file(ctx.attr._o1.name)
          ctx.actions.write(output=out_file, content='hi')
          return [MyInfo(o1=ctx.attr._o1)]

        def output_fn():
          return Label('//test/starlark:foo.txt')

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'_o1': attr.output(default = output_fn)})
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_rule')

        custom_rule(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("got unexpected keyword argument 'default'");
  }

  @Test
  public void testNoOutputListAttrDefault() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return []

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'outs': attr.output_list(default = [])})
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_rule')

        custom_rule(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("got unexpected keyword argument 'default'");
  }

  @Test
  public void testRuleClassNonMandatoryEmptyOutputs() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def custom_rule_impl(ctx):
          return [MyInfo(
              o1=ctx.outputs.o1,
              o2=ctx.outputs.o2)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'o1': attr.output(), 'o2': attr.output_list()})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");
    StructImpl myInfo = getMyInfoFromTarget(target);
    assertThat(myInfo.getValue("o1")).isEqualTo(Starlark.NONE);
    assertThat(myInfo.getValue("o2")).isEqualTo(StarlarkList.empty());
  }

  @Test
  public void testRuleClassImplicitAndExplicitOutputNamesCollide() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return []

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'o': attr.output_list()},
          outputs = {'o': '%{name}.txt'})
        """);

    checkError(
        "test/starlark",
        "cr",
        "Implicit output key 'o' collides with output attribute name",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr', o = [':bar.txt'])");
  }

  @Test
  public void testRuleClassDefaultFilesToBuild() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          files = [ctx.outputs.o]
          ctx.actions.run_shell(
            outputs = files,
            command = 'echo')
          ftb = depset(files)
          for i in ctx.outputs.out:
            ctx.actions.write(output=i, content='hi there')

        def output_func(attr1):
          return {'o': attr1 + '.txt'}

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {
            'attr1': attr.string(),
            'out': attr.output_list()
          },
          outputs = output_func)
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = 'bar', out=['other'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("bar.txt", "other")
        .inOrder();
  }

  @Test
  public void rulesReturningDeclaredProviders() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        my_provider = provider()
        def _impl(ctx):
           return [my_provider(x = 1)]
        my_rule = rule(_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':extension.bzl', 'my_rule')
        my_rule(name = 'r')
        """);

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test:r");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(configuredTarget.getLabel().getPackageIdentifier(), "extension.bzl")),
            "my_provider");
    StructImpl declaredProvider = (StructImpl) configuredTarget.get(key);
    assertThat(declaredProvider).isNotNull();
    assertThat(declaredProvider.getProvider().getKey()).isEqualTo(key);
    assertThat(declaredProvider.getValue("x")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void rulesReturningDeclaredProvidersCompatMode() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        my_provider = provider()
        def _impl(ctx):
           return [my_provider(x = 1)]
        my_rule = rule(_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':extension.bzl', 'my_rule')
        my_rule(name = 'r')
        """);

    ConfiguredTarget configuredTarget  = getConfiguredTarget("//test:r");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(configuredTarget.getLabel().getPackageIdentifier(), "extension.bzl")),
            "my_provider");
    StructImpl declaredProvider = (StructImpl) configuredTarget.get(key);
    assertThat(declaredProvider).isNotNull();
    assertThat(declaredProvider.getProvider().getKey()).isEqualTo(key);
    assertThat(declaredProvider.getValue("x")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testRuleReturningUnwrappedDeclaredProvider() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        my_provider = provider()
        def _impl(ctx):
           return my_provider(x = 1)
        my_rule = rule(_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':extension.bzl', 'my_rule')
        my_rule(name = 'r')
        """);

    ConfiguredTarget configuredTarget  = getConfiguredTarget("//test:r");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(configuredTarget.getLabel().getPackageIdentifier(), "extension.bzl")),
            "my_provider");
    StructImpl declaredProvider = (StructImpl) configuredTarget.get(key);
    assertThat(declaredProvider).isNotNull();
    assertThat(declaredProvider.getProvider().getKey()).isEqualTo(key);
    assertThat(declaredProvider.getValue("x")).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testConflictingProviderKeys_fromStruct_disallowed() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        my_provider = provider()
        other_provider = provider()
        def _impl(ctx):
           return [my_provider(x = 1), other_provider(), my_provider(x = 2)]
        my_rule = rule(_impl)
        """);

    checkError(
        "test",
        "r",
        "Multiple conflicting returned providers with key my_provider",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'r')");
  }

  @Test
  public void testConflictingProviderKeys_fromIterable_disallowed() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        my_provider = provider()
        other_provider = provider()
        def _impl(ctx):
           return [my_provider(x = 1), other_provider(), my_provider(x = 2)]
        my_rule = rule(_impl)
        """);

    checkError(
        "test",
        "r",
        "Multiple conflicting returned providers with key my_provider",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(name = 'r')");
  }

  @Test
  public void testRecursionDetection() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def _impl(ctx):
          _impl(ctx)
        empty = rule(implementation = _impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'empty')
        empty(name = 'test_target')
        """);

    getConfiguredTarget("//test/starlark:test_target");
    assertContainsEvent("function '_impl' called recursively");
  }

  @Test
  public void testBadCallbackFunction() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def impl(): return 0

        custom_rule = rule(impl)
        """);

    checkError(
        "test/starlark",
        "cr",
        "impl() does not accept positional arguments",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr')");
  }

  @Test
  public void testRuleClassImplicitOutputFunctionBadAttr() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return None

        def output_func(bad_attr):
          return {'a': bad_attr}

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.string()},
          outputs = output_func)
        """);

    checkError(
        "test/starlark",
        "cr",
        "Attribute 'bad_attr' either doesn't exist or uses a select()",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "custom_rule(name = 'cr', attr1 = 'bar')");
  }

  @Test
  public void testHelperFunctionInRuleImplementation() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def helper_func(attr1):
          return depset(attr1)

        def custom_rule_impl(ctx):
          attr1 = ctx.files.attr1
          ftb = helper_func(attr1)
          return [DefaultInfo(runfiles = ctx.runfiles(), files = ftb)]

        custom_rule = rule(implementation = custom_rule_impl,
          attrs = {'attr1': attr.label_list(mandatory=True, allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr', attr1 = [':a.txt'])
        """);

    ConfiguredTarget target = getConfiguredTarget("//test/starlark:cr");

    assertThat(target.getLabel().toString()).isEqualTo("//test/starlark:cr");
    assertThat(
        ActionsTestUtil.baseArtifactNames(
            target.getProvider(FileProvider.class).getFilesToBuild()))
        .containsExactly("a.txt");
  }

  @Test
  public void testMultipleLoadsOfSameRule() throws Exception {
    scratch.file("test/starlark/BUILD");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return None

        custom_rule = rule(implementation = custom_rule_impl,
             attrs = {'dep': attr.label_list(allow_files=True)})
        """);

    scratch.file(
        "test/starlark1/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')
        custom_rule(name = 'cr1')
        """);

    scratch.file(
        "test/starlark2/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')
        custom_rule(name = 'cr2', dep = ['//test/starlark1:cr1'])
        """);

    getConfiguredTarget("//test/starlark2:cr2");
  }

  @Test
  public void testFunctionGeneratingRules() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def impl(ctx): return None
        def gen(): return rule(impl)
        r = gen()
        s = gen()
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load(':extension.bzl', 'r', 's')
        r(name = 'r')
        s(name = 's')
        """);

    getConfiguredTarget("//test/starlark:r");
    getConfiguredTarget("//test/starlark:s");
  }

  @Test
  public void testLoadInStarlark() throws Exception {
    scratch.file(
        "test/starlark/implementation.bzl",
        """
        def custom_rule_impl(ctx):
          return None
        """);

    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//test/starlark:implementation.bzl', 'custom_rule_impl')

        custom_rule = rule(implementation = custom_rule_impl,
             attrs = {'dep': attr.label_list(allow_files=True)})
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')
        custom_rule(name = 'cr')
        """);

    getConfiguredTarget("//test/starlark:cr");
  }

  @Test
  public void testRuleAliasing() throws Exception {
    scratch.file(
        "test/starlark/implementation.bzl",
        """
        def impl(ctx): return []
        custom_rule = rule(implementation = impl)
        """);

    scratch.file(
        "test/starlark/ext.bzl",
        """
        load('//test/starlark:implementation.bzl', 'custom_rule')
        def impl(ctx): return []
        custom_rule1 = rule(implementation = impl)
        custom_rule2 = custom_rule1
        custom_rule3 = custom_rule
        """);

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:ext.bzl', 'custom_rule1', 'custom_rule2', 'custom_rule3')
        custom_rule4 = custom_rule3
        custom_rule1(name = 'cr1')
        custom_rule2(name = 'cr2')
        custom_rule3(name = 'cr3')
        custom_rule4(name = 'cr4')
        """);

    getConfiguredTarget("//test/starlark:cr1");
    getConfiguredTarget("//test/starlark:cr2");
    getConfiguredTarget("//test/starlark:cr3");
    getConfiguredTarget("//test/starlark:cr4");
  }

  @Test
  public void testRecursiveLoad() throws Exception {
    scratch.file("test/starlark/ext2.bzl", "load('//test/starlark:ext1.bzl', 'symbol2')");

    scratch.file("test/starlark/ext1.bzl", "load('//test/starlark:ext2.bzl', 'symbol1')");

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:ext1.bzl', 'custom_rule')
        genrule(name = 'rule')
        """);

    reporter.removeHandler(failFastHandler);
    assertThrows(BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:rule"));
    assertContainsEvent(
        "cycle detected in extension files: \n"
            + "    test/starlark/BUILD\n"
            + ".-> //test/starlark:ext1.bzl\n"
            + "|   //test/starlark:ext2.bzl\n"
            + "`-- //test/starlark:ext1.bzl");
  }

  @Test
  public void testRecursiveLoad2() throws Exception {
    scratch.file("test/starlark/ext1.bzl", "load('//test/starlark:ext2.bzl', 'symbol2')");
    scratch.file("test/starlark/ext2.bzl", "load('//test/starlark:ext3.bzl', 'symbol3')");
    scratch.file("test/starlark/ext3.bzl", "load('//test/starlark:ext4.bzl', 'symbol4')");
    scratch.file("test/starlark/ext4.bzl", "load('//test/starlark:ext2.bzl', 'symbol2')");

    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:ext1.bzl', 'custom_rule')
        genrule(name = 'rule')
        """);

    reporter.removeHandler(failFastHandler);
    assertThrows(BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:rule"));
    assertContainsEvent(
        "cycle detected in extension files: \n"
            + "    test/starlark/BUILD\n"
            + "    //test/starlark:ext1.bzl\n"
            + ".-> //test/starlark:ext2.bzl\n"
            + "|   //test/starlark:ext3.bzl\n"
            + "|   //test/starlark:ext4.bzl\n"
            + "`-- //test/starlark:ext2.bzl");
  }

  @Test
  public void testLoadSymbolTypo() throws Exception {
    scratch.file("test/starlark/ext1.bzl", "myvariable = 2");

    scratch.file("test/starlark/BUILD", "load('//test/starlark:ext1.bzl', 'myvariables')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:test_target");
    assertContainsEvent(
        "file '//test/starlark:ext1.bzl' does not contain symbol 'myvariables' "
            + "(did you mean 'myvariable'?)");
  }

  @Test
  public void testOutputsObjectOrphanExecutableReportError() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           o = ctx.outputs.executable
           return [DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:xxx");
    assertContainsEvent("ERROR /workspace/test/BUILD:2:8: in my_rule rule //test:xxx: ");
    assertContainsEvent("The following files have no generating action:");
    assertContainsEvent("test/xxx");
  }

  @Test
  public void testCustomExecutableUsed() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           o = ctx.actions.declare_file('x.sh')
           ctx.actions.write(o, 'echo Stuff', is_executable = True)
           return [DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test:xxx");
    Artifact executable = configuredTarget.getProvider(FilesToRunProvider.class).getExecutable();
    assertThat(executable.getRootRelativePathString()).isEqualTo("test/x.sh");
  }

  @Test
  public void testCustomAndDefaultExecutableReportsError() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           e = ctx.outputs.executable
           o = ctx.actions.declare_file('x.sh')
           ctx.actions.write(o, 'echo Stuff', is_executable = True)
           return [DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:xxx");
    assertContainsEvent("ERROR /workspace/test/BUILD:2:8: in my_rule rule //test:xxx: ");
    assertContainsEvent(
        "/workspace/test/rule.bzl:5:23: The rule 'my_rule' both accesses "
            + "'ctx.outputs.executable' and provides a different executable 'test/x.sh'. "
            + "Do not use 'ctx.output.executable'.");
  }


  @Test
  public void testCustomExecutableStrNoEffect() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           o = ctx.actions.declare_file('x.sh')
           ctx.actions.write(o, 'echo Stuff', is_executable = True)
           print(str(ctx.outputs))
           return [DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test:xxx");
    Artifact executable = configuredTarget.getProvider(FilesToRunProvider.class).getExecutable();
    assertThat(executable.getRootRelativePathString()).isEqualTo("test/x.sh");
  }

  @Test
  public void testCustomExecutableDirNoEffect() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           o = ctx.actions.declare_file('x.sh')
           ctx.actions.write(o, 'echo Stuff', is_executable = True)
           print(dir(ctx.outputs))
           return [DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test:xxx");
    Artifact executable = configuredTarget.getProvider(FilesToRunProvider.class).getExecutable();
    assertThat(executable.getRootRelativePathString()).isEqualTo("test/x.sh");
  }

  @Test
  public void testOutputsObjectInDifferentRuleInaccessible() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        PInfo = provider(fields = ['outputs'])
        def _impl(ctx):
           o = ctx.actions.declare_file('x.sh')
           ctx.actions.write(o, 'echo Stuff', is_executable = True)
           return [PInfo(outputs = ctx.outputs), DefaultInfo(executable = o)]
        my_rule = rule(_impl, executable = True)
        def _dep_impl(ctx):
           o = ctx.attr.dep[PInfo].outputs.executable  # this is line 8
           pass
        my_dep_rule = rule(_dep_impl, attrs = { 'dep' : attr.label() })
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule', 'my_dep_rule')
        my_rule(name = 'xxx')
        my_dep_rule(name = 'yyy', dep = ':xxx')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:yyy");
    assertContainsEvent("ERROR /workspace/test/BUILD:3:12: in my_dep_rule rule //test:yyy: ");
    assertContainsEvent("File \"/workspace/test/rule.bzl\", line 8, column 35, in _dep_impl");
    assertContainsEvent("cannot access outputs of rule '//test:xxx' outside "
        + "of its own rule implementation function");
  }

  @Test
  public void testOutputsObjectStringRepresentation() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        PInfo = provider(fields = ['outputs', 's'])
        def _impl(ctx):
           ctx.actions.write(ctx.outputs.executable, 'echo Stuff', is_executable = True)
           ctx.actions.write(ctx.outputs.other, 'Other')
           return [PInfo(outputs = ctx.outputs, s = str(ctx.outputs))]
        my_rule = rule(_impl, executable = True, outputs = { 'other' : '%{name}.other' })
        def _dep_impl(ctx):
           return [PInfo(s = str(ctx.attr.dep[PInfo].outputs))]
        my_dep_rule = rule(_dep_impl, attrs = { 'dep' : attr.label() })
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule', 'my_dep_rule')
        my_rule(name = 'xxx')
        my_dep_rule(name = 'yyy', dep = ':xxx')
        """);

    StarlarkProvider.Key pInfoKey =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//test:rule.bzl")), "PInfo");

    ConfiguredTarget targetXXX = getConfiguredTarget("//test:xxx");
    StructImpl structXXX = (StructImpl) targetXXX.get(pInfoKey);

    assertThat(structXXX.getValue("s"))
        .isEqualTo(
            "ctx.outputs(executable = <generated file test/xxx>, "
                + "other = <generated file test/xxx.other>)");

    ConfiguredTarget targetYYY = getConfiguredTarget("//test:yyy");
    StructImpl structYYY = (StructImpl) targetYYY.get(pInfoKey);
    assertThat(structYYY.getValue("s"))
        .isEqualTo("ctx.outputs(for //test:xxx)");
  }

  @Test
  public void testExecutableRuleWithNoExecutableReportsError() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
           pass
        my_rule = rule(_impl, executable = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'xxx')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:xxx");
    assertContainsEvent("ERROR /workspace/test/BUILD:2:8: in my_rule rule //test:xxx: ");
    assertContainsEvent("/rule.bzl:1:5: The rule 'my_rule' is executable. "
        + "It needs to create an executable File and pass it as the 'executable' "
        + "parameter to the DefaultInfo it returns.");
  }

  @Test
  public void testExecutableFromDifferentRuleIsForbidden() throws Exception {
    scratch.file(
        "pkg/BUILD",
        """
        filegroup(name = 'tryme',
                  srcs = [':tryme.sh'],
                  visibility = ['//visibility:public'],
        )
        """);

    scratch.file(
        "src/rulez.bzl",
        """
        def  _impl(ctx):
           return [DefaultInfo(executable = ctx.executable.runme,
                               files = depset([ctx.executable.runme]),
                  )]
        r = rule(_impl,
                 executable = True,
                 attrs = {
                    'runme' : attr.label(executable = True, mandatory = True, cfg = 'exec'),
                 }
        )
        """);

    scratch.file(
        "src/BUILD",
        """
        load(':rulez.bzl', 'r')
        r(name = 'r_tools', runme = '//pkg:tryme')
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//src:r_tools");
    assertContainsEvent(
        "/workspace/src/rulez.bzl:2:23: 'executable' provided by an executable"
            + " rule 'r' should be created by the same rule.");
  }

  @Test
  public void testFileAndDirectory() throws Exception {
    scratch.file(
        "ext.bzl",
        """
        def _extrule(ctx):
          dir = ctx.actions.declare_directory('foo/bar/baz')
          ctx.actions.run_shell(
              outputs = [dir],
              command = 'mkdir -p ' + dir.path + ' && echo wtf > ' + dir.path + '/wtf.txt')

        extrule = rule(
            _extrule,
            outputs = {
              'out': 'foo/bar/baz',
            },
        )
        """);
    scratch.file(
        "BUILD", //
        "load(':ext.bzl', 'extrule')",
        "",
        "extrule(",
        "    name = 'test'",
        ")");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//:test");
    assertContainsEvent("ERROR /workspace/BUILD:3:8: in extrule rule //:test:");
    assertContainsEvent("he following directories were also declared as files:");
    assertContainsEvent("foo/bar/baz");
  }

  @Test
  public void testEnvironmentConstraintsFromStarlarkRule() throws Exception {
    scratch.file(
        "buildenv/foo/BUILD",
        """
        environment_group(name = 'env_group',
            defaults = [':default'],
            environments = ['default', 'other'])
        environment(name = 'default')
        environment(name = 'other')
        """);
    // The example Starlark rule explicitly provides the MyProvider provider as a regression test
    // for a bug where a Starlark rule with unsatisfied constraints but explicit providers would
    // result in Bazel throwing a null pointer exception.
    scratch.file(
        "test/starlark/extension.bzl",
        """
        MyProvider = provider()

        def _impl(ctx):
          return [MyProvider(foo = 'bar')]
        my_rule = rule(implementation = _impl,
            attrs = { 'deps' : attr.label_list() },
            provides = [MyProvider])
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test/starlark:extension.bzl',  'my_rule')
        java_library(name = 'dep', srcs = ['a.java'], restricted_to = ['//buildenv/foo:other'])
        my_rule(name='my', deps = [':dep'])
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test/starlark:my")).isNull();
    assertContainsEvent(
        "//test/starlark:dep doesn't support expected environment: //buildenv/foo:default");
  }

  @Test
  public void testTestResultInfo() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]

        custom_rule = rule(implementation = custom_rule_impl)
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_rule')

        custom_rule(name = 'r')
        """);

    ConfiguredTarget target = getConfiguredTarget("//test:r");
    AnalysisTestResultInfo info =
        (AnalysisTestResultInfo) target.get(AnalysisTestResultInfo.STARLARK_CONSTRUCTOR.getKey());
    assertThat(info.getSuccess()).isTrue();
    assertThat(info.getMessage()).isEqualTo("message contents");
  }

  @Test
  public void testAnalysisTestRuleWithActionRegistration() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def custom_rule_impl(ctx):
          out_file = ctx.actions.declare_file('file.txt')
          ctx.actions.write(output=out_file, content='hi')

        custom_test = rule(implementation = custom_rule_impl, analysis_test = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_test')

        custom_test(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent(
        "implementation function of a rule with analysis_test=true may not register actions");
  }

  @Test
  public void testAnalysisTestRuleWithFlag() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]

        custom_test = rule(implementation = custom_rule_impl, analysis_test = True)
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_test')

        custom_test(name = 'r')
        """);

    ConfiguredTarget target = getConfiguredTarget("//test:r");
    AnalysisTestResultInfo info =
        (AnalysisTestResultInfo) target.get(AnalysisTestResultInfo.STARLARK_CONSTRUCTOR.getKey());
    assertThat(info.getSuccess()).isTrue();
    assertThat(info.getMessage()).isEqualTo("message contents");

    // TODO(cparsons): Verify implicit action registration via AnalysisTestResultInfo.
  }

  @Test
  public void testAnalysisTestTransitionOnAnalysisTest() throws Exception {
    useConfiguration("--copt=yeehaw");

    scratch.file(
        "test/extension.bzl",
        """
        MyInfo = provider()
        MyDep = provider()

        def outer_rule_impl(ctx):
          return [MyInfo(copts = ctx.fragments.cpp.copts),
                  MyDep(info = ctx.attr.dep[0][MyInfo]),
                  AnalysisTestResultInfo(success = True, message = 'message contents')]
        def inner_rule_impl(ctx):
          return [MyInfo(copts = ctx.fragments.cpp.copts)]

        my_transition = analysis_test_transition(
            settings = {
                '//command_line_option:copt' : ['cowabunga'] }
        )
        inner_rule = rule(implementation = inner_rule_impl,
                          fragments = ['cpp'])
        outer_rule_test = rule(
          implementation = outer_rule_impl,
          fragments = ['cpp'],
          analysis_test = True,
          attrs = {
            'dep':  attr.label(cfg = my_transition),
          })
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'inner_rule', 'outer_rule_test')

        inner_rule(name = 'inner')
        outer_rule_test(name = 'r', dep = ':inner')
        """);

    StarlarkProvider.Key myInfoKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:extension.bzl")), "MyInfo");
    StarlarkProvider.Key myDepKey =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//test:extension.bzl")), "MyDep");

    ConfiguredTarget outerTarget = getConfiguredTarget("//test:r");
    StructImpl outerInfo = (StructImpl) outerTarget.get(myInfoKey);
    StructImpl outerDepInfo = (StructImpl) outerTarget.get(myDepKey);
    StructImpl innerInfo = (StructImpl) outerDepInfo.getValue("info");

    assertThat((Sequence) outerInfo.getValue("copts")).containsExactly("yeehaw");
    assertThat((Sequence) innerInfo.getValue("copts")).containsExactly("cowabunga");
  }

  // Regression test for b/168715549 which exposed a bug when an analysistest transition
  // set an option to the same value it already had in the configuration, depended on a c++ rule,
  // and was built at the same time as the same cc rule not under transition. Basically it's
  // ensuring that analysistests are never treated as no-op transitions (which don't update the
  // output directory).
  @Test
  public void testAnalysisTestTransitionOnAndWithCcRuleHasNoActionConflicts() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        test_transition = analysis_test_transition(
          settings = {'//command_line_option:compilation_mode': 'fastbuild'}
        )
        def _test_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]
        my_analysis_test = rule(
          implementation = _test_impl,
          attrs = {
            'target_under_test': attr.label(cfg = test_transition),
          },
          test = True,
          analysis_test = True
        )
        def _impl(ctx):
          pass

        parent = rule(
          implementation = _impl,
          attrs = {
            'one': attr.label(),
            'two': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'my_analysis_test', 'parent')
        cc_library(name = 'dep')
        my_analysis_test(
          name = 'test',
          target_under_test = ':dep',
        )
        parent(
          name = 'parent',
          # Needs to be testonly to depend on a test rule.
          testonly = True,
          one = ':dep',
          two = ':test',
        )
        """);
    useConfiguration("--compilation_mode=fastbuild");
    getConfiguredTarget("//test:parent");
  }

  @Test
  public void testAnalysisTestTransitionOnNonAnalysisTest() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return []
        my_transition = analysis_test_transition(
            settings = {
                '//command_line_option:foo' : 'yeehaw' }
        )

        custom_rule = rule(
          implementation = custom_rule_impl,
          attrs = {
            'dep':  attr.label(cfg = my_transition),
          })
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'custom_rule')

        custom_rule(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent(
        "Only rule definitions with analysis_test=True may have attributes "
            + "with analysis_test_transition transitions");
  }

  @Test
  public void testBuildSettingRule_flag() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx): return None
        build_setting_rule = rule(_impl, build_setting = config.string(flag=True))
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'build_setting_rule')
        build_setting_rule(name = 'my_build_setting', build_setting_default = 'default')
        """);

    BuildSetting buildSetting =
        getTarget("//test:my_build_setting")
            .getAssociatedRule()
            .getRuleClassObject()
            .getBuildSetting();

    assertThat(buildSetting.getType()).isEqualTo(Type.STRING);
    assertThat(buildSetting.isFlag()).isTrue();
  }

  @Test
  public void testBuildSettingRule_settingByDefault() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx): return None
        build_setting_rule = rule(_impl, build_setting = config.string())
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'build_setting_rule')
        build_setting_rule(name = 'my_build_setting', build_setting_default = 'default')
        """);

    BuildSetting buildSetting =
        getTarget("//test:my_build_setting")
            .getAssociatedRule()
            .getRuleClassObject()
            .getBuildSetting();

    assertThat(buildSetting.getType()).isEqualTo(Type.STRING);
    assertThat(buildSetting.isFlag()).isFalse();
  }

  @Test
  public void testBuildSettingRule_settingByFlagParameter() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx): return None
        build_setting_rule = rule(_impl, build_setting = config.string(flag=False))
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'build_setting_rule')
        build_setting_rule(name = 'my_build_setting', build_setting_default = 'default')
        """);

    BuildSetting buildSetting =
        getTarget("//test:my_build_setting")
            .getAssociatedRule()
            .getRuleClassObject()
            .getBuildSetting();

    assertThat(buildSetting.getType()).isEqualTo(Type.STRING);
    assertThat(buildSetting.isFlag()).isFalse();
  }


  @Test
  public void testBuildSettingRule_noDefault() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx): return None
        build_setting_rule = rule(_impl, build_setting = config.string())
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'build_setting_rule')
        build_setting_rule(name = 'my_build_setting')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_build_setting");
    assertContainsEvent("missing value for mandatory attribute "
        + "'build_setting_default' in 'build_setting_rule' rule");

  }

  @Test
  public void testAnalysisTestCannotDependOnAnalysisTest() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def analysis_test_rule_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]
        def middle_rule_impl(ctx):
          return []
        def inner_rule_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]

        my_transition = analysis_test_transition(
            settings = {
                '//command_line_option:foo' : 'yeehaw' }
        )

        inner_rule_test = rule(
          implementation = analysis_test_rule_impl,
          analysis_test = True,
        )
        middle_rule = rule(
          implementation = middle_rule_impl,
          attrs = {'dep':  attr.label()}
        )
        outer_rule_test = rule(
          implementation = analysis_test_rule_impl,
          analysis_test = True,
          attrs = {
            'dep':  attr.label(cfg = my_transition),
          })
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'outer_rule_test', 'middle_rule', 'inner_rule_test')

        outer_rule_test(name = 'outer', dep = ':middle')
        middle_rule(name = 'middle', dep = ':inner')
        inner_rule_test(name = 'inner')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:outer");
    assertContainsEvent(
        "analysis_test rule '//test:inner' cannot be transitively "
            + "depended on by another analysis test rule");
  }

  @Test
  public void testAnalysisTestOverDepsLimit() throws Exception {
    setupAnalysisTestDepsLimitTest(10, 12, true);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent(
        "analysis test rule exceeded maximum dependency edge count. " + "Count: 14. Limit is 10.");
  }

  @Test
  public void testAnalysisTestUnderDepsLimit() throws Exception {
    setupAnalysisTestDepsLimitTest(10, 8, true);

    assertThat(getConfiguredTarget("//test:r")).isNotNull();
  }

  @Test
  public void testAnalysisDepsLimitOnlyForTransition() throws Exception {
    setupAnalysisTestDepsLimitTest(3, 10, false);

    assertThat(getConfiguredTarget("//test:r")).isNotNull();
  }

  private void setupAnalysisTestDepsLimitTest(
      int limit, int dependencyChainSize, boolean useTransition) throws Exception {
    Preconditions.checkArgument(dependencyChainSize > 2);
    useConfiguration("--analysis_testing_deps_limit=" + limit);

    String transitionDefinition;
    if (useTransition) {
      transitionDefinition =
          "my_transition = analysis_test_transition("
              + "settings = {'//command_line_option:foo' : 'yeehaw' })";
    } else {
      transitionDefinition = "my_transition = None";
    }

    scratch.file(
        "test/extension.bzl",
        "",
        "def outer_rule_impl(ctx):",
        "  return [AnalysisTestResultInfo(success = True, message = 'message contents')]",
        "def dep_rule_impl(ctx):",
        "  return []",
        "",
        transitionDefinition,
        "",
        "dep_rule = rule(",
        "  implementation = dep_rule_impl,",
        "  attrs = {'deps': attr.label_list()}",
        ")",
        "outer_rule_test = rule(",
        "  implementation = outer_rule_impl,",
        "  fragments = ['java'],",
        "  analysis_test = True,",
        "  attrs = {",
        "    'dep':  attr.label(cfg = my_transition),",
        "  })");

    // Create a chain of targets where 'innerN' depends on 'inner{N+1}' until the max length.
    StringBuilder dependingRulesChain = new StringBuilder();
    for (int i = 0; i < dependencyChainSize - 1; i++) {
      // Each dep_rule target also depends on the leaf.
      // The leaf should not be counted multiple times.
      dependingRulesChain.append(
          String.format(
              "dep_rule(name = 'inner%s', deps = [':inner%s', ':inner%s'])\n",
              i, (i + 1), dependencyChainSize));
    }
    dependingRulesChain.append(
        String.format(
            "dep_rule(name = 'inner%s', deps = [':inner%s'])\n",
            dependencyChainSize - 1, dependencyChainSize));
    dependingRulesChain.append(String.format("dep_rule(name = 'inner%s')", dependencyChainSize));

    scratch.file(
        "test/BUILD",
        "load('//test:extension.bzl', 'dep_rule', 'outer_rule_test')",
        "",
        "outer_rule_test(name = 'r', dep = ':inner0')",
        dependingRulesChain.toString());
  }

  @Test
  public void testBadAllowlistTransition_onNonLabelAttr() throws Exception {
    String allowlistAttributeName =
        FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME.replace("$", "_");
    scratch.file(
        "test/rules.bzl",
        "def _impl(ctx):",
        "    return []",
        "",
        "my_rule = rule(_impl, attrs = {'"
            + allowlistAttributeName
            + "':attr.string(default = 'blah')})");
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'my_rule')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_rule");
    assertContainsEvent("_allowlist_function_transition attribute must be a label type");
  }

  @Test
  public void testBadAllowlistTransition_noDefaultValue() throws Exception {
    String allowlistAttributeName =
        FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME.replace("$", "_");
    scratch.file(
        "test/rules.bzl",
        "def _impl(ctx):",
        "    return []",
        "",
        "my_rule = rule(_impl, attrs = {'" + allowlistAttributeName + "':attr.label()})");
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'my_rule')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_rule");
    assertContainsEvent("_allowlist_function_transition attribute must have a default value");
  }

  @Test
  public void testBadAllowlistTransition_wrongDefaultValue() throws Exception {
    String allowlistAttributeName =
        FunctionSplitTransitionAllowlist.ATTRIBUTE_NAME.replace("$", "_");
    scratch.file(
        "test/rules.bzl",
        "def _impl(ctx):",
        "    return []",
        "",
        "my_rule = rule(_impl, attrs = {'"
            + allowlistAttributeName
            + "':attr.label(default = Label('//test:my_other_rule'))})");
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'my_rule')
        my_rule(name = 'my_other_rule')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_rule");
    assertContainsEvent(
        " _allowlist_function_transition attribute (//test:my_other_rule) does not have the"
            + " expected value");
  }

  @Test
  public void testBadAnalysisTestRule_notAnalysisTest() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        def outer_rule_impl(ctx):
          return [AnalysisTestResultInfo(success = True, message = 'message contents')]
        def dep_rule_impl(ctx):
          return []

        my_transition = analysis_test_transition(
            settings = {
                '//command_line_option:foo' : 'yeehaw' }
        )
        dep_rule = rule(
          implementation = dep_rule_impl,
          attrs = {'dep':  attr.label()}
        )
        outer_rule = rule(
          implementation = outer_rule_impl,
        # analysis_test = True,
          fragments = ['java'],
          attrs = {
            'dep':  attr.label(cfg = my_transition),
          })
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'dep_rule', 'outer_rule')

        outer_rule(name = 'r', dep = ':inner')
        dep_rule(name = 'inner')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:outer_rule");
    assertContainsEvent(
        "Only rule definitions with analysis_test=True may have attributes with "
            + "analysis_test_transition transitions");
  }

  @Test
  public void testBadAllowlistTransition_automaticAllowlist() throws Exception {
    scratch.overwriteFile(
        TestConstants.TOOLS_REPOSITORY_SCRATCH
            + "tools/allowlists/function_transition_allowlist/BUILD",
        "package_group(",
        "    name = 'function_transition_allowlist',",
        "    packages = [",
        // cross-repo allowlists don't work well
        analysisMock.isThisBazel() ? "'public'," : "'//test/...',",
        "    ],",
        ")");
    scratch.file(
        "test/rules.bzl",
        """
        def transition_func(settings, attr):
          return {'t0': {'//command_line_option:cpu': 'k8'}}
        my_transition = transition(implementation = transition_func, inputs = [],
          outputs = ['//command_line_option:cpu'])
        def _my_rule_impl(ctx):
          return []
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep':  attr.label(cfg = my_transition),
          })
        def _simple_rule_impl(ctx):
          return []
        simple_rule = rule(_simple_rule_impl)
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule', 'simple_rule')
        my_rule(name = 'my_rule', dep = ':dep')
        simple_rule(name = 'dep')
        """);

    getConfiguredTarget("//test:my_rule");
    assertNoEvents();
  }

  @Test
  public void testPrintFromTransitionImpl() throws Exception {
    // This test not only asserts expected behavior, it also checks that Starlark transition caching
    // doesn't suppress non-error transition events like print(). Also see
    // transitionErrorAlwaysReported for the equivalent cache test for error events.
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//test/...',
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        "def _transition_impl(settings, attr):",
        "  print('printing from transition impl', settings['//command_line_option:foo'])",
        "  return {'//command_line_option:foo': " + "settings['//command_line_option:foo']+'meow'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//command_line_option:foo'],",
        "  outputs = ['//command_line_option:foo'],",
        ")",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    'dep': attr.label(cfg = my_transition),",
        "  }",
        ")");

    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'test', dep = ':dep')
        my_rule(name = 'dep')
        """);

    useConfiguration("--foo=meow");

    getConfiguredTarget("//test");
    // Test print from top level transition
    assertContainsEvent("printing from transition impl meow");
    // Test print from dep transition
    assertContainsEvent("printing from transition impl meowmeow");
    // Test print from (non-top level) rule class transition
    assertContainsEvent("printing from transition impl meowmeowmeow");
  }

  @Test
  public void transitionErrorAlwaysReported() throws Exception {
    // For performance reasons, Starlark transition calls are cached (see
    // ConfigurationResolver#starlarkTransitionCache). We have to be careful to preserve
    // determinism, which includes consistent error reporting.
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//test/...',
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        def _transition_impl(settings, attr):
            fail('bad transition')
        my_transition = transition(
          implementation = _transition_impl,
          inputs = [],
          outputs = ['//command_line_option:bar'],
        )
        def _rule_impl(ctx):
          return []
        my_rule = rule(
          implementation = _rule_impl,
          cfg = my_transition,
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'mytarget')
        """);

    reporter.removeHandler(failFastHandler);

    // Try #1: this invokes the transition for the first time, which fails.
    getConfiguredTarget("//test:mytarget");
    assertContainsEvent("bad transition");

    // Try #2: make sure the cache doesn't suppress the error message.
    invalidatePackages();
    skyframeExecutor.clearEmittedEventStateForTesting();
    eventCollector.clear();

    getConfiguredTarget("//test:mytarget");
    assertContainsEvent("bad transition");
  }

  @Test
  public void testTransitionEquality() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//test/...',
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        def _transition_impl(settings, attr):
          return {'//command_line_option:foo': 'meow'}
        my_transition = transition(
          implementation = _transition_impl,
          inputs = [],
          outputs = ['//command_line_option:foo'],
        )
        def _rule_impl(ctx):
          return []
        my_rule = rule(
          implementation = _rule_impl,
          cfg = my_transition,
          attrs = {
            'dep': attr.label(cfg = my_transition),
          }
        )
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'test', dep = ':dep')
        my_rule(name = 'dep')
        """);

    useConfiguration("--foo=meow");

    StarlarkDefinedConfigTransition ruleTransition =
        ((StarlarkAttributeTransitionProvider)
                getTarget("//test")
                    .getAssociatedRule()
                    .getRuleClassObject()
                    .getAttributeByName("dep")
                    .getTransitionFactory())
            .getStarlarkDefinedConfigTransitionForTesting();

    StarlarkDefinedConfigTransition attrTransition =
        ((StarlarkRuleTransitionProvider)
                getTarget("//test").getAssociatedRule().getRuleClassObject().getTransitionFactory())
            .getStarlarkDefinedConfigTransitionForTesting();

    assertThat(ruleTransition).isEqualTo(attrTransition);
    assertThat(attrTransition).isEqualTo(ruleTransition);
    assertThat(ruleTransition.hashCode()).isEqualTo(attrTransition.hashCode());
  }

  @Test
  public void testBadAllowlistTransition_allowlistNoCfg() throws Exception {
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//test/...',
            ],
        )
        """);
    scratch.file(
        "test/rules.bzl",
        """
        def _my_rule_impl(ctx):
          return []
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
        #   'dep':  attr.label(cfg = my_transition),
            '_allowlist_function_transition': attr.label(
                default = '//tools/allowlists/function_transition_allowlist',
            ),
          })
        def _simple_rule_impl(ctx):
          return []
        simple_rule = rule(_simple_rule_impl)
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule', 'simple_rule')
        my_rule(name = 'my_rule', dep = ':dep')
        simple_rule(name = 'dep')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_rule");
    assertContainsEvent("Unused function-based split transition allowlist");
  }

  @Test
  public void testLicenseType() throws Exception {
    // Note that attr.license is deprecated, and thus this test is subject to imminent removal.
    // (See --incompatible_no_attr_license). However, this verifies that until the attribute
    // is removed, values of the attribute are a valid Starlark type.
    setBuildLanguageOptions("--incompatible_no_attr_license=false");
    scratch.file(
        "test/rule.bzl",
        """
        def _my_rule_impl(ctx):
          print(ctx.attr.my_license)
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'my_license':  attr.license(),
          })
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'test')
        """);

    getConfiguredTarget("//test:test");

    assertContainsEvent("[\"none\"]");
  }

  @Test
  public void testNativeModuleFields() throws Exception {
    // Check that
    scratch.file(
        "test/file.bzl",
        """
        def valid(s):
            if not s[0].isalpha(): return False
            for c in s.elems():
                if not (c.isalpha() or c == '_' or c.isdigit()): return False
            return True

        bad_names = [name for name in dir(native) if not valid(name)]
        print('bad_names =', bad_names)
        """);
    scratch.file("test/BUILD", "load('//test:file.bzl', 'bad_names')");

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:anything");
    assertContainsEvent("bad_names = []");
  }

  @Test
  public void testDisallowStructProviderSyntax() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=true");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
          return struct() # intentional

        custom_rule = rule(implementation = custom_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'custom_rule')

        custom_rule(name = 'cr')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:cr");
    assertContainsEvent(
        "Returning a struct from a rule implementation function is deprecated and will be "
            + "removed soon. It may be temporarily re-enabled by setting "
            + "--incompatible_disallow_struct_provider_syntax=false");
  }

  @Test
  public void testDisableTargetProviderFields() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disable_target_provider_fields=true",
        "--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/rule.bzl",
        """
        MyProvider = provider()

        def _my_rule_impl(ctx):
          print(ctx.attr.dep.my_info)
        def _dep_rule_impl(ctx):
          my_info = MyProvider(foo = 'bar')
          return [my_info]
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep':  attr.label(),
          })
        dep_rule = rule(implementation = _dep_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load(':rule.bzl', 'my_rule', 'dep_rule')

        my_rule(name = 'r', dep = ':d')
        dep_rule(name = 'd')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:r");
    assertContainsEvent(
        "Accessing providers via the field syntax on structs is deprecated and will be removed "
            + "soon. It may be temporarily re-enabled by setting "
            + "--incompatible_disable_target_provider_fields=false. "
            + "See https://github.com/bazelbuild/bazel/issues/9014 for details.");
  }

  // Verifies that non-provider fields on the 'target' type are still available even with
  // --incompatible_disable_target_provider_fields.
  @Test
  public void testDisableTargetProviderFields_actionsField() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disable_target_provider_fields=true",
        "--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/rule.bzl",
        """
        MyProvider = provider()

        def _my_rule_impl(ctx):
          print(ctx.attr.dep.actions)
        def _dep_rule_impl(ctx):
          my_info = MyProvider(foo = 'bar')
          return [my_info]
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep':  attr.label(),
          })
        dep_rule = rule(implementation = _dep_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load(':rule.bzl', 'my_rule', 'dep_rule')

        my_rule(name = 'r', dep = ':d')
        dep_rule(name = 'd')
        """);

    assertThat(getConfiguredTarget("//test/starlark:r")).isNotNull();
  }

  @Test
  public void testDisableTargetProviderFields_disabled() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disable_target_provider_fields=false",
        "--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/rule.bzl",
        """
        MyProvider = provider()

        def _my_rule_impl(ctx):
          print(ctx.attr.dep.my_info)
        def _dep_rule_impl(ctx):
          my_info = MyProvider(foo = 'bar')
          return struct(my_info = my_info, providers = [my_info])  # intentional
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep':  attr.label(),
          })
        dep_rule = rule(implementation = _dep_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load(':rule.bzl', 'my_rule', 'dep_rule')

        my_rule(name = 'r', dep = ':d')
        dep_rule(name = 'd')
        """);

    assertThat(getConfiguredTarget("//test/starlark:r")).isNotNull();
  }

  @Test
  @Ignore("http://b/344577554")
  public void testNoRuleOutputsParam() throws Exception {
    setBuildLanguageOptions("--incompatible_no_rule_outputs_param=true");
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _impl(ctx):
          output = ctx.outputs.out
          ctx.actions.write(output = output, content = 'hello')

        my_rule = rule(
          implementation = _impl,
          outputs = {"out": "%{name}.txt"})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent(
        "parameter 'outputs' is deprecated and will be removed soon. It may be temporarily "
            + "re-enabled by setting --incompatible_no_rule_outputs_param=false");
  }

  @Test
  public void testExecutableNotInRunfiles() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_file('exe')
          ctx.actions.run_shell(outputs=[exe], command='touch exe')
          runfile = ctx.actions.declare_file('rrr')
          ctx.actions.run_shell(outputs=[runfile], command='touch rrr')
          return DefaultInfo(executable = exe, default_runfiles = ctx.runfiles(files = [runfile]))
        my_rule = rule(implementation = _my_rule_impl, executable = True)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent("exe not included in runfiles");
  }

  @Test
  public void testCommandStringList() throws Exception {
    setBuildLanguageOptions("--incompatible_run_shell_command_string");
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_file('exe')
          ctx.actions.run_shell(outputs=[exe], command=['touch', 'exe'])
          return []
        my_rule = rule(implementation = _my_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent("'command' must be of type string");
  }

  // Regression test for b/180124719.
  @Test
  public void actionsRunShell_argumentsNonSequenceValue_fails() throws Exception {
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_file('exe')
          ctx.actions.run_shell(outputs=[exe], command='touch', arguments='exe')
          return []
        my_rule = rule(implementation = _my_rule_impl)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent("'arguments' got value of type 'string', want 'sequence'");
  }

  /** Starlark integration test that forces inlining. */
  @RunWith(JUnit4.class)
  public static class StarlarkIntegrationTestsWithInlineCalls extends StarlarkIntegrationTest {

    @Override
    protected boolean usesInliningBzlLoadFunction() {
      return true;
    }

    @Override
    @Test
    public void testRecursiveLoad() throws Exception {
      scratch.file("test/starlark/ext2.bzl", "load('//test/starlark:ext1.bzl', 'symbol2')");

      scratch.file("test/starlark/ext1.bzl", "load('//test/starlark:ext2.bzl', 'symbol1')");

      scratch.file(
          "test/starlark/BUILD",
          """
          load('//test/starlark:ext1.bzl', 'custom_rule')
          genrule(name = 'rule')
          """);

      reporter.removeHandler(failFastHandler);
      BuildFileContainsErrorsException e =
          assertThrows(
              BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:rule"));
      assertThat(e)
          .hasMessageThat()
          .contains("Starlark load cycle: [//test/starlark:ext1.bzl, //test/starlark:ext2.bzl]");
    }

    @Override
    @Test
    public void testRecursiveLoad2() throws Exception {
      scratch.file("test/starlark/ext1.bzl", "load('//test/starlark:ext2.bzl', 'symbol2')");
      scratch.file("test/starlark/ext2.bzl", "load('//test/starlark:ext3.bzl', 'symbol3')");
      scratch.file("test/starlark/ext3.bzl", "load('//test/starlark:ext4.bzl', 'symbol4')");
      scratch.file("test/starlark/ext4.bzl", "load('//test/starlark:ext2.bzl', 'symbol2')");

      scratch.file(
          "test/starlark/BUILD",
          """
          load('//test/starlark:ext1.bzl', 'custom_rule')
          genrule(name = 'rule')
          """);

      reporter.removeHandler(failFastHandler);
      BuildFileContainsErrorsException e =
          assertThrows(
              BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:rule"));
      assertThat(e)
          .hasMessageThat()
          .contains(
              "Starlark load cycle: [//test/starlark:ext2.bzl, "
                  + "//test/starlark:ext3.bzl, //test/starlark:ext4.bzl]");
    }
  }

  @Test
  public void testUnhashableInDictForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = [] in {}");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("unhashable type: 'list'");
  }

  @Test
  public void testDictGetUnhashableForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = {}.get({})");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("unhashable type: 'dict'");
  }

  @Test
  public void testUnknownStringEscapesForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = \"\\z\"");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("invalid escape sequence: \\z");
  }

  @Test
  public void testSplitEmptySeparatorForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = 'abc'.split('')");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("Empty separator");
  }

  @Test
  public void testIdentifierAssignmentFromOuterScope2() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        a = [1, 2, 3]
        def f(): a[0] = 9
        y = f()
        fail() if a[0] != 9 else None
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    getConfiguredTarget("//test:r");
  }

  @Test
  public void testIdentifierAssignmentFromOuterScopeForbidden() throws Exception {
    scratch.file(
        "test/extension.bzl",
        """
        a = []
        def f(): a += [1]
        y = f()
        """);

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("local variable 'a' is referenced before assignment");
  }

  @Test
  public void testHashFrozenListForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = []");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        {y: 1}
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("unhashable type: 'list'");
  }

  @Test
  public void testHashFrozenDeepMutableForbidden() throws Exception {
    scratch.file("test/extension.bzl", "y = {}");

    scratch.file(
        "test/BUILD",
        """
        load('//test:extension.bzl', 'y')
        {('a', (y,), True): None}
        cc_library(name = 'r')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:r");
    assertContainsEvent("unhashable type: 'dict'");
  }

  @Test
  public void testNoOutputsError() throws Exception {
    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          ctx.actions.run_shell(outputs=[], command='foo')
        my_rule = rule(implementation = _my_rule_impl, executable = True)
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent("param 'outputs' may not be empty");
  }

  @Test
  public void testDeclareFileInvalidDirectory_withSibling() throws Exception {
    scratch.file("test/dep/test_file.txt", "Test file");

    scratch.file("test/dep/BUILD", "exports_files(['test_file.txt'])");

    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_file('exe', sibling = ctx.file.dep)
          ctx.actions.run_shell(outputs=[exe], command=['touch', 'exe'])
          return []
        my_rule = rule(implementation = _my_rule_impl,
            attrs = {'dep': attr.label(allow_single_file = True)})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target', dep = '//test/dep:test_file.txt')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test/starlark:target");
    assertContainsEvent(
        "the output artifact 'test/dep/exe' is not under package directory "
            + "'test/starlark' for target '//test/starlark:target'");
  }

  @Test
  public void testDeclareFileInvalidDirectory_noSibling() throws Exception {
    scratch.file("test/dep/test_file.txt", "Test file");

    scratch.file("test/dep/BUILD", "exports_files(['test_file.txt'])");

    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_file('/foo/exe')
          ctx.actions.run_shell(outputs=[exe], command=['touch', 'exe'])
          return []
        my_rule = rule(implementation = _my_rule_impl,
            attrs = {'dep': attr.label(allow_single_file = True)})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target', dep = '//test/dep:test_file.txt')
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test/starlark:target")).isNull();
    assertContainsEvent(
        "the output artifact '/foo/exe' is not under package directory "
            + "'test/starlark' for target '//test/starlark:target'");
  }

  @Test
  public void testDeclareDirectoryInvalidParent_withSibling() throws Exception {
    scratch.file("test/dep/test_file.txt", "Test file");

    scratch.file("test/dep/BUILD", "exports_files(['test_file.txt'])");

    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_directory('/foo/exe', sibling = ctx.file.dep)
          ctx.actions.run_shell(outputs=[exe], command=['touch', 'exe'])
          return []
        my_rule = rule(implementation = _my_rule_impl,
            attrs = {'dep': attr.label(allow_single_file = True)})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target', dep = '//test/dep:test_file.txt')
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test/starlark:target")).isNull();
    assertContainsEvent(
        "the output directory '/foo/exe' is not under package directory "
            + "'test/starlark' for target '//test/starlark:target'");
  }

  @Test
  public void testDeclareDirectoryInvalidParent_noSibling() throws Exception {
    scratch.file("test/dep/test_file.txt", "Test file");

    scratch.file("test/dep/BUILD", "exports_files(['test_file.txt'])");

    scratch.file(
        "test/starlark/test_rule.bzl",
        """
        def _my_rule_impl(ctx):
          exe = ctx.actions.declare_directory('/foo/exe')
          ctx.actions.run_shell(outputs=[exe], command=['touch', 'exe'])
          return []
        my_rule = rule(implementation = _my_rule_impl,
            attrs = {'dep': attr.label(allow_single_file = True)})
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:test_rule.bzl', 'my_rule')
        my_rule(name = 'target', dep = '//test/dep:test_file.txt')
        """);

    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test/starlark:target")).isNull();
    assertContainsEvent(
        "the output directory '/foo/exe' is not under package directory "
            + "'test/starlark' for target '//test/starlark:target'");
  }

  @Test
  public void testCustomMallocUnset() throws Exception {
    setUpCustomMallocRule();
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:malloc");
    StructImpl provider = getMyInfoFromTarget(target);
    Object customMalloc = provider.getValue("malloc");
    assertThat(customMalloc).isInstanceOf(NoneType.class);
  }

  @Test
  public void testCustomMallocSet() throws Exception {
    setUpCustomMallocRule();
    useConfiguration("--custom_malloc=//base:system_malloc");
    ConfiguredTarget target = getConfiguredTarget("//test/starlark:malloc");
    StructImpl provider = getMyInfoFromTarget(target);
    RuleConfiguredTarget customMalloc = provider.getValue("malloc", RuleConfiguredTarget.class);
    assertThat(customMalloc.getLabel().getCanonicalForm()).isEqualTo("//base:system_malloc");
  }

  private void setUpCustomMallocRule() throws IOException {
    scratch.overwriteFile("base/BUILD", "cc_library(name = 'system_malloc')");
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')

        def _malloc_rule_impl(ctx):
          return [MyInfo(malloc = ctx.attr._custom_malloc)]

        malloc_rule = rule(
            implementation = _malloc_rule_impl,
            attrs = {
                '_custom_malloc': attr.label(
                    default = configuration_field(
                        fragment = 'cpp',
                        name = 'custom_malloc',
                    ),
                    providers = [CcInfo],
                ),
            }
        )
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:extension.bzl', 'malloc_rule')

        malloc_rule(name = 'malloc')
        """);
  }

  // Test for an interesting situation for the inlining implementation's attempt to process
  // subsequent load statements even when an earlier one has a missing Skyframe dep.
  @Test
  public void bzlFileWithErrorsLoadedThroughMultipleLoadPathsWithTheLatterOneHavingMissingDeps()
      throws Exception {
    scratch.file("test/starlark/error.bzl", "nope");
    scratch.file("test/starlark/ok.bzl", "ok = 42");
    scratch.file(
        "test/starlark/loads-error-and-has-missing-deps.bzl",
        """
        load('//test/starlark:error.bzl', 'doesntmatter')
        load('//test/starlark:ok.bzl', 'ok')
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:error.bzl', 'doesntmatter')
        load('//test/starlark:loads-error-and-has-missing-deps.bzl', 'doesntmatter')
        """);

    reporter.removeHandler(failFastHandler);
    BuildFileContainsErrorsException e =
        assertThrows(
            BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:BUILD"));
    assertThat(e)
        .hasMessageThat()
        .contains("compilation of module 'test/starlark/error.bzl' failed");
  }

  // Test for an interesting situation for the inlining implementation's attempt to process
  // subsequent load statements even when an earlier one has a missing Skyframe dep.
  @Test
  public void bzlFileWithErrorsLoadedThroughMultipleLoadPathsWithTheLatterOneNotHavingMissingDeps()
      throws Exception {
    scratch.file("test/starlark/error.bzl", "nope");
    scratch.file("test/starlark/ok.bzl", "ok = 42");
    scratch.file(
        "test/starlark/loads-error-and-has-missing-deps.bzl",
        """
        load('//test/starlark:error.bzl', 'doesntmatter')
        load('//test/starlark:ok.bzl', 'ok')
        """);
    scratch.file(
        "test/starlark/BUILD",
        """
        load('//test/starlark:ok.bzl', 'ok')
        load('//test/starlark:error.bzl', 'doesntmatter')
        load('//test/starlark:loads-error-and-has-missing-deps.bzl', 'doesntmatter')
        """);

    reporter.removeHandler(failFastHandler);
    BuildFileContainsErrorsException e =
        assertThrows(
            BuildFileContainsErrorsException.class, () -> getTarget("//test/starlark:BUILD"));
    assertThat(e)
        .hasMessageThat()
        .contains("compilation of module 'test/starlark/error.bzl' failed");
  }

  @Test
  public void testStarlarkRulePropagatesRunEnvironmentProvider() throws Exception {
    scratch.file(
        "examples/rules.bzl",
        """
        def my_rule_impl(ctx):
          script = ctx.actions.declare_file(ctx.attr.name)
          ctx.actions.write(script, '', is_executable = True)
          run_env = RunEnvironmentInfo(
            {'FIXED': 'fixed'},
            ['INHERITED']
          )
          return [
            DefaultInfo(executable = script),
            run_env,
          ]
        my_rule = rule(
         implementation = my_rule_impl,
          attrs = {},
          executable = True,
        )
        """);
    scratch.file(
        "examples/BUILD",
        """
        load(':rules.bzl', 'my_rule')
        my_rule(
            name = 'my_target',
        )
        """);

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples:my_target");
    RunEnvironmentInfo provider = starlarkTarget.get(RunEnvironmentInfo.PROVIDER);

    assertThat(provider.getEnvironment()).containsExactly("FIXED", "fixed");
    assertThat(provider.getInheritedEnvironment()).containsExactly("INHERITED");
  }

  @Test
  public void nonExecutableStarlarkRuleReturningRunEnvironmentInfoErrors() throws Exception {
    scratch.file(
        "examples/rules.bzl",
        """
        def my_rule_impl(ctx):
          return [RunEnvironmentInfo()]
        my_rule = rule(
          implementation = my_rule_impl,
          attrs = {},
        )
        """);
    scratch.file(
        "examples/BUILD",
        """
        load(':rules.bzl', 'my_rule')
        my_rule(
            name = 'my_target',
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//examples:my_target");
    assertContainsEvent(
        "in my_rule rule //examples:my_target: Returning RunEnvironmentInfo from a non-executable,"
            + " non-test target has no effect",
        ImmutableSet.of(EventKind.ERROR));
  }

  @Test
  public void nonExecutableStarlarkRuleReturningTestEnvironmentProducesAWarning() throws Exception {
    scratch.file(
        "examples/rules.bzl",
        """
        def my_rule_impl(ctx):
          return [testing.TestEnvironment(environment = {})]
        my_rule = rule(
          implementation = my_rule_impl,
          attrs = {},
        )
        """);
    scratch.file(
        "examples/BUILD",
        """
        load(':rules.bzl', 'my_rule')
        my_rule(
            name = 'my_target',
        )
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//examples:my_target");
    assertContainsEvent(
        "in my_rule rule //examples:my_target: Returning RunEnvironmentInfo from a non-executable,"
            + " non-test target has no effect",
        ImmutableSet.of(EventKind.WARNING));
  }

  @Test
  public void identicalPrintStatementsOnSameLineNotDeduplicated_buildFileLoop() throws Exception {
    scratch.file("foo/BUILD", "[print('this is a print statement') for _ in range(2)]");
    update("//foo:all", /*loadingPhaseThreads=*/ 1, /*doAnalysis=*/ false);
    assertContainsEventWithFrequency("this is a print statement", 2);
  }

  @Test
  public void identicalPrintStatementsOnSameLineNotDeduplicated_macroCalledFromMultipleBuildFiles()
      throws Exception {
    scratch.file("defs/BUILD");
    scratch.file(
        "defs/macro.bzl",
        """
        def macro():
          print('this is a print statement')
        """);
    scratch.file(
        "foo/BUILD",
        """
        load('//defs:macro.bzl', 'macro')
        macro()
        """);
    scratch.file(
        "bar/BUILD",
        """
        load('//defs:macro.bzl', 'macro')
        macro()
        """);
    update("//...", /*loadingPhaseThreads=*/ 1, /*doAnalysis=*/ false);
    assertContainsEventWithFrequency("this is a print statement", 2);
  }

  @Test
  public void identicalPrintStatementsOnSameLineNotDeduplicated_ruleImplementationFunction()
      throws Exception {
    scratch.file(
        "foo/defs.bzl",
        """
        def _my_rule_impl(ctx):
          print('this is a print statement')
        my_rule = rule(implementation = _my_rule_impl)
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(':defs.bzl', 'my_rule')
        my_rule(name = 'a')
        my_rule(name = 'b')
        """);
    update("//foo:all", /*loadingPhaseThreads=*/ 1, /*doAnalysis=*/ true);
    assertContainsEventWithFrequency("this is a print statement", 2);
  }

  @Test
  public void topLevelAspectOnTargetWithNonIdempotentRuleTransition() throws Exception {
    scratch.file(
        "test/aspect.bzl",
        """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        """);

    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//test/...',
            ],
        )
        """);

    scratch.file(
        "test/rules.bzl",
        "def _transition_impl(settings, attr):",
        "  print('printing from transition impl', settings['//command_line_option:foo'])",
        "  return {'//command_line_option:foo': " + "settings['//command_line_option:foo']+'meow'}",
        "my_transition = transition(",
        "  implementation = _transition_impl,",
        "  inputs = ['//command_line_option:foo'],",
        "  outputs = ['//command_line_option:foo'],",
        ")",
        "def _rule_impl(ctx):",
        "  return []",
        "my_rule = rule(",
        "  implementation = _rule_impl,",
        "  cfg = my_transition,",
        "  attrs = {",
        "    'dep': attr.label(cfg = my_transition),",
        "  }",
        ")");

    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'test', dep = ':dep')
        my_rule(name = 'dep')
        """);

    useConfiguration("--foo=meow");

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//test:test"),
            ImmutableList.of("test/aspect.bzl%MyAspect"),
            /* keepGoing= */ true,
            /* loadingPhaseThreads= */ 1,
            /* doAnalysis= */ true,
            new EventBus());
    assertThat(getOnlyElement(analysisResult.getTargetsToBuild()).getLabel().toString())
        .isEqualTo("//test:test");
    AspectKey aspectKey = getOnlyElement(analysisResult.getAspectsMap().keySet());
    assertThat(aspectKey.getAspectClass().getName()).isEqualTo("//test:aspect.bzl%MyAspect");
    assertThat(aspectKey.getLabel().toString()).isEqualTo("//test:test");
  }

  // Regression test for b/295156684.
  @Test
  public void testLabelConstructorFailsInBuildFile() throws Exception {
    // The Label() constructor is not a predeclared symbol for BUILD files, but it can still be
    // called if it's loaded from a .bzl that re-exports it. Test that this doesn't crash.
    scratch.file(
        "test/foo.bzl", //
        "label_builtin = Label");
    scratch.file(
        "test/BUILD",
        """
        load(':foo.bzl', 'label_builtin')
        label_builtin(':something')
        """);

    reporter.removeHandler(failFastHandler);
    getTarget("//test:BUILD");
    assertContainsEvent(
        "Label() can only be used during .bzl initialization (top-level evaluation)",
        ImmutableSet.of(EventKind.ERROR));
  }
}
