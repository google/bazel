// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Build event announcing supplementary metadata accompanying the build in the form of key-value
 * string pairs.
 */
public class BuildMetadataEvent implements BuildEventWithOrderConstraint {

  private final ListMultimap<String, String> buildMetadata;

  /**
   * Construct the build metadata event.
   *
   * @param buildMetadata the supplementary build metadata for a single build.
   */
  public BuildMetadataEvent(ListMultimap<String, String> buildMetadata) {
    this.buildMetadata = buildMetadata;
  }

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.buildMetadataId();
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext converters) {
    BuildEventStreamProtos.BuildMetadata.Builder metadataBuilder =
        BuildEventStreamProtos.BuildMetadata.newBuilder();
    for (String key : buildMetadata.keySet()) {
      List<String> values = buildMetadata.get(key);
      metadataBuilder.putMetadata(key, values.get(values.size() - 1));
      if (values.size() > 1) {
        metadataBuilder.putExtraValues(
            key,
            BuildEventStreamProtos.BuildMetadata.ExtraValues.newBuilder()
                .addAllValues(values.subList(0, values.size()-1))
                .build());
      }
    }
    return GenericBuildEvent.protoChaining(this).setBuildMetadata(metadataBuilder.build()).build();
  }

  @Override
  public Collection<BuildEventId> postedAfter() {
    return ImmutableList.of(BuildEventIdUtil.buildStartedId());
  }
}
