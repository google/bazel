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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.unix.NativePosixFiles.StatErrorHandling;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the {@link NativePosixFiles} class. */
@RunWith(JUnit4.class)
public class NativePosixFilesTest {

  private Path workingDir;

  @Before
  public final void createFileSystem() throws Exception  {
    FileSystem testFS = new UnixFileSystem(DigestHashFunction.SHA256, /*hashAttributeName=*/ "");
    workingDir = testFS.getPath(new File(TestUtils.tmpDir()).getCanonicalPath());
  }

  // TODO(tjgq): Move this into FileSystemTest, and add more comprehensive coverage for chmod.
  @Test
  public void chmod_throwsFilePermissionException() throws Exception {
    File foo = new File("/bin");
    try {
      int perms =
          NativePosixFiles.lstat(foo.getPath(), StatErrorHandling.ALWAYS_THROW).getPermissions();
      NativePosixFiles.chmod(foo.getPath(), perms | UnixFileStatus.S_IWUSR);
      fail("Expected FilePermissionException or IOException, but wasn't thrown.");
    } catch (FilePermissionException e) {
      assertThat(e).hasMessageThat().isEqualTo(foo + " (Operation not permitted)");
    } catch (IOException e) {
      // When running in a sandbox, /bin might actually be a read-only file system.
      assertThat(e).hasMessageThat().isEqualTo(foo + " (Read-only file system)");
    }
  }

  /** Skips the test if the file system does not support extended attributes. */
  private static void assumeXattrsSupported() throws Exception {
    // The standard file systems on macOS support extended attributes by default, so we can assume
    // that the test will work on that platform. For other systems, we currently don't have a
    // mechanism to validate this so the tests are skipped unconditionally.
    assumeTrue(OS.getCurrent() == OS.DARWIN);
  }

  @Test
  public void testGetxattr_attributeFound() throws Exception {
    assumeXattrsSupported();

    String myfile = Files.createTempFile("getxattrtest", null).toString();
    assertThat(new ProcessBuilder("xattr", "-w", "foo", "bar", myfile).start().waitFor())
        .isEqualTo(0);

    assertThat(new String(NativePosixFiles.getxattr(myfile, "foo"), UTF_8)).isEqualTo("bar");
    assertThat(new String(NativePosixFiles.lgetxattr(myfile, "foo"), UTF_8)).isEqualTo("bar");
  }

  @Test
  public void testGetxattr_attributeNotFoundReturnsNull() throws Exception {
    assumeXattrsSupported();

    String myfile = Files.createTempFile("getxattrtest", null).toString();

    assertThat(NativePosixFiles.getxattr(myfile, "foo")).isNull();
    assertThat(NativePosixFiles.lgetxattr(myfile, "foo")).isNull();
  }

  @Test
  public void testGetxattr_fileNotFound() throws Exception {
    String nonexistentFile = workingDir.getChild("nonexistent").toString();

    assertThrows(
        FileNotFoundException.class, () -> NativePosixFiles.getxattr(nonexistentFile, "foo"));
    assertThrows(
        FileNotFoundException.class, () -> NativePosixFiles.lgetxattr(nonexistentFile, "foo"));
  }

  @Test
  public void writing() throws Exception {
    java.nio.file.Path myfile = Files.createTempFile("myfile", null);
    int fd1 = NativePosixFiles.openWrite(myfile.toString(), false);
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> NativePosixFiles.write(fd1, new byte[] {0, 1, 2, 3}, 5, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> NativePosixFiles.write(fd1, new byte[] {0, 1, 2, 3}, -1, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> NativePosixFiles.write(fd1, new byte[] {0, 1, 2, 3}, 0, -1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> NativePosixFiles.write(fd1, new byte[] {0, 1, 2, 3}, 0, 5));
    NativePosixFiles.write(fd1, new byte[] {0, 1, 2, 3}, 0, 4);
    NativePosixFiles.close(fd1, null);
    assertThat(Files.readAllBytes(myfile)).isEqualTo(new byte[] {0, 1, 2, 3});
    // Try appending.
    int fd2 = NativePosixFiles.openWrite(myfile.toString(), true);
    NativePosixFiles.write(fd2, new byte[] {5, 6, 7, 8, 9}, 1, 3);
    NativePosixFiles.close(fd2, null);
    assertThat(Files.readAllBytes(myfile)).isEqualTo(new byte[] {0, 1, 2, 3, 6, 7, 8});
  }
}
