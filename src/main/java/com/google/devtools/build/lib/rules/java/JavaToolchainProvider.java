// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.ProviderCollection;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

/** Information about the JDK used by the <code>java_*</code> rules. */
@Immutable
public final class JavaToolchainProvider extends StarlarkInfoWrapper {

  public static final StarlarkProviderWrapper<JavaToolchainProvider> RULES_JAVA_PROVIDER =
      new RulesJavaProvider();
  public static final StarlarkProviderWrapper<JavaToolchainProvider> WORKSPACE_PROVIDER =
      new WorkspaceProvider();
  public static final StarlarkProviderWrapper<JavaToolchainProvider> PROVIDER = new Provider();

  private JavaToolchainProvider(StarlarkInfo underlying) {
    super(underlying);
  }

  public static JavaToolchainProvider wrap(Info info) throws RuleErrorException {
    com.google.devtools.build.lib.packages.Provider.Key key = info.getProvider().getKey();
    if (key.equals(PROVIDER.getKey())) {
      return PROVIDER.wrap(info);
    } else if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
      return RULES_JAVA_PROVIDER.wrap(info);
    } else if (key.equals(WORKSPACE_PROVIDER.getKey())) {
      return WORKSPACE_PROVIDER.wrap(info);
    } else {
      throw new RuleErrorException("expected JavaToolchainInfo, got: " + key);
    }
  }

  @Override
  public int hashCode() {
    try {
      // StructImpl.hashcode() is too expensive, just the label should be enough
      return getToolchainLabel().hashCode();
    } catch (RuleErrorException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof JavaToolchainProvider)) {
      return false;
    }
    return underlying.equals(((JavaToolchainProvider) obj).underlying);
  }

  /** Returns the Java Toolchain associated with the rule being analyzed or {@code null}. */
  public static JavaToolchainProvider from(RuleContext ruleContext) {
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(
            ruleContext
                .getPrerequisite(JavaRuleClasses.JAVA_TOOLCHAIN_TYPE_ATTRIBUTE_NAME)
                .getLabel());
    return from(toolchainInfo, ruleContext);
  }

  @VisibleForTesting
  public static JavaToolchainProvider from(ProviderCollection collection) {
    ToolchainInfo toolchainInfo = collection.get(ToolchainInfo.PROVIDER);
    return from(toolchainInfo, null);
  }

  @Nullable
  private static JavaToolchainProvider from(
      ToolchainInfo toolchainInfo, @Nullable RuleErrorConsumer errorConsumer) {
    if (toolchainInfo != null) {
      try {
        JavaToolchainProvider provider =
            JavaToolchainProvider.wrap(toolchainInfo.getValue("java", Info.class));
        if (provider != null) {
          return provider;
        }
      } catch (EvalException | RuleErrorException e) {
        if (errorConsumer != null) {
          errorConsumer.ruleError(
              String.format("There was an error reading the Java toolchain: %s", e));
        }
      }
    }
    if (errorConsumer != null) {
      errorConsumer.ruleError("The selected Java toolchain is not a JavaToolchainProvider");
    }
    return null;
  }

  /** Returns the label for this {@code java_toolchain}. */
  public Label getToolchainLabel() throws RuleErrorException {
    return Preconditions.checkNotNull(getUnderlyingValue("label", Label.class));
  }

  /** Returns the target Java bootclasspath. */
  public BootClassPathInfo getBootclasspath() throws RuleErrorException {
    return BootClassPathInfo.wrap(getUnderlyingValue("_bootclasspath_info", Info.class));
  }

  /** Returns the {@link Artifact}s of compilation tools. */
  public NestedSet<Artifact> getTools() throws RuleErrorException {
    return getUnderlyingNestedSet("tools", Artifact.class);
  }

  /** Returns the {@link JavaToolchainTool} for JavaBuilder */
  public JavaToolchainTool getJavaBuilder() throws RuleErrorException {
    return JavaToolchainTool.fromStarlark(getUnderlyingValue("_javabuilder", StructImpl.class));
  }

  /** Returns the {@link JavaToolchainTool} for the header compiler */
  @Nullable
  public JavaToolchainTool getHeaderCompiler() throws RuleErrorException {
    return JavaToolchainTool.fromStarlark(getUnderlyingValue("_header_compiler", StructImpl.class));
  }

  /**
   * Returns the {@link FilesToRunProvider} of the Header Compiler deploy jar for direct-classpath,
   * non-annotation processing actions.
   */
  @Nullable
  public JavaToolchainTool getHeaderCompilerDirect() throws RuleErrorException {
    return JavaToolchainTool.fromStarlark(
        getUnderlyingValue("_header_compiler_direct", StructImpl.class));
  }

  @Nullable
  @VisibleForTesting
  public StructImpl getAndroidLint() throws RuleErrorException {
    return getUnderlyingValue("_android_linter", StructImpl.class);
  }

  @Nullable
  public JspecifyInfo jspecifyInfo() throws RuleErrorException {
    return JspecifyInfo.fromStarlark(getUnderlyingValue("_jspecify_info", StarlarkValue.class));
  }

  @Nullable
  public JavaToolchainTool getBytecodeOptimizer() throws RuleErrorException {
    return JavaToolchainTool.fromStarlark(
        getUnderlyingValue("_bytecode_optimizer", StructImpl.class));
  }

  public ImmutableList<Artifact> getLocalJavaOptimizationConfiguration() throws RuleErrorException {
    return getUnderlyingSequence("_local_java_optimization_config", Artifact.class)
        .getImmutableList();
  }

  /** Returns class names of annotation processors that are built in to the header compiler. */
  public ImmutableSet<String> getHeaderCompilerBuiltinProcessors() throws RuleErrorException {
    return getUnderlyingNestedSet("_header_compiler_builtin_processors", String.class).toSet();
  }

  public ImmutableSet<String> getReducedClasspathIncompatibleProcessors()
      throws RuleErrorException {
    return getUnderlyingNestedSet("_reduced_classpath_incompatible_processors", String.class)
        .toSet();
  }

  /** Returns the {@link FilesToRunProvider} of the SingleJar tool. */
  public FilesToRunProvider getSingleJar() throws RuleErrorException {
    return getUnderlyingValue("single_jar", FilesToRunProvider.class);
  }

  /** Returns the {@link Artifact} of the GenClass deploy jar */
  public Artifact getGenClass() throws RuleErrorException {
    return getUnderlyingValue("_gen_class", Artifact.class);
  }

  /**
   * Returns the {@link Artifact} of the latest timezone data resource jar that can be loaded by
   * Java 8 binaries.
   */
  @Nullable
  @VisibleForTesting
  public Artifact getTimezoneData() throws RuleErrorException {
    return getUnderlyingValue("_timezone_data", Artifact.class);
  }

  /** Returns the ijar executable */
  public FilesToRunProvider getIjar() throws RuleErrorException {
    return getUnderlyingValue("ijar", FilesToRunProvider.class);
  }

  /**
   * Returns the NestedSet of default options for the JVM running the java compiler and associated
   * tools.
   */
  public NestedSet<String> getJvmOptions() throws RuleErrorException {
    return getUnderlyingNestedSet("jvm_opt", String.class);
  }

  /** Returns whether JavaBuilders supports running as a persistent worker or not. */
  public boolean getJavacSupportsWorkers() throws RuleErrorException {
    return getUnderlyingValue("_javac_supports_workers", Boolean.class);
  }

  /** Returns whether JavaBuilders supports running persistent workers in multiplex mode */
  public boolean getJavacSupportsMultiplexWorkers() throws RuleErrorException {
    return getUnderlyingValue("_javac_supports_multiplex_workers", Boolean.class);
  }

  /** Returns whether JavaBuilders supports running persistent workers with cancellation */
  public boolean getJavacSupportsWorkerCancellation() throws RuleErrorException {
    return getUnderlyingValue("_javac_supports_worker_cancellation", Boolean.class);
  }

  /** Returns whether JavaBuilders supports running multiplex persistent workers in sandbox mode */
  public boolean getJavacSupportsWorkerMultiplexSandboxing() throws RuleErrorException {
    return getUnderlyingValue("_javac_supports_worker_multiplex_sandboxing", Boolean.class);
  }

  /** Returns the global {@code java_package_configuration} data. */
  public ImmutableList<JavaPackageConfigurationProvider> packageConfiguration()
      throws RuleErrorException {
    return JavaPackageConfigurationProvider.wrapSequence(
        getUnderlyingSequence("_package_configuration", StructImpl.class));
  }

  public FilesToRunProvider getJacocoRunner() throws RuleErrorException {
    return getUnderlyingValue("jacocorunner", FilesToRunProvider.class);
  }

  public JavaRuntimeInfo getJavaRuntime() throws RuleErrorException {
    return JavaRuntimeInfo.wrap(getUnderlyingValue("java_runtime", Info.class));
  }

  record JspecifyInfo(
      JavaPluginData jspecifyProcessor,
      NestedSet<Artifact> jspecifyImplicitDeps,
      ImmutableList<String> jspecifyJavacopts,
      ImmutableList<PackageSpecificationProvider> jspecifyPackages) {
    JspecifyInfo {
      requireNonNull(jspecifyProcessor, "jspecifyProcessor");
      requireNonNull(jspecifyImplicitDeps, "jspecifyImplicitDeps");
      requireNonNull(jspecifyJavacopts, "jspecifyJavacopts");
      requireNonNull(jspecifyPackages, "jspecifyPackages");
    }

    boolean matches(Label label) {
      for (PackageSpecificationProvider provider : jspecifyPackages()) {
        for (PackageGroupContents specifications : provider.getPackageSpecifications().toList()) {
          if (specifications.containsPackage(label.getPackageIdentifier())) {
            return true;
          }
        }
      }
      return false;
    }

    @Nullable
    static JspecifyInfo fromStarlark(@Nullable StarlarkValue value) throws RuleErrorException {
      if (value == null || value == Starlark.NONE) {
        return null;
      } else if (value instanceof StructImpl struct) {
        try {
          return new JspecifyInfo(
              JavaPluginData.wrap(struct.getValue("processor")),
              Depset.noneableCast(
                  struct.getValue("implicit_deps"), Artifact.class, "implicit_deps"),
              Sequence.noneableCast(struct.getValue("javacopts"), String.class, "javacopts")
                  .getImmutableList(),
              Sequence.noneableCast(
                      struct.getValue("packages"), PackageSpecificationProvider.class, "packages")
                  .getImmutableList());
        } catch (EvalException e) {
          throw new RuleErrorException(e);
        }
      } else {
        throw new RuleErrorException("expected JspecifyInfo, got: " + Starlark.type(value));
      }
    }
  }

  private static class RulesJavaProvider extends Provider {
    private RulesJavaProvider() {
      super(keyForBuild(Label.parseCanonicalUnchecked("//java/common/rules:java_toolchain.bzl")));
    }
  }

  private static class WorkspaceProvider extends Provider {
    private WorkspaceProvider() {
      super(
          keyForBuild(
              Label.parseCanonicalUnchecked("@@rules_java//java/common/rules:java_toolchain.bzl")));
    }
  }

  private static class Provider extends StarlarkProviderWrapper<JavaToolchainProvider> {
    private Provider() {
      this(
          keyForBuild(
              Label.parseCanonicalUnchecked(
                  JavaSemantics.RULES_JAVA_PROVIDER_LABELS_PREFIX
                      + "java/common/rules:java_toolchain.bzl")));
    }

    private Provider(BzlLoadValue.Key key) {
      super(key, "JavaToolchainInfo");
    }

    @Override
    public JavaToolchainProvider wrap(Info value) throws RuleErrorException {
      if (value instanceof StarlarkInfoWithSchema
          && value.getProvider().getKey().equals(getKey())) {
        return new JavaToolchainProvider((StarlarkInfo) value);
      } else {
        throw new RuleErrorException(
            "got value of type '" + Starlark.type(value) + "', want 'JavaToolchainInfo'");
      }
    }
  }
}
