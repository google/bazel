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
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.util.CommandBuilder;
import com.google.devtools.build.lib.util.CommandUtils;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SnapshottingFileSystem;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Helper class responsible for the symlink tree creation. Used to generate runfiles and fileset
 * symlink farms.
 */
public final class SymlinkTreeHelper {
  @VisibleForTesting
  public static final String BUILD_RUNFILES = "build-runfiles" + OsUtils.executableExtension();

  private final Path execRoot;
  private final Path inputManifest;
  private final Path outputManifest;
  private final Path symlinkTreeRoot;
  private final boolean filesetTree;
  private final String workspaceName;

  /**
   * Creates SymlinkTreeHelper instance. Can be used independently of SymlinkTreeAction.
   *
   * @param inputManifest path to the input runfiles manifest
   * @param outputManifest path to the output runfiles manifest
   * @param symlinkTreeRoot path to the root of the symlink tree
   * @param filesetTree true if this is a fileset symlink tree, false if this is a runfiles symlink
   *     tree.
   * @param workspaceName the name of the workspace, used to create the workspace subdirectory
   */
  public SymlinkTreeHelper(
      Path execRoot,
      Path inputManifest,
      Path outputManifest,
      Path symlinkTreeRoot,
      boolean filesetTree,
      String workspaceName) {
    this.execRoot = ensureNonSnapshotting(execRoot);
    this.inputManifest = ensureNonSnapshotting(inputManifest);
    this.outputManifest = ensureNonSnapshotting(outputManifest);
    this.symlinkTreeRoot = ensureNonSnapshotting(symlinkTreeRoot);
    this.filesetTree = filesetTree;
    this.workspaceName = workspaceName;
  }

  private static Path ensureNonSnapshotting(Path path) {
    // Changes made to a file referenced by a symlink tree should be reflected in the symlink tree
    // without having to rebuild. Therefore, if a snapshotting file system is used, we must use the
    // underlying non-snapshotting file system instead to create the symlink tree.
    if (path.getFileSystem() instanceof SnapshottingFileSystem snapshottingFs) {
      return snapshottingFs.getUnderlyingNonSnapshottingFileSystem().getPath(path.asFragment());
    }
    return path;
  }

  interface TargetPathFunction<T> {
    /** Obtains a symlink target path from a T. */
    PathFragment get(T target) throws IOException;
  }

  /** Creates a symlink tree for a fileset by making VFS calls. */
  public void createFilesetSymlinksDirectly(Map<PathFragment, PathFragment> symlinkMap)
      throws IOException {
    createSymlinksDirectly(symlinkMap, (path) -> path);
  }

  /** Creates a symlink tree for a runfiles by making VFS calls. */
  public void createRunfilesSymlinksDirectly(Map<PathFragment, Artifact> symlinkMap)
      throws IOException {
    createSymlinksDirectly(
        symlinkMap,
        (artifact) ->
            artifact.isSymlink()
                // Unresolved symlinks are created textually.
                ? artifact.getPath().readSymbolicLink()
                : artifact.getPath().asFragment());
  }

  /** Creates a symlink tree by making VFS calls. */
  private <T> void createSymlinksDirectly(
      Map<PathFragment, T> symlinkMap, TargetPathFunction<T> targetPathFn) throws IOException {
    // Our strategy is to minimize mutating file system operations as much as possible. Ideally, if
    // there is an existing symlink tree with the expected contents, we don't make any changes. Our
    // algorithm goes as follows:
    //
    // 1. Create a tree structure that represents the entire set of paths that we want to exist. The
    //    tree structure contains a node for every intermediate directory. For example, this is the
    //    tree structure corresponding to the symlinks {"a/b/c": "foobar", "a/d/e": null}:
    //
    //      / b - c (symlink to "foobar")
    //    a
    //      \ d - e (empty file)
    //
    //    Note that we need to distinguish directories, symlinks, and empty files. In the Directory
    //    class below, we use two maps for that purpose: one for directories, and one for symlinks
    //    and empty files. This avoids having to create additional classes / objects to distinguish
    //    them.
    //
    // 2. Perform a depth-first traversal over the on-disk file system tree and make each directory
    //    match our expected directory layout. To that end, call readdir, and compare the result
    //    with the contents of the corresponding node in the in-memory tree.
    //
    //    For each Dirent entry in the readdir result:
    //    - If the entry is not in the current node, if the entry has an incompatible type, or if it
    //      is a symlink that points to the wrong location, delete the entry on disk (recursively).
    //    - Otherwise:
    //      - If the entry is a directory, recurse into that directory
    //      - In all cases, delete the entry in the current in-memory node.
    //
    // 3. For every remaining entry in the node, create the corresponding file, symlink, or
    //    directory on disk. If it is a directory, recurse into that directory.
    try (SilentCloseable c = Profiler.instance().profile("Create symlink tree in-process")) {
      Directory<T> root = new Directory<>();
      for (Map.Entry<PathFragment, T> entry : symlinkMap.entrySet()) {
        // This creates intermediate directory nodes as a side effect.
        Directory<T> parentDir = root.walk(entry.getKey().getParentDirectory());
        parentDir.addSymlink(entry.getKey().getBaseName(), entry.getValue());
      }
      root.syncTreeRecursively(symlinkTreeRoot, targetPathFn);
      createWorkspaceSubdirectory();
    }
  }

  /**
   * Ensures that the runfiles directory is empty except for the symlinked MANIFEST and the
   * workspace subdirectory. This is the expected state with --noenable_runfiles.
   */
  public void clearRunfilesDirectory() throws ExecException {
    deleteRunfilesDirectory();
    linkManifest();
    try {
      createWorkspaceSubdirectory();
    } catch (IOException e) {
      throw new EnvironmentalExecException(e, Code.SYMLINK_TREE_CREATION_IO_EXCEPTION);
    }
  }

  /** Deletes the contents of the runfiles directory. */
  private void deleteRunfilesDirectory() throws ExecException {
    try (SilentCloseable c = Profiler.instance().profile("Clear symlink tree")) {
      symlinkTreeRoot.deleteTreesBelow();
    } catch (IOException e) {
      throw new EnvironmentalExecException(e, Code.SYMLINK_TREE_DELETION_IO_EXCEPTION);
    }
  }

  /** Links the output manifest to the input manifest. */
  private void linkManifest() throws ExecException {
    // Pretend we created the runfiles tree by symlinking the output manifest to the input manifest.
    try {
      symlinkTreeRoot.createDirectoryAndParents();
      outputManifest.delete();
      outputManifest.createSymbolicLink(inputManifest);
    } catch (IOException e) {
      throw new EnvironmentalExecException(e, Code.SYMLINK_TREE_MANIFEST_LINK_IO_EXCEPTION);
    }
  }

  private void createWorkspaceSubdirectory() throws IOException {
    // Always create the subdirectory corresponding to the workspace (i.e., the main repository).
    // This is required by tests as their working directory, even with --noenable_runfiles. But if
    // the test action creates the directory and then proceeds to execute the test spawn, this logic
    // would remove it. For the sake of consistency, always create the directory instead.
    symlinkTreeRoot.getRelative(workspaceName).createDirectory();
  }

  /**
   * Creates a symlink tree using a CommandBuilder. This means that the symlink tree will always be
   * present on the developer's workstation. Useful when running commands locally.
   *
   * <p>Warning: this method REALLY executes the command on the box Bazel is running on, without any
   * kind of synchronization, locking, or anything else.
   */
  public void createSymlinksUsingCommand(
      BinTools binTools, Map<String, String> shellEnvironment, OutErr outErr)
      throws EnvironmentalExecException, InterruptedException {
    try (SilentCloseable c = Profiler.instance().profile("Create symlink tree out-of-process")) {
      Command command = createCommand(binTools, shellEnvironment);
      try {
        if (outErr != null) {
          command.execute(outErr.getOutputStream(), outErr.getErrorStream());
        } else {
          command.execute();
        }
      } catch (CommandException e) {
        throw new EnvironmentalExecException(
            e,
            FailureDetail.newBuilder()
                .setMessage(CommandUtils.describeCommandFailure(true, e))
                .setExecution(
                    Execution.newBuilder().setCode(Code.SYMLINK_TREE_CREATION_COMMAND_EXCEPTION))
                .build());
      }
      try {
        createWorkspaceSubdirectory();
      } catch (IOException e) {
        throw new EnvironmentalExecException(e, Code.SYMLINK_TREE_CREATION_IO_EXCEPTION);
      }
    }
  }

  @VisibleForTesting
  Command createCommand(BinTools binTools, Map<String, String> shellEnvironment) {
    Preconditions.checkNotNull(shellEnvironment);
    List<String> args = Lists.newArrayList();
    args.add(binTools.getEmbeddedPath(BUILD_RUNFILES).asFragment().getPathString());
    args.add("--allow_relative");
    if (filesetTree) {
      args.add("--use_metadata");
    }
    args.add(inputManifest.relativeTo(execRoot).getPathString());
    args.add(symlinkTreeRoot.relativeTo(execRoot).getPathString());
    return new CommandBuilder()
        .addArgs(args)
        .setWorkingDir(execRoot)
        .setEnv(shellEnvironment)
        .build();
  }

  /**
   * Processes a list of fileset symlinks into a map that can be passed to {@link
   * com.google.devtools.build.lib.vfs.OutputService#createSymlinkTree}.
   *
   * <p>By convention, all symlinks are placed under a directory with the given workspace name.
   */
  static ImmutableMap<PathFragment, PathFragment> processFilesetLinks(
      ImmutableList<FilesetOutputSymlink> links, String workspaceName, PathFragment execRoot) {
    PathFragment root = PathFragment.create(workspaceName);
    var symlinks = ImmutableMap.<PathFragment, PathFragment>builderWithExpectedSize(links.size());
    for (FilesetOutputSymlink symlink : links) {
      symlinks.put(root.getRelative(symlink.name()), symlink.reconstituteTargetPath(execRoot));
    }
    // Fileset links are already deduplicated by name in SkyframeFilesetManifestAction.
    return symlinks.buildOrThrow();
  }

  private static final class Directory<T> {
    private final Map<String, T> symlinks = new HashMap<>();
    private final Map<String, Directory<T>> directories = new HashMap<>();

    void addSymlink(String basename, @Nullable T target) {
      symlinks.put(basename, target);
    }

    Directory<T> walk(PathFragment dir) {
      Directory<T> result = this;
      for (String segment : dir.segments()) {
        result = result.directories.computeIfAbsent(segment, unused -> new Directory<>());
      }
      return result;
    }

    void syncTreeRecursively(Path at, TargetPathFunction<T> targetPathFn) throws IOException {
      // This is a reimplementation of the C++ code in build-runfiles.cc. This avoids having to ship
      // a separate native tool to create a few runfiles.
      // TODO(ulfjack): Measure performance.
      FileStatus stat = at.statNullable(Symlinks.FOLLOW);
      if (stat == null) {
        at.createDirectoryAndParents();
      } else if (!stat.isDirectory()) {
        at.deleteTree();
        at.createDirectoryAndParents();
      } else {
        // If the directory already exists, ensure it has appropriate permissions.
        int perms = stat.getPermissions();
        if (perms == -1) {
          at.chmod(0755);
        } else if ((perms & 0700) != 0700) {
          at.chmod(stat.getPermissions() | 0700);
        }
      }
      for (Dirent dirent : at.readdir(Symlinks.NOFOLLOW)) {
        String basename = dirent.getName();
        Path next = at.getChild(basename);
        if (symlinks.containsKey(basename)) {
          T value = symlinks.remove(basename);
          if (value == null) {
            if (dirent.getType() != Dirent.Type.FILE) {
              next.deleteTree();
              FileSystemUtils.createEmptyFile(next);
            }
            // For consistency with build-runfiles.cc, we don't truncate the file if one exists.
          } else {
            // ensureSymbolicLink will replace a symlink that doesn't have the correct target, but
            // everything else needs to be deleted first.
            if (dirent.getType() != Dirent.Type.SYMLINK) {
              next.deleteTree();
            }
            FileSystemUtils.ensureSymbolicLink(next, targetPathFn.get(value));
          }
        } else if (directories.containsKey(basename)) {
          Directory<T> nextDir = directories.remove(basename);
          if (dirent.getType() != Dirent.Type.DIRECTORY) {
            next.deleteTree();
          }
          nextDir.syncTreeRecursively(at.getChild(basename), targetPathFn);
        } else {
          at.getChild(basename).deleteTree();
        }
      }

      for (Map.Entry<String, T> entry : symlinks.entrySet()) {
        Path next = at.getChild(entry.getKey());
        T value = entry.getValue();
        if (value == null) {
          FileSystemUtils.createEmptyFile(next);
        } else {
          FileSystemUtils.ensureSymbolicLink(next, targetPathFn.get(value));
        }
      }
      for (Map.Entry<String, Directory<T>> entry : directories.entrySet()) {
        entry.getValue().syncTreeRecursively(at.getChild(entry.getKey()), targetPathFn);
      }
    }
  }
}
