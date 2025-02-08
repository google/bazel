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
package com.google.devtools.build.lib.skyframe;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ArtifactMetrics;
import java.time.Duration;

/**
 * Event signaling the end of the execution phase. Contains statistics about the action cache, the
 * metadata cache and about last file save times.
 */
public record ExecutionFinishedEvent(
    int outputDirtyFiles,
    ImmutableList<String> outputDirtyFileExecPathSample,
    int outputModifiedFilesDuringPreviousBuild,
    Duration sourceDiffCheckingDuration,
    int numSourceFilesCheckedBecauseOfMissingDiffs,
    Duration outputTreeDiffCheckingDuration,
    ArtifactMetrics.FilesMetric sourceArtifactsRead,
    ArtifactMetrics.FilesMetric outputArtifactsSeen,
    ArtifactMetrics.FilesMetric outputArtifactsFromActionCache,
    ArtifactMetrics.FilesMetric topLevelArtifacts) {
  public ExecutionFinishedEvent {
    requireNonNull(outputDirtyFileExecPathSample, "outputDirtyFileExecPathSample");
    requireNonNull(sourceDiffCheckingDuration, "sourceDiffCheckingDuration");
    requireNonNull(outputTreeDiffCheckingDuration, "outputTreeDiffCheckingDuration");
    requireNonNull(sourceArtifactsRead, "sourceArtifactsRead");
    requireNonNull(outputArtifactsSeen, "outputArtifactsSeen");
    requireNonNull(outputArtifactsFromActionCache, "outputArtifactsFromActionCache");
    requireNonNull(topLevelArtifacts, "topLevelArtifacts");
  }

  // AutoValue Builders require that all fields are populated, so we provide a default.
  public static ExecutionFinishedEvent.Builder builderWithDefaults() {
    ArtifactMetrics.FilesMetric emptyFilesMetric = ArtifactMetrics.FilesMetric.getDefaultInstance();
    return builder()
        .setOutputDirtyFiles(0)
        .setOutputDirtyFileExecPathSample(ImmutableList.of())
        .setOutputModifiedFilesDuringPreviousBuild(0)
        .setSourceDiffCheckingDuration(Duration.ZERO)
        .setNumSourceFilesCheckedBecauseOfMissingDiffs(0)
        .setOutputTreeDiffCheckingDuration(Duration.ZERO)
        .setSourceArtifactsRead(emptyFilesMetric)
        .setOutputArtifactsSeen(emptyFilesMetric)
        .setOutputArtifactsFromActionCache(emptyFilesMetric)
        .setTopLevelArtifacts(emptyFilesMetric);
  }

  static Builder builder() {
    return new AutoBuilder_ExecutionFinishedEvent_Builder();
  }

  @AutoBuilder
  abstract static class Builder {
    abstract Builder setOutputDirtyFiles(int outputDirtyFiles);

    abstract Builder setOutputDirtyFileExecPathSample(
        ImmutableList<String> outputDirtyFileExecPathSample);

    abstract Builder setOutputModifiedFilesDuringPreviousBuild(
        int outputModifiedFilesDuringPreviousBuild);

    abstract Builder setSourceDiffCheckingDuration(Duration sourceDiffCheckingDuration);

    abstract Builder setNumSourceFilesCheckedBecauseOfMissingDiffs(
        int numSourceFilesCheckedBecauseOfMissingDiffs);

    abstract Builder setOutputTreeDiffCheckingDuration(Duration outputTreeDiffCheckingDuration);

    public abstract Builder setSourceArtifactsRead(ArtifactMetrics.FilesMetric value);

    public abstract Builder setOutputArtifactsSeen(ArtifactMetrics.FilesMetric value);

    public abstract Builder setOutputArtifactsFromActionCache(ArtifactMetrics.FilesMetric value);

    public abstract Builder setTopLevelArtifacts(ArtifactMetrics.FilesMetric value);

    abstract ExecutionFinishedEvent build();
  }
}
