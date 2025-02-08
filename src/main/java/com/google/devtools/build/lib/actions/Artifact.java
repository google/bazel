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

package com.google.devtools.build.lib.actions;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.FileRootApi;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.PathStrippable;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.ExecutionPhaseSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * An Artifact represents a file used by the build system, whether it's a source file or a derived
 * (output) file. Not all Artifacts have a corresponding FileTarget object in the <code>
 * build.lib.packages</code> API: for example, low-level intermediaries internal to a given rule,
 * such as a Java class files or C++ object files. However all FileTargets have a corresponding
 * Artifact.
 *
 * <p>In any given call to SkyframeExecutor#buildArtifacts(), no two Artifacts in the action graph
 * may refer to the same path.
 *
 * <p>Artifacts generally fall into two classifications, source and derived, but there exist a few
 * other cases that are fuzzy and difficult to classify. The following cases exist:
 *
 * <ul>
 *   <li>Well-formed source Artifacts will have null generating Actions and a root that is
 *       orthogonal to execRoot. (With the root coming from the package path.)
 *   <li>Well-formed derived Artifacts will have non-null generating Actions, and a root that is
 *       below execRoot.
 *   <li>Symlinked include source Artifacts under the output/include tree will appear to be derived
 *       artifacts with null generating Actions.
 *   <li>Some derived Artifacts, mostly in the genfiles tree and mostly discovered during include
 *       validation, will also have null generating Actions.
 * </ul>
 *
 * <p>See {@link ArtifactRoot} for a detailed example on root, execRoot, and related paths.
 *
 * <p>In the usual case, an Artifact represents a single file. However, an Artifact may also
 * represent the following:
 *
 * <ul>
 *   <li>A TreeArtifact, which is a directory containing a tree of unknown {@link Artifact}s. In the
 *       future, Actions will be able to examine these files as inputs and declare them as outputs
 *       at execution time, but this is not yet implemented. This is used for Actions where the
 *       inputs and/or outputs might not be discoverable except during Action execution.
 *   <li>A directory of unknown contents, but not a TreeArtifact. This is a legacy facility and
 *       should not be used by any new rule implementations. In particular, the file system cache
 *       integrity checks fail for directories.
 *   <li>A runfiles tree.
 *   <li>A 'constant metadata' special Artifact. These represent real files, changes to which are
 *       ignored by the build system. They are useful for files which change frequently but do not
 *       affect the result of a build, such as timestamp files.
 *   <li>A 'Fileset' special Artifact. This is a legacy type of Artifact and should not be used by
 *       new rule implementations.
 *   <li>A 'symlink' special Artifact. While a symlink can also be represented by a regular
 *       Artifact, using a symlink special Artifact would result in deriving the Artifact's SkyValue
 *       from the symlinks themselves (lstat, not stat), and not following the symlinks like in
 *       regular Artifacts. The underlying symlink can be unresolved, otherwise known as a dangling
 *       symlink.
 * </ul>
 *
 * <p>While Artifact implements {@link SkyKey} for memory-saving purposes, Skyframe requests
 * involving artifacts should always go through {@link Artifact#key} since ordinary derived
 * artifacts should not be requested directly from Skyframe.
 */
public abstract sealed class Artifact
    implements FileType.HasFileType,
        ActionInput,
        FileApi,
        Comparable<Artifact>,
        CommandLineItem,
        ExecutionPhaseSkyKey
    permits SourceArtifact, DerivedArtifact {

  public static final Depset.ElementType TYPE = Depset.ElementType.of(Artifact.class);

  /** Compares artifact according to their exec paths. Sorts null values first. */
  @SerializationConstant
  @SuppressWarnings("ReferenceEquality") // "a == b" is an optimization
  public static final Comparator<Artifact> EXEC_PATH_COMPARATOR =
      (a, b) -> {
        if (a == b) {
          return 0;
        } else if (a == null) {
          return -1;
        } else if (b == null) {
          return 1;
        } else {
          return a.execPath.compareTo(b.execPath);
        }
      };

  /** Compares artifact according to their root relative paths. Sorts null values first. */
  @SuppressWarnings("ReferenceEquality") // "a == b" is an optimization
  public static final Comparator<Artifact> ROOT_RELATIVE_PATH_COMPARATOR =
      (a, b) -> {
        if (a == b) {
          return 0;
        } else if (a == null) {
          return -1;
        } else if (b == null) {
          return 1;
        } else {
          int result = a.getRootRelativePath().compareTo(b.getRootRelativePath());
          if (result == 0) {
            // Use the full exec path as a fallback if the root-relative paths are the same, thus
            // avoiding problems when ImmutableSortedMaps are switched from EXEC_PATH_COMPARATOR.
            return a.execPath.compareTo(b.execPath);
          } else {
            return result;
          }
        }
      };

  /**
   * {@link com.google.devtools.build.lib.skyframe.ArtifactFunction} does direct filesystem access
   * without declaring Skyframe dependencies if the artifact is a source directory. However, that
   * filesystem access is not invalidated on incremental builds, and we have no plans to fix it,
   * since general consumption of source directories in this way is unsound. Therefore no new bugs
   * are created by declaring {@link com.google.devtools.build.lib.skyframe.ArtifactFunction} to be
   * hermetic.
   *
   * <p>TODO(janakr): Avoid this issue entirely by giving {@link SourceArtifact} its own {@code
   * SkyFunction}. Then we can just declare that function to be non-hermetic. That will also save
   * memory since we can make mandatory source artifacts their own SkyKeys!
   */
  public static final SkyFunctionName ARTIFACT = SkyFunctionName.createHermetic("ARTIFACT");

  /**
   * Returns a {@link SkyKey} that, when built, will produce this artifact. For source artifacts and
   * generated artifacts that may aggregate other artifacts, returns the artifact itself. For normal
   * generated artifacts, returns the key of the generating action.
   *
   * <p>Callers should use this method (or the related ones below) in preference to directly
   * requesting an {@link Artifact} to be built by Skyframe, since ordinary derived artifacts should
   * never be directly built by Skyframe.
   */
  @ThreadSafety.ThreadSafe
  public static SkyKey key(Artifact artifact) {
    if (artifact.isTreeArtifact()
        || artifact.isRunfilesTree()
        || !artifact.hasKnownGeneratingAction()) {
      return artifact;
    }

    return ((DerivedArtifact) artifact).getGeneratingActionKey();
  }

  public static <T extends Artifact> Iterable<SkyKey> keys(Iterable<T> artifacts) {
    return artifacts instanceof Collection<T> collection
        ? keys(collection)
        : Iterables.transform(artifacts, Artifact::key);
  }

  public static <T extends Artifact> Collection<SkyKey> keys(Collection<T> artifacts) {
    return artifacts instanceof List<T> list
        ? keys(list)
        // Use Collections2 instead of Iterables#transform to ensure O(1) size().
        : Collections2.transform(artifacts, Artifact::key);
  }

  public static <T extends Artifact> List<SkyKey> keys(List<T> artifacts) {
    return Lists.transform(artifacts, Artifact::key);
  }

  @Override
  public int compareTo(Artifact o) {
    return EXEC_PATH_COMPARATOR.compare(this, o);
  }

  /** A Predicate that evaluates to true if the Artifact is not a runfiles tree. */
  public static final Predicate<Artifact> RUNFILES_FILTER = input -> !input.isRunfilesTree();

  private final ArtifactRoot root;

  private final int hashCode;
  private final PathFragment execPath;

  private Artifact(ArtifactRoot root, PathFragment execPath, int hashCodeWithOwner) {
    Preconditions.checkNotNull(root);
    // Use a precomputed hashcode since there tends to be massive hash-based collections of
    // artifacts. Importantly, the hashcode ought to incorporate the artifact's owner to prevent a
    // hash collision on the same exec path but a different owner (this is the common case for
    // multiple aspects that produce the same output file).
    this.hashCode = hashCodeWithOwner;
    this.root = root;
    this.execPath = execPath;
  }

  /** An artifact corresponding to a file in the output tree, generated by an {@link Action}. */
  public static sealed class DerivedArtifact extends Artifact implements PathStrippable
      permits SpecialArtifact, TreeFileArtifact, ArchivedTreeArtifact {

    /**
     * An {@link ActionLookupKey} until {@link #setGeneratingActionKey} is set, at which point it is
     * an {@link ActionLookupData}, whose {@link ActionLookupData#getActionLookupKey} will be the
     * same as the original value of owner.
     *
     * <p>We overload this field in order to save memory.
     */
    private Object owner;

    /**
     * Content-based output paths are experimental. Only derived artifacts that are explicitly opted
     * in by their creating rules should use them and only when {@link
     * com.google.devtools.build.lib.analysis.config.BuildConfigurationValue#useContentBasedOutputPaths}
     * is on.
     */
    private final boolean contentBasedPath;

    /** Standard factory method for derived artifacts. */
    public static DerivedArtifact create(
        ArtifactRoot root, PathFragment execPath, ActionLookupKey owner) {
      return create(root, execPath, owner, /*contentBasedPath=*/ false);
    }

    /**
     * Same as {@link #create(ArtifactRoot, PathFragment, ActionLookupKey)} but includes the option
     * to use a content-based path for this artifact (see {@link
     * com.google.devtools.build.lib.analysis.config.BuildConfigurationValue#useContentBasedOutputPaths}).
     */
    public static DerivedArtifact create(
        ArtifactRoot root, PathFragment execPath, ActionLookupKey owner, boolean contentBasedPath) {
      return new DerivedArtifact(root, execPath, owner, contentBasedPath);
    }

    @VisibleForSerialization
    DerivedArtifact(ArtifactRoot root, PathFragment execPath, Object owner) {
      this(root, execPath, owner, /*contentBasedPath=*/ false);
    }

    private DerivedArtifact(
        ArtifactRoot root, PathFragment execPath, Object owner, boolean contentBasedPath) {
      super(root, execPath, HashCodes.hashObjects(execPath, getOwnerToUseForHashCode(owner)));
      Preconditions.checkState(
          !root.getExecPath().isEmpty(), "Derived root has no exec path: %s, %s", root, execPath);
      this.owner = Preconditions.checkNotNull(owner);
      this.contentBasedPath = contentBasedPath;
    }

    /**
     * Called when a configured target's actions are being collected. {@code generatingActionKey}
     * must have the same owner as this artifact's current {@link #getArtifactOwner}.
     */
    @VisibleForTesting
    public final void setGeneratingActionKey(ActionLookupData generatingActionKey) {
      Preconditions.checkState(
          this.owner != OMITTED_FOR_SERIALIZATION, "Owner was omitted for serialization: %s", this);
      Preconditions.checkState(
          this.owner instanceof ActionLookupKey,
          "Already set generating action key: %s (%s %s)",
          this,
          this.owner,
          generatingActionKey);
      Preconditions.checkState(
          Preconditions.checkNotNull(generatingActionKey, this).getActionLookupKey().equals(owner),
          "Owner of generating action key not same as artifact's owner: %s (%s %s)",
          this,
          this.owner,
          generatingActionKey);
      this.owner = generatingActionKey;
    }

    @VisibleForTesting
    public final boolean hasGeneratingActionKey() {
      return this.owner instanceof ActionLookupData;
    }

    /** Can only be called once {@link #setGeneratingActionKey} is called. */
    public final ActionLookupData getGeneratingActionKey() {
      Preconditions.checkState(owner instanceof ActionLookupData, "Bad owner: %s %s", this, owner);
      return (ActionLookupData) owner;
    }

    @Override
    public final ActionLookupKey getArtifactOwner() {
      Preconditions.checkState(
          this.owner != OMITTED_FOR_SERIALIZATION, "Owner was omitted for serialization: %s", this);
      return owner instanceof ActionLookupData
          ? getGeneratingActionKey().getActionLookupKey()
          : (ActionLookupKey) owner;
    }

    /**
     * Returns the object to use for the hash code for this artifact's owner, with the goal of being
     * consistent across calls to {@link #setGeneratingActionKey} and also serialization.
     */
    private static Object getOwnerToUseForHashCode(Object owner) {
      return owner instanceof ActionLookupData actionLookupData
          ? actionLookupData.getActionLookupKey()
          : owner;
    }

    @Override
    public final Label getOwnerLabel() {
      return getArtifactOwner().getLabel();
    }

    @Override
    public final String toDebugString() {
      if (hasGeneratingActionKey() || owner == OMITTED_FOR_SERIALIZATION) {
        return super.toDetailString() + " (" + owner + ")";
      }
      return super.toDebugString();
    }

    @Override
    public final PathFragment getRootRelativePath() {
      return getExecPath().relativeTo(getRoot().getExecPath());
    }

    @Override
    final boolean ownersEqual(Artifact other) {
      DerivedArtifact that = (DerivedArtifact) other;
      if (!(this.owner instanceof ActionLookupData) || !(that.owner instanceof ActionLookupData)) {
        // Happens when at least one of these artifacts hasn't had its generating action key set
        // yet, so its configured target is still being analyzed. Tolerate.
        return this.getArtifactOwner().equals(that.getArtifactOwner());
      }
      return this.owner.equals(that.owner);
    }

    @Override
    public boolean contentBasedPath() {
      return contentBasedPath;
    }

    @Override
    public String expand(UnaryOperator<PathFragment> stripPaths) {
      return stripPaths.apply(getExecPath()).getPathString();
    }
  }

  /** Supplies {@link SourceArtifact} instances and allows for interning of derived artifacts. */
  public interface ArtifactSerializationContext {

    SourceArtifact getSourceArtifact(PathFragment execPath, Root root, ArtifactOwner owner);

    /**
     * Whether to include the generating action key when serializing the given derived artifact.
     *
     * <p>If {@code false} is returned, upon deserialization the generating action key is replaced
     * with the marker {@link #OMITTED_FOR_SERIALIZATION}. The artifact is then only intended for
     * use with {@link #equalsWithoutOwner} or {@link OwnerlessArtifactWrapper} - any operation
     * accessing the generating action key will fail.
     */
    default boolean includeGeneratingActionKey(DerivedArtifact artifact) {
      return true;
    }

    default DerivedArtifact intern(DerivedArtifact original) {
      return original;
    }
  }

  /**
   * Marker stored in place of the generating action key for deserialized artifacts when {@link
   * ArtifactSerializationContext#includeGeneratingActionKey} is {@code false}.
   */
  @SerializationConstant @VisibleForSerialization
  static final Object OMITTED_FOR_SERIALIZATION =
      new Object() {
        @Override
        public String toString() {
          return "OMITTED_FOR_SERIALIZATION";
        }
      };

  public final Path getPath() {
    return root.getRoot().getRelative(getRootRelativePath());
  }

  public boolean hasParent() {
    return getParent() != null;
  }

  /**
   * Returns the parent Artifact containing this Artifact. Artifacts without parents shall return
   * null.
   */
  @Nullable
  public SpecialArtifact getParent() {
    return null;
  }

  /**
   * Returns the directory name of this artifact, similar to dirname(1).
   *
   * <p> The directory name is always a relative path to the execution directory.
   */
  public final String getDirname() {
    return getDirname(execPath);
  }

  private static String getDirname(PathFragment execPath) {
    PathFragment parent = execPath.getParentDirectory();
    return (parent == null) ? "/" : parent.getSafePathString();
  }

  @Override
  public final String getDirnameForStarlark(StarlarkSemantics semantics) {
    return getDirname(PathMapper.loadFrom(semantics).map(execPath));
  }

  /** Returns the base file name of this artifact, similar to basename(1). */
  @Override
  public final String getFilename() {
    return execPath.getBaseName();
  }

  @Override
  public final String getExtension() {
    return execPath.getFileExtension();
  }

  /**
   * Checks whether this artifact is of the supplied file type.
   *
   * <p>Prefer this method to pulling out strings from the Artifact and passing to {@link
   * FileType#apply(String)} manually. This method has been optimized to generate a minimum of
   * garbage.
   */
  public boolean isFileType(FileType fileType) {
    return fileType.matches(this);
  }

  /** Checks whether this artifact is of one of the types in the supplied set. */
  public boolean isFileType(FileTypeSet fileTypeSet) {
    return fileTypeSet.matches(filePathForFileTypeMatcher());
  }

  @Override
  public final String filePathForFileTypeMatcher() {
    return execPath.filePathForFileTypeMatcher();
  }

  @Override
  public final String expandToCommandLine() {
    return getExecPathString();
  }

  /** Returns the artifact's owning label. May be null. */
  @Nullable
  public final Label getOwner() {
    return getOwnerLabel();
  }

  /**
   * Gets the {@code ActionLookupKey} of the {@code ConfiguredTarget} that owns this artifact, if it
   * was set. Otherwise, this should be a dummy value -- either {@link ArtifactOwner#NULL_OWNER} or
   * a dummy owner set in tests. Such a dummy value should only occur for source artifacts if
   * created without specifying the owner, or for special derived artifacts, such as build info
   * artifacts.
   */
  public abstract ArtifactOwner getArtifactOwner();

  /**
   * Returns the root beneath which this Artifact resides, if any. This may be one of the
   * package-path entries (for source Artifacts), or one of the bin, genfiles or includes dirs (for
   * derived Artifacts). It will always be an ancestor of getPath().
   */
  public final ArtifactRoot getRoot() {
    return root;
  }

  @Override
  public final FileRootApi getRootForStarlark(StarlarkSemantics semantics) {
    return PathMapper.loadFrom(semantics).mapRoot(this);
  }

  @Override
  public final PathFragment getExecPath() {
    return execPath;
  }

  @Override
  public String getExecPathStringForStarlark(StarlarkSemantics semantics) {
    return PathMapper.loadFrom(semantics).getMappedExecPathString(this);
  }

  /**
   * Returns the relative path to this artifact relative to its root. It makes no guarantees as to
   * the semantic meaning or the completeness of the returned path value. In other words, no
   * assumptions should be made in terms of where the root portion of the path ends, and the
   * returned value almost always needs to be used in conjunction with its root.
   *
   * <p>{#link Artifact#getOutputDirRelativePath()} is more versatile for general use cases.
   */
  public abstract PathFragment getRootRelativePath();

  /**
   * Returns the fully-qualified package path to this artifact. By "fully-qualified", it means the
   * returned path is prefixed with "external/<repository name>" if this artifact is in an external
   * repository.
   *
   * <p>Do not call this method just because you need a path prefixed with the "external/<repository
   * name>" fragment for external repository artifacts. {@link * Artifact#getOutputDirRelativePath}
   * is the right one to use in almost all cases.
   *
   * @deprecated This method is only to be used for $(location) and getOutputDirRelativePath
   *     implementations.
   */
  @Deprecated
  public PathFragment getPathForLocationExpansion() {
    return getRootRelativePath();
  }

  /**
   * Returns the path to this artifact relative to an output directory, e.g. the bin directory. Note
   * that this is available on every Artifact type, including source artifacts. As a matter of fact,
   * one of its most common use cases is to construct a derived artifact's output path out of a
   * sibling source artifact's by replacing the basename in its output-dir-relative path.
   */
  public PathFragment getOutputDirRelativePath(boolean siblingRepositoryLayout) {
    return getRootRelativePath();
  }

  /**
   * Returns the path to this artifact relative to its repository root. As a result, the returned
   * path always starts with a corresponding package name, if exists.
   */
  public PathFragment getRepositoryRelativePath() {
    PathFragment relativePath = getRootRelativePath();
    // External artifacts under legacy roots are still prefixed with "external/<repo name>".
    if (root.isLegacy() && relativePath.startsWith(LabelConstants.EXTERNAL_PATH_PREFIX)) {
      relativePath = relativePath.subFragment(2);
    }
    return relativePath;
  }

  /** Returns this.getExecPath().getPathString(). */
  @Override
  public final String getExecPathString() {
    return execPath.getPathString();
  }

  public final String getRootRelativePathString() {
    return getRootRelativePath().getPathString();
  }

  public final String getRepositoryRelativePathString() {
    return getRepositoryRelativePath().getPathString();
  }

  @Override
  public boolean isSymlink() {
    return false;
  }

  /**
   * Returns the path of this Artifact relative to this containing Artifact. Since ordinary
   * Artifacts correspond to only one Artifact -- itself -- for ordinary Artifacts, this just
   * returns the empty path. For special Artifacts, returns {@code null}.
   */
  public PathFragment getParentRelativePath() {
    return PathFragment.EMPTY_FRAGMENT;
  }

  @Override
  public String getTreeRelativePathString() throws EvalException {
    throw Starlark.errorf(
        "tree_relative_path not allowed for files that are not tree artifact files.");
  }

  /**
   * Returns true iff this is a source Artifact as determined by its path and root relationships.
   * Note that this will report all Artifacts in the output tree, including in the include symlink
   * tree, as non-source.
   *
   * <p>An {@link Artifact} is a {@link SourceArtifact} iff this returns true, and a {@link
   * DerivedArtifact} otherwise.
   */
  @Override
  public final boolean isSourceArtifact() {
    return root.isSourceRoot();
  }

  /**
   * Returns true iff this artifact has a generating action, and that generating action is known.
   */
  public boolean hasKnownGeneratingAction() {
    return !isSourceArtifact();
  }

  /**
   * Returns true iff this artifact represents a runfiles tree.
   *
   * <p>If true, this artifact is necessarily a {@link DerivedArtifact}.
   */
  public boolean isRunfilesTree() {
    return false;
  }

  /**
   * Returns true iff this is a TreeArtifact representing a directory tree containing Artifacts.
   *
   * <p>if true, this artifact is necessarily a {@link SpecialArtifact} with type {@link
   * SpecialArtifactType#TREE}.
   */
  public boolean isTreeArtifact() {
    return false;
  }

  /**
   * Returns {@code true} if this is a {@link TreeFileArtifact} that was created by an action which
   * declared an output directory, as opposed to an action that was generated by an action template
   * expansion.
   *
   * <p>Such artifacts should always be stored within a {@link
   * com.google.devtools.build.lib.skyframe.TreeArtifactValue} representing the declared directory
   * and all children, not individually like other derived artifacts.
   */
  public boolean isChildOfDeclaredDirectory() {
    return false;
  }

  /**
   * Returns whether the artifact represents a Fileset.
   *
   * <p>if true, this artifact is necessarily a {@link SpecialArtifact} with type {@link
   * SpecialArtifactType#FILESET}.
   */
  public boolean isFileset() {
    return false;
  }

  /** The disjunction of {@link #isTreeArtifact} and {@link #isFileset}. */
  @Override
  public boolean isDirectory() {
    return isTreeArtifact() || isFileset();
  }

  /**
   * Returns true iff metadata cache must return constant metadata for the given artifact.
   *
   * <p>If true, this artifact is necessarily a {@link SpecialArtifact} with type {@link
   * SpecialArtifactType#CONSTANT_METADATA}.
   */
  public boolean isConstantMetadata() {
    return false;
  }

  /**
   * For targets in external repositories, this returns the path the artifact live at in the
   * runfiles tree. For local targets, it returns the rootRelativePath.
   */
  public final PathFragment getRunfilesPath() {
    PathFragment relativePath = getRootRelativePath();
    // Runfile paths for external artifacts should be prefixed with "../<repo name>".
    if (root.isLegacy()) {
      // Root-relative paths of external artifacts under legacy roots are already prefixed with
      // "external/<repo name>". Just replace "external" with "..".
      if (relativePath.startsWith(LabelConstants.EXTERNAL_PATH_PREFIX)) {
        relativePath = relativePath.relativeTo(LabelConstants.EXTERNAL_PATH_PREFIX);
        relativePath = LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX.getRelative(relativePath);
      }
    } else {
      if (root.isExternal()) {
        // Both external source artifacts and external derived artifacts have their repo name as
        // their 2nd level directory name in their exec paths.
        // i.e. external/<repo name>/... and bazel-out/<repo name>/...
        // This is a pure coincidence, and the below line needs to be updated if any of the
        // directory structures change.
        String repoName = execPath.getSegment(1);
        relativePath =
            LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX
                .getRelative(repoName)
                .getRelative(relativePath);
      }
    }
    // We can't use root.isExternalSource() here since it needs to handle derived artifacts too.
    return relativePath;
  }

  @Override
  public final String getRunfilesPathString() {
    return getRunfilesPath().getPathString();
  }

  public final String prettyPrint() {
    // toDetailString would probably be more useful to users, but lots of tests rely on the
    // current values.
    return getRootRelativePath().toString();
  }

  @SuppressWarnings("EqualsGetClass") // Distinct classes of Artifact are never equal.
  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Artifact that)) {
      return false;
    }
    if (!getClass().equals(other.getClass())) {
      return false;
    }
    return equalsWithoutOwner(that) && ownersEqual(that);
  }

  private int hashCodeWithoutOwner() {
    return HashCodes.hashObjects(execPath, root);
  }

  final boolean equalsWithoutOwner(Artifact other) {
    return execPath.equals(other.execPath) && root.equals(other.root);
  }

  abstract boolean ownersEqual(Artifact other);

  @Override
  public final int hashCode() {
    return hashCode;
  }

  @Override
  public final String toString() {
    return "File:" + toDetailString();
  }

  /** Returns a string representing the complete artifact path information. */
  public final String toDetailString() {
    if (isSourceArtifact()) {
      // Source Artifact: relPath == execPath, & real path is not under execRoot
      return "[" + root + "]" + getRootRelativePathString();
    } else {
      // Derived Artifact: path and root are under execRoot
      //
      // TODO(blaze-team): this is misleading because execution_root isn't unique. Dig the
      // workspace name out and print that also.
      return "[[<execution_root>]" + root.getExecPath() + "]" + getRootRelativePathString();
    }
  }

  public String toDebugString() {
    if (getOwner() == null || getOwner().toPathFragment().equals(execPath)) {
      return toDetailString();
    }
    return toDetailString() + " (" + getArtifactOwner() + ")";
  }

  @Override
  public final SkyFunctionName functionName() {
    return ARTIFACT;
  }

  /** {@link Artifact#isSourceArtifact() is true.
   *
   * <p>Source artifacts have the property that unlike for output artifacts, direct file system
   * access for their contents should be safe, even in a distributed context.
   *
   * TODO(shahan): move {@link Artifact#getPath} to this subclass.
   */
  public static final class SourceArtifact extends Artifact {
    private final ArtifactOwner owner;

    @VisibleForTesting
    public SourceArtifact(ArtifactRoot root, PathFragment execPath, ArtifactOwner owner) {
      super(root, execPath, execPath.hashCode());
      this.owner = owner;
    }

    /**
     * Source artifacts do not consider their owners in equality checks, since their owners are
     * purely cosmetic.
     */
    @Override
    boolean ownersEqual(Artifact other) {
      return true;
    }

    @Override
    public PathFragment getRootRelativePath() {
      return getRoot().isExternal() ? getExecPath().subFragment(2) : getExecPath();
    }

    @Override
    public PathFragment getPathForLocationExpansion() {
      return getExecPath();
    }

    @Override
    public PathFragment getOutputDirRelativePath(boolean siblingRepositoryLayout) {
      return siblingRepositoryLayout ? getRepositoryRelativePath() : getExecPath();
    }

    @Override
    public PathFragment getRepositoryRelativePath() {
      return getRootRelativePath();
    }

    @Override
    public ArtifactOwner getArtifactOwner() {
      return owner;
    }

    @Override
    public Label getOwnerLabel() {
      return owner.getLabel();
    }

    boolean differentOwnerOrRoot(ArtifactOwner owner, ArtifactRoot root) {
      return !this.owner.equals(owner) || !this.getRoot().equals(root);
    }
  }

  /**
   * Special artifact types.
   *
   * @see SpecialArtifact
   */
  @VisibleForTesting
  public enum SpecialArtifactType {
    /** A runfiles tree. */
    RUNFILES,

    /** Google-specific legacy type. */
    FILESET,

    /**
     * A symlink. Not chased, can be dangling. All we care about is the return value of {@code
     * readlink()}.
     */
    UNRESOLVED_SYMLINK,

    /** A subtree containing multiple files and directories. */
    TREE,

    /** Special artifact type for workspace status information. */
    CONSTANT_METADATA,
  }

  /**
   * A special kind of artifact that either is a fileset or needs special metadata caching behavior.
   *
   * <p>We subclass {@link DerivedArtifact} instead of storing the special attributes inside in
   * order to save memory. The proportion of artifacts that are special is very small, and by not
   * having to keep around the attribute for the rest we save some memory.
   */
  public static final class SpecialArtifact extends DerivedArtifact {
    private final SpecialArtifactType type;

    @VisibleForTesting
    public static SpecialArtifact create(
        ArtifactRoot root, PathFragment execPath, ActionLookupKey owner, SpecialArtifactType type) {
      return new SpecialArtifact(root, execPath, owner, type);
    }

    @VisibleForSerialization
    SpecialArtifact(
        ArtifactRoot root, PathFragment execPath, Object owner, SpecialArtifactType type) {
      super(root, execPath, owner);
      this.type = type;
    }

    @Override
    public boolean isRunfilesTree() {
      return type == SpecialArtifactType.RUNFILES;
    }

    @Override
    public boolean isFileset() {
      return type == SpecialArtifactType.FILESET;
    }

    @Override
    public boolean isConstantMetadata() {
      return type == SpecialArtifactType.CONSTANT_METADATA;
    }

    @Override
    public boolean isTreeArtifact() {
      return type == SpecialArtifactType.TREE;
    }

    @Override
    public boolean isSymlink() {
      return type == SpecialArtifactType.UNRESOLVED_SYMLINK;
    }

    @Override
    public boolean hasParent() {
      return false;
    }

    @Override
    @Nullable
    public PathFragment getParentRelativePath() {
      return null;
    }

    @Override
    public boolean valueIsShareable() {
      return !isConstantMetadata();
    }

    @VisibleForSerialization
    SpecialArtifactType getSpecialArtifactType() {
      return type;
    }
  }

  /**
   * Artifact representing a single-file archive with the filesystem tree belonging to a {@linkplain
   * SpecialArtifact tree artifact}.
   *
   * <p>The archive is equivalent to the entire tree artifact -- it contains all of the {@linkplain
   * TreeFileArtifact children} (and nothing else) of the tree artifact with their filesystem
   * structure, relative to the {@linkplain SpecialArtifact#getExecPath() tree artifact directory}.
   */
  public static final class ArchivedTreeArtifact extends DerivedArtifact {
    private static final PathFragment DEFAULT_DERIVED_TREE_ROOT =
        PathFragment.create(":archived_tree_artifacts");

    private final SpecialArtifact treeArtifact;

    private ArchivedTreeArtifact(
        SpecialArtifact treeArtifact,
        ArtifactRoot root,
        PathFragment execPath,
        Object generatingActionKey) {
      super(root, execPath, generatingActionKey);
      Preconditions.checkArgument(
          treeArtifact.isTreeArtifact(), "Not a tree artifact: %s", treeArtifact);
      this.treeArtifact = treeArtifact;
    }

    @Override
    public SpecialArtifact getParent() {
      return treeArtifact;
    }

    /**
     * Creates an {@link ArchivedTreeArtifact} for a given tree artifact at the path inferred from
     * the provided tree.
     *
     * <p>Returned artifact is stored in a permanent location, therefore can be shared across
     * actions and builds.
     *
     * <p>Example: for a tree artifact of {@code bazel-out/k8-fastbuild/bin/directory} returns an
     * {@linkplain ArchivedTreeArtifact artifact} of: {@code
     * bazel-out/:archived_tree_artifacts/k8-fastbuild/bin/directory.zip}.
     */
    public static ArchivedTreeArtifact createForTree(SpecialArtifact treeArtifact) {
      return createInternal(
          treeArtifact,
          DEFAULT_DERIVED_TREE_ROOT,
          treeArtifact.getRootRelativePath().replaceName(treeArtifact.getFilename() + ".zip"),
          treeArtifact.getGeneratingActionKey());
    }

    /**
     * Creates an {@link ArchivedTreeArtifact} for a given tree artifact within provided derived
     * tree directory.
     *
     * <p>Example: for a tree artifact with root of {@code bazel-out/k8-fastbuild/bin} returns an
     * {@linkplain ArchivedTreeArtifact artifact} of: {@code
     * bazel-out/{derivedTreeRoot}/k8-fastbuild/bin/{rootRelativePath}} with root of: {@code
     * bazel-out/{derivedTreeRoot}/k8-fastbuild/bin}.
     *
     * <p>Such artifacts should only be used as outputs of intermediate spawns. Action execution
     * results must come from {@link #createForTree}.
     */
    public static ArchivedTreeArtifact createWithCustomDerivedTreeRoot(
        SpecialArtifact treeArtifact, PathFragment derivedTreeRoot, PathFragment rootRelativePath) {
      return createInternal(
          treeArtifact, derivedTreeRoot, rootRelativePath, treeArtifact.getGeneratingActionKey());
    }

    @VisibleForSerialization
    static ArchivedTreeArtifact createInternal(
        SpecialArtifact treeArtifact,
        PathFragment derivedTreeRoot,
        PathFragment rootRelativePath,
        Object generatingActionKey) {
      ArtifactRoot treeRoot = treeArtifact.getRoot();
      PathFragment archiveRoot = embedDerivedTreeRoot(treeRoot.getExecPath(), derivedTreeRoot);
      return new ArchivedTreeArtifact(
          treeArtifact,
          ArtifactRoot.asDerivedRoot(getExecRoot(treeRoot), RootType.Output, archiveRoot),
          archiveRoot.getRelative(rootRelativePath),
          generatingActionKey);
    }

    /**
     * Returns an exec path within the archived artifacts directory tree corresponding to the
     * provided one.
     *
     * <p>Example: {@code bazel-out/k8-fastbuild/bin ->
     * bazel-out/{customDerivedTreeRoot}/k8-fastbuild/bin}.
     */
    public static PathFragment getExecPathWithinArchivedArtifactsTree(PathFragment execPath) {
      return embedDerivedTreeRoot(execPath, DEFAULT_DERIVED_TREE_ROOT);
    }

    /**
     * Translates provided output {@code execPath} to one under provided derived tree root.
     *
     * <p>Example: {@code bazel-out/k8-fastbuild/bin ->
     * bazel-out/{derivedTreeRoot}/k8-fastbuild/bin}.
     */
    private static PathFragment embedDerivedTreeRoot(
        PathFragment execPath, PathFragment derivedTreeRoot) {
      return execPath
          .subFragment(0, 1)
          .getRelative(derivedTreeRoot)
          .getRelative(execPath.subFragment(1));
    }

    private static Path getExecRoot(ArtifactRoot artifactRoot) {
      // /output_base/execroot/bazel-out/k8-fastbuild/bin
      Path rootPath = artifactRoot.getRoot().asPath();
      PathFragment rootPathFragment = rootPath.asFragment();
      // /output_base/execroot
      PathFragment execRootPath =
          rootPathFragment.subFragment(
              0, rootPathFragment.segmentCount() - artifactRoot.getExecPath().segmentCount());
      return rootPath.getFileSystem().getPath(execRootPath);
    }
  }

  /**
   * A special kind of artifact that represents a concrete file created at execution time under its
   * associated parent TreeArtifact.
   *
   * <p>TreeFileArtifacts should be only created during execution time inside some special actions
   * to support action inputs and outputs that are unpredictable at analysis time. TreeFileArtifacts
   * should not be created directly by any rules at analysis time.
   *
   * <p>There are two types of TreeFileArtifacts:
   *
   * <ol>
   *   <li>Outputs under a directory created by an action using {@code declare_directory}. In this
   *       case, a single action creates both the parent and all of the children. Instances should
   *       be created by calling {@link #createTreeOutput}. {@link #isChildOfDeclaredDirectory} will
   *       return {@code true}.
   *   <li>Outputs of an action template expansion. In this case, the parent directory is not
   *       actually produced by any action, but rather serves as a placeholder for dependant actions
   *       to declare a dep on during analysis, before the children are known. The children are
   *       created by various actions (from the template expansion). Instances should be created by
   *       calling {@link #createTemplateExpansionOutput}. {@link #isChildOfDeclaredDirectory} will
   *       return {@code false}.
   * </ol>
   */
  public static final class TreeFileArtifact extends DerivedArtifact {
    private final SpecialArtifact parent;
    private final PathFragment parentRelativePath;

    /**
     * Creates a {@link TreeFileArtifact} representing a child of the given parent tree artifact.
     *
     * <p>The child should already have been created by the parent's generating action. For this
     * reason, {@link DerivedArtifact#hasGeneratingActionKey} on the parent must be {@code true}
     * when this is called. The child is set with the same generating action.
     */
    public static TreeFileArtifact createTreeOutput(
        SpecialArtifact parent, PathFragment parentRelativePath) {
      Preconditions.checkArgument(
          parent.hasGeneratingActionKey(),
          "%s has no generating action key (parent owner: %s, parent relative path: %s)",
          parent,
          parent.getArtifactOwner(),
          parentRelativePath);
      ActionLookupData generatingActionKey = parent.getGeneratingActionKey();
      Preconditions.checkArgument(
          !isActionTemplateExpansionKey(generatingActionKey.getActionLookupKey()),
          "%s owned by action template expansion %s (parent relative path: %s)",
          parent,
          generatingActionKey.getActionLookupKey(),
          parentRelativePath);
      return new TreeFileArtifact(parent, parentRelativePath, generatingActionKey);
    }

    /**
     * Convenience method for {@link #createTreeOutput(SpecialArtifact, PathFragment)} with a string
     * relative path.
     */
    public static TreeFileArtifact createTreeOutput(
        SpecialArtifact parent, String parentRelativePath) {
      return createTreeOutput(parent, PathFragment.create(parentRelativePath));
    }

    /**
     * Creates a {@link TreeFileArtifact} representing the output of an action generated dynamically
     * by an {@link ActionTemplate} during the execution phase.
     *
     * <p>The returned artifact does not yet have a generating action set.
     */
    public static TreeFileArtifact createTemplateExpansionOutput(
        SpecialArtifact parent, PathFragment parentRelativePath, ActionLookupKey owner) {
      Preconditions.checkArgument(
          isActionTemplateExpansionKey(owner),
          "Template expansion outputs must be owned by an action template expansion key, but %s is"
              + " owned by %s (parent relative path: %s)",
          parent,
          owner,
          parentRelativePath);
      return new TreeFileArtifact(parent, parentRelativePath, owner);
    }

    /**
     * Convenience method for {@link #createTemplateExpansionOutput(SpecialArtifact, PathFragment,
     * ActionLookupKey)} with a string relative path.
     */
    public static TreeFileArtifact createTemplateExpansionOutput(
        SpecialArtifact parent, String parentRelativePath, ActionLookupKey owner) {
      return createTemplateExpansionOutput(parent, PathFragment.create(parentRelativePath), owner);
    }

    @VisibleForSerialization
    TreeFileArtifact(SpecialArtifact parent, PathFragment parentRelativePath, Object owner) {
      super(parent.getRoot(), parent.getExecPath().getRelative(parentRelativePath), owner);
      Preconditions.checkArgument(
          parent.isTreeArtifact(),
          "The parent of TreeFileArtifact (parent-relative path: %s) is not a TreeArtifact: %s",
          parentRelativePath,
          parent);
      Preconditions.checkArgument(
          !parentRelativePath.containsUplevelReferences() && !parentRelativePath.isAbsolute(),
          "%s is not a proper normalized relative path",
          parentRelativePath);
      this.parent = parent;
      this.parentRelativePath = parentRelativePath;
    }

    @Override
    public SpecialArtifact getParent() {
      return parent;
    }

    @Override
    public PathFragment getParentRelativePath() {
      return parentRelativePath;
    }

    @Override
    public String getTreeRelativePathString() {
      return parentRelativePath.getPathString();
    }

    @Override
    public boolean isChildOfDeclaredDirectory() {
      return !isActionTemplateExpansionKey(getArtifactOwner());
    }

    private static boolean isActionTemplateExpansionKey(ActionLookupKey key) {
      return SkyFunctions.ACTION_TEMPLATE_EXPANSION.equals(key.functionName());
    }
  }

  // ---------------------------------------------------------------------------
  // Static methods to assist in working with Artifacts

  /** Formatter for execPath PathFragment output. */
  public static final Function<Artifact, String> ROOT_RELATIVE_PATH_STRING =
      artifact -> artifact.getRootRelativePath().getPathString();

  /**
   * Converts a collection of artifacts into execution-time path strings, and adds those to a given
   * collection. Runfiles trees are ignored by this method.
   */
  public static void addExecPaths(Iterable<Artifact> artifacts, Collection<String> output) {
    addNonRunfilesTreeArtifacts(artifacts, output, ActionInput::getExecPathString);
  }

  /**
   * Converts a collection of artifacts into the outputs computed by outputFormatter and adds them
   * to a given collection. Runfiles trees are ignored.
   */
  private static <E> void addNonRunfilesTreeArtifacts(
      Iterable<Artifact> artifacts,
      Collection<? super E> output,
      Function<? super Artifact, E> outputFormatter) {
    for (Artifact artifact : artifacts) {
      if (RUNFILES_FILTER.apply(artifact)) {
        output.add(outputFormatter.apply(artifact));
      }
    }
  }

  /**
   * Lazily converts artifacts into root-relative path strings. Runfiles trees are ignored by this
   * method.
   */
  public static Iterable<String> toRootRelativePaths(NestedSet<Artifact> artifacts) {
    return toRootRelativePaths(artifacts.toList());
  }

  /**
   * Lazily converts artifacts into root-relative path strings. Runfiles trees are ignored by this
   * method.
   */
  public static Iterable<String> toRootRelativePaths(Iterable<Artifact> artifacts) {
    return Iterables.transform(
        Iterables.filter(artifacts, RUNFILES_FILTER),
        artifact -> artifact.getRootRelativePath().getPathString());
  }

  /**
   * Lazily converts artifacts into execution-time path strings. Runfiles trees are ignored by this
   * * method.
   */
  public static Iterable<String> toExecPaths(Iterable<Artifact> artifacts) {
    return Iterables.transform(
        Iterables.filter(artifacts, RUNFILES_FILTER), ActionInput::getExecPathString);
  }

  /**
   * Converts a collection of artifacts into execution-time path strings, and returns those as an
   * immutable list. Runfiles trees are ignored by this method.
   *
   * <p>Avoid this method in production code - it flattens the given nested set unconditionally.
   */
  @VisibleForTesting
  public static List<String> asExecPaths(NestedSet<Artifact> artifacts) {
    return asExecPaths(artifacts.toList());
  }

  /**
   * Converts a collection of artifacts into execution-time path strings, and returns those as an
   * immutable list. Runfiles trees are ignored by this method.
   */
  public static List<String> asExecPaths(Iterable<Artifact> artifacts) {
    return ImmutableList.copyOf(toExecPaths(artifacts));
  }

  /**
   * Renders a collection of artifacts as execution-time paths and joins them into a single string.
   * Runfiles trees are ignored by this method.
   */
  public static String joinExecPaths(String delimiter, Iterable<Artifact> artifacts) {
    return Joiner.on(delimiter).join(toExecPaths(artifacts));
  }

  /**
   * Convenience method to filter the files to build for a certain filetype.
   *
   * @param artifacts the files to filter
   * @param allowedType the allowed filetype
   * @return all members of filesToBuild that are of one of the
   *     allowed filetypes
   */
  public static List<Artifact> filterFiles(Iterable<Artifact> artifacts, FileType allowedType) {
    List<Artifact> filesToBuild = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      if (allowedType.apply(artifact.getFilename())) {
        filesToBuild.add(artifact);
      }
    }
    return filesToBuild;
  }

  /**
   * Converts artifacts into their exec paths. Returns an immutable list.
   */
  public static List<PathFragment> asPathFragments(Iterable<? extends Artifact> artifacts) {
    return Streams.stream(artifacts).map(Artifact::getExecPath).collect(toImmutableList());
  }

  /**
   * Returns the exec paths of the input artifacts in alphabetical order.
   */
  public static ImmutableList<PathFragment> asSortedPathFragments(Iterable<Artifact> input) {
    return Streams.stream(input).map(Artifact::getExecPath).sorted().collect(toImmutableList());
  }


  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public void repr(Printer printer) {
    if (isSourceArtifact()) {
      printer.append("<source file " + getRootRelativePathString() + ">");
    } else {
      printer.append("<generated file " + getRootRelativePathString() + ">");
    }
  }

  /**
   * A utility class that compares {@link Artifact}s without taking their owners into account.
   * Should only be used for detecting action conflicts and merging shared action data.
   */
  public static final class OwnerlessArtifactWrapper {
    private final Artifact artifact;
    private final int hashCode;

    public OwnerlessArtifactWrapper(Artifact artifact) {
      this.artifact = artifact;
      this.hashCode = artifact.hashCodeWithoutOwner();
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof OwnerlessArtifactWrapper ownerlessArtifactWrapper
          && this.artifact.equalsWithoutOwner(ownerlessArtifactWrapper.artifact);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("artifact", artifact.toDebugString()).toString();
    }
  }
}
