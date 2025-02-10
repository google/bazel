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

package com.google.devtools.build.lib.rules.python;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import org.junit.Before;
import org.junit.Test;

/** Tests that are common to {@code py_binary}, {@code py_test}, and {@code py_library}. */
public abstract class PyBaseConfiguredTargetTestBase extends BuildViewTestCase {

  private final String ruleName;
  protected final String bzlLoad;

  protected PyBaseConfiguredTargetTestBase(String ruleName) {
    this.ruleName = ruleName;
    bzlLoad = getPyLoad(ruleName);
  }

  @Before
  public final void setUpPython() throws Exception {
    analysisMock.pySupport().setup(mockToolsConfig);
  }

  /** Retrieves the Python version of a configured target. */
  protected PythonVersion getPythonVersion(ConfiguredTarget ct) {
    return getConfiguration(ct).getOptions().get(PythonOptions.class).getPythonVersion();
  }

  @Test
  public void goodSrcsVersionValue() throws Exception {
    scratch.file(
        "pkg/BUILD",
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs_version = 'PY3',",
        "    srcs = ['foo.py'])");
    getConfiguredTarget("//pkg:foo");
    assertNoEvents();
  }

  @Test
  public void versionIs3IfUnspecified() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'])");
    assertThat(getPythonVersion(getConfiguredTarget("//pkg:foo"))).isEqualTo(PythonVersion.PY3);
  }

  @Test
  public void producesProvider() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'])");
    ConfiguredTarget target = getConfiguredTarget("//pkg:foo");
    assertThat(PyInfo.fromTarget(target)).isNotNull();
  }

  @Test
  public void consumesProvider() throws Exception {
    scratch.file(
        "pkg/rules.bzl",
        getPyLoad("PyInfo"),
        "def _myrule_impl(ctx):",
        "    return [PyInfo(transitive_sources=depset([]))]",
        "myrule = rule(",
        "    implementation = _myrule_impl,",
        ")");
    scratch.file(
        "pkg/BUILD",
        bzlLoad,
        "load(':rules.bzl', 'myrule')",
        "myrule(",
        "    name = 'dep',",
        ")",
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        "    deps = [':dep'],",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//pkg:foo");
    assertThat(target).isNotNull();
    assertNoEvents();
  }

  @Test
  public void requiresProvider() throws Exception {
    scratch.file(
        "pkg/rules.bzl",
        """
        def _myrule_impl(ctx):
            return []

        myrule = rule(
            implementation = _myrule_impl,
        )
        """);
    checkError(
        "pkg",
        "foo",
        // error:
        "'//pkg:dep' does not have mandatory providers",
        // build file:
        bzlLoad,
        "load(':rules.bzl', 'myrule')",
        "myrule(",
        "    name = 'dep',",
        ")",
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        "    deps = [':dep'],",
        ")");
  }

  @Test
  public void dataSetsUsesSharedLibrary() throws Exception {
    scratch.file(
        "pkg/BUILD",
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        "    data = ['lib.so']",
        ")");
    ConfiguredTarget target = getConfiguredTarget("//pkg:foo");
    assertThat(PyInfo.fromTarget(target).getUsesSharedLibraries()).isTrue();
  }


}
