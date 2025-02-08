// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.ParallelSkyQueryUtils.DepAndRdep;
import com.google.devtools.build.lib.query2.ParallelSkyQueryUtils.DepAndRdepAtDepth;
import com.google.devtools.build.lib.query2.ParallelVisitorUtils.ParallelQueryVisitor;
import com.google.devtools.build.lib.query2.ParallelVisitorUtils.QueryVisitorFactory;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.MinDepthUniquifier;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.HashMap;
import java.util.Map;

/**
 * A helper class that computes bounded 'allrdeps(<expr>, <depth>)' or
 * 'rdeps(<precomputed-universe>, <expr>, <depth>)' via BFS.
 *
 * <p>This is very similar to {@link RdepsUnboundedVisitor}. A lot of the same concerns apply here
 * but there are additional subtle concerns about the correctness of the bounded traversal: just
 * like for the sequential implementation of bounded allrdeps, we use {@link MinDepthUniquifier}.
 */
class RdepsBoundedVisitor extends AbstractTargetOuputtingVisitor<DepAndRdepAtDepth> {
  private final int depth;
  private final MinDepthUniquifier<SkyKey> validRdepMinDepthUniquifier;
  private final Predicate<SkyKey> universe;
  private final ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps;

  private RdepsBoundedVisitor(
      SkyQueryEnvironment env,
      int depth,
      MinDepthUniquifier<SkyKey> validRdepMinDepthUniquifier,
      Predicate<SkyKey> universe,
      ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps,
      Callback<Target> callback) {
    super(env, callback);
    this.depth = depth;
    this.validRdepMinDepthUniquifier = validRdepMinDepthUniquifier;
    this.extraGlobalDeps = extraGlobalDeps;
    this.universe = universe;
  }

  static class Factory implements QueryVisitorFactory<DepAndRdepAtDepth, SkyKey, Target> {
    private final SkyQueryEnvironment env;
    private final int depth;
    private final MinDepthUniquifier<SkyKey> validRdepMinDepthUniquifier;
    private final Predicate<SkyKey> universe;
    private final ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps;
    private final Callback<Target> callback;

    Factory(
        SkyQueryEnvironment env,
        int depth,
        Predicate<SkyKey> universe,
        ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps,
        Callback<Target> callback) {
      this.env = env;
      this.depth = depth;
      this.universe = universe;
      this.validRdepMinDepthUniquifier = env.createMinDepthSkyKeyUniquifier();
      this.extraGlobalDeps = extraGlobalDeps;
      this.callback = callback;
    }

    @Override
    public ParallelQueryVisitor<DepAndRdepAtDepth, SkyKey, Target> create() {
      return new RdepsBoundedVisitor(
          env, depth, validRdepMinDepthUniquifier, universe, extraGlobalDeps, callback);
    }
  }

  @Override
  protected Visit getVisitResult(Iterable<DepAndRdepAtDepth> depAndRdepAtDepths)
      throws InterruptedException {
    Map<SkyKey, Integer> shallowestRdepDepthMap = new HashMap<>();
    depAndRdepAtDepths.forEach(
        depAndRdepAtDepth ->
            shallowestRdepDepthMap.merge(
                depAndRdepAtDepth.depAndRdep.rdep, depAndRdepAtDepth.rdepDepth, Integer::min));

    ImmutableList<SkyKey> uniqueValidRdeps =
        Streams.stream(
                RdepsVisitorUtils.getMaybeFilteredRdeps(
                    Iterables.transform(
                        depAndRdepAtDepths, depAndRdepAtDepth -> depAndRdepAtDepth.depAndRdep),
                    env))
            .filter(
                validRdep ->
                    validRdepMinDepthUniquifier.uniqueAtDepthLessThanOrEqualTo(
                        validRdep, shallowestRdepDepthMap.get(validRdep)))
            .collect(ImmutableList.toImmutableList());

    // Don't bother getting the rdeps of the rdeps that are already at the depth bound.
    Iterable<SkyKey> uniqueValidRdepsBelowDepthBound =
        Iterables.filter(
            uniqueValidRdeps,
            uniqueValidRdep -> shallowestRdepDepthMap.get(uniqueValidRdep) < depth);

    // Retrieve the reverse deps as SkyKeys and defer the targetification and filtering to next
    // recursive visitation.
    Map<SkyKey, Iterable<SkyKey>> unfilteredRdepsOfRdeps =
        env.getReverseDepLabelsOfLabels(uniqueValidRdepsBelowDepthBound, extraGlobalDeps);

    ImmutableList.Builder<DepAndRdepAtDepth> depAndRdepAtDepthsToVisitBuilder =
        ImmutableList.builder();
    unfilteredRdepsOfRdeps
        .entrySet()
        .forEach(
            entry -> {
              SkyKey rdep = entry.getKey();
              int depthOfRdepOfRdep = shallowestRdepDepthMap.get(rdep) + 1;
              Streams.stream(entry.getValue())
                  .filter(Predicates.and(SkyQueryEnvironment.IS_LABEL, universe))
                  .forEachOrdered(
                      rdepOfRdep -> {
                        depAndRdepAtDepthsToVisitBuilder.add(
                            new DepAndRdepAtDepth(
                                new DepAndRdep(rdep, rdepOfRdep), depthOfRdepOfRdep));
                      });
            });

    return new Visit(
        /*keysToUseForResult=*/ uniqueValidRdeps,
        /*keysToVisit=*/ depAndRdepAtDepthsToVisitBuilder.build());
  }

  @Override
  protected SkyKey visitationKeyToOutputKey(DepAndRdepAtDepth visitationKey) {
    return visitationKey.depAndRdep.rdep;
  }

  @Override
  protected Iterable<DepAndRdepAtDepth> noteAndReturnUniqueVisitationKeys(
      Iterable<DepAndRdepAtDepth> prospectiveVisitationKeys) {
    // See the comment in RdepsUnboundedVisitor#noteAndReturnUniqueVisitationKeys.
    return Iterables.filter(
        prospectiveVisitationKeys,
        depAndRdepAtDepth ->
            validRdepMinDepthUniquifier.uniqueAtDepthLessThanOrEqualToPure(
                depAndRdepAtDepth.depAndRdep.rdep, depAndRdepAtDepth.rdepDepth));
  }

  @Override
  protected Iterable<DepAndRdepAtDepth> preprocessInitialVisit(Iterable<SkyKey> skyKeys) {
    return Iterables.transform(
        skyKeys, key -> new DepAndRdepAtDepth(new DepAndRdep(/*dep=*/ null, key), 0));
  }
}
