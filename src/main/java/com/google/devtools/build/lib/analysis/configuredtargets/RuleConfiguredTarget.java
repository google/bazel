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
package com.google.devtools.build.lib.analysis.configuredtargets;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.analysis.Util;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.starlark.StarlarkApiProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClassId;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.ActionApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/**
 * A {@link com.google.devtools.build.lib.analysis.ConfiguredTarget} that is produced by a rule.
 *
 * <p>Created by {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder}. There
 * is an instance of this class for every analyzed rule. For more information about how analysis
 * works, see {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 */
@Immutable
@AutoCodec
public final class RuleConfiguredTarget extends AbstractConfiguredTarget {

  /** A set of this target's implicitDeps. */
  private final ImmutableSet<ConfiguredTargetKey> implicitDeps;

  /*
   * An interner for the implicitDeps set. {@link Util.findImplicitDeps} is called upon every
   * construction of a RuleConfiguredTarget and we expect many of these targets to contain the same
   * set of implicit deps so this reduces the memory load per build.
   */
  private static final Interner<ImmutableSet<ConfiguredTargetKey>> IMPLICIT_DEPS_INTERNER =
      BlazeInterners.newWeakInterner();

  private final TransitiveInfoProviderMap providers;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;
  private final RuleClassId ruleClassId;

  /**
   * Operations accessing actions (for example, executing them or looking up metadata in them)
   * should be performed in the same Bazel instance that constructs the {@code RuleConfiguredTarget}
   * instance and not on a Bazel instance that retrieves the {@code RuleConfiguredTarget} remotely
   * using deserialization, because actions will be null then.
   *
   * <p>Prefer using {@link #getActions()} which guards against null actions with a clear error.
   */
  @Nullable // Null if deserialized.
  private final transient ImmutableList<ActionAnalysisMetadata> actions;

  private RuleConfiguredTarget(
      ActionLookupKey actionLookupKey,
      NestedSet<PackageGroupContents> visibility,
      boolean isCreatedInSymbolicMacro,
      TransitiveInfoProviderMap providers,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      ImmutableSet<ConfiguredTargetKey> implicitDeps,
      RuleClassId ruleClassId,
      ImmutableList<ActionAnalysisMetadata> actions) {
    super(actionLookupKey, visibility);

    // We don't use ImmutableMap.Builder here to allow augmenting the initial list of 'default'
    // providers by passing them in.
    TransitiveInfoProviderMapBuilder providerBuilder =
        new TransitiveInfoProviderMapBuilder().addAll(providers);
    if (isCreatedInSymbolicMacro) {
      // Rather than add a boolean field to all RuleConfiguredTargets, we add a marker provider to
      // just the ones that are created in symbolic macros. (This tradeoff may make less sense if
      // many targets are created in macros.)
      providerBuilder.add(CreatedInSymbolicMacroMarker.INSTANCE);
    }
    checkState(providerBuilder.contains(RunfilesProvider.class), actionLookupKey);
    checkState(providerBuilder.contains(FileProvider.class), actionLookupKey);
    checkState(providerBuilder.contains(FilesToRunProvider.class), actionLookupKey);

    // Initialize every StarlarkApiProvider
    for (int i = 0; i < providers.getProviderCount(); i++) {
      Object obj = providers.getProviderInstanceAt(i);
      if (obj instanceof StarlarkApiProvider starlarkApiProvider) {
        starlarkApiProvider.init(this);
      }
    }

    this.providers = providerBuilder.build();
    this.configConditions = configConditions;
    this.implicitDeps = IMPLICIT_DEPS_INTERNER.intern(implicitDeps);
    this.ruleClassId = ruleClassId;
    this.actions = actions;
  }

  public RuleConfiguredTarget(
      RuleContext ruleContext,
      TransitiveInfoProviderMap providers,
      ImmutableList<ActionAnalysisMetadata> actions) {
    this(
        ruleContext.getOwner(),
        ruleContext.getVisibility(),
        /* isCreatedInSymbolicMacro= */ ruleContext.getRule().isCreatedInSymbolicMacro(),
        providers,
        ruleContext.getConfigConditions(),
        Util.findImplicitDeps(ruleContext),
        ruleContext.getRule().getRuleClassObject().getRuleClassId(),
        actions);
    // Make sure that all declared output files are also created as artifacts. The
    // CachingAnalysisEnvironment makes sure that they all have generating actions.
    if (!ruleContext.hasErrors()) {
      for (OutputFile out : ruleContext.getRule().getOutputFiles()) {
        ruleContext.createOutputArtifact(out);
      }
    }
  }

  /** Use this constructor for creating incompatible ConfiguredTarget instances. */
  public RuleConfiguredTarget(
      ActionLookupKey actionLookupKey,
      NestedSet<PackageGroupContents> visibility,
      boolean isCreatedInSymbolicMacro,
      TransitiveInfoProviderMap providers,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      RuleClassId ruleClassId) {
    this(
        actionLookupKey,
        visibility,
        isCreatedInSymbolicMacro,
        providers,
        configConditions,
        ImmutableSet.of(),
        ruleClassId,
        ImmutableList.of());
    checkState(providers.get(IncompatiblePlatformProvider.PROVIDER) != null, actionLookupKey);
  }

  /**
   * @deprecated for serialization only
   */
  @Deprecated
  @VisibleForSerialization
  @AutoCodec.Instantiator
  RuleConfiguredTarget(
      ActionLookupKey lookupKey,
      NestedSet<PackageGroupContents> visibility,
      TransitiveInfoProviderMap providers,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      ImmutableSet<ConfiguredTargetKey> implicitDeps,
      RuleClassId ruleClassId) {
    super(lookupKey, visibility);
    this.providers = providers;
    this.configConditions = configConditions;
    this.implicitDeps = implicitDeps;
    this.ruleClassId = ruleClassId;
    this.actions = null; // not currently serialized
  }

  /**
   * Marker provider that indicates this target was instantiated within one or more symbolic macros.
   */
  private static class CreatedInSymbolicMacroMarker implements TransitiveInfoProvider {
    public static final CreatedInSymbolicMacroMarker INSTANCE = new CreatedInSymbolicMacroMarker();

    // Singleton.
    private CreatedInSymbolicMacroMarker() {}
  }

  @Override
  public boolean isCreatedInSymbolicMacro() {
    return getProvider(CreatedInSymbolicMacroMarker.class) != null;
  }

  /** The configuration conditions that trigger this rule's configurable attributes. */
  @Override
  public ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  @Override
  public boolean isRuleConfiguredTarget() {
    return true;
  }

  public ImmutableSet<ConfiguredTargetKey> getImplicitDeps() {
    return implicitDeps;
  }

  @Override
  public String getRuleClassString() {
    return ruleClassId.name();
  }

  public RuleClassId getRuleClassId() {
    return ruleClassId;
  }

  @Nullable
  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> providerClass) {
    // TODO(bazel-team): Should aspects be allowed to override providers on the configured target
    // class?
    AnalysisUtils.checkProvider(providerClass);
    final P provider = providers.getProvider(providerClass);
    if (provider != null) {
      return provider;
    }
    if (providerClass.isAssignableFrom(getClass())) {
      return providerClass.cast(this);
    }
    return null;
  }

  @Override
  public String getErrorMessageForUnknownField(String name) {
    return String.format(
        "%s (rule '%s') doesn't have provider '%s'", Starlark.repr(this), ruleClassId.name(), name);
  }

  @Override
  protected void addExtraStarlarkKeys(Consumer<String> result) {
    for (int i = 0; i < providers.getProviderCount(); i++) {
      Object classAt = providers.getProviderKeyAt(i);
      if (classAt instanceof String string) {
        result.accept(string);
      }
    }
    result.accept(ACTIONS_FIELD_NAME);
  }

  @Override
  protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
    return providers.get(providerKey);
  }

  @Override
  protected Object rawGetStarlarkProvider(String providerKey) {
    if (providerKey.equals(ACTIONS_FIELD_NAME)) {
      // Only expose actions which are legitimate Starlark values, otherwise they will later
      // cause a Bazel crash.
      // TODO(cparsons): Expose all actions to Starlark.
      return getActions().stream()
          .filter(action -> action instanceof ActionApi)
          .collect(ImmutableList.toImmutableList());
    }
    return providers.get(providerKey);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<target " + getLabel() + ">");
  }

  @Override
  public void debugPrint(Printer printer, StarlarkThread thread) {
    // Show the names of the provider keys that this target propagates.
    // Provider key names might potentially be *private* information, and thus a comprehensive
    // list of provider keys should not be exposed in any way other than for debug information.
    printer.append("<target " + getLabel() + ", keys:[");
    ImmutableList.Builder<String> starlarkProviderKeyStrings = ImmutableList.builder();
    for (int providerIndex = 0; providerIndex < providers.getProviderCount(); providerIndex++) {
      Object providerKey = providers.getProviderKeyAt(providerIndex);
      if (providerKey instanceof Provider.Key) {
        starlarkProviderKeyStrings.add(providerKey.toString());
      }
    }
    printer.append(Joiner.on(", ").join(starlarkProviderKeyStrings.build()));
    printer.append("]>");
  }

  /** Returns a list of actions that this configured target generated. */
  public ImmutableList<ActionAnalysisMetadata> getActions() {
    return checkNotNull(actions, "actions are not available on deserialized instances");
  }

  /**
   * Finds an artifact (known to be produced by this rule) by its corresponding output label, for
   * use when creating an {@link OutputFileConfiguredTarget}.
   */
  public Artifact findArtifactByOutputLabel(Label outputLabel) {
    checkArgument(
        outputLabel.getPackageIdentifier().equals(getLabel().getPackageIdentifier()),
        "%s not in same package as %s",
        outputLabel,
        this);
    PathFragment relativeOutputPath = outputLabel.toPathFragment();
    for (ActionAnalysisMetadata action : getActions()) {
      for (Artifact output : action.getOutputs()) {
        if (output.getExecPath().endsWith(relativeOutputPath)) {
          return output;
        }
      }
    }
    throw new IllegalArgumentException("No output matching " + outputLabel + " in " + this);
  }

  @Override
  public Dict<String, Object> getProvidersDictForQuery() {
    return toProvidersDictForQuery(providers);
  }
}
