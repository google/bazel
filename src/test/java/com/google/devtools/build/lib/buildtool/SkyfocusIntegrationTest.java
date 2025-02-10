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
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.runtime.commands.CqueryCommand;
import com.google.devtools.build.lib.skyframe.SkyfocusState.WorkingSetType;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Skyfocus integration tests */
@RunWith(JUnit4.class)
public final class SkyfocusIntegrationTest extends BuildIntegrationTestCase {

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();
    addOptions("--experimental_enable_skyfocus");
  }

  @Test
  public void cquery_doesNotTriggerSkyfocus() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    runtimeWrapper.newCommand(CqueryCommand.class);
    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings()).isEmpty();
  }

  @Test
  public void workingSet_canBeUsedWithBuildCommandAndNoTargets() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget();
  }

  @Test
  public void workingSet_canBeUsedWithBuildCommandWithTargets() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetType())
        .isEqualTo(WorkingSetType.USER_DEFINED);
  }

  @Test
  public void workingSet_canBeAutomaticallyDerivedUsingTopLevelTargetPackage() throws Exception {
    write("hello/x.txt", "x");
    write("hello/world/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "world/y.txt"],
            outs = ["out"],
            cmd = "cat $(location x.txt) $(location world/y.txt) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/world", "hello/world/y.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().verificationSet()).isNotEmpty();
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetType())
        .isEqualTo(WorkingSetType.DERIVED);
  }

  @Test
  public void workingSet_canBeAutomaticallyDerivedUsingProjectFile() throws Exception {
    addOptions("--experimental_enable_scl_dialect");

    write("hello/x.txt", "x");
    write("hello/world/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "world/y.txt", "//somewhere/else:files"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    // Files under //somewhere/else will be included because of this PROJECT.scl file.
    write(
        "hello/PROJECT.scl",
        """
        project = {
          "active_directories": { "default": [ "hello", "somewhere/else", "not/used" ] },
        }
        """);

    write("somewhere/else/file.txt", "some content");
    write(
        "somewhere/else/BUILD",
        """
        filegroup(name = "files", srcs = ["file.txt"])
        """);

    // Even though the PROJECT.scl file specified //not/used, this is not a dependency of
    // the focused target, hence it's not part of the working set.
    write("not/used/BUILD");

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly(
            "hello",
            "hello/PROJECT.scl",
            "hello/BUILD",
            "hello/x.txt",
            "hello/world",
            "hello/world/y.txt",
            "somewhere/else",
            "somewhere/else/BUILD",
            "somewhere/else/file.txt");
  }

  @Test
  public void workingSet_ignoresTopLevelPackageDirectoriesWhenUsingProjectFile() throws Exception {
    addOptions("--experimental_enable_scl_dialect");

    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "//somewhere/else:files"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write(
        "hello/PROJECT.scl",
        """
        project = {
          "active_directories": { "default": ["somewhere/else"] },
        }
        """);
    write("somewhere/else/file.txt", "some content");
    write(
        "somewhere/else/BUILD",
        """
        filegroup(name = "files", srcs = ["file.txt"])
        """);

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("somewhere/else", "somewhere/else/BUILD", "somewhere/else/file.txt");
  }

  @Test
  public void workingSet_projectFileCanHandleExcludedDirectories() throws Exception {
    addOptions("--experimental_enable_scl_dialect");

    write("hello/x.txt", "x");
    write("hello/world/y.txt", "y");
    write("hello/world/again/z.txt", "z");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "world/y.txt", "world/again/z.txt", "//somewhere/else:files"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    write(
        "hello/PROJECT.scl",
        """
        project = {
          "active_directories": {
            "default": [
              "hello", # included
              "-hello/world", # excluded
              "hello/world/again", # included
              "-somewhere/else", # excluded
            ],
          },
        }
        """);

    write("somewhere/else/file.txt", "some content");
    write(
        "somewhere/else/BUILD",
        """
        filegroup(name = "files", srcs = ["file.txt"])
        """);

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly(
            "hello",
            "hello/PROJECT.scl",
            "hello/BUILD",
            "hello/x.txt",
            "hello/world/again",
            "hello/world/again/z.txt");
  }

  @Test
  public void workingSet_skyfocusDoesNotRunIfDerivedWorkingSetIsUnchanged() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
    assertContainsEvent("Focusing on");

    events.clear();

    buildTarget("//hello:target");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
    assertDoesNotContainEvent("Focusing on");
  }

  @Test
  public void workingSet_derivedWorkingSetCanBeOverwrittenByUserDefinedWorkingSet()
      throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetType())
        .isEqualTo(WorkingSetType.DERIVED);

    addOptions("--experimental_working_set=hello/x.txt");
    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetType())
        .isEqualTo(WorkingSetType.USER_DEFINED);

    resetOptions();
    setupOptions();
    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetType())
        .isEqualTo(WorkingSetType.USER_DEFINED);
  }

  @Test
  public void workingSet_isRetainedAcrossInvocations() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");

    resetOptions();
    setupOptions();

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");
  }

  @Test
  public void workingSet_derivedWorkingSetChangesWhenTargetHasNewDependency() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");

    assertContents("x", "//hello:target");

    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("hello/y.txt", "y");
    buildTarget("//hello:target");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/y.txt");

    assertContents("x\ny", "//hello:target");
  }

  @Test
  public void workingSet_derivedWorkingSetChangesWhenTargetHasNewGlobDependency() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = glob(["*.txt"]),
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello:target");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
    assertContents("x", "//hello:target");

    write("hello/y.txt", "y");
    buildTarget("//hello:target");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/y.txt");
    assertContents("x\ny", "//hello:target");
  }

  @Test
  public void workingSet_derivedWorkingSetChangesWhenPackageHasANewTarget() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello:all");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));

    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )

        genrule(
            name = "target2",
            srcs = ["y.txt"],
            outs = ["out2"],
            cmd = "cat $< > $@",
        )
        """);
    write("hello/y.txt", "y");

    buildTarget("//hello:all");

    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/y.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(
            Label.parseCanonicalUnchecked("//hello:target"),
            Label.parseCanonicalUnchecked("//hello:target2"));
  }

  @Test
  public void workingSet_canBeAutomaticallyDerivedWithoutSkymeld() throws Exception {
    addOptions("--noexperimental_merged_skyframe_analysis_execution");
    write("hello/x.txt", "x");
    write("hello/world/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "world/y.txt"],
            outs = ["out"],
            cmd = "cat $(location x.txt) $(location world/y.txt) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContainsEvent("automatically deriving working set");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/world", "hello/world/y.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().verificationSet()).isNotEmpty();
  }

  @Test
  public void workingSet_derivationDoesNotIncludeFilesInSubpackage() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);
    write("hello/world/y.txt", "y");
    write("hello/world/BUILD", "");

    buildTarget("//hello:target");
    assertContainsEvent("automatically deriving working set");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
  }

  @Test
  public void workingSet_canBeAutomaticallyDerivedUsingMultipleTopLevelTargetPackages()
      throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    write("hello/world/y.txt", "y");
    write(
        "hello/world/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContainsEvent("automatically deriving working set");

    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(
            Label.parseCanonicalUnchecked("//hello:target"),
            Label.parseCanonicalUnchecked("//hello/world:target"));
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly(
            "hello",
            "hello/BUILD",
            "hello/x.txt",
            "hello/world",
            "hello/world/BUILD",
            "hello/world/y.txt");
    assertThat(getSkyframeExecutor().getSkyfocusState().verificationSet()).isNotEmpty();
  }

  @Test
  public void workingSet_shouldBeDerivedAndRetainedByTopLevelTarget() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    write("world/y.txt", "y");
    write(
        "world/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(Label.parseCanonicalUnchecked("//hello:target"));

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");

    assertContainsEvent("automatically deriving working set");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().verificationSet()).isNotEmpty();

    events.collector().clear();

    buildTarget("//world/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().focusedTargetLabels())
        .containsExactly(
            Label.parseCanonicalUnchecked("//hello:target"),
            Label.parseCanonicalUnchecked("//world:target"));
    assertContainsEvent("automatically deriving working set");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly(
            "hello", "hello/BUILD", "hello/x.txt", "world", "world/BUILD", "world/y.txt");
  }

  @Test
  public void workingSet_derivedWorkingSetBuildsForTargetThenRdep() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "//hello/world:dep"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("hello/world/y.txt", "y");
    write(
        "hello/world/BUILD",
        """
        genrule(
            name = "dep",
            srcs = ["y.txt"],
            outs = ["dep.txt"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/world:dep");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/world", "hello/world/BUILD", "hello/world/y.txt");

    events.collector().clear();

    buildTarget("//hello:target");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly(
            "hello/world",
            "hello/world/BUILD",
            "hello/world/y.txt",
            "hello",
            "hello/BUILD",
            "hello/x.txt");
  }

  @Test
  public void workingSet_sharedDepBetweenTwoTopLevelTargetsIsKept() throws Exception {
    // A -> C
    // B -> C
    // After building A, CT(C) will be dropped, but not CT(C/in.txt).
    // After building B, A's nodes should not be affected.
    write("A/in.txt", "A");
    write(
        "A/BUILD",
        """
        genrule(
            name = "A",
            srcs = ["in.txt", "//C:C.txt"],
            outs = ["A"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("B/in.txt", "B");
    write(
        "B/BUILD",
        """
        genrule(
            name = "B",
            srcs = ["in.txt", "//C:C.txt"],
            outs = ["B"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("C/in.txt", "C");
    write(
        "C/BUILD",
        """
        genrule(
            name = "C",
            srcs = ["in.txt"],
            outs = ["C.txt"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//A");

    assertThat(getAllConfiguredTargets())
        .containsAtLeast(
            getConfiguredTarget("//A:in.txt"),
            getConfiguredTarget("//A:A"),
            getConfiguredTarget("//C:C.txt"));
    assertThat(
            SkyframeExecutorTestUtils.getExistingConfiguredTarget(
                getSkyframeExecutor(), label("//C"), getTargetConfiguration()))
        .isNull();

    buildTarget("//B");

    assertThat(getAllConfiguredTargets())
        .containsAtLeast(
            getConfiguredTarget("//A:in.txt"), // nodes from the previous build should still be kept
            getConfiguredTarget("//A:A"),
            getConfiguredTarget("//B:in.txt"),
            getConfiguredTarget("//B:B"),
            getConfiguredTarget("//C:C.txt"));

    assertThat(
            SkyframeExecutorTestUtils.getExistingConfiguredTarget(
                getSkyframeExecutor(), label("//C"), getTargetConfiguration()))
        .isNull();

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("A", "A/BUILD", "A/in.txt", "B", "B/BUILD", "B/in.txt");
  }

  @Test
  public void workingSet_configChangesAreHandledWithDerivedWorkingSet() throws Exception {
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContents("x\ny", "//hello:target");

    addOptions("--compilation_mode=opt", "--experimental_frontier_violation_check=warn");
    buildTarget("//hello/...");
    assertContainsEvent("detected changes to the build configuration");
    assertContainsEvent("will be discarding the analysis cache");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt", "hello/y.txt");
  }

  @Test
  public void workingSet_configChangesAreHandledWithExplicitWorkingSet() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContents("x\ny", "//hello:target");

    addOptions("--compilation_mode=opt", "--experimental_frontier_violation_check=warn");
    buildTarget("//hello/...");
    assertContainsEvent("detected changes to the build configuration");
    assertContainsEvent("will be discarding the analysis cache");
    assertContainsEvent("Focusing on");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello/x.txt");
  }

  @Test
  public void workingSet_configChangesAreHandledStrictly() throws Exception {
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");

    addOptions("--compilation_mode=opt");
    AbruptExitException e =
        assertThrows(AbruptExitException.class, () -> buildTarget("//hello/..."));
    assertThat(e).hasMessageThat().contains("detected changes to the build configuration");
  }

  @Test
  public void workingSet_withFiles_correctlyRebuilds() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContents("x\ny", "//hello:target");

    write("hello/x.txt", "x2");
    buildTarget("//hello/...");
    assertContents("x2\ny", "//hello:target");
  }

  @Test
  public void workingSet_withDirs_correctlyRebuilds() throws Exception {
    /*
     * Setting directories in the working set works, because the rdep edges look like:
     *
     * FILE_STATE:[dir] -> FILE:[dir] -> FILE:[dir/BUILD], FILE:[dir/file.txt]
     *
     * ...and the FILE SkyKeys directly depend on their respective FILE_STATE SkyKeys,
     * which are the nodes that are invalidated by SkyframeExecutor#handleDiffs
     * at the start of every build, and are also kept by Skyfocus.
     *
     * In other words, defining a working set of directories will automatically
     * include all the files under those directories for focusing.
     */

    // Define working set to be a directory, not file
    addOptions("--experimental_working_set=hello");
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContents("x\ny", "//hello:target");

    write("hello/x.txt", "x2");
    buildTarget("//hello/...");
    // Correctly rebuilds referenced source file
    assertContents("x2\ny", "//hello:target");

    write(
        "hello/BUILD",
        """
        genrule(
            name = "y_only",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    buildTarget("//hello/...");
    // Correctly reanalyzes BUILD file
    assertContents("y", "//hello:y_only");
  }

  @Test
  public void workingSet_nestedDirs_correctlyRebuilds() throws Exception {
    // Define working set to be the parent package
    addOptions("--experimental_working_set=hello");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "//hello/world:target"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("hello/world/y.txt", "y");
    write(
        "hello/world/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertContents("x\ny", "//hello:target");

    write("hello/x.txt", "x2");
    buildTarget("//hello/...");
    // Rebuilds when parent package's source file changes
    assertContents("x2\ny", "//hello:target");

    write("hello/world/y.txt", "y2");
    buildTarget("//hello/...");
    // Rebuilds when child package's source file changes
    assertContents("x2\ny2", "//hello:target");
  }

  @Test
  public void newNonFocusedTargets_canBeBuilt() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "g",
            srcs = ["x.txt"],
            outs = ["g.txt"],
            cmd = "cat $(SRCS) > $@",
        )
        genrule(
            name = "g2",
            srcs = ["x.txt"],
            outs = ["g2.txt"],
            cmd = "cat $(SRCS) > $@",
        )
        genrule(
            name = "g3",
            outs = ["g3.txt"],
            cmd = "touch $@",
        )
        """);
    buildTarget("//hello:g");
    write("hello/x.txt", "x2");
    buildTarget("//hello:g");
    buildTarget("//hello:g2");
    buildTarget("//hello:g3");
  }

  @Test
  public void skyfocus_doesNotRun_forUnsuccessfulBuilds() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
          # error
        )
        """);
    TargetParsingException e =
        assertThrows(TargetParsingException.class, () -> buildTarget("//hello/..."));

    assertThat(e).hasMessageThat().contains("Package 'hello' contains errors");

    assertThat(getSkyframeExecutor().getSkyfocusState().enabled()).isTrue();
    assertThat(getSkyframeExecutor().getSkyfocusState().verificationSet()).isEmpty();
  }

  @Test
  public void editingNonWorkingSet_inSameDir_failsTheBuild() throws Exception {
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    addOptions("--experimental_working_set=hello/x.txt");
    buildTarget("//hello/...");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);

    write("hello/y.txt", "y2");
    AbruptExitException e =
        assertThrows(AbruptExitException.class, () -> buildTarget("//hello/..."));
    assertThat(e).hasMessageThat().contains("detected changes outside of the working set");
    assertThat(e).hasMessageThat().contains("hello/y.txt");

    addOptions("--experimental_working_set=hello/x.txt,hello/y.txt");
    buildTarget("//hello/...");
    assertContents("x\ny2", "//hello:target");
  }

  @Test
  public void editingNonWorkingSet_throughDep_failsTheBuild() throws Exception {
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "dep",
            srcs = ["x.txt"],
            outs = ["dep.txt"],
            cmd = "cat $(SRCS) > $@",
        )
        genrule(
            name = "target",
            srcs = ["dep.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    addOptions("--experimental_working_set=hello/y.txt");
    buildTarget("//hello/...");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);

    write("hello/x.txt", "x2");
    AbruptExitException e =
        assertThrows(AbruptExitException.class, () -> buildTarget("//hello/..."));
    assertThat(e).hasMessageThat().contains("detected changes outside of the working set");
    assertThat(e).hasMessageThat().contains("hello/x.txt");

    addOptions("--experimental_working_set=hello/x.txt,hello/y.txt");
    buildTarget("//hello/...");
    assertContents("x2\ny", "//hello:target");
  }

  @Test
  public void editingNonWorkingSet_inSiblingDir_failsTheBuild() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "//world:target"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    write("world/y.txt", "y");
    write(
        "world/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    addOptions("--experimental_working_set=hello/x.txt");
    buildTarget("//hello/...");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);

    write("world/y.txt", "y2");
    AbruptExitException e =
        assertThrows(AbruptExitException.class, () -> buildTarget("//hello/..."));
    assertThat(e).hasMessageThat().contains("detected changes outside of the working set");
    assertThat(e).hasMessageThat().contains("world/y.txt");

    addOptions("--experimental_working_set=hello/x.txt,world/y.txt");
    buildTarget("//hello/...");
    assertContents("x\ny2", "//hello:target");
  }

  @Test
  public void editingNonWorkingSet_inParentDir_failsTheBuild() throws Exception {
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "//hello/world:target"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    write("hello/world/y.txt", "y");
    write(
        "hello/world/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    addOptions("--experimental_working_set=hello/world");
    buildTarget("//hello/...");

    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);

    write("hello/x.txt", "x2");
    AbruptExitException e =
        assertThrows(AbruptExitException.class, () -> buildTarget("//hello/..."));
    assertThat(e).hasMessageThat().contains("detected changes outside of the working set");
    assertThat(e).hasMessageThat().contains("hello/x.txt");

    addOptions("--experimental_working_set=hello");
    buildTarget("//hello/...");
    assertContents("x2\ny", "//hello:target");
  }

  @Test
  public void workingSet_reduced_withoutReanalysis() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt,hello/y.txt");
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(2);

    resetOptions();
    setupOptions();
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x2");

    buildTarget("//hello/...");
    assertDoesNotContainEvent("discarding analysis cache");
    assertContents("x2\ny", "//hello:target");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);
  }

  @Test
  public void workingSet_expanded_withReanalysis() throws Exception {
    addOptions("--experimental_working_set=hello/x.txt");
    write("hello/x.txt", "x");
    write("hello/y.txt", "y");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt", "y.txt"],
            outs = ["out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);

    buildTarget("//hello/...");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(1);

    resetOptions();
    setupOptions();
    addOptions("--experimental_working_set=hello/x.txt,hello/y.txt");
    write("hello/x.txt", "x2");

    buildTarget("//hello/...");
    assertContainsEvent("discarding analysis cache");
    assertContents("x2\ny", "//hello:target");
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSet()).hasSize(2);
  }

  @Test
  public void actionCache_canBeNull() throws Exception {
    addOptions("--nouse_action_cache");
    write("hello/x.txt", "x");
    write(
        "hello/BUILD",
        """
        genrule(
            name = "target",
            srcs = ["x.txt"],
            outs = ["out"],
            cmd = "cat $< > $@",
        )
        """);

    buildTarget("//hello/..."); // does not crash.
    assertThat(getSkyframeExecutor().getSkyfocusState().workingSetStrings())
        .containsExactly("hello", "hello/BUILD", "hello/x.txt");
  }
}
