// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.util.io.CommandExtensionReporter.NO_OP_COMMAND_EXTENSION_REPORTER;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.runtime.commands.VersionCommand;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.server.FailureDetails.Crash;
import com.google.devtools.build.lib.server.FailureDetails.Crash.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.IdleTask;
import com.google.devtools.build.lib.server.InstallBaseGarbageCollectorIdleTask;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.StringValue;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BlazeRuntime} static methods. */
@RunWith(JUnit4.class)
public class BlazeRuntimeTest {

  private final ManualClock clock = new ManualClock();
  private final FileSystem fs = new InMemoryFileSystem(clock, DigestHashFunction.SHA256);
  private final ServerDirectories serverDirectories =
      new ServerDirectories(
          fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/output_user"));
  private final BlazeDirectories blazeDirectories =
      new BlazeDirectories(
          serverDirectories, fs.getPath("/workspace"), fs.getPath("/system_javabase"), "blaze");
  private final OptionsParser optionsParser =
      OptionsParser.builder()
          .optionsClasses(ImmutableList.of(CommonCommandOptions.class, ClientOptions.class))
          .build();
  private final Thread commandThread = mock(Thread.class);
  private final AtomicReference<String> shutdownReason = new AtomicReference<>();

  @Test
  public void manageProfiles() throws Exception {
    var dir = fs.getPath("/output");
    dir.createDirectory();
    dir.getChild("foo").createDirectory();
    dir.getChild("bar").getOutputStream().close();
    clock.advanceMillis(10);
    var p1 = BlazeRuntime.manageProfiles(dir, "p1", 3);
    assertThat(p1.getBaseName()).isEqualTo("command-p1.profile.gz");
    p1.getOutputStream().close();
    clock.advanceMillis(10);
    var p2 = BlazeRuntime.manageProfiles(dir, "p2", 3);
    assertThat(p2.getBaseName()).isEqualTo("command-p2.profile.gz");
    p2.getOutputStream().close();
    clock.advanceMillis(10);
    var p3 = BlazeRuntime.manageProfiles(dir, "p3", 3);
    assertThat(p3.getBaseName()).isEqualTo("command-p3.profile.gz");
    p3.getOutputStream().close();
    clock.advanceMillis(10);
    var p4 = BlazeRuntime.manageProfiles(dir, "p4", 3);
    assertThat(p4.getBaseName()).isEqualTo("command-p4.profile.gz");
    p4.getOutputStream().close();
    assertThat(dir.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName))
        .containsExactly(
            "foo",
            "bar",
            "command-p2.profile.gz",
            "command-p3.profile.gz",
            "command-p4.profile.gz");
    clock.advanceMillis(10);
    var p5 = BlazeRuntime.manageProfiles(dir, "p5", 1);
    assertThat(p5.getBaseName()).isEqualTo("command-p5.profile.gz");
    p5.getOutputStream().close();
    assertThat(dir.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName))
        .containsExactly("foo", "bar", "command-p5.profile.gz");
  }

  @Test
  public void optionSplitting() {
    BlazeRuntime.CommandLineOptions options =
        BlazeRuntime.splitStartupOptions(
            ImmutableList.of(),
            "--install_base=/foo --host_jvm_args=-Xmx1B",
            "build",
            "//foo:bar",
            "--nobuild");
    assertThat(options.getStartupArgs())
        .containsExactly("--install_base=/foo --host_jvm_args=-Xmx1B");
    assertThat(options.getOtherArgs()).isEqualTo(Arrays.asList("build", "//foo:bar", "--nobuild"));
  }

  // A regression test to make sure that the 'no' prefix is handled correctly.
  @Test
  public void optionSplittingNoPrefix() {
    BlazeRuntime.CommandLineOptions options =
        BlazeRuntime.splitStartupOptions(ImmutableList.of(), "--nobatch", "build");
    assertThat(options.getStartupArgs()).containsExactly("--nobatch");
    assertThat(options.getOtherArgs()).containsExactly("build");
  }

  @Test
  public void crashTest() throws Exception {
    BlazeRuntime runtime = createRuntime();
    CommandEnvironment env = createCommandEnvironment(runtime);
    runtime.beforeCommand(env, optionsParser.getOptions(CommonCommandOptions.class));
    DetailedExitCode oom =
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setCrash(Crash.newBuilder().setCode(Code.CRASH_OOM))
                .build());
    runtime.cleanUpForCrash(oom);
    BlazeCommandResult mainThreadCrash =
        BlazeCommandResult.failureDetail(
            FailureDetail.newBuilder()
                .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                .build());
    assertThat(
            runtime
                .afterCommand(/* forceKeepStateForTesting= */ false, env, mainThreadCrash)
                .getDetailedExitCode())
        .isEqualTo(oom);
    // Confirm that runtime interrupted the command thread.
    verify(commandThread).interrupt();
    assertThat(shutdownReason.get()).isEqualTo("foo product is crashing: ");
  }

  @Test
  public void addsResponseExtensions() throws Exception {
    BlazeRuntime runtime = createRuntime();
    CommandEnvironment env = createCommandEnvironment(runtime);
    Any anyFoo = Any.pack(StringValue.of("foo"));
    Any anyBar = Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar")));
    env.addResponseExtensions(ImmutableList.of(anyFoo, anyBar));
    assertThat(
            runtime
                .afterCommand(
                    /* forceKeepStateForTesting= */ false, env, BlazeCommandResult.success())
                .getResponseExtensions())
        .containsExactly(anyFoo, anyBar);
  }

  @Test
  public void doesNotAddInstallBaseGcIdleTaskWhenDisabled() throws Exception {
    BlazeRuntime runtime = createRuntime();
    CommandEnvironment env = createCommandEnvironment(runtime);
    CommonCommandOptions options = new CommonCommandOptions();
    options.installBaseGcMaxAge = Duration.ZERO;
    runtime.beforeCommand(env, options);
    assertThat(env.getIdleTasks()).isEmpty();
  }

  @Test
  public void addsInstallBaseGcIdleTaskWhenEnabled() throws Exception {
    BlazeRuntime runtime = createRuntime();
    CommandEnvironment env = createCommandEnvironment(runtime);
    CommonCommandOptions options = new CommonCommandOptions();
    options.installBaseGcMaxAge = Duration.ofDays(365);
    runtime.beforeCommand(env, options);
    assertThat(env.getIdleTasks()).hasSize(1);
    assertThat(env.getIdleTasks().get(0)).isInstanceOf(InstallBaseGarbageCollectorIdleTask.class);
    var idleTask = (InstallBaseGarbageCollectorIdleTask) env.getIdleTasks().get(0);
    assertThat(idleTask.delay()).isEqualTo(Duration.ZERO);
    assertThat(idleTask.getGarbageCollector().getRoot())
        .isEqualTo(blazeDirectories.getInstallBase().getParentDirectory());
    assertThat(idleTask.getGarbageCollector().getOwnInstallBase())
        .isEqualTo(blazeDirectories.getInstallBase());
    assertThat(idleTask.getGarbageCollector().getMaxAge()).isEqualTo(Duration.ofDays(365));
  }

  @Test
  public void addsIdleTasksFromModules() throws Exception {
    BlazeRuntime runtime = createRuntime();
    CommandEnvironment env = createCommandEnvironment(runtime);
    IdleTask fooTask = () -> {};
    IdleTask barTask = () -> {};
    env.addIdleTask(fooTask);
    env.addIdleTask(barTask);
    assertThat(
            runtime
                .afterCommand(
                    /* forceKeepStateForTesting= */ false, env, BlazeCommandResult.success())
                .getIdleTasks())
        .containsExactly(fooTask, barTask);
  }

  @Test
  public void addsCommandsFromModules() throws Exception {
    BlazeRuntime runtime = createRuntime(new FooCommandModule(), new BarCommandModule());

    assertThat(runtime.getCommandMap().keySet()).containsExactly("foo", "bar").inOrder();
    assertThat(runtime.getCommandMap().get("foo")).isInstanceOf(FooCommandModule.FooCommand.class);
    assertThat(runtime.getCommandMap().get("bar")).isInstanceOf(BarCommandModule.BarCommand.class);
  }

  private BlazeRuntime createRuntime(BlazeModule... modules) throws Exception {
    var builder =
        new BlazeRuntime.Builder()
            .setFileSystem(fs)
            .setProductName("foo product")
            .setServerDirectories(serverDirectories)
            .setStartupOptionsProvider(mock(OptionsParsingResult.class));
    for (var module : modules) {
      builder.addBlazeModule(module);
    }
    return builder.build();
  }

  private CommandEnvironment createCommandEnvironment(BlazeRuntime runtime) throws Exception {
    BlazeWorkspace workspace =
        runtime.initWorkspace(blazeDirectories, BinTools.empty(blazeDirectories));
    return new CommandEnvironment(
        runtime,
        workspace,
        mock(EventBus.class),
        commandThread,
        VersionCommand.class.getAnnotation(Command.class),
        optionsParser,
        InvocationPolicy.getDefaultInstance(),
        /* packageLocator= */ null,
        SyscallCache.NO_CACHE,
        QuiescingExecutorsImpl.forTesting(),
        /* warnings= */ ImmutableList.of(),
        /* waitTimeInMs= */ 0L,
        /* commandStartTime= */ 0L,
        /* commandExtensions= */ ImmutableList.of(),
        /* shutdownReasonConsumer= */ shutdownReason::set,
        NO_OP_COMMAND_EXTENSION_REPORTER,
        /* attemptNumber= */ 1,
        ConfigFlagDefinitions.NONE);
  }

  private static class FooCommandModule extends BlazeModule {
    @Command(name = "foo", shortDescription = "", help = "")
    private static class FooCommand implements BlazeCommand {

      @Override
      public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
        return null;
      }
    }

    @Override
    public void serverInit(OptionsParsingResult startupOptions, ServerBuilder builder) {
      builder.addCommands(new FooCommand());
    }
  }

  private static class BarCommandModule extends BlazeModule {
    @Command(name = "bar", shortDescription = "", help = "")
    private static class BarCommand implements BlazeCommand {

      @Override
      public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
        return null;
      }
    }

    @Override
    public void serverInit(OptionsParsingResult startupOptions, ServerBuilder builder) {
      builder.addCommands(new BarCommand());
    }
  }
}
