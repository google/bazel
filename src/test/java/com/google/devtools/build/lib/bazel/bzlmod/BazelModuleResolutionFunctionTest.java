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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BazelModuleResolutionFunction}. */
@RunWith(JUnit4.class)
public class BazelModuleResolutionFunctionTest extends BuildViewTestCase {

  @Test
  public void testBazelInvalidCompatibility() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0dd'])");
    invalidatePackages(false);

    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("invalid version argument '>5.1.0dd'");
  }

  @Test
  public void testSimpleBazelCompatibilityFailure() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");
    invalidatePackages(false);

    embedBazelVersion("5.1.4");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testBazelCompatibilityWarning() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
                BazelCompatibilityMode.WARNING)));
    invalidatePackages(false);

    embedBazelVersion("5.1.4");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isFalse();
    assertContainsEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testDisablingBazelCompatibility() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
                BazelCompatibilityMode.OFF)));
    invalidatePackages(false);

    embedBazelVersion("5.1.4");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isFalse();
    assertDoesNotContainEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testBazelCompatibilitySuccess() throws Exception {
    setupModulesForCompatibility();

    embedBazelVersion("5.1.4-pre.20220421.3");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testBazelCompatibilityFailure() throws Exception {
    setupModulesForCompatibility();

    embedBazelVersion("5.1.5rc444");
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 5.1.5rc444 is not compatible with module \"b@1.0\" (bazel_compatibility:"
            + " [<=5.1.4, -5.1.2])");
  }

  @Test
  public void testRcIsCompatibleWithReleaseRequirement() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])");
    invalidatePackages(false);

    embedBazelVersion("6.4.0rc1");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testPrereleaseIsNotCompatibleWithReleaseRequirement() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])");
    invalidatePackages(false);

    embedBazelVersion("6.4.0-pre-1");
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 6.4.0-pre-1 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>=6.4.0])");
  }

  private void embedBazelVersion(String version) {
    // Double-get version-info to determine if it's the cached instance or not, and if not cache it.
    BlazeVersionInfo blazeInfo1 = BlazeVersionInfo.instance();
    BlazeVersionInfo blazeInfo2 = BlazeVersionInfo.instance();
    if (blazeInfo1 != blazeInfo2) {
      BlazeVersionInfo.setBuildInfo(ImmutableMap.of());
      blazeInfo1 = BlazeVersionInfo.instance();
    }

    // embed new version
    Map<String, String> blazeInfo = blazeInfo1.getBuildData();
    blazeInfo.remove(BlazeVersionInfo.BUILD_LABEL);
    blazeInfo.put(BlazeVersionInfo.BUILD_LABEL, version);
  }

  private void setupModulesForCompatibility() throws Exception {
    /* Root depends on "a" which depends on "b"
       The only versions that would work with root, a and b compatibility constrains are between
       -not including- 5.1.2 and 5.1.4.
       Ex: 5.1.3rc44, 5.1.3, 5.1.4-pre22.44
    */
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.6'])",
        "bazel_dep(name = 'a', version = '1.0')");

    registry
        .addModule(
            createModuleKey("a", "1.0"),
            "module(name='a', version='1.0', bazel_compatibility=['>=5.1.2', '-5.1.4']);",
            "bazel_dep(name='b', version='1.0')")
        .addModule(
            createModuleKey("b", "1.0"),
            "module(name='b', version='1.0', bazel_compatibility=['<=5.1.4', '-5.1.2']);");
    invalidatePackages(false);
  }

  @Test
  public void testYankedVersionCheckSuccess() throws Exception {
    reporter.removeHandler(failFastHandler);
    setupModulesForYankedVersion();
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains(
            "Yanked version detected in your resolved dependency graph: b@1.0, for the reason: 1.0"
                + " is a bad version!");
  }

  @Test
  public void testYankedVersionCheckIgnoredByAll() throws Exception {
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, ImmutableList.of("all"))));
    setupModulesForYankedVersion();
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testYankedVersionCheckIgnoredBySpecific() throws Exception {
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, ImmutableList.of("b@1.0"))));
    setupModulesForYankedVersion();
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testBadYankedVersionFormat() throws Exception {
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, ImmutableList.of("b+1.0"))));
    setupModulesForYankedVersion();
    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains(
            "Parsing command line flag --allow_yanked_versions=b+1.0 failed, module versions must"
                + " be of the form '<module name>@<version>'");
  }

  private void setupModulesForYankedVersion() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0')",
        "bazel_dep(name = 'a', version = '1.0')");

    registry
        .addModule(
            createModuleKey("a", "1.0"),
            "module(name='a', version='1.0');",
            "bazel_dep(name='b', version='1.0')")
        .addModule(createModuleKey("b", "1.0"), "module(name='b', version='1.0');")
        .addYankedVersion("b", ImmutableMap.of(Version.parse("1.0"), "1.0 is a bad version!"));
    invalidatePackages(false);
  }

  @Test
  public void overrideOnNonexistentModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0')",
        "bazel_dep(name = 'a', version = '1.0')",
        "bazel_dep(name = 'b', version = '1.1')",
        "local_path_override(module_name='d', path='whatevs')");

    registry
        .addModule(
            createModuleKey("a", "1.0"),
            "module(name='a', version='1.0')",
            "bazel_dep(name='b', version='1.0')")
        .addModule(createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
        .addModule(createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
        .addModule(
            createModuleKey("b", "1.0"),
            "module(name='b', version='1.0')",
            "bazel_dep(name='c', version='1.1')")
        .addModule(
            createModuleKey("b", "1.1"),
            "module(name='b', version='1.1')",
            "bazel_dep(name='c', version='1.0')");
    invalidatePackages(false);

    EvaluationResult<BazelModuleResolutionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains("the root module specifies overrides on nonexistent module(s): d");
  }

  @Test
  public void testPrintBehavior() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='mod', version='1.0')",
        "print('hello from root module')",
        "bazel_dep(name = 'a', version = '1.0')",
        "bazel_dep(name = 'b', version = '1.1')",
        "single_version_override(module_name = 'b', version = '1.1')",
        "local_path_override(module_name='a', path='a')");
    scratch.file(
        "a/MODULE.bazel",
        "module(name='a', version='1.0')",
        "print('hello from overridden a')",
        "bazel_dep(name='b', version='1.0')");

    registry
        .addModule(
            createModuleKey("a", "1.0"),
            "module(name='a', version='1.0')",
            "print('hello from a@1.0')",
            "bazel_dep(name='b', version='1.0')")
        .addModule(createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
        .addModule(createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
        .addModule(
            createModuleKey("b", "1.0"),
            "module(name='b', version='1.0', compatibility_level = 2)",
            "bazel_dep(name='c', version='1.1')",
            "print('hello from b@1.0')")
        .addModule(
            createModuleKey("b", "1.1"),
            "module(name='b', version='1.1', compatibility_level = 3)",
            "bazel_dep(name='c', version='1.0')",
            "print('hello from b@1.1')");
    invalidatePackages(false);

    SkyframeExecutorTestUtils.evaluate(
        skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter);

    assertContainsEvent("hello from root module");
    assertContainsEvent("hello from overridden a");
    assertDoesNotContainEvent("hello from a@1.0");
    assertDoesNotContainEvent("hello from b@1.0");
    assertDoesNotContainEvent("hello from b@1.1");
  }
}
