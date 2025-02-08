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

package com.google.devtools.build.lib.cmdline;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/**
 * BazelModuleContext records Bazel-specific information associated with a .bzl {@link
 * net.starlark.java.eval.Module}.
 *
 * <p>Maintainer's note: This object is determined prior to the module's evaluation in
 * BzlLoadFunction. It is saved in the {@code Module} as {@link Module#getClientData client data}.
 * The {@code Module} used during .bzl compilation is separate and uses {@link BazelCompileContext}
 * as client data. For data that is computed after the module's evaluation and which need not be
 * exposed outside the module-loading machinery, consider {@link BzlLoadValue}.
 */
// Immutability is useful because this object is retrievable from a Module and therefore from a
// BzlLoadValue.
@AutoValue
public abstract class BazelModuleContext {
  /** Label associated with the Starlark {@link net.starlark.java.eval.Module}. */
  public final Label label() {
    return key().getLabel();
  }

  /** {@link com.google.devtools.build.lib.skyframe.BzlLoadValue.Key} used to create the module. */
  public abstract BazelModuleKey key();

  /** The repository mapping applicable to the repo where the .bzl file is located in. */
  public abstract RepositoryMapping repoMapping();

  /** Returns the name of the module's .bzl file, as provided to the parser. */
  public abstract String filename();

  /**
   * Returns a list of modules loaded by this .bzl file, in source order.
   *
   * <p>By traversing these modules' loads, it is possible to reconstruct the complete load DAG (not
   * including {@code @_builtins} .bzl files). See {@link #visitLoadGraphRecursively}.
   */
  public abstract ImmutableList<Module> loads();

  /**
   * Consumes labels of loaded Starlark files during a call to {@link #visitLoadGraphRecursively}.
   *
   * <p>The value returned by {@link #visit} determines whether the traversal should continue (true)
   * or backtrack (false). Using a method reference to {@link Set#add} is a convenient way to
   * aggregate Starlark files while pruning branches when a file was already seen. The same set may
   * be reused across multiple calls to {@link #visitLoadGraphRecursively} in order to prune the
   * graph at files already seen during a previous traversal.
   */
  @FunctionalInterface
  public interface LoadGraphVisitor<E1 extends Exception, E2 extends Exception> {
    /**
     * Processes a single loaded Starlark file and determines whether to recurse into that file's
     * loads.
     *
     * @return true if the visitation should recurse into the loads of the given file
     */
    @CanIgnoreReturnValue
    boolean visit(Label load) throws E1, E2;
  }

  /** Performs an online visitation of the load graph rooted at a given list of loads. */
  public static <E1 extends Exception, E2 extends Exception> void visitLoadGraphRecursively(
      Iterable<Module> loads, LoadGraphVisitor<E1, E2> visitor) throws E1, E2 {
    for (Module module : loads) {
      BazelModuleContext ctx = BazelModuleContext.of(module);
      if (visitor.visit(ctx.label())) {
        visitLoadGraphRecursively(ctx.loads(), visitor);
      }
    }
  }

  /**
   * Transitive digest of the .bzl file of the {@link net.starlark.java.eval.Module} itself and all
   * files it transitively loads.
   */
  @SuppressWarnings({"AutoValueImmutableFields", "mutable"})
  @AutoValue.CopyAnnotations
  public abstract byte[] bzlTransitiveDigest();

  /**
   * Returns a label for a {@link net.starlark.java.eval.Module}.
   *
   * <p>This is a user-facing value and we rely on this string to be a valid label for the {@link
   * net.starlark.java.eval.Module} (and that only).
   */
  @Override
  public final String toString() {
    return label().toString();
  }

  /**
   * Returns the {@code BazelModuleContext} associated with the specified Starlark module, or null
   * if there isn't any.
   */
  @Nullable
  public static BazelModuleContext of(Module m) {
    @Nullable Object data = m.getClientData();
    if (data instanceof BazelModuleContext bazelModuleContext) {
      return bazelModuleContext;
    } else {
      return null;
    }
  }

  /**
   * Returns the {@code BazelModuleContext} associated with the innermost Starlark function on the
   * call stack of the given thread.
   *
   * <p>Usage note: Following the example of {@link Module#ofInnermostEnclosingStarlarkFunction},
   * the name of this method is intentionally clumsy to remind the reader that introspecting the
   * current module is a dubious practice. We went with a different name here because the null
   * tolerance of the two methods differs.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module has no {@code BazelModuleContext}.
   */
  public static BazelModuleContext ofInnermostBzlOrThrow(StarlarkThread thread) {
    Module m = Preconditions.checkNotNull(Module.ofInnermostEnclosingStarlarkFunction(thread));
    return Preconditions.checkNotNull(of(m));
  }

  /**
   * Returns the {@code BazelModuleContext} associated with the innermost Starlark function on the
   * call stack of the given thread. If not present, throws {@code EvalException} with an error
   * message indicating that {@code what} can't be used in this Starlark environment.
   */
  @CanIgnoreReturnValue
  public static BazelModuleContext ofInnermostBzlOrFail(StarlarkThread thread, String what)
      throws EvalException {
    BazelModuleContext ctx = null;
    Module m = Module.ofInnermostEnclosingStarlarkFunction(thread);
    if (m != null) {
      ctx = of(m);
    }
    if (ctx == null) {
      throw Starlark.errorf(
          "%s can only be used during .bzl initialization (top-level evaluation)", what);
    }
    return ctx;
  }

  public static BazelModuleContext create(
      BazelModuleKey key,
      RepositoryMapping repoMapping,
      String filename,
      ImmutableList<Module> loads,
      byte[] bzlTransitiveDigest) {
    return new AutoValue_BazelModuleContext(key, repoMapping, filename, loads, bzlTransitiveDigest);
  }

  public final Label.PackageContext packageContext() {
    return Label.PackageContext.of(label().getPackageIdentifier(), repoMapping());
  }
}
