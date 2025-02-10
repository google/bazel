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

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Applies a configuration transition to a build options instance.
 *
 * <p>postwork - replay events/throw errors from transition implementation function and validate the
 * outputs of the transition. This only applies to Starlark transitions.
 */
final class TransitionApplier
    implements StateMachine, StateMachine.ValueOrExceptionSink<TransitionException> {
  interface ResultSink extends BuildConfigurationKeyMapProducer.ResultSink {
    void acceptTransitionError(TransitionException e);
  }

  // -------------------- Input --------------------
  private final Label label;
  private final BuildConfigurationKey fromConfiguration;
  private final ConfigurationTransition transition;
  private final StarlarkTransitionCache transitionCache;

  // -------------------- Output --------------------
  private final ResultSink sink;
  private final ExtendedEventHandler eventHandler;

  // -------------------- Sequencing --------------------
  private final StateMachine runAfter;

  // -------------------- Internal State --------------------
  private StarlarkBuildSettingsDetailsValue buildSettingsDetailsValue;

  TransitionApplier(
      Label label,
      BuildConfigurationKey fromConfiguration,
      ConfigurationTransition transition,
      StarlarkTransitionCache transitionCache,
      ResultSink sink,
      ExtendedEventHandler eventHandler,
      StateMachine runAfter) {
    this.fromConfiguration = fromConfiguration;
    this.transition = transition;
    this.transitionCache = transitionCache;
    this.sink = sink;
    this.eventHandler = eventHandler;
    this.runAfter = runAfter;
    this.label = label;
  }

  @Override
  public StateMachine step(Tasks tasks) throws InterruptedException {
    boolean doesStarlarkTransition;
    try {
      doesStarlarkTransition = StarlarkTransition.doesStarlarkTransition(transition);
    } catch (TransitionException e) {
      sink.acceptTransitionError(e);
      return runAfter;
    }
    if (!doesStarlarkTransition) {
      return new BuildConfigurationKeyMapProducer(
          this.sink,
          this.runAfter,
          transition.apply(
              TransitionUtil.restrict(transition, fromConfiguration.getOptions()), eventHandler),
          this.label);
    }

    ImmutableSet<Label> starlarkBuildSettings =
        StarlarkTransition.getAllStarlarkBuildSettings(transition);
    if (starlarkBuildSettings.isEmpty()) {
      // Quick escape if transition doesn't use any Starlark build settings.
      buildSettingsDetailsValue = StarlarkBuildSettingsDetailsValue.EMPTY;
      return applyStarlarkTransition(tasks);
    }
    tasks.lookUp(
        StarlarkBuildSettingsDetailsValue.key(starlarkBuildSettings),
        TransitionException.class,
        (ValueOrExceptionSink<TransitionException>) this);
    return this::applyStarlarkTransition;
  }

  @Override
  public void acceptValueOrException(@Nullable SkyValue value, @Nullable TransitionException e) {
    if (value != null) {
      buildSettingsDetailsValue = (StarlarkBuildSettingsDetailsValue) value;
      return;
    }
    if (e != null) {
      sink.acceptTransitionError(e);
      return;
    }
    throw new IllegalArgumentException("No result received.");
  }

  private StateMachine applyStarlarkTransition(Tasks tasks) throws InterruptedException {
    if (buildSettingsDetailsValue == null) {
      return runAfter; // There was an error.
    }

    Map<String, BuildOptions> transitionedOptions;
    try {
      transitionedOptions =
          transitionCache.computeIfAbsent(
              fromConfiguration.getOptions(), transition, buildSettingsDetailsValue, eventHandler);
    } catch (TransitionException e) {
      sink.acceptTransitionError(e);
      return runAfter;
    }

    return new BuildConfigurationKeyMapProducer(
        this.sink, this.runAfter, transitionedOptions, this.label);
  }
}
