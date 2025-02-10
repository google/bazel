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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Tables;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BzlLoadFunction. */
@RunWith(JUnit4.class)
public class BzlLoadFunctionTest extends BuildViewTestCase {

  @Override
  protected FileSystem createFileSystem() {
    return new CustomInMemoryFs();
  }

  @Before
  public final void preparePackageLoading() throws Exception {
    Path alternativeRoot = scratch.dir("/root_2");
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.defaultVisibility = RuleVisibility.PUBLIC;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BuildLanguageOptions.class).build();
    parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
    BuildLanguageOptions options = parser.getOptions(BuildLanguageOptions.class);
    getSkyframeExecutor()
        .preparePackageLoading(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory), Root.fromPath(alternativeRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
            packageOptions,
            options,
            UUID.randomUUID(),
            ImmutableMap.of(),
            QuiescingExecutorsImpl.forTesting(),
            new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
  }

  @Test
  public void testBzlLoadLabels() throws Exception {
    scratch.file("pkg1/BUILD");
    scratch.file("pkg1/ext.bzl");
    checkSuccessfulLookup("//pkg1:ext.bzl");

    scratch.file("pkg2/BUILD");
    scratch.file("pkg2/dir/ext.bzl");
    checkSuccessfulLookup("//pkg2:dir/ext.bzl");

    scratch.file("dir/pkg3/BUILD");
    scratch.file("dir/pkg3/dir/ext.bzl");
    checkSuccessfulLookup("//dir/pkg3:dir/ext.bzl");
  }

  @Test
  public void testBzlLoadLabelsAlternativeRoot() throws Exception {
    scratch.file("/root_2/pkg4/BUILD");
    scratch.file("/root_2/pkg4/ext.bzl");
    checkSuccessfulLookup("//pkg4:ext.bzl");
  }

  @Test
  public void testBzlLoadLabelsMultipleBuildFiles() throws Exception {
    scratch.file("dir1/BUILD");
    scratch.file("dir1/dir2/BUILD");
    scratch.file("dir1/dir2/ext.bzl");
    checkSuccessfulLookup("//dir1/dir2:ext.bzl");
  }

  @Test
  public void testLoadFromStarlarkFileInRemoteRepo() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name = 'a_remote_repo')",
        "local_path_override(module_name = 'a_remote_repo', path = '/a_remote_repo')");
    scratch.file("/a_remote_repo/MODULE.bazel", "module(name = 'a_remote_repo')");
    scratch.file("/a_remote_repo/remote_pkg/BUILD");
    scratch.file("/a_remote_repo/remote_pkg/ext1.bzl", "load(':ext2.bzl', 'CONST')");
    scratch.file("/a_remote_repo/remote_pkg/ext2.bzl", "CONST = 17");
    checkSuccessfulLookup("@@a_remote_repo+//remote_pkg:ext1.bzl");
  }

  @Test
  public void testLoadRelativeLabel() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1.bzl", "a = 1");
    scratch.file("pkg/ext2.bzl", "load(':ext1.bzl', 'a')");
    checkSuccessfulLookup("//pkg:ext2.bzl");
  }

  @Test
  public void testLoadAbsoluteLabel() throws Exception {
    scratch.file("pkg2/BUILD");
    scratch.file("pkg3/BUILD");
    scratch.file("pkg2/ext.bzl", "b = 1");
    scratch.file("pkg3/ext.bzl", "load('//pkg2:ext.bzl', 'b')");
    checkSuccessfulLookup("//pkg3:ext.bzl");
  }

  @Test
  public void testLoadFromSameAbsoluteLabelTwice() throws Exception {
    scratch.file("pkg1/BUILD");
    scratch.file("pkg2/BUILD");
    scratch.file(
        "pkg1/ext.bzl",
        """
        a = 1
        b = 2
        """);
    scratch.file(
        "pkg2/ext.bzl",
        """
        load("//pkg1:ext.bzl", "a", "b")
        """);
    checkSuccessfulLookup("//pkg2:ext.bzl");
  }

  @Test
  public void testLoadFromSameRelativeLabelTwice() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/ext1.bzl",
        """
        a = 1
        b = 2
        """);
    scratch.file(
        "pkg/ext2.bzl",
        """
        load(":ext1.bzl", "a", "b")
        """);
    checkSuccessfulLookup("//pkg:ext2.bzl");
  }

  @Test
  public void testLoadFromRelativeLabelInSubdir() throws Exception {
    scratch.file("pkg/BUILD");
    scratch.file("pkg/subdir/ext1.bzl", "a = 1");
    scratch.file("pkg/subdir/ext2.bzl", "load(':subdir/ext1.bzl', 'a')");
    checkSuccessfulLookup("//pkg:subdir/ext2.bzl");
  }

  @Test
  public void testLoadBadExtension_sclDisabled() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=false");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext.bzl", "load(':foo.garbage', 'a')");
    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext.bzl", "has invalid load statements");
    assertContainsEvent("The label must reference a file with extension \".bzl\"");
    assertDoesNotContainEvent(".scl");
  }

  @Test
  public void testLoadBadExtension_sclEnabled() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext.bzl", "load(':foo.garbage', 'a')");
    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext.bzl", "has invalid load statements");
    assertContainsEvent("The label must reference a file with extension \".bzl\" or \".scl\"");
  }

  @Test
  public void testLoadingSclRequiresExperimentalFlag() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=false");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext.scl");
    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//pkg:ext.scl", "loading .scl files requires setting --experimental_enable_scl_dialect");
  }

  @Test
  public void testCanLoadScl() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext.scl");
    checkSuccessfulLookup("//pkg:ext.scl");
  }

  @Test
  public void testCanLoadSclFromBzlAndScl() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1.scl", "a = 1");
    // Can use relative load label syntax from ext2a.bzl, but not from ext2b.scl.
    scratch.file("pkg/ext2a.bzl", "load(':ext1.scl', 'a')");
    scratch.file("pkg/ext2b.scl", "load('//pkg:ext1.scl', 'a')");

    checkSuccessfulLookup("//pkg:ext2a.bzl");
    checkSuccessfulLookup("//pkg:ext2b.scl");
  }

  @Test
  public void testSclCannotLoadNonSclFiles() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1a.bzl", "a = 1");
    scratch.file("pkg/ext1a.garbage", "a = 1");
    // Cannot use relative label.
    scratch.file("pkg/ext2a.scl", "load('//pkg:ext1a.bzl', 'a')");
    scratch.file("pkg/ext2b.scl", "load('//pkg:ext1b.garbage', 'a')");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext2a.scl", "has invalid load statements");
    assertContainsEvent(
        "The label must reference a file with extension \".scl\" (.scl files cannot load .bzl"
            + " files)");
    eventCollector.clear();
    checkFailingLookup("//pkg:ext2b.scl", "has invalid load statements");
    assertContainsEvent("The label must reference a file with extension \".scl\"");
    assertDoesNotContainEvent(".bzl");
  }

  @Test
  public void testSclCanOnlyLoadLabelsRelativeToDefaultRepoRoot() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/ext1.scl", "load(':foo.scl', 'a')");
    scratch.file("pkg/ext2.scl", "load('@repo//:foo.scl', 'a')");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext1.scl", "has invalid load statements");
    assertContainsEvent("in .scl files, load labels must begin with \"//\"");
    eventCollector.clear();
    checkFailingLookup("//pkg:ext2.scl", "has invalid load statements");
    assertContainsEvent("in .scl files, load labels must begin with \"//\"");
  }

  @Test
  public void testSclSupportsStructAndVisibility() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/ext1.scl", //
        "visibility('private')",
        "a = struct()");
    scratch.file(
        "pkg/ext2.scl", //
        "load('//pkg:ext1.scl', 'a')");
    scratch.file("pkg2/BUILD");
    scratch.file(
        "pkg2/ext3.scl", //
        "load('//pkg:ext1.scl', 'a')");

    checkSuccessfulLookup("//pkg:ext2.scl");
    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//pkg2:ext3.scl", "module //pkg2:ext3.scl contains .bzl load visibility violations");
  }

  @Test
  public void testSclDoesNotSupportOtherBazelSymbols() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/ext.scl", //
        "a = depset([])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext.scl", "compilation of module 'pkg/ext.scl' failed");
    assertContainsEvent("name 'depset' is not defined");
  }

  @Test
  public void testSclDisallowsNonAsciiStringLiterals() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/ext1.bzl", //
        "'x\377z'"); // xÿz
    scratch.file(
        "pkg/ext2.scl", //
        "'x\377z'");

    checkSuccessfulLookup("//pkg:ext1.bzl");
    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//pkg:ext2.scl", "compilation of module 'pkg/ext2.scl' failed");
    assertContainsEvent("string literal contains non-ASCII character");
  }

  private EvaluationResult<BzlLoadValue> get(SkyKey skyKey) throws Exception {
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    if (result.hasError()) {
      fail(result.getError(skyKey).getException().getMessage());
    }
    return result;
  }

  private static SkyKey key(String label) {
    return BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked(label));
  }

  /** Loads a .bzl with the given label and asserts success. */
  private void checkSuccessfulLookup(String label) throws Exception {
    SkyKey skyKey = key(label);
    EvaluationResult<BzlLoadValue> result = get(skyKey);
    // Ensure that the file has been processed by checking its Module for the label field.
    assertThat(Label.parseCanonicalUnchecked(label))
        .isEqualTo(BazelModuleContext.of(result.get(skyKey).getModule()).label());
  }

  /* Loads a .bzl with the given label and asserts BzlLoadFailedException with the given message. */
  private void checkFailingLookup(String label, String expectedMessage)
      throws InterruptedException {
    SkyKey skyKey = key(label);
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .isInstanceOf(BzlLoadFailedException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(expectedMessage);
  }

  @Test
  public void testBzlLoadNoBuildFile() throws Exception {
    scratch.file("pkg/ext.bzl", "");
    SkyKey skyKey = key("//pkg:ext.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    String errorMessage = errorInfo.getException().getMessage();
    assertThat(errorMessage)
        .contains(
            "Every .bzl file must have a corresponding package, but '//pkg:ext.bzl' does not");
  }

  @Test
  public void testBzlLoadNoBuildFileForLoad() throws Exception {
    scratch.file("pkg2/BUILD");
    scratch.file("pkg1/ext.bzl", "a = 1");
    scratch.file("pkg2/ext.bzl", "load('//pkg1:ext.bzl', 'a')");
    SkyKey skyKey = key("//pkg:ext.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    String errorMessage = errorInfo.getException().getMessage();
    assertThat(errorMessage).contains("Every .bzl file must have a corresponding package");
  }

  @Test
  public void testBzlLoadFilenameWithControlChars() throws Exception {
    scratch.file("pkg/BUILD", "");
    scratch.file("pkg/ext.bzl", "load('//pkg:oops\u0000.bzl', 'a')");
    SkyKey skyKey = key("//pkg:ext.bzl");
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                SkyframeExecutorTestUtils.evaluate(
                    getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter));
    String errorMessage = e.getMessage();
    assertThat(errorMessage)
        .contains(
            "invalid target name 'oops<?>.bzl': "
                + "target names may not contain non-printable characters: '\\x00'");
  }

  @Test
  public void testLoadFromSubdirInSamePackageIsOk() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')");
    scratch.file("a/b/b.bzl", "b = 42");

    checkSuccessfulLookup("//a:a.bzl");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofSubpkg() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a:b/b.bzl', 'b')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/b.bzl", "b = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
            + " put the colon here: '//a/b:b.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofSubpkg_relative() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('b/b.bzl', 'b')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/b.bzl", "b = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a:b/b.bzl' is invalid because 'a/b' is a subpackage; perhaps you meant to"
            + " put the colon here: '//a/b:b.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofIndirectSubpkg() throws Exception {
    scratch.file("a/BUILD");
    scratch.file("a/a.bzl", "load('//a/b:c/c.bzl', 'c')");
    scratch.file("a/b/BUILD", "");
    scratch.file("a/b/c/BUILD", "");
    scratch.file("a/b/c/c.bzl", "c = 42");
    checkFailingLookup(
        "//a:a.bzl",
        "Label '//a/b:c/c.bzl' is invalid because 'a/b/c' is a subpackage; perhaps you meant"
            + " to put the colon here: '//a/b/c:c.bzl'?");
  }

  @Test
  public void testLoadMustRespectPackageBoundary_ofParentPkg() throws Exception {
    scratch.file("a/b/BUILD");
    scratch.file("a/b/b.bzl", "load('//a/c:c/c.bzl', 'c')");
    scratch.file("a/BUILD");
    scratch.file("a/c/c/c.bzl", "c = 42");
    checkFailingLookup(
        "//a/b:b.bzl",
        "Label '//a/c:c/c.bzl' is invalid because 'a/c' is not a package; perhaps you meant to "
            + "put the colon here: '//a:c/c/c.bzl'?");
  }

  @Test
  public void testBzlVisibility_disabledWithoutFlag() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=false");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility("private")
        x = 1
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'b/bar.bzl' failed");
    assertContainsEvent("Use of `visibility()` requires --experimental_bzl_visibility");
  }

  @Test
  public void testBzlVisibility_publicExplicit() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility("public")
        x = 1
        """);

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_publicImplicit() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        // No visibility() declaration, defaults to public.
        "x = 1");

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateSamePackage() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//a:bar.bzl\", \"x\")");
    scratch.file(
        "a/bar.bzl",
        """
        visibility("private")
        x = 1
        """);

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateDifferentPackage() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility("private")
        x = 1
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl", "module //a:foo.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.");
  }

  @Test
  public void testBzlVisibility_emptyListMeansPrivate() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility([])
        x = 1
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl", "module //a:foo.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.");
  }

  @Test
  public void testBzlVisibility_publicListElement() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        # Tests "public" as a list item, and alongside other list items.
        visibility(["public", "//c"])
        x = 1
        """);

    checkSuccessfulLookup("//a:foo.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_privateListElement() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        # Tests "private" as a list item, and alongside other list items.
        visibility(["private", "//a1"])
        x = 1
        """);

    checkSuccessfulLookup("//a1:foo.bzl");
    assertNoEvents();
    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo.bzl", "module //a2:foo.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_failureInDependency() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        load("//c:baz.bzl", "y")

        visibility("public")
        x = y
        """);
    scratch.file("c/BUILD");
    scratch.file(
        "c/baz.bzl",
        """
        visibility("private")
        y = 1
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a:foo.bzl",
        "at /workspace/a/foo.bzl:1:6: module //b:bar.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //c:baz.bzl is not visible for loading from package //b.");
  }

  @Test
  public void testBzlVisibility_cannotBeSetInFunction() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl",
        """
        def helper():
            visibility("public")

        helper()
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("load visibility may only be set at the top level");
  }

  @Test
  public void testBzlVisibility_cannotBeSetTwice() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl",
        """
        visibility("public")
        visibility("public")
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("load visibility may not be set more than once");
  }

  @Test
  public void testBzlVisibility_enumeratedPackages() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility(["//a1"])
        x = 1
        """);

    checkSuccessfulLookup("//a1:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_singleEnumeratedPackageAsString() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a1/BUILD");
    scratch.file(
        "a1/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a2/BUILD");
    scratch.file(
        "a2/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        # Note: "//a1", not ["//a1"]
        visibility("//a1")
        x = 1
        """);

    checkSuccessfulLookup("//a1:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a2:foo2.bzl", "module //a2:foo2.bzl contains .bzl load visibility violations");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a2.");
  }

  @Test
  public void testBzlVisibility_enumeratedPackagesMultipleRepos() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    // @repo//pkg:foo1.bzl and @//pkg:foo2.bzl both try to access @repo//lib:bar.bzl. Test that when
    // bar.bzl declares a visibility allowing "//pkg", it means @repo//pkg and *not* @//pkg.
    scratch.overwriteFile(
        "MODULE.bazel", //
        "bazel_dep(name = 'repo')",
        "local_path_override(module_name = 'repo', path = 'repo')");
    scratch.file("repo/MODULE.bazel", "module(name = 'repo')");
    scratch.file("repo/pkg/BUILD");
    scratch.file(
        "repo/pkg/foo1.bzl", //
        "load(\"//lib:bar.bzl\", \"x\")");
    scratch.file("repo/lib/BUILD");
    scratch.file(
        "repo/lib/bar.bzl",
        """
        visibility(["//pkg"])
        x = 1
        """);
    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/foo2.bzl", //
        "load(\"@repo//lib:bar.bzl\", \"x\")");

    checkSuccessfulLookup("@@repo+//pkg:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//pkg:foo2.bzl", "module //pkg:foo2.bzl contains .bzl load visibility violations");
    assertContainsEvent(
        "Starlark file @@repo+//lib:bar.bzl is not visible for loading from package //pkg.");
  }

  // TODO(#16365): This test case can be deleted once --incompatible_package_group_has_public_syntax
  // is deleted (not just flipped).
  @Test
  public void testBzlVisibility_canUsePublicPrivate_regardlessOfFlag() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        // Test that we can use "public" and "private" visibility for .bzl files even when the
        // incompatible flag is disabled.
        "--incompatible_package_group_has_public_syntax=false");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "visibility(\"public\")");
    scratch.file(
        "a/foo2.bzl", //
        "visibility(\"private\")");

    checkSuccessfulLookup("//a:foo1.bzl");
    checkSuccessfulLookup("//a:foo2.bzl");
    assertNoEvents();
  }

  // TODO(#16324): Once --incompatible_fix_package_group_reporoot_syntax is deleted (not just
  // flipped), this test case will be redundant with tests for //... in PackageGroupTest. At that
  // point we'll just delete this test case.
  @Test
  public void testBzlVisibility_repoRootSubpackagesIsNotPublic_regardlessOfFlag() throws Exception {
    setBuildLanguageOptions(
        "--experimental_bzl_visibility=true",
        // Test that we get the fixed behavior even when the incompatible flag is disabled.
        "--incompatible_fix_package_group_reporoot_syntax=false");

    scratch.overwriteFile(
        "MODULE.bazel", //
        "bazel_dep(name = 'repo')",
        "local_path_override(module_name = 'repo', path = 'repo')");
    scratch.file("repo/MODULE.bazel", "module(name = 'repo')");
    scratch.file("repo/a/BUILD");
    scratch.file(
        "repo/a/foo.bzl", //
        "load(\"@@//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility(["//..."])
        x = 1
        """);

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "@@repo+//a:foo.bzl", "module @@repo+//a:foo.bzl contains .bzl load visibility violations");
    assertContainsEvent(
        "Starlark file //b:bar.bzl is not visible for loading from package @@repo+//a.");
  }

  @Test
  public void testBzlVisibility_disallowsSubpackagesWithoutWildcard() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a/subpkg/BUILD");
    scratch.file(
        "a/subpkg/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility(["//a"])
        x = 1
        """);

    checkSuccessfulLookup("//a:foo1.bzl");
    assertNoEvents();

    reporter.removeHandler(failFastHandler);
    checkFailingLookup(
        "//a/subpkg:foo2.bzl",
        "module //a/subpkg:foo2.bzl contains .bzl load visibility violations");
    assertContainsEvent(
        "Starlark file //b:bar.bzl is not visible for loading from package //a/subpkg.");
  }

  @Test
  public void testBzlVisibility_allowsSubpackagesWithWildcard() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo1.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("a/subpkg/BUILD");
    scratch.file(
        "a/subpkg/foo2.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility(["//a/..."])
        x = 1
        """);

    checkSuccessfulLookup("//a:foo1.bzl");
    assertNoEvents();

    checkSuccessfulLookup("//a/subpkg:foo2.bzl");
    assertNoEvents();
  }

  @Test
  public void testBzlVisibility_invalid_badType() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility(123)");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("Invalid visibility: got 'int', want string or list of strings");
  }

  @Test
  public void testBzlVisibility_invalid_badElementType() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"//a\", 123])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("at index 1 of visibility list, got element of type int, want string");
  }

  @Test
  public void testBzlVisibility_invalid_packageOutsideRepo() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"@repo//b\"])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("invalid package name '@repo//b': must start with '//'");
  }

  @Test
  public void testBzlVisibility_invalid_negationNotSupported() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "visibility([\"-//a\"])");

    reporter.removeHandler(failFastHandler);
    checkFailingLookup("//a:foo.bzl", "initialization of module 'a/foo.bzl' failed");
    assertContainsEvent("Cannot use negative package patterns here");
  }

  @Test
  public void testBzlVisibility_errorsDemotedToWarningWhenBreakGlassFlagIsSet() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true", "--check_bzl_visibility=false");

    scratch.file("a/BUILD");
    scratch.file(
        "a/foo.bzl", //
        "load(\"//b:bar.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/bar.bzl",
        """
        visibility("private")
        x = 1
        """);

    checkSuccessfulLookup("//a:foo.bzl");
    assertContainsEvent("Starlark file //b:bar.bzl is not visible for loading from package //a.");
    assertContainsEvent("Continuing because --nocheck_bzl_visibility is active");
  }

  @Test
  public void testLoadFromNonExistentRepository_producesMeaningfulError() throws Exception {
    scratch.file("BUILD", "load(\"@repository//dir:file.bzl\", \"foo\")");

    SkyKey skyKey = key("@repository//dir:file.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .isInstanceOf(BzlLoadFailedException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "Unable to find package for @@repository//dir:file.bzl: The repository '@@repository' "
                + "could not be resolved: Repository '@@repository' is not defined.");
  }

  @Test
  public void testLoadBzlFileFromBzlmod() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect");
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='foo',version='1.0')");
    registry
        .addModule(
            createModuleKey("foo", "1.0"),
            "module(name='foo',version='1.0')",
            "bazel_dep(name='bar',version='2.0',repo_name='bar_alias')")
        .addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    Path fooDir = moduleRoot.getRelative("foo+1.0");
    scratch.file(fooDir.getRelative("REPO.bazel").getPathString());
    scratch.file(fooDir.getRelative("BUILD").getPathString());
    scratch.file(
        fooDir.getRelative("test.bzl").getPathString(),
        // Also test that bzlmod .bzl files can load .scl files.
        "load('@bar_alias//:test.scl', 'haha')",
        "l = Label('@foo//:whatever')",
        "hoho = haha");
    Path barDir = moduleRoot.getRelative("bar+2.0");
    scratch.file(barDir.getRelative("REPO.bazel").getPathString());
    scratch.file(barDir.getRelative("BUILD").getPathString());
    scratch.file(barDir.getRelative("test.scl").getPathString(), "haha = 5");

    SkyKey skyKey = BzlLoadValue.keyForBzlmod(Label.parseCanonical("@@foo+//:test.bzl"));
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);

    assertThatEvaluationResult(result).hasNoError();
    var bzlLoadValue = result.get(skyKey);
    assertThat(bzlLoadValue.getModule().getGlobals()).containsEntry("hoho", StarlarkInt.of(5));
    assertThat(bzlLoadValue.getRecordedRepoMappings().cellSet())
        .containsExactly(
            Tables.immutableCell(
                RepositoryName.create("foo+"), "bar_alias", RepositoryName.create("bar+")),
            Tables.immutableCell(
                RepositoryName.create("foo+"), "foo", RepositoryName.create("foo+")))
        .inOrder();
    // Note that we're not testing the case of a non-registry override using @bazel_tools here, but
    // that is incredibly hard to set up in a unit test. So we should just rely on integration tests
    // for that.
  }

  @Test
  public void testBuiltinsInjectionFailure() throws Exception {
    setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging");
    scratch.file(
        "tools/builtins_staging/exports.bzl",
        """
        1 // 0  # <-- dynamic error
        exported_toplevels = {}
        exported_rules = {}
        exported_to_java = {}
        """);
    scratch.file("pkg/BUILD");
    scratch.file("pkg/foo.bzl");
    reporter.removeHandler(failFastHandler);

    SkyKey key = key("//pkg:foo.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);

    assertContainsEvent(
        "File \"/workspace/tools/builtins_staging/exports.bzl\", line 1, column 3, in <toplevel>");
    assertContainsEvent("Error: integer division by zero");
    Exception ex = result.getError(key).getException();
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "Internal error while loading Starlark builtins for //pkg:foo.bzl: Failed to load"
                + " builtins sources: initialization of module 'exports.bzl' (internal) failed");
  }

  @Test
  public void testErrorReadingBzlFileIsTransientWhenUsingASTInlining() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    fs.badPathForRead = scratch.file("a/a1.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isTransient();
  }

  @Test
  public void testErrorReadingOtherBzlFileIsPersistentFromPerspectiveOfParent() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    scratch.file("a/a1.bzl", "load('//a:a2.bzl', 'a2')");
    fs.badPathForRead = scratch.file("a/a2.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient();
  }

  @Test
  public void testErrorStatingBzlFileInFileStateFunctionIsPersistent() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) fileSystem;
    scratch.file("a/BUILD");
    fs.badPathForStat = scratch.file("a/a1.bzl", "doesntmatter");

    SkyKey key = key("//a:a1.bzl");
    EvaluationResult<BzlLoadValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key).isNotTransient();
  }

  private static class CustomInMemoryFs extends InMemoryFileSystem {
    @Nullable private Path badPathForStat;
    @Nullable private Path badPathForRead;

    CustomInMemoryFs() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (badPathForStat != null && badPathForStat.asFragment().equals(path)) {
        throw new IOException("bad");
      }
      return super.statIfFound(path, followSymlinks);
    }

    @Override
    protected synchronized InputStream getInputStream(PathFragment path) throws IOException {
      if (badPathForRead != null && badPathForRead.asFragment().equals(path)) {
        throw new IOException("bad");
      }
      return super.getInputStream(path);
    }
  }
}
