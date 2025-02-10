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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsFunction.InvalidToolchainLabelException;
import com.google.devtools.build.lib.skyframe.toolchains.SingleToolchainResolutionValue.SingleToolchainResolutionKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** {@link SkyFunction} which performs toolchain resolution for a single toolchain type. */
public class SingleToolchainResolutionFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws ToolchainResolutionFunctionException, InterruptedException {
    SingleToolchainResolutionKey key = (SingleToolchainResolutionKey) skyKey.argument();

    // This call could be combined with the call below, but this SkyFunction is evaluated so rarely
    // it's not worth optimizing.
    BuildConfigurationValue configuration =
        (BuildConfigurationValue) env.getValue(key.configurationKey());
    if (env.valuesMissing()) {
      return null;
    }

    // Check if we are debugging the target or the toolchain type.
    boolean debug =
        key.debugTarget()
            || configuration
                .getFragment(PlatformConfiguration.class)
                .debugToolchainResolution(key.toolchainType().toolchainType());

    // Get all toolchains.
    RegisteredToolchainsValue toolchains;
    try {
      toolchains =
          (RegisteredToolchainsValue)
              env.getValueOrThrow(
                  RegisteredToolchainsValue.key(key.configurationKey(), debug),
                  InvalidToolchainLabelException.class);
      if (toolchains == null) {
        return null;
      }
    } catch (InvalidToolchainLabelException e) {
      throw new ToolchainResolutionFunctionException(e);
    }

    SingleToolchainResolutionDebugPrinter debugPrinter =
        SingleToolchainResolutionDebugPrinter.create(debug, env.getListener());

    // Describe rejected toolchains if any are present.
    Optional.ofNullable(toolchains.rejectedToolchains())
        .map(rejectedToolchains -> rejectedToolchains.row(key.toolchainType().toolchainType()))
        .ifPresent(debugPrinter::describeRejectedToolchains);

    // Find the right one.
    SingleToolchainResolutionValue toolchainResolution =
        resolveConstraints(
            env,
            debugPrinter,
            key.toolchainType(),
            key.toolchainTypeInfo(),
            key.availableExecutionPlatformKeys(),
            key.targetPlatformKey(),
            toolchains.registeredToolchains());

    debugPrinter.finishDebugging();

    return toolchainResolution;
  }

  /**
   * Given the available execution platforms and toolchains, find the set of platform, toolchain
   * pairs that are compatible a) with each other, and b) with the toolchain type and target
   * platform.
   */
  @Nullable
  private static SingleToolchainResolutionValue resolveConstraints(
      Environment env,
      SingleToolchainResolutionDebugPrinter debugPrinter,
      ToolchainTypeRequirement toolchainType,
      ToolchainTypeInfo toolchainTypeInfo,
      List<ConfiguredTargetKey> availableExecutionPlatformKeys,
      ConfiguredTargetKey targetPlatformKey,
      ImmutableList<DeclaredToolchainInfo> toolchains)
      throws ToolchainResolutionFunctionException, InterruptedException {

    // Load the PlatformInfo needed to check constraints.
    Map<ConfiguredTargetKey, PlatformInfo> platforms;
    try {
      platforms =
          PlatformLookupUtil.getPlatformInfo(
              ImmutableList.<ConfiguredTargetKey>builderWithExpectedSize(
                      availableExecutionPlatformKeys.size() + 1)
                  .add(targetPlatformKey)
                  .addAll(availableExecutionPlatformKeys)
                  .build(),
              env);
      if (env.valuesMissing()) {
        return null;
      }
    } catch (InvalidPlatformException e) {
      throw new ToolchainResolutionFunctionException(e);
    }

    PlatformInfo targetPlatform = platforms.get(targetPlatformKey);
    debugPrinter.startToolchainResolution(toolchainType.toolchainType(), targetPlatform.label());

    // Platforms may exist multiple times in availableExecutionPlatformKeys. The Set lets this code
    // check whether a platform has already been seen during processing.
    Set<ConfiguredTargetKey> platformKeysSeen = new HashSet<>();
    ImmutableMap.Builder<ConfiguredTargetKey, Label> builder = ImmutableMap.builder();

    // Pre-filter for the correct toolchain type. This simplifies the loop and makes debugging
    // toolchain resolution much, much easier.
    ImmutableList<DeclaredToolchainInfo> filteredToolchains =
        toolchains.stream()
            .filter(
                toolchain ->
                    toolchain.toolchainType().typeLabel().equals(toolchainType.toolchainType()))
            .collect(toImmutableList());

    for (DeclaredToolchainInfo toolchain : filteredToolchains) {
      // Make sure the target platform matches.
      if (!checkConstraints(
          debugPrinter,
          toolchain.targetConstraints(),
          /* isTargetPlatform= */ true,
          targetPlatform,
          toolchain.targetLabel(),
          toolchain.resolvedToolchainLabel())) {
        continue;
      }

      debugPrinter.reportCompatibleTargetPlatform(
          toolchain.targetLabel(), toolchain.resolvedToolchainLabel());

      boolean done = true;

      // Find the matching execution platforms.
      for (ConfiguredTargetKey executionPlatformKey : availableExecutionPlatformKeys) {
        // Only check the toolchains if this is a new platform.
        if (platformKeysSeen.contains(executionPlatformKey)) {
          debugPrinter.reportSkippedExecutionPlatformSeen(executionPlatformKey.getLabel());
          continue;
        }

        PlatformInfo executionPlatform = platforms.get(executionPlatformKey);

        // Check if the platform allows this toolchain type.
        if (executionPlatform.checkToolchainTypes()
            && !executionPlatform.allowedToolchainTypes().contains(toolchainType.toolchainType())) {
          debugPrinter.reportSkippedExecutionPlatformDisallowed(
              executionPlatformKey.getLabel(), toolchainType.toolchainType());

          // Keep looking for a valid toolchain for this exec platform
          done = false;
          continue;
        }

        // Check if the execution constraints match.
        if (!checkConstraints(
            debugPrinter,
            toolchain.execConstraints(),
            /* isTargetPlatform= */ false,
            executionPlatform,
            toolchain.targetLabel(),
            toolchain.resolvedToolchainLabel())) {
          // Keep looking for a valid toolchain for this exec platform
          done = false;
          continue;
        }

        debugPrinter.reportCompatibleExecutionPlatform(executionPlatformKey.getLabel());
        builder.put(executionPlatformKey, toolchain.resolvedToolchainLabel());
        platformKeysSeen.add(executionPlatformKey);
      }

      if (done) {
        debugPrinter.reportDone(toolchainType.toolchainType());
        break;
      }
    }

    ImmutableMap<ConfiguredTargetKey, Label> resolvedToolchainLabels = builder.buildOrThrow();
    debugPrinter.reportResolvedToolchains(
        resolvedToolchainLabels, targetPlatform.label(), toolchainType.toolchainType());

    return SingleToolchainResolutionValue.create(toolchainTypeInfo, resolvedToolchainLabels);
  }

  /**
   * Returns {@code true} iff all constraints set by the toolchain and in the {@link PlatformInfo}
   * match.
   */
  private static boolean checkConstraints(
      SingleToolchainResolutionDebugPrinter debugPrinter,
      ConstraintCollection toolchainConstraints,
      boolean isTargetPlatform,
      PlatformInfo platform,
      Label targetLabel,
      Label resolvedToolchainLabel) {

    // Check every constraint_setting in either the toolchain or the platform.
    ImmutableSet<ConstraintSettingInfo> mismatchSettings =
        toolchainConstraints.diff(platform.constraints());

    // If a constraint_setting has a default_constraint_value, and the platform
    // sets a non-default constraint value for the same constraint_setting, then
    // even toolchains with no reference to that constraint_setting will detect
    // a mismatch here. This manifests as a toolchain resolution failure (#8778).
    //
    // To allow combining rulesets with their own toolchains in a single top-level
    // workspace, toolchains that do not reference a constraint_setting should not
    // be forced to match with it.
    ImmutableSet<ConstraintSettingInfo> mismatchSettingsWithDefault =
        mismatchSettings.stream()
            .filter(toolchainConstraints::hasWithoutDefault)
            .collect(ImmutableSet.toImmutableSet());

    debugPrinter.reportMismatchedSettings(
        toolchainConstraints,
        isTargetPlatform,
        platform,
        targetLabel,
        resolvedToolchainLabel,
        mismatchSettingsWithDefault);

    return mismatchSettingsWithDefault.isEmpty();
  }

  /**
   * Exception used when there was some issue with the toolchain making it impossible to resolve
   * constraints.
   */
  static final class InvalidConfigurationDuringToolchainResolutionException
      extends ToolchainException {
    InvalidConfigurationDuringToolchainResolutionException(InvalidConfigurationException e) {
      super(e);
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_CONSTRAINT_VALUE;
    }
  }

  /**
   * Used to indicate errors during the computation of an {@link SingleToolchainResolutionValue}.
   */
  private static final class ToolchainResolutionFunctionException extends SkyFunctionException {
    ToolchainResolutionFunctionException(InvalidToolchainLabelException e) {
      super(e, Transience.PERSISTENT);
    }

    ToolchainResolutionFunctionException(InvalidPlatformException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
