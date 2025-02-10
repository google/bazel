// Copyright 2024 The Bazel Authors. All rights reserved.
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
import static org.junit.Assert.assertThrows;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.packages.Globber.Operation;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.ForOverride;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;

public abstract class GlobTestBase {
  protected static final EvaluationContext EVALUATION_OPTIONS =
      EvaluationContext.newBuilder()
          .setKeepGoing(false)
          .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
          .setEventHandler(NullEventHandler.INSTANCE)
          .build();

  private CustomInMemoryFs fs;
  protected MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  protected Path root;
  private Path writableRoot;
  protected Path pkgPath;
  private AtomicReference<PathPackageLocator> pkgLocator;

  protected static final PackageIdentifier PKG_ID = PackageIdentifier.createInMainRepo("pkg");

  @Before
  public final void setUp() throws Exception {
    fs = new CustomInMemoryFs(new ManualClock());
    root = fs.getPath("/root/workspace");
    writableRoot = fs.getPath("/writableRoot/workspace");
    pkgPath = root.getRelative(PKG_ID.getPackageFragment());

    pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                fs.getPath("/output_base"),
                ImmutableList.of(Root.fromPath(writableRoot), Root.fromPath(root)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));

    differencer = new SequencedRecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(createFunctionMap(), differencer);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());

    createTestFiles();
  }

  private Map<SkyFunctionName, SkyFunction> createFunctionMap() {
    AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages =
        new AtomicReference<>(ImmutableSet.of());
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(root, root, root),
            root,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

    AnalysisMock analysisMock = AnalysisMock.get();
    RuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    createGlobSkyFunction(skyFunctions);
    skyFunctions.put(
        SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE));
    skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages,
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.REPO_FILE,
        new RepoFileFunction(
            ruleClassProvider.getBazelStarlarkEnvironment(), directories.getWorkspace()));
    skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.INSTANCE);
    skyFunctions.put(
        FileStateKey.FILE_STATE,
        new FileStateFunction(
            Suppliers.ofInstance(new TimestampGranularityMonitor(BlazeClock.instance())),
            SyscallCache.NO_CACHE,
            externalFilesHelper));
    skyFunctions.put(
        FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
        new FileSymlinkCycleUniquenessFunction());
    skyFunctions.put(SkyFunctions.FILE, new FileFunction(pkgLocator, directories));
    skyFunctions.put(
        FileSymlinkCycleUniquenessFunction.NAME, new FileSymlinkCycleUniquenessFunction());
    skyFunctions.put(
        WorkspaceFileValue.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            ruleClassProvider,
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fs),
            directories,
            /* bzlLoadFunctionForInlining= */ null));
    skyFunctions.put(
        SkyFunctions.EXTERNAL_PACKAGE,
        new ExternalPackageFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
        new LocalRepositoryLookupFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.REPOSITORY_MAPPING,
        new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) {
            return RepositoryMappingValue.VALUE_FOR_ROOT_MODULE_WITHOUT_REPOS;
          }
        });
    skyFunctions.put(
        BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
        new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) {
            return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
          }
        });
    return skyFunctions;
  }

  @ForOverride
  protected abstract void createGlobSkyFunction(Map<SkyFunctionName, SkyFunction> skyFunctions);

  protected boolean alwaysUsesDirListing() {
    return false;
  }

  private void createTestFiles() throws IOException {
    pkgPath.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("BUILD"));
    for (String dir :
        ImmutableList.of(
            "foo/bar/wiz", "foo/barnacle/wiz", "food/barnacle/wiz", "fool/barnacle/wiz")) {
      pkgPath.getRelative(dir).createDirectoryAndParents();
    }
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/wiz/file"));

    // Used for testing the behavior of globbing into nested subpackages.
    for (String dir : ImmutableList.of("a1/b1/c", "a2/b2/c")) {
      pkgPath.getRelative(dir).createDirectoryAndParents();
    }
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("a2/b2/BUILD"));
  }

  @Test
  public void testSimple() throws Exception {
    assertSingleGlobMatches("food", /* => */ "food");
  }

  @Test
  public void testIgnoreList() throws Exception {
    FileSystemUtils.writeContentAsLatin1(root.getRelative(".bazelignore"), "pkg/foo/bar");
    assertSingleGlobMatches("foo/**", "foo/barnacle/wiz", "foo/barnacle", "foo");
    differencer.invalidate(
        ImmutableList.of(
            FileStateValue.key(
                RootedPath.toRootedPath(
                    Root.fromPath(root), PathFragment.create(".bazelignore")))));

    FileSystemUtils.createEmptyFile(root.getRelative(".bazelignore"));
    assertSingleGlobMatches(
        "foo/**",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/bar",
        "foo/barnacle/wiz",
        "foo/barnacle",
        "foo");
  }

  @Test
  public void testStartsWithStar() throws Exception {
    assertSingleGlobMatches("*oo", /* => */ "foo");
  }

  @Test
  public void testStartsWithStarWithMiddleStar() throws Exception {
    assertSingleGlobMatches("*f*o", /* => */ "foo");
  }

  @Test
  public void testSingleMatchEqual() throws Exception {
    assertGlobsEqual("*oo", "*f*o"); // both produce "foo"
  }

  @Test
  public void testEndsWithStar() throws Exception {
    assertSingleGlobMatches("foo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testEndsWithStarWithMiddleStar() throws Exception {
    assertSingleGlobMatches("f*oo*", /* => */ "foo", "food", "fool");
  }

  @Test
  public void testMultipleMatchesEqual() throws Exception {
    assertGlobsEqual("foo*", "f*oo*"); // both produce "foo", "food", "fool"
  }

  @Test
  public void testMiddleStar() throws Exception {
    assertSingleGlobMatches("f*o", /* => */ "foo");
  }

  @Test
  public void testTwoMiddleStars() throws Exception {
    assertSingleGlobMatches("f*o*o", /* => */ "foo");
  }

  @Test
  public void testSingleStarPatternWithNamedChild() throws Exception {
    assertSingleGlobMatches("*/bar", /* => */ "foo/bar");
  }

  @Test
  public void testDeepSubpackages() throws Exception {
    assertSingleGlobMatches("*/*/c", /* => */ "a1/b1/c");
  }

  @Test
  public void testSingleStarPatternWithChildGlob() throws Exception {
    assertSingleGlobMatches(
        "*/bar*", /* => */ "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testSingleStarAsChildGlob() throws Exception {
    assertSingleGlobMatches("foo/*/wiz", /* => */ "foo/bar/wiz", "foo/barnacle/wiz");
  }

  @Test
  public void testNoAsteriskAndFilesDontExist() throws Exception {
    // Note un-UNIX like semantics:
    assertSingleGlobMatches("ceci/n'est/pas/une/globbe" /* => nothing */);
  }

  @Test
  public void testSingleAsteriskUnderNonexistentDirectory() throws Exception {
    // Note un-UNIX like semantics:
    assertSingleGlobMatches("not-there/*" /* => nothing */);
  }

  @Test
  public void testDifferentGlobsSameResultEqual() throws Exception {
    // Once the globs are run, it doesn't matter what pattern ran; only the output.
    assertGlobsEqual("not-there/*", "syzygy/*"); // Both produce nothing.
  }

  @Test
  public void testGlobUnderFile() throws Exception {
    assertSingleGlobMatches("foo/bar/wiz/file/*" /* => nothing */);
  }

  @Test
  public void testGlobEqualsHashCode() throws Exception {
    // Each "equality group" forms a set of elements that are all equals() to one another,
    // and also produce the same hashCode.
    new EqualsTester()
        .addEqualityGroup(
            runSingleGlob("no-such-file", Globber.Operation.FILES_AND_DIRS)) // Matches nothing.
        .addEqualityGroup(
            runSingleGlob("BUILD", Globber.Operation.FILES_AND_DIRS),
            runSingleGlob("BUILD", Globber.Operation.FILES)) // Matches BUILD.
        .addEqualityGroup(
            runSingleGlob("**", Globber.Operation.FILES_AND_DIRS)) // Matches lots of things.
        .addEqualityGroup(
            runSingleGlob("f*o/bar*", Globber.Operation.FILES_AND_DIRS),
            runSingleGlob(
                "foo/bar*", Globber.Operation.FILES_AND_DIRS)) // Matches foo/bar and foo/barnacle.
        .testEquals();
  }

  @Test
  public void testGlobDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/BUILD"));
    // "foo/bar" should not be in the results because foo is a separate package.
    assertSingleGlobMatches("f*/*", /* => */ "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testGlobDirectoryMatchDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"));
    // "foo/bar" should not be in the results because foo/bar is a separate package.
    assertSingleGlobMatches("foo/*", /* => */ "foo/barnacle");
  }

  @Test
  public void testStarStarDoesNotCrossPackageBoundary() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"));
    // "foo/bar" should not be in the results because foo/bar is a separate package.
    assertSingleGlobMatches("foo/**", /* => */ "foo/barnacle/wiz", "foo/barnacle", "foo");
  }

  @Test
  public void testGlobDoesNotCrossPackageBoundaryUnderOtherPackagePath() throws Exception {
    writableRoot.getRelative("pkg/foo/bar").createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(writableRoot.getRelative("pkg/foo/bar/BUILD"));
    // "foo/bar" should not be in the results because foo/bar is detected as a separate package,
    // even though it is under a different package path.
    assertSingleGlobMatches("foo/**", /* => */ "foo/barnacle/wiz", "foo/barnacle", "foo");
  }

  /**
   * For {@link GlobFunctionTest}, creates a {@link GlobDescriptor} using the input pattern.
   *
   * <p>For {@link GlobsFunctionTest}, creates a {@link GlobsValue.Key} whose {@code globRequests}
   * member contains only one element. The sole element's pattern is the input one.
   *
   * <p>Queries the {@link GlobDescriptor} or {@link GlobsValue.Key} in Skyframe and asserts that
   * matches in the result {@link GlobValue} or {@link GlobsValue} is equal to the input {@code
   * expecteds}.
   */
  private void assertSingleGlobMatches(String pattern, String... expecteds) throws Exception {
    assertSingleGlobMatches(pattern, Operation.FILES_AND_DIRS, expecteds);
  }

  protected abstract void assertSingleGlobMatches(
      String pattern, Globber.Operation globberOperation, String... expecteds) throws Exception;

  private void assertGlobWithoutDirsMatches(String pattern, String... expecteds) throws Exception {
    assertSingleGlobMatches(pattern, Globber.Operation.FILES, expecteds);
  }

  protected void assertGlobsEqual(String pattern1, String pattern2) throws Exception {
    SkyValue value1 = runSingleGlob(pattern1, Globber.Operation.FILES_AND_DIRS);
    SkyValue value2 = runSingleGlob(pattern2, Globber.Operation.FILES_AND_DIRS);
    new EqualsTester().addEqualityGroup(value1, value2).testEquals();
  }

  protected abstract SkyValue runSingleGlob(String pattern, Globber.Operation globberOperation)
      throws Exception;

  @Test
  public void testGlobWithoutWildcards() throws Exception {
    String pattern = "foo/bar/wiz/file";

    assertSingleGlobMatches(pattern, "foo/bar/wiz/file");
    // Ensure that the glob depends on the FileValue and not on the DirectoryListingValue.
    pkgPath.getRelative("foo/bar/wiz/file").delete();

    // Nothing has been invalidated yet, so the cached result is returned.
    assertSingleGlobMatches(pattern, "foo/bar/wiz/file");

    if (alwaysUsesDirListing()) {
      differencer.invalidate(
          ImmutableList.of(
              FileStateValue.key(
                  RootedPath.toRootedPath(
                      Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file")))));
      // The result should not rely on the FileStateValue, so it's still a cache hit.
      assertSingleGlobMatches(pattern, "foo/bar/wiz/file");

      differencer.invalidate(
          ImmutableList.of(
              DirectoryListingStateValue.key(
                  RootedPath.toRootedPath(
                      Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz")))));
    } else {
      differencer.invalidate(
          ImmutableList.of(
              DirectoryListingStateValue.key(
                  RootedPath.toRootedPath(
                      Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz")))));
      // The result should not rely on the DirectoryListingValue, so it's still a cache hit.
      assertSingleGlobMatches(pattern, "foo/bar/wiz/file");

      differencer.invalidate(
          ImmutableList.of(
              FileStateValue.key(
                  RootedPath.toRootedPath(
                      Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file")))));
    }

    // This should have invalidated the glob result.
    assertSingleGlobMatches(pattern /* => nothing */);
  }

  @Test
  public void testIllegalPatterns() {
    assertIllegalPattern("foo**bar");
    assertIllegalPattern("?");
    assertIllegalPattern("");
    assertIllegalPattern(".");
    assertIllegalPattern("/foo");
    assertIllegalPattern("./foo");
    assertIllegalPattern("foo/");
    assertIllegalPattern("foo/./bar");
    assertIllegalPattern("../foo/bar");
    assertIllegalPattern("foo//bar");
  }

  @Test
  public void testIllegalRecursivePatterns() throws Exception {
    for (String prefix : Lists.newArrayList("", "*/", "**/", "ba/")) {
      String suffix = ("/" + prefix).substring(0, prefix.length());
      for (String pattern : Lists.newArrayList("**fo", "fo**", "**fo**", "fo**fo", "fo**fo**fo")) {
        assertIllegalPattern(prefix + pattern);
        assertIllegalPattern(pattern + suffix);
      }
    }
  }

  protected abstract void assertIllegalPattern(String pattern);

  /** Tests that globs can contain Java regular expression special characters */
  @Test
  public void testSpecialRegexCharacter() throws Exception {
    Path aDotB = pkgPath.getChild("a.b");
    FileSystemUtils.createEmptyFile(aDotB);
    FileSystemUtils.createEmptyFile(pkgPath.getChild("aab"));
    // Note: this contains two asterisks because otherwise a RE is not built,
    // as an optimization.
    assertThat(
            new UnixGlob.Builder(pkgPath, SyscallCache.NO_CACHE)
                .addPattern("*a.b*")
                .globInterruptible())
        .containsExactly(aDotB);
  }

  @Test
  public void testMatchesCallWithNoCache() {
    assertThat(UnixGlob.matches("*a*b", "CaCb", null)).isTrue();
  }

  @Test
  public void testHiddenFiles() throws Exception {
    for (String dir : ImmutableList.of(".hidden", "..also.hidden", "not.hidden")) {
      pkgPath.getRelative(dir).createDirectoryAndParents();
    }
    // Note that these are not in the result: ".", ".."
    assertSingleGlobMatches(
        "*", "..also.hidden", ".hidden", "BUILD", "a1", "a2", "foo", "food", "fool", "not.hidden");
    assertSingleGlobMatches("*.hidden", "not.hidden");
  }

  @Test
  public void testDoubleStar() throws Exception {
    assertSingleGlobMatches(
        "**",
        "a1/b1/c",
        "a1/b1",
        "a1",
        "a2",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/bar",
        "foo/barnacle/wiz",
        "foo/barnacle",
        "foo",
        "food/barnacle/wiz",
        "food/barnacle",
        "food",
        "fool/barnacle/wiz",
        "fool/barnacle",
        "fool",
        "BUILD");
  }

  @Test
  public void testDoubleStarExcludeDirs() throws Exception {
    assertGlobWithoutDirsMatches("**", "foo/bar/wiz/file", "BUILD");
  }

  @Test
  public void testDoubleDoubleStar() throws Exception {
    assertSingleGlobMatches(
        "**/**",
        "a1/b1/c",
        "a1/b1",
        "a1",
        "a2",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/bar",
        "foo/barnacle/wiz",
        "foo/barnacle",
        "foo",
        "food/barnacle/wiz",
        "food/barnacle",
        "food",
        "fool/barnacle/wiz",
        "fool/barnacle",
        "fool",
        "BUILD");
  }

  @Test
  public void testDirectoryWithDoubleStar() throws Exception {
    assertSingleGlobMatches(
        "foo/**",
        "foo/bar/wiz",
        "foo/bar/wiz/file",
        "foo/bar",
        "foo/barnacle/wiz",
        "foo/barnacle",
        "foo");
  }

  @Test
  public void testDoubleStarPatternWithNamedChild() throws Exception {
    assertSingleGlobMatches("**/bar", "foo/bar");
  }

  @Test
  public void testDoubleStarPatternWithErrorChild() throws Exception {
    FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("self"), "self");

    IOException ioException =
        assertThrows(IOException.class, () -> runSingleGlob("**/self", Operation.FILES));
    assertThat(ioException).hasMessageThat().matches("Symlink cycle");
  }

  @Test
  public void testDoubleStarPatternWithChildGlob() throws Exception {
    assertSingleGlobMatches("**/ba*", "foo/bar", "foo/barnacle", "food/barnacle", "fool/barnacle");
  }

  @Test
  public void testDoubleStarAsChildGlob() throws Exception {
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/barnacle/wiz/wiz"));
    pkgPath.getRelative("foo/barnacle/baz/wiz").createDirectoryAndParents();

    assertSingleGlobMatches(
        "foo/**/wiz",
        "foo/bar/wiz",
        "foo/barnacle/wiz",
        "foo/barnacle/baz/wiz",
        "foo/barnacle/wiz/wiz");
  }

  @Test
  public void testDoubleStarUnderNonexistentDirectory() throws Exception {
    assertSingleGlobMatches("not-there/**" /* => nothing */);
  }

  @Test
  public void testDoubleStarUnderFile() throws Exception {
    assertSingleGlobMatches("foo/bar/wiz/file/**" /* => nothing */);
  }

  protected abstract SkyKey createdGlobRelatedSkyKey(
      String pattern, Globber.Operation globberOperation) throws InvalidGlobPatternException;

  /** Regression test for b/13319874: Directory listing crash. */
  @Test
  public void testResilienceToFilesystemInconsistencies_directoryExistence() throws Exception {
    // Our custom filesystem says "pkgPath/BUILD" exists but "pkgPath" does not exist.
    fs.stubStat(pkgPath, null);
    RootedPath pkgRootedPath = RootedPath.toRootedPath(Root.fromPath(root), pkgPath);
    FileStateValue pkgDirFileStateValue =
        FileStateValue.create(pkgRootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null);
    FileValue pkgDirValue =
        FileValue.value(
            ImmutableList.of(pkgRootedPath),
            null,
            null,
            pkgRootedPath,
            pkgDirFileStateValue,
            pkgRootedPath,
            pkgDirFileStateValue);
    differencer.inject(ImmutableMap.of(FileValue.key(pkgRootedPath), Delta.justNew(pkgDirValue)));
    String expectedMessage = "/root/workspace/pkg is no longer an existing directory";
    SkyKey skyKey = createdGlobRelatedSkyKey("*/foo", Operation.FILES_AND_DIRS);
    EvaluationResult<GlobValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage);
  }

  @Test
  public void testResilienceToFilesystemInconsistencies_subdirectoryExistence() throws Exception {
    // Our custom filesystem says directory "pkgPath/foo/bar" contains a subdirectory "wiz" but a
    // direct stat on "pkgPath/foo/bar/wiz" says it does not exist.
    Path fooBarDir = pkgPath.getRelative("foo/bar");
    fs.stubStat(fooBarDir.getRelative("wiz"), null);
    RootedPath fooBarDirRootedPath = RootedPath.toRootedPath(Root.fromPath(root), fooBarDir);
    SkyValue fooBarDirListingValue =
        DirectoryListingStateValue.create(
            ImmutableList.of(new Dirent("wiz", Dirent.Type.DIRECTORY)));
    differencer.inject(
        ImmutableMap.of(
            DirectoryListingStateValue.key(fooBarDirRootedPath),
            Delta.justNew(fooBarDirListingValue)));
    String expectedMessage = "/root/workspace/pkg/foo/bar/wiz is no longer an existing directory.";
    SkyKey skyKey = createdGlobRelatedSkyKey("**/wiz", Globber.Operation.FILES_AND_DIRS);
    EvaluationResult<GlobValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage);
  }

  @Test
  public void testResilienceToFilesystemInconsistencies_symlinkType() throws Exception {
    RootedPath wizRootedPath =
        RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz"));
    RootedPath fileRootedPath =
        RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("foo/bar/wiz/file"));
    final FileStatus realStat = fileRootedPath.asPath().stat();
    fs.stubStat(
        fileRootedPath.asPath(),
        new FileStatus() {

          @Override
          public boolean isFile() {
            // The stat says foo/bar/wiz/file is a real file, not a symlink.
            return true;
          }

          @Override
          public boolean isSpecialFile() {
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
          public long getSize() throws IOException {
            return realStat.getSize();
          }

          @Override
          public long getLastModifiedTime() throws IOException {
            return realStat.getLastModifiedTime();
          }

          @Override
          public long getLastChangeTime() throws IOException {
            return realStat.getLastChangeTime();
          }

          @Override
          public long getNodeId() throws IOException {
            return realStat.getNodeId();
          }
        });
    // But the dir listing say foo/bar/wiz/file is a symlink.
    SkyValue wizDirListingValue =
        DirectoryListingStateValue.create(
            ImmutableList.of(new Dirent("file", Dirent.Type.SYMLINK)));
    differencer.inject(
        ImmutableMap.of(
            DirectoryListingStateValue.key(wizRootedPath), Delta.justNew(wizDirListingValue)));
    String expectedMessage =
        "readdir and stat disagree about whether " + fileRootedPath.asPath() + " is a symlink";
    SkyKey skyKey = createdGlobRelatedSkyKey("foo/bar/wiz/*", Globber.Operation.FILES_AND_DIRS);
    EvaluationResult<GlobValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException()).hasMessageThat().contains(expectedMessage);
  }

  /**
   * When globbing symlinks, the returned path should use the path of the symlink source instead of
   * the symlink target, regardless of whether glob pattern contains wildcard character or not.
   */
  @Test
  public void testSymlinks(@TestParameter boolean withWildcard) throws Exception {
    pkgPath.getRelative("symlinks").createDirectoryAndParents();
    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/dangling.txt"), "nope");
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("symlinks/yup"));
    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/existing.txt"), "yup");

    String globPattern = withWildcard ? "symlinks/*.txt" : "symlinks/existing.txt";
    assertSingleGlobMatches(globPattern, "symlinks/existing.txt");
  }

  @Test
  public void testSymlinks_symlinkPointToDirectory() throws Exception {
    root.getRelative("target_direc").createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(root.getRelative("target_direc/file1"));
    root.getRelative("target_direc/sub").createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(root.getRelative("target_direc/sub/file2"));

    FileSystemUtils.ensureSymbolicLink(
        pkgPath.getRelative("symlink"), root.getRelative("target_direc"));
    assertSingleGlobMatches(
        "symlink/**", "symlink/sub", "symlink/sub/file2", "symlink", "symlink/file1");
  }

  @Test
  public void symlinkFileValueWithError_symlinkCycleToSelf() throws Exception {
    FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("self"), "self");

    IOException ioException =
        assertThrows(IOException.class, () -> runSingleGlob("self", Operation.FILES_AND_DIRS));
    assertThat(ioException).hasMessageThat().matches("Symlink cycle");
  }

  @Test
  public void symlinkFileValueWithError_symlinkCycleBetweenTwoSymlinks(
      @TestParameter boolean withWildcard) throws Exception {
    pkgPath.getRelative("foo").createDirectoryAndParents();
    pkgPath.getRelative("bar").createDirectoryAndParents();

    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("foo/a"), pkgPath.getRelative("bar/b"));
    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("bar/b"), pkgPath.getRelative("foo/a"));

    String globPattern = withWildcard ? "foo/*" : "foo/a";
    IOException ioException =
        assertThrows(IOException.class, () -> runSingleGlob(globPattern, Operation.FILES_AND_DIRS));
    assertThat(ioException).hasMessageThat().matches("Symlink cycle");
  }

  @Test
  public void symlinkSubdirValueWithError() throws Exception {
    Path cycle = pkgPath.getChild("cycle");
    FileSystemUtils.ensureSymbolicLink(cycle.getChild("self"), "self");
    FileSystemUtils.ensureSymbolicLink(pkgPath.getChild("symlink"), cycle);

    IOException ioException =
        assertThrows(
            IOException.class, () -> runSingleGlob("symlink/self", Operation.FILES_AND_DIRS));
    assertThat(ioException).hasMessageThat().matches("Symlink cycle");
  }

  @Test
  public void testSymlinks_unboundedSymlinkExpansion(@TestParameter boolean withRecursiveWildcard)
      throws Exception {
    pkgPath.getRelative("parent/sub").createDirectoryAndParents();
    FileSystemUtils.ensureSymbolicLink(
        pkgPath.getRelative("parent/sub/symlink"), pkgPath.getRelative("parent"));

    String globPattern = withRecursiveWildcard ? "parent/**" : "parent/sub/symlink";
    SkyKey skyKey = createdGlobRelatedSkyKey(globPattern, Globber.Operation.FILES_AND_DIRS);

    EvaluationResult<GlobValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);

    if (withRecursiveWildcard || alwaysUsesDirListing()) {
      assertThat(result.hasError()).isTrue();
      ErrorInfo errorInfo = result.getError(skyKey);
      assertThat(errorInfo.getException())
          .isInstanceOf(FileSymlinkInfiniteExpansionException.class);
      assertThat(errorInfo.getException()).hasMessageThat().contains("Infinite symlink expansion");
    } else {
      assertThat(result.hasError()).isFalse();
    }
  }

  /**
   * Covers the scenario when a directory has two symlinks of different status.
   *
   * <p>One of the symlinks is a normal one whose path should be accepted by {@code
   * SymlinkProducer}.
   *
   * <p>The other symlink shows different {@code readdir} and {@code stat} status. {@code readdir}
   * shows that it is a symlink but {@code stat} shows that it is a normal file. A {@link
   * InconsistentFilesystemException} should be accepted for this path by {@code SymlinkProducer}.
   *
   * <p>{@code PatternWithWildcardProducer} immediately returns {@code DONE} when knowing the number
   * of accepted symlink paths (1) is smaller than the number of symlink queried (2). The size
   * mismatch indicates that one of the symlinks goes wrong.
   */
  @Test
  public void testSymlinks_oneNormalOneInconsistencyFilesystemError() throws Exception {
    pkgPath.getRelative("inconsistent").createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(pkgPath.getRelative("target"));
    FileSystemUtils.ensureSymbolicLink(
        pkgPath.getRelative("inconsistent/good"), pkgPath.getRelative("target"));
    FileSystemUtils.ensureSymbolicLink(
        pkgPath.getRelative("inconsistent/bad"), pkgPath.getRelative("target"));

    RootedPath badRootedPath =
        RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("inconsistent/bad"));
    final FileStatus realStat = badRootedPath.asPath().stat();
    fs.stubStat(
        badRootedPath.asPath(),
        new FileStatus() {

          @Override
          public boolean isFile() {
            // Intentionally set `isFile` as true, which disagree with filesystem.
            return true;
          }

          @Override
          public boolean isSpecialFile() {
            return false;
          }

          @Override
          public boolean isDirectory() {
            return false;
          }

          @Override
          public boolean isSymbolicLink() {
            // Intentionally set `isSymbolicLink` as false, which disagree with filesystem.
            return false;
          }

          @Override
          public long getSize() throws IOException {
            return realStat.getSize();
          }

          @Override
          public long getLastModifiedTime() throws IOException {
            return realStat.getLastModifiedTime();
          }

          @Override
          public long getLastChangeTime() throws IOException {
            return realStat.getLastChangeTime();
          }

          @Override
          public long getNodeId() throws IOException {
            return realStat.getNodeId();
          }
        });

    SkyKey skyKey = createdGlobRelatedSkyKey("inconsistent/*", Globber.Operation.FILES_AND_DIRS);
    EvaluationResult<GlobValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException())
        .hasMessageThat()
        .contains("Inconsistent filesystem operations. readdir and stat disagree");
  }

  /**
   * The test below covers the case when {@link DirectoryListingValue} contains multiple symlinks,
   * which is common for bazel shell integration tests. Bazel shell integration tests usually create
   * symlinks for all source files.
   *
   * <p>Expect all matches to be returned when globbing multiple symlinks under the directory.
   */
  @Test
  public void testSymlinksUnderDirectory_shouldAllBeGlobbed() throws Exception {
    root.getRelative("targets").createDirectoryAndParents();
    pkgPath.getRelative("symlinks").createDirectoryAndParents();
    for (char c = 'a'; c <= 'z'; ++c) {
      FileSystemUtils.createEmptyFile(root.getRelative("targets/" + c + ".bzl"));
      FileSystemUtils.ensureSymbolicLink(
          pkgPath.getRelative("symlinks/" + c + ".bzl"), root.getRelative("targets/" + c + ".bzl"));
    }

    String[] allExpectedPathsInStr = new String[26];
    for (int i = 0; i < 26; ++i) {
      allExpectedPathsInStr[i] = "symlinks/" + (char) ('a' + i) + ".bzl";
    }
    assertSingleGlobMatches("symlinks/*.bzl", Operation.FILES_AND_DIRS, allExpectedPathsInStr);
  }

  static final class CustomInMemoryFs extends InMemoryFileSystem {
    private final Map<PathFragment, FileStatus> stubbedStats = Maps.newHashMap();

    CustomInMemoryFs(ManualClock manualClock) {
      super(manualClock, DigestHashFunction.SHA256);
    }

    public void stubStat(Path path, @Nullable FileStatus stubbedResult) {
      stubbedStats.put(path.asFragment(), stubbedResult);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (stubbedStats.containsKey(path)) {
        return stubbedStats.get(path);
      }
      return super.statIfFound(path, followSymlinks);
    }
  }

  private void assertSubpackageMatches(String pattern, String... expecteds) throws Exception {
    assertThat(getSubpackagesMatches(pattern))
        .containsExactlyElementsIn(ImmutableList.copyOf(expecteds));
  }

  protected abstract Iterable<String> getSubpackagesMatches(String pattern) throws Exception;

  private void makeEmptyPackage(Path newPackagePath) throws Exception {
    newPackagePath.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(newPackagePath.getRelative("BUILD"));
  }

  private void makeEmptyPackage(String path) throws Exception {
    makeEmptyPackage(pkgPath.getRelative(path));
  }

  @Test
  public void subpackages_simple() throws Exception {
    makeEmptyPackage("horse");
    makeEmptyPackage("monkey");
    makeEmptyPackage("horse/saddle");

    // "horse/saddle" should not be in the results because horse/saddle is too deep. a2/b2 added by
    // setup().
    assertSubpackageMatches("**", /* => */ "a2/b2", "horse", "monkey");
  }

  @Test
  public void subpackages_empty() throws Exception {
    assertSubpackageMatches("foo/*");
    assertSubpackageMatches("foo/**");
  }

  @Test
  public void subpackages_doubleStarPatternWithNamedChild() throws Exception {
    assertSubpackageMatches("**/bar");
  }

  @Test
  public void subpackages_noWildcard() throws Exception {
    makeEmptyPackage("sub1");
    makeEmptyPackage("sub2");
    makeEmptyPackage("sub3/deep");
    makeEmptyPackage("sub4/deeper/deeper");

    assertSubpackageMatches("sub");
    assertSubpackageMatches("sub1", "sub1");
    assertSubpackageMatches("sub2", "sub2");
    assertSubpackageMatches("sub3/deep", "sub3/deep");
    assertSubpackageMatches("sub4/deeper/deeper", "sub4/deeper/deeper");
  }

  @Test
  public void subpackages_zeroLevelDeep(@TestParameter boolean withDeeperSubpackage)
      throws Exception {
    makeEmptyPackage("sub");
    if (withDeeperSubpackage) {
      makeEmptyPackage("sub/subOfSub");
    }

    assertSubpackageMatches("sub/*");

    // `**` is considered to matching nothing below.
    assertSubpackageMatches("sub/**", "sub");
    assertSubpackageMatches("sub/**/**", "sub");

    assertSubpackageMatches("sub/**/foo");
    assertSubpackageMatches("sub/**/foo/**");
  }

  @Test
  public void subpackages_oneLevelDeep() throws Exception {
    makeEmptyPackage("base/sub");
    makeEmptyPackage("base/sub2");
    makeEmptyPackage("base/sub3");

    List<String> matchingPatterns =
        Arrays.asList("base/*", "base/**", "base/**/**", "base/**/sub*", "base/**/sub*/**");

    for (String pattern : matchingPatterns) {
      assertSubpackageMatches(pattern, /* => */ "base/sub", "base/sub2", "base/sub3");
    }
  }

  @Test
  public void subpackages_deepRecurse() throws Exception {
    makeEmptyPackage("base/sub/1");
    makeEmptyPackage("base/sub/2");
    makeEmptyPackage("base/sub2/3");
    makeEmptyPackage("base/sub2/4");
    makeEmptyPackage("base/sub3/5");
    makeEmptyPackage("base/sub3/6");

    FileSystemUtils.createEmptyFile(pkgPath.getRelative("foo/bar/BUILD"));

    // * doesn't go deep enough, so no matches
    assertSubpackageMatches("base/*");

    List<String> matchingPatterns =
        Arrays.asList("base/**", "base/*/*", "base/*/*/**", "base/*/*/**/**", "base/**/sub*/**");

    for (String pattern : matchingPatterns) {
      assertSubpackageMatches(
          pattern,
          "base/sub/1",
          "base/sub/2",
          "base/sub2/3",
          "base/sub2/4",
          "base/sub3/5",
          "base/sub3/6");
    }
  }

  @Test
  public void subpackages_middleWildcard() throws Exception {
    makeEmptyPackage("base/same");
    makeEmptyPackage("base/sub1/same");
    makeEmptyPackage("base/sub2/same");
    makeEmptyPackage("base/sub3/same");
    makeEmptyPackage("base/sub4/same");
    makeEmptyPackage("base/sub5/same");
    makeEmptyPackage("base/sub6/same");
    makeEmptyPackage("base/sub7/sub8/same");
    makeEmptyPackage("base/sub9/sub10/sub11/same");

    assertSubpackageMatches(
        "base/*/same",
        "base/sub1/same",
        "base/sub2/same",
        "base/sub3/same",
        "base/sub4/same",
        "base/sub5/same",
        "base/sub6/same");

    assertSubpackageMatches(
        "base/**/same",
        "base/same",
        "base/sub1/same",
        "base/sub2/same",
        "base/sub3/same",
        "base/sub4/same",
        "base/sub5/same",
        "base/sub6/same",
        "base/sub7/sub8/same",
        "base/sub9/sub10/sub11/same");
  }

  @Test
  public void subpackages_testSymlinks() throws Exception {
    Path newPackagePath = pkgPath.getRelative("path/to/pkg");
    makeEmptyPackage(newPackagePath);

    pkgPath.getRelative("symlinks").createDirectoryAndParents();
    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("symlinks/deeplink"), newPackagePath);
    FileSystemUtils.ensureSymbolicLink(pkgPath.getRelative("shallowlink"), newPackagePath);

    assertSubpackageMatches("**", "a2/b2", "symlinks/deeplink", "path/to/pkg", "shallowlink");
    assertSubpackageMatches("*", "shallowlink");

    assertSubpackageMatches("symlinks/**", "symlinks/deeplink");
    assertSubpackageMatches("symlinks/*", "symlinks/deeplink");
  }
}
