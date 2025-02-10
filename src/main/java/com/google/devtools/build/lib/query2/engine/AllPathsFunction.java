// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.CustomFunctionQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Implementation of the <code>allpaths()</code> function.
 */
public class AllPathsFunction implements QueryFunction {
  AllPathsFunction() {
  }

  @Override
  public String getName() {
    return "allpaths";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public List<ArgumentType> getArgumentTypes() {
    return ImmutableList.of(ArgumentType.EXPRESSION, ArgumentType.EXPRESSION);
  }

  @Override
  public <T> QueryTaskFuture<Void> eval(
      final QueryEnvironment<T> env,
      QueryExpressionContext<T> context,
      QueryExpression expression,
      List<Argument> args,
      Callback<T> callback) {
    if (env instanceof StreamableQueryEnvironment) {
      return ((StreamableQueryEnvironment<T>) env)
          .allPaths(
              args.get(0).getExpression(),
              args.get(1).getExpression(),
              context,
              callback,
              expression);
    }

    QueryTaskFuture<ThreadSafeMutableSet<T>> fromValueFuture =
        QueryUtil.evalAll(env, context, args.get(0).getExpression());
    QueryTaskFuture<ThreadSafeMutableSet<T>> toValueFuture =
        QueryUtil.evalAll(env, context, args.get(1).getExpression());

    if (env instanceof CustomFunctionQueryEnvironment) {
      return env.whenAllSucceedCall(
          ImmutableList.of(fromValueFuture, toValueFuture),
          () -> {
            ThreadSafeMutableSet<T> fromValue = fromValueFuture.getIfSuccessful();
            ThreadSafeMutableSet<T> toValue = toValueFuture.getIfSuccessful();
            ((CustomFunctionQueryEnvironment<T>) env)
                .allPaths(fromValue, toValue, expression, callback);
            return null;
          });
    }
    return env.whenAllSucceedCall(
        ImmutableList.of(fromValueFuture, toValueFuture),
        () -> {
          // Algorithm: compute "reachableFromX", the forward transitive closure of the "from" set,
          // then find the intersection of "reachableFromX" with the reverse transitive closure of
          // the "to" set.  The reverse transitive closure and intersection operations are
          // interleaved for efficiency. "result" holds the intersection.

          ThreadSafeMutableSet<T> fromValue = fromValueFuture.getIfSuccessful();
          ThreadSafeMutableSet<T> toValue = toValueFuture.getIfSuccessful();

          env.buildTransitiveClosure(expression, fromValue, OptionalInt.empty());

          Set<T> reachableFromX = env.getTransitiveClosure(fromValue, context);
          Predicate<T> reachable = Predicates.in(reachableFromX);
          Uniquifier<T> uniquifier = env.createUniquifier();
          ImmutableList<T> result = uniquifier.unique(intersection(reachableFromX, toValue));
          callback.process(result);
          ImmutableList<T> worklist = result;
          while (!worklist.isEmpty()) {
            Iterable<T> reverseDeps = env.getReverseDeps(worklist, context);
            worklist = uniquifier.unique(Iterables.filter(reverseDeps, reachable));
            callback.process(worklist);
          }
          return null;
        });
  }

  /**
   * Returns a (new, mutable, unordered) set containing the intersection of the
   * two specified sets.
   */
  private static <T> Set<T> intersection(Set<T> x, Set<T> y) {
    Set<T> result = new HashSet<>();
    if (x.size() > y.size()) {
      Sets.intersection(y, x).copyInto(result);
    } else {
      Sets.intersection(x, y).copyInto(result);
    }
    return result;
  }
}
