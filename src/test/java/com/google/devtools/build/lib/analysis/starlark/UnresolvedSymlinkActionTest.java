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
package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationDepsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link UnresolvedSymlinkAction}. */
@RunWith(JUnit4.class)
public class UnresolvedSymlinkActionTest extends BuildViewTestCase {

  private Path output;
  private Artifact.DerivedArtifact outputArtifact;
  private UnresolvedSymlinkAction action;

  @Before
  public final void setUp() throws Exception {
    ArtifactRoot binDir = targetConfig.getBinDirectory(RepositoryName.MAIN);
    outputArtifact =
        SpecialArtifact.create(
            binDir,
            binDir.getExecPath().getRelative("symlink"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.UNRESOLVED_SYMLINK);
    outputArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    output = outputArtifact.getPath();
    output.getParentDirectory().createDirectoryAndParents();
    action =
        UnresolvedSymlinkAction.create(
            NULL_ACTION_OWNER,
            outputArtifact,
            "../some/relative/path",
            "Creating unresolved symlink");
  }

  @Test
  public void testInputsAreEmpty() {
    assertThat(action.getInputs().toList()).isEmpty();
  }

  @Test
  public void testOutputArtifactIsOutput() {
    assertThat(action.getOutputs()).containsExactly(outputArtifact);
  }

  @Test
  public void testTargetAffectsKey() {
    UnresolvedSymlinkAction action1 =
        UnresolvedSymlinkAction.create(
            NULL_ACTION_OWNER, outputArtifact, "some/path", "Creating unresolved symlink");
    UnresolvedSymlinkAction action2 =
        UnresolvedSymlinkAction.create(
            NULL_ACTION_OWNER, outputArtifact, "some/other/path", "Creating unresolved symlink");

    assertThat(computeKey(action1)).isNotEqualTo(computeKey(action2));
  }

  @Test
  public void testSymlink() throws Exception {
    Executor executor = new TestExecutorBuilder(fileSystem, directories, null).build();
    ActionResult actionResult =
        action.execute(
            new ActionExecutionContext(
                executor,
                /* actionInputFileCache= */ null,
                ActionInputPrefetcher.NONE,
                actionKeyContext,
                mock(OutputMetadataStore.class),
                /* rewindingEnabled= */ false,
                LostInputsCheck.NONE,
                /* fileOutErr= */ null,
                new StoredEventHandler(),
                /* clientEnv= */ ImmutableMap.of(),
                /* topLevelFilesets= */ ImmutableMap.of(),
                /* artifactExpander= */ null,
                /* actionFileSystem= */ null,
                /* skyframeDepsResult= */ null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE));
    assertThat(actionResult.spawnResults()).isEmpty();
    assertThat(output.isSymbolicLink()).isTrue();
    assertThat(output.readSymbolicLink()).isEqualTo(PathFragment.create("../some/relative/path"));
    assertThat(action.getPrimaryInput()).isNull();
    assertThat(action.getPrimaryOutput()).isEqualTo(outputArtifact);
  }

  @Test
  public void testCodec() throws Exception {
    new SerializationTester(action)
        .addDependency(FileSystem.class, scratch.getFileSystem())
        .addDependency(Root.RootCodecDependencies.class, new Root.RootCodecDependencies(root))
        .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
        .setVerificationFunction(
            (in, out) -> {
              UnresolvedSymlinkAction inAction = (UnresolvedSymlinkAction) in;
              UnresolvedSymlinkAction outAction = (UnresolvedSymlinkAction) out;
              assertThat(inAction.getPrimaryInput()).isEqualTo(outAction.getPrimaryInput());
              assertThat(inAction.getPrimaryOutput().getFilename())
                  .isEqualTo(outAction.getPrimaryOutput().getFilename());
              assertThat(inAction.getOwner()).isEqualTo(outAction.getOwner());
              assertThat(inAction.getProgressMessage()).isEqualTo(outAction.getProgressMessage());
            })
        .runTests();
  }

  private String computeKey(UnresolvedSymlinkAction action) {
    Fingerprint fp = new Fingerprint();
    action.computeKey(actionKeyContext, /*artifactExpander=*/ null, fp);
    return fp.hexDigestAndReset();
  }
}
