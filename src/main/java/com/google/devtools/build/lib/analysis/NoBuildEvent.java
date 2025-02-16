// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.buildeventstream.ProgressEvent;
import com.google.protobuf.util.Timestamps;

/** This event raised to indicate that no build will be happening for the given command. */
public final class NoBuildEvent implements BuildEvent {
  private final String id;
  private final String command;
  private final Long startTimeMillis;
  private final boolean separateFinishedEvent;
  private final boolean showProgress;

  public NoBuildEvent(
      String command,
      Long startTimeMillis,
      boolean separateFinishedEvent,
      boolean showProgress,
      String id) {
    this.command = command;
    this.startTimeMillis = startTimeMillis;
    this.separateFinishedEvent = separateFinishedEvent;
    this.showProgress = showProgress;
    this.id = id;
  }

  public NoBuildEvent(String command, Long startTimeMillis, boolean separateFinishedEvent) {
    this(command, startTimeMillis, separateFinishedEvent, false, null);
  }

  public NoBuildEvent() {
    this(null, null, false);
  }

  @Override
  public ImmutableList<BuildEventId> getChildrenEvents() {
    if (separateFinishedEvent) {
      return ImmutableList.of(
          ProgressEvent.INITIAL_PROGRESS_UPDATE, BuildEventIdUtil.buildFinished());
    } else {
      return ImmutableList.of(ProgressEvent.INITIAL_PROGRESS_UPDATE);
    }
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.buildStartedId();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.BuildStarted.Builder started =
        BuildEventStreamProtos.BuildStarted.newBuilder()
            .setBuildToolVersion(BlazeVersionInfo.instance().getVersion());
    if (command != null) {
      started.setCommand(command);
    }
    if (startTimeMillis != null) {
      started.setStartTimeMillis(startTimeMillis).setStartTime(Timestamps.fromMillis(startTimeMillis));
    }
    if (id != null) {
      started.setUuid(id);
    }
    started.setServerPid(ProcessHandle.current().pid());
    return GenericBuildEvent.protoChaining(this).setStarted(started.build()).build();
  }

  /**
   * Iff true, clients will expect to a receive a separate {@link
   * com.google.devtools.build.lib.buildeventstream.BuildCompletingEvent}.
   */
  public boolean separateFinishedEvent() {
    return separateFinishedEvent;
  }

  public boolean showProgress() {
    return showProgress;
  }
}
