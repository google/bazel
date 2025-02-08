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

/** Tests for {@link PyInfo}. */
@RunWith(JUnit4.class)
public class PyInfoTest extends BuildViewTestCase {

  private Artifact dummyArtifact;

  @Before
  public void setUp() throws Exception {
    dummyArtifact = getSourceArtifact("dummy");
  }

  private void writeCreatePyInfo(String... lines) throws Exception {
    var builder = new StringBuilder();
    for (var line : lines) {
      builder.append("    ").append(line).append(",\n");
    }
    scratch.overwriteFile(
        "defs.bzl",
        getPyLoad("PyInfo"),
        "def _impl(ctx):",
        "    dummy_file = ctx.file.dummy_file",
        "    info = PyInfo(",
        builder.toString(),
        "    )",
        "    return [info]",
        "create_py_info = rule(implementation=_impl, attrs={",
        "  'dummy_file': attr.label(default='dummy', allow_single_file=True),",
        "})",
        "");
    scratch.overwriteFile(
        "BUILD", "load(':defs.bzl', 'create_py_info')", "create_py_info(name='subject')");
  }

  private PyInfo getPyInfo() throws Exception {
    return PyInfo.fromTarget(getConfiguredTarget("//:subject"));
  }

  /** We need this because {@code NestedSet}s don't have value equality. */
  private static void assertHasOrderAndContainsExactly(
      NestedSet<?> set, Order order, Object... values) {
    assertThat(set.getOrder()).isEqualTo(order);
    assertThat(set.toList()).containsExactly(values);
  }

  private void assertContainsError(String pattern) throws Exception {
    reporter.removeHandler(failFastHandler); // expect errors

    getConfiguredTarget("//:subject");

    // The Starlark messages are within a long multi-line traceback string, so
    // add the implicit .* for convenience.
    // NOTE: failures and events are accumulated between getConfiguredTarget() calls.
    assertContainsEvent(Pattern.compile(".*" + pattern));
  }

  @Test
  public void starlarkConstructor() throws Exception {
    writeCreatePyInfo(
        "    transitive_sources = depset(direct=[dummy_file])",
        "    uses_shared_libraries = True",
        "    imports = depset(direct=['abc'])",
        "    has_py2_only_sources = True",
        "    has_py3_only_sources = True");

    PyInfo info = getPyInfo();

    assertHasOrderAndContainsExactly(
        info.getTransitiveSourcesSet(), Order.STABLE_ORDER, dummyArtifact);
    assertThat(info.getUsesSharedLibraries()).isTrue();
    assertHasOrderAndContainsExactly(info.getImportsSet(), Order.STABLE_ORDER, "abc");
    assertThat(info.getHasPy2OnlySources()).isTrue();
    assertThat(info.getHasPy3OnlySources()).isTrue();
  }

  @Test
  public void starlarkConstructorDefaults() throws Exception {
    writeCreatePyInfo("transitive_sources = depset(direct=[dummy_file])");

    PyInfo info = getPyInfo();

    assertHasOrderAndContainsExactly(
        info.getTransitiveSourcesSet(), Order.STABLE_ORDER, dummyArtifact);
    assertThat(info.getUsesSharedLibraries()).isFalse();
    assertHasOrderAndContainsExactly(info.getImportsSet(), Order.STABLE_ORDER);
    assertThat(info.getHasPy2OnlySources()).isFalse();
    assertThat(info.getHasPy3OnlySources()).isFalse();
  }

  @Test
  public void starlarkConstructorErrors_transitiveSources_missing() throws Exception {
    writeCreatePyInfo();

    assertContainsError("missing.*argument.*transitive_sources");
  }

  @Test
  public void starlarkConstructorErrors_transitiveSources_badType() throws Exception {
    writeCreatePyInfo("transitive_sources = 'abc'");

    assertContainsError("transitive_sources.*got.*string.*want.*depset");
  }

  @Test
  public void starlarkConstructorErrors_transitiveSources_rejectsPreOrder() throws Exception {
    writeCreatePyInfo("transitive_sources = depset(direct=[dummy_file], order='preorder')");

    assertContainsError("Order.*postorder.*incompatible.*preorder");
  }

  @Test
  public void starlarkConstructorErrors_UsesSharedLibraries() throws Exception {
    writeCreatePyInfo("transitive_sources = depset()", "uses_shared_libraries = 'abc'");

    assertContainsError("uses_shared_libraries.*got.*string.*want.*bool");
  }

  @Test
  public void starlarkConstructorErrors_imports_badType() throws Exception {
    writeCreatePyInfo("transitive_sources = depset()", "imports = 'abc'");

    assertContainsError("imports.*got.*string.*want.*depset");
  }

  @Test
  public void starlarkConstructorErrors_HasPy2OnlySources() throws Exception {
    writeCreatePyInfo("transitive_sources = depset()", "has_py2_only_sources = 'abc'");

    assertContainsError("has_py2_only_sources.*got.*string.*want.*bool");
  }

  @Test
  public void starlarkConstructorErrors_HasPy3OnlySources() throws Exception {
    writeCreatePyInfo("transitive_sources = depset()", "has_py3_only_sources = 'abc'");

    assertContainsError("has_py3_only_sources.*got.*string.*want.*bool");
  }
}
