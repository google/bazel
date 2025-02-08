// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.VisibilityProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/**
 * Dummy ConfiguredTarget for environment groups. Contains no functionality, since environment
 * groups are not really first-class Targets.
 */
@Immutable
@AutoCodec
public final class EnvironmentGroupConfiguredTarget extends AbstractConfiguredTarget {

  public EnvironmentGroupConfiguredTarget(ActionLookupKey actionLookupKey) {
    super(actionLookupKey, VisibilityProvider.PRIVATE_VISIBILITY);
    Preconditions.checkState(actionLookupKey.getConfigurationKey() == null, actionLookupKey);
  }

  @Override
  @Nullable
  protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
    return null;
  }

  @Override
  @Nullable
  protected Object rawGetStarlarkProvider(String providerKey) {
    return null;
  }

  @Override
  public boolean isCreatedInSymbolicMacro() {
    // Correct, since environment_group isn't allowed in symbolic macros. But that doesn't matter,
    // since we don't expect this to be called.
    return false;
  }
}
