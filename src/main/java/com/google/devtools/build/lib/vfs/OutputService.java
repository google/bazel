// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.vfs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * An OutputService retains control over the Blaze output tree, and provides a higher level of
 * abstraction compared to the VFS layer.
 *
 * <p>Higher-level facilities include batch statting, cleaning the output tree, creating symlink
 * trees, and out-of-band insertion of metadata into the tree.
 */
public interface OutputService {

  /** Properties of the action file system implementation provided by this output service. */
  enum ActionFileSystemType {

    /** The action file system is disabled. */
    DISABLED,

    /**
     * The action file system implementation is purely in-memory, taking full control of the output
     * base. It's not able to stage remote outputs accessed as inputs by local actions, but is able
     * to do input discovery. Used by Blaze.
     */
    IN_MEMORY_ONLY_FILE_SYSTEM,

    /**
     * The action file system implementation mixes an in-memory and a local file system. It uses the
     * in-memory filesystem for in-process and remote actions, but is also aware of outputs from
     * local actions. It's able to stage remote outputs accessed as inputs by local actions and to
     * do input discovery. Used by Blaze.
     */
    STAGE_REMOTE_FILES_FILE_SYSTEM,

    /**
     * Similar to STAGE_REMOTE_FILES_FILES_SYSTEM, but only constructs output directories as needed
     * by local actions. Used by Blaze.
     */
    STAGE_REMOTE_FILES_ON_DEMAND_FILE_SYSTEM,

    /**
     * The action file system implementation mixes an in-memory and a local file system. It uses the
     * in-memory filesystem for in-process and remote actions, but is also aware of outputs from
     * local actions. It's able to stage remote outputs accessed as inputs by local actions, but
     * unable to do input discovery. Used by Bazel.
     */
    REMOTE_FILE_SYSTEM;

    public boolean inMemoryFileSystem() {
      return this != DISABLED;
    }

    /**
     * Returns true if this service should early prepare the underlying filesystem for every action.
     * This involves deleting old output files and creating directories for the newly-created output
     * files. If false, the output service must handle such tasks itself as needed.
     */
    public boolean shouldDoEagerActionPrep() {
      return this != IN_MEMORY_ONLY_FILE_SYSTEM && this != STAGE_REMOTE_FILES_ON_DEMAND_FILE_SYSTEM;
    }

    /**
     * Returns true if this service needs top-level output tree setup. This involves creating
     * symlinks to the source tree in the execRoot and constructing a directory for action logging.
     */
    public boolean shouldDoTopLevelOutputSetup() {
      return this != IN_MEMORY_ONLY_FILE_SYSTEM;
    }

    public boolean supportsInputDiscovery() {
      return this != REMOTE_FILE_SYSTEM;
    }

    public boolean isEnabled() {
      return this != DISABLED;
    }
  }

  /**
   * Returns the name of the filesystem used by this output service, akin to what you might see in
   * /proc/mounts.
   *
   * @param outputBaseFileSystemName from {@link
   *     com.google.devtools.build.lib.runtime.BlazeWorkspace#getOutputBaseFilesystemTypeName()}
   */
  String getFileSystemName(String outputBaseFileSystemName);

  /** Whether actions can only be executed locally. */
  default boolean isLocalOnly() {
    return false;
  }

  /** Returns true if remote output metadata should be stored in action cache. */
  default boolean shouldStoreRemoteOutputMetadataInActionCache() {
    return false;
  }

  default OutputChecker getOutputChecker() {
    return OutputChecker.TRUST_ALL;
  }

  /**
   * Starts the build.
   *
   * @param buildId the build identifier
   * @param workspaceName the name of the workspace in which the build is running
   * @param eventHandler an {@link EventHandler} to inform of events
   * @param finalizeActions whether this build is finalizing actions so that the output service can
   *     track output tree modifications
   * @return a ModifiedFileSet of changed output files.
   * @throws BuildFailedException if build preparation failed
   */
  ModifiedFileSet startBuild(
      UUID buildId, String workspaceName, EventHandler eventHandler, boolean finalizeActions)
      throws BuildFailedException, AbruptExitException, InterruptedException;

  /** Flush and wait for in-progress downloads. */
  default void flushOutputTree() throws InterruptedException {}

  /**
   * Finish the build.
   *
   * @param buildSuccessful iff build was successful
   * @throws BuildFailedException on failure
   */
  void finalizeBuild(boolean buildSuccessful)
      throws BuildFailedException, AbruptExitException, InterruptedException;

  /** Notify the output service of a completed action. */
  void finalizeAction(Action action, OutputMetadataStore outputMetadataStore)
      throws IOException, EnvironmentalExecException, InterruptedException;

  @Nullable
  BatchStat getBatchStatter();

  /** Returns true iff {@link #createSymlinkTree} is available. */
  boolean canCreateSymlinkTree();

  /**
   * Creates a symlink tree.
   *
   * @param symlinks map from {@code symlinkTreeRoot}-relative path to symlink target; may contain
   *     null values to represent an empty file instead of a symlink (can happen with {@code
   *     __init__.py} files, see {@link
   *     com.google.devtools.build.lib.rules.python.PythonUtils.GetInitPyFiles})
   * @param symlinkTreeRoot the symlink tree root, relative to the exec root
   * @throws ExecException on failure
   */
  void createSymlinkTree(Map<PathFragment, PathFragment> symlinks, PathFragment symlinkTreeRoot)
      throws ExecException, InterruptedException;

  /**
   * Cleans the entire output tree.
   *
   * @throws ExecException on failure
   */
  void clean() throws ExecException, InterruptedException;

  default ActionFileSystemType actionFileSystemType() {
    return ActionFileSystemType.DISABLED;
  }

  /**
   * Returns an action-scoped filesystem if {@link #actionFileSystemType} is enabled.
   *
   * @param delegateFileSystem the actual underlying filesystem
   * @param execRootFragment absolute path fragment pointing to the execution root
   * @param relativeOutputPath execution root relative path to output
   * @param sourceRoots list of directories on the package path (from {@link
   *     com.google.devtools.build.lib.pkgcache.PathPackageLocator})
   * @param inputArtifactData information about required inputs to the action
   * @param outputArtifacts required outputs of the action
   * @param rewindingEnabled whether to track failed remote reads to enable action rewinding
   */
  @Nullable
  default FileSystem createActionFileSystem(
      FileSystem delegateFileSystem,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ImmutableList<Root> sourceRoots,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputArtifacts,
      boolean rewindingEnabled) {
    return null;
  }

  /**
   * Updates the context used by the filesystem returned by {@link #createActionFileSystem}.
   *
   * <p>Should be called as context changes throughout action execution.
   *
   * @param actionFileSystem must be a filesystem returned by {@link #createActionFileSystem}.
   * @param filesets The Fileset symlinks known for this action.
   */
  default void updateActionFileSystemContext(
      ActionExecutionMetadata action,
      FileSystem actionFileSystem,
      Environment env,
      OutputMetadataStore outputMetadataStore,
      ImmutableMap<Artifact, FilesetOutputTree> filesets) {}

  /**
   * Checks the filesystem returned by {@link #createActionFileSystem} for errors attributable to
   * lost inputs.
   */
  default void checkActionFileSystemForLostInputs(FileSystem actionFileSystem, Action action)
      throws LostInputsActionExecutionException {}

  default boolean supportsPathResolverForArtifactValues() {
    return false;
  }

  default ArtifactPathResolver createPathResolverForArtifactValues(
      PathFragment execRoot,
      String relativeOutputPath,
      FileSystem fileSystem,
      ImmutableList<Root> pathEntries,
      ActionInputMap actionInputMap,
      Map<Artifact, ImmutableSortedSet<TreeFileArtifact>> treeArtifacts,
      Map<Artifact, FilesetOutputTree> filesets) {
    throw new IllegalStateException("Path resolver not supported by this class");
  }

  @Nullable
  default BulkDeleter bulkDeleter() {
    return null;
  }

  default XattrProvider getXattrProvider(XattrProvider delegate) {
    return delegate;
  }
}
