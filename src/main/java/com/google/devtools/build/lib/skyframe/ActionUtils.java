// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import javax.annotation.Nullable;

/** Utility methods for dealing with actions. */
public final class ActionUtils {

  @Nullable
  public static Action getActionForLookupData(Environment env, ActionLookupData actionLookupData)
      throws InterruptedException {
    ActionLookupValue actionLookupValue =
        ArtifactFunction.getActionLookupValue(actionLookupData.getActionLookupKey(), env);
    return actionLookupValue != null
        ? actionLookupValue.getAction(actionLookupData.getActionIndex())
        : null;
  }

  public static String getOwnerLabelAsString(Action action) {
    ActionOwner owner = action.getOwner();
    if (owner == null) {
      return "";
    }
    Label ownerLabel = owner.getLabel();
    if (ownerLabel == null) {
      return "";
    }
    return ownerLabel.getCanonicalForm();
  }

  public static String getOwnerConfigurationAsString(Action action) {
    ActionOwner owner = action.getOwner();
    if (owner == null) {
      return "";
    }
    return owner.getConfigurationChecksum();
  }

  private ActionUtils() {}
}
