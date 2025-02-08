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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
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
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.Location;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ModuleFileFunction}. */
@RunWith(JUnit4.class)
public class ModuleFileFunctionTest extends FoundationTestCase {

  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
    setUpWithBuiltinModules(ImmutableMap.of());
  }

  private void setUpWithBuiltinModules(ImmutableMap<String, NonRegistryOverride> builtinModules) {
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build();
    registryFactory = new FakeRegistry.Factory();
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
    ConfiguredRuleClassProvider ruleClassProvider = AnalysisMock.get().createRuleClassProvider();

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
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
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        rootDirectory,
                        builtinModules))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(
                    SkyFunctions.REPOSITORY_DIRECTORY,
                    new RepositoryDelegatorFunction(
                        repositoryHandlers,
                        null,
                        new AtomicBoolean(true),
                        ImmutableMap::of,
                        directories,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
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
                .buildOrThrow(),
            differencer);

    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    RepositoryMappingFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());

    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);
  }

  @Test
  public void testRootModule() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(",
        "    name='aaa',",
        "    version='0.1',",
        "    compatibility_level=4,",
        ")",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0',repo_name='see')",
        "register_toolchains('//my:toolchain', '//my:toolchain2')",
        "register_execution_platforms('//my:platform', '//my:platform2')",
        "single_version_override(module_name='ddd',version='18')",
        "local_path_override(module_name='eee',path='somewhere/else')",
        "multiple_version_override(module_name='fff',versions=['1.0','2.0'])",
        "archive_override(module_name='ggg',urls=['https://hello.com/world.zip'])");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    assertThat(rootModuleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("aaa", "0.1", 4)
                .setKey(ModuleKey.ROOT)
                .addExecutionPlatformsToRegister(
                    ImmutableList.of("//my:platform", "//my:platform2"))
                .addToolchainsToRegister(ImmutableList.of("//my:toolchain", "//my:toolchain2"))
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("see", createModuleKey("ccc", "2.0"))
                .build());
    assertThat(rootModuleFileValue.getOverrides())
        .containsExactly(
            "ddd",
            SingleVersionOverride.create(
                Version.parse("18"), "", ImmutableList.of(), ImmutableList.of(), 0),
            "eee",
            new NonRegistryOverride(LocalPathRepoSpecs.create("somewhere/else")),
            "fff",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""),
            "ggg",
            new NonRegistryOverride(
                new ArchiveRepoSpecBuilder()
                    .setUrls(ImmutableList.of("https://hello.com/world.zip"))
                    .build()));
    assertThat(rootModuleFileValue.getNonRegistryOverrideCanonicalRepoNameLookup())
        .containsExactly(
            RepositoryName.create("eee+"), "eee",
            RepositoryName.create("ggg+"), "ggg");
  }

  @Test
  public void testRootModule_noModuleFunctionIsOkay() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='bbb',version='1.0')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    assertThat(rootModuleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("", "")
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .build());
    assertThat(rootModuleFileValue.getOverrides()).isEmpty();
    assertThat(rootModuleFileValue.getNonRegistryOverrideCanonicalRepoNameLookup()).isEmpty();
  }

  @Test
  public void testRootModule_badSelfOverride() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "single_version_override(module_name='aaa',version='7')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("invalid override for the root module");
  }

  @Test
  public void testRootModule_overrideBuiltinModule() throws Exception {
    setUpWithBuiltinModules(
        ImmutableMap.of(
            "bazel_tools",
            new NonRegistryOverride(
                LocalPathRepoSpecs.create(
                    rootDirectory.getRelative("bazel_tools_original").getPathString()))));
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "local_path_override(module_name='bazel_tools',path='./bazel_tools_new')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    ModuleOverride bazelToolsOverride =
        result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE).getOverrides().get("bazel_tools");
    assertThat(bazelToolsOverride)
        .isEqualTo(new NonRegistryOverride(LocalPathRepoSpecs.create("./bazel_tools_new")));
  }

  @Test
  public void testRootModule_overridesIgnoredWithIgnoreDevDependency() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='aaa')",
        "single_version_override(module_name='ddd',version='18')",
        "local_path_override(module_name='eee',path='somewhere/else')",
        "multiple_version_override(module_name='fff',versions=['1.0','2.0'])",
        "archive_override(module_name='ggg',urls=['https://hello.com/world.zip'])");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, true);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE).getOverrides()).isEmpty();
  }

  @Test
  public void testRootModule_include_good() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('//java:java.MODULE.bazel')",
        "bazel_dep(name='foo', version='1.0')",
        "register_toolchains('//:whatever')",
        "include('//python:python.MODULE.bazel')");
    scratch.overwriteFile(rootDirectory.getRelative("java/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("java/java.MODULE.bazel").getPathString(),
        "bazel_dep(name='java-foo', version='1.0')");
    scratch.overwriteFile(rootDirectory.getRelative("python/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("python/python.MODULE.bazel").getPathString(),
        "bazel_dep(name='py-foo', version='1.0', repo_name='python-foo')",
        "single_version_override(module_name='java-foo', version='2.0')",
        "include('//python:toolchains/toolchains.MODULE.bazel')");
    scratch.overwriteFile(
        rootDirectory.getRelative("python/toolchains/toolchains.MODULE.bazel").getPathString(),
        "register_toolchains('//:python-whatever')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    InterimModule expectedModule =
        InterimModuleBuilder.create("aaa", "")
            .setKey(ModuleKey.ROOT)
            .addDep("java-foo", createModuleKey("java-foo", "1.0"))
            .addDep("foo", createModuleKey("foo", "1.0"))
            .addDep("python-foo", createModuleKey("py-foo", "1.0"))
            .addToolchainsToRegister(ImmutableList.of("//:whatever", "//:python-whatever"))
            .build();
    assertThat(rootModuleFileValue.getModule()).isEqualTo(expectedModule);
    // specifically assert the order of deps, which is significant; Map.equals semantics don't test
    // this.
    assertThat(rootModuleFileValue.getModule().getDeps())
        .containsExactlyEntriesIn(expectedModule.getDeps())
        .inOrder();
    assertThat(rootModuleFileValue.getOverrides())
        .containsExactly(
            "java-foo",
            SingleVersionOverride.create(
                Version.parse("2.0"), "", ImmutableList.of(), ImmutableList.of(), 0));
  }

  @Test
  public void testRootModule_include_bad_otherRepoLabel() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('@haha//java:java.MODULE.bazel')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("starting with double slashes");
  }

  @Test
  public void testRootModule_include_bad_relativeLabel() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include(':relative.MODULE.bazel')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("starting with double slashes");
  }

  @Test
  public void testRootModule_include_bad_notEndingInModuleBazel() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('//:MODULE.bazel.segment')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("have a name ending in '.MODULE.bazel'");
  }

  @Test
  public void testRootModule_include_bad_startsWithPeriod() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('//pkg:dir/.MODULE.bazel')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains("the name of the file to be included must not start with '.'");
  }

  @Test
  public void testRootModule_include_bad_badLabelSyntax() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('//haha/:::.MODULE.bazel')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("bad include label");
  }

  @Test
  public void testRootModule_include_bad_moduleAfterInclude() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "include('//java:java.MODULE.bazel')");
    scratch.overwriteFile(rootDirectory.getRelative("java/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("java/java.MODULE.bazel").getPathString(),
        "module(name='bet-you-didnt-expect-this-didya')",
        "bazel_dep(name='java-foo', version='1.0', repo_name='foo')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent("if module() is called, it must be called before any other functions");
  }

  @Test
  public void testRootModule_include_bad_repoNameCollision() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "include('//java:java.MODULE.bazel')",
        "include('//python:python.MODULE.bazel')");
    scratch.overwriteFile(rootDirectory.getRelative("java/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("java/java.MODULE.bazel").getPathString(),
        "bazel_dep(name='java-foo', version='1.0', repo_name='foo')");
    scratch.overwriteFile(rootDirectory.getRelative("python/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("python/python.MODULE.bazel").getPathString(),
        "bazel_dep(name='python-foo', version='1.0', repo_name='foo')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent("The repo name 'foo' is already being used");
  }

  @Test
  public void testRootModule_include_bad_tryingToLeakBindings() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "FOO_NAME = 'foo'",
        "include('//java:java.MODULE.bazel')");
    scratch.overwriteFile(rootDirectory.getRelative("java/BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("java/java.MODULE.bazel").getPathString(),
        "bazel_dep(name=FOO_NAME, version='1.0')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent("name 'FOO_NAME' is not defined");
  }

  @Test
  public void forgotVersion() throws Exception {
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("bbb", ""), null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains("bad bazel_dep on module 'bbb' with no version");
  }

  @Test
  public void testRegistriesCascade() throws Exception {
    // Registry1 has no module B@1.0; registry2 and registry3 both have it. We should be using the
    // B@1.0 from registry2.
    FakeRegistry registry1 = registryFactory.newFakeRegistry("/foo");
    FakeRegistry registry2 =
        registryFactory
            .newFakeRegistry("/bar")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0');bazel_dep(name='ccc',version='2.0')");
    FakeRegistry registry3 =
        registryFactory
            .newFakeRegistry("/baz")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0');bazel_dep(name='ddd',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(
        differencer, ImmutableSet.of(registry1.getUrl(), registry2.getUrl(), registry3.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("bbb", "1.0"), null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .setRegistry(registry2)
                .build());
  }

  @Test
  public void testNonRootModuleCannotUseInclude() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("foo", "1.0"),
                "module(name='foo',version='1.0')",
                "include('//java:MODULE.bazel.segment')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.key(createModuleKey("foo", "1.0"), null)),
            evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString()).contains("but it can only be used in the root module");
  }

  @Ignore(
      "b/389163906 - figure out how to convert this class to BuildViewTestCase; the presence of the"
          + " many builtin modules in the default AnalysisMock makes it very hard")
  @Test
  public void testLocalPathOverride() throws Exception {
    // There is an override for B to use the local path "code_for_b", so we shouldn't even be
    // looking at the registry.
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "local_path_override(module_name='bbb',path='code_for_b')");
    scratch.overwriteFile(
        rootDirectory.getRelative("code_for_b/MODULE.bazel").getPathString(),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0');bazel_dep(name='ccc',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    // The version is empty here due to the override.
    SkyKey skyKey =
        ModuleFileValue.key(
            createModuleKey("bbb", ""),
            new NonRegistryOverride(LocalPathRepoSpecs.create("code_for_b")));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("bbb", "1.0")
                .setKey(createModuleKey("bbb", ""))
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .build());
  }

  @Ignore(
      "b/389163906 - figure out how to convert this class to BuildViewTestCase; the presence of the"
          + " many builtin modules in the default AnalysisMock makes it very hard")
  @Test
  public void testCommandLineModuleOverrides() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name = \"bbb\", version = \"1.0\")",
        "local_path_override(module_name='bbb', path='ignored_override')");

    // Command line override has the priority. Thus, "used_override" with dependency on 'ccc'
    // should be selected.
    scratch.overwriteFile(
        rootDirectory.getRelative("ignored_override/MODULE.bazel").getPathString(),
        "module(name='bbb',version='1.0')");
    scratch.overwriteFile(
        rootDirectory.getRelative("used_override/MODULE.bazel").getPathString(),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')");

    // ModuleFileFuncion.MODULE_OVERRIDES should be filled from command line options
    // Inject for testing
    Map<String, ModuleOverride> moduleOverride =
        new LinkedHashMap<>(
            ImmutableMap.of(
                "bbb", new NonRegistryOverride(LocalPathRepoSpecs.create("used_override"))));
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.copyOf(moduleOverride));

    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0');bazel_dep(name='ccc',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    // The version is empty here due to the override.
    SkyKey skyKey =
        ModuleFileValue.key(
            createModuleKey("bbb", ""),
            new NonRegistryOverride(LocalPathRepoSpecs.create("used_override")));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("bbb", "1.0")
                .setKey(createModuleKey("bbb", ""))
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .build());
  }

  @Test
  public void testRegistryOverride() throws Exception {
    FakeRegistry registry1 =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0',compatibility_level=4)",
                "bazel_dep(name='ccc',version='2.0')");
    FakeRegistry registry2 =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0',compatibility_level=6)",
                "bazel_dep(name='ccc',version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry1.getUrl()));

    // Override the registry for B to be registry2 (instead of the default registry1).
    SkyKey skyKey =
        ModuleFileValue.key(
            createModuleKey("bbb", "1.0"),
            SingleVersionOverride.create(
                Version.EMPTY, registry2.getUrl(), ImmutableList.of(), ImmutableList.of(), 0));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("bbb", "1.0", 6)
                .addDep("ccc", createModuleKey("ccc", "3.0"))
                .setRegistry(registry2)
                .build());
  }

  @Test
  public void testModuleExtensions_good() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext1 = use_extension('//:defs.bzl','myext1')",
                "use_repo(myext1, 'repo1')",
                "myext1.tag(key='val')",
                "myext2 = use_extension('//:defs.bzl','myext2')",
                "use_repo(myext2, 'repo2', other_repo1='repo1')",
                "myext2.tag1(key1='val1')",
                "myext2.tag2(key2='val2')",
                "bazel_dep(name='rules_jvm_external',version='2.0')",
                "maven = use_extension('@rules_jvm_external//:defs.bzl','maven')",
                "use_repo(maven, mvn='maven')",
                "maven.dep(coord='junit')",
                "use_repo(maven, 'junit', 'guava')",
                "maven.dep(coord='guava')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    ModuleKey myMod = createModuleKey("mymod", "1.0");
    SkyKey skyKey = ModuleFileValue.key(myMod, null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("mymod", "1.0")
                .addDep("rules_jvm_external", createModuleKey("rules_jvm_external", "2.0"))
                .setRegistry(registry)
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@mymod//:defs.bzl")
                        .setExtensionName("myext1")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 2, 23))
                                .setDevDependency(false)
                                .setProxyName("myext1")
                                .setImports(ImmutableBiMap.of("repo1", "repo1"))
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("key", "val")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 4, 11))
                                .build())
                        .build())
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@mymod//:defs.bzl")
                        .setExtensionName("myext2")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 5, 23))
                                .setDevDependency(false)
                                .setProxyName("myext2")
                                .setImports(
                                    ImmutableBiMap.of("other_repo1", "repo1", "repo2", "repo2"))
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag1")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("key1", "val1")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 7, 12))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag2")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("key2", "val2")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 8, 12))
                                .build())
                        .build())
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@rules_jvm_external//:defs.bzl")
                        .setExtensionName("maven")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 10, 22))
                                .setDevDependency(false)
                                .setProxyName("maven")
                                .setImports(
                                    ImmutableBiMap.of(
                                        "mvn", "maven", "junit", "junit", "guava", "guava"))
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("dep")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("coord", "junit")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 12, 10))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("dep")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("coord", "guava")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 14, 10))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void testModuleExtensions_duplicateProxy_asRoot() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "myext1 = use_extension('//:defs.bzl','myext',dev_dependency=True)",
        "myext1.tag(name = 'tag1')",
        "use_repo(myext1, 'alpha')",
        "myext2 = use_extension('//:defs.bzl','myext')",
        "myext2.tag(name = 'tag2')",
        "use_repo(myext2, 'beta')",
        "myext3 = use_extension('//:defs.bzl','myext',dev_dependency=True)",
        "myext3.tag(name = 'tag3')",
        "use_repo(myext3, 'gamma')",
        "myext4 = use_extension('//:defs.bzl','myext')",
        "myext4.tag(name = 'tag4')",
        "use_repo(myext4, 'delta')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of());

    SkyKey skyKey = ModuleFileValue.KEY_FOR_ROOT_MODULE;
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("", "")
                .setKey(ModuleKey.ROOT)
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@//:defs.bzl")
                        .setExtensionName("myext")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 1, 23))
                                .setDevDependency(true)
                                .setProxyName("myext1")
                                .addImport("alpha", "alpha")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 4, 23))
                                .setDevDependency(false)
                                .setProxyName("myext2")
                                .addImport("beta", "beta")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 7, 23))
                                .setDevDependency(true)
                                .setProxyName("myext3")
                                .addImport("gamma", "gamma")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 10, 23))
                                .setDevDependency(false)
                                .setProxyName("myext4")
                                .addImport("delta", "delta")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag1")
                                            .buildImmutable()))
                                .setDevDependency(true)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 2, 11))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag2")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 5, 11))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag3")
                                            .buildImmutable()))
                                .setDevDependency(true)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 8, 11))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag4")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 11, 11))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void testModuleExtensions_duplicateProxy_asDep() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext1 = use_extension('//:defs.bzl','myext',dev_dependency=True)",
                "myext1.tag(name = 'tag1')",
                "use_repo(myext1, 'alpha')",
                "myext2 = use_extension('//:defs.bzl','myext')",
                "myext2.tag(name = 'tag2')",
                "use_repo(myext2, 'beta')",
                "myext3 = use_extension('//:defs.bzl','myext',dev_dependency=True)",
                "myext3.tag(name = 'tag3')",
                "use_repo(myext3, 'gamma')",
                "myext4 = use_extension('//:defs.bzl','myext')",
                "myext4.tag(name = 'tag4')",
                "use_repo(myext4, 'delta')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    ModuleKey myMod = createModuleKey("mymod", "1.0");
    SkyKey skyKey = ModuleFileValue.key(myMod, null);
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("mymod", "1.0")
                .setRegistry(registry)
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("@mymod//:defs.bzl")
                        .setExtensionName("myext")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 5, 23))
                                .setDevDependency(false)
                                .setProxyName("myext2")
                                .addImport("beta", "beta")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 11, 23))
                                .setDevDependency(false)
                                .setProxyName("myext4")
                                .addImport("delta", "delta")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag2")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 6, 11))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("tag")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "tag4")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn(
                                        "fake:0/modules/mymod/1.0/MODULE.bazel", 12, 11))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void testModuleExtensions_repoNameCollision_localRepoName() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext = use_extension('//:defs.bzl','myext')",
                "use_repo(myext, mymod='some_repo')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);

    assertContainsEvent(
        "The repo name 'mymod' is already being used as the current module name at");
  }

  @Test
  public void testModuleExtensions_repoNameCollision_exportedRepoName() throws Exception {
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/foo")
            .addModule(
                createModuleKey("mymod", "1.0"),
                "module(name='mymod',version='1.0')",
                "myext = use_extension('//:defs.bzl','myext')",
                "use_repo(myext, 'some_repo', again='some_repo')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    SkyKey skyKey = ModuleFileValue.key(createModuleKey("mymod", "1.0"), null);
    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);

    assertContainsEvent(
        "The repo exported as 'some_repo' by module extension 'myext' is already imported at");
  }

  @Test
  public void testModuleExtensions_innate() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "repo = use_repo_rule('//:repo.bzl','repo')",
        "repo(name='repo_name', value='something')",
        "http_archive = use_repo_rule('@bazel_tools//:http.bzl','http_archive')",
        "http_archive(name='guava',url='guava.com')",
        "http_archive(name='vuaga',url='vuaga.com',dev_dependency=True)");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of());

    SkyKey skyKey = ModuleFileValue.KEY_FOR_ROOT_MODULE;
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("", "")
                .setKey(ModuleKey.ROOT)
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//:MODULE.bazel")
                        .setExtensionName("//:repo.bzl repo")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 2, 5))
                                .setDevDependency(false)
                                .addImport("repo_name", "repo_name")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("repo")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "repo_name")
                                            .put("value", "something")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 2, 5))
                                .build())
                        .build())
                .addExtensionUsage(
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//:MODULE.bazel")
                        .setExtensionName("@bazel_tools//:http.bzl http_archive")
                        .setIsolationKey(Optional.empty())
                        .setRepoOverrides(ImmutableMap.of())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 4, 13))
                                .setDevDependency(false)
                                .addImport("guava", "guava")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 5, 13))
                                .setDevDependency(true)
                                .addImport("vuaga", "vuaga")
                                .setContainingModuleFilePath(
                                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("repo")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "guava")
                                            .put("url", "guava.com")
                                            .buildImmutable()))
                                .setDevDependency(false)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 4, 13))
                                .build())
                        .addTag(
                            Tag.builder()
                                .setTagName("repo")
                                .setAttributeValues(
                                    AttributeValues.create(
                                        Dict.<String, Object>builder()
                                            .put("name", "vuaga")
                                            .put("url", "vuaga.com")
                                            .buildImmutable()))
                                .setDevDependency(true)
                                .setLocation(
                                    Location.fromFileLineColumn("/workspace/MODULE.bazel", 5, 13))
                                .build())
                        .build())
                .build());
  }

  @Test
  public void testModuleFileExecute_syntaxError() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1',compatibility_level=4)",
        "foo()");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertContainsEvent("name 'foo' is not defined");
  }

  @Test
  public void testModuleFileExecute_evalError() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1',compatibility_level=\"4\")");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("parameter 'compatibility_level' got value of type 'string', want 'int'");
  }

  @Test
  public void validateModuleName() throws Exception {
    ModuleFileGlobals.validateModuleName("abc");
    ModuleFileGlobals.validateModuleName("a3");
    ModuleFileGlobals.validateModuleName("a.e");
    ModuleFileGlobals.validateModuleName("a.-_e");
    ModuleFileGlobals.validateModuleName("a");

    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName(""));
    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName("fooBar"));
    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName("_foo"));
    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName("foo#bar"));
    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName("foo~bar"));
    assertThrows(EvalException.class, () -> ModuleFileGlobals.validateModuleName("foo+bar"));
  }

  @Test
  public void badModuleName_module() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='f.',version='0.1')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid module name 'f.'");
  }

  @Test
  public void badModuleName_bazelDep() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='f.',version='0.1')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid module name 'f.'");
  }

  @Test
  public void badRepoName_module() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='foo',version='0.1',repo_name='_foo')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid user-provided repo name '_foo'");
  }

  @Test
  public void badRepoName_bazelDep() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='0.1',repo_name='_foo')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid user-provided repo name '_foo'");
  }

  @Test
  public void badRepoName_useRepo() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "ext=use_extension('//:hello.bzl', 'ext')",
        "use_repo(ext, foo='_foo')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid user-provided repo name '_foo'");
  }

  @Test
  public void badRepoName_useRepo_assignedName() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "ext=use_extension('//:hello.bzl', 'ext')",
        "use_repo(ext, _foo='foo')");

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("invalid user-provided repo name '_foo'");
  }

  @Test
  public void testBuiltinModules_forRoot() throws Exception {
    ImmutableMap<String, NonRegistryOverride> builtinModules =
        ImmutableMap.of(
            "bazel_tools",
            new NonRegistryOverride(LocalPathRepoSpecs.create("/tools")),
            "local_config_platform",
            new NonRegistryOverride(LocalPathRepoSpecs.create("/local_config_platform")));
    setUpWithBuiltinModules(builtinModules);
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of());

    SkyKey skyKey = ModuleFileValue.KEY_FOR_ROOT_MODULE;
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    RootModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("", "")
                .addDep("bazel_tools", createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .addDep("foo", createModuleKey("foo", "1.0"))
                .build());
    assertThat(moduleFileValue.getOverrides()).containsExactlyEntriesIn(builtinModules);
  }

  @Ignore(
      "b/389163906 - figure out how to convert this class to BuildViewTestCase; the presence of the"
          + " many builtin modules in the default AnalysisMock makes it very hard")
  @Test
  public void testBuiltinModules_forBuiltinModules() throws Exception {
    ImmutableMap<String, NonRegistryOverride> builtinModules =
        ImmutableMap.of(
            "bazel_tools",
            new NonRegistryOverride(
                LocalPathRepoSpecs.create(rootDirectory.getRelative("tools").getPathString())),
            "local_config_platform",
            new NonRegistryOverride(LocalPathRepoSpecs.create("/local_config_platform")));
    setUpWithBuiltinModules(builtinModules);
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')");
    scratch.overwriteFile(
        rootDirectory.getRelative("tools/MODULE.bazel").getPathString(),
        "module(name='bazel_tools',version='1.0')",
        "bazel_dep(name='foo',version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of());

    SkyKey skyKey =
        ModuleFileValue.key(createModuleKey("bazel_tools", ""), builtinModules.get("bazel_tools"));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ModuleFileValue moduleFileValue = result.get(skyKey);
    assertThat(moduleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("bazel_tools", "1.0")
                .setKey(createModuleKey("bazel_tools", ""))
                .addDep("local_config_platform", createModuleKey("local_config_platform", ""))
                .addDep("foo", createModuleKey("foo", "2.0"))
                .build());
  }

  @Test
  public void moduleRepoName() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1',repo_name='bbb')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RootModuleFileValue rootModuleFileValue = result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    assertThat(rootModuleFileValue.getModule())
        .isEqualTo(
            InterimModuleBuilder.create("aaa", "0.1")
                .setKey(ModuleKey.ROOT)
                .setRepoName("bbb")
                .build());
  }

  @Test
  public void moduleRepoName_conflict() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1',repo_name='bbb')",
        "bazel_dep(name='bbb',version='1.0')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("The repo name 'bbb' is already being used as the module's own repo name");
  }

  @Test
  public void module_calledTwice() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1',repo_name='bbb')",
        "module(name='aaa',version='0.1',repo_name='bbb')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("the module() directive can only be called once");
  }

  @Test
  public void module_calledLate() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "use_extension('//:extensions.bzl', 'my_ext')",
        "module(name='aaa',version='0.1',repo_name='bbb')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent("if module() is called, it must be called before any other functions");
  }

  @Test
  public void restrictedSyntax() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "if 3+5>7: module(name='aaa',version='0.1',repo_name='bbb')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);

    assertContainsEvent(
        "`if` statements are not allowed in MODULE.bazel files. You may use an `if` expression for"
            + " simple cases");
  }

  @Test
  public void isolatedUsageWithoutImports() throws Exception {
    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, true)
            .build());

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "isolated_ext = use_extension('//:extensions.bzl', 'my_ext', isolate = True)");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().toString())
        .contains(
            "the isolated usage at /workspace/MODULE.bazel:1:29 of extension my_ext defined in "
                + "@//:extensions.bzl has no effect as no repositories are imported from it. "
                + "Either import one or more repositories generated by the extension with "
                + "use_repo or remove the usage.");
  }

  @Test
  public void isolatedUsageNotExported() throws Exception {
    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, true)
            .build());

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "use_extension('//:extensions.bzl', 'my_ext', isolate = True)");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertContainsEvent(
        "Isolated extension usage at /workspace/MODULE.bazel:1:14 must be assigned to a "
            + "top-level variable");
  }

  @Test
  public void isolatedUsage_notEnabled() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "use_extension('//:extensions.bzl', 'my_ext', isolate = True)");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    reporter.removeHandler(failFastHandler); // expect failures
    evaluator.evaluate(ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertContainsEvent(
        "Error in use_extension: in call to use_extension(), parameter 'isolate' is experimental "
            + "and thus unavailable with the current flags. It may be enabled by setting "
            + "--experimental_isolated_extension_usages");
  }

  @Test
  public void testRegisterToolchains_singlePackageRestriction_underDir() throws Exception {
    // Test intentionally introduces errors.
    reporter.removeHandler(failFastHandler);
    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING, true)
            .build());

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "register_toolchains('//bar/...')");

    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        "invalid target pattern \"//bar/...\": register_toolchain target patterns "
            + "may only refer to targets within a single package");
  }

  @Test
  public void testRegisterToolchains_pathSyntax() throws Exception {
    // Test intentionally introduces errors.
    reporter.removeHandler(failFastHandler);

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "register_toolchains('bar/baz')");

    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        "Expected absolute target patterns (must begin with '//' or '@') for 'register_toolchains'"
            + " argument, but got 'bar/baz' as an argument");
  }

  @Test
  public void testRegisterToolchains_singlePackageRestriction() throws Exception {
    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING, true)
            .build());

    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "register_toolchains('//:whatever')",
        "register_toolchains('//bar:all')",
        "register_toolchains('//qux:baz')");
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isFalse();
  }

  @Test
  public void testNoUsages() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "http_archive = use_repo_rule('@bazel_tools//tools/build_defs/repo:http.bzl',"
            + " 'http_archive')");

    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isFalse();
    assertThat(result.get(ModuleFileValue.KEY_FOR_ROOT_MODULE).getModule().getExtensionUsages())
        .isEmpty();
  }

  @Test
  public void testInvalidRepoInPatches() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "single_version_override(",
        "    module_name = 'bbb',",
        "    version = '1.0',",
        "    patches = ['@unknown_repo//:patch.bzl'],",
        ")");

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        "Error in single_version_override: invalid label in 'patches': only patches in the main"
            + " repository can be applied, not from '@unknown_repo'");
  }

  @Test
  public void testInvalidUseExtensionLabel() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa')",
        "use_extension('@foo/bar:extensions.bzl', 'my_ext')");

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        "Error in use_extension: invalid label \"@foo/bar:extensions.bzl\": invalid repository"
            + " name 'foo/bar:extensions.bzl': repo names may contain only A-Z, a-z, 0-9, '-',"
            + " '_', '.' and '+'");
  }

  @Test
  public void testSingleVersionOverridePatches() throws Exception {
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    ModuleKey bbb = createModuleKey("bbb", "1.0");
    registry.addModule(bbb, "module(name='bbb',version='1.0')");

    scratch.file("BUILD");
    scratch.file(
        "patch.diff",
        """
        diff --git a/MODULE.bazel b/MODULE.bazel
        --- a/MODULE.bazel
        +++ b/MODULE.bazel
        @@ -1,1 +1,1 @@
        -module(name='bbb',version='1.0')
        +module(name='bbb',version='1.0',bazel_compatibility=[">=7.0.0"])
        diff --git a/not/MODULE.bazel b/not/MODULE.bazel
        --- a/not/MODULE.bazel
        +++ b/not/MODULE.bazel
        @@ -1,1 +1,2 @@
         module(name='bbb',version='1.0',bazel_compatibility=[">=7.0.0"])
        +bazel_dep(name='ccc',version='3.0')
        diff --git a/also/not/MODULE.bazel b/also/not/MODULE.bazel.bak
        similarity index 55%
        rename from also/not/MODULE.bazel
        rename to also/not/MODULE.bazel.bak
        index 3f855b5..949dd15 100644
        """);
    scratch.file("other/pkg/BUILD");
    Path otherPatch =
        scratch.file(
            "other/pkg/other_patch.diff",
            """
            --- a/MODULE.bazel
            +++ b/MODULE.bazel
            @@ -1,1 +1,2 @@
             module(name='bbb',version='1.0',bazel_compatibility=[">=7.0.0"])
            +bazel_dep(name='ccc',version='3.0')
            """);

    var moduleFileKey =
        ModuleFileValue.key(
            bbb,
            SingleVersionOverride.create(
                Version.EMPTY,
                "",
                ImmutableList.of(
                    Label.parseCanonicalUnchecked("//:patch.diff"),
                    Label.parseCanonicalUnchecked("@other_repo//:patch.diff"),
                    Label.parseCanonicalUnchecked("//other/pkg:other_patch.diff")),
                ImmutableList.of(),
                1));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(moduleFileKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(moduleFileKey).getModule().getBazelCompatibility())
        .isEqualTo(ImmutableList.of(">=7.0.0"));
    assertThat(result.get(moduleFileKey).getModule().getDeps())
        .containsExactly(
            "ccc", InterimModule.DepSpec.fromModuleKey(new ModuleKey("ccc", Version.parse("3.0"))));

    FileSystemUtils.writeContentAsLatin1(
        otherPatch,
        """
        --- a/MODULE.bazel
        +++ b/MODULE.bazel
        @@ -1,1 +1,2 @@
         module(name='bbb',version='1.0',bazel_compatibility=[">=7.0.0"])
        +bazel_dep(name='ccc',version='2.0')
        """);
    differencer.invalidate(
        ImmutableList.of(
            FileStateValue.key(RootedPath.toRootedPath(Root.fromPath(rootDirectory), otherPatch))));

    result = evaluator.evaluate(ImmutableList.of(moduleFileKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(moduleFileKey).getModule().getBazelCompatibility())
        .isEqualTo(ImmutableList.of(">=7.0.0"));
    assertThat(result.get(moduleFileKey).getModule().getDeps())
        .containsExactly(
            "ccc", InterimModule.DepSpec.fromModuleKey(new ModuleKey("ccc", Version.parse("2.0"))));
  }

  @Test
  public void testSingleVersionOverridePatches_failsOnRename() throws Exception {
    FakeRegistry registry = registryFactory.newFakeRegistry("/foo");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    ModuleKey bbb = createModuleKey("bbb", "1.0");
    registry.addModule(bbb, "module(name='bbb',version='1.0')");

    scratch.file("BUILD");
    scratch.file(
        "patch.diff",
        """
        diff --git a/MODULE.bazel b/MODULE.bazel.bak
        similarity index 55%
        rename from MODULE.bazel
        rename to MODULE.bazel.bak
        index 3f855b5..949dd15 100644
        """);

    var moduleFileKey =
        ModuleFileValue.key(
            bbb,
            SingleVersionOverride.create(
                Version.EMPTY,
                "",
                ImmutableList.of(Label.parseCanonicalUnchecked("//:patch.diff")),
                ImmutableList.of(),
                1));
    EvaluationResult<ModuleFileValue> result =
        evaluator.evaluate(ImmutableList.of(moduleFileKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "error applying single_version_override patch /workspace/patch.diff to module file:"
                + " Renaming /module/MODULE.bazel while applying patches to it as a single file is"
                + " not supported.");
  }

  @Test
  public void testOverrideRepo_overridingRepoDoesntExist() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        ext = use_extension('//:defs.bzl', 'ext')
        override_repo(ext, 'foo')
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:3:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 3, column 14, in <toplevel>
        \t\toverride_repo(ext, 'foo')
        Error in override_repo: The repo exported as 'foo' by module extension 'ext' is \
        overridden with 'foo', but no repo is visible under this name\
        """);
  }

  @Test
  public void testOverrideRepo_duplicateOverride() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        bazel_dep(name = "override1", version = "1.0")
        bazel_dep(name = "override2", version = "1.0")
        ext = use_extension('//:defs.bzl', 'ext')
        override_repo(ext, foo = "override1")
        override_repo(ext, foo = "override2")
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:6:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 6, column 14, in <toplevel>
        \t\toverride_repo(ext, foo = "override2")
        Error in override_repo: The repo exported as 'foo' by module extension 'ext' is already \
        overridden with 'override1' at /workspace/MODULE.bazel:5:14\
        """);
  }

  @Test
  public void testOverrideRepo_chain_singleExtension() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        bazel_dep(name = "override", version = "1.0")
        ext = use_extension('//:defs.bzl', 'ext')
        use_repo(ext, bar = "foo")
        override_repo(ext, baz = "bar")
        override_repo(ext, foo = "override")
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:5:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 5, column 14, in <toplevel>
        \t\toverride_repo(ext, baz = "bar")
        Error in override_repo: The repo 'bar' used as an override for 'baz' in module extension \
        'ext' is itself overridden with 'override' at /workspace/MODULE.bazel:6:14, which is not \
        supported.\
        """);
  }

  @Test
  public void testOverrideRepo_chain_multipleExtensions() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        bazel_dep(name = "override", version = "1.0")
        ext1 = use_extension('//:defs.bzl', 'ext1')
        ext2 = use_extension('//:defs.bzl', 'ext2')
        override_repo(ext1, baz = "bar")
        override_repo(ext2, foo = "override")
        use_repo(ext2, bar = "foo")
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:5:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 5, column 14, in <toplevel>
        \t\toverride_repo(ext1, baz = "bar")
        Error in override_repo: The repo 'bar' used as an override for 'baz' in module extension \
        'ext1' is itself overridden with 'override' at /workspace/MODULE.bazel:6:14, which is not \
        supported.\
        """);
  }

  @Test
  public void testOverrideRepo_cycle_singleExtension() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        bazel_dep(name = "override", version = "1.0")
        ext = use_extension('//:defs.bzl', 'ext')
        use_repo(ext, my_foo = "foo", my_bar = "bar")
        override_repo(ext, foo = "my_bar", bar = "my_foo")
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:5:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 5, column 14, in <toplevel>
        \t\toverride_repo(ext, foo = "my_bar", bar = "my_foo")
        Error in override_repo: The repo 'my_foo' used as an override for 'bar' in module \
        extension 'ext' is itself overridden with 'my_bar' at /workspace/MODULE.bazel:5:14, which \
        is not supported.\
        """);
  }

  @Test
  public void testOverrideRepo_cycle_multipleExtensions() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        ext1 = use_extension('//:defs.bzl', 'ext1')
        ext2 = use_extension('//:defs.bzl', 'ext2')
        override_repo(ext1, foo = "my_bar")
        override_repo(ext2, bar = "my_foo")
        use_repo(ext1, my_foo = "foo")
        use_repo(ext2, my_bar = "bar")
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:5:14: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 5, column 14, in <toplevel>
        \t\toverride_repo(ext2, bar = "my_foo")
        Error in override_repo: The repo 'my_foo' used as an override for 'bar' in module \
        extension 'ext2' is itself overridden with 'my_bar' at /workspace/MODULE.bazel:4:14, \
        which is not supported.\
        """);
  }

  @Test
  public void testInjectRepo_imported() throws Exception {
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        """
        module(name='aaa')
        bazel_dep(name = 'my_repo', version = "1.0")
        ext = use_extension('//:defs.bzl', 'ext')
        inject_repo(ext, foo = 'my_repo')
        use_repo(ext, bar = 'foo')
        """);

    reporter.removeHandler(failFastHandler);
    EvaluationResult<RootModuleFileValue> result =
        evaluator.evaluate(
            ImmutableList.of(ModuleFileValue.KEY_FOR_ROOT_MODULE), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertContainsEvent(
        """
        ERROR /workspace/MODULE.bazel:5:9: Traceback (most recent call last):
        \tFile "/workspace/MODULE.bazel", line 5, column 9, in <toplevel>
        \t\tuse_repo(ext, bar = 'foo')
        Error in use_repo: Cannot import repo 'foo' that has been injected into \
        module extension 'ext' at /workspace/MODULE.bazel:4:12. Please refer \
        to @my_repo directly.\
        """);
  }
}
