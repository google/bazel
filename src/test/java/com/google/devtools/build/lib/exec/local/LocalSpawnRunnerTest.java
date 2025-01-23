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

package com.google.devtools.build.lib.exec.local;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testing.common.DirectoryListingHelper.file;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnSchedulingEvent;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.sandbox.SpawnRunnerTestUtil.SpawnExecutionContextForTesting;
import com.google.devtools.build.lib.shell.JavaSubprocessFactory;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.SubprocessBuilder.StreamAction;
import com.google.devtools.build.lib.shell.SubprocessFactory;
import com.google.devtools.build.lib.testing.common.DirectoryListingHelper;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.util.NetUtil;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Converters.RegexPatternConverter;
import com.google.devtools.common.options.Options;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit tests for {@link LocalSpawnRunner}. */
@RunWith(JUnit4.class)
public class LocalSpawnRunnerTest {

  private static class TestedLocalSpawnRunner extends LocalSpawnRunner {
    private Path tmpDirPath;

    public TestedLocalSpawnRunner(
        Path execRoot,
        LocalExecutionOptions localExecutionOptions,
        ResourceManager resourceManager,
        ProcessWrapper processWrapper,
        LocalEnvProvider localEnvProvider) {
      super(
          execRoot,
          Options.getDefaults(ExecutionOptions.class),
          localExecutionOptions,
          resourceManager,
          localEnvProvider,
          /* binTools= */ null,
          processWrapper,
          Mockito.mock(RunfilesTreeUpdater.class));
    }

    // Rigged to act on supplied filesystem (e.g. InMemoryFileSystem) for testing purposes
    // TODO(b/70572634): Update FileSystem abstraction to support createTempDirectory() from
    // the java.nio.file.Files package.
    @Override
    protected Path createActionTemp(Path execRoot) throws IOException {
      Path tempDirPath;
      do {
        String idStr =
            Long.toHexString(Thread.currentThread().getId())
                + "_"
                + Long.toHexString(ThreadLocalRandom.current().nextLong());
        tempDirPath = execRoot.getRelative("tmp" + idStr);
      } while (tempDirPath.exists());
      if (!tempDirPath.createDirectory()) {
        throw new IOException(String.format("Could not create temp directory '%s'", tempDirPath));
      }
      this.tmpDirPath = tempDirPath;
      return tempDirPath;
    }

    public Path getActionTemp() {
      return tmpDirPath;
    }
  }

  private static class FinishedSubprocess implements Subprocess {
    private final int exitCode;

    public FinishedSubprocess(int exitCode) {
      this.exitCode = exitCode;
    }

    @Override
    public boolean destroy() {
      return false;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public boolean finished() {
      return true;
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public boolean timedout() {
      return false;
    }

    @Override
    public void waitFor() throws InterruptedException {
      // Do nothing.
    }

    @Override
    public OutputStream getOutputStream() {
      return ByteStreams.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void close() {
      // Do nothing.
    }

    @Override
    public long getProcessId() {
      return 0;
    }
  }

  private static final Spawn SIMPLE_SPAWN =
      new SpawnBuilder("/bin/echo", "Hi!").withEnvironment("VARIABLE", "value").build();

  private static final class SubprocessInterceptor implements SubprocessFactory {
    @Override
    public Subprocess create(SubprocessBuilder params) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  private final ResourceManager resourceManager = ResourceManager.instanceForTestingOnly();

  private Logger logger;

  private static ImmutableMap<String, String> keepLocalEnvUnchanged(
      Map<String, String> env, BinTools binTools, String fallbackTmpDir) {
    return ImmutableMap.copyOf(env);
  }

  @Before
  public final void suppressLogging() {
    logger = Logger.getLogger(TestedLocalSpawnRunner.class.getName());
    logger.setFilter(
        new Filter() {
          @Override
          public boolean isLoggable(LogRecord record) {
            return false;
          }
        });
  }

  private FileSystem setupEnvironmentForFakeExecution() throws InterruptedException, IOException {
    // Prevent any subprocess execution at all.
    SubprocessBuilder.setDefaultSubprocessFactory(new SubprocessInterceptor());
    resourceManager.setAvailableResources(
        ResourceSet.create(/*memoryMb=*/ 1, /*cpuUsage=*/ 1, /*localTestCount=*/ 1));
    return new InMemoryFileSystem(DigestHashFunction.SHA256);
  }

  private static ProcessWrapper makeProcessWrapper(FileSystem fs, LocalExecutionOptions options) {
    return new ProcessWrapper(
        fs.getPath("/process-wrapper"),
        options.getLocalSigkillGraceSeconds(),
        /*gracefulSigterm=*/ false);
  }

  /**
   * Enables real execution by default.
   *
   * <p>Tests should call setupEnvironmentForFakeExecution() if they do not want real execution.
   */
  @Before
  public final void setupEnvironmentForRealExecution() throws InterruptedException, IOException {
    SubprocessBuilder.setDefaultSubprocessFactory(JavaSubprocessFactory.INSTANCE);
    resourceManager.setAvailableResources(LocalHostCapacity.getLocalHostCapacity());
  }

  @Test
  public void vanillaZeroExit() throws Exception {
    // TODO(#3536): Make this test work on Windows.
    // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
    // T:\execroot\execroot\_bin\process-wrapper
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    options.localSigkillGraceSeconds = 456;
    TestedLocalSpawnRunner testedRunner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);
    LocalSpawnRunner runner = testedRunner;

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    SpawnResult result = runner.exec(SIMPLE_SPAWN, context);
    verify(factory).create(any(SubprocessBuilder.class));
    assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    assertThat(captor.getValue().getArgv())
        .containsExactlyElementsIn(
            ImmutableList.of(
                "/process-wrapper",
                "--timeout=123",
                "--kill_delay=456",
                "--stats=" + testedRunner.getActionTemp().getRelative("stats.out"),
                "/bin/echo",
                "Hi!"));
    assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value");
    assertThat(captor.getValue().getTimeoutMillis()).isEqualTo(0);
    assertThat(captor.getValue().getStdout()).isEqualTo(StreamAction.REDIRECT);
    assertThat(captor.getValue().getStdoutFile()).isEqualTo(new File("/out/stdout"));
    assertThat(captor.getValue().getStderr()).isEqualTo(StreamAction.REDIRECT);
    assertThat(captor.getValue().getStderrFile()).isEqualTo(new File("/out/stderr"));

    assertThat(context.lockOutputFilesCalled).isTrue();
    assertThat(context.reportedStatus)
        .containsExactly(SpawnSchedulingEvent.create("local"), SpawnExecutingEvent.create("local"))
        .inOrder();
  }

  @Test
  public void testParamFiles() throws Exception {
    // TODO(#3536): Make this test work on Windows.
    // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
    // T:\execroot\execroot\_bin\process-wrapper
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    when(factory.create(any())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    options.localSigkillGraceSeconds = 456;
    Path execRoot = fs.getPath("/execroot");
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            execRoot,
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);
    ParamFileActionInput paramFileActionInput =
        new ParamFileActionInput(
            PathFragment.create("some/dir/params"),
            ImmutableList.of("--foo", "--bar"),
            ParameterFileType.UNQUOTED,
            UTF_8);
    Spawn spawn =
        new SpawnBuilder("/bin/echo", "Hi!")
            .withInput(paramFileActionInput)
            .withEnvironment("VARIABLE", "value")
            .build();
    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    SpawnResult result = runner.exec(spawn, context);
    assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());
    Path paramFile = execRoot.getRelative("some/dir/params");
    assertThat(paramFile.exists()).isTrue();
    try (InputStream inputStream = paramFile.getInputStream()) {
      assertThat(new String(ByteStreams.toByteArray(inputStream), UTF_8).split("\n"))
          .asList()
          .containsExactly("--foo", "--bar");
    }
  }

  @Test
  public void exec_materializesVirtualInputAsExecutable() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();
    SubprocessFactory factory = mock(SubprocessFactory.class);
    when(factory.create(any())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);
    Path execRoot = fs.getPath("/execroot");
    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            execRoot,
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);
    VirtualActionInput virtualInput = ActionsTestUtil.createVirtualActionInput("input1", "hello");
    Spawn spawn = new SpawnBuilder("/bin/true").withInput(virtualInput).build();
    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ZERO);

    SpawnResult result = runner.exec(spawn, context);

    assertThat(result.status()).isEqualTo(Status.SUCCESS);
    assertThat(DirectoryListingHelper.leafDirectoryEntries(execRoot))
        .containsExactly(file("input1"));
    Path inputPath = execRoot.getRelative(virtualInput.getExecPath());
    assertThat(inputPath.isExecutable()).isTrue();
    assertThat(FileSystemUtils.readLinesAsLatin1(inputPath)).containsExactly("hello");
  }

  @Test
  public void noProcessWrapper() throws Exception {
    // TODO(#3536): Make this test work on Windows.
    // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
    // T:\execroot\bin\echo
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    options.localSigkillGraceSeconds = 456;
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            /*processWrapper=*/ null,
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    SpawnResult result = runner.exec(SIMPLE_SPAWN, context);
    verify(factory).create(any());
    assertThat(result.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    assertThat(captor.getValue().getArgv())
        .containsExactlyElementsIn(ImmutableList.of("/bin/echo", "Hi!"));
    assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value");
    // Without the process wrapper, we use the Command API to enforce the timeout.
    assertThat(captor.getValue().getTimeoutMillis()).isEqualTo(123 * 1000L);

    assertThat(context.lockOutputFilesCalled).isTrue();
  }

  @Test
  public void nonZeroExit() throws Exception {
    // TODO(#3536): Make this test work on Windows.
    // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
    // T:\execroot\execroot\_bin\process-wrapper
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture())).thenReturn(new FinishedSubprocess(3));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    TestedLocalSpawnRunner testedRunner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);
    LocalSpawnRunner runner = testedRunner;

    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ZERO);
    SpawnResult result = runner.exec(SIMPLE_SPAWN, context);
    verify(factory).create(any(SubprocessBuilder.class));
    assertThat(result.status()).isEqualTo(SpawnResult.Status.NON_ZERO_EXIT);
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.setupSuccess()).isTrue();
    assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    assertThat(captor.getValue().getArgv())
        .containsExactlyElementsIn(
            ImmutableList.of(
                "/process-wrapper",
                "--timeout=0",
                "--kill_delay=15",
                "--stats=" + testedRunner.getActionTemp().getRelative("stats.out"),
                "/bin/echo",
                "Hi!"));
    assertThat(captor.getValue().getEnv()).containsExactly("VARIABLE", "value");
    assertThat(captor.getValue().getStdout()).isEqualTo(StreamAction.REDIRECT);
    assertThat(captor.getValue().getStdoutFile()).isEqualTo(new File("/out/stdout"));
    assertThat(captor.getValue().getStderr()).isEqualTo(StreamAction.REDIRECT);
    assertThat(captor.getValue().getStderrFile()).isEqualTo(new File("/out/stderr"));

    assertThat(context.lockOutputFilesCalled).isTrue();
  }

  @Test
  public void processStartupThrows() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture())).thenThrow(new IOException("I'm sorry, Dave"));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    assertThat(fs.getPath("/out").createDirectory()).isTrue();
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ZERO);
    SpawnResult result = runner.exec(SIMPLE_SPAWN, context);
    verify(factory).create(any(SubprocessBuilder.class));
    assertThat(result.status()).isEqualTo(SpawnResult.Status.EXECUTION_FAILED);
    assertThat(result.exitCode()).isEqualTo(-1);
    assertThat(result.setupSuccess()).isFalse();
    assertThat(result.getWallTimeInMs()).isEqualTo(0);
    assertThat(result.getUserTimeInMs()).isEqualTo(0);
    assertThat(result.getSystemTimeInMs()).isEqualTo(0);
    assertThat(result.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    assertThat(FileSystemUtils.readContent(fs.getPath("/out/stderr"), UTF_8))
        .isEqualTo("Action failed to execute: java.io.IOException: I'm sorry, Dave\n");

    assertThat(context.lockOutputFilesCalled).isTrue();
  }

  @Test
  public void disallowLocalExecution() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    options.allowedLocalAction = new RegexPatternConverter().convert("none");
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    FileOutErr fileOutErr = new FileOutErr();
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ZERO);
    SpawnResult reply = runner.exec(SIMPLE_SPAWN, context);
    assertThat(reply.status()).isEqualTo(SpawnResult.Status.EXECUTION_DENIED);
    assertThat(reply.exitCode()).isEqualTo(-1);
    assertThat(reply.setupSuccess()).isFalse();
    assertThat(reply.getWallTimeInMs()).isEqualTo(0);
    assertThat(reply.getUserTimeInMs()).isEqualTo(0);
    assertThat(reply.getSystemTimeInMs()).isEqualTo(0);
    assertThat(reply.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    // TODO(ulfjack): Maybe we should only lock after checking?
    assertThat(context.lockOutputFilesCalled).isTrue();
  }

  @Test
  public void interruptedException() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture()))
        .thenReturn(
            new FinishedSubprocess(3) {
              private boolean destroyed;

              @Override
              public boolean destroy() {
                destroyed = true;
                return true;
              }

              @Override
              public void waitFor() throws InterruptedException {
                if (!destroyed) {
                  throw new InterruptedException();
                }
              }
            });
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ZERO);
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    assertThrows(InterruptedException.class, () -> runner.exec(SIMPLE_SPAWN, context));
    Thread.interrupted();
    assertThat(context.lockOutputFilesCalled).isTrue();
  }

  @Test
  public void interruptWaitsForProcessExit() throws Exception {
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    Path tempDir = TestUtils.createUniqueTmpDir(new JavaIoFileSystem(DigestHashFunction.SHA256));

    LocalSpawnRunner runner =
        new LocalSpawnRunner(
            tempDir,
            Options.getDefaults(ExecutionOptions.class),
            Options.getDefaults(LocalExecutionOptions.class),
            resourceManager,
            LocalEnvProvider.forCurrentOs(ImmutableMap.of()),
            /* binTools= */ null,
            /* processWrapper= */ null,
            Mockito.mock(RunfilesTreeUpdater.class));
    FileOutErr fileOutErr =
        new FileOutErr(tempDir.getRelative("stdout"), tempDir.getRelative("stderr"));

    // This test to exercise a race condition by attempting an operation multiple times. We can get
    // false positives (the test passing without us catching a problem), so try a few times. When
    // implementing this fix on 2019-09-11, this specific configuration was sufficient to catch the
    // previously-existent bug.
    int tries = 10;
    int delaySeconds = 1;

    Path content = tempDir.getChild("content");
    Path started = tempDir.getChild("started");
    // Start a subprocess that blocks until it is killed, and when it is, writes some output to
    // a temporary file after some delay.
    String script =
        "trap 'sleep "
            + delaySeconds
            + "; echo foo >"
            + content.getPathString()
            + "; exit 1' TERM; "
            + "touch "
            + started.getPathString()
            + "; "
            + "while :; do "
            + "  echo 'waiting to be killed'; "
            + "  sleep 1; "
            + "done";
    Spawn spawn = new SpawnBuilder("/bin/sh", "-c", script).build();

    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ZERO);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      for (int i = 0; i < tries; i++) {
        content.delete();
        started.delete();
        Semaphore interruptCaught = new Semaphore(0);
        Future<?> future =
            executor.submit(
                () -> {
                  try {
                    runner.exec(spawn, context);
                  } catch (InterruptedException e) {
                    interruptCaught.release();
                  } catch (Throwable t) {
                    throw new IllegalStateException(t);
                  }
                });
        // Wait until we know the subprocess has started so that delivering a termination signal
        // to it triggers the delayed write to the file.
        while (!started.exists()) {
          Thread.sleep(1);
        }
        future.cancel(true);
        interruptCaught.acquireUninterruptibly();
        // At this point, the subprocess must have fully stopped so write some content to the file
        // and expect that these contents remain unmodified.
        FileSystemUtils.writeContent(content, StandardCharsets.UTF_8, "bar");
        // Wait for longer than the spawn takes to exit before we check the file contents to ensure
        // that we properly awaited for termination of the subprocess.
        Thread.sleep(delaySeconds * 2 * 1000);
        assertThat(FileSystemUtils.readContent(content, StandardCharsets.UTF_8)).isEqualTo("bar");
      }
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void checkPrefetchCalled() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    when(factory.create(any())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    runner.exec(SIMPLE_SPAWN, context);
    assertThat(context.prefetchCalled).isTrue();
  }

  @Test
  public void checkNoPrefetchCalled() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    when(factory.create(any())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));

    Spawn spawn =
        new SpawnBuilder("/bin/echo", "Hi!")
            .withExecutionInfo(ExecutionRequirements.DISABLE_LOCAL_PREFETCH, "")
            .build();

    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofSeconds(123));

    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    runner.exec(spawn, context);
    assertThat(context.prefetchCalled).isFalse();
  }

  @Test
  public void checkLocalEnvProviderCalled() throws Exception {
    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    when(factory.create(any())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);
    LocalEnvProvider localEnvProvider = mock(LocalEnvProvider.class);

    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);
    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            options,
            resourceManager,
            makeProcessWrapper(fs, options),
            localEnvProvider);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(SIMPLE_SPAWN, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();

    runner.exec(SIMPLE_SPAWN, context);
    verify(localEnvProvider)
        .rewriteLocalEnv(any(), any(), matches("^/execroot/tmp[0-9a-fA-F]+_[0-9a-fA-F]+/work$"));
  }

  /**
   * Copies the {@code process-wrapper} tool into the path under the temporary execRoot where the
   * {@link LocalSpawnRunner} expects to find it.
   */
  private void copyProcessWrapperIntoExecRoot(Path wrapperPath) throws IOException {
    File realProcessWrapperFile =
        new File(
            PathFragment.create(BlazeTestUtils.runfilesDir())
                .getRelative(TestConstants.PROCESS_WRAPPER_PATH)
                .getPathString());
    assertThat(realProcessWrapperFile.exists()).isTrue();

    wrapperPath.createDirectoryAndParents();
    File wrapperFile = wrapperPath.getPathFile();

    wrapperPath.delete();
    Files.copy(realProcessWrapperFile, wrapperFile);
    assertThat(wrapperPath.exists()).isTrue();

    wrapperPath.setExecutable(true);
  }

  /**
   * Copies the {@code spend_cpu_time} test util into the temporary execRoot so that the {@link
   * LocalSpawnRunner} can execute it.
   */
  private Path copyCpuTimeSpenderIntoExecRoot(Path execRoot) throws IOException {
    File realCpuTimeSpenderFile =
        new File(
            PathFragment.create(BlazeTestUtils.runfilesDir())
                .getRelative(TestConstants.CPU_TIME_SPENDER_PATH)
                .getPathString());
    assertThat(realCpuTimeSpenderFile.exists()).isTrue();

    Path execRootCpuTimeSpenderPath = execRoot.getRelative("spend-cpu-time");
    File execRootCpuTimeSpenderFile = execRootCpuTimeSpenderPath.getPathFile();

    assertThat(execRootCpuTimeSpenderPath.exists()).isFalse();
    Files.copy(realCpuTimeSpenderFile, execRootCpuTimeSpenderFile);
    assertThat(execRootCpuTimeSpenderPath.exists()).isTrue();

    execRootCpuTimeSpenderPath.setExecutable(true);

    return execRootCpuTimeSpenderPath;
  }

  private Path getTemporaryRoot(FileSystem fs, String name) throws IOException {
    Path tempDirPath = TestUtils.createUniqueTmpDir(fs);
    assertThat(tempDirPath.exists()).isTrue();

    Path root = tempDirPath.getRelative(name);
    assertThat(root.createDirectory()).isTrue();
    assertThat(root.exists()).isTrue();

    return root;
  }

  /**
   * Returns an execRoot {@link Path} inside a new temporary directory.
   *
   * <p>The temporary directory will be automatically deleted on exit.
   */
  private Path getTemporaryExecRoot(FileSystem fs) throws IOException {
    return getTemporaryRoot(fs, "execRoot");
  }

  private Path getTemporaryEmbeddedBin(FileSystem fs) throws IOException {
    return getTemporaryRoot(fs, "embedded_bin");
  }

  @Test
  public void hasExecutionStatistics() throws Exception {
    // TODO(b/62588075) Currently no process-wrapper or execution statistics support in Windows.
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = new UnixFileSystem(DigestHashFunction.SHA256, /*hashAttributeName=*/ "");

    ExecutionOptions executionOptions = Options.getDefaults(ExecutionOptions.class);
    LocalExecutionOptions options = Options.getDefaults(LocalExecutionOptions.class);

    int minimumWallTimeToSpendInMs = 10 * 1000;

    int minimumUserTimeToSpendInMs = minimumWallTimeToSpendInMs;
    // Under normal loads we should be able to use a much lower bound for maxUserTime, but be
    // generous here in case of hardware issues.
    int maximumUserTimeToSpendInMs = minimumUserTimeToSpendInMs + 20 * 1000;

    int minimumSystemTimeToSpendInMs = 0;
    // Under normal loads we should be able to use a much lower bound for maxSysTime, but be
    // generous here in case of hardware issues.
    int maximumSystemTimeToSpendInMs = minimumSystemTimeToSpendInMs + 20 * 1000;

    Path execRoot = getTemporaryExecRoot(fs);
    Path embeddedBinaries = getTemporaryEmbeddedBin(fs);
    BinTools binTools =
        BinTools.forEmbeddedBin(embeddedBinaries, ImmutableList.of("process-wrapper"));
    Path processWrapperPath = binTools.getEmbeddedPath("process-wrapper");
    copyProcessWrapperIntoExecRoot(processWrapperPath);
    Path cpuTimeSpenderPath = copyCpuTimeSpenderIntoExecRoot(execRoot);

    LocalSpawnRunner runner =
        new LocalSpawnRunner(
            execRoot,
            executionOptions,
            options,
            resourceManager,
            LocalSpawnRunnerTest::keepLocalEnvUnchanged,
            binTools,
            new ProcessWrapper(
                processWrapperPath, /* killDelay= */ Duration.ZERO, /* gracefulSigterm= */ false),
            Mockito.mock(RunfilesTreeUpdater.class));

    Spawn spawn =
        new SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                String.valueOf(minimumUserTimeToSpendInMs / 1000L),
                String.valueOf(minimumSystemTimeToSpendInMs / 1000L))
            .build();

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/dev/null"), fs.getPath("/dev/null"));
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ZERO);

    SpawnResult spawnResult = runner.exec(spawn, context);

    assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS);
    assertThat(spawnResult.exitCode()).isEqualTo(0);
    assertThat(spawnResult.setupSuccess()).isTrue();
    assertThat(spawnResult.getExecutorHostName()).isEqualTo(NetUtil.getCachedShortHostName());

    assertThat(spawnResult.getWallTimeInMs()).isNotNull();
    assertThat(spawnResult.getWallTimeInMs()).isAtLeast(minimumWallTimeToSpendInMs);
    // Under heavy starvation, max wall time could be anything, so don't check it here.
    assertThat(spawnResult.getUserTimeInMs()).isNotNull();
    assertThat(spawnResult.getUserTimeInMs()).isAtLeast(minimumUserTimeToSpendInMs);
    assertThat(spawnResult.getUserTimeInMs()).isAtMost(maximumUserTimeToSpendInMs);
    assertThat(spawnResult.getSystemTimeInMs()).isNotNull();
    assertThat(spawnResult.getSystemTimeInMs()).isAtLeast(minimumSystemTimeToSpendInMs);
    assertThat(spawnResult.getSystemTimeInMs()).isAtMost(maximumSystemTimeToSpendInMs);
    assertThat(spawnResult.getNumBlockOutputOperations()).isAtLeast(0L);
    assertThat(spawnResult.getNumBlockInputOperations()).isAtLeast(0L);
    assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isAtLeast(0L);
  }

  // Check that relative paths in the Spawn are absolutized relative to the execroot passed to the
  // LocalSpawnRunner.
  @Test
  public void relativePath() throws Exception {
    // TODO(#3536): Make this test work on Windows.
    // The Command API implicitly absolutizes the path, and we get weird paths on Windows:
    // T:\execroot\execroot\_bin\process-wrapper
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    FileSystem fs = setupEnvironmentForFakeExecution();

    SubprocessFactory factory = mock(SubprocessFactory.class);
    ArgumentCaptor<SubprocessBuilder> captor = ArgumentCaptor.forClass(SubprocessBuilder.class);
    when(factory.create(captor.capture())).thenReturn(new FinishedSubprocess(0));
    SubprocessBuilder.setDefaultSubprocessFactory(factory);

    LocalSpawnRunner runner =
        new TestedLocalSpawnRunner(
            fs.getPath("/execroot"),
            Options.getDefaults(LocalExecutionOptions.class),
            resourceManager,
            /*processWrapper=*/ null,
            LocalSpawnRunnerTest::keepLocalEnvUnchanged);

    FileOutErr fileOutErr = new FileOutErr(fs.getPath("/out/stdout"), fs.getPath("/out/stderr"));
    Spawn spawn = new SpawnBuilder("foo/bar", "Hi!").build();
    SpawnExecutionContextForTesting context =
        new SpawnExecutionContextForTesting(spawn, fileOutErr, Duration.ofSeconds(123));
    assertThat(fs.getPath("/execroot").createDirectory()).isTrue();
    runner.exec(spawn, context);
    verify(factory).create(any(SubprocessBuilder.class));

    assertThat(captor.getValue().getArgv()).containsExactly("/execroot/foo/bar", "Hi!");
  }
}
