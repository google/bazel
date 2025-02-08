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

package com.google.devtools.build.lib.analysis.constraints;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;

/**
 * Targets that have additional restrictions based on the current platform.
 *
 * @param targetsToSkip Targets that need be skipped.
 * @param targetsWithErrors Targets that should be skipped, but were explicitly requested on the
 *     command line.
 */
public record PlatformRestrictionsResult(
    ImmutableSet<ConfiguredTarget> targetsToSkip,
    ImmutableSet<ConfiguredTarget> targetsWithErrors) {
  public PlatformRestrictionsResult {
    requireNonNull(targetsToSkip, "targetsToSkip");
    requireNonNull(targetsWithErrors, "targetsWithErrors");
  }

  public static Builder builder() {
    return new AutoBuilder_PlatformRestrictionsResult_Builder()
        .targetsToSkip(ImmutableSet.of())
        .targetsWithErrors(ImmutableSet.of());
  }

  /** {@link PlatformRestrictionsResult}Builder. */
  @AutoBuilder
  public interface Builder {
    Builder targetsToSkip(ImmutableSet<ConfiguredTarget> targetsToSkip);

    Builder targetsWithErrors(ImmutableSet<ConfiguredTarget> targetsWithErrors);

    PlatformRestrictionsResult build();
  }
}
