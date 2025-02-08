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
package com.google.devtools.build.lib.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.server.InstallBaseGarbageCollector.DELETED_SUFFIX;
import static com.google.devtools.build.lib.server.InstallBaseGarbageCollector.LOCK_SUFFIX;

import com.google.devtools.build.lib.testutil.ExternalFileSystemLock;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link InstallBaseGarbageCollector}. */
@RunWith(JUnit4.class)
public final class InstallBaseGarbageCollectorTest {
  private static final String OWN_MD5 = "012345678901234567890123456789012";
  private static final String OTHER_MD5 = "abcdefabcdefabcdefabcdefabcdefab";

  private Path rootDir;
  private Path ownInstallBase;

  @Before
  public void setUp() throws Exception {
    rootDir = TestUtils.createUniqueTmpDir(null);
    ownInstallBase = createSubdirectory(OWN_MD5);
  }

  @Test
  public void onlyOwnInstallBase_notCollected() throws Exception {
    run(Duration.ZERO);

    assertDirectoryContents(OWN_MD5);
  }

  @Test
  public void otherInstallBase_notStaleAndUnlocked_notCollected() throws Exception {
    Path otherInstallBase = createSubdirectory(OTHER_MD5);
    setAge(otherInstallBase, Duration.ofDays(1));

    run(Duration.ofDays(2));

    assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX);
  }

  @Test
  public void otherInstallBase_notStaleAndLocked_notCollected() throws Exception {
    Path otherInstallBase = createSubdirectory(OTHER_MD5);
    setAge(otherInstallBase, Duration.ofDays(1));

    try (var lock = ExternalFileSystemLock.getShared(rootDir.getChild(OTHER_MD5 + LOCK_SUFFIX))) {
      run(Duration.ofDays(2));
    }

    assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX);
  }

  @Test
  public void otherInstallBase_staleAndUnlocked_collected() throws Exception {
    Path otherInstallBase = createSubdirectory(OTHER_MD5);
    setAge(otherInstallBase, Duration.ofDays(3));

    run(Duration.ofDays(2));

    assertDirectoryContents(OWN_MD5);
  }

  @Test
  public void otherInstallBase_staleAndLocked_notCollected() throws Exception {
    Path otherInstallBase = createSubdirectory(OTHER_MD5);
    setAge(otherInstallBase, Duration.ofDays(3));

    try (var lock = ExternalFileSystemLock.getShared(rootDir.getChild(OTHER_MD5 + LOCK_SUFFIX))) {
      run(Duration.ofDays(2));
    }

    assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX);
  }

  @Test
  public void incompleteDeletion_collected() throws Exception {
    Path incompleteDeletion = createSubdirectory(OTHER_MD5 + DELETED_SUFFIX);
    setAge(incompleteDeletion, Duration.ofDays(2));

    run(Duration.ofDays(1));

    assertDirectoryContents(OWN_MD5);
  }

  @Test
  public void otherFilesAndDirectories_notCollected() throws Exception {
    Path otherFile = rootDir.getChild("file");
    FileSystemUtils.writeContentAsLatin1(otherFile, "content");
    setAge(otherFile, Duration.ofDays(2));
    Path otherDir = rootDir.getChild("dir");
    otherDir.createDirectoryAndParents();
    setAge(otherDir, Duration.ofDays(2));
    Path otherSymlink = rootDir.getChild("symlink");
    otherSymlink.createSymbolicLink(PathFragment.create(OWN_MD5));

    run(Duration.ofDays(1));

    assertDirectoryContents(OWN_MD5, "file", "dir", "symlink");
  }

  private Path createSubdirectory(String name) throws IOException {
    Path dir = rootDir.getChild(name);
    Path file = dir.getChild("file");
    Path subdir = dir.getChild("subdir");
    Path subfile = subdir.getChild("file");
    dir.createDirectoryAndParents();
    subdir.createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(file, "content");
    FileSystemUtils.writeContentAsLatin1(subfile, "content");

    return dir;
  }

  private void setAge(Path path, Duration age) throws IOException {
    path.setLastModifiedTime(Instant.now().minus(age).toEpochMilli());
  }

  private void run(Duration maxAge) throws Exception {
    new InstallBaseGarbageCollector(rootDir, ownInstallBase, maxAge).run();
  }

  private void assertDirectoryContents(Object... expected) throws Exception {
    assertThat(rootDir.getDirectoryEntries().stream().map(Path::getBaseName))
        .containsExactly(expected);
  }
}
