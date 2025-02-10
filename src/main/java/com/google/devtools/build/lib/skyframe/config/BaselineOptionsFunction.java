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
package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.transitions.BaselineOptionsValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import com.google.devtools.common.options.OptionsParsingException;
import javax.annotation.Nullable;

/** A builder for {@link BaselineOptionsValue} instances. */
public final class BaselineOptionsFunction implements SkyFunction {

  private final Version minimalVersionToInject;

  public BaselineOptionsFunction(Version minimalVersionToInject) {
    this.minimalVersionToInject = checkNotNull(minimalVersionToInject);
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, BaselineOptionsFunctionException {
    env.injectVersionForNonHermeticFunction(minimalVersionToInject);

    BaselineOptionsValue.Key key = (BaselineOptionsValue.Key) skyKey.argument();

    BuildOptions rawBaselineOptions;
    if (key.afterExecTransition()) {
      // Use the precomputed baseline exec
      rawBaselineOptions = PrecomputedValue.BASELINE_EXEC_CONFIGURATION.get(env);
    } else {
      // Use the standard baseline
      rawBaselineOptions = PrecomputedValue.BASELINE_CONFIGURATION.get(env);
    }

    // Some test infrastructure only creates mock or partial top-level BuildOptions such that
    // PlatformOptions or even CoreOptions might not be included.
    // In that case, is not worth doing any special processing of the baseline.
    if (rawBaselineOptions.hasNoConfig()) {
      return BaselineOptionsValue.create(rawBaselineOptions);
    }

    // First, make sure platform_mappings applied to the top-level baseline option.
    BuildOptions mappedBaselineOptions = mapBuildOptions(env, rawBaselineOptions);
    if (mappedBaselineOptions == null) {
      return null;
    }
    BuildOptions adjustedBaselineOptions = mappedBaselineOptions;

    if (key.newPlatform() != null) {
      // Clone for safety as-is the standard for all transitions.
      adjustedBaselineOptions = adjustedBaselineOptions.clone();
      adjustedBaselineOptions.get(PlatformOptions.class).platforms =
          ImmutableList.of(key.newPlatform());
    }

    // Re-apply platform_mappings if we updated the platform.
    // This initially seems somewhat redundant with the application above; however, this is meant to
    // better track how the top-level build options will initially have platform mappings applied
    // before some transition (e.g exec transition) changes the platform to cause another
    // application of platform mappings. Platforms in platform_mappings may change different sets of
    // options so applying both should lead to better baselines.
    // TODO(twigg,jcater): Evaluate and reconsider this 'scenario'.
    BuildOptions remappedAdjustedBaselineOptions = adjustedBaselineOptions;
    if (key.newPlatform() != null) {
      remappedAdjustedBaselineOptions = mapBuildOptions(env, remappedAdjustedBaselineOptions);
      if (remappedAdjustedBaselineOptions == null) {
        return null;
      }
    }

    return BaselineOptionsValue.create(remappedAdjustedBaselineOptions);
  }

  @Nullable
  private static BuildOptions mapBuildOptions(Environment env, BuildOptions rawBaselineOptions)
      throws InterruptedException, BaselineOptionsFunctionException {
    BuildConfigurationKeyValue.Key bckvk =
        BuildConfigurationKeyValue.Key.create(rawBaselineOptions);
    try {
      BuildConfigurationKeyValue buildConfigurationKeyValue =
          (BuildConfigurationKeyValue)
              env.getValueOrThrow(
                  bckvk,
                  OptionsParsingException.class,
                  PlatformMappingException.class,
                  InvalidPlatformException.class);
      if (buildConfigurationKeyValue == null) {
        return null;
      }
      return buildConfigurationKeyValue.buildConfigurationKey().getOptions();
    } catch (PlatformMappingException | OptionsParsingException | InvalidPlatformException e) {
      throw new BaselineOptionsFunctionException(e);
    }
  }

  private static final class BaselineOptionsFunctionException extends SkyFunctionException {
    BaselineOptionsFunctionException(Exception e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
