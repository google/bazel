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
package com.google.devtools.build.lib.query2.engine;

/**
 * The environment of a Blaze query which supports predefined streaming operations.
 *
 * @param <T> the node type of the dependency graph
 */
public interface StreamableQueryEnvironment<T> extends QueryEnvironment<T> {
  QueryTaskFuture<Void> getAllRdepsBoundedParallel(
      QueryExpression expression,
      int depth,
      QueryExpressionContext<T> context,
      Callback<T> callback);

  QueryTaskFuture<Void> getAllRdepsUnboundedParallel(
      QueryExpression expression, QueryExpressionContext<T> context, Callback<T> callback);

  QueryTaskFuture<Void> getRdepsBoundedParallel(
      QueryExpression expression,
      int depth,
      QueryExpression universe,
      QueryExpressionContext<T> context,
      Callback<T> callback);

  QueryTaskFuture<Void> getRdepsUnboundedParallel(
      QueryExpression expression,
      QueryExpression universe,
      QueryExpressionContext<T> context,
      Callback<T> callback);

  QueryTaskFuture<Void> getDepsUnboundedParallel(
      QueryExpression expression,
      QueryExpressionContext<T> context,
      Callback<T> callback,
      QueryExpression caller);

  // TODO(bazel-team): Make this parallel.
  QueryTaskFuture<Void> getDepsBounded(
      QueryExpression expression,
      QueryExpressionContext<T> context,
      Callback<T> callback,
      int depth,
      QueryExpression caller);

  QueryTaskFuture<Void> somePath(
      QueryExpression fromExpression,
      QueryExpression toExpression,
      QueryExpressionContext<T> context,
      Callback<T> callback,
      QueryExpression caller);

  QueryTaskFuture<Void> allPaths(
      QueryExpression fromExpression,
      QueryExpression toExpression,
      QueryExpressionContext<T> context,
      Callback<T> callback,
      QueryExpression caller);
}
