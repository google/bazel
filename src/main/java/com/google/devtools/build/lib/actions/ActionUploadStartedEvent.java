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

import build.bazel.remote.execution.v2.Digest;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.remote.Store;

/**
 * The event fired when a resource is about to be uploaded to a remote or disk cache upon completion
 * of a local action.
 *
 * @param action Returns the associated action.
 * @param store Returns the store that the resource belongs to.
 * @param digest Returns the digest that uniquely identifies the resource.
 */
public record ActionUploadStartedEvent(ActionExecutionMetadata action, Store store, Digest digest)
    implements Postable {
  public ActionUploadStartedEvent {
    requireNonNull(action, "action");
    requireNonNull(store, "store");
    requireNonNull(digest, "digest");
  }

  public static ActionUploadStartedEvent create(
      ActionExecutionMetadata action, Store store, Digest digest) {
    return new ActionUploadStartedEvent(action, store, digest);
  }

}
