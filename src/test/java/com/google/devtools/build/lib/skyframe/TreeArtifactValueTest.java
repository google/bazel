// Copyright 2020 The Bazel Authors. All rights reserved.
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
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TreeArtifactValue}. */
@RunWith(TestParameterInjector.class)
public final class TreeArtifactValueTest {

  private final Scratch scratch = new Scratch();
  private final ArtifactRoot root =
      ArtifactRoot.asDerivedRoot(
          scratch.resolve("root"), RootType.Output, PathFragment.create("bin"));

  record VisitTreeArgs(
      PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink) {
    VisitTreeArgs {
      requireNonNull(parentRelativePath, "parentRelativePath");
      requireNonNull(type, "type");
    }

    static VisitTreeArgs of(
        PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink) {
      return new VisitTreeArgs(parentRelativePath, type, traversedSymlink);
    }
  }

  @Test
  public void createsCorrectValue() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    FileArtifactValue metadata1 = metadataWithId(1);
    FileArtifactValue metadata2 = metadataWithId(2);

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child1, metadata1)
            .putChild(child2, metadata2)
            .build();

    assertThat(tree.getChildren()).containsExactly(child1, child2);
    assertThat(tree.getChildValues()).containsExactly(child1, metadata1, child2, metadata2);
    assertThat(tree.getChildPaths())
        .containsExactly(child1.getParentRelativePath(), child2.getParentRelativePath());
    assertThat(tree.getDigest()).isNotNull();
    assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest());
  }

  @Test
  public void createsCorrectValueWithArchivedRepresentation() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue child1Metadata = metadataWithId(1);
    FileArtifactValue child2Metadata = metadataWithId(2);
    FileArtifactValue archivedArtifactMetadata = metadataWithId(3);

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child1, child1Metadata)
            .putChild(child2, child2Metadata)
            .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
            .build();

    assertThat(tree.getChildren()).containsExactly(child1, child2);
    assertThat(tree.getChildValues())
        .containsExactly(child1, child1Metadata, child2, child2Metadata);
    assertThat(tree.getChildPaths())
        .containsExactly(child1.getParentRelativePath(), child2.getParentRelativePath());
    assertThat(tree.getDigest()).isNotNull();
    assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest());
    assertThat(tree.getArchivedRepresentation())
        .hasValue(ArchivedRepresentation.create(archivedTreeArtifact, archivedArtifactMetadata));
  }

  @Test
  public void createsCorrectValueWithResolvedPath() {
    PathFragment targetPath = PathFragment.create("/some/target/path");
    SpecialArtifact parent = createTreeArtifact("bin/tree");

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(parent).setResolvedPath(targetPath).build();

    assertThat(tree.getResolvedPath()).hasValue(targetPath);
    assertThat(tree.getMetadata().getResolvedPath()).isEqualTo(targetPath);
  }

  @Test
  public void empty() {
    TreeArtifactValue emptyTree = TreeArtifactValue.empty();

    assertThat(emptyTree.getChildren()).isEmpty();
    assertThat(emptyTree.getChildValues()).isEmpty();
    assertThat(emptyTree.getChildPaths()).isEmpty();
    assertThat(emptyTree.getDigest()).isNotNull();
    assertThat(emptyTree.getMetadata().getDigest()).isEqualTo(emptyTree.getDigest());
  }

  @Test
  public void createsCanonicalEmptyInstance() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");

    TreeArtifactValue emptyTreeFromBuilder = TreeArtifactValue.newBuilder(parent).build();

    assertThat(emptyTreeFromBuilder).isSameInstanceAs(TreeArtifactValue.empty());
  }

  @Test
  public void createsCorrectEmptyValueWithArchivedRepresentation() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue archivedArtifactMetadata = metadataWithId(1);

    TreeArtifactValue tree =
        TreeArtifactValue.newBuilder(parent)
            .setArchivedRepresentation(archivedTreeArtifact, archivedArtifactMetadata)
            .build();

    assertThat(tree.getChildren()).isEmpty();
    assertThat(tree.getChildValues()).isEmpty();
    assertThat(tree.getChildPaths()).isEmpty();
    assertThat(tree.getDigest()).isNotNull();
    assertThat(tree.getMetadata().getDigest()).isEqualTo(tree.getDigest());
    assertThat(tree.getArchivedRepresentation())
        .hasValue(ArchivedRepresentation.create(archivedTreeArtifact, archivedArtifactMetadata));
  }

  @Test
  public void cannotCreateBuilderForNonTreeArtifact() {
    SpecialArtifact notTreeArtifact =
        SpecialArtifact.create(
            root,
            PathFragment.create("bin/not_tree"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET);

    assertThrows(
        IllegalArgumentException.class, () -> TreeArtifactValue.newBuilder(notTreeArtifact));
  }

  @Test
  public void cannotMixParentsWithinSingleBuilder() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact childOfAnotherParent =
        TreeFileArtifact.createTreeOutput(createTreeArtifact("bin/other_tree"), "child");

    TreeArtifactValue.Builder builderForParent = TreeArtifactValue.newBuilder(parent);

    assertThrows(
        IllegalArgumentException.class,
        () -> builderForParent.putChild(childOfAnotherParent, metadataWithId(1)));
  }

  @Test
  public void cannotAddArchivedRepresentationWithWrongParent() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    ArchivedTreeArtifact archivedDifferentTreeArtifact =
        createArchivedTreeArtifact(createTreeArtifact("bin/other_tree"));
    TreeArtifactValue.Builder builderForParent = TreeArtifactValue.newBuilder(parent);
    FileArtifactValue metadata = metadataWithId(1);

    assertThrows(
        IllegalArgumentException.class,
        () -> builderForParent.setArchivedRepresentation(archivedDifferentTreeArtifact, metadata));
  }

  @Test
  public void orderIndependence() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    FileArtifactValue metadata1 = metadataWithId(1);
    FileArtifactValue metadata2 = metadataWithId(2);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child1, metadata1)
            .putChild(child2, metadata2)
            .build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent)
            .putChild(child2, metadata2)
            .putChild(child1, metadata1)
            .build();

    assertThat(tree1).isEqualTo(tree2);
  }

  @Test
  public void nullDigests_equal() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");
    FileArtifactValue metadataNoDigest = metadataWithIdNoDigest(1);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest).build();

    assertThat(metadataNoDigest.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1).isEqualTo(tree2);
  }

  @Test
  public void nullDigestsForArchivedRepresentation_equal() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue metadataNoDigest = metadataWithIdNoDigest(1);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent)
            .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest)
            .build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent)
            .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest)
            .build();

    assertThat(metadataNoDigest.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1).isEqualTo(tree2);
  }

  @Test
  public void nullDigests_notEqual() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");
    FileArtifactValue metadataNoDigest1 = metadataWithIdNoDigest(1);
    FileArtifactValue metadataNoDigest2 = metadataWithIdNoDigest(2);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest1).build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent).putChild(child, metadataNoDigest2).build();

    assertThat(metadataNoDigest1.getDigest()).isNull();
    assertThat(metadataNoDigest2.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1.getDigest()).isNotEqualTo(tree2.getDigest());
  }

  @Test
  public void nullDigestsForArchivedRepresentation_notEqual() {
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue metadataNoDigest1 = metadataWithIdNoDigest(1);
    FileArtifactValue metadataNoDigest2 = metadataWithIdNoDigest(2);

    TreeArtifactValue tree1 =
        TreeArtifactValue.newBuilder(parent)
            .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest1)
            .build();
    TreeArtifactValue tree2 =
        TreeArtifactValue.newBuilder(parent)
            .setArchivedRepresentation(archivedTreeArtifact, metadataNoDigest2)
            .build();

    assertThat(metadataNoDigest1.getDigest()).isNull();
    assertThat(metadataNoDigest2.getDigest()).isNull();
    assertThat(tree1.getDigest()).isNotNull();
    assertThat(tree2.getDigest()).isNotNull();
    assertThat(tree1.getDigest()).isNotEqualTo(tree2.getDigest());
  }

  @Test
  public void findChildEntryByExecPath_returnsCorrectEntry() {
    SpecialArtifact tree = createTreeArtifact("bin/tree");
    TreeFileArtifact file1 = TreeFileArtifact.createTreeOutput(tree, "file1");
    FileArtifactValue file1Metadata = metadataWithIdNoDigest(1);
    TreeArtifactValue treeArtifactValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(file1, file1Metadata)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "file2"), metadataWithIdNoDigest(2))
            .build();

    assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create("bin/tree/file1")))
        .isEqualTo(Maps.immutableEntry(file1, file1Metadata));
  }

  @Test
  public void findChildEntryByExecPath_nonExistentChild_returnsNull(
      @TestParameter({"bin/nonexistent", "a_before_nonexistent", "z_after_nonexistent"})
          String nonexistentPath) {
    SpecialArtifact tree = createTreeArtifact("bin/tree");
    TreeArtifactValue treeArtifactValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "file"), metadataWithIdNoDigest(1))
            .build();

    assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create(nonexistentPath)))
        .isNull();
  }

  @Test
  public void findChildEntryByExecPath_emptyTreeArtifactValue_returnsNull() {
    TreeArtifactValue treeArtifactValue = TreeArtifactValue.empty();
    assertThat(treeArtifactValue.findChildEntryByExecPath(PathFragment.create("file"))).isNull();
  }

  @Test
  public void visitTree_visitsEachChild() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file1");
    scratch.file("tree/a/file2");
    scratch.file("tree/a/b/file3");
    scratch.resolve("tree/file_link").createSymbolicLink(PathFragment.create("file1"));
    scratch.resolve("tree/a/dir_link").createSymbolicLink(PathFragment.create("b"));
    List<VisitTreeArgs> children = new ArrayList<>();

    TreeArtifactValue.visitTree(
        treeDir,
        (child, type, traversedSymlink) -> {
          synchronized (children) {
            children.add(VisitTreeArgs.of(child, type, traversedSymlink));
          }
        });

    assertThat(children)
        .containsExactly(
            VisitTreeArgs.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("a/b"), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("file1"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("a/file2"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("a/b/file3"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("file_link"), Dirent.Type.FILE, true),
            VisitTreeArgs.of(PathFragment.create("a/dir_link"), Dirent.Type.DIRECTORY, true),
            VisitTreeArgs.of(PathFragment.create("a/dir_link/file3"), Dirent.Type.FILE, true));
  }

  @Test
  public void visitTree_throwsOnDanglingSymlink() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.resolve("tree/symlink").createSymbolicLink(PathFragment.create("/does_not_exist"));

    Exception e =
        assertThrows(
            IOException.class,
            () -> TreeArtifactValue.visitTree(treeDir, (child, type, traversedSymlink) -> {}));
    assertThat(e).hasMessageThat().contains("child symlink is a dangling symbolic link");
  }

  @Test
  public void visitTree_throwsOnSymlinkLoop() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.resolve("tree/symlink").createSymbolicLink(scratch.resolve(treeDir.asFragment()));

    Exception e =
        assertThrows(
            IOException.class,
            () -> TreeArtifactValue.visitTree(treeDir, (child, type, traversedSymlink) -> {}));
    assertThat(e).hasMessageThat().contains("tree/symlink");
    assertThat(e).hasMessageThat().contains("Too many levels of symbolic links");
  }

  @Test
  public void visitTree_throwsOnUnknownDirentType() throws Exception {
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (path.equals(PathFragment.create("/tree"))) {
              return ImmutableList.of(new Dirent("unknown", Dirent.Type.UNKNOWN));
            }
            return super.readdir(path, followSymlinks);
          }
        };
    Path treeDir = fs.getPath("/tree");

    Exception e =
        assertThrows(
            IOException.class,
            () -> TreeArtifactValue.visitTree(treeDir, (child, type, traversedSymlink) -> {}));
    assertThat(e).hasMessageThat().contains("child unknown has an unsupported type");
  }

  @Test
  public void visitTree_throwsOnSymlinkToSpecialFile() throws Exception {
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          @Nullable
          public FileStatus statIfFound(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (path.equals(PathFragment.create("/tree/sym"))) {
              return new FileStatus() {
                @Override
                public boolean isFile() {
                  return true;
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
                  return true;
                }

                @Override
                public long getLastChangeTime() {
                  return 0;
                }

                @Override
                public long getLastModifiedTime() {
                  return 0;
                }

                @Override
                public long getNodeId() {
                  return 0;
                }

                @Override
                public long getSize() {
                  return 0;
                }
              };
            }
            return super.statIfFound(path, followSymlinks);
          }
        };
    Path treeDir = fs.getPath("/tree");
    treeDir.createDirectory();
    treeDir.getChild("sym").createSymbolicLink(PathFragment.create("/special"));

    Exception e =
        assertThrows(
            IOException.class,
            () -> TreeArtifactValue.visitTree(treeDir, (child, type, traversedSymlink) -> {}));
    assertThat(e).hasMessageThat().contains("child sym has an unsupported type");
  }

  @Test
  public void visitTree_propagatesIoExceptionFromVisitor() throws Exception {
    Path treeDir = scratch.dir("tree");
    IOException e = new IOException("From visitor");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                TreeArtifactValue.visitTree(
                    treeDir,
                    (child, type, traversedSymlink) -> {
                      throw e;
                    }));
    assertThat(thrown).isSameInstanceAs(e);
  }

  @Test
  public void visitTree_permitsUpLevelSymlinkInsideTree() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    scratch.dir("tree/a");
    scratch.resolve("tree/a/up_link").createSymbolicLink(PathFragment.create("../file"));
    List<VisitTreeArgs> children = new ArrayList<>();

    TreeArtifactValue.visitTree(
        treeDir,
        (child, type, traversedSymlink) -> {
          synchronized (children) {
            children.add(VisitTreeArgs.of(child, type, traversedSymlink));
          }
        });

    assertThat(children)
        .containsExactly(
            VisitTreeArgs.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("file"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("a/up_link"), Dirent.Type.FILE, true));
  }

  @Test
  public void visitTree_permitsUpLevelSymlinkOutsideTree() throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    scratch.dir("tree/a");
    scratch.file("other_tree/file");
    scratch
        .resolve("tree/a/uplink")
        .createSymbolicLink(PathFragment.create("../../other_tree/file"));
    List<VisitTreeArgs> children = new ArrayList<>();

    TreeArtifactValue.visitTree(
        treeDir,
        (child, type, traversedSymlink) -> {
          synchronized (children) {
            children.add(VisitTreeArgs.of(child, type, traversedSymlink));
          }
        });

    assertThat(children)
        .containsExactly(
            VisitTreeArgs.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("file"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("a"), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("a/uplink"), Dirent.Type.FILE, true));
  }

  @Test
  public void visitTree_permitsAbsoluteSymlink() throws Exception {
    Path treeDir = scratch.dir("tree");
    Path targetFile = scratch.file("target_file");
    Path targetDir = scratch.dir("target_dir");
    scratch.resolve("tree/absolute_file_link").createSymbolicLink(targetFile.asFragment());
    scratch.resolve("tree/absolute_dir_link").createSymbolicLink(targetDir.asFragment());
    List<VisitTreeArgs> children = new ArrayList<>();

    TreeArtifactValue.visitTree(
        treeDir,
        (child, type, traversedSymlink) -> {
          synchronized (children) {
            children.add(VisitTreeArgs.of(child, type, traversedSymlink));
          }
        });

    assertThat(children)
        .containsExactly(
            VisitTreeArgs.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("absolute_file_link"), Dirent.Type.FILE, true),
            VisitTreeArgs.of(
                PathFragment.create("absolute_dir_link"), Dirent.Type.DIRECTORY, true));
  }

  @Test
  public void visitTree_permitsUplevelSymlinkTraversingOutsideThenBackInsideTree()
      throws Exception {
    Path treeDir = scratch.dir("tree");
    scratch.file("tree/file");
    scratch.resolve("tree/link").createSymbolicLink(PathFragment.create("../tree/file"));

    List<VisitTreeArgs> children = new ArrayList<>();

    TreeArtifactValue.visitTree(
        treeDir,
        (child, type, traversedSymlink) -> {
          synchronized (children) {
            children.add(VisitTreeArgs.of(child, type, traversedSymlink));
          }
        });

    assertThat(children)
        .containsExactly(
            VisitTreeArgs.of(PathFragment.create(""), Dirent.Type.DIRECTORY, false),
            VisitTreeArgs.of(PathFragment.create("file"), Dirent.Type.FILE, false),
            VisitTreeArgs.of(PathFragment.create("link"), Dirent.Type.FILE, true));
  }

  @Test
  public void multiBuilder_empty_injectsNothing() {
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    TreeArtifactValue.newMultiBuilder().forEach(results::put);

    assertThat(results).isEmpty();
  }

  @Test
  public void multiBuilder_injectsSingleTreeArtifact() {
    TreeArtifactValue.MultiBuilder treeArtifacts = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    treeArtifacts
        .putChild(child1, metadataWithId(1))
        .putChild(child2, metadataWithId(2))
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent,
            TreeArtifactValue.newBuilder(parent)
                .putChild(child1, metadataWithId(1))
                .putChild(child2, metadataWithId(2))
                .build());
  }

  @Test
  public void multiBuilder_injectsMultipleTreeArtifacts() {
    TreeArtifactValue.MultiBuilder treeArtifacts = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent1 = createTreeArtifact("bin/tree1");
    TreeFileArtifact parent1Child1 = TreeFileArtifact.createTreeOutput(parent1, "child1");
    TreeFileArtifact parent1Child2 = TreeFileArtifact.createTreeOutput(parent1, "child2");
    SpecialArtifact parent2 = createTreeArtifact("bin/tree2");
    TreeFileArtifact parent2Child = TreeFileArtifact.createTreeOutput(parent2, "child");
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    treeArtifacts
        .putChild(parent1Child1, metadataWithId(1))
        .putChild(parent2Child, metadataWithId(3))
        .putChild(parent1Child2, metadataWithId(2))
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent1,
            TreeArtifactValue.newBuilder(parent1)
                .putChild(parent1Child1, metadataWithId(1))
                .putChild(parent1Child2, metadataWithId(2))
                .build(),
            parent2,
            TreeArtifactValue.newBuilder(parent2)
                .putChild(parent2Child, metadataWithId(3))
                .build());
  }

  @Test
  public void multiBuilder_injectsTreeArtifactWithArchivedRepresentation() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child = TreeFileArtifact.createTreeOutput(parent, "child");
    FileArtifactValue childMetadata = metadataWithId(1);
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue archivedTreeArtifactMetadata = metadataWithId(2);
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder
        .putChild(child, childMetadata)
        .setArchivedRepresentation(archivedTreeArtifact, archivedTreeArtifactMetadata)
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent,
            TreeArtifactValue.newBuilder(parent)
                .putChild(child, childMetadata)
                .setArchivedRepresentation(archivedTreeArtifact, archivedTreeArtifactMetadata)
                .build());
  }

  @Test
  public void multiBuilder_injectsEmptyTreeArtifactWithArchivedRepresentation() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    ArchivedTreeArtifact archivedTreeArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue metadata = metadataWithId(1);
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder.setArchivedRepresentation(archivedTreeArtifact, metadata).forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent,
            TreeArtifactValue.newBuilder(parent)
                .setArchivedRepresentation(archivedTreeArtifact, metadata)
                .build());
  }

  @Test
  public void multiBuilder_injectsTreeArtifactsWithAndWithoutArchivedRepresentation() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent1 = createTreeArtifact("bin/tree1");
    ArchivedTreeArtifact archivedArtifact1 = createArchivedTreeArtifact(parent1);
    FileArtifactValue archivedArtifact1Metadata = metadataWithId(1);
    TreeFileArtifact parent1Child = TreeFileArtifact.createTreeOutput(parent1, "child");
    FileArtifactValue parent1ChildMetadata = metadataWithId(2);
    SpecialArtifact parent2 = createTreeArtifact("bin/tree2");
    TreeFileArtifact parent2Child = TreeFileArtifact.createTreeOutput(parent2, "child");
    FileArtifactValue parent2ChildMetadata = metadataWithId(3);
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder
        .setArchivedRepresentation(archivedArtifact1, archivedArtifact1Metadata)
        .putChild(parent1Child, parent1ChildMetadata)
        .putChild(parent2Child, parent2ChildMetadata)
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent1,
            TreeArtifactValue.newBuilder(parent1)
                .putChild(parent1Child, parent1ChildMetadata)
                .setArchivedRepresentation(archivedArtifact1, metadataWithId(1))
                .build(),
            parent2,
            TreeArtifactValue.newBuilder(parent2)
                .putChild(parent2Child, parent2ChildMetadata)
                .build());
  }

  @Test
  public void multiBuilder_doesNotInjectRemovedValue() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent1 = createTreeArtifact("bin/tree1");
    TreeFileArtifact parent1Child = TreeFileArtifact.createTreeOutput(parent1, "child");
    FileArtifactValue parent1ChildMetadata = metadataWithId(1);
    SpecialArtifact parent2 = createTreeArtifact("bin/tree2");
    TreeFileArtifact parent2Child = TreeFileArtifact.createTreeOutput(parent2, "child");
    FileArtifactValue parent2ChildMetadata = metadataWithId(2);
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder
        .putChild(parent1Child, parent1ChildMetadata)
        .putChild(parent2Child, parent2ChildMetadata)
        .remove(parent1)
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent2,
            TreeArtifactValue.newBuilder(parent2)
                .putChild(parent2Child, parent2ChildMetadata)
                .build());
  }

  @Test
  public void multiBuilder_removeMissingTree_doesNothing() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact missingTree = createTreeArtifact("bin/tree");
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder.remove(missingTree).forEach(results::put);

    assertThat(results).isEmpty();
  }

  @Test
  public void multiBuilder_removeNotATreeArtifact_fails() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact notATreeArtifact =
        SpecialArtifact.create(
            root,
            root.getExecPath().getRelative("bin/artifact"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET);

    assertThrows(IllegalArgumentException.class, () -> builder.remove(notATreeArtifact));
  }

  @Test
  public void multiBuilder_removeAndRecreateValue_injectsValueAfterRemove() {
    TreeArtifactValue.MultiBuilder builder = TreeArtifactValue.newMultiBuilder();
    SpecialArtifact parent = createTreeArtifact("bin/tree");
    TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(parent, "child1");
    FileArtifactValue child1Metadata = metadataWithId(1);
    ArchivedTreeArtifact archivedArtifact = createArchivedTreeArtifact(parent);
    FileArtifactValue archivedArtifactMetadata = metadataWithId(2);
    TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(parent, "child2");
    FileArtifactValue child2Metadata = metadataWithId(3);
    Map<SpecialArtifact, TreeArtifactValue> results = new HashMap<>();

    builder
        .putChild(child1, child1Metadata)
        .setArchivedRepresentation(archivedArtifact, archivedArtifactMetadata)
        .remove(parent)
        .putChild(child2, child2Metadata)
        .forEach(results::put);

    assertThat(results)
        .containsExactly(
            parent, TreeArtifactValue.newBuilder(parent).putChild(child2, child2Metadata).build());
  }

  private static ArchivedTreeArtifact createArchivedTreeArtifact(SpecialArtifact specialArtifact) {
    return ArchivedTreeArtifact.createForTree(specialArtifact);
  }

  private SpecialArtifact createTreeArtifact(String execPath) {
    return createTreeArtifact(execPath, root);
  }

  private static SpecialArtifact createTreeArtifact(String execPath, ArtifactRoot root) {
    return ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        root, PathFragment.create(execPath));
  }

  private static FileArtifactValue metadataWithId(int id) {
    return FileArtifactValue.createForRemoteFile(new byte[] {(byte) id}, id, id);
  }

  private static FileArtifactValue metadataWithIdNoDigest(int id) {
    FileArtifactValue value = spy(FileArtifactValue.class);
    doReturn(null).when(value).getDigest();
    doReturn((long) id).when(value).getModifiedTime();
    return value;
  }
}
