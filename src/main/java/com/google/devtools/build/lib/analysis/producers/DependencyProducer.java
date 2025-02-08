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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.analysis.DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY;
import static com.google.devtools.build.lib.analysis.DependencyKind.VISIBILITY_DEPENDENCY;
import static com.google.devtools.build.lib.analysis.DependencyResolutionHelpers.getExecutionPlatformLabel;
import static com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyKind.AttributeDependencyKind;
import com.google.devtools.build.lib.analysis.DependencyKind.ToolchainDependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolutionHelpers;
import com.google.devtools.build.lib.analysis.DependencyResolutionHelpers.ExecutionPlatformResult;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigurationTransitionEvent;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionCollector;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionCreationException;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.AspectCreationException;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Evaluates dependencies.
 *
 * <p>A dependency is described by a {@link DependencyKind}, a {@link Label} and possibly a list of
 * {@link Aspect}s. This class determines the {@link AttributeConfiguration}, based on the parent's
 * configuration. This may include using the {@link TransitionApplier} to perform an attribute
 * configuration transition.
 *
 * <p>It then delegates computation of the {@link ConfiguredTargetAndData} prerequisite values to
 * {@link PrerequisitesProducer} with the determined configuration(s).
 */
final class DependencyProducer
    implements StateMachine, TransitionApplier.ResultSink, PrerequisitesProducer.ResultSink {
  private static final ConfiguredTargetAndData[] EMPTY_OUTPUT = new ConfiguredTargetAndData[0];

  interface ResultSink extends TransitionCollector {
    /**
     * Accepts dependency values for a given kind and label.
     *
     * <p>Multiple values may occur if there is a split transition.
     *
     * <p>For a skipped dependency, outputs an empty array. See comments in {@link
     * DependencyResolutionHelpers#getExecutionPlatformLabel} for when this happens.
     */
    void acceptDependencyValues(int index, ConfiguredTargetAndData[] values);

    void acceptDependencyError(DependencyError error);

    void acceptDependencyError(MissingEdgeError error);
  }

  // -------------------- Input --------------------
  private final PrerequisiteParameters parameters;
  private final DependencyKind kind;
  private final Label toLabel;
  private final ImmutableList<Aspect> propagatingAspects;

  // -------------------- Output --------------------
  private final ResultSink sink;
  private final int index;

  // -------------------- Internal State --------------------
  private ImmutableMap<String, BuildConfigurationKey> transitionedConfigurations;

  DependencyProducer(
      PrerequisiteParameters parameters,
      DependencyKind kind,
      Label toLabel,
      ImmutableList<Aspect> propagatingAspects,
      ResultSink sink,
      int index) {
    this.parameters = parameters;
    this.kind = checkNotNull(kind);
    this.toLabel = toLabel;
    this.propagatingAspects = propagatingAspects;
    this.sink = sink;
    this.index = index;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    @Nullable Attribute attribute = kind.getAttribute();

    if (kind == VISIBILITY_DEPENDENCY
        || (attribute != null && attribute.getName().equals("visibility"))) {
      // This is always a null transition because visibility targets are not configurable.
      return computePrerequisites(
          AttributeConfiguration.ofVisibility(), /* executionPlatformLabel= */ null);
    }

    // The logic of `DependencyResolutionHelpers.computeDependencyLabels` implies that
    // `parameters.configurationKey()` is non-null for everything that follows.
    BuildConfigurationKey configurationKey = checkNotNull(parameters.configurationKey());

    if (DependencyKind.isToolchain(kind)) {
      // There's no attribute so no attribute transition.

      // This dependency is a toolchain. Its package has not been loaded and therefore we can't
      // determine which aspects and which rule configuration transition we should use, so just
      // use sensible defaults. Not depending on their package makes the error message reporting
      // a missing toolchain a bit better.
      // TODO(lberki): This special-casing is weird. Find a better way to depend on toolchains.
      // This logic needs to stay in sync with the dep finding logic in
      // //third_party/bazel/src/main/java/com/google/devtools/build/lib/analysis/Util.java#findImplicitDeps.
      return computePrerequisites(
          AttributeConfiguration.ofUnary(configurationKey),
          parameters.getExecutionPlatformLabel(
              ((ToolchainDependencyKind) kind).getExecGroupName(),
              DependencyKind.isBaseTargetToolchain(kind)));
    }

    if (kind == OUTPUT_FILE_RULE_DEPENDENCY) {
      // There's no attribute so no attribute transition.
      return computePrerequisites(
          AttributeConfiguration.ofUnary(configurationKey), /* executionPlatformLabel= */ null);
    }

    var transitionData =
        AttributeTransitionData.builder()
            .attributes(parameters.attributeMap())
            .analysisData(parameters.starlarkTransitionProvider());
    ExecutionPlatformResult executionPlatformResult =
        getExecutionPlatformLabel(
            (AttributeDependencyKind) kind,
            parameters.toolchainContexts(),
            parameters.baseTargetToolchainContexts(),
            parameters.aspects());
    switch (executionPlatformResult.kind()) {
      case LABEL -> transitionData.executionPlatform(executionPlatformResult.label());
      case NULL_LABEL -> transitionData.executionPlatform(null);
      case SKIP -> {
        sink.acceptDependencyValues(index, EMPTY_OUTPUT);
        return DONE;
      }
      case ERROR -> {
        return new ExecGroupErrorEmitter(executionPlatformResult.error());
      }
    }
    ConfigurationTransition attributeTransition;
    try {
      attributeTransition = attribute.getTransitionFactory().create(transitionData.build());
    } catch (TransitionCreationException e) {
      sink.acceptDependencyError(DependencyError.of(e));
      return DONE;
    }
    sink.acceptTransition(kind, toLabel, attributeTransition);
    return new TransitionApplier(
        toLabel,
        configurationKey,
        attributeTransition,
        parameters.transitionCache(),
        (TransitionApplier.ResultSink) this,
        parameters.eventHandler(),
        /* runAfter= */ this::processTransitionResult);
  }

  @Override
  public void acceptTransitionedConfigurations(
      ImmutableMap<String, BuildConfigurationKey> transitionedConfigurations) {
    this.transitionedConfigurations = transitionedConfigurations;
  }

  @Override
  public void acceptTransitionError(TransitionException e) {
    sink.acceptDependencyError(
        DependencyError.of(new TransitionException(getMessageWithEdgeTransitionInfo(e), e)));
  }

  @Override
  public void acceptOptionsParsingError(OptionsParsingException e) {
    sink.acceptDependencyError(
        DependencyError.of(
            new OptionsParsingException(
                getMessageWithEdgeTransitionInfo(e), e.getInvalidArgument(), e)));
  }

  @Override
  public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
    sink.acceptDependencyError(DependencyError.of(e));
  }

  @Override
  public void acceptPlatformMappingError(PlatformMappingException e) {
    sink.acceptDependencyError(DependencyError.of(e));
  }

  @Override
  public void acceptPlatformFlagsError(InvalidPlatformException e) {
    sink.acceptDependencyError(DependencyError.of(e));
  }

  private String getMessageWithEdgeTransitionInfo(Throwable e) {
    return String.format(
        "On dependency edge %s (%s) -|%s|-> %s: %s",
        parameters.target().getLabel(),
        parameters.configurationKey().getOptions().shortId(),
        kind.getAttribute().getName(),
        toLabel,
        e.getMessage());
  }

  private StateMachine processTransitionResult(Tasks tasks) {
    if (transitionedConfigurations == null) {
      return DONE; // There was a previously reported error.
    }

    if (isNonconfigurableTargetInSamePackage()) {
      // The target is in the same package as the parent and non-configurable. In the general case
      // loading a child target would defeat Package-based sharding. However, when the target is in
      // the same Package, that concern no longer applies. This optimization means that delegation,
      // and the corresponding creation of additional Skyframe nodes, can be avoided in the very
      // common case of source file dependencies in the same Package.

      // Discards transition keys for patch transitions but keeps them otherwise.
      ImmutableList<String> transitionKeys =
          transitionedConfigurations.size() == 1
                  && transitionedConfigurations.containsKey(PATCH_TRANSITION_KEY)
              ? ImmutableList.of()
              : transitionedConfigurations.keySet().asList();
      return computePrerequisites(
          AttributeConfiguration.ofNullTransitionKeys(transitionKeys),
          /* executionPlatformLabel= */ null);
    }

    String parentChecksum = parameters.configurationKey().getOptionsChecksum();
    for (BuildConfigurationKey configuration : transitionedConfigurations.values()) {
      String childChecksum = configuration.getOptionsChecksum();
      if (!parentChecksum.equals(childChecksum)) {
        parameters
            .eventHandler()
            .post(ConfigurationTransitionEvent.create(parentChecksum, childChecksum));
      }
    }

    if (transitionedConfigurations.size() == 1) {
      BuildConfigurationKey patchedConfiguration =
          transitionedConfigurations.get(PATCH_TRANSITION_KEY);
      if (patchedConfiguration != null) {
        // It was a patch transition or no-op split transition.
        return computePrerequisites(
            AttributeConfiguration.ofUnary(patchedConfiguration),
            /* executionPlatformLabel= */ null);
      }
    }

    return computePrerequisites(
        AttributeConfiguration.ofSplit(transitionedConfigurations),
        /* executionPlatformLabel= */ null);
  }

  private StateMachine computePrerequisites(
      AttributeConfiguration configuration, @Nullable Label executionPlatformLabel) {
    return new PrerequisitesProducer(
        parameters,
        toLabel,
        executionPlatformLabel,
        configuration,
        propagatingAspects,
        (PrerequisitesProducer.ResultSink) this,
        useBaseTargetPrerequisitesSupplier());
  }

  /**
   * Returns true only during aspects evaluation for attribute dependencies not owned by an aspect
   * to enable using the {@link BaseTargetPrerequisitesSupplier} to look up them.
   *
   * <p>Check {@link AspectFunction#baseTargetPrerequisitesSupplier} for more details.
   */
  private boolean useBaseTargetPrerequisitesSupplier() {
    if (parameters.aspects().isEmpty()) {
      return false;
    }

    if (DependencyKind.isBaseTargetToolchain(kind)) {
      return true;
    }

    if (DependencyKind.isAttribute(kind)) {
      if (kind.getOwningAspect() == null) {
        return true;
      }
    }

    return false;
  }

  @Override
  public void acceptPrerequisitesValue(ConfiguredTargetAndData[] value) {
    sink.acceptDependencyValues(index, value);
  }

  @Override
  public void acceptPrerequisitesError(NoSuchThingException error) {
    sink.acceptDependencyError(new MissingEdgeError(kind, toLabel, error));
  }

  @Override
  public void acceptPrerequisitesError(InvalidVisibilityDependencyException error) {
    sink.acceptDependencyError(DependencyError.of(error));
  }

  @Override
  public void acceptPrerequisitesCreationError(ConfiguredValueCreationException error) {
    // Cases where the child target cannot be loaded at all are propagated as
    // `NoSuchThingException`. In some cases, child target loading completes with errors. In that
    // case, the error is propagated as a `ConfiguredValueCreationException` with a
    // `LoadingFailedCause`. Requests parent-side context to be added to such errors by propagating
    // a `MissingEdgeError`.
    for (Cause cause : error.getRootCauses().toList()) {
      if (cause instanceof LoadingFailedCause loadingFailed) {
        if (loadingFailed.getLabel().equals(toLabel)) {
          sink.acceptDependencyError(
              new MissingEdgeError(
                  kind, toLabel, NoSuchTargetException.createForParentPropagation(toLabel)));
        }
      }
    }
  }

  @Override
  public void acceptPrerequisitesAspectError(DependencyEvaluationException error) {
    sink.acceptDependencyError(DependencyError.of(error));
  }

  @Override
  public void acceptPrerequisitesAspectError(AspectCreationException error) {
    sink.acceptDependencyError(DependencyError.of(error));
  }

  private boolean isNonconfigurableTargetInSamePackage() {
    Target parentTarget = parameters.target();
    if (parentTarget.getLabel().getPackageIdentifier().equals(toLabel.getPackageIdentifier())) {
      try {
        Target toTarget = parentTarget.getPackage().getTarget(toLabel.getName());
        if (!toTarget.isConfigurable()) {
          return true;
        }
      } catch (NoSuchTargetException e) {
        parameters
            .transitiveState()
            .addTransitiveCause(new LoadingFailedCause(toLabel, e.getDetailedExitCode()));
        parameters
            .eventHandler()
            .handle(
                Event.error(
                    TargetUtils.getLocationMaybe(parentTarget),
                    TargetUtils.formatMissingEdge(parentTarget, toLabel, e, kind.getAttribute())));
      }
    }
    return false;
  }

  /**
   * Emits errors from {@link ExecutionPlatformResult#error}.
   *
   * <p>Exists to fetch the {@link BuildConfigurationValue}, needed to construct {@link
   * AnalysisRootCauseEvent}.
   */
  private class ExecGroupErrorEmitter implements StateMachine, Consumer<SkyValue> {
    // -------------------- Input --------------------
    private final String message;

    // -------------------- Internal State --------------------
    private BuildConfigurationValue configuration;

    private ExecGroupErrorEmitter(String message) {
      this.message = message;
    }

    @Override
    public StateMachine step(Tasks tasks) {
      // The configuration value should already exist as a dependency so this lookup is safe enough
      // for error handling.
      tasks.lookUp(parameters.configurationKey(), (Consumer<SkyValue>) this);
      return this::postEvent;
    }

    @Override
    public void accept(SkyValue value) {
      this.configuration = (BuildConfigurationValue) value;
    }

    private StateMachine postEvent(Tasks tasks) {
      parameters
          .eventHandler()
          .post(AnalysisRootCauseEvent.withConfigurationValue(configuration, toLabel, message));
      sink.acceptDependencyError(
          DependencyError.of(
              new DependencyEvaluationException(
                  new ConfiguredValueCreationException(
                      parameters.location(),
                      message,
                      parameters.label(),
                      parameters.eventId(),
                      /* rootCauses= */ null,
                      /* detailedExitCode= */ null),
                  // This error originates in dependency resolution, attached to the current target,
                  // so no dependency has reported the error.
                  /* depReportedOwnError= */ false)));
      return DONE;
    }
  }
}
