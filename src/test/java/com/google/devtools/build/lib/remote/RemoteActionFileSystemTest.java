// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testing.vfs.SpiedFileSystem;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;

/** Tests for {@link RemoteActionFileSystem} */
@RunWith(TestParameterInjector.class)
public final class RemoteActionFileSystemTest extends RemoteActionFileSystemTestBase {
  private static final RemoteOutputChecker DUMMY_REMOTE_OUTPUT_CHECKER =
      new RemoteOutputChecker(
          new JavaClock(), "build", RemoteOutputsMode.MINIMAL, ImmutableList.of());

  private static final String RELATIVE_OUTPUT_PATH = "out";

  private final RemoteActionInputFetcher inputFetcher = mock(RemoteActionInputFetcher.class);
  private final SpiedFileSystem fs = SpiedFileSystem.createInMemorySpy();
  private final Path execRoot = fs.getPath("/exec");
  private final ArtifactRoot sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, RELATIVE_OUTPUT_PATH);

  enum FilesystemTestParam {
    LOCAL,
    REMOTE;

    FileSystem getFilesystem(RemoteActionFileSystem actionFs) {
      switch (this) {
        case LOCAL:
          return actionFs.getLocalFileSystem();
        case REMOTE:
          return actionFs.getRemoteOutputTree();
      }
      throw new IllegalStateException();
    }
  };

  @Before
  public void setUp() throws IOException {
    outputRoot.getRoot().asPath().createDirectoryAndParents();
  }

  @Override
  protected RemoteActionFileSystem createActionFileSystem(
      ActionInputMap inputs, Iterable<Artifact> outputs, InputMetadataProvider fileCache)
      throws IOException {
    doReturn(DUMMY_REMOTE_OUTPUT_CHECKER).when(inputFetcher).getRemoteOutputChecker();
    RemoteActionFileSystem remoteActionFileSystem =
        new RemoteActionFileSystem(
            fs,
            execRoot.asFragment(),
            RELATIVE_OUTPUT_PATH,
            inputs,
            outputs,
            fileCache,
            inputFetcher);
    remoteActionFileSystem.updateContext(mock(ActionExecutionMetadata.class));
    remoteActionFileSystem.createDirectoryAndParents(outputRoot.getRoot().asPath().asFragment());
    return remoteActionFileSystem;
  }

  @Override
  protected FileSystem getLocalFileSystem(FileSystem actionFs) {
    return ((RemoteActionFileSystem) actionFs).getLocalFileSystem();
  }

  @Override
  protected FileSystem getRemoteFileSystem(FileSystem actionFs) {
    return ((RemoteActionFileSystem) actionFs).getRemoteOutputTree();
  }

  @Override
  protected PathFragment getOutputPath(String outputRootRelativePath) {
    return outputRoot.getRoot().asPath().getRelative(outputRootRelativePath).asFragment();
  }

  private static Answer<ListenableFuture<Void>> mockPrefetchFile(Path path, String contents) {
    return invocationOnMock -> {
      FileSystemUtils.writeContent(path, UTF_8, contents);
      return immediateVoidFuture();
    };
  }

  @Test
  public void testGetInputStream_fromInputArtifactData_forLocalArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    FileSystem actionFs = createActionFileSystem(inputs);

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void testGetInputStream_fromInputArtifactData_forRemoteArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    FileSystem actionFs = createActionFileSystem(inputs);
    doAnswer(mockPrefetchFile(artifact.getPath(), "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void testGetInputStream_fromRemoteOutputTree_forDeclaredOutput() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileSystem actionFs = createActionFileSystem(new ActionInputMap(0), ImmutableList.of(artifact));
    injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents");
    doAnswer(mockPrefetchFile(artifact.getPath(), "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(artifact)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void testGetInputStream_fromRemoteOutputTree_forUndeclaredOutput() throws Exception {
    // arrange
    Path path = outputRoot.getRoot().getRelative("out");
    ActionInput input = ActionInputHelper.fromPath(path.relativeTo(execRoot));
    FileSystem actionFs = createActionFileSystem();
    injectRemoteFile(actionFs, path.asFragment(), "remote contents");
    doAnswer(mockPrefetchFile(path, "remote contents"))
        .when(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(input)), any(), eq(Priority.CRITICAL));

    // act
    Path actionFsPath = actionFs.getPath(path.asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("remote contents");
    verify(inputFetcher)
        .prefetchFiles(any(), eq(ImmutableList.of(input)), any(), eq(Priority.CRITICAL));
    verifyNoMoreInteractions(inputFetcher);
  }

  @Test
  public void getInputStream_fromLocalFilesystem_forSourceFile() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(sourceRoot, "src");
    FileSystem actionFs = createActionFileSystem();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void getInputStream_fromLocalFilesystem_forOutputFile() throws Exception {
    // arrange
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileSystem actionFs = createActionFileSystem();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    // act
    Path actionFsPath = actionFs.getPath(artifact.getPath().asFragment());
    String contents = FileSystemUtils.readContent(actionFsPath, UTF_8);

    // assert
    assertThat(actionFsPath.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(contents).isEqualTo("local contents");
  }

  @Test
  public void getInput_fromInputArtifactData_forLocalArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromInputArtifactData_forRemoteArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromOutputMapping() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    RemoteActionFileSystem actionFs =
        (RemoteActionFileSystem)
            createActionFileSystem(new ActionInputMap(0), ImmutableList.of(artifact));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromFileCache_forSourceFile() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(sourceRoot, "src");
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, /* proxy= */ null, 42);
    RemoteActionFileSystem actionFs =
        createActionFileSystem(
            new ActionInputMap(0),
            ImmutableList.of(),
            new StaticInputMetadataProvider(ImmutableMap.of(artifact, metadata)));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isEqualTo(artifact);
  }

  @Test
  public void getInput_fromFileCache_notForOutputFile() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    FileArtifactValue metadata =
        FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, /* proxy= */ null, 42);
    RemoteActionFileSystem actionFs =
        createActionFileSystem(
            new ActionInputMap(0),
            ImmutableList.of(),
            new StaticInputMetadataProvider(ImmutableMap.of(artifact, metadata)));

    assertThat(actionFs.getInput(artifact.getExecPathString())).isNull();
  }

  @Test
  public void getInput_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();

    assertThat(actionFs.getInput("some-path")).isNull();
  }

  @Test
  public void getMetadata_fromInputArtifactData_forLocalArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    FileArtifactValue metadata = checkNotNull(inputs.getInputMetadata(artifact));
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInputMetadata(artifact)).isEqualTo(metadata);
  }

  @Test
  public void getMetadata_fromInputArtifactData_forRemoteArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    FileArtifactValue metadata = checkNotNull(inputs.getInputMetadata(artifact));
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.getInputMetadata(artifact)).isEqualTo(metadata);
  }

  @Test
  public void getMetadata_notFound() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();

    assertThat(actionFs.getInputMetadata(artifact)).isNull();
  }

  @Test
  public void statAndExists_fromInputArtifactData_file() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createLocalArtifact("local-file", "local contents", inputs);
    PathFragment path = artifact.getPath().asFragment();
    FileArtifactValue metadata = checkNotNull(inputs.getInputMetadata(artifact));
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.exists(path, /* followSymlinks= */ true)).isTrue();

    FileStatus st = actionFs.stat(path, /* followSymlinks= */ true);
    assertThat(st.isFile()).isTrue();
    assertThat(st).isInstanceOf(FileStatusWithDigest.class);
    assertThat(((FileStatusWithDigest) st).getDigest()).isEqualTo(metadata.getDigest());
  }

  @Test
  public void statAndExists_fromInputArtifactData_treeSubDir() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    SpecialArtifact tree =
        createLocalTreeArtifact("tree", ImmutableMap.of("subdir/file", ""), inputs);
    PathFragment path = tree.getPath().getChild("subdir").asFragment();
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThat(actionFs.exists(path, /* followSymlinks= */ true)).isTrue();

    FileStatus st = actionFs.stat(path, /* followSymlinks= */ true);
    assertThat(st.isDirectory()).isTrue();
  }

  @Test
  public void statAndExists_fromRemoteOutputTree() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    FileArtifactValue metadata =
        injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents");

    assertThat(actionFs.exists(path, /* followSymlinks= */ true)).isTrue();

    FileStatus st = actionFs.stat(path, /* followSymlinks= */ true);
    assertThat(st.isFile()).isTrue();
    assertThat(st).isInstanceOf(FileStatusWithDigest.class);
    assertThat(((FileStatusWithDigest) st).getDigest()).isEqualTo(metadata.getDigest());
  }

  @Test
  public void statAndExists_fromLocalFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    assertThat(actionFs.exists(path)).isTrue();

    FileStatus st = actionFs.stat(path, /* followSymlinks= */ true);
    assertThat(st.isFile()).isTrue();
    assertThat(st.getSize()).isEqualTo("local contents".getBytes(UTF_8).length);
  }

  @Test
  public void statAndExists_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");
    fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());

    assertThat(actionFs.exists(linkPath, /* followSymlinks= */ false)).isTrue();
    assertThat(actionFs.exists(linkPath, /* followSymlinks= */ true)).isFalse();
    assertThat(actionFs.stat(linkPath, /* followSymlinks= */ false).isSymbolicLink()).isTrue();
    assertThrows(
        FileNotFoundException.class, () -> actionFs.stat(linkPath, /* followSymlinks= */ true));

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, targetPath, "content");
    } else {
      injectRemoteFile(actionFs, targetPath, "content");
    }

    assertThat(actionFs.exists(linkPath, /* followSymlinks= */ false)).isTrue();
    assertThat(actionFs.stat(linkPath, /* followSymlinks= */ false).isSymbolicLink()).isTrue();
    assertThat(actionFs.exists(linkPath, /* followSymlinks= */ true)).isTrue();
    assertThat(actionFs.stat(linkPath, /* followSymlinks= */ true).isFile()).isTrue();
  }

  @Test
  public void statAndExists_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment path = getOutputPath("does_not_exist");

    assertThat(actionFs.exists(path)).isFalse();

    assertThat(actionFs.statIfFound(path, /* followSymlinks= */ true)).isNull();

    assertThrows(
        FileNotFoundException.class, () -> actionFs.stat(path, /* followSymlinks= */ true));
  }

  @Test
  public void statAndExists_isNotDirectory() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment nonDirPath = getOutputPath("non_dir");
    PathFragment path = nonDirPath.getChild("file");

    writeLocalFile(actionFs, nonDirPath, "content");

    assertThat(actionFs.exists(path)).isFalse();

    assertThat(actionFs.statIfFound(path, /* followSymlinks= */ true)).isNull();

    assertThrows(
        FileNotFoundException.class, () -> actionFs.stat(path, /* followSymlinks= */ true));
  }

  @Test
  public void statAndExists_danglingSymlink_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment path = getOutputPath("sym");

    actionFs.getPath(path).createSymbolicLink(PathFragment.create("/does_not_exist"));

    assertThat(actionFs.exists(path)).isFalse();

    assertThat(actionFs.statIfFound(path, /* followSymlinks= */ true)).isNull();

    assertThrows(
        FileNotFoundException.class, () -> actionFs.stat(path, /* followSymlinks= */ true));
  }

  @Test
  public void delete_deleteSymlink() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();

    PathFragment linkPath = getOutputPath("link");
    PathFragment targetPath = getOutputPath("target");
    actionFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());
    writeLocalFile(actionFs, targetPath, "content");

    assertThat(actionFs.delete(linkPath)).isTrue();
    assertThat(actionFs.exists(linkPath, /* followSymlinks= */ false)).isFalse();
    assertThat(actionFs.exists(targetPath, /* followSymlinks= */ false)).isTrue();
  }

  @Test
  public void delete_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment dirLinkPath = getOutputPath("dirLink");
    PathFragment dirTargetPath = getOutputPath("dirTarget");
    fromFs
        .getPath(dirLinkPath)
        .createSymbolicLink(execRoot.getRelative(dirTargetPath).asFragment());
    actionFs.getPath(dirTargetPath).createDirectoryAndParents();

    PathFragment naivePath = dirLinkPath.getChild("file");
    PathFragment canonicalPath = dirTargetPath.getChild("file");

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, canonicalPath, "content");
    } else {
      injectRemoteFile(actionFs, canonicalPath, "content");
    }

    assertThat(actionFs.delete(naivePath)).isTrue();
    assertThat(actionFs.exists(naivePath, /* followSymlinks= */ false)).isFalse();
    assertThat(actionFs.exists(canonicalPath, /* followSymlinks= */ false)).isFalse();
  }

  @Test
  public void delete_invalidatesResolveSymbolicLinksCache() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");

    actionFs.getPath(linkPath).getParentDirectory().createDirectoryAndParents();
    actionFs.getPath(linkPath).createSymbolicLink(targetPath);
    writeLocalFile(actionFs, targetPath, "content");

    assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(targetPath));

    assertThat(actionFs.delete(linkPath)).isTrue();
    writeLocalFile(actionFs, linkPath, "content");

    assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(linkPath));
  }

  @Test
  public void setLastModifiedTime_forRemoteOutputTree() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents");

    actionFs.getPath(path).setLastModifiedTime(1234567890);
    assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(1234567890);
  }

  @Test
  public void setLastModifiedTime_forLocalFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    actionFs.getPath(path).setLastModifiedTime(1234567890);
    assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(1234567890);
  }

  @Test
  public void setLastModifiedTime_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");
    fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, targetPath, "content");
    } else {
      injectRemoteFile(actionFs, targetPath, "content");
    }

    actionFs.getPath(linkPath).setLastModifiedTime(1234567890);
    assertThat(actionFs.getPath(linkPath).getLastModifiedTime()).isEqualTo(1234567890);
    assertThat(actionFs.getPath(targetPath).getLastModifiedTime()).isEqualTo(1234567890);
  }

  @Test
  public void getDigest_fromInputArtifactData_forLocalArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("file", "local contents", inputs);
    PathFragment path = artifact.getPath().asFragment();
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    // Verify that we don't fall back to a slow digest.
    reset(fs);
    assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("local contents"));
    verify(fs, never()).getDigest(any());

    assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("local contents"));
  }

  @Test
  public void getDigest_fromInputArtifactData_forRemoteArtifact() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("file", "remote contents", inputs);
    PathFragment path = artifact.getPath().asFragment();
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    // Verify that we don't fall back to a slow digest.
    reset(fs);
    assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("remote contents"));
    verify(fs, never()).getDigest(any());

    assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("remote contents"));
  }

  @Test
  public void getDigest_fromRemoteOutputTree() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    injectRemoteFile(actionFs, artifact.getPath().asFragment(), "remote contents");

    assertThat(actionFs.getFastDigest(path)).isEqualTo(getDigest("remote contents"));
    assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("remote contents"));
  }

  @Test
  public void getDigest_fromLocalFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();
    writeLocalFile(actionFs, artifact.getPath().asFragment(), "local contents");

    assertThat(actionFs.getFastDigest(path)).isNull();
    assertThat(actionFs.getDigest(path)).isEqualTo(getDigest("local contents"));
  }

  @Test
  public void getDigest_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    PathFragment path = artifact.getPath().asFragment();

    assertThrows(FileNotFoundException.class, () -> actionFs.getFastDigest(path));
    assertThrows(FileNotFoundException.class, () -> actionFs.getDigest(path));
  }

  @Test
  public void getDigest_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");
    fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, targetPath, "content");
      assertThat(actionFs.getFastDigest(linkPath)).isNull();
    } else {
      injectRemoteFile(actionFs, targetPath, "content");
      assertThat(actionFs.getFastDigest(linkPath)).isEqualTo(getDigest("content"));
    }

    assertThat(actionFs.getDigest(linkPath)).isEqualTo(getDigest("content"));
  }

  @Test
  public void readdir_fromRemoteOutputTree() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    injectRemoteFile(actionFs, getOutputPath("dir/out1"), "contents1");
    injectRemoteFile(actionFs, getOutputPath("dir/out2"), "contents2");
    injectRemoteFile(actionFs, getOutputPath("dir/subdir/out3"), "contents3");
    PathFragment dirPath = getOutputPath("dir");

    assertReaddir(
        actionFs,
        dirPath,
        /* followSymlinks= */ true,
        new Dirent("out1", Dirent.Type.FILE),
        new Dirent("out2", Dirent.Type.FILE),
        new Dirent("subdir", Dirent.Type.DIRECTORY));

    assertReaddirThrows(actionFs, getOutputPath("dir/out1"), /* followSymlinks= */ true);
  }

  @Test
  public void readdir_fromLocalFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    writeLocalFile(actionFs, getOutputPath("dir/file"), "contents");
    writeLocalFile(actionFs, getOutputPath("dir/subdir/file"), "contents");
    PathFragment dirPath = getOutputPath("dir");

    assertReaddir(
        actionFs,
        dirPath,
        /* followSymlinks= */ true,
        new Dirent("file", Dirent.Type.FILE),
        new Dirent("subdir", Dirent.Type.DIRECTORY));

    assertReaddirThrows(actionFs, getOutputPath("dir/out1"), /* followSymlinks= */ true);
  }

  @Test
  public void readdir_fromInputArtifactData() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    createLocalTreeArtifact("tree", ImmutableMap.of("file", "", "dir/subdir/subfile", ""), inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertReaddir(
        actionFs,
        getOutputPath("tree"),
        /* followSymlinks= */ true,
        new Dirent("dir", Dirent.Type.DIRECTORY),
        new Dirent("file", Dirent.Type.FILE));

    assertReaddir(
        actionFs,
        getOutputPath("tree/dir"),
        /* followSymlinks= */ true,
        new Dirent("subdir", Dirent.Type.DIRECTORY));

    assertReaddir(
        actionFs,
        getOutputPath("tree/dir/subdir"),
        /* followSymlinks= */ true,
        new Dirent("subfile", Dirent.Type.FILE));

    assertReaddirThrows(
        actionFs, getOutputPath("tree/dir/subdir/subfile"), /* followSymlinks= */ true);
  }

  @Test
  public void readdir_fromInputArtifactData_emptyDir() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    createLocalTreeArtifact("tree", ImmutableMap.of(), inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertReaddir(actionFs, getOutputPath("tree"), /* followSymlinks= */ true);
  }

  @Test
  public void readdir_fromRemoteOutputTreeAndLocalFilesystem() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);
    writeLocalFile(actionFs, getOutputPath("dir/out1"), "contents1");
    injectRemoteFile(actionFs, getOutputPath("dir/out2"), "contents2");
    PathFragment dirPath = getOutputPath("dir");

    assertReaddir(
        actionFs,
        dirPath,
        /* followSymlinks= */ true,
        new Dirent("out1", Dirent.Type.FILE),
        new Dirent("out2", Dirent.Type.FILE));
  }

  @Test
  public void readdir_fromRemoteOutputTreeAndLocalFilesystem_emptyDir() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);
    PathFragment dirPath = getOutputPath("dir");
    actionFs.getRemoteOutputTree().getPath(dirPath).createDirectoryAndParents();
    actionFs.getLocalFileSystem().getPath(dirPath).createDirectoryAndParents();

    assertReaddir(actionFs, dirPath, /* followSymlinks= */ true);
  }

  @Test
  public void readdir_followSymlinks_forDirectory(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("dir");
    PathFragment childPath = getOutputPath("dir/child");

    fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());
    toFs.getPath(targetPath).createDirectory();

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, childPath, "content");
    } else {
      injectRemoteFile(actionFs, childPath, "content");
    }

    assertReaddir(
        actionFs, linkPath, /* followSymlinks= */ false, new Dirent("child", Dirent.Type.FILE));
    assertReaddir(
        actionFs, linkPath, /* followSymlinks= */ true, new Dirent("child", Dirent.Type.FILE));
  }

  @Test
  public void readdir_followSymlinks_forDirectoryEntries(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment dirPath = getOutputPath("dir");
    PathFragment fileLinkPath = getOutputPath("dir/file_sym");
    PathFragment fileTargetPath = getOutputPath("file_target");
    PathFragment dirLinkPath = getOutputPath("dir/dir_sym");
    PathFragment dirTargetPath = getOutputPath("dir_target");
    PathFragment loopingLinkPath = getOutputPath("dir/looping_sym");
    PathFragment danglingLinkPath = getOutputPath("dir/dangling_sym");

    fromFs.getPath(dirPath).createDirectory();
    fromFs
        .getPath(fileLinkPath)
        .createSymbolicLink(execRoot.getRelative(fileTargetPath).asFragment());
    fromFs
        .getPath(dirLinkPath)
        .createSymbolicLink(execRoot.getRelative(dirTargetPath).asFragment());
    fromFs
        .getPath(loopingLinkPath)
        .createSymbolicLink(execRoot.getRelative(loopingLinkPath).asFragment());
    fromFs.getPath(danglingLinkPath).createSymbolicLink(PathFragment.create("/does_not_exist"));

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, fileTargetPath, "content");
      actionFs.getLocalFileSystem().getPath(dirTargetPath).createDirectoryAndParents();
    } else {
      injectRemoteFile(actionFs, fileTargetPath, "content");
      actionFs.getRemoteOutputTree().createDirectoryAndParents(dirTargetPath);
    }

    assertReaddir(
        actionFs,
        dirPath,
        /* followSymlinks= */ false,
        new Dirent("file_sym", Dirent.Type.SYMLINK),
        new Dirent("dir_sym", Dirent.Type.SYMLINK),
        new Dirent("looping_sym", Dirent.Type.SYMLINK),
        new Dirent("dangling_sym", Dirent.Type.SYMLINK));
    assertReaddir(
        actionFs,
        dirPath,
        /* followSymlinks= */ true,
        new Dirent("file_sym", Dirent.Type.FILE),
        new Dirent("dir_sym", Dirent.Type.DIRECTORY),
        new Dirent("looping_sym", Dirent.Type.UNKNOWN),
        new Dirent("dangling_sym", Dirent.Type.UNKNOWN));
  }

  @Test
  public void readdir_nonDirectory() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "dir/out");
    PathFragment path = artifact.getPath().getParentDirectory().asFragment();

    writeLocalFile(actionFs, path, "content");

    assertReaddirThrows(actionFs, path, /* followSymlinks= */ true);
  }

  @Test
  public void readdir_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    Artifact artifact = ActionsTestUtil.createArtifact(outputRoot, "dir/out");
    PathFragment path = artifact.getPath().getParentDirectory().asFragment();

    assertReaddirThrows(actionFs, path, /* followSymlinks= */ true);
  }

  private void assertReaddir(
      RemoteActionFileSystem actionFs,
      PathFragment dirPath,
      boolean followSymlinks,
      Dirent... expected)
      throws Exception {
    assertThat(actionFs.readdir(dirPath, followSymlinks)).containsExactlyElementsIn(expected);
    assertThat(actionFs.getDirectoryEntries(dirPath))
        .containsExactlyElementsIn(
            stream(expected).map(Dirent::getName).collect(toImmutableList()));
  }

  private void assertReaddirThrows(
      RemoteActionFileSystem actionFs, PathFragment dirPath, boolean followSymlinks)
      throws Exception {
    assertThrows(IOException.class, () -> actionFs.readdir(dirPath, followSymlinks));
    assertThrows(IOException.class, () -> actionFs.getDirectoryEntries(dirPath));
  }

  @Test
  public void permissions_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");
    fromFs.getPath(linkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());

    assertThrows(FileNotFoundException.class, () -> actionFs.chmod(linkPath, 0777));

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, targetPath, "content");
    } else {
      injectRemoteFile(actionFs, targetPath, "content");
    }

    // For a remote file, permissions are always 0777.
    boolean isRemote = toFs.equals(actionFs.getRemoteOutputTree());

    assertThat(actionFs.getPath(linkPath).isReadable()).isTrue();
    assertThat(actionFs.getPath(linkPath).isWritable()).isTrue();
    assertThat(actionFs.getPath(linkPath).isExecutable()).isEqualTo(isRemote);

    actionFs.getPath(linkPath).chmod(0111);
    assertThat(actionFs.getPath(linkPath).isReadable()).isEqualTo(isRemote);
    assertThat(actionFs.getPath(linkPath).isWritable()).isEqualTo(isRemote);
    assertThat(actionFs.getPath(linkPath).isExecutable()).isTrue();

    actionFs.getPath(linkPath).setReadable(true);
    actionFs.getPath(linkPath).setWritable(true);
    actionFs.getPath(linkPath).setExecutable(false);
    assertThat(actionFs.getPath(linkPath).isReadable()).isTrue();
    assertThat(actionFs.getPath(linkPath).isWritable()).isTrue();
    assertThat(actionFs.getPath(linkPath).isExecutable()).isEqualTo(isRemote);
  }

  @Test
  public void readSymbolicLink_fromLocalFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment filePath = getOutputPath("file");
    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = PathFragment.create("/some/path");
    actionFs.getLocalFileSystem().getPath(linkPath).createSymbolicLink(targetPath);
    writeLocalFile(actionFs, filePath, "contents");

    assertThat(actionFs.readSymbolicLink(linkPath)).isEqualTo(targetPath);

    assertThrows(NotASymlinkException.class, () -> actionFs.readSymbolicLink(filePath));
  }

  @Test
  public void readSymbolicLink_fromRemoteFilesystem() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment filePath = getOutputPath("file");
    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = PathFragment.create("/some/path");
    actionFs.getRemoteOutputTree().getPath(linkPath).createSymbolicLink(targetPath);
    injectRemoteFile(actionFs, filePath, "contents");

    assertThat(actionFs.readSymbolicLink(linkPath)).isEqualTo(targetPath);

    assertThrows(NotASymlinkException.class, () -> actionFs.readSymbolicLink(filePath));
  }

  @Test
  public void readSymbolicLink_fromInputArtifactData_regularFile() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact artifact = createRemoteArtifact("file", "contents", inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThrows(
        NotASymlinkException.class,
        () -> actionFs.readSymbolicLink(artifact.getPath().asFragment()));
  }

  @Test
  public void readSymbolicLink_fromInputArtifactData_treeSubDir() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    SpecialArtifact tree =
        createRemoteTreeArtifact("tree", ImmutableMap.of("subdir/file", ""), inputs);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    assertThrows(
        NotASymlinkException.class, () -> actionFs.readSymbolicLink(tree.getPath().asFragment()));

    assertThrows(
        NotASymlinkException.class,
        () -> actionFs.readSymbolicLink(tree.getPath().getRelative("subdir").asFragment()));
  }

  @Test
  public void readSymbolicLink_fromInputArtifactData_unresolvedSymlink() throws Exception {
    ActionInputMap inputs = new ActionInputMap(1);
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem(inputs);

    Artifact symlink = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "symlink");
    PathFragment targetPath = PathFragment.create("/some/path");
    // Create symlink on the filesystem so we can digest it, then delete it to verify that its
    // presence in the ActionInputMap is sufficient for readSymbolicLink to work. Note that this is
    // an unrealistic scenario, as symlinks are always materialized even when produced remotely.
    Path symlinkPath = getLocalFileSystem(actionFs).getPath(symlink.getPath().getPathString());
    symlinkPath.createSymbolicLink(targetPath);
    inputs.putWithNoDepOwner(symlink, FileArtifactValue.createForUnresolvedSymlink(symlinkPath));
    symlinkPath.delete();

    assertThat(actionFs.readSymbolicLink(getOutputPath("symlink"))).isEqualTo(targetPath);
  }

  @Test
  public void readSymbolicLink_notFound() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment linkPath = getOutputPath("sym");

    assertThrows(FileNotFoundException.class, () -> actionFs.readSymbolicLink(linkPath));
  }

  @Test
  public void createSymbolicLink_localFileArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact localArtifact = createLocalArtifact("local-file", "local contents", inputs);
    Artifact outputArtifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = localArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
  }

  @Test
  public void createSymbolicLink_remoteFileArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    Artifact remoteArtifact = createRemoteArtifact("remote-file", "remote contents", inputs);
    Artifact outputArtifact = ActionsTestUtil.createArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = remoteArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(outputArtifact.getPath().readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
  }

  @Test
  public void createSymbolicLink_localTreeArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    ImmutableMap<String, String> contentMap =
        ImmutableMap.of("foo", "foo contents", "bar", "bar contents");
    Artifact localArtifact = createLocalTreeArtifact("remote-dir", contentMap, inputs);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = localArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
  }

  @Test
  public void createSymbolicLink_remoteTreeArtifact() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    ImmutableMap<String, String> contentMap =
        ImmutableMap.of("foo", "foo contents", "bar", "bar contents");
    Artifact remoteArtifact = createRemoteTreeArtifact("remote-dir", contentMap, inputs);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    PathFragment targetPath = remoteArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(actionFs.getPath(targetPath));

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).readSymbolicLink())
        .isEqualTo(targetPath);
  }

  @Test
  public void createSymbolicLink_unresolvedSymlink() throws Exception {
    // arrange
    ActionInputMap inputs = new ActionInputMap(1);
    SpecialArtifact outputArtifact =
        ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "out");
    ImmutableList<Artifact> outputs = ImmutableList.of(outputArtifact);
    FileSystem actionFs = createActionFileSystem(inputs, outputs);
    PathFragment targetPath = PathFragment.create("some/path");

    // act
    PathFragment linkPath = outputArtifact.getPath().asFragment();
    Path symlinkActionFs = actionFs.getPath(linkPath);
    symlinkActionFs.createSymbolicLink(targetPath);

    // assert
    assertThat(symlinkActionFs.getFileSystem()).isSameInstanceAs(actionFs);
    assertThat(symlinkActionFs.readSymbolicLink()).isEqualTo(targetPath);
    assertThat(getLocalFileSystem(actionFs).getPath(linkPath).exists()).isFalse();
  }

  @Test
  public void createAndReadSymbolicLink_followSymlinks(@TestParameter FilesystemTestParam from)
      throws Exception {
    // createSymbolicLink writes to both the local and remote filesystem, so it makes no sense to
    // parameterize on the symlink's destination filesystem.
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);

    PathFragment parentLinkPath = getOutputPath("parent_link");
    PathFragment parentTargetPath = getOutputPath("parent_target");
    fromFs
        .getPath(parentLinkPath)
        .createSymbolicLink(execRoot.getRelative(parentTargetPath).asFragment());
    actionFs.getPath(parentTargetPath).createDirectoryAndParents();

    PathFragment linkPath = getOutputPath("parent_target/link");
    PathFragment targetPath = PathFragment.create("/some/path");
    actionFs.getPath(linkPath).createSymbolicLink(targetPath);

    assertThat(actionFs.getPath(linkPath).readSymbolicLink()).isEqualTo(targetPath);
  }

  @Test
  public void resolveSymbolicLinks(
      @TestParameter FilesystemTestParam a, @TestParameter FilesystemTestParam b) throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem aFs = a.getFilesystem(actionFs);
    FileSystem bFs = b.getFilesystem(actionFs);

    // /a
    //  |- asub
    //  |  `- afile
    //  `- abssym -> /b/bsub
    //  `- relsym -> asub
    // /b
    //  `- bsub
    //     `- bfile

    aFs.getPath(getOutputPath("a/asub")).createDirectoryAndParents();
    aFs.getPath(getOutputPath("a/abssym")).createSymbolicLink(getOutputPath("b/bsub"));
    aFs.getPath(getOutputPath("a/relsym")).createSymbolicLink(PathFragment.create("asub"));
    if (aFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, getOutputPath("a/asub/afile"), "content");
    } else {
      injectRemoteFile(actionFs, getOutputPath("a/asub/afile"), "content");
    }

    bFs.getPath(getOutputPath("b/bsub")).createDirectoryAndParents();
    if (bFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, getOutputPath("b/bsub/bfile"), "content");
    } else {
      injectRemoteFile(actionFs, getOutputPath("b/bsub/bfile"), "content");
    }

    assertThat(actionFs.getPath(getOutputPath("a/relsym/afile")).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(getOutputPath("a/asub/afile")));

    assertThrows(
        FileNotFoundException.class,
        () -> actionFs.getPath(getOutputPath("a/bsub/nofile")).resolveSymbolicLinks());

    assertThat(actionFs.getPath(getOutputPath("a/abssym/bfile")).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(getOutputPath("b/bsub/bfile")));

    assertThrows(
        FileNotFoundException.class,
        () -> actionFs.getPath(getOutputPath("b/bsub/nofile")).resolveSymbolicLinks());
  }

  @Test
  public void renameTo_onlyLocalFile_renameLocalFile() throws Exception {
    FileSystem actionFs = createActionFileSystem();
    PathFragment path = getOutputPath("file");
    writeLocalFile(actionFs, path, "local-content");
    PathFragment newPath = getOutputPath("file-new");

    actionFs.renameTo(path, newPath);

    assertThat(actionFs.exists(path)).isFalse();
    assertThat(actionFs.exists(newPath)).isTrue();
    assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse();
    assertThat(getLocalFileSystem(actionFs).exists(newPath)).isTrue();
  }

  @Test
  public void renameTo_moveSymlink() throws Exception {
    FileSystem actionFs = createActionFileSystem();
    PathFragment oldLinkPath = getOutputPath("oldLink");
    PathFragment newLinkPath = getOutputPath("newLink");
    PathFragment targetPath = getOutputPath("target");
    actionFs.getPath(oldLinkPath).createSymbolicLink(execRoot.getRelative(targetPath).asFragment());
    writeLocalFile(actionFs, targetPath, "content");

    actionFs.renameTo(oldLinkPath, newLinkPath);

    assertThat(actionFs.getPath(oldLinkPath).exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(actionFs.getPath(newLinkPath).exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(actionFs.getPath(newLinkPath).readSymbolicLink()).isEqualTo(targetPath);
    assertThat(actionFs.getPath(targetPath).exists()).isTrue();
  }

  @Test
  public void renameTo_followSymlinks(
      @TestParameter FilesystemTestParam from, @TestParameter FilesystemTestParam to)
      throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    FileSystem fromFs = from.getFilesystem(actionFs);
    FileSystem toFs = to.getFilesystem(actionFs);

    PathFragment srcDirLinkPath = getOutputPath("srcDirLink");
    PathFragment srcDirTargetPath = getOutputPath("srcDirTarget");
    PathFragment naiveSrcPath = srcDirLinkPath.getChild("oldFile");
    PathFragment canonicalSrcPath = srcDirTargetPath.getChild("oldFile");

    PathFragment dstDirLinkPath = getOutputPath("dstDirLink");
    PathFragment dstDirTargetPath = getOutputPath("dstDirTarget");
    PathFragment naiveDstPath = dstDirLinkPath.getChild("newFile");
    PathFragment canonicalDstPath = dstDirTargetPath.getChild("newFile");

    actionFs.getPath(srcDirTargetPath).createDirectoryAndParents();
    actionFs.getPath(dstDirTargetPath).createDirectoryAndParents();

    fromFs
        .getPath(srcDirLinkPath)
        .createSymbolicLink(execRoot.getRelative(srcDirTargetPath).asFragment());
    fromFs
        .getPath(dstDirLinkPath)
        .createSymbolicLink(execRoot.getRelative(dstDirTargetPath).asFragment());

    if (toFs.equals(actionFs.getLocalFileSystem())) {
      writeLocalFile(actionFs, canonicalSrcPath, "content");
    } else {
      injectRemoteFile(actionFs, canonicalSrcPath, "content");
    }

    actionFs.renameTo(naiveSrcPath, naiveDstPath);

    assertThat(actionFs.exists(naiveSrcPath)).isFalse();
    assertThat(actionFs.exists(canonicalSrcPath)).isFalse();
    assertThat(actionFs.exists(naiveDstPath)).isTrue();
    assertThat(actionFs.exists(canonicalDstPath)).isTrue();
  }

  @Test
  public void renameTo_invalidatesResolveSymbolicLinksCache() throws Exception {
    RemoteActionFileSystem actionFs = (RemoteActionFileSystem) createActionFileSystem();
    PathFragment linkPath = getOutputPath("sym");
    PathFragment targetPath = getOutputPath("target");
    PathFragment renamedPath = getOutputPath("renamed");

    actionFs.getPath(linkPath).getParentDirectory().createDirectoryAndParents();
    actionFs.getPath(linkPath).createSymbolicLink(targetPath);
    writeLocalFile(actionFs, targetPath, "content");

    assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(targetPath));

    actionFs.renameTo(linkPath, renamedPath);
    writeLocalFile(actionFs, linkPath, "content");

    assertThat(actionFs.getPath(linkPath).resolveSymbolicLinks())
        .isEqualTo(actionFs.getPath(linkPath));
  }

  @Override
  @CanIgnoreReturnValue
  protected FileArtifactValue injectRemoteFile(
      FileSystem actionFs, PathFragment path, String content) throws IOException {
    byte[] digest = getDigest(content);
    int size = Utf8.encodedLength(content);
    ((RemoteActionFileSystem) actionFs)
        .injectRemoteFile(path, digest, size, /* expireAtEpochMilli= */ -1);
    return RemoteFileArtifactValue.create(
        digest, size, /* locationIndex= */ 1, /* expireAtEpochMilli= */ -1);
  }

  @Override
  protected void writeLocalFile(FileSystem actionFs, PathFragment path, String content)
      throws IOException {
    FileSystem localFs = getLocalFileSystem(actionFs);
    localFs.getPath(path).getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(localFs.getPath(path), UTF_8, content);
  }

  /** Returns a remote artifact and puts its metadata into the action input map. */
  private Artifact createRemoteArtifact(
      String pathFragment, String content, ActionInputMap inputs) {
    Artifact a = ActionsTestUtil.createArtifact(outputRoot, pathFragment);
    RemoteFileArtifactValue f =
        RemoteFileArtifactValue.create(
            getDigest(content),
            Utf8.encodedLength(content),
            /* locationIndex= */ 1,
            /* expireAtEpochMilli= */ -1);
    inputs.putWithNoDepOwner(a, f);
    return a;
  }

  /** Returns a remote tree artifact and puts its metadata into the action input map. */
  private SpecialArtifact createRemoteTreeArtifact(
      String pathFragment, Map<String, String> contentMap, ActionInputMap inputs) {
    SpecialArtifact a =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment);
    inputs.putTreeArtifact(a, createRemoteTreeArtifactValue(a, contentMap), /* depOwner= */ null);
    return a;
  }

  private TreeArtifactValue createRemoteTreeArtifactValue(
      SpecialArtifact a, Map<String, String> contentMap) {
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(a);
    for (Map.Entry<String, String> entry : contentMap.entrySet()) {
      TreeFileArtifact child = TreeFileArtifact.createTreeOutput(a, entry.getKey());
      String content = entry.getValue();
      RemoteFileArtifactValue childMeta =
          RemoteFileArtifactValue.create(
              getDigest(content),
              Utf8.encodedLength(content),
              /* locationIndex= */ 0,
              /* expireAtEpochMilli= */ -1);
      builder.putChild(child, childMeta);
    }
    return builder.build();
  }

  /** Returns a local artifact and puts its metadata into the action input map. */
  private Artifact createLocalArtifact(String pathFragment, String contents, ActionInputMap inputs)
      throws IOException {
    Path p = outputRoot.getRoot().asPath().getRelative(pathFragment);
    FileSystemUtils.writeContent(p, UTF_8, contents);
    Artifact a = ActionsTestUtil.createArtifact(outputRoot, p);
    Path path = a.getPath();
    // Caution: there's a race condition between stating the file and computing the
    // digest. We need to stat first, since we're using the stat to detect changes.
    // We follow symlinks here to be consistent with getDigest.
    inputs.putWithNoDepOwner(
        a,
        FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE));
    return a;
  }

  /** Returns a local tree artifact and puts its metadata into the action input map. */
  @CanIgnoreReturnValue
  private SpecialArtifact createLocalTreeArtifact(
      String pathFragment, Map<String, String> contentMap, ActionInputMap inputs)
      throws IOException {
    Path dir = outputRoot.getRoot().asPath().getRelative(pathFragment);
    dir.createDirectoryAndParents();
    for (Map.Entry<String, String> entry : contentMap.entrySet()) {
      Path child = dir.getRelative(entry.getKey());
      child.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.writeContent(child, entry.getValue().getBytes(UTF_8));
    }
    SpecialArtifact a =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, pathFragment);
    inputs.putTreeArtifact(a, createLocalTreeArtifactValue(a, contentMap), /* depOwner= */ null);
    return a;
  }

  private TreeArtifactValue createLocalTreeArtifactValue(
      SpecialArtifact a, Map<String, String> contentMap) throws IOException {
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(a);
    for (String name : contentMap.keySet()) {
      Path path = a.getPath().getRelative(name);
      TreeFileArtifact child = TreeFileArtifact.createTreeOutput(a, name);
      FileArtifactValue childMeta =
          FileArtifactValue.createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE);
      builder.putChild(child, childMeta);
    }
    return builder.build();
  }

  private byte[] getDigest(String content) {
    return fs.getDigestFunction().getHashFunction().hashString(content, UTF_8).asBytes();
  }
}
