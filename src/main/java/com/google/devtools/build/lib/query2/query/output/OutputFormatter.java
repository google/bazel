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
package com.google.devtools.build.lib.query2.query.output;

import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** Interface for classes which order, format and print the result of a Blaze graph query. */
public abstract class OutputFormatter {

  /** Returns the user-visible name of the output formatter. */
  public abstract String getName();

  /**
   * Workaround for a bug in {@link java.nio.channels.Channels#newChannel(OutputStream)}, which
   * attempts to close the output stream on interrupt, which can cause a deadlock if there is an
   * ongoing write. If this formatter uses Channels.newChannel, then it must return false here, and
   * perform its own buffering.
   */
  public boolean canBeBuffered() {
    return true;
  }

  /**
   * Verifies that the environment and expression are compatible with this formatter, throws a
   * {@link QueryException} if not.
   */
  public void verifyCompatible(QueryEnvironment<?> env, QueryExpression expr)
      throws QueryException {}

  /**
   * Format the result (a set of target nodes implicitly ordered according to the graph maintained
   * by the QueryEnvironment), and print it to "out".
   */
  public abstract void output(
      QueryOptions options,
      Digraph<Target> result,
      OutputStream out,
      AspectResolver aspectProvider,
      @Nullable EventHandler eventHandler,
      HashFunction hashFunction,
      LabelPrinter labelPrinter)
      throws IOException, InterruptedException;

  /**
   * Sets a source root to use when formatting an absolute {@link Location}.
   *
   * <p>If set, the given root replaces the source root in packages. This is useful to replace a
   * {@linkplain com.google.devtools.build.lib.analysis.BlazeDirectories#getVirtualSourceRoot
   * virtual source root}.
   */
  public void setOverrideSourceRoot(PathFragment sourceRoot) {}
}
