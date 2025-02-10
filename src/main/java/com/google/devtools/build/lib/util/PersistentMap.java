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

package com.google.devtools.build.lib.util;

import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A map that is backed by persistent storage. It uses two files on disk for this: The first file
 * contains all the entries and gets written when invoking the {@link #save()} method. The second
 * file contains a journal of all entries that were added to or removed from the map since
 * constructing the instance of the map or the last invocation of {@link #save()} and gets written
 * after each update of the map although sub-classes are free to implement their own journal update
 * strategy.
 *
 * <p><b>Ceci n'est pas un Map</b>. Strictly speaking, the {@link Map} interface doesn't permit the
 * possibility of failure. This class uses persistence; persistence means I/O, and I/O means the
 * possibility of failure. Therefore the semantics of this may deviate from the Map contract in
 * failure cases. In particular, updates are not guaranteed to succeed. However, I/O failures are
 * guaranteed to be reported upon the subsequent call to a method that throws {@code IOException}
 * such as {@link #save}.
 *
 * <p>To populate the map entries using the previously persisted entries call {@link #load()} prior
 * to invoking any other map operation.
 *
 * <p>Like {@link Hashtable} but unlike {@link HashMap}, this class does <em>not</em> allow
 * <tt>null</tt> to be used as a key or a value.
 *
 * <p>IO failures during reading or writing the map entries to disk may result in {@link
 * AssertionError} getting thrown from the failing method.
 *
 * <p>The constructor allows passing in a version number that gets written to the files on disk and
 * checked before reading from disk. Files with an incompatible version number will be ignored. This
 * allows the client code to change the persistence format without polluting the file system name
 * space.
 */
public abstract class PersistentMap<K, V> extends ForwardingConcurrentMap<K, V> {
  private static final int MAGIC = 0x20071105;
  private static final int ENTRY_MAGIC = 0xfe;
  private static final int MIN_MAPFILE_SIZE = 16;
  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final int version;

  @GuardedBy("this")
  private final Path mapFile;

  @GuardedBy("this")
  private final Path journalFile;

  private final LinkedBlockingQueue<K> journal;
  private DataOutputStream journalOut;

  /**
   * 'dirty' is true when the in-memory representation of the map is more recent than the on-disk
   * representation.
   */
  private boolean dirty;

  /**
   * If non-null, contains the message from an {@code IOException} thrown by a previously failed
   * write. This error is deferred until the next call to a method which is able to throw an
   * exception.
   */
  private String deferredIOFailure = null;

  /**
   * 'loaded' is true when the in-memory representation is at least as recent as the on-disk
   * representation.
   */
  private boolean loaded;

  private final ConcurrentMap<K, V> delegate;

  /**
   * Creates a new PersistentMap instance using the specified backing map.
   *
   * @param version the version tag. Changing the version tag allows updating the on disk format.
   *     The map will never read from a file that was written using a different version tag.
   * @param map the backing map to use for this PersistentMap.
   * @param mapFile the file to save the map entries to.
   * @param journalFile the journal file to write entries between invocations of {@link #save()}.
   */
  protected PersistentMap(int version, ConcurrentMap<K, V> map, Path mapFile, Path journalFile) {
    this.version = version;
    journal = new LinkedBlockingQueue<>();
    this.mapFile = mapFile;
    this.journalFile = journalFile;
    delegate = map;
  }

  @Override
  protected final ConcurrentMap<K, V> delegate() {
    return delegate;
  }

  @ThreadSafe
  @Override
  @Nullable
  public V put(K key, V value) {
    V previous = delegate.put(key, value);
    journal.add(key);
    markAsDirty();
    return previous;
  }

  @ThreadSafe
  @Override
  @Nullable
  public V putIfAbsent(K key, V value) {
    V previous = delegate.putIfAbsent(key, value);
    if (previous == null) {
      journal.add(key);
      markAsDirty();
    }
    return previous;
  }

  /** Marks the map as dirty and potentially writes updated entries to the journal. */
  @ThreadSafe
  protected void markAsDirty() {
    dirty = true;
    if (updateJournal()) {
      writeJournal();
    }
  }

  /**
   * Determines if the journal should be updated. The default implementation always returns 'true',
   * but subclasses are free to override this to implement their own journal updating strategy. For
   * example it is possible to implement an update at most every five seconds using the following
   * code:
   *
   * <pre>
   * private long nextUpdate;
   * protected boolean updateJournal() {
   *   long time = System.currentTimeMillis();
   *   if (time &gt; nextUpdate) {
   *     nextUpdate = time + 5 * 1000;
   *     return true;
   *   }
   *   return false;
   * }
   * </pre>
   */
  protected boolean updateJournal() {
    return true;
  }

  @ThreadSafe
  @Override
  @SuppressWarnings("unchecked")
  @Nullable
  public V remove(Object object) {
    V previous = delegate.remove(object);
    if (previous != null) {
      // we know that 'object' must be an instance of K, because the
      // remove call succeeded, i.e. 'object' was mapped to 'previous'.
      journal.add((K) object); // unchecked
      markAsDirty();
    }
    return previous;
  }

  @Override
  public V replace(K key, V value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    throw new UnsupportedOperationException();
  }

  /**
   * Updates the persistent journal by writing all entries to the {@link #journalOut} stream and
   * clearing the in memory journal.
   */
  private synchronized void writeJournal() {
    try {
      if (journalOut == null) {
        if (journalFile.exists()) {
          // The journal file was left around after the last save() because
          // keepJournal() was true. Append to it.
          journalOut =
              new DataOutputStream(new BufferedOutputStream(journalFile.getOutputStream(true)));
        } else {
          // Create new journal.
          journalOut = createMapFile(journalFile);
        }
      }
      // Journal may have duplicates, we can ignore them.
      LinkedHashSet<K> items = Sets.newLinkedHashSetWithExpectedSize(journal.size());
      journal.drainTo(items);
      writeEntries(journalOut, items, delegate());
      journalOut.flush();
    } catch (IOException e) {
      this.deferredIOFailure = e.getMessage() + " during journal append";
    }
  }

  protected void forceFlush() {
    if (dirty) {
      writeJournal();
    }
  }

  /**
   * Loads the previous written map entries from disk.
   *
   * @param failFast if true, throw IOException rather than silently ignoring.
   * @throws IOException
   */
  public synchronized void load(boolean failFast) throws IOException {
    if (!loaded) {
      loadEntries(mapFile, failFast);
      if (journalFile.exists()) {
        try {
          loadEntries(journalFile, failFast);
        } catch (IOException e) {
          if (failFast) {
            throw e;
          }
          //Else: ignore any errors reading the journal file as it may contain
          //partial entries.
        }
        // Force the map to be dirty, so that we can save it to disk.
        dirty = true;
        save(/*fullSave=*/ true);
      } else {
        dirty = false;
      }
      loaded = true;
    }
  }

  /** Loads the previous written map entries from disk. */
  public synchronized void load() throws IOException {
    load(/* failFast= */ false);
  }

  @Override
  public synchronized void clear() {
    super.clear();
    markAsDirty();
    try {
      save();
    } catch (IOException e) {
      this.deferredIOFailure = e.getMessage() + " during map write";
    }
  }

  /**
   * Saves all the entries of this map to disk and deletes the journal file.
   *
   * @throws IOException if there was an I/O error during this call, or any previous call since the
   *     last save().
   */
  public synchronized long save() throws IOException {
    return save(false);
  }

  /**
   * Saves all the entries of this map to disk and deletes the journal file.
   *
   * @param fullSave if true, always write the full cache to disk, without the journal.
   * @throws IOException if there was an I/O error during this call, or any previous call since the
   *     last save().
   */
  private synchronized long save(boolean fullSave) throws IOException {
    /* Report a previously failing I/O operation. */
    if (deferredIOFailure != null) {
      try {
        throw new IOException(deferredIOFailure);
      } finally {
        deferredIOFailure = null;
      }
    }
    if (dirty) {
      if (!fullSave && keepJournal()) {
        forceFlush();
        journalOut.close();
        journalOut = null;
        return journalSize() + cacheSize();
      } else {
        dirty = false;
        Path mapTemp =
            mapFile.getRelative(FileSystemUtils.replaceExtension(mapFile.asFragment(), ".tmp"));
        try {
          saveEntries(delegate(), mapTemp);
          mapFile.delete();
          mapTemp.renameTo(mapFile);
        } finally {
          mapTemp.delete();
        }
        clearJournal();
        journalFile.delete();
        return cacheSize();
      }
    } else {
      return cacheSize();
    }
  }

  protected final synchronized long journalSize() throws IOException {
    return journalFile.exists() ? journalFile.getFileSize() : 0;
  }

  protected final synchronized long cacheSize() throws IOException {
    return mapFile.exists() ? mapFile.getFileSize() : 0;
  }

  /**
   * If true, keep the journal during the save(). The journal is flushed, but
   * the map file is not touched. This may be useful in cases where the journal
   * is much smaller than the map.
   */
  protected boolean keepJournal() {
    return false;
  }

  private synchronized void clearJournal() throws IOException {
    journal.clear();
    if (journalOut != null) {
      journalOut.close();
      journalOut = null;
    }
  }

  private synchronized void loadEntries(Path mapFile, boolean failFast) throws IOException {
    if (!mapFile.exists()) {
      return;
    }

    long fileSize = mapFile.getFileSize();
    if (fileSize < MIN_MAPFILE_SIZE) {
      if (failFast) {
        throw new IOException(mapFile + " is too short: Only " + fileSize + " bytes");
      } else {
        return;
      }
    } else if (fileSize > MAX_ARRAY_SIZE) {
      if (failFast) {
        throw new IOException(mapFile + " is too long: " + fileSize + " bytes");
      } else {
        return;
      }
    }

    // We read the whole file up front as a performance optimization; otherwise calling available()
    // on the stream over and over does a lot of syscalls.
    byte[] mapBytes;
    try (InputStream fileInput = mapFile.getInputStream()) {
      mapBytes = ByteStreams.toByteArray(new BufferedInputStream(fileInput));
    }
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(mapBytes));
    try {
      if (in.readLong() != MAGIC) { // not a PersistentMap
        if (failFast) {
          throw new IOException("Bad magic number");
        }
        return;
      }
      long persistedVersion = in.readLong();
      if (persistedVersion != version) { // PersistentMap version incompatible
        if (failFast) {
          throw new IOException(
              "Incompatible version: want %d, got %d".formatted(version, persistedVersion));
        }
        return;
      }
      readEntries(in, failFast);
    } finally {
      in.close();
    }

    logger.atInfo().log("Loaded cache '%s' [%d bytes]", mapFile, fileSize);
  }

  /**
   * Saves the entries in the specified map into the specified file.
   *
   * @param map the map to be written into the file.
   * @param mapFile the file the map is written to.
   * @throws IOException
   */
  private synchronized void saveEntries(Map<K, V> map, Path mapFile) throws IOException {
    try (DataOutputStream out = createMapFile(mapFile)) {
      writeEntries(out, map.keySet(), map);
    }
  }

  /**
   * Creates the specified file and returns the DataOuputStream suitable for writing entries.
   *
   * @param mapFile the file the map is written to.
   * @return the DataOutputStream that was can be used for saving the map to the file.
   * @throws IOException
   */
  private synchronized DataOutputStream createMapFile(Path mapFile) throws IOException {
    mapFile.getParentDirectory().createDirectoryAndParents();
    DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(mapFile.getOutputStream()));
    out.writeLong(MAGIC);
    out.writeLong(version);
    return out;
  }

  private void writeEntries(DataOutputStream out, Set<K> keys, Map<K, V> map) throws IOException {
    for (K key : keys) {
      out.writeByte(ENTRY_MAGIC);
      writeKey(key, out);
      V value = map.get(key);
      boolean isEntry = (value != null);
      out.writeBoolean(isEntry);
      if (isEntry) {
        writeValue(value, out);
      }
    }
  }

  /**
   * Reads the Map entries from the specified DataInputStream.
   *
   * @param failFast if true, throw IOException if entries are in an unexpected
   *                 format.
   * @param in the DataInputStream to read the Map entries from.
   * @throws IOException
   */
  private void readEntries(DataInputStream in, boolean failFast) throws IOException {
    Map<K, V> map = delegate();
    while (hasEntries(in, failFast)) {
      K key = readKey(in);
      boolean isEntry = in.readBoolean();
      if (isEntry) {
        V value = readValue(in);
        map.put(key, value);
      } else {
        map.remove(key);
      }
    }
  }

  private boolean hasEntries(DataInputStream in, boolean failFast) throws IOException {
    if (in.available() <= 0) {
      return false;
    } else if (in.readUnsignedByte() != ENTRY_MAGIC) {
      if (failFast) {
        throw new IOException("Corrupted entry separator");
      } else {
        return false;
      }
    }
    return true;
  }

  /**
   * Writes a key of this map into the specified DataOutputStream.
   *
   * @param key the key to write to the DataOutputStream.
   * @param out the DataOutputStream to write the entry to.
   * @throws IOException
   */
  protected abstract void writeKey(K key, DataOutputStream out) throws IOException;

  /**
   * Writes a value of this map into the specified DataOutputStream.
   *
   * @param value the value to write to the DataOutputStream.
   * @param out the DataOutputStream to write the entry to.
   * @throws IOException
   */
  protected abstract void writeValue(V value, DataOutputStream out) throws IOException;

  /**
   * Reads an entry of this map from the specified DataInputStream.
   *
   * @param in the DataOutputStream to read the entry from.
   * @return the entry that was read from the DataInputStream.
   * @throws IOException
   */
  protected abstract K readKey(DataInputStream in) throws IOException;

  /**
   * Reads an entry of this map from the specified DataInputStream.
   *
   * @param in the DataOutputStream to read the entry from.
   * @return the entry that was read from the DataInputStream.
   * @throws IOException
   */
  protected abstract V readValue(DataInputStream in) throws IOException;
}

