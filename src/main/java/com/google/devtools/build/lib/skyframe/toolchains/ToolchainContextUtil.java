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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.producers.UnloadedToolchainContextsInputs;
import com.google.devtools.build.lib.analysis.starlark.StarlarkExecGroupCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import java.util.List;
import javax.annotation.Nullable;

/** Utility methods for toolchain context creation. */
public final class ToolchainContextUtil {

  public static UnloadedToolchainContextsInputs getUnloadedToolchainContextsInputs(
      Target target,
      CoreOptions coreOptions,
      PlatformConfiguration platformConfig,
      @Nullable Label parentExecutionPlatformLabel,
      BuildConfigurationKey toolchainConfigurationKey)
      throws ExecGroupCollection.InvalidExecGroupException {
    Rule rule = target.getAssociatedRule();
    if (rule == null) {
      return UnloadedToolchainContextsInputs.empty();
    }

    var ruleClass = rule.getRuleClassObject();
    boolean useAutoExecGroups =
        ruleClass
            .getAutoExecGroupsMode()
            .isEnabled(RawAttributeMapper.of(rule), coreOptions.useAutoExecGroups);

    ImmutableSet<ToolchainTypeRequirement> toolchainTypes = ruleClass.getToolchainTypes();
    if (!ruleClass.isStarlark() && ruleClass.getName().equals("genrule")) {
      // Override the toolchain types based on the target-level "toolchains" attribute.
      toolchainTypes = updateToolchainTypesFromAttribute(rule, toolchainTypes);
    }

    var defaultExecConstraintLabels = getExecutionPlatformConstraints(rule, platformConfig);
    var perExecGroupExecConstraintLabels =
        getPerExecGroupExecutionPlatformConstraints(
            rule, platformConfig, toolchainTypes, useAutoExecGroups);

    var processedExecGroups =
        ExecGroupCollection.process(
            ruleClass.getExecGroups(),
            defaultExecConstraintLabels,
            perExecGroupExecConstraintLabels,
            toolchainTypes,
            useAutoExecGroups);

    if (platformConfig == null || !rule.useToolchainResolution()) {
      return UnloadedToolchainContextsInputs.create(
          processedExecGroups, /* targetToolchainContextKey= */ null);
    }

    return UnloadedToolchainContextsInputs.create(
        processedExecGroups,
        createDefaultToolchainContextKey(
            toolchainConfigurationKey,
            defaultExecConstraintLabels,
            /* debugTarget= */ platformConfig.debugToolchainResolution(rule.getLabel()),
            /* useAutoExecGroups= */ useAutoExecGroups,
            toolchainTypes,
            parentExecutionPlatformLabel));
  }

  private static ImmutableSet<ToolchainTypeRequirement> updateToolchainTypesFromAttribute(
      Rule rule, ImmutableSet<ToolchainTypeRequirement> toolchainTypes) {
    NonconfigurableAttributeMapper attributes = NonconfigurableAttributeMapper.of(rule);
    List<Label> targetToolchainTypes = attributes.get("toolchains", BuildType.LABEL_LIST);
    if (targetToolchainTypes.isEmpty()) {
      // No need to update.
      return toolchainTypes;
    }

    ImmutableSet.Builder<ToolchainTypeRequirement> updatedToolchainTypes =
        new ImmutableSet.Builder<>();
    updatedToolchainTypes.addAll(toolchainTypes);
    targetToolchainTypes.stream()
        .map(
            toolchainTypeLabel ->
                ToolchainTypeRequirement.builder(toolchainTypeLabel)
                    .mandatory(false)
                    // Some of these may be template variables, not toolchain types, and so should
                    // be silently ignored.
                    .ignoreIfInvalid(true)
                    .build())
        .forEach(updatedToolchainTypes::add);
    return updatedToolchainTypes.build();
  }

  public static ToolchainContextKey createDefaultToolchainContextKey(
      BuildConfigurationKey configurationKey,
      ImmutableSet<Label> defaultExecConstraintLabels,
      boolean debugTarget,
      boolean useAutoExecGroups,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      @Nullable Label parentExecutionPlatformLabel) {
    ToolchainContextKey.Builder toolchainContextKeyBuilder =
        ToolchainContextKey.key()
            .configurationKey(configurationKey)
            .execConstraintLabels(defaultExecConstraintLabels)
            .debugTarget(debugTarget);

    // Add toolchain types only if automatic exec groups are not created for this target.
    if (!useAutoExecGroups) {
      toolchainContextKeyBuilder.toolchainTypes(toolchainTypes);
    }

    if (parentExecutionPlatformLabel != null) {
      // Find out what execution platform the parent used, and force that.
      // This should only be set for direct toolchain dependencies.
      toolchainContextKeyBuilder.forceExecutionPlatform(parentExecutionPlatformLabel);
    }
    return toolchainContextKeyBuilder.build();
  }

  /**
   * Returns the target-specific execution platform constraints, based on the rule definition and
   * any constraints added by the target, including those added for the target on the command line.
   */
  private static ImmutableSet<Label> getExecutionPlatformConstraints(
      Rule rule, @Nullable PlatformConfiguration platformConfiguration) {
    if (platformConfiguration == null) {
      return ImmutableSet.of(); // See NoConfigTransition.
    }
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    ImmutableSet.Builder<Label> execConstraintLabels = new ImmutableSet.Builder<>();

    execConstraintLabels.addAll(rule.getRuleClassObject().getExecutionPlatformConstraints());
    if (rule.getRuleClassObject()
        .hasAttr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)) {
      execConstraintLabels.addAll(
          mapper.get(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST));
    }

    execConstraintLabels.addAll(
        platformConfiguration.getAdditionalExecutionConstraintsFor(rule.getLabel()));

    return execConstraintLabels.build();
  }

  private static ImmutableMultimap<String, Label> getPerExecGroupExecutionPlatformConstraints(
      Rule rule,
      @Nullable PlatformConfiguration platformConfiguration,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      boolean useAutoExecGroups)
      throws ExecGroupCollection.InvalidExecGroupException {
    if (platformConfiguration == null) {
      return ImmutableMultimap.of(); // See NoConfigTransition.
    }
    if (!rule.getRuleClassObject()
        .hasAttr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)) {
      return ImmutableMultimap.of();
    }
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    ImmutableMultimap.Builder<String, Label> execGroupConstraints = ImmutableMultimap.builder();

    var packageContext =
        Label.PackageContext.of(
            rule.getPackage().getPackageIdentifier(), rule.getPackage().getRepositoryMapping());
    for (var entry :
        mapper
            .get(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
            .entrySet()) {
      String canonicalKey;
      if (StarlarkExecGroupCollection.isValidGroupName(entry.getKey())) {
        canonicalKey = entry.getKey();
        if (!rule.getRuleClassObject().getExecGroups().containsKey(canonicalKey)) {
          throw new ExecGroupCollection.InvalidExecGroupException(
              "execution constraints",
              ImmutableSet.of(canonicalKey),
              rule.getRuleClassObject().getExecGroups().keySet());
        }
      } else if (useAutoExecGroups) {
        Label label;
        try {
          label = Label.parseWithPackageContext(entry.getKey(), packageContext);
        } catch (LabelSyntaxException e) {
          throw new ExecGroupCollection.InvalidExecGroupException(
              "execution constraints", ImmutableSet.of(entry.getKey()), ImmutableSet.of());
        }
        if (toolchainTypes.stream()
            .map(ToolchainTypeRequirement::toolchainType)
            .noneMatch(label::equals)) {
          ImmutableSet<String> suggestedLabels = ImmutableSet.of();
          // TODO: Generalize Label#getDisplayForm to accept non-main repo mappings.
          if (rule.getLabel().getRepository().isMain()) {
            suggestedLabels =
                toolchainTypes.stream()
                    .map(ToolchainTypeRequirement::toolchainType)
                    .map(type -> type.getDisplayForm(rule.getPackage().getRepositoryMapping()))
                    .collect(toImmutableSet());
          }
          throw new ExecGroupCollection.InvalidExecGroupException(
              "execution constraints", ImmutableSet.of(entry.getKey()), suggestedLabels);
        }
        canonicalKey = label.getUnambiguousCanonicalForm();
      } else {
        throw new ExecGroupCollection.InvalidExecGroupException(
            "execution constraints", ImmutableSet.of(entry.getKey()), ImmutableSet.of());
      }
      execGroupConstraints.putAll(canonicalKey, entry.getValue());
    }

    return execGroupConstraints.build();
  }

  private ToolchainContextUtil() {}
}
