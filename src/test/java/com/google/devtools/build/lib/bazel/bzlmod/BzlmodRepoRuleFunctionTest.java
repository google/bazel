// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BzlmodRepoRuleFunction}. */
@RunWith(JUnit4.class)
public final class BzlmodRepoRuleFunctionTest extends BuildViewTestCase {

  @Test
  public void testRepoSpec_bazelModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BzlmodRepoRuleValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/workspace/modules/ccc+2.0");
  }

  @Test
  public void testRepoSpec_nonRegistryOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "local_path_override(module_name='ccc',path='/foo/bar/C')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BzlmodRepoRuleValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/foo/bar/C");
  }

  @Test
  public void testRepoSpec_singleVersionOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "single_version_override(",
        "  module_name='ccc',version='3.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        .addModule(createModuleKey("ccc", "3.0"), "module(name='ccc', version='3.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<BzlmodRepoRuleValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BzlmodRepoRuleValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ccc+");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/workspace/modules/ccc+3.0");
  }

  @Test
  public void testRepoSpec_multipleVersionOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='1.0')")
        .addModule(
            createModuleKey("ccc", "2.0"),
            "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='2.0')")
        .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
        .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ddd+2.0");
    EvaluationResult<BzlmodRepoRuleValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BzlmodRepoRuleValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    Rule repoRule = bzlmodRepoRuleValue.getRule();

    assertThat(repoRule.getRuleClass()).isEqualTo("local_repository");
    assertThat(repoRule.getName()).isEqualTo("ddd+2.0");
    assertThat(repoRule.getAttr("path", Type.STRING)).isEqualTo("/workspace/modules/ddd+2.0");
  }

  @Test
  public void testRepoSpec_notFound() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "module(name='aaa',version='0.1')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ss");
    EvaluationResult<BzlmodRepoRuleValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, BzlmodRepoRuleValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    BzlmodRepoRuleValue bzlmodRepoRuleValue = result.get(BzlmodRepoRuleValue.key(repo));
    assertThat(bzlmodRepoRuleValue).isEqualTo(BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE);
  }
}
