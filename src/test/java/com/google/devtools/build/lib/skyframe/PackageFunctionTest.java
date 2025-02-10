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

import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.Globber.Operation;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageOverheadEstimator;
import com.google.devtools.build.lib.packages.PackageValidator;
import com.google.devtools.build.lib.packages.PackageValidator.InvalidPackageException;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.skyframe.GlobsValue.GlobRequest;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.Options;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests of specific functionality of PackageFunction. Note that it's already tested indirectly
 * in several other places.
 */
@RunWith(TestParameterInjector.class)
public class PackageFunctionTest extends BuildViewTestCase {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private PackageValidator mockPackageValidator;

  @Mock private PackageOverheadEstimator mockPackageOverheadEstimator;

  @TestParameter private boolean globUnderSingleDep;

  private final CustomInMemoryFs fs = new CustomInMemoryFs(new ManualClock());

  private void preparePackageLoading(Path... roots) throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(parseBuildLanguageOptions(), roots);
  }

  private void preparePackageLoadingWithCustomStarklarkSemanticsOptions(
      BuildLanguageOptions buildLanguageOptions, Path... roots) {
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.defaultVisibility = RuleVisibility.PUBLIC;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    getSkyframeExecutor()
        .preparePackageLoading(
            new PathPackageLocator(
                outputBase,
                Arrays.stream(roots).map(Root::fromPath).collect(ImmutableList.toImmutableList()),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of(),
            QuiescingExecutorsImpl.forTesting(),
            new TimestampGranularityMonitor(BlazeClock.instance()));
    skyframeExecutor.setActionEnv(ImmutableMap.of());
  }

  @Override
  protected FileSystem createFileSystem() {
    return fs;
  }

  @Override
  protected PackageValidator getPackageValidator() {
    return mockPackageValidator;
  }

  @Override
  protected PackageOverheadEstimator getPackageOverheadEstimator() {
    return mockPackageOverheadEstimator;
  }

  private Package validPackageWithoutErrors(SkyKey skyKey) throws InterruptedException {
    return validPackageInternal(skyKey, /*checkPackageError=*/ true);
  }

  private Package validPackage(SkyKey skyKey) throws InterruptedException {
    return validPackageInternal(skyKey, /*checkPackageError=*/ false);
  }

  private Package validPackageInternal(SkyKey skyKey, boolean checkPackageError)
      throws InterruptedException {
    SkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty())));
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, skyKey, /*keepGoing=*/ false, reporter);
    if (result.hasError()) {
      fail(result.getError(skyKey).getException().getMessage());
    }
    PackageValue value = result.get(skyKey);
    if (checkPackageError) {
      assertThat(value.getPackage().containsErrors()).isFalse();
    }
    return value.getPackage();
  }

  private Exception evaluatePackageToException(String pkg) throws Exception {
    return evaluatePackageToException(pkg, /*keepGoing=*/ false);
  }

  /**
   * Helper that evaluates the given package and returns the expected exception.
   *
   * <p>Disables the failFastHandler as a side-effect.
   */
  private Exception evaluatePackageToException(String pkg, boolean keepGoing) throws Exception {
    reporter.removeHandler(failFastHandler);

    SkyKey skyKey = PackageIdentifier.createInMainRepo(pkg);
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(getSkyframeExecutor(), skyKey, keepGoing, reporter);
    assertThat(result.hasError()).isTrue();
    return result.getError(skyKey).getException();
  }

  @Before
  @Override
  public final void initializeSkyframeExecutor() throws Exception {
    initializeSkyframeExecutor(
        /* doPackageLoadingChecks= */ true,
        /* diffAwarenessFactories= */ ImmutableList.of(),
        /* globUnderSingleDep= */ globUnderSingleDep);
  }

  @Test
  public void testValidPackage() throws Exception {
    scratch.file("pkg/BUILD");
    validPackageWithoutErrors(PackageIdentifier.createInMainRepo("pkg"));
  }

  @Test
  public void testInvalidPackage() throws Exception {
    scratch.file("pkg/BUILD", "filegroup(name='foo', srcs=['foo.sh'])");
    scratch.file("pkg/foo.sh");

    doAnswer(
            inv -> {
              Package pkg = inv.getArgument(0, Package.class);
              if (pkg.getName().equals("pkg")) {
                inv.getArgument(1, ExtendedEventHandler.class).handle(Event.warn("warning event"));
                throw new InvalidPackageException(pkg.getPackageIdentifier(), "no good");
              }
              return null;
            })
        .when(mockPackageValidator)
        .validate(any(Package.class), any(ExtendedEventHandler.class));

    invalidatePackages();

    Exception ex = evaluatePackageToException("pkg");
    assertThat(ex).isInstanceOf(InvalidPackageException.class);
    assertThat(ex).hasMessageThat().contains("no such package 'pkg': no good");
    assertContainsEvent("warning event");
  }

  @Test
  public void testPackageOverheadPassedToValidationLogic() throws Exception {
    scratch.file("pkg/BUILD", "# Contents doesn't matter, it's all fake");

    when(mockPackageOverheadEstimator.estimatePackageOverhead(any(Package.class)))
        .thenReturn(OptionalLong.of(42));
    ArgumentCaptor<Package> packageCaptor = ArgumentCaptor.forClass(Package.class);

    invalidatePackages(true);
    reset(mockPackageValidator);

    SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(),
        PackageIdentifier.createInMainRepo("pkg"),
        /* keepGoing= */ false,
        reporter);

    verify(mockPackageValidator).validate(packageCaptor.capture(), any(ExtendedEventHandler.class));
    List<Package> packages = packageCaptor.getAllValues();
    assertThat(packages.get(0).getPackageOverhead()).isEqualTo(OptionalLong.of(42));
  }

  @Test
  public void testSkyframeExecutorClearedPackagesResultsInReload() throws Exception {
    scratch.file("pkg/BUILD", "filegroup(name='foo', srcs=['foo.sh'])");
    scratch.file("pkg/foo.sh");

    invalidatePackages();

    // Use number of times the package was validated as a proxy for number of times it was loaded.
    AtomicInteger validationCount = new AtomicInteger();
    doAnswer(
            inv -> {
              if (inv.getArgument(0, Package.class).getName().equals("pkg")) {
                validationCount.incrementAndGet();
              }
              return null;
            })
        .when(mockPackageValidator)
        .validate(any(Package.class), any(ExtendedEventHandler.class));

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    EvaluationResult<PackageValue> result1 =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result1).hasNoError();

    skyframeExecutor.clearLoadedPackages();

    EvaluationResult<PackageValue> result2 =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThatEvaluationResult(result2).hasNoError();

    assertThat(validationCount.get()).isEqualTo(2);
  }

  @Test
  public void testPropagatesFilesystemInconsistencies() throws Exception {
    RecordingDifferencer differencer = getSkyframeExecutor().getDifferencerForTesting();
    Root pkgRoot = getSkyframeExecutor().getPathEntries().get(0);
    Path fooBuildFile = scratch.file("foo/BUILD");
    Path fooDir = fooBuildFile.getParentDirectory();

    // Our custom filesystem says that fooDir is neither a file nor directory nor symlink
    FileStatus inconsistentFileStatus =
        new FileStatus() {
          @Override
          public boolean isFile() {
            return false;
          }

          @Override
          public boolean isDirectory() {
            return false;
          }

          @Override
          public boolean isSymbolicLink() {
            return false;
          }

          @Override
          public boolean isSpecialFile() {
            return false;
          }

          @Override
          public long getSize() {
            return 0;
          }

          @Override
          public long getLastModifiedTime() {
            return 0;
          }

          @Override
          public long getLastChangeTime() {
            return 0;
          }

          @Override
          public long getNodeId() {
            return 0;
          }
        };

    fs.stubStat(fooBuildFile, inconsistentFileStatus);
    RootedPath pkgRootedPath = RootedPath.toRootedPath(pkgRoot, fooDir);
    SkyValue fooDirValue = FileStateValue.create(pkgRootedPath, SyscallCache.NO_CACHE, tsgm);
    differencer.inject(
        ImmutableMap.of(FileStateValue.key(pkgRootedPath), Delta.justNew(fooDirValue)));

    Exception ex = evaluatePackageToException("foo");
    String msg = ex.getMessage();
    assertThat(msg).contains("Inconsistent filesystem operations");
    assertThat(msg)
        .contains(
            "according to stat, existing path /workspace/foo/BUILD is neither"
                + " a file nor directory nor symlink.");
    assertDetailedExitCode(
        ex,
        PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR,
        ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
  }

  @Test
  public void testPropagatesFilesystemInconsistencies_globbing() throws Exception {
    RecordingDifferencer differencer = getSkyframeExecutor().getDifferencerForTesting();
    Root pkgRoot = getSkyframeExecutor().getPathEntries().get(0);
    scratch.file(
        "foo/BUILD",
        """
        filegroup(
            name = "foo",
            srcs = glob(["bar/**/baz.sh"]),
        )

        x = 1 // 0
        """ // causes 'foo' to be marked in error
        );
    Path bazFile = scratch.file("foo/bar/baz/baz.sh");
    Path bazDir = bazFile.getParentDirectory();
    Path barDir = bazDir.getParentDirectory();

    // Our custom filesystem says "foo/bar/baz" does not exist but it also says that "foo/bar"
    // has a child directory "baz".
    fs.stubStat(bazDir, null);
    RootedPath barDirRootedPath = RootedPath.toRootedPath(pkgRoot, barDir);
    differencer.inject(
        ImmutableMap.of(
            DirectoryListingStateValue.key(barDirRootedPath),
            Delta.justNew(
                DirectoryListingStateValue.create(
                    ImmutableList.of(new Dirent("baz", Dirent.Type.DIRECTORY))))));

    Exception ex = evaluatePackageToException("foo");
    String msg = ex.getMessage();
    assertThat(msg).contains("Inconsistent filesystem operations");
    assertThat(msg).contains("/workspace/foo/bar/baz is no longer an existing directory");
    assertDetailedExitCode(
        ex,
        PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR,
        ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
  }

  /** Regression test for unexpected exception type from PackageValue. */
  @Test
  public void testDiscrepancyBetweenGlobbingErrors() throws Exception {
    Path fooBuildFile =
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['bar/*.sh']))");
    Path fooDir = fooBuildFile.getParentDirectory();
    Path barDir = fooDir.getRelative("bar");
    scratch.file("foo/bar/baz.sh");
    fs.scheduleMakeUnreadableAfterReaddir(barDir);

    Exception ex =
        evaluatePackageToException(
            "foo",
            // Use --keep_going, not --nokeep_going, semantics so as to exercise the situation we
            // want to exercise.
            //
            // In --nokeep_going semantics, the GlobValue node's error would halt normal evaluation
            // and trigger error bubbling. Then, during error bubbling we would freshly compute the
            // PackageValue node again, meaning we would do non-Skyframe globbing except this time
            // non-Skyframe globbing would encounter the io error, meaning there actually wouldn't
            // be a discrepancy.
            /*keepGoing=*/ true);
    String msg = ex.getMessage();
    assertThat(msg).contains("Inconsistent filesystem operations");
    assertThat(msg).contains("Encountered error '/workspace/foo/bar (Permission denied)'");
    assertDetailedExitCode(
        ex,
        PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR,
        ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
  }

  @SuppressWarnings("unchecked") // Cast of srcs attribute to Iterable<Label>.
  @Test
  public void testGlobOrderStable() throws Exception {
    scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['**/*.txt']))");
    scratch.file("foo/b.txt");
    scratch.file("foo/c/c.txt");
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    assertThat((Iterable<Label>) pkg.getTarget("foo").getAssociatedRule().getAttr("srcs"))
        .containsExactly(
            Label.parseCanonicalUnchecked("//foo:b.txt"),
            Label.parseCanonicalUnchecked("//foo:c/c.txt"))
        .inOrder();
    scratch.file("foo/d.txt");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/d.txt")).build(),
            Root.fromPath(rootDirectory));
    pkg = validPackageWithoutErrors(skyKey);
    assertThat((Iterable<Label>) pkg.getTarget("foo").getAssociatedRule().getAttr("srcs"))
        .containsExactly(
            Label.parseCanonicalUnchecked("//foo:b.txt"),
            Label.parseCanonicalUnchecked("//foo:c/c.txt"),
            Label.parseCanonicalUnchecked("//foo:d.txt"))
        .inOrder();
  }

  @Test
  public void testGlobOrderStableWithNonSkyframeAndSkyframeComponents() throws Exception {
    scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt']))");
    scratch.file("foo/b.txt");
    scratch.file("foo/a.config");
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:b.txt");
    scratch.overwriteFile(
        "foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt', '*.config']))");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:a.config", "//foo:b.txt");
    scratch.overwriteFile(
        "foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt', '*.config'])) # comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:a.config", "//foo:b.txt");
    getSkyframeExecutor().resetEvaluator();
    PackageOptions packageOptions = Options.getDefaults(PackageOptions.class);
    packageOptions.defaultVisibility = RuleVisibility.PUBLIC;
    packageOptions.showLoadingProgress = true;
    packageOptions.globbingThreads = 7;
    getSkyframeExecutor()
        .preparePackageLoading(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
            packageOptions,
            parseBuildLanguageOptions(),
            UUID.randomUUID(),
            ImmutableMap.of(),
            QuiescingExecutorsImpl.forTesting(),
            tsgm);
    getSkyframeExecutor().injectExtraPrecomputedValues(analysisMock.getPrecomputedValues());
    getSkyframeExecutor().setActionEnv(ImmutableMap.of());
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:a.config", "//foo:b.txt");
  }

  @Test
  public void globEscapesAt() throws Exception {
    scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt']))");
    scratch.file("foo/@f.txt");
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:@f.txt");

    scratch.overwriteFile("foo/BUILD", "filegroup(name = 'foo', srcs = glob(['*.txt'])) # comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));
    assertSrcs(validPackageWithoutErrors(skyKey), "foo", "//foo:@f.txt");
  }

  /**
   * Tests that a symlink to a file outside of the package root is handled consistently. If the
   * default behavior of Bazel was changed from {@code
   * ExternalFileAction#DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS} to {@code
   * ExternalFileAction#ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS} then foo/link.sh
   * should no longer appear in the srcs of //foo:foo. However, either way the srcs should be the
   * same independent of the evaluation being incremental or clean.
   */
  @Test
  public void testGlobWithExternalSymlink() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        filegroup(
            name = "foo",
            srcs = glob(["*.sh"]),
        )

        filegroup(
            name = "bar",
            srcs = glob(["link.sh"]),
        )

        filegroup(
            name = "baz",
            srcs = glob(["subdir_link/*.txt"]),
        )
        """);
    scratch.file("foo/ordinary.sh");
    Path externalTarget = scratch.file("../ops/target.txt");
    FileSystemUtils.ensureSymbolicLink(scratch.resolve("foo/link.sh"), externalTarget);
    FileSystemUtils.ensureSymbolicLink(
        scratch.resolve("foo/subdir_link"), externalTarget.getParentDirectory());
    preparePackageLoading(rootDirectory);
    SkyKey fooKey = PackageIdentifier.createInMainRepo("foo");
    Package fooPkg = validPackageWithoutErrors(fooKey);
    assertSrcs(fooPkg, "foo", "//foo:link.sh", "//foo:ordinary.sh");
    assertSrcs(fooPkg, "bar", "//foo:link.sh");
    assertSrcs(fooPkg, "baz", "//foo:subdir_link/target.txt");
    scratch.overwriteFile(
        "foo/BUILD",
        """
        filegroup(
            name = "foo",
            srcs = glob(["*.sh"]),
        )  #comment

        filegroup(
            name = "bar",
            srcs = glob(["link.sh"]),
        )

        filegroup(
            name = "baz",
            srcs = glob(["subdir_link/*.txt"]),
        )
        """);
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));
    Package fooPkg2 = validPackageWithoutErrors(fooKey);
    assertThat(fooPkg2).isNotEqualTo(fooPkg);
    assertSrcs(fooPkg2, "foo", "//foo:link.sh", "//foo:ordinary.sh");
    assertSrcs(fooPkg2, "bar", "//foo:link.sh");
    assertSrcs(fooPkg2, "baz", "//foo:subdir_link/target.txt");
  }

  private static void assertSrcs(Package pkg, String targetName, String... expected)
      throws NoSuchTargetException {
    List<Label> expectedLabels = new ArrayList<>();
    for (String item : expected) {
      expectedLabels.add(Label.parseCanonicalUnchecked(item));
    }
    assertThat(getSrcs(pkg, targetName)).containsExactlyElementsIn(expectedLabels).inOrder();
  }

  @SuppressWarnings("unchecked")
  private static Iterable<Label> getSrcs(Package pkg, String targetName)
      throws NoSuchTargetException {
    return (Iterable<Label>) pkg.getTarget(targetName).getAssociatedRule().getAttr("srcs");
  }

  @Test
  public void testOneNewElementInMultipleGlob() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        filegroup(
            name = "foo",
            srcs = glob(
                ["*.sh"],
                allow_empty = True,
            ),
        )

        filegroup(
            name = "bar",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )
        """);
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    scratch.file("foo/irrelevant");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/irrelevant")).build(),
            Root.fromPath(rootDirectory));
    assertThat(validPackageWithoutErrors(skyKey)).isSameInstanceAs(pkg);
  }

  @Test
  public void testNoNewElementInMultipleGlob() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        filegroup(
            name = "foo",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )

        filegroup(
            name = "bar",
            srcs = glob(
                [
                    "*.sh",
                    "*.txt",
                ],
                allow_empty = True,
            ),
        )
        """);
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    scratch.file("foo/irrelevant");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/irrelevant")).build(),
            Root.fromPath(rootDirectory));
    assertThat(validPackageWithoutErrors(skyKey)).isSameInstanceAs(pkg);
  }

  @Test
  public void testTransitiveStarlarkDepsStoredInPackage() throws Exception {
    scratch.file("foo/BUILD", "load('//bar:ext.bzl', 'a')");
    scratch.file("bar/BUILD");
    scratch.file(
        "bar/ext.bzl",
        """
        load("//baz:ext.scl", "b")

        a = b
        """);
    scratch.file("baz/BUILD");
    scratch.file("baz/ext.scl", "b = 1");
    scratch.file("qux/BUILD");
    scratch.file("qux/ext.bzl", "c = 1");

    preparePackageLoading(rootDirectory);
    // must be done after preparePackageLoading()
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");

    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    assertThat(pkg.getOrComputeTransitivelyLoadedStarlarkFiles())
        .containsExactly(
            Label.parseCanonical("//bar:ext.bzl"), Label.parseCanonical("//baz:ext.scl"));

    scratch.overwriteFile(
        "bar/ext.bzl",
        """
        load("//qux:ext.bzl", "c")

        a = c
        """);
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("bar/ext.bzl")).build(),
            Root.fromPath(rootDirectory));

    pkg = validPackageWithoutErrors(skyKey);
    assertThat(pkg.getOrComputeTransitivelyLoadedStarlarkFiles())
        .containsExactly(
            Label.parseCanonical("//bar:ext.bzl"), Label.parseCanonical("//qux:ext.bzl"));
  }

  @Test
  public void testNonExistingStarlarkExtension() throws Exception {
    scratch.file("test/starlark/BUILD", "load('//test/starlark:bad_extension.bzl', 'some_symbol')");
    invalidatePackages();

    Exception ex = evaluatePackageToException("test/starlark");
    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "error loading package 'test/starlark': "
                + "cannot load '//test/starlark:bad_extension.bzl': no such file");
    assertDetailedExitCode(
        ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testNonExistingStarlarkExtensionFromExtension() throws Exception {
    scratch.file(
        "test/starlark/extension.bzl",
        """
        load("//test/starlark:bad_extension.bzl", "some_symbol")

        a = "a"
        """);
    scratch.file("test/starlark/BUILD", "load('//test/starlark:extension.bzl', 'a')");
    invalidatePackages();

    Exception ex = evaluatePackageToException("test/starlark");
    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "error loading package 'test/starlark': "
                + "at /workspace/test/starlark/extension.bzl:1:6: "
                + "cannot load '//test/starlark:bad_extension.bzl': no such file");
    assertDetailedExitCode(
        ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE);
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

    Exception ex = evaluatePackageToException("pkg");
    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "error loading package 'pkg': Internal error while loading Starlark builtins: Failed"
                + " to load builtins sources: initialization of module 'exports.bzl' (internal)"
                + " failed");
    assertDetailedExitCode(
        ex, PackageLoading.Code.BUILTINS_INJECTION_FAILURE, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testSymlinkCycleWithStarlarkExtension() throws Exception {
    Path extensionFilePath = scratch.resolve("/workspace/test/starlark/extension.bzl");
    FileSystemUtils.ensureSymbolicLink(extensionFilePath, PathFragment.create("extension.bzl"));
    scratch.file("test/starlark/BUILD", "load('//test/starlark:extension.bzl', 'a')");
    invalidatePackages();

    Exception ex = evaluatePackageToException("test/starlark");
    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "error loading package 'test/starlark': Encountered error while reading extension "
                + "file 'test/starlark/extension.bzl': Symlink cycle");
    assertDetailedExitCode(
        ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testIOErrorLookingForSubpackageForLabelIsHandled() throws Exception {
    scratch.file(
        "foo/BUILD", //
        "filegroup(name = 'foo', srcs = ['bar/baz.sh'])");
    Path barBuildFile = scratch.file("foo/bar/BUILD");
    fs.stubStatError(barBuildFile, new IOException("nope"));

    evaluatePackageToException("foo");
    assertContainsEvent("nope");
  }

  @Test
  public void testLoadOK() throws Exception {
    scratch.file("p/a.bzl", "a = 1; b = 1; d = 1");
    scratch.file("p/subdir/a.bzl", "c = 1; e = 1");
    scratch.file(
        "p/BUILD",
        """
        load("//p:a.bzl", "d")
        load("//p:subdir/a.bzl", "e")
        load(":a.bzl", "a")
        load("a.bzl", "b")
        load("subdir/a.bzl", "c")
        """);
    validPackageWithoutErrors(PackageIdentifier.createInMainRepo("p"));
  }

  // See WorkspaceFileFunctionTest for tests that exercise load('@repo...').

  @Test
  public void testLoadBadLabel() throws Exception {
    scratch.file("p/BUILD", "load('this\tis not a label', 'a')");
    reporter.removeHandler(failFastHandler);
    SkyKey key = PackageIdentifier.createInMainRepo("p");
    SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    assertContainsEvent(
        "in load statement: invalid target name 'this<?>is not a label': target names may not"
            + " contain non-printable characters");
  }

  @Test
  public void testLoadFromExternalPackage() throws Exception {
    scratch.file("p/BUILD", "load('//external:file.bzl', 'a')");
    reporter.removeHandler(failFastHandler);
    SkyKey key = PackageIdentifier.createInMainRepo("p");
    SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    assertContainsEvent("Starlark files may not be loaded from the //external package");
  }

  @Test
  public void testLoadWithoutBzlSuffix() throws Exception {
    scratch.file("p/BUILD", "load('//p:file.starlark', 'a')");
    reporter.removeHandler(failFastHandler);
    SkyKey key = PackageIdentifier.createInMainRepo("p");
    SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    assertContainsEvent("The label must reference a file with extension \".bzl\"");
  }

  @Test
  public void testBzlVisibilityViolation() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file(
        "a/BUILD", //
        "load(\"//b:foo.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/foo.bzl",
        """
        visibility("private")
        x = 1
        """);

    reporter.removeHandler(failFastHandler);
    Exception ex = evaluatePackageToException("a");
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "error loading package 'a': file //a:BUILD contains .bzl load visibility violations");
    assertDetailedExitCode(
        ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE);
    assertContainsEvent("Starlark file //b:foo.bzl is not visible for loading from package //a.");
  }

  @Test
  public void testBzlVisibilityViolationDemotedToWarningWhenBreakGlassFlagIsSet() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true", "--check_bzl_visibility=false");

    scratch.file(
        "a/BUILD", //
        "load(\"//b:foo.bzl\", \"x\")");
    scratch.file("b/BUILD");
    scratch.file(
        "b/foo.bzl",
        """
        visibility("private")
        x = 1
        """);

    validPackageWithoutErrors(PackageIdentifier.createInMainRepo("a"));
    assertContainsEvent("Starlark file //b:foo.bzl is not visible for loading from package //a.");
    assertContainsEvent("Continuing because --nocheck_bzl_visibility is active");
  }

  @Test
  public void testVisibilityCallableNotAvailableInBUILD() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file(
        "a/BUILD", //
        "visibility(\"public\")");

    reporter.removeHandler(failFastHandler);
    // The evaluation result ends up being null, probably due to the test framework swallowing
    // exceptions (similar to b/26382502). So let's just look for the error event instead of
    // asserting on the exception.
    SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(),
        PackageIdentifier.createInMainRepo("a"),
        /* keepGoing= */ false,
        reporter);
    assertContainsEvent("name 'visibility' is not defined");
  }

  @Test
  public void testVisibilityCallableErroneouslyInvokedInBUILD() throws Exception {
    setBuildLanguageOptions("--experimental_bzl_visibility=true");

    scratch.file(
        "a/BUILD",
        """
        load(":helper.bzl", "helper")

        helper()
        """);
    scratch.file(
        "a/helper.bzl",
        """
        def helper():
            visibility("public")
        """);

    reporter.removeHandler(failFastHandler);
    SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(),
        PackageIdentifier.createInMainRepo("a"),
        /* keepGoing= */ false,
        reporter);
    assertContainsEvent(
        "visibility() can only be used during .bzl initialization (top-level evaluation)");
  }

  // Regression test for the two ugly consequences of a bug where GlobFunction incorrectly matched
  // dangling symlinks.
  @Test
  public void testIncrementalSkyframeHybridGlobbingOnDanglingSymlink() throws Exception {
    Path packageDirPath =
        scratch.file("foo/BUILD", "exports_files(glob(['*.txt']))").getParentDirectory();
    scratch.file("foo/existing.txt");
    FileSystemUtils.ensureSymbolicLink(packageDirPath.getChild("dangling.txt"), "nope");

    preparePackageLoading(rootDirectory);

    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertThat(pkg.getTarget("existing.txt").getName()).isEqualTo("existing.txt");
    assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("dangling.txt"));

    scratch.overwriteFile(
        "foo/BUILD",
        """
        exports_files(glob(["*.txt"]))
        #some-irrelevant-comment
        """);

    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));

    Package pkg2 = validPackageWithoutErrors(skyKey);
    assertThat(pkg2.containsErrors()).isFalse();
    assertThat(pkg2.getTarget("existing.txt").getName()).isEqualTo("existing.txt");
    assertThrows(NoSuchTargetException.class, () -> pkg2.getTarget("dangling.txt"));
    // One consequence of the bug was that dangling symlinks were matched by globs evaluated by
    // Skyframe globbing, meaning there would incorrectly be corresponding targets in packages
    // that had skyframe cache hits during skyframe hybrid globbing.

    scratch.file("foo/nope");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/nope")).build(),
            Root.fromPath(rootDirectory));

    Package newPkg = validPackageWithoutErrors(skyKey);
    assertThat(newPkg.containsErrors()).isFalse();
    assertThat(newPkg.getTarget("existing.txt").getName()).isEqualTo("existing.txt");
    // Another consequence of the bug is that change pruning would incorrectly cut off changes that
    // caused a dangling symlink potentially matched by a glob to come into existence.
    assertThat(newPkg.getTarget("dangling.txt").getName()).isEqualTo("dangling.txt");
    assertThat(newPkg).isNotSameInstanceAs(pkg);
  }

  // Regression test for Skyframe globbing incorrectly matching the package's directory path on
  // 'glob(['**'], exclude_directories = 0)'. We test for this directly by triggering
  // hybrid globbing (gives coverage for both non-skyframe globbing and skyframe globbing).
  @Test
  public void testRecursiveGlobNeverMatchesPackageDirectory() throws Exception {
    scratch.file(
        "foo/BUILD",
        "[filegroup(name = x + '-matched') for x in glob(['**'], exclude_directories = 0)]");
    scratch.file("foo/bar");

    preparePackageLoading(rootDirectory);

    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    Package pkg = validPackageWithoutErrors(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertThat(pkg.getTarget("bar-matched").getName()).isEqualTo("bar-matched");
    assertThrows(NoSuchTargetException.class, () -> pkg.getTarget("-matched"));

    scratch.overwriteFile(
        "foo/BUILD",
        """
        [filegroup(name = x + "-matched") for x in glob(
            ["**"],
            exclude_directories = 0,
        )]
        #some-irrelevant-comment
        """);
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));

    Package pkg2 = validPackageWithoutErrors(skyKey);
    assertThat(pkg2.containsErrors()).isFalse();
    assertThat(pkg2.getTarget("bar-matched").getName()).isEqualTo("bar-matched");
    assertThrows(NoSuchTargetException.class, () -> pkg2.getTarget("-matched"));
  }

  @Test
  public void testPackageLoadingErrorOnIOExceptionReadingBuildFile() throws Exception {
    Path fooBuildFilePath = scratch.file("foo/BUILD");
    IOException exn = new IOException("nope");
    fs.throwExceptionOnGetInputStream(fooBuildFilePath, exn);

    Exception ex = evaluatePackageToException("foo");
    assertThat(ex).hasMessageThat().contains("nope");
    assertThat(ex).isInstanceOf(NoSuchPackageException.class);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);
    assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testPackageLoadingErrorOnMissingBuildFile_singlePackagePath() throws Exception {
    scratch.file("foo/bar");

    // There is no foo/BUILD file, but we enforce loading package 'foo'.
    Exception ex = evaluatePackageToException("foo");
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "BUILD file not found in any of the following directories. "
                + "Add a BUILD file to a directory to mark it as a package.\n"
                // Print the package_path relative directory path if only a single `package_path` is
                // provided.
                + " - foo");
    assertThat(ex).isInstanceOf(BuildFileNotFoundException.class);
    assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testPackageLoadingErrorOnMissingBuildFile_multiplePackagePath() throws Exception {
    scratch.file("foo/bar");
    Path otherRootDir = scratch.dir("/ws2");
    scratch.file("/ws2/foo/bar");
    getSkyframeExecutor()
        .preparePackageLoading(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory), Root.fromPath(otherRootDir)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY),
            Options.getDefaults(PackageOptions.class),
            Options.getDefaults(BuildLanguageOptions.class),
            UUID.randomUUID(),
            ImmutableMap.of(),
            QuiescingExecutorsImpl.forTesting(),
            tsgm);

    // There is no foo/BUILD file under both `package_path`s foo directory, but we enforce loading
    // package 'foo'.
    Exception ex = evaluatePackageToException("foo");

    assertThat(ex)
        .hasMessageThat()
        .contains(
            "BUILD file not found in any of the following directories. "
                + "Add a BUILD file to a directory to mark it as a package.");
    // Print the absolute directory paths if multiple `package_path`s are provided.
    assertThat(ex).hasMessageThat().contains("- /workspace/foo");
    assertThat(ex).hasMessageThat().contains("- /ws2/foo");
    assertThat(ex).isInstanceOf(BuildFileNotFoundException.class);
    assertDetailedExitCode(ex, PackageLoading.Code.BUILD_FILE_MISSING, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testPackageLoadingErrorOnIOExceptionReadingBzlFile() throws Exception {
    scratch.file("foo/BUILD", "load('//foo:bzl.bzl', 'x')");
    Path fooBzlFilePath = scratch.file("foo/bzl.bzl");
    IOException exn = new IOException("nope");
    fs.throwExceptionOnGetInputStream(fooBzlFilePath, exn);

    Exception ex = evaluatePackageToException("foo");
    assertThat(ex).hasMessageThat().contains("nope");
    assertThat(ex).isInstanceOf(NoSuchPackageException.class);
    assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);
    assertDetailedExitCode(
        ex, PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR, ExitCode.BUILD_FAILURE);
  }

  @Test
  public void testLabelsCrossesSubpackageBoundaries_singleSubpackageCrossing() throws Exception {
    reporter.removeHandler(failFastHandler);

    scratch.file("pkg/foo/BUILD", "exports_files(['sub/bar/blah'])");
    scratch.file("pkg/foo/sub/BUILD");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg/foo");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThatEvaluationResult(result).hasNoError();
    assertThat(result.get(skyKey).getPackage().containsErrors()).isTrue();
    assertContainsEvent(
        "Label '//pkg/foo:sub/bar/blah' is invalid because 'pkg/foo/sub' is a subpackage; perhaps"
            + " you meant to put the colon here: '//pkg/foo/sub:bar/blah'?");
  }

  @Test
  public void testLabelsCrossesSubpackageBoundaries_complexSubpackageCrossing() throws Exception {
    reporter.removeHandler(failFastHandler);

    scratch.file(
        "pkg/foo/BUILD",
        """
        exports_files(["sub11/sub12/blah1"])

        exports_files(["sub21/sub22/blah2"])
        """);
    scratch.file("pkg/foo/sub11/BUILD");
    scratch.file("pkg/foo/sub11/sub12/BUILD");
    scratch.file("pkg/foo/sub21/BUILD");
    scratch.file("pkg/foo/sub21/sub22/BUILD");

    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg/foo");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThatEvaluationResult(result).hasNoError();
    assertThat(result.get(skyKey).getPackage().containsErrors()).isTrue();

    // Only the deepest package that crosses subpackage boundary should be displayed in the error
    // message.
    assertContainsEvent(
        "Label '//pkg/foo:sub11/sub12/blah1' is invalid because 'pkg/foo/sub11/sub12' is a"
            + " subpackage; perhaps you meant to put the colon here:"
            + " '//pkg/foo/sub11/sub12:blah1'?");
    assertContainsEvent(
        "Label '//pkg/foo:sub21/sub22/blah2' is invalid because 'pkg/foo/sub21/sub22' is a"
            + " subpackage; perhaps you meant to put the colon here:"
            + " '//pkg/foo/sub21/sub22:blah2'?");
    assertThat(eventCollector.filtered(EventKind.ERROR)).hasSize(2);
  }

  @Test
  public void testSymlinkCycleEncounteredWhileHandlingLabelCrossingSubpackageBoundaries()
      throws Exception {
    scratch.file("pkg/BUILD", "exports_files(['sub/blah'])");
    Path subBuildFilePath = scratch.dir("pkg/sub").getChild("BUILD");
    FileSystemUtils.ensureSymbolicLink(subBuildFilePath, subBuildFilePath);
    invalidatePackages();

    Exception ex = evaluatePackageToException("pkg");
    assertThat(ex).isInstanceOf(BuildFileNotFoundException.class);
    assertThat(ex)
        .hasMessageThat()
        .contains(
            "no such package 'pkg/sub': Symlink cycle detected while trying to find BUILD file");
    assertContainsEvent("circular symlinks detected");
  }

  // Regression test for b/206459361.
  @Test
  public void nonSkyframeGlobbingIOException_andLabelCrossingSubpackageBoundaries_withKeepGoing()
      throws Exception {
    reporter.removeHandler(failFastHandler);

    // When a package's BUILD file and the relevant filesystem state is such that non-Skyframe
    // globbing will encounter an IOException due to a directory symlink cycle *and* the BUILD file
    // defines a target with a label that crosses subpackage boundaries,
    Path pkgBUILDPath =
        scratch.file(
            "pkg/BUILD",
            """
            exports_files(["sub/blah"])  # label crossing subpackage boundaries

            glob(["globcycle/**/foo.txt"])  # triggers non-Skyframe globbing error
            """);
    scratch.file("pkg/sub/BUILD");
    Path pkgGlobcyclePath = pkgBUILDPath.getParentDirectory().getChild("globcycle");
    FileSystemUtils.ensureSymbolicLink(pkgGlobcyclePath, pkgGlobcyclePath);
    assertThrows(IOException.class, () -> pkgGlobcyclePath.statIfFound(Symlinks.FOLLOW));

    invalidatePackages();

    // ... and we evaluate the package with keepGoing == true, we expect the evaluation to fail with
    // the non-Skyframe globbing error, but for the label crossing event to *not* get added (because
    // the globbing IOException would put Package.Builder in a state on which we cannot run
    // handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions).
    SkyKey pkgKey = PackageIdentifier.createInMainRepo("pkg");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), pkgKey, /*keepGoing=*/ true, reporter);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .isInstanceOf(NoSuchPackageException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("Symlink cycle: /workspace/pkg/globcycle");
    assertDoesNotContainEvent(
        "Label '//pkg:sub/blah' is invalid because 'pkg/sub' is a subpackage");
  }

  @Test
  public void testGlobAllowEmpty_paramValueMustBeBoolean() throws Exception {
    reporter.removeHandler(failFastHandler);

    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty = 5)");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    validPackage(skyKey);

    assertContainsEvent("expected boolean for argument `allow_empty`, got `5`");
  }

  @Test
  public void testGlobAllowEmpty_functionParam() throws Exception {
    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=True)");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();
  }

  @Test
  public void testGlobAllowEmpty_starlarkOption() throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        parseBuildLanguageOptions("--incompatible_disallow_empty_glob=false"), rootDirectory);

    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();
  }

  @Test
  public void testGlobDisallowEmpty_functionParam_wasNonEmptyAndBecomesEmpty() throws Exception {
    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)");
    scratch.file("pkg/blah.foo");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();

    scratch.deleteFile("pkg/blah.foo");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
            Root.fromPath(rootDirectory));

    reporter.removeHandler(failFastHandler);
    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).");
  }

  @Test
  public void testGlobDisallowEmpty_starlarkOption_wasNonEmptyAndBecomesEmpty() throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"), rootDirectory);

    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])");
    scratch.file("pkg/blah.foo");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();

    scratch.deleteFile("pkg/blah.foo");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
            Root.fromPath(rootDirectory));

    reporter.removeHandler(failFastHandler);
    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).");
  }

  @Test
  public void testGlobDisallowEmpty_functionParam_wasEmptyAndStaysEmpty() throws Exception {
    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    reporter.removeHandler(failFastHandler);

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    String expectedEventString =
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).";
    assertContainsEvent(expectedEventString);

    scratch.overwriteFile("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False) #comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
            Root.fromPath(rootDirectory));

    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(expectedEventString);
  }

  @Test
  public void testGlobDisallowEmpty_starlarkOption_wasEmptyAndStaysEmpty() throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"), rootDirectory);

    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    reporter.removeHandler(failFastHandler);

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    String expectedEventString =
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).";
    assertContainsEvent(expectedEventString);

    scratch.overwriteFile("pkg/BUILD", "x = " + "glob(['*.foo']) #comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
            Root.fromPath(rootDirectory));

    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(expectedEventString);
  }

  @Test
  public void testGlobDisallowEmpty_functionParam_wasEmptyDueToExcludeAndStaysEmpty()
      throws Exception {
    scratch.file("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*'], allow_empty=False)");
    scratch.file("pkg/blah.foo");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    reporter.removeHandler(failFastHandler);

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    String expectedEventString =
        "all files in the glob have been excluded, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).";
    assertContainsEvent(expectedEventString);

    scratch.overwriteFile(
        "pkg/BUILD",
        "x = glob(include=['*.foo'], exclude=['blah.*'], allow_empty=False) # comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
            Root.fromPath(rootDirectory));

    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(expectedEventString);
  }

  @Test
  public void testGlobDisallowEmpty_starlarkOption_wasEmptyDueToExcludeAndStaysEmpty()
      throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"), rootDirectory);

    scratch.file("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*'])");
    scratch.file("pkg/blah.foo");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");
    reporter.removeHandler(failFastHandler);

    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    String expectedEventString =
        "all files in the glob have been excluded, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).";
    assertContainsEvent(expectedEventString);

    scratch.overwriteFile("pkg/BUILD", "x = glob(include=['*.foo'], exclude=['blah.*']) # comment");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/BUILD")).build(),
            Root.fromPath(rootDirectory));

    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(expectedEventString);
  }

  @Test
  public void testGlobDisallowEmpty_functionParam_wasEmptyAndBecomesNonEmpty() throws Exception {
    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'], allow_empty=False)");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");

    reporter.removeHandler(failFastHandler);
    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();
    assertContainsEvent(
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).");

    scratch.file("pkg/blah.foo");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
            Root.fromPath(rootDirectory));

    reporter.addHandler(failFastHandler);
    eventCollector.clear();
    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();
  }

  @Test
  public void testGlobDisallowEmpty_starlarkOption_wasEmptyAndBecomesNonEmpty() throws Exception {
    preparePackageLoadingWithCustomStarklarkSemanticsOptions(
        parseBuildLanguageOptions("--incompatible_disallow_empty_glob=true"), rootDirectory);

    scratch.file("pkg/BUILD", "x = " + "glob(['*.foo'])");
    invalidatePackages();

    SkyKey skyKey = PackageIdentifier.createInMainRepo("pkg");

    reporter.removeHandler(failFastHandler);
    Package pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isTrue();

    assertContainsEvent(
        "glob pattern '*.foo' didn't match anything, but allow_empty is set to False (the "
            + "default value of allow_empty can be set with --incompatible_disallow_empty_glob).");

    scratch.file("pkg/blah.foo");
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("pkg/blah.foo")).build(),
            Root.fromPath(rootDirectory));

    reporter.addHandler(failFastHandler);
    eventCollector.clear();
    pkg = validPackage(skyKey);
    assertThat(pkg.containsErrors()).isFalse();
    assertNoEvents();
  }

  @Test
  public void testPackageRecordsLoadedModules() throws Exception {
    scratch.file("p/BUILD", "load('a.bzl', 'a'); load(':b.bzl', 'b')");
    scratch.file("p/a.bzl", "load('c.bzl', 'c'); a = c");
    scratch.file("p/b.bzl", "load(':c.bzl', 'c'); b = c");
    scratch.file("p/c.bzl", "load(':d.bzl', 'd'); c = d");
    scratch.file("p/d.bzl", "d = 0");

    // load p
    preparePackageLoading(rootDirectory);
    SkyKey skyKey = PackageIdentifier.createInMainRepo("p");
    Package p = validPackageWithoutErrors(skyKey);

    assertThat(toStrings(p.getOrComputeTransitivelyLoadedStarlarkFiles()))
        .containsExactly("//p:a.bzl", "//p:b.bzl", "//p:c.bzl", "//p:d.bzl");
    assertThat(p.countTransitivelyLoadedStarlarkFiles()).isEqualTo(4);

    // Custom visitation: c.bzl is visited twice, but the second time we don't recurse, so d.bzl is
    // only visited once.
    Multiset<Label> loads = HashMultiset.create();
    p.visitLoadGraph(load -> loads.add(load, 1) == 0);
    assertThat(toStrings(loads))
        .containsExactly("//p:a.bzl", "//p:b.bzl", "//p:c.bzl", "//p:c.bzl", "//p:d.bzl");
  }

  private static Stream<String> toStrings(Iterable<Label> labels) {
    return stream(labels).map(Label::toString);
  }

  @Test
  public void veryBrokenPackagePostsDoneToProgressReceiver() throws Exception {
    reporter.removeHandler(failFastHandler);

    // Note: syntax error (recovered), non-existent .bzl file.
    scratch.file("pkg/BUILD", "load('//does_not:exist.bzl', 'broken'");
    SkyKey key = PackageIdentifier.createInMainRepo("pkg");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(getSkyframeExecutor(), key, false, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(key);
    assertContainsEvent("syntax error at 'newline': expected ,");
    assertThat(getSkyframeExecutor().getPackageProgressReceiver().progressState())
        .isEqualTo(new Pair<>("1 packages loaded", ""));
  }

  @Test
  public void testNonSkyframeGlobbingEncountersSymlinkCycleAndThrowsIOException() throws Exception {
    reporter.removeHandler(failFastHandler);

    // When a package's BUILD file and the relevant filesystem state is such that non-Skyframe
    // globbing will encounter an IOException due to a directory symlink cycle,
    Path fooBUILDPath = scratch.file("foo/BUILD", "glob(['cycle/**/foo.txt'])");
    Path fooCyclePath = fooBUILDPath.getParentDirectory().getChild("cycle");
    FileSystemUtils.ensureSymbolicLink(fooCyclePath, fooCyclePath);
    IOException ioExnFromFS =
        assertThrows(IOException.class, () -> fooCyclePath.statIfFound(Symlinks.FOLLOW));
    // And it is indeed the case that the FileSystem throws an IOException when the cycle's Path is
    // stat'd (following symlinks, as non-Skyframe globbing does).
    assertThat(ioExnFromFS).hasMessageThat().contains("Too many levels of symbolic links");

    // Then, when we evaluate the PackageValue node for the Package in keepGoing mode,
    SkyKey pkgKey = PackageIdentifier.createInMainRepo("foo");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), pkgKey, /*keepGoing=*/ true, reporter);
    // The result is a *non-transient* Skyframe error.
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey).isNotTransient();
    // And that error is a NoSuchPackageException
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .isInstanceOf(NoSuchPackageException.class);
    // With a useful error message,
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("Symlink cycle: /workspace/foo/cycle");
    // And appropriate Skyframe root cause (N.B. since we want PackageFunction to rethrow in
    // situations like this, we want the PackageValue node to be its own root cause).
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey);

    // Then, when we modify the BUILD file so as to force package loading,
    scratch.overwriteFile(
        "foo/BUILD", "glob(['cycle/**/foo.txt']) # dummy comment to force package loading");

    if (!globUnderSingleDep) {
      // When globbing strategy is SKYFRAME_HYBRID (globUnderSingleDep = false), and we don't make
      // any filesystem changes that would invalidate the GlobValues, PackageFunction will observe
      // cache hits from Skyframe globbing.
      //
      // And we also have our filesystem blow up if the directory symlink cycle is encountered
      // (thus, the absence of a crash indicates the lack of non-Skyframe globbing).
      //
      // However, when globbing strategy is GLOBS (globUnderSingleDep = true), and we lose Skyframe
      // Hybrid globbing, we expect package reloading still to always do non-Skyframe globbing which
      // calls stats for symlink `foo/cycle`.
      fs.stubStatError(
          fooCyclePath,
          new IOException() {
            @Override
            public String getMessage() {
              throw new IllegalStateException("shouldn't get here!");
            }
          });
    }

    // And we evaluate the PackageValue node for the Package in keepGoing mode,
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("foo/BUILD")).build(),
            Root.fromPath(rootDirectory));
    // The results are exactly the same as before,
    result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), pkgKey, /*keepGoing=*/ true, reporter);
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(pkgKey).isNotTransient();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .isInstanceOf(NoSuchPackageException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(pkgKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("Symlink cycle: /workspace/foo/cycle");
    // Thus showing that clean and incremental package loading have the same semantics in the
    // presence of a symlink cycle encountered during glob evaluation.
  }

  @Test
  public void testGlobbingSkyframeDependencyStructure() throws Exception {
    reporter.removeHandler(failFastHandler);

    Root pkgRoot = getSkyframeExecutor().getPathEntries().get(0);

    Path fooBuildPath =
        scratch.file("foo/BUILD", "glob(['dir/*.sh'])", "subpackages(include = ['subpkg/**'])");

    Path fooDirPath = fooBuildPath.getParentDirectory().getChild("dir");
    scratch.file("foo/dir/bar.sh");
    scratch.file("foo/dir/baz.sh");

    Path fooSubpkgPath = fooBuildPath.getParentDirectory().getChild("subpkg");
    scratch.file("foo/subpkg/BUILD");

    SkyKey pkgKey = PackageIdentifier.createInMainRepo("foo");
    SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(), pkgKey, /* keepGoing= */ true, reporter);

    InMemoryGraph graph = getSkyframeExecutor().memoizingEvaluator.getInMemoryGraph();
    InMemoryNodeEntry packageNode = graph.getIfPresent(pkgKey);
    if (globUnderSingleDep) {
      // The package subgraph with single Globs node looks like this:
      // PKG["foo"]
      //  |- GLOBS[["dir/*.sh", FILES], ["subpkg/**", SUBPACKAGES]]
      //      |- FILE["foo/dir"]
      //      |- DIRECTORY_LISTING["foo/dir"]
      //      |- PACKAGE_LOOKUP["foo/dir"]
      //      |- FILE["foo/subdir"]
      //      |- PACKAGE_LOOKUP["foo/subdir"]
      GlobsValue.Key globsKey =
          GlobsValue.key(
              PackageIdentifier.createInMainRepo("foo"),
              pkgRoot,
              ImmutableSet.of(
                  GlobRequest.create("dir/*.sh", Operation.FILES),
                  GlobRequest.create("subpkg/**", Operation.SUBPACKAGES)));
      assertThat(packageNode.getDirectDeps()).contains(globsKey);

      InMemoryNodeEntry globsNode = graph.getIfPresent(globsKey);
      SkyValue globsValue = globsNode.getValue();
      assertThat(globsValue).isInstanceOf(GlobsValue.class);
      assertThat(((GlobsValue) globsValue).getMatches())
          .containsExactly(
              PathFragment.create("subpkg"),
              PathFragment.create("dir/bar.sh"),
              PathFragment.create("dir/baz.sh"));
      ImmutableSet<SkyKey> globsDirectDeps = ImmutableSet.copyOf(globsNode.getDirectDeps());
      assertThat(globsDirectDeps)
          .containsAtLeast(
              DirectoryListingValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
              FileValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
              FileValue.key(RootedPath.toRootedPath(pkgRoot, fooSubpkgPath)),
              PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/dir")),
              PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/subpkg")));
    } else {
      // The package subgraph with multiple Glob nodes looks like this:
      // PKG["foo"]
      //  |- GLOB["dir/*.sh", FILES]
      //      |- FILE["foo/dir"]
      //      |- DIRECTORY_LISTING["foo/dir"]
      //      |- PACKAGE_LOOKUP["foo/dir"]
      //  |- GLOB["subpkg/**", SUBPACKAGES]
      //      |- FILE["foo/subdir"]
      //      |- PACKAGE_LOOKUP["foo/subdir"]
      GlobDescriptor dirGlobDescriptor =
          GlobValue.key(
              PackageIdentifier.createInMainRepo("foo"),
              pkgRoot,
              /* pattern= */ "dir/*.sh",
              Operation.FILES,
              PathFragment.EMPTY_FRAGMENT);
      GlobDescriptor subdirGlobDescriptor =
          GlobValue.key(
              PackageIdentifier.createInMainRepo("foo"),
              pkgRoot,
              /* pattern= */ "subpkg/**",
              Operation.SUBPACKAGES,
              PathFragment.EMPTY_FRAGMENT);
      assertThat(packageNode.getDirectDeps())
          .containsAtLeast(dirGlobDescriptor, subdirGlobDescriptor);

      ImmutableSet<SkyKey> dirGlobNodeDeps =
          ImmutableSet.copyOf(graph.getIfPresent(dirGlobDescriptor).getDirectDeps());
      assertThat(dirGlobNodeDeps)
          .containsAtLeast(
              DirectoryListingValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
              FileValue.key(RootedPath.toRootedPath(pkgRoot, fooDirPath)),
              PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/dir")));

      ImmutableSet<SkyKey> subdirGlobNodeDeps =
          ImmutableSet.copyOf(graph.getIfPresent(subdirGlobDescriptor).getDirectDeps());
      assertThat(subdirGlobNodeDeps)
          .containsAtLeast(
              FileValue.key(RootedPath.toRootedPath(pkgRoot, fooSubpkgPath)),
              PackageLookupValue.key(PackageIdentifier.createInMainRepo("foo/subpkg")));
    }
  }

  private static void assertDetailedExitCode(
      Exception exception, PackageLoading.Code expectedPackageLoadingCode, ExitCode exitCode) {
    assertThat(exception).isInstanceOf(DetailedException.class);
    DetailedExitCode detailedExitCode = ((DetailedException) exception).getDetailedExitCode();
    assertThat(detailedExitCode.getExitCode()).isEqualTo(exitCode);
    assertThat(detailedExitCode.getFailureDetail().getPackageLoading().getCode())
        .isEqualTo(expectedPackageLoadingCode);
    assertThat(DetailedExitCode.getExitCode(detailedExitCode.getFailureDetail()))
        .isEqualTo(exitCode);
  }

  /**
   * Tests of the prelude file functionality.
   *
   * <p>This is in a separate BuildViewTestCase because we override the prelude label for the test.
   * (The prelude label is configured differently between Bazel and Blaze.)
   */
  @RunWith(JUnit4.class)
  public static class PreludeTest extends BuildViewTestCase {

    private final CustomInMemoryFs fs = new CustomInMemoryFs(new ManualClock());

    @Override
    protected FileSystem createFileSystem() {
      return fs;
    }

    @Override
    protected ConfiguredRuleClassProvider createRuleClassProvider() {
      ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
      // addStandardRules() may call setPrelude(), so do it first.
      TestRuleClassProvider.addStandardRules(builder);
      builder.setPrelude("//tools/test_build_rules:test_prelude");
      return builder.build();
    }

    @Test
    public void testPreludeDefinedSymbolIsUsable() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(foo)");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeAutomaticallyReexportsLoadedSymbols() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "load('//util:common.bzl', 'foo')");
      scratch.file("util/BUILD");
      scratch.file(
          "util/common.bzl", //
          "foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(foo)");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    // TODO(brandjon): Invert this test once the prelude is a module instead of a syntactic
    // mutation on BUILD files.
    @Test
    public void testPreludeCanExportUnderscoreSymbols() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "_foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(_foo)");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeCanShadowUniversal() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "len = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(len)");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeCanShadowPredeclareds() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "cc_library = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(cc_library)");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeCanShadowInjectedPredeclareds() throws Exception {
      setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging");
      scratch.file(
          "tools/builtins_staging/exports.bzl",
          """
          exported_toplevels = {}
          exported_rules = {"cc_library": "BAR"}
          exported_to_java = {}
          """);
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "cc_library = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(cc_library)");

      try {
        invalidatePackages();
      } catch (
          @SuppressWarnings("InterruptedExceptionSwallowed")
          Exception e) {
        // Ignore any errors.
      }

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeSymbolCannotBeMutated() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "foo = ['FOO']");
      scratch.file(
          "pkg/BUILD", //
          "foo.append('BAR')");

      reporter.removeHandler(failFastHandler);
      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("trying to mutate a frozen list value");
    }

    @Test
    public void testPreludeCanAccessBzlDialectFeatures() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      // Test both bzl symbols and syntax (e.g. function defs).
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "def foo():",
          "    return native.glob");
      scratch.file(
          "pkg/BUILD", //
          "print(foo())");

      invalidatePackages();

      getConfiguredTarget("//pkg:BUILD");
      // Prelude can access native.glob (though only a BUILD thread can call it).
      assertContainsEvent("<built-in method glob of native value>");
    }

    @Test
    public void testPreludeNeedNotBePresent() throws Exception {
      scratch.file(
          "pkg/BUILD", //
          "print('FOO')");

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeNeedNotBePresent_evenWhenPackageIs() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "pkg/BUILD", //
          "print('FOO')");

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    @Test
    public void testPreludeFileNotRecognizedWithoutPackage() throws Exception {
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(foo)");

      // The prelude file is not found without a corresponding package to contain it. BUILD files
      // get processed as if no prelude file is present.
      reporter.removeHandler(failFastHandler);
      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("name 'foo' is not defined");
    }

    @Test
    public void testPreludeFailsWhenErrorInPreludeFile() throws Exception {
      scratch.file("tools/test_build_rules/BUILD");
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "1//0", // <-- dynamic error
          "foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(foo)");

      reporter.removeHandler(failFastHandler);

      try {
        invalidatePackages();
      } catch (
          @SuppressWarnings("InterruptedExceptionSwallowed")
          Exception e) {
        // Ignore any errors.
      }

      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent(
          "File \"/workspace/tools/test_build_rules/test_prelude\", line 1, column 2, in"
              + " <toplevel>");
      assertContainsEvent("Error: integer division by zero");
    }

    @Test
    public void testPreludeWorksEvenWhenPreludePackageInError() throws Exception {
      scratch.file(
          "tools/test_build_rules/BUILD", //
          "1//0"); // <-- dynamic error
      scratch.file(
          "tools/test_build_rules/test_prelude", //
          "foo = 'FOO'");
      scratch.file(
          "pkg/BUILD", //
          "print(foo)");

      invalidatePackages();

      // Succeeds because prelude loading is only dependent on the prelude package's existence, not
      // its evaluation.
      getConfiguredTarget("//pkg:BUILD");
      assertContainsEvent("FOO");
    }

    // Another hypothetical test case we could try: Confirm that it's possible to explicitly load
    // the prelude file as a regular .bzl. We don't bother testing this use case because, aside from
    // being arguably pathological, it is currently impossible in practice: The prelude label
    // doesn't end with ".bzl" and isn't configurable by the user. We also want to eliminate the
    // prelude, so there's no intention of adding such a feature.

    // Another possible test case: Verify how prelude applies to WORKSPACE files.
  }

  private static class CustomInMemoryFs extends InMemoryFileSystem {
    private abstract static class FileStatusOrException {
      abstract FileStatus get() throws IOException;

      private static class ExceptionImpl extends FileStatusOrException {
        private final IOException exn;

        private ExceptionImpl(IOException exn) {
          this.exn = exn;
        }

        @Override
        FileStatus get() throws IOException {
          throw exn;
        }
      }

      private static class FileStatusImpl extends FileStatusOrException {

        @Nullable private final FileStatus fileStatus;

        private FileStatusImpl(@Nullable FileStatus fileStatus) {
          this.fileStatus = fileStatus;
        }

        @Override
        @Nullable
        FileStatus get() {
          return fileStatus;
        }
      }
    }

    private final Map<PathFragment, FileStatusOrException> stubbedStats = Maps.newHashMap();
    private final Set<PathFragment> makeUnreadableAfterReaddir = Sets.newHashSet();
    private final Map<PathFragment, IOException> pathsToErrorOnGetInputStream = Maps.newHashMap();

    CustomInMemoryFs(ManualClock manualClock) {
      super(manualClock, DigestHashFunction.SHA256);
    }

    void stubStat(Path path, @Nullable FileStatus stubbedResult) {
      stubbedStats.put(path.asFragment(), new FileStatusOrException.FileStatusImpl(stubbedResult));
    }

    void stubStatError(Path path, IOException stubbedResult) {
      stubbedStats.put(path.asFragment(), new FileStatusOrException.ExceptionImpl(stubbedResult));
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (stubbedStats.containsKey(path)) {
        return stubbedStats.get(path).get();
      }
      return super.statIfFound(path, followSymlinks);
    }

    void scheduleMakeUnreadableAfterReaddir(Path path) {
      makeUnreadableAfterReaddir.add(path.asFragment());
    }

    @Override
    public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
        throws IOException {
      Collection<Dirent> result = super.readdir(path, followSymlinks);
      if (makeUnreadableAfterReaddir.contains(path)) {
        setReadable(path, false);
      }
      return result;
    }

    void throwExceptionOnGetInputStream(Path path, IOException exn) {
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
