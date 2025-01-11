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
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import net.starlark.java.eval.Dict;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BuildConfigurationValue}'s integration with Starlark. */
@RunWith(JUnit4.class)
public final class BuildConfigurationStarlarkTest extends BuildViewTestCase {

  @Test
  public void testStarlarkWithTestEnvOptions() throws Exception {
    useConfiguration("--test_env=TEST_ENV_VAR=my_value");
    scratch.file("examples/rule/BUILD");
    scratch.file(
        "examples/rule/config_test.bzl",
        """
        MyInfo = provider()

        def _test_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(out, "exit 0", is_executable = True)
            return [
                DefaultInfo(executable = out),
                MyInfo(test_env = ctx.configuration.test_env),
            ]

        my_test = rule(
            implementation = _test_rule_impl,
            attrs = {},
            test = True,
        )
        """);

    scratch.file(
        "examples/config_starlark/BUILD",
        """
        load("//examples/rule:config_test.bzl", "my_test")

        package(default_visibility = ["//visibility:public"])

        my_test(
            name = "my_target",
        )
        """);

    ConfiguredTarget starlarkTarget = getConfiguredTarget("//examples/config_starlark:my_target");
    Provider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonical("//examples/rule:config_test.bzl")), "MyInfo");
    StructImpl myInfo = (StructImpl) starlarkTarget.get(key);
    assertThat(((Dict) myInfo.getValue("test_env")).get("TEST_ENV_VAR")).isEqualTo("my_value");
  }

  @Test
  public void testIsToolConfigurationIsBlocked() throws Exception {
    scratch.file(
        "example/BUILD",
        """
        load(":rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);

    scratch.file(
        "example/rule.bzl",
        """
        def _impl(ctx):
            ctx.configuration.is_tool_configuration()
            return [DefaultInfo()]

        custom_rule = rule(implementation = _impl)
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//example:custom"));
    assertThat(e).hasMessageThat().contains("file '//example:rule.bzl' cannot use private API");
  }

  @Test
  public void testRunfilesEnabledIsPrivateApi() throws Exception {
    scratch.file(
        "example/BUILD",
        """
        load(":rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        """);

    scratch.file(
        "example/rule.bzl",
        """
        def _impl(ctx):
            ctx.configuration.runfiles_enabled()
            return [DefaultInfo()]

        custom_rule = rule(implementation = _impl)
        """);

    AssertionError e =
        assertThrows(AssertionError.class, () -> getConfiguredTarget("//example:custom"));
    assertThat(e).hasMessageThat().contains("file '//example:rule.bzl' cannot use private API");
  }
}
