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
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.util.ActionTester;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.util.Collection;
import org.junit.Before;

/** Test cases for {@link FileWriteAction}. */
public abstract class FileWriteActionTestCase extends BuildViewTestCase {

  private Action action;
  private Artifact outputArtifact;
  private Path output;
  private Executor executor;
  protected ActionExecutionContext context;

  @Before
  public final void createAction() throws Exception {
    outputArtifact = getBinArtifactWithNoOwner("destination.txt");
    output = outputArtifact.getPath();
    output.getParentDirectory().createDirectoryAndParents();
    action = createAction(NULL_ACTION_OWNER, outputArtifact, "Hello World", false);
  }

  protected abstract Action createAction(
      ActionOwner actionOwner, Artifact outputArtifact, String data, boolean makeExecutable);

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

  protected void checkNoInputsByDefault() {
    assertThat(action.getInputs().toList()).isEmpty();
    assertThat(action.getPrimaryInput()).isNull();
  }

  protected void checkDestinationArtifactIsOutput() {
    Collection<Artifact> outputs = action.getOutputs();
    assertThat(Sets.newHashSet(outputs)).isEqualTo(Sets.newHashSet(outputArtifact));
    assertThat(action.getPrimaryOutput()).isEqualTo(outputArtifact);
  }

  protected void checkCanWriteNonExecutableFile() throws Exception {
    ActionResult actionResult = action.execute(context);
    assertThat(actionResult.spawnResults()).isEmpty();
    String content = new String(FileSystemUtils.readContentAsLatin1(output));
    assertThat(content).isEqualTo("Hello World");
    assertThat(output.isExecutable()).isFalse();
  }

  protected void checkCanWriteExecutableFile() throws Exception {
    Artifact outputArtifact = getBinArtifactWithNoOwner("hello");
    Path output = outputArtifact.getPath();
    Action action = createAction(NULL_ACTION_OWNER, outputArtifact, "echo 'Hello World'", true);
    ActionResult actionResult = action.execute(context);
    assertThat(actionResult.spawnResults()).isEmpty();
    String content = new String(FileSystemUtils.readContentAsLatin1(output));
    assertThat(content).isEqualTo("echo 'Hello World'");
    assertThat(output.isExecutable()).isTrue();
  }

  private enum KeyAttributes {
    DATA,
    MAKE_EXECUTABLE
  }

  protected void checkComputesConsistentKeys() throws Exception {
    ActionTester.runTest(
        KeyAttributes.class,
        new ActionTester.ActionCombinationFactory<KeyAttributes>() {
          @Override
          public Action generate(ImmutableSet<KeyAttributes> attributesToFlip) {
            return createAction(
                NULL_ACTION_OWNER,
                outputArtifact,
                attributesToFlip.contains(KeyAttributes.DATA) ? "0" : "1",
                attributesToFlip.contains(KeyAttributes.MAKE_EXECUTABLE));
          }
        },
        actionKeyContext);
  }
}
