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
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsInOutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import javax.annotation.Nullable;

/** This event is fired as soon as a top-level aspect is either built or fails. */
public final class AspectCompleteEvent
    implements SkyValue, BuildEventWithOrderConstraint, EventReportingArtifacts {
  private final AspectKey aspectKey;
  private final NestedSet<Cause> rootCauses;
  private final Collection<BuildEventId> postedAfter;
  private final CompletionContext completionContext;
  private final ImmutableMap<String, ArtifactsInOutputGroup> artifactOutputGroups;
  private final boolean printToMasterLog;

  private AspectCompleteEvent(
      AspectKey aspectKey,
      NestedSet<Cause> rootCauses,
      CompletionContext completionContext,
      ImmutableMap<String, ArtifactsInOutputGroup> artifactOutputGroups,
      boolean printToMasterLog) {
    this.aspectKey = aspectKey;
    this.rootCauses =
        (rootCauses == null) ? NestedSetBuilder.emptySet(Order.STABLE_ORDER) : rootCauses;
    ImmutableList.Builder<BuildEventId> postedAfterBuilder = ImmutableList.builder();
    for (Cause cause : this.rootCauses.toList()) {
      postedAfterBuilder.add(cause.getIdProto());
    }
    this.postedAfter = postedAfterBuilder.build();
    this.completionContext = completionContext;
    this.artifactOutputGroups = artifactOutputGroups;
    this.printToMasterLog = printToMasterLog;
  }

  /** Construct a successful target completion event. */
  public static AspectCompleteEvent createSuccessful(
      AspectKey key,
      CompletionContext completionContext,
      ImmutableMap<String, ArtifactsInOutputGroup> artifacts,
      boolean printToMasterLog) {
    return new AspectCompleteEvent(key, null, completionContext, artifacts, printToMasterLog);
  }

  /**
   * Construct a target completion event for a failed target, with the given non-empty root causes.
   */
  public static AspectCompleteEvent createFailed(
      AspectKey key,
      CompletionContext ctx,
      NestedSet<Cause> rootCauses,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      boolean printToMasterLog) {
    Preconditions.checkArgument(!rootCauses.isEmpty());
    return new AspectCompleteEvent(key, rootCauses, ctx, outputs, printToMasterLog);
  }

  /** Returns the key of the completed aspect. */
  public AspectKey getAspectKey() {
    return aspectKey;
  }

  /**
   * Determines whether the target has failed or succeeded.
   */
  public boolean failed() {
    return !rootCauses.isEmpty();
  }

  /** Get the root causes of the target. May be empty. */
  public NestedSet<Cause> getRootCauses() {
    return rootCauses;
  }

  public Label getLabel() {
    return aspectKey.getLabel();
  }

  public String getAspectName() {
    return aspectKey.getAspectDescriptor().getAspectClass().getName();
  }

  @Nullable
  public ArtifactsInOutputGroup getArtifacts(String outputGroup) {
    return artifactOutputGroups.get(outputGroup);
  }

  public CompletionContext getCompletionContext() {
    return completionContext;
  }

  public Iterable<Artifact> getLegacyFilteredImportantArtifacts() {
    if (!printToMasterLog) {
      return ImmutableList.of();
    }
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.stableOrder();
    for (ArtifactsInOutputGroup artifactsInOutputGroup : artifactOutputGroups.values()) {
      if (artifactsInOutputGroup.areImportant()) {
        builder.addTransitive(artifactsInOutputGroup.getArtifacts());
      }
    }
    // An aspect could potentially return a source artifact if it added it to its provider.
    return Iterables.filter(builder.build().toList(), (artifact) -> !artifact.isSourceArtifact());
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.aspectCompleted(
        aspectKey.getLabel(),
        configurationId(aspectKey.getConfigurationKey()),
        aspectKey.getAspectDescriptor().getDescription());
  }

  @Override
  public Collection<BuildEventId> postedAfter() {
    return postedAfter;
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public ReportedArtifacts reportedArtifacts(OutputGroupFileModes outputGroupFileModes) {
    return TargetCompleteEvent.toReportedArtifacts(
        artifactOutputGroups,
        completionContext,
        /* baselineCoverageArtifact= */ null,
        outputGroupFileModes);
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.TargetComplete.Builder builder =
        BuildEventStreamProtos.TargetComplete.newBuilder();
    builder.setSuccess(!failed());
    builder.addAllOutputGroup(
        TargetCompleteEvent.toOutputGroupProtos(
            artifactOutputGroups,
            /* baselineCoverageArtifact= */ null,
            completionContext,
            converters));
    return GenericBuildEvent.protoChaining(this).setCompleted(builder.build()).build();
  }

  @Override
  public boolean storeForReplay() {
    return true;
  }
}
