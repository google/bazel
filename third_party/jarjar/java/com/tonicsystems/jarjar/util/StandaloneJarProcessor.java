/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Util for transforming a JAR. */
public final class StandaloneJarProcessor {
  public static void run(File from, File to, JarProcessor proc) throws IOException {
    ArrayList<EntryStruct> entries = new ArrayList<>();

    // Read and transform all the input entries
    try (ZipFile inZip = new ZipFile(from)) {
      for (Enumeration<? extends ZipEntry> e = inZip.entries(); e.hasMoreElements(); ) {
        ZipEntry inEntry = e.nextElement();
        EntryStruct outEntry = new EntryStruct();
        outEntry.name = inEntry.getName();
        outEntry.time = inEntry.getTime();
        outEntry.data = inZip.getInputStream(inEntry).readAllBytes();

        if (!proc.process(outEntry)) {
          continue; // Skip any inputs dropped by the transformation rules
        }

        entries.add(outEntry);
      }
    }

    // Sort the entries by their transformed names
    // For determinism in the case of duplicate entry names, this must be a stable sort.
    Collections.sort(entries, Comparator.comparing((x) -> x.name));

    // Drop any empty directories and handle duplicate names
    EntryStruct prevFile = null;
    EntryStruct prevEntry = null;
    for (int i = entries.size() - 1; i >= 0; i--) {
      EntryStruct entry = entries.get(i);
      boolean dropEntry = false;

      if (prevEntry != null && prevEntry.name.equals(entry.name)) {
        if (entry.isDir()) {
          dropEntry = true; // TODO(nickreid): Report duplicate dirs
        } else {
          throw new IllegalArgumentException("Duplicate jar entries: " + entry.name);
        }
      } else if (entry.isDir()) {
        // Is this dir a parent of the previous file?
        dropEntry = (prevFile == null || !prevFile.name.startsWith(entry.name));
      } else {
        prevFile = entry;
      }

      if (dropEntry) {
        entries.set(i, null); // Set to null, rather than shift the entire list
      } else {
        prevEntry = entry;
      }
    }

    // Write all surviving entries
    try (ZipOutputStream outZip = IoUtil.bufferedZipOutput(to)) {
      for (EntryStruct entry : entries) {
        if (entry == null) {
          continue;
        }

        ZipEntry outEntry = new ZipEntry(entry.name);
        outEntry.setTime(entry.time);
        outEntry.setCompressedSize(-1);
        outZip.putNextEntry(outEntry);
        outZip.write(entry.data);
      }
    }
  }

  private StandaloneJarProcessor() {}
}
