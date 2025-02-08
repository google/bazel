// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.util.AbruptExitException;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration test for Starlark repository not as heavyweight than shell integration tests. */
@RunWith(JUnit4.class)
public class StarlarkRepositoryIntegrationTest extends BuildViewTestCase {

  @Override
  protected void invalidatePackages() throws InterruptedException, AbruptExitException {
    // Repository shuffling breaks access to config-needed paths like //tools/jdk:toolchain and
    // these tests don't do anything interesting with configurations anyway. So exempt them.
    invalidatePackages(/* alsoConfigs= */ false);
  }

  @Test
  public void testStarlarkLocalRepository() throws Exception {
    // A simple test that recreates local_repository with Starlark.
    scratch.file("/repo2/MODULE.bazel", "module(name='repo2')");
    scratch.file("/repo2/bar.txt");
    scratch.file("/repo2/BUILD", "filegroup(name='bar', srcs=['bar.txt'])");
    scratch.file(
        "def.bzl",
        """
        def _impl(repository_ctx):
            repository_ctx.symlink(repository_ctx.attr.path, "")

        repo = repository_rule(
            implementation = _impl,
            local = True,
            attrs = {"path": attr.string(mandatory = True)},
        )
        """);
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foo', path='/repo2')");
    invalidatePackages();
    getConfiguredTargetAndData("@@+_repo_rules+foo//:bar");
  }

  @Test
  public void testfailWithIncompatibleUseCcConfigureFromRulesCcDoesNothing() throws Exception {
    // A simple test that recreates local_repository with Starlark.
    scratch.file("/repo2/MODULE.bazel", "module(name='repo2')");
    scratch.file("/repo2/bar.txt");
    scratch.file("/repo2/BUILD", "filegroup(name='bar', srcs=['bar.txt'])");
    scratch.file(
        "def.bzl",
        """
        __do_not_use_fail_with_incompatible_use_cc_configure_from_rules_cc()

        def _impl(repository_ctx):
            repository_ctx.symlink(repository_ctx.attr.path, "")

        repo = repository_rule(
            implementation = _impl,
            local = True,
            attrs = {"path": attr.string(mandatory = True)},
        )
        """);
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foo', path='/repo2')");
    invalidatePackages();
    getConfiguredTargetAndData("@@+_repo_rules+foo//:bar");
  }

  @Test
  public void testStarlarkSymlinkFileFromRepository() throws Exception {
    // This test creates a symbolic link BUILD -> bar.txt.
    scratch.file("/repo2/bar.txt", "filegroup(name='bar', srcs=['foo.txt'])");
    scratch.file("/repo2/BUILD");
    scratch.file("/repo2/MODULE.bazel", "module(name='repo2')");
    scratch.file(
        "def.bzl",
        """
        def _impl(repository_ctx):
            repository_ctx.symlink(Label("@repo2//:bar.txt"), "BUILD")
            repository_ctx.file("foo.txt", "foo")

        repo = repository_rule(
            implementation = _impl,
            local = True,
        )
        """);
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name = 'repo2')",
        "local_path_override(module_name = 'repo2', path = '/repo2')",
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foo')");
    invalidatePackages();
    getConfiguredTargetAndData("@@+_repo_rules+foo//:bar");
  }

  @Test
  public void testStarlarkRepositoryTemplate() throws Exception {
    scratch.file("/repo2/bar.txt", "filegroup(name='{target}', srcs=['{path}'])");
    scratch.file("/repo2/BUILD");
    scratch.file("/repo2/MODULE.bazel", "module(name='repo2')");
    scratch.file(
        "def.bzl",
        "def _impl(repository_ctx):",
        "  repository_ctx.template('BUILD', Label('@repo2//:bar.txt'), "
            + "{'{target}': 'bar', '{path}': 'foo'})",
        "  repository_ctx.file('foo.txt', 'foo')",
        "",
        "repo = repository_rule(",
        "    implementation=_impl,",
        "    local=True)");
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name = 'repo2')",
        "local_path_override(module_name = 'repo2', path = '/repo2')",
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foo')");
    invalidatePackages();
    ConfiguredTargetAndData target = getConfiguredTargetAndData("@@+_repo_rules+foo//:bar");
    @SuppressWarnings("unchecked")
    List<Label> srcs =
        (List<Label>) target.getTargetForTesting().getAssociatedRule().getAttr("srcs", LABEL_LIST);
    assertThat(srcs).containsExactly(Label.parseCanonical("@@+_repo_rules+foo//:foo"));
  }

  @Test
  public void testStarlarkRepositoryName() throws Exception {
    // Variation of the template rule to test the repository_ctx.name field.
    scratch.file("/repo2/bar.txt", "filegroup(name='bar', srcs=['{path}'])");
    scratch.file("/repo2/BUILD");
    scratch.file("/repo2/MODULE.bazel", "module(name='repo2')");
    scratch.file(
        "def.bzl",
        "def _impl(repository_ctx):",
        "  repository_ctx.template('BUILD', Label('@repo2//:bar.txt'), "
            + "{'{path}': repository_ctx.name})",
        "  repository_ctx.file('foo.txt', 'foo')",
        "",
        "repo = repository_rule(",
        "    implementation=_impl,",
        "    local=True)");
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "bazel_dep(name = 'repo2')",
        "local_path_override(module_name = 'repo2', path = '/repo2')",
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foobar')");
    invalidatePackages();
    ConfiguredTargetAndData target = getConfiguredTargetAndData("@@+_repo_rules+foobar//:bar");
    @SuppressWarnings("unchecked")
    List<Label> srcs =
        (List<Label>) target.getTargetForTesting().getAssociatedRule().getAttr("srcs", LABEL_LIST);
    assertThat(srcs)
        .containsExactly(Label.parseCanonical("@@+_repo_rules+foobar//:+_repo_rules+foobar"));
  }

  @Test
  public void testStarlarkRepositoryCannotOverrideBuiltInAttribute() throws Exception {
    scratch.file(
        "def.bzl",
        """
        def _impl(ctx):
            print(ctx.attr.name)

        repo = repository_rule(
            implementation = _impl,
            attrs = {"name": attr.string(mandatory = True)},
        )
        """);
    scratch.file(rootDirectory.getRelative("BUILD").getPathString());
    scratch.overwriteFile(
        rootDirectory.getRelative("MODULE.bazel").getPathString(),
        "repo = use_repo_rule('//:def.bzl', 'repo')",
        "repo(name='foo')");

    invalidatePackages();
    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("@@+_repo_rules+foo//:bar"));
    assertThat(e)
        .hasMessageThat()
        .contains("There is already a built-in attribute 'name' " + "which cannot be overridden");
  }

  @Test
  public void testCallRepositoryRuleFromBuildFile() throws Exception {
    // Check that we get a proper error when calling a repository rule from a BUILD file.

    reporter.removeHandler(failFastHandler);
    scratch.file(
        "repo.bzl",
        """
        def _impl(ctx):
            pass

        repo = repository_rule(implementation = _impl)
        """);
    scratch.file("BUILD", "load('repo.bzl', 'repo')", "repo(name = 'repository_rule')");

    invalidatePackages();
    getConfiguredTarget("//:x");
    assertContainsEvent("repository rules can only be used while evaluating a WORKSPACE file");
  }

  @Test
  public void testPackageAndRepositoryNameFunctionsInExternalRepository() throws Exception {
    // @foo repo
    scratch.file("/foo/MODULE.bazel", "module(name='foo')");
    scratch.file("/foo/p/BUILD", "print('repo='+repository_name()+' pkg='+package_name())");
    // main repo
    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='foo')",
        "local_path_override(module_name='foo', path='/foo')");

    invalidatePackages(); // why is this needed?

    getConfiguredTarget("@@foo+//p:BUILD"); // (loadPackage(@foo//p) would suffice)
    assertContainsEvent("repo=@foo+ pkg=p");
  }
}
