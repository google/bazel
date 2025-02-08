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

package com.google.devtools.build.lib.testutil;

import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import javax.annotation.Nullable;

/** Some static utility functions for testing. */
public final class TestUtils {

  public static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  /**
   * Returns the path to a fixed temporary directory, with back-slashes turned into slashes. The
   * directory is guaranteed to exist and be unique for the test <em>target</em>. Since test
   * <em>cases</em> may run in parallel, prefer using {@link #createUniqueTmpDir} instead, which
   * also guarantees that the directory is empty.
   */
  public static String tmpDir() {
    return tmpDirFile().getAbsolutePath().replace('\\', '/');
  }

  static String getUserValue(String key) {
    String value = System.getProperty(key);
    if (value == null) {
      value = System.getenv(key);
    }
    return value;
  }

  /**
   * Returns a fixed temporary directory, guaranteed to exist and be unique for the test
   * <em>target</em>. Since test <em>cases</em> may run in parallel, prefer using {@link
   * #createUniqueTmpDir} instead, which also guarantees that the directory is empty.
   */
  public static File tmpDirFile() {
    File tmpDir = tmpDirRoot();

    // Ensure tmpDir exists
    if (!tmpDir.isDirectory()) {
      tmpDir.mkdirs();
    }
    return tmpDir;
  }

  /**
   * Creates a unique and empty temporary directory.
   *
   * @param fileSystem The file system the directory should be created on. If null, uses the Java
   *     file system.
   * @return A newly created directory, extremely likely to be unique.
   */
  public static Path createUniqueTmpDir(FileSystem fileSystem) throws IOException {
    if (fileSystem == null) {
      fileSystem = new JavaIoFileSystem(DigestHashFunction.SHA256);
    }
    File tmpDirRoot = tmpDirRoot();
    Path path =
        fileSystem
            .getPath(StringEncoding.platformToInternal(tmpDirRoot.getPath()))
            .getRelative(UUID.randomUUID().toString());
    path.createDirectoryAndParents();
    return path;
  }

  private static File tmpDirRoot() {
    File tmpDir; // Flag value specified in environment?
    String tmpDirStr = getUserValue("TEST_TMPDIR");

    if (tmpDirStr != null && tmpDirStr.length() > 0) {
      tmpDir = new File(tmpDirStr);
    } else {
      // Fallback default $TEMP/$USER/tmp/$TESTNAME
      String baseTmpDir = System.getProperty("java.io.tmpdir");
      tmpDir = new File(baseTmpDir).getAbsoluteFile();

      // .. Add username
      String username = System.getProperty("user.name");
      username = username.replace('/', '_');
      username = username.replace('\\', '_');
      username = username.replace('\000', '_');
      tmpDir = new File(tmpDir, username);
      tmpDir = new File(tmpDir, "tmp");
    }
    return tmpDir;
  }

  public static int getRandomSeed() {
    // Default value if not running under framework
    int randomSeed = 301;

    // Value specified in environment by framework?
    String value = getUserValue("TEST_RANDOM_SEED");
    if ((value != null) && (value.length() > 0)) {
      try {
        randomSeed = Integer.parseInt(value);
      } catch (NumberFormatException e) {
        // throw new AssertionError("TEST_RANDOM_SEED must be an integer");
        throw new RuntimeException("TEST_RANDOM_SEED must be an integer", e);
      }
    }

    return randomSeed;
  }

  public static SyscallCache makeDisappearingFileCache(String path) {
    PathFragment badPath = PathFragment.create(path);
    return new SyscallCache() {
      @Override
      public Collection<Dirent> readdir(Path path) throws IOException {
        return SyscallCache.NO_CACHE.readdir(path);
      }

      @Nullable
      @Override
      public FileStatus statIfFound(Path path, Symlinks symlinks) throws IOException {
        return path.asFragment().endsWith(badPath)
            ? null
            : SyscallCache.NO_CACHE.statIfFound(path, symlinks);
      }

      @Override
      public DirentTypeWithSkip getType(Path path, Symlinks symlinks) {
        return path.asFragment().endsWith(badPath)
            ? DirentTypeWithSkip.FILE
            : DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED;
      }

      @Override
      public void clear() {}
    };
  }

  /** Creates the assertion String to match against when a target isn't found. */
  public static String createMissingTargetAssertionString(
      String target, String packageStr, String workspaceRoot, String expectedTargets) {
    if (workspaceRoot == null) {
      workspaceRoot = "";
    }

    String buildFilePath = workspaceRoot + "/" + packageStr + "/BUILD";

    String fullTarget = "//" + packageStr + ":" + target;

    final String suggestedTargetsBaseString =
        "no such target '%s': target '%s' not declared in package '%s' "
            + "defined by %s"
            + expectedTargets;

    return String.format(
        suggestedTargetsBaseString, fullTarget, target, packageStr, buildFilePath, expectedTargets);
  }

  /**
   * Timeouts for asserting that an arbitrary event occurs eventually.
   *
   * <p>In general, it's not appropriate to use a small constant timeout for an arbitrary
   * computation since there is no guarantee that a snippet of code will execute within a given
   * amount of time - you are at the mercy of the jvm, your machine, and your OS. In theory we could
   * try to take all of these factors into account but instead we took the simpler and obviously
   * correct approach of not having timeouts.
   *
   * <p>If a test that uses these timeout values is failing due to a "timeout" at the 'blaze test'
   * level, it could be because of a legitimate deadlock that would have been caught if the timeout
   * values below were small. So you can rule out such a deadlock by changing these values to small
   * numbers (also note that the --test_timeout blaze flag may be useful).
   */
  public static final long WAIT_TIMEOUT_MILLISECONDS = Long.MAX_VALUE;

  public static final long WAIT_TIMEOUT_SECONDS = WAIT_TIMEOUT_MILLISECONDS / 1000;

  private TestUtils() {}
}
