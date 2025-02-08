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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.util.OS;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests of the interaction of Thread.interrupt and Command.execute.
 *
 * Read http://www.ibm.com/developerworks/java/library/j-jtp05236/
 * for background material.
 *
 * NOTE: This test is dependent on thread timings.  Under extreme machine load
 * it's possible that this test could fail spuriously or intermittently.  In
 * that case, adjust the timing constants to increase the tolerance.
 */
@RunWith(JUnit4.class)
public class InterruptibleTest {

  private final Thread mainThread = Thread.currentThread();

  // Interrupt main thread after 1 second.  Hopefully by then /bin/sleep
  // should be running.
  private final Thread interrupter =
      new Thread(
          () -> {
            try {
              Thread.sleep(1000); // 1 sec
            } catch (InterruptedException e) {
              throw new IllegalStateException("Unexpected interrupt!");
            }
            mainThread.interrupt();
          });

  private Command command;
  private Path tmpDir;

  @Before
  public final void startInterrupter() throws IOException {
    Thread.interrupted(); // side effect: clear interrupted status
    assertWithMessage("Unexpected interruption!").that(mainThread.isInterrupted()).isFalse();

    // We interrupt after 1 sec, so this gives us plenty of time for the library to notice the
    // subprocess exit.
    tmpDir = Files.createTempDirectory("script_outs");
    String dirString = tmpDir + "/";
    Path script =
        Files.createTempFile(
            "script",
            ".sh",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
    Files.write(
        script,
        ImmutableList.of(
            "echo start", "sleep 20", "touch " + dirString + "endfile", "echo end >&2"));
    this.command = new Command(new String[] {script.toString()});

    interrupter.start();
  }

  @After
  public final void waitForInterrupter() throws Exception {
    interrupter.join();
    Thread.interrupted(); // Clear interrupted status, or else other tests may fail.
  }

  /**
   * Test that interrupting a thread in an "uninterruptible" Command.execute marks the thread as
   * interrupted, and does not terminate the subprocess.
   */
  @Test
  public void uninterruptibleCommandRunsToCompletion() throws Exception {
    assumeTrue(OS.getCurrent() != OS.WINDOWS);

    CommandResult result =
        command.executeAsync(Command.NO_INPUT, Command.CONTINUE_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().success()).isTrue();
    assertThat(new String(result.getStdout(), UTF_8)).isEqualTo("start\n");
    assertThat(new String(result.getStderr(), UTF_8)).isEqualTo("end\n");
    assertThat(Files.exists(tmpDir.resolve("endfile"))).isTrue();

    // The interrupter thread should have exited about 1000ms ago.
    assertWithMessage("Interrupter thread is still alive!").that(interrupter.isAlive()).isFalse();

    // The interrupter thread should have set the main thread's interrupt flag.
    assertWithMessage("Main thread was not interrupted during command execution!")
        .that(mainThread.isInterrupted())
        .isTrue();
  }

  /**
   * Test that interrupting a thread in an "interruptible" Command.execute does terminate the
   * subprocess and throws an {@link InterruptedException}.
   */
  @Test
  public void interruptibleCommandIsInterrupted() throws CommandException {
    assumeTrue(OS.getCurrent() != OS.WINDOWS);
    FutureCommandResult result = command.executeAsync();
    assertThrows(InterruptedException.class, result::get);
    assertThat(Files.exists(tmpDir.resolve("endfile"))).isFalse();
    assertThat(result.isDone()).isTrue();
  }
}
