// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.SchedulingActionEvent;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;

/**
 * Notifies that {@link SpawnRunner} is waiting for local or remote resources to become available.
 */
public record SpawnSchedulingEvent(String name) implements ProgressStatus {
  public SpawnSchedulingEvent {
    requireNonNull(name, "name");
  }

  public static SpawnSchedulingEvent create(String name) {
    return new SpawnSchedulingEvent(name);
  }

  @Override
  public void postTo(ExtendedEventHandler eventHandler, ActionExecutionMetadata action) {
    eventHandler.post(new SchedulingActionEvent(action, name()));
  }
}
