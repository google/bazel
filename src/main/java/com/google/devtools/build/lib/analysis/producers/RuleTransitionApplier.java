// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.ConfigurationTransitionEvent;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformValue;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextUtil;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** Applies any requested rule transition before producing the final configuration. */
public class RuleTransitionApplier
    implements StateMachine,
        TransitionApplier.ResultSink,
        ConfigConditionsProducer.ResultSink,
        PlatformProducer.ResultSink {
  /** Interface for accepting values produced by this class. */
  public interface ResultSink {
    void acceptConfiguration(
        BuildConfigurationKey configurationKey, IdempotencyState idempotencyState);

    void acceptErrorMessage(
        String message, @Nullable Location location, @Nullable DetailedExitCode exitCode);
  }

  /**
   * Classifies a transition based on its behavior (no-op, idempotent, or non-idempotent).
   *
   * <p>During analysis, when a rule transition modifies the configuration, it triggers a new
   * evaluation of the target with the new configuration. This re-evaluation can inadvertently lead
   * to the rule transition being applied twice. To prevent this, the {@link ConfiguredTargetKey}
   * includes a {@link ConfiguredTargetKey#shouldApplyRuleTransition} flag. Setting this flag allows
   * skipping the rule transition during re-evaluation. This flag should be set false when the
   * result is {@link IdempotencyState#NON_IDEMPOTENT}.
   *
   * <p>At first glance, it seems like setting `shouldApplyRuleTransition=false` should be benign
   * for ({@link IdempotencyState#IDEMPOTENT} and {@link IdempotencyState#NON_IDEMPOTENT}), but it
   * would be an error in the idempotent case.
   *
   * <p>Idempotent Case
   *
   * <p>If we were to mark the idempotent case with `shouldApplyRuleTransition=false`, it would lead
   * to action conflicts. Let `//foo[123]` be a key that rule transitions to `//foo[abc]` and
   * suppose the outcome is marked `//foo[abc] shouldApplyRuleTransition=false`.
   *
   * <p>A different parent might directly request `//foo[abc] shouldApplyRuleTransition=true`. Since
   * the rule transition is a idempotent, it would result in the same actions as `//foo[abc]
   * shouldApplyRuleTransition=false` with a different key, causing action conflicts.
   *
   * <p>Non-idempotent Case
   *
   * <p>If the transition is non-idempotent, marks {@link
   * ConfiguredTargetKey#shouldApplyRuleTransition} false in the delegate key.
   *
   * <p>In the example of //foo[abc] shouldApplyRuleTransition=false and //foo[abc]
   * shouldApplyRuleTransition=true, there should be no action conflicts because the
   * `shouldApplyRuleTransition=false` is the result of a non-idempotent rule transition and
   * `shouldApplyRuleTransition=true` will produce a different configuration.
   */
  public enum IdempotencyState {
    /** The transition was a no-op. */
    IDENTITY,
    /** The rule transition is idempotent. */
    IDEMPOTENT,
    /** The rule transition is non-idempotent. */
    NON_IDEMPOTENT
  }

  // -------------------- Input --------------------
  private final Target target;
  private final TargetAndConfigurationData targetAndConfigurationData;

  // -------------------- Output --------------------
  private final ResultSink sink;
  private final ExtendedEventHandler eventHandler;

  // -------------------- Sequencing --------------------
  private final StateMachine runAfter;

  // -------------------- Internal State --------------------
  @Nullable private PlatformInfo platformInfo;
  private ConfigConditions configConditions;
  private ConfigurationTransition ruleTransition;
  private BuildConfigurationKey configurationKey;

  public RuleTransitionApplier(
      Target target,
      TargetAndConfigurationData targetAndConfigurationData,
      RuleTransitionApplier.ResultSink sink,
      ExtendedEventHandler eventHandler,
      StateMachine runAfter) {
    this.target = target;
    this.targetAndConfigurationData = targetAndConfigurationData;
    this.sink = sink;
    this.eventHandler = eventHandler;
    this.runAfter = runAfter;
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    UnloadedToolchainContextsInputs unloadedToolchainContextsInputs;
    PlatformConfiguration platformConfiguration = null;
    ConfiguredTargetKey preRuleTransitionKey = targetAndConfigurationData.getPreRuleTransitionKey();
    var platformOptions =
        preRuleTransitionKey.getConfigurationKey().getOptions().get(PlatformOptions.class);
    if (platformOptions == null) {
      unloadedToolchainContextsInputs = UnloadedToolchainContextsInputs.empty();
    } else {
      platformConfiguration = new PlatformConfiguration(platformOptions);
      try {
        unloadedToolchainContextsInputs =
            ToolchainContextUtil.getUnloadedToolchainContextsInputs(
                target,
                preRuleTransitionKey.getConfigurationKey().getOptions().get(CoreOptions.class),
                platformConfiguration,
                preRuleTransitionKey.getExecutionPlatformLabel(),
                computeToolchainConfigurationKey(
                    preRuleTransitionKey.getConfigurationKey().getOptions(),
                    targetAndConfigurationData.getToolchainTaggedTrimmingTransition()));
      } catch (ExecGroupCollection.InvalidExecGroupException e) {
        emitErrorMessage(e.getMessage());
        return runAfter;
      }
    }

    if (unloadedToolchainContextsInputs.targetToolchainContextKey() != null) {
      tasks.enqueue(
          new PlatformProducer(
              platformConfiguration.getTargetPlatform(),
              (PlatformProducer.ResultSink) this,
              this::computeConfigConditions));
    } else {
      this.platformInfo = null;
      computeConfigConditions(tasks);
    }

    return runAfter;
  }

  private BuildConfigurationKey computeToolchainConfigurationKey(
      BuildOptions buildOptions, PatchTransition toolchainTaggedTrimmingTransition)
      throws InterruptedException {
    // The toolchain context's options are the parent rule's options with manual trimming
    // auto-applied. This means toolchains don't inherit feature flags. This helps build
    // performance: if the toolchain context had the exact same configuration of its parent and
    // that
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
                buildOptions, toolchainTaggedTrimmingTransition.requiresOptionFragments()),
            eventHandler);
    return BuildConfigurationKey.create(toolchainOptions);
  }

  @CanIgnoreReturnValue
  public StateMachine computeConfigConditions(Tasks tasks) {
    ConfiguredTargetKey preRuleTransitionKey = targetAndConfigurationData.getPreRuleTransitionKey();
    // TODO @aranguyen b/297077082
    tasks.enqueue(
        new ConfigConditionsProducer(
            target,
            preRuleTransitionKey.getLabel(),
            preRuleTransitionKey.getConfigurationKey(),
            platformInfo,
            targetAndConfigurationData.getTransitiveState(),
            (ConfigConditionsProducer.ResultSink) this,
            this::computeTransition));
    return DONE;
  }

  @Override
  public void acceptConfigConditions(ConfigConditions configConditions) {
    this.configConditions = configConditions;
  }

  @Override
  public void acceptConfigConditionsError(ConfiguredValueCreationException e) {
    emitErrorMessage(e.getMessage());
  }

  // Keep in sync with CqueryTransitionResolver.getRuleTransition.
  public StateMachine computeTransition(Tasks tasks) {
    if (configConditions == null) {
      return DONE;
    }
    TransitionFactory<RuleTransitionData> transitionFactory =
        target.getAssociatedRule().getRuleClassObject().getTransitionFactory();
    TransitionFactory<RuleTransitionData> trimmingTransitionFactory =
        targetAndConfigurationData.getTrimmingTransitionFactory();
    if (trimmingTransitionFactory != null) {
      transitionFactory =
          ComposingTransitionFactory.of(transitionFactory, trimmingTransitionFactory);
    }
    ConfiguredTargetKey preRuleTransitionKey = targetAndConfigurationData.getPreRuleTransitionKey();
    var transitionData =
        RuleTransitionData.create(
            target.getAssociatedRule(),
            configConditions.asProviders(),
            preRuleTransitionKey.getConfigurationKey().getOptionsChecksum());
    ConfigurationTransition transition = transitionFactory.create(transitionData);
    this.ruleTransition = transition;
    return new TransitionApplier(
        target.getLabel(),
        preRuleTransitionKey.getConfigurationKey(),
        ruleTransition,
        targetAndConfigurationData.getTransitionCache(),
        (TransitionApplier.ResultSink) this,
        eventHandler,
        /* runAfter= */ this::processTransitionedKey);
  }

  @Override
  public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
    emitErrorMessage(e.getMessage());
  }

  @Override
  public void acceptTransitionedConfigurations(
      ImmutableMap<String, BuildConfigurationKey> transitionResult) {
    checkState(transitionResult.size() == 1, "Expected exactly one result: %s", transitionResult);
    this.configurationKey =
        checkNotNull(
            transitionResult.get(ConfigurationTransition.PATCH_TRANSITION_KEY),
            "Transition result missing patch transition entry: %s",
            transitionResult);
  }

  @Override
  public void acceptTransitionError(TransitionException e) {
    emitErrorMessage(e.getMessage());
  }

  @Override
  public void acceptOptionsParsingError(OptionsParsingException e) {
    emitErrorMessage(e.getMessage());
  }

  @Override
  public void acceptPlatformMappingError(PlatformMappingException e) {
    emitErrorMessage(e.getMessage());
  }

  @Override
  public void acceptPlatformFlagsError(InvalidPlatformException e) {
    emitErrorMessage(e.getMessage());
  }

  @Override
  public void acceptPlatformValue(PlatformValue value) {
    this.platformInfo = value.platformInfo();
  }

  @Override
  public void acceptPlatformInfoError(InvalidPlatformException error) {
    emitErrorMessage(error.getMessage());
  }

  private StateMachine processTransitionedKey(Tasks tasks) {
    if (configurationKey == null) {
      return DONE; // There was an error.
    }
    BuildConfigurationKey parentConfiguration =
        targetAndConfigurationData.getPreRuleTransitionKey().getConfigurationKey();
    if (configurationKey.equals(parentConfiguration)) {
      // This key owns the configuration and the computation completes normally.
      sink.acceptConfiguration(configurationKey, IdempotencyState.IDENTITY);
      return DONE;
    }
    eventHandler.post(
        ConfigurationTransitionEvent.create(
            parentConfiguration.getOptionsChecksum(), configurationKey.getOptionsChecksum()));
    return new IdempotencyChecker();
  }

  /** Checks of transition is idempotent and accepts the configuration accordingly. */
  private class IdempotencyChecker implements StateMachine, TransitionApplier.ResultSink {
    // -------------------- Internal State --------------------
    private BuildConfigurationKey configurationKey2;

    @Override
    public StateMachine step(Tasks tasks) {
      return new TransitionApplier(
          target.getLabel(),
          configurationKey,
          ruleTransition,
          targetAndConfigurationData.getTransitionCache(),
          (TransitionApplier.ResultSink) this,
          eventHandler,
          /* runAfter= */ DONE);
    }

    @Override
    public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
      emitErrorMessage(e.getMessage());
    }

    @Override
    public void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionResult) {
      checkState(transitionResult.size() == 1, "Expected exactly one result: %s", transitionResult);
      this.configurationKey2 =
          checkNotNull(
              transitionResult.get(ConfigurationTransition.PATCH_TRANSITION_KEY),
              "Transition result missing patch transition entry: %s",
              transitionResult);

      IdempotencyState idempotencyState =
          configurationKey.equals(configurationKey2)
              ? IdempotencyState.IDEMPOTENT
              : IdempotencyState.NON_IDEMPOTENT;
      sink.acceptConfiguration(configurationKey, idempotencyState);
    }

    @Override
    public void acceptTransitionError(TransitionException e) {
      emitErrorMessage(e.getMessage());
    }

    @Override
    public void acceptOptionsParsingError(OptionsParsingException e) {
      emitErrorMessage(e.getMessage());
    }

    @Override
    public void acceptPlatformMappingError(PlatformMappingException e) {
      emitErrorMessage(e.getMessage());
    }

    @Override
    public void acceptPlatformFlagsError(InvalidPlatformException e) {
      emitErrorMessage(e.getMessage());
    }
  }

  private void emitErrorMessage(String message) {
    sink.acceptErrorMessage(message, TargetUtils.getLocationMaybe(target), /* exitCode= */ null);
  }
}
