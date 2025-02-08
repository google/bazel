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
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.FilesetTraversalParams;
import com.google.devtools.build.lib.actions.FilesetTraversalParamsFactory;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.HashMap;
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

/** Tests for {@link FilesetEntryFunction}. */
@RunWith(JUnit4.class)
public final class FilesetEntryFunctionTest extends FoundationTestCase {
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;

  @Before
  public void setUp() throws Exception {
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(outputBase, outputBase, outputBase),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

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
            new AtomicReference<>(ImmutableSet.of()),
            CrossRepositoryLabelViolationStrategy.ERROR,
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));
    skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP);
    skyFunctions.put(
        SkyFunctions.FILESET_ENTRY, new FilesetEntryFunction((unused) -> rootDirectory));
    skyFunctions.put(SkyFunctions.WORKSPACE_NAME, new TestWorkspaceNameFunction());
    skyFunctions.put(
        SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
        new LocalRepositoryLookupFunction(BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER));

    differencer = new SequencedRecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(skyFunctions, differencer);
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
  }

  private Artifact getSourceArtifact(String path) {
    return ActionsTestUtil.createArtifact(
        ArtifactRoot.asSourceRoot(Root.fromPath(rootDirectory)), path);
  }

  private Artifact createSourceArtifact(String path) throws Exception {
    Artifact result = getSourceArtifact(path);
    createFile(result, "foo");
    return result;
  }

  private static RootedPath childOf(Artifact artifact, String relative) {
    return RootedPath.toRootedPath(
        artifact.getRoot().getRoot(), artifact.getRootRelativePath().getRelative(relative));
  }

  private static RootedPath siblingOf(Artifact artifact, String relative) {
    PathFragment parent =
        Preconditions.checkNotNull(artifact.getRootRelativePath().getParentDirectory());
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
    createFile(path.asPath(), contents);
    return path;
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

  private FilesetEntryValue evalFilesetTraversal(FilesetTraversalParams params) throws Exception {
    SkyKey key = FilesetEntryKey.key(params);
    EvaluationResult<FilesetEntryValue> result = eval(key);
    assertThat(result.hasError()).isFalse();
    return result.get(key);
  }

  private FilesetOutputSymlink symlink(String from, Artifact to) {
    return symlink(PathFragment.create(from), to.getPath().asFragment());
  }

  private FilesetOutputSymlink symlink(String from, RootedPath to) {
    return symlink(PathFragment.create(from), to.asPath().asFragment());
  }

  private FilesetOutputSymlink symlink(PathFragment from, PathFragment to) {
    return FilesetOutputSymlink.createForTesting(from, to, rootDirectory.asFragment());
  }

  private void assertSymlinksCreatedInOrder(
      FilesetTraversalParams request, FilesetOutputSymlink... expectedSymlinks) throws Exception {
    Collection<FilesetOutputSymlink> actual =
        Collections2.transform(
            evalFilesetTraversal(request).getSymlinks(),
            // Strip the metadata from the actual results.
            (input) ->
                FilesetOutputSymlink.createAlreadyRelativizedForTesting(
                    input.name(), input.targetPath(), input.relativeToExecRoot()));
    assertThat(actual).containsExactlyElementsIn(expectedSymlinks).inOrder();
  }

  private static Label label(String label) throws Exception {
    return Label.parseCanonical(label);
  }

  @Test
  public void testFileTraversalForFile() throws Exception {
    Artifact file = createSourceArtifact("foo/file.real");
    FilesetTraversalParams params =
        FilesetTraversalParamsFactory.fileTraversal(
            /* ownerLabel= */ label("//foo"),
            /* fileToTraverse= */ file,
            PathFragment.create("output-name"),
            /* strictFilesetOutput= */ false,
            /* permitDirectories= */ false);
    assertSymlinksCreatedInOrder(params, symlink("output-name", file));
  }


  @Test
  public void testFileTraversalForDirectory() throws Exception {
    Artifact dir = getSourceArtifact("foo/dir_real");
    RootedPath fileA = createFile(childOf(dir, "file.a"), "hello");
    RootedPath fileB = createFile(childOf(dir, "sub/file.b"), "world");

    FilesetTraversalParams params =
        FilesetTraversalParamsFactory.fileTraversal(
            /* ownerLabel= */ label("//foo"),
            /* fileToTraverse= */ dir,
            PathFragment.create("output-name"),
            /* strictFilesetOutput= */ false,
            /* permitDirectories= */ true);
    assertSymlinksCreatedInOrder(
        params, symlink("output-name/file.a", fileA), symlink("output-name/sub/file.b", fileB));
  }

  @Test
  public void testFileTraversalForDisallowedDirectoryThrows() throws Exception {
    Artifact dir = getSourceArtifact("foo/dir_real");
    createFile(childOf(dir, "file.a"), "hello");
    createFile(childOf(dir, "sub/file.b"), "world");

    FilesetTraversalParams params =
        FilesetTraversalParamsFactory.fileTraversal(
            /* ownerLabel= */ label("//foo"),
            /* fileToTraverse= */ dir,
            PathFragment.create("output-name"),
            /* strictFilesetOutput= */ false,
            /* permitDirectories= */ false);

    SkyKey key = FilesetEntryKey.key(params);
    EvaluationResult<FilesetEntryValue> result = eval(key);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError(key).getException())
        .hasMessageThat()
        .contains("foo contains a directory");
  }


  @Test
  public void testFileTraversalForDanglingSymlink() throws Exception {
    Artifact linkName = getSourceArtifact("foo/dangling.sym");
    RootedPath linkTarget = createFile(siblingOf(linkName, "target.file"), "blah");
    linkName.getPath().createSymbolicLink(PathFragment.create("target.file"));
    linkTarget.asPath().delete();

    FilesetTraversalParams params =
        FilesetTraversalParamsFactory.fileTraversal(
            /* ownerLabel= */ label("//foo"),
            /* fileToTraverse= */ linkName,
            PathFragment.create("output-name"),
            /* strictFilesetOutput= */ false,
            /* permitDirectories= */ false);
    assertSymlinksCreatedInOrder(params); // expect empty results
  }

  @Test
  public void testFileTraversalForNonExistentFile() throws Exception {
    Artifact path = getSourceArtifact("foo/non-existent");
    FilesetTraversalParams params =
        FilesetTraversalParamsFactory.fileTraversal(
            /* ownerLabel= */ label("//foo"),
            /* fileToTraverse= */ path,
            PathFragment.create("output-name"),
            /* strictFilesetOutput= */ false,
            /* permitDirectories= */ false);
    assertSymlinksCreatedInOrder(params); // expect empty results
  }

  /**
   * Tests that the fingerprint is a function of all arguments of the factory method.
   *
   * <p>Implementations must provide:
   * <ul>
   * <li>two different values (a domain) for each argument of the factory method and whether or not
   * it is expected to influence the fingerprint
   * <li>a way to instantiate {@link FilesetTraversalParams} with a given set of arguments from the
   * specified domains
   * </ul>
   *
   * <p>The tests will instantiate pairs of {@link FilesetTraversalParams} objects with only a given
   * attribute differing, and observe whether the fingerprints differ (if they are expected to) or
   * are the same (otherwise).
   */
  private abstract static class FingerprintTester {
    private final Map<String, Domain> domains;

    FingerprintTester(Map<String, Domain> domains) {
      this.domains = domains;
    }

    abstract FilesetTraversalParams create(Map<String, ?> kwArgs) throws Exception;

    private Map<String, ?> getDefaultArgs() {
      return getKwArgs(null);
    }

    private Map<String, ?> getKwArgs(@Nullable String useAlternateFor) {
      Map<String, Object> values = new HashMap<>();
      for (Map.Entry<String, Domain> d : domains.entrySet()) {
        values.put(
            d.getKey(),
            d.getKey().equals(useAlternateFor) ? d.getValue().valueA : d.getValue().valueB);
      }
      return values;
    }

    public void doTest() throws Exception {
      Fingerprint fp = new Fingerprint();

      create(getDefaultArgs()).fingerprint(fp);
      String primary = fp.hexDigestAndReset();

      for (String argName : domains.keySet()) {
        create(getKwArgs(argName)).fingerprint(fp);
        String secondary = fp.hexDigestAndReset();

        if (domains.get(argName).includedInFingerprint) {
          assertWithMessage(
                  "Argument '"
                      + argName
                      + "' was expected to be included in the"
                      + " fingerprint, but wasn't")
              .that(primary)
              .isNotEqualTo(secondary);
        } else {
          assertWithMessage(
                  "Argument '"
                      + argName
                      + "' was expected not to be included in the"
                      + " fingerprint, but was")
              .that(primary)
              .isEqualTo(secondary);
        }
      }
    }
  }

  private static final class Domain {
    boolean includedInFingerprint;
    Object valueA;
    Object valueB;

    Domain(boolean includedInFingerprint, Object valueA, Object valueB) {
      this.includedInFingerprint = includedInFingerprint;
      this.valueA = valueA;
      this.valueB = valueB;
    }
  }

  private static Domain partOfFingerprint(Object valueA, Object valueB) {
    return new Domain(true, valueA, valueB);
  }

  private static Domain notPartOfFingerprint(Object valueB) {
    return new Domain(false, "//foo", valueB);
  }

  @Test
  public void testFingerprintOfFileTraversal() throws Exception {
    new FingerprintTester(
        ImmutableMap.<String, Domain>builder()
            .put("ownerLabel", notPartOfFingerprint("//bar"))
            .put("fileToTraverse", partOfFingerprint("foo/file.a", "bar/file.b"))
            .put("destPath", partOfFingerprint("out1", "out2"))
            .put("strictFilesetOutput", partOfFingerprint(true, false))
            .put("permitDirectories", partOfFingerprint(true, false))
            .buildOrThrow()) {
      @Override
      FilesetTraversalParams create(Map<String, ?> kwArgs) throws Exception {
        return FilesetTraversalParamsFactory.fileTraversal(
            label((String) kwArgs.get("ownerLabel")),
            getSourceArtifact((String) kwArgs.get("fileToTraverse")),
            PathFragment.create((String) kwArgs.get("destPath")),
            (Boolean) kwArgs.get("strictFilesetOutput"),
            (Boolean) kwArgs.get("permitDirectories"));
      }
    }.doTest();
  }

  @Test
  public void testFingerprintOfNestedTraversal() throws Exception {
    Artifact nested1 = getSourceArtifact("a/b");
    Artifact nested2 = getSourceArtifact("a/c");

    new FingerprintTester(
        ImmutableMap.of(
            "ownerLabel", notPartOfFingerprint("//bar"),
            "nestedArtifact", partOfFingerprint(nested1, nested2),
            "destDir", partOfFingerprint("out1", "out2"),
            "excludes", partOfFingerprint(ImmutableSet.<String>of(), ImmutableSet.of("x")))) {
      @SuppressWarnings("unchecked")
      @Override
      FilesetTraversalParams create(Map<String, ?> kwArgs) throws Exception {
        return FilesetTraversalParamsFactory.nestedTraversal(
            label((String) kwArgs.get("ownerLabel")),
            (Artifact) kwArgs.get("nestedArtifact"),
            PathFragment.create((String) kwArgs.get("destDir")),
            (Set<String>) kwArgs.get("excludes"));
      }
    }.doTest();
  }

  private static class TestWorkspaceNameFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      return WorkspaceNameValue.withName("workspace");
    }
  }
}
