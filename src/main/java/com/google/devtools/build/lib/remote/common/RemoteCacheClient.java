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

package com.google.devtools.build.lib.remote.common;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ServerCapabilities;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * An interface for a remote caching protocol.
 *
 * <p>Implementations must be thread-safe.
 */
public interface RemoteCacheClient extends MissingDigestsFinder {
  ServerCapabilities getServerCapabilities() throws IOException;

  ListenableFuture<String> getAuthority();

  /**
   * A key in the remote action cache. The type wraps around a {@link Digest} of an {@link Action}.
   * Action keys are special in that they aren't content-addressable but refer to action results.
   *
   * <p>Terminology note: "action" is used here in the remote execution protocol sense, which is
   * equivalent to a Bazel "spawn" (a Bazel "action" being a higher-level concept).
   */
  final class ActionKey {

    private final Digest digest;

    public Digest getDigest() {
      return digest;
    }

    public ActionKey(Digest digest) {
      this.digest = Preconditions.checkNotNull(digest, "digest");
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ActionKey otherKey)) {
        return false;
      }

      return digest.equals(otherKey.digest);
    }

    @Override
    public int hashCode() {
      return digest.hashCode();
    }
  }

  /**
   * Downloads an action result for the {@code actionKey}.
   *
   * @param context the context for the action.
   * @param actionKey The digest of the {@link Action} that generated the action result.
   * @param inlineOutErr A hint to the server to inline the stdout and stderr in the {@code
   *     ActionResult} message.
   * @param inlineOutputFiles A hint to the server to inline the specified output files in the
   *     {@code ActionResult} message.
   * @return A Future representing pending download of an action result. If an action result for
   *     {@code actionKey} cannot be found the result of the Future is {@code null}.
   */
  ListenableFuture<ActionResult> downloadActionResult(
      RemoteActionExecutionContext context,
      ActionKey actionKey,
      boolean inlineOutErr,
      Set<String> inlineOutputFiles);

  /**
   * Uploads an action result for the {@code actionKey}.
   *
   * @param context the context for the action.
   * @param actionKey The digest of the {@link Action} that generated the action result.
   * @param actionResult The action result to associate with the {@code actionKey}.
   * @return A Future representing pending completion of the upload.
   */
  ListenableFuture<Void> uploadActionResult(
      RemoteActionExecutionContext context, ActionKey actionKey, ActionResult actionResult);

  /**
   * Downloads a BLOB for the given {@code digest} and writes it to {@code out}.
   *
   * <p>It's the callers responsibility to close {@code out}.
   *
   * @param context the context for the action.
   * @return A Future representing pending completion of the download. If a BLOB for {@code digest}
   *     does not exist in the cache the Future fails with a {@link CacheNotFoundException}.
   */
  ListenableFuture<Void> downloadBlob(
      RemoteActionExecutionContext context, Digest digest, OutputStream out);

  /**
   * A supplier for the data comprising a BLOB.
   *
   * <p>As blobs can be large and may need to be kept in memory, consumers should call {@link #get}
   * as late as possible and close the blob as soon as they are done with it.
   */
  @FunctionalInterface
  interface Blob extends Closeable {
    /** Get an input stream for the blob's data. Can be called multiple times. */
    InputStream get() throws IOException;

    @Override
    default void close() {}
  }

  /**
   * Uploads a {@code file} BLOB to the CAS.
   *
   * @param context the context for the action.
   * @param digest The digest of the file.
   * @param file The file to upload.
   * @return A future representing pending completion of the upload.
   */
  default ListenableFuture<Void> uploadFile(
      RemoteActionExecutionContext context, Digest digest, Path file) {
    return uploadBlob(context, digest, () -> new LazyFileInputStream(file));
  }

  /**
   * Uploads a blob to the CAS.
   *
   * @param context the context for the action.
   * @param digest The digest of the blob.
   * @param blob A supplier for the blob to upload. May be called multiple times, but is closed by
   *     the implementation after the upload is complete.
   * @return A future representing pending completion of the upload.
   */
  ListenableFuture<Void> uploadBlob(RemoteActionExecutionContext context, Digest digest, Blob blob);

  /**
   * Uploads an in-memory BLOB to the CAS.
   *
   * @param context the context for the action.
   * @param digest The digest of the blob.
   * @param data The BLOB to upload.
   * @return A future representing pending completion of the upload.
   */
  default ListenableFuture<Void> uploadBlob(
      RemoteActionExecutionContext context, Digest digest, ByteString data) {
    return uploadBlob(context, digest, data::newInput);
  }

  /** Close resources associated with the remote cache. */
  void close();
}
