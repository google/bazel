// Copyright 2022 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.ExtendedEventHandler;

/**
 * An event that is fired when all inputs of an action are collected but before the these inputs are
 * requested to skyframe.
 */
public record ActionInputCollectedEvent(
    Action action, NestedSet<Artifact> inputs, ActionContextRegistry actionContextRegistry)
    implements ExtendedEventHandler.Postable {
  public ActionInputCollectedEvent {
    requireNonNull(action, "action");
    requireNonNull(inputs, "inputs");
    requireNonNull(actionContextRegistry, "actionContextRegistry");
  }

  public static ActionInputCollectedEvent create(
      Action action, NestedSet<Artifact> inputs, ActionContextRegistry actionContextRegistry) {
    return new ActionInputCollectedEvent(action, inputs, actionContextRegistry);
  }

}
