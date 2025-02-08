// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.analysis.DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY;
import static com.google.devtools.build.lib.analysis.DependencyKind.VISIBILITY_DEPENDENCY;

import com.google.auto.value.AutoOneOf;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.DependencyKind.AttributeDependencyKind;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.syntax.Location;

/**
 * Helpers for resolution for dependencies between configured targets.
 *
 * <p>Includes logic to determine all attribute dependencies and their associated labels.
 */
public final class DependencyResolutionHelpers {

  private DependencyResolutionHelpers() {}

  /** The tuple {@link #computeDependencyLabels} outputs. */
  public static final class DependencyLabels {
    private final OrderedSetMultimap<DependencyKind, Label> labels;
    @Nullable private final ConfiguredAttributeMapper attributeMap;

    private DependencyLabels(
        OrderedSetMultimap<DependencyKind, Label> labels,
        @Nullable ConfiguredAttributeMapper attributeMap) {
      this.labels = labels;
      this.attributeMap = attributeMap;
    }

    public OrderedSetMultimap<DependencyKind, Label> labels() {
      return labels;
    }

    @Nullable // Non-null for rules and output files when there are aspects that apply to files.
    public ConfiguredAttributeMapper attributeMap() {
      return attributeMap;
    }
  }

  public static DependencyLabels computeDependencyLabels(
      TargetAndConfiguration node,
      ImmutableList<Aspect> aspects,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      @Nullable ToolchainCollection<UnloadedToolchainContext> baseTargetUnloadedToolchainContexts)
      throws Failure, InterruptedException {
    Target target = node.getTarget();
    BuildConfigurationValue config = node.getConfiguration();
    OrderedSetMultimap<DependencyKind, Label> outgoingLabels = OrderedSetMultimap.create();

    // TODO(bazel-team): Figure out a way to implement the below using LabelVisitationUtils.
    Rule fromRule;
    ConfiguredAttributeMapper attributeMap = null;
    if (target instanceof OutputFile) {
      Preconditions.checkNotNull(config);
      addVisibilityDepLabels(target.getVisibilityDependencyLabels(), outgoingLabels);
      Rule rule = ((OutputFile) target).getGeneratingRule();
      outgoingLabels.put(OUTPUT_FILE_RULE_DEPENDENCY, rule.getLabel());
      if (Iterables.any(aspects, a -> a.getDefinition().applyToFiles())) {
        attributeMap = ConfiguredAttributeMapper.of(rule, configConditions, config);
        resolveAttributes(getAspectAttributes(aspects), outgoingLabels, rule, attributeMap, config);
      }
      addToolchainDeps(toolchainContexts, outgoingLabels);
    } else if (target instanceof InputFile || target instanceof EnvironmentGroup) {
      addVisibilityDepLabels(target.getVisibilityDependencyLabels(), outgoingLabels);
    } else if (target instanceof Rule rule) {
      fromRule = rule;
      attributeMap = ConfiguredAttributeMapper.of(fromRule, configConditions, config);
      visitRule(
          node,
          aspects,
          attributeMap,
          toolchainContexts,
          baseTargetUnloadedToolchainContexts,
          outgoingLabels);
    } else if (target instanceof PackageGroup packageGroup) {
      outgoingLabels.putAll(VISIBILITY_DEPENDENCY, packageGroup.getIncludes());
    } else {
      throw new IllegalStateException(target.getLabel().toString());
    }
    return new DependencyLabels(outgoingLabels, attributeMap);
  }

  /** The results of {@link #getExecutionPlatformLabel} as a tagged union. */
  @AutoOneOf(DependencyResolutionHelpers.ExecutionPlatformResult.Kind.class)
  public abstract static class ExecutionPlatformResult {
    /** Tags for the possible results. */
    public enum Kind {
      /** A label was successfully determined. */
      LABEL,
      /**
       * A label was successfully determined to be null.
       *
       * <p>{@link AutoOneOf} does not permit {@code @Nullable} so this is distinct from {@link
       * #LABEL}.
       */
      NULL_LABEL,
      /**
       * The dependency should be skipped.
       *
       * <p>See comments in {@link #getExecutionPlatformLabel} for details.
       */
      SKIP,
      /** An error message. */
      ERROR
    }

    public abstract Kind kind();

    public abstract Label label();

    abstract void nullLabel();

    abstract void skip();

    public abstract String error();

    private static ExecutionPlatformResult ofLabel(Label label) {
      return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.label(label);
    }

    private static ExecutionPlatformResult ofNullLabel() {
      return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.nullLabel();
    }

    private static ExecutionPlatformResult ofSkip() {
      return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.skip();
    }

    private static ExecutionPlatformResult ofError(String message) {
      return AutoOneOf_DependencyResolutionHelpers_ExecutionPlatformResult.error(message);
    }
  }

  public static ExecutionPlatformResult getExecutionPlatformLabel(
      AttributeDependencyKind kind,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      @Nullable ToolchainCollection<UnloadedToolchainContext> baseTargetUnloadedToolchainContexts,
      ImmutableList<Aspect> aspectsList) {
    if (aspectsList.isEmpty() || isMainAspect(aspectsList, kind.getOwningAspect())) {
      return getExecutionPlatformLabel(kind, toolchainContexts);
    } else if (kind.getOwningAspect() == null) {
      // During aspect evaluation, use {@code baseTargetUnloadedToolchainContexts} for the base
      // target's dependencies.
      return getExecutionPlatformLabel(kind, baseTargetUnloadedToolchainContexts);
    } else {
      ExecutionPlatformResult executionPlatformResult =
          getExecutionPlatformLabel(kind, toolchainContexts);
      if (executionPlatformResult.kind() == ExecutionPlatformResult.Kind.ERROR) {
        // TODO(b/373963347): Make the toolchain contexts of base aspects available to be used with
        // their corresponding dependencies.
        // Currently dependencies of the base aspects are resolved with the toolchain context of the
        // main aspect, skip errors as actual errors would be reported during the base aspect
        // evaluation.
        return ExecutionPlatformResult.ofSkip();
      } else {
        return executionPlatformResult;
      }
    }
  }

  private static ExecutionPlatformResult getExecutionPlatformLabel(
      AttributeDependencyKind kind,
      @Nullable ToolchainCollection<? extends ToolchainContext> toolchainContexts) {
    if (toolchainContexts == null) {
      return ExecutionPlatformResult.ofNullLabel();
    }

    TransitionFactory<AttributeTransitionData> transitionFactory =
        kind.getAttribute().getTransitionFactory();
    if (!(transitionFactory instanceof ExecutionTransitionFactory)) {
      return ExecutionPlatformResult.ofLabel(
          toolchainContexts
              .getToolchainContext(ExecGroup.DEFAULT_EXEC_GROUP_NAME)
              .executionPlatform()
              .label());
    }

    String execGroup = ((ExecutionTransitionFactory) transitionFactory).getExecGroup();
    if (toolchainContexts.hasToolchainContext(execGroup)) {
      PlatformInfo platform = toolchainContexts.getToolchainContext(execGroup).executionPlatform();
      return platform == null
          ? ExecutionPlatformResult.ofNullLabel()
          : ExecutionPlatformResult.ofLabel(platform.label());
    }

    return ExecutionPlatformResult.ofError(
        String.format(
            "Attr '%s' declares a transition for non-existent exec group '%s'",
            kind.getAttribute().getName(), execGroup));
  }

  /** True if {@code owningAspect} is the main aspect, the last one in {@code aspectsList}. */
  private static boolean isMainAspect(
      ImmutableList<Aspect> aspectsList, @Nullable AspectClass owningAspect) {
    return Iterables.getLast(aspectsList).getAspectClass().equals(owningAspect);
  }

  /** Indicates a failure during dependency resolution. */
  public static class Failure extends Exception {
    @Nullable private final Location location;

    private Failure(Location location, String message) {
      super(message);
      this.location = location;
    }

    /** Returns the location of the error, if known. */
    @Nullable
    public Location getLocation() {
      return location;
    }
  }

  private static void visitRule(
      TargetAndConfiguration node,
      ImmutableList<Aspect> aspects,
      ConfiguredAttributeMapper attributeMap,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      @Nullable ToolchainCollection<UnloadedToolchainContext> baseTargetUnloadedToolchainContexts,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels)
      throws Failure, InterruptedException {
    Preconditions.checkArgument(node.getTarget() instanceof Rule, node);
    BuildConfigurationValue ruleConfig = Preconditions.checkNotNull(node.getConfiguration(), node);
    Rule rule = (Rule) node.getTarget();

    try {
      attributeMap.validateAttributes();
    } catch (ConfiguredAttributeMapper.ValidationException ex) {
      throw new Failure(rule.getLocation(), ex.getMessage());
    }

    Iterable<Label> visibilityDepLabels = rule.getVisibilityDependencyLabels();
    addVisibilityDepLabels(visibilityDepLabels, outgoingLabels);
    resolveAttributes(getAttributes(rule, aspects), outgoingLabels, rule, attributeMap, ruleConfig);

    // Add the rule's visibility labels (which may come from the rule or from package defaults).
    addExplicitDeps(outgoingLabels, rule, "visibility", visibilityDepLabels);

    // Add package default constraints when the rule doesn't explicitly declare them.
    //
    // Note that this can have subtle implications for constraint semantics. For example: say that
    // package defaults declare compatibility with ':foo' and rule R declares compatibility with
    // ':bar'. Does that mean that R is compatible with [':foo', ':bar'] or just [':bar']? In other
    // words, did R's author intend to add additional compatibility to the package defaults or to
    // override them? More severely, what if package defaults "restrict" support to just [':baz']?
    // Should R's declaration signify [':baz'] + ['bar'], [ORIGINAL_DEFAULTS] + ['bar'], or
    // something else?
    //
    // Rather than try to answer these questions with possibly confusing logic, we take the
    // simple approach of assigning the rule's "restriction" attribute to the rule-declared value if
    // it exists, else the package defaults value (and likewise for "compatibility"). This may not
    // always provide what users want, but it makes it easy for them to understand how rule
    // declarations and package defaults intermix (and how to refactor them to get what they want).
    //
    // An alternative model would be to apply the "rule declaration" / "rule class defaults"
    // relationship, i.e. the rule class' "compatibility" and "restriction" declarations are merged
    // to generate a set of default environments, then the rule's declarations are independently
    // processed on top of that. This protects against obscure coupling behavior between
    // declarations from wildly different places (e.g. it offers clear answers to the examples posed
    // above). But within the scope of a single package it seems better to keep the model simple and
    // make the user responsible for resolving ambiguities.
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR)) {
      addExplicitDeps(
          outgoingLabels,
          rule,
          RuleClass.COMPATIBLE_ENVIRONMENT_ATTR,
          rule.getPackage().getPackageArgs().defaultCompatibleWith());
    }
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.RESTRICTED_ENVIRONMENT_ATTR)) {
      addExplicitDeps(
          outgoingLabels,
          rule,
          RuleClass.RESTRICTED_ENVIRONMENT_ATTR,
          rule.getPackage().getPackageArgs().defaultRestrictedTo());
    }

    addToolchainDeps(toolchainContexts, outgoingLabels);
    addBaseTargetToolchainDeps(baseTargetUnloadedToolchainContexts, outgoingLabels);
  }

  private static void addToolchainDeps(
      ToolchainCollection<ToolchainContext> toolchainContexts,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels) {
    if (toolchainContexts != null) {
      for (Map.Entry<String, ToolchainContext> entry : toolchainContexts.contextMap().entrySet()) {
        outgoingLabels.putAll(
            DependencyKind.forExecGroup(entry.getKey()),
            entry.getValue().resolvedToolchainLabels());
      }
    }
  }

  private static void addBaseTargetToolchainDeps(
      @Nullable ToolchainCollection<UnloadedToolchainContext> toolchainContexts,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels) {
    if (toolchainContexts == null) {
      return;
    }
    for (Map.Entry<String, UnloadedToolchainContext> execGroup :
        toolchainContexts.contextMap().entrySet()) {
      for (var toolchainTypeToResolved :
          execGroup.getValue().toolchainTypeToResolved().asMap().entrySet()) {
        // map entries from (exec group, toolchain type) to resolved toolchain labels. We need to
        // distinguish the resolved toolchains per type because aspects propagate on toolchains
        // based on the types specified in `toolchains_aspects`. So even if 2 types resolved to the
        // same toolchain target, their CT will be different if an aspect propagates to one type but
        // not the other.
        outgoingLabels.putAll(
            DependencyKind.forBaseTargetExecGroup(
                execGroup.getKey(), toolchainTypeToResolved.getKey().typeLabel()),
            toolchainTypeToResolved.getValue());
      }
    }
  }

  private static void resolveAttributes(
      Iterable<AttributeDependencyKind> attributeDependencyKinds,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      ConfiguredAttributeMapper attributeMap,
      BuildConfigurationValue ruleConfig)
      throws InterruptedException {
    for (AttributeDependencyKind dependencyKind : attributeDependencyKinds) {
      Attribute attribute = dependencyKind.getAttribute();
      // Not only is resolving CONFIG_SETTING_DEPS_ATTRIBUTE deps here wasteful, since the only
      // place they're used is in ConfiguredTargetFunction.getConfigConditions, but it actually
      // breaks trimming as shown by
      // FeatureFlagManualTrimmingTest#featureFlagInUnusedSelectBranchButNotInTransitiveConfigs_DoesNotError
      // because it resolves a dep that trimming (correctly) doesn't account for because it's part
      // of an unchosen select() branch.
      if (attribute.getName().equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
        continue;
      }
      Type<?> type = attribute.getType();
      if (type == BuildType.OUTPUT
          || type == BuildType.OUTPUT_LIST
          || type == BuildType.NODEP_LABEL
          || type == BuildType.NODEP_LABEL_LIST
          || type == BuildType.DORMANT_LABEL
          || type == BuildType.DORMANT_LABEL_LIST
          || type == BuildType.GENQUERY_SCOPE_TYPE
          || type == BuildType.GENQUERY_SCOPE_TYPE_LIST) {
        // These types invoke visitLabels() so that they are reported in "bazel query" but do not
        // create a dependency. Maybe it's better to remove that, but then the labels() query
        // function would need to be rethought.
        continue;
      }

      resolveAttribute(
          attribute, type, dependencyKind, outgoingLabels, rule, attributeMap, ruleConfig);
    }
  }

  private static <T> void resolveAttribute(
      Attribute attribute,
      Type<T> type,
      AttributeDependencyKind dependencyKind,
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      ConfiguredAttributeMapper attributeMap,
      BuildConfigurationValue ruleConfig)
      throws InterruptedException {
    T attributeValue = null;
    if (attribute.isImplicit()) {
      // Since the attributes that come from aspects do not appear in attributeMap, we have to get
      // their values from somewhere else. This incidentally means that aspects attributes are not
      // configurable. It would be nice if that wasn't the case, but we'd have to revamp how
      // attribute mapping works, which is a large chunk of work.
      if (dependencyKind.getOwningAspect() == null) {
        attributeValue = attributeMap.get(attribute.getName(), type);
      } else {
        Object defaultValue = attribute.getDefaultValue(rule);
        attributeValue =
            type.cast(
                defaultValue instanceof ComputedDefault computedDefault
                    ? computedDefault.getDefault(attributeMap)
                    : defaultValue);
      }
    } else if (attribute.isMaterializing()) {
      // These attributes are resolved by calling the materializer function in
      // DependencyMapProducer. The reason is that they need the analyzed versions some direct
      // dependencies and we can't do that here.
      outgoingLabels.put(dependencyKind, null);
    } else if (attribute.isLateBound()) {
      attributeValue =
          type.cast(resolveLateBoundDefault(rule, attributeMap, attribute, ruleConfig));
    } else if (dependencyKind.getOwningAspect() == null && attributeMap.has(attribute.getName())) {
      // This condition is false for aspect attributes that do not give rise to dependencies because
      // attributes that come from aspects do not appear in attributeMap (see the comment in the
      // case that handles implicit attributes).
      attributeValue = attributeMap.get(attribute.getName(), type);
    }

    if (attributeValue == null) {
      return;
    }

    type.visitLabels(
        (depLabel, ctx) -> outgoingLabels.put(dependencyKind, depLabel),
        attributeValue,
        /*context=*/ null);
  }

  @Nullable
  @VisibleForTesting(/* used to test LateBoundDefaults' default values */ )
  static <FragmentT> Object resolveLateBoundDefault(
      Rule rule, AttributeMap attributeMap, Attribute attribute, BuildConfigurationValue ruleConfig)
      throws InterruptedException {
    Preconditions.checkState(!attribute.getTransitionFactory().isSplit());
    @SuppressWarnings("unchecked")
    LateBoundDefault<FragmentT, ?> lateBoundDefault =
        (LateBoundDefault<FragmentT, ?>) attribute.getLateBoundDefault();

    Class<FragmentT> fragmentClass = lateBoundDefault.getFragmentClass();
    try {
      // TODO(b/65746853): remove this when nothing uses it anymore
      if (BuildConfigurationValue.class.equals(fragmentClass)
          // noconfig targets can't meaningfully parse late-bound defaults. See NoConfigTransition.
          && !ruleConfig.getOptions().hasNoConfig()) {
        return lateBoundDefault.resolve(rule, attributeMap, fragmentClass.cast(ruleConfig));
      }
      if (Void.class.equals(fragmentClass)) {
        return lateBoundDefault.resolve(
            rule, attributeMap, /* input= */ null
            /* analysisContext= */
            /* eventHandler= */ );
      }
      @SuppressWarnings("unchecked")
      FragmentT fragment =
          fragmentClass.cast(ruleConfig.getFragment((Class<? extends Fragment>) fragmentClass));
      if (fragment == null) {
        return null;
      }
      return lateBoundDefault.resolve(
          rule, attributeMap, fragment
          /* analysisContext= */
          /* eventHandler= */ );
    } catch (EvalException e) {
      // Materializers should not be called here and those are the only kind of late-bound defaults
      // that can throw these exceptions.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Adds new dependencies to the given rule under the given attribute name
   *
   * @param attrName the name of the attribute to add dependency labels to
   * @param labels the dependencies to add
   */
  private static void addExplicitDeps(
      OrderedSetMultimap<DependencyKind, Label> outgoingLabels,
      Rule rule,
      String attrName,
      Iterable<Label> labels) {
    if (!rule.isAttrDefined(attrName, BuildType.LABEL_LIST)
        && !rule.isAttrDefined(attrName, BuildType.NODEP_LABEL_LIST)) {
      return;
    }
    Attribute attribute = rule.getRuleClassObject().getAttributeByName(attrName);
    outgoingLabels.putAll(AttributeDependencyKind.forRule(attribute), labels);
  }

  /** Returns the attributes that should be visited for this rule/aspect combination. */
  private static ImmutableList<AttributeDependencyKind> getAttributes(
      Rule rule, ImmutableList<Aspect> aspects) {
    ImmutableList.Builder<AttributeDependencyKind> result = ImmutableList.builder();
    HashSet<String> ruleAndBaseAspectsProcessedAttributes = new HashSet<>();

    // For aspects evaluation, all attributes of the main aspect (last aspect in {@code aspects}
    // should be added, even if they have the same name as an attribute in the rule or a base aspect
    // because main aspect attributes are separated and retrieved from `ctx.attr`.

    // Attributes of the underlying rule and base aspects are merged and retrieved from
    // `ctx.rule.attr` with rule attributes taking precedence then aspects' attributes based on the
    // aspect order in the aspects path (lowest order to highest).

    List<Attribute> ruleAttributes = rule.getRuleClassObject().getAttributes();
    for (Attribute attribute : ruleAttributes) {
        result.add(AttributeDependencyKind.forRule(attribute));
      ruleAndBaseAspectsProcessedAttributes.add(attribute.getName());
    }

    addAspectAttributes(aspects, ruleAndBaseAspectsProcessedAttributes, result);

    return result.build();
  }

  private static ImmutableList<AttributeDependencyKind> getAspectAttributes(
      ImmutableList<Aspect> aspects) {
    ImmutableList.Builder<AttributeDependencyKind> result = ImmutableList.builder();
    addAspectAttributes(aspects, new HashSet<>(), result);
    return result.build();
  }

  private static void addAspectAttributes(
      ImmutableList<Aspect> aspects,
      Set<String> processedAttributes,
      ImmutableList.Builder<AttributeDependencyKind> attributes) {

    if (aspects.isEmpty()) {
      return;
    }

    // Add all the main aspect's attributes
    Aspect mainAspect = Iterables.getLast(aspects, null);
    for (Attribute attribute : mainAspect.getDefinition().getAttributes().values()) {
      attributes.add(AttributeDependencyKind.forAspect(attribute, mainAspect.getAspectClass()));
    }

    // For base aspects, if multiple attributes have the same name, take the first encountered in
    // the aspects path.
    for (Aspect aspect : aspects.subList(0, aspects.size() - 1)) {
      for (Attribute attribute : aspect.getDefinition().getAttributes().values()) {
        if (processedAttributes.add(attribute.getName())) {
          attributes.add(AttributeDependencyKind.forAspect(attribute, aspect.getAspectClass()));
        }
      }
    }
  }

  private static void addVisibilityDepLabels(
      Iterable<Label> labels, OrderedSetMultimap<DependencyKind, Label> outgoingLabels) {
    outgoingLabels.putAll(VISIBILITY_DEPENDENCY, labels);
  }
}
