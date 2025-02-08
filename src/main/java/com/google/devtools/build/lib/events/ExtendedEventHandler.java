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

package com.google.devtools.build.lib.events;

import javax.annotation.Nullable;

/**
 * Interface for reporting events during the build. It extends the {@link EventHandler} by also
 * allowing posting more structured information.
 */
public interface ExtendedEventHandler extends EventHandler {

  public static final ExtendedEventHandler NOOP =
      new ExtendedEventHandler() {
        @Override
        public void handle(Event event) {}

        @Override
        public void post(Postable obj) {}
      };

  /** An event that can be posted via the extended event handler. */
  interface Postable extends Reportable {

    @Override
    default void reportTo(ExtendedEventHandler handler) {
      handler.post(this);
    }

    @Override
    default Postable withTag(@Nullable String tag) {
      return this; // No tag-based filtering.
    }

    /** Replays a sequence of posts on {@code handler}. */
    public static void replayPostsOn(ExtendedEventHandler handler, Iterable<Postable> posts) {
      for (Postable post : posts) {
        handler.post(post);
      }
    }
  }

  /** Posts a {@link Postable} object about an important build event. */
  void post(Postable obj);

  /** A progress event that reports about fetching from a remote site. */
  interface FetchProgress extends Postable {

    /**
     * The resource that was originally requested and uniquely determines the fetch source. The
     * actual fetching may use mirrors, proxies, or similar. The resource need not be an URL, but it
     * has to uniquely identify the particular fetch among all fetch events.
     */
    String getResourceIdentifier();

    /** Human readable description of the progress */
    String getProgress();

    /** Wether the fetch progress reported about is finished already */
    boolean isFinished();
  }

}
