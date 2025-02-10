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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Map;

/**
 * Creates the needed {@link BuildConfigurationKey} instances for the given options.
 *
 * <p>This includes merging in platform mappings and platform-based flags.
 *
 * <p>The output preserves the iteration order of the input.
 */
public class BuildConfigurationKeyMapProducer
    implements StateMachine, BuildConfigurationKeyProducer.ResultSink<String> {

  /** Interface for clients to accept results of this computation. */
  public interface ResultSink {

    void acceptOptionsParsingError(OptionsParsingException e);

    void acceptPlatformMappingError(PlatformMappingException e);

    void acceptPlatformFlagsError(InvalidPlatformException error);

    void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e);

    void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionedOptions);
  }

  // -------------------- Input --------------------
  private final ResultSink sink;
  private final StateMachine runAfter;
  private final Map<String, BuildOptions> options;
  private final Label label;

  // -------------------- Internal State --------------------
  private final Map<String, BuildConfigurationKey> results;

  public BuildConfigurationKeyMapProducer(
      ResultSink sink, StateMachine runAfter, Map<String, BuildOptions> options, Label label) {
    this.sink = sink;
    this.runAfter = runAfter;
    this.options = options;
    this.results = Maps.newHashMapWithExpectedSize(options.size());
    this.label = label;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    options.forEach(
        (context, buildOptions) ->
            tasks.enqueue(
                new BuildConfigurationKeyProducer<>(
                    (BuildConfigurationKeyProducer.ResultSink<String>) this,
                    StateMachine.DONE,
                    context,
                    buildOptions,
                    label)));
    return this::combineResults;
  }

  private StateMachine combineResults(Tasks tasks) {
    if (this.results.size() != this.options.size()) {
      // An error occurred while processing at least one set of options.
      return StateMachine.DONE;
    }

    // Ensure that the result keys are in the same order as the original.
    ImmutableMap.Builder<String, BuildConfigurationKey> output =
        ImmutableMap.builderWithExpectedSize(this.options.size());
    for (String transitionKey : this.options.keySet()) {
      BuildConfigurationKey resultKey = this.results.get(transitionKey);
      output.put(transitionKey, resultKey);
    }

    this.sink.acceptTransitionedConfigurations(output.buildOrThrow());
    return this.runAfter;
  }

  @Override
  public void acceptOptionsParsingError(OptionsParsingException e) {
    this.sink.acceptOptionsParsingError(e);
  }

  @Override
  public void acceptPlatformMappingError(PlatformMappingException e) {
    this.sink.acceptPlatformMappingError(e);
  }

  @Override
  public void acceptPlatformFlagsError(InvalidPlatformException error) {
    this.sink.acceptPlatformFlagsError(error);
  }

  @Override
  public void acceptTransitionedConfiguration(
      String transitionKey, BuildConfigurationKey transitionedOptionKey) {
    this.results.put(transitionKey, transitionedOptionKey);
  }

  @Override
  public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
    this.sink.acceptBuildOptionsScopeFunctionError(e);
  }
}
