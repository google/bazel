// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.WORKSPACE_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SymlinkTreeHelper}. */
@RunWith(TestParameterInjector.class)
public final class SymlinkTreeHelperTest {

  private enum TreeType {
    RUNFILES,
    FILESET
  }

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fs.getPath("/execroot");
  private final ArtifactRoot outputRoot =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");

  @Before
  public void setUp() throws Exception {
    outputRoot.getRoot().asPath().createDirectoryAndParents();
  }

  @Test
  public void checkCreatedSpawn() {
    Path execRoot = fs.getPath("/my/workspace");
    Path inputManifestPath = execRoot.getRelative("input_manifest");
    Path outputManifestPath = execRoot.getRelative("output/MANIFEST");
    Path symlinkTreeRoot = execRoot.getRelative("output");
    BinTools binTools =
        BinTools.forUnitTesting(execRoot, ImmutableList.of(SymlinkTreeHelper.BUILD_RUNFILES));
    Command command =
        new SymlinkTreeHelper(
                execRoot, inputManifestPath, outputManifestPath, symlinkTreeRoot, false, "__main__")
            .createCommand(binTools, ImmutableMap.of());
    assertThat(command.getEnvironment()).isEmpty();
    assertThat(command.getWorkingDirectory()).isEqualTo(execRoot.getPathFile());
    ImmutableList<String> commandLine = command.getArguments();
    assertThat(commandLine).hasSize(4);
    assertThat(commandLine.get(0)).endsWith(SymlinkTreeHelper.BUILD_RUNFILES);
    assertThat(commandLine.get(1)).isEqualTo("--allow_relative");
    assertThat(commandLine.get(2)).isEqualTo("input_manifest");
    assertThat(commandLine.get(3)).isEqualTo("output");
  }

  @Test
  public void readManifest() {
    PathFragment execRoot = PathFragment.create("/my/workspace");

    FilesetOutputSymlink link =
        FilesetOutputSymlink.createForTesting(
            PathFragment.create("from"), PathFragment.create("to"), execRoot);

    Map<PathFragment, PathFragment> symlinks =
        SymlinkTreeHelper.processFilesetLinks(ImmutableList.of(link), "workspace", execRoot);
    assertThat(symlinks)
        .containsExactly(PathFragment.create("workspace/from"), PathFragment.create("to"));
  }

  @Test
  public void readMultilineManifest() {
    PathFragment execRoot = PathFragment.create("/my/workspace");

    FilesetOutputSymlink link1 =
        FilesetOutputSymlink.createForTesting(
            PathFragment.create("from"), PathFragment.create("to"), execRoot);
    FilesetOutputSymlink link2 =
        FilesetOutputSymlink.createForTesting(
            PathFragment.create("foo"), PathFragment.create("/bar"), execRoot);
    FilesetOutputSymlink link3 =
        FilesetOutputSymlink.createAlreadyRelativizedForTesting(
            PathFragment.create("rel"), PathFragment.create("path"), true);
    FilesetOutputSymlink link4 =
        FilesetOutputSymlink.createAlreadyRelativizedForTesting(
            PathFragment.create("rel2"), PathFragment.create("/path"), false);

    Map<PathFragment, PathFragment> symlinks =
        SymlinkTreeHelper.processFilesetLinks(
            ImmutableList.of(link1, link2, link3, link4), "workspace2", execRoot);
    assertThat(symlinks)
        .containsExactly(
            PathFragment.create("workspace2/from"),
            PathFragment.create("to"),
            PathFragment.create("workspace2/foo"),
            PathFragment.create("/bar"),
            PathFragment.create("workspace2/rel"),
            execRoot.getRelative("path"),
            PathFragment.create("workspace2/rel2"),
            PathFragment.create("/path"));
  }

  @Test
  public void createSymlinksDirectly(
      @TestParameter TreeType treeType, @TestParameter boolean replace) throws Exception {
    Path treeRoot = execRoot.getRelative("foo.runfiles");
    Path inputManifestPath = execRoot.getRelative("foo.runfiles_manifest");
    Path outputManifestPath = execRoot.getRelative("foo.runfiles/MANIFEST");
    SymlinkTreeHelper helper =
        new SymlinkTreeHelper(
            execRoot, inputManifestPath, outputManifestPath, treeRoot, false, WORKSPACE_NAME);

    Artifact file = ActionsTestUtil.createArtifact(outputRoot, "file");
    Artifact symlink = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputRoot, "symlink");

    FileSystemUtils.writeContent(file.getPath(), UTF_8, "content");
    FileSystemUtils.ensureSymbolicLink(symlink.getPath(), "/path/to/target");

    Path treeWorkspace = treeRoot.getRelative(WORKSPACE_NAME);
    Path treeEmpty = treeWorkspace.getRelative("empty");
    Path treeFile = treeWorkspace.getRelative("file");
    Path treeSymlink = treeWorkspace.getRelative("symlink");
    Path treeMissing = treeWorkspace.getRelative("missing");

    if (replace) {
      treeEmpty.createDirectoryAndParents();
      treeFile.createDirectoryAndParents();
      treeSymlink.createDirectoryAndParents();
      treeMissing.createDirectoryAndParents();
      treeWorkspace.chmod(000);
    }

    switch (treeType) {
      case RUNFILES -> {
        HashMap<PathFragment, Artifact> symlinkMap = new HashMap<>();
        symlinkMap.put(PathFragment.create(WORKSPACE_NAME + "/empty"), null);
        symlinkMap.put(PathFragment.create(WORKSPACE_NAME + "/file"), file);
        symlinkMap.put(PathFragment.create(WORKSPACE_NAME + "/symlink"), symlink);

        helper.createRunfilesSymlinksDirectly(symlinkMap);
      }
      case FILESET -> {
        HashMap<PathFragment, PathFragment> symlinkMap = new HashMap<>();
        symlinkMap.put(PathFragment.create(WORKSPACE_NAME + "/empty"), null);
        symlinkMap.put(PathFragment.create(WORKSPACE_NAME + "/file"), file.getPath().asFragment());
        symlinkMap.put(
            PathFragment.create(WORKSPACE_NAME + "/symlink"),
            PathFragment.create("/path/to/target"));

        helper.createFilesetSymlinksDirectly(symlinkMap);
      }
    }

    assertThat(treeRoot.isDirectory()).isTrue();
    assertThat(treeWorkspace.isDirectory()).isTrue();
    assertThat(treeEmpty.isFile()).isTrue();
    assertThat(FileSystemUtils.readContent(treeEmpty)).isEmpty();
    assertThat(treeFile.isSymbolicLink()).isTrue();
    assertThat(treeFile.readSymbolicLink()).isEqualTo(file.getPath().asFragment());
    assertThat(treeSymlink.isSymbolicLink()).isTrue();
    assertThat(treeSymlink.readSymbolicLink()).isEqualTo(PathFragment.create("/path/to/target"));
    assertThat(treeMissing.exists()).isFalse();
  }
}
