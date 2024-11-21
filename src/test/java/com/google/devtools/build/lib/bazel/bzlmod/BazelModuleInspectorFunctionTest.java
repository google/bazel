// Copyright 2022 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.AugmentedModuleBuilder.buildAugmentedModule;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BazelModuleInspectorFunction}. */
@RunWith(JUnit4.class)
public class BazelModuleInspectorFunctionTest {

  @Test
  public void testDiamond_simple() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd_from_bbb", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0", 1).buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0", 1).buildEntry())
            .buildOrThrow();

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT,
            createModuleKey("bbb", "1.0"),
            createModuleKey("ccc", "2.0"),
            createModuleKey("ddd", "2.0"));

    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, /* overrides= */ ImmutableMap.of());

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa")
                .addDep("bbb_from_aaa", "bbb", "1.0")
                .addDep("ccc_from_aaa", "ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("bbb", "1.0")
                .addChangedDep(
                    "ddd_from_bbb", "ddd", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ccc", "2.0")
                .addDep("ddd_from_ccc", "ddd", "2.0")
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ddd", "2.0")
                .addDependant("bbb", "1.0")
                .addStillDependant("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("ddd", "1.0").addOriginalDependant("bbb", "1.0").buildEntry());
  }

  @Test
  public void testDiamond_withFurtherRemoval() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ddd", createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0").buildEntry())
            .put(
                InterimModuleBuilder.create("ddd", "1.0")
                    .addDep("eee", createModuleKey("eee", "1.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("eee", "1.0").buildEntry())
            .buildOrThrow();

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT,
            createModuleKey("bbb", "1.0"),
            createModuleKey("ccc", "2.0"),
            createModuleKey("ddd", "2.0"));

    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, /*overrides*/ ImmutableMap.of());

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa")
                .addDep("bbb", "1.0")
                .addDep("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("bbb", "1.0")
                .addChangedDep("ddd", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ccc", "2.0")
                .addDep("ddd", "2.0")
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ddd", "2.0")
                .addDependant("bbb", "1.0")
                .addStillDependant("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("ddd", "1.0")
                .addDep("eee", "1.0")
                .addOriginalDependant("bbb", "1.0")
                .buildEntry(),
            buildAugmentedModule("eee", "1.0").addOriginalDependant("ddd", "1.0").buildEntry());
  }

  @Test
  public void testCircularDependencyDueToSelection() throws Exception {
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
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
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .addOriginalDep("bbb", createModuleKey("bbb", "1.0-pre"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0-pre")
                    .addDep("ddd", createModuleKey("ddd", "1.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "1.0").buildEntry())
            .buildOrThrow();

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT, createModuleKey("bbb", "1.0"), createModuleKey("ccc", "2.0"));

    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, /*overrides*/ ImmutableMap.of());

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa").addDep("bbb", "1.0").buildEntry(),
            buildAugmentedModule("bbb", "1.0")
                .addDep("ccc", "2.0")
                .addStillDependant(ModuleKey.ROOT)
                .addDependant("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("ccc", "2.0")
                .addChangedDep("bbb", "1.0", "1.0-pre", ResolutionReason.MINIMAL_VERSION_SELECTION)
                .addStillDependant("bbb", "1.0")
                .buildEntry(),
            buildAugmentedModule("bbb", "1.0-pre")
                .addDep("ddd", "1.0")
                .addOriginalDependant("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("ddd", "1.0").addOriginalDependant("bbb", "1.0-pre").buildEntry());
  }

  @Test
  public void testSingleVersionOverride_withRemoval() throws Exception {
    // Original (non-resolved) dep graph
    // single_version_override (ccc, 2.0)
    // aaa -> bbb 1.0 -> ccc 1.0 -> ddd 1.0
    //                   ccc 2.0 -> ddd 2.0
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("ccc", "2.0")
                    .addDep("ddd", createModuleKey("ddd", "2.0"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ddd", "2.0").buildEntry())
            .buildOrThrow();

    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "ccc",
            SingleVersionOverride.create(
                Version.parse("2.0"), "", ImmutableList.of(), ImmutableList.of(), 0));

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT,
            createModuleKey("bbb", "1.0"),
            createModuleKey("ccc", "1.0"),
            createModuleKey("ccc", "2.0"),
            createModuleKey("ddd", "1.0"),
            createModuleKey("ddd", "2.0"));

    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, overrides);

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa").addDep("bbb", "1.0").buildEntry(),
            buildAugmentedModule("bbb", "1.0")
                .addChangedDep("ccc", "2.0", "1.0", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ccc", "1.0", false)
                .addOriginalDependant("bbb", "1.0")
                .buildEntry(),
            buildAugmentedModule("ccc", "2.0")
                .addDependant("bbb", "1.0")
                .addDep("ddd", "2.0")
                .buildEntry(),
            buildAugmentedModule("ddd", "2.0").addStillDependant("ccc", "2.0").buildEntry());
  }

  @Test
  public void testNonRegistryOverride_withRemoval() throws Exception {
    // Original (non-resolved) dep graph
    // archive_override "file://users/user/bbb.zip"
    // aaa    -> bbb 1.0        -> ccc 1.0 (not loaded)
    //   (local) bbb 1.0-hotfix -> ccc 1.1
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", createModuleKey("bbb", ""))
                    .addOriginalDep("bbb", createModuleKey("bbb", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .setKey(createModuleKey("bbb", ""))
                    .addDep("ccc", createModuleKey("ccc", "1.1"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.1").buildEntry())
            .buildOrThrow();

    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "bbb",
            new NonRegistryOverride(
                new RepoSpec(
                    ArchiveRepoSpecBuilder.HTTP_ARCHIVE,
                    AttributeValues.create(
                        ImmutableMap.of("urls", ImmutableList.of("file://users/user/bbb.zip"))))));

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(ModuleKey.ROOT, createModuleKey("bbb", ""), createModuleKey("ccc", "1.1"));

    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, overrides);

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa")
                .addChangedDep("bbb", "", "1.0", ResolutionReason.NON_REGISTRY_OVERRIDE)
                .buildEntry(),
            buildAugmentedModule("bbb", "1.0", false)
                .addOriginalDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule(createModuleKey("bbb", ""), "bbb", Version.parse("1.0"), true)
                .addDep("ccc", "1.1")
                .addDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("ccc", "1.1").addStillDependant("bbb", "").buildEntry());
  }

  @Test
  public void testMultipleVersionOverride_simpleSnapToHigher() throws Exception {
    // Initial dep graph
    // aaa  -> (bbb1)bbb 1.0 -> ccc 1.0
    //     \-> (bbb2)bbb 2.0 -> ccc 1.5
    //     \-> ccc 2.0
    // multiple_version_override ccc: [1.5, 2.0]
    // multiple_version_override bbb: [1.0, 2.0]
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb", "2.0"))
                    .addDep("ccc", createModuleKey("ccc", "2.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "1.0")
                    .addDep("ccc", createModuleKey("ccc", "1.5"))
                    .addOriginalDep("ccc", createModuleKey("ccc", "1.0"))
                    .buildEntry())
            .put(
                InterimModuleBuilder.create("bbb", "2.0")
                    .addDep("ccc", createModuleKey("ccc", "1.5"))
                    .buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.0").buildEntry())
            .put(InterimModuleBuilder.create("ccc", "1.5").buildEntry())
            .put(InterimModuleBuilder.create("ccc", "2.0").buildEntry())
            .buildOrThrow();

    ImmutableMap<String, ModuleOverride> overrides =
        ImmutableMap.of(
            "bbb",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.0"), Version.parse("2.0")), ""),
            "ccc",
            MultipleVersionOverride.create(
                ImmutableList.of(Version.parse("1.5"), Version.parse("2.0")), ""));

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT,
            createModuleKey("bbb", "1.0"),
            createModuleKey("bbb", "2.0"),
            createModuleKey("ccc", "1.5"),
            createModuleKey("ccc", "2.0"));
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, overrides);

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa")
                .addDep("bbb1", "bbb", "1.0")
                .addDep("bbb2", "bbb", "2.0")
                .addDep("ccc", "2.0")
                .buildEntry(),
            buildAugmentedModule("bbb", "1.0")
                .addStillDependant(ModuleKey.ROOT)
                .addChangedDep("ccc", "1.5", "1.0", ResolutionReason.MULTIPLE_VERSION_OVERRIDE)
                .buildEntry(),
            buildAugmentedModule("bbb", "2.0")
                .addStillDependant(ModuleKey.ROOT)
                .addDep("ccc", "1.5")
                .buildEntry(),
            buildAugmentedModule("ccc", "1.0").addOriginalDependant("bbb", "1.0").buildEntry(),
            buildAugmentedModule("ccc", "1.5")
                .addDependant("bbb", "1.0")
                .addStillDependant("bbb", "2.0")
                .buildEntry(),
            buildAugmentedModule("ccc", "2.0").addStillDependant(ModuleKey.ROOT).buildEntry());
  }

  @Test
  public void testMultipleVersionOverride_badDepsUnreferenced() throws Exception {
    // Initial dep graph
    // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
    //     \            \-> bbb2@1.1
    //     \-> bbb2@1.0 --> ccc@1.5
    //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
    //     \            \-> bbb4@1.1
    //     \-> bbb4@1.0 --> ccc@3.0
    //
    // Resolved dep graph
    // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
    //     \            \-> bbb2@1.1
    //     \-> bbb2@1.1
    //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
    //     \            \-> bbb4@1.1
    //     \-> bbb4@1.1
    // ccc@1.5 and ccc@3.0, the versions violating the allowlist, are gone.
    ImmutableMap<ModuleKey, InterimModule> unprunedDepGraph =
        ImmutableMap.<ModuleKey, InterimModule>builder()
            .put(
                InterimModuleBuilder.create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", createModuleKey("bbb2", "1.1"))
                    .addOriginalDep("bbb2", createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", createModuleKey("bbb4", "1.1"))
                    .addOriginalDep("bbb4", createModuleKey("bbb4", "1.0"))
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

    ImmutableSet<ModuleKey> usedModules =
        ImmutableSet.of(
            ModuleKey.ROOT,
            createModuleKey("bbb1", "1.0"),
            createModuleKey("bbb2", "1.1"),
            createModuleKey("bbb3", "1.0"),
            createModuleKey("bbb4", "1.1"),
            createModuleKey("ccc", "1.0"),
            createModuleKey("ccc", "2.0"));
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        BazelModuleInspectorFunction.computeAugmentedGraph(
            unprunedDepGraph, usedModules, overrides);

    assertThat(depGraph.entrySet())
        .containsExactly(
            buildAugmentedModule(ModuleKey.ROOT, "aaa")
                .addDep("bbb1", "1.0")
                .addChangedDep("bbb2", "1.1", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                .addDep("bbb3", "1.0")
                .addChangedDep("bbb4", "1.1", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                .buildEntry(),
            buildAugmentedModule("bbb1", "1.0")
                .addDep("ccc", "1.0")
                .addDep("bbb2", "1.1")
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("bbb2", "1.0")
                .addDep("ccc", "1.5")
                .addOriginalDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("bbb2", "1.1")
                .addDependant(ModuleKey.ROOT)
                .addStillDependant("bbb1", "1.0")
                .buildEntry(),
            buildAugmentedModule("bbb3", "1.0")
                .addDep("ccc", "2.0")
                .addDep("bbb4", "1.1")
                .addStillDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("bbb4", "1.0")
                .addDep("ccc", "3.0")
                .addOriginalDependant(ModuleKey.ROOT)
                .buildEntry(),
            buildAugmentedModule("bbb4", "1.1")
                .addDependant(ModuleKey.ROOT)
                .addStillDependant("bbb3", "1.0")
                .buildEntry(),
            buildAugmentedModule("ccc", "1.0").addStillDependant("bbb1", "1.0").buildEntry(),
            buildAugmentedModule("ccc", "1.5").addOriginalDependant("bbb2", "1.0").buildEntry(),
            buildAugmentedModule("ccc", "2.0").addStillDependant("bbb3", "1.0").buildEntry(),
            buildAugmentedModule("ccc", "3.0").addOriginalDependant("bbb4", "1.0").buildEntry());
  }
}
