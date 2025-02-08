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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.rules.java.JavaInfo.nullIfNone;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaInfo.JavaInfoInternalProvider;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.JavaOutput;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaOutputApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaRuleOutputJarsProviderApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

/** Provides information about jar files produced by a Java rule. */
@Immutable
@AutoCodec
public record JavaRuleOutputJarsProvider(@Override ImmutableList<JavaOutput> javaOutputs)
    implements JavaInfoInternalProvider, JavaRuleOutputJarsProviderApi<JavaOutput> {
  public JavaRuleOutputJarsProvider {
    requireNonNull(javaOutputs, "javaOutputs");
  }

  @Override
  public ImmutableList<JavaOutput> getJavaOutputs() {
    return javaOutputs();
  }

  @SerializationConstant
  public static final JavaRuleOutputJarsProvider EMPTY =
      new JavaRuleOutputJarsProvider(ImmutableList.<JavaOutput>of());

  /**
   * A collection of artifacts associated with a jar output.
   *
   * @param sourceJars A {@link NestedSet} of sources archive files.
   */
  @Immutable
  @AutoCodec
  public record JavaOutput(
      @Override Artifact classJar,
      @Nullable @Override Artifact compileJar,
      @Nullable @Override Artifact compileJdeps,
      @Nullable @Override Artifact generatedClassJar,
      @Nullable @Override Artifact generatedSourceJar,
      @Nullable @Override Artifact nativeHeadersJar,
      @Nullable @Override Artifact manifestProto,
      @Nullable @Override Artifact jdeps,
      NestedSet<Artifact> sourceJars)
      implements JavaOutputApi<Artifact> {
    public JavaOutput {
      requireNonNull(classJar, "classJar");
      requireNonNull(sourceJars, "sourceJars");
    }

    @Override
    public Artifact getClassJar() {
      return classJar();
    }

    @Nullable
    @Override
    public Artifact getCompileJar() {
      return compileJar();
    }

    @Nullable
    @Override
    public Artifact getCompileJdeps() {
      return compileJdeps();
    }

    @Nullable
    @Override
    public Artifact getGeneratedClassJar() {
      return generatedClassJar();
    }

    @Nullable
    @Override
    public Artifact getGeneratedSourceJar() {
      return generatedSourceJar();
    }

    @Nullable
    @Override
    public Artifact getNativeHeadersJar() {
      return nativeHeadersJar();
    }

    @Nullable
    @Override
    public Artifact getManifestProto() {
      return manifestProto();
    }

    @Nullable
    @Override
    public Artifact getJdeps() {
      return jdeps();
    }

    /**
     * Translates a collection of {@link JavaOutput} for use in native code.
     *
     * @param outputs the collection of translate
     * @return an immutable list of {@link JavaOutput} instances
     * @throws EvalException if there were errors reading fields from the {@code Starlark} object
     * @throws RuleErrorException if any item in the supplied collection is not a valid {@link
     *     JavaOutput}
     */
    @VisibleForTesting
    public static ImmutableList<JavaOutput> wrapSequence(Collection<?> outputs)
        throws EvalException, RuleErrorException {
      ImmutableList.Builder<JavaOutput> result = ImmutableList.builder();
      for (Object info : outputs) {
        if (info instanceof JavaOutput javaOutput) {
          result.add(javaOutput);
        } else if (info instanceof StructImpl structImpl) {
          result.add(fromStarlarkJavaOutput(structImpl));
        } else {
          throw new RuleErrorException("expected JavaOutput, got: " + Starlark.type(info));
        }
      }
      return result.build();
    }

    @Override
    public boolean isImmutable() {
      return true; // immutable and Starlark-hashable
    }

    @Nullable
    @Deprecated
    @Override
    public Artifact getIJar() {
      return compileJar();
    }

    @Nullable
    @Deprecated
    @Override
    public Artifact getSrcJar() {
      return Iterables.getOnlyElement(getSourceJarsAsList(), null);
    }

    public ImmutableList<Artifact> getSourceJarsAsList() {
      return sourceJars().toList();
    }

    @Nullable
    @Override
    public Depset getSrcJarsStarlark(StarlarkSemantics semantics) {
      return Depset.of(Artifact.class, sourceJars());
    }

    public static JavaOutput fromStarlarkJavaOutput(StructImpl struct) throws EvalException {
      NestedSet<Artifact> sourceJars;
      Object starlarkSourceJars = struct.getValue("source_jars");
      if (starlarkSourceJars == Starlark.NONE || starlarkSourceJars instanceof Depset) {
        sourceJars = Depset.noneableCast(starlarkSourceJars, Artifact.class, "source_jars");
      } else {
        sourceJars =
            NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                Sequence.cast(starlarkSourceJars, Artifact.class, "source_jars"));
      }
      return JavaOutput.builder()
          .setClassJar(nullIfNone(struct.getValue("class_jar"), Artifact.class))
          .setCompileJar(nullIfNone(struct.getValue("compile_jar"), Artifact.class))
          .setCompileJdeps(nullIfNone(struct.getValue("compile_jdeps"), Artifact.class))
          .setGeneratedClassJar(nullIfNone(struct.getValue("generated_class_jar"), Artifact.class))
          .setGeneratedSourceJar(
              nullIfNone(struct.getValue("generated_source_jar"), Artifact.class))
          .setNativeHeadersJar(nullIfNone(struct.getValue("native_headers_jar"), Artifact.class))
          .setManifestProto(nullIfNone(struct.getValue("manifest_proto"), Artifact.class))
          .setJdeps(nullIfNone(struct.getValue("jdeps"), Artifact.class))
          .addSourceJars(sourceJars)
          .build();
    }

    /** Builder for OutputJar. */
    @AutoBuilder
    public abstract static class Builder {
      private final NestedSetBuilder<Artifact> sourceJarsBuilder = NestedSetBuilder.stableOrder();

      public abstract Builder setClassJar(Artifact value);

      public abstract Builder setCompileJar(Artifact value);

      public abstract Builder setCompileJdeps(Artifact value);

      public abstract Builder setGeneratedClassJar(Artifact value);

      public abstract Builder setGeneratedSourceJar(Artifact value);

      public abstract Builder setNativeHeadersJar(Artifact value);

      public abstract Builder setManifestProto(Artifact value);

      public abstract Builder setJdeps(Artifact value);

      @CanIgnoreReturnValue
      abstract Builder setSourceJars(NestedSet<Artifact> value);

      public Builder addSourceJar(@Nullable Artifact value) {
        if (value != null) {
          sourceJarsBuilder.add(value);
        }
        return this;
      }

      public Builder addSourceJars(NestedSet<Artifact> values) {
        sourceJarsBuilder.addTransitive(values);
        return this;
      }

      /** Populates the builder with outputs from {@link JavaCompileOutputs}. */
      public Builder fromJavaCompileOutputs(JavaCompileOutputs<Artifact> value) {
        return fromJavaCompileOutputs(value, true);
      }

      @CanIgnoreReturnValue
      public Builder fromJavaCompileOutputs(
          JavaCompileOutputs<Artifact> value, boolean includeJdeps) {
        setClassJar(value.output());
        if (includeJdeps) {
          setJdeps(value.depsProto());
        }
        setGeneratedClassJar(value.genClass());
        setGeneratedSourceJar(value.genSource());
        setNativeHeadersJar(value.nativeHeader());
        setManifestProto(value.manifestProto());
        return this;
      }

      abstract JavaOutput autoBuild();

      public JavaOutput build() {
        setSourceJars(sourceJarsBuilder.build());
        return autoBuild();
      }
    }

    public static Builder builder() {
      return new AutoBuilder_JavaRuleOutputJarsProvider_JavaOutput_Builder();
    }
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  /** Collects all class output jars from {@link #getJavaOutputs} */
  public Iterable<Artifact> getAllClassOutputJars() {
    return javaOutputs().stream().map(JavaOutput::classJar).collect(Collectors.toList());
  }

  /** Collects all source output jars from {@link #getJavaOutputs} */
  public ImmutableList<Artifact> getAllSrcOutputJars() {
    return javaOutputs().stream()
        .map(JavaOutput::getSourceJarsAsList)
        .flatMap(ImmutableList::stream)
        .collect(toImmutableList());
  }

  @Nullable
  @Override
  @Deprecated
  public Artifact getJdeps() {
    ImmutableList<Artifact> jdeps =
        javaOutputs().stream()
            .map(JavaOutput::jdeps)
            .filter(Objects::nonNull)
            .collect(toImmutableList());
    return jdeps.size() == 1 ? jdeps.get(0) : null;
  }

  @Nullable
  @Override
  @Deprecated
  public Artifact getNativeHeaders() {
    ImmutableList<Artifact> nativeHeaders =
        javaOutputs().stream()
            .map(JavaOutput::nativeHeadersJar)
            .filter(Objects::nonNull)
            .collect(toImmutableList());
    return nativeHeaders.size() == 1 ? nativeHeaders.get(0) : null;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link JavaRuleOutputJarsProvider}. */
  public static class Builder {
    // CompactHashSet preserves insertion order here since we never perform any removals
    private final CompactHashSet<JavaOutput> javaOutputs = CompactHashSet.create();

    @CanIgnoreReturnValue
    public Builder addJavaOutput(JavaOutput javaOutput) {
      javaOutputs.add(javaOutput);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addJavaOutput(Collection<JavaOutput> javaOutputs) {
      this.javaOutputs.addAll(javaOutputs);
      return this;
    }

    public JavaRuleOutputJarsProvider build() {
      return new JavaRuleOutputJarsProvider(ImmutableList.copyOf(javaOutputs));
    }
  }

  /**
   * Translates the {@code outputs} field of a {@link JavaInfo} instance into a native {@link
   * JavaRuleOutputJarsProvider} instance.
   *
   * <p>This method first attempts to transform the {@code outputs} field of the supplied {@code
   * JavaInfo}. If this is not present (for example, in Bazel), it attempts to create the result
   * from the {@code java_outputs} field instead.
   *
   * @param javaInfo the {@link JavaInfo} instance
   * @return a {@link JavaRuleOutputJarsProvider} instance
   * @throws EvalException if there are any errors accessing Starlark values
   * @throws RuleErrorException if any of the {@code output} instances are of incompatible type
   */
  static JavaRuleOutputJarsProvider fromStarlarkJavaInfo(StructImpl javaInfo)
      throws EvalException, RuleErrorException {
    Object outputs = javaInfo.getValue("outputs");
    if (outputs == null) {
      return JavaRuleOutputJarsProvider.builder()
          .addJavaOutput(
              JavaOutput.wrapSequence(
                  Sequence.cast(javaInfo.getValue("java_outputs"), Objects.class, "java_outputs")))
          .build();
    } else {
      return fromStarlark(outputs);
    }
  }

  /**
   * Translates the supplied object into a {@link JavaRuleOutputJarsProvider} instance.
   *
   * @param obj the object to translate
   * @return a {@link JavaRuleOutputJarsProvider} instance, or null if the supplied object was null
   *     or {@link Starlark#NONE}
   * @throws EvalException if there were any errors reading fields from the supplied object
   * @throws RuleErrorException if the supplied object is not a {@link JavaRuleOutputJarsProvider}
   */
  @VisibleForTesting
  public static JavaRuleOutputJarsProvider fromStarlark(Object obj)
      throws EvalException, RuleErrorException {
    if (obj == Starlark.NONE) {
      return JavaRuleOutputJarsProvider.EMPTY;
    } else if (obj instanceof JavaRuleOutputJarsProvider javaRuleOutputJarsProvider) {
      return javaRuleOutputJarsProvider;
    } else if (obj instanceof StructImpl) {
      return JavaRuleOutputJarsProvider.builder()
          .addJavaOutput(
              JavaOutput.wrapSequence(
                  Sequence.cast(((StructImpl) obj).getValue("jars"), Object.class, "jars")))
          .build();
    } else {
      throw new RuleErrorException(
          "expected JavaRuleOutputJarsProvider, got: " + Starlark.type(obj));
    }
  }
}
