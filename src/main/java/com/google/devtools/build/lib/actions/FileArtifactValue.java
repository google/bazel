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
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
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

  // TODO(lberki): This is only used by FileArtifactValue itself. It seems possible to remove this.
  public abstract FileContentsProxy getContentsProxy();

  @Nullable
  public byte[] getValueFingerprint() {
    // TODO(janakr): return fingerprint in other cases: symlink, directory.
    return getDigest();
  }

  /**
   * Index used to resolve remote files.
   *
   * <p>0 indicates that no such information is available which can mean that it's either a local
   * file, empty, or an omitted output.
   */
  public int getLocationIndex() {
    return 0;
  }

  /** Returns {@code true} if the file only exists remotely. */
  public boolean isRemote() {
    return false;
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
  public boolean couldBeModifiedSince(FileArtifactValue lastKnown) {
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
   * Optional materialization path.
   *
   * <p>If present, this artifact is a copy of another artifact. It is still tracked as a
   * non-symlink by Bazel, but materialized in the local filesystem as a symlink to the original
   * artifact, whose contents live at this location. This is used by {@link
   * com.google.devtools.build.lib.remote.AbstractActionInputPrefetcher} to implement zero-cost
   * copies of remotely stored artifacts.
   */
  public Optional<PathFragment> getMaterializationExecPath() {
    return Optional.empty();
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

  @VisibleForTesting
  public static FileArtifactValue createForUnresolvedSymlink(Artifact artifact) throws IOException {
    checkArgument(artifact.isSymlink());
    return createForUnresolvedSymlink(artifact.getPath());
  }

  public static FileArtifactValue createForUnresolvedSymlink(Path symlink) throws IOException {
    return new UnresolvedSymlinkArtifactValue(
        symlink.readSymbolicLink().getPathString(), symlink.getFileSystem());
  }

  public static FileArtifactValue createForUnresolvedSymlinkWithKnownTarget(
      Path symlink, String symlinkTarget) {
    return new UnresolvedSymlinkArtifactValue(symlinkTarget, symlink.getFileSystem());
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
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
      return MoreObjects.toStringHelper(this).add("digest", digest).toString();
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
      return Objects.hash(Arrays.hashCode(digest), proxy, size);
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
    protected boolean couldBeModifiedByMetadata(FileArtifactValue o) {
      if (o instanceof SymlinkToSourceFileArtifactValue symlinkToSource) {
        o = symlinkToSource.sourceFileMetadata; // "Dereference" the symlink.
      }
      if (!(o instanceof RegularFileArtifactValue lastKnown)) {
        return true;
      }

      return size != lastKnown.size || !Objects.equals(proxy, lastKnown.proxy);
    }
  }

  /** Metadata for remotely stored files. */
  public static class RemoteFileArtifactValue extends FileArtifactValue {
    private final byte[] digest;
    private final long size;
    private final int locationIndex;
    @Nullable private final PathFragment materializationExecPath;

    private RemoteFileArtifactValue(
        byte[] digest,
        long size,
        int locationIndex,
        @Nullable PathFragment materializationExecPath) {
      this.digest = checkNotNull(digest);
      this.size = size;
      this.locationIndex = locationIndex;
      this.materializationExecPath = materializationExecPath;
    }

    public static RemoteFileArtifactValue create(
        byte[] digest, long size, int locationIndex, long expireAtEpochMilli) {
      return create(
          digest, size, locationIndex, expireAtEpochMilli, /* materializationExecPath= */ null);
    }

    @VisibleForTesting
    public static RemoteFileArtifactValue create(
        byte[] digest,
        long size,
        int locationIndex,
        long expireAtEpochMilli,
        @Nullable PathFragment materializationExecPath) {
      return expireAtEpochMilli < 0
          ? new RemoteFileArtifactValue(digest, size, locationIndex, materializationExecPath)
          : new RemoteFileArtifactValueWithExpiration(
              digest, size, locationIndex, materializationExecPath, expireAtEpochMilli);
    }

    /**
     * Returns a {@link RemoteFileArtifactValue} identical to the given one, except that its
     * materialization path is set to the given value unless already present.
     */
    public static RemoteFileArtifactValue createFromExistingWithMaterializationPath(
        RemoteFileArtifactValue metadata, PathFragment materializationExecPath) {
      checkNotNull(materializationExecPath);
      if (metadata.materializationExecPath != null) {
        return metadata;
      }
      return create(
          metadata.getDigest(),
          metadata.getSize(),
          metadata.getLocationIndex(),
          metadata.getExpireAtEpochMilli(),
          metadata.getMaterializationExecPath().orElse(materializationExecPath));
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
          && locationIndex == that.locationIndex
          && Objects.equals(materializationExecPath, that.materializationExecPath);
    }

    @Override
    public final int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), size, locationIndex, materializationExecPath);
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
    public final FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
    public final Optional<PathFragment> getMaterializationExecPath() {
      return Optional.ofNullable(materializationExecPath);
    }

    /**
     * Returns the time when the remote file expires in milliseconds since epoch. A negative value
     * means the remote is not known to expire.
     *
     * <p>Expiration time does not contribute to equality of remote files.
     */
    public long getExpireAtEpochMilli() {
      return -1;
    }

    /**
     * Extends the expiration time for this metadata. If it was constructed without known expiration
     * time (i.e. expireAtEpochMilli < 0), this extension does nothing.
     */
    public void extendExpireAtEpochMilli(long expireAtEpochMilli) {}

    public boolean isAlive(Instant now) {
      return true;
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
    public final String toString() {
      return MoreObjects.toStringHelper(this)
          .add("digest", bytesToString(digest))
          .add("size", size)
          .add("locationIndex", locationIndex)
          .add("materializationExecPath", materializationExecPath)
          .add("expireAtEpochMilli", getExpireAtEpochMilli())
          .toString();
    }
  }

  /** A remote artifact that expires at a particular time. */
  private static final class RemoteFileArtifactValueWithExpiration extends RemoteFileArtifactValue {
    private long expireAtEpochMilli;

    private RemoteFileArtifactValueWithExpiration(
        byte[] digest,
        long size,
        int locationIndex,
        PathFragment materializationExecPath,
        long expireAtEpochMilli) {
      super(digest, size, locationIndex, materializationExecPath);
      this.expireAtEpochMilli = expireAtEpochMilli;
    }

    @Override
    public long getExpireAtEpochMilli() {
      return expireAtEpochMilli;
    }

    @Override
    public void extendExpireAtEpochMilli(long expireAtEpochMilli) {
      checkState(expireAtEpochMilli > this.expireAtEpochMilli);
      this.expireAtEpochMilli = expireAtEpochMilli;
    }

    @Override
    public boolean isAlive(Instant now) {
      return now.toEpochMilli() < expireAtEpochMilli;
    }
  }

  /** A {@link FileArtifactValue} representing a symlink that is not to be resolved. */
  public static final class UnresolvedSymlinkArtifactValue extends FileArtifactValue {
    private final String symlinkTarget;
    private final byte[] digest;

    private UnresolvedSymlinkArtifactValue(String symlinkTarget, FileSystem fs) {
      byte[] digest =
          fs.getDigestFunction().getHashFunction().hashString(symlinkTarget, ISO_8859_1).asBytes();

      // We need to be able to tell the difference between a symlink and a file containing the same
      // text. So we transform the digest a bit. This works because if one wants to craft a file
      // with the same digest as a symlink, one would need to mount a preimage attack on the digest
      // function (this would be different if we tweaked the data before applying the hash function)
      digest[0] = (byte) (digest[0] ^ 0xff);

      this.symlinkTarget = symlinkTarget;
      this.digest = digest;
    }

    public String getSymlinkTarget() {
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
    public FileContentsProxy getContentsProxy() {
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

  /** File stored inline in metadata. */
  public static final class InlineFileArtifactValue extends FileArtifactValue {

    public static InlineFileArtifactValue create(byte[] bytes, HashFunction hashFunction) {
      return new InlineFileArtifactValue(bytes, hashFunction.hashBytes(bytes).asBytes());
    }

    private final byte[] data;
    private final byte[] digest;

    private InlineFileArtifactValue(byte[] data, byte[] digest) {
      this.data = checkNotNull(data);
      this.digest = checkNotNull(digest);
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
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
  }

  /**
   * Metadata for an output of {@link com.google.devtools.build.lib.analysis.actions.SymlinkAction}
   * that resolves to a source file.
   */
  public static final class SymlinkToSourceFileArtifactValue extends FileArtifactValue {

    /** Creates metadata for a symlink pointing to a known {@link SourceArtifact}. */
    public static SymlinkToSourceFileArtifactValue toSourceArtifact(
        SourceArtifact sourceArtifact, FileArtifactValue sourceFileMetadata) {
      return new SymlinkToSourceFileArtifactValue(
          sourceArtifact.getPath().asFragment(), sourceFileMetadata, sourceArtifact.getExecPath());
    }

    /**
     * Creates metadata for a symlink pointing to a source file that is not a known {@link
     * SourceArtifact}.
     *
     * <p>This is only expected to happen for a symlink to an FDO profile file when {@code
     * --fdo_profile} is specified as an absolute path.
     */
    public static SymlinkToSourceFileArtifactValue toUnknownSourceFile(
        PathFragment resolvedPath, FileArtifactValue sourceFileMetadata) {
      return new SymlinkToSourceFileArtifactValue(
          resolvedPath, sourceFileMetadata, /* sourceArtifactExecPath= */ null);
    }

    private final PathFragment resolvedPath;
    private final FileArtifactValue sourceFileMetadata;
    @Nullable private final PathFragment sourceArtifactExecPath;

    private SymlinkToSourceFileArtifactValue(
        PathFragment resolvedPath,
        FileArtifactValue sourceFileMetadata,
        @Nullable PathFragment sourceArtifactExecPath) {
      checkArgument(resolvedPath.isAbsolute(), "Resolved path must be absolute: %s", resolvedPath);
      this.resolvedPath = resolvedPath;
      this.sourceFileMetadata = checkNotNull(sourceFileMetadata);
      this.sourceArtifactExecPath = sourceArtifactExecPath;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SymlinkToSourceFileArtifactValue that)) {
        return false;
      }
      return resolvedPath.equals(that.resolvedPath)
          && sourceFileMetadata.equals(that.sourceFileMetadata)
          && Objects.equals(sourceArtifactExecPath, that.sourceArtifactExecPath);
    }

    @Override
    public int hashCode() {
      return HashCodes.hashObjects(resolvedPath, sourceFileMetadata, sourceArtifactExecPath);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("resolvedPath", resolvedPath)
          .add("sourceFileMetadata", sourceFileMetadata)
          .add("sourceArtifactExecPath", sourceArtifactExecPath)
          .toString();
    }

    /** Returns the absolute path to which the symlink resolves. */
    public PathFragment getResolvedPath() {
      return resolvedPath;
    }

    /**
     * If the symlink resolves to a {@link SourceArtifact}, returns that artifact's exec path.
     *
     * <p>Returns {@code null} when the symlink does not resolve to a known {@link SourceArtifact}.
     * See {@link #toUnknownSourceFile}.
     */
    @Nullable
    public PathFragment getSourceArtifactExecPath() {
      return sourceArtifactExecPath;
    }

    @Override
    public FileStateType getType() {
      return sourceFileMetadata.getType();
    }

    @Nullable
    @Override
    public byte[] getDigest() {
      return sourceFileMetadata.getDigest();
    }

    @Override
    public FileContentsProxy getContentsProxy() {
      return sourceFileMetadata.getContentsProxy();
    }

    @Override
    public long getSize() {
      return sourceFileMetadata.getSize();
    }

    @Override
    public long getModifiedTime() {
      return sourceFileMetadata.getModifiedTime();
    }

    @Override
    public boolean wasModifiedSinceDigest(Path path) throws IOException {
      return sourceFileMetadata.wasModifiedSinceDigest(path);
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
    public FileContentsProxy getContentsProxy() {
      return delegate.getContentsProxy();
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
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
    public FileContentsProxy getContentsProxy() {
      throw new UnsupportedOperationException();
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
