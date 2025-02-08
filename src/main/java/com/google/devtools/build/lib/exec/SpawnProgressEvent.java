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
import com.google.devtools.build.lib.actions.ActionProgressEvent;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SpawnRunner.ProgressStatus;

/**
 * The {@link SpawnRunner} is making some progress.
 *
 * @param progressId The id that uniquely determines the progress among all progress events for this
 *     spawn.
 * @param progress Human readable description of the progress.
 * @param finished Whether the progress reported about is finished already.
 */
public record SpawnProgressEvent(String progressId, String progress, boolean finished)
    implements ProgressStatus {
  public SpawnProgressEvent {
    requireNonNull(progressId, "progressId");
    requireNonNull(progress, "progress");
  }

  public static SpawnProgressEvent create(String resourceId, String progress, boolean finished) {
    return new SpawnProgressEvent(resourceId, progress, finished);
  }

  @Override
  public void postTo(ExtendedEventHandler eventHandler, ActionExecutionMetadata action) {
    eventHandler.post(ActionProgressEvent.create(action, progressId(), progress(), finished()));
  }
}
