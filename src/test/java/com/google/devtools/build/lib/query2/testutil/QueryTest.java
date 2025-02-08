// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import java.io.IOException;
import java.util.Set;
import org.junit.Test;

/** Tests for the blaze query implementation, --nokeep_going. */
public abstract class QueryTest extends AbstractQueryTest<Target> {

  @Override
  protected QueryHelper<Target> createQueryHelper() {
    return new SkyframeQueryHelper() {
      @Override
      protected String getRootDirectoryNameForSetup() {
        return "/workspace";
      }

      @Override
      protected void performAdditionalClientSetup(MockToolsConfig mockToolsConfig)
          throws IOException {}

      @Override
      protected Iterable<QueryFunction> getExtraQueryFunctions() {
        return ImmutableList.of();
      }
    };
  }

  @Override
  protected boolean includeCppToolchainDependencies() {
    return false;
  }

  @Test
  public void testFindsAllTargets_nativeRuleMacro() throws Exception {
    writeFile(
        "test/starlark/extension.bzl",
        """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >$@")
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")

        macro(name = "rule2")
        """);

    assertThat(targetLabels(eval("//test/starlark:*")))
        .containsExactly(
            "//test/starlark:rule1",
            "//test/starlark:rule2",
            "//test/starlark:BUILD",
            "//test/starlark:rule1.txt",
            "//test/starlark:rule2.txt");
  }

  @Test
  public void testFindsAllTargets_starlarkRuleMacro() throws Exception {
    writeFile(
        "test//starlark/extension.bzl",
        """
        def impl(ctx):
            return None

        starlark_rule = rule(implementation = impl)

        def macro(name):
            starlark_rule(name = name)
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")

        macro(name = "rule2")
        """);

    assertThat(targetLabels(eval("//test/starlark:*")))
        .containsExactly("//test/starlark:rule1", "//test/starlark:rule2", "//test/starlark:BUILD");
  }

  @Test
  public void testBuildfiles_starlarkDep() throws Exception {
    writeFile(
        "test//starlark/extension.bzl",
        """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >$@")
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")
        """);

    assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
        .containsExactly("//test/starlark:extension.bzl", "//test/starlark:BUILD");
  }

  @Test
  public void testLoadfiles_starlarkDep() throws Exception {
    writeFile(
        "test//starlark/extension.bzl",
        """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >$@")
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")
        """);

    assertThat(targetLabels(eval("loadfiles(//test/starlark:BUILD)")))
        .containsExactly("//test/starlark:extension.bzl");
  }

  @Test
  public void testLoadfiles_sclDep() throws Exception {
    writeBzlAndSclFiles();

    assertThat(targetLabels(eval("loadfiles(//foo:BUILD)")))
        .containsExactly(
            "//bar:direct.scl",
            "//bar:indirect.scl",
            "//bar:intermediate.bzl",
            "//test_defs:foo_library.bzl");
  }

  @Test
  public void testDeps_labelKeyedStringDictDeps() throws Exception {
    writeFile(
        "test//starlark/rule.bzl",
        """
        def _impl(ctx):
            return

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "value_dict": attr.label_keyed_string_dict(allow_files = True),
            },
        )
        """);
    writeFile("test//starlark/dep.cc");
    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:rule.bzl", "my_rule")

        filegroup(
            name = "group",
            srcs = ["dep.cc"],
        )

        my_rule(
            name = "rule",
            value_dict = {":group": "queried"},
        )
        """);

    assertThat(targetLabels(eval("deps(//test/starlark:rule)")))
        .containsExactly("//test/starlark:rule", "//test/starlark:group", "//test/starlark:dep.cc");
  }

  @Test
  public void testBuildfiles_transitiveStarlarkDeps() throws Exception {
    writeFile(
        "test//starlark/extension1.bzl",
        """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >$@")
        """);

    writeFile(
        "test//starlark/extension2.bzl",
        """
        load("//test/starlark:extension1.bzl", "macro")

        def func(name):
            macro(name)
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension2.bzl", "func")

        func(name = "rule1")
        """);

    assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
        .containsExactly(
            "//test/starlark:extension1.bzl",
            "//test/starlark:extension2.bzl",
            "//test/starlark:BUILD");
  }

  @Test
  public void testBuildfiles_diamondStarlarkDeps() throws Exception {
    writeFile(
        "test//starlark/extension1.bzl",
        """
        my_constant = "rule1"

        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >$@")
        """);

    writeFile(
        "test//starlark/extension2.bzl",
        """
        load("//test/starlark:extension1.bzl", "macro")

        def func(name):
            macro(name)
        """);

    writeFile(
        "test//starlark/extension3.bzl",
        """
        load("//test/starlark:extension1.bzl", "my_constant")

        my_rule_name = my_constant
        """);

    writeFile(
        "test//starlark/extension4.bzl",
        """
        load("//test/starlark:extension2.bzl", "func")
        load("//test/starlark:extension3.bzl", "my_rule_name")

        my_dummy_name = my_rule_name
        """);

    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension2.bzl", "func")
        load("//test/starlark:extension3.bzl", "my_rule_name")
        load("//test/starlark:extension4.bzl", "my_dummy_name")

        func(name = my_rule_name + "-" + my_dummy_name)
        """);

    assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
        .containsExactly(
            "//test/starlark:extension1.bzl",
            "//test/starlark:extension2.bzl",
            "//test/starlark:extension3.bzl",
            "//test/starlark:extension4.bzl",
            "//test/starlark:BUILD");
  }

  @Test
  public void testBuildfiles_starlarkDepPackageBuildfileIncluded() throws Exception {
    writeFile("test//starlark2/BUILD");
    writeFile("test//starlark2/extension.bzl", "file_ext = '.txt'");

    writeFile("test//starlark1/BUILD");
    writeFile(
        "test//starlark1/extension.bzl",
        """
        load("//test/starlark2:extension.bzl", "file_ext")

        def macro(name):
            native.genrule(name = name, outs = [name + file_ext], cmd = "echo hi >$@")
        """);

    writeFile(
        "test/pkg/BUILD",
        """
        load("//test/starlark1:extension.bzl", "macro")

        macro(name = "rule1")
        """);

    assertThat(targetLabels(eval("buildfiles(//test/pkg:BUILD)")))
        .containsExactly(
            "//test/pkg:BUILD",
            "//test/starlark1:extension.bzl",
            "//test/starlark1:BUILD",
            "//test/starlark2:extension.bzl",
            "//test/starlark2:BUILD");
  }

  @Test
  public void testQueryTimeLoadingWhenPackageDoesNotExist() throws Exception {
    // Given a workspace containing a package "//a",
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");

    // When the query environment is queried for "//a/b:b" which doesn't exist,
    String nonExistentPackage = "a/b";
    String s = evalThrows("//" + nonExistentPackage + ":itsNotThere", false).getMessage();

    // Then an exception is thrown that says that the specified package does not exist.
    assertThat(s).containsMatch("no such package '" + nonExistentPackage + "'");
  }

  @Test
  public void testQueryTimeLoadingWhenPackageIsMalformed() throws Exception {
    // Given a workspace containing a malformed package "//a",
    writeFile(
        "a/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'a') BUT WAIT THERE'S MORE");

    // When the query environment is queried for "//a:a" which belongs to a malformed package,
    String s = evalThrows("//a:a", false).getMessage();

    // Then an exception is thrown,
    assertThat(s).isNotNull();

    // And then the query output contains a description of the malformed package error.
    assertContainsEvent("unclosed string literal");
  }

  @Test
  public void testQueryTimeLoadingOfSymlinkCyclePackage() throws Exception {
    // Given a workspace containing a symlink cycle that looks like a BUILD file at "//a/BUILD",
    ensureSymbolicLink("a/BUILD", "a/BUILD");

    // When the query environment is queried for "//a:*",
    String s = evalThrows("//a:*", false).getMessage();

    // Then an exception is thrown,
    assertThat(s).isNotNull();

    // And then the query output contains a description of the circular symlink problem.
    assertContainsEvent("circular symlinks detected");
  }

  @Test
  public void boundedDepsQueryWithError() throws Exception {
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name ='foo', deps = ['//bar'])");
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name ='bar')");
    writeFile(
        "errorparent",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'errorparent', deps = ['//error'])");
    writeFile("error", "has errors");

    evalThrows("deps(//foo:all + //errorparent:all, 25)", false);
  }

  @Test
  public void testIgnoredSubdirectories() throws Exception {
    useReducedSetOfRules();
    writeFile(helper.getIgnoredSubdirectoriesFile().getPathString(), "a/b", "a/c");
    writeFile("a/BUILD", "filegroup(name = 'a')");
    writeFile("b/BUILD", "filegroup(name = 'b')");
    writeFile("a/b/BUILD", "filegroup(name = 'a_b')");
    writeFile("a/c/BUILD", "filegroup(name = 'a_c')");
    writeFile("a/d/BUILD", "filegroup(name = 'a_d')");
    writeFile("a/e/BUILD", "filegroup(name = 'a_e')");
    // Ensure that modified files are invalidated in the skyframe. If a file has
    // already been read prior to the test's writes, this forces the query to
    // pick up the modified versions.
    helper.maybeHandleDiffs();
    Iterable<String> result = targetLabels(eval("//..."));
    assertThat(result).containsAtLeast("//a:a", "//b:b", "//a/d:a_d", "//a/e:a_e");
    assertThat(result).containsNoneOf("//a/b:a_b", "//a/c:a_c");
    result = targetLabels(eval("//a/..."));
    assertThat(result).containsExactly("//a:a", "//a/d:a_d", "//a/e:a_e");
  }

  private void writeStarlarkDefinedRuleClassBzlFile() throws java.io.IOException {
    writeFile(
        "test//starlark/extension.bzl",
        """
        def custom_rule_impl(ctx):
            ftb = depset(ctx.attr._secret_labels)
            return DefaultInfo(runfiles = ctx.runfiles(), files = ftb)

        def secret_labels_func(prefix, suffix):
            return [
                Label("//test/starlark:" + prefix + "01" + suffix),
                Label("//test/starlark:" + prefix + "02" + suffix),
            ]

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {
                "prefix": attr.string(default = "default_prefix"),
                "suffix": attr.string(default = "default_suffix"),
                "_secret_labels": attr.label_list(default = secret_labels_func),
            },
        )
        """);
  }

  @Test
  public void testQueryStarlarkComputedDefault() throws Exception {
    writeStarlarkDefinedRuleClassBzlFile();
    writeFile(
        "test//starlark/BUILD",
        """
        load("//test/starlark:extension.bzl", "custom_rule")

        custom_rule(
            name = "custom",
            prefix = "a",
            suffix = "b",
        )
        """);

    Set<Target> targets = eval("//test/starlark:*");
    assertThat(targetLabels(targets))
        .containsExactly(
            "//test/starlark:BUILD",
            "//test/starlark:custom",
            "//test/starlark:a01b",
            "//test/starlark:a02b");
  }

  @Test
  public void testQueryStarlarkComputedDefaultWithConfigurableDeps() throws Exception {
    writeStarlarkDefinedRuleClassBzlFile();
    writeFile(
        "test//starlark/BUILD",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "config_setting(",
        "    name = 'cfg_a',",
        "    values = {'test_arg': 'something'})",
        "config_setting(",
        "    name = 'cfg_b',",
        "    values = {'test_arg': 'something_else'})",
        "",
        "custom_rule(",
        "    name = 'custom',",
        "    prefix = select({",
        "        ':cfg_a':'a',",
        "        ':cfg_b':'b',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def'}),",
        "    suffix = select({",
        "        ':cfg_a':'a',",
        "        ':cfg_b':'b',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def'}))");

    ImmutableList.Builder<String> computedLabelsBuilder = ImmutableList.builder();
    for (String prefix : ImmutableList.of("a", "b", "def")) {
      for (String middle : ImmutableList.of("01", "02")) {
        for (String suffix : ImmutableList.of("a", "b", "def")) {
          computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix);
        }
      }
    }
    ImmutableList<String> computedLabels = computedLabelsBuilder.build();

    Set<Target> targets = eval("//test/starlark:*");
    assertThat(targetLabels(targets))
        .containsAtLeastElementsIn(
            Iterables.concat(
                ImmutableList.of(
                    "//test/starlark:BUILD",
                    "//test/starlark:cfg_a",
                    "//test/starlark:cfg_b",
                    "//test/starlark:custom"),
                computedLabels));
  }

  @Test
  public void testQueryStarlarkComputedDefaultWithConfigurableDepsUsedTwice() throws Exception {
    writeStarlarkDefinedRuleClassBzlFile();
    writeFile(
        "test//starlark/BUILD",
        "load('//test/starlark:extension.bzl', 'custom_rule')",
        "",
        "config_setting(",
        "    name = 'cfg_a',",
        "    values = {'test_arg': 'something'})",
        "config_setting(",
        "    name = 'cfg_b',",
        "    values = {'test_arg': 'something_else'})",
        "",
        "custom_rule(",
        "    name = 'custom_one',",
        "    prefix = select({",
        "        ':cfg_a':'a_one',",
        "        ':cfg_b':'b_one',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_one'}),",
        "    suffix = select({",
        "        ':cfg_a':'a_one',",
        "        ':cfg_b':'b_one',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_one'}))",
        "custom_rule(",
        "    name = 'custom_two',",
        "    prefix = select({",
        "        ':cfg_a':'a_two',",
        "        ':cfg_b':'b_two',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_two'}),",
        "    suffix = select({",
        "        ':cfg_a':'a_two',",
        "        ':cfg_b':'b_two',",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_two'}))");

    ImmutableList.Builder<String> computedLabelsBuilder = ImmutableList.builder();
    for (String prefix : ImmutableList.of("a_one", "b_one", "def_one")) {
      for (String middle : ImmutableList.of("01", "02")) {
        for (String suffix : ImmutableList.of("a_one", "b_one", "def_one")) {
          computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix);
        }
      }
    }
    for (String prefix : ImmutableList.of("a_two", "b_two", "def_two")) {
      for (String middle : ImmutableList.of("01", "02")) {
        for (String suffix : ImmutableList.of("a_two", "b_two", "def_two")) {
          computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix);
        }
      }
    }
    ImmutableList<String> computedLabels = computedLabelsBuilder.build();

    Set<Target> targets = eval("//test/starlark:*");
    assertThat(targetLabels(targets))
        .containsExactlyElementsIn(
            Iterables.concat(
                ImmutableList.of(
                    "//test/starlark:BUILD",
                    "//test/starlark:cfg_a",
                    "//test/starlark:cfg_b",
                    "//test/starlark:custom_one",
                    "//test/starlark:custom_two"),
                computedLabels));
  }

  @Test
  public void testFileTargetLiteralInSubdirectory() throws Exception {
    writeFile(
        "foo/BUILD",
        "exports_files(glob(['**/*.txt']))");
    writeFile("foo/bar/file1.txt");
    writeFile("foo/bar/file2.txt");
    Set<Target> targets = eval("foo/bar/file1.txt + foo/bar/file2.txt");
    assertThat(targetLabels(targets))
        .containsExactly(
            "//foo:bar/file1.txt",
            "//foo:bar/file2.txt");
  }

  @Test
  public void testShorthandTargetLiteralUnion() throws Exception {
    writeFile(
        "foo/bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar')");
    writeFile(
        "foo/baz/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'baz')");
    Set<Target> targets = eval("foo/bar + foo/baz");
    assertThat(targetLabels(targets))
        .containsExactly(
            "//foo/bar:bar",
            "//foo/baz:baz");
  }

  @Test
  public void testShorthandAbsoluteTargetLiteralUnion() throws Exception {
    writeFile(
        "foo/bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar')");
    writeFile(
        "foo/baz/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'baz')");
    Set<Target> targets = eval("//foo/bar + //foo/baz");
    assertThat(targetLabels(targets))
        .containsExactly(
            "//foo/bar:bar",
            "//foo/baz:baz");
  }

  @Test
  public void testLoadfilesWithDuplicates() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load("//bar:bar.bzl", "B")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            deps = ["//bar"],
        )
        """);
    writeFile(
        "bar/BUILD",
        """
        load("//bar:bar.bzl", "B")
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(name = "bar")
        """);
    writeFile("bar/bar.bzl", "B = []");
    assertThat(evalToString("loadfiles(deps(//foo))"))
        .isEqualTo("//bar:bar.bzl //test_defs:foo_library.bzl");
  }

  protected void runTestRdepsWithNonDefaultDependencyFilter(String query, String expected)
      throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        genrule(
            name = "gen",
            srcs = ["doesntmatter.txt"],
            outs = ["out.txt"],
            cmd = "blah",
            tools = [":a"],
        )

        foo_binary(
            name = "a",
        )

        foo_binary(
            name = "b",
            srcs = [":a"],
        )

        foo_binary(
            name = "c",
            srcs = [":out.txt"],
        )
        """);
    helper.setQuerySettings(Setting.ONLY_TARGET_DEPS);
    assertThat(evalToString(query)).isEqualTo(expected);
  }

  @Test
  public void testRdepsUnboundedWithNonDefaultDependencyFilter() throws Exception {
    runTestRdepsWithNonDefaultDependencyFilter("rdeps(//foo:all, //foo:a)", "//foo:a //foo:b");
  }

  @Test
  public void testRdepsBoundedWithNonDefaultDependencyFilter() throws Exception {
    runTestRdepsWithNonDefaultDependencyFilter("rdeps(//foo:all, //foo:a, 1)", "//foo:a //foo:b");
  }

  // Regression test for default visibility of output file targets being traversed even with
  // --noimplicit_deps is set.
  @Test
  public void testDefaultVisibilityOfOutputTarget_noImplicitDeps() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        package(default_visibility = [':pg'])
        genrule(name = 'gen', srcs = ['in'], outs = ['out'], cmd = 'doesntmatter')
        package_group(name = 'pg', includes = [':other-pg'])
        package_group(name = 'other-pg')
        """);
    assertEqualsFiltered(
        "deps(//foo:gen) + //foo:out + //foo:pg + //foo:other-pg"
            + getDependencyCorrectionWithGen(),
        "deps(//foo:out)" + getDependencyCorrectionWithGen(),
        Setting.NO_IMPLICIT_DEPS);
  }

  @Test
  public void testDormantDepsAreReturned() throws Exception {
    writeFile(
        "a/a.bzl",
        """
        def _impl(*args):
          fail("should not be called")

        r = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          attrs = { "dormant": attr.dormant_label(), "dormant_list": attr.dormant_label_list() })
        """);

    writeFile(
        "a/BUILD",
        """
        load(":a.bzl", "r")
        filegroup(name="a")
        filegroup(name="b1")
        filegroup(name="b2")

        r(name="r", dormant=":a", dormant_list=[":b1", ":b2"])
        """);

    assertThat(evalToListOfStrings("deps('//a:r')"))
        .containsAtLeast("//a:r", "//a:a", "//a:b1", "//a:b2");
  }

  protected Iterable<String> targetLabels(Set<Target> targets) {
    return Iterables.transform(targets, new Function<Target, String>() {
      @Override
      public String apply(Target input) {
        return input.getLabel().toString();
      }
    });
  }
}
