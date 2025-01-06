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
import static org.junit.Assert.fail;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BzlmodRepoRuleFunction}. */
@RunWith(JUnit4.class)
public final class BzlmodRepoRuleFunctionTest extends FoundationTestCase {

  private Path workspaceRoot;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
    workspaceRoot = scratch.dir("/ws");
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

    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));
    ConfiguredRuleClassProvider ruleClassProvider = builder.build();

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
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
                .put(SkyFunctions.BAZEL_DEP_GRAPH, new BazelDepGraphFunction())
                .put(SkyFunctions.BAZEL_LOCK_FILE, new BazelLockFileFunction(rootDirectory, directories.getOutputBase()))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(
                        ruleClassProvider.getBazelStarlarkEnvironment(),
                        workspaceRoot,
                        ImmutableMap.of()))
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
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.INJECTED_REPOSITORIES.set(differencer, ImmutableMap.of());
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
    BazelLockFileFunction.LOCKFILE_MODE.set(differencer, LockfileMode.UPDATE);
    RepositoryDelegatorFunction.VENDOR_DIRECTORY.set(differencer, Optional.empty());
  }

  @Test
  public void testRepoSpec_bazelModule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        evaluator.evaluate(ImmutableList.of(BzlmodRepoRuleValue.key(repo)), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClassObject().isStarlark()).isFalse();
    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/usr/local/modules/ccc+2.0");
  }

  @Test
  public void testRepoSpec_nonRegistryOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "local_path_override(module_name='ccc',path='/foo/bar/C')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        evaluator.evaluate(ImmutableList.of(BzlmodRepoRuleValue.key(repo)), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClassObject().isStarlark()).isFalse();
    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/foo/bar/C");
  }

  @Test
  public void testRepoSpec_singleVersionOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "single_version_override(",
        "  module_name='ccc',version='3.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
            .addModule(createModuleKey("ccc", "3.0"), "module(name='ccc', version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        evaluator.evaluate(ImmutableList.of(BzlmodRepoRuleValue.key(repo)), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClassObject().isStarlark()).isFalse();
    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/usr/local/modules/ccc+3.0");
  }

  @Test
  public void testRepoSpec_multipleVersionOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='1.0')")
            .addModule(
                createModuleKey("ccc", "2.0"),
                "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='2.0')")
            .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
            .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableSet.of(registry.getUrl()));

    RepositoryName repo = RepositoryName.create("ddd+2.0");
    EvaluationResult<BzlmodRepoRuleValue> result =
        evaluator.evaluate(ImmutableList.of(BzlmodRepoRuleValue.key(repo)), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClassObject().isStarlark()).isFalse();
    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ddd+2.0");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/usr/local/modules/ddd+2.0");
  }

  @Test
  public void testRepoSpec_notFound() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')");

    RepositoryName repo = RepositoryName.create("ss");
    EvaluationResult<BzlmodRepoRuleValue> result =
        evaluator.evaluate(ImmutableList.of(BzlmodRepoRuleValue.key(repo)), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    assertThat(bzlmodRepoRuleValue).isEqualTo(BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE);
  }
}
