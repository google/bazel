// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for building external packages.
 */
@RunWith(JUnit4.class)
public class ExternalPackageTest extends BuildViewTestCase {

  private Path workspacePath;

  @Before
  public final void setWorkspacePath() throws Exception {
    workspacePath = rootDirectory.getRelative("WORKSPACE");
  }

  @Test
  public void testMultipleRulesWithSameName() throws Exception {
    TestUtils.writeLines(
        workspacePath,
        "local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        ")",
        "new_local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        "    build_file = 'baz',",
        ")");

    invalidatePackages(/*alsoConfigs=*/false);
    // Make sure the second rule "wins."
    assertThat(getTarget("//external:my_rule").getTargetKind())
        .isEqualTo("new_local_repository rule");
  }

  @Test
  public void testOverridingBindRules() throws Exception {
    TestUtils.writeLines(
        workspacePath,
        "bind(",
        "    name = 'my_rule',",
        "    actual = '//foo:bar',",
        ")",
        "new_local_repository(",
        "    name = 'my_rule',",
        "    path = '/foo/bar',",
        "    build_file = 'baz',",
        ")");

    invalidatePackages(/*alsoConfigs=*/false);
    // Make sure the second rule "wins."
    assertThat(getTarget("//external:my_rule").getTargetKind())
        .isEqualTo("new_local_repository rule");
  }

  @Test
  public void testBindToConfigSetting() throws Exception {
    TestUtils.appendLines(
        workspacePath, "bind(", "    name = 'condition',", "    actual = '//:setting',", ")");
    TestUtils.writeLines(
        rootDirectory.getRelative("BUILD"),
        "load('@rules_java//java:defs.bzl', 'java_library')",
        "config_setting(",
        "    name = 'setting',",
        "    values = {'define': 'foo=bar'},",
        ")",
        "java_library(",
        "    name = 'a',",
        "    runtime_deps = select({",
        "        '//external:condition': [':b'],",
        "        '//conditions:default': [':c'],",
        "    }),",
        ")",
        "java_library(name = 'b', srcs = [])",
        "java_library(name = 'c', srcs = [])");
    invalidatePackages();
    useConfiguration("--define", "foo=bar");
    ConfiguredTargetAndData ctad = getConfiguredTargetAndData("//:a");
    ConfiguredAttributeMapper configuredAttributeMapper =
        getMapperFromConfiguredTargetAndTarget(ctad);
    assertThat(configuredAttributeMapper.get("runtime_deps", BuildType.LABEL_LIST))
        .containsExactly(Label.parseCanonical("//:b"));
  }
}
