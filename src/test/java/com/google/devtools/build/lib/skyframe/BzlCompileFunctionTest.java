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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests of specific functionality of BzlCompileFunction. */
@RunWith(JUnit4.class)
public class BzlCompileFunctionTest extends BuildViewTestCase {

  private static class MockFileSystem extends InMemoryFileSystem {
    PathFragment throwIOExceptionFor = null;

    MockFileSystem() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public FileStatus statIfFound(PathFragment path, boolean followSymlinks) throws IOException {
      if (path.equals(throwIOExceptionFor)) {
        throw new IOException("bork");
      }
      return super.statIfFound(path, followSymlinks);
    }
  }

  private MockFileSystem mockFS;

  @Override
  protected FileSystem createFileSystem() {
    mockFS = new MockFileSystem();
    return mockFS;
  }

  @Test
  public void testIOExceptionOccursDuringReading() throws Exception {
    reporter.removeHandler(failFastHandler);
    scratch.file("/workspace/tools/test_build_rules/BUILD");
    scratch.file(
        "foo/BUILD",
        """
        genrule(
            name = "foo",
            outs = ["out.txt"],
            cmd = "echo hello >@",
        )
        """);
    mockFS.throwIOExceptionFor = PathFragment.create("/workspace/foo/BUILD");
    invalidatePackages(/*alsoConfigs=*/ false); // We don't want to fail early on config creation.

    SkyKey skyKey = PackageIdentifier.createInMainRepo("foo");
    EvaluationResult<PackageValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    assertThat(result.hasError()).isTrue();
    ErrorInfo errorInfo = result.getError(skyKey);
    Throwable e = errorInfo.getException();
    assertThat(e).isInstanceOf(NoSuchPackageException.class);
    assertThat(e).hasMessageThat().contains("bork");
  }

  @Test
  public void testLoadFromFileInRemoteRepo() throws Exception {
    Path repoPath = scratch.dir("/a_remote_repo");
    scratch.file("/a_remote_repo/REPO.bazel");
    scratch.file("/a_remote_repo/remote_pkg/BUILD");
    scratch.file("/a_remote_repo/remote_pkg/foo.bzl", "load(':bar.bzl', 'CONST')");
    scratch.file("/a_remote_repo/remote_pkg/bar.bzl", "CONST = 17");

    invalidatePackages(/*alsoConfigs=*/ false); // Repository shuffling messes with toolchains.
    SkyKey skyKey =
        BzlCompileValue.key(
            Root.fromPath(repoPath),
            Label.parseCanonicalUnchecked("@a_remote_repo//remote_pkg:foo.bzl"));
    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    List<String> loads =
        BzlLoadFunction.getLoadsFromProgram(result.get(skyKey).getProgram()).stream()
            .map(Pair::getFirst)
            .collect(toImmutableList());
    assertThat(loads).containsExactly(":bar.bzl");
  }

  @Test
  public void testLoadOfNonexistentFile() throws Exception {
    SkyKey skyKey = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"));
    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThat(result.get(skyKey).lookupSuccessful()).isFalse();
    assertThat(result.get(skyKey).getError()).contains("cannot load '//pkg:foo.bzl': no such file");
  }

  @Test
  public void testBigIntegerLiterals() throws Exception {
    // This test ensures that numerical literals with values that can't be expressed as Java longs
    // can be compiled. Regression test for b/217548647.
    SkyKey skyKey = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:bigint.bzl"));
    scratch.file("pkg/BUILD");
    scratch.file(
        "pkg/bigint.bzl",
        String.format(
            "[%s, %s]",
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /*keepGoing=*/ false, reporter);
    BzlCompileValue bzlCompileValue = result.get(skyKey);
    assertThat(bzlCompileValue.lookupSuccessful()).isTrue();

    try (Mutability mu = Mutability.create()) {
      Object val =
          Starlark.execFileProgram(
              bzlCompileValue.getProgram(),
              Module.withPredeclared(StarlarkSemantics.DEFAULT, ImmutableMap.of()),
              StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT));
      assertThat(val.toString()).isEqualTo("[-9223372036854775809, 9223372036854775808]");
    }
  }

  @Test
  public void testInvalidUtf8_enforcementOff() throws Exception {
    setBuildLanguageOptions("--noincompatible_enforce_starlark_utf8");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/foo.bzl", new byte[] {'#', ' ', (byte) 0x80});

    SkyKey skyKey = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"));
    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThat(result.get(skyKey).lookupSuccessful()).isTrue();
    assertNoEvents();
  }

  @Test
  public void testInvalidUtf8_enforcementWarning() throws Exception {
    setBuildLanguageOptions("--incompatible_enforce_starlark_utf8=warning");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/foo.bzl", new byte[] {'#', ' ', (byte) 0x80});

    SkyKey skyKey = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"));
    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThat(result.get(skyKey).lookupSuccessful()).isTrue();
    assertContainsEvent(
        "WARNING /workspace/pkg/foo.bzl: not a valid UTF-8 encoded file; this can lead to"
            + " inconsistent behavior and will be disallowed in a future version of Bazel");
  }

  @Test
  public void testInvalidUtf8_enforcementError() throws Exception {
    reporter.removeHandler(failFastHandler);
    setBuildLanguageOptions("--incompatible_enforce_starlark_utf8");

    scratch.file("pkg/BUILD");
    scratch.file("pkg/foo.bzl", new byte[] {'#', ' ', (byte) 0x80});

    SkyKey skyKey = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"));
    EvaluationResult<BzlCompileValue> result =
        SkyframeExecutorTestUtils.evaluate(
            getSkyframeExecutor(), skyKey, /* keepGoing= */ false, reporter);
    assertThat(result.get(skyKey).lookupSuccessful()).isFalse();
    assertThat(result.get(skyKey).getError())
        .isEqualTo("compilation of '/workspace/pkg/foo.bzl' failed");
    assertContainsEvent(
        "ERROR /workspace/pkg/foo.bzl: not a valid UTF-8 encoded file; this can lead to"
            + " inconsistent behavior and will be disallowed in a future version of Bazel");
  }
}
