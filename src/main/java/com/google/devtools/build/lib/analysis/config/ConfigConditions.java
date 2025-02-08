// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;

/**
 * Utility class for temporarily tracking {@code select()} keys' {@link ConfigMatchingProvider}s and
 * {@link ConfiguredTarget}s.
 *
 * <p>This is a utility class because its only purpose is to maintain {@link ConfiguredTarget} long
 * enough for {@link RuleContext.Builder} to do prerequisite validation on it (for example,
 * visibility checks).
 *
 * <p>Once {@link RuleContext} is instantiated, it should only have access to {@link
 * ConfigMatchingProvider}, on the principle that providers are the correct interfaces for storing
 * and sharing target metadata. {@link ConfiguredTarget} isn't meant to persist that long.
 */
public record ConfigConditions(
    ImmutableMap<Label, ConfiguredTargetAndData> asConfiguredTargets,
    ImmutableMap<Label, ConfigMatchingProvider> asProviders) {
  public ConfigConditions {
    requireNonNull(asConfiguredTargets, "asConfiguredTargets");
    requireNonNull(asProviders, "asProviders");
  }

  public static ConfigConditions create(
      ImmutableMap<Label, ConfiguredTargetAndData> asConfiguredTargets,
      ImmutableMap<Label, ConfigMatchingProvider> asProviders) {
    return new ConfigConditions(asConfiguredTargets, asProviders);
  }

  public static final ConfigConditions EMPTY =
      ConfigConditions.create(ImmutableMap.of(), ImmutableMap.of());

  /** Exception for when a {@code select()} has an invalid key (for example, wrong target type). */
  public static class InvalidConditionException extends Exception {}

  /**
   * Returns a {@link ConfigMatchingProvider} from the given configured target if appropriate, else
   * triggers a {@link InvalidConditionException}.
   *
   * <p>This is the canonical place to extract {@link ConfigMatchingProvider}s from configured
   * targets. It's not as simple as {@link ConfiguredTarget#getProvider}.
   */
  public static ConfigMatchingProvider fromConfiguredTarget(
      ConfiguredTargetAndData selectKey, PlatformInfo targetPlatform)
      throws InvalidConditionException {
    ConfiguredTarget selectable = selectKey.getConfiguredTarget();
    // The below handles config_setting (which natively provides ConfigMatchingProvider) and
    // constraint_value (which needs a custom-built ConfigMatchingProvider).
    ConfigMatchingProvider matchingProvider = selectable.getProvider(ConfigMatchingProvider.class);
    if (matchingProvider != null) {
      return matchingProvider;
    }
    ConstraintValueInfo constraintValueInfo = selectable.get(ConstraintValueInfo.PROVIDER);
    if (constraintValueInfo != null && targetPlatform != null) {
      // If platformInfo == null, that means the owning target doesn't invoke toolchain
      // resolution, in which case depending on a constraint_value is nonsensical.
      return constraintValueInfo.configMatchingProvider(targetPlatform);
    }

    // Not a valid provider for configuration conditions.
    throw new InvalidConditionException();
  }
}
