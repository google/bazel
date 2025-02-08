// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.FileArtifactValue.createForTesting;
import static org.junit.Assert.assertThrows;

import com.google.common.io.BaseEncoding;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FileArtifactValue}. */
@RunWith(JUnit4.class)
public final class FileArtifactValueTest {
  private final ManualClock clock = new ManualClock();
  private final FileSystem fs = new InMemoryFileSystem(clock, DigestHashFunction.SHA256);

  private Path scratchFile(String name, long mtime, String content) throws IOException {
    Path path = fs.getPath(name);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(path, content);
    path.setLastModifiedTime(mtime);
    return path;
  }

  private Path scratchDir(String name, long mtime) throws IOException {
    Path path = fs.getPath(name);
    path.createDirectoryAndParents();
    path.setLastModifiedTime(mtime);
    return path;
  }

  private Path scratchSymlink(String name, String targetPath) throws IOException {
    Path path = fs.getPath(name);
    path.getParentDirectory().createDirectoryAndParents();
    path.createSymbolicLink(PathFragment.create(targetPath));
    return path;
  }

  private static byte[] toBytes(String hex) {
    return BaseEncoding.base16().upperCase().decode(hex);
  }

  @Test
  public void testEqualsAndHashCode() {
    // Each "equality group" is checked for equality within itself (including hashCode equality)
    // and inequality with members of other equality groups.
    new EqualsTester()
        .addEqualityGroup(
            FileArtifactValue.createForNormalFile(
                toBytes("00112233445566778899AABBCCDDEEFF"), /* proxy= */ null, 1L),
            FileArtifactValue.createForNormalFile(
                toBytes("00112233445566778899AABBCCDDEEFF"), /* proxy= */ null, 1L))
        .addEqualityGroup(
            FileArtifactValue.createForNormalFile(
                toBytes("00112233445566778899AABBCCDDEEFF"), /* proxy= */ null, 2L))
        .addEqualityGroup(FileArtifactValue.createForDirectoryWithMtime(1))
        .addEqualityGroup(
            FileArtifactValue.createForNormalFile(
                toBytes("FFFFFF00000000000000000000000000"), /* proxy= */ null, 1L))
        .addEqualityGroup(
            FileArtifactValue.createForDirectoryWithMtime(2),
            FileArtifactValue.createForDirectoryWithMtime(2))
        .addEqualityGroup(
            // expireAtEpochMilli doesn't contribute to the equality
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                toBytes("00112233445566778899AABBCCDDEEFF"),
                /* size= */ 1,
                /* locationIndex= */ 1,
                /* expirationTime= */ Instant.ofEpochMilli(1)),
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                toBytes("00112233445566778899AABBCCDDEEFF"),
                /* size= */ 1,
                /* locationIndex= */ 1,
                /* expirationTime= */ Instant.ofEpochMilli(2)))
        .addEqualityGroup(
            // A ResolvedSymlinkArtifactValue is not equal to the FileArtifactValue it wraps.
            FileArtifactValue.createFromExistingWithResolvedPath(
                FileArtifactValue.createForNormalFile(
                    toBytes("00112233445566778899AABBCCDDEEFF"), /* proxy= */ null, 1L),
                PathFragment.create("/some/path")))
        .addEqualityGroup(FileArtifactValue.MISSING_FILE_MARKER)
        .addEqualityGroup(FileArtifactValue.RUNFILES_TREE_MARKER)
        .addEqualityGroup("a string")
        .testEquals();
  }

  @Test
  public void testEquality() throws Exception {
    Path path1 = scratchFile("/dir/artifact1", 0L, "content");
    Path path2 = scratchFile("/dir/artifact2", 0L, "content");
    Path digestPath = scratchFile("/dir/diffDigest", 0L, "1234567");
    Path mtimePath = scratchFile("/dir/diffMtime", 1L, "content");

    Path empty1 = scratchFile("/dir/empty1", 0L, "");
    Path empty2 = scratchFile("/dir/empty2", 1L, "");
    Path empty3 = scratchFile("/dir/empty3", 1L, "");

    Path dir1 = scratchDir("/dir1", 0L);
    Path dir2 = scratchDir("/dir2", 1L);
    Path dir3 = scratchDir("/dir3", 1L);

    new EqualsTester()
        // We check for ctime and inode equality for paths.
        .addEqualityGroup(createForTesting(path1))
        .addEqualityGroup(createForTesting(path2))
        .addEqualityGroup(createForTesting(mtimePath))
        .addEqualityGroup(createForTesting(digestPath))
        .addEqualityGroup(createForTesting(empty1))
        .addEqualityGroup(createForTesting(empty2))
        .addEqualityGroup(createForTesting(empty3))
        // We check for mtime equality for directories.
        .addEqualityGroup(createForTesting(dir1))
        .addEqualityGroup(createForTesting(dir2), createForTesting(dir3))
        // A ResolvedSymlinkArtifactValue is not equal to the FileArtifactValue it wraps.
        .addEqualityGroup(
            FileArtifactValue.createFromExistingWithResolvedPath(
                createForTesting(path1), PathFragment.create("/some/path")))
        .testEquals();
  }

  @Test
  public void testCtimeInEquality() throws Exception {
    Path path = scratchFile("/dir/artifact1", 0L, "content");
    FileArtifactValue before = createForTesting(path);
    clock.advanceMillis(1);
    path.chmod(0777);
    FileArtifactValue after = createForTesting(path);
    assertThat(before).isNotEqualTo(after);
  }

  @Test
  public void testNoMtimeIfNonemptyFile() throws Exception {
    Path path = scratchFile("/root/non-empty", 1L, "abc");
    FileArtifactValue value = createForTesting(path);
    assertThat(value.getDigest()).isEqualTo(path.getDigest());
    assertThat(value.getSize()).isEqualTo(3L);
    assertThrows(
        "mtime for non-empty file should not be stored.",
        UnsupportedOperationException.class,
        value::getModifiedTime);
  }

  @Test
  public void testDirectory() throws Exception {
    Path path = scratchDir("/dir", /* mtime= */ 1L);
    FileArtifactValue value = createForTesting(path);
    assertThat(value.getDigest()).isNull();
    assertThat(value.getModifiedTime()).isEqualTo(1L);
  }

  @Test
  public void testUnresolvedSymlink() throws Exception {
    Path path = scratchSymlink("/sym", "/some/path");
    FileArtifactValue value = FileArtifactValue.createForUnresolvedSymlink(path);
    FileArtifactValue value2 = FileArtifactValue.createForUnresolvedSymlink(path);
    assertThat(value.getType()).isEqualTo(FileStateType.SYMLINK);
    assertThat(value.getUnresolvedSymlinkTarget()).isEqualTo("/some/path");
    new EqualsTester().addEqualityGroup(value, value2).testEquals();
  }

  @Test
  public void testResolvedSymlinkToFile() throws Exception {
    Path path = scratchFile("/file", /* mtime= */ 1L, "content");
    FileArtifactValue delegate = FileArtifactValue.createForTesting(path);
    FileArtifactValue value =
        FileArtifactValue.createFromExistingWithResolvedPath(
            delegate, PathFragment.create("/file"));
    assertThat(value.getType()).isEqualTo(FileStateType.REGULAR_FILE);
    assertThat(value.getResolvedPath()).isEqualTo(PathFragment.create("/file"));
    assertThat(value.getDigest()).isEqualTo(delegate.getDigest());
    assertThat(value.getSize()).isEqualTo(delegate.getSize());
  }

  @Test
  public void testResolvedSymlinkToDirectory() throws Exception {
    Path path = scratchDir("/dir", /* mtime= */ 1L);
    FileArtifactValue delegate = FileArtifactValue.createForTesting(path);
    FileArtifactValue value =
        FileArtifactValue.createFromExistingWithResolvedPath(
            delegate, PathFragment.create("/file"));
    assertThat(value.getType()).isEqualTo(FileStateType.DIRECTORY);
    assertThat(value.getResolvedPath()).isEqualTo(PathFragment.create("/file"));
    assertThat(value.getModifiedTime()).isEqualTo(delegate.getModifiedTime());
  }

  // Empty files are the same as normal files -- mtime is not stored.
  @Test
  public void testEmptyFile() throws Exception {
    Path path = scratchFile("/root/empty", 1L, "");
    path.setLastModifiedTime(1L);
    FileArtifactValue value = createForTesting(path);
    assertThat(value.getDigest()).isEqualTo(path.getDigest());
    assertThat(value.getSize()).isEqualTo(0L);
    assertThrows(
        "mtime for non-empty file should not be stored.",
        UnsupportedOperationException.class,
        value::getModifiedTime);
  }

  @Test
  public void testIOException() throws Exception {
    IOException exception = new IOException("beep");
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public byte[] getDigest(PathFragment path) throws IOException {
            throw exception;
          }

          @Override
          @SuppressWarnings("UnsynchronizedOverridesSynchronized")
          protected byte[] getFastDigest(PathFragment path) throws IOException {
            throw exception;
          }
        };
    Path path = fs.getPath("/some/path");
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(path, "content");
    IOException e = assertThrows(IOException.class, () -> createForTesting(path));
    assertThat(e).isSameInstanceAs(exception);
  }

  @Test
  public void testUptodateCheck() throws Exception {
    Path path = scratchFile("/dir/artifact1", 0L, "content");
    FileArtifactValue value = createForTesting(path);
    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
    clock.advanceMillis(1);
    path.setLastModifiedTime(123); // Changing mtime implicitly updates ctime.
    assertThat(value.wasModifiedSinceDigest(path)).isTrue();
    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isTrue();
  }

  @Test
  public void testUptodateCheckDeleteFile() throws Exception {
    Path path = scratchFile("/dir/artifact1", 0L, "content");
    FileArtifactValue value = createForTesting(path);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
    path.delete();
    assertThat(value.wasModifiedSinceDigest(path)).isTrue();
  }

  @Test
  public void testUptodateCheckDirectory() throws Exception {
    // For now, we don't attempt to detect changes to directories.
    Path path = scratchDir("/dir", 0L);
    FileArtifactValue value = createForTesting(path);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
    path.delete();
    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
  }

  @Test
  public void testUptodateChangeFileToDirectory() throws Exception {
    // For now, we don't attempt to detect changes to directories.
    Path path = scratchFile("/dir/file", 0L, "");
    FileArtifactValue value = createForTesting(path);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();
    // If we only check ctime, then we need to change the clock here, or we get a ctime match on the
    // stat.
    path.delete();
    path.createDirectoryAndParents();
    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isTrue();
  }

  @Test
  public void testUptodateUnresolvedSymlink() throws Exception {
    Path path = fs.getPath("/dir/symlink");
    path.getParentDirectory().createDirectoryAndParents();
    path.createSymbolicLink(PathFragment.create("target_path"));
    FileArtifactValue value = FileArtifactValue.createForUnresolvedSymlink(path);

    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isFalse();

    path.delete();
    path.createSymbolicLink(PathFragment.create("modified_target_path"));

    clock.advanceMillis(1);
    assertThat(value.wasModifiedSinceDigest(path)).isTrue();
  }

  @Test
  public void addToFingerprint_equalByDigest() throws Exception {
    FileArtifactValue value1 =
        FileArtifactValue.createForTesting(scratchFile("/dir/file1", /* mtime= */ 1, "content"));
    FileArtifactValue value2 =
        FileArtifactValue.createForTesting(scratchFile("/dir/file2", /* mtime= */ 2, "content"));
    Fingerprint fingerprint1 = new Fingerprint();
    Fingerprint fingerprint2 = new Fingerprint();

    value1.addTo(fingerprint1);
    value2.addTo(fingerprint2);

    assertThat(value1.getDigest()).isNotNull();
    assertThat(value2.getDigest()).isNotNull();
    assertThat(fingerprint1.digestAndReset()).isEqualTo(fingerprint2.digestAndReset());
  }

  @Test
  public void addToFingerprint_notEqualByDigest() throws Exception {
    FileArtifactValue value1 =
        FileArtifactValue.createForTesting(scratchFile("/dir/file1", /* mtime= */ 1, "content1"));
    FileArtifactValue value2 =
        FileArtifactValue.createForTesting(scratchFile("/dir/file2", /* mtime= */ 1, "content2"));
    Fingerprint fingerprint1 = new Fingerprint();
    Fingerprint fingerprint2 = new Fingerprint();

    value1.addTo(fingerprint1);
    value2.addTo(fingerprint2);

    assertThat(value1.getDigest()).isNotNull();
    assertThat(value2.getDigest()).isNotNull();
    assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset());
  }

  @Test
  public void addToFingerprint_equalByMtime() throws Exception {
    FileArtifactValue value1 =
        FileArtifactValue.createForTesting(scratchDir("/dir1", /* mtime= */ 1));
    FileArtifactValue value2 =
        FileArtifactValue.createForTesting(scratchDir("/dir2", /* mtime= */ 1));
    Fingerprint fingerprint1 = new Fingerprint();
    Fingerprint fingerprint2 = new Fingerprint();

    value1.addTo(fingerprint1);
    value2.addTo(fingerprint2);

    assertThat(value1.getDigest()).isNull();
    assertThat(value2.getDigest()).isNull();
    assertThat(fingerprint1.digestAndReset()).isEqualTo(fingerprint2.digestAndReset());
  }

  @Test
  public void addToFingerprint_notEqualByMtime() throws Exception {
    FileArtifactValue value1 =
        FileArtifactValue.createForTesting(scratchDir("/dir1", /* mtime= */ 1));
    FileArtifactValue value2 =
        FileArtifactValue.createForTesting(scratchDir("/dir2", /* mtime= */ 2));
    Fingerprint fingerprint1 = new Fingerprint();
    Fingerprint fingerprint2 = new Fingerprint();

    value1.addTo(fingerprint1);
    value2.addTo(fingerprint2);

    assertThat(value1.getDigest()).isNull();
    assertThat(value2.getDigest()).isNull();
    assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset());
  }

  @Test
  public void addToFingerprint_fileWithDigestNotEqualToFileWithOnlyMtime() throws Exception {
    FileArtifactValue value1 =
        FileArtifactValue.createForTesting(scratchDir("/dir", /* mtime= */ 1));
    FileArtifactValue value2 =
        FileArtifactValue.createForTesting(scratchFile("/dir/file", /* mtime= */ 1, "contents"));
    Fingerprint fingerprint1 = new Fingerprint();
    Fingerprint fingerprint2 = new Fingerprint();

    value1.addTo(fingerprint1);
    value2.addTo(fingerprint2);

    assertThat(value1.getDigest()).isNull();
    assertThat(value2.getDigest()).isNotNull();
    assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset());
  }
}
