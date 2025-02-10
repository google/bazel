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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.hash.HashFunction;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A value that represents a file for the purposes of up-to-dateness checks of actions.
 *
 * <p>It always stands for an actual file. In particular, tree artifacts and runfiles trees do not
 * have a corresponding {@link FileArtifactValue}. However, the file is not necessarily present in
 * the file system; this happens when intermediate build outputs are not downloaded (and maybe when
 * an input artifact of an action is missing?)
 *
 * <p>It makes its main appearance in {@code ActionExecutionValue.artifactData}. It has two main
 * uses:
 *
 * <ul>
 *   <li>This is how dependent actions get hold of the output metadata of their generated inputs.
 *   <li>This is how {@code FileSystemValueChecker} figures out which actions need to be invalidated
 *       (just propagating the invalidation up from leaf nodes is not enough, because the output
 *       tree may have been changed while Blaze was not looking)
 * </ul>
 *
 * <p>{@link FileArtifactValue} instance equality should only be used for testing purposes. To
 * determine whether a metadata is equivalent to another for invalidation purposes, use {@link
 * #couldBeModifiedSince} or {@link #wasModifiedSinceDigest}.
 */
@Immutable
@ThreadSafe
public abstract class FileArtifactValue implements SkyValue, HasDigest {
  /**
   * The type of the underlying file system object. If it is a regular file, then it is guaranteed
   * to have a digest. Otherwise it does not have a digest.
   */
  public abstract FileStateType getType();

  /**
   * Returns a digest of the content of the underlying file system object; must always return a
   * non-null value for instances of type {@link FileStateType#REGULAR_FILE} that are owned by an
   * {@code ActionExecutionValue}.
   *
   * <p>All instances of this interface must either have a digest or return a last-modified time.
   * Clients should prefer using the digest for content identification (e.g., for caching), and only
   * fall back to the last-modified time if no digest is available.
   *
   * <p>The return value is owned by this object and must not be modified.
   */
  @Override
  public abstract byte[] getDigest();

  /** Returns the file's size, or 0 if the underlying file system object is not a file. */
  // TODO(ulfjack): Throw an exception if it's not a file.
  public abstract long getSize();

  /**
   * Returns the last modified time; see the documentation of {@link #getDigest} for when this can
   * and should be called.
   */
  public abstract long getModifiedTime();

  /**
   * Returns a contents proxy (typically, a subset of the file system object's inode properties)
   * that can be used to detect modifications more cheaply (at the cost of increased chance of a
   * false negative) in situations where a digest would be too expensive to compute.
   *
   * <p>If no proxy is available, returns null.
   */
  @Nullable
  public FileContentsProxy getContentsProxy() {
    return null;
  }

  /**
   * Sets the contents proxy. If this metadata does not support setting the contents proxy, does
   * nothing.
   */
  public void setContentsProxy(FileContentsProxy proxy) {}

  @Nullable
  public byte[] getValueFingerprint() {
    // TODO(janakr): return fingerprint in other cases: symlink, directory.
    return getDigest();
  }

  /**
   * Returns the unresolved symlink target path.
   *
   * @throws UnsupportedOperationException if the metadata is not of symlink file type.
   */
  public String getUnresolvedSymlinkTarget() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns whether the file contents are inline, i.e., can be obtained directly from this {@link
   * FileArtifactValue} by calling {@link #getInputStream}.
   */
  public boolean isInline() {
    return false;
  }

  /**
   * Returns an input stream for the inline file contents.
   *
   * @throws UnsupportedOperationException if the file contents are not inline.
   */
  public InputStream getInputStream() {
    throw new UnsupportedOperationException();
  }

  /** Returns whether the file contents exist remotely. */
  public boolean isRemote() {
    return false;
  }

  /** Returns the location index for remote files. For non-remote files, returns 0. */
  public int getLocationIndex() {
    return 0;
  }

  /**
   * Returns the time when the remote file contents expire. If the contents never expire, including
   * when they're not remote, returns null.
   *
   * <p>The expiration time does not factor into equality, as it can be mutated by {@link
   * #setExpirationTime}.
   */
  @Nullable
  public Instant getExpirationTime() {
    return null;
  }

  /**
   * Sets the expiration time. If this metadata does not support setting the expiration time, does
   * nothing.
   */
  public void setExpirationTime(Instant newExpirationTime) {}

  /**
   * Returns whether the file contents are available (either locally, or remotely and not expired).
   */
  public final boolean isAlive(Instant now) {
    return getExpirationTime() == null || getExpirationTime().isAfter(now);
  }

  /**
   * Provides a best-effort determination whether the file was changed since the digest was
   * computed. This method performs file system I/O, so may be expensive. It's primarily intended to
   * avoid storing bad cache entries in an action cache. It should return true if there is a chance
   * that the file was modified since the digest was computed. Better not upload if we are not sure
   * that the cache entry is reliable.
   */
  // TODO(lberki): This is very similar to couldBeModifiedSince(). Check if we can unify these.
  public abstract boolean wasModifiedSinceDigest(Path path) throws IOException;

  /**
   * Returns whether the two {@link FileArtifactValue} instances could be considered the same for
   * purposes of action invalidation.
   */
  // TODO(lberki): This is very similar to wasModifiedSinceDigest(). Check if we can unify these.
  public final boolean couldBeModifiedSince(FileArtifactValue lastKnown) {
    if (this instanceof Singleton || lastKnown instanceof Singleton) {
      return true;
    }

    if (getType() != lastKnown.getType()) {
      return true;
    }

    if (getDigest() != null && lastKnown.getDigest() != null) {
      // If we know the digests, we can tell with certainty whether the file has changed.
      return !Arrays.equals(getDigest(), lastKnown.getDigest()) || getSize() != lastKnown.getSize();
    } else {
      // If not, we assume by default that the file has changed, but individual implementations
      // might know better. For example, regular local files can be compared by ctime or mtime.
      return couldBeModifiedByMetadata(lastKnown);
    }
  }

  /** Adds this file metadata to the given {@link Fingerprint}. */
  public final void addTo(Fingerprint fp) {
    byte[] digest = getDigest();
    if (digest != null) {
      fp.addBytes(digest);
    } else {
      // Use the timestamp if the digest is not present, but not both. Modifying a timestamp while
      // keeping the contents of a file the same should not cause rebuilds.
      fp.addLong(getModifiedTime());
    }
  }

  protected boolean couldBeModifiedByMetadata(FileArtifactValue lastKnown) {
    return true;
  }

  /**
   * Returns the real path at which the file contents this metadata refers to can be found.
   *
   * <p>If present, an artifact possessing this metadata is materialized in the filesystem as a
   * symlink to another artifact, but acts as a copy of that artifact for invalidation purposes.
   * Thus, all other metadata fields reflect the properties of the file system object found at the
   * real path. In particular, this means that {@link #getType} doesn't necessarily return {@link
   * FileStateType#SYMLINK}.
   *
   * <p>The path must be absolute and not contain any unresolved symlinks, i.e., calling {@link
   * Path#resolveSymbolicLinks} on it should yield the same path.
   *
   * <p>This allows such an artifact to be created as a symlink to the real path when lazily
   * materialized on disk, in situations where making a copy is undesirable (e.g. because it would
   * result in redundant downloads of the same remote output file) or impossible (e.g. because the
   * original is a source file or a local output file, and its contents cannot be obtained from the
   * digest). An output service is free to ignore this hint and materialize the artifact in some
   * other way (e.g. as a regular file backed by a FUSE filesystem).
   */
  @Nullable
  public PathFragment getResolvedPath() {
    return null;
  }

  /**
   * Marker interface for singleton implementations of this class.
   *
   * <p>Needed for a correct implementation of {@code equals}.
   */
  interface Singleton {}

  /**
   * Metadata for runfiles trees.
   *
   * <p>This should really be more nuanced so that runfiles trees don't need to be special-cased in
   * the local action cache, but it works well enough. The only downsides are that we don't detect
   * when someone changed a runfiles tree like we do for other output artifacts and a number of
   * extra branches.
   *
   * <p>In Skyframe, we check whether a runfiles tree changed based on {@link
   * RunfilesArtifactValue}, which does contain data about its contents.
   */
  @SerializationConstant
  public static final FileArtifactValue RUNFILES_TREE_MARKER = new SingletonMarkerValue();

  /** Data that marks that a file is not present on the filesystem. */
  @SerializationConstant
  public static final FileArtifactValue MISSING_FILE_MARKER = new SingletonMarkerValue();

  public static FileArtifactValue createForSourceArtifact(
      Artifact artifact, FileValue fileValue, XattrProvider xattrProvider) throws IOException {
    // Artifacts with known generating actions should obtain the derived artifact's SkyValue
    // from the generating action, instead.
    checkState(!artifact.hasKnownGeneratingAction());
    checkState(!artifact.isConstantMetadata());
    boolean isFile = fileValue.isFile();
    return create(
        artifact.getPath(),
        isFile,
        isFile ? fileValue.getSize() : 0,
        isFile ? fileValue.realFileStateValue().getContentsProxy() : null,
        isFile ? fileValue.getDigest() : null,
        xattrProvider);
  }

  public static FileArtifactValue createFromInjectedDigest(
      FileArtifactValue metadata, @Nullable byte[] digest) {
    return createForNormalFile(digest, metadata.getContentsProxy(), metadata.getSize());
  }

  @VisibleForTesting
  public static FileArtifactValue createForTesting(Artifact artifact) throws IOException {
    return createForTesting(artifact.getPath());
  }

  @VisibleForTesting
  public static FileArtifactValue createForTesting(Path path) throws IOException {
    // Caution: there's a race condition between stating the file and computing the digest. We need
    // to stat first, since we're using the stat to detect changes. We follow symlinks here to be
    // consistent with getDigest.
    return createFromStat(path, path.stat(Symlinks.FOLLOW), SyscallCache.NO_CACHE);
  }

  public static FileArtifactValue createFromStat(
      Path path, FileStatus stat, XattrProvider xattrProvider) throws IOException {
    return create(
        path,
        stat.isFile(),
        stat.getSize(),
        FileContentsProxy.create(stat),
        /* digest= */ null,
        xattrProvider);
  }

  private static FileArtifactValue create(
      Path path,
      boolean isFile,
      long size,
      FileContentsProxy proxy,
      @Nullable byte[] digest,
      XattrProvider xattrProvider)
      throws IOException {
    if (!isFile) {
      // In this case, we need to store the mtime because the action cache uses mtime for
      // directories to determine if this artifact has changed. We want this code path to go away
      // somehow.
      return new DirectoryArtifactValue(path.getLastModifiedTime());
    }
    if (digest == null) {
      digest = DigestUtils.getDigestWithManualFallback(path, xattrProvider);
    }
    checkState(digest != null, path);
    return createForNormalFile(digest, proxy, size);
  }

  public static FileArtifactValue createForVirtualActionInput(byte[] digest, long size) {
    return new RegularFileArtifactValue(digest, /* proxy= */ null, size);
  }

  public static FileArtifactValue createForUnresolvedSymlink(Artifact artifact) throws IOException {
    checkArgument(artifact.isSymlink());
    return createForUnresolvedSymlink(artifact.getPath());
  }

  public static FileArtifactValue createForUnresolvedSymlink(Path symlink) throws IOException {
    return new UnresolvedSymlinkArtifactValue(symlink);
  }

  public static FileArtifactValue createForNormalFile(
      byte[] digest, @Nullable FileContentsProxy proxy, long size) {
    return new RegularFileArtifactValue(digest, proxy, size);
  }

  /**
   * Create a FileArtifactValue using the {@link Path} and size. FileArtifactValue#create will
   * handle getting the digest using the Path and size values.
   */
  public static FileArtifactValue createForNormalFileUsingPath(
      Path path, long size, XattrProvider xattrProvider) throws IOException {
    return create(
        path, /* isFile= */ true, size, /* proxy= */ null, /* digest= */ null, xattrProvider);
  }

  public static FileArtifactValue createForDirectoryWithHash(byte[] digest) {
    return new HashedDirectoryArtifactValue(digest);
  }

  public static FileArtifactValue createForDirectoryWithMtime(long mtime) {
    return new DirectoryArtifactValue(mtime);
  }

  public static FileArtifactValue createForInlineFile(byte[] bytes, HashFunction hashFunction) {
    return new InlineFileArtifactValue(bytes, hashFunction.hashBytes(bytes).asBytes());
  }

  public static FileArtifactValue createForRemoteFile(byte[] digest, long size, int locationIndex) {
    return new RemoteFileArtifactValue(digest, size, locationIndex);
  }

  public static FileArtifactValue createForRemoteFileWithMaterializationData(
      byte[] digest, long size, int locationIndex, @Nullable Instant expirationTime) {
    return new RemoteFileArtifactValueWithMaterializationData(
        digest, size, locationIndex, expirationTime);
  }

  public static FileArtifactValue createFromExistingWithResolvedPath(
      FileArtifactValue delegate, PathFragment resolvedPath) {
    return new ResolvedSymlinkArtifactValue(delegate, resolvedPath);
  }

  /**
   * Creates a FileArtifactValue used as a 'proxy' input for other ArtifactValues. These are used in
   * {@link ActionCacheChecker}.
   */
  public static FileArtifactValue createProxy(byte[] digest) {
    checkNotNull(digest);
    return createForNormalFile(digest, /* proxy= */ null, /* size= */ 0);
  }

  private static String bytesToString(@Nullable byte[] bytes) {
    return bytes == null ? "null" : "0x" + BaseEncoding.base16().omitPadding().encode(bytes);
  }

  private static final class DirectoryArtifactValue extends FileArtifactValue {
    private final long mtime;

    private DirectoryArtifactValue(long mtime) {
      this.mtime = mtime;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DirectoryArtifactValue that)) {
        return false;
      }

      return mtime == that.mtime;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(mtime);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public byte[] getValueFingerprint() {
      return new Fingerprint()
          .addString(getClass().getCanonicalName())
          .addLong(mtime)
          .digestAndReset();
    }

    @Override
    public long getModifiedTime() {
      return mtime;
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
    public String toString() {
      return MoreObjects.toStringHelper(this).add("mtime", mtime).toString();
    }
  }

  private static final class HashedDirectoryArtifactValue extends FileArtifactValue {

    private final byte[] digest;

    private HashedDirectoryArtifactValue(byte[] digest) {
      this.digest = digest;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof HashedDirectoryArtifactValue that)) {
        return false;
      }

      return Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.DIRECTORY;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      // TODO(ulfjack): Ideally, we'd attempt to detect intra-build modifications here. I'm
      // consciously deferring work here as this code will most likely change again, and we're
      // already doing better than before by detecting inter-build modifications.
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("digest", bytesToString(digest)).toString();
    }
  }

  private static final class RegularFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    @Nullable private final FileContentsProxy proxy;
    private final long size;

    private RegularFileArtifactValue(
        @Nullable byte[] digest, @Nullable FileContentsProxy proxy, long size) {
      this.digest = digest;
      this.proxy = proxy;
      this.size = size;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RegularFileArtifactValue that)) {
        return false;
      }
      return Arrays.equals(digest, that.digest)
          && Objects.equals(proxy, that.proxy)
          && size == that.size;
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(Arrays.hashCode(digest), proxy, size);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      return proxy;
    }

    @Override
    public long getSize() {
      return size;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      if (proxy == null) {
        return false;
      }
      FileStatus stat = path.statIfFound(Symlinks.FOLLOW);
      return stat == null || !stat.isFile() || !proxy.equals(FileContentsProxy.create(stat));
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException(
          "regular file's mtime should never be called. (" + this + ")");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", size)
          .add("proxy", proxy)
          .toString();
    }

    @Override
    protected boolean couldBeModifiedByMetadata(FileArtifactValue lastKnown) {
      return size != lastKnown.getSize() || !Objects.equals(proxy, lastKnown.getContentsProxy());
    }
  }

  /** Metadata for remotely stored files. */
  private static class RemoteFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final long size;
    private final int locationIndex;

    private RemoteFileArtifactValue(byte[] digest, long size, int locationIndex) {
      this.digest = checkNotNull(digest);
      this.size = size;
      this.locationIndex = locationIndex;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RemoteFileArtifactValue that)) {
        return false;
      }

      return Arrays.equals(digest, that.digest)
          && size == that.size
          && locationIndex == that.locationIndex;
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(Arrays.hashCode(digest), size, locationIndex);
    }

    @Override
    public final FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public final byte[] getDigest() {
      return digest;
    }

    @Override
    public final long getSize() {
      return size;
    }

    @Override
    public final long getModifiedTime() {
      throw new UnsupportedOperationException(
          "RemoteFileArtifactValue doesn't support getModifiedTime");
    }

    @Override
    public final int getLocationIndex() {
      return locationIndex;
    }

    @Override
    public final boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public final boolean isRemote() {
      return true;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", size)
          .add("locationIndex", locationIndex)
          .toString();
    }
  }

  /**
   * Metadata for remotely stored files, with the additional ability to store a {@link
   * #getExpirationTime} modifiable via {@link #setExpirationTime}, and a {@link #getContentsProxy}
   * modifiable via {@link #setContentsProxy}.
   *
   * <p>This is used when the output mode allows for late materialization of remote outputs in the
   * local filesystem.
   */
  private static final class RemoteFileArtifactValueWithMaterializationData
      extends RemoteFileArtifactValue {
    private long expirationTime;
    @Nullable private FileContentsProxy proxy;

    private RemoteFileArtifactValueWithMaterializationData(
        byte[] digest, long size, int locationIndex, @Nullable Instant expirationTime) {
      super(digest, size, locationIndex);
      this.expirationTime = toEpochMilli(expirationTime);
    }

    private static long toEpochMilli(@Nullable Instant expirationTime) {
      return expirationTime != null ? expirationTime.toEpochMilli() : -1;
    }

    @Nullable
    private static Instant fromEpochMilli(long expirationTime) {
      return expirationTime >= 0 ? Instant.ofEpochMilli(expirationTime) : null;
    }

    @Override
    @Nullable
    public Instant getExpirationTime() {
      return fromEpochMilli(expirationTime);
    }

    @Override
    public void setExpirationTime(Instant expirationTime) {
      this.expirationTime = toEpochMilli(expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns non-null if the file contents have been materialized in the local filesystem.
     */
    @Override
    @Nullable
    public FileContentsProxy getContentsProxy() {
      return proxy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called when the file contents are materialized in the local filesystem.
     */
    @Override
    public void setContentsProxy(FileContentsProxy proxy) {
      this.proxy = proxy;
    }


    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RemoteFileArtifactValueWithMaterializationData that)) {
        return false;
      }

      return Arrays.equals(getDigest(), that.getDigest())
          && getSize() == that.getSize()
          && getLocationIndex() == that.getLocationIndex();
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(Arrays.hashCode(getDigest()), getSize(), getLocationIndex());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(getDigest()))
          .add("size", getSize())
          .add("locationIndex", getLocationIndex())
          .add("expirationTime", fromEpochMilli(expirationTime))
          .add("proxy", proxy)
          .toString();
    }
  }

  /**
   * Metadata for an artifact that is materialized in the filesystem as a symlink to another
   * artifact, but acts as a copy of that artifact for invalidation purposes. See the documentation
   * of {@link #getResolvedPath} for when this is useful.
   *
   * <p>Other than {@link #getResolvedPath}, all methods delegate to the {@link FileArtifactValue}
   * of the artifact pointed to, which must itself have a null {@link #getResolvedPath}).
   */
  private static final class ResolvedSymlinkArtifactValue extends FileArtifactValue {
    private final FileArtifactValue delegate;
    private final PathFragment resolvedPath;

    // TODO(b/329460099): Store just the execpath once multiple source roots are no longer
    // supported. At that point it becomes possible to reliably compute the absolute path from the
    // execpath.

    private ResolvedSymlinkArtifactValue(FileArtifactValue delegate, PathFragment resolvedPath) {
      checkArgument(!(delegate instanceof Singleton), "delegate is a singleton: %s", delegate);
      checkArgument(resolvedPath.isAbsolute(), "resolved path is not absolute: %s", resolvedPath);
      checkArgument(
          delegate.getResolvedPath() == null || delegate.getResolvedPath().equals(resolvedPath),
          "delegate has a different resolved path: %s",
          delegate);
      this.delegate =
          delegate instanceof ResolvedSymlinkArtifactValue resolvedDelegate
              ? resolvedDelegate.delegate
              : delegate;
      this.resolvedPath = resolvedPath;
    }

    @Override
    public PathFragment getResolvedPath() {
      return resolvedPath;
    }

    @Override
    public FileStateType getType() {
      return delegate.getType();
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return delegate.getDigest();
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      return delegate.getContentsProxy();
    }

    @Override
    public void setContentsProxy(FileContentsProxy proxy) {
      delegate.setContentsProxy(proxy);
    }

    @Override
    public long getSize() {
      return delegate.getSize();
    }

    @Override
    public long getModifiedTime() {
      return delegate.getModifiedTime();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return delegate.wasModifiedSinceDigest(path);
    }

    @Override
    protected boolean couldBeModifiedByMetadata(FileArtifactValue lastKnown) {
      return delegate.couldBeModifiedByMetadata(lastKnown);
    }

    @Override
    public byte[] getValueFingerprint() {
      return delegate.getValueFingerprint();
    }

    @Override
    public boolean isInline() {
      return delegate.isInline();
    }

    @Override
    public InputStream getInputStream() {
      return delegate.getInputStream();
    }

    @Override
    public boolean isRemote() {
      return delegate.isRemote();
    }

    @Override
    public int getLocationIndex() {
      return delegate.getLocationIndex();
    }

    @Override
    @Nullable
    public Instant getExpirationTime() {
      return delegate.getExpirationTime();
    }

    @Override
    public void setExpirationTime(Instant newExpirationTime) {
      delegate.setExpirationTime(newExpirationTime);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ResolvedSymlinkArtifactValue that)) {
        return false;
      }
      return delegate.equals(that.delegate) && resolvedPath.equals(that.resolvedPath);
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(delegate, resolvedPath);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("delegate", delegate)
          .add("resolvedPath", resolvedPath)
          .toString();
    }
  }

  /**
   * Metadata for a symlink that is not to be resolved.
   *
   * <p>Unlike {@link ResolvedSymlinkArtifactValue}, only the textual contents of the symlink matter
   * for invalidation purposes.
   */
  private static final class UnresolvedSymlinkArtifactValue extends FileArtifactValue {
    private final String symlinkTarget;
    private final byte[] digest;

    private UnresolvedSymlinkArtifactValue(Path symlink) throws IOException {
      String symlinkTarget = symlink.readSymbolicLink().getPathString();

      byte[] digest =
          symlink
              .getFileSystem()
              .getDigestFunction()
              .getHashFunction()
              .hashString(symlinkTarget, ISO_8859_1)
              .asBytes();

      // We need to be able to tell the difference between a symlink and a file containing the same
      // text. So we transform the digest a bit. This works because if one wants to craft a file
      // with the same digest as a symlink, one would need to mount a preimage attack on the digest
      // function (this would be different if we tweaked the data before applying the hash function)
      digest[0] = (byte) (digest[0] ^ 0xff);

      this.symlinkTarget = symlinkTarget;
      this.digest = digest;
    }

    @Override
    public String getUnresolvedSymlinkTarget() {
      return symlinkTarget;
    }

    @Override
    public FileStateType getType() {
      return FileStateType.SYMLINK;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      throw new IllegalStateException();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof UnresolvedSymlinkArtifactValue)) {
        return false;
      }
      UnresolvedSymlinkArtifactValue that = (UnresolvedSymlinkArtifactValue) o;
      return Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      try {
        var newMetadata = FileArtifactValue.createForUnresolvedSymlink(path);
        return !Arrays.equals(digest, newMetadata.getDigest());
      } catch (IOException e) {
        return true;
      }
    }
  }

  /** Metadata for files whose contents are available in memory. */
  private static final class InlineFileArtifactValue extends FileArtifactValue {
    private final byte[] data;
    private final byte[] digest;

    private InlineFileArtifactValue(byte[] data, byte[] digest) {
      this.data = checkNotNull(data);
      this.digest = checkNotNull(digest);
    }

    @Override
    public boolean isInline() {
      return true;
    }

    @Override
    public ByteArrayInputStream getInputStream() {
      return new ByteArrayInputStream(data);
    }

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public long getSize() {
      return data.length;
    }

    @Override
    public long getModifiedTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InlineFileArtifactValue that)) {
        return false;
      }
      return Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", getSize())
          .toString();
    }
  }

  /** Metadata for an artifact obtained via a path proxy. */
  public static final class ProxyFileArtifactValue extends FileArtifactValue {
    private final FileArtifactValue delegate;
    private final Path path;

    public ProxyFileArtifactValue(FileArtifactValue delegate, Path path) {
      this.delegate = checkNotNull(delegate);
      this.path = checkNotNull(path);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ProxyFileArtifactValue that)) {
        return false;
      }
      return this.delegate.equals(that.delegate) && this.path.equals(that.path);
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(delegate, path);
    }

    public Path getTargetPath() {
      return path;
    }

    @Override
    public FileStateType getType() {
      return delegate.getType();
    }

    @Override
    public byte[] getDigest() {
      return delegate.getDigest();
    }

    @Override
    @Nullable
    public FileContentsProxy getContentsProxy() {
      return delegate.getContentsProxy();
    }

    @Override
    public void setContentsProxy(FileContentsProxy proxy) {
      delegate.setContentsProxy(proxy);
    }

    @Override
    public long getSize() {
      return delegate.getSize();
    }

    @Override
    public long getModifiedTime() {
      return delegate.getModifiedTime();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return delegate.wasModifiedSinceDigest(path);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("delegate", delegate)
          .add("path", path)
          .toString();
    }
  }

  private static final class SingletonMarkerValue extends FileArtifactValue implements Singleton {
    private static final byte[] FINGERPRINT = new byte[] {0x10};

    @Override
    public FileStateType getType() {
      return FileStateType.NONEXISTENT;
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return null;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      return 0;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      return false;
    }

    @Override
    public byte[] getValueFingerprint() {
      return FINGERPRINT;
    }

    @Override
    public String toString() {
      return "singleton marker artifact value (" + hashCode() + ")";
    }
  }

  /** {@link FileArtifactValue} subclass for artifacts with constant metadata. A singleton. */
  public static final class ConstantMetadataValue extends FileArtifactValue
      implements FileArtifactValue.Singleton {
    static final ConstantMetadataValue INSTANCE = new ConstantMetadataValue();
    // This needs to not be of length 0, so it is distinguishable from a missing digest when written
    // into a Fingerprint.
    private static final byte[] DIGEST = new byte[1];

    private ConstantMetadataValue() {}

    @Override
    public FileStateType getType() {
      return FileStateType.REGULAR_FILE;
    }

    @Override
    public byte[] getDigest() {
      return DIGEST;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getModifiedTime() {
      return -1;
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) {
      throw new UnsupportedOperationException(
          "ConstantMetadataValue doesn't support wasModifiedSinceDigest " + path);
    }
  }
}
