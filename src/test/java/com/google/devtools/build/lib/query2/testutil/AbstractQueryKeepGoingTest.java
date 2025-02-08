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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.testutil.AbstractQueryTest.QueryHelper.ResultAndTargets;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.ExitCode;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for query evaluation when keep_going is enabled. It covers the QueryEvalTest and adds
 * additional tests that are keep_going-specific.
 */
public abstract class AbstractQueryKeepGoingTest extends QueryTest {

  @Before
  public final void setKeepGoing() throws Exception {
    helper.setKeepGoing(true);
  }

  // Like eval(), but asserts that evaluation completes normally, with an error.
  // Events should be checked with assertContainsEvent().
  protected ResultAndTargets<Target> evalFail(String query) throws Exception {
    ResultAndTargets<Target> result = helper.evaluateQuery(query);
    assertWithMessage("evaluateQuery succeeded: " + query)
        .that(result.getQueryEvalResult().getSuccess())
        .isFalse();
    return result;
  }

  // Like eval(), but makes no assertions about whether evaluation completes with an error.
  // Because the query helper reuses its AbstractBlazeQueryEnvironment, BlazeQueryEnvironment-based
  // implementations will perform graph evaluations using the same memoizing evaluator, which reuses
  // the same EmittedEventState, causing later evaluations that emit the same errors to not count as
  // failures. SkyQueryEnvironment-based implementations do not do this, and may report that later
  // evaluations have failed.
  // In either case, events should be checked with assertContainsEvent().
  // TODO(bazel-team): it is probably unintentional that BlazeQueryEnvironment-based evaluations'
  // error state is sensitive to prior evaluations. Tests that use this method should be fixed when
  // there's a chance to fix the state that's retained across queries because of the query helper
  // and BlazeQueryEnvironment.
  protected Set<Target> evalMaybe(String query) throws Exception {
    return helper.evaluateQuery(query).getResultSet();
  }

  @Override
  protected EvalThrowsResult evalThrows(String query, boolean unconditionallyThrows)
      throws Exception {
    // This method can be called in both keep_going and nokeep_going modes: expect either an
    // exception or an error message.
    try {
      ResultAndTargets<Target> result = evalFail(query);
      assertThat(helper.isKeepGoing()).isTrue();
      String msg =
          helper
              .getFirstEvent()
              .replaceAll("^Skipping '[^']+': ", "")
              .replaceAll("Evaluation of query \"[^\"]+\" failed: ", "");
      return new EvalThrowsResult(
          msg, result.getQueryEvalResult().getDetailedExitCode().getFailureDetail());
    } catch (QueryException e) {
      // TODO(ulfjack): Even in keep_going mode, the query engine sometimes throws a QueryException.
      // Remove the guard and fix the problems.
      if (!unconditionallyThrows) {
        assertThat(helper.isKeepGoing()).isFalse();
      }
      String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
      return new EvalThrowsResult(msg, e.getFailureDetail());
    }
  }

  // Regression test for bug #2482284:
  // "blaze query mypackage:* does not report targets that cross package boundaries"
  @Test
  public void testErrorWhenResultContainsLabelsCrossingSubpackage() throws Exception {
    writeFile(
        "pear/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "plum/peach",
            srcs = ["peach.sh"],
        )

        foo_library(
            name = "apple",
            srcs = ["apple.sh"],
        )
        """);
    writeFile("pear/plum/BUILD");

    ResultAndTargets<Target> resultAndTargets = evalFail("//pear:apple");
    assertContainsEvent("is invalid because 'pear/plum' is a subpackage");
    if (helper.reportsUniverseEvaluationErrors()) {
      assertPackageLoadingCode(resultAndTargets, Code.LABEL_CROSSES_PACKAGE_BOUNDARY);
    } else {
      assertQueryCode(
          resultAndTargets.getQueryEvalResult().getDetailedExitCode().getFailureDetail(),
          Query.Code.BUILD_FILE_ERROR);
    }
  }

  @Test
  public void testErrorWhenWildcardResultContainsLabelsCrossingSubpackage() throws Exception {
    writeFile(
        "pear/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "plum/peach",
            srcs = ["peach.sh"],
        )

        foo_library(
            name = "apple",
            srcs = ["apple.sh"],
        )
        """);
    writeFile("pear/plum/BUILD");

    ResultAndTargets<Target> resultAndTargets = evalFail("//pear:all");
    assertContainsEvent("is invalid because 'pear/plum' is a subpackage");
    if (helper.reportsUniverseEvaluationErrors()) {
      assertPackageLoadingCode(resultAndTargets, Code.LABEL_CROSSES_PACKAGE_BOUNDARY);
    } else {
      assertQueryCode(
          resultAndTargets.getQueryEvalResult().getDetailedExitCode().getFailureDetail(),
          Query.Code.BUILD_FILE_ERROR);
    }
  }

  @Override
  protected void writeBuildFiles3() throws Exception {
    writeFile(
        "a/BUILD",
        """
        genrule(
            name = "a",
            srcs = [
                "//b",
                "//c",
            ],
            outs = ["out"],
            cmd = ":",
        )

        exports_files(["a2"])
        """);
    writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("d/BUILD", "exports_files(['d'])");
  }

  protected void assertNoFailFast(
      String errorMsg, boolean checkFailureDetail, String keepGoingErrorMsg) throws Exception {
    writeFile(
        "missingdep/BUILD",
        """
        cc_library(
            name = "missingdep",
            deps = ["//i/do/not/exist"],
        )
        """);

    helper.setKeepGoing(false);
    EvalThrowsResult throwsResult1 = evalThrows("deps(//missingdep)", false);
    assertThat(throwsResult1.getMessage()).contains(errorMsg);
    if (checkFailureDetail) {
      assertPackageLoadingCode(throwsResult1.getFailureDetail(), Code.BUILD_FILE_MISSING);
    }

    // (1) --keep_going.
    helper.clearEvents();
    helper.setKeepGoing(true);
    // partial results
    ResultAndTargets<Target> failResult =
        evalFail("deps(//missingdep)" + TestConstants.CC_DEPENDENCY_CORRECTION);
    assertThat(failResult.getResultSet()).isEqualTo(eval("//missingdep"));
    assertContainsEvent("Evaluation of query \"deps(//missingdep)\" failed: " + keepGoingErrorMsg);
    if (checkFailureDetail) {
      assertPackageLoadingCode(failResult, Code.BUILD_FILE_MISSING);
    }

    // (2) --nokeep_going.
    helper.setKeepGoing(false);
    EvalThrowsResult throwsResult2 = evalThrows("deps(//missingdep)", false);
    assertThat(throwsResult2.getMessage()).contains(errorMsg); // no results
    if (checkFailureDetail) {
      assertPackageLoadingCode(throwsResult2.getFailureDetail(), Code.BUILD_FILE_MISSING);
    }
  }

  // Regression test for bug #1234015, "blaze query --keep_going doesn't
  // always work".  Previously, any failure in a labels() expression would
  // cause results to be suppressed.  Now, partial results are printed.
  @Test
  public void testNoFailFastOnLabelsExpression() throws Exception {
    writeFile(
        "bad/BUILD", "genrule(name='bad', srcs=['x', '//missing', 'y'], outs=['out'], cmd=':')");

    Set<Target> result = evalFail("labels(srcs, //bad)").getResultSet();
    assertContainsEvent("no such package 'missing': " + "BUILD file not found");
    assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate");
    assertThat(result).isEqualTo(eval("//bad:x + //bad:y")); // partial results
  }

  // Ensure that --keep_going distinguishes malformed target literals from
  // good ones that happen to refer to bad BUILD files.
  @Test
  public void testBadBuildFileKeepGoing() throws Exception {
    writeFile("bad/BUILD", "blah blah blah");
    ResultAndTargets<Target> result = evalFail("bad:*");
    if (helper.reportsUniverseEvaluationErrors()) {
      assertPackageLoadingCode(result, Code.SYNTAX_ERROR);
    } else {
      assertQueryCode(
          result.getQueryEvalResult().getDetailedExitCode().getFailureDetail(),
          Query.Code.BUILD_FILE_ERROR);
    }
    assertContainsEvent("syntax error at 'blah'");
    assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate");

    assertThat(result.getResultSet()).isEqualTo(evalMaybe("//bad:BUILD")); // partial results
  }

  @Test
  public void testStrictTestSuiteWithFileAndKeepGoing() throws Exception {
    helper.setQuerySettings(Setting.TESTS_EXPRESSION_STRICT);
    writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])");
    assertThat(evalFail("tests(//x:a)").getResultSet()).isEmpty();
    assertContainsEvent(
        "The label '//x:a.txt' in the test_suite '//x:a' does not refer to a test "
            + "or test_suite rule!");
  }

  @Test
  public void testQueryAllForBrokenPackage() throws Exception {
    writeFile(
        "x/BUILD",
        """
        filegroup(name = "a")

        x = 1 // 0

        filegroup(name = "c")
        """ // not executed
        );
    assertThat(evalFail("//x:all").getResultSet()).hasSize(1);
    assertContainsEvent("division by zero");
    assertContainsEvent("Results may be inaccurate");
  }

  @Test
  public void testQueryDotDotDotForBrokenPackage() throws Exception {
    writeFile(
        "x/BUILD",
        """
        filegroup(name = "a")

        x = 1 // 0

        filegroup(name = "c")
        """ // not executed
        );
    assertThat(evalFail("//x/...").getResultSet()).hasSize(1);
    assertContainsEvent("division by zero");
    assertContainsEvent("Results may be inaccurate");
  }

  @Test
  public void testNonExistentDotDotDot() throws Exception {
    assertThat(evalFail("//does_not_exist/...").getResultSet()).isEmpty();
    assertContainsEvent("no targets found beneath 'does_not_exist'");
    assertContainsEvent("Results may be inaccurate");
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_TBD()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/...", 1);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_TIP()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/foo:all", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_ST()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("//foo/foo:banana", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile_IPAT()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile("foo/foo/banana", 0);
  }

  private void runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile(
      String queryExpression, int numExpectedTargets) throws Exception {
    if (helper.reportsUniverseEvaluationErrors()) {
      // This family of test cases are interesting only for query environments that don't report
      // universe evaluation errors. This way we can assert that any error message came from query
      // evaluation.
      return;
    }

    // Starlark imports must refer to files in packages. When the file being imported exists, but
    // it has no containing package, an error should be reported for queries that involve the
    // package containing that import.

    // The package "//foo" can be loaded and has no errors.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='apple', srcs=['apple.sh'])");

    // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
    // on the load, but because the package failed to load, it does not exist.
    writeFile(
        "foo/foo/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);

    // This Starlark file is fine, but it has no containing package, so it can't be loaded.
    writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')");

    assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets);

    String expectedError =
        "error loading package 'foo/foo': Every .bzl file must have a corresponding package";
    assertContainsEvent(expectedError);
  }

  @Test
  public void testPluralErrorsReportedWhenStarlarkLoadRefersToMissingPkgExistingFile()
      throws Exception {
    // This test does not yet pass for some SkyQueryEnvironment-specific QueryExpression
    // implementations.

    // Like runTestErrorReportedWhenStarlarkLoadRefersToMissingPkgExistingFile, but with multiple
    // packages in error, testing that each packages' error is reported.

    // The package "//foo" can be loaded and has no errors.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='apple', srcs=['apple.sh'])");

    // The packages "//foo/foo" and "//foo/foo2" each have a load statement that fails. The
    // ":banana" targets do not depend on the load, but because the packages failed to load, they do
    // not exist.
    writeFile(
        "foo/foo/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);
    writeFile(
        "foo/foo2/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);

    // This Starlark file is fine, but it has no containing package, so it can't be loaded.
    writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')");

    assertThat(evalFail("//foo/foo:*").getResultSet()).isEmpty();
    String expectedError =
        "error loading package 'foo/foo': Every .bzl file must have a corresponding package, "
            + "but '//bar:lib.bzl' does not have one";
    assertContainsEvent(expectedError);
    helper.clearEvents();

    assertThat(evalFail("//foo/foo2:*").getResultSet()).isEmpty();
    String expectedError2 =
        "error loading package 'foo/foo2': Every .bzl file must have a corresponding package, "
            + "but '//bar:lib.bzl' does not have one";
    assertContainsEvent(expectedError2);
    helper.clearEvents();

    assertThat(evalFail("//foo/foo:* + //foo/foo2:*").getResultSet()).isEmpty();
    assertContainsEvent(expectedError);
    assertContainsEvent(expectedError2);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_TBD()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/...", 1);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_TIP()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/foo:all", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_ST()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("//foo/foo:banana", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile_IPAT()
      throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile("foo/foo/banana", 0);
  }

  private void runTestErrorReportedWhenStarlarkLoadRefersToExistingPkgMissingFile(
      String queryExpression, int numExpectedTargets) throws Exception {
    if (helper.reportsUniverseEvaluationErrors()) {
      // This family of test cases are interesting only for query environments that don't report
      // universe evaluation errors. This way we can assert that any error message came from query
      // evaluation.
      return;
    }

    // Starlark imports must refer to files that exist, otherwise they will fail and an error should
    // be reported. How shocking!

    // The package "//foo" can be loaded and has no errors.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='apple', srcs=['apple.sh'])");

    // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
    // on the load, but because the package failed to load, it does not exist.
    writeFile(
        "foo/foo/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);

    // The load statement in "//foo/foo" refers to an existing package, but the Starlark file is
    // missing.
    writeFile("bar/BUILD");

    assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets);

    String expectedError =
        "error loading package 'foo/foo': cannot load '//bar:lib.bzl': no such file";
    assertContainsEvent(expectedError);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_TBD() throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/...", 1);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_TIP() throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/foo:all", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_ST() throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("//foo/foo:banana", 0);
  }

  @Test
  public void testErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle_IPAT() throws Exception {
    runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle("foo/foo/banana", 0);
  }

  private void runTestErrorReportedWhenStarlarkLoadRefersToFileInSymlinkCycle(
      String queryExpression, int numExpectedTargets) throws Exception {
    if (helper.reportsUniverseEvaluationErrors()) {
      // This family of test cases are interesting only for query environments that don't report
      // universe evaluation errors. This way we can assert that any error message came from query
      // evaluation.
      return;
    }

    // Starlark imports must refer to files that don't point into a symlink cycle, otherwise they
    // will fail and an error should be reported. Quite astonishing!

    // The package "//foo" can be loaded and has no errors.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='apple', srcs=['apple.sh'])");

    // The package "//foo/foo" has a load statement that fails. Its ":banana" target does not depend
    // on the load, but because the package failed to load, it does not exist.
    writeFile(
        "foo/foo/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);

    // The load statement in "//foo/foo" refers to an existing package, but the Starlark file the
    // load statement refers to points into a symlink cycle.
    writeFile("bar/BUILD");
    ensureSymbolicLink("bar/lib.bzl", "bar/recursion");
    ensureSymbolicLink("bar/recursion", "bar/recursion");

    assertThat(evalFail(queryExpression).getResultSet()).hasSize(numExpectedTargets);

    String expectedError =
        "error loading package 'foo/foo': Encountered error while reading extension file"
            + " 'bar/lib.bzl': Symlink cycle";
    assertContainsEvent(expectedError);
  }

  @Test
  public void testNoErrorReportedWhenUniverseIncludesBrokenPkgButQueryDoesNot() throws Exception {
    if (helper.reportsUniverseEvaluationErrors()) {
      // This test case is interesting only for query environments that don't report universe
      // evaluation errors. This way we can assert that any error message came from query
      // evaluation.
      return;
    }

    // The package "//foo" is healthy.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='apple', srcs=['apple.sh'])");

    // The package "//baz" is not healthy: it contains a load statement referring to an unpackaged
    // Starlark file.
    writeFile(
        "baz/BUILD",
        """
        load("//bar:lib.bzl", "myfunc")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "banana",
            srcs = ["banana.sh"],
        )
        """);
    writeFile("bar/lib.bzl", "custom_rule(name = 'myfunc')");

    // Nevertheless, a query affecting just the healthy package emits no errors.
    assertThat(eval("//foo/...")).hasSize(1);
    assertDoesNotContainEvent("error loading package 'baz'");
  }

  @Override
  @Test
  public void boundedRdepsWithError() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            deps = ["//bar:missing"],
        )
        """);
    ResultAndTargets<Target> targetResultAndTargets = evalFail("rdeps(//foo:foo, //foo:dep, 1)");
    assertThat(
            targetResultAndTargets.getResultSet().stream()
                .map(t -> Label.print(t.getLabel()))
                .collect(toImmutableSet()))
        .containsExactly("//foo:dep", "//foo:foo");
    // Ideally we wouldn't print this irrelevant error (since //bar:missing is a dep of //foo:dep,
    // not an rdep), or make it fail the query.
    assertThat(targetResultAndTargets.getQueryEvalResult().getDetailedExitCode().getExitCode())
        .isEqualTo(ExitCode.BUILD_FAILURE);
    assertContainsEvent("no such package 'bar':");
  }

  @Test
  public void testIgnoredSubdirectoryIsTBDQuery() throws Exception {
    overwriteFile(helper.getIgnoredSubdirectoriesFile().getPathString(), "a/b");
    writeFile("a/BUILD", "filegroup(name = 'a')");
    writeFile("a/b/BUILD", "filegroup(name = 'a_b')");
    writeFile("a/b/c/BUILD", "filegroup(name = 'a_b_c')");

    // Ensure that modified files are invalidated in the skyframe. If a file has
    // already been read prior to the test's writes, this forces the query to
    // pick up the modified versions.
    helper.maybeHandleDiffs();

    ResultAndTargets<Target> resultAndTargets = helper.evaluateQuery("//a/b/...");
    assertContainsEvent("Pattern '//a/b/...' was filtered out by ignored directory 'a/b'");
    assertThat(resultAndTargets.getQueryEvalResult().getSuccess()).isTrue();
    assertThat(targetLabels(resultAndTargets.getResultSet())).isEmpty();
  }

  @Test
  public void bogusVisibility() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility = ["//visibility:public"])

        foo_library(
            name = "a",
            visibility = [
                "//bad:visibility",
                "//bar:__pkg__",
            ],
        )

        foo_library(name = "b")

        foo_library(
            name = "c",
            visibility = ["//bad:visibility"],
        )
        """);
    writeFile("bar/BUILD");
    ResultAndTargets<Target> resultAndTargets =
        helper.evaluateQuery("visible(//bar:BUILD, //foo:all)");
    assertThat(resultAndTargets.getQueryEvalResult().getSuccess()).isFalse();
    assertThat(targetLabels(resultAndTargets.getResultSet())).containsExactly("//foo:a", "//foo:b");
    assertContainsEvent("Invalid visibility label '//bad:visibility': no such package 'bad'");
    assertContainsEvent("--keep_going specified, ignoring errors. Results may be inaccurate");
  }
}
