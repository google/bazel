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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationIdMessage;
import static com.google.devtools.build.lib.skyframe.ConflictCheckingMode.NONE;
import static com.google.devtools.build.lib.skyframe.ConflictCheckingMode.UPON_CONFIGURED_OBJECT_CREATION;
import static com.google.devtools.build.lib.skyframe.ConflictCheckingMode.WITH_TRAVERSAL;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.eventbus.EventBus;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.AnalysisGraphStatsEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.actions.TotalAndConfiguredTargetOnlyMetric;
import com.google.devtools.build.lib.analysis.AnalysisFailureEvent;
import com.google.devtools.build.lib.analysis.AnalysisOperationWatcher;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.AdditionalConfigurationChangeEvent;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader.StarlarkExecTransitionLoadingException;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.analysis.test.AnalysisFailurePropagationException;
import com.google.devtools.build.lib.analysis.test.CoverageArtifactsKnownEvent;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor;
import com.google.devtools.build.lib.concurrent.QuiescingExecutors;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType;
import com.google.devtools.build.lib.skyframe.SkyframeErrorProcessor.ErrorProcessingResult;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.ConfigureTargetsResult;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.FailureToRetrieveIntrospectedValueException;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.TopLevelActionConflictReport;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.GroupedDeps;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.common.options.OptionDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Skyframe-based driver of analysis.
 *
 * <p>Covers enough functionality to work as a substitute for {@code BuildView#configureTargets}.
 */
public final class SkyframeBuildView {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ConfiguredTargetFactory factory;
  private final ArtifactFactory artifactFactory;
  private final SkyframeExecutor skyframeExecutor;
  private final ActionKeyContext actionKeyContext;
  private boolean enableAnalysis = false;

  // This hack allows us to see when an action lookup node has been invalidated, and thus when the
  // set of artifact conflicts needs to be recomputed (whenever an action lookup node has been
  // invalidated or newly evaluated).
  private final ActionLookupValueProgressReceiver progressReceiver =
      new ActionLookupValueProgressReceiver();
  // Used to see if checks of graph consistency need to be done after analysis.
  private volatile boolean someActionLookupValueEvaluated = false;

  // We keep the set of invalidated action lookup nodes so that we can know if something has been
  // invalidated after graph pruning has been executed.
  private Set<ActionLookupKey> dirtiedActionLookupKeys = Sets.newConcurrentHashSet();

  private final ConfiguredRuleClassProvider ruleClassProvider;

  // Null until the build configuration is set.
  @Nullable private BuildConfigurationValue configuration;
  @Nullable private BuildOptions originalConfigurationOptions;

  /**
   * If the last build was executed with {@code Options#discard_analysis_cache} and we are not
   * running Skyframe full, we should clear the legacy data since it is out-of-sync.
   */
  private boolean skyframeAnalysisWasDiscarded;

  private ImmutableSet<ActionLookupKey> largestTopLevelKeySetCheckedForConflicts =
      ImmutableSet.of();
  private boolean foundActionConflictInLatestCheck;

  private final StarlarkTransitionCache starlarkTransitionCache = new StarlarkTransitionCache();

  public SkyframeBuildView(
      ArtifactFactory artifactFactory,
      SkyframeExecutor skyframeExecutor,
      ConfiguredRuleClassProvider ruleClassProvider,
      ActionKeyContext actionKeyContext) {
    this.actionKeyContext = actionKeyContext;
    this.factory =
        new ConfiguredTargetFactory(
            ruleClassProvider,
            () ->
                skyframeExecutor.getCheckerForConflictCheckingMode(
                    UPON_CONFIGURED_OBJECT_CREATION));
    this.artifactFactory = artifactFactory;
    this.skyframeExecutor = skyframeExecutor;
    this.ruleClassProvider = ruleClassProvider;
  }

  public void resetProgressReceiver() {
    progressReceiver.reset();
  }

  public TotalAndConfiguredTargetOnlyMetric getEvaluatedCounts() {
    return TotalAndConfiguredTargetOnlyMetric.create(
        progressReceiver.configuredObjectCount.get(), progressReceiver.configuredTargetCount.get());
  }

  ConfiguredTargetFactory getConfiguredTargetFactory() {
    return factory;
  }

  public TotalAndConfiguredTargetOnlyMetric getEvaluatedActionCounts() {
    return TotalAndConfiguredTargetOnlyMetric.create(
        progressReceiver.actionCount.get(), progressReceiver.configuredTargetActionCount.get());
  }

  public ImmutableMap<String, Integer> getEvaluatedActionCountsByMnemonic() {
    ImmutableMap.Builder<String, Integer> builder = ImmutableMap.builder();
    for (Map.Entry<String, AtomicInteger> entry :
        progressReceiver.actionCountByMnemonic.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().get());
    }
    return builder.buildOrThrow();
  }

  /**
   * Returns a description of the analysis-cache affecting changes between the current configuration
   * and the incoming one.
   *
   * @param maxDifferencesToShow the maximum number of change-affecting options to include in the
   *     returned description
   * @return a description or {@code null} if the configuration has not changed in a way that
   *     requires the analysis cache to be invalidated
   */
  @Nullable
  private String describeConfigurationDifference(
      BuildOptions oldOptions, BuildOptions newOptions, int maxDifferencesToShow) {
    OptionsDiff diff = OptionsDiff.diff(oldOptions, newOptions);

    ImmutableSet<OptionDefinition> nativeCacheInvalidatingDifferences =
        getNativeCacheInvalidatingDifferences(configuration, diff);
    if (nativeCacheInvalidatingDifferences.isEmpty()
        && diff.getChangedStarlarkOptions().isEmpty()) {
      // The configuration may have changed, but none of the changes required a cache reset. For
      // example, test trimming was turned on and a test option changed. In this case, nothing needs
      // to be done.
      return null;
    }

    if (maxDifferencesToShow == 0) {
      return "Build options have changed";
    }

    ImmutableList<String> relevantDifferences =
        Streams.concat(
                diff.getChangedStarlarkOptions().stream().map(Label::getCanonicalForm),
                nativeCacheInvalidatingDifferences.stream().map(OptionDefinition::getOptionName))
            .map(s -> "--" + s)
            // Sorting the list to ensure that (if truncated through maxDifferencesToShow) the
            // options in the message remain stable.
            .sorted()
            .collect(toImmutableList());

    if (maxDifferencesToShow > 0 && relevantDifferences.size() > maxDifferencesToShow) {
      return String.format(
          "Build options %s%s and %d more have changed",
          Joiner.on(", ").join(relevantDifferences.subList(0, maxDifferencesToShow)),
          maxDifferencesToShow == 1 ? "" : ",",
          relevantDifferences.size() - maxDifferencesToShow);
    } else if (relevantDifferences.size() == 1) {
      return String.format(
          "Build option %s has changed", Iterables.getOnlyElement(relevantDifferences));
    } else if (relevantDifferences.size() == 2) {
      return String.format(
          "Build options %s have changed", Joiner.on(" and ").join(relevantDifferences));
    } else {
      return String.format(
          "Build options %s, and %s have changed",
          Joiner.on(", ").join(relevantDifferences.subList(0, relevantDifferences.size() - 1)),
          Iterables.getLast(relevantDifferences));
    }
  }

  // TODO(schmitt): This method assumes that the only option that can cause multiple target
  //  configurations is --cpu which (with the presence of split transitions) is no longer true.
  private ImmutableSet<OptionDefinition> getNativeCacheInvalidatingDifferences(
      BuildConfigurationValue newConfig, OptionsDiff diff) {
    return diff.getFirst().keySet().stream()
        .filter(
            (definition) ->
                ruleClassProvider.shouldInvalidateCacheForOptionDiff(
                    newConfig.getOptions(),
                    definition,
                    diff.getFirst().get(definition),
                    Iterables.getOnlyElement(diff.getSecond().get(definition))))
        .collect(toImmutableSet());
  }

  /**
   * Returns whether the analysis results from previous invocations should be discarded or report an
   * error if it should be, but it's disallowed.
   *
   * <p>This should happen when the top-level configuration has changed or if the previous
   * invocation decided that this should happen. Either way, this method also emits a message
   * informing the user about this decision.
   */
  public boolean shouldDiscardAnalysisCache(
      EventHandler eventHandler,
      BuildOptions newOptions,
      int maxDifferencesToShow,
      boolean allowAnalysisCacheDiscards,
      Optional<AdditionalConfigurationChangeEvent> additionalConfigurationChangeEvent)
      throws InvalidConfigurationException {
    if (this.configuration == null) {
      return false;
    }

    if (skyframeAnalysisWasDiscarded) {
      logger.atInfo().log("Discarding analysis cache because the previous invocation told us to");
      eventHandler.handle(
          Event.warn(
              "--discard_analysis_cache was used in the previous build, "
                  + "discarding analysis cache."));
      return true;
    }

    String diff =
        describeConfigurationDifference(
            originalConfigurationOptions, newOptions, maxDifferencesToShow);

    if (diff == null && additionalConfigurationChangeEvent.isPresent()) {
      diff = additionalConfigurationChangeEvent.get().getChangeDescription();
    }

    if (diff != null) {
      if (!allowAnalysisCacheDiscards) {
        String message = String.format("%s, analysis cache would have been discarded.", diff);
        throw new InvalidConfigurationException(
            message, FailureDetails.BuildConfiguration.Code.CONFIGURATION_DISCARDED_ANALYSIS_CACHE);
      }
      eventHandler.handle(
          Event.warn(
              diff
                  + ", discarding analysis cache (this can be expensive, see"
                  + " https://bazel.build/advanced/performance/iteration-speed)."));
      logger.atInfo().log(
          "Discarding analysis cache because the build configuration changed: %s", diff);
      return true;
    }

    return false;
  }

  /** Sets the configuration. Not thread-safe. */
  @VisibleForTesting
  public void setConfiguration(
      BuildConfigurationValue configuration,
      BuildOptions originalOptions,
      boolean discardAnalysisCache) {
    if (discardAnalysisCache) {
      // Note that clearing the analysis cache is currently required for correctness. It is also
      // helpful to save memory.
      //
      // If we had more memory, fixing the correctness issue (see also b/144932999) would allow us
      // to not invalidate the cache, leading to potentially better performance on incremental
      // builds.
      this.configuration = configuration;
      this.originalConfigurationOptions = originalOptions;
      skyframeExecutor.handleAnalysisInvalidatingChange();
    } else if (this.configuration == null) {
      this.configuration = configuration;
      this.originalConfigurationOptions = originalOptions;
    }

    skyframeAnalysisWasDiscarded = false;
    skyframeExecutor.setTopLevelConfiguration(configuration);
  }

  @VisibleForTesting
  @Nullable
  public BuildConfigurationValue getBuildConfiguration() {
    return configuration;
  }

  /**
   * Drops the analysis cache. If building with Skyframe, targets in {@code topLevelTargets} may
   * remain in the cache for use during the execution phase.
   *
   * @see com.google.devtools.build.lib.analysis.AnalysisOptions#discardAnalysisCache
   */
  public void clearAnalysisCache(
      ImmutableSet<ConfiguredTarget> topLevelTargets, ImmutableSet<AspectKey> topLevelAspects) {
    // TODO(bazel-team): Consider clearing packages too to save more memory.
    skyframeAnalysisWasDiscarded = true;
    try (SilentCloseable c = Profiler.instance().profile("skyframeExecutor.clearAnalysisCache")) {
      skyframeExecutor.clearAnalysisCache(topLevelTargets, topLevelAspects);
    }
    starlarkTransitionCache.clear();
  }

  /**
   * Analyzes the specified targets using Skyframe as the driving framework.
   *
   * @return the configured targets that should be built along with a WalkableGraph of the analysis.
   */
  public SkyframeAnalysisResult configureTargets(
      ExtendedEventHandler eventHandler,
      ImmutableMap<Label, Target> labelToTargetMap,
      ImmutableList<ConfiguredTargetKey> ctKeys,
      ImmutableList<TopLevelAspectsKey> topLevelAspectsKeys,
      TopLevelArtifactContext topLevelArtifactContextForConflictPruning,
      EventBus eventBus,
      BugReporter bugReporter,
      boolean keepGoing,
      QuiescingExecutors executors,
      boolean checkForActionConflicts)
      throws InterruptedException, ViewCreationFailedException {
    enableAnalysis(true);
    ConfigureTargetsResult result;
    try (SilentCloseable c = Profiler.instance().profile("skyframeExecutor.configureTargets")) {
      result =
          skyframeExecutor.configureTargets(
              eventHandler, labelToTargetMap, ctKeys, topLevelAspectsKeys, keepGoing, executors);
    } finally {
      enableAnalysis(false);
    }

    ImmutableSet<ConfiguredTarget> cts = result.configuredTargets();
    ImmutableMap<AspectKey, ConfiguredAspect> aspects = result.aspects();
    ImmutableSet<AspectKey> aspectKeys = aspects.keySet();
    PackageRoots packageRoots = result.packageRoots();
    EvaluationResult<ActionLookupValue> evaluationResult = result.evaluationResult();

    ImmutableMap<ActionAnalysisMetadata, ActionConflictException> interTargetConflicts =
        ImmutableMap.of();
    try (SilentCloseable c =
        Profiler.instance().profile("skyframeExecutor.findArtifactConflicts")) {
      var newKeys =
          ImmutableSet.<ActionLookupKey>builderWithExpectedSize(ctKeys.size() + aspectKeys.size())
              .addAll(ctKeys)
              .addAll(aspectKeys)
              .build();
      if (shouldCheckForConflicts(checkForActionConflicts, newKeys)) {
        largestTopLevelKeySetCheckedForConflicts = newKeys;
        // This operation is somewhat expensive, so we only do it if the graph might have changed in
        // some way -- either we analyzed a new target or we invalidated an old one or are building
        // targets together that haven't been built before.
        ActionLookupValuesTraversal analysisTraversalResult =
            skyframeExecutor.collectActionLookupValuesInBuild(ctKeys, aspectKeys);
        ArtifactConflictFinder.ActionConflictsAndStats conflictsAndStats =
            ArtifactConflictFinder.findAndStoreArtifactConflicts(
                analysisTraversalResult.getActionLookupValueShards(),
                analysisTraversalResult.getActionCount(),
                actionKeyContext);
        BuildGraphMetrics buildGraphMetrics =
            analysisTraversalResult
                .getMetrics()
                .setOutputArtifactCount(conflictsAndStats.outputArtifactCount())
                .build();
        eventBus.post(new AnalysisGraphStatsEvent(buildGraphMetrics));
        interTargetConflicts = conflictsAndStats.conflicts();
        someActionLookupValueEvaluated = false;
      }
    }

    // Intra-target conflict would mean an error in evaluationResult.
    if (!evaluationResult.hasError() && interTargetConflicts.isEmpty()) {
      return new SkyframeAnalysisResult(
          /* hasLoadingError= */ false,
          /* hasAnalysisError= */ false,
          /* hasActionConflicts= */ false,
          cts,
          evaluationResult.getWalkableGraph(),
          aspects,
          result.targetsWithConfiguration(),
          packageRoots);
    }

    ErrorProcessingResult errorProcessingResult =
        SkyframeErrorProcessor.processAnalysisErrors(
            evaluationResult,
            skyframeExecutor.getCyclesReporter(),
            eventHandler,
            keepGoing,
            skyframeExecutor.tracksStateForIncrementality(),
            eventBus,
            bugReporter);

    var actionConflicts =
        ImmutableMap.<ActionAnalysisMetadata, ActionConflictException>builder()
            .putAll(interTargetConflicts)
            .putAll(errorProcessingResult.actionConflicts()) // Intra-target conflicts.
            .buildOrThrow();
    foundActionConflictInLatestCheck = !actionConflicts.isEmpty();
    ViewCreationFailedException noKeepGoingExceptionDueToConflict = null;
    // Sometimes there are action conflicts, but the actions aren't actually required to run by the
    // build. In such cases, the conflict should still be reported to the user.
    // See OutputArtifactConflictTest#unusedActionsStillConflict.
    Set<String> reportedActionConflictExceptions = Sets.newHashSet();
    for (Entry<ActionAnalysisMetadata, ActionConflictException> bad : actionConflicts.entrySet()) {
      ActionConflictException ace = bad.getValue();
      var detailedExitCode = ace.getDetailedExitCode();
      if (reportedActionConflictExceptions.add(ace.getMessage())) {
        ace.reportTo(eventHandler);
        if (keepGoing) {
          eventHandler.handle(
              Event.warn(
                  "errors encountered while analyzing target '"
                      + bad.getKey().getOwner().getLabel()
                      + "': it will not be built"));
        }
      }
      if (!keepGoing) {
        noKeepGoingExceptionDueToConflict =
            new ViewCreationFailedException(detailedExitCode.getFailureDetail(), ace);
      }
    }

    if (foundActionConflictInLatestCheck) {
      // In order to determine the set of configured targets transitively error free from action
      // conflict issues, we run a post-processing update() that uses the bad action map.
      TopLevelActionConflictReport topLevelActionConflictReport;
      enableAnalysis(true);
      try {
        topLevelActionConflictReport =
            skyframeExecutor.filterActionConflictsForConfiguredTargetsAndAspects(
                eventHandler,
                Iterables.concat(ctKeys, aspectKeys),
                actionConflicts,
                topLevelArtifactContextForConflictPruning);
      } finally {
        enableAnalysis(false);
      }
      // Report an AnalysisFailureEvent to BEP for the top-level targets with discoverable action
      // conflicts, then finally throw if evaluation is --nokeep_going.
      for (ActionLookupKey actionLookupKey : Iterables.concat(ctKeys, aspectKeys)) {
        if (!topLevelActionConflictReport.isErrorFree(actionLookupKey)) {
          Optional<ActionConflictException> e =
              topLevelActionConflictReport.getConflictException(actionLookupKey);
          if (e.isEmpty()) {
            continue;
          }
          ActionConflictException conflictException = e.get();
          AnalysisFailedCause failedCause =
              makeArtifactConflictAnalysisFailedCause(conflictException);
          boolean targetConfigured = true;
          // Attempt to promote any ConfiguredTargetKey to the one embedded in the ConfiguredTarget
          // to reflect any transitions or trimming.
          if (actionLookupKey instanceof ConfiguredTargetKey) {
            var value = ((ConfiguredTargetValue) evaluationResult.get(actionLookupKey));
            if (value == null) {
              targetConfigured = false;
            } else if (value.getConfiguredTarget() != null) {
              // It's possible that the ConfiguredTarget has been cleared.
              actionLookupKey = value.getConfiguredTarget().getLookupKey();
            }
          }
          if (!targetConfigured) {
            eventBus.post(
                AnalysisFailureEvent.whileAnalyzingTarget(
                    (ConfiguredTargetKey) actionLookupKey,
                    NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)));
          } else {
            eventBus.post(
                AnalysisFailureEvent.actionConflict(
                    actionLookupKey, NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)));
          }

          if (!keepGoing) {
            noKeepGoingExceptionDueToConflict =
                new ViewCreationFailedException(
                    failedCause.getDetailedExitCode().getFailureDetail(), conflictException);
          }
        }
      }

      // If we're here and we're --nokeep_going, then there was a conflict due to actions not
      // discoverable by TopLevelActionLookupConflictFindingFunction. This includes extra actions,
      // coverage artifacts, and artifacts produced by aspects in output groups not present in
      // --output_groups. Throw the exception produced by the ArtifactConflictFinder which cannot
      // identify root-cause top-level keys but does catch all possible conflicts.
      if (!keepGoing) {
        skyframeExecutor.resetActionConflictsStoredInSkyframe();
        throw Preconditions.checkNotNull(noKeepGoingExceptionDueToConflict);
      }

      // Filter cts and aspects to only error-free keys. Note that any analysis failure - not just
      // action conflicts - will be observed here and lead to a key's exclusion.
      cts =
          ctKeys.stream()
              .filter(topLevelActionConflictReport::isErrorFree)
              .map(
                  k ->
                      Preconditions.checkNotNull((ConfiguredTargetValue) evaluationResult.get(k), k)
                          .getConfiguredTarget())
              .collect(toImmutableSet());

      aspects =
          aspects.entrySet().stream()
              .filter(e -> topLevelActionConflictReport.isErrorFree(e.getKey()))
              .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    return new SkyframeAnalysisResult(
        errorProcessingResult.hasLoadingError(),
        evaluationResult.hasError() || foundActionConflictInLatestCheck,
        foundActionConflictInLatestCheck,
        cts,
        evaluationResult.getWalkableGraph(),
        aspects,
        result.targetsWithConfiguration(),
        packageRoots);
  }

  /**
   * Performs analysis & execution of the CTs and aspects with Skyframe.
   *
   * <p>In case of error: --nokeep_going will eventually throw a ViewCreationFailedException,
   * whereas --keep_going will return a SkyframeAnalysisAndExecutionResult which contains the
   * failure details.
   *
   * <p>TODO(b/199053098) Have a more appropriate return type.
   */
  public SkyframeAnalysisResult analyzeAndExecuteTargets(
      ExtendedEventHandler eventHandler,
      List<ConfiguredTargetKey> ctKeys,
      ImmutableList<TopLevelAspectsKey> topLevelAspectsKeys,
      @Nullable ImmutableSet<Label> testsToRun,
      ImmutableMap<Label, Target> labelTargetMap,
      TopLevelArtifactContext topLevelArtifactContext,
      ImmutableSet<Label> explicitTargetPatterns,
      EventBus eventBus,
      BugReporter bugReporter,
      ResourceManager resourceManager,
      BuildResultListener buildResultListener,
      CoverageReportActionsWrapperSupplier coverageReportActionsWrapperSupplier,
      boolean keepGoing,
      boolean skipIncompatibleExplicitTargets,
      boolean checkForActionConflicts,
      boolean extraActionTopLevelOnly,
      QuiescingExecutors executors,
      boolean shouldDiscardAnalysisCache,
      boolean shouldClearSyscallCache,
      BuildDriverKeyTestContext buildDriverKeyTestContext,
      int skymeldAnalysisOverlapPercentage)
      throws InterruptedException,
          ViewCreationFailedException,
          BuildFailedException,
          TestExecException {
    Stopwatch analysisWorkTimer = Stopwatch.createStarted();
    EvaluationResult<SkyValue> mainEvaluationResult;

    var newKeys =
        ImmutableSet.<ActionLookupKey>builderWithExpectedSize(
                ctKeys.size() + topLevelAspectsKeys.size())
            .addAll(ctKeys)
            .addAll(topLevelAspectsKeys)
            .build();

    ImmutableList<Artifact> workspaceStatusArtifacts =
        skyframeExecutor.getWorkspaceStatusArtifacts(eventHandler);

    skyframeExecutor.setTestTypeResolver(
        target ->
            determineTestTypeImpl(
                testsToRun,
                labelTargetMap,
                target.getLabel(),
                buildDriverKeyTestContext,
                eventHandler));

    ImmutableSet<BuildDriverKey> buildDriverCTKeys =
        ctKeys.stream()
            .map(
                ctKey ->
                    BuildDriverKey.ofConfiguredTarget(
                        ctKey,
                        topLevelArtifactContext,
                        /* explicitlyRequested= */ explicitTargetPatterns.contains(
                            ctKey.getLabel()),
                        skipIncompatibleExplicitTargets,
                        extraActionTopLevelOnly,
                        keepGoing))
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<BuildDriverKey> buildDriverAspectKeys =
        topLevelAspectsKeys.stream()
            .map(
                k ->
                    BuildDriverKey.ofTopLevelAspect(
                        k,
                        topLevelArtifactContext,
                        /* explicitlyRequested= */ explicitTargetPatterns.contains(k.getLabel()),
                        skipIncompatibleExplicitTargets,
                        extraActionTopLevelOnly,
                        keepGoing))
            .collect(ImmutableSet.toImmutableSet());
    List<DetailedExitCode> detailedExitCodes = new ArrayList<>();
    MultiThreadPoolsQuiescingExecutor executor =
        (MultiThreadPoolsQuiescingExecutor) executors.getMergedAnalysisAndExecutionExecutor();
    Set<SkyKey> topLevelKeys =
        Sets.newConcurrentHashSet(Sets.union(buildDriverCTKeys, buildDriverAspectKeys));

    ConflictCheckingMode conflictCheckingMode =
        shouldCheckForConflicts(checkForActionConflicts, newKeys)
            ? skyframeExecutor.tracksStateForIncrementality()
                ? WITH_TRAVERSAL
                : UPON_CONFIGURED_OBJECT_CREATION
            : NONE;
    skyframeExecutor.setConflictCheckingModeInThisBuild(conflictCheckingMode);

    try (AnalysisOperationWatcher autoCloseableWatcher =
        AnalysisOperationWatcher.createAndRegisterWithEventBus(
            topLevelKeys,
            eventBus,
            /* lowerThresholdToSignalForExecution= */ (float)
                (topLevelKeys.size() * skymeldAnalysisOverlapPercentage / 100.0),
            /* finisher= */ () ->
                analysisFinishedCallback(
                    eventBus,
                    buildResultListener,
                    skyframeExecutor,
                    ctKeys,
                    /* shouldDiscardAnalysisCache= */ shouldDiscardAnalysisCache,
                    /* shouldClearSyscallCache= */ shouldClearSyscallCache,
                    /* measuredAnalysisTime= */ analysisWorkTimer.stop().elapsed().toMillis(),
                    /* conflictCheckingMode= */ conflictCheckingMode),
            /* executionGoAheadCallback= */ executor::launchQueuedUpExecutionPhaseTasks)) {

      try {
        skyframeExecutor.getIsBuildingExclusiveArtifacts().set(false);
        resourceManager.resetResourceUsage();
        EvaluationResult<SkyValue> additionalArtifactsResult;
        try (SilentCloseable c =
            Profiler.instance().profile("skyframeExecutor.evaluateBuildDriverKeys")) {
          // Will be disabled later by the AnalysisOperationWatcher upon conclusion of analysis.
          enableAnalysis(true);
          mainEvaluationResult =
              skyframeExecutor.evaluateBuildDriverKeys(
                  eventHandler,
                  buildDriverCTKeys,
                  buildDriverAspectKeys,
                  workspaceStatusArtifacts,
                  keepGoing,
                  executors.executionParallelism(),
                  executor);
        } finally {
          if (shouldClearSyscallCache) {
            skyframeExecutor.clearSyscallCache();
          }
          // Required for incremental correctness.
          // We unconditionally reset the states here instead of in #analysisFinishedCallback since
          // in case of --nokeep_going & analysis error, the analysis phase is never finished.
          skyframeExecutor.clearIncrementalArtifactConflictFindingStates();
          skyframeExecutor.resetBuildDriverFunction();
          skyframeExecutor.setTestTypeResolver(null);

          // These attributes affect whether conflict checking will be done during the next build.
          if (shouldCheckForConflicts(checkForActionConflicts, newKeys)) {
            largestTopLevelKeySetCheckedForConflicts = newKeys;
          }
          someActionLookupValueEvaluated = false;
        }

        // The exclusive tests whose analysis succeeded i.e. those that can be run.
        ImmutableSet<ConfiguredTarget> exclusiveTestsToRun =
            getExclusiveTests(mainEvaluationResult);
        boolean continueWithExclusiveTests = !mainEvaluationResult.hasError() || keepGoing;
        boolean hasExclusiveTestsError = false;

        if (continueWithExclusiveTests && !exclusiveTestsToRun.isEmpty()) {
          skyframeExecutor.getIsBuildingExclusiveArtifacts().set(true);
          // Run exclusive tests sequentially.
          Iterable<SkyKey> testCompletionKeys =
              TestCompletionValue.keys(
                  exclusiveTestsToRun, topLevelArtifactContext, /* exclusiveTesting= */ true);
          for (SkyKey testCompletionKey : testCompletionKeys) {
            EvaluationResult<SkyValue> testRunResult =
                skyframeExecutor.runExclusiveTestSkymeld(
                    eventHandler,
                    resourceManager,
                    testCompletionKey,
                    keepGoing,
                    executors.executionParallelism());
            if (testRunResult.hasError()) {
              hasExclusiveTestsError = true;
              detailedExitCodes.add(
                  SkyframeErrorProcessor.processErrors(
                          testRunResult,
                          skyframeExecutor.getCyclesReporter(),
                          eventHandler,
                          keepGoing,
                          skyframeExecutor.tracksStateForIncrementality(),
                          eventBus,
                          bugReporter,
                          /* includeExecutionPhase= */ true)
                      .executionDetailedExitCode());
            }
          }
        }

        // Coverage report generation should only be requested after all tests have executed.
        // When --nokeep_going and there's an earlier error, we should skip this and fail fast.
        if ((!mainEvaluationResult.hasError() && !hasExclusiveTestsError) || keepGoing) {
          ImmutableSet<Artifact> coverageReportArtifacts =
              coverageReportActionsWrapperSupplier.getCoverageReportArtifacts(
                  buildResultListener.getAnalyzedTargets(), buildResultListener.getAnalyzedTests());
          eventBus.post(CoverageArtifactsKnownEvent.create(coverageReportArtifacts));
          additionalArtifactsResult =
              skyframeExecutor.evaluateSkyKeys(
                  eventHandler, Artifact.keys(coverageReportArtifacts), keepGoing);
          if (additionalArtifactsResult.hasError()) {
            detailedExitCodes.add(
                SkyframeErrorProcessor.processErrors(
                        additionalArtifactsResult,
                        skyframeExecutor.getCyclesReporter(),
                        eventHandler,
                        keepGoing,
                        skyframeExecutor.tracksStateForIncrementality(),
                        eventBus,
                        bugReporter,
                        /* includeExecutionPhase= */ true)
                    .executionDetailedExitCode());
          }
        }
      } finally {
        // No more action execution beyond this point.
        skyframeExecutor.clearExecutionStatesSkymeld(eventHandler);
        // Also releases thread locks.
        resourceManager.resetResourceUsage();
      }

      if (!mainEvaluationResult.hasError() && detailedExitCodes.isEmpty()) {
        ImmutableMap<AspectKey, ConfiguredAspect> successfulAspects =
            getSuccessfulAspectMap(
                topLevelAspectsKeys.size(),
                mainEvaluationResult,
                buildDriverAspectKeys,
                /* topLevelActionConflictReport= */ null);
        var targetsWithConfiguration =
            ImmutableList.<TargetAndConfiguration>builderWithExpectedSize(ctKeys.size());
        ImmutableSet<ConfiguredTarget> successfulConfiguredTargets =
            getSuccessfulConfiguredTargets(
                ctKeys.size(),
                mainEvaluationResult,
                buildDriverCTKeys,
                labelTargetMap,
                targetsWithConfiguration,
                /* topLevelActionConflictReport= */ null);

        return SkyframeAnalysisAndExecutionResult.success(
            successfulConfiguredTargets,
            mainEvaluationResult.getWalkableGraph(),
            successfulAspects,
            targetsWithConfiguration.build(),
            /* packageRoots= */ null);
      }

      ErrorProcessingResult errorProcessingResult =
          SkyframeErrorProcessor.processErrors(
              mainEvaluationResult,
              skyframeExecutor.getCyclesReporter(),
              eventHandler,
              keepGoing,
              skyframeExecutor.tracksStateForIncrementality(),
              eventBus,
              bugReporter,
              /* includeExecutionPhase= */ true);
      detailedExitCodes.add(errorProcessingResult.executionDetailedExitCode());

      foundActionConflictInLatestCheck = !errorProcessingResult.actionConflicts().isEmpty();
      TopLevelActionConflictReport topLevelActionConflictReport =
          foundActionConflictInLatestCheck
              ? handleActionConflicts(
                  eventHandler,
                  mainEvaluationResult.getWalkableGraph(),
                  ctKeys,
                  topLevelAspectsKeys,
                  topLevelArtifactContext,
                  eventBus,
                  keepGoing,
                  errorProcessingResult)
              : null;
      ImmutableMap<AspectKey, ConfiguredAspect> successfulAspects =
          getSuccessfulAspectMap(
              topLevelAspectsKeys.size(),
              mainEvaluationResult,
              buildDriverAspectKeys,
              topLevelActionConflictReport);
      var targetsWithConfiguration =
          ImmutableList.<TargetAndConfiguration>builderWithExpectedSize(ctKeys.size());
      ImmutableSet<ConfiguredTarget> successfulConfiguredTargets =
          getSuccessfulConfiguredTargets(
              ctKeys.size(),
              mainEvaluationResult,
              buildDriverCTKeys,
              labelTargetMap,
              targetsWithConfiguration,
              topLevelActionConflictReport);

      return SkyframeAnalysisAndExecutionResult.withErrors(
          /* hasLoadingError= */ errorProcessingResult.hasLoadingError(),
          // legacy behavior: action conflicts are considered analysis errors.
          /* hasAnalysisError= */ errorProcessingResult.hasAnalysisError()
              || foundActionConflictInLatestCheck,
          /* hasActionConflicts= */ foundActionConflictInLatestCheck,
          successfulConfiguredTargets,
          mainEvaluationResult.getWalkableGraph(),
          successfulAspects,
          targetsWithConfiguration.build(),
          /* packageRoots= */ null,
          Collections.max(detailedExitCodes, DetailedExitCodeComparator.INSTANCE));
    }
  }

  /** Handles the required steps after all analysis work in this build is done. */
  private void analysisFinishedCallback(
      EventBus eventBus,
      BuildResultListener buildResultListener,
      SkyframeExecutor skyframeExecutor,
      List<ConfiguredTargetKey> configuredTargetKeys,
      boolean shouldDiscardAnalysisCache,
      boolean shouldClearSyscallCache,
      long measuredAnalysisTime,
      ConflictCheckingMode conflictCheckingMode)
      throws InterruptedException {
    if (conflictCheckingMode != NONE) {
      // Now that we have the full picture, it's time to collect the metrics of the whole graph.
      BuildGraphMetrics.Builder buildGraphMetricsBuilder =
          skyframeExecutor
              .collectActionLookupValuesInBuild(
                  configuredTargetKeys, buildResultListener.getAnalyzedAspects().keySet())
              .getMetrics();
      IncrementalArtifactConflictFinder incrementalArtifactConflictFinder =
          skyframeExecutor.getCheckerForConflictCheckingMode(conflictCheckingMode);
      if (incrementalArtifactConflictFinder != null) {
        buildGraphMetricsBuilder.setOutputArtifactCount(
            incrementalArtifactConflictFinder.getOutputArtifactCount());
      }
      eventBus.post(new AnalysisGraphStatsEvent(buildGraphMetricsBuilder.build()));
    }

    if (shouldDiscardAnalysisCache) {
      clearAnalysisCache(
          buildResultListener.getAnalyzedTargets(),
          buildResultListener.getAnalyzedAspects().keySet());
    }

    // At this point, it's safe to clear objects related to action conflict checking.
    // Clearing the states here is a performance optimization (reduce peak heap size) and isn't
    // required for correctness.
    skyframeExecutor.clearIncrementalArtifactConflictFindingStates();

    // Clearing the syscall cache here to free up some heap space.
    // TODO(b/273225564) Would this incur more CPU cost for the execution phase cache misses?
    if (shouldClearSyscallCache) {
      skyframeExecutor.clearSyscallCache();
    }

    enableAnalysis(false);

    eventBus.post(
        new AnalysisPhaseCompleteEvent(
            buildResultListener.getAnalyzedTargets(),
            getEvaluatedCounts(),
            getEvaluatedActionCounts(),
            getEvaluatedActionCountsByMnemonic(),
            measuredAnalysisTime,
            skyframeExecutor.getPackageManager().getAndClearStatistics(),
            skyframeExecutor.wasAnalysisCacheInvalidatedAndResetBit()));
  }

  /**
   * Report the appropriate conflicts and return a TopLevelActionConflictReport.
   *
   * <p>The TopLevelActionConflictReport is used to determine the set of top level targets that
   * depend on conflicted actions.
   */
  private TopLevelActionConflictReport handleActionConflicts(
      ExtendedEventHandler eventHandler,
      WalkableGraph graph,
      List<ConfiguredTargetKey> ctKeys,
      ImmutableList<TopLevelAspectsKey> topLevelAspectsKeys,
      TopLevelArtifactContext topLevelArtifactContextForConflictPruning,
      EventBus eventBus,
      boolean keepGoing,
      ErrorProcessingResult errorProcessingResult)
      throws InterruptedException, ViewCreationFailedException {
    // TODO(b/332898055) Unify with the noskymeld code path.
    try {
      // Here we already have the <TopLevelAspectKey, error> mapping, but what we need to fit into
      // the existing AnalysisFailureEvent is <AspectKey, error>. An extra Skyframe evaluation is
      // required.
      // If the conflict is intra-Aspect, the TopLevelAspectValue would be null and the AspectKey
      // isn't retrievable. It must be supplied via the ErrorProcessingResult.
      Iterable<ActionLookupKey> effectiveTopLevelKeysForConflictReporting =
          ImmutableSet.<ActionLookupKey>builder()
              .addAll(ctKeys)
              .addAll(getDerivedAspectKeysForConflictReporting(topLevelAspectsKeys))
              .addAll(errorProcessingResult.aspectKeysForConflictReporting())
              .build();
      TopLevelActionConflictReport topLevelActionConflictReport;
      enableAnalysis(true);
      // In order to determine the set of configured targets transitively error free from action
      // conflict issues, we run a post-processing update() that uses the bad action map.
      try {
        topLevelActionConflictReport =
            skyframeExecutor.filterActionConflictsForConfiguredTargetsAndAspects(
                eventHandler,
                effectiveTopLevelKeysForConflictReporting,
                errorProcessingResult.actionConflicts(),
                topLevelArtifactContextForConflictPruning);
      } finally {
        enableAnalysis(false);
      }
      reportActionConflictErrors(
          topLevelActionConflictReport,
          graph,
          effectiveTopLevelKeysForConflictReporting,
          errorProcessingResult.actionConflicts(),
          eventHandler,
          eventBus,
          keepGoing);
      return topLevelActionConflictReport;
    } finally {
      skyframeExecutor.resetActionConflictsStoredInSkyframe();
    }
  }

  /**
   * From the {@code topLevelActionConflictReport}, report the action conflict errors.
   *
   * <p>Throw a ViewCreationFailedException in case of --nokeep_going.
   */
  private static void reportActionConflictErrors(
      TopLevelActionConflictReport topLevelActionConflictReport,
      WalkableGraph graph,
      Iterable<ActionLookupKey> effectiveTopLevelKeysForConflictReporting,
      ImmutableMap<ActionAnalysisMetadata, ActionConflictException> actionConflicts,
      ExtendedEventHandler eventHandler,
      EventBus eventBus,
      boolean keepGoing)
      throws ViewCreationFailedException, InterruptedException {
    // ArtifactPrefixConflictExceptions come in pairs, and only one should be reported.
    Set<String> reportedActionConflictExceptions = Sets.newHashSet();

    // Sometimes a conflicting action can't be traced to a top level target via
    // TopLevelActionConflictReport. We therefore need to print the errors from the conflicts
    // themselves. See SkyframeIntegrationTest#topLevelAspectsAndExtraActionsWithConflict.
    for (ActionConflictException e : actionConflicts.values()) {
      if (reportedActionConflictExceptions.add(e.getMessage())) {
        e.reportTo(eventHandler);
        if (keepGoing) {
          eventHandler.handle(
              Event.warn(
                  String.format(
                      "errors encountered while analyzing target '"
                          + e.getArtifact().getOwnerLabel()
                          + "': it will not be built")));
        }
      }
    }
    // Report an AnalysisFailureEvent to BEP for the top-level targets with discoverable action
    // conflicts, then finally throw.
    for (ActionLookupKey actionLookupKey : effectiveTopLevelKeysForConflictReporting) {
      if (topLevelActionConflictReport.isErrorFree(actionLookupKey)) {
        continue;
      }
      Optional<ActionConflictException> e =
          topLevelActionConflictReport.getConflictException(actionLookupKey);
      if (e.isEmpty()) {
        continue;
      }

      ActionConflictException conflictException = e.get();
      AnalysisFailedCause failedCause = makeArtifactConflictAnalysisFailedCause(conflictException);
      boolean targetConfigured = true;
      // Attempt to promote any ConfiguredTargetKey to the one embedded in the ConfiguredTarget to
      // reflect any transitions or trimming.
      if (actionLookupKey instanceof ConfiguredTargetKey) {
        // This is a graph lookup instead of an EvaluationResult lookup because Skymeld's
        // EvaluationResult does not contain ConfiguredTargetKey.
        var value = ((ConfiguredTargetValue) graph.getValue(actionLookupKey));
        if (value == null) {
          targetConfigured = false;
        } else if (value.getConfiguredTarget() != null) {
          // It's possible that the ConfiguredTarget has been cleared.
          actionLookupKey = value.getConfiguredTarget().getLookupKey();
        }
      }
      if (!targetConfigured) {
        eventBus.post(
            AnalysisFailureEvent.whileAnalyzingTarget(
                (ConfiguredTargetKey) actionLookupKey,
                NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)));
      } else {
        eventBus.post(
            AnalysisFailureEvent.actionConflict(
                actionLookupKey, NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)));
      }

      if (!keepGoing) {
        throw new ViewCreationFailedException(
            failedCause.getDetailedExitCode().getFailureDetail(), conflictException);
      }
    }
  }

  private static ImmutableSet<ConfiguredTarget> getExclusiveTests(
      EvaluationResult<SkyValue> evaluationResult) {
    ImmutableSet.Builder<ConfiguredTarget> exclusiveTests = ImmutableSet.builder();
    for (SkyValue value : evaluationResult.values()) {
      if (value instanceof ExclusiveTestBuildDriverValue) {
        exclusiveTests.add(
            ((ExclusiveTestBuildDriverValue) value).getExclusiveTestConfiguredTarget());
      }
    }
    return exclusiveTests.build();
  }

  private static TestType determineTestTypeImpl(
      ImmutableSet<Label> testsToRun,
      ImmutableMap<Label, Target> labelTargetMap,
      Label label,
      BuildDriverKeyTestContext buildDriverKeyTestContext,
      ExtendedEventHandler eventHandler) {
    if (testsToRun == null || !testsToRun.contains(label)) {
      return TestType.NOT_TEST;
    }
    Target target = labelTargetMap.get(label);

    if (!(target instanceof Rule rule)) {
      return TestType.NOT_TEST;
    }

    TestType fromExplicitFlagOrTag;
    if (buildDriverKeyTestContext.getTestStrategy().equals("exclusive")
        || TargetUtils.isExclusiveTestRule(rule)
        || (TargetUtils.isExclusiveIfLocalTestRule(rule) && TargetUtils.isLocalTestRule(rule))) {
      fromExplicitFlagOrTag = TestType.EXCLUSIVE;
    } else if (TargetUtils.isExclusiveIfLocalTestRule(rule)) {
      fromExplicitFlagOrTag = TestType.EXCLUSIVE_IF_LOCAL;
    } else {
      fromExplicitFlagOrTag = TestType.PARALLEL;
    }

    if ((fromExplicitFlagOrTag == TestType.EXCLUSIVE
            && buildDriverKeyTestContext.forceExclusiveTestsInParallel())
        || (fromExplicitFlagOrTag == TestType.EXCLUSIVE_IF_LOCAL
            && buildDriverKeyTestContext.forceExclusiveIfLocalTestsInParallel())) {
      eventHandler.handle(
          Event.warn(
              label
                  + " is tagged "
                  + fromExplicitFlagOrTag.getMsg()
                  + ", but --test_strategy="
                  + buildDriverKeyTestContext.getTestStrategy()
                  + " forces parallel test execution."));
      return TestType.PARALLEL;
    }
    return fromExplicitFlagOrTag;
  }

  // When we check for action conflicts that occur with a TopLevelAspectKey, a reference to the
  // lower-level AspectKeys is required: it could happen that only some AspectKeys, but not
  // all, that derived from a TopLevelAspectKey has a conflicting action.
  private ImmutableSet<AspectKey> getDerivedAspectKeysForConflictReporting(
      ImmutableList<TopLevelAspectsKey> topLevelAspectsKeys) {
    ImmutableSet.Builder<AspectKey> aspectKeysBuilder = ImmutableSet.builder();
    for (TopLevelAspectsKey topLevelAspectsKey : topLevelAspectsKeys) {
      try {
        TopLevelAspectsValue topLevelAspectsValue =
            (TopLevelAspectsValue)
                skyframeExecutor.getDoneSkyValueForIntrospection(topLevelAspectsKey);
        aspectKeysBuilder.addAll(topLevelAspectsValue.getTopLevelAspectsMap().keySet());
      } catch (FailureToRetrieveIntrospectedValueException e) {
        // It could happen that the analysis of TopLevelAspectKey wasn't complete: either its own
        // analysis failed, or another error was raise in --nokeep_going mode. In that case, it
        // couldn't be involved in the conflict exception anyway, and we just move on.
        // Unless it's an unexpected interrupt that caused the exception.
        if (e.getCause() instanceof InterruptedException) {
          BugReport.sendNonFatalBugReport(e);
        }
      }
    }
    return aspectKeysBuilder.build();
  }

  private static ImmutableSet<ConfiguredTarget> getSuccessfulConfiguredTargets(
      int expectedSize,
      EvaluationResult<SkyValue> evaluationResult,
      Set<BuildDriverKey> buildDriverCTKeys,
      ImmutableMap<Label, Target> labelToTargetMap,
      ImmutableList.Builder<TargetAndConfiguration> targetsWithConfiguration,
      @Nullable TopLevelActionConflictReport topLevelActionConflictReport)
      throws InterruptedException {
    ImmutableSet.Builder<ConfiguredTarget> cts = ImmutableSet.builderWithExpectedSize(expectedSize);
    for (BuildDriverKey bdCTKey : buildDriverCTKeys) {
      if (topLevelActionConflictReport != null
          && !topLevelActionConflictReport.isErrorFree(bdCTKey.getActionLookupKey())) {
        continue;
      }
      BuildDriverValue value = (BuildDriverValue) evaluationResult.get(bdCTKey);
      if (value == null) {
        continue;
      }
      ConfiguredTargetValue ctValue = (ConfiguredTargetValue) value.getWrappedSkyValue();
      cts.add(ctValue.getConfiguredTarget());

      BuildConfigurationKey configurationKey = ctValue.getConfiguredTarget().getConfigurationKey();
      var configuration =
          configurationKey == null
              ? null
              : (BuildConfigurationValue)
                  evaluationResult.getWalkableGraph().getValue(configurationKey);
      targetsWithConfiguration.add(
          new TargetAndConfiguration(
              labelToTargetMap.get(bdCTKey.getActionLookupKey().getLabel()), configuration));
    }
    return cts.build();
  }

  private static ImmutableMap<AspectKey, ConfiguredAspect> getSuccessfulAspectMap(
      int expectedSize,
      EvaluationResult<SkyValue> evaluationResult,
      Set<BuildDriverKey> buildDriverAspectKeys,
      @Nullable TopLevelActionConflictReport topLevelActionConflictReport) {
    // There can't be duplicate Aspects after resolving --aspects, so this is safe.
    ImmutableMap.Builder<AspectKey, ConfiguredAspect> aspects =
        ImmutableMap.builderWithExpectedSize(expectedSize);
    for (BuildDriverKey bdAspectKey : buildDriverAspectKeys) {
      if (topLevelActionConflictReport != null
          && !topLevelActionConflictReport.isErrorFree(bdAspectKey.getActionLookupKey())) {
        continue;
      }
      BuildDriverValue value = (BuildDriverValue) evaluationResult.get(bdAspectKey);
      if (value == null) {
        // Skip aspects that couldn't be applied to targets.
        continue;
      }
      TopLevelAspectsValue topLevelAspectsValue = (TopLevelAspectsValue) value.getWrappedSkyValue();
      aspects.putAll(topLevelAspectsValue.getTopLevelAspectsMap());
    }
    return aspects.buildOrThrow();
  }

  private static AnalysisFailedCause makeArtifactConflictAnalysisFailedCause(
      ActionConflictException ace) {
    DetailedExitCode detailedExitCode = ace.getDetailedExitCode();
    Label causeLabel = ace.getArtifact().getArtifactOwner().getLabel();
    BuildConfigurationKey causeConfigKey = null;
    if (ace.getArtifact().getArtifactOwner() instanceof ConfiguredTargetKey) {
      causeConfigKey =
          ((ConfiguredTargetKey) ace.getArtifact().getArtifactOwner()).getConfigurationKey();
    }
    return new AnalysisFailedCause(
        causeLabel, configurationIdMessage(causeConfigKey), detailedExitCode);
  }

  private boolean shouldCheckForConflicts(
      boolean specifiedValueInRequest, ImmutableSet<ActionLookupKey> newKeys) {
    if (!specifiedValueInRequest) {
      // A build request by default enables action conflict checking, except for some cases e.g.
      // cquery.
      return false;
    }

    if (someActionLookupValueEvaluated) {
      // A top-level target was added and may introduce a conflict, or a top-level target was
      // recomputed and may introduce or resolve a conflict.
      return true;
    }

    if (!dirtiedActionLookupKeys.isEmpty()) {
      // No target was (re)computed but at least one was dirtied.
      // Example: (//:x //foo:y) are built, and in conflict (//:x creates foo/C and //foo:y
      // creates C). Then y is removed from foo/BUILD and only //:x is built, so //foo:y is
      // dirtied but not recomputed, and no other nodes are recomputed (and none are deleted).
      // Still we must do the conflict checking because previously there was a conflict but now
      // there isn't.
      return true;
    }

    if (foundActionConflictInLatestCheck) {
      // Example sequence:
      // 1.  Build (x y z), and there is a conflict. We store (x y z) as the largest checked key
      //     set, and record the fact that there were bad actions.
      // 2.  Null-build (x z), so we don't evaluate or dirty anything, but because we know there was
      //     some conflict last time but don't know exactly which targets conflicted, it could have
      //     been (x z), so we now check again. The value of foundActionConflictInLatestCheck would
      //     then be updated for the next build, based on the result of this check.
      return true;
    }

    if (!largestTopLevelKeySetCheckedForConflicts.containsAll(newKeys)) {
      // Example sequence:
      // 1.  Build (x y z), and there is a conflict. We store (x y z) as the largest checked key
      //     set, and record the fact that there were bad actions.
      // 2.  Null-build (x z), so we don't evaluate or dirty anything, but we check again for
      //     conflict because foundActionConflictInLatestCheck is true, and store (x z) as the
      //     largest checked key set.
      // 3.  Null-build (y z), so again we don't evaluate or dirty anything, and the previous build
      //     had no conflicts, so no other condition is true. But because (y z) is not a subset of
      //     (x z) and we only keep the most recent largest checked key set, we don't know if (y z)
      //     are conflict free, so we check.
      return true;
    }

    // We believe the conditions above are correct in the sense that we always check for conflicts
    // when we have to. But they are incomplete, so we sometimes check for conflicts even if we
    // wouldn't have to. For example:
    // - if no target was evaluated nor dirtied and build sequence is (x y) [no conflict], (z),
    //   where z is in the transitive closure of (x y), then we shouldn't check.
    // - if no target was evaluated nor dirtied and build sequence is (x y) [no conflict], (w), (x),
    //   then the last build shouldn't conflict-check because (x y) was checked earlier. But it
    //   does, because after the second build we store (w) as the largest checked set, and (x) is
    //   not a subset of that.

    // Case when we DON'T need to re-check:
    // - a configured target is deleted. Deletion can only resolve conflicts, not introduce any, and
    //   if the previous build had a conflict then foundActionConflictInLatestCheck would be true,
    //   and if the previous build had no conflict then deleting a CT won't change that.
    //   Example that triggers this scenario:
    //   1.  genrule(name='x', srcs=['A'], ...)
    //       genrule(name='y', outs=['A'], ...)
    //   2.  Build (x y)
    //   3.  Rename 'x' to 'y', and 'y' to 'z'
    //   4.  Build (y z)
    //   5.  Null-build (y z) again
    // We only delete the old 'x' value in (5), and we don't evaluate nor dirty anything, nor was
    // (4) bad. So there's no reason to re-check just because we deleted something.
    return false;
  }

  public ArtifactFactory getArtifactFactory() {
    return artifactFactory;
  }

  CachingAnalysisEnvironment createAnalysisEnvironment(
      ActionLookupKey owner,
      ExtendedEventHandler eventHandler,
      Environment env,
      BuildConfigurationValue config,
      StarlarkBuiltinsValue starlarkBuiltinsValue) {
    boolean extendedSanityChecks = config != null && config.extendedSanityChecks();
    boolean allowAnalysisFailures = config != null && config.allowAnalysisFailures();
    return new CachingAnalysisEnvironment(
        artifactFactory,
        skyframeExecutor.getActionKeyContext(),
        owner,
        extendedSanityChecks,
        allowAnalysisFailures,
        eventHandler,
        env,
        starlarkBuiltinsValue);
  }

  /**
   * Invokes the appropriate constructor to create a {@link ConfiguredTarget} instance.
   *
   * <p>For use in {@code ConfiguredTargetFunction}.
   *
   * <p>Returns null if Skyframe deps are missing or upon certain errors.
   */
  @Nullable
  ConfiguredTarget createConfiguredTarget(
      Target target,
      BuildConfigurationValue configuration,
      CachingAnalysisEnvironment analysisEnvironment,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      @Nullable NestedSet<Package> transitivePackages,
      ExecGroupCollection.Builder execGroupCollectionBuilder)
      throws InterruptedException,
          ActionConflictException,
          InvalidExecGroupException,
          AnalysisFailurePropagationException,
          StarlarkExecTransitionLoadingException {
    Preconditions.checkState(
        enableAnalysis, "Already in execution phase %s %s", target, configuration);
    Preconditions.checkNotNull(analysisEnvironment);
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(prerequisiteMap);

    Optional<StarlarkAttributeTransitionProvider> starlarkExecTransition =
        StarlarkExecTransitionLoader.loadStarlarkExecTransition(
            configuration == null ? null : configuration.getOptions(),
            (bzlKey) ->
                (BzlLoadValue)
                    analysisEnvironment
                        .getSkyframeEnv()
                        .getValueOrThrow(bzlKey, BzlLoadFailedException.class));
    if (starlarkExecTransition == null) {
      return null;
    }

    return factory.createConfiguredTarget(
        analysisEnvironment,
        artifactFactory,
        target,
        configuration,
        configuredTargetKey,
        prerequisiteMap,
        configConditions,
        toolchainContexts,
        transitivePackages,
        execGroupCollectionBuilder,
        starlarkExecTransition.orElse(null));
  }

  /**
   * Workaround to clear all legacy data, like the artifact factory. We need to clear them to avoid
   * conflicts. TODO(bazel-team): Remove this workaround. [skyframe-execution]
   */
  void clearLegacyData() {
    artifactFactory.clear();
    starlarkTransitionCache.clear();
  }

  /**
   * Clears any data cached in this BuildView. To be called when the attached SkyframeExecutor is
   * reset.
   */
  public void reset() {
    configuration = null;
    originalConfigurationOptions = null;
    skyframeAnalysisWasDiscarded = false;
    clearLegacyData();
  }

  /**
   * Hack to invalidate actions in legacy action graph when their values are invalidated in
   * skyframe.
   */
  EvaluationProgressReceiver getProgressReceiver() {
    return progressReceiver;
  }

  /** Clear the invalidated action lookup nodes detected during loading and analysis phases. */
  public void clearInvalidatedActionLookupKeys() {
    dirtiedActionLookupKeys = Sets.newConcurrentHashSet();
    starlarkTransitionCache.clear();
  }

  /**
   * {@link #createConfiguredTarget} will only create configured targets if this is set to true. It
   * should be set to true before any Skyframe update call that might call into {@link
   * #createConfiguredTarget}, and false immediately after the call. Use it to fail-fast in the case
   * that a target is requested for analysis not during the analysis phase.
   */
  public void enableAnalysis(boolean enable) {
    this.enableAnalysis = enable;
  }

  public StarlarkTransitionCache getStarlarkTransitionCache() {
    return starlarkTransitionCache;
  }

  private final class ActionLookupValueProgressReceiver implements EvaluationProgressReceiver {
    private final AtomicInteger configuredObjectCount = new AtomicInteger();
    private final AtomicInteger actionCount = new AtomicInteger();
    private final AtomicInteger configuredTargetCount = new AtomicInteger();
    private final AtomicInteger configuredTargetActionCount = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> actionCountByMnemonic =
        new ConcurrentHashMap<>();

    @Override
    public void dirtied(SkyKey skyKey, DirtyType dirtyType) {
      if (skyKey instanceof ActionLookupKey) {
        // If the value was just dirtied and not deleted, then it may not be truly invalid, since
        // it may later get re-validated. Therefore adding the key to dirtiedConfiguredTargetKeys
        // is provisional--if the key is later evaluated and the value found to be clean, then we
        // remove it from the set.
        dirtiedActionLookupKeys.add((ActionLookupKey) skyKey);
      }
    }

    @Override
    public void evaluated(
        SkyKey skyKey,
        EvaluationState state,
        @Nullable SkyValue newValue,
        @Nullable ErrorInfo newError,
        @Nullable GroupedDeps directDeps) {
      // We tolerate any action lookup keys here, although we only expect configured targets,
      // aspects, and the workspace status value.
      if (!(skyKey instanceof ActionLookupKey)) {
        return;
      }
      if (!state.versionChanged()) {
        // ActionLookupValue subclasses don't implement equality, so must have been marked clean.
        dirtiedActionLookupKeys.remove(skyKey);
      } else if (state.succeeded()) {
        boolean isConfiguredTarget = skyKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET);
        if (isConfiguredTarget) {
          ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) skyKey;
          ConfiguredTargetValue configuredTargetValue = (ConfiguredTargetValue) newValue;
          if (!Objects.equals(
              configuredTargetKey.getConfigurationKey(),
              configuredTargetValue.getConfiguredTarget().getConfigurationKey())) {
            // The node entry performs delegation and doesn't own the value. Skips it to avoid
            // overcounting.
            return;
          }
          configuredTargetCount.incrementAndGet();
        }
        configuredObjectCount.incrementAndGet();
        if (newValue instanceof ActionLookupValue alv) {
          if (alv instanceof AspectValue) {
            if (AspectValue.isForAliasTarget((AspectValue) alv)) {
              // Created actions will be counted from {@link AspectValue} on the original target.
              return;
            }
          }
          if (alv.getNumActions() == 0) {
            // No actions in deserialized action lookup values, and calling #getActions will
            // cause an NPE.
            return;
          }

          // During multithreaded operation, this is only set to true, so no concurrency issues.
          someActionLookupValueEvaluated = true;
          ImmutableList<ActionAnalysisMetadata> actions = alv.getActions();
          for (ActionAnalysisMetadata action : actions) {
            actionCountByMnemonic
                .computeIfAbsent(action.getMnemonic(), (m) -> new AtomicInteger(0))
                .incrementAndGet();
          }

          int numActions = actions.size();
          actionCount.addAndGet(numActions);
          if (isConfiguredTarget) {
            configuredTargetActionCount.addAndGet(numActions);
          }
        }
      }
    }

    public void reset() {
      configuredObjectCount.set(0);
      actionCount.set(0);
      configuredTargetCount.set(0);
      configuredTargetActionCount.set(0);
    }
  }

  /** Provides the list of coverage artifacts to be built. */
  @FunctionalInterface
  public interface CoverageReportActionsWrapperSupplier {
    ImmutableSet<Artifact> getCoverageReportArtifacts(
        Set<ConfiguredTarget> configuredTargets, Set<ConfiguredTarget> allTargetsToTest)
        throws InterruptedException;
  }

  /** Encapsulates the context required to construct a test BuildDriverKey. */
  public interface BuildDriverKeyTestContext {
    String getTestStrategy();

    boolean forceExclusiveTestsInParallel();

    boolean forceExclusiveIfLocalTestsInParallel();
  }
}
