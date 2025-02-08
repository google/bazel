// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import javax.annotation.Nullable;

/**
 * Groups together unloaded toolchain contexts and config conditions.
 *
 * <p>These are used together when computing dependencies.
 */
public record DependencyContext(
    @Nullable ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts,
    ConfigConditions configConditions) {
  public DependencyContext {
    requireNonNull(configConditions, "configConditions");
  }

  @Nullable
  public final ToolchainCollection<ToolchainContext> toolchainContexts() {
    if (unloadedToolchainContexts() == null) {
      return null;
    }
    return unloadedToolchainContexts().asToolchainContexts();
  }

  public static DependencyContext create(
      @Nullable ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts,
      ConfigConditions configConditions) {
    return new DependencyContext(unloadedToolchainContexts, configConditions);
  }
}
