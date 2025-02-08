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
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.extra.EnvironmentVariable;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.util.ActionTester;
import com.google.devtools.build.lib.analysis.util.ActionTester.ActionCombinationFactory;
import com.google.devtools.build.lib.analysis.util.AnalysisTestUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.starlark.java.syntax.Location;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link SpawnAction}. */
@RunWith(JUnit4.class)
public final class SpawnActionTest extends BuildViewTestCase {
  private Artifact welcomeArtifact;
  private Artifact destinationArtifact;
  private Artifact jarArtifact;
  private AnalysisTestUtil.CollectingAnalysisEnvironment collectingAnalysisEnvironment;

  private static SpawnAction.Builder builder() {
    return new SpawnAction.Builder();
  }

  @Before
  public void createArtifacts() throws Exception {
    collectingAnalysisEnvironment =
        new AnalysisTestUtil.CollectingAnalysisEnvironment(getTestAnalysisEnvironment());
    welcomeArtifact = getSourceArtifact("pkg/welcome.txt");
    jarArtifact = getSourceArtifact("pkg/exe.jar");
    destinationArtifact = getBinArtifactWithNoOwner("dir/destination.txt");
  }

  private SpawnAction createCopyFromWelcomeToDestination(Map<String, String> environmentVariables) {
    PathFragment cp = PathFragment.create("/bin/cp");
    ImmutableList<String> arguments =
        ImmutableList.of(
            welcomeArtifact.getExecPath().getPathString(),
            destinationArtifact.getExecPath().getPathString());

    SpawnAction action =
        builder()
            .addInput(welcomeArtifact)
            .addOutput(destinationArtifact)
            .setExecutionInfo(ImmutableMap.of("local", ""))
            .setExecutable(cp)
            .setProgressMessage("hi, mom!")
            .setMnemonic("Dummy")
            .setEnvironment(environmentVariables)
            .addCommandLine(CommandLine.of(arguments))
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    return action;
  }

  @Test
  public void testWelcomeArtifactIsInput() {
    SpawnAction copyFromWelcomeToDestination =
        createCopyFromWelcomeToDestination(ImmutableMap.of());
    ImmutableList<Artifact> inputs = copyFromWelcomeToDestination.getInputs().toList();
    assertThat(inputs).containsExactly(welcomeArtifact);
  }

  @Test
  public void testDestinationArtifactIsOutput() {
    SpawnAction copyFromWelcomeToDestination =
        createCopyFromWelcomeToDestination(ImmutableMap.of());
    Collection<Artifact> outputs = copyFromWelcomeToDestination.getOutputs();
    assertThat(outputs).containsExactly(destinationArtifact);
  }

  @Test
  public void testExecutionInfoCopied() {
    SpawnAction copyFromWelcomeToDestination =
        createCopyFromWelcomeToDestination(ImmutableMap.of());
    ImmutableMap<String, String> executionInfo = copyFromWelcomeToDestination.getExecutionInfo();
    assertThat(executionInfo).containsExactly("local", "");
  }

  @Test
  public void testExecutionInfo_fromExecutionPlatform() throws Exception {
    ActionOwner actionOwner =
        ActionOwner.createDummy(
            Label.parseCanonicalUnchecked("//target"),
            new Location("dummy-file", 0, 0),
            /* targetKind= */ "dummy-kind",
            /* buildConfigurationMnemonic= */ "dummy-configuration-mnemonic",
            /* configurationChecksum= */ "dummy-configuration",
            new BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
            /* isToolConfiguration= */ false,
            /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
            ImmutableList.of(),
            ImmutableMap.<String, String>builder()
                .put("prop1", "foo")
                .put("prop2", "bar")
                .buildOrThrow());

    SpawnAction action =
        builder()
            .addInput(welcomeArtifact)
            .addOutput(destinationArtifact)
            .setExecutionInfo(
                ImmutableMap.<String, String>builder()
                    .put("prop2", "quux") // Overwrite the value from ActionOwner's exec properties.
                    .buildOrThrow())
            .setExecutable(scratch.file("/bin/xxx").asFragment())
            .setProgressMessage("hi, mom!")
            .setMnemonic("Dummy")
            .build(actionOwner, targetConfig);

    ImmutableMap<String, String> result = action.getExecutionInfo();
    assertThat(result).containsEntry("prop1", "foo");
    assertThat(result).containsEntry("prop2", "quux");
  }

  @Test
  public void testBuilder() throws Exception {
    Artifact input = getSourceArtifact("input");
    Artifact output = getBinArtifactWithNoOwner("output");
    SpawnAction action =
        builder()
            .addInput(input)
            .addOutput(output)
            .setExecutable(scratch.file("/bin/xxx").asFragment())
            .setProgressMessage("Test")
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    assertThat(action.getOwner().getLabel())
        .isEqualTo(ActionsTestUtil.NULL_ACTION_OWNER.getLabel());
    assertThat(action.getInputs().toList()).containsExactly(input);
    assertThat(action.getOutputs()).containsExactly(output);
    assertThat(action.getSpawnForTesting().getLocalResources())
        .isEqualTo(AbstractAction.DEFAULT_RESOURCE_SET);
    assertThat(action.getArguments()).containsExactly("/bin/xxx");
    assertThat(action.getProgressMessage()).isEqualTo("Test");
  }

  @Test
  public void testBuilderWithExecutable() throws Exception {
    SpawnAction action =
        builder()
            .setExecutable(welcomeArtifact)
            .addOutput(destinationArtifact)
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    assertThat(action.getArguments())
        .containsExactly(welcomeArtifact.getExecPath().getPathString());
  }

  @Test
  public void testBuilderWithExecutableInRootPackage() throws Exception {
    Artifact tool = getSourceArtifact("tool.bin");
    SpawnAction action =
        builder()
            .setExecutable(tool)
            .addOutput(destinationArtifact)
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    assertThat(action.getArguments()).hasSize(1);
    assertThat(action.getArguments().get(0)).matches("\\.[/\\\\]tool.bin");
  }

  @Test
  public void testBuilderWithJarExecutable() throws Exception {
    SpawnAction action =
        builder()
            .addOutput(destinationArtifact)
            .setJarExecutable(
                PathFragment.create("/bin/java"),
                jarArtifact,
                NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg"))
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    assertThat(action.getArguments())
        .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar")
        .inOrder();
  }

  @Test
  public void testBuilderWithJarExecutableAndParameterFile2() throws Exception {
    useConfiguration("--min_param_file_size=0");
    collectingAnalysisEnvironment =
        new AnalysisTestUtil.CollectingAnalysisEnvironment(getTestAnalysisEnvironment());
    Artifact output = getBinArtifactWithNoOwner("output");
    SpawnAction action =
        builder()
            .addOutput(output)
            .setJarExecutable(
                PathFragment.create("/bin/java"),
                jarArtifact,
                NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg"))
            .addCommandLine(
                CustomCommandLine.builder().add("-X").build(),
                ParamFileInfo.builder(ParameterFileType.UNQUOTED).build())
            .build(nullOwnerWithTargetConfig(), targetConfig);

    // The action reports all arguments, including those inside the param file
    assertThat(action.getArguments())
        .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "-X")
        .inOrder();

    ActionExecutionContext actionExecutionContext =
        new ActionExecutionContextBuilder()
            .setArtifactExpander(treeArtifact -> ImmutableSortedSet.of())
            .setMetadataProvider(new FakeActionInputFileCache())
            .build();

    Spawn spawn =
        action.getSpawn(
            actionExecutionContext,
            ImmutableMap.of(),
            /* envResolved= */ false,
            /* reportOutputs= */ true);
    String paramFileName = output.getExecPathString() + "-0.params";
    // The spawn's primary arguments should reference the param file
    assertThat(spawn.getArguments())
        .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "@" + paramFileName)
        .inOrder();

    // Asserts that the inputs contain the param file virtual input
    Optional<? extends ActionInput> input =
        spawn.getInputFiles().toList().stream()
            .filter(i -> i instanceof VirtualActionInput)
            .findFirst();
    assertThat(input).isPresent();
    VirtualActionInput paramFile = (VirtualActionInput) input.get();
    assertThat(paramFile.getBytes().toString(ISO_8859_1).trim()).isEqualTo("-X");
  }

  @Test
  public void testBuilderWithExtraExecutableArguments() throws Exception {
    SpawnAction action =
        builder()
            .addOutput(destinationArtifact)
            .setJarExecutable(
                PathFragment.create("/bin/java"),
                jarArtifact,
                NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg"))
            .addExecutableArguments("execArg1", "execArg2")
            .addCommandLine(CustomCommandLine.builder().add("arg1").build())
            .build(nullOwnerWithTargetConfig(), targetConfig);
    collectingAnalysisEnvironment.registerAction(action);
    assertThat(action.getArguments())
        .containsExactly(
            "/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "execArg1", "execArg2", "arg1");
  }

  @Test
  public void testBuilderWithNoExecutableCommand_buildsActionWithCorrectArgs() throws Exception {
    SpawnAction action =
        builder()
            .addOutput(getBinArtifactWithNoOwner("output"))
            .addCommandLine(CommandLine.of(ImmutableList.of("arg1", "arg2")))
            .addCommandLine(CommandLine.of(ImmutableList.of("arg3")))
            .build(nullOwnerWithTargetConfig(), targetConfig);

    assertThat(action.getArguments()).containsExactly("arg1", "arg2", "arg3").inOrder();
  }

  @Test
  public void testMultipleCommandLines() throws Exception {
    Artifact input = getSourceArtifact("input");
    Artifact output = getBinArtifactWithNoOwner("output");
    SpawnAction action =
        builder()
            .addInput(input)
            .addOutput(output)
            .setExecutable(scratch.file("/bin/xxx").asFragment())
            .addCommandLine(CommandLine.of(ImmutableList.of("arg1")))
            .addCommandLine(CommandLine.of(ImmutableList.of("arg2")))
            .build(nullOwnerWithTargetConfig(), targetConfig);
    assertThat(action.getArguments()).containsExactly("/bin/xxx", "arg1", "arg2").inOrder();
  }

  @Test
  public void testExtraActionInfo() throws Exception {
    SpawnAction action = createCopyFromWelcomeToDestination(ImmutableMap.of());
    ExtraActionInfo info = action.getExtraActionInfo(actionKeyContext).build();
    assertThat(info.getMnemonic()).isEqualTo("Dummy");

    SpawnInfo spawnInfo = info.getExtension(SpawnInfo.spawnInfo);
    assertThat(info.hasExtension(SpawnInfo.spawnInfo)).isTrue();

    assertThat(spawnInfo.getArgumentList()).containsExactlyElementsIn(action.getArguments());

    List<String> inputPaths = Artifact.asExecPaths(action.getInputs());
    List<String> outputPaths = Artifact.asExecPaths(action.getOutputs());

    assertThat(spawnInfo.getInputFileList()).containsExactlyElementsIn(inputPaths);
    assertThat(spawnInfo.getOutputFileList()).containsExactlyElementsIn(outputPaths);
    ImmutableMap<String, String> environment = action.getIncompleteEnvironmentForTesting();
    assertThat(spawnInfo.getVariableCount()).isEqualTo(environment.size());

    for (EnvironmentVariable variable : spawnInfo.getVariableList()) {
      assertThat(environment).containsEntry(variable.getName(), variable.getValue());
    }
  }

  /** Test that environment variables are not escaped or quoted. */
  @Test
  public void testExtraActionInfoEnvironmentVariables() throws Exception {
    ImmutableMap<String, String> env =
        ImmutableMap.of(
            "P1", "simple",
            "P2", "spaces are not escaped",
            "P3", ":",
            "P4", "",
            "NONSENSE VARIABLE", "value");

    SpawnInfo spawnInfo =
        createCopyFromWelcomeToDestination(env)
            .getExtraActionInfo(actionKeyContext)
            .build()
            .getExtension(SpawnInfo.spawnInfo);
    assertThat(env).hasSize(spawnInfo.getVariableCount());
    for (EnvironmentVariable variable : spawnInfo.getVariableList()) {
      assertThat(env).containsEntry(variable.getName(), variable.getValue());
    }
  }

  private enum KeyAttributes {
    EXECUTABLE_PATH,
    EXECUTABLE,
    MNEMONIC,
    ENVIRONMENT
  }

  @Test
  public void testComputeKey() throws Exception {
    final Artifact artifactA = getSourceArtifact("a");
    final Artifact artifactB = getSourceArtifact("b");

    ActionTester.runTest(
        KeyAttributes.class,
        new ActionCombinationFactory<KeyAttributes>() {
          @Override
          public Action generate(ImmutableSet<KeyAttributes> attributesToFlip) {
            SpawnAction.Builder builder = builder();
            builder.addOutput(destinationArtifact);

            PathFragment executable =
                attributesToFlip.contains(KeyAttributes.EXECUTABLE_PATH)
                    ? artifactA.getExecPath()
                    : artifactB.getExecPath();
            if (attributesToFlip.contains(KeyAttributes.EXECUTABLE)) {
              builder.setExecutable(executable);
            } else {
              builder.setJarExecutable(
                  executable, jarArtifact, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
            }

            builder.setMnemonic(attributesToFlip.contains(KeyAttributes.MNEMONIC) ? "a" : "b");

            Map<String, String> env = new HashMap<>();
            if (attributesToFlip.contains(KeyAttributes.ENVIRONMENT)) {
              env.put("foo", "bar");
            }
            builder.setEnvironment(env);

            SpawnAction action = builder.build(nullOwnerWithTargetConfig(), targetConfig);
            collectingAnalysisEnvironment.registerAction(action);
            return action;
          }
        },
        actionKeyContext);
  }

  @Test
  public void testMnemonicMustNotContainSpaces() {
    SpawnAction.Builder builder = builder();
    assertThrows(IllegalArgumentException.class, () -> builder.setMnemonic("contains space"));
    assertThrows(IllegalArgumentException.class, () -> builder.setMnemonic("contains\nnewline"));
    assertThrows(IllegalArgumentException.class, () -> builder.setMnemonic("contains/slash"));
  }

  @Test
  public void testProgressMessagePlaceholders() throws Exception {
    SpawnAction action =
        builder()
            .addInput(getSourceArtifact("some/input"))
            .addOutput(getBinArtifactWithNoOwner("some/output"))
            .setExecutable(scratch.file("/bin/xxx").asFragment())
            .setProgressMessage("Progress for %{label}: %{input} -> %{output}")
            .build(nullOwnerWithTargetConfig(), targetConfig);
    assertThat(action.getProgressMessage())
        .isEqualTo("Progress for //null/action:owner: some/input -> some/output");
  }

  /**
   * Tests that the ExtraActionInfo proto that's generated from an action, contains Aspect-related
   * information.
   */
  @Test
  public void testGetExtraActionInfoOnAspects() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("//a:def.bzl", "testrule")

        testrule(
            name = "a",
            deps = [":b"],
        )

        testrule(name = "b")
        """);
    scratch.file(
        "a/def.bzl",
        """
        MyInfo = provider()

        def _aspect_impl(target, ctx):
            f = ctx.actions.declare_file("foo.txt")
            ctx.actions.run_shell(outputs = [f], command = 'echo foo > "$1"')
            return MyInfo(output = f)

        def _rule_impl(ctx):
            return DefaultInfo(
                files = depset([artifact[MyInfo].output for artifact in ctx.attr.deps]),
            )

        aspect1 = aspect(
            _aspect_impl,
            attr_aspects = ["deps"],
            attrs = {"parameter": attr.string(values = ["param_value"])},
        )
        testrule = rule(_rule_impl, attrs = {
            "deps": attr.label_list(aspects = [aspect1]),
            "parameter": attr.string(default = "param_value"),
        })
        """);

    update(
        ImmutableList.of("//a:a"),
        /* keepGoing= */ false,
        /* loadingPhaseThreads= */ 1,
        /* doAnalysis= */ true,
        new EventBus());

    Artifact artifact = getFilesToBuild(getConfiguredTarget("//a:a")).getSingleton();
    ExtraActionInfo.Builder extraActionInfo =
        getGeneratingAction(artifact).getExtraActionInfo(actionKeyContext);
    assertThat(extraActionInfo.getAspectName()).isEqualTo("//a:def.bzl%aspect1");
    assertThat(extraActionInfo.getAspectParametersMap())
        .containsExactly(
            "parameter", ExtraActionInfo.StringList.newBuilder().addValue("param_value").build());
  }

  private SpawnAction createWorkerSupportSpawn(Map<String, String> executionInfoVariables)
      throws Exception {
    Artifact input = getSourceArtifact("input");
    Artifact output = getBinArtifactWithNoOwner("output");
    return builder()
        .addInput(input)
        .addOutput(output)
        .setMnemonic("ActionToolMnemonic")
        .setExecutionInfo(executionInfoVariables)
        .setExecutable(scratch.file("/bin/xxx").asFragment())
        .build(nullOwnerWithTargetConfig(), targetConfig);
  }

  @Test
  public void testWorkerSupport() throws Exception {
    SpawnAction workerSupportSpawn =
        createWorkerSupportSpawn(ImmutableMap.of("supports-workers", "1"));
    assertThat(Spawns.supportsWorkers(workerSupportSpawn.getSpawnForTesting())).isTrue();
  }

  @Test
  public void testMultiplexWorkerSupport() throws Exception {
    SpawnAction multiplexWorkerSupportSpawn =
        createWorkerSupportSpawn(ImmutableMap.of("supports-multiplex-workers", "1"));
    assertThat(Spawns.supportsMultiplexWorkers(multiplexWorkerSupportSpawn.getSpawnForTesting()))
        .isTrue();
  }

  @Test
  public void testWorkerProtocolFormat_defaultIsProto() throws Exception {
    SpawnAction spawn = createWorkerSupportSpawn(ImmutableMap.of("supports-workers", "1"));
    assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
        .isEqualTo(WorkerProtocolFormat.PROTO);
  }

  @Test
  public void testWorkerProtocolFormat_explicitProto() throws Exception {
    SpawnAction spawn =
        createWorkerSupportSpawn(
            ImmutableMap.of("supports-workers", "1", "requires-worker-protocol", "proto"));
    assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
        .isEqualTo(WorkerProtocolFormat.PROTO);
  }

  @Test
  public void testWorkerProtocolFormat_explicitJson() throws Exception {
    SpawnAction spawn =
        createWorkerSupportSpawn(
            ImmutableMap.of("supports-workers", "1", "requires-worker-protocol", "json"));
    assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
        .isEqualTo(WorkerProtocolFormat.JSON);
  }

  @Test
  public void testWorkerMnemonicDefault() throws Exception {
    SpawnAction defaultMnemonicSpawn = createWorkerSupportSpawn(ImmutableMap.of());
    assertThat(Spawns.getWorkerKeyMnemonic(defaultMnemonicSpawn.getSpawnForTesting()))
        .isEqualTo("ActionToolMnemonic");
  }

  @Test
  public void testWorkerMnemonicOverride() throws Exception {
    SpawnAction customMnemonicSpawn =
        createWorkerSupportSpawn(ImmutableMap.of("worker-key-mnemonic", "ToolPoolMnemonic"));
    assertThat(Spawns.getWorkerKeyMnemonic(customMnemonicSpawn.getSpawnForTesting()))
        .isEqualTo("ToolPoolMnemonic");
  }

  private ActionOwner nullOwnerWithTargetConfig() {
    return ActionOwner.create(
        ActionsTestUtil.NULL_ACTION_OWNER.getLabel(),
        ActionsTestUtil.NULL_ACTION_OWNER.getLocation(),
        ActionsTestUtil.NULL_ACTION_OWNER.getTargetKind(),
        targetConfig,
        ActionsTestUtil.NULL_ACTION_OWNER.getExecutionPlatform(),
        ActionsTestUtil.NULL_ACTION_OWNER.getAspectDescriptors(),
        ActionsTestUtil.NULL_ACTION_OWNER.getExecProperties());
  }
}
