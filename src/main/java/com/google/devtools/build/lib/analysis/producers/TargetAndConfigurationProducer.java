// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import static com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationId;

import com.google.auto.value.AutoOneOf;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.InconsistentNullConfigException;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.producers.RuleTransitionApplier.IdempotencyState;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId.ConfigurationId;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Computes the target and configuration for a configured target key.
 *
 * <p>If the key has a configuration and the target is configurable, attempts to apply a rule side
 * transition. If the configuration changes, delegates to a target with the new configuration. If
 * the target is not configurable, directly delegates to the null configuration.
 */
public final class TargetAndConfigurationProducer
    implements TargetAndConfigurationData,
        StateMachine,
        StateMachine.ValueOrExceptionSink<InvalidConfigurationException>,
        Consumer<SkyValue>,
        TargetProducer.ResultSink,
        RuleTransitionApplier.ResultSink {

  /** Accepts results of this producer. */
  public interface ResultSink {
    void acceptTargetAndConfiguration(TargetAndConfiguration value, ConfiguredTargetKey fullKey);

    void acceptTargetAndConfigurationDelegatedValue(ConfiguredTargetValue value);

    void acceptTargetAndConfigurationError(TargetAndConfigurationError error);
  }

  /** Tagged union of possible errors. */
  @AutoOneOf(TargetAndConfigurationError.Kind.class)
  public abstract static class TargetAndConfigurationError {
    /** Tags the error type. */
    public enum Kind {
      CONFIGURED_VALUE_CREATION,
      NO_SUCH_THING,
      INCONSISTENT_NULL_CONFIG
    }

    public abstract Kind kind();

    public abstract ConfiguredValueCreationException configuredValueCreation();

    public abstract NoSuchThingException noSuchThing();

    public abstract InconsistentNullConfigException inconsistentNullConfig();

    private static TargetAndConfigurationError of(ConfiguredValueCreationException e) {
      return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
          .configuredValueCreation(e);
    }

    private static TargetAndConfigurationError of(NoSuchThingException e) {
      return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError.noSuchThing(e);
    }

    private static TargetAndConfigurationError of(InconsistentNullConfigException e) {
      return AutoOneOf_TargetAndConfigurationProducer_TargetAndConfigurationError
          .inconsistentNullConfig(e);
    }
  }

  // -------------------- Input --------------------
  private final ConfiguredTargetKey preRuleTransitionKey;
  @Nullable private final TransitionFactory<RuleTransitionData> trimmingTransitionFactory;
  private final PatchTransition toolchainTaggedTrimmingTransition;
  private final StarlarkTransitionCache transitionCache;

  private final TransitiveDependencyState transitiveState;

  // -------------------- Output --------------------
  private final ResultSink sink;
  private final ExtendedEventHandler eventHandler;

  // -------------------- Internal State --------------------
  private Target target;
  private BuildConfigurationKey configurationKey;
  private IdempotencyState idempotencyState;

  public TargetAndConfigurationProducer(
      ConfiguredTargetKey preRuleTransitionKey,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory,
      PatchTransition toolchainTaggedTrimmingTransition,
      StarlarkTransitionCache transitionCache,
      TransitiveDependencyState transitiveState,
      ResultSink sink,
      ExtendedEventHandler eventHandler) {
    this.preRuleTransitionKey = preRuleTransitionKey;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.toolchainTaggedTrimmingTransition = toolchainTaggedTrimmingTransition;
    this.transitionCache = transitionCache;
    this.transitiveState = transitiveState;
    this.sink = sink;
    this.eventHandler = eventHandler;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    return new TargetProducer(
        preRuleTransitionKey.getLabel(),
        transitiveState,
        (TargetProducer.ResultSink) this,
        /* runAfter= */ this::determineConfiguration);
  }

  @Override
  public void acceptTarget(Target target) {
    this.target = target;
  }

  @Override
  public void acceptTargetError(NoSuchPackageException e) {
    eventHandler.handle(Event.error(e.getMessage()));
    sink.acceptTargetAndConfigurationError(TargetAndConfigurationError.of(e));
  }

  @Override
  public void acceptTargetError(NoSuchTargetException e, Location location) {
    eventHandler.handle(Event.error(location, e.getMessage()));
    sink.acceptTargetAndConfigurationError(TargetAndConfigurationError.of(e));
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
    return transitiveState;
  }

  private StateMachine determineConfiguration(Tasks tasks) {
    if (target == null) {
      return DONE; // A target could not be determined.
    }

    BuildConfigurationKey configurationKey = preRuleTransitionKey.getConfigurationKey();
    if (configurationKey == null) {
      if (target.isConfigurable()) {
        // We somehow ended up in a target that requires a non-null configuration but with a key
        // that doesn't have a configuration. This is always an error, but we need to bubble this
        // up to the parent to provide more context.
        sink.acceptTargetAndConfigurationError(
            TargetAndConfigurationError.of(new InconsistentNullConfigException()));
        return DONE;
      }
      sink.acceptTargetAndConfiguration(
          new TargetAndConfiguration(target, /* configuration= */ null), preRuleTransitionKey);
      return DONE;
    }

    if (!target.isConfigurable()) {
      // If target is not configurable, but requested with a configuration. Delegates to a key with
      // the null configuration. This is expected to be uncommon. The common case of a
      // non-configurable target is an input file, but those are usually package local and requested
      // correctly with the null configuration.
      delegateTo(
          tasks,
          ConfiguredTargetKey.builder()
              .setLabel(preRuleTransitionKey.getLabel())
              .setExecutionPlatformLabel(preRuleTransitionKey.getExecutionPlatformLabel())
              .build());
      return DONE;
    }

    if (!preRuleTransitionKey.shouldApplyRuleTransition()) {
      lookUpConfigurationValue(tasks);
      return DONE;
    }

    return new RuleTransitionApplier(
        target,
        (TargetAndConfigurationData) this,
        (RuleTransitionApplier.ResultSink) this,
        eventHandler,
        /* runAfter= */ this::computeTargetAndConfigurationOrDelegatedValue);
  }

  private void delegateTo(Tasks tasks, ActionLookupKey delegate) {
    tasks.lookUp(delegate, (Consumer<SkyValue>) this);
  }

  @Override
  public void accept(SkyValue value) {
    sink.acceptTargetAndConfigurationDelegatedValue((ConfiguredTargetValue) value);
  }

  private void lookUpConfigurationValue(Tasks tasks) {
    tasks.lookUp(
        preRuleTransitionKey.getConfigurationKey(),
        InvalidConfigurationException.class,
        (ValueOrExceptionSink<InvalidConfigurationException>) this);
  }

  @Override
  public void acceptValueOrException(
      @Nullable SkyValue value, @Nullable InvalidConfigurationException error) {
    if (value != null) {
      sink.acceptTargetAndConfiguration(
          new TargetAndConfiguration(target, (BuildConfigurationValue) value),
          preRuleTransitionKey);
      return;
    }
    emitError(
        error.getMessage(), TargetUtils.getLocationMaybe(target), error.getDetailedExitCode());
  }

  /**
   * Implementation of {@link RuleTransitionApplier.ResultSink}, where accepting the configuration
   * and idempotency state is needed to compute target and configuration or to delegate (see {@link
   * TargetAndConfigurationProducer#computeTargetAndConfigurationOrDelegatedValue}).
   */
  @Override
  public void acceptConfiguration(
      BuildConfigurationKey configurationKey, IdempotencyState idempotencyState) {
    this.configurationKey = configurationKey;
    this.idempotencyState = idempotencyState;
  }

  @Override
  public void acceptErrorMessage(
      String message, Location location, @Nullable DetailedExitCode exitCode) {
    emitError(message, location, exitCode);
  }

  public StateMachine computeTargetAndConfigurationOrDelegatedValue(Tasks tasks) {
    if (idempotencyState == IdempotencyState.IDENTITY) {
      tasks.lookUp(
          configurationKey,
          InvalidConfigurationException.class,
          (ValueOrExceptionSink<InvalidConfigurationException>) this);
      return DONE;
    }

    ConfiguredTargetKey.Builder keyBuilder =
        ConfiguredTargetKey.builder()
            .setLabel(preRuleTransitionKey.getLabel())
            .setExecutionPlatformLabel(preRuleTransitionKey.getExecutionPlatformLabel())
            .setConfigurationKey(configurationKey);

    if (idempotencyState == IdempotencyState.NON_IDEMPOTENT) {
      // The transition was not idempotent. Explicitly informs the delegate to avoid applying a
      // rule transition.
      keyBuilder.setShouldApplyRuleTransition(false);
    }

    tasks.lookUp(keyBuilder.build(), (Consumer<SkyValue>) this);
    return DONE;
  }

  private void emitError(
      String message, @Nullable Location location, @Nullable DetailedExitCode exitCode) {
    Cause cause =
        new AnalysisFailedCause(
            preRuleTransitionKey.getLabel(),
            configurationIdMessage(preRuleTransitionKey.getConfigurationKey().getOptionsChecksum()),
            exitCode != null ? exitCode : createDetailedExitCode(message));
    sink.acceptTargetAndConfigurationError(
        TargetAndConfigurationError.of(
            new ConfiguredValueCreationException(
                location,
                message,
                target.getLabel(),
                configurationId(preRuleTransitionKey.getConfigurationKey()),
                NestedSetBuilder.create(Order.STABLE_ORDER, cause),
                exitCode != null ? exitCode : createDetailedExitCode(message))));
  }

  public static ConfigurationId configurationIdMessage(@Nullable String optionsCheckSum) {
    if (optionsCheckSum == null) {
      return ConfigurationId.newBuilder().setId("none").build();
    }
    return ConfigurationId.newBuilder().setId(optionsCheckSum).build();
  }

  public static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setAnalysis(
                Analysis.newBuilder().setCode(Analysis.Code.CONFIGURED_VALUE_CREATION_FAILED))
            .build());
  }

}
