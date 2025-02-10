// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.concurrent.CountDownLatch;

/**
 * {@link FileDependencies} implementation that waits for {@link #enable} inside {@link
 * findEarliestMatch}.
 *
 * <p>This can be used to exercise certain concurrency conditions.
 */
final class ControllableFileDependencies extends FileDependencies {
  private final ImmutableList<String> resolvedPaths;
  private final ImmutableList<FileDependencies> dependencies;

  private final CountDownLatch findEarliestMatchEntered = new CountDownLatch(1);
  private final CountDownLatch countDown = new CountDownLatch(1);

  ControllableFileDependencies(
      ImmutableList<String> resolvedPaths, ImmutableList<FileDependencies> dependencies) {
    this.resolvedPaths = resolvedPaths;
    this.dependencies = dependencies;
  }

  void awaitEarliestMatchEntered() throws InterruptedException {
    findEarliestMatchEntered.await();
  }

  void enable() {
    countDown.countDown();
  }

  @Override
  int findEarliestMatch(VersionedChanges changes, int validityHorizon) {
    findEarliestMatchEntered.countDown();
    try {
      countDown.await();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    int minMatch = VersionedChanges.NO_MATCH;
    for (String element : resolvedPaths) {
      int result = changes.matchFileChange(element, validityHorizon);
      if (result < minMatch) {
        minMatch = result;
      }
    }
    return minMatch;
  }

  @Override
  int getDependencyCount() {
    return dependencies.size();
  }

  @Override
  FileDependencies getDependency(int index) {
    return dependencies.get(index);
  }

  @Override
  String resolvedPath() {
    return Iterables.getLast(resolvedPaths);
  }

  @Override
  ImmutableList<String> getAllResolvedPathsForTesting() {
    return resolvedPaths;
  }
}
