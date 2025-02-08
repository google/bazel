// Copyright 2015 The Bazel Authors. All rights reserved.
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
import static com.google.devtools.build.lib.actions.FileArtifactValue.createForTesting;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.RunfilesTreeAction;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.SourceArtifactException;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ArtifactFunction}.
 */
// Doesn't actually need any particular Skyframe, but is only relevant to Skyframe full mode.
@RunWith(JUnit4.class)
public class ArtifactFunctionTest extends ArtifactFunctionTestCase {

  @Before
  public final void setUp() {
    delegateActionExecutionFunction = new SimpleActionExecutionFunction();
  }

  private void assertFileArtifactValueMatches() throws Exception {
    Artifact output = createDerivedArtifact("output");
    Path path = output.getPath();
    file(path, "contents");
    assertValueMatches(path.stat(), path.getDigest(), evaluateFileArtifactValue(output));
  }

  @Test
  public void testBasicArtifact() throws Exception {
    fastDigest = false;
    assertFileArtifactValueMatches();
  }

  @Test
  public void testBasicArtifactWithXattr() throws Exception {
    fastDigest = true;
    assertFileArtifactValueMatches();
  }

  @Test
  public void testMissingNonMandatoryArtifact() throws Throwable {
    Artifact input = createSourceArtifact("input1");
    assertThat(evaluateArtifactValue(input)).isNotNull();
  }

  @Test
  public void testUnreadableInputWithFsWithAvailableDigest() throws Throwable {
    final byte[] expectedDigest = {1, 2, 3, 4};
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public byte[] getDigest(PathFragment path) throws IOException {
            return path.getBaseName().equals("unreadable") ? expectedDigest : super.getDigest(path);
          }
        });

    Artifact input = createSourceArtifact("unreadable");
    Path inputPath = input.getPath();
    file(inputPath, "dummynotused");
    inputPath.chmod(0);

    FileArtifactValue value = (FileArtifactValue) evaluateArtifactValue(input);

    FileStatus stat = inputPath.stat();
    assertThat(value.getSize()).isEqualTo(stat.getSize());
    assertThat(value.getDigest()).isEqualTo(expectedDigest);
  }

  @Test
  public void testRunfilesTree() throws Throwable {
    DerivedArtifact output = createRunfilesArtifact("output");
    Artifact input1 = createSourceArtifact("input1");
    Artifact input2 = createDerivedArtifact("input2");
    SpecialArtifact tree = createDerivedTreeArtifactWithAction("treeArtifact");
    TreeFileArtifact treeFile1 = createFakeTreeFileArtifact(tree, "child1", "hello1");
    TreeFileArtifact treeFile2 = createFakeTreeFileArtifact(tree, "child2", "hello2");
    file(treeFile1.getPath(), "src1");
    file(treeFile2.getPath(), "src2");
    RunfilesTree mockRunfilesTree = mock(RunfilesTree.class);
    Action action =
        new RunfilesTreeAction(
            ActionsTestUtil.NULL_ACTION_OWNER,
            mockRunfilesTree,
            NestedSetBuilder.create(Order.STABLE_ORDER, input1, input2, tree),
            ImmutableSet.of(output));
    actions.add(action);
    file(input2.getPath(), "contents");
    file(input1.getPath(), "source contents");

    SkyValue value = evaluateArtifactValue(output);
    assertThat(value)
        .isEqualTo(
            new RunfilesArtifactValue(
                mockRunfilesTree,
                ImmutableList.of(input1, input2),
                ImmutableList.of(createForTesting(input1), createForTesting(input2)),
                ImmutableList.of(tree),
                ImmutableList.of((TreeArtifactValue) evaluateArtifactValue(tree))));
  }

  /**
   * Tests that ArtifactFunction rethrows a transitive {@link IOException} as an {@link
   * SourceArtifactException}.
   */
  @Test
  public void testIOException_endToEnd() throws Throwable {
    IOException exception = new IOException("beep");
    setupRoot(
        new CustomInMemoryFs() {
          @Override
          public FileStatus statIfFound(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (path.getBaseName().equals("bad")) {
              throw exception;
            }
            return super.statIfFound(path, followSymlinks);
          }
        });
    Artifact sourceArtifact = createSourceArtifact("bad");
    SourceArtifactException e =
        assertThrows(SourceArtifactException.class, () -> evaluateArtifactValue(sourceArtifact));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("error reading file '" + sourceArtifact.getExecPathString() + "': beep");
  }

  @Test
  public void testActionTreeArtifactOutput() throws Throwable {
    SpecialArtifact artifact = createDerivedTreeArtifactWithAction("treeArtifact");
    TreeFileArtifact treeFileArtifact1 = createFakeTreeFileArtifact(artifact, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 = createFakeTreeFileArtifact(artifact, "child2", "hello2");

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  @Test
  public void testSpawnActionTemplate() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    SpecialArtifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");

    // artifact2 is a tree artifact generated by action template.
    SpecialArtifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    actions.add(actionTemplate);
    TreeFileArtifact treeFileArtifact1 =
        createFakeExpansionTreeFileArtifact(actionTemplate, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 =
        createFakeExpansionTreeFileArtifact(actionTemplate, "child2", "hello2");

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact2);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  @Test
  public void testConsecutiveSpawnActionTemplates() throws Throwable {
    // artifact1 is a tree artifact generated by normal action.
    SpecialArtifact artifact1 = createDerivedTreeArtifactWithAction("treeArtifact1");
    createFakeTreeFileArtifact(artifact1, "child1", "hello1");
    createFakeTreeFileArtifact(artifact1, "child2", "hello2");

    // artifact2 is a tree artifact generated by action template.
    SpecialArtifact artifact2 = createDerivedTreeArtifactOnly("treeArtifact2");
    SpawnActionTemplate template2 =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    actions.add(template2);
    createFakeExpansionTreeFileArtifact(template2, "child1", "hello1");
    createFakeExpansionTreeFileArtifact(template2, "child2", "hello2");

    // artifact3 is a tree artifact generated by action template.
    SpecialArtifact artifact3 = createDerivedTreeArtifactOnly("treeArtifact3");
    SpawnActionTemplate template3 =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact2, artifact3);
    actions.add(template3);
    TreeFileArtifact treeFileArtifact1 =
        createFakeExpansionTreeFileArtifact(template3, "child1", "hello1");
    TreeFileArtifact treeFileArtifact2 =
        createFakeExpansionTreeFileArtifact(template3, "child2", "hello2");

    TreeArtifactValue value = (TreeArtifactValue) evaluateArtifactValue(artifact3);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact1);
    assertThat(value.getChildValues()).containsKey(treeFileArtifact2);
    assertThat(value.getChildValues().get(treeFileArtifact1).getDigest()).isNotNull();
    assertThat(value.getChildValues().get(treeFileArtifact2).getDigest()).isNotNull();
  }

  private static void file(Path path, String contents) throws Exception {
    path.getParentDirectory().createDirectoryAndParents();
    writeFile(path, contents);
  }

  private Artifact createSourceArtifact(String path) {
    return ActionsTestUtil.createArtifactWithExecPath(
        ArtifactRoot.asSourceRoot(Root.fromPath(root)), PathFragment.create(path));
  }

  private DerivedArtifact createDerivedArtifact(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    DerivedArtifact output =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"), execPath, ALL_OWNER);
    actions.add(new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output));
    output.setGeneratingActionKey(ActionLookupData.create(ALL_OWNER, actions.size() - 1));
    return output;
  }

  private SpecialArtifact createRunfilesArtifact(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    return SpecialArtifact.create(
        ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
        execPath,
        ALL_OWNER,
        SpecialArtifactType.RUNFILES);
  }

  private SpecialArtifact createDerivedTreeArtifactWithAction(String path) {
    SpecialArtifact treeArtifact = createDerivedTreeArtifactOnly(path);
    actions.add(new DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), treeArtifact));
    treeArtifact.setGeneratingActionKey(ActionLookupData.create(ALL_OWNER, actions.size() - 1));
    return treeArtifact;
  }

  private SpecialArtifact createDerivedTreeArtifactOnly(String path) {
    PathFragment execPath = PathFragment.create("out").getRelative(path);
    return SpecialArtifact.create(
        ArtifactRoot.asDerivedRoot(root, RootType.Output, "out"),
        execPath,
        ALL_OWNER,
        SpecialArtifactType.TREE);
  }

  private static TreeFileArtifact createFakeTreeFileArtifact(
      SpecialArtifact treeArtifact, String parentRelativePath, String content) throws Exception {
    TreeFileArtifact treeFileArtifact =
        TreeFileArtifact.createTreeOutput(treeArtifact, parentRelativePath);
    Path path = treeFileArtifact.getPath();
    path.getParentDirectory().createDirectoryAndParents();
    writeFile(path, content);
    return treeFileArtifact;
  }

  private TreeFileArtifact createFakeExpansionTreeFileArtifact(
      ActionTemplate<?> actionTemplate, String parentRelativePath, String content)
      throws Exception {
    int actionIndex = Iterables.indexOf(actions, actionTemplate::equals);
    Preconditions.checkState(actionIndex >= 0, "%s not registered", actionTemplate);
    TreeFileArtifact treeFileArtifact =
        TreeFileArtifact.createTemplateExpansionOutput(
            actionTemplate.getOutputTreeArtifact(),
            parentRelativePath,
            ActionTemplateExpansionValue.key(ALL_OWNER, actionIndex));
    Path path = treeFileArtifact.getPath();
    path.getParentDirectory().createDirectoryAndParents();
    writeFile(path, content);
    return treeFileArtifact;
  }

  private static void assertValueMatches(FileStatus file, byte[] digest, FileArtifactValue value)
      throws IOException {
    assertThat(value.getSize()).isEqualTo(file.getSize());
    if (digest == null) {
      assertThat(value.getDigest()).isNull();
      assertThat(value.getModifiedTime()).isEqualTo(file.getLastModifiedTime());
    } else {
      assertThat(value.getDigest()).isEqualTo(digest);
    }
  }

  private FileArtifactValue evaluateFileArtifactValue(Artifact artifact) throws Exception {
    SkyValue value = evaluateArtifactValue(artifact);
    assertThat(value).isInstanceOf(FileArtifactValue.class);
    return (FileArtifactValue) value;
  }

  private SkyValue evaluateArtifactValue(Artifact artifact) throws Exception {
    SkyKey key = Artifact.key(artifact);
    EvaluationResult<SkyValue> result = evaluate(ImmutableList.of(key).toArray(new SkyKey[0]));
    if (result.hasError()) {
      throw result.getError().getException();
    }
    SkyValue value = result.get(key);
    if (value instanceof ActionExecutionValue actionExecutionValue) {
      return actionExecutionValue.getExistingFileArtifactValue(artifact);
    }
    return value;
  }

  private void setGeneratingActions()
      throws InterruptedException, ActionConflictException,
          Actions.ArtifactGeneratedByOtherRuleException {
    if (evaluator.getExistingValue(ALL_OWNER) == null) {
      ImmutableList<ActionAnalysisMetadata> generatingActions = ImmutableList.copyOf(actions);
      Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
          actionKeyContext, generatingActions, ALL_OWNER);
      differencer.inject(
          ImmutableMap.of(ALL_OWNER, Delta.justNew(new BasicActionLookupValue(generatingActions))));
    }
  }

  private <E extends SkyValue> EvaluationResult<E> evaluate(SkyKey... keys)
      throws InterruptedException, ActionConflictException,
          Actions.ArtifactGeneratedByOtherRuleException {
    setGeneratingActions();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
            .setEventHandler(NullEventHandler.INSTANCE)
            .build();
    return evaluator.evaluate(Arrays.asList(keys), evaluationContext);
  }

  /**
   * Value builder for actions that just stats and stores the output file (which must either be
   * orphaned or exist).
   */
  private static final class SimpleActionExecutionFunction implements SkyFunction {
    SimpleActionExecutionFunction() {}

    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
      Map<Artifact, FileArtifactValue> artifactData = new HashMap<>();
      Map<Artifact, TreeArtifactValue> treeArtifactData = new HashMap<>();
      ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
      ActionLookupValue actionLookupValue =
          (ActionLookupValue) env.getValue(actionLookupData.getActionLookupKey());
      Action action = actionLookupValue.getAction(actionLookupData.getActionIndex());
      Artifact output = Iterables.getOnlyElement(action.getOutputs());

      try {
        if (output.isTreeArtifact()) {
          SpecialArtifact parent = (SpecialArtifact) output;
          TreeFileArtifact treeFileArtifact1 =
              TreeFileArtifact.createTreeOutput((SpecialArtifact) output, "child1");
          TreeFileArtifact treeFileArtifact2 =
              TreeFileArtifact.createTreeOutput((SpecialArtifact) output, "child2");
          TreeArtifactValue tree =
              TreeArtifactValue.newBuilder(parent)
                  .putChild(
                      treeFileArtifact1, FileArtifactValue.createForTesting(treeFileArtifact1))
                  .putChild(
                      treeFileArtifact2, FileArtifactValue.createForTesting(treeFileArtifact2))
                  .build();
          treeArtifactData.put(output, tree);
        } else if (output.isRunfilesTree()) {
          artifactData.put(output, FileArtifactValue.RUNFILES_TREE_MARKER);
        } else {
          Path path = output.getPath();
          FileArtifactValue noDigest =
              ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                  output,
                  FileStatusWithDigestAdapter.maybeAdapt(path.statIfFound(Symlinks.NOFOLLOW)),
                  SyscallCache.NO_CACHE,
                  null);
          FileArtifactValue withDigest =
              FileArtifactValue.createFromInjectedDigest(noDigest, path.getDigest());
          artifactData.put(output, withDigest);
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return ActionsTestUtil.createActionExecutionValue(
          ImmutableMap.copyOf(artifactData), ImmutableMap.copyOf(treeArtifactData));
    }
  }
}
