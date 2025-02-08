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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryDirtinessChecker;
import com.google.devtools.build.lib.skyframe.SkyframeExecutorRepositoryHelpersHolder;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map.Entry;
import net.starlark.java.eval.EvalException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that the repo mapping manifest file is properly generated for runfiles. */
@RunWith(JUnit4.class)
public class RunfilesRepoMappingManifestTest extends BuildViewTestCase {

  @Override
  protected SkyframeExecutorRepositoryHelpersHolder getRepositoryHelpersHolder() {
    // Transitive packages are needed for RepoMappingManifestAction and are only stored when
    // external repositories are enabled.
    return SkyframeExecutorRepositoryHelpersHolder.create(
        new RepositoryDirectoryDirtinessChecker());
  }

  /**
   * Sets up a Bazel module bare_rule@1.0, which provides a bare_binary rule that passes along
   * runfiles in the data attribute, and does nothing else.
   */
  @Before
  public void setupBareBinaryRule() throws Exception {
    registry.addModule(
        createModuleKey("bare_rule", "1.0"), "module(name='bare_rule',version='1.0')");
    scratch.overwriteFile(moduleRoot.getRelative("bare_rule+1.0/REPO.bazel").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("bare_rule+1.0/defs.bzl").getPathString(),
        "def _bare_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, 'i got nothing', True)",
        "  runfiles = ctx.runfiles(files=ctx.files.data)",
        "  for data in ctx.attr.data:",
        "    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)",
        "bare_binary=rule(",
        "  implementation=_bare_binary_impl,",
        "  attrs={'data':attr.label_list(allow_files=True)},",
        "  executable=True,",
        ")");
    scratch.overwriteFile(
        moduleRoot.getRelative("bare_rule+1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'bare_binary')",
        "bare_binary(name='bare_binary')");
  }

  private RepoMappingManifestAction getRepoMappingManifestActionForTarget(String label)
      throws Exception {
    Action action = getGeneratingAction(getRunfilesSupport(label).getRepoMappingManifest());
    assertThat(action).isInstanceOf(RepoMappingManifestAction.class);
    return (RepoMappingManifestAction) action;
  }

  private String computeKey(RepoMappingManifestAction action)
      throws CommandLineExpansionException, EvalException, InterruptedException {
    Fingerprint fp = new Fingerprint();
    action.computeKey(actionKeyContext, /* artifactExpander= */ null, fp);
    return fp.hexDigestAndReset();
  }

  private ImmutableList<String> getRepoMappingManifestForTarget(String label) throws Exception {
    return getRepoMappingManifestActionForTarget(label)
        .newDeterministicWriter(null)
        .getBytes()
        .toStringUtf8()
        .lines()
        .collect(toImmutableList());
  }

  @Test
  public void diamond() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("bbb", "1.0"),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='ddd',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ccc", "2.0"),
        "module(name='ccc',version='2.0')",
        "bazel_dep(name='ddd',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "1.0"),
        "module(name='ddd',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "2.0"),
        "module(name='ddd',version='2.0')",
        "bazel_dep(name='bare_rule',version='1.0')");

    scratch.overwriteFile(
        "BUILD",
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "bare_binary(name='aaa',data=['@bbb'])");
    ImmutableMap<String, String> buildFiles =
        ImmutableMap.of(
            "bbb+1.0", "bare_binary(name='bbb',data=['@ddd'])",
            "ccc+2.0", "bare_binary(name='ccc',data=['@ddd'])",
            "ddd+1.0", "bare_binary(name='ddd')",
            "ddd+2.0", "bare_binary(name='ddd')");
    for (Entry<String, String> entry : buildFiles.entrySet()) {
      scratch.overwriteFile(
          moduleRoot.getRelative(entry.getKey()).getRelative("REPO.bazel").getPathString());
      scratch.overwriteFile(
          moduleRoot.getRelative(entry.getKey()).getRelative("BUILD").getPathString(),
          "load('@bare_rule//:defs.bzl', 'bare_binary')",
          entry.getValue());
    }

    // Called last as it triggers package invalidation, which requires a valid MODULE.bazel setup.
    invalidatePackages();

    assertThat(getRepoMappingManifestForTarget("//:aaa"))
        .containsExactly(
            ",aaa," + getRuleClassProvider().getRunfilesPrefix(),
            ",bbb,bbb+",
            "bbb+,bbb,bbb+",
            "bbb+,ddd,ddd+",
            "ddd+,ddd,ddd+")
        .inOrder();
    assertThat(getRepoMappingManifestForTarget("@@ccc+//:ccc"))
        .containsExactly("ccc+,ccc,ccc+", "ccc+,ddd,ddd+", "ddd+,ddd,ddd+")
        .inOrder();
  }

  @Test
  public void runfilesFromToolchain() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='tooled_rule',version='1.0')");
    // tooled_rule offers a tooled_binary rule, which uses a toolchain backed by a binary from
    // bare_rule. tooled_binary explicitly requests that runfiles from this binary are included in
    // its runfiles tree, which would mean that bare_rule should be included in the repo mapping
    // manifest.
    registry.addModule(
        createModuleKey("tooled_rule", "1.0"),
        "module(name='tooled_rule',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')",
        "register_toolchains('//:all')");
    scratch.overwriteFile(moduleRoot.getRelative("tooled_rule+1.0/REPO.bazel").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("tooled_rule+1.0/defs.bzl").getPathString(),
        "def _tooled_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, 'i got something', True)",
        "  runfiles = ctx.runfiles(files=ctx.files.data)",
        "  for data in ctx.attr.data:",
        "    runfiles = runfiles.merge(data[DefaultInfo].default_runfiles)",
        "  runfiles = runfiles.merge(",
        "      ctx.toolchains['//:toolchain_type'].tooled_info[DefaultInfo].default_runfiles)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)",
        "tooled_binary = rule(",
        "  implementation=_tooled_binary_impl,",
        "  attrs={'data':attr.label_list(allow_files=True)},",
        "  executable=True,",
        "  toolchains=['//:toolchain_type'],",
        ")",
        "",
        "def _tooled_toolchain_rule_impl(ctx):",
        "  return [platform_common.ToolchainInfo(tooled_info = ctx.attr.backing_binary)]",
        "tooled_toolchain_rule=rule(_tooled_toolchain_rule_impl,",
        "  attrs={'backing_binary':attr.label()})",
        "def tooled_toolchain(name, backing_binary):",
        "  tooled_toolchain_rule(name=name+'_impl',backing_binary=backing_binary)",
        "  native.toolchain(",
        "    name=name,",
        "    toolchain=':'+name+'_impl',",
        "    toolchain_type=Label('//:toolchain_type'),",
        "  )");
    scratch.overwriteFile(
        moduleRoot.getRelative("tooled_rule+1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'tooled_toolchain')",
        "toolchain_type(name='toolchain_type')",
        "tooled_toolchain(name='tooled_toolchain', backing_binary='@bare_rule//:bare_binary')");

    scratch.overwriteFile(
        "BUILD",
        "load('@tooled_rule//:defs.bzl', 'tooled_binary')",
        "tooled_binary(name='tooled')");

    // Called last as it triggers package invalidation, which requires a valid MODULE.bazel setup.
    invalidatePackages();

    assertThat(getRepoMappingManifestForTarget("//:tooled"))
        .containsExactly(
            "bare_rule+,bare_rule,bare_rule+",
            "tooled_rule+,bare_rule,bare_rule+")
        .inOrder();
  }

  @Test
  public void actionRerunsOnRepoMappingChange_repoName() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(
        "BUILD", "load('@bare_rule//:defs.bzl', 'bare_binary')", "bare_binary(name='aaa')");
    invalidatePackages();

    RepoMappingManifestAction actionBeforeChange = getRepoMappingManifestActionForTarget("//:aaa");

    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0',repo_name='not_aaa')",
        "bazel_dep(name='bare_rule',version='1.0')");
    invalidatePackages();

    RepoMappingManifestAction actionAfterChange = getRepoMappingManifestActionForTarget("//:aaa");
    assertThat(computeKey(actionBeforeChange)).isNotEqualTo(computeKey(actionAfterChange));
  }

  @Test
  public void actionRerunsOnRepoMappingChange_newEntry() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(
        "BUILD", "load('@bare_rule//:defs.bzl', 'bare_binary')", "bare_binary(name='aaa')");

    registry.addModule(
        createModuleKey("bbb", "1.0"),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(
        moduleRoot.getRelative("bbb+1.0").getRelative("REPO.bazel").getPathString());
    scratch.overwriteFile(moduleRoot.getRelative("bbb+1.0").getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("bbb+1.0").getRelative("def.bzl").getPathString(), "BBB = '1'");
    invalidatePackages();

    RepoMappingManifestAction actionBeforeChange = getRepoMappingManifestActionForTarget("//:aaa");

    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(
        "BUILD",
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "load('@bbb//:def.bzl', 'BBB')",
        "bare_binary(name='aaa')");
    invalidatePackages();

    RepoMappingManifestAction actionAfterChange = getRepoMappingManifestActionForTarget("//:aaa");
    assertThat(computeKey(actionBeforeChange)).isNotEqualTo(computeKey(actionAfterChange));
  }

  @Test
  public void hasMappingForSymlinks() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='my_module',version='1.0')",
        "bazel_dep(name='aaa',version='1.0')");

    registry.addModule(
        createModuleKey("aaa", "1.0"),
        "module(name='aaa',version='1.0')",
        "bazel_dep(name='my_module',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')",
        "bazel_dep(name='symlinks',version='1.0')");
    scratch.overwriteFile(moduleRoot.getRelative("aaa+1.0/REPO.bazel").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("aaa+1.0/BUILD").getPathString(),
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "bare_binary(name='aaa',data=['@symlinks'])");

    registry.addModule(
        createModuleKey("symlinks", "1.0"),
        "module(name='symlinks',version='1.0')",
        "bazel_dep(name='ddd',version='1.0')");
    scratch.overwriteFile(moduleRoot.getRelative("symlinks+1.0/REPO.bazel").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("symlinks+1.0/defs.bzl").getPathString(),
        "def _symlinks_impl(ctx):",
        "  runfiles = ctx.runfiles(",
        "    symlinks = {'path/to/pkg/symlink': ctx.file.data},",
        "    root_symlinks = {ctx.label.workspace_name + '/path/to/pkg/root_symlink':"
            + " ctx.file.data},",
        "  )",
        "  return DefaultInfo(runfiles=runfiles)",
        "symlinks = rule(",
        "  implementation=_symlinks_impl,",
        "  attrs={'data':attr.label(allow_single_file=True)},",
        ")");
    scratch.overwriteFile(
        moduleRoot.getRelative("symlinks+1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'symlinks')",
        "symlinks(name='symlinks',data='@ddd')");

    registry.addModule(
        createModuleKey("ddd", "1.0"),
        "module(name='ddd',version='1.0')",
        "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(moduleRoot.getRelative("ddd+1.0/REPO.bazel").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("ddd+1.0/BUILD").getPathString(),
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "bare_binary(name='ddd')");
    invalidatePackages();

    RunfilesSupport runfilesSupport = getRunfilesSupport("@aaa+//:aaa");
    ImmutableList<String> runfilesPaths =
        runfilesSupport
            .getRunfiles()
            .getRunfilesInputs(runfilesSupport.getRepoMappingManifest())
            .keySet()
            .stream()
            .map(PathFragment::getPathString)
            .collect(toImmutableList());
    assertThat(runfilesPaths)
        .containsAtLeast(
            "aaa+/aaa",
            getRuleClassProvider().getRunfilesPrefix() + "/path/to/pkg/symlink",
            "symlinks+/path/to/pkg/root_symlink",
            "_repo_mapping");

    assertThat(getRepoMappingManifestForTarget("@aaa+//:aaa"))
        .containsExactly(
            // @aaa+ contributes the top-level executable to runfiles.
            "aaa+,aaa,aaa+",
            // The symlink is staged under the main repository's runfiles directory and aaa has a
            // repo mapping entry for it.
            "aaa+,my_module," + getRuleClassProvider().getRunfilesPrefix(),
            // @symlinks+ appears as the first segment of a root symlink.
            "aaa+,symlinks,symlinks+",
            "symlinks+,symlinks,symlinks+")
        .inOrder();
  }

  @Test
  public void repoMappingOnFilesToRunProvider() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "bazel_dep(name='bare_rule',version='1.0')");
    scratch.overwriteFile(
        "defs.bzl",
        """
        def _get_repo_mapping_impl(ctx):
            files_to_run = ctx.attr.bin[DefaultInfo].files_to_run
            return [
                DefaultInfo(files = depset([files_to_run.repo_mapping_manifest])),
            ]

        get_repo_mapping = rule(
            implementation = _get_repo_mapping_impl,
            attrs = {"bin": attr.label(cfg = "target", executable = True)},
        )
        """);
    scratch.overwriteFile(
        "BUILD",
        "load('@bare_rule//:defs.bzl', 'bare_binary')",
        "load('//:defs.bzl', 'get_repo_mapping')",
        "bare_binary(name='aaa')",
        "get_repo_mapping(name='get_repo_mapping', bin=':aaa')");
    invalidatePackages();

    assertThat(getFilesToBuild(getConfiguredTarget("//:get_repo_mapping")).toList())
        .containsExactly(getRunfilesSupport("//:aaa").getRepoMappingManifest());
  }
}
