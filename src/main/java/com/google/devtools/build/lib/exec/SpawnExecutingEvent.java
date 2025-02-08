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
import com.google.devtools.build.lib.actions.RunningActionEvent;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;

/**
 * Notifies that {@link SpawnRunner} failed to find a cache hit and acquired the resources to
 * execute. This MUST be posted before attempting to execute the subprocess.
 *
 * <p>Caching {@link SpawnRunner} implementations should only post this after a failed cache lookup,
 * but may post this if cache lookup and execution happen within the same step, e.g. as part of a
 * single RPC call with no mechanism to report cache misses.
 */
public record SpawnExecutingEvent(String name) implements ProgressStatus {
  public SpawnExecutingEvent {
    requireNonNull(name, "name");
  }

  public static SpawnExecutingEvent create(String name) {
    return new SpawnExecutingEvent(name);
  }

  @Override
  public void postTo(ExtendedEventHandler eventHandler, ActionExecutionMetadata action) {
    eventHandler.post(new RunningActionEvent(action, name()));
  }
}
