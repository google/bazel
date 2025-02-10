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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.RuleConfiguredObjectValue;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Package;
import javax.annotation.Nullable;

/** A configured target in the context of a Skyframe graph. */
@Immutable
@ThreadSafe
public final class RuleConfiguredTargetValue
    extends AbstractConfiguredTargetValue<RuleConfiguredTarget>
    implements RuleConfiguredObjectValue, ConfiguredTargetValue {

  /**
   * Operations accessing actions, for example, executing them, should be performed in the same
   * Bazel instance that constructs the {@code RuleConfiguredTargetValue} instance and not on a
   * Bazel instance that retrieves it remotely using deserialization.
   */
  @Nullable // Null if deserialized.
  private final transient ImmutableList<ActionAnalysisMetadata> actions;

  public RuleConfiguredTargetValue(
      RuleConfiguredTarget configuredTarget, @Nullable NestedSet<Package> transitivePackages) {
    super(configuredTarget, transitivePackages);
    // These are specifically *not* copied to save memory.
    this.actions = configuredTarget.getActions();
  }

  @Override
  public ImmutableList<ActionAnalysisMetadata> getActions() {
    return checkNotNull(actions, "actions are not available on deserialized instances");
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("actions", actions)
        .add("configuredTarget", getConfiguredTarget())
        .toString();
  }
}
