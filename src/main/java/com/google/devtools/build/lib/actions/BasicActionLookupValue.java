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
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/**
 * Basic implementation of {@link ActionLookupValue} where the value itself owns and maintains the
 * list of generating actions.
 */
public class BasicActionLookupValue implements ActionLookupValue {
  @Nullable protected final transient ImmutableList<ActionAnalysisMetadata> actions;

  @VisibleForTesting
  public BasicActionLookupValue(ImmutableList<ActionAnalysisMetadata> actions) {
    this.actions = actions;
  }

  @Override
  public ImmutableList<ActionAnalysisMetadata> getActions() {
    return checkNotNull(actions, "actions are not available on deserialized instances");
  }

  @Override
  public int getNumActions() {
    if (actions == null) {
      return 0;
    }
    return actions.size();
  }

  protected ToStringHelper getStringHelper() {
    return MoreObjects.toStringHelper(this).add("actions", actions);
  }
}
