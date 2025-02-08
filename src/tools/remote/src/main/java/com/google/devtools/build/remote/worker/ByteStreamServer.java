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

package com.google.devtools.build.remote.worker;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.remote.Chunker;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import javax.annotation.Nullable;

/** A basic implementation of a {@link ByteStreamImplBase} service. */
final class ByteStreamServer extends ByteStreamImplBase {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final OnDiskBlobStoreCache cache;
  private final Path workPath;
  private final DigestUtil digestUtil;

  @Nullable
  static Digest parseDigestFromResourceName(String resourceName) {
    try {
      String[] tokens = resourceName.split("/");
      if (tokens.length < 2) {
        return null;
      }
      String hash = tokens[tokens.length - 2];
      long size = Long.parseLong(tokens[tokens.length - 1]);
      return DigestUtil.buildDigest(hash, size);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public ByteStreamServer(OnDiskBlobStoreCache cache, Path workPath, DigestUtil digestUtil) {
    this.cache = cache;
    this.workPath = workPath;
    this.digestUtil = digestUtil;
  }

  @Override
  public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
    RequestMetadata meta = TracingMetadataUtils.fromCurrentContext();
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(meta);
    Digest digest = parseDigestFromResourceName(request.getResourceName());

    if (digest == null) {
      responseObserver.onError(
          StatusUtils.invalidArgumentError(
              "resource_name",
              "Failed parsing digest from resource_name:" + request.getResourceName()));
    }

    try {
      // This still relies on the blob size to be small enough to fit in memory.
      // TODO(olaola): refactor to fix this if the need arises.
      byte[] bytes = getFromFuture(cache.downloadBlob(context, digest));
      try (Chunker c =
          Chunker.builder().setInput(bytes.length, () -> new ByteArrayInputStream(bytes)).build()) {
        while (c.hasNext()) {
          responseObserver.onNext(ReadResponse.newBuilder().setData(c.next().getData()).build());
        }
      }
      responseObserver.onCompleted();
    } catch (CacheNotFoundException e) {
      responseObserver.onError(StatusUtils.notFoundError(digest));
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Read request failed");
      responseObserver.onError(StatusUtils.internalError(e));
    }
  }

  @Override
  public StreamObserver<WriteRequest> write(final StreamObserver<WriteResponse> responseObserver) {
    RequestMetadata meta = TracingMetadataUtils.fromCurrentContext();
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(meta);

    Path temp = workPath.getRelative("upload").getRelative(UUID.randomUUID().toString());
    try {
      temp.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.createEmptyFile(temp);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Failed to create temporary file for upload");
      responseObserver.onError(StatusUtils.internalError(e));
      // We need to make sure that subsequent onNext or onCompleted calls don't make any further
      // calls on the responseObserver after the onError above, so we return a no-op observer.
      return new NoOpStreamObserver<>();
    }
    return new StreamObserver<WriteRequest>() {
      private Digest digest;
      private long offset;
      private String resourceName;
      private boolean closed;

      @Override
      public void onNext(WriteRequest request) {
        if (closed) {
          return;
        }

        if (digest == null) {
          resourceName = request.getResourceName();
          digest = parseDigestFromResourceName(resourceName);
        }

        if (digest == null) {
          responseObserver.onError(
              StatusUtils.invalidArgumentError(
                  "resource_name",
                  "Failed parsing digest from resource_name: " + request.getResourceName()));
          closed = true;
          return;
        }

        if (offset == 0) {
          boolean exists = false;
          try {
            exists = cache.refresh(digest);
          } catch (IOException e) {
            responseObserver.onError(StatusUtils.internalError(e));
            closed = true;
            return;
          }
          if (exists) {
            responseObserver.onNext(
                WriteResponse.newBuilder().setCommittedSize(digest.getSizeBytes()).build());
            responseObserver.onCompleted();
            closed = true;
            return;
          }
        }

        if (request.getWriteOffset() != offset) {
          responseObserver.onError(
              StatusUtils.invalidArgumentError(
                  "write_offset",
                  "Expected: " + offset + ", received: " + request.getWriteOffset()));
          closed = true;
          return;
        }

        if (!request.getResourceName().isEmpty()
            && !request.getResourceName().equals(resourceName)) {
          responseObserver.onError(
              StatusUtils.invalidArgumentError(
                  "resource_name",
                  "Expected: " + resourceName + ", received: " + request.getResourceName()));
          closed = true;
          return;
        }

        long size = request.getData().size();

        if (size > 0) {
          try (OutputStream out = temp.getOutputStream(true)) {
            request.getData().writeTo(out);
          } catch (IOException e) {
            responseObserver.onError(StatusUtils.internalError(e));
            closed = true;
            return;
          }
          offset += size;
        }

        boolean shouldFinishWrite = offset == digest.getSizeBytes();

        if (shouldFinishWrite != request.getFinishWrite()) {
          responseObserver.onError(
              StatusUtils.invalidArgumentError(
                  "finish_write",
                  "Expected:" + shouldFinishWrite + ", received: " + request.getFinishWrite()));
          closed = true;
          return;
        }
      }

      @Override
      public void onError(Throwable t) {
        if (Status.fromThrowable(t).getCode() != Status.Code.CANCELLED) {
          logger.atWarning().withCause(t).log("Write request failed remotely");
        }
        closed = true;
        try {
          temp.delete();
        } catch (IOException e) {
          logger.atWarning().withCause(e).log("Could not delete temp file");
        }
      }

      @Override
      public void onCompleted() {
        if (closed) {
          return;
        }

        if (digest == null || offset != digest.getSizeBytes()) {
          responseObserver.onError(
              StatusProto.toStatusRuntimeException(
                  com.google.rpc.Status.newBuilder()
                      .setCode(Status.Code.FAILED_PRECONDITION.value())
                      .setMessage("Request completed before all data was sent.")
                      .build()));
          closed = true;
          return;
        }

        try {
          Digest d = digestUtil.compute(temp);
          getFromFuture(cache.uploadFile(context, d, temp));
          try {
            temp.delete();
          } catch (IOException e) {
            logger.atWarning().withCause(e).log("Could not delete temp file");
          }

          if (!d.equals(digest)) {
            responseObserver.onError(
                StatusUtils.invalidArgumentError(
                    "resource_name",
                    "Received digest " + digest + " does not match computed digest " + d));
            closed = true;
            return;
          }

          responseObserver.onNext(WriteResponse.newBuilder().setCommittedSize(offset).build());
          responseObserver.onCompleted();
        } catch (Exception e) {
          logger.atWarning().withCause(e).log("Write request failed");
          responseObserver.onError(StatusUtils.internalError(e));
          closed = true;
        }
      }
    };
  }

  private static class NoOpStreamObserver<T> implements StreamObserver<T> {
    @Override
    public void onNext(T value) {
    }

    @Override
    public void onError(Throwable t) {
    }

    @Override
    public void onCompleted() {
    }
  }
}
