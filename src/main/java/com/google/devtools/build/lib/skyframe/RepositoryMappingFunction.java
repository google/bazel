// Copyright 2018 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.Module;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/** {@link SkyFunction} for {@link RepositoryMappingValue}s. */
public class RepositoryMappingFunction implements SkyFunction {
  public static final PrecomputedValue.Precomputed<Map<RepositoryName, PathFragment>>
      REPOSITORY_OVERRIDES = new PrecomputedValue.Precomputed<>("repository_overrides");
  private final RuleClassProvider ruleClassProvider;

  public RepositoryMappingFunction(RuleClassProvider ruleClassProvider) {
    this.ruleClassProvider = ruleClassProvider;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RepositoryMappingValue.Key key = (RepositoryMappingValue.Key) skyKey;
    RepositoryMappingValue repositoryMappingValue = computeInternal(key, env);
    if (repositoryMappingValue == null) {
      return null;
    }
    if (repositoryMappingValue == RepositoryMappingValue.NOT_FOUND_VALUE
        && REPOSITORY_OVERRIDES.get(env).containsKey(key.repoName())) {
      throw new RepositoryMappingFunctionException(
          String.format(
              "the repository %s does not exist, but has been specified as overridden with"
                  + " --override_repository. Use --inject_repository instead to add a new"
                  + " repository.",
              key.repoName()));
    }
    return repositoryMappingValue;
  }

  private RepositoryMappingValue computeInternal(RepositoryMappingValue.Key skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    RepositoryName repositoryName = skyKey.repoName();
    boolean enableBzlmod = starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD);
    boolean enableWorkspace = starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE);

    if (!enableBzlmod && !enableWorkspace) {
      throw new RepositoryMappingFunctionException(
          "Both --enable_bzlmod and --enable_workspace are disabled, but one of them must be"
              + " enabled to fetch external dependencies.");
    }

    if (enableBzlmod) {
      if (StarlarkBuiltinsValue.isBuiltinsRepo(repositoryName)) {
        // If tools repo is not set, repo mapping for @_builtins should be always fallback.
        if (ruleClassProvider.getToolsRepository() == null) {
          return RepositoryMappingValue.createForWorkspaceRepo(RepositoryMapping.ALWAYS_FALLBACK);
        }
        // Builtins .bzl files should use the repo mapping of @bazel_tools, to get access to repos
        // such as @platforms.
        RepositoryMappingValue bazelToolsMapping =
            (RepositoryMappingValue)
                env.getValue(
                    RepositoryMappingValue.Key.create(
                        ruleClassProvider.getToolsRepository(),
                        /* rootModuleShouldSeeWorkspaceRepos= */ false));
        if (bazelToolsMapping == null) {
          return null;
        }
        // We need to make sure that @_builtins maps to @_builtins too.
        return RepositoryMappingValue.createForBzlmodRepo(
            RepositoryMapping.create(
                    ImmutableMap.of(
                        StarlarkBuiltinsValue.BUILTINS_NAME,
                        StarlarkBuiltinsValue.BUILTINS_REPO,
                        // TODO(wyv): Google internal tests that have Bzlmod enabled fail because
                        //  they try to access cpp tools targets in the main repo from inside the
                        //  @_builtin repo. This is just a workaround and needs a proper way to
                        //  inject this mapping for google internal tests only.
                        "",
                        RepositoryName.MAIN),
                    StarlarkBuiltinsValue.BUILTINS_REPO)
                .withAdditionalMappings(bazelToolsMapping.repositoryMapping()),
            // The "associated module" doesn't exist here (@_builtins doesn't come from a module),
            // so we just supply dummy values.
            "",
            Version.EMPTY);
      }

      BazelDepGraphValue bazelDepGraphValue =
          (BazelDepGraphValue) env.getValue(BazelDepGraphValue.KEY);
      if (bazelDepGraphValue == null) {
        return null;
      }

      if (repositoryName.isMain()
          && skyKey.rootModuleShouldSeeWorkspaceRepos()
          && enableWorkspace) {
        // The root module should be able to see repos defined in WORKSPACE. Therefore, we find all
        // workspace repos and add them as extra visible repos in root module's repo mappings.
        PackageValue externalPackageValue =
            (PackageValue) env.getValue(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
        if (env.valuesMissing()) {
          return null;
        }
        ImmutableMap<String, RepositoryName> additionalMappings =
            externalPackageValue.getPackage().getTargets().entrySet().stream()
                // We need to filter out the non repository rule targets in the //external package.
                .filter(
                    entry ->
                        entry.getValue().getAssociatedRule() != null
                            && !entry.getValue().getAssociatedRule().getRuleClass().equals("bind"))
                .collect(
                    toImmutableMap(
                        Entry::getKey, entry -> RepositoryName.createUnvalidated(entry.getKey())));
        return computeForBazelModuleRepo(repositoryName, bazelDepGraphValue)
            .get()
            // For the transitional period, we need to map the workspace name to the main repo.
            .withAdditionalMappings(
                ImmutableMap.of(
                    externalPackageValue.getPackage().getWorkspaceName(), RepositoryName.MAIN))
            .withAdditionalMappings(additionalMappings)
            .withCachedInverseMap();
      }

      // Try and see if this is a repo generated from a Bazel module.
      Optional<RepositoryMappingValue> mappingValue =
          computeForBazelModuleRepo(repositoryName, bazelDepGraphValue);
      if (mappingValue.isPresent()) {
        return repositoryName.isMain()
            ? mappingValue.get().withCachedInverseMap()
            : mappingValue.get();
      }

      // Now try and see if this is a repo generated from a module extension.
      Optional<ModuleExtensionId> moduleExtensionId =
          maybeGetModuleExtensionForRepo(repositoryName, bazelDepGraphValue);

      if (moduleExtensionId.isPresent()) {
        var repoMappingEntriesValue =
            (ModuleExtensionRepoMappingEntriesValue)
                env.getValue(ModuleExtensionRepoMappingEntriesValue.key(moduleExtensionId.get()));
        if (repoMappingEntriesValue == null) {
          return null;
        }
        return RepositoryMappingValue.createForBzlmodRepo(
            RepositoryMapping.create(repoMappingEntriesValue.entries(), repositoryName),
            repoMappingEntriesValue.moduleKey().name(),
            repoMappingEntriesValue.moduleKey().version());
      }
    }

    if (enableWorkspace) {
      PackageValue externalPackageValue =
          (PackageValue) env.getValue(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
      RepositoryMappingValue rootModuleRepoMappingValue =
          enableBzlmod
              ? (RepositoryMappingValue)
                  env.getValue(RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS)
              : null;
      if (env.valuesMissing()) {
        return null;
      }

      RepositoryMapping rootModuleRepoMapping =
          rootModuleRepoMappingValue == null
              ? null
              : rootModuleRepoMappingValue.repositoryMapping();
      return computeFromWorkspace(repositoryName, externalPackageValue, rootModuleRepoMapping);
    }

    return RepositoryMappingValue.NOT_FOUND_VALUE;
  }

  /**
   * Calculates repo mappings for a repo generated from a Bazel module. Such a repo can see all its
   * {@code bazel_dep}s, as well as any repos generated by an extension it has a {@code use_repo}
   * clause for.
   *
   * @return the repo mappings for the repo if it's generated from a Bazel module, otherwise return
   *     Optional.empty().
   */
  private Optional<RepositoryMappingValue> computeForBazelModuleRepo(
      RepositoryName repositoryName, BazelDepGraphValue bazelDepGraphValue) {
    ModuleKey moduleKey = bazelDepGraphValue.getCanonicalRepoNameLookup().get(repositoryName);
    if (moduleKey == null) {
      return Optional.empty();
    }
    Module module = bazelDepGraphValue.getDepGraph().get(moduleKey);
    return Optional.of(
        RepositoryMappingValue.createForBzlmodRepo(
            bazelDepGraphValue.getFullRepoMapping(moduleKey),
            module.getName(),
            module.getVersion()));
  }

  /**
   * Calculate repo mappings for a repo generated from WORKSPACE. Such a repo is not subject to
   * strict deps, and can additionally see all repos that the root module can see.
   */
  private RepositoryMappingValue computeFromWorkspace(
      RepositoryName repositoryName,
      PackageValue externalPackageValue,
      @Nullable RepositoryMapping rootModuleRepoMapping)
      throws RepositoryMappingFunctionException {
    Package externalPackage = externalPackageValue.getPackage();
    if (externalPackage.containsErrors()) {
      throw new RepositoryMappingFunctionException("error evaluating WORKSPACE");
    }
    RepositoryMapping workspaceMapping =
        RepositoryMapping.createAllowingFallback(
            externalPackage.getExternalPackageRepositoryMapping(repositoryName));
    if (rootModuleRepoMapping == null) {
      // This means Bzlmod is disabled.
      return RepositoryMappingValue.createForWorkspaceRepo(workspaceMapping);
    }
    // If Bzlmod is in play, we need to make sure that WORKSPACE repos see all repos that the root
    // module can see, taking care to compose the existing WORKSPACE mapping with the main repo
    // mapping from Bzlmod.
    return RepositoryMappingValue.createForWorkspaceRepo(
        workspaceMapping.composeWith(rootModuleRepoMapping));
  }

  private static Optional<ModuleExtensionId> maybeGetModuleExtensionForRepo(
      RepositoryName repositoryName, BazelDepGraphValue bazelDepGraphValue) {
    return bazelDepGraphValue.getExtensionUniqueNames().entrySet().stream()
        .filter(e -> repositoryName.getName().startsWith(e.getValue() + "+"))
        .map(Entry::getKey)
        .findFirst();
  }

  private static class RepositoryMappingFunctionException extends SkyFunctionException {
    RepositoryMappingFunctionException(String message) {
      super(
          new BuildFileContainsErrorsException(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER, message),
          Transience.PERSISTENT);
    }
  }
}
