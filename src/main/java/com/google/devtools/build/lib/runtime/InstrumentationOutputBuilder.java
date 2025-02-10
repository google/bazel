// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.OptionsProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Builds different {@link InstrumentationOutput} objects with correct input parameters. */
public interface InstrumentationOutputBuilder {
  /** Sets the name of the {@link InstrumentationOutput}. */
  @CanIgnoreReturnValue
  InstrumentationOutputBuilder setName(String name);

  /**
   * Sets the relative or absolute path for write the {@link LocalInstrumentationOutput} or download
   * the redirect {@link InstrumentationOutput} in the downstream filesystem.
   */
  @CanIgnoreReturnValue
  default InstrumentationOutputBuilder setDestination(PathFragment destination) {
    return this;
  }

  /** Specifies type of directory the output path is relative to. */
  @CanIgnoreReturnValue
  default InstrumentationOutputBuilder setDestinationRelatedToType(
      DestinationRelativeTo relatedToType) {
    return this;
  }

  /** Provides the options necessary for building the {@link InstrumentationOutput}. */
  @CanIgnoreReturnValue
  default InstrumentationOutputBuilder setOptions(OptionsProvider options) {
    return this;
  }

  /** Specifies whether output parent directory should be created. */
  @CanIgnoreReturnValue
  InstrumentationOutputBuilder setCreateParent(boolean createParent);

  /** Builds the {@link InstrumentationOutput} object. */
  InstrumentationOutput build();
}
