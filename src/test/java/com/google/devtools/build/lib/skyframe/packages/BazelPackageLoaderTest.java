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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertNoEvents;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.IOException;
import java.util.concurrent.ForkJoinPool;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Simple tests for {@link BazelPackageLoader}.
 *
 * <p>Bazel's unit and integration tests do consistency checks with {@link BazelPackageLoader} under
 * the covers, so we get pretty exhaustive correctness tests for free.
 */
@RunWith(JUnit4.class)
public final class BazelPackageLoaderTest extends AbstractPackageLoaderTest {

  private Path installBase;
  private Path outputBase;

  @Before
  public void setUp() throws Exception {
    installBase = fs.getPath("/installBase/");
    installBase.createDirectoryAndParents();
    outputBase = fs.getPath("/outputBase/");
    outputBase.createDirectoryAndParents();

    mockEmbeddedTools(installBase);
    fetchExternalRepo(RepositoryName.create("bazel_tools"));

    file("MODULE.bazel", "");
  }

  private static void mockEmbeddedTools(Path embeddedBinaries) throws IOException {
    Path tools = embeddedBinaries.getRelative("embedded_tools");
    tools.getRelative("tools/cpp").createDirectoryAndParents();
    tools.getRelative("tools/osx").createDirectoryAndParents();
    FileSystemUtils.writeIsoLatin1(tools.getRelative("MODULE.bazel"), "module(name='bazel_tools')");
    FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/cpp/BUILD"), "");
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/cpp/cc_configure.bzl"),
        "def cc_configure(*args, **kwargs):",
        "    pass");
    FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/osx/BUILD"), "");
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/osx/xcode_configure.bzl"),
        "def xcode_configure(*args, **kwargs):",
        "    pass");
    FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/sh/BUILD"), "");
    FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/build_defs/repo/BUILD"));
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/build_defs/repo/http.bzl"),
        "def http_archive(**kwargs):",
        "  pass",
        "",
        "def http_file(**kwargs):",
        "  pass",
        "",
        "def http_jar(**kwargs):",
        "  pass");
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/build_defs/repo/local.bzl"),
        """
        def _local_repository_impl(rctx):
          path = rctx.workspace_root.get_child(rctx.attr.path)
          rctx.symlink(path, ".")
        local_repository = repository_rule(
            implementation = _local_repository_impl,
            attrs = {"path": attr.string()},
        )

        def new_local_repository(**kwargs):
          pass
        """);
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/build_defs/repo/utils.bzl"),
        "def maybe(repo_rule, name, **kwargs):",
        "  if name not in native.existing_rules():",
        "    repo_rule(name = name, **kwargs)");
    FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/jdk/BUILD"));
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/jdk/jdk_build_file.bzl"), "JDK_BUILD_TEMPLATE = ''");
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/jdk/local_java_repository.bzl"),
        "def local_java_repository(**kwargs):",
        "  pass");
    FileSystemUtils.writeIsoLatin1(
        tools.getRelative("tools/jdk/remote_java_repository.bzl"),
        "def remote_java_repository(**kwargs):",
        "  pass");
  }

  private void fetchExternalRepo(RepositoryName externalRepo) {
    try (PackageLoader pkgLoaderForFetch =
        newPackageLoaderBuilder(root).setFetchForTesting().build()) {
      // Load the package '' in this repo. This package may or may not exist; we don't care since we
      // merely need the side-effects of the 'fetch' work.
      PackageIdentifier pkgId = PackageIdentifier.create(externalRepo, PathFragment.create(""));
      try {
        var unused = pkgLoaderForFetch.loadPackage(pkgId);
      } catch (NoSuchPackageException | InterruptedException e) {
        // Doesn't matter; see above comment.
      }
    }
  }

  @Override
  protected BazelPackageLoader.Builder newPackageLoaderBuilder(Root workspaceDir) {
    return (BazelPackageLoader.Builder)
        BazelPackageLoader.builder(workspaceDir, installBase, outputBase)
            .setStarlarkSemantics(
                StarlarkSemantics.builder()
                    .set(BuildLanguageOptions.INCOMPATIBLE_AUTOLOAD_EXTERNALLY, ImmutableList.of())
                    .build());
  }

  @Override
  protected ForkJoinPool extractLegacyGlobbingForkJoinPool(PackageLoader packageLoader) {
    return ((BazelPackageLoader) packageLoader).forkJoinPoolForNonSkyframeGlobbing;
  }

  @Test
  public void simpleLocalRepositoryPackage() throws Exception {
    file(
        "MODULE.bazel",
        "bazel_dep(name = 'r')",
        "local_path_override(module_name = 'r', path='r')");
    file("r/MODULE.bazel", "module(name = 'r')");
    file("r/good/BUILD", "filegroup(name = 'good')");
    RepositoryName rRepoName = RepositoryName.create("r+");
    fetchExternalRepo(rRepoName);

    PackageIdentifier pkgId = PackageIdentifier.create(rRepoName, PathFragment.create("good"));
    Package goodPkg;
    RepositoryMapping repoMapping;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      goodPkg = pkgLoader.loadPackage(pkgId);
      repoMapping = pkgLoader.makeLoadingContext().getRepositoryMapping();
    }
    assertThat(goodPkg.containsErrors()).isFalse();
    assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup");
    assertThat(repoMapping.entries().get("r")).isEqualTo(rRepoName);
    assertNoEvents(handler.getEvents());
  }

  @Test
  public void buildDotBazelForSubpackageCheckDuringGlobbing() throws Exception {
    file("a/BUILD", "filegroup(name = 'fg', srcs = glob(['sub/a.txt'], allow_empty = True))");
    file("a/sub/a.txt");
    file("a/sub/BUILD.bazel");

    PackageIdentifier pkgId = PackageIdentifier.createInMainRepo(PathFragment.create("a"));
    Package aPkg;
    try (PackageLoader pkgLoader = newPackageLoader()) {
      aPkg = pkgLoader.loadPackage(pkgId);
    }
    assertThat(aPkg.containsErrors()).isFalse();
    assertThrows(NoSuchTargetException.class, () -> aPkg.getTarget("sub/a.txt"));
    assertNoEvents(handler.getEvents());
  }
}
