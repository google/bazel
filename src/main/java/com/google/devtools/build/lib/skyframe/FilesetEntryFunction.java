// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.FilesetTraversalParams;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversal;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.RecursiveFilesystemTraversalException;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalValue.ResolvedFile;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.annotation.Nullable;

/** SkyFunction for {@link FilesetEntryValue}. */
public final class FilesetEntryFunction implements SkyFunction {

  private static final class FilesetEntryFunctionException extends SkyFunctionException {
    FilesetEntryFunctionException(RecursiveFilesystemTraversalException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  private final Function<String, Path> getExecRoot;

  public FilesetEntryFunction(Function<String, Path> getExecRoot) {
    this.getExecRoot = getExecRoot;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey key, Environment env)
      throws FilesetEntryFunctionException, InterruptedException {
    WorkspaceNameValue workspaceNameValue =
        (WorkspaceNameValue) env.getValue(WorkspaceNameValue.key());
    if (workspaceNameValue == null) {
      return null;
    }

    FilesetTraversalParams params = checkParams((FilesetEntryKey) key);

    RecursiveFilesystemTraversalValue traversalValue =
        (RecursiveFilesystemTraversalValue) env.getValue(FilesetTraversalRequest.create(params));
    if (traversalValue == null) {
      return null;
    }

    // The root can only be absent for the EMPTY RecursiveFilesystemTraversalValue instance.
    if (traversalValue.getResolvedRoot().isEmpty()) {
      return FilesetEntryValue.EMPTY;
    }

    ResolvedFile resolvedRoot = traversalValue.getResolvedRoot().get();

    // Handle dangling symlinks gracefully be returning empty results.
    if (!resolvedRoot.getType().exists()) {
      return FilesetEntryValue.EMPTY;
    }

    // Check if directory traversal is permitted
    if (resolvedRoot.getType().isDirectory() && !params.getDirectTraversal().permitDirectories()) {
      throw new FilesetEntryFunctionException(
          new RecursiveFilesystemTraversalException(
              String.format(
                  "%s contains a directory artifact '%s'",
                  params.getOwnerLabelForErrorMessages(), params.getDestPath()),
              RecursiveFilesystemTraversalException.Type.CANNOT_TRAVERSE_SOURCE_DIRECTORY));
    }

    // The "direct" traversal params are present, which is the case when the FilesetEntry
    // specifies a package's BUILD file, a directory or a list of files.

    // The root of the direct traversal is defined as follows.
    //
    // If FilesetEntry.files is specified, then a TraversalRequest is created for each entry, the
    // root being the respective entry itself. These are all traversed for they may be
    // directories or symlinks to directories, and we need to establish Skyframe dependencies on
    // their contents for incremental correctness. If an entry is indeed a directory (but not when
    // it's a symlink to one) then we have to create symlinks to each of their children.
    // (NB: there seems to be no good reason for this, it's just how legacy Fileset works. We may
    // want to consider creating a symlink just for the directory and not for its child elements.)
    //
    // If FilesetEntry.files is not specified, then srcdir refers to either a BUILD file or a
    // directory. For the former, the root will be the parent of the BUILD file. For the latter,
    // the root will be srcdir itself.
    DirectTraversal direct = params.getDirectTraversal();

    // The prefix to remove is the entire path of the root. This is OK:
    // - when the root is a file, this removes the entire path, but the traversal's destination
    //   path is actually the name of the output symlink, so this works out correctly
    // - when the root is a directory or a symlink to one then we need to strip off the
    //   directory's path from every result (this is how the output symlinks must be created)
    //   before making them relative to the destination path
    PathFragment prefixToRemove = direct.getRoot().getRelativePart();

    Iterable<ResolvedFile> results;
    if (resolvedRoot.getType().isDirectory() && !resolvedRoot.getType().isSymlink()) {
      // The traversal is recursive (requested for an entire FilesetEntry.srcdir) or it was
      // requested for a FilesetEntry.files entry which turned out to be a directory. We need to
      // create an output symlink for every file in it and all of its subdirectories. Only
      // exception is when the subdirectory is really a symlink to a directory -- no output
      // shall be created for the contents of those.
      // Now we create Dir objects to model the filesystem tree. The object employs a trick to
      // find directory symlinks: directory symlinks have corresponding ResolvedFile entries and
      // are added as files too, while their children, also added as files, contain the path of
      // the parent. Finding and discarding the children is easy if we traverse the tree from
      // root to leaf.
      DirectoryTree root = new DirectoryTree();
      for (ResolvedFile f : traversalValue.getTransitiveFiles().toList()) {
        PathFragment path = f.getNameInSymlinkTree().relativeTo(prefixToRemove);
        if (!path.isEmpty()) {
          path = params.getDestPath().getRelative(path);
          DirectoryTree dir = root;
          for (String segment : path.getParentDirectory().segments()) {
            dir = dir.addOrGetSubdir(segment);
          }
          dir.maybeAddFile(f);
        }
      }
      // Here's where the magic happens. The returned iterable will yield all files in the
      // directory that are not under symlinked directories, as well as all directory symlinks.
      results = root.iterateFiles();
    } else {
      // If we're on this branch then the traversal was done for just one entry in
      // FilesetEntry.files (which was not a directory, so it was either a file, a symlink to one
      // or a symlink to a directory), meaning we'll have only one output symlink.
      results = ImmutableList.of(resolvedRoot);
    }

    // The map of output symlinks. Each key is the path of an output symlink that the Fileset must
    // create, relative to the Fileset.out directory, and each value specifies extra information
    // about the link (its target, associated metadata, and again its name).
    Map<PathFragment, FilesetOutputSymlink> outputSymlinks = new LinkedHashMap<>();

    SpecialArtifact enclosingTreeArtifact = getTreeArtifactOrNull(params);

    // Create one output symlink for each entry in the results.
    for (ResolvedFile f : results) {
      // The linkName has to be under the traversal's root, which is also the prefix to remove.
      PathFragment linkName = f.getNameInSymlinkTree().relativeTo(prefixToRemove);

      // Check whether the symlink is excluded before attempting to resolve it. It may be dangling,
      // but excluding it is still fine. Only top-level files can be excluded, i.e. ones that are
      // directly under the root if the root is a directory. Matching on getSegment(0) is sufficient
      // to satisfy this, since any specified exclusions with multiple segments will never match.
      // TODO(b/64754128): Investigate if we could have made the exclude earlier before
      //                   unnecessarily iterating over all the files in an excluded directory.
      if (!linkName.isEmpty() && params.getExcludedFiles().contains(linkName.getSegment(0))) {
        continue;
      }

      PathFragment targetName = f.getPath().asPath().asFragment();
      maybeStoreSymlink(
          linkName,
          targetName,
          f.getMetadata(),
          params.getDestPath(),
          outputSymlinks,
          getExecRoot.apply(workspaceNameValue.getName()),
          enclosingTreeArtifact);
    }

    return FilesetEntryValue.of(ImmutableSet.copyOf(outputSymlinks.values()));
  }

  @Nullable
  private static SpecialArtifact getTreeArtifactOrNull(FilesetTraversalParams params) {
    DirectTraversal direct = params.getDirectTraversal();
    if (direct == null) {
      return null;
    }
    Artifact artifact = direct.getRoot().getOutputArtifact();
    return artifact instanceof SpecialArtifact special && special.isTreeArtifact() ? special : null;
  }

  /** Stores an output symlink unless it would overwrite an existing one. */
  private static void maybeStoreSymlink(
      PathFragment linkName,
      PathFragment linkTarget,
      HasDigest metadata,
      PathFragment destPath,
      Map<PathFragment, FilesetOutputSymlink> result,
      Path execRoot,
      @Nullable SpecialArtifact enclosingTreeArtifact) {
    linkName = destPath.getRelative(linkName);
    if (!result.containsKey(linkName)) {
      result.put(
          linkName,
          FilesetOutputSymlink.create(
              linkName, linkTarget, metadata, execRoot.asFragment(), enclosingTreeArtifact));
    }
  }

  /**
   * Returns the {@link TraversalRequest} node used to compute the Skyframe value for {@code
   * filesetEntryKey}. Should only be called to determine which nodes need to be rewound, and only
   * when {@code DirectTraversal.isGenerated()}.
   */
  public static TraversalRequest getDependencyForRewinding(FilesetEntryKey key) {
    FilesetTraversalParams params = checkParams(key);
    checkState(
        params.getDirectTraversal().isGenerated(),
        "Rewinding is only supported for outputs: %s",
        params);
    // Traversals in the output tree inline any recursive TraversalRequest evaluations, i.e. there
    // won't be any transitively depended-on TraversalRequests.
    return FilesetTraversalRequest.create(params);
  }

  private static FilesetTraversalParams checkParams(FilesetEntryKey key) {
    FilesetTraversalParams params = key.argument();
    checkState(
        params.getDirectTraversal() != null && params.getNestedArtifact() == null,
        "FilesetEntry does not support nested traversal: %s",
        params);
    return params;
  }

  /**
   * Models a FilesetEntryFunction's portion of the symlink output tree created by a Fileset rule.
   *
   * <p>A Fileset rule's output is computed by zero or more {@link FilesetEntryFunction}s, resulting
   * in one {@link FilesetEntryValue} for each. Each of those represents a portion of the grand
   * output tree of the Fileset. These portions are later merged and written to the fileset manifest
   * file, which is then consumed by a tool that ultimately creates the symlinks in the filesystem.
   *
   * <p>Because the Fileset doesn't process the lists in the FilesetEntryValues any further than
   * merging them, they have to adhere to the conventions of the manifest file. One of these is that
   * files are alphabetically ordered (enables the consumer of the manifest to work faster than
   * otherwise) and another is that the contents of regular directories are listed, but contents of
   * directory symlinks are not, only the symlinks are. (Other details of the manifest file are not
   * relevant here.)
   *
   * <p>See {@link DirectoryTree#iterateFiles()} for more details.
   */
  private static final class DirectoryTree {
    // Use TreeMaps for the benefit of alphabetically ordered iteration.
    final Map<String, ResolvedFile> files = new TreeMap<>();
    final Map<String, DirectoryTree> dirs = new TreeMap<>();

    DirectoryTree addOrGetSubdir(String name) {
      DirectoryTree result = dirs.get(name);
      if (result == null) {
        result = new DirectoryTree();
        dirs.put(name, result);
      }
      return result;
    }

    void maybeAddFile(ResolvedFile r) {
      String name = r.getNameInSymlinkTree().getBaseName();
      if (!files.containsKey(name)) {
        files.put(name, r);
      }
    }

    /**
     * Lazily yields all files in this directory and all of its subdirectories.
     *
     * <p>The function first yields all the files directly under this directory, in alphabetical
     * order. Then come the contents of subdirectories, processed recursively in the same fashion
     * as this directory, and also in alphabetical order.
     *
     * <p>If a directory symlink is encountered its contents are not listed, only the symlink is.
     */
    Iterable<ResolvedFile> iterateFiles() {
      // 1. Filter directory symlinks. If the symlink target contains files, those were added
      // as normal files so their parent directory (the symlink) would show up in the dirs map
      // (as a directory) as well as in the files map (as a symlink to a directory).
      final Set<String> fileNames = files.keySet();
      Iterable<Map.Entry<String, DirectoryTree>> noDirSymlinkes =
          Iterables.filter(dirs.entrySet(), input -> !fileNames.contains(input.getKey()));

      // 2. Extract the iterables of the true subdirectories.
      Iterable<Iterable<ResolvedFile>> subdirIters =
          Iterables.transform(noDirSymlinkes, input -> input.getValue().iterateFiles());

      // 3. Just concat all subdirectory iterations for one, seamless iteration.
      Iterable<ResolvedFile> dirsIter = Iterables.concat(subdirIters);

      return Iterables.concat(files.values(), dirsIter);
    }
  }
}
