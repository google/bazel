// Copyright 2022 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsUtil.BZLMOD_ALLOWED_YANKED_VERSIONS_ENV;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.bazel.BazelVersion;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Discovers the whole dependency graph and runs selection algorithm on it to produce the pruned
 * dependency graph and runs checks on it.
 */
public class BazelModuleResolutionFunction implements SkyFunction {

  public static final Precomputed<CheckDirectDepsMode> CHECK_DIRECT_DEPENDENCIES =
      new Precomputed<>("check_direct_dependency");
  public static final Precomputed<BazelCompatibilityMode> BAZEL_COMPATIBILITY_MODE =
      new Precomputed<>("bazel_compatibility_mode");

  private record Result(
      Selection.Result selectionResult,
      ImmutableMap<String, Optional<Checksum>> registryFileHashes,
      ImmutableMap<ModuleKey, String> selectedYankedVersions) {}

  private static class ModuleResolutionComputeState implements Environment.SkyKeyComputeState {
    Result discoverAndSelectResult;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BazelModuleResolutionFunctionException, InterruptedException {
    ClientEnvironmentValue allowedYankedVersionsFromEnv =
        (ClientEnvironmentValue)
            env.getValue(ClientEnvironmentFunction.key(BZLMOD_ALLOWED_YANKED_VERSIONS_ENV));
    if (allowedYankedVersionsFromEnv == null) {
      return null;
    }

    Optional<ImmutableSet<ModuleKey>> allowedYankedVersions;
    try {
      allowedYankedVersions =
          YankedVersionsUtil.parseAllowedYankedVersions(
              allowedYankedVersionsFromEnv.getValue(),
              Objects.requireNonNull(YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.get(env)));
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }

    RootModuleFileValue root =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (root == null) {
      return null;
    }

    var state = env.getState(ModuleResolutionComputeState::new);
    if (state.discoverAndSelectResult == null) {
      state.discoverAndSelectResult = discoverAndSelect(env, root, allowedYankedVersions);
      if (state.discoverAndSelectResult == null) {
        return null;
      }
    }

    SequencedMap<String, Optional<Checksum>> registryFileHashes =
        new LinkedHashMap<>(state.discoverAndSelectResult.registryFileHashes);
    ImmutableSet<RepoSpecKey> repoSpecKeys =
        state.discoverAndSelectResult.selectionResult.resolvedDepGraph().values().stream()
            // Modules with a null registry have a non-registry override. We don't need to
            // fetch or store the repo spec in this case.
            .filter(module -> module.getRegistry() != null)
            .map(RepoSpecKey::of)
            .collect(toImmutableSet());
    SkyframeLookupResult repoSpecResults = env.getValuesAndExceptions(repoSpecKeys);
    ImmutableMap.Builder<ModuleKey, RepoSpec> remoteRepoSpecs = ImmutableMap.builder();
    for (RepoSpecKey repoSpecKey : repoSpecKeys) {
      RepoSpecValue repoSpecValue = (RepoSpecValue) repoSpecResults.get(repoSpecKey);
      if (repoSpecValue == null) {
        return null;
      }
      remoteRepoSpecs.put(repoSpecKey.moduleKey(), repoSpecValue.repoSpec());
      registryFileHashes.putAll(repoSpecValue.registryFileHashes());
    }

    ImmutableMap<ModuleKey, Module> finalDepGraph;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "compute final dep graph")) {
      finalDepGraph =
          computeFinalDepGraph(
              state.discoverAndSelectResult.selectionResult.resolvedDepGraph(),
              root.getOverrides(),
              remoteRepoSpecs.buildOrThrow());
    }

    return BazelModuleResolutionValue.create(
        finalDepGraph,
        state.discoverAndSelectResult.selectionResult.unprunedDepGraph(),
        ImmutableMap.copyOf(registryFileHashes),
        state.discoverAndSelectResult.selectedYankedVersions);
  }

  @Nullable
  private static Result discoverAndSelect(
      Environment env,
      RootModuleFileValue root,
      Optional<ImmutableSet<ModuleKey>> allowedYankedVersions)
      throws BazelModuleResolutionFunctionException, InterruptedException {
    Discovery.Result discoveryResult;
    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.BZLMOD, "discovery")) {
      discoveryResult = Discovery.run(env, root);
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }
    if (discoveryResult == null) {
      return null;
    }

    verifyAllOverridesAreOnExistentModules(discoveryResult.depGraph(), root.getOverrides());

    Selection.Result selectionResult;
    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.BZLMOD, "selection")) {
      selectionResult = Selection.run(discoveryResult.depGraph(), root.getOverrides());
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }
    ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph = selectionResult.resolvedDepGraph();

    ImmutableMap<ModuleKey, YankedVersionsValue> yankedVersionsValues;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "collect yanked versions")) {
      yankedVersionsValues = collectYankedVersionsValues(env, resolvedDepGraph.values());
    }
    if (yankedVersionsValues == null) {
      return null;
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "verify root module direct deps")) {
      verifyRootModuleDirectDepsAreAccurate(
          discoveryResult.depGraph().get(ModuleKey.ROOT),
          resolvedDepGraph.get(ModuleKey.ROOT),
          Objects.requireNonNull(CHECK_DIRECT_DEPENDENCIES.get(env)),
          env.getListener());
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "check bazel compatibility")) {
      checkBazelCompatibility(
          resolvedDepGraph.values(),
          Objects.requireNonNull(BAZEL_COMPATIBILITY_MODE.get(env)),
          env.getListener());
    }

    var selectedYankedVersions = checkNoYankedVersions(yankedVersionsValues, allowedYankedVersions);
    return new Result(
        selectionResult, discoveryResult.registryFileHashes(), selectedYankedVersions);
  }

  @Nullable
  private static ImmutableMap<ModuleKey, YankedVersionsValue> collectYankedVersionsValues(
      Environment env, ImmutableCollection<InterimModule> modules) throws InterruptedException {
    ImmutableMap.Builder<ModuleKey, YankedVersionsValue> yankedVersionsValues =
        ImmutableMap.builder();
    Map<ModuleKey, YankedVersionsValue.Key> yankedVersionsKeys = new HashMap<>();
    for (InterimModule m : modules) {
      if (m.getRegistry() == null) {
        // Modules with a non-registry override are never yanked.
        yankedVersionsValues.put(m.getKey(), YankedVersionsValue.NONE_YANKED);
        continue;
      }
      var lockfileYankedVersionsValue =
          m.getRegistry().tryGetYankedVersionsFromLockfile(m.getKey());
      if (lockfileYankedVersionsValue.isPresent()) {
        yankedVersionsValues.put(m.getKey(), lockfileYankedVersionsValue.get());
      } else {
        // We need to download the list of yanked versions from the registry.
        yankedVersionsKeys.put(
            m.getKey(), YankedVersionsValue.Key.create(m.getName(), m.getRegistry().getUrl()));
      }
    }
    SkyframeLookupResult yankedVersionsResult =
        env.getValuesAndExceptions(yankedVersionsKeys.values());
    if (env.valuesMissing()) {
      return null;
    }
    for (var entry : yankedVersionsKeys.entrySet()) {
      var yankedVersionsValue = (YankedVersionsValue) yankedVersionsResult.get(entry.getValue());
      if (yankedVersionsValue == null) {
        return null;
      }
      yankedVersionsValues.put(entry.getKey(), yankedVersionsValue);
    }
    return yankedVersionsValues.buildOrThrow();
  }

  private static void verifyAllOverridesAreOnExistentModules(
      ImmutableMap<ModuleKey, InterimModule> initialDepGraph,
      ImmutableMap<String, ModuleOverride> overrides)
      throws BazelModuleResolutionFunctionException {
    ImmutableSet<String> existentModules =
        initialDepGraph.values().stream().map(InterimModule::getName).collect(toImmutableSet());
    Set<String> nonexistentModules = Sets.difference(overrides.keySet(), existentModules);
    if (!nonexistentModules.isEmpty()) {
      throw new BazelModuleResolutionFunctionException(
          ExternalDepsException.withMessage(
              Code.BAD_MODULE,
              "the root module specifies overrides on nonexistent module(s): %s",
              Joiner.on(", ").join(nonexistentModules)),
          Transience.PERSISTENT);
    }
  }

  private static void verifyRootModuleDirectDepsAreAccurate(
      InterimModule discoveredRootModule,
      InterimModule resolvedRootModule,
      CheckDirectDepsMode mode,
      EventHandler eventHandler)
      throws BazelModuleResolutionFunctionException {
    if (mode == CheckDirectDepsMode.OFF) {
      return;
    }

    boolean failure = false;
    for (Map.Entry<String, DepSpec> dep : discoveredRootModule.getDeps().entrySet()) {
      ModuleKey resolved = resolvedRootModule.getDeps().get(dep.getKey()).toModuleKey();
      if (!dep.getValue().toModuleKey().equals(resolved)) {
        String message =
            String.format(
                "For repository '%s', the root module requires module version %s, but got %s in the"
                    + " resolved dependency graph. Please update the version in your MODULE.bazel"
                    + " or set --check_direct_dependencies=off",
                dep.getKey(), dep.getValue().toModuleKey(), resolved);
        if (mode == CheckDirectDepsMode.WARNING) {
          eventHandler.handle(Event.warn(message));
        } else {
          eventHandler.handle(Event.error(message));
          failure = true;
        }
      }
    }

    if (failure) {
      throw new BazelModuleResolutionFunctionException(
          ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR, "Direct dependency check failed."),
          Transience.PERSISTENT);
    }
  }

  public static void checkBazelCompatibility(
      ImmutableCollection<InterimModule> modules,
      BazelCompatibilityMode mode,
      EventHandler eventHandler)
      throws BazelModuleResolutionFunctionException {
    if (mode == BazelCompatibilityMode.OFF) {
      return;
    }

    String currentBazelVersion = BlazeVersionInfo.instance().getVersion();
    if (Strings.isNullOrEmpty(currentBazelVersion)) {
      return;
    }

    BazelVersion curVersion = BazelVersion.parse(currentBazelVersion);
    for (InterimModule module : modules) {
      for (String compatVersion : module.getBazelCompatibility()) {
        if (!curVersion.satisfiesCompatibility(compatVersion)) {
          String message =
              String.format(
                  "Bazel version %s is not compatible with module \"%s\" (bazel_compatibility: %s)",
                  curVersion.getOriginal(), module.getKey(), module.getBazelCompatibility());

          if (mode == BazelCompatibilityMode.WARNING) {
            eventHandler.handle(Event.warn(message));
          } else {
            eventHandler.handle(Event.error(message));
            throw new BazelModuleResolutionFunctionException(
                ExternalDepsException.withMessage(
                    Code.VERSION_RESOLUTION_ERROR, "Bazel compatibility check failed"),
                Transience.PERSISTENT);
          }
        }
      }
    }
  }

  /**
   * Fail if any selected module is yanked and not explicitly allowed.
   *
   * @return the yanked info for each yanked but explicitly allowed module
   */
  private static ImmutableMap<ModuleKey, String> checkNoYankedVersions(
      ImmutableMap<ModuleKey, YankedVersionsValue> yankedVersionValues,
      Optional<ImmutableSet<ModuleKey>> allowedYankedVersions)
      throws BazelModuleResolutionFunctionException {
    ImmutableMap.Builder<ModuleKey, String> selectedYankedVersions = ImmutableMap.builder();
    for (var entry : yankedVersionValues.entrySet()) {
      ModuleKey key = entry.getKey();
      YankedVersionsValue yankedVersionsValue = entry.getValue();
      if (yankedVersionsValue.yankedVersions().isEmpty()) {
        // No yanked version information available for this module.
        continue;
      }
      String yankedInfo = yankedVersionsValue.yankedVersions().get().get(key.version());
      if (yankedInfo == null) {
        // The selected version is not yanked.
        continue;
      }
      if (allowedYankedVersions.isEmpty() || allowedYankedVersions.get().contains(key)) {
        // The selected version is yanked but explicitly allowed.
        selectedYankedVersions.put(key, yankedInfo);
        continue;
      }
      throw new BazelModuleResolutionFunctionException(
          ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR,
              "Yanked version detected in your resolved dependency graph: %s, for the reason: "
                  + "%s.\nYanked versions may contain serious vulnerabilities and should not be "
                  + "used. To fix this, use a bazel_dep on a newer version of this module. To "
                  + "continue using this version, allow it using the --allow_yanked_versions "
                  + "flag or the BZLMOD_ALLOW_YANKED_VERSIONS env variable.",
              key,
              yankedInfo),
          Transience.PERSISTENT);
    }
    return selectedYankedVersions.buildOrThrow();
  }

  private static ImmutableMap<ModuleKey, Module> computeFinalDepGraph(
      ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph,
      ImmutableMap<String, ModuleOverride> overrides,
      ImmutableMap<ModuleKey, RepoSpec> remoteRepoSpecs) {
    ImmutableMap.Builder<ModuleKey, Module> finalDepGraph = ImmutableMap.builder();
    for (Map.Entry<ModuleKey, InterimModule> entry : resolvedDepGraph.entrySet()) {
      finalDepGraph.put(
          entry.getKey(),
          InterimModule.toModule(
              entry.getValue(),
              overrides.get(entry.getKey().name()),
              remoteRepoSpecs.get(entry.getKey())));
    }
    return finalDepGraph.buildOrThrow();
  }

  static class BazelModuleResolutionFunctionException extends SkyFunctionException {
    BazelModuleResolutionFunctionException(ExternalDepsException e, Transience transience) {
      super(e, transience);
    }
  }
}
