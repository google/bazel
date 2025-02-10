// Copyright 2018 The Bazel Authors. All rights reserved.
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.actions.HasDigest.ByteStringDigest;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.remote.RemoteActionFileSystem;
import com.google.devtools.build.lib.remote.RemoteActionInputFetcher;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ActionOutputMetadataStore}. */
@RunWith(TestParameterInjector.class)
public final class ActionOutputMetadataStoreTest {

  private enum ArtifactType {
    SOURCE,
    OUTPUT
  }

  private enum ResolvedPathDepth {
    SHALLOW,
    DEEP
  }

  private final Map<Path, Integer> chmodCalls = Maps.newConcurrentMap();

  private final Scratch scratch =
      new Scratch(
          new InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Override
            public void chmod(PathFragment pathFragment, int mode) throws IOException {
              Path path = getPath(pathFragment);
              if (chmodCalls.containsKey(path)) {
                fail("chmod called on " + path + " twice");
              }
              chmodCalls.put(path, mode);
              super.chmod(pathFragment, mode);
            }
          });

  private final TimestampGranularityMonitor tsgm =
      new TimestampGranularityMonitor(new ManualClock());

  private final Path execRoot = scratch.resolve("/workspace");
  private final ArtifactRoot sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");

  @Before
  public void createRootDirs() throws Exception {
    sourceRoot.getRoot().asPath().createDirectoryAndParents();
    outputRoot.getRoot().asPath().createDirectoryAndParents();
  }

  private ActionOutputMetadataStore createStore(ImmutableSet<Artifact> outputs) {
    return createStore(outputs, /* actionFs= */ null);
  }

  private ActionOutputMetadataStore createStore(
      ImmutableSet<Artifact> outputs, @Nullable FileSystem actionFs) {
    return ActionOutputMetadataStore.create(
        /* archivedTreeArtifactsEnabled= */ false,
        OutputPermissions.READONLY,
        outputs,
        SyscallCache.NO_CACHE,
        tsgm,
        ArtifactPathResolver.createPathResolver(actionFs, execRoot));
  }

  private RemoteActionFileSystem createRemoteActionFileSystem(
      ActionInputMap inputMap, ImmutableSet<Artifact> outputs) {
    return new RemoteActionFileSystem(
        scratch.getFileSystem(),
        execRoot.asFragment(),
        outputRoot.getExecPathString(),
        inputMap,
        outputs,
        StaticInputMetadataProvider.empty(),
        mock(RemoteActionInputFetcher.class));
  }

  @Test
  public void withNonArtifactInput() throws Exception {
    ActionInput input = ActionInputHelper.fromPath("foo/bar");
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(
            new byte[] {1, 2, 3}, /* proxy= */ null, /* size= */ 10L);
    ActionInputMap map = new ActionInputMap(1);
    map.putWithNoDepOwner(input, metadata);
    assertThat(map.getInputMetadata(input)).isEqualTo(metadata);
    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(execRoot.asFragment(), map, ImmutableMap.of());
    assertThat(inputMetadataProvider.getInputMetadata(input)).isNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void withArtifactInput() throws Exception {
    PathFragment path = PathFragment.create("src/a");
    Artifact artifact = ActionsTestUtil.createArtifactWithRootRelativePath(sourceRoot, path);
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(
            new byte[] {1, 2, 3}, /* proxy= */ null, /* size= */ 10L);
    ActionInputMap map = new ActionInputMap(1);
    map.putWithNoDepOwner(artifact, metadata);
    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(execRoot.asFragment(), map, ImmutableMap.of());
    assertThat(inputMetadataProvider.getInputMetadata(artifact)).isEqualTo(metadata);
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void unknownSourceArtifactPermittedDuringInputDiscovery() throws Exception {
    PathFragment path = PathFragment.create("src/a");
    Artifact artifact = ActionsTestUtil.createArtifactWithRootRelativePath(sourceRoot, path);
    ActionInputMap inputMap = new ActionInputMap(0);
    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(execRoot.asFragment(), inputMap, ImmutableMap.of());
    assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void unknownArtifactPermittedDuringInputDiscovery() throws Exception {
    PathFragment path = PathFragment.create("foo/bar");
    Artifact artifact = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path);
    ActionInputMap inputMap = new ActionInputMap(0);
    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(execRoot.asFragment(), inputMap, ImmutableMap.of());
    assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void withKnownOutputArtifactStatsFile() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "foo/bar");
    scratch.file(artifact.getPath().getPathString(), "not empty");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));
    assertThat(store.getOutputMetadata(artifact)).isNotNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void withMissingOutputArtifactStatsFileFailsWithException() {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "foo/bar");
    assertThat(artifact.getPath().exists()).isFalse();
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));
    assertThrows(FileNotFoundException.class, () -> store.getOutputMetadata(artifact));
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void unknownTreeArtifactPermittedDuringInputDiscovery() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    Artifact artifact = TreeFileArtifact.createTreeOutput(treeArtifact, "baz");
    ActionInputMap inputMap = new ActionInputMap(0);
    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(execRoot.asFragment(), inputMap, ImmutableMap.of());
    assertThat(inputMetadataProvider.getInputMetadata(artifact)).isNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void withUnknownOutputArtifactStatsFileTreeArtifact() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    Artifact artifact = TreeFileArtifact.createTreeOutput(treeArtifact, "baz");
    scratch.file(artifact.getPath().getPathString(), "not empty");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));
    assertThat(store.getOutputMetadata(artifact)).isNotNull();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void createsTreeArtifactValueFromFilesystem() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(treeArtifact, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(treeArtifact, "child2");
    scratch.file(child1.getPath().getPathString(), "child1");
    scratch.file(child2.getPath().getPathString(), "child2");

    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));

    FileArtifactValue treeMetadata = store.getOutputMetadata(treeArtifact);
    FileArtifactValue child1Metadata = store.getOutputMetadata(child1);
    FileArtifactValue child2Metadata = store.getOutputMetadata(child2);
    TreeArtifactValue tree = store.getAllTreeArtifactData().get(treeArtifact);

    assertThat(tree.getMetadata()).isEqualTo(treeMetadata);
    assertThat(tree.getChildValues())
        .containsExactly(child1, child1Metadata, child2, child2Metadata);
    assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree);
    assertThat(store.getAllArtifactData()).isEmpty();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void withDanglingSymlinkInTreeArtifactFailsWithException() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(treeArtifact, "child");
    treeArtifact.getPath().createDirectoryAndParents();
    child.getPath().createSymbolicLink(PathFragment.create("/does_not_exist"));

    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));

    IOException e = assertThrows(IOException.class, () -> store.getOutputMetadata(treeArtifact));
    assertThat(e).hasMessageThat().contains("dangling symbolic link");
  }

  @Test
  public void resettingOutputs() throws Exception {
    PathFragment path = PathFragment.create("foo/bar");
    Artifact artifact = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path);
    Path outputPath = scratch.file(artifact.getPath().getPathString(), "not empty");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));
    store.prepareForActionExecution();

    // The store doesn't have any info. It'll stat the file and discover that it's 10 bytes long.
    assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(10);
    assertThat(chmodCalls).containsExactly(outputPath, 0555);

    // Inject a remote file of size 42.
    store.injectFile(artifact, FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 42, 0));
    assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(42);

    // Reset this output, which will make the store stat the file again.
    store.resetOutputs(ImmutableList.of(artifact));
    chmodCalls.clear();
    assertThat(store.getOutputMetadata(artifact).getSize()).isEqualTo(10);
    // The store should not have chmodded the file as it already has the correct permission.
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void injectRemoteArtifactMetadata() throws Exception {
    PathFragment path = PathFragment.create("foo/bar");
    Artifact artifact = ActionsTestUtil.createArtifactWithRootRelativePath(outputRoot, path);
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));
    store.prepareForActionExecution();

    byte[] digest = new byte[] {1, 2, 3};
    int size = 10;
    store.injectFile(
        artifact, FileArtifactValue.createForRemoteFile(digest, size, /* locationIndex= */ 1));

    FileArtifactValue v = store.getOutputMetadata(artifact);
    assertThat(v).isNotNull();
    assertThat(v.getDigest()).isEqualTo(digest);
    assertThat(v.getSize()).isEqualTo(size);
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void cannotInjectTreeArtifactChildIndividually() {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(treeArtifact, "child");

    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));
    store.prepareForActionExecution();

    FileArtifactValue childValue =
        FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 5, 1);

    assertThrows(IllegalArgumentException.class, () -> store.injectFile(child, childValue));
    assertThat(store.getAllArtifactData()).isEmpty();
    assertThat(store.getAllTreeArtifactData()).isEmpty();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void canInjectTemplateExpansionOutput() {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    TreeFileArtifact output =
        TreeFileArtifact.createTemplateExpansionOutput(
            treeArtifact, "output", ActionsTestUtil.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER);

    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));
    store.prepareForActionExecution();

    FileArtifactValue value = FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 5, 1);
    store.injectFile(output, value);

    assertThat(store.getAllArtifactData()).containsExactly(output, value);
    assertThat(store.getAllTreeArtifactData()).isEmpty();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void injectRemoteTreeArtifactMetadata() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "dir");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));
    store.prepareForActionExecution();

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(treeArtifact)
            .putChild(
                TreeFileArtifact.createTreeOutput(treeArtifact, "foo"),
                FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 5, 1))
            .putChild(
                TreeFileArtifact.createTreeOutput(treeArtifact, "bar"),
                FileArtifactValue.createForRemoteFile(new byte[] {4, 5, 6}, 10, 1))
            .build();

    store.injectTree(treeArtifact, tree);

    FileArtifactValue value = store.getOutputMetadata(treeArtifact);
    assertThat(value).isNotNull();
    assertThat(value.getDigest()).isEqualTo(tree.getDigest());
    assertThat(store.getAllTreeArtifactData().get(treeArtifact)).isEqualTo(tree);
    assertThat(chmodCalls).isEmpty();

    assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree);

    // Make sure that all children are transferred properly into the ActionExecutionValue. If any
    // child is missing, getExistingFileArtifactValue will throw.
    ActionExecutionValue actionExecutionValue =
        ActionExecutionValue.createFromOutputMetadataStore(
            store, FilesetOutputTree.EMPTY, new NullAction());
    tree.getChildren().forEach(actionExecutionValue::getExistingFileArtifactValue);
  }

  @Test
  public void fileArtifactMaterializedAsSymlinkToNonArtifact() throws Exception {
    Path resolvedPath = outputRoot.getRoot().getRelative("resolved");
    Artifact outputArtifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("output"));

    RemoteActionFileSystem actionFs =
        createRemoteActionFileSystem(new ActionInputMap(0), ImmutableSet.of(outputArtifact));

    ActionOutputMetadataStore store = createStore(ImmutableSet.of(outputArtifact), actionFs);
    store.prepareForActionExecution();

    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .getParentDirectory()
        .createDirectoryAndParents();
    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .createSymbolicLink(resolvedPath.asFragment());
    FileSystemUtils.writeContentAsLatin1(resolvedPath, "foo");

    assertThat(store.getOutputMetadata(outputArtifact))
        .isEqualTo(FileArtifactValue.createForTesting(resolvedPath));
  }

  @Test
  public void fileArtifactMaterializedAsSymlinkToFileArtifact(
      @TestParameter ArtifactType artifactType, @TestParameter ResolvedPathDepth depth)
      throws Exception {
    ArtifactRoot root =
        switch (artifactType) {
          case SOURCE -> sourceRoot;
          case OUTPUT -> outputRoot;
        };
    Artifact resolvedArtifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(root, PathFragment.create("resolved"));
    Artifact inputArtifact =
        switch (depth) {
          case SHALLOW -> resolvedArtifact;
          case DEEP ->
              ActionsTestUtil.createArtifactWithRootRelativePath(
                  outputRoot, PathFragment.create("input"));
        };
    Artifact outputArtifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("output"));

    FileArtifactValue inputMetadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, null, 123L);
    if (!resolvedArtifact.equals(inputArtifact)) {
      inputMetadata =
          FileArtifactValue.createFromExistingWithResolvedPath(
              inputMetadata, resolvedArtifact.getPath().asFragment());
    }

    ActionInputMap inputMap = new ActionInputMap(0);
    inputMap.putWithNoDepOwner(inputArtifact, inputMetadata);

    RemoteActionFileSystem actionFs =
        createRemoteActionFileSystem(inputMap, ImmutableSet.of(outputArtifact));

    ActionOutputMetadataStore store = createStore(ImmutableSet.of(outputArtifact), actionFs);
    store.prepareForActionExecution();

    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .getParentDirectory()
        .createDirectoryAndParents();
    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .createSymbolicLink(inputArtifact.getPath().asFragment());

    assertThat(store.getOutputMetadata(outputArtifact))
        .isEqualTo(
            FileArtifactValue.createFromExistingWithResolvedPath(
                inputMetadata, resolvedArtifact.getPath().asFragment()));
  }

  @Test
  public void treeArtifactMaterializedAsSymlinkToNonArtifact() throws Exception {
    Path resolvedPath = outputRoot.getRoot().getRelative("resolved");
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "output");

    RemoteActionFileSystem actionFs =
        createRemoteActionFileSystem(new ActionInputMap(0), ImmutableSet.of(outputArtifact));

    ActionOutputMetadataStore store = createStore(ImmutableSet.of(outputArtifact), actionFs);
    store.prepareForActionExecution();

    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .getParentDirectory()
        .createDirectoryAndParents();
    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .createSymbolicLink(resolvedPath.asFragment());
    actionFs.getPath(resolvedPath.asFragment()).createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(resolvedPath.getRelative("child"), "foo");

    assertThat(store.getTreeArtifactValue(outputArtifact))
        .isEqualTo(
            TreeArtifactValue.newBuilder(outputArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(outputArtifact, "child"),
                    FileArtifactValue.createForTesting(resolvedPath.getRelative("child")))
                .build());
  }

  @Test
  public void treeArtifactMaterializedAsSymlinkToAnotherTreeArtifact(
      @TestParameter ResolvedPathDepth depth) throws Exception {
    SpecialArtifact resolvedArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "resolved");
    SpecialArtifact inputArtifact =
        switch (depth) {
          case SHALLOW -> resolvedArtifact;
          case DEEP -> ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "input");
        };
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "output");

    TreeArtifactValue.Builder builder =
        TreeArtifactValue.newBuilder(inputArtifact)
            .putChild(
                TreeFileArtifact.createTreeOutput(inputArtifact, "child"),
                FileArtifactValue.createForNormalFile(
                    new byte[] {1, 2, 3}, /* proxy= */ null, 123L));
    if (depth == ResolvedPathDepth.DEEP) {
      builder.setResolvedPath(resolvedArtifact.getPath().asFragment());
    }
    TreeArtifactValue inputMetadata = builder.build();

    ActionInputMap inputMap = new ActionInputMap(0);
    inputMap.putTreeArtifact(inputArtifact, inputMetadata, /* depOwner= */ null);

    RemoteActionFileSystem actionFs =
        createRemoteActionFileSystem(inputMap, ImmutableSet.of(outputArtifact));

    ActionOutputMetadataStore store = createStore(ImmutableSet.of(outputArtifact), actionFs);
    store.prepareForActionExecution();

    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .getParentDirectory()
        .createDirectoryAndParents();
    actionFs
        .getPath(outputArtifact.getPath().asFragment())
        .createSymbolicLink(inputArtifact.getPath().asFragment());

    assertThat(store.getTreeArtifactValue(outputArtifact))
        .isEqualTo(
            TreeArtifactValue.newBuilder(outputArtifact)
                .putChild(
                    TreeFileArtifact.createTreeOutput(outputArtifact, "child"),
                    FileArtifactValue.createForNormalFile(
                        new byte[] {1, 2, 3}, /* proxy= */ null, 123L))
                .setResolvedPath(resolvedArtifact.getPath().asFragment())
                .build());
  }

  @Test
  public void getMetadataFromFilesetMapping() throws Exception {
    FileArtifactValue directoryFav = FileArtifactValue.createForDirectoryWithMtime(10L);
    FileArtifactValue regularFav =
        FileArtifactValue.createForVirtualActionInput(new byte[] {1, 2, 3, 4}, 10L);
    HasDigest.ByteStringDigest byteStringDigest = new ByteStringDigest(new byte[] {2, 3, 4});

    ImmutableList<FilesetOutputSymlink> symlinks =
        ImmutableList.of(
            createFilesetOutputSymlink(directoryFav, "dir"),
            createFilesetOutputSymlink(regularFav, "file"),
            createFilesetOutputSymlink(byteStringDigest, "bytes"));

    Artifact artifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("foo/bar"));
    ImmutableMap<Artifact, FilesetOutputTree> expandedFilesets =
        ImmutableMap.of(artifact, FilesetOutputTree.create(symlinks));

    ActionInputMetadataProvider inputMetadataProvider =
        new ActionInputMetadataProvider(
            execRoot.asFragment(), new ActionInputMap(0), expandedFilesets);

    // Only the regular FileArtifactValue should have its metadata stored.
    assertThat(inputMetadataProvider.getInputMetadata(createInput("dir"))).isNull();
    assertThat(inputMetadataProvider.getInputMetadata(createInput("file"))).isEqualTo(regularFav);
    assertThat(inputMetadataProvider.getInputMetadata(createInput("bytes"))).isNull();
    assertThat(inputMetadataProvider.getInputMetadata(createInput("does_not_exist"))).isNull();
    assertThat(chmodCalls).isEmpty();
  }

  private FilesetOutputSymlink createFilesetOutputSymlink(HasDigest digest, String identifier) {
    return FilesetOutputSymlink.create(
        PathFragment.create(identifier + "_symlink"),
        execRoot.getRelative(identifier).asFragment(),
        digest,
        execRoot.asFragment(),
        /* enclosingTreeArtifact= */ null);
  }

  private ActionInput createInput(String identifier) {
    return ActionInputHelper.fromPath(execRoot.getRelative(identifier).getPathString());
  }

  @Test
  public void omitRegularArtifact() {
    Artifact omitted =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("omitted"));
    Artifact consumed =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("consumed"));
    ActionOutputMetadataStore store =
        createStore(/* outputs= */ ImmutableSet.of(omitted, consumed));

    store.prepareForActionExecution();
    store.markOmitted(omitted);

    assertThat(store.artifactOmitted(omitted)).isTrue();
    assertThat(store.artifactOmitted(consumed)).isFalse();
    assertThat(store.getAllTreeArtifactData()).isEmpty();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void omitTreeArtifact() {
    SpecialArtifact omittedTree =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            outputRoot, PathFragment.create("omitted"));
    SpecialArtifact consumedTree =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            outputRoot, PathFragment.create("consumed"));
    ActionOutputMetadataStore store =
        createStore(/* outputs= */ ImmutableSet.of(omittedTree, consumedTree));

    store.prepareForActionExecution();
    store.markOmitted(omittedTree);
    store.markOmitted(omittedTree); // Marking a tree artifact as omitted twice is tolerated.

    assertThat(store.artifactOmitted(omittedTree)).isTrue();
    assertThat(store.artifactOmitted(consumedTree)).isFalse();
    assertThat(store.getAllArtifactData()).isEmpty();
    assertThat(chmodCalls).isEmpty();
  }

  @Test
  public void outputArtifactNotPreviouslyInjectedInExecutionMode() throws Exception {
    Artifact output =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("dir/file.out"));
    Path outputPath = scratch.file(output.getPath().getPathString(), "contents");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(output));
    store.prepareForActionExecution();

    FileArtifactValue metadata = store.getOutputMetadata(output);

    assertThat(metadata.getDigest()).isEqualTo(outputPath.getDigest());
    assertThat(store.getAllArtifactData()).containsExactly(output, metadata);
    assertThat(store.getAllTreeArtifactData()).isEmpty();
    assertThat(chmodCalls).containsExactly(outputPath, 0555);
  }

  @Test
  public void outputArtifactNotPreviouslyInjectedInExecutionMode_writablePermissions()
      throws Exception {
    Artifact output =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("dir/file.out"));
    Path outputPath = scratch.file(output.getPath().getPathString(), "contents");
    ActionOutputMetadataStore store =
        ActionOutputMetadataStore.create(
            /* archivedTreeArtifactsEnabled= */ false,
            OutputPermissions.WRITABLE,
            /* outputs= */ ImmutableSet.of(output),
            SyscallCache.NO_CACHE,
            tsgm,
            ArtifactPathResolver.IDENTITY);
    store.prepareForActionExecution();

    FileArtifactValue metadata = store.getOutputMetadata(output);

    assertThat(metadata.getDigest()).isEqualTo(outputPath.getDigest());
    assertThat(store.getAllArtifactData()).containsExactly(output, metadata);
    assertThat(store.getAllTreeArtifactData()).isEmpty();
    // Permissions preserved in store, so chmod calls should be empty.
    assertThat(chmodCalls).containsExactly(outputPath, 0755);
  }

  @Test
  public void outputTreeArtifactNotPreviouslyInjectedInExecutionMode() throws Exception {
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "foo/bar");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(treeArtifact, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/child2");
    Path child1Path = scratch.file(child1.getPath().getPathString(), "contents1");
    Path child2Path = scratch.file(child2.getPath().getPathString(), "contents2");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(treeArtifact));
    store.prepareForActionExecution();

    FileArtifactValue treeMetadata = store.getOutputMetadata(treeArtifact);
    FileArtifactValue child1Metadata = store.getOutputMetadata(child1);
    FileArtifactValue child2Metadata = store.getOutputMetadata(child2);
    TreeArtifactValue tree = store.getAllTreeArtifactData().get(treeArtifact);

    assertThat(tree.getMetadata()).isEqualTo(treeMetadata);
    assertThat(tree.getChildValues())
        .containsExactly(child1, child1Metadata, child2, child2Metadata);
    assertThat(store.getTreeArtifactValue(treeArtifact)).isEqualTo(tree);
    assertThat(store.getAllArtifactData()).isEmpty();
    assertThat(chmodCalls)
        .containsExactly(
            treeArtifact.getPath(),
            0555,
            child1Path,
            0555,
            child2Path,
            0555,
            child2Path.getParentDirectory(),
            0555);
  }

  @Test
  public void enteringExecutionModeClearsCachedOutputs() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("output"));
    SpecialArtifact treeArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(treeArtifact, "child");
    scratch.file(artifact.getPath().getPathString(), "1");
    scratch.file(child.getPath().getPathString(), "1");
    ActionOutputMetadataStore store =
        createStore(/* outputs= */ ImmutableSet.of(artifact, treeArtifact));

    FileArtifactValue artifactMetadata1 = store.getOutputMetadata(artifact);
    FileArtifactValue treeArtifactMetadata1 = store.getOutputMetadata(treeArtifact);
    assertThat(artifactMetadata1).isNotNull();
    assertThat(artifactMetadata1).isNotNull();
    assertThat(store.getAllArtifactData().keySet()).containsExactly(artifact);
    assertThat(store.getAllTreeArtifactData().keySet()).containsExactly(treeArtifact);

    // Entering execution mode should clear the cached outputs.
    store.prepareForActionExecution();
    assertThat(store.getAllArtifactData()).isEmpty();
    assertThat(store.getAllTreeArtifactData()).isEmpty();

    // Updated metadata should be read from the filesystem.
    scratch.overwriteFile(artifact.getPath().getPathString(), "2");
    scratch.overwriteFile(child.getPath().getPathString(), "2");
    FileArtifactValue artifactMetadata2 = store.getOutputMetadata(artifact);
    FileArtifactValue treeArtifactMetadata2 = store.getOutputMetadata(treeArtifact);
    assertThat(artifactMetadata2).isNotNull();
    assertThat(treeArtifactMetadata2).isNotNull();
    assertThat(artifactMetadata2).isNotEqualTo(artifactMetadata1);
    assertThat(treeArtifactMetadata2).isNotEqualTo(treeArtifactMetadata1);
  }

  @Test
  public void cannotEnterExecutionModeTwice() {
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of());
    store.prepareForActionExecution();
    assertThrows(IllegalStateException.class, store::prepareForActionExecution);
  }

  @Test
  public void fileArtifactValueFromArtifactCompatibleWithGetMetadata_changed() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("output"));
    scratch.file(artifact.getPath().getPathString(), "1");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));

    FileArtifactValue getMetadataResult = store.getOutputMetadata(artifact);
    assertThat(getMetadataResult).isNotNull();

    scratch.overwriteFile(artifact.getPath().getPathString(), "2");
    FileArtifactValue fileArtifactValueFromArtifactResult =
        ActionOutputMetadataStore.fileArtifactValueFromArtifact(
            artifact, /* statNoFollow= */ null, SyscallCache.NO_CACHE, /* tsgm= */ null);
    assertThat(fileArtifactValueFromArtifactResult).isNotNull();

    assertThat(fileArtifactValueFromArtifactResult.couldBeModifiedSince(getMetadataResult))
        .isTrue();
  }

  @Test
  public void fileArtifactValueFromArtifactCompatibleWithGetMetadata_notChanged() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("output"));
    scratch.file(artifact.getPath().getPathString(), "contents");
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(artifact));

    FileArtifactValue getMetadataResult = store.getOutputMetadata(artifact);
    assertThat(getMetadataResult).isNotNull();

    FileArtifactValue fileArtifactValueFromArtifactResult =
        ActionOutputMetadataStore.fileArtifactValueFromArtifact(
            artifact, /* statNoFollow= */ null, SyscallCache.NO_CACHE, /* tsgm= */ null);
    assertThat(fileArtifactValueFromArtifactResult).isNotNull();

    assertThat(fileArtifactValueFromArtifactResult.couldBeModifiedSince(getMetadataResult))
        .isFalse();
  }

  @Test
  public void fileArtifactValueForSymlink_readFromCache() throws Exception {
    DigestUtils.configureCache(1);
    Artifact target =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("target"));
    scratch.file(target.getPath().getPathString(), "contents");
    Artifact symlink =
        ActionsTestUtil.createArtifactWithRootRelativePath(
            outputRoot, PathFragment.create("symlink"));
    scratch
        .getFileSystem()
        .getPath(symlink.getPath().getPathString())
        .createSymbolicLink(scratch.getFileSystem().getPath(target.getPath().getPathString()));
    ActionOutputMetadataStore store = createStore(/* outputs= */ ImmutableSet.of(target, symlink));
    var targetMetadata = store.getOutputMetadata(target);
    assertThat(DigestUtils.getCacheStats().hitCount()).isEqualTo(0);

    var symlinkMetadata = store.getOutputMetadata(symlink);

    assertThat(symlinkMetadata).isEqualTo(targetMetadata);
    assertThat(DigestUtils.getCacheStats().hitCount()).isEqualTo(1);
  }
}
