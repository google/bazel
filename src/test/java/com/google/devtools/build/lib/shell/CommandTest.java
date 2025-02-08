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
package com.google.devtools.build.lib.shell;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.TestConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link Command}. This test will only succeed on Linux, currently, because of its
 * non-portable nature.
 */
@RunWith(JUnit4.class)
public class CommandTest {

  // Platform-independent tests ----------------------------------------------

  @Before
  public final void configureLogger() throws Exception  {
    // Enable all log statements to ensure there are no problems with logging code.
    Logger.getLogger("com.google.devtools.build.lib.shell.Command").setLevel(Level.FINEST);
  }

  @Test
  public void testIllegalArgs() throws Exception {
    assertThrows(NullPointerException.class, () -> new Command(null));

    assertThrows(
        NullPointerException.class, () -> new Command(new String[] {"/bin/true", null}).execute());

    Command r = new Command(new String[] {"foo"});
    assertThrows(
        NullPointerException.class,
        () -> r.executeAsync((InputStream) null, Command.KILL_SUBPROCESS_ON_INTERRUPT).get());
  }

  @Test
  public void testGetters() {
    File workingDir = new File(".");
    Map<String, String> env = Collections.singletonMap("foo", "bar");
    String[] commandArgs = new String[] { "command" };
    Command command = new Command(commandArgs, env, workingDir);
    assertThat(command.getArguments()).containsExactlyElementsIn(commandArgs);
    for (String key : env.keySet()) {
      assertThat(command.getEnvironment()).containsEntry(key, env.get(key));
    }
    assertThat(command.getWorkingDirectory()).isEqualTo(workingDir);
  }

  // Platform-dependent tests ------------------------------------------------

  @Test
  public void testSimpleCommand() throws Exception {
    Command command = new Command(new String[] {"ls"});
    CommandResult result = command.execute();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(result.getStderr()).isEmpty();
    assertThat(result.getStdout().length).isGreaterThan(0);
  }

  @Test
  public void testArguments() throws Exception {
    Command command = new Command(new String[] {"echo", "foo"});
    checkSuccess(command.execute(), "foo\n");
  }

  @Test
  public void testNonEmptyEnvironment() throws Exception {
    ImmutableMap<String, String> env = ImmutableMap.of("FOO", "abc", "BAR", "def");
    Command command = new Command(new String[] {"/bin/sh", "-c", "echo $FOO $BAR"}, env, null);
    checkSuccess(command.execute(), "abc def\n");
  }

  @Test
  public void testEmptyEnvironment() throws Exception {
    // Check only that TZ was not inherited instead of verifying the entire environment.
    assertThat(Strings.nullToEmpty(System.getenv("TZ"))).isNotEmpty();
    Command command =
        new Command(new String[] {"/bin/sh", "-c", "echo $TZ"}, ImmutableMap.of(), null);
    checkSuccess(command.execute(), "\n");
  }

  @Test
  public void testInheritedEnvironment() throws Exception {
    // Check only that TZ was inherited instead of verifying the entire environment.
    assertThat(Strings.nullToEmpty(System.getenv("TZ"))).isNotEmpty();
    Command command = new Command(new String[] {"/bin/sh", "-c", "echo $TZ"}, null, null);
    checkSuccess(command.execute(), System.getenv("TZ") + "\n");
  }

  @Test
  public void testWorkingDir() throws Exception {
    Command command = new Command(new String[] {"pwd"}, null, new File("/"));
    checkSuccess(command.execute(), "/\n");
  }

  @Test
  public void testStdin() throws Exception {
    Command command = new Command(new String[] {"grep", "bar"});
    InputStream in = new ByteArrayInputStream("foobarbaz".getBytes());
    checkSuccess(
        command.executeAsync(in, Command.KILL_SUBPROCESS_ON_INTERRUPT).get(),
        "foobarbaz\n");
  }

  @Test
  public void testRawCommand() throws Exception {
    Command command = new Command(new String[] { "perl", "-e", "print 'a'x100000" });
    CommandResult result = command.execute();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(result.getStderr()).isEmpty();
    assertThat(result.getStdout().length).isGreaterThan(0);
  }

  @Test
  public void testRawCommandWithDir() throws Exception {
    Command command = new Command(new String[] { "pwd" }, null, new File("/"));
    CommandResult result = command.execute();
    checkSuccess(result, "/\n");
  }

  @Test
  public void testHugeOutput() throws Exception {
    Command command = new Command(new String[] {"perl", "-e", "print 'a'x100000"});
    CommandResult result = command.execute();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(result.getStderr()).isEmpty();
    assertThat(result.getStdout()).hasLength(100000);
  }

  @Test
  public void testNoStreamingInputForCat() throws Exception {
    Command command = new Command(new String[]{"/bin/cat"});
    ByteArrayInputStream emptyInput = new ByteArrayInputStream(new byte[0]);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandResult result = command
        .executeAsync(emptyInput, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT)
        .get();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(out.toString("UTF-8")).isEmpty();
    assertThat(err.toString("UTF-8")).isEmpty();
  }

  @Test
  public void testNoInputForCat() throws Exception {
    Command command = new Command(new String[]{"/bin/cat"});
    CommandResult result = command.execute();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(new String(result.getStdout(), "UTF-8")).isEmpty();
    assertThat(new String(result.getStderr(), "UTF-8")).isEmpty();
  }

  @Test
  public void testProvidedOutputStreamCapturesHelloWorld() throws Exception {
    String helloWorld = "Hello, world.";
    Command command = new Command(new String[]{"/bin/echo", helloWorld});
    ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
    ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
    command.execute(stdOut, stdErr);
    assertThat(stdOut.toString("UTF-8")).isEqualTo(helloWorld + "\n");
    assertThat(stdErr.toByteArray()).isEmpty();
  }

  @Test
  public void testAsynchronous() throws Exception {
    File tempFile = File.createTempFile("googlecron-test", "tmp");
    tempFile.delete();
    Command command = new Command(new String[] {"touch", tempFile.getAbsolutePath()});
    FutureCommandResult result = command.executeAsync();
    result.get();
    assertThat(tempFile.exists()).isTrue();
    assertThat(result.isDone()).isTrue();
    tempFile.delete();
  }

  @Test
  public void testAsynchronousWithOutputStreams() throws Exception {
    String helloWorld = "Hello, world.";
    Command command = new Command(new String[]{"/bin/echo", helloWorld});
    ByteArrayInputStream emptyInput = new ByteArrayInputStream(new byte[0]);
    ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
    ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
    FutureCommandResult result =
        command.executeAsync(emptyInput, stdOut, stdErr, /* killSubprocessOnInterrupt= */ false);
    result.get(); // Make sure the process actually finished
    assertThat(stdOut.toString("UTF-8")).isEqualTo(helloWorld + "\n");
    assertThat(stdErr.toByteArray()).isEmpty();
  }

  @Test
  public void testTimeout() throws Exception {
    // Sleep for 3 seconds, but timeout after 1 second.
    Command command = new Command(new String[] {"sleep", "3"}, null, null, Duration.ofSeconds(1));
    AbnormalTerminationException ate =
        assertThrows(AbnormalTerminationException.class, () -> command.execute());
    checkCommandElements(ate, "sleep", "3");
    checkATE(ate);
  }

  @Test
  public void testTimeoutDoesntFire() throws Exception {
    Command command = new Command(new String[] {"cat"}, null, null, Duration.ofSeconds(2));
    InputStream in = new ByteArrayInputStream(new byte[]{'H', 'i', '!'});
    command.executeAsync(in, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
  }

  @Test
  public void testCommandDoesNotExist() throws Exception {
    Command command = new Command(new String[]{"thisisnotreal"});
    ExecFailedException e = assertThrows(ExecFailedException.class, () -> command.execute());
    checkCommandElements(e, "thisisnotreal");
  }

  @Test
  public void testNoSuchCommand() throws Exception {
    final Command command = new Command(new String[] {"thisisnotreal"});
    assertThrows(ExecFailedException.class, () -> command.execute());
  }

  @Test
  public void testExitCodes() throws Exception {
    // 0 => success
    {
      String[] args = { "/bin/sh", "-c", "exit 0" };
      CommandResult result = new Command(args).execute();
      TerminationStatus status = result.terminationStatus();
      assertThat(status.success()).isTrue();
      assertThat(status.exited()).isTrue();
      assertThat(status.getExitCode()).isEqualTo(0);
    }

    // Every exit value in range [1-255] is reported as such (except [129-191],
    // which map to signals).
    for (int exit : new int[] { 1, 2, 3, 127, 128, 192, 255 }) {
      String[] args = {"/bin/sh", "-c", "exit " + exit};
      BadExitStatusException e =
          assertThrows(
              "Should have exited with status " + exit,
              BadExitStatusException.class,
              () -> new Command(args).execute());
      assertThat(e).hasMessageThat().isEqualTo("Process exited with status " + exit);
        checkCommandElements(e, "/bin/sh", "-c", "exit " + exit);
      TerminationStatus status = e.getResult().terminationStatus();
        assertThat(status.success()).isFalse();
        assertThat(status.exited()).isTrue();
        assertThat(status.getExitCode()).isEqualTo(exit);
      assertThat(status.toShortString()).isEqualTo("Exit " + exit);
    }

    // negative exit values are modulo 256:
    for (int exit : new int[] { -1, -2, -3 }) {
      int expected = 256 + exit;
      String[] args = {"/bin/bash", "-c", "exit " + exit};
      BadExitStatusException e =
          assertThrows(
              "Should have exited with status " + expected,
              BadExitStatusException.class,
              () -> new Command(args).execute());
      assertThat(e).hasMessageThat().isEqualTo("Process exited with status " + expected);
        checkCommandElements(e, "/bin/bash", "-c", "exit " + exit);
      TerminationStatus status = e.getResult().terminationStatus();
        assertThat(status.success()).isFalse();
        assertThat(status.exited()).isTrue();
        assertThat(status.getExitCode()).isEqualTo(expected);
      assertThat(status.toShortString()).isEqualTo("Exit " + expected);
    }
  }

  @Test
  public void testFailedWithSignal() throws Exception {
    // SIGHUP, SIGINT, SIGKILL, SIGTERM
    for (int signal : new int[] { 1, 2, 9, 15 }) {
      // Invoke a C++ program (killmyself.cc) that will die
      // with the specified signal.
      String killmyself = BlazeTestUtils.runfilesDir() + "/"
          + TestConstants.JAVATESTS_ROOT
          + "/com/google/devtools/build/lib/shell/killmyself";
      String[] args = {killmyself, "" + signal};
      AbnormalTerminationException e =
          assertThrows(
              "Expected signal " + signal,
              AbnormalTerminationException.class,
              () -> new Command(args).execute());
      assertThat(e).hasMessageThat().isEqualTo("Process terminated by signal " + signal);
        checkCommandElements(e, killmyself, "" + signal);
      TerminationStatus status = e.getResult().terminationStatus();
        assertThat(status.success()).isFalse();
        assertThat(status.exited()).isFalse();
        assertThat(status.getTerminatingSignal()).isEqualTo(signal);

        switch (signal) {
          case 1: assertThat(status.toShortString()).isEqualTo("Hangup"); break;
          case 2: assertThat(status.toShortString()).isEqualTo("Interrupt"); break;
          case 9: assertThat(status.toShortString()).isEqualTo("Killed"); break;
          case 15: assertThat(status.toShortString()).isEqualTo("Terminated"); break;
        default: // fall out
      }
    }
  }

  @Test
  public void testOnlyReadsPartialInput() throws Exception {
    // -c == --bytes, but -c also works on Darwin.
    Command command = new Command(new String[] {"head", "-c", "500"});
    OutputStream out = new ByteArrayOutputStream();
    InputStream in = new InputStream() {
      @Override
      public int read() {
        return 0; // write an unbounded amount
      }
    };

    CommandResult result =
        command.executeAsync(in, out, out, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    TerminationStatus status = result.terminationStatus();
    assertThat(status.success()).isTrue();
  }

  @Test
  public void testFlushing() throws Exception {
    final Command command = new Command(
        // On darwin, /bin/sh does not support -n for the echo builtin.
        new String[] {"/bin/bash", "-c", "echo -n Foo; sleep 0.1; echo Bar"});
    // We run this command, passing in a special output stream that records when each flush()
    // occurs. We test that a flush occurs after writing "Foo" and that another flush occurs after
    // writing "Bar\n".
    boolean[] flushed = new boolean[8];
    OutputStream out = new OutputStream() {
      private int count = 0;
      @Override
      public void write(int b) throws IOException {
        count++;
      }
      @Override
      public void flush() throws IOException {
        flushed[count] = true;
      }
    };
    command.execute(out, System.err);
    assertThat(flushed[0]).isFalse();
    assertThat(flushed[1]).isFalse(); // 'F'
    assertThat(flushed[2]).isFalse(); // 'o'
    assertThat(flushed[3]).isTrue(); // 'o'   <- expect flush here.
    assertThat(flushed[4]).isFalse(); // 'B'
    assertThat(flushed[5]).isFalse(); // 'a'
    assertThat(flushed[6]).isFalse(); // 'r'
    assertThat(flushed[7]).isTrue(); // '\n'
  }

  @Test
  public void testOutputStreamThrowsException() throws Exception {
    OutputStream out = new OutputStream () {
      @Override
      public void write(int b) throws IOException {
        throw new IOException();
      }
    };
    Command command = new Command(new String[] {"/bin/echo", "foo"});
    AbnormalTerminationException e =
        assertThrows(AbnormalTerminationException.class, () -> command.execute(out, out));
    checkCommandElements(e, "/bin/echo", "foo");
    assertThat(e).hasMessageThat().isEqualTo("java.io.IOException");
  }

  @Test
  public void testOutputStreamThrowsExceptionAndCommandFails() throws Exception {
    OutputStream out = new OutputStream () {
      @Override
      public void write(int b) throws IOException {
        throw new IOException();
      }
    };
    Command command = new Command(new String[] {"cat", "/dev/thisisnotreal"});
    AbnormalTerminationException e =
        assertThrows(AbnormalTerminationException.class, () -> command.execute(out, out));
    checkCommandElements(e, "cat", "/dev/thisisnotreal");
    TerminationStatus status = e.getResult().terminationStatus();
      // Subprocess either gets a SIGPIPE trying to write to our output stream,
      // or it exits with failure.  Both are observed, nondetermistically.
      assertThat(status.exited() ? status.getExitCode() == 1 : status.getTerminatingSignal() == 13)
          .isTrue();
    assertWithMessage(e.getMessage())
        .that(
            e.getMessage()
                .endsWith("also encountered an error while attempting " + "to retrieve output"))
        .isTrue();
  }

  private static void checkCommandElements(CommandException e,
      String... expected) {
    assertThat(e.getCommand().getArguments()).containsExactlyElementsIn(expected);
  }

  private static void checkATE(final AbnormalTerminationException ate) {
    final CommandResult result = ate.getResult();
    assertThat(result.terminationStatus().success()).isFalse();
  }

  private static void checkSuccess(final CommandResult result,
                                   final String expectedOutput) {
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(result.getStderr()).isEmpty();
    assertThat(new String(result.getStdout())).isEqualTo(expectedOutput);
  }

  @Test
  public void testRelativePath() throws Exception {
    Command command = new Command(new String[]{"relative/path/to/binary"},
        ImmutableMap.<String, String>of(),
        new File("/working/directory"));
    assertThat(command.getArguments().get(0))
        .isEqualTo("/working/directory/relative/path/to/binary");
  }
}
