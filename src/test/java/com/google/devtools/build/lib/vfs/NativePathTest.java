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
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import com.google.devtools.build.lib.vfs.util.TestUnixGlobPathDiscriminator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Path} in combination with the native file system for the current platform. */
@RunWith(JUnit4.class)
public class NativePathTest {

  private FileSystem fs;
  private File aDirectory;
  private File aFile;
  private File anotherFile;
  private File tmpDir;

  protected FileSystem getNativeFileSystem() {
    return FileSystems.getNativeFileSystem();
  }

  @Before
  public final void createFiles() throws Exception  {
    fs = getNativeFileSystem();
    tmpDir = new File(TestUtils.tmpDir(), "tmpDir");
    tmpDir.mkdirs();
    aDirectory = new File(tmpDir, "a_directory");
    aDirectory.mkdirs();
    aFile = new File(tmpDir, "a_file");
    new FileOutputStream(aFile).close();
    anotherFile = new File(aDirectory, "another_file.txt");
    new FileOutputStream(anotherFile).close();
  }

  @Test
  public void testExists() {
    assertThat(fs.getPath(aDirectory.getPath()).exists()).isTrue();
    assertThat(fs.getPath(aFile.getPath()).exists()).isTrue();
    assertThat(fs.getPath("/does/not/exist").exists()).isFalse();
  }

  @Test
  public void testDirectoryEntriesForDirectory() throws IOException {
    assertThat(fs.getPath(tmpDir.getPath()).getDirectoryEntries()).containsExactly(
      fs.getPath(tmpDir.getPath() + "/a_file"),
      fs.getPath(tmpDir.getPath() + "/a_directory"));

  }

  @Test
  public void testDirectoryEntriesForFileThrowsException() {
    assertThrows(IOException.class, () -> fs.getPath(aFile.getPath()).getDirectoryEntries());
  }

  @Test
  public void testIsFileIsTrueForFile() {
    assertThat(fs.getPath(aFile.getPath()).isFile()).isTrue();
  }

  @Test
  public void testIsFileIsFalseForDirectory() {
    assertThat(fs.getPath(aDirectory.getPath()).isFile()).isFalse();
  }

  @Test
  public void testBaseName() {
    assertThat(fs.getPath("/foo/base").getBaseName()).isEqualTo("base");
  }

  @Test
  public void testBaseNameRunsAfterDotDotInterpretation() {
    assertThat(fs.getPath("/base/foo/..").getBaseName()).isEqualTo("base");
  }

  @Test
  public void testIsDirectory() {
    assertThat(fs.getPath(aDirectory.getPath()).isDirectory()).isTrue();
    assertThat(fs.getPath(aFile.getPath()).isDirectory()).isFalse();
    assertThat(fs.getPath("/does/not/exist").isDirectory()).isFalse();
  }

  @Test
  public void testListNonExistingDirectoryThrowsException() {
    assertThrows(IOException.class, () -> fs.getPath("/does/not/exist").getDirectoryEntries());
  }

  private void assertPathSet(Collection<Path> actual, String... expected) {
    List<String> actualStrings = Lists.newArrayListWithCapacity(actual.size());

    for (Path path : actual) {
      actualStrings.add(path.getPathString());
    }

    assertThat(actualStrings).containsExactlyElementsIn(Arrays.asList(expected));
  }

  @Test
  public void testGlob() throws Exception {
    Collection<Path> textFiles =
        new UnixGlob.Builder(fs.getPath(tmpDir.getPath()), SyscallCache.NO_CACHE)
            .addPattern("*/*.txt")
            .globInterruptible();
    assertThat(textFiles).hasSize(1);
    Path onlyFile = textFiles.iterator().next();
    assertThat(onlyFile).isEqualTo(fs.getPath(anotherFile.getPath()));

    Collection<Path> onlyFiles =
        new UnixGlob.Builder(fs.getPath(tmpDir.getPath()), SyscallCache.NO_CACHE)
            .addPattern("*")
            .setPathDiscriminator(
                new TestUnixGlobPathDiscriminator(p -> true, (p, isDir) -> !isDir))
            .globInterruptible();
    assertPathSet(onlyFiles, aFile.getPath());

    Collection<Path> directoriesToo =
        new UnixGlob.Builder(fs.getPath(tmpDir.getPath()), SyscallCache.NO_CACHE)
            .addPattern("*")
            .setPathDiscriminator(new TestUnixGlobPathDiscriminator(p -> true, (p, isDir) -> true))
            .globInterruptible();
    assertPathSet(directoriesToo, aFile.getPath(), aDirectory.getPath());
  }

  @Test
  public void testGetRelative() {
    Path relative = fs.getPath("/foo").getChild("bar");
    Path expected = fs.getPath("/foo/bar");
    assertThat(relative).isEqualTo(expected);
  }

  @Test
  public void testEqualsAndHash() {
    Path path = fs.getPath("/foo/bar");
    Path equalPath = fs.getPath("/foo/bar");
    Path differentPath = fs.getPath("/foo/bar/baz");
    Object differentType = new Object();

    new EqualsTester().addEqualityGroup(path, equalPath).testEquals();
    assertThat(path.equals(differentPath)).isFalse();
    assertThat(path.equals(differentType)).isFalse();
  }

  @Test
  public void testLatin1ReadAndWrite() throws IOException {
    char[] allLatin1Chars = new char[256];
    for (int i = 0; i < 256; i++) {
      allLatin1Chars[i] = (char) i;
    }
    Path path = fs.getPath(aFile.getPath());
    String latin1String = new String(allLatin1Chars);
    FileSystemUtils.writeContent(path, latin1String);
    String fileContent = FileSystemUtils.readContentToString(path);
    assertThat(latin1String).isEqualTo(fileContent);
  }

  /**
   * Verify that the encoding implemented by {@link
   * com.google.devtools.build.lib.vfs.FileSystemUtils#writeContent(Path, String)} really is 8859-1
   * (latin1).
   */
  @Test
  public void testVerifyLatin1() throws IOException {
    char[] allLatin1Chars = new char[256];
    for( int i = 0; i < 256; i++) {
      allLatin1Chars[i] = (char)i;
    }
    Path path = fs.getPath(aFile.getPath());
    String latin1String = new String(allLatin1Chars);
    FileSystemUtils.writeContent(path, latin1String);
    byte[] bytes = FileSystemUtils.readContent(path);
    assertThat(latin1String).isEqualTo(new String(bytes, ISO_8859_1));
  }

  @Test
  public void testBytesReadAndWrite() throws IOException {
    byte[] bytes = new byte[] { (byte) 0xdeadbeef, (byte) 0xdeadbeef>>8,
                                (byte) 0xdeadbeef>>16, (byte) 0xdeadbeef>>24 };
    Path path = fs.getPath(aFile.getPath());
    FileSystemUtils.writeContent(path, bytes);
    byte[] content = FileSystemUtils.readContent(path);
    assertThat(content).hasLength(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      assertThat(content[i]).isEqualTo(bytes[i]);
    }
  }

  @Test
  public void testInputOutputStreams() throws IOException {
    Path path = fs.getPath(aFile.getPath());
    try (OutputStream out = path.getOutputStream()) {
      for (int i = 0; i < 256; i++) {
        out.write(i);
      }
    }
    try (InputStream in = path.getInputStream()) {
      for (int i = 0; i < 256; i++) {
        assertThat(in.read()).isEqualTo(i);
      }
      assertThat(in.read()).isEqualTo(-1);
    }
  }
}
