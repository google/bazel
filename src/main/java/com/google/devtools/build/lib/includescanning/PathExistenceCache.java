// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Cache to store file existence status for include paths. Only paths that are considered immutable
 * for the duration of the build (any path outside of blaze-out directory will satisfy that
 * criteria) are cached. This information is used by LegacyIncludeScanner class.
 */
@ThreadSafe
class PathExistenceCache {
  private final Path execRoot;
  private final ArtifactFactory artifactFactory;

  private final Map<PathFragment, ListenableFuture<Boolean>> fileExistenceCache =
      new ConcurrentHashMap<>();
  private final Map<PathFragment, ListenableFuture<Boolean>> directoryExistenceCache =
      new ConcurrentHashMap<>();

  PathExistenceCache(Path execRoot, ArtifactFactory artifactFactory) {
    this.execRoot = execRoot;
    this.artifactFactory = artifactFactory;
  }

  /** Returns true if given path exists and is a file, false otherwise. */
  boolean fileExists(PathFragment execPath, boolean isSource) {
    // This is not using computeIfAbsent() as that can lead to substantial contention. As per the
    // CompactHashMap documentation, the computation for computeIfAbsent() "should be short and
    // simple", which file stat'ing is not.
    SettableFuture<Boolean> newFuture = SettableFuture.create();
    var existingFuture = fileExistenceCache.putIfAbsent(execPath, newFuture);
    if (existingFuture == null) {
      existingFuture = newFuture;
      Path path =
          isSource
              ? artifactFactory.getPathFromSourceExecPath(execRoot, execPath)
              : execRoot.getRelative(execPath);
      newFuture.set(path.isFile());
    }
    try {
      return Uninterruptibles.getUninterruptibly(existingFuture);
    } catch (ExecutionException e) {
      throw new AssertionError("Unexpected ExecutionException", e);
    }
  }

  /** Returns true if given path exists and is a directory, false otherwise. */
  boolean directoryExists(PathFragment execPath) {
    // Like for fileExists(), do not use computeIfAbsent() to avoid contention (see comment there).
    SettableFuture<Boolean> newFuture = SettableFuture.create();
    var existingFuture = directoryExistenceCache.putIfAbsent(execPath, newFuture);
    if (existingFuture == null) {
      existingFuture = newFuture;
      Path path = artifactFactory.getPathFromSourceExecPath(execRoot, execPath);
      newFuture.set(path.isDirectory());
    }
    try {
      return Uninterruptibles.getUninterruptibly(existingFuture);
    } catch (ExecutionException e) {
      throw new AssertionError("Unexpected ExecutionException", e);
    }
  }
}
