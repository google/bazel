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
package com.google.devtools.build.lib.standalone;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.BlazeExecutor;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalSpawnRunner;
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder;
import com.google.devtools.build.lib.integration.util.IntegrationMock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Test StandaloneSpawnStrategy.
 */
@RunWith(JUnit4.class)
public class StandaloneSpawnStrategyTest {
  private static final ArtifactExpander SIMPLE_ARTIFACT_EXPANDER =
      new ArtifactExpander() {
        @Override
        public void expand(Artifact artifact, Collection<? super Artifact> output) {
          output.add(artifact);
        }
      };
  private static final String WINDOWS_SYSTEM_DRIVE = "C:";
  private static final String CMD_EXE = getWinSystemBinary("cmd.exe");

  private Reporter reporter =
      new Reporter(new EventBus(), PrintingEventHandler.ERRORS_AND_WARNINGS_TO_STDERR);
  private BlazeExecutor executor;
  private FileSystem fileSystem;
  private FileOutErr outErr;

  private Path createTestRoot() throws IOException {
    fileSystem = FileSystems.getNativeFileSystem();
    Path testRoot = fileSystem.getPath(TestUtils.tmpDir()).getRelative("test");
    testRoot.createDirectoryAndParents();
    try {
      testRoot.deleteTreesBelow();
    } catch (IOException e) {
      System.err.println("Failed to remove directory " + testRoot + ": " + e.getMessage());
      throw e;
    }
    return testRoot;
  }

  /**
   * We assume Windows is installed on C: and all system binaries exist under C:\Windows\System32\
   */
  private static String getWinSystemBinary(String binary) {
    return WINDOWS_SYSTEM_DRIVE + "\\Windows\\System32\\" + binary;
  }

  @Before
  public final void setUp() throws Exception {
    Path testRoot = createTestRoot();
    Path workspaceDir = testRoot.getRelative("workspace-name");
    workspaceDir.createDirectory();
    outErr = new FileOutErr(testRoot.getRelative("stdout"), testRoot.getRelative("stderr"));

    // setup output base & directories
    Path outputBase = testRoot.getRelative("outputBase");
    outputBase.createDirectory();

    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(outputBase, outputBase, outputBase),
            workspaceDir,
            /* defaultSystemJavabase= */ null,
            "mock-product-name");
    // This call implicitly symlinks the integration bin tools into the exec root.
    IntegrationMock.get().getIntegrationBinTools(fileSystem, directories);
    OptionsParser optionsParser =
        OptionsParser.builder().optionsClasses(ExecutionOptions.class).build();
    optionsParser.parse("--verbose_failures");
    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    LocalExecutionOptions localExecutionOptions = Options.getDefaults(LocalExecutionOptions.class);

    ResourceManager resourceManager = ResourceManager.instanceForTestingOnly();
    resourceManager.setAvailableResources(
        ResourceSet.create(/*memoryMb=*/1, /*cpuUsage=*/1, /*localTestCount=*/1));
    Path execRoot = directories.getExecRoot(TestConstants.WORKSPACE_NAME);
    BinTools binTools = BinTools.forIntegrationTesting(directories, ImmutableList.of());
    StandaloneSpawnStrategy strategy =
        new StandaloneSpawnStrategy(
            execRoot,
            new LocalSpawnRunner(
                execRoot,
                executionOptions,
                localExecutionOptions,
                resourceManager,
                (env, binTools1, fallbackTmpDir) -> ImmutableMap.copyOf(env),
                binTools,
                /* processWrapper= */ null,
                Mockito.mock(RunfilesTreeUpdater.class)),
            new ExecutionOptions());
    this.executor =
        new TestExecutorBuilder(fileSystem, directories, binTools)
            .addStrategy(strategy, "standalone")
            .setDefaultStrategies("standalone")
            .build();

    executor.getExecRoot().createDirectoryAndParents();
  }

  private Spawn createSpawn(String... arguments) {
    return new SimpleSpawn(
        new ActionsTestUtil.NullAction(),
        ImmutableList.copyOf(arguments),
        /*environment=*/ ImmutableMap.of(),
        /*executionInfo=*/ ImmutableMap.of(),
        /*inputs=*/ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        /*outputs=*/ ImmutableSet.of(),
        ResourceSet.ZERO);
  }

  private String out() {
    return outErr.outAsLatin1();
  }
  private String err() {
    return outErr.errAsLatin1();
  }

  @Test
  public void testBinTrueExecutesFine() throws Exception {
    Spawn spawn = createSpawn(getTrueCommand());
    executor.getContext(SpawnStrategyResolver.class).exec(spawn, createContext());

    if (OS.getCurrent() != OS.WINDOWS) {
      assertThat(out()).isEmpty();
    }
    assertThat(err()).isEmpty();
  }

  private List<SpawnResult> run(Spawn spawn) throws Exception {
    return executor.getContext(SpawnStrategyResolver.class).exec(spawn, createContext());
  }

  private ActionExecutionContext createContext() {
    Path execRoot = executor.getExecRoot();
    return new ActionExecutionContext(
        executor,
        new SingleBuildFileCache(
            execRoot.getPathString(), execRoot.getFileSystem(), SyscallCache.NO_CACHE),
        ActionInputPrefetcher.NONE,
        new ActionKeyContext(),
        /* outputMetadataStore= */ null,
        /* rewindingEnabled= */ false,
        LostInputsCheck.NONE,
        outErr,
        reporter,
        /* clientEnv= */ ImmutableMap.of(),
        /* topLevelFilesets= */ ImmutableMap.of(),
        SIMPLE_ARTIFACT_EXPANDER,
        /* actionFileSystem= */ null,
        /* skyframeDepsResult= */ null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  @Test
  public void testBinFalseYieldsException() {
    ExecException e = assertThrows(ExecException.class, () -> run(createSpawn(getFalseCommand())));
    assertWithMessage("got: " + e.getMessage())
        .that(e.getMessage().contains("failed: error executing Null command"))
        .isTrue();
  }

  private static String getFalseCommand() {
    if (OS.getCurrent() == OS.WINDOWS) {
      // No false command on Windows, we use help.exe as an alternative,
      // the caveat is that the command will have some output to stdout.
      // Default exit code of help is 1
      return getWinSystemBinary("help.exe");
    }
    return OS.getCurrent() == OS.DARWIN ? "/usr/bin/false" : "/bin/false";
  }

  private static String getTrueCommand() {
    if (OS.getCurrent() == OS.WINDOWS) {
      // No true command on Windows, we use whoami.exe as an alternative,
      // the caveat is that the command will have some output to stdout.
      // Default exit code of help is 0
      return getWinSystemBinary("whoami.exe");
    }
    return OS.getCurrent() == OS.DARWIN ? "/usr/bin/true" : "/bin/true";
  }

  @Test
  public void testBinEchoPrintsArguments() throws Exception {
    Spawn spawn;
    if (OS.getCurrent() == OS.WINDOWS) {
      spawn = createSpawn(CMD_EXE, "/c", "echo", "Hello,", "world.");
    } else {
      spawn = createSpawn("/bin/echo", "Hello,", "world.");
    }
    run(spawn);
    assertThat(out()).isEqualTo("Hello, world." + System.lineSeparator());
    assertThat(err()).isEmpty();
  }

  @Test
  public void testCommandRunsInWorkingDir() throws Exception {
    Spawn spawn;
    if (OS.getCurrent() == OS.WINDOWS) {
      spawn = createSpawn(CMD_EXE, "/c", "cd");
    } else {
      spawn = createSpawn("/bin/pwd");
    }
    run(spawn);
    assertThat(out().replace('\\', '/')).isEqualTo(executor.getExecRoot() + System.lineSeparator());
  }

  @Test
  public void testCommandHonorsEnvironment() throws Exception {
    Spawn spawn =
        new SimpleSpawn(
            new ActionsTestUtil.NullAction(),
            OS.getCurrent() == OS.WINDOWS
                ? ImmutableList.of(CMD_EXE, "/c", "set")
                : ImmutableList.of("/usr/bin/env"),
            /*environment=*/ ImmutableMap.of("foo", "bar", "baz", "boo"),
            /*executionInfo=*/ ImmutableMap.of(),
            /*inputs=*/ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            /*outputs=*/ ImmutableSet.of(),
            ResourceSet.ZERO);
    run(spawn);
    HashSet<String> environment = Sets.newHashSet(out().split(System.lineSeparator()));
    if (OS.getCurrent() == OS.WINDOWS || OS.getCurrent() == OS.DARWIN) {
      // On Windows and macOS, we may have some other env vars
      // (eg. SystemRoot or __CF_USER_TEXT_ENCODING).
      assertThat(environment).contains("foo=bar");
      assertThat(environment).contains("baz=boo");
    } else {
      assertThat(environment).isEqualTo(Sets.newHashSet("foo=bar", "baz=boo"));
    }
  }

  @Test
  public void testStandardError() throws Exception {
    Spawn spawn;
    if (OS.getCurrent() == OS.WINDOWS) {
      spawn = createSpawn(CMD_EXE, "/c", "echo Oops!>&2");
    } else {
      spawn = createSpawn("/bin/sh", "-c", "echo Oops! >&2");
    }
    run(spawn);
    assertThat(err()).isEqualTo("Oops!" + System.lineSeparator());
    assertThat(out()).isEmpty();
  }

  /**
   * Regression test for https://github.com/bazelbuild/bazel/issues/10572 Make sure we do have the
   * command line executed in the error message of ActionExecutionException when --verbose_failures
   * is enabled.
   */
  @Test
  public void testVerboseFailures() {
    ExecException e = assertThrows(ExecException.class, () -> run(createSpawn(getFalseCommand())));
    ActionExecutionException actionExecutionException =
        ActionExecutionException.fromExecException(e, new NullAction());
    assertWithMessage("got: " + actionExecutionException.getMessage())
        .that(
            actionExecutionException.getMessage().contains("failed: error executing Null command"))
        .isTrue();
  }
}
