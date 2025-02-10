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
package com.google.devtools.build.lib.actions.cache;

import static java.lang.Math.max;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadSafe;
import com.google.devtools.build.lib.util.PersistentMap;
import com.google.devtools.build.lib.util.StringIndexer;
import com.google.devtools.build.lib.vfs.Path;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Persistent implementation of {@link StringIndexer}.
 *
 * <p>This class is backed by a {@link PersistentMap} that holds one direction of the
 * canonicalization mapping. The other direction is handled purely in memory and reconstituted at
 * load-time.
 *
 * <p>Thread-safety is ensured by locking on all mutating operations. Read-only operations are not
 * locked.
 */
@ConditionallyThreadSafe // Each instance must be instantiated with a different dataPath.
final class PersistentStringIndexer implements StringIndexer {

  private static final int INITIAL_CAPACITY = 8192;

  /** Instantiates and loads instance of the persistent string indexer. */
  static PersistentStringIndexer create(Path dataPath, Path journalPath, Clock clock)
      throws IOException {
    PersistentIndexMap stringToInt = new PersistentIndexMap(dataPath, journalPath, clock);

    // INITIAL_CAPACITY or the next power of two greater than the size.
    int capacity = max(INITIAL_CAPACITY, Integer.highestOneBit(stringToInt.size()) << 1);

    var intToString = new AtomicReferenceArray<String>(capacity);
    for (Map.Entry<String, Integer> entry : stringToInt.entrySet()) {
      int index = entry.getValue();
      if (index < 0 || index >= capacity) {
        throw new IOException(
            String.format(
                "Corrupted filename index %d out of bounds for length %d (map size %d)",
                index, capacity, stringToInt.size()));
      }
      if (intToString.getAndSet(index, entry.getKey()) != null) {
        throw new IOException("Corrupted filename index has duplicate entry: " + entry.getKey());
      }
    }
    return new PersistentStringIndexer(stringToInt, intToString);
  }

  private final ReentrantLock lock = new ReentrantLock();

  // These two fields act similarly to a (synchronized) BiMap. Mutating operations are performed in
  // synchronized blocks. Reads are done lock-free.
  private final PersistentIndexMap stringToInt;
  private volatile AtomicReferenceArray<String> intToString;

  private PersistentStringIndexer(
      PersistentIndexMap stringToInt, AtomicReferenceArray<String> intToString) {
    this.stringToInt = stringToInt;
    this.intToString = intToString;
  }

  @Override
  public void clear() {
    lock.lock();
    try {
      stringToInt.clear();
      intToString = new AtomicReferenceArray<>(INITIAL_CAPACITY);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    return stringToInt.size();
  }

  @Override
  public Integer getOrCreateIndex(String s) {
    Integer i = stringToInt.get(s);
    if (i != null) {
      return i;
    }
    s = s.intern();
    lock.lock();
    try {
      i = stringToInt.size();
      Integer existing = stringToInt.putIfAbsent(s, i);
      if (existing != null) {
        return existing; // Another thread won the race.
      }
      int capacity = intToString.length();
      if (i == capacity) {
        intToString = copyOf(intToString, capacity * 2);
      }
      intToString.set(i, s);
      return i;
    } finally {
      lock.unlock();
    }
  }

  private static AtomicReferenceArray<String> copyOf(
      AtomicReferenceArray<String> oldArray, int newCapacity) {
    var newArray = new AtomicReferenceArray<String>(newCapacity);
    for (int j = 0; j < oldArray.length(); j++) {
      newArray.setPlain(j, oldArray.getPlain(j));
    }
    return newArray;
  }

  @Override
  @Nullable
  public Integer getIndex(String s) {
    return stringToInt.get(s);
  }

  @Override
  @Nullable
  public String getStringForIndex(Integer i) {
    if (i < 0) {
      return null;
    }
    var snapshot = intToString;
    return i < snapshot.length() ? snapshot.get(i) : null;
  }

  /** Saves index data to the file. */
  long save() throws IOException {
    lock.lock();
    try {
      return stringToInt.save();
    } finally {
      lock.unlock();
    }
  }

  /** Flushes the journal. */
  void flush() {
    lock.lock();
    try {
      stringToInt.flush();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    lock.lock();
    try {
      StringBuilder builder = new StringBuilder();
      builder.append("size = ").append(size()).append("\n");
      for (Map.Entry<String, Integer> entry : stringToInt.entrySet()) {
        builder.append(entry.getKey()).append(" <==> ").append(entry.getValue()).append("\n");
      }
      return builder.toString();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Persistent metadata map. Used as a backing map to provide a persistent implementation of the
   * metadata cache.
   */
  private static final class PersistentIndexMap extends PersistentMap<String, Integer> {
    private static final int VERSION = 0x01;
    private static final long SAVE_INTERVAL_NS = 3L * 1000 * 1000 * 1000;

    private final Clock clock;
    private long nextUpdate;

    PersistentIndexMap(Path mapFile, Path journalFile, Clock clock) throws IOException {
      super(VERSION, new ConcurrentHashMap<>(INITIAL_CAPACITY), mapFile, journalFile);
      this.clock = clock;
      nextUpdate = clock.nanoTime();
      load(/* failFast= */ true);
    }

    @Override
    protected boolean updateJournal() {
      long time = clock.nanoTime();
      if (SAVE_INTERVAL_NS == 0L || time > nextUpdate) {
        nextUpdate = time + SAVE_INTERVAL_NS;
        return true;
      }
      return false;
    }

    @Override
    public Integer remove(Object object) {
      throw new UnsupportedOperationException();
    }

    void flush() {
      forceFlush();
    }

    @Override
    protected String readKey(DataInputStream in) throws IOException {
      int length = in.readInt();
      if (length < 0) {
        throw new IOException("corrupt key length: " + length);
      }
      byte[] content = new byte[length];
      in.readFully(content);
      return new String(content, UTF_8);
    }

    @Override
    protected Integer readValue(DataInputStream in) throws IOException {
      return in.readInt();
    }

    @Override
    protected void writeKey(String key, DataOutputStream out) throws IOException {
      byte[] content = key.getBytes(UTF_8);
      out.writeInt(content.length);
      out.write(content);
    }

    @Override
    protected void writeValue(Integer value, DataOutputStream out) throws IOException {
      out.writeInt(value);
    }
  }
}
