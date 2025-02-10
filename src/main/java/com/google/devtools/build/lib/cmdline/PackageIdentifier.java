// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.cmdline;

import static com.google.devtools.build.lib.cmdline.RepositoryName.repositoryNameCodec;
import static com.google.devtools.build.lib.vfs.PathFragment.pathFragmentCodec;

import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Uniquely identifies a package. Contains the (canonical) name of the repository this package lives
 * in, and the package's path fragment.
 *
 * <p>Used as a {@link SkyKey} to request a {@link
 * com.google.devtools.build.lib.skyframe.PackageValue}.
 */
@Immutable
public final class PackageIdentifier implements SkyKey, Comparable<PackageIdentifier> {
  private static final SkyKeyInterner<PackageIdentifier> interner = SkyKey.newInterner();

  public static PackageIdentifier create(String repository, PathFragment pkgName)
      throws LabelSyntaxException {
    return create(RepositoryName.create(repository), pkgName);
  }

  public static PackageIdentifier create(RepositoryName repository, PathFragment pkgName) {
    return interner.intern(new PackageIdentifier(repository, pkgName));
  }

  /** Creates {@code PackageIdentifier} from a known-valid string. */
  public static PackageIdentifier createUnchecked(String repository, String pkgName) {
    return create(RepositoryName.createUnvalidated(repository), PathFragment.create(pkgName));
  }

  public static final PackageIdentifier EMPTY_PACKAGE_ID =
      createInMainRepo(PathFragment.EMPTY_FRAGMENT);

  public static PackageIdentifier createInMainRepo(String name) {
    return createInMainRepo(PathFragment.create(name));
  }

  public static PackageIdentifier createInMainRepo(PathFragment name) {
    return create(RepositoryName.MAIN, name);
  }

  public static PackageIdentifier createRootPackage(RepositoryName repository) {
    return create(repository, PathFragment.EMPTY_FRAGMENT);
  }

  /**
   * Tries to infer the package identifier from the given exec path. This method does not perform
   * any I/O, but looks solely at the structure of the exec path. The resulting identifier may
   * actually be a subdirectory of a package rather than a package, e.g.:
   *
   * <pre><code>
   * + WORKSPACE
   * + foo/BUILD
   * + foo/bar/bar.java
   * </code></pre>
   *
   * In this case, this method returns a package identifier for foo/bar, even though that is not a
   * package. Callers need to look up the actual package if needed.
   *
   * <p>Returns {@link Optional#empty()} if the path corresponds to an invalid label (e.g. with a
   * malformed repo name).
   */
  public static Optional<PackageIdentifier> discoverFromExecPath(
      PathFragment execPath, boolean forFiles, boolean siblingRepositoryLayout) {
    Preconditions.checkArgument(!execPath.isAbsolute(), execPath);
    PathFragment tofind =
        forFiles
            ? Preconditions.checkNotNull(
                execPath.getParentDirectory(), "Must pass in files, not root directory")
            : execPath;
    PathFragment prefix =
        siblingRepositoryLayout
            ? LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
            : LabelConstants.EXTERNAL_PATH_PREFIX;
    if (tofind.startsWith(prefix)) {
      // Using the path prefix can be either "external" or "..", depending on whether the sibling
      // repository layout is used.
      try {
        RepositoryName repository = RepositoryName.create(tofind.getSegment(1));
        return Optional.of(PackageIdentifier.create(repository, tofind.subFragment(2)));
      } catch (LabelSyntaxException e) {
        // The path corresponds to an invalid label.
        return Optional.empty();
      }
    } else {
      return Optional.of(PackageIdentifier.createInMainRepo(tofind));
    }
  }

  /**
   * The identifier for this repository. This is either "" or prefixed with an "@", e.g., "@myrepo".
   */
  private final RepositoryName repository;

  /** The name of the package. */
  private final PathFragment pkgName;

  /**
   * Precomputed hash code. Hash/equality is based on repository and pkgName. Note that due to weak
   * interning, x.equals(y) usually implies x==y.
   */
  private final int hashCode;

  private PackageIdentifier(RepositoryName repository, PathFragment pkgName) {
    this.repository = Preconditions.checkNotNull(repository);
    this.pkgName = Preconditions.checkNotNull(pkgName);
    this.hashCode = HashCodes.hashObjects(repository, pkgName);
  }

  public static PackageIdentifier parse(String input) throws LabelSyntaxException {
    if (input.contains(":")) {
      throw LabelParser.syntaxErrorf("invalid package identifier '%s': contains ':'", input);
    }
    LabelParser.Parts parts = LabelParser.Parts.parse(input + ":dummy_target");
    RepositoryName repoName =
        parts.repo() == null ? RepositoryName.MAIN : RepositoryName.createUnvalidated(parts.repo());
    return create(repoName, PathFragment.create(parts.pkg()));
  }

  public RepositoryName getRepository() {
    return repository;
  }

  public PathFragment getPackageFragment() {
    return pkgName;
  }

  /**
   * Returns a path to the source code for this package relative to the corresponding source root.
   * Returns pkgName for all repositories.
   */
  // TODO(bazel-team): This name is misleading, since the returned object is not a source root.
  // Maybe "getSourceRootRelative()"?
  public PathFragment getSourceRoot() {
    return pkgName;
  }

  /**
   * Get the top level dir after the root.
   *
   * <p>Used for some symlink planting strategies.
   */
  public String getTopLevelDir() {
    return getSourceRoot().isEmpty() ? "" : getSourceRoot().getSegment(0);
  }

  /**
   * Returns the package path fragment to derived artifacts for this package. Returns pkgName if
   * this is in the main repository or siblingRepositoryLayout is true. Otherwise, returns
   * external/[repository name]/[pkgName].
   */
  // TODO(bazel-team): Rename getDerivedArtifactPath or similar.
  public PathFragment getPackagePath(boolean siblingRepositoryLayout) {
    return repository.isMain() || siblingRepositoryLayout
        ? pkgName
        : LabelConstants.EXTERNAL_PATH_PREFIX
            .getRelative(repository.getName())
            .getRelative(pkgName);
  }

  public PathFragment getExecPath(boolean siblingRepositoryLayout) {
    return repository.getExecPath(siblingRepositoryLayout).getRelative(pkgName);
  }

  /**
   * Returns the runfiles/execRoot path for this repository (relative to the x.runfiles/main-repo/
   * directory).
   */
  public PathFragment getRunfilesPath() {
    return repository.getRunfilesPath().getRelative(pkgName);
  }

  /**
   * Returns the package in label syntax format.
   *
   * <p>Packages in the main repo are formatted without a repo qualifier.
   */
  // TODO(bazel-team): Maybe rename to "getDefaultForm"?
  public String getCanonicalForm() {
    return repository.getCanonicalForm() + "//" + pkgName;
  }

  /**
   * Returns an absolutely unambiguous canonical form for this package in label form. Parsing this
   * string in any environment, even when subject to repository mapping, should identify the same
   * package.
   */
  public String getUnambiguousCanonicalForm() {
    return repository.getNameWithAt() + "//" + pkgName;
  }

  /**
   * Returns a label representation for this package that is suitable for display. The returned
   * string is as simple as possible while referencing the current package when parsed in the
   * context of the main repository whose repository mapping is provided.
   *
   * @param mainRepositoryMapping the {@link RepositoryMapping} of the main repository
   * @return
   *     <dl>
   *       <dt><code>//some/pkg</code>
   *       <dd>if this package lives in the main repository
   *       <dt><code>@protobuf//some/pkg</code>
   *       <dd>if this package lives in a repository with "protobuf" as <code>name</code> of a
   *           repository in WORKSPACE or as apparent name of a Bzlmod dependency of the main module
   *       <dt><code>@@protobuf+//some/pkg</code>
   *       <dd>only with Bzlmod if the current package belongs to a repository that is not visible
   *           from the main module
   */
  public String getDisplayForm(@Nullable RepositoryMapping mainRepositoryMapping) {
    return repository.getDisplayForm(mainRepositoryMapping) + "//" + pkgName;
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.PACKAGE;
  }

  @Override
  public SkyKeyInterner<?> getSkyKeyInterner() {
    return interner;
  }

  /**
   * Returns the package path, possibly qualified with a repository name.
   *
   * <p>Packages that live in the main repo are stringified without a "@" qualifier or "//"
   * separator (e.g. "foo/bar"). All other packages include these (e.g. "@repo//foo/bar").
   */
  // TODO(bazel-team): The absence of "//" for the main repo seems strange. Can we eliminate
  // that disparity?
  @Override
  public String toString() {
    if (repository.isMain()) {
      return pkgName.getPathString();
    }
    return getCanonicalForm();
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof PackageIdentifier)) {
      return false;
    }
    PackageIdentifier that = (PackageIdentifier) object;
    return this.hashCode == that.hashCode
        && pkgName.equals(that.pkgName)
        && repository.equals(that.repository);
  }

  @Override
  public int hashCode() {
    return this.hashCode;
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // Performance optimization.
  public int compareTo(PackageIdentifier that) {
    // Fast-paths for the common case of the same package or a package in the same repository.
    if (this == that) {
      return 0;
    }
    if (repository == that.repository) {
      return pkgName.compareTo(that.pkgName);
    }
    return ComparisonChain.start()
        .compare(repository.getName(), that.repository.getName())
        .compare(pkgName, that.pkgName)
        .result();
  }

  public static PackageIdentifierLeafCodec packageIdentifierCodec() {
    return PackageIdentifierLeafCodec.INSTANCE;
  }

  public static DeferredObjectCodec<PackageIdentifier> deferredCodec() {
    return PackageIdentifierDeferredCodec.INSTANCE;
  }

  private static class PackageIdentifierDeferredCodec
      extends DeferredObjectCodec<PackageIdentifier> {
    private static final PackageIdentifierDeferredCodec INSTANCE =
        new PackageIdentifierDeferredCodec();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<PackageIdentifier> getEncodedClass() {
      return PackageIdentifier.class;
    }

    @Override
    public void serialize(
        SerializationContext context, PackageIdentifier obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serializeLeaf(obj, packageIdentifierCodec(), codedOut);
    }

    @Override
    public DeferredValue<PackageIdentifier> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      PackageIdentifier value = context.deserializeLeaf(codedIn, packageIdentifierCodec());
      return () -> value;
    }
  }

  private static class PackageIdentifierLeafCodec extends LeafObjectCodec<PackageIdentifier> {
    private static final PackageIdentifierLeafCodec INSTANCE = new PackageIdentifierLeafCodec();

    @Override
    public Class<PackageIdentifier> getEncodedClass() {
      return PackageIdentifier.class;
    }

    @Override
    public void serialize(
        LeafSerializationContext context, PackageIdentifier obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serializeLeaf(obj.getRepository(), repositoryNameCodec(), codedOut);
      context.serializeLeaf(obj.getPackageFragment(), pathFragmentCodec(), codedOut);
    }

    @Override
    public PackageIdentifier deserialize(
        LeafDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      RepositoryName repository = context.deserializeLeaf(codedIn, repositoryNameCodec());
      PathFragment pkgName = context.deserializeLeaf(codedIn, pathFragmentCodec());
      return create(repository, pkgName);
    }
  }
}
