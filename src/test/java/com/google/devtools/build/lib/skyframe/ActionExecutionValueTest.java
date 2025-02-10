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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationDepsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root.RootCodecDependencies;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ActionExecutionValueTest {
  private static final FileArtifactValue VALUE_1_REMOTE =
      FileArtifactValue.createForRemoteFile(
          /* digest= */ new byte[0], /* size= */ 0, /* locationIndex= */ 1);
  private static final FileArtifactValue VALUE_2_REMOTE =
      FileArtifactValue.createForRemoteFile(
          /* digest= */ new byte[0], /* size= */ 0, /* locationIndex= */ 2);

  private static final ActionLookupKey KEY = ActionsTestUtil.NULL_ARTIFACT_OWNER;
  private static final ActionLookupData ACTION_LOOKUP_DATA_1 = ActionLookupData.create(KEY, 1);
  private static final ActionLookupData ACTION_LOOKUP_DATA_2 = ActionLookupData.create(KEY, 2);

  private static final ArtifactRoot OUTPUT_ROOT =
      ArtifactRoot.asDerivedRoot(new Scratch().resolve("/execroot"), RootType.Output, "out");

  @Test
  public void equality() {
    SpecialArtifact tree1 = tree("tree1");
    TreeArtifactValue tree1Value1 =
        TreeArtifactValue.newBuilder(tree1)
            .putChild(TreeFileArtifact.createTreeOutput(tree1, "file1"), VALUE_1_REMOTE)
            .build();
    TreeArtifactValue tree1Value2 =
        TreeArtifactValue.newBuilder(tree1)
            .putChild(TreeFileArtifact.createTreeOutput(tree1, "file1"), VALUE_2_REMOTE)
            .build();
    FilesetOutputSymlink symlink1 =
        FilesetOutputSymlink.createForTesting(
            PathFragment.create("name1"),
            PathFragment.create("target1"),
            PathFragment.create("execPath1"));
    FilesetOutputSymlink symlink2 =
        FilesetOutputSymlink.createForTesting(
            PathFragment.create("name2"),
            PathFragment.create("target2"),
            PathFragment.create("execPath2"));

    new EqualsTester()
        .addEqualityGroup(createWithArtifactData(ImmutableMap.of(output("file1"), VALUE_1_REMOTE)))
        .addEqualityGroup(createWithArtifactData(ImmutableMap.of(output("file1"), VALUE_2_REMOTE)))
        .addEqualityGroup(
            createWithArtifactData(
                ImmutableMap.of(output("file1", ACTION_LOOKUP_DATA_2), VALUE_1_REMOTE)))
        .addEqualityGroup(createWithArtifactData(ImmutableMap.of(output("file2"), VALUE_1_REMOTE)))
        .addEqualityGroup(
            createWithArtifactData(
                ImmutableMap.of(output("file1"), VALUE_1_REMOTE, output("file2"), VALUE_2_REMOTE)))
        // treeArtifactData
        .addEqualityGroup(
            createWithTreeArtifactData(ImmutableMap.of(tree1, TreeArtifactValue.empty())))
        .addEqualityGroup(
            createWithTreeArtifactData(ImmutableMap.of(tree("tree2"), TreeArtifactValue.empty())))
        .addEqualityGroup(
            ImmutableMap.of(
                tree1, TreeArtifactValue.empty(), tree("tree2"), TreeArtifactValue.empty()))
        .addEqualityGroup(ImmutableMap.of(tree1, tree1Value1))
        .addEqualityGroup(ImmutableMap.of(tree1, tree1Value2))
        // outputSymlinks
        .addEqualityGroup(
            createWithFilesetOutput(FilesetOutputTree.create(ImmutableList.of(symlink1))))
        .addEqualityGroup(
            createWithFilesetOutput(FilesetOutputTree.create(ImmutableList.of(symlink2))))
        .addEqualityGroup(
            createWithFilesetOutput(FilesetOutputTree.create(ImmutableList.of(symlink1, symlink2))))
        .addEqualityGroup(
            createWithFilesetOutput(FilesetOutputTree.create(ImmutableList.of(symlink2, symlink1))))
        // discoveredModules
        .addEqualityGroup(
            createWithDiscoveredModules(
                NestedSetBuilder.create(Order.STABLE_ORDER, output("file1"))),
            createWithDiscoveredModules(
                NestedSetBuilder.create(Order.STABLE_ORDER, output("file1"))))
        .addEqualityGroup(
            createWithDiscoveredModules(
                NestedSetBuilder.create(Order.STABLE_ORDER, output("file1", ACTION_LOOKUP_DATA_2))))
        .addEqualityGroup(
            createWithDiscoveredModules(
                NestedSetBuilder.<Artifact>stableOrder().add(output("file2"))))
        .addEqualityGroup(
            createWithDiscoveredModules(
                NestedSetBuilder.<Artifact>stableOrder().add(output("file1")).add(output("file2"))))
        // Does not detect equality for identical sets with different shape.
        .addEqualityGroup(
            createWithDiscoveredModules(
                NestedSetBuilder.<Artifact>stableOrder()
                    .add(output("file1"))
                    .addTransitive(NestedSetBuilder.create(Order.STABLE_ORDER, output("file2")))))
        .testEquals();
  }

  @Test
  public void serialization() throws Exception {
    new SerializationTester(
            // Single output file
            createWithArtifactData(ImmutableMap.of(output("output1"), VALUE_1_REMOTE)),
            // Fileset
            createWithFilesetOutput(
                FilesetOutputTree.create(
                    ImmutableList.of(
                        FilesetOutputSymlink.createForTesting(
                            PathFragment.create("name"),
                            PathFragment.create("target"),
                            PathFragment.create("execPath"))))),
            // Module discovering
            createWithDiscoveredModules(
                NestedSetBuilder.create(Order.STABLE_ORDER, output("module"))),
            // Multiple output files
            createWithArtifactData(
                ImmutableMap.of(
                    output("output1"), VALUE_1_REMOTE, output("output2"), VALUE_2_REMOTE)),
            // Single tree
            createWithTreeArtifactData(ImmutableMap.of(tree("tree"), TreeArtifactValue.empty())),
            // Multiple trees
            createWithTreeArtifactData(
                ImmutableMap.of(
                    tree("tree1"),
                    TreeArtifactValue.empty(),
                    tree("tree2"),
                    TreeArtifactValue.empty())),
            // Mixed file and tree
            ActionExecutionValue.createFromOutputMetadataStore(
                ImmutableMap.of(output("file"), VALUE_1_REMOTE),
                ImmutableMap.of(tree("tree"), TreeArtifactValue.empty()),
                FilesetOutputTree.EMPTY,
                /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER)))
        .addDependency(FileSystem.class, OUTPUT_ROOT.getRoot().getFileSystem())
        .addDependency(
            RootCodecDependencies.class, new RootCodecDependencies(OUTPUT_ROOT.getRoot()))
        .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
        .runTests();
  }

  private static ActionExecutionValue createWithArtifactData(
      ImmutableMap<Artifact, FileArtifactValue> artifactData) {
    return ActionExecutionValue.createFromOutputMetadataStore(
        /* artifactData= */ artifactData,
        /* treeArtifactData= */ ImmutableMap.of(),
        FilesetOutputTree.EMPTY,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private static ActionExecutionValue createWithTreeArtifactData(
      ImmutableMap<Artifact, TreeArtifactValue> treeArtifactData) {
    return ActionExecutionValue.createFromOutputMetadataStore(
        /* artifactData= */ ImmutableMap.of(),
        treeArtifactData,
        FilesetOutputTree.EMPTY,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private static ActionExecutionValue createWithFilesetOutput(FilesetOutputTree filesetOutput) {
    return ActionExecutionValue.createFromOutputMetadataStore(
        ImmutableMap.of(output("fileset.manifest"), VALUE_1_REMOTE),
        /* treeArtifactData= */ ImmutableMap.of(),
        filesetOutput,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private static ActionExecutionValue createWithDiscoveredModules(
      NestedSetBuilder<Artifact> discoveredModules) {
    return createWithDiscoveredModules(discoveredModules.build());
  }

  private static ActionExecutionValue createWithDiscoveredModules(
      NestedSet<Artifact> discoveredModules) {
    return ActionExecutionValue.createFromOutputMetadataStore(
        /* artifactData= */ ImmutableMap.of(output("modules.pcm"), VALUE_1_REMOTE),
        /* treeArtifactData= */ ImmutableMap.of(),
        FilesetOutputTree.EMPTY,
        discoveredModules);
  }

  private static DerivedArtifact output(String rootRelativePath) {
    return output(rootRelativePath, ACTION_LOOKUP_DATA_1);
  }

  private static DerivedArtifact output(
      String rootRelativePath, ActionLookupData generatingAction) {
    DerivedArtifact result =
        DerivedArtifact.create(
            OUTPUT_ROOT,
            OUTPUT_ROOT.getExecPath().getRelative(rootRelativePath),
            generatingAction.getActionLookupKey());
    result.setGeneratingActionKey(generatingAction);
    return result;
  }

  private static SpecialArtifact tree(String rootRelativePath) {
    SpecialArtifact result =
        SpecialArtifact.create(
            OUTPUT_ROOT,
            OUTPUT_ROOT.getExecPath().getRelative(rootRelativePath),
            KEY,
            SpecialArtifactType.TREE);
    result.setGeneratingActionKey(ACTION_LOOKUP_DATA_1);
    return result;
  }
}
