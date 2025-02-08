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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Structure;

/**
 * A {@link ConfiguredTarget} is conceptually a {@link TransitiveInfoCollection} coupled with the
 * {@link com.google.devtools.build.lib.packages.Target} and {@link
 * com.google.devtools.build.lib.analysis.config.BuildConfigurationValue} objects it was created
 * from.
 *
 * <p>This interface is supposed to only be used in {@link BuildView} and above. In particular, rule
 * implementations should not be able to access the {@link ConfiguredTarget} objects associated with
 * their direct dependencies, only the corresponding {@link TransitiveInfoCollection}s. Also, {@link
 * ConfiguredTarget} objects should not be accessible from the action graph.
 */
public interface ConfiguredTarget extends TransitiveInfoCollection, Structure, CqueryNode {

  /** All <code>ConfiguredTarget</code>s have a "label" field. */
  String LABEL_FIELD = "label";

  /** All <code>ConfiguredTarget</code>s have a "files" field. */
  String FILES_FIELD = "files";

  /** Returns a key that may be used to lookup this {@link ConfiguredTarget}. */
  @Override
  ActionLookupKey getLookupKey();

  @Override
  default Label getLabel() {
    return getLookupKey().getLabel();
  }

  @Override
  @Nullable
  default String getConfigurationChecksum() {
    return getConfigurationKey() == null ? null : getConfigurationKey().getOptions().checksum();
  }

  /**
   * Returns the {@link BuildConfigurationKey} naming the {@link
   * com.google.devtools.build.lib.analysis.config.BuildConfigurationValue} for which this
   * configured target is defined. Configuration is defined for all configured targets with
   * exception of {@link
   * com.google.devtools.build.lib.analysis.configuredtargets.InputFileConfiguredTarget} and {@link
   * com.google.devtools.build.lib.analysis.configuredtargets.PackageGroupConfiguredTarget} for
   * which it is always <b>null</b>.
   *
   * <p>If this changes, {@link AspectResolver#aspectMatchesConfiguredTarget} should be updated.
   */
  @Override
  @Nullable
  default BuildConfigurationKey getConfigurationKey() {
    return getLookupKey().getConfigurationKey();
  }

  /** Returns keys for a legacy Starlark provider. */
  @Override
  ImmutableCollection<String> getFieldNames();

  /**
   * Returns a legacy Starlark provider.
   *
   * <p>Overrides {@link Structure#getValue(String)}, but does not allow EvalException to be thrown.
   */
  @Nullable
  @Override
  Object getValue(String name);

  /**
   * If the configured target is an alias, return the actual target in the end of the alias chain,
   * otherwise return the current target. This follows alias chains.
   */
  @Override
  default ConfiguredTarget getActual() {
    return this;
  }

  /**
   * If the configured target is an alias, return the actual target directly pointed to by the
   * alias, otherwise return the current target. This does not follow alias chains.
   */
  default ConfiguredTarget getActualNoFollow() {
    return this;
  }

  /**
   * If the configured target is an alias, return the original label, otherwise return the current
   * label. This is not the same as {@code getActual().getLabel()}, because it does not follow alias
   * chains.
   */
  @Override
  default Label getOriginalLabel() {
    return getLabel();
  }

  /**
   * The configuration conditions that trigger this configured target's configurable attributes. For
   * targets that do not support configurable attributes, this will be an empty map.
   */
  @Override
  default ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return ImmutableMap.of();
  }

  @Override
  default boolean isRuleConfiguredTarget() {
    return false;
  }

  /**
   * The base configured target if it has been merged with aspects otherwise the current value.
   *
   * <p>Unwrapping is recursive if there are multiple layers.
   */
  @Override
  default ConfiguredTarget unwrapIfMerged() {
    return this;
  }

  /**
   * This is only intended to be called from the query dialects of Starlark.
   *
   * @return a map of provider names to their values, or null if there are no providers
   */
  @Override
  @Nullable
  default Dict<String, Object> getProvidersDictForQuery() {
    return null;
  }
}
