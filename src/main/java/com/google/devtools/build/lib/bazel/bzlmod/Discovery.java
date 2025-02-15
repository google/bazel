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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    // Because of the possible existence of nodep edges, we do multiple rounds of discovery.
    // In each round, we keep track of unfulfilled nodep edges, and at the end of the round, if any
    // unfulfilled nodep edge can now be fulfilled, we run another round.
    ImmutableSet<String> prevRoundModuleNames = ImmutableSet.of(root.module().getName());
    while (true) {
      DiscoveryRound discoveryRound = new DiscoveryRound(env, root, prevRoundModuleNames);
      Result result = discoveryRound.run();
      if (result == null) {
        return null;
      }
      prevRoundModuleNames =
          result.depGraph().values().stream().map(InterimModule::getName).collect(toImmutableSet());
      if (discoveryRound.unfulfilledNodepEdgeModuleNames.stream()
          .noneMatch(prevRoundModuleNames::contains)) {
        return result;
      }
    }
  }

  private static class DiscoveryRound {
    private final Environment env;
    private final RootModuleFileValue root;
    private final ImmutableSet<String> prevRoundModuleNames;
    private final Map<ModuleKey, InterimModule> depGraph = new LinkedHashMap<>();

    /**
     * Stores a mapping from a module to its "predecessor" -- that is, its first dependent in BFS
     * order. This is used to report a dependency chain in errors (see {@link
     * #maybeReportDependencyChain}.
     */
    private final Map<ModuleKey, ModuleKey> predecessors = new HashMap<>();

    /**
     * For all unfulfilled nodep edges seen during this round, this set stores the module names of
     * those nodep edges. Remember that whether a nodep edge can be fulfilled depends on whether the
     * module it names already exists in the dep graph.
     */
    private final Set<String> unfulfilledNodepEdgeModuleNames = new HashSet<>();

    DiscoveryRound(
        Environment env, RootModuleFileValue root, ImmutableSet<String> prevRoundModuleNames) {
      this.env = env;
      this.root = root;
      this.prevRoundModuleNames = prevRoundModuleNames;
    }

    /**
     * Runs one round of discovery. At its core, this is a simple breadth-first search: we start
     * from the "horizon" of just the root module, and advance the horizon by discovering the
     * dependencies of modules in the current horizon. Keep doing this until the horizon is empty.
     */
    @Nullable
    Result run() throws InterruptedException, ExternalDepsException {
      SequencedMap<String, Optional<Checksum>> registryFileHashes = new LinkedHashMap<>();
      depGraph.put(ModuleKey.ROOT, root.module().withDepSpecsTransformed(this::applyOverrides));
      ImmutableSet<ModuleKey> horizon = ImmutableSet.of(ModuleKey.ROOT);
      while (!horizon.isEmpty()) {
        ImmutableSet<ModuleFileValue.Key> nextHorizonSkyKeys = advanceHorizon(horizon);
        SkyframeLookupResult result = env.getValuesAndExceptions(nextHorizonSkyKeys);
        var nextHorizon = ImmutableSet.<ModuleKey>builder();
        for (ModuleFileValue.Key skyKey : nextHorizonSkyKeys) {
          ModuleKey depKey = skyKey.moduleKey();
          ModuleFileValue moduleFileValue;
          try {
            moduleFileValue =
                (ModuleFileValue) result.getOrThrow(skyKey, ExternalDepsException.class);
          } catch (ExternalDepsException e) {
            throw maybeReportDependencyChain(e, depKey);
          }
          if (moduleFileValue == null) {
            // Don't return yet. Try to expand any other unexpanded nodes before returning.
            depGraph.put(depKey, null);
          } else {
            depGraph.put(
                depKey, moduleFileValue.module().withDepSpecsTransformed(this::applyOverrides));
            registryFileHashes.putAll(moduleFileValue.registryFileHashes());
            nextHorizon.add(depKey);
          }
        }
        horizon = nextHorizon.build();
      }
      if (env.valuesMissing()) {
        return null;
      }
      return new Result(ImmutableMap.copyOf(depGraph), ImmutableMap.copyOf(registryFileHashes));
    }

    /**
     * Returns a new {@link DepSpec} that is transformed according to any existing overrides on the
     * dependency module.
     */
    DepSpec applyOverrides(DepSpec depSpec) {
      if (root.module().getName().equals(depSpec.name())) {
        return DepSpec.fromModuleKey(ModuleKey.ROOT);
      }
      Version newVersion =
          switch (root.overrides().get(depSpec.name())) {
            case NonRegistryOverride nro -> Version.EMPTY;
            case SingleVersionOverride svo when !svo.version().isEmpty() -> svo.version();
            case null, default -> depSpec.version();
          };
      return new DepSpec(depSpec.name(), newVersion, depSpec.maxCompatibilityLevel());
    }

    /**
     * Given a set of module keys to discover (the current "horizon"), return the next horizon
     * consisting of newly discovered module keys from the current set (mostly, their dependencies).
     *
     * <p>The current horizon contains keys to modules that are already in the {@code depGraph}.
     * Note also that this method mutates {@code predecessors} and {@code
     * unfulfilledNodepEdgeModuleNames}.
     */
    ImmutableSet<ModuleFileValue.Key> advanceHorizon(ImmutableSet<ModuleKey> horizon) {
      var nextHorizon = ImmutableSet.<ModuleFileValue.Key>builder();
      for (ModuleKey moduleKey : horizon) {
        InterimModule module = depGraph.get(moduleKey);
        // The main group of module keys to discover are the current horizon's normal deps.
        for (DepSpec depSpec : module.getDeps().values()) {
          ModuleKey depKey = depSpec.toModuleKey();
          if (depGraph.containsKey(depKey)) {
            continue;
          }
          predecessors.putIfAbsent(depKey, module.getKey());
          nextHorizon.add(ModuleFileValue.key(depKey));
        }
        // Any of the current horizon's nodep deps should also be discovered ("fulfilled"), iff the
        // module they refer to already exists in the dep graph. Otherwise, record these unfulfilled
        // nodep edges, so that we can later decide whether to run another round of discovery.
        for (DepSpec depSpec : module.getNodepDeps()) {
          ModuleKey depKey = depSpec.toModuleKey();
          if (depGraph.containsKey(depKey)) {
            continue;
          }
          if (!prevRoundModuleNames.contains(depSpec.name())) {
            unfulfilledNodepEdgeModuleNames.add(depSpec.name());
            continue;
          }
          predecessors.putIfAbsent(depKey, module.getKey());
          nextHorizon.add(ModuleFileValue.key(depKey));
        }
      }
      return nextHorizon.build();
    }

    /**
     * When an exception occurs while discovering a new dep, try to add information about the
     * dependency chain that led to that dep.
     */
    private ExternalDepsException maybeReportDependencyChain(
        ExternalDepsException e, ModuleKey depKey) {
      if (e.getDetailedExitCode().getFailureDetail() == null
          || e.getDetailedExitCode().getFailureDetail().getExternalDeps().getCode()
              != FailureDetails.ExternalDeps.Code.BAD_MODULE) {
        // This is not due to a bad module, so don't print a dependency chain. This covers cases
        // such as a parse error in the lockfile or an I/O exception during registry access,
        // which aren't related to any particular module dep.
        return e;
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
      String depChainString = depChain.stream().map(ModuleKey::toString).collect(joining(" -> "));
      return ExternalDepsException.withCauseAndMessage(
          FailureDetails.ExternalDeps.Code.BAD_MODULE,
          e,
          "in module dependency chain %s",
          depChainString);
    }
  }
}
