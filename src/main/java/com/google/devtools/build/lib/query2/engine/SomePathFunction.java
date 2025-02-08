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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.CustomFunctionQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskCallable;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ThreadSafeMutableSet;
import java.util.List;
import java.util.OptionalInt;

/**
 * A somepath(x, y) query expression, which computes the set of nodes on some arbitrary path from a
 * target in set x to a target in set y.
 *
 * <pre>expr ::= SOMEPATH '(' expr ',' expr ')'</pre>
 */
class SomePathFunction implements QueryFunction {
  SomePathFunction() {
  }

  @Override
  public String getName() {
    return "somepath";
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
      final QueryExpression expression,
      List<Argument> args,
      final Callback<T> callback) {
    if (env instanceof StreamableQueryEnvironment) {
      return ((StreamableQueryEnvironment<T>) env)
          .somePath(
              args.get(0).getExpression(),
              args.get(1).getExpression(),
              context,
              callback,
              expression);
    }

    final QueryTaskFuture<ThreadSafeMutableSet<T>> fromValueFuture =
        QueryUtil.evalAll(env, context, args.get(0).getExpression());
    final QueryTaskFuture<ThreadSafeMutableSet<T>> toValueFuture =
        QueryUtil.evalAll(env, context, args.get(1).getExpression());

    if (env instanceof CustomFunctionQueryEnvironment) {
      return env.whenAllSucceedCall(
          ImmutableList.of(fromValueFuture, toValueFuture),
          new QueryTaskCallable<Void>() {
            @Override
            public Void call() throws QueryException, InterruptedException {
              ThreadSafeMutableSet<T> fromValue = fromValueFuture.getIfSuccessful();
              ThreadSafeMutableSet<T> toValue = toValueFuture.getIfSuccessful();
              ((CustomFunctionQueryEnvironment<T>) env)
                  .somePath(fromValue, toValue, expression, callback);
              return null;
            }
          });
    }
    return env.whenAllSucceedCall(
        ImmutableList.of(fromValueFuture, toValueFuture),
        new QueryTaskCallable<Void>() {
          @Override
          public Void call() throws QueryException, InterruptedException {
            // Implementation strategy: for each x in "from", compute its forward
            // transitive closure.  If it intersects "to", then do a path search from x
            // to an arbitrary node in the intersection, and return the path.  This
            // avoids computing the full transitive closure of "from" in some cases.

            ThreadSafeMutableSet<T> fromValue = fromValueFuture.getIfSuccessful();
            ThreadSafeMutableSet<T> toValue = toValueFuture.getIfSuccessful();

            env.buildTransitiveClosure(expression, fromValue, OptionalInt.empty());

            for (T x : fromValue) {
              // TODO(b/122548314): if x was already seen as part of a previous node's tc, we should
              // skip it here. That's subsumed by the TODO below.
              ThreadSafeMutableSet<T> xSet = env.createThreadSafeMutableSet();
              xSet.add(x);
              // TODO(b/122548314): this transitive closure building should stop at any nodes that
              // have already been visited.
              ThreadSafeMutableSet<T> xtc = env.getTransitiveClosure(xSet, context);
              SetView<T> result;
              if (xtc.size() > toValue.size()) {
                result = Sets.intersection(toValue, xtc);
              } else {
                result = Sets.intersection(xtc, toValue);
              }
              if (!result.isEmpty()) {
                callback.process(env.getNodesOnPath(x, result.iterator().next(), context));
                return null;
              }
            }
            callback.process(ImmutableSet.<T>of());
            return null;
          }
        });
  }
}
