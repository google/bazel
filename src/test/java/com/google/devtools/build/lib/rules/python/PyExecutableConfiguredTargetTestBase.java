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
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FailAction;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import org.junit.Test;

/** Tests that are common to {@code py_binary} and {@code py_test}. */
public abstract class PyExecutableConfiguredTargetTestBase extends PyBaseConfiguredTargetTestBase {

  private final String ruleName;

  protected PyExecutableConfiguredTargetTestBase(String ruleName) {
    super(ruleName);
    this.ruleName = ruleName;
  }

  /**
   * Returns the configured target with the given label while asserting that, if it is an executable
   * target, the executable is not produced by {@link FailAction}.
   *
   * <p>This serves as a drop-in replacement for {@link #getConfiguredTarget} that will also catch
   * unexpected deferred failures (e.g. {@code srcs_versions} validation failures) in {@code
   * py_binary} and {@code py_test} targets.
   */
  protected ConfiguredTarget getOkPyTarget(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    // It can be null without events due to b/26382502.
    Preconditions.checkNotNull(target, "target was null (is it misspelled or in error?)");
    Artifact executable = getExecutable(target);
    if (executable != null) {
      Action action = getGeneratingAction(executable);
      if (action instanceof FailAction) {
        throw new AssertionError(
            String.format(
                "execution of target would fail with error '%s'",
                ((FailAction) action).getErrorMessage()));
      }
    }
    return target;
  }

  /**
   * Gets the configured target for an executable Python rule (generally {@code py_binary} or {@code
   * py_test}) and asserts that it produces a deferred error via {@link FailAction}.
   *
   * @return the deferred error string
   */
  protected String getPyExecutableDeferredError(String label) throws Exception {
    ConfiguredTarget target = getConfiguredTarget(label);
    // It can be null without events due to b/26382502.
    Preconditions.checkNotNull(target, "target was null (is it misspelled or in error?)");
    Artifact executable = getExecutable(target);
    Preconditions.checkNotNull(
        executable, "executable was null (is this a py_binary/py_test target?)");
    Action action = getGeneratingAction(executable);
    assertThat(action).isInstanceOf(FailAction.class);
    return ((FailAction) action).getErrorMessage();
  }

  /** Asserts that a configured target has the given Python version. */
  protected void assertPythonVersionIs(String targetName, PythonVersion version) throws Exception {
    assertThat(getPythonVersion(getOkPyTarget(targetName))).isEqualTo(version);
  }

  /**
   * Sets the configuration, then asserts that a configured target has the given Python version.
   *
   * <p>The configuration is given as a series of "--flag=value" strings.
   */
  protected void assertPythonVersionIs_UnderNewConfig(
      String targetName, PythonVersion version, String... flags) throws Exception {
    useConfiguration(flags);
    assertPythonVersionIs(targetName, version);
  }

  /**
   * Asserts that a configured target has the given Python version under multiple configurations.
   *
   * <p>The configurations are given as a series of arrays of "--flag=value" strings.
   *
   * <p>This destructively changes the current configuration.
   */
  protected void assertPythonVersionIs_UnderNewConfigs(
      String targetName, PythonVersion version, String[]... configs) throws Exception {
    for (String[] config : configs) {
      useConfiguration(config);
      assertWithMessage(String.format("Under config '%s'", Joiner.on(" ").join(config)))
          .that(getPythonVersion(getOkPyTarget(targetName)))
          .isEqualTo(version);
    }
  }

  private static String join(String... lines) {
    return String.join("\n", lines);
  }

  private String ruleDeclWithPyVersionAttr(String name, String version) {
    return join(
        ruleName + "(",
        "    name = '" + name + "',",
        "    srcs = ['" + name + ".py'],",
        "    python_version = '" + version + "'",
        ")");
  }

  @Test
  public void pyRuntimeInfoIsPresent() throws Exception {
    useConfiguration("--incompatible_use_python_toolchains=true");
    scratch.file(
        "pkg/BUILD", //
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = [':foo.py'],",
        ")");
    assertThat(PyRuntimeInfo.fromTarget(getConfiguredTarget("//pkg:foo"))).isNotNull();
  }

  @Test
  public void versionAttr_GoodValue() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        bzlLoad,
        ruleDeclWithPyVersionAttr("foo", "PY3"));
    getOkPyTarget("//pkg:foo");
    assertNoEvents();
  }

  @Test
  public void versionAttrWorks_WhenSameAsDefaultValue() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        bzlLoad,
        ruleDeclWithPyVersionAttr("foo", "PY3"));

    assertPythonVersionIs("//pkg:foo", PythonVersion.PY3);
  }

  @Test
  public void targetInPackageWithHyphensOkIfSrcsFromOtherPackage() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        "exports_files(['foo.py', 'bar.py'])");
    scratch.file(
        "pkg-with-hyphens/BUILD",
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    main = '//pkg:foo.py',",
        "    srcs = ['//pkg:foo.py', '//pkg:bar.py'])");
    getOkPyTarget("//pkg-with-hyphens:foo"); // should not fail
  }

  @Test
  public void targetInPackageWithHyphensOkIfOnlyExplicitMainHasHyphens() throws Exception {
    scratch.file(
        "pkg-with-hyphens/BUILD",
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    main = 'foo.py',",
        "    srcs = ['foo.py'])");
    getOkPyTarget("//pkg-with-hyphens:foo"); // should not fail
  }

  @Test
  public void targetInPackageWithHyphensOkIfOnlyImplicitMainHasHyphens() throws Exception {
    scratch.file(
        "pkg-with-hyphens/BUILD", //
        bzlLoad,
        ruleName + "(",
        "    name = 'foo',",
        "    srcs = ['foo.py'])");
    getOkPyTarget("//pkg-with-hyphens:foo"); // should not fail
  }
}
