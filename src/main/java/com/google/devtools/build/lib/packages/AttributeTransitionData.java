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
package com.google.devtools.build.lib.packages;

import com.google.auto.value.AutoBuilder;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.cmdline.Label;
import javax.annotation.Nullable;

/**
 * Helper class which contains data used by a {@link TransitionFactory} to create a transition for
 * attributes.
 *
 * @param attributes Returns the {@link AttributeMap} which can be used to create a transition.
 * @param executionPlatform Returns the {@link Label} of the execution platform used by the
 *     configured target this transition factory is part of.
 * @param analysisData Optional parameter to let callers instantiate objects that the {@code
 *     lib.packages} library can't resolve. This class is both defined in {@code lib.packages} and
 *     referenced by other files in that package.
 *     <p>Callers are responsible for ensuring correct casting between writes and reads.
 */
public record AttributeTransitionData(
    @Nullable AttributeMap attributes,
    @Nullable Label executionPlatform,
    @Nullable Object analysisData)
    implements TransitionFactory.Data {

  /** Returns a new {@link Builder} for {@link AttributeTransitionData}. */
  public static Builder builder() {
    return new AutoBuilder_AttributeTransitionData_Builder();
  }

  /** Builder class for {@link AttributeTransitionData}. */
  @AutoBuilder
  public abstract static class Builder {
    /** Sets the attributes. */
    public abstract Builder attributes(@Nullable AttributeMap attributes);

    /** Sets the execution platform label. */
    public abstract Builder executionPlatform(@Nullable Label executionPlatform);

    public abstract Builder analysisData(Object analysisData);

    /** Returns the new {@link AttributeTransitionData}. */
    public abstract AttributeTransitionData build();
  }
}
