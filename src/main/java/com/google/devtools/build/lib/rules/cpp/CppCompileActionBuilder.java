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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/** Builder class to construct C++ compile actions. */
public final class CppCompileActionBuilder {

  private final ActionOwner owner;
  private boolean shareable;
  private final BuildConfigurationValue configuration;
  private CcToolchainFeatures.FeatureConfiguration featureConfiguration;
  private CcToolchainVariables variables = CcToolchainVariables.EMPTY;
  private Artifact sourceFile;
  private final NestedSetBuilder<Artifact> mandatoryInputsBuilder;
  private Artifact outputFile;
  private Artifact dwoFile;
  private Artifact ltoIndexingFile;
  private Artifact dotdFile;
  private Artifact diagnosticsFile;
  private Artifact gcnoFile;
  private CcCompilationContext ccCompilationContext = CcCompilationContext.EMPTY;
  private final List<String> pluginOpts = new ArrayList<>();
  private CoptsFilter coptsFilter = CoptsFilter.alwaysPasses();
  private ImmutableList<PathFragment> extraSystemIncludePrefixes = ImmutableList.of();
  private boolean usePic;
  private final CppConfiguration cppConfiguration;
  private final ArrayList<Artifact> additionalIncludeScanningRoots;
  private Boolean shouldScanIncludes;
  private Map<String, String> executionInfo = new LinkedHashMap<>();
  private final CppSemantics cppSemantics;
  private final CcToolchainProvider ccToolchain;
  @Nullable private String actionName;
  private ImmutableList<Artifact> buildInfoHeaderArtifacts = ImmutableList.of();
  private NestedSet<Artifact> cacheKeyInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private NestedSet<Artifact> additionalPrunableHeaders =
      NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  private ImmutableList<Artifact> additionalOutputs = ImmutableList.of();
  // New fields need to be added to the copy constructor.

  /** Creates a builder from a rule and configuration. */
  public CppCompileActionBuilder(
      ActionConstructionContext actionConstructionContext,
      CcToolchainProvider ccToolchain,
      BuildConfigurationValue configuration,
      CppSemantics cppSemantics) {

    ActionOwner actionOwner = null;
    if (actionConstructionContext instanceof RuleContext ruleContext
        && ruleContext.useAutoExecGroups()) {
      actionOwner = actionConstructionContext.getActionOwner(cppSemantics.getCppToolchainType());
    }

    this.owner = actionOwner == null ? actionConstructionContext.getActionOwner() : actionOwner;
    this.shareable = false;
    this.configuration = configuration;
    this.cppConfiguration = configuration.getFragment(CppConfiguration.class);
    this.mandatoryInputsBuilder = NestedSetBuilder.stableOrder();
    this.additionalIncludeScanningRoots = new ArrayList<>();
    this.ccToolchain = ccToolchain;
    this.cppSemantics = cppSemantics;
  }

  /**
   * Creates a builder that is a copy of another builder.
   */
  public CppCompileActionBuilder(CppCompileActionBuilder other) {
    this.owner = other.owner;
    this.shareable = other.shareable;
    this.featureConfiguration = other.featureConfiguration;
    this.sourceFile = other.sourceFile;
    this.mandatoryInputsBuilder = NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(other.mandatoryInputsBuilder.build());
    this.additionalIncludeScanningRoots = new ArrayList<>();
    this.additionalIncludeScanningRoots.addAll(other.additionalIncludeScanningRoots);
    this.outputFile = other.outputFile;
    this.dwoFile = other.dwoFile;
    this.ltoIndexingFile = other.ltoIndexingFile;
    this.dotdFile = other.dotdFile;
    this.gcnoFile = other.gcnoFile;
    this.ccCompilationContext = other.ccCompilationContext;
    this.pluginOpts.addAll(other.pluginOpts);
    this.coptsFilter = other.coptsFilter;
    this.extraSystemIncludePrefixes = other.extraSystemIncludePrefixes;
    this.cppConfiguration = other.cppConfiguration;
    this.configuration = other.configuration;
    this.usePic = other.usePic;
    this.shouldScanIncludes = other.shouldScanIncludes;
    this.executionInfo = new LinkedHashMap<>(other.executionInfo);
    this.cppSemantics = other.cppSemantics;
    this.ccToolchain = other.ccToolchain;
    this.actionName = other.actionName;
    this.additionalOutputs = other.additionalOutputs;
  }

  public CppCompileActionBuilder setSourceFile(Artifact sourceFile) {
    Preconditions.checkState(
        this.sourceFile == null,
        "New source file %s trying to overwrite old source file %s",
        sourceFile,
        this.sourceFile);
    return setSourceFileUnchecked(sourceFile);
  }

  public CppCompileActionBuilder setSourceFile(Artifact.TreeFileArtifact sourceFile) {
    Preconditions.checkState(
        !(this.sourceFile instanceof Artifact.TreeFileArtifact),
        "New source file %s trying to overwrite old source file %s also a tree file artifact",
        sourceFile,
        this.sourceFile);
    return setSourceFileUnchecked(sourceFile);
  }

  @CanIgnoreReturnValue
  private CppCompileActionBuilder setSourceFileUnchecked(Artifact sourceFile) {
    this.sourceFile = sourceFile;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setAdditionalOutputs(ImmutableList<Artifact> additionalOutputs) {
    this.additionalOutputs = additionalOutputs;
    return this;
  }

  public Artifact getSourceFile() {
    return sourceFile;
  }

  public CcCompilationContext getCcCompilationContext() {
    return ccCompilationContext;
  }

  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputsBuilder.build();
  }

  public String getActionName() {
    if (actionName != null) {
      return actionName;
    }
    PathFragment sourcePath = sourceFile.getExecPath();
    if (CppFileTypes.CPP_MODULE_MAP.matches(sourcePath)) {
      return CppActionNames.CPP_MODULE_COMPILE;
    } else if (CppFileTypes.CPP_HEADER.matches(sourcePath)) {
      // TODO(bazel-team): Handle C headers that probably don't work in C++ mode.
      if (featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS)) {
        return CppActionNames.CPP_HEADER_PARSING;
      }
      // CcCommon.collectCAndCppSources() ensures we do not add headers to
      // the compilation artifacts unless 'parse_headers' is set.
      throw new IllegalStateException();
    } else if (CppFileTypes.C_SOURCE.matches(sourcePath)) {
      return CppActionNames.C_COMPILE;
    } else if (CppFileTypes.CPP_SOURCE.matches(sourcePath)) {
      return CppActionNames.CPP_COMPILE;
    } else if (CppFileTypes.OBJC_SOURCE.matches(sourcePath)) {
      return CppActionNames.OBJC_COMPILE;
    } else if (CppFileTypes.OBJCPP_SOURCE.matches(sourcePath)) {
      return CppActionNames.OBJCPP_COMPILE;
    } else if (CppFileTypes.ASSEMBLER.matches(sourcePath)) {
      return CppActionNames.ASSEMBLE;
    } else if (CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR.matches(sourcePath)) {
      return CppActionNames.PREPROCESS_ASSEMBLE;
    } else if (CppFileTypes.CLIF_INPUT_PROTO.matches(sourcePath)) {
      return CppActionNames.CLIF_MATCH;
    } else if (CppFileTypes.CPP_MODULE.matches(sourcePath)) {
      return CppActionNames.CPP_MODULE_CODEGEN;
    }
    // CcCompilationHelper ensures CppCompileAction only gets instantiated for supported file types.
    throw new IllegalStateException();
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Uses {@link
   * RuleContext#throwWithRuleError(String)} to report errors. Prefer this method over {@link
   * CppCompileActionBuilder#buildOrThrowIllegalStateException()} whenever possible (meaning
   * whenever you have access to {@link RuleContext}).
   *
   * <p>This method may be called multiple times to create multiple compile actions (usually after
   * calling some setters to modify the generated action).
   */
  CppCompileAction buildOrThrowRuleError(RuleErrorConsumer ruleErrorConsumer)
      throws RuleErrorException {
    try {
      return buildAndVerify();
    } catch (UnconfiguredActionConfigException e) {
      throw ruleErrorConsumer.throwWithRuleError(e.getMessage());
    } catch (EvalException e) {
      throw ruleErrorConsumer.throwWithRuleError(e.getMessage());
    }
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Throws {@link
   * IllegalStateException} to report errors. Prefer {@link
   * CppCompileActionBuilder#buildOrThrowRuleError(RuleErrorConsumer)} over this method whenever
   * possible (meaning whenever you have access to {@link RuleContext}).
   *
   * <p>This method may be called multiple times to create multiple compile actions (usually after
   * calling some setters to modify the generated action).
   */
  public CppCompileAction buildOrThrowIllegalStateException() {
    try {
      return buildAndVerify();
    } catch (UnconfiguredActionConfigException | EvalException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Exception thrown when the action is not configured in the toolchain. */
  public static final class UnconfiguredActionConfigException extends Exception {
    private UnconfiguredActionConfigException(String actionName) {
      super(String.format("Expected action_config for '%s' to be configured", actionName));
    }
  }

  /**
   * Builds the Action as configured and performs some validations on the action. Uses given {@link
   * Consumer} to collect validation errors.
   */
  public CppCompileAction buildAndVerify() throws UnconfiguredActionConfigException, EvalException {
    // This must be set either to false or true by CppSemantics, otherwise someone forgot to call
    // finalizeCompileActionBuilder on this builder.
    Preconditions.checkNotNull(shouldScanIncludes);
    Preconditions.checkNotNull(featureConfiguration);
    boolean useHeaderModules = useHeaderModules();

    String actionName = getActionName();
    if (featureConfiguration.actionIsConfigured(actionName)) {
      for (String executionRequirement :
          featureConfiguration.getToolRequirementsForAction(actionName)) {
        executionInfo.put(executionRequirement, "");
      }
    } else {
      throw new UnconfiguredActionConfigException(actionName);
    }

    NestedSet<Artifact> realMandatorySpawnInputs = buildMandatoryInputs();
    NestedSet<Artifact> realMandatoryInputs =
        new NestedSetBuilder<Artifact>(Order.STABLE_ORDER)
            .addTransitive(realMandatorySpawnInputs)
            .addTransitive(cacheKeyInputs)
            .build();
    NestedSet<Artifact> prunableHeaders = additionalPrunableHeaders;

    configuration.modifyExecutionInfo(
        executionInfo,
        CppCompileAction.actionNameToMnemonic(
            actionName, featureConfiguration, cppConfiguration.useCppCompileHeaderMnemonic()));

    // Copying the collections is needed to make the builder reusable.
    return new CppCompileAction(
        owner,
        featureConfiguration,
        variables,
        sourceFile,
        configuration,
        shareable,
        shouldScanIncludes,
        usePic,
        useHeaderModules,
        realMandatoryInputs,
        realMandatorySpawnInputs,
        getBuiltinIncludeFiles(),
        prunableHeaders,
        outputFile,
        dotdFile,
        diagnosticsFile,
        gcnoFile,
        dwoFile,
        ltoIndexingFile,
        ccCompilationContext,
        coptsFilter,
        ImmutableList.copyOf(additionalIncludeScanningRoots),
        ImmutableMap.copyOf(executionInfo),
        actionName,
        cppSemantics,
        getBuiltinIncludeDirectories(),
        ccToolchain.getGrepIncludes(),
        additionalOutputs);
  }

  private ImmutableList<Artifact> getBuiltinIncludeFiles() throws EvalException {
    ImmutableList<Artifact> builtinIncludeFiles = ccToolchain.getBuiltinIncludeFiles();
    if (buildInfoHeaderArtifacts.isEmpty()) {
      return builtinIncludeFiles;
    }
    if (builtinIncludeFiles.isEmpty()) {
      return buildInfoHeaderArtifacts;
    }
    return ImmutableList.<Artifact>builderWithExpectedSize(
            builtinIncludeFiles.size() + buildInfoHeaderArtifacts.size())
        .addAll(builtinIncludeFiles)
        .addAll(buildInfoHeaderArtifacts)
        .build();
  }

  private boolean shouldParseShowIncludes() {
    return featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES);
  }

  /** Returns the list of mandatory inputs for the {@link CppCompileAction} as configured. */
  NestedSet<Artifact> buildMandatoryInputs() throws EvalException {
    NestedSetBuilder<Artifact> realMandatoryInputsBuilder = NestedSetBuilder.compileOrder();
    realMandatoryInputsBuilder.addTransitive(mandatoryInputsBuilder.build());
    realMandatoryInputsBuilder.addAll(getBuiltinIncludeFiles());
    if (useHeaderModules() && !shouldScanIncludes) {
      realMandatoryInputsBuilder.addTransitive(ccCompilationContext.getTransitiveModules(usePic));
    }
    ccCompilationContext.addAdditionalInputs(realMandatoryInputsBuilder);
    realMandatoryInputsBuilder.add(Preconditions.checkNotNull(sourceFile));
    if (ccToolchain.getGrepIncludes() != null) {
      realMandatoryInputsBuilder.add(ccToolchain.getGrepIncludes());
    }
    if (!shouldScanIncludes && dotdFile == null && !shouldParseShowIncludes()) {
      realMandatoryInputsBuilder.addTransitive(ccCompilationContext.getDeclaredIncludeSrcs());
      realMandatoryInputsBuilder.addTransitive(additionalPrunableHeaders);
    }
    return realMandatoryInputsBuilder.build();
  }

  NestedSet<Artifact> getPrunableHeaders() {
    return additionalPrunableHeaders;
  }

  NestedSet<Artifact> getInputsForInvalidation() {
    return ccCompilationContext.getTransitiveCompilationPrerequisites();
  }

  private boolean useHeaderModules(Artifact sourceFile) {
    Preconditions.checkNotNull(featureConfiguration);
    Preconditions.checkNotNull(sourceFile);
    return featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES)
        && (sourceFile.isFileType(CppFileTypes.CPP_SOURCE)
            || sourceFile.isFileType(CppFileTypes.CPP_HEADER)
            || sourceFile.isFileType(CppFileTypes.CPP_MODULE_MAP));
  }

  private boolean useHeaderModules() {
    return useHeaderModules(sourceFile);
  }

  /**
   * Set action name that is used to pick the right action_config and features from {@link
   * FeatureConfiguration}. By default the action name is decided from the source filetype.
   */
  @CanIgnoreReturnValue
  public CppCompileActionBuilder setActionName(String actionName) {
    Preconditions.checkState(
        this.actionName == null,
        "New actionName %s trying to overwrite old name %s",
        actionName,
        this.actionName);
    this.actionName = actionName;
    return this;
  }

  /** Sets the feature configuration to be used for the action. */
  @CanIgnoreReturnValue
  public CppCompileActionBuilder setFeatureConfiguration(
      FeatureConfiguration featureConfiguration) {
    Preconditions.checkNotNull(featureConfiguration);
    this.featureConfiguration = featureConfiguration;
    return this;
  }

  FeatureConfiguration getFeatureConfiguration() {
    return featureConfiguration;
  }

  /** Sets the feature build variables to be used for the action. */
  @CanIgnoreReturnValue
  public CppCompileActionBuilder setVariables(CcToolchainVariables variables) {
    this.variables = variables;
    return this;
  }

  /** Returns the build variables to be used for the action. */
  public CcToolchainVariables getVariables() {
    return variables;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder addExecutionInfo(Map<String, String> executionInfo) {
    this.executionInfo.putAll(executionInfo);
    return this;
  }

  Map<String, String> getExecutionInfo() {
    return executionInfo;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder addMandatoryInputs(NestedSet<Artifact> artifacts) {
    mandatoryInputsBuilder.addTransitive(artifacts);
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder addMandatoryInputs(List<Artifact> artifacts) {
    mandatoryInputsBuilder.addAll(artifacts);
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder addTransitiveMandatoryInputs(NestedSet<Artifact> artifacts) {
    mandatoryInputsBuilder.addTransitive(artifacts);
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder addAdditionalIncludeScanningRoots(
      List<Artifact> additionalIncludeScanningRoots) {
    this.additionalIncludeScanningRoots.addAll(additionalIncludeScanningRoots);
    return this;
  }

  public boolean useDotdFile(Artifact sourceFile) {
    return CppFileTypes.headerDiscoveryRequired(sourceFile) && !useHeaderModules(sourceFile);
  }

  public boolean dotdFilesEnabled() {
    return cppSemantics.needsDotdInputPruning(configuration)
        && !shouldParseShowIncludes()
        && !featureConfiguration.isEnabled(CppRuleClasses.NO_DOTD_FILE);
  }

  public boolean serializedDiagnosticsFilesEnabled() {
    return featureConfiguration.isEnabled(CppRuleClasses.SERIALIZED_DIAGNOSTICS_FILE);
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setOutputs(
      Artifact outputFile, Artifact dotdFile, Artifact diagnosticsFile) {
    this.outputFile = outputFile;
    this.dotdFile = dotdFile;
    this.diagnosticsFile = diagnosticsFile;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setOutputs(
      ActionConstructionContext actionConstructionContext,
      RuleErrorConsumer ruleErrorConsumer,
      Label label,
      ArtifactCategory outputCategory,
      String outputName)
      throws RuleErrorException {
    this.outputFile =
        CppHelper.getCompileOutputArtifact(
            actionConstructionContext,
            label,
            CppHelper.getArtifactNameForCategory(ccToolchain, outputCategory, outputName),
            configuration);
    if (dotdFilesEnabled() && useDotdFile(sourceFile)) {
      String dotdFileName = CppHelper.getDotdFileName(ccToolchain, outputCategory, outputName);
      dotdFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext, label, dotdFileName, configuration);
    } else {
      dotdFile = null;
    }
    if (serializedDiagnosticsFilesEnabled()) {
      String diagnosticsFileName =
          CppHelper.getDiagnosticsFileName(ccToolchain, outputCategory, outputName);
      diagnosticsFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext, label, diagnosticsFileName, configuration);
    } else {
      diagnosticsFile = null;
    }
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setDwoFile(Artifact dwoFile) {
    this.dwoFile = dwoFile;
    return this;
  }

  /**
   * Set the minimized bitcode file emitted by this (ThinLTO) compilation that can be used in place
   * of the full bitcode outputFile in the LTO indexing step.
   */
  @CanIgnoreReturnValue
  public CppCompileActionBuilder setLtoIndexingFile(Artifact ltoIndexingFile) {
    this.ltoIndexingFile = ltoIndexingFile;
    return this;
  }

  public Artifact getOutputFile() {
    return outputFile;
  }

  public Artifact getDotdFile() {
    return this.dotdFile;
  }

  public Artifact getDiagnosticsFile() {
    return this.diagnosticsFile;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setGcnoFile(Artifact gcnoFile) {
    this.gcnoFile = gcnoFile;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setCcCompilationContext(
      CcCompilationContext ccCompilationContext) {
    this.ccCompilationContext = ccCompilationContext;
    return this;
  }

  /** Sets whether the CompileAction should use pic mode. */
  @CanIgnoreReturnValue
  public CppCompileActionBuilder setPicMode(boolean usePic) {
    this.usePic = usePic;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setShareable(boolean shareable) {
    this.shareable = shareable;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setShouldScanIncludes(boolean shouldScanIncludes) {
    this.shouldScanIncludes = shouldScanIncludes;
    return this;
  }

  public boolean getShouldScanIncludes() {
    return shouldScanIncludes;
  }

  public CcToolchainProvider getToolchain() {
    return ccToolchain;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setCoptsFilter(CoptsFilter coptsFilter) {
    this.coptsFilter = Preconditions.checkNotNull(coptsFilter);
    return this;
  }

  CoptsFilter getCoptsFilter() {
    return coptsFilter;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setBuildInfoHeaderArtifacts(
      ImmutableList<Artifact> buildInfoHeaderArtifacts) {
    this.buildInfoHeaderArtifacts = buildInfoHeaderArtifacts;
    return this;
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setCacheKeyInputs(NestedSet<Artifact> cacheKeyInputs) {
    this.cacheKeyInputs = cacheKeyInputs;
    return this;
  }

  public PathFragment getRealOutputFilePath() {
    return outputFile.getExecPath();
  }

  ActionEnvironment getActionEnvironment() {
    return configuration.getActionEnvironment();
  }

  @CanIgnoreReturnValue
  public CppCompileActionBuilder setAdditionalPrunableHeaders(
      NestedSet<Artifact> additionalPrunableHeaders) {
    this.additionalPrunableHeaders = Preconditions.checkNotNull(additionalPrunableHeaders);
    return this;
  }

  ImmutableList<PathFragment> getBuiltinIncludeDirectories() throws EvalException {
    return ccToolchain.getBuiltInIncludeDirectories();
  }

  public boolean shouldCompileHeaders() {
    Preconditions.checkNotNull(featureConfiguration);
    return CcToolchainProvider.shouldProcessHeaders(featureConfiguration, cppConfiguration);
  }
}
