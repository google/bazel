// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.toolchains;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;

/**
 * A value which represents the map of potential execution platforms and resolved toolchains for a
 * single toolchain type. This allows for a Skyframe cache per toolchain type.
 *
 * @param toolchainType Returns the resolved details about the requested toolchain type.
 * @param availableToolchainLabels Returns the resolved set of toolchain labels (as {@link Label})
 *     for the requested toolchain type, keyed by the execution platforms (as {@link
 *     ConfiguredTargetKey}). Ordering is not preserved, if the caller cares about the order of
 *     platforms it must take care of that directly.
 */
@AutoCodec
public record SingleToolchainResolutionValue(
    ToolchainTypeInfo toolchainType,
    ImmutableMap<ConfiguredTargetKey, Label> availableToolchainLabels)
    implements SkyValue {
  public SingleToolchainResolutionValue {
    requireNonNull(toolchainType, "toolchainType");
    requireNonNull(availableToolchainLabels, "availableToolchainLabels");
  }

  // A key representing the input data.
  public static SingleToolchainResolutionKey key(
      BuildConfigurationKey configurationKey,
      ToolchainTypeRequirement toolchainType,
      ToolchainTypeInfo toolchainTypeInfo,
      ConfiguredTargetKey targetPlatformKey,
      List<ConfiguredTargetKey> availableExecutionPlatformKeys) {
    return key(
        configurationKey,
        toolchainType,
        toolchainTypeInfo,
        targetPlatformKey,
        availableExecutionPlatformKeys,
        false);
  }

  public static SingleToolchainResolutionKey key(
      BuildConfigurationKey configurationKey,
      ToolchainTypeRequirement toolchainType,
      ToolchainTypeInfo toolchainTypeInfo,
      ConfiguredTargetKey targetPlatformKey,
      List<ConfiguredTargetKey> availableExecutionPlatformKeys,
      boolean debugTarget) {
    return SingleToolchainResolutionKey.create(
        configurationKey,
        toolchainType,
        toolchainTypeInfo,
        targetPlatformKey,
        availableExecutionPlatformKeys,
        debugTarget);
  }

  /** {@link SkyKey} implementation used for {@link SingleToolchainResolutionFunction}. */
  @AutoValue
  public abstract static class SingleToolchainResolutionKey implements SkyKey {

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.SINGLE_TOOLCHAIN_RESOLUTION;
    }

    abstract BuildConfigurationKey configurationKey();

    public abstract ToolchainTypeRequirement toolchainType();

    public abstract ToolchainTypeInfo toolchainTypeInfo();

    abstract ConfiguredTargetKey targetPlatformKey();

    abstract ImmutableList<ConfiguredTargetKey> availableExecutionPlatformKeys();

    abstract boolean debugTarget();

    static SingleToolchainResolutionKey create(
        BuildConfigurationKey configurationKey,
        ToolchainTypeRequirement toolchainType,
        ToolchainTypeInfo toolchainTypeInfo,
        ConfiguredTargetKey targetPlatformKey,
        List<ConfiguredTargetKey> availableExecutionPlatformKeys,
        boolean debugTarget) {
      return new AutoValue_SingleToolchainResolutionValue_SingleToolchainResolutionKey(
          configurationKey,
          toolchainType,
          toolchainTypeInfo,
          targetPlatformKey,
          ImmutableList.copyOf(availableExecutionPlatformKeys),
          debugTarget);
    }
  }

  @VisibleForTesting
  public static SingleToolchainResolutionValue create(
      ToolchainTypeInfo toolchainType,
      ImmutableMap<ConfiguredTargetKey, Label> availableToolchainLabels) {
    return new SingleToolchainResolutionValue(toolchainType, availableToolchainLabels);
  }

}
