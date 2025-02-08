// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.HasDigest;
import com.google.devtools.build.lib.actions.cache.MetadataDigestUtils;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.NamedForkJoinPool;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.IORuntimeException;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

/**
 * Value for TreeArtifacts, which contains a digest and the {@link FileArtifactValue}s of its child
 * {@link TreeFileArtifact}s.
 */
public class TreeArtifactValue implements HasDigest, SkyValue {
  private static final ForkJoinPool VISITOR_POOL =
      NamedForkJoinPool.newNamedPool(
          "tree-artifact-visitor", Runtime.getRuntime().availableProcessors());

  /**
   * Comparator based on exec path which works on {@link ActionInput} as opposed to {@link
   * com.google.devtools.build.lib.actions.Artifact}. This way, we can use an {@link ActionInput} to
   * search {@link #childData}.
   */
  @SerializationConstant @VisibleForSerialization
  static final Comparator<ActionInput> EXEC_PATH_COMPARATOR =
      (input1, input2) -> input1.getExecPath().compareTo(input2.getExecPath());

  private static final ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> EMPTY_MAP =
      childDataBuilder().buildOrThrow();

  private static ImmutableSortedMap.Builder<TreeFileArtifact, FileArtifactValue>
      childDataBuilder() {
    return new ImmutableSortedMap.Builder<>(EXEC_PATH_COMPARATOR);
  }

  /** Returns an empty {@link TreeArtifactValue}. */
  public static TreeArtifactValue empty() {
    return EMPTY;
  }

  /**
   * Returns a new {@link Builder} for the given parent tree artifact.
   *
   * <p>The returned builder only supports adding children under this parent. To build multiple tree
   * artifacts at once, use {@link MultiBuilder}.
   */
  public static Builder newBuilder(SpecialArtifact parent) {
    return new Builder(parent);
  }

  /** Builder for constructing multiple instances of {@link TreeArtifactValue} at once. */
  public static final class MultiBuilder {

    private final Map<SpecialArtifact, Builder> map = new HashMap<>();

    private MultiBuilder() {}

    /**
     * Puts a child tree file into this builder under its {@linkplain TreeFileArtifact#getParent
     * parent}.
     *
     * @return {@code this} for convenience
     */
    @CanIgnoreReturnValue
    public MultiBuilder putChild(TreeFileArtifact child, FileArtifactValue metadata) {
      map.computeIfAbsent(child.getParent(), Builder::new).putChild(child, metadata);
      return this;
    }

    /**
     * Sets the archived representation and its metadata for the {@linkplain
     * ArchivedTreeArtifact#getParent parent} of the provided tree artifact.
     *
     * <p>Setting an archived representation is only allowed once per {@linkplain SpecialArtifact
     * tree artifact}.
     */
    @CanIgnoreReturnValue
    public MultiBuilder setArchivedRepresentation(
        ArchivedTreeArtifact archivedArtifact, FileArtifactValue metadata) {
      map.computeIfAbsent(archivedArtifact.getParent(), Builder::new)
          .setArchivedRepresentation(ArchivedRepresentation.create(archivedArtifact, metadata));
      return this;
    }

    /**
     * Removes all of collected data for a given tree artifact.
     *
     * <p>No-op if there is no data for a given tree artifact.
     */
    @CanIgnoreReturnValue
    public MultiBuilder remove(SpecialArtifact treeArtifact) {
      checkArgument(treeArtifact.isTreeArtifact(), "Not a tree artifact: %s", treeArtifact);
      map.remove(treeArtifact);
      return this;
    }

    /**
     * For each unique parent seen by this builder, passes the aggregated metadata to the specified
     * {@link BiConsumer}.
     */
    public void forEach(BiConsumer<SpecialArtifact, TreeArtifactValue> consumer) {
      map.forEach((parent, builder) -> consumer.accept(parent, builder.build()));
    }
  }

  /** Returns a new {@link MultiBuilder}. */
  public static MultiBuilder newMultiBuilder() {
    return new MultiBuilder();
  }

  /**
   * Archived representation of a tree artifact which contains a representation of the filesystem
   * tree starting with the tree artifact directory.
   *
   * <p>Contains both the {@linkplain ArchivedTreeArtifact artifact} for the archived file and the
   * metadata for it.
   */
  @AutoCodec
  public record ArchivedRepresentation(
      ArchivedTreeArtifact archivedTreeFileArtifact, FileArtifactValue archivedFileValue) {
    public ArchivedRepresentation {
      requireNonNull(archivedTreeFileArtifact, "archivedTreeFileArtifact");
      requireNonNull(archivedFileValue, "archivedFileValue");
    }

    public static ArchivedRepresentation create(
        ArchivedTreeArtifact archivedTreeFileArtifact, FileArtifactValue fileArtifactValue) {
      return new ArchivedRepresentation(archivedTreeFileArtifact, fileArtifactValue);
    }
  }

  @SuppressWarnings("WeakerAccess") // Serialization constant.
  @SerializationConstant
  @VisibleForSerialization
  static final TreeArtifactValue EMPTY =
      new TreeArtifactValue(
          MetadataDigestUtils.fromMetadata(ImmutableMap.of()),
          EMPTY_MAP,
          0L,
          /* archivedRepresentation= */ null,
          /* resolvedPath= */ null,
          /* entirelyRemote= */ false);

  private final byte[] digest;
  private final ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> childData;
  private final long totalChildSize;

  /**
   * Optional archived representation of the entire tree artifact which can be sent instead of all
   * the items in the directory.
   */
  @Nullable private final ArchivedRepresentation archivedRepresentation;

  /**
   * Optional resolved path.
   *
   * <p>See {@link FileArtifactValue#getResolvedPath} for semantics.
   */
  @Nullable private final PathFragment resolvedPath;

  private final boolean entirelyRemote;

  /** A FileArtifactValue used to stand in for a TreeArtifactValue. */
  private static final class TreeArtifactCompositeFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final boolean isRemote;
    @Nullable private final PathFragment resolvedPath;

    TreeArtifactCompositeFileArtifactValue(
        byte[] digest, boolean isRemote, @Nullable PathFragment resolvedPath) {
      this.digest = digest;
      this.isRemote = isRemote;
      this.resolvedPath = resolvedPath;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TreeArtifactCompositeFileArtifactValue that)) {
        return false;
      }
      return Arrays.equals(digest, that.digest) && Objects.equals(resolvedPath, that.resolvedPath);
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(Arrays.hashCode(digest), resolvedPath);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    @Nullable
    public FileContentsProxy getContentsProxy() {
      return null;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", BaseEncoding.base16().lowerCase().encode(digest))
          .add("resolvedPath", resolvedPath)
          .toString();
    }

    @Override
    protected boolean couldBeModifiedByMetadata(FileArtifactValue o) {
      return false;
    }

    @Override
    public boolean isRemote() {
      return isRemote;
    }

    @Override
    @Nullable
    public PathFragment getResolvedPath() {
      return resolvedPath;
    }
  }

  private TreeArtifactValue(
      byte[] digest,
      ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> childData,
      long totalChildSize,
      @Nullable ArchivedRepresentation archivedRepresentation,
      @Nullable PathFragment resolvedPath,
      boolean entirelyRemote) {
    this.digest = digest;
    this.childData = childData;
    this.totalChildSize = totalChildSize;
    this.archivedRepresentation = archivedRepresentation;
    this.resolvedPath = resolvedPath;
    this.entirelyRemote = entirelyRemote;
  }

  public FileArtifactValue getMetadata() {
    return new TreeArtifactCompositeFileArtifactValue(digest, entirelyRemote, resolvedPath);
  }

  ImmutableSet<PathFragment> getChildPaths() {
    return childData.keySet().stream()
        .map(TreeFileArtifact::getParentRelativePath)
        .collect(toImmutableSet());
  }

  @Override
  public byte[] getDigest() {
    return digest.clone();
  }

  public ImmutableSortedSet<TreeFileArtifact> getChildren() {
    return childData.keySet();
  }

  public long getTotalChildBytes() {
    return totalChildSize;
  }

  /** Returns the archived representation of the tree artifact, if present. */
  public Optional<ArchivedRepresentation> getArchivedRepresentation() {
    return Optional.ofNullable(archivedRepresentation);
  }

  /**
   * Returns the resolved path, if present.
   *
   * <p>See {@link FileArtifactValue#getResolvedPath} for semantics.
   */
  public Optional<PathFragment> getResolvedPath() {
    return Optional.ofNullable(resolvedPath);
  }

  @Nullable
  public ArchivedTreeArtifact getArchivedArtifact() {
    return archivedRepresentation != null
        ? archivedRepresentation.archivedTreeFileArtifact()
        : null;
  }

  public ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> getChildValues() {
    return childData;
  }

  /** Returns an entry for child with given exec path or null if no such child is present. */
  @SuppressWarnings("unchecked")
  @Nullable
  public Map.Entry<TreeFileArtifact, FileArtifactValue> findChildEntryByExecPath(
      PathFragment execPath) {
    ActionInput searchToken = ActionInputHelper.fromPath(execPath);
    // Not really a copy -- original map is already an ImmutableSortedMap using the same comparator.
    ImmutableSortedMap<ActionInput, FileArtifactValue> casted =
        ImmutableSortedMap.copyOf(childData, EXEC_PATH_COMPARATOR);
    checkState(casted == (Object) childData, "Casting children resulted with a copy");
    Map.Entry<? extends ActionInput, FileArtifactValue> entry = casted.floorEntry(searchToken);
    return entry != null && entry.getKey().getExecPath().equals(execPath)
        ? (Map.Entry<TreeFileArtifact, FileArtifactValue>) entry
        : null;
  }

  /** Returns true if the {@link TreeFileArtifact}s are only stored remotely. */
  public boolean isEntirelyRemote() {
    return entirelyRemote;
  }

  @Override
  public int hashCode() {
    return HashCodes.hashObjects(Arrays.hashCode(digest), archivedRepresentation, resolvedPath);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof TreeArtifactValue that)) {
      return false;
    }

    return Arrays.equals(digest, that.digest)
        && childData.equals(that.childData)
        && Objects.equals(archivedRepresentation, that.archivedRepresentation)
        && Objects.equals(resolvedPath, that.resolvedPath);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("digest", digest)
        .add("childData", childData)
        .add("archivedRepresentation", archivedRepresentation)
        .add("resolvedPath", resolvedPath)
        .toString();
  }

  /**
   * A TreeArtifactValue that represents a missing TreeArtifact. This is occasionally useful because
   * Java's concurrent collections disallow null members.
   */
  public static final TreeArtifactValue MISSING_TREE_ARTIFACT =
      createMarker("MISSING_TREE_ARTIFACT");

  private static TreeArtifactValue createMarker(String toStringRepresentation) {
    return new TreeArtifactValue(
        null,
        EMPTY_MAP,
        0L,
        /* archivedRepresentation= */ null,
        /* resolvedPath= */ null,
        /* entirelyRemote= */ false) {
      @Override
      public ImmutableSortedSet<TreeFileArtifact> getChildren() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> getChildValues() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public FileArtifactValue getMetadata() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      ImmutableSet<PathFragment> getChildPaths() {
        throw new UnsupportedOperationException(toString());
      }

      @Nullable
      @Override
      public byte[] getDigest() {
        throw new UnsupportedOperationException(toString());
      }

      @Override
      public int hashCode() {
        return System.identityHashCode(this);
      }

      @Override
      public boolean equals(Object other) {
        return this == other;
      }

      @Override
      public String toString() {
        return toStringRepresentation;
      }
    };
  }

  /** Visitor for use in {@link #visitTree}. */
  @FunctionalInterface
  public interface TreeArtifactVisitor {
    /**
     * Called for every directory entry encountered during tree traversal, in a nondeterministic
     * order.
     *
     * <p>Regular files and directories are reported as {@link Dirent.Type.FILE} or {@link
     * Dirent.Type.DIRECTORY}, respectively. Directories are traversed recursively.
     *
     * <p>Symlinks that resolve to an existing file or directory are followed and reported as the
     * regular files or directories they point to, recursively for directories. Symlinks that fail
     * to resolve to an existing path cause an {@link IOException} to be immediately thrown without
     * invoking the visitor. Thus, the visitor is never called with a {@link Dirent.Type.SYMLINK}
     * type.
     *
     * <p>Special files or files whose type could not be determined, regardless of whether they are
     * encountered directly or indirectly through symlinks, cause an {@link IOException} to be
     * immediately thrown without invoking the visitor. Thus, the visitor is never called with a
     * {@link Dirent.Type.UNKNOWN} type.
     *
     * <p>The {@code parentRelativePath} argument is always set to the apparent path relative to the
     * tree directory root, without resolving any intervening symlinks. The {@code traversedSymlink}
     * argument is true if at least one symlink was traversed on the way to the entry being
     * reported.
     *
     * <p>If the visitor throws {@link IOException}, traversal is immediately halted and the
     * exception is propagated.
     *
     * <p>This method can be called from multiple threads in parallel during a single call of {@link
     * TreeArtifactVisitor#visitTree(Path, TreeArtifactVisitor)}.
     */
    @ThreadSafe
    void visit(PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink)
        throws IOException;
  }

  /** An {@link AbstractQueueVisitor} that visits every file in the tree artifact. */
  static class Visitor extends AbstractQueueVisitor {
    private final Path parentDir;
    private final TreeArtifactVisitor visitor;

    Visitor(Path parentDir, TreeArtifactVisitor visitor) {
      super(
          VISITOR_POOL,
          ExecutorOwnership.SHARED,
          ExceptionHandlingMode.FAIL_FAST,
          ErrorClassifier.DEFAULT);
      this.parentDir = checkNotNull(parentDir);
      this.visitor = checkNotNull(visitor);
    }

    void run() throws IOException, InterruptedException {
      execute(
          () ->
              visit(
                  PathFragment.EMPTY_FRAGMENT,
                  Dirent.Type.DIRECTORY,
                  /* traversedSymlink= */ false));
      try {
        awaitQuiescence(true);
      } catch (IORuntimeException e) {
        throw e.getCauseIOException();
      }
    }

    private void visit(
        PathFragment parentRelativePath, Dirent.Type type, boolean traversedSymlink) {
      try {
        Path path = parentDir.getRelative(parentRelativePath);

        if (type == Dirent.Type.SYMLINK) {
          traversedSymlink = true;

          FileStatus statFollow = path.statIfFound(Symlinks.FOLLOW);

          if (statFollow == null) {
            throw new IOException(
                String.format("child %s is a dangling symbolic link", parentRelativePath));
          }

          if (statFollow.isFile() && !statFollow.isSpecialFile()) {
            type = Dirent.Type.FILE;
          } else if (statFollow.isDirectory()) {
            type = Dirent.Type.DIRECTORY;
          } else {
            type = Dirent.Type.UNKNOWN;
          }
        }

        if (type == Dirent.Type.UNKNOWN) {
          throw new IOException(
              String.format("child %s has an unsupported type", parentRelativePath));
        }

        visitor.visit(parentRelativePath, type, traversedSymlink);

        if (type == Dirent.Type.DIRECTORY) {
          for (Dirent dirent : path.readdir(Symlinks.NOFOLLOW)) {
            PathFragment childPath = parentRelativePath.getChild(dirent.getName());
            Dirent.Type childType = dirent.getType();
            boolean finalTraversedSymlink = traversedSymlink;
            execute(() -> visit(childPath, childType, finalTraversedSymlink));
          }
        }
      } catch (IOException e) {
        // We can't throw checked exceptions here since AQV expects Runnables
        throw new IORuntimeException(e);
      }
    }
  }

  /**
   * Recursively visits all descendants under a directory.
   *
   * <p>{@link TreeArtifactVisitor#visit} is invoked on {@code visitor} for each directory, file,
   * and symlink under the given {@code parentDir}, including {@code parentDir} itself.
   *
   * <p>This method is intended to provide uniform semantics for constructing a tree artifact,
   * including special logic that validates directory entries. Invalid directory entries include a
   * symlink that traverses outside of the tree artifact and any entry of {@link
   * Dirent.Type#UNKNOWN}, such as a named pipe.
   *
   * <p>The visitor will be called on multiple threads in parallel. Accordingly, it must be
   * thread-safe.
   *
   * @throws IOException if there is any problem reading or validating outputs under the given tree
   *     artifact directory, or if {@link TreeArtifactVisitor#visit} throws {@link IOException}
   */
  public static void visitTree(Path parentDir, TreeArtifactVisitor treeArtifactVisitor)
      throws IOException, InterruptedException {
    Visitor visitor = new Visitor(parentDir, treeArtifactVisitor);
    visitor.run();
  }

  /** Builder for a {@link TreeArtifactValue}. */
  public static final class Builder {
    private final ImmutableSortedMap.Builder<TreeFileArtifact, FileArtifactValue> childData =
        childDataBuilder();
    private ArchivedRepresentation archivedRepresentation;
    private PathFragment resolvedPath;
    private final SpecialArtifact parent;

    Builder(SpecialArtifact parent) {
      checkArgument(parent.isTreeArtifact(), "%s is not a tree artifact", parent);
      this.parent = parent;
    }

    /**
     * Adds a child to this builder.
     *
     * <p>The child's {@linkplain TreeFileArtifact#getParent parent} <em>must</em> match the parent
     * with which this builder was initialized.
     *
     * <p>Children may be added in any order. The children are sorted prior to constructing the
     * final {@link TreeArtifactValue}.
     *
     * @return {@code this} for convenience
     */
    @CanIgnoreReturnValue
    public Builder putChild(TreeFileArtifact child, FileArtifactValue metadata) {
      checkArgument(
          child.getParent().equals(parent),
          "While building TreeArtifactValue for %s, got %s with parent %s",
          parent,
          child,
          child.getParent());
      childData.put(child, metadata);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setArchivedRepresentation(
        ArchivedTreeArtifact archivedTreeArtifact, FileArtifactValue metadata) {
      return setArchivedRepresentation(
          ArchivedRepresentation.create(archivedTreeArtifact, metadata));
    }

    @CanIgnoreReturnValue
    public Builder setArchivedRepresentation(ArchivedRepresentation archivedRepresentation) {
      checkNotNull(archivedRepresentation);
      checkState(
          this.archivedRepresentation == null,
          "Tried to add 2 archived representations for: %s",
          parent);
      checkArgument(
          parent.equals(archivedRepresentation.archivedTreeFileArtifact().getParent()),
          "Cannot add archived representation: %s for a mismatching tree artifact: %s",
          archivedRepresentation,
          parent);
      this.archivedRepresentation = archivedRepresentation;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setResolvedPath(PathFragment resolvedPath) {
      checkArgument(resolvedPath.isAbsolute(), resolvedPath);
      checkState(
          this.resolvedPath == null, "Tried to set resolved path multiple times for: %s", parent);
      this.resolvedPath = resolvedPath;
      return this;
    }

    /** Builds the final {@link TreeArtifactValue}. */
    public TreeArtifactValue build() {
      ImmutableSortedMap<TreeFileArtifact, FileArtifactValue> finalChildData =
          childData.buildOrThrow();
      if (finalChildData.isEmpty() && archivedRepresentation == null && resolvedPath == null) {
        return EMPTY;
      }

      Fingerprint fingerprint = new Fingerprint();
      boolean entirelyRemote =
          archivedRepresentation == null || archivedRepresentation.archivedFileValue().isRemote();

      long totalChildSize = 0;
      for (Map.Entry<TreeFileArtifact, FileArtifactValue> childData : finalChildData.entrySet()) {
        // Digest will be deterministic because children are sorted.
        fingerprint.addPath(childData.getKey().getParentRelativePath());
        FileArtifactValue metadata = childData.getValue();
        metadata.addTo(fingerprint);

        // Tolerate a mix of local and remote children (b/152496153#comment80).
        entirelyRemote &= metadata.isRemote();

        if (metadata.getType() == FileStateType.REGULAR_FILE) {
          totalChildSize += metadata.getSize();
        }
      }

      if (archivedRepresentation != null) {
        archivedRepresentation.archivedFileValue().addTo(fingerprint);
      }

      return new TreeArtifactValue(
          fingerprint.digestAndReset(),
          finalChildData,
          totalChildSize,
          archivedRepresentation,
          resolvedPath,
          entirelyRemote);
    }
  }
}
