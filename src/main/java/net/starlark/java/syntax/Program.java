// Copyright 2020 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * An opaque, executable representation of a valid Starlark program. Programs may
 * [eventually---TODO(adonovan)] be efficiently serialized and deserialized without parsing and
 * recompiling.
 */
public final class Program {

  private final Resolver.Function body;
  private final ImmutableList<String> loads;
  private final ImmutableList<Location> loadLocations;

  private Program(
      Resolver.Function body, ImmutableList<String> loads, ImmutableList<Location> loadLocations) {
    Preconditions.checkArgument(
        loads.size() == loadLocations.size(), "each load must have a corresponding location");

    // TODO(adonovan): compile here.
    this.body = body;
    this.loads = loads;
    this.loadLocations = loadLocations;
  }

  // TODO(adonovan): eliminate once Eval no longer needs access to syntax.
  public Resolver.Function getResolvedFunction() {
    return body;
  }

  /** Returns the file name of this compiled program. */
  public String getFilename() {
    return body.getLocation().file();
  }

  /** Returns the list of load strings of this compiled program, in source order. */
  public ImmutableList<String> getLoads() {
    return loads;
  }

  /*** Returns the location of the ith load (see {@link #getLoads}). */
  public Location getLoadLocation(int i) {
    return loadLocations.get(i);
  }

  /**
   * Resolves a file syntax tree in the specified environment and compiles it to a Program. This
   * operation mutates the syntax tree, both by resolving identifiers and recording local variables,
   * and in case of error, by appending to {@code file.errors()}.
   *
   * @throws SyntaxError.Exception in case of resolution error, or if the syntax tree already
   *     contained syntax scan/parse errors. Resolution errors are added to {@code file.errors()}.
   */
  public static Program compileFile(StarlarkFile file, Resolver.Module env)
      throws SyntaxError.Exception {
    Resolver.resolveFile(file, env);
    if (!file.ok()) {
      throw new SyntaxError.Exception(file.errors());
    }

    // Extract load statements.
    ImmutableList.Builder<String> loads = ImmutableList.builder();
    ImmutableList.Builder<Location> loadLocations = ImmutableList.builder();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement load) {
        String module = load.getImport().getValue();
        loads.add(module);
        loadLocations.add(load.getImport().getLocation());
      }
    }

    return new Program(file.getResolvedFunction(), loads.build(), loadLocations.build());
  }

  /**
   * Resolves an expression syntax tree in the specified environment and compiles it to a Program.
   * This operation mutates the syntax tree. The {@code options} must match those used when parsing
   * expression.
   *
   * @throws SyntaxError.Exception in case of resolution error.
   */
  public static Program compileExpr(Expression expr, Resolver.Module module, FileOptions options)
      throws SyntaxError.Exception {
    Resolver.Function body = Resolver.resolveExpr(expr, module, options);
    return new Program(body, /*loads=*/ ImmutableList.of(), /*loadLocations=*/ ImmutableList.of());
  }
}
