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

import build.bazel.remote.execution.v2.Digest;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Intermediate tree representation of a list of lexicographically sorted list of files. Each node
 * in the tree represents either a directory or file.
 */
final class DirectoryTree {

  interface Visitor {

    void visitDirectory(
        PathFragment dirname,
        SortedSet<FileNode> files,
        SortedSet<SymlinkNode> symlinks,
        SortedSet<DirectoryNode> dirs);
  }

  abstract static class Node implements Comparable<Node> {
    private final String pathSegment;

    Node(String pathSegment) {
      this.pathSegment = Preconditions.checkNotNull(pathSegment);
    }

    String getPathSegment() {
      return pathSegment;
    }

    @Override
    public int compareTo(Node other) {
      return pathSegment.compareTo(other.pathSegment);
    }

    @Override
    public int hashCode() {
      return pathSegment.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Node other) {
        return Objects.equals(pathSegment, other.pathSegment);
      }
      return false;
    }
  }

  static class FileNode extends Node {
    private final Path path;
    private final VirtualActionInput virtualActionInput;
    private final Digest digest;
    private final boolean isExecutable;
    private final boolean toolInput;

    /**
     * Create a FileNode with its executable bit set.
     *
     * <p>We always treat files as executable since Bazel will `chmod 555` on the output files of an
     * action within ActionOutputMetadataStore#getMetadata after action execution if no metadata was
     * injected. We can't use real executable bit of the file until this behaviour is changed. See
     * https://github.com/bazelbuild/bazel/issues/13262 for more details.
     */
    static FileNode createExecutable(String pathSegment, Path path, Digest digest) {
      return new FileNode(pathSegment, path, digest, /* isExecutable= */ true, false);
    }

    static FileNode createExecutable(
        String pathSegment, Path path, Digest digest, boolean toolInput) {
      return new FileNode(pathSegment, path, digest, /* isExecutable= */ true, toolInput);
    }

    static FileNode createExecutable(
        String pathSegment,
        VirtualActionInput virtualActionInput,
        Digest digest,
        boolean toolInput) {
      return new FileNode(
          pathSegment, virtualActionInput, digest, /* isExecutable= */ true, toolInput);
    }

    private FileNode(
        String pathSegment, Path path, Digest digest, boolean isExecutable, boolean toolInput) {
      super(pathSegment);
      this.path = Preconditions.checkNotNull(path, "path");
      this.virtualActionInput = null;
      this.digest = Preconditions.checkNotNull(digest, "digest");
      this.isExecutable = isExecutable;
      this.toolInput = toolInput;
    }

    private FileNode(
        String pathSegment,
        VirtualActionInput input,
        Digest digest,
        boolean isExecutable,
        boolean toolInput) {
      super(pathSegment);
      this.path = null;
      this.virtualActionInput = Preconditions.checkNotNull(input, "data");
      this.digest = Preconditions.checkNotNull(digest, "digest");
      this.isExecutable = isExecutable;
      this.toolInput = toolInput;
    }

    Digest getDigest() {
      return digest;
    }

    Path getPath() {
      return path;
    }

    VirtualActionInput getVirtualActionInput() {
      return virtualActionInput;
    }

    public boolean isExecutable() {
      return isExecutable;
    }

    boolean isToolInput() {
      return toolInput;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          super.hashCode(), path, virtualActionInput, digest, toolInput, isExecutable);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof FileNode other) {
        return super.equals(other)
            && Objects.equals(path, other.path)
            && Objects.equals(virtualActionInput, other.virtualActionInput)
            && Objects.equals(digest, other.digest)
            && toolInput == other.toolInput
            && isExecutable == other.isExecutable;
      }
      return false;
    }

    @Override
    public String toString() {
      return String.format(
          "%s (hash: %s, size: %d)", getPathSegment(), digest.getHash(), digest.getSizeBytes());
    }
  }

  static class SymlinkNode extends Node {
    private final String target;

    SymlinkNode(String pathSegment, String target) {
      super(pathSegment);
      this.target = target;
    }

    public String getTarget() {
      return target;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), target);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof SymlinkNode other) {
        return super.equals(other) && Objects.equals(target, other.target);
      }
      return false;
    }

    @Override
    public String toString() {
      return String.format("%s --> %s", getPathSegment(), getTarget());
    }
  }

  static class DirectoryNode extends Node {

    private final SortedSet<FileNode> files = new TreeSet<>();
    private final SortedSet<SymlinkNode> symlinks = new TreeSet<>();
    private final SortedSet<DirectoryNode> subdirs = new TreeSet<>();

    DirectoryNode(String pathSegment) {
      super(pathSegment);
    }

    @CanIgnoreReturnValue
    boolean addChild(FileNode file) {
      return files.add(Preconditions.checkNotNull(file, "file"));
    }

    @CanIgnoreReturnValue
    boolean addChild(SymlinkNode symlink) {
      return symlinks.add(Preconditions.checkNotNull(symlink, "symlink"));
    }

    @CanIgnoreReturnValue
    boolean addChild(DirectoryNode subdir) {
      return subdirs.add(Preconditions.checkNotNull(subdir, "subdir"));
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          super.hashCode(), files.hashCode(), symlinks.hashCode(), subdirs.hashCode());
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof DirectoryNode other) {
        return super.equals(other)
            && Objects.equals(files, other.files)
            && Objects.equals(symlinks, other.symlinks)
            && Objects.equals(subdirs, other.subdirs);
      }
      return false;
    }
  }

  private final Map<PathFragment, DirectoryNode> tree;
  private final int numFiles;

  DirectoryTree(Map<PathFragment, DirectoryNode> tree, int numFiles) {
    Preconditions.checkState(numFiles >= 0, "numFiles must gte 0");
    this.tree = Preconditions.checkNotNull(tree, "tree");
    this.numFiles = numFiles;
  }

  int numDirectories() {
    return tree.size();
  }

  int numFiles() {
    return numFiles;
  }

  boolean isEmpty() {
    return tree.isEmpty();
  }

  /**
   * Traverses the {@link DirectoryTree} in a depth first search manner. The children are visited in
   * lexographical order.
   */
  void visit(Visitor visitor) {
    Preconditions.checkNotNull(visitor, "visitor");
    visit(visitor, PathFragment.EMPTY_FRAGMENT);
  }

  private void visit(Visitor visitor, PathFragment dirname) {
    DirectoryNode dir = tree.get(dirname);
    if (dir == null) {
      return;
    }

    for (DirectoryNode subdir : dir.subdirs) {
      visit(visitor, dirname.getRelative(subdir.getPathSegment()));
    }
    visitor.visitDirectory(dirname, dir.files, dir.symlinks, dir.subdirs);
  }

  @Override
  public String toString() {
    Map<PathFragment, StringBuilder> m = new HashMap<>();
    visit(
        (dirname, files, symlinks, dirs) -> {
          int depth = dirname.segmentCount() - 1;
          StringBuilder sb = new StringBuilder();

          if (!dirname.equals(PathFragment.EMPTY_FRAGMENT)) {
            sb.append(" ".repeat(2 * depth));
            sb.append(dirname.getBaseName());
            sb.append("\n");
          }
          for (Node fileOrSymlink : Iterables.concat(files, symlinks)) {
            sb.append(" ".repeat(2 * (depth + 1)));
            sb.append(fileOrSymlink);
            sb.append("\n");
          }
          if (!dirs.isEmpty()) {
            for (DirectoryNode dir : dirs) {
              sb.append(m.remove(dirname.getRelative(dir.getPathSegment())));
            }
          }
          m.put(dirname, sb);
        });
    return m.get(PathFragment.EMPTY_FRAGMENT).toString();
  }

  @Override
  public int hashCode() {
    return tree.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof DirectoryTree other)) {
      return false;
    }
    return tree.equals(other.tree);
  }
}
