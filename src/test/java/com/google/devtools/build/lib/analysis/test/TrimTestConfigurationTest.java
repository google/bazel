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
package com.google.devtools.build.lib.analysis.test;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableMultiset.toImmutableMultiset;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.MockRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** BUILD-level Tests for test_trim_configuration. */
@RunWith(JUnit4.class)
public final class TrimTestConfigurationTest extends AnalysisTestCase {

  /** Simple native test rule. */
  public static final class NativeTest implements RuleConfiguredTargetFactory {
    @Override
    @Nullable
    public ConfiguredTarget create(RuleContext context)
        throws ActionConflictException, InterruptedException {
      Artifact executable = context.getBinArtifact(context.getLabel().getName());
      context.registerAction(FileWriteAction.create(context, executable, "#!/bin/true", true));
      Runfiles runfiles =
          new Runfiles.Builder(context.getWorkspaceName()).addArtifact(executable).build();
      return new RuleConfiguredTargetBuilder(context)
          .setFilesToBuild(NestedSetBuilder.create(Order.STABLE_ORDER, executable))
          .addProvider(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
          .setRunfilesSupport(
              RunfilesSupport.withExecutable(context, runfiles, executable), executable)
          .build();
    }
  }

  private static final RuleDefinition NATIVE_TEST_RULE =
      (MockRule)
          () ->
              MockRule.ancestor(
                      BaseRuleClasses.TestBaseRule.class, BaseRuleClasses.NativeBuildRule.class)
                  .factory(NativeTest.class)
                  .type(RuleClassType.TEST)
                  .define(
                      "native_test",
                      attr("deps", LABEL_LIST).allowedFileTypes(),
                      attr("exec_deps", LABEL_LIST)
                          .cfg(ExecutionTransitionFactory.createFactory())
                          .allowedFileTypes());

  private static final RuleDefinition NATIVE_LIB_RULE =
      (MockRule)
          () ->
              MockRule.ancestor(BaseRuleClasses.NativeBuildRule.class)
                  .define(
                      "native_lib",
                      attr("deps", LABEL_LIST).allowedFileTypes(),
                      attr("exec_deps", LABEL_LIST)
                          .cfg(ExecutionTransitionFactory.createFactory())
                          .allowedFileTypes());

  @Before
  public void setUp() throws Exception {
    setRulesAvailableInTests(NATIVE_TEST_RULE, NATIVE_LIB_RULE);
    scratch.file(
        "test/test.bzl",
        """
        def _starlark_test_impl(ctx):
            executable = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(executable, "#!/bin/true", is_executable = True)
            return DefaultInfo(
                executable = executable,
            )

        starlark_test = rule(
            implementation = _starlark_test_impl,
            test = True,
            executable = True,
            attrs = {
                "deps": attr.label_list(),
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        """);
    scratch.file(
        "test/lib.bzl",
        """
        def _starlark_lib_impl(ctx):
            pass

        starlark_lib = rule(
            implementation = _starlark_lib_impl,
            attrs = {
                "deps": attr.label_list(),
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        """);
  }

  private static void assertNumberOfConfigurationsOfTargets(
      Set<? extends ActionLookupKey> keys, Map<String, Integer> targetsWithCounts) {
    ImmutableMultiset<Label> actualSet =
        keys.stream()
            .filter(key -> key instanceof ConfiguredTargetKey)
            .map(ArtifactOwner::getLabel)
            .collect(toImmutableMultiset());
    ImmutableMap<Label, Integer> expected =
        targetsWithCounts.entrySet().stream()
            .collect(
                toImmutableMap(
                    entry -> Label.parseCanonicalUnchecked(entry.getKey()), Entry::getValue));
    ImmutableMap<Label, Integer> actual =
        expected.keySet().stream().collect(toImmutableMap(label -> label, actualSet::count));
    assertThat(actual).containsExactlyEntriesIn(expected);
  }

  @Test
  public void flagOffDifferentTestOptions_ResultsInDifferentCTs() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update(
        "//test:suite",
        "//test:native_test",
        "//test:starlark_test",
        "//test:native_dep",
        "//test:starlark_dep",
        "//test:native_shared_dep",
        "//test:starlark_shared_dep");
    LinkedHashSet<ActionLookupKey> visitedTargets =
        new LinkedHashSet<>(getSkyframeEvaluatedTargetKeys());
    // asserting that the top-level targets are the same as the ones in the diamond starting at
    // //test:suite
    assertNumberOfConfigurationsOfTargets(
        visitedTargets,
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());

    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB");
    update(
        "//test:suite",
        "//test:native_test",
        "//test:starlark_test",
        "//test:native_dep",
        "//test:starlark_dep",
        "//test:native_shared_dep",
        "//test:starlark_shared_dep");
    visitedTargets.addAll(getSkyframeEvaluatedTargetKeys());
    // asserting that we got no overlap between the two runs, we had to build different versions of
    // all seven targets
    assertNumberOfConfigurationsOfTargets(
        visitedTargets,
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 2)
            .put("//test:native_test", 2)
            .put("//test:starlark_test", 2)
            .put("//test:native_dep", 2)
            .put("//test:starlark_dep", 2)
            .put("//test:native_shared_dep", 2)
            .put("//test:starlark_shared_dep", 2)
            .build());
  }

  @Test
  public void flagOffDifferentTestOptions_CacheCleared() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:suite");
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB");
    update("//test:suite");
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:suite");
    // asserting that we got no overlap between the first and third runs, we had to reanalyze all
    // seven targets
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());
  }

  @Test
  public void flagOnDifferentTestOptions_SharesCTsForNonTestRules() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update(
        "//test:suite",
        "//test:native_test",
        "//test:starlark_test",
        "//test:native_dep",
        "//test:starlark_dep",
        "//test:native_shared_dep",
        "//test:starlark_shared_dep");
    var visitedTargetKeys = new LinkedHashSet<ActionLookupKey>(getEvaluatedTargetValueKeys());
    // asserting that the top-level targets are the same as the ones in the diamond starting at
    // //test:suite
    assertNumberOfConfigurationsOfTargets(
        visitedTargetKeys,
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());

    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB");
    update(
        "//test:suite",
        "//test:native_test",
        "//test:starlark_test",
        "//test:native_dep",
        "//test:starlark_dep",
        "//test:native_shared_dep",
        "//test:starlark_shared_dep");
    visitedTargetKeys.addAll(getEvaluatedTargetValueKeys());

    // asserting that our non-test rules matched between the two runs, we had to build different
    // versions of the three test targets but not the four non-test targets
    assertNumberOfConfigurationsOfTargets(
        visitedTargetKeys,
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 2)
            .put("//test:native_test", 2)
            .put("//test:starlark_test", 2)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());
  }

  private ImmutableSet<ActionLookupKey> getEvaluatedTargetValueKeys() throws InterruptedException {
    MemoizingEvaluator evaluator = skyframeExecutor.getEvaluator();
    var result = ImmutableSet.<ActionLookupKey>builder();
    for (ActionLookupKey key : getSkyframeEvaluatedTargetKeys()) {
      result.add(
          ((ConfiguredTargetValue) evaluator.getExistingValue(key))
              .getConfiguredTarget()
              .getLookupKey());
    }
    return result.build();
  }

  @Test
  public void flagOnDifferentTestOptions_CacheKeptBetweenRuns() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:suite");
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeB");
    update("//test:suite");
    // asserting that the non-test rules were cached from the last run and did not need to be run
    // again
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:native_dep", 0)
            .put("//test:starlark_dep", 0)
            .put("//test:native_shared_dep", 0)
            .put("//test:starlark_shared_dep", 0)
            .build());
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:suite");
    // asserting that the test rules were cached from the first run and did not need to be run again
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 0)
            .put("//test:native_test", 0)
            .put("//test:starlark_test", 0)
            .build());
  }

  @Test
  public void flagOnDifferentNonTestOptions_CacheCleared() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeA");
    update("//test:suite");
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeB");
    update("//test:suite");
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--define=Test=TypeA");
    update("//test:suite");
    // asserting that we got no overlap between the first and third runs, we had to reanalyze all
    // seven targets
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());
  }

  @Test
  public void flagOffToOn_CacheCleared() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites");
    update("//test:suite");
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites");
    update("//test:suite");
    // asserting that we got no overlap between the first and second runs, we had to reanalyze all
    // seven targets
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());
  }

  @Test
  public void flagOnToOff_CacheCleared() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites");
    update("//test:suite");
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites");
    update("//test:suite");
    // asserting that we got no overlap between the first and second runs, we had to reanalyze all
    // seven targets
    assertNumberOfConfigurationsOfTargets(
        getSkyframeEvaluatedTargetKeys(),
        new ImmutableMap.Builder<String, Integer>()
            .put("//test:suite", 1)
            .put("//test:native_test", 1)
            .put("//test:starlark_test", 1)
            .put("//test:native_dep", 1)
            .put("//test:starlark_dep", 1)
            .put("//test:native_shared_dep", 1)
            .put("//test:starlark_shared_dep", 1)
            .build());
  }

  @Test
  public void flagOnDynamicConfigsNotrimExecDeps_AreNotAnalyzedAnyExtraTimes() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        native_test(
            name = "native_outer_test",
            exec_deps = [
                ":native_test",
                ":starlark_test",
            ],
            deps = [
                ":native_test",
                ":starlark_test",
            ],
        )

        starlark_test(
            name = "starlark_outer_test",
            exec_deps = [
                ":native_test",
                ":starlark_test",
            ],
            deps = [
                ":native_test",
                ":starlark_test",
            ],
        )

        native_test(
            name = "native_test",
            exec_deps = [
                ":native_dep",
                ":starlark_dep",
            ],
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        starlark_test(
            name = "starlark_test",
            exec_deps = [
                ":native_dep",
                ":starlark_dep",
            ],
            deps = [
                ":native_dep",
                ":starlark_dep",
            ],
        )

        native_lib(
            name = "native_dep",
            exec_deps = [
                ":native_shared_dep",
                "starlark_shared_dep",
            ],
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        starlark_lib(
            name = "starlark_dep",
            exec_deps = [
                ":native_shared_dep",
                "starlark_shared_dep",
            ],
            deps = [
                "starlark_shared_dep",
                ":native_shared_dep",
            ],
        )

        native_lib(
            name = "native_shared_dep",
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration");
    update(
        "//test:native_outer_test",
        "//test:starlark_outer_test",
        "//test:native_test",
        "//test:starlark_test",
        "//test:native_dep",
        "//test:starlark_dep",
        "//test:native_shared_dep",
        "//test:starlark_shared_dep");
    LinkedHashSet<ActionLookupKey> visitedTargets =
        new LinkedHashSet<>(getSkyframeEvaluatedTargetKeys());
    assertNumberOfConfigurationsOfTargets(
        visitedTargets,
        new ImmutableMap.Builder<String, Integer>()
            // Top-level and exec.
            .put("//test:native_test", 2)
            .put("//test:starlark_test", 2)
            // Target and exec.
            .put("//test:native_dep", 2)
            .put("//test:starlark_dep", 2)
            .put("//test:native_shared_dep", 2)
            .put("//test:starlark_shared_dep", 2)
            .build());
  }

  @Test
  public void flagOffConfigSetting_CanInspectTestOptions() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        config_setting(
            name = "test_mode",
            values = {"test_arg": "TypeA"},
        )

        starlark_test(
            name = "starlark_test",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_dep",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:test_mode", "//test:starlark_test", "//test:starlark_dep");
    // All 3 targets (top level, under a test, under a non-test) should successfully analyze.
    assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(3);
  }

  @Test
  public void flagOnConfigSetting_skipsTryingToInspectTestOptions() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        config_setting(
            name = "test_mode",
            values = {"test_arg": "TypeA"},
        )

        starlark_test(
            name = "starlark_test",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_dep",
            deps = select({
                ":test_mode": [":starlark_shared_dep"],
                "//conditions:default": [],
            }),
        )

        starlark_lib(
            name = "starlark_shared_dep",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:starlark_dep");
    assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(1);

    update("//test:test_mode", "//test:starlark_test");
    // When reached through only test targets (top level, under a test) analysis should succeed
    assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(2);
  }

  @Test
  public void flagOffNonTestTargetWithTestDependencies_IsPermitted() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        """);
    useConfiguration("--notrim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:starlark_dep");
    assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty();
  }

  @Test
  public void flagOnNonTestTargetWithTestDependencies_IsPermitted() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:starlark_dep");
    assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty();
  }

  @Test
  public void flagOnNonTestTargetWithTestSuiteDependencies_IsPermitted() throws Exception {
    // reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":a_test_suite"],
        )

        starlark_test(
            name = "starlark_test",
        )

        test_suite(
            name = "a_test_suite",
            tests = [":starlark_test"],
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:starlark_dep");
    assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty();
  }

  @Test
  public void flagOnNonTestTargetWithJavaTestDependencies_IsPermitted() throws Exception {
    // reporter.removeHandler(failFastHandler);
    scratch.file(
        "test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        load(":lib.bzl", "starlark_lib")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":JavaTest"],
        )

        java_test(
            name = "JavaTest",
            srcs = ["JavaTest.java"],
            test_class = "test.JavaTest",
        )
        """);
    useConfiguration(
        "--trim_test_configuration",
        "--noexpand_test_suites",
        "--test_arg=TypeA",
        "--experimental_google_legacy_api");
    update("//test:starlark_dep");
    assertThat(getAnalysisResult().getTargetsToBuild()).isNotEmpty();
  }

  @Test
  public void flagOnTestSuiteWithTestDependencies_CanBeAnalyzed() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        test_suite(
            name = "suite",
            tests = [
                ":starlark_test",
                ":suite_2",
            ],
        )

        test_suite(
            name = "suite_2",
            tests = [
                ":starlark_test_2",
                ":starlark_test_3",
            ],
        )

        starlark_test(
            name = "starlark_test",
        )

        starlark_test(
            name = "starlark_test_2",
        )

        starlark_test(
            name = "starlark_test_3",
        )
        """);
    useConfiguration("--trim_test_configuration", "--noexpand_test_suites", "--test_arg=TypeA");
    update("//test:suite", "//test:suite_2");
    assertThat(getAnalysisResult().getTargetsToBuild()).hasSize(2);
  }

  @Test
  public void flagOnNonTestTargetWithTestDependencies_isTrimmed() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        """);
    useConfiguration(
        "--trim_test_configuration", "--noexperimental_retain_test_configuration_across_testonly");
    update("//test:starlark_dep");
    ConfiguredTarget top = getConfiguredTarget("//test:starlark_dep");
    assertThat(getConfiguration(top).hasFragment(TestConfiguration.class)).isFalse();
  }

  @Test
  public void flagOnNonTestTargetWithTestDependencies_isNotTrimmedWithExperimentalFlag()
      throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            deps = [":starlark_test"],
        )

        starlark_test(
            name = "starlark_test",
        )
        """);
    useConfiguration(
        "--trim_test_configuration", "--experimental_retain_test_configuration_across_testonly");
    update("//test:starlark_dep");
    ConfiguredTarget top = getConfiguredTarget("//test:starlark_dep");
    assertThat(getConfiguration(top).hasFragment(TestConfiguration.class)).isTrue();
  }

  @Test
  public void flagOnNonTestTargetWithMagicTransitiveConfigs_isNotTrimmed() throws Exception {
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "starlark_lib")
        load(":test.bzl", "starlark_test")

        starlark_lib(
            name = "starlark_dep",
            testonly = 1,
            transitive_configs = ["//command_line_option/fragment:test"],
            deps = [],
        )
        """);
    useConfiguration("--trim_test_configuration");
    update("//test:starlark_dep");
    ConfiguredTarget top = getConfiguredTarget("//test:starlark_dep");
    assertThat(getConfiguration(top).hasFragment(TestConfiguration.class)).isTrue();
  }
}
