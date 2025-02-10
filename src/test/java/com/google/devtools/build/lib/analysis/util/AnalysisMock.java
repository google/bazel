// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesFunction;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.bazel.bzlmod.RegistryFunction;
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpecFunction;
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionEvalFunction;
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesFunction;
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsFunction;
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsUtil;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.packages.util.LoadingMock;
import com.google.devtools.build.lib.packages.util.MockCcSupport;
import com.google.devtools.build.lib.packages.util.MockPythonSupport;
import com.google.devtools.build.lib.packages.util.MockToolsConfig;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.packages.PackageFactoryBuilderWithSkyframeForTesting;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Create a mock client for the analysis phase, as well as a configuration factory. */
public abstract class AnalysisMock extends LoadingMock {

  public static AnalysisMock get() {
    try {
      Class<?> providerClass = Class.forName(TestConstants.TEST_ANALYSIS_MOCK);
      Field instanceField = providerClass.getField("INSTANCE");
      return (AnalysisMock) instanceField.get(null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static AnalysisMock getAnalysisMockWithoutBuiltinModules() {
    return new AnalysisMock.Delegate(AnalysisMock.get()) {
      @Override
      public ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
          BlazeDirectories directories) {
        return ImmutableMap.of();
      }
    };
  }

  @Override
  public String getProductName() {
    return TestConstants.PRODUCT_NAME;
  }

  public ImmutableList<String> getEmbeddedTools() {
    return TestConstants.EMBEDDED_TOOLS;
  }

  @Override
  public PackageFactoryBuilderWithSkyframeForTesting getPackageFactoryBuilderForTesting(
      BlazeDirectories directories) {
    return super.getPackageFactoryBuilderForTesting(directories)
        .setExtraSkyFunctions(getSkyFunctions(directories))
        .setExtraPrecomputeValues(getPrecomputedValues());
  }

  /**
   * This is called from test setup to create the mock directory layout needed to create the
   * configuration.
   */
  public void setupMockClient(MockToolsConfig mockToolsConfig) throws IOException {
    setupMockClientInternal(mockToolsConfig);
    setupMockTestingRules(mockToolsConfig);
  }

  public abstract void setupMockClientInternal(MockToolsConfig mockToolsConfig) throws IOException;

  public void setupMockTestingRules(MockToolsConfig mockToolsConfig) throws IOException {
    mockToolsConfig.create("test_defs/BUILD");
    mockToolsConfig.create(
        "test_defs/foo_library.bzl",
        """
        def _impl(ctx):
          pass
        foo_library = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
          },
        )
        """);
    mockToolsConfig.create(
        "test_defs/foo_binary.bzl",
        """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_binary = rule(
          implementation = _impl,
          executable = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        """);
    mockToolsConfig.create(
        "test_defs/foo_test.bzl",
        """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_test = rule(
          implementation = _impl,
          test = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        """);
  }

  /** Creates a mock tools repository. */
  public void setupMockToolsRepository(MockToolsConfig config) throws IOException {
    // Do nothing by default.
  }

  public abstract boolean isThisBazel();

  public abstract MockCcSupport ccSupport();

  public abstract AbstractMockJavaSupport javaSupport();

  public abstract MockPythonSupport pySupport();

  public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(BlazeDirectories directories) {
    return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
        .put(
            SkyFunctions.REPOSITORY_DIRECTORY,
            new RepositoryDelegatorFunction(
                ImmutableMap.of(),
                new StarlarkRepositoryFunction(),
                new AtomicBoolean(true),
                ImmutableMap::of,
                directories,
                BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
        .put(
            SkyFunctions.MODULE_FILE,
            new ModuleFileFunction(
                createRuleClassProvider().getBazelStarlarkEnvironment(),
                directories.getWorkspace(),
                getBuiltinModules(directories)))
        .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
        .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(directories.getWorkspace()))
        .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
        .put(SkyFunctions.SINGLE_EXTENSION, new SingleExtensionFunction())
        .put(
            SkyFunctions.SINGLE_EXTENSION_EVAL,
            new SingleExtensionEvalFunction(directories, ImmutableMap::of))
        .put(SkyFunctions.SINGLE_EXTENSION_USAGES, new SingleExtensionUsagesFunction())
        .put(
            SkyFunctions.REGISTRY,
            new RegistryFunction(FakeRegistry.DEFAULT_FACTORY, directories.getWorkspace()))
        .put(SkyFunctions.REPO_SPEC, new RepoSpecFunction())
        .put(SkyFunctions.YANKED_VERSIONS, new YankedVersionsFunction())
        .put(
            SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
            new ModuleExtensionRepoMappingEntriesFunction())
        .put(
            SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
            new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
        .buildOrThrow();
  }

  public ImmutableList<PrecomputedValue.Injected> getPrecomputedValues() {
    // PrecomputedValues required by SkyFunctions in getSkyFunctions()
    return ImmutableList.of(
        PrecomputedValue.injected(PrecomputedValue.REPO_ENV, ImmutableMap.of()),
        PrecomputedValue.injected(ModuleFileFunction.MODULE_OVERRIDES, ImmutableMap.of()),
        PrecomputedValue.injected(
            RepositoryMappingFunction.REPOSITORY_OVERRIDES, ImmutableMap.of()),
        PrecomputedValue.injected(
            RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty()),
        PrecomputedValue.injected(
            RepositoryDelegatorFunction.FORCE_FETCH,
            RepositoryDelegatorFunction.FORCE_FETCH_DISABLED),
        PrecomputedValue.injected(RepositoryDelegatorFunction.VENDOR_DIRECTORY, Optional.empty()),
        PrecomputedValue.injected(RepositoryDelegatorFunction.DISABLE_NATIVE_REPO_RULES, false),
        PrecomputedValue.injected(ModuleFileFunction.REGISTRIES, ImmutableSet.of()),
        PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
        PrecomputedValue.injected(ModuleFileFunction.INJECTED_REPOSITORIES, ImmutableMap.of()),
        PrecomputedValue.injected(YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, ImmutableList.of()),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, BazelCompatibilityMode.ERROR),
        PrecomputedValue.injected(BazelLockFileFunction.LOCKFILE_MODE, LockfileMode.UPDATE));
  }

  /** Returns the built-in modules. */
  public abstract ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
      BlazeDirectories directories);

  public abstract void setupPrelude(MockToolsConfig mockToolsConfig) throws IOException;

  public abstract BlazeModule getBazelRepositoryModule(BlazeDirectories directories);

  /**
   * Stub class for tests to extend in order to update a small amount of {@link AnalysisMock}
   * functionality.
   */
  public static class Delegate extends AnalysisMock {

    private final AnalysisMock delegate;

    public Delegate(AnalysisMock delegate) {
      this.delegate = delegate;
    }

    @Override
    public void setupMockClientInternal(MockToolsConfig mockToolsConfig) throws IOException {
      delegate.setupMockClientInternal(mockToolsConfig);
    }

    @Override
    public void setupMockToolsRepository(MockToolsConfig config) throws IOException {
      delegate.setupMockToolsRepository(config);
    }

    @Override
    public ConfiguredRuleClassProvider createRuleClassProvider() {
      return delegate.createRuleClassProvider();
    }

    @Override
    public boolean isThisBazel() {
      return delegate.isThisBazel();
    }

    @Override
    public MockCcSupport ccSupport() {
      return delegate.ccSupport();
    }

    @Override
    public AbstractMockJavaSupport javaSupport() {
      return delegate.javaSupport();
    }

    @Override
    public MockPythonSupport pySupport() {
      return delegate.pySupport();
    }

    @Override
    public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
        BlazeDirectories directories) {
      return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
          .putAll(
              Maps.filterKeys(
                  super.getSkyFunctions(directories),
                  fnName -> !fnName.equals(SkyFunctions.MODULE_FILE)))
          .put(
              SkyFunctions.MODULE_FILE,
              new ModuleFileFunction(
                  createRuleClassProvider().getBazelStarlarkEnvironment(),
                  directories.getWorkspace(),
                  getBuiltinModules(directories)))
          .buildOrThrow();
    }

    @Override
    public ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
        BlazeDirectories directories) {
      return delegate.getBuiltinModules(directories);
    }

    @Override
    public void setupPrelude(MockToolsConfig mockToolsConfig) throws IOException {
      delegate.setupPrelude(mockToolsConfig);
    }

    @Override
    public BlazeModule getBazelRepositoryModule(BlazeDirectories directories) {
      return delegate.getBazelRepositoryModule(directories);
    }
  }
}
