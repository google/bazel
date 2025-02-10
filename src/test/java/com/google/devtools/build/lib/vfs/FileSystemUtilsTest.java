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
import static com.google.devtools.build.lib.vfs.FileSystemUtils.appendWithoutExtension;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.commonAncestor;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.copyTool;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.relativePath;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.removeExtension;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.traverseTree;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.vfs.FileSystemUtils.MoveResult;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * This class tests the file system utilities.
 */
@RunWith(JUnit4.class)
public class FileSystemUtilsTest {
  private ManualClock clock;
  private FileSystem fileSystem;
  private Path workingDir;

  @Before
  public final void initializeFileSystem() throws Exception  {
    clock = new ManualClock();
    fileSystem = new InMemoryFileSystem(clock, DigestHashFunction.SHA256);
    workingDir = fileSystem.getPath("/workingDir");
    workingDir.createDirectory();
  }

  Path topDir;
  Path file1;
  Path file2;
  Path aDir;
  Path bDir;
  Path file3;
  Path innerDir;
  Path link1;
  Path dirLink;
  Path file4;
  Path file5;

  /*
   * Build a directory tree that looks like:
   *   top-dir/
   *     file-1
   *     file-2
   *     a-dir/
   *       file-3
   *       inner-dir/
   *         link-1 => file-4
   *         dir-link => b-dir
   *   file-4
   */
  private void createTestDirectoryTree() throws IOException {
    topDir = fileSystem.getPath("/top-dir");
    file1 = fileSystem.getPath("/top-dir/file-1");
    file2 = fileSystem.getPath("/top-dir/file-2");
    aDir = fileSystem.getPath("/top-dir/a-dir");
    bDir = fileSystem.getPath("/top-dir/b-dir");
    file3 = fileSystem.getPath("/top-dir/a-dir/file-3");
    innerDir = fileSystem.getPath("/top-dir/a-dir/inner-dir");
    link1 = fileSystem.getPath("/top-dir/a-dir/inner-dir/link-1");
    dirLink = fileSystem.getPath("/top-dir/a-dir/inner-dir/dir-link");
    file4 = fileSystem.getPath("/file-4");
    file5 = fileSystem.getPath("/top-dir/b-dir/file-5");

    topDir.createDirectory();
    FileSystemUtils.createEmptyFile(file1);
    FileSystemUtils.createEmptyFile(file2);
    aDir.createDirectory();
    bDir.createDirectory();
    FileSystemUtils.createEmptyFile(file3);
    innerDir.createDirectory();
    link1.createSymbolicLink(file4);  // simple symlink
    dirLink.createSymbolicLink(bDir);
    FileSystemUtils.createEmptyFile(file4);
    FileSystemUtils.createEmptyFile(file5);
  }

  private Path checkTestDirectoryTreesBelowExceptSymlinks(Path toPath) throws IOException {
    Path copiedFile1 = toPath.getChild("file-1");
    assertThat(copiedFile1.exists()).isTrue();
    assertThat(copiedFile1.isFile()).isTrue();

    Path copiedFile2 = toPath.getChild("file-2");
    assertThat(copiedFile2.exists()).isTrue();
    assertThat(copiedFile2.isFile()).isTrue();

    Path copiedADir = toPath.getChild("a-dir");
    assertThat(copiedADir.exists()).isTrue();
    assertThat(copiedADir.isDirectory()).isTrue();
    Collection<Path> aDirEntries = copiedADir.getDirectoryEntries();
    assertThat(aDirEntries).hasSize(2);

    Path copiedFile3 = copiedADir.getChild("file-3");
    assertThat(copiedFile3.exists()).isTrue();
    assertThat(copiedFile3.isFile()).isTrue();

    Path copiedInnerDir = copiedADir.getChild("inner-dir");
    assertThat(copiedInnerDir.exists()).isTrue();
    assertThat(copiedInnerDir.isDirectory()).isTrue();
    return copiedInnerDir;
  }

  private void checkTestDirectoryTreesBelow(Path toPath) throws IOException {
    Path copiedInnerDir = checkTestDirectoryTreesBelowExceptSymlinks(toPath);
    Path copiedLink1 = copiedInnerDir.getChild("link-1");
    assertThat(copiedLink1.exists()).isTrue();
    assertThat(copiedLink1.isSymbolicLink()).isFalse();

    Path copiedDirLink = copiedInnerDir.getChild("dir-link");
    assertThat(copiedDirLink.exists()).isTrue();
    assertThat(copiedDirLink.isDirectory()).isTrue();
    assertThat(copiedDirLink.getChild("file-5").exists()).isTrue();
  }

  // tests

  @Test
  public void testChangeModtime() throws IOException {
    Path file = fileSystem.getPath("/my-file");
    assertThrows(FileNotFoundException.class, () -> BlazeTestUtils.changeModtime(file));
    FileSystemUtils.createEmptyFile(file);
    long prevMtime = file.getLastModifiedTime();
    BlazeTestUtils.changeModtime(file);
    assertThat(prevMtime == file.getLastModifiedTime()).isFalse();
  }

  @Test
  public void testCommonAncestor() {
    assertThat(commonAncestor(topDir, topDir)).isEqualTo(topDir);
    assertThat(commonAncestor(file1, file3)).isEqualTo(topDir);
    assertThat(commonAncestor(file1, dirLink)).isEqualTo(topDir);
  }

  @Test
  public void testRelativePath() throws IOException {
    createTestDirectoryTree();
    assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/top-dir/file-1"))
                .getPathString())
        .isEqualTo("file-1");
    assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/top-dir"))
                .getPathString())
        .isEqualTo("");
    assertThat(
            relativePath(
                    PathFragment.create("/top-dir"),
                    PathFragment.create("/top-dir/a-dir/inner-dir/dir-link"))
                .getPathString())
        .isEqualTo("a-dir/inner-dir/dir-link");
    assertThat(
            relativePath(PathFragment.create("/top-dir"), PathFragment.create("/file-4"))
                .getPathString())
        .isEqualTo("../file-4");
    assertThat(
            relativePath(
                    PathFragment.create("/top-dir/a-dir/inner-dir"), PathFragment.create("/file-4"))
                .getPathString())
        .isEqualTo("../../../file-4");
  }

  @Test
  public void testRemoveExtension_strings() throws Exception {
    assertThat(removeExtension("foo.c")).isEqualTo("foo");
    assertThat(removeExtension("a/foo.c")).isEqualTo("a/foo");
    assertThat(removeExtension("a.b/foo")).isEqualTo("a.b/foo");
    assertThat(removeExtension("foo")).isEqualTo("foo");
    assertThat(removeExtension("foo.")).isEqualTo("foo");
  }

  @Test
  public void testRemoveExtension_paths() throws Exception {
    assertPath("/foo", removeExtension(fileSystem.getPath("/foo.c")));
    assertPath("/a/foo", removeExtension(fileSystem.getPath("/a/foo.c")));
    assertPath("/a.b/foo", removeExtension(fileSystem.getPath("/a.b/foo")));
    assertPath("/foo", removeExtension(fileSystem.getPath("/foo")));
    assertPath("/foo", removeExtension(fileSystem.getPath("/foo.")));
  }

  private static void assertPath(String expected, PathFragment actual) {
    assertThat(actual.getPathString()).isEqualTo(expected);
  }

  private static void assertPath(String expected, Path actual) {
    assertThat(actual.getPathString()).isEqualTo(expected);
  }

  @Test
  public void testReplaceExtension_path() throws Exception {
    assertPath("/foo/bar.baz",
               FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/bar"), ".baz"));
    assertPath("/foo/bar.baz",
               FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/bar.cc"), ".baz"));
    assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo/"), ".baz"));
    assertPath("/foo.baz",
               FileSystemUtils.replaceExtension(fileSystem.getPath("/foo.cc/"), ".baz"));
    assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo"), ".baz"));
    assertPath("/foo.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/foo.cc"), ".baz"));
    assertPath("/.baz", FileSystemUtils.replaceExtension(fileSystem.getPath("/.cc"), ".baz"));
    assertThat(FileSystemUtils.replaceExtension(fileSystem.getPath("/"), ".baz")).isNull();
  }

  @Test
  public void testReplaceExtension_pathFragment() throws Exception {
    assertPath("foo/bar.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("foo/bar"), ".baz"));
    assertPath("foo/bar.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("foo/bar.cc"), ".baz"));
    assertPath("/foo/bar.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("/foo/bar"), ".baz"));
    assertPath("/foo/bar.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("/foo/bar.cc"), ".baz"));
    assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo/"), ".baz"));
    assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo.cc/"), ".baz"));
    assertPath("/foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("/foo/"), ".baz"));
    assertPath("/foo.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("/foo.cc/"), ".baz"));
    assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo"), ".baz"));
    assertPath("foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("foo.cc"), ".baz"));
    assertPath("/foo.baz", FileSystemUtils.replaceExtension(PathFragment.create("/foo"), ".baz"));
    assertPath("/foo.baz",
               FileSystemUtils.replaceExtension(PathFragment.create("/foo.cc"), ".baz"));
    assertPath(".baz", FileSystemUtils.replaceExtension(PathFragment.create(".cc"), ".baz"));
    assertThat(FileSystemUtils.replaceExtension(PathFragment.create("/"), ".baz")).isNull();
    assertThat(FileSystemUtils.replaceExtension(PathFragment.create(""), ".baz")).isNull();
    assertPath("foo/bar.baz",
        FileSystemUtils.replaceExtension(PathFragment.create("foo/bar.pony"), ".baz", ".pony"));
    assertPath("foo/bar.baz",
        FileSystemUtils.replaceExtension(PathFragment.create("foo/bar"), ".baz", ""));
    assertThat(FileSystemUtils.replaceExtension(PathFragment.create(""), ".baz", ".pony")).isNull();
    assertThat(
            FileSystemUtils.replaceExtension(
                PathFragment.create("foo/bar.pony"), ".baz", ".unicorn"))
        .isNull();
  }

  @Test
  public void testAppendWithoutExtension() throws Exception {
    assertPath("libfoo-src.jar",
        appendWithoutExtension(PathFragment.create("libfoo.jar"), "-src"));
    assertPath("foo/libfoo-src.jar",
        appendWithoutExtension(PathFragment.create("foo/libfoo.jar"), "-src"));
    assertPath("java/com/google/foo/libfoo-src.jar",
        appendWithoutExtension(PathFragment.create("java/com/google/foo/libfoo.jar"), "-src"));
    assertPath("libfoo.bar-src.jar",
        appendWithoutExtension(PathFragment.create("libfoo.bar.jar"), "-src"));
    assertPath("libfoo-src",
        appendWithoutExtension(PathFragment.create("libfoo"), "-src"));
    assertPath("libfoo-src.jar",
        appendWithoutExtension(PathFragment.create("libfoo.jar/"), "-src"));
    assertPath("libfoo.src.jar",
        appendWithoutExtension(PathFragment.create("libfoo.jar"), ".src"));
    assertThat(appendWithoutExtension(PathFragment.create("/"), "-src")).isNull();
    assertThat(appendWithoutExtension(PathFragment.create(""), "-src")).isNull();
  }

  @Test
  public void testGetWorkingDirectory() {
    String userDir = System.getProperty("user.dir");

    assertThat(fileSystem.getPath(System.getProperty("user.dir", "/")))
        .isEqualTo(FileSystemUtils.getWorkingDirectory(fileSystem));

    System.setProperty("user.dir", "/blah/blah/blah");
    assertThat(fileSystem.getPath("/blah/blah/blah"))
        .isEqualTo(FileSystemUtils.getWorkingDirectory(fileSystem));

    System.setProperty("user.dir", userDir);
  }

  @Test
  public void testResolveRelativeToFilesystemWorkingDir() {
    PathFragment relativePath = PathFragment.create("relative/path");
    assertThat(workingDir.getRelative(relativePath))
        .isEqualTo(workingDir.getRelative(relativePath));

    PathFragment absolutePath = PathFragment.create("/absolute/path");
    assertThat(workingDir.getRelative(absolutePath)).isEqualTo(fileSystem.getPath(absolutePath));
  }

  @Test
  public void testTouchFileCreatesFile() throws IOException {
    createTestDirectoryTree();
    Path nonExistingFile = fileSystem.getPath("/previously-non-existing");
    assertThat(nonExistingFile.exists()).isFalse();
    touchFile(nonExistingFile);

    assertThat(nonExistingFile.exists()).isTrue();
  }

  @Test
  public void testTouchFileAdjustsFileTime() throws IOException {
    createTestDirectoryTree();
    Path testFile = file4;
    long oldTime = testFile.getLastModifiedTime();
    testFile.setLastModifiedTime(42);
    touchFile(testFile);

    assertThat(testFile.getLastModifiedTime()).isAtLeast(oldTime);
  }

  @Test
  public void testCopyFile() throws IOException {
    createTestDirectoryTree();
    Path originalFile = file1;
    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.writeContent(originalFile, content);

    Path copyTarget = file2;

    copyFile(originalFile, copyTarget);

    assertThat(FileSystemUtils.readContent(copyTarget)).isEqualTo(content);
  }

  @Test
  public void testMoveFile() throws IOException {
    createTestDirectoryTree();
    Path originalFile = file1;
    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.writeContent(originalFile, content);

    Path moveTarget = file2;

    assertThat(moveFile(originalFile, moveTarget)).isEqualTo(MoveResult.FILE_MOVED);

    assertThat(FileSystemUtils.readContent(moveTarget)).isEqualTo(content);
    assertThat(originalFile.exists()).isFalse();
  }

  @Test
  public void testMoveFileAcrossDevices() throws Exception {
    FileSystem fs = new MultipleDeviceFS();
    Path source = fs.getPath("/fs1/source");
    source.getParentDirectory().createDirectoryAndParents();
    Path target = fs.getPath("/fs2/target");
    target.getParentDirectory().createDirectoryAndParents();

    FileSystemUtils.writeContent(source, UTF_8, "hello, world");
    source.setLastModifiedTime(142);
    assertThat(FileSystemUtils.moveFile(source, target)).isEqualTo(MoveResult.FILE_COPIED);
    assertThat(source.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(target.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(FileSystemUtils.readContent(target, UTF_8)).isEqualTo("hello, world");
    assertThat(target.getLastModifiedTime()).isEqualTo(142);

    source.createSymbolicLink(PathFragment.create("link-target"));

    assertThat(FileSystemUtils.moveFile(source, target)).isEqualTo(MoveResult.FILE_COPIED);

    assertThat(source.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(target.isSymbolicLink()).isTrue();
    assertThat(target.readSymbolicLink()).isEqualTo(PathFragment.create("link-target"));
  }

  @Test
  public void testMoveFileFixPermissions() throws Exception {
    FileSystem fs = new MultipleDeviceFS();
    Path source = fs.getPath("/fs1/source");
    source.getParentDirectory().createDirectoryAndParents();
    Path target = fs.getPath("/fs2/target");
    target.getParentDirectory().createDirectoryAndParents();

    FileSystemUtils.writeContent(source, UTF_8, "linear-a");
    source.setLastModifiedTime(142);
    source.setReadable(false);

    MoveResult moveResult = moveFile(source, target);

    assertThat(moveResult).isEqualTo(MoveResult.FILE_COPIED);
    assertThat(source.exists(Symlinks.NOFOLLOW)).isFalse();
    assertThat(target.isFile(Symlinks.NOFOLLOW)).isTrue();
    assertThat(FileSystemUtils.readContent(target, UTF_8)).isEqualTo("linear-a");
  }

  @Test
  public void testReadWithKnownFileSize() throws IOException {
    createTestDirectoryTree();
    String str = "this is a test of readContentWithLimit method";
    FileSystemUtils.writeContent(file1, ISO_8859_1, str);

    assertThat(FileSystemUtils.readWithKnownFileSize(file1, str.length()))
        .isEqualTo(str.getBytes(ISO_8859_1));
    assertThrows(
        FileSystemUtils.LongReadIOException.class,
        () -> FileSystemUtils.readWithKnownFileSize(file1, str.length() - 1));
    assertThrows(
        FileSystemUtils.ShortReadIOException.class,
        () -> FileSystemUtils.readWithKnownFileSize(file1, str.length() + 1));
  }

  @Test
  public void testAppend() throws IOException {
    createTestDirectoryTree();
    FileSystemUtils.writeIsoLatin1(file1, "nobody says ");
    FileSystemUtils.writeIsoLatin1(file1, "mary had");
    FileSystemUtils.appendIsoLatin1(file1, "a little lamb");
    assertThat(new String(FileSystemUtils.readContentAsLatin1(file1)))
        .isEqualTo("mary had\na little lamb\n");
  }

  @Test
  public void testCopyFileAttributes() throws IOException {
    createTestDirectoryTree();
    Path originalFile = file1;
    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.writeContent(originalFile, content);
    file1.setLastModifiedTime(12345L);
    file1.setWritable(false);
    file1.setExecutable(false);

    Path copyTarget = file2;
    copyFile(originalFile, copyTarget);

    assertThat(file2.getLastModifiedTime()).isEqualTo(12345L);
    assertThat(file2.isExecutable()).isFalse();
    assertThat(file2.isWritable()).isFalse();

    file1.setWritable(true);
    file1.setExecutable(true);

    copyFile(originalFile, copyTarget);

    assertThat(file2.getLastModifiedTime()).isEqualTo(12345L);
    assertThat(file2.isExecutable()).isTrue();
    assertThat(file2.isWritable()).isTrue();
  }

  @Test
  public void testCopyFileThrowsExceptionIfTargetCantBeDeleted() throws IOException {
    createTestDirectoryTree();
    Path originalFile = file1;
    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.writeContent(originalFile, content);

    IOException ex = assertThrows(IOException.class, () -> copyFile(originalFile, aDir));
    assertThat(ex)
        .hasMessageThat()
        .isEqualTo(
            "error copying file: couldn't delete destination: " + aDir + " (Directory not empty)");
  }

  @Test
  public void testCopyTool() throws IOException {
    createTestDirectoryTree();
    Path originalFile = file1;
    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.writeContent(originalFile, content);

    Path copyTarget = copyTool(topDir.getRelative("file-1"), aDir.getRelative("file-1"));

    assertThat(FileSystemUtils.readContent(copyTarget)).isEqualTo(content);
    assertThat(copyTarget.isWritable()).isEqualTo(file1.isWritable());
    assertThat(copyTarget.isExecutable()).isEqualTo(file1.isExecutable());
    assertThat(copyTarget.getLastModifiedTime()).isEqualTo(file1.getLastModifiedTime());
  }

  @Test
  public void testCopyTreesBelow() throws IOException {
    createTestDirectoryTree();
    Path toPath = fileSystem.getPath("/copy-here");
    toPath.createDirectory();

    FileSystemUtils.copyTreesBelow(topDir, toPath, Symlinks.FOLLOW);
    checkTestDirectoryTreesBelow(toPath);
  }

  @Test
  public void testCopyTreesBelowWithOverriding() throws IOException {
    createTestDirectoryTree();
    Path toPath = fileSystem.getPath("/copy-here");
    toPath.createDirectory();
    toPath.getChild("file-2");

    FileSystemUtils.copyTreesBelow(topDir, toPath, Symlinks.FOLLOW);
    checkTestDirectoryTreesBelow(toPath);
  }

  @Test
  public void testCopyTreesBelowToSubtree() throws IOException {
    createTestDirectoryTree();
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> FileSystemUtils.copyTreesBelow(topDir, aDir, Symlinks.FOLLOW));
    assertThat(expected).hasMessageThat().isEqualTo("/top-dir/a-dir is a subdirectory of /top-dir");
  }

  @Test
  public void testCopyFileAsDirectoryTree() throws IOException {
    createTestDirectoryTree();
    IOException expected =
        assertThrows(
            IOException.class, () -> FileSystemUtils.copyTreesBelow(file1, aDir, Symlinks.FOLLOW));
    assertThat(expected).hasMessageThat().isEqualTo("/top-dir/file-1 (Not a directory)");
  }

  @Test
  public void testCopyTreesBelowToFile() throws IOException {
    createTestDirectoryTree();
    Path copyDir = fileSystem.getPath("/my-dir");
    Path copySubDir = fileSystem.getPath("/my-dir/subdir");
    copySubDir.createDirectoryAndParents();
    IOException expected =
        assertThrows(
            IOException.class,
            () -> FileSystemUtils.copyTreesBelow(copyDir, file4, Symlinks.FOLLOW));
    assertThat(expected).hasMessageThat().isEqualTo("/file-4 (Not a directory)");
  }

  @Test
  public void testCopyTreesBelowFromUnexistingDir() throws IOException {
    createTestDirectoryTree();

    Path unexistingDir = fileSystem.getPath("/unexisting-dir");
    FileNotFoundException expected =
        assertThrows(
            FileNotFoundException.class,
            () -> FileSystemUtils.copyTreesBelow(unexistingDir, aDir, Symlinks.FOLLOW));
    assertThat(expected).hasMessageThat().isEqualTo("/unexisting-dir (No such file or directory)");
  }

  @Test
  public void testCopyTreesBelowNoFollowSymlinks() throws IOException {
    createTestDirectoryTree();
    PathFragment relative1 = file1.relativeTo(topDir);
    topDir.getRelative("relative-link").createSymbolicLink(relative1);
    PathFragment relativeInner = PathFragment.create("..").getRelative(relative1);
    bDir.getRelative("relative-inner-link").createSymbolicLink(relativeInner);
    PathFragment rootDirectory = PathFragment.create("/");
    topDir.getRelative("absolute-link").createSymbolicLink(rootDirectory);
    Path toPath = fileSystem.getPath("/copy-here");
    toPath.createDirectory();

    FileSystemUtils.copyTreesBelow(topDir, toPath, Symlinks.NOFOLLOW);
    Path copiedInnerDir = checkTestDirectoryTreesBelowExceptSymlinks(toPath);
    Path copiedLink1 = copiedInnerDir.getChild("link-1");
    assertThat(copiedLink1.exists()).isTrue();
    assertThat(copiedLink1.isSymbolicLink()).isTrue();
    assertThat(copiedLink1.readSymbolicLink()).isEqualTo(file4.asFragment());

    Path copiedDirLink = copiedInnerDir.getChild("dir-link");
    assertThat(copiedDirLink.exists()).isTrue();
    assertThat(copiedDirLink.isDirectory()).isTrue();
    assertThat(copiedDirLink.readSymbolicLink()).isEqualTo(bDir.asFragment());

    assertThat(toPath.getRelative("relative-link").readSymbolicLink()).isEqualTo(relative1);
    assertThat(
            toPath
                .getRelative(bDir.relativeTo(topDir))
                .getRelative("relative-inner-link")
                .readSymbolicLink())
        .isEqualTo(relativeInner);
    assertThat(toPath.getRelative("absolute-link").readSymbolicLink()).isEqualTo(rootDirectory);
  }

  @Test
  public void testTraverseTree() throws IOException {
    createTestDirectoryTree();

    Collection<Path> paths = traverseTree(topDir, p -> !p.getPathString().contains("a-dir"));
    assertThat(paths).containsExactly(file1, file2, bDir, file5);
  }

  @Test
  public void testTraverseTreeDeep() throws IOException {
    createTestDirectoryTree();

    Collection<Path> paths = traverseTree(topDir, ignored -> true);
    assertThat(paths).containsExactly(aDir,
        file3,
        innerDir,
        link1,
        file1,
        file2,
        dirLink,
        bDir,
        file5);
  }

  @Test
  public void testTraverseTreeLinkDir() throws IOException {
    // Use a new little tree for this test:
    //  top-dir/
    //    dir-link2 => linked-dir
    //  linked-dir/
    //    file
    topDir = fileSystem.getPath("/top-dir");
    Path dirLink2 = fileSystem.getPath("/top-dir/dir-link2");
    Path linkedDir = fileSystem.getPath("/linked-dir");
    Path linkedDirFile = fileSystem.getPath("/top-dir/dir-link2/file");

    topDir.createDirectory();
    linkedDir.createDirectory();
    dirLink2.createSymbolicLink(linkedDir);  // simple symlink
    FileSystemUtils.createEmptyFile(linkedDirFile);  // created through the link

    // traverseTree doesn't follow links:
    Collection<Path> paths = traverseTree(topDir, ignored -> true);
    assertThat(paths).containsExactly(dirLink2);

    paths = traverseTree(linkedDir, ignored -> true);
    assertThat(paths).containsExactly(fileSystem.getPath("/linked-dir/file"));
  }

  @Test
  public void testWriteIsoLatin1() throws Exception {
    Path file = fileSystem.getPath("/does/not/exist/yet.txt");
    FileSystemUtils.writeIsoLatin1(file, "Line 1", "Line 2", "Line 3");
    String expected = "Line 1\nLine 2\nLine 3\n";
    String actual = new String(FileSystemUtils.readContentAsLatin1(file));
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void testWriteLinesAs() throws Exception {
    Path file = fileSystem.getPath("/does/not/exist/yet.txt");
    FileSystemUtils.writeLinesAs(file, UTF_8, "\u00F6"); // an oe umlaut
    byte[] expected = new byte[] {(byte) 0xC3, (byte) 0xB6, 0x0A};//"\u00F6\n";
    byte[] actual = FileSystemUtils.readContent(file);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void testUpdateContent() throws Exception {
    Path file = fileSystem.getPath("/test.txt");

    clock.advanceMillis(1000);

    byte[] content = new byte[] { 'a', 'b', 'c', 23, 42 };
    FileSystemUtils.maybeUpdateContent(file, content);
    byte[] actual = FileSystemUtils.readContent(file);
    assertThat(actual).isEqualTo(content);
    FileStatus stat = file.stat();
    assertThat(stat.getLastChangeTime()).isEqualTo(1000);
    assertThat(stat.getLastModifiedTime()).isEqualTo(1000);

    clock.advanceMillis(1000);

    // Update with same contents; should not write anything.
    FileSystemUtils.maybeUpdateContent(file, content);
    assertThat(actual).isEqualTo(content);
    stat = file.stat();
    assertThat(stat.getLastChangeTime()).isEqualTo(1000);
    assertThat(stat.getLastModifiedTime()).isEqualTo(1000);

    clock.advanceMillis(1000);

    // Update with different contents; file should be rewritten.
    content[0] = 'b';
    file.chmod(0400);  // Protect the file to ensure we can rewrite it.
    FileSystemUtils.maybeUpdateContent(file, content);
    actual = FileSystemUtils.readContent(file);
    assertThat(actual).isEqualTo(content);
    stat = file.stat();
    assertThat(stat.getLastChangeTime()).isEqualTo(3000);
    assertThat(stat.getLastModifiedTime()).isEqualTo(3000);
  }

  @Test
  public void testGetFileSystem() throws Exception {
    Path mountTable = fileSystem.getPath("/proc/mounts");
    FileSystemUtils.writeIsoLatin1(mountTable,
        "/dev/sda1 / ext2 blah 0 0",
        "/dev/mapper/_dev_sda6 /usr/local/google ext3 blah 0 0",
        "devshm /dev/shm tmpfs blah 0 0",
        "/dev/fuse /fuse/mnt fuse blah 0 0",
        "mtvhome22.nfs:/vol/mtvhome22/johndoe /home/johndoe nfs blah 0 0",
        "/dev/foo /foo dummy_foo blah 0 0",
        "/dev/foobar /foobar dummy_foobar blah 0 0",
        "proc proc proc rw,noexec,nosuid,nodev 0 0");
    Path path = fileSystem.getPath("/usr/local/google/_blaze");
    path.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("ext3");

    // Should match the root "/"
    path = fileSystem.getPath("/usr/local/tmp");
    path.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("ext2");

    // Make sure we don't consider /foobar matches /foo
    path = fileSystem.getPath("/foo");
    path.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("dummy_foo");
    path = fileSystem.getPath("/foobar");
    path.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("dummy_foobar");

    path = fileSystem.getPath("/dev/shm/blaze");
    path.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("tmpfs");

    Path fusePath = fileSystem.getPath("/fuse/mnt/tmp");
    fusePath.createDirectoryAndParents();
    assertThat(FileSystemUtils.getFileSystem(fusePath)).isEqualTo("fuse");

    // Create a symlink and make sure it gives the file system of the symlink target.
    path = fileSystem.getPath("/usr/local/google/_blaze/out");
    path.createSymbolicLink(fusePath);
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("fuse");

    // Non existent path should return "unknown"
    path = fileSystem.getPath("/does/not/exist");
    assertThat(FileSystemUtils.getFileSystem(path)).isEqualTo("unknown");
  }

  @Test
  public void testStartsWithAnySuccess() throws Exception {
    PathFragment a = PathFragment.create("a");
    assertThat(
            FileSystemUtils.startsWithAny(
                a, Arrays.asList(PathFragment.create("b"), PathFragment.create("a"))))
        .isTrue();
  }

  @Test
  public void testStartsWithAnyNotFound() throws Exception {
    PathFragment a = PathFragment.create("a");
    assertThat(
            FileSystemUtils.startsWithAny(
                a, Arrays.asList(PathFragment.create("b"), PathFragment.create("c"))))
        .isFalse();
  }

  @Test
  public void testReadLines() throws Exception {
    Path file = fileSystem.getPath("/test.txt");
    FileSystemUtils.writeContent(file, ISO_8859_1, "a\nb");
    assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder();

    FileSystemUtils.writeContent(file, ISO_8859_1, "a\rb");
    assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder();

    FileSystemUtils.writeContent(file, ISO_8859_1, "a\r\nb");
    assertThat(FileSystemUtils.readLinesAsLatin1(file)).containsExactly("a", "b").inOrder();
  }

  @Test
  public void testEnsureSymbolicLinkDoesNotMakeUnnecessaryChanges() throws Exception {
    PathFragment target = PathFragment.create("/b");
    Path file = fileSystem.getPath("/a");
    file.createSymbolicLink(target);
    long prevTimeMillis = clock.currentTimeMillis();
    clock.advanceMillis(1000);
    FileSystemUtils.ensureSymbolicLink(file, target);
    long timestamp = file.getLastModifiedTime(Symlinks.NOFOLLOW);
    assertThat(timestamp).isEqualTo(prevTimeMillis);
  }

  @Test
  public void testCreateHardLinkForFile_success() throws Exception {

    /* Original file exists and link file does not exist */
    Path originalPath = workingDir.getRelative("original");
    Path linkPath = workingDir.getRelative("link");
    FileSystemUtils.createEmptyFile(originalPath);
    FileSystemUtils.createHardLink(linkPath, originalPath);
    assertThat(originalPath.exists()).isTrue();
    assertThat(linkPath.exists()).isTrue();
    assertThat(fileSystem.stat(linkPath.asFragment(), false).getNodeId())
        .isEqualTo(fileSystem.stat(originalPath.asFragment(), false).getNodeId());
  }

  @Test
  public void testCreateHardLinkForEmptyDirectory_success() throws Exception {

    Path originalDir = workingDir.getRelative("originalDir");
    Path linkPath = workingDir.getRelative("link");

    originalDir.createDirectoryAndParents();

    /* Original directory is empty, no link to be created. */
    FileSystemUtils.createHardLink(linkPath, originalDir);
    assertThat(linkPath.exists()).isFalse();
  }

  @Test
  public void testCreateHardLinkForNonEmptyDirectory_success() throws Exception {

    /* Test when original path is a directory */
    Path originalDir = workingDir.getRelative("originalDir");
    Path linkPath = workingDir.getRelative("link");
    Path originalPath1 = originalDir.getRelative("original1");
    Path originalPath2 = originalDir.getRelative("original2");
    Path originalPath3 = originalDir.getRelative("original3");
    Path linkPath1 = linkPath.getRelative("original1");
    Path linkPath2 = linkPath.getRelative("original2");
    Path linkPath3 = linkPath.getRelative("original3");

    originalDir.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(originalPath1);
    FileSystemUtils.createEmptyFile(originalPath2);
    FileSystemUtils.createEmptyFile(originalPath3);

    /* Three link files created under linkPath */
    FileSystemUtils.createHardLink(linkPath, originalDir);
    assertThat(linkPath.exists()).isTrue();
    assertThat(linkPath1.exists()).isTrue();
    assertThat(linkPath2.exists()).isTrue();
    assertThat(linkPath3.exists()).isTrue();
    assertThat(fileSystem.stat(linkPath1.asFragment(), false).getNodeId())
        .isEqualTo(fileSystem.stat(originalPath1.asFragment(), false).getNodeId());
    assertThat(fileSystem.stat(linkPath2.asFragment(), false).getNodeId())
        .isEqualTo(fileSystem.stat(originalPath2.asFragment(), false).getNodeId());
    assertThat(fileSystem.stat(linkPath3.asFragment(), false).getNodeId())
        .isEqualTo(fileSystem.stat(originalPath3.asFragment(), false).getNodeId());
  }

  static class MultipleDeviceFS extends InMemoryFileSystem {
    MultipleDeviceFS() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public void renameTo(PathFragment source, PathFragment target) throws IOException {
      if (!source.startsWith(target.subFragment(0, 1))) {
        throw new IOException("EXDEV");
      }
      super.renameTo(source, target);
    }
  }
}
