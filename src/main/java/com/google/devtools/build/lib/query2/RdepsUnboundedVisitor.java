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
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.ParallelSkyQueryUtils.DepAndRdep;
import com.google.devtools.build.lib.query2.ParallelVisitorUtils.ParallelQueryVisitor;
import com.google.devtools.build.lib.query2.ParallelVisitorUtils.QueryVisitorFactory;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.Uniquifier;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * A helper class that computes unbounded 'allrdeps(<expr>)' or 'rdeps(<precomputed-universe>,
 * <expr>)' via BFS.
 *
 * <p>The visitor uses {@link DepAndRdep} to keep track the nodes to visit and avoid dealing with
 * targetification of reverse deps until they are needed. The rdep node itself is needed to filter
 * out disallowed deps later. Compared against the approach using a single SkyKey, it consumes 16
 * more bytes in a 64-bit environment for each edge. However it defers the need to load all the
 * packages which have at least a target as a rdep of the current batch, thus greatly reduces the
 * risk of OOMs. The additional memory usage should not be a large concern here, as even with 10M
 * edges, the memory overhead is around 160M, and the memory can be reclaimed by regular GC.
 *
 * <p>TODO(bazel-team): Split this up into two classes: one which does edge filtering and which
 * doesn't.
 */
class RdepsUnboundedVisitor extends AbstractTargetOuputtingVisitor<DepAndRdep> {
  /**
   * A {@link Uniquifier} for *valid* visitations of rdeps. {@code env}'s dependency filter might
   * mean that some rdep edges are invalid, meaning that any individual {@link DepAndRdep}
   * visitation may actually be invalid. Because the same rdep can be reached through more than one
   * reverse edge, it'd be incorrect to naively dedupe visitations solely based on the rdep.
   */
  private final Uniquifier<SkyKey> validRdepUniquifier;

  private final ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps;

  private final Predicate<SkyKey> unfilteredUniverse;

  RdepsUnboundedVisitor(
      SkyQueryEnvironment env,
      Uniquifier<SkyKey> validRdepUniquifier,
      Predicate<SkyKey> unfilteredUniverse,
      ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps,
      Callback<Target> callback) {
    super(env, callback);
    this.validRdepUniquifier = validRdepUniquifier;
    this.unfilteredUniverse = unfilteredUniverse;
    this.extraGlobalDeps = extraGlobalDeps;
  }

  /**
   * A {@link Factory} for {@link RdepsUnboundedVisitor} instances, each of which will be used to
   * perform visitation of the reverse transitive closure of the {@link Target}s passed in a single
   * {@link Callback#process} call. Note that all the created instances share the same {@link
   * Uniquifier} so that we don't visit the same Skyframe node more than once.
   */
  static class Factory implements QueryVisitorFactory<DepAndRdep, SkyKey, Target> {
    private final SkyQueryEnvironment env;
    private final Uniquifier<SkyKey> validRdepUniquifier;
    private final Predicate<SkyKey> unfilteredUniverse;
    private final ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps;
    private final Callback<Target> callback;

    Factory(
        SkyQueryEnvironment env,
        Predicate<SkyKey> unfilteredUniverse,
        ImmutableSetMultimap<SkyKey, SkyKey> extraGlobalDeps,
        Callback<Target> callback) {
      this.env = env;
      this.unfilteredUniverse = unfilteredUniverse;
      this.validRdepUniquifier = env.createSkyKeyUniquifier();
      this.extraGlobalDeps = extraGlobalDeps;
      this.callback = callback;
    }

    @Override
    public ParallelQueryVisitor<DepAndRdep, SkyKey, Target> create() {
      return new RdepsUnboundedVisitor(
          env, validRdepUniquifier, unfilteredUniverse, extraGlobalDeps, callback);
    }
  }

  @Override
  protected Visit getVisitResult(Iterable<DepAndRdep> depAndRdeps)
      throws QueryException, InterruptedException {
    ImmutableList.Builder<SkyKey> uniqueValidRdepsbuilder = ImmutableList.builder();
    for (SkyKey rdep : RdepsVisitorUtils.getMaybeFilteredRdeps(depAndRdeps, env)) {
      if (validRdepUniquifier.unique(rdep)) {
        uniqueValidRdepsbuilder.add(rdep);
      }
    }
    ImmutableList<SkyKey> uniqueValidRdeps = uniqueValidRdepsbuilder.build();

    // Retrieve the reverse deps as SkyKeys and defer the targetification and filtering to next
    // recursive visitation. Because the universe given to us is unfiltered, we definitely still
    // need to filter out disallowed edges, but cannot do so before targetification occurs. This
    // means we may be wastefully visiting nodes via disallowed edges.
    ImmutableList.Builder<DepAndRdep> depAndRdepsToVisitBuilder = ImmutableList.builder();
    env.getReverseDepLabelsOfLabels(uniqueValidRdeps, extraGlobalDeps)
        .forEach(
            (key, value) ->
                depAndRdepsToVisitBuilder.addAll(
                    Iterables.transform(
                        Iterables.filter(
                            value,
                            Predicates.and(SkyQueryEnvironment.IS_LABEL, unfilteredUniverse)),
                        (com.google.devtools.build.skyframe.SkyKey rdep) ->
                            new DepAndRdep(key, rdep))));

    return new Visit(
        /*keysToUseForResult=*/ uniqueValidRdeps,
        /*keysToVisit=*/ depAndRdepsToVisitBuilder.build());
  }

  @Override
  protected SkyKey visitationKeyToOutputKey(DepAndRdep visitationKey) {
    return visitationKey.rdep;
  }

  @Override
  protected Iterable<DepAndRdep> noteAndReturnUniqueVisitationKeys(
      Iterable<DepAndRdep> prospectiveVisitationKeys) {
    // This isn't correct in isolation, but is end-to-end correct given the way ParallelVisitor
    // works: the contents of the input Iterable are surely unique because of the way this method is
    // called (a single dep can't have duplicate rdeps), and each node is [validly] visited at most
    // once, so by induction each DepAndRdep this RdepsUnboundedVisitor ever sees in this method is
    // unique across all calls.
    return Iterables.filter(
        prospectiveVisitationKeys, depAndRdep -> validRdepUniquifier.uniquePure(depAndRdep.rdep));
  }

  @Override
  protected Iterable<DepAndRdep> preprocessInitialVisit(Iterable<SkyKey> skyKeys) {
    return Iterables.transform(
        Iterables.filter(skyKeys, k -> unfilteredUniverse.apply(k)),
        key -> new DepAndRdep(/*dep=*/ null, key));
  }
}
