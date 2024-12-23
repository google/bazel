// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StreamedTestOutput}. */
@RunWith(JUnit4.class)
public class StreamedTestOutputTest {

  private final InMemoryFileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);

  @Test
  public void testEmptyFile() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    FileSystemUtils.writeContent(watchedPath, new byte[0]);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (StreamedTestOutput underTest =
        new StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile"))) {}

    assertThat(out.toByteArray()).isEmpty();
    assertThat(err.toByteArray()).isEmpty();
  }

  @Test
  public void testNoHeaderOutputsEntireFile() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    FileSystemUtils.writeContent(watchedPath, "random\nlines\n");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (StreamedTestOutput underTest =
        new StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile"))) {}

    assertThat(out.toString(StandardCharsets.UTF_8.name())).isEqualTo("random\nlines\n");
    assertThat(err.toString(StandardCharsets.UTF_8.name())).isEmpty();
  }

  @Test
  public void testOnlyOutputsContentsAfterHeaderWhenPresent() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    TestUtils.writeLines(
        watchedPath, "ignored", "lines", TestLogHelper.HEADER_DELIMITER, "included", "lines");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (StreamedTestOutput underTest =
        new StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile"))) {}

    assertThat(out.toString(StandardCharsets.UTF_8.name()))
        .isEqualTo("included" + System.lineSeparator() + "lines" + System.lineSeparator());
    assertThat(err.toString(StandardCharsets.UTF_8.name())).isEmpty();
  }

  @Test
  public void testWatcherDoneAfterClose() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    TestUtils.writeLines(watchedPath, TestLogHelper.HEADER_DELIMITER, "x".repeat(10 << 20));
    StreamedTestOutput underTest =
        new StreamedTestOutput(
            OutErr.create(ByteStreams.nullOutputStream(), ByteStreams.nullOutputStream()),
            fileSystem.getPath("/myfile"));
    underTest.close();
    assertThat(underTest.getFileWatcher().isAlive()).isFalse();
  }

  @Test
  public void testInterruptWaitsForWatcherToClose() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    TestUtils.writeLines(watchedPath, TestLogHelper.HEADER_DELIMITER, "x".repeat(10 << 20));

    StreamedTestOutput underTest =
        new StreamedTestOutput(
            OutErr.create(ByteStreams.nullOutputStream(), ByteStreams.nullOutputStream()),
            fileSystem.getPath("/myfile"));
    try {
      Thread.currentThread().interrupt();
      underTest.close();
      assertThat(underTest.getFileWatcher().isAlive()).isFalse();
    } finally {
      // Both checks that the interrupt bit was reset and clears it for later tests.
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  @Test
  public void testOutputsFileWithHeaderRegardlessOfInterrupt() throws IOException {
    Path watchedPath = fileSystem.getPath("/myfile");
    FileSystemUtils.writeContent(watchedPath, "blahblahblah");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    StreamedTestOutput underTest =
        new StreamedTestOutput(OutErr.create(out, err), fileSystem.getPath("/myfile"));
    try {
      Thread.currentThread().interrupt();
      underTest.close();
      assertThat(underTest.getFileWatcher().isAlive()).isFalse();
    } finally {
      // Both checks that the interrupt bit was reset and clears it for later tests.
      assertThat(Thread.interrupted()).isTrue();
    }

    assertThat(out.toString(StandardCharsets.UTF_8.name())).isEqualTo("blahblahblah");
    assertThat(err.toString(StandardCharsets.UTF_8.name())).isEmpty();
  }
}
