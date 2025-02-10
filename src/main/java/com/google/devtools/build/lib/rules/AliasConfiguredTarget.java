// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.analysis.VisibilityProviderImpl;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Structure;

/**
 * A {@link ConfiguredTarget} that pretends to be whatever type of target {@link #getActual} is,
 * mirroring its label and transitive info providers.
 *
 * <p>Transitive info providers may also be overridden. At a minimum, {@link #getProvider} provides
 * {@link AliasProvider} and an explicit {@link VisibilityProvider} which takes precedent over the
 * actual target's visibility.
 *
 * <p>The {@link ConfiguredTarget#getConfigurationKey} returns the configuration of the alias itself
 * and not the configuration of {@link AliasConfiguredTarget#actual} for the following reasons.
 *
 * <ul>
 *   <li>{@code actual} might be an input file, in which case its configuration key is null, and we
 *       don't want to have rules with a null configuration key.
 *   <li>{@code actual} has a self transition. Self transitions don't get applied to the alias rule,
 *       and so the configuration keys actually differ.
 * </ul>
 *
 * <p>An {@code alias} target may not be used to redirect a {@code package_group} target in a {@code
 * visibility} declaration or a {@code package_group}'s {@code includes} attribute.
 */
@Immutable
@AutoCodec
public final class AliasConfiguredTarget implements ConfiguredTarget, Structure {

  /**
   * Convenience wrapper for {@link #createWithOverrides} that does not specify any additional
   * overrides.
   */
  public static AliasConfiguredTarget create(
      RuleContext ruleContext,
      ConfiguredTarget actual,
      NestedSet<PackageGroupContents> visibility) {
    return createWithOverrides(
        ruleContext, actual, visibility, /*overrides=*/ ImmutableClassToInstanceMap.of());
  }

  /**
   * Constructs an {@code AliasConfiguredTarget} that forwards most of the providers of {@code
   * actual}, with certain providers shadowed.
   *
   * <p>The shadowed providers are anything given in {@code overrides}, plus the following built-in
   * changes which take priority above both {@code actual} and {@code overrides}:
   *
   * <ul>
   *   <li>{@link AliasProvider} is set to indicate that this is an alias configured target.
   *   <li>{@link VisibilityProvider} has the information describing this alias target (as passed
   *       here in the {@code visibility} parameter), not the information describing the {@code
   *       actual} underlying target.
   *   <li>{@link RequiredConfigFragmentsProvider} may be set}
   * </ul>
   */
  public static AliasConfiguredTarget createWithOverrides(
      RuleContext ruleContext,
      ConfiguredTarget actual,
      NestedSet<PackageGroupContents> visibility,
      ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides) {
    ImmutableClassToInstanceMap.Builder<TransitiveInfoProvider> allOverrides =
        ImmutableClassToInstanceMap.<TransitiveInfoProvider>builder()
            .putAll(overrides)
            .put(AliasProvider.class, AliasProvider.fromAliasRule(ruleContext.getRule(), actual))
            .put(
                VisibilityProvider.class,
                new VisibilityProviderImpl(
                    visibility,
                    /* isCreatedInSymbolicMacro= */ ruleContext
                        .getRule()
                        .isCreatedInSymbolicMacro()));
    if (ruleContext.getRequiredConfigFragments() != null) {
      // This causes "blaze cquery --show_config_fragments=direct" to only show the
      // fragments/options the alias directly uses, not those of its actual target. Since alias
      // has a narrow API this practically means whatever a select() in the alias requires.
      allOverrides.put(
          RequiredConfigFragmentsProvider.class, ruleContext.getRequiredConfigFragments());
    }
    return new AliasConfiguredTarget(
        ruleContext.getOwner(), actual, allOverrides.build(), ruleContext.getConfigConditions());
  }

  private final ActionLookupKey actionLookupKey;
  private final ConfiguredTarget actual;
  private final ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;

  @VisibleForSerialization
  AliasConfiguredTarget(
      ActionLookupKey actionLookupKey,
      ConfiguredTarget actual,
      ImmutableClassToInstanceMap<TransitiveInfoProvider> overrides,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
    this.actionLookupKey = actionLookupKey;
    this.actual = checkNotNull(actual);
    this.overrides = checkNotNull(overrides);
    this.configConditions = checkNotNull(configConditions);
  }

  @Override
  public ConfiguredTarget getActualNoFollow() {
    return actual;
  }

  @Override
  public ActionLookupKey getLookupKey() {
    return this.actionLookupKey;
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @Override
  public ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
    P p = overrides.getInstance(provider);
    return p != null ? p : actual.getProvider(provider);
  }

  // TODO(bazel-team): It's a bit confusing that we're returning the label of the target we directly
  // point to, rather than our own label, or the label of the eventual endpoint of the alias chain.
  // Is there a reason we need to put that behavior in this override rather than having this return
  // our own label and making a separate method to get the actual's label? (If so, update this
  // comment.)
  @Override
  public Label getLabel() {
    return actual.getLabel();
  }

  @Override
  public Object get(String providerKey) {
    return actual.get(providerKey);
  }

  @Nullable
  @Override
  public Info get(Provider.Key providerKey) {
    return actual.get(providerKey);
  }

  @Override
  public Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    return actual.getIndex(semantics, key);
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    return actual.containsKey(semantics, key);
  }

  /* Structure methods */

  @Override
  public Object getValue(String name) {
    if (name.equals(LABEL_FIELD)) {
      return getLabel();
    } else if (name.equals(FILES_FIELD)) {
      // A shortcut for files to build in Starlark. FileConfiguredTarget and RuleConfiguredTarget
      // always has FileProvider and Error- and PackageGroupConfiguredTarget-s shouldn't be
      // accessible in Starlark.
      return Depset.of(Artifact.class, getProvider(FileProvider.class).getFilesToBuild());
    }
    return actual.getValue(name);
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return actual.getFieldNames();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String name) {
    // Use the default error message.
    return null;
  }

  @Override
  public ConfiguredTarget getActual() {
    // This will either dereference an alias chain, or return the final ConfiguredTarget.
    return actual.getActual();
  }

  @Override
  public Label getOriginalLabel() {
    return actionLookupKey.getLabel();
  }

  @Override
  public Dict<String, Object> getProvidersDictForQuery() {
    return actual.getProvidersDictForQuery();
  }

  @Override
  public void repr(Printer printer) {
    printer.append(
        "<alias target " + actionLookupKey.getLabel() + " of " + actual.getLabel() + ">");
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("label", actionLookupKey.getLabel())
        .add("configurationKey", getConfigurationKey())
        .add("actual", actual)
        .add("overrides", overrides)
        .add("configConditions", configConditions)
        .toString();
  }
}
