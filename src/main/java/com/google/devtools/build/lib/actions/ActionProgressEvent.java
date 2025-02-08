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
package com.google.devtools.build.lib.actions;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;

/**
 * Notifications for the progress of an in-flight action.
 *
 * @param action Gets the metadata associated with the action being scheduled.
 * @param progressId The id that uniquely determines the progress among all progress events within
 *     an action.
 * @param progress Human readable description of the progress.
 * @param finished Whether the download progress reported about is finished already.
 */
public record ActionProgressEvent(
    ActionExecutionMetadata action, String progressId, String progress, boolean finished)
    implements Postable {
  public ActionProgressEvent {
    requireNonNull(action, "action");
    requireNonNull(progressId, "progressId");
    requireNonNull(progress, "progress");
  }

  public static ActionProgressEvent create(
      ActionExecutionMetadata action, String progressId, String progress, boolean finished) {
    return new ActionProgressEvent(action, progressId, progress, finished);
  }

}
