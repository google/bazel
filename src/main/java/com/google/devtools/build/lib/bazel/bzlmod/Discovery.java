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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.SequencedMap;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Runs module discovery. This step of module resolution reads the module file of the root module
 * (i.e. the current workspace), adds its direct {@code bazel_dep}s to the dependency graph, and
 * repeats the step for any added dependencies until the entire graph is discovered.
 */
final class Discovery {
  private Discovery() {}

  public record Result(
      ImmutableMap<ModuleKey, InterimModule> depGraph,
      ImmutableMap<String, Optional<Checksum>> registryFileHashes) {}

  /**
   * Runs module discovery. This function follows SkyFunction semantics (returns null if a Skyframe
   * dependency is missing and this function needs a restart).
   */
  @Nullable
  public static Result run(Environment env, RootModuleFileValue root)
      throws InterruptedException, ExternalDepsException {
    String rootModuleName = root.getModule().getName();
    ImmutableMap<String, ModuleOverride> overrides = root.getOverrides();
    Map<ModuleKey, InterimModule> depGraph = new LinkedHashMap<>();
    depGraph.put(
        ModuleKey.ROOT,
        root.getModule()
            .withDepSpecsTransformed(InterimModule.applyOverrides(overrides, rootModuleName)));
    Queue<ModuleKey> unexpanded = new ArrayDeque<>();
    Map<ModuleKey, ModuleKey> predecessors = new HashMap<>();
    SequencedMap<String, Optional<Checksum>> registryFileHashes =
        new LinkedHashMap<>(root.getRegistryFileHashes());
    unexpanded.add(ModuleKey.ROOT);
    while (!unexpanded.isEmpty()) {
      Set<SkyKey> unexpandedSkyKeys = new LinkedHashSet<>();
      while (!unexpanded.isEmpty()) {
        InterimModule module = depGraph.get(unexpanded.remove());
        for (DepSpec depSpec : module.getDeps().values()) {
          if (depGraph.containsKey(depSpec.toModuleKey())) {
            continue;
          }
          predecessors.putIfAbsent(depSpec.toModuleKey(), module.getKey());
          unexpandedSkyKeys.add(
              ModuleFileValue.key(depSpec.toModuleKey(), overrides.get(depSpec.name())));
        }
      }
      SkyframeLookupResult result = env.getValuesAndExceptions(unexpandedSkyKeys);
      for (SkyKey skyKey : unexpandedSkyKeys) {
        ModuleKey depKey = ((ModuleFileValue.Key) skyKey).getModuleKey();
        ModuleFileValue moduleFileValue;
        try {
          moduleFileValue =
              (ModuleFileValue) result.getOrThrow(skyKey, ExternalDepsException.class);
        } catch (ExternalDepsException e) {
          if (e.getDetailedExitCode().getFailureDetail() == null
              || e.getDetailedExitCode().getFailureDetail().getExternalDeps().getCode()
                  != FailureDetails.ExternalDeps.Code.BAD_MODULE) {
            // This is not due to a bad module, so don't print a dependency chain. This covers cases
            // such as a parse error in the lockfile or an I/O exception during registry access,
            // which aren't related to any particular module dep.
            throw e;
          }
          // Trace back a dependency chain to the root module. There can be multiple paths to the
          // failing module, but any of those is useful for debugging.
          List<ModuleKey> depChain = new ArrayList<>();
          depChain.add(depKey);
          ModuleKey predecessor = depKey;
          while ((predecessor = predecessors.get(predecessor)) != null) {
            depChain.add(predecessor);
          }
          Collections.reverse(depChain);
          String depChainString =
              depChain.stream().map(ModuleKey::toString).collect(joining(" -> "));
          throw ExternalDepsException.withCauseAndMessage(
              FailureDetails.ExternalDeps.Code.BAD_MODULE,
              e,
              "in module dependency chain %s",
              depChainString);
        }
        if (moduleFileValue == null) {
          // Don't return yet. Try to expand any other unexpanded nodes before returning.
          depGraph.put(depKey, null);
        } else {
          depGraph.put(
              depKey,
              moduleFileValue
                  .getModule()
                  .withDepSpecsTransformed(
                      InterimModule.applyOverrides(overrides, rootModuleName)));
          registryFileHashes.putAll(moduleFileValue.getRegistryFileHashes());
          unexpanded.add(depKey);
        }
      }
    }
    if (env.valuesMissing()) {
      return null;
    }
    return new Result(ImmutableMap.copyOf(depGraph), ImmutableMap.copyOf(registryFileHashes));
  }
}
