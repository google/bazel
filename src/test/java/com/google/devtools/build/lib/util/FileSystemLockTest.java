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
package com.google.devtools.build.lib.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.testutil.ExternalFileSystemLock;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.FileSystemLock.LockAlreadyHeldException;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FileSystemLock}. */
@RunWith(JUnit4.class)
public final class FileSystemLockTest {

  private Path lockPath;

  @Before
  public void setUp() throws Exception {
    var rootDir = TestUtils.createUniqueTmpDir(null);
    lockPath = rootDir.getRelative("subdir/lock");
  }

  @Test
  public void getShared_whenNotLocked_succeeds() throws Exception {
    try (var lock = FileSystemLock.getShared(lockPath)) {
      assertThat(lock.isShared()).isTrue();
    }
  }

  @Test
  public void getShared_whenLockedForSharedUse_succeeds() throws Exception {
    try (var externalLock = ExternalFileSystemLock.getShared(lockPath);
        var lock = FileSystemLock.getShared(lockPath)) {
      assertThat(lock.isShared()).isTrue();
    }
  }

  @Test
  public void getShared_whenLockedForExclusiveUse_fails() throws Exception {
    try (var externalLock = ExternalFileSystemLock.getExclusive(lockPath)) {
      LockAlreadyHeldException e =
          assertThrows(LockAlreadyHeldException.class, () -> FileSystemLock.getShared(lockPath));
      assertThat(e).hasMessageThat().contains("failed to acquire shared filesystem lock");
    }
  }

  @Test
  public void getExclusive_whenNotLocked_succeeds() throws Exception {
    try (var lock = FileSystemLock.getExclusive(lockPath)) {
      assertThat(lock.isExclusive()).isTrue();
    }
  }

  @Test
  public void getExclusive_whenLockedForSharedUse_fails() throws Exception {
    try (var externalLock = ExternalFileSystemLock.getShared(lockPath)) {
      LockAlreadyHeldException e =
          assertThrows(LockAlreadyHeldException.class, () -> FileSystemLock.getExclusive(lockPath));
      assertThat(e).hasMessageThat().contains("failed to acquire exclusive filesystem lock");
    }
  }

  @Test
  public void getExclusive_whenLockedForExclusiveUse_fails() throws Exception {
    try (var lock = ExternalFileSystemLock.getExclusive(lockPath)) {
      LockAlreadyHeldException e =
          assertThrows(LockAlreadyHeldException.class, () -> FileSystemLock.getExclusive(lockPath));
      assertThat(e).hasMessageThat().contains("failed to acquire exclusive filesystem lock");
    }
  }
}
