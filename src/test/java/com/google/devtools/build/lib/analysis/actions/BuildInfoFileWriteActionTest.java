// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.util.ActionTester;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.BazelModuleKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.skyframe.WorkspaceStatusValue;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link BuildInfoFileWriteAction}. */
@RunWith(JUnit4.class)
public class BuildInfoFileWriteActionTest extends BuildViewTestCase {

  private Artifact outputFile;
  private Path outputPath;
  private ActionExecutionContext context;
  private Executor executor;

  private static Object exec(String... lines) throws Exception {
    try (Mutability mutability = Mutability.create("test")) {
      StarlarkThread thread = StarlarkThread.createTransient(mutability, StarlarkSemantics.DEFAULT);
      return Starlark.execFile(
          ParserInput.fromLines(lines),
          FileOptions.DEFAULT,
          Module.withPredeclaredAndData(
              StarlarkSemantics.DEFAULT,
              ImmutableMap.of(),
              BazelModuleContext.create(
                  BazelModuleKey.createFakeModuleKeyForTesting(
                      Label.parseCanonicalUnchecked("//test:label")),
                  RepositoryMapping.ALWAYS_FALLBACK,
                  "test/label.bzl",
                  /* loads= */ ImmutableList.of(),
                  /* bzlTransitiveDigest= */ new byte[0])),
          thread);
    }
  }

  @Before
  public final void createOutputFile() throws Exception {
    outputFile = getBinArtifactWithNoOwner("output.txt");
    outputPath = outputFile.getPath();
    outputPath.getParentDirectory().createDirectoryAndParents();
  }

  @Before
  public final void createExecutorAndContext() throws Exception {
    BinTools binTools = BinTools.forUnitTesting(directories, analysisMock.getEmbeddedTools());
    executor = new TestExecutorBuilder(fileSystem, directories, binTools).build();
    context =
        new ActionExecutionContext(
            executor,
            /* inputMetadataProvider= */ null,
            ActionInputPrefetcher.NONE,
            actionKeyContext,
            /* outputMetadataStore= */ null,
            /* rewindingEnabled= */ false,
            LostInputsCheck.NONE,
            new FileOutErr(),
            new StoredEventHandler(),
            /* clientEnv= */ ImmutableMap.of(),
            /* topLevelFilesets= */ ImmutableMap.of(),
            /* artifactExpander= */ null,
            /* actionFileSystem= */ null,
            /* skyframeDepsResult= */ null,
            DiscoveredModulesPruner.DEFAULT,
            SyscallCache.NO_CACHE,
            ThreadStateReceiver.NULL_INSTANCE);
  }

  @Test
  public void execute_writesToOutputFile() throws Exception {
    scratch.file(
        "input.txt", //
        "name test_name",
        "client test_client");
    scratch.file(
        "template.txt", //
        "#define NAME {NAME}",
        "#define CLIENT {CLIENT}");
    Object starlarkFuncObject =
        exec(
            "def t(d):",
            " r = {}",
            " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
            " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
            " return r",
            "t");
    String expected = "#define NAME test_name_foo\n" + "#define CLIENT test_client_c\n";

    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);
    ActionResult actionResult = action.execute(context);
    String actual = new String(FileSystemUtils.readContentAsLatin1(outputPath));

    assertThat(actionResult.spawnResults()).isEmpty();
    assertThat(action.getOutputs()).containsExactly(outputFile);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void keyMissing_templatePartiallyExpanded() throws Exception {
    scratch.file(
        "input.txt", //
        "name test_name",
        "client test_client");
    scratch.file(
        "template.txt", //
        "#define NAME {NAME}",
        "#define CLIENT {CLIENT_MISSING}");
    Object starlarkFuncObject =
        exec(
            "def t(d):",
            " r = {}",
            " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
            " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
            " return r",
            "t");
    String expected = "#define NAME test_name_foo\n" + "#define CLIENT {CLIENT_MISSING}\n";

    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);
    ActionResult actionResult = action.execute(context);
    String actual = new String(FileSystemUtils.readContentAsLatin1(outputPath));

    assertThat(actionResult.spawnResults()).isEmpty();
    assertThat(action.getOutputs()).containsExactly(outputFile);
    assertThat(actual).isEqualTo(expected);
  }

  private enum BuildInfoFileWriteActionAttributes {
    INPUT,
    TEMPLATE,
    IS_VOLATILE,
  }

  @Test
  public void actionInputsVary_checkComputeKeyResults() throws Exception {
    scratch.file("input1.txt", "");
    scratch.file("template1.txt", "");
    scratch.file("input2.txt", "");
    scratch.file("template2.txt", "");
    Object starlarkFuncObject =
        exec(
            "def t(d):",
            " for i in range(1, 10):",
            "   for j in range (1, 10):",
            "       a = 5",
            " return {}",
            "t");

    ActionTester.runTest(
        BuildInfoFileWriteActionAttributes.class,
        new ActionTester.ActionCombinationFactory<BuildInfoFileWriteActionAttributes>() {
          @Override
          public Action generate(
              ImmutableSet<BuildInfoFileWriteActionAttributes> attributesToFlip) {
            return new BuildInfoFileWriteAction(
                NULL_ACTION_OWNER,
                attributesToFlip.contains(BuildInfoFileWriteActionAttributes.INPUT)
                    ? getSourceArtifact("input1.txt", WorkspaceStatusValue.BUILD_INFO_KEY)
                    : getSourceArtifact("input2.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
                outputFile,
                (StarlarkFunction) starlarkFuncObject,
                attributesToFlip.contains(BuildInfoFileWriteActionAttributes.TEMPLATE)
                    ? getSourceArtifact("template1.txt")
                    : getSourceArtifact("template2.txt"),
                attributesToFlip.contains(BuildInfoFileWriteActionAttributes.IS_VOLATILE),
                StarlarkSemantics.DEFAULT);
          }
        },
        actionKeyContext);
  }

  @Test
  public void wrongKeyRead_exceptionThrown() throws Exception {
    scratch.file(
        "input.txt", //
        "name test_name",
        "extra_client test_client");
    scratch.file(
        "template.txt", //
        "#define NAME {NAME}",
        "#define CLIENT {CLIENT}");
    Object starlarkFuncObject =
        exec(
            "def t(d):",
            " r = {}",
            " r[\"{NAME}\"] = d[\"name\"] + \"_foo\"",
            " r[\"{CLIENT}\"] = d[\"client\"] + \"_c\"",
            " return r",
            "t");
    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);

    assertThat(assertThrows(ActionExecutionException.class, () -> action.execute(context)))
        .hasMessageThat()
        .contains("key \"client\" not found in dictionary");
  }

  @Test
  public void callbackReturnValueInvalidType_exceptionThrown() throws Exception {
    scratch.file("input.txt", "");
    scratch.file("template.txt", "");
    Object starlarkFuncObject = exec("def t(d):", " return [2, 5]", "t");
    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);

    assertThat(assertThrows(ActionExecutionException.class, () -> action.execute(context)))
        .hasMessageThat()
        .contains(
            "BuildInfo translation callback function is expected to return dict of strings to"
                + " strings, could not convert return value to Java type: got list for"
                + " 'substitution_dict', want dict");
  }

  @Test
  public void callbackReturnDictContainsInvalidType_exceptionThrown() throws Exception {
    scratch.file("input.txt", "");
    scratch.file("template.txt", "");
    Object starlarkFuncObject = exec("def t(d):", " return {'a': 'b', 'c': 5}", "t");
    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);

    assertThat(assertThrows(ActionExecutionException.class, () -> action.execute(context)))
        .hasMessageThat()
        .contains(
            "could not convert return value to Java type: got dict<string, int> for"
                + " 'substitution_dict', want dict<string, string>");
  }

  @Test
  public void callbackFails_exceptionThrown() throws Exception {
    scratch.file("input.txt", "");
    scratch.file("template.txt", "");
    Object starlarkFuncObject = exec("def t(d):", " fail('starlark error')", "t");
    AbstractAction action =
        new BuildInfoFileWriteAction(
            NULL_ACTION_OWNER,
            getSourceArtifact("input.txt", WorkspaceStatusValue.BUILD_INFO_KEY),
            outputFile,
            (StarlarkFunction) starlarkFuncObject,
            getSourceArtifact("template.txt"),
            false,
            StarlarkSemantics.DEFAULT);

    assertThat(assertThrows(ActionExecutionException.class, () -> action.execute(context)))
        .hasMessageThat()
        .contains("Error in fail: starlark error");
  }

  @Test
  public void wrongArtifactOwnerOnInputSourceFile_exceptionThrown() throws Exception {
    scratch.file("input.txt", "");
    scratch.file("template.txt", "");
    Object starlarkFuncObject = exec("def t(d):", " pass", "t");

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new BuildInfoFileWriteAction(
                        NULL_ACTION_OWNER,
                        // Set no artifact owner.
                        getSourceArtifact("input.txt"),
                        outputFile,
                        (StarlarkFunction) starlarkFuncObject,
                        getSourceArtifact("template.txt"),
                        false,
                        StarlarkSemantics.DEFAULT)))
        .hasMessageThat()
        .contains(
            "input artifact of BuildInfoFileWriteAction must be one of workspace status artifacts:"
                + " ctx.info_file or ctx.version_file");
  }
}
