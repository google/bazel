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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.PROJECT_FILE_NAME;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Project}. */
@RunWith(JUnit4.class)
public class ProjectTest extends AnalysisTestCase {
  @Before
  public void defineSimpleRule() throws Exception {
    scratch.file(
        "foo/defs.bzl",
        """
        simple_rule = rule(
            implementation = lambda ctx: [],
            attrs = {},
        )
        """);
  }

  @Test
  public void singleTargetNoProjects() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);

    assertThat(
            Project.findProjectFiles(
                ImmutableList.of(Label.parseCanonical("//foo/bar:s")), skyframeExecutor, reporter))
        .isEmpty();
  }

  @Test
  public void singleTargetProjectInDirectPackage() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);
    scratch.file("foo/bar/" + PROJECT_FILE_NAME);

    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(Label.parseCanonical("//foo/bar:s")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo/bar:s"),
            ImmutableList.of(Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void singleTargetProjectInParentPackage() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);
    scratch.file("foo/BUILD");
    scratch.file("foo/" + PROJECT_FILE_NAME);

    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(Label.parseCanonical("//foo/bar:s")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo/bar:s"),
            ImmutableList.of(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void singleTargetProjectInBothDirectAndParentPackages() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);
    scratch.file("foo/BUILD");
    scratch.file("foo/" + PROJECT_FILE_NAME);
    scratch.file("foo/bar/" + PROJECT_FILE_NAME);

    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(Label.parseCanonical("//foo/bar:s")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo/bar:s"),
            ImmutableList.of(
                Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME),
                Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void singleTargetProjectInNonPackageParentDir() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);
    scratch.file("foo/" + PROJECT_FILE_NAME);
    scratch.file("foo/bar/" + PROJECT_FILE_NAME);

    // Project files don't count if they're in directories without BUILD files.
    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(Label.parseCanonical("//foo/bar:s")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo/bar:s"),
            ImmutableList.of(Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void twoTargetsInIndependentPackages() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        """);
    scratch.file(
        "baz/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "t")
        """);
    scratch.file("foo/" + PROJECT_FILE_NAME);
    scratch.file("baz/" + PROJECT_FILE_NAME);

    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(
                        Label.parseCanonical("//foo:s"), Label.parseCanonical("//baz:t")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo:s"),
            ImmutableList.of(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)),
            Label.parseCanonical("//baz:t"),
            ImmutableList.of(Label.parseCanonical("//baz:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void twoTargetsInSubPackagesHierarchy() throws Exception {
    scratch.file(
        "foo/bar/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "child")
        """);
    scratch.file(
        "foo/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "parent")
        """);
    scratch.file("foo/bar/" + PROJECT_FILE_NAME);
    scratch.file("foo/" + PROJECT_FILE_NAME);

    assertThat(
            Project.findProjectFiles(
                    ImmutableList.of(
                        Label.parseCanonical("//foo:parent"),
                        Label.parseCanonical("//foo/bar:child")),
                    skyframeExecutor,
                    reporter)
                .asMap())
        .containsExactly(
            Label.parseCanonical("//foo:parent"),
            ImmutableList.of(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)),
            Label.parseCanonical("//foo/bar:child"),
            ImmutableList.of(
                Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME),
                Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)));
  }

  @Test
  public void testInvalidProjectFile() throws Exception {
    scratch.file(
        "foo/BUILD",
        """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "myrule")
        """);
    scratch.dir("foo/" + PROJECT_FILE_NAME);

    assertThrows(
        ProjectResolutionException.class,
        () ->
            Project.findProjectFiles(
                ImmutableList.of(Label.parseCanonical("//foo:myrule")),
                skyframeExecutor,
                reporter));
  }
}
