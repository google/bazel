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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.skyframe.TransitiveTargetKey;
import com.google.protobuf.ByteString;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration tests for the 'genquery' rule. */
@RunWith(TestParameterInjector.class)
public class GenQueryIntegrationTest extends BuildIntegrationTestCase {

  @TestParameter private boolean ttvFree;
  @TestParameter private boolean keepGoing;

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();
    runtimeWrapper.addOptions(
        ttvFree
            ? "--experimental_skip_ttvs_for_genquery"
            : "--noexperimental_skip_ttvs_for_genquery");
    runtimeWrapper.addOptions(keepGoing ? "--keep_going" : "--nokeep_going");
  }

  @Test
  public void testDoesNotFailHorribly() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        """);
    assertQueryResult("//fruits:q", "//fruits:melon", "//fruits:papaya");
  }

  @Test
  public void testDeterministic() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":apple",
                ":papaya",
            ],
        )

        foo_library(
            name = "papaya",
            deps = [":banana"],
        )

        foo_library(
            name = "banana",
            deps = [":apple"],
        )

        foo_library(
            name = "apple",
            deps = [":cherry"],
        )

        foo_library(name = "cherry")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        """);
    String firstResult = getQueryResult("//fruits:q");
    for (int i = 0; i < 10; i++) {
      createFilesAndMocks(); // Do a clean.
      assertThat(getQueryResult("//fruits:q")).isEqualTo(firstResult);
    }
  }

  @Test
  public void testDuplicateName() throws Exception {
    write(
        "one/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='foo')");
    write(
        "two/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='foo')");
    write(
        "query/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "common",
            deps = [
                "//one:foo",
                "//two:foo",
            ],
        )

        genquery(
            name = "q",
            expression = "deps(//query:common)",
            scope = ["//query:common"],
        )
        """);
    assertThat(getQueryResult("//query:q").split("\n")).hasLength(3);
  }

  @Test
  public void testFailsIfGoesOutOfScope() throws Exception {
    write(
        "vegetables/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tomato",
            deps = [":cabbage"],
        )

        foo_library(name = "cabbage")

        genquery(
            name = "q",
            expression = "deps(//vegetables:tomato)",
            scope = [":cabbage"],
        )
        """);

    assertThrows(expectedExceptionClass(), () -> buildTarget("//vegetables:q"));

    assertContainsEvent("is not within the scope of the query");
  }

  // Regression test for http://b/29964062.
  @Test
  public void testFailsIfGoesOutOfScopeViaSelect() throws Exception {
    write(
        "q/BUILD",
        """
        genquery(
            name = "q",
            expression = "deps(//q:f)",
            scope = ["f"],
        )

        config_setting(
            name = "cs",
            values = {"define": "D=1"},
        )

        filegroup(
            name = "f",
            srcs = select({
                "cs": [],
                "//conditions:default": ["//dne"],
            }),
        )
        """);

    addOptions("--define=D=1");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//q"));

    events.assertContainsError(
        "in genquery rule //q:q: errors were encountered while computing transitive closure of the"
            + " scope");
    events.assertContainsError(
        Pattern.compile(
            "no such package 'dne': BUILD file not found in any of the following directories. Add a"
                + " BUILD file to a directory to mark it as a package.\n"
                + " - dne"));
  }

  // Regression test for http://b/34132681
  @Test
  public void testFailsIfBrokenDependencyViaSelect() throws Exception {
    write(
        "q/BUILD",
        """
        genquery(
            name = "q",
            expression = "deps(//q:f)",
            scope = ["f"],
        )

        config_setting(
            name = "cs",
            values = {"define": "D=1"},
        )

        filegroup(
            name = "f",
            srcs = select({
                "cs": [],
                "//conditions:default": ["//d"],
            }),
        )
        """);
    // d exists but has nonexistent "deps"
    write("d/BUILD", "filegroup(name = 'd', deps = [])");

    addOptions("--define=D=1");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//q"));

    events.assertContainsError(
        "in genquery rule //q:q: errors were encountered while computing transitive closure of the"
            + " scope");
    events.assertContainsError("Target '//d:d' contains an error and its package is in error");
  }

  @Test
  public void testResultsAlphabetized() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":1",
                ":a",
                ":c",
                ":z",
                "//a:z",
                "//c",
                "//z:a",
            ],
        )

        foo_library(name = "a")

        foo_library(name = "z")

        foo_library(name = "1")

        foo_library(name = "c")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        """);
    write(
        "z/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");
    write(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'z')");
    write(
        "c/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'c', deps = ['//z:a'])");
    assertQueryResult(
        "//fruits:q",
        // Results are ordered in lexicographical order (uses graphless genquery by default).
        "//a:z",
        "//c:c",
        "//fruits:1",
        "//fruits:a",
        "//fruits:c",
        "//fruits:melon",
        "//fruits:z",
        "//z:a");
  }

  @Test
  public void testQueryReexecutedIfDepsChange() throws Exception {
    write(
        "food/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "fruit_salad",
            deps = ["//fruits:tropical"],
        )

        genquery(
            name = "q",
            expression = "deps(//food:fruit_salad)",
            scope = [":fruit_salad"],
        )
        """);

    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tropical",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")
        """);

    assertQueryResult("//food:q", "//food:fruit_salad", "//fruits:papaya", "//fruits:tropical");

    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tropical",
            deps = [
                ":coconut",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "coconut")
        """);

    assertQueryResult(
        "//food:q",
        "//food:fruit_salad",
        "//fruits:coconut",
        "//fruits:papaya",
        "//fruits:tropical");
  }

  @Test
  public void testGenQueryEncountersAnotherGenQuery() throws Exception {
    write(
        "spices/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "cinnamon",
            deps = [":nutmeg"],
        )

        foo_library(name = "nutmeg")

        genquery(
            name = "q",
            expression = "deps(//spices:cinnamon)",
            scope = [":cinnamon"],
        )
        """);

    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "pear",
            deps = [":plum"],
        )

        foo_library(name = "plum")

        genquery(
            name = "q",
            expression = "deps(//fruits:pear) + deps(//spices:q)",
            scope = [
                ":pear",
                "//spices:q",
            ],
        )
        """);

    assertQueryResult(
        "//fruits:q",
        "//fruits:pear",
        "//fruits:plum",
        "//spices:cinnamon",
        "//spices:nutmeg",
        "//spices:q");
  }

  /**
   * Regression test for b/14227750: genquery referring to non-existent target crashes on skyframe.
   */
  @Test
  public void testHandlesMissingTargetGracefully() throws Exception {
    write(
        "a/BUILD",
        "genquery(name='query', scope=['//b:target'], expression='deps(//b:nosuchtarget)')");
    write(
        "b/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'target')");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//a:query"));
    events.assertContainsError(
        "in genquery rule //a:query: query failed: no such target '//b:nosuchtarget'");
  }

  @Test
  public void testReportsMissingScopeTarget() throws Exception {
    write("a/BUILD", "genquery(name='query', scope=['//b:target'], expression='set()')");
    write("b/BUILD");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//a:query"));
    events.assertContainsError(
        "in genquery rule //a:query: errors were encountered while computing transitive closure of"
            + " the scope");
    events.assertContainsError(
        Pattern.compile(
            "no such target '//b:target': target 'target' not declared in package 'b' defined by"
                + " .*/b/BUILD"));
  }

  @Test
  public void testReportsMissingTransitiveScopeTarget() throws Exception {
    write(
        "a/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "query",
            expression = "set()",
            scope = [":missingdep"],
        )

        foo_library(
            name = "missingdep",
            deps = ["//b:target"],
        )
        """);
    write("b/BUILD");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//a:query"));
    events.assertContainsError(
        "in genquery rule //a:query: errors were encountered while computing transitive closure of"
            + " the scope");
    events.assertContainsError(
        Pattern.compile(
            "no such target '//b:target': target 'target' not declared in package 'b' defined by"
                + " .*/b/BUILD"));
  }

  @Test
  public void testReportsMissingScopePackage() throws Exception {
    write("a/BUILD", "genquery(name='query', scope=['//b:target'], expression='set()')");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//a:query"));
    events.assertContainsError(
        "in genquery rule //a:query: errors were encountered while computing transitive closure of"
            + " the scope");
    events.assertContainsError(
        Pattern.compile(
            "no such package 'b': BUILD file not found in any of the following directories. Add a"
                + " BUILD file to a directory to mark it as a package.\n"
                + " - b"));
  }

  @Test
  public void testReportsMissingTransitiveScopePackage() throws Exception {
    write(
        "a/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "query",
            expression = "set()",
            scope = [":missingdep"],
        )

        foo_library(
            name = "missingdep",
            deps = ["//b:target"],
        )
        """);
    assertThrows(expectedExceptionClass(), () -> buildTarget("//a:query"));
    events.assertContainsError(
        "in genquery rule //a:query: errors were encountered while computing transitive closure"
            + " of the scope");
    events.assertContainsError(
        Pattern.compile(
            "no such package 'b': BUILD file not found in any of the following"
                + " directories. Add a BUILD file to a directory to mark it as a package.\n"
                + " - b"));
  }

  @Test
  public void testMultiplePatternsInQuery() throws Exception {
    String buildFile = "load('//test_defs:foo_library.bzl', 'foo_library')\n";
    String genQuery =
        "genquery(name = 'q', scope = [':top'], expression = 'deps(//spices:top) ' + \n";
    String topTarget = "foo_library(name = 'top', deps = [\n";
    for (int i = 0; i < 20; i++) {
      String targetName = (i % 2 == 0 ? "in" : "out") + i;
      buildFile += "foo_library(name = '" + targetName + "')\n";
      if (i % 2 != 0) {
        genQuery += "' - //spices:" + targetName + " ' + \n";
      }
      topTarget += "    ':" + targetName + "',\n";
    }
    topTarget += "]\n)\n";
    genQuery += "'')";
    write("spices/BUILD", buildFile, topTarget, genQuery);
    List<String> expected = new ArrayList<>(11);
    for (int i = 0; i < 20; i += 2) {
      expected.add(i / 2, "//spices:in" + i);
    }
    expected.add(0, "//spices:top");
    Collections.sort(expected);
    assertQueryResult("//spices:q", expected.toArray(new String[0]));
  }

  @Test
  public void testGraphOutput_factored() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":coconut",
                ":mango",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "mango")

        foo_library(name = "coconut")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            opts = ["--output=graph"],
            scope = [":melon"],
        )
        """);
    assertPartialQueryResult(
        "//fruits:q",
        "  \"//fruits:melon\"",
        "  \"//fruits:melon\" -> \"//fruits:coconut\\n//fruits:mango\\n//fruits:papaya\"",
        "  \"//fruits:coconut\\n//fruits:mango\\n//fruits:papaya\"");
  }

  @Test
  public void testGraphOutput_unfactored() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":coconut",
                ":mango",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "mango")

        foo_library(name = "coconut")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            opts = [
                "--output=graph",
                "--nograph:factored",
            ],
            scope = [":melon"],
        )
        """);
    assertPartialQueryResult(
        "//fruits:q",
        "  \"//fruits:melon\"",
        "  \"//fruits:melon\" -> \"//fruits:coconut\"",
        "  \"//fruits:melon\" -> \"//fruits:mango\"",
        "  \"//fruits:melon\" -> \"//fruits:papaya\"",
        "  \"//fruits:papaya\"",
        "  \"//fruits:mango\"",
        "  \"//fruits:coconut\"");
  }

  @Test
  public void testDoesntAllowLocationOutputWithLoadfiles() throws Exception {
    write("foo/bzl.bzl", "x = 2");
    write(
        "foo/BUILD",
        """
        load("//foo:bzl.bzl", "x")

        filegroup(name = "foo")

        genquery(
            name = "gen-loadfiles",
            expression = "loadfiles(//foo:foo)",
            scope = ["//foo"],
        )

        genquery(
            name = "gen-loadfiles-location",
            expression = "loadfiles(//foo:foo)",
            opts = ["--output=location"],
            scope = ["//foo"],
        )
        """);
    assertQueryResult("//foo:gen-loadfiles", "//foo:bzl.bzl");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//foo:gen-loadfiles-location"));
    events.assertContainsError(
        "in genquery rule //foo:gen-loadfiles-location: query failed: Query expressions "
            + "involving 'buildfiles' or 'loadfiles' cannot be used with --output=location");
  }

  @Test
  public void testDoesntAllowLocationOutputWithBuildfiles() throws Exception {
    write("foo/bzl.bzl", "x = 2");
    write(
        "foo/BUILD",
        """
        load("//foo:bzl.bzl", "x")

        filegroup(name = "foo")

        genquery(
            name = "gen-buildfiles",
            expression = "buildfiles(//foo:foo)",
            scope = ["//foo"],
        )

        genquery(
            name = "gen-buildfiles-location",
            expression = "buildfiles(//foo:foo)",
            opts = ["--output=location"],
            scope = ["//foo"],
        )
        """);
    assertQueryResult("//foo:gen-buildfiles", "//foo:BUILD", "//foo:bzl.bzl");
    assertThrows(expectedExceptionClass(), () -> buildTarget("//foo:gen-buildfiles-location"));
    events.assertContainsError(
        "in genquery rule //foo:gen-buildfiles-location: query failed: Query expressions "
            + "involving 'buildfiles' or 'loadfiles' cannot be used with --output=location");
  }

  /** Regression test for b/127644784. */
  @Test
  public void somepathOutputDeterministic() throws Exception {
    /*
     * This graph structure routinely reproduces the bug within 10 iterations:
     *
     *   ----------top------------
     *   |       |       |       |
     *  mid1    mid2    mid3    mid4
     *   |       |       |       |
     *   --lower--       |       |
     *       |           |       |
     *       -----bottom----------
     */
    write(
        "query/BUILD",
        """
        genquery(
            name = "query",
            expression = "somepath(//top, //bottom)",
            scope = [
                "//top",
                "//bottom",
            ],
        )
        """);
    write(
        "top/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'top', deps = ['//mid1', '//mid2', '//mid3', '//mid4'])");
    write(
        "mid1/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'mid1', deps = ['//lower'])");
    write(
        "mid2/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'mid2', deps = ['//lower'])");
    write(
        "mid3/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'mid3', deps = ['//bottom'])");
    write(
        "mid4/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'mid4', deps = ['//bottom'])");
    write(
        "lower/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'lower', deps = ['//bottom'])");
    write(
        "bottom/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bottom')");

    String firstResult = getQueryResult("//query");
    for (int i = 0; i < 10; i++) {
      createFilesAndMocks(); // Do a clean.
      assertThat(getQueryResult("//query")).isEqualTo(firstResult);
    }
  }

  private void runNodepDepsTest(String optsStringValue, boolean expectVisibilityDep)
      throws Exception {
    write(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "t1",
            visibility = [
                ":pg",
                "//query:__pkg__",
            ],
            deps = [":t2"],
        )

        foo_library(name = "t2")

        package_group(name = "pg")
        """);
    write(
        "query/BUILD",
        "genquery(",
        "  name = 'gen',",
        "  expression = 'deps(//foo:t1)',",
        "  scope = ['//foo:t1'],",
        "  opts = " + optsStringValue,
        ")");

    List<String> queryResultStrings =
        ImmutableList.copyOf(getQueryResult("//query:gen").split("\n"));
    if (expectVisibilityDep) {
      assertThat(queryResultStrings).contains("//foo:pg");
    } else {
      assertThat(queryResultStrings).doesNotContain("//foo:pg");
    }
  }

  @Test
  public void testNodepDeps_defaultIsFalse() throws Exception {
    runNodepDepsTest(/* optsStringValue= */ "[]", /* expectVisibilityDep= */ false);
  }

  @Test
  public void testNodepDeps_false() throws Exception {
    runNodepDepsTest(
        /* optsStringValue= */ "['--nodep_deps=false']", /* expectVisibilityDep= */ false);
  }

  @Test
  public void testNodepDeps_true() throws Exception {
    runNodepDepsTest(
        /* optsStringValue= */ "['--nodep_deps=true']", /* expectVisibilityDep= */ true);
  }

  @Test
  public void testLoadingPhaseCycle() throws Exception {
    // This test uses a target in a self-cycle to demonstrate that a genquery rule having a cycle in
    // its scope causes it to fail, unless --experimental_skip_ttvs_for_genquery is used.
    write(
        "cycle/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "gen",
            expression = "//cycle",
            scope = [":cycle"],
        )

        foo_library(
            name = "cycle",
            deps = [":cycle"],
        )
        """);
    if (ttvFree) {
      assertQueryResult("//cycle:gen", "//cycle:cycle");
    } else {
      assertThrows(expectedExceptionClass(), () -> buildTarget("//cycle:gen"));
      assertContainsEvent("in foo_library rule //cycle:cycle: cycle in dependency graph");
    }
  }

  private void writeAspectDefinition(String aspectPackage, String extraDep) throws Exception {
    write(aspectPackage + "/BUILD");
    write(
        aspectPackage + "/aspect.bzl",
        "def _aspect_impl(target, ctx):",
        "   return []",
        "def _rule_impl(ctx):",
        "   return []",
        "MyAspect = aspect(",
        "   implementation=_aspect_impl,",
        "   attr_aspects=['deps'],",
        "   attrs = {'_extra_deps': attr.label(default = Label('" + extraDep + "'))})",
        "aspect_rule = rule(",
        "   implementation=_rule_impl,",
        "   attrs = { 'attr' : ",
        "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]),",
        "             'param' : attr.string(),",
        "           },",
        ")");
  }

  @Test
  public void testAspectDepChain() throws Exception {
    writeAspectDefinition("aspect1", "//middle");
    writeAspectDefinition("aspect2", "//end");
    write(
        "start/BUILD",
        """
        load("//aspect1:aspect.bzl", "aspect_rule")
        load('//test_defs:foo_library.bzl', 'foo_library')

        genquery(
            name = "gen",
            expression = "deps(//start)",
            scope = [":start"],
        )

        aspect_rule(
            name = "start",
            attr = [":startdep"],
        )

        foo_library(name = "startdep")
        """);
    write(
        "middle/BUILD",
        """
        load("//aspect2:aspect.bzl", "aspect_rule")
        load('//test_defs:foo_library.bzl', 'foo_library')

        aspect_rule(
            name = "middle",
            attr = [":middledep"],
        )

        foo_library(name = "middledep")
        """);
    write(
        "end/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "end",
            deps = [":enddep"],
        )

        foo_library(name = "enddep")
        """);
    assertQueryResult(
        "//start:gen",
        "//end:end",
        "//end:enddep",
        "//middle:middle",
        "//middle:middledep",
        "//start:start",
        "//start:startdep");
  }

  @Test
  public void testGenQueryOutputCompressed() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            compressed_output = True,
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        """);

    buildTarget("//fruits:q");
    Artifact output = Iterables.getOnlyElement(getArtifacts("//fruits:q"));
    ByteString compressedContent = readContentAsByteArray(output);

    ByteArrayOutputStream decompressedOut = new ByteArrayOutputStream();
    try (GZIPInputStream gzipIn = new GZIPInputStream(compressedContent.newInput())) {
      ByteStreams.copy(gzipIn, decompressedOut);
    }

    assertThat(decompressedOut.toString(UTF_8)).isEqualTo("//fruits:melon\n//fruits:papaya\n");
  }

  @Test
  public void testConsistentLabels() throws Exception {
    write(
        "fruits/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
            opts = ["--consistent_labels"],
        )
        """);
    assertQueryResult("//fruits:q", "@@//fruits:melon", "@@//fruits:papaya");
  }

  @Test
  public void testGenQueryInExternalRepo() throws Exception {
    if (!AnalysisMock.get().isThisBazel()) {
      return;
    }
    write(
        "MODULE.bazel",
        """
        bazel_dep(name = "other_module")
        local_path_override(
            module_name = "other_module",
            path = "other_module",
        )
        """);
    write(
        "other_module/MODULE.bazel",
        """
        module(name = 'other_module')
        """);
    write(
        "other_module/fruits/BUILD",
        """
        load('@@//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        """);

    assertQueryResult(
        "@@other_module+//fruits:q",
        "@@other_module+//fruits:melon",
        "@@other_module+//fruits:papaya");
  }

  private void assertQueryResult(String queryTarget, String... expected) throws Exception {
    assertThat(getQueryResult(queryTarget).split("\n"))
        .asList()
        .containsExactlyElementsIn(ImmutableList.copyOf(expected))
        .inOrder();
  }

  private void assertPartialQueryResult(String queryTarget, String... expected) throws Exception {
    assertThat(getQueryResult(queryTarget).split("\n"))
        .asList()
        .containsAtLeastElementsIn(ImmutableList.copyOf(expected))
        .inOrder();
  }

  private String getQueryResult(String queryTarget) throws Exception {
    buildTarget(queryTarget);
    Artifact output = Iterables.getOnlyElement(getArtifacts(queryTarget));
    assertThat(getAllKeysInGraph().stream().anyMatch(key -> key instanceof TransitiveTargetKey))
        .isEqualTo(!ttvFree);
    return readContentAsLatin1String(output);
  }

  private Class<? extends Throwable> expectedExceptionClass() {
    return keepGoing ? BuildFailedException.class : ViewCreationFailedException.class;
  }
}
