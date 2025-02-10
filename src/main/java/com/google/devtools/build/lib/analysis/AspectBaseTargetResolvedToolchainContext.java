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

package com.google.devtools.build.lib.analysis;

import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.MergedConfiguredTarget.MergingException;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkIndexable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Structure;

/**
 * A toolchain context for the aspect's base target toolchains. It is used to represent the result
 * of applying the aspects propagation to the base target toolchains.
 */
@AutoValue
public abstract class AspectBaseTargetResolvedToolchainContext
    implements ResolvedToolchainsDataInterface<
        AspectBaseTargetResolvedToolchainContext.ToolchainAspectsProviders> {

  public abstract ImmutableMap<ToolchainTypeInfo, ToolchainAspectsProviders> toolchains();

  public static AspectBaseTargetResolvedToolchainContext load(
      UnloadedToolchainContext unloadedToolchainContext,
      String targetDescription,
      ImmutableMultimap<ToolchainTypeInfo, ConfiguredTargetAndData> toolchainTargets)
      throws MergingException {

    ImmutableMap.Builder<ToolchainTypeInfo, ToolchainAspectsProviders> toolchainsBuilder =
        new ImmutableMap.Builder<>();

    for (var toolchainType : unloadedToolchainContext.toolchainTypeToResolved().keySet()) {
      Preconditions.checkArgument(toolchainTargets.get(toolchainType).size() == 1);

      var toolchainTarget =
          Iterables.getOnlyElement(toolchainTargets.get(toolchainType)).getConfiguredTarget();

      if (toolchainTarget instanceof MergedConfiguredTarget mergedConfiguredTarget) {
        // Only add the aspects providers from the toolchains that the aspects applied to.
        TemplateVariableInfo templateVariableInfo =
            (TemplateVariableInfo)
                mergedConfiguredTarget
                    .getBaseConfiguredTarget()
                    .get(TemplateVariableInfo.PROVIDER.id());
        toolchainsBuilder.put(
            toolchainType,
            new ToolchainAspectsProviders(
                mergedConfiguredTarget.getAspectsProviders(),
                templateVariableInfo,
                mergedConfiguredTarget.getLabel()));
      } else {
        // Add empty providers for the toolchains that the aspects did not apply to.
        TemplateVariableInfo templateVariableInfo =
            (TemplateVariableInfo) toolchainTarget.get(TemplateVariableInfo.PROVIDER.id());
        toolchainsBuilder.put(
            toolchainType,
            new ToolchainAspectsProviders(
                new TransitiveInfoProviderMapBuilder().build(),
                templateVariableInfo,
                toolchainTarget.getLabel()));
      }
    }
    ImmutableMap<ToolchainTypeInfo, ToolchainAspectsProviders> toolchains =
        toolchainsBuilder.buildOrThrow();

    return new AutoValue_AspectBaseTargetResolvedToolchainContext(
        // ToolchainContext:
        unloadedToolchainContext.key(),
        unloadedToolchainContext.executionPlatform(),
        unloadedToolchainContext.targetPlatform(),
        unloadedToolchainContext.toolchainTypes(),
        unloadedToolchainContext.resolvedToolchainLabels(),
        // ResolvedToolchainsDataInterface:
        targetDescription,
        unloadedToolchainContext.requestedLabelToToolchainType(),
        // this:
        toolchains);
  }

  @Override
  @Nullable
  public ToolchainAspectsProviders forToolchainType(Label toolchainTypeLabel) {
    if (requestedToolchainTypeLabels().containsKey(toolchainTypeLabel)) {
      return toolchains().get(requestedToolchainTypeLabels().get(toolchainTypeLabel));
    }

    return null;
  }

  public ImmutableList<TemplateVariableInfo> templateVariableProviders() {
    return toolchains().values().stream()
        .map(ToolchainAspectsProviders::templateVariableProvider)
        .filter(notNull())
        .collect(toImmutableList());
  }

  /**
   * A Starlark-indexable wrapper used to represent the providers of the aspects applied on the base
   * target toolchains.
   */
  public static class ToolchainAspectsProviders
      implements StarlarkIndexable, Structure, ResolvedToolchainData {

    private final TransitiveInfoProviderMap aspectsProviders;
    @Nullable private final TemplateVariableInfo templateVariableInfo;
    private final Label label;

    private ToolchainAspectsProviders(
        TransitiveInfoProviderMap aspectsProviders,
        @Nullable TemplateVariableInfo templateVariableInfo,
        Label label) {
      this.aspectsProviders = aspectsProviders;
      this.templateVariableInfo = templateVariableInfo;
      this.label = label;
    }

    @Override
    public final Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
      Provider constructor = selectExportedProvider(key, "index");
      Object declaredProvider = aspectsProviders.get(constructor.getKey());
      if (declaredProvider != null) {
        return declaredProvider;
      }
      throw Starlark.errorf(
          "%s doesn't contain declared provider '%s'",
          Starlark.repr(this), constructor.getPrintableName());
    }

    @Override
    public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
      return aspectsProviders.get(selectExportedProvider(key, "query").getKey()) != null;
    }

    /**
     * Selects the provider identified by {@code key}, throwing a Starlark error if the key is not a
     * provider or not exported.
     */
    private Provider selectExportedProvider(Object key, String operation) throws EvalException {
      if (!(key instanceof Provider constructor)) {
        throw Starlark.errorf(
            "This type only supports %sing by object constructors, got %s instead",
            operation, Starlark.type(key));
      }
      if (!constructor.isExported()) {
        throw Starlark.errorf(
            "%s only supports %sing by exported providers. Assign the provider a name "
                + "in a top-level assignment statement.",
            Starlark.repr(this), operation);
      }
      return constructor;
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<ToolchainAspectsProviders for toolchain target: " + label + ">");
    }

    @Nullable
    @Override
    public Object getValue(String name) {
      if (name.equals(MergedConfiguredTarget.LABEL_FIELD)) {
        return label;
      }
      return null;
    }

    @Override
    public ImmutableList<String> getFieldNames() {
      return ImmutableList.of(MergedConfiguredTarget.LABEL_FIELD);
    }

    @Nullable
    @Override
    public String getErrorMessageForUnknownField(String field) {
      // Use the default error message.
      return null;
    }

    @Nullable
    public TemplateVariableInfo templateVariableProvider() {
      return this.templateVariableInfo;
    }
  }
}
