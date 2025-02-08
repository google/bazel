// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.PathFragmentPrefixTrie;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProjectFunctionTest extends BuildViewTestCase {

  @Before
  public void setUp() throws Exception {
    setBuildLanguageOptions("--experimental_enable_scl_dialect=true");
  }

  @Test
  public void projectFunction_emptyFile_isValid() throws Exception {
    scratch.file("test/PROJECT.scl", "project = {}");
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isFalse();

    ProjectValue value = result.get(key);
    assertThat(value.getDefaultActiveDirectory()).isEmpty();
  }

  @Test
  public void projectFunction_returnsActiveDirectories() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": {'default': ['foo'], 'a': ['bar', '-bar/baz']},
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isFalse();

    ProjectValue value = result.get(key);
    ImmutableMap<String, PathFragmentPrefixTrie> trie =
        PathFragmentPrefixTrie.transformValues(value.getActiveDirectories());
    assertThat(trie.get("default").includes(PathFragment.create("foo"))).isTrue();
    assertThat(trie.get("default").includes(PathFragment.create("bar"))).isFalse();
    assertThat(trie.get("a").includes(PathFragment.create("bar"))).isTrue();
    assertThat(trie.get("a").includes(PathFragment.create("bar/baz"))).isFalse();
    assertThat(trie.get("a").includes(PathFragment.create("bar/qux"))).isTrue();
    assertThat(trie.get("b")).isNull();
  }

  @Test
  public void projectFunction_returnsDefaultActiveDirectories() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": { 'default': ['a', 'b/c'] },
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isFalse();

    ProjectValue value = result.get(key);
    PathFragmentPrefixTrie trie = PathFragmentPrefixTrie.of(value.getDefaultActiveDirectory());
    assertThat(trie.includes(PathFragment.create("a"))).isTrue();
    assertThat(trie.includes(PathFragment.create("b/c"))).isTrue();
    assertThat(trie.includes(PathFragment.create("d"))).isFalse();
  }

  @Test
  public void projectFunction_returnsDefaultActiveDirectories_topLevelProjectSchema()
      throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": { "default": ["a", "b/c"] }
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isFalse();

    ProjectValue value = result.get(key);
    PathFragmentPrefixTrie trie = PathFragmentPrefixTrie.of(value.getDefaultActiveDirectory());
    assertThat(trie.includes(PathFragment.create("a"))).isTrue();
    assertThat(trie.includes(PathFragment.create("b/c"))).isTrue();
    assertThat(trie.includes(PathFragment.create("d"))).isFalse();
  }

  @Test
  public void projectFunction_nonEmptyActiveDirectoriesMustHaveADefault() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": { 'foo': ['a', 'b/c'] },
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains("non-empty active_directories must contain the 'default' key");
  }

  @Test
  public void projectFunction_incorrectType() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": 42,
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .matches("expected a map of string to list of strings, got .+Int32");
  }

  @Test
  public void projectFunction_incorrectType_inList() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {
          "active_directories": { 'default': [42] },
        }
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .matches("expected a list of strings, got element of .+Int32");
  }

  @Test
  public void projectFunction_incorrectProjectType() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = 1
        """);

    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .matches("project variable: expected a map of string to objects, got .+Int32");
  }

  @Test
  public void projectFunction_incorrectProjectKeyType() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        project = {1: [] }
        """);

    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    EvaluationResult<ProjectValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .matches("project variable: expected string key, got element of .+Int32");
  }

  @Test
  public void projectFunction_catchSyntaxError() throws Exception {
    scratch.file(
        "test/PROJECT.scl",
        """
        something_is_wrong =
        """);
    scratch.file("test/BUILD");
    ProjectValue.Key key = new ProjectValue.Key(Label.parseCanonical("//test:PROJECT.scl"));

    AssertionError e =
        assertThrows(
            AssertionError.class,
            () -> SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter));
    assertThat(e).hasMessageThat().contains("syntax error at 'newline': expected expression");
  }
}
