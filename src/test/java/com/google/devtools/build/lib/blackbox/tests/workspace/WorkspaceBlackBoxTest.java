// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.blackbox.tests.workspace;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.blackbox.framework.BuilderRunner;
import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import com.google.devtools.build.lib.blackbox.framework.ProcessResult;
import com.google.devtools.build.lib.blackbox.junit.AbstractBlackBoxTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/** End to end test of workspace-related functionality. */
public class WorkspaceBlackBoxTest extends AbstractBlackBoxTest {

  @Test
  public void testNotInMsys() throws Exception {
    context()
        .write(
            "repo_rule.bzl",
            """
            def _impl(rctx):
                result = rctx.execute(["bash", "-c", "which bash > out.txt"])
                if result.return_code != 0:
                    fail("Execute bash failed: " + result.stderr)
                rctx.file("BUILD", 'exports_files(["out.txt"])')

            check_bash = repository_rule(implementation = _impl)
            """);

    context()
        .write(
            MODULE_DOT_BAZEL,
            "check_bash = use_repo_rule('//:repo_rule.bzl', 'check_bash')",
            "check_bash(name = 'check_bash_target')");

    // To make repository rule target be computed, depend on it in debug_rule
    context()
        .write(
            "BUILD",
            "load(':rule.bzl', 'debug_rule')",
            "debug_rule(name = 'check', dep = '@check_bash_target//:out.txt')");

    context()
        .write(
            "rule.bzl",
            """
            def _impl(ctx):
                out = ctx.actions.declare_file("does_not_matter")
                ctx.actions.do_nothing(mnemonic = "UseInput", inputs = ctx.attr.dep.files)
                ctx.actions.write(out, "Hi")
                return [DefaultInfo(files = depset([out]))]

            debug_rule = rule(
                implementation = _impl,
                attrs = {
                    "dep": attr.label(allow_single_file = True),
                },
            )
            """);

    BuilderRunner bazel = WorkspaceTestUtils.bazel(context());
    // The build using "bash" should fail on Windows, and pass on Linux and Mac OS
    if (isWindows()) {
      bazel.shouldFail();
    }
    bazel.build("check");
  }

  @Test
  public void testExecuteInWorkingDirectory() throws Exception {
    String pwd = isWindows() ? "['cmd', '/c', 'echo %cd%']" : "['pwd']";
    String buildFileText =
        "\"\"\""
            + String.join(
                "\n",
                RepoWithRuleWritingTextGenerator.loadRule("@main"),
                RepoWithRuleWritingTextGenerator.callRule("debug_me", "out", "%s"))
            + "\"\"\" % stdout";
    context()
        .write(
            "repo_rule.bzl",
            "def _impl(rctx):",
            String.format(
                "  result = rctx.execute(%s, working_directory=rctx.attr.working_directory)", pwd),
            "  if result.return_code != 0:",
            "    fail('Execute failed: ' + result.stderr)",
            // we want to compare the real paths,
            // otherwise it is not clear how to verify the relative path variant
            "  wd = str(rctx.path(rctx.attr.working_directory))",
            // pwd returns the path with '\n' in the end of the line; cut it
            "  stdout = result.stdout.strip(' \\n\\r').replace('\\\\', '/')",
            "  if wd != stdout:",
            "    fail('Wrong current directory: **%s**, expecting **%s**' % (stdout, wd))",
            // create BUILD file with a target so we can call it;
            // rule of a target is defined in the main repository
            "  rctx.file('BUILD', " + buildFileText + ")",
            "check_wd = repository_rule(implementation = _impl,",
            "  attrs = { 'working_directory': attr.string() }",
            ")");

    context()
        .write(
            RepoWithRuleWritingTextGenerator.HELPER_FILE,
            RepoWithRuleWritingTextGenerator.WRITE_TEXT_TO_FILE);
    context().write("BUILD");

    Path tempDirectory = Files.createTempDirectory("temp-execute");
    context()
        .write(
            MODULE_DOT_BAZEL,
            "module(name = 'main')",
            "check_wd = use_repo_rule('//:repo_rule.bzl', 'check_wd')",
            "check_wd(name = 'relative', working_directory = 'relative')",
            "check_wd(name = 'relative2', working_directory = '../relative2')",
            String.format(
                "check_wd(name = 'absolute', working_directory = '%s')",
                PathUtils.pathForStarlarkFile(tempDirectory)),
            String.format(
                "check_wd(name = 'absolute2', working_directory = '%s')",
                PathUtils.pathForStarlarkFile(tempDirectory.resolve("non_existent_child"))));

    BuilderRunner bazel = WorkspaceTestUtils.bazel(context());
    bazel.build("@relative//:debug_me");
    Path outFile = context().resolveBinPath(bazel, "external/+_repo_rules+relative/out");
    assertThat(outFile.toFile().exists()).isTrue();
    List<String> lines = PathUtils.readFile(outFile);
    assertThat(lines.size()).isEqualTo(1);
    assertThat(
            Paths.get(lines.get(0)).endsWith(Paths.get("external/+_repo_rules+relative/relative")))
        .isTrue();

    bazel.build("@relative2//:debug_me");
    bazel.build("@absolute//:debug_me");

    bazel.build("@absolute2//:debug_me");
    Path outFile2 = context().resolveBinPath(bazel, "external/+_repo_rules+absolute2/out");
    assertThat(outFile2.toFile().exists()).isTrue();
    List<String> lines2 = PathUtils.readFile(outFile2);
    assertThat(lines2.size()).isEqualTo(1);
    assertThat(Paths.get(lines2.get(0)).equals(tempDirectory.resolve("non_existent_child")))
        .isTrue();
  }

  @Test
  public void testWorkspaceChanges() throws Exception {
    Path repoA = context().getTmpDir().resolve("a");
    new RepoWithRuleWritingTextGenerator(repoA).withOutputText("hi").setupRepository();

    Path repoB = context().getTmpDir().resolve("b");
    new RepoWithRuleWritingTextGenerator(repoB).withOutputText("bye").setupRepository();

    context()
        .write(
            MODULE_DOT_BAZEL,
            "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                + " 'local_repository')",
            String.format(
                "local_repository(name = 'x', path = '%s',)",
                PathUtils.pathForStarlarkFile(repoA)));
    BuilderRunner bazel = WorkspaceTestUtils.bazel(context());
    bazel.build("@x//:" + RepoWithRuleWritingTextGenerator.TARGET);

    Path xPath = context().resolveBinPath(bazel, "external/+_repo_rules+x/out");
    WorkspaceTestUtils.assertLinesExactly(xPath, "hi");

    context()
        .write(
            MODULE_DOT_BAZEL,
            "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                + " 'local_repository')",
            String.format(
                "local_repository(name = 'x', path = '%s',)",
                PathUtils.pathForStarlarkFile(repoB)));
    bazel.build("@x//:" + RepoWithRuleWritingTextGenerator.TARGET);

    WorkspaceTestUtils.assertLinesExactly(xPath, "bye");
  }

  @Test
  public void testNoPackageLoadingOnBenignWorkspaceChanges() throws Exception {
    Path repo = context().getTmpDir().resolve(testName.getMethodName());
    new RepoWithRuleWritingTextGenerator(repo).withOutputText("hi").setupRepository();

    context()
        .write(
            MODULE_DOT_BAZEL,
            "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                + " 'local_repository')",
            String.format(
                "local_repository(name = 'ext', path = '%s',)",
                PathUtils.pathForStarlarkFile(repo)));

    BuilderRunner bazel =
        WorkspaceTestUtils.bazel(context())
            // This combination of flags ensures all progress events get into stdout
            // and Bazel recognizes that there is a terminal, so progress events will be displayed
            .withFlags("--experimental_ui_debug_all_events", "--curses=yes");

    final String progressMessage = "PROGRESS <no location>: Loading package: @@+_repo_rules+ext//";

    ProcessResult result = bazel.query("@ext//:all");
    assertThat(result.outString()).contains(progressMessage);

    result = bazel.query("@ext//:all");
    assertThat(result.outString()).doesNotContain(progressMessage);

    // TODO(bzlmod): Fix this for MODULE.bazel
    // Path moduleDotBazel = context().getWorkDir().resolve(MODULE_DOT_BAZEL);
    // PathUtils.append(moduleDotBazel, "# comment");

    // result = bazel.query("@ext//:all");
    // assertThat(result.outString()).doesNotContain(progressMessage);
  }

  @Test
  public void testPathWithSpace() throws Exception {
    context().write("a b/MODULE.bazel");
    BuilderRunner bazel = WorkspaceTestUtils.bazel(context());
    bazel.info();
    bazel.help();
  }

  @Test
  public void testBadRepoName() throws Exception {
    context()
        .write(
            MODULE_DOT_BAZEL,
            "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                + " 'local_repository')",
            "local_repository(name = '@a', path = 'abc')");
    context().write("BUILD");
    ProcessResult result = context().bazel().shouldFail().build("//...");
    assertThat(result.errString()).contains("invalid user-provided repo name '@a'");
  }
}
