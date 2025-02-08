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
package com.google.devtools.build.lib.skyframe.rewinding;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputDepOwnerMap;
import com.google.devtools.build.lib.actions.ActionInputDepOwners;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.CompletionContext.ArtifactReceiver;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.actions.EventReportingArtifacts.ReportedArtifacts;
import com.google.devtools.build.lib.actions.LostInputsExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.AspectCompleteEvent;
import com.google.devtools.build.lib.analysis.TargetCompleteEvent;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions.JobsConverter;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.RecordingBugReporter;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.exec.SpawnExecException;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.ActionRewinding;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.testutil.ActionEventRecorder;
import com.google.devtools.build.lib.testutil.ControllableActionStrategyModule;
import com.google.devtools.build.lib.testutil.SpawnController;
import com.google.devtools.build.lib.testutil.SpawnController.ExecResult;
import com.google.devtools.build.lib.testutil.SpawnController.SpawnShim;
import com.google.devtools.build.lib.testutil.SpawnInputUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.NotifyingHelper;
import com.google.devtools.build.skyframe.NotifyingHelper.EventType;
import com.google.devtools.build.skyframe.NotifyingHelper.Order;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueWithMetadata;
import com.google.devtools.build.skyframe.proto.GraphInconsistency.Inconsistency;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements rewinding-specific infrastructure and test logic used for rewinding tests. Search for
 * the callers of these methods to find the {@link BuildIntegrationTestCase} classes where the
 * build-specific infrastructure and the actual tests are implemented.
 *
 * <p>In this class, methods whose names begin with {@code run} implement test logic. Generally,
 * these tests have the following structure:
 *
 * <ol>
 *   <li>{@code BUILD}, {@code bzl}, and other source file setup.
 *   <li>Injection of one or more {@linkplain SpawnShim spawn shims}, giving the test control of
 *       whether execution succeeds or fails with either an appropriately structured {@link
 *       LostInputsExecException} or an {@link IOException}. These shims also occasionally capture
 *       input file contents to be checked after the build is done.
 *   <li>Injection of a {@link
 *       com.google.devtools.build.skyframe.MemoizingEvaluator.GraphTransformerForTesting} so that
 *       the invalidated Skyframe nodes can be tracked, along with the order they're invalidated in.
 *   <li>The build itself.
 *   <li>Assertions of (some subset of):
 *       <ul>
 *         <li>the build's output's contents
 *         <li>what spawn actions were run (and, when possible, in what order)
 *         <li>what events were emitted
 *         <li>what Skyframe nodes were invalidated and in what order
 *       </ul>
 * </ol>
 *
 * <p>Tests are largely of two structurally similar but distinguishable categories:
 *
 * <ol>
 *   <li>Tests that check the rewinding strategy's behavior and how it interacts with build logic
 *       under varying circumstances, like {@link #runActionFromPreviousBuildReevaluated}, {@link
 *       #runIneffectiveRewindingResultsInLostInputTooManyTimes}, {@link
 *       #runInterruptedDuringRewindStopsNormally}.
 *   <li>Tests that check the behavior of the execution strategy and Skyframe action execution
 *       machinery to make sure they collaborate to give the action rewinding strategy the
 *       information it needs to figure out what Skyframe nodes need to be rewound. Examples of
 *       these include {@link #runDependentActionsReevaluated}, {@link #runTreeFileArtifactRewound},
 *       and others, which test different combinations of types of action inputs which can get lost.
 * </ol>
 */
public class RewindingTestsHelper {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  final ActionEventRecorder recorder;
  final BuildIntegrationTestCase testCase;
  private final SpawnController spawnController = new SpawnController();
  final LostImportantOutputHandlerModule lostOutputsModule;

  RewindingTestsHelper(BuildIntegrationTestCase testCase, ActionEventRecorder recorder) {
    this.testCase = checkNotNull(testCase);
    this.recorder = checkNotNull(recorder);
    this.lostOutputsModule = createLostOutputsModule();
  }

  public final LostImportantOutputHandlerModule getLostOutputsModule() {
    return lostOutputsModule;
  }

  /**
   * Returns whether the execution strategy can handle rewinding happening concurrently with another
   * action consuming the rewound action's outputs.
   *
   * <p>When an action is rewound, it executes a second time, including the {@link Action#prepare}
   * step which deletes previous outputs on disk. Another action which observed its dependency to be
   * done (before rewinding was initiated) may simultaneously attempt to consume these deleted
   * outputs, leading to a flaky build failure. If this method returns {@code false}, test cases
   * which exercise the described scenario will set {@code --jobs=1} to avoid the race condition.
   */
  @ForOverride
  boolean supportsConcurrentRewinding() {
    return false;
  }

  /**
   * Converts a file digest to a hex string compatible with the test's active {@link
   * com.google.devtools.build.lib.vfs.DigestHashFunction}.
   */
  @ForOverride
  String toHex(byte[] digest) {
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  @ForOverride
  LostImportantOutputHandlerModule createLostOutputsModule() {
    return new LostImportantOutputHandlerModule(this::toHex);
  }

  public final ControllableActionStrategyModule makeControllableActionStrategyModule(
      String identifier) {
    return new ControllableActionStrategyModule(spawnController, identifier);
  }

  public final ImmutableList<String> getExecutedSpawnDescriptions() {
    return spawnController.getExecutedSpawnDescriptions();
  }

  public final void clearExecutedSpawnDescriptions() {
    spawnController.clearExecutedSpawnDescriptions();
  }

  public final void addSpawnShim(String spawnDescription, SpawnShim spawnShim) {
    spawnController.addSpawnShim(spawnDescription, spawnShim);
  }

  public final void verifyAllSpawnShimsConsumed() {
    spawnController.verifyAllShimsConsumed();
  }

  public final ExecResult createLostInputsExecException(
      ActionExecutionContext context,
      ImmutableList<ActionInput> lostInputs,
      ActionInputDepOwners owners)
      throws IOException {
    ImmutableMap.Builder<String, ActionInput> builder = ImmutableMap.builder();
    for (ActionInput lostInput : lostInputs) {
      builder.put(getHexDigest(lostInput, context), lostInput);
    }
    return ExecResult.ofException(new LostInputsExecException(builder.buildOrThrow(), owners));
  }

  private String getHexDigest(ActionInput input, ActionExecutionContext context)
      throws IOException {
    return toHex(context.getInputMetadataProvider().getInputMetadata(input).getDigest());
  }

  static ActionInputDepOwners getInputOwners(Multimap<ActionInput, Artifact> mappings) {
    ActionInputDepOwnerMap owners = new ActionInputDepOwnerMap(mappings.keySet());
    mappings.forEach(owners::addOwner);
    return owners;
  }

  /**
   * Injects a {@link NotifyingHelper.Listener} that collects keys rewound by rewinding into the
   * returned list, starting with the next build.
   *
   * <p>To avoid brittle assertions on the number of keys rewound, {@link ArtifactNestedSetKey} is
   * not collected, though it may be rewound. Its {@link
   * com.google.devtools.build.lib.collect.nestedset.NestedSet} may contain multiple paths (of
   * varying length) to a lost artifact, any of which would be a correct chain for rewinding.
   */
  public final List<SkyKey> collectOrderedRewoundKeys() {
    List<SkyKey> rewoundKeys = Collections.synchronizedList(new ArrayList<>());
    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (type.equals(NotifyingHelper.EventType.MARK_DIRTY) && order.equals(Order.AFTER)) {
            NotifyingHelper.MarkDirtyAfterContext markDirtyAfterContext =
                (NotifyingHelper.MarkDirtyAfterContext) context;
            if (markDirtyAfterContext.dirtyType() == DirtyType.REWIND
                && markDirtyAfterContext.actuallyDirtied()
                // Ignore ArtifactNestedSetKey. See method javadoc.
                && !(key instanceof ArtifactNestedSetKey)) {
              rewoundKeys.add(key);
            }
          }
        });
    return rewoundKeys;
  }

  static void assertOnlyActionsRewound(List<SkyKey> rewoundKeys) {
    for (SkyKey key : rewoundKeys) {
      if (!(key instanceof ArtifactNestedSetKey)) {
        assertThat(key).isInstanceOf(ActionLookupData.class);
      }
    }
  }

  static ImmutableList<String> rewoundArtifactOwnerLabels(List<SkyKey> rewoundKeys) {
    return rewoundKeys.stream()
        .filter(k -> k instanceof ActionLookupData)
        .map(k -> ((ActionLookupData) k).getActionLookupKey().getLabel().getCanonicalForm())
        .collect(toImmutableList());
  }

  static void assertArtifactKey(SkyKey skyKey, String path) {
    assertThat(skyKey).isInstanceOf(Artifact.class);
    assertThat(((Artifact) skyKey).getRootRelativePathString()).isEqualTo(path);
  }

  static void assertActionKey(SkyKey skyKey, String label, int index) {
    assertThat(skyKey).isInstanceOf(ActionLookupData.class);
    assertThat(((ActionLookupData) skyKey).getLabel().getCanonicalForm()).isEqualTo(label);
    assertThat(((ActionLookupData) skyKey).getActionIndex()).isEqualTo(index);
  }

  static void assertTreeArtifactRewound(List<SkyKey> rewoundKeys, String lostTree) {
    assertThat(rewoundKeys).hasSize(2);
    assertThat(rewoundKeys.get(1)).isInstanceOf(SpecialArtifact.class);
    SpecialArtifact treeArtifact = (SpecialArtifact) rewoundKeys.get(1);
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    assertThat(treeArtifact.getRootRelativePathString()).isEqualTo(lostTree);
    assertThat(rewoundKeys.get(0)).isEqualTo(treeArtifact.getGeneratingActionKey());
  }

  static String latin1StringFromActionInput(ActionExecutionContext context, ActionInput input)
      throws IOException {
    // Test logic implemented here requires that files whose contents will be read locally have the
    // suffix ".inlined". Tests using remote execution should be configured to eagerly fetch these
    // artifacts.
    checkArgument(
        input.getExecPathString().endsWith(".inlined"),
        "Only inputs ending in .inlined are guaranteed readable. Tried to read: %s",
        input);
    return new String(readContentAsLatin1(context.getInputPath(input)));
  }

  /**
   * Builds the genrule "//{@code pkg}:consume_output", which must specify "//{@code
   * pkg}:output.inlined" as a "srcs" dep. Returns the contents of {@code output.inlined} as a
   * latin1 {@link String}.
   *
   * <p>This is useful for builds that do not write output files to disk, and so those files'
   * contents can't be verified via regular filesystem operations. This method extracts {@code
   * output.inlined}'s contents during evaluation.
   */
  final String buildAndGetOutput(String pkg, BuildIntegrationTestCase testCase) throws Exception {
    AtomicReference<String> invocationOutput = new AtomicReference<>(null);
    addSpawnShim(
        String.format("Executing genrule //%s:consume_output", pkg),
        (spawn, context) -> {
          ActionInput actionInput = SpawnInputUtils.getInputWithName(spawn, "output.inlined");
          invocationOutput.set(latin1StringFromActionInput(context, actionInput));
          return ExecResult.delegate();
        });
    testCase.buildTarget(String.format("//%s:consume_output", pkg));
    return invocationOutput.get();
  }

  public final void runNoLossSmokeTest() throws Exception {
    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1",
            srcs = ["source.txt"],
            outs = ["intermediate.txt"],
            cmd = "(cat $< && echo from rule1) > $@",
        )

        genrule(
            name = "rule2",
            srcs = ["intermediate.txt"],
            outs = ["output.inlined"],
            cmd = "(cat $< && echo from rule2) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);
    testCase.write("test/source.txt", "source");

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    String outputFileContent = buildAndGetOutput("test", testCase);

    assertThat(outputFileContent).isEqualTo("source\nfrom rule1\nfrom rule2\n");
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:consume_output")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Executing genrule //test:rule1", "Executing genrule //test:rule2"),
        /* completedRewound= */ ImmutableList.of(),
        /* failedRewound= */ ImmutableList.of(),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(0));

    assertThat(rewoundKeys).isEmpty();
  }

  public final void runLostInputWithRewindingDisabled() throws Exception {
    testCase.write(
        "foo/BUILD",
        """
        genrule(name = 'top', outs = ['top.out'], srcs = [':dep'], cmd = 'cp $< $@')
        genrule(name = 'dep', outs = ['dep.out'], cmd = 'touch $@')
        """);
    testCase.addOptions("--norewind_lost_inputs");
    addSpawnShim(
        "Executing genrule //foo:top",
        (spawn, context) -> {
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "dep.out"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    var e = assertThrows(BuildFailedException.class, () -> testCase.buildTarget("//foo:top"));
    assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
        .isEqualTo(ActionRewinding.Code.LOST_INPUT_REWINDING_DISABLED);
    testCase.assertContainsError("Executing genrule //foo:top failed: lost inputs with digests");
  }

  /**
   * Tests that {@link Inconsistency#BUILDING_PARENT_FOUND_UNDONE_CHILD} is not tolerated if there
   * has not been any rewinding.
   */
  public final void runBuildingParentFoundUndoneChildNotToleratedWithoutRewinding()
      throws Exception {
    BugReporter bugReporter = mock(BugReporter.class);
    testCase.setCustomBugReporterAndReinitialize(bugReporter);
    testCase.write(
        "foo/BUILD",
        """
        genrule(
            name = "top",
            srcs = [":dep"],
            outs = ["top.out"],
            cmd = "cp $< $@",
        )

        genrule(
            name = "dep",
            outs = ["dep.out"],
            cmd = "touch $@",
        )
        """);
    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (type == EventType.GET_BATCH
              && order == Order.BEFORE
              && context == Reason.PREFETCH
              && isActionExecutionKey(key, Label.parseCanonicalUnchecked("//foo:dep"))) {
            try {
              testCase
                  .getSkyframeExecutor()
                  .getEvaluator()
                  .getExistingEntryAtCurrentlyEvaluatingVersion(key)
                  .markDirty(DirtyType.REWIND);
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          }
        });

    Exception e =
        assertThrows(IllegalStateException.class, () -> testCase.buildTarget("//foo:top"));
    assertThat(e).hasMessageThat().contains("Unexpected undone children");
    verify(bugReporter).handleCrash(any(), any());
  }

  public final void runDependentActionsReevaluated_spawnFailed() throws Exception {
    // The first time rule2 is executed, the execution strategy fails, saying that rule2's two input
    // files are missing.
    runDependentActionsReevaluated(
        (spawn, context) ->
            createLostInputsExecException(
                context,
                getIntermediate1And2LostInputs(spawn),
                new ActionInputDepOwnerMap(getIntermediate1And2LostInputs(spawn))));
  }

  static ImmutableList<ActionInput> getIntermediate1And2LostInputs(Spawn spawn) {
    return ImmutableList.of(
        SpawnInputUtils.getInputWithName(spawn, "intermediate_1.txt"),
        SpawnInputUtils.getInputWithName(spawn, "intermediate_2.txt"));
  }

  final void runDependentActionsReevaluated(SpawnShim shim) throws Exception {
    // This test sets up a genrule, rule2, that consumes the outputs of two other genrules.
    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1_1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat $< && echo from rule1_1) > $@",
        )

        genrule(
            name = "rule1_2",
            srcs = ["source_2.txt"],
            outs = ["intermediate_2.txt"],
            cmd = "(cat $< && echo from rule1_2) > $@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat $(SRCS) && echo from rule2) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);

    testCase.write("test/source_1.txt", "source_1");
    testCase.write("test/source_2.txt", "source_2");
    testCase.write("test/source_3.txt", "source_3");

    addSpawnShim("Executing genrule //test:rule2", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    String outputFileContents = buildAndGetOutput("test", testCase);

    // The evaluation succeeds, producing the expected output, after re-executing rule1_1's and
    // rule1_2's actions.
    assertThat(outputFileContents)
        .isEqualTo("source_1\nfrom rule1_1\nsource_2\nfrom rule1_2\nsource_3\nfrom rule2\n");

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1_1",
            "Executing genrule //test:rule1_2",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1_1",
            "Executing genrule //test:rule1_2",
            "Executing genrule //test:rule2",
            "Executing genrule //test:consume_output");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of(
            "Executing genrule //test:rule1_1", "Executing genrule //test:rule1_2"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule2"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(2));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
        .containsExactly("//test:rule1_1", "//test:rule1_2");
  }

  private static void writeTwoGenrulePackage(BuildIntegrationTestCase testCase) throws IOException {
    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate.txt"],
            cmd = "(cat $< && echo from rule1) > $@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate.txt",
                "source_2.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat $(SRCS) && echo from rule2) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);

    testCase.write("test/source_1.txt", "source_1");
    testCase.write("test/source_2.txt", "source_2");
  }

  public final void runActionFromPreviousBuildReevaluated() throws Exception {
    // This test sets up a genrule, rule2, that consumes the outputs of rule1. rule1 is requested on
    // the first build, so that on the second build, when rule2 discovers its missing input, rule1
    // is cached.
    writeTwoGenrulePackage(testCase);

    testCase.buildTarget("//test:rule1");
    assertThat(getExecutedSpawnDescriptions()).containsExactly("Executing genrule //test:rule1");

    clearExecutedSpawnDescriptions();
    // The first time rule2 is executed, the execution strategy fails, saying that rule2's input
    // file is missing.
    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "intermediate.txt"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    String outputFileContents = buildAndGetOutput("test", testCase);

    // The evaluation succeeds, producing the expected output, after re-executing rule1's action.
    assertThat(outputFileContents).isEqualTo("source_1\nfrom rule1\nsource_2\nfrom rule2\n");

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:consume_output");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:rule1"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule2"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(0, 1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1");
  }

  public final void runIneffectiveRewindingResultsInLostInputTooManyTimes() throws Exception {
    // This test sets up two genrules, and makes the several execution attempts of rule2 fail,
    // saying that the file produced by rule1 is missing. The last time rule2 fails because of the
    // same lost input, rewinding is not attempted, and the build fails with a
    // LOST_INPUT_TOO_MANY_TIMES detailed exit code.
    writeTwoGenrulePackage(testCase);

    // Store a reference to the input so that we can match the exception message. The output
    // directory name (and hence the string representation) varies by platform.
    AtomicReference<ActionInput> intermediate = new AtomicReference<>();
    for (int i = 0; i <= ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS; i++) {
      addSpawnShim(
          "Executing genrule //test:rule2",
          (spawn, context) -> {
            intermediate.set(SpawnInputUtils.getInputWithName(spawn, "intermediate.txt"));
            return ExecResult.ofException(
                new LostInputsExecException(
                    ImmutableMap.of("fakedigest", intermediate.get()),
                    new ActionInputDepOwnerMap(ImmutableList.of(intermediate.get()))));
          });
    }

    RecordingBugReporter bugReporter = testCase.recordBugReportsAndReinitialize();
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> testCase.buildTarget("//test:rule2"));
    assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
        .isEqualTo(ActionRewinding.Code.LOST_INPUT_TOO_MANY_TIMES);

    String errorDetail =
        String.format(
            "lost input too many times (#%s) for the same action. lostInput: %s, "
                + "lostInput digest: fakedigest, "
                + "failedAction: action 'Executing genrule //test:rule2'",
            ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1, intermediate.get());
    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage()).contains(errorDetail);
    assertThat(Iterables.getOnlyElement(bugReporter.getExceptions()))
        .hasMessageThat()
        .contains(errorDetail);

    assertThat(getExecutedSpawnDescriptions())
        .containsExactlyElementsIn(
            Iterables.concat(
                Collections.nCopies(
                    ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1,
                    ImmutableList.of(
                        "Executing genrule //test:rule1", "Executing genrule //test:rule2"))))
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:rule1"),
        /* failedRewound= */ ImmutableList.of(),
        /* expectResultReceivedForFailedRewound= */ false,
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(
            ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(Iterables.frequency(rewoundArtifactOwnerLabels(rewoundKeys), "//test:rule1"))
        .isEqualTo(ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS);
  }

  /**
   * Create N genrules that are dependent on a static source file. And then create another N
   * genrules that will consume the previous genrules equal to its index from 1 to N. For example,
   * if N = 3, the first consume genrule will contain 'rule1', the second consume genrule will
   * contain 'rule1' and 'rule2', and the third consume genrule will contain 'rule1', 'rule2', and
   * 'rule3'. Lastly there is a genrule that will have an output: 'output.inlined' that contains all
   * N consume genrules as sources; this is used to assert that no output file remains after the
   * test case.
   */
  private void writeNGenrulePackages(int n) throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      testCase.write("test/source_" + i + ".txt", "source_" + i);
      lines.add("genrule(");
      lines.add("    name = 'rule" + i + "',");
      lines.add("    srcs = ['source_" + i + ".txt'],");
      lines.add("    outs = ['out_" + i + ".txt'],");
      lines.add("    cmd = '(cat $(SRCS) && echo from rule" + i + ") > $@') ");
      lines.add("");
    }
    StringBuilder outs = new StringBuilder();
    for (int i = 1; i <= n; i++) {
      String out = " 'consume_" + i + ".out', ";
      outs.append(out);
      StringBuilder entries = new StringBuilder();
      for (int e = 1; e <= i; e++) {
        entries.append("':out_").append(e).append(".txt', ");
      }
      lines.add("genrule(");
      lines.add("    name = 'consume_" + i + "',");
      lines.add("    srcs = [" + entries + "],");
      lines.add("    outs = [" + out + "],");
      lines.add("    cmd = '(cat $(SRCS) && echo from consume_" + i + ") > $@') ");
      lines.add("");
    }
    lines.add("genrule(");
    lines.add("    name = 'consume_output',");
    lines.add("    srcs = [" + outs + "],");
    lines.add("    outs = ['output.inlined'],");
    lines.add("    cmd = 'touch $@')");
    String[] writeLines = new String[lines.size()];
    for (int i = 0; i < lines.size(); i++) {
      writeLines[i] = lines.get(i);
    }
    testCase.write("test/BUILD", writeLines);
  }

  /**
   * This test sets up {@link ActionRewindStrategy#MAX_ACTION_REWIND_EVENTS} + 1 (N) genrules that
   * consume 1 ... N inputs respectively and will build each of the genrules. All N inputs will be
   * lost and throw a {@link LostInputsExecException} such that all of the genrule actions will
   * rewind. The {@link ActionRewindingStats} event will contain the top {@link
   * ActionRewindStrategy#MAX_ACTION_REWIND_EVENTS} action rewind events based on the maximum number
   * of nodes invalidated for each rewind action plan. The expected action rewind events logged will
   * not contain the genrule action with one input.
   */
  public final void runMultipleLostInputsForRewindPlan() throws Exception {
    if (!supportsConcurrentRewinding()) {
      testCase.addOptions("--jobs=1");
    }
    writeNGenrulePackages(ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1);
    for (int i = 1; i <= ActionRewindStrategy.MAX_ACTION_REWIND_EVENTS + 1; i++) {
      final int target = i;
      addSpawnShim(
          "Executing genrule //test:consume_" + target,
          (spawn, context) -> {
            ImmutableMap.Builder<String, ActionInput> inputMapBuilder = ImmutableMap.builder();
            for (int e = 1; e <= target; e++) {
              ActionInput input = SpawnInputUtils.getInputWithName(spawn, "out_" + e + ".txt");
              inputMapBuilder.put("fake_digest_" + target + "_" + e, input);
            }
            ImmutableMap<String, ActionInput> inputMap = inputMapBuilder.buildOrThrow();
            return ExecResult.ofException(
                new LostInputsExecException(
                    inputMap, new ActionInputDepOwnerMap(inputMap.values())));
          });
    }
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget(
        "//test:consume_1",
        "//test:consume_2",
        "//test:consume_3",
        "//test:consume_4",
        "//test:consume_5",
        "//test:consume_6");
    assertOnlyActionsRewound(rewoundKeys);
    verifyAllSpawnShimsConsumed();
    recorder.assertTotalLostInputCountsFromStats(ImmutableList.of(21));
  }

  public final void runInterruptedDuringRewindStopsNormally() throws Exception {
    // This test sets up two genrules, and makes the first execution of rule2 fail, saying that the
    // file produced by rule1 is missing. Before rule1 is re-executed, the test interrupts the
    // build. The build should stop with an interrupt normally (and not crash).
    writeTwoGenrulePackage(testCase);

    Thread mainThread = Thread.currentThread();
    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          addSpawnShim(
              "Executing genrule //test:rule1",
              (ignoredSpawn, ignoredContext) -> {
                mainThread.interrupt();
                return ExecResult.delegate();
              });

          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "intermediate.txt"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    assertThrows(InterruptedException.class, () -> testCase.buildTarget("//test:rule2"));

    assertOutputForStopBeforeRewoundReexecution();

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1");
  }

  private void assertOutputForStopBeforeRewoundReexecution() {
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of("Executing genrule //test:rule1"),
        /* completedRewound= */ ImmutableList.of(),
        /* failedRewound= */ ImmutableList.of(),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));
    assertThat(
            recorder.getActionStartedEvents().stream()
                .map(e -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()))
                .filter("Executing genrule //test:rule2"::equals)
                .count())
        .isEqualTo(1);
    assertThat(
            recorder.getActionCompletionEvents().stream()
                .map(e -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()))
                .filter("Executing genrule //test:rule2"::equals)
                .count())
        .isEqualTo(0);
    assertThat(
            recorder.getActionExecutedEvents().stream()
                .map(e -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()))
                .filter("Executing genrule //test:rule2"::equals)
                .count())
        .isEqualTo(0);
    assertThat(
            recorder.getActionResultReceivedEvents().stream()
                .map(e -> ActionEventRecorder.progressMessageOrPrettyPrint(e.getAction()))
                .filter("Executing genrule //test:rule2"::equals)
                .count())
        .isEqualTo(0);
    assertThat(
            recorder.getActionRewoundEvents().stream()
                .map(
                    e ->
                        ActionEventRecorder.progressMessageOrPrettyPrint(
                            e.getFailedRewoundAction()))
                .filter("Executing genrule //test:rule2"::equals)
                .count())
        .isEqualTo(1);
  }

  private static final SpawnResult FAILED_RESULT =
      new SpawnResult.Builder()
          .setStatus(SpawnResult.Status.NON_ZERO_EXIT)
          .setExitCode(1)
          .setFailureDetail(
              FailureDetail.newBuilder()
                  .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
                  .build())
          .setRunnerName("remote")
          .build();

  public final void runFailureDuringRewindStopsNormally() throws Exception {
    // This test sets up two genrules, and makes the first execution of rule2 fail, saying that the
    // file produced by rule1 is missing. The execution of rule1 fails. The build should stop with
    // that failure (and not crash).
    writeTwoGenrulePackage(testCase);

    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          addSpawnShim(
              "Executing genrule //test:rule1",
              (ignoredSpawn, ignoredContext) ->
                  ExecResult.ofException(
                      new SpawnExecException(
                          "kaboom",
                          FAILED_RESULT,
                          /* forciblyRunRemotely= */ false,
                          /* catastrophe= */ false)));

          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "intermediate.txt"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    BuildFailedException buildFailedException =
        assertThrows(BuildFailedException.class, () -> testCase.buildTarget("//test:rule2"));

    String errorDetail = "Executing genrule //test:rule1 failed: (Exit 1)";
    if (keepGoing()) {
      assertThat(buildFailedException).hasMessageThat().isNull();
    } else {
      assertThat(buildFailedException).hasMessageThat().contains(errorDetail);
    }
    testCase.assertContainsError(errorDetail);
    assertOutputForStopBeforeRewoundReexecution();
    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1");
  }

  public final void runIntermediateActionRewound() throws Exception {
    // This test sets up three genrules, and makes the first execution of rule3 fail, saying that
    // the file produced by rule2 is missing but the file produced by rule1 is not.
    // Rule2 executes twice, consuming the file output from rule1 both times. Rule1 is not executed
    // a second time.
    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat $< && echo from rule1) > $@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "source_2.txt",
            ],
            outs = ["intermediate_2.txt"],
            cmd = "(cat $(SRCS) && echo from rule2) > $@",
        )

        genrule(
            name = "rule3",
            srcs = [
                "intermediate_1.txt",
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat $(SRCS) && echo from rule3) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);

    testCase.write("test/source_1.txt", "source_1");
    testCase.write("test/source_2.txt", "source_2");
    testCase.write("test/source_3.txt", "source_3");

    addSpawnShim(
        "Executing genrule //test:rule3",
        (spawn, context) -> {
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "intermediate_2.txt"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    String outputFileContents = buildAndGetOutput("test", testCase);

    assertThat(outputFileContents)
        .isEqualTo(
            "source_1\nfrom rule1\nsource_1\nfrom rule1\nsource_2\nfrom rule2\nsource_3\n"
                + "from rule3\n");

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3",
            "Executing genrule //test:consume_output")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of("Executing genrule //test:rule1"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:rule2"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule3"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule2");
  }

  public final void runChainOfActionsRewound() throws Exception {
    // This test exercises recursive rewinding. It sets up three genrules that depend on each other
    // in a chain. Rule1 and rule2 execute successfully their first time. When rule3 executes, it
    // fails, saying that the file produced by rule2 is missing. When rule2 is executed for the
    // second time, it fails, saying that the file produced by rule1 is missing. Thereafter, all
    // executions succeed.
    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.txt"],
            cmd = "(cat $< && echo from rule1) > $@",
        )

        genrule(
            name = "rule2",
            srcs = [
                "intermediate_1.txt",
                "source_2.txt",
            ],
            outs = ["intermediate_2.txt"],
            cmd = "(cat $(SRCS) && echo from rule2) > $@",
        )

        genrule(
            name = "rule3",
            srcs = [
                "intermediate_2.txt",
                "source_3.txt",
            ],
            outs = ["output.inlined"],
            cmd = "(cat $(SRCS) && echo from rule3) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);

    testCase.write("test/source_1.txt", "source_1");
    testCase.write("test/source_2.txt", "source_2");
    testCase.write("test/source_3.txt", "source_3");

    addSpawnShim(
        "Executing genrule //test:rule3",
        (spawn, context) -> {
          addSpawnShim(
              "Executing genrule //test:rule2",
              (otherSpawn, otherContext) -> {
                ImmutableList<ActionInput> lostInputs =
                    ImmutableList.of(
                        SpawnInputUtils.getInputWithName(otherSpawn, "intermediate_1.txt"));
                return createLostInputsExecException(
                    otherContext, lostInputs, new ActionInputDepOwnerMap(lostInputs));
              });

          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "intermediate_2.txt"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    String outputFileContents = buildAndGetOutput("test", testCase);

    assertThat(outputFileContents)
        .isEqualTo("source_1\nfrom rule1\nsource_2\nfrom rule2\nsource_3\nfrom rule3\n");

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3",
            "Executing genrule //test:consume_output")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of(
            "Executing genrule //test:rule1", "Executing genrule //test:rule2"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule3"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(2));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
        .containsExactly("//test:rule2", "//test:rule1");
  }

  public final void runNondeterministicActionRewound() throws Exception {
    // This test demonstrates that rewinding works when rewound actions are nondeterministic.
    //
    // A nondeterministic genrule, rule1, produces output which is lost. The genrule rule2 uses this
    // output and fails. The rewound nondeterministic action generates a new output. This test
    // asserts that rule2 uses the new output on its second try, by checking rule2's output when
    // it's used by rule3.

    testCase.write(
        "test/BUILD",
        """
        genrule(
            name = "rule1",
            srcs = ["source_1.txt"],
            outs = ["intermediate_1.inlined"],
            cmd = "(cat $(location source_1.txt) && echo $$RANDOM) > $@",
            tags = ["no-cache"],
        )

        genrule(
            name = "rule2",
            srcs = [
                "source_2.txt",
                "intermediate_1.inlined",
            ],
            outs = ["intermediate_2.inlined"],
            cmd = "(cat $(SRCS) && echo from rule2) > $@",
        )

        genrule(
            name = "rule3",
            srcs = ["intermediate_2.inlined"],
            outs = ["output.txt"],
            cmd = "(cat $< && echo from rule3) > $@",
        )
        """);
    testCase.write("test/source_1.txt", "source_1");
    testCase.write("test/source_2.txt", "source_2");

    AtomicReference<String> intermediate1FirstContent = new AtomicReference<>(null);
    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          ActionInput intermediate1 =
              SpawnInputUtils.getInputWithName(spawn, "intermediate_1.inlined");
          intermediate1FirstContent.set(latin1StringFromActionInput(context, intermediate1));
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(intermediate1);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    AtomicReference<String> intermediate1SecondContent = new AtomicReference<>(null);
    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          ActionInput intermediate1 =
              SpawnInputUtils.getInputWithName(spawn, "intermediate_1.inlined");
          intermediate1SecondContent.set(latin1StringFromActionInput(context, intermediate1));
          return ExecResult.delegate();
        });

    AtomicReference<String> intermediate2Content = new AtomicReference<>(null);
    addSpawnShim(
        "Executing genrule //test:rule3",
        (spawn, context) -> {
          ActionInput intermediate2 =
              SpawnInputUtils.getInputWithName(spawn, "intermediate_2.inlined");
          intermediate2Content.set(latin1StringFromActionInput(context, intermediate2));
          return ExecResult.delegate();
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//test:rule3");

    assertThat(intermediate1SecondContent.get()).isNotEqualTo(intermediate1FirstContent.get());
    assertThat(intermediate2Content.get())
        .isEqualTo(String.format("source_2\n%sfrom rule2\n", intermediate1SecondContent.get()));
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of("Executing genrule //test:rule3"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:rule1"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule2"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:rule1");
  }

  private void setUpParallelTrackSharedActionPackage() throws IOException {
    testCase.write(
        "shared/shared.bzl",
        "def _shared_impl(ctx):",
        "    in_file = ctx.file.src",
        "    a_shared_out = ctx.actions.declare_file('A-shared.out')",
        "    ctx.actions.run_shell(",
        "        inputs = [in_file],",
        "        outputs = [a_shared_out],",
        "        progress_message = 'Copying %s input %s to A-shared.out' % (ctx.attr.name,"
            + " in_file.short_path),",
        "        command = 'cp %s %s' % (in_file.path, a_shared_out.path),",
        "    )",
        "    b_shared_out = ctx.actions.declare_file('B-shared.out')",
        "    ctx.actions.run_shell(",
        "        inputs = [a_shared_out],",
        "        outputs = [b_shared_out],",
        "        progress_message = 'Copying A-shared.out to B-shared.out on behalf of %s' % "
            + "(ctx.attr.name),",
        "        command = 'cp %s %s' % (a_shared_out.path, b_shared_out.path),",
        "    )",
        "    out = ctx.outputs.out",
        "    ctx.actions.run_shell(",
        "        inputs = [b_shared_out],",
        "        outputs = [out],",
        "        progress_message = 'Copying B-shared.out to %s output %s' % (ctx.attr.name,"
            + " out.short_path),",
        "        command = 'cp %s %s' % (b_shared_out.path, out.path),",
        "    )",
        "    return [DefaultInfo(files = depset([out]))]",
        "",
        "shared = rule(",
        "    implementation = _shared_impl,",
        "    attrs = {",
        "        'src': attr.label(",
        "            mandatory = True,",
        "            allow_single_file = True,",
        "        ),",
        "        'out': attr.output(",
        "            mandatory = True",
        "        ),",
        "    }",
        ")");
    testCase.write(
        "shared/BUILD",
        """
        load("//shared:shared.bzl", "shared")

        genrule(
            name = "shared_input",
            srcs = [],
            outs = ["shared_input.txt"],
            cmd = 'echo "hi i am a shared input" > $@',
        )

        shared(
            name = "shared_1",
            src = "shared_input.txt",
            out = "shared_1.out",
        )

        shared(
            name = "shared_2",
            src = "shared_input.txt",
            out = "shared_2.out",
        )

        genrule(
            name = "merge_shared_rules",
            srcs = [
                "shared_1.out",
                "shared_2.out",
            ],
            outs = ["output.inlined"],
            cmd = "(cat $(location shared_1.out) && cat $(location shared_2.out)) > $@",
        )

        genrule(
            name = "consume_output",
            srcs = [":output.inlined"],
            outs = ["dummy.out"],
            cmd = "touch $@",
        )
        """);
  }

  private static boolean actionHasLabelAndIndex(
      ActionLookupData actionLookupData, String labelName, int index) {
    Label label = actionLookupData.getLabel();
    return label != null
        && label.getName().equals(labelName)
        && actionLookupData.getActionIndex() == index;
  }

  public final void runParallelTrackSharedActionsRewound() throws Exception {
    // This test demonstrates that, given a pair of parallel sequences of shared actions like so:
    //
    //   1B   2B  (higher actions depend on lower actions)
    //   |    |
    //   1A   2A
    //
    // in which 1A and 2A are shared, 1B and 2B are shared, and xB depends on an output of xA,
    // when 1B rewinds because the 1A output it depends on is lost, and 2B ran simultaneously with
    // the first, failed, evaluation of 1B and registers itself as depending on 1B's completion
    // future, then 2B gets reset when 1B clears its ActionExecutionState. Re-evaluations of dep
    // actions may proceed non-deterministically, but this test makes 2A win the "rewound A" race,
    // and then 1B win the "rewound B" race.
    ensureMultipleJobs();
    setUpParallelTrackSharedActionPackage();

    addSpawnShim(
        "Copying A-shared.out to B-shared.out on behalf of shared_1",
        (spawn, context) -> {
          ImmutableList<ActionInput> sharedInput =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "A-shared.out"));
          return createLostInputsExecException(
              context, sharedInput, new ActionInputDepOwnerMap(sharedInput));
        });
    addSpawnShim(
        "Copying A-shared.out to B-shared.out on behalf of shared_2",
        (spawn, context) -> {
          ImmutableList<ActionInput> sharedInput =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "A-shared.out"));
          return createLostInputsExecException(
              context, sharedInput, new ActionInputDepOwnerMap(sharedInput));
        });

    // This code controls the evaluation of the shared actions belonging to shared_1 and shared_2
    // so that the following events occur in the specified order. Each non-final step is associated
    // with a latch which prevents the subsequent step from happening before the preceding step
    // happens.
    //
    // 1. shared_1's B-shared.out generating action (hereafter referred to as "shared_1B", and
    //    likewise for other actions) emits an ActionStartedEvent, discovers its input is lost,
    //    emits an ActionRewoundEvent, but does not yet clear its ActionExecutionState from
    //    SkyframeActionExecutor.
    CountDownLatch shared1BEmittedRewoundEvent = new CountDownLatch(1);

    // 2. shared_2A coalesces with shared_1A's done ActionExecutionState. shared_2B coalesces with
    //    action_1B's not-done ActionExecutionState. It declares a Future dependency, and waits.
    CountDownLatch shared2BDeclaresFutureDep = new CountDownLatch(1);

    // 3. shared_1B clears its ActionExecutionState from SkyframeActionExecutor, triggering
    //    shared_2B's re-evaluation. shared_1B also clears shared_1A's ActionExecutionState.
    //    shared_2B does not find a matching ActionExecutionState, proceeds with its own evaluation,
    //    and discovers its input is lost also (which would be realistic, given that neither
    //    shared_1A nor shared_2A have re-evaluated).
    //
    //    shared_2B clears its ActionExecutionState, attempts to clear any ActionExecutionState
    //    associated with shared_2A (but there is none), requests shared_2A's re-evaluation,
    //    shared_2A re-evaluates, and shared_2B is ready to evaluate for its fifth(*) time.
    //
    // (*) Count:
    //    1. before shared_2A is first evaluated
    //    2. after shared_2A is first evaluated
    //    3. reset by shared_1B's state clearing
    //    4. reset by its own rewinding, before shared_2A is again evaluated
    //    5. after shared_2A is again evaluated
    CountDownLatch shared2BReadyForFifthTime = new CountDownLatch(1);

    // 4. shared_1A coalesces with the done ActionExecutionState from shared_2A's second evaluation,
    //    and shared_1B successfully re-evaluates.
    CountDownLatch shared1BDone = new CountDownLatch(1);

    // 5. shared_2B coalesces with shared_1B's done ActionExecutionState, and the build successfully
    //    completes.

    AtomicInteger shared1ARewound = new AtomicInteger(0);
    AtomicInteger shared2ARewound = new AtomicInteger(0);
    AtomicInteger shared2AReady = new AtomicInteger(0);
    AtomicInteger shared2BReady = new AtomicInteger(0);
    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          // Count the times shared_1{A,B} are rewound.
          if (type.equals(NotifyingHelper.EventType.MARK_DIRTY) && order.equals(Order.AFTER)) {
            NotifyingHelper.MarkDirtyAfterContext markDirtyAfterContext =
                (NotifyingHelper.MarkDirtyAfterContext) context;
            checkState(
                markDirtyAfterContext.dirtyType().equals(DirtyType.REWIND),
                "Unexpected DirtyType %s for key %s",
                context,
                key);
            checkState(key instanceof ActionLookupData, "rewound key not an action: %s", key);
            if (actionHasLabelAndIndex((ActionLookupData) key, "shared_1", 0)) {
              checkState(shared1ARewound.incrementAndGet() == 1, "shared_1A rewound twice");
            } else if (actionHasLabelAndIndex((ActionLookupData) key, "shared_2", 0)) {
              checkState(shared2ARewound.incrementAndGet() == 1, "shared_2A rewound twice");
            } else {
              throw new IllegalStateException(
                  String.format("rewound key has unexpected address: %s", key));
            }
          }

          if (type.equals(EventType.IS_READY)
              && key instanceof ActionLookupData actionLookupData
              && actionHasLabelAndIndex(actionLookupData, "shared_2", 0)) {
            int shared2AReadiedCount = shared2AReady.incrementAndGet();
            if (shared2AReadiedCount == 1) {
              awaitUninterruptibly(shared1BEmittedRewoundEvent);
            }
          }

          if (type.equals(EventType.IS_READY)
              && key instanceof ActionLookupData actionLookupData
              && actionHasLabelAndIndex(actionLookupData, "shared_2", 1)) {
            int shared2BReadiedCount = shared2BReady.incrementAndGet();
            if (shared2BReadiedCount == 5) {
              // Wait to attempt final evaluation of shared_2B until after shared_1B is done.
              shared2BReadyForFifthTime.countDown();
              awaitUninterruptibly(shared1BDone);
            }
          }

          // When shared_2B declares a future dep, allow shared_1B's Skyframe execution attempt to
          // clear its ActionExecutionState and reset its node.
          if (type.equals(EventType.ADD_EXTERNAL_DEP)
              && key instanceof ActionLookupData actionLookupData
              && actionHasLabelAndIndex(actionLookupData, "shared_2", 1)) {
            shared2BDeclaresFutureDep.countDown();
          }

          // Wait to attempt the rewound evaluation of shared_1A until after shared_2A finishes its
          // rewound evaluation and shared_2B is ready again.
          if (type.equals(EventType.IS_READY)
              && key instanceof ActionLookupData actionLookupData
              && actionHasLabelAndIndex(actionLookupData, "shared_1", 0)) {
            if (shared1ARewound.get() == 1) {
              awaitUninterruptibly(shared2BReadyForFifthTime);
            }
          }

          if (type.equals(EventType.SET_VALUE)
              && key instanceof ActionLookupData actionLookupData
              && actionHasLabelAndIndex(actionLookupData, "shared_1", 1)) {
            shared1BDone.countDown();
          }
        });

    recorder.setActionRewoundEventSubscriber(
        rewoundEvent -> {
          String progressMessage = rewoundEvent.getFailedRewoundAction().getProgressMessage();
          if (progressMessage.equals(
              "Copying A-shared.out to B-shared.out on behalf of shared_1")) {
            shared1BEmittedRewoundEvent.countDown();
            awaitUninterruptibly(shared2BDeclaresFutureDep);
          }
        });

    String output = buildAndGetOutput("shared", testCase);

    assertThat(output).isEqualTo("hi i am a shared input\nhi i am a shared input\n");

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //shared:shared_input",
            "Copying shared_1 input shared/shared_input.txt to A-shared.out",
            "Copying A-shared.out to B-shared.out on behalf of shared_1",
            "Copying A-shared.out to B-shared.out on behalf of shared_2",
            "Copying shared_2 input shared/shared_input.txt to A-shared.out",
            "Copying A-shared.out to B-shared.out on behalf of shared_1",
            "Copying B-shared.out to shared_1 output shared/shared_1.out",
            "Copying B-shared.out to shared_2 output shared/shared_2.out",
            "Executing genrule //shared:merge_shared_rules",
            "Executing genrule //shared:consume_output");
    assertThat(shared1ARewound.get()).isEqualTo(1);
    assertThat(shared2ARewound.get()).isEqualTo(1);
  }

  /**
   * This method defines a package with a Starlark rule "make_cc" and a cc_library rule
   * "consumes_tree" which depends on "make_cc". The Starlark rule generates a tree artifact. The
   * cc_library rule class knows how to consume tree artifacts: it uses a separate compilation
   * action for each file in the tree, and then one linking action for the tree.
   */
  private static void setUpTreeArtifactPackage(BuildIntegrationTestCase testCase) throws Exception {
    testCase.write(
        "tree/tree.bzl",
        """
        def _tree_impl(ctx):
            tree_artifact = ctx.actions.declare_directory(ctx.attr.name + "_dir.cc")
            ctx.actions.run_shell(
                inputs = ctx.files.srcs,
                outputs = [tree_artifact],
                command = "touch $1/file1.cc && touch $1/file2.cc",
                arguments = [tree_artifact.path],
            )
            return DefaultInfo(files = depset(direct = [tree_artifact]))

        tree = rule(
            implementation = _tree_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )
        """);

    testCase.write(
        "tree/BUILD",
        """
        load(":tree.bzl", "tree")

        tree(
            name = "make_cc",
            srcs = ["source_1.txt"],
        )

        cc_library(
            name = "consumes_tree",
            srcs = [
                "source_2.cc",
                ":make_cc",
            ],
        )
        """);

    testCase.write("tree/source_1.txt", "source_1");
    testCase.write("tree/source_2.cc", "#define FOO");
    // Don't want to have to track inclusion extraction for tree file artifacts.
    testCase.addOptions("--features=-cc_include_scanning");
  }

  public final void runTreeFileArtifactRewound_spawnFailed() throws Exception {
    runTreeFileArtifactRewound(
        (spawn, context) -> {
          ImmutableList<ActionInput> lostInputs = getTreeFileArtifactRewoundLostInputs(spawn);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });
  }

  static ImmutableList<ActionInput> getTreeFileArtifactRewoundLostInputs(Spawn spawn) {
    return ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "make_cc_dir.cc/file1.cc"));
  }

  final void runTreeFileArtifactRewound(SpawnShim shim) throws Exception {
    // This test demonstrates that rewinding works when an action fails due to a lost input which is
    // a generated TreeFileArtifact that is directly depended on. To emphasize: the failed action
    // directly depends on a file *contained in the tree*, and does *not* directly depend on the
    // tree itself.
    //
    // The compilation action "Compiling tree/make_cc_dir.cc/file1.cc" fails, saying that
    // "make_cc_dir.cc/file1.cc", one of the output files in the tree outputted by the "make_cc"
    // rule, is lost. The action that generated that tree, "Action tree/make_cc_dir.cc", is rewound
    // along with the failed compilation action.
    //
    // This test also confirms that rewinding is compatible with critical-path tracking when a
    // non-shared action (like this test's compiling actions) fails and is run a second time.

    setUpTreeArtifactPackage(testCase);

    addSpawnShim("Compiling tree/make_cc_dir.cc/file1.cc", shim);

    if (!supportsConcurrentRewinding()) {
      testCase.addOptions("--jobs=1");
    }

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//tree:consumes_tree");

    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Action tree/make_cc_dir.cc",
            "Compiling tree/make_cc_dir.cc/file1.cc",
            "Action tree/make_cc_dir.cc",
            "Compiling tree/make_cc_dir.cc/file1.cc",
            "Compiling tree/make_cc_dir.cc/file2.cc",
            "Compiling tree/source_2.cc",
            "Linking tree/libconsumes_tree.so",
            "Linking tree/libconsumes_tree.a");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Compiling tree/make_cc_dir.cc/file2.cc",
            "Linking tree/libconsumes_tree.so",
            "Linking tree/libconsumes_tree.a"),
        /* completedRewound= */ ImmutableList.of("Action tree/make_cc_dir.cc"),
        /* failedRewound= */ ImmutableList.of("Compiling tree/make_cc_dir.cc/file1.cc"),

        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertThat(rewoundKeys).hasSize(1);
    assertActionKey(rewoundKeys.get(0), "//tree:make_cc", /* index= */ 0);
  }

  public final void runTreeArtifactRewound_allFilesLost_spawnFailed() throws Exception {
    // This test demonstrates that rewinding works when an action fails due to a lost input which is
    // a generated TreeFileArtifact that is *indirectly* depended on. In contrast to what
    // testTreeFileArtifactRewound tests, in this test the failed action directly depends on the
    // tree, not the file contained in the tree.
    //
    // The linking action "Linking tree/libconsumes_tree.so" fails, saying that the "*.pic.o" files
    // produced by the compilation actions are lost. The linking action which failed is reset along
    // with those compilation actions.
    //
    // This test also confirms that rewinding is compatible with critical-path tracking when a
    // previously completed non-shared action (like this test's compiling actions) is rerun.

    ImmutableList<String> lostTreeFileArtifactNames =
        ImmutableList.of("make_cc_dir/file1.pic.o", "make_cc_dir/file2.pic.o");

    SpawnShim shim =
        getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(lostTreeFileArtifactNames);

    runTreeArtifactRewoundWhenTreeFilesLost(lostTreeFileArtifactNames, shim);
  }

  public final void runTreeArtifactRewound_oneFileLost_spawnFailed() throws Exception {
    // This test is like runTreeArtifactRewound_allFilesLost_spawnFailed, except it loses only one
    // of the files in the tree that "Linking tree/libconsumes_tree.so" depends on. By doing so it
    // exercises the case when only a subset of a tree's files are lost.
    //
    // The linking action which failed is reset, and *all* the compilation actions whose outputs
    // are included by the tree are rewound.
    //
    // It would be better if only the compilation action responsible for the lost file was rewound,
    // but rewinding is expected to be uncommon, so the overkill effort shouldn't be a problem in
    // practice.

    ImmutableList<String> lostTreeFileArtifactNames = ImmutableList.of("make_cc_dir/file1.pic.o");

    SpawnShim shim =
        getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(lostTreeFileArtifactNames);

    runTreeArtifactRewoundWhenTreeFilesLost(lostTreeFileArtifactNames, shim);
  }

  private SpawnShim getTreeArtifactRewoundWhenTreeFilesLostSpawnFailedShim(
      ImmutableList<String> lostTreeFileArtifactNames) {
    return (spawn, context) -> {
      Artifact treeArtifact = getTreeArtifactRewoundWhenTreeFilesLostTree(spawn);
      ImmutableList<ActionInput> lostTreeFileArtifacts =
          getTreeArtifactRewoundWhenTreeFilesLostInputs(
              lostTreeFileArtifactNames, spawn, context, treeArtifact);

      return createLostInputsExecException(
          context,
          lostTreeFileArtifacts,
          getInputOwners(
              lostTreeFileArtifacts.stream()
                  .collect(toImmutableSetMultimap(a -> a, a -> treeArtifact))));
    };
  }

  static Artifact getTreeArtifactRewoundWhenTreeFilesLostTree(Spawn spawn) {
    return SpawnInputUtils.getTreeArtifactWithName(spawn, "make_cc_dir");
  }

  static ImmutableList<ActionInput> getTreeArtifactRewoundWhenTreeFilesLostInputs(
      ImmutableList<String> lostTreeFileArtifactNames,
      Spawn spawn,
      ActionExecutionContext context,
      Artifact treeArtifact) {
    return lostTreeFileArtifactNames.stream()
        .map(n -> SpawnInputUtils.getExpandedToArtifact(n, treeArtifact, spawn, context))
        .collect(toImmutableList());
  }

  final void runTreeArtifactRewoundWhenTreeFilesLost(
      ImmutableList<String> lostTreeFileArtifactNames, SpawnShim shim) throws Exception {
    setUpTreeArtifactPackage(testCase);

    addSpawnShim("Linking tree/libconsumes_tree.so", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//tree:consumes_tree");
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Action tree/make_cc_dir.cc",
            "Compiling tree/make_cc_dir.cc/file1.cc",
            "Compiling tree/make_cc_dir.cc/file2.cc",
            "Compiling tree/source_2.cc",
            "Linking tree/libconsumes_tree.so",
            "Compiling tree/make_cc_dir.cc/file1.cc",
            "Compiling tree/make_cc_dir.cc/file2.cc",
            "Linking tree/libconsumes_tree.so",
            "Linking tree/libconsumes_tree.a");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Action tree/make_cc_dir.cc", "Linking tree/libconsumes_tree.a"),
        /* completedRewound= */ ImmutableList.of(
            "Compiling tree/make_cc_dir.cc/file1.cc", "Compiling tree/make_cc_dir.cc/file2.cc"),
        /* failedRewound= */ ImmutableList.of("Linking tree/libconsumes_tree.so"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(
            lostTreeFileArtifactNames.size()));

    assertThat(rewoundKeys).hasSize(3);
    HashSet<Integer> treeActionIndices = new HashSet<>(ImmutableList.of(0, 1));
    for (int i = 0; i < 2; i++) {
      assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
      assertThat(((ActionLookupData) rewoundKeys.get(i)).getLabel().getCanonicalForm())
          .isEqualTo("//tree:consumes_tree");
      assertThat(treeActionIndices.remove(((ActionLookupData) rewoundKeys.get(i)).getActionIndex()))
          .isTrue();
    }
    assertArtifactKey(rewoundKeys.get(2), "tree/_pic_objs/consumes_tree/make_cc_dir");
  }

  public final void runGeneratedRunfilesRewound_allFilesLost_spawnFailed() throws Exception {
    // This test demonstrates that rewinding works when an action fails due to lost inputs which are
    // generated files in the action's runfiles. Rewinding must propagate across the runfiles tree
    // artifacts and actions associated with the runfiles.

    ImmutableList<String> lostRunfiles = ImmutableList.of("gen1.dat", "gen2.dat");

    SpawnShim shim = getGeneratedRunfilesRewoundSpawnFailedShim(lostRunfiles);

    runGeneratedRunfilesRewound(lostRunfiles, shim);
  }

  public final void runGeneratedRunfilesRewound_oneFileLost_spawnFailed() throws Exception {
    // This test is like runGeneratedRunfilesRewound_allFilesLost_spawnFailed, except it loses only
    // one of the two generated runfiles that "Executing genrule //middle:tool_user" depends on.
    //
    // Like with runTreeArtifactRewound_oneFileLost_spawnFailed, it would be better if only the one
    // action responsible for the lost input was rewound, but rewinding is expected to be uncommon,
    // so the overkill effort isn't expected to be a problem in practice.

    ImmutableList<String> lostRunfiles = ImmutableList.of("gen1.dat");

    SpawnShim shim = getGeneratedRunfilesRewoundSpawnFailedShim(lostRunfiles);

    runGeneratedRunfilesRewound(lostRunfiles, shim);
  }

  private SpawnShim getGeneratedRunfilesRewoundSpawnFailedShim(ImmutableList<String> lostRunfiles) {
    return (spawn, context) -> {
      ImmutableList<ActionInput> lostRunfileArtifacts =
          getGeneratedRunfilesRewoundLostRunfiles(lostRunfiles, spawn, context);
      return createLostInputsExecException(
          context, lostRunfileArtifacts, new ActionInputDepOwnerMap(lostRunfileArtifacts));
    };
  }

  static ImmutableList<ActionInput> getGeneratedRunfilesRewoundLostRunfiles(
      ImmutableList<String> lostRunfiles, Spawn spawn, ActionExecutionContext context) {
    return lostRunfiles.stream()
        .map(n -> SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, n))
        .collect(toImmutableList());
  }

  protected void mockFooBinary(String relativePath) throws Exception {
    testCase.write(
        relativePath,
        """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_binary = rule(
          implementation = _impl,
          executable = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        """);
  }

  final void runGeneratedRunfilesRewound(ImmutableList<String> lostRunfiles, SpawnShim shim)
      throws Exception {
    mockFooBinary("middle/foo_binary.bzl");
    testCase.write(
        "middle/BUILD",
        """
        load(":foo_binary.bzl", "foo_binary")
        genrule(
            name = "gen1",
            srcs = [],
            outs = ["gen1.dat"],
            cmd = 'echo "made by gen1" > $@',
        )

        genrule(
            name = "gen2",
            srcs = [],
            outs = ["gen2.dat"],
            cmd = 'echo "made by gen2" > $@',
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = [
                "gen1.dat",
                "gen2.dat",
                "source_1.txt",
            ],
        )

        genrule(
            name = "tool_user",
            srcs = [],
            outs = ["tool_user.out"],
            cmd = "touch $(OUTS)",
            tools = ["tool"],
        )
        """);
    testCase.write("middle/tool.sh", "#!/bin/bash").setExecutable(true);
    testCase.write("middle/source_1.txt", "source_1");

    addSpawnShim("Executing genrule //middle:tool_user", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//middle:tool_user");
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //middle:gen1 [for tool]",
            "Executing genrule //middle:gen2 [for tool]",
            "Executing genrule //middle:tool_user",
            "Executing genrule //middle:gen1 [for tool]",
            "Executing genrule //middle:gen2 [for tool]",
            "Executing genrule //middle:tool_user");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of(
            "Executing genrule //middle:gen1 [for tool]",
            "Executing genrule //middle:gen2 [for tool]"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //middle:tool_user"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(lostRunfiles.size()));

    if (buildRunfileManifests()) {
      assertThat(rewoundKeys).hasSize(7);
      HashSet<String> expectedRewoundGenrules =
          new HashSet<>(ImmutableList.of("//middle:gen1", "//middle:gen2"));
      int i = 0;
      while (i < 5) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//middle:tool")) {
          switch (actionKey.getActionIndex()) {
            case 0: // SymlinkAction
              break;
            case 1: // SourceManifestAction
              assertActionKey(rewoundKeys.get(i), "//middle:tool", 2);
              i++;
              break;
            default:
              fail(String.format("Unexpected action index. actionKey: %s, i: %s", actionKey, i));
          }
        } else {
          assertThat(expectedRewoundGenrules.remove(actionLabel)).isTrue();
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//middle:tool", /* index= */ 3);
      assertArtifactKey(rewoundKeys.get(i), "middle/tool.runfiles");
    } else {
      assertThat(rewoundKeys).hasSize(5);
      HashSet<String> expectedRewoundGenrules =
          new HashSet<>(ImmutableList.of("//middle:gen1", "//middle:gen2"));
      int i = 0;
      while (i < 3) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//middle:tool")) {
          assertThat(actionKey.getActionIndex()).isEqualTo(0);
        } else {
          assertThat(expectedRewoundGenrules.remove(actionLabel)).isTrue();
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//middle:tool", /* index= */ 1);
      assertArtifactKey(rewoundKeys.get(i), "middle/tool.runfiles");
    }
  }

  public final void runDupeDirectAndRunfilesDependencyRewound_spawnFailed() throws Exception {
    AtomicReference<String> intermediate1FirstContent = new AtomicReference<>(null);
    SpawnShim shim =
        (spawn, context) -> {
          ActionInput lostInput =
              getDupeDirectAndRunfilesDependencyRewoundLostInput(spawn, context);
          intermediate1FirstContent.set(latin1StringFromActionInput(context, lostInput));
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(lostInput);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        };
    runDupeDirectAndRunfilesDependencyRewound(intermediate1FirstContent, shim);
  }

  static ActionInput getDupeDirectAndRunfilesDependencyRewoundLostInput(
      Spawn spawn, ActionExecutionContext context) {
    return SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "intermediate_1.inlined");
  }

  /**
   * Runs a test which demonstrates that rewinding works when a lost input is both directly depended
   * on and, via runfiles, indirectly depended on by an action. Rewinding must invalidate both paths
   * from the failed action to the rewound generating action.
   *
   * <p>This checks that the correct nodes were invalidated in the correct order. In particular, the
   * runfiles action and output artifact must have been invalidated after the artifact corresponding
   * to the lost input. Otherwise, their evaluation could race with the invalidation of the
   * generating action and its output artifact. If the runfiles nodes won, they could propagate
   * stale values for the lost input.
   */
  final void runDupeDirectAndRunfilesDependencyRewound(
      AtomicReference<String> intermediate1FirstContent, SpawnShim shim) throws Exception {
    mockFooBinary("test/foo_binary.bzl");
    testCase.write(
        "test/BUILD",
        """
        load(":foo_binary.bzl", "foo_binary")
        genrule(
            name = "rule1",
            srcs = [],
            outs = ["intermediate_1.inlined"],
            cmd = "echo $$RANDOM > $@",
            tags = ["no-cache"],
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = ["intermediate_1.inlined"],
        )

        genrule(
            name = "rule2",
            srcs = [],
            outs = ["intermediate_2.inlined"],
            cmd = "($(location tool) && cat $(location intermediate_1.inlined) && " +
                  "echo from rule2) > $@",
            tools = [
                "intermediate_1.inlined",
                "tool",
            ],
        )

        genrule(
            name = "rule3",
            srcs = ["intermediate_2.inlined"],
            outs = ["output.txt"],
            cmd = "(cat $< && echo from rule3) > $@",
        )
        """);
    testCase
        .write(
            "test/tool.sh",
            "#!/bin/bash",
            String.format(
                "cat ${0}.runfiles/%s/test/intermediate_1.inlined", TestConstants.WORKSPACE_NAME),
            "echo 'from tool'")
        .setExecutable(true);

    addSpawnShim("Executing genrule //test:rule2", shim);

    AtomicReference<String> intermediate1SecondContent = new AtomicReference<>(null);
    addSpawnShim(
        "Executing genrule //test:rule2",
        (spawn, context) -> {
          Artifact intermediate1 =
              SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "intermediate_1.inlined");
          intermediate1SecondContent.set(latin1StringFromActionInput(context, intermediate1));
          return ExecResult.delegate();
        });

    AtomicReference<String> intermediate2Content = new AtomicReference<>(null);
    addSpawnShim(
        "Executing genrule //test:rule3",
        (spawn, context) -> {
          ActionInput intermediate2 =
              SpawnInputUtils.getInputWithName(spawn, "intermediate_2.inlined");
          intermediate2Content.set(latin1StringFromActionInput(context, intermediate2));
          return ExecResult.delegate();
        });

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//test:rule3");

    assertThat(intermediate1SecondContent.get()).isNotEqualTo(intermediate1FirstContent.get());
    assertThat(intermediate2Content.get())
        .isEqualTo(
            String.format(
                "%sfrom tool\n%sfrom rule2\n",
                intermediate1SecondContent.get(), intermediate1SecondContent.get()));
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:rule1 [for tool]",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule1 [for tool]",
            "Executing genrule //test:rule2",
            "Executing genrule //test:rule3")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of("Executing genrule //test:rule3"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:rule1 [for tool]"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //test:rule2"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    if (buildRunfileManifests()) {
      assertThat(rewoundKeys).hasSize(6);
      int i = 0;
      while (i < 4) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//test:tool")) {
          switch (actionKey.getActionIndex()) {
            case 0: // SymlinkAction
              break;
            case 1: // SourceManifestAction
              assertActionKey(rewoundKeys.get(i), "//test:tool", /* index= */ 2);
              i++;
              break;
            default:
              fail(
                  String.format(
                      "Unexpected action index. actionKey: %s, rewoundKeys: %s",
                      actionKey, rewoundKeys));
          }
        } else {
          assertThat(actionLabel).isEqualTo("//test:rule1");
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//test:tool", /* index= */ 3);
      assertArtifactKey(rewoundKeys.get(i), "test/tool.runfiles");
    } else {
      assertThat(rewoundKeys).hasSize(4);
      int i = 0;
      while (i < 2) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//test:tool")) {
          assertThat(actionKey.getActionIndex()).isEqualTo(0);
        } else {
          assertThat(actionLabel).isEqualTo("//test:rule1");
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//test:tool", /* index= */ 1);
      assertArtifactKey(rewoundKeys.get(i), "test/tool.runfiles");
    }
  }

  public final void runTreeInRunfilesRewound_spawnFailed() throws Exception {
    SpawnShim shim =
        (spawn, context) -> {
          Artifact treeArtifact = getTreeInRunfilesRewoundTree(spawn, context);
          ImmutableList<ActionInput> lostInputs =
              getTreeInRunfilesRewoundLostInputs(spawn, context, treeArtifact);
          return createLostInputsExecException(
              context,
              lostInputs,
              getInputOwners(
                  ImmutableSetMultimap.of(
                      lostInputs.get(0), treeArtifact, lostInputs.get(1), treeArtifact)));
        };

    runTreeInRunfilesRewound(shim);
  }

  static Artifact getTreeInRunfilesRewoundTree(Spawn spawn, ActionExecutionContext context) {
    return SpawnInputUtils.getRunfilesArtifactWithName(spawn, context, "gen_tree");
  }

  static ImmutableList<ActionInput> getTreeInRunfilesRewoundLostInputs(
      Spawn spawn, ActionExecutionContext context, Artifact treeArtifact) {
    return ImmutableList.of(
        SpawnInputUtils.getExpandedToArtifact("gen1.out", treeArtifact, spawn, context),
        SpawnInputUtils.getExpandedToArtifact("gen2.out", treeArtifact, spawn, context));
  }

  final void runTreeInRunfilesRewound(SpawnShim shim) throws Exception {
    testCase.write(
        "middle/tree.bzl",
        """
        def _tree_impl(ctx):
            tree_artifact = ctx.actions.declare_directory(ctx.attr.name + "_dir")
            ctx.actions.run_shell(
                inputs = ctx.files.srcs,
                outputs = [tree_artifact],
                command = '(echo "tree1" > $1/gen1.out) && (echo "tree2" > $1/gen2.out)',
                arguments = [tree_artifact.path],
            )
            return DefaultInfo(
                files = depset(direct = [tree_artifact]),
                runfiles = ctx.runfiles(files = [tree_artifact]),
            )

        tree = rule(
            implementation = _tree_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )
        """);
    mockFooBinary("middle/foo_binary.bzl");
    testCase.write(
        "middle/BUILD",
        """
        load(":tree.bzl", "tree")
        load(":foo_binary.bzl", "foo_binary")

        tree(
            name = "gen_tree",
            srcs = ["source_1.txt"],
        )

        foo_binary(
            name = "tool",
            srcs = ["tool.sh"],
            data = [
                "source_2.txt",
                ":gen_tree",
            ],
        )

        genrule(
            name = "tool_user",
            srcs = [],
            outs = ["tool_user.out"],
            cmd = "touch $(OUTS)",
            tools = ["tool"],
        )
        """);
    testCase.write("middle/tool.sh", "#!/bin/bash").setExecutable(true);
    testCase.write("middle/source_1.txt", "source_1");
    testCase.write("middle/source_2.txt", "source_2");

    addSpawnShim("Executing genrule //middle:tool_user", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//middle:tool_user");
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Action middle/gen_tree_dir [for tool]",
            "Executing genrule //middle:tool_user",
            "Action middle/gen_tree_dir [for tool]",
            "Executing genrule //middle:tool_user")
        .inOrder();

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of("Action middle/gen_tree_dir [for tool]"),
        /* failedRewound= */ ImmutableList.of("Executing genrule //middle:tool_user"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(2));

    if (buildRunfileManifests()) {
      assertThat(rewoundKeys).hasSize(7);
      int i = 0;
      while (i < 5) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//middle:tool")) {
          switch (actionKey.getActionIndex()) {
            case 0: // SymlinkAction
              break;
            case 1: // SourceManifestAction
              assertActionKey(rewoundKeys.get(i), "//middle:tool", 2);
              i++;
              break;
            default:
              fail(String.format("Unexpected action index. actionKey: %s", actionKey));
          }
        } else {
          assertThat(actionLabel).isEqualTo("//middle:gen_tree");
          assertArtifactKey(rewoundKeys.get(i), "middle/gen_tree_dir");
          i++;
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//middle:tool", /* index= */ 3);
      assertArtifactKey(rewoundKeys.get(i), "middle/tool.runfiles");
    } else {
      assertThat(rewoundKeys).hasSize(5);
      int i = 0;
      while (i < 3) {
        assertThat(rewoundKeys.get(i)).isInstanceOf(ActionLookupData.class);
        ActionLookupData actionKey = (ActionLookupData) rewoundKeys.get(i);
        String actionLabel = actionKey.getLabel().getCanonicalForm();
        i++;
        if (actionLabel.equals("//middle:tool")) {
          assertThat(actionKey.getActionIndex()).isEqualTo(0);
        } else {
          assertThat(actionLabel).isEqualTo("//middle:gen_tree");
          assertArtifactKey(rewoundKeys.get(i), "middle/gen_tree_dir");
          i++;
        }
      }

      assertActionKey(rewoundKeys.get(i++), "//middle:tool", /* index= */ 1);
      assertArtifactKey(rewoundKeys.get(i), "middle/tool.runfiles");
    }
  }

  /**
   * Regression test for b/181884247.
   *
   * <p>The action for {@code //test:consumer} has three inputs all generated by {@code //test:gen}.
   * However, the action's {@code depset} of inputs is arranged such that the three artifacts are
   * split among its children. This tests that rewinding properly handles the case of a requested
   * {@link ArtifactNestedSetKey} containing only some of the inputs for a particular generating
   * action.
   */
  public final void runInputsFromSameGeneratingActionSplitAmongNestedSetChildren()
      throws Exception {
    testCase.write(
        "test/defs.bzl",
        """
        def _consumer_impl(ctx):
            in1, in2, in3 = ctx.attr.three_output_genrule.files.to_list()
            out = ctx.actions.declare_file("consumer.out")
            ctx.actions.run_shell(
                outputs = [out],
                # Arrange the inputs such that they are split among the depset's children.
                inputs = depset([in1], transitive = [depset([in2, in3])]),
                command = "touch %s" % out.path,
                progress_message = "Running consumer",
            )
            return DefaultInfo(files = depset([out]))

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {"three_output_genrule": attr.label(mandatory = True)},
        )
        """);
    testCase.write(
        "test/BUILD",
        """
        load(":defs.bzl", "consumer")

        genrule(
            name = "gen",
            outs = [
                "gen.out1",
                "gen.out2",
                "gen.out3",
            ],
            cmd = "touch $(OUTS)",
        )

        consumer(
            name = "consumer",
            three_output_genrule = ":gen",
        )
        """);

    addSpawnShim(
        "Running consumer",
        (spawn, context) -> {
          ActionInput genOut1 = SpawnInputUtils.getInputWithName(spawn, "gen.out1");
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(genOut1);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();

    testCase.buildTarget("//test:consumer");

    assertThat(rewoundKeys).hasSize(1);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//test:gen");
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //test:gen",
            "Running consumer",
            "Executing genrule //test:gen",
            "Running consumer")
        .inOrder();
    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(),
        /* completedRewound= */ ImmutableList.of("Executing genrule //test:gen"),
        /* failedRewound= */ ImmutableList.of("Running consumer"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));
  }

  private static void writeGeneratedHeaderDirectDepPackage(BuildIntegrationTestCase testCase)
      throws IOException {
    testCase.write(
        "genheader/BUILD",
        """
        genrule(
            name = "gen_header",
            srcs = [],
            outs = ["gen.h"],
            cmd = "touch $@",
        )

        cc_binary(
            name = "consumes_header",
            srcs = [
                "consumes.cc",
                "gen.h",
            ],
        )
        """);
    testCase.write(
        "genheader/consumes.cc",
        "#include \"genheader/gen.h\"",
        "int main() {",
        "  return 0;",
        "}");
  }

  public final void runGeneratedHeaderRewound_lostInInputDiscovery_spawnFailed() throws Exception {
    SpawnShim shim =
        (spawn, context) -> {
          ActionInput header = getGeneratedHeaderRewoundLostInput(spawn);
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(header);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        };

    runGeneratedHeaderRewound_lostInInputDiscovery(shim);
  }

  static ActionInput getGeneratedHeaderRewoundLostInput(Spawn spawn) {
    return SpawnInputUtils.getInputWithName(spawn, "genheader/gen.h");
  }

  final void runGeneratedHeaderRewound_lostInInputDiscovery(SpawnShim shim) throws Exception {
    // This test checks that rewinding works when the lost input is a generated header, and the loss
    // is found by remote include scanning, which happens in input discovery.
    writeGeneratedHeaderDirectDepPackage(testCase);

    addSpawnShim(
        String.format(
            "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
            TestConstants.PRODUCT_NAME),
        shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//genheader:consumes_header");
    verifyAllSpawnShimsConsumed();

    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //genheader:gen_header",
            "Extracting include lines from genheader/consumes.cc",
            "Extracting include lines from tools/cpp/malloc.cc",
            "Compiling tools/cpp/malloc.cc",
            "Extracting include lines from tools/cpp/linkextra.cc",
            "Compiling tools/cpp/linkextra.cc",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Executing genrule //genheader:gen_header",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Compiling genheader/consumes.cc",
            "Linking genheader/consumes_header");

    // Input discovery actions do not result in action lifecycle events. E.g., the "Extracting
    // [...]" action is run, but results in no ActionStartedEvent/ActionCompletionEvent/etc.
    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Linking genheader/consumes_header", "Compiling genheader/consumes.cc"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //genheader:gen_header"),
        /* failedRewound= */ ImmutableList.of(),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header");
  }

  public final void runGeneratedHeaderRewound_lostInActionExecution_spawnFailed() throws Exception {
    SpawnShim shim =
        (spawn, context) -> {
          ActionInput header = getGeneratedHeaderRewoundLostInput(spawn);
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(header);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        };

    runGeneratedHeaderRewound_lostInActionExecution(shim);
  }

  final void runGeneratedHeaderRewound_lostInActionExecution(SpawnShim shim) throws Exception {
    // This test checks that rewinding works when the lost input is a generated header, and the loss
    // is found during action execution (after input discovery)
    //
    // This test also confirms that rewinding is compatible with critical-path tracking when a
    // non-shared action (like this test's compiling action) fails and is run a second time.
    writeGeneratedHeaderDirectDepPackage(testCase);

    addSpawnShim("Compiling genheader/consumes.cc", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//genheader:consumes_header");
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //genheader:gen_header",
            "Extracting include lines from genheader/consumes.cc",
            "Extracting include lines from tools/cpp/malloc.cc",
            "Compiling tools/cpp/malloc.cc",
            "Extracting include lines from tools/cpp/linkextra.cc",
            "Compiling tools/cpp/linkextra.cc",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Compiling genheader/consumes.cc",
            "Executing genrule //genheader:gen_header",
            "Compiling genheader/consumes.cc",
            "Linking genheader/consumes_header");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of("Linking genheader/consumes_header"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //genheader:gen_header"),
        /* failedRewound= */ ImmutableList.of("Compiling genheader/consumes.cc"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header");
  }

  private static void writeGeneratedHeaderIndirectDepPackage(BuildIntegrationTestCase testCase)
      throws IOException {
    testCase.write(
        "genheader/BUILD",
        """
        genrule(
            name = "gen_header",
            srcs = [],
            outs = ["gen.h"],
            cmd = 'echo "int f(int x);" > $@',
        )

        cc_library(
            name = "intermediate",
            srcs = ["intermediate.cc"],
            hdrs = ["gen.h"],
        )

        cc_binary(
            name = "consumes_header",
            srcs = ["consumes.cc"],
            deps = ["intermediate"],
        )
        """);
    testCase.write("genheader/intermediate.cc", "int f(int x) { return x + 1; }");
    testCase.write(
        "genheader/consumes.cc",
        "#include \"genheader/gen.h\"",
        "int main() {",
        "  return f(1);",
        "}");
  }

  public final void runGeneratedTransitiveHeaderRewound_lostInInputDiscovery_spawnFailed()
      throws Exception {
    SpawnShim shim =
        (discoverySpawn, discoveryContext) -> {
          ActionInput header = getGeneratedHeaderRewoundLostInput(discoverySpawn);
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(header);
          return createLostInputsExecException(
              discoveryContext, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        };

    runGeneratedTransitiveHeaderRewound_lostInInputDiscovery(shim);
  }

  final void runGeneratedTransitiveHeaderRewound_lostInInputDiscovery(SpawnShim shim)
      throws Exception {
    // Like runGeneratedHeaderRewound_lostInInputDiscovery, this test checks that rewinding works
    // when the lost input is a generated header, except in this test, the header is indirectly
    // depended on.
    //
    // Note that only the target-graph dependency is indirect (i.e. the dependency between
    // ":consumes_header" and ":gen.h"). The Skyframe node corresponding to the compiling action of
    // ":consumes_header" directly depends on the "gen.h" artifact, though that dependency is
    // discovered during execution.
    writeGeneratedHeaderIndirectDepPackage(testCase);

    addSpawnShim(
        String.format(
            "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
            TestConstants.PRODUCT_NAME),
        shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//genheader:consumes_header");
    verifyAllSpawnShimsConsumed();

    // Note that because intermediate.cc does not have an include directive for gen.h, the
    // "Extracting [...]/gen.h" action is first attempted just prior to the first attempt of
    // "Compiling genheader/consumes.cc".
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //genheader:gen_header",
            "Extracting include lines from genheader/intermediate.cc",
            "Extracting include lines from genheader/consumes.cc",
            "Extracting include lines from tools/cpp/malloc.cc",
            "Compiling tools/cpp/malloc.cc",
            "Extracting include lines from tools/cpp/linkextra.cc",
            "Compiling tools/cpp/linkextra.cc",
            "Compiling genheader/intermediate.cc",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Executing genrule //genheader:gen_header",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Compiling genheader/consumes.cc",
            "Linking genheader/consumes_header");

    // Input discovery actions do not result in action lifecycle events. E.g., the "Extracting
    // [...]" action is run, but results in no ActionStartedEvent/ActionCompletionEvent/etc.
    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Compiling genheader/intermediate.cc",
            "Compiling genheader/consumes.cc",
            "Linking genheader/consumes_header"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //genheader:gen_header"),
        /* failedRewound= */ ImmutableList.of(),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header");
  }

  public final void runGeneratedTransitiveHeaderRewound_lostInActionExecution_spawnFailed()
      throws Exception {
    SpawnShim shim =
        (spawn, context) -> {
          ActionInput header = getGeneratedHeaderRewoundLostInput(spawn);
          ImmutableList<ActionInput> lostInputs = ImmutableList.of(header);
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        };

    runGeneratedTransitiveHeaderRewound_lostInActionExecution(shim);
  }

  final void runGeneratedTransitiveHeaderRewound_lostInActionExecution(SpawnShim shim)
      throws Exception {
    // Like runGeneratedHeaderRewound_lostInActionExecution, this test checks that rewinding works
    // when the lost input is a generated header, except in this test, the header is indirectly
    // depended on.
    //
    // Note that only the target-graph dependency is indirect (i.e. the dependency between
    // ":consumes_header" and ":gen.h"). The Skyframe node corresponding to the compiling action of
    // ":consumes_header" directly depends on the "gen.h" artifact, though that dependency is
    // discovered during execution.
    writeGeneratedHeaderIndirectDepPackage(testCase);

    addSpawnShim("Compiling genheader/consumes.cc", shim);

    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    testCase.buildTarget("//genheader:consumes_header");
    verifyAllSpawnShimsConsumed();
    assertThat(getExecutedSpawnDescriptions())
        .containsExactly(
            "Executing genrule //genheader:gen_header",
            "Extracting include lines from genheader/intermediate.cc",
            "Extracting include lines from tools/cpp/malloc.cc",
            "Compiling tools/cpp/malloc.cc",
            "Extracting include lines from tools/cpp/linkextra.cc",
            "Compiling tools/cpp/linkextra.cc",
            "Extracting include lines from genheader/consumes.cc",
            "Compiling genheader/intermediate.cc",
            String.format(
                "Extracting include lines from %s-out/k8-fastbuild/bin/genheader/gen.h",
                TestConstants.PRODUCT_NAME),
            "Compiling genheader/consumes.cc",
            "Executing genrule //genheader:gen_header",
            "Compiling genheader/consumes.cc",
            "Linking genheader/consumes_header");

    recorder.assertEvents(
        /* runOnce= */ ImmutableList.of(
            "Linking genheader/consumes_header", "Compiling genheader/intermediate.cc"),
        /* completedRewound= */ ImmutableList.of("Executing genrule //genheader:gen_header"),
        /* failedRewound= */ ImmutableList.of("Compiling genheader/consumes.cc"),
        /* actionRewindingPostLostInputCounts= */ ImmutableList.of(1));

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//genheader:gen_header");
  }

  /**
   * Regression test for b/242179728.
   *
   * <p>Exercises a scenario where a failing action depends on another action which is rewound
   * between the time that the action fails and the dep is looked up for signaling. The order of
   * events in this scenario (synchronized so that they execute sequentially) is:
   *
   * <ol>
   *   <li>{@code //foo:dep} executes successfully and produces two outputs, {@code dep.out1} and
   *       {@code dep.out2}.
   *   <li>{@code //foo:fail}, which depends on {@code dep.out1}, executes and fails due to a
   *       regular action execution failure (not a lost input).
   *   <li>{@code //foo:other}, which depends on {@code dep.out2}, executes and observes a lost
   *       input. {@code //foo:dep} is rewound.
   *   <li>{@code //foo:fail} looks up {@code //foo:dep} for {@link Reason#RDEP_ADDITION} and
   *       observes it to be dirty.
   * </ol>
   */
  public final void runDoneToDirtyDepForNodeInError() throws Exception {
    ensureMultipleJobs();
    testCase.write(
        "foo/BUILD",
        """
        genrule(
            name = "other",
            srcs = [":dep.out2"],
            outs = ["other.out"],
            cmd = "cp $< $@",
        )

        genrule(
            name = "fail",
            srcs = [":dep.out1"],
            outs = ["fail.out"],
            cmd = "false",
        )

        genrule(
            name = "dep",
            outs = [
                "dep.out1",
                "dep.out2",
            ],
            cmd = "touch $(OUTS)",
        )
        """);
    CountDownLatch depDone = new CountDownLatch(1);
    CountDownLatch failExecuting = new CountDownLatch(1);
    CountDownLatch depRewound = new CountDownLatch(1);
    Label fail = Label.parseCanonicalUnchecked("//foo:fail");
    Label dep = Label.parseCanonicalUnchecked("//foo:dep");
    addSpawnShim(
        "Executing genrule //foo:fail",
        (spawn, context) -> {
          failExecuting.countDown();
          return ExecResult.delegate();
        });
    addSpawnShim(
        "Executing genrule //foo:other",
        (spawn, context) -> {
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "dep.out2"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });
    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (isActionExecutionKey(key, fail) && type == EventType.CREATE_IF_ABSENT) {
            awaitUninterruptibly(depDone);
          } else if (isActionExecutionKey(key, dep)
              && type == EventType.SET_VALUE
              && order == Order.AFTER) {
            depDone.countDown();
          } else if (isActionExecutionKey(key, dep)
              && type == EventType.ADD_REVERSE_DEP
              && order == Order.BEFORE
              && isActionExecutionKey(context, fail)) {
            awaitUninterruptibly(depRewound);
          } else if (isActionExecutionKey(key, dep)
              && type == EventType.MARK_DIRTY
              && order == Order.AFTER) {
            depRewound.countDown();
          }
        });

    assertThrows(BuildFailedException.class, () -> testCase.buildTarget("//foo:all"));
    testCase.assertContainsError("Executing genrule //foo:fail failed");
  }

  private void runFlakyActionFailsAfterRewind_raceWithIndirectConsumer() throws Exception {
    ensureMultipleJobs();
    testCase.write(
        "foo/defs.bzl",
        """
        def _action_with_indirect_input(ctx):
            other1 = ctx.actions.declare_file("other1")
            ctx.actions.write(other1, "")
            other2 = ctx.actions.declare_file("other2")
            ctx.actions.write(other2, "")

            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            indirect_input = ctx.file.indirect_input
            ctx.actions.run_shell(
                inputs = depset([other1], transitive = [depset([other2, indirect_input])]),
                outputs = [out],
                command = "cat $1 $2 $3 > $4",
                arguments = [other1.path, other2.path, indirect_input.path, out.path],
            )
            return DefaultInfo(files = depset([out]))

        action_with_indirect_input = rule(
            implementation = _action_with_indirect_input,
            attrs = {"indirect_input": attr.label(allow_single_file = True)},
        )
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "action_with_indirect_input")

        action_with_indirect_input(
            name = "top2",
            indirect_input = ":flaky_lost",
        )

        genrule(
            name = "top1",
            srcs = [":flaky_lost"],
            outs = ["top1.out"],
            cmd = "cp $< $@",
        )

        genrule(
            name = "flaky_lost",
            outs = ["flaky_lost.out"],
            cmd = "touch $@",
        )
        """);
    Label top2 = Label.parseCanonical("//foo:top2");
    Label top1 = Label.parseCanonical("//foo:top1");
    Label flakyLost = Label.parseCanonical("//foo:flaky_lost");

    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();

    assertThrows(
        BuildFailedException.class, () -> testCase.buildTarget("//foo:top1", "//foo:top2"));
    verifyAllSpawnShimsConsumed();
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:flaky_lost");

    // Check that TargetCompleteEvents were posted with the correct root cause.
    if (keepGoing()) {
      assertThat(targetCompleteEvents.keySet()).containsExactly(top1, top2);
    } else {
      assertThat(targetCompleteEvents).hasSize(1);
      assertThat(targetCompleteEvents.keySet()).containsAnyOf(top1, top2);
    }
    targetCompleteEvents.forEach(
        (target, event) ->
            assertWithMessage("%s", target)
                .that(event.getRootCauses().getSingleton().getLabel())
                .isEqualTo(flakyLost));

    // Trying again irons out the flaky failure with no rewinding.
    rewoundKeys.clear();
    targetCompleteEvents.clear();
    testCase.buildTarget("//foo:top1", "//foo:top2");
    assertThat(rewoundKeys).isEmpty();
  }

  /**
   * Tests handling of an action that is rewound and completes with an error in between the time
   * that a second action declares a dependency on it and consumes it during input checking, where
   * the second action depends on the lost input indirectly (via an {@link ArtifactNestedSetKey}).
   *
   * <p>Targets in this test:
   *
   * <ul>
   *   <li>{@code :flaky_lost}: initially executes successfully, but then gets rewound and completes
   *       with an error.
   *   <li>{@code :top1}: initiates rewinding on {@code :flaky_lost}.
   *   <li>{@code :top2}: depends indirectly on {@code :flaky_lost} and observes it as an undone
   *       input.
   * </ul>
   *
   * <p>Order of events in this test:
   *
   * <ol>
   *   <li>{@code :top2} requests its inputs from Skyframe, including an {@link
   *       ArtifactNestedSetKey} containing {@code flaky_lost.out}. It is not done, so {@code :top2}
   *       needs a Skyframe restart.
   *   <li>The {@link ArtifactNestedSetKey} containing {@code flaky_lost.out} completes
   *       successfully.
   *   <li>{@code :top2} resumes after the Skyframe restart.
   *   <li>{@code :top1} observes {@code flaky_lost.out} to be a lost input and rewinds {@code
   *       :flaky_lost}.
   *   <li>{@code :flaky_lost} executes a second time, and this time the action fails.
   *   <li>{@code :top2} has no missing direct deps, but cannot look up {@code flaky_lost.out}
   *       because its generating action failed. In order to propagate a valid root cause, it
   *       initiates rewinding of the {@link ArtifactNestedSetKey}.
   * </ol>
   */
  public final void
      runFlakyActionFailsAfterRewind_raceWithIndirectConsumer_undoneDuringInputChecking()
          throws Exception {
    CountDownLatch top2RestartedWithDoneNestedSet = new CountDownLatch(1);
    CountDownLatch errorSet = new CountDownLatch(1);
    addSpawnShim(
        "Executing genrule //foo:top1",
        (spawn, context) -> {
          top2RestartedWithDoneNestedSet.await();
          addSpawnShim(
              "Executing genrule //foo:flaky_lost",
              (spawn2, context2) ->
                  ExecResult.ofException(
                      new SpawnExecException(
                          "Flaky action failure",
                          FAILED_RESULT,
                          /* forciblyRunRemotely= */ false,
                          /* catastrophe= */ false)));
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "flaky_lost.out"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (key instanceof ArtifactNestedSetKey
              && type == EventType.GET_BATCH
              && order == Order.BEFORE
              && context == Reason.PREFETCH) {
            top2RestartedWithDoneNestedSet.countDown();
            awaitUninterruptibly(errorSet);
          } else if (isActionExecutionKey(key, Label.parseCanonicalUnchecked("//foo:flaky_lost"))
              && type == EventType.SET_VALUE
              && order == Order.AFTER
              && ValueWithMetadata.getMaybeErrorInfo((SkyValue) context) != null) {
            errorSet.countDown();
          }
        });

    runFlakyActionFailsAfterRewind_raceWithIndirectConsumer();
  }

  /**
   * Tests handling of an action that is rewound and completes with an error in between the time
   * that a second action observes it to be lost and attempts to look it up during lost input
   * handling, where the second action depends on the lost input indirectly (via an {@link
   * ArtifactNestedSetKey}).
   *
   * <p>Targets in this test:
   *
   * <ul>
   *   <li>{@code :flaky_lost}: initially executes successfully, but then gets rewound and completes
   *       with an error.
   *   <li>{@code :top1}: initiates rewinding on {@code :flaky_lost}.
   *   <li>{@code :top2}: depends indirectly on {@code :flaky_lost} and observes it as an undone
   *       input.
   * </ul>
   *
   * <p>Order of events in this test:
   *
   * <ol>
   *   <li>{@code :top2} requests its inputs from Skyframe, including an {@link
   *       ArtifactNestedSetKey} containing {@code flaky_lost.out}. All are done, so it begins to
   *       execute, and observes {@code flaky_lost.out} to be lost.
   *   <li>{@code :top1} observes {@code flaky_lost.out} to be a lost input and rewinds {@code
   *       :flaky_lost}.
   *   <li>{@code :flaky_lost} executes a second time, and this time the action fails.
   *   <li>{@code :top2} attempts to handle lost inputs by initiating rewinding, but this requires
   *       looking up {@code flaky_lost.out}, which is undone.
   * </ol>
   */
  public final void
      runFlakyActionFailsAfterRewind_raceWithIndirectConsumer_undoneDuringLostInputHandling()
          throws Exception {
    CountDownLatch top2Executing = new CountDownLatch(1);
    CountDownLatch errorSet = new CountDownLatch(1);
    addSpawnShim(
        "Executing genrule //foo:top1",
        (spawn, context) -> {
          top2Executing.await();
          addSpawnShim(
              "Executing genrule //foo:flaky_lost",
              (spawn2, context2) ->
                  ExecResult.ofException(
                      new SpawnExecException(
                          "Flaky action failure",
                          FAILED_RESULT,
                          /* forciblyRunRemotely= */ false,
                          /* catastrophe= */ false)));
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "flaky_lost.out"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });
    addSpawnShim(
        "Action foo/top2.out",
        (spawn, context) -> {
          top2Executing.countDown();
          awaitUninterruptibly(errorSet);
          ImmutableList<ActionInput> lostInputs =
              ImmutableList.of(SpawnInputUtils.getInputWithName(spawn, "flaky_lost.out"));
          return createLostInputsExecException(
              context, lostInputs, new ActionInputDepOwnerMap(lostInputs));
        });

    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (isActionExecutionKey(key, Label.parseCanonicalUnchecked("//foo:flaky_lost"))
              && type == EventType.SET_VALUE
              && order == Order.AFTER
              && ValueWithMetadata.getMaybeErrorInfo((SkyValue) context) != null) {
            errorSet.countDown();
          }
        });

    runFlakyActionFailsAfterRewind_raceWithIndirectConsumer();
  }

  public void runDiscoveredCppModuleLost() throws Exception {
    testCase.write(
        "foo/BUILD",
        """
        package(features = [
            "header_modules",
            "use_header_modules",
        ])

        cc_library(
            name = "top",
            srcs = ["top.cc"],
            deps = [":dep"],
        )

        cc_library(
            name = "dep",
            hdrs = ["dep.h"],
        )
        """);
    testCase.write("foo/top.cc", "#include \"foo/dep.h\"");
    testCase.write("foo/dep.h");

    AtomicReference<Artifact> depPcm = new AtomicReference<>();
    addSpawnShim(
        "Compiling foo/top.cc",
        (spawn, context) -> {
          ActionInput lostInput = SpawnInputUtils.getInputWithName(spawn, "dep.pic.pcm");
          depPcm.set((Artifact) lostInput);
          return createLostInputsExecException(
              context,
              ImmutableList.of(lostInput),
              new ActionInputDepOwnerMap(ImmutableList.of(lostInput)));
        });
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();

    testCase.buildTarget("//foo:top");

    verifyAllSpawnShimsConsumed();
    assertThat(rewoundKeys).containsExactly(Artifact.key(depPcm.get()));
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Compiling foo/dep.cppmap", 2);
  }

  public final void runLostTopLevelOutputWithRewindingDisabled() throws Exception {
    testCase.write(
        "foo/BUILD", "genrule(name = 'gen', outs = ['gen.out'], cmd = 'echo lost > $@')");
    testCase.addOptions("--norewind_lost_inputs");
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/gen.out"));

    var e = assertThrows(BuildFailedException.class, () -> testCase.buildTarget("//foo:gen"));
    lostOutputsModule.verifyAllLostOutputsConsumed();
    assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
        .isEqualTo(ActionRewinding.Code.LOST_OUTPUT_REWINDING_DISABLED);
    testCase.assertContainsError(
        "//foo:gen: Unexpected lost outputs (pass --rewind_lost_inputs to enable recovery):"
            + " foo/gen.out");
  }

  public final void runTopLevelOutputRewound_regularFile() throws Exception {
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_and_found_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return DefaultInfo(files = depset([lost, found]))

        lost_and_found = rule(implementation = _lost_and_found_impl)
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "lost_and_found")

        lost_and_found(name = "lost_and_found")
        """);
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"));
    Label fooLostAndFound = Label.parseCanonical("//foo:lost_and_found");
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLostAndFound, targetCompleteEvents);

    testCase.buildTarget("//foo:lost_and_found");

    lostOutputsModule.verifyAllLostOutputsConsumed();
    assertThat(rewoundKeys).hasSize(1);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:lost_and_found");
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/lost.out", 2);
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/found.out", 1);
    assertThat(targetCompleteEvents.keySet()).containsExactly(fooLostAndFound);
    assertOutputsReported(
        targetCompleteEvents.get(fooLostAndFound), "bin/foo/lost.out", "bin/foo/found.out");
    recorder.assertTotalLostOutputCountsFromStats(ImmutableList.of(1));
  }

  public final void runTopLevelOutputRewound_aspectOwned() throws Exception {
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_and_found_aspect_impl(target, ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return [OutputGroupInfo(default = depset([lost, found]))]

        lost_and_found_aspect = aspect(implementation = _lost_and_found_aspect_impl)
        """);
    testCase.write("foo/BUILD", "filegroup(name = 'lib')");
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"));
    Label fooLib = Label.parseCanonical("//foo:lib");
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, AspectCompleteEvent> aspectCompleteEvents = recordAspectCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLib, aspectCompleteEvents);

    testCase.addOptions("--aspects=foo/defs.bzl%lost_and_found_aspect");
    testCase.buildTarget("//foo:lib");

    lostOutputsModule.verifyAllLostOutputsConsumed();
    assertThat(rewoundKeys).hasSize(1);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//foo:lib");
    assertThat(((ActionLookupData) rewoundKeys.get(0)).getActionLookupKey())
        .isInstanceOf(AspectKey.class);
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/lost.out", 2);
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/found.out", 1);
    assertThat(aspectCompleteEvents.keySet()).containsExactly(fooLib);
    assertOutputsReported(
        aspectCompleteEvents.get(fooLib), "bin/foo/lost.out", "bin/foo/found.out");
    recorder.assertTotalLostOutputCountsFromStats(ImmutableList.of(1));
  }

  public final void runTopLevelOutputRewound_fileInTreeArtifact() throws Exception {
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_and_found_trees_impl(ctx):
            lost_tree = ctx.actions.declare_directory("lost_tree")
            found_tree = ctx.actions.declare_directory("found_tree")
            ctx.actions.run_shell(
                outputs = [lost_tree],
                command = "echo lost > %s/lost_file" % lost_tree.path,
            )
            ctx.actions.run_shell(
                outputs = [found_tree],
                command = "echo found > %s/found_file" % found_tree.path,
            )
            return DefaultInfo(files = depset([lost_tree, found_tree]))

        lost_and_found_trees = rule(implementation = _lost_and_found_trees_impl)
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "lost_and_found_trees")

        lost_and_found_trees(name = "lost_and_found_trees")
        """);
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost_tree/lost_file"));
    Label fooLostAndFoundTrees = Label.parseCanonical("//foo:lost_and_found_trees");
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLostAndFoundTrees, targetCompleteEvents);

    testCase.buildTarget("//foo:lost_and_found_trees");

    lostOutputsModule.verifyAllLostOutputsConsumed();
    assertTreeArtifactRewound(rewoundKeys, "foo/lost_tree");
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/lost_tree", 2);
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/found_tree", 1);
    assertThat(targetCompleteEvents.keySet()).containsExactly(fooLostAndFoundTrees);
    assertOutputsReported(
        targetCompleteEvents.get(fooLostAndFoundTrees),
        "bin/foo/lost_tree/lost_file",
        "bin/foo/found_tree/found_file");
    recorder.assertTotalLostOutputCountsFromStats(ImmutableList.of(1));
  }

  public final void runTopLevelOutputRewound_partiallyBuiltTarget_regularFile() throws Exception {
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_found_and_failed_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            failed = ctx.actions.declare_file("failed.out")
            ctx.actions.run_shell(
                outputs = [lost, found],
                command = "echo lost > %s && echo found > %s" % (lost.path, found.path),
            )
            ctx.actions.run_shell(outputs = [failed], inputs = [found], command = "false")
            return DefaultInfo(files = depset([lost, found, failed]))

        lost_found_and_failed = rule(implementation = _lost_found_and_failed_impl)
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "lost_found_and_failed")

        lost_found_and_failed(name = "lost_found_and_failed")
        """);
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost.out"));
    Label fooLostFoundAndFailed = Label.parseCanonical("//foo:lost_found_and_failed");
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLostFoundAndFailed, targetCompleteEvents);

    assertThrows(
        BuildFailedException.class, () -> testCase.buildTarget("//foo:lost_found_and_failed"));

    lostOutputsModule.verifyAllLostOutputsConsumed();

    assertThat(targetCompleteEvents.keySet()).containsExactly(fooLostFoundAndFailed);
    TargetCompleteEvent event = targetCompleteEvents.get(fooLostFoundAndFailed);
    assertThat(event.failed()).isTrue();

    if (keepGoing()) {
      assertThat(rewoundKeys).hasSize(1);
      assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
          .containsExactly("//foo:lost_found_and_failed");
      assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
          .hasCount("Action foo/lost.out", 2);
      assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
          .hasCount("Action foo/failed.out", 1);
      // The event is failed but still reports the built artifacts, including the one that was lost.
      assertOutputsReported(event, "bin/foo/lost.out", "bin/foo/found.out");
    } else {
      assertThat(rewoundKeys).isEmpty();
      assertThat(getExecutedSpawnDescriptions()).containsNoDuplicates();
      // The event does not report the lost artifact because with --nokeep_going, we have no
      // opportunity to rewind after an error is observed.
      assertOutputsReported(event, "bin/foo/found.out");
    }
    recorder.assertTotalLostOutputCountsFromStats(ImmutableList.of(1));
  }

  public final void runTopLevelOutputRewound_partiallyBuiltTarget_fileInTreeArtifact()
      throws Exception {
    ensureMultipleJobs();
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_tree_found_and_failed_impl(ctx):
            lost_tree = ctx.actions.declare_directory("lost_tree")
            found = ctx.actions.declare_file("found.out")
            failed = ctx.actions.declare_file("failed.out")
            ctx.actions.run_shell(
                outputs = [lost_tree, found],
                command = "echo lost > $1/lost_file && echo found > $2",
                arguments = [lost_tree.path, found.path],
            )
            ctx.actions.run_shell(outputs = [failed], inputs = [found], command = "false")
            return DefaultInfo(files = depset([lost_tree, found, failed]))

        lost_tree_found_and_failed = rule(implementation = _lost_tree_found_and_failed_impl)
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "lost_tree_found_and_failed")

        lost_tree_found_and_failed(name = "lost_tree_found_and_failed")
        """);
    lostOutputsModule.addLostOutput(getExecPath("bin/foo/lost_tree/lost_file"));
    Label fooLostTreeFoundAndFailed = Label.parseCanonical("//foo:lost_tree_found_and_failed");
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLostTreeFoundAndFailed, targetCompleteEvents);

    if (!keepGoing()) {
      // Block the failing action on the completion of the TreeArtifactValue (produced by
      // ArtifactFunction). Otherwise, the build may be aborted without considering it as built,
      // meaning it won't be observed to be lost.
      CountDownLatch treeArtifactDone = new CountDownLatch(1);
      testCase.injectListenerAtStartOfNextBuild(
          (key, type, order, context) -> {
            if (key instanceof Artifact artifact
                && artifact.isTreeArtifact()
                && type == EventType.SET_VALUE
                && order == Order.AFTER) {
              treeArtifactDone.countDown();
            }
          });
      addSpawnShim(
          "Action foo/failed.out",
          (spawn, context) -> {
            treeArtifactDone.await();
            return ExecResult.delegate();
          });
    }

    assertThrows(
        BuildFailedException.class, () -> testCase.buildTarget("//foo:lost_tree_found_and_failed"));

    lostOutputsModule.verifyAllLostOutputsConsumed();

    assertThat(targetCompleteEvents.keySet()).containsExactly(fooLostTreeFoundAndFailed);
    TargetCompleteEvent event = targetCompleteEvents.get(fooLostTreeFoundAndFailed);
    assertThat(event.failed()).isTrue();

    if (keepGoing()) {
      assertTreeArtifactRewound(rewoundKeys, "foo/lost_tree");
      assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
          .hasCount("Action foo/lost_tree", 2);
      assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
          .hasCount("Action foo/failed.out", 1);
      // The event is failed but still reports the built artifacts, including the one that was lost.
      assertOutputsReported(event, "bin/foo/lost_tree/lost_file", "bin/foo/found.out");
    } else {
      assertThat(rewoundKeys).isEmpty();
      assertThat(getExecutedSpawnDescriptions()).containsNoDuplicates();
      // The event does not report the lost artifact because with --nokeep_going, we have no
      // opportunity to rewind after an error is observed.
      assertOutputsReported(event, "bin/foo/found.out");
    }
    recorder.assertTotalLostOutputCountsFromStats(ImmutableList.of(1));
  }

  public final void runTopLevelOutputRewound_ineffectiveRewinding() throws Exception {
    testCase.write(
        "foo/defs.bzl",
        """
        def _lost_and_found_impl(ctx):
            lost = ctx.actions.declare_file("lost.out")
            found = ctx.actions.declare_file("found.out")
            ctx.actions.run_shell(outputs = [lost], command = "echo lost > %s" % lost.path)
            ctx.actions.run_shell(outputs = [found], command = "echo found > %s" % found.path)
            return DefaultInfo(files = depset([lost, found]))

        lost_and_found = rule(implementation = _lost_and_found_impl)
        """);
    testCase.write(
        "foo/BUILD",
        """
        load(":defs.bzl", "lost_and_found")

        lost_and_found(name = "lost_and_found")
        """);
    Label fooLostAndFound = Label.parseCanonical("//foo:lost_and_found");
    String outputExecPath = getExecPath("bin/foo/lost.out");
    RecordingBugReporter bugReporter = testCase.recordBugReportsAndReinitialize();
    List<SkyKey> rewoundKeys = collectOrderedRewoundKeys();
    Map<Label, TargetCompleteEvent> targetCompleteEvents = recordTargetCompleteEvents();
    listenForNoCompletionEventsBeforeRewinding(fooLostAndFound, targetCompleteEvents);

    for (int i = 0; i <= ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS; i++) {
      addSpawnShim(
          "Action foo/lost.out",
          (spawn, context) -> {
            lostOutputsModule.addLostOutput(outputExecPath);
            return ExecResult.delegate();
          });
    }

    BuildFailedException e =
        assertThrows(
            BuildFailedException.class, () -> testCase.buildTarget("//foo:lost_and_found"));
    assertThat(e.getDetailedExitCode().getFailureDetail().getActionRewinding().getCode())
        .isEqualTo(ActionRewinding.Code.LOST_OUTPUT_TOO_MANY_TIMES);

    assertOnlyActionsRewound(rewoundKeys);
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
        .containsExactlyElementsIn(
            Collections.nCopies(
                ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS, "//foo:lost_and_found"));
    assertThat(ImmutableMultiset.copyOf(getExecutedSpawnDescriptions()))
        .hasCount("Action foo/lost.out", ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1);

    ActionExecutionValue actionExecutionValue =
        (ActionExecutionValue)
            testCase.getSkyframeExecutor().getEvaluator().getExistingValue(rewoundKeys.get(0));
    byte[] lostDigest =
        actionExecutionValue.getAllFileValues().entrySet().stream()
            .filter(entry -> entry.getKey().getRootRelativePathString().equals("foo/lost.out"))
            .map(entry -> entry.getValue().getDigest())
            .collect(onlyElement());
    String expectedError =
        String.format(
            "Lost output foo/lost.out (digest %s), and rewinding was ineffective after %d"
                + " attempts.",
            toHex(lostDigest), ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS);
    testCase.assertContainsError(expectedError);
    assertThat(e.getDetailedExitCode().getFailureDetail().getMessage()).contains(expectedError);
    assertThat(Iterables.getOnlyElement(bugReporter.getExceptions()))
        .hasMessageThat()
        .contains(expectedError);

    // TargetCompleteEvent is failed and reports only the found output and not the lost output.
    assertThat(targetCompleteEvents.keySet()).containsExactly(fooLostAndFound);
    TargetCompleteEvent event = targetCompleteEvents.get(fooLostAndFound);
    assertThat(event.failed()).isTrue();
    assertOutputsReported(event, "bin/foo/found.out");

    recorder.assertTotalLostOutputCountsFromStats(
        ImmutableList.of(ActionRewindStrategy.MAX_REPEATED_LOST_INPUTS + 1));
  }

  final void listenForNoCompletionEventsBeforeRewinding(
      Label lostLabel, Map<Label, ? extends EventReportingArtifacts> events) {
    testCase.injectListenerAtStartOfNextBuild(
        (key, type, order, context) -> {
          if (type == EventType.MARK_DIRTY
              || (isActionExecutionKey(key, lostLabel) && type == EventType.SET_VALUE)) {
            // Completion events for lost outputs should not be emitted until after rewinding
            // completes. Otherwise, we may publish stale artifact URIs to the BEP.
            assertThat(events).isEmpty();
          }
        });
  }

  final void assertOutputsReported(
      EventReportingArtifacts event, String... expectedRootRelativePaths) throws Exception {
    ReportedArtifacts reported = event.reportedArtifacts(OutputGroupFileModes.DEFAULT);
    List<PathFragment> expectedExecPaths = new ArrayList<>();
    for (String path : expectedRootRelativePaths) {
      expectedExecPaths.add(PathFragment.create(getExecPath(path)));
    }
    PathFragment execRoot =
        testCase.getRuntimeWrapper().getCommandEnvironment().getExecRoot().asFragment();
    List<PathFragment> execPaths = new ArrayList<>();
    for (NestedSet<Artifact> set : reported.artifacts) {
      reported.completionContext.visitArtifacts(
          set.toList(),
          new ArtifactReceiver() {
            @Override
            public void accept(Artifact artifact) {
              execPaths.add(artifact.getExecPath());
            }

            @Override
            public void acceptFilesetMapping(
                Artifact fileset, PathFragment relName, Path targetFile) {
              execPaths.add(targetFile.asFragment().relativeTo(execRoot));
            }
          });
    }
    assertThat(execPaths).containsExactlyElementsIn(expectedExecPaths);
  }

  static boolean isActionExecutionKey(Object key, Label label) {
    return key instanceof ActionLookupData && label.equals(((ActionLookupData) key).getLabel());
  }

  static void awaitUninterruptibly(CountDownLatch latch) {
    assertThat(
            Uninterruptibles.awaitUninterruptibly(latch, TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS))
        .isTrue();
  }

  /**
   * Ensures that the value of the {@code --jobs} flag is at least 2.
   *
   * <p>Several tests use artificial synchronization to exercise certain race conditions and require
   * a multiple execution phase threads to guarantee progress.
   *
   * <p>Note that the default value for {@code --jobs} is automatically calculated based on host
   * CPU.
   */
  private void ensureMultipleJobs() throws Exception {
    int autoJobs = new JobsConverter().convert("auto");
    if (autoJobs == 1) {
      logger.atInfo().log("Setting --jobs=2 (was 1)");
      testCase.addOptions("--jobs=2");
    } else {
      logger.atInfo().log("Keeping default value of --jobs=%s", autoJobs);
    }
  }

  private boolean keepGoing() {
    return testCase.getRuntimeWrapper().getOptions(KeepGoingOption.class).keepGoing;
  }

  final boolean buildRunfileManifests() {
    return testCase.getRuntimeWrapper().getOptions(CoreOptions.class).buildRunfileManifests;
  }

  final Map<Label, TargetCompleteEvent> recordTargetCompleteEvents() {
    Map<Label, TargetCompleteEvent> targetCompleteEvents = new HashMap<>();
    testCase
        .getRuntimeWrapper()
        .registerSubscriber(
            new Object() {
              @Subscribe
              @SuppressWarnings("unused")
              public void accept(TargetCompleteEvent event) {
                var prev = targetCompleteEvents.put(event.getLabel(), event);
                checkState(prev == null, "Duplicate TargetCompleteEvent for %s", event.getLabel());
              }
            });
    return targetCompleteEvents;
  }

  private Map<Label, AspectCompleteEvent> recordAspectCompleteEvents() {
    Map<Label, AspectCompleteEvent> aspectCompleteEvents = new HashMap<>();
    testCase
        .getRuntimeWrapper()
        .registerSubscriber(
            new Object() {
              @Subscribe
              @SuppressWarnings("unused")
              public void accept(AspectCompleteEvent event) {
                // If we need to track targets with multiple aspects, we could change the key type.
                var prev = aspectCompleteEvents.put(event.getLabel(), event);
                checkState(prev == null, "Duplicate AspectCompleteEvent for %s", event.getLabel());
              }
            });
    return aspectCompleteEvents;
  }

  /**
   * Converts a root-relative output path to an exec path, accounting for the top-level
   * configuration's mnemonic and {@link TestConstants#PRODUCT_NAME}.
   *
   * <p>Example: bin/pkg/file.out -> bazel-out/k8-fastbuild/bin/pkg/file.out
   */
  private String getExecPath(String rootRelativePath) throws Exception {
    if (testCase.getTargetConfigurationFromLastBuildResult() == null) {
      // Need at least one build to get the configuration, so run a null build.
      testCase.buildTarget();
      recorder.clear(); // Don't record stats for the null build.
    }
    return testCase
        .getTargetConfigurationFromLastBuildResult()
        .getOutputDirectory(RepositoryName.MAIN)
        .getExecPath()
        .getRelative(rootRelativePath)
        .getPathString();
  }
}
