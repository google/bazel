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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static java.util.Comparator.naturalOrder;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Runs module selection. This step of module resolution reads the output of {@link Discovery} and
 * applies the Minimal Version Selection algorithm to it, removing unselected modules from the
 * dependency graph and rewriting dependencies to point to the selected versions. It also returns an
 * un-pruned version of the dep graph for inspection purpose.
 *
 * <p>Essentially, what needs to happen is:
 *
 * <ul>
 *   <li>In the most basic case, only one version of each module is selected (ie. remains in the dep
 *       graph). The selected version is simply the highest among all existing versions in the dep
 *       graph. In other words, each module name forms a "selection group". If foo@1.5 is selected,
 *       then any other foo@X is removed from the dep graph, and any module depending on foo@X will
 *       depend on foo@1.5 instead.
 *   <li>As an extension of the above, we also remove any module that becomes unreachable from the
 *       root module because of the removal of some other module.
 *   <li>If, however, versions of the same module but with different compatibility levels exist in
 *       the dep graph, then one version is selected for each compatibility level (ie. we split the
 *       selection groups by compatibility level). In the end, though, still only one version can
 *       remain in the dep graph after the removal of unselected and unreachable modules.
 *   <li>Things get more complicated with multiple-version overrides. If module foo has a
 *       multiple-version override which allows versions [1.3, 1.5, 2.0] (using the major version as
 *       the compatibility level), then we further split the selection groups by the target allowed
 *       version (keep in mind that versions are upgraded to the nearest higher-or-equal allowed
 *       version at the same compatibility level). If, for example, some module depends on foo@1.0,
 *       then it'll depend on foo@1.3 post-selection instead (and foo@1.0 will be removed). If any
 *       of foo@1.7, foo@2.2, or foo@3.0 exist in the dependency graph before selection, they must
 *       be removed before the end of selection (by becoming unreachable, for example), otherwise
 *       it'll be an error since they're not allowed by the override (these versions are in
 *       selection groups that have no valid target allowed version).
 *   <li>Things get even more complicated with max_compatibility_level. The difference this
 *       introduces is that each "DepSpec" could be satisfied by one of multiple choices. (Without
 *       max_compatibility_level, there is always only one choice.) So what we do is go through all
 *       the combinations of possible choices for each distinct DepSpec, and for each combination,
 *       see if the resulting dep graph is valid. As soon as we find a valid combination, we return
 *       that result. The distinct DepSpecs are sorted by the order they first appear in the dep
 *       graph if we BFS from the root module. The combinations are attempted in the typical
 *       cartesian product order (see {@link Lists#cartesianProduct}); the "version choices" of each
 *       DepSpec are sorted from low to high.
 * </ul>
 */
final class Selection {
  private Selection() {}

  /**
   * The result of selection.
   *
   * @param resolvedDepGraph Final dep graph sorted in BFS iteration order, with unused modules
   *     removed.
   * @param unprunedDepGraph Un-pruned dep graph, with updated dep keys, and additionally containing
   *     the unused modules which were initially discovered (and their MODULE.bazel files loaded).
   *     Does not contain modules overridden by {@code single_version_override} or {@link
   *     NonRegistryOverride}, only by {@code multiple_version_override}.
   */
  record Result(
      ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph,
      ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph) {
    Result {
      requireNonNull(resolvedDepGraph, "resolvedDepGraph");
      requireNonNull(unprunedDepGraph, "unprunedDepGraph");
    }

    static Result create(
        ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph,
        ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph) {
      return new Result(resolvedDepGraph, unprunedDepGraph);
    }
  }

  /**
   * During selection, a version is selected for each distinct "selection group".
   *
   * @param targetAllowedVersion This is only used for modules with multiple-version overrides.
   */
  record SelectionGroup(String moduleName, int compatibilityLevel, Version targetAllowedVersion) {
    SelectionGroup {
      requireNonNull(moduleName, "moduleName");
      requireNonNull(targetAllowedVersion, "targetAllowedVersion");
    }

    static SelectionGroup create(
        String moduleName, int compatibilityLevel, Version targetAllowedVersion) {
      return new SelectionGroup(moduleName, compatibilityLevel, targetAllowedVersion);
    }
  }

  record ModuleNameAndCompatibilityLevel(
      @SuppressWarnings("unused") String moduleName,
      @SuppressWarnings("unused") int compatibilityLevel) {
    ModuleNameAndCompatibilityLevel {
      requireNonNull(moduleName, "moduleName");
    }

    static ModuleNameAndCompatibilityLevel create(String moduleName, int compatibilityLevel) {
      return new ModuleNameAndCompatibilityLevel(moduleName, compatibilityLevel);
    }
  }

  /**
   * Computes a mapping from (moduleName, compatibilityLevel) to the set of allowed versions. This
   * is only performed for modules with multiple-version overrides.
   */
  private static ImmutableMap<ModuleNameAndCompatibilityLevel, ImmutableSortedSet<Version>>
      computeAllowedVersionSets(
          ImmutableMap<String, ModuleOverride> overrides,
          ImmutableMap<ModuleKey, InterimModule> depGraph)
          throws ExternalDepsException {
    Map<ModuleNameAndCompatibilityLevel, ImmutableSortedSet.Builder<Version>> allowedVersionSets =
        new HashMap<>();
    for (Map.Entry<String, ModuleOverride> overrideEntry : overrides.entrySet()) {
      String moduleName = overrideEntry.getKey();
      ModuleOverride override = overrideEntry.getValue();
      if (!(override instanceof MultipleVersionOverride)) {
        continue;
      }
      ImmutableList<Version> allowedVersions = ((MultipleVersionOverride) override).versions();
      for (Version allowedVersion : allowedVersions) {
        InterimModule allowedVersionModule =
            depGraph.get(new ModuleKey(moduleName, allowedVersion));
        if (allowedVersionModule == null) {
          throw ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR,
              "multiple_version_override for module %s contains version %s, but it doesn't"
                  + " exist in the dependency graph",
              moduleName,
              allowedVersion);
        }
        ImmutableSortedSet.Builder<Version> allowedVersionSet =
            allowedVersionSets.computeIfAbsent(
                ModuleNameAndCompatibilityLevel.create(
                    moduleName, allowedVersionModule.getCompatibilityLevel()),
                // Remember that the empty version compares greater than any other version, so we
                // can use it as a sentinel value.
                k -> ImmutableSortedSet.<Version>naturalOrder().add(Version.EMPTY));
        allowedVersionSet.add(allowedVersion);
      }
    }
    return ImmutableMap.copyOf(
        Maps.transformValues(allowedVersionSets, ImmutableSortedSet.Builder::build));
  }

  /**
   * Computes the {@link SelectionGroup} for the given module. If the module has a multiple-version
   * override (which would be reflected in the allowedVersionSets), information in there will be
   * used to compute its targetAllowedVersion.
   */
  private static SelectionGroup computeSelectionGroup(
      InterimModule module,
      ImmutableMap<ModuleNameAndCompatibilityLevel, ImmutableSortedSet<Version>>
          allowedVersionSets) {
    ImmutableSortedSet<Version> allowedVersionSet =
        allowedVersionSets.get(
            ModuleNameAndCompatibilityLevel.create(
                module.getName(), module.getCompatibilityLevel()));
    if (allowedVersionSet == null) {
      // This means that this module has no multiple-version override.
      return SelectionGroup.create(
          module.getKey().name(), module.getCompatibilityLevel(), Version.EMPTY);
    }
    return SelectionGroup.create(
        module.getKey().name(),
        module.getCompatibilityLevel(),
        // We use the `ceiling` method here to quickly locate the lowest allowed version that's
        // still no lower than this module's version.
        // If this module's version is higher than any allowed version (in which case EMPTY is
        // returned), it should result in an error. We don't immediately throw here because it might
        // still become unreferenced later.
        allowedVersionSet.ceiling(module.getVersion()));
  }

  /**
   * Computes the possible list of ModuleKeys a single given DepSpec can resolve to. This is
   * normally just one ModuleKey, but when max_compatibility_level is involved, multiple choices may
   * be possible.
   */
  private static ImmutableList<ModuleKey> computePossibleResolutionResultsForOneDepSpec(
      DepSpec depSpec,
      ImmutableMap<ModuleKey, SelectionGroup> selectionGroups,
      Map<SelectionGroup, Version> selectedVersions) {
    int minCompatibilityLevel = selectionGroups.get(depSpec.toModuleKey()).compatibilityLevel();
    int maxCompatibilityLevel =
        depSpec.maxCompatibilityLevel() < 0
            ? minCompatibilityLevel
            : depSpec.maxCompatibilityLevel();
    // First find the selection groups that this DepSpec could use.
    return Maps.filterKeys(
            selectedVersions,
            group ->
                group.moduleName().equals(depSpec.name())
                    && group.compatibilityLevel() >= minCompatibilityLevel
                    && group.compatibilityLevel() <= maxCompatibilityLevel
                    && group.targetAllowedVersion().compareTo(depSpec.version()) >= 0)
        .entrySet()
        .stream()
        // Collect into an ImmutableSortedMap so that:
        //  1. The final list is sorted by compatibility level, guaranteeing lowest version first;
        //  2. Only one ModuleKey is attempted per compatibility level, so that in the case of a
        //     multiple-version override, we only try the lowest allowed version in that
        //     compatibility level (note the Comparators::min call).
        .collect(
            toImmutableSortedMap(
                naturalOrder(),
                e -> e.getKey().compatibilityLevel(),
                e -> e.getValue(),
                Comparators::min))
        .values()
        .stream()
        .map(v -> new ModuleKey(depSpec.name(), v))
        .collect(toImmutableList());
  }

  /**
   * Computes the possible list of ModuleKeys a DepSpec can resolve to, for all distinct DepSpecs in
   * the dependency graph.
   */
  private static ImmutableMap<DepSpec, ImmutableList<ModuleKey>> computePossibleResolutionResults(
      ImmutableMap<ModuleKey, InterimModule> depGraph,
      ImmutableMap<ModuleKey, SelectionGroup> selectionGroups,
      Map<SelectionGroup, Version> selectedVersions) {
    // Important that we use a LinkedHashMap here to ensure reproducibility.
    Map<DepSpec, ImmutableList<ModuleKey>> results = new LinkedHashMap<>();
    for (InterimModule module : depGraph.values()) {
      for (DepSpec depSpec : module.getDeps().values()) {
        results.computeIfAbsent(
            depSpec,
            ds ->
                computePossibleResolutionResultsForOneDepSpec(
                    ds, selectionGroups, selectedVersions));
      }
    }
    return ImmutableMap.copyOf(results);
  }

  /**
   * Given the possible list of ModuleKeys each DepSpec can resolve to, enumerate through all the
   * possible resolution strategies. Each strategy assigns each DepSpec to a single ModuleKey out of
   * its possible list.
   */
  private static List<Function<DepSpec, ModuleKey>> enumerateStrategies(
      ImmutableMap<DepSpec, ImmutableList<ModuleKey>> possibleResolutionResults) {
    Map<DepSpec, Integer> depSpecToPosition = new HashMap<>();
    int position = 0;
    for (DepSpec depSpec : possibleResolutionResults.keySet()) {
      depSpecToPosition.put(depSpec, position++);
    }
    return Lists.transform(
        Lists.cartesianProduct(possibleResolutionResults.values().asList()),
        (List<ModuleKey> choices) ->
            (DepSpec depSpec) -> choices.get(depSpecToPosition.get(depSpec)));
    // TODO(wyv): There are some strategies that we could eliminate earlier. For example, the
    //   strategy where (foo@1.1, maxCL=3) resolves to foo@2.0 and (foo@1.2, maxCL=3) resolves to
    //   foo@3.0 is obviously not valid. All foo@? should resolve to the same version (assuming no
    //   multiple-version override).
  }

  /** Runs module selection (aka version resolution). */
  public static Result run(
      ImmutableMap<ModuleKey, InterimModule> depGraph,
      ImmutableMap<String, ModuleOverride> overrides)
      throws ExternalDepsException {
    // For any multiple-version overrides, build a mapping from (moduleName, compatibilityLevel) to
    // the set of allowed versions.
    ImmutableMap<ModuleNameAndCompatibilityLevel, ImmutableSortedSet<Version>> allowedVersionSets =
        computeAllowedVersionSets(overrides, depGraph);

    // For each module in the dep graph, pre-compute its selection group. For most modules this is
    // simply its (moduleName, compatibilityLevel) tuple; for modules with multiple-version
    // overrides, it additionally includes the targetAllowedVersion, which denotes the version to
    // "snap" to during selection.
    ImmutableMap<ModuleKey, SelectionGroup> selectionGroups =
        ImmutableMap.copyOf(
            Maps.transformValues(
                depGraph, module -> computeSelectionGroup(module, allowedVersionSets)));

    // Figure out the version to select for every selection group.
    Map<SelectionGroup, Version> selectedVersions = new HashMap<>();
    for (Map.Entry<ModuleKey, SelectionGroup> entry : selectionGroups.entrySet()) {
      ModuleKey key = entry.getKey();
      SelectionGroup selectionGroup = entry.getValue();
      selectedVersions.merge(selectionGroup, key.version(), Comparators::max);
    }

    // Compute the possible list of ModuleKeys that each DepSpec could resolve to.
    ImmutableMap<DepSpec, ImmutableList<ModuleKey>> possibleResolutionResults =
        computePossibleResolutionResults(depGraph, selectionGroups, selectedVersions);
    for (Map.Entry<DepSpec, ImmutableList<ModuleKey>> e : possibleResolutionResults.entrySet()) {
      if (e.getValue().isEmpty()) {
        throw ExternalDepsException.withMessage(
            Code.VERSION_RESOLUTION_ERROR,
            "Unexpected error: %s has no valid resolution result",
            e.getKey());
      }
    }

    // Each DepSpec may resolve to one or more ModuleKeys. We try out every single possible
    // combination; in other words, we enumerate through the cartesian product of the "possible
    // resolution result" set for every distinct DepSpec. Each element of this cartesian product is
    // essentially a mapping from DepSpecs to ModuleKeys; we can call this mapping a "resolution
    // strategy".
    //
    // Given a resolution strategy, we can walk through the graph from the root module, and see if
    // the strategy yields a valid graph (only containing the nodes reachable from the root). If the
    // graph is invalid (for example, because there are modules with different compatibility
    // levels), we try the next resolution strategy. When all strategies are exhausted, we know
    // there is no way to achieve a valid selection result, so we report the failure from the time
    // we attempted to walk the graph using the first resolution strategy.
    DepGraphWalker depGraphWalker = new DepGraphWalker(depGraph, overrides, selectionGroups);
    ExternalDepsException firstFailure = null;
    for (Function<DepSpec, ModuleKey> resolutionStrategy :
        enumerateStrategies(possibleResolutionResults)) {
      try {
        ImmutableMap<ModuleKey, InterimModule> prunedDepGraph =
            depGraphWalker.walk(resolutionStrategy);
        // If the call above didn't throw, we have a valid graph. Go ahead and produce a result!
        ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
            ImmutableMap.copyOf(
                Maps.transformValues(
                    depGraph,
                    module ->
                        module.withDepSpecsTransformed(
                            depSpec -> DepSpec.fromModuleKey(resolutionStrategy.apply(depSpec)))));
        return Result.create(prunedDepGraph, unprunedDepGraph);
      } catch (ExternalDepsException e) {
        if (firstFailure == null) {
          firstFailure = e;
        }
      }
    }
    // firstFailure cannot be null, since enumerateStrategies(...) cannot be empty, since no
    // element of possibleResolutionResults is empty.
    throw firstFailure;
  }

  /**
   * Walks the dependency graph from the root node, collecting any reachable nodes through deps into
   * a new dep graph and checking that nothing conflicts.
   */
  static class DepGraphWalker {
    private static final Joiner JOINER = Joiner.on(", ");
    private final ImmutableMap<ModuleKey, InterimModule> oldDepGraph;
    private final ImmutableMap<String, ModuleOverride> overrides;
    private final ImmutableMap<ModuleKey, SelectionGroup> selectionGroups;

    DepGraphWalker(
        ImmutableMap<ModuleKey, InterimModule> oldDepGraph,
        ImmutableMap<String, ModuleOverride> overrides,
        ImmutableMap<ModuleKey, SelectionGroup> selectionGroups) {
      this.oldDepGraph = oldDepGraph;
      this.overrides = overrides;
      this.selectionGroups = selectionGroups;
    }

    /**
     * Walks the old dep graph and builds a new dep graph containing only deps reachable from the
     * root module. The returned map has a guaranteed breadth-first iteration order.
     */
    ImmutableMap<ModuleKey, InterimModule> walk(Function<DepSpec, ModuleKey> resolutionStrategy)
        throws ExternalDepsException {
      HashMap<String, ExistingModule> moduleByName = new HashMap<>();
      ImmutableMap.Builder<ModuleKey, InterimModule> newDepGraph = ImmutableMap.builder();
      Set<ModuleKey> known = new HashSet<>();
      Queue<ModuleKeyAndDependent> toVisit = new ArrayDeque<>();
      toVisit.add(ModuleKeyAndDependent.create(ModuleKey.ROOT, null));
      known.add(ModuleKey.ROOT);
      while (!toVisit.isEmpty()) {
        ModuleKeyAndDependent moduleKeyAndDependent = toVisit.remove();
        ModuleKey key = moduleKeyAndDependent.moduleKey();
        InterimModule module =
            oldDepGraph
                .get(key)
                .withDepSpecsTransformed(
                    depSpec -> DepSpec.fromModuleKey(resolutionStrategy.apply(depSpec)));
        visit(key, module, moduleKeyAndDependent.dependent(), moduleByName);

        for (DepSpec depSpec : module.getDeps().values()) {
          if (known.add(depSpec.toModuleKey())) {
            toVisit.add(ModuleKeyAndDependent.create(depSpec.toModuleKey(), key));
          }
        }
        newDepGraph.put(key, module);
      }
      return newDepGraph.buildOrThrow();
    }

    void visit(
        ModuleKey key,
        InterimModule module,
        @Nullable ModuleKey from,
        HashMap<String, ExistingModule> moduleByName)
        throws ExternalDepsException {
      if (overrides.get(key.name()) instanceof MultipleVersionOverride override) {
        if (selectionGroups.get(key).targetAllowedVersion().isEmpty()) {
          // This module has no target allowed version, which means that there's no allowed version
          // higher than its version at the same compatibility level.
          Preconditions.checkState(
              from != null, "the root module cannot have a multiple version override");
          throw ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR,
              "%s depends on %s which is not allowed by the multiple_version_override on %s,"
                  + " which allows only [%s]",
              from,
              key,
              key.name(),
              JOINER.join(override.versions()));
        }
      } else {
        ExistingModule existingModuleWithSameName =
            moduleByName.put(
                module.getName(), ExistingModule.create(key, module.getCompatibilityLevel(), from));
        if (existingModuleWithSameName != null) {
          // This has to mean that a module with the same name but a different compatibility level
          // was also selected.
          Preconditions.checkState(
              from != null && existingModuleWithSameName.dependent() != null,
              "the root module cannot possibly exist more than once in the dep graph");
          throw ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR,
              "%s depends on %s with compatibility level %d, but %s depends on %s with"
                  + " compatibility level %d which is different",
              from,
              key,
              module.getCompatibilityLevel(),
              existingModuleWithSameName.dependent(),
              existingModuleWithSameName.moduleKey(),
              existingModuleWithSameName.compatibilityLevel());
        }
      }

      // Make sure that we don't have `module` depending on the same dependency version twice.
      HashMap<ModuleKey, String> depKeyToRepoName = new HashMap<>();
      for (Map.Entry<String, DepSpec> depEntry : module.getDeps().entrySet()) {
        String repoName = depEntry.getKey();
        DepSpec depSpec = depEntry.getValue();
        String previousRepoName = depKeyToRepoName.put(depSpec.toModuleKey(), repoName);
        if (previousRepoName != null) {
          throw ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR,
              "%s depends on %s at least twice (with repo names %s and %s). Consider adding a"
                  + " multiple_version_override if you want to depend on multiple versions of"
                  + " %s simultaneously",
              key,
              depSpec.toModuleKey(),
              repoName,
              previousRepoName,
              key.name());
        }
      }
    }

    record ModuleKeyAndDependent(ModuleKey moduleKey, @Nullable ModuleKey dependent) {
      ModuleKeyAndDependent {
        requireNonNull(moduleKey, "moduleKey");
      }

      static ModuleKeyAndDependent create(ModuleKey moduleKey, @Nullable ModuleKey dependent) {
        return new ModuleKeyAndDependent(moduleKey, dependent);
      }
    }

    record ExistingModule(
        ModuleKey moduleKey, int compatibilityLevel, @Nullable ModuleKey dependent) {
      ExistingModule {
        requireNonNull(moduleKey, "moduleKey");
      }

      static ExistingModule create(
          ModuleKey moduleKey, int compatibilityLevel, ModuleKey dependent) {
        return new ExistingModule(moduleKey, compatibilityLevel, dependent);
      }
    }
  }
}
