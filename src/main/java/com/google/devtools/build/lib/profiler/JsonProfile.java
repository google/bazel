// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.profiler;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.devtools.build.lib.profiler.statistics.PhaseSummaryStatistics;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/**
 * Utility class to handle parsing the JSON trace profiles.
 *
 * <p>The format itself is documented in
 * https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview
 */
public final class JsonProfile {
  private BuildMetadata buildMetadata;
  private PhaseSummaryStatistics phaseSummaryStatistics;
  private List<TraceEvent> traceEvents;

  public JsonProfile(File profileFile) throws IOException {
    this(getInputStream(profileFile));
  }

  public JsonProfile(InputStream inputStream) throws IOException {
    try (JsonReader reader =
        new JsonReader(new BufferedReader(new InputStreamReader(inputStream, ISO_8859_1)))) {
      if (reader.peek() == JsonToken.BEGIN_OBJECT) {
        reader.beginObject();
        while (reader.hasNext()) {
          String objectKey = reader.nextName();
          if ("otherData".equals(objectKey)) {
            buildMetadata = parseBuildMetadata(reader);
          } else if ("traceEvents".equals(objectKey)) {
            traceEvents = TraceEvent.parseTraceEvents(reader);
            phaseSummaryStatistics = new PhaseSummaryStatistics();
            TraceEvent lastPhaseEvent = null;
            Duration maxEndTime = Duration.ZERO;
            for (TraceEvent traceEvent : traceEvents) {
              if (traceEvent.timestamp() != null) {
                Duration curEndTime = traceEvent.timestamp();
                if (traceEvent.duration() != null) {
                  curEndTime = curEndTime.plus(traceEvent.duration());
                }
                if (curEndTime.compareTo(maxEndTime) > 0) {
                  maxEndTime = curEndTime;
                }
              }
              if (ProfilerTask.PHASE.description.equals(traceEvent.category())) {
                if (lastPhaseEvent != null) {
                  phaseSummaryStatistics.addProfilePhase(
                      ProfilePhase.getPhaseFromDescription(lastPhaseEvent.name()),
                      traceEvent.timestamp().minus(lastPhaseEvent.timestamp()));
                }
                lastPhaseEvent = traceEvent;
              }
            }
            if (lastPhaseEvent != null) {
              phaseSummaryStatistics.addProfilePhase(
                  ProfilePhase.getPhaseFromDescription(lastPhaseEvent.name()),
                  maxEndTime.minus(lastPhaseEvent.timestamp()));
            }
          } else {
            reader.skipValue();
          }
        }
      }
    }
    if (traceEvents == null) {
      throw new IOException("Corrupted profile file: couldn't find 'traceEvents'.");
    }
  }

  private static InputStream getInputStream(File profileFile) throws IOException {
    InputStream inputStream = new FileInputStream(profileFile);
    if (profileFile.getName().endsWith(".gz")) {
      inputStream = new GZIPInputStream(inputStream);
    }
    return inputStream;
  }

  private static BuildMetadata parseBuildMetadata(JsonReader reader) throws IOException {
    reader.beginObject();
    String buildId = null;
    String date = null;
    String outputBase = null;
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case "build_id" -> buildId = reader.nextString();
        case "date" -> date = reader.nextString();
        case "output_base" -> outputBase = reader.nextString();
        default -> reader.skipValue();
      }
    }
    reader.endObject();

    return BuildMetadata.create(buildId, date, outputBase);
  }

  public PhaseSummaryStatistics getPhaseSummaryStatistics() {
    return phaseSummaryStatistics;
  }

  public List<TraceEvent> getTraceEvents() {
    return traceEvents;
  }

  @Nullable
  public BuildMetadata getBuildMetadata() {
    return buildMetadata;
  }

  /** Value class to hold build metadata (id, date, output base) if available. */
  public record BuildMetadata(
      @Nullable String buildId, @Nullable String date, @Nullable String outputBase) {
    public static BuildMetadata create(
        @Nullable String buildId, @Nullable String date, @Nullable String outputBase) {
      return new BuildMetadata(buildId, date, outputBase);
    }

  }
}
