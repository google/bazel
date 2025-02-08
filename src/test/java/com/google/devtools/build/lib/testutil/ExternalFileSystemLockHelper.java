// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A helper program that attempts to obtain a shared or exclusive lock on a file and optionally
 * sleeps forever while holding it.
 *
 * <p>The arguments are as follows:
 *
 * <ol>
 *   <li>The path of the file to lock.
 *   <li>One of "shared" or "exclusive", indicating the type of lock to obtain.
 *   <li>One of "sleep" or "exit", indicating whether to sleep forever or exit immediately once the
 *       lock is held.
 * </ol>
 *
 * <p>Does not block waiting for the lock, exiting immediately if it's already held.
 *
 * <p>Once the lock is held, prints '!' to stdout.
 *
 * <p>In a Java test, prefer {@link ExternalFileSystemLock} over using this directly.
 */
public final class ExternalFileSystemLockHelper {
  private ExternalFileSystemLockHelper() {}

  private static final ImmutableSet<OpenOption> OPEN_OPTIONS =
      ImmutableSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length != 3
        || !(args[1].equals("shared") || args[1].equals("exclusive"))
        || !(args[2].equals("sleep") || args[2].equals("exit"))) {
      throw new IOException("invalid arguments");
    }

    Path path = Path.of(args[0]).toAbsolutePath();
    boolean shared = args[1].equals("shared");
    boolean sleep = args[2].equals("sleep");

    Files.createDirectories(path.getParent());
    try (FileChannel channel = FileChannel.open(path, OPEN_OPTIONS);
        FileLock lock = channel.tryLock(0, Long.MAX_VALUE, shared)) {
      if (lock == null) {
        throw new IOException("lock already held");
      }

      // Signal parent that the lock is held.
      System.out.println("!");

      // If so requested, block until killed by parent.
      if (sleep) {
        while (true) {
          Thread.sleep(1000);
        }
      }
    }
  }
}
