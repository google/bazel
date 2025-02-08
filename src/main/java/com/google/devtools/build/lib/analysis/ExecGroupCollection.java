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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;

import com.google.auto.value.AutoValue;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AbstractSaneAnalysisException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/**
 * A container class for groups of {@link ExecGroup} instances. This correctly handles exec group
 * inheritance between rules and targets. See https://bazel.build/reference/exec-groups for further
 * details.
 */
@AutoValue
public abstract class ExecGroupCollection {
  /**
   * Prepares the input exec groups to serve as {@link Builder#execGroups}.
   *
   * <p>Adds auto exec groups when {@code useAutoExecGroups} is true.
   */
  public static ImmutableMap<String, ExecGroup> process(
      ImmutableMap<String, ExecGroup> execGroups,
      ImmutableSet<Label> defaultExecWith,
      ImmutableMultimap<String, Label> execGroupExecWith,
      ImmutableSet<ToolchainTypeRequirement> defaultToolchainTypes,
      boolean useAutoExecGroups) {
    var processedGroups =
        ImmutableMap.<String, ExecGroup>builderWithExpectedSize(
            useAutoExecGroups
                ? (execGroups.size() + defaultToolchainTypes.size())
                : execGroups.size());
    for (Map.Entry<String, ExecGroup> entry : execGroups.entrySet()) {
      String name = entry.getKey();
      ExecGroup execGroup = entry.getValue();

      if (execGroup.copyFromDefault()) {
          execGroup =
              ExecGroup.builder()
                  .execCompatibleWith(defaultExecWith)
                  .toolchainTypes(defaultToolchainTypes)
                  .build();
      }
      ImmutableCollection<Label> extraExecWith = execGroupExecWith.get(name);
      if (!extraExecWith.isEmpty()) {
        execGroup =
            execGroup.toBuilder()
                .execCompatibleWith(
                    ImmutableSet.<Label>builder()
                        .addAll(execGroup.execCompatibleWith())
                        .addAll(extraExecWith)
                        .build())
                .build();
      }

      processedGroups.put(name, execGroup);
    }

    if (useAutoExecGroups) {
      // Creates one exec group for each toolchain (automatic exec groups).
      for (ToolchainTypeRequirement toolchainType : defaultToolchainTypes) {
        ImmutableSet<Label> execCompatibleWith = defaultExecWith;
        ImmutableCollection<Label> extraExecWith =
            execGroupExecWith.get(toolchainType.toolchainType().getUnambiguousCanonicalForm());
        if (!extraExecWith.isEmpty()) {
          execCompatibleWith =
              ImmutableSet.<Label>builder().addAll(defaultExecWith).addAll(extraExecWith).build();
        }
        processedGroups.put(
            toolchainType.toolchainType().toString(),
            ExecGroup.builder()
                .addToolchainType(toolchainType)
                .execCompatibleWith(execCompatibleWith)
                .build());
      }
    }
    return processedGroups.buildOrThrow();
  }

  /** Builder class for correctly constructing ExecGroupCollection instances. */
  // Note that this is _not_ an actual @AutoValue.Builder: it provides more logic and has different
  // fields.
  public abstract static class Builder {
    public abstract ImmutableMap<String, ExecGroup> execGroups();

    public ExecGroupCollection build(
        @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
        ImmutableMap<String, String> rawExecProperties)
        throws InvalidExecGroupException {

      // For each exec group, compute the combined execution properties.
      ImmutableTable<String, String, String> combinedExecProperties =
          computeCombinedExecProperties(toolchainContexts, rawExecProperties);

      return new AutoValue_ExecGroupCollection(execGroups(), combinedExecProperties);
    }
  }

  /**
   * Gets the combined exec properties of the platform and the target's exec properties. If a
   * property is set in both, the target properties take precedence.
   */
  private static ImmutableTable<String, String, String> computeCombinedExecProperties(
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      ImmutableMap<String, String> rawExecProperties)
      throws InvalidExecGroupException {

    ImmutableSet<String> execGroupNames;
    if (toolchainContexts == null) {
      execGroupNames = ImmutableSet.of(DEFAULT_EXEC_GROUP_NAME);
    } else {
      execGroupNames = toolchainContexts.getExecGroupNames();
    }

    // Parse the target-level exec properties.
    ImmutableTable<String, String, String> parsedTargetProperties =
        parseExecProperties(rawExecProperties);
    // Validate the exec group names in the properties.
    if (toolchainContexts != null) {
      ImmutableSet<String> unknownTargetExecGroupNames =
          parsedTargetProperties.rowKeySet().stream()
              .filter(name -> !name.equals(DEFAULT_EXEC_GROUP_NAME))
              .filter(name -> !execGroupNames.contains(name))
              .collect(toImmutableSet());
      if (!unknownTargetExecGroupNames.isEmpty()) {
        throw new InvalidExecGroupException(
            "exec_properties",
            unknownTargetExecGroupNames,
            Iterables.concat(execGroupNames, ImmutableSet.of(DEFAULT_EXEC_GROUP_NAME)));
      }
    }

    // Parse each execution platform's exec properties.
    ImmutableSet<PlatformInfo> executionPlatforms;
    if (toolchainContexts == null) {
      executionPlatforms = ImmutableSet.of();
    } else {
      executionPlatforms =
          execGroupNames.stream()
              .map(name -> toolchainContexts.getToolchainContext(name).executionPlatform())
              .distinct()
              .collect(toImmutableSet());
    }
    Map<PlatformInfo, ImmutableTable<String, String, String>> parsedPlatformProperties =
        new LinkedHashMap<>();
    for (PlatformInfo executionPlatform : executionPlatforms) {
      ImmutableTable<String, String, String> parsed =
          parseExecProperties(executionPlatform.execProperties());
      parsedPlatformProperties.put(executionPlatform, parsed);
    }

    // First, get the defaults.
    ImmutableMap<String, String> defaultExecProperties =
        parsedTargetProperties.row(DEFAULT_EXEC_GROUP_NAME);
    Table<String, String, String> result = HashBasedTable.create();
    putAll(result, DEFAULT_EXEC_GROUP_NAME, defaultExecProperties);

    for (String execGroupName : execGroupNames) {
      ImmutableMap<String, String> combined =
          computeProperties(
              execGroupName,
              defaultExecProperties,
              toolchainContexts,
              parsedPlatformProperties,
              parsedTargetProperties);
      putAll(result, execGroupName, combined);
    }

    return ImmutableTable.copyOf(result);
  }

  private static <R, C, V> void putAll(Table<R, C, V> builder, R row, Map<C, V> values) {
    for (Map.Entry<C, V> entry : values.entrySet()) {
      builder.put(row, entry.getKey(), entry.getValue());
    }
  }

  private static ImmutableMap<String, String> computeProperties(
      String execGroupName,
      ImmutableMap<String, String> defaultExecProperties,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      Map<PlatformInfo, ImmutableTable<String, String, String>> parsedPlatformProperties,
      ImmutableTable<String, String, String> parsedTargetProperties) {

    ImmutableMap<String, String> defaultExecGroupPlatformProperties;
    ImmutableMap<String, String> platformProperties;
    if (toolchainContexts == null) {
      defaultExecGroupPlatformProperties = ImmutableMap.of();
      platformProperties = ImmutableMap.of();
    } else {
      PlatformInfo executionPlatform =
          toolchainContexts.getToolchainContext(execGroupName).executionPlatform();
      defaultExecGroupPlatformProperties =
          parsedPlatformProperties.get(executionPlatform).row(DEFAULT_EXEC_GROUP_NAME);
      platformProperties = parsedPlatformProperties.get(executionPlatform).row(execGroupName);
    }
    Map<String, String> targetProperties =
        new LinkedHashMap<>(parsedTargetProperties.row(execGroupName));
    for (String propertyName : defaultExecProperties.keySet()) {
      // If the property exists in the default and not in the target, copy it.
      targetProperties.computeIfAbsent(propertyName, defaultExecProperties::get);
    }

    // Combine the target and exec platform properties. Target properties take precedence.
    // Use a HashMap instead of an ImmutableMap.Builder because we expect duplicate keys.
    Map<String, String> combined = new LinkedHashMap<>();
    combined.putAll(defaultExecGroupPlatformProperties);
    combined.putAll(defaultExecProperties);
    combined.putAll(platformProperties);
    combined.putAll(targetProperties);
    return ImmutableMap.copyOf(combined);
  }

  protected abstract ImmutableMap<String, ExecGroup> execGroups();

  protected abstract ImmutableTable<String, String, String> execProperties();

  public ExecGroup getExecGroup(String execGroupName) {
    return execGroups().get(execGroupName);
  }

  public ImmutableMap<String, String> getExecProperties(String execGroupName) {
    return execProperties().row(execGroupName);
  }

  /**
   * Parse raw exec properties attribute value into a map of exec group names to their properties.
   * The raw map can have keys of two forms: (1) 'property' and (2) 'exec_group_name.property'. The
   * former get parsed into the default exec group, the latter get parsed into their relevant exec
   * groups.
   */
  private static ImmutableTable<String, String, String> parseExecProperties(
      Map<String, String> rawExecProperties) {
    ImmutableTable.Builder<String, String, String> execProperties = ImmutableTable.builder();
    for (Map.Entry<String, String> execProperty : rawExecProperties.entrySet()) {
      String rawProperty = execProperty.getKey();
      int delimiterIndex = rawProperty.indexOf('.');
      if (delimiterIndex == -1) {
        execProperties.put(DEFAULT_EXEC_GROUP_NAME, rawProperty, execProperty.getValue());
      } else {
        String execGroup = rawProperty.substring(0, delimiterIndex);
        String property = rawProperty.substring(delimiterIndex + 1);
        execProperties.put(execGroup, property, execProperty.getValue());
      }
    }
    return execProperties.buildOrThrow();
  }

  /** An error for when the user tries to access a non-existent exec group. */
  public static final class InvalidExecGroupException extends AbstractSaneAnalysisException {

    public InvalidExecGroupException(
        String what, Collection<String> invalidNames, Iterable<String> validNames) {
      super(
          String.format(
              "Tried to set %s for non-existent exec groups: %s%s",
              what,
              String.join(",", invalidNames),
              invalidNames.stream()
                  .map(invalidName -> SpellChecker.didYouMean(invalidName, validNames))
                  .filter(s -> !s.isEmpty())
                  .findFirst()
                  .orElse("")));
    }

    @Override
    public DetailedExitCode getDetailedExitCode() {
      return DetailedExitCode.of(
          FailureDetail.newBuilder()
              .setMessage(getMessage())
              .setAnalysis(Analysis.newBuilder().setCode(Code.EXEC_GROUP_MISSING))
              .build());
    }
  }
}
