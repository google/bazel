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

import static com.google.devtools.build.lib.rules.java.JavaStarlarkCommon.checkPrivateAccess;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Ascii;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.StrictDepsMode;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaConfigurationApi;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/** A java compiler configuration containing the flags required for compilation. */
@Immutable
@RequiresOptions(options = {JavaOptions.class, PlatformOptions.class})
public final class JavaConfiguration extends Fragment implements JavaConfigurationApi {

  /** Values for the --java_classpath option */
  public enum JavaClasspathMode {
    /** Use full transitive classpaths, the default behavior. */
    OFF,
    /** JavaBuilder computes the reduced classpath before invoking javac. */
    JAVABUILDER,
    /** Bazel computes the reduced classpath and tries it in a separate action invocation. */
    BAZEL
  }

  /** Values for the --experimental_one_version_enforcement option */
  public enum OneVersionEnforcementLevel {
    /** Don't attempt to check for one version violations (the default) */
    OFF,
    /**
     * Check for one version violations, emit warnings to stderr if any are found, but don't break
     * the binary.
     */
    WARNING,
    /**
     * Check for one version violations, emit warnings to stderr if any are found, and break the
     * rule if it's found.
     */
    ERROR
  }

  private final NestedSet<String> commandLineJavacFlags;
  private final Label javaLauncherLabel;
  private final boolean useIjars;
  private final boolean useHeaderCompilation;
  private final boolean generateJavaDeps;
  private final OneVersionEnforcementLevel enforceOneVersion;
  private final boolean enforceOneVersionOnJavaTests;
  private final JavaClasspathMode javaClasspath;
  private final boolean inmemoryJdepsFiles;
  private final ImmutableList<String> defaultJvmFlags;
  private final StrictDepsMode strictJavaDeps;
  private final String fixDepsTool;
  private final Label proguardBinary;
  private final NamedLabel bytecodeOptimizer;
  private final boolean runLocalJavaOptimizations;
  private final Label localJavaOptimizationConfiguration;
  private final boolean splitBytecodeOptimizationPass;
  private final int bytecodeOptimizationPassActions;
  private final boolean enforceProguardFileExtension;
  private final boolean runAndroidLint;
  private final boolean explicitJavaTestDeps;
  private final boolean addTestSupportToCompileTimeDeps;
  private final ImmutableList<Label> pluginList;
  private final boolean experimentalTurbineAnnotationProcessing;
  private final boolean experimentalEnableJspecify;
  private final boolean multiReleaseDeployJars;
  private final boolean disallowJavaImportExports;
  private final boolean disallowJavaImportEmptyJars;
  private final boolean autoCreateDeployJarForJavaTests;

  public JavaConfiguration(BuildOptions buildOptions) throws InvalidConfigurationException {
    JavaOptions javaOptions = buildOptions.get(JavaOptions.class);
    this.commandLineJavacFlags =
        JavaHelper.detokenizeJavaOptions(JavaHelper.tokenizeJavaOptions(javaOptions.javacOpts));
    this.javaLauncherLabel = javaOptions.javaLauncher;
    this.useIjars = javaOptions.useIjars;
    this.useHeaderCompilation = javaOptions.headerCompilation;
    this.generateJavaDeps =
        javaOptions.javaDeps || javaOptions.javaClasspath != JavaClasspathMode.OFF;
    this.javaClasspath = javaOptions.javaClasspath;
    this.inmemoryJdepsFiles = javaOptions.inmemoryJdepsFiles;
    this.defaultJvmFlags = ImmutableList.copyOf(javaOptions.jvmOpts);
    this.strictJavaDeps = javaOptions.strictJavaDeps;
    this.fixDepsTool = javaOptions.fixDepsTool;
    this.proguardBinary = javaOptions.proguard;
    this.runLocalJavaOptimizations = javaOptions.runLocalJavaOptimizations;
    this.localJavaOptimizationConfiguration = javaOptions.localJavaOptimizationConfiguration;
    this.splitBytecodeOptimizationPass = javaOptions.splitBytecodeOptimizationPass;
    this.bytecodeOptimizationPassActions = javaOptions.bytecodeOptimizationPassActions;
    this.enforceProguardFileExtension = javaOptions.enforceProguardFileExtension;
    this.enforceOneVersion = javaOptions.enforceOneVersion;
    this.enforceOneVersionOnJavaTests = javaOptions.enforceOneVersionOnJavaTests;
    this.explicitJavaTestDeps = javaOptions.explicitJavaTestDeps;
    this.addTestSupportToCompileTimeDeps = javaOptions.addTestSupportToCompileTimeDeps;
    this.runAndroidLint = javaOptions.runAndroidLint;
    this.multiReleaseDeployJars = javaOptions.multiReleaseDeployJars;
    this.disallowJavaImportExports = javaOptions.disallowJavaImportExports;
    this.disallowJavaImportEmptyJars = javaOptions.disallowJavaImportEmptyJars;
    this.autoCreateDeployJarForJavaTests = javaOptions.autoCreateDeployJarForJavaTests;
    Map<String, Label> optimizers = javaOptions.bytecodeOptimizers;
    if (optimizers.size() != 1) {
      throw new InvalidConfigurationException(
          String.format(
              "--experimental_bytecode_optimizers can only accept exactly one mapping, but %d"
                  + " mappings were provided.",
              optimizers.size()));
    }
    Map.Entry<String, Label> optimizer = Iterables.getOnlyElement(optimizers.entrySet());
    String mnemonic = optimizer.getKey();
    Label optimizerLabel = optimizer.getValue();
    if (optimizerLabel == null && !"Proguard".equals(mnemonic)) {
      throw new InvalidConfigurationException("Must supply label for optimizer " + mnemonic);
    }
    this.bytecodeOptimizer = NamedLabel.create(mnemonic, Optional.fromNullable(optimizerLabel));
    if (runLocalJavaOptimizations && optimizerLabel == null) {
      throw new InvalidConfigurationException(
          "--experimental_local_java_optimizations cannot be provided without "
              + "--experimental_bytecode_optimizers.");
    }

    this.pluginList = ImmutableList.copyOf(javaOptions.pluginList);
    this.experimentalTurbineAnnotationProcessing =
        javaOptions.experimentalTurbineAnnotationProcessing;
    this.experimentalEnableJspecify = javaOptions.experimentalEnableJspecify;

    if (javaOptions.disallowLegacyJavaToolchainFlags) {
      checkLegacyToolchainFlagIsUnset("javabase", javaOptions.javaBase);
      checkLegacyToolchainFlagIsUnset("host_javabase", javaOptions.hostJavaBase);
      checkLegacyToolchainFlagIsUnset("java_toolchain", javaOptions.javaToolchain);
      checkLegacyToolchainFlagIsUnset("host_java_toolchain", javaOptions.hostJavaToolchain);
    }

    boolean oldToolchainFlagSet =
        javaOptions.javaBase != null
            || javaOptions.hostJavaBase != null
            || javaOptions.javaToolchain != null
            || javaOptions.hostJavaToolchain != null;
    boolean newToolchainFlagSet =
        javaOptions.javaLanguageVersion != null
            || javaOptions.hostJavaLanguageVersion != null
            || javaOptions.javaRuntimeVersion != null
            || javaOptions.hostJavaRuntimeVersion != null;
    if (oldToolchainFlagSet && !newToolchainFlagSet) {
      throw new InvalidConfigurationException(
          "At least one of the deprecated no-op toolchain configuration flags is set (--javabase,"
              + " --host_javabase, --java_toolchain, --host_java_toolchain) and none of the new"
              + " toolchain configuration flags is set (--java_language_version,"
              + " --tool_java_language_version, --java_runtime_version,"
              + " --tool_java_runtime_version). This may result in incorrect toolchain selection "
              + "(see https://github.com/bazelbuild/bazel/issues/7849).");
    }
  }

  private static void checkLegacyToolchainFlagIsUnset(String flag, Label label)
      throws InvalidConfigurationException {
    if (label != null) {
      throw new InvalidConfigurationException(
          String.format(
              "--%s=%s is no longer supported, use --platforms instead (see #7849)", flag, label));
    }
  }

  @Override
  // TODO(bazel-team): this is the command-line passed options, we should remove from Starlark
  // probably.
  public ImmutableList<String> getDefaultJavacFlagsForStarlarkAsList() {
    return JavaHelper.tokenizeJavaOptions(commandLineJavacFlags);
  }

  @Override
  // TODO(bazel-team): this is the command-line passed options, we should remove from Starlark
  // probably.
  public Depset getDefaultJavacFlagsStarlark() {
    return Depset.of(String.class, commandLineJavacFlags);
  }

  @Override
  public String getStrictJavaDepsName() {
    return Ascii.toLowerCase(strictJavaDeps.name());
  }

  /** Returns true iff Java compilation should use ijars. */
  public boolean getUseIjars() {
    return useIjars;
  }

  /**
   * Returns true iff Java compilation should use ijars. Checks if the functions is been called from
   * builtins.
   */
  @Override
  public boolean getUseIjarsInStarlark(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return useIjars;
  }

  /** Returns true iff Java header compilation is enabled. */
  public boolean useHeaderCompilation() {
    return useHeaderCompilation;
  }

  @Override
  public boolean useHeaderCompilationStarlark(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return useHeaderCompilation();
  }

  /** Returns true iff dependency information is generated after compilation. */
  public boolean getGenerateJavaDeps() {
    return generateJavaDeps;
  }

  @Override
  public boolean getGenerateJavaDepsStarlark(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return getGenerateJavaDeps();
  }

  public JavaClasspathMode getReduceJavaClasspath() {
    return javaClasspath;
  }

  @Override
  public String getReduceJavaClasspathStarlark(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return getReduceJavaClasspath().name();
  }

  public boolean inmemoryJdepsFiles() {
    return inmemoryJdepsFiles;
  }

  @Override
  public ImmutableList<String> getDefaultJvmFlags() {
    return defaultJvmFlags;
  }

  /** Which tool to use for fixing dependency errors. */
  public String getFixDepsTool() {
    return fixDepsTool;
  }

  /** Returns proper label only if --java_launcher= is specified, otherwise null. */
  @StarlarkConfigurationField(
      name = "launcher",
      doc = "Returns the label provided with --java_launcher, if any.",
      defaultInToolRepository = true)
  @Nullable
  public Label getJavaLauncherLabel() {
    return javaLauncherLabel;
  }

  /** Returns the label provided with --proguard_top, if any. */
  @StarlarkConfigurationField(
      name = "proguard_top",
      doc = "Returns the label provided with --proguard_top, if any.",
      defaultInToolRepository = true)
  @Nullable
  public Label getProguardBinary() {
    return proguardBinary;
  }

  /**
   * Returns whether the OPTIMIZATION stage of the bytecode optimizer will be split across two
   * actions.
   */
  @Override
  public boolean splitBytecodeOptimizationPass() {
    return splitBytecodeOptimizationPass;
  }

  /**
   * This specifies the number of actions to divide the OPTIMIZATION stage of the bytecode optimizer
   * into. Note that if split_bytecode_optimization_pass is set, this will only change behavior if
   * it is > 2.
   */
  @Override
  public int bytecodeOptimizationPassActions() {
    return bytecodeOptimizationPassActions;
  }

  /** Returns whether ProGuard configuration files are required to use a *.pgcfg extension. */
  @Override
  public boolean enforceProguardFileExtension() {
    return enforceProguardFileExtension;
  }

  /** Stores a String name and an optional associated label. */
  @AutoCodec
  public record NamedLabel(String name, Optional<Label> label) {
    public NamedLabel {
      requireNonNull(name, "name");
      requireNonNull(label, "label");
    }

    public static NamedLabel create(String name, Optional<Label> label) {
      return new NamedLabel(name, label);
    }
  }

  /** Returns bytecode optimizer to run. */
  @Nullable
  public NamedLabel getBytecodeOptimizer() {
    return bytecodeOptimizer;
  }

  @Override
  public String getBytecodeOptimizerMnemonic() {
    return bytecodeOptimizer.name();
  }

  @StarlarkConfigurationField(
      name = "bytecode_optimizer",
      doc = "Returns the label provided with --proguard_top, if any.",
      defaultInToolRepository = true)
  @Nullable
  public Label getBytecodeOptimizerLabel() {
    return bytecodeOptimizer.label().orNull();
  }

  /** Returns true if the bytecode optimizer should incrementally optimize all Java artifacts. */
  public boolean runLocalJavaOptimizations() {
    return runLocalJavaOptimizations;
  }

  @StarlarkConfigurationField(
      name = "java_toolchain_bytecode_optimizer",
      documented = false,
      defaultInToolRepository = true)
  @Nullable
  public Label getBytecodeOptimizerLabelForJavaToolchain() {
    if (runLocalJavaOptimizations) {
      return bytecodeOptimizer.label().orNull();
    } else {
      return null;
    }
  }

  /** Returns the optimization configuration for local Java optimizations if they are enabled. */
  @StarlarkConfigurationField(name = "local_java_optimization_configuration", documented = false)
  @Nullable
  public Label getLocalJavaOptimizationConfiguration() {
    if (runLocalJavaOptimizations) {
      return localJavaOptimizationConfiguration;
    } else {
      return null;
    }
  }

  /**
   * Make it mandatory for java_test targets to explicitly declare any JUnit or Hamcrest
   * dependencies instead of accidentally obtaining them from the TestRunner's dependencies.
   */
  public boolean explicitJavaTestDeps() {
    return explicitJavaTestDeps;
  }

  @Override
  public boolean explicitJavaTestDepsStarlark(StarlarkThread thread) throws EvalException {
    checkPrivateAccess(thread);
    return explicitJavaTestDeps();
  }

  /**
   * Returns an enum representing whether or not Bazel should attempt to enforce one-version
   * correctness on java_binary rules using the 'oneversion' tool in the java_toolchain.
   *
   * <p>One-version correctness will inspect for multiple non-identical versions of java classes in
   * the transitive dependencies for a java_binary.
   */
  public OneVersionEnforcementLevel oneVersionEnforcementLevel() {
    return enforceOneVersion;
  }

  @Override
  public boolean multiReleaseDeployJars() {
    return multiReleaseDeployJars;
  }

  /** Returns true if empty java_import jars are not allowed. */
  @Override
  public boolean getDisallowJavaImportEmptyJarsInStarlark(StarlarkThread thread)
      throws EvalException {
    checkPrivateAccess(thread);
    return disallowJavaImportEmptyJars;
  }

  /** Returns true if java_import exports are not allowed. */
  @Override
  public boolean getDisallowJavaImportExportsInStarlark(StarlarkThread thread)
      throws EvalException {
    checkPrivateAccess(thread);
    return disallowJavaImportExports;
  }

  @Override
  public String starlarkOneVersionEnforcementLevel() {
    return oneVersionEnforcementLevel().name();
  }

  @Override
  public boolean enforceOneVersionOnJavaTests() {
    return enforceOneVersionOnJavaTests;
  }

  @Override
  public boolean addTestSupportToCompileTimeDeps() {
    return addTestSupportToCompileTimeDeps;
  }

  @Override
  public boolean runAndroidLint() {
    return runAndroidLint;
  }

  @Override
  public ImmutableList<Label> getPlugins() {
    return pluginList;
  }

  public boolean experimentalTurbineAnnotationProcessing() {
    return experimentalTurbineAnnotationProcessing;
  }

  public boolean experimentalEnableJspecify() {
    return experimentalEnableJspecify;
  }

  @Override
  public boolean autoCreateJavaTestDeployJars(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    return autoCreateDeployJarForJavaTests;
  }
}
