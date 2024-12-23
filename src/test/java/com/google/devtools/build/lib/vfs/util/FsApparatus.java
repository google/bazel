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
package com.google.devtools.build.lib.vfs.util;

import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Base class for a testing apparatus for a scratch filesystem.
 */
public class FsApparatus {

  /* ---------- State that the apparatus initializes / operates on --------- */
  protected FileSystem fileSystem = null;
  protected Path workingDir = null;

  public static FsApparatus newInMemory() {
    return new FsApparatus();
  }

  // TestUtil.getTmpDir is slow, so cache the result here
  private static final String TMP_DIR =
      new File(TestUtils.tmpDir(), "bs").toString();


  /**
   * When using a Native file system, absolute paths will be treated as absolute paths on the unix
   * file system, as opposed to paths relative to the backing temp directory. So for simplicity,
   * you ought to only use relative paths for FsApparatus#file, FsApparatus#dir, and
   * FsApparatus#path. Otherwise, be aware of the following issue
   *
   *   Path p1 = scratch.path(...);
   *   Path p2 = scratch.path(p1.getPathString());
   *
   * We'd like the invariant that p1.equals(p2) regardless if scratch is in-memory or not, but this
   * does not hold with our usage of Unix filesystems.
   */
  public static FsApparatus newNative() {
    FileSystem fs = FileSystems.getNativeFileSystem();
    Path wd = fs.getPath(TMP_DIR);

    try {
      wd.deleteTree();
    } catch (IOException e) {
      throw new AssertionError(e.getMessage());
    }

    return new FsApparatus(fs, wd);
  }

  private FsApparatus() {
    fileSystem = new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256);
    workingDir = fileSystem.getPath("/");
  }

  public FsApparatus(FileSystem fs, Path cwd) {
    fileSystem = fs;
    workingDir = cwd;
  }

  public FsApparatus(FileSystem fs) {
    fileSystem = fs;
    workingDir = fs.getPath("/");
  }

  public FileSystem fs() {
    return fileSystem;
  }

  /**
   * Creates a scratch file in the scratch filesystem with the given {@code pathName} with
   * {@code lines} being its content. The method returns a Path instance for the scratch file.
   */
  public Path file(String pathName, String... lines) throws IOException {
    Path file = path(pathName);
    Path parentDir = file.getParentDirectory();
    if (!parentDir.exists()) {
      parentDir.createDirectoryAndParents();
    }
    if (file.exists()) {
      throw new IOException("Could not create scratch file (file exists) "
          + file);
    }
    String fileContent = StringUtilities.joinLines(lines);
    FileSystemUtils.writeContent(file, fileContent);
    return file;
  }

  /**
   * Creates or recreates a scratch file just like {@link #file} but tolerating an existing file.
   */
  public Path overwriteFile(String pathName, String... lines) throws IOException {
    try {
      path(pathName).delete();
    } catch (FileNotFoundException e) {
      // Ignored.
    }
    return file(pathName, lines);
  }

  /**
   * Initializes this apparatus (if it hasn't been initialized yet), and creates
   * a directory in the scratch filesystem, with the given {@code pathName}.
   * Creates parent directories as necessary.
   */
  public Path dir(String pathName) throws IOException {
    Path dir = path(pathName);
    if (!dir.exists()) {
      dir.createDirectoryAndParents();
    }
    if (!dir.isDirectory()) {
      throw new IOException("Exists, but is not a directory: " + dir);
    }
    return dir;
  }

  /**
   * Resolves {@code pathName} relative to the working directory. Note that this will not create any
   * entity in the filesystem; i.e., the file that the object is describing may not exist in the
   * filesystem.
   */
  public Path path(String pathName) {
    return workingDir.getRelative(pathName);
  }

  /**
   * Create a fresh directory in the system temporary directory, instead of the
   * testing directory provided by the testing framework. This path is usually
   * shorter than a path starting with TestUtil.getTmpDir(). We care about the
   * length because of the path length restriction for Unix local socket files.
   *
   * Clients are responsible for deleting the directory after tests.
   */
  public Path createUnixTempDir() throws IOException {
    if (fileSystem instanceof InMemoryFileSystem) {
      throw new IOException("Can not create Unix temporary directories in "
                            + "an in-memory file system");
    }
    File file = File.createTempFile("scratch", "tmp");
    final Path path = fileSystem.getPath(file.getAbsolutePath());
    path.delete();
    path.createDirectory();
    return path;
  }
}
