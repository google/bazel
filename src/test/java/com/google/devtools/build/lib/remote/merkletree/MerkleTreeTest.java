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
package com.google.devtools.build.lib.remote.merkletree;

import static com.google.common.truth.Truth.assertThat;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree.ContentSource;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link MerkleTree}. */
@RunWith(JUnit4.class)
public class MerkleTreeTest {

  private Path execRoot;
  private ArtifactRoot artifactRoot;
  private DigestUtil digestUtil;

  @Before
  public void setup() {
    FileSystem fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/exec");
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "srcs");
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, fs.getDigestFunction());
  }

  @Test
  public void emptyMerkleTree() throws IOException {
    MerkleTree tree =
        MerkleTree.build(
            Collections.emptySortedMap(),
            new StaticInputMetadataProvider(Collections.emptyMap()),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);
    Digest emptyDigest = digestUtil.compute(new byte[0]);
    assertThat(tree.getRootDigest()).isEqualTo(emptyDigest);
  }

  @Test
  public void buildMerkleTree() throws IOException {
    // arrange
    SortedMap<PathFragment, ActionInput> sortedInputs = new TreeMap<>();
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();

    Artifact foo = addFile("srcs/foo.cc", "foo", sortedInputs, metadata);
    Artifact bar = addFile("srcs/bar.cc", "bar", sortedInputs, metadata);
    Artifact buzz = addFile("srcs/fizz/buzz.cc", "buzz", sortedInputs, metadata);
    Artifact fizzbuzz = addFile("srcs/fizz/fizzbuzz.cc", "fizzbuzz", sortedInputs, metadata);

    Directory fizzDir =
        Directory.newBuilder()
            .addFiles(newFileNode("buzz.cc", digestUtil.computeAsUtf8("buzz"), true))
            .addFiles(newFileNode("fizzbuzz.cc", digestUtil.computeAsUtf8("fizzbuzz"), true))
            .build();
    Directory srcsDir =
        Directory.newBuilder()
            .addFiles(newFileNode("bar.cc", digestUtil.computeAsUtf8("bar"), true))
            .addFiles(newFileNode("foo.cc", digestUtil.computeAsUtf8("foo"), true))
            .addDirectories(
                DirectoryNode.newBuilder().setName("fizz").setDigest(digestUtil.compute(fizzDir)))
            .build();
    Directory rootDir =
        Directory.newBuilder()
            .addDirectories(
                DirectoryNode.newBuilder().setName("srcs").setDigest(digestUtil.compute(srcsDir)))
            .build();

    // act
    MerkleTree tree =
        MerkleTree.build(
            sortedInputs,
            new StaticInputMetadataProvider(metadata),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);

    // assert
    Digest expectedRootDigest = digestUtil.compute(rootDir);
    assertThat(tree.getRootDigest()).isEqualTo(expectedRootDigest);

    Digest[] dirDigests =
        new Digest[] {
          digestUtil.compute(fizzDir), digestUtil.compute(srcsDir), digestUtil.compute(rootDir)
        };
    assertThat(tree.getDirectoryByDigest(dirDigests[0])).isEqualTo(fizzDir);
    assertThat(tree.getDirectoryByDigest(dirDigests[1])).isEqualTo(srcsDir);
    assertThat(tree.getDirectoryByDigest(dirDigests[2])).isEqualTo(rootDir);

    Digest[] inputDigests =
        new Digest[] {
          digestUtil.computeAsUtf8("foo"),
          digestUtil.computeAsUtf8("bar"),
          digestUtil.computeAsUtf8("buzz"),
          digestUtil.computeAsUtf8("fizzbuzz")
        };
    assertThat(tree.getFileByDigest(inputDigests[0]))
        .isEqualTo(new ContentSource.PathSource(foo.getPath()));
    assertThat(tree.getFileByDigest(inputDigests[1]))
        .isEqualTo(new ContentSource.PathSource(bar.getPath()));
    assertThat(tree.getFileByDigest(inputDigests[2]))
        .isEqualTo(new ContentSource.PathSource(buzz.getPath()));
    assertThat(tree.getFileByDigest(inputDigests[3]))
        .isEqualTo(new ContentSource.PathSource(fizzbuzz.getPath()));

    Digest[] allDigests = Iterables.toArray(tree.getAllDigests(), Digest.class);
    assertThat(allDigests.length).isEqualTo(dirDigests.length + inputDigests.length);
    assertThat(allDigests).asList().containsAtLeastElementsIn(dirDigests);
    assertThat(allDigests).asList().containsAtLeastElementsIn(inputDigests);
  }

  @Test
  public void mergeMerkleTrees() throws IOException {
    // arrange
    SortedMap<PathFragment, ActionInput> sortedInputs1 = new TreeMap<>();
    SortedMap<PathFragment, ActionInput> sortedInputs2 = new TreeMap<>();
    SortedMap<PathFragment, ActionInput> sortedInputsAll = new TreeMap<>();
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();

    addFile("srcs/foo.cc", "foo", sortedInputs1, metadata);
    addFile("srcs/bar.cc", "bar", sortedInputs2, metadata);
    addFile("srcs/fizz/buzz.cc", "buzz", sortedInputs1, metadata);
    addFile("srcs/fizz/fizzbuzz.cc", "fizzbuzz", sortedInputs2, metadata);
    sortedInputsAll.putAll(sortedInputs1);
    sortedInputsAll.putAll(sortedInputs2);

    MerkleTree treeEmpty =
        MerkleTree.build(
            new TreeMap<>(),
            new StaticInputMetadataProvider(metadata),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);
    MerkleTree tree1 =
        MerkleTree.build(
            sortedInputs1,
            new StaticInputMetadataProvider(metadata),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);
    MerkleTree tree2 =
        MerkleTree.build(
            sortedInputs2,
            new StaticInputMetadataProvider(metadata),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);
    MerkleTree treeAll =
        MerkleTree.build(
            sortedInputsAll,
            new StaticInputMetadataProvider(metadata),
            execRoot,
            ArtifactPathResolver.forExecRoot(execRoot),
            /* spawnScrubber= */ null,
            digestUtil);

    // act
    MerkleTree mergedTreeEmpty = MerkleTree.merge(Arrays.asList(), digestUtil);
    MerkleTree mergedTree1 = MerkleTree.merge(Arrays.asList(tree1), digestUtil);
    MerkleTree mergedTree11 = MerkleTree.merge(Arrays.asList(tree1, tree1), digestUtil);
    MerkleTree mergedTree12 = MerkleTree.merge(Arrays.asList(tree1, tree2), digestUtil);

    // assert
    assertThat(mergedTreeEmpty.getRootDigest()).isEqualTo(treeEmpty.getRootDigest());
    assertThat(mergedTree1.getRootDigest()).isEqualTo(tree1.getRootDigest());
    assertThat(mergedTree11.getRootDigest()).isEqualTo(tree1.getRootDigest());
    assertThat(mergedTree12.getRootDigest()).isEqualTo(treeAll.getRootDigest());

    assertThat(mergedTree1).isSameInstanceAs(tree1);

    assertThat(mergedTree12.getAllDigests()).containsExactlyElementsIn(treeAll.getAllDigests());
  }

  private Artifact addFile(
      String path,
      String content,
      SortedMap<PathFragment, ActionInput> sortedInputs,
      Map<ActionInput, FileArtifactValue> metadata)
      throws IOException {
    Path p = execRoot.getRelative(path);
    Preconditions.checkNotNull(p.getParentDirectory()).createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(p, content);
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);

    sortedInputs.put(PathFragment.create(path), a);
    metadata.put(a, FileArtifactValue.createForTesting(a));
    return a;
  }

  private static FileNode newFileNode(String name, Digest digest, boolean isExecutable) {
    return FileNode.newBuilder()
        .setName(name)
        .setDigest(digest)
        .setIsExecutable(isExecutable)
        .build();
  }
}
