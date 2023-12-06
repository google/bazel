// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.producers;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.Globber.Operation;
import com.google.devtools.build.lib.packages.producers.GlobComputationProducer.GlobDetail;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Handles package (sub-)directory entries which matches part or all of the glob pattern fragments.
 *
 * <p>Created by {@link PatternWithWildcardProducer} or {@link PatternWithoutWildcardProducer} after
 * they confirm that {@link #direntPath} matches part or all of the glob pattern fragments.
 *
 * <p>Checks whether {@link #direntPath} is a qualified glob matching result, and add to result if
 * so. Before creating the next {@link FragmentProducer} also checks whether (1) there are pattern
 * fragment(s) left to match and (2) subpackage crossing exists .
 */
final class DirentProducer implements StateMachine, Consumer<SkyValue> {

  // -------------------- Input --------------------
  private final GlobDetail globDetail;

  /** The {@link PathFragment} of the dirent containing the package fragments. */
  private final PathFragment direntPath;

  private final int fragmentIndex;
  private final boolean isDir;

  // -------------------- Internal State --------------------
  private PackageLookupValue packageLookupValue = null;
  @Nullable private final Set<Pair<PathFragment, Integer>> visitedGlobSubTasks;

  // -------------------- Output --------------------
  private final FragmentProducer.ResultSink resultSink;

  DirentProducer(
      GlobDetail globDetail,
      PathFragment direntPath,
      int fragmentIndex,
      boolean isDir,
      FragmentProducer.ResultSink resultSink,
      @Nullable Set<Pair<PathFragment, Integer>> visitedGlobSubTasks) {
    // Upstream logic should already have appended some dirent to the package fragment when
    // constructing this `direntPath`.
    Preconditions.checkArgument(
        !direntPath.equals(globDetail.packageIdentifier().getPackageFragment()));
    this.direntPath = direntPath;
    this.globDetail = globDetail;
    this.fragmentIndex = fragmentIndex;
    this.isDir = isDir;
    this.resultSink = resultSink;
    this.visitedGlobSubTasks = visitedGlobSubTasks;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    if (!isDir) {
      // The dirent is just a file, we need to check whether this is a glob matching before
      // returning.
      if (shouldAddResult(/* isDir= */ false, /* isSubpackage= */ false)) {
        resultSink.acceptPathFragmentWithPackageFragment(direntPath);
      }
      return DONE;
    }

    // Check whether the next directory path matches any `IgnoredPackagePrefix`.
    for (PathFragment ignoredPrefix : globDetail.ignoredPackagePrefixesPatterns()) {
      if (direntPath.startsWith(ignoredPrefix)) {
        return DONE;
      }
    }

    tasks.lookUp(
        PackageLookupValue.key(
            PackageIdentifier.create(globDetail.packageIdentifier().getRepository(), direntPath)),
        (Consumer<SkyValue>) this);
    return this::checkSubpackageExistence;
  }

  @Override
  public void accept(SkyValue skyValue) {
    packageLookupValue = (PackageLookupValue) skyValue;
  }

  private StateMachine checkSubpackageExistence(Tasks tasks) {
    Preconditions.checkNotNull(packageLookupValue);
    if (packageLookupValue
        instanceof PackageLookupValue.IncorrectRepositoryReferencePackageLookupValue) {
      // Cross repository boundary, so glob expansion should not descend into that subdir.
      return DONE;
    }

    if (packageLookupValue.packageExists()) {
      // Cross the package boundary. The subdir contains a BUILD file and thus defines another
      // package, so glob expansion should not descend into that subdir.
      if (globDetail.globOperation().equals(Operation.SUBPACKAGES)) {
        // If this is a subpackages() call, we need to check whether this subpackage is a glob
        // matching before returning.
        if (shouldAddResult(/* isDir= */ true, /* isSubpackage= */ true)) {
          resultSink.acceptPathFragmentWithPackageFragment(direntPath);
        }
      }
      return DONE;
    }

    return addResultsOrCreateNextFragmentProducer(tasks);
  }

  private StateMachine addResultsOrCreateNextFragmentProducer(Tasks tasks) {
    // Even for directory dirent, we need to check whether this path is a matching result.
    if (shouldAddResult(/* isDir= */ true, /* isSubpackage= */ false)) {
      resultSink.acceptPathFragmentWithPackageFragment(direntPath);
    }

    int nextFragmentIndex =
        globDetail.patternFragments().get(fragmentIndex).equals("**")
            ? fragmentIndex
            : fragmentIndex + 1;
    if (nextFragmentIndex == globDetail.patternFragments().size()) {
      // When the last glob pattern is not double star, we have already processed all pattern
      // fragments, so execution enters this block and immediately returns.
      return DONE;
    }

    if (visitedGlobSubTasks == null
        || visitedGlobSubTasks.add(Pair.of(direntPath, nextFragmentIndex))) {
      // Create the next unvisited `FragmentProducer` if it has not been processed.
      tasks.enqueue(
          new FragmentProducer(
              globDetail, direntPath, nextFragmentIndex, visitedGlobSubTasks, resultSink));
    }
    return DONE;
  }

  /** Returns {@code true} if {@code path} can be added as a glob matching result. */
  private boolean shouldAddResult(boolean isDir, boolean isSubpackage) {
    if (fragmentIndex < globDetail.patternFragments().size() - 1) {
      // Early return because this dirent path has not matched all pattern fragments.
      return false;
    }

    switch (globDetail.globOperation()) {
      case FILES:
        return !isDir;
      case SUBPACKAGES:
        return isDir && isSubpackage;
      case FILES_AND_DIRS:
        return !isSubpackage;
    }
    throw new IllegalStateException(
        "Unexpected globber globberOperation = [" + globDetail.globOperation() + "]");
  }
}
