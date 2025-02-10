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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.skyframe.SkyframeExecutor.DEFAULT_THREAD_COUNT;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.FileValue.DifferentRealPathFileValueWithStoredChain;
import com.google.devtools.build.lib.actions.FileValue.DifferentRealPathFileValueWithoutStoredChain;
import com.google.devtools.build.lib.actions.FileValue.SymlinkFileValueWithStoredChain;
import com.google.devtools.build.lib.actions.FileValue.SymlinkFileValueWithoutStoredChain;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleException;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestPackageFactoryBuilderFactory;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileAccessException;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.ErrorInfoSubject;
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
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FileFunction}. */
@RunWith(JUnit4.class)
public class FileFunctionTest {
  private static final EvaluationContext EVALUATION_OPTIONS =
      EvaluationContext.newBuilder()
          .setKeepGoing(false)
          .setParallelism(DEFAULT_THREAD_COUNT)
          .setEventHandler(NullEventHandler.INSTANCE)
          .build();

  private InMemoryFileSystem fs;
  private Root pkgRoot;
  private Path outputBase;
  private Root outputBaseRoot;
  private PathPackageLocator pkgLocator;
  private boolean fastDigest;
  private ManualClock manualClock;
  private RecordingDifferencer differencer;

  @Before
  public final void createFsAndRoot() throws Exception {
    fastDigest = true;
    manualClock = new ManualClock();
    createFsAndRoot(new CustomInMemoryFs(manualClock));
  }

  private void createFsAndRoot(CustomInMemoryFs fs) throws IOException {
    this.fs = fs;
    pkgRoot = Root.fromPath(fs.getPath("/root"));
    outputBase = fs.getPath("/output_base");
    outputBaseRoot = Root.fromPath(outputBase);
    pkgLocator =
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(pkgRoot),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    pkgRoot.asPath().createDirectoryAndParents();
  }

  private MemoizingEvaluator makeEvaluator() {
    return makeEvaluator(ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS);
  }

  private MemoizingEvaluator makeEvaluator(ExternalFileAction externalFileAction) {
    AtomicReference<PathPackageLocator> pkgLocatorRef = new AtomicReference<>(pkgLocator);
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(pkgRoot.asPath(), outputBase, outputBase),
            pkgRoot.asPath(),
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(pkgLocatorRef, externalFileAction, directories);
    differencer = new SequencedRecordingDifferencer();
    ConfiguredRuleClassProvider ruleClassProvider = TestRuleClassProvider.getRuleClassProvider();
    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    MemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    FileSymlinkCycleUniquenessFunction.NAME,
                    new FileSymlinkCycleUniquenessFunction())
                .put(
                    FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
                    new FileSymlinkInfiniteExpansionUniquenessFunction())
                .put(SkyFunctions.FILE, new FileFunction(pkgLocatorRef, directories))
                .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        ruleClassProvider,
                        TestPackageFactoryBuilderFactory.getInstance()
                            .builder(directories)
                            .build(ruleClassProvider, fs),
                        directories,
                        /* bzlLoadFunctionForInlining= */ null))
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        null,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.REPOSITORY_MAPPING,
                    new SkyFunction() {
                      @Override
                      public SkyValue compute(SkyKey skyKey, Environment env) {
                        return RepositoryMappingValue.VALUE_FOR_ROOT_MODULE_WITHOUT_REPOS;
                      }
                    })
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new SkyFunction() {
                      @Override
                      public SkyValue compute(SkyKey skyKey, Environment env) {
                        return BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE;
                      }
                    })
                .build(),
            differencer);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator);
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    return evaluator;
  }

  private FileValue valueForPath(Path path) throws InterruptedException {
    return valueForPathHelper(pkgRoot, path, makeEvaluator());
  }

  private FileValue valueForPathOutsidePkgRoot(Path path) throws InterruptedException {
    return valueForPathHelper(Root.absoluteRoot(fs), path, makeEvaluator());
  }

  private static FileValue valueForPathHelper(Root root, Path path, MemoizingEvaluator evaluator)
      throws InterruptedException {
    return valueForRootedPathHelper(
        RootedPath.toRootedPath(root, root.relativize(path)), evaluator);
  }

  private FileValue valueForRootedPath(RootedPath rootedPath) throws InterruptedException {
    return valueForRootedPathHelper(rootedPath, makeEvaluator());
  }

  private static FileValue valueForRootedPathHelper(
      RootedPath rootedPath, MemoizingEvaluator evaluator) throws InterruptedException {
    SkyKey key = FileValue.key(rootedPath);
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isFalse();
    return result.get(key);
  }

  @Test
  public void testFileValueHashCodeAndEqualsContract() throws Exception {
    Path pathA = file("a", "a");
    Path pathB = file("b", "b");
    Path pathC = symlink("c", "a");
    Path pathD = directory("d");
    Path pathDA = file("d/a", "da");
    Path pathE = symlink("e", "d");
    Path pathF = symlink("f", "a");

    FileValue valueA1 = valueForPathOutsidePkgRoot(pathA);
    FileValue valueA2 = valueForPathOutsidePkgRoot(pathA);
    FileValue valueB1 = valueForPathOutsidePkgRoot(pathB);
    FileValue valueB2 = valueForPathOutsidePkgRoot(pathB);
    FileValue valueC1 = valueForPathOutsidePkgRoot(pathC);
    FileValue valueC2 = valueForPathOutsidePkgRoot(pathC);
    FileValue valueD1 = valueForPathOutsidePkgRoot(pathD);
    FileValue valueD2 = valueForPathOutsidePkgRoot(pathD);
    FileValue valueDA1 = valueForPathOutsidePkgRoot(pathDA);
    FileValue valueDA2 = valueForPathOutsidePkgRoot(pathDA);
    FileValue valueE1 = valueForPathOutsidePkgRoot(pathE);
    FileValue valueE2 = valueForPathOutsidePkgRoot(pathE);
    FileValue valueF1 = valueForPathOutsidePkgRoot(pathF);
    FileValue valueF2 = valueForPathOutsidePkgRoot(pathF);

    new EqualsTester()
        .addEqualityGroup(valueA1, valueA2)
        .addEqualityGroup(valueB1, valueB2)
        // Both 'f' and 'c' are transitively symlinks to 'a', so all of these FileValues ought to be
        // equal.
        .addEqualityGroup(valueC1, valueC2, valueF1, valueF2)
        .addEqualityGroup(valueD1, valueD2)
        .addEqualityGroup(valueDA1, valueDA2)
        .addEqualityGroup(valueE1, valueE2)
        .testEquals();
  }

  @Test
  public void testIsDirectory() throws Exception {
    assertThat(valueForPath(file("a")).isDirectory()).isFalse();
    assertThat(valueForPath(path("nonexistent")).isDirectory()).isFalse();
    assertThat(valueForPath(directory("dir")).isDirectory()).isTrue();

    assertThat(valueForPath(symlink("sa", "a")).isDirectory()).isFalse();
    assertThat(valueForPath(symlink("smissing", "missing")).isDirectory()).isFalse();
    assertThat(valueForPath(symlink("sdir", "dir")).isDirectory()).isTrue();
    assertThat(valueForPath(symlink("ssdir", "sdir")).isDirectory()).isTrue();
  }

  @Test
  public void testIsFile() throws Exception {
    assertThat(valueForPath(file("a")).isFile()).isTrue();
    assertThat(valueForPath(path("nonexistent")).isFile()).isFalse();
    assertThat(valueForPath(directory("dir")).isFile()).isFalse();

    assertThat(valueForPath(symlink("sa", "a")).isFile()).isTrue();
    assertThat(valueForPath(symlink("smissing", "missing")).isFile()).isFalse();
    assertThat(valueForPath(symlink("sdir", "dir")).isFile()).isFalse();
    assertThat(valueForPath(symlink("ssfile", "sa")).isFile()).isTrue();
  }

  @Test
  public void testSimpleIndependentFiles() throws Exception {
    file("a");
    file("b");

    Set<RootedPath> seenFiles = Sets.newHashSet();
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("a", false, "b"));
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"));
    assertThat(seenFiles).containsExactly(rootedPath("a"), rootedPath("b"), rootedPath(""));
  }

  @Test
  public void testSimpleSymlink() throws Exception {
    symlink("a", "b");
    file("b");

    assertValueChangesIfContentsOfFileChanges("a", false, "b");
    assertValueChangesIfContentsOfFileChanges("b", true, "a");
  }

  @Test
  public void testTransitiveSymlink() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    file("c");

    assertValueChangesIfContentsOfFileChanges("a", false, "b");
    assertValueChangesIfContentsOfFileChanges("a", false, "c");
    assertValueChangesIfContentsOfFileChanges("b", true, "a");
    assertValueChangesIfContentsOfFileChanges("c", true, "b");
    assertValueChangesIfContentsOfFileChanges("c", true, "a");
  }

  @Test
  public void testFileUnderBrokenDirectorySymlink() throws Exception {
    symlink("a", "b/c");
    symlink("b", "d");
    assertValueChangesIfContentsOfDirectoryChanges("b", true, "a/e");
  }

  @Test
  public void testFileUnderDirectorySymlink() throws Exception {
    symlink("a", "b/c");
    symlink("b", "d");
    file("d/c/e");
    assertValueChangesIfContentsOfDirectoryChanges("b", true, "a/e");
  }

  @Test
  public void testSymlinkInDirectory() throws Exception {
    symlink("a/aa", "ab");
    file("a/ab");

    assertValueChangesIfContentsOfFileChanges("a/aa", false, "a/ab");
    assertValueChangesIfContentsOfFileChanges("a/ab", true, "a/aa");
  }

  @Test
  public void testRelativeSymlink() throws Exception {
    symlink("a/aa/aaa", "../ab/aba");
    file("a/ab/aba");
    assertValueChangesIfContentsOfFileChanges("a/ab/aba", true, "a/aa/aaa");
  }

  @Test
  public void testDoubleRelativeSymlink() throws Exception {
    symlink("a/b/c/d", "../../e/f");
    file("a/e/f");
    assertValueChangesIfContentsOfFileChanges("a/e/f", true, "a/b/c/d");
  }

  @Test
  public void testExternalRelativeSymlink() throws Exception {
    symlink("a", "../outside");
    file("b");
    file("../outside");
    Set<RootedPath> seenFiles = Sets.newHashSet();
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"));
    seenFiles.addAll(
        getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("../outside", true, "a"));
    assertThat(seenFiles)
        .containsExactly(
            rootedPath("a"),
            rootedPath(""),
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/")),
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/outside")));
  }

  @Test
  public void testAbsoluteSymlink() throws Exception {
    symlink("a", "/absolute");
    file("b");
    file("/absolute");
    Set<RootedPath> seenFiles = Sets.newHashSet();
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"));
    seenFiles.addAll(
        getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("/absolute", true, "a"));
    assertThat(seenFiles)
        .containsExactly(
            rootedPath("a"),
            rootedPath(""),
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/")),
            RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/absolute")));
  }

  @Test
  public void testAbsoluteSymlinkToExternal() throws Exception {
    String externalPath =
        outputBase
            .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
            .getRelative("a/b")
            .getPathString();
    symlink("a", externalPath);
    file("b");
    file(externalPath);
    Set<RootedPath> seenFiles = Sets.newHashSet();
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", false, "a"));
    seenFiles.addAll(
        getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(externalPath, true, "a"));
    assertThat(seenFiles)
        .containsExactly(
            rootedPath("a"),
            rootedPath(""),
            rootedPath("/output_base"),
            rootedPath("/output_base/external"),
            rootedPath("/output_base/external/a"),
            rootedPath("/output_base/external/a/b"));
  }

  @Test
  public void testSymlinkAsAncestor() throws Exception {
    file("a/b/c/d");
    symlink("f", "a/b/c");
    assertValueChangesIfContentsOfFileChanges("a/b/c/d", true, "f/d");
  }

  @Test
  public void testSymlinkAsAncestorNested() throws Exception {
    file("a/b/c/d");
    symlink("f", "a/b");
    assertValueChangesIfContentsOfFileChanges("a/b/c/d", true, "f/c/d");
  }

  @Test
  public void testTwoSymlinksInAncestors() throws Exception {
    file("a/aa/aaa/aaaa");
    symlink("b/ba/baa", "../../a/aa");
    symlink("c/ca", "../b/ba");

    assertValueChangesIfContentsOfFileChanges("c/ca", true, "c/ca/baa/aaa/aaaa");
    assertValueChangesIfContentsOfFileChanges("b/ba/baa", true, "c/ca/baa/aaa/aaaa");
    assertValueChangesIfContentsOfFileChanges("a/aa/aaa/aaaa", true, "c/ca/baa/aaa/aaaa");
  }

  @Test
  public void testSelfReferencingSymlink() throws Exception {
    symlink("a", "a");
    assertError("a");
  }

  @Test
  public void testMutuallyReferencingSymlinks() throws Exception {
    symlink("a", "b");
    symlink("b", "a");
    assertError("a");
  }

  @Test
  public void testRecursiveNestingSymlink() throws Exception {
    symlink("a/a", "../a");
    file("b");
    assertNoError("a/a/a/a/b");
  }

  @Test
  public void testSimpleUnboundedAncestorSymlinkExpansionChainReported() throws Exception {
    symlink("a/a", "../a");
    FileValue v = valueForPath(path("a/a"));
    assertThat(v.unboundedAncestorSymlinkExpansionChain())
        .containsExactly(rootedPath("a/a"), rootedPath("a"));
  }

  @Test
  public void testBrokenSymlink() throws Exception {
    symlink("a", "b");
    Set<RootedPath> seenFiles = Sets.newHashSet();
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("b", true, "a"));
    seenFiles.addAll(getFilesSeenAndAssertValueChangesIfContentsOfFileChanges("a", false, "b"));
    assertThat(seenFiles).containsExactly(rootedPath("a"), rootedPath("b"), rootedPath(""));
  }

  @Test
  public void testBrokenDirectorySymlink() throws Exception {
    symlink("a", "b");
    file("c");

    assertValueChangesIfContentsOfDirectoryChanges("a", true, "a/aa");
    // This just creates the directory "b", which doesn't change the value for "a/aa", since "a/aa"
    // still has real path "b/aa" and still doesn't exist.
    assertValueChangesIfContentsOfDirectoryChanges("b", false, "a/aa");
    assertValueChangesIfContentsOfFileChanges("c", false, "a/aa");
  }

  @Test
  public void testTraverseIntoVirtualNonDirectory() throws Exception {
    file("dir/a");
    symlink("vdir", "dir");
    // The following evaluation should not throw IOExceptions.
    assertNoError("vdir/a/aa/aaa");
  }

  @Test
  public void testFileCreation() throws Exception {
    FileValue a = valueForPath(path("file"));
    Path p = file("file");
    FileValue b = valueForPath(p);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  public void testEmptyFile() throws Exception {
    final byte[] digest = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    createFsAndRoot(
        new CustomInMemoryFs(manualClock) {
          @Override
          @SuppressWarnings("UnsynchronizedOverridesSynchronized")
          protected byte[] getFastDigest(PathFragment path) {
            return digest;
          }
        });
    Path p = file("file");
    p.setLastModifiedTime(0L);
    FileValue a = valueForPath(p);
    p.setLastModifiedTime(1L);
    assertThat(valueForPath(p)).isEqualTo(a);
    FileSystemUtils.writeContentAsLatin1(p, "content");
    // Same digest, but now non-empty.
    assertThat(valueForPath(p)).isNotEqualTo(a);
  }

  @Test
  public void testUnreadableFileWithNoFastDigest() throws Exception {
    Path p = file("unreadable");
    p.chmod(0);
    p.setLastModifiedTime(0L);

    FileValue value = valueForPath(p);
    assertThat(value.exists()).isTrue();
    assertThat(value.getDigest()).isNull();

    p.setLastModifiedTime(10L);
    assertThat(valueForPath(p)).isNotEqualTo(value);

    p.setLastModifiedTime(0L);
    assertThat(valueForPath(p)).isEqualTo(value);
  }

  @Test
  public void testUnreadableFileWithFastDigest() throws Exception {
    final byte[] expectedDigest = {1, 2, 3, 4};

    createFsAndRoot(
        new CustomInMemoryFs(manualClock) {
          @Override
          @SuppressWarnings("UnsynchronizedOverridesSynchronized")
          protected byte[] getFastDigest(PathFragment path) {
            return path.getBaseName().equals("unreadable") ? expectedDigest : null;
          }
        });

    Path p = file("unreadable");
    p.chmod(0);

    FileValue value = valueForPath(p);
    assertThat(value.exists()).isTrue();
    assertThat(value.getDigest()).isNotNull();
  }

  @Test
  public void testFileModificationDigest() throws Exception {
    fastDigest = true;
    Path p = file("file");
    FileValue a = valueForPath(p);
    FileSystemUtils.writeContentAsLatin1(p, "goop");
    FileValue b = valueForPath(p);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  public void testModTimeVsDigest() throws Exception {
    Path p = file("somefile", "fizzley");

    fastDigest = true;
    FileValue aMd5 = valueForPath(p);
    fastDigest = false;
    FileValue aModTime = valueForPath(p);
    assertThat(aModTime).isNotEqualTo(aMd5);
    new EqualsTester().addEqualityGroup(aMd5).addEqualityGroup(aModTime).testEquals();
  }

  @Test
  public void testFileDeletion() throws Exception {
    Path p = file("file");
    FileValue a = valueForPath(p);
    p.delete();
    FileValue b = valueForPath(p);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  public void testFileTypeChange() throws Exception {
    Path p = file("file");
    FileValue a = valueForPath(p);
    p.delete();
    p = symlink("file", "foo");
    FileValue b = valueForPath(p);
    p.delete();
    pkgRoot.getRelative("file").createDirectoryAndParents();
    FileValue c = valueForPath(p);
    assertThat(a.equals(b)).isFalse();
    assertThat(b.equals(c)).isFalse();
    assertThat(a.equals(c)).isFalse();
  }

  @Test
  public void testSymlinkTargetChange() throws Exception {
    Path p = symlink("symlink", "foo");
    FileValue a = valueForPath(p);
    p.delete();
    p = symlink("symlink", "bar");
    FileValue b = valueForPath(p);
    assertThat(b).isNotEqualTo(a);
  }

  @Test
  public void testSymlinkTargetContentsChangeCTime() throws Exception {
    fastDigest = false;
    Path fooPath = file("foo");
    FileSystemUtils.writeContentAsLatin1(fooPath, "foo");
    Path p = symlink("symlink", "foo");
    FileValue a = valueForPath(p);
    manualClock.advanceMillis(1);
    fooPath.chmod(0555);
    manualClock.advanceMillis(1);
    FileValue b = valueForPath(p);
    assertThat(b).isNotEqualTo(a);
  }

  @Test
  public void testSymlinkTargetContentsChangeDigest() throws Exception {
    fastDigest = true;
    Path fooPath = file("foo");
    FileSystemUtils.writeContentAsLatin1(fooPath, "foo");
    Path p = symlink("symlink", "foo");
    FileValue a = valueForPath(p);
    FileSystemUtils.writeContentAsLatin1(fooPath, "bar");
    FileValue b = valueForPath(p);
    assertThat(b).isNotEqualTo(a);
  }

  @Test
  public void testRealPath() throws Exception {
    file("file");
    directory("directory");
    file("directory/file");
    symlink("directory/link", "file");
    symlink("directory/doublelink", "link");
    symlink("directory/parentlink", "../file");
    symlink("directory/doubleparentlink", "../link");
    symlink("link", "file");
    symlink("deadlink", "missing_file");
    symlink("dirlink", "directory");
    symlink("doublelink", "link");
    symlink("doubledirlink", "dirlink");

    checkRealPath("file");
    checkRealPath("link");
    checkRealPath("doublelink");

    for (String dir : new String[] {"directory", "dirlink", "doubledirlink"}) {
      checkRealPath(dir);
      checkRealPath(dir + "/file");
      checkRealPath(dir + "/link");
      checkRealPath(dir + "/doublelink");
      checkRealPath(dir + "/parentlink");
    }

    assertRealPath("missing", "missing");
    assertRealPath("deadlink", "missing_file");
  }

  @Test
  public void testRealPathRelativeSymlink() throws Exception {
    directory("dir");
    symlink("dir/link", "../dir2");
    directory("dir2");
    symlink("dir2/filelink", "../dest");
    file("dest");

    checkRealPath("dir/link/filelink");
  }

  @Test
  public void testSymlinkAcrossPackageRoots() throws Exception {
    Path otherPkgRoot = fs.getPath("/other_root");
    pkgLocator =
        new PathPackageLocator(
            outputBase,
            ImmutableList.of(pkgRoot, Root.fromPath(otherPkgRoot)),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    symlink("a", "/other_root/b");
    assertValueChangesIfContentsOfFileChanges("/other_root/b", true, "a");
  }

  @Test
  public void testFilesOutsideRootIsReEvaluated() throws Exception {
    Path file = file("/outsideroot");
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey key = skyKey("/outsideroot");
    EvaluationResult<SkyValue> result;
    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    if (result.hasError()) {
      fail(String.format("Evaluation error for %s: %s", key, result.getError()));
    }
    FileValue oldValue = (FileValue) result.get(key);
    assertThat(oldValue.exists()).isTrue();

    file.delete();
    differencer.invalidate(ImmutableList.of(fileStateSkyKey("/outsideroot")));
    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    if (result.hasError()) {
      fail(String.format("Evaluation error for %s: %s", key, result.getError()));
    }
    FileValue newValue = (FileValue) result.get(key);
    assertThat(newValue).isNotSameInstanceAs(oldValue);
    assertThat(newValue.exists()).isFalse();
  }

  @Test
  public void testFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable() throws Exception {
    file("/outsideroot");

    MemoizingEvaluator evaluator =
        makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS);
    SkyKey key = skyKey("/outsideroot");
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);

    assertThatEvaluationResult(result).hasNoError();
    FileValue value = (FileValue) result.get(key);
    assertThat(value).isNotNull();
    assertThat(value.exists()).isFalse();
  }

  @Test
  public void testAbsoluteSymlinksToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable()
      throws Exception {
    file("/outsideroot");
    symlink("a", "/outsideroot");

    MemoizingEvaluator evaluator =
        makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS);
    SkyKey key = skyKey("a");
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);

    assertThatEvaluationResult(result).hasNoError();
    FileValue value = (FileValue) result.get(key);
    assertThat(value).isNotNull();
    assertThat(value.exists()).isFalse();
  }

  @Test
  public void testAbsoluteSymlinksReferredByInternalFilesToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable()
      throws Exception {
    file("/outsideroot/src/foo/bar");
    symlink("/root/src", "/outsideroot/src");

    MemoizingEvaluator evaluator =
        makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS);
    SkyKey key = skyKey("/root/src/foo/bar");
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);

    assertThatEvaluationResult(result).hasNoError();
    FileValue value = (FileValue) result.get(key);
    assertThat(value).isNotNull();
    assertThat(value.exists()).isFalse();
  }

  @Test
  public void testRelativeSymlinksToFilesOutsideRootWhenExternalAssumedNonExistentAndImmutable()
      throws Exception {
    file("../outsideroot");
    symlink("a", "../outsideroot");
    MemoizingEvaluator evaluator =
        makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS);
    SkyKey key = skyKey("a");
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);

    assertThatEvaluationResult(result).hasNoError();
    FileValue value = (FileValue) result.get(key);
    assertThat(value).isNotNull();
    assertThat(value.exists()).isFalse();
  }

  @Test
  public void testAbsoluteSymlinksBackIntoSourcesOkWhenExternalDisallowed() throws Exception {
    Path file = file("insideroot");
    symlink("a", file.getPathString());

    MemoizingEvaluator evaluator =
        makeEvaluator(ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS);
    SkyKey key = skyKey("a");
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);

    assertThatEvaluationResult(result).hasNoError();
    FileValue value = (FileValue) result.get(key);
    assertThat(value).isNotNull();
    assertThat(value.exists()).isTrue();
    assertThat(
            value.realRootedPath((RootedPath) key.argument()).getRootRelativePath().getPathString())
        .isEqualTo("insideroot");
  }

  private static Set<RootedPath> filesSeen(MemoizingEvaluator graph) {
    return graph.getValues().keySet().stream()
        .filter(SkyFunctionName.functionIs(FileStateKey.FILE_STATE))
        .map(SkyKey::argument)
        .map(RootedPath.class::cast)
        .collect(toImmutableSet());
  }

  @Test
  public void testSize() throws Exception {
    Path file = file("file");
    int fileSize = 20;
    FileSystemUtils.writeContentAsLatin1(file, "a".repeat(fileSize));
    assertThat(valueForPath(file).getSize()).isEqualTo(fileSize);
    Path dir = directory("directory");
    file(dir.getChild("child").getPathString());
    assertThrows(IllegalStateException.class, () -> valueForPath(dir).getSize());
    Path nonexistent = fs.getPath("/root/noexist");
    assertThrows(IllegalStateException.class, () -> valueForPath(nonexistent).getSize());
    Path fileSymlink = symlink("link", "/root/file");
    // Symlink stores size of target, not link.
    assertThat(valueForPath(fileSymlink).getSize()).isEqualTo(fileSize);
    assertThat(fileSymlink.delete()).isTrue();

    Path rootDirSymlink = symlink("link", "/root/directory");
    assertThrows(IllegalStateException.class, () -> valueForPath(rootDirSymlink).getSize());
    assertThat(rootDirSymlink.delete()).isTrue();

    Path noExistSymlink = symlink("link", "/root/noexist");
    assertThrows(IllegalStateException.class, () -> valueForPath(noExistSymlink).getSize());
  }

  @Test
  public void testDigest() throws Exception {
    final AtomicInteger digestCalls = new AtomicInteger(0);
    int expectedCalls = 0;
    fs =
        new CustomInMemoryFs(manualClock) {
          @Override
          protected byte[] getDigest(PathFragment path) throws IOException {
            digestCalls.incrementAndGet();
            return super.getDigest(path);
          }
        };
    pkgRoot = Root.fromPath(fs.getPath("/root"));
    Path file = file("file");
    FileSystemUtils.writeContentAsLatin1(file, "a".repeat(20));
    byte[] digest = file.getDigest();
    expectedCalls++;
    assertThat(digestCalls.get()).isEqualTo(expectedCalls);
    FileValue value = valueForPath(file);
    expectedCalls++;
    assertThat(digestCalls.get()).isEqualTo(expectedCalls);
    assertThat(value.getDigest()).isEqualTo(digest);
    // Digest is cached -- no filesystem access.
    assertThat(digestCalls.get()).isEqualTo(expectedCalls);
    fastDigest = false;
    digestCalls.set(0);
    value = valueForPath(file);
    // No new digest calls.
    assertThat(digestCalls.get()).isEqualTo(0);
    assertThat(value.getDigest()).isNull();
    assertThat(digestCalls.get()).isEqualTo(0);
    fastDigest = true;
    Path dir = directory("directory");
    assertThrows(
        IllegalStateException.class, () -> assertThat(valueForPath(dir).getDigest()).isNull());
    assertThat(digestCalls.get()).isEqualTo(0); // No digest calls made for directory.
    Path nonexistent = fs.getPath("/root/noexist");
    assertThrows(
        IllegalStateException.class,
        () -> assertThat(valueForPath(nonexistent).getDigest()).isNull());
    assertThat(digestCalls.get()).isEqualTo(0); // No digest calls made for nonexistent file.
    Path symlink = symlink("link", "/root/file");
    value = valueForPath(symlink);
    assertThat(digestCalls.get()).isEqualTo(1);
    // Symlink stores digest of target, not link.
    assertThat(value.getDigest()).isEqualTo(digest);
    assertThat(digestCalls.get()).isEqualTo(1);
    digestCalls.set(0);
    assertThat(symlink.delete()).isTrue();
    // Symlink stores digest of target, not link, for directories too.
    assertThrows(
        IllegalStateException.class,
        () -> assertThat(valueForPath(symlink("link", "/root/directory")).getDigest()).isNull());
    assertThat(digestCalls.get()).isEqualTo(0);
  }

  @Test
  public void testDoesntStatChildIfParentDoesntExist() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) this.fs;
    // Our custom filesystem says "a" does not exist, so FileFunction shouldn't bother trying to
    // think about "a/b". Test for this by having a stat of "a/b" fail with an io error, and
    // observing that we don't encounter the error.
    fs.stubStat(path("a"), null);
    fs.stubStatError(path("a/b"), new IOException("ouch!"));
    assertThat(valueForPath(path("a/b")).exists()).isFalse();
  }

  @Test
  public void testFilesystemInconsistencies_getFastDigest() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) this.fs;
    file("a");
    // Our custom filesystem says "a/b" exists but "a" does not exist.
    fs.stubFastDigestError(path("a"), new IOException("nope"));
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey skyKey = skyKey("a");
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException()).hasMessageThat().contains("encountered error 'nope'");
    assertThat(errorInfo.getException()).hasMessageThat().contains("/root/a is no longer a file");
  }

  @Test
  public void testFilesystemInconsistencies_getFastDigestAndIsReadableFailure() throws Exception {
    createFsAndRoot(
        new CustomInMemoryFs(manualClock) {
          @Override
          protected boolean isReadable(PathFragment path) throws IOException {
            if (path.getBaseName().equals("unreadable")) {
              throw new IOException("isReadable failed");
            }
            return super.isReadable(path);
          }
        });

    Path p = file("unreadable");
    p.chmod(0);

    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey skyKey = skyKey("unreadable");
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    assertThat(errorInfo.getException()).isInstanceOf(InconsistentFilesystemException.class);
    assertThat(errorInfo.getException())
        .hasMessageThat()
        .contains("encountered error 'isReadable failed'");
    assertThat(errorInfo.getException())
        .hasMessageThat()
        .contains("/root/unreadable is no longer a file");
  }

  private void runTestSymlinkCycle(boolean ancestorCycle, boolean startInCycle) throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    symlink("c", "d");
    symlink("d", "e");
    symlink("e", "c");
    // We build multiple keys at once to make sure the cycle is reported exactly once.
    Map<RootedPath, ImmutableList<RootedPath>> startToCycleMap =
        ImmutableMap.<RootedPath, ImmutableList<RootedPath>>builder()
            .put(
                rootedPath("a"),
                ImmutableList.of(rootedPath("c"), rootedPath("d"), rootedPath("e")))
            .put(
                rootedPath("b"),
                ImmutableList.of(rootedPath("c"), rootedPath("d"), rootedPath("e")))
            .put(
                rootedPath("d"),
                ImmutableList.of(rootedPath("d"), rootedPath("e"), rootedPath("c")))
            .put(
                rootedPath("e"),
                ImmutableList.of(rootedPath("e"), rootedPath("c"), rootedPath("d")))
            .put(
                rootedPath("a/some/descendant"),
                ImmutableList.of(rootedPath("c"), rootedPath("d"), rootedPath("e")))
            .put(
                rootedPath("b/some/descendant"),
                ImmutableList.of(rootedPath("c"), rootedPath("d"), rootedPath("e")))
            .put(
                rootedPath("d/some/descendant"),
                ImmutableList.of(rootedPath("d"), rootedPath("e"), rootedPath("c")))
            .put(
                rootedPath("e/some/descendant"),
                ImmutableList.of(rootedPath("e"), rootedPath("c"), rootedPath("d")))
            .buildOrThrow();
    Map<RootedPath, ImmutableList<RootedPath>> startToPathToCycleMap =
        ImmutableMap.<RootedPath, ImmutableList<RootedPath>>builder()
            .put(rootedPath("a"), ImmutableList.of(rootedPath("a"), rootedPath("b")))
            .put(rootedPath("b"), ImmutableList.of(rootedPath("b")))
            .put(rootedPath("d"), ImmutableList.of())
            .put(rootedPath("e"), ImmutableList.of())
            .put(
                rootedPath("a/some/descendant"), ImmutableList.of(rootedPath("a"), rootedPath("b")))
            .put(rootedPath("b/some/descendant"), ImmutableList.of(rootedPath("b")))
            .put(rootedPath("d/some/descendant"), ImmutableList.of())
            .put(rootedPath("e/some/descendant"), ImmutableList.of())
            .buildOrThrow();
    ImmutableList<SkyKey> keys;
    if (ancestorCycle && startInCycle) {
      keys = ImmutableList.of(skyKey("d/some/descendant"), skyKey("e/some/descendant"));
    } else if (ancestorCycle) {
      keys = ImmutableList.of(skyKey("a/some/descendant"), skyKey("b/some/descendant"));
    } else if (startInCycle) {
      keys = ImmutableList.of(skyKey("d"), skyKey("e"));
    } else {
      keys = ImmutableList.of(skyKey("a"), skyKey("b"));
    }
    StoredEventHandler eventHandler = new StoredEventHandler();
    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<FileValue> result = evaluator.evaluate(keys, evaluationContext);
    assertThat(result.hasError()).isTrue();
    for (SkyKey key : keys) {
      ErrorInfo errorInfo = result.getError(key);
      // FileFunction detects symlink cycles explicitly.
      assertThat(errorInfo.getCycleInfo()).isEmpty();
      FileSymlinkCycleException fsce = (FileSymlinkCycleException) errorInfo.getException();
      RootedPath start = (RootedPath) key.argument();
      assertThat(fsce.getPathToCycle())
          .containsExactlyElementsIn(startToPathToCycleMap.get(start))
          .inOrder();
      assertThat(fsce.getCycle()).containsExactlyElementsIn(startToCycleMap.get(start)).inOrder();
    }
    // Check that the unique cycle was reported exactly once.
    assertThat(eventHandler.getEvents()).hasSize(1);
    assertThat(Iterables.getOnlyElement(eventHandler.getEvents()).getMessage())
        .contains("circular symlinks detected");
  }

  @Test
  public void testSymlinkCycle_ancestorCycle_startInCycle() throws Exception {
    runTestSymlinkCycle(/*ancestorCycle=*/ true, /*startInCycle=*/ true);
  }

  @Test
  public void testSymlinkCycle_ancestorCycle_startOutOfCycle() throws Exception {
    runTestSymlinkCycle(/*ancestorCycle=*/ true, /*startInCycle=*/ false);
  }

  @Test
  public void testSymlinkCycle_regularCycle_startInCycle() throws Exception {
    runTestSymlinkCycle(/*ancestorCycle=*/ false, /*startInCycle=*/ true);
  }

  @Test
  public void testSymlinkCycle_regularCycle_startOutOfCycle() throws Exception {
    runTestSymlinkCycle(/*ancestorCycle=*/ false, /*startInCycle=*/ false);
  }

  @Test
  public void testSerialization() throws Exception {
    fs = FsUtils.TEST_FILESYSTEM;
    pkgRoot = Root.absoluteRoot(fs);
    FileValue a = valueForPath(fs.getPath("/"));
    Path tmp = fs.getPath("/file.txt");
    FileSystemUtils.writeContentAsLatin1(tmp, "test contents");
    FileValue b = valueForPath(tmp);
    Preconditions.checkState(b.isFile());
    FileValue c = valueForPath(fs.getPath("/does/not/exist"));
    SerializationTester serializationTester = new SerializationTester(a, b, c).makeMemoizing();
    FsUtils.addDependencies(serializationTester);
    serializationTester.runTests();
  }

  @Test
  public void testFileStateEquality() throws Exception {
    file("a");
    symlink("b1", "a");
    symlink("b2", "a");
    symlink("b3", "zzz");
    directory("d1");
    directory("d2");
    SkyKey file = fileStateSkyKey("a");
    SkyKey symlink1 = fileStateSkyKey("b1");
    SkyKey symlink2 = fileStateSkyKey("b2");
    SkyKey symlink3 = fileStateSkyKey("b3");
    SkyKey missing1 = fileStateSkyKey("c1");
    SkyKey missing2 = fileStateSkyKey("c2");
    SkyKey directory1 = fileStateSkyKey("d1");
    SkyKey directory2 = fileStateSkyKey("d2");
    ImmutableList<SkyKey> keys =
        ImmutableList.of(
            file, symlink1, symlink2, symlink3, missing1, missing2, directory1, directory2);

    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationResult<SkyValue> result = evaluator.evaluate(keys, EVALUATION_OPTIONS);

    new EqualsTester()
        .addEqualityGroup(result.get(file))
        .addEqualityGroup(result.get(symlink1), result.get(symlink2))
        .addEqualityGroup(result.get(symlink3))
        .addEqualityGroup(result.get(missing1), result.get(missing2))
        .addEqualityGroup(result.get(directory1), result.get(directory2))
        .testEquals();
  }

  @Test
  public void testSymlinkToPackagePathBoundary() throws Exception {
    Path path = path("this/is/a/path");
    FileSystemUtils.ensureSymbolicLink(path, pkgRoot.asPath());
    assertNoError("this/is/a/path");
  }

  private void runTestSimpleInfiniteSymlinkExpansion(
      boolean symlinkToAncestor, boolean absoluteSymlink) throws Exception {
    Path otherPath = path("other");
    RootedPath otherRootedPath = RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(otherPath));
    Path ancestorPath = path("a");
    RootedPath ancestorRootedPath =
        RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(ancestorPath));
    FileSystemUtils.ensureSymbolicLink(otherPath, ancestorPath);
    Path intermediatePath = path("inter");
    RootedPath intermediateRootedPath =
        RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(intermediatePath));
    Path descendantPath = path("a/b/c/d/e");
    RootedPath descendantRootedPath =
        RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(descendantPath));
    if (symlinkToAncestor) {
      FileSystemUtils.ensureSymbolicLink(descendantPath, intermediatePath);
      if (absoluteSymlink) {
        FileSystemUtils.ensureSymbolicLink(intermediatePath, ancestorPath);
      } else {
        FileSystemUtils.ensureSymbolicLink(
            intermediatePath, ancestorRootedPath.getRootRelativePath());
      }
    } else {
      FileSystemUtils.ensureSymbolicLink(ancestorPath, intermediatePath);
      if (absoluteSymlink) {
        FileSystemUtils.ensureSymbolicLink(intermediatePath, descendantPath);
      } else {
        FileSystemUtils.ensureSymbolicLink(
            intermediatePath, descendantRootedPath.getRootRelativePath());
      }
    }
    StoredEventHandler eventHandler = new StoredEventHandler();
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey ancestorPathKey = FileValue.key(ancestorRootedPath);
    SkyKey descendantPathKey = FileValue.key(descendantRootedPath);
    SkyKey otherPathKey = FileValue.key(otherRootedPath);
    ImmutableList<SkyKey> keys;
    ImmutableList<SkyKey> errorKeys;
    ImmutableList<RootedPath> expectedChain;
    if (symlinkToAncestor) {
      keys = ImmutableList.of(descendantPathKey, otherPathKey);
      errorKeys = ImmutableList.of(descendantPathKey);
      expectedChain =
          ImmutableList.of(descendantRootedPath, intermediateRootedPath, ancestorRootedPath);
    } else {
      keys = ImmutableList.of(ancestorPathKey, otherPathKey);
      errorKeys = keys;
      expectedChain =
          ImmutableList.of(ancestorRootedPath, intermediateRootedPath, descendantRootedPath);
    }

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<FileValue> result = evaluator.evaluate(keys, evaluationContext);
    if (symlinkToAncestor) {
      assertThat(result.hasError()).isFalse();
    } else {
      assertThat(result.hasError()).isTrue();
      for (SkyKey key : errorKeys) {
        ErrorInfo errorInfo = result.getError(key);
        // FileFunction detects infinite symlink expansion explicitly.
        assertThat(errorInfo.getCycleInfo()).isEmpty();
        FileSymlinkInfiniteExpansionException fsiee =
            (FileSymlinkInfiniteExpansionException) errorInfo.getException();
        assertThat(fsiee).hasMessageThat().contains("Infinite symlink expansion");
        assertThat(fsiee.getChain()).containsExactlyElementsIn(expectedChain).inOrder();
      }
      // Check that the unique symlink expansion error was reported exactly once.
      assertThat(eventHandler.getEvents()).hasSize(1);
      assertThat(Iterables.getOnlyElement(eventHandler.getEvents()).getMessage())
          .contains("infinite symlink expansion detected");
    }
  }

  @Test
  public void testInfiniteSymlinkExpansion_absoluteSymlinkToDescendant() throws Exception {
    runTestSimpleInfiniteSymlinkExpansion(
        /* symlinkToAncestor= */ false, /*absoluteSymlink=*/ true);
  }

  @Test
  public void testInfiniteSymlinkExpansion_relativeSymlinkToDescendant() throws Exception {
    runTestSimpleInfiniteSymlinkExpansion(
        /* symlinkToAncestor= */ false, /*absoluteSymlink=*/ false);
  }

  @Test
  public void testInfiniteSymlinkExpansion_absoluteSymlinkToAncestor() throws Exception {
    runTestSimpleInfiniteSymlinkExpansion(/* symlinkToAncestor= */ true, /*absoluteSymlink=*/ true);
  }

  @Test
  public void testInfiniteSymlinkExpansion_relativeSymlinkToAncestor() throws Exception {
    runTestSimpleInfiniteSymlinkExpansion(
        /* symlinkToAncestor= */ true, /*absoluteSymlink=*/ false);
  }

  @Test
  public void testInfiniteSymlinkExpansion_symlinkToReferrerToAncestor() throws Exception {
    symlink("d", "a");
    directory("a/b");
    symlink("a/b/c", "../../d/b");
    symlink("e", "a/b/c");
    Path fPath = symlink("f", "e");

    RootedPath rootedPathF = RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(fPath));
    SkyKey keyF = FileValue.key(rootedPathF);

    StoredEventHandler eventHandler = new StoredEventHandler();
    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(keyF), evaluationContext);

    assertThatEvaluationResult(result).hasNoError();
    FileValue e = result.get(keyF);
    assertThat(e.pathToUnboundedAncestorSymlinkExpansionChain())
        .containsExactly(rootedPath("f"), rootedPath("e"))
        .inOrder();
    assertThat(e.unboundedAncestorSymlinkExpansionChain())
        .containsExactly(rootedPath("a/b/c"), rootedPath("d/b"), rootedPath("a/b"))
        .inOrder();
  }

  @Test
  public void testInfiniteSymlinkExpansion_symlinkToReferrerToAncestor_levelsOfDirectorySymlinks()
      throws Exception {
    symlink("dir1/a", "../dir2");
    symlink("dir2/b", "../dir1");

    RootedPath rootedPathDir1AB =
        RootedPath.toRootedPath(pkgRoot, pkgRoot.relativize(path("dir1/a/b")));
    SkyKey keyDir1AB = FileValue.key(rootedPathDir1AB);

    StoredEventHandler eventHandler = new StoredEventHandler();
    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(keyDir1AB), evaluationContext);

    assertThatEvaluationResult(result).hasNoError();
  }

  @Test
  public void testChildOfNonexistentParent() throws Exception {
    Path ancestor = directory("this/is/an/ancestor");
    Path parent = ancestor.getChild("parent");
    Path child = parent.getChild("child");
    assertThat(valueForPath(parent).exists()).isFalse();
    assertThat(valueForPath(child).exists()).isFalse();
  }

  @Test
  public void testInjectionOverIOException() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) this.fs;
    Path foo = file("foo");
    SkyKey fooKey = skyKey("foo");
    fs.stubStatError(foo, new IOException("bork"));
    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(1)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(fooKey), evaluationContext);
    ErrorInfoSubject errorInfoSubject = assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(fooKey);
    errorInfoSubject.isTransient();
    errorInfoSubject
        .hasExceptionThat()
        .hasMessageThat()
        .isEqualTo("bork");
    fs.stubbedStatErrors.remove(foo.asFragment());
    differencer.inject(
        fileStateSkyKey("foo"),
        Delta.justNew(
            FileStateValue.create(
                RootedPath.toRootedPath(pkgRoot, foo),
                SyscallCache.NO_CACHE,
                new TimestampGranularityMonitor(BlazeClock.instance()))));
    result = evaluator.evaluate(ImmutableList.of(fooKey), evaluationContext);
    assertThatEvaluationResult(result).hasNoError();
    assertThat(result.get(fooKey).exists()).isTrue();
  }

  @Test
  public void testMultipleLevelsOfDirectorySymlinks_clean() throws Exception {
    symlink("a/b/c", "../c");
    Path abcd = path("a/b/c/d");
    symlink("a/c/d", "../d");
    assertThat(valueForPath(abcd).isSymlink()).isTrue();
  }

  @Test
  public void testMultipleLevelsOfDirectorySymlinks_incremental() throws Exception {
    MemoizingEvaluator evaluator = makeEvaluator();

    symlink("a/b/c", "../c");
    Path acd = directory("a/c/d");
    Path abcd = path("a/b/c/d");

    FileValue abcdFileValue = valueForPathHelper(pkgRoot, abcd, evaluator);
    assertThat(abcdFileValue.isDirectory()).isTrue();
    assertThat(abcdFileValue.isSymlink()).isFalse();

    acd.delete();
    symlink("a/c/d", "../d");
    differencer.invalidate(ImmutableList.of(fileStateSkyKey("a/c/d")));

    abcdFileValue = valueForPathHelper(pkgRoot, abcd, evaluator);

    assertThat(abcdFileValue.isSymlink()).isTrue();
  }

  private void checkRealPath(String pathString) throws Exception {
    Path realPath = pkgRoot.getRelative(pathString).resolveSymbolicLinks();
    assertRealPath(pathString, pkgRoot.relativize(realPath).toString());
  }

  private void assertRealPath(String pathString, String expectedRealPathString) throws Exception {
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey key = skyKey(pathString);
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    if (result.hasError()) {
      fail(String.format("Evaluation error for %s: %s", key, result.getError()));
    }
    FileValue fileValue = (FileValue) result.get(key);
    assertThat(fileValue.realRootedPath((RootedPath) key.argument()).asPath().toString())
        .isEqualTo(pkgRoot.getRelative(expectedRealPathString).toString());
  }

  @Test
  public void testLogicalChainDuringResolution_directory_simpleSymlink() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    directory("c");
    RootedPath rootedPath = rootedPath("a");
    FileValue fileValue = valueForRootedPath(rootedPath);
    assertThat(fileValue).isInstanceOf(SymlinkFileValueWithStoredChain.class);
    assertThat(fileValue.getUnresolvedLinkTarget()).isEqualTo(PathFragment.create("b"));
    assertThat(fileValue.logicalChainDuringResolution(rootedPath))
        .containsExactly(rootedPath("a"), rootedPath("b"), rootedPath("c"))
        .inOrder();
  }

  @Test
  public void testLogicalChainDuringResolution_directory_simpleAncestorSymlink() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    directory("c/d");
    RootedPath rootedPath = rootedPath("a/d");
    FileValue fileValue = valueForRootedPath(rootedPath);
    assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithStoredChain.class);
    assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("c/d"));
    assertThat(fileValue.logicalChainDuringResolution(rootedPath))
        .containsExactly(rootedPath("a/d"), rootedPath("b/d"), rootedPath("c/d"))
        .inOrder();
  }

  @Test
  public void testLogicalChainDuringResolution_file_simpleSymlink() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    file("c");
    RootedPath rootedPath = rootedPath("a");
    FileValue fileValue = valueForRootedPath(rootedPath);
    assertThat(fileValue).isInstanceOf(SymlinkFileValueWithoutStoredChain.class);
    assertThat(fileValue.getUnresolvedLinkTarget()).isEqualTo(PathFragment.create("b"));
    assertThrows(
        IllegalStateException.class, () -> fileValue.logicalChainDuringResolution(rootedPath));
  }

  @Test
  public void testLogicalChainDuringResolution_file_simpleAncestorSymlink() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    file("c/d");
    RootedPath rootedPath = rootedPath("a/d");
    FileValue fileValue = valueForRootedPath(rootedPath);
    assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithoutStoredChain.class);
    assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("c/d"));
    assertThrows(
        IllegalStateException.class, () -> fileValue.logicalChainDuringResolution(rootedPath));
  }

  @Test
  public void testLogicalChainDuringResolution_complicated() throws Exception {
    symlink("a", "b");
    symlink("b", "c");
    directory("c");
    symlink("c/d", "../e/f");
    symlink("e", "g");
    directory("g");
    symlink("g/f", "../h");
    directory("h");
    RootedPath rootedPath = rootedPath("a/d");
    FileValue fileValue = valueForRootedPath(rootedPath);
    assertThat(fileValue).isInstanceOf(DifferentRealPathFileValueWithStoredChain.class);
    assertThat(fileValue.realRootedPath(rootedPath)).isEqualTo(rootedPath("h"));
    assertThat(fileValue.logicalChainDuringResolution(rootedPath))
        .containsExactly(
            rootedPath("a/d"),
            rootedPath("b/d"),
            rootedPath("c/d"),
            rootedPath("e/f"),
            rootedPath("g/f"),
            rootedPath("h"))
        .inOrder();
  }

  @Test
  public void testFileAccessException() throws Exception {
    CustomInMemoryFs fs = (CustomInMemoryFs) this.fs;
    Path foo = file("foo");
    FileAccessException fae = new FileAccessException("nope");
    fs.stubStatError(foo, fae);
    SkyKey skyKey = skyKey("foo");
    MemoizingEvaluator evaluator = makeEvaluator();
    EvaluationResult<FileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), EVALUATION_OPTIONS);
    assertThat(result.hasError()).isTrue();
    ErrorInfoSubject errorInfoSubject =
        assertThatEvaluationResult(result).hasErrorEntryForKeyThat(skyKey);
    errorInfoSubject.isTransient();
    errorInfoSubject.hasExceptionThat().isSameInstanceAs(fae);
  }

  /**
   * Returns a callback that, when executed, deletes the given path. Not meant to be called directly
   * by tests.
   */
  private static Runnable makeDeletePathCallback(Path toDelete) {
    return () -> {
      try {
        toDelete.delete();
      } catch (IOException e) {
        e.printStackTrace();
        fail(e.getMessage());
      }
    };
  }

  /**
   * Returns a callback that, when executed, writes the given bytes to the given file path. Not
   * meant to be called directly by tests.
   */
  private static Runnable makeWriteFileContentCallback(Path toChange, byte[] contents) {
    return () -> {
      try (OutputStream outputStream = toChange.getOutputStream()) {
        outputStream.write(contents);
      } catch (IOException e) {
        e.printStackTrace();
        fail(e.getMessage());
      }
    };
  }

  /**
   * Returns a callback that, when executed, creates the given directory path. Not meant to be
   * called directly by tests.
   */
  private static Runnable makeCreateDirectoryCallback(Path toCreate) {
    return () -> {
      try {
        toCreate.createDirectory();
      } catch (IOException e) {
        e.printStackTrace();
        fail(e.getMessage());
      }
    };
  }

  /**
   * Returns a callback that, when executed, makes {@code toLink} a symlink to {@code toTarget}. Not
   * meant to be called directly by tests.
   */
  private static Runnable makeSymlinkCallback(Path toLink, PathFragment toTarget) {
    return () -> {
      try {
        FileSystemUtils.ensureSymbolicLink(toLink, toTarget);
      } catch (IOException e) {
        e.printStackTrace();
        fail(e.getMessage());
      }
    };
  }

  /** Returns the files that would be changed/created if {@code path} were to be changed/created. */
  private static ImmutableList<String> filesTouchedIfTouched(Path path) {
    List<String> filesToBeTouched = Lists.newArrayList();
    do {
      filesToBeTouched.add(path.getPathString());
      path = path.getParentDirectory();
    } while (!path.exists());
    return ImmutableList.copyOf(filesToBeTouched);
  }

  /**
   * Changes the contents of the FileValue for the given file in some way e.g.
   *
   * <ul>
   * <li> If it's a regular file, the contents will be changed.
   * <li> If it's a non-existent file, it will be created.
   *     <ul>
   *     and then returns the file(s) changed paired with a callback to undo the change. Not meant
   *     to be called directly by tests.
   */
  private Pair<ImmutableList<String>, Runnable> changeFile(String fileStringToChange)
      throws Exception {
    Path fileToChange = path(fileStringToChange);
    if (fileToChange.exists()) {
      final byte[] oldContents = FileSystemUtils.readContent(fileToChange);
      try (OutputStream outputStream = fileToChange.getOutputStream(/*append=*/ true)) {
        outputStream.write(new byte[] {(byte) 42}, 0, 1);
      }
      return Pair.of(
          ImmutableList.of(fileStringToChange),
          makeWriteFileContentCallback(fileToChange, oldContents));
    } else {
      ImmutableList<String> filesTouched = filesTouchedIfTouched(fileToChange);
      file(fileStringToChange, "new stuff");
      return Pair.of(ImmutableList.copyOf(filesTouched), makeDeletePathCallback(fileToChange));
    }
  }

  /**
   * Changes the contents of the FileValue for the given directory in some way e.g.
   *
   * <ul>
   * <li> If it exists, the directory will be deleted.
   * <li> If it doesn't exist, the directory will be created.
   *     <ul>
   *     and then returns the file(s) changed paired with a callback to undo the change. Not meant
   *     to be called directly by tests.
   */
  private Pair<ImmutableList<String>, Runnable> changeDirectory(String directoryStringToChange)
      throws Exception {
    final Path directoryToChange = path(directoryStringToChange);
    if (directoryToChange.exists()) {
      directoryToChange.delete();
      return Pair.of(
          ImmutableList.of(directoryStringToChange),
          makeCreateDirectoryCallback(directoryToChange));
    } else {
      directoryToChange.createDirectory();
      return Pair.of(
          ImmutableList.of(directoryStringToChange), makeDeletePathCallback(directoryToChange));
    }
  }

  /**
   * Performs filesystem operations to change the file or directory denoted by {@code
   * changedPathString} and returns the file(s) changed paired with a callback to undo the change.
   * Not meant to be called directly by tests.
   *
   * @param isSupposedToBeFile whether the path denoted by the given string is supposed to be a file
   *     or a directory. This is needed is the path doesn't exist yet, and so the filesystem doesn't
   *     know.
   */
  private Pair<ImmutableList<String>, Runnable> change(
      String changedPathString, boolean isSupposedToBeFile) throws Exception {
    Path changedPath = path(changedPathString);
    if (changedPath.isSymbolicLink()) {
      ImmutableList<String> filesTouched = filesTouchedIfTouched(changedPath);
      PathFragment oldTarget = changedPath.readSymbolicLink();
      FileSystemUtils.ensureSymbolicLink(changedPath, oldTarget.getChild("__different_target__"));
      return Pair.of(filesTouched, makeSymlinkCallback(changedPath, oldTarget));
    } else if (isSupposedToBeFile) {
      return changeFile(changedPathString);
    } else {
      return changeDirectory(changedPathString);
    }
  }

  /**
   * Asserts that if the contents of {@code changedPathString} changes, then the FileValue
   * corresponding to {@code pathString} will change. Not meant to be called directly by tests.
   */
  private void assertValueChangesIfContentsOfFileChanges(
      String changedPathString, boolean changes, String pathString) throws Exception {
    getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(
        changedPathString, changes, pathString);
  }

  /**
   * Asserts that if the contents of {@code changedPathString} changes, then the FileValue
   * corresponding to {@code pathString} will change. Returns the paths of all files seen.
   */
  private Set<RootedPath> getFilesSeenAndAssertValueChangesIfContentsOfFileChanges(
      String changedPathString, boolean changes, String pathString) throws Exception {
    return assertChangesIfChanges(changedPathString, true, changes, pathString);
  }

  /**
   * Asserts that if the directory {@code changedPathString} changes, then the FileValue
   * corresponding to {@code pathString} will change.
   */
  private void assertValueChangesIfContentsOfDirectoryChanges(
      String changedPathString, boolean changes, String pathString) throws Exception {
    assertChangesIfChanges(changedPathString, false, changes, pathString);
  }

  /**
   * Asserts that if the contents of {@code changedPathString} changes, then the FileValue
   * corresponding to {@code pathString} will change. Returns the paths of all files seen. Not meant
   * to be called directly by tests.
   */
  private Set<RootedPath> assertChangesIfChanges(
      String changedPathString, boolean isFile, boolean changes, String pathString)
      throws Exception {
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey key = skyKey(pathString);
    EvaluationResult<SkyValue> result;
    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    if (result.hasError()) {
      fail(String.format("Evaluation error for %s: %s", key, result.getError()));
    }
    SkyValue oldValue = result.get(key);

    Pair<ImmutableList<String>, Runnable> changeResult = change(changedPathString, isFile);
    ImmutableList<String> changedPathStrings = changeResult.first;
    Runnable undoCallback = changeResult.second;
    differencer.invalidate(
        changedPathStrings.stream().map(this::fileStateSkyKey).collect(Collectors.toList()));

    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    if (result.hasError()) {
      fail(String.format("Evaluation error for %s: %s", key, result.getError()));
    }

    SkyValue newValue = result.get(key);
    assertWithMessage(
            String.format(
                "Changing the contents of %s %s should%s change the value for file %s.",
                isFile ? "file" : "directory",
                changedPathString,
                changes ? "" : " not",
                pathString))
        .that(changes != newValue.equals(oldValue))
        .isTrue();

    // Restore the original file.
    undoCallback.run();
    return filesSeen(evaluator);
  }

  /**
   * Asserts that trying to construct a FileValue for {@code path} succeeds. Returns the paths of
   * all files seen.
   */
  private void assertNoError(String pathString) throws Exception {
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey key = skyKey(pathString);
    EvaluationResult<FileValue> result;
    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    assertWithMessage(
            "Did not expect error while evaluating " + pathString + ", got " + result.get(key))
        .that(result.hasError())
        .isFalse();
  }

  /**
   * Asserts that trying to construct a FileValue for {@code path} fails. Returns the paths of all
   * files seen.
   */
  private void assertError(String pathString) throws Exception {
    MemoizingEvaluator evaluator = makeEvaluator();
    SkyKey key = skyKey(pathString);
    EvaluationResult<FileValue> result;
    result = evaluator.evaluate(ImmutableList.of(key), EVALUATION_OPTIONS);
    assertWithMessage("Expected error while evaluating " + pathString + ", got " + result.get(key))
        .that(result.hasError())
        .isTrue();
    assertThat(
            !result.getError().getCycleInfo().isEmpty() || result.getError().getException() != null)
        .isTrue();
  }

  private Path file(String fileName) throws Exception {
    Path path = path(fileName);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(path);
    return path;
  }

  private Path file(String fileName, String contents) throws Exception {
    Path path = path(fileName);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(path, contents);
    return path;
  }

  private Path directory(String directoryName) throws Exception {
    Path path = path(directoryName);
    path.createDirectoryAndParents();
    return path;
  }

  private Path symlink(String link, String target) throws Exception {
    Path path = path(link);
    path.getParentDirectory().createDirectoryAndParents();
    path.createSymbolicLink(PathFragment.create(target));
    return path;
  }

  private Path path(String rootRelativePath) {
    return pkgRoot.getRelative(PathFragment.create(rootRelativePath));
  }

  private RootedPath rootedPath(String pathString) {
    ImmutableList<Root> roots =
        ImmutableList.<Root>builder()
            .addAll(pkgLocator.getPathEntries())
            .add(outputBaseRoot)
            .build();
    return RootedPath.toRootedPathMaybeUnderRoot(path(pathString), roots);
  }

  private SkyKey skyKey(String pathString) {
    return FileValue.key(rootedPath(pathString));
  }

  private SkyKey fileStateSkyKey(String pathString) {
    return FileStateValue.key(rootedPath(pathString));
  }

  private class CustomInMemoryFs extends InMemoryFileSystem {

    private final Map<PathFragment, FileStatus> stubbedStats = Maps.newHashMap();
    private final Map<PathFragment, IOException> stubbedStatErrors = Maps.newHashMap();
    private final Map<PathFragment, IOException> stubbedFastDigestErrors = Maps.newHashMap();

    CustomInMemoryFs(ManualClock manualClock) {
      super(manualClock, DigestHashFunction.SHA256);
    }

    void stubFastDigestError(Path path, IOException error) {
      stubbedFastDigestErrors.put(path.asFragment(), error);
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized")
    protected byte[] getFastDigest(PathFragment path) throws IOException {
      if (stubbedFastDigestErrors.containsKey(path)) {
        throw stubbedFastDigestErrors.get(path);
      }
      return fastDigest ? getDigest(path) : null;
    }

    void stubStat(Path path, @Nullable FileStatus stubbedResult) {
      stubbedStats.put(path.asFragment(), stubbedResult);
    }

    void stubStatError(Path path, IOException error) {
      stubbedStatErrors.put(path.asFragment(), error);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (stubbedStatErrors.containsKey(path)) {
        throw stubbedStatErrors.get(path);
      }
      if (stubbedStats.containsKey(path)) {
        return stubbedStats.get(path);
      }
      return super.statIfFound(path, followSymlinks);
    }
  }
}
