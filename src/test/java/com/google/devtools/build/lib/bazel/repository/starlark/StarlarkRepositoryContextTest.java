// Copyright 2014 The Bazel Authors. All rights reserved.
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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.Builder.PackageSettings;
import com.google.devtools.build.lib.packages.PackageOverheadEstimator;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.packages.WorkspaceFactoryHelper;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor.ExecutionResult;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for complex function of StarlarkRepositoryContext. */
@RunWith(JUnit4.class)
public final class StarlarkRepositoryContextTest {

  private Scratch scratch;
  private Path outputBase;
  private Path outputDirectory;
  private Root root;
  private Path workspaceFile;
  private StarlarkRepositoryContext context;
  private Label fakeFileLabel;
  private static final StarlarkThread thread =
      StarlarkThread.createTransient(Mutability.create("test"), StarlarkSemantics.DEFAULT);

  private static final String ONE_LINE_PATCH = "@@ -1,1 +1,2 @@\n line one\n+line two\n";

  @Before
  public void setUp() throws Exception {
    scratch = new Scratch("/");
    outputBase = scratch.dir("/outputBase");
    outputDirectory = scratch.dir("/outputDir");
    root = Root.fromPath(scratch.dir("/wsRoot"));
    workspaceFile = scratch.file("/wsRoot/WORKSPACE");
  }

  private static RuleClass buildRuleClass(Attribute... attributes) {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("test", RuleClassType.WORKSPACE, true);
    for (Attribute attr : attributes) {
      ruleClassBuilder.addAttribute(attr);
    }
    ruleClassBuilder.setWorkspaceOnly();
    ruleClassBuilder.setConfiguredTargetFunction(
        (StarlarkFunction) exec("def test(ctx): pass", "test"));
    return ruleClassBuilder.build();
  }

  private static Object exec(String... lines) {
    try {
      return Starlark.execFile(
          ParserInput.fromLines(lines), FileOptions.DEFAULT, Module.create(), thread);
    } catch (Exception ex) { // SyntaxError | EvalException | InterruptedException
      throw new AssertionError("exec failed", ex);
    }
  }

  private static final ImmutableList<StarlarkThread.CallStackEntry> DUMMY_STACK =
      ImmutableList.of(
          StarlarkThread.callStackEntry(
              StarlarkThread.TOP_LEVEL, Location.fromFileLineColumn("BUILD", 10, 1)),
          StarlarkThread.callStackEntry("foo", Location.fromFileLineColumn("foo.bzl", 42, 1)),
          StarlarkThread.callStackEntry("myrule", Location.fromFileLineColumn("bar.bzl", 30, 6)));

  private void setUpContextForRule(
      Map<String, Object> kwargs,
      IgnoredSubdirectories ignoredSubdirectories,
      ImmutableMap<String, String> envVariables,
      StarlarkSemantics starlarkSemantics,
      @Nullable RepositoryRemoteExecutor repoRemoteExecutor,
      Attribute... attributes)
      throws Exception {
    Package.Builder packageBuilder =
        Package.newExternalPackageBuilder(
            PackageSettings.DEFAULTS,
            WorkspaceFileValue.key(RootedPath.toRootedPath(root, workspaceFile)),
            "runfiles",
            RepositoryMapping.ALWAYS_FALLBACK,
            starlarkSemantics.getBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT),
            starlarkSemantics.getBool(
                BuildLanguageOptions.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS),
            PackageOverheadEstimator.NOOP_ESTIMATOR);
    ExtendedEventHandler listener = Mockito.mock(ExtendedEventHandler.class);
    Rule rule =
        WorkspaceFactoryHelper.createAndAddRepositoryRule(
            packageBuilder,
            buildRuleClass(attributes),
            kwargs,
            DUMMY_STACK);
    DownloadManager downloader = Mockito.mock(DownloadManager.class);
    SkyFunction.Environment environment = Mockito.mock(SkyFunction.Environment.class);
    when(environment.getListener()).thenReturn(listener);
    fakeFileLabel = Label.parseCanonical("//:foo");
    when(environment.getValue(PackageLookupValue.key(fakeFileLabel.getPackageIdentifier())))
        .thenReturn(
            PackageLookupValue.success(
                Root.fromPath(workspaceFile.getParentDirectory()), BuildFileName.BUILD));
    when(environment.getValueOrThrow(any(), eq(IOException.class)))
        .thenReturn(Mockito.mock(FileValue.class));
    PathPackageLocator packageLocator =
        new PathPackageLocator(
            outputDirectory,
            ImmutableList.of(root),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY);
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(root.asPath(), outputBase, root.asPath()),
            root.asPath(),
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    context =
        new StarlarkRepositoryContext(
            rule,
            packageLocator,
            outputDirectory,
            ignoredSubdirectories,
            environment,
            envVariables,
            downloader,
            1.0,
            /* processWrapper= */ null,
            starlarkSemantics,
            repoRemoteExecutor,
            SyscallCache.NO_CACHE,
            directories);
  }

  private void setUpContextForRule(String name) throws Exception {
    setUpContextForRule(name, StarlarkSemantics.DEFAULT);
  }

  private void setUpContextForRule(String name, StarlarkSemantics starlarkSemantics)
      throws Exception {
    setUpContextForRule(
        ImmutableMap.of("name", name),
        IgnoredSubdirectories.EMPTY,
        ImmutableMap.of("FOO", "BAR"),
        starlarkSemantics,
        /* repoRemoteExecutor= */ null);
  }

  @Test
  public void testAttr() throws Exception {
    setUpContextForRule(
        ImmutableMap.of("name", "test", "foo", "bar"),
        IgnoredSubdirectories.EMPTY,
        ImmutableMap.of("FOO", "BAR"),
        StarlarkSemantics.DEFAULT,
        /* repoRemoteExecutor= */ null,
        Attribute.attr("foo", Type.STRING).build());

    assertThat(context.getAttr().getFieldNames()).contains("foo");
    assertThat(context.getAttr().getValue("foo")).isEqualTo("bar");
  }

  @Test
  public void testWhich() throws Exception {
    setUpContextForRule(
        ImmutableMap.of("name", "test"),
        IgnoredSubdirectories.EMPTY,
        ImmutableMap.of("PATH", String.join(File.pathSeparator, "/bin", "/path/sbin", ".")),
        StarlarkSemantics.DEFAULT,
        /* repoRemoteExecutor= */ null);
    scratch.file("/bin/true").setExecutable(true);
    scratch.file("/path/sbin/true").setExecutable(true);
    scratch.file("/path/sbin/false").setExecutable(true);
    scratch.file("/path/bin/undef").setExecutable(true);
    scratch.file("/path/bin/def").setExecutable(true);
    scratch.file("/bin/undef");

    assertThat(context.which("anything", thread)).isNull();
    assertThat(context.which("def", thread)).isNull();
    assertThat(context.which("undef", thread)).isNull();
    assertThat(context.which("true", thread).toString()).isEqualTo("/bin/true");
    assertThat(context.which("false", thread).toString()).isEqualTo("/path/sbin/false");
  }

  @Test
  public void testFile() throws Exception {
    setUpContextForRule("test");
    context.createFile(context.getPath("foobar"), "", true, true, thread);
    context.createFile(context.getPath("foo/bar"), "foobar", true, true, thread);
    context.createFile(context.getPath("bar/foo/bar"), "", true, true, thread);

    testOutputFile(outputDirectory.getChild("foobar"), "");
    testOutputFile(outputDirectory.getRelative("foo/bar"), "foobar");
    testOutputFile(outputDirectory.getRelative("bar/foo/bar"), "");

    try {
      context.createFile(context.getPath("/absolute"), "", true, true, thread);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /absolute");
    }
    try {
      context.createFile(context.getPath("../somepath"), "", true, true, thread);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /somepath");
    }
    try {
      context.createFile(context.getPath("foo/../../somepath"), "", true, true, thread);
      fail("Expected error on creating path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /somepath");
    }
  }

  @Test
  public void testDelete() throws Exception {
    setUpContextForRule("testDelete");
    Path bar = outputDirectory.getRelative("foo/bar");
    Object path1 = bar.getPathString();
    StarlarkPath barPath = context.getPath(path1);
    context.createFile(barPath, "content", true, true, thread);
    assertThat(context.delete(barPath, thread)).isTrue();

    assertThat(context.delete(barPath, thread)).isFalse();

    Path tempFile = scratch.file("/abcde/b", "123");
    Object path = tempFile.getPathString();
    assertThat(context.delete(context.getPath(path), thread)).isTrue();

    Path innerDir = scratch.dir("/some/inner");
    scratch.dir("/some/inner/deeper");
    scratch.file("/some/inner/deeper.txt");
    scratch.file("/some/inner/deeper/1.txt");
    assertThat(context.delete(innerDir.toString(), thread)).isTrue();

    Path underWorkspace = root.getRelative("under_workspace");
    try {
      context.delete(underWorkspace.toString(), thread);
      fail();
    } catch (EvalException expected) {
      assertThat(expected.getMessage())
          .startsWith("delete() can only be applied to external paths");
    }

    scratch.file(underWorkspace.getPathString(), "123");
    setUpContextForRule(
        ImmutableMap.of("name", "test"),
        IgnoredSubdirectories.of(ImmutableSet.of(PathFragment.create("under_workspace"))),
        ImmutableMap.of("FOO", "BAR"),
        StarlarkSemantics.DEFAULT,
        /* repoRemoteExecutor= */ null);
    assertThat(context.delete(underWorkspace.toString(), thread)).isTrue();
  }

  @Test
  public void testRead() throws Exception {
    setUpContextForRule("test");
    context.createFile(context.getPath("foo/bar"), "foobar", true, true, thread);

    String content = context.readFile(context.getPath("foo/bar"), "auto", thread);
    assertThat(content).isEqualTo("foobar");
  }

  @Test
  public void testPatch() throws Exception {
    setUpContextForRule("test");
    StarlarkPath foo = context.getPath("foo");
    context.createFile(foo, "line one\n", false, true, thread);
    StarlarkPath patchFile = context.getPath("my.patch");
    context.createFile(
        context.getPath("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, thread);
    context.patch(patchFile, StarlarkInt.of(0), "auto", thread);
    testOutputFile(foo.getPath(), "line one\nline two\n");
  }

  @Test
  public void testCannotFindFileToPatch() throws Exception {
    setUpContextForRule("test");
    StarlarkPath patchFile = context.getPath("my.patch");
    context.createFile(
        context.getPath("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, thread);
    try {
      context.patch(patchFile, StarlarkInt.of(0), "auto", thread);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: Cannot find file to patch (near line 1)"
                  + ", old file name (foo) doesn't exist, new file name (foo) doesn't exist.");
    }
  }

  @Test
  public void testPatchOutsideOfExternalRepository() throws Exception {
    setUpContextForRule("test");
    StarlarkPath patchFile = context.getPath("my.patch");
    context.createFile(
        context.getPath("my.patch"),
        "--- ../other_root/foo\n" + "+++ ../other_root/foo\n" + ONE_LINE_PATCH,
        false,
        true,
        thread);
    try {
      context.patch(patchFile, StarlarkInt.of(0), "auto", thread);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: Cannot patch file outside of external "
                  + "repository (/outputDir), file path = \"../other_root/foo\" at line 1");
    }
  }

  @Test
  public void testPatchErrorWasThrown() throws Exception {
    setUpContextForRule("test");
    StarlarkPath foo = context.getPath("foo");
    StarlarkPath patchFile = context.getPath("my.patch");
    context.createFile(foo, "line three\n", false, true, thread);
    context.createFile(
        context.getPath("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, thread);
    try {
      context.patch(patchFile, StarlarkInt.of(0), "auto", thread);
      fail("Expected RepositoryFunctionException");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo(
              "Error applying patch /outputDir/my.patch: in patch applied to "
                  + "/outputDir/foo: could not apply patch due to"
                  + " CONTENT_DOES_NOT_MATCH_TARGET, error applying change near line 1");
    }
  }

  @Test
  public void testRemoteExec() throws Exception {
    // Test that context.execute() can call out to remote execution and correctly forward
    // execution properties.

    // Prepare mocked remote repository and corresponding repository rule.
    ImmutableMap<String, Object> attrValues =
        ImmutableMap.of(
            "name",
            "configure",
            "$remotable",
            true,
            "exec_properties",
            Dict.builder().put("OSFamily", "Linux").buildImmutable());

    RepositoryRemoteExecutor repoRemoteExecutor = Mockito.mock(RepositoryRemoteExecutor.class);
    ExecutionResult executionResult =
        new ExecutionResult(
            0,
            "test-stdout".getBytes(StandardCharsets.US_ASCII),
            "test-stderr".getBytes(StandardCharsets.US_ASCII));
    when(repoRemoteExecutor.execute(any(), any(), any(), any(), any(), any()))
        .thenReturn(executionResult);

    setUpContextForRule(
        attrValues,
        IgnoredSubdirectories.EMPTY,
        ImmutableMap.of("FOO", "BAR"),
        StarlarkSemantics.builder()
            .setBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC, true)
            .build(),
        repoRemoteExecutor,
        Attribute.attr("$remotable", Type.BOOLEAN).build(),
        Attribute.attr("exec_properties", Types.STRING_DICT).build());

    // Execute the `StarlarkRepositoryContext`.
    StarlarkExecutionResult starlarkExecutionResult =
        context.execute(
            StarlarkList.of(/* mutability= */ null, "/bin/cmd", "arg1"),
            /* timeoutI= */ StarlarkInt.of(10),
            /* uncheckedEnvironment= */ Dict.empty(),
            /* quiet= */ true,
            /* overrideWorkingDirectory= */ "",
            thread);

    // Verify the remote repository rule was run and its response returned.
    verify(repoRemoteExecutor)
        .execute(
            /* arguments= */ ImmutableList.of("/bin/cmd", "arg1"),
            /* inputFiles= */ ImmutableSortedMap.of(),
            /* executionProperties= */ ImmutableMap.of("OSFamily", "Linux"),
            /* environment= */ ImmutableMap.of(),
            /* workingDirectory= */ "",
            /* timeout= */ Duration.ofSeconds(10));
    assertThat(starlarkExecutionResult.getReturnCode()).isEqualTo(0);
    assertThat(starlarkExecutionResult.getStdout()).isEqualTo("test-stdout");
    assertThat(starlarkExecutionResult.getStderr()).isEqualTo("test-stderr");
  }

  @Test
  public void testRename() throws Exception {
    setUpContextForRule("test");
    context.createFile(context.getPath("foo"), "foobar", true, true, thread);

    context.rename(context.getPath("foo"), context.getPath("bar/baz"), thread);
    testOutputFile(outputDirectory.getChild("bar").getChild("baz"), "foobar");

    try {
      context.rename(context.getPath("/foo"), context.getPath("bar"), thread);
      fail("Expected error on renaming path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /foo");
    }

    try {
      context.rename(context.getPath("foo"), context.getPath("/bar"), thread);
      fail("Expected error on renaming path outside of the repository directory");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Cannot write outside of the repository directory for path /bar");
    }
  }

  @Test
  public void testRenameConflict() throws Exception {
    setUpContextForRule("test");
    context.createFile(context.getPath("foo"), "fooooo", true, true, thread);
    context.createFile(context.getPath("bar"), "baaaar", true, true, thread);
    context.createFile(context.getPath("baz.d/baz"), "baaaaz", true, true, thread);

    try {
      context.rename(context.getPath("foo"), context.getPath("bar"), thread);
      fail("Expected error on renaming to a path that already exists");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Could not rename /outputDir/foo to /outputDir/bar: already exists");
    }

    try {
      context.rename(context.getPath("foo"), context.getPath("baz.d"), thread);
      fail("Expected error on renaming to a path that already exists");
    } catch (RepositoryFunctionException ex) {
      assertThat(ex)
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("Could not rename /outputDir/foo to /outputDir/baz.d: already exists");
    }
  }

  @Test
  public void testSymlink() throws Exception {
    setUpContextForRule("test");
    context.createFile(context.getPath("foo"), "foobar", true, true, thread);

    context.symlink(context.getPath("foo"), context.getPath("bar"), thread);
    testOutputFile(outputDirectory.getChild("bar"), "foobar");

    assertThat(context.getPath("bar").realpath()).isEqualTo(context.getPath("foo"));
  }

  private static void testOutputFile(Path path, String content) throws IOException {
    assertThat(path.exists()).isTrue();
    try (InputStreamReader reader =
        new InputStreamReader(path.getInputStream(), StandardCharsets.UTF_8)) {
      assertThat(CharStreams.toString(reader)).isEqualTo(content);
    }
  }

  @Test
  public void testDirectoryListing() throws Exception {
    setUpContextForRule("test");
    scratch.file("/my/folder/a");
    scratch.file("/my/folder/b");
    scratch.file("/my/folder/c");
    assertThat(context.getPath("/my/folder").readdir("no"))
        .containsExactly(
            context.getPath("/my/folder/a"),
            context.getPath("/my/folder/b"),
            context.getPath("/my/folder/c"));
  }

  @Test
  public void testWorkspaceRoot() throws Exception {
    setUpContextForRule("test");
    assertThat(context.getWorkspaceRoot().getPath()).isEqualTo(root.asPath());
  }

  @Test
  public void testNoIncompatibleNoImplicitWatchLabel() throws Exception {
    setUpContextForRule(
        "test",
        StarlarkSemantics.DEFAULT.toBuilder()
            .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, false)
            .build());
    scratch.file(root.getRelative("foo").getPathString());
    StarlarkPath unusedPath = context.getPath(fakeFileLabel);
    String unusedRead = context.readFile(fakeFileLabel, "no", thread);
    assertThat(context.getRecordedFileInputs()).isNotEmpty();
  }

  @Test
  public void testIncompatibleNoImplicitWatchLabel() throws Exception {
    setUpContextForRule(
        "test",
        StarlarkSemantics.DEFAULT.toBuilder()
            .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, true)
            .build());
    scratch.file(root.getRelative("foo").getPathString());
    StarlarkPath unusedPath = context.getPath(fakeFileLabel);
    String unusedRead = context.readFile(fakeFileLabel, "no", thread);
    assertThat(context.getRecordedFileInputs()).isEmpty();
  }
}
