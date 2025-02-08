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

package com.google.devtools.build.lib.runtime;

import com.google.auto.value.AutoBuilder;
import com.google.common.annotations.VisibleForTesting;

/** A memory pressure event. */
public record MemoryPressureEvent(
    boolean wasManualGc,
    boolean wasGcLockerInitiatedGc,
    boolean wasFullGc,
    long tenuredSpaceUsedBytes,
    long tenuredSpaceMaxBytes) {

  public final int percentTenuredSpaceUsed() {
    return (int) ((tenuredSpaceUsedBytes() * 100L) / tenuredSpaceMaxBytes());
  }

  @VisibleForTesting
  public static Builder newBuilder() {
    return new AutoBuilder_MemoryPressureEvent_Builder()
        .setWasManualGc(false)
        .setWasGcLockerInitiatedGc(false)
        .setWasFullGc(false);
  }

  /** A memory pressure event builder. */
  @VisibleForTesting
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder setWasManualGc(boolean value);

    public abstract Builder setWasGcLockerInitiatedGc(boolean value);

    public abstract Builder setWasFullGc(boolean value);

    public abstract Builder setTenuredSpaceUsedBytes(long value);

    public abstract Builder setTenuredSpaceMaxBytes(long value);

    public abstract MemoryPressureEvent build();
  }
}
