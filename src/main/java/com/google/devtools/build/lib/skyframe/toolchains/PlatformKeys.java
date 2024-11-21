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
package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.toolchains.ConstraintValueLookupUtil.InvalidConstraintValueException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsFunction.InvalidExecutionPlatformLabelException;
import com.google.devtools.build.skyframe.SkyFunction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;

/** Details of platforms used during toolchain resolution. */
record PlatformKeys(
    ConfiguredTargetKey targetPlatformKey,
    ImmutableList<ConfiguredTargetKey> executionPlatformKeys,
    ImmutableMap<ConfiguredTargetKey, PlatformInfo> platformInfos) {

  private static class Builder {
    // Input data.
    private final SkyFunction.Environment environment;
    private final ToolchainResolutionDebugPrinter debugPrinter;
    private final BuildConfigurationKey configurationKey;

    // Internal state used during loading.
    private final Label hostPlatformLabel;
    private final Label targetPlatformLabel;
    private ConfiguredTargetKey hostPlatformKey;
    private List<ConfiguredTargetKey> executionPlatformKeys;
    private Map<ConfiguredTargetKey, PlatformInfo> platformInfos;

    private Builder(
        SkyFunction.Environment environment,
        ToolchainResolutionDebugPrinter debugPrinter,
        BuildConfigurationKey configurationKey,
        PlatformConfiguration platformConfiguration) {
      this.environment = environment;
      this.debugPrinter = debugPrinter;
      this.configurationKey = configurationKey;

      this.hostPlatformLabel = platformConfiguration.getHostPlatform();
      this.targetPlatformLabel = platformConfiguration.getTargetPlatform();
    }

    private PlatformKeys build(ImmutableSet<Label> execConstraintLabels)
        throws InterruptedException,
            ToolchainResolutionFunction.ValueMissingException,
            InvalidPlatformException,
            InvalidExecutionPlatformLabelException,
            InvalidConstraintValueException {

      // Determine the execution platform keys.
      findExecutionPlatformKeys();

      // Load the PlatformInfo for all relevant platforms (target, host, exec).
      loadPlatformInfo();

      // Update platform keys to resolve possible aliases.
      ConfiguredTargetKey resolvedTargetPlatformKey =
          updateConfiguredTargetKey(targetPlatformLabel);
      ConfiguredTargetKey resolvedHostPlatformKey = updateConfiguredTargetKey(hostPlatformLabel);
      if (!hostPlatformKey.equals(resolvedHostPlatformKey)) {
        // Replace the host platform key in the execution platform keys with the updated version.
        this.executionPlatformKeys.remove(this.hostPlatformKey);
        this.executionPlatformKeys.add(resolvedHostPlatformKey);
        this.hostPlatformKey = resolvedHostPlatformKey;
      }

      // Ensure all platforms are present in platformInfos by original key and by alias-resolved
      // key, if they are different.
      Map<ConfiguredTargetKey, PlatformInfo> updated = new HashMap<>();
      for (Map.Entry<ConfiguredTargetKey, PlatformInfo> entry : this.platformInfos.entrySet()) {
        ConfiguredTargetKey originalKey = entry.getKey();
        ConfiguredTargetKey resolvedKey = updateConfiguredTargetKey(originalKey.getLabel());
        if (!originalKey.equals(resolvedKey)) {
          updated.put(resolvedKey, entry.getValue());
        }
      }
      this.platformInfos.putAll(updated);

      // Filter the execution platforms, based on the applied constraints (if any).
      ImmutableList<ConfiguredTargetKey> executionPlatformKeys =
          filterExecutionPlatforms(execConstraintLabels);

      return new PlatformKeys(
          resolvedTargetPlatformKey, executionPlatformKeys, ImmutableMap.copyOf(platformInfos));
    }

    private void findExecutionPlatformKeys()
        throws InterruptedException,
            ToolchainResolutionFunction.ValueMissingException,
            InvalidPlatformException,
            InvalidExecutionPlatformLabelException {
      // Find the registered execution platforms.
      RegisteredExecutionPlatformsValue registeredExecutionPlatforms =
          (RegisteredExecutionPlatformsValue)
              environment.getValueOrThrow(
                  RegisteredExecutionPlatformsValue.key(configurationKey),
                  InvalidPlatformException.class,
                  InvalidExecutionPlatformLabelException.class);
      if (registeredExecutionPlatforms == null) {
        throw new ToolchainResolutionFunction.ValueMissingException();
      }

      this.executionPlatformKeys = new ArrayList<>();
      executionPlatformKeys.addAll(registeredExecutionPlatforms.registeredExecutionPlatformKeys());
      this.hostPlatformKey =
          ConfiguredTargetKey.builder()
              .setLabel(hostPlatformLabel)
              .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
              .build();
      executionPlatformKeys.add(this.hostPlatformKey);
    }

    private void loadPlatformInfo()
        throws InterruptedException,
            ToolchainResolutionFunction.ValueMissingException,
            InvalidPlatformException {
      ImmutableList<ConfiguredTargetKey> platformKeys =
          new ImmutableList.Builder<ConfiguredTargetKey>()
              .add(
                  ConfiguredTargetKey.builder()
                      .setLabel(targetPlatformLabel)
                      .setConfigurationKey(
                          BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                      .build())
              .addAll(this.executionPlatformKeys)
              .build();

      this.platformInfos = PlatformLookupUtil.getPlatformInfo(platformKeys, environment);
      if (environment.valuesMissing()) {
        throw new ToolchainResolutionFunction.ValueMissingException();
      }
    }

    @Nullable
    private ConfiguredTargetKey updateConfiguredTargetKey(Label platformLabel) {
      Optional<PlatformInfo> platformInfo =
          platformInfos.entrySet().stream()
              .filter(entry -> entry.getKey().getLabel().equals(platformLabel))
              .map(Entry::getValue)
              .findFirst();
      return platformInfo
          .map(
              info ->
                  ConfiguredTargetKey.builder()
                      .setLabel(info.label())
                      .setConfigurationKey(
                          BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                      .build())
          .orElse(null);
    }

    private ImmutableList<ConfiguredTargetKey> filterExecutionPlatforms(
        ImmutableSet<Label> execConstraintLabels)
        throws InterruptedException,
            ToolchainResolutionFunction.ValueMissingException,
            InvalidConstraintValueException {

      // Short circuit if not needed.
      if (execConstraintLabels.isEmpty()) {
        return ImmutableList.copyOf(executionPlatformKeys);
      }

      // Filter out execution platforms that don't satisfy the extra constraints.
      ImmutableList<ConfiguredTargetKey> execConstraintKeys =
          execConstraintLabels.stream()
              .map(
                  label ->
                      ConfiguredTargetKey.builder()
                          .setLabel(label)
                          .setConfigurationKey(
                              BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                          .build())
              .collect(toImmutableList());

      List<ConstraintValueInfo> constraints =
          ConstraintValueLookupUtil.getConstraintValueInfo(execConstraintKeys, environment);
      if (constraints == null) {
        throw new ToolchainResolutionFunction.ValueMissingException();
      }

      return executionPlatformKeys.stream()
          .filter(key -> filterPlatform(platformInfos.get(key), constraints))
          .collect(toImmutableList());
    }

    /** Returns {@code true} if the given platform has all of the constraints. */
    private boolean filterPlatform(
        PlatformInfo platformInfo, List<ConstraintValueInfo> constraints) {
      ImmutableList<ConstraintValueInfo> missingConstraints =
          platformInfo.constraints().findMissing(constraints);
      debugPrinter.reportRemovedExecutionPlatform(platformInfo.label(), missingConstraints);

      return missingConstraints.isEmpty();
    }
  }

  static PlatformKeys load(
      SkyFunction.Environment environment,
      ToolchainResolutionDebugPrinter debugPrinter,
      BuildConfigurationKey configurationKey,
      PlatformConfiguration platformConfiguration,
      ImmutableSet<Label> execConstraintLabels)
      throws InterruptedException,
          ToolchainResolutionFunction.ValueMissingException,
          InvalidConstraintValueException,
          InvalidPlatformException,
          InvalidExecutionPlatformLabelException {

    return new Builder(environment, debugPrinter, configurationKey, platformConfiguration)
        .build(execConstraintLabels);
  }

  @Nullable
  public ConfiguredTargetKey find(Label platformLabel) {
    if (platformLabel.equals(targetPlatformKey.getLabel())) {
      return targetPlatformKey();
    }

    for (ConfiguredTargetKey configuredTargetKey : executionPlatformKeys) {
      if (platformLabel.equals(configuredTargetKey.getLabel())) {
        return configuredTargetKey;
      }
    }

    return null;
  }

  @Nullable
  PlatformInfo platformInfo(ConfiguredTargetKey configuredTargetKey) {
    return platformInfos.get(configuredTargetKey);
  }

  public boolean isPlatformSuitable(
      ConfiguredTargetKey executionPlatformKey,
      ImmutableSet<ToolchainResolutionFunction.ToolchainType> toolchainTypes,
      Table<ConfiguredTargetKey, ToolchainTypeInfo, Label> resolvedToolchains,
      boolean checkAllowedToolchainTypes) {
    PlatformInfo executionPlatformInfo = platformInfo(executionPlatformKey);
    if (checkAllowedToolchainTypes
        && executionPlatformInfo.checkToolchainTypes()
        && toolchainTypes.isEmpty()) {
      // This can't be suitable.
      return false;
    } else if (toolchainTypes.isEmpty()) {
      // Since there aren't any toolchains, we should be able to use any execution platform that
      // has made it this far.
      return true;
    }

    // Determine whether all mandatory toolchains are present.
    return resolvedToolchains
        .row(executionPlatformKey)
        .keySet()
        .containsAll(
            toolchainTypes.stream()
                .filter(ToolchainResolutionFunction.ToolchainType::mandatory)
                .map(ToolchainResolutionFunction.ToolchainType::toolchainTypeInfo)
                .collect(toImmutableSet()));
  }
}
