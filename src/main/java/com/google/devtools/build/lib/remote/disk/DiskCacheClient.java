// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.disk;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.DigestUtil.isOldStyleDigestFunction;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.DirectCopyOutputStream;
import com.google.devtools.build.lib.remote.util.DigestOutputStream;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;

/**
 * An on-disk store for the remote action cache.
 *
 * <p>Concurrent Bazel processes can safely retrieve and store entries in a shared disk cache, even
 * when they collide.
 *
 * <p>The mtime of an entry reflects the most recent time the entry was stored *or* retrieved. This
 * property may be used to garbage collect the disk cache by deleting the least recently accessed
 * entries. This may be done by Bazel itself (see {@link DiskCacheGarbageCollectorIdleTask}), by
 * another Bazel process sharing the disk cache, or by an external process. Although we could have
 * arranged for an ongoing garbage collection to block a concurrent build, we judge it to not be
 * worth the extra complexity; assuming that the collection policy is not overly aggressive, the
 * likelihood of a race condition is fairly small, and an affected build is able to automatically
 * recover by retrying.
 */
public class DiskCacheClient {

  private static final String AC_DIR = "ac";
  private static final String CAS_DIR = "cas";
  private static final String TMP_DIR = "tmp";

  private final ImmutableMap<Store, Path> storeRootMap;
  private final Path tmpRoot;

  private final ListeningExecutorService executorService;
  private final boolean verifyDownloads;
  private final DigestUtil digestUtil;

  /**
   * @param verifyDownloads whether verify the digest of downloaded content are the same as the
   *     digest used to index that file.
   */
  public DiskCacheClient(
      Path root, DigestUtil digestUtil, ExecutorService executorService, boolean verifyDownloads)
      throws IOException {
    this.digestUtil = digestUtil;
    this.executorService = MoreExecutors.listeningDecorator(executorService);
    this.verifyDownloads = verifyDownloads;

    Path fnRoot =
        isOldStyleDigestFunction(digestUtil.getDigestFunction())
            ? root
            : root.getChild(
                Ascii.toLowerCase(digestUtil.getDigestFunction().getValueDescriptor().getName()));
    this.storeRootMap =
        ImmutableMap.of(Store.AC, fnRoot.getChild(AC_DIR), Store.CAS, fnRoot.getChild(CAS_DIR));

    this.tmpRoot = root.getChild(TMP_DIR);

    fnRoot.createDirectoryAndParents();
    tmpRoot.createDirectoryAndParents();
  }

  /**
   * If the given path exists, updates its mtime and returns true. Otherwise, returns false.
   *
   * <p>This provides a cheap way to identify candidates for deletion when trimming the cache. We
   * deliberately use the mtime because the atime is more likely to be externally modified and may
   * be unavailable on some filesystems.
   *
   * <p>Prefer calling {@link #downloadBlob} instead, which will automatically update the mtime.
   * This method should only be called by the remote worker implementation.
   *
   * @throws IOException if an I/O error other than a missing file occurs.
   */
  public boolean refresh(Path path) throws IOException {
    try {
      // Use NOW_SENTINEL_TIME instead of obtaining the current time so that the operation succeeds
      // even when the file has a different owner, as might be the case for a shared cache.
      path.setLastModifiedTime(Path.NOW_SENTINEL_TIME);
    } catch (FileNotFoundException e) {
      return false;
    }
    return true;
  }

  /**
   * Moves an existing file into the cache.
   *
   * <p>The caller must ensure that the digest is correct and the file has been recently modified.
   * This method should only be called by the combined cache implementation.
   */
  public void captureFile(Path src, Digest digest, Store store) throws IOException {
    Path target = toPath(digest, store);

    if (refresh(target)) {
      src.delete();
      return;
    }

    target.getParentDirectory().createDirectoryAndParents();
    src.renameTo(target);
  }
  private ListenableFuture<Void> download(Digest digest, OutputStream out, Store store) {
    return download(digest, out, store, null);
  }
  private ListenableFuture<Void> download(Digest digest, OutputStream out, Store store, DirectCopyOutputStream directCopyOut) {
    return executorService.submit(
        () -> {
          Path path = toPath(digest, store);
          if (!refresh(path)) {
            throw new CacheNotFoundException(digest);
          }
          if (directCopyOut != null) {
            try {
              directCopyFile(path, directCopyOut.path);
              directCopyOut.setDirectCopyed(true);
            } catch (IOException e) {
              try (InputStream in = path.getInputStream()) {
                ByteStreams.copy(in, out);
              }
            }
          } else {
            try (InputStream in = path.getInputStream()) {
              ByteStreams.copy(in, out);
            }
          }
          return null;
        });
  }

  public ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
    
    @Nullable
    DirectCopyOutputStream directCopyOut = (out instanceof DirectCopyOutputStream) ? (DirectCopyOutputStream)out : null;

    @Nullable
    DigestOutputStream digestOut = verifyDownloads ? digestUtil.newDigestOutputStream(out) : null;
    return Futures.transformAsync(
        download(digest, digestOut != null ? digestOut : out, Store.CAS, directCopyOut),
        (v) -> {
          try {
            if (digestOut != null) {
              if (directCopyOut != null) {
                Utils.verifyBlobContents(digest, digestUtil.compute(directCopyOut.path));
              } else {
                Utils.verifyBlobContents(digest, digestOut.digest());
                out.flush();
              }
            }
            return immediateFuture(null);
          } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
          }
        },
        directExecutor());
  }

  private void checkDigestExists(Digest digest) throws IOException {
    if (digest.getSizeBytes() == 0) {
      return;
    }

    Path path = toPath(digest, Store.CAS);
    if (!refresh(path)) {
      throw new CacheNotFoundException(digest);
    }
  }

  private void checkOutputDirectory(Directory dir) throws IOException {
    for (var file : dir.getFilesList()) {
      checkDigestExists(file.getDigest());
    }
  }

  /**
   * Checks that all of the blobs referenced by the {@link ActionResult} exist and marks them as
   * recently used.
   *
   * @throws CacheNotFoundException if at least one of the referenced blobs is missing.
   * @throws IOException if an I/O error other than a missing file occurs.
   */
  private void checkActionResult(ActionResult actionResult) throws IOException {
    for (var outputFile : actionResult.getOutputFilesList()) {
      checkDigestExists(outputFile.getDigest());
    }

    for (var outputDirectory : actionResult.getOutputDirectoriesList()) {
      var treeDigest = outputDirectory.getTreeDigest();
      checkDigestExists(treeDigest);

      var treePath = toPath(treeDigest, Store.CAS);
      var tree =
          Tree.parseFrom(treePath.getInputStream(), ExtensionRegistryLite.getEmptyRegistry());
      checkOutputDirectory(tree.getRoot());
      for (var dir : tree.getChildrenList()) {
        checkOutputDirectory(dir);
      }
    }

    if (actionResult.hasStdoutDigest()) {
      checkDigestExists(actionResult.getStdoutDigest());
    }

    if (actionResult.hasStderrDigest()) {
      checkDigestExists(actionResult.getStderrDigest());
    }
  }

  public ListenableFuture<ActionResult> downloadActionResult(ActionKey actionKey) {
    return Futures.transformAsync(
        // Update the mtime on the action result itself before any of the blobs it references.
        // This ensures that the blobs are always newer than the action result, so that trimming the
        // cache in LRU order cannot create dangling references.
        Utils.downloadAsActionResult(actionKey, (digest, out) -> download(digest, out, Store.AC)),
        actionResult -> {
          if (actionResult == null) {
            return immediateFuture(null);
          }

          try {
            // Verify that all of the referenced blobs exist and update their mtime.
            checkActionResult(actionResult);
          } catch (CacheNotFoundException e) {
            // If at least one of the referenced blobs is missing, consider the action result to be
            // stale. At this point we might have unnecessarily updated the mtime on some of the
            // referenced blobs, but this should happen infrequently, and doing it this way avoids a
            // double pass over the blobs.
            return immediateFuture(null);
          }

          return immediateFuture(actionResult);
        },
        directExecutor());
  }

  public ListenableFuture<Void> uploadActionResult(ActionKey actionKey, ActionResult actionResult) {
    return executorService.submit(
        () -> {
          try (InputStream data = actionResult.toByteString().newInput()) {
            saveFile(actionKey.getDigest(), Store.AC, data);
          }
          return null;
        });
  }

  public void close() {}

  public ListenableFuture<Void> uploadFile(Digest digest, Path file) {
    return executorService.submit(
        () -> {
          try {
            
            directSaveFile(digest, Store.CAS, file);
          } catch (IOException e) {
            throw e;
          }
          return null;
        });
  }

  public ListenableFuture<Void> uploadBlob(Digest digest, ByteString data) {
    return executorService.submit(
        () -> {
          try (InputStream in = data.newInput()) {
            saveFile(digest, Store.CAS, in);
          }
          return null;
        });
  }

  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(Iterable<Digest> digests) {
    // Both upload and download check if the file exists before doing I/O. So we don't
    // have to do it here.
    return immediateFuture(ImmutableSet.copyOf(digests));
  }

  public Path getTempPath() {
    return tmpRoot.getChild(UUID.randomUUID().toString());
  }

  public Path toPath(Digest digest, Store store) {
    String hash = digest.getHash();
    return toPath(hash, store);
  }

  public Path toPath(String hash, Store store) {
    // Create the file in a subfolder to bypass possible folder file count limits.
    return root.getChild(store.toString()).getChild(hash.substring(0, 2)).getChild(hash);
  }

  private void directCopyFile(Path src, Path dest) throws IOException {
    java.nio.file.Path srcNIOPath = new File(src.asFragment().toString()).toPath();
    java.nio.file.Path destNIOPath = new File(dest.asFragment().toString()).toPath();
    try {
      Files.copy(srcNIOPath, destNIOPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    } catch (IOException e) {
      throw e;
    }
  }

  private void directSaveFile(Digest digest, Store store, Path inputFile) throws IOException {
    Path path = toPath(digest, store);

    if (refresh(path)) {
      return;
    }

    try {
      path.getParentDirectory().createDirectoryAndParents();
      directCopyFile(inputFile, path);
    } catch (IOException e) {
      throw e;
    }
  }

  public void saveFile(Digest digest, Store store, InputStream in) throws IOException {
    Path path = toPath(digest, store);

    if (refresh(path)) {
      return;
    }

    // Write a temporary file first, and then rename, to avoid data corruption in case of a crash.
    Path temp = getTempPath();

    try {
      try (OutputStream out = temp.getOutputStream()) {
        ByteStreams.copy(in, out);
        // Fsync temp before we rename it to avoid data loss in the case of machine
        // crashes (the OS may reorder the writes and the rename).
        if (out instanceof FileOutputStream fos) {
          fos.getFD().sync();
        }
      }
      path.getParentDirectory().createDirectoryAndParents();
      temp.renameTo(path);
    } catch (IOException e) {
      try {
        temp.delete();
      } catch (IOException deleteErr) {
        e.addSuppressed(deleteErr);
      }
      throw e;
    }
  }
}
