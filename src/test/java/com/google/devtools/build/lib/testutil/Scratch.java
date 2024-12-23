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

import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Allow tests to easily manage scratch files in a FileSystem.
 */
public final class Scratch {

  private final FileSystem fileSystem;
  private Path workingDir = null;

  /**
   * Create a new ScratchFileSystem using the {@link InMemoryFileSystem}
   */
  public Scratch() {
    this(new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256), "/");
  }

  /**
   * Create a new ScratchFileSystem using the {@link InMemoryFileSystem}
   */
  public Scratch(String workingDir) {
    this(new InMemoryFileSystem(BlazeClock.instance(), DigestHashFunction.SHA256), workingDir);
  }

  /**
   * Create a new ScratchFileSystem using the given {@code Path}.
   */
  public Scratch(Path workingDir) {
    this.fileSystem = workingDir.getFileSystem();
    this.workingDir = workingDir;
  }

  /**
   * Create a new ScratchFileSystem using the supplied FileSystem.
   */
  public Scratch(FileSystem fileSystem) {
    this(fileSystem, "/");
  }

  /**
   * Create a new ScratchFileSystem using the supplied FileSystem.
   */
  public Scratch(FileSystem fileSystem, String workingDir) {
    this.fileSystem = fileSystem;
    this.workingDir = fileSystem.getPath(workingDir);
  }

  /**
   * Returns the FileSystem in use.
   */
  public FileSystem getFileSystem() {
    return fileSystem;
  }

  public void setWorkingDir(String workingDir) {
    this.workingDir = fileSystem.getPath(workingDir);
  }

  /**
   * Resolves {@code pathName} relative to the working directory. Note that this will not create any
   * entity in the filesystem; i.e., the file that the object is describing may not exist in the
   * filesystem.
   */
  public Path resolve(String pathName) {
    return workingDir.getRelative(pathName);
  }

  /**
   * Resolves {@code pathName} relative to the working directory. Note that this will not create any
   * entity in the filesystem; i.e., the file that the object is describing may not exist in the
   * filesystem.
   */
  public Path resolve(PathFragment pathName) {
    return workingDir.getRelative(pathName);
  }

  /**
   * Create a directory in the scratch filesystem, with the given path name.
   */
  public Path dir(String pathName) throws IOException {
    Path dir = resolve(pathName);
    if (!dir.exists()) {
      dir.createDirectoryAndParents();
    }
    if (!dir.isDirectory()) {
      throw new IOException("Exists, but is not a directory: " + pathName);
    }
    return dir;
  }

  /**
   * Create a scratch file in the scratch filesystem, with the given pathName, consisting of a set
   * of lines. The method returns a Path instance for the scratch file.
   */
  public Path file(String pathName, String... lines) throws IOException {
    Path file = newFile(pathName);
    String content = linesAsString(lines);
    FileSystemUtils.writeContent(file, content);
    file.setLastModifiedTime(-1L);
    return file;
  }

  /**
   * Create a scratch file in the given filesystem, with the given pathName, consisting of a set of
   * lines. The method returns a Path instance for the scratch file.
   */
  public Path file(String pathName, byte[] content) throws IOException {
    Path file = newFile(pathName);
    FileSystemUtils.writeContent(file, content);
    return file;
  }

  public String readFile(String pathName) throws IOException {
    try (InputStream in = resolve(pathName).getInputStream()) {
      return new String(ByteStreams.toByteArray(in), StandardCharsets.ISO_8859_1);
    }
  }

  /** Like {@code scratch.file}, but the lines are added to the end if the file already exists. */
  public Path appendFile(String pathName, Collection<String> lines) throws IOException {
    return appendFile(pathName, lines.toArray(new String[lines.size()]));
  }

  /** Like {@code scratch.file}, but the lines are added to the end if the file already exists. */
  public Path appendFile(String pathName, String... lines) throws IOException {
    Path path = resolve(pathName);

    StringBuilder content = new StringBuilder();
    if (path.exists()) {
      content.append(readFile(pathName));
      content.append("\n");
    }
    content.append(linesAsString(lines));

    return overwriteFile(pathName, content.toString());
  }

  /**
   * Like {@code scratch.file}, but the file is first deleted if it already
   * exists.
   */
  public Path overwriteFile(String pathName, Collection<String> lines)  throws IOException {
    return overwriteFile(pathName, lines.toArray(new String[0]));
  }

  /** Like {@code scratch.file}, but the file is first deleted if it already exists. */
  public Path overwriteFile(String pathName, String... lines) throws IOException {
    Path oldFile = resolve(pathName);
    long newMTime = oldFile.exists() ? oldFile.getLastModifiedTime() + 1 : -1;
    oldFile.delete();
    Path newFile = file(pathName, lines);
    newFile.setLastModifiedTime(newMTime);
    return newFile;
  }

  /**
   * Deletes the specified scratch file, using the same specification as {@link Path#delete}.
   */
  public boolean deleteFile(String pathName) throws IOException {
    return resolve(pathName).delete();
  }

  /** Creates a new scratch file, ensuring parents exist. */
  private Path newFile(String pathName) throws IOException {
    Path file = resolve(pathName);
    Path parentDir = file.getParentDirectory();
    if (!parentDir.exists()) {
      parentDir.createDirectoryAndParents();
    }
    if (file.exists()) {
      throw new IOException("Could not create scratch file (file exists) "
          + pathName);
    }
    return file;
  }

  /**
   * Converts the lines into a String with linebreaks. Useful for creating
   * in-memory input for a file, for example.
   */
  private static String linesAsString(String... lines) {
    StringBuilder builder = new StringBuilder();
    for (String line : lines) {
      builder.append(line);
      builder.append('\n');
    }
    return builder.toString();
  }

  public void copyFile(String sourceFile, String destFile) throws IOException {
    String contents = readFile(sourceFile);
    overwriteFile(destFile, contents);
  }
}
