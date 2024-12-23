// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class that sets up a sandbox in a more comprehensible way. Handles setting up
 * SandboxInputs and SandboxOutputs as well as creating related files.
 */
class SandboxHelper {

  /** Map from workdir-relative input path to optional real file path. */
  private final Map<PathFragment, Path> inputs = new HashMap<>();

  private final Map<VirtualActionInput, byte[]> virtualInputs = new HashMap<>();
  private final Map<PathFragment, PathFragment> symlinks = new HashMap<>();
  private final Map<PathFragment, Path> workerFiles = new HashMap<>();
  private final List<PathFragment> outputFiles = new ArrayList<>();
  private final List<PathFragment> outputDirs = new ArrayList<>();

  /** The global execRoot. */
  final Path execRoot;

  /** The worker process's sandbox root. */
  final Path workDir;

  public SandboxHelper(Path execRoot, Path workDir) {
    this.execRoot = execRoot;
    this.workDir = workDir;
  }

  /**
   * Adds a regular input file at relativePath under {@code workDir}, with the real file at {@code
   * workspacePath} under {@code execRoot}.
   */
  @CanIgnoreReturnValue
  public SandboxHelper addInputFile(String relativePath, String workspacePath) {
    inputs.put(
        PathFragment.create(relativePath),
        workspacePath != null ? execRoot.getRelative(workspacePath) : null);
    return this;
  }

  /**
   * Adds a regular input file at relativePath under the {@code workDir}, with the real file at
   * {@code workspacePath} under {@code execRoot}. The real file gets created immediately and filled
   * with {@code contents}, which is assumed to be ASCII text.
   */
  @CanIgnoreReturnValue
  public SandboxHelper addAndCreateInputFile(
      String relativePath, String workspacePath, String contents) throws IOException {
    addInputFile(relativePath, workspacePath);
    Path absPath = execRoot.getRelative(workspacePath);
    absPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(absPath, contents);
    return this;
  }

  /** Adds a virtual input with some contents, which is assumed to be ASCII text. */
  @CanIgnoreReturnValue
  public SandboxHelper addAndCreateVirtualInput(String relativePath, String contents) {
    VirtualActionInput input = ActionsTestUtil.createVirtualActionInput(relativePath, contents);
    byte[] digest =
        execRoot
            .getRelative(relativePath)
            .getFileSystem()
            .getDigestFunction()
            .getHashFunction()
            .hashString(contents, UTF_8)
            .asBytes();
    virtualInputs.put(input, digest);
    return this;
  }

  /** Adds a symlink to the inputs. */
  @CanIgnoreReturnValue
  public SandboxHelper addSymlink(String relativePath, String linkTo) {
    symlinks.put(PathFragment.create(relativePath), PathFragment.create(linkTo));
    return this;
  }

  /** Adds an output file without creating it. */
  @CanIgnoreReturnValue
  public SandboxHelper addOutput(String relativePath) {
    outputFiles.add(PathFragment.create(relativePath));
    return this;
  }

  /** Adds an output directory without creating it. */
  @CanIgnoreReturnValue
  public SandboxHelper addOutputDir(String relativePath) {
    outputDirs.add(
        PathFragment.create(relativePath.endsWith("/") ? relativePath : relativePath + "/"));
    return this;
  }

  /**
   * Adds a worker file that is created under {@code execRoot} and referenced under the {@code
   * workDir}.
   */
  @CanIgnoreReturnValue
  public SandboxHelper addWorkerFile(String relativePath) {
    Path absPath = execRoot.getRelative(relativePath);
    workerFiles.put(PathFragment.create(relativePath), absPath);
    return this;
  }

  /**
   * Adds a worker file that is created under {@code execRoot} and referenced under the {@code
   * workDir}. Writes the content, which is assumed to be ASCII text, under {@code execRoot}.
   */
  @CanIgnoreReturnValue
  public SandboxHelper addAndCreateWorkerFile(String relativePath, String contents)
      throws IOException {
    addWorkerFile(relativePath);
    Path absPath = execRoot.getRelative(relativePath);
    absPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(absPath, contents);
    return this;
  }

  /**
   * Creates a file with {@code contents}, which is assumed to be ASCII text, at {@code relPath}
   * under the {@code workDir}.
   */
  @CanIgnoreReturnValue
  public SandboxHelper createExecRootFile(String relativePath, String contents) throws IOException {
    Path absPath = workDir.getRelative(relativePath);
    absPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(absPath, contents);
    return this;
  }

  /**
   * Creates a file with {@code contents}, which is assumed to be ASCII text, at {@code relPath}
   * under the {@code workDir}.
   */
  @CanIgnoreReturnValue
  public SandboxHelper createWorkspaceDirFile(String workspaceDirPath, String contents)
      throws IOException {
    Path absPath = execRoot.getRelative(workspaceDirPath);
    absPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(absPath, contents);
    return this;
  }

  /**
   * Creates a symlink from within the {@code workDir}. The destination is just what's written into
   * the symlink and thus relative to the created symlink.
   */
  @CanIgnoreReturnValue
  public SandboxHelper createSymlink(String relativePath, String relativeDestination)
      throws IOException {
    Path fromPath = workDir.getRelative(relativePath);
    FileSystemUtils.ensureSymbolicLink(fromPath, relativeDestination);
    return this;
  }

  public SandboxInputs getSandboxInputs() {
    return new SandboxInputs(inputs, virtualInputs, symlinks);
  }

  public SandboxOutputs getSandboxOutputs() {
    return SandboxOutputs.create(
        ImmutableSet.copyOf(this.outputFiles), ImmutableSet.copyOf(this.outputDirs));
  }

  public ImmutableSet<PathFragment> getWorkerFiles() {
    return ImmutableSet.copyOf(workerFiles.keySet());
  }
}
