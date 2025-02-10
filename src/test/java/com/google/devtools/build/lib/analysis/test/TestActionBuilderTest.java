// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.RunfilesTreeAction;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link com.google.devtools.build.lib.analysis.test.TestActionBuilder} tests. */
@RunWith(JUnit4.class)
public class TestActionBuilderTest extends BuildViewTestCase {

  @Before
  public final void createBuildFile() throws Exception {
    analysisMock.pySupport().setup(mockToolsConfig);

    scratch.file(
        "tests/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        getPyLoad("py_binary"),
        getPyLoad("py_test"),
        "py_test(name = 'small_test_1',",
        "        srcs = ['small_test_1.py'],",
        "        data = [':xUnit'],",
        "        size = 'small',",
        "        tags = ['tag1'])",
        "",
        "foo_test(name = 'small_test_2',",
        "        srcs = ['small_test_2.sh'],",
        "        size = 'small',",
        "        tags = ['tag2'])",
        "",
        "foo_test(name = 'large_test_1',",
        "        srcs = ['large_test_1.sh'],",
        "        data = [':xUnit'],",
        "        size = 'large',",
        "        tags = ['tag1'])",
        "",
        "py_binary(name = 'notest',",
        "        srcs = ['notest.py'])",
        "cc_library(name = 'xUnit')",
        "",
        "test_suite(name = 'smallTests', tags=['small'])");
  }

  private void assertSharded(ConfiguredTarget testRule, int expectSharding) {
    ImmutableList<Artifact.DerivedArtifact> testStatusList = getTestStatusArtifacts(testRule);
    if (expectSharding == 0) {
      Artifact testResult = Iterables.getOnlyElement(testStatusList);
      TestRunnerAction action = (TestRunnerAction) getGeneratingAction(testResult);
      assertThat(action.isSharded()).isFalse();
      assertThat(action.getExecutionSettings().getTotalShards()).isSameInstanceAs(0);
      assertThat(action.getShardNum()).isSameInstanceAs(0);
      return;
    }

    int totalShards = testStatusList.size();
    Set<Integer> shardNumbers = new HashSet<>();
    for (Artifact testResult : testStatusList) {
      TestRunnerAction action = (TestRunnerAction) getGeneratingAction(testResult);
      assertThat(action.isSharded()).isTrue();
      assertThat(action.getExecutionSettings().getTotalShards()).isSameInstanceAs(totalShards);
      assertThat(action.getTestLog().getExecPath().getPathString())
          .endsWith(
              String.format("shard_%d_of_%d/test.log", action.getShardNum() + 1, totalShards));
      shardNumbers.add(action.getShardNum());
    }
    assertThat(shardNumbers).isEqualTo(sequenceSet(0, totalShards));
    assertThat(shardNumbers).hasSize(expectSharding);
  }

  private static Set<Integer> sequenceSet(int start, int end) {
    Preconditions.checkArgument(end > start);
    Set<Integer> seqSet = new HashSet<>();
    for (int i = start; i < end; i++) {
      seqSet.add(i);
    }
    return seqSet;
  }

  private void writeJavaTests() throws IOException {
    scratch.file(
        "javatests/jt/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "RGT",
            srcs = ["RGT.java"],
        )

        java_test(
            name = "RGT_none",
            srcs = ["RGT.java"],
            shard_count = 0,
        )

        java_test(
            name = "RGT_many",
            srcs = ["RGT.java"],
            shard_count = 33,
        )

        java_test(
            name = "RGT_small",
            size = "small",
            srcs = ["RGT.java"],
        )

        java_test(
            name = "NoRunner",
            srcs = ["NoTestRunnerTest.java"],
            main_class = "NoTestRunnerTest.java",
            use_testrunner = 0,
        )
        """);
  }

  private ImmutableList<Map<PathFragment, Artifact>> getShardRunfilesMappings(String label)
      throws Exception {
    return getTestStatusArtifacts(label).stream()
        .map(this::getGeneratingAction)
        .map(a -> ((TestRunnerAction) a).getRunfilesTree())
        .map(this::getGeneratingAction)
        .map(a -> ((RunfilesTreeAction) a).getRunfilesTree())
        .map(RunfilesTree::getMapping)
        .collect(toImmutableList());
  }

  @Test
  public void testRunfilesMappingCached() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "sh",
            srcs = ["a.sh"],
            shard_count = 2,
        )

        java_test(
            name = "java",
            srcs = ["Java.java"],
            shard_count = 2,
        )
        """);

    ImmutableList<Map<PathFragment, Artifact>> shMappings = getShardRunfilesMappings("//a:sh");
    assertThat(shMappings).hasSize(2);
    assertThat(shMappings.get(0)).isSameInstanceAs(shMappings.get(1));

    ImmutableList<Map<PathFragment, Artifact>> javaMappings = getShardRunfilesMappings("//a:java");
    assertThat(javaMappings).hasSize(2);
    assertThat(javaMappings.get(0)).isSameInstanceAs(javaMappings.get(1));
  }

  @Test
  public void testSharding() throws Exception {
    useConfiguration("--test_sharding_strategy=explicit");

    assertSharded(getConfiguredTarget("//tests:small_test_1"), 0);
    assertSharded(getConfiguredTarget("//tests:large_test_1"), 0);

    writeJavaTests();
    assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 0);

    // Has an explicit "shard_count" attribute.
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 33);
  }

  @Test
  public void testShardingDisabled() throws Exception {
    useConfiguration("--test_sharding_strategy=disabled");

    assertSharded(getConfiguredTarget("//tests:small_test_1"), 0);
    assertSharded(getConfiguredTarget("//tests:large_test_1"), 0);

    writeJavaTests();
    assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 0);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 0);

    // Has an explicit "shard_count" attribute.
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 0);
  }

  @Test
  public void testShardingForced() throws Exception {
    useConfiguration("--test_sharding_strategy=forced=5");

    assertSharded(getConfiguredTarget("//tests:small_test_1"), 5);
    assertSharded(getConfiguredTarget("//tests:large_test_1"), 5);

    writeJavaTests();
    assertSharded(getConfiguredTarget("//javatests/jt:NoRunner"), 5);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT"), 5);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_small"), 5);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_none"), 5);
    assertSharded(getConfiguredTarget("//javatests/jt:RGT_many"), 5);
  }

  @Test
  public void testShardingForced_equalValue_equalChecksum() throws Exception {
    useConfiguration("--test_sharding_strategy=forced=5");
    var config1 = getTargetConfiguration();

    initializeSkyframeExecutor();

    useConfiguration("--test_sharding_strategy=forced=5");
    var config2 = getTargetConfiguration();

    assertThat(config2).isEqualTo(config1);
  }

  @Test
  public void testShardingForced_differentValue_differentChecksum() throws Exception {
    useConfiguration("--test_sharding_strategy=forced=5");
    var config1 = getTargetConfiguration();

    initializeSkyframeExecutor();

    useConfiguration("--test_sharding_strategy=forced=6");
    var config2 = getTargetConfiguration();

    assertThat(config2).isNotEqualTo(config1);
  }

  @Test
  public void testFlakyAttributeValidation() throws Exception {
    scratch.file(
        "flaky/BUILD",
        """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "good_test",
            srcs = ["a.sh"],
        )

        foo_test(
            name = "flaky_test",
            srcs = ["a.sh"],
            flaky = 1,
        )
        """);
    Artifact testStatus = Iterables.getOnlyElement(getTestStatusArtifacts("//flaky:good_test"));
    assertThat(testStatus).isNotNull();
    TestRunnerAction action = (TestRunnerAction) getGeneratingAction(testStatus);
    assertThat(action.getTestProperties().isFlaky()).isFalse();

    testStatus = Iterables.getOnlyElement(getTestStatusArtifacts("//flaky:flaky_test"));
    assertThat(testStatus).isNotNull();
    action = (TestRunnerAction) getGeneratingAction(testStatus);
    assertThat(action.getTestProperties().isFlaky()).isTrue();
  }

  @Test
  public void testIllegalBooleanFlakySetting() throws Exception {
    checkError(
        "flaky",
        "bad_test",
        "expected one of [False, True, 0, 1]",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'bad_test',",
        "        srcs = ['a.sh'],",
        "        flaky = 2)");
  }

  @Test
  public void testRunsPerTest() throws Exception {
    useConfiguration("--runs_per_test=2");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//tests:small_test_1");
    assertThat(testStatusList).hasSize(2);
    Artifact testStatus1 = testStatusList.get(0);
    Artifact testStatus2 = testStatusList.get(1);
    assertThat(testStatus1).isNotNull();
    assertThat(testStatus2).isNotNull();
    assertThat(testStatus2).isNotSameInstanceAs(testStatus1);
    assertThat(getGeneratingAction(testStatus2))
        .isNotSameInstanceAs(getGeneratingAction(testStatus1));
    assertThat(testStatus1.getRootRelativePath().getPathString())
        .contains("tests/small_test_1/run_1_of_2/test");
    assertThat(testStatus2.getRootRelativePath().getPathString())
        .contains("tests/small_test_1/run_2_of_2/test");
  }

  @Test
  public void testRunsPerTestCanBeOverridden() throws Exception {
    useConfiguration("--runs_per_test=1", "--runs_per_test=2");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//tests:small_test_1");
    assertThat(testStatusList).hasSize(2);
    Artifact testStatus1 = testStatusList.get(0);
    Artifact testStatus2 = testStatusList.get(1);
    assertThat(testStatus1).isNotNull();
    assertThat(testStatus2).isNotNull();
    assertThat(testStatus2).isNotSameInstanceAs(testStatus1);
    assertThat(getGeneratingAction(testStatus2))
        .isNotSameInstanceAs(getGeneratingAction(testStatus1));
    assertThat(testStatus1.getRootRelativePath().getPathString())
        .contains("tests/small_test_1/run_1_of_2/test");
    assertThat(testStatus2.getRootRelativePath().getPathString())
        .contains("tests/small_test_1/run_2_of_2/test");
  }

  /**
   * Test that test rules always construct with a standard timeout, either
   * inferred from size or explicitly set by attribute.
   */
  @Test
  public void testTestTimeoutFlagOverridesTimeoutDefaultsValues() throws Exception {
    scratch.file(
        "javatests/timeouts/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "small_no_timeout",
            size = "small",
            srcs = [],
        )

        java_test(
            name = "small_with_timeout",
            size = "small",
            timeout = "long",
            srcs = [],
        )
        """);
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//javatests/timeouts:small_no_timeout");
    TestRunnerAction testAction = (TestRunnerAction)
        getGeneratingAction(Iterables.get(testStatusList, 0));
    Integer timeout = testAction.getTestProperties().getTimeout().getTimeoutSeconds();
    assertThat(timeout).isEqualTo(TestTimeout.SHORT.getTimeoutSeconds());

    testStatusList = getTestStatusArtifacts("//javatests/timeouts:small_with_timeout");
    testAction = (TestRunnerAction) getGeneratingAction(Iterables.get(testStatusList, 0));
    timeout = testAction.getTestProperties().getTimeout().getTimeoutSeconds();
    assertThat(timeout).isEqualTo(TestTimeout.LONG.getTimeoutSeconds());
  }

  @Test
  public void testRunsPerTestWithSharding() throws Exception {
    useConfiguration("--runs_per_test=2");
    scratch.file(
        "javatests/jt/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "RGT",
            srcs = ["RGT.java"],
            shard_count = 10,
        )
        """);
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//javatests/jt:RGT");
    assertThat(testStatusList).hasSize(20);
    Artifact testStatus1 = testStatusList.get(0);
    Artifact testStatus10 = testStatusList.get(9);
    Artifact testStatus11 = testStatusList.get(10);
    assertThat(testStatus1).isNotNull();
    assertThat(testStatus10).isNotNull();
    assertThat(testStatus11).isNotNull();
    assertThat(testStatus1.getRootRelativePath().getPathString())
        .contains("javatests/jt/RGT/shard_1_of_10_run_1_of_2/test");
    assertThat(testStatus10.getRootRelativePath().getPathString())
        .contains("javatests/jt/RGT/shard_5_of_10_run_2_of_2/test");
    assertThat(testStatus11.getRootRelativePath().getPathString())
        .contains("javatests/jt/RGT/shard_6_of_10_run_1_of_2/test");
  }

  @Test
  public void testAspectOverNonExpandingTestSuitesVisitsImplicitTests() throws Exception {
    scratch.file(
        "BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'test_a',",
        "        srcs = [':a.sh'])",
        "",
        "foo_test(name = 'test_b',",
        "        srcs = [':b.sh'])",
        "",
        "test_suite(name = 'suite'",
        ")");
    writeLabelCollectionAspect();

    useLoadingOptions("--noexpand_test_suites");
    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//:suite"),
            ImmutableList.of("//:aspect.bzl%a"),
            /* keepGoing= */ false,
            /* loadingPhaseThreads= */ 1,
            /* doAnalysis= */ true,
            new EventBus());
    ConfiguredAspect aspectValue =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl");
    StructImpl info = (StructImpl) aspectValue.get(key);
    assertThat(((Depset) info.getValue("labels")).getSet(String.class).toList())
        .containsExactly("@@//:suite", "@@//:test_a", "@@//:test_b");
  }

  @Test
  public void testAspectOverNonExpandingTestSuitesVisitsExplicitTests() throws Exception {
    scratch.file(
        "BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'test_a',",
        "        srcs = [':a.sh'])",
        "",
        "foo_test(name = 'test_b',",
        "        srcs = [':b.sh'])",
        "",
        "test_suite(name = 'suite',",
        "           tests = [':test_b']",
        ")");
    writeLabelCollectionAspect();

    useLoadingOptions("--noexpand_test_suites");
    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//:suite"),
            ImmutableList.of("//:aspect.bzl%a"),
            /* keepGoing= */ false,
            /* loadingPhaseThreads= */ 1,
            /* doAnalysis= */ true,
            new EventBus());
    ConfiguredAspect aspectValue =
        Iterables.getOnlyElement(analysisResult.getAspectsMap().values());
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl");
    StructImpl info = (StructImpl) aspectValue.get(key);
    assertThat(((Depset) info.getValue("labels")).getSet(String.class).toList())
        .containsExactly("@@//:suite", "@@//:test_b");
  }

  @Test
  public void testAspectOverExpandingTestSuitesDoesNotVisitSuite() throws Exception {
    scratch.file(
        "BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'test_a',",
        "        srcs = [':a.sh'])",
        "",
        "foo_test(name = 'test_b',",
        "        srcs = [':b.sh'])",
        "",
        "test_suite(name = 'suite',",
        ")");
    writeLabelCollectionAspect();

    AnalysisResult analysisResult =
        update(
            ImmutableList.of("//:suite"),
            ImmutableList.of("//:aspect.bzl%a"),
            /* keepGoing= */ false,
            /* loadingPhaseThreads= */ 1,
            /* doAnalysis= */ true,
            new EventBus());
    final StarlarkProvider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//:aspect.bzl")), "StructImpl");

    List<String> labels = new ArrayList<>();
    for (ConfiguredAspect a : analysisResult.getAspectsMap().values()) {
      StructImpl info = (StructImpl) a.get(key);
      labels.addAll(((Depset) info.getValue("labels")).getSet(String.class).toList());
    }
    assertThat(labels).containsExactly("@@//:test_a", "@@//:test_b");
  }

  private void writeLabelCollectionAspect() throws IOException {
    scratch.file(
        "aspect.bzl",
        """
        StructImpl = provider(fields = ["labels"])

        def _impl(target, ctx):
            print(target.label)
            transitive = []
            if hasattr(ctx.rule.attr, "tests"):
                transitive += [dep[StructImpl].labels for dep in ctx.rule.attr.tests]
            if hasattr(ctx.rule.attr, "_implicit_tests"):
                transitive += [dep[StructImpl].labels for dep in ctx.rule.attr._implicit_tests]
            return [StructImpl(labels = depset([str(target.label)], transitive = transitive))]

        a = aspect(_impl, attr_aspects = ["tests", "_implicit_tests"])
        """);
  }

  /**
   * Regression test for bug {@link "http://b/2644860"}.
   */
  @Test
  public void testIllegalTestSizeAttributeDoesNotCrashTestSuite() throws Exception {
    checkError(
        "bad_size",
        "illegal_size_test",
        "In rule 'illegal_size_test', size 'bad' is not a valid size",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'illegal_size_test',",
        "        srcs = ['illegal.sh'],",
        "        size = 'bad')",
        "test_suite(name = 'everything')");
  }

  /**
   * Regression test for bug {@link "http://b/2644860"} but with an illegal Timeout.
   */
  @Test
  public void testIllegalTestTimeoutAttributeDoesNotCrashTestSuite() throws Exception {
    checkError(
        "bad_timeout",
        "illegal_timeout_test",
        "In rule 'illegal_timeout_test', timeout 'unreasonable' is not a valid timeout",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'illegal_timeout_test',",
        "        srcs = ['illegal.sh'],",
        "        timeout = 'unreasonable')",
        "test_suite(name = 'everything')");
  }

  /**
   * Overriding the exec group from within the test affects the way exec properties are selected.
   */
  @Test
  public void testOverrideExecGroup() throws Exception {
    scratch.file(
        "some_test.bzl",
        """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo({}, exec_group = "custom_group"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            exec_groups = {"custom_group": exec_group()},
            test = True,
        )
        """);
    scratch.file(
        "BUILD",
        "load(':some_test.bzl', 'some_test')",
        "some_test(",
        "    name = 'custom_exec_group_test',",
        "    exec_properties = {'test.key': 'bad', 'custom_group.key': 'good'},",
        ")");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//:custom_exec_group_test");
    TestRunnerAction testAction = (TestRunnerAction) getGeneratingAction(testStatusList.get(0));
    ImmutableMap<String, String> executionInfo = testAction.getExecutionInfo();
    assertThat(executionInfo).containsExactly("key", "good");
  }

  /**
   * Overriding the exec group from within the test with --use_target_platform_for_tests.
   *
   * <p>This is the same test as testOverrideExecGroup with --use_target_platform_for_tests and a
   * target platform.
   */
  @Test
  public void testOverrideTestExecGroup() throws Exception {
    scratch.file(
        "some_test.bzl",
        """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo({}, exec_group = "custom_group"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            exec_groups = {"custom_group": exec_group()},
            test = True,
        )
        """);
    scratch.file(
        "BUILD",
        "load(':some_test.bzl', 'some_test')",
        "platform(",
        "    name = 'linux_aarch64',",
        "    constraint_values = [",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
        "    ],",
        ")",
        "some_test(",
        "    name = 'custom_exec_group_test',",
        "    exec_properties = {'test.key': 'bad', 'custom_group.key': 'good'},",
        ")");
    useConfiguration("--use_target_platform_for_tests=true", "--platforms=//:linux_aarch64");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//:custom_exec_group_test");
    TestRunnerAction testAction = (TestRunnerAction) getGeneratingAction(testStatusList.get(0));
    ImmutableMap<String, String> executionInfo = testAction.getExecutionInfo();
    assertThat(executionInfo).containsExactly("key", "good");
  }

  /** Adding exec_properties from the platform with --use_target_platform_for_tests. */
  @Test
  public void testTargetTestExecGroup() throws Exception {
    scratch.file(
        "some_test.bzl",
        """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        """);
    scratch.file(
        "BUILD",
        "load(':some_test.bzl', 'some_test')",
        "platform(",
        "    name = 'linux_x86',",
        "    constraint_values = [",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
        "    ],",
        "    exec_properties = {'keyhost': 'bad'},",
        ")",
        "platform(",
        "    name = 'linux_aarch64',",
        "    constraint_values = [",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
        "    ],",
        "    exec_properties = {'key2': 'good'},",
        ")",
        "some_test(",
        "    name = 'exec_group_test',",
        "    exec_properties = {'key': 'bad'},",
        ")");
    useConfiguration(
        "--use_target_platform_for_tests=true",
        "--platforms=//:linux_aarch64",
        "--host_platform=//:linux_x86");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//:exec_group_test");
    TestRunnerAction testAction = (TestRunnerAction) getGeneratingAction(testStatusList.get(0));
    assertThat(testAction.getExecutionPlatform().label().getName()).isEqualTo("linux_aarch64");

    ImmutableMap<String, String> executionInfo = testAction.getExecutionInfo();
    assertThat(executionInfo).containsExactly("key2", "good");
  }

  /** Adding test specific exec_properties with --use_target_platform_for_tests. */
  @Test
  @Ignore("https://github.com/bazelbuild/bazel/issues/17466")
  public void testTargetTestExecGroupInheritance() throws Exception {
    useConfiguration(
        "--use_target_platform_for_tests=true",
        "--platforms=//:linux_aarch64",
        "--host_platform=//:linux_x86");
    scratch.file(
        "some_test.bzl",
        """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.write(script, "shell script goes here", is_executable = True)
            return [
                DefaultInfo(executable = script),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
        )
        """);
    scratch.file(
        "BUILD",
        "load(':some_test.bzl', 'some_test')",
        "platform(",
        "    name = 'linux_x86',",
        "    constraint_values = [",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
        "       '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
        "    ],",
        "    exec_properties = {'keyhost': 'bad'},",
        ")",
        "platform(",
        "    name = 'linux_aarch64',",
        "    constraint_values = [",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:linux',",
        "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
        "    ],",
        "    exec_properties = {'key2': 'good'},",
        ")",
        "some_test(",
        "    name = 'exec_group_test',",
        "    exec_properties = {'test.key': 'good', 'key': 'bad'},",
        ")");
    ImmutableList<Artifact.DerivedArtifact> testStatusList =
        getTestStatusArtifacts("//:exec_group_test");
    TestRunnerAction testAction = (TestRunnerAction) getGeneratingAction(testStatusList.get(0));
    assertThat(testAction.getExecutionPlatform().label().getName()).isEqualTo("linux_aarch64");

    ImmutableMap<String, String> executionInfo = testAction.getExecutionInfo();
    assertThat(executionInfo).containsExactly("key2", "good", "key", "good");
  }

  @Test
  public void testNonExecutableCoverageReportGenerator() throws Exception {
    useConfiguration(
        "--coverage_report_generator=//bad_gen:bad_cov_gen", "--collect_code_coverage");
    checkError(
        "bad_gen",
        "some_test",
        "--coverage_report_generator does not refer to an executable target",
        "filegroup(name = 'bad_cov_gen')",
        "cc_test(name = 'some_test')");
  }

  private ImmutableList<Artifact.DerivedArtifact> getTestStatusArtifacts(String label)
      throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    return target.getProvider(TestProvider.class).getTestParams().getTestStatusArtifacts();
  }

  @Test
  public void testRunUnderConfiguredForTestExecPlatform() throws Exception {
    scratch.file(
        "some_test.bzl",
        """
        def _some_test_impl(ctx):
            script = ctx.actions.declare_file(ctx.attr.name + ".sh")
            ctx.actions.run_shell(
                outputs = [script],
                inputs = [],
                command = "echo 'shell script goes here' > $@",
            )
            return [
                DefaultInfo(executable = script),
                testing.ExecutionInfo(exec_group = "alternative_test"),
            ]

        some_test = rule(
            implementation = _some_test_impl,
            test = True,
            exec_groups = {
                "test": exec_group(
                    exec_compatible_with = [
                        "%1$sos:linux",
                    ],
                ),
                "alternative_test": exec_group(
                    exec_compatible_with = [
                        "%1$sos:android",
                    ],
                ),
            },
        )
        """
            .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT));
    scratch.file(
        "BUILD",
        """
        load(':some_test.bzl', 'some_test')
        platform(
            name = "linux",
            constraint_values = [
                "%1$sos:linux",
            ],
        )
        platform(
            name = "windows",
            constraint_values = [
                "%1$sos:windows",
            ],
        )
        platform(
            name = "macos",
            constraint_values = [
                "%1$sos:macos",
            ],
        )
        platform(
            name = "android",
            constraint_values = [
                "%1$sos:android",
            ],
        )
        genrule(
            name = "run_under_tool",
            outs = ["run_under_tool.sh"],
            cmd = "echo 'runUnderTool' > $@",
            executable = True,
        )
        some_test(
            name = "some_test",
            exec_compatible_with = ["%1$sos:macos"],
        )
        """
            .formatted(TestConstants.CONSTRAINTS_PACKAGE_ROOT));
    useConfiguration(
        "--run_under=//:run_under_tool",
        "--incompatible_bazel_test_exec_run_under",
        "--platforms=//:windows",
        "--host_platform=//:windows",
        "--extra_execution_platforms=//:windows,//:android,//:linux,//:macos");

    Action generateAction = getGeneratingAction(getExecutable("//:some_test"));
    assertThat(generateAction.getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonicalUnchecked("//:macos"));

    Action testAction = getGeneratingAction(getTestStatusArtifacts("//:some_test").get(0));
    assertThat(testAction.getExecutionPlatform().label())
        .isEqualTo(Label.parseCanonicalUnchecked("//:android"));

    Artifact runUnderTool =
        testAction.getInputs().toList().stream()
            .filter(artifact -> artifact.getExecPath().getBaseName().equals("run_under_tool.sh"))
            .findFirst()
            .orElseThrow();
    // TODO: The run_under_tool should be built for the exec platform of the test action, which
    //  differs from the exec platform of the "test" exec group due to testing.ExecutionInfo.
    //  Building for the "test" exec group is still preferred over building for the target platform
    //  or the default exec platform of the test rule.
    assertThat(
            ((BuildConfigurationValue)
                    getGeneratingAction(runUnderTool).getOwner().getBuildConfigurationInfo())
                .getOptions()
                .get(PlatformOptions.class)
                .platforms)
        .containsExactly(Label.parseCanonicalUnchecked("//:linux"));
  }

  private ImmutableList<Artifact.DerivedArtifact> getTestStatusArtifacts(
      TransitiveInfoCollection target) {
    return target.getProvider(TestProvider.class).getTestParams().getTestStatusArtifacts();
  }
}
