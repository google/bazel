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
package com.google.devtools.build.lib.actions.cache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PersistentStringIndexer}. */
@RunWith(JUnit4.class)
public final class PersistentStringIndexerTest {

  private static class ManualClock implements Clock {
    private long currentTime = 0L;

    ManualClock() {}

    @Override
    public long currentTimeMillis() {
      throw new AssertionError("unexpected method call");
    }

    @Override
    public long nanoTime() {
      return currentTime;
    }

    void advance(long time) {
      currentTime += time;
    }
  }

  private final Map<Integer, String> mappings = new ConcurrentHashMap<>();
  private final Scratch scratch = new Scratch();
  private final ManualClock clock = new ManualClock();
  private final Path dataPath = scratch.resolve("/cache/test.dat");
  private final Path journalPath = scratch.resolve("/cache/test.journal");

  private PersistentStringIndexer indexer;

  @Before
  public void createIndexer() throws Exception {
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
  }

  private void assertSize(int expected) {
    assertThat(indexer.size()).isEqualTo(expected);
  }

  private void assertIndex(int expected, String s) {
    int index = indexer.getOrCreateIndex(s);
    assertThat(index).isEqualTo(expected);
    mappings.put(expected, s);
  }

  private void assertContent() {
    for (int i = 0; i < indexer.size(); i++) {
      if (mappings.get(i) != null) {
        assertThat(mappings).containsEntry(i, indexer.getStringForIndex(i));
      }
    }
  }

  private void setupTestContent() {
    assertSize(0);
    assertIndex(0, "abcdefghi");  // Create leafs
    assertIndex(1, "abcdefjkl");
    assertIndex(2, "abcdefmno");
    assertIndex(3, "abcdefjklpr");
    assertIndex(3, "abcdefjklpr");
    assertIndex(4, "abcdstr");
    assertIndex(5, "012345");
    assertSize(6);
    assertIndex(6, "abcdef");  // Validate inner nodes
    assertIndex(7, "abcd");
    assertIndex(8, "");
    assertSize(9);
    assertContent();
  }

  /**
   * Writes lots of entries with labels "fooconcurrent[int]" at the same time. The set of labels
   * written is deterministic, but the label:index mapping is not.
   */
  private void writeLotsOfEntriesConcurrently(int numToWrite) throws InterruptedException {
    int numThreads = 10;
    CountDownLatch synchronizerLatch = new CountDownLatch(numThreads);

    TestRunnable indexAdder =
        () -> {
          for (int i = 0; i < numToWrite; i++) {
            synchronizerLatch.countDown();
            synchronizerLatch.await();

            String value = "fooconcurrent" + i;
            mappings.put(indexer.getOrCreateIndex(value), value);
          }
        };

    Collection<TestThread> threads = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      TestThread thread = new TestThread(indexAdder);
      thread.start();
      threads.add(thread);
    }

    for (TestThread thread : threads) {
      thread.joinAndAssertState(0);
    }
  }

  @Test
  public void returnsSameIntegerInstance() {
    int n = 1000; // Greater than the default java.lang.Integer.IntegerCache.high of 127.
    for (int i = 0; i < n; i++) {
      String s = "a".repeat(i);
      Integer index = indexer.getOrCreateIndex(s);
      assertThat(indexer.getIndex(s)).isSameInstanceAs(index);
    }
  }

  @Test
  public void unindexedStringReturnsNull() {
    assertThat(indexer.getIndex("absent")).isNull();
  }

  @Test
  public void testNormalOperation() throws Exception {
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();
    setupTestContent();
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();

    clock.advance(4);
    assertIndex(9, "xyzqwerty"); // This should flush journal to disk.
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isTrue();

    indexer.save(); // Successful save will remove journal file.
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();

    // Now restore data from file and verify it.
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
    assertThat(journalPath.exists()).isFalse();
    clock.advance(4);
    assertSize(10);
    assertContent();
    assertThat(journalPath.exists()).isFalse();
  }

  @Test
  public void testJournalRecoveryWithoutMainDataFile() throws Exception {
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();
    setupTestContent();
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();

    clock.advance(4);
    assertIndex(9, "abc1234"); // This should flush journal to disk.
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isTrue();

    // Now restore data from file and verify it. All data should be restored from journal;
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();
    clock.advance(4);
    assertSize(10);
    assertContent();
    assertThat(journalPath.exists()).isFalse();
  }

  @Test
  public void testJournalRecovery() throws Exception {
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();
    setupTestContent();
    indexer.save();
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();
    long oldDataFileLen = dataPath.getFileSize();

    clock.advance(4);
    assertIndex(9, "another record"); // This should flush journal to disk.
    assertSize(10);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isTrue();

    // Now restore data from file and verify it. All data should be restored from journal;
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();
    assertThat(dataPath.getFileSize())
        .isGreaterThan(oldDataFileLen); // data file should have been updated
    clock.advance(4);
    assertSize(10);
    assertContent();
    assertThat(journalPath.exists()).isFalse();
  }

  @Test
  public void testConcurrentWritesJournalRecovery() throws Exception {
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();
    setupTestContent();
    indexer.save();
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();
    long oldDataFileLen = dataPath.getFileSize();

    int size = indexer.size();
    int numToWrite = 50000;
    writeLotsOfEntriesConcurrently(numToWrite);
    assertThat(journalPath.exists()).isFalse();
    clock.advance(4);
    assertIndex(size + numToWrite, "another record"); // This should flush journal to disk.
    assertSize(size + numToWrite + 1);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isTrue();

    // Now restore data from file and verify it. All data should be restored from journal;
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();
    assertThat(dataPath.getFileSize())
        .isGreaterThan(oldDataFileLen); // data file should have been updated
    clock.advance(4);
    assertSize(size + numToWrite + 1);
    assertContent();
    assertThat(journalPath.exists()).isFalse();
  }

  @Test
  public void testCorruptedJournal() throws Exception {
    journalPath.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(journalPath, "bogus content");
    IOException e =
        assertThrows(
            IOException.class,
            () -> indexer = PersistentStringIndexer.create(dataPath, journalPath, clock));
    assertThat(e).hasMessageThat().contains("too short: Only 13 bytes");

    journalPath.delete();
    setupTestContent();
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();

    clock.advance(4);
    assertIndex(9, "abc1234"); // This should flush journal to disk.
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isTrue();

    byte[] journalContent = FileSystemUtils.readContent(journalPath);

    // Now restore data from file and verify it. All data should be restored from journal;
    indexer = PersistentStringIndexer.create(dataPath, journalPath, clock);
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();

    // Now put back truncated journal. We should get an error.
    assertThat(dataPath.delete()).isTrue();
    FileSystemUtils.writeContent(
        journalPath, Arrays.copyOf(journalContent, journalContent.length - 1));
    assertThrows(
        EOFException.class,
        () -> indexer = PersistentStringIndexer.create(dataPath, journalPath, clock));

    // Corrupt the journal with a negative size value.
    byte[] journalCopy = journalContent.clone();
    // Flip this bit to make the key size negative.
    journalCopy[95] = -2;
    FileSystemUtils.writeContent(journalPath, journalCopy);
    e =
        assertThrows(
            IOException.class,
            () -> indexer = PersistentStringIndexer.create(dataPath, journalPath, clock));
    assertThat(e).hasMessageThat().contains("corrupt key length");

    // Now put back corrupted journal. We should get an error.
    journalContent[journalContent.length - 13] = 100;
    FileSystemUtils.writeContent(journalPath, journalContent);
    assertThrows(
        IOException.class,
        () -> indexer = PersistentStringIndexer.create(dataPath, journalPath, clock));
  }

  @Test
  public void testDupeIndexCorruption() throws Exception {
    setupTestContent();
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();

    assertIndex(9, "abc1234"); // This should flush journal to disk.
    indexer.save();
    assertThat(dataPath.exists()).isTrue();
    assertThat(journalPath.exists()).isFalse();

    byte[] content = FileSystemUtils.readContent(dataPath);

    // We remove the data file, and instead create a corrupt journal.
    //
    // The journal has a header followed by a sequence of (String, int) pairs, where each int is a
    // unique value. The String is encoded by the length (as an int), and the int is simply encoded
    // as an int. Note that the DataOutputStream class uses big endian by default, so the low-order
    // bits are at the end.
    //
    // For the purpose of this test, we want to make the journal contain two entries with the same
    // index (which is illegal). The PersistentStringIndexer assigns int values in the usual order,
    // starting with zero, and it now contains 9 entries. We simply change the last entry to an
    // index that is guaranteed to already exist. If it is the index 1, we change it to 2, otherwise
    // we change it to 1 - in both cases, the code currently guarantees that the duplicate comes
    // earlier in the stream.
    assertThat(dataPath.delete()).isTrue();
    content[content.length - 1] = content[content.length - 1] == 1 ? (byte) 2 : (byte) 1;
    FileSystemUtils.writeContent(journalPath, content);

    IOException e =
        assertThrows(
            IOException.class,
            () -> indexer = PersistentStringIndexer.create(dataPath, journalPath, clock));
    assertThat(e).hasMessageThat().contains("Corrupted filename index has duplicate entry");
  }

  @Test
  public void testDeferredIOFailure() throws Exception {
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();
    setupTestContent();
    assertThat(dataPath.exists()).isFalse();
    assertThat(journalPath.exists()).isFalse();

    // Ensure that journal cannot be saved.
    journalPath.createDirectoryAndParents();

    clock.advance(4);
    assertIndex(9, "abc1234"); // This should flush journal to disk (and fail at that).
    assertThat(dataPath.exists()).isFalse();

    // Subsequent updates should succeed even though journaling is disabled at this point.
    clock.advance(4);
    assertIndex(10, "another record");
    IOException e = assertThrows(IOException.class, () -> indexer.save());
    assertThat(e).hasMessageThat().contains(journalPath.getPathString() + " (Is a directory)");
  }
}
