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

import static java.util.stream.Collectors.joining;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.skyframe.AbstractLabelCycleReporter;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Optional;

/**
 * {@link CyclesReporter.SingleCycleReporter} implementation that can handle cycles involving
 * registered execution platforms.
 */
public class RegisteredExecutionPlatformsCycleReporter
    implements CyclesReporter.SingleCycleReporter {

  private static final Predicate<SkyKey> IS_REGISTERED_EXECUTION_PLATFORMS_SKY_KEY =
      SkyFunctions.isSkyFunction(SkyFunctions.REGISTERED_EXECUTION_PLATFORMS);

  private static final Predicate<SkyKey> IS_CONFIGURED_TARGET_SKY_KEY =
      SkyFunctions.isSkyFunction(SkyFunctions.CONFIGURED_TARGET);

  private static final Predicate<SkyKey> IS_TOOLCHAIN_RESOLUTION_SKY_KEY =
      SkyFunctions.isSkyFunction(SkyFunctions.TOOLCHAIN_RESOLUTION);

  private static final Predicate<SkyKey> IS_TOOLCHAIN_RELATED =
      Predicates.or(IS_REGISTERED_EXECUTION_PLATFORMS_SKY_KEY, IS_TOOLCHAIN_RESOLUTION_SKY_KEY);

  @Override
  public boolean maybeReportCycle(
      SkyKey topLevelKey,
      CycleInfo cycleInfo,
      boolean alreadyReported,
      ExtendedEventHandler eventHandler) {
    ImmutableList<SkyKey> cycle = cycleInfo.getCycle();
    if (alreadyReported) {
      return true;
    } else if (!Iterables.any(cycle, IS_TOOLCHAIN_RELATED)) {
      return false;
    }

    // Find the ConfiguredTargetKey, this should tell the problem.
    Optional<ConfiguredTargetKey> configuredTargetKey = findRootConfiguredTarget(cycle);
    if (!configuredTargetKey.isPresent()) {
      return false;
    }

    Function<Object, String> printer =
        input -> {
          if (input instanceof ConfiguredTargetKey ctk) {
            Label label = ctk.getLabel();
            return label.toString();
          }
          if (input instanceof RegisteredExecutionPlatformsValue.Key) {
            return "RegisteredExecutionPlatforms";
          }
          if (input instanceof ToolchainContextKey toolchainContextKey) {
            String toolchainTypes =
                toolchainContextKey.toolchainTypes().stream()
                    .map(ToolchainTypeRequirement::toolchainType)
                    .map(Label::toString)
                    .collect(joining(", "));
            return String.format("toolchain types %s", toolchainTypes);
          }

          throw new UnsupportedOperationException(input.toString());
        };

    // TODO: jcater - Clean this up: ideally we only need to list the actual platform and the direct
    // dependency of it in the cycle.
    StringBuilder cycleMessage =
        new StringBuilder()
            .append("Misconfigured execution platforms: ")
            .append(printer.apply(configuredTargetKey.get()))
            .append(" is declared as a platform but has inappropriate dependencies.")
            .append(" Execution platforms should not have dependencies that themselves require")
            .append(" toolchain resolution.");

    AbstractLabelCycleReporter.printCycle(cycleInfo.getCycle(), cycleMessage, printer);
    eventHandler.handle(Event.error(null, cycleMessage.toString()));
    return true;
  }

  /**
   * Returns the first {@link SkyKey} that is an instance of {@link ConfiguredTargetKey} and follows
   * {@link RegisteredToolchainsValue.Key}. This will loop over the cycle in case the {@link
   * RegisteredToolchainsValue} is not first in the list.
   */
  private Optional<ConfiguredTargetKey> findRootConfiguredTarget(ImmutableList<SkyKey> cycle) {
    // Loop over the cycle, possibly twice, first looking for RegisteredExecutionPlatformsValue,
    // then finding the first ConfiguredTargetKey.
    boolean repvFound = false;
    for (int i = 0; i < cycle.size() * 2; i++) {
      SkyKey skyKey = cycle.get(i % cycle.size());
      if (!repvFound && IS_REGISTERED_EXECUTION_PLATFORMS_SKY_KEY.apply(skyKey)) {
        repvFound = true;
      }
      if (repvFound && IS_CONFIGURED_TARGET_SKY_KEY.apply(skyKey)) {
        return Optional.of((ConfiguredTargetKey) skyKey);
      }
    }

    return Optional.empty();
  }
}
