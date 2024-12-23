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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSymlinkLoopException;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PathCanonicalizer}. */
@RunWith(JUnit4.class)
public final class PathCanonicalizerTest {

  // Test outline:
  // 1. Set up the filesystem state by calling createSymlink, createNonSymlink or deleteTree.
  // 2. Call assertSuccess or assertFailure to check for successful resolution or failure.

  // On Windows, absolute paths start with a drive letter, e.g. C:/, instead of / as in Unix.
  // To avoid test duplication, when the tests run on Windows, Unix-style absolute paths passed to
  // the above methods will have a C: automatically prepended to them.

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);

  private final PathCanonicalizer canonicalizer = new PathCanonicalizer(this::resolve);

  private @Nullable PathFragment resolve(PathFragment pathFragment) throws IOException {
    Path path = fs.getPath(pathFragment);
    try {
      return path.readSymbolicLink();
    } catch (NotASymlinkException e) {
      return null;
    }
  }

  @Test
  public void testRoot() throws Exception {
    assertSuccess("/", "/");
  }

  @Test
  public void testAlreadyCanonical() throws Exception {
    createNonSymlink("/a/b");
    assertSuccess("/a/b", "/a/b");
  }

  @Test
  public void testAbsoluteSymlinkToFile() throws Exception {
    createSymlink("/a/b", "/c/d");
    createNonSymlink("/c/d");
    assertSuccess("/a/b", "/c/d");
  }

  @Test
  public void testAbsoluteSymlinkToDirectory() throws Exception {
    createSymlink("/a/b", "/d/e");
    createNonSymlink("/d/e/c");
    assertSuccess("/a/b/c", "/d/e/c");
  }

  @Test
  public void testAbsoluteSymlinkToDifferentDrive() throws Exception {
    assumeTrue(OS.getCurrent() == OS.WINDOWS);

    createSymlink("C:/a/b", "D:/e/f");
    createNonSymlink("D:/e/f");
    assertSuccess("C:/a/b/c/d", "D:/e/f/c/d");
  }

  @Test
  public void testRelativeSymlinkToFileInSameDirectory() throws Exception {
    createSymlink("/a/b", "c");
    createNonSymlink("/a/c");
    assertSuccess("/a/b", "/a/c");
  }

  @Test
  public void testRelativeSymlinkToFileInDirectoryBelow() throws Exception {
    createSymlink("/a/b", "c/d");
    createNonSymlink("/a/c/d");
    assertSuccess("/a/b", "/a/c/d");
  }

  @Test
  public void testRelativeSymlinkToFileInDirectoryAbove() throws Exception {
    createSymlink("/a/b/c", "../d/e");
    createNonSymlink("/a/d/e");
    assertSuccess("/a/b/c", "/a/d/e");
  }

  @Test
  public void testRelativeSymlinkToRoot() throws Exception {
    createSymlink("/a/b/c", "../../d");
    createNonSymlink("/d");
    assertSuccess("/a/b/c", "/d");
  }

  @Test
  public void testRelativeSymlinkWithTooManyUplevelReferences() throws Exception {
    createSymlink("/a/b", "../../d");
    createNonSymlink("/d/c");
    assertSuccess("/a/b/c", "/d/c");
  }

  @Test
  public void testMultipleSymlinks() throws Exception {
    createSymlink("/a", "/b");
    createSymlink("/b/c", "/d");
    createSymlink("/d/e", "/f");
    createNonSymlink("/f");
    assertSuccess("/a/c/e", "/f");
  }

  @Test
  public void testReplayCanonical() throws Exception {
    createNonSymlink("/a/b/c");
    assertSuccess("/a/b/c", "/a/b/c");
    assertSuccess("/a/b/c", "/a/b/c");
  }

  @Test
  public void testReplaySymlink() throws Exception {
    createSymlink("/a/b", "/d");
    createNonSymlink("/d/c");
    assertSuccess("/a/b/c", "/d/c");
    assertSuccess("/a/b/c", "/d/c");
  }

  @Test
  public void testDistinguishPathsWithCommonPrefix() throws Exception {
    createSymlink("/a/b", "/d");
    createNonSymlink("/d/c");
    createNonSymlink("/a/e");
    assertSuccess("/a/b/c", "/d/c");
    assertSuccess("/a/e", "/a/e");
  }

  @Test
  public void testDistinguishPathsWithDifferentDriveLetter() throws Exception {
    assumeTrue(OS.getCurrent() == OS.WINDOWS);
    createSymlink("C:/a/b", "D:/d");
    createNonSymlink("D:/d/c");
    createNonSymlink("C:/a/b/c");
    assertSuccess("C:/a/b/c", "D:/d/c");
    assertSuccess("D:/a/b/c", "D:/a/b/c");
  }

  @Test
  public void testClearAndReplaceWithSymlink() throws Exception {
    createNonSymlink("/a/b/c");
    assertSuccess("/a/b/c", "/a/b/c");
    deleteTree("/a/b");
    createSymlink("/a/b", "/d");
    createNonSymlink("/d/c");
    assertSuccess("/a/b/c", "/d/c");
  }

  @Test
  public void testClearAndReplaceWithNonSymlink() throws Exception {
    createSymlink("/a/b", "/d");
    createNonSymlink("/d/c");
    assertSuccess("/a/b/c", "/d/c");
    deleteTree("/a/b");
    createNonSymlink("/a/b/c");
    assertSuccess("/a/b/c", "/a/b/c");
  }

  @Test
  public void testClearSymlinkAndDoNotReplace() throws Exception {
    createSymlink("/a/b", "/d");
    createNonSymlink("/d/c");
    assertSuccess("/a/b/c", "/d/c");
    deleteTree("/a/b");
    assertFailure(FileNotFoundException.class, "/a/b/c");
  }

  @Test
  public void testClearNonSymlinkAndDoNotReplace() throws Exception {
    createNonSymlink("/a/b/c");
    assertSuccess("/a/b/c", "/a/b/c");
    deleteTree("/a/b");
    assertFailure(FileNotFoundException.class, "/a/b/c");
  }

  @Test
  public void testClearUnknownPathDescendingFromSymlink() throws Exception {
    createSymlink("/a/b", "/d");
    createNonSymlink("/d");
    assertSuccess("/a/b", "/d");
    deleteTree("/a/b/c");
    assertSuccess("/a/b", "/d");
  }

  @Test
  public void testClearUnknownPathDescendingFromNonSymlink() throws Exception {
    createNonSymlink("/a/b");
    assertSuccess("/a/b", "/a/b");
    deleteTree("/a/b/c");
    assertSuccess("/a/b", "/a/b");
  }

  @Test
  public void testSymlinkSelfLoop() throws Exception {
    createSymlink("/a/b", "/a/b");
    assertFailure(FileSymlinkLoopException.class, "/a/b");
  }

  @Test
  public void testSymlinkMutualLoop() throws Exception {
    createSymlink("/a/b", "/c/d");
    createSymlink("/c/d", "/a/b");
    assertFailure(FileSymlinkLoopException.class, "/a/b");
  }

  @Test
  public void testSymlinkChainTooLong() throws Exception {
    for (int i = 0; i < FileSystem.MAX_SYMLINKS + 1; i++) {
      createSymlink(String.format("/%s", i), String.format("/%s", i + 1));
    }
    assertFailure(FileSymlinkLoopException.class, "/0");
  }

  @Test
  public void testFileNotFound() throws Exception {
    assertFailure(FileNotFoundException.class, "/a/b");
    createNonSymlink("/a/b");
    assertSuccess("/a/b", "/a/b");
  }

  @Test
  public void testEmpty() throws Exception {
    assertFailure(IllegalArgumentException.class, "");
  }

  @Test
  public void testNonAbsolute() throws Exception {
    assertFailure(IllegalArgumentException.class, "a/b");
  }

  private void createSymlink(String linkPathStr, String targetPathStr) throws Exception {
    Path linkPath = fs.getPath(pathFragment(linkPathStr));
    linkPath.getParentDirectory().createDirectoryAndParents();
    linkPath.createSymbolicLink(pathFragment(targetPathStr));
  }

  private void createNonSymlink(String pathStr) throws Exception {
    Path path = fs.getPath(pathFragment(pathStr));
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, "");
  }

  private void deleteTree(String pathStr) throws Exception {
    canonicalizer.clearPrefix(pathFragment(pathStr));
    fs.getPath(pathFragment(pathStr)).deleteTree();
  }

  private void assertSuccess(String input, String output) throws Exception {
    assertThat(canonicalizer.resolveSymbolicLinks(pathFragment(input)))
        .isEqualTo(pathFragment(output));
  }

  private <T extends Throwable> void assertFailure(Class<T> exceptionClass, String input)
      throws Exception {
    assertThrows(exceptionClass, () -> canonicalizer.resolveSymbolicLinks(pathFragment(input)));
  }

  private static PathFragment pathFragment(String pathStr) {
    if (pathStr.startsWith("/") && OS.getCurrent() == OS.WINDOWS) {
      pathStr = "C:" + pathStr;
    }
    return PathFragment.create(pathStr);
  }
}
