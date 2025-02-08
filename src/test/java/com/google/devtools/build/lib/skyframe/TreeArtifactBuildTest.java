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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.actions.util.TestAction.DummyAction;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.server.FailureDetails.Crash;
import com.google.devtools.build.lib.server.FailureDetails.Crash.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationDepsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Timestamp builder tests for TreeArtifacts. */
@RunWith(JUnit4.class)
public final class TreeArtifactBuildTest extends TimestampBuilderTestCase {

  @Test
  public void codec() throws Exception {
    SpecialArtifact parent = createTreeArtifact("parent");
    parent.setGeneratingActionKey(ActionLookupData.create(ACTION_LOOKUP_KEY, 0));
    new SerializationTester(parent, TreeFileArtifact.createTreeOutput(parent, "child"))
        .addDependency(FileSystem.class, scratch.getFileSystem())
        .addDependency(
            Root.RootCodecDependencies.class,
            new Root.RootCodecDependencies(Root.absoluteRoot(scratch.getFileSystem())))
        .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
        .runTests();
  }

  /** Simple smoke test. If this isn't passing, something is very wrong... */
  @Test
  public void treeArtifactSimpleCase() throws Exception {
    SpecialArtifact parent = createTreeArtifact("parent");
    TouchingTestAction action = new TouchingTestAction(parent, "out1", "out2");
    registerAction(action);

    TreeArtifactValue result = buildArtifact(parent);

    verifyOutputTree(result, parent, "out1", "out2");
  }

  /** Simple test for the case with dependencies. */
  @Test
  public void dependentTreeArtifacts() throws Exception {
    SpecialArtifact tree1 = createTreeArtifact("tree1");
    TouchingTestAction action1 = new TouchingTestAction(tree1, "out1", "out2");
    registerAction(action1);

    SpecialArtifact tree2 = createTreeArtifact("tree2");
    CopyTreeAction action2 = new CopyTreeAction(tree1, tree2);
    registerAction(action2);

    TreeArtifactValue result = buildArtifact(tree2);

    assertThat(tree1.getPath().getRelative("out1").exists()).isTrue();
    assertThat(tree1.getPath().getRelative("out2").exists()).isTrue();
    verifyOutputTree(result, tree2, "out1", "out2");
  }

  /** Test for tree artifacts with sub directories. */
  @Test
  public void treeArtifactWithSubDirectory() throws Exception {
    SpecialArtifact parent = createTreeArtifact("parent");
    TouchingTestAction action = new TouchingTestAction(parent, "sub1/file1", "sub2/file2");
    registerAction(action);

    TreeArtifactValue result = buildArtifact(parent);

    verifyOutputTree(result, parent, "sub1/file1", "sub2/file2");
  }

  @Test
  public void inputTreeArtifactMetadataProvider() throws Exception {
    SpecialArtifact treeArtifactInput = createTreeArtifact("tree");
    TouchingTestAction action1 = new TouchingTestAction(treeArtifactInput, "out1", "out2");
    registerAction(action1);

    Artifact normalOutput = createDerivedArtifact("normal/out");
    Action testAction =
        new SimpleTestAction(ImmutableList.of(treeArtifactInput), normalOutput) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            // Check the metadata provider for input TreeFileArtifacts.
            InputMetadataProvider inputMetadataProvider =
                actionExecutionContext.getInputMetadataProvider();
            assertThat(
                    inputMetadataProvider
                        .getInputMetadata(
                            TreeFileArtifact.createTreeOutput(treeArtifactInput, "out1"))
                        .getType()
                        .isFile())
                .isTrue();
            assertThat(
                    inputMetadataProvider
                        .getInputMetadata(
                            TreeFileArtifact.createTreeOutput(treeArtifactInput, "out2"))
                        .getType()
                        .isFile())
                .isTrue();

            // Touch the action output.
            touchFile(normalOutput);
          }
        };

    registerAction(testAction);
    buildArtifact(normalOutput);
  }

  /** Unchanged TreeArtifact outputs should not cause reexecution. */
  @Test
  public void cacheCheckingForTreeArtifactsDoesNotCauseReexecution() throws Exception {
    SpecialArtifact out1 = createTreeArtifact("out1");
    Button button1 = new Button();

    SpecialArtifact out2 = createTreeArtifact("out2");
    Button button2 = new Button();

    TouchingTestAction action1 = new TouchingTestAction(button1, out1, "file_one", "file_two");
    registerAction(action1);

    CopyTreeAction action2 = new CopyTreeAction(button2, out1, out2);
    registerAction(action2);

    button1.pressed = false;
    button2.pressed = false;
    buildArtifact(out2);
    assertThat(button1.pressed).isTrue(); // built
    assertThat(button2.pressed).isTrue(); // built

    button1.pressed = false;
    button2.pressed = false;
    buildArtifact(out2);
    assertThat(button1.pressed).isFalse(); // not built
    assertThat(button2.pressed).isFalse(); // not built
  }

  /** Test rebuilding TreeArtifacts for inputs, outputs, and dependents. Also a test for caching. */
  @Test
  public void transitiveReexecutionForTreeArtifacts() throws Exception {
    Artifact in = createSourceArtifact("input");
    writeFile(in, "input content");

    Button button1 = new Button();
    SpecialArtifact out1 = createTreeArtifact("output1");
    WriteInputToFilesAction action1 =
        new WriteInputToFilesAction(button1, in, out1, "file1", "file2");
    registerAction(action1);

    Button button2 = new Button();
    SpecialArtifact out2 = createTreeArtifact("output2");
    CopyTreeAction action2 = new CopyTreeAction(button2, out1, out2);
    registerAction(action2);

    button1.pressed = false;
    button2.pressed = false;
    buildArtifact(out2);
    assertThat(button1.pressed).isTrue(); // built
    assertThat(button2.pressed).isTrue(); // built

    button1.pressed = false;
    button2.pressed = false;
    writeFile(in, "modified input");
    buildArtifact(out2);
    assertThat(button1.pressed).isTrue(); // built
    assertThat(button2.pressed).isTrue(); // built

    button1.pressed = false;
    button2.pressed = false;
    writeFile(TreeFileArtifact.createTreeOutput(out1, "file1"), "modified output");
    buildArtifact(out2);
    assertThat(button1.pressed).isTrue(); // built
    assertThat(button2.pressed).isFalse(); // should have been cached

    button1.pressed = false;
    button2.pressed = false;
    writeFile(TreeFileArtifact.createTreeOutput(out2, "file1"), "more modified output");
    buildArtifact(out2);
    assertThat(button1.pressed).isFalse(); // not built
    assertThat(button2.pressed).isTrue(); // built
  }

  /** Tests that changing a TreeArtifact directory should cause reexeuction. */
  @Test
  public void directoryContentsCachingForTreeArtifacts() throws Exception {
    Artifact in = createSourceArtifact("input");
    writeFile(in, "input content");

    Button button1 = new Button();
    SpecialArtifact out1 = createTreeArtifact("output1");
    WriteInputToFilesAction action1 =
        new WriteInputToFilesAction(button1, in, out1, "file1", "file2");
    registerAction(action1);

    Button button2 = new Button();
    SpecialArtifact out2 = createTreeArtifact("output2");
    CopyTreeAction action2 = new CopyTreeAction(button2, out1, out2);
    registerAction(action2);

    button1.pressed = false;
    button2.pressed = false;
    buildArtifact(out2);
    // just a smoke test--if these aren't built we have bigger problems!
    assertThat(button1.pressed).isTrue();
    assertThat(button2.pressed).isTrue();

    // Adding a file to a directory should cause reexecution.
    button1.pressed = false;
    button2.pressed = false;
    Path spuriousOutputOne = out1.getPath().getRelative("spuriousOutput");
    touchFile(spuriousOutputOne);
    buildArtifact(out2);
    // Should re-execute, and delete spurious output
    assertThat(spuriousOutputOne.exists()).isFalse();
    assertThat(button1.pressed).isTrue();
    assertThat(button2.pressed).isFalse(); // should have been cached

    button1.pressed = false;
    button2.pressed = false;
    Path spuriousOutputTwo = out2.getPath().getRelative("anotherSpuriousOutput");
    touchFile(spuriousOutputTwo);
    buildArtifact(out2);
    assertThat(spuriousOutputTwo.exists()).isFalse();
    assertThat(button1.pressed).isFalse();
    assertThat(button2.pressed).isTrue();

    // Deleting should cause reexecution.
    button1.pressed = false;
    button2.pressed = false;
    TreeFileArtifact out1File1 = TreeFileArtifact.createTreeOutput(out1, "file1");
    deleteFile(out1File1);
    buildArtifact(out2);
    assertThat(out1File1.getPath().exists()).isTrue();
    assertThat(button1.pressed).isTrue();
    assertThat(button2.pressed).isFalse(); // should have been cached

    button1.pressed = false;
    button2.pressed = false;
    TreeFileArtifact out2File1 = TreeFileArtifact.createTreeOutput(out2, "file1");
    deleteFile(out2File1);
    buildArtifact(out2);
    assertThat(out2File1.getPath().exists()).isTrue();
    assertThat(button1.pressed).isFalse();
    assertThat(button2.pressed).isTrue();
  }

  /** TreeArtifacts don't care about mtime, even when the file is empty. */
  @Test
  public void mTimeForTreeArtifactsDoesNotMatter() throws Exception {
    // For this test, we only touch the input file.
    Artifact in = createSourceArtifact("touchable_input");
    touchFile(in);

    Button button1 = new Button();
    SpecialArtifact out1 = createTreeArtifact("output1");
    WriteInputToFilesAction action1 =
        new WriteInputToFilesAction(button1, in, out1, "file1", "file2");
    registerAction(action1);

    Button button2 = new Button();
    SpecialArtifact out2 = createTreeArtifact("output2");
    CopyTreeAction action2 = new CopyTreeAction(button2, out1, out2);
    registerAction(action2);

    button1.pressed = false;
    button2.pressed = false;
    buildArtifact(out2);
    assertThat(button1.pressed).isTrue(); // built
    assertThat(button2.pressed).isTrue(); // built

    button1.pressed = false;
    button2.pressed = false;
    touchFile(in);
    buildArtifact(out2);
    // mtime does not matter.
    assertThat(button1.pressed).isFalse();
    assertThat(button2.pressed).isFalse();

    // None of the below following should result in anything being built.
    button1.pressed = false;
    button2.pressed = false;
    touchFile(TreeFileArtifact.createTreeOutput(out1, "file1"));
    buildArtifact(out2);
    // Nothing should be built.
    assertThat(button1.pressed).isFalse();
    assertThat(button2.pressed).isFalse();

    button1.pressed = false;
    button2.pressed = false;
    touchFile(TreeFileArtifact.createTreeOutput(out1, "file2"));
    buildArtifact(out2);
    // Nothing should be built.
    assertThat(button1.pressed).isFalse();
    assertThat(button2.pressed).isFalse();
  }

  private static void checkDirectoryPermissions(Path path) throws IOException {
    assertThat(path.isDirectory()).isTrue();
    assertThat(path.isExecutable()).isTrue();
    assertThat(path.isReadable()).isTrue();
    assertThat(path.isWritable()).isFalse();
  }

  private static void checkFilePermissions(Path path) throws IOException {
    assertThat(path.isDirectory()).isFalse();
    assertThat(path.isExecutable()).isTrue();
    assertThat(path.isReadable()).isTrue();
    assertThat(path.isWritable()).isFalse();
  }

  @Test
  public void outputsAreReadOnlyAndExecutable() throws Exception {
    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            writeFile(out.getPath().getChild("three").getChild("four"), "three/four");
          }
        };

    registerAction(action);
    buildArtifact(out);

    checkDirectoryPermissions(out.getPath());
    checkFilePermissions(out.getPath().getChild("one"));
    checkFilePermissions(out.getPath().getChild("two"));
    checkDirectoryPermissions(out.getPath().getChild("three"));
    checkFilePermissions(out.getPath().getChild("three").getChild("four"));
  }

  @Test
  public void doesNotSetPermissionsAfterTraversingSymlink() throws Exception {
    SpecialArtifact out = createTreeArtifact("output");

    Path fileTarget = scratch.file("file");
    writeFile(fileTarget, "file");

    Path dirTarget = scratch.dir("dir");
    Path dirFileTarget = dirTarget.getChild("file");
    writeFile(dirFileTarget, "dir/file");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            out.getPath().getChild("file_link").createSymbolicLink(fileTarget.asFragment());
            out.getPath().getChild("dir_link").createSymbolicLink(dirTarget.asFragment());
          }
        };

    registerAction(action);
    buildArtifact(out);

    assertThat(fileTarget.isWritable()).isTrue();
    assertThat(dirTarget.isWritable()).isTrue();
    assertThat(dirFileTarget.isWritable()).isTrue();
  }

  @Test
  public void symlinkLoopRejected() {
    // Failure expected
    EventCollector eventCollector = new EventCollector(EventKind.ERROR);
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(eventCollector);

    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            writeFile(out.getPath().getRelative("dir/file"), "contents");
            out.getPath().getRelative("dir/sym").createSymbolicLink(PathFragment.create("../dir"));
          }
        };

    registerAction(action);
    assertThrows(BuildFailedException.class, () -> buildArtifact(out));

    ImmutableList<Event> errors = ImmutableList.copyOf(eventCollector);
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).getMessage()).contains("Too many levels of symbolic links");
    assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid");
  }

  @Test
  public void validAbsoluteSymlinkAccepted() throws Exception {
    scratch.overwriteFile("/random/pointer");

    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "/random/pointer");
          }
        };

    registerAction(action);
    buildArtifact(out);
  }

  @Test
  public void danglingAbsoluteSymlinkRejected() {
    // Failure expected
    EventCollector eventCollector = new EventCollector(EventKind.ERROR);
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(eventCollector);

    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "/random/pointer");
          }
        };

    registerAction(action);
    assertThrows(BuildFailedException.class, () -> buildArtifact(out));

    ImmutableList<Event> errors = ImmutableList.copyOf(eventCollector);
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link");
    assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid");
  }

  @Test
  public void validRelativeSymlinkAccepted() throws Exception {
    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "../one");
          }
        };

    registerAction(action);
    buildArtifact(out);
  }

  @Test
  public void danglingRelativeSymlinkRejected() {
    // Failure expected
    EventCollector eventCollector = new EventCollector(EventKind.ERROR);
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(eventCollector);

    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "../invalid");
          }
        };

    registerAction(action);
    assertThrows(BuildFailedException.class, () -> buildArtifact(out));

    ImmutableList<Event> errors = ImmutableList.copyOf(eventCollector);
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link");
    assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid");
  }

  @Test
  public void validRelativeSymlinkToOutsideOfTreeArtifactAccepted() throws Exception {
    SpecialArtifact out = createTreeArtifact("output");

    scratch.file(out.getPath().getRelative("../some/file").getPathString());

    TestAction action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "../../some/file");
          }
        };

    registerAction(action);
    buildArtifact(out);
  }

  @Test
  public void danglingRelativeSymlinkOutsideOfTreeArtifactRejected() {
    // Failure expected
    EventCollector eventCollector = new EventCollector(EventKind.ERROR);
    reporter.removeHandler(failFastHandler);
    reporter.addHandler(eventCollector);

    SpecialArtifact out = createTreeArtifact("output");

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            writeFile(out.getPath().getChild("one"), "one");
            writeFile(out.getPath().getChild("two"), "two");
            FileSystemUtils.ensureSymbolicLink(
                out.getPath().getChild("links").getChild("link"), "../../some/file");
          }
        };

    registerAction(action);
    assertThrows(BuildFailedException.class, () -> buildArtifact(out));

    ImmutableList<Event> errors = ImmutableList.copyOf(eventCollector);
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link");
    assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid");
  }

  @Test
  public void remoteDirectoryInjection() throws Exception {
    SpecialArtifact out = createTreeArtifact("output");
    FileArtifactValue remoteFile1 =
        FileArtifactValue.createForRemoteFile(
            Hashing.sha256().hashString("one", UTF_8).asBytes(),
            /* size= */ 3,
            /* locationIndex= */ 1);
    FileArtifactValue remoteFile2 =
        FileArtifactValue.createForRemoteFile(
            Hashing.sha256().hashString("two", UTF_8).asBytes(),
            /* size= */ 3,
            /* locationIndex= */ 2);

    Action action =
        new SimpleTestAction(out) {
          @Override
          void run(ActionExecutionContext actionExecutionContext) throws IOException {
            TreeFileArtifact child1 = TreeFileArtifact.createTreeOutput(out, "one");
            TreeFileArtifact child2 = TreeFileArtifact.createTreeOutput(out, "two");
            writeFile(child1, "one");
            writeFile(child2, "two");

            actionExecutionContext
                .getOutputMetadataStore()
                .injectTree(
                    out,
                    TreeArtifactValue.newBuilder(out)
                        .putChild(child1, remoteFile1)
                        .putChild(child2, remoteFile2)
                        .build());
          }
        };

    registerAction(action);
    TreeArtifactValue result = buildArtifact(out);

    assertThat(result.getChildValues())
        .containsExactly(
            TreeFileArtifact.createTreeOutput(out, "one"),
            remoteFile1,
            TreeFileArtifact.createTreeOutput(out, "two"),
            remoteFile2);
  }

  @Test
  public void expandedActionsBuildInActionTemplate() throws Exception {
    // artifact1 is a tree artifact generated by a TouchingTestAction.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new TouchingTestAction(artifact1, "file1", "file2"));

    // artifact2 is a tree artifact generated by an action template.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    // We mock out the action template function to expand into two actions that just touch the
    // output files.
    ActionTemplateExpansionKey secondOwner = ActionTemplateExpansionValue.key(ACTION_LOOKUP_KEY, 1);
    TreeFileArtifact expectedExpansionOutput1 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner);
    TreeFileArtifact expectedExpansionOutput2 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner);
    Action expandedAction1 =
        new DummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "file1"), expectedExpansionOutput1);
    Action expandedAction2 =
        new DummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "file2"), expectedExpansionOutput2);

    actionTemplateExpansionFunction =
        new DummyActionTemplateExpansionFunction(
            actionKeyContext, ImmutableList.of(expandedAction1, expandedAction2));

    TreeArtifactValue result = buildArtifact(artifact2);

    assertThat(result.getChildren())
        .containsExactly(expectedExpansionOutput1, expectedExpansionOutput2);
  }

  @Test
  public void expandedActionDoesNotGenerateOutputInActionTemplate() {
    // expect errors
    reporter.removeHandler(failFastHandler);

    // artifact1 is a tree artifact generated by a TouchingTestAction.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new TouchingTestAction(artifact1, "child1", "child2"));

    // artifact2 is a tree artifact generated by an action template.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    // We mock out the action template function to expand into two actions:
    // One Action that touches the output file.
    // The other action that does not generate the output file.
    ActionTemplateExpansionKey secondOwner = ActionTemplateExpansionKey.of(ACTION_LOOKUP_KEY, 1);
    TreeFileArtifact expectedExpansionOutput1 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner);
    TreeFileArtifact expectedExpansionOutput2 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner);
    Action generateOutputAction =
        new DummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1);
    Action noGenerateOutputAction =
        new NoOpDummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2);

    actionTemplateExpansionFunction =
        new DummyActionTemplateExpansionFunction(
            actionKeyContext, ImmutableList.of(generateOutputAction, noGenerateOutputAction));

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildArtifact(artifact2));
    assertThat(e).hasMessageThat().contains("not all outputs were created or valid");
  }

  @Test
  public void oneExpandedActionThrowsInActionTemplate() {
    // expect errors
    reporter.removeHandler(failFastHandler);

    // artifact1 is a tree artifact generated by a TouchingTestAction.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new TouchingTestAction(artifact1, "child1", "child2"));

    // artifact2 is a tree artifact generated by an action template.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    // We mock out the action template function to expand into two actions:
    // One Action that touches the output file.
    // The other action that just throws when executed.
    ActionTemplateExpansionKey secondOwner = ActionTemplateExpansionKey.of(ACTION_LOOKUP_KEY, 1);
    TreeFileArtifact expectedExpansionOutput1 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner);
    TreeFileArtifact expectedExpansionOutput2 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner);
    Action generateOutputAction =
        new DummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1);
    Action throwingAction =
        new ThrowingDummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2);

    actionTemplateExpansionFunction =
        new DummyActionTemplateExpansionFunction(
            actionKeyContext, ImmutableList.of(generateOutputAction, throwingAction));

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildArtifact(artifact2));
    assertThat(e).hasMessageThat().contains("Throwing dummy action");
  }

  @Test
  public void allExpandedActionsThrowInActionTemplate() {
    // expect errors
    reporter.removeHandler(failFastHandler);

    // artifact1 is a tree artifact generated by a TouchingTestAction.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new TouchingTestAction(artifact1, "child1", "child2"));

    // artifact2 is a tree artifact generated by an action template.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    // We mock out the action template function to expand into two actions that throw when executed.
    ActionTemplateExpansionKey secondOwner = ActionTemplateExpansionKey.of(ACTION_LOOKUP_KEY, 1);
    TreeFileArtifact expectedExpansionOutput1 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner);
    TreeFileArtifact expectedExpansionOutput2 =
        TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner);
    Action throwingAction =
        new ThrowingDummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1);
    Action anotherThrowingAction =
        new ThrowingDummyAction(
            TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2);

    actionTemplateExpansionFunction =
        new DummyActionTemplateExpansionFunction(
            actionKeyContext, ImmutableList.of(throwingAction, anotherThrowingAction));

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildArtifact(artifact2));
    assertThat(e).hasMessageThat().contains("Throwing dummy action");
  }

  @Test
  public void inputTreeArtifactCreationFailedInActionTemplate() {
    // expect errors
    reporter.removeHandler(failFastHandler);

    // artifact1 is created by a action that throws.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new ThrowingDummyAction(artifact1));

    // artifact2 is a tree artifact generated by an action template.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildArtifact(artifact2));
    assertThat(e).hasMessageThat().contains("Throwing dummy action");
  }

  @Test
  public void emptyInputAndOutputTreeArtifactInActionTemplate() throws Exception {
    // artifact1 is an empty tree artifact which is generated by a single no-op dummy action.
    SpecialArtifact artifact1 = createTreeArtifact("treeArtifact1");
    registerAction(new NoOpDummyAction(artifact1));

    // artifact2 is a tree artifact generated by an action template that takes artifact1 as input.
    SpecialArtifact artifact2 = createTreeArtifact("treeArtifact2");
    SpawnActionTemplate actionTemplate =
        ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2);
    registerAction(actionTemplate);

    buildArtifact(artifact2);

    assertThat(artifact1.getPath().exists()).isTrue();
    assertThat(artifact1.getPath().getDirectoryEntries()).isEmpty();
    assertThat(artifact2.getPath().exists()).isTrue();
    assertThat(artifact2.getPath().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void treeArtifactWithSymlinkToFile() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("tree");
    registerAction(
        new SimpleTestAction(/* output= */ treeArtifact) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            touchFile(treeArtifact.getPath().getRelative("subdir/file"));
            treeArtifact
                .getPath()
                .getRelative("link")
                .createSymbolicLink(PathFragment.create("subdir/file"));
          }
        });

    TreeArtifactValue tree = buildArtifact(treeArtifact);

    assertThat(tree.getChildren())
        .containsExactly(
            TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/file"),
            TreeFileArtifact.createTreeOutput(treeArtifact, "link"));
  }

  @Test
  public void treeArtifactWithSymlinkToDirectory() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("tree");
    registerAction(
        new SimpleTestAction(/* output= */ treeArtifact) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            touchFile(treeArtifact.getPath().getRelative("subdir/file"));
            treeArtifact
                .getPath()
                .getRelative("link")
                .createSymbolicLink(PathFragment.create("subdir"));
          }
        });

    TreeArtifactValue tree = buildArtifact(treeArtifact);

    assertThat(tree.getChildren())
        .containsExactly(
            TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/file"),
            TreeFileArtifact.createTreeOutput(treeArtifact, "link/file"));
  }

  @Test
  public void symlinkActionToTreeArtifact() throws Exception {
    SpecialArtifact tree1 = createTreeArtifact("tree1");
    registerAction(
        new SimpleTestAction(/* output= */ tree1) {
          @Override
          void run(ActionExecutionContext context) throws IOException {
            touchFile(tree1.getPath().getChild("file"));
          }
        });

    SpecialArtifact tree2 = createTreeArtifact("tree2");
    registerAction(
        SymlinkAction.toArtifact(
            ActionsTestUtil.NULL_ACTION_OWNER, tree1, tree2, "Symlinking tree2 -> tree1"));

    // The SymlinkAction should produce a TreeArtifactValue with tree2 as the parent.
    TreeArtifactValue tree2Value = buildArtifact(tree2);
    assertThat(tree2Value.getChildren())
        .containsExactly(TreeFileArtifact.createTreeOutput(tree2, "file"));
  }

  private abstract static class SimpleTestAction extends TestAction {
    private final Button button;

    SimpleTestAction(Artifact output) {
      this(/* inputs= */ ImmutableList.of(), output);
    }

    SimpleTestAction(Iterable<Artifact> inputs, Artifact output) {
      this(new Button(), inputs, output);
    }

    SimpleTestAction(Button button, Iterable<Artifact> inputs, Artifact output) {
      super(NO_EFFECT, NestedSetBuilder.wrap(Order.STABLE_ORDER, inputs), ImmutableSet.of(output));
      this.button = button;
    }

    @Override
    public final ActionResult execute(ActionExecutionContext context)
        throws ActionExecutionException {
      button.pressed = true;
      try {
        run(context);
      } catch (IOException e) {
        throw new ActionExecutionException(
            e, this, /*catastrophe=*/ false, CrashFailureDetails.detailedExitCodeForThrowable(e));
      }
      return ActionResult.EMPTY;
    }

    abstract void run(ActionExecutionContext context) throws IOException;
  }

  /** An action that touches some output TreeFileArtifacts. Takes no inputs. */
  private static final class TouchingTestAction extends SimpleTestAction {
    private final ImmutableList<String> outputFiles;

    TouchingTestAction(SpecialArtifact output, String... outputFiles) {
      this(new Button(), output, outputFiles);
    }

    TouchingTestAction(Button button, SpecialArtifact output, String... outputFiles) {
      super(button, /*inputs=*/ ImmutableList.of(), output);
      this.outputFiles = ImmutableList.copyOf(outputFiles);
    }

    @Override
    void run(ActionExecutionContext context) throws IOException {
      for (String file : outputFiles) {
        touchFile(getPrimaryOutput().getPath().getRelative(file));
      }
    }
  }

  /** Takes an input file and populates several copies inside a TreeArtifact. */
  private static final class WriteInputToFilesAction extends SimpleTestAction {
    private final ImmutableList<String> outputFiles;

    WriteInputToFilesAction(
        Button button, Artifact input, SpecialArtifact output, String... outputFiles) {
      super(button, ImmutableList.of(input), output);
      this.outputFiles = ImmutableList.copyOf(outputFiles);
    }

    @Override
    void run(ActionExecutionContext actionExecutionContext) throws IOException {
      for (String file : outputFiles) {
        Path newOutput = getPrimaryOutput().getPath().getRelative(file);
        newOutput.createDirectoryAndParents();
        FileSystemUtils.copyFile(getPrimaryInput().getPath(), newOutput);
      }
    }
  }

  /** Copies the given TreeFileArtifact inputs to the given outputs, in respective order. */
  private static final class CopyTreeAction extends SimpleTestAction {

    CopyTreeAction(SpecialArtifact input, SpecialArtifact output) {
      this(new Button(), input, output);
    }

    CopyTreeAction(Button button, SpecialArtifact input, SpecialArtifact output) {
      super(button, ImmutableList.of(input), output);
    }

    @Override
    void run(ActionExecutionContext context) throws IOException {
      for (Artifact child :
          context.getArtifactExpander().tryExpandTreeArtifact(getPrimaryInput())) {
        Path newOutput = getPrimaryOutput().getPath().getRelative(child.getParentRelativePath());
        newOutput.createDirectoryAndParents();
        FileSystemUtils.copyFile(child.getPath(), newOutput);
      }
    }
  }

  private SpecialArtifact createTreeArtifact(String name) {
    FileSystem fs = scratch.getFileSystem();
    Path execRoot =
        fs.getPath(TestUtils.tmpDir()).getRelative("execroot").getRelative("default-exec-root");
    PathFragment execPath = PathFragment.create("out").getRelative(name);
    return SpecialArtifact.create(
        ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out"),
        execPath,
        ACTION_LOOKUP_KEY,
        SpecialArtifactType.TREE);
  }

  private TreeArtifactValue buildArtifact(SpecialArtifact treeArtifact) throws Exception {
    Preconditions.checkArgument(treeArtifact.isTreeArtifact(), treeArtifact);
    BuilderWithResult builder = cachingBuilder();
    buildArtifacts(builder, treeArtifact);
    return (TreeArtifactValue) builder.getLatestResult().get(treeArtifact);
  }

  private void buildArtifact(Artifact normalArtifact) throws Exception {
    buildArtifacts(cachingBuilder(), normalArtifact);
  }

  private static void verifyOutputTree(
      TreeArtifactValue result, SpecialArtifact parent, String... expectedChildPaths) {
    Preconditions.checkArgument(parent.isTreeArtifact(), parent);
    Set<TreeFileArtifact> expectedChildren =
        Arrays.stream(expectedChildPaths)
            .map(path -> TreeFileArtifact.createTreeOutput(parent, path))
            .collect(toImmutableSet());
    for (TreeFileArtifact child : expectedChildren) {
      assertWithMessage(child + " does not exist").that(child.getPath().exists()).isTrue();
    }
    assertThat(result.getChildren()).isEqualTo(expectedChildren);
  }

  private static void writeFile(Path path, String contents) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    // sometimes we write read-only files
    if (path.exists()) {
      path.setWritable(true);
    }
    FileSystemUtils.writeContentAsLatin1(path, contents);
  }

  private static void writeFile(Artifact file, String contents) throws IOException {
    writeFile(file.getPath(), contents);
  }

  private static void touchFile(Path path) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    path.getParentDirectory().setWritable(true);
    FileSystemUtils.touchFile(path);
  }

  private static void touchFile(Artifact file) throws IOException {
    touchFile(file.getPath());
  }

  private static void deleteFile(Artifact file) throws IOException {
    Path path = file.getPath();
    // sometimes we write read-only files
    path.setWritable(true);
    // work around the sticky bit (this might depend on the behavior of the OS?)
    path.getParentDirectory().setWritable(true);
    path.delete();
  }

  /** A dummy action template expansion function that just returns the injected actions. */
  private static final class DummyActionTemplateExpansionFunction implements SkyFunction {
    private final ActionKeyContext actionKeyContext;
    private final ImmutableList<ActionAnalysisMetadata> actions;

    DummyActionTemplateExpansionFunction(
        ActionKeyContext actionKeyContext, ImmutableList<ActionAnalysisMetadata> actions) {
      this.actionKeyContext = actionKeyContext;
      this.actions = actions;
    }

    @Override
    public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
      try {
        Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
            actionKeyContext, actions, (ActionLookupKey) skyKey);
      } catch (ActionConflictException | Actions.ArtifactGeneratedByOtherRuleException e) {
        throw new IllegalStateException(e);
      }
      return new ActionTemplateExpansionValue(actions);
    }
  }

  /** No-op action that does not generate the action outputs. */
  private static final class NoOpDummyAction extends SimpleTestAction {

    NoOpDummyAction(Artifact output) {
      super(/*inputs=*/ ImmutableList.of(), output);
    }

    NoOpDummyAction(Artifact input, Artifact output) {
      super(ImmutableList.of(input), output);
    }

    /** Does nothing. */
    @Override
    void run(ActionExecutionContext actionExecutionContext) {}
  }

  /** No-op action that throws when executed. */
  private static final class ThrowingDummyAction extends TestAction {

    ThrowingDummyAction(Artifact output) {
      super(NO_EFFECT, NestedSetBuilder.emptySet(Order.STABLE_ORDER), ImmutableSet.of(output));
    }

    ThrowingDummyAction(Artifact input, Artifact output) {
      super(NO_EFFECT, NestedSetBuilder.create(Order.STABLE_ORDER, input), ImmutableSet.of(output));
    }

    /** Unconditionally throws. */
    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
      DetailedExitCode code =
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                  .build());
      throw new ActionExecutionException(
          "Throwing dummy action", this, /*catastrophe=*/ true, code);
    }
  }
}
