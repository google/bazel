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
//

package com.google.devtools.build.lib.rules.repository;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionRepoMappingEntriesFunction;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
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
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.AutoloadSymbols;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.repository.ExternalPackageHelper;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.SuccessfulRepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.AlreadyReportedRepositoryAccessException;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlCompileFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepositoryDelegatorFunction} */
@RunWith(JUnit4.class)
public class RepositoryDelegatorTest extends FoundationTestCase {
  private Path overrideDirectory;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private Path rootPath;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setupDelegator() throws Exception {
    rootPath = scratch.dir("/outputbase");
    scratch.file(
        rootPath.getRelative("MODULE.bazel").getPathString(),
        "module(name='test',version='0.1')",
        "bazel_dep(name='bazel_tools',version='1.0')");
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootPath, rootPath, rootPath),
            rootPath,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    RepositoryFunction localRepositoryFunction = new LocalRepositoryFunction();
    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, localRepositoryFunction);
    RepositoryDelegatorFunction delegatorFunction =
        new RepositoryDelegatorFunction(
            repositoryHandlers,
            new StarlarkRepositoryFunction(),
            /* isFetch= */ new AtomicBoolean(true),
            /* clientEnvironmentSupplier= */ ImmutableMap::of,
            directories,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER);
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                rootPath,
                ImmutableList.of(Root.fromPath(rootPath)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    differencer = new SequencedRecordingDifferencer();

    ConfiguredRuleClassProvider ruleClassProvider = AnalysisMock.get().createRuleClassProvider();

    PackageFactory pkgFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);

    registryFactory = new FakeRegistry.Factory();
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry(scratch.dir("modules").getPathString())
            .addModule(
                createModuleKey("bazel_tools", "1.0"),
                "module(name='bazel_tools', version='1.0');");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    HashFunction hashFunction = fileSystem.getDigestFunction().getHashFunction();
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(SkyFunctions.FILE, new FileFunction(pkgLocator, directories))
                .put(SkyFunctions.REPOSITORY_DIRECTORY, delegatorFunction)
                .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        ruleClassProvider,
                        pkgFactory,
                        directories,
                        /* bzlLoadFunctionForInlining= */ null))
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.BZL_COMPILE,
                    new BzlCompileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(), hashFunction))
                .put(
                    SkyFunctions.BZL_LOAD,
                    BzlLoadFunction.create(
                        ruleClassProvider,
                        directories,
                        hashFunction,
                        Caffeine.newBuilder().build()))
                .put(
                    SkyFunctions.STARLARK_BUILTINS,
                    new StarlarkBuiltinsFunction(ruleClassProvider.getBazelStarlarkEnvironment()))
                .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction())
                .put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
                .put(
                    SkyFunctions.REPOSITORY_MAPPING,
                    new RepositoryMappingFunction(ruleClassProvider))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        rootPath,
                        ImmutableMap.of()))
                .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
                .put(
                    SkyFunctions.REGISTRY,
                    new RegistryFunction(registryFactory, directories.getWorkspace()))
                .put(SkyFunctions.REPO_SPEC, new RepoSpecFunction())
                .put(SkyFunctions.YANKED_VERSIONS, new YankedVersionsFunction())
                .put(SkyFunctions.SINGLE_EXTENSION, new SingleExtensionFunction())
                .put(
                    SkyFunctions.SINGLE_EXTENSION_EVAL,
                    new SingleExtensionEvalFunction(directories, ImmutableMap::of))
                .put(SkyFunctions.SINGLE_EXTENSION_USAGES, new SingleExtensionUsagesFunction())
                .put(
                    SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                    new ModuleExtensionRepoMappingEntriesFunction())
                .put(
                    SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                    new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
                .build(),
            differencer);
    overrideDirectory = scratch.dir("/foo");
    scratch.file("/foo/REPO.bazel");
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.IS_VENDOR_COMMAND.set(differencer, false);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    StarlarkSemantics semantics = StarlarkSemantics.DEFAULT;
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, semantics);
    AutoloadSymbols.AUTOLOAD_SYMBOLS.set(
        differencer, new AutoloadSymbols(ruleClassProvider, semantics));
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);
  }

  @Test
  public void testOverride() throws Exception {
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(
        differencer,
        ImmutableMap.of(RepositoryName.createUnvalidated("foo"), overrideDirectory.asFragment()));

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    Path expectedPath = scratch.dir("/outputbase/external/foo");
    Path actualPath = repositoryDirectoryValue.getPath();
    assertThat(actualPath).isEqualTo(expectedPath);
    assertThat(actualPath.isSymbolicLink()).isTrue();
    assertThat(actualPath.readSymbolicLink()).isEqualTo(overrideDirectory.asFragment());
  }

  @Test
  public void testRepositoryDirtinessChecker() throws Exception {
    TimestampGranularityMonitor tsgm = new TimestampGranularityMonitor(new ManualClock());

    RepositoryDirectoryDirtinessChecker checker = new RepositoryDirectoryDirtinessChecker();
    RepositoryName repositoryName = RepositoryName.create("repo");
    RepositoryDirectoryValue.Key key = RepositoryDirectoryValue.key(repositoryName);

    SuccessfulRepositoryDirectoryValue usual =
        RepositoryDirectoryValue.builder()
            .setPath(rootDirectory.getRelative("a"))
            .setDigest(new byte[] {1})
            .build();

    assertThat(
            checker.check(key, usual, /* oldMtsv= */ null, SyscallCache.NO_CACHE, tsgm).isDirty())
        .isFalse();

    SuccessfulRepositoryDirectoryValue fetchDelayed =
        RepositoryDirectoryValue.builder()
            .setPath(rootDirectory.getRelative("b"))
            .setFetchingDelayed()
            .setDigest(new byte[] {1})
            .build();

    assertThat(
            checker
                .check(key, fetchDelayed, /* oldMtsv= */ null, SyscallCache.NO_CACHE, tsgm)
                .isDirty())
        .isTrue();
  }

  @Test
  public void testFetchRepositoryException_eventHandled() throws Exception {
    scratch.file(
        rootPath.getRelative("rule.bzl").getPathString(),
        "def _impl(ctx):",
        "    pass",
        "sample = rule(",
        "    implementation = _impl,",
        "    toolchains = ['//:toolchain_type'],",
        ")");
    scratch.file(
        rootPath.getRelative("BUILD").getPathString(),
        "load('rule.bzl', 'sample')",
        "toolchain_type(name = 'toolchain_type')",
        "sample(name = 'sample')");
    scratch.file(
        rootPath.getRelative("repo_rule.bzl").getPathString(),
        "def _impl(repo_ctx):",
        "# Error: no file written",
        "    pass",
        "broken_repo = repository_rule(implementation = _impl)");
    scratch.overwriteFile(
        rootPath.getRelative("MODULE.bazel").getPathString(),
        "broken_repo = use_repo_rule('//:repo_rule.bzl', 'broken_repo')",
        "broken_repo(name = 'broken')");

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key =
        RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("+_repo_rules+broken"));
    // Make it be evaluated every time, as we are testing evaluation.
    differencer.invalidate(ImmutableSet.of(key));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .isInstanceOf(AlreadyReportedRepositoryAccessException.class);
    assertThat(eventHandler.hasErrors()).isTrue();
    assertThat(eventHandler.getEvents()).hasSize(1);
  }

  @Test
  public void loadRepositoryNotDefined() throws Exception {
    // WORKSPACE is empty
    scratch.overwriteFile(rootPath.getRelative("MODULE.bazel").getPathString(), "");

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"));
    // Make it be evaluated every time, as we are testing evaluation.
    differencer.invalidate(ImmutableSet.of(key));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains("Repository '@@foo' is not defined");
  }

  @Test
  public void loadInvisibleRepository() throws Exception {
    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key =
        RepositoryDirectoryValue.key(
            RepositoryName.createUnvalidated("foo")
                .toNonVisible(RepositoryName.createUnvalidated("fake_owner_repo")));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains("No repository visible as '@foo' from repository '@@fake_owner_repo'");
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .doesNotContain(ExternalPackageHelper.WORKSPACE_DEPRECATION);
  }

  @Test
  public void loadInvisibleRepositoryFromMain() throws Exception {
    // Create the WORKSPACE file to trigger error message for WORKSPACE deprecation.
    scratch.file(rootPath.getRelative("WORKSPACE").getPathString());
    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key =
        RepositoryDirectoryValue.key(
            RepositoryName.createUnvalidated("foo").toNonVisible(RepositoryName.MAIN));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains("No repository visible as '@foo' from main repository");
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains(ExternalPackageHelper.WORKSPACE_DEPRECATION);
  }
}
