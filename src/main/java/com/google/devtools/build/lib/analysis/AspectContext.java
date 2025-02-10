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

package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Extends {@link RuleContext} to provide all data available during the analysis of an aspect. */
public final class AspectContext extends RuleContext {

  /**
   * A list of all aspects applied to the target.
   *
   * <p>The last aspect in the list is the main aspect that this context is for.
   */
  private final ImmutableList<Aspect> aspects;

  private final ImmutableList<AspectDescriptor> aspectDescriptors;

  private final PrerequisitesCollection mainAspectPrerequisites;

  /**
   * The toolchain contexts for the base target.
   *
   * <p>It only contains the providers created by the aspects that propagate to the toolchains.
   */
  @Nullable
  private final ToolchainCollection<AspectBaseTargetResolvedToolchainContext>
      baseTargetToolchainContexts;

  /** Whether the target uses auto exec groups. */
  private final boolean targetUsesAutoExecGroups;

  /** The make variables for the base target. */
  @Nullable
  private ConfigurationMakeVariableContext baseTargetConfigurationMakeVariableContext = null;

  AspectContext(
      RuleContext.Builder builder,
      AspectAwareAttributeMapper aspectAwareAttributeMapper,
      PrerequisitesCollection ruleAndBaseAspectsPrerequisites,
      PrerequisitesCollection mainAspectPrerequisites,
      ExecGroupCollection execGroupCollection,
      @Nullable
          ToolchainCollection<AspectBaseTargetResolvedToolchainContext> baseTargetToolchainContexts,
      boolean targetUsesAutoExecGroups) {
    super(
        builder, aspectAwareAttributeMapper, ruleAndBaseAspectsPrerequisites, execGroupCollection);

    this.aspects = builder.getAspects();
    this.aspectDescriptors = aspects.stream().map(Aspect::getDescriptor).collect(toImmutableList());
    this.mainAspectPrerequisites = mainAspectPrerequisites;
    this.baseTargetToolchainContexts = baseTargetToolchainContexts;
    this.targetUsesAutoExecGroups = targetUsesAutoExecGroups;
  }

  /**
   * Returns the toolchain contexts for the base target. Can be null if no aspect in the {@code
   * aspects} path propagate to the toolchains.
   */
  @Nullable
  public ToolchainCollection<AspectBaseTargetResolvedToolchainContext>
      getBaseTargetToolchainContexts() {
    return baseTargetToolchainContexts;
  }

  /** Returns the labels of default the toolchain types that aspects have propagated. */
  public ImmutableSet<Label> getRequestedToolchainTypesLabels() {
    if (targetUsesAutoExecGroups) {
      return baseTargetToolchainContexts.contextMap().entrySet().stream()
          .filter(e -> ExecGroup.isAutomatic(e.getKey()))
          .flatMap(e -> e.getValue().requestedToolchainTypeLabels().keySet().stream())
          .collect(toImmutableSet());
    } else {
      return baseTargetToolchainContexts
          .getDefaultToolchainContext()
          .requestedToolchainTypeLabels()
          .keySet();
    }
  }

  /**
   * Returns the toolchain data for the given type, or {@code null} if the toolchain type was not
   * required in this context.
   */
  @Nullable
  public AspectBaseTargetResolvedToolchainContext.ToolchainAspectsProviders getToolchainTarget(
      Label toolchainType) {
    var execGroupContext = baseTargetToolchainContexts.getDefaultToolchainContext();
    if (targetUsesAutoExecGroups) {
      execGroupContext =
          baseTargetToolchainContexts.contextMap().entrySet().stream()
              .filter(
                  e ->
                      ExecGroup.isAutomatic(e.getKey())
                          && e.getValue().requestedToolchainTypeLabels().containsKey(toolchainType))
              .findFirst()
              .map(e -> e.getValue())
              .orElse(null);
      if (execGroupContext == null) {
        return null;
      }
    }
    return execGroupContext
        .toolchains()
        .get(execGroupContext.requestedToolchainTypeLabels().get(toolchainType));
  }

  /**
   * Merge the attributes of the aspects in the aspects path.
   *
   * <p>For attributes with the same name, the one that is first encountered takes precedence.
   */
  private static ImmutableMap<String, Attribute> mergeAspectsAttributes(
      ImmutableList<Aspect> aspects) {
    if (aspects.isEmpty()) {
      return ImmutableMap.of();
    } else if (aspects.size() == 1) {
      return aspects.get(0).getDefinition().getAttributes();
    } else {
      LinkedHashMap<String, Attribute> aspectAttributes = new LinkedHashMap<>();
      for (Aspect aspect : aspects) {
        ImmutableMap<String, Attribute> currentAttributes = aspect.getDefinition().getAttributes();
        for (Map.Entry<String, Attribute> kv : currentAttributes.entrySet()) {
          aspectAttributes.putIfAbsent(kv.getKey(), kv.getValue());
        }
      }
      return ImmutableMap.copyOf(aspectAttributes);
    }
  }

  static AspectContext create(
      Builder builder,
      AttributeMap ruleAttributes,
      ImmutableListMultimap<DependencyKind, ConfiguredTargetAndData> targetsMap,
      ExecGroupCollection execGroupCollection,
      ToolchainCollection<AspectBaseTargetResolvedToolchainContext> baseTargetToolchainContexts) {
    return createAspectContextWithSeparatedPrerequisites(
        builder, ruleAttributes, targetsMap, execGroupCollection, baseTargetToolchainContexts);
  }

  /**
   * Create prerequisites collection for aspect evaluation separating the main aspect prerequisites
   * from the underlying rule and base aspects prerequisites.
   */
  private static AspectContext createAspectContextWithSeparatedPrerequisites(
      RuleContext.Builder ruleContextBuilder,
      AttributeMap ruleAttributes,
      ImmutableListMultimap<DependencyKind, ConfiguredTargetAndData> prerequisitesMap,
      ExecGroupCollection execGroupCollection,
      ToolchainCollection<AspectBaseTargetResolvedToolchainContext> baseTargetToolchainContexts) {
    ImmutableSortedKeyListMultimap.Builder<String, ConfiguredTargetAndData>
        mainAspectPrerequisites = ImmutableSortedKeyListMultimap.builder();
    ImmutableSortedKeyListMultimap.Builder<String, ConfiguredTargetAndData>
        ruleAndBaseAspectsPrerequisites = ImmutableSortedKeyListMultimap.builder();

    Aspect mainAspect = Iterables.getLast(ruleContextBuilder.getAspects());

    for (Map.Entry<DependencyKind, Collection<ConfiguredTargetAndData>> entry :
        prerequisitesMap.asMap().entrySet()) {
      String attributeName = entry.getKey().getAttribute().getName();

      if (mainAspect.getAspectClass().equals(entry.getKey().getOwningAspect())) {
        mainAspectPrerequisites.putAll(attributeName, entry.getValue());
      } else {
        ruleAndBaseAspectsPrerequisites.putAll(attributeName, entry.getValue());
      }
    }

    boolean targetUsesAutoExecGroups =
        ruleContextBuilder
            .getRule()
            .getRuleClassObject()
            .getAutoExecGroupsMode()
            .isEnabled(ruleAttributes, ruleContextBuilder.getConfiguration().useAutoExecGroups());

    return new AspectContext(
        ruleContextBuilder,
        new AspectAwareAttributeMapper(
            ruleAttributes, mergeAspectsAttributes(ruleContextBuilder.getAspects())),
        new PrerequisitesCollection(
            ruleAndBaseAspectsPrerequisites.build(),
            mergeRuleAndBaseAspectsAttributes(ruleAttributes, ruleContextBuilder.getAspects()),
            ruleContextBuilder.getErrorConsumer(),
            ruleContextBuilder.getRule(),
            ruleContextBuilder.getRuleClassNameForLogging()),
        new PrerequisitesCollection(
            mainAspectPrerequisites.build(),
            mainAspect.getDefinition().getAttributes(),
            ruleContextBuilder.getErrorConsumer(),
            ruleContextBuilder.getRule(),
            ruleContextBuilder.getRuleClassNameForLogging()),
        execGroupCollection,
        baseTargetToolchainContexts,
        targetUsesAutoExecGroups);
  }

  private static AspectAwareAttributeMapper mergeRuleAndBaseAspectsAttributes(
      AttributeMap ruleAttributes, ImmutableList<Aspect> aspects) {
    LinkedHashMap<String, Attribute> mergedBaseAspectsAttributes = new LinkedHashMap<>();
    for (int i = 0; i < aspects.size() - 1; i++) {
      for (Attribute attribute : aspects.get(i).getDefinition().getAttributes().values()) {
        mergedBaseAspectsAttributes.putIfAbsent(attribute.getName(), attribute);
      }
    }
    return new AspectAwareAttributeMapper(
        ruleAttributes, ImmutableMap.copyOf(mergedBaseAspectsAttributes));
  }

  @Override
  PrerequisitesCollection getOwningPrerequisitesCollection(String attributeName) {
    if (mainAspectPrerequisites.has(attributeName)) {
      return mainAspectPrerequisites;
    }
    return getRulePrerequisitesCollection();
  }

  public PrerequisitesCollection getMainAspectPrerequisitesCollection() {
    return mainAspectPrerequisites;
  }

  @Override
  public ImmutableList<Aspect> getAspects() {
    return aspects;
  }

  /**
   * Return the main aspect of this context.
   *
   * <p>It is the last aspect in the list of aspects applied to a target; all other aspects are the
   * ones main aspect sees as specified by its "required_aspect_providers").
   */
  @Override
  public Aspect getMainAspect() {
    return Iterables.getLast(aspects);
  }

  /** All aspects applied to the rule. */
  @Override
  public ImmutableList<AspectDescriptor> getAspectDescriptors() {
    return aspectDescriptors;
  }

  @Override
  public boolean useAutoExecGroups() {
    // TODO: b/370558813 - Use AutoExecGroupsMode for aspects, as well.
    ImmutableMap<String, Attribute> aspectAttributes =
        getMainAspect().getDefinition().getAttributes();
    if (aspectAttributes.containsKey("$use_auto_exec_groups")) {
      return (boolean) aspectAttributes.get("$use_auto_exec_groups").getDefaultValueUnchecked();
    } else {
      return getConfiguration().useAutoExecGroups();
    }
  }

  @Override
  public ImmutableList<? extends TransitiveInfoCollection> getAllPrerequisites() {
    return Streams.concat(
            mainAspectPrerequisites.getAllPrerequisites().stream(),
            getRulePrerequisitesCollection().getAllPrerequisites().stream())
        .collect(toImmutableList());
  }

  ImmutableList<TemplateVariableInfo> getTemplateVariablesFromAspectAttributes(
      Iterable<String> attributeNames) {
    // Get template variable providers from the attributes.
    return Streams.stream(attributeNames)
        // Only process this attribute it if is present in the aspect directly.
        .filter(attrName -> this.getMainAspectPrerequisitesCollection().has(attrName))
        // Get the TemplateVariableInfo providers from this attribute.
        .flatMap(
            attrName ->
                this.getMainAspectPrerequisitesCollection()
                    .getPrerequisites(attrName, TemplateVariableInfo.PROVIDER)
                    .stream())
        .collect(toImmutableList());
  }

  ImmutableList<TemplateVariableInfo> getTemplateVariablesFromBaseRuleAttributes(
      Iterable<String> attributeNames) {
    // Get template variable providers from the attributes.
    return Streams.stream(attributeNames)
        // Only process this attribute it if is present in the target directly.
        .filter(attrName -> this.getRulePrerequisitesCollection().has(attrName))
        // Get the TemplateVariableInfo providers from this attribute.
        .flatMap(
            attrName ->
                this.getRulePrerequisitesCollection()
                    .getPrerequisites(attrName, TemplateVariableInfo.PROVIDER)
                    .stream())
        .collect(toImmutableList());
  }

  private ImmutableList<TemplateVariableInfo> getTemplateVariablesFromBaseRuleToolchains() {
    if (this.getBaseTargetToolchainContexts() == null) {
      return ImmutableList.of();
    }

    return this.getBaseTargetToolchainContexts().contextMap().values().stream()
        .flatMap(context -> context.templateVariableProviders().stream())
        .collect(toImmutableList());
  }

  @Override
  public ImmutableList<TemplateVariableInfo> getDefaultTemplateVariableProviders() {
    return new ImmutableList.Builder<TemplateVariableInfo>()
        .addAll(getTemplateVariablesFromAspectAttributes(DEFAULT_MAKE_VARIABLE_ATTRIBUTES))
        .addAll(fromToolchains())
        .build();
  }

  /**
   * Returns the {@link ConfigurationMakeVariableContext} for the aspect itself, including
   * toolchains but not including the underlying target.
   */
  @Override
  public ConfigurationMakeVariableContext getConfigurationMakeVariableContext() {
    return super.getConfigurationMakeVariableContext();
  }

  /**
   * Returns the {@link ConfigurationMakeVariableContext} for the base rule, but not including the
   * aspect and its toolchains.
   */
  public ConfigurationMakeVariableContext getBaseTargetConfigurationMakeVariableContext() {
    if (baseTargetConfigurationMakeVariableContext == null) {
      ImmutableList<TemplateVariableInfo> templateVariableProviders =
          new ImmutableList.Builder<TemplateVariableInfo>()
              .addAll(getTemplateVariablesFromBaseRuleAttributes(DEFAULT_MAKE_VARIABLE_ATTRIBUTES))
              .addAll(getTemplateVariablesFromBaseRuleToolchains())
              .build();

      baseTargetConfigurationMakeVariableContext =
          new ConfigurationMakeVariableContext(
              this.getRule().getPackage(), getConfiguration(), templateVariableProviders);
    }
    return baseTargetConfigurationMakeVariableContext;
  }
}
