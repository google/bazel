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
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the command class with large inputs
 *
 */
@RunWith(JUnit4.class)
public class CommandLargeInputsTest {

  @Before
  public final void configureLogger() throws Exception  {
    // enable all log statements to ensure there are no problems with
    // logging code
    Logger.getLogger("com.google.devtools.build.lib.shell.Command").setLevel(Level.FINEST);
  }

  private byte[] getRandomBytes() {
    byte[] randomBytes;
    final Random rand = new Random(0xdeadbeef);
    randomBytes = new byte[10000];
    rand.nextBytes(randomBytes);
    return randomBytes;
  }

  private byte[] getAllByteValues() {
    byte[] allByteValues = new byte[Byte.MAX_VALUE - Byte.MIN_VALUE];
    for(int i = 0; i < allByteValues.length; i++) {
      allByteValues[i] = (byte) (i + Byte.MIN_VALUE);
    }
    return allByteValues;
  }

  @Test
  public void testCatRandomBinaryToOutputStream() throws Exception {
    final Command command = new Command(new String[] {"cat"});
    byte[] randomBytes = getRandomBytes();
    ByteArrayInputStream in = new ByteArrayInputStream(randomBytes);

    CommandResult result =
        command.executeAsync(in, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0);
    TestUtil.assertArrayEquals(randomBytes, result.getStdout());
    assertThat(result.getStderr()).isEmpty();
   }

  @Test
  public void testCatRandomBinaryToErrorStream() throws Exception {
    final Command command = new Command(new String[] {"/bin/sh", "-c", "cat >&2"});
    byte[] randomBytes = getRandomBytes();
    ByteArrayInputStream in = new ByteArrayInputStream(randomBytes);

    CommandResult result =
        command.executeAsync(in, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0);
    TestUtil.assertArrayEquals(randomBytes, result.getStderr());
    assertThat(result.getStdout()).isEmpty();
   }

  @Test
  public void testCatRandomBinaryFromInputStreamToOutputStream() throws Exception {
    final Command command = new Command(new String[] {"cat"});
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    byte[] randomBytes = getRandomBytes();
    ByteArrayInputStream in = new ByteArrayInputStream(randomBytes);

    CommandResult result =
        command.executeAsync(in, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0);
    assertThat(err.toByteArray()).isEmpty();
    TestUtil.assertArrayEquals(randomBytes, out.toByteArray());
    assertOutAndErrNotAvailable(result);
  }

  @Test
  public void testCatRandomBinaryFromInputStreamToErrorStream() throws Exception {
    final Command command = new Command(new String[] {"/bin/sh", "-c", "cat >&2"});
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    byte[] randomBytes = getRandomBytes();
    ByteArrayInputStream in = new ByteArrayInputStream(randomBytes);

    CommandResult result =
        command.executeAsync(in, out, err, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0);
    assertThat(out.toByteArray()).isEmpty();
    TestUtil.assertArrayEquals(randomBytes, err.toByteArray());
    assertOutAndErrNotAvailable(result);
  }

  @Test
  public void testStdoutInterleavedWithStdErr() throws Exception {
    final Command command = new Command(new String[]{"/bin/bash",
      "-c", "for i in $( seq 0 999); do (echo OUT$i >&1) && (echo ERR$i  >&2); done"
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    command.execute(out, err);
    StringBuilder expectedOut = new StringBuilder();
    StringBuilder expectedErr = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      expectedOut.append("OUT").append(i).append("\n");
      expectedErr.append("ERR").append(i).append("\n");
    }
    assertThat(out.toString("UTF-8")).isEqualTo(expectedOut.toString());
    assertThat(err.toString("UTF-8")).isEqualTo(expectedErr.toString());
  }

  private void assertOutAndErrNotAvailable(final CommandResult result) {
    assertThrows(IllegalStateException.class, () -> result.getStdout());
    assertThrows(IllegalStateException.class, () -> result.getStderr());
  }

  @Test
  public void testCatAllByteValues() throws Exception {
    final Command command = new Command(new String[] {"cat"});
    byte[] allByteValues = getAllByteValues();
    ByteArrayInputStream in = new ByteArrayInputStream(allByteValues);

    CommandResult result =
        command.executeAsync(in, Command.KILL_SUBPROCESS_ON_INTERRUPT).get();
    assertThat(result.terminationStatus().getRawExitCode()).isEqualTo(0);
    assertThat(result.getStderr()).isEmpty();
    TestUtil.assertArrayEquals(allByteValues, result.getStdout());
  }
}
