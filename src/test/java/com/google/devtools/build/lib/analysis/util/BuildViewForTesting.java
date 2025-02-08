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
package com.google.devtools.build.lib.analysis.util;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.PackageRoots;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolutionHelpers;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker.IncompatibleTargetException;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.runtime.QuiescingExecutorsImpl;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.ReportedException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.UnreportedException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.DependencyResolver;
import com.google.devtools.build.lib.skyframe.SkyFunctionEnvironmentForTesting;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyframeBuildView;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider.DisabledDependenciesProvider;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.Version;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.starlark.java.eval.Mutability;

/**
 * A util class that contains all the helper stuff previously in BuildView that only exists to give
 * tests access to Skyframe internals. The code largely predates the introduction of Skyframe, and
 * mostly exists to avoid having to rewrite our tests to work with Skyframe natively.
 */
public class BuildViewForTesting {
  private final BuildView buildView;
  private final SkyframeExecutor skyframeExecutor;
  private final SkyframeBuildView skyframeBuildView;

  private final ConfiguredRuleClassProvider ruleClassProvider;

  private ImmutableMap<ActionLookupKey, Version> currentActionLookupKeys = ImmutableMap.of();

  /**
   * Tracks keys that mismatched at a previous diff computation.
   *
   * <p>{@link #populateActionLookupKeyMapAndGetDiff} scans the entire graph and computes a diff
   * against the previous {@link #currentActionLookupKeys} value. For this to be consistent with
   * {@link SkyframeExecutor#getEvaluatedCounts} it needs to filter out {@link
   * SkyFunctions#CONFIGURED_TARGET} nodes that do not own the underlying {@link
   * ConfiguredTargetValue}s. The owners have {@link ConfiguredTargetKey#getConfigurationKey} values
   * matching the {@link ConfiguredTarget#getConfigurationKey} values.
   *
   * <p>The problem is that the Skyframe graph may contain entries that are not done at the time of
   * graph inspection. This may occur when there's an incremental evaluation that doesn't require a
   * previously computed value.
   *
   * <p>If the {@link ConfiguredTargetValue} is unavailable and can't be compared, the diff still
   * needs to decide whether to skip it. If it was skipped previously, it needs to be skipped again.
   * Otherwise it'll show up as a newly evaluated node.
   */
  private ImmutableSet<ConfiguredTargetKey> previousProxyNodeKeys = ImmutableSet.of();

  public BuildViewForTesting(
      BlazeDirectories directories,
      ConfiguredRuleClassProvider ruleClassProvider,
      SkyframeExecutor skyframeExecutor,
      CoverageReportActionFactory coverageReportActionFactory) {
    this.buildView =
        new BuildView(
            directories, ruleClassProvider, skyframeExecutor, coverageReportActionFactory);
    this.ruleClassProvider = ruleClassProvider;
    this.skyframeExecutor = Preconditions.checkNotNull(skyframeExecutor);
    this.skyframeBuildView = skyframeExecutor.getSkyframeBuildView();
  }

  Set<ActionLookupKey> getSkyframeEvaluatedActionLookupKeyCountForTesting() {
    Set<ActionLookupKey> actionLookupKeys = populateActionLookupKeyMapAndGetDiff();
    Preconditions.checkState(
        actionLookupKeys.size() == skyframeBuildView.getEvaluatedCounts().total(),
        "Number of newly evaluated action lookup values %s does not agree with number that changed"
            + " in graph: %s. Keys: %s",
        actionLookupKeys.size(),
        skyframeBuildView.getEvaluatedCounts().total(),
        actionLookupKeys);
    return actionLookupKeys;
  }

  private Set<ActionLookupKey> populateActionLookupKeyMapAndGetDiff() {
    InMemoryGraph graph = skyframeExecutor.getEvaluator().getInMemoryGraph();
    var proxyNodeKeys = ImmutableSet.<ConfiguredTargetKey>builder();
    ImmutableMap<ActionLookupKey, Version> newMap =
        graph.getAllNodeEntries().stream()
            .filter(
                entry -> {
                  SkyKey key = entry.getKey();
                  if (!(key instanceof ActionLookupKey)) {
                    return false;
                  }
                  if (!key.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
                    return true;
                  }

                  var ctKey = (ConfiguredTargetKey) key;

                  if (!entry.isDone()) {
                    if (previousProxyNodeKeys.contains(ctKey)) {
                      // The node is dirty and was a proxy previously. Filters the entry as long as
                      // it remains not done.
                      proxyNodeKeys.add(ctKey);
                      return false;
                    }
                    return true;
                  }

                  var value = (ConfiguredTargetValue) entry.getValue();
                  if (value == null) {
                    // The node has an error. No filtering is applied in this case.
                    return true;
                  }
                  if (!Objects.equals(
                      ctKey.getConfigurationKey(),
                      value.getConfiguredTarget().getConfigurationKey())) {
                    // The configurations are not equal so the node is only performing delegation
                    // and doesn't own the configured target.
                    proxyNodeKeys.add(ctKey);
                    return false;
                  }
                  return true;
                })
            .collect(toImmutableMap(e -> (ActionLookupKey) e.getKey(), NodeEntry::getVersion));
    previousProxyNodeKeys = proxyNodeKeys.build();
    MapDifference<ActionLookupKey, Version> difference =
        Maps.difference(newMap, currentActionLookupKeys);
    currentActionLookupKeys = newMap;
    return Sets.union(
        difference.entriesDiffering().keySet(), difference.entriesOnlyOnLeft().keySet());
  }

  /** Returns whether the given configured target has errors. */
  public boolean hasErrors(ConfiguredTarget configuredTarget) {
    return configuredTarget == null;
  }

  @ThreadCompatible
  public AnalysisResult update(
      TargetPatternPhaseValue loadingResult,
      BuildOptions targetOptions,
      ImmutableSet<Label> explicitTargetPatterns,
      List<String> aspects,
      ImmutableMap<String, String> aspectsParameters,
      AnalysisOptions viewOptions,
      boolean keepGoing,
      int loadingPhaseThreads,
      TopLevelArtifactContext topLevelOptions,
      ExtendedEventHandler eventHandler,
      EventBus eventBus)
      throws ViewCreationFailedException,
          InterruptedException,
          InvalidConfigurationException,
          BuildFailedException,
          TestExecException,
          AbruptExitException {
    populateActionLookupKeyMapAndGetDiff();
    return buildView.update(
        loadingResult,
        targetOptions,
        explicitTargetPatterns,
        aspects,
        aspectsParameters,
        viewOptions,
        keepGoing,
        /* skipIncompatibleExplicitTargets= */ false,
        /* checkForActionConflicts= */ true,
        QuiescingExecutorsImpl.forTesting(),
        topLevelOptions,
        /* reportIncompatibleTargets= */ true,
        eventHandler,
        eventBus,
        BugReporter.defaultInstance(),
        /* includeExecutionPhase= */ false,
        /* skymeldAnalysisOverlapPercentage= */ 0,
        /* resourceManager= */ null,
        /* buildResultListener= */ null,
        /* executionSetupCallback= */ null,
        /* buildConfigurationsCreatedCallback= */ null,
        /* buildDriverKeyTestContext= */ null,
        /* additionalConfigurationChangeEvent= */ Optional.empty(),
        /* remoteAnalysisCachingDependenciesProvider= */ DisabledDependenciesProvider.INSTANCE);
  }

  /** Sets the configuration. Not thread-safe. */
  public void setConfigurationForTesting(BuildConfigurationValue configuration) {
    skyframeBuildView.setConfiguration(configuration, configuration.getOptions(), true);
  }

  public ArtifactFactory getArtifactFactory() {
    return skyframeBuildView.getArtifactFactory();
  }

  /**
   * Sets the possible artifact roots in the artifact factory. This allows the factory to resolve
   * paths with unknown roots to artifacts.
   */
  public void setArtifactRoots(PackageRoots packageRoots) {
    getArtifactFactory().setPackageRoots(packageRoots.getPackageRootLookup());
  }

  public Collection<ConfiguredTarget> getDirectPrerequisitesForTesting(
      ExtendedEventHandler eventHandler, ConfiguredTarget ct)
      throws InterruptedException,
          DependencyResolutionHelpers.Failure,
          InvalidConfigurationException,
          InconsistentAspectOrderException,
          StarlarkTransition.TransitionException {
    return Collections2.transform(
        getConfiguredTargetAndDataDirectPrerequisitesForTesting(eventHandler, ct),
        ConfiguredTargetAndData::getConfiguredTarget);
  }

  protected Collection<ConfiguredTargetAndData>
      getConfiguredTargetAndDataDirectPrerequisitesForTesting(
          ExtendedEventHandler eventHandler, ConfiguredTarget configuredTarget)
          throws InterruptedException,
              DependencyResolutionHelpers.Failure,
              InvalidConfigurationException,
              InconsistentAspectOrderException,
              StarlarkTransition.TransitionException {
    DependencyResolver.State state =
        initializeDependencyResolverState(eventHandler, configuredTarget);
    DependencyResolver producer = runDependencyResolver(eventHandler, configuredTarget, state);
    return producer.getDepValueMap().values();
  }

  /**
   * Returns a configured target for the specified target and configuration. If the target in
   * question has a top-level rule class transition, that transition is applied in the returned
   * ConfiguredTarget.
   *
   * <p>Returns {@code null} if something goes wrong.
   */
  public ConfiguredTarget getConfiguredTargetForTesting(
      ExtendedEventHandler eventHandler, Label label, BuildConfigurationValue config)
      throws InvalidConfigurationException, InterruptedException {
    return skyframeExecutor.getConfiguredTargetForTesting(eventHandler, label, config);
  }

  ConfiguredTargetAndData getConfiguredTargetAndDataForTesting(
      ExtendedEventHandler eventHandler, Label label, BuildConfigurationValue config)
      throws InvalidConfigurationException, InterruptedException {
    return skyframeExecutor.getConfiguredTargetAndDataForTesting(eventHandler, label, config);
  }

  /**
   * Returns a RuleContext which is the same as the original RuleContext of the target parameter.
   */
  public RuleContext getRuleContextForTesting(
      ConfiguredTarget target, StoredEventHandler eventHandler)
      throws DependencyResolutionHelpers.Failure,
          InvalidConfigurationException,
          InterruptedException,
          InconsistentAspectOrderException,
          ToolchainException,
          StarlarkTransition.TransitionException,
          InvalidExecGroupException {
    BuildConfigurationValue targetConfig =
        skyframeExecutor.getConfiguration(eventHandler, target.getConfigurationKey());
    SkyFunction.Environment skyframeEnv =
        new SkyFunctionEnvironmentForTesting(eventHandler, skyframeExecutor);
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue)
            Preconditions.checkNotNull(skyframeEnv.getValue(StarlarkBuiltinsValue.key()));
    CachingAnalysisEnvironment analysisEnv =
        new CachingAnalysisEnvironment(
            getArtifactFactory(),
            skyframeExecutor.getActionKeyContext(),
            ConfiguredTargetKey.builder()
                .setLabel(target.getLabel())
                .setConfiguration(targetConfig)
                .build(),
            targetConfig.extendedSanityChecks(),
            targetConfig.allowAnalysisFailures(),
            eventHandler,
            skyframeEnv,
            starlarkBuiltinsValue);
    return getRuleContextForTesting(eventHandler, target, analysisEnv);
  }

  /**
   * Creates and returns a rule context that is equivalent to the one that was used to create the
   * given configured target.
   */
  public RuleContext getRuleContextForTesting(
      ExtendedEventHandler eventHandler, ConfiguredTarget configuredTarget, AnalysisEnvironment env)
      throws DependencyResolutionHelpers.Failure,
          InvalidConfigurationException,
          InterruptedException,
          InconsistentAspectOrderException,
          ToolchainException,
          StarlarkTransition.TransitionException,
          InvalidExecGroupException {
    DependencyResolver.State state =
        initializeDependencyResolverState(eventHandler, configuredTarget);
    DependencyResolver producer = runDependencyResolver(eventHandler, configuredTarget, state);

    OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> prerequisiteMap =
        producer.getDepValueMap();

    Target target = state.targetAndConfiguration.getTarget();
    String targetDescription = target.toString();

    ToolchainCollection<UnloadedToolchainContext> unloadedToolchainCollection =
        producer.getUnloadedToolchainContexts();

    ToolchainCollection.Builder<ResolvedToolchainContext> resolvedToolchainContext =
        ToolchainCollection.builder();
    for (Map.Entry<String, UnloadedToolchainContext> unloadedToolchainContext :
        unloadedToolchainCollection.contextMap().entrySet()) {
      ResolvedToolchainContext toolchainContext =
          ResolvedToolchainContext.load(
              unloadedToolchainContext.getValue(),
              targetDescription,
              ImmutableSet.copyOf(
                  prerequisiteMap.get(
                      DependencyKind.forExecGroup(unloadedToolchainContext.getKey()))));
      resolvedToolchainContext.addContext(unloadedToolchainContext.getKey(), toolchainContext);
    }

    return new RuleContext.Builder(
            env,
            target,
            /* aspects= */ ImmutableList.of(),
            state.targetAndConfiguration.getConfiguration())
        .setRuleClassProvider(ruleClassProvider)
        .setConfigurationFragmentPolicy(
            target.getAssociatedRule().getRuleClassObject().getConfigurationFragmentPolicy())
        .setActionOwnerSymbol(ConfiguredTargetKey.fromConfiguredTarget(configuredTarget))
        .setMutability(Mutability.create("configured target"))
        .setVisibility(VisibilityProvider.PUBLIC_VISIBILITY)
        .setPrerequisites(ConfiguredTargetFactory.removeToolchainDeps(prerequisiteMap))
        .setConfigConditions(ConfigConditions.EMPTY)
        .setToolchainContexts(resolvedToolchainContext.build())
        .setExecGroupCollectionBuilder(state.execGroupCollectionBuilder)
        .unsafeBuild();
  }

  private DependencyResolver runDependencyResolver(
      ExtendedEventHandler eventHandler,
      ConfiguredTarget configuredTarget,
      DependencyResolver.State state)
      throws InterruptedException {
    DependencyResolver producer = new DependencyResolver(state.targetAndConfiguration);
    try {
      if (!producer.evaluate(
          state,
          ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
          ruleClassProvider,
          skyframeBuildView.getStarlarkTransitionCache(),
          /* semaphoreLocker= */ () -> {},
          new SkyFunctionEnvironmentForTesting(eventHandler, skyframeExecutor),
          eventHandler)) {
        throw new IllegalStateException(configuredTarget + " should be already evaluated");
      }
    } catch (ReportedException | UnreportedException | IncompatibleTargetException e) {
      throw new IllegalStateException(e); // Should not be possible for done ConfiguredTarget.
    }
    return producer;
  }

  private DependencyResolver.State initializeDependencyResolverState(
      ExtendedEventHandler eventHandler, ConfiguredTarget configuredTarget)
      throws InterruptedException {
    // In production, the TargetAndConfiguration value is based on final configuration of the
    // ConfiguredTarget after any rule transition is applied.
    BuildConfigurationValue configuration =
        skyframeExecutor.getConfiguration(eventHandler, configuredTarget.getConfigurationKey());
    Target target;
    try {
      target =
          skyframeExecutor.getPackageManager().getTarget(eventHandler, configuredTarget.getLabel());
    } catch (NoSuchPackageException | NoSuchTargetException e) {
      eventHandler.handle(
          Event.error("Failed to get target when trying to get rule context for testing"));
      throw new IllegalStateException(e);
    }
    return DependencyResolver.State.createForTesting(
        new TargetAndConfiguration(target.getAssociatedRule(), configuration));
  }

  /** Clears the analysis cache as in --discard_analysis_cache. */
  void clearAnalysisCache(
      ImmutableSet<ConfiguredTarget> topLevelTargets, ImmutableSet<AspectKey> topLevelAspects) {
    skyframeBuildView.clearAnalysisCache(topLevelTargets, topLevelAspects);
  }
}
