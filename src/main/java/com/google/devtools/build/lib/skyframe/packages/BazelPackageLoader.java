// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.packages;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.BazelRepositoryModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.RegistryFactoryImpl;
import com.google.devtools.build.lib.bazel.bzlmod.RegistryFunction;
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpecFunction;
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsFunction;
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsUtil;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.cache.RepositoryCache;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.downloader.HttpDownloader;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.bazel.rules.BazelRulesModule;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.repository.ExternalPackageHelper;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.ActionEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.DirectoryListingFunction;
import com.google.devtools.build.lib.skyframe.DirectoryListingStateFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction.ActionOnIOExceptionReadingBuildFile;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concrete implementation of {@link PackageLoader} that uses skyframe under the covers, but with no
 * caching or incrementality.
 */
public class BazelPackageLoader extends AbstractPackageLoader {
  private static final ImmutableList<BuildFileName> BUILD_FILES_BY_PRIORITY =
      BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY;

  private static final ExternalPackageHelper EXTERNAL_PACKAGE_HELPER =
      BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER;

  /** Returns a fresh {@link Builder} instance. */
  public static Builder builder(Root workspaceDir, Path installBase, Path outputBase) {
    // Prevent PackageLoader from fetching any remote repositories; these should only be fetched by
    // Bazel before calling PackageLoader.
    AtomicBoolean isFetch = new AtomicBoolean(false);
    return new Builder(workspaceDir, installBase, outputBase, isFetch);
  }

  /** Builder for {@link BazelPackageLoader} instances. */
  public static class Builder extends AbstractPackageLoader.Builder {
    private static final ConfiguredRuleClassProvider DEFAULT_RULE_CLASS_PROVIDER =
        createRuleClassProvider();

    private final AtomicBoolean isFetch;

    private static ConfiguredRuleClassProvider createRuleClassProvider() {
      ConfiguredRuleClassProvider.Builder classProvider = new ConfiguredRuleClassProvider.Builder();
      new BazelRepositoryModule().initializeRuleClasses(classProvider);
      new BazelRulesModule().initializeRuleClasses(classProvider);
      return classProvider.build();
    }

    private Builder(Root workspaceDir, Path installBase, Path outputBase, AtomicBoolean isFetch) {
      super(
          workspaceDir,
          installBase,
          outputBase,
          BUILD_FILES_BY_PRIORITY,
          ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS);
      this.isFetch = isFetch;
      addExtraPrecomputedValues(
          PrecomputedValue.injected(PrecomputedValue.ACTION_ENV, ImmutableMap.of()),
          PrecomputedValue.injected(PrecomputedValue.REPO_ENV, ImmutableMap.of()),
          PrecomputedValue.injected(
              RepositoryMappingFunction.REPOSITORY_OVERRIDES,
              Suppliers.ofInstance(ImmutableMap.of())),
          PrecomputedValue.injected(
              RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty()),
          PrecomputedValue.injected(
              RepositoryDelegatorFunction.FORCE_FETCH,
              RepositoryDelegatorFunction.FORCE_FETCH_DISABLED),
          PrecomputedValue.injected(ModuleFileFunction.INJECTED_REPOSITORIES, ImmutableMap.of()),
          PrecomputedValue.injected(ModuleFileFunction.MODULE_OVERRIDES, ImmutableMap.of()),
          PrecomputedValue.injected(
              RepositoryDelegatorFunction.FORCE_FETCH_CONFIGURE,
              RepositoryDelegatorFunction.FORCE_FETCH_DISABLED),
          PrecomputedValue.injected(RepositoryDelegatorFunction.VENDOR_DIRECTORY, Optional.empty()),
          PrecomputedValue.injected(
              ModuleFileFunction.REGISTRIES, BazelRepositoryModule.DEFAULT_REGISTRIES),
          PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
          PrecomputedValue.injected(RepositoryDelegatorFunction.DISABLE_NATIVE_REPO_RULES, false),
          PrecomputedValue.injected(
              BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES,
              RepositoryOptions.CheckDirectDepsMode.OFF),
          PrecomputedValue.injected(
              BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
              RepositoryOptions.BazelCompatibilityMode.OFF),
          PrecomputedValue.injected(
              BazelLockFileFunction.LOCKFILE_MODE, RepositoryOptions.LockfileMode.OFF),
          PrecomputedValue.injected(
              YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, ImmutableList.of()));
    }

    @Override
    public BazelPackageLoader buildImpl() {
      // Set up SkyFunctions and PrecomputedValues needed to make local repositories work correctly.
      RepositoryCache repositoryCache = new RepositoryCache();
      HttpDownloader httpDownloader = new HttpDownloader();
      DownloadManager downloadManager =
          new DownloadManager(repositoryCache, httpDownloader, httpDownloader);
      RegistryFactoryImpl registryFactory =
          new RegistryFactoryImpl(Suppliers.ofInstance(ImmutableMap.of()));

      // Allow tests to override the following functions to use fake registry or custom built-in
      // modules
      if (!this.extraSkyFunctions.containsKey(SkyFunctions.MODULE_FILE)) {
        ModuleFileFunction moduleFileFunction =
            new ModuleFileFunction(
                ruleClassProvider.getBazelStarlarkEnvironment(),
                directories.getWorkspace(),
                ImmutableMap.copyOf(
                    Maps.filterKeys(
                        ModuleFileFunction.getBuiltinModules(), "bazel_tools"::equals)));

        addExtraSkyFunctions(ImmutableMap.of(SkyFunctions.MODULE_FILE, moduleFileFunction));
        moduleFileFunction.setDownloadManager(downloadManager);
      }
      if (!this.extraSkyFunctions.containsKey(SkyFunctions.REGISTRY)) {
        addExtraSkyFunctions(
            ImmutableMap.of(
                SkyFunctions.REGISTRY,
                new RegistryFunction(registryFactory, directories.getWorkspace())));
      }
      StarlarkRepositoryFunction starlarkRepositoryFunction = new StarlarkRepositoryFunction();
      starlarkRepositoryFunction.setDownloadManager(downloadManager);

      RepoSpecFunction repoSpecFunction = new RepoSpecFunction();
      repoSpecFunction.setDownloadManager(downloadManager);

      YankedVersionsFunction yankedVersionsFunction = new YankedVersionsFunction();
      yankedVersionsFunction.setDownloadManager(downloadManager);

      addExtraSkyFunctions(
          ImmutableMap.<SkyFunctionName, SkyFunction>builder()
              .put(
                  SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                  new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
              .put(
                  SkyFunctions.DIRECTORY_LISTING_STATE,
                  new DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE))
              .put(SkyFunctions.ACTION_ENVIRONMENT_VARIABLE, new ActionEnvironmentFunction())
              .put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction())
              .put(
                  SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                  new LocalRepositoryLookupFunction(EXTERNAL_PACKAGE_HELPER))
              .put(
                  SkyFunctions.REPOSITORY_DIRECTORY,
                  new RepositoryDelegatorFunction(
                      BazelRepositoryModule.repositoryRules(),
                      starlarkRepositoryFunction,
                      isFetch,
                      ImmutableMap::of,
                      directories,
                      EXTERNAL_PACKAGE_HELPER))
              .put(
                  SkyFunctions.BAZEL_LOCK_FILE,
                  new BazelLockFileFunction(directories.getWorkspace()))
              .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
              .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
              .put(SkyFunctions.REPO_SPEC, repoSpecFunction)
              .put(SkyFunctions.YANKED_VERSIONS, yankedVersionsFunction)
              .buildOrThrow());

      return new BazelPackageLoader(this);
    }

    @Override
    protected ConfiguredRuleClassProvider getDefaultRuleClassProvider() {
      return DEFAULT_RULE_CLASS_PROVIDER;
    }

    @CanIgnoreReturnValue
    public Builder setFetchForTesting() {
      this.isFetch.set(true);
      return this;
    }
  }

  private BazelPackageLoader(Builder builder) {
    super(builder);
  }

  @Override
  protected CrossRepositoryLabelViolationStrategy getCrossRepositoryLabelViolationStrategy() {
    return BazelSkyframeExecutorConstants.CROSS_REPOSITORY_LABEL_VIOLATION_STRATEGY;
  }

  @Override
  protected ImmutableList<BuildFileName> getBuildFilesByPriority() {
    return BUILD_FILES_BY_PRIORITY;
  }

  @Override
  protected ExternalPackageHelper getExternalPackageHelper() {
    return EXTERNAL_PACKAGE_HELPER;
  }

  @Override
  protected ActionOnIOExceptionReadingBuildFile getActionOnIOExceptionReadingBuildFile() {
    return BazelSkyframeExecutorConstants.ACTION_ON_IO_EXCEPTION_READING_BUILD_FILE;
  }

  @Override
  protected boolean shouldUseRepoDotBazel() {
    return BazelSkyframeExecutorConstants.USE_REPO_DOT_BAZEL;
  }
}
