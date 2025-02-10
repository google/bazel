// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.EnvironmentVariable;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.SortedMap;
import javax.annotation.Nullable;

/** An {@link ActionContext} providing the ability to log executed spawns. */
public abstract class SpawnLogContext implements ActionContext {
  /**
   * Logs an executed spawn.
   *
   * <p>May be called concurrently.
   *
   * @param spawn the spawn to log
   * @param inputMetadataProvider provides metadata for the spawn inputs
   * @param inputMap the mapping from input paths to action inputs
   * @param fileSystem the filesystem containing the spawn inputs and outputs, which might be an
   *     action filesystem when building without the bytes
   * @param timeout the timeout the spawn was run under
   * @param result the spawn result
   */
  public abstract void logSpawn(
      Spawn spawn,
      InputMetadataProvider inputMetadataProvider,
      SortedMap<PathFragment, ActionInput> inputMap,
      FileSystem fileSystem,
      Duration timeout,
      SpawnResult result)
      throws IOException, InterruptedException, ExecException;

  /**
   * Logs an internal symlink action, which is not backed by a spawn.
   *
   * <p>May be called concurrently.
   *
   * @param action the action to log
   */
  public abstract void logSymlinkAction(AbstractAction action)
      throws IOException, InterruptedException;

  /** Finishes writing the log and performs any required post-processing. */
  public abstract void close() throws IOException;

  /** Whether the log should be published to the build event protocol. */
  public abstract boolean shouldPublish();

  /** Computes the environment variables. */
  protected ImmutableList<EnvironmentVariable> getEnvironmentVariables(Spawn spawn) {
    ImmutableMap<String, String> environment = spawn.getEnvironment();
    ImmutableList.Builder<EnvironmentVariable> builder =
        ImmutableList.builderWithExpectedSize(environment.size());
    for (Map.Entry<String, String> entry : ImmutableSortedMap.copyOf(environment).entrySet()) {
      builder.add(
          EnvironmentVariable.newBuilder()
              .setName(entry.getKey())
              .setValue(entry.getValue())
              .build());
    }
    return builder.build();
  }

  /** Computes the execution platform. */
  @Nullable
  protected Platform getPlatform(Spawn spawn, RemoteOptions remoteOptions)
      throws UserExecException {
    var execPlatform = PlatformUtils.getPlatformProto(spawn, remoteOptions);
    if (execPlatform == null) {
      return null;
    }
    Platform.Builder builder = Platform.newBuilder();
    for (var p : execPlatform.getPropertiesList()) {
      builder.addPropertiesBuilder().setName(p.getName()).setValue(p.getValue());
    }
    return builder.build();
  }

  /**
   * Determines whether an action input is a directory, avoiding I/O if possible.
   *
   * <p>Do not call for action outputs.
   */
  protected boolean isInputDirectory(ActionInput input, InputMetadataProvider inputMetadataProvider)
      throws IOException {
    if (input.isDirectory()) {
      return true;
    }
    if (input.isSymlink()) {
      return false;
    }
    // Virtual action inputs are always files.
    if (input instanceof VirtualActionInput) {
      return false;
    }
    // Source artifacts are always of file type, but may be directories in the filesystem.
    if (input instanceof SourceArtifact) {
      FileArtifactValue metadata = inputMetadataProvider.getInputMetadata(input);
      return metadata != null && metadata.getType().isDirectory();
    }
    return false;
  }

  /**
   * Computes the digest of an ActionInput or its path.
   *
   * <p>Will try to obtain the digest from cached metadata first, falling back to digesting the
   * contents manually.
   */
  protected Digest computeDigest(
      @Nullable ActionInput input,
      Path path,
      @Nullable InputMetadataProvider inputMetadataProvider,
      XattrProvider xattrProvider,
      DigestHashFunction digestHashFunction,
      boolean includeHashFunctionName)
      throws IOException {
    Digest.Builder builder = Digest.newBuilder();

    if (includeHashFunctionName) {
      builder.setHashFunctionName(digestHashFunction.toString());
    }

    if (input != null) {
      if (input instanceof VirtualActionInput virtualActionInput) {
        build.bazel.remote.execution.v2.Digest digest =
            DigestUtil.compute(virtualActionInput, digestHashFunction.getHashFunction());
        return builder.setHash(digest.getHash()).setSizeBytes(digest.getSizeBytes()).build();
      }

      if (inputMetadataProvider != null) {
        // Try to obtain a digest from the input metadata.
        try {
          FileArtifactValue metadata = inputMetadataProvider.getInputMetadata(input);
          if (metadata != null && metadata.getDigest() != null) {
            return builder
                .setHash(HashCode.fromBytes(metadata.getDigest()).toString())
                .setSizeBytes(metadata.getSize())
                .build();
          }
        } catch (IOException | IllegalStateException e) {
          // Pass through to local computation.
        }
      }
    }

    // Obtain a digest from the filesystem.
    FileStatus status = path.stat();
    return builder
        .setHash(
            HashCode.fromBytes(DigestUtils.getDigestWithManualFallback(path, xattrProvider, status))
                .toString())
        .setSizeBytes(status.getSize())
        .build();
  }

  protected static Protos.SpawnMetrics getSpawnMetricsProto(SpawnResult result) {
    SpawnMetrics metrics = result.getMetrics();
    Protos.SpawnMetrics.Builder builder = Protos.SpawnMetrics.newBuilder();
    if (metrics.totalTimeInMs() != 0L) {
      builder.setTotalTime(millisToProto(metrics.totalTimeInMs()));
    }
    if (metrics.parseTimeInMs() != 0L) {
      builder.setParseTime(millisToProto(metrics.parseTimeInMs()));
    }
    if (metrics.networkTimeInMs() != 0L) {
      builder.setNetworkTime(millisToProto(metrics.networkTimeInMs()));
    }
    if (metrics.fetchTimeInMs() != 0L) {
      builder.setFetchTime(millisToProto(metrics.fetchTimeInMs()));
    }
    if (metrics.queueTimeInMs() != 0L) {
      builder.setQueueTime(millisToProto(metrics.queueTimeInMs()));
    }
    if (metrics.setupTimeInMs() != 0L) {
      builder.setSetupTime(millisToProto(metrics.setupTimeInMs()));
    }
    if (metrics.uploadTimeInMs() != 0L) {
      builder.setUploadTime(millisToProto(metrics.uploadTimeInMs()));
    }
    if (metrics.executionWallTimeInMs() != 0L) {
      builder.setExecutionWallTime(millisToProto(metrics.executionWallTimeInMs()));
    }
    if (metrics.processOutputsTimeInMs() != 0L) {
      builder.setProcessOutputsTime(millisToProto(metrics.processOutputsTimeInMs()));
    }
    if (metrics.retryTimeInMs() != 0L) {
      builder.setRetryTime(millisToProto(metrics.retryTimeInMs()));
    }
    builder.setInputBytes(metrics.inputBytes());
    builder.setInputFiles(metrics.inputFiles());
    builder.setMemoryEstimateBytes(metrics.memoryEstimate());
    builder.setInputBytesLimit(metrics.inputBytesLimit());
    builder.setInputFilesLimit(metrics.inputFilesLimit());
    builder.setOutputBytesLimit(metrics.outputBytesLimit());
    builder.setOutputFilesLimit(metrics.outputFilesLimit());
    builder.setMemoryBytesLimit(metrics.memoryLimit());
    if (metrics.timeLimitInMs() != 0L) {
      builder.setTimeLimit(millisToProto(metrics.timeLimitInMs()));
    }
    if (result.getStartTime() != null) {
      builder.setStartTime(Timestamps.fromMillis(result.getStartTime().toEpochMilli()));
    }
    return builder.build();
  }

  @VisibleForTesting
  static com.google.protobuf.Duration millisToProto(int t) {
    return Durations.fromMillis(t);
  }
}
