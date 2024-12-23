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
package com.google.devtools.build.lib.worker;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.sandbox.SynchronousTreeDeleter;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkerExecRoot}. */
@RunWith(JUnit4.class)
public class WorkerExecRootTest {

  /** The global execroot directory, where real files live. */
  private Path execRoot;
  /** The {@code workDir} directory of the worker. This is the CWD of the worker process. */
  private Path workDir;

  @Before
  public final void setupTestDirs() throws IOException {
    FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path testRoot = fileSystem.getPath(TestUtils.tmpDir());
    testRoot.createDirectoryAndParents();

    execRoot = testRoot.getRelative("workspace");
    execRoot.createDirectory();
    workDir = execRoot.getRelative("sandbox/execroot/__main__");
    workDir.createDirectoryAndParents();
  }

  @Test
  public void cleanFileSystem() throws Exception {
    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .addAndCreateInputFile("worker.sh", "worker.sh", "#!/bin/bash")
            .addOutput("very/output.txt")
            .addWorkerFile("worker.sh");
    Path workerSh = execRoot.getRelative("worker.sh");

    WorkerExecRoot workerExecRoot = new WorkerExecRoot(workDir, ImmutableList.of(), false);
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    // Pretend to do some work inside the execRoot.
    workDir.getRelative("tempdir").createDirectory();
    FileSystemUtils.createEmptyFile(workDir.getRelative("very/output.txt"));
    FileSystemUtils.createEmptyFile(workDir.getRelative("temp.txt"));
    // Modify the worker.sh so that we're able to detect whether it gets rewritten or not.
    FileSystemUtils.writeContent(workerSh, "#!/bin/sh");

    // Reuse the same execRoot.
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(workDir.getRelative("worker.sh").exists()).isTrue();
    assertThat(FileSystemUtils.readContentToString(workDir.getRelative("worker.sh")))
        .isEqualTo("#!/bin/sh");
    assertThat(workDir.getRelative("tempdir").exists()).isFalse();
    assertThat(workDir.getRelative("very/output.txt").exists()).isFalse();
    assertThat(workDir.getRelative("temp.txt").exists()).isFalse();
  }

  @Test
  public void createsAndCleansInputSymlinks() throws Exception {
    // Simulate existing symlinks in the exec root to check that `WorkerExecRoot` correctly deletes
    // the unnecessary ones and updates the ones that don't point to the right target.
    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .createSymlink("dir/input_symlink_1", "old_content")
            .createSymlink("dir/input_symlink_2", "unchanged")
            .createSymlink("dir/input_symlink_3", "whatever")
            .addSymlink("dir/input_symlink_1", "new_content")
            .addSymlink("dir/input_symlink_2", "unchanged");

    WorkerExecRoot workerExecRoot = new WorkerExecRoot(workDir, ImmutableList.of(), false);

    // This should update the `input_symlink_{1,2,3}` according to `SandboxInputs`, i.e., update the
    // first/second (alternatively leave the second unchanged) and delete the third.
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(workDir.getRelative("dir/input_symlink_1").readSymbolicLink())
        .isEqualTo(PathFragment.create("new_content"));
    assertThat(workDir.getRelative("dir/input_symlink_2").readSymbolicLink())
        .isEqualTo(PathFragment.create("unchanged"));
    assertThat(workDir.getRelative("dir/input_symlink_3").exists()).isFalse();
  }

  @Test
  public void createsOutputDirs() throws Exception {
    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .addOutput("dir/foo/bar_kt.jar")
            .addOutput("dir/foo/bar_kt.jdeps")
            .addOutput("dir/foo/bar_kt-sources.jar")
            .addOutputDir("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_sourcegenfiles")
            .addOutputDir("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_classes")
            .addOutputDir("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_temp")
            .addOutputDir("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_generated_classes");
    WorkerExecRoot workerExecRoot = new WorkerExecRoot(workDir, ImmutableList.of(), false);
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(workDir.getRelative("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_sourcegenfiles").exists())
        .isTrue();
    assertThat(workDir.getRelative("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_classes").exists()).isTrue();
    assertThat(workDir.getRelative("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_temp").exists()).isTrue();
    assertThat(workDir.getRelative("dir/foo/_kotlinc/bar_kt_jvm/bar_kt_generated_classes").exists())
        .isTrue();
  }

  @Test
  public void workspaceFilesAreNotDeleted() throws Exception {
    // We want to check that `WorkerExecRoot` deletes pre-existing symlinks if they're not in the
    // `SandboxInputs`, but it does not delete the files that the symlinks point to (i.e., the files
    // in the workspace directory).
    Path neededWorkspaceFile = execRoot.getRelative("needed_file");
    Path otherWorkspaceFile = execRoot.getRelative("other_file");

    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .addInputFile("needed_file", "needed_file")
            .createWorkspaceDirFile("needed_file", "needed workspace content")
            .createWorkspaceDirFile("other_file", "other workspace content")
            .createSymlink("needed_file", neededWorkspaceFile.getPathString())
            .createSymlink("other_file", otherWorkspaceFile.getPathString());

    WorkerExecRoot workerExecRoot = new WorkerExecRoot(workDir, ImmutableList.of(), false);
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(workDir.getRelative("needed_file").readSymbolicLink())
        .isEqualTo(neededWorkspaceFile.asFragment());
    assertThat(workDir.getRelative("other_file").exists()).isFalse();

    assertThat(FileSystemUtils.readContentToString(neededWorkspaceFile))
        .isEqualTo("needed workspace content");
    assertThat(FileSystemUtils.readContentToString(otherWorkspaceFile))
        .isEqualTo("other workspace content");
  }

  @Test
  public void recreatesEmptyFiles() throws Exception {
    // Simulate existing non-empty file in the exec root to check that `WorkerExecRoot` will clear
    // the contents as requested by `SandboxInputs`.
    // This is interesting, because the filepath is a key in `SandboxInputs`, but its value is
    // `null`, which means "create an empty file". So after `createFileSystem` the file should be
    // empty.
    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .createExecRootFile("some_file", "some content")
            .addInputFile("some_file", null);

    WorkerExecRoot workerExecRoot =
        new WorkerExecRoot(sandboxHelper.workDir, ImmutableList.of(), false);
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(FileSystemUtils.readContentToString(workDir.getRelative("some_file"))).isEmpty();
  }

  @Test
  public void createsAndDeletesSiblingExternalRepoFiles() throws Exception {
    // With the sibling repository layout, external repository source files are no longer symlinked
    // under <execroot>/external/<repo name>/<path>. Instead, they become *siblings* of the main
    // workspace files in that they're placed at <execroot>/../<repo name>/<path>. Simulate this
    // layout and check if inputs are correctly created and irrelevant symlinks are deleted.

    Path fooRepoDir = execRoot.getRelative("external_dir/foo");
    Path input1 = fooRepoDir.getRelative("bar/input1");
    Path input2 = fooRepoDir.getRelative("input2");
    Path random = fooRepoDir.getRelative("bar/random");

    SandboxHelper sandboxHelper =
        new SandboxHelper(execRoot, workDir)
            .createWorkspaceDirFile("external_dir/foo/bar/input1", "This is input1.")
            .createWorkspaceDirFile("external_dir/foo/input2", "This is input2.")
            .createWorkspaceDirFile("external_dir/foo/bar/random", "This is random.")
            .createSymlink("../foo/bar/input1", input1.getPathString())
            .createSymlink("../foo/bar/random", random.getPathString())
            .addInputFile("../foo/bar/input1", input1.getPathString())
            .addInputFile("../foo/input2", input2.getPathString());

    WorkerExecRoot workerExecRoot = new WorkerExecRoot(workDir, ImmutableList.of(), false);
    workerExecRoot.createFileSystem(
        sandboxHelper.getWorkerFiles(),
        sandboxHelper.getSandboxInputs(),
        sandboxHelper.getSandboxOutputs(),
        new SynchronousTreeDeleter());

    assertThat(workDir.getRelative("../foo/bar/input1").readSymbolicLink())
        .isEqualTo(input1.asFragment());
    assertThat(workDir.getRelative("../foo/input2").readSymbolicLink())
        .isEqualTo(input2.asFragment());
    assertThat(workDir.getRelative("bar/random").exists()).isFalse();
  }

}
