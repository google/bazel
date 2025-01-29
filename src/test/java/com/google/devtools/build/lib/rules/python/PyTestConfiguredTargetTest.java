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
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.analysis.test.ExecutionInfo;
import com.google.devtools.build.lib.testutil.TestConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@code py_test}. */
@RunWith(JUnit4.class)
public class PyTestConfiguredTargetTest extends PyExecutableConfiguredTargetTestBase {
  public PyTestConfiguredTargetTest() {
    super("py_test");
  }

  @Test
  public void macRequiresDarwinForExecution() throws Exception {
    getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "darwin_x86_64");
    // The default mock environment doesn't have platform_mappings (which map --cpu to a platform),
    // nor does it have Apple platforms defined, so we have to set one up ourselves.
    mockToolsConfig.create(
        "platforms/BUILD",
        "platform(",
        "  name = 'darwin_x86_64',",
        "  constraint_values = [",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:macos',",
        "    '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64',",
        "  ],",
        ")");
    useConfiguration(
        "--platforms=//platforms:darwin_x86_64",
        "--extra_execution_platforms=//platforms:darwin_x86_64");
    scratch.file(
        "pkg/BUILD", //
        getPyLoad("py_test"),
        "py_test(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        ")");
    ExecutionInfo executionInfo =
        (ExecutionInfo) getOkPyTarget("//pkg:foo").get(ExecutionInfo.PROVIDER.getKey());
    assertThat(executionInfo).isNotNull();
    assertThat(executionInfo.getExecutionInfo()).containsKey(ExecutionRequirements.REQUIRES_DARWIN);
  }

  @Test
  public void nonMacDoesNotRequireDarwinForExecution() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        getPyLoad("py_test"),
        "py_test(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        ")");
    ExecutionInfo executionInfo =
        (ExecutionInfo) getOkPyTarget("//pkg:foo").get(ExecutionInfo.PROVIDER.getKey());
    if (executionInfo != null) {
      assertThat(executionInfo.getExecutionInfo())
          .doesNotContainKey(ExecutionRequirements.REQUIRES_DARWIN);
    }
  }

  @Test
  // b/382441996
  public void nativePyTestReportsAnError() throws Exception {
    scratch.file(
        "pkg/BUILD", //
        "py_test(",
        "    name = 'foo',",
        "    srcs = ['foo.py'],",
        ")");

    AssertionError error =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//pkg:foo"));

    if (analysisMock.isThisBazel()) {
      assertThat(error).hasMessageThat().contains("name 'py_test' is not defined");
    } else {
      assertThat(error).hasMessageThat().contains("Rule is unimplemented.");
    }
  }
}
