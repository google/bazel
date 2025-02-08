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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.lang.ref.WeakReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Miscellaneous tests of the analysis phase. (Sometimes it's easier to express these in terms of
 * the BuildTool than of the BuildView because the latter's class interface is quite complex.)
 */
@RunWith(JUnit4.class)
public class MiscAnalysisTest extends BuildIntegrationTestCase {

  @Test
  public void testWarningsNotReplayed() throws Exception {
    AnalysisMock.get().pySupport().setup(mockToolsConfig);
    write(
        "y/BUILD",
        "genrule(name='y', outs=['y.out'], cmd='touch $@', deprecation='generate a warning')");
    addOptions("--nobuild");

    buildTarget("//y");
    events.assertContainsWarning("target '//y:y' is deprecated");

    buildTarget("//y");
    assertDoesNotContainEvent("target '//y:y' is deprecated");
  }

  @Test
  public void testDeprecatedTargetOnCommandLine() throws Exception {
    write(
        "raspberry/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='raspberry', srcs=['raspberry.sh'], deprecation='rotten')");
    addOptions("--nobuild");
    buildTarget("//raspberry:raspberry");
    events.assertContainsWarning("target '//raspberry:raspberry' is deprecated: rotten");
  }

  @Test
  public void targetAnalyzedInTwoConfigurations_deprecationWarningDisplayedOncePerBuild()
      throws Exception {
    // :a depends on :dep in the target configuration. :b depends on :dep in the exec configuration.
    write(
        "foo/BUILD",
        """
        genrule(
            name = "a",
            srcs = [":dep"],
            outs = ["a.out"],
            cmd = "touch $@",
        )

        genrule(
            name = "b",
            outs = ["b.out"],
            cmd = "touch $@",
            tools = [":dep"],
        )

        genrule(
            name = "dep",
            srcs = ["//deprecated"],
            outs = ["dep.out"],
            cmd = "touch $@",
        )
        """);
    write(
        "deprecated/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'deprecated', deprecation = 'old')");
    addOptions("--nobuild");
    buildTarget("//foo:a", "//foo:b");
    events.assertContainsEventWithFrequency(
        "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1);

    // Edit to force re-analysis.
    write(
        "deprecated/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'deprecated', deprecation = 'very old')");
    buildTarget("//foo:a", "//foo:b");
    events.assertContainsEventWithFrequency(
        "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1);
  }

  // Regression test for http://b/12465751: "IllegalStateException in ParallelEvaluator".
  @Test
  public void testShBinaryTwoSrcs() throws Exception {
    write(
        "test_defs/foo_one.bzl",
        """
        def _impl(ctx):
          if len(ctx.files.srcs) != 1:
             fail("you must specify exactly one file in 'srcs'", attr = "srcs")
        foo_one = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
          },
        )
        """);
    write(
        "sh/BUILD",
        "load('//test_defs:foo_one.bzl', 'foo_one')",
        "foo_one(name = 'double', srcs = ['a','b'])");
    addOptions("--nobuild");

    assertThrows(Exception.class, () -> buildTarget("//sh:double"));
    events.assertContainsError("you must specify exactly one file in 'srcs'");
  }

  // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
  @Test
  public void testAnalysisCachingAndKeepGoing() throws Exception {
    write(
        "fruit/BUILD",
        """
        cc_library(
            name = "apple",
            deps = [":banana"],
        )

        cc_library(
            name = "banana",
            deps = [":cherry"],
        )

        cc_library(
            name = "cherry",
            deps = [":durian__hdrs__"],
        )

        genrule(
            name = "durian",
            outs = ["durian.out"],
            cmd = ":",
        )
        """);
    addOptions("--nobuild", "--keep_going");

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//fruit:apple"));
    assertThat(e).hasMessageThat().contains("command succeeded");
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:cherry: "
            + "target '//fruit:durian__hdrs__' does not exist");

    e = assertThrows(BuildFailedException.class, () -> buildTarget("//fruit:apple"));
    assertThat(e).hasMessageThat().contains("command succeeded");
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:cherry: "
            + "target '//fruit:durian__hdrs__' does not exist");
  }

  // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
  @Test
  public void testErrorsAreReplayedEvenWithAnalysisCaching() throws Exception {
    write(
        "fruit/BUILD",
        """
        cc_library(
            name = "apple",
            deps = [":banana__hdrs__"],
        )

        genrule(
            name = "banana",
            outs = ["banana.out"],
            cmd = ":",
        )
        """);
    addOptions("--nobuild");

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//fruit:apple"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:apple: "
            + "target '//fruit:banana__hdrs__' does not exist");

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//fruit:apple"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:apple: "
            + "target '//fruit:banana__hdrs__' does not exist");
  }

  @Test
  public void testBuildAllAndParsingError() throws Exception {
    write(
        "pkg/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(",
        "name = \"foo\",",
        "  syntax error here",
        ")");

    addOptions("--nobuild");

    Exception e = assertThrows(Exception.class, () -> buildTarget("//pkg:all"));
    events.assertContainsError("syntax error at 'error'");
    assertPkgErrorMsg(e);
  }

  @Test
  public void testDiscardAnalysisCache() throws Exception {
    write(
        "sh/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "sh",
            srcs = [],
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            srcs = [],
        )
        """);
    buildTarget("//sh:sh");
    // We test with dep because we may keep references to the top-level configured targets.
    ConfiguredTarget ct = getConfiguredTarget("//sh:dep");
    addOptions("--discard_analysis_cache");
    buildTarget("//sh:sh");
    addOptions("--nodiscard_analysis_cache");
    buildTarget("//sh:sh");
    // Configured target was replaced.
    ConfiguredTarget newCt = getConfiguredTarget("//sh:dep");
    assertThat(newCt).isNotSameInstanceAs(ct);
    WeakReference<ConfiguredTarget> ref = new WeakReference<>(newCt);
    newCt = null;
    addOptions("--discard_analysis_cache");
    buildTarget("//sh:sh");
    GcFinalization.awaitClear(ref);
  }

  @Test
  public void testDiscardAnalysisCacheWithError() throws Exception {
    write(
        "x/BUILD",
        """
        cc_library(
            name = "x",
            deps = [":z__hdrs__"],
        )

        genrule(
            name = "z",
            outs = ["z.out"],
            cmd = ":",
        )
        """);
    write("y/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='y')");
    addOptions("--discard_analysis_cache", "--keep_going");
    EventCollector collector = new EventCollector(EventKind.STDERR);
    events.addHandler(collector);
    assertThrows(BuildFailedException.class, () -> buildTarget("//x:x", "//y:y"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //x:x: target '//x:z__hdrs__' does not exist");
    MoreAsserts.assertContainsEvent(collector, "Target //y:y up-to-date", EventKind.STDERR);
  }

  @Test
  public void testBuildAllAndEvaluationError() throws Exception {
    write(
        "pkg/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "foo",
            srcs = unknown_value,
        )
        """);

    addOptions("--nobuild");

    Exception e = assertThrows(Exception.class, () -> buildTarget("//pkg:all"));
    events.assertContainsError("name 'unknown_value' is not defined");
    assertPkgErrorMsg(e);
  }

  private static void assertPkgErrorMsg(Exception e) {
    assertThat(e).hasMessageThat().containsMatch("[pP]ackage.*contains errors");
  }

  @Test
  public void testNoTestTargetsFoundMessageForBuildCommand() throws Exception {
    write("pkg/BUILD");
    for (String option : ImmutableList.of("", "--nobuild", "--noanalyze")) {
      setupOptions();
      addOptions(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
      addOptions(option);
      buildTarget("//pkg:all");
      assertDoesNotContainEvent("test target");
    }
  }
}
