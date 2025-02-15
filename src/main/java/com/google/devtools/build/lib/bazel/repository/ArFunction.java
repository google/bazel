// Copyright 2022 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue.Decompressor;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;

/**
 * Opens a .ar archive file. It ignores the prefix setting because these archives cannot contain
 * directories.
 */
public class ArFunction implements Decompressor {

  public static final Decompressor INSTANCE = new ArFunction();

  // This is the same value as picked for .tar files, which appears to have worked well.
  private static final int BUFFER_SIZE = 32 * 1024;

  @Override
  public Path decompress(DecompressorDescriptor descriptor)
      throws InterruptedException, IOException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Map<String, String> renameFiles = descriptor.renameFiles();

    try (InputStream decompressorStream =
        new BufferedInputStream(descriptor.archivePath().getInputStream(), BUFFER_SIZE)) {
      ArArchiveInputStream arStream = new ArArchiveInputStream(decompressorStream);
      ArArchiveEntry entry;
      while ((entry = arStream.getNextArEntry()) != null) {
        String entryName = entry.getName();
        entryName = renameFiles.getOrDefault(entryName, entryName);
        Path filePath = descriptor.destinationPath().getRelative(entryName);
        filePath.getParentDirectory().createDirectoryAndParents();
        if (entry.isDirectory()) {
          // ar archives don't contain any directory information, so this should never
          // happen
          continue;
        } else {
          // We do not have to worry about symlinks in .ar files - it's not supported
          // by the .ar file format.
          try (OutputStream out = filePath.getOutputStream()) {
            ByteStreams.copy(arStream, out);
          }
          filePath.chmod(entry.getMode());
          // entry.getLastModified() appears to be in seconds, so we need to convert
          // it into milliseconds for setLastModifiedTime
          filePath.setLastModifiedTime(entry.getLastModified() * 1000L);
        }
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
      }
    }

    return descriptor.destinationPath();
  }
}
