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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/**
 * Identifies a repo rule.
 *
 * @param bzlFileLabel The label pointing to the .bzl file defining the repo rule, or null if this
 *     is a native repo rule.
 * @param ruleName The name of the repo rule.
 */
@AutoCodec
public record RepoRuleId(@Nullable Label bzlFileLabel, String ruleName) {
  public boolean isNative() {
    return bzlFileLabel == null;
  }

  @Override
  public String toString() {
    if (bzlFileLabel == null) {
      return ruleName;
    }
    return bzlFileLabel.getUnambiguousCanonicalForm() + "%" + ruleName;
  }
}
