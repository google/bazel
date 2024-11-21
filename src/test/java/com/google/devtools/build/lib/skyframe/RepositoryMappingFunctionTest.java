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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.LocalPathRepoSpecs;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepositoryMappingFunction} and {@link RepositoryMappingValue}. */
@RunWith(JUnit4.class)
public class RepositoryMappingFunctionTest extends BuildViewTestCase {

  private EvaluationResult<RepositoryMappingValue> eval(SkyKey key)
      throws InterruptedException, AbruptExitException {
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder()
                .modify(PathFragment.create("WORKSPACE"))
                .modify(PathFragment.create("MODULE.bazel"))
                .build(),
            Root.fromPath(rootDirectory));
    return SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(), key, /* keepGoing= */ false, reporter);
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    // Make sure we have minimal built-in modules affecting the dependency graph.
    return new AnalysisMock.Delegate(AnalysisMock.get()) {
      @Override
      public ImmutableMap<String, NonRegistryOverride> getBuiltinModules(
          BlazeDirectories directories) {
        if (!isThisBazel()) {
          return ImmutableMap.of();
        }
        return ImmutableMap.of(
            "bazel_tools",
            new NonRegistryOverride(
                LocalPathRepoSpecs.create(
                    directories
                        .getWorkingDirectory()
                        .getRelative("embedded_tools")
                        .getPathString())),
            "platforms",
            new NonRegistryOverride(
                LocalPathRepoSpecs.create(
                    directories
                        .getWorkingDirectory()
                        .getRelative("platforms_workspace")
                        .getPathString())));
      }
    };
  }

  private static RepositoryMappingValue valueForWorkspace(
      ImmutableMap<String, RepositoryName> repositoryMapping) throws Exception {
    ImmutableMap.Builder<String, RepositoryName> allMappings = ImmutableMap.builder();
    allMappings.putAll(repositoryMapping);
    if (AnalysisMock.get().isThisBazel()) {
      allMappings
          .put("bazel_tools", RepositoryName.create("bazel_tools"))
          .put("platforms", RepositoryName.create("platforms"));
    }
    return RepositoryMappingValue.createForWorkspaceRepo(
        RepositoryMapping.createAllowingFallback(allMappings.buildOrThrow()));
  }

  private static RepositoryMappingValue valueForBzlmod(
      ImmutableMap<String, RepositoryName> repositoryMapping,
      RepositoryName ownerRepo,
      String associatedModuleName,
      String associatedModuleVersion)
      throws Exception {
    ImmutableMap.Builder<String, RepositoryName> allMappings = ImmutableMap.builder();
    allMappings.putAll(repositoryMapping);
    if (AnalysisMock.get().isThisBazel()) {
      allMappings
          .put("bazel_tools", RepositoryName.create("bazel_tools"))
          .put("platforms", RepositoryName.create("platforms"));
    }
    return RepositoryMappingValue.createForBzlmodRepo(
        RepositoryMapping.create(allMappings.buildOrThrow(), ownerRepo),
        associatedModuleName,
        Version.parse(associatedModuleVersion));
  }

  private RepositoryMappingValue valueForRootModule(
      ImmutableMap<String, RepositoryName> repositoryMapping,
      String rootModuleName,
      String rootModuleVersion)
      throws Exception {
    return valueForRootModule(repositoryMapping, rootModuleName, rootModuleVersion, false);
  }

  private RepositoryMappingValue valueForRootModule(
      ImmutableMap<String, RepositoryName> repositoryMapping,
      String rootModuleName,
      String rootModuleVersion,
      boolean needWorkspaceRepos)
      throws Exception {
    ImmutableMap.Builder<String, RepositoryName> allMappings = ImmutableMap.builder();
    allMappings.putAll(repositoryMapping);
    if (needWorkspaceRepos) {
      for (String name : analysisMock.getWorkspaceRepos()) {
        allMappings.put(name, RepositoryName.createUnvalidated(name));
      }
    }
    return valueForBzlmod(
        allMappings.buildOrThrow(), RepositoryName.MAIN, rootModuleName, rootModuleVersion);
  }

  @Test
  public void testSimpleMapping() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    rewriteWorkspace(
        """
        workspace(name = "good")
        local_repository(
            name = "a_remote_repo",
            path = "/a_remote_repo",
            repo_mapping = {"@a": "@b"},
        )
        """);
    RepositoryName name = RepositoryName.create("a_remote_repo");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    assertThat(result.hasError()).isFalse();
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.of(
                    "a",
                    RepositoryName.create("b"),
                    "good",
                    RepositoryName.MAIN,
                    "",
                    RepositoryName.MAIN)));
  }

  @Test
  public void testRepoNameMapping_asRootModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0', repo_name = 'com_foo_bar_b')");
    registry.addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')");

    SkyKey skyKey = RepositoryMappingValue.key(RepositoryName.MAIN);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    assertThat(result.hasError()).isFalse();
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForRootModule(
                ImmutableMap.of(
                    "",
                    RepositoryName.MAIN,
                    "aaa",
                    RepositoryName.MAIN,
                    "com_foo_bar_b",
                    RepositoryName.create("bbb+")),
                "aaa",
                "0.1"));
  }

  @Test
  public void testRepoNameMapping_asRootModule_withOwnRepoName() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1',repo_name='haha')",
        "bazel_dep(name='bbb',version='1.0', repo_name = 'com_foo_bar_b')");
    registry.addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')");

    SkyKey skyKey = RepositoryMappingValue.key(RepositoryName.MAIN);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    assertThat(result.hasError()).isFalse();
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForRootModule(
                ImmutableMap.of(
                    "",
                    RepositoryName.MAIN,
                    "haha",
                    RepositoryName.MAIN,
                    "com_foo_bar_b",
                    RepositoryName.create("bbb+")),
                "aaa",
                "0.1"));
  }

  @Test
  public void testRepoNameMapping_asDependency() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='1.0', repo_name = 'com_foo_bar_c')");
    registry
        .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
        .addModule(
            createModuleKey("ccc", "1.0"),
            "module(name='ccc', version='1.0')",
            "bazel_dep(name='bbb', version='1.0', repo_name='com_foo_bar_b')");

    RepositoryName name = RepositoryName.create("ccc+");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    assertThat(result.hasError()).isFalse();
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForBzlmod(
                ImmutableMap.of(
                    "ccc", RepositoryName.create("ccc+"),
                    "com_foo_bar_b", RepositoryName.create("bbb+")),
                name,
                "ccc",
                "1.0"));
  }

  @Test
  public void testRepoNameMapping_dependencyOnRootModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')");
    registry.addModule(
        createModuleKey("bbb", "1.0"),
        "module(name='bbb', version='1.0')",
        "bazel_dep(name='aaa',version='3.0')");

    RepositoryName name = RepositoryName.create("bbb+");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    assertThat(result.hasError()).isFalse();
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForBzlmod(
                ImmutableMap.of(
                    "bbb", RepositoryName.create("bbb+"), "aaa", RepositoryName.create("")),
                name,
                "bbb",
                "1.0"));
  }

  @Test
  public void testRepoNameMapping_multipleVersionOverride_fork() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0',repo_name='bbb1')",
        "bazel_dep(name='bbb',version='2.0',repo_name='bbb2')",
        "multiple_version_override(module_name='bbb',versions=['1.0','2.0'])");
    registry
        .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
        .addModule(createModuleKey("bbb", "2.0"), "module(name='bbb', version='2.0')");

    SkyKey skyKey = RepositoryMappingValue.key(RepositoryName.MAIN);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    if (result.hasError()) {
      fail(result.getError().toString());
    }
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForRootModule(
                ImmutableMap.of(
                    "",
                    RepositoryName.MAIN,
                    "aaa",
                    RepositoryName.MAIN,
                    "bbb1",
                    RepositoryName.create("bbb+1.0"),
                    "bbb2",
                    RepositoryName.create("bbb+2.0")),
                "aaa",
                "0.1"));
  }

  @Test
  public void testRepoNameMapping_multipleVersionOverride_diamond() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ddd', version='1.0')")
        .addModule(
            createModuleKey("ccc", "2.0"),
            "module(name='ccc', version='2.0');bazel_dep(name='ddd', version='2.0')")
        .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
        .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')");

    RepositoryName name = RepositoryName.create("bbb+");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    if (result.hasError()) {
      fail(result.getError().toString());
    }
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForBzlmod(
                ImmutableMap.of(
                    "bbb", RepositoryName.create("bbb+"),
                    "ddd", RepositoryName.create("ddd+1.0")),
                name,
                "bbb",
                "1.0"));
  }

  @Test
  public void testRepoNameMapping_multipleVersionOverride_lookup() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0',repo_name='bbb1')",
        "bazel_dep(name='bbb',version='2.0',repo_name='bbb2')",
        "multiple_version_override(module_name='bbb',versions=['1.0','2.0'])");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');"
                + "bazel_dep(name='ccc', version='1.0', repo_name='com_foo_bar_c')")
        .addModule(createModuleKey("bbb", "2.0"), "module(name='bbb', version='2.0')")
        .addModule(createModuleKey("ccc", "1.0"), "module(name='ccc', version='1.0')");

    RepositoryName name = RepositoryName.create("bbb+1.0");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    EvaluationResult<RepositoryMappingValue> result = eval(skyKey);

    if (result.hasError()) {
      fail(result.getError().toString());
    }
    assertThatEvaluationResult(result)
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForBzlmod(
                ImmutableMap.of(
                    "bbb", RepositoryName.create("bbb+1.0"),
                    "com_foo_bar_c", RepositoryName.create("ccc+")),
                name,
                "bbb",
                "1.0"));
  }

  @Test
  public void testMultipleRepositoriesWithMapping() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    rewriteWorkspace(
        """
        workspace(name = "good")
        local_repository(
            name = "a_remote_repo",
            path = "/a_remote_repo",
            repo_mapping = {"@a": "@b"},
        )
        local_repository(
            name = "other_remote_repo",
            path = "/other_remote_repo",
            repo_mapping = {"@x": "@y"},
        )
        """);
    RepositoryName name1 = RepositoryName.create("a_remote_repo");
    SkyKey skyKey1 = RepositoryMappingValue.key(name1);
    RepositoryName name2 = RepositoryName.create("other_remote_repo");
    SkyKey skyKey2 = RepositoryMappingValue.key(name2);

    assertThatEvaluationResult(eval(skyKey1))
        .hasEntryThat(skyKey1)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.of(
                    "a",
                    RepositoryName.create("b"),
                    "good",
                    RepositoryName.MAIN,
                    "",
                    RepositoryName.MAIN)));
    assertThatEvaluationResult(eval(skyKey2))
        .hasEntryThat(skyKey2)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.of(
                    "x",
                    RepositoryName.create("y"),
                    "good",
                    RepositoryName.MAIN,
                    "",
                    RepositoryName.MAIN)));
  }

  @Test
  public void testRepositoryWithMultipleMappings() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    rewriteWorkspace(
        """
        workspace(name = "good")
        local_repository(
            name = "a_remote_repo",
            path = "/a_remote_repo",
            repo_mapping = {"@a": "@b", "@x": "@y"},
        )
        """);
    RepositoryName name = RepositoryName.create("a_remote_repo");
    SkyKey skyKey = RepositoryMappingValue.key(name);

    assertThatEvaluationResult(eval(skyKey))
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.of(
                    "a",
                    RepositoryName.create("b"),
                    "x",
                    RepositoryName.create("y"),
                    "good",
                    RepositoryName.MAIN,
                    "",
                    RepositoryName.MAIN)));
  }

  @Test
  public void testMixtureOfBothSystems_workspaceRepo() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "ext=use_extension('@ccc//:ext.bzl', 'ext')",
        "use_repo(ext, 'ddd')");
    registry
        .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");

    // Called last as it triggers package invalidation, which requires a valid MODULE.bazel setup.
    rewriteWorkspace(
        """
        workspace(name = "root")
        local_repository(
            name = "ws_repo",
            path = "/ws_repo",
            repo_mapping = {
                "@bbb_alias": "@bbb",
                "@bbb_alias2": "@bbb",
                "@ddd_alias": "@ddd",
                "@eee_alias": "@eee",
            },
        )
        """);

    RepositoryName name = RepositoryName.create("ws_repo");
    SkyKey skyKey = RepositoryMappingValue.key(name);
    assertThatEvaluationResult(eval(skyKey))
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.<String, RepositoryName>builder()
                    .put("", RepositoryName.MAIN)
                    .put("aaa", RepositoryName.MAIN)
                    .put("root", RepositoryName.MAIN)
                    // mappings to @bbb get remapped to @@bbb+ because of root dep on bbb@1.0
                    .put("bbb_alias", RepositoryName.create("bbb+"))
                    .put("bbb_alias2", RepositoryName.create("bbb+"))
                    // mapping from @bbb to @@bbb+ is also created
                    .put("bbb", RepositoryName.create("bbb+"))
                    // mapping from @ccc to @@ccc+ is created despite not being mentioned
                    .put("ccc", RepositoryName.create("ccc+"))
                    // mapping to @ddd gets remapped to a module-extension-generated repo
                    .put("ddd_alias", RepositoryName.create("ccc++ext+ddd"))
                    .put("ddd", RepositoryName.create("ccc++ext+ddd"))
                    // mapping to @eee is untouched because the root module doesn't know about it
                    .put("eee_alias", RepositoryName.create("eee"))
                    .buildOrThrow()));
  }

  @Test
  public void testMixtureOfBothSystems_mainRepo() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc', version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");

    // Called last as it triggers package invalidation, which requires a valid MODULE.bazel setup.
    rewriteWorkspace(
        """
        workspace(name = "root")
        local_repository(
            name = "ws_repo",
            path = "/ws_repo",
        )
        """);

    SkyKey skyKey = RepositoryMappingValue.key(RepositoryName.MAIN);
    assertThatEvaluationResult(eval(skyKey))
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForRootModule(
                ImmutableMap.of(
                    "", RepositoryName.MAIN,
                    "aaa", RepositoryName.MAIN,
                    "bbb", RepositoryName.create("bbb+"),
                    "root", RepositoryName.MAIN,
                    "ws_repo", RepositoryName.create("ws_repo")),
                "aaa",
                "0.1",
                /* needWorkspaceRepos= */ true));
  }

  @Test
  public void testMixtureOfBothSystems_mainRepo_shouldNotSeeWorkspaceRepos() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc', version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");

    // Called last as it triggers package invalidation, which requires a valid MODULE.bazel setup.
    rewriteWorkspace(
        """
        workspace(name = "root")
        local_repository(
            name = "ws_repo",
            path = "/ws_repo",
        )
        """);

    SkyKey skyKey = RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS;
    assertThatEvaluationResult(eval(skyKey))
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForBzlmod(
                ImmutableMap.of(
                    "", RepositoryName.MAIN,
                    "aaa", RepositoryName.MAIN,
                    "bbb", RepositoryName.create("bbb+")),
                RepositoryName.MAIN,
                "aaa",
                "0.1"));
  }

  @Test
  public void testErrorWithMapping() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    reporter.removeHandler(failFastHandler);
    scratch.overwriteFile(
        "WORKSPACE",
        "workspace(name = 'good')",
        "local_repository(",
        "    name = 'a_remote_repo',",
        "    path = '/a_remote_repo',",
        "    repo_mapping = {'x' : '@b'},",
        ")");
    RepositoryName name = RepositoryName.create("a_remote_repo");
    SkyKey skyKey = RepositoryMappingValue.key(name);

    assertThatEvaluationResult(eval(skyKey))
        .hasErrorEntryForKeyThat(skyKey)
        .hasExceptionThat()
        .isInstanceOf(NoSuchPackageException.class);
    assertContainsEvent(
        "invalid repository name 'x': repo names used in the repo_mapping attribute must start with"
            + " '@'");
  }

  @Test
  public void testExplicitMainRepoNameInMapping() throws Exception {
    setBuildLanguageOptions("--enable_workspace");
    scratch.overwriteFile(
        "WORKSPACE",
        "workspace(name = 'good')",
        "local_repository(",
        "    name = 'a_remote_repo',",
        "    path = '/a_remote_repo',",
        ")");
    RepositoryName name = RepositoryName.create("a_remote_repo");
    SkyKey skyKey = RepositoryMappingValue.key(name);

    assertThatEvaluationResult(eval(skyKey))
        .hasEntryThat(skyKey)
        .isEqualTo(
            valueForWorkspace(
                ImmutableMap.of("good", RepositoryName.MAIN, "", RepositoryName.MAIN)));
  }

  @Test
  public void builtinsRepo() throws Exception {
    SkyKey builtinsKey = RepositoryMappingValue.key(RepositoryName.create("_builtins"));
    SkyKey toolsKey =
        RepositoryMappingValue.Key.create(ruleClassProvider.getToolsRepository(), false);
    EvaluationResult<RepositoryMappingValue> builtinsResult = eval(builtinsKey);
    assertThat(builtinsResult.hasError()).isFalse();
    RepositoryMapping builtinsMapping = builtinsResult.get(builtinsKey).repositoryMapping();
    EvaluationResult<RepositoryMappingValue> toolsResult = eval(toolsKey);
    assertThat(toolsResult.hasError()).isFalse();
    RepositoryMapping toolsMapping = toolsResult.get(toolsKey).repositoryMapping();

    assertThat(builtinsMapping.entries()).containsAtLeastEntriesIn(toolsMapping.entries());
    assertThat(builtinsMapping.get("_builtins")).isEqualTo(RepositoryName.create("_builtins"));
    assertThat(builtinsMapping.get("")).isEqualTo(RepositoryName.MAIN);
  }

  @Test
  public void testEqualsAndHashCode() throws Exception {
    new EqualsTester()
        .addEqualityGroup(
            valueForWorkspace(ImmutableMap.of("foo", RepositoryName.create("bar"))),
            valueForWorkspace(ImmutableMap.of("foo", RepositoryName.create("bar"))))
        .addEqualityGroup(
            valueForWorkspace(ImmutableMap.of("fizz", RepositoryName.create("buzz"))),
            valueForWorkspace(ImmutableMap.of("fizz", RepositoryName.create("buzz"))))
        .testEquals();
  }
}
