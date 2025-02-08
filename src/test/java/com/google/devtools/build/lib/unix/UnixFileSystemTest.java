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
package com.google.devtools.build.lib.unix;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SymlinkAwareFileSystemTest;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import org.junit.Test;

/** Tests for the {@link com.google.devtools.build.lib.unix.UnixFileSystem} class. */
public class UnixFileSystemTest extends SymlinkAwareFileSystemTest {

  @Override
  protected FileSystem getFreshFileSystem(DigestHashFunction digestHashFunction) {
    return new UnixFileSystem(digestHashFunction, /*hashAttributeName=*/ "");
  }

  @Override
  public void destroyFileSystem(FileSystem fileSystem) {
    // Nothing.
  }

  // Most tests are just inherited from FileSystemTest.

  @Test
  public void testCircularSymlinkFound() throws Exception {
    Path linkA = absolutize("link-a");
    Path linkB = absolutize("link-b");
    linkA.createSymbolicLink(linkB);
    linkB.createSymbolicLink(linkA);
    assertThat(linkA.exists(Symlinks.FOLLOW)).isFalse();
    assertThrows(IOException.class, () -> linkA.statIfFound(Symlinks.FOLLOW));
  }

  @Test
  public void testIsSpecialFile() throws Exception {
    Path regular = absolutize("regular");
    Path fifo = absolutize("fifo");
    FileSystemUtils.createEmptyFile(regular);
    NativePosixFiles.mkfifo(fifo.toString(), 0777);

    assertThat(regular.isFile()).isTrue();
    assertThat(regular.isSpecialFile()).isFalse();
    assertThat(regular.stat().isFile()).isTrue();
    assertThat(regular.stat().isSpecialFile()).isFalse();
    assertThat(fifo.isFile()).isTrue();
    assertThat(fifo.isSpecialFile()).isTrue();
    assertThat(fifo.stat().isFile()).isTrue();
    assertThat(fifo.stat().isSpecialFile()).isTrue();
  }

  @Test
  public void testReaddirSpecialFile() throws Exception {
    Path dir = absolutize("readdir");
    Path symlink = dir.getChild("symlink");
    Path fifo = dir.getChild("fifo");
    dir.createDirectoryAndParents();
    symlink.createSymbolicLink(fifo.asFragment());
    NativePosixFiles.mkfifo(fifo.toString(), 0777);

    assertThat(dir.getDirectoryEntries()).containsExactly(symlink, fifo);

    assertThat(dir.readdir(Symlinks.NOFOLLOW))
        .containsExactly(
            new Dirent("symlink", Dirent.Type.SYMLINK), new Dirent("fifo", Dirent.Type.UNKNOWN));

    assertThat(dir.readdir(Symlinks.FOLLOW))
        .containsExactly(
            new Dirent("symlink", Dirent.Type.UNKNOWN), new Dirent("fifo", Dirent.Type.UNKNOWN));
  }
}
