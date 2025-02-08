// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.InjectedActionLookupKey;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.OutputPathMapper;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.bugreport.Crash;
import com.google.devtools.build.lib.bugreport.CrashContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ActionTemplateExpansionFunction}. */
@RunWith(JUnit4.class)
public final class ActionTemplateExpansionFunctionTest extends FoundationTestCase  {

  private final Map<Artifact, TreeArtifactValue> artifactValueMap = new LinkedHashMap<>();
  private final SequencedRecordingDifferencer differencer = new SequencedRecordingDifferencer();
  private final RecordingBugReporter bugReporter = new RecordingBugReporter();
  private final MemoizingEvaluator evaluator =
      new InMemoryMemoizingEvaluator(
          ImmutableMap.of(
              Artifact.ARTIFACT,
              new DummyArtifactFunction(artifactValueMap),
              SkyFunctions.ACTION_TEMPLATE_EXPANSION,
              new ActionTemplateExpansionFunction(new ActionKeyContext(), bugReporter)),
          differencer);

  @Before
  public void setUp() {
    PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID());
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(
        differencer,
        new PathPackageLocator(
            rootDirectory.getFileSystem().getPath("/outputbase"),
            ImmutableList.of(Root.fromPath(rootDirectory)),
            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
  }

  @After
  public void assertNoBugReports() {
    bugReporter.assertNoExceptions();
  }

  @Test
  public void testActionTemplateExpansionFunction() throws Exception {
    SpecialArtifact inputTreeArtifact =
        createAndPopulateTreeArtifact("inputTreeArtifact", "child0", "child1", "child2");
    SpecialArtifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");

    SpawnActionTemplate spawnActionTemplate = ActionsTestUtil.createDummySpawnActionTemplate(
        inputTreeArtifact, outputTreeArtifact);
    List<Action> actions = evaluate(spawnActionTemplate);
    assertThat(actions).hasSize(3);

    ArtifactOwner owner = ActionTemplateExpansionValue.key(CTKEY, 0);
    int i = 0;
    for (Action action : actions) {
      String childName = "child" + i;
      assertThat(Artifact.asExecPaths(action.getInputs()))
          .contains("out/inputTreeArtifact/" + childName);
      assertThat(Artifact.asExecPaths(action.getOutputs()))
          .containsExactly("out/outputTreeArtifact/" + childName);
      assertThat(Iterables.getOnlyElement(action.getOutputs()).getArtifactOwner()).isEqualTo(owner);
      ++i;
    }
  }

  @Test
  public void testThrowsOnActionConflict() throws Exception {
    SpecialArtifact inputTreeArtifact =
        createAndPopulateTreeArtifact("inputTreeArtifact", "child0", "child1", "child2");
    SpecialArtifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");

    OutputPathMapper mapper = artifact -> PathFragment.create("conflict_path");
    SpawnActionTemplate spawnActionTemplate =
        new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutable(PathFragment.create("/bin/cp"))
            .setCommandLineTemplate(CustomCommandLine.builder().build())
            .setOutputPathMapper(mapper)
            .build(ActionsTestUtil.NULL_ACTION_OWNER);

    ActionConflictException e =
        assertThrows(ActionConflictException.class, () -> evaluate(spawnActionTemplate));
    assertThat(bugReporter.getExceptions()).hasSize(1);
    assertThat(bugReporter.getFirstCause()).isSameInstanceAs(e);
    assertThat(bugReporter.getExceptions().get(0))
        .hasMessageThat()
        .contains("Unexpected action conflict for ActionTemplateExpansionKey{");
    bugReporter.clear();
  }

  @Test
  public void testThrowsOnArtifactPrefixConflict() throws Exception {
    SpecialArtifact inputTreeArtifact =
        createAndPopulateTreeArtifact("inputTreeArtifact", "child0", "child1", "child2");
    SpecialArtifact outputTreeArtifact = createTreeArtifact("outputTreeArtifact");

    OutputPathMapper mapper = new OutputPathMapper() {
      private int i = 0;
      @Override
      public PathFragment parentRelativeOutputPath(TreeFileArtifact inputTreeFileArtifact) {
        PathFragment path;
        switch (i) {
          case 0:
            path = PathFragment.create("path_prefix");
            break;
          case 1:
            path = PathFragment.create("path_prefix/conflict");
            break;
          default:
            path = inputTreeFileArtifact.getParentRelativePath();
        }

        ++i;
        return path;
      }
    };
    SpawnActionTemplate spawnActionTemplate =
        new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
            .setExecutable(PathFragment.create("/bin/cp"))
            .setCommandLineTemplate(CustomCommandLine.builder().build())
            .setOutputPathMapper(mapper)
            .build(ActionsTestUtil.NULL_ACTION_OWNER);

    ActionConflictException e =
        assertThrows(ActionConflictException.class, () -> evaluate(spawnActionTemplate));
    assertThat(bugReporter.getExceptions()).hasSize(1);
    assertThat(bugReporter.getFirstCause()).isSameInstanceAs(e);
    assertThat(e).hasMessageThat().contains("is a prefix of the other");
    var exception = bugReporter.getExceptions().get(0);
    assertThat(exception)
        .hasMessageThat()
        .contains("Unexpected action conflict for ActionTemplateExpansionKey{");
    bugReporter.clear();
  }

  @Test
  public void cannotDeclareNonTreeOutput() throws Exception {
    SpecialArtifact inputTree = createAndPopulateTreeArtifact("input", "child");
    SpecialArtifact outputTree = createTreeArtifact("output");

    ActionTemplate<DummyAction> template =
        new TestActionTemplate(inputTree, outputTree) {
          @Override
          public ImmutableList<DummyAction> generateActionsForInputArtifacts(
              ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts,
              ActionLookupKey artifactOwner) {
            return ImmutableList.of();
          }

          @Override
          public ImmutableSet<Artifact> getOutputs() {
            return ImmutableSet.of(
                outputTree,
                DerivedArtifact.create(
                    outputTree.getRoot(),
                    outputTree.getRoot().getExecPath().getRelative("not_tree"),
                    outputTree.getArtifactOwner()));
          }
        };

    Exception e = assertThrows(RuntimeException.class, () -> evaluate(template));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(template + " declares an output which is not a tree artifact");
  }

  @Test
  public void cannotGenerateOutputWithWrongOwner() throws Exception {
    SpecialArtifact inputTree = createAndPopulateTreeArtifact("input", "child");
    SpecialArtifact outputTree = createTreeArtifact("output");

    ActionTemplate<DummyAction> template =
        new TestActionTemplate(inputTree, outputTree) {
          @Override
          public ImmutableList<DummyAction> generateActionsForInputArtifacts(
              ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts,
              ActionLookupKey artifactOwner) {
            TreeFileArtifact input = Iterables.getOnlyElement(inputTreeFileArtifacts);
            TreeFileArtifact outputWithWrongOwner =
                TreeFileArtifact.createTemplateExpansionOutput(
                    outputTree, "child", ActionsTestUtil.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER);
            assertThat(outputWithWrongOwner.getArtifactOwner()).isNotEqualTo(artifactOwner);
            return ImmutableList.of(new DummyAction(input, outputWithWrongOwner));
          }
        };

    Exception e = assertThrows(RuntimeException.class, () -> evaluate(template));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(template + " generated an action with an output owned by the wrong owner");
  }

  @Test
  public void cannotGenerateNonTreeFileArtifactOutput() throws Exception {
    SpecialArtifact inputTree = createAndPopulateTreeArtifact("input", "child");
    SpecialArtifact outputTree = createTreeArtifact("output");

    ActionTemplate<DummyAction> template =
        new TestActionTemplate(inputTree, outputTree) {
          @Override
          public ImmutableList<DummyAction> generateActionsForInputArtifacts(
              ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts,
              ActionLookupKey artifactOwner) {
            TreeFileArtifact input = Iterables.getOnlyElement(inputTreeFileArtifacts);
            Artifact notTreeFileArtifact =
                DerivedArtifact.create(
                    input.getRoot(),
                    input.getRoot().getExecPath().getRelative("a.txt"),
                    artifactOwner);
            assertThat(notTreeFileArtifact.isTreeArtifact()).isFalse();
            return ImmutableList.of(new DummyAction(input, notTreeFileArtifact));
          }
        };

    Exception e = assertThrows(RuntimeException.class, () -> evaluate(template));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(template + " generated an action which outputs a non-TreeFileArtifact");
  }

  @Test
  public void cannotGenerateOutputUnderUndeclaredTree() throws Exception {
    SpecialArtifact inputTree = createAndPopulateTreeArtifact("input", "child");
    SpecialArtifact outputTree = createTreeArtifact("output");

    ActionTemplate<DummyAction> template =
        new TestActionTemplate(inputTree, outputTree) {
          @Override
          public ImmutableList<DummyAction> generateActionsForInputArtifacts(
              ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts,
              ActionLookupKey artifactOwner) {
            TreeFileArtifact input = Iterables.getOnlyElement(inputTreeFileArtifacts);
            TreeFileArtifact outputUnderWrongTree =
                TreeFileArtifact.createTemplateExpansionOutput(
                    createTreeArtifact("undeclared"), "child", artifactOwner);
            return ImmutableList.of(new DummyAction(input, outputUnderWrongTree));
          }
        };

    Exception e = assertThrows(RuntimeException.class, () -> evaluate(template));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            template
                + " generated an action with an output File:[[<execution_root>]out]undeclared/child"
                + " under an undeclared tree not in [File:[[<execution_root>]out]output]");
  }

  @Test
  public void canGenerateOutputUnderAdditionalDeclaredTree() throws Exception {
    SpecialArtifact inputTree = createAndPopulateTreeArtifact("input", "child");
    SpecialArtifact outputTree = createTreeArtifact("output");
    SpecialArtifact additionalOutputTree = createTreeArtifact("additional_output");

    ActionTemplate<DummyAction> template =
        new TestActionTemplate(inputTree, outputTree) {
          @Override
          public ImmutableList<DummyAction> generateActionsForInputArtifacts(
              ImmutableSet<TreeFileArtifact> inputTreeFileArtifacts,
              ActionLookupKey artifactOwner) {
            TreeFileArtifact input = Iterables.getOnlyElement(inputTreeFileArtifacts);
            return ImmutableList.of(
                new DummyAction(
                    input,
                    TreeFileArtifact.createTemplateExpansionOutput(
                        outputTree, "child", artifactOwner)),
                new DummyAction(
                    input,
                    TreeFileArtifact.createTemplateExpansionOutput(
                        additionalOutputTree, "additional_child", artifactOwner)));
          }

          @Override
          public ImmutableSet<Artifact> getOutputs() {
            return ImmutableSet.of(outputTree, additionalOutputTree);
          }
        };

    evaluate(template);
  }

  private static final ActionLookupKey CTKEY = new InjectedActionLookupKey("key");

  private ImmutableList<Action> evaluate(ActionTemplate<?> actionTemplate) throws Exception {
    ActionLookupValue ctValue = createActionLookupValue(actionTemplate);

    differencer.inject(CTKEY, Delta.justNew(ctValue));
    ActionTemplateExpansionKey templateKey = ActionTemplateExpansionValue.key(CTKEY, 0);
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    EvaluationResult<ActionTemplateExpansionValue> result =
        evaluator.evaluate(ImmutableList.of(templateKey), evaluationContext);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    ActionTemplateExpansionValue actionTemplateExpansionValue = result.get(templateKey);
    ImmutableList.Builder<Action> actionList = ImmutableList.builder();
    for (int i = 0; i < actionTemplateExpansionValue.getNumActions(); i++) {
      actionList.add(actionTemplateExpansionValue.getAction(i));
    }
    return actionList.build();
  }

  private static ActionLookupValue createActionLookupValue(ActionTemplate<?> actionTemplate)
      throws ActionConflictException,
          InterruptedException,
          Actions.ArtifactGeneratedByOtherRuleException {
    ImmutableList<ActionAnalysisMetadata> actions = ImmutableList.of(actionTemplate);
    Actions.assignOwnersAndThrowIfConflict(new ActionKeyContext(), actions, CTKEY);
    return new BasicActionLookupValue(actions);
  }

  private SpecialArtifact createTreeArtifact(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    return SpecialArtifact.create(
        ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, "out"),
        execPath,
        CTKEY,
        SpecialArtifactType.TREE);
  }

  private SpecialArtifact createAndPopulateTreeArtifact(String path, String... childRelativePaths)
      throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact(path);
    treeArtifact.setGeneratingActionKey(ActionLookupData.create(CTKEY, /*actionIndex=*/ 0));
    TreeArtifactValue.Builder tree = TreeArtifactValue.newBuilder(treeArtifact);

    for (String childRelativePath : childRelativePaths) {
      TreeFileArtifact treeFileArtifact =
          TreeFileArtifact.createTreeOutput(treeArtifact, childRelativePath);
      scratch.file(treeFileArtifact.getPath().toString(), childRelativePath);
      // We do not care about the FileArtifactValues in this test.
      tree.putChild(treeFileArtifact, FileArtifactValue.createForTesting(treeFileArtifact));
    }

    artifactValueMap.put(treeArtifact, tree.build());
    return treeArtifact;
  }

  /** Dummy ArtifactFunction that just returns injected values */
  private static final class DummyArtifactFunction implements SkyFunction {
    private final Map<Artifact, TreeArtifactValue> artifactValueMap;

    DummyArtifactFunction(Map<Artifact, TreeArtifactValue> artifactValueMap) {
      this.artifactValueMap = artifactValueMap;
    }
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) {
      return Preconditions.checkNotNull(artifactValueMap.get(skyKey));
    }
  }

  private abstract static class TestActionTemplate implements ActionTemplate<DummyAction> {
    private final SpecialArtifact inputTreeArtifact;
    private final SpecialArtifact outputTreeArtifact;

    TestActionTemplate(SpecialArtifact inputTreeArtifact, SpecialArtifact outputTreeArtifact) {
      Preconditions.checkArgument(inputTreeArtifact.isTreeArtifact(), inputTreeArtifact);
      Preconditions.checkArgument(outputTreeArtifact.isTreeArtifact(), outputTreeArtifact);
      this.inputTreeArtifact = inputTreeArtifact;
      this.outputTreeArtifact = outputTreeArtifact;
    }

    @Override
    public SpecialArtifact getInputTreeArtifact() {
      return inputTreeArtifact;
    }

    @Override
    public SpecialArtifact getOutputTreeArtifact() {
      return outputTreeArtifact;
    }

    @Override
    public ActionOwner getOwner() {
      return ActionsTestUtil.NULL_ACTION_OWNER;
    }

    @Override
    public boolean isShareable() {
      return false;
    }

    @Override
    public String getMnemonic() {
      return "TestActionTemplate";
    }

    @Override
    public String getKey(
        ActionKeyContext actionKeyContext, @Nullable ArtifactExpander artifactExpander) {
      Fingerprint fp = new Fingerprint();
      fp.addPath(inputTreeArtifact.getPath());
      fp.addPath(outputTreeArtifact.getPath());
      return fp.hexDigestAndReset();
    }

    @Override
    public String prettyPrint() {
      return "TestActionTemplate for " + outputTreeArtifact;
    }

    @Override
    public String describe() {
      return prettyPrint();
    }

    @Override
    public NestedSet<Artifact> getTools() {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public NestedSet<Artifact> getInputs() {
      return NestedSetBuilder.create(Order.STABLE_ORDER, inputTreeArtifact);
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return getInputs();
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public Collection<String> getClientEnvironmentVariables() {
      return ImmutableList.of();
    }

    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public ImmutableSet<Artifact> getMandatoryOutputs() {
      return ImmutableSet.of();
    }

    @Override
    public NestedSet<Artifact> getMandatoryInputs() {
      return NestedSetBuilder.create(Order.STABLE_ORDER, inputTreeArtifact);
    }

    @Override
    public String toString() {
      return prettyPrint();
    }
  }

  /** {@link BugReporter} that stores bug reports for later inspection. */
  private static class RecordingBugReporter implements BugReporter {
    @GuardedBy("this")
    private final List<Throwable> exceptions = new ArrayList<>();

    @Override
    public synchronized void sendBugReport(
        Throwable exception, List<String> args, String... values) {
      exceptions.add(exception);
    }

    @Override
    public synchronized void sendNonFatalBugReport(Throwable exception) {
      exceptions.add(exception);
    }

    @Override
    public void handleCrash(Crash crash, CrashContext ctx) {
      // Unexpected: try to crash JVM.
      BugReport.handleCrash(crash, ctx);
    }

    public synchronized ImmutableList<Throwable> getExceptions() {
      return ImmutableList.copyOf(exceptions);
    }

    public synchronized Throwable getFirstCause() {
      assertThat(exceptions).isNotEmpty();
      Throwable first = exceptions.get(0);
      assertThat(first).hasCauseThat().isNotNull();
      return first.getCause();
    }

    public synchronized void assertNoExceptions() {
      assertThat(exceptions).isEmpty();
    }

    public synchronized void clear() {
      exceptions.clear();
    }
  }
}
