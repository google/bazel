// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs.inmemoryfs;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Lists;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SymlinkAwareFileSystemTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link InMemoryFileSystem}.
 *
 * <p>Note that most tests are inherited from {@link SymlinkAwareFileSystemTest} and ancestors.
 */
public final class InMemoryFileSystemTest extends SymlinkAwareFileSystemTest {

  @Override
  public FileSystem getFreshFileSystem(DigestHashFunction digestHashFunction) {
    return new InMemoryFileSystem(BlazeClock.instance(), digestHashFunction);
  }

  @Override
  public void destroyFileSystem(FileSystem fileSystem) {}

  private static final int NUM_THREADS_FOR_CONCURRENCY_TESTS = 10;
  private static final String TEST_FILE_DATA = "data";

  /**
   * Writes the given data to the given file.
   */
  private static void writeToFile(Path path, String data) throws IOException {
    try (OutputStream out = path.getOutputStream()) {
      out.write(data.getBytes(Charset.defaultCharset()));
    }
  }

  /**
   * Tests concurrent creation of a substantial tree hierarchy including files, directories,
   * symlinks, file contents, and permissions.
   */
  @Test
  public void testConcurrentTreeConstruction() throws Exception {
    int n = 10000;
    AtomicInteger baseSelector = new AtomicInteger();

    // 1) Define the intended path structure.
    TestRunnable pathCreator =
        () -> {
          Path base = testFS.getPath("/base" + baseSelector.getAndIncrement());
          base.createDirectory();

          for (int i = 0; i < n; i++) {
            Path subdir1 = base.getRelative("subdir1_" + i);
            subdir1.createDirectory();
            Path subdir2 = base.getRelative("subdir2_" + i);
            subdir2.createDirectory();

            Path file = base.getRelative("somefile" + i);
            writeToFile(file, TEST_FILE_DATA);

            subdir1.setReadable(true);
            subdir2.setReadable(false);
            file.setReadable(true);

            subdir1.setWritable(false);
            subdir2.setWritable(true);
            file.setWritable(false);

            subdir1.setExecutable(false);
            subdir2.setExecutable(true);
            file.setExecutable(false);

            subdir1.setLastModifiedTime(100);
            subdir2.setLastModifiedTime(200);
            file.setLastModifiedTime(300);

            Path symlink = base.getRelative("symlink" + i);
            symlink.createSymbolicLink(file);
          }
        };

    // 2) Construct the tree.
    Collection<TestThread> threads =
        Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(pathCreator);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }

    // 3) Define the validation logic.
    TestRunnable pathValidator =
        () -> {
          Path base = testFS.getPath("/base" + baseSelector.getAndIncrement());
          assertThat(base.exists()).isTrue();
          assertThat(base.getRelative("notreal").exists()).isFalse();

          for (int i = 0; i < n; i++) {
            Path subdir1 = base.getRelative("subdir1_" + i);
            assertThat(subdir1.exists()).isTrue();
            assertThat(subdir1.isDirectory()).isTrue();
            assertThat(subdir1.isReadable()).isTrue();
            assertThat(subdir1.isWritable()).isFalse();
            assertThat(subdir1.isExecutable()).isFalse();
            assertThat(subdir1.getLastModifiedTime()).isEqualTo(100);

            Path subdir2 = base.getRelative("subdir2_" + i);
            assertThat(subdir2.exists()).isTrue();
            assertThat(subdir2.isDirectory()).isTrue();
            assertThat(subdir2.isReadable()).isFalse();
            assertThat(subdir2.isWritable()).isTrue();
            assertThat(subdir2.isExecutable()).isTrue();
            assertThat(subdir2.getLastModifiedTime()).isEqualTo(200);

            Path file = base.getRelative("somefile" + i);
            assertThat(file.exists()).isTrue();
            assertThat(file.isFile()).isTrue();
            assertThat(file.isReadable()).isTrue();
            assertThat(file.isWritable()).isFalse();
            assertThat(file.isExecutable()).isFalse();
            assertThat(file.getLastModifiedTime()).isEqualTo(300);
            try (BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(file.getInputStream(), Charset.defaultCharset()))) {
              assertThat(reader.readLine()).isEqualTo(TEST_FILE_DATA);
              assertThat(reader.readLine()).isNull();
            }

            Path symlink = base.getRelative("symlink" + i);
            assertThat(symlink.exists()).isTrue();
            assertThat(symlink.isSymbolicLink()).isTrue();
            assertThat(symlink.readSymbolicLink()).isEqualTo(file.asFragment());
          }
        };

    // 4) Validate the results.
    baseSelector.set(0);
    threads = Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(pathValidator);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }
  }

  /**
   * Tests concurrent creation of many files, all within the same directory.
   */
  @Test
  public void testConcurrentDirectoryConstruction() throws Exception {
    int n = 10000;
    AtomicInteger baseSelector = new AtomicInteger();

    // 1) Define the intended path structure.
    TestRunnable pathCreator =
        () -> {
          int threadId = baseSelector.getAndIncrement();
          Path base = testFS.getPath("/common_dir");
          base.createDirectory();

          for (int i = 0; i < n; i++) {
            Path file = base.getRelative("somefile_" + threadId + "_" + i);
            writeToFile(file, TEST_FILE_DATA);
            file.setReadable(i % 2 == 0);
            file.setWritable(i % 3 == 0);
            file.setExecutable(i % 4 == 0);
            file.setLastModifiedTime(i);
            Path symlink = base.getRelative("symlink_" + threadId + "_" + i);
            symlink.createSymbolicLink(file);
          }
        };

    // 2) Create the files.
    Collection<TestThread> threads =
        Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(pathCreator);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }

    // 3) Define the validation logic.
    TestRunnable pathValidator =
        () -> {
          int threadId = baseSelector.getAndIncrement();
          Path base = testFS.getPath("/common_dir");
          assertThat(base.exists()).isTrue();

          for (int i = 0; i < n; i++) {
            Path file = base.getRelative("somefile_" + threadId + "_" + i);
            assertThat(file.exists()).isTrue();
            assertThat(file.isFile()).isTrue();
            assertThat(file.isReadable()).isEqualTo(i % 2 == 0);
            assertThat(file.isWritable()).isEqualTo(i % 3 == 0);
            assertThat(file.isExecutable()).isEqualTo(i % 4 == 0);
            assertThat(file.getLastModifiedTime()).isEqualTo(i);
            if (file.isReadable()) {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(file.getInputStream(), Charset.defaultCharset()))) {
                assertThat(reader.readLine()).isEqualTo(TEST_FILE_DATA);
                assertThat(reader.readLine()).isNull();
              }
            }

            Path symlink = base.getRelative("symlink_" + threadId + "_" + i);
            assertThat(symlink.exists()).isTrue();
            assertThat(symlink.isSymbolicLink()).isTrue();
            assertThat(symlink.readSymbolicLink()).isEqualTo(file.asFragment());
          }
        };

    // 4) Validate the results.
    baseSelector.set(0);
    threads = Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(pathValidator);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }
  }

  /**
   * Tests concurrent file deletion.
   */
  @Test
  public void testConcurrentDeletion() throws Exception {
    int n = 10000;
    AtomicInteger baseSelector = new AtomicInteger();

    Path base = testFS.getPath("/base");
    base.createDirectory();

    // 1) Create a bunch of files.
    for (int i = 0; i < n; i++) {
      writeToFile(base.getRelative("file" + i), TEST_FILE_DATA);
    }

    // 2) Define our deletion strategy.
    TestRunnable fileDeleter =
        () -> {
          for (int i = 0; i < n / NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
            int whichFile = baseSelector.getAndIncrement();
            Path file = base.getRelative("file" + whichFile);
            if (whichFile % 25 != 0) {
              assertThat(file.delete()).isTrue();
            } else {
              // Throw another concurrent access point into the mix.
              file.setExecutable(whichFile % 2 == 0);
            }
            assertThat(base.getRelative("doesnotexist" + whichFile).delete()).isFalse();
          }
        };

    // 3) Delete some files.
    Collection<TestThread> threads =
        Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(fileDeleter);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }

    // 4) Check the results.
    for (int i = 0; i < n; i++) {
      Path file = base.getRelative("file" + i);
      if (i % 25 != 0) {
        assertThat(file.exists()).isFalse();
      } else {
        assertThat(file.exists()).isTrue();
        assertThat(file.isExecutable()).isEqualTo(i % 2 == 0);
      }
    }
  }

  /**
   * Tests concurrent file renaming.
   */
  @Test
  public void testConcurrentRenaming() throws Exception {
    int n = 10000;
    AtomicInteger baseSelector = new AtomicInteger();

    Path base = testFS.getPath("/base");
    base.createDirectory();

    // 1) Create a bunch of files.
    for (int i = 0; i < n; i++) {
      writeToFile(base.getRelative("file" + i), TEST_FILE_DATA);
    }

    // 2) Define our renaming strategy.
    TestRunnable fileDeleter =
        () -> {
          for (int i = 0; i < n / NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
            int whichFile = baseSelector.getAndIncrement();
            Path file = base.getRelative("file" + whichFile);
            if (whichFile % 25 != 0) {
              Path newName = base.getRelative("newname" + whichFile);
              file.renameTo(newName);
            } else {
              // Throw another concurrent access point into the mix.
              file.setExecutable(whichFile % 2 == 0);
            }
            assertThat(base.getRelative("doesnotexist" + whichFile).delete()).isFalse();
          }
        };

    // 3) Rename some files.
    Collection<TestThread> threads =
        Lists.newArrayListWithCapacity(NUM_THREADS_FOR_CONCURRENCY_TESTS);
    for (int i = 0; i < NUM_THREADS_FOR_CONCURRENCY_TESTS; i++) {
      TestThread thread = new TestThread(fileDeleter);
      thread.start();
      threads.add(thread);
    }
    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }

    // 4) Check the results.
    for (int i = 0; i < n; i++) {
      Path file = base.getRelative("file" + i);
      if (i % 25 != 0) {
        assertThat(file.exists()).isFalse();
        assertThat(base.getRelative("newname" + i).exists()).isTrue();
      } else {
        assertThat(file.exists()).isTrue();
        assertThat(file.isExecutable()).isEqualTo(i % 2 == 0);
      }
    }
  }

  @Test
  public void testEloop() throws Exception {
    // The test assumes that aName and bName is not a prefix of the workingDir.
    String aName = "/" + UUID.randomUUID();
    String bName = "/" + UUID.randomUUID();

    Path a = testFS.getPath(aName);
    Path b = testFS.getPath(bName);
    a.createSymbolicLink(PathFragment.create(bName));
    b.createSymbolicLink(PathFragment.create(aName));
    IOException e = assertThrows(IOException.class, a::stat);
    assertThat(e).hasMessageThat().isEqualTo(aName + " (Too many levels of symbolic links)");
  }

  @Test
  public void testEloopSelf() throws Exception {
    // The test assumes that aName is not a prefix of the workingDir.
    String aName = "/" + UUID.randomUUID();

    Path a = testFS.getPath(aName);
    a.createSymbolicLink(PathFragment.create(aName));
    IOException e = assertThrows(IOException.class, a::stat);
    assertThat(e).hasMessageThat().isEqualTo(aName + " (Too many levels of symbolic links)");
  }

  @Test
  public void getxattr_symlink_returnsNull() throws Exception {
    Path dir = testFS.getPath("/any/dir");
    dir.createDirectoryAndParents();
    FileSystemUtils.writeContent(dir.getRelative("file.txt"), "contents");
    Path symlink = dir.getRelative("link");
    symlink.createSymbolicLink(PathFragment.create("file.txt"));

    assertThat(symlink.getxattr("some.xattr")).isNull();
  }

  @Test
  public void testLargeFile() throws Exception {
    Path file = testFS.getPath("/file");

    String largeStr = "abcdefghijklmnopqrstuvwxyz".repeat(1000000);
    FileSystemUtils.writeContent(file, largeStr);
    assertThat(FileSystemUtils.readContentToString(file)).isEqualTo(largeStr);
  }
}
