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

package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.configurationIdMessage;
import static com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationProducer.createDetailedExitCode;
import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.producers.RuleTransitionApplier;
import com.google.devtools.build.lib.analysis.producers.RuleTransitionApplier.IdempotencyState;
import com.google.devtools.build.lib.analysis.producers.TargetAndConfigurationData;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.BuildTopLevelAspectsDetailsFunction.AspectDetails;
import com.google.devtools.build.lib.skyframe.BuildTopLevelAspectsDetailsFunction.BuildTopLevelAspectsDetailsKey;
import com.google.devtools.build.lib.skyframe.BuildTopLevelAspectsDetailsFunction.BuildTopLevelAspectsDetailsValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.DependencyException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetEvaluationExceptions.ReportedException;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.build.skyframe.state.Driver;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * SkyFunction to run the aspects path obtained from top-level aspects on the list of top-level
 * targets.
 *
 * <p>Used for loading top-level aspects. At top level, in {@link
 * com.google.devtools.build.lib.analysis.BuildView}, we cannot invoke two SkyFunctions one after
 * another, so BuildView calls this function to do the work.
 */
final class ToplevelStarlarkAspectFunction implements SkyFunction {
  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;
  private final boolean storeTransitivePackages;
  // Do not use this field for package retrieval of the base configured target since it will cause
  // incrementality errors because an essential dependency edge would not be registered.
  private final PrerequisitePackageFunction prerequisitePackages;

  ToplevelStarlarkAspectFunction(
      BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider,
      boolean storeTransitivePackages,
      PrerequisitePackageFunction prerequisitePackages) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
    this.storeTransitivePackages = storeTransitivePackages;
    this.prerequisitePackages = prerequisitePackages;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException,
          TopLevelStarlarkAspectFunctionException,
          DependencyException,
          ReportedException {
    TopLevelAspectsKey topLevelAspectsKey = (TopLevelAspectsKey) skyKey.argument();

    BuildTopLevelAspectsDetailsKey topLevelAspectsDetailsKey =
        BuildTopLevelAspectsDetailsKey.create(
            topLevelAspectsKey.getTopLevelAspectsClasses(),
            topLevelAspectsKey.getTopLevelAspectsParameters());
    ConfiguredTargetKey baseConfiguredTargetKey = topLevelAspectsKey.getBaseConfiguredTargetKey();

    SkyframeLookupResult initialLookupResult =
        env.getValuesAndExceptions(ImmutableList.of(topLevelAspectsDetailsKey));
    var topLevelAspectsDetails =
        (BuildTopLevelAspectsDetailsValue) initialLookupResult.get(topLevelAspectsDetailsKey);
    if (topLevelAspectsDetails == null) {
      return null; // some aspects details are not ready
    }

    // Configuration of top level target could change during the analysis phase with rule
    // transitions. In order not to wait for the complete configuration of the assigned target,
    // {@link RuleTransitionApplier} is used to apply potentially requested rule transitions
    // upfront. Configuration can be `null` if the target is not configurable, in which case the
    // Skyframe restart is needed.
    baseConfiguredTargetKey = getConfiguredTargetKey(baseConfiguredTargetKey, env);
    if (baseConfiguredTargetKey == null) {
      return null;
    }

    Collection<AspectKey> aspectsKeys =
        getTopLevelAspectsKeys(topLevelAspectsDetails.getAspectsDetails(), baseConfiguredTargetKey);

    SkyframeLookupResult result = env.getValuesAndExceptions(aspectsKeys);
    if (env.valuesMissing()) {
      return null; // some aspects keys are not evaluated
    }
    ImmutableMap.Builder<AspectKey, AspectValue> valuesMap =
        ImmutableMap.builderWithExpectedSize(aspectsKeys.size());
    for (AspectKey aspectKey : aspectsKeys) {
      try {
        AspectValue value =
            (AspectValue) result.getOrThrow(aspectKey, ActionConflictException.class);
        if (value == null) {
          return null;
        }
        valuesMap.put(aspectKey, value);
      } catch (ActionConflictException e) {
        // Required in case of skymeld: the AspectKey isn't accessible from the BuildDriverKey.
        throw new TopLevelStarlarkAspectFunctionException(
            ActionConflictException.withAspectKeyInfo(e, aspectKey));
      }
    }
    return new TopLevelAspectsValue(valuesMap.buildOrThrow());
  }

  private static Collection<AspectKey> getTopLevelAspectsKeys(
      ImmutableList<AspectDetails> aspectsDetails, ConfiguredTargetKey topLevelTargetKey) {
    Map<AspectDescriptor, AspectKey> result = new HashMap<>();
    for (AspectDetails aspect : aspectsDetails) {
      buildAspectKey(aspect, result, topLevelTargetKey);
    }
    return result.values();
  }

  private static AspectKey buildAspectKey(
      AspectDetails aspect,
      Map<AspectDescriptor, AspectKey> result,
      ConfiguredTargetKey topLevelTargetKey) {
    if (result.containsKey(aspect.getAspectDescriptor())) {
      return result.get(aspect.getAspectDescriptor());
    }

    ImmutableList.Builder<AspectKey> dependentAspects = ImmutableList.builder();
    for (AspectDetails depAspect : aspect.getUsedAspects()) {
      dependentAspects.add(buildAspectKey(depAspect, result, topLevelTargetKey));
    }

    AspectKey aspectKey =
        AspectKeyCreator.createAspectKey(
            aspect.getAspectDescriptor(), dependentAspects.build(), topLevelTargetKey);
    result.put(aspectKey.getAspectDescriptor(), aspectKey);
    return aspectKey;
  }

  /**
   * Skyframe lookup for the package and, if it is ready, get the target from it. Otherwise, return
   * `null` since Skyframe restart is needed.
   */
  @Nullable
  private Target getTarget(ConfiguredTargetKey configuredTargetKey, Environment env)
      throws InterruptedException, NoSuchTargetException {
    PackageIdentifier packageIdentifier = configuredTargetKey.getLabel().getPackageIdentifier();
    SkyframeLookupResult packageLookupResult =
        env.getValuesAndExceptions(ImmutableList.of(packageIdentifier));
    var packageValue = (PackageValue) packageLookupResult.get(packageIdentifier);
    if (packageValue == null) {
      // Skyframe restart is needed since package is still not ready.
      return null;
    }
    Package pkg = packageValue.getPackage();
    return pkg.getTarget(configuredTargetKey.getLabel().getName());
  }

  /**
   * Returns {@code `baseConfiguredTargetKey`} if the configuration didn't change with potential
   * transitions ({@link IdempotencyState#IDENTITY}). Otherwise, returns a new {@link
   * ConfiguredTargetKey} with the new configuration ({@code `buildConfigurationKey`}).
   */
  @Nullable
  private ConfiguredTargetKey createConfiguredTargetKey(
      BuildConfigurationKey buildConfigurationKey,
      ConfiguredTargetKey baseConfiguredTargetKey,
      IdempotencyState idempotencyState) {
    if (idempotencyState == IdempotencyState.IDENTITY) {
      return baseConfiguredTargetKey;
    }
    ConfiguredTargetKey.Builder keyBuilder =
        ConfiguredTargetKey.builder()
            .setLabel(baseConfiguredTargetKey.getLabel())
            .setConfigurationKey(buildConfigurationKey);

    if (idempotencyState == IdempotencyState.NON_IDEMPOTENT) {
      // The transition was not idempotent. Explicitly informs the delegate to avoid applying a
      // rule transition.
      keyBuilder.setShouldApplyRuleTransition(false);
    }
    return keyBuilder.build();
  }

  /**
   * Computes configuration of the target by driving the state machine of {@link
   * RuleTransitionApplier}.
   */
  public void computeConfiguration(
      Environment env,
      State state,
      ConfiguredTargetKey baseConfiguredTargetKey,
      Target target,
      ConfiguredRuleClassProvider ruleClassProvider,
      BuildViewProvider buildViewProvider)
      throws InterruptedException {
    if (state.myProducer == null) {
      state.myProducer =
          new Driver(
              new TransitionedBaseConfigurationProducer(
                  baseConfiguredTargetKey,
                  ruleClassProvider.getTrimmingTransitionFactory(),
                  ruleClassProvider.getToolchainTaggedTrimmingTransition(),
                  buildViewProvider.getSkyframeBuildView().getStarlarkTransitionCache(),
                  target,
                  state));
    }
    if (state.myProducer.drive(env)) {
      state.myProducer = null;
    }
  }


  // Computes {@link BuildConfigurationKey} by driving the state machine of {@link
  // RuleTransitionApplier} and returns the new {@link ConfiguredTargetKey} with the obtained build
  // configuration. In case configuration key is still not ready, returns `null` since Skyframe
  // restart is needed.
  @Nullable
  private ConfiguredTargetKey getConfiguredTargetKey(
      ConfiguredTargetKey baseConfiguredTargetKey, Environment env)
      throws InterruptedException, AssertionError, DependencyException, ReportedException {
    Target target;
    try {
      // TODO(kotlaja): Move this logic into the state machine.
      target = getTarget(baseConfiguredTargetKey, env);
      if (target == null) {
        return null;
      }
    } catch (NoSuchTargetException e) {
      throw new DependencyException(e);
    }
    if (!target.isConfigurable()) {
      return baseConfiguredTargetKey.toBuilder().setConfigurationKey(null).build();
    }

    State state = env.getState(() -> new State(storeTransitivePackages, prerequisitePackages));
    computeConfiguration(
        env,
        state,
        baseConfiguredTargetKey,
        target,
        (ConfiguredRuleClassProvider) ruleClassProvider,
        buildViewProvider);

    if (state.hasError()) {
      ConfiguredValueCreationException exception =
          state.createException(baseConfiguredTargetKey, target);
      if (!exception.getMessage().isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(exception.getLocation(), exception.getMessage()));
      }
      throw new ReportedException(exception);
    }

    if (state.configurationKey == null) {
      // Skyframe restart is needed since configuration is still not ready.
      return null;
    }
    return createConfiguredTargetKey(
        state.configurationKey, baseConfiguredTargetKey, state.idempotencyState);
  }

  private static class TopLevelStarlarkAspectFunctionException extends SkyFunctionException {
    protected TopLevelStarlarkAspectFunctionException(ActionConflictException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }

  /**
   * {@link StateMachine} which drives {@link RuleTransitionApplier} to apply potentially requested
   * rule transitions and accepts the configuration key in {@link State}.
   */
  private static class TransitionedBaseConfigurationProducer
      implements StateMachine, TargetAndConfigurationData {
    ConfiguredTargetKey preRuleTransitionKey;
    TransitionFactory<RuleTransitionData> trimmingTransitionFactory;
    PatchTransition toolchainTaggedTrimmingTransition;
    StarlarkTransitionCache transitionCache;
    Target target;
    State state;

    TransitionedBaseConfigurationProducer(
        ConfiguredTargetKey preRuleTransitionKey,
        TransitionFactory<RuleTransitionData> trimmingTransitionFactory,
        PatchTransition toolchainTaggedTrimmingTransition,
        StarlarkTransitionCache transitionCache,
        Target target,
        State state) {
      this.preRuleTransitionKey = preRuleTransitionKey;
      this.trimmingTransitionFactory = trimmingTransitionFactory;
      this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition;
      this.transitionCache = transitionCache;
      this.target = target;
      this.state = state;
    }

    @Override
    public StateMachine step(Tasks tasks) {
      return new RuleTransitionApplier(
          target,
          (TargetAndConfigurationData) this,
          (RuleTransitionApplier.ResultSink) state,
          state.storedEvents,
          /* runAfter= */ DONE);
    }

    @Override
    public ConfiguredTargetKey getPreRuleTransitionKey() {
      return preRuleTransitionKey;
    }

    @Override
    public TransitionFactory<RuleTransitionData> getTrimmingTransitionFactory() {
      return trimmingTransitionFactory;
    }

    @Override
    public PatchTransition getToolchainTaggedTrimmingTransition() {
      return toolchainTaggedTrimmingTransition;
    }

    @Override
    public StarlarkTransitionCache getTransitionCache() {
      return transitionCache;
    }

    @Override
    public TransitiveDependencyState getTransitiveState() {
      return state.transitiveState;
    }
  }

  /**
   * State which drives a {@link TransitionedBaseConfigurationProducer} and accepts the
   * configuration when complete.
   */
  public static class State implements SkyKeyComputeState, RuleTransitionApplier.ResultSink {
    @Nullable // Non-null while in-flight.
    private Driver myProducer;
    private final TransitiveDependencyState transitiveState;
    private final StoredEventHandler storedEvents;

    // --------------- Configuration fields ------------------
    private BuildConfigurationKey configurationKey;
    private IdempotencyState idempotencyState;

    // --------------- Error handling fields ------------------
    @Nullable private String message = null;
    @Nullable private Location location = null;
    @Nullable private DetailedExitCode exitCode = null;

    State(boolean storeTransitivePackages, PrerequisitePackageFunction prerequisitePackages) {
      this.transitiveState =
          new TransitiveDependencyState(storeTransitivePackages, prerequisitePackages);
      this.storedEvents = new StoredEventHandler();
    }

    /**
     * Implementation of {@link RuleTransitionApplier.ResultSink}, where accepting the configuration
     * and idempotency state is needed to compute {@link ConfiguredTargetKey}.
     */
    @Override
    public void acceptConfiguration(
        BuildConfigurationKey configurationKey, IdempotencyState idempotencyState) {
      this.configurationKey = configurationKey;
      this.idempotencyState = idempotencyState;
    }

    /**
     * Implementation of {@link RuleTransitionApplier.ResultSink}, where accepting the error message
     * is needed to throw {@link ReportedException}.
     */
    @Override
    public void acceptErrorMessage(
        String message, @Nullable Location location, @Nullable DetailedExitCode exitCode) {
      this.message = message;
      this.location = location;
      this.exitCode = exitCode;
    }

    public boolean hasError() {
      return this.message != null || this.location != null || this.exitCode != null;
    }

    /**
     * Handles an exception thrown during the rule transition application in {@link
     * RuleTransitionApplier}
     */
    public ConfiguredValueCreationException createException(
        ConfiguredTargetKey baseConfiguredTargetKey, Target target) {
      Cause cause =
          new AnalysisFailedCause(
              baseConfiguredTargetKey.getLabel(),
              configurationIdMessage(
                  baseConfiguredTargetKey.getConfigurationKey().getOptionsChecksum()),
              exitCode != null ? exitCode : createDetailedExitCode(message));
      return new ConfiguredValueCreationException(
          location,
          message,
          target.getLabel(),
          configurationId(baseConfiguredTargetKey.getConfigurationKey()),
          NestedSetBuilder.create(Order.STABLE_ORDER, cause),
          exitCode != null ? exitCode : createDetailedExitCode(message));
    }
  }
}
