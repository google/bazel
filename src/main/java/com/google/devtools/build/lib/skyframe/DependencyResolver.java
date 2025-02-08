// Copyright 2022 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationId;
import static com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage;
import static com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.createDetailedExitCode;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolutionHelpers;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader.StarlarkExecTransitionLoadingException;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionCollector;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionCreationException;
import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker.IncompatibleTargetException;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.producers.DependencyContext;
import com.google.devtools.build.lib.analysis.producers.DependencyContextError;
import com.google.devtools.build.lib.analysis.producers.DependencyContextProducer;
import com.google.devtools.build.lib.analysis.producers.DependencyContextProducerWithCompatibilityCheck;
import com.google.devtools.build.lib.analysis.producers.DependencyError;
import com.google.devtools.build.lib.analysis.producers.DependencyMapProducer;
import com.google.devtools.build.lib.analysis.producers.DependencyMapProducer.MaterializerException;
import com.google.devtools.build.lib.analysis.producers.MissingEdgeError;
import com.google.devtools.build.lib.analysis.producers.PrerequisiteParameters;
import com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsInputs;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.ReportedException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.UnreportedException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextUtil;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainException;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment;
import com.google.devtools.build.skyframe.state.Driver;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Helper logic for {@link ConfiguredTargetFunction} and {@link AspectFunction}: performs the
 * analysis phase through computation of prerequisites.
 *
 * <p>For the {@link ConfiguredTargetFunction} this includes:
 *
 * <ul>
 *   <li>getting this target's {@link Target} and {@link BuildConfigurationValue}
 *   <li>getting this target's {@code select()} keys ({@link ConfigConditions}), which are used to
 *       evaluate all rule attributes with {@code select()} and determine exact dependencies
 *   <li>figuring out which toolchains this target needs
 *   <li>getting the {@link ConfiguredTargetValue}s of this target's prerequisites (through
 *       recursive calls to {@link ConfiguredTargetFunction}
 * </ul>
 *
 * <p>Figuring out which toolchains are needed and computing the {@link ConfigConditions} is
 * performed by the {@link DependencyContextProducerWithCompatibilityCheck}, which additionally
 * checks for directly incompatible targets using the {@link
 * IncompatibleTargetChecker.IncompatibleTargetProducer}.
 *
 * <p>Cumulatively, this is enough information to run the target's rule logic.
 *
 * <p>This class also provides getters for the above data for subsequent analysis logic to use.
 *
 * <p>See {@link ConfiguredTargetFunction} for more review on analysis implementation.
 *
 * <p>{@link AspectFunction} shares the logic computing a target's prerequisites via the {@link
 * DependencyResolver#computeDependencies}.
 */
public final class DependencyResolver {
  /**
   * Memoizies computation steps of {@link #evaluate} so they do not need to be repeated on {@code
   * Skyframe} restart.
   */
  public static class State
      implements SkyKeyComputeState,
          DependencyContextProducer.ResultSink,
          DependencyMapProducer.ResultSink {
    /** Must be set before calling {@link #evaluate}. */
    public TargetAndConfiguration targetAndConfiguration;

    /** Set once {@link #dependencyContextProducer} starts. */
    @VisibleForTesting public ExecGroupCollection.Builder execGroupCollectionBuilder;

    /**
     * Computes the dependency context, comprised of the unloaded toolchain contexts and the config
     * conditions.
     *
     * <p>One of {@link #dependencyContext} or {@link #dependencyContextError} will be set upon
     * completion.
     */
    @Nullable // Non-null when in-flight.
    Driver dependencyContextProducer;

    @VisibleForTesting // package-private
    @Nullable
    public DependencyContext dependencyContext;

    @Nullable DependencyContextError dependencyContextError;

    /**
     * Computes the configured target dependency map, including aspects if applicable.
     *
     * <p>One of {@link #dependencyMap} or {@link #dependencyMapError} will be set upon completion.
     */
    @Nullable // Non-null when in-flight.
    private Driver dependencyMapProducer;

    @Nullable private OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> dependencyMap;
    @Nullable private DependencyError dependencyMapError;

    final TransitiveDependencyState transitiveState;
    private final TransitionCollector transitionCollector;

    /**
     * Stores events emitted by memoized computations.
     *
     * <p>Both the {@link #computeDependencies} and the {@link TargetAndConfigurationProducer} may
     * perform Starlark transitions that emit events. Skyframe uses only the events emitted to
     * {@code env.getListener()} on a call to {@link #evaluate} that had no missing deps. Since the
     * computations are memoized, they do not re-emit events when Skyframe restarts. Therefore
     * events are stored and replayed when subsequent Skyframe restarts occur.
     */
    final StoredEventHandler storedEvents = new StoredEventHandler();

    public static State createForTesting(TargetAndConfiguration targetAndConfiguration) {
      var state =
          new State(/* storeTransitivePackages= */ false, /* prerequisitePackages= */ p -> null);
      state.targetAndConfiguration = targetAndConfiguration;
      return state;
    }

    public static State createForCquery(
        TargetAndConfiguration targetAndConfiguration, TransitionCollector transitionCollector) {
      var state =
          new State(
              /* storeTransitivePackages= */ false,
              /* prerequisitePackages= */ p -> null,
              transitionCollector);
      state.targetAndConfiguration = targetAndConfiguration;
      return state;
    }

    State(boolean storeTransitivePackages, PrerequisitePackageFunction prerequisitePackages) {
      this(storeTransitivePackages, prerequisitePackages, NULL_TRANSITION_COLLECTOR);
    }

    private State(
        boolean storeTransitivePackages,
        PrerequisitePackageFunction prerequisitePackages,
        TransitionCollector transitionCollector) {
      this.transitiveState =
          new TransitiveDependencyState(storeTransitivePackages, prerequisitePackages);
      this.transitionCollector = transitionCollector;
    }

    public NestedSetBuilder<Cause> transitiveRootCauses() {
      return transitiveState.transitiveRootCauses();
    }

    public NestedSet<Package> transitivePackages() {
      return transitiveState.transitivePackages();
    }

    @Override
    public void acceptDependencyContext(DependencyContext value) {
      this.dependencyContext = value;
    }

    @Override
    public void acceptDependencyContextError(DependencyContextError error) {
      this.dependencyContextError = error;
    }

    @Override
    public void acceptDependencyMap(
        OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> value) {
      this.dependencyMap = value;
    }

    @Override
    public void acceptDependencyMapError(DependencyError error) {
      this.dependencyMapError = error;
    }

    @Override
    public void acceptDependencyMapError(MissingEdgeError error) {
      error.emitCausesAndEvents(targetAndConfiguration, transitiveState, storedEvents);
    }

    @Override
    public void acceptTransition(
        DependencyKind kind, Label label, ConfigurationTransition transition) {
      transitionCollector.acceptTransition(kind, label, transition);
    }
  }

  /** Lets calling logic provide a semaphore to restrict the number of concurrent analysis calls. */
  public interface SemaphoreAcquirer {
    void acquireSemaphore() throws InterruptedException;
  }

  private final TargetAndConfiguration targetAndConfiguration;
  private OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap = null;
  private ConfigConditions configConditions = null;
  private PlatformInfo platformInfo = null;
  @Nullable private ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts = null;

  public DependencyResolver(TargetAndConfiguration targetAndConfiguration) {
    this.targetAndConfiguration = Preconditions.checkNotNull(targetAndConfiguration);
  }

  /** Return this target's {@link TargetAndConfiguration}. */
  TargetAndConfiguration getTargetAndConfiguration() {
    return targetAndConfiguration;
  }

  /**
   * Return this target's fully resolved dependencies.
   *
   * <p>{@link #evaluate} must be called before this info is available.
   */
  public OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> getDepValueMap() {
    return Preconditions.checkNotNull(depValueMap);
  }

  /**
   * Return the keys in this target's {@code select()}s.
   *
   * <p>{@link #evaluate} must be called before this info is available.
   */
  ConfigConditions getConfigConditions() {
    return Preconditions.checkNotNull(configConditions);
  }

  /**
   * Return this target's platform metadata, or null if it doesn't use platforms.
   *
   * <p>{@link #evaluate} must be called before this info is available.
   */
  @Nullable
  PlatformInfo getPlatformInfo() {
    return platformInfo;
  }

  /**
   * Return this target's toolchain requirements, or null if it doesn't use toolchains.
   *
   * <p>{@link #evaluate} must be called before this info is available.
   */
  @VisibleForTesting
  @Nullable
  public ToolchainCollection<UnloadedToolchainContext> getUnloadedToolchainContexts() {
    return unloadedToolchainContexts;
  }

  /**
   * Runs the analysis phase for this target through prerequisite evaluation.
   *
   * <p>See {@link DependencyResolver} javadoc for details.
   *
   * <p>This is the main entry point to {@link DependencyResolver}. This method runs its share of
   * the analysis phase, after which all the data is computes is accessible to calling code through
   * related getters.
   *
   * <p>After instantiating this class, this method should be called once. It returns false when any
   * Skyframe dependencies need to be evaluated, else true.
   */
  public boolean evaluate(
      State state,
      ConfiguredTargetKey configuredTargetKey,
      RuleClassProvider ruleClassProvider,
      StarlarkTransitionCache transitionCache,
      SemaphoreAcquirer semaphoreLocker,
      LookupEnvironment env,
      ExtendedEventHandler listener)
      throws ReportedException,
          UnreportedException,
          IncompatibleTargetException,
          InterruptedException {
    // TODO(janakr): this call may tie up this thread indefinitely, reducing the parallelism of
    //  Skyframe. This is a strict improvement over the prior state of the code, in which we ran
    //  with #processors threads, but ideally we would call #tryAcquire here, and if we failed,
    //  would exit this SkyFunction and restart it when permits were available.
    semaphoreLocker.acquireSemaphore();
    try {
      var dependencyContext =
          getDependencyContext(state, configuredTargetKey, ruleClassProvider, env, listener);
      if (dependencyContext == null) {
        return false;
      }
      this.unloadedToolchainContexts = dependencyContext.unloadedToolchainContexts();
      this.platformInfo =
          unloadedToolchainContexts != null ? unloadedToolchainContexts.getTargetPlatform() : null;
      this.configConditions = dependencyContext.configConditions();

      // TODO(ulfjack): ConfiguredAttributeMapper (indirectly used from computeDependencies) isn't
      // safe to use if there are missing config conditions, so we stop here, but only if there are
      // config conditions - though note that we can't check if configConditions is non-empty - it
      // may be empty for other reasons. It would be better to continue here so that we can collect
      // more root causes during computeDependencies.
      // Note that this doesn't apply to AspectFunction, because aspects can't have configurable
      // attributes.
      NestedSetBuilder<Cause> transitiveRootCauses = state.transitiveRootCauses();
      if (!transitiveRootCauses.isEmpty()
          && !Objects.equals(configConditions, ConfigConditions.EMPTY)) {
        NestedSet<Cause> causes = transitiveRootCauses.build();
        listener.handle(
            Event.error(
                targetAndConfiguration.getTarget().getLocation(),
                "Cannot compute config conditions"));
        throw new ReportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration.getTarget(),
                configurationId(targetAndConfiguration.getConfiguration()),
                "Cannot compute config conditions",
                causes,
                getPrioritizedDetailedExitCode(causes)));
      }

      Optional<StarlarkAttributeTransitionProvider> starlarkExecTransition =
          StarlarkExecTransitionLoader.loadStarlarkExecTransition(
              targetAndConfiguration.getConfiguration() == null
                  ? null
                  : targetAndConfiguration.getConfiguration().getOptions(),
              (bzlKey) -> (BzlLoadValue) env.getValueOrThrow(bzlKey, BzlLoadFailedException.class));
      if (starlarkExecTransition == null) {
        return false;
      }

      // Calculate the dependencies of this target.
      depValueMap =
          computeDependencies(
              state,
              configuredTargetKey,
              /* aspects= */ ImmutableList.of(),
              transitionCache,
              starlarkExecTransition.orElse(null),
              env,
              listener,
              /* baseTargetPrerequisitesSupplier= */ null,
              /* baseTargetUnloadedToolchainContexts= */ null);
      if (!transitiveRootCauses.isEmpty()) {
        NestedSet<Cause> causes = transitiveRootCauses.build();
        // TODO(bazel-team): consider reporting the error in this class vs. exporting it for
        // BuildTool to handle. Calling code needs to be untangled for that to work and pass tests.
        throw new UnreportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration.getTarget(),
                configurationId(targetAndConfiguration.getConfiguration()),
                "Analysis failed",
                causes,
                getPrioritizedDetailedExitCode(causes)));
      }
      if (depValueMap == null) {
        return false;
      }
    } catch (DependencyEvaluationException
        | ConfiguredValueCreationException
        | AspectCreationException
        | StarlarkExecTransitionLoadingException
        | ToolchainException
        | ExecGroupCollection.InvalidExecGroupException e) {
      // We handle exceptions in a dedicated method to keep this method concise and readable.
      handleException(listener, targetAndConfiguration.getTarget(), e);
    }
    return true;
  }

  @VisibleForTesting
  @Nullable // Null when a Skyframe restart is needed.
  public static DependencyContext getDependencyContext(
      State state,
      ConfiguredTargetKey configuredTargetKey,
      RuleClassProvider ruleClassProvider,
      LookupEnvironment env,
      ExtendedEventHandler listener)
      throws InterruptedException,
          ToolchainException,
          ConfiguredValueCreationException,
          IncompatibleTargetException,
          DependencyEvaluationException,
          ExecGroupCollection.InvalidExecGroupException {
    if (state.dependencyContext != null) {
      return state.dependencyContext;
    }
    if (state.dependencyContextProducer == null) {
      var targetAndConfiguration = state.targetAndConfiguration;
      var unloadedToolchainContextsInputs =
          getUnloadedToolchainContextsInputs(
              targetAndConfiguration,
              configuredTargetKey.getExecutionPlatformLabel(),
              ruleClassProvider,
              listener);
      state.execGroupCollectionBuilder = unloadedToolchainContextsInputs;
      state.dependencyContextProducer =
          new Driver(
              new DependencyContextProducerWithCompatibilityCheck(
                  targetAndConfiguration,
                  configuredTargetKey,
                  unloadedToolchainContextsInputs,
                  state.transitiveState,
                  (DependencyContextProducer.ResultSink) state));
    }
    if (state.dependencyContextProducer.drive(env)) {
      state.dependencyContextProducer = null;
    }

    // During error bubbling, the state machine might not be done, but still emit an error.
    var error = state.dependencyContextError;
    if (error != null) {
      switch (error.kind()) {
        case TOOLCHAIN:
          throw error.toolchain();
        case CONFIGURED_VALUE_CREATION:
          throw error.configuredValueCreation();
        case INCOMPATIBLE_TARGET:
          throw error.incompatibleTarget();
        case VALIDATION:
          var validationException = error.validation();
          var targetAndConfiguration = state.targetAndConfiguration;
          throw handleDependencyRootCauseError(
              targetAndConfiguration,
              targetAndConfiguration.getTarget().getLocation(),
              validationException.getMessage(),
              listener);
      }
      throw new IllegalStateException("unreachable");
    }

    return state.dependencyContext; // Null if not yet done.
  }

  /**
   * Handles all exceptions that {@link #evaluate} may throw.
   *
   * <p>This is its own method because there's a lot of logic here and when directly inlined it
   * makes it harder to follow the calling method's control flow.
   */
  private void handleException(ExtendedEventHandler listener, Target target, Exception untyped)
      throws ReportedException {

    throw switch (untyped) {
      case DependencyEvaluationException e -> {
        String errorMessage = e.getMessage();
        if (!e.depReportedOwnError()) {
          listener.handle(Event.error(e.getLocation(), e.getMessage()));
        }

        ConfiguredValueCreationException cvce = null;
        if (e.getCause() instanceof ConfiguredValueCreationException) {
          cvce = (ConfiguredValueCreationException) e.getCause();

          // Check if this is caused by an unresolved toolchain, and report it as such.
          if (unloadedToolchainContexts != null) {
            ImmutableSet<Label> requiredToolchains =
                unloadedToolchainContexts.getResolvedToolchains();
            ImmutableSet<Label> toolchainDependencyErrors =
                cvce.getRootCauses().toList().stream()
                    .map(Cause::getLabel)
                    .filter(requiredToolchains::contains)
                    .collect(toImmutableSet());

            if (!toolchainDependencyErrors.isEmpty()) {
              errorMessage = "errors encountered resolving toolchains for " + target.getLabel();
              listener.handle(Event.error(target.getLocation(), errorMessage));
            }
          }
        }

        yield new ReportedException(
            cvce != null
                ? cvce
                : new ConfiguredValueCreationException(
                    targetAndConfiguration.getTarget(),
                    configurationId(targetAndConfiguration.getConfiguration()),
                    errorMessage,
                    null,
                    e.getDetailedExitCode()));
      }
      case ConfiguredValueCreationException e -> {
        if (!e.getMessage().isEmpty()) {
          // Report the error to the user.
          listener.handle(Event.error(e.getLocation(), e.getMessage()));
        }
        yield new ReportedException(e);
      }
      case AspectCreationException e -> {
        if (!e.getMessage().isEmpty()) {
          // Report the error to the user.
          listener.handle(Event.error(null, e.getMessage()));
        }
        yield new ReportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration.getTarget(),
                configurationId(targetAndConfiguration.getConfiguration()),
                e.getMessage(),
                e.getCauses(),
                e.getDetailedExitCode()));
      }
      case ToolchainException e -> {
        ConfiguredValueCreationException cvce =
            e.asConfiguredValueCreationException(targetAndConfiguration);
        listener.handle(Event.error(target.getLocation(), cvce.getMessage()));
        yield new ReportedException(cvce);
      }
      case StarlarkExecTransitionLoadingException e -> {
        if (!e.getMessage().isEmpty()) {
          // Report the error to the user.
          listener.handle(Event.error(null, e.getMessage()));
        }
        yield new ReportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration.getTarget(),
                configurationId(targetAndConfiguration.getConfiguration()),
                e.getMessage(),
                /* rootCauses= */ null,
                /* detailedExitCode= */ null));
      }
      case ExecGroupCollection.InvalidExecGroupException e -> {
        listener.handle(Event.error(target.getLocation(), e.getMessage()));
        yield new ReportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration.getTarget(),
                configurationId(targetAndConfiguration.getConfiguration()),
                e.getMessage(),
                /* rootCauses= */ null,
                /* detailedExitCode= */ null));
      }
      default ->
          throw new IllegalStateException(
              "unexpected exception with no appropriate handler", untyped);
    };
  }

  /**
   * Computes the direct dependencies of a node in the configured target graph (a configured target
   * or an aspects).
   *
   * <p>Returns null if Skyframe hasn't evaluated the required dependencies yet. In this case, the
   * caller should also return null to Skyframe.
   *
   * <p>REQUIRES: {@code state.dependencyContext} is populated.
   *
   * @param state the compute state
   * @param configuredTargetKey key associated with {@code state.targetAndConfiguration}'s
   *     configuration
   * @param starlarkTransitionProvider the Starlark transition that implements exec transition
   *     logic, if specified. Null if Bazel uses native logic.
   * @param env the Skyframe environment
   * @param baseTargetPrerequisitesSupplier not null only in case of aspect evaluation. It provides
   *     a way to get the {@link ConfiguredTargetValue}s and {@link BuildConfigurationValue}s of the
   *     underlying target dependencies without creating a dependency edge from the aspect to them.
   * @param baseTargetUnloadedToolchainContexts not null only in case of aspect evaluation. It's the
   *     {@link UnloadedToolchainContext}s of the underlying target to support aspects toolchains
   *     propagation.
   */
  // TODO(b/213351014): Make the control flow of this helper function more readable. This will
  //   involve making a corresponding change to State to match the control flow.
  @Nullable
  public static OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> computeDependencies(
      State state,
      ConfiguredTargetKey configuredTargetKey,
      ImmutableList<Aspect> aspects,
      StarlarkTransitionCache transitionCache,
      @Nullable StarlarkAttributeTransitionProvider starlarkTransitionProvider,
      LookupEnvironment env,
      ExtendedEventHandler listener,
      @Nullable BaseTargetPrerequisitesSupplier baseTargetPrerequisitesSupplier,
      @Nullable ToolchainCollection<UnloadedToolchainContext> baseTargetUnloadedToolchainContexts)
      throws DependencyEvaluationException,
          ConfiguredValueCreationException,
          AspectCreationException,
          InterruptedException {
    // Replays stored events unless a Skyframe restart is immediately needed and the events would
    // be unused anyway.
    boolean shouldReplayStoredEvents = true;
    try {
      if (state.dependencyMap != null) {
        return state.dependencyMap;
      }
      if (state.dependencyMapProducer == null) {
        var ctgValue = state.targetAndConfiguration;
        DependencyContext dependencyContext = state.dependencyContext;
        ToolchainCollection<ToolchainContext> toolchainContexts =
            dependencyContext.toolchainContexts();
        DependencyResolutionHelpers.DependencyLabels dependencyLabels;
        try {
          dependencyLabels =
              DependencyResolutionHelpers.computeDependencyLabels(
                  ctgValue,
                  aspects,
                  dependencyContext.configConditions().asProviders(),
                  toolchainContexts,
                  baseTargetUnloadedToolchainContexts);
        } catch (DependencyResolutionHelpers.Failure e) {
          throw handleDependencyRootCauseError(ctgValue, e.getLocation(), e.getMessage(), listener);
        }
        state.dependencyMapProducer =
            new Driver(
                new DependencyMapProducer(
                    new PrerequisiteParameters(
                        configuredTargetKey,
                        ctgValue.getTarget(),
                        aspects,
                        starlarkTransitionProvider,
                        transitionCache,
                        toolchainContexts,
                        dependencyLabels.attributeMap(),
                        state.transitiveState,
                        state.storedEvents,
                        baseTargetPrerequisitesSupplier,
                        baseTargetUnloadedToolchainContexts),
                    dependencyLabels.labels(),
                    (DependencyMapProducer.ResultSink) state));
      }
      try {
        if (state.dependencyMapProducer.drive(env)) {
          state.dependencyMapProducer = null;
        }
      } catch (InterruptedException e) {
        // In practice, this comes from resolveConfigurations: other InterruptedExceptions are
        // declared for Skyframe value retrievals, which don't throw in reality.
        if (state.transitiveState.hasRootCause()) {
          // Allow caller to throw, don't prioritize interrupt: we may be error bubbling.
          Thread.currentThread().interrupt();
          return null;
        }
        throw e;
      }

      DependencyError error = state.dependencyMapError;
      if (error != null) {
        var ctgValue = state.targetAndConfiguration;
        switch (error.kind()) {
          case DEPENDENCY_TRANSITION:
            {
              TransitionException e = error.dependencyTransition();
              throw new ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage());
            }
          case DEPENDENCY_OPTIONS_PARSING:
            {
              OptionsParsingException e = error.dependencyOptionsParsing();
              throw new ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage());
            }
          case MATERIALIZER:
            {
              MaterializerException e = error.materializer();
              throw new ConfiguredValueCreationException(ctgValue.getTarget(), e.getMessage());
            }
          case INVALID_VISIBILITY:
            {
              InvalidVisibilityDependencyException e = error.invalidVisibility();
              throw handleDependencyRootCauseError(
                  ctgValue,
                  ctgValue.getTarget().getLocation(),
                  String.format("Label '%s' does not refer to a package group.", e.label()),
                  listener);
            }
          case ASPECT_EVALUATION:
            throw error.aspectEvaluation();
          case ASPECT_CREATION:
            throw error.aspectCreation();
          case PLATFORM_MAPPING:
            PlatformMappingException platformMappingException = error.platformMapping();
            throw new ConfiguredValueCreationException(
                ctgValue.getTarget(), platformMappingException.getMessage());
          case INVALID_PLATFORM:
            InvalidPlatformException invalidPlatformException = error.invalidPlatform();
            throw new ConfiguredValueCreationException(
                ctgValue.getTarget(), invalidPlatformException.getMessage());
          case TRANSITION_CREATION:
            TransitionCreationException transitionCreationException = error.transitionCreation();
            throw new ConfiguredValueCreationException(
                ctgValue.getTarget(), transitionCreationException.getMessage());
          case BUILD_OPTIONS_SCOPE:
            BuildOptionsScopeFunctionException buildOptionsScopeFunctionException =
                error.buildOptionsScope();
            throw new ConfiguredValueCreationException(
                ctgValue.getTarget(), buildOptionsScopeFunctionException.getMessage());
        }
      }
      if (!state.transitiveState.hasRootCause() && state.dependencyMap == null) {
        shouldReplayStoredEvents = false; // Skyframe restart is needed.
      }
      return state.dependencyMap;
    } finally {
      if (shouldReplayStoredEvents) {
        state.storedEvents.replayOn(listener);
      }
    }
  }

  public static UnloadedToolchainContextsInputs getUnloadedToolchainContextsInputs(
      TargetAndConfiguration targetAndConfiguration,
      @Nullable Label parentExecutionPlatformLabel,
      RuleClassProvider ruleClassProvider,
      ExtendedEventHandler listener)
      throws InterruptedException, ExecGroupCollection.InvalidExecGroupException {
    if (targetAndConfiguration.getConfiguration() == null) {
      return UnloadedToolchainContextsInputs.empty();
    }
    return ToolchainContextUtil.getUnloadedToolchainContextsInputs(
        targetAndConfiguration.getTarget(),
        targetAndConfiguration.getConfiguration().getOptions().get(CoreOptions.class),
        targetAndConfiguration.getConfiguration().getFragment(PlatformConfiguration.class),
        parentExecutionPlatformLabel,
        computeToolchainConfigurationKey(
            targetAndConfiguration.getConfiguration(),
            ((ConfiguredRuleClassProvider) ruleClassProvider)
                .getToolchainTaggedTrimmingTransition(),
            listener));
  }

  private static BuildConfigurationKey computeToolchainConfigurationKey(
      BuildConfigurationValue configuration,
      PatchTransition toolchainTaggedTrimmingTransition,
      ExtendedEventHandler listener)
      throws InterruptedException {
    // The toolchain context's options are the parent rule's options with manual trimming
    // auto-applied. This means toolchains don't inherit feature flags. This helps build
    // performance: if the toolchain context had the exact same configuration of its parent and that
    // included feature flags, all the toolchain's dependencies would apply this transition
    // individually. That creates a lot more potentially expensive applications of that transition
    // (especially since manual trimming applies to every configured target in the build).
    //
    // In other words: without this modification:
    // parent rule -> toolchain context -> toolchain
    //     -> toolchain dep 1 # applies manual trimming to remove feature flags
    //     -> toolchain dep 2 # applies manual trimming to remove feature flags
    //     ...
    //
    // With this modification:
    // parent rule -> toolchain context # applies manual trimming to remove feature flags
    //     -> toolchain
    //         -> toolchain dep 1
    //         -> toolchain dep 2
    //         ...
    //
    // None of this has any effect on rules that don't utilize manual trimming.
    BuildOptions toolchainOptions =
        toolchainTaggedTrimmingTransition.patch(
            new BuildOptionsView(
                configuration.getOptions(),
                toolchainTaggedTrimmingTransition.requiresOptionFragments()),
            listener);
    return BuildConfigurationKey.create(toolchainOptions);
  }

  private static DependencyEvaluationException handleDependencyRootCauseError(
      TargetAndConfiguration targetAndConfiguration,
      @Nullable Location location,
      String message,
      ExtendedEventHandler listener) {
    BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();
    Label label = targetAndConfiguration.getLabel();
    listener.post(AnalysisRootCauseEvent.withConfigurationValue(configuration, label, message));
    Cause cause =
        new AnalysisFailedCause(
            targetAndConfiguration.getLabel(),
            configurationIdMessage(targetAndConfiguration.getConfiguration()),
            createDetailedExitCode(message));
    return new DependencyEvaluationException(
        new ConfiguredValueCreationException(
            location,
            message,
            label,
            configurationId(configuration),
            NestedSetBuilder.create(Order.STABLE_ORDER, cause),
            cause.getDetailedExitCode()),
        // These errors occur in dependency resolution, which is attached to the current target.
        // i.e. no dependent ConfiguredTargetFunction call happens to report its own error.
        /* depReportedOwnError= */ false);
  }

  static DetailedExitCode getPrioritizedDetailedExitCode(NestedSet<Cause> causes) {
    DetailedExitCode prioritizedDetailedExitCode = null;
    for (Cause c : causes.toList()) {
      prioritizedDetailedExitCode =
          DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
              prioritizedDetailedExitCode, c.getDetailedExitCode());
    }
    return prioritizedDetailedExitCode;
  }
}
