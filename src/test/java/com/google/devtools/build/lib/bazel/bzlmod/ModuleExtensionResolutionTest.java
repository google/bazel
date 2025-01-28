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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertEventCount;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.AutoloadSymbols;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlCompileFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadCycleReporter;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.BzlmodRepoCycleReporter;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingFunction;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for module extension resolution. */
@RunWith(JUnit4.class)
public class ModuleExtensionResolutionTest extends FoundationTestCase {

  private Path workspaceRoot;
  private Path modulesRoot;
  private MemoizingEvaluator evaluator;
  private EvaluationContext evaluationContext;
  private FakeRegistry registry;
  private RecordingDifferencer differencer;
  private final CyclesReporter cyclesReporter =
      new CyclesReporter(new BzlLoadCycleReporter(), new BzlmodRepoCycleReporter());

  @Before
  public void setup() throws Exception {
    workspaceRoot = scratch.dir("/ws");
    String bazelToolsPath = "/ws/embedded_tools";
    scratch.file(bazelToolsPath + "/MODULE.bazel", "module(name = 'bazel_tools')");
    scratch.file(bazelToolsPath + "/WORKSPACE");
    modulesRoot = scratch.dir("/modules");
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setParallelism(8).setEventHandler(reporter).build();
    FakeRegistry.Factory registryFactory = new FakeRegistry.Factory();
    registry = registryFactory.newFakeRegistry(modulesRoot.getPathString());
    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(workspaceRoot)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            workspaceRoot,
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

    PackageFactory packageFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);
    HashFunction hashFunction = fileSystem.getDigestFunction().getHashFunction();
    StarlarkRepositoryFunction starlarkRepositoryFunction = new StarlarkRepositoryFunction();

    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, new LocalRepositoryFunction());
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileValue.FILE, new FileFunction(packageLocator, directories))
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
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        workspaceRoot,
                        // Required to load @_builtins.
                        ImmutableMap.of("bazel_tools", LocalPathOverride.create(bazelToolsPath))))
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
                .put(
                    SkyFunctions.PACKAGE,
                    new PackageFunction(
                        /* packageFactory= */ null,
                        /* pkgLocator= */ null,
                        /* showLoadingProgress= */ null,
                        /* numPackagesSuccessfullyLoaded= */ new AtomicInteger(),
                        /* bzlLoadFunctionForInlining= */ null,
                        /* packageProgress= */ null,
                        PackageFunction.ActionOnIOExceptionReadingBuildFile.UseOriginalIOException
                            .INSTANCE,
                        /* shouldUseRepoDotBazel= */ true,
                        GlobbingStrategy.SKYFRAME_HYBRID,
                        ignored -> ThreadStateReceiver.NULL_INSTANCE,
                        /* cpuBoundSemaphore= */ new AtomicReference<>()))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction())
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.IGNORED_PACKAGE_PREFIXES,
                    new IgnoredPackagePrefixesFunction(
                        /* ignoredPackagePrefixesFile= */ PathFragment.EMPTY_FRAGMENT))
                .put(
                    SkyFunctions.REPOSITORY_MAPPING,
                    new RepositoryMappingFunction(ruleClassProvider))
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        ruleClassProvider,
                        packageFactory,
                        directories,
                        /* bzlLoadFunctionForInlining= */ null))
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
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory))
                .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(SkyFunctions.SINGLE_EXTENSION_USAGES, new SingleExtensionUsagesFunction())
                .put(SkyFunctions.SINGLE_EXTENSION, new SingleExtensionFunction())
                .put(
                    SkyFunctions.SINGLE_EXTENSION_EVAL,
                    new SingleExtensionEvalFunction(directories, ImmutableMap::of))
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
                .build(),
            differencer);

    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.ENABLE_BZLMOD, true)
            .setBool(BuildLanguageOptions.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, true)
            .build();
    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, semantics);
    AutoloadSymbols.AUTOLOAD_SYMBOLS.set(
        differencer, new AutoloadSymbols(ruleClassProvider, semantics));
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.FORCE_FETCH.set(
        differencer, RepositoryDelegatorFunction.FORCE_FETCH_DISABLED);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, packageLocator.get());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);
    RepositoryDelegatorFunction.IS_VENDOR_COMMAND.set(differencer, false);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());

    // Set up a simple repo rule.
    registry.addModule(
        createModuleKey("data_repo", "1.0"), "module(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("data_repo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("data_repo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("data_repo~v1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data = '+json.encode(ctx.attr.data))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.string()})");
  }

  @Test
  public void simpleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag(name='foo', data='fu')",
        "ext.tag(name='bar', data='ba')",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag}, "
            + "os_dependent=True, arch_dependent=True)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("foo:fu bar:ba");
  }

  @Test
  public void simpleExtension_nonCanonicalLabel() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='my_module', version = '1.0')",
        "bazel_dep(name='data_repo', version='1.0')",
        "ext1 = use_extension('//:defs.bzl', 'ext')",
        "ext1.tag(name='foo', data='fu')",
        "use_repo(ext1, 'foo')",
        "ext2 = use_extension('@my_module//:defs.bzl', 'ext')",
        "ext2.tag(name='bar', data='ba')",
        "use_repo(ext2, 'bar')",
        "ext3 = use_extension('@//:defs.bzl', 'ext')",
        "ext3.tag(name='quz', data='qu')",
        "use_repo(ext3, 'quz')",
        "ext4 = use_extension('defs.bzl', 'ext')",
        "ext4.tag(name='qor', data='qo')",
        "use_repo(ext4, 'qor')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "load('@quz//:data.bzl', quz_data='data')",
        "load('@qor//:data.bzl', qor_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data+' quz:'+quz_data+' qor:'+qor_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo:fu bar:ba quz:qu qor:qo");
  }

  @Test
  public void simpleExtension_nonCanonicalLabel_repoName() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='my_module', version = '1.0', repo_name='my_name')",
        "bazel_dep(name='data_repo', version='1.0')",
        "ext1 = use_extension('//:defs.bzl', 'ext')",
        "ext1.tag(name='foo', data='fu')",
        "use_repo(ext1, 'foo')",
        "ext2 = use_extension('@my_name//:defs.bzl', 'ext')",
        "ext2.tag(name='bar', data='ba')",
        "use_repo(ext2, 'bar')",
        "ext3 = use_extension('@//:defs.bzl', 'ext')",
        "ext3.tag(name='quz', data='qu')",
        "use_repo(ext3, 'quz')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "load('@quz//:data.bzl', quz_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data+' quz:'+quz_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("foo:fu bar:ba quz:qu");
  }

  @Test
  public void multipleExtensions_sameName() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "first_ext = use_extension('//first_ext:defs.bzl', 'ext')",
        "first_ext.tag(name='foo', data='first_fu')",
        "first_ext.tag(name='bar', data='first_ba')",
        "use_repo(first_ext, first_foo='foo', first_bar='bar')",
        "second_ext = use_extension('//second_ext:defs.bzl', 'ext')",
        "second_ext.tag(name='foo', data='second_fu')",
        "second_ext.tag(name='bar', data='second_ba')",
        "use_repo(second_ext, second_foo='foo', second_bar='bar')");
    scratch.file(workspaceRoot.getRelative("first_ext/BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("first_ext/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'name':attr.string(),'data':attr.string()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_repo(name=tag.name,data=tag.data)",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("second_ext/BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("second_ext/defs.bzl").getPathString(),
        "load('//first_ext:defs.bzl', _ext = 'ext')",
        "ext = _ext");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@first_foo//:data.bzl', first_foo_data='data')",
        "load('@first_bar//:data.bzl', first_bar_data='data')",
        "load('@second_foo//:data.bzl', second_foo_data='data')",
        "load('@second_bar//:data.bzl', second_bar_data='data')",
        "data = 'first_foo:'+first_foo_data+' first_bar:'+first_bar_data"
            + "+' second_foo:'+second_foo_data+' second_bar:'+second_bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo(
            "first_foo:first_fu first_bar:first_ba second_foo:second_fu " + "second_bar:second_ba");
  }

  @Test
  public void multipleModules() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='root',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='quux',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='quux',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");
    registry.addModule(
        createModuleKey("quux", "1.0"),
        "module(name='quux',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='quux@1.0')");
    registry.addModule(
        createModuleKey("quux", "2.0"),
        "module(name='quux',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='quux@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = ''",
        "  for mod in ctx.modules:",
        "    data_str += mod.name + '@' + mod.version + (' (root): ' if mod.is_root else ': ')",
        "    for tag in mod.tags.tag:",
        "      data_str += tag.data",
        "    data_str += '\\n'",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo(
            "root@1.0 (root): root\nfoo@1.0: foo@1.0\nbar@2.0: bar@2.0\nquux@2.0: quux@2.0\n");
  }

  @Test
  public void multipleModules_devDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'modules:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + tag.data + ' ' + str(ctx.is_dev_dependency(tag))",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("modules: root True bar@2.0 False");
  }

  @Test
  public void multipleModules_ignoreDevDependency() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='root')",
        "use_repo(ext,'ext_repo')");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext',dev_dependency=True)",
        "ext.tag(data='foo@1.0')");
    registry.addModule(
        createModuleKey("bar", "2.0"),
        "module(name='bar',version='2.0')",
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='bar@2.0')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'modules:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + tag.data + ' ' + str(ctx.is_dev_dependency(tag))",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, true);

    SkyKey skyKey =
        BzlLoadValue.keyForBuild(Label.parseCanonical("@@ext~~ext~ext_repo//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("modules: bar@2.0 False");
  }

  @Test
  public void multipleModules_isolatedUsages() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='root',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='root',expect_isolated=False)",
        "use_repo(ext,'ext_repo')",
        "isolated_ext = use_extension('@ext//:defs.bzl','ext',isolate=True)",
        "isolated_ext.tag(data='root_isolated',expect_isolated=True)",
        "use_repo(isolated_ext,isolated_ext_repo='ext_repo')",
        "isolated_dev_ext ="
            + " use_extension('@ext//:defs.bzl','ext',isolate=True,dev_dependency=True)",
        "isolated_dev_ext.tag(data='root_isolated_dev',expect_isolated=True)",
        "use_repo(isolated_dev_ext,isolated_dev_ext_repo='ext_repo')",
        "ext2 = use_extension('@ext//:defs.bzl','ext')",
        "ext2.tag(data='root_2',expect_isolated=False)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "load('@isolated_ext_repo//:data.bzl', isolated_ext_data='data')",
        "load('@isolated_dev_ext_repo//:data.bzl', isolated_dev_ext_data='data')",
        "data=ext_data",
        "isolated_data=isolated_ext_data",
        "isolated_dev_data=isolated_dev_ext_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "isolated_ext = use_extension('@ext//:defs.bzl','ext',isolate=True)",
        "isolated_ext.tag(data='foo@1.0_isolated',expect_isolated=True)",
        "use_repo(isolated_ext,isolated_ext_repo='ext_repo')",
        "isolated_dev_ext ="
            + " use_extension('@ext//:defs.bzl','ext',isolate=True,dev_dependency=True)",
        "isolated_dev_ext.tag(data='foo@1.0_isolated_dev',expect_isolated=True)",
        "use_repo(isolated_dev_ext,isolated_dev_ext_repo='ext_repo')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(data='foo@1.0',expect_isolated=False)",
        "use_repo(ext,'ext_repo')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "load('@isolated_ext_repo//:data.bzl', isolated_ext_data='data')",
        "data=ext_data",
        "isolated_data=isolated_ext_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = ''",
        "  for mod in ctx.modules:",
        "    data_str += mod.name + '@' + mod.version + (' (root): ' if mod.is_root else ': ')",
        "    for tag in mod.tags.tag:",
        "      data_str += tag.data",
        "      if tag.expect_isolated != ctx.is_isolated:",
        "        fail()",
        "    data_str += '\\n'",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'data':attr.string(),'expect_isolated':attr.bool()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("root@1.0 (root): rootroot_2\nfoo@1.0: foo@1.0\n");
    assertThat(result.get(skyKey).getModule().getGlobal("isolated_data"))
        .isEqualTo("root@1.0 (root): root_isolated\n");
    assertThat(result.get(skyKey).getModule().getGlobal("isolated_dev_data"))
        .isEqualTo("root@1.0 (root): root_isolated_dev\n");

    skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("@foo~//:data.bzl"));
    result = evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("root@1.0 (root): rootroot_2\nfoo@1.0: foo@1.0\n");
    assertThat(result.get(skyKey).getModule().getGlobal("isolated_data"))
        .isEqualTo("foo@1.0: foo@1.0_isolated\n");
  }

  @Test
  public void labels_readInModuleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@bar//:requirements.txt')");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~v2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~v2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~v2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_str = 'requirements:'",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + ctx.read(tag.file).strip()",
        "  data_repo(name='ext_repo',data=data_str)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("requirements: get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void labels_passedOnToRepoRule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "bazel_dep(name='ext',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@bar//:requirements.txt')");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~v2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~v2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~v2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(createModuleKey("ext", "1.0"), "module(name='ext',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ' '.join([ctx.read(l).strip() for l in ctx.attr.files])",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'files':attr.label_list()})",
        "",
        "def _ext_impl(ctx):",
        "  data_files = []",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_files.append(tag.file)",
        "  data_repo(name='ext_repo',files=data_files)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("get up at 6am. go to bed at 11pm.");

    SkyKey extensionSkyKey =
        SingleExtensionValue.key(
            ModuleExtensionId.create(
                Label.parseCanonicalUnchecked("@@ext+//:defs.bzl"), "ext", Optional.empty()));
    EvaluationResult<SingleExtensionValue> extensionResult =
        evaluator.evaluate(ImmutableList.of(extensionSkyKey), evaluationContext);
    if (extensionResult.hasError()) {
      throw extensionResult.getError().getException();
    }
    assertThat(
            extensionResult
                .get(extensionSkyKey)
                .lockFileInfo()
                .get()
                .moduleExtension()
                .getRecordedRepoMappingEntries())
        .containsCell(RepositoryName.create("foo+"), "bar", RepositoryName.create("bar+"));
  }

  @Test
  public void labels_fromExtensionGeneratedRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "myext = use_extension('//:defs.bzl','myext')",
        "use_repo(myext,'myrepo')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag(file='@myrepo//:requirements.txt')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _myrepo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('requirements.txt', 'get up at 6am.')",
        "myrepo = repository_rule(implementation=_myrepo_impl)",
        "",
        "def _myext_impl(ctx):",
        "  myrepo(name='myrepo')",
        "myext=module_extension(implementation=_myext_impl)");
    scratch.file(workspaceRoot.getRelative("requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(createModuleKey("ext", "1.0"), "module(name='ext',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ' '.join([ctx.read(l).strip() for l in ctx.attr.files])",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'files':attr.label_list()})",
        "",
        "def _ext_impl(ctx):",
        "  data_files = []",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_files.append(tag.file)",
        "  data_repo(name='ext_repo',files=data_files)",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("get up at 6am.");
  }

  @Test
  public void labels_constructedInModuleExtension_readInModuleExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "ext.tag()",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/requirements.txt").getPathString(), "get up at 6am.");
    registry.addModule(createModuleKey("bar", "2.0"), "module(name='bar',version='2.0')");
    scratch.file(modulesRoot.getRelative("bar~v2.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("bar~v2.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("bar~v2.0/requirements.txt").getPathString(), "go to bed at 11pm.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='bar',version='2.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        // The Label() call on the following line should work, using ext.1.0's repo mapping.
        "  data_str = 'requirements: ' + ctx.read(Label('@foo//:requirements.txt')).strip()",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      data_str += ' ' + ctx.read(tag.file).strip()",
        "  data_repo(name='ext_repo',data=data_str)",
        // So should the attr.label default value on the following line.
        "tag=tag_class(attrs={'file':attr.label(default='@bar//:requirements.txt')})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("requirements: get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void labels_constructedInModuleExtensionAsString_passedOnToRepoRule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl','ext')",
        "use_repo(ext,'ext_repo')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext_repo//:data.bzl', ext_data='data')",
        "data=ext_data");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/requirements.txt").getPathString(), "get up at 6am.");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='foo',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  content = ctx.read(ctx.attr.file).strip()",
        "  ctx.file('data.bzl', 'data='+json.encode(content))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'file':attr.label()})",
        "",
        "def _ext_impl(ctx):",
        // The label literal on the following line should be interpreted using ext.1.0's repo
        // mapping.
        "  data_repo(name='ext_repo',file='@foo//:requirements.txt')",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("get up at 6am.");
  }

  /** Tests that a complex-typed attribute (here, string_list_dict) behaves well on a tag. */
  @Test
  public void complexTypedAttribute() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag(data={'foo':['val1','val2'],'bar':['val3','val4']})",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {'data':attr.string_list_dict()})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      for key in tag.data:",
        "        data_repo(name=key,data=','.join(tag.data[key]))",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo:val1,val2 bar:val3,val4");
  }

  /**
   * Tests that a complex-typed attribute (here, string_list_dict) behaves well when it has a
   * default value and is omitted in a tag.
   */
  @Test
  public void complexTypedAttribute_default() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.tag()",
        "use_repo(ext, 'foo', 'bar')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "tag = tag_class(attrs = {",
        "  'data': attr.string_list_dict(",
        "    default = {'foo':['val1','val2'],'bar':['val3','val4']},",
        ")})",
        "def _ext_impl(ctx):",
        "  for mod in ctx.modules:",
        "    for tag in mod.tags.tag:",
        "      for key in tag.data:",
        "        data_repo(name=key,data=','.join(tag.data[key]))",
        "ext = module_extension(implementation=_ext_impl, tag_classes={'tag':tag})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@foo//:data.bzl', foo_data='data')",
        "load('@bar//:data.bzl', bar_data='data')",
        "data = 'foo:'+foo_data+' bar:'+bar_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo:val1,val2 bar:val3,val4");
  }

  @Test
  public void generatedReposHaveCorrectMappings() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', foo_data='data')",
        "load('@internal//:data.bzl', internal_data='data')",
        "data = 'foo: '+foo_data+' internal: '+internal_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _internal_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data='+json.encode('internal-stuff'))",
        "internal_repo = repository_rule(implementation=_internal_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  internal_repo(name='internal')",
        "  ext_repo(name='ext')",
        "ext=module_extension(implementation=_ext_impl)");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/data.bzl").getPathString(), "data = 'foo-stuff'");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("foo: foo-stuff internal: internal-stuff");
  }

  @Test
  public void generatedReposHaveCorrectMappings_moduleOwnRepoName() throws Exception {
    // tests that things work correctly when the module specifies its own repo name (via
    // `module(repo_name=...)`).
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='foo',version='1.0',repo_name='bar')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(workspaceRoot.getRelative("data.bzl").getPathString(), "data='hello world'");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@bar//:data.bzl', bar_data='data')",
        "data = 'bar: '+bar_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "ext=module_extension(implementation=lambda ctx: ext_repo(name='ext'))");
    scratch.file(
        workspaceRoot.getRelative("ext_data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data='ext: ' + ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:ext_data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("ext: bar: hello world");
  }

  @Test
  public void generatedReposHaveCorrectMappings_internalRepoWins() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', foo_data='data')",
        "data = 'the foo I see is '+foo_data",
        "\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _internal_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', 'data='+json.encode('inner-foo'))",
        "internal_repo = repository_rule(implementation=_internal_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  internal_repo(name='foo')",
        "  ext_repo(name='ext')",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo',version='1.0')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/data.bzl").getPathString(), "data = 'outer-foo'");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("the foo I see is inner-foo");
  }

  @Test
  public void generatedReposHaveCorrectMappings_strictDepsViolation() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _ext_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "  ctx.file('data.bzl', \"\"\"load('@foo//:data.bzl', 'data')\"\"\")",
        "ext_repo = repository_rule(implementation=_ext_repo_impl)",
        "",
        "def _ext_impl(ctx):",
        "  ext_repo(name='ext')",
        "tag=tag_class(attrs={'file':attr.label()})",
        "ext=module_extension(implementation=_ext_impl,tag_classes={'tag':tag})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains("No repository visible as '@foo' from repository '@@_main~ext~ext'");
  }

  @Test
  public void wrongModuleExtensionLabel() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//foo/defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "Label '//foo/defs.bzl:defs.bzl' is invalid because 'foo/defs.bzl' is not a package");
  }

  @Test
  public void importNonExistentRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "bazel_dep(name='data_repo', version='1.0')",
        "use_repo(ext,my_repo='missing_repo')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='ext',data='void')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@@_main~ext~ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "module extension \"ext\" from \"//:defs.bzl\" does not generate repository"
                + " \"missing_repo\", yet it is imported as \"my_repo\" in the usage at"
                + " /ws/MODULE.bazel:1:20");
  }

  @Test
  public void invalidAttributeValue() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "bazel_dep(name='data_repo', version='1.0')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='ext',data=42)",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "ERROR /ws/defs.bzl:3:12: //:_main~ext~ext: expected value of type 'string' for attribute 'data'"
            + " in 'data_repo' rule, but got 42 (int)");
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo("error evaluating module extension ext in //:defs.bzl");
  }

  @Test
  public void badRepoNameInExtensionImplFunction() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "bazel_dep(name='data_repo', version='1.0')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='_something',data='void')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent("invalid user-provided repo name '_something'");
  }

  @Test
  public void nonVisibleLabelInLabelAttr() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.label()})",
        "def _ext_impl(ctx):",
        "  data_repo(name='other_repo')",
        "  data_repo(name='ext',data='@not_other_repo//:foo')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent(
        """
        ERROR /ws/defs.bzl:9:12: Traceback (most recent call last):
        \tFile "/ws/defs.bzl", line 9, column 12, in _ext_impl
        \t\tdata_repo(name='ext',data='@not_other_repo//:foo')
        Error in repository_rule: no repository visible as '@not_other_repo' in \
        the extension '@@//:defs.bzl%ext', but referenced by label \
        '@not_other_repo//:foo' in attribute 'data' of data_repo 'ext'.""");
  }

  @Test
  public void nonVisibleLabelInLabelAttrNonRootModule() throws Exception {
    registry.addModule(
        createModuleKey("ext_module", "1.0"), "module(name='ext_module',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext_module~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext_module~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext_module~v1.0/defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.label()})",
        "def _ext_impl(ctx):",
        "  data_repo(name='other_repo')",
        "  data_repo(name='ext',data='@not_other_repo//:foo')",
        "ext = module_extension(implementation=_ext_impl)");

    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name = 'ext_module', version = '1.0')",
        "ext = use_extension('@ext_module//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent(
        """
        ERROR /usr/local/google/_blaze_jrluser/FAKEMD5/external/ext_module~/defs.bzl:9:12: \
        Traceback (most recent call last):
        \tFile "/usr/local/google/_blaze_jrluser/FAKEMD5/external/ext_module~/defs.bzl", \
        line 9, column 12, in _ext_impl
        \t\tdata_repo(name='ext',data='@not_other_repo//:foo')
        Error in repository_rule: no repository visible as '@not_other_repo' in the extension \
        '@@ext_module~//:defs.bzl%ext', but referenced by label '@not_other_repo//:foo' in \
        attribute 'data' of data_repo 'ext'.""");
  }

  @Test
  public void nonVisibleLabelInLabelAttrForwardedFromTag() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "ext.label(label = '@other_repo//:foo')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.label()})",
        "def _ext_impl(ctx):",
        "  data_repo(name='other_repo')",
        "  data_repo(name='ext',data=ctx.modules[0].tags.label[0].label)",
        "label=tag_class(attrs={'label':attr.label()})",
        "ext = module_extension(",
        "  implementation=_ext_impl,",
        "  tag_classes={'label':label},",
        ")");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    var result = evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "in tag at /ws/MODULE.bazel:2:10: no repository visible as '@other_repo' to the root"
                + " module, but referenced by label '@other_repo//:foo' in attribute 'label' of tag"
                + " 'label'.");
  }

  @Test
  public void nonVisibleLabelInLabelListAttr() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.label_list()})",
        "def _ext_impl(ctx):",
        "  data_repo(name='other_repo')",
        "  data_repo(name='ext',data=['@not_other_repo//:foo'])",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent(
        """
        ERROR /ws/defs.bzl:9:12: Traceback (most recent call last):
        \tFile "/ws/defs.bzl", line 9, column 12, in _ext_impl
        \t\tdata_repo(name='ext',data=['@not_other_repo//:foo'])
        Error in repository_rule: no repository visible as '@not_other_repo' \
        in the extension '@@//:defs.bzl%ext', but referenced by label \
        '@not_other_repo//:foo' in attribute 'data' of data_repo 'ext'.""");
  }

  @Test
  public void nonVisibleLabelInLabelKeyedStringDictAttr() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl','ext')",
        "use_repo(ext,'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('WORKSPACE')",
        "  ctx.file('BUILD')",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl,",
        "  attrs={'data':attr.label_keyed_string_dict()})",
        "def _ext_impl(ctx):",
        "  data_repo(name='other_repo')",
        "  data_repo(name='ext',data={'@not_other_repo//:foo':'bar'})",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data=ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertContainsEvent(
        """
        ERROR /ws/defs.bzl:9:12: Traceback (most recent call last):
        \tFile "/ws/defs.bzl", line 9, column 12, in _ext_impl
        \t\tdata_repo(name='ext',data={'@not_other_repo//:foo':'bar'})
        Error in repository_rule: no repository visible as '@not_other_repo' \
        in the extension '@@//:defs.bzl%ext', but referenced by label \
        '@not_other_repo//:foo' in attribute 'data' of data_repo 'ext'.""");
  }

  @Test
  public void nativeExistingRuleIsEmpty() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo', version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'ext')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  if not native.existing_rules():",
        "    data_repo(name='ext',data='haha')",
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext//:data.bzl', ext_data='data')",
        "data = ext_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("haha");
  }

  @Test
  public void extensionLoadsRepoFromAnotherExtension() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'summarized_candy')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'exposed_candy')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@@ext~~ext~candy//:data.bzl', candy='data')",
        "load('@exposed_candy//:data.bzl', exposed_candy='data')",
        "def _ext_impl(ctx):",
        "  data_str = exposed_candy + ' (and ' + candy + ')'",
        "  data_repo(name='summarized_candy', data=data_str)",
        "my_ext=module_extension(implementation=_ext_impl)");

    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@summarized_candy//:data.bzl', data='data')",
        "candy_data = 'candy: ' + data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy', data='cotton candy')",
        "  data_repo(name='exposed_candy', data='lollipops')",
        "ext = module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("candy_data"))
        .isEqualTo("candy: lollipops (and cotton candy)");
  }

  @Test
  public void extensionRepoCtxReadsFromAnotherExtensionRepo() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        // Repos from this extension (i.e. my_ext2) can still be used if their canonical name is
        // somehow known
        "my_ext2 = use_extension('@//:defs.bzl', 'my_ext2')");

    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_file = ctx.read(Label('@@_main~my_ext2~candy2//:data.bzl'))",
        "  data_repo(name='candy1',data=data_file)",
        "my_ext=module_extension(implementation=_ext_impl)",
        "def _ext_impl2(ctx):",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl2)");

    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@candy1//:data.bzl', data='data')",
        "candy_data_file = data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw Objects.requireNonNull(result.getError().getException());
    }
    assertThat(result.get(skyKey).getModule().getGlobal("candy_data_file"))
        .isEqualTo("data = \"lollipops\"");
  }

  @Test
  public void testReportRepoAndBzlCycles_circularExtReposCtxRead() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        "my_ext2 = use_extension('@//:defs.bzl', 'my_ext2')",
        "use_repo(my_ext2, 'candy2')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  ctx.read(Label('@candy2//:data.bzl'))",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)",
        "def _ext_impl2(ctx):",
        "  ctx.read(Label('@candy1//:data.bzl'))",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl2)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"), PathFragment.EMPTY_FRAGMENT);
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @@_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   @@_main~my_ext2~candy2\n"
            + "|   extension 'my_ext2' defined in //:defs.bzl\n"
            + "`-- @@_main~my_ext~candy1");
  }

  @Test
  public void testReportRepoAndBzlCycles_circularExtReposLoadInDefFile() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')",
        "my_ext2 = use_extension('@//:defs2.bzl', 'my_ext2')",
        "use_repo(my_ext2, 'candy2')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  ctx.read(Label('@candy2//:data.bzl'))",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)");
    scratch.file(
        workspaceRoot.getRelative("defs2.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@candy1//:data.bzl','data')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy2',data='lollipops')",
        "my_ext2=module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"),
            PathFragment.create("data.bzl"));
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @@_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   @@_main~my_ext2~candy2\n"
            + "|   extension 'my_ext2' defined in //:defs2.bzl\n"
            + "|   //:defs2.bzl\n"
            + "|   @@_main~my_ext~candy1//:data.bzl\n"
            + "`-- @@_main~my_ext~candy1");
  }

  @Test
  public void testReportRepoAndBzlCycles_extRepoLoadSelfCycle() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='data_repo',version='1.0')",
        "my_ext = use_extension('@//:defs.bzl', 'my_ext')",
        "use_repo(my_ext, 'candy1')");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "load('@candy1//:data.bzl','data')",
        "def _ext_impl(ctx):",
        "  data_repo(name='candy1',data='lollipops')",
        "my_ext=module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    SkyKey skyKey =
        PackageIdentifier.create(
            RepositoryName.createUnvalidated("_main~my_ext~candy1"),
            PathFragment.create("data.bzl"));
    EvaluationResult<PackageValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getCycleInfo()).isNotEmpty();
    reporter.removeHandler(failFastHandler);
    cyclesReporter.reportCycles(
        result.getError().getCycleInfo(), skyKey, evaluationContext.getEventHandler());
    assertContainsEvent(
        "ERROR <no location>: Circular definition of repositories generated by module extensions"
            + " and/or .bzl files:\n"
            + ".-> @@_main~my_ext~candy1\n"
            + "|   extension 'my_ext' defined in //:defs.bzl\n"
            + "|   //:defs.bzl\n"
            + "|   @@_main~my_ext~candy1//:data.bzl\n"
            + "`-- @@_main~my_ext~candy1");
  }

  @Test
  public void extensionMetadata_exactlyOneArgIsNone() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps=['foo'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must both be specified or both be"
            + " unspecified");
  }

  @Test
  public void extensionMetadata_exactlyOneArgIsNoneDev() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_dev_deps=['foo'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must both be specified or both be"
            + " unspecified");
  }

  @Test
  public void extensionMetadata_allUsedTwice() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps='all',root_module_direct_dev_deps='all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "if one of root_module_direct_deps and root_module_direct_dev_deps is \"all\", the other"
            + " must be an empty list");
  }

  @Test
  public void extensionMetadata_allAndNone() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps='all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "if one of root_module_direct_deps and root_module_direct_dev_deps is \"all\", the other"
            + " must be an empty list");
  }

  @Test
  public void extensionMetadata_unsupportedString() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_deps='not_all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must be None, \"all\", or a list"
            + " of strings");
  }

  @Test
  public void extensionMetadata_unsupportedStringDev() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return ctx.extension_metadata(root_module_direct_dev_deps='not_all')");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps and root_module_direct_dev_deps must be None, \"all\", or a list"
            + " of strings");
  }

  @Test
  public void extensionMetadata_invalidRepoName() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['~invalid'],root_module_direct_dev_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_deps: invalid user-provided repo name '~invalid': valid names may"
            + " contain only A-Z, a-z, 0-9, '-', '_', '.', and must start with a letter");
  }

  @Test
  public void extensionMetadata_invalidDevRepoName() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_dev_deps=['~invalid'],root_module_direct_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_dev_deps: invalid user-provided repo name '~invalid': valid names"
            + " may contain only A-Z, a-z, 0-9, '-', '_', '.', and must start with a letter");
  }

  @Test
  public void extensionMetadata_duplicateRepo() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep','dep'],root_module_direct_dev_deps=[])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("in root_module_direct_deps: duplicate entry 'dep'");
  }

  @Test
  public void extensionMetadata_duplicateDevRepo() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=[],root_module_direct_dev_deps=['dep','dep'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("in root_module_direct_dev_deps: duplicate entry 'dep'");
  }

  @Test
  public void extensionMetadata_duplicateRepoAcrossTypes() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep'],root_module_direct_dev_deps=['dep'])");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "in root_module_direct_dev_deps: entry 'dep' is also in root_module_direct_deps");
  }

  @Test
  public void extensionMetadata_devUsageWithAllDirectNonDevDeps() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=\"all\","
                + "root_module_direct_dev_deps=[])",
            /* devDependency= */ true);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps must be empty if the root module contains no usages with "
            + "dev_dependency = False");
  }

  @Test
  public void extensionMetadata_nonDevUsageWithAllDirectDevDeps() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=[],"
                + "root_module_direct_dev_deps=\"all\")",
            /* devDependency= */ false);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_dev_deps must be empty if the root module contains no usages with "
            + "dev_dependency = True");
  }

  @Test
  public void extensionMetadata_devUsageWithDirectNonDevDeps() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep1'],"
                + "root_module_direct_dev_deps=['dep2'])",
            /* devDependency= */ true);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_deps must be empty if the root module contains no usages with "
            + "dev_dependency = False");
  }

  @Test
  public void extensionMetadata_nonDevUsageWithDirectDevDeps() throws Exception {
    var result =
        evaluateSimpleModuleExtension(
            "return"
                + " ctx.extension_metadata(root_module_direct_deps=['dep1'],"
                + "root_module_direct_dev_deps=['dep2'])",
            /* devDependency= */ false);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "root_module_direct_dev_deps must be empty if the root module contains no usages with "
            + "dev_dependency = True");
  }

  @Test
  public void extensionMetadata() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(",
        "  ext,",
        "  'indirect_dep',",
        "  'invalid_dep',",
        "  'dev_as_non_dev_dep',",
        "  my_direct_dep = 'direct_dep',",
        ")",
        "inject_repo(ext, my_data_repo = 'data_repo')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(",
        "  ext_dev,",
        "  'indirect_dev_dep',",
        "  'invalid_dev_dep',",
        "  'non_dev_as_dev_dep',",
        "  my_direct_dev_dep = 'direct_dev_dep',",
        ")");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@my_direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  data_repo(name='dev_as_non_dev_dep')",
        "  data_repo(name='non_dev_as_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=['direct_dep', 'missing_direct_dep', 'non_dev_as_dev_dep'],",
        "    root_module_direct_dev_deps=['direct_dev_dep', 'missing_direct_dev_dep',"
            + " 'dev_as_non_dev_dep'],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "Imported as a regular dependency, but reported as a dev dependency by the"
            + " extension (may cause the build to fail when used by other modules):\n"
            + "    dev_as_non_dev_dep\n"
            + "\n"
            + "Imported as a dev dependency, but reported as a regular dependency by the"
            + " extension (may cause the build to fail when used by other modules):\n"
            + "    non_dev_as_dev_dep\n"
            + "\n"
            + "Imported, but reported as indirect dependencies by the extension:\n"
            + "    indirect_dep, indirect_dev_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue evalValue =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"), "ext", Optional.empty())));
    assertThat(evalValue.getFixup()).isPresent();
    assertThat(evalValue.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext missing_direct_dep non_dev_as_dev_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext dev_as_non_dev_dep indirect_dep invalid_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext_dev dev_as_non_dev_dep missing_direct_dev_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext_dev indirect_dev_dep invalid_dev_dep non_dev_as_dev_dep");
    assertThat(evalValue.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for @ext//:defs.bzl%ext");
  }

  @Test
  public void extensionMetadata_includes() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "include('//:firstProd.MODULE.bazel')",
        "include('//:second.MODULE.bazel')");
    scratch.file(
        workspaceRoot.getRelative("firstProd.MODULE.bazel").getPathString(),
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(",
        "  ext,",
        "  'indirect_dep',",
        "  'invalid_dep',",
        "  'dev_as_non_dev_dep',",
        "  my_direct_dep = 'direct_dep',",
        ")",
        "include('//:firstDev.MODULE.bazel')");
    scratch.file(
        workspaceRoot.getRelative("firstDev.MODULE.bazel").getPathString(),
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(",
        "  ext_dev,",
        "  'indirect_dev_dep',",
        "  'invalid_dev_dep',",
        "  'non_dev_as_dev_dep',",
        "  my_direct_dev_dep = 'direct_dev_dep',",
        ")");
    scratch.file(
        workspaceRoot.getRelative("second.MODULE.bazel").getPathString(),
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'invalid_dep2')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'invalid_dev_dep2')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@my_direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  data_repo(name='dev_as_non_dev_dep')",
        "  data_repo(name='non_dev_as_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=['direct_dep', 'missing_direct_dep', 'non_dev_as_dev_dep'],",
        "    root_module_direct_dev_deps=['direct_dev_dep', 'missing_direct_dev_dep',"
            + " 'dev_as_non_dev_dep'],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        """
        WARNING /ws/firstProd.MODULE.bazel:1:20: The module extension ext defined in \
        @ext//:defs.bzl reported incorrect imports of repositories via use_repo():

        Imported, but not created by the extension (will cause the build to fail):
            invalid_dep, invalid_dep2, invalid_dev_dep, invalid_dev_dep2

        Not imported, but reported as direct dependencies by the extension (may cause the\
         build to fail):
            missing_direct_dep, missing_direct_dev_dep

        Imported as a regular dependency, but reported as a dev dependency by the\
         extension (may cause the build to fail when used by other modules):
            dev_as_non_dev_dep

        Imported as a dev dependency, but reported as a regular dependency by the\
         extension (may cause the build to fail when used by other modules):
            non_dev_as_dev_dep

        Imported, but reported as indirect dependencies by the extension:
            indirect_dep, indirect_dev_dep

        Fix the use_repo calls by running 'bazel mod tidy'.""",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue evalValue =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"), "ext", Optional.empty())));
    assertThat(evalValue.getFixup()).isPresent();
    assertThat(evalValue.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("firstProd.MODULE.bazel"),
            "use_repo_add ext missing_direct_dep non_dev_as_dev_dep",
            PathFragment.create("firstProd.MODULE.bazel"),
            "use_repo_remove ext dev_as_non_dev_dep indirect_dep invalid_dep",
            PathFragment.create("second.MODULE.bazel"),
            "use_repo_remove ext invalid_dep2",
            PathFragment.create("firstDev.MODULE.bazel"),
            "use_repo_add ext_dev dev_as_non_dev_dep missing_direct_dev_dep",
            PathFragment.create("firstDev.MODULE.bazel"),
            "use_repo_remove ext_dev indirect_dev_dep invalid_dev_dep non_dev_as_dev_dep",
            PathFragment.create("second.MODULE.bazel"),
            "use_repo_remove ext_dev invalid_dev_dep2");
    assertThat(evalValue.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for @ext//:defs.bzl%ext");
  }

  @Test
  public void extensionMetadata_all() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'direct_dep', 'indirect_dep', 'invalid_dep')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'direct_dev_dep', 'indirect_dev_dep', 'invalid_dev_dep')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps='all',",
        "    root_module_direct_dev_deps=[],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "module extension \"ext\" from \"@@ext~//:defs.bzl\" does not generate repository "
                + "\"invalid_dep\", yet it is imported as \"invalid_dep\" in the usage at "
                + "/ws/MODULE.bazel:3:20");

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "Imported as a dev dependency, but reported as a regular dependency by the"
            + " extension (may cause the build to fail when used by other modules):\n"
            + "    direct_dev_dep, indirect_dev_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue evalValue =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"), "ext", Optional.empty())));
    assertThat(evalValue.getFixup()).isPresent();
    assertThat(evalValue.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext direct_dev_dep indirect_dev_dep missing_direct_dep"
                + " missing_direct_dev_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext invalid_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext_dev direct_dev_dep indirect_dev_dep invalid_dev_dep");
    assertThat(evalValue.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for @ext//:defs.bzl%ext");
  }

  @Test
  public void extensionMetadata_allDev() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('@ext//:defs.bzl', 'ext')",
        "use_repo(ext, 'direct_dep', 'indirect_dep', 'invalid_dep')",
        "ext_dev = use_extension('@ext//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'direct_dev_dep', 'indirect_dev_dep', 'invalid_dev_dep')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', direct_dep_data='data')",
        "data = direct_dep_data");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=[],",
        "    root_module_direct_dev_deps='all',",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "module extension \"ext\" from \"@@ext~//:defs.bzl\" does not generate repository "
                + "\"invalid_dep\", yet it is imported as \"invalid_dep\" in the usage at "
                + "/ws/MODULE.bazel:3:20");

    assertEventCount(1, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:20: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Imported, but not created by the extension (will cause the build to fail):\n"
            + "    invalid_dep, invalid_dev_dep\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep, missing_direct_dev_dep\n"
            + "\n"
            + "Imported as a regular dependency, but reported as a dev dependency by the"
            + " extension (may cause the build to fail when used by other modules):\n"
            + "    direct_dep, indirect_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue evalValue =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"), "ext", Optional.empty())));
    assertThat(evalValue.getFixup()).isPresent();
    assertThat(evalValue.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext direct_dep indirect_dep invalid_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext_dev direct_dep indirect_dep missing_direct_dep"
                + " missing_direct_dev_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext_dev invalid_dev_dep");
    assertThat(evalValue.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for @ext//:defs.bzl%ext");
  }

  @Test
  public void extensionMetadata_noRootUsage() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')",
        "ext_dev = use_extension('//:defs.bzl', 'ext', dev_dependency = True)",
        "use_repo(ext_dev, 'indirect_dev_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='direct_dev_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='missing_direct_dev_dep')",
        "  data_repo(name='indirect_dep', data='indirect_dep_data')",
        "  data_repo(name='indirect_dev_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps='all',",
        "    root_module_direct_dev_deps=[],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/data.bzl").getPathString(),
        "load('@indirect_dep//:data.bzl', indirect_dep_data='data')",
        "data = indirect_dep_data");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("@ext~//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.get(skyKey).getModule().getGlobal("data")).isEqualTo("indirect_dep_data");

    assertEventCount(0, eventCollector);
    SingleExtensionValue evalValue =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"), "ext", Optional.empty())));
    assertThat(evalValue.getFixup()).isEmpty();
  }

  @Test
  public void extensionMetadata_isolated() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext1 = use_extension('@ext//:defs.bzl', 'ext', isolate = True)",
        "use_repo(",
        "  ext1,",
        "  'indirect_dep',",
        ")",
        "ext2 = use_extension('@ext//:defs.bzl', 'ext', isolate = True)",
        "use_repo(",
        "  ext2,",
        "  'direct_dep',",
        ")");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', data_1='data')",
        "load('@indirect_dep//:data.bzl', data_2='data')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='indirect_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=['direct_dep', 'missing_direct_dep'],",
        "    root_module_direct_dev_deps=[],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isFalse();

    assertEventCount(2, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:21: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    direct_dep, missing_direct_dep\n"
            + "\n"
            + "Imported, but reported as indirect dependencies by the extension:\n"
            + "    indirect_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:8:21: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue ext1Value =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"),
                            "ext",
                            Optional.of(
                                ModuleExtensionId.IsolationKey.create(ModuleKey.ROOT, "ext1")))));
    assertThat(ext1Value.getFixup()).isPresent();
    assertThat(ext1Value.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext1 direct_dep missing_direct_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext1 indirect_dep");
    assertThat(ext1Value.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for isolated usage 'ext1' of @ext//:defs.bzl%ext");
    SingleExtensionValue ext2Value =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"),
                            "ext",
                            Optional.of(
                                ModuleExtensionId.IsolationKey.create(ModuleKey.ROOT, "ext2")))));
    assertThat(ext2Value.getFixup()).isPresent();
    assertThat(ext2Value.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"), "use_repo_add ext2 missing_direct_dep");
    assertThat(ext2Value.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for isolated usage 'ext2' of @ext//:defs.bzl%ext");
  }

  @Test
  public void extensionMetadata_isolatedDev() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='ext', version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext1 = use_extension('@ext//:defs.bzl', 'ext', isolate = True, dev_dependency = True)",
        "use_repo(",
        "  ext1,",
        "  'indirect_dep',",
        ")",
        "ext2 = use_extension('@ext//:defs.bzl', 'ext', isolate = True, dev_dependency = True)",
        "use_repo(",
        "  ext2,",
        "  'direct_dep',",
        ")");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@direct_dep//:data.bzl', data_1='data')",
        "load('@indirect_dep//:data.bzl', data_2='data')");

    registry.addModule(
        createModuleKey("ext", "1.0"),
        "module(name='ext',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext = use_extension('//:defs.bzl', 'ext')",
        "use_repo(ext, 'indirect_dep')");
    scratch.file(modulesRoot.getRelative("ext~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("ext~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("ext~v1.0/defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(ctx):",
        "  data_repo(name='direct_dep')",
        "  data_repo(name='missing_direct_dep')",
        "  data_repo(name='indirect_dep')",
        "  return ctx.extension_metadata(",
        "    root_module_direct_deps=[],",
        "    root_module_direct_dev_deps=['direct_dep', 'missing_direct_dep'],",
        "  )",
        "ext=module_extension(implementation=_ext_impl)");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    // Evaluation fails due to the import of a repository not generated by the extension, but we
    // only want to assert that the warning is emitted.
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isFalse();

    assertEventCount(2, eventCollector);
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:3:21: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    direct_dep, missing_direct_dep\n"
            + "\n"
            + "Imported, but reported as indirect dependencies by the extension:\n"
            + "    indirect_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    assertContainsEvent(
        "WARNING /ws/MODULE.bazel:8:21: The module extension ext defined in @ext//:defs.bzl"
            + " reported incorrect imports of repositories via use_repo():\n"
            + "\n"
            + "Not imported, but reported as direct dependencies by the extension (may cause the"
            + " build to fail):\n"
            + "    missing_direct_dep\n"
            + "\n"
            + "Fix the use_repo calls by running 'bazel mod tidy'.",
        ImmutableSet.of(EventKind.WARNING));
    SingleExtensionValue ext1Value =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"),
                            "ext",
                            Optional.of(
                                ModuleExtensionId.IsolationKey.create(ModuleKey.ROOT, "ext1")))));
    assertThat(ext1Value.getFixup()).isPresent();
    assertThat(ext1Value.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"),
            "use_repo_add ext1 direct_dep missing_direct_dep",
            PathFragment.create("MODULE.bazel"),
            "use_repo_remove ext1 indirect_dep");
    assertThat(ext1Value.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for isolated usage 'ext1' of @ext//:defs.bzl%ext");
    SingleExtensionValue ext2Value =
        (SingleExtensionValue)
            evaluator
                .getDoneValues()
                .get(
                    SingleExtensionValue.evalKey(
                        ModuleExtensionId.create(
                            Label.parseCanonical("@@ext~//:defs.bzl"),
                            "ext",
                            Optional.of(
                                ModuleExtensionId.IsolationKey.create(ModuleKey.ROOT, "ext2")))));
    assertThat(ext2Value.getFixup()).isPresent();
    assertThat(ext2Value.getFixup().get().moduleFilePathToBuildozerCommands())
        .containsExactly(
            PathFragment.create("MODULE.bazel"), "use_repo_add ext2 missing_direct_dep");
    assertThat(ext2Value.getFixup().get().getSuccessMessage())
        .isEqualTo("Updated use_repo calls for isolated usage 'ext2' of @ext//:defs.bzl%ext");
  }

  private EvaluationResult<SingleExtensionValue> evaluateSimpleModuleExtension(
      String returnStatement) throws Exception {
    return evaluateSimpleModuleExtension(returnStatement, /* devDependency= */ false);
  }

  private EvaluationResult<SingleExtensionValue> evaluateSimpleModuleExtension(
      String returnStatement, boolean devDependency) throws Exception {
    String devDependencyStr = devDependency ? "True" : "False";
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        String.format(
            "ext = use_extension('//:defs.bzl', 'ext', dev_dependency = %s)", devDependencyStr));
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "repo = repository_rule(lambda ctx: True)",
        "def _ext_impl(ctx):",
        "  repo(name = 'dep1')",
        "  repo(name = 'dep2')",
        "  " + returnStatement,
        "ext = module_extension(implementation=_ext_impl)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    ModuleExtensionId extensionId =
        ModuleExtensionId.create(Label.parseCanonical("//:defs.bzl"), "ext", Optional.empty());
    reporter.removeHandler(failFastHandler);
    return evaluator.evaluate(
        ImmutableList.of(SingleExtensionValue.key(extensionId)), evaluationContext);
  }

  @Test
  public void isDevDependency_usages() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='root',version='1.0')",
        "bazel_dep(name='data_repo',version='1.0')",
        "ext1 = use_extension('//:defs.bzl','ext1')",
        "use_repo(ext1,ext1_repo='ext_repo')",
        "ext2 = use_extension('//:defs.bzl','ext2',dev_dependency=True)",
        "use_repo(ext2,ext2_repo='ext_repo')",
        "ext3a = use_extension('//:defs.bzl','ext3')",
        "use_repo(ext3a,ext3_repo='ext_repo')",
        "ext3b = use_extension('//:defs.bzl','ext3',dev_dependency=True)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@ext1_repo//:data.bzl', _ext1_data='data')",
        "load('@ext2_repo//:data.bzl', _ext2_data='data')",
        "load('@ext3_repo//:data.bzl', _ext3_data='data')",
        "ext1_data=_ext1_data",
        "ext2_data=_ext2_data",
        "ext3_data=_ext3_data");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "load('@data_repo//:defs.bzl','data_repo')",
        "def _ext_impl(id,ctx):",
        "  data_str = id + ': ' + str(ctx.root_module_has_non_dev_dependency)",
        "  data_repo(name='ext_repo',data=data_str)",
        "ext1=module_extension(implementation=lambda ctx: _ext_impl('ext1', ctx))",
        "ext2=module_extension(implementation=lambda ctx: _ext_impl('ext2', ctx))",
        "ext3=module_extension(implementation=lambda ctx: _ext_impl('ext3', ctx))");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("ext1_data")).isEqualTo("ext1: True");
    assertThat(result.get(skyKey).getModule().getGlobal("ext2_data")).isEqualTo("ext2: False");
    assertThat(result.get(skyKey).getModule().getGlobal("ext3_data")).isEqualTo("ext3: True");
  }

  @Test
  public void printAndFailOnTag() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "ext = use_extension('//:defs.bzl', 'ext')",
        "ext.foo()",
        "ext.foo()");
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        "repo = repository_rule(lambda ctx: True)",
        "def _ext_impl(ctx):",
        "  tag1 = ctx.modules[0].tags.foo[0]",
        "  tag2 = ctx.modules[0].tags.foo[1]",
        "  print('Conflict between', tag1, 'and', tag2)",
        "  fail('Fatal conflict between', tag1, 'and', tag2)",
        "foo = tag_class()",
        "ext = module_extension(implementation=_ext_impl,tag_classes={'foo':foo})");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());

    ModuleExtensionId extensionId =
        ModuleExtensionId.create(Label.parseCanonical("//:defs.bzl"), "ext", Optional.empty());
    reporter.removeHandler(failFastHandler);
    var result =
        evaluator.<SingleExtensionValue>evaluate(
            ImmutableList.of(SingleExtensionValue.key(extensionId)), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Fatal conflict between 'foo' tag at /ws/MODULE.bazel:2:8 and 'foo' tag at "
            + "/ws/MODULE.bazel:3:8",
        ImmutableSet.of(EventKind.ERROR));
    assertContainsEvent(
        "Conflict between 'foo' tag at /ws/MODULE.bazel:2:8 and 'foo' tag at /ws/MODULE.bazel:3:8",
        ImmutableSet.of(EventKind.DEBUG));
  }

  @Test
  public void innate() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "data_repo = use_repo_rule('@foo//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data='get up at 6am.')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@data//:data.bzl', self_data='data')",
        "load('@foo//:data.bzl', foo_data='data')",
        "data=self_data+' '+foo_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "data_repo = use_repo_rule('//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data='go to bed at 11pm.')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/data.bzl").getPathString(),
        "load('@data//:data.bzl',repo_data='data')",
        "data=repo_data");
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/repo.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('BUILD.bazel')",
        "  ctx.file('data.bzl', 'data='+json.encode(ctx.attr.data))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'data':attr.string()})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat(result.get(skyKey).getModule().getGlobal("data"))
        .isEqualTo("get up at 6am. go to bed at 11pm.");
  }

  @Test
  public void innate_noSuchRepoRule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "data_repo = use_repo_rule('//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data='get up at 6am.')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@data//:data.bzl', self_data='data')",
        "data=self_data");
    scratch.file(
        workspaceRoot.getRelative("repo.bzl").getPathString(),
        "# not a repo rule",
        "def data_repo(name):",
        "    pass");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "//:repo.bzl exports a value called data_repo of type function, yet a repository_rule"
                + " is requested at /ws/MODULE.bazel");
  }

  @Test
  public void innate_noSuchValue() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "data_repo = use_repo_rule('//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data='get up at 6am.')");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@data//:data.bzl', self_data='data')",
        "data=self_data");
    scratch.file(workspaceRoot.getRelative("repo.bzl").getPathString(), "");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains(
            "//:repo.bzl does not export a repository_rule called data_repo, yet its use is"
                + " requested at /ws/MODULE.bazel");
  }

  @Test
  public void innate_invalidAttributeValue() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name='foo',version='1.0')",
        "data_repo = use_repo_rule('@foo//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data=5)");
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        "load('@data//:data.bzl', self_data='data')",
        "load('@foo//:data.bzl', foo_data='data')",
        "data=self_data+' '+foo_data");

    registry.addModule(
        createModuleKey("foo", "1.0"),
        "module(name='foo',version='1.0')",
        "data_repo = use_repo_rule('//:repo.bzl', 'data_repo')",
        "data_repo(name='data', data='go to bed at 11pm.')");
    scratch.file(modulesRoot.getRelative("foo~v1.0/WORKSPACE").getPathString());
    scratch.file(modulesRoot.getRelative("foo~v1.0/BUILD").getPathString());
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/data.bzl").getPathString(),
        "load('@data//:data.bzl',repo_data='data')",
        "data=repo_data");
    scratch.file(
        modulesRoot.getRelative("foo~v1.0/repo.bzl").getPathString(),
        "def _data_repo_impl(ctx):",
        "  ctx.file('BUILD.bazel')",
        "  ctx.file('data.bzl', 'data='+json.encode(ctx.attr.data))",
        "data_repo = repository_rule(",
        "  implementation=_data_repo_impl, attrs={'data':attr.string()})");

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "ERROR /ws/MODULE.bazel:3:10: //:_main~_repo_rules~data: expected value"
            + " of type 'string' for attribute 'data' in 'data_repo' rule, but got 5 (int)");
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "error creating repo data requested at /ws/MODULE.bazel:3:10: failed to instantiate"
                + " 'data_repo' from this module extension");
  }

  @Test
  public void extensionRepoMapping() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        """
        bazel_dep(name = "data_repo", version = "1.0")
        ext = use_extension("//:defs.bzl","ext")
        use_repo(ext, real_foo = "foo", real_bar = "bar")
        other_ext = use_extension("//:defs.bzl", "other_ext")
        use_repo(other_ext, foo = "other_foo", bar = "other_bar")
        """);
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        """
        load("@real_foo//:list.bzl", _foo_list = "list")
        load("@real_bar//:list.bzl", _bar_list = "list")
        foo_list = _foo_list
        bar_list = _bar_list
        """);
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        """
        load("@data_repo//:defs.bzl", "data_repo")
        def _list_repo_impl(ctx):
          ctx.file("WORKSPACE")
          ctx.file("BUILD")
          labels = [str(Label(l)) for l in ctx.attr.labels]
          names = [str(Label(n)) for n in ctx.attr.names]
          ctx.file("list.bzl", "list = " + repr(labels + names))
        list_repo = repository_rule(
          implementation = _list_repo_impl,
          attrs = {
            "names": attr.string_list(),
            "labels": attr.label_list(),
          },
        )
        def _ext_impl(ctx):
          labels = [
            "@foo//:target1",
            "@bar//:target2",
            Label("@foo//:target3"),
            Label("@bar//:target4"),
          ]
          list_repo(
            name = "foo",
            labels = labels,
            names = [
              "@foo",
              "@bar",
            ],
          )

          # Modify the list passed to "foo" to verify that it is not retained by
          # reference.
          labels[0] = "@foo//:target5"
          labels[1] = "@bar//:target6"
          labels[2] = Label("@foo//:target7")
          labels[3] = Label("@bar//:target8")
          list_repo(
            name = "bar",
            labels = labels,
            names = [
              "@foo",
              "@bar",
            ],
          )
        ext = module_extension(implementation = _ext_impl)
        def _other_ext_impl(ctx):
          data_repo(name="other_foo",data="other_foo_data")
          data_repo(name="other_bar",data="other_bar_data")
        other_ext=module_extension(implementation=_other_ext_impl)
        """);

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat((List<?>) result.get(skyKey).getModule().getGlobal("foo_list"))
        .containsExactly(
            "@@_main~ext~foo//:target1",
            "@@_main~ext~bar//:target2",
            "@@_main~other_ext~other_foo//:target3",
            "@@_main~other_ext~other_bar//:target4",
            "@@_main~other_ext~other_foo//:foo",
            "@@_main~other_ext~other_bar//:bar")
        .inOrder();
    assertThat((List<?>) result.get(skyKey).getModule().getGlobal("bar_list"))
        .containsExactly(
            "@@_main~ext~foo//:target5",
            "@@_main~ext~bar//:target6",
            "@@_main~other_ext~other_foo//:target7",
            "@@_main~other_ext~other_bar//:target8",
            "@@_main~other_ext~other_foo//:foo",
            "@@_main~other_ext~other_bar//:bar")
        .inOrder();
  }

  @Test
  public void overrideRepo_override() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        """
        bazel_dep(name = "data_repo", version = "1.0")
        ext = use_extension("//:defs.bzl","ext")
        use_repo(ext, "bar", module_foo = "foo")
        data_repo = use_repo_rule("@data_repo//:defs.bzl", "data_repo")
        data_repo(name = "override", data = "overridden_data")
        override_repo(ext, foo = "override")
        """);
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        """
        load("@bar//:list.bzl", _bar_list = "list")
        load("@override//:data.bzl", _override_data = "data")
        load("@module_foo//:data.bzl", _foo_data = "data")
        bar_list = _bar_list
        foo_data = _foo_data
        override_data = _override_data
        """);
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        """
        load("@data_repo//:defs.bzl", "data_repo")
        def _list_repo_impl(ctx):
          ctx.file("WORKSPACE")
          ctx.file("BUILD")
          labels = [str(Label(l)) for l in ctx.attr.labels]
          labels += [str(Label("@module_foo//:target3"))]
          ctx.file("list.bzl", "list = " + repr(labels) + " + [str(Label('@foo//:target4'))]")
        list_repo = repository_rule(
          implementation = _list_repo_impl,
          attrs = {
            "labels": attr.label_list(),
          },
        )
        def _fail_repo_impl(ctx):
          fail("This rule should not be evaluated")
        fail_repo = repository_rule(implementation = _fail_repo_impl)
        def _ext_impl(ctx):
          fail_repo(name = "foo")
          list_repo(
            name = "bar",
            labels = [
              # lazy extension implementation function repository mapping
              "@foo//:target1",
              # module repo repository mapping
              "@module_foo//:target2",
            ],
          )
        ext = module_extension(implementation = _ext_impl)
        """);

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat((List<?>) result.get(skyKey).getModule().getGlobal("bar_list"))
        .containsExactly(
            "@@_main~_repo_rules~override//:target1",
            "@@_main~_repo_rules~override//:target2",
            "@@_main~_repo_rules~override//:target3",
            "@@_main~_repo_rules~override//:target4")
        .inOrder();
    Object overrideData = result.get(skyKey).getModule().getGlobal("override_data");
    assertThat(overrideData).isInstanceOf(String.class);
    assertThat(overrideData).isEqualTo("overridden_data");
    Object fooData = result.get(skyKey).getModule().getGlobal("foo_data");
    assertThat(fooData).isSameInstanceAs(overrideData);
  }

  @Test
  public void overrideRepo_override_onNonExistentRepoFails() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        """
        bazel_dep(name = "data_repo", version = "1.0")
        ext = use_extension("//:defs.bzl","ext")
        use_repo(ext, "bar", module_foo = "foo")
        data_repo = use_repo_rule("@data_repo//:defs.bzl", "data_repo")
        data_repo(name = "foo", data = "overridden_data")
        override_repo(ext, "foo")
        """);
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        """
        load("@bar//:list.bzl", _bar_list = "list")
        load("@foo//:data.bzl", _foo_data = "data")
        bar_list = _bar_list
        foo_data = _foo_data
        """);
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        """
        load("@data_repo//:defs.bzl", "data_repo")
        def _list_repo_impl(ctx):
          ctx.file("WORKSPACE")
          ctx.file("BUILD")
          labels = [str(Label(l)) for l in ctx.attr.labels]
          labels += [str(Label("@foo//:target3"))]
          ctx.file("list.bzl", "list = " + repr(labels) + " + [str(Label('@foo//:target4'))]")
        list_repo = repository_rule(
          implementation = _list_repo_impl,
          attrs = {
            "labels": attr.label_list(),
          },
        )
        def _ext_impl(ctx):
          list_repo(
            name = "bar",
            labels = [
              # lazy extension implementation function repository mapping
              "@foo//:target1",
              # module repo repository mapping
              Label("@foo//:target2"),
            ],
          )
        ext = module_extension(implementation = _ext_impl)
        """);

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    reporter.removeHandler(failFastHandler);
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .isEqualTo(
            "module extension \"ext\" from \"//:defs.bzl\" does not generate repository \"foo\","
                + " yet it is overridden via override_repo() at /ws/MODULE.bazel:6:14. Use"
                + " inject_repo() instead to inject a new repository.");
  }

  @Test
  public void overrideRepo_inject() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        """
        bazel_dep(name = "data_repo", version = "1.0")
        ext = use_extension("//:defs.bzl","ext")
        use_repo(ext, "bar")
        data_repo = use_repo_rule("@data_repo//:defs.bzl", "data_repo")
        data_repo(name = "foo", data = "overridden_data")
        inject_repo(ext, "foo")
        """);
    scratch.file(workspaceRoot.getRelative("BUILD").getPathString());
    scratch.file(
        workspaceRoot.getRelative("data.bzl").getPathString(),
        """
        load("@bar//:list.bzl", _bar_list = "list")
        load("@foo//:data.bzl", _foo_data = "data")
        bar_list = _bar_list
        foo_data = _foo_data
        """);
    scratch.file(
        workspaceRoot.getRelative("defs.bzl").getPathString(),
        """
        load("@data_repo//:defs.bzl", "data_repo")
        def _list_repo_impl(ctx):
          ctx.file("WORKSPACE")
          ctx.file("BUILD")
          labels = [str(Label(l)) for l in ctx.attr.labels]
          labels += [str(Label("@foo//:target3"))]
          ctx.file("list.bzl", "list = " + repr(labels) + " + [str(Label('@foo//:target4'))]")
        list_repo = repository_rule(
          implementation = _list_repo_impl,
          attrs = {
            "labels": attr.label_list(),
          },
        )
        def _ext_impl(ctx):
          list_repo(
            name = "bar",
            labels = [
              # lazy extension implementation function repository mapping
              "@foo//:target1",
              # module repo repository mapping
              Label("@foo//:target2"),
            ],
          )
        ext = module_extension(implementation = _ext_impl)
        """);

    SkyKey skyKey = BzlLoadValue.keyForBuild(Label.parseCanonical("//:data.bzl"));
    EvaluationResult<BzlLoadValue> result =
        evaluator.evaluate(ImmutableList.of(skyKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThat((List<?>) result.get(skyKey).getModule().getGlobal("bar_list"))
        .containsExactly(
            "@@_main~_repo_rules~foo//:target1",
            "@@_main~_repo_rules~foo//:target2",
            "@@_main~_repo_rules~foo//:target3",
            "@@_main~_repo_rules~foo//:target4")
        .inOrder();
    Object fooData = result.get(skyKey).getModule().getGlobal("foo_data");
    assertThat(fooData).isInstanceOf(String.class);
    assertThat(fooData).isEqualTo("overridden_data");
  }
}
