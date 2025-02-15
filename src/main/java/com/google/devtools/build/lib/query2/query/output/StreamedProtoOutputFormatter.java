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
package com.google.devtools.build.lib.query2.query.output;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.OutputFormatterCallback;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.protobuf.CodedOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An output formatter that outputs a protocol buffer representation of a query result and outputs
 * the proto bytes to the output print stream. By taking the bytes and calling {@code mergeFrom()}
 * on a {@code Build.QueryResult} object the full result can be reconstructed.
 */
public class StreamedProtoOutputFormatter extends ProtoOutputFormatter {

  @Override
  public String getName() {
    return "streamed_proto";
  }

  @Override
  public OutputFormatterCallback<Target> createPostFactoStreamCallback(
      final OutputStream out, final QueryOptions options, LabelPrinter labelPrinter) {
    return new OutputFormatterCallback<Target>() {
      private static final int MAX_CHUNKS_IN_QUEUE = Runtime.getRuntime().availableProcessors() * 2;
      private static final int TARGETS_PER_CHUNK = 500;

      private final LabelPrinter ourLabelPrinter = labelPrinter;

      @Override
      public void processOutput(Iterable<Target> partialResult)
          throws IOException, InterruptedException {
        ForkJoinTask<?> writeAllTargetsFuture;
        try (ForkJoinPool executor =
            new ForkJoinPool(
                Runtime.getRuntime().availableProcessors(),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                // we use asyncMode to ensure the queue is processed FIFO, which maximizes
                // throughput
                true)) {
          var targetQueue = new LinkedBlockingQueue<Future<List<byte[]>>>(MAX_CHUNKS_IN_QUEUE);
          var stillAddingTargetsToQueue = new AtomicBoolean(true);
          writeAllTargetsFuture =
              executor.submit(
                  () -> {
                    try {
                      while (stillAddingTargetsToQueue.get() || !targetQueue.isEmpty()) {
                        Future<List<byte[]>> targets = targetQueue.take();
                        for (byte[] target : targets.get()) {
                          out.write(target);
                        }
                      }
                    } catch (InterruptedException e) {
                      throw new WrappedInterruptedException(e);
                    } catch (IOException e) {
                      throw new WrappedIOException(e);
                    } catch (ExecutionException e) {
                      // TODO: figure out what might be in here and propagate
                      throw new RuntimeException(e);
                    }
                  });
          try {
            for (List<Target> targets : Iterables.partition(partialResult, TARGETS_PER_CHUNK)) {
              targetQueue.put(executor.submit(() -> writeTargetsDelimitedToByteArrays(targets)));
            }
          } finally {
            stillAddingTargetsToQueue.set(false);
          }
        }
        try {
          writeAllTargetsFuture.get();
        } catch (ExecutionException e) {
          // TODO: propagate
          throw new RuntimeException(e);
        }
      }

      private List<byte[]> writeTargetsDelimitedToByteArrays(List<Target> targets) {
        return targets.stream().map(target -> writeDelimited(toProto(target))).toList();
      }

      private Build.Target toProto(Target target) {
        try {
          return toTargetProtoBuffer(target, ourLabelPrinter);
        } catch (InterruptedException e) {
          throw new WrappedInterruptedException(e);
        }
      }
    };
  }

  private static byte[] writeDelimited(Build.Target targetProtoBuffer) {
    try {
      var serializedSize = targetProtoBuffer.getSerializedSize();
      var headerSize = CodedOutputStream.computeUInt32SizeNoTag(serializedSize);
      var output = new byte[headerSize + serializedSize];
      var codedOut = CodedOutputStream.newInstance(output, headerSize, output.length - headerSize);
      targetProtoBuffer.writeTo(codedOut);
      codedOut.flush();
      return output;
    } catch (IOException e) {
      throw new WrappedIOException(e);
    }
  }

  private static class WrappedIOException extends RuntimeException {
    private WrappedIOException(IOException cause) {
      super(cause);
    }

    @Override
    public IOException getCause() {
      return (IOException) super.getCause();
    }
  }

  private static class WrappedInterruptedException extends RuntimeException {
    private WrappedInterruptedException(InterruptedException cause) {
      super(cause);
    }

    @Override
    public InterruptedException getCause() {
      return (InterruptedException) super.getCause();
    }
  }
}
