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
package com.google.devtools.common.options;

import com.google.common.collect.ImmutableList;
import java.util.function.Predicate;

/** Utility functions for global RC files. */
public final class GlobalRcUtils {

  private GlobalRcUtils() {}

  /* No global RC files in Bazel, so no global configs. */
  public static final ImmutableList<String> ALLOWED_GLOBAL_CONFIGS = ImmutableList.of();

  private static final ImmutableList<String> GLOBAL_RC_FILES =
      ImmutableList.of(
          // LINT.IfChange
          "client",
          // LINT.ThenChange(//src/main/cpp/option_processor.cc)
          // LINT.IfChange
          "Invocation policy"
          // LINT.ThenChange(//src/main/java/com/google/devtools/common/options/InvocationPolicyEnforcer.java)
          );

  /** No global RC files in Bazel. Consider "client" options to be global. */
  static final Predicate<ParsedOptionDescription> IS_GLOBAL_RC_OPTION =
      // LINT.IfChange
      (option) -> {
        for (String globalRc : GLOBAL_RC_FILES) {
          // Don't match the full RC file location to be resilient to builds with the same global
          // RC but different workspaces.
          if (option.getOrigin().getSource() != null
              && option.getOrigin().getSource().endsWith(globalRc)) {
            return true;
          }
          if (option.getExpandedFrom() != null) {
            if (option.getExpandedFrom().getOrigin().getSource() != null
                && option.getExpandedFrom().getOrigin().getSource().endsWith(globalRc)) {
              return true;
            }
          }
        }
        return false;
      };
  // LINT.ThenChange(//src/main/cpp/option_processor.cc,
  // src/main/java/com/google/devtools/common/options/InvocationPolicyEnforcer.java

  /** Is an rc file path a global rc? */
  public static boolean isGlobalRcFile(String rcFilePath) {
    for (String globalRc : GLOBAL_RC_FILES) {
      if (rcFilePath.endsWith(globalRc)) {
        return true;
      }
    }
    return false;
  }
}
