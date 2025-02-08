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

package com.google.devtools.build.buildjar.jarhelper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

/**
 * A simple helper class for creating Jar files. All Jar entries are sorted alphabetically. Allows
 * normalization of Jar entries by setting the timestamp of non-.class files to the DOS epoch.
 * Timestamps of .class files are set to the DOS epoch + 2 seconds (The zip timestamp granularity)
 * Adjusting the timestamp for .class files is necessary since otherwise javac will recompile java
 * files if both the java file and its .class file are present.
 */
public class JarHelper {

  public static final String MANIFEST_DIR = "META-INF/";
  public static final String MANIFEST_NAME = JarFile.MANIFEST_NAME;

  /**
   * Normalize timestamps to 2010-1-1.
   *
   * <p>The ZIP format uses MS-DOS timestamps (see <a
   * href="https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT">APPNOTE.TXT</a>) which use
   * 1980-1-1 as the epoch, but {@link ZipEntry#setTime(long)} expects milliseconds since the unix
   * epoch (1970-1-1). To work around this, {@link ZipEntry} uses portability-reducing ZIP
   * extensions to store pre-1980 timestamps, which can occasionally <a
   * href="https://bugs.openjdk.java.net/browse/JDK-8246129>cause</a> <a
   * href="https://openjdk.markmail.org/thread/wzw7zfilk5j7uzqk>issues</a>. For that reason, using a
   * fixed post-1980 timestamp is preferred to e.g. calling {@code setTime(0)}. At Google, the
   * timestamp of 2010-1-1 is used by convention in deterministic jar archives.
   */
  @SuppressWarnings("GoodTime-ApiWithNumericTimeUnit") // Use setTime(LocalDateTime) in Java > 9
  public static final long DEFAULT_TIMESTAMP =
      LocalDateTime.of(2010, 1, 1, 0, 0, 0)
          .atZone(ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();
  // These attributes are used by JavaBuilder, Turbine, and ijar.
  // They must all be kept in sync.
  public static final Attributes.Name TARGET_LABEL = new Attributes.Name("Target-Label");
  public static final Attributes.Name INJECTING_RULE_KIND =
      new Attributes.Name("Injecting-Rule-Kind");

  public static final Attributes.Name MULTI_RELEASE = new Attributes.Name("Multi-Release");

  // ZIP timestamps have a resolution of 2 seconds.
  // see http://www.info-zip.org/FAQ.html#limits
  public static final long MINIMUM_TIMESTAMP_INCREMENT = 2000L;

  // The path to the Jar we want to create
  protected final Path jarPath;

  // The properties to describe how to create the Jar
  protected boolean normalize;
  protected int storageMethod = JarEntry.DEFLATED;
  protected boolean verbose = false;
  protected boolean multiRelease = false;

  // The state needed to create the Jar
  protected final Set<String> names = new HashSet<>();

  public JarHelper(Path path) {
    jarPath = path;
  }

  /**
   * Enables or disables the Jar entry normalization.
   *
   * @param normalize If true the timestamps of Jar entries will be set to the DOS epoch.
   */
  public void setNormalize(boolean normalize) {
    this.normalize = normalize;
  }

  public void multiRelease(boolean multiRelease) {
    this.multiRelease = multiRelease;
  }

  /**
   * Enables or disables compression for the Jar file entries.
   *
   * @param compression if true enables compressions for the Jar file entries.
   */
  public void setCompression(boolean compression) {
    storageMethod = compression ? JarEntry.DEFLATED : JarEntry.STORED;
  }

  /**
   * Enables or disables verbose messages.
   *
   * @param verbose if true enables verbose messages.
   */
  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  /**
   * Returns the normalized timestamp for a jar entry based on its name. This is necessary since
   * javac will, when loading a class X, prefer a source file to a class file, if both files have
   * the same timestamp. Therefore, we need to adjust the timestamp for class files to slightly
   * after the normalized time.
   *
   * @param name The name of the file for which we should return the normalized timestamp.
   * @return the time for a new Jar file entry in milliseconds since the epoch.
   */
  private long normalizedTimestamp(String name) {
    if (name.endsWith(".class")) {
      return DEFAULT_TIMESTAMP + MINIMUM_TIMESTAMP_INCREMENT;
    } else {
      return DEFAULT_TIMESTAMP;
    }
  }

  /**
   * Returns the time for a new Jar file entry in milliseconds since the epoch. Uses {@link
   * JarCreator#DEFAULT_TIMESTAMP} for normalized entries, {@link System#currentTimeMillis()}
   * otherwise.
   *
   * @param filename The name of the file for which we are entering the time
   * @return the time for a new Jar file entry in milliseconds since the epoch.
   */
  protected long newEntryTimeMillis(String filename) {
    return normalize ? normalizedTimestamp(filename) : System.currentTimeMillis();
  }

  /**
   * Writes an entry with specific contents to the jar. Directory entries must include the trailing
   * '/'.
   */
  protected void writeEntry(JarOutputStream out, String name, byte[] content) throws IOException {
    if (names.add(name)) {
      // Create a new entry
      JarEntry entry = new JarEntry(name);
      entry.setTime(newEntryTimeMillis(name));
      int size = content.length;
      entry.setSize(size);
      if (size == 0) {
        entry.setMethod(JarEntry.STORED);
        entry.setCrc(0);
        out.putNextEntry(entry);
      } else {
        entry.setMethod(storageMethod);
        if (storageMethod == JarEntry.STORED) {
          CRC32 crc = new CRC32();
          crc.update(content);
          entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(content);
      }
      out.closeEntry();
    }
  }

  /**
   * Writes a standard Java manifest entry into the JarOutputStream. This includes the directory
   * entry for the "META-INF" directory
   *
   * @param content the Manifest content to write to the manifest entry.
   * @throws IOException
   */
  protected void writeManifestEntry(JarOutputStream out, byte[] content) throws IOException {
    int oldStorageMethod = storageMethod;
    // Do not compress small manifest files, the compressed one is frequently
    // larger than the original. The threshold of 256 bytes is somewhat arbitrary.
    if (content.length < 256) {
      storageMethod = JarEntry.STORED;
    }
    try {
      writeEntry(out, MANIFEST_DIR, new byte[] {});
      writeEntry(out, MANIFEST_NAME, content);
    } finally {
      storageMethod = oldStorageMethod;
    }
  }

  /**
   * Copies file or directory entries from the file system into the jar. Directory entries will be
   * detected and their names automatically '/' suffixed.
   */
  protected void copyEntry(JarOutputStream out, String name, Path path) throws IOException {
    if (!names.contains(name)) {
      if (!Files.exists(path)) {
        throw new FileNotFoundException(path.toAbsolutePath() + " (No such file or directory)");
      }
      boolean isDirectory = Files.isDirectory(path);
      if (isDirectory && !name.endsWith("/")) {
        name = name + '/'; // always normalize directory names before checking set
      }
      if (names.add(name)) {
        if (verbose) {
          System.err.println("adding " + path);
        }
        // Create a new entry
        long size = isDirectory ? 0 : Files.size(path);
        JarEntry outEntry = new JarEntry(name);
        long newtime =
            normalize ? normalizedTimestamp(name) : Files.getLastModifiedTime(path).toMillis();
        outEntry.setTime(newtime);
        outEntry.setSize(size);
        if (size == 0L) {
          outEntry.setMethod(JarEntry.STORED);
          outEntry.setCrc(0);
          out.putNextEntry(outEntry);
        } else {
          outEntry.setMethod(storageMethod);
          if (storageMethod == JarEntry.STORED) {
            // ZipFile requires us to calculate the CRC-32 for any STORED entry.
            // It would be nicer to do this via DigestInputStream, but
            // the architecture of ZipOutputStream requires us to know the CRC-32
            // before we write the data to the stream.
            byte[] bytes = Files.readAllBytes(path);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            outEntry.setCrc(crc.getValue());
            out.putNextEntry(outEntry);
            out.write(bytes);
          } else {
            out.putNextEntry(outEntry);
            Files.copy(path, out);
          }
        }
        out.closeEntry();
      }
    }
  }
}
