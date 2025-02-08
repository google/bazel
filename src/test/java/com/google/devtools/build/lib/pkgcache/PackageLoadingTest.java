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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.config.FeatureSet;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.SkyframeExecutorTestHelper;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.util.UUID;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for package loading. */
@RunWith(JUnit4.class)
public class PackageLoadingTest extends FoundationTestCase {

  private SkyframeExecutor skyframeExecutor;
  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Before
  public final void initializeSkyframeExecutor() throws Exception {
    initializeSkyframeExecutor(/* doPackageLoadingChecks= */ true);
  }

  @Before
  public final void fooLibrary() throws Exception {
    scratch.file("test_defs/BUILD");
    scratch.file(
        "test_defs/foo_library.bzl",
        """
        def _impl(ctx):
          pass
        foo_library = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
          },
        )
        """);
  }

  /**
   * @param doPackageLoadingChecks when true, a PackageLoader will be called after each package load
   *     this test performs, and the results compared to SkyFrame's result.
   */
  private void initializeSkyframeExecutor(boolean doPackageLoadingChecks) throws Exception {
    AnalysisMock analysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules();
    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(outputBase, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            analysisMock.getProductName());
    PackageFactory.BuilderForTesting packageFactoryBuilder =
        analysisMock.getPackageFactoryBuilderForTesting(directories);
    if (!doPackageLoadingChecks) {
      packageFactoryBuilder.disableChecks();
    }
    skyframeExecutor =
        BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
            .setPkgFactory(packageFactoryBuilder.build(ruleClassProvider, fileSystem))
            .setFileSystem(fileSystem)
            .setDirectories(directories)
            .setActionKeyContext(actionKeyContext)
            .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
            .setSyscallCache(SyscallCache.NO_CACHE)
            .build();
    SkyframeExecutorTestHelper.process(skyframeExecutor);
    setUpSkyframe(parsePackageOptions(), parseBuildLanguageOptions());
  }

  private void setUpSkyframe(
      PackageOptions packageOptions, BuildLanguageOptions buildLanguageOptions) {
    PathPackageLocator pkgLocator =
        PathPackageLocator.create(
            /* outputBase= */ null,
            packageOptions.packagePath,
            reporter,
            rootDirectory.asFragment(),
            rootDirectory,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    skyframeExecutor.injectExtraPrecomputedValues(AnalysisMock.get().getPrecomputedValues());
    skyframeExecutor.preparePackageLoading(
        pkgLocator,
        packageOptions,
        buildLanguageOptions,
        UUID.randomUUID(),
        ImmutableMap.of(),
        QuiescingExecutorsImpl.forTesting(),
        new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
    skyframeExecutor.setDeletedPackages(ImmutableSet.copyOf(packageOptions.getDeletedPackages()));
  }

  private static OptionsParser parse(String... options) throws Exception {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(PackageOptions.class, BuildLanguageOptions.class)
            .build();
    parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
    parser.parse("--default_visibility=public");
    parser.parse(options);

    return parser;
  }

  private static PackageOptions parsePackageOptions(String... options) throws Exception {
    return parse(options).getOptions(PackageOptions.class);
  }

  private static BuildLanguageOptions parseBuildLanguageOptions(String... options)
      throws Exception {
    return parse(options).getOptions(BuildLanguageOptions.class);
  }

  protected void setOptions(String... options) throws Exception {
    setUpSkyframe(parsePackageOptions(options), parseBuildLanguageOptions(options));
  }

  private PackageManager getPackageManager() {
    return skyframeExecutor.getPackageManager();
  }

  private void invalidatePackages() throws InterruptedException, AbruptExitException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory));
  }

  private Package getPackage(String packageName)
      throws NoSuchPackageException, InterruptedException {
    return getPackageManager()
        .getPackage(reporter, PackageIdentifier.createInMainRepo(packageName));
  }

  private Target getTarget(Label label)
      throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
    return getPackageManager().getTarget(reporter, label);
  }

  private Target getTarget(String label) throws Exception {
    return getTarget(Label.parseCanonical(label));
  }

  private void createPkg1() throws IOException {
    scratch.file("pkg1/BUILD", "cc_library(name = 'foo') # a BUILD file");
  }

  // Check that a substring is present in an error message.
  private void checkGetPackageFails(String packageName, String expectedMessage) {
    NoSuchPackageException e =
        assertThrows(NoSuchPackageException.class, () -> getPackage(packageName));
    assertThat(e).hasMessageThat().contains(expectedMessage);
  }

  @Test
  public void testGetPackage() throws Exception {
    createPkg1();
    Package pkg1 = getPackage("pkg1");
    assertThat(pkg1.getName()).isEqualTo("pkg1");
    assertThat(pkg1.getFilename().asPath().getPathString()).isEqualTo("/workspace/pkg1/BUILD");
    assertThat(getPackageManager().getPackage(reporter, PackageIdentifier.createInMainRepo("pkg1")))
        .isSameInstanceAs(pkg1);
  }

  @Test
  public void testASTIsNotRetained() throws Exception {
    createPkg1();
    Package pkg1 = getPackage("pkg1");
    MoreAsserts.assertInstanceOfNotReachable(pkg1, StarlarkFile.class);
  }

  @Test
  public void testGetNonexistentPackage() {
    checkGetPackageFails("not-there", "no such package 'not-there': " + "BUILD file not found");
  }

  @Test
  public void testGetPackageWithInvalidName() throws Exception {
    scratch.file("invalidpackagename:42/BUILD", "cc_library(name = 'foo') # a BUILD file");
    checkGetPackageFails(
        "invalidpackagename:42",
        "no such package 'invalidpackagename:42': Invalid package name 'invalidpackagename:42'");
  }

  @Test
  public void testGetTarget() throws Exception {
    createPkg1();
    Label label = Label.parseCanonical("//pkg1:foo");
    Target target = getTarget(label);
    assertThat(target.getLabel()).isEqualTo(label);
  }

  @Test
  public void testGetNonexistentTarget() throws Exception {
    createPkg1();
    NoSuchTargetException e =
        assertThrows(NoSuchTargetException.class, () -> getTarget("//pkg1:not-there"));
    assertThat(e)
        .hasMessageThat()
        .matches(
            TestUtils.createMissingTargetAssertionString("not-there", "pkg1", "/workspace", ""));
  }

  /**
   * A missing package is one for which no BUILD file can be found. The PackageCache caches failures
   * of this kind until the next sync.
   */
  @Test
  public void testRepeatedAttemptsToParseMissingPackage() throws Exception {
    checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found");

    // Still missing:
    checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found");

    // Update the BUILD file on disk so "missing" is no longer missing:
    scratch.file("missing/BUILD", "# an ok build file");

    // Still missing:
    checkGetPackageFails("missing", "no such package 'missing': " + "BUILD file not found");

    invalidatePackages();

    // Found:
    Package missing = getPackage("missing");

    assertThat(missing.getName()).isEqualTo("missing");
  }

  /**
   * A broken package is one that exists but contains lexer/parser/evaluator errors. The
   * PackageCache only makes one attempt to parse each package once found.
   *
   * <p>Depending on the strictness of the PackageFactory, parsing a broken package may cause a
   * Package object to be returned (possibly missing some rules) or an exception to be thrown. For
   * this test we need that strict behavior.
   *
   * <p>Note: since the PackageCache.setStrictPackageCreation method was deleted (since it wasn't
   * used by any significant clients) creating a "broken" build file got trickier--syntax errors are
   * not enough. For now, we create an unreadable BUILD file, which will cause an IOException to be
   * thrown. This test seems less valuable than it once did.
   */
  @Test
  public void testParseBrokenPackage() throws Exception {
    reporter.removeHandler(failFastHandler);

    Path brokenBuildFile = scratch.file("broken/BUILD");
    brokenBuildFile.setReadable(false);

    BuildFileContainsErrorsException e =
        assertThrows(BuildFileContainsErrorsException.class, () -> getPackage("broken"));
    assertThat(e).hasMessageThat().contains("/workspace/broken/BUILD (Permission denied)");
    eventCollector.clear();

    // Update the BUILD file on disk so "broken" is no longer broken:
    scratch.overwriteFile("broken/BUILD", "# an ok build file");

    invalidatePackages(); //  resets cache of failures

    Package broken = getPackage("broken");
    assertThat(broken.getName()).isEqualTo("broken");
    assertNoEvents();
  }

  @Test
  public void testMovedBuildFileCausesReloadAfterSync() throws Exception {
    // PackageLoader doesn't support --package_path.
    initializeSkyframeExecutor(/* doPackageLoadingChecks= */ false);

    Path buildFile1 = scratch.file("pkg/BUILD", "cc_library(name = 'foo')");
    Path buildFile2 = scratch.file("/otherroot/pkg/BUILD", "cc_library(name = 'bar')");
    setOptions("--package_path=/workspace:/otherroot");

    Package oldPkg = getPackage("pkg");
    assertThat(getPackage("pkg")).isSameInstanceAs(oldPkg); // change not yet visible
    assertThat(oldPkg.getFilename().asPath()).isEqualTo(buildFile1);
    assertThat(oldPkg.getSourceRoot().get()).isEqualTo(Root.fromPath(rootDirectory));

    buildFile1.delete();
    invalidatePackages();

    Package newPkg = getPackage("pkg");
    assertThat(newPkg).isNotSameInstanceAs(oldPkg);
    assertThat(newPkg.getFilename().asPath()).isEqualTo(buildFile2);
    assertThat(newPkg.getSourceRoot().get()).isEqualTo(Root.fromPath(scratch.dir("/otherroot")));

    // TODO(bazel-team): (2009) test BUILD file moves in the other direction too.
  }

  private Path rootDir1;
  private Path rootDir2;

  private void setUpCacheWithTwoRootLocator() throws Exception {
    // Root 1:
    //   /a/BUILD
    //   /b/BUILD
    //   /c/d
    //   /c/e
    //
    // Root 2:
    //   /b/BUILD
    //   /c/BUILD
    //   /c/d/BUILD
    //   /f/BUILD
    //   /f/g
    //   /f/g/h/BUILD

    rootDir1 = scratch.dir("/workspace");
    rootDir2 = scratch.dir("/otherroot");

    createBuildFile(rootDir1, "a", "foo.txt", "bar/foo.txt");
    createBuildFile(rootDir1, "b", "foo.txt", "bar/foo.txt");

    rootDir1.getRelative("c").createDirectory();
    rootDir1.getRelative("c/d").createDirectory();
    rootDir1.getRelative("c/e").createDirectory();

    createBuildFile(rootDir2, "c", "d", "d/foo.txt", "foo.txt", "bar/foo.txt", "e", "e/foo.txt");
    createBuildFile(rootDir2, "c/d", "foo.txt");
    createBuildFile(rootDir2, "f", "g/foo.txt", "g/h", "g/h/foo.txt", "foo.txt");
    createBuildFile(rootDir2, "f/g/h", "foo.txt");

    setOptions("--package_path=/workspace:/otherroot");
  }

  protected Path createBuildFile(Path workspace, String packageName, String... targets)
      throws IOException {
    String[] lines = new String[targets.length + 1];

    lines[0] = "load('//test_defs:foo_library.bzl', 'foo_library')";
    for (int i = 0; i < targets.length; i++) {
      lines[i + 1] = "foo_library(name='" + targets[i] + "')";
    }

    return scratch.file(workspace + "/" + packageName + "/BUILD", lines);
  }

  private void assertLabelValidity(boolean expected, String labelString) throws Exception {
    Label label = Label.parseCanonical(labelString);

    boolean actual = false;
    String error = null;
    try {
      getTarget(label);
      actual = true;
    } catch (NoSuchPackageException | NoSuchTargetException e) {
      error = e.getMessage();
    }
    if (actual != expected) {
      fail(
          "assertLabelValidity("
              + label
              + ") "
              + actual
              + ", not equal to expected value "
              + expected
              + " (error="
              + error
              + ")");
    }
  }

  private void assertPackageLoadingFails(String pkgName, String expectedError) throws Exception {
    Package pkg = getPackage(pkgName);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(expectedError);
  }

  @Test
  public void testLocationForLabelCrossingSubpackage() throws Exception {
    scratch.file("e/f/BUILD");
    scratch.file(
        "e/BUILD",
        """
        # Whatever
        filegroup(
            name = "fg",
            srcs = ["f/g"],
        )
        """);
    reporter.removeHandler(failFastHandler);

    getPackage("e");

    assertThat(eventCollector).hasSize(1);
    assertThat(Iterables.getOnlyElement(eventCollector).getLocation().line()).isEqualTo(2);
  }

  /** Static tests (i.e. no changes to filesystem, nor calls to sync). */
  @Test
  public void testLabelValidity() throws Exception {
    // PackageLoader doesn't support --package_path.
    initializeSkyframeExecutor(/* doPackageLoadingChecks= */ false);

    reporter.removeHandler(failFastHandler);
    setUpCacheWithTwoRootLocator();

    scratch.file(rootDir2 + "/c/d/foo.txt");

    assertLabelValidity(true, "//a:foo.txt");
    assertLabelValidity(true, "//a:bar/foo.txt");
    assertLabelValidity(false, "//a/bar:foo.txt"); //  no such package a/bar

    assertLabelValidity(true, "//b:foo.txt");
    assertLabelValidity(true, "//b:bar/foo.txt");
    assertLabelValidity(false, "//b/bar:foo.txt"); // no such package b/bar

    assertLabelValidity(true, "//c:foo.txt");
    assertLabelValidity(true, "//c:bar/foo.txt");
    assertLabelValidity(false, "//c/bar:foo.txt"); // no such package c/bar

    assertLabelValidity(true, "//c:foo.txt");

    assertLabelValidity(false, "//c:d/foo.txt"); // crosses boundary of c/d
    assertLabelValidity(true, "//c/d:foo.txt");

    assertLabelValidity(true, "//c:foo.txt");
    assertLabelValidity(true, "//c:e");
    assertLabelValidity(true, "//c:e/foo.txt");
    assertLabelValidity(false, "//c/e:foo.txt"); // no such package c/e

    assertLabelValidity(true, "//f:foo.txt");
    assertLabelValidity(true, "//f:g/foo.txt");
    assertLabelValidity(false, "//f/g:foo.txt"); // no such package f/g
    assertLabelValidity(false, "//f:g/h/foo.txt"); // crosses boundary of f/g/h
    assertLabelValidity(false, "//f/g:h/foo.txt"); // no such package f/g
    assertLabelValidity(true, "//f/g/h:foo.txt");
  }

  /** Dynamic tests of label validity. */
  @Test
  public void testAddedBuildFileCausesLabelToBecomeInvalid() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("pkg/BUILD", "cc_library(name = 'foo', srcs = ['x/y.cc'])");

    assertLabelValidity(true, "//pkg:x/y.cc");

    // The existence of this file makes 'x/y.cc' an invalid reference.
    scratch.file("pkg/x/BUILD");

    // but not yet...
    assertLabelValidity(true, "//pkg:x/y.cc");

    invalidatePackages();

    // now:
    assertPackageLoadingFails(
        "pkg", "Label '//pkg:x/y.cc' is invalid because 'pkg/x' is a subpackage");
  }

  @Test
  public void testDeletedPackages() throws Exception {
    // PackageLoader doesn't support --deleted_packages.
    initializeSkyframeExecutor(/* doPackageLoadingChecks= */ false);
    reporter.removeHandler(failFastHandler);
    setUpCacheWithTwoRootLocator();
    createBuildFile(rootDir1, "c", "d/x", "e/x");
    createBuildFile(rootDir1, "c/e", "x");
    // Now package c exists in both roots, and c/d exists in only in the second
    // root.  It's as if we've merged c and c/d in the first root.

    // c/d is still a subpackage--found in the second root:
    assertThat(getPackage("c/d").getFilename().asPath())
        .isEqualTo(rootDir2.getRelative("c/d/BUILD"));

    // Subpackage labels are still valid...
    assertLabelValidity(true, "//c/d:foo.txt");
    assertLabelValidity(true, "//c/e:x");
    // ...and this crosses package boundaries:
    assertLabelValidity(false, "//c:d/x");
    assertPackageLoadingFails(
        "c",
        "Label '//c:d/x' is invalid because 'c/d' is a subpackage; have you deleted c/d/BUILD? "
            + "If so, use the --deleted_packages=c/d option");

    assertThat(getPackageManager().isPackage(reporter, PackageIdentifier.createInMainRepo("c/d")))
        .isTrue();

    setOptions("--package_path=/workspace:/otherroot", "--deleted_packages=c/d");
    invalidatePackages();

    assertThat(getPackageManager().isPackage(reporter, PackageIdentifier.createInMainRepo("c/d")))
        .isFalse();

    // c/d is no longer a subpackage--even though there's a BUILD file in the
    // second root:
    NoSuchPackageException e = assertThrows(NoSuchPackageException.class, () -> getPackage("c/d"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "no such package 'c/d': Package is considered deleted due to --deleted_packages");

    // Labels in the subpackage are no longer valid...
    assertLabelValidity(false, "//c/d:x");
    // ...and now d is just a subdirectory of c:
    assertLabelValidity(true, "//c:d/x");

    // Verify that multiple --deleted_packages options are concatenated
    setOptions(
        "--package_path=/workspace:/otherroot", "--deleted_packages=c/d", "--deleted_packages=c/e");
    invalidatePackages();

    assertLabelValidity(false, "//c/d:x");
    assertLabelValidity(false, "//c/e:x");
    assertLabelValidity(true, "//c:d/x");
    assertLabelValidity(true, "//c:e/x");

    // Verify that comma-separated values work, too
    setOptions("--package_path=/workspace:/otherroot", "--deleted_packages=c/d,c/e");
    invalidatePackages();

    assertLabelValidity(false, "//c/d:x");
    assertLabelValidity(false, "//c/e:x");
    assertLabelValidity(true, "//c:d/x");
    assertLabelValidity(true, "//c:e/x");
  }

  @Test
  public void testPackageFeatures() throws Exception {
    scratch.file(
        "peach/BUILD",
        """
        package(features = ["crosstool_default_false"])

        cc_library(
            name = "cc",
            srcs = ["cc.cc"],
        )
        """);
    assertThat(getPackage("peach").getPackageArgs().features())
        .isEqualTo(FeatureSet.parse(ImmutableList.of("crosstool_default_false")));
  }

  @Test
  public void testBrokenPackageOnMultiplePackagePathEntries() throws Exception {
    reporter.removeHandler(failFastHandler);
    setOptions("--package_path=.:.");
    scratch.file("x/y/BUILD");
    scratch.file(
        "x/BUILD",
        """
        genrule(
            name = "x",
            srcs = [],
            outs = ["y/z.h"],
            cmd = "",
        )
        """);
    Package p = getPackage("x");
    assertThat(p.containsErrors()).isTrue();
  }

  // Regression test for b/230791645: non-deterministic location of input file targets.
  @Test
  public void testDeterminismOfInputFileLocation() throws Exception {
    scratch.file(
        "p/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "t1",
            srcs = ["f.sh"],
        )

        foo_library(
            name = "t2",
            srcs = ["f.sh"],
        )
        """);
    Package p = getPackage("p");
    InputFile f = (InputFile) p.getTarget("f.sh");
    assertThat(f.getLocation().line()).isEqualTo(3);
  }

  @Test
  public void testDeterminismOfFailureDetailOnMultipleLabelCrossingSubpackageBoundaryErrors()
      throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("p/sub/BUILD");
    scratch.file(
        "p/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(name = "sub/a")

        foo_library(name = "sub/b")
        """);
    Package p = getPackage("p");
    assertThat(p.getFailureDetail().getPackageLoading().getCode())
        .isEqualTo(PackageLoading.Code.LABEL_CROSSES_PACKAGE_BOUNDARY);
    // We used to non-deterministically pick a target whose label crossed a subpackage boundary, but
    // now we deterministically pick the first one (alphabetically by target name).
    assertThat(p.getFailureDetail().getMessage()).startsWith("Label '//p:sub/a' is invalid");
  }
}
