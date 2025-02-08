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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ObjectArrays;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.util.AnalysisCachingTestBase;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestConstants.InternalTestExecutionMode;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkValue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Analysis caching tests. */
@RunWith(JUnit4.class)
public class AnalysisCachingTest extends AnalysisCachingTestBase {
  private static final String CACHE_DISCARD_WARNING =
      "discarding analysis cache (this can be expensive, see"
          + " https://bazel.build/advanced/performance/iteration-speed).";

  @Before
  public void setup() throws Exception {
    useConfiguration();
  }

  @Override
  public void useConfiguration(String... args) throws Exception {
    super.useConfiguration(ObjectArrays.concat(args, "--experimental_google_legacy_api"));
  }

  @Test
  public void testSimpleCleanAnalysis() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget javaTest = getConfiguredTarget("//java/a:A");
    assertThat(javaTest).isNotNull();
    assertThat(JavaInfo.getProvider(JavaSourceJarsProvider.class, javaTest)).isNotNull();
  }

  @Test
  public void testTickTock() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )

        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        """);
    update("//java/a:A");
    update("//java/a:B");
    update("//java/a:A");
  }

  @Test
  public void testFullyCached() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget old = getConfiguredTarget("//java/a:A");
    update("//java/a:A");
    ConfiguredTarget current = getConfiguredTarget("//java/a:A");
    assertThat(current).isSameInstanceAs(old);
  }

  @Test
  public void testSubsetCached() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )

        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        """);
    update("//java/a:A", "//java/a:B");
    ConfiguredTarget old = getConfiguredTarget("//java/a:A");
    update("//java/a:A");
    ConfiguredTarget current = getConfiguredTarget("//java/a:A");
    assertThat(current).isSameInstanceAs(old);
  }

  @Test
  public void testDependencyChanged() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        """);
    scratch.file(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget old = getConfiguredTarget("//java/a:A");
    scratch.overwriteFile(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["C.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget current = getConfiguredTarget("//java/a:A");
    assertThat(current).isNotSameInstanceAs(old);
  }

  @Test
  public void testAspectHintsChanged() throws Exception {
    scratch.file(
        "foo/rule.bzl",
        """
        def _rule_impl(ctx):
            return []

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "deps": attr.label_list(),
                "srcs": attr.label_list(allow_files = True),
            },
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            deps = [":bar"],
        )

        my_rule(
            name = "bar",
            aspect_hints = ["//aspect_hint:hint"],
        )
        """);
    scratch.file(
        "aspect_hint/BUILD",
        """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "hint",
            srcs = ["baz.h"],
        )
        """);

    update("//foo:foo");
    ConfiguredTarget old = getConfiguredTarget("//foo:foo");
    scratch.overwriteFile(
        "aspect_hint/BUILD",
        """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "hint",
            srcs = ["qux.h"],
        )
        """);
    update("//foo:foo");
    ConfiguredTarget current = getConfiguredTarget("//foo:foo");

    assertThat(current).isNotSameInstanceAs(old);
  }

  @Test
  public void testTopLevelChanged() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        """);
    scratch.file(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget old = getConfiguredTarget("//java/a:A");
    scratch.overwriteFile(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);
    update("//java/a:A");
    ConfiguredTarget current = getConfiguredTarget("//java/a:A");
    assertThat(current).isNotSameInstanceAs(old);
  }

  // Regression test for:
  // "action conflict detection is incorrect if conflict is in non-top-level configured targets".
  @Test
  public void testActionConflictInDependencyImpliesTopLevelTargetFailure() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict_non_top_level/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )

        cc_binary(
            name = "foo",
            data = ["_objs/x/foo.o"],
            deps = ["x"],
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict_non_top_level:foo");
    assertContainsEvent("file 'conflict_non_top_level/_objs/x/foo.o' " + CONFLICT_MSG);
    assertThat(getAnalysisResult().getTargetsToBuild()).isEmpty();
  }

  /**
   * Generating the same output from two targets is ok if we build them on successive builds and
   * invalidate the first target before we build the second target. This is a strictly weaker test
   * than if we didn't invalidate the first target, but since Skyframe can't pass then, this test
   * could be useful for it. Actually, since Skyframe makes multiple update calls, it manages to
   * unregister actions even when it shouldn't, and so this test can incorrectly pass. However,
   * {@code SkyframeExecutorTest#testNoActionConflictWithInvalidatedTarget} tests it more
   * rigorously.
   */
  @Test
  public void testNoActionConflictWithInvalidatedTarget() throws Exception {
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    update("//conflict:x");
    ConfiguredTarget conflict = getConfiguredTarget("//conflict:x");
    Action oldAction = getGeneratingAction(getBinArtifact("_objs/x/foo.o", conflict));
    assertThat(oldAction.getOwner().getLabel().toString()).isEqualTo("//conflict:x");
    scratch.overwriteFile(
        "conflict/BUILD",
        """
        # Rename target.
        cc_library(
            name = "newx",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    update(defaultFlags(), "//conflict:_objs/x/foo.o");
    ConfiguredTarget objsConflict = getConfiguredTarget("//conflict:_objs/x/foo.o");
    Action newAction = getGeneratingAction(getBinArtifact("_objs/x/foo.o", objsConflict));
    assertThat(newAction.getOwner().getLabel().toString()).isEqualTo("//conflict:_objs/x/foo.o");
  }

  /** Generating the same output from multiple actions is causing an error. */
  @Test
  public void testActionConflictCausesError() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o");
    assertContainsEvent("file 'conflict/_objs/x/foo.o' " + CONFLICT_MSG);
  }

  @Test
  public void testNoActionConflictErrorAfterClearedAnalysis() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o");
    // We want to force a "dropConfiguredTargetsNow" operation, which won't inform the
    // invalidation receiver about the dropped configured targets.
    skyframeExecutor.clearAnalysisCache(ImmutableSet.of(), ImmutableSet.of());
    assertContainsEvent("file 'conflict/_objs/x/foo.o' " + CONFLICT_MSG);
    eventCollector.clear();
    scratch.overwriteFile(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["baz.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o");
    assertNoEvents();
  }

  /**
   * For two conflicting actions whose primary inputs are different, no list diff detail should be
   * part of the output.
   */
  @Test
  public void testConflictingArtifactsErrorWithNoListDetail() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o");

    assertContainsEvent("file 'conflict/_objs/x/foo.o' " + CONFLICT_MSG);
    assertDoesNotContainEvent("MandatoryInputs");
    assertDoesNotContainEvent("Outputs");
  }

  /**
   * For two conflicted actions whose primary inputs are the same, list diff (max 5) should be part
   * of the output.
   */
  @Test
  public void testConflictingArtifactsWithListDetail() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        "cc_library(name='x', srcs=['foo1.cc'])",
        "genrule(name = 'foo', outs=['_objs/x/foo1.o'], srcs=['foo1.cc', 'foo2.cc', "
            + "'foo3.cc', 'foo4.cc', 'foo5.cc', 'foo6.cc'], cmd='', output_to_bindir=1)");
    reporter.removeHandler(failFastHandler); // expect errors
    update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:foo");

    Event event = assertContainsEvent("file 'conflict/_objs/x/foo1.o' " + CONFLICT_MSG);
    assertContainsEvent("MandatoryInputs");
    assertContainsEvent("Outputs");

    // Validate that maximum of 5 artifacts in MandatoryInputs are part of output.
    Pattern pattern = Pattern.compile("\tconflict\\/foo[2-6].cc");
    Matcher matcher = pattern.matcher(event.getMessage());
    int matchCount = 0;
    while (matcher.find()) {
      matchCount++;
    }

    assertWithMessage(
            "Event does not contain expected number of file conflicts:\n" + event.getMessage())
        .that(matchCount)
        .isEqualTo(5);
  }

  /**
   * The current action conflict detection code will only mark one of the targets as having an
   * error, and with multi-threaded analysis it is not deterministic which one that will be.
   */
  @Test
  public void testActionConflictMarksTargetInvalid() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67529176): conflicts not detected.
      return;
    }
    useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL);
    scratch.file(
        "conflict/BUILD",
        """
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        """);
    reporter.removeHandler(failFastHandler); // expect errors
    int successfulAnalyses =
        update(defaultFlags().with(Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.pic.o")
            .getTargetsToBuild()
            .size();
    assertThat(successfulAnalyses).isEqualTo(1);
  }

  @Test
  public void aliasConflict() throws Exception {
    scratch.file(
        "conflict/conflict.bzl",
        """
        def _conflict(ctx):
            file = ctx.actions.declare_file("single_file")
            ctx.actions.write(output = file, content = ctx.attr.name)
            return [DefaultInfo(files = depset([file]))]

        my_rule = rule(implementation = _conflict)
        """);
    scratch.file(
        "conflict/BUILD",
        """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "conflict1")

        my_rule(name = "conflict2")

        alias(
            name = "aliased",
            actual = ":conflict2",
        )
        """);
    reporter.removeHandler(failFastHandler);
    assertThrows(
        ViewCreationFailedException.class,
        () -> update("//conflict:conflict1", "//conflict:aliased"));
  }

  @Test
  public void actionConflictFromSameTarget() throws Exception {
    scratch.file(
        "conflict/conflict.bzl",
        """
        def _conflict(ctx):
            file = ctx.actions.declare_file("single_file")
            ctx.actions.write(output = file, content = "a")
            ctx.actions.write(output = file, content = "b")
            return [DefaultInfo(files = depset([file]))]

        my_rule = rule(implementation = _conflict)
        """);
    scratch.file(
        "conflict/BUILD",
        """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "conflict")
        """);
    reporter.removeHandler(failFastHandler);
    assertThrows(ViewCreationFailedException.class, () -> update("//conflict"));
    assertContainsEvent("file 'conflict/single_file' is generated by these conflicting actions:");
  }

  @Test
  public void actionConflictWithDependentRule() throws IOException {
    scratch.file(
        "conflict/conflict.bzl",
        """
        def _dep(ctx):
            file = ctx.actions.declare_file("file")
            ctx.actions.write(output = file, content = "")
            return [DefaultInfo(files = depset([file]))]

        dep_rule = rule(implementation = _dep)

        def _top(ctx):
            file = ctx.file.src
            ctx.actions.write(output = file, content = "")
            return [DefaultInfo(files = depset([file]))]

        top_rule = rule(
            implementation = _top,
            attrs = {"src": attr.label(mandatory = True, allow_single_file = True)},
        )
        """);
    scratch.file(
        "conflict/BUILD",
        """
        load(":conflict.bzl", "dep_rule", "top_rule")

        top_rule(
            name = "top",
            src = ":dep",
        )

        dep_rule(name = "dep")
        """);
    reporter.removeHandler(failFastHandler);

    assertThrows(ViewCreationFailedException.class, () -> update("//conflict:top"));

    assertContainsEvent(
        "in top_rule rule //conflict:top: File 'conflict/file' is produced by action 'Writing file"
            + " conflict/file' but is already generated by rule //conflict:dep");
  }

  /** BUILD file involved in BUILD-file cycle is changed */
  @Test
  public void testBuildFileInCycleChanged() throws Exception {
    if (getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
      // TODO(b/67412276): cycles not properly handled.
      return;
    }
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        """);
    scratch.file(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
            deps = ["//java/c"],
        )
        """);
    scratch.file(
        "java/c/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "c",
            srcs = ["C.java"],
            deps = ["//java/b"],
        )
        """);
    // expect error
    reporter.removeHandler(failFastHandler);
    update(defaultFlags().with(Flag.KEEP_GOING), "//java/a:A");
    ConfiguredTarget old = getConfiguredTarget("//java/a:A");
    // drop dependency on from b to c
    scratch.overwriteFile(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        """);
    eventCollector.clear();
    reporter.addHandler(failFastHandler);
    update("//java/a:A");
    ConfiguredTarget current = getConfiguredTarget("//java/a:A");
    assertThat(current).isNotSameInstanceAs(old);
  }

  private void assertNoTargetsVisited() {
    Set<?> analyzedTargets = getSkyframeEvaluatedTargetKeys();
    assertWithMessage(analyzedTargets.toString()).that(analyzedTargets.size()).isEqualTo(0);
  }

  @Test
  public void testSecondRunAllCacheHits() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);
    update("//java/a:A");
    update("//java/a:A");
    assertNoTargetsVisited();
  }

  @Test
  public void testDependencyAllCacheHits() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = ["y"],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
        )
        """);
    update("//java/a:x");
    Set<?> oldAnalyzedTargets = getSkyframeEvaluatedTargetKeys();
    assertThat(oldAnalyzedTargets.size()).isAtLeast(2); // could be greater due to implicit deps
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(1);
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(1);

    update("//java/a:y");
    assertThat(getSkyframeEvaluatedTargetKeys()).isEmpty();
  }

  @Test
  public void testSupersetNotAllCacheHits() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        # It's important that all targets are of the same rule class, otherwise the second update
        # call might analyze more than one extra target because of potential implicit dependencies.
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = ["y"],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
            deps = ["z"],
        )

        java_library(
            name = "z",
            srcs = ["C.java"],
        )
        """);
    update("//java/a:y");
    Set<?> oldAnalyzedTargets = getSkyframeEvaluatedTargetKeys();
    assertThat(oldAnalyzedTargets.size()).isAtLeast(3); // could be greater due to implicit deps
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(0);
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(1);
    update("//java/a:x");
    Set<?> newAnalyzedTargets = getSkyframeEvaluatedTargetKeys();
    // Source target and x.
    assertThat(newAnalyzedTargets).hasSize(2);
    assertThat(countObjectsPartiallyMatchingRegex(newAnalyzedTargets, "//java/a:x")).isEqualTo(1);
    assertThat(countObjectsPartiallyMatchingRegex(newAnalyzedTargets, "//java/a:y")).isEqualTo(0);
  }

  @Test
  public void testExtraActions() throws Exception {
    scratch.file(
        "java/com/google/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='a', srcs=['A.java'])
        """);
    scratch.file(
        "java/com/google/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='b', srcs=['B.java'])
        """);
    scratch.file(
        "extra/BUILD",
        """
        extra_action(
            name = "extra",
            cmd = "",
            out_templates = ["$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        """);

    useConfiguration("--experimental_action_listener=//extra:listener");
    update("//java/com/google/a:a");
    update("//java/com/google/b:b");
  }

  @Test
  public void testExtraActionsCaching() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='a', srcs=['A.java'])
        """);
    scratch.file(
        "extra/BUILD",
        """
        extra_action(
            name = "extra",
            cmd = "echo $(EXTRA_ACTION_FILE)",
            out_templates = ["$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        """);
    useConfiguration("--experimental_action_listener=//extra:listener");

    update("//java/a:a");
    getConfiguredTarget("//java/a:a");

    scratch.overwriteFile(
        "extra/BUILD",
        """
        extra_action(
            name = "extra",
            # <-- change here
            cmd = "echo $(BUG)",
            out_templates = ["$(OWNER_LABEL_DIGEST)_$(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        """);
    reporter.removeHandler(failFastHandler);
    ViewCreationFailedException e =
        assertThrows(ViewCreationFailedException.class, () -> update("//java/a:a"));
    assertThat(e).hasMessageThat().contains("Analysis of target '//java/a:a' failed");
    assertContainsEvent("$(BUG) not defined");
  }

  @Test
  public void testConfigurationCachingWithWarningReplay() throws Exception {
    useConfiguration("--strip=always", "--copt=-g");
    update();
    assertContainsEvent("Debug information will be generated and then stripped away");
    eventCollector.clear();
    update();
    assertContainsEvent("Debug information will be generated and then stripped away");
  }

  @Test
  public void testSkyframeCacheInvalidationBuildFileChange() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);
    String aTarget = "//java/a:A";
    update(aTarget);
    ConfiguredTarget firstCT = getConfiguredTarget(aTarget);

    scratch.overwriteFile(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["B.java"],
        )
        """);

    update(aTarget);
    ConfiguredTarget updatedCT = getConfiguredTarget(aTarget);
    assertThat(updatedCT).isNotSameInstanceAs(firstCT);

    update(aTarget);
    ConfiguredTarget updated2CT = getConfiguredTarget(aTarget);
    assertThat(updated2CT).isSameInstanceAs(updatedCT);
  }

  @Test
  public void testSkyframeDifferentPackagesInvalidation() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        """);

    scratch.file(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        """);

    String aTarget = "//java/a:A";
    update(aTarget);
    ConfiguredTarget oldAConfTarget = getConfiguredTarget(aTarget);
    String bTarget = "//java/b:B";
    update(bTarget);
    ConfiguredTarget oldBConfTarget = getConfiguredTarget(bTarget);

    scratch.overwriteFile(
        "java/b/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "B",
            srcs = ["C.java"],
        )
        """);

    update(aTarget);
    // Check that 'A' was not invalidated because 'B' was modified and invalidated.
    ConfiguredTarget newAConfTarget = getConfiguredTarget(aTarget);
    ConfiguredTarget newBConfTarget = getConfiguredTarget(bTarget);

    assertThat(newAConfTarget).isSameInstanceAs(oldAConfTarget);
    assertThat(newBConfTarget).isNotSameInstanceAs(oldBConfTarget);
  }

  private int countObjectsPartiallyMatchingRegex(
      Iterable<? extends Object> elements, String toStringMatching) {
    toStringMatching = ".*" + toStringMatching + ".*";
    int result = 0;
    for (Object o : elements) {
      if (o.toString().matches(toStringMatching)) {
        ++result;
      }
    }
    return result;
  }

  @Test
  public void testGetSkyframeEvaluatedTargetKeysOmitsCachedTargets() throws Exception {
    scratch.file(
        "java/a/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = [
                "w",
                "z",
            ],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
            deps = [
                "w",
                "z",
            ],
        )

        java_library(
            name = "z",
            srcs = ["C.java"],
        )

        java_library(
            name = "w",
            srcs = ["D.java"],
        )
        """);

    update("//java/a:x");
    Set<?> oldAnalyzedTargets = getSkyframeEvaluatedTargetKeys();
    assertThat(oldAnalyzedTargets.size()).isAtLeast(2); // could be greater due to implicit deps
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(1);
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(0);
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:z")).isEqualTo(1);
    assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:w")).isEqualTo(1);

    // Unless the build is not fully cached, we get notified about newly evaluated targets, as well
    // as cached top-level targets. For the two tests above to work correctly, we need to ensure
    // that getSkyframeEvaluatedTargetKeys() doesn't return these.
    update("//java/a:x", "//java/a:y", "//java/a:z");
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//java/a:y", 1) // Newly requested.
            .put("//java/a:B.java", 1)
            .put("//java/a:z", 0) // Fully cached.
            .buildOrThrow());
  }

  /** Test options class for testing diff-based analysis cache resetting. */
  public static final class DiffResetOptions extends FragmentOptions {
    public static final OptionDefinition PROBABLY_IRRELEVANT_OPTION =
        OptionsParser.getOptionDefinitionByName(DiffResetOptions.class, "probably_irrelevant");
    public static final OptionDefinition ALSO_IRRELEVANT_OPTION =
        OptionsParser.getOptionDefinitionByName(DiffResetOptions.class, "also_irrelevant");
    public static final PatchTransition CLEAR_IRRELEVANT =
        new PatchTransition() {
          @Override
          public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
            return ImmutableSet.of(DiffResetOptions.class);
          }

          @Override
          public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
            if (options.underlying().hasNoConfig()) {
              return options.underlying();
            }
            BuildOptionsView cloned = options.clone();
            cloned.get(DiffResetOptions.class).probablyIrrelevantOption = "(cleared)";
            cloned.get(DiffResetOptions.class).alsoIrrelevantOption = "(cleared)";
            return cloned.underlying();
          }
        };

    @Option(
        name = "probably_irrelevant",
        defaultValue = "(unset)",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is irrelevant to non-uses_irrelevant targets and is trimmed from them.")
    public String probablyIrrelevantOption;

    @Option(
        name = "also_irrelevant",
        defaultValue = "(unset)",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is irrelevant to non-uses_irrelevant targets and is trimmed from them.")
    public String alsoIrrelevantOption;

    @Option(
        name = "definitely_relevant",
        defaultValue = "(unset)",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is not trimmed and is used by all targets.")
    public String definitelyRelevantOption;

    @Option(
        name = "also_relevant",
        defaultValue = "(unset)",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is not trimmed and is used by all targets.")
    public String alsoRelevantOption;

    @Option(
        name = "host_relevant",
        defaultValue = "(unset)",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "This option is not trimmed and is used by all host targets.")
    public String hostRelevantOption;
  }

  /** Test fragment. */
  @StarlarkBuiltin(name = "test_diff_fragment", doc = "fragment for testing differy fragments")
  @RequiresOptions(options = {DiffResetOptions.class})
  public static final class DiffResetFragment extends Fragment implements StarlarkValue {
    public DiffResetFragment(BuildOptions buildOptions) {}
  }

  private void setupDiffResetTesting() throws Exception {
    ImmutableSet<OptionDefinition> optionsThatCanChange =
        ImmutableSet.of(
            DiffResetOptions.PROBABLY_IRRELEVANT_OPTION, DiffResetOptions.ALSO_IRRELEVANT_OPTION);
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DiffResetFragment.class);
    builder.overrideShouldInvalidateCacheForOptionDiffForTesting(
        (newOptions, changedOption, oldValue, newValue) -> {
          return !optionsThatCanChange.contains(changedOption);
        });
    builder.overrideTrimmingTransitionFactoryForTesting(
        new TransitionFactory<>() {
          @Override
          public ConfigurationTransition create(RuleTransitionData ruleData) {
            if (ruleData.rule().getRuleClassObject().getName().equals("uses_irrelevant")) {
              return NoTransition.INSTANCE;
            }
            return DiffResetOptions.CLEAR_IRRELEVANT;
          }

          @Override
          public TransitionType transitionType() {
            return TransitionType.RULE;
          }
        });
    useRuleClassProvider(builder.build());
    scratch.file(
        "test/lib.bzl",
        """
        def _empty_impl(ctx):
            pass

        normal_lib = rule(
            implementation = _empty_impl,
            fragments = ["test_diff_fragment"],
            attrs = {
                "deps": attr.label_list(),
                "host_deps": attr.label_list(cfg = "exec"),
            },
        )
        uses_irrelevant = rule(
            implementation = _empty_impl,
            fragments = ["test_diff_fragment"],
            attrs = {
                "deps": attr.label_list(),
                "host_deps": attr.label_list(cfg = "exec"),
            },
        )
        """);
    update();
  }

  @Test
  public void cacheNotClearedWhenOptionsStaySame() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        """);
    useConfiguration("--definitely_relevant=Testing");
    update("//test:top");
    update("//test:top");
    assertNoTargetsVisited();
  }

  @Test
  public void cacheClearedWhenNonAllowedOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        """);
    useConfiguration("--definitely_relevant=Test 1");
    update("//test:top");
    useConfiguration("--definitely_relevant=Test 2");
    update("//test:top");
    useConfiguration("--definitely_relevant=Test 1");
    update("//test:top");
    // these targets needed to be reanalyzed even though we built them in this configuration
    // just a moment ago
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 1)
            .put("//test:shared", 1)
            .buildOrThrow());
  }

  @Test
  public void cacheNotClearedForExecWhenNonExecOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            host_deps = [":shared"],
        )

        normal_lib(name = "shared")
        """);
    useConfiguration("--host_relevant=Test 1");
    update("//test:top");
    useConfiguration("--host_relevant=Test 2");
    update("//test:top");
    // //test:shared is in the exec configuration, and --host_relevant is not part of the exec
    // configuration. Therefore, //test:shared is not reanalyzed, even though //test:top is.
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 1)
            .put("//test:shared", 0)
            .buildOrThrow());
  }

  @Test
  public void cacheClearedForExecWhenExecOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            host_deps = [":shared"],
        )

        normal_lib(name = "shared")
        """);
    // --host_compilation_mode is part of the exec configuration.
    useConfiguration("--host_compilation_mode=opt");
    update("//test:top");
    useConfiguration("--host_compilation_mode=dbg");
    update("//test:top");

    // Now, //test:shared is reanalyzed, because --host_compilation_mode is part of the exec
    // configuration.
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 1)
            .put("//test:shared", 1)
            .buildOrThrow());

    // Return to the original configuration and check that the cache is not cleared.
    useConfiguration("--host_compilation_mode=opt");
    update("//test:top");
    // these targets needed to be reanalyzed even though we built them in this configuration
    // just a moment ago
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 1)
            .put("//test:shared", 1)
            .buildOrThrow());
  }

  @Test
  public void cacheNotClearedWhenAllowedOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        """);
    useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 1");
    update("//test:top");
    useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 2");
    update("//test:top");
    // the shared library got to reuse the cached value, while the entry point had to be rebuilt in
    // the new configuration
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 1)
            .put("//test:shared", 0)
            .buildOrThrow());
    useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 1");
    update("//test:top");
    // now we're back to the old configuration with no cache clears, so no work needed to be done
    assertNumberOfAnalyzedConfigurationsOfTargets(
        ImmutableMap.<String, Integer>builder()
            .put("//test:top", 0)
            .put("//test:shared", 0)
            .buildOrThrow());
  }

  @Test
  public void cacheNotClearedWhenRedundantDefinesChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--define=a=1", "--define=a=2");
    update("//test:top");
    useConfiguration("--define=a=2");
    update("//test:top");
    assertNumberOfAnalyzedConfigurationsOfTargets(ImmutableMap.of("//test:top", 0));
  }

  @Test
  public void noCacheClearMessageAfterCleanWithSameOptions() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration();
    update("//test:top");
    cleanSkyframe();
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void noCacheClearMessageAfterCleanWithDifferentOptions() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--definitely_relevant=before");
    update("//test:top");
    cleanSkyframe();
    useConfiguration("--definitely_relevant=after");
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void noCacheClearMessageAfterDiscardAnalysisCacheThenCleanWithSameOptions()
      throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--discard_analysis_cache");
    update("//test:top");
    cleanSkyframe();
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void noCacheClearMessageAfterDiscardAnalysisCacheThenCleanWithChangedOptions()
      throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--definitely_relevant=before", "--discard_analysis_cache");
    update("//test:top");
    cleanSkyframe();
    useConfiguration("--definitely_relevant=after", "--discard_analysis_cache");
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void cacheClearMessageAfterDiscardAnalysisCacheBuild() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1",
        "--probably_irrelevant=yeah",
        "--discard_analysis_cache");
    update("//test:top");
    eventCollector.clear();
    update("//test:top");
    assertContainsEvent("--discard_analysis_cache");
    assertDoesNotContainEvent("Build option");
    assertContainsEvent("discarding analysis cache");
  }

  @Test
  public void noCacheClearMessageAfterNonDiscardAnalysisCacheBuild() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--max_config_changes_to_show=-1", "--discard_analysis_cache");
    update("//test:top");
    useConfiguration("--max_config_changes_to_show=-1");
    update("//test:top");
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void noCacheClearMessageAfterIrrelevantOptionChanges() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--max_config_changes_to_show=-1", "--probably_irrelevant=old");
    update("//test:top");
    useConfiguration("--max_config_changes_to_show=-1", "--probably_irrelevant=new");
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void noCacheClearMessageAfterIrrelevantOptionChangesWithDiffDisabled() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--max_config_changes_to_show=0", "--probably_irrelevant=old");
    update("//test:top");
    useConfiguration("--max_config_changes_to_show=0", "--probably_irrelevant=new");
    eventCollector.clear();
    update("//test:top");
    assertNoEvents();
  }

  @Test
  public void cacheClearMessageAfterChangingPlatform() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1", "--platforms=" + TestConstants.PLATFORM_LABEL);
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1", "--platforms=" + TestConstants.PIII_PLATFORM_LABEL);
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent("Build option --platforms has changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterSingleRelevantOptionChanges() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--max_config_changes_to_show=-1", "--definitely_relevant=old");
    update("//test:top");
    useConfiguration("--max_config_changes_to_show=-1", "--definitely_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageDoesNotIncludeIrrelevantOptions() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1",
        "--definitely_relevant=old",
        "--probably_irrelevant=old",
        "--also_irrelevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1",
        "--definitely_relevant=new",
        "--probably_irrelevant=new",
        "--also_irrelevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageDoesNotIncludeUnchangedOptions() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1", "--definitely_relevant=old", "--also_relevant=fixed");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1", "--definitely_relevant=new", "--also_relevant=fixed");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterRelevantOptionChangeWithDiffDisabled() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--max_config_changes_to_show=0", "--definitely_relevant=old");
    update("//test:top");
    useConfiguration("--max_config_changes_to_show=0", "--definitely_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent("Build options have changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterTwoRelevantOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1", "--definitely_relevant=old", "--also_relevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1", "--definitely_relevant=new", "--also_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent(
        "Build options --also_relevant and --definitely_relevant have changed, "
            + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterMultipleRelevantOptionsChange() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1",
        "--definitely_relevant=old",
        "--also_relevant=old",
        "--host_relevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1",
        "--definitely_relevant=new",
        "--also_relevant=new",
        "--host_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent(
        "Build options --also_relevant, --definitely_relevant, and --host_relevant have changed, "
            + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterMultipleRelevantOptionsChangeWithDiffLimit() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=2",
        "--definitely_relevant=old",
        "--also_relevant=old",
        "--host_relevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=2",
        "--definitely_relevant=new",
        "--also_relevant=new",
        "--host_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent(
        "Build options --also_relevant, --definitely_relevant, and 1 more have changed, "
            + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterMultipleRelevantOptionsChangeWithSingleDiffLimit()
      throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=1",
        "--definitely_relevant=old",
        "--also_relevant=old",
        "--host_relevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=1",
        "--definitely_relevant=new",
        "--also_relevant=new",
        "--host_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertDoesNotContainEvent("--discard_analysis_cache");
    assertContainsEvent(
        "Build options --also_relevant and 2 more have changed, " + CACHE_DISCARD_WARNING);
  }

  @Test
  public void cacheClearMessageAfterDiscardAnalysisCacheBuildWithRelevantOptionChanges()
      throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration(
        "--max_config_changes_to_show=-1", "--discard_analysis_cache", "--definitely_relevant=old");
    update("//test:top");
    useConfiguration(
        "--max_config_changes_to_show=-1", "--discard_analysis_cache", "--definitely_relevant=new");
    eventCollector.clear();
    update("//test:top");
    assertContainsEvent("--discard_analysis_cache");
    assertDoesNotContainEvent("Build option");
    assertContainsEvent("discarding analysis cache");
  }

  @Test
  public void throwsIfAnalysisCacheIsDiscardedWhenOptionSet() throws Exception {
    setupDiffResetTesting();
    scratch.file(
        "test/BUILD",
        """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        """);
    useConfiguration("--definitely_relevant=old");

    // Set up the analysis cache
    update("//test:top");

    // Check if things work if the build options are not changed
    useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=old");
    update("//test:top");

    // Check if an error is raised when the build options are changed. Do it twice because
    // had already had a bug that the second invocation erroneously worked. See
    // https://github.com/bazelbuild/bazel/issues/23491 .
    useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=new");
    Throwable t = assertThrows(InvalidConfigurationException.class, () -> update("//test:top"));
    assertThat(t.getMessage().contains("analysis cache would have been discarded")).isTrue();

    t = assertThrows(InvalidConfigurationException.class, () -> update("//test:top"));
    assertThat(t.getMessage()).contains("analysis cache would have been discarded");

    // Check if going back to the original configuration works.
    useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=old");
    update("//test:top");

    // Now check if removing --noallow_analysis_cache_discard in fact allows discarding the cache.
    useConfiguration("--definitely_relevant=new");
    update("//test:top");
  }
}
