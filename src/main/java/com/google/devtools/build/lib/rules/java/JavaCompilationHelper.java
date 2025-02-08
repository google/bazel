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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.LazyWritePathsFileAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
import com.google.devtools.build.lib.rules.java.JavaToolchainProvider.JspecifyInfo;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A helper class for compiling Java targets. It contains method to create the various intermediate
 * Artifacts for using ijars and source ijars.
 *
 * <p>Also supports the creation of resource and source only Jars.
 */
public final class JavaCompilationHelper {

  private static final Interner<ImmutableList<String>> javacOptsInterner =
      BlazeInterners.newWeakInterner();
  private static final Interner<ImmutableMap<String, String>> executionInfoInterner =
      BlazeInterners.newWeakInterner();

  private final RuleContext ruleContext;
  private final JavaToolchainProvider javaToolchain;
  private final JavaTargetAttributes.Builder attributes;
  private JavaTargetAttributes builtAttributes;
  private final ImmutableList<String> customJavacOpts;
  private NestedSet<String> javaBuilderJvmFlags = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private final JavaSemantics semantics;
  private final ImmutableList<Artifact> additionalInputsForDatabinding;
  private boolean enableJspecify = true;
  private boolean enableDirectClasspath = true;
  private final String execGroup;

  public JavaCompilationHelper(
      RuleContext ruleContext,
      JavaSemantics semantics,
      ImmutableList<String> javacOpts,
      JavaTargetAttributes.Builder attributes,
      JavaToolchainProvider javaToolchainProvider,
      ImmutableList<Artifact> additionalInputsForDatabinding) {
    this.ruleContext = ruleContext;
    this.javaToolchain = Preconditions.checkNotNull(javaToolchainProvider);
    this.attributes = attributes;
    this.customJavacOpts = javacOptsInterner.intern(javacOpts);
    this.semantics = semantics;
    this.additionalInputsForDatabinding = additionalInputsForDatabinding;

    if (ruleContext.useAutoExecGroups()) {
      this.execGroup = semantics.getJavaToolchainType();
    } else {
      this.execGroup = DEFAULT_EXEC_GROUP_NAME;
    }
  }

  public void javaBuilderJvmFlags(NestedSet<String> javaBuilderJvmFlags) {
    this.javaBuilderJvmFlags = javaBuilderJvmFlags;
  }

  public void enableJspecify(boolean enableJspecify) {
    this.enableJspecify = enableJspecify;
  }

  JavaTargetAttributes getAttributes() {
    if (builtAttributes == null) {
      builtAttributes = attributes.build();
    }
    return builtAttributes;
  }

  public void enableDirectClasspath(boolean enableDirectClasspath) {
    this.enableDirectClasspath = enableDirectClasspath;
  }

  public RuleContext getRuleContext() {
    return ruleContext;
  }

  private AnalysisEnvironment getAnalysisEnvironment() {
    return ruleContext.getAnalysisEnvironment();
  }

  private BuildConfigurationValue getConfiguration() {
    return ruleContext.getConfiguration();
  }

  private JavaConfiguration getJavaConfiguration() {
    return ruleContext.getFragment(JavaConfiguration.class);
  }

  public void createCompileAction(JavaCompileOutputs<Artifact> outputs)
      throws RuleErrorException, InterruptedException {
    if (outputs.genClass() != null) {
      createGenJarAction(
          outputs.output(),
          outputs.manifestProto(),
          outputs.genClass(),
          javaToolchain.getJavaRuntime());
    }

    JavaTargetAttributes attributes = getAttributes();

    JspecifyInfo jspecifyInfo = javaToolchain.jspecifyInfo();
    boolean jspecify =
        enableJspecify
            && getJavaConfiguration().experimentalEnableJspecify()
            && jspecifyInfo != null
            && jspecifyInfo.matches(ruleContext.getLabel());
    if (jspecify) {
      // JSpecify requires these on the compile-time classpath; see b/187113128
      // Add them as non-direct deps (for the purposes of Strict Java Deps) to still require an
      // explicit dep if they're directly used by the compiled source.
      attributes =
          attributes.appendAdditionalTransitiveClassPathEntries(
              jspecifyInfo.jspecifyImplicitDeps());
    }

    ImmutableList<Artifact> sourceJars = attributes.getSourceJars();
    JavaPluginData plugins = attributes.plugins().plugins();
    List<Artifact> resourceJars = new ArrayList<>();

    boolean turbineAnnotationProcessing =
        usesAnnotationProcessing()
            && getJavaConfiguration().experimentalTurbineAnnotationProcessing();
    if (turbineAnnotationProcessing) {
      Artifact turbineResources = turbineOutput(outputs.output(), "-turbine-resources.jar");
      resourceJars.add(turbineResources);
      Artifact turbineJar = turbineOutput(outputs.output(), "-turbine-apt.jar");
      Artifact turbineJdeps = turbineOutput(outputs.output(), "-turbine-apt.jdeps");
      Artifact turbineGensrc =
          outputs.genSource() != null
              ? outputs.genSource()
              : turbineOutput(outputs.output(), "-turbine-apt-gensrc.jar");

      JavaHeaderCompileAction.Builder builder = getJavaHeaderCompileActionBuilder();
      builder.setOutputJar(turbineJar);
      builder.setOutputDepsProto(turbineJdeps);
      builder.setPlugins(plugins);
      builder.setResourceOutputJar(turbineResources);
      builder.setGensrcOutputJar(turbineGensrc);
      builder.setManifestOutput(outputs.manifestProto());
      builder.setAdditionalOutputs(attributes.getAdditionalOutputs());
      // TODO(cushon): GraalVM/native-image doesn't support service-loading for Dagger SPI plugins
      builder.enableHeaderCompilerDirect(false);
      builder.build(javaToolchain);

      // The sources generated by the turbine annotation processing action are added to the list of
      // source jars passed to JavaBuilder.
      sourceJars =
          ImmutableList.copyOf(Iterables.concat(sourceJars, ImmutableList.of(turbineGensrc)));
    }

    if (separateResourceJar(resourceJars, attributes)) {
      Artifact originalOutput = outputs.output();
      outputs =
          outputs.withOutput(
              ruleContext.getDerivedArtifact(
                  FileSystemUtils.appendWithoutExtension(
                      outputs
                          .output()
                          .getOutputDirRelativePath(getConfiguration().isSiblingRepositoryLayout()),
                      "-class"),
                  outputs.output().getRoot()));
      resourceJars.add(outputs.output());
      createResourceJarAction(originalOutput, ImmutableList.copyOf(resourceJars));
    }

    Artifact optimizedJar = null;
    if (getJavaConfiguration().runLocalJavaOptimizations()) {
      optimizedJar = outputs.output();
      outputs =
          outputs.withOutput(
              ruleContext.getDerivedArtifact(
                  FileSystemUtils.replaceExtension(
                      outputs
                          .output()
                          .getOutputDirRelativePath(getConfiguration().isSiblingRepositoryLayout()),
                      "-pre-optimization.jar"),
                  outputs.output().getRoot()));
    }

    ImmutableList<String> javacopts = customJavacOpts;
    if (jspecify) {
      plugins =
          JavaPluginInfo.JavaPluginData.merge(
              ImmutableList.of(plugins, jspecifyInfo.jspecifyProcessor()));
      var jspecifyOpts = jspecifyInfo.jspecifyJavacopts();
      javacopts =
          javacOptsInterner.intern(
              ImmutableList.<String>builderWithExpectedSize(javacopts.size() + jspecifyOpts.size())
                  .addAll(javacopts)
                  // Add JSpecify options last to discourage overriding them, at least for now.
                  .addAll(jspecifyOpts)
                  .build());
    }

    JavaCompileActionBuilder builder =
        new JavaCompileActionBuilder(ruleContext, javaToolchain, execGroup);

    JavaClasspathMode classpathMode = getJavaConfiguration().getReduceJavaClasspath();
    builder.setClasspathMode(classpathMode);
    builder.setAdditionalInputs(additionalInputsForDatabinding);
    Label label = ruleContext.getLabel();
    builder.setTargetLabel(label);
    Artifact coverageArtifact = maybeCreateCoverageArtifact(outputs.output());
    builder.setCoverageArtifact(coverageArtifact);
    BootClassPathInfo bootClassPathInfo = getBootclasspathOrDefault();
    builder.setBootClassPath(bootClassPathInfo);
    NestedSet<Artifact> classpath =
        NestedSetBuilder.<Artifact>naiveLinkOrder()
            .addTransitive(bootClassPathInfo.auxiliary())
            .addTransitive(attributes.getCompileTimeClassPath())
            .build();
    if (!bootClassPathInfo.auxiliary().isEmpty()) {
      builder.setClasspathEntries(classpath);
      builder.setDirectJars(
          NestedSetBuilder.<Artifact>naiveLinkOrder()
              .addTransitive(bootClassPathInfo.auxiliary())
              .addTransitive(attributes.getDirectJars())
              .build());
    } else {
      builder.setClasspathEntries(attributes.getCompileTimeClassPath());
      builder.setDirectJars(attributes.getDirectJars());
    }
    builder.setSourcePathEntries(attributes.getSourcePath());
    builder.setToolsJars(javaToolchain.getTools());
    builder.setJavaBuilder(
        javaToolchain.getJavaBuilder().withAdditionalJvmFlags(javaBuilderJvmFlags));
    if (!turbineAnnotationProcessing) {
      builder.setGenSourceOutput(outputs.genSource());
      builder.setAdditionalOutputs(attributes.getAdditionalOutputs());
      builder.setPlugins(plugins);
      builder.setManifestOutput(outputs.manifestProto());
    } else {
      // Don't do annotation processing, but pass the processorpath through to allow service-loading
      // Error Prone plugins.
      builder.setPlugins(
          JavaPluginData.create(
              /* processorClasses= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
              plugins.processorClasspath(),
              plugins.data()));
    }
    builder.setOutputs(outputs);

    ImmutableSet<Artifact> sourceFiles = attributes.getSourceFiles();
    builder.setSourceFiles(sourceFiles);
    builder.setSourceJars(sourceJars);
    builder.setJavacOpts(javacopts);
    builder.setUtf8Environment(semantics.utf8Environment(ruleContext.getExecutionPlatform()));
    builder.setJavacExecutionInfo(executionInfoInterner.intern(getExecutionInfo()));
    builder.setCompressJar(true);
    builder.setExtraData(JavaCommon.computePerPackageData(ruleContext, javaToolchain));
    builder.setStrictJavaDeps(attributes.getStrictJavaDeps());
    builder.setFixDepsTool(getJavaConfiguration().getFixDepsTool());
    builder.setCompileTimeDependencyArtifacts(attributes.getCompileTimeDependencyArtifacts());
    builder.setTargetLabel(
        attributes.getTargetLabel() == null ? label : attributes.getTargetLabel());
    builder.setInjectingRuleKind(attributes.getInjectingRuleKind());

    if (coverageArtifact != null) {
      ruleContext.registerAction(
          new LazyWritePathsFileAction(
              ruleContext.getActionOwner(execGroup),
              coverageArtifact,
              NestedSetBuilder.<Artifact>stableOrder().addAll(sourceFiles).build(),
              /* filesToIgnore= */ ImmutableSet.of(),
              false));
    }

    JavaCompileAction javaCompileAction = builder.build();
    ruleContext.getAnalysisEnvironment().registerAction(javaCompileAction);

    if (optimizedJar != null) {
      JavaConfiguration.NamedLabel optimizerLabel = getJavaConfiguration().getBytecodeOptimizer();
      createLocalOptimizationAction(
          outputs.output(),
          optimizedJar,
          NestedSetBuilder.<Artifact>naiveLinkOrder()
              .addTransitive(bootClassPathInfo.bootclasspath())
              .addTransitive(classpath)
              .build(),
          javaToolchain.getLocalJavaOptimizationConfiguration(),
          javaToolchain.getBytecodeOptimizer().tool(),
          optimizerLabel.name());
    }
  }

  /**
   * If there are sources and no resource, the only output is from the javac action. Otherwise
   * create a separate jar for the compilation and add resources with singlejar.
   */
  private boolean separateResourceJar(
      List<Artifact> resourceJars, JavaTargetAttributes attributes) {
    return !resourceJars.isEmpty()
        || !attributes.getResources().isEmpty()
        || !attributes.getResourceJars().isEmpty()
        || !attributes.getClassPathResources().isEmpty();
  }

  private ImmutableMap<String, String> getExecutionInfo() throws RuleErrorException {
    ImmutableMap.Builder<String, String> modifiableExecutionInfo = ImmutableMap.builder();
    modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "1");
    if (javaToolchain.getJavacSupportsWorkers()) {
      modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_WORKERS, "1");
    }
    if (javaToolchain.getJavacSupportsMultiplexWorkers()) {
      modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS, "1");
    }
    if (javaToolchain.getJavacSupportsWorkerCancellation()) {
      modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1");
    }
    if (javaToolchain.getJavacSupportsWorkerMultiplexSandboxing()) {
      modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING, "1");
    }
    ImmutableMap.Builder<String, String> executionInfo = ImmutableMap.builder();
    executionInfo.putAll(
        getConfiguration()
            .modifiedExecutionInfo(
                modifiableExecutionInfo.buildOrThrow(), JavaCompileActionBuilder.MNEMONIC));
    executionInfo.putAll(
        TargetUtils.getExecutionInfo(ruleContext.getRule(), ruleContext.isAllowTagsPropagation()));

    return executionInfo.buildKeepingLast();
  }

  /** Returns the bootclasspath explicit set in attributes if present, or else the default. */
  public BootClassPathInfo getBootclasspathOrDefault() throws RuleErrorException {
    JavaTargetAttributes attributes = getAttributes();
    if (!attributes.getBootClassPath().isEmpty()) {
      return attributes.getBootClassPath();
    } else {
      return javaToolchain.getBootclasspath();
    }
  }

  /**
   * Creates an {@link Artifact} needed by {@code JacocoCoverageRunner}.
   *
   * <p>The {@link Artifact} is created in the same directory as the given {@code compileJar} and
   * has the suffix {@code -paths-for-coverage.txt}.
   *
   * <p>Returns {@code null} if {@code compileJar} should not be instrumented.
   */
  @Nullable
  private Artifact maybeCreateCoverageArtifact(Artifact compileJar) {
    if (!shouldInstrumentJar()) {
      return null;
    }
    PathFragment packageRelativePath =
        compileJar.getRootRelativePath().relativeTo(ruleContext.getPackageDirectory());
    PathFragment path =
        FileSystemUtils.replaceExtension(packageRelativePath, "-paths-for-coverage.txt");
    return ruleContext.getPackageRelativeArtifact(path, compileJar.getRoot());
  }

  private boolean shouldInstrumentJar() {
    RuleContext ruleContext = getRuleContext();
    return getConfiguration().isCodeCoverageEnabled()
        && attributes.hasSourceFiles()
        && InstrumentedFilesCollector.shouldIncludeLocalSources(
            ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget());
  }

  private Artifact turbineOutput(Artifact classJar, String newExtension) {
    return getAnalysisEnvironment()
        .getDerivedArtifact(
            FileSystemUtils.replaceExtension(
                classJar.getOutputDirRelativePath(getConfiguration().isSiblingRepositoryLayout()),
                newExtension),
            classJar.getRoot());
  }

  /**
   * Creates the Action that compiles ijars from source.
   *
   * @param headerJar the jar output of this java compilation
   * @param headerDeps the .jdeps output of this java compilation
   */
  public void createHeaderCompilationAction(Artifact headerJar, Artifact headerDeps)
      throws RuleErrorException, InterruptedException {

    JavaTargetAttributes attributes = getAttributes();

    // only run API-generating annotation processors during header compilation
    JavaPluginData plugins = attributes.plugins().apiGeneratingPlugins();

    JavaHeaderCompileAction.Builder builder = getJavaHeaderCompileActionBuilder();
    builder.setOutputJar(headerJar);
    builder.setOutputDepsProto(headerDeps);
    builder.setPlugins(plugins);
    if (plugins
        .processorClasses()
        .toList()
        .contains("dagger.internal.codegen.ComponentProcessor")) {
      // See b/31371210 and b/142059842.
      builder.addTurbineHjarJavacOpt();
    }
    builder.enableDirectClasspath(enableDirectClasspath);
    builder.build(javaToolchain);
  }

  private JavaHeaderCompileAction.Builder getJavaHeaderCompileActionBuilder()
      throws RuleErrorException {
    JavaTargetAttributes attributes = getAttributes();
    JavaHeaderCompileAction.Builder builder = JavaHeaderCompileAction.newBuilder(ruleContext);
    builder.setSourceFiles(attributes.getSourceFiles());
    builder.setSourceJars(attributes.getSourceJars());
    builder.setClasspathEntries(attributes.getCompileTimeClassPath());
    builder.setBootclasspathEntries(getBootclasspathOrDefault().bootclasspath());
    // Exclude any per-package configured data (see JavaCommon.computePerPackageData).
    // It is used to allow Error Prone checks to load additional data,
    // and Error Prone doesn't run during header compilation.
    builder.setJavacOpts(customJavacOpts);
    builder.setStrictJavaDeps(attributes.getStrictJavaDeps());
    builder.setCompileTimeDependencyArtifacts(attributes.getCompileTimeDependencyArtifacts());
    builder.setDirectJars(attributes.getDirectJars());
    builder.setTargetLabel(attributes.getTargetLabel());
    builder.setInjectingRuleKind(attributes.getInjectingRuleKind());
    builder.setAdditionalInputs(additionalInputsForDatabinding);
    builder.setToolsJars(javaToolchain.getTools());
    builder.setExecGroup(execGroup);
    builder.setUtf8Environment(semantics.utf8Environment(ruleContext.getExecutionPlatform()));
    return builder;
  }

  /** Returns whether this target uses annotation processing. */
  public boolean usesAnnotationProcessing() {
    JavaTargetAttributes attributes = getAttributes();
    return getJavacOpts().contains("-processor") || attributes.plugins().hasProcessors();
  }

  private void createGenJarAction(
      Artifact classJar, Artifact manifestProto, Artifact genClassJar, JavaRuntimeInfo hostJavabase)
      throws RuleErrorException {
    getRuleContext()
        .registerAction(
            new SpawnAction.Builder()
                .addInput(manifestProto)
                .addInput(classJar)
                .addOutput(genClassJar)
                .addTransitiveInputs(hostJavabase.javaBaseInputs())
                .setJarExecutable(
                    JavaCommon.getHostJavaExecutable(hostJavabase),
                    getGenClassJar(ruleContext),
                    javaToolchain.getJvmOptions())
                .addCommandLine(
                    CustomCommandLine.builder()
                        .addExecPath("--manifest_proto", manifestProto)
                        .addExecPath("--class_jar", classJar)
                        .addExecPath("--output_jar", genClassJar)
                        .build())
                .setProgressMessage("Building genclass jar %{output}")
                .setMnemonic("JavaSourceJar")
                .setExecGroup(execGroup)
                .build(getRuleContext()));
  }

  /** Returns the GenClass deploy jar Artifact. */
  private Artifact getGenClassJar(RuleContext ruleContext) throws RuleErrorException {
    Artifact genClass = javaToolchain.getGenClass();
    if (genClass != null) {
      return genClass;
    }
    return ruleContext.getPrerequisiteArtifact("$genclass");
  }

  private void createResourceJarAction(Artifact resourceJar, ImmutableList<Artifact> extraJars)
      throws RuleErrorException {
    checkNotNull(resourceJar, "resource jar output must not be null");
    JavaTargetAttributes attributes = getAttributes();
    new ResourceJarActionBuilder()
        .setAdditionalInputs(
            NestedSetBuilder.wrap(Order.STABLE_ORDER, additionalInputsForDatabinding))
        .setJavaToolchain(javaToolchain)
        .setOutputJar(resourceJar)
        .setResources(attributes.getResources())
        .setClasspathResources(attributes.getClassPathResources())
        .setResourceJars(
            NestedSetBuilder.fromNestedSet(attributes.getResourceJars()).addAll(extraJars).build())
        .build(semantics, ruleContext, execGroup);
  }

  private void createLocalOptimizationAction(
      Artifact unoptimizedOutputJar,
      Artifact optimizedOutputJar,
      NestedSet<Artifact> classpath,
      List<Artifact> configs,
      FilesToRunProvider optimizer,
      String mnemonic) {
    CustomCommandLine.Builder command =
        CustomCommandLine.builder()
            .add("-runtype", "LOCAL_ONLY")
            .addExecPath("-injars", unoptimizedOutputJar)
            .addExecPath("-outjars", optimizedOutputJar)
            .addExecPaths(CustomCommandLine.VectorArg.addBefore("-libraryjars").each(classpath));
    for (Artifact config : configs) {
      command.addPrefixedExecPath("@", config);
    }

    getRuleContext()
        .registerAction(
            new SpawnAction.Builder()
                .addInput(unoptimizedOutputJar)
                .addTransitiveInputs(classpath)
                .addInputs(configs)
                .addOutput(optimizedOutputJar)
                .setExecutable(optimizer)
                .addCommandLine(
                    command.build(),
                    ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED).build())
                .setProgressMessage("Optimizing jar %{label}")
                .setMnemonic(mnemonic)
                .setExecGroup(execGroup)
                .build(getRuleContext()));
  }

  /**
   * Gets the value of the "javacopts" attribute combining them with the default options. If the
   * current rule has no javacopts attribute, this method only returns the default options.
   */
  private ImmutableList<String> getJavacOpts() {
    return customJavacOpts;
  }

}
