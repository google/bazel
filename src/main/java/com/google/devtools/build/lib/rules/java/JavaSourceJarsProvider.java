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

package com.google.devtools.build.lib.rules.java;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaInfo.JavaInfoInternalProvider;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

/**
 * The collection of source jars from the transitive closure.
 *
 * @param transitiveSourceJars Returns all the source jars in the transitive closure, that can be
 *     reached by a chain of JavaSourceJarsProvider instances.
 * @param sourceJars Return the source jars that are to be built when the target is on the command
 *     line.
 */
@Immutable
@AutoCodec
public record JavaSourceJarsProvider(
    NestedSet<Artifact> transitiveSourceJars, ImmutableList<Artifact> sourceJars)
    implements JavaInfoInternalProvider {
  public JavaSourceJarsProvider {
    requireNonNull(transitiveSourceJars, "transitiveSourceJars");
    requireNonNull(sourceJars, "sourceJars");
  }

  @SerializationConstant
  public static final JavaSourceJarsProvider EMPTY =
      create(NestedSetBuilder.emptySet(Order.STABLE_ORDER), ImmutableList.of());

  public static JavaSourceJarsProvider create(
      NestedSet<Artifact> transitiveSourceJars, Iterable<Artifact> sourceJars) {
    return new JavaSourceJarsProvider(transitiveSourceJars, ImmutableList.copyOf(sourceJars));
  }

  /** Returns a builder for a {@link JavaSourceJarsProvider}. */
  public static Builder builder() {
    return new Builder();
  }

  /** A builder for {@link JavaSourceJarsProvider}. */
  public static final class Builder {

    // CompactHashSet preserves insertion order here since we never perform any removals
    private final CompactHashSet<Artifact> sourceJars = CompactHashSet.create();
    private final NestedSetBuilder<Artifact> transitiveSourceJars = NestedSetBuilder.stableOrder();

    /** Add a source jar that is to be built when the target is on the command line. */
    @CanIgnoreReturnValue
    public Builder addSourceJar(Artifact sourceJar) {
      sourceJars.add(Preconditions.checkNotNull(sourceJar));
      return this;
    }

    /** Add source jars to be built when the target is on the command line. */
    @CanIgnoreReturnValue
    public Builder addAllSourceJars(Collection<Artifact> sourceJars) {
      this.sourceJars.addAll(Preconditions.checkNotNull(sourceJars));
      return this;
    }

    /**
     * Add a source jar in the transitive closure, that can be reached by a chain of
     * JavaSourceJarsProvider instances.
     */
    @CanIgnoreReturnValue
    public Builder addTransitiveSourceJar(Artifact transitiveSourceJar) {
      transitiveSourceJars.add(Preconditions.checkNotNull(transitiveSourceJar));
      return this;
    }

    /**
     * Add source jars in the transitive closure, that can be reached by a chain of
     * JavaSourceJarsProvider instances.
     */
    @CanIgnoreReturnValue
    public Builder addAllTransitiveSourceJars(NestedSet<Artifact> transitiveSourceJars) {
      this.transitiveSourceJars.addTransitive(Preconditions.checkNotNull(transitiveSourceJars));
      return this;
    }

    public JavaSourceJarsProvider build() {
      return JavaSourceJarsProvider.create(
          transitiveSourceJars.build(), ImmutableList.copyOf(sourceJars));
    }
  }

  static JavaSourceJarsProvider fromStarlarkJavaInfo(StructImpl javaInfo)
      throws EvalException, TypeException {
    return JavaSourceJarsProvider.create(
        javaInfo.getValue("transitive_source_jars", Depset.class).getSet(Artifact.class),
        Sequence.cast(
            javaInfo.getValue("source_jars", StarlarkList.class), Artifact.class, "source_jars"));
  }
}
