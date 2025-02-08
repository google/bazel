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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A helper interface for printing debug messages from single toolchain resolution. */
public sealed interface SingleToolchainResolutionDebugPrinter {
  static SingleToolchainResolutionDebugPrinter create(
      boolean debug, ExtendedEventHandler eventHandler) {
    if (debug) {
      return new EventHandlerImpl(eventHandler);
    }
    return new NoopPrinter();
  }

  void finishDebugging();

  void describeRejectedToolchains(ImmutableMap<Label, String> rejectedToolchains);

  void startToolchainResolution(Label toolchainType, Label targetPlatform);

  void reportCompatibleTargetPlatform(Label targetLabel, Label resolvedToolchainLabel);

  void reportSkippedExecutionPlatformSeen(Label executionPlatform);

  void reportSkippedExecutionPlatformDisallowed(Label executionPlatform, Label toolchainType);

  void reportCompatibleExecutionPlatform(Label executionPlatform);

  void reportResolvedToolchains(
      ImmutableMap<ConfiguredTargetKey, Label> resolvedToolchains,
      Label targetPlatform,
      Label toolchainType);

  void reportMismatchedSettings(
      ConstraintCollection toolchainConstraints,
      boolean isTargetPlatform,
      PlatformInfo platform,
      Label targetLabel,
      Label resolvedToolchainLabel,
      ImmutableSet<ConstraintSettingInfo> mismatchSettingsWithDefault);

  void reportDone(Label toolchainType);

  /** A do-nothing implementation for when debug messages are suppressed. */
  final class NoopPrinter implements SingleToolchainResolutionDebugPrinter {

    private NoopPrinter() {}

    @Override
    public void finishDebugging() {}

    @Override
    public void describeRejectedToolchains(ImmutableMap<Label, String> rejectedToolchains) {}

    @Override
    public void startToolchainResolution(Label toolchainType, Label targetPlatform) {}

    @Override
    public void reportCompatibleTargetPlatform(Label targetLabel, Label resolvedToolchainLabel) {}

    @Override
    public void reportSkippedExecutionPlatformSeen(Label executionPlatform) {}

    @Override
    public void reportSkippedExecutionPlatformDisallowed(
        Label executionPlatform, Label toolchainType) {}

    @Override
    public void reportCompatibleExecutionPlatform(Label executionPlatform) {}

    @Override
    public void reportResolvedToolchains(
        ImmutableMap<ConfiguredTargetKey, Label> resolvedToolchains,
        Label targetPlatform,
        Label toolchainType) {}

    @Override
    public void reportMismatchedSettings(
        ConstraintCollection toolchainConstraints,
        boolean isTargetPlatform,
        PlatformInfo platform,
        Label targetLabel,
        Label resolvedToolchainLabel,
        ImmutableSet<ConstraintSettingInfo> mismatchSettingsWithDefault) {}

    @Override
    public void reportDone(Label toolchainType) {}
  }

  /** Implement debug printing using the {@link ExtendedEventHandler}. */
  final class EventHandlerImpl implements SingleToolchainResolutionDebugPrinter {
    /** Helper enum to define the three indentation levels used in {@code debugMessage}. */
    private enum IndentLevel {
      TARGET_PLATFORM_LEVEL(""),
      TOOLCHAIN_LEVEL("  "),
      EXECUTION_PLATFORM_LEVEL("    ");

      final String value;

      IndentLevel(String value) {
        this.value = value;
      }

      public String indent() {
        return value;
      }
    }

    private final ExtendedEventHandler eventHandler;
    private final List<String> resolutionTrace = new ArrayList<>();

    private EventHandlerImpl(ExtendedEventHandler eventHandler) {
      this.eventHandler = eventHandler;
    }

    @FormatMethod
    private void debugMessage(IndentLevel indent, @FormatString String template, Object... args) {
      String padding = resolutionTrace.isEmpty() ? "" : " ".repeat("INFO: ".length());
      resolutionTrace.add(
          padding + "ToolchainResolution: " + indent.indent() + String.format(template, args));
    }

    @Override
    public void finishDebugging() {
      eventHandler.handle(Event.info(String.join("\n", resolutionTrace)));
    }

    @Override
    public void describeRejectedToolchains(ImmutableMap<Label, String> rejectedToolchains) {
      if (!rejectedToolchains.isEmpty()) {
        for (Map.Entry<Label, String> entry : rejectedToolchains.entrySet()) {
          Label toolchainLabel = entry.getKey();
          String message = entry.getValue();
          debugMessage(
              IndentLevel.TOOLCHAIN_LEVEL, "Rejected toolchain %s; %s", toolchainLabel, message);
        }
      }
    }

    @Override
    public void startToolchainResolution(Label toolchainType, Label targetPlatform) {
      debugMessage(
          IndentLevel.TARGET_PLATFORM_LEVEL,
          "Performing resolution of %s for target platform %s",
          toolchainType,
          targetPlatform);
    }

    @Override
    public void reportCompatibleTargetPlatform(Label targetLabel, Label resolvedToolchainLabel) {
      debugMessage(
          IndentLevel.TOOLCHAIN_LEVEL,
          "Toolchain %s (resolves to %s) is compatible with target platform, searching for"
              + " execution platforms:",
          targetLabel,
          resolvedToolchainLabel);
    }

    @Override
    public void reportSkippedExecutionPlatformSeen(Label executionPlatform) {
      debugMessage(
          IndentLevel.EXECUTION_PLATFORM_LEVEL,
          "Skipping execution platform %s; it has already selected a toolchain",
          executionPlatform);
    }

    @Override
    public void reportSkippedExecutionPlatformDisallowed(
        Label executionPlatform, Label toolchainType) {
      debugMessage(
          IndentLevel.EXECUTION_PLATFORM_LEVEL,
          "Skipping execution platform %s; its allowed toolchain types does not contain the"
              + " current toolchain type %s",
          executionPlatform,
          toolchainType);
    }

    @Override
    public void reportCompatibleExecutionPlatform(Label executionPlatform) {
      debugMessage(
          IndentLevel.EXECUTION_PLATFORM_LEVEL,
          "Compatible execution platform %s",
          executionPlatform);
    }

    @Override
    public void reportResolvedToolchains(
        ImmutableMap<ConfiguredTargetKey, Label> resolvedToolchains,
        Label targetPlatform,
        Label toolchainType) {
      if (resolvedToolchains.isEmpty()) {
        debugMessage(
            IndentLevel.TARGET_PLATFORM_LEVEL,
            "No %s toolchain found for target platform %s.",
            toolchainType,
            targetPlatform);
      } else {
        debugMessage(
            IndentLevel.TARGET_PLATFORM_LEVEL,
            "Recap of selected %s toolchains for target platform %s:",
            toolchainType,
            targetPlatform);
        resolvedToolchains.forEach(
            (executionPlatformKey, resolvedToolchainLabel) ->
                debugMessage(
                    IndentLevel.TOOLCHAIN_LEVEL,
                    "Selected %s to run on execution platform %s",
                    resolvedToolchainLabel,
                    executionPlatformKey.getLabel()));
      }
    }

    @Override
    public void reportMismatchedSettings(
        ConstraintCollection toolchainConstraints,
        boolean isTargetPlatform,
        PlatformInfo platform,
        Label targetLabel,
        Label resolvedToolchainLabel,
        ImmutableSet<ConstraintSettingInfo> mismatchSettingsWithDefault) {
      if (!mismatchSettingsWithDefault.isEmpty()) {
        String mismatchValues =
            mismatchSettingsWithDefault.stream()
                .filter(toolchainConstraints::has)
                .map(s -> toolchainConstraints.get(s).label().getName())
                .collect(joining(", "));
        if (!mismatchValues.isEmpty()) {
          mismatchValues = "; mismatching values: " + mismatchValues;
        }

        String missingSettings =
            mismatchSettingsWithDefault.stream()
                .filter(s -> !toolchainConstraints.has(s))
                .map(s -> s.label().getName())
                .collect(joining(", "));
        if (!missingSettings.isEmpty()) {
          missingSettings = "; missing: " + missingSettings;
        }
        if (isTargetPlatform) {
          debugMessage(
              IndentLevel.TOOLCHAIN_LEVEL,
              "Rejected toolchain %s (resolves to %s) %s",
              targetLabel,
              resolvedToolchainLabel,
              mismatchValues + missingSettings);
        } else {
          debugMessage(
              IndentLevel.EXECUTION_PLATFORM_LEVEL,
              "Incompatible execution platform %s%s",
              platform.label(),
              mismatchValues + missingSettings);
        }
      }
    }

    @Override
    public void reportDone(Label toolchainType) {
      debugMessage(
          IndentLevel.TOOLCHAIN_LEVEL,
          "All execution platforms have been assigned a %s toolchain, stopping",
          toolchainType);
    }
  }
}
