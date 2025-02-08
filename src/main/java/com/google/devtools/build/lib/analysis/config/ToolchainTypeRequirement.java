// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkToolchainTypeRequirement;

/**
 * Describes a requirement on a specific toolchain type.
 *
 * @param toolchainType Returns the label of the toolchain type that is requested.
 * @param mandatory Returns whether the toolchain type is mandatory or optional. An optional
 *     toolchain type which cannot be found will be skipped, but a mandatory toolchain type which
 *     cannot be found will stop the build with an error.
 * @param ignoreIfInvalid Returns whether the toolchain type should be ignored if it is found to be
 *     invalid. This should only be used for internally-generated requirements, not user-generated.
 */
@AutoCodec
public record ToolchainTypeRequirement(
    @Override Label toolchainType, @Override boolean mandatory, boolean ignoreIfInvalid)
    implements StarlarkToolchainTypeRequirement {
  public ToolchainTypeRequirement {
    requireNonNull(toolchainType, "toolchainType");
  }

  /** Returns a new {@link ToolchainTypeRequirement}. */
  public static ToolchainTypeRequirement create(Label toolchainType) {
    return builder(toolchainType).build();
  }

  /** Returns a builder for a new {@link ToolchainTypeRequirement}. */
  public static Builder builder(Label toolchainType) {
    return new AutoBuilder_ToolchainTypeRequirement_Builder()
        .toolchainType(toolchainType)
        .mandatory(true)
        .ignoreIfInvalid(false);
  }

  /**
   * Returns the ToolchainTypeRequirement with the strictest restriction, or else the first.
   * Mandatory toolchain type requirements are stricter than optional.
   */
  public static ToolchainTypeRequirement strictest(
      ToolchainTypeRequirement first, ToolchainTypeRequirement second) {
    Preconditions.checkArgument(
        first.toolchainType().equals(second.toolchainType()),
        "Cannot use strictest() for two instances with different type labels.");
    if (first.mandatory()) {
      return first;
    }
    if (second.mandatory()) {
      return second;
    }
    return first;
  }

  /** Returns a new Builder to copy this ToolchainTypeRequirement. */
  public Builder toBuilder() {
    return new AutoBuilder_ToolchainTypeRequirement_Builder(this);
  }

  /** A builder for a new {@link ToolchainTypeRequirement}. */
  @AutoBuilder
  public interface Builder {
    /** Sets the toolchain type. */
    Builder toolchainType(Label toolchainType);
    /** Sets whether the toolchain type is mandatory. */
    Builder mandatory(boolean mandatory);

    Builder ignoreIfInvalid(boolean ignore);

    /** Returns the newly built {@link ToolchainTypeRequirement}. */
    ToolchainTypeRequirement build();
  }
}
