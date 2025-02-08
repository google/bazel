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

package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/** Syntax node for an identifier. */
public final class Identifier extends Expression {

  private final String name;
  private final int nameOffset;

  // set by Resolver
  @Nullable private Resolver.Binding binding;

  Identifier(FileLocations locs, String name, int nameOffset) {
    super(locs, Kind.IDENTIFIER);
    this.name = name;
    this.nameOffset = nameOffset;
  }

  @Override
  public int getStartOffset() {
    return nameOffset;
  }

  @Override
  public int getEndOffset() {
    return nameOffset + name.length();
  }

  /**
   * Returns the name of the Identifier. If there were parse errors, misparsed regions may be
   * represented as an Identifier for which {@code !isValid(getName())}.
   */
  public String getName() {
    return name;
  }

  public boolean isPrivate() {
    return name.startsWith("_");
  }

  /** Returns information about the binding that the identifier denotes. Set by the resolver. */
  @Nullable
  public Resolver.Binding getBinding() {
    return binding;
  }

  void setBinding(Resolver.Binding bind) {
    Preconditions.checkState(this.binding == null);
    this.binding = bind;
  }

  @Override
  public void accept(NodeVisitor visitor) {
    visitor.visit(this);
  }

  /** Reports whether the string is a valid identifier. */
  public static boolean isValid(String name) {
    // Keep consistent with Lexer.scanIdentifier.
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!(('a' <= c && c <= 'z')
          || ('A' <= c && c <= 'Z')
          || (i > 0 && '0' <= c && c <= '9')
          || (c == '_'))) {
        return false;
      }
    }
    return !name.isEmpty();
  }

  /**
   * Returns all names bound by an LHS expression.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li><{@code x = ...} binds x.
   *   <li><{@code x, [y,z] = ..} binds x, y, z.
   *   <li><{@code x[5] = ..} does not bind any names.
   * </ul>
   */
  // TODO(adonovan): remove this function in due course.
  // - Resolver makes one pass to discover bindings than another to resolve uses.
  //   When it works in a single pass, it is more efficient to process bindings in order,
  //   deferring (rare) forward references until the end of the block.
  // - Eval calls boundIdentifiers for comprehensions. This can be eliminated when
  //   variables are assigned frame slot indices.
  // - Eval calls boundIdentifiers for the 'export' hack. This can be eliminated
  //   when we switch to compilation by emitting EXPORT instructions for the necessary
  //   bindings. (Ideally we would eliminate Bazel's export hack entirely.)
  public static ImmutableSet<Identifier> boundIdentifiers(Expression expr) {
    if (expr instanceof Identifier) {
      // Common case/fast path - skip the builder.
      return ImmutableSet.of((Identifier) expr);
    } else {
      ImmutableSet.Builder<Identifier> result = ImmutableSet.builder();
      collectBoundIdentifiers(expr, result);
      return result.build();
    }
  }

  private static void collectBoundIdentifiers(
      Expression lhs, ImmutableSet.Builder<Identifier> result) {
    if (lhs instanceof Identifier) {
      result.add((Identifier) lhs);
      return;
    }
    if (lhs instanceof ListExpression variables) {
      for (Expression expression : variables.getElements()) {
        collectBoundIdentifiers(expression, result);
      }
    }
  }
}
