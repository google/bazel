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
package com.google.devtools.build.lib.pkgcache;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.PatternExpanded.TestSuiteExpansion;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.PatternExpandingError;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SkyframeExecutor#loadTargetPatternsWithFilters}. */
@RunWith(TestParameterInjector.class)
public final class LoadingPhaseRunnerTest {

  private LoadingPhaseTester tester;

  @BeforeClass
  public static void silenceLogger() {
    Logger.getLogger(BuildView.class.getName()).setLevel(Level.OFF);
  }

  @Before
  public void createLoadingPhaseTester() throws Exception {
    tester = new LoadingPhaseTester();
  }

  private static List<Label> getLabels(String... labels) {
    List<Label> result = new ArrayList<>();
    for (String label : labels) {
      result.add(Label.parseCanonicalUnchecked(label));
    }
    return result;
  }

  private void assertCircularSymlinksDuringTargetParsing(String targetPattern, String errorMessage)
      throws Exception {
    assertThrows(TargetParsingException.class, () -> tester.load(targetPattern));
    tester.assertContainsError(errorMessage);
    TargetPatternPhaseValue result = tester.loadKeepGoing(targetPattern);
    assertThat(result.hasError()).isTrue();
  }

  private TargetPatternPhaseValue assertNoErrors(TargetPatternPhaseValue loadingResult) {
    assertThat(loadingResult.hasError()).isFalse();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    tester.assertNoEvents();
    return loadingResult;
  }

  @Test
  public void testSmoke() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    TargetPatternPhaseValue loadingResult = assertNoErrors(tester.load("//base:hello"));
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//base:hello"));
    assertThat(loadingResult.getTestsToRunLabels()).isNull();
  }

  @Test
  public void testNonExistentPackage() throws Exception {
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//base:missing");
    assertThat(loadingResult.hasError()).isTrue();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    assertThat(loadingResult.getTargetLabels()).isEmpty();
    assertThat(loadingResult.getTestsToRunLabels()).isNull();
    tester.assertContainsError("Skipping '//base:missing': no such package 'base'");
    tester.assertContainsWarning("Target pattern parsing failed.");
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//base:missing");
  }

  @Test
  public void testNonExistentPackageWithoutKeepGoing() {
    assertThrows(TargetParsingException.class, () -> tester.load("//does/not/exist"));
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//does/not/exist");
  }

  @Test
  public void testNonExistentTarget() throws Exception {
    tester.addFile("base/BUILD");
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//base:missing");
    assertThat(loadingResult.hasError()).isTrue();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    assertThat(loadingResult.getTargetLabels()).isEmpty();
    assertThat(loadingResult.getTestsToRunLabels()).isNull();
    tester.assertContainsError("Skipping '//base:missing': no such target '//base:missing'");
    tester.assertContainsWarning("Target pattern parsing failed.");
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//base:missing");
  }

  @Test
  public void testExistingAndNonExistentTargetsWithKeepGoing() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.loadKeepGoing("//base:hello", "//base:missing");
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//base:missing");
    TargetParsingCompleteEvent event = tester.findPostOnce(TargetParsingCompleteEvent.class);
    assertThat(event.getOriginalTargetPattern()).containsExactly("//base:hello", "//base:missing");
    assertThat(event.getFailedTargetPatterns()).containsExactly("//base:missing");
  }

  @Test
  public void testRecursiveAllRules() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'base', srcs = ['base.txt'])");
    tester.addFile("base/foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.txt'])");
    tester.addFile("base/bar/BUILD", "filegroup(name = 'bar', srcs = ['bar.txt'])");
    TargetPatternPhaseValue loadingResult = tester.load("//base/...");
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//base", "//base/foo", "//base/bar"));

    loadingResult = tester.load("//base/bar/...");
    assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/bar"));
  }

  @Test
  public void testRecursiveAllTargets() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'base', srcs = ['base.txt'])");
    tester.addFile("base/foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.txt'])");
    tester.addFile("base/bar/BUILD", "filegroup(name = 'bar', srcs = ['bar.txt'])");
    TargetPatternPhaseValue loadingResult = tester.load("//base/...:*");
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(
            getLabels(
                "//base:BUILD",
                "//base:base",
                "//base:base.txt",
                "//base/foo:BUILD",
                "//base/foo:foo",
                "//base/foo:foo.txt",
                "//base/bar:BUILD",
                "//base/bar:bar",
                "//base/bar:bar.txt"));

    loadingResult = tester.load("//base/...:all-targets");
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(
            getLabels(
                "//base:BUILD",
                "//base:base",
                "//base:base.txt",
                "//base/foo:BUILD",
                "//base/foo:foo",
                "//base/foo:foo.txt",
                "//base/bar:BUILD",
                "//base/bar:bar",
                "//base/bar:bar.txt"));
  }

  @Test
  public void testNonExistentRecursive() throws Exception {
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//base/...");
    assertThat(loadingResult.hasError()).isTrue();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    assertThat(loadingResult.getTargetLabels()).isEmpty();
    assertThat(loadingResult.getTestsToRunLabels()).isNull();
    tester.assertContainsError("Skipping '//base/...': no targets found beneath 'base'");
    tester.assertContainsWarning("Target pattern parsing failed.");
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//base/...");
  }

  @Test
  public void testMistypedTarget() {
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load("foo//bar:missing"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "invalid package name 'foo//bar': package names may not contain '//' path separators");
    ParsingFailedEvent err = tester.findPostOnce(ParsingFailedEvent.class);
    assertThat(err.getPattern()).isEqualTo("foo//bar:missing");
  }

  @Test
  public void testEmptyTarget() {
    TargetParsingException e = assertThrows(TargetParsingException.class, () -> tester.load(""));
    assertThat(e).hasMessageThat().contains("invalid target name '': empty target name");
  }

  @Test
  public void testMistypedTargetKeepGoing() throws Exception {
    TargetPatternPhaseValue result = tester.loadKeepGoing("foo//bar:missing");
    assertThat(result.hasError()).isTrue();
    tester.assertContainsError(
        "invalid package name 'foo//bar': package names may not contain '//' path separators");
    ParsingFailedEvent err = tester.findPostOnce(ParsingFailedEvent.class);
    assertThat(err.getPattern()).isEqualTo("foo//bar:missing");
  }

  @Test
  public void testBadTargetPatternWithTest() throws Exception {
    tester.addFile("base/BUILD");
    TargetPatternPhaseValue loadingResult = tester.loadTestsKeepGoing("//base:missing");
    assertThat(loadingResult.hasError()).isTrue();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    assertThat(loadingResult.getTargetLabels()).isEmpty();
    assertThat(loadingResult.getTestsToRunLabels()).isEmpty();
    tester.assertContainsError("Skipping '//base:missing': no such target '//base:missing'");
    tester.assertContainsWarning("Target pattern parsing failed.");
  }

  @Test
  public void testManualTarget() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile("cc/BUILD", "cc_library(name = 'my_lib', srcs = ['lib.cc'], tags = ['manual'])");
    TargetPatternPhaseValue loadingResult = assertNoErrors(tester.load("//cc:all"));
    assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels());

    // Explicitly specified on the command line.
    loadingResult = assertNoErrors(tester.load("//cc:my_lib"));
    assertThat(loadingResult.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_lib"));
  }

  @Test
  public void testConfigSettingTarget() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "config/BUILD",
        """
        cc_library(
            name = "somelib",
            srcs = ["somelib.cc"],
            hdrs = ["somelib.h"],
        )

        config_setting(
            name = "configa",
            values = {"define": "foo=a"},
        )

        config_setting(
            name = "configb",
            values = {"define": "foo=b"},
        )
        """);
    TargetPatternPhaseValue result = assertNoErrors(tester.load("//config:all"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//config:somelib"));

    // Explicitly specified on the command line.
    result = assertNoErrors(tester.load("//config:configa"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//config:configa"));
  }

  @Test
  public void testNegativeTestDoesNotShowUpAtAll() throws Exception {
    tester.addFile(
        "my_test/BUILD",
        "load('//test_defs:foo_test.bzl', 'foo_test')",
        "foo_test(name = 'my_test', srcs = ['test.cc'])");
    assertNoErrors(tester.loadTests("-//my_test"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testNegativeTargetDoesNotShowUpAtAll() throws Exception {
    tester.addFile("my_library/BUILD", "cc_library(name = 'my_library', srcs = ['test.cc'])");
    assertNoErrors(tester.loadTests("-//my_library"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testTestMinusAllTests() throws Exception {
    tester.addFile(
        "test/BUILD",
        """
        cc_library(name = "bar1")

        cc_test(
            name = "test",
            tags = ["manual"],
            deps = [":bar1"],
        )
        """);
    TargetPatternPhaseValue result = tester.loadTests("//test:test", "-//test:all");
    assertThat(result.hasError()).isFalse();
    assertThat(result.hasPostExpansionError()).isFalse();
    tester.assertContainsWarning("All specified test targets were excluded by filters");
    assertThat(tester.getFilteredTargets()).containsExactlyElementsIn(getLabels("//test:test"));
    assertThat(result.getTargetLabels()).isEmpty();
  }

  @Test
  public void testFindLongestPrefix() throws Exception {
    tester.addFile("base/BUILD", "exports_files(['bar', 'bar/bar', 'bar/baz'])");
    TargetPatternPhaseValue result = assertNoErrors(tester.load("base/bar/baz"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:bar/baz"));
    result = assertNoErrors(tester.load("base/bar"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:bar"));
  }

  @Test
  public void testMultiSegmentLabel() throws Exception {
    tester.addFile("base/foo/BUILD", "exports_files(['bar/baz'])");
    TargetPatternPhaseValue value = assertNoErrors(tester.load("base/foo:bar/baz"));
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/foo:bar/baz"));
  }

  @Test
  public void testMultiSegmentLabelRelative() throws Exception {
    tester.addFile("base/foo/BUILD", "exports_files(['bar/baz'])");
    tester.setRelativeWorkingDirectory("base");
    TargetPatternPhaseValue value = assertNoErrors(tester.load("foo:bar/baz"));
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base/foo:bar/baz"));
  }

  @Test
  public void testDeletedPackage() throws Exception {
    tester.addFile("base/BUILD", "exports_files(['base'])");
    tester.setDeletedPackages(PackageIdentifier.createInMainRepo("base"));
    TargetPatternPhaseValue result = tester.loadKeepGoing("//base");
    assertThat(result.hasError()).isTrue();
    tester.assertContainsError(
        "no such package 'base': Package is considered deleted due to --deleted_packages");
    ParsingFailedEvent err = tester.findPostOnce(ParsingFailedEvent.class);
    assertThat(err.getPattern()).isEqualTo("//base");
  }

  private void writeBuildFilesForTestFiltering() throws Exception {
    tester.addFile(
        "tests/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(
            name = "t1",
            size = "small",
            srcs = ["pass.sh"],
            local = 1,
        )

        foo_test(
            name = "t2",
            size = "medium",
            srcs = ["pass.sh"],
        )

        foo_test(
            name = "t3",
            srcs = ["pass.sh"],
            tags = [
                "local",
                "manual",
            ],
        )
        """);
  }

  @Test
  public void testTestFiltering() throws Exception {
    writeBuildFilesForTestFiltering();
    TargetPatternPhaseValue loadingResult = assertNoErrors(tester.loadTests("//tests:all"));
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(loadingResult.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testTestFilteringIncludingManual() throws Exception {
    writeBuildFilesForTestFiltering();
    tester.useLoadingOptions("--build_manual_tests");
    TargetPatternPhaseValue loadingResult = assertNoErrors(tester.loadTests("//tests:all"));
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2", "//tests:t3"));
    assertThat(loadingResult.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testTestFilteringBuildTestsOnly() throws Exception {
    writeBuildFilesForTestFiltering();
    tester.useLoadingOptions("--build_tests_only");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//tests:all"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testTestFilteringSize() throws Exception {
    writeBuildFilesForTestFiltering();
    tester.useLoadingOptions("--test_size_filters=small");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//tests:all"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t2"));
    assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//tests:t1"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).isEmpty();
  }

  @Test
  public void testTestFilteringSizeAndBuildTestsOnly() throws Exception {
    writeBuildFilesForTestFiltering();
    tester.useLoadingOptions("--test_size_filters=small", "--build_tests_only");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//tests:all"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//tests:t1"));
    assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//tests:t1"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).containsExactlyElementsIn(getLabels("//tests:t2"));
  }

  @Test
  public void testTestFilteringLocalAndBuildTestsOnly() throws Exception {
    writeBuildFilesForTestFiltering();
    tester.useLoadingOptions("--test_tag_filters=local", "--build_tests_only");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//tests:all", "//tests:t3"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t3"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//tests:t1", "//tests:t3"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets()).containsExactlyElementsIn(getLabels("//tests:t2"));
  }

  @Test
  public void testTestSuiteExpansion() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        """);
    TargetPatternPhaseValue loadingResult = assertNoErrors(tester.loadTests("//cc:tests"));
    assertThat(loadingResult.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_test"));
    assertThat(loadingResult.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_test"));
    assertThat(tester.getOriginalTargets())
        .containsExactlyElementsIn(getLabels("//cc:tests", "//cc:my_test"));
    assertThat(tester.getTestSuiteTargets())
        .containsExactly(Label.parseCanonicalUnchecked("//cc:tests"));
  }

  @Test
  public void testTestSuiteExpansionFails() throws Exception {
    tester.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//nonexistent:my_test'])");
    tester.useLoadingOptions("--build_tests_only");
    TargetPatternPhaseValue loadingResult = tester.loadTestsKeepGoing("//ts:tests");
    assertThat(loadingResult.hasError()).isTrue();
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
    tester.assertContainsError("no such package 'nonexistent'");
  }

  @Test
  public void testTestSuiteExpansionFailsForBuild() throws Exception {
    tester.addFile("ts/BUILD", "test_suite(name = 'tests', tests = [':nonexistent_test'])");
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//ts:tests");
    assertThat(loadingResult.hasError()).isFalse();
    assertThat(loadingResult.hasPostExpansionError()).isTrue();
    tester.assertContainsError(
        "expecting a test or a test_suite rule but '//ts:nonexistent_test' is not one");
  }

  @Test
  public void failureWhileLoadingTestsForTestSuiteKeepGoing() throws Exception {
    tester.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//pkg:tests'])");
    tester.addFile(
        "pkg/BUILD",
        """
        test_suite(name = "tests")

        test_suite()
        """);
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//ts:tests");
    assertThat(loadingResult.hasError()).isFalse();
    assertThat(loadingResult.hasPostExpansionError()).isTrue();
    tester.assertContainsError("test_suite rule has no 'name' attribute");
  }

  @Test
  public void failureWhileLoadingTestsForTestSuiteNoKeepGoing() throws Exception {
    tester.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//pkg:tests'])");
    tester.addFile(
        "pkg/BUILD",
        """
        test_suite(name = "tests")

        test_suite()
        """);
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load("//ts:tests"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("error loading package 'pkg': Package 'pkg' contains errors");
    tester.assertContainsError("test_suite rule has no 'name' attribute");
  }

  @Test
  public void testTestSuiteExpansionFailsMissingTarget() throws Exception {
    tester.addFile("other/BUILD", "");
    tester.addFile("ts/BUILD", "test_suite(name = 'tests', tests = ['//other:no_such_test'])");
    TargetPatternPhaseValue result = tester.loadTestsKeepGoing("//ts:tests");
    assertThat(result.hasError()).isTrue();
    assertThat(result.hasPostExpansionError()).isTrue();
    tester.assertContainsError("no such target '//other:no_such_test'");
  }

  @Test
  public void testTestSuiteExpansionFailsMultipleSuites() throws Exception {
    tester.addFile("other/BUILD", "");
    tester.addFile(
        "ts/BUILD",
        """
        test_suite(
            name = "a",
            tests = ["//other:no_such_test"],
        )

        test_suite(
            name = "b",
            tests = [],
        )
        """);
    TargetPatternPhaseValue result = tester.loadTestsKeepGoing("//ts:all");
    assertThat(result.hasError()).isTrue();
    assertThat(result.hasPostExpansionError()).isTrue();
    tester.assertContainsError("no such target '//other:no_such_test'");
  }

  @Test
  public void testTestSuiteOverridesManualWithBuildTestsOnly() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(
            name = "foo",
            srcs = ["foo.sh"],
            tags = ["manual"],
        )

        foo_test(
            name = "bar",
            srcs = ["bar.sh"],
            tags = ["manual"],
        )

        foo_test(
            name = "baz",
            srcs = ["baz.sh"],
        )

        test_suite(
            name = "foo_suite",
            tests = [
                ":baz",
                ":foo",
            ],
        )
        """);
    tester.useLoadingOptions("--build_tests_only");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//foo:all"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:baz"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:baz"));
    assertThat(tester.getFilteredTargets()).isEmpty();
    assertThat(tester.getTestFilteredTargets())
        .containsExactlyElementsIn(getLabels("//foo:foo_suite"));
  }

  /** Regression test for bug: "subtracting tests from test doesn't work" */
  @Test
  public void testFilterNegativeTestFromTestSuite() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [
                ":my_other_test",
                ":my_test",
            ],
        )
        """);
    TargetPatternPhaseValue result =
        assertNoErrors(tester.loadTests("//cc:tests", "-//cc:my_test"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_other_test", "//cc:my_test"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_other_test"));
  }

  /** Regression test for bug: "blaze doesn't seem to respect target subtractions" */
  @Test
  public void testNegativeTestSuiteExpanded() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )

        test_suite(
            name = "all_tests",
            tests = ["my_other_test"],
        )
        """);
    TargetPatternPhaseValue result =
        assertNoErrors(tester.loadTests("//cc:all_tests", "-//cc:tests"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_other_test"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_other_test"));
  }

  @Test
  public void testTestSuiteIsSubtracted() throws Exception {
    // Test suites are expanded for each target pattern in sequence, not the whole set of target
    // patterns after all the inclusions and exclusions are processed.
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        """);
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//cc:all", "-//cc:tests"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_test", "//cc:my_other_test"));
    assertThat(result.getTestsToRunLabels())
        .containsExactlyElementsIn(getLabels("//cc:my_other_test"));
  }

  /** Regression test for bug: "blaze test "no targets found" warning now fatal" */
  @Test
  public void testNoTestsInRecursivePattern() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile("foo/BUILD", "cc_library(name = 'foo', srcs = ['foo.cc'])");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//foo/..."));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//foo"));
    assertThat(result.getTestsToRunLabels()).isEmpty();
  }

  @Test
  public void testComplexTestSuite() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "test1",
            srcs = ["test.cc"],
        )

        cc_test(
            name = "test2",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "empty",
            tags = ["impossible"],
            tests = [],
        )

        test_suite(
            name = "suite1",
            tests = [
                "empty",
                "test1",
            ],
        )

        test_suite(
            name = "suite2",
            tests = ["test2"],
        )

        test_suite(
            name = "all_tests",
            tests = [
                "suite1",
                "suite2",
            ],
        )
        """);
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//cc:all_tests"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//cc:test1", "//cc:test2"));
  }

  @Test
  public void testAllExcludesManualTest() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        cc_test(
            name = "my_other_test",
            srcs = ["other_test.cc"],
            tags = ["manual"],
        )
        """);
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//cc:all"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//cc:my_test"));
    assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//cc:my_test"));
  }

  @Test
  public void testBuildFilterDoesNotApplyToTests() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "foo",
            srcs = ["foo.sh"],
        )

        foo_library(
            name = "lib",
            srcs = ["lib.sh"],
        )

        foo_library(
            name = "nofoo",
            srcs = ["nofoo.sh"],
            tags = ["nofoo"],
        )
        """);
    tester.useLoadingOptions("--build_tag_filters=nofoo");
    TargetPatternPhaseValue result = assertNoErrors(tester.loadTests("//foo:all"));
    assertThat(result.getTargetLabels())
        .containsExactlyElementsIn(getLabels("//foo:foo", "//foo:nofoo"));
    assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels("//foo:foo"));
  }

  /**
   * Regression test for bug: "blaze is lying to me about what tests exist (have been specified)"
   */
  @Test
  public void testTotalNegationEmitsWarning() throws Exception {
    AnalysisMock.get().ccSupport().setup(tester.mockToolsConfig);
    tester.addFile(
        "cc/BUILD",
        """
        cc_test(
            name = "my_test",
            srcs = ["test.cc"],
        )

        test_suite(
            name = "tests",
            tests = [":my_test"],
        )
        """);
    TargetPatternPhaseValue result = tester.loadTests("//cc:tests", "-//cc:my_test");
    tester.assertContainsWarning("All specified test targets were excluded by filters");
    assertThat(result.getTestsToRunLabels()).containsExactlyElementsIn(getLabels());
  }

  @Test
  public void testRepeatedSameLoad() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    TargetPatternPhaseValue firstResult = assertNoErrors(tester.load("//base:hello"));
    TargetPatternPhaseValue secondResult = assertNoErrors(tester.load("//base:hello"));
    assertThat(secondResult.getTargetLabels()).isEqualTo(firstResult.getTargetLabels());
    assertThat(secondResult.getTestsToRunLabels()).isEqualTo(firstResult.getTestsToRunLabels());
  }

  /**
   * Tests whether globs can update correctly when a new file is added.
   *
   * <p>The usage of {@link LoadingPhaseTester#sync()} triggers this via {@link
   * SkyframeExecutor#invalidateFilesUnderPathForTesting}.
   */
  @Test
  public void testGlobPicksUpNewFile() throws Exception {
    tester.addFile("foo/BUILD", "filegroup(name='x', srcs=glob(['*.y']))");
    tester.addFile("foo/a.y");
    Label label =
        Iterables.getOnlyElement(assertNoErrors(tester.load("//foo:x")).getTargetLabels());
    Target result = tester.getTarget(label.toString());
    assertThat(
            Iterables.transform(
                result.getAssociatedRule().getLabels(), Functions.toStringFunction()))
        .containsExactly("//foo:a.y");

    tester.addFile("foo/b.y");
    tester.sync();
    label = Iterables.getOnlyElement(assertNoErrors(tester.load("//foo:x")).getTargetLabels());
    result = tester.getTarget(label.toString());
    assertThat(
            Iterables.transform(
                result.getAssociatedRule().getLabels(), Functions.toStringFunction()))
        .containsExactly("//foo:a.y", "//foo:b.y");
  }

  /** Regression test: handle symlink cycles gracefully. */
  @Test
  public void testCycleReporting_symlinkCycleDuringTargetParsing() throws Exception {
    tester.addFile("hello/BUILD", "cc_library(name = 'a', srcs = glob(['*.cc']))");
    Path buildFilePath = tester.getWorkspace().getRelative("hello/BUILD");
    Path dirPath = buildFilePath.getParentDirectory();
    Path fooFilePath = dirPath.getRelative("foo.cc");
    Path barFilePath = dirPath.getRelative("bar.cc");
    Path bazFilePath = dirPath.getRelative("baz.cc");
    fooFilePath.createSymbolicLink(barFilePath);
    barFilePath.createSymbolicLink(bazFilePath);
    bazFilePath.createSymbolicLink(fooFilePath);
    assertCircularSymlinksDuringTargetParsing("//hello:a", "Too many levels of symbolic links");
  }

  @Test
  public void testRecursivePatternWithCircularSymlink() throws Exception {
    tester.getWorkspace().getChild("broken").createDirectory();

    // Create a circular symlink.
    tester
        .getWorkspace()
        .getRelative(PathFragment.create("broken/BUILD"))
        .createSymbolicLink(PathFragment.create("BUILD"));

    assertCircularSymlinksDuringTargetParsing("//broken/...", "circular symlinks detected");
  }

  @Test
  public void testRecursivePatternWithTwoCircularSymlinks() throws Exception {
    tester.getWorkspace().getChild("broken").createDirectory();

    // Create a circular symlink.
    tester
        .getWorkspace()
        .getRelative(PathFragment.create("broken/BUILD"))
        .createSymbolicLink(PathFragment.create("x"));
    tester
        .getWorkspace()
        .getRelative(PathFragment.create("broken/x"))
        .createSymbolicLink(PathFragment.create("BUILD"));

    assertCircularSymlinksDuringTargetParsing("//broken/...", "circular symlinks detected");
  }

  @Test
  public void testSuiteInSuite() throws Exception {
    tester.addFile(
        "suite/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "a",
            tests = [":b"],
        )

        test_suite(
            name = "b",
            tests = [":c"],
        )

        foo_test(
            name = "c",
            srcs = ["test.cc"],
        )
        """);
    TargetPatternPhaseValue result = assertNoErrors(tester.load("//suite:a"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//suite:c"));
  }

  @Test
  public void testTopLevelTargetErrorsPrintedExactlyOnce_noKeepGoing() throws Exception {
    tester.addFile(
        "bad/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "bad",
            srcs = ["bad.sh"],
        )

        fail("some error")
        """);
    assertThrows(TargetParsingException.class, () -> tester.load("//bad"));
    tester.assertContainsEventWithFrequency("some error", 1);
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//bad");
  }

  @Test
  public void testTopLevelTargetErrorsPrintedExactlyOnce_keepGoing() throws Exception {
    tester.addFile(
        "bad/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "bad",
            srcs = ["bad.sh"],
        )

        fail("some error")
        """);
    TargetPatternPhaseValue result = tester.loadKeepGoing("//bad");
    assertThat(result.hasError()).isTrue();
    tester.assertContainsEventWithFrequency("some error", 1);
  }

  @Test
  public void testCompileOneDependency() throws Exception {
    tester.addFile("base/BUILD", "cc_library(name = 'hello', srcs = ['hello.cc'])");
    tester.useLoadingOptions("--compile_one_dependency");
    TargetPatternPhaseValue result = assertNoErrors(tester.load("base/hello.cc"));
    assertThat(result.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"));
  }

  @Test
  public void testCompileOneDependencyNonExistentSource() throws Exception {
    tester.addFile("base/BUILD", "cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])");
    tester.useLoadingOptions("--compile_one_dependency");
    try {
      TargetPatternPhaseValue loadingResult = tester.load("base/hello.cc");
      assertThat(loadingResult.hasPostExpansionError()).isFalse();
    } catch (LoadingFailedException expected) {
      tester.assertContainsError("no such package 'bad'");
    }
  }

  @Test
  public void testCompileOneDependencyNonExistentSourceKeepGoing() throws Exception {
    tester.addFile("base/BUILD", "cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])");
    tester.useLoadingOptions("--compile_one_dependency");
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("base/hello.cc");
    assertThat(loadingResult.hasPostExpansionError()).isFalse();
  }

  @Test
  public void testCompileOneDependencyReferencesFile() throws Exception {
    tester.addFile("base/BUILD", "cc_library(name = 'hello', srcs = ['hello.cc', '//bad:bad.cc'])");
    tester.useLoadingOptions("--compile_one_dependency");
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load("//base:hello"));
    assertThat(e)
        .hasMessageThat()
        .contains("--compile_one_dependency target '//base:hello' must be a file");
  }

  @Test
  public void testParsingFailureReported() throws Exception {
    TargetPatternPhaseValue loadingResult = tester.loadKeepGoing("//does_not_exist");
    assertThat(loadingResult.hasError()).isTrue();
    ParsingFailedEvent event = tester.findPostOnce(ParsingFailedEvent.class);
    assertThat(event.getPattern()).isEqualTo("//does_not_exist");
    assertThat(event.getMessage()).contains("BUILD file not found");
  }

  @Test
  public void testCyclesKeepGoing() throws Exception {
    tester.addFile("test/BUILD", "load(':cycle1.bzl', 'make_cycle')");
    tester.addFile("test/cycle1.bzl", "load(':cycle2.bzl', 'make_cycle')");
    tester.addFile("test/cycle2.bzl", "load(':cycle1.bzl', 'make_cycle')");
    // The skyframe target pattern evaluator isn't able to provide partial results in the presence
    // of cycles, so it simply raises an exception rather than returning an empty result.
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load("//test:cycle1"));
    assertThat(e).hasMessageThat().contains("cycles detected");
    tester.assertContainsEventWithFrequency("cycle detected in extension", 1);
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//test:cycle1");
  }

  @Test
  public void testCyclesNoKeepGoing() throws Exception {
    tester.addFile("test/BUILD", "load(':cycle1.bzl', 'make_cycle')");
    tester.addFile("test/cycle1.bzl", "load(':cycle2.bzl', 'make_cycle')");
    tester.addFile("test/cycle2.bzl", "load(':cycle1.bzl', 'make_cycle')");
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load("//test:cycle1"));
    assertThat(e).hasMessageThat().contains("cycles detected");
    tester.assertContainsEventWithFrequency("cycle detected in extension", 1);
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//test:cycle1");
  }

  @Test
  public void mapsOriginalPatternsToLabels() throws Exception {
    tester.addFile("test/a/BUILD", "cc_library(name = 'a_lib', srcs = ['a.cc'])");
    tester.addFile("test/b/BUILD", "cc_library(name = 'b_lib', srcs = ['b.cc'])");

    tester.load("test/a:all", "test/b:all", "test/...");

    assertThat(tester.getOriginalPatternsToLabels())
        .containsExactly(
            "test/a:all", Label.parseCanonicalUnchecked("//test/a:a_lib"),
            "test/b:all", Label.parseCanonicalUnchecked("//test/b:b_lib"),
            "test/...", Label.parseCanonicalUnchecked("//test/a:a_lib"),
            "test/...", Label.parseCanonicalUnchecked("//test/b:b_lib"));
  }

  @Test
  public void mapsOriginalPatternsToLabels_omitsExcludedTargets() throws Exception {
    tester.addFile("test/a/BUILD", "cc_library(name = 'a_lib', srcs = ['a.cc'])");

    tester.load("test/...", "-test/a:a_lib");

    assertThat(tester.getOriginalPatternsToLabels()).isEmpty();
  }

  @Test
  public void testSuiteCycle() throws Exception {
    tester.addFile(
        "BUILD", "test_suite(name = 'a', tests = [':b']); test_suite(name = 'b', tests = [':a'])");
    assertThat(
            assertThrows(TargetParsingException.class, () -> tester.loadKeepGoing("//:a", "//:b")))
        .hasMessageThat()
        .contains("cycles detected");
    assertThat(tester.assertContainsError("cycle in dependency graph").toString())
        .containsMatch("in test_suite rule //:.: cycle in dependency graph");
    PatternExpandingError err = tester.findPostOnce(PatternExpandingError.class);
    assertThat(err.getPattern()).containsExactly("//:a", "//:b");
  }

  @Test
  public void testSuiteExpansions_emptyIfNoTestSuitesRequested() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["a"],
        )

        foo_test(
            name = "t",
            srcs = [],
        )
        """);

    tester.load("//foo:t");

    assertThat(tester.getTestSuiteExpansions()).isEmpty();
  }

  @Test
  public void testSuiteExpansions_includesEmptyTestSuite() throws Exception {
    tester.addFile("foo/BUILD", "test_suite(name = 's', tests = [])");

    tester.load("//foo:s");

    assertThat(tester.getTestSuiteExpansions())
        .containsExactly(TestSuiteExpansion.newBuilder().setSuiteLabel("//foo:s").build());
  }

  @Test
  public void testSuiteExpansions_singleTestSuite() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = [
                "t1",
                "t2",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        """);

    tester.load("//foo:s");

    assertThat(tester.getTestSuiteExpansions())
        .ignoringRepeatedFieldOrder()
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s")
                .addTestLabels("//foo:t1")
                .addTestLabels("//foo:t2")
                .build());
  }

  @Test
  public void testSuiteExpansions_multipleTestSuites() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "t1",
                "t2",
            ],
        )

        test_suite(
            name = "s2",
            tests = ["t3"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        """);

    tester.load("//foo:s1", "//foo:s2");

    assertThat(tester.getTestSuiteExpansions())
        .ignoringRepeatedFieldOrder()
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s1")
                .addTestLabels("//foo:t1")
                .addTestLabels("//foo:t2")
                .build(),
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s2")
                .addTestLabels("//foo:t3")
                .build());
  }

  @Test
  public void testSuiteExpansions_overlappingTestSuites() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "t1",
                "t2",
            ],
        )

        test_suite(
            name = "s2",
            tests = [
                "t2",
                "t3",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        """);

    tester.load("//foo:s1", "//foo:s2");

    assertThat(tester.getTestSuiteExpansions())
        .ignoringRepeatedFieldOrder()
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s1")
                .addTestLabels("//foo:t1")
                .addTestLabels("//foo:t2")
                .build(),
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s2")
                .addTestLabels("//foo:t2")
                .addTestLabels("//foo:t3")
                .build());
  }

  @Test
  public void testSuiteExpansions_nestedTestSuite() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s1",
            tests = [
                "s2",
                "t1",
            ],
        )

        test_suite(
            name = "s2",
            tests = [
                "t2",
                "t3",
            ],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )

        foo_test(
            name = "t3",
            srcs = [],
        )
        """);

    tester.load("//foo:s1");

    assertThat(tester.getTestSuiteExpansions())
        .ignoringRepeatedFieldOrder()
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s1")
                .addTestLabels("//foo:t1")
                .addTestLabels("//foo:t2")
                .addTestLabels("//foo:t3")
                .build());
  }

  @Test
  public void testSuiteExpansions_includesTestMatchingNegativePattern() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t"],
        )

        foo_test(
            name = "t",
            srcs = [],
        )
        """);

    tester.load("//foo:s", "-//foo:t");

    assertThat(tester.getTestSuiteExpansions())
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s")
                .addTestLabels("//foo:t")
                .build());
  }

  @Test
  public void testSuiteExpansions_presentWhenSuiteMatchesWildcard() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t1"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        """);

    tester.load("//foo:all");

    assertThat(tester.getTestSuiteExpansions())
        .containsExactly(
            TestSuiteExpansion.newBuilder()
                .setSuiteLabel("//foo:s")
                .addTestLabels("//foo:t1")
                .build());
  }

  @Test
  public void testSuiteExpansions_excludesSuiteMatchingNegativePatten() throws Exception {
    tester.addFile(
        "foo/BUILD",
        """
        load("//test_defs:foo_test.bzl", "foo_test")
        test_suite(
            name = "s",
            tests = ["t1"],
        )

        foo_test(
            name = "t1",
            srcs = [],
        )

        foo_test(
            name = "t2",
            srcs = [],
        )
        """);

    tester.load("//foo:all", "-//foo:s");

    assertThat(tester.getTestSuiteExpansions()).isEmpty();
  }

  @Test
  public void testWildcard() throws Exception {
    tester.addFile(
        "foo/lib/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'lib2', srcs = ['foo.cc'])");
    TargetPatternPhaseValue value = assertNoErrors(tester.load("//foo/lib:all-targets"));
    assertThat(value.getTargetLabels())
        .containsExactlyElementsIn(
            getLabels("//foo/lib:BUILD", "//foo/lib:lib2", "//foo/lib:foo.cc"));

    value = assertNoErrors(tester.load("//foo/lib:*"));
    assertThat(value.getTargetLabels())
        .containsExactlyElementsIn(
            getLabels("//foo/lib:BUILD", "//foo/lib:lib2", "//foo/lib:foo.cc"));
  }

  @Test
  public void testWildcardConflict() throws Exception {
    tester.addFile(
        "foo/lib/BUILD",
        """
        cc_library(name = "lib1")

        cc_library(name = "lib2")

        cc_library(name = "all-targets")

        cc_library(name = "all")
        """);

    assertWildcardConflict("//foo/lib:all", ":all");
    assertWildcardConflict("//foo/lib:all-targets", ":all-targets");
  }

  private void assertWildcardConflict(String label, String suffix) throws Exception {
    TargetPatternPhaseValue value = tester.load(label);
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels(label));
    tester.assertContainsWarning(
        String.format(
            "The target pattern '%s' is ambiguous: '%s' is both a wildcard, and the name of an"
                + " existing cc_library rule; using the latter interpretation",
            label, suffix));
  }

  @Test
  public void testAbsolutePatternEndsWithSlashAll() throws Exception {
    tester.addFile("foo/all/BUILD", "cc_library(name = 'all')");
    TargetPatternPhaseValue value = tester.load("//foo/all");
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//foo/all:all"));
  }

  @Test
  public void testRelativeLabel() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    TargetPatternPhaseValue value = assertNoErrors(tester.load("base:hello"));
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"));
  }

  @Test
  public void testAbsoluteLabelWithOffset() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.setRelativeWorkingDirectory("base");
    TargetPatternPhaseValue value = assertNoErrors(tester.load("//base:hello"));
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"));
  }

  @Test
  public void testRelativeLabelWithOffset() throws Exception {
    tester.addFile("base/BUILD", "filegroup(name = 'hello', srcs = ['foo.txt'])");
    tester.setRelativeWorkingDirectory("base");
    TargetPatternPhaseValue value = assertNoErrors(tester.load(":hello"));
    assertThat(value.getTargetLabels()).containsExactlyElementsIn(getLabels("//base:hello"));
  }

  private void expectError(String pattern, String message) {
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> tester.load(pattern));
    assertThat(e).hasMessageThat().contains(message);
  }

  @Test
  public void testPatternWithSingleSlashIsError() {
    expectError(
        "/single/slash",
        "invalid target name '/single/slash': target names may not start with '/'");
  }

  @Test
  public void testPatternWithSingleSlashIsErrorAndOffset() {
    tester.setRelativeWorkingDirectory("base");
    expectError(
        "/single/slash",
        "invalid target name '/single/slash': target names may not start with '/'");
  }

  @Test
  public void testPatternWithTripleSlashIsError() {
    expectError(
        "///triple/slash",
        "invalid package name '/triple/slash': package names may not start with '/'");
  }

  @Test
  public void testPatternEndingWithSingleSlashIsError() {
    expectError("foo/", "invalid target name 'foo/': target names may not end with '/'");
  }

  @Test
  public void testPatternStartingWithDotDotSlash() {
    expectError(
        "../foo",
        "invalid target name '../foo': target names may not contain up-level references '..'");
  }

  private void runTestPackageLoadingError(boolean keepGoing, String... patterns) throws Exception {
    tester.addFile("bad/BUILD", "nope");
    if (keepGoing) {
      TargetPatternPhaseValue value = tester.loadKeepGoing(patterns);
      assertThat(value.hasError()).isTrue();
      tester.assertContainsWarning("Target pattern parsing failed");
    } else {
      TargetParsingException exn =
          assertThrows(TargetParsingException.class, () -> tester.load(patterns));
      assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException.class);
      assertThat(exn).hasCauseThat().hasMessageThat().contains("Package 'bad' contains errors");
    }
    tester.assertContainsError("/workspace/bad/BUILD:1:1: name 'nope' is not defined");
  }

  @Test
  public void testPackageLoadingError_keepGoing_explicitTarget() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ true, "//bad:BUILD");
  }

  @Test
  public void testPackageLoadingError_noKeepGoing_explicitTarget() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ false, "//bad:BUILD");
  }

  @Test
  public void testPackageLoadingError_keepGoing_targetsInPackage() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ true, "//bad:all");
  }

  @Test
  public void testPackageLoadingError_noKeepGoing_targetsInPackage() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ false, "//bad:all");
  }

  @Test
  public void testPackageLoadingError_keepGoing_targetsBeneathDirectory() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ true, "//bad/...");
  }

  @Test
  public void testPackageLoadingError_noKeepGoing_targetsBeneathDirectory() throws Exception {
    runTestPackageLoadingError(/*keepGoing=*/ false, "//bad/...");
  }

  @Test
  public void testPackageLoadingError_keepGoing_someGoodTargetsBeneathDirectory() throws Exception {
    tester.addFile(
        "good/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 't')\n");
    runTestPackageLoadingError(/* keepGoing= */ true, "//...");
  }

  @Test
  public void testPackageLoadingError_noKeepGoing_someGoodTargetsBeneathDirectory()
      throws Exception {
    tester.addFile(
        "good/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 't')\n");
    runTestPackageLoadingError(/* keepGoing= */ false, "//...");
  }

  private void runTestPackageFileInconsistencyError(boolean keepGoing, String... patterns)
      throws Exception {
    tester.addFile(
        "bad/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 't')\n");
    IOException ioExn = new IOException("nope");
    tester.throwExceptionOnGetInputStream(tester.getWorkspace().getRelative("bad/BUILD"), ioExn);
    if (keepGoing) {
      TargetPatternPhaseValue value = tester.loadKeepGoing(patterns);
      assertThat(value.hasError()).isTrue();
      tester.assertContainsWarning("Target pattern parsing failed");
      tester.assertContainsError("error loading package 'bad': nope");
    } else {
      TargetParsingException exn =
          assertThrows(TargetParsingException.class, () -> tester.load(patterns));
      assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException.class);
      assertThat(exn).hasCauseThat().hasMessageThat().contains("error loading package 'bad': nope");
    }
  }

  @Test
  public void testPackageFileInconsistencyError_keepGoing_explicitTarget() throws Exception {
    runTestPackageFileInconsistencyError(true, "//bad:BUILD");
  }

  @Test
  public void testPackageFileInconsistencyError_noKeepGoing_explicitTarget() throws Exception {
    runTestPackageFileInconsistencyError(false, "//bad:BUILD");
  }

  @Test
  public void testPackageFileInconsistencyError_keepGoing_targetsInPackage() throws Exception {
    runTestPackageFileInconsistencyError(true, "//bad:all");
  }

  @Test
  public void testPackageFileInconsistencyError_noKeepGoing_targetsInPackage() throws Exception {
    runTestPackageFileInconsistencyError(false, "//bad:all");
  }

  @Test
  public void testPackageFileInconsistencyError_keepGoing_argetsBeneathDirectory()
      throws Exception {
    runTestPackageFileInconsistencyError(true, "//bad/...");
  }

  @Test
  public void testPackageFileInconsistencyError_noKeepGoing_targetsBeneathDirectory()
      throws Exception {
    runTestPackageFileInconsistencyError(false, "//bad/...");
  }

  @Test
  public void testPackageFileInconsistencyError_keepGoing_someGoodTargetsBeneathDirectory()
      throws Exception {
    tester.addFile(
        "good/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 't')\n");
    runTestPackageFileInconsistencyError(true, "//...");
  }

  @Test
  public void testPackageFileInconsistencyError_noKeepGoing_someGoodTargetsBeneathDirectory()
      throws Exception {
    tester.addFile(
        "good/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 't')\n");
    runTestPackageFileInconsistencyError(false, "//...");
  }

  @Test
  public void extensionLoadingError(
      @TestParameter boolean keepGoing,
      @TestParameter({"//bad:BUILD", "//bad:all", "//bad/...", "//..."}) String pattern)
      throws Exception {
    tester.addFile("bad/f1.bzl", "nope");
    tester.addFile("bad/BUILD", "load(\":f1.bzl\", \"not_a_symbol\")");
    if (keepGoing) {
      TargetPatternPhaseValue value = tester.loadKeepGoing(pattern);
      assertThat(value.hasError()).isTrue();
      tester.assertContainsWarning("Target pattern parsing failed");
    } else {
      TargetParsingException exn =
          assertThrows(TargetParsingException.class, () -> tester.load(pattern));
      assertThat(exn).hasCauseThat().isInstanceOf(BuildFileContainsErrorsException.class);
      assertThat(exn)
          .hasCauseThat()
          .hasMessageThat()
          .contains("compilation of module 'bad/f1.bzl' failed");
      DetailedExitCode detailedExitCode = exn.getDetailedExitCode();
      assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.BUILD_FAILURE);
      assertThat(detailedExitCode.getFailureDetail().getPackageLoading().getCode())
          .isEqualTo(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR);
    }
    tester.assertContainsError("/workspace/bad/f1.bzl:1:1: name 'nope' is not defined");
  }

  private static final class LoadingPhaseTester {
    private final ManualClock clock = new ManualClock();
    private final CustomInMemoryFs fs = new CustomInMemoryFs(clock);
    private final Path workspace;

    private final SkyframeExecutor skyframeExecutor;

    private final List<Path> changes = new ArrayList<>();

    private LoadingOptions options;
    private final StoredEventHandler storedErrors;

    private PathFragment relativeWorkingDirectory = PathFragment.EMPTY_FRAGMENT;
    private TargetParsingCompleteEvent targetParsingCompleteEvent;
    private LoadingPhaseCompleteEvent loadingPhaseCompleteEvent;

    private final MockToolsConfig mockToolsConfig;

    LoadingPhaseTester() throws IOException, OptionsParsingException {
      this.workspace = fs.getPath("/workspace");
      workspace.createDirectory();
      mockToolsConfig = new MockToolsConfig(workspace);
      AnalysisMock analysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules();
      analysisMock.setupMockClient(mockToolsConfig);
      BlazeDirectories directories =
          new BlazeDirectories(
              new ServerDirectories(
                  fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/userRoot")),
              workspace,
              /*defaultSystemJavabase=*/ null,
              analysisMock.getProductName());
      workspace.getRelative("base").deleteTree();

      ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
      PackageFactory pkgFactory =
          analysisMock.getPackageFactoryBuilderForTesting(directories).build(ruleClassProvider, fs);
      PackageOptions options = Options.getDefaults(PackageOptions.class);
      storedErrors = new StoredEventHandler();
      skyframeExecutor =
          BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
              .setPkgFactory(pkgFactory)
              .setFileSystem(fs)
              .setDirectories(directories)
              .setActionKeyContext(new ActionKeyContext())
              .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
              .setSyscallCache(SyscallCache.NO_CACHE)
              .build();
      SkyframeExecutorTestHelper.process(skyframeExecutor);
      PathPackageLocator pkgLocator =
          PathPackageLocator.create(
              /*outputBase=*/ null,
              options.packagePath,
              storedErrors,
              workspace.asFragment(),
              workspace,
              BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
      PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
      packageOptions.defaultVisibility = RuleVisibility.PRIVATE;
      packageOptions.showLoadingProgress = true;
      packageOptions.globbingThreads = 7;
      skyframeExecutor.injectExtraPrecomputedValues(analysisMock.getPrecomputedValues());
      skyframeExecutor.preparePackageLoading(
          pkgLocator,
          packageOptions,
          defaultBuildLanguageOptions(),
          UUID.randomUUID(),
          ImmutableMap.of(),
          QuiescingExecutorsImpl.forTesting(),
          new TimestampGranularityMonitor(clock));
      skyframeExecutor.setActionEnv(ImmutableMap.of());
      this.options = Options.getDefaults(LoadingOptions.class);
    }

    private static BuildLanguageOptions defaultBuildLanguageOptions()
        throws OptionsParsingException {
      OptionsParser parser =
          OptionsParser.builder().optionsClasses(BuildLanguageOptions.class).build();
      parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
      return parser.getOptions(BuildLanguageOptions.class);
    }

    void useLoadingOptions(String... options) throws OptionsParsingException {
      OptionsParser parser = OptionsParser.builder().optionsClasses(LoadingOptions.class).build();
      parser.parse(ImmutableList.copyOf(options));
      this.options = parser.getOptions(LoadingOptions.class);
    }

    public void setRelativeWorkingDirectory(String relativeWorkingDirectory) {
      this.relativeWorkingDirectory = PathFragment.create(relativeWorkingDirectory);
    }

    public void setDeletedPackages(PackageIdentifier... packages) {
      skyframeExecutor.setDeletedPackages(ImmutableList.copyOf(packages));
    }

    public TargetPatternPhaseValue load(String... patterns) throws Exception {
      return loadWithFlags(/*keepGoing=*/ false, /*determineTests=*/ false, patterns);
    }

    TargetPatternPhaseValue loadKeepGoing(String... patterns) throws Exception {
      return loadWithFlags(/*keepGoing=*/ true, /*determineTests=*/ false, patterns);
    }

    TargetPatternPhaseValue loadTests(String... patterns) throws Exception {
      return loadWithFlags(/*keepGoing=*/ false, /*determineTests=*/ true, patterns);
    }

    TargetPatternPhaseValue loadTestsKeepGoing(String... patterns) throws Exception {
      return loadWithFlags(/*keepGoing=*/ true, /*determineTests=*/ true, patterns);
    }

    TargetPatternPhaseValue loadWithFlags(
        boolean keepGoing, boolean determineTests, String... patterns) throws Exception {
      sync();
      storedErrors.clear();
      TargetPatternPhaseValue result =
          skyframeExecutor.loadTargetPatternsWithFilters(
              storedErrors,
              ImmutableList.copyOf(patterns),
              relativeWorkingDirectory,
              options,
              // We load very few packages, and everything is in memory; two should be plenty.
              /* threadCount= */ 2,
              keepGoing,
              determineTests);
      this.targetParsingCompleteEvent = findPost(TargetParsingCompleteEvent.class);
      this.loadingPhaseCompleteEvent = findPost(LoadingPhaseCompleteEvent.class);
      if (!keepGoing) {
        assertThat(storedErrors.hasErrors()).isFalse();
      }
      return result;
    }

    public Path getWorkspace() {
      return workspace;
    }

    public void addFile(String fileName, String... content) throws IOException {
      Path buildFile = workspace.getRelative(fileName);
      Preconditions.checkState(!buildFile.exists());
      Path currentPath = buildFile;

      // Add the new file and all the directories that will be created by
      // createDirectoryAndParents()
      while (!currentPath.exists()) {
        changes.add(currentPath);
        currentPath = currentPath.getParentDirectory();
      }

      buildFile.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.writeContent(buildFile, Joiner.on('\n').join(content));
    }

    private void sync() throws InterruptedException, AbruptExitException {
      clock.advanceMillis(1);
      ModifiedFileSet.Builder builder = ModifiedFileSet.builder();
      for (Path path : changes) {
        if (!path.startsWith(workspace)) {
          continue;
        }

        PathFragment workspacePath = path.relativeTo(workspace);
        builder.modify(workspacePath);
      }
      ModifiedFileSet modified = builder.build();
      skyframeExecutor.invalidateFilesUnderPathForTesting(
          storedErrors, modified, Root.fromPath(workspace));

      changes.clear();
    }

    public Target getTarget(String targetName) throws Exception {
      StoredEventHandler eventHandler = new StoredEventHandler();
      Target target =
          getPkgManager().getTarget(eventHandler, Label.parseCanonicalUnchecked(targetName));
      assertThat(eventHandler.hasErrors()).isFalse();
      return target;
    }

    private PackageManager getPkgManager() {
      return skyframeExecutor.getPackageManager();
    }

    ImmutableSet<Label> getFilteredTargets() {
      return ImmutableSet.copyOf(targetParsingCompleteEvent.getFilteredLabels());
    }

    ImmutableSet<Label> getTestFilteredTargets() {
      return ImmutableSet.copyOf(targetParsingCompleteEvent.getTestFilteredLabels());
    }

    ImmutableSet<Label> getOriginalTargets() {
      return ImmutableSet.copyOf(targetParsingCompleteEvent.getLabels());
    }

    ImmutableSetMultimap<String, Label> getOriginalPatternsToLabels() {
      return targetParsingCompleteEvent.getOriginalPatternsToLabels();
    }

    ImmutableSet<Label> getTestSuiteTargets() {
      return loadingPhaseCompleteEvent.getFilteredLabels();
    }

    List<TestSuiteExpansion> getTestSuiteExpansions() {
      return targetParsingCompleteEvent
          .asStreamProto(null)
          .getExpanded()
          .getTestSuiteExpansionsList();
    }

    void throwExceptionOnGetInputStream(Path path, IOException exn) {
      fs.throwExceptionOnGetInputStream(path, exn);
    }

    private Iterable<Event> filteredEvents() {
      return Iterables.filter(
          storedErrors.getEvents(), event -> event.getKind() != EventKind.PROGRESS);
    }

    public void assertNoEvents() {
      MoreAsserts.assertNoEvents(filteredEvents());
    }

    public Event assertContainsWarning(String expectedMessage) {
      return MoreAsserts.assertContainsEvent(filteredEvents(), expectedMessage, EventKind.WARNING);
    }

    public Event assertContainsError(String expectedMessage) {
      return MoreAsserts.assertContainsEvent(filteredEvents(), expectedMessage, EventKind.ERRORS);
    }

    void assertContainsEventWithFrequency(String expectedMessage, int expectedFrequency) {
      MoreAsserts.assertContainsEventWithFrequency(
          filteredEvents(), expectedMessage, expectedFrequency);
    }

    <T extends Postable> T findPost(Class<T> clazz) {
      return Iterators.getNext(Iterators.filter(storedErrors.getPosts().iterator(), clazz), null);
    }

    <T extends Postable> T findPostOnce(Class<T> clazz) {
      return Iterables.getOnlyElement(Iterables.filter(storedErrors.getPosts(), clazz));
    }
  }

  /**
   * Custom {@link InMemoryFileSystem} that can be pre-configured per-file to throw a supplied
   * IOException instead of the usual behavior.
   */
  private static final class CustomInMemoryFs extends InMemoryFileSystem {
    private final Map<PathFragment, IOException> pathsToErrorOnGetInputStream = Maps.newHashMap();

    CustomInMemoryFs(ManualClock manualClock) {
      super(manualClock, DigestHashFunction.SHA256);
    }

    synchronized void throwExceptionOnGetInputStream(Path path, IOException exn) {
      pathsToErrorOnGetInputStream.put(path.asFragment(), exn);
    }

    @Override
    protected synchronized InputStream getInputStream(PathFragment path) throws IOException {
      IOException exnToThrow = pathsToErrorOnGetInputStream.get(path);
      if (exnToThrow != null) {
        throw exnToThrow;
      }
      return super.getInputStream(path);
    }
  }
}
