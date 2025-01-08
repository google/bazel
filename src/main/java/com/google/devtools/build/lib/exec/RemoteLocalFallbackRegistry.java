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

package com.google.devtools.build.lib.exec;

import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.Spawn;
import javax.annotation.Nullable;

/**
 * Registry providing the resolved strategy to use when falling back from remote to local execution.
 */
public interface RemoteLocalFallbackRegistry extends ActionContext {

  /**
   * Returns the resolved strategy implementation to use for falling back from remote to local
   * execution.
   *
   * @return remote fallback strategy or {@code null} if none was registered
   */
  @Nullable
  AbstractSpawnStrategy getRemoteLocalFallbackStrategy(Spawn spawn);
}
