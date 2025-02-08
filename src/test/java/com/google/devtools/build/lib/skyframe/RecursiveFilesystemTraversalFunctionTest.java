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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory.danglingSymlink;
import static com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory.regularFile;
import static com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory.symlinkToDirectory;
import static com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory.symlinkToFile;
import static org.junit.Assert.assertEquals;

import com.google.auto.value.AutoValue;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileStateValue.RegularFileStateValueWithContentsProxy;
import com.google.devtools.build.lib.actions.FileStateValue.RegularFileStateValueWithDigest;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversalRoot;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.io.FileSymlinkCycleUniquenessFunction;
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFileFactory;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TimestampGranularityUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.GroupedDeps;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RecursiveFilesystemTraversalFunction}. */
@RunWith(JUnit4.class)
public final class RecursiveFilesystemTraversalFunctionTest extends FoundationTestCase {
  private static final HasDigest EMPTY_METADATA = HasDigest.EMPTY;

  private RecordingEvaluationProgressReceiver progressReceiver;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private AtomicReference<PathPackageLocator> pkgLocator;
  private NonHermeticArtifactFakeFunction artifactFunction;
  private List<Artifact.DerivedArtifact> artifacts;

  private final Set<PathFragment> pathsToPretendDontExist = Sets.newConcurrentHashSet();

  @Override
  protected FileSystem createFileSystem() {
    return new DelegateFileSystem(super.createFileSystem()) {
      @Override
      protected FileStatus statIfFound(PathFragment path, boolean followSymlinks)
          throws IOException {
        if (pathsToPretendDontExist.contains(path)) {
          return null;
        }
        return super.statIfFound(path, followSymlinks);
      }
    };
  }

  @Before
  public void setUp() {
    artifacts = new ArrayList<>();
    AnalysisMock analysisMock = AnalysisMock.get();
    pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages =
        new AtomicReference<>(ImmutableSet.of());
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            null,
            analysisMock.getProductName());
    ExternalFilesHelper externalFilesHelper = ExternalFilesHelper.createForTesting(
        pkgLocator, ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS, directories);

    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(
        FileStateKey.FILE_STATE,
        new FileStateFunction(
            Suppliers.ofInstance(new TimestampGranularityMonitor(BlazeClock.instance())),
            SyscallCache.NO_CACHE,
            externalFilesHelper));
    skyFunctions.put(SkyFunctions.FILE, new FileFunction(pkgLocator, directories));
    skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    skyFunctions.put(
        SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE));
    skyFunctions.put(
        SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL,
        new RecursiveFilesystemTraversalFunction(SyscallCache.NO_CACHE));
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages,
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP);
    skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build());
    skyFunctions.put(
        WorkspaceFileValue.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            ruleClassProvider,
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fileSystem),
            directories,
            /*bzlLoadFunctionForInlining=*/ null));
    skyFunctions.put(
        SkyFunctions.EXTERNAL_PACKAGE,
        new ExternalPackageFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
        new LocalRepositoryLookupFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(
        FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
        new FileSymlinkInfiniteExpansionUniquenessFunction());
    skyFunctions.put(
        FileSymlinkCycleUniquenessFunction.NAME, new FileSymlinkCycleUniquenessFunction());
    // We use a non-hermetic key to allow us to invalidate the proper artifacts on rebuilds. We
    // could have the artifact depend on the corresponding FileValue, but that would not cover the
    // case of a generated directory, which we have test coverage for.
    skyFunctions.put(Artifact.ARTIFACT, new ArtifactFakeFunction());
    artifactFunction = new NonHermeticArtifactFakeFunction();
    skyFunctions.put(SkyFunctions.ACTION_EXECUTION, new ActionFakeFunction());
    skyFunctions.put(NONHERMETIC_ARTIFACT, artifactFunction);

    progressReceiver = new RecordingEvaluationProgressReceiver();
    differencer = new SequencedRecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(skyFunctions, differencer, progressReceiver);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
  }

  private Artifact sourceArtifact(String path) {
    return ActionsTestUtil.createArtifact(
        ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)), path);
  }

  private SpecialArtifact treeArtifact(String path) {
    return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, "out"),
        PathFragment.create("out/" + path));
  }

  private void addNewTreeFileArtifact(SpecialArtifact parent, String relatedPath)
      throws IOException {
    TreeFileArtifact treeFileArtifact = TreeFileArtifact.createTreeOutput(parent, relatedPath);
    artifactFunction.addNewTreeFileArtifact(treeFileArtifact);
  }

  private Artifact derivedArtifact(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    Artifact.DerivedArtifact result =
        (Artifact.DerivedArtifact)
            ActionsTestUtil.createArtifactWithExecPath(
                ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, "out"), execPath);
    result.setGeneratingActionKey(
        ActionLookupData.create(ActionsTestUtil.NULL_ARTIFACT_OWNER, artifacts.size()));
    artifacts.add(result);
    return result;
  }

  private static RootedPath rootedPath(Artifact artifact) {
    return RootedPath.toRootedPath(artifact.getRoot().getRoot(), artifact.getRootRelativePath());
  }

  private RootedPath rootedPath(String path, String packagePath) {
    return RootedPath.toRootedPath(
        Root.fromPath(rootDirectory.getRelative(packagePath)), PathFragment.create(path));
  }

  private static RootedPath childOf(Artifact artifact, String relative) {
    return RootedPath.toRootedPath(
        artifact.getRoot().getRoot(), artifact.getRootRelativePath().getRelative(relative));
  }

  private static RootedPath parentOf(RootedPath path) {
    return checkNotNull(path.getParentDirectory());
  }

  private static RootedPath siblingOf(Artifact artifact, String relative) {
    PathFragment parent = checkNotNull(artifact.getRootRelativePath().getParentDirectory());
    return RootedPath.toRootedPath(artifact.getRoot().getRoot(), parent.getRelative(relative));
  }

  private void createFile(Path path, String... contents) throws Exception {
    if (!path.getParentDirectory().exists()) {
      scratch.dir(path.getParentDirectory().getPathString());
    }
    scratch.file(path.getPathString(), contents);
  }

  private void createFile(Artifact artifact, String... contents) throws Exception {
    createFile(artifact.getPath(), contents);
  }

  private RootedPath createFile(RootedPath path, String... contents) throws Exception {
    scratch.dir(parentOf(path).asPath().getPathString());
    createFile(path.asPath(), contents);
    return path;
  }

  private static TraversalRequest fileLikeRoot(
      Artifact file, boolean strictOutput, boolean emitEmptyDirectoryNodes) {
    return new AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
        DirectTraversalRoot.forFileOrDirectory(file),
        /* isRootGenerated= */ !file.isSourceArtifact(),
        strictOutput,
        /* skipTestingForSubpackage= */ false,
        emitEmptyDirectoryNodes);
  }

  private static TraversalRequest fileLikeRoot(Artifact file, boolean strictOutput) {
    return fileLikeRoot(file, strictOutput, /* emitEmptyDirectoryNodes= */ false);
  }

  private static TraversalRequest fileLikeRoot(Artifact file) {
    return fileLikeRoot(file, false);
  }

  private static TraversalRequest pkgRoot(RootedPath pkgDirectory) {
    return new AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
        DirectTraversalRoot.forRootedPath(pkgDirectory),
        /* isRootGenerated= */ false,
        /* strictOutputFiles= */ false,
        /* skipTestingForSubpackage= */ true,
        /* emitEmptyDirectoryNodes= */ false);
  }

  @AutoValue
  abstract static class BasicTraversalRequest extends TraversalRequest {

    @Override
    protected final String errorInfo() {
      return "";
    }

    @Override
    protected final TraversalRequest duplicateWithOverrides(
        DirectTraversalRoot root, boolean skipTestingForSubpackage) {
      return new AutoValue_RecursiveFilesystemTraversalFunctionTest_BasicTraversalRequest(
          root,
          isRootGenerated(),
          strictOutputFiles(),
          skipTestingForSubpackage,
          emitEmptyDirectoryNodes());
    }
  }

  private <T extends SkyValue> EvaluationResult<T> eval(SkyKey key) throws Exception {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    return evaluator.evaluate(ImmutableList.of(key), evaluationContext);
  }

  private RecursiveFilesystemTraversalValue evalTraversalRequest(TraversalRequest params)
      throws Exception {
    EvaluationResult<RecursiveFilesystemTraversalValue> result = eval(params);
    assertThat(result.hasError()).isFalse();
    return result.get(params);
  }

  /**
   * Asserts that the requested SkyValue can be built and results in the expected set of files.
   *
   * <p>The metadata of files is ignored in comparing the actual results with the expected ones. The
   * returned object however contains the actual metadata.
   */
  private RecursiveFilesystemTraversalValue traverseAndAssertFiles(
      TraversalRequest params, ResolvedFile... expectedFilesIgnoringMetadata) throws Exception {
    RecursiveFilesystemTraversalValue result = evalTraversalRequest(params);
    Map<PathFragment, ResolvedFile> nameToActualResolvedFiles = new HashMap<>();
    for (ResolvedFile act : result.getTransitiveFiles().toList()) {
      // We can't compare  directly, since metadata would be different, so we compare
      // by comparing the results of public method calls..
      nameToActualResolvedFiles.put(act.getNameInSymlinkTree(), act);
    }
    assertExpectedResolvedFilesPresent(nameToActualResolvedFiles, expectedFilesIgnoringMetadata);
    return result;
  }

  private static void assertExpectedResolvedFilesPresent(
      Map<PathFragment, ResolvedFile> nameToActualResolvedFiles,
      ResolvedFile... expectedFilesIgnoringMetadata)
      throws Exception {
    assertWithMessage("Expected files " + Arrays.toString(expectedFilesIgnoringMetadata))
        .that(nameToActualResolvedFiles)
        .hasSize(expectedFilesIgnoringMetadata.length);
    assertEquals(
        "Unequal number of ResolvedFiles in Actual and expected.",
        expectedFilesIgnoringMetadata.length,
        nameToActualResolvedFiles.size());
    for (ResolvedFile expected : expectedFilesIgnoringMetadata) {
      ResolvedFile actual = nameToActualResolvedFiles.get(expected.getNameInSymlinkTree());
      assertEquals(expected.getType(), actual.getType());
      assertEquals(expected.getPath(), actual.getPath());
    }
  }

  private void appendToFile(RootedPath rootedPath, SkyKey toInvalidate, String content)
      throws Exception {
    Path path = rootedPath.asPath();
    if (path.exists()) {
      try (OutputStream os = path.getOutputStream(/*append=*/ true)) {
        os.write(content.getBytes(StandardCharsets.UTF_8));
      }
      differencer.invalidate(ImmutableList.of(toInvalidate));
    } else {
      createFile(path, content);
    }
  }

  private void appendToFile(RootedPath rootedPath, String content) throws Exception {
    appendToFile(rootedPath, FileStateValue.key(rootedPath), content);
  }

  private void appendToFile(Artifact file, String content) throws Exception {
    SkyKey key =
        file.isSourceArtifact()
            ? FileStateValue.key(rootedPath(file))
            : new NonHermeticArtifactSkyKey(file);
    appendToFile(rootedPath(file), key, content);
  }

  private void invalidateDirectory(RootedPath path) {
    differencer.invalidate(ImmutableList.of(DirectoryListingStateValue.key(path)));
  }

  private void invalidateDirectory(Artifact directoryArtifact) {
    invalidateDirectory(rootedPath(directoryArtifact));
  }

  private void invalidateOutputArtifact(Artifact output) {
    assertThat(output.isSourceArtifact()).isFalse();
    differencer.invalidate(ImmutableList.of(new NonHermeticArtifactSkyKey(output)));
  }

  private static final class RecordingEvaluationProgressReceiver
      implements EvaluationProgressReceiver {
    Set<SkyKey> invalidations;
    Set<SkyKey> evaluations;

    RecordingEvaluationProgressReceiver() {
      clear();
    }

    void clear() {
      invalidations = Sets.newConcurrentHashSet();
      evaluations = Sets.newConcurrentHashSet();
    }

    @Override
    public void dirtied(SkyKey skyKey, DirtyType dirtyType) {
      invalidations.add(skyKey);
    }

    @Override
    public void deleted(SkyKey skyKey) {
      invalidations.add(skyKey);
    }

    @Override
    public void evaluated(
        SkyKey skyKey,
        EvaluationState state,
        @Nullable SkyValue newValue,
        @Nullable ErrorInfo newError,
        @Nullable GroupedDeps directDeps) {
      if (state.succeeded()) {
        evaluations.add(skyKey);
      }
    }
  }

  private static void assertTraversalRootHashesAre(
      boolean equal, RecursiveFilesystemTraversalValue a, RecursiveFilesystemTraversalValue b) {
    if (equal) {
      assertThat(a.getResolvedRoot().get().hashCode())
          .isEqualTo(b.getResolvedRoot().get().hashCode());
    } else {
      assertThat(a.getResolvedRoot().get().hashCode())
          .isNotEqualTo(b.getResolvedRoot().get().hashCode());
    }
  }

  private static void assertTraversalRootHashesAreEqual(
      RecursiveFilesystemTraversalValue a, RecursiveFilesystemTraversalValue b) {
    assertTraversalRootHashesAre(true, a, b);
  }

  private static void assertTraversalRootHashesAreNotEqual(
      RecursiveFilesystemTraversalValue a, RecursiveFilesystemTraversalValue b) {
    assertTraversalRootHashesAre(false, a, b);
  }

  private void assertTraversalOfFile(Artifact rootArtifact, boolean strictOutput) throws Exception {
    TraversalRequest traversalRoot = fileLikeRoot(rootArtifact, strictOutput);
    RootedPath rootedPath = createFile(rootedPath(rootArtifact), "foo");

    // Assert that the SkyValue is built and looks right.
    ResolvedFile expected = regularFile(rootedPath, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v1 = traverseAndAssertFiles(traversalRoot, expected);
    assertThat(progressReceiver.invalidations).isEmpty();
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    progressReceiver.clear();

    // Edit the file and verify that the value is rebuilt.
    appendToFile(rootArtifact, "bar");
    RecursiveFilesystemTraversalValue v2 = traverseAndAssertFiles(traversalRoot, expected);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreNotEqual(v1, v2);

    progressReceiver.clear();
  }

  @Test
  public void testTraversalOfSourceFile() throws Exception {
    assertTraversalOfFile(sourceArtifact("foo/bar.txt"), false);
  }

  @Test
  public void testTraversalOfGeneratedFile() throws Exception {
    assertTraversalOfFile(derivedArtifact("foo/bar.txt"), false);
  }

  @Test
  public void testTraversalOfGeneratedFileWithStrictOutput() throws Exception {
    assertTraversalOfFile(derivedArtifact("foo/bar.txt"), true);
  }


  @Test
  public void testTraversalOfSymlinkToFile() throws Exception {
    Artifact linkNameArtifact = sourceArtifact("foo/baz/qux.sym");
    Artifact linkTargetArtifact = sourceArtifact("foo/bar/baz.txt");
    PathFragment linkValue = PathFragment.create("../bar/baz.txt");
    TraversalRequest traversalRoot = fileLikeRoot(linkNameArtifact);
    createFile(linkTargetArtifact);
    scratch.dir(linkNameArtifact.getExecPath().getParentDirectory().getPathString());
    rootDirectory.getRelative(linkNameArtifact.getExecPath()).createSymbolicLink(linkValue);

    // Assert that the SkyValue is built and looks right.
    RootedPath symlinkNamePath = rootedPath(linkNameArtifact);
    RootedPath symlinkTargetPath = rootedPath(linkTargetArtifact);
    ResolvedFile expected =
        symlinkToFile(symlinkTargetPath, symlinkNamePath, linkValue, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v1 = traverseAndAssertFiles(traversalRoot, expected);
    assertThat(progressReceiver.invalidations).isEmpty();
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    progressReceiver.clear();

    // Edit the target of the symlink and verify that the value is rebuilt.
    appendToFile(linkTargetArtifact, "bar");
    RecursiveFilesystemTraversalValue v2 = traverseAndAssertFiles(traversalRoot, expected);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreNotEqual(v1, v2);
  }

  @Test
  public void testTraversalOfTransitiveSymlinkToFile() throws Exception {
    Artifact directLinkArtifact = sourceArtifact("direct/file.sym");
    Artifact transitiveLinkArtifact = sourceArtifact("transitive/sym.sym");
    RootedPath fileA = createFile(rootedPath(sourceArtifact("a/file.a")));
    RootedPath directLink = rootedPath(directLinkArtifact);
    RootedPath transitiveLink = rootedPath(transitiveLinkArtifact);
    PathFragment directLinkPath = PathFragment.create("../a/file.a");
    PathFragment transitiveLinkPath = PathFragment.create("../direct/file.sym");

    parentOf(directLink).asPath().createDirectory();
    parentOf(transitiveLink).asPath().createDirectory();
    directLink.asPath().createSymbolicLink(directLinkPath);
    transitiveLink.asPath().createSymbolicLink(transitiveLinkPath);

    traverseAndAssertFiles(
        fileLikeRoot(directLinkArtifact),
        symlinkToFile(fileA, directLink, directLinkPath, EMPTY_METADATA));

    traverseAndAssertFiles(
        fileLikeRoot(transitiveLinkArtifact),
        symlinkToFile(fileA, transitiveLink, transitiveLinkPath, EMPTY_METADATA));
  }

  private void assertTraversalOfDirectory(Artifact directoryArtifact) throws Exception {
    // Create files under the directory.
    // Use the root + root-relative path of the rootArtifact to create these files, rather than
    // using the rootDirectory + execpath of the rootArtifact. The resulting paths are the same
    // but the RootedPaths are different:
    // in the 1st case, it is: RootedPath(/root/execroot, relative), in the second it is
    // in the 2nd case, it is: RootedPath(/root, execroot/relative).
    // Creating the files will also create the parent directories.
    RootedPath file1 = createFile(childOf(directoryArtifact, "bar.txt"));
    RootedPath file2;
    if (directoryArtifact.isTreeArtifact()) {
      file2 = createFile(childOf(directoryArtifact, "qux.txt"));
      addNewTreeFileArtifact((SpecialArtifact) directoryArtifact, "bar.txt");
      addNewTreeFileArtifact((SpecialArtifact) directoryArtifact, "qux.txt");
    } else {
      file2 = createFile(childOf(directoryArtifact, "baz/qux.txt"));
    }

    TraversalRequest traversalRoot = fileLikeRoot(directoryArtifact);

    // Assert that the SkyValue is built and looks right.
    ResolvedFile expected1 = regularFile(file1, EMPTY_METADATA);
    ResolvedFile expected2 = regularFile(file2, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v1 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2);
    assertThat(progressReceiver.invalidations).isEmpty();
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    progressReceiver.clear();

    // Add a new file to the directory and see that the value is rebuilt.
    TimestampGranularityUtils.waitForTimestampGranularity(
        directoryArtifact.getPath().stat().getLastChangeTime(), OutErr.SYSTEM_OUT_ERR);
    RootedPath file3 = createFile(childOf(directoryArtifact, "foo.txt"));
    if (directoryArtifact.isTreeArtifact()) {
      addNewTreeFileArtifact((SpecialArtifact) directoryArtifact, "foo.txt");
    }
    if (directoryArtifact.isSourceArtifact()) {
      invalidateDirectory(directoryArtifact);
    } else {
      invalidateOutputArtifact(directoryArtifact);
    }
    ResolvedFile expected3 = regularFile(file3, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v2 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    // Directories always have the same hash code, but that is fine because their contents are also
    // part of the RecursiveFilesystemTraversalValue, so v1 and v2 are unequal.
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreEqual(v1, v2);
    progressReceiver.clear();

    // Edit a file in the directory and see that the value is rebuilt.
    RecursiveFilesystemTraversalValue v3;
    if (directoryArtifact.isSourceArtifact()) {
      SkyKey toInvalidate = FileStateValue.key(file1);
      appendToFile(file1, toInvalidate, "bar");
      v3 = traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3);
      assertThat(progressReceiver.invalidations).contains(traversalRoot);
      assertThat(progressReceiver.evaluations).contains(traversalRoot);
      assertThat(v3).isNotEqualTo(v2);
      // Directories always have the same hash code, but that is fine because their contents are
      // also part of the RecursiveFilesystemTraversalValue, so v2 and v3 are unequal.
      assertTraversalRootHashesAreEqual(v2, v3);
      progressReceiver.clear();
    } else {
      // Dependency checking of output directories is unsound. Specifically, the directory mtime
      // is not changed when a contained file is modified.
      v3 = v2;
    }

    // Add a new file *outside* of the directory and see that the value is *not* rebuilt.
    Artifact someFile = sourceArtifact("somewhere/else/a.file");
    createFile(someFile, "new file");
    appendToFile(someFile, "not all changes are treated equal");
    RecursiveFilesystemTraversalValue v4 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3);
    assertThat(v4).isSameInstanceAs(v3);
    assertTraversalRootHashesAreEqual(v3, v4);
    assertThat(progressReceiver.invalidations).doesNotContain(traversalRoot);

    // Add a new empty subdirectory to the directory and see that the value is rebuilt, but results
    // in the collection of files.
    // TODO(#15901): Empty directories currently aren't representable as tree artifact contents and
    //  thus aren't tested here.
    if (!directoryArtifact.isTreeArtifact()) {
      childOf(directoryArtifact, "empty_dir").asPath().createDirectory();
      if (directoryArtifact.isSourceArtifact()) {
        invalidateDirectory(directoryArtifact);
      } else {
        invalidateOutputArtifact(directoryArtifact);
      }

      RecursiveFilesystemTraversalValue v5 =
          traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3);
      assertThat(v5.getResolvedRoot()).isEqualTo(v4.getResolvedRoot());
      assertThat(v5.getTransitiveFiles().toList())
          .containsExactlyElementsIn(v4.getTransitiveFiles().toList());
      assertThat(progressReceiver.invalidations).contains(traversalRoot);
    }
  }

  @Test
  public void testTraversalOfSourceDirectory() throws Exception {
    assertTraversalOfDirectory(sourceArtifact("dir"));
  }

  @Test
  public void testTraversalOfSourceTreeArtifact() throws Exception {
    assertTraversalOfDirectory(treeArtifact("dir"));
  }

  // Note that in actual Bazel derived artifact directories are not checked for modifications on
  // incremental builds by default. See TrackSourceDirectoriesFlag.
  @Test
  public void testTraversalOfGeneratedDirectory() throws Exception {
    assertTraversalOfDirectory(derivedArtifact("dir"));
  }

  @Test
  public void testTraversalOfSourceDirectoryWithEmptyDirectoryNodes() throws Exception {
    Artifact directoryArtifact = sourceArtifact("dir");
    directoryArtifact.getPath().createDirectoryAndParents();

    TraversalRequest traversalRoot =
        fileLikeRoot(
            directoryArtifact, /* strictOutput= */ false, /* emitEmptyDirectoryNodes= */ true);

    // Assert that the SkyValue is built and looks right.
    ResolvedFile rootNode =
        ResolvedFileFactory.directory(
            RootedPath.toRootedPath(
                directoryArtifact.getRoot().getRoot(), directoryArtifact.getRootRelativePath()));
    RecursiveFilesystemTraversalValue v1 = traverseAndAssertFiles(traversalRoot, rootNode);
    assertThat(progressReceiver.invalidations).isEmpty();
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    progressReceiver.clear();

    // Add a new file to the directory and see that the value is rebuilt.
    RootedPath emptyDir = childOf(directoryArtifact, "empty_dir");
    emptyDir.asPath().createDirectory();
    ResolvedFile emptyDirNode = ResolvedFileFactory.directory(emptyDir);
    invalidateDirectory(directoryArtifact);

    // The value only contains nodes for empty directories - the root dir is no longer empty at this
    // point and thus not represented as a node.
    RecursiveFilesystemTraversalValue v2 = traverseAndAssertFiles(traversalRoot, emptyDirNode);
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreEqual(v1, v2);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
  }

  @Test
  public void testTraversalOfTransitiveSymlinkToDirectory() throws Exception {
    Artifact directLinkArtifact = sourceArtifact("direct/dir.sym");
    Artifact transitiveLinkArtifact = sourceArtifact("transitive/sym.sym");
    RootedPath fileA = createFile(rootedPath(sourceArtifact("a/file.a")));
    RootedPath directLink = rootedPath(directLinkArtifact);
    RootedPath transitiveLink = rootedPath(transitiveLinkArtifact);
    PathFragment directLinkPath = PathFragment.create("../a");
    PathFragment transitiveLinkPath = PathFragment.create("../direct/dir.sym");

    parentOf(directLink).asPath().createDirectory();
    parentOf(transitiveLink).asPath().createDirectory();
    directLink.asPath().createSymbolicLink(directLinkPath);
    transitiveLink.asPath().createSymbolicLink(transitiveLinkPath);

    // Expect the file as if was a child of the direct symlink, not of the actual directory.
    traverseAndAssertFiles(
        fileLikeRoot(directLinkArtifact),
        symlinkToDirectory(parentOf(fileA), directLink, directLinkPath, EMPTY_METADATA),
        regularFile(childOf(directLinkArtifact, "file.a"), EMPTY_METADATA));

    // Expect the file as if was a child of the transitive symlink, not of the actual directory.
    traverseAndAssertFiles(
        fileLikeRoot(transitiveLinkArtifact),
        symlinkToDirectory(parentOf(fileA), transitiveLink, transitiveLinkPath, EMPTY_METADATA),
        regularFile(childOf(transitiveLinkArtifact, "file.a"), EMPTY_METADATA));
  }

  @Test
  public void testTraversePackage() throws Exception {
    Artifact buildFile = sourceArtifact("pkg/BUILD");
    RootedPath buildFilePath = createFile(rootedPath(buildFile));
    RootedPath file1 = createFile(siblingOf(buildFile, "subdir/file.a"));

    traverseAndAssertFiles(
        pkgRoot(parentOf(buildFilePath)),
        regularFile(buildFilePath, EMPTY_METADATA),
        regularFile(file1, EMPTY_METADATA));
  }

  @Test
  public void testTraversalOfSymlinkToDirectory() throws Exception {
    Artifact linkNameArtifact = sourceArtifact("link/foo.sym");
    Artifact linkTargetArtifact = sourceArtifact("dir");
    RootedPath linkName = rootedPath(linkNameArtifact);
    PathFragment linkValue = PathFragment.create("../dir");
    RootedPath file1 = createFile(childOf(linkTargetArtifact, "file.1"));
    createFile(childOf(linkTargetArtifact, "sub/file.2"));
    scratch.dir(parentOf(linkName).asPath().getPathString());
    linkName.asPath().createSymbolicLink(linkValue);

    // Assert that the SkyValue is built and looks right.
    TraversalRequest traversalRoot = fileLikeRoot(linkNameArtifact);
    ResolvedFile expected1 =
        symlinkToDirectory(rootedPath(linkTargetArtifact), linkName, linkValue, EMPTY_METADATA);
    ResolvedFile expected2 = regularFile(childOf(linkNameArtifact, "file.1"), EMPTY_METADATA);
    ResolvedFile expected3 = regularFile(childOf(linkNameArtifact, "sub/file.2"), EMPTY_METADATA);
    // We expect to see all the files from the symlink'd directory, under the symlink's path, not
    // under the symlink target's path.
    RecursiveFilesystemTraversalValue v1 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3);
    assertThat(progressReceiver.invalidations).isEmpty();
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    progressReceiver.clear();

    // Add a new file to the directory and see that the value is rebuilt.
    createFile(childOf(linkTargetArtifact, "file.3"));
    invalidateDirectory(linkTargetArtifact);
    ResolvedFile expected4 = regularFile(childOf(linkNameArtifact, "file.3"), EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v2 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreNotEqual(v1, v2);
    progressReceiver.clear();

    // Edit a file in the directory and see that the value is rebuilt.
    appendToFile(file1, "bar");
    RecursiveFilesystemTraversalValue v3 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4);
    assertThat(progressReceiver.invalidations).contains(traversalRoot);
    assertThat(progressReceiver.evaluations).contains(traversalRoot);
    assertThat(v3).isNotEqualTo(v2);
    assertTraversalRootHashesAreNotEqual(v2, v3);
    progressReceiver.clear();

    // Add a new file *outside* of the directory and see that the value is *not* rebuilt.
    Artifact someFile = sourceArtifact("somewhere/else/a.file");
    createFile(someFile, "new file");
    appendToFile(someFile, "not all changes are treated equal");
    RecursiveFilesystemTraversalValue v4 =
        traverseAndAssertFiles(traversalRoot, expected1, expected2, expected3, expected4);
    assertThat(v4).isEqualTo(v3);
    assertTraversalRootHashesAreEqual(v3, v4);
    assertThat(progressReceiver.invalidations).doesNotContain(traversalRoot);
  }

  @Test
  public void testTraversalOfDanglingSymlink() throws Exception {
    Artifact linkArtifact = sourceArtifact("a/dangling.sym");
    RootedPath link = rootedPath(linkArtifact);
    PathFragment linkTarget = PathFragment.create("non_existent");
    parentOf(link).asPath().createDirectory();
    link.asPath().createSymbolicLink(linkTarget);
    traverseAndAssertFiles(
        fileLikeRoot(linkArtifact), danglingSymlink(link, linkTarget, EMPTY_METADATA));
  }

  @Test
  public void testTraversalOfDanglingSymlinkInADirectory() throws Exception {
    Artifact dirArtifact = sourceArtifact("a");
    RootedPath file = createFile(childOf(dirArtifact, "file.txt"));
    RootedPath link = rootedPath(sourceArtifact("a/dangling.sym"));
    PathFragment linkTarget = PathFragment.create("non_existent");
    parentOf(link).asPath().createDirectory();
    link.asPath().createSymbolicLink(linkTarget);
    traverseAndAssertFiles(
        fileLikeRoot(dirArtifact),
        regularFile(file, EMPTY_METADATA),
        danglingSymlink(link, linkTarget, EMPTY_METADATA));
  }

  @Test
  public void testReportErrorWhenTraversingSubpackages() throws Exception {
    Artifact pkgDirArtifact = sourceArtifact("pkg1/foo");
    Artifact subpkgDirArtifact = sourceArtifact("pkg1/foo/subdir/subpkg");
    RootedPath pkgBuildFile = childOf(pkgDirArtifact, "BUILD");
    RootedPath subpkgBuildFile = childOf(subpkgDirArtifact, "BUILD");
    scratch.dir(rootedPath(pkgDirArtifact).asPath().getPathString());
    scratch.dir(rootedPath(subpkgDirArtifact).asPath().getPathString());
    createFile(pkgBuildFile);
    createFile(subpkgBuildFile);

    TraversalRequest traversalRoot = pkgRoot(parentOf(pkgBuildFile));
    EvaluationResult<SkyValue> result = eval(traversalRoot);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains("crosses package boundary into package rooted at");
  }

  @Test
  public void testFileDigestChangeCausesRebuild() throws Exception {
    Artifact artifact = sourceArtifact("foo/bar.txt");
    RootedPath path = rootedPath(artifact);
    createFile(path, "hello");

    // Assert that the SkyValue is built and looks right.
    TraversalRequest params = fileLikeRoot(artifact);
    ResolvedFile expected = regularFile(path, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v1 = traverseAndAssertFiles(params, expected);
    assertThat(progressReceiver.evaluations).contains(params);
    progressReceiver.clear();

    // Change the digest of the file. See that the value is rebuilt.
    appendToFile(path, "world");
    RecursiveFilesystemTraversalValue v2 = traverseAndAssertFiles(params, expected);
    assertThat(progressReceiver.invalidations).contains(params);
    assertThat(v2).isNotEqualTo(v1);
    assertTraversalRootHashesAreNotEqual(v1, v2);
  }

  @Test
  public void testFileMtimeChangeDoesNotCauseRebuildIfDigestIsUnchanged() throws Exception {
    Artifact artifact = sourceArtifact("foo/bar.txt");
    RootedPath path = rootedPath(artifact);
    createFile(path, "hello");

    // Assert that the SkyValue is built and looks right.
    TraversalRequest params = fileLikeRoot(artifact);
    ResolvedFile expected = regularFile(path, EMPTY_METADATA);
    RecursiveFilesystemTraversalValue v1 = traverseAndAssertFiles(params, expected);
    assertThat(progressReceiver.evaluations).contains(params);
    progressReceiver.clear();

    // Change the mtime of the file but not the digest. See that the value is *not* rebuilt.
    TimestampGranularityUtils.waitForTimestampGranularity(
        path.asPath().stat().getLastChangeTime(), OutErr.SYSTEM_OUT_ERR);
    path.asPath().setLastModifiedTime(System.currentTimeMillis());
    RecursiveFilesystemTraversalValue v2 = traverseAndAssertFiles(params, expected);
    assertThat(v2).isEqualTo(v1);
    assertTraversalRootHashesAreEqual(v1, v2);
  }

  @Test
  public void testGeneratedDirectoryConflictsWithPackage() throws Exception {
    Artifact genDir = derivedArtifact("a/b");
    createFile(rootedPath(sourceArtifact("a/b/c/file.real")));
    createFile(rootedPath(derivedArtifact("a/b/c/file.fake")));
    createFile(sourceArtifact("a/b/c/BUILD"));

    SkyKey key = fileLikeRoot(genDir);
    EvaluationResult<SkyValue> result = eval(key);
    assertThat(result.hasError()).isTrue();
    ErrorInfo error = result.getError(key);
    assertThat(error.isTransitivelyTransient()).isFalse();
    assertThat(error.getException())
        .hasMessageThat()
        .contains("Generated directory a/b/c conflicts with package under the same path.");
  }

  @Test
  public void unboundedSymlinkExpansionError() throws Exception {
    Artifact bazLink = sourceArtifact("foo/baz.sym");
    Path parentDir = scratch.dir("foo");
    bazLink.getPath().createSymbolicLink(parentDir);
    SkyKey key = pkgRoot(parentOf(rootedPath(bazLink)));
    EvaluationResult<SkyValue> result = eval(key);
    assertThat(result.hasError()).isTrue();
    ErrorInfo error = result.getError(key);
    assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException.class);
    assertThat(((RecursiveFilesystemTraversalException) error.getException()).getType())
        .isEqualTo(RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION);
    assertThat(error.getException()).hasMessageThat().contains("Infinite symlink expansion");
  }

  @Test
  public void symlinkChainError() throws Exception {
    scratch.dir("a");
    Artifact fooLink = sourceArtifact("a/foo.sym");
    Artifact barLink = sourceArtifact("a/bar.sym");
    Artifact bazLink = sourceArtifact("a/baz.sym");
    fooLink.getPath().createSymbolicLink(barLink.getPath());
    barLink.getPath().createSymbolicLink(bazLink.getPath());
    bazLink.getPath().createSymbolicLink(fooLink.getPath());

    SkyKey key = pkgRoot(parentOf(rootedPath(bazLink)));
    EvaluationResult<SkyValue> result = eval(key);
    assertThat(result.hasError()).isTrue();
    ErrorInfo error = result.getError(key);
    assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException.class);
    assertThat(((RecursiveFilesystemTraversalException) error.getException()).getType())
        .isEqualTo(RecursiveFilesystemTraversalException.Type.SYMLINK_CYCLE_OR_INFINITE_EXPANSION);
    assertThat(error.getException()).hasMessageThat().contains("Symlink cycle");
  }

  private static final class NonHermeticArtifactFakeFunction implements SkyFunction {

    private TreeArtifactValue.Builder tree;

    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
      try {
        if (skyKey.argument() instanceof Artifact
            && ((Artifact) skyKey.argument()).isTreeArtifact()) {
          return tree.build();
        }
        return FileArtifactValue.createForTesting(((Artifact) skyKey.argument()).getPath());
      } catch (IOException e) {
        throw new SkyFunctionException(e, Transience.PERSISTENT){};
      }
    }

    void addNewTreeFileArtifact(TreeFileArtifact input) throws IOException {
      if (tree == null) {
        tree = TreeArtifactValue.newBuilder(input.getParent());
      }
      tree.putChild(input, FileArtifactValue.createForTesting(input.getPath()));
    }
  }

  private static final class ArtifactFakeFunction implements SkyFunction {
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
      return env.getValue(new NonHermeticArtifactSkyKey(skyKey));
    }
  }

  private final class ActionFakeFunction implements SkyFunction {
    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
      return env.getValue(
          new NonHermeticArtifactSkyKey(
              checkNotNull(artifacts.get(((ActionLookupData) skyKey).getActionIndex()), skyKey)));
    }
  }

  @Test
  public void testFileArtifactValueRetainsData() throws Exception {
    Artifact artifact = derivedArtifact("foo/fooy.txt");
    Artifact strictArtifact = derivedArtifact("goo/gooy.txt");
    createFile(rootedPath(artifact), "fooy");
    createFile(rootedPath(strictArtifact), "gooy");
    TraversalRequest request = fileLikeRoot(artifact, false);
    TraversalRequest strictRequest = fileLikeRoot(strictArtifact, true);

    EvaluationResult<RecursiveFilesystemTraversalValue> result = eval(request);
    EvaluationResult<RecursiveFilesystemTraversalValue> strictResult = eval(strictRequest);

    assertThat(result.values()).hasSize(1);
    assertThat(strictResult.values()).hasSize(1);

    RecursiveFilesystemTraversalValue value = result.values().iterator().next();
    RecursiveFilesystemTraversalValue strictValue = strictResult.values().iterator().next();
    ResolvedFile resolvedFile = value.getResolvedRoot().get();
    ResolvedFile strictResolvedFile = strictValue.getResolvedRoot().get();

    assertThat(resolvedFile.getMetadata()).isInstanceOf(FileArtifactValue.class);
    assertThat(strictResolvedFile.getMetadata()).isInstanceOf(FileArtifactValue.class);
  }

  @Test
  public void testWithDigestFileArtifactValue() throws Exception {
    // file artifacts will return the same bytes as it was initialized with
    byte[] expectedBytes = new byte[] {1, 2, 3};
    FileArtifactValue fav = FileArtifactValue.createForVirtualActionInput(expectedBytes, 10L);
    HasDigest result =
        RecursiveFilesystemTraversalFunction.withDigest(fav, null, SyscallCache.NO_CACHE);
    assertThat(result).isInstanceOf(FileArtifactValue.class);
    assertThat(result.getDigest()).isEqualTo(expectedBytes);

    // Directories do not have digest but the result will have a fingerprinted digest
    FileArtifactValue directoryFav = FileArtifactValue.createForDirectoryWithMtime(10L);
    HasDigest directoryResult =
        RecursiveFilesystemTraversalFunction.withDigest(directoryFav, null, SyscallCache.NO_CACHE);
    assertThat(directoryResult).isInstanceOf(HasDigest.ByteStringDigest.class);
    assertThat(directoryResult.getDigest()).isNotNull();
  }

  @Test
  public void testWithDigestFileStateValue() throws Exception {
    // RegularFileStateValue with actual digest will be transformed with the same digest
    byte[] expectedBytes = new byte[] {1, 2, 3};
    RegularFileStateValueWithDigest withDigest =
        new RegularFileStateValueWithDigest(/* size= */ 10L, /* digest= */ expectedBytes);
    HasDigest result =
        RecursiveFilesystemTraversalFunction.withDigest(withDigest, null, SyscallCache.NO_CACHE);
    assertThat(result).isInstanceOf(FileArtifactValue.class);
    assertThat(result.getDigest()).isEqualTo(expectedBytes);

    // FileStateValue will be transformed with fingerprinted digest
    RootedPath rootedPath = rootedPath("bar", "foo");
    FileStateValue fsv = FileStateValue.create(rootedPath, SyscallCache.NO_CACHE, /*tsgm=*/ null);
    HasDigest fsvResult =
        RecursiveFilesystemTraversalFunction.withDigest(fsv, null, SyscallCache.NO_CACHE);
    assertThat(fsvResult).isInstanceOf(HasDigest.ByteStringDigest.class);
    assertThat(fsvResult.getDigest()).isNotNull();
  }

  @Test
  public void testRegularFileStateValueWithoutDigest() throws Exception {
    Artifact artifact = derivedArtifact("foo/fooy.txt");
    RootedPath rootedPath = rootedPath(artifact);
    createFile(rootedPath, "fooy-content");
    FileStatus status = rootedPath.asPath().stat();

    RegularFileStateValueWithContentsProxy withoutDigest =
        new RegularFileStateValueWithContentsProxy(
            status.getSize(), /* contentsProxy= */ FileContentsProxy.create(status));
    HasDigest withoutDigestResult =
        RecursiveFilesystemTraversalFunction.withDigest(
            withoutDigest, rootedPath.asPath(), SyscallCache.NO_CACHE);
    // withDigest will construct a FileArtifactValue using the Path
    assertThat(withoutDigestResult).isInstanceOf(FileArtifactValue.class);
    assertThat(withoutDigestResult.getDigest()).isNotNull();
  }

  @Test
  public void testWithDigestByteStringDigest() throws Exception {
    byte[] expectedBytes = new byte[] {1, 2, 3};
    HasDigest.ByteStringDigest byteStringDigest = new HasDigest.ByteStringDigest(expectedBytes);
    HasDigest result =
        RecursiveFilesystemTraversalFunction.withDigest(
            byteStringDigest, null, SyscallCache.NO_CACHE);
    assertThat(result).isInstanceOf(HasDigest.ByteStringDigest.class);
    assertThat(result.getDigest()).isEqualTo(expectedBytes);
  }

  @Test
  public void testGracefullyHandlesInconsistentFilesystem() throws Exception {
    scratch.dir("parent");
    PathFragment childPathFragment = scratch.file("parent/child").asFragment();
    pathsToPretendDontExist.add(childPathFragment);
    Artifact childArtifact = sourceArtifact("parent/child");
    SkyKey key = pkgRoot(parentOf(rootedPath(childArtifact)));
    EvaluationResult<SkyValue> result = eval(key);
    assertThat(result.hasError()).isTrue();
    ErrorInfo error = result.getError(key);
    assertThat(error.getException()).isInstanceOf(RecursiveFilesystemTraversalException.class);
    assertThat(((RecursiveFilesystemTraversalException) error.getException()).getType())
        .isEqualTo(RecursiveFilesystemTraversalException.Type.INCONSISTENT_FILESYSTEM);
    assertThat(error.getException())
        .hasMessageThat()
        .contains("We were previously told [/workspace]/[parent/child] was an existing file but");
  }

  private static class NonHermeticArtifactSkyKey extends AbstractSkyKey<SkyKey> {
    private NonHermeticArtifactSkyKey(SkyKey arg) {
      super(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return NONHERMETIC_ARTIFACT;
    }
  }

  private static final SkyFunctionName NONHERMETIC_ARTIFACT =
      SkyFunctionName.createNonHermetic("NONHERMETIC_ARTIFACT");
}
