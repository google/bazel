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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.testutil.TestConstants.GENRULE_SETUP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.truth.Truth;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.graph.DotOutputVisitor;
import com.google.devtools.build.lib.graph.Node;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.query2.engine.DigraphQueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.testutil.AbstractQueryTest.QueryHelper.ResultAndTargets;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the query engine, generic over the result type. This allows us to share the tests
 * between the different implementations, and also parameterize it over the set of options, such as
 * {@code --keep_going}.
 *
 * @param <T> the actual target type
 */
public abstract class AbstractQueryTest<T> {

  protected static final ImmutableSet<?> EMPTY = ImmutableSet.of();

  private static final String DEFAULT_UNIVERSE = "//...:*";

  protected MockToolsConfig mockToolsConfig;
  protected QueryHelper<T> helper;
  protected AnalysisMock analysisMock;

  protected ConfiguredRuleClassProvider.Builder setRuleClassProviders(MockRule... mockRules) {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    for (MockRule rule : mockRules) {
      builder.addRuleDefinition(rule);
    }
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder;
  }

  @Before
  public final void initializeQueryHelper() throws Exception {
    helper = createQueryHelper();
    helper.setUp();
    mockToolsConfig = new MockToolsConfig(helper.getRootDirectory());
    analysisMock = AnalysisMock.get();
    helper.setUniverseScope(getDefaultUniverseScope());
    helper.useRuleClassProvider(setRuleClassProviders().build());

    analysisMock.setupMockTestingRules(mockToolsConfig);
  }

  @After
  public final void cleanUpQueryHelper() {
    helper.cleanUp();
  }

  /**
   * By default, we load the universe (of both rules and files) for our tests. If a specific test or
   * subclass requires that only a subset of the universe is loaded, it may override this default
   * and/or specify a per-test method universe scope.
   */
  protected String getDefaultUniverseScope() {
    return DEFAULT_UNIVERSE;
  }

  protected abstract QueryHelper<T> createQueryHelper();

  /**
   * Used to disable configurable attribute queries on DepServerQueryEnvironment, which doesn't
   * support them.
   */
  protected boolean testConfigurableAttributes() {
    return true;
  }

  /** Partial query to filter out implicit dependencies. */
  protected String getDependencyCorrection() {
    return "";
  }

  /** Partial query to filter out implicit dependencies of genrules. */
  protected String getDependencyCorrectionWithGen() {
    return getDependencyCorrection() + " - deps(" + GENRULE_SETUP + ")";
  }

  protected final void writeFile(String pathName, String... lines) throws IOException {
    helper.writeFile(pathName, lines);
  }

  protected final void overwriteFile(String pathName, String... lines) throws IOException {
    helper.overwriteFile(pathName, lines);
  }

  protected final void overwriteFile(String pathName, ImmutableList<String> lines)
      throws IOException {
    helper.overwriteFile(pathName, lines.toArray(new String[0]));
  }

  protected void assertContainsEvent(String expectedMessage) {
    helper.assertContainsEvent(expectedMessage);
  }

  protected final void assertDoesNotContainEvent(String notExpectedMessage) {
    helper.assertDoesNotContainEvent(notExpectedMessage);
  }

  protected final void ensureSymbolicLink(String link, String target) throws IOException {
    helper.ensureSymbolicLink(link, target);
  }

  protected final void assertStartsWith(String expected, String actual) {
    if (!actual.startsWith(expected)) {
      // Call into ChattyAssertsTestCase to get the nice formatting.
      assertThat(actual).isEqualTo(expected);
    }
  }

  // Evaluate the query, assert that it is successful, and return its results.
  protected Set<T> eval(String query) throws Exception {
    ResultAndTargets<T> result = helper.evaluateQuery(query);
    assertWithMessage(
            "evaluateQuery failed: " + query + "\n" + Iterables.toString(helper.getEvents()))
        .that(result.getQueryEvalResult().getSuccess())
        .isTrue();
    return result.getResultSet();
  }

  // Like eval(), but asserts that evaluation completes abruptly with a QueryException, whose
  // message and FailureDetail is returned.
  protected EvalThrowsResult evalThrows(String query, boolean unconditionallyThrows)
      throws Exception {
    try {
      helper.evaluateQuery(query);
      fail("evaluateQuery completed normally: " + query);
      throw new IllegalStateException();
    } catch (QueryException e) {
      String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
      return new EvalThrowsResult(message, e.getFailureDetail());
    }
  }

  /**
   * Error message and {@link FailureDetail} from the failing query evaluation performed by {@link
   * #evalThrows}.
   */
  protected static class EvalThrowsResult {
    private final String message;
    private final FailureDetail failureDetail;

    protected EvalThrowsResult(String message, FailureDetail failureDetail) {
      this.message = message;
      this.failureDetail = failureDetail;
    }

    public String getMessage() {
      return message;
    }

    public FailureDetail getFailureDetail() {
      return failureDetail;
    }
  }

  // Returns the set as a space-separated list of labels in lex order.
  protected String evalToString(String query) throws Exception {
    return Joiner.on(' ').join(evalToListOfStrings(query));
  }

  protected ImmutableList<String> evalToListOfStrings(String query) throws Exception {
    return resultSetToListOfStrings(eval(query));
  }

  protected ImmutableList<String> resultSetToListOfStrings(Set<T> results) {
    return results.stream()
        .map(node -> helper.getLabel(node))
        .distinct()
        .sorted(Ordering.natural())
        .collect(toImmutableList());
  }

  protected void assertContains(Set<T> x, Set<T> y) throws Exception {
    if (!x.containsAll(y)) {
      fail("x is not a superset of y:\nx = " + x + "\ny = " + y);
    }
  }

  protected void assertNotContains(Set<T> x, Set<T> y) throws Exception {
    assertThat(x.containsAll(y)).isFalse();
  }

  protected static void assertPackageLoadingCode(ResultAndTargets<Target> result, Code code) {
    FailureDetail failureDetail =
        result.getQueryEvalResult().getDetailedExitCode().getFailureDetail();
    assertThat(failureDetail).isNotNull();
    assertPackageLoadingCode(failureDetail, code);
  }

  protected static void assertPackageLoadingCode(FailureDetail failureDetail, Code code) {
    assertThat(failureDetail.getPackageLoading().getCode()).isEqualTo(code);
  }

  protected static void assertQueryCode(FailureDetail failureDetail, Query.Code code) {
    assertThat(failureDetail.getQuery().getCode()).isEqualTo(code);
  }

  @Test
  public void testTargetLiteralWithMissingTargets() throws Exception {
    writeFile("a/BUILD");
    EvalThrowsResult evalThrowsResult = evalThrows("//a:b", false);
    assertThat(evalThrowsResult.getMessage())
        .matches(
            TestUtils.createMissingTargetAssertionString(
                "b", "a", helper.getRootDirectory().getPathString(), ""));
    assertThat(evalThrowsResult.getFailureDetail().getPackageLoading().getCode())
        .isEqualTo(FailureDetails.PackageLoading.Code.TARGET_MISSING);
  }

  protected void writeBuildFiles1() throws Exception {
    // Note, these BUILD files contain no rules, only files, so we use the
    // "a/...:*" wildcard to match them.
    writeFile("a/BUILD", "exports_files(['x', 'y', 'z'])");
    writeFile("a/b/BUILD", "exports_files(['p', 'q'])");
  }

  protected static final String AB_FILES = "//a/b:BUILD //a/b:p //a/b:q";
  protected static final String A_FILES = "//a:BUILD //a:x //a:y //a:z";
  protected static final String A_AB_FILES = AB_FILES + " " + A_FILES;

  @Test
  public void testTargetLiterals() throws Exception {
    writeBuildFiles1();
    assertThat(evalToString("a/b:*")).isEqualTo(AB_FILES);
    assertThat(evalToString("a/...:*")).isEqualTo(A_AB_FILES);
    assertThat(evalToString("a:*")).isEqualTo(A_FILES);
    assertThat(evalToString("//a:x")).isEqualTo("//a:x");
  }

  @Test
  public void testBadTargetLiterals() throws Exception {
    EvalThrowsResult result = evalThrows("bad:*:*", false);
    checkResultofBadTargetLiterals(result.getMessage(), result.getFailureDetail());
  }

  protected final void checkResultofBadTargetLiterals(String message, FailureDetail failureDetail) {
    assertThat(failureDetail.getTargetPatterns().getCode())
        .isEqualTo(TargetPatterns.Code.LABEL_SYNTAX_ERROR);
    assertThat(message).isEqualTo("invalid target name '*:*': target names may not contain ':'");
  }

  @Test
  public void testAlgebraicSetOperations() throws Exception {
    writeBuildFiles1();
    assertThat(evalToString("a/...:* intersect a/b/...:*")).isEqualTo(AB_FILES);
    assertThat(evalToString("a/b/...:* intersect a/...:*")).isEqualTo(AB_FILES);
    assertThat(evalToString("//a:x union a/b/...:*")).isEqualTo(AB_FILES + " //a:x");
    assertThat(evalToString("a/b/...:* union //a:x")).isEqualTo(AB_FILES + " //a:x");
    assertThat(evalToString("a/...:* except a/b/...:*")).isEqualTo(A_FILES);
    assertThat(evalToString("a/b/...:* except a/...:*")).isEmpty();

    assertThat(evalToString("(a/...:* union a/b/...:*) except //a/b:p"))
        .isEqualTo("//a/b:BUILD //a/b:q " + A_FILES);
    assertThat(evalToString("a/...:* union (a/b/...:* except //a/b:p)")).isEqualTo(A_AB_FILES);

    // Test - + ^ variants:
    assertThat(evalToString("a/...:* + (a/b/...:* - //a/b:p)")).isEqualTo(A_AB_FILES);
    assertThat(evalToString("a/...:* ^ a/b/...:*")).isEqualTo(AB_FILES);
  }

  @Test
  public void testAlgebraicSetOperations_manyOperands() throws Exception {
    writeBuildFiles1();
    assertThat(evalToString("//a:BUILD + //a:x + //a:y + //a:z + //a/b:BUILD + //a/b:p + //a/b:q"))
        .isEqualTo(A_AB_FILES);
    assertThat(
            evalToString(
                "a/...:* - //a:BUILD - //a:x - //a:y - //a:z - //a/b:BUILD - //a/b:p - //a/b:q"))
        .isEmpty();
    assertThat(
            evalToString(
                "(//a:x + //a:y) ^ (//a:x + //a:z) ^ (//a:x + //a/b:p) ^ (//a:x + //a/b:q)"))
        .isEqualTo("//a:x");
  }

  private void writeBuildFiles2() throws Exception {
    writeFile(
        "c/BUILD",
        """
        genrule(name='c', srcs=['p', 'q'], outs=['r', 's'], cmd=':')
        cc_binary(name='d', srcs=['e.cc'], data=['r'])
        cc_test(name='f', srcs=['g.cc'])
        """);
  }

  @Test
  public void testKindOperator() throws Exception {
    writeBuildFiles2();
    assertThat(evalToString("c:*"))
        .isEqualTo(
            "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                + " //c:g.cc //c:p //c:q //c:r //c:s");
    assertThat(evalToString("kind(rule, c:*)")).isEqualTo("//c:c //c:d //c:f");
    assertThat(evalToString("kind(genrule, c:*)")).isEqualTo("//c:c");
    assertThat(evalToString("kind(cc.*, c:*)")).isEqualTo("//c:d //c:f");
    assertThat(evalToString("kind(file, c:*)"))
        .isEqualTo(
            "//c:BUILD //c:d.dwp //c:d.stripped //c:e.cc //c:f.dwp //c:f.stripped //c:g.cc //c:p"
                + " //c:q //c:r //c:s");
    assertThat(evalToString("kind(gener.*, c:*)"))
        .isEqualTo("//c:d.dwp //c:d.stripped //c:f.dwp //c:f.stripped //c:r //c:s");
    assertThat(evalToString("kind(gen.*, c:*)"))
        .isEqualTo("//c:c //c:d.dwp //c:d.stripped //c:f.dwp //c:f.stripped //c:r //c:s");
    assertThat(evalToString("kind(source, c:*)"))
        .isEqualTo("//c:BUILD //c:e.cc //c:g.cc //c:p //c:q");
    assertThat(evalToString("kind('source file', c:*)"))
        .isEqualTo("//c:BUILD //c:e.cc //c:g.cc //c:p //c:q");
  }

  @Test
  public void testFilterOperator() throws Exception {
    writeBuildFiles2();
    assertThat(evalToString("c:*"))
        .isEqualTo(
            "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                + " //c:g.cc //c:p //c:q //c:r //c:s");
    assertThat(evalToString("filter(BUILD, c:*)")).isEqualTo("//c:BUILD");
    assertThat(evalToString("filter('\\.cc$', c:*)")).isEqualTo("//c:e.cc //c:g.cc");
    assertThat(evalToString("filter(//c.*cc$, c:*)")).isEqualTo("//c:e.cc //c:g.cc");
    assertThat(evalToString("filter(:.$, c:*)"))
        .isEqualTo("//c:c //c:d //c:f //c:p //c:q //c:r //c:s");
  }

  @Test
  public void testAttrOperatorOnName() throws Exception {
    writeBuildFiles2();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(evalToString("attr(name, '.*', '//c:*')")).isEqualTo("//c:c //c:d //c:f");
    assertThat(evalToString("attr(name, '.+', '//c:*')")).isEqualTo("//c:c //c:d //c:f");
    assertThat(evalToString("attr(name, '.*d.*', '//c:*')")).isEqualTo("//c:d");

    assertThat(evalToString("attr(name, '.*e.*', '//c:*')")).isEmpty();
  }

  @Test
  public void testAttrOperator() throws Exception {
    writeBuildFiles2();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(evalToString("c:*"))
        .isEqualTo(
            "//c:BUILD //c:c //c:d //c:d.dwp //c:d.stripped //c:e.cc //c:f //c:f.dwp //c:f.stripped"
                + " //c:g.cc //c:p //c:q //c:r //c:s");
    assertThat(evalToString("attr(cmd,':', c:*)")).isEqualTo("//c:c");
    // Using "empty" pattern will just check existence of the attribute.
    assertThat(evalToString("attr(cmd,'', c:*)")).isEqualTo("//c:c");
    assertThat(evalToString("attr(linkshared, 0, c:*)")).isEqualTo("//c:d //c:f");
    assertThat(evalToString("attr('data', 'r', c:*)")).isEqualTo("//c:d");
    // Empty list attribute value always resolves to '[]'. If list attribute has
    // more than one value, the will be delimited with ','.
    assertThat(evalToString("attr('deps', '\\[\\]', c:*)")).isEqualTo("//c:d //c:f");
    assertThat(evalToString("attr('deps', '^..$', c:*)")).isEqualTo("//c:d //c:f");
    assertThat(evalToString("attr('srcs', '\\[[^,]+\\]', c:*)")).isEqualTo("//c:d //c:f");

    // Configurable attributes:
    if (testConfigurableAttributes()) {
      assertThat(evalToString("attr('deps', 'bdep', //configurable/...)"))
          .isEqualTo("//configurable:main");
      assertThat(evalToString("attr('deps', 'nomatch', //configurable/...)")).isEmpty();
    }
  }

  /** Regression test for b/16835016: don't crash when evaluating null-valued attributes. */
  @Test
  public void testNullAttrOperator() throws Exception {
    writeBuildFiles2();
    assertThat(evalToString("attr(deprecation, ' ', c:*)")).isEmpty();
  }

  @Test
  public void testAttrOperatorOnBooleans() throws Exception {
    writeFile(
        "t/BUILD",
        """
        cc_library(name='t', srcs=['t.cc'], data=['r'], testonly=0)
        cc_library(name='t_test', srcs=['t.cc'], data=['r'], testonly=1)
        """);

    // Assure that integers query correctly for BOOLEAN values.
    assertThat(evalToString("attr(testonly, 0, t:*)")).isEqualTo("//t:t");
    assertThat(evalToString("attr(testonly, 1, t:*)")).isEqualTo("//t:t_test");
  }

  protected void runGenqueryScopeTest(boolean isPostAnalysisQuery) throws Exception {
    // Tests the relationship between deps(genquery_rule) and that of its scope.
    // For query, deps(genquery_rule) should include transitive deps of its scope
    // For cquery and aquery, deps(genquery_rule) should include its scope, but not its transitive
    // deps.

    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='a')");
    writeFile(
        "b/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='b', deps=['//a:a'])");
    writeFile("q/BUILD", "genquery(name='q', scope=['//b'], expression='deps(//b)')");

    // Assure that deps of a genquery rule includes the transitive closure of its scope.
    // This is required for correctness of incremental "blaze build genqueryrule"
    ImmutableList<String> evalResult = evalToListOfStrings("deps(//q:q)");
    if (isPostAnalysisQuery) {
      // Not checking for equality, since when run as a cquery test, there will be other
      // dependencies.
      assertThat(evalResult).contains("//q:q");
      // assert that transitive closure of scope is NOT present.
      assertThat(evalResult).containsNoneOf("//a:a", "//b:b");
    } else {
      assertThat(evalResult).containsExactly("//q:q", "//a:a", "//b:b");
    }
  }

  @Test
  public void testGenqueryScope() throws Exception {
    runGenqueryScopeTest(false);
  }

  @Test
  public void testAttrOnPackageDefaultVisibility() throws Exception {
    writeFile(
        "t/BUILD",
        """
        package(default_visibility=['//visibility:public'])
        cc_library(name='t', srcs=['t.cc'])
        """);

    assertThat(evalToString("attr(visibility, public, t:*)")).isEqualTo("//t:t");
  }

  @Test
  public void testSomeOperator_noCountParameter() throws Exception {
    writeBuildFiles2();
    assertThat(eval("some(c:*)")).hasSize(1);
    assertContains(eval("c:*"), eval("some(c:*)"));
    assertThat(evalToString("some(//c:q)")).isEqualTo("//c:q");

    EvalThrowsResult result = evalThrows("some(//c:q intersect //c:p)", true);
    assertThat(result.getMessage()).isEqualTo("argument set is empty");
    assertQueryCode(result.getFailureDetail(), Query.Code.ARGUMENTS_MISSING);
  }

  @Test
  public void testSomeOperator_countParameterNotEqualActualCount() throws Exception {
    writeBuildFiles2();
    assertThat(eval("some(//c:p + //c:q, 5)")).hasSize(2);
    assertThat(evalToString("some(//c:p + //c:q, 5)")).isEqualTo("//c:p //c:q");

    assertThat(eval("some(//c:c + //c:d + //c:p + //c:q + //c:r + //c:s, 3)")).hasSize(3);
    // No need to check `evalToString`, the output strings may differ based test suite setup.
  }

  @Test
  public void testSomeOperator_nestedSomeTest() throws Exception {
    writeBuildFiles2();
    assertThat(eval("some(some(//c:p + //c:q, 2) + some(//c:p + //c:s + //c:q, 3), 5)")).hasSize(3);
    assertThat(evalToString("some(some(//c:p + //c:q, 2) + some(//c:p + //c:s + //c:q, 3), 5)"))
        .isEqualTo("//c:p //c:q //c:s");
  }

  protected void writeBuildFiles3() throws Exception {
    writeFile(
        "a/BUILD",
        """
        genrule(name='a', srcs=['//b', '//c'], outs=['out'], cmd=':')
        exports_files(['a2'])
        """);
    writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("d/BUILD", "exports_files(['d'])");
  }

  /**
   * Setup a BUILD file that loads two .scl files, one directly and the other through a .bzl file.
   */
  protected void writeBzlAndSclFiles() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//bar:direct.scl', 'x')
        load('//bar:intermediate.bzl', 'y')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = 'foo',
            tags = [x, y],
        )
        """);
    writeFile("bar/BUILD");
    writeFile(
        "bar/direct.scl", //
        "x = 'X'");
    writeFile(
        "bar/intermediate.bzl",
        """
        load(':indirect.scl', _y='y')
        y = _y
        """);
    writeFile(
        "bar/indirect.scl", //
        "y = 'Y'");
  }

  protected void writeBuildFilesWithConfigurableAttributesUnconditionally() throws Exception {
    writeFile(
        "conditions/BUILD",
        """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        config_setting(
            name = 'b',
            values = {'foo': 'b'})
        """);
    writeFile(
        "configurable/BUILD",
        "cc_binary(",
        "    name = 'main',",
        "    srcs = ['main.cc'],",
        "    deps = select({",
        "        '//conditions:a': [':adep'],",
        "        '//conditions:b': [':bdep'],",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
        "    }))",
        "cc_library(",
        "    name = 'adep',",
        "    srcs = ['adep.cc'])",
        "cc_library(",
        "    name = 'bdep',",
        "    srcs = ['bdep.cc'])",
        "cc_library(",
        "    name = 'defaultdep',",
        "    srcs = ['defaultdep.cc'])");
  }

  private void writeBuildFilesWithConfigurableAttributes() throws Exception {
    if (testConfigurableAttributes()) {
      writeBuildFilesWithConfigurableAttributesUnconditionally();
    }
  }

  @Test
  public void testSomePathOperator() throws Exception {
    writeBuildFiles3();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(eval("somepath(//a, //a:a2)")).isEqualTo(EMPTY); // no path
    assertThat(eval("somepath(//d, //a)")).isEqualTo(EMPTY); // no path

    Set<T> somepathAToD = eval("somepath(//a, //d)");
    assertThat(somepathAToD).containsAtLeastElementsIn(eval("//a"));
    Set<T> aAndB = eval("//a + //b");
    // Contains one of {//b, //c}:
    assertThat(somepathAToD).containsAnyIn(aAndB);
    assertContains(somepathAToD, eval("//d"));

    // Configurable attributes:
    if (testConfigurableAttributes()) {
      assertThat(eval("somepath(//configurable:main, //configurable:bdep.cc)"))
          .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"));
    }
  }

  @Test
  public void testSomePathOperatorOrdering() throws Exception {
    writeFile(
        "a/BUILD",
        """
        genrule(name='a1', srcs=['//b', '//c'], outs=['out1'], cmd=':')
        genrule(name='a0', srcs=[':a1'], outs=['out0'], cmd=':')
        """);
    writeFile("b/BUILD", "genrule(name='b', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("c/BUILD", "genrule(name='c', srcs=['//d'], outs=['out'], cmd=':')");
    writeFile("d/BUILD", "exports_files(['d'])");

    ImmutableList<String> pathList1 = ImmutableList.of("//a:a0", "//a:a1", "//b:b", "//d:d");
    ImmutableList<String> pathList2 = ImmutableList.of("//a:a0", "//a:a1", "//c:c", "//d:d");

    ImmutableList<String> somepathAToD = evalToListOfStrings("somepath(//a:a0, //d)");
    if (somepathAToD.contains("//b:b")) {
      assertThat(pathList1).isEqualTo(somepathAToD);
    } else {
      assertThat(somepathAToD).isEqualTo(pathList2);
    }
  }

  @Test
  public void testAllPathsOperator() throws Exception {
    writeBuildFiles3();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(eval("somepath(//a, //a:a2)")).isEqualTo(EMPTY); // no path
    assertThat(eval("somepath(//d, //a)")).isEqualTo(EMPTY); // no path

    Set<T> allpathsAtoD = eval("allpaths(//a, //d)");
    assertThat(allpathsAtoD).containsAtLeastElementsIn(eval("//a + //b + //c + //d"));

    // Configurable attributes:
    if (testConfigurableAttributes()) {
      assertThat(eval("allpaths(//configurable:main, //configurable:bdep.cc)"))
          .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"));
    }
  }

  @Test
  public void testPathOperatorsWithOutputFile() throws Exception {
    writeFile("a/BUILD", "genrule(name='a', outs=['out'], cmd=':')");

    assertThat(eval("somepath(//a, //a:out)")).isEqualTo(EMPTY); // no path
    assertThat(eval("allpaths(//a, //a:out)")).isEqualTo(EMPTY); // no path

    assertThat(eval("somepath(//a:out, //a)")).isEqualTo(eval("//a + //a:out"));
    assertThat(eval("allpaths(//a:out, //a)")).isEqualTo(eval("//a + //a:out"));
  }

  @Test
  public void testDeps() throws Exception {
    writeBuildFiles3();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(eval("deps(//d)")).isEqualTo(eval("//d"));
    assertThat(eval("deps(//c)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//c union //d"));
    assertThat(eval("deps(//b)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//b union //d"));
    assertThat(eval("deps(//a)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//a union //b union //c union //d"));

    assertThat(eval("deps(//c:out)")).isEqualTo(eval("deps(//c) union //c:out"));
    assertThat(eval("deps(//b:out)")).isEqualTo(eval("deps(//b) union //b:out"));
    assertThat(eval("deps(//a:out)")).isEqualTo(eval("deps(//a) union //a:out"));

    // Test depth-bounded variant:
    assertThat(eval("deps(//a, 0)" + getDependencyCorrectionWithGen())).isEqualTo(eval("//a"));
    assertThat(eval("deps(//a, 1)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//a union //b union //c"));
    assertThat(eval("deps(//a, 2)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//a + //b + //c + //d"));

    // Regression coverage for bug #1561800:
    // "blaze query 'deps(<output file>, 1)' returns the output file,
    // not its generating rule"
    assertThat(eval("deps(//a:out, 0)")).isEqualTo(eval("//a:out"));
    assertThat(eval("deps(//a:out, 1)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//a:out + //a"));
    assertThat(eval("deps(//a:out, 2)" + getDependencyCorrectionWithGen()))
        .isEqualTo(eval("//a:out + //a + //b + //c"));

    // Configurable attributes:
    if (testConfigurableAttributes()) {
      String implicitDeps = "";
      if (analysisMock.isThisBazel()) {
        implicitDeps = " + " + helper.getToolsRepository() + "//tools/def_parser:def_parser";
      }
      String expectedDependencies =
          helper.getToolsRepository()
              + "//tools/cpp:link_extra_lib + "
              + helper.getToolsRepository()
              + "//tools/cpp:malloc + //configurable:main + "
              + "//configurable:main.cc + //configurable:adep + //configurable:bdep + "
              + "//configurable:defaultdep + //conditions:a + //conditions:b "
              + implicitDeps;
      if (includeCppToolchainDependencies()) {
        expectedDependencies += " + //tools/cpp:toolchain_type + //tools/cpp:current_cc_toolchain";
      }
      assertThat(eval("deps(//configurable:main, 1)" + TestConstants.CC_DEPENDENCY_CORRECTION))
          .containsExactlyElementsIn(eval(expectedDependencies));
    }
  }

  protected boolean includeCppToolchainDependencies() {
    return true;
  }

  @Test
  public void testDepsDoesNotIncludeBuildFiles() throws Exception {
    writeFile("deps/BUILD", "exports_files(['build_def', 'starlark.bzl'])");
    writeFile(
        "deps/starlark.bzl",
        """
        def macro():
          native.genrule(name = 'dep2', outs = ['dep2.txt'], cmd = 'echo Hi >$@')
        """);

    writeFile(
        "s/BUILD",
        """
        load('//deps:starlark.bzl', 'macro')
        macro()
        genrule(name = 'my_rule',
                outs = ['my.txt'],
                srcs = [':dep1.txt', ':dep2.txt'],
                cmd = 'echo $(SRCS) >$@')
        """);

    ImmutableList<String> result = evalToListOfStrings("deps(//s:my_rule)");
    assertThat(result).containsAtLeast("//s:dep2", "//s:dep1.txt", "//s:dep2.txt", "//s:my_rule");
    assertThat(result)
        .containsNoneOf("//deps:BUILD", "//deps:build_def", "//deps:starlark.bzl", "//s:BUILD");
  }

  protected void writeAspectDefinition(String aspectAttrs) throws Exception {
    helper.setQuerySettings(Setting.INCLUDE_ASPECTS);
    writeFile(
        "test/aspect.bzl",
        "def _aspect_impl(target, ctx):",
        "   return []",
        "def _rule_impl(ctx):",
        "   return []",
        "",
        "MyAspect = aspect(",
        "   implementation=_aspect_impl,",
        "   attr_aspects=['deps'],",
        "   attrs = ",
        aspectAttrs,
        ")",
        "aspect_rule = rule(",
        "   implementation=_rule_impl,",
        "   attrs = { 'attr' : ",
        "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]),",
        "             'param' : attr.string(),",
        "           },",
        ")",
        "plain_rule = rule(",
        "   implementation=_rule_impl,",
        "   attrs = { 'attr' : ",
        "             attr.label_list(mandatory=False, allow_files=True) ",
        "           },",
        ")");
    writeFile(
        "prod/BUILD",
        """
        load('//test:aspect.bzl', 'plain_rule')
        plain_rule(
             name = 'zzz'
        )
        """);
  }

  @Test
  public void testAspectOnRuleWithoutDeclaredProviders() throws Exception {
    helper.setQuerySettings(Setting.INCLUDE_ASPECTS);
    writeAspectDefinition("{'_extra_deps' : attr.label(default = Label('//test:z'))}");
    writeFile(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        aspect_rule(name='a', attr=[':b'])
        plain_rule(name='b')
        plain_rule(name='z')
        """);

    assertThat(eval("deps(//test:a)")).containsAtLeastElementsIn(eval("//test:b + //test:z"));
  }

  @Test
  public void testQueryStarlarkAspects() throws Exception {
    helper.setQuerySettings(Setting.INCLUDE_ASPECTS);
    writeAspectDefinition("{'_extra_deps' : attr.label(default = Label('//prod:zzz'))}");
    writeFile(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        aspect_rule(
             name = 'qqq',
             attr = ['//test:yyy'],
        )
        """);

    assertThat(eval("deps(//test:xxx)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"));
    assertThat(eval("deps(//test:qqq)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"));
  }

  @Test
  public void testQueryStarlarkAspectWithParameters() throws Exception {
    helper.setQuerySettings(Setting.INCLUDE_ASPECTS);
    writeAspectDefinition(
        "{'_extra_deps' : attr.label(default = Label('//prod:zzz')),"
            + "'param' : attr.string(values=['a', 'b']) }");
    writeFile(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
             param = 'a',
        )
        aspect_rule(
             name = 'qqq',
             attr = ['//test:yyy'],
             param = 'b',
        )
        """);

    assertThat(eval("deps(//test:xxx)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"));
    assertThat(eval("deps(//test:qqq)")).containsAtLeastElementsIn(eval("//prod:zzz + //test:yyy"));
  }

  @Test
  public void testQueryStarlarkAspectsNoImplicitDeps() throws Exception {
    helper.setQuerySettings(Setting.INCLUDE_ASPECTS);
    writeAspectDefinition("{'_extra_deps':attr.label(default = Label('//prod:zzz'))}");
    writeFile(
        "test/BUILD",
        """
        load('//test:aspect.bzl', 'aspect_rule', 'plain_rule')
        plain_rule(
             name = 'yyy',
        )
        aspect_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        """);
    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);

    assertThat(eval("deps(//test:xxx)")).containsNoneIn(eval("//prod:zzz"));
  }

  @Test
  public void testStarlarkDiamondEquality() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//foo:a.bzl', 'A')
        load('//foo:b.bzl', 'B')
        load('//foo:checker.bzl', 'check')
        load('//test_defs:foo_library.bzl', 'foo_library')
        check(A.c, B.c)
        check(B.a, A)
        foo_library(name = 'foo')
        """);
    writeFile(
        "foo/a.bzl",
        """
        load('//foo:c.bzl', 'C')
        A = struct(c = C)
        # comment to make sure this formats properly
        """);
    writeFile(
        "foo/b.bzl",
        """
        load('//foo:a.bzl', 'A')
        load('//foo:c.bzl', 'C')
        B = struct(a = A, c = C)
        """);
    writeFile("foo/c.bzl", "C = struct()");
    writeFile(
        "foo/checker.bzl",
        """
        def check(arg1, arg2):
          if arg1 != arg2:
            fail('Long error message just saying that the two args passed in were not equal')
        """);
    // Check no errors.
    assertThat(evalToString("//foo:foo")).isEqualTo("//foo:foo");
  }

  @Test
  public void testRdeps() throws Exception {
    writeBuildFiles3();
    writeBuildFilesWithConfigurableAttributes();

    assertThat(eval("rdeps(//a, //d)" + getDependencyCorrection()))
        .isEqualTo(eval("//a union //b union //c union //d"));
    assertThat(eval("rdeps(//b, //d)" + getDependencyCorrection()))
        .isEqualTo(eval("//b union //d"));
    assertThat(eval("rdeps(//b union //c, //d)" + getDependencyCorrection()))
        .isEqualTo(eval("//b union //c union //d"));
    assertThat(eval("rdeps(//a union //c, //b)" + getDependencyCorrection()))
        .isEqualTo(eval("//a union //b"));
    assertThat(eval("rdeps(//a:out union //c:out, //b)" + getDependencyCorrection()))
        .isEqualTo(eval("//a union //a:out union //b"));
    assertThat(eval("rdeps(//d, //a)" + getDependencyCorrection())).isEqualTo(EMPTY);

    // Test depth-bounded variant:
    assertThat(eval("rdeps(//a, //d, 1)" + getDependencyCorrection()))
        .isEqualTo(eval("//b union //c union //d"));
    assertThat(eval("rdeps(//a, //d, 0)" + getDependencyCorrection())).isEqualTo(eval("//d"));

    // Configurable attributes:
    if (testConfigurableAttributes()) {
      assertThat(eval("rdeps(//configurable:all, //configurable:adep.cc)"))
          .isEqualTo(eval("//configurable:main + //configurable:adep + //configurable:adep.cc"));
      assertThat(eval("rdeps(//configurable:all, //configurable:bdep.cc)"))
          .isEqualTo(eval("//configurable:main + //configurable:bdep + //configurable:bdep.cc"));
      assertThat(eval("rdeps(//configurable:all, //configurable:defaultdep.cc)"))
          .isEqualTo(
              eval(
                  "//configurable:main + //configurable:defaultdep + "
                      + "//configurable:defaultdep.cc"));
    }
  }

  @Test
  public void testLet() throws Exception {
    writeBuildFiles3();

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    assertContains(
        eval("//b + //c + //d"),
        eval("let x = //a in deps($x) except $x" + getDependencyCorrectionWithGen()));
    EvalThrowsResult result = evalThrows("$undefined", true);
    assertThat(result.getMessage()).isEqualTo("undefined variable 'undefined'");
    assertQueryCode(result.getFailureDetail(), Query.Code.VARIABLE_UNDEFINED);
  }

  @Test
  public void testScopeOfLetExpressions() throws Exception {
    int numTargets = 1000;

    StringBuilder filesBuilder = new StringBuilder("'0'");
    for (int i = 1; i < numTargets; i++) {
      filesBuilder.append(String.format(", '%d'", i));
    }
    String files = filesBuilder.toString();
    writeFile("a/BUILD", "exports_files([" + files + "])");

    StringBuilder letQueryBuilder = new StringBuilder("(let x = //a:0 in $x)");
    for (int i = 1; i < numTargets; i++) {
      letQueryBuilder.append(String.format(" + (let x = //a:%d in $x)", i));
    }
    String letQuery = letQueryBuilder.toString();

    assertThat(eval(letQuery)).containsExactlyElementsIn(eval("//a:* - //a:BUILD"));
  }

  @Test
  public void testSubdirSymlinkCycle() throws Exception {
    writeBuildFiles1();
    helper.ensureSymbolicLink("a/s", "s");
    assertThat(evalToString("a/...:*")).isEqualTo(A_AB_FILES);
  }

  @Test
  public void testCycleInSubpackage() throws Exception {
    writeFile(
        "a/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':dep'])
        foo_library(name = 'dep')
        """);
    writeFile(
        "a/subdir/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'cycletarget', deps = ['cycletarget'])");
    assertThat(evalToListOfStrings("deps(//a:a)")).containsExactly("//a:a", "//a:dep");
  }

  protected void setupCycleInStarlarkParentDir() throws Exception {
    writeFile(
        "a/BUILD",
        """
        load('//a:cycle1.bzl', 'C1')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        """);
    writeFile(
        "a/cycle1.bzl",
        """
        load('//a:cycle2.bzl', 'C2')
        C1 = struct()
        """);
    writeFile(
        "a/cycle2.bzl",
        """
        load('//a:cycle1.bzl', 'C1')
        C2 = struct()
        """);
    writeFile(
        "a/subdir/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'subdir')");
  }

  @Test
  public void testCycleInStarlarkParentDir() throws Exception {
    setupCycleInStarlarkParentDir();
    assertThat(evalToListOfStrings("//a/subdir:all")).containsExactly("//a/subdir:subdir");
  }

  @Test
  public void testNestedLetExpressions() throws Exception {
    writeFile("a/BUILD", "exports_files(['f1', 'f2'])");
    writeFile("b/BUILD", "exports_files(['f1', 'f2'])");
    String letQuery =
        "let x1 = //a:f1 in "
            + "let x2 = //a:f2 in "
            + "let x1 = //b:f1 in "
            + "let x2 = //b:f2 in "
            + "$x1 + $x2";
    assertThat(eval(letQuery)).containsExactlyElementsIn(eval("//b:f1 + //b:f2"));
  }

  @Test
  public void testBuildFiles() throws Exception {
    writeBuildFiles3();
    assertThat(eval("//a ^ //b")).isEqualTo(EMPTY);
    assertThat(eval("buildfiles(//a ^ //b)")).isEqualTo(EMPTY);
    assertThat(eval("buildfiles(//a)")).isEqualTo(eval("//a:BUILD"));
    assertThat(eval("buildfiles(//b)")).isEqualTo(eval("//b:BUILD"));
    assertThat(eval("buildfiles(//a + //b)")).isEqualTo(eval("//a:BUILD + //b:BUILD"));
  }

  @Test
  public void testBuildFilesDoesNotReturnVisibilityOfRule() throws Exception {
    writeFile("fruit/BUILD", "filegroup(name='fruit', visibility=['//fruit/lemon:lemon'])");
    writeFile("fruit/lemon/BUILD", "package_group(name='lemon', packages=['//fruit/...'])");
    assertThat(eval("buildfiles(//fruit:all)")).isEqualTo(eval("//fruit:BUILD"));
  }

  @Test
  public void testBuildFilesDoesNotReturnVisibilityOfBUILD() throws Exception {
    writeFile(
        "fruit/BUILD",
        """
        filegroup(name='fruit', srcs=['fruit.sh'])
        exports_files(['BUILD'], visibility=['//fruit/lemon:lemon'])
        """);
    writeFile("fruit/lemon/BUILD", "package_group(name='lemon', packages=['//fruit/...'])");

    assertThat(eval("buildfiles(//fruit:all)")).isEqualTo(eval("//fruit:BUILD"));
  }

  @Test
  public void testNoImplicitDeps() throws Exception {
    writeFile("x/BUILD", "cc_binary(name='x', srcs=['x.cc'])");

    // Implicit dependencies:
    String hostDepsExpr = helper.getToolsRepository() + "//tools/cpp:malloc";
    hostDepsExpr +=
        " + "
            + helper.getToolsRepository()
            + "//tools/cpp:link_extra_lib"
            + " + "
            + helper.getToolsRepository()
            + "//tools/cpp:linkextra.cc";
    if (!analysisMock.isThisBazel()) {
      hostDepsExpr += " + //tools/cpp:malloc.cc";
    }
    String implicitDepsExpr = "";
    if (analysisMock.isThisBazel()) {
      implicitDepsExpr +=
          " + "
              + helper.getToolsRepository()
              + "//tools/def_parser:def_parser"
              + " + "
              + helper.getToolsRepository()
              + "//tools/def_parser:def_parser.exe";
    }

    String targetDepsExpr = "//x:x + //x:x.cc";
    String toolchainDepsExpr = "//tools/cpp:toolchain_type + //tools/cpp:current_cc_toolchain";

    // Test all combinations of --[no]host_deps and --[no]implicit_deps on //x:x
    String expected = targetDepsExpr + " + " + hostDepsExpr + implicitDepsExpr;
    if (includeCppToolchainDependencies()) {
      expected += " + " + toolchainDepsExpr;
    }
    assertEqualsFiltered(expected, "deps(//x)" + TestConstants.CC_DEPENDENCY_CORRECTION);
    assertEqualsFiltered(
        targetDepsExpr + " + " + hostDepsExpr,
        "deps(//x)" + TestConstants.CC_DEPENDENCY_CORRECTION,
        Setting.ONLY_TARGET_DEPS);
    assertEqualsFiltered(targetDepsExpr, "deps(//x)", Setting.NO_IMPLICIT_DEPS);
    assertEqualsFiltered(
        targetDepsExpr, "deps(//x)", Setting.ONLY_TARGET_DEPS, Setting.NO_IMPLICIT_DEPS);
  }

  protected void assertEqualsFiltered(String expected, String actual, Setting... settings)
      throws Exception {
    helper.setQuerySettings(settings);
    assertThat(eval(actual)).containsExactlyElementsIn(eval(expected));
  }

  private void runNodepDepsTest(boolean expectVisibilityDep, Setting... settings) throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 't1', deps = [':t2'], visibility = [':pg'])
        foo_library(name = 't2')
        package_group(name = 'pg')
        """);

    helper.setQuerySettings(settings);

    if (expectVisibilityDep) {
      assertThat(eval("deps(//foo:t1)")).contains(Iterables.getOnlyElement(eval("//foo:pg")));
    } else {
      assertThat(eval("deps(//foo:t1)")).doesNotContain(Iterables.getOnlyElement(eval("//foo:pg")));
    }
  }

  @Test
  public void testNodepDeps_defaultIsTrue() throws Exception {
    runNodepDepsTest(/* expectVisibilityDep= */ true);
  }

  @Test
  public void testNodepDeps_false() throws Exception {
    runNodepDepsTest(/* expectVisibilityDep= */ false, Setting.NO_NODEP_DEPS);
  }

  @Test
  public void testCycleInStarlark() throws Exception {
    runCycleInStarlarkTest(/* checkFailureDetail= */ true);
  }

  protected void runCycleInStarlarkTest(boolean checkFailureDetail) throws Exception {
    writeFile(
        "a/BUILD",
        """
        load('//a:cycle1.bzl', 'C1')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        """);
    writeFile(
        "a/cycle1.bzl",
        """
        load('//a:cycle2.bzl', 'C2')
        C1 = struct()
        """);
    writeFile(
        "a/cycle2.bzl",
        """
        load('//a:cycle1.bzl', 'C1')
        C2 = struct()
        """);
    EvalThrowsResult result = evalThrows("//a:all", false);
    // TODO(mschaller): evalThrows's message can be non-deterministic if events are too. It probably
    //  needs to be refactored to deal with underlying event non-determinism, because fixing query
    //  engines' event non-determinism is probably hard.
    if (checkFailureDetail) {
      assertThat(result.getFailureDetail().getTargetPatterns().getCode())
          .isEqualTo(TargetPatterns.Code.CYCLE);
    }
  }

  @Test
  public void testLabelsOperator() throws Exception {
    writeBuildFiles3();
    writeBuildFilesWithConfigurableAttributes();
    writeBuildFilesWithImplicitAttribute();

    // srcs:
    assertThat(eval("labels(srcs, //a)")).isEqualTo(eval("//b + //c"));
    assertThat(eval("labels(srcs, //b)")).isEqualTo(eval("//d"));
    assertThat(eval("labels(srcs, //b + //a)")).isEqualTo(eval("//b + //c + //d"));

    // outs:
    assertThat(eval("labels(outs, //a)")).isEqualTo(eval("//a:out"));
    assertThat(eval("labels(outs, //b)")).isEqualTo(eval("//b:out"));
    assertThat(eval("labels(outs, //d)")).isEqualTo(EMPTY); // d is a file

    // empty:
    assertThat(eval("labels(data, //b + //a)")).isEqualTo(EMPTY);

    // no such attribute
    assertThat(eval("labels(no_such_attr, //b)")).isEqualTo(EMPTY);

    // singleton LABEL:
    assertThat(eval("labels(srcs, //k)")).isEqualTo(eval("//k:k.txt"));

    // Works for implicit edges too.  This is for consistency with --output
    // xml, which exposes them too. Note that, for whatever reason, the
    // implicit attribute must be referenced using "$" instead of "_".
    assertThat(eval("labels('$implicit', //k)")).isEqualTo(eval("//k:implicit"));

    // Configurable deps:
    if (testConfigurableAttributes()) {
      assertThat(eval("labels(\"deps\", //configurable:main)"))
          .isEqualTo(eval("//configurable:adep + //configurable:bdep + //configurable:defaultdep"));
    }
  }

  private void writeBuildFilesWithImplicitAttribute() throws Exception {
    writeFile(
        "k/defs.bzl",
        """
        def impl(ctx):
          return [DefaultInfo()]
        has_implicit_attr = rule(
            implementation=impl,
            attrs = {
                'srcs': attr.label_list(),
                '_implicit': attr.label(default='//k:implicit')
            },
        )
        """);
    writeFile(
        "k/BUILD",
        """
        load(':defs.bzl', 'has_implicit_attr')
        has_implicit_attr(name='k', srcs=['k.txt'])
        filegroup(name='implicit')
        """);
  }

  /* tests(x) operator */

  @Test
  public void testTestsOperatorExpandsTestsAndExcludesNonTests() throws Exception {
    writeFile(
        "a/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "test_suite(name='a')",
        "foo_test(name='foo_test', srcs=['foo_test.sh'])",
        "cc_binary(name='cc_binary')");
    assertThat(eval("tests(//a)")).isEqualTo(eval("//a:foo_test"));
  }

  @Test
  public void testTestsOperatorFiltersByTagSizeAndEnv() throws Exception {
    writeFile(
        "b/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "test_suite(name='large_tests', tags=['large'])",
        "test_suite(name='prod_tests', tags=['prod'])",
        "test_suite(name='foo_tests', tags=['foo'])",
        "foo_test(name='large_test', size='large', srcs=['foo_test.sh'])",
        "foo_test(name='prod_test', tags=['prod'], srcs=['py_test.py'])",
        "foo_test(name='foo_test', tags=['foo'])");

    assertThat(eval("tests(//b:large_tests)")).isEqualTo(eval("//b:large_test"));
    assertThat(eval("tests(//b:prod_tests)")).isEqualTo(eval("//b:prod_test"));
    assertThat(eval("tests(//b:foo_tests)")).isEqualTo(eval("//b:foo_test"));
  }

  @Test
  public void testTestsOperatorFiltersByNegativeTag() throws Exception {
    writeFile(
        "b/BUILD",
        getPyLoad("py_test"),
        "test_suite(name='foo_tests', tags=['foo'])",
        "test_suite(name='bar_tests', tags=['bar'])",
        "test_suite(name='foo_notbar_tests', tags=['foo', '-bar'])",
        "py_test(name='py_test', tags=['blah', 'prod'], srcs=['py_test.py'])",
        "cc_test(name='cc_test', tags=['foo'])",
        "cc_test(name='cc_test2', tags=['bar'])",
        "cc_test(name='cc_test3', tags=['foo', 'bar'])");

    assertThat(eval("tests(//b:foo_notbar_tests)")).isEqualTo(eval("//b:cc_test"));
    assertThat(eval("tests(//b:foo_tests)")).isEqualTo(eval("//b:cc_test + //b:cc_test3"));
    assertThat(eval("tests(//b:bar_tests)")).isEqualTo(eval("//b:cc_test2 + //b:cc_test3"));
  }

  @Test
  public void testTestsOperatorCrossesPackages() throws Exception {
    writeFile("c/BUILD", "test_suite(name='c', tests=['//d:suite'])");
    writeFile(
        "d/BUILD",
        """
        load('//test_defs:foo_test.bzl', 'foo_test')
        test_suite(name='suite')
        foo_test(name='foo_test', srcs=['foo_test.sh'])
        """);

    assertThat(eval("tests(//c)")).isEqualTo(eval("//d:foo_test"));
  }

  @Test
  public void testTestsOperatorHandlesCyclesGracefully() throws Exception {
    writeFile("c/BUILD", "test_suite(name='c', tests=['//d'])");
    writeFile("d/BUILD", "test_suite(name='d', tests=['//c'])");

    assertThat(eval("tests(//c)")).isEqualTo(EMPTY); // Doesn't crash or get stuck.
  }

  @Test
  public void testTestSuiteInTestsAttributeAndViceVersa() throws Exception {
    writeFile(
        "cherry/BUILD",
        """
        load('//test_defs:foo_test.bzl', 'foo_test')
        test_suite(name='cherry', tests=[':suite', ':direct'])
        test_suite(name='suite', tests=[':indirect'])
        foo_test(name='direct', srcs=['direct.sh'])
        foo_test(name='indirect', srcs=['indirect.sh'])
        """);

    assertThat(eval("tests(//cherry:cherry)"))
        .isEqualTo(eval("//cherry:direct + //cherry:indirect"));
  }

  @Test
  public void testTestsOperatorReportsMissingTargets() throws Exception {
    writeFile("c/BUILD", "test_suite(name='c', tests=['//d'])");
    writeFile("d/BUILD");

    EvalThrowsResult result = evalThrows("tests(//c)", false);
    assertStartsWith(
        "couldn't expand 'tests' attribute of test_suite //c:c: " + "no such target '//d:d'",
        result.getMessage());
    assertPackageLoadingCode(result.getFailureDetail(), Code.TARGET_MISSING);
  }

  @Test
  public void testDotDotDotWithUnrelatedCycle() throws Exception {
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");
    writeFile(
        "cycle/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'cycle1', deps = ['cycle2'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        """);
    assertThat(eval("//a:a")).isEqualTo(eval("//a/..."));
  }

  @Test
  public void testDotDotDotWithCycle() throws Exception {
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");
    writeFile(
        "a/b/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'cycle1', deps = ['cycle2'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        """);
    assertThat(eval("//a:a + //a/b:cycle1 + //a/b:cycle2")).isEqualTo(eval("//a/..."));
  }

  /* set(x) operator */

  @Test
  public void testSet() throws Exception {
    writeBuildFiles3();

    assertThat(eval("set()")).isEqualTo(EMPTY);
    assertThat(eval("set(\t//a\n//b )")).isEqualTo(eval("//a + //b"));
    assertThat(eval("set(//a //b //c //d)")).isEqualTo(eval("//a + //b + //c + //d"));
  }

  /* Regression tests */

  // Regression test for bug #1153968, "CRASH in query: getTransitiveClosure
  // called without prior call to buildTransitiveClosure".
  @Test
  public void testRuleOutputAmbiguityIsntFatal() throws Exception {
    writeFile("x/BUILD", "genrule(name='x', outs=['x'], cmd='')");
    Set<T> result = eval("allpaths(x:*, //x)"); // doesn't crash
    // result = { genrule(//x) }
    assertThat(result).hasSize(1);
    T r = result.iterator().next();
    assertThat(helper.getLabel(r)).isEqualTo("//x:x");
  }

  // Regression test for bug #2340261:
  // "blaze query doesn't show deps that come from the default_visibility..."
  @Test
  public void testDefaultVisibilityReturnedInDeps() throws Exception {
    writeFile(
        "kiwi/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi')
        """);
    writeFile("mango/BUILD", "package_group(name='mango', packages=[])");

    Set<T> result = eval("deps(//kiwi:kiwi)" + getDependencyCorrection());
    assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi"));
  }

  @Test
  public void testDefaultVisibilityReturnedInDeps_nonEmptyDependencyFilter() throws Exception {
    writeFile(
        "kiwi/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi')
        """);
    writeFile("mango/BUILD", "package_group(name='mango', packages=[])");

    helper.setQuerySettings(Setting.ONLY_TARGET_DEPS);

    Set<T> result = eval("deps(//kiwi:kiwi)" + getDependencyCorrection());
    assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi"));
  }

  @Test
  public void testDefaultVisibilityReturnedInDepsForInputFiles() throws Exception {
    writeFile(
        "kiwi/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package(default_visibility=['//mango:mango'])
        foo_library(name='kiwi', srcs=['kiwi.sh'])
        """);
    writeFile("mango/BUILD", "package_group(name='mango', packages=[])");

    Set<T> result = eval("deps(//kiwi:kiwi.sh)");
    assertThat(result).isEqualTo(eval("//mango:mango + //kiwi:kiwi.sh"));
  }

  // Regression test for bug #2827101:
  // "Package group dependencies are not taken into account by gcheckout"
  @Test
  public void testIncludesReturnedInDeps() throws Exception {
    writeFile(
        "peach/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package_group(name='peach',
                      includes=[':seed'])
        package_group(name='seed',
                      includes=[':cyanide'])
        package_group(name='cyanide',
                      packages=['//hydrogen', '//nitrogen', '//carbon'])
        foo_library(name='dessert',
                   visibility=[':peach'])
        """);

    Set<T> result = eval("deps(//peach:dessert)" + getDependencyCorrection());
    assertThat(result)
        .isEqualTo(eval("//peach:peach + //peach:seed + //peach:cyanide + //peach:dessert"));
  }

  // Regression test for #1267510, modification of result of subexpression
  // evaluation.
  @Test
  public void testRegression1267510() throws Exception {
    writeFile("x/BUILD");
    writeFile("y/BUILD");

    // somepath(x:BUILD, y:BUILD) returns a constant empty set.  "+" should not
    // attempt to modify its LHS operand.
    assertThat(eval("somepath(x:BUILD, y:BUILD) + x:BUILD")).isEqualTo(eval("x:BUILD"));
  }

  // Regression test for #1309697, NPE crash during Blaze query.
  @Test
  public void testRegression1309697() throws Exception {
    writeFile("x/BUILD", "cc_library(name='x', srcs=['a.cc', 'a.cc'])");
    String expectedError = "Label '//x:a.cc' is duplicated in the 'srcs' attribute of rule 'x'";
    if (helper.isKeepGoing()) {
      assertThat(evalThrows("//x:all", false).getMessage()).contains(expectedError);
    } else {
      evalThrows("//x:all", false);
      assertContainsEvent(expectedError);
    }
  }

  // Private helper of testGraphOrderOfWildcards.
  private T one(String label) throws Exception {
    return eval(label).iterator().next();
  }

  private static <T> DotOutputVisitor<T> createVisitor(PrintWriter writer) {
    return new DotOutputVisitor<T>(writer, (Node<T> node) -> node.getLabel().toString());
  }

  @Test
  public void testGraphOrderOfWildcards() throws Exception {
    // TODO(blaze-team): (2009) we could use some helpers for graph order tests.
    writeFile(
        "x/BUILD",
        """
        genrule(name='x', srcs=['y'], outs=['x.out'], cmd=':')
        genrule(name='y', outs=['y.out'], cmd=':')
        """);
    helper.setOrderedResults(true); // This query needs a graph.

    ResultAndTargets<T> resultAndTargets = helper.evaluateQuery("//x:*");
    @SuppressWarnings("unchecked")
    DigraphQueryEvalResult<T> digraphResult =
        (DigraphQueryEvalResult<T>) resultAndTargets.getQueryEvalResult();
    Set<T> results = resultAndTargets.getResultSet();
    Digraph<T> subgraph = digraphResult.getGraph().extractSubgraph(results);

    T xBuild = one("//x:BUILD");
    T xx = one("//x:x");
    T xxout = one("//x:x.out");
    T xy = one("//x:y");
    T xyout = one("//x:y.out");

    assertThat(results).isEqualTo(ImmutableSet.of(xBuild, xx, xxout, xy, xyout));

    Digraph<T> expected = new Digraph<>();
    expected.addEdge(xyout, xy);
    expected.addEdge(xx, xy);
    expected.addEdge(xxout, xx);
    expected.createNode(xBuild);
    if (!expected.equals(subgraph)) {
      // TODO(blaze-team): (2009) make this a utility method of Digraph.
      System.err.println("Expected:");
      expected.visitNodesBeforeEdges(
          AbstractQueryTest.createVisitor(
              new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)))),
          null);
      System.err.println("Was:");
      subgraph.visitNodesBeforeEdges(
          AbstractQueryTest.createVisitor(
              new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)))),
          null);
      fail();
    }
  }

  // Regression test for bug #1345896, "Blaze query p:* loads more packages
  // than just p".
  @Test
  public void testWildcardsDontLoadUnnecessaryPackages() throws Exception {
    writeFile("x/BUILD", "cc_library(name='x', deps=['//y'])");
    writeFile("y/BUILD");

    eval("//x:*");
    helper.assertPackageNotLoaded("y");
  }

  // #1352570, "NPE crash in deps(x, n)".
  @Test
  public void testRegression1352570() throws Exception {
    writeFile(
        "x/BUILD",
        """
        cc_library(name='x', deps=['z'])
        cc_library(name='y', deps=['z'])
        cc_library(name='z')
        """);
    Set<T> result = eval("deps(//x:x + //x:y, 2) intersect //x:*"); // no crash
    assertThat(result).isEqualTo(eval("//x:x + //x:y + //x:z"));
  }

  @Test
  public void testTestSuiteWithFile() throws Exception {
    // Note that test_suite does not restrict the set of targets that can appear here.
    writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])");
    assertThat(eval("tests(//x:a)")).isEmpty();
    assertThat(eval("deps(//x:a)")).isEqualTo(eval("//x:a + //x:a.txt"));
  }

  @Test
  public void testStrictTestSuiteWithFile() throws Exception {
    helper.setQuerySettings(Setting.TESTS_EXPRESSION_STRICT);
    writeFile("x/BUILD", "test_suite(name='a', tests=['a.txt'])");
    EvalThrowsResult result = evalThrows("tests(//x:a)", false);
    assertThat(result.getMessage())
        .isEqualTo(
            "The label '//x:a.txt' in the test_suite '//x:a' does not refer to a test or "
                + "test_suite rule!");
    assertQueryCode(result.getFailureDetail(), Query.Code.INVALID_LABEL_IN_TEST_SUITE);
  }

  @Test
  public void testAmbiguousAllResolvesToTestSuiteNamedAll() throws Exception {
    helper.setQuerySettings(Setting.TESTS_EXPRESSION_STRICT);
    writeFile(
        "x/BUILD",
        """
        cc_test(name='one')
        cc_test(name='two')
        test_suite(name='all', tests=[':one'])
        """);
    assertThat(eval("tests(//x:all)")).isEqualTo(eval("//x:one"));
    // Expect an ambiguity warning in the event handler.
    assertContainsEvent(
        "The target pattern '//x:all' is ambiguous: ':all' is both a wildcard, and "
            + "the name of an existing test_suite rule; using the latter interpretation");
  }

  // Test that long expressions can be parsed and evaluated (without stackoverflow)
  @Test
  public void testBigExpression() throws Exception {
    writeBuildFiles3();

    StringBuilder query = new StringBuilder();
    query.append("//a");
    for (int i = 1; i < 10000; i++) {
      query.append("+ //b");
    }
    assertThat(eval(query.toString())).isEqualTo(eval("//a + //b"));
  }

  @Test
  public void testSlashSlashDotDotDot() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['a.sh'])");
    assertThat(eval("//...")).isEqualTo(eval("//a"));
  }

  @Test
  public void testQueryTimeLoadingOfTargetPatternHappyPath() throws Exception {
    // Given a workspace containing two packages, "//a" and "//a/b",
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");
    writeFile(
        "a/b/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'b')");

    // When the query environment is queried for "//a/b:b" which hasn't been loaded,
    Set<T> queryTimeLoadedPattern = eval("//a/b:b");

    // Then the query evaluates to that target.
    assertThat(queryTimeLoadedPattern).hasSize(1);
  }

  @Test
  public void testQueryTimeLoadingOfTargetsBelowPackageHappyPath() throws Exception {
    // Given a workspace containing three packages, "//a", "//a/b", and "//a/b/c",
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");
    writeFile(
        "a/b/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'b')");
    writeFile(
        "a/b/c/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'c')");

    // When the query environment is queried for "//a/b/..." which hasn't been loaded,
    Set<T> queryTimeLoadedPattern = eval("//a/b/...");

    // Then the query evaluates to the two targets "//a/b:b" and "//a/b/c:c".
    assertThat(queryTimeLoadedPattern).hasSize(2);
  }

  @Test
  public void testQueryTimeLoadingTargetsBelowMissingPackage() throws Exception {
    // Given a workspace containing one package, "//a",
    writeFile(
        "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')");

    // When the query environment is queried for targets belonging to packages beneath the
    // package "a/b", which doesn't exist,
    String missingPackage = "a/b";
    EvalThrowsResult result = evalThrows("//" + missingPackage + "/...", false);
    String s = result.getMessage();

    // Then an exception is thrown that says that the pattern matched nothing.
    assertThat(s).containsMatch("no targets found beneath '" + missingPackage + "'");
    assertThat(result.getFailureDetail().getTargetPatterns().getCode())
        .isEqualTo(TargetPatterns.Code.TARGETS_MISSING);
  }

  @Test
  public void testQueryTimeLoadingTargetsBelowNonPackageDirectory() throws Exception {
    // Given a workspace containing two packages, "//a/b/c", and "//a/b/c/d",
    writeFile(
        "a/b/c/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'c')");
    writeFile(
        "a/b/c/d/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'd')");

    // When the query environment is queried for "//a/b/..." which hasn't been loaded,
    Set<T> queryTimeLoadedPattern = eval("//a/b/...");

    // Then the query evaluates to the two targets "//a/b/c:c" and "//a/b/c/d:d".
    assertThat(queryTimeLoadedPattern).hasSize(2);
  }

  private void useExtendedSetOfRules() throws Exception {
    helper.useRuleClassProvider(
        setRuleClassProviders(
                TestAspects.BASE_RULE,
                TestAspects.ASPECT_REQUIRING_RULE,
                TestAspects.EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER_RULE,
                TestAspects.HONEST_RULE,
                TestAspects.SIMPLE_RULE)
            .build());
  }

  protected void useReducedSetOfRules() throws Exception {
    helper.clearAllFiles();
    helper.useRuleClassProvider(analysisMock.createRuleClassProvider());
    helper.writeFile("embedded_tools/BUILD");
    helper.writeFile("embedded_tools/MODULE.bazel", "module(name='bazel_tools')");
    helper.writeFile("embedded_tools/tools/build_defs/repo/BUILD");
    helper.writeFile(
        "embedded_tools/tools/build_defs/repo/local.bzl",
        """
        def _local_repository_impl(rctx):
          path = rctx.workspace_root.get_child(rctx.attr.path)
          rctx.symlink(path, ".")
        local_repository = repository_rule(
          implementation = _local_repository_impl,
          attrs = {"path": attr.string()},
        )
        """);
    helper.writeFile("platforms_workspace/BUILD");
    helper.writeFile("platforms_workspace/MODULE.bazel", "module(name='platforms')");
    helper.writeFile("local_config_xcode_workspace/BUILD");
    helper.writeFile(
        "local_config_xcode_workspace/MODULE.bazel", "module(name='local_config_xcode')");
    helper.writeFile("rules_java_workspace/BUILD");
    helper.writeFile("rules_java_workspace/MODULE.bazel", "module(name='rules_java')");
    helper.writeFile("rules_python_workspace/BUILD");
    helper.writeFile("rules_python_workspace/MODULE.bazel", "module(name='rules_python')");
    helper.writeFile("rules_python_internal_workspace/BUILD");
    helper.writeFile(
        "rules_python_internal_workspace/MODULE.bazel", "module(name='rules_python_internal')");
    helper.writeFile("bazel_skylib_workspace/BUILD");
    helper.writeFile("bazel_skylib_workspace/MODULE.bazel", "module(name='bazel_skylib')");
    helper.writeFile("third_party/protobuf/BUILD");
    helper.writeFile("third_party/protobuf/MODULE.bazel", "module(name='com_google_protobuf')");
    helper.writeFile("proto_bazel_features_workspace/BUILD");
    helper.writeFile(
        "proto_bazel_features_workspace/MODULE.bazel", "module(name='proto_bazel_features')");
    helper.writeFile("local_config_platform_workspace/BUILD");
    helper.writeFile(
        "local_config_platform_workspace/MODULE.bazel", "module(name='local_config_platform')");
    helper.writeFile("build_bazel_apple_support/BUILD");
    helper.writeFile(
        "build_bazel_apple_support/MODULE.bazel", "module(name='build_bazel_apple_support')");
    helper.writeFile("third_party/bazel_rules/rules_cc/BUILD");
    helper.writeFile("third_party/bazel_rules/rules_cc/MODULE.bazel", "module(name='rules_cc')");
    helper.writeFile("third_party/bazel_rules/rules_shell/BUILD");
    helper.writeFile(
        "third_party/bazel_rules/rules_shell/MODULE.bazel", "module(name='rules_shell')");
  }

  @Test
  public void testHaveDepsOnAspectsAttributes() throws Exception {
    useExtendedSetOfRules();
    writeFile(
        "a/BUILD",
        """
        extra_attribute_aspect_requiring_provider(name='a', foo=[':b'])
        honest(name='b', foo=[])
        """);
    writeFile("extra/BUILD", "honest(name='extra', foo=[])");

    Truth.assertThat(evalToString("deps(//a:a)")).contains("//extra:extra");
  }

  @Test
  public void testNoDepsOnAspectAttributeWhenAspectMissing() throws Exception {
    useExtendedSetOfRules();
    writeFile(
        "a/BUILD",
        """
        aspect(name='a', foo=[':b'])
        honest(name='b', foo=[])
        extra_attribute_aspect_requiring_provider(name='c', foo=[':d'])
        simple(name='d', foo=[])
        """);
    writeFile("extra/BUILD", "honest(name='extra', foo=[])");

    assertThat(evalToString("deps(//a:a)")).doesNotContain("//extra:extra");
    assertThat(evalToString("deps(//a:c)")).doesNotContain("//extra:extra");
  }

  @Test
  public void testNoDepsOnAspectAttributeWithNoImpicitDeps() throws Exception {
    useExtendedSetOfRules();
    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);
    writeFile(
        "a/BUILD",
        """
        extra_attribute_aspect_requiring_provider(name='a', foo=[':b'])
        honest(name='b', foo=[])
        """);
    writeFile("extra/BUILD", "honest(name='extra', foo=[])");

    Truth.assertThat(evalToString("deps(//a:a)")).doesNotContain("//extra:extra");
  }

  public void simpleVisibilityTest(String visibility, boolean expectVisible) throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])");
    writeFile(
        "b/BUILD", "filegroup(name = 'b', srcs = ['b.txt'], visibility = ['" + visibility + "'])");
    String actual = evalToString("visible(//a, somepath(//a, //b))");
    if (expectVisible) {
      assertThat(actual).isEqualTo("//a:a //b:b");
    } else {
      assertThat(actual).isEqualTo("//a:a");
    }
  }

  @Test
  public void testVisible_simple_public() throws Exception {
    simpleVisibilityTest("//visibility:public", true);
  }

  @Test
  public void testVisible_simple_private() throws Exception {
    simpleVisibilityTest("//visibility:private", false);
  }

  @Test
  public void testVisible_simple_package() throws Exception {
    simpleVisibilityTest("//a:__pkg__", true);
  }

  @Test
  public void testVisible_simple_subpackages() throws Exception {
    simpleVisibilityTest("//a:__subpackages__", true);
  }

  @Test
  public void testVisible_simple_different_subpackages() throws Exception {
    simpleVisibilityTest("//c:__subpackages__", false);
  }

  @Test
  public void testVisible_private_same_package() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile(
        "a/BUILD",
        """
        filegroup(name = 'a', srcs = [':b'], visibility = ['//visibility:private'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = ['//visibility:private'])
        """);
    assertThat(evalToString("visible(//a:a, somepath(//a:a, //a:b))")).isEqualTo("//a:a //a:b");
  }

  @Test
  public void testVisible_package_group() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])");
    writeFile(
        "b/BUILD",
        """
        package_group(name = 'friends', packages = ['//a', '//b'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        """);
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b");
  }

  @Test
  public void testVisible_package_group_invisible() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])");
    writeFile(
        "b/BUILD",
        """
        package_group(name = 'friends', packages = ['//c'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        """);
    writeFile("c/BUILD");
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a");
  }

  @Test
  public void testVisible_package_group_include() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])");
    writeFile(
        "b/BUILD",
        """
        package_group(name = 'friends', packages = ['//c'], includes = [':friends_of_friends'])
        package_group(name = 'friends_of_friends', packages = ['//a'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        """);
    writeFile("c/BUILD");
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b");
  }

  @Test
  public void testVisible_java_javatests() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile(
        "java/com/google/a/BUILD",
        "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])");
    writeFile(
        "javatests/com/google/a/BUILD",
        "filegroup(name = 'a', srcs = ['//java/com/google/a:a'],"
            + " visibility = ['//visibility:private'])");
    assertThat(
            evalToString(
                "visible(//javatests/com/google/a,"
                    + " somepath(//javatests/com/google/a, //java/com/google/a))"))
        .isEqualTo("//java/com/google/a:a //javatests/com/google/a:a");
  }

  @Test
  public void testVisible_java_javatests_different_package() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile(
        "java/com/google/a/BUILD",
        "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])");
    writeFile(
        "javatests/com/google/b/BUILD",
        "filegroup(name = 'b', srcs = ['//java/com/google/a:a'],"
            + " visibility = ['//visibility:private'])");
    assertThat(
            evalToString(
                "visible(//javatests/com/google/b,"
                    + " somepath(//javatests/com/google/b, //java/com/google/a))"))
        .isEqualTo("//javatests/com/google/b:b");
  }

  // java cannot see javatests
  @Test
  public void testVisible_javatests_java() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile(
        "javatests/com/google/a/BUILD",
        "filegroup(name = 'a', srcs = ['a.txt'], visibility = ['//visibility:private'])");
    writeFile(
        "java/com/google/a/BUILD",
        "filegroup(name = 'a', srcs = ['//javatests/com/google/a:a'],"
            + " visibility = ['//visibility:private'])");
    assertThat(
            evalToString(
                "visible(//java/com/google/a,"
                    + " somepath(//java/com/google/a, //javatests/com/google/a))"))
        .isEqualTo("//java/com/google/a:a");
  }

  @Test
  public void testVisible_default_private() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b'])");
    writeFile(
        "b/BUILD",
        """
        package(default_visibility = ['//visibility:private'])
        filegroup(name = 'b', srcs = ['b.txt'])
        """);
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a");
  }

  @Test
  public void testVisible_default_public() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b'])");
    writeFile(
        "b/BUILD",
        """
        package(default_visibility = ['//visibility:public'])
        filegroup(name = 'b', srcs = ['b.txt'])
        """);
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b");
  }

  @Test
  public void testPackageGroupAllBeneath() throws Exception {
    useReducedSetOfRules();
    writeFile("MODULE.bazel");
    writeFile("a/BUILD", "filegroup(name = 'a', srcs = ['//b:b'])");
    writeFile(
        "b/BUILD",
        """
        package_group(name = 'friends', packages = ['//a/...'])
        filegroup(name = 'b', srcs = ['b.txt'], visibility = [':friends'])
        """);
    assertThat(evalToString("visible(//a, somepath(//a, //b))")).isEqualTo("//a:a //b:b");
  }

  @Test
  public void testBuildfilesWithDuplicates() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = ['//baz'])
        """);
    writeFile(
        "bar/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'bar', deps = ['//baz'])
        """);
    writeFile(
        "baz/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'baz')
        """);
    writeFile("baz/baz.bzl", "x = 2");
    assertThat(evalToString("buildfiles(deps(//foo)) + buildfiles(deps(//bar))"))
        .isEqualTo(
            "//bar:BUILD //baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD"
                + " //test_defs:foo_library.bzl");
  }

  @Test
  public void bzlPackageBadDueToBrokenLoad() throws Exception {
    writeFile("foo/BUILD", "load('//bar:bar.bzl', 'sym')");
    writeFile("bar/BUILD", "load('//noexist:noexist.bzl', 'bad')");
    writeFile("bar/bar.bzl", "sym = 0");
    assertThat(evalToListOfStrings("buildfiles(//foo:BUILD)"))
        .containsExactly("//foo:BUILD", "//bar:bar.bzl", "//bar:BUILD");
  }

  @Test
  public void bzlPackageBadDueToBrokenSyntax() throws Exception {
    writeFile("foo/BUILD", "load('//bar:bar.bzl', 'sym')");
    writeFile("bar/BUILD", "malformed syntax");
    writeFile("bar/bar.bzl", "sym = 0");
    assertThat(evalToListOfStrings("buildfiles(//foo:BUILD)"))
        .containsExactly("//foo:BUILD", "//bar:bar.bzl", "//bar:BUILD");
  }

  @Test
  public void testBuildfilesContainingScl() throws Exception {
    writeBzlAndSclFiles();

    assertThat(evalToString("buildfiles(deps(//foo))"))
        .isEqualTo(
            "//bar:BUILD //bar:direct.scl //bar:indirect.scl //bar:intermediate.bzl //foo:BUILD"
                + " //test_defs:BUILD //test_defs:foo_library.bzl");
  }

  @Test
  public void badRuleInDeps() throws Exception {
    runBadRuleInDeps(Code.STARLARK_EVAL_ERROR);
  }

  protected final void runBadRuleInDeps(Object code) throws Exception {
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'foo', deps = ['//bar:bar'])");
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar', srcs = 'bad_single_file')");
    EvalThrowsResult evalThrowsResult =
        evalThrows("deps(//foo:foo)", /* unconditionallyThrows= */ false);
    FailureDetail.Builder failureDetailBuilder = FailureDetail.newBuilder();
    if (code instanceof FailureDetails.PackageLoading.Code) {
      failureDetailBuilder.setPackageLoading(
          FailureDetails.PackageLoading.newBuilder().setCode((Code) code));
    } else if (code instanceof Query.Code) {
      failureDetailBuilder.setQuery(FailureDetails.Query.newBuilder().setCode((Query.Code) code));
    }
    assertThat(evalThrowsResult.getFailureDetail())
        .comparingExpectedFieldsOnly()
        .isEqualTo(failureDetailBuilder.build());
  }

  @Test
  public void buildfilesBazel() throws Exception {
    writeFile("bar/BUILD.bazel");
    writeFile("bar/bar.bzl", "sym = 0");
    writeFile("foo/BUILD.bazel", "load('//bar:bar.bzl', 'sym')");
    assertThat(evalToListOfStrings("buildfiles(foo:*)"))
        .containsExactly("//foo:BUILD.bazel", "//bar:bar.bzl", "//bar:BUILD.bazel");
  }

  @Test
  public void testTargetsFromBuildfilesAndRealTargets() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = ['//baz'])
        """);
    writeFile(
        "baz/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        exports_files(['baz.bzl'])
        foo_library(name = 'baz')
        """);
    writeFile("baz/baz.bzl", "x = 2");
    assertThat(evalToString("buildfiles(deps(//foo)) + //baz:BUILD + //baz:baz.bzl"))
        .isEqualTo(
            "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl");
    assertThat(evalToString("buildfiles(deps(//foo)) ^ //baz:BUILD")).isEqualTo("//baz:BUILD");
    assertThat(evalToString("buildfiles(deps(//foo)) ^ //baz:baz.bzl")).isEqualTo("//baz:baz.bzl");
  }

  @Test
  public void testBuildfilesOfBuildfiles() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//baz:baz.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo')
        """);
    writeFile("baz/BUILD", "load('//bar:bar.bzl', 'x')");
    writeFile("baz/baz.bzl", "x = 1");
    writeFile("bar/BUILD");
    writeFile("bar/bar.bzl", "x = 2");
    assertThat(evalToString("buildfiles(//foo)"))
        .isEqualTo(
            "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl");
    assertThat(evalToString("buildfiles(buildfiles(//foo))"))
        .isEqualTo(
            "//baz:BUILD //baz:baz.bzl //foo:BUILD //test_defs:BUILD //test_defs:foo_library.bzl");
  }

  @Test
  public void testBoundedDepsStreaming() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        """);
    assertThat(evalToString("deps(//foo:a + //foo:b, 1)" + getDependencyCorrection()))
        .isEqualTo("//foo:a //foo:b //foo:c");
  }

  @Test
  public void testBoundedRdepsStreaming() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        """);
    assertThat(evalToString("rdeps(//foo:a, //foo:d + //foo:c, 1)" + getDependencyCorrection()))
        .isEqualTo("//foo:b //foo:c //foo:d");
  }

  @Test
  public void boundedDepsWithError() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = [':dep'])
        foo_library(name = 'dep', deps = ['//bar:missing'])
        """);
    assertThat(evalToListOfStrings("deps(//foo:foo, 1)")).containsExactly("//foo:foo", "//foo:dep");
  }

  // Ideally we wouldn't fail on an irrelevant error (since //bar:missing is a dep of //foo:dep,
  // not an rdep). This test documents the current non-ideal behavior.
  @Test
  public void boundedRdepsWithError() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', deps = [':dep'])
        foo_library(name = 'dep', deps = ['//bar:missing'])
        """);
    assertThat(
            evalThrows("rdeps(//foo:foo, //foo:dep, 1)", /* unconditionallyThrows= */ false)
                .getMessage())
        .contains("preloading transitive closure failed: no such package 'bar':");
  }

  @Test
  public void testEqualityOfOrderedThreadSafeImmutableSet() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        """);

    Set<T> targets = eval("//foo:a + //foo:b");
    QueryEnvironment<T> env = helper.getQueryEnvironment();
    ThreadSafeMutableSet<T> mutableSet = env.createThreadSafeMutableSet();
    mutableSet.addAll(targets);
    assertThat(targets).isEqualTo(mutableSet);
  }

  @Test
  public void testSiblings_simple() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        foo_library(name = 'c')
        foo_library(name = 'd')
        """);
    assertThat(evalToString("siblings(//foo:a)"))
        .isEqualTo("//foo:BUILD //foo:a //foo:b //foo:c //foo:d");
  }

  @Test
  public void testSiblings_duplicatePackages() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a')
        foo_library(name = 'b')
        foo_library(name = 'c')
        foo_library(name = 'd')
        """);
    assertThat(evalToString("siblings(//foo:a + //foo:b + //foo:c + //foo:d)"))
        .isEqualTo("//foo:BUILD //foo:a //foo:b //foo:c //foo:d");
  }

  @Test
  public void testSiblings_samePackageRdeps() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c', ':d'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        """);
    writeFile(
        "bar/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'e', deps = ['//foo:d'])
        foo_library(name = 'f', deps = ['//foo:d'])
        """);
    assertThat(evalToString("rdeps(//foo:* + //bar:*, //foo:d, 1)"))
        .isEqualTo("//bar:e //bar:f //foo:b //foo:c //foo:d");
    assertThat(evalToString("rdeps(siblings(//foo:d), //foo:d, 1)"))
        .isEqualTo("//foo:b //foo:c //foo:d");
    // 'same_pkg_direct_rdeps(//foo:d)' is supposed to have the same semantics as
    // 'rdeps(siblings(//foo:d), //foo:d, 1) - //foo:d'
    assertThat(evalToString("same_pkg_direct_rdeps(//foo:d)")).isEqualTo("//foo:b //foo:c");
  }

  @Test
  public void testSiblings_matchesTargetNamedAll() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        # NOTE: target named 'all' collides with, takes precedence over the ':all' wildcard
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'all')
        foo_library(name = 'ball')
        foo_library(name = 'call')
        foo_library(name = 'doll')
        """);
    assertThat(evalToString("//foo:all")).isEqualTo("//foo:all");
    assertThat(evalToString("kind(' rule', siblings(//foo:BUILD))"))
        .isEqualTo("//foo:all //foo:ball //foo:call //foo:doll");
  }

  // Explicit test for the interaction of 'siblings' on operands coming from 'buildfiles' or
  // 'loadfiles'. The behavior here of treating a load'd .bzl file as coming from the package
  // loading it, rather than the package to which it belongs, is unfortunate, but it's the only
  // thing blaze can do with the unfortunate implementation details of 'buildfiles' and 'loadfiles'
  // (see FakeLoadTarget and other tests dealing with these functions).
  @Test
  public void testSiblings_withBuildfiles() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//bar:bar.bzl', 'x')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo')
        """);
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar')");
    writeFile("bar/bar.bzl", "x = 42");
    assertThat(evalToString("siblings(buildfiles(//foo:foo))")).isEqualTo("//foo:BUILD //foo:foo");
  }

  @Test
  public void testSamePackageRdeps_simple() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        """);
    assertThat(evalToString("same_pkg_direct_rdeps(//foo:A.java)")).isEqualTo("//foo:a");
  }

  @Test
  public void testSamePackageRdeps_duplicate() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        """);
    assertThat(evalToString("same_pkg_direct_rdeps(//foo:A.java + //foo:A.java)"))
        .isEqualTo("//foo:a");
  }

  @Test
  public void testSamePackageRdeps_two() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':c', ':d'])
        foo_library(name = 'c', deps = [':d'])
        foo_library(name = 'd')
        """);
    writeFile(
        "bar/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'e', deps = ['//foo:d'])
        foo_library(name = 'f', deps = ['//foo:d'])
        """);
    assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:d))"))
        .isEqualTo("//foo:b //foo:c");
  }

  @Test
  public void testSamePackageRdeps_twoPackages() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = [':a'])
        foo_library(name = 'c', srcs = ['C.java'], deps = [':b'])
        """);
    // //bar:d directly depends on //foo:a but is in the wrong package
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'd', srcs = ['D.java'], deps = ['//foo:a'])");
    assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:a))")).isEqualTo("//foo:b");
  }

  @Test
  public void testSamePackageRdeps_crissCross() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = ['//bar:a'])
        """);
    writeFile(
        "bar/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', srcs = ['A.java'])
        foo_library(name = 'b', srcs = ['B.java'], deps = ['//foo:a'])
        """);
    assertThat(evalToString("kind(rule, same_pkg_direct_rdeps(//foo:a + //bar:a))")).isEmpty();
  }

  @Test
  public void testVisibleWithNonPackageGroupVisibility() throws Exception {
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'foo', visibility = ['//bar:bar'])");
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar')");
    assertThat(evalToString("visible(//bar:bar, //foo:foo)")).isEmpty();
  }

  @Test
  public void testVisibleWithPackageGroupWithNonPackageGroupIncludes() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo', visibility = [':pg'])
        package_group(name = 'pg', includes = ['//bar:bar'])
        """);
    writeFile(
        "bar/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'bar')");
    assertThat(evalToString("visible(//bar:bar, //foo:foo)")).isEmpty();
  }

  @Test
  public void testDeepNestedLet() throws Exception {
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'foo')");

    // We used to get a StackOverflowError at this depth. We're still vulnerable to stack overflows
    // at higher depths, due to how the query engine works.
    int nestingDepth = 500;
    String queryString =
        Joiner.on(" + ").join(Collections.nCopies(nestingDepth, "let x = //foo:foo in $x"));

    assertThat(evalToString(queryString)).isEqualTo("//foo:foo");
  }

  @Test
  public void testUnsuccessfulInnerFutureInNestedLetTransformAsyncFastPath() throws Exception {
    // Not actually needed for the behavior being tested, but needed for the cquery and aquery test
    // subclasses that infer and load a universe.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'foo')");
    EvalThrowsResult result =
        evalThrows("let x = let y = //foo in $nope in $x", /* unconditionallyThrows= */ true);
    assertThat(result.getMessage()).contains("undefined variable 'nope'");
    assertThat(result.getMessage()).doesNotContain("java.lang.IllegalStateException");
    assertQueryCode(result.getFailureDetail(), Query.Code.VARIABLE_UNDEFINED);
  }

  @Test
  public void testUnconditionalQueryException() throws Exception {
    // The query expression being evaluated needs to be of the form "e1 + e2", where evaluation of
    // "e1" throws a QueryException even in keepGoing mode. See cl/141772584.
    writeFile(
        "foo/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'foo')");
    EvalThrowsResult result =
        evalThrows("some(//foo - //foo) + //foo", /* unconditionallyThrows= */ true);
    assertThat(result.getMessage()).isEqualTo("argument set is empty");
    assertQueryCode(result.getFailureDetail(), Query.Code.ARGUMENTS_MISSING);
  }

  @Test
  public void testNoImplicitDeps_computedDefault() throws Exception {
    // This rule cannot be defined in Starlark because the latter requires attributes with a
    // computed default to be private.
    MockRule computedDefaultRule =
        () ->
            MockRule.define(
                "computed_default_rule",
                attr("use_default", Type.BOOLEAN),
                attr("dep", BuildType.LABEL)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .value(
                        new Attribute.ComputedDefault("use_default") {
                          @Override
                          public Object getDefault(AttributeMap rule) {
                            return rule.get("use_default", Type.BOOLEAN)
                                ? Label.parseCanonicalUnchecked("//x:default")
                                : null;
                          }
                        }));

    helper.useRuleClassProvider(setRuleClassProviders(computedDefaultRule).build());

    writeFile(
        "x/BUILD",
        """
        computed_default_rule(name='x1')
        computed_default_rule(name='x2', use_default = True)
        computed_default_rule(name='x3', dep = ':custom')
        computed_default_rule(name='x4', dep = ':custom', use_default = True)
        computed_default_rule(name='x5', dep = '//x:default')
        computed_default_rule(name='x6', dep = '//x:default', use_default = True)
        cc_binary(name='default')
        cc_binary(name='custom')
        """);

    assertDependsNotFiltered("//x:x1", "//x:default");
    assertDependsFiltered("//x:x2", "//x:default");
    assertDependsFiltered("//x:x3", "//x:custom");
    assertDependsFiltered("//x:x4", "//x:custom");
    assertDependsFiltered("//x:x5", "//x:default");
    assertDependsFiltered("//x:x6", "//x:default");

    assertDependsNotFiltered("//x:x1", "//x:default", Setting.NO_IMPLICIT_DEPS);
    assertDependsNotFiltered("//x:x2", "//x:default", Setting.NO_IMPLICIT_DEPS);
    assertDependsFiltered("//x:x3", "//x:custom", Setting.NO_IMPLICIT_DEPS);
    assertDependsFiltered("//x:x4", "//x:custom", Setting.NO_IMPLICIT_DEPS);
    assertDependsFiltered("//x:x5", "//x:default", Setting.NO_IMPLICIT_DEPS);
    assertDependsFiltered("//x:x6", "//x:default", Setting.NO_IMPLICIT_DEPS);
  }

  private void assertDependsNotFiltered(String from, String to, Setting... settings)
      throws Exception {
    String fromDeps = "deps(" + from + ")";
    assertEqualsFiltered(fromDeps, fromDeps + '-' + to, settings);
  }

  private void assertDependsFiltered(String from, String to, Setting... settings) throws Exception {
    String fromDeps = "deps(" + from + ")";
    assertEqualsFiltered(to, fromDeps + '^' + to, settings);
  }

  protected void writeBzlmodBuildFiles() throws Exception {
    helper.overwriteFile(
        "MODULE.bazel", "bazel_dep(name= 'repo', version='1.0', repo_name='my_repo')");
    helper.overwriteFile(
        "BUILD",
        "load('//test_defs:foo_binary.bzl', 'foo_binary')",
        "foo_binary(",
        "name='rinne',",
        "srcs=['rinne.sh'],",
        "deps=['@my_repo//a:x','@my_repo//a/b:p']",
        ")");
    helper.addModule(
        new ModuleKey("repo", Version.parse("1.0")), "module(name = 'repo', version = '1.0')");
    writeFile(helper.getModuleRoot().getRelative("repo+1.0/REPO.bazel").getPathString(), "");
    writeFile(
        helper.getModuleRoot().getRelative("repo+1.0/a/BUILD").getPathString(),
        "exports_files(['x', 'y', 'z'])",
        "filegroup(name = 'a_shar')");
    writeFile(
        helper.getModuleRoot().getRelative("repo+1.0/a/b/BUILD").getPathString(),
        "exports_files(['p', 'q'])",
        "filegroup(name = 'a_b_shar')");
    RepositoryMapping mapping =
        RepositoryMapping.create(
            ImmutableMap.of("my_repo", RepositoryName.create("repo+")), RepositoryName.MAIN);
    helper.setMainRepoTargetParser(mapping);
  }

  protected static final String REPO_A_RULES = "@@repo+//a:a_shar";
  protected static final String REPO_AB_RULES = "@@repo+//a/b:a_b_shar";
  protected static final String REPO_AB_ALL =
      "@@repo+//a/b:BUILD @@repo+//a/b:a_b_shar @@repo+//a/b:p @@repo+//a/b:q";
  protected static final String REPO_A_ALL =
      "@@repo+//a:BUILD @@repo+//a:a_shar @@repo+//a:x @@repo+//a:y @@repo+//a:z";
  protected static final String REPO_A_AB_RULES = REPO_AB_RULES + " " + REPO_A_RULES;
  protected static final String REPO_A_AB_ALL = REPO_AB_ALL + " " + REPO_A_ALL;

  @Test
  public void testExternalRepo_allTargetsInPackage() throws Exception {
    writeBzlmodBuildFiles();
    assertThat(evalToString("@my_repo//a/b:*")).isEqualTo(REPO_AB_ALL);
    assertThat(evalToString("@my_repo//a:*")).isEqualTo(REPO_A_ALL);
  }

  @Test
  public void testExternalRepo_allTargetsBelow() throws Exception {
    writeBzlmodBuildFiles();
    assertThat(evalToString("@my_repo//...:*")).isEqualTo(REPO_A_AB_ALL);
    assertThat(evalToString("@my_repo//a/...")).isEqualTo(REPO_A_AB_RULES);
    assertThat(evalToString("@my_repo//a/b/...")).isEqualTo(REPO_AB_RULES);
  }

  @Test
  public void testLabelFlagDefaultAppearsInDepsQuery() throws Exception {
    writeFile(
        "donut/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'thief', srcs = ['thief.sh'])
        label_flag(name = 'myflag', build_setting_default = ':thief')
        """);

    assertThat(evalToString("deps(//donut:myflag, 1)" + getDependencyCorrectionWithGen()))
        .isEqualTo("//donut:myflag //donut:thief");
  }

  @Test
  public void testLabelSettingDefaultAppearsInDepsQuery() throws Exception {
    writeFile(
        "donut/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'thief', srcs = ['thief.sh'])
        label_setting(name = 'mysetting', build_setting_default = ':thief')
        """);

    assertThat(evalToString("deps(//donut:mysetting, 1)" + getDependencyCorrectionWithGen()))
        .isEqualTo("//donut:mysetting //donut:thief");
  }

  @Test
  public void testStarlarkRuleToolchainDeps() throws Exception {
    overwriteFile("MODULE.bazel", "register_toolchains('//bar:all')");
    writeFile(
        "foo/BUILD",
        """
        load(":foo.bzl", "my_rule")

        my_rule(name = "foo")
        """);
    writeFile(
        "foo/foo.bzl",
        """
        def noop(ctx):
          pass

        my_rule = rule(
          implementation = noop,
          toolchains = ['//bar:bar_type'],
        )
        """);
    writeFile(
        "bar/BUILD",
        """
        load(":bar.bzl", "test_toolchain")

        toolchain_type(name = "bar_type")
        toolchain_type(name = "other_type")
        test_toolchain(name = "bar_impl")
        test_toolchain(name = "other_impl")

        toolchain(
            name = "bar_toolchain",
            toolchain = ":bar_impl",
            toolchain_type = ":bar_type",
        )

        toolchain(
            name = "other_toolchain",
            toolchain = ":other_impl",
            toolchain_type = ":other_type",
        )
        """);
    writeFile(
        "bar/bar.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo()
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
        )
        """);

    // Use contains (instead of matching full string) because post-analysis query implementation
    // will contain resolved toolchain, whereas pre-analysis query will not.
    assertThat(evalToString("deps(//foo, 1)")).contains("//bar:bar_type");
    // Test unbounded deps, too.
    assertThat(evalToString("deps(//foo)")).contains("//bar:bar_type");

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);

    assertThat(evalToString("deps(//foo, 1)")).doesNotContain("//bar:bar_type");
    assertThat(evalToString("deps(//foo)")).doesNotContain("//bar:bar_type");
  }

  @Test
  public void testNativeRuleToolchainDeps() throws Exception {
    writeFile(
        "foo/BUILD",
        """
        cc_library(name = "cclib")
        """);

    assertThat(evalToString("deps(//foo:cclib)")).contains("//tools/cpp:toolchain_type");

    helper.setQuerySettings(Setting.NO_IMPLICIT_DEPS);

    assertThat(evalToString("deps(//foo:cclib)")).doesNotContain("//tools/cpp:toolchain_type");
  }

  /**
   * A helper interface that allows creating a bunch of BUILD files and running queries against
   * them. We use this rather than the existing FoundationTestCase / BuildTestCase infrastructure to
   * allow running the same test against multiple query implementations (like the deps server).
   */
  public interface QueryHelper<T> {

    /** Basic set-up; this is called once at the beginning of a test, before anything else. */
    void setUp() throws Exception;

    void cleanUp();

    void setKeepGoing(boolean keepGoing);

    boolean isKeepGoing();

    void setOrderedResults(boolean orderedResults);

    void setUniverseScope(String universeScope);

    default boolean reportsUniverseEvaluationErrors() {
      return true;
    }

    /** Re-initializes the query environment with the given settings. */
    void setQuerySettings(Setting... settings);

    Path getRootDirectory();

    PathFragment getIgnoredSubdirectoriesFile();

    /** Removes all files below the package root. */
    void clearAllFiles() throws IOException;

    /** Changes the rule class provider to be used for the query evaluation. */
    void useRuleClassProvider(ConfiguredRuleClassProvider ruleClassProvider) throws Exception;

    /**
     * Create a scratch file in the given filesystem, with the given pathName, consisting of a set
     * of lines. The method returns a Path instance for the scratch file.
     */
    void writeFile(String fileName, String... lines) throws IOException;

    /** Like {@code writeFile}, but the file is written unconditionally. */
    void overwriteFile(String fileName, String... lines) throws IOException;

    /**
     * Create a symbolic link in the given filesystem from {@code link} that points to {@code
     * target}.
     */
    void ensureSymbolicLink(String link, String target) throws IOException;

    /** Return an instance of {@link QueryEnvironment} according to set-up rules. */
    QueryEnvironment<T> getQueryEnvironment();

    /** Evaluates the given query and returns the result. Query is expected to have valid syntax. */
    ResultAndTargets<T> evaluateQuery(String query) throws Exception;

    default Set<T> evaluateQueryRaw(String query) throws Exception {
      return evaluateQuery(query).results;
    }

    default RepositoryName getToolsRepository() {
      return RepositoryName.MAIN;
    }

    /**
     * Contains both the results of the query (Like if there were errors, empty result, etc.) and
     * the actual targets returned by the query.
     */
    class ResultAndTargets<T> {

      private final QueryEvalResult queryEvalResult;
      private final Set<T> results;

      public ResultAndTargets(QueryEvalResult queryEvalResult, Set<T> results) {
        this.queryEvalResult = queryEvalResult;
        this.results = results;
      }

      public QueryEvalResult getQueryEvalResult() {
        return queryEvalResult;
      }

      public Set<T> getResultSet() {
        return results;
      }
    }

    /**
     * Clears the event storage that is used for {@link #assertContainsEvent} and {@link
     * #getFirstEvent}.
     */
    void clearEvents();

    /** Asserts that the event storage contains an event with the given message text. */
    void assertContainsEvent(String expectedMessage);

    /** Asserts that the event storage does not contain an event with the given message text. */
    void assertDoesNotContainEvent(String notExpectedMessage);

    /** Returns the message text for the first event in the event storage. */
    String getFirstEvent();

    Iterable<Event> getEvents();

    /**
     * If this implementation is backed by a package cache, this asserts that the given package is
     * not present in the cache.
     */
    void assertPackageNotLoaded(String packageName) throws Exception;

    String getLabel(T target);

    void addModule(ModuleKey key, String... moduleFileLines);

    Path getModuleRoot();

    void setMainRepoTargetParser(RepositoryMapping mapping);

    void maybeHandleDiffs() throws AbruptExitException, InterruptedException;
  }
}
