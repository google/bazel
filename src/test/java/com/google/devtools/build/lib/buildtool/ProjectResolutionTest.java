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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.PROJECT_FILE_NAME;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.Project;
import com.google.devtools.build.lib.analysis.ProjectResolutionException;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests how Bazel finds the right {@link Project} for a build.
 *
 * <p>This is an integration test between {@link Project} and the build process. It specifically
 * tests how builds call {@link Project} and use the results meaningfully. For direct unit tests on
 * projects, use {@link ProjectTest}.
 */
@RunWith(JUnit4.class)
public class ProjectResolutionTest extends BuildViewTestCase {
  @Test
  public void buildWithNoProjectFiles() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//pkg:f")), getSkyframeExecutor(), reporter))
        .isNull();
  }

  @Test
  public void buildWithOneProjectFile() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file("pkg/" + PROJECT_FILE_NAME, "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//pkg:f")), getSkyframeExecutor(), reporter))
        .isEqualTo(Label.parseCanonical("//pkg:" + PROJECT_FILE_NAME));
  }

  @Test
  public void buildWithTwoProjectFiles() throws Exception {
    scratch.file("foo/bar/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file("foo/BUILD");
    scratch.file("foo/" + PROJECT_FILE_NAME, "project = {}");
    scratch.file("foo/bar/" + PROJECT_FILE_NAME, "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//foo/bar:f")),
                getSkyframeExecutor(),
                reporter))
        .isEqualTo(Label.parseCanonical("//foo/bar:PROJECT.scl"));
  }

  @Test
  public void twoTargetsSameProjectFile() throws Exception {
    scratch.file("foo/bar/BUILD", "genrule(name='child', cmd = '', srcs=[], outs=['c.out'])");
    scratch.file("foo/BUILD", "genrule(name='parent', cmd = '', srcs=[], outs=['p.out'])");
    scratch.file("foo/" + PROJECT_FILE_NAME, "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(
                    Label.parseCanonical("//foo:parent"), Label.parseCanonical("//foo/bar:child")),
                getSkyframeExecutor(),
                reporter))
        .isEqualTo(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME));
  }

  @Test
  public void twoTargetsDifferentProjectFiles() throws Exception {
    scratch.file("foo/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['f.out'])");
    scratch.file("bar/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['g.out'])");
    scratch.file("foo/" + PROJECT_FILE_NAME, "project = {}");
    scratch.file("bar/" + PROJECT_FILE_NAME, "project = {}");

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(
                        Label.parseCanonical("//foo:f"), Label.parseCanonical("//bar:g")),
                    getSkyframeExecutor(),
                    reporter));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            """
This build doesn't support automatic project resolution. Targets have different project settings:
  - //foo:f -> //foo:PROJECT.scl
  - //bar:g -> //bar:PROJECT.scl\
""");
  }

  @Test
  public void twoTargetsOnlyOneHasProjectFile() throws Exception {}

  @Test
  public void innermostPackageIsAParentDirectory() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file("pkg/" + PROJECT_FILE_NAME, "project = {}");
    scratch.file("pkg/subdir/not_a_build_file");
    // Doesn't count because it's not colocated with a BUILD file:
    scratch.file("pkg/subdir" + PROJECT_FILE_NAME, "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//pkg/subdir:fake_target")),
                getSkyframeExecutor(),
                reporter))
        .isEqualTo(Label.parseCanonical("//pkg:" + PROJECT_FILE_NAME));
  }

  @Test
  public void aliasProjectFile() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        """);
    scratch.file("canonical/BUILD");
    scratch.file("canonical/PROJECT.scl", "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//pkg:f")), getSkyframeExecutor(), reporter))
        .isEqualTo(Label.parseCanonical("//canonical:PROJECT.scl"));
  }

  @Test
  public void aliasActualAttributeWrongType() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": ["//canonical:PROJECT.scl"],
        }
        """);

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(Label.parseCanonical("//pkg:f")),
                    getSkyframeExecutor(),
                    reporter));
    assertThat(thrown)
        .hasMessageThat()
        .contains("project[\"actual\"]: expected string, got [\"//canonical:PROJECT.scl\"]");
  }

  @Test
  public void aliasWithExtraProjectData() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
          "extra": "data",
        }
        """);

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(Label.parseCanonical("//pkg:f")),
                    getSkyframeExecutor(),
                    reporter));
    assertThat(thrown)
        .hasMessageThat()
        .contains("project[\"actual\"] is present, but other keys are present as well");
  }

  @Test
  public void aliasWithExtraGlobalSymbol() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        other_global = {}
        """);

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(Label.parseCanonical("//pkg:f")),
                    getSkyframeExecutor(),
                    reporter));
    // This isn't actually specific to aliases: no PROJECT.scl fine can define non-"project"
    // globals. Still want to check here since aliases have their own reason for this: make sure
    // they're pure aliases and nothing else.
    assertThat(thrown)
        .hasMessageThat()
        .contains("project global variable is present, but other globals are present as well");
  }

  @Test
  public void aliasRefDoesntExist() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        """);
    scratch.file("canonical/BUILD");

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(Label.parseCanonical("//pkg:f")),
                    getSkyframeExecutor(),
                    reporter));
    // This isn't actually specific to aliases: no PROJECT.scl fine can define non-"project"
    // globals. Still want to check here since aliases have their own reason for this: make sure
    // they're pure aliases and nothing else.
    assertThat(thrown)
        .hasMessageThat()
        .contains("cannot load '//canonical:PROJECT.scl': no such file");
  }

  @Test
  public void aliasToAlias() throws Exception {
    scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg/PROJECT.scl",
        """
        project = {
          "actual": "//pkg2:PROJECT.scl",
        }
        """);
    scratch.file("pkg2/BUILD");
    scratch.file(
        "pkg2/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        """);
    scratch.file("canonical/BUILD");
    scratch.file("canonical/PROJECT.scl", "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(Label.parseCanonical("//pkg:f")), getSkyframeExecutor(), reporter))
        .isEqualTo(Label.parseCanonical("//canonical:PROJECT.scl"));
  }

  @Test
  public void sameProjectFileAfterAliasResolution() throws Exception {
    scratch.file("pkg1/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg1/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        """);
    scratch.file("pkg2/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg2/PROJECT.scl",
        """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        """);
    scratch.file("canonical/BUILD");
    scratch.file("canonical/PROJECT.scl", "project = {}");

    assertThat(
            Project.getProjectFile(
                ImmutableList.of(
                    Label.parseCanonical("//pkg1:f"), Label.parseCanonical("//pkg2:g")),
                getSkyframeExecutor(),
                reporter))
        .isEqualTo(Label.parseCanonical("//canonical:PROJECT.scl"));
  }

  @Test
  public void differentProjectFilesAfterAliasResolution() throws Exception {
    scratch.file("pkg1/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg1/PROJECT.scl",
        """
        project = {
          "actual": "//canonical1:PROJECT.scl",
        }
        """);
    scratch.file("pkg2/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['a.out'])");
    scratch.file(
        "pkg2/PROJECT.scl",
        """
        project = {
          "actual": "//canonical2:PROJECT.scl",
        }
        """);
    scratch.file("canonical1/BUILD");
    scratch.file("canonical1/PROJECT.scl", "project = {}");
    scratch.file("canonical2/BUILD");
    scratch.file("canonical2/PROJECT.scl", "project = {}");

    var thrown =
        assertThrows(
            ProjectResolutionException.class,
            () ->
                Project.getProjectFile(
                    ImmutableList.of(
                        Label.parseCanonical("//pkg1:f"), Label.parseCanonical("//pkg2:g")),
                    getSkyframeExecutor(),
                    reporter));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            """
This build doesn't support automatic project resolution. Targets have different project settings:
  - //pkg1:f -> //canonical1:PROJECT.scl
  - //pkg2:g -> //canonical2:PROJECT.scl\
""");
  }

  // TODO: b/382265245 - handle aliases that self-reference or produce cycles.
}
