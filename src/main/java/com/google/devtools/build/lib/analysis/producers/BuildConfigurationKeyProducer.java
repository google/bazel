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

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Scope;
import com.google.devtools.build.lib.analysis.platform.PlatformValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingValue;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrExceptionSink;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Creates the needed {@link BuildConfigurationKey} instance for a single {@link BuildOptions},
 * including merging in any platform-based flags or a platform mapping.
 *
 * <p>Platform-based flags and platform mappings are mutually exclusive: only one will be applied if
 * they are present. Trying to mix and match would be possible but confusing, especially if they try
 * to change the same flag. The logic is:
 *
 * <ul>
 *   <li>If {@link PlatformOptions#platforms} specifies a target platform, look up the {@link
 *       PlatformValue}. If it specifies {@linkplain PlatformValue#parsedFlags flags}, use {@link
 *       ParsedFlagsValue#mergeWith}.
 *   <li>If {@link PlatformOptions#platforms} does not specify a target platform, or if the target
 *       platform does not specify {@linkplain PlatformValue#parsedFlags flags}, look up the {@link
 *       PlatformMappingValue} and use {@link PlatformMappingValue#map}.
 * </ul>
 *
 * <p>Scopes for starlark flags also get applied before producing the final BuildConfigurationKey.
 * Scopes are applied after platform-based flags or platform mappings are applied. The logic is:
 *
 * <ul>
 *   <li>If all starlark flags have ScopeType.UNIVERSAL, no further processing is done.
 *   <li>If any starlark flag has ScopeType.PROJECT or its ScopeType is not yet resolved, a lookup
 *       for {@link BuildOptionsScopeValue} via {@link BuildOptionsScopesFunction} is performed.
 *   <li>If the ScopeType for a flag is ScopeType.PROJECT, and the flag is not in the scope of the
 *       current package, the flag is reset to its baseline value if it is present in the baseline.
 *       If the flag is not present in the baseline, it is removed. This is to ensure that we do not
 *       trigger an addition ST-<hash>, which defeats the purpose of scoping.
 *   <li>If the ScopeType for a flag is ScopeType.PROJECT, and the flag is in the scope of the
 *       current package, the flag keeps its current value.
 * </ul>
 *
 * @param <C> The type of the context variable that the producer will pass via the {@link
 *     ResultSink} so that consumers can identify which options are which.
 */
public final class BuildConfigurationKeyProducer<C>
    implements StateMachine,
        ValueOrExceptionSink<PlatformMappingException>,
        Consumer<SkyValue>,
        PlatformProducer.ResultSink {

  /** Interface for clients to accept results of this computation. */
  public interface ResultSink<C> {

    void acceptOptionsParsingError(OptionsParsingException e);

    void acceptPlatformMappingError(PlatformMappingException e);

    void acceptPlatformFlagsError(InvalidPlatformException error);

    void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e);

    void acceptTransitionedConfiguration(C context, BuildConfigurationKey transitionedOptionKey);
  }

  // -------------------- Input --------------------
  private final ResultSink<C> sink;
  private final StateMachine runAfter;
  private final C context;
  private final BuildOptions options;
  private final Label label;

  // -------------------- Internal State --------------------
  private PlatformValue targetPlatformValue;
  private PlatformMappingValue platformMappingValue;
  private BuildOptionsScopeValue buildOptionsScopeValue;
  private BuildOptions postPlatformProcessedOptions;
  private BuildOptions baselineConfiguration;

  BuildConfigurationKeyProducer(
      ResultSink<C> sink, StateMachine runAfter, C context, BuildOptions options, Label label) {
    this.sink = sink;
    this.runAfter = runAfter;
    this.context = context;
    this.options = options;
    this.label = label;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    // Short-circuit if there are no platform options.
    var platformOptions = options.get(PlatformOptions.class);
    if (platformOptions == null) {
      this.postPlatformProcessedOptions = options;
      return this::findBuildOptionsScopes;
    }

    List<Label> targetPlatforms = platformOptions.platforms;
    if (targetPlatforms.size() == 1) {
      // TODO: https://github.com/bazelbuild/bazel/issues/19807 - We define this flag to only use
      //  the first value and ignore any subsequent ones. Remove this check as part of cleanup.
      tasks.enqueue(
          new PlatformProducer(targetPlatforms.getFirst(), this, this::checkTargetPlatformFlags));
      return runAfter;
    } else {
      Verify.verify(targetPlatforms.isEmpty());
      return this::mergeFromPlatformMapping;
    }
  }

  /**
   * Determine whether to update the BuildOptions with platform-based flags via {@link
   * ParsedFlagsValue#mergeWith} or with platform mappings via {@link PlatformMappingValue#map}
   * based on the presence of {@link ParsedFlagsValue}.
   */
  private StateMachine checkTargetPlatformFlags(Tasks tasks) {
    if (targetPlatformValue == null) {
      return DONE; // Error.
    }
    Optional<ParsedFlagsValue> parsedFlags = targetPlatformValue.parsedFlags();
    if (parsedFlags.isPresent()) {
      this.postPlatformProcessedOptions = parsedFlags.get().mergeWith(options).getOptions();
      return this::findBuildOptionsScopes;
    } else {
      return this::mergeFromPlatformMapping;
    }
  }

  /**
   * Performs a lookup for {@link BuildOptionsScopeValue} via {@link BuildOptionsScopesFunction}
   * given {@link postPlatformProcessedOptions}. This is only done if there are any flag that has
   * {@link ScopeType.PROJECT} or its {@link ScopeType} is not yet resolved.
   */
  private StateMachine findBuildOptionsScopes(Tasks tasks) {
    Preconditions.checkNotNull(this.postPlatformProcessedOptions);
    // including platform-based flags in skykey for scopes lookUp
    if (postPlatformProcessedOptions.getStarlarkOptions().isEmpty()) {
      return this::possiblyApplyScopes;
    }

    // the list of flags that are either project scoped or their scopes are not yet resolved.
    // Lookup via BuildOptionsScopeFunction will be done for these flags
    List<Label> flagsWithIncompleteScopeInfo = new ArrayList<>();
    for (Map.Entry<Label, Object> entry :
        postPlatformProcessedOptions.getStarlarkOptions().entrySet()) {
      Scope.ScopeType scopeType =
          this.postPlatformProcessedOptions.getScopeTypeMap().get(entry.getKey());
      // scope is null is applicable for cases where a transition applies starlark flags that are
      // not already part of the baseline configuration.
      if (scopeType == null || scopeType == Scope.ScopeType.PROJECT) {
        flagsWithIncompleteScopeInfo.add(entry.getKey());
      }
    }

    // if flagsWithIncompleteScopeInfo is empty, we do not need to do any further lookUp for the
    // ScopeType and ScopeDefinition
    if (flagsWithIncompleteScopeInfo.isEmpty()) {
      return this::possiblyApplyScopes;
    }

    BuildOptionsScopeValue.Key buildOptionsScopeValueKey =
        BuildOptionsScopeValue.Key.create(
            this.postPlatformProcessedOptions, flagsWithIncompleteScopeInfo);
    tasks.lookUp(buildOptionsScopeValueKey, (Consumer<SkyValue>) this);
    return this::possiblyApplyScopes;
  }

  /**
   * Performs a lookup for {@link PlatformMappingValue} via {@link PlatformMappingFunction} given
   * {@link options} and will transform the input {@link BuildOptions} with any matching platform
   * mappings.
   */
  private StateMachine mergeFromPlatformMapping(Tasks tasks) {
    tasks.lookUp(
        options.get(PlatformOptions.class).platformMappingKey,
        PlatformMappingException.class,
        this);
    return this::applyPlatformMapping;
  }

  private StateMachine applyPlatformMapping(Tasks tasks) {
    if (platformMappingValue == null) {
      return DONE; // Error.
    }
    try {
      this.postPlatformProcessedOptions = platformMappingValue.map(options).getOptions();
      return this::findBuildOptionsScopes;
    } catch (OptionsParsingException e) {
      sink.acceptOptionsParsingError(e);
      return runAfter;
    }
  }

  // Handles results from the PlatformMappingValueKey lookup.
  @Override
  public void acceptValueOrException(
      @Nullable SkyValue value, @Nullable PlatformMappingException exception) {
    if (value == null && exception == null) {
      throw new IllegalStateException("No value or exception was provided");
    }
    if (value != null && exception != null) {
      throw new IllegalStateException("Both value and exception were provided");
    }

    if (exception != null) {
      sink.acceptPlatformMappingError(exception);
    } else {
      this.platformMappingValue = (PlatformMappingValue) value;
    }
  }

  @Override
  public void acceptPlatformValue(PlatformValue value) {
    this.targetPlatformValue = value;
  }

  @Override
  public void acceptPlatformInfoError(InvalidPlatformException error) {
    sink.acceptPlatformFlagsError(error);
  }

  @Override
  public void acceptOptionsParsingError(OptionsParsingException error) {
    sink.acceptOptionsParsingError(error);
  }

  @Override
  public void accept(SkyValue value) {
    this.buildOptionsScopeValue = (BuildOptionsScopeValue) value;
  }

  private StateMachine possiblyApplyScopes(Tasks tasks) {
    // This is not the same as null associated with Skyframe lookUp. This happens when scoping logic
    // is not enabled. This means the lookup via BuildOptionsScopesFunction was not performed.
    if (buildOptionsScopeValue == null
        || postPlatformProcessedOptions.getStarlarkOptions().isEmpty()) {
      return finishConfigurationKeyProcessing(postPlatformProcessedOptions);
    }

    boolean shouldApplyScopes =
        buildOptionsScopeValue.getFullyResolvedScopes().values().stream()
            .anyMatch(scope -> scope.getScopeType() == Scope.ScopeType.PROJECT);

    if (!shouldApplyScopes) {
      return finishConfigurationKeyProcessing(
          buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes());
    }

    // TODO: b/390669368 - The same performance issue still exists if we reach this point.
    tasks.lookUp(
        PrecomputedValue.BASELINE_CONFIGURATION.getKey(),
        val -> this.baselineConfiguration = (BuildOptions) ((PrecomputedValue) val).get());
    return this::applyScopes;
  }

  private StateMachine applyScopes(Tasks tasks) {
    BuildOptions resolved = buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes();
    BuildOptions finalBuildOptions =
        baselineConfiguration.getStarlarkOptions().equals(resolved.getStarlarkOptions())
            ? resolved
            : resetFlags(buildOptionsScopeValue, baselineConfiguration, label);
    return finishConfigurationKeyProcessing(finalBuildOptions);
  }

  private StateMachine finishConfigurationKeyProcessing(BuildOptions finalBuildOptions) {
    sink.acceptTransitionedConfiguration(context, BuildConfigurationKey.create(finalBuildOptions));
    return runAfter;
  }

  /**
   * If a flag is considered to be out of scope, resetFlags does either of the following:
   *
   * <ul>
   *   <li>If the flag is not present in the baseline configuration, remove the flag from the {@link
   *       BuildOptions}.
   *   <li>If the flag is present in the baseline configuration, set the flag to the baseline value.
   *       <p>This is to ensure that we do not trigger an additional ST-<hash>, which defeats the
   *       <p>purpose of scoping.
   * </ul>
   *
   * This method returns the final {@link BuildOptions} after scoping is applied and the object only
   * has the {@link Scope.ScopeType} information for all starlark flags.
   */
  private static BuildOptions resetFlags(
      BuildOptionsScopeValue buildOptionsScopeValue,
      BuildOptions baselineConfiguration,
      Label label) {
    Preconditions.checkNotNull(buildOptionsScopeValue);
    Preconditions.checkNotNull(label);

    BuildOptions transitionedOptionsWithScopeType =
        buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes();
    // If there are no scopes, short circuit.
    if (buildOptionsScopeValue.getFullyResolvedScopes().isEmpty()) {
      return transitionedOptionsWithScopeType;
    }

    Preconditions.checkNotNull(baselineConfiguration);
    boolean flagsRemoved = false;
    boolean flagsResetToBaseline = false;
    BuildOptions.Builder optionsWithScopeTypesBuilder =
        transitionedOptionsWithScopeType.toBuilder();
    for (Map.Entry<Label, Object> flagEntry :
        transitionedOptionsWithScopeType.getStarlarkOptions().entrySet()) {
      Label flagLabel = flagEntry.getKey();
      Scope scope = buildOptionsScopeValue.getFullyResolvedScopes().get(flagLabel);
      if (scope == null) {
        Verify.verify(
            transitionedOptionsWithScopeType.getScopeTypeMap().get(flagLabel)
                == Scope.ScopeType.UNIVERSAL);
      } else if (scope.getScopeType() == Scope.ScopeType.PROJECT) {
        Object flagValue = flagEntry.getValue();
        Object baselineValue = baselineConfiguration.getStarlarkOptions().get(flagLabel);
        if (flagValue != baselineValue && !isInScope(label, scope.getScopeDefinition())) {
          if (baselineValue == null) {
            optionsWithScopeTypesBuilder.removeStarlarkOption(flagLabel);
            flagsRemoved = true;
          } else {
            optionsWithScopeTypesBuilder.addStarlarkOption(flagLabel, baselineValue);
            flagsResetToBaseline = true;
          }
        }
      }
    }

    if (!flagsRemoved && !flagsResetToBaseline) {
      return transitionedOptionsWithScopeType;
    }

    BuildOptions scopedBuildOptions = optionsWithScopeTypesBuilder.build();
    if (scopedBuildOptions.equals(baselineConfiguration)) {
      return baselineConfiguration;
    }

    return scopedBuildOptions;
  }

  private static boolean isInScope(Label label, Scope.ScopeDefinition scopeDefinition) {
    Preconditions.checkNotNull(scopeDefinition);
    for (String path : scopeDefinition.getOwnedCodePaths()) {
      if (label.getCanonicalForm().startsWith(path)) {
        return true;
      }
    }
    return false;
  }
}
