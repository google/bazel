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

package com.google.devtools.build.lib.starlark;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.analysis.ActionsProvider;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.BuildInfoFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.StarlarkAction;
import com.google.devtools.build.lib.analysis.configuredtargets.FileConfiguredTarget;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.analysis.starlark.StarlarkExecGroupCollection;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link StarlarkRuleContext}. */
@RunWith(TestParameterInjector.class)
public final class StarlarkRuleContextTest extends BuildViewTestCase {

  private StarlarkRuleContext createRuleContext(String label) throws Exception {
    return new StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null);
  }

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase();

  /** A test rule that exercises the semantics of mandatory providers. */
  private static final MockRule TESTING_RULE_FOR_MANDATORY_PROVIDERS =
      () ->
          MockRule.define(
              "testing_rule_for_mandatory_providers",
              (builder, env) ->
                  builder
                      .setUndocumented()
                      .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                      .add(
                          attr("deps", LABEL_LIST)
                              .legacyAllowAnyFileType()
                              .mandatoryProvidersList(
                                  ImmutableList.of(
                                      ImmutableList.of(StarlarkProviderIdentifier.forLegacy("a")),
                                      ImmutableList.of(
                                          StarlarkProviderIdentifier.forLegacy("b"),
                                          StarlarkProviderIdentifier.forLegacy("c"))))));

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder()
            .addRuleDefinition(TESTING_RULE_FOR_MANDATORY_PROVIDERS);
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  @Before
  public final void setupMyInfoAndGenerateBuildFile() throws Exception {
    scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()");
    scratch.file("myinfo/BUILD");
    scratch.file(
        "foo/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_import")
        package(features = ['-f1', 'f2', 'f3'])
        genrule(name = 'foo',
          cmd = 'dummy_cmd',
          srcs = ['a.txt', 'b.img'],
          tools = ['t.exe'],
          outs = ['c.txt'])
        genrule(name = 'foo2',
          cmd = 'dummy_cmd',
          outs = ['e.txt'])
        genrule(name = 'bar',
          cmd = 'dummy_cmd',
          srcs = [':jl', ':gl'],
          outs = ['d.txt'])
        java_library(name = 'jl',
          srcs = ['a.java'])
        java_import(name = 'asr',
          jars = [ 'asr.jar' ],
          srcjar = 'asr-src.jar',
        )
        genrule(name = 'gl',
          cmd = 'touch $(OUTS)',
          srcs = ['a.go'],
          outs = [ 'gl.a', 'gl.gcgox', ],
          output_to_bindir = 1,
        )
        cc_library(name = 'cc_with_features',
                   srcs = ['dummy.cc'],
                   features = ['f1', '-f3'],
        )
        """);
  }

  private void setRuleContext(StarlarkRuleContext ctx) throws Exception {
    ev.update("ruleContext", ctx);
  }

  private void setUpAttributeErrorTest() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test:macros.bzl', 'macro_native_rule', 'macro_starlark_rule', 'starlark_rule')
        macro_native_rule(name = 'm_native',
          deps = [':jlib'])
        macro_starlark_rule(name = 'm_starlark',
          deps = [':jlib'])
        java_library(name = 'jlib',
          srcs = ['bla.java'])
        cc_library(name = 'cclib',
          deps = [':jlib'])
        starlark_rule(name = 'skyrule',
          deps = [':jlib'])
        """);
    scratch.file(
        "test/macros.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'deps': attr.label_list(providers = ['some_provider'], allow_files=True)
          }
        )
        def macro_native_rule(name, deps):
          native.cc_library(name = name, deps = deps)
        def macro_starlark_rule(name, deps):
          starlark_rule(name = name, deps = deps)
        """);
    reporter.removeHandler(failFastHandler);
  }

  @Test
  public void hasCorrectLocationForRuleAttributeError_NativeRuleWithMacro() throws Exception {
    setUpAttributeErrorTest();
    assertThrows(Exception.class, () -> createRuleContext("//test:m_native"));
    assertContainsEvent("misplaced here");
    // Skip the part of the error message that has details about the allowed deps since the mocks
    // for the mac tests might have different values for them.
    assertContainsEvent(
        ". Since this "
            + "rule was created by the macro 'macro_native_rule', the error might have been caused "
            + "by the macro implementation");
  }

  @Test
  public void hasCorrectLocationForRuleAttributeError_StarlarkRuleWithMacro() throws Exception {
    setUpAttributeErrorTest();
    assertThrows(Exception.class, () -> createRuleContext("//test:m_starlark"));
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:5:20: in deps attribute of starlark_rule rule "
            + "//test:m_starlark: '//test:jlib' does not have mandatory providers:"
            + " 'some_provider'. "
            + "Since this rule was created by the macro 'macro_starlark_rule', the error might "
            + "have been caused by the macro implementation");
  }

  @Test
  public void hasCorrectLocationForRuleAttributeError_NativeRule() throws Exception {
    setUpAttributeErrorTest();
    assertThrows(Exception.class, () -> createRuleContext("//test:cclib"));
    assertContainsEvent("misplaced here");
    // Skip the part of the error message that has details about the allowed deps since the mocks
    // for the mac tests might have different values for them.
    assertDoesNotContainEvent("Since this rule was created by the macro");
  }

  @Test
  public void hasCorrectLocationForRuleAttributeError_StarlarkRule() throws Exception {
    setUpAttributeErrorTest();
    assertThrows(Exception.class, () -> createRuleContext("//test:skyrule"));
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:11:14: in deps attribute of "
            + "starlark_rule rule //test:skyrule: '//test:jlib' does not have mandatory providers: "
            + "'some_provider'");
  }

  @Test
  public void testMandatoryProvidersListWithStarlark() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'starlark_rule', 'my_rule', 'my_other_rule')
        my_rule(name = 'mylib',
          srcs = ['a.py'])
        starlark_rule(name = 'skyrule1',
          deps = [':mylib'])
        my_other_rule(name = 'my_other_lib',
          srcs = ['a.py'])
        starlark_rule(name = 'skyrule2',
          deps = [':my_other_lib'])
        """);
    scratch.file(
        "test/rules.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'deps': attr.label_list(providers = [['a'], ['b', 'c']],
            allow_files=True)
          }
        )
        def my_rule_impl(ctx):
          return struct(a = [])
        my_rule = rule(implementation = my_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        def my_other_rule_impl(ctx):
          return struct(b = [])
        my_other_rule = rule(implementation = my_other_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test:skyrule1")).isNotNull();

    assertThrows(Exception.class, () -> createRuleContext("//test:skyrule2"));
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:8:14: in deps attribute of "
            + "starlark_rule rule //test:skyrule2: '//test:my_other_lib' does not have "
            + "mandatory providers: 'a' or 'c'");
  }

  @Test
  public void testMandatoryProvidersListWithNative() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule', 'my_other_rule')
        my_rule(name = 'mylib',
          srcs = ['a.py'])
        testing_rule_for_mandatory_providers(name = 'skyrule1',
          deps = [':mylib'])
        my_other_rule(name = 'my_other_lib',
          srcs = ['a.py'])
        testing_rule_for_mandatory_providers(name = 'skyrule2',
          deps = [':my_other_lib'])
        """);
    scratch.file(
        "test/rules.bzl",
        """
        def my_rule_impl(ctx):
          return struct(a = [])
        my_rule = rule(implementation = my_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        def my_other_rule_impl(ctx):
          return struct(b = [])
        my_other_rule = rule(implementation = my_other_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        """);
    reporter.removeHandler(failFastHandler);
    assertThat(getConfiguredTarget("//test:skyrule1")).isNotNull();

    assertThrows(Exception.class, () -> createRuleContext("//test:skyrule2"));
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:8:37: in deps attribute of "
            + "testing_rule_for_mandatory_providers rule //test:skyrule2: '//test:my_other_lib' "
            + "does not have mandatory providers: 'a' or 'c'");
  }

  /* Sharing setup code between the testPackageBoundaryError*() methods is not possible since the
   * errors already happen when loading the file. Consequently, all tests would fail at the same
   * statement. */
  @Test
  public void testPackageBoundaryError_nativeRule() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        cc_library(name = 'cclib',
          srcs = ['sub/my_sub_lib.h'])
        """);
    scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:cclib");
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:1:11: Label '//test:sub/my_sub_lib.h' is invalid because "
            + "'test/sub' is a subpackage; perhaps you meant to put the colon here: "
            + "'//test/sub:my_sub_lib.h'?");
  }

  @Test
  public void testPackageBoundaryError_starlarkRule() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:macros.bzl', 'starlark_rule')
        starlark_rule(name = 'skyrule',
          srcs = ['sub/my_sub_lib.h'])
        """);
    scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    scratch.file(
        "test/macros.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:skyrule");
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:14: Label '//test:sub/my_sub_lib.h' is invalid because "
            + "'test/sub' is a subpackage; perhaps you meant to put the colon here: "
            + "'//test/sub:my_sub_lib.h'?");
  }

  @Test
  public void testPackageBoundaryError_starlarkMacro() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:macros.bzl', 'macro_starlark_rule')
        macro_starlark_rule(name = 'm_starlark',
          srcs = ['sub/my_sub_lib.h'])
        """);
    scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    scratch.file(
        "test/macros.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        def macro_starlark_rule(name, srcs=[]):
          starlark_rule(name = name, srcs = srcs)
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:m_starlark");
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:20: Label '//test:sub/my_sub_lib.h' is invalid because"
            + " 'test/sub' is a subpackage; perhaps you meant to put the colon here: "
            + "'//test/sub:my_sub_lib.h'?");
  }

  /* The error message for this case used to be wrong. */
  @Test
  public void testPackageBoundaryError_externalRepository_entirelyInside() throws Exception {
    scratch.file("/r/MODULE.bazel", "module(name = 'r')");
    scratch.file(
        "/r/BUILD",
        """
        cc_library(name = 'cclib',
          srcs = ['sub/my_sub_lib.h'])
        """);
    scratch.file("/r/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    scratch.overwriteFile(
        "MODULE.bazel", "bazel_dep(name = 'r')", "local_path_override(module_name='r', path='/r')");
    invalidatePackages(
        /* alsoConfigs= */ false); // Repository shuffling messes with toolchain labels.
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("@@r+//:cclib");
    assertContainsEvent(
        "/external/r+/BUILD:1:11: Label '@@r+//:sub/my_sub_lib.h' is invalid because "
            + "'@@r+//sub' is a subpackage; perhaps you meant to put the colon here: "
            + "'@@r+//sub:my_sub_lib.h'?");
  }

  /*
   * Making the location in BUILD file the default for "crosses boundary of subpackage" errors does
   * not work in this case since the error actually happens in the bzl file. However, because of
   * the current design, we can neither show the location in the bzl file nor display both
   * locations (BUILD + bzl).
   *
   * Since this case is less common than having such an error in a BUILD file, we can live
   * with it.
   */
  @Test
  public void testPackageBoundaryError_starlarkMacroWithErrorInBzlFile() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:macros.bzl', 'macro_starlark_rule')
        macro_starlark_rule(name = 'm_starlark')
        """);
    scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    scratch.file(
        "test/macros.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        def macro_starlark_rule(name, srcs=[]):
          starlark_rule(name = name, srcs = srcs + ['sub/my_sub_lib.h'])
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:m_starlark");
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:20: Label '//test:sub/my_sub_lib.h' "
            + "is invalid because 'test/sub' is a subpackage");
  }

  @Test
  public void testPackageBoundaryError_nativeMacro() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:macros.bzl', 'macro_native_rule')
        macro_native_rule(name = 'm_native',
          srcs = ['sub/my_sub_lib.h'])
        """);
    scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])");
    scratch.file(
        "test/macros.bzl",
        """
        def macro_native_rule(name, deps=[], srcs=[]):
          native.cc_library(name = name, deps = deps, srcs = srcs)
        """);
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:m_native");
    assertContainsEvent(
        "ERROR /workspace/test/BUILD:2:18: Label '//test:sub/my_sub_lib.h' "
            + "is invalid because 'test/sub' is a subpackage");
  }

  @Test
  public void shouldGetPrerequisiteArtifacts() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.files.srcs");
    assertArtifactList(result, ImmutableList.of("a.txt", "b.img"));
  }

  private static void assertArtifactList(Object result, List<String> artifacts) {
    assertThat(result).isInstanceOf(Sequence.class);
    Sequence<?> resultList = (Sequence) result;
    assertThat(resultList).hasSize(artifacts.size());
    int i = 0;
    for (String artifact : artifacts) {
      assertThat(((Artifact) resultList.get(i++)).getFilename()).isEqualTo(artifact);
    }
  }

  @Test
  public void shouldGetPrerequisites() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:bar");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.srcs");
    // Check for a known provider
    TransitiveInfoCollection tic1 = (TransitiveInfoCollection) ((Sequence) result).get(0);
    assertThat(JavaInfo.getProvider(JavaSourceJarsProvider.class, tic1)).isNotNull();
    // Check an unimplemented provider too
    assertThat(tic1.get("not_implemented_provider")).isNull();
  }

  @Test
  public void shouldGetPrerequisite() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:asr");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.srcjar");
    TransitiveInfoCollection tic = (TransitiveInfoCollection) result;
    assertThat(tic).isInstanceOf(FileConfiguredTarget.class);
    assertThat(tic.getLabel().getName()).isEqualTo("asr-src.jar");
  }

  @Test
  public void testGetRuleAttributeListType() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.outs");
    assertThat(result).isInstanceOf(Sequence.class);
  }

  @Test
  public void testGetRuleAttributeListValue() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.outs");
    assertThat(((Sequence) result)).hasSize(1);
  }

  @Test
  public void testGetRuleAttributeListValueNoGet() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.outs");
    assertThat(((Sequence) result)).hasSize(1);
  }

  @Test
  public void testGetRuleAttributeStringTypeValue() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.cmd");
    assertThat((String) result).isEqualTo("dummy_cmd");
  }

  @Test
  public void testGetRuleAttributeStringTypeValueNoGet() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.attr.cmd");
    assertThat((String) result).isEqualTo("dummy_cmd");
  }

  @Test
  public void testGetRuleAttributeBadAttributeName() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    ev.checkEvalErrorContains("No attribute 'bad'", "ruleContext.attr.bad");
  }

  @Test
  public void testGetRuleAttributeNoAspectHints() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    ev.checkEvalErrorContains("No attribute 'aspect_hints'", "ruleContext.attr.aspect_hints");
  }

  @Test
  public void testGetLabel() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.label");
    assertThat(((Label) result).toString()).isEqualTo("//foo:foo");
  }

  @Test
  public void testRuleError() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    ev.checkEvalErrorContains("message", "fail('message')");
  }

  @Test
  public void testAttributeError() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    ev.checkEvalErrorContains("attribute srcs: message", "fail(attr='srcs', msg='message')");
  }

  private void writeToolUsingRule() throws Exception {
    // Write a rule with an executable implicit attribute.
    scratch.file(
        "a/rule.bzl",
        """
        def _impl(ctx):
            pass
        sample = rule(
            implementation = _impl,
            attrs = {
                '_tool': attr.label(
                    cfg = 'exec',
                    executable = True,
                    default = '//a:tool'),
            }
        )
        """);

    // Use the rule.
    scratch.file(
        "a/BUILD",
        """
        load('rule.bzl', 'sample')
        cc_binary(name = 'tool')
        sample(name = 'sample')
        """);
  }

  @Test
  public void testGetExecutablePrerequisite() throws Exception {
    writeToolUsingRule();

    setRuleContext(createRuleContext("//a:sample"));
    Object result = ev.eval("ruleContext.executable._tool");
    assertThat(((Artifact) result).getFilename()).matches("^tool(\\.exe)?$");
  }

  @Test
  public void testCreateSpawnActionArgumentsWithExecutableFilesToRunProvider() throws Exception {
    writeToolUsingRule();

    StarlarkRuleContext ruleContext = createRuleContext("//a:sample");
    setRuleContext(ruleContext);
    ev.exec(
        "output = ruleContext.actions.declare_file(ruleContext.attr.name + '_out')",
        "ruleContext.actions.run(",
        "  outputs = [output],",
        "  arguments = ['--a','--b'],",
        "  executable = ruleContext.executable._tool)");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertThat(action.getCommandFilename()).matches("^.*/tool(\\.exe)?$");
  }

  @Test
  public void testGetExecutablePrerequisite_forNativeRuleWithLabelList() throws Exception {
    // Starlark rules only support executable=True on LABEL attributes, but native rules support it
    // for LABEL_LIST as well. This became a problem when we started creating StarlarkRuleContexts
    // for native rules for builtins injection. We work around it by not populating the executable
    // field for these rules.
    scratch.file(
        "pkg/BUILD",
        """
        extra_action(
            name = 'foo',
            cmd = 'cmd',
            out_templates = ['foo.out'],
            tools = [':tool1', ':tool2']  # not allowed in Starlark-defined rules
        )
        cc_binary(
            name = 'tool1',
            srcs = ['tool1.cc'],
        )
        cc_binary(
            name = 'tool2',
            srcs = ['tool2.cc'],
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//pkg:foo");
    setRuleContext(ruleContext);
    assertThat((Boolean) ev.eval("hasattr(ruleContext.executable, 'tools')")).isFalse();
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithUnusedInputsList() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    ev.exec(
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  executable = 'executable',",
        "  unused_inputs_list = ruleContext.files.srcs[0])");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertThat(action.getUnusedInputsList()).isPresent();
    assertThat(action.getUnusedInputsList().get().getFilename()).isEqualTo("a.txt");
    assertThat(action.discoversInputs()).isTrue();
    assertThat(action.isShareable()).isFalse();
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_success() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  if os == \"osx\":",
        "    return {\"cpu\": 2., \"memory\": 350. + inputs_size * 20, \"local_test\": 2.}",
        "  return {\"cpu\": 1., \"memory\": 350. + inputs_size * 10, \"local_test\": 0.}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    assertThat(action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2))
        .isEqualTo(ResourceSet.create(370, 1, 0));
    assertThat(action.getResourceSetOrBuilder().buildResourceSet(OS.DARWIN, 2))
        .isEqualTo(ResourceSet.create(390, 2, 2));
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_noneResourceSet() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  if os == \"osx\":",
        "    return {\"cpu\": 2., \"memory\": 350. + inputs_size * 20, \"local_test\": 2.}",
        "  return {\"cpu\": 1., \"memory\": 350. + inputs_size * 10, \"local_test\": 0.}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = None,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    assertThat(action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2))
        .isEqualTo(ResourceSet.create(250, 1, 0));
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_lambdaForbidden() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    Exception thrown =
        assertThrows(
            EvalException.class,
            () ->
                ev.exec(
                    "ruleContext.actions.run(",
                    "  inputs = ruleContext.files.srcs,",
                    "  outputs = ruleContext.files.srcs,",
                    "  resource_set = lambda os, inputs_size : {\"cpu\": 1., \"memory\": 1.,"
                        + " \"local_test\": 1.} ,",
                    "  executable = 'executable')"));

    assertThat(thrown).hasMessageThat().contains("must be declared by a top-level def statement");
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_illegalResource() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  return {\"cpu\": 2., \"memory\": 350., \"local_test\": 2., \"gpu\": 1.}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    Exception thrown =
        assertThrows(
            ExecException.class,
            () -> action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2));
    assertThat(thrown).hasMessageThat().contains("Illegal resource keys: (gpu)");
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_defaultValue() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  return {\"cpu\": 2., \"local_test\": 2.}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    assertThat(action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2))
        .isEqualTo(ResourceSet.create(250, 2, 2));
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_intDict() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": 3}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    assertThat(action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 0))
        .isEqualTo(ResourceSet.create(2, 1, 3));
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_notDict() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  return \"keks\"",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    Exception thrown =
        assertThrows(
            ExecException.class,
            () -> action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2));
    assertThat(thrown).hasMessageThat().contains("got string for 'resource_set', want dict");
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_wrongDict() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os, inputs_size):",
        "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": \"hi\"}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    Exception thrown =
        assertThrows(
            ExecException.class,
            () -> action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2));
    assertThat(thrown).hasMessageThat().contains("Illegal resource value type for key local_test");
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithResourceSet_incorrectSignature()
      throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);

    ev.exec(
        "def get_resources(os):",
        "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": \"hi\"}",
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  resource_set = get_resources,",
        "  executable = 'executable')");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());

    Exception thrown =
        assertThrows(
            ExecException.class,
            () -> action.getResourceSetOrBuilder().buildResourceSet(OS.LINUX, 2));
    assertThat(thrown)
        .hasMessageThat()
        .contains("get_resources() accepts no more than 1 positional argument but got 2");
  }

  @Test
  public void testCreateStarlarkActionArgumentsWithoutUnusedInputsList() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    ev.exec(
        "ruleContext.actions.run(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  executable = 'executable',",
        "  unused_inputs_list = None)");
    StarlarkAction action =
        (StarlarkAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertThat(action.getUnusedInputsList()).isEmpty();
    assertThat(action.discoversInputs()).isFalse();
  }

  @Test
  public void testOutputs() throws Exception {
    setRuleContext(createRuleContext("//foo:bar"));
    Iterable<?> result = (Iterable) ev.eval("ruleContext.outputs.outs");
    assertThat(((Artifact) Iterables.getOnlyElement(result)).getFilename()).isEqualTo("d.txt");
  }

  // Regression test for b/329066920
  @Test
  public void outputsAddOutput_keyCollision_failsCleanly() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _undertest_impl(ctx):
            pass

        undertest_rule = rule(
            implementation = _undertest_impl,
            attrs = {'out_collision': attr.output(),},
            outputs = {'out_collision': '%{name}.out'},
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
        )
        """);

    checkError("//test:undertest", "Multiple outputs with the same key: out_collision");
  }

  @Test
  public void testStarlarkRuleContextGetDefaultShellEnv() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.configuration.default_shell_env");
    assertThat(result).isInstanceOf(Dict.class);
  }

  @Test
  public void testCheckPlaceholders() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.check_placeholders('%{name}', ['name'])");
    assertThat(result).isEqualTo(true);
  }

  @Test
  public void testCheckPlaceholdersBadPlaceholder() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.check_placeholders('%{name}', ['abc'])");
    assertThat(result).isEqualTo(false);
  }

  @Test
  public void testExpandMakeVariables() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.expand_make_variables('cmd', '$(ABC)', {'ABC': 'DEF'})");
    assertThat(result).isEqualTo("DEF");
  }

  @Test
  public void testExpandMakeVariablesShell() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.expand_make_variables('cmd', '$$ABC', {})");
    assertThat(result).isEqualTo("$ABC");
  }

  private void setUpMakeVarToolchain() throws Exception {
    scratch.file(
        "vars/vars.bzl",
        """
        def _make_var_supplier_impl(ctx):
          val = ctx.attr.value
          return [platform_common.TemplateVariableInfo({'MAKE_VAR_VALUE': val})]
        make_var_supplier = rule(
            implementation = _make_var_supplier_impl,
            attrs = {
                'value': attr.string(mandatory = True),
            })
        def _make_var_user_impl(ctx):
          return []
        make_var_user = rule(
            implementation = _make_var_user_impl,
        )
        """);
    scratch.file(
        "vars/BUILD",
        """
        load(':vars.bzl', 'make_var_supplier', 'make_var_user')
        make_var_supplier(name = 'supplier', value = 'foo')
        cc_toolchain_alias(name = 'current_cc_toolchain')
        make_var_user(
            name = 'vars',
            toolchains = [':supplier', ':current_cc_toolchain'],
        )
        """);
  }

  @Test
  public void testExpandMakeVariables_cc() throws Exception {
    setUpMakeVarToolchain();
    setRuleContext(createRuleContext("//vars:vars"));
    String result = (String) ev.eval("ruleContext.expand_make_variables('cmd', '$(CC)', {})");
    assertThat(result).isNotEmpty();
  }

  @Test
  public void testExpandMakeVariables_toolchain() throws Exception {
    setUpMakeVarToolchain();
    setRuleContext(createRuleContext("//vars:vars"));
    Object result = ev.eval("ruleContext.expand_make_variables('cmd', '$(MAKE_VAR_VALUE)', {})");
    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void testVar_toolchain() throws Exception {
    setUpMakeVarToolchain();
    setRuleContext(createRuleContext("//vars:vars"));
    Object result = ev.eval("ruleContext.var['MAKE_VAR_VALUE']");
    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void testConfiguration() throws Exception {
    StarlarkRuleContext ruleContext = createRuleContext("//foo:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.configuration");
    assertThat(ruleContext.getRuleContext().getConfiguration()).isSameInstanceAs(result);
  }

  @Test
  public void testFeatures() throws Exception {
    setRuleContext(createRuleContext("//foo:cc_with_features"));
    Object result = ev.eval("ruleContext.features");
    assertThat((Sequence) result).containsExactly("f1", "f2");
  }

  @Test
  public void testDisabledFeatures() throws Exception {
    setRuleContext(createRuleContext("//foo:cc_with_features"));
    Object result = ev.eval("ruleContext.disabled_features");
    assertThat((Sequence) result).containsExactly("f3");
  }

  @Test
  public void testDeriveArtifact() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.actions.declare_file('a/b.txt')");
    PathFragment fragment = ((Artifact) result).getRootRelativePath();
    assertThat(fragment.getPathString()).isEqualTo("foo/a/b.txt");
  }

  @Test
  public void testDeriveTreeArtifact() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result = ev.eval("ruleContext.actions.declare_directory('a/b')");
    Artifact artifact = (Artifact) result;
    PathFragment fragment = artifact.getRootRelativePath();
    assertThat(fragment.getPathString()).isEqualTo("foo/a/b");
    assertThat(artifact.isTreeArtifact()).isTrue();
  }

  @Test
  public void testDeriveTreeArtifactType() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    String result = (String) ev.eval("type(ruleContext.actions.declare_directory('a/b'))");
    assertThat(result).isEqualTo("File");
  }

  @Test
  public void testDeriveTreeArtifactNextToSibling() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Artifact artifact =
        (Artifact)
            ev.eval(
                "ruleContext.actions.declare_directory('c',"
                    + " sibling=ruleContext.actions.declare_directory('a/b'))");
    PathFragment fragment = artifact.getRootRelativePath();
    assertThat(fragment.getPathString()).isEqualTo("foo/a/c");
    assertThat(artifact.isTreeArtifact()).isTrue();
  }

  @Test
  public void testParamFileSuffix() throws Exception {
    setRuleContext(createRuleContext("//foo:foo"));
    Object result =
        ev.eval(
            "ruleContext.actions.declare_file(ruleContext.files.tools[0].basename + '.params', "
                + "sibling = ruleContext.files.tools[0])");
    PathFragment fragment = ((Artifact) result).getRootRelativePath();
    assertThat(fragment.getPathString()).isEqualTo("foo/t.exe.params");
  }

  @Test
  public void testLabelKeyedStringDictConvertsToTargetToStringMap() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "filegroup(name='dep')",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={':dep': 'value'})");

    invalidatePackages();
    setRuleContext(createRuleContext("//:r"));
    Label keyLabel = (Label) ev.eval("ruleContext.attr.label_dict.keys()[0].label");
    assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:dep"));
    String valueString = (String) ev.eval("ruleContext.attr.label_dict.values()[0]");
    assertThat(valueString).isEqualTo("value");
  }

  @Test
  public void testLabelKeyedStringDictTranslatesAliases() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "filegroup(name='dep')",
        "alias(name='alias', actual='dep')",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={':alias': 'value'})");

    invalidatePackages();
    setRuleContext(createRuleContext("//:r"));
    Label keyLabel = (Label) ev.eval("ruleContext.attr.label_dict.keys()[0].label");
    assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:dep"));
    String valueString = (String) ev.eval("ruleContext.attr.label_dict.values()[0]");
    assertThat(valueString).isEqualTo("value");
  }

  @Test
  public void testLabelKeyedStringDictAcceptsDefaultValues() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(default={Label('//:default'): 'defs'}),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "filegroup(name='default')",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r')");

    invalidatePackages();
    setRuleContext(createRuleContext("//:r"));
    Label keyLabel = (Label) ev.eval("ruleContext.attr.label_dict.keys()[0].label");
    assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:default"));
    String valueString = (String) ev.eval("ruleContext.attr.label_dict.values()[0]");
    assertThat(valueString).isEqualTo("defs");
  }

  @Test
  public void testLabelKeyedStringDictAllowsFilesWhenAllowFilesIsTrue() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=True),
          }
        )
        """);

    scratch.file("myfile.cc");

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={'myfile.cc': 'value'})");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelKeyedStringDictAllowsFilesOfAppropriateTypes() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=['.cc']),
          }
        )
        """);

    scratch.file("myfile.cc");

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={'myfile.cc': 'value'})");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelKeyedStringDictForbidsFilesOfIncorrectTypes() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=['.cc']),
          }
        )
        """);

    scratch.file("myfile.cpp");

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={'myfile.cpp': 'value'})");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent("file '//:myfile.cpp' is misplaced here (expected .cc)");
  }

  @Test
  public void testLabelKeyedStringDictForbidsFilesWhenAllowFilesIsFalse() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=False),
          }
        )
        """);

    scratch.file("myfile.cpp");

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={'myfile.cpp': 'value'})");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent(
        "in label_dict attribute of my_rule rule //:r: "
            + "source file '//:myfile.cpp' is misplaced here (expected no files)");
  }

  @Test
  public void testLabelKeyedStringDictAllowsRulesWithRequiredProviders_legacy() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(providers=[['my_provider']]),
          }
        )
        def _dep_impl(ctx):
          return struct(my_provider=5)
        my_dep_rule = rule(
          implementation = _dep_impl,
          attrs = {}
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
        "my_dep_rule(name='dep')",
        "my_rule(name='r',",
        "        label_dict={':dep': 'value'})");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelKeyedStringDictAllowsRulesWithRequiredProviders() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(providers=[MyInfo]),
          }
        )
        def _dep_impl(ctx):
          return MyInfo(my_provider=5)
        my_dep_rule = rule(
          implementation = _dep_impl,
          attrs = {}
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
        "my_dep_rule(name='dep')",
        "my_rule(name='r',",
        "        label_dict={':dep': 'value'})");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelKeyedStringDictForbidsRulesMissingRequiredProviders() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(providers=[['my_provider']]),
          }
        )
        def _dep_impl(ctx):
          return
        my_dep_rule = rule(
          implementation = _dep_impl,
          attrs = {}
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
        "my_dep_rule(name='dep')",
        "my_rule(name='r',",
        "        label_dict={':dep': 'value'})");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent(
        "in label_dict attribute of my_rule rule //:r: "
            + "'//:dep' does not have mandatory providers: 'my_provider'");
  }

  @Test
  public void testLabelKeyedStringDictForbidsEmptyDictWhenAllowEmptyIsFalse() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_empty=False),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={})");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent(
        "in label_dict attribute of my_rule rule //:r: " + "attribute must be non empty");
  }

  @Test
  public void testLabelKeyedStringDictAllowsEmptyDictWhenAllowEmptyIsTrue() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_empty=True),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_dict={})");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelListNoDuplicatesNoError() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("a.txt", "");
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        """);

    scratch.file(
        "BUILD",
        "load('//:my_rule.bzl', 'my_rule')",
        "my_rule(name='r',",
        "        label_list=[\"a.txt\"])");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelListNoDuplicatesNonOverlappingSelectsNoError() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("a.txt", "");
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        """);
    scratch.file(
        "BUILD",
        """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list = select({
            ":arm_cpu": [],
            "//conditions:default": ["a.txt"],
          }) + select({
              ":arm_cpu": ["a.txt"],
              "//conditions:default": [],
          }),
        )
        """);
    invalidatePackages();
    getConfiguredTarget("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelListNoDuplicatesOverlappingSelectsHasError() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("a.txt", "");
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        """);
    scratch.file(
        "BUILD",
        """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list = select({
            ":arm_cpu": [],
            "//conditions:default": ["a.txt"],
          }) + select({
              ":arm_cpu": ["a.txt"],
              "//conditions:default": ["a.txt"],
          }),
        )
        """);

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent(
        "in label_list attribute of my_rule rule //:r: " + "Label \'//:a.txt\' is duplicated");
  }

  @Test
  public void testLabelKeyedStringDictForbidsMissingAttributeWhenMandatoryIsTrue()
      throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(mandatory=True),
          }
        )
        """);

    scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')");

    invalidatePackages();
    getConfiguredTarget("//:r");
    assertContainsEvent("missing value for mandatory attribute 'label_dict' in 'my_rule' rule");
  }

  @Test
  public void testLabelKeyedStringDictAllowsMissingAttributeWhenMandatoryIsFalse()
      throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(mandatory=False),
          }
        )
        """);

    scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')");

    invalidatePackages();
    createRuleContext("//:r");
    assertNoEvents();
  }

  @Test
  public void testLabelAttributeDefault() throws Exception {
    scratch.file(
        "my_rule.bzl",
        """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'explicit_dep': attr.label(default = Label('//:dep')),
            '_implicit_dep': attr.label(default = Label('//:dep')),
            'explicit_dep_list': attr.label_list(default = [Label('//:dep')]),
            '_implicit_dep_list': attr.label_list(default = [Label('//:dep')]),
          }
        )
        """);

    scratch.file(
        "BUILD", "filegroup(name='dep')", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')");

    invalidatePackages();
    setRuleContext(createRuleContext("//:r"));
    Label explicitDepLabel = (Label) ev.eval("ruleContext.attr.explicit_dep.label");
    assertThat(explicitDepLabel).isEqualTo(Label.parseCanonical("//:dep"));
    Label implicitDepLabel = (Label) ev.eval("ruleContext.attr._implicit_dep.label");
    assertThat(implicitDepLabel).isEqualTo(Label.parseCanonical("//:dep"));
    Label explicitDepListLabel = (Label) ev.eval("ruleContext.attr.explicit_dep_list[0].label");
    assertThat(explicitDepListLabel).isEqualTo(Label.parseCanonical("//:dep"));
    Label implicitDepListLabel = (Label) ev.eval("ruleContext.attr._implicit_dep_list[0].label");
    assertThat(implicitDepListLabel).isEqualTo(Label.parseCanonical("//:dep"));
  }

  @Test
  public void testRelativeLabelInExternalRepository() throws Exception {
    scratch.file(
        "external_rule.bzl",
        """
        def _impl(ctx):
          return
        external_rule = rule(
          implementation = _impl,
          attrs = {
            'internal_dep': attr.label(default = Label('//:dep'))
          }
        )
        """);

    scratch.file("BUILD", "filegroup(name='dep')");

    scratch.file("/r/MODULE.bazel", "module(name='r')");
    scratch.file(
        "/r/a/BUILD",
        """
        load('@@//:external_rule.bzl', 'external_rule')
        external_rule(name='r')
        """);

    scratch.overwriteFile(
        "MODULE.bazel", "bazel_dep(name='r')", "local_path_override(module_name='r', path='/r')");

    invalidatePackages(
        /* alsoConfigs= */ false); // Repository shuffling messes with toolchain labels.
    setRuleContext(createRuleContext("@@r+//a:r"));
    Label depLabel = (Label) ev.eval("ruleContext.attr.internal_dep.label");
    assertThat(depLabel).isEqualTo(Label.parseCanonical("//:dep"));
  }

  @Test
  public void testAccessingRunfiles() throws Exception {
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file("test/__init__.py");
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        getPyLoad("py_binary"),
        "load('//test:rule.bzl', 'starlark_rule')",
        "py_binary(name = 'lib', srcs = ['lib.py', 'lib2.py'])",
        "starlark_rule(name = 'foo', dep = ':lib')",
        "py_binary(name = 'lib_with_init', srcs = ['lib_with_init.py', 'lib2.py', '__init__.py'])",
        "starlark_rule(name = 'foo_with_init', dep = ':lib_with_init')");

    setRuleContext(createRuleContext("//test:foo"));
    Object filenames =
        ev.eval("[f.short_path for f in ruleContext.attr.dep.default_runfiles.files.to_list()]");
    assertThat(filenames).isInstanceOf(Sequence.class);
    Sequence<?> filenamesList = (Sequence) filenames;
    assertThat(filenamesList).containsAtLeast("test/lib.py", "test/lib2.py");

    setRuleContext(createRuleContext("//test:foo_with_init"));
    Object noEmptyFilenames =
        ev.eval("ruleContext.attr.dep.default_runfiles.empty_filenames.to_list()");
    assertThat(noEmptyFilenames).isInstanceOf(Sequence.class);
    Sequence<?> noEmptyFilenamesList = (Sequence) noEmptyFilenames;
    assertThat(noEmptyFilenamesList).isEmpty();
  }

  @Test
  public void testAccessingRunfilesSymlinks_legacy() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file(
        "test/rule.bzl",
        """
        def symlink_impl(ctx):
          symlinks = {
            'symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              symlinks=symlinks,
            )
          )
        symlink_rule = rule(
          implementation = symlink_impl,
          attrs = {
            'symlink': attr.label(allow_files=True),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_rule(name = 'lib_with_symlink', symlink = ':a.py')
        foo_binary(
          name = 'test_with_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_symlink'],
        )
        """);
    setRuleContext(createRuleContext("//test:test_with_symlink"));
    Object symlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> symlinkPathsList = (Sequence) symlinkPaths;
    assertThat(symlinkPathsList).containsExactly("symlink_test/a.py").inOrder();
    Object symlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> symlinkFilenamesList = (Sequence) symlinkFilenames;
    assertThat(symlinkFilenamesList).containsExactly("test/a.py").inOrder();
  }

  @Test
  public void testAccessingRunfilesSymlinks() throws Exception {
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file(
        "test/rule.bzl",
        """
        def symlink_impl(ctx):
          symlinks = {
            'symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              symlinks=symlinks,
            )
          )
        symlink_rule = rule(
          implementation = symlink_impl,
          attrs = {
            'symlink': attr.label(allow_files=True),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_rule(name = 'lib_with_symlink', symlink = ':a.py')
        foo_binary(
          name = 'test_with_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_symlink'],
        )
        """);
    setRuleContext(createRuleContext("//test:test_with_symlink"));
    Object symlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> symlinkPathsList = (Sequence) symlinkPaths;
    assertThat(symlinkPathsList).containsExactly("symlink_test/a.py").inOrder();
    Object symlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> symlinkFilenamesList = (Sequence) symlinkFilenames;
    assertThat(symlinkFilenamesList).containsExactly("test/a.py").inOrder();
  }

  @Test
  public void testAccessingRunfilesRootSymlinks_legacy() throws Exception {
    setBuildLanguageOptions("--incompatible_disallow_struct_provider_syntax=false");
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file(
        "test/rule.bzl",
        """
        def root_symlink_impl(ctx):
          root_symlinks = {
            'root_symlink_' + f.short_path: f
            for f in ctx.files.root_symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              root_symlinks=root_symlinks,
            )
          )
        root_symlink_rule = rule(
          implementation = root_symlink_impl,
          attrs = {
            'root_symlink': attr.label(allow_files=True)
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'root_symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        root_symlink_rule(name = 'lib_with_root_symlink', root_symlink = ':a.py')
        foo_binary(
          name = 'test_with_root_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_root_symlink'],
        )
        """);
    setRuleContext(createRuleContext("//test:test_with_root_symlink"));
    Object rootSymlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");
    assertThat(rootSymlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkPathsList = (Sequence) rootSymlinkPaths;
    assertThat(rootSymlinkPathsList).containsExactly("root_symlink_test/a.py").inOrder();
    Object rootSymlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");
    assertThat(rootSymlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkFilenamesList = (Sequence) rootSymlinkFilenames;
    assertThat(rootSymlinkFilenamesList).containsExactly("test/a.py").inOrder();
  }

  @Test
  public void testAccessingRunfilesRootSymlinks() throws Exception {
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file(
        "test/rule.bzl",
        """
        def root_symlink_impl(ctx):
          root_symlinks = {
            'root_symlink_' + f.short_path: f
            for f in ctx.files.root_symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              root_symlinks=root_symlinks,
            )
          )
        root_symlink_rule = rule(
          implementation = root_symlink_impl,
          attrs = {
            'root_symlink': attr.label(allow_files=True)
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'root_symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        root_symlink_rule(name = 'lib_with_root_symlink', root_symlink = ':a.py')
        foo_binary(
          name = 'test_with_root_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_root_symlink'],
        )
        """);
    setRuleContext(createRuleContext("//test:test_with_root_symlink"));
    Object rootSymlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");
    assertThat(rootSymlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkPathsList = (Sequence) rootSymlinkPaths;
    assertThat(rootSymlinkPathsList).containsExactly("root_symlink_test/a.py").inOrder();
    Object rootSymlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");
    assertThat(rootSymlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkFilenamesList = (Sequence) rootSymlinkFilenames;
    assertThat(rootSymlinkFilenamesList).containsExactly("test/a.py").inOrder();
  }

  @Test
  public void testForwardingDefaultInfoRetainsDataRunfiles() throws Exception {
    scratch.file(
        "bar/rules.bzl",
        """
        def _forward_default_info_impl(ctx):
            return [
                ctx.attr.target[DefaultInfo],
            ]
        forward_default_info = rule(
            implementation = _forward_default_info_impl,
            attrs = {
                'target': attr.label(
                    mandatory = True,
                ),
            },
        )
        """);
    scratch.file("bar/i_am_a_runfile");
    scratch.file(
        "bar/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        load(':rules.bzl', 'forward_default_info')
        java_library(
            name = 'lib',
            data = ['i_am_a_runfile'],
        )
        forward_default_info(
            name = 'forwarded_lib',
            target = ':lib',
        )
        """);

    ConfiguredTarget nativeTarget = getConfiguredTarget("//bar:lib");

    ImmutableList<Artifact> nativeRunfiles =
        getDataRunfiles(nativeTarget).getAllArtifacts().toList();
    ConfiguredTarget forwardedTarget = getConfiguredTarget("//bar:forwarded_lib");
    ImmutableList<Artifact> forwardedRunfiles =
        getDataRunfiles(forwardedTarget).getAllArtifacts().toList();
    assertThat(forwardedRunfiles).isEqualTo(nativeRunfiles);
    assertThat(forwardedRunfiles).hasSize(1);
    assertThat(forwardedRunfiles.get(0).getPath().getBaseName()).isEqualTo("i_am_a_runfile");
  }

  @Test
  public void testAccessingRunfilesSymlinksAsDepsets() throws Exception {
    // Prepare rule using ctx.runfiles() with `symlinks`/`root_symlinks` kwargs.
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file(
        "test/rule.bzl",
        """
        def symlink_impl(ctx):
          symlinks = {
            'symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          root_symlinks = {
            'root_symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          runfiles_from_dict = ctx.runfiles(
            symlinks=symlinks,
            root_symlinks=root_symlinks,
          )
          runfiles_from_depset = ctx.runfiles(
            symlinks = runfiles_from_dict.symlinks,
            root_symlinks = runfiles_from_dict.root_symlinks,
          )

          return DefaultInfo(runfiles = runfiles_from_depset,)
        symlink_rule = rule(
          implementation = symlink_impl,
          attrs = {
            'symlink': attr.label(allow_files=True),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_rule(name = 'lib_with_symlink', symlink = ':a.py')
        foo_binary(
          name = 'test_with_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_symlink'],
        )
        """);
    setRuleContext(createRuleContext("//test:test_with_symlink"));

    // Evaluate path expression for runfiles symlinks.
    Object symlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    Object rootSymlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");

    // Confirm expected runfiles symlink behavior in returned sequences.
    assertThat(symlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> symlinkPathsList = (Sequence) symlinkPaths;
    assertThat(symlinkPathsList).containsExactly("symlink_test/a.py").inOrder();
    Object symlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> symlinkFilenamesList = (Sequence) symlinkFilenames;
    assertThat(symlinkFilenamesList).containsExactly("test/a.py").inOrder();
    assertThat(rootSymlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkPathsList = (Sequence) rootSymlinkPaths;
    assertThat(rootSymlinkPathsList).containsExactly("root_symlink_test/a.py").inOrder();
    Object rootSymlinkFilenames =
        ev.eval(
            "[s.target_file.short_path for s in"
                + " ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]");
    assertThat(rootSymlinkFilenames).isInstanceOf(Sequence.class);
    Sequence<?> rootSymlinkFilenamesList = (Sequence) rootSymlinkFilenames;
    assertThat(rootSymlinkFilenamesList).containsExactly("test/a.py").inOrder();
  }

  @Test
  public void runfiles_merge() throws Exception {
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file("test/other.py");
    scratch.file(
        "test/rule.bzl",
        """
        def symlink_merge_impl(ctx):
          runfiles = ctx.runfiles(symlinks = {
            'symlink_' + ctx.file.symlink.short_path: ctx.file.symlink
          })
          if ctx.attr.dep:
            runfiles = runfiles.merge(ctx.attr.dep[DefaultInfo].default_runfiles)
          return DefaultInfo(
            runfiles = runfiles
          )
        symlink_merge_rule = rule(
          implementation = symlink_merge_impl,
          attrs = {
            'symlink': attr.label(allow_single_file=True),
            'dep': attr.label(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'symlink_merge_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_merge_rule(name = 'lib_a', symlink = ':a.py', dep = 'lib_b')
        symlink_merge_rule(name = 'lib_b', symlink = ':b.py')
        foo_binary(
          name = 'test',
          srcs = ['test/other.py'],
          data = [':lib_a'],
        )
        """);
    setRuleContext(createRuleContext("//test:test"));
    Object symlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> symlinkPathsList = (Sequence) symlinkPaths;
    assertThat(symlinkPathsList)
        .containsExactly("symlink_test/a.py", "symlink_test/b.py")
        .inOrder();
  }

  @Test
  public void runfiles_mergeAll() throws Exception {
    scratch.file("test/a.py");
    scratch.file("test/b.py");
    scratch.file("test/c.py");
    scratch.file("test/other.py");
    scratch.file(
        "test/rule.bzl",
        """
        def symlink_merge_all_impl(ctx):
          runfiles = ctx.runfiles(symlinks = {
            'symlink_' + ctx.file.symlink.short_path: ctx.file.symlink
          })
          if ctx.attr.deps:
            runfiles = runfiles.merge_all([dep[DefaultInfo].default_runfiles
                                           for dep in ctx.attr.deps])
          return DefaultInfo(
            runfiles = runfiles
          )
        symlink_merge_all_rule = rule(
          implementation = symlink_merge_all_impl,
          attrs = {
            'symlink': attr.label(allow_single_file=True),
            'deps': attr.label_list(),
          },
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'symlink_merge_all_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_merge_all_rule(name = 'lib_a', symlink = ':a.py', deps = [':lib_b', ':lib_c'])
        symlink_merge_all_rule(name = 'lib_b', symlink = ':b.py')
        symlink_merge_all_rule(name = 'lib_c', symlink = ':c.py')
        foo_binary(
          name = 'test',
          srcs = ['test/other.py'],
          data = [':lib_a'],
        )
        """);
    setRuleContext(createRuleContext("//test:test"));
    Object symlinkPaths =
        ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]");
    assertThat(symlinkPaths).isInstanceOf(Sequence.class);
    Sequence<?> symlinkPathsList = Sequence.cast(symlinkPaths, String.class, "symlinkPaths");
    assertThat(symlinkPathsList)
        .containsExactly("symlink_test/a.py", "symlink_test/b.py", "symlink_test/c.py")
        .inOrder();
  }

  @Test
  public void runfiles_incompatibleTransitiveFilesOrder() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _bad_runfiles_impl(ctx):
          ctx.runfiles(transitive_files = depset(order = 'preorder'))
        bad_runfiles = rule(implementation = _bad_runfiles_impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'bad_runfiles')
        bad_runfiles(name = 'test')
        """);
    reporter.removeHandler(failFastHandler); // Error expected.
    assertThat(getConfiguredTarget("//test:test")).isNull();
    assertContainsEvent("Error in runfiles: order 'preorder' is invalid for transitive_files");
  }

  // regression test for b/237547165
  @Test
  public void runfiles_failOnRunfilesTreeInFiles() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
          internal_output_group = ctx.attr.bin[OutputGroupInfo]._hidden_top_level_INTERNAL_
          ctx.runfiles(files = internal_output_group.to_list())
        bad_runfiles = rule(
          implementation = _impl,
          attrs = {'bin' : attr.label()}
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'bad_runfiles')
        cc_binary(name = 'bin')
        bad_runfiles(name = 'test', bin = ':bin')
        """);

    reporter.removeHandler(failFastHandler); // Error expected.
    assertThat(getConfiguredTarget("//test:test")).isNull();
    assertContainsEvent(
        "Error in runfiles: could not add all 'files': unexpected runfiles tree artifact");
  }

  @Test
  public void testExternalShortPath() throws Exception {
    scratch.file("/bar/MODULE.bazel", "module(name='foo')");
    scratch.file("/bar/bar.txt");
    scratch.file("/bar/BUILD", "exports_files(['bar.txt'])");
    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='foo')",
        "local_path_override(module_name='foo', path='/bar')");
    scratch.file(
        "test/BUILD",
        """
        genrule(
            name = 'lib',
            srcs = ['@foo//:bar.txt'],
            cmd = 'echo $(SRCS) $@',
            outs = ['lib.out'],
            executable = 1,
        )
        """);
    invalidatePackages();
    StarlarkRuleContext ruleContext = createRuleContext("//test:lib");
    setRuleContext(ruleContext);
    String filename = ev.eval("ruleContext.files.srcs[0].short_path").toString();
    assertThat(filename).isEqualTo("../foo+/bar.txt");
  }

  // Borrowed from Scratch.java.
  private static String linesAsString(String... lines) {
    StringBuilder builder = new StringBuilder();
    for (String line : lines) {
      builder.append(line);
      builder.append('\n');
    }
    return builder.toString();
  }

  // The common structure of the following actions tests is a rule under test depended upon by
  // a testing rule, where the rule under test has one output and one caller-supplied action.

  private static String getSimpleUnderTestDefinition(
      boolean withStarlarkTestable, String[] actionLines) {
    return linesAsString(
        // TODO(b/153667498): Just passing fail to map_each parameter of Args.add_all does not work.
        "def fail_with_message(s):",
        "    fail(s)",
        "",
        "def _undertest_impl(ctx):",
        "  out = ctx.outputs.out",
        "  " + Joiner.on("\n  ").join(actionLines),
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        withStarlarkTestable ? "  _skylark_testable = True," : "",
        ")");
  }

  private static String getSimpleUnderTestDefinition(String... actionLines) {
    return getSimpleUnderTestDefinition(true, actionLines);
  }

  private static String getSimpleNontestableUnderTestDefinition(String... actionLines) {
    return getSimpleUnderTestDefinition(false, actionLines);
  }

  private final String testingRuleDefinition =
      linesAsString(
          "def _testing_impl(ctx):",
          "  pass",
          "testing_rule = rule(",
          "  implementation = _testing_impl,",
          "  attrs = {'dep': attr.label()},",
          ")");

  private final String simpleBuildDefinition =
      linesAsString(
          "load(':rules.bzl', 'undertest_rule', 'testing_rule')",
          "undertest_rule(",
          "    name = 'undertest',",
          ")",
          "testing_rule(",
          "    name = 'testing',",
          "    dep = ':undertest',",
          ")");

  @Test
  public void testDependencyActionsProvider() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);

    Object provider = ev.eval("ruleContext.attr.dep[Actions]");
    assertThat(provider).isInstanceOf(StructImpl.class);
    assertThat(((StructImpl) provider).getProvider()).isEqualTo(ActionsProvider.INSTANCE);
    ev.update("actions", provider);

    Map<?, ?> mapping = (Dict<?, ?>) ev.eval("actions.by_file");
    assertThat(mapping).hasSize(1);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    Object actionUnchecked = ev.eval("actions.by_file[file]");
    assertThat(actionUnchecked).isInstanceOf(ActionAnalysisMetadata.class);
  }

  @Test
  public void testNoAccessToDependencyActionsWithoutStarlarkTest() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/rules.bzl",
        getSimpleNontestableUnderTestDefinition(
            "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);

    Exception e = assertThrows(Exception.class, () -> ev.eval("ruleContext.attr.dep[Actions]"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "<target //test:undertest> (rule 'undertest_rule') doesn't contain "
                + "declared provider 'Actions'");
  }

  @Test
  public void testAbstractActionInterface() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disallow_struct_provider_syntax=false",
        "--incompatible_no_rule_outputs_param=false");
    scratch.file(
        "test/rules.bzl",
        "def _undertest_impl(ctx):",
        "  out1 = ctx.outputs.out1",
        "  out2 = ctx.outputs.out2",
        "  ctx.actions.write(output=out1, content='foo123')",
        "  ctx.actions.run_shell(outputs=[out2], inputs=[out1],",
        "                        command='cp ' + out1.path + ' ' + out2.path)",
        "  return struct(out1=out1, out2=out2)",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out1': '%{name}1.txt',",
        "             'out2': '%{name}2.txt'},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file1", ev.eval("ruleContext.attr.dep.out1"));
    ev.update("file2", ev.eval("ruleContext.attr.dep.out2"));
    ev.update("action1", ev.eval("ruleContext.attr.dep[Actions].by_file[file1]"));
    ev.update("action2", ev.eval("ruleContext.attr.dep[Actions].by_file[file2]"));

    assertThat(ev.eval("action1.inputs")).isInstanceOf(Depset.class);
    assertThat(ev.eval("action1.outputs")).isInstanceOf(Depset.class);

    assertThat(ev.eval("action1.argv")).isEqualTo(Starlark.NONE);
    assertThat(ev.eval("action2.content")).isEqualTo(Starlark.NONE);
    assertThat(ev.eval("action1.substitutions")).isEqualTo(Starlark.NONE);

    assertThat(ev.eval("action1.inputs.to_list()")).isEqualTo(ev.eval("[]"));
    assertThat(ev.eval("action1.outputs.to_list()")).isEqualTo(ev.eval("[file1]"));
    assertThat(ev.eval("action2.inputs.to_list()")).isEqualTo(ev.eval("[file1]"));
    assertThat(ev.eval("action2.outputs.to_list()")).isEqualTo(ev.eval("[file2]"));
  }

  // For created_actions() tests, the "undertest" rule represents both the code under test and the
  // Starlark user test code itself.

  @Test
  public void testCreatedActions() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disallow_struct_provider_syntax=false",
        "--incompatible_no_rule_outputs_param=false");
    // createRuleContext() gives us the context for a rule upon entry into its analysis function.
    // But we need to inspect the result of calling created_actions() after the rule context has
    // been modified by creating actions. So we'll call created_actions() from within the analysis
    // function and pass it along as a provider.
    scratch.file(
        "test/rules.bzl",
        "def _undertest_impl(ctx):",
        "  out1 = ctx.outputs.out1",
        "  out2 = ctx.outputs.out2",
        "  ctx.actions.run_shell(outputs=[out1], command='echo foo123 > ' + out1.path,",
        "                        mnemonic='foo')",
        "  v = ctx.created_actions().by_file",
        "  ctx.actions.run_shell(outputs=[out2], command='echo bar123 > ' + out2.path)",
        "  return struct(v=v, out1=out1, out2=out2)",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out1': '%{name}1.txt',",
        "             'out2': '%{name}2.txt'},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);

    Object mapUnchecked = ev.eval("ruleContext.attr.dep.v");
    assertThat(mapUnchecked).isInstanceOf(Dict.class);
    Map<?, ?> map = (Dict) mapUnchecked;
    // Should only have the first action because created_actions() was called
    // before the second action was created.
    Object file = ev.eval("ruleContext.attr.dep.out1");
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(file);
    Object actionUnchecked = map.get(file);
    assertThat(actionUnchecked).isInstanceOf(ActionAnalysisMetadata.class);
    assertThat(((ActionAnalysisMetadata) actionUnchecked).getMnemonic()).isEqualTo("foo");
  }

  @Test
  public void testNoAccessToCreatedActionsWithoutStarlarkTest() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleNontestableUnderTestDefinition(
            "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"));
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:undertest");
    setRuleContext(ruleContext);

    Object result = ev.eval("ruleContext.created_actions()");
    assertThat(result).isEqualTo(Starlark.NONE);
  }

  @Test
  public void testSpawnActionInterface() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object argvUnchecked = ev.eval("action.argv");
    assertThat(argvUnchecked).isInstanceOf(StarlarkList.class);
    StarlarkList<?> argv = (StarlarkList) argvUnchecked;
    assertThat((List<?>) argv).hasSize(3);
    assertThat(argv.isImmutable()).isTrue();
    Object result = ev.eval("action.argv[2].startswith('echo foo123')");
    assertThat((Boolean) result).isTrue();
  }

  @Test
  public void testRunShellUsesHelperScriptForLongCommand() throws Exception {
    setBuildLanguageOptions(
        "--incompatible_disallow_struct_provider_syntax=false",
        "--incompatible_no_rule_outputs_param=false");
    // createRuleContext() gives us the context for a rule upon entry into its analysis function.
    // But we need to inspect the result of calling created_actions() after the rule context has
    // been modified by creating actions. So we'll call created_actions() from within the analysis
    // function and pass it along as a provider.
    scratch.file(
        "test/rules.bzl",
        "def _undertest_impl(ctx):",
        "  out1 = ctx.outputs.out1",
        "  out2 = ctx.outputs.out2",
        "  out3 = ctx.outputs.out3",
        "  ctx.actions.run_shell(outputs=[out1],",
        "                        command='( %s ; ) > $1' % (",
        "                            ' ; '.join(['echo xxx%d' % i for i in range(0, 7000)])),",
        "                        mnemonic='mnemonic1',",
        "                        arguments=[out1.path])",
        "  ctx.actions.run_shell(outputs=[out2],",
        "                        command='echo foo > ' + out2.path,",
        "                        mnemonic='mnemonic2')",
        "  ctx.actions.run_shell(outputs=[out3],",
        "                        command='( %s ; ) > $1' % (",
        "                            ' ; '.join(['echo yyy%d' % i for i in range(0, 7000)])),",
        "                        mnemonic='mnemonic3',",
        "                        arguments=[out3.path])",
        "  v = ctx.created_actions().by_file",
        "  return struct(v=v, out1=out1, out2=out2, out3=out3)",
        "",
        "undertest_rule = rule(",
        "    implementation=_undertest_impl,",
        "    outputs={'out1': '%{name}1.txt',",
        "             'out2': '%{name}2.txt',",
        "             'out3': '%{name}3.txt'},",
        "    _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);

    Object mapUnchecked = ev.eval("ruleContext.attr.dep.v");
    assertThat(mapUnchecked).isInstanceOf(Dict.class);
    Map<?, ?> map = (Dict) mapUnchecked;
    Object out1 = ev.eval("ruleContext.attr.dep.out1");
    Object out2 = ev.eval("ruleContext.attr.dep.out2");
    Object out3 = ev.eval("ruleContext.attr.dep.out3");
    // 5 actions in total: 3 SpawnActions and 2 FileWriteActions for the two long commands.
    assertThat(map).hasSize(5);
    assertThat(map).containsKey(out1);
    assertThat(map).containsKey(out2);
    assertThat(map).containsKey(out3);
    Object action1Unchecked = map.get(out1);
    Object action2Unchecked = map.get(out2);
    Object action3Unchecked = map.get(out3);
    assertThat(action1Unchecked).isInstanceOf(ActionAnalysisMetadata.class);
    assertThat(action2Unchecked).isInstanceOf(ActionAnalysisMetadata.class);
    assertThat(action3Unchecked).isInstanceOf(ActionAnalysisMetadata.class);
    ActionAnalysisMetadata spawnAction1 = (ActionAnalysisMetadata) action1Unchecked;
    ActionAnalysisMetadata spawnAction2 = (ActionAnalysisMetadata) action2Unchecked;
    ActionAnalysisMetadata spawnAction3 = (ActionAnalysisMetadata) action3Unchecked;
    assertThat(spawnAction1.getMnemonic()).isEqualTo("mnemonic1");
    assertThat(spawnAction2.getMnemonic()).isEqualTo("mnemonic2");
    assertThat(spawnAction3.getMnemonic()).isEqualTo("mnemonic3");
    Artifact helper1 =
        Iterables.getOnlyElement(
            Iterables.filter(
                spawnAction1.getInputs().toList(),
                a -> a.getFilename().equals("undertest.run_shell_0.sh")));
    assertThat(
            Iterables.filter(
                spawnAction2.getInputs().toList(), a -> a.getFilename().contains("run_shell_")))
        .isEmpty();
    Artifact helper3 =
        Iterables.getOnlyElement(
            Iterables.filter(
                spawnAction3.getInputs().toList(),
                a -> a.getFilename().equals("undertest.run_shell_2.sh")));
    assertThat(map).containsKey(helper1);
    assertThat(map).containsKey(helper3);
    Object action4Unchecked = map.get(helper1);
    Object action5Unchecked = map.get(helper3);
    assertThat(action4Unchecked).isInstanceOf(FileWriteAction.class);
    assertThat(action5Unchecked).isInstanceOf(FileWriteAction.class);
    FileWriteAction fileWriteAction1 = (FileWriteAction) action4Unchecked;
    FileWriteAction fileWriteAction2 = (FileWriteAction) action5Unchecked;
    assertThat(fileWriteAction1.getFileContents()).contains("echo xxx6999 ;");
    assertThat(fileWriteAction2.getFileContents()).contains("echo yyy6999 ;");
  }

  @Test
  public void testInvalidMnemonic() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
          out = ctx.actions.declare_file('f')
          ctx.actions.run_shell(
              outputs=[out], command='false', mnemonic='@@@')
        r = rule(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'r')
        r(name = 'target')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:target");
    assertContainsEvent(
        "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"@@@\"");
  }

  @Test
  public void testFileWriteActionInterface() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition("ctx.actions.write(output=out, content='foo123')"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    assertThat(contentUnchecked).isEqualTo("foo123");
  }

  @Test
  public void testFileWriteActionInterfaceWithArgs() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "args = ctx.actions.args()",
            "args.add('foo123')",
            "ctx.actions.write(output=out, content=args)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    // Args content ends the file with a newline
    assertThat(contentUnchecked).isEqualTo("foo123\n");
  }

  @Test
  public void testFileWriteActionInterfaceWithArgsContainingTreeArtifact() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "directory = ctx.actions.declare_directory('dir')",
            "ctx.actions.run_shell(",
            "    outputs = [directory],",
            "    command = 'mkdir {out}'",
            ")",
            "args = ctx.actions.args()",
            "args.add_all([directory])",
            "ctx.actions.write(output=out, content=args)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    // If the Args contain a directory File that needs to be expanded, the contents are not known
    // at analysis time.
    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isEqualTo(Starlark.NONE);
  }

  @Test
  public void testFileWriteActionInterfaceWithArgsExpansionError() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "args = ctx.actions.args()",
            "args.add_all(['args expansion error message'], map_each = fail_with_message)",
            "ctx.actions.write(output=out, content=args)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    // If there's a failure when expanding Args, that error message is propagated.
    EvalException e =
        assertThrows(
            "Should be an error expanding action.content",
            EvalException.class,
            () -> ev.eval("action.content"));

    // e has a trivial stack (just <expr>, aka action.content), but its message
    // contains a stack that has evidently been flattened into a string and passed
    // through an event reporter as an ERROR at :7:15 (?).
    // Ideally we would remove some of this cruft.
    // ```
    // Error expanding command line:
    //
    //     /workspace/test/rules.bzl:7:15: Traceback (most recent call last):
    //          File "/workspace/test/rules.bzl", line 2, column 9, in fail_with_message
    //     Error in fail: args expansion error message
    // ```

    // stack=[fail_with_message@rules.bzl:2, fail@<builtin>]
    assertThat(e).hasMessageThat().contains("Error expanding command line:");
    assertThat(e)
        .hasMessageThat()
        .contains("File \"/workspace/test/rules.bzl\", line 2, column 9, in fail_with_message");
    assertThat(e).hasMessageThat().contains("Error in fail: args expansion error message");
  }

  @Test
  public void testArgsMapEachFunctionMustBeGlobal() throws Exception {
    // lambda
    scratch.file(
        "p/inc.bzl",
        """
        def _impl(ctx):
          ctx.actions.args().add_all([], map_each=lambda x: x)  # error
        r = rule(implementation=_impl)
        """);
    scratch.file(
        "p/BUILD",
        """
        load('inc.bzl', 'r')
        r(name='r')
        """);
    AssertionError ex = assertThrows(AssertionError.class, () -> getConfiguredTarget("//p:r"));
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "map_each function (declared at /workspace/p/inc.bzl:2:43) must be "
                + "declared by a top-level def statement");

    // non-global def
    scratch.file(
        "q/inc.bzl",
        """
        def _impl(ctx):
          def id(x): return x
          ctx.actions.args().add_all([], map_each=id)  # error
        r = rule(implementation=_impl)
        """);
    scratch.file(
        "q/BUILD",
        """
        load('inc.bzl', 'r')
        r(name='r')
        """);
    ex = assertThrows(AssertionError.class, () -> getConfiguredTarget("//q:r"));
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "map_each function (declared at /workspace/q/inc.bzl:2:7) must be "
                + "declared by a top-level def statement");
  }

  @Test
  public void testArgsMapEachFunctionAllowClosure() throws Exception {
    // lambda
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition(
            "def local_fn(x): return 'local:%s' % x",
            "args = ctx.actions.args()",
            "args.add_all(['a', 'b'], allow_closure=True, map_each=lambda x: 'lambda:%s' % x)",
            "args.add_joined(['c', 'd'], join_with=';', allow_closure=True, map_each=local_fn)",
            "args.set_param_file_format('multiline')",
            "ctx.actions.write(output=out, content=args)"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    // Args content ends the file with a newline
    assertThat(ev.eval("action.content")).isEqualTo("lambda:a\nlambda:b\nlocal:c;local:d\n");
  }

  @Test
  public void testArgsMapEachWithPathMapper() throws Exception {
    scratch.file(
        "test/rules.bzl",
        getSimpleUnderTestDefinition("ctx.actions.write(out, '')"),
        testingRuleDefinition);
    scratch.file("test/BUILD", simpleBuildDefinition);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);

    ev.update("file1", ev.eval("ruleContext.actions.declare_file('file1')"));
    ev.update("file2", ev.eval("ruleContext.actions.declare_file('dir/file2')"));
    Object result =
        ev.eval(
            "ruleContext.actions.args().add_all("
                + "  [file1, file2],"
                + "  allow_closure=True,"
                + "  map_each=lambda f: 'file:%s:%s:%s:%s:%s' % ("
                // Verify that mapped roots are comparable.
                + "        f.path, f.dirname, f.root.path, type(f.root), f.root <= f.root)"
                + ")");

    PathMapper stripConfig =
        execPath -> execPath.subFragment(0, 1).getRelative(execPath.subFragment(2));

    assertThat(result).isInstanceOf(Args.class);
    CommandLine args = ((Args) result).build(() -> RepositoryMapping.ALWAYS_FALLBACK);
    String out = TestConstants.PRODUCT_NAME + "-out";
    assertThat(args.arguments(null, stripConfig))
        .containsExactly(
            String.format("file:%1$s/bin/test/file1:%1$s/bin/test:%1$s/bin:mapped_root:True", out),
            String.format(
                "file:%1$s/bin/test/dir/file2:%1$s/bin/test/dir:%1$s/bin:mapped_root:True", out))
        .inOrder();
  }

  @Test
  public void testTemplateExpansionActionInterface() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _undertest_impl(ctx):",
        "  out = ctx.outputs.out",
        "  ctx.actions.expand_template(output=out,",
        "                              template=ctx.file.template, substitutions={'a': 'b'})",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True)},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "aaaaa", "bcdef");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    assertThat(contentUnchecked).isEqualTo("bbbbb\nbcdef\n");

    Object substitutionsUnchecked = ev.eval("action.substitutions");
    assertThat(substitutionsUnchecked).isInstanceOf(Dict.class);
    assertThat(substitutionsUnchecked).isEqualTo(ImmutableMap.of("a", "b"));
  }

  private void setUpCoverageInstrumentedTest() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        cc_library(
          name = 'foo',
          srcs = ['foo.cc'],
          deps = [':bar'],
        )
        cc_library(
          name = 'bar',
          srcs = ['bar.cc'],
        )
        """);
  }

  @Test
  public void testCoverageInstrumentedCoverageDisabled() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--nocollect_code_coverage", "--instrumentation_filter=.");
    StarlarkRuleContext ruleContext = createRuleContext("//test:foo");
    setRuleContext(ruleContext);
    Object result = ev.eval("ruleContext.coverage_instrumented()");
    assertThat((Boolean) result).isFalse();
  }

  @Test
  public void testCoverageInstrumentedFalseForSourceFileLabel() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=.");
    setRuleContext(createRuleContext("//test:foo"));
    Object result = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.srcs[0])");
    assertThat((Boolean) result).isFalse();
  }

  @Test
  public void testCoverageInstrumentedDoesNotMatchFilter() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo");
    setRuleContext(createRuleContext("//test:bar"));
    Object result = ev.eval("ruleContext.coverage_instrumented()");
    assertThat((Boolean) result).isFalse();
  }

  @Test
  public void testCoverageInstrumentedMatchesFilter() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo");
    setRuleContext(createRuleContext("//test:foo"));
    Object result = ev.eval("ruleContext.coverage_instrumented()");
    assertThat((Boolean) result).isTrue();
  }

  @Test
  public void testCoverageInstrumentedDoesNotMatchFilterNonDefaultLabel() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo");
    setRuleContext(createRuleContext("//test:foo"));
    // //test:bar does not match :foo, though //test:foo would.
    Object result = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.deps[0])");
    assertThat((Boolean) result).isFalse();
  }

  @Test
  public void testCoverageInstrumentedMatchesFilterNonDefaultLabel() throws Exception {
    setUpCoverageInstrumentedTest();
    useConfiguration("--collect_code_coverage", "--instrumentation_filter=:bar");
    setRuleContext(createRuleContext("//test:foo"));
    // //test:bar does match :bar, though //test:foo would not.
    Object result = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.deps[0])");
    assertThat((Boolean) result).isTrue();
  }

  // A list of attributes and methods ctx objects have
  private static final ImmutableList<String> CTX_ATTRIBUTES =
      ImmutableList.of(
          "attr",
          "split_attr",
          "executable",
          "file",
          "files",
          "workspace_name",
          "label",
          "fragments",
          "configuration",
          "coverage_instrumented(dep)",
          "features",
          "bin_dir",
          "genfiles_dir",
          "outputs",
          "rule",
          "aspect_ids",
          "var",
          "tokenize('foo')",
          "actions.declare_file('foo.txt')",
          "actions.declare_file('foo.txt', sibling = file)",
          "actions.declare_directory('foo.txt')",
          "actions.declare_directory('foo.txt', sibling = file)",
          "actions.do_nothing(mnemonic = 'foo', inputs = [file])",
          "actions.expand_template(template = file, output = file, substitutions = {})",
          "actions.run(executable = file, outputs = [file])",
          "actions.run_shell(command = 'foo', outputs = [file])",
          "actions.write(file, 'foo')",
          "check_placeholders('foo', [])",
          "build_file_path",
          "runfiles()",
          "resolve_command(command = 'foo')",
          "resolve_tools()");

  @Test
  public void testFrozenRuleContextHasInaccessibleAttributes() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'main_rule', 'dep_rule')
        dep_rule(name = 'dep')
        main_rule(name = 'main', deps = [':dep'])
        """);
    scratch.file("test/rules.bzl");

    for (String attribute : CTX_ATTRIBUTES) {
      scratch.overwriteFile(
          "test/rules.bzl",
          "load('//myinfo:myinfo.bzl', 'MyInfo')",
          "def _main_impl(ctx):",
          "  dep = ctx.attr.deps[0]",
          "  file = ctx.outputs.file",
          "  foo = dep[MyInfo].dep_ctx." + attribute,
          "main_rule = rule(",
          "  implementation = _main_impl,",
          "  attrs = {",
          "    'deps': attr.label_list()",
          "  },",
          "  outputs = {'file': 'output.txt'},",
          ")",
          "def _dep_impl(ctx):",
          "  return MyInfo(dep_ctx = ctx)",
          "dep_rule = rule(implementation = _dep_impl)");
      initializeSkyframeExecutor();
      AssertionError e =
          assertThrows(
              "Should have been unable to access dep_ctx." + attribute,
              AssertionError.class,
              () -> getConfiguredTarget("//test:main"));
      assertThat(e)
          .hasMessageThat()
          .contains(
              "cannot access field or method '"
                  + Iterables.get(Splitter.on('(').split(attribute), 0)
                  + "' of rule context for '//test:dep' outside of its own rule implementation "
                  + "function");
    }
  }

  @Test
  public void testFrozenRuleContextForAspectsHasInaccessibleAttributes() throws Exception {
    List<String> attributes = new ArrayList<>();
    attributes.addAll(CTX_ATTRIBUTES);
    attributes.addAll(
        ImmutableList.of("rule.attr", "rule.executable", "rule.file", "rule.files", "rule.kind"));
    scratch.file(
        "test/BUILD",
        """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'dep')
        my_rule(name = 'mid', deps = [':dep'])
        my_rule(name = 'main', deps = [':mid'])
        """);
    scratch.file("test/rules.bzl");
    for (String attribute : attributes) {
      scratch.overwriteFile(
          "test/rules.bzl",
          "def _rule_impl(ctx):",
          "  pass",
          "def _aspect_impl(target, ctx):",
          "  if ctx.rule.attr.deps:",
          "    dep = ctx.rule.attr.deps[0]",
          "    file = ctx.actions.declare_file('file.txt')",
          "    foo = dep." + (attribute.startsWith("rule.") ? "" : "ctx.") + attribute,
          "  return struct(ctx = ctx, rule=ctx.rule)",
          "MyAspect = aspect(implementation=_aspect_impl)",
          "my_rule = rule(",
          "  implementation = _rule_impl,",
          "  attrs = {",
          "    'deps': attr.label_list(aspects = [MyAspect])",
          "  },",
          ")");

      reporter.removeHandler(failFastHandler);
      invalidatePackages();

      getConfiguredTarget("//test:main");

      // Typical value of e.getMessage():
      //
      // ERROR /workspace/test/BUILD:3:8: \
      //   in //test:rules.bzl%MyAspect aspect on my_rule rule //test:mid:
      // Traceback (most recent call last):
      //        File "/workspace/test/BUILD", line 3, column 8, in //test:rules.bzl%MyAspect
      //        File "/workspace/test/rules.bzl", line 7, column 18, in _aspect_impl
      // Error: cannot access field or method 'attr' of rule context for '//test:dep' \
      // outside of its own rule implementation function
      assertContainsEvent(
          "cannot access field or method '"
              + Iterables.get(Splitter.on('(').split(attribute), 0)
              + "' of rule context for '//test:dep' outside of its own rule implementation "
              + "function");
    }
  }

  @Test
  public void testMapAttributeOrdering() throws Exception {
    scratch.file(
        "a/a.bzl",
        """
        key_provider = provider(fields=['keys'])
        def _impl(ctx):
          return [key_provider(keys=ctx.attr.value.keys())]
        a = rule(implementation=_impl, attrs={'value': attr.string_dict()})
        """);
    scratch.file(
        "a/BUILD",
        """
        load(':a.bzl', 'a')
        a(name='a', value={'c': 'c', 'b': 'b', 'a': 'a', 'f': 'f', 'e': 'e', 'd': 'd'})
        """);

    ConfiguredTarget a = getConfiguredTarget("//a");
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(keyForBuild(Label.parseCanonical("//a:a.bzl")), "key_provider");

    StarlarkInfo keyInfo = (StarlarkInfo) a.get(key);
    Sequence<?> keys = (Sequence) keyInfo.getValue("keys");
    assertThat(keys).containsExactly("c", "b", "a", "f", "e", "d").inOrder();
  }

  private void writeIntFlagBuildSettingFiles() throws Exception {
    scratch.file(
        "test/build_setting.bzl",
        """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        int_flag = rule(
          implementation = _impl,
          build_setting = config.int(flag = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:build_setting.bzl', 'int_flag')
        int_flag(name = 'int_flag', build_setting_default = 42)
        """);
  }

  @Test
  public void testBuildSettingValue_explicitlySet() throws Exception {
    writeIntFlagBuildSettingFiles();
    useConfiguration("--//test:int_flag=24");

    ConfiguredTarget buildSetting = getConfiguredTarget("//test:int_flag");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    StructImpl buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isEqualTo(StarlarkInt.of(24));
  }

  @Test
  public void testBuildSettingValue_defaultFallback() throws Exception {
    writeIntFlagBuildSettingFiles();

    ConfiguredTarget buildSetting = getConfiguredTarget("//test:int_flag");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    StructImpl buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isEqualTo(StarlarkInt.of(42));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testBuildSettingValue_allowMultipleSetting() throws Exception {
    scratch.file(
        "test/build_setting.bzl",
        """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        string_flag = rule(
          implementation = _impl,
          build_setting = config.string(flag = True, allow_multiple = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:build_setting.bzl', 'string_flag')
        string_flag(name = 'string_flag', build_setting_default = 'some-value')
        """);

    // from default
    ConfiguredTarget buildSetting = getConfiguredTarget("//test:string_flag");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    StructImpl buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isInstanceOf(List.class);
    assertThat((List<String>) buildSettingInfo.getValue("value")).containsExactly("some-value");

    // Set multiple times
    useConfiguration(
        "--//test:string_flag=some-other-value", "--//test:string_flag=some-other-other-value");
    buildSetting = getConfiguredTarget("//test:string_flag");
    key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isInstanceOf(List.class);
    assertThat((List<String>) buildSettingInfo.getValue("value"))
        .containsExactly("some-other-value", "some-other-other-value");
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testBuildSettingValue_isRepeatedSetting() throws Exception {
    scratch.file(
        "test/build_setting.bzl",
        """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        string_list_flag = rule(
          implementation = _impl,
          build_setting = config.string_list(flag = True, repeatable = True),
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:build_setting.bzl', 'string_list_flag')
        string_list_flag(name = 'string_list_flag', build_setting_default = ['some-value'])
        """);

    // from default
    ConfiguredTarget buildSetting = getConfiguredTarget("//test:string_list_flag");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    StructImpl buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isInstanceOf(List.class);
    assertThat((List<String>) buildSettingInfo.getValue("value")).containsExactly("some-value");

    // Set multiple times
    useConfiguration(
        "--//test:string_list_flag=some-other-value",
        "--//test:string_list_flag=some-other-other-value");
    buildSetting = getConfiguredTarget("//test:string_list_flag");
    key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isInstanceOf(List.class);
    assertThat((List<String>) buildSettingInfo.getValue("value"))
        .containsExactly("some-other-value", "some-other-other-value");

    // No splitting on comma.
    useConfiguration(
        "--//test:string_list_flag=a,b,c",
        "--//test:string_list_flag=a",
        "--//test:string_list_flag=b,c");
    buildSetting = getConfiguredTarget("//test:string_list_flag");
    key =
        new StarlarkProvider.Key(
            keyForBuild(
                Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")),
            "BuildSettingInfo");
    buildSettingInfo = (StructImpl) buildSetting.get(key);

    assertThat(buildSettingInfo.getValue("value")).isInstanceOf(List.class);
    assertThat((List<String>) buildSettingInfo.getValue("value"))
        .containsExactly("a,b,c", "a", "b,c");
  }

  @Test
  public void testBuildSettingValue_nonBuildSettingRule() throws Exception {
    scratch.file(
        "test/rule.bzl",
        """
        def _impl(ctx):
          foo = ctx.build_setting_value
          return []
        non_build_setting = rule(implementation = _impl)
        """);
    scratch.file(
        "test/BUILD",
        """
        load('//test:rule.bzl', 'non_build_setting')
        non_build_setting(name = 'my_non_build_setting')
        """);

    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_non_build_setting");
    assertContainsEvent(
        "attempting to access 'build_setting_value' of non-build setting "
            + "//test:my_non_build_setting");
  }

  private void createToolchains() throws Exception {
    scratch.file(
        "rule/test_toolchain.bzl",
        """
        def _impl(ctx):
            value = ctx.attr.value
            toolchain = platform_common.ToolchainInfo(value = value)
            return [toolchain]
        test_toolchain = rule(
            implementation = _impl,
            attrs = {'value': attr.string()},
        )
        """);
    scratch.file(
        "rule/test_rule.bzl",
        """
        result = provider()
        def _impl(ctx):
            toolchain = ctx.toolchains['//rule:toolchain_type']
            return [result(
                value_from_toolchain = toolchain.value,
            )]
        test_rule = rule(
            implementation = _impl,
            toolchains = ['//rule:toolchain_type'],
        )
        """);
    scratch.file(
        "rule/BUILD",
        """
        exports_files(['test_toolchain/bzl', 'test_rule.bzl'])
        toolchain_type(name = 'toolchain_type')
        """);
    scratch.file(
        "toolchain/BUILD",
        """
        load('//rule:test_toolchain.bzl', 'test_toolchain')
        test_toolchain(
            name = 'foo',
            value = 'foo',
        )
        toolchain(
            name = 'foo_toolchain',
            toolchain_type = '//rule:toolchain_type',
            target_compatible_with = ['//platform:constraint_1'],
            toolchain = ':foo',
        )
        test_toolchain(
            name = 'bar',
            value = 'bar',
        )
        toolchain(
            name = 'bar_toolchain',
            toolchain_type = '//rule:toolchain_type',
            target_compatible_with = ['//platform:constraint_2'],
            toolchain = ':bar',
        )
        """);
  }

  private void createPlatforms() throws Exception {
    scratch.overwriteFile(
        "platform/BUILD",
        """
        constraint_setting(name = 'setting')
        constraint_value(
            name = 'constraint_1',
            constraint_setting = ':setting',
        )
        constraint_value(
            name = 'constraint_2',
            constraint_setting = ':setting',
        )
        platform(
            name = 'platform_1',
            constraint_values = [':constraint_1'],
        )
        platform(
            name = 'platform_2',
            constraint_values = [':constraint_2'],
        )
        """);
  }

  private String getToolchainResult(String targetName) throws Exception {
    ConfiguredTarget myRuleTarget = getConfiguredTarget(targetName);
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//rule:test_rule.bzl")), "result"));

    assertThat(info).isNotNull();
    return (String) info.getValue("value_from_toolchain");
  }

  @Test
  public void testToolchains() throws Exception {
    createToolchains();
    createPlatforms();
    scratch.file(
        "demo/BUILD",
        """
        load('//rule:test_rule.bzl', 'test_rule')
        test_rule(
            name = 'demo',
        )
        """);

    useConfiguration(
        "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
        "--platforms=//platform:platform_1");
    String value = getToolchainResult("//demo");
    assertThat(value).isEqualTo("foo");

    // Re-test with the other platform.
    useConfiguration(
        "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
        "--platforms=//platform:platform_2");
    value = getToolchainResult("//demo");
    assertThat(value).isEqualTo("bar");
  }

  @Test
  public void testTargetPlatformHasConstraint() throws Exception {
    createPlatforms();

    scratch.file(
        "demo/test_rule.bzl",
        """
        result = provider()
        def _impl(ctx):
            constraint = ctx.attr._constraint[platform_common.ConstraintValueInfo]
            has_constraint = ctx.target_platform_has_constraint(constraint)
            return [result(
                has_constraint = has_constraint,
            )]
        test_rule = rule(
            implementation = _impl,
            attrs = {
                '_constraint': attr.label(default = '//platform:constraint_1'),
            },
        )
        """);
    scratch.file(
        "demo/BUILD",
        """
        load(':test_rule.bzl', 'test_rule')
        test_rule(
            name = 'demo',
        )
        """);

    useConfiguration("--platforms=//platform:platform_1");

    ConfiguredTarget myRuleTarget = getConfiguredTarget("//demo");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//demo:test_rule.bzl")), "result"));

    assertThat(info).isNotNull();
    boolean hasConstraint = (boolean) info.getValue("has_constraint");
    assertThat(hasConstraint).isTrue();

    // Re-test with the other platform.
    useConfiguration("--platforms=//platform:platform_2");
    myRuleTarget = getConfiguredTarget("//demo");
    info =
        (StructImpl)
            myRuleTarget.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonical("//demo:test_rule.bzl")), "result"));

    assertThat(info).isNotNull();
    hasConstraint = (boolean) info.getValue("has_constraint");
    assertThat(hasConstraint).isFalse();
  }

  private void writeExecGroups() throws Exception {
    createToolchains();
    createPlatforms();
    scratch.file(
        "something/defs.bzl",
        """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['dragonfruit'].toolchains['//rule:toolchain_type']
          return [result(
            toolchain_value = toolchain.value,
            exec_groups = exec_groups,
          )]
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
          },
        )
        """);
    scratch.file(
        "something/BUILD",
        """
        load('//something:defs.bzl', 'use_exec_groups')
        use_exec_groups(name = 'nectarine')
        """);
    useConfiguration(
        "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
        "--platforms=//platform:platform_1");
  }

  @Test
  public void testExecGroup_toolchain() throws Exception {
    writeExecGroups();

    ConfiguredTarget target = getConfiguredTarget("//something:nectarine");
    StructImpl info =
        (StructImpl)
            target.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonicalUnchecked("//something:defs.bzl")), "result"));
    assertThat(info).isNotNull();
    assertThat(info.getValue("toolchain_value")).isEqualTo("foo");
    assertThat(info.getValue("exec_groups")).isInstanceOf(StarlarkExecGroupCollection.class);
    var toolchainContexts =
        ((StarlarkExecGroupCollection) info.getValue("exec_groups"))
            .getToolchainCollectionForTesting();
    assertThat(toolchainContexts.keySet()).containsExactly(DEFAULT_EXEC_GROUP_NAME, "dragonfruit");
    assertThat(toolchainContexts.get(DEFAULT_EXEC_GROUP_NAME).toolchainTypes()).isEmpty();
    assertThat(toolchainContexts.get("dragonfruit").resolvedToolchainLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"));
  }

  // Tests for an error that occurs when two exec groups have different requirements (toolchain
  // types and exec constraints), but have the same toolchain type. This also requires the toolchain
  // transition to be enabled.
  @Test
  public void testExecGroup_duplicateToolchainType() throws Exception {
    createToolchains();
    createPlatforms();
    scratch.file(
        "something/defs.bzl",
        """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['dragonfruit'].toolchains['//rule:toolchain_type']
          return [result(
            toolchain_value = toolchain.value,
            exec_groups = exec_groups,
          )]
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
            'passionfruit': exec_group(
              toolchains = ['//rule:toolchain_type'],
              exec_compatible_with = ['//something:extra'],
            ),
          },
        )
        """);
    scratch.file(
        "something/BUILD",
        """
        constraint_setting(name = 'setting', default_constraint_value = ':extra')
        constraint_value(name = 'extra', constraint_setting = ':setting')
        load('//something:defs.bzl', 'use_exec_groups')
        use_exec_groups(name = 'nectarine')
        """);
    useConfiguration(
        "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
        "--platforms=//platform:platform_1");

    ConfiguredTarget target = getConfiguredTarget("//something:nectarine");
    StructImpl info =
        (StructImpl)
            target.get(
                new StarlarkProvider.Key(
                    keyForBuild(Label.parseCanonicalUnchecked("//something:defs.bzl")), "result"));
    assertThat(info).isNotNull();
    assertThat(info.getValue("toolchain_value")).isEqualTo("foo");
    assertThat(info.getValue("exec_groups")).isInstanceOf(StarlarkExecGroupCollection.class);
    var toolchainContexts =
        ((StarlarkExecGroupCollection) info.getValue("exec_groups"))
            .getToolchainCollectionForTesting();
    assertThat(toolchainContexts.keySet())
        .containsExactly(DEFAULT_EXEC_GROUP_NAME, "dragonfruit", "passionfruit");
    assertThat(toolchainContexts.get(DEFAULT_EXEC_GROUP_NAME).toolchainTypes()).isEmpty();
    assertThat(toolchainContexts.get("dragonfruit").resolvedToolchainLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"));
    assertThat(toolchainContexts.get("passionfruit").resolvedToolchainLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"));
  }

  @Test
  public void testInvalidExecGroup() throws Exception {
    writeExecGroups();

    scratch.overwriteFile(
        "something/defs.bzl",
        """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['unknown_fruit']
          return []
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
          },
        )
        """);

    assertThrows(AssertionError.class, () -> getConfiguredTarget("//something:nectarine"));
    assertContainsEvent(
        "unrecognized exec group 'unknown_fruit' requested. Available exec groups: [dragonfruit]");
  }

  @Test
  public void testCannotAccessDefaultGroupViaExecGroups() throws Exception {
    writeExecGroups();

    scratch.overwriteFile(
        "something/defs.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  exec_groups = ctx.exec_groups",
        "  toolchain = ctx.exec_groups['" + DEFAULT_EXEC_GROUP_NAME + "']",
        "  return []",
        "use_exec_groups = rule(",
        "  implementation = _impl,",
        "  exec_groups = {",
        "    'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),",
        "  },",
        ")");

    assertThrows(AssertionError.class, () -> getConfiguredTarget("//something:nectarine"));
    assertContainsEvent(
        "unrecognized exec group '"
            + DEFAULT_EXEC_GROUP_NAME
            + "' requested. Available exec groups: [dragonfruit]");
  }

  @Test
  public void testInvalidExecGroupName() throws Exception {
    writeExecGroups();
    String badName = "1bad-stuff-name";

    scratch.overwriteFile(
        "something/defs.bzl",
        "result = provider()",
        "def _impl(ctx):",
        "  exec_groups = ctx.exec_groups",
        "  toolchain = ctx.exec_groups['" + badName + "']",
        "  return []",
        "use_exec_groups = rule(",
        "  implementation = _impl,",
        "  exec_groups = {",
        "    '" + badName + "': exec_group(toolchains = ['//rule:toolchain_type']),",
        "  },",
        ")");

    assertThrows(AssertionError.class, () -> getConfiguredTarget("//something:nectarine"));
    assertContainsEvent("Exec group name '" + badName + "' is not a valid name.");
  }

  @Test
  public void testBuildFilePath() throws Exception {
    scratch.file("/foo/MODULE.bazel", "module(name='foo')");
    scratch.file("/foo/bar/BUILD", "genrule(name = 'baz', cmd = 'dummy_cmd', outs = ['a.txt'])");

    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='foo')",
        "local_path_override(module_name='foo', path='/foo')");

    invalidatePackages(false);

    setRuleContext(createRuleContext("@@foo+//bar:baz"));
    Object result = ev.eval("ruleContext.build_file_path");
    assertThat(result).isEqualTo("bar/BUILD");

    // The reason `build_file_path` should be deprecated. It's just another trivial knob on `ctx`.
    // The results are always the same as `ctx.label.package + '/BUILD'`
    result = ev.eval("ruleContext.label.package + '/BUILD'");
    assertThat(result).isEqualTo("bar/BUILD");
  }

  @Test
  public void testStopExportingBuildFilePath() throws Exception {
    scratch.file("/foo/MODULE.bazel", "module(name='foo')");
    scratch.file("/foo/bar/BUILD", "genrule(name = 'baz', cmd = 'dummy_cmd', outs = ['a.txt'])");

    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='foo')",
        "local_path_override(module_name='foo', path='/foo')");

    invalidatePackages(false);
    setBuildLanguageOptions("--incompatible_stop_exporting_build_file_path");

    setRuleContext(createRuleContext("@@foo+//bar:baz"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("ruleContext.build_file_path"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo(
            "Use ctx.label.package + '/BUILD' instead of ctx.build_file_path.\nUse"
                + " --incompatible_stop_exporting_build_file_path=false to temporarily disable this"
                + " check.");
  }

  @Test
  public void testDisallowCtxResolveTools() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name = 'foo', cmd = 'dummy_cmd', outs = ['a.txt'])");

    setBuildLanguageOptions("--incompatible_disallow_ctx_resolve_tools");

    setRuleContext(createRuleContext("//pkg:foo"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("ruleContext.resolve_tools()"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo(
            "Pass an executable or tools argument to ctx.actions.run or ctx.actions.run_shell"
                + " instead of calling ctx.resolve_tools.\n"
                + "Use --noincompatible_disallow_ctx_resolve_tools to temporarily disable this"
                + " check.");
  }

  @Test
  public void testNoToolchainContext() throws Exception {
    // Build setting rules do not have a toolchain context, as they are part of the configuration.
    scratch.file(
        "test/BUILD",
        """
        load(':rule.bzl', 'sample_setting')
        toolchain_type(name = 'toolchain_type')
        sample_setting(
            name = 'test',
            build_setting_default = True,
        )
        """);
    scratch.file(
        "test/rule.bzl",
        """
        def _sample_impl(ctx):
            # This should raise an error.
            ctx.toolchains['//:toolchain_type']
            fail('Toolchain was not empty')
        sample_setting = rule(
            implementation = _sample_impl,
            build_setting = config.bool(flag = True),
        )
        """);
    assertThrows(AssertionError.class, () -> getConfiguredTarget("//test:test"));
    assertContainsEvent("Toolchains are not valid in this context");
    assertDoesNotContainEvent("Toolchain was not empty");
  }

  @Test
  public void testTemplateExpansionComputedSubstitution() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _artifact_to_basename(file):",
        "  return file.basename if file.basename != 'ignored.txt' else None",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add('a', 'X')",
        "  template_dict.add_joined('td_files_key', depset(ctx.files.srcs),",
        "                           map_each = _artifact_to_basename,",
        "                           join_with = '%%',",
        "                           format_joined = 'header/%s/footer',",
        "                          )",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              substitutions={'b': 'Y'},",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),",
        "           'srcs':attr.label_list(allow_files=True)",
        "           },",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "aaaaa", "bbb-pqr", "td_files_key");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.txt', 'baz.txt', 'ignored.txt'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    assertThat(contentUnchecked)
        .isEqualTo("XXXXX\nYYY-pqr\nheader/foo.txt%%bar.txt%%baz.txt/footer\n");

    Object substitutionsUnchecked = ev.eval("action.substitutions");
    assertThat(substitutionsUnchecked).isInstanceOf(Dict.class);
    assertThat(substitutionsUnchecked)
        .isEqualTo(
            ImmutableMap.of(
                "a", "X",
                "b", "Y",
                "td_files_key", "header/foo.txt%%bar.txt%%baz.txt/footer"));
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionWithUniquify() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _artifact_to_extension(file):",
        "  return file.extension",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('exts', depset(ctx.files.srcs),",
        "                           map_each = _artifact_to_extension,",
        "                           uniquify = True,",
        "                           join_with = '%%',",
        "                          )",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),",
        "           'srcs':attr.label_list(allow_files=True)",
        "           },",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "exts", "exts");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.log', 'baz.txt', 'bak.exe', 'far.sh', 'boo.sh'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    assertThat(contentUnchecked).isEqualTo("txt%%log%%exe%%sh\ntxt%%log%%exe%%sh\n");

    Object substitutionsUnchecked = ev.eval("action.substitutions");
    assertThat(substitutionsUnchecked).isInstanceOf(Dict.class);
    assertThat(substitutionsUnchecked).isEqualTo(ImmutableMap.of("exts", "txt%%log%%exe%%sh"));
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionWithUniquifyAndListMapEach()
      throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _artifact_to_extension(file):",
        "  if file.extension == 'sh':",
        "    return [file.extension]",
        "  return [file.extension, '.' + file.extension]",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('exts', depset(ctx.files.srcs),",
        "                           map_each = _artifact_to_extension,",
        "                           uniquify = True,",
        "                           join_with = '%%',",
        "                          )",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),",
        "           'srcs':attr.label_list(allow_files=True)",
        "           },",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "exts", "exts");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.log', 'baz.txt', 'bak.exe', 'far.sh', 'boo.sh'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    assertThat(ev.eval("type(action)")).isEqualTo("Action");

    Object contentUnchecked = ev.eval("action.content");
    assertThat(contentUnchecked).isInstanceOf(String.class);
    assertThat(contentUnchecked)
        .isEqualTo("txt%%.txt%%log%%.log%%exe%%.exe%%sh\ntxt%%.txt%%log%%.log%%exe%%.exe%%sh\n");

    Object substitutionsUnchecked = ev.eval("action.substitutions");
    assertThat(substitutionsUnchecked).isInstanceOf(Dict.class);
    assertThat(substitutionsUnchecked)
        .isEqualTo(ImmutableMap.of("exts", "txt%%.txt%%log%%.log%%exe%%.exe%%sh"));
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionDuplicateKeys() throws Exception {
    scratch.file(
        "test/rules.bzl",
        """
        def _undertest_impl(ctx):
          template_dict = ctx.actions.template_dict()
          template_dict.add('a', '1')
          template_dict.add('a', '2')
          ctx.actions.expand_template(output=ctx.outputs.out,
                                      template=ctx.file.template,
                                      computed_substitutions=template_dict,
                                      )
        undertest_rule = rule(
          implementation = _undertest_impl,
          outputs = {'out': '%{name}.txt'},
          attrs = {'template': attr.label(allow_single_file=True),},
        )
        """);
    scratch.file("test/template.txt");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        """);

    checkError("//test:undertest", "Error in expand_template: Multiple entries with same key: a");
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionNoParamMapEach() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def no_args_func():",
        "  return 'magic-string'",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('%the_key%', depset(ctx.files.template),",
        "                           map_each = no_args_func,",
        "                           join_with = '')",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "%the_key%");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("action.content"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo("no_args_func() does not accept positional arguments, but got 1");
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionTwoParamMapEach() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def two_args_func(arg1, arg2):",
        "  return 'magic-string'",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('%the_key%', depset(ctx.files.template),",
        "                           map_each = two_args_func,",
        "                           join_with = '')",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "%the_key%");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("action.content"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo("two_args_func() missing 1 required positional argument: arg2");
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionMapEachBadReturnType() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def file_to_owner_label(file):",
        "  return file.owner",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('%files%', depset(ctx.files.template),",
        "                           map_each = file_to_owner_label,",
        "                           join_with = '')",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("action.content"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo(
            "Function provided to map_each must return string, None, or list of strings, "
                + "but returned type Label for key '%files%' and value: "
                + "File:[/workspace[source]]test/template.txt");
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionMapEachBadListReturnType() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def file_to_owner_label(file):",
        "  return [file.owner]",
        "",
        "def _undertest_impl(ctx):",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('%files%', depset(ctx.files.template),",
        "                           map_each = file_to_owner_label,",
        "                           join_with = '')",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);
    StarlarkRuleContext ruleContext = createRuleContext("//test:testing");
    setRuleContext(ruleContext);
    ev.update("file", ev.eval("ruleContext.attr.dep.files.to_list()[0]"));
    ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"));

    EvalException evalException =
        assertThrows(EvalException.class, () -> ev.eval("action.content"));
    assertThat(evalException)
        .hasMessageThat()
        .isEqualTo(
            "Function provided to map_each must return string, None, or list of strings, "
                + "but returned list containing element '//test:template.txt' of type Label for "
                + "key '%files%' and value: File:[/workspace[source]]test/template.txt");
  }

  @Test
  public void testTemplateExpansionComputedSubstitutionMapEachMustBeTopLevel() throws Exception {
    scratch.file(
        "test/rules.bzl",
        "def _undertest_impl(ctx):",
        "",
        "  def file_to_shortpath(file):",
        "    return file.short_path",
        "",
        "  template_dict = ctx.actions.template_dict()",
        "  template_dict.add_joined('%files%', depset(ctx.files.template),",
        "                           map_each = file_to_shortpath,",
        "                           join_with = '')",
        "  ctx.actions.expand_template(output=ctx.outputs.out,",
        "                              template=ctx.file.template,",
        "                              computed_substitutions=template_dict,",
        "                              )",
        "undertest_rule = rule(",
        "  implementation = _undertest_impl,",
        "  outputs = {'out': '%{name}.txt'},",
        "  attrs = {'template': attr.label(allow_single_file=True),},",
        "  _skylark_testable = True,",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        """);

    checkError("//test:testing", "must be declared by a top-level def statement");
  }

  @Test
  public void transformFile_correctActionGenerated(
      @TestParameter({"ctx.actions.transform_info_file", "ctx.actions.transform_version_file"})
          String apiMethod)
      throws Exception {
    boolean volatileAndExcuteUnconditionally =
        apiMethod.equals("ctx.actions.transform_version_file");
    scratch.file(
        "test/rules.bzl",
        "def t(d):",
        " r = {}",
        " r['{NAME}'] = d['name'] + '_foo'",
        " r['{CLIENT}'] = d['client'] + '_c'",
        " return r",
        "def _buildinfo_impl(ctx):",
        String.format(
            "  output = %s(transform_func = t, template = ctx.file.template, output_file_name ="
                + " 'buildinfo.h')",
            apiMethod),
        "  return DefaultInfo(files = depset([output]))",
        "buildinfo_rule = rule(",
        "  implementation = _buildinfo_impl,",
        "  attrs = {'template': attr.label(allow_single_file=True)},",
        ")",
        testingRuleDefinition);
    scratch.file("test/template.txt", "#define NAME {NAME}", "#define CLIENT {CLIENT}");
    scratch.file(
        "test/BUILD",
        """
        load(':rules.bzl', 'buildinfo_rule')
        buildinfo_rule(
            name = 'generating_target',
            template = ':template.txt',
        )
        """);

    ConfiguredTarget buildInfo = getConfiguredTarget("//test:generating_target");
    Artifact buildInfoArtifact = getFilesToBuild(buildInfo).getSingleton();
    BuildInfoFileWriteAction buildInfoAction =
        (BuildInfoFileWriteAction) getGeneratingAction(buildInfoArtifact);

    assertThat(buildInfoAction).isNotNull();
    assertThat(buildInfoArtifact).isNotNull();
    assertThat(buildInfoArtifact.getFilename()).isEqualTo("buildinfo.h");
    assertThat(buildInfoArtifact.isConstantMetadata()).isEqualTo(volatileAndExcuteUnconditionally);
    assertThat(buildInfoAction.getMnemonic()).isEqualTo("TranslateBuildInfo");
    assertThat(buildInfoAction.executeUnconditionally())
        .isEqualTo(volatileAndExcuteUnconditionally);
    assertThat(buildInfoAction.isVolatile()).isEqualTo(volatileAndExcuteUnconditionally);
    assertThat(artifactsToStrings(buildInfoAction.getInputs())).contains("src test/template.txt");
  }

  @Test
  public void transformFile_cannotBeAccessedOutsideOfAllowlist(
      @TestParameter({"ctx.actions.transform_version_file", "ctx.actions.transform_info_file"})
          String apiMethod)
      throws Exception {
    scratch.file(
        "some_dir/rules.bzl",
        "def t(d):",
        " pass",
        "def _buildinfo_impl(ctx):",
        String.format(
            "  output = %s(transform_func = t, template = ctx.file.template, output_file_name ="
                + " 'buildinfo.h')",
            apiMethod),
        "  return DefaultInfo(files = depset([output]))",
        "buildinfo_rule = rule(",
        "  implementation = _buildinfo_impl,",
        "  attrs = {'template': attr.label(allow_single_file=True)},",
        ")",
        testingRuleDefinition);
    scratch.file("some_dir/template.txt", "");

    checkError(
        "some_dir",
        "generating_target",
        "file '//some_dir:rules.bzl' cannot use private API",
        "load(':rules.bzl', 'buildinfo_rule')",
        "buildinfo_rule(",
        "    name = 'generating_target',",
        "    template = ':template.txt',",
        ")");
  }
}
