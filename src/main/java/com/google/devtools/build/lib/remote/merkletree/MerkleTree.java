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

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.SymlinkNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.Scrubber.SpawnScrubber;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree.ContentSource.PathSource;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree.ContentSource.VirtualActionInputSource;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;

/** A merkle tree representation as defined by the remote execution api. */
public class MerkleTree {
  private static final String BAZEL_TOOL_INPUT_MARKER = "bazel_tool_input";

  /** A source of a file's content. */
  public sealed interface ContentSource {
    /** Content provided by an actual file. */
    record PathSource(Path path) implements ContentSource {}

    /** Content provided by a virtual action input. */
    record VirtualActionInputSource(VirtualActionInput virtualActionInput)
        implements ContentSource {}
  }

  private interface MerkleTreeDirectoryVisitor {

    /**
     * Visits each directory in a {@code MerkleTree}.
     *
     * <p>The order of the iteration is undefined.
     *
     * @param dir a directory in the {@code MerkleTree}.
     */
    void visitDirectory(MerkleTree dir);
  }

  private Map<Digest, Directory> digestDirectoryMap;
  // Object is an unwrapped ContentSource to reduce retained memory when caching MerkleTrees.
  private Map<Digest, Object> digestFileMap;
  @Nullable private final Directory rootProto;
  private final Digest rootDigest;
  private final SortedSet<DirectoryTree.FileNode> files;
  private final SortedSet<DirectoryTree.SymlinkNode> symlinks;
  private final SortedMap<String, MerkleTree> directories;
  private final long inputFiles;
  private final long inputBytes;

  private MerkleTree(
      @Nullable Directory rootProto,
      Digest rootDigest,
      SortedSet<DirectoryTree.FileNode> files,
      SortedSet<DirectoryTree.SymlinkNode> symlinks,
      SortedMap<String, MerkleTree> directories,
      long inputFiles,
      long inputBytes) {
    this.digestDirectoryMap = null;
    this.digestFileMap = null;
    this.rootProto = rootProto;
    this.rootDigest = Preconditions.checkNotNull(rootDigest, "rootDigest");
    this.files = Preconditions.checkNotNull(files, "files");
    this.symlinks = Preconditions.checkNotNull(symlinks, "symlinks");
    this.directories = Preconditions.checkNotNull(directories, "directories");
    this.inputFiles = inputFiles;
    this.inputBytes = inputBytes;
  }

  /** Returns the digest of the Merkle tree's root. */
  @Nullable
  public Directory getRootProto() {
    return rootProto;
  }

  /** Returns the protobuf representation of the Merkle tree's root. */
  public Digest getRootDigest() {
    return rootDigest;
  }

  private SortedSet<DirectoryTree.FileNode> getFiles() {
    return files;
  }

  private SortedSet<DirectoryTree.SymlinkNode> getSymlinks() {
    return symlinks;
  }

  private SortedMap<String, MerkleTree> getDirectories() {
    return directories;
  }

  private void visitTree(MerkleTreeDirectoryVisitor visitor) {
    visitor.visitDirectory(this);
    for (MerkleTree dir : getDirectories().values()) {
      dir.visitTree(visitor);
    }
  }

  /** Returns the number of files represented by this merkle tree */
  public long getInputFiles() {
    return inputFiles;
  }

  /** Returns the sum of file sizes plus protobuf sizes used to represent this merkle tree */
  public long getInputBytes() {
    return inputBytes;
  }

  private Map<Digest, Directory> getDigestDirectoryMap() {
    if (this.digestDirectoryMap == null) {
      Map<Digest, Directory> newDigestMap = Maps.newHashMap();
      visitTree(
          (dir) -> {
            if (dir.getRootProto() != null) {
              newDigestMap.put(dir.getRootDigest(), dir.getRootProto());
            }
          });
      this.digestDirectoryMap = newDigestMap;
    }
    return this.digestDirectoryMap;
  }

  private Map<Digest, Object> getDigestFileMap() {
    if (this.digestFileMap == null) {
      Map<Digest, Object> newDigestMap = Maps.newHashMap();
      visitTree(
          (dir) -> {
            for (DirectoryTree.FileNode file : dir.getFiles()) {
              if (file.getPath() != null) {
                newDigestMap.put(file.getDigest(), file.getPath());
              } else {
                newDigestMap.put(file.getDigest(), file.getVirtualActionInput());
              }
            }
          });
      this.digestFileMap = newDigestMap;
    }
    return this.digestFileMap;
  }

  @Nullable
  public Directory getDirectoryByDigest(Digest digest) {
    return getDigestDirectoryMap().get(digest);
  }

  @Nullable
  public ContentSource getFileByDigest(Digest digest) {
    return switch (getDigestFileMap().get(digest)) {
      case Path path -> new PathSource(path);
      case VirtualActionInput virtualActionInput ->
          new VirtualActionInputSource(virtualActionInput);
      case null -> null;
      default ->
          throw new IllegalStateException("Unexpected value: " + getDigestFileMap().get(digest));
    };
  }

  /**
   * Returns the hashes of all nodes and leafs of the merkle tree. That is, the hashes of the {@link
   * Directory} protobufs and {@link ActionInput} files.
   */
  public Iterable<Digest> getAllDigests() {
    return Iterables.concat(getDigestDirectoryMap().keySet(), getDigestFileMap().keySet());
  }

  /**
   * Constructs a merkle tree from a lexicographically sorted map of inputs (files).
   *
   * @param inputs a map of path to input. The map is required to be sorted lexicographically by
   *     paths. Inputs of type tree artifacts are not supported and are expected to have been
   *     expanded before.
   * @param inputMetadataProvider provides metadata for all {@link ActionInput}s in {@code inputs},
   *     as well as any {@link ActionInput}s being discovered via directory expansion.
   * @param execRoot all paths in {@code inputs} need to be relative to this {@code execRoot}.
   * @param digestUtil a hashing utility
   */
  public static MerkleTree build(
      SortedMap<PathFragment, ActionInput> inputs,
      InputMetadataProvider inputMetadataProvider,
      Path execRoot,
      ArtifactPathResolver artifactPathResolver,
      @Nullable SpawnScrubber spawnScrubber,
      DigestUtil digestUtil)
      throws IOException {
    return build(
        inputs,
        /* toolInputs= */ ImmutableSet.of(),
        inputMetadataProvider,
        execRoot,
        artifactPathResolver,
        spawnScrubber,
        digestUtil);
  }

  /**
   * Constructs a merkle tree from a lexicographically sorted map of inputs (files).
   *
   * @param inputs a map of path to input. The map is required to be sorted lexicographically by
   *     paths. Inputs of type tree artifacts are not supported and are expected to have been
   *     expanded before.
   * @param inputMetadataProvider provides metadata for all {@link ActionInput}s in {@code inputs},
   *     as well as any {@link ActionInput}s being discovered via directory expansion.
   * @param execRoot all paths in {@code inputs} need to be relative to this {@code execRoot}.
   * @param digestUtil a hashing utility
   */
  public static MerkleTree build(
      SortedMap<PathFragment, ActionInput> inputs,
      Set<PathFragment> toolInputs,
      InputMetadataProvider inputMetadataProvider,
      Path execRoot,
      ArtifactPathResolver artifactPathResolver,
      @Nullable SpawnScrubber spawnScrubber,
      DigestUtil digestUtil)
      throws IOException {
    try (SilentCloseable c = Profiler.instance().profile("MerkleTree.build(ActionInput)")) {
      DirectoryTree tree =
          DirectoryTreeBuilder.fromActionInputs(
              inputs,
              toolInputs,
              inputMetadataProvider,
              execRoot,
              artifactPathResolver,
              spawnScrubber,
              digestUtil);
      return build(tree, digestUtil);
    }
  }

  /**
   * Constructs a merkle tree from a lexicographically sorted map of files.
   *
   * @param inputFiles a map of path to files. The map is required to be sorted lexicographically by
   *     paths.
   * @param digestUtil a hashing utility
   */
  public static MerkleTree build(SortedMap<PathFragment, Path> inputFiles, DigestUtil digestUtil)
      throws IOException {
    try (SilentCloseable c = Profiler.instance().profile("MerkleTree.build(Path)")) {
      DirectoryTree tree = DirectoryTreeBuilder.fromPaths(inputFiles, digestUtil);
      return build(tree, digestUtil);
    }
  }

  private static MerkleTree build(DirectoryTree tree, DigestUtil digestUtil) {
    Preconditions.checkNotNull(tree);
    if (tree.isEmpty()) {
      return new MerkleTree(
          null,
          digestUtil.compute(new byte[0]),
          ImmutableSortedSet.of(),
          ImmutableSortedSet.of(),
          ImmutableSortedMap.of(),
          0,
          0);
    }
    Map<PathFragment, MerkleTree> m = new HashMap<>();
    tree.visit(
        (dirname, files, symlinks, dirs) -> {
          SortedMap<String, MerkleTree> subDirs = new TreeMap<>();
          for (DirectoryTree.DirectoryNode dir : dirs) {
            PathFragment subDirname = dirname.getRelative(dir.getPathSegment());
            MerkleTree subMerkleTree =
                Preconditions.checkNotNull(
                    m.remove(subDirname), "subMerkleTree at '%s' was null", subDirname);
            subDirs.put(dir.getPathSegment(), subMerkleTree);
          }
          MerkleTree mt = buildMerkleTree(files, symlinks, subDirs, digestUtil);
          m.put(dirname, mt);
        });
    MerkleTree rootMerkleTree = m.get(PathFragment.EMPTY_FRAGMENT);
    Preconditions.checkState(
        rootMerkleTree.getInputFiles() == tree.numFiles(),
        "rootMerkleTree.getInputFiles() %s != tree.numFiles() %s",
        rootMerkleTree.getInputFiles(),
        tree.numFiles());
    return rootMerkleTree;
  }

  public static MerkleTree merge(Collection<MerkleTree> merkleTrees, DigestUtil digestUtil) {
    if (merkleTrees.isEmpty()) {
      return build(new DirectoryTree(ImmutableMap.of(), 0), digestUtil);
    }

    MerkleTree firstMerkleTree = merkleTrees.iterator().next();
    Digest firstRootDigest = firstMerkleTree.getRootDigest();
    if (merkleTrees.stream()
        .allMatch((mt) -> Objects.equals(mt.getRootDigest(), firstRootDigest))) {
      // All are the same, pick the first one.
      return firstMerkleTree;
    }

    // Some differ, do a full merge.
    SortedSet<DirectoryTree.FileNode> files = new TreeSet<>();
    for (MerkleTree merkleTree : merkleTrees) {
      files.addAll(merkleTree.getFiles());
    }
    SortedSet<DirectoryTree.SymlinkNode> symlinks = new TreeSet<>();
    for (MerkleTree merkleTree : merkleTrees) {
      symlinks.addAll(merkleTree.getSymlinks());
    }

    // Group all Merkle trees per path.
    Multimap<String, MerkleTree> allDirsToMerge = ArrayListMultimap.create();
    for (MerkleTree merkleTree : merkleTrees) {
      merkleTree.getDirectories().forEach(allDirsToMerge::put);
    }
    // Merge the Merkle trees for each path.
    SortedMap<String, MerkleTree> directories = new TreeMap<>();
    allDirsToMerge
        .asMap()
        .forEach(
            (baseName, dirsToMerge) -> directories.put(baseName, merge(dirsToMerge, digestUtil)));

    return buildMerkleTree(files, symlinks, directories, digestUtil);
  }

  private static MerkleTree buildMerkleTree(
      SortedSet<DirectoryTree.FileNode> files,
      SortedSet<DirectoryTree.SymlinkNode> symlinks,
      SortedMap<String, MerkleTree> directories,
      DigestUtil digestUtil) {
    Directory.Builder b = Directory.newBuilder();
    for (DirectoryTree.FileNode file : files) {
      b.addFiles(buildProto(file));
    }
    for (DirectoryTree.SymlinkNode symlink : symlinks) {
      b.addSymlinks(buildProto(symlink));
    }
    for (Map.Entry<String, MerkleTree> nameAndDir : directories.entrySet()) {
      b.addDirectories(buildProto(nameAndDir.getKey(), nameAndDir.getValue()));
    }
    Directory protoDir = b.build();
    Digest protoDirDigest = digestUtil.compute(protoDir);

    long inputFiles = (long) files.size() + symlinks.size();
    for (MerkleTree dir : directories.values()) {
      inputFiles += dir.getInputFiles();
    }

    long inputBytes = protoDirDigest.getSizeBytes();
    for (DirectoryTree.FileNode file : files) {
      inputBytes += file.getDigest().getSizeBytes();
    }
    for (MerkleTree dir : directories.values()) {
      inputBytes += dir.getInputBytes();
    }

    return new MerkleTree(
        protoDir, protoDirDigest, files, symlinks, directories, inputFiles, inputBytes);
  }

  private static FileNode buildProto(DirectoryTree.FileNode file) {
    var node =
        FileNode.newBuilder()
            .setName(internalToUnicode(file.getPathSegment()))
            .setDigest(file.getDigest())
            .setIsExecutable(file.isExecutable());
    if (file.isToolInput()) {
      node.getNodePropertiesBuilder().addPropertiesBuilder().setName(BAZEL_TOOL_INPUT_MARKER);
    }
    return node.build();
  }

  private static DirectoryNode buildProto(String baseName, MerkleTree dir) {
    return DirectoryNode.newBuilder()
        .setName(internalToUnicode(baseName))
        .setDigest(dir.getRootDigest())
        .build();
  }

  private static SymlinkNode buildProto(DirectoryTree.SymlinkNode symlink) {
    return SymlinkNode.newBuilder()
        .setName(internalToUnicode(symlink.getPathSegment()))
        .setTarget(internalToUnicode(symlink.getTarget()))
        .build();
  }
}
