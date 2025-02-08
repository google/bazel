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

import static com.google.devtools.build.lib.rules.java.JavaInfo.nullIfNone;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaInfo.JavaInfoInternalProvider;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaAnnotationProcessingApi;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;

/** The collection of gen jars from the transitive closure. */
public interface JavaGenJarsProvider
    extends JavaInfoInternalProvider, JavaAnnotationProcessingApi<Artifact> {

  JavaGenJarsProvider EMPTY =
      new NativeJavaGenJarsProvider(
          false,
          null,
          null,
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          NestedSetBuilder.emptySet(Order.STABLE_ORDER));

  static JavaGenJarsProvider create(
      boolean usesAnnotationProcessing,
      @Nullable Artifact genClassJar,
      @Nullable Artifact genSourceJar,
      JavaPluginInfo plugins,
      List<JavaGenJarsProvider> transitiveJavaGenJars)
      throws RuleErrorException {
    if (!usesAnnotationProcessing
        && genClassJar == null
        && genSourceJar == null
        && plugins.isEmpty()
        && transitiveJavaGenJars.isEmpty()) {
      return EMPTY;
    }

    NestedSetBuilder<Artifact> classJarsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> sourceJarsBuilder = NestedSetBuilder.stableOrder();

    if (genClassJar != null) {
      classJarsBuilder.add(genClassJar);
    }
    if (genSourceJar != null) {
      sourceJarsBuilder.add(genSourceJar);
    }
    for (JavaGenJarsProvider dep : transitiveJavaGenJars) {
      classJarsBuilder.addTransitive(dep.getTransitiveGenClassJars());
      sourceJarsBuilder.addTransitive(dep.getTransitiveGenSourceJars());
    }
    return new NativeJavaGenJarsProvider(
        usesAnnotationProcessing,
        genClassJar,
        genSourceJar,
        plugins.plugins().processorClasspath(),
        plugins.plugins().processorClasses(),
        classJarsBuilder.build(),
        sourceJarsBuilder.build());
  }

  static JavaGenJarsProvider from(Object obj) throws EvalException {
    if (obj == null || obj == Starlark.NONE) {
      return EMPTY;
    } else if (obj instanceof JavaGenJarsProvider javaGenJarsProvider) {
      return javaGenJarsProvider;
    } else if (obj instanceof StructImpl struct) {
      return new NativeJavaGenJarsProvider(
          struct.getValue("enabled", Boolean.class),
          nullIfNone(struct.getValue("class_jar"), Artifact.class),
          nullIfNone(struct.getValue("source_jar"), Artifact.class),
          Depset.cast(
              struct.getValue("processor_classpath"), Artifact.class, "processor_classpath"),
          NestedSetBuilder.wrap(
              Order.NAIVE_LINK_ORDER,
              Sequence.cast(
                  struct.getValue("processor_classnames"), String.class, "processor_classnames")),
          Depset.cast(
              struct.getValue("transitive_class_jars"), Artifact.class, "transitive_class_jars"),
          Depset.cast(
              struct.getValue("transitive_source_jars"), Artifact.class, "transitive_source_jars"));
    }
    throw Starlark.errorf("wanted JavaGenJarsProvider, got %s", Starlark.type(obj));
  }

  default boolean isEmpty() throws EvalException, RuleErrorException {
    return !usesAnnotationProcessing()
        && getGenClassJar() == null
        && getGenSourceJar() == null
        && getTransitiveGenClassJars().isEmpty()
        && getTransitiveGenSourceJars().isEmpty();
  }

  NestedSet<Artifact> getTransitiveGenClassJars() throws RuleErrorException;

  NestedSet<Artifact> getTransitiveGenSourceJars() throws RuleErrorException;

  NestedSet<Artifact> getProcessorClasspath() throws EvalException;

  /** Natively constructed JavaGenJarsProvider */
  @Immutable
  @AutoCodec
  public record NativeJavaGenJarsProvider(
      @Override boolean usesAnnotationProcessing,
      @Nullable @Override Artifact getGenClassJar,
      @Nullable @Override Artifact getGenSourceJar,
      @Override NestedSet<Artifact> getProcessorClasspath,
      NestedSet<String> getProcessorClassnames,
      @Override NestedSet<Artifact> getTransitiveGenClassJars,
      @Override NestedSet<Artifact> getTransitiveGenSourceJars)
      implements JavaGenJarsProvider {
    public NativeJavaGenJarsProvider {
      requireNonNull(getProcessorClasspath, "getProcessorClasspath");
      requireNonNull(getProcessorClassnames, "getProcessorClassnames");
      requireNonNull(getTransitiveGenClassJars, "getTransitiveGenClassJars");
      requireNonNull(getTransitiveGenSourceJars, "getTransitiveGenSourceJars");
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public Depset /*<Artifact>*/ getTransitiveGenClassJarsForStarlark() {
      return Depset.of(Artifact.class, getTransitiveGenClassJars());
    }

    @Override
    public Depset /*<Artifact>*/ getTransitiveGenSourceJarsForStarlark() {
      return Depset.of(Artifact.class, getTransitiveGenSourceJars());
    }

    @Override
    public Depset /*<Artifact>*/ getProcessorClasspathForStarlark() {
      return Depset.of(Artifact.class, getProcessorClasspath());
    }

    @Override
    public ImmutableList<String> getProcessorClassNamesList() {
      return getProcessorClassnames().toList();
    }
  }
}
