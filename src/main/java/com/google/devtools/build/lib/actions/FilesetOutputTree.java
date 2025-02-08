// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import javax.annotation.Nullable;

/** A collection of {@link FilesetOutputSymlink}s comprising the output tree of a fileset. */
public final class FilesetOutputTree {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public static final FilesetOutputTree EMPTY = new FilesetOutputTree(ImmutableList.of(), false);
  private static final int MAX_SYMLINK_TRAVERSALS = 256;

  public static FilesetOutputTree create(ImmutableList<FilesetOutputSymlink> symlinks) {
    return symlinks.isEmpty()
        ? EMPTY
        : new FilesetOutputTree(
            symlinks, symlinks.stream().anyMatch(FilesetOutputTree::isRelativeSymlink));
  }

  private final ImmutableList<FilesetOutputSymlink> symlinks;
  private final boolean hasRelativeSymlinks;

  private FilesetOutputTree(
      ImmutableList<FilesetOutputSymlink> symlinks, boolean hasRelativeSymlinks) {
    this.symlinks = checkNotNull(symlinks);
    this.hasRelativeSymlinks = hasRelativeSymlinks;
  }

  /** Receiver for the symlinks in a fileset's output tree. */
  @FunctionalInterface
  public interface Visitor<E1 extends Exception, E2 extends Exception> {

    /**
     * Called for each symlink in the fileset's output tree.
     *
     * @param name path of the symlink relative to the fileset's root; equivalent to {@link
     *     FilesetOutputSymlink#getName}
     * @param target symlink target; either an absolute path if the symlink points to a source file
     *     or an execroot-relative path if the symlink points to an output
     * @param metadata a {@link FileArtifactValue} representing the target's metadata if available,
     *     or {@code null}
     */
    void acceptSymlink(PathFragment name, PathFragment target, @Nullable FileArtifactValue metadata)
        throws E1, E2;
  }

  /**
   * Visits the symlinks in this fileset tree, handling relative symlinks according to the given
   * {@link RelativeSymlinkBehavior}.
   */
  public <E1 extends Exception, E2 extends Exception> void visitSymlinks(
      RelativeSymlinkBehavior relSymlinkBehavior, Visitor<E1, E2> visitor)
      throws ForbiddenRelativeSymlinkException, E1, E2 {
    var relSymlinkBehaviorWithoutError =
        switch (relSymlinkBehavior) {
          case RESOLVE_FULLY -> RelativeSymlinkBehaviorWithoutError.RESOLVE_FULLY;
          case RESOLVE -> RelativeSymlinkBehaviorWithoutError.RESOLVE;
          case IGNORE -> RelativeSymlinkBehaviorWithoutError.IGNORE;
          case ERROR -> {
            if (hasRelativeSymlinks) {
              FilesetOutputSymlink relativeLink =
                  symlinks.stream().filter(FilesetOutputTree::isRelativeSymlink).findFirst().get();
              throw new ForbiddenRelativeSymlinkException(relativeLink);
            }
            yield RelativeSymlinkBehaviorWithoutError.IGNORE;
          }
        };
    visitSymlinks(relSymlinkBehaviorWithoutError, visitor);
  }

  /**
   * Visits the symlinks in this fileset tree, handling relative symlinks according to the given
   * {@link RelativeSymlinkBehaviorWithoutError}.
   */
  public <E1 extends Exception, E2 extends Exception> void visitSymlinks(
      RelativeSymlinkBehaviorWithoutError relSymlinkBehavior, Visitor<E1, E2> visitor)
      throws E1, E2 {
    // Fast path: if we don't need to resolve relative symlinks (either because there are none or
    // because we are ignoring them), perform a single-pass visitation. This is expected to be the
    // common case.
    if (!hasRelativeSymlinks || relSymlinkBehavior == RelativeSymlinkBehaviorWithoutError.IGNORE) {
      for (FilesetOutputSymlink symlink : symlinks) {
        if (!isRelativeSymlink(symlink)) {
          visitor.acceptSymlink(
              symlink.name(),
              symlink.targetPath(),
              symlink.metadata() instanceof FileArtifactValue metadata ? metadata : null);
        }
      }
      return;
    }

    // Symlink name to resolved target path. Relative target paths are relative to the exec root.
    Map<PathFragment, String> resolvedLinks = new LinkedHashMap<>();
    // Symlink name to relative target path.
    Map<PathFragment, String> relativeLinks = new HashMap<>();
    // Resolved target path to metadata.
    Map<String, FileArtifactValue> artifactValues = new HashMap<>();

    for (FilesetOutputSymlink outputSymlink : symlinks) {
      PathFragment name = outputSymlink.name();
      String targetPath = outputSymlink.targetPath().getPathString();
      var map = isRelativeSymlink(outputSymlink) ? relativeLinks : resolvedLinks;

      // Symlinks are already deduplicated by name in SkyframeFilesetManifestAction.
      checkState(map.put(name, targetPath) == null, "Duplicate fileset entry at %s", name);

      if (outputSymlink.metadata() instanceof FileArtifactValue metadata) {
        artifactValues.put(targetPath, metadata);
      }
    }

    if (relSymlinkBehavior == RelativeSymlinkBehaviorWithoutError.RESOLVE_FULLY) {
      fullyResolveRelativeSymlinks(resolvedLinks, relativeLinks);
    } else {
      resolveRelativeSymlinks(resolvedLinks, relativeLinks);
    }

    for (var entry : resolvedLinks.entrySet()) {
      PathFragment name = entry.getKey();
      String target = entry.getValue();
      FileArtifactValue metadata = artifactValues.get(target);
      visitor.acceptSymlink(name, PathFragment.create(target), metadata);
    }
  }

  public ImmutableList<FilesetOutputSymlink> symlinks() {
    return symlinks;
  }

  public int size() {
    return symlinks.size();
  }

  public boolean isEmpty() {
    return symlinks.isEmpty();
  }

  @Override
  public int hashCode() {
    return symlinks.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FilesetOutputTree that)) {
      return false;
    }
    return symlinks.equals(that.symlinks);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("symlinks", symlinks)
        .add("hasRelativeSymlinks", hasRelativeSymlinks)
        .toString();
  }

  /** Mode that determines how to handle relative target paths. */
  public enum RelativeSymlinkBehavior {
    /** Ignore any relative target paths. */
    IGNORE,
    /** Give an error if a relative target path is encountered. */
    ERROR,
    /** Resolve relative target paths that (transitively) point to another file in the fileset. */
    RESOLVE,
    /**
     * Fully resolve all relative paths, even those pointing to internal directories. Then do a
     * virtual filesystem traversal to find all paths to all files in the fileset.
     */
    RESOLVE_FULLY
  }

  /**
   * Shadow of {@link RelativeSymlinkBehavior} without the {@link RelativeSymlinkBehavior#ERROR}
   * value for callers who know there won't be an error thrown when constructing the manifest.
   */
  public enum RelativeSymlinkBehaviorWithoutError {
    /** Ignore any relative target paths. */
    IGNORE,
    /** Resolve all relative target paths. */
    RESOLVE,
    /** Fully resolve all relative paths, even those pointing to internal directories. */
    RESOLVE_FULLY
  }

  private static boolean isRelativeSymlink(FilesetOutputSymlink symlink) {
    return !symlink.targetPath().isAbsolute() && !symlink.relativeToExecRoot();
  }

  /** Fully resolves relative symlinks, including internal directory symlinks. */
  private static void fullyResolveRelativeSymlinks(
      Map<PathFragment, String> resolvedLinks, Map<PathFragment, String> relativeLinks) {
    try {
      // Construct an in-memory Filesystem containing all the non-relative-symlink entries in the
      // Fileset. Treat these as regular files in the filesystem whose contents are the "real"
      // symlink pointing out of the Fileset. For relative symlinks, we encode these as symlinks in
      // the in-memory Filesystem. This allows us to then crawl the filesystem for files. Any
      // readable file is a valid part of the FilesetManifest. Dangling internal links or symlink
      // cycles will be discovered by the in-memory filesystem.
      // (Choice of digest function is irrelevant).
      InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
      Path root = fs.getPath("/");
      for (Map.Entry<PathFragment, String> e : resolvedLinks.entrySet()) {
        PathFragment location = e.getKey();
        Path locationPath = root.getRelative(location);
        locationPath.getParentDirectory().createDirectoryAndParents();
        FileSystemUtils.writeContentAsLatin1(locationPath, e.getValue());
      }
      for (Map.Entry<PathFragment, String> e : relativeLinks.entrySet()) {
        PathFragment location = e.getKey();
        Path locationPath = fs.getPath("/").getRelative(location);
        PathFragment value = PathFragment.create(checkNotNull(e.getValue(), e));

        locationPath.getParentDirectory().createDirectoryAndParents();
        locationPath.createSymbolicLink(value);
      }

      addSymlinks(root, resolvedLinks);
    } catch (IOException e) {
      throw new IllegalStateException("InMemoryFileSystem can't throw", e);
    }
  }

  private static void addSymlinks(Path root, Map<PathFragment, String> resolvedLinks)
      throws IOException {
    for (Path path : root.getDirectoryEntries()) {
      try {
        if (path.isDirectory()) {
          addSymlinks(path, resolvedLinks);
        } else {
          String contents = new String(FileSystemUtils.readContentAsLatin1(path));
          resolvedLinks.put(path.asFragment().toRelative(), contents);
        }
      } catch (IOException e) {
        logger.atWarning().log("Symlink %s is dangling or cyclic: %s", path, e.getMessage());
      }
    }
  }

  /** Resolves relative symlinks and puts them in the {@code resolvedLinks} map. */
  private static void resolveRelativeSymlinks(
      Map<PathFragment, String> resolvedLinks, Map<PathFragment, String> relativeLinks) {
    for (Map.Entry<PathFragment, String> e : relativeLinks.entrySet()) {
      PathFragment location = e.getKey();
      String actual = e.getValue();
      PathFragment actualLocation = location;

      // Recursively resolve relative symlinks.
      LinkedHashSet<String> seen = new LinkedHashSet<>();
      int traversals = 0;
      do {
        actualLocation = actualLocation.getParentDirectory().getRelative(actual);
        actual = relativeLinks.get(actualLocation);
      } while (++traversals <= MAX_SYMLINK_TRAVERSALS && actual != null && seen.add(actual));

      if (traversals >= MAX_SYMLINK_TRAVERSALS) {
        logger.atWarning().log(
            "Symlink %s is part of a chain of length at least %d"
                + " which exceeds Blaze's maximum allowable symlink chain length",
            location, traversals);
      } else if (actual != null) {
        // TODO(b/113128395): throw here.
        logger.atWarning().log("Symlink %s forms a symlink cycle: %s", location, seen);
      } else {
        String resolvedTarget = resolvedLinks.get(actualLocation);
        if (resolvedTarget == null) {
          // We've found a relative symlink that points out of the fileset. We should really
          // always throw here, but current behavior is that we tolerate such symlinks when they
          // occur in runfiles, which is the only time this code is hit.
          // TODO(b/113128395): throw here.
          logger.atWarning().log(
              "Symlink %s (transitively) points to %s that is not in this fileset (or was"
                  + " pruned because of a cycle)",
              location, actualLocation);
        } else {
          // We have successfully resolved the symlink.
          resolvedLinks.put(location, resolvedTarget);
        }
      }
    }
  }

  /** Exception indicating that a relative symlink was encountered but not permitted. */
  public static final class ForbiddenRelativeSymlinkException
      extends ForbiddenActionInputException {
    private ForbiddenRelativeSymlinkException(FilesetOutputSymlink relativeLink) {
      super(
          "Fileset symlink %s -> %s is not absolute"
              .formatted(relativeLink.name(), relativeLink.targetPath()));
    }
  }
}
