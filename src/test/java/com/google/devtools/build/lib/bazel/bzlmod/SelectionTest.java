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
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createDepSpec;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Selection}. */
@RunWith(JUnit4.class)
public class SelectionTest {

  @Test
  public void diamond_simple() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry());
  }

  @Test
  public void diamond_withIgnoredNonAffectingMaxCompatibilityLevel() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 3))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 3))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 3))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry());
  }

  @Test
  public void diamond_withSelectedNonAffectingMaxCompatibilityLevel() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createDepSpec("ddd", "2.0", 4))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_ccc", createDepSpec("ddd", "2.0", 4))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_ccc", createDepSpec("ddd", "2.0", 4))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry());
  }

  @Test
  public void diamond_withFurtherRemoval() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ddd", "1.0")
                    .addDep("eee", createModuleKey("eee", "1.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0").buildEntry())
            // Only D@1.0 needs E. When D@1.0 is removed, E should be gone as well (even though
            // E@1.0 is selected for E).
            .put(InterimModuleBuilder.create("eee", "1.0").buildEntry())
            .build();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0").buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0").buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0")
                .addDep("eee", createModuleKey("eee", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("eee", "1.0").buildEntry());
  }

  @Test
  public void circularDependencyDueToSelection() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("bbb", createModuleKey("bbb", "1.0-pre"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0-pre")
                    .addDep("ddd", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0").buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0-pre"))
                .buildEntry())
        .inOrder();
    // D is completely gone.

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("bbb", createModuleKey("bbb", "1.0"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0-pre"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0-pre")
                .addDep("ddd", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0").buildEntry());
  }

  @Test
  public void differentCompatibilityLevelIsRejected() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
            .buildOrThrow();

    ExternalDepsException e =
        assertThrows(
            ExternalDepsException.class,
            () -> Selection.run(depGraph, /* overrides= */ ImmutableMap.of()));
    String error = e.getMessage();
    assertThat(error).contains("bbb@1.0 depends on ddd@1.0 with compatibility level 1");
    assertThat(error).contains("ccc@2.0 depends on ddd@2.0 with compatibility level 2");
    assertThat(error).contains("which is different");
  }

  @Test
  public void differentCompatibilityLevelWithMaxCompatibilityLevelIsRejected() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createDepSpec("ddd", "2.0", 3))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
            .buildOrThrow();

    ExternalDepsException e =
        assertThrows(
            ExternalDepsException.class,
            () -> Selection.run(depGraph, /* overrides= */ ImmutableMap.of()));
    String error = e.getMessage();
    assertThat(error).contains("bbb@1.0 depends on ddd@1.0 with compatibility level 1");
    assertThat(error).contains("ccc@2.0 depends on ddd@2.0 with compatibility level 2");
    assertThat(error).contains("which is different");
  }

  @Test
  public void maxCompatibilityBasedSelection() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 2))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 2))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 2))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry());
  }

  @Test
  public void maxCompatibilityBasedSelection_sameVersion() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createDepSpec("ddd", "2.0", 3))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
            .buildOrThrow();

    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "2.0", 3))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                .addOriginalDep("ddd_from_bbb", createDepSpec("ddd", "2.0", 3))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry());
  }

  @Test
  public void maxCompatibilityBasedSelection_limitedToMax() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createDepSpec("ddd", "1.0", 2))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "3.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "3.0", 3).buildEntry())
            .buildOrThrow();

    ExternalDepsException e =
        assertThrows(
            ExternalDepsException.class,
            () -> Selection.run(depGraph, /* overrides= */ ImmutableMap.of()));
    String error = e.getMessage();
    assertThat(error).contains("bbb@1.0 depends on ddd@1.0 with compatibility level 1");
    assertThat(error).contains("ccc@2.0 depends on ddd@3.0 with compatibility level 3");
    assertThat(error).contains("which is different");
  }

  @Test
  public void maxCompatibilityBasedSelection_unreferencedNotSelected() throws Exception {
    // aaa 1.0 -> bbb 1.0 -> ccc 2.0
    //       \-> ccc 1.0 (max_compatibility_level=2)
    //        \-> ddd 1.0 -> bbb 1.1
    //         \-> eee 1.0 -> ccc 1.1
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", "1.0")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .addDep("ccc", createDepSpec("ccc", "1.0", 2))
                    .addDep("ddd", createModuleKey("ddd", "1.0"))
                    .addDep("eee", createModuleKey("eee", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(
                InterimModuleBuilder.create("ddd", "1.0")
                    .addDep("bbb", createModuleKey("bbb", "1.1"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.1").buildEntry())
            .put(
                InterimModuleBuilder.create("eee", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.1"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry())
            .buildOrThrow();

    // After selection, ccc 2.0 is gone, so ccc 1.0 (max_compatibility_level=2) doesn't upgrade to
    // ccc 2.0, and only upgrades to ccc 1.1
    // aaa 1.0 -> bbb 1.1
    //       \-> ccc 1.1
    //        \-> ddd 1.0 -> bbb 1.1
    //         \-> eee 1.0 -> ccc 1.1
    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", "1.0")
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .addOriginalDep("ccc", createDepSpec("ccc", "1.0", 2))
                .addDep("ddd", createModuleKey("ddd", "1.0"))
                .addDep("eee", createModuleKey("eee", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0")
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("eee", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", "1.0")
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .addOriginalDep("ccc", createDepSpec("ccc", "1.0", 2))
                .addDep("ddd", createModuleKey("ddd", "1.0"))
                .addDep("eee", createModuleKey("eee", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0")
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("eee", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .buildEntry());
  }

  @Test
  public void differentCompatibilityLevelIsOkIfUnreferenced() throws Exception {
    // aaa 1.0 -> bbb 1.0 -> ccc 2.0
    //       \-> ccc 1.0
    //        \-> ddd 1.0 -> bbb 1.1
    //         \-> eee 1.0 -> ccc 1.1
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", "1.0")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .addDep("ccc", createModuleKey("ccc", "1.0"))
                    .addDep("ddd", createModuleKey("ddd", "1.0"))
                    .addDep("eee", createModuleKey("eee", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(
                InterimModuleBuilder.create("ddd", "1.0")
                    .addDep("bbb", createModuleKey("bbb", "1.1"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.1").buildEntry())
            .put(
                InterimModuleBuilder.create("eee", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.1"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry())
            .buildOrThrow();

    // After selection, ccc 2.0 is gone, so we're okay.
    // aaa 1.0 -> bbb 1.1
    //       \-> ccc 1.1
    //        \-> ddd 1.0 -> bbb 1.1
    //         \-> eee 1.0 -> ccc 1.1
    Selection.Result selectionResult = Selection.run(depGraph, /* overrides= */ ImmutableMap.of());
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", "1.0")
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                .addDep("ddd", createModuleKey("ddd", "1.0"))
                .addDep("eee", createModuleKey("eee", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0")
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("eee", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", "1.0")
                .setKey(ModuleKey.ROOT)
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .addOriginalDep("bbb", createModuleKey("bbb", "1.0"))
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                .addDep("ddd", createModuleKey("ddd", "1.0"))
                .addDep("eee", createModuleKey("eee", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.1", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0")
                .addDep("bbb", createModuleKey("bbb", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("eee", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.1"))
                .buildEntry());
  }

  @Test
  public void multipleVersionOverride_fork_allowedVersionMissingInDepGraph() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.0").buildEntry())
            .put(InterimModuleBuilder.create("bbb", "2.0").buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "bbb",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0"), Version.parse("3.0")),
                ""));

    ExternalDepsException e =
        assertThrows(ExternalDepsException.class, () -> Selection.run(depGraph, overrides));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "multiple_version_override for module bbb contains version 3.0, but it doesn't exist in"
                + " the dependency graph");
  }

  @Test
  public void multipleVersionOverride_fork_goodCase() throws Exception {
    // For more complex good cases, see the "diamond" test cases below.
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.0").buildEntry())
            .put(InterimModuleBuilder.create("bbb", "2.0").buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "bbb",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    Selection.Result selectionResult = Selection.run(depGraph, overrides);
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb1", createModuleKey("bbb", "1.0"))
                .addDep("bbb2", createModuleKey("bbb", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0").buildEntry(),
            InterimModuleBuilder.create("bbb", "2.0").buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph());
  }

  @Test
  public void multipleVersionOverride_fork_sameVersionUsedTwice() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb", "1.3"))
                    .addDep("bbb3", createModuleKey("bbb", "1.5"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.0").buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.3").buildEntry())
            .put(InterimModuleBuilder.create("bbb", "1.5").buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "bbb",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("1.5")), ""));

    ExternalDepsException e =
        assertThrows(ExternalDepsException.class, () -> Selection.run(depGraph, overrides));
    assertThat(e)
        .hasMessageThat()
        .containsMatch(
            "aaa@_ depends on bbb@1.5 at least twice \\(with repo names (bbb2 and bbb3)|(bbb3 and"
                + " bbb2)\\)");
  }

  @Test
  public void multipleVersionOverride_diamond_differentCompatibilityLevels() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ddd",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    Selection.Result selectionResult = Selection.run(depGraph, overrides);
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0", 2).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph());
  }

  @Test
  public void multipleVersionOverride_diamond_sameCompatibilityLevel() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0").buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0").buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ddd",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    Selection.Result selectionResult = Selection.run(depGraph, overrides);
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb", "1.0")
                .addDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0")
                .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ddd", "1.0").buildEntry(),
            InterimModuleBuilder.create("ddd", "2.0").buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph());
  }

  @Test
  public void multipleVersionOverride_diamond_snappingToNextHighestVersion() throws Exception {
    // aaa --> bbb1@1.0 -> ccc@1.0
    //     \-> bbb2@1.0 -> ccc@1.3  [allowed]
    //     \-> bbb3@1.0 -> ccc@1.5
    //     \-> bbb4@1.0 -> ccc@1.7  [allowed]
    //     \-> bbb5@1.0 -> ccc@2.0  [allowed]
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", createModuleKey("bbb4", "1.0"))
                    .addDep("bbb5", createModuleKey("bbb5", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb1", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb2", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.3"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb3", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.5"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb4", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.7"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb5", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.3", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.5", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.7", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ccc",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.3"), Version.parse("1.7"), Version.parse("2.0")),
                ""));

    // aaa --> bbb1@1.0 -> ccc@1.3  [originally ccc@1.0]
    //     \-> bbb2@1.0 -> ccc@1.3  [allowed]
    //     \-> bbb3@1.0 -> ccc@1.7  [originally ccc@1.5]
    //     \-> bbb4@1.0 -> ccc@1.7  [allowed]
    //     \-> bbb5@1.0 -> ccc@2.0  [allowed]
    Selection.Result selectionResult = Selection.run(depGraph, overrides);
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.0"))
                .addDep("bbb5", createModuleKey("bbb5", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb1", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.3"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb2", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.3"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb3", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.7"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.5"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb4", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.7"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb5", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "1.3", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.7", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.0"))
                .addDep("bbb5", createModuleKey("bbb5", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb1", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.3"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb2", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.3"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb3", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.7"))
                .addOriginalDep("ccc", createModuleKey("ccc", "1.5"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb4", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.7"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb5", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .buildEntry(),
            InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.3", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.5", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.7", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry());
  }

  @Test
  public void multipleVersionOverride_diamond_dontSnapToDifferentCompatibility() throws Exception {
    // aaa --> bbb1@1.0 -> ccc@1.0  [allowed]
    //     \-> bbb2@1.0 -> ccc@1.7
    //     \-> bbb3@1.0 -> ccc@2.0  [allowed]
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb1", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb2", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.7"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb3", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.7", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ccc",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    ExternalDepsException e =
        assertThrows(ExternalDepsException.class, () -> Selection.run(depGraph, overrides));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "bbb2@1.0 depends on ccc@1.7 which is not allowed by the multiple_version_override on"
                + " ccc, which allows only [1.0, 2.0]");
  }

  @Test
  public void multipleVersionOverride_diamond_unknownCompatibility() throws Exception {
    // aaa --> bbb1@1.0 -> ccc@1.0  [allowed]
    //     \-> bbb2@1.0 -> ccc@2.0  [allowed]
    //     \-> bbb3@1.0 -> ccc@3.0
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb1", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb2", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb3", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "3.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "3.0", 3).buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ccc",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    ExternalDepsException e =
        assertThrows(ExternalDepsException.class, () -> Selection.run(depGraph, overrides));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "bbb3@1.0 depends on ccc@3.0 which is not allowed by the multiple_version_override on"
                + " ccc, which allows only [1.0, 2.0]");
  }

  @Test
  public void multipleVersionOverride_diamond_badVersionsAreOkayIfUnreferenced() throws Exception {
    // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
    //     \            \-> bbb2@1.1
    //     \-> bbb2@1.0 --> ccc@1.5
    //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
    //     \            \-> bbb4@1.1
    //     \-> bbb4@1.0 --> ccc@3.0
    ImmutableMap<ModuleKey, InterimModule> depGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", createModuleKey("bbb4", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb1", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb2", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.5"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb2", "1.1").buildEntry())
            .put(
                InterimModuleBuilder.create("bbb3", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb4", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "3.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("bbb4", "1.1").buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.5", 1).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
            .put(InterimModuleBuilder.create("ccc", "3.0", 3).buildEntry())
            .buildOrThrow();
    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ccc",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""));

    // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
    //     \            \-> bbb2@1.1
    //     \-> bbb2@1.1
    //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
    //     \            \-> bbb4@1.1
    //     \-> bbb4@1.1
    // ccc@1.5 and ccc@3.0, the versions violating the allowlist, are gone.
    Selection.Result selectionResult = Selection.run(depGraph, overrides);
    assertThat(selectionResult.resolvedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                .addOriginalDep("bbb2", createModuleKey("bbb2", "1.0"))
                .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                .addOriginalDep("bbb4", createModuleKey("bbb4", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb1", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb2", "1.1").buildEntry(),
            InterimModuleBuilder.create("bbb3", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb4", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry())
        .inOrder();

    assertThat(selectionResult.unprunedDepGraph().entrySet())
        .containsExactly(
            InterimModuleBuilder.create("aaa", Version.EMPTY)
                .setKey(ModuleKey.ROOT)
                .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                .addOriginalDep("bbb2", createModuleKey("bbb2", "1.0"))
                .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                .addOriginalDep("bbb4", createModuleKey("bbb4", "1.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb1", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.0"))
                .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb2", "1.0")
                .addDep("ccc", createModuleKey("ccc", "1.5"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb2", "1.1").buildEntry(),
            InterimModuleBuilder.create("bbb3", "1.0")
                .addDep("ccc", createModuleKey("ccc", "2.0"))
                .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb4", "1.0")
                .addDep("ccc", createModuleKey("ccc", "3.0"))
                .buildEntry(),
            InterimModuleBuilder.create("bbb4", "1.1").buildEntry(),
            InterimModuleBuilder.create("ccc", "1.0", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "1.5", 1).buildEntry(),
            InterimModuleBuilder.create("ccc", "2.0", 2).buildEntry(),
            InterimModuleBuilder.create("ccc", "3.0", 3).buildEntry());
  }
}
