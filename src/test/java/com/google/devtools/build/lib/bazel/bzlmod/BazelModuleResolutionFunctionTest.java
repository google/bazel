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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
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
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BazelModuleResolutionFunction}. */
@RunWith(JUnit4.class)
public class BazelModuleResolutionFunctionTest extends FoundationTestCase {

  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
    differencer = new SequencedRecordingDifferencer();
    registryFactory = new FakeRegistry.Factory();
    evaluationContext =
        EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build();

    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFilePrefixForTesting()
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));

    ConfiguredRuleClassProvider ruleClassProvider = builder.build();
    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    StarlarkRepositoryFunction starlarkRepositoryFunction = new StarlarkRepositoryFunction();

    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(SkyFunctions.FILE, new FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        TestRuleClassProvider.getRuleClassProvider().getBazelStarlarkEnvironment(),
                        rootDirectory,
                        ImmutableMap.of()))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory, directories.getOutputBase()))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    SkyFunctions.REGISTRY,
                    new RegistryFunction(registryFactory, directories.getWorkspace()))
                .put(SkyFunctions.REPO_SPEC, new RepoSpecFunction())
                .put(SkyFunctions.YANKED_VERSIONS, new YankedVersionsFunction())
                .put(
                    SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                    new ModuleExtensionRepoMappingEntriesFunction())
                .put(
                    SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                    new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        starlarkRepositoryFunction,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
                .buildOrThrow(),
            differencer);

    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder().setBool(BuildLanguageOptions.ENABLE_BZLMOD, true).build());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.OFF);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());
  }

  @Test
  public void testBazelInvalidCompatibility() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0dd'])");

    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("invalid version argument '>5.1.0dd'");
  }

  @Test
  public void testSimpleBazelCompatibilityFailure() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");

    embedBazelVersion("5.1.4");
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testBazelCompatibilityWarning() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");

    embedBazelVersion("5.1.4");
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.WARNING);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isFalse();
    assertContainsEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testDisablingBazelCompatibility() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])");

    embedBazelVersion("5.1.4");
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.OFF);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isFalse();
    assertDoesNotContainEvent(
        "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>5.1.0, <5.1.4])");
  }

  @Test
  public void testBazelCompatibilitySuccess() throws Exception {
    setupModulesForCompatibility();

    embedBazelVersion("5.1.4-pre.20220421.3");
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testBazelCompatibilityFailure() throws Exception {
    setupModulesForCompatibility();

    embedBazelVersion("5.1.5rc444");
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 5.1.5rc444 is not compatible with module \"b@1.0\" (bazel_compatibility:"
            + " [<=5.1.4, -5.1.2])");
  }

  @Test
  public void testRcIsCompatibleWithReleaseRequirement() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])");

    embedBazelVersion("6.4.0rc1");
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testPrereleaseIsNotCompatibleWithReleaseRequirement() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])");

    embedBazelVersion("6.4.0-pre-1");
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Bazel version 6.4.0-pre-1 is not compatible with module \"<root>\" (bazel_compatibility:"
            + " [>=6.4.0])");
  }

  private void embedBazelVersion(String version) {
    // Double-get version-info to determine if it's the cached instance or not, and if not cache it.
    BlazeVersionInfo blazeInfo1 = BlazeVersionInfo.instance();
    BlazeVersionInfo blazeInfo2 = BlazeVersionInfo.instance();
    if (blazeInfo1 != blazeInfo2) {
      BlazeVersionInfo.setBuildInfo(ImmutableMap.of());
      blazeInfo1 = BlazeVersionInfo.instance();
    }

    // embed new version
    Map<String, String> blazeInfo = blazeInfo1.getBuildData();
    blazeInfo.remove(BlazeVersionInfo.BUILD_LABEL);
    blazeInfo.put(BlazeVersionInfo.BUILD_LABEL, version);
  }

  private void setupModulesForCompatibility() throws IOException {
    /* Root depends on "a" which depends on "b"
       The only versions that would work with root, a and b compatibility constrains are between
       -not including- 5.1.2 and 5.1.4.
       Ex: 5.1.3rc44, 5.1.3, 5.1.4-pre22.44
    */
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.6'])",
        "bazel_dep(name = 'a', version = '1.0')");

    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("a", "1.0"),
                "module(name='a', version='1.0', bazel_compatibility=['>=5.1.2', '-5.1.4']);",
                "bazel_dep(name='b', version='1.0')")
            .addModule(
                createModuleKey("b", "1.0"),
                "module(name='b', version='1.0', bazel_compatibility=['<=5.1.4', '-5.1.2']);");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
  }

  @Test
  public void testYankedVersionCheckSuccess() throws Exception {
    setupModulesForYankedVersion();
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains(
            "Yanked version detected in your resolved dependency graph: b@1.0, for the reason: 1.0"
                + " is a bad version!");
  }

  @Test
  public void testYankedVersionCheckIgnoredByAll() throws Exception {
    setupModulesForYankedVersion();
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of("all"));
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testYankedVersionCheckIgnoredBySpecific() throws Exception {
    setupModulesForYankedVersion();
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of("b@1.0"));
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testBadYankedVersionFormat() throws Exception {
    setupModulesForYankedVersion();
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of("b+1.0"));
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains(
            "Parsing command line flag --allow_yanked_versions=b+1.0 failed, module versions must"
                + " be of the form '<module name>@<version>'");
  }

  private void setupModulesForYankedVersion() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0')",
        "bazel_dep(name = 'a', version = '1.0')");

    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("a", "1.0"),
                "module(name='a', version='1.0');",
                "bazel_dep(name='b', version='1.0')")
            .addModule(createModuleKey("b", "1.0"), "module(name='b', version='1.0');")
            .addYankedVersion("b", ImmutableMap.of(Version.parse("1.0"), "1.0 is a bad version!"));
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
  }

  @Test
  public void overrideOnNonexistentModule() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0')",
        "bazel_dep(name = 'a', version = '1.0')",
        "bazel_dep(name = 'b', version = '1.1')",
        "local_path_override(module_name='d', path='whatevs')");

    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("a", "1.0"),
                "module(name='a', version='1.0')",
                "bazel_dep(name='b', version='1.0')")
            .addModule(createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
            .addModule(createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
            .addModule(
                createModuleKey("b", "1.0"),
                "module(name='b', version='1.0')",
                "bazel_dep(name='c', version='1.1')")
            .addModule(
                createModuleKey("b", "1.1"),
                "module(name='b', version='1.1')",
                "bazel_dep(name='c', version='1.0')");

    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    EvaluationResult<BazelModuleResolutionValue> result =
        evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains("the root module specifies overrides on nonexistent module(s): d");
  }

  @Test
  public void testPrintBehavior() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='mod', version='1.0')",
        "print('hello from root module')",
        "bazel_dep(name = 'a', version = '1.0')",
        "bazel_dep(name = 'b', version = '1.1')",
        "single_version_override(module_name = 'b', version = '1.1')",
        "local_path_override(module_name='a', path='a')");
    scratch.file(
        "a/MODULE.bazel",
        "module(name='a', version='1.0')",
        "print('hello from overridden a')",
        "bazel_dep(name='b', version='1.0')");

    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("a", "1.0"),
                "module(name='a', version='1.0')",
                "print('hello from a@1.0')",
                "bazel_dep(name='b', version='1.0')")
            .addModule(createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
            .addModule(createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
            .addModule(
                createModuleKey("b", "1.0"),
                "module(name='b', version='1.0', compatibility_level = 2)",
                "bazel_dep(name='c', version='1.1')",
                "print('hello from b@1.0')")
            .addModule(
                createModuleKey("b", "1.1"),
                "module(name='b', version='1.1', compatibility_level = 3)",
                "bazel_dep(name='c', version='1.0')",
                "print('hello from b@1.1')");

    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    evaluator.evaluate(ImmutableList.of(BazelModuleResolutionValue.KEY), evaluationContext);

    assertContainsEvent("hello from root module");
    assertContainsEvent("hello from overridden a");
    assertDoesNotContainEvent("hello from a@1.0");
    assertDoesNotContainEvent("hello from b@1.0");
    assertDoesNotContainEvent("hello from b@1.1");
  }
}
