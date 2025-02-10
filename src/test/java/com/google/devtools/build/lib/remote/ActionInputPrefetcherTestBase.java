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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.createTreeArtifactWithGeneratingAction;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Priority;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher.Reason;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.remote.AbstractActionInputPrefetcher.MetadataSupplier;
import com.google.devtools.build.lib.remote.common.LostInputsEvent;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testing.vfs.SpiedFileSystem;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.TempPathGenerator;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;

/** Base test class for {@link AbstractActionInputPrefetcher} implementations. */
public abstract class ActionInputPrefetcherTestBase {
  protected static final DigestHashFunction HASH_FUNCTION = DigestHashFunction.SHA256;

  private static class DelayedChmodFileSystem extends DelegateFileSystem {

    private Duration chmodDelay = Duration.ZERO;

    DelayedChmodFileSystem(FileSystem delegateFs) {
      super(delegateFs);
    }

    @Override
    public void chmod(PathFragment path, int mode) throws IOException {
      if (!chmodDelay.isZero()) {
        try {
          Thread.sleep(chmodDelay.toMillis());
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      super.chmod(path, mode);
    }

    void setChmodDelay(Duration chmodDelay) {
      this.chmodDelay = chmodDelay;
    }
  }

  protected SpiedFileSystem fs;
  protected Path execRoot;
  protected ArtifactRoot artifactRoot;
  protected TempPathGenerator tempPathGenerator;
  protected EventBus eventBus;

  protected ActionExecutionMetadata action;

  @Before
  public void setUp() throws IOException {
    action = mock(ActionExecutionMetadata.class);
    when(action.getMnemonic()).thenReturn("DummyAction");
    when(action.getOwner()).thenReturn(NULL_ACTION_OWNER);

    fs =
        SpiedFileSystem.createSpy(
            new DelayedChmodFileSystem(new InMemoryFileSystem(DigestHashFunction.SHA256)));
    execRoot = fs.getPath("/exec");
    execRoot.createDirectoryAndParents();
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "root");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    Path tempDir = fs.getPath("/tmp");
    tempDir.createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(tempDir);

    eventBus = new EventBus();
  }

  protected Artifact createRemoteArtifact(
      String pathFragment,
      String contents,
      @Nullable PathFragment resolvedPath,
      Map<ActionInput, FileArtifactValue> metadata,
      @Nullable Map<HashCode, byte[]> cas) {
    Path p = artifactRoot.getRoot().getRelative(pathFragment);
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    byte[] contentsBytes = contents.getBytes(UTF_8);
    HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contentsBytes);
    FileArtifactValue f =
        FileArtifactValue.createForRemoteFileWithMaterializationData(
            hashCode.asBytes(),
            contentsBytes.length,
            /* locationIndex= */ 1,
            /* expirationTime= */ null);
    if (resolvedPath != null) {
      f = FileArtifactValue.createFromExistingWithResolvedPath(f, resolvedPath);
    }
    metadata.put(a, f);
    if (cas != null) {
      cas.put(hashCode, contentsBytes);
    }
    return a;
  }

  protected Artifact createRemoteArtifact(
      String pathFragment,
      String contents,
      Map<ActionInput, FileArtifactValue> metadata,
      @Nullable Map<HashCode, byte[]> cas) {
    return createRemoteArtifact(pathFragment, contents, /* resolvedPath= */ null, metadata, cas);
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> createRemoteTreeArtifact(
      String pathFragment,
      Map<String, String> localContentMap,
      Map<String, String> remoteContentMap,
      @Nullable PathFragment resolvedPath,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<HashCode, byte[]> cas,
      boolean isActionTemplateExpansion)
      throws IOException {
    SpecialArtifact parent = createTreeArtifactWithGeneratingAction(artifactRoot, pathFragment);

    TreeArtifactValue.Builder treeBuilder = TreeArtifactValue.newBuilder(parent);
    for (Map.Entry<String, String> entry : localContentMap.entrySet()) {
      TreeFileArtifact child =
          TreeFileArtifact.createTreeOutput(parent, PathFragment.create(entry.getKey()));
      byte[] contents = entry.getValue().getBytes(UTF_8);
      HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents);
      FileArtifactValue childValue =
          FileArtifactValue.createForNormalFile(
              hashCode.asBytes(), /* proxy= */ null, contents.length);
      treeBuilder.putChild(child, childValue);
      metadata.put(child, childValue);
      cas.put(hashCode, contents);
    }
    for (Map.Entry<String, String> entry : remoteContentMap.entrySet()) {
      PathFragment parentRelativePath = PathFragment.create(entry.getKey());
      TreeFileArtifact child =
          isActionTemplateExpansion
              ? TreeFileArtifact.createTemplateExpansionOutput(
                  parent,
                  parentRelativePath,
                  ActionsTestUtil.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER)
              : TreeFileArtifact.createTreeOutput(parent, parentRelativePath);
      byte[] contents = entry.getValue().getBytes(UTF_8);
      HashCode hashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents);
      FileArtifactValue childValue =
          FileArtifactValue.createForRemoteFileWithMaterializationData(
              hashCode.asBytes(),
              contents.length,
              /* locationIndex= */ 1,
              /* expirationTime= */ null);
      treeBuilder.putChild(child, childValue);
      metadata.put(child, childValue);
      cas.put(hashCode, contents);
    }
    if (resolvedPath != null) {
      treeBuilder.setResolvedPath(resolvedPath);
    }
    TreeArtifactValue treeValue = treeBuilder.build();

    metadata.put(parent, treeValue.getMetadata());

    return Pair.of(parent, treeValue.getChildren().asList());
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> createRemoteTreeArtifact(
      String pathFragment,
      Map<String, String> localContentMap,
      Map<String, String> remoteContentMap,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<HashCode, byte[]> cas)
      throws IOException {
    return createRemoteTreeArtifact(
        pathFragment,
        localContentMap,
        remoteContentMap,
        /* resolvedPath= */ null,
        metadata,
        cas,
        /* isActionTemplateExpansion= */ false);
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> createRemoteTreeArtifact(
      String pathFragment,
      Map<String, String> localContentMap,
      Map<String, String> remoteContentMap,
      @Nullable PathFragment resolvedPath,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<HashCode, byte[]> cas)
      throws IOException {
    return createRemoteTreeArtifact(
        pathFragment,
        localContentMap,
        remoteContentMap,
        resolvedPath,
        metadata,
        cas,
        /* isActionTemplateExpansion= */ false);
  }

  protected Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>>
      createRemoteTreeArtifactForActionTemplateExpansion(
          String pathFragment,
          Map<String, String> localContentMap,
          Map<String, String> remoteContentMap,
          Map<ActionInput, FileArtifactValue> metadata,
          Map<HashCode, byte[]> cas)
          throws IOException {
    return createRemoteTreeArtifact(
        pathFragment,
        localContentMap,
        remoteContentMap,
        /* resolvedPath= */ null,
        metadata,
        cas,
        /* isActionTemplateExpansion= */ true);
  }

  protected abstract AbstractActionInputPrefetcher createPrefetcher(Map<HashCode, byte[]> cas);

  @Test
  public void prefetchFiles_fileExists_doNotDownload()
      throws IOException, ExecException, InterruptedException {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world", metadata, cas);
    FileSystemUtils.writeContent(a.getPath(), "hello world".getBytes(UTF_8));
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    verify(prefetcher, never())
        .doDownloadFile(eq(action), any(), any(), any(), any(), any(), any());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_fileExistsButContentMismatches_download()
      throws IOException, ExecException, InterruptedException {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a = createRemoteArtifact("file", "hello world remote", metadata, cas);
    FileSystemUtils.writeContent(a.getPath(), "hello world local".getBytes(UTF_8));
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    verify(prefetcher)
        .doDownloadFile(eq(action), any(), any(), eq(a.getExecPath()), any(), any(), any());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
    assertThat(FileSystemUtils.readContent(a.getPath(), UTF_8)).isEqualTo("hello world remote");
  }

  @Test
  public void prefetchFiles_downloadRemoteFiles() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    Artifact a2 = createRemoteArtifact("file2", "fizz buzz", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(FileSystemUtils.readContent(a1.getPath(), UTF_8)).isEqualTo("hello world");
    assertReadableNonWritableAndExecutable(a1.getPath());
    assertThat(FileSystemUtils.readContent(a2.getPath(), UTF_8)).isEqualTo("fizz buzz");
    assertReadableNonWritableAndExecutable(a2.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(a1.getPath(), a2.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteFiles_withResolvedPath() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    PathFragment resolvedPath = artifactRoot.getRoot().asPath().getChild("target").asFragment();
    Artifact a = createRemoteArtifact("file", "hello world", resolvedPath, metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(a.getPath().isSymbolicLink()).isTrue();
    assertThat(a.getPath().readSymbolicLink()).isEqualTo(resolvedPath);
    assertThat(FileSystemUtils.readContent(a.getPath(), UTF_8)).isEqualTo("hello world");
    assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath(), fs.getPath(resolvedPath));
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "file1", "content1", "nested_dir/file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, children, metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(FileSystemUtils.readContent(firstChild.getPath(), UTF_8)).isEqualTo("content1");
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");

    assertTreeReadableNonWritableAndExecutable(tree.getPath());

    assertThat(prefetcher.downloadedFiles())
        .containsExactly(firstChild.getPath(), secondChild.getPath());
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees_partial() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of("file1", "content1"),
            /* remoteContentMap= */ ImmutableMap.of("file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(firstChild, secondChild),
            metadata::get,
            Priority.MEDIUM,
            Reason.INPUTS));

    assertThat(firstChild.getPath().exists()).isFalse();
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");
    assertTreeReadableNonWritableAndExecutable(tree.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath());
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees_withResolvedPath() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    PathFragment resolvedPath = artifactRoot.getRoot().asPath().getChild("target").asFragment();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "file1", "content1", "nested_dir/file2", "content2"),
            resolvedPath,
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, children, metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(tree.getPath().isSymbolicLink()).isTrue();
    assertThat(tree.getPath().readSymbolicLink()).isEqualTo(resolvedPath);
    assertThat(FileSystemUtils.readContent(firstChild.getPath(), UTF_8)).isEqualTo("content1");
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");

    assertTreeReadableNonWritableAndExecutable(fs.getPath(resolvedPath));

    assertThat(prefetcher.downloadedFiles())
        .containsExactly(
            tree.getPath(),
            fs.getPath(resolvedPath).getRelative(firstChild.getParentRelativePath()),
            fs.getPath(resolvedPath).getRelative(secondChild.getParentRelativePath()));
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_downloadRemoteTrees_forActionTemplateExpansion() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifactForActionTemplateExpansion(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "subdir/file1", "content1", "subdir/file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, ImmutableList.of(firstChild), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertTreeReadableWritableAndExecutable(tree.getPath());

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, ImmutableList.of(secondChild), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertTreeReadableWritableAndExecutable(tree.getPath());
  }

  @Test
  public void prefetchFiles_missingFiles_fails() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a = createRemoteArtifact("file1", "hello world", metadata, /* cas= */ new HashMap<>());
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    assertThrows(
        Exception.class,
        () ->
            wait(
                prefetcher.prefetchFilesInterruptibly(
                    action, ImmutableList.of(a), metadata::get, Priority.MEDIUM, Reason.INPUTS)));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_ignoreNonRemoteFiles() throws Exception {
    // Test that non-remote files are not downloaded.

    Path p = execRoot.getRelative(artifactRoot.getExecPath()).getRelative("file1");
    FileSystemUtils.writeContent(p, UTF_8, "hello world");
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    FileArtifactValue f = FileArtifactValue.createForTesting(a);
    ImmutableMap<ActionInput, FileArtifactValue> metadata = ImmutableMap.of(a, f);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(new HashMap<>());

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, ImmutableList.of(a), metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(prefetcher.downloadedFiles()).isEmpty();
    assertThat(prefetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void prefetchFiles_ignoreNonRemoteFiles_tree() throws Exception {
    // Test that non-remote tree files are not downloaded, but other files in the tree are.

    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            ImmutableMap.of("file1", "content1"),
            ImmutableMap.of("file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action, children, metadata::get, Priority.MEDIUM, Reason.INPUTS));

    assertThat(firstChild.getPath().exists()).isFalse();
    assertThat(FileSystemUtils.readContent(secondChild.getPath(), UTF_8)).isEqualTo("content2");
    assertTreeReadableNonWritableAndExecutable(tree.getPath());
    assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath());
  }

  @Test
  public void prefetchFiles_treeFiles_minimizeFilesystemOperations() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of(
                "subdir/file1", "content1", "subdir/file2", "content2"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    PathFragment root = tree.getPath().asFragment();
    PathFragment subdir = tree.getPath().getChild("subdir").asFragment();
    ImmutableList<TreeFileArtifact> children = treeAndChildren.getSecond();
    Artifact firstChild = children.get(0);
    Artifact secondChild = children.get(1);

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    reset(fs);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(firstChild, secondChild),
            metadata::get,
            Priority.MEDIUM,
            Reason.INPUTS));

    verify(fs).createWritableDirectory(root);
    verify(fs).createWritableDirectory(subdir);
    verify(fs).chmod(root, 0555);
    verify(fs).chmod(subdir, 0555);

    reset(fs);

    wait(
        prefetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(firstChild, secondChild),
            metadata::get,
            Priority.MEDIUM,
            Reason.INPUTS));

    verify(fs, never()).createWritableDirectory(root);
    verify(fs, never()).createWritableDirectory(subdir);
    verify(fs, never()).chmod(root, 0555);
    verify(fs, never()).chmod(subdir, 0555);
  }

  @Test
  public void prefetchFiles_treeFiles_multipleThreads_waitForPermissionsToBeSet() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Pair<SpecialArtifact, ImmutableList<TreeFileArtifact>> treeAndChildren =
        createRemoteTreeArtifact(
            "dir",
            /* localContentMap= */ ImmutableMap.of(),
            /* remoteContentMap= */ ImmutableMap.of("subdir/file", "content"),
            metadata,
            cas);
    SpecialArtifact tree = treeAndChildren.getFirst();
    Artifact child = Iterables.getOnlyElement(treeAndChildren.getSecond());

    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    // Prefetch the same tree artifact in two concurrent calls.
    // Verify that the second waits until the download operation completes *and* sets the output
    // permissions on the entire tree artifact before returning.
    // Delay the chmod() calls to make it much more likely that we'd catch a bug where the second
    // call returns after the download completes but before the permissions have been set.
    // Regression test for b/299934607.

    ((DelayedChmodFileSystem) fs.getDelegateFs()).setChmodDelay(Duration.ofMillis(100));

    ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, SECONDS, new LinkedBlockingQueue<>());

    Callable<Void> prefetch =
        () -> {
          wait(
              prefetcher.prefetchFilesInterruptibly(
                  action, ImmutableList.of(child), metadata::get, Priority.MEDIUM, Reason.INPUTS));
          assertTreeReadableNonWritableAndExecutable(tree.getPath());
          return null;
        };

    Future<Void> f1 = pool.submit(prefetch);
    Future<Void> f2 = pool.submit(prefetch);

    pool.shutdown();
    pool.awaitTermination(10, SECONDS);

    f1.get();
    f2.get();
  }

  @Test
  public void prefetchFiles_multipleThreads_downloadIsCancelled() throws Exception {
    // Test shared downloads are cancelled if all threads/callers are interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cas);

    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    SettableFuture<Void> downloadThatNeverFinishes = SettableFuture.create();
    mockDownload(prefetcher, cas, () -> downloadThatNeverFinishes);

    Thread cancelledThread1 =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(artifact),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    Thread cancelledThread2 =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(artifact),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    // act
    cancelledThread1.start();
    cancelledThread2.start();
    cancelledThread1.interrupt();
    cancelledThread2.interrupt();
    cancelledThread1.join();
    cancelledThread2.join();

    // assert
    assertThat(downloadThatNeverFinishes.isCancelled()).isTrue();
    assertThat(artifact.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void prefetchFiles_multipleThreads_downloadIsNotCancelledByOtherThreads()
      throws Exception {
    // Test multiple threads can share downloads, but do not cancel each other when interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cas);
    SettableFuture<Void> download = SettableFuture.create();
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    mockDownload(prefetcher, cas, () -> download);
    Thread cancelledThread =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(artifact),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS));
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });

    AtomicBoolean successful = new AtomicBoolean(false);
    Thread successfulThread =
        new Thread(
            () -> {
              try {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(artifact),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS));
                successful.set(true);
              } catch (IOException | ExecException | InterruptedException ignored) {
                // do nothing
              }
            });
    cancelledThread.start();
    successfulThread.start();
    while (true) {
      if (prefetcher
              .getDownloadCache()
              .getSubscriberCount(execRoot.getRelative(artifact.getExecPath()))
          == 2) {
        break;
      }
    }

    // act
    cancelledThread.interrupt();
    cancelledThread.join();
    // simulate the download finishing
    assertThat(download.isCancelled()).isFalse();
    download.set(null);
    successfulThread.join();

    // assert
    assertThat(successful.get()).isTrue();
    assertThat(FileSystemUtils.readContent(artifact.getPath(), UTF_8)).isEqualTo("hello world");
  }

  @Test
  public void prefetchFile_interruptingMetadataSupplier_interruptsDownload() throws Exception {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);

    MetadataSupplier interruptedMetadataSupplier =
        unused -> {
          throw new InterruptedException();
        };

    ListenableFuture<Void> future =
        prefetcher.prefetchFilesInterruptibly(
            action,
            ImmutableList.of(a1),
            interruptedMetadataSupplier,
            Priority.MEDIUM,
            Reason.INPUTS);

    assertThrows(InterruptedException.class, () -> getFromFuture(future));
  }

  @Test
  public void prefetchFiles_onInterrupt_deletePartialDownloadedFile() throws Exception {
    Semaphore startSemaphore = new Semaphore(0);
    Semaphore endSemaphore = new Semaphore(0);
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cas);
    AbstractActionInputPrefetcher prefetcher = spy(createPrefetcher(cas));
    mockDownload(
        prefetcher,
        cas,
        () -> {
          startSemaphore.release();
          return SettableFuture.create(); // A future that never complete so we can interrupt later
        });

    AtomicBoolean interrupted = new AtomicBoolean(false);
    Thread t =
        new Thread(
            () -> {
              try {
                getFromFuture(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        ImmutableList.of(a1),
                        metadata::get,
                        Priority.MEDIUM,
                        Reason.INPUTS));
              } catch (IOException ignored) {
                // Intentionally left empty
              } catch (InterruptedException e) {
                interrupted.set(true);
              }
              endSemaphore.release();
            });
    t.start();
    startSemaphore.acquire();
    t.interrupt();
    endSemaphore.acquire();

    assertThat(interrupted.get()).isTrue();
    assertThat(a1.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void missingInputs_sendLostInputsEvent() {
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<HashCode, byte[]> cas = new HashMap<>();
    var unused = createRemoteArtifact("file", "hello world", metadata, /* cas= */ null);
    AbstractActionInputPrefetcher prefetcher = createPrefetcher(cas);
    var lostInputsEvents = new ConcurrentLinkedQueue<LostInputsEvent>();
    eventBus.register(
        new Object() {
          @Subscribe
          @AllowConcurrentEvents
          public void onLostInputsEvent(LostInputsEvent event) {
            lostInputsEvents.add(event);
          }
        });

    assertThrows(
        Exception.class,
        () ->
            wait(
                prefetcher.prefetchFilesInterruptibly(
                    action, metadata.keySet(), metadata::get, Priority.MEDIUM, Reason.INPUTS)));

    assertThat(lostInputsEvents).hasSize(1);
  }

  protected static void wait(ListenableFuture<Void> future)
      throws IOException, ExecException, InterruptedException {
    try {
      future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        throwIfInstanceOf(cause, IOException.class);
        throwIfInstanceOf(cause, ExecException.class);
        throwIfInstanceOf(cause, InterruptedException.class);
        throwIfInstanceOf(cause, RuntimeException.class);
      }
      throw new IOException(e);
    } catch (InterruptedException e) {
      future.cancel(/* mayInterruptIfRunning= */ true);
      throw e;
    }
  }

  protected static void mockDownload(
      AbstractActionInputPrefetcher prefetcher,
      Map<HashCode, byte[]> cas,
      Supplier<ListenableFuture<Void>> resultSupplier)
      throws IOException {
    doAnswer(
            invocation -> {
              Path path = invocation.getArgument(2);
              FileArtifactValue metadata = invocation.getArgument(4);
              byte[] content = cas.get(HashCode.fromBytes(metadata.getDigest()));
              if (content == null) {
                return Futures.immediateFailedFuture(new IOException("Not found"));
              }
              FileSystemUtils.writeContent(path, content);
              return resultSupplier.get();
            })
        .when(prefetcher)
        .doDownloadFile(any(), any(), any(), any(), any(), any(), any());
  }

  private void assertReadableNonWritableAndExecutable(Path path) throws IOException {
    assertWithMessage(path + " should be readable").that(path.isReadable()).isTrue();
    assertWithMessage(path + " should not be writable").that(path.isWritable()).isFalse();
    assertWithMessage(path + " should be executable").that(path.isExecutable()).isTrue();
  }

  private void assertTreeReadableNonWritableAndExecutable(Path path) throws IOException {
    checkState(path.isDirectory());
    assertReadableNonWritableAndExecutable(path);
    for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
      if (dirent.getType().equals(Dirent.Type.DIRECTORY)) {
        assertTreeReadableNonWritableAndExecutable(path.getChild(dirent.getName()));
      }
    }
  }

  private void assertReadableWritableAndExecutable(Path path) throws IOException {
    assertWithMessage(path + " should be readable").that(path.isReadable()).isTrue();
    assertWithMessage(path + " should be writable").that(path.isWritable()).isTrue();
    assertWithMessage(path + " should be executable").that(path.isExecutable()).isTrue();
  }

  private void assertTreeReadableWritableAndExecutable(Path path) throws IOException {
    checkState(path.isDirectory());
    assertReadableWritableAndExecutable(path);
    for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
      if (dirent.getType().equals(Dirent.Type.DIRECTORY)) {
        assertTreeReadableWritableAndExecutable(path.getChild(dirent.getName()));
      }
    }
  }
}
