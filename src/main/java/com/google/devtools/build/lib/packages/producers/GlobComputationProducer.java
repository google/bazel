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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.skyframe.GlobDescriptor;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.state.StateMachine;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves as the entrance {@link StateMachine} to compute a single glob. There are two ways to
 * create {@link GlobComputationProducer}:
 *
 * <ul>
 *   <li>When each glob within a package is represented as an individual GLOB node, {@link
 *       com.google.devtools.build.lib.skyframe.GlobFunctionWithRecursionInSingleFunction} creates
 *       {@link GlobComputationProducer} to start the computation of each GLOB node.
 *   <li>All globs within a package are held by a single GLOBS node. When a package's depending
 *       {@link com.google.devtools.build.lib.skyframe.GlobsFunction#compute} is called for the
 *       first time, multiple {@link GlobComputationProducer}s are created for each individual
 *       package glob, and they shall be driven in-parallel.
 * </ul>
 */
public final class GlobComputationProducer implements StateMachine, FragmentProducer.ResultSink {

  /**
   * Propagates all glob matching {@link PathFragment}s or any {@link Exception}.
   *
   * <p>If any {@link GlobError} is accepted, the already discovered path fragments are still
   * reported. However, {@link com.google.devtools.build.lib.skyframe.GlobFunction} and {@link
   * com.google.devtools.build.lib.skyframe.GlobsFunction} throw the first discovered {@link
   * GlobError} wrapped in a {@link com.google.devtools.build.lib.skyframe.GlobException}.
   *
   * <p>The already discovered path fragments should be considered as undefined. Since: (1) there is
   * no skyframe restart after glob computation throws an exception, so the discovered path
   * fragments can miss some matchings; (2) these discovered path fragments are not used to
   * construct a {@link com.google.devtools.build.lib.skyframe.GlobValue} or {@link
   * com.google.devtools.build.lib.skyframe.GlobsValue}.
   */
  public interface ResultSink {
    void acceptPathFragmentsWithoutPackageFragment(ImmutableSet<PathFragment> pathFragments);

    void acceptGlobError(GlobError error);
  }

  // -------------------- Input --------------------
  private final GlobDescriptor globDescriptor;
  private final ResultSink resultSink;

  // -------------------- Internal State --------------------
  private final ImmutableSet.Builder<PathFragment> pathFragmentsWithPackageFragment;
  private final IgnoredSubdirectories ignoredSubdirectories;
  private final ConcurrentHashMap<String, Pattern> regexPatternCache;

  public GlobComputationProducer(
      GlobDescriptor globDescriptor,
      IgnoredSubdirectories ignoredSubdirectories,
      ConcurrentHashMap<String, Pattern> regexPatternCache,
      ResultSink resultSink) {
    this.globDescriptor = globDescriptor;
    this.ignoredSubdirectories = ignoredSubdirectories;
    this.regexPatternCache = regexPatternCache;
    this.resultSink = resultSink;
    this.pathFragmentsWithPackageFragment = ImmutableSet.builder();
  }

  @Override
  public StateMachine step(Tasks tasks) {
    Preconditions.checkNotNull(ignoredSubdirectories);
    ImmutableList<String> patterns =
        ImmutableList.copyOf(Splitter.on('/').split(globDescriptor.getPattern()));
    GlobDetail globDetail =
        GlobDetail.create(
            globDescriptor.getPackageId(),
            globDescriptor.getPackageRoot(),
            patterns,
            /* containsMultipleDoubleStars= */ Collections.frequency(patterns, "**") > 1,
            ignoredSubdirectories,
            regexPatternCache,
            globDescriptor.globberOperation());
    Set<Pair<PathFragment, Integer>> visitedGlobSubTasks = null;
    if (globDetail.containsMultipleDoubleStars()) {
      visitedGlobSubTasks = new HashSet<>();
    }
    tasks.enqueue(
        new FragmentProducer(
            globDetail,
            globDetail
                .packageIdentifier()
                .getPackageFragment()
                .getRelative(globDescriptor.getSubdir()),
            /* fragmentIndex= */ 0,
            visitedGlobSubTasks,
            (FragmentProducer.ResultSink) this));
    return this::buildAndBubbleUpResult;
  }

  @Override
  public void acceptPathFragmentWithPackageFragment(PathFragment pathFragment) {
    pathFragmentsWithPackageFragment.add(pathFragment);
  }

  @Override
  public void acceptGlobError(GlobError error) {
    resultSink.acceptGlobError(error);
  }

  /**
   * Removes the package fragment from all accepted matching path fragments before {@link
   * ResultSink} accepts them.
   */
  public StateMachine buildAndBubbleUpResult(Tasks tasks) {
    resultSink.acceptPathFragmentsWithoutPackageFragment(
        pathFragmentsWithPackageFragment.build().parallelStream()
            .map(p -> p.relativeTo(globDescriptor.getPackageId().getPackageFragment()))
            .collect(toImmutableSet()));
    return DONE;
  }

  /**
   * Container which holds all constant information needed for globbing.
   *
   * <p>This object is created and passed into {@link FragmentProducer} so that we only need one
   * reference of {@link GlobDetail} downstream.
   *
   * @param containsMultipleDoubleStars When multiple {@code **} s appear in pattern fragments, a
   *     set is created to track visited glob subtasks in order to prevent duplicate work.
   *     <p>See {@link FragmentProducer#visitedGlobSubTasks} for more details.
   */
  record GlobDetail(
      PackageIdentifier packageIdentifier,
      Root packageRoot,
      ImmutableList<String> patternFragments,
      boolean containsMultipleDoubleStars,
      IgnoredSubdirectories ignoredSubdirectories,
      ConcurrentHashMap<String, Pattern> regexPatternCache,
      Globber.Operation globOperation) {
    GlobDetail {
      requireNonNull(packageIdentifier, "packageIdentifier");
      requireNonNull(packageRoot, "packageRoot");
      requireNonNull(patternFragments, "patternFragments");
      requireNonNull(ignoredSubdirectories, "ignoredSubdirectories");
      requireNonNull(regexPatternCache, "regexPatternCache");
      requireNonNull(globOperation, "globOperation");
    }

    static GlobDetail create(
        PackageIdentifier packageIdentifier,
        Root packageRoot,
        ImmutableList<String> patternFragments,
        boolean containsMultipleDoubleStars,
        IgnoredSubdirectories ignoredSubdirectories,
        ConcurrentHashMap<String, Pattern> regexPatternCache,
        Globber.Operation globOperation) {
      return new GlobDetail(
          packageIdentifier,
          packageRoot,
          patternFragments,
          containsMultipleDoubleStars,
          ignoredSubdirectories,
          regexPatternCache,
          globOperation);
    }

  }
}
