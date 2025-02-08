// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.CompletionContext;
import com.google.devtools.build.lib.actions.CompletionContext.PathResolverFactory;
import com.google.devtools.build.lib.analysis.AspectCompleteEvent;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsInOutputGroup;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsToBuild;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.skyframe.AspectCompletionValue.AspectCompletionKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.CompletionFunction.Completor;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindStrategy;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.function.Supplier;

/** Manages completing builds for aspects. */
final class AspectCompletor
    implements Completor<AspectValue, AspectCompletionValue, AspectCompletionKey> {

  static SkyFunction aspectCompletionFunction(
      PathResolverFactory pathResolverFactory,
      SkyframeActionExecutor skyframeActionExecutor,
      MetadataConsumerForMetrics.FilesMetricConsumer topLevelArtifactsMetric,
      ActionRewindStrategy actionRewindStrategy,
      BugReporter bugReporter,
      Supplier<Boolean> isSkymeld) {
    return new CompletionFunction<>(
        pathResolverFactory,
        new AspectCompletor(),
        skyframeActionExecutor,
        topLevelArtifactsMetric,
        actionRewindStrategy,
        bugReporter,
        isSkymeld);
  }

  @Override
  public Event getRootCauseError(
      AspectCompletionKey key, AspectValue value, LabelCause rootCause, Environment env)
      throws InterruptedException {
    AspectKey aspectKey = key.actionLookupKey();
    // Skyframe lookups here should not have large effect on the number of dependency edges as
    // they are only needed for failed top-level aspects.
    ConfiguredTargetValue baseTargetValue =
        (ConfiguredTargetValue) env.getValue(aspectKey.getBaseConfiguredTargetKey());
    checkNotNull(baseTargetValue, "Base configured target value should be ready!");

    ConfiguredTargetAndData configuredTargetAndData =
        ConfiguredTargetAndData.fromExistingConfiguredTargetInSkyframe(
            baseTargetValue.getConfiguredTarget(), env);

    return Event.error(
        configuredTargetAndData.getLocation(),
        String.format(
            "%s, aspect %s: %s",
            aspectKey.getLabel(), aspectKey.getAspectClass().getName(), rootCause.getMessage()));
  }

  @Override
  public String getLocationIdentifier(AspectCompletionKey key, AspectValue value, Environment env) {
    AspectKey aspectKey = key.actionLookupKey();
    return aspectKey.getLabel() + ", aspect " + aspectKey.getAspectClass().getName();
  }

  @Override
  public AspectCompletionValue getResult() {
    return AspectCompletionValue.INSTANCE;
  }

  @Override
  public AspectCompleteEvent createFailed(
      AspectCompletionKey skyKey,
      AspectValue value,
      NestedSet<Cause> rootCauses,
      CompletionContext ctx,
      ImmutableMap<String, ArtifactsInOutputGroup> outputs,
      Environment env) {
    return AspectCompleteEvent.createFailed(
        skyKey.actionLookupKey(), ctx, rootCauses, outputs, value.getWritesOutputToMasterLog());
  }

  @Override
  public AspectCompleteEvent createSucceeded(
      AspectCompletionKey skyKey,
      AspectValue value,
      CompletionContext completionContext,
      ArtifactsToBuild artifactsToBuild,
      Environment env) {
    return AspectCompleteEvent.createSuccessful(
        skyKey.actionLookupKey(),
        completionContext,
        artifactsToBuild.getAllArtifactsByOutputGroup(),
        value.getWritesOutputToMasterLog());
  }
}
