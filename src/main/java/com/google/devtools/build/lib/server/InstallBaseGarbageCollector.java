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

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.util.FileSystemLock;
import com.google.devtools.build.lib.util.FileSystemLock.LockAlreadyHeldException;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A garbage collector for stale install bases.
 *
 * <p>Garbage collection operates on other install bases found in the parent directory of our own
 * install base. The mtime of each install base directory, which is updated by the client on every
 * invocation, determines whether it's eligible for garbage collection. In addition, both clients
 * and servers place a lock on their respective install base to prevent it from being collected
 * while in use.
 */
public final class InstallBaseGarbageCollector {
  @VisibleForTesting static final String LOCK_SUFFIX = ".lock";
  @VisibleForTesting static final String DELETED_SUFFIX = ".deleted";

  private final Path root;
  private final Path ownInstallBase;
  private final Duration maxAge;

  /**
   * Creates a new garbage collector.
   *
   * @param root the install user root, i.e., the parent directory of install bases
   * @param ownInstallBase the current server's install base
   * @param maxAge how long an install base must remain unused before it's eligible for collection
   */
  InstallBaseGarbageCollector(Path root, Path ownInstallBase, Duration maxAge) {
    this.root = root;
    this.ownInstallBase = ownInstallBase;
    this.maxAge = maxAge;
  }

  @VisibleForTesting
  public Path getRoot() {
    return root;
  }

  @VisibleForTesting
  public Path getOwnInstallBase() {
    return ownInstallBase;
  }

  @VisibleForTesting
  public Duration getMaxAge() {
    return maxAge;
  }

  void run() throws IOException, InterruptedException {
    for (Dirent dirent : root.readdir(Symlinks.FOLLOW)) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      if (!dirent.getType().equals(Dirent.Type.DIRECTORY)) {
        // Ignore non-directories.
        continue;
      }
      Path child = root.getChild(dirent.getName());
      if (isInstallBase(child)) {
        if (child.equals(ownInstallBase)) {
          // Don't attempt to collect our own install base.
          continue;
        }
        collectWhenStale(child);
      } else if (isIncompleteDeletion(child)) {
        // This install base is either being deleted, or an earlier attempt to delete it was
        // interrupted. Assume the latter and try again, otherwise it will never be deleted.
        // Concurrent attempts are fine because deleteTree treats not found as successful deletion.
        child.deleteTree();
      }
    }
  }

  private void collectWhenStale(Path installBase) throws IOException {
    Path pathToDelete = null;
    Path lockPath = getLockPath(installBase);
    try (FileSystemLock lock = FileSystemLock.getExclusive(lockPath)) {
      FileStatus status = installBase.statIfFound();
      if (status == null) {
        // The install base is already gone. Back off.
        // This cannot be a garbage collection by another Bazel server, as it would have taken an
        // exclusive lock, but maybe the user or something else in the system did a cleanup.
        return;
      }
      Duration age =
          Duration.between(Instant.ofEpochMilli(status.getLastModifiedTime()), Instant.now());
      if (age.compareTo(maxAge) < 0) {
        // The install base was recently used. Back off.
        // If the install base belongs to an older binary that doesn't lock it before use, it's
        // possible to hit a tiny race condition between the older binary checking whether the
        // install base exists and updating its mtime. Unfortunately, this is the best we can do.
        return;
      }
      // Rename the install base before deleting it.
      // This avoids leaving behind a corrupted install base if the deletion is interrupted, which
      // would be treated as a fatal error by a subsequent invocation and require a manual cleanup.
      // The new name must be unique, because the same install base can be recreated and deleted for
      // a second time after a first deletion attempt is interrupted.
      pathToDelete = getDeletedPath(installBase);
      installBase.renameTo(pathToDelete);
      // Now that the install base has been renamed, we can delete the lock file.
      // This is done early to avoid leaving the lock file behind if the deletion is interrupted.
      // It's still possible to get interrupted in between the rename and delete, but we accept it.
      lockPath.delete();
    } catch (LockAlreadyHeldException e) {
      // Looks like this install base is currently in use. Back off.
      return;
    }
    // We can now perform the actual deletion.
    pathToDelete.deleteTree();
  }

  private static Path getLockPath(Path installBase) {
    Path parent = installBase.getParentDirectory();
    return parent.getChild(installBase.getBaseName() + LOCK_SUFFIX);
  }

  private static Path getDeletedPath(Path installBase) {
    Path parent = installBase.getParentDirectory();
    return parent.getChild(UUID.randomUUID() + DELETED_SUFFIX);
  }

  private static boolean isInstallBase(Path path) {
    String name = path.getBaseName();
    return name.length() == 32
        && name.chars().allMatch(c -> (c >= 'a' && c <= 'f') || (c >= '0' && c <= '9'));
  }

  private static boolean isIncompleteDeletion(Path path) {
    return path.getBaseName().endsWith(DELETED_SUFFIX);
  }
}
