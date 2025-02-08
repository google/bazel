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
package com.google.devtools.build.lib.analysis.constraints;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.TransitiveDependencyState;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.test.TestActionBuilder;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper.ValidationException;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassId;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Helpers for creating configured targets that are incompatible.
 *
 * <p>A target is considered incompatible if any of the following applies:
 *
 * <ol>
 *   <li>The target's <code>target_compatible_with</code> attribute specifies a constraint that is
 *       not present in the target platform. The target is said to be "directly incompatible".
 *   <li>One or more of the target's dependencies is incompatible. The target is said to be
 *       "indirectly incompatible."
 * </ol>
 *
 * The intent of these helpers is that they get called as early in the analysis phase as possible.
 * That's why there are two helpers instead of just one. The first helper determines direct
 * incompatibility very early in the analysis phase. If a target is not directly incompatible, the
 * dependencies need to be analysed and then we can check for indirect incompatibility. Doing these
 * checks as early as possible allows us to skip analysing unused dependencies and ignore unused
 * toolchains.
 *
 * <p>See https://bazel.build/docs/platforms#skipping-incompatible-targets for more information on
 * incompatible target skipping.
 */
public class IncompatibleTargetChecker {
  /**
   * Creates an incompatible configured target if it is "directly incompatible".
   *
   * <p>In other words, this state machine checks if a target is incompatible because of its
   * "target_compatible_with" attribute.
   *
   * <p>Outputs an {@code Optional} {@link RuleConfiguredTargetValue} as follows.
   *
   * <ul>
   *   <li>{@code Optional.empty()}: The target is not directly incompatible. Analysis can continue.
   *   <li>{@code !Optional.empty()}: The target is directly incompatible. Analysis should not
   *       continue.
   * </ul>
   */
  public static class IncompatibleTargetProducer implements StateMachine, Consumer<SkyValue> {
    private final TargetAndConfiguration targetAndConfiguration;
    private final ConfiguredTargetKey configuredTargetKey;
    private final ConfigConditions configConditions;
    // Non-null when the target has an associated rule and does not opt out of toolchain resolution.
    @Nullable private final PlatformInfo platformInfo;
    private final TransitiveDependencyState transitiveState;

    private final ResultSink sink;

    private final StateMachine runAfter;

    private final ImmutableList.Builder<ConstraintValueInfo> invalidConstraintValuesBuilder =
        new ImmutableList.Builder<>();

    /** Sink for the output of this state machine. */
    public interface ResultSink {
      void acceptIncompatibleTarget(Optional<RuleConfiguredTargetValue> incompatibleTarget);

      void acceptValidationException(ValidationException e);
    }

    public IncompatibleTargetProducer(
        TargetAndConfiguration targetAndConfiguration,
        ConfiguredTargetKey configuredTargetKey,
        ConfigConditions configConditions,
        @Nullable PlatformInfo platformInfo,
        TransitiveDependencyState transitiveState,
        ResultSink sink,
        StateMachine runAfter) {
      this.targetAndConfiguration = targetAndConfiguration;
      this.configuredTargetKey = configuredTargetKey;
      this.configConditions = configConditions;
      this.platformInfo = platformInfo;
      this.transitiveState = transitiveState;
      this.sink = sink;
      this.runAfter = runAfter;
    }

    @Override
    public StateMachine step(Tasks tasks) {
      Rule rule = targetAndConfiguration.getTarget().getAssociatedRule();
      if (rule == null || !rule.useToolchainResolution() || platformInfo == null) {
        sink.acceptIncompatibleTarget(Optional.empty());
        return runAfter;
      }

      BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();
      // Retrieves the label list for the target_compatible_with attribute.
      ConfiguredAttributeMapper attrs =
          ConfiguredAttributeMapper.of(rule, configConditions.asProviders(), configuration);
      if (!attrs.has("target_compatible_with", BuildType.LABEL_LIST)) {
        sink.acceptIncompatibleTarget(Optional.empty());
        return runAfter;
      }

      // Resolves the constraint labels, checking for invalid configured attributes.
      List<Label> targetCompatibleWith;
      try {
        targetCompatibleWith = attrs.getAndValidate("target_compatible_with", BuildType.LABEL_LIST);
      } catch (ValidationException e) {
        sink.acceptValidationException(e);
        return runAfter;
      }
      for (Label label : targetCompatibleWith) {
        tasks.lookUp(
            ConfiguredTargetKey.builder().setLabel(label).setConfiguration(configuration).build(),
            this);
      }
      return this::processResult;
    }

    @Override
    public void accept(SkyValue value) {
      var configuredTarget = ((ConfiguredTargetValue) value).getConfiguredTarget();
      @Nullable ConstraintValueInfo info = PlatformProviderUtils.constraintValue(configuredTarget);
      if (info == null || platformInfo.constraints().hasConstraintValue(info)) {
        return;
      }
      invalidConstraintValuesBuilder.add(info);
    }

    private StateMachine processResult(Tasks tasks) {
      var invalidConstraintValues = invalidConstraintValuesBuilder.build();
      if (!invalidConstraintValues.isEmpty()) {
        sink.acceptIncompatibleTarget(
            Optional.of(
                createIncompatibleRuleConfiguredTarget(
                    configuredTargetKey,
                    targetAndConfiguration.getConfiguration(),
                    configConditions,
                    IncompatiblePlatformProvider.incompatibleDueToConstraints(
                        platformInfo.label(), invalidConstraintValues),
                    targetAndConfiguration
                        .getTarget()
                        .getAssociatedRule()
                        .getRuleClassObject()
                        .getRuleClassId(),
                    transitiveState)));
        return runAfter;
      }
      sink.acceptIncompatibleTarget(Optional.empty());
      return runAfter;
    }
  }

  /** Rules where it doesn't make sense to check for platform compatibility. */
  private static final ImmutableList<String> NO_COMPATIBILITY_CHECK_RULES =
      ImmutableList.of("toolchain", "config_setting", "label_flag");

  /**
   * Creates an incompatible target if it is "indirectly incompatible".
   *
   * <p>In other words, this function checks if a target is incompatible because of one of its
   * dependencies. If a dependency is incompatible, then this target is also incompatible.
   *
   * <p>This function returns an {@code Optional} of a {@link RuleConfiguredTargetValue}. This
   * provides two states of return values:
   *
   * <ul>
   *   <li>{@code Optional.empty()}: The target is not indirectly incompatible. Analysis can
   *       continue.
   *   <li>{@code !Optional.empty()}: The target is indirectly incompatible. Analysis should not
   *       continue.
   * </ul>
   */
  public static Optional<RuleConfiguredTargetValue> createIndirectlyIncompatibleTarget(
      TargetAndConfiguration targetAndConfiguration,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap,
      ConfigConditions configConditions,
      @Nullable PlatformInfo platformInfo,
      TransitiveDependencyState transitiveState) {
    Target target = targetAndConfiguration.getTarget();
    Rule rule = target.getAssociatedRule();

    if (rule == null || NO_COMPATIBILITY_CHECK_RULES.contains(rule.getRuleClass())) {
      return Optional.empty();
    }

    // Find all the incompatible dependencies.
    ImmutableList<ConfiguredTarget> incompatibleDeps =
        depValueMap.values().stream()
            .map(ConfiguredTargetAndData::getConfiguredTarget)
            .filter(
                dep -> RuleContextConstraintSemantics.checkForIncompatibility(dep).isIncompatible())
            .collect(toImmutableList());
    if (incompatibleDeps.isEmpty()) {
      return Optional.empty();
    }

    BuildConfigurationValue configuration = targetAndConfiguration.getConfiguration();
    Label platformLabel = platformInfo != null ? platformInfo.label() : null;
    return Optional.of(
        createIncompatibleRuleConfiguredTarget(
            configuredTargetKey,
            configuration,
            configConditions,
            IncompatiblePlatformProvider.incompatibleDueToTargets(platformLabel, incompatibleDeps),
            rule.getRuleClassObject().getRuleClassId(),
            transitiveState));
  }

  /** Thrown if this target is platform-incompatible with the current build. */
  public static class IncompatibleTargetException extends Exception {
    private final RuleConfiguredTargetValue target;

    public IncompatibleTargetException(RuleConfiguredTargetValue target) {
      this.target = target;
    }

    public RuleConfiguredTargetValue target() {
      return target;
    }
  }

  /** Creates an incompatible target. */
  private static RuleConfiguredTargetValue createIncompatibleRuleConfiguredTarget(
      ConfiguredTargetKey configuredTargetKey,
      BuildConfigurationValue configuration,
      ConfigConditions configConditions,
      IncompatiblePlatformProvider incompatiblePlatformProvider,
      RuleClassId ruleClassId,
      TransitiveDependencyState transitiveState) {
    // Create dummy instances of the necessary data for a configured target. None of this data will
    // actually be used because actions associated with incompatible targets must not be evaluated.
    TransitiveInfoProviderMapBuilder providerBuilder =
        new TransitiveInfoProviderMapBuilder()
            .put(incompatiblePlatformProvider)
            .add(RunfilesProvider.simple(Runfiles.EMPTY))
            .add(FileProvider.EMPTY)
            .add(FilesToRunProvider.EMPTY)
            .add(SupportedEnvironments.EMPTY);
    if (configuration.hasFragment(TestConfiguration.class)) {
      // Create a dummy TestProvider instance so that other parts of the code base stay happy. Even
      // though this test will never execute, some code still expects the provider.
      TestProvider.TestParams testParams = TestActionBuilder.createEmptyTestParams();
      providerBuilder.put(TestProvider.class, new TestProvider(testParams));
    }

    RuleConfiguredTarget configuredTarget =
        new RuleConfiguredTarget(
            configuredTargetKey,
            convertVisibility(),
            /* isCreatedInSymbolicMacro= */ false,
            providerBuilder.build(),
            configConditions.asProviders(),
            ruleClassId);
    return new RuleConfiguredTargetValue(configuredTarget, transitiveState.transitivePackages());
  }

  /**
   * Generates visibility for an incompatible target.
   *
   * <p>The intent is for this function is to match ConfiguredTargetFactory.convertVisibility().
   * Since visibility is currently validated after incompatibility is evaluated, however, it doesn't
   * matter what visibility we set here. To keep it simple, we pretend that all incompatible targets
   * are public.
   *
   * <p>TODO(#16044): Set up properly validated visibility here.
   */
  private static NestedSet<PackageGroupContents> convertVisibility() {
    return VisibilityProvider.PUBLIC_VISIBILITY;
  }
}
