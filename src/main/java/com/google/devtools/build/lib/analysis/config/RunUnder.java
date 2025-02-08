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
package com.google.devtools.build.lib.analysis.config;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/** Components of the {@code --run_under} option. */
public sealed interface RunUnder {
  /**
   * @return the whole value passed to --run_under option.
   */
  String value();

  /**
   * Returns everything except the first word (according to shell tokenization) passed to {@code
   * --run_under}.
   */
  ImmutableList<String> options();

  /**
   * Returns a new instance that only retains the information that is relevant for the analysis of
   * non-test targets.
   */
  @Nullable
  static RunUnder trimForNonTestConfiguration(@Nullable RunUnder runUnder) {
    return switch (runUnder) {
      case LabelRunUnder labelRunUnder ->
          new LabelRunUnder("", ImmutableList.of(), labelRunUnder.label());
      case null, default -> null;
    };
  }

  /**
   * Represents a value of {@code --run_under} whose first word (according to shell tokenization)
   * starts with {@code "//"} or {@code "@"}. It is treated as a label referencing a target that
   * should be used as the {@code --run_under} executable.
   */
  @AutoCodec
  record LabelRunUnder(String value, ImmutableList<String> options, Label label)
      implements RunUnder {}

  /**
   * Represents a value of {@code --run_under} whose first word (according to shell tokenization)
   * does not start with {@code "//"} or {@code "@"}. It is treated as a shell command.
   */
  @AutoCodec
  record CommandRunUnder(String value, ImmutableList<String> options, String command)
      implements RunUnder {}
}
