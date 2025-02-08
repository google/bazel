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

package com.google.devtools.build.lib.bazel.bzlmod.modcommand;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.AugmentedModuleBuilder.buildAugmentedModule;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.buildTag;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.Version;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsExpanded;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.OutputFormat;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.OutputFormatters.OutputFormatter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.OutputFormatters.OutputFormatter.Explanation;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.util.MaybeCompleteSet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ModExecutor}. */
@RunWith(JUnit4.class)
public class ModExecutorTest {
  // TODO(andreisolo): Add a Json output test
  // TODO(andreisolo): Add a PATH query test

  private final Writer writer = new StringWriter();

  // Tests for the ModExecutor::expandAndPrune core function.
  //
  // (* In the ASCII graph hints "__>" or "-->" mean a direct edge, while "..>" means an indirect
  // edge. "aaa ..." means module "aaa" is unexpanded.)

  @Test
  public void testExpandFromTargetsFirst() throws ParseException {
    // aaa -> bbb -> ccc -> ddd
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "aaa", Version.parse("1.0"), true)
                    .addDep("bbb", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("bbb", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDep("ccc", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ccc", "1.0")
                    .addStillDependant("bbb", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .put(buildAugmentedModule("ddd", "1.0").addStillDependant("ccc", "1.0").buildEntry())
            .buildOrThrow();

    ModOptions options = ModOptions.getDefaultOptions();
    ModExecutor executor = new ModExecutor(depGraph, options, writer);

    // RESULT:
    // <root> ...> ccc -> ddd
    //       \___> bbb -> ccc ...
    assertThat(
            executor.expandAndPrune(
                ImmutableSet.of(ModuleKey.ROOT, createModuleKey("ccc", "1.0")),
                MaybeCompleteSet.completeSet(),
                false))
        .containsExactly(
            ModuleKey.ROOT,
            ResultNode.builder()
                .addChild(createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("ccc", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                .build(),
            createModuleKey("bbb", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("ccc", "1.0"), IsExpanded.FALSE, IsIndirect.FALSE)
                .build(),
            createModuleKey("ccc", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("ddd", "1.0"),
            ResultNode.builder().build())
        .inOrder();
  }

  @Test
  public void testPathsDepth1_containsAllTargetsWithNestedIndirect() throws ParseException {
    // <root> -> bbb -> ccc -> ddd -> eee -> fff -> ggg -> hhh
    //                          ^     /
    //                           \___/
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "aaa", Version.parse("1.0"), true)
                    .addDep("bbb", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("bbb", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDep("ccc", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ccc", "1.0")
                    .addStillDependant("bbb", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ddd", "1.0")
                    .addStillDependant("ccc", "1.0")
                    .addStillDependant("eee", "1.0")
                    .addDep("eee", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("eee", "1.0")
                    .addStillDependant("ddd", "1.0")
                    .addDep("fff", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("fff", "1.0")
                    .addStillDependant("eee", "1.0")
                    .addDep("ggg", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ggg", "1.0")
                    .addStillDependant("fff", "1.0")
                    .addDep("hhh", "1.0")
                    .buildEntry())
            .put(buildAugmentedModule("hhh", "1.0").addStillDependant("ggg", "1.0").buildEntry())
            .buildOrThrow();

    ModOptions options = ModOptions.getDefaultOptions();
    options.cycles = true;
    options.depth = 1;
    ModExecutor executor = new ModExecutor(depGraph, options, writer);
    MaybeCompleteSet<ModuleKey> targets =
        MaybeCompleteSet.copyOf(
            ImmutableSet.of(createModuleKey("eee", "1.0"), createModuleKey("hhh", "1.0")));

    // RESULT:
    // <root> --> bbb ..> ddd --> eee --> ddd (cycle)
    //                               \..> ggg --> hhh
    assertThat(executor.expandAndPrune(ImmutableSet.of(ModuleKey.ROOT), targets, false))
        .containsExactly(
            ModuleKey.ROOT,
            ResultNode.builder()
                .addChild(createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("bbb", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                .build(),
            createModuleKey("ddd", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("eee", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("eee", "1.0"),
            ResultNode.builder()
                .setTarget(true)
                .addChild(createModuleKey("ggg", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                .addCycle(createModuleKey("ddd", "1.0"))
                .build(),
            createModuleKey("ggg", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("hhh", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("hhh", "1.0"),
            ResultNode.builder().setTarget(true).build())
        .inOrder();
  }

  @Test
  public void testPathsDepth1_targetParentIsDirectAndIndirectChild() throws ParseException {
    // <root> --> bbb --> ccc
    //             \       |________
    //              \      V       |
    //               \__> ddd --> eee
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "aaa", Version.parse("1.0"), true)
                    .addDep("bbb", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("bbb", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDep("ccc", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ccc", "1.0")
                    .addStillDependant("bbb", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("ddd", "1.0")
                    .addStillDependant("bbb", "1.0")
                    .addStillDependant("ccc", "1.0")
                    .addStillDependant("eee", "1.0")
                    .addDep("eee", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("eee", "1.0")
                    .addStillDependant("ddd", "1.0")
                    .addStillDependant("eee", "1.0")
                    .addDep("ddd", "1.0")
                    .buildEntry())
            .buildOrThrow();

    ModOptions options = ModOptions.getDefaultOptions();
    options.cycles = true;
    options.depth = 1;
    ModExecutor executor = new ModExecutor(depGraph, options, writer);
    MaybeCompleteSet<ModuleKey> targets =
        MaybeCompleteSet.copyOf(ImmutableSet.of(createModuleKey("eee", "1.0")));

    // RESULT:
    // <root> --> bbb --- ddd --> eee --> ddd (c)
    //             \
    //              \..> ddd ...
    assertThat(executor.expandAndPrune(ImmutableSet.of(ModuleKey.ROOT), targets, false))
        .containsExactly(
            ModuleKey.ROOT,
            ResultNode.builder()
                .addChild(createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("bbb", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("ddd", "1.0"), IsExpanded.FALSE, IsIndirect.TRUE)
                .build(),
            createModuleKey("ddd", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("eee", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("eee", "1.0"),
            ResultNode.builder().setTarget(true).addCycle(createModuleKey("ddd", "1.0")).build())
        .inOrder();
  }

  // TODO(andreisolo): Add more eventual edge-case tests for the #expandAndPrune core method

  //// Tests for the ModExecutor OutputFormatters
  //

  @Test
  public void testResolutionExplanation_mostCases() throws ParseException {
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "A", Version.parse("1.0"), true)
                    .addDep("B", "1.0")
                    .addDep("C", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("B", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addChangedDep("C", "1.0", "0.1", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .addChangedDep("E", "", "1.0", ResolutionReason.NON_REGISTRY_OVERRIDE)
                    .buildEntry())
            .put(
                buildAugmentedModule("C", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDependant("B", "1.0")
                    .addChangedDep("D", "1.5", "1.0", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                    .buildEntry())
            .put(buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0").buildEntry())
            .put(buildAugmentedModule("D", "1.0").addOriginalDependant("C", "1.0").buildEntry())
            .put(buildAugmentedModule("D", "1.5").addDependant("C", "1.0").buildEntry())
            .put(buildAugmentedModule("E", "1.0").addOriginalDependant("B", "1.0").buildEntry())
            .put(buildAugmentedModule("E", "").addDependant("B", "1.0").buildEntry())
            .buildOrThrow();

    ModOptions options = ModOptions.getDefaultOptions();
    options.verbose = true;
    options.includeUnused = true;

    OutputFormatter formatter = OutputFormatters.getFormatter(OutputFormat.TEXT);
    assertThat(formatter.getExtraResolutionExplanation(ModuleKey.ROOT, null, depGraph, options))
        .isNull();
    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("B", "1.0"), ModuleKey.ROOT, depGraph, options))
        .isNull();

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("C", "1.0"), createModuleKey("B", "1.0"), depGraph, options))
        .isEqualTo(
            Explanation.create(
                Version.parse("0.1"),
                ResolutionReason.MINIMAL_VERSION_SELECTION,
                ImmutableSet.of(ModuleKey.ROOT)));

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("C", "0.1"), createModuleKey("B", "1.0"), depGraph, options))
        .isEqualTo(
            Explanation.create(
                Version.parse("1.0"),
                ResolutionReason.MINIMAL_VERSION_SELECTION,
                ImmutableSet.of(ModuleKey.ROOT)));

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("D", "1.0"), createModuleKey("C", "1.0"), depGraph, options))
        .isEqualTo(
            Explanation.create(
                Version.parse("1.5"), ResolutionReason.SINGLE_VERSION_OVERRIDE, null));

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("D", "1.5"), createModuleKey("C", "1.0"), depGraph, options))
        .isEqualTo(
            Explanation.create(
                Version.parse("1.0"), ResolutionReason.SINGLE_VERSION_OVERRIDE, null));

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("E", "1.0"), createModuleKey("B", "1.0"), depGraph, options))
        .isEqualTo(Explanation.create(Version.EMPTY, ResolutionReason.NON_REGISTRY_OVERRIDE, null));

    assertThat(
            formatter.getExtraResolutionExplanation(
                createModuleKey("E", ""), createModuleKey("B", "1.0"), depGraph, options))
        .isEqualTo(
            Explanation.create(Version.parse("1.0"), ResolutionReason.NON_REGISTRY_OVERRIDE, null));
  }

  @Test
  public void testTextAndGraphOutput_indirectAndNestedTargetPathsWithUnused()
      throws ParseException, IOException {
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "A", Version.parse("1.0"), true)
                    .addDep("B", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("B", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addChangedDep("C", "1.0", "0.1", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                    .addChangedDep("Y", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .buildEntry())
            .put(buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0").buildEntry())
            .put(
                buildAugmentedModule("C", "1.0")
                    .addDependant("B", "1.0")
                    .addDep("D", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("D", "1.0")
                    .addStillDependant("C", "1.0")
                    .addStillDependant("E", "1.0")
                    .addDep("E", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("E", "1.0")
                    .addStillDependant("D", "1.0")
                    .addDep("F", "1.0")
                    .addDep("D", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("F", "1.0")
                    .addStillDependant("E", "1.0")
                    .addDep("G", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("G", "1.0")
                    .addStillDependant("F", "1.0")
                    .addDep("H", "1.0")
                    .addDep("Y", "2.0")
                    .buildEntry())
            .put(buildAugmentedModule("H", "1.0").addStillDependant("G", "1.0").buildEntry())
            .put(buildAugmentedModule("Y", "1.0").addOriginalDependant("B", "1.0").buildEntry())
            .put(
                buildAugmentedModule("Y", "2.0")
                    .addDependant("B", "1.0")
                    .addStillDependant("G", "1.0")
                    .buildEntry())
            .buildOrThrow();

    ImmutableMap<ModuleKey, ResultNode> result =
        ImmutableMap.of(
            ModuleKey.ROOT,
            ResultNode.builder()
                .addChild(createModuleKey("B", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("B", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("Y", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("Y", "2.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("C", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("C", "0.1"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("C", "0.1"),
            ResultNode.builder().setTarget(true).build(),
            createModuleKey("C", "1.0"),
            ResultNode.builder()
                .setTarget(true)
                .addChild(createModuleKey("D", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("D", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("E", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .build(),
            createModuleKey("E", "1.0"),
            ResultNode.builder()
                .setTarget(true)
                .addChild(createModuleKey("G", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                .addCycle(createModuleKey("D", "1.0"))
                .build(),
            createModuleKey("G", "1.0"),
            ResultNode.builder()
                .addChild(createModuleKey("H", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                .addChild(createModuleKey("Y", "2.0"), IsExpanded.FALSE, IsIndirect.FALSE)
                .build(),
            createModuleKey("H", "1.0"),
            ResultNode.builder().setTarget(true).build(),
            createModuleKey("Y", "1.0"),
            ResultNode.builder().setTarget(true).build(),
            createModuleKey("Y", "2.0"),
            ResultNode.builder().setTarget(true).build());

    ModOptions options = ModOptions.getDefaultOptions();
    options.cycles = true;
    options.includeUnused = true;
    options.verbose = true;
    options.depth = 1;
    options.outputFormat = OutputFormat.TEXT;

    File file = File.createTempFile("output_text", "txt");
    file.deleteOnExit();
    Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF_8);

    ModExecutor executor = new ModExecutor(depGraph, options, writer);
    MaybeCompleteSet<ModuleKey> targets =
        MaybeCompleteSet.copyOf(
            ImmutableSet.of(
                createModuleKey("C", "0.1"),
                createModuleKey("C", "1.0"),
                createModuleKey("Y", "1.0"),
                createModuleKey("Y", "2.0"),
                createModuleKey("E", "1.0"),
                createModuleKey("H", "1.0")));

    // Double check for human error
    assertThat(executor.expandAndPrune(ImmutableSet.of(ModuleKey.ROOT), targets, false))
        .isEqualTo(result);

    executor.allPaths(ImmutableSet.of(ModuleKey.ROOT), targets.getElementsIfNotComplete());
    List<String> textOutput = Files.readAllLines(file.toPath());

    assertThat(textOutput)
        .containsExactly(
            "<root> (A@1.0)",
            "└───B@1.0 ",
            "    ├───C@0.1 (to 1.0, cause single_version_override)",
            "    ├───C@1.0 # (was 0.1, cause single_version_override)",
            "    │   └───D@1.0 ",
            "    │       └───E@1.0 # ",
            "    │           ├───D@1.0 (cycle) ",
            "    │           └╌╌╌G@1.0 ",
            "    │               ├───Y@2.0 (*) ",
            "    │               └───H@1.0 ",
            "    ├───Y@1.0 (to 2.0, cause G@1.0)",
            "    └───Y@2.0 (was 1.0, cause G@1.0)",
            "")
        .inOrder();

    options.outputFormat = OutputFormat.GRAPH;
    File fileGraph = File.createTempFile("output_graph", "txt");
    fileGraph.deleteOnExit();
    writer = new OutputStreamWriter(new FileOutputStream(fileGraph), UTF_8);
    executor = new ModExecutor(depGraph, options, writer);

    executor.allPaths(ImmutableSet.of(ModuleKey.ROOT), targets.getElementsIfNotComplete());
    List<String> graphOutput = Files.readAllLines(fileGraph.toPath());

    assertThat(graphOutput)
        .containsExactly(
            "digraph mygraph {",
            "  node [ shape=box ]",
            "  edge [ fontsize=8 ]",
            "  \"<root>\" [ label=\"<root> (A@1.0)\" ]",
            "  \"<root>\" -> \"B@1.0\" [  ]",
            "  \"B@1.0\" -> \"C@0.1\" [ label=SVO ]",
            "  \"B@1.0\" -> \"C@1.0\" [ label=SVO ]",
            "  \"B@1.0\" -> \"Y@1.0\" [ label=MVS ]",
            "  \"B@1.0\" -> \"Y@2.0\" [ label=MVS ]",
            "  \"C@0.1\" [ shape=diamond style=dotted ]",
            "  \"C@1.0\" [ shape=diamond style=solid ]",
            "  \"C@1.0\" -> \"D@1.0\" [  ]",
            "  \"Y@1.0\" [ shape=diamond style=dotted ]",
            "  \"Y@2.0\" [ shape=diamond style=solid ]",
            "  \"D@1.0\" -> \"E@1.0\" [  ]",
            "  \"E@1.0\" [ shape=diamond style=solid ]",
            "  \"E@1.0\" -> \"D@1.0\" [  ]",
            "  \"E@1.0\" -> \"G@1.0\" [ style=dashed ]",
            "  \"G@1.0\" -> \"H@1.0\" [  ]",
            "  \"G@1.0\" -> \"Y@2.0\" [  ]",
            "  \"H@1.0\" [ shape=diamond style=solid ]",
            "}")
        .inOrder();
  }

  @Test
  public void testExtensionsInfoTextAndGraph() throws Exception {
    ImmutableMap<ModuleKey, AugmentedModule> depGraph =
        new ImmutableMap.Builder<ModuleKey, AugmentedModule>()
            .put(
                buildAugmentedModule(ModuleKey.ROOT, "A", Version.parse("1.0"), true)
                    .addDep("B", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("B", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addChangedDep("C", "1.0", "0.1", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                    .addChangedDep("Y", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .buildEntry())
            .put(buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0").buildEntry())
            .put(
                buildAugmentedModule("C", "1.0")
                    .addDependant("B", "1.0")
                    .addDep("D", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("D", "1.0")
                    .addStillDependant("C", "1.0")
                    .addStillDependant("E", "1.0")
                    .addDep("E", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("E", "1.0")
                    .addStillDependant("D", "1.0")
                    .addDep("F", "1.0")
                    .addDep("D", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("F", "1.0")
                    .addStillDependant("E", "1.0")
                    .addDep("G", "1.0")
                    .buildEntry())
            .put(
                buildAugmentedModule("G", "1.0")
                    .addStillDependant("F", "1.0")
                    .addDep("H", "1.0")
                    .addDep("Y", "2.0")
                    .buildEntry())
            .put(buildAugmentedModule("H", "1.0").addStillDependant("G", "1.0").buildEntry())
            .put(buildAugmentedModule("Y", "1.0").addOriginalDependant("B", "1.0").buildEntry())
            .put(
                buildAugmentedModule("Y", "2.0")
                    .addDependant("B", "1.0")
                    .addStillDependant("G", "1.0")
                    .buildEntry())
            .buildOrThrow();

    ModuleExtensionId mavenId = createExtensionId("extensions", "maven");
    ModuleExtensionId gradleId = createExtensionId("extensions", "gradle");
    ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsages =
        new ImmutableTable.Builder<ModuleExtensionId, ModuleKey, ModuleExtensionUsage>()
            .put(
                mavenId,
                createModuleKey("C", "1.0"),
                ModuleExtensionUsage.builder()
                    .setExtensionBzlFile("//extensions:extensions.bzl")
                    .setExtensionName("maven")
                    .setRepoOverrides(ImmutableMap.of())
                    .addProxy(
                        ModuleExtensionUsage.Proxy.builder()
                            .setLocation(Location.fromFileLineColumn("C@1.0/MODULE.bazel", 2, 23))
                            .setImports(ImmutableBiMap.of("repo1", "repo1", "repo3", "repo3"))
                            .setDevDependency(false)
                            .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                            .build())
                    .build())
            .put(
                mavenId,
                createModuleKey("D", "1.0"),
                ModuleExtensionUsage.builder()
                    .setExtensionBzlFile("//extensions:extensions.bzl")
                    .setExtensionName("maven")
                    .setRepoOverrides(ImmutableMap.of())
                    .addProxy(
                        ModuleExtensionUsage.Proxy.builder()
                            .setLocation(Location.fromFileLineColumn("D@1.0/MODULE.bazel", 1, 10))
                            .setImports(ImmutableBiMap.of("repo1", "repo1", "repo2", "repo2"))
                            .setDevDependency(false)
                            .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                            .build())
                    .build())
            .put(
                gradleId,
                createModuleKey("Y", "2.0"),
                ModuleExtensionUsage.builder()
                    .setExtensionBzlFile("//extensions:extensions.bzl")
                    .setExtensionName("gradle")
                    .setRepoOverrides(ImmutableMap.of())
                    .addProxy(
                        ModuleExtensionUsage.Proxy.builder()
                            .setLocation(Location.fromFileLineColumn("Y@2.0/MODULE.bazel", 2, 13))
                            .setImports(ImmutableBiMap.of("repo2", "repo2"))
                            .setDevDependency(false)
                            .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                            .build())
                    .build())
            .put(
                mavenId,
                createModuleKey("Y", "2.0"),
                ModuleExtensionUsage.builder()
                    .setExtensionBzlFile("//extensions:extensions.bzl")
                    .setExtensionName("maven")
                    .setRepoOverrides(ImmutableMap.of())
                    .addProxy(
                        ModuleExtensionUsage.Proxy.builder()
                            .setLocation(Location.fromFileLineColumn("Y@2.0/MODULE.bazel", 13, 10))
                            .setImports(ImmutableBiMap.of("myrepo", "repo5"))
                            .setDevDependency(false)
                            .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                            .build())
                    .addTag(buildTag("dep").addAttr("coord", "junit").build())
                    .addTag(buildTag("dep").addAttr("coord", "guava").build())
                    .addTag(
                        buildTag("pom")
                            .addAttr(
                                "pom_xmls",
                                StarlarkList.immutableOf("//:pom.xml", "@bar//:pom.xml"))
                            .build())
                    .build())
            .buildOrThrow();

    File file = File.createTempFile("output_text", "txt");
    file.deleteOnExit();
    Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF_8);

    // Contains the already-filtered map of target extensions along with their full list of repos
    ImmutableSetMultimap<ModuleExtensionId, String> extensionRepos =
        new ImmutableSetMultimap.Builder<ModuleExtensionId, String>()
            .putAll(mavenId, ImmutableSet.of("repo6", "repo1", "repo2", "repo3", "repo4", "repo5"))
            .putAll(gradleId, ImmutableSet.of("repo1", "repo2"))
            .build();

    ModOptions options = ModOptions.getDefaultOptions();
    options.outputFormat = OutputFormat.TEXT;
    options.extensionInfo = ExtensionShow.ALL;

    ModExecutor executor =
        new ModExecutor(
            depGraph, extensionUsages, extensionRepos, Optional.empty(), options, writer);

    executor.graph(ImmutableSet.of(ModuleKey.ROOT));

    List<String> textOutput = Files.readAllLines(file.toPath());

    assertThat(textOutput)
        .containsExactly(
            "<root> (A@1.0)",
            "└───B@1.0 ",
            "    ├───C@1.0 ",
            "    │   ├───$@@//extensions:extensions%maven ",
            "    │   │   ├───repo1",
            "    │   │   ├───repo3",
            "    │   │   ├╌╌╌repo4",
            "    │   │   └╌╌╌repo6",
            "    │   └───D@1.0 ",
            "    │       ├───$@@//extensions:extensions%maven ... ",
            "    │       │   ├───repo1",
            "    │       │   └───repo2",
            "    │       └───E@1.0 ",
            "    │           └───F@1.0 ",
            "    │               └───G@1.0 ",
            "    │                   ├───Y@2.0 (*) ",
            "    │                   └───H@1.0 ",
            "    └───Y@2.0 ",
            "        ├───$@@//extensions:extensions%gradle ",
            "        │   ├───repo2",
            "        │   └╌╌╌repo1",
            "        └───$@@//extensions:extensions%maven ... ",
            "            └───repo5",
            "")
        .inOrder();

    options.outputFormat = OutputFormat.GRAPH;
    File fileGraph = File.createTempFile("output_graph", "txt");
    fileGraph.deleteOnExit();
    writer = new OutputStreamWriter(new FileOutputStream(fileGraph), UTF_8);
    executor =
        new ModExecutor(
            depGraph, extensionUsages, extensionRepos, Optional.empty(), options, writer);

    executor.graph(ImmutableSet.of(ModuleKey.ROOT));
    List<String> graphOutput = Files.readAllLines(fileGraph.toPath());

    assertThat(graphOutput)
        .containsExactly(
            "digraph mygraph {",
            "  node [ shape=box ]",
            "  edge [ fontsize=8 ]",
            "  \"<root>\" [ label=\"<root> (A@1.0)\" ]",
            "  \"<root>\" -> \"B@1.0\" [  ]",
            "  \"B@1.0\" -> \"C@1.0\" [  ]",
            "  \"B@1.0\" -> \"Y@2.0\" [  ]",
            "  subgraph \"cluster_@@//extensions:extensions%maven\" {",
            "    label=\"@@//extensions:extensions%maven\"",
            "    \"@@//extensions:extensions%maven%repo1\" [ label=\"repo1\" ]",
            "    \"@@//extensions:extensions%maven%repo2\" [ label=\"repo2\" ]",
            "    \"@@//extensions:extensions%maven%repo3\" [ label=\"repo3\" ]",
            "    \"@@//extensions:extensions%maven%repo5\" [ label=\"repo5\" ]",
            "    \"@@//extensions:extensions%maven%repo4\" [ label=\"repo4\" style=dotted ]",
            "    \"@@//extensions:extensions%maven%repo6\" [ label=\"repo6\" style=dotted ]",
            "  }",
            "  \"C@1.0\" -> \"@@//extensions:extensions%maven%repo1\"",
            "  \"C@1.0\" -> \"@@//extensions:extensions%maven%repo3\"",
            "  \"C@1.0\" -> \"D@1.0\" [  ]",
            "  subgraph \"cluster_@@//extensions:extensions%gradle\" {",
            "    label=\"@@//extensions:extensions%gradle\"",
            "    \"@@//extensions:extensions%gradle%repo2\" [ label=\"repo2\" ]",
            "    \"@@//extensions:extensions%gradle%repo1\" [ label=\"repo1\" style=dotted ]",
            "  }",
            "  \"Y@2.0\" -> \"@@//extensions:extensions%gradle%repo2\"",
            "  \"Y@2.0\" -> \"@@//extensions:extensions%maven%repo5\"",
            "  \"D@1.0\" -> \"@@//extensions:extensions%maven%repo1\"",
            "  \"D@1.0\" -> \"@@//extensions:extensions%maven%repo2\"",
            "  \"D@1.0\" -> \"E@1.0\" [  ]",
            "  \"E@1.0\" -> \"F@1.0\" [  ]",
            "  \"F@1.0\" -> \"G@1.0\" [  ]",
            "  \"G@1.0\" -> \"H@1.0\" [  ]",
            "  \"G@1.0\" -> \"Y@2.0\" [  ]",
            "}")
        .inOrder();

    options.outputFormat = OutputFormat.TEXT;
    options.depth = 1;
    File fileText2 = File.createTempFile("output_text2", "txt");
    fileText2.deleteOnExit();
    writer = new OutputStreamWriter(new FileOutputStream(fileText2), UTF_8);
    executor =
        new ModExecutor(
            depGraph,
            extensionUsages,
            extensionRepos,
            Optional.of(MaybeCompleteSet.copyOf(ImmutableSet.of(mavenId))),
            options,
            writer);

    executor.allPaths(
        ImmutableSet.of(ModuleKey.ROOT), ImmutableSet.of(createModuleKey("Y", "2.0")));
    List<String> textOutput2 = Files.readAllLines(fileText2.toPath());

    assertThat(textOutput2)
        .containsExactly(
            "<root> (A@1.0)",
            "└───B@1.0 ",
            "    ├───C@1.0 # ",
            "    │   ├───$@@//extensions:extensions%maven ",
            "    │   │   ├───repo1",
            "    │   │   ├───repo3",
            "    │   │   ├╌╌╌repo4",
            "    │   │   └╌╌╌repo6",
            "    │   └───D@1.0 # ",
            "    │       ├───$@@//extensions:extensions%maven ... ",
            "    │       │   ├───repo1",
            "    │       │   └───repo2",
            "    │       └╌╌╌G@1.0 ",
            "    │           └───Y@2.0 (*) ",
            "    └───Y@2.0 # ",
            "        └───$@@//extensions:extensions%maven ... ",
            "            └───repo5",
            "")
        .inOrder();
  }

  private ModuleExtensionId createExtensionId(String targetName, String extensionName)
      throws LabelSyntaxException {
    return ModuleExtensionId.create(
        Label.create(PackageIdentifier.createInMainRepo(targetName), targetName),
        extensionName,
        Optional.empty());
  }
}
