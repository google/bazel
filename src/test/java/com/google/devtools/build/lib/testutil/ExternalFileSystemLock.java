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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;

/**
 * Runs an external process that holds a shared or exclusive lock on a file.
 *
 * <p>This is needed for testing because the JVM does not allow overlapping locks.
 */
public class ExternalFileSystemLock implements AutoCloseable {
  private static final String HELPER_PATH =
      "io_bazel/src/test/java/com/google/devtools/build/lib/testutil/external_file_system_lock_helper"
          + (OS.getCurrent() == OS.WINDOWS ? ".exe" : "");

    private final Subprocess subprocess;

  public static ExternalFileSystemLock getShared(Path lockPath) throws IOException {
    return new ExternalFileSystemLock(lockPath, true);
    }

  public static ExternalFileSystemLock getExclusive(Path lockPath) throws IOException {
    return new ExternalFileSystemLock(lockPath, false);
    }

  private ExternalFileSystemLock(Path lockPath, boolean shared) throws IOException {
      String binaryPath = Runfiles.preload().withSourceRepository("").rlocation(HELPER_PATH);
    this.subprocess =
        new SubprocessBuilder()
            .setArgv(
                ImmutableList.of(
                    binaryPath, lockPath.getPathString(), shared ? "shared" : "exclusive", "sleep"))
            .start();
      // Wait for child to report that the lock has been acquired.
      // We could read the entire stdout/stderr here to obtain additional debugging information,
      // but for some reason that hangs forever on Windows, even if we close them on the child side.
      if (subprocess.getInputStream().read() != '!') {
        throw new IOException("external helper process failed");
      }
    }

    @Override
    public void close() throws IOException {
    // Wait for process to exit and release the lock.
    subprocess.destroyAndWait();
  }
}
