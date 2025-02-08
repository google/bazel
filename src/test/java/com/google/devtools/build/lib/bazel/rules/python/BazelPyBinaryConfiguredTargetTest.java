// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules.python;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.actions.Substitution;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Bazel-specific tests for {@code py_binary}. */
@RunWith(JUnit4.class)
public class BazelPyBinaryConfiguredTargetTest extends BuildViewTestCase {

  private static final String TOOLCHAIN_TYPE =
      TestConstants.TOOLS_REPOSITORY + "//tools/python:toolchain_type";

  private static String join(String... lines) {
    return String.join("\n", lines);
  }

  /**
   * Given a {@code py_binary} or {@code py_test} target and substitution key, returns the
   * corresponding substitution value used by the generated stub script.
   *
   * <p>This works by casting the stub script's generating action to a template expansion action and
   * looking for the requested substitution key. It's therefore linked to the implementation of the
   * rule, but that's the cost we pay for avoiding an execution-time test.
   */
  private String getSubstitutionValueFromStub(
      ConfiguredTarget pyExecutableTarget, String substitutionKey) {
    // First find the stub script. Normally this is just the executable associated with the target.
    // But for Windows the executable is a separate launcher with an ".exe" extension, and the stub
    // script artifact has the same base name with the extension ".temp" instead. (At least, when
    // --build_python_zip is enabled, which is the default on Windows.)
    Artifact executable = pyExecutableTarget.getProvider(FilesToRunProvider.class).getExecutable();
    Artifact stub;
    if (OS.getCurrent() == OS.WINDOWS) {
      stub =
          getDerivedArtifact(
              FileSystemUtils.replaceExtension(executable.getRootRelativePath(), ".temp"),
              executable.getRoot(),
              executable.getArtifactOwner());
    } else {
      stub = executable;
    }
    assertThat(stub).isNotNull();
    // Now grab its generating action, which should be a template action, and get the key for the
    // binary path.
    Action generatingAction = getGeneratingAction(stub);
    assertThat(generatingAction).isInstanceOf(TemplateExpansionAction.class);
    TemplateExpansionAction templateAction = (TemplateExpansionAction) generatingAction;
    for (Substitution sub : templateAction.getSubstitutions()) {
      if (sub.getKey().equals(substitutionKey)) {
        return sub.getValueUnchecked();
      }
    }
    throw new AssertionError(
        "Failed to find the '"
            + substitutionKey
            + "' key in the stub script's template "
            + "expansion action");
  }

  private String getInterpreterPathFromStub(ConfiguredTarget pyExecutableTarget) {
    return getSubstitutionValueFromStub(pyExecutableTarget, "%python_binary%");
  }

  private String getShebangFromStub(ConfiguredTarget pyExecutableTarget) {
    return getSubstitutionValueFromStub(pyExecutableTarget, "%shebang%");
  }

  // TODO(#8169): Delete tests of the legacy --python_top / --python_path behavior.

  @Test
  public void runtimeSetByPythonTop() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_runtime"),
        getPyLoad("py_binary"),
        """
        py_runtime(
            name = "my_py_runtime",
            interpreter_path = "/system/python3",
            python_version = "PY3",
        )

        py_binary(
            name = "pybin",
            srcs = ["pybin.py"],
        )
        """);
    String pythonTop =
        analysisMock.pySupport().createPythonTopEntryPoint(mockToolsConfig, "//pkg:my_py_runtime");
    useConfiguration("--incompatible_use_python_toolchains=false", "--python_top=" + pythonTop);
    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:pybin"));
    assertThat(path).isEqualTo("/system/python3");
  }

  @Test
  public void runtimeSetByPythonPath() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "pybin",
            srcs = ["pybin.py"],
        )
        """);
    useConfiguration("--incompatible_use_python_toolchains=false", "--python_path=/system/python2");
    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:pybin"));
    assertThat(path).isEqualTo("/system/python2");
  }

  @Test
  public void runtimeDefaultsToPythonSystemCommand() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "pybin",
            srcs = ["pybin.py"],
        )
        """);
    useConfiguration("--incompatible_use_python_toolchains=false");
    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:pybin"));
    assertThat(path).isEqualTo("python");
  }

  @Test
  public void pythonTopTakesPrecedenceOverPythonPath() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_runtime"),
        getPyLoad("py_binary"),
        """
        py_runtime(
            name = "my_py_runtime",
            interpreter_path = "/system/python3",
            python_version = "PY3",
        )

        py_binary(
            name = "pybin",
            srcs = ["pybin.py"],
        )
        """);
    String pythonTop =
        analysisMock.pySupport().createPythonTopEntryPoint(mockToolsConfig, "//pkg:my_py_runtime");
    useConfiguration(
        "--incompatible_use_python_toolchains=false",
        "--python_top=" + pythonTop,
        "--python_path=/better/not/be/this/one");
    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:pybin"));
    assertThat(path).isEqualTo("/system/python3");
  }

  // TODO(brandjon): Move generic toolchain tests that don't access legacy behavior to
  // PyExecutableConfiguredtargetTestBase. Asserting on the chosen PyRuntimeInfo is problematic to
  // do at analysis time though. It's easier in this test because we know the PythonSemantics is
  // BazelPythonSemantics.

  /** Adds toolchain definitions to a //toolchains package, for user by the below tests. */
  private void defineToolchains() throws Exception {
    scratch.file(
        "toolchains/BUILD",
        getPyLoad("py_runtime"),
        getPyLoad("py_runtime_pair"),
        "py_runtime(",
        "    name = 'py3_runtime',",
        "    interpreter_path = '/system/python3',",
        "    python_version = 'PY3',",
        "    stub_shebang = '#!/usr/bin/env python3',",
        ")",
        "py_runtime_pair(",
        "    name = 'py_runtime_pair',",
        "    py3_runtime = ':py3_runtime',",
        ")",
        "toolchain(",
        "    name = 'py_toolchain',",
        "    toolchain = ':py_runtime_pair',",
        "    toolchain_type = '" + TOOLCHAIN_TYPE + "',",
        ")",
        "py_runtime_pair(",
        "    name = 'py_runtime_pair_for_py3_only',",
        "    py3_runtime = ':py3_runtime',",
        ")",
        "toolchain(",
        "    name = 'py_toolchain_for_py3_only',",
        "    toolchain = ':py_runtime_pair_for_py3_only',",
        "    toolchain_type = '" + TOOLCHAIN_TYPE + "',",
        ")");
  }

  @Test
  public void runtimeObtainedFromToolchain() throws Exception {
    defineToolchains();
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "py3_bin",
            srcs = ["py3_bin.py"],
            python_version = "PY3",
        )
        """);
    useConfiguration(
        "--incompatible_use_python_toolchains=true",
        "--extra_toolchains=//toolchains:py_toolchain");

    ConfiguredTarget py3 = getConfiguredTarget("//pkg:py3_bin");

    String py3Path = getInterpreterPathFromStub(py3);
    assertThat(py3Path).isEqualTo("/system/python3");

    String py3Shebang = getShebangFromStub(py3);
    assertThat(py3Shebang).isEqualTo("#!/usr/bin/env python3");
  }

  @Test
  public void toolchainCanOmitUnusedRuntimeVersion() throws Exception {
    defineToolchains();
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "py3_bin",
            srcs = ["py3_bin.py"],
            python_version = "PY3",
        )
        """);
    useConfiguration(
        "--incompatible_use_python_toolchains=true",
        "--extra_toolchains=//toolchains:py_toolchain_for_py3_only");

    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:py3_bin"));
    assertThat(path).isEqualTo("/system/python3");
  }

  @Test
  public void toolchainTakesPrecedenceOverLegacyFlags() throws Exception {
    defineToolchains();
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "py3_bin",
            srcs = ["py3_bin.py"],
            python_version = "PY3",
        )
        """);
    useConfiguration(
        "--incompatible_use_python_toolchains=true",
        "--extra_toolchains=//toolchains:py_toolchain",
        "--python_path=/better/not/be/this/one");

    String path = getInterpreterPathFromStub(getConfiguredTarget("//pkg:py3_bin"));
    assertThat(path).isEqualTo("/system/python3");
  }

  /**
   * Creates a custom toolchain at //toolchains:custom that has the given lines in its rule
   * implementation function.
   */
  private void defineCustomToolchain(String... lines) throws Exception {
    String indentedBody;
    if (lines.length == 0) {
      indentedBody = "    pass";
    } else {
      indentedBody = "    " + join(lines).replace("\n", "\n    ");
    }
    scratch.file(
        "toolchains/rules.bzl",
        getPyLoad("PyRuntimeInfo"),
        "def _custom_impl(ctx):",
        indentedBody,
        "custom = rule(",
        "    implementation = _custom_impl",
        ")");
    scratch.file(
        "toolchains/BUILD",
        "load(':rules.bzl', 'custom')",
        "custom(",
        "    name = 'custom',",
        ")",
        "toolchain(",
        "    name = 'custom_toolchain',",
        "    toolchain = ':custom',",
        "    toolchain_type = '" + TOOLCHAIN_TYPE + "',",
        ")");
  }

  /**
   * Defines a py_binary target at //pkg:pybin, configures it to use the custom toolchain
   * //toolchains:custom, and attempts to retrieve it with {@link #getConfiguredTarget}.
   */
  private void analyzePyBinaryTargetUsingCustomToolchain() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "pybin",
            srcs = ["pybin.py"],
            python_version = "PY3",
        )
        """);
    useConfiguration(
        "--incompatible_use_python_toolchains=true",
        "--extra_toolchains=//toolchains:custom_toolchain");
    getConfiguredTarget("//pkg:pybin");
  }

  @Test
  public void toolchainInfoFieldIsMissing() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineCustomToolchain("return platform_common.ToolchainInfo()", "");
    analyzePyBinaryTargetUsingCustomToolchain();
    assertContainsEvent(Pattern.compile("py3_runtime.*missing"));
  }

  @Test
  public void toolchainInfoFieldHasBadVersion() throws Exception {
    reporter.removeHandler(failFastHandler);
    defineCustomToolchain(
        "return platform_common.ToolchainInfo(",
        "    py3_runtime = PyRuntimeInfo(",
        "        interpreter_path = '/system/python3',",
        // python_version is erroneously set to PY2 for the PY3 field.
        "        python_version = 'PY2'),",
        ")");
    analyzePyBinaryTargetUsingCustomToolchain();
    assertContainsEvent(Pattern.compile("py3_runtime.*python_version.*got.*PY2"));
  }

  @Test
  public void explicitInitPy_CanBeGloballyEnabled() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        join(
            "py_binary(", //
            "    name = 'foo',",
            "    srcs = ['foo.py'],",
            ")"));
    useConfiguration("--incompatible_default_to_explicit_init_py=true");
    assertThat(getDefaultRunfiles(getConfiguredTarget("//pkg:foo")).getEmptyFilenames()).isEmpty();
  }

  @Test
  public void explicitInitPy_CanBeSelectivelyDisabled() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        join(
            "py_binary(", //
            "    name = 'foo',",
            "    srcs = ['foo.py'],",
            "    legacy_create_init = True,",
            ")"));
    useConfiguration("--incompatible_default_to_explicit_init_py=true");
    assertThat(getDefaultRunfiles(getConfiguredTarget("//pkg:foo")).getEmptyFilenames())
        .containsExactly(PathFragment.create("pkg/__init__.py"));
  }

  @Test
  public void explicitInitPy_CanBeGloballyDisabled() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        join(
            "py_binary(", //
            "    name = 'foo',",
            "    srcs = ['foo.py'],",
            ")"));
    useConfiguration("--incompatible_default_to_explicit_init_py=false");
    assertThat(getDefaultRunfiles(getConfiguredTarget("//pkg:foo")).getEmptyFilenames())
        .containsExactly(PathFragment.create("pkg/__init__.py"));
  }

  @Test
  public void explicitInitPy_CanBeSelectivelyEnabled() throws Exception {
    scratch.file(
        "pkg/BUILD",
        getPyLoad("py_binary"),
        join(
            "py_binary(", //
            "    name = 'foo',",
            "    srcs = ['foo.py'],",
            "    legacy_create_init = False,",
            ")"));
    useConfiguration("--incompatible_default_to_explicit_init_py=false");
    assertThat(getDefaultRunfiles(getConfiguredTarget("//pkg:foo")).getEmptyFilenames()).isEmpty();
  }

  @Test
  public void packageNameCanHaveHyphen() throws Exception {
    scratch.file(
        "pkg-hyphenated/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "foo",
            srcs = ["foo.py"],
        )
        """);
    assertThat(getConfiguredTarget("//pkg-hyphenated:foo")).isNotNull();
    assertNoEvents();
  }

  @Test
  public void srcsPackageNameCanHaveHyphen() throws Exception {
    scratch.file(
        "pkg-hyphenated/BUILD", //
        "exports_files(['bar.py'])");
    scratch.file(
        "otherpkg/BUILD",
        getPyLoad("py_binary"),
        """
        py_binary(
            name = "foo",
            srcs = [
                "foo.py",
                "//pkg-hyphenated:bar.py",
            ],
        )
        """);
    assertThat(getConfiguredTarget("//otherpkg:foo")).isNotNull();
    assertNoEvents();
  }
}
