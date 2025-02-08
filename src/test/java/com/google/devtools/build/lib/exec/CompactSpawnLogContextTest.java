// Copyright 2023 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.PRODUCT_NAME;
import static com.google.devtools.build.lib.testutil.TestConstants.WORKSPACE_NAME;

import com.github.luben.zstd.ZstdInputStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.common.options.Options;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CompactSpawnLogContext}. */
@RunWith(TestParameterInjector.class)
public final class CompactSpawnLogContextTest extends SpawnLogContextTestBase {
  private final Path logPath = fs.getPath("/log");

  @Test
  public void testTransitiveNestedSet(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact file1 = ActionsTestUtil.createArtifact(rootDir, "file1");
    Artifact file2 = ActionsTestUtil.createArtifact(rootDir, "file2");
    Artifact file3 = ActionsTestUtil.createArtifact(rootDir, "file3");

    writeFile(file1, "abc");
    writeFile(file2, "def");
    writeFile(file3, "ghi");

    NestedSet<ActionInput> inputs =
        NestedSetBuilder.<ActionInput>stableOrder()
            .add(file1)
            .addTransitive(
                NestedSetBuilder.<ActionInput>stableOrder().add(file2).add(file3).build())
            .build();

    assertThat(inputs.getLeaves()).hasSize(1);
    assertThat(inputs.getNonLeaves()).hasSize(1);

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(inputs);
    if (inputsMode.isTool()) {
      spawn = spawn.withTools(inputs);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(file1, file2, file3),
        createInputMap(file1, file2, file3),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath("file1")
                    .setDigest(getDigest("abc"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath("file2")
                    .setDigest(getDigest("def"))
                    .setIsTool(inputsMode.isTool()))
            .addInputs(
                File.newBuilder()
                    .setPath("file3")
                    .setDigest(getDigest("ghi"))
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testSymlinkAction() throws IOException, InterruptedException {
    Artifact source = ActionsTestUtil.createArtifact(rootDir, "source");
    Artifact target = ActionsTestUtil.createArtifact(rootDir, "target");
    ActionOwner owner =
        ActionOwner.createDummy(
            Label.parseCanonicalUnchecked("//pkg:symlink"),
            new Location("dummy-file", 0, 0),
            "some_rule",
            "configurationMnemonic",
            /* configurationChecksum= */ "configurationChecksum",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ null,
            /* aspectDescriptors= */ ImmutableList.of(),
            /* execProperties= */ ImmutableMap.of());
    SymlinkAction symlinkAction =
        SymlinkAction.toArtifact(owner, source, target, "Creating symlink");

    SpawnLogContext context = createSpawnLogContext();
    context.logSymlinkAction(symlinkAction);

    var entries = closeAndReadCompactLog(context);
    assertThat(entries)
        .containsExactly(
            Protos.ExecLogEntry.newBuilder()
                .setInvocation(
                    Protos.ExecLogEntry.Invocation.newBuilder()
                        .setHashFunctionName("SHA-256")
                        .setWorkspaceRunfilesDirectory(TestConstants.WORKSPACE_NAME)
                        .setSiblingRepositoryLayout(siblingRepositoryLayout)
                        .setId("00000000-0000-0000-0000-000000000000"))
                .build(),
            Protos.ExecLogEntry.newBuilder()
                .setSymlinkAction(
                    Protos.ExecLogEntry.SymlinkAction.newBuilder()
                        .setInputPath("source")
                        .setOutputPath("target")
                        .setMnemonic("Symlink")
                        .setTargetLabel("//pkg:symlink"))
                .build());
  }

  @Test
  public void testRunfilesTreeReusedForTool() throws Exception {
    Artifact tool = ActionsTestUtil.createArtifact(rootDir, "data.txt");
    writeFile(tool, "abc");
    Artifact toolRunfiles = ActionsTestUtil.createRunfilesArtifact(outputDir, "tool.runfiles");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree = createRunfilesTree(runfilesRoot, tool);

    Artifact firstInput = ActionsTestUtil.createArtifact(rootDir, "first_input");
    writeFile(firstInput, "def");
    Artifact secondInput = ActionsTestUtil.createArtifact(rootDir, "second_input");
    writeFile(secondInput, "ghi");

    Spawn firstSpawn =
        defaultSpawnBuilder().withTool(toolRunfiles).withInputs(firstInput, toolRunfiles).build();
    Spawn secondSpawn =
        defaultSpawnBuilder().withTool(toolRunfiles).withInputs(secondInput, toolRunfiles).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        firstSpawn,
        createInputMetadataProvider(runfilesTree, toolRunfiles, firstInput),
        createInputMap(runfilesTree, firstInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());
    context.logSpawn(
        secondSpawn,
        createInputMetadataProvider(runfilesTree, toolRunfiles, secondInput),
        createInputMap(runfilesTree, secondInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    var entries = closeAndReadCompactLog(context);
    assertThat(entries.stream().filter(Protos.ExecLogEntry::hasRunfilesTree)).hasSize(1);

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/data.txt")
                    .setDigest(getDigest("abc"))
                    .setIsTool(true))
            .addInputs(
                File.newBuilder()
                    .setPath("first_input")
                    .setDigest(getDigest("def"))
                    .setIsTool(false))
            .build(),
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath(
                        PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                            + WORKSPACE_NAME
                            + "/data.txt")
                    .setDigest(getDigest("abc"))
                    .setIsTool(true))
            .addInputs(
                File.newBuilder()
                    .setPath("second_input")
                    .setDigest(getDigest("ghi"))
                    .setIsTool(false))
            .build());
  }

  @Override
  protected SpawnLogContext createSpawnLogContext(ImmutableMap<String, String> platformProperties)
      throws IOException, InterruptedException {
    RemoteOptions remoteOptions = Options.getDefaults(RemoteOptions.class);
    remoteOptions.remoteDefaultExecProperties = platformProperties.entrySet().asList();

    return new CompactSpawnLogContext(
        logPath,
        execRoot.asFragment(),
        TestConstants.WORKSPACE_NAME,
        siblingRepositoryLayout,
        remoteOptions,
        DigestHashFunction.SHA256,
        SyscallCache.NO_CACHE,
        UUID.fromString("00000000-0000-0000-0000-000000000000"));
  }

  @Override
  protected void closeAndAssertLog(SpawnLogContext context, SpawnExec... expected)
      throws IOException {
    context.close();

    ArrayList<SpawnExec> actual = new ArrayList<>();
    try (SpawnLogReconstructor reconstructor =
        new SpawnLogReconstructor(logPath.getInputStream())) {
      SpawnExec ex;
      while ((ex = reconstructor.read()) != null) {
        actual.add(ex);
      }
    }

    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  private ImmutableList<Protos.ExecLogEntry> closeAndReadCompactLog(SpawnLogContext context)
      throws IOException {
    context.close();

    ImmutableList.Builder<Protos.ExecLogEntry> entries = ImmutableList.builder();
    try (InputStream in = logPath.getInputStream();
        ZstdInputStream zstdIn = new ZstdInputStream(in)) {
      Protos.ExecLogEntry entry;
      while ((entry = Protos.ExecLogEntry.parseDelimitedFrom(zstdIn)) != null) {
        entries.add(entry);
      }
    }
    return entries.build();
  }
}
