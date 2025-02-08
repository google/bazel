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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryExpressionContext;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllCallback;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Parallel implementations of various functionality in {@link SkyQueryEnvironment}.
 *
 * <p>Special attention is given to memory usage. Naive parallel implementations of query
 * functionality would lead to memory blowup. Instead of dealing with {@link Target}s, we try to
 * deal with {@link SkyKey}s as much as possible to reduce the number of {@link Package}s forcibly
 * in memory at any given time.
 */
// TODO(bazel-team): Be more deliberate about bounding memory usage here.
public class ParallelSkyQueryUtils {

  /** The maximum number of keys to visit at once. */
  @VisibleForTesting public static final int VISIT_BATCH_SIZE = 10000;

  private ParallelSkyQueryUtils() {
  }

  static QueryTaskFuture<Void> getAllRdepsUnboundedParallel(
      SkyQueryEnvironment env,
      QueryExpression expression,
      QueryExpressionContext<Target> context,
      Callback<Target> callback) {
    return env.eval(
        expression,
        context,
        ParallelVisitorUtils.createParallelVisitorCallback(
            new RdepsUnboundedVisitor.Factory(
                env,
                /* unfilteredUniverse= */ Predicates.alwaysTrue(),
                context.extraGlobalDeps(),
                callback)));
  }

  static QueryTaskFuture<Void> getAllRdepsBoundedParallel(
      SkyQueryEnvironment env,
      QueryExpression expression,
      int depth,
      QueryExpressionContext<Target> context,
      Callback<Target> callback) {
    return env.eval(
        expression,
        context,
        ParallelVisitorUtils.createParallelVisitorCallback(
            new RdepsBoundedVisitor.Factory(
                env,
                depth,
                /* universe= */ Predicates.alwaysTrue(),
                context.extraGlobalDeps(),
                callback)));
  }

  static QueryTaskFuture<Void> getRdepsInUniverseUnboundedParallel(
      SkyQueryEnvironment env,
      QueryExpression expression,
      Predicate<SkyKey> unfilteredUniverse,
      QueryExpressionContext<Target> context,
      Callback<Target> callback) {
    return env.eval(
        expression,
        context,
        ParallelVisitorUtils.createParallelVisitorCallback(
            new RdepsUnboundedVisitor.Factory(
                env, unfilteredUniverse, context.extraGlobalDeps(), callback)));
  }

  static QueryTaskFuture<Predicate<SkyKey>> getDTCSkyKeyPredicateFuture(
      SkyQueryEnvironment env,
      QueryExpression expression,
      QueryExpressionContext<Target> context,
      int processResultsBatchSize,
      int concurrencyLevel) {
    QueryTaskFuture<ThreadSafeMutableSet<Target>> universeValueFuture =
        QueryUtil.evalAll(env, context, expression);

    Function<ThreadSafeMutableSet<Target>, QueryTaskFuture<Predicate<SkyKey>>>
        getTransitiveClosureAsyncFunction =
            universeValue -> {
              ThreadSafeAggregateAllSkyKeysCallback aggregateAllCallback =
                  new ThreadSafeAggregateAllSkyKeysCallback(concurrencyLevel);
              return env.execute(
                  () -> {
                    UnfilteredSkyKeyLabelDTCVisitor visitor =
                        new UnfilteredSkyKeyLabelDTCVisitor.Factory(
                                env,
                                env.createSkyKeyUniquifier(),
                                processResultsBatchSize,
                                context.extraGlobalDeps(),
                                aggregateAllCallback)
                            .create();
                    visitor.visitAndWaitForCompletion(
                        SkyQueryEnvironment.makeLabelsStrict(universeValue));
                    return Predicates.in(aggregateAllCallback.getResult());
                  });
            };

    return env.transformAsync(universeValueFuture, getTransitiveClosureAsyncFunction);
  }

  static QueryTaskFuture<Void> getRdepsInUniverseBoundedParallel(
      SkyQueryEnvironment env,
      QueryExpression expression,
      int depth,
      Predicate<SkyKey> universe,
      QueryExpressionContext<Target> context,
      Callback<Target> callback) {
    return env.eval(
        expression,
        context,
        ParallelVisitorUtils.createParallelVisitorCallback(
            new RdepsBoundedVisitor.Factory(
                env, depth, universe, context.extraGlobalDeps(), callback)));
  }

  /** Specialized parallel variant of {@link SkyQueryEnvironment#getRBuildFiles}. */
  static void getRBuildFilesParallel(
      SkyQueryEnvironment env,
      Collection<PathFragment> fileIdentifiers,
      QueryExpressionContext<Target> context,
      Callback<Target> callback) throws QueryException, InterruptedException {
    RBuildFilesVisitor visitor =
        new RBuildFilesVisitor(
            env,
            /*visitUniquifier=*/ env.createSkyKeyUniquifier(),
            /*resultUniquifier=*/ env.createSkyKeyUniquifier(),
            context,
            callback);
    visitor.visitFileIdentifiersAndWaitForCompletion(env.graph, fileIdentifiers);
  }

  static QueryTaskFuture<Void> getDepsUnboundedParallel(
      SkyQueryEnvironment env,
      QueryExpression expression,
      QueryExpressionContext<Target> context,
      Callback<Target> callback,
      boolean depsNeedFiltering,
      QueryExpression caller) {
    return env.eval(
        expression,
        context,
        ParallelVisitorUtils.createParallelVisitorCallback(
            new DepsUnboundedVisitor.Factory(env, callback, depsNeedFiltering, context, caller)));
  }

  static class DepAndRdep {
    @Nullable final SkyKey dep;
    final SkyKey rdep;

    DepAndRdep(@Nullable SkyKey dep, SkyKey rdep) {
      this.dep = dep;
      this.rdep = rdep;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof DepAndRdep other)) {
        return false;
      }
      return Objects.equals(dep, other.dep) && rdep.equals(other.rdep);
    }

    @Override
    public int hashCode() {
      // N.B. - We deliberately use a garbage-free hashCode implementation (rather than e.g.
      // Objects#hash). Depending on the structure of the graph being traversed, this method can
      // be very hot.
      return 31 * Objects.hashCode(dep) + rdep.hashCode();
    }
  }

  static class DepAndRdepAtDepth {
    final DepAndRdep depAndRdep;
    final int rdepDepth;

    DepAndRdepAtDepth(DepAndRdep depAndRdep, int rdepDepth) {
      this.depAndRdep = depAndRdep;
      this.rdepDepth = rdepDepth;
    }
  }

  /** Thread-safe {@link AggregateAllCallback} backed by a concurrent {@link Set}. */
  @ThreadSafe
  private static class ThreadSafeAggregateAllSkyKeysCallback
      implements AggregateAllCallback<SkyKey, ImmutableSet<SkyKey>> {

    private final Set<SkyKey> results;

    private ThreadSafeAggregateAllSkyKeysCallback(int concurrencyLevel) {
      this.results =
          Collections.newSetFromMap(
              new ConcurrentHashMap<>(
                  /*initialCapacity=*/ concurrencyLevel, /*loadFactor=*/ 0.75f));
    }

    @Override
    public void process(Iterable<SkyKey> partialResult)
        throws QueryException, InterruptedException {
      Iterables.addAll(results, partialResult);
    }

    @Override
    public ImmutableSet<SkyKey> getResult() {
      return ImmutableSet.copyOf(results);
    }
  }
}
