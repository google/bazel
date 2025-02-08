// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.EXCLUSIVE;
import static com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.EXCLUSIVE_IF_LOCAL;
import static com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.NOT_TEST;
import static com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.PARALLEL;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AspectConfiguredEvent;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.TargetConfiguredEvent;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.constraints.RuleContextConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics.EnvironmentCompatibility;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics.PlatformCompatibility;
import com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics.TargetCompatibilityCheckException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AspectCompletionValue.AspectCompletionKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.AspectAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.SomeExecutionStartedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TestAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelEntityAnalysisConcludedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelStatusEventWithType;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetAnalyzedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetPendingExecutionEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetReadyForSymlinkPlanting;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetSkippedEvent;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Drives the analysis & execution of an ActionLookupKey, which is wrapped inside a BuildDriverKey.
 */
public class BuildDriverFunction implements SkyFunction {
  private final Supplier<IncrementalArtifactConflictFinder> incrementalArtifactConflictFinder;
  private final Supplier<RuleContextConstraintSemantics> ruleContextConstraintSemantics;
  private final Supplier<RegexFilter> extraActionFilterSupplier;
  private final Supplier<TestTypeResolver> testTypeResolver;
  final AdditionalPostAnalysisDepsRequestedAndAvailable
      additionalPostAnalysisDepsRequestedAndAvailable;

  @Nullable private Supplier<Boolean> shouldCheckForConflictWithTraversal;

  // A set of BuildDriverKeys that have been checked for conflicts.
  // This gets cleared after each build.
  // We can't use SkyKeyComputeState here since it doesn't guarantee that the same state for
  // a previously requested SkyKey is retrieved. This could cause a correctness issue:
  // - we clear the conflict checking states and shut down the Executors after all the analysis
  //   work is done in the build
  // - If the SkyKeyComputeState for this BuildDriverKey was cleared, an evaluation of this key
  //   would attempt again to check for conflicts => we redo the work, or a race condition with the
  //   shutting down of the Executors could lead to a RejectedExecutionException.
  private Set<BuildDriverKey> checkedForConflicts = Sets.newConcurrentHashSet();

  // Events coming from Skyframe may contain duplicates (because of resets). It would be better to
  // de-duplicate at the source to avoid repeated work by each subscriber.
  //
  // Each top level key has at most 1 effective status event, e.g. a top level target can't be
  // analyzed twice in a build. Therefore, to keep track of the posted events, we only need to keep
  // the sent event types instead of the events themselves.
  //
  // We didn't use SkyKeyComputeState since it should only be used as a performance optimization,
  // whereas in this situation the state determines the behavior of the SkyFunction.
  private Map<BuildDriverKey, Set<TopLevelStatusEvents.Type>> keyToPostedEvents =
      Maps.newConcurrentMap();

  public BuildDriverFunction(
      Supplier<IncrementalArtifactConflictFinder> incrementalArtifactConflictFinder,
      Supplier<RuleContextConstraintSemantics> ruleContextConstraintSemantics,
      Supplier<RegexFilter> extraActionFilterSupplier,
      Supplier<TestTypeResolver> testTypeResolver,
      AdditionalPostAnalysisDepsRequestedAndAvailable
          additionalPostAnalysisDepsRequestedAndAvailable) {
    this.incrementalArtifactConflictFinder = incrementalArtifactConflictFinder;
    this.ruleContextConstraintSemantics = ruleContextConstraintSemantics;
    this.extraActionFilterSupplier = extraActionFilterSupplier;
    this.testTypeResolver = testTypeResolver;
    this.additionalPostAnalysisDepsRequestedAndAvailable =
        additionalPostAnalysisDepsRequestedAndAvailable;
  }

  private static class State implements SkyKeyComputeState {
    // It's only necessary to do this check once.
    private boolean checkedForCompatibility = false;
    private boolean checkedForPlatformCompatibility = false;

    private TestType testType;
  }

  public void setShouldCheckForConflictWithTraversal(
      Supplier<Boolean> shouldCheckForConflictWithTraversal) {
    this.shouldCheckForConflictWithTraversal = shouldCheckForConflictWithTraversal;
  }

  /**
   * From the ConfiguredTarget/Aspect keys, get the top-level artifacts. Then evaluate them together
   * with the appropriate CompletionFunctions. This is the bridge between the conceptual analysis &
   * execution phases.
   */
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    BuildDriverKey buildDriverKey = (BuildDriverKey) skyKey;
    ActionLookupKey actionLookupKey = buildDriverKey.getActionLookupKey();
    TopLevelArtifactContext topLevelArtifactContext = buildDriverKey.getTopLevelArtifactContext();
    State state = env.getState(State::new);

    // Register a dependency on the BUILD_ID. We do this to make sure BuildDriverFunction is
    // reevaluated every build.
    PrecomputedValue.BUILD_ID.get(env);

    Set<TopLevelStatusEvents.Type> postedEventsTypes =
        keyToPostedEvents.computeIfAbsent(buildDriverKey, (unused) -> new HashSet<>());
    // Why SkyValue and not ActionLookupValue? The evaluation of some ActionLookupKey can result in
    // classes that don't implement ActionLookupValue
    // (e.g. ConfiguredTargetKey -> NonRuleConfiguredTargetValue).
    SkyValue topLevelSkyValue;
    try {
      topLevelSkyValue = env.getValueOrThrow(actionLookupKey, AbstractSaneAnalysisException.class);
    } catch (AbstractSaneAnalysisException e) {
      signalAnalysisConclusionIfKeepGoing(
          env, buildDriverKey, postedEventsTypes, /* success= */ false);
      throw BuildDriverFunctionException.ofConfiguredTargetOrAspectEval(e);
    }

    if (env.valuesMissing()) {
      return null;
    }

    // At this point, the target is considered "analyzed". It's important that this event is sent
    // before the TopLevelEntityAnalysisConcludedEvent: when the last of the analysis work is
    // concluded, we need to have the complete list of analyzed targets ready in
    // BuildResultListener.
    if (topLevelSkyValue instanceof ConfiguredTargetValue) {
      announceTopLevelConfiguredTargetAnalyzed(
          env, (ConfiguredTargetValue) topLevelSkyValue, postedEventsTypes);
    } else {
      announceTopLevelAspectAnalyzed(
          env, (TopLevelAspectsValue) topLevelSkyValue, postedEventsTypes);
    }

    // We only check for action conflict once per BuildDriverKey.
    if (Preconditions.checkNotNull(shouldCheckForConflictWithTraversal).get()
        && checkedForConflicts.add(buildDriverKey)) {
      try (SilentCloseable c =
          Profiler.instance().profile("BuildDriverFunction.checkActionConflicts")) {
        ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts =
            checkActionConflicts(actionLookupKey);
        if (!actionConflicts.isEmpty()) {
          // The analysis technically succeeded, even though the target/aspect can't be executed.
          signalAnalysisConclusionIfKeepGoing(
              env, buildDriverKey, postedEventsTypes, /* success= */ true);
          throw new BuildDriverFunctionException(
              new TopLevelConflictException(
                  "Action conflict(s) detected while analyzing top-level target "
                      + actionLookupKey.getLabel(),
                  actionConflicts));
        }
      }
    }

    Preconditions.checkState(
        topLevelSkyValue instanceof ConfiguredTargetValue
            || topLevelSkyValue instanceof TopLevelAspectsValue);
    if (state.testType == null) {
      if (topLevelSkyValue instanceof ConfiguredTargetValue) {
        state.testType =
            testTypeResolver
                .get()
                .determineTestType(
                    ((ConfiguredTargetValue) topLevelSkyValue).getConfiguredTarget());
      } else {
        state.testType = NOT_TEST;
      }
    }

    if (topLevelSkyValue instanceof ConfiguredTargetValue configuredTargetValue) {
      ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
      // It's possible that this code path is triggered AFTER the analysis cache clean up and the
      // transitive packages for package root resolution is already cleared. In such a case, the
      // symlinks should have already been planted.
      NestedSet<Package> transitivePackagesForSymlinkPlanting =
          configuredTargetValue.getTransitivePackages();
      if (transitivePackagesForSymlinkPlanting != null) {
        postEventIfNecessary(
            postedEventsTypes,
            env,
            TopLevelTargetReadyForSymlinkPlanting.create(transitivePackagesForSymlinkPlanting));
      }

      BuildConfigurationValue buildConfigurationValue =
          configuredTarget.getConfigurationKey() == null
              ? null
              : (BuildConfigurationValue) env.getValue(configuredTarget.getConfigurationKey());
      if (env.valuesMissing()) {
        return null;
      }

      if (!state.checkedForCompatibility) {
        try {
          Boolean isConfiguredTargetCompatible =
              isConfiguredTargetCompatible(
                  env,
                  state,
                  configuredTarget,
                  buildConfigurationValue,
                  buildDriverKey.isExplicitlyRequested(),
                  buildDriverKey.shouldSkipIncompatibleExplicitTargets());
          if (isConfiguredTargetCompatible == null) {
            return null;
          }

          state.checkedForCompatibility = true;
          if (!isConfiguredTargetCompatible) {
            postEventIfNecessary(
                postedEventsTypes, env, TopLevelTargetSkippedEvent.create(configuredTarget));
            // We still record analyzed but skipped tests, as this information is needed for the
            // result summary.
            if (isTest(state.testType)) {
              postEventIfNecessary(
                  postedEventsTypes,
                  env,
                  TestAnalyzedEvent.create(
                      configuredTarget,
                      Preconditions.checkNotNull(buildConfigurationValue),
                      /* isSkipped= */ true));
            }
            // Only send the event now to include the compatibility check in the measurement for
            // time spent on analysis work.
            postEventIfNecessary(
                postedEventsTypes,
                env,
                TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey, /* succeeded= */ true));
            // We consider the evaluation of this BuildDriverKey successful at this point, even when
            // the target is skipped.
            removeStatesForKey(buildDriverKey);
            return new BuildDriverValue(topLevelSkyValue, /*skipped=*/ true);
          }
        } catch (TargetCompatibilityCheckException e) {
          // The analysis of the target technically succeeded, just that it was incompatible and
          // can't be executed.
          signalAnalysisConclusionIfKeepGoing(
              env, buildDriverKey, postedEventsTypes, /* success= */ true);
          throw new BuildDriverFunctionException(e);
        }
      }

      if (!additionalPostAnalysisDepsRequestedAndAvailable.request(env, actionLookupKey)) {
        return null;
      }

      postEventIfNecessary(
          postedEventsTypes,
          env,
          TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey, /* succeeded= */ true));
      postEventIfNecessary(
          postedEventsTypes,
          env,
          TopLevelTargetPendingExecutionEvent.create(configuredTarget, isTest(state.testType)));
      requestConfiguredTargetExecution(
          configuredTarget,
          buildDriverKey,
          buildConfigurationValue,
          env,
          topLevelArtifactContext,
          postedEventsTypes,
          state.testType);
    } else {
      ImmutableSet.Builder<Artifact> artifactsToBuild = ImmutableSet.builder();
      List<SkyKey> aspectCompletionKeys = new ArrayList<>();

      // Do not trigger Skyframe restarts in this loop (see comments below).
      for (Map.Entry<AspectKey, AspectValue> entry :
          ((TopLevelAspectsValue) topLevelSkyValue).getTopLevelAspectsMap().entrySet()) {
        AspectKey aspectKey = entry.getKey();
        AspectValue aspectValue = entry.getValue();
        addExtraActionsIfRequested(
            aspectValue.getProvider(ExtraActionArtifactsProvider.class),
            artifactsToBuild,
            buildDriverKey.isExtraActionTopLevelOnly());

        // It's possible that this code path is triggered AFTER the analysis cache clean up and the
        // transitive packages for package root resolution is already cleared. In such a case, the
        // symlinks should have already been planted.
        NestedSet<Package> transitivePackagesForSymlinkPlanting =
            aspectValue.getTransitivePackages();
        if (transitivePackagesForSymlinkPlanting != null) {
          // This event should be sent out exactly once per aspect in this BuildDriverKey, even with
          // resets. We achieve this by marking the event type as sent only after sending the event
          // for all aspects, but must avoid triggering Skyframe restarts while doing so.
          if (!postedEventsTypes.contains(
              TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING)) {
            env.getListener()
                .post(
                    TopLevelTargetReadyForSymlinkPlanting.create(
                        transitivePackagesForSymlinkPlanting));
          }
        }
        aspectCompletionKeys.add(AspectCompletionKey.create(aspectKey, topLevelArtifactContext));
      }
      postedEventsTypes.add(TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING);

      if (!additionalPostAnalysisDepsRequestedAndAvailable.request(env, actionLookupKey)) {
        return null;
      }

      // Send the AspectAnalyzedEvents first to make sure the BuildResultListener is up-to-date
      // before signaling that the analysis of this top level aspect has concluded.
      postEventIfNecessary(
          postedEventsTypes,
          env,
          TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey, /* succeeded= */ true));

      postEventIfNecessary(postedEventsTypes, env, SomeExecutionStartedEvent.create());
      // Request the execution of the collected aspects.
      declareDependenciesAndCheckValues(
          env, Iterables.concat(Artifact.keys(artifactsToBuild.build()), aspectCompletionKeys));
    }

    if (env.valuesMissing()) {
      return null;
    }

    // If we get to this point, the execution of this target/aspect succeeded.

    if (state.testType.equals(EXCLUSIVE) || state.testType.equals(EXCLUSIVE_IF_LOCAL)) {
      Preconditions.checkState(topLevelSkyValue instanceof ConfiguredTargetValue);
      removeStatesForKey(buildDriverKey);
      return new ExclusiveTestBuildDriverValue(
          topLevelSkyValue, ((ConfiguredTargetValue) topLevelSkyValue).getConfiguredTarget());
    }

    removeStatesForKey(buildDriverKey);
    return new BuildDriverValue(topLevelSkyValue, /*skipped=*/ false);
  }

  /**
   * Sends out a signal that no more analysis work will be done on this top level target/aspect.
   *
   * <p>Only do so in --keep_going mode. This is consistent with the legacy behavior where the
   * analysis phase isn't considered "finished" if there's an error in --nokeep_going mode.
   */
  private static void signalAnalysisConclusionIfKeepGoing(
      Environment env,
      BuildDriverKey buildDriverKey,
      Set<TopLevelStatusEvents.Type> postedEventsTypes,
      boolean success) {
    if (buildDriverKey.keepGoing()) {
      postEventIfNecessary(
          postedEventsTypes,
          env,
          TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey, success));
    }
  }

  /**
   * {@link TopLevelTargetAnalyzedEvent}s should be sent out before conflict checking to be
   * consistent with the non-skymeld code path.
   */
  private static void announceTopLevelConfiguredTargetAnalyzed(
      Environment env,
      ConfiguredTargetValue configuredTargetValue,
      Set<TopLevelStatusEvents.Type> postedEventsTypes)
      throws InterruptedException {
    ConfiguredTarget configuredTarget = configuredTargetValue.getConfiguredTarget();
    if (postedEventsTypes.add(TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_CONFIGURED)) {
      Target target;
      try {
        Label label = configuredTarget.getOriginalLabel();
        target =
            ((PackageValue) env.getValue(label.getPackageIdentifier()))
                .getPackage()
                .getTarget(label.getName());
      } catch (NoSuchTargetException e) {
        throw new IllegalStateException(
            "Target should already be verified and available for top level ConfiguredTarget: "
                + configuredTarget,
            e);
      }

      env.getListener()
          .post(
              new TargetConfiguredEvent(
                  target, getConfigurationValue(env, configuredTarget.getConfigurationKey())));
    }
    postEventIfNecessary(
        postedEventsTypes, env, TopLevelTargetAnalyzedEvent.create(configuredTarget));
  }

  /**
   * {@link AspectAnalyzedEvents} should be sent out before conflict checking to be consistent with
   * the non-skymeld code path.
   */
  private static void announceTopLevelAspectAnalyzed(
      Environment env,
      TopLevelAspectsValue topLevelAspectsValue,
      Set<TopLevelStatusEvents.Type> postedEventsTypes)
      throws InterruptedException {
    if (!postedEventsTypes.add(TopLevelStatusEvents.Type.ASPECT_ANALYZED)) {
      return;
    }
    for (Map.Entry<AspectKey, AspectValue> entry :
        topLevelAspectsValue.getTopLevelAspectsMap().entrySet()) {
      AspectKey aspectKey = entry.getKey();
      env.getListener()
          .post(
              new AspectConfiguredEvent(
                  aspectKey.getLabel(),
                  /* aspectClassName= */ aspectKey.getAspectClass().getName(),
                  aspectKey.getAspectDescriptor().getDescription(),
                  getConfigurationValue(env, aspectKey.getConfigurationKey())));
      env.getListener().post(AspectAnalyzedEvent.create(aspectKey, entry.getValue()));
    }
  }

  @Nullable
  private static BuildConfigurationValue getConfigurationValue(
      Environment env, @Nullable BuildConfigurationKey key) throws InterruptedException {
    if (key == null) {
      return null;
    }
    return (BuildConfigurationValue) env.getValue(key);
  }

  public void resetStates() {
    checkedForConflicts = Sets.newConcurrentHashSet();
    keyToPostedEvents = Maps.newConcurrentMap();
  }

  private void removeStatesForKey(BuildDriverKey key) {
    checkedForConflicts.remove(key);
    keyToPostedEvents.remove(key);
  }

  private static void postEventIfNecessary(
      Set<TopLevelStatusEvents.Type> postedEventsTypes,
      Environment env,
      TopLevelStatusEventWithType event) {
    if (postedEventsTypes.add(event.getType())) {
      env.getListener().post(event);
    }
  }

  private static boolean isTest(TestType testType) {
    return !testType.equals(NOT_TEST);
  }

  /**
   * Checks if a ConfiguredTarget is compatible with the platform/environment. See {@link
   * TopLevelConstraintSemantics}.
   *
   * @return null if a value is missing in the environment.
   */
  @Nullable
  private Boolean isConfiguredTargetCompatible(
      Environment env,
      State state,
      ConfiguredTarget configuredTarget,
      BuildConfigurationValue buildConfigurationValue,
      boolean isExplicitlyRequested,
      boolean skipIncompatibleExplicitTargets)
      throws InterruptedException, TargetCompatibilityCheckException {

    if (!state.checkedForPlatformCompatibility) {
      PlatformCompatibility platformCompatibility =
          TopLevelConstraintSemantics.compatibilityWithPlatformRestrictions(
              configuredTarget,
              env.getListener(),
              /* eagerlyThrowError= */ true,
              isExplicitlyRequested,
              skipIncompatibleExplicitTargets);
      state.checkedForPlatformCompatibility = true;
      switch (platformCompatibility) {
        case INCOMPATIBLE_EXPLICIT:
        case INCOMPATIBLE_IMPLICIT:
          return false;
        case COMPATIBLE:
          break;
      }
    }

    EnvironmentCompatibility environmentCompatibility =
        TopLevelConstraintSemantics.compatibilityWithTargetEnvironment(
            configuredTarget,
            buildConfigurationValue,
            label -> getTarget(env, label),
            env.getListener());
    if (env.valuesMissing() || environmentCompatibility == null) {
      return null;
    }
    if (environmentCompatibility.isCompatible()) {
      return true;
    }
    if (environmentCompatibility.severeMissingEnvironments() == null) {
      return false;
    }
    String badTargetsUserMessage =
        TopLevelConstraintSemantics.getErrorMessageForTarget(
            ruleContextConstraintSemantics.get(),
            configuredTarget,
            environmentCompatibility.severeMissingEnvironments());
    throw new TargetCompatibilityCheckException(
        badTargetsUserMessage,
        FailureDetail.newBuilder()
            .setMessage(badTargetsUserMessage)
            .setAnalysis(Analysis.newBuilder().setCode(Code.TARGETS_MISSING_ENVIRONMENTS))
            .build());
  }

  @Nullable
  private static Target getTarget(Environment env, Label label)
      throws InterruptedException, NoSuchTargetException {
    PackageValue packageValue = (PackageValue) env.getValue(label.getPackageIdentifier());
    if (env.valuesMissing() || packageValue == null) {
      return null;
    }
    Package pkg = packageValue.getPackage();
    return pkg.getTarget(label.getName());
  }

  private void requestConfiguredTargetExecution(
      ConfiguredTarget configuredTarget,
      BuildDriverKey buildDriverKey,
      BuildConfigurationValue buildConfigurationValue,
      Environment env,
      TopLevelArtifactContext topLevelArtifactContext,
      Set<TopLevelStatusEvents.Type> postedEventsTypes,
      TestType testType)
      throws InterruptedException {
    ImmutableSet.Builder<Artifact> artifactsToBuild = ImmutableSet.builder();
    addExtraActionsIfRequested(
        configuredTarget.getProvider(ExtraActionArtifactsProvider.class),
        artifactsToBuild,
        buildDriverKey.isExtraActionTopLevelOnly());
    ImmutableSet.Builder<SkyKey> keysToRequest =
        ImmutableSet.<SkyKey>builder().addAll(Artifact.keys(artifactsToBuild.build()));
    postEventIfNecessary(postedEventsTypes, env, SomeExecutionStartedEvent.create());
    if (testType.equals(NOT_TEST)) {
      keysToRequest.add(
          TargetCompletionValue.key(
              ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
              topLevelArtifactContext,
              /* willTest= */ false));
      declareDependenciesAndCheckValues(env, keysToRequest.build());
      return;
    }

    postEventIfNecessary(
        postedEventsTypes,
        env,
        TestAnalyzedEvent.create(
            configuredTarget,
            Preconditions.checkNotNull(buildConfigurationValue),
            /* isSkipped= */ false));

    if (testType.equals(PARALLEL)) {
      // Only run non-exclusive tests here. Exclusive tests need to be run sequentially later.
      keysToRequest.add(
          TestCompletionValue.key(
              ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
              topLevelArtifactContext,
              /* exclusiveTesting= */ false));
      declareDependenciesAndCheckValues(env, keysToRequest.build());
      return;
    }

    // Exclusive tests will be run with sequential Skyframe evaluations afterwards.
    keysToRequest.add(
        TargetCompletionValue.key(
            ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
            topLevelArtifactContext,
            /* willTest= */ true));
    declareDependenciesAndCheckValues(env, keysToRequest.build());
  }

  /**
   * Declares dependencies and checks values for requested nodes in the graph.
   *
   * <p>Calls {@link SkyFunction.Environment#getValuesAndExceptions} and iterates over the result.
   * If any node is not done, or during iteration any value has exception, {@link
   * SkyFunction.Environment#valuesMissing} will return true.
   */
  private static void declareDependenciesAndCheckValues(
      Environment env, Iterable<? extends SkyKey> skyKeys) throws InterruptedException {
    SkyframeLookupResult result = env.getValuesAndExceptions(skyKeys);
    for (SkyKey key : skyKeys) {
      if (result.get(key) == null) {
        return;
      }
    }
  }

  @VisibleForTesting
  ImmutableMap<ActionAnalysisMetadata, ActionConflictException> checkActionConflicts(
      ActionLookupKey actionLookupKey) throws InterruptedException {
    IncrementalArtifactConflictFinder localRef = incrementalArtifactConflictFinder.get();
    // a null value means that the conflict checker is shut down.
    if (localRef == null) {
      return ImmutableMap.of();
    }
    return localRef.findArtifactConflicts(actionLookupKey).conflicts();
  }

  private void addExtraActionsIfRequested(
      ExtraActionArtifactsProvider provider,
      ImmutableSet.Builder<Artifact> artifactsToBuild,
      boolean extraActionTopLevelOnly) {
    if (provider != null) {
      addArtifactsToBuilder(
          extraActionTopLevelOnly
              ? provider.getExtraActionArtifacts().toList()
              : provider.getTransitiveExtraActionArtifacts().toList(),
          artifactsToBuild,
          extraActionFilterSupplier.get());
    }
  }

  private static void addArtifactsToBuilder(
      List<? extends Artifact> artifacts,
      ImmutableSet.Builder<Artifact> builder,
      RegexFilter filter) {
    for (Artifact artifact : artifacts) {
      if (filter.isIncluded(artifact.getOwnerLabel().toString())) {
        builder.add(artifact);
      }
    }
  }

  /** A SkyFunctionException wrapper for the actual TopLevelConflictException. */
  private static final class BuildDriverFunctionException extends SkyFunctionException {
    private BuildDriverFunctionException(Exception cause, Transience transience) {
      super(cause, transience);
    }

    // The exception is transient here since it could be caused by external factors (conflict with
    // another target).
    BuildDriverFunctionException(TopLevelConflictException cause) {
      super(cause, Transience.TRANSIENT);
    }

    BuildDriverFunctionException(TargetCompatibilityCheckException cause) {
      super(cause, Transience.TRANSIENT);
    }

    static BuildDriverFunctionException ofConfiguredTargetOrAspectEval(
        AbstractSaneAnalysisException cause) {
      return new BuildDriverFunctionException(cause, Transience.PERSISTENT);
    }
  }

  /** Helper to resolve the test type. */
  public interface TestTypeResolver {

    /** Determines the appropriate test type given a ConfiguredTarget. */
    TestType determineTestType(ConfiguredTarget target);
  }

  /** Helper to request additional post analysis deps, if required. */
  @FunctionalInterface
  public interface AdditionalPostAnalysisDepsRequestedAndAvailable {
    AdditionalPostAnalysisDepsRequestedAndAvailable NO_OP = (env, key) -> true;

    /** Returns whether the deps are requested and available. */
    boolean request(Environment env, ActionLookupKey key) throws InterruptedException;
  }

  /** Contains the results of collecting ALVs. */
  @AutoValue
  public abstract static class ActionLookupValuesCollectionResult {
    abstract ImmutableCollection<SkyValue> collectedValues();

    static ActionLookupValuesCollectionResult create(
        ImmutableCollection<SkyValue> collectedValues) {
      return new AutoValue_BuildDriverFunction_ActionLookupValuesCollectionResult(collectedValues);
    }
  }
}
