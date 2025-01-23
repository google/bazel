// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.util.OptionsUtils;
import java.util.Collection;
import java.util.List;

/** A build event reporting the original commandline by which bazel was invoked. */
public class OriginalUnstructuredCommandLineEvent implements BuildEventWithOrderConstraint {
  static final OriginalUnstructuredCommandLineEvent REDACTED_UNSTRUCTURED_COMMAND_LINE_EVENT =
      new OriginalUnstructuredCommandLineEvent(ImmutableList.of("REDACTED"));
  private final ImmutableList<String> args;

  OriginalUnstructuredCommandLineEvent(List<String> args) {
    this.args = OptionsUtils.scrubArgs(ImmutableList.copyOf(args));
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.unstructuredCommandlineId();
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public Collection<BuildEventId> postedAfter() {
    return ImmutableList.of(BuildEventIdUtil.buildStartedId());
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    return GenericBuildEvent.protoChaining(this)
        .setUnstructuredCommandLine(
            BuildEventStreamProtos.UnstructuredCommandLine.newBuilder().addAllArgs(args).build())
        .build();
  }
}
