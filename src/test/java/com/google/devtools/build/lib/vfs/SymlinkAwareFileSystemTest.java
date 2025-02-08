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
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.FileSystem.NotASymlinkException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;

/**
 * This class handles the generic tests that any filesystem must pass.
 *
 * <p>Each filesystem-test should inherit from this class, thereby obtaining
 * all the tests.
 */
public abstract class SymlinkAwareFileSystemTest extends FileSystemTest {

  protected Path xLinkToFile;
  protected Path xLinkToLinkToFile;
  protected Path xLinkToDirectory;
  protected Path xDanglingLink;

  @Before
  public final void createSymbolicLinks() throws Exception  {
    // % ls -lR
    // -rw-rw-r-- xFile
    // drwxrwxr-x xNonEmptyDirectory
    // -rw-rw-r-- xNonEmptyDirectory/foo
    // drwxrwxr-x xEmptyDirectory
    // lrwxrwxr-x xLinkToFile -> xFile
    // lrwxrwxr-x xLinkToDirectory -> xEmptyDirectory
    // lrwxrwxr-x xLinkToLinkToFile -> xLinkToFile
    // lrwxrwxr-x xDanglingLink -> xNothing

    xLinkToFile = absolutize("xLinkToFile");
    xLinkToLinkToFile = absolutize("xLinkToLinkToFile");
    xLinkToDirectory = absolutize("xLinkToDirectory");
    xDanglingLink = absolutize("xDanglingLink");

    createSymbolicLink(xLinkToFile, xFile);
    createSymbolicLink(xLinkToLinkToFile, xLinkToFile);
    createSymbolicLink(xLinkToDirectory, xEmptyDirectory);
    createSymbolicLink(xDanglingLink, xNothing);
  }

  @Test
  public void testCreateLinkToFile() throws IOException {
    Path newPath = xEmptyDirectory.getChild("new-file");
    FileSystemUtils.createEmptyFile(newPath);

    Path linkPath = xEmptyDirectory.getChild("some-link");

    createSymbolicLink(linkPath, newPath);

    assertThat(linkPath.isSymbolicLink()).isTrue();

    assertThat(linkPath.isFile()).isTrue();
    assertThat(linkPath.isFile(Symlinks.NOFOLLOW)).isFalse();
    assertThat(linkPath.isFile(Symlinks.FOLLOW)).isTrue();

    assertThat(linkPath.isDirectory()).isFalse();
    assertThat(linkPath.isDirectory(Symlinks.NOFOLLOW)).isFalse();
    assertThat(linkPath.isDirectory(Symlinks.FOLLOW)).isFalse();

    if (testFS.supportsSymbolicLinksNatively(linkPath.asFragment())) {
      assertThat(linkPath.getFileSize(Symlinks.NOFOLLOW)).isEqualTo(newPath.toString().length());
      assertThat(linkPath.getFileSize()).isEqualTo(newPath.getFileSize(Symlinks.NOFOLLOW));
    }
    assertThat(linkPath.getParentDirectory().getDirectoryEntries()).hasSize(2);
    assertThat(linkPath.getParentDirectory().getDirectoryEntries()).containsExactly(newPath,
        linkPath);
  }

  @Test
  public void testCreateLinkToDirectory() throws IOException {
    Path newPath = xEmptyDirectory.getChild("new-file");
    newPath.createDirectory();

    Path linkPath = xEmptyDirectory.getChild("some-link");

    createSymbolicLink(linkPath, newPath);

    assertThat(linkPath.isSymbolicLink()).isTrue();
    assertThat(linkPath.isFile()).isFalse();
    assertThat(linkPath.isDirectory()).isTrue();
    assertThat(linkPath.getParentDirectory().getDirectoryEntries()).hasSize(2);
    assertThat(linkPath.getParentDirectory().
      getDirectoryEntries()).containsExactly(newPath, linkPath);
  }

  @Test
  public void testFileCanonicalPath() throws IOException {
    Path newPath = absolutize("new-file");
    FileSystemUtils.createEmptyFile(newPath);
    newPath = newPath.resolveSymbolicLinks();

    Path link1 = absolutize("some-link");
    Path link2 = absolutize("some-link2");

    createSymbolicLink(link1, newPath);
    createSymbolicLink(link2, link1);

    assertCanonicalPathsMatch(newPath, link1, link2);
  }

  @Test
  public void testDirectoryCanonicalPath() throws IOException {
    Path newPath = absolutize("new-folder");
    newPath.createDirectory();
    newPath = newPath.resolveSymbolicLinks();

    Path newFile = newPath.getChild("file");
    FileSystemUtils.createEmptyFile(newFile);

    Path link1 = absolutize("some-link");
    Path link2 = absolutize("some-link2");

    createSymbolicLink(link1, newPath);
    createSymbolicLink(link2, link1);

    Path linkFile1 = link1.getChild("file");
    Path linkFile2 = link2.getChild("file");

    assertCanonicalPathsMatch(newFile, linkFile1, linkFile2);
  }

  private void assertCanonicalPathsMatch(Path newPath, Path link1, Path link2)
      throws IOException {
    assertThat(link1.resolveSymbolicLinks()).isEqualTo(newPath);
    assertThat(link2.resolveSymbolicLinks()).isEqualTo(newPath);
  }

  //
  //  createDirectory
  //

  @Test
  public void testCreateDirectoryWhereDanglingSymlinkAlreadyExists() {
    IOException e = assertThrows(IOException.class, () -> xDanglingLink.createDirectory());
    assertThat(e).hasMessageThat().isEqualTo(xDanglingLink + " (File exists)");
    assertThat(xDanglingLink.isSymbolicLink()).isTrue(); // still a symbolic link
    assertThat(xDanglingLink.isDirectory(Symlinks.FOLLOW)).isFalse(); // link still dangles
  }

  @Test
  public void testCreateDirectoryWhereSymlinkAlreadyExists() {
    IOException e = assertThrows(IOException.class, () -> xLinkToDirectory.createDirectory());
    assertThat(e).hasMessageThat().isEqualTo(xLinkToDirectory + " (File exists)");
    assertThat(xLinkToDirectory.isSymbolicLink()).isTrue(); // still a symbolic link
    assertThat(xLinkToDirectory.isDirectory(Symlinks.FOLLOW)).isTrue(); // link still points to dir
  }

  //  createSymbolicLink(PathFragment)

  @Test
  public void testCreateSymbolicLinkFromFragment() throws IOException {
    String[] linkTargets = {
      "foo",
      "foo/bar",
      ".",
      "..",
      "../foo",
      "../../foo",
      "../../../../../../../../../../../../../../../../../../../../../foo",
      "/foo",
      "/foo/bar",
      "/..",
      "/foo/../bar",
    };
    Path linkPath = absolutize("link");
    for (String linkTarget : linkTargets) {
      PathFragment relative = PathFragment.create(linkTarget);
      linkPath.delete();
      createSymbolicLink(linkPath, relative);
      if (testFS.supportsSymbolicLinksNatively(linkPath.asFragment())) {
        assertThat(linkPath.getFileSize(Symlinks.NOFOLLOW))
            .isEqualTo(relative.getSafePathString().length());
        assertThat(linkPath.readSymbolicLink()).isEqualTo(relative);
      }
    }
  }

  @Test
  public void testLinkToRootResolvesCorrectly() throws IOException {
    if (OS.getCurrent() == OS.WINDOWS) {
      // This test cannot be run on Windows, it mixes "/" paths with "C:/" paths
      return;
    }
    Path rootPath = testFS.getPath("/");

    try {
      rootPath.getChild("testDir").createDirectory();
    } catch (IOException e) {
      // Do nothing. This is a real FS, and we don't have permission.
    }

    Path linkPath = absolutize("link");
    createSymbolicLink(linkPath, rootPath);

    // resolveSymbolicLinks requires an existing path:
    assertThrows(
        FileNotFoundException.class, () -> linkPath.getRelative("test").resolveSymbolicLinks());

    // The path may not be a symlink, neither on Darwin nor on Linux.
    String nonLinkEntry = null;
    for (String child : testFS.getDirectoryEntries(rootPath.asFragment())) {
      Path p = rootPath.getChild(child);
      if (!p.isSymbolicLink() && p.isDirectory()) {
        nonLinkEntry = p.getBaseName();
        break;
      }
    }

    assertThat(nonLinkEntry).isNotNull();
    Path rootChild = testFS.getPath("/" + nonLinkEntry);
    assertThat(linkPath.getRelative(nonLinkEntry).resolveSymbolicLinks()).isEqualTo(rootChild);
  }

  @Test
  public void testLinkToFragmentContainingLinkResolvesCorrectly() throws IOException {
    Path link1 = absolutize("link1");
    PathFragment link1target = PathFragment.create("link2/foo");
    Path link2 = absolutize("link2");
    Path link2target = xNonEmptyDirectory;

    createSymbolicLink(link1, link1target); // ln -s link2/foo link1
    createSymbolicLink(link2, link2target); // ln -s xNonEmptyDirectory link2
    // link1 --> xNonEmptyDirectory/foo
    assertThat(link2target.getRelative("foo")).isEqualTo(link1.resolveSymbolicLinks());
  }

  //
  //  readSymbolicLink / resolveSymbolicLinks
  //

  @Test
  public void testRecursiveSymbolicLink() throws IOException {
    Path link = absolutize("recursive-link");
    createSymbolicLink(link, link);

    if (testFS.supportsSymbolicLinksNatively(link.asFragment())) {
      IOException e = assertThrows(IOException.class, () -> link.resolveSymbolicLinks());
      assertThat(e).hasMessageThat().isEqualTo(link + " (Too many levels of symbolic links)");
    }
  }

  @Test
  public void testMutuallyRecursiveSymbolicLinks() throws IOException {
    Path link1 = absolutize("link1");
    Path link2 = absolutize("link2");
    createSymbolicLink(link2, link1);
    createSymbolicLink(link1, link2);

    if (testFS.supportsSymbolicLinksNatively(link1.asFragment())) {
      IOException e = assertThrows(IOException.class, () -> link1.resolveSymbolicLinks());
      assertThat(e).hasMessageThat().isEqualTo(link1 + " (Too many levels of symbolic links)");
    }
  }

  @Test
  public void testResolveSymbolicLinksENOENT() {
    if (testFS.supportsSymbolicLinksNatively(xDanglingLink.asFragment())) {
      IOException e = assertThrows(IOException.class, () -> xDanglingLink.resolveSymbolicLinks());
      assertThat(e).hasMessageThat().isEqualTo(xNothing + " (No such file or directory)");
    }
  }

  @Test
  public void testResolveSymbolicLinksENOTDIR() throws IOException {
    Path badLinkTarget = xFile.getChild("bad"); // parent is not a directory!
    Path badLink = absolutize("badLink");
    if (testFS.supportsSymbolicLinksNatively(badLink.asFragment())) {
      createSymbolicLink(badLink, badLinkTarget);
      assertThrows(IOException.class, badLink::resolveSymbolicLinks);
    }
  }

  @Test
  public void testResolveSymbolicLinksWithUplevelRefs() throws IOException {
    if (testFS.supportsSymbolicLinksNatively(xLinkToFile.asFragment())) {
      // Create a series of links that refer to xFile as ./xFile,
      // ./../foo/xFile, ./../../bar/foo/xFile, etc.  They should all resolve
      // to xFile.
      Path ancestor = xFile;
      String prefix = "./";
      while ((ancestor = ancestor.getParentDirectory()) != null) {
        xLinkToFile.delete();
        createSymbolicLink(xLinkToFile, PathFragment.create(prefix + xFile.relativeTo(ancestor)));
        assertThat(xLinkToFile.resolveSymbolicLinks()).isEqualTo(xFile);

        prefix += "../";
      }
    }
  }

  @Test
  public void testReadSymbolicLink() throws IOException {
    if (testFS.supportsSymbolicLinksNatively(xDanglingLink.asFragment())) {
      assertThat(xDanglingLink.readSymbolicLink().toString()).isEqualTo(xNothing.toString());
    }

    assertThat(xLinkToFile.readSymbolicLink().toString()).isEqualTo(xFile.toString());

    assertThat(xLinkToDirectory.readSymbolicLink().toString())
        .isEqualTo(xEmptyDirectory.toString());

    NotASymlinkException nase =
        assertThrows(NotASymlinkException.class, () -> xFile.readSymbolicLink());
    assertThat(nase).hasMessageThat().isEqualTo(xFile.toString() + " is not a symlink");

    FileNotFoundException fnfe =
        assertThrows(FileNotFoundException.class, () -> xNothing.readSymbolicLink());
    assertThat(fnfe).hasMessageThat().isEqualTo(xNothing + " (No such file or directory)");
  }

  @Test
  public void testCannotCreateSymbolicLinkWithReadOnlyParent()
      throws IOException {
    xEmptyDirectory.setWritable(false);
    Path xChildOfReadonlyDir = xEmptyDirectory.getChild("x");
    if (testFS.supportsSymbolicLinksNatively(xChildOfReadonlyDir.asFragment())) {
      IOException e =
          assertThrows(IOException.class, () -> xChildOfReadonlyDir.createSymbolicLink(xNothing));
      assertThat(e).hasMessageThat().isEqualTo(xChildOfReadonlyDir + " (Permission denied)");
    }
  }

  //
  // createSymbolicLink
  //

  @Test
  public void testCanCreateDanglingLink() throws IOException {
    Path newPath = absolutize("non-existing-dir/new-file");
    Path someLink = absolutize("dangling-link");
    createSymbolicLink(someLink, newPath);
    assertThat(someLink.isSymbolicLink()).isTrue();
    assertThat(someLink.exists(Symlinks.NOFOLLOW)).isTrue(); // the link itself exists
    assertThat(someLink.exists()).isFalse(); // ...but the referent doesn't
    if (testFS.supportsSymbolicLinksNatively(someLink.asFragment())) {
      FileNotFoundException e =
          assertThrows(FileNotFoundException.class, someLink::resolveSymbolicLinks);
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(newPath.getParentDirectory() + " (No such file or directory)");
    }
  }

  @Test
  public void testCannotCreateSymbolicLinkWithoutParent() throws IOException {
    Path xChildOfMissingDir = xNothing.getChild("x");
    if (testFS.supportsSymbolicLinksNatively(xChildOfMissingDir.asFragment())) {
      FileNotFoundException e =
          assertThrows(
              FileNotFoundException.class, () -> xChildOfMissingDir.createSymbolicLink(xFile));
      assertThat(e).hasMessageThat().endsWith(" (No such file or directory)");
    }
  }

  @Test
  public void testCreateSymbolicLinkWhereNothingExists() throws IOException {
    createSymbolicLink(xNothing, xFile);
    assertThat(xNothing.isSymbolicLink()).isTrue();
  }

  @Test
  public void testCreateSymbolicLinkWhereDirectoryAlreadyExists() {
    IOException e =
        assertThrows(IOException.class, () -> createSymbolicLink(xEmptyDirectory, xFile));
    assertThat(e).hasMessageThat().isEqualTo(xEmptyDirectory + " (File exists)");
    assertThat(xEmptyDirectory.isDirectory(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void testCreateSymbolicLinkWhereFileAlreadyExists() {
    IOException e =
        assertThrows(IOException.class, () -> createSymbolicLink(xFile, xEmptyDirectory));
    assertThat(e).hasMessageThat().isEqualTo(xFile + " (File exists)");
    assertThat(xFile.isFile(Symlinks.NOFOLLOW)).isTrue();
  }

  @Test
  public void testCreateSymbolicLinkWhereDanglingSymlinkAlreadyExists() {
    IOException e = assertThrows(IOException.class, () -> createSymbolicLink(xDanglingLink, xFile));
    assertThat(e).hasMessageThat().isEqualTo(xDanglingLink + " (File exists)");
    assertThat(xDanglingLink.isSymbolicLink()).isTrue(); // still a symbolic link
    assertThat(xDanglingLink.isDirectory()).isFalse(); // link still dangles
  }

  @Test
  public void testCreateSymbolicLinkWhereSymlinkAlreadyExists() {
    IOException e =
        assertThrows(IOException.class, () -> createSymbolicLink(xLinkToDirectory, xNothing));
    assertThat(e).hasMessageThat().isEqualTo(xLinkToDirectory + " (File exists)");
    assertThat(xLinkToDirectory.isSymbolicLink()).isTrue(); // still a symbolic link
    assertThat(xLinkToDirectory.isDirectory()).isTrue(); // link still points to dir
  }

  @Test
  public void testDeleteLink() throws IOException {
    Path newPath = xEmptyDirectory.getChild("new-file");
    Path someLink = xEmptyDirectory.getChild("a-link");
    FileSystemUtils.createEmptyFile(newPath);
    createSymbolicLink(someLink, newPath);

    assertThat(xEmptyDirectory.getDirectoryEntries()).hasSize(2);

    assertThat(someLink.delete()).isTrue();
    assertThat(xEmptyDirectory.getDirectoryEntries()).hasSize(1);

    assertThat(xEmptyDirectory.getDirectoryEntries()).containsExactly(newPath);
  }

  // Testing the links
  @Test
  public void testLinkFollowedToDirectory() throws IOException {
    Path theDirectory = absolutize("foo/");
    assertThat(theDirectory.createDirectory()).isTrue();
    Path newPath1 = absolutize("foo/new-file-1");
    Path newPath2 = absolutize("foo/new-file-2");
    Path newPath3 = absolutize("foo/new-file-3");

    FileSystemUtils.createEmptyFile(newPath1);
    FileSystemUtils.createEmptyFile(newPath2);
    FileSystemUtils.createEmptyFile(newPath3);

    Path linkPath = absolutize("link");
    createSymbolicLink(linkPath, theDirectory);

    Path resultPath1 = absolutize("link/new-file-1");
    Path resultPath2 = absolutize("link/new-file-2");
    Path resultPath3 = absolutize("link/new-file-3");
    assertThat(linkPath.getDirectoryEntries()).containsExactly(resultPath1, resultPath2,
        resultPath3);
  }

  @Test
  public void testDanglingLinkIsNoFile() throws IOException {
    Path newPath1 = absolutize("new-file-1");
    Path newPath2 = absolutize("new-file-2");
    FileSystemUtils.createEmptyFile(newPath1);
    assertThat(newPath2.createDirectory()).isTrue();

    Path linkPath1 = absolutize("link1");
    Path linkPath2 = absolutize("link2");
    createSymbolicLink(linkPath1, newPath1);
    createSymbolicLink(linkPath2, newPath2);

    newPath1.delete();
    newPath2.delete();

    assertThat(linkPath1.isFile()).isFalse();
    assertThat(linkPath2.isDirectory()).isFalse();
  }

  @Test
  public void testWriteOnLinkChangesFile() throws IOException {
    Path testFile = absolutize("test-file");
    FileSystemUtils.createEmptyFile(testFile);
    String testData = "abc19";

    Path testLink = absolutize("a-link");
    createSymbolicLink(testLink, testFile);

    FileSystemUtils.writeContentAsLatin1(testLink, testData);
    String resultData =
      new String(FileSystemUtils.readContentAsLatin1(testFile));

    assertThat(resultData).isEqualTo(testData);
  }

  //
  // Symlink tests:
  //

  @Test
  public void testExistsWithSymlinks() throws IOException {
    Path a = absolutize("a");
    Path b = absolutize("b");
    FileSystemUtils.createEmptyFile(b);
    createSymbolicLink(a, b);  // ln -sf "b" "a"
    assertThat(a.exists()).isTrue(); // = exists(FOLLOW)
    assertThat(b.exists()).isTrue(); // = exists(FOLLOW)
    assertThat(a.exists(Symlinks.FOLLOW)).isTrue();
    assertThat(b.exists(Symlinks.FOLLOW)).isTrue();
    assertThat(a.exists(Symlinks.NOFOLLOW)).isTrue();
    assertThat(b.exists(Symlinks.NOFOLLOW)).isTrue();
    b.delete(); // "a" is now a dangling link
    assertThat(a.exists()).isFalse(); // = exists(FOLLOW)
    assertThat(b.exists()).isFalse(); // = exists(FOLLOW)
    assertThat(a.exists(Symlinks.FOLLOW)).isFalse();
    assertThat(b.exists(Symlinks.FOLLOW)).isFalse();

    assertThat(a.exists(Symlinks.NOFOLLOW)).isTrue(); // symlink still exists
    assertThat(b.exists(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void testIsDirectoryWithSymlinks() throws IOException {
    Path a = absolutize("a");
    Path b = absolutize("b");
    b.createDirectory();
    createSymbolicLink(a, b);  // ln -sf "b" "a"
    assertThat(a.isDirectory()).isTrue(); // = isDirectory(FOLLOW)
    assertThat(b.isDirectory()).isTrue(); // = isDirectory(FOLLOW)
    assertThat(a.isDirectory(Symlinks.FOLLOW)).isTrue();
    assertThat(b.isDirectory(Symlinks.FOLLOW)).isTrue();
    assertThat(a.isDirectory(Symlinks.NOFOLLOW)).isFalse(); // it's a link!
    assertThat(b.isDirectory(Symlinks.NOFOLLOW)).isTrue();
    b.delete(); // "a" is now a dangling link
    assertThat(a.isDirectory()).isFalse(); // = isDirectory(FOLLOW)
    assertThat(b.isDirectory()).isFalse(); // = isDirectory(FOLLOW)
    assertThat(a.isDirectory(Symlinks.FOLLOW)).isFalse();
    assertThat(b.isDirectory(Symlinks.FOLLOW)).isFalse();
    assertThat(a.isDirectory(Symlinks.NOFOLLOW)).isFalse();
    assertThat(b.isDirectory(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void testIsFileWithSymlinks() throws IOException {
    Path a = absolutize("a");
    Path b = absolutize("b");
    FileSystemUtils.createEmptyFile(b);
    createSymbolicLink(a, b);  // ln -sf "b" "a"
    assertThat(a.isFile()).isTrue(); // = isFile(FOLLOW)
    assertThat(b.isFile()).isTrue(); // = isFile(FOLLOW)
    assertThat(a.isFile(Symlinks.FOLLOW)).isTrue();
    assertThat(b.isFile(Symlinks.FOLLOW)).isTrue();
    assertThat(a.isFile(Symlinks.NOFOLLOW)).isFalse(); // it's a link!
    assertThat(b.isFile(Symlinks.NOFOLLOW)).isTrue();
    b.delete(); // "a" is now a dangling link
    assertThat(a.isFile()).isFalse(); // = isFile()
    assertThat(b.isFile()).isFalse(); // = isFile()
    assertThat(a.isFile()).isFalse();
    assertThat(b.isFile()).isFalse();
    assertThat(a.isFile(Symlinks.NOFOLLOW)).isFalse();
    assertThat(b.isFile(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void testGetDirectoryEntriesOnLinkToDirectory() throws Exception {
    Path fooAlias = xNothing.getChild("foo");
    createSymbolicLink(xNothing, xNonEmptyDirectory);
    Collection<Path> dirents = xNothing.getDirectoryEntries();
    assertThat(dirents).containsExactly(fooAlias);
  }

  @Test
  public void testFilesOfLinkedDirectories() throws Exception {
    Path child = xEmptyDirectory.getChild("child");
    Path aliasToChild = xLinkToDirectory.getChild("child");

    assertThat(aliasToChild.exists()).isFalse();
    FileSystemUtils.createEmptyFile(child);
    assertThat(aliasToChild.exists()).isTrue();
    assertThat(aliasToChild.isFile()).isTrue();
    assertThat(aliasToChild.isDirectory()).isFalse();

    validateLinkedReferenceObeysReadOnly(child, aliasToChild);
    validateLinkedReferenceObeysExecutable(child, aliasToChild);
  }

  @Test
  public void testDirectoriesOfLinkedDirectories() throws Exception {
    Path childDir = xEmptyDirectory.getChild("childDir");
    Path linkToChildDir = xLinkToDirectory.getChild("childDir");

    assertThat(linkToChildDir.exists()).isFalse();
    childDir.createDirectory();
    assertThat(linkToChildDir.exists()).isTrue();
    assertThat(linkToChildDir.isDirectory()).isTrue();
    assertThat(linkToChildDir.isFile()).isFalse();

    validateLinkedReferenceObeysReadOnly(childDir, linkToChildDir);
    validateLinkedReferenceObeysExecutable(childDir, linkToChildDir);
  }

  @Test
  public void testDirectoriesOfLinkedDirectoriesOfLinkedDirectories() throws Exception {
    Path childDir = xEmptyDirectory.getChild("childDir");
    Path linkToLinkToDirectory = absolutize("xLinkToLinkToDirectory");
    createSymbolicLink(linkToLinkToDirectory, xLinkToDirectory);
    Path linkToChildDir = linkToLinkToDirectory.getChild("childDir");

    assertThat(linkToChildDir.exists()).isFalse();
    childDir.createDirectory();
    assertThat(linkToChildDir.exists()).isTrue();
    assertThat(linkToChildDir.isDirectory()).isTrue();
    assertThat(linkToChildDir.isFile()).isFalse();

    validateLinkedReferenceObeysReadOnly(childDir, linkToChildDir);
    validateLinkedReferenceObeysExecutable(childDir, linkToChildDir);
  }

  private void validateLinkedReferenceObeysReadOnly(Path path, Path link) throws IOException {
    path.setWritable(false);
    assertThat(path.isWritable()).isFalse();
    assertThat(link.isWritable()).isFalse();
    path.setWritable(true);
    assertThat(path.isWritable()).isTrue();
    assertThat(link.isWritable()).isTrue();
    path.setWritable(false);
    assertThat(path.isWritable()).isFalse();
    assertThat(link.isWritable()).isFalse();
  }

  private void validateLinkedReferenceObeysExecutable(Path path, Path link) throws IOException {
    path.setExecutable(true);
    assertThat(path.isExecutable()).isTrue();
    assertThat(link.isExecutable()).isTrue();
    path.setExecutable(false);
    assertThat(path.isExecutable()).isFalse();
    assertThat(link.isExecutable()).isFalse();
    path.setExecutable(true);
    assertThat(path.isExecutable()).isTrue();
    assertThat(link.isExecutable()).isTrue();
  }

  @Test
  public void testReadingFileFromLinkedDirectory() throws Exception {
    Path linkedTo = absolutize("linkedTo");
    linkedTo.createDirectory();
    Path child = linkedTo.getChild("child");
    FileSystemUtils.createEmptyFile(child);

    byte[] outputData = "This is a test".getBytes();
    FileSystemUtils.writeContent(child, outputData);

    Path link = absolutize("link");
    createSymbolicLink(link, linkedTo);
    Path linkedChild = link.getChild("child");
    byte[] inputData = FileSystemUtils.readContent(linkedChild);
    assertThat(inputData).isEqualTo(outputData);
  }

  @Test
  public void testCreatingFileInLinkedDirectory() throws Exception {
    Path linkedTo = absolutize("linkedTo");
    linkedTo.createDirectory();
    Path child = linkedTo.getChild("child");

    Path link = absolutize("link");
    createSymbolicLink(link, linkedTo);
    Path linkedChild = link.getChild("child");
    byte[] outputData = "This is a test".getBytes();
    FileSystemUtils.writeContent(linkedChild, outputData);

    byte[] inputData = FileSystemUtils.readContent(child);
    assertThat(inputData).isEqualTo(outputData);
  }

  @Test
  public void testUtf8Symlink() throws Exception {
    assumeUtf8CompatibleEncoding();

    String target = StringEncoding.unicodeToInternal("入力_A_🌱.target");
    Path link = absolutize(StringEncoding.unicodeToInternal("入力_A_🌱.txt"));
    createSymbolicLink(link, PathFragment.create(target));
    assertThat(link.readSymbolicLink().toString()).isEqualTo(target);

    java.nio.file.Path javaPath = getJavaPathOrSkipIfUnsupported(link);
    assertThat(platformToUnicode(Files.readSymbolicLink(javaPath).toString()))
        .isEqualTo("入力_A_🌱.target");
  }
}
