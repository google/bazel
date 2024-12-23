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

import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.merkletree.DirectoryTree.DirectoryNode;
import com.google.devtools.build.lib.remote.merkletree.DirectoryTree.FileNode;
import com.google.devtools.build.lib.remote.merkletree.DirectoryTree.Node;
import com.google.devtools.build.lib.remote.merkletree.DirectoryTree.SymlinkNode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DirectoryTree}. */
@RunWith(JUnit4.class)
public abstract class DirectoryTreeTest {

  protected Path execRoot;
  protected ArtifactRoot artifactRoot;
  protected DigestUtil digestUtil;

  @Before
  public void setup() {
    FileSystem fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/exec");
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "srcs");
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, fs.getDigestFunction());
  }

  protected abstract DirectoryTree build(Path... paths) throws IOException;

  @Test
  public void emptyTreeShouldWork() throws Exception {
    DirectoryTree tree = build();
    assertThat(directoryNodesAtDepth(tree, 0)).isEmpty();
    assertThat(fileNodesAtDepth(tree, 0)).isEmpty();
  }

  @Test
  public void buildingATreeOfFilesShouldWork() throws Exception {
    Path foo = createFile("srcs/foo.cc", "foo");
    Path bar = createFile("srcs/bar.cc", "bar");
    Path buzz = createFile("srcs/fizz/buzz.cc", "buzz");

    DirectoryTree tree = build(foo, bar, buzz);
    assertLexicographicalOrder(tree);

    assertThat(directoriesAtDepth(0, tree)).containsExactly("srcs");
    assertThat(directoriesAtDepth(1, tree)).containsExactly("fizz");
    assertThat(directoriesAtDepth(2, tree)).isEmpty();

    FileNode expectedFooNode =
        FileNode.createExecutable("foo.cc", foo, digestUtil.computeAsUtf8("foo"));
    FileNode expectedBarNode =
        FileNode.createExecutable("bar.cc", bar, digestUtil.computeAsUtf8("bar"));
    FileNode expectedBuzzNode =
        FileNode.createExecutable("buzz.cc", buzz, digestUtil.computeAsUtf8("buzz"));
    assertThat(fileNodesAtDepth(tree, 0)).isEmpty();
    assertThat(fileNodesAtDepth(tree, 1)).containsExactly(expectedFooNode, expectedBarNode);
    assertThat(fileNodesAtDepth(tree, 2)).containsExactly(expectedBuzzNode);
  }


  @Test
  public void testLexicographicalOrder() throws Exception {
    // Regression test for https://github.com/bazelbuild/bazel/pull/8008
    //
    // The issue was that before #8008 we wrongly assumed that a sorted full list of inputs would
    // also lead to sorted tree nodes. Thereby not taking into account that the path separator '/'
    // influences the sorting of the full list but not that of the tree nodes as its stripped there.
    // For example, the below full list is lexicographically sorted
    //  srcs/system-root/bar.txt
    //  srcs/system/foo.txt
    //
    // However, the tree node [system-root, system] is not (note the missing / suffix).

    Path file1 = createFile("srcs/system/foo.txt", "foo");
    Path file2 = createFile("srcs/system-root/bar.txt", "bar");

    DirectoryTree tree = build(file1, file2);

    assertLexicographicalOrder(tree);
  }

  protected Path createFile(String path, String content) throws IOException {
    Path p = execRoot.getRelative(path);
    p.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(p, content);
    return p;
  }

  static void assertLexicographicalOrder(DirectoryTree tree) {
    // Assert the lexicographical order as defined by the remote execution protocol
    tree.visit(
        (PathFragment dirname,
            SortedSet<FileNode> files,
            SortedSet<SymlinkNode> symlinks,
            SortedSet<DirectoryNode> dirs) -> {
          assertThat(files).isInStrictOrder();
          assertThat(dirs).isInStrictOrder();
        });
  }

  static List<String> directoriesAtDepth(int depth, DirectoryTree tree) {
    return asPathSegments(directoryNodesAtDepth(tree, depth));
  }

  private static List<String> asPathSegments(List<? extends Node> nodes) {
    return nodes.stream().map(Node::getPathSegment).collect(Collectors.toList());
  }

  private static List<DirectoryNode> directoryNodesAtDepth(DirectoryTree tree, int depth) {
    List<DirectoryNode> directoryNodes = new ArrayList<>();
    tree.visit(
        (PathFragment dirname,
            SortedSet<FileNode> files,
            SortedSet<SymlinkNode> symlinks,
            SortedSet<DirectoryNode> dirs) -> {
          int currDepth = dirname.segmentCount();
          if (currDepth == depth) {
            directoryNodes.addAll(dirs);
          }
        });
    return directoryNodes;
  }

  static List<FileNode> fileNodesAtDepth(DirectoryTree tree, int depth) {
    List<FileNode> fileNodes = new ArrayList<>();
    tree.visit(
        (PathFragment dirname,
            SortedSet<FileNode> files,
            SortedSet<SymlinkNode> symlinks,
            SortedSet<DirectoryNode> dirs) -> {
          int currDepth = dirname.segmentCount();
          if (currDepth == depth) {
            fileNodes.addAll(files);
          }
        });
    return fileNodes;
  }
}
