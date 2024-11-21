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

package com.google.devtools.build.lib.rules.python;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.python.PythonTestUtils.getPyLoad;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.Order;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PyRuntimeInfo}. */
@RunWith(JUnit4.class)
public class PyRuntimeInfoTest extends BuildViewTestCase {

  // Copied from providers.bzl for ease of reference.
  private static final String EXPECTED_DEFAULT_STUB_SHEBANG = "#!/usr/bin/env python3";

  private Artifact dummyInterpreter;
  private Artifact dummyFile;

  @Before
  public void setUp() throws Exception {
    dummyInterpreter = getSourceArtifact("dummy_interpreter");
    dummyFile = getSourceArtifact("dummy_file");
  }

  private void writeCreatePyRuntimeInfo(String... lines) throws Exception {
    var builder = new StringBuilder();
    for (var line : lines) {
      builder.append("    ").append(line).append(",\n");
    }
    scratch.overwriteFile(
        "defs.bzl",
        getPyLoad("PyRuntimeInfo"),
        "def _impl(ctx):",
        "    dummy_file = ctx.file.dummy_file",
        "    dummy_interpreter = ctx.file.dummy_interpreter",
        "    info = PyRuntimeInfo(",
        builder.toString(),
        "    )",
        "    return [info]",
        "create_py_runtime_info = rule(implementation=_impl, attrs={",
        "  'dummy_file': attr.label(default='dummy_file', allow_single_file=True),",
        "  'dummy_interpreter': attr.label(default='dummy_interpreter', allow_single_file=True),",
        "})",
        "");
    scratch.overwriteFile(
        "BUILD",
        "load(':defs.bzl', 'create_py_runtime_info')",
        "create_py_runtime_info(name='subject')");
  }

  private PyRuntimeInfo getPyRuntimeInfo() throws Exception {
    return PyRuntimeInfo.fromTarget(getConfiguredTarget("//:subject"));
  }

  private void assertContainsError(String pattern) throws Exception {
    reporter.removeHandler(failFastHandler); // expect errors

    getConfiguredTarget("//:subject");

    // The Starlark messages are within a long multi-line traceback string, so
    // add the implicit .* for convenience.
    // NOTE: failures and events are accumulated between getConfiguredTarget() calls.
    assertContainsEvent(Pattern.compile(".*" + pattern));
  }

  /** We need this because {@code NestedSet}s don't have value equality. */
  private static void assertHasOrderAndContainsExactly(
      NestedSet<?> set, Order order, Object... values) {
    assertThat(set.getOrder()).isEqualTo(order);
    assertThat(set.toList()).containsExactly(values);
  }

  @Test
  public void starlarkConstructor_inBuildRuntime() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter = dummy_interpreter",
        "files = depset([dummy_file])",
        "python_version = 'PY3'",
        "bootstrap_template = dummy_file");

    PyRuntimeInfo info = getPyRuntimeInfo();

    assertThat(info.getInterpreterPathString()).isNull();
    assertThat(info.getInterpreter()).isEqualTo(dummyInterpreter);
    assertHasOrderAndContainsExactly(info.getFiles(), Order.STABLE_ORDER, dummyFile);
    assertThat(info.getPythonVersion()).isEqualTo(PythonVersion.PY3);
    assertThat(info.getStubShebang()).isEqualTo(EXPECTED_DEFAULT_STUB_SHEBANG);
    assertThat(info.getBootstrapTemplate()).isEqualTo(dummyFile);
  }

  @Test
  public void starlarkConstructor_platformRuntime() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter_path = '/system/interpreter'",
        "python_version = 'PY3'",
        "bootstrap_template = dummy_file");

    PyRuntimeInfo info = getPyRuntimeInfo();

    assertThat(info.getInterpreterPathString()).isEqualTo("/system/interpreter");
    assertThat(info.getInterpreter()).isNull();
    assertThat(info.getFiles()).isNull();
    assertThat(info.getPythonVersion()).isEqualTo(PythonVersion.PY3);
    assertThat(info.getStubShebang()).isEqualTo(EXPECTED_DEFAULT_STUB_SHEBANG);
  }

  @Test
  public void starlarkConstructor_customShebang() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter_path = '/system/interpreter'",
        "python_version = 'PY2'",
        "stub_shebang = '#!/usr/bin/custom'",
        "bootstrap_template = dummy_file");

    PyRuntimeInfo info = getPyRuntimeInfo();

    assertThat(info.getStubShebang()).isEqualTo("#!/usr/bin/custom");
  }

  @Test
  public void starlarkConstructor_filesDefaultsToEmpty() throws Exception {
    writeCreatePyRuntimeInfo(
        "    interpreter = dummy_interpreter",
        "    python_version = 'PY2'",
        "    bootstrap_template = dummy_file");

    PyRuntimeInfo info = getPyRuntimeInfo();

    assertHasOrderAndContainsExactly(info.getFiles(), Order.STABLE_ORDER);
  }

  @Test
  public void starlarkConstructorErrors_inBuildXorPlatform_noInterpreter() throws Exception {
    writeCreatePyRuntimeInfo("python_version = 'PY3'");

    assertContainsError("exactly one of.*interpreter.*interpreter_path.*must be specified");
  }

  @Test
  public void starlarkConstructorErrors_inBuildXorPlatform_bothInterpreters() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter_path = '/system/interpreter'",
        "interpreter = dummy_interpreter",
        "python_version = 'PY2'");

    assertContainsError("exactly one of.*interpreter.*interpreter_path.*must be specified");
  }

  @Test
  public void starlarkConstructorErrors_files_invalidValue() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter = dummy_interpreter", //
        "files = 'abc'",
        "python_version = 'PY2'");

    assertContainsError("invalid files:.*got.*string.*want.*depset");
  }

  @Test
  public void starlarkConstructorErrors_files_cannotSpecify() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter_path = '/system/interpreter'",
        "files = depset([dummy_file])",
        "python_version = 'PY2'");

    assertContainsError("cannot specify 'files' if 'interpreter_path' is given");
  }

  @Test
  public void starlarkConstructorErrors_pythonVersion_missingArg() throws Exception {
    writeCreatePyRuntimeInfo("interpreter_path = '/system/interpreter'");

    assertContainsError("missing.*argument: python_version");
  }

  @Test
  public void starlarkConstructorErrors_pythonVersion_invalidValue() throws Exception {
    writeCreatePyRuntimeInfo(
        "interpreter_path = '/system/interpreter'", //
        "python_version = 'not a Python version'");

    assertContainsError("invalid python_version");
  }
}
