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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.skyframe.IntVersion;
import java.util.Optional;

/** Information for a workspace computed at the time of collecting diff. */
public interface WorkspaceInfoFromDiff {

  default IntVersion getEvaluatingVersion() {
    // TODO: b/367284400 - handle this for external version control systems.
    return IntVersion.of(Long.MIN_VALUE);
  }

  default Optional<ClientId> getSnapshot() {
    // TODO: b/367284400 - handle this for external version control systems.
    return Optional.empty();
  }
}
