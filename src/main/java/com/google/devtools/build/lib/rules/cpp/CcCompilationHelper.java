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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.PathMappers;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcCommon.Language;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariablesExtension;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CompilationInfoApi;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Tuple;

/**
 * A class to create C/C++ compile actions in a way that is consistent with cc_library. Rules that
 * generate source files and emulate cc_library on top of that should use this class instead of the
 * lower-level APIs in CppHelper and CppCompileActionBuilder.
 *
 * <p>Rules that want to use this class are required to have implicit dependencies on the toolchain,
 * the STL, and so on. Optionally, they can also have copts, and malloc attributes, but note that
 * these require explicit calls to the corresponding setter methods.
 */
public final class CcCompilationHelper {
  /**
   * Configures a compile action builder by setting up command line options and auxiliary inputs
   * according to the FDO configuration. This method does nothing If FDO is disabled.
   */
  private void configureFdoBuildVariables(
      Map<String, String> variablesBuilder, String fdoInstrument, String csFdoInstrument)
      throws EvalException {
    if (featureConfiguration.isEnabled(CppRuleClasses.FDO_INSTRUMENT)) {
      variablesBuilder.put(
          CompileBuildVariables.FDO_INSTRUMENT_PATH.getVariableName(), fdoInstrument);
    }
    if (featureConfiguration.isEnabled(CppRuleClasses.CS_FDO_INSTRUMENT)) {
      variablesBuilder.put(
          CompileBuildVariables.CS_FDO_INSTRUMENT_PATH.getVariableName(), csFdoInstrument);
    }

    // FDO is disabled -> do nothing.
    Preconditions.checkNotNull(fdoContext);
    if (!fdoContext.hasArtifacts()) {
      return;
    }

    if (fdoContext.getPrefetchHintsArtifact() != null) {
      variablesBuilder.put(
          CompileBuildVariables.FDO_PREFETCH_HINTS_PATH.getVariableName(),
          fdoContext.getPrefetchHintsArtifact().getExecPathString());
    }

    if (shouldPassPropellerProfiles()) {
      if (fdoContext.getPropellerOptimizeInputFile().getCcArtifact() != null) {
        variablesBuilder.put(
            CompileBuildVariables.PROPELLER_OPTIMIZE_CC_PATH.getVariableName(),
            fdoContext.getPropellerOptimizeInputFile().getCcArtifact().getExecPathString());
      }

      if (fdoContext.getPropellerOptimizeInputFile().getLdArtifact() != null) {
        variablesBuilder.put(
            CompileBuildVariables.PROPELLER_OPTIMIZE_LD_PATH.getVariableName(),
            fdoContext.getPropellerOptimizeInputFile().getLdArtifact().getExecPathString());
      }
    }

    if (fdoContext.getMemProfProfileArtifact() != null) {
      variablesBuilder.put(
          CompileBuildVariables.MEMPROF_PROFILE_PATH.getVariableName(),
          fdoContext.getMemProfProfileArtifact().getExecPathString());
    }

    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    // Optimization phase
    if (branchFdoProfile != null) {
      if (!getAuxiliaryFdoInputs().isEmpty()) {
        if (featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO)
            || featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO)) {
          variablesBuilder.put(
              CompileBuildVariables.FDO_PROFILE_PATH.getVariableName(),
              branchFdoProfile.getProfileArtifact().getExecPathString());
        }
        if (featureConfiguration.isEnabled(CppRuleClasses.FDO_OPTIMIZE)) {
          if (branchFdoProfile.isLlvmFdo() || branchFdoProfile.isLlvmCSFdo()) {
            variablesBuilder.put(
                CompileBuildVariables.FDO_PROFILE_PATH.getVariableName(),
                branchFdoProfile.getProfileArtifact().getExecPathString());
          }
        }
      }
    }
  }

  /** Returns whether Propeller profiles should be passed to a compile action. */
  private boolean shouldPassPropellerProfiles() throws EvalException {
    if (ccToolchain.isToolConfiguration()) {
      // Propeller doesn't make much sense for host builds.
      return false;
    }

    if (fdoContext.getPropellerOptimizeInputFile() == null) {
      // No Propeller profiles to pass.
      return false;
    }
    // Don't pass Propeller input files if they have no effect (i.e. for ThinLTO).
    return !featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
        || featureConfiguration.isEnabled(
            CppRuleClasses.PROPELLER_OPTIMIZE_THINLTO_COMPILE_ACTIONS);
  }

  /** Returns the auxiliary files that need to be added to the {@link CppCompileAction}. */
  private NestedSet<Artifact> getAuxiliaryFdoInputs() throws EvalException {
    NestedSetBuilder<Artifact> auxiliaryInputs = NestedSetBuilder.stableOrder();

    if (fdoContext.getPrefetchHintsArtifact() != null) {
      auxiliaryInputs.add(fdoContext.getPrefetchHintsArtifact());
    }
    if (shouldPassPropellerProfiles()) {
      if (fdoContext.getPropellerOptimizeInputFile().getCcArtifact() != null) {
        auxiliaryInputs.add(fdoContext.getPropellerOptimizeInputFile().getCcArtifact());
      }
      if (fdoContext.getPropellerOptimizeInputFile().getLdArtifact() != null) {
        auxiliaryInputs.add(fdoContext.getPropellerOptimizeInputFile().getLdArtifact());
      }
    }
    if (fdoContext.getMemProfProfileArtifact() != null) {
      auxiliaryInputs.add(fdoContext.getMemProfProfileArtifact());
    }
    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    // If --fdo_optimize was not specified, we don't have any additional inputs.
    if (branchFdoProfile != null) {
      auxiliaryInputs.add(branchFdoProfile.getProfileArtifact());
    }

    return auxiliaryInputs.build();
  }

  /**
   * A group of source file types and action names for builds controlled by CcCompilationHelper.
   * Determines what file types CcCompilationHelper considers sources and what action configs are
   * configured in the CROSSTOOL.
   */
  enum SourceCategory {
    CC(
        FileTypeSet.of(
            CppFileTypes.CPP_SOURCE,
            CppFileTypes.CPP_HEADER,
            CppFileTypes.C_SOURCE,
            CppFileTypes.ASSEMBLER,
            CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR,
            CppFileTypes.CLIF_INPUT_PROTO)),
    CC_AND_OBJC(
        FileTypeSet.of(
            CppFileTypes.CPP_SOURCE,
            CppFileTypes.CPP_HEADER,
            CppFileTypes.OBJC_SOURCE,
            CppFileTypes.OBJCPP_SOURCE,
            CppFileTypes.C_SOURCE,
            CppFileTypes.ASSEMBLER,
            CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR));

    private final FileTypeSet sourceTypeSet;

    SourceCategory(FileTypeSet sourceTypeSet) {
      this.sourceTypeSet = sourceTypeSet;
    }

    /** Returns the set of file types that are valid for this category. */
    public FileTypeSet getSourceTypes() {
      return sourceTypeSet;
    }
  }

  /**
   * Contains the providers as well as the {@code CcCompilationOutputs} and the {@code
   * CcCompilationContext}.
   */
  // TODO(plf): Rename so that it's not confused with CcCompilationContext and also consider
  // merging
  // this class with {@code CcCompilationOutputs}.
  public static final class CompilationInfo implements CompilationInfoApi<Artifact> {
    private final CcCompilationContext ccCompilationContext;
    private final CcCompilationOutputs compilationOutputs;

    private CompilationInfo(
        CcCompilationContext ccCompilationContext, CcCompilationOutputs compilationOutputs) {
      this.ccCompilationContext = ccCompilationContext;
      this.compilationOutputs = compilationOutputs;
    }

    @Override
    public CcCompilationOutputs getCcCompilationOutputs() {
      return compilationOutputs;
    }

    @Override
    public CcCompilationContext getCcCompilationContext() {
      return ccCompilationContext;
    }
  }

  private final CppSemantics semantics;
  private final BuildConfigurationValue configuration;
  private final ImmutableMap<String, String> executionInfo;
  private final CppConfiguration cppConfiguration;
  private final boolean shouldProcessHeaders;

  private final List<Artifact> publicHeaders = new ArrayList<>();
  private final List<Artifact> separateModuleHeaders = new ArrayList<>();
  private final List<Artifact> nonModuleMapHeaders = new ArrayList<>();
  private final List<Artifact> publicTextualHeaders = new ArrayList<>();
  private final List<Artifact> privateHeaders = new ArrayList<>();
  private final List<Artifact> additionalInputs = new ArrayList<>();
  private final List<Artifact> additionalCompilationInputs = new ArrayList<>();
  private final List<Artifact> additionalIncludeScanningRoots = new ArrayList<>();
  private final List<PathFragment> additionalExportedHeaders = new ArrayList<>();
  private final List<CppModuleMap> additionalCppModuleMaps = new ArrayList<>();
  private final LinkedHashMap<Artifact, CppSource> compilationUnitSources = new LinkedHashMap<>();
  private final LinkedHashMap<Artifact, CppSource> moduleInterfaceSources = new LinkedHashMap<>();
  private ImmutableList<String> copts = ImmutableList.of();
  private ImmutableList<String> conlyopts = ImmutableList.of();
  private ImmutableList<String> cxxopts = ImmutableList.of();
  private CoptsFilter coptsFilter = CoptsFilter.alwaysPasses();
  private final Set<String> defines = new LinkedHashSet<>();
  private final Set<String> localDefines = new LinkedHashSet<>();
  private final List<CcCompilationContext> deps = new ArrayList<>();
  private final List<CcCompilationContext> implementationDeps = new ArrayList<>();
  private final List<PathFragment> systemIncludeDirs = new ArrayList<>();
  private final List<PathFragment> quoteIncludeDirs = new ArrayList<>();
  private final List<PathFragment> includeDirs = new ArrayList<>();
  private final List<PathFragment> frameworkIncludeDirs = new ArrayList<>();

  private final SourceCategory sourceCategory;
  private final List<VariablesExtension> variablesExtensions = new ArrayList<>();
  private CcToolchainVariables prebuiltParent;
  private CcToolchainVariables prebuiltParentWithFdo;
  @Nullable private CppModuleMap cppModuleMap;
  private boolean propagateModuleMapToCompileAction = true;

  private final FeatureConfiguration featureConfiguration;
  private final CcToolchainProvider ccToolchain;
  private final FdoContext fdoContext;
  private boolean generateModuleMap = true;
  private String purpose = "";
  private boolean generateNoPicAction;
  private boolean generatePicAction;
  private boolean isCodeCoverageEnabled = true;
  private String stripIncludePrefix = null;
  private String includePrefix = null;

  // This context is built out of interface deps and implementation deps.
  private CcCompilationContext ccCompilationContext;

  private final RuleErrorConsumer ruleErrorConsumer;
  private final ActionConstructionContext actionConstructionContext;
  private final Label label;

  /** Creates a CcCompilationHelper that outputs artifacts in a given configuration. */
  public CcCompilationHelper(
      ActionConstructionContext actionConstructionContext,
      Label label,
      CppSemantics semantics,
      FeatureConfiguration featureConfiguration,
      SourceCategory sourceCategory,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext,
      BuildConfigurationValue buildConfiguration,
      ImmutableMap<String, String> executionInfo,
      boolean shouldProcessHeaders)
      throws EvalException {
    this.semantics = Preconditions.checkNotNull(semantics);
    this.featureConfiguration = Preconditions.checkNotNull(featureConfiguration);
    this.sourceCategory = Preconditions.checkNotNull(sourceCategory);
    this.ccToolchain = Preconditions.checkNotNull(ccToolchain);
    this.fdoContext = Preconditions.checkNotNull(fdoContext);
    this.actionConstructionContext = Preconditions.checkNotNull(actionConstructionContext);
    this.configuration = Preconditions.checkNotNull(buildConfiguration);
    this.cppConfiguration = ccToolchain.getCppConfiguration();
    setGenerateNoPicAction(
        !CcToolchainProvider.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            || !CppHelper.usePicForBinaries(cppConfiguration, featureConfiguration));
    setGeneratePicAction(
        CcToolchainProvider.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            || CppHelper.usePicForBinaries(cppConfiguration, featureConfiguration));
    this.ruleErrorConsumer = actionConstructionContext.getRuleErrorConsumer();
    this.label = Preconditions.checkNotNull(label);
    this.executionInfo = Preconditions.checkNotNull(executionInfo);
    this.shouldProcessHeaders = shouldProcessHeaders;
  }

  /**
   * Adds {@code headers} as public header files. These files will be made visible to dependent
   * rules. They may be parsed/preprocessed or compiled into a header module depending on the
   * configuration.
   *
   * <p>The option to pass in a Sequence of (Artifact, Label) Tuples is created for the method
   * collectPerFileCopts() which in turn supports the --per_file_copt flag.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addPublicHeaders(Sequence<?> headers) throws EvalException {
    for (Object header : headers) {
      if (header instanceof Artifact headerArtifact) {
        addHeader(headerArtifact, label);
      } else if (header instanceof Tuple headerTuple) {
        addHeader((Artifact) headerTuple.get(0), (Label) headerTuple.get(1));
      } else {
        throw new EvalException("Unsupported public header type: " + header.getClass());
      }
    }
    return this;
  }

  /**
   * Adds headers that are compiled into a separate module (when using C++ modules). The idea here
   * is that a single (generated) library might want to create headers of very different transitive
   * dependency size. In this case, building headers with very few transitive dependencies into a
   * separate module can drastrically improve build performance of that module and its users.
   *
   * <p>Headers in this separate module must not include any of the regular headers.
   *
   * <p>THIS IS AN EXPERIMENTAL FACILITY THAT MIGHT GO AWAY.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addSeparateModuleHeaders(Collection<Artifact> headers) {
    separateModuleHeaders.addAll(headers);
    return this;
  }

  /**
   * Add the corresponding files as public header files, i.e., these files will not be compiled, but
   * are made visible as includes to dependent rules in module maps.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addAdditionalExportedHeaders(
      Iterable<PathFragment> additionalExportedHeaders) {
    Iterables.addAll(this.additionalExportedHeaders, additionalExportedHeaders);
    return this;
  }

  /**
   * Add the corresponding files as public textual header files. These files will not be compiled
   * into a target's header module, but will be made visible as textual includes to dependent rules.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addPublicTextualHeaders(List<Artifact> textualHeaders) {
    Iterables.addAll(this.publicTextualHeaders, textualHeaders);
    for (Artifact header : textualHeaders) {
      this.additionalExportedHeaders.add(header.getExecPath());
    }
    return this;
  }

  /**
   * Adds {@code headers} as private header files. These files will be made visible to dependent
   * rules. They may be parsed/preprocessed or compiled into a header module depending on the
   * configuration.
   *
   * <p>The option to pass in a Sequence of (Artifact, Label) Tuples is created for the method
   * collectPerFileCopts() which in turn supports the --per_file_copt flag.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addPrivateHeaders(Sequence<?> headers) throws EvalException {
    for (Object header : headers) {
      if (header instanceof Artifact headerArtifact) {
        addPrivateHeader(headerArtifact, label);
      } else if (header instanceof Tuple headerTuple) {
        addPrivateHeader((Artifact) headerTuple.get(0), (Label) headerTuple.get(1));
      } else {
        throw new EvalException("Unsupported private header type: " + header.getClass());
      }
    }
    return this;
  }

  @CanIgnoreReturnValue
  private CcCompilationHelper addPrivateHeader(Artifact privateHeader, Label label) {
    boolean isHeader =
        CppFileTypes.CPP_HEADER.matches(privateHeader.getExecPath())
            || privateHeader.isTreeArtifact();
    boolean isTextualInclude =
        CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(privateHeader.getExecPath());
    Preconditions.checkState(isHeader || isTextualInclude);

    if (shouldProcessHeaders
        && CcToolchainProvider.shouldProcessHeaders(featureConfiguration, cppConfiguration)
        && !shouldProvideHeaderModules()
        && !isTextualInclude) {
      compilationUnitSources.put(
          privateHeader, CppSource.create(privateHeader, label, CppSource.Type.HEADER));
    }
    this.privateHeaders.add(privateHeader);
    return this;
  }

  /**
   * Add the corresponding files as source files. These may also be header files, in which case they
   * will not be compiled, but also not made visible as includes to dependent rules.
   *
   * <p>The option to pass in a Sequence of (Artifact, Label) Tuples is created for the method
   * collectPerFileCopts() which in turn supports the --per_file_copt flag.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addSources(Sequence<?> sources) throws EvalException {
    for (Object source : sources) {
      if (source instanceof Artifact sourceArtifact) {
        addSource(sourceArtifact, label);
      } else if (source instanceof Tuple sourceTuple) {
        addSource((Artifact) sourceTuple.get(0), (Label) sourceTuple.get(1));
      } else {
        throw new EvalException("Unsupported source type: " + source.getClass());
      }
    }
    return this;
  }

  /**
   * Add the corresponding files as module interface source files.
   *
   * <p>The option to pass in a Sequence of (Artifact, Label) Tuples is created for the method
   * collectPerFileCopts() which in turn supports the --per_file_copt flag.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addModuleInterfaceSources(Sequence<?> sources) throws EvalException {
    for (Object source : sources) {
      if (source instanceof Artifact sourceArtifact) {
        addModuleInterfaceSource(sourceArtifact, label);
      } else if (source instanceof Tuple sourceTuple) {
        addModuleInterfaceSource((Artifact) sourceTuple.get(0), (Label) sourceTuple.get(1));
      } else {
        throw new EvalException("Unsupported module interface source type: " + source.getClass());
      }
    }
    return this;
  }

  private void addModuleInterfaceSource(Artifact source, Label label) {
    Preconditions.checkNotNull(featureConfiguration);
    moduleInterfaceSources.put(source, CppSource.create(source, label, CppSource.Type.SOURCE));
  }

  /** Add the corresponding files as non-header, non-source input files. */
  @CanIgnoreReturnValue
  public CcCompilationHelper addAdditionalInputs(Collection<Artifact> inputs) {
    Iterables.addAll(additionalInputs, inputs);
    return this;
  }

  /**
   * Adds a header to {@code publicHeaders} and in case header processing is switched on for the
   * file type also to compilationUnitSources.
   */
  private void addHeader(Artifact header, Label label) {
    // We assume TreeArtifacts passed in are directories containing proper headers.
    boolean isHeader =
        CppFileTypes.CPP_HEADER.matches(header.getExecPath()) || header.isTreeArtifact();
    boolean isTextualInclude = CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(header.getExecPath());
    publicHeaders.add(header);
    if (!shouldProcessHeaders
        || isTextualInclude
        || !isHeader
        || !CcToolchainProvider.shouldProcessHeaders(featureConfiguration, cppConfiguration)
        || shouldProvideHeaderModules()) {
      return;
    }

    compilationUnitSources.put(header, CppSource.create(header, label, CppSource.Type.HEADER));
  }

  /**
   * Adds a source to {@code compilationUnitSources} if it is a compiled file type (including
   * parsed/preprocessed header) and to {@code privateHeaders} if it is a header.
   */
  private void addSource(Artifact source, Label label) {
    Preconditions.checkNotNull(featureConfiguration);
    Preconditions.checkState(!CppFileTypes.CPP_HEADER.matches(source.getExecPath()));
    // We assume TreeArtifacts passed in are directories containing proper sources for compilation.
    if (!sourceCategory.getSourceTypes().matches(source.getExecPathString())
        && !source.isTreeArtifact()) {
      // TODO(plf): If it's a non-source file we ignore it. This is only the case for precompiled
      // files which should be forbidden in srcs of cc_library|binary and instead be migrated to
      // cc_import rules.
      return;
    }

    boolean isClifInputProto = CppFileTypes.CLIF_INPUT_PROTO.matches(source.getExecPathString());
    CppSource.Type type;
    if (isClifInputProto) {
      type = CppSource.Type.CLIF_INPUT_PROTO;
    } else {
      type = CppSource.Type.SOURCE;
    }
    compilationUnitSources.put(source, CppSource.create(source, label, type));
  }

  /**
   * Returns the compilation unit sources. That includes all compiled source files as well as
   * headers that will be parsed or preprocessed. Each source file contains the label it arises from
   * in the build graph as well as {@code FeatureConfiguration} that should be used during its
   * compilation.
   */
  public ImmutableSet<CppSource> getCompilationUnitSources() {
    return ImmutableSet.copyOf(this.compilationUnitSources.values());
  }

  @CanIgnoreReturnValue
  public CcCompilationHelper setCopts(ImmutableList<String> copts) {
    this.copts = Preconditions.checkNotNull(copts);
    return this;
  }

  /** Sets a pattern that is used to filter copts; set to {@code null} for no filtering. */
  public void setCoptsFilter(CoptsFilter coptsFilter) {
    this.coptsFilter = Preconditions.checkNotNull(coptsFilter);
  }

  @CanIgnoreReturnValue
  public CcCompilationHelper setConlyopts(ImmutableList<String> copts) {
    this.conlyopts = Preconditions.checkNotNull(copts);
    return this;
  }

  @CanIgnoreReturnValue
  public CcCompilationHelper setCxxopts(ImmutableList<String> copts) {
    this.cxxopts = Preconditions.checkNotNull(copts);
    return this;
  }

  /**
   * Adds the given defines to the compiler command line of this target as well as its dependent
   * targets.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addDefines(Iterable<String> defines) {
    Iterables.addAll(this.defines, defines);
    return this;
  }

  /**
   * Adds the given defines to the compiler command line. These defines are not propagated
   * transitively to the dependent targets.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addNonTransitiveDefines(Iterable<String> defines) {
    Iterables.addAll(this.localDefines, defines);
    return this;
  }

  /** For adding CC compilation infos that affect compilation, for example from dependencies. */
  @CanIgnoreReturnValue
  public CcCompilationHelper addCcCompilationContexts(
      Iterable<CcCompilationContext> ccCompilationContexts) {
    Iterables.addAll(this.deps, Preconditions.checkNotNull(ccCompilationContexts));
    return this;
  }

  /**
   * For adding CC compilation infos that affect compilation non-transitively, for example from
   * dependencies.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addImplementationDepsCcCompilationContexts(
      Iterable<CcCompilationContext> ccCompileActionCompilationContexts) {
    Iterables.addAll(
        this.implementationDeps, Preconditions.checkNotNull(ccCompileActionCompilationContexts));
    return this;
  }

  /**
   * Adds the given directories to the system include directories (they are passed with {@code
   * "-isystem"} to the compiler); these are also passed to dependent rules.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addSystemIncludeDirs(Iterable<PathFragment> systemIncludeDirs) {
    Iterables.addAll(this.systemIncludeDirs, systemIncludeDirs);
    return this;
  }

  /**
   * Adds the given directories to the quote include directories (they are passed with {@code
   * "-iquote"} to the compiler); these are also passed to dependent rules.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addQuoteIncludeDirs(Iterable<PathFragment> quoteIncludeDirs) {
    Iterables.addAll(this.quoteIncludeDirs, quoteIncludeDirs);
    return this;
  }

  /**
   * Adds the given directories to the include directories (they are passed with {@code "-I"} to the
   * compiler); these are also passed to dependent rules.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addIncludeDirs(Iterable<PathFragment> includeDirs) {
    Iterables.addAll(this.includeDirs, includeDirs);
    return this;
  }

  /**
   * Adds the given directories to the framework include directories (they are passed with {@code
   * "-F"} to the compiler); these are also passed to dependent rules.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper addFrameworkIncludeDirs(Iterable<PathFragment> frameworkIncludeDirs) {
    Iterables.addAll(this.frameworkIncludeDirs, frameworkIncludeDirs);
    return this;
  }

  /** Adds a variableExtension to template the crosstool. */
  @CanIgnoreReturnValue
  public CcCompilationHelper addVariableExtension(VariablesExtension variableExtension) {
    Preconditions.checkNotNull(variableExtension);
    this.variablesExtensions.add(variableExtension);
    return this;
  }

  /** Sets a module map artifact for this build. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setCppModuleMap(CppModuleMap cppModuleMap) {
    Preconditions.checkNotNull(cppModuleMap);
    this.cppModuleMap = cppModuleMap;
    return this;
  }

  /** Signals that this target's module map should not be an input to c++ compile actions. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setPropagateModuleMapToCompileAction(boolean propagatesModuleMap) {
    this.propagateModuleMapToCompileAction = propagatesModuleMap;
    return this;
  }

  /** Whether to generate no-PIC actions. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setGenerateNoPicAction(boolean generateNoPicAction) {
    this.generateNoPicAction = generateNoPicAction;
    return this;
  }

  /** Whether to generate PIC actions. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setGeneratePicAction(boolean generatePicAction) {
    this.generatePicAction = generatePicAction;
    return this;
  }

  /** Adds mandatory inputs for the compilation action. */
  @CanIgnoreReturnValue
  public CcCompilationHelper addAdditionalCompilationInputs(
      Collection<Artifact> compilationMandatoryInputs) {
    this.additionalCompilationInputs.addAll(compilationMandatoryInputs);
    return this;
  }

  /** Adds additional includes to be scanned. */
  // TODO(plf): This is only needed for CLIF. Investigate whether this is strictly necessary or
  // there is a way to avoid include scanning for CLIF rules.
  @CanIgnoreReturnValue
  public CcCompilationHelper addAdditionalIncludeScanningRoots(
      Collection<Artifact> additionalIncludeScanningRoots) {
    this.additionalIncludeScanningRoots.addAll(additionalIncludeScanningRoots);
    return this;
  }

  /** Sets the include prefix to append to the public headers. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setIncludePrefix(@Nullable String includePrefix) {
    this.includePrefix = includePrefix;
    return this;
  }

  /** Sets the include prefix to remove from the public headers. */
  @CanIgnoreReturnValue
  public CcCompilationHelper setStripIncludePrefix(@Nullable String stripIncludePrefix) {
    this.stripIncludePrefix = stripIncludePrefix;
    return this;
  }

  @CanIgnoreReturnValue
  public CcCompilationHelper setCodeCoverageEnabled(boolean codeCoverageEnabled) {
    this.isCodeCoverageEnabled = codeCoverageEnabled;
    return this;
  }

  private static StarlarkList<String> convertPathFragmentsToStarlarkList(
      Iterable<PathFragment> pathFragments) {
    ImmutableList.Builder<String> pathStrings = ImmutableList.builder();
    for (PathFragment pathFragment : pathFragments) {
      pathStrings.add(pathFragment.getPathString());
    }
    return StarlarkList.immutableCopyOf(pathStrings.build());
  }

  private Tuple callStarlarkInit(RuleContext ruleContext)
      throws RuleErrorException, InterruptedException {
    StarlarkFunction initCcCompilationContext =
        (StarlarkFunction) ruleContext.getStarlarkDefinedBuiltin("init_cc_compilation_context");
    ruleContext.initStarlarkRuleContext();
    try {
      return (Tuple)
          ruleContext.callStarlarkOrThrowRuleError(
              initCcCompilationContext,
              ImmutableList.of(
                  /* ctx */ ruleContext.getStarlarkRuleContext(),
                  /* binfiles_dir */ ruleContext.getBinFragment().getPathString(),
                  /* genfiles_dir */ ruleContext.getGenfilesFragment().getPathString(),
                  /* label */ label,
                  /* config */ configuration,
                  /* quote_include_dirs */ convertPathFragmentsToStarlarkList(quoteIncludeDirs),
                  /* framework_include_dirs */ convertPathFragmentsToStarlarkList(
                      frameworkIncludeDirs),
                  /* system_include_dirs */ convertPathFragmentsToStarlarkList(systemIncludeDirs),
                  /* include_dirs */ convertPathFragmentsToStarlarkList(includeDirs),
                  /* feature_configuration */ FeatureConfigurationForStarlark.from(
                      featureConfiguration),
                  /* public_headers_artifacts */ StarlarkList.immutableCopyOf(publicHeaders),
                  /* include_prefix */ includePrefix == null ? Starlark.NONE : includePrefix,
                  /* strip_include_prefix */ stripIncludePrefix == null
                      ? Starlark.NONE
                      : stripIncludePrefix,
                  /* non_module_map_headers */ StarlarkList.immutableCopyOf(nonModuleMapHeaders),
                  /* cc_toolchain_compilation_context */ ccToolchain == null
                      ? Starlark.NONE
                      : ccToolchain.getCcInfo().getCcCompilationContext(),
                  /* defines */ StarlarkList.immutableCopyOf(defines),
                  /* local_defines */ StarlarkList.immutableCopyOf(localDefines),
                  /* public_textual_headers */ StarlarkList.immutableCopyOf(publicTextualHeaders),
                  /* private_headers_artifacts */ StarlarkList.immutableCopyOf(privateHeaders),
                  /* additional_inputs */ StarlarkList.immutableCopyOf(additionalInputs),
                  /* separate_module_headers */ StarlarkList.immutableCopyOf(separateModuleHeaders),
                  /* generate_module_map */ generateModuleMap,
                  /* generate_pic_action */ generatePicAction,
                  /* generate_no_pic_action */ generateNoPicAction,
                  /* module_map */ cppModuleMap == null ? Starlark.NONE : cppModuleMap,
                  /* propagate_module_map_to_compile_action */ propagateModuleMapToCompileAction,
                  /* additional_exported_headers */ convertPathFragmentsToStarlarkList(
                      additionalExportedHeaders),
                  /* deps */ StarlarkList.immutableCopyOf(deps),
                  /* purpose */ purpose,
                  /* implementation_deps */ StarlarkList.immutableCopyOf(implementationDeps),
                  /* additional_cpp_module_maps */ StarlarkList.immutableCopyOf(
                      additionalCppModuleMaps)),
              ImmutableMap.of());
    } catch (EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }
  }

  /** Create the C++ compile actions, and the corresponding compilation related providers. */
  public CompilationInfo compile(RuleContext ruleContext)
      throws RuleErrorException, InterruptedException {

    if (!generatePicAction && !generateNoPicAction) {
      ruleErrorConsumer.ruleError("Either PIC or no PIC actions have to be created.");
    }

    Tuple ccCompilationContextObjectsTuple = callStarlarkInit(ruleContext);

    CcCompilationContext publicCompilationContext =
        (CcCompilationContext) ccCompilationContextObjectsTuple.get(0);
    ccCompilationContext = publicCompilationContext;
    if (!implementationDeps.isEmpty()) {
      Preconditions.checkState(
          ccCompilationContextObjectsTuple.get(1) != Starlark.NONE,
          "Compilation context for implementation deps was not created");
      ccCompilationContext = (CcCompilationContext) ccCompilationContextObjectsTuple.get(1);
    }

    boolean compileHeaderModules = featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULES);
    Preconditions.checkState(
        !compileHeaderModules || publicCompilationContext.getCppModuleMap() != null,
        "All cc rules must support module maps.");

    // Create compile actions (both PIC and no-PIC).
    try {
      CcCompilationOutputs ccOutputs;
      if (featureConfiguration.isEnabled(CppRuleClasses.CPP_MODULES)) {
        // Handle C++20 Module compile
        ccOutputs = createCcCompileActionsWithCpp20Module();
        publicCompilationContext =
            CcCompilationContext.createWithCpp20Modules(
                publicCompilationContext,
                ccOutputs.getPcmFiles(false),
                ccOutputs.getPcmFiles(true),
                ccOutputs.getModulesInfoFiles(false),
                ccOutputs.getModulesInfoFiles(true));
      } else {
        // Create compile actions (both PIC and no-PIC).
        ccOutputs = createCcCompileActions();
      }
      if (cppConfiguration.processHeadersInDependencies()) {
        return new CompilationInfo(
            CcCompilationContext.createWithExtraHeaderTokens(
                publicCompilationContext, ccOutputs.getHeaderTokenFiles()),
            ccOutputs);
      } else {
        return new CompilationInfo(publicCompilationContext, ccOutputs);
      }
    } catch (EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }
  }

  public void registerAdditionalModuleMap(CppModuleMap cppModuleMap) {
    this.additionalCppModuleMaps.add(Preconditions.checkNotNull(cppModuleMap));
  }

  /** Don't generate a module map for this target if a custom module map is provided. */
  @CanIgnoreReturnValue
  public CcCompilationHelper doNotGenerateModuleMap() {
    generateModuleMap = false;
    return this;
  }

  /**
   * Sets the purpose for the {@code CcCompilationContext}.
   *
   * @param purpose the purpose. Only used to influence the location of the output of Objective-C
   *     compilation.
   */
  @CanIgnoreReturnValue
  public CcCompilationHelper setPurpose(String purpose) {
    this.purpose = Preconditions.checkNotNull(purpose);
    return this;
  }

  /** @return whether we want to provide header modules for the current target. */
  private boolean shouldProvideHeaderModules() {
    return featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULES)
        && (!publicHeaders.isEmpty() || !privateHeaders.isEmpty());
  }

  /**
   * Calculate the output names for object file paths from a set of source files.
   *
   * <p>The object file path is constructed in the following format:
   *   {@code <bazel-bin>/<target_package_path>/_objs/<target_name>/<output_name>.<obj_extension>}.
   *
   * <p>When there's no two source files having the same basename:
   *   {@code <output_name> = <prefixDir>/<source_file_base_name>}
   * otherwise:
   *   {@code <output_name> = <prefixDir>/N/<source_file_base_name>,
   *   {@code N} = the file's order among the source files with the same basename, starts with 0
   *
   * <p>Examples:
   * <ol>
   * <li>Output names for ["lib1/foo.cc", "lib2/bar.cc"] are ["foo", "bar"]
   * <li>Output names for ["foo.cc", "bar.cc", "foo.cpp", "lib/foo.cc"] are
   *     ["0/foo", "bar", "1/foo", "2/foo"]
   * </ol>
   */
  private ImmutableMap<Artifact, String> calculateOutputNameMap(
      ImmutableSet<Artifact> sourceArtifacts, String prefixDir) {
    ImmutableMap.Builder<Artifact, String> builder = ImmutableMap.builder();

    HashMap<String, Integer> count = new LinkedHashMap<>();
    HashMap<String, Integer> number = new LinkedHashMap<>();
    for (Artifact source : sourceArtifacts) {
      String outputName =
          FileSystemUtils.removeExtension(source.getRootRelativePath()).getBaseName();
      count.put(
          outputName.toLowerCase(Locale.ROOT),
          count.getOrDefault(outputName.toLowerCase(Locale.ROOT), 0) + 1);
    }

    for (Artifact source : sourceArtifacts) {
      String outputName =
          FileSystemUtils.removeExtension(source.getRootRelativePath()).getBaseName();
      if (count.getOrDefault(outputName.toLowerCase(Locale.ROOT), 0) > 1) {
        int num = number.getOrDefault(outputName.toLowerCase(Locale.ROOT), 0);
        number.put(outputName.toLowerCase(Locale.ROOT), num + 1);
        outputName = num + "/" + outputName;
      }
      // If prefixDir is set, prepend it to the outputName
      if (prefixDir != null) {
        outputName = prefixDir + "/" + outputName;
      }
      builder.put(source, outputName);
    }

    return builder.buildOrThrow();
  }

  /**
   * Calculate outputNameMap for different source types separately. Returns a merged outputNameMap
   * for all artifacts.
   */
  private ImmutableMap<Artifact, String> calculateOutputNameMapByType(
      Map<Artifact, CppSource> sources, String prefixDir) {
    ImmutableMap.Builder<Artifact, String> builder = ImmutableMap.builder();
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.SOURCE), prefixDir));
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.HEADER), prefixDir));
    // TODO(plf): Removing CLIF logic
    builder.putAll(
        calculateOutputNameMap(
            getSourceArtifactsByType(sources, CppSource.Type.CLIF_INPUT_PROTO), prefixDir));
    return builder.buildOrThrow();
  }

  private ImmutableSet<Artifact> getSourceArtifactsByType(
      Map<Artifact, CppSource> sources, CppSource.Type type) {
    ImmutableSet.Builder<Artifact> result = ImmutableSet.builder();
    for (CppSource source : sources.values()) {
      if (source.getType().equals(type)) {
        result.add(source.getSource());
      }
    }
    return result.build();
  }

  private CcCompilationOutputs createCcCompileActionsWithCpp20Module()
      throws RuleErrorException, EvalException, InterruptedException {
    Preconditions.checkState(
        featureConfiguration.isEnabled(CppRuleClasses.CPP_MODULES),
        "to use C++20 Modules, the feature CPP_MODULES must be enabled");
    Preconditions.checkNotNull(ccCompilationContext);
    CcCompilationOutputs.Builder result = CcCompilationOutputs.builder();
    // merge module interfaces and ordinary sources
    Map<Artifact, CppSource> sourcesMap = new LinkedHashMap<>();
    sourcesMap.putAll(compilationUnitSources);
    sourcesMap.putAll(moduleInterfaceSources);
    ImmutableMap<Artifact, String> outputNameMap =
        calculateOutputNameMapByType(sourcesMap, /* prefixDir= */ null);
    if (generateNoPicAction) {
      createCcCompileActionsWithCpp20ModuleHelper(result, /* usePic= */ false, outputNameMap);
    }
    if (generatePicAction) {
      createCcCompileActionsWithCpp20ModuleHelper(result, /* usePic= */ true, outputNameMap);
    }
    return result.build();
  }

  private void createCcCompileActionsWithCpp20ModuleHelper(
      CcCompilationOutputs.Builder result,
      boolean usePic,
      ImmutableMap<Artifact, String> outputNameMap)
      throws RuleErrorException, EvalException, InterruptedException {

    ImmutableMap.Builder<Artifact, Artifact.DerivedArtifact> moduleFileMapBuilder =
        new ImmutableMap.Builder<>();
    ImmutableMap.Builder<Artifact, Artifact> ddiFileMapBuilder = new ImmutableMap.Builder<>();
    NestedSetBuilder<Artifact> ddiFileSetBuilder = NestedSetBuilder.stableOrder();
    ImmutableList.Builder<CommandLine> commandLineBuilder = new ImmutableList.Builder<>();

    // declare <target-name>.CXXModules.json
    // all modules information is put here
    Artifact modulesInfoFile =
        CppHelper.getCompileOutputArtifact(
            actionConstructionContext,
            label,
            CppHelper.getArtifactNameForCategory(
                ccToolchain,
                ArtifactCategory.CPP_MODULES_INFO,
                getOutputNameBaseWith(label.getName(), usePic)),
            configuration);
    if (usePic) {
      result.addPicModulesInfoFile(modulesInfoFile);
    } else {
      result.addModulesInfoFile(modulesInfoFile);
    }
    for (CppSource source : moduleInterfaceSources.values()) {
      Artifact sourceArtifact = source.getSource();
      String outputName = outputNameMap.get(sourceArtifact);
      ArtifactCategory outputCategory = ArtifactCategory.CPP_MODULE;
      if (Objects.equals(ccToolchain.getCompiler(), "gcc")) {
        outputCategory = ArtifactCategory.CPP_MODULE_GCM;
      } else if (Objects.equals(ccToolchain.getCompiler(), "msvc-cl")) {
        outputCategory = ArtifactCategory.CPP_MODULE_IFC;
      }
      var moduleFile =
          CppHelper.getCompileModuleOutputArtifact(
              actionConstructionContext,
              label,
              CppHelper.getArtifactNameForCategory(
                  ccToolchain, outputCategory, getOutputNameBaseWith(outputName, usePic)),
              configuration);
      moduleFileMapBuilder.put(sourceArtifact, moduleFile);
      // dependencies information are put in .ddi file
      // the format is https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2022/p1689r5.html
      var ddiOutputName =
          CppHelper.getArtifactNameForCategory(
              ccToolchain,
              ArtifactCategory.CPP_MODULES_DDI,
              getOutputNameBaseWith(outputName, usePic));
      Artifact ddiFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext, label, ddiOutputName, configuration);
      commandLineBuilder.add(
          CommandLine.of(
              ImmutableList.of("-d", ddiFile.getExecPathString(), moduleFile.getExecPathString())));
      createScanDepsAction(source.getLabel(), sourceArtifact, usePic, ddiFile, ddiOutputName);
      ddiFileSetBuilder.add(ddiFile);
      ddiFileMapBuilder.put(sourceArtifact, ddiFile);
    }
    createAggDdiAction(
        ddiFileSetBuilder.build(),
        commandLineBuilder.build(),
        ccCompilationContext.getModulesInfoFiles(usePic),
        modulesInfoFile);
    ImmutableMap<Artifact, Artifact.DerivedArtifact> moduleFileMap = moduleFileMapBuilder.build();
    ImmutableMap<Artifact, Artifact> ddiFileMap = ddiFileMapBuilder.build();
    var allModuleFilesBuilder =
        NestedSetBuilder.<Artifact.DerivedArtifact>stableOrder().addAll(moduleFileMap.values());
    var depModuleFiles = ccCompilationContext.getModuleFiles(usePic);
    if (depModuleFiles != null) {
      allModuleFilesBuilder.addTransitive(depModuleFiles);
    }
    var allModuleFiles = allModuleFilesBuilder.build();
    for (CppSource source : moduleInterfaceSources.values()) {
      Artifact sourceArtifact = source.getSource();

      Label sourceLabel = source.getLabel();
      CppCompileActionBuilder builder = initializeCompileAction(sourceArtifact);
      builder.setPicMode(usePic);
      builder.setActionName(CppActionNames.CPP20_MODULE_COMPILE);

      builder
          .addMandatoryInputs(additionalCompilationInputs)
          .addAdditionalIncludeScanningRoots(additionalIncludeScanningRoots);

      boolean bitcodeOutput =
          featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
              && CppFileTypes.LTO_SOURCE.matches(sourceArtifact.getFilename());

      String outputName = outputNameMap.get(sourceArtifact);
      Artifact.DerivedArtifact moduleFile = moduleFileMap.get(sourceArtifact);
      Preconditions.checkNotNull(moduleFile);
      builder.setAdditionalOutputs(ImmutableList.of(moduleFile));
      builder.setModuleFiles(allModuleFiles);
      if (usePic) {
        result.addPicCpp20ModuleFile(moduleFile);
      } else {
        result.addCpp20ModuleFile(moduleFile);
      }

      // all -fmodule-file=<module-name>=<path/to/bmi> flags are put in .modmap file
      var modmapFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext,
              label,
              CppHelper.getArtifactNameForCategory(
                  ccToolchain,
                  ArtifactCategory.CPP_MODULES_MODMAP,
                  getOutputNameBaseWith(outputName, usePic)),
              configuration);
      // all path/to/bmi are put in .modmap.input file,
      // which is convenient to get all bmi in CppCompileAction
      var modmapInputFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext,
              label,
              CppHelper.getArtifactNameForCategory(
                  ccToolchain,
                  ArtifactCategory.CPP_MODULES_MODMAP_INPUT,
                  getOutputNameBaseWith(outputName, usePic)),
              configuration);
      var ddiFile = ddiFileMap.get(sourceArtifact);
      Preconditions.checkNotNull(ddiFile);
      createGenModmapAction(ddiFile, modulesInfoFile, modmapFile, modmapInputFile);
      builder.setModmapFile(modmapFile);
      builder.setModmapInputFile(modmapInputFile);

      PathFragment ccRelativeName = sourceArtifact.getRootRelativePath();
      // TODO(plf): Continue removing CLIF logic from C++. Follow up changes would include
      // refactoring CppSource.Type and ArtifactCategory to be classes instead of enums
      // that could be instantiated with arbitrary values.
      ArtifactCategory outputCategory =
          source.getType() == CppSource.Type.CLIF_INPUT_PROTO
              ? ArtifactCategory.CLIF_OUTPUT_PROTO
              : ArtifactCategory.OBJECT_FILE;
      ImmutableMap<String, String> additionalBuildVariables =
          ImmutableMap.<String, String>builder()
              .put(
                  CompileBuildVariables.CPP_MODULE_OUTPUT_FILE.getVariableName(),
                  moduleFile.getExecPathString())
              .put(
                  CompileBuildVariables.CPP_MODULE_MODMAP_FILE.getVariableName(),
                  modmapFile.getExecPathString())
              .build();
      createSourceActionHelper(
          sourceLabel,
          outputName,
          result,
          sourceArtifact,
          builder,
          outputCategory,
          ccCompilationContext.getCppModuleMap(),
          /* addObject= */ true,
          isCodeCoverageEnabled,
          CcToolchainProvider.shouldCreatePerObjectDebugInfo(
              featureConfiguration, cppConfiguration),
          bitcodeOutput,
          ccRelativeName,
          usePic,
          additionalBuildVariables);
    }

    for (CppSource source : compilationUnitSources.values()) {
      Artifact sourceArtifact = source.getSource();

      Label sourceLabel = source.getLabel();
      CppCompileActionBuilder builder = initializeCompileAction(sourceArtifact);
      builder.setPicMode(usePic);
      builder
          .addMandatoryInputs(additionalCompilationInputs)
          .addAdditionalIncludeScanningRoots(additionalIncludeScanningRoots);

      boolean bitcodeOutput =
          featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
              && CppFileTypes.LTO_SOURCE.matches(sourceArtifact.getFilename());

      String outputName = outputNameMap.get(sourceArtifact);
      builder.setModuleFiles(allModuleFiles);

      // all -fmodule-file=<module-name>=<path/to/bmi> flags are put in .modmap file
      var modmapFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext,
              label,
              CppHelper.getArtifactNameForCategory(
                  ccToolchain,
                  ArtifactCategory.CPP_MODULES_MODMAP,
                  getOutputNameBaseWith(outputName, usePic)),
              configuration);
      // all path/to/bmi are put in .modmap.input file,
      // which is convenient to get all bmi in CppCompileAction
      var modmapInputFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext,
              label,
              CppHelper.getArtifactNameForCategory(
                  ccToolchain,
                  ArtifactCategory.CPP_MODULES_MODMAP_INPUT,
                  getOutputNameBaseWith(outputName, usePic)),
              configuration);
      var ddiOutputName =
          CppHelper.getArtifactNameForCategory(
              ccToolchain,
              ArtifactCategory.CPP_MODULES_DDI,
              getOutputNameBaseWith(outputName, usePic));
      Artifact ddiFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext, label, ddiOutputName, configuration);
      createScanDepsAction(source.getLabel(), sourceArtifact, usePic, ddiFile, ddiOutputName);
      createGenModmapAction(ddiFile, modulesInfoFile, modmapFile, modmapInputFile);
      builder.setModmapFile(modmapFile);
      builder.setModmapInputFile(modmapInputFile);

      PathFragment ccRelativeName = sourceArtifact.getRootRelativePath();
      // TODO(plf): Continue removing CLIF logic from C++. Follow up changes would include
      // refactoring CppSource.Type and ArtifactCategory to be classes instead of enums
      // that could be instantiated with arbitrary values.
      ArtifactCategory outputCategory =
          source.getType() == CppSource.Type.CLIF_INPUT_PROTO
              ? ArtifactCategory.CLIF_OUTPUT_PROTO
              : ArtifactCategory.OBJECT_FILE;
      ImmutableMap<String, String> additionalBuildVariables =
          ImmutableMap.<String, String>builder()
              .put(
                  CompileBuildVariables.CPP_MODULE_MODMAP_FILE.getVariableName(),
                  modmapFile.getExecPathString())
              .build();
      createSourceActionHelper(
          sourceLabel,
          outputName,
          result,
          sourceArtifact,
          builder,
          outputCategory,
          ccCompilationContext.getCppModuleMap(),
          /* addObject= */ true,
          isCodeCoverageEnabled,
          CcToolchainProvider.shouldCreatePerObjectDebugInfo(
              featureConfiguration, cppConfiguration),
          bitcodeOutput,
          ccRelativeName,
          usePic,
          additionalBuildVariables);
    }
  }

  /**
   * Constructs the C++ compiler actions. It generally creates one action for every specified source
   * file. It takes into account coverage, and PIC, in addition to using the settings specified on
   * the current object. This method should only be called once.
   */
  private CcCompilationOutputs createCcCompileActions()
      throws RuleErrorException, EvalException, InterruptedException {
    Preconditions.checkState(
        moduleInterfaceSources.isEmpty(),
        "to use C++20 Modules, the feature cpp20_module must be enabled");
    CcCompilationOutputs.Builder result = CcCompilationOutputs.builder();
    Preconditions.checkNotNull(ccCompilationContext);

    if (shouldProvideHeaderModules()) {
      CppModuleMap cppModuleMap = ccCompilationContext.getCppModuleMap();
      Label moduleMapLabel = Label.parseCanonicalUnchecked(cppModuleMap.getName());
      ImmutableList<Artifact> modules = createModuleAction(result, cppModuleMap);
      ImmutableList<Artifact> separateModules = ImmutableList.of();
      if (!separateModuleHeaders.isEmpty()) {
        CppModuleMap separateMap =
            new CppModuleMap(
                cppModuleMap.getArtifact(),
                cppModuleMap.getName() + CppModuleMap.SEPARATE_MODULE_SUFFIX);
        separateModules = createModuleAction(result, separateMap);
      }
      if (featureConfiguration.isEnabled(CppRuleClasses.HEADER_MODULE_CODEGEN)) {
        for (Artifact module : Iterables.concat(modules, separateModules)) {
          // TODO(djasper): Investigate whether we need to use a label separate from that of the
          // module map. It is used for per-file-copts.
          createModuleCodegenAction(result, moduleMapLabel, module);
        }
      }
    }

    String outputNamePrefixDir = null;
    // purpose is only used by objc rules, it ends with either "_non_objc_arc" or "_objc_arc".
    // Here we use it to distinguish arc and non-arc compilation.
    Preconditions.checkNotNull(purpose);
    if (purpose.endsWith("_objc_arc")) {
      outputNamePrefixDir = purpose.endsWith("_non_objc_arc") ? "non_arc" : "arc";
    }
    ImmutableMap<Artifact, String> outputNameMap =
        calculateOutputNameMapByType(compilationUnitSources, outputNamePrefixDir);

    Set<String> compiledBasenames = new HashSet<>();
    for (CppSource source : compilationUnitSources.values()) {
      Artifact sourceArtifact = source.getSource();

      // Headers compilations will be created in the loop below.
      if (!sourceArtifact.isTreeArtifact() && source.getType() == CppSource.Type.HEADER) {
        continue;
      }

      Label sourceLabel = source.getLabel();
      CppCompileActionBuilder builder = initializeCompileAction(sourceArtifact);

      builder
          .addMandatoryInputs(additionalCompilationInputs)
          .addAdditionalIncludeScanningRoots(additionalIncludeScanningRoots);

      boolean bitcodeOutput =
          featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
              && CppFileTypes.LTO_SOURCE.matches(sourceArtifact.getFilename());

      String outputName = outputNameMap.get(sourceArtifact);

      if (!sourceArtifact.isTreeArtifact()) {
        compiledBasenames.add(Files.getNameWithoutExtension(sourceArtifact.getExecPathString()));
        createSourceAction(
            sourceLabel,
            outputName,
            result,
            sourceArtifact,
            builder,
            // TODO(plf): Continue removing CLIF logic from C++. Follow up changes would include
            // refactoring CppSource.Type and ArtifactCategory to be classes instead of enums
            // that could be instantiated with arbitrary values.
            source.getType() == CppSource.Type.CLIF_INPUT_PROTO
                ? ArtifactCategory.CLIF_OUTPUT_PROTO
                : ArtifactCategory.OBJECT_FILE,
            ccCompilationContext.getCppModuleMap(),
            /* addObject= */ true,
            isCodeCoverageEnabled,
            // The source action does not generate dwo when it has bitcode
            // output (since it isn't generating a native object with debug
            // info). In that case the LtoBackendAction will generate the dwo.
            CcToolchainProvider.shouldCreatePerObjectDebugInfo(
                featureConfiguration, cppConfiguration),
            bitcodeOutput);
      } else {
        switch (source.getType()) {
          case HEADER:
            Artifact headerTokenFile =
                createCompileActionTemplate(
                    source,
                    outputName,
                    builder,
                    result,
                    ImmutableList.of(
                        ArtifactCategory.GENERATED_HEADER, ArtifactCategory.PROCESSED_HEADER),
                    // If we generate pic actions, we prefer the header actions to use the pic mode.
                    generatePicAction,
                    bitcodeOutput);
            result.addHeaderTokenFile(headerTokenFile);
            break;
          case SOURCE:
            if (generateNoPicAction) {
              Artifact objectFile =
                  createCompileActionTemplate(
                      source,
                      outputName,
                      builder,
                      result,
                      ImmutableList.of(ArtifactCategory.OBJECT_FILE),
                      /* usePic= */ false,
                      featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO));
              result.addObjectFile(objectFile);
            }

            if (generatePicAction) {
              Artifact picObjectFile =
                  createCompileActionTemplate(
                      source,
                      outputName,
                      builder,
                      result,
                      ImmutableList.of(ArtifactCategory.PIC_OBJECT_FILE),
                      /* usePic= */ true,
                      featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO));
              result.addPicObjectFile(picObjectFile);
            }
            break;
          default:
            throw new IllegalStateException(
                "Encountered invalid source types when creating CppCompileActionTemplates");
        }
      }
    }
    for (CppSource source : compilationUnitSources.values()) {
      Artifact artifact = source.getSource();
      if (source.getType() != CppSource.Type.HEADER || artifact.isTreeArtifact()) {
        // These are already handled above.
        continue;
      }
      if (featureConfiguration.isEnabled(CppRuleClasses.VALIDATES_LAYERING_CHECK_IN_TEXTUAL_HDRS)
          && compiledBasenames.contains(
              Files.getNameWithoutExtension(artifact.getExecPathString()))) {
        continue;
      }
      CppCompileActionBuilder builder = initializeCompileAction(artifact);
      builder
          .addMandatoryInputs(additionalCompilationInputs)
          .addAdditionalIncludeScanningRoots(additionalIncludeScanningRoots);

      String outputName = outputNameMap.get(artifact);
      createHeaderAction(source.getLabel(), outputName, result, builder);
    }

    return result.build();
  }

  private Artifact createCompileActionTemplate(
      CppSource source,
      String outputName,
      CppCompileActionBuilder builder,
      CcCompilationOutputs.Builder result,
      ImmutableList<ArtifactCategory> outputCategories,
      boolean usePic,
      boolean bitcodeOutput)
      throws RuleErrorException, EvalException, InterruptedException {
    if (usePic) {
      builder = new CppCompileActionBuilder(builder).setPicMode(true);
    }
    SpecialArtifact sourceArtifact = (SpecialArtifact) source.getSource();
    SpecialArtifact outputFiles =
        CppHelper.getCompileOutputTreeArtifact(
            actionConstructionContext, label, sourceArtifact, outputName, usePic);
    // Dotd and dia file outputs are specified in the execution phase.
    builder.setOutputs(outputFiles, /* dotdFile= */ null, /* diagnosticsFile= */ null);
    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            /* sourceLabel= */ null,
            usePic,
            /* needsFdoBuildVariables= */ false,
            ccCompilationContext.getCppModuleMap(),
            /* enableCoverage= */ false,
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    // Make sure this builder doesn't reference ruleContext outside of analysis phase.
    SpecialArtifact dotdTreeArtifact = null;
    if (builder.dotdFilesEnabled()) {
      dotdTreeArtifact =
          CppHelper.getDotdOutputTreeArtifact(
              actionConstructionContext, label, sourceArtifact, outputName, usePic);
    }
    SpecialArtifact diagnosticsTreeArtifact = null;
    if (builder.serializedDiagnosticsFilesEnabled()) {
      diagnosticsTreeArtifact =
          CppHelper.getDiagnosticsOutputTreeArtifact(
              actionConstructionContext, label, sourceArtifact, outputName, usePic);
    }

    // Currently we do not generate minimized bitcode files for tree artifacts because of issues
    // with the indexing step.
    // If ltoIndexTreeArtifact is set to a tree artifact, the minimized bitcode files will be
    // properly generated and will be an input to the indexing step. However, the lto indexing step
    // fails. The indexing step finds the full bitcode file by replacing the suffix of the
    // minimized bitcode file, therefore they have to be in the same directory.
    // Since the files are in the same directory, the command line artifact expander expands the
    // tree artifact to both the minimized bitcode files and the full bitcode files, causing an
    // error that functions are defined twice.
    // TODO(b/289071777): support for minimized bitcode files.
    SpecialArtifact ltoIndexTreeArtifact = null;

    if (bitcodeOutput) {
      Label sourceLabel = source.getLabel();
      result.addLtoBitcodeFile(
          outputFiles, ltoIndexTreeArtifact, getCopts(sourceArtifact, sourceLabel));
    }

    ActionOwner actionOwner = null;
    if (actionConstructionContext instanceof RuleContext ruleContext
        && ruleContext.useAutoExecGroups()) {
      actionOwner = actionConstructionContext.getActionOwner(semantics.getCppToolchainType());
    }
    try {
      CppCompileActionTemplate actionTemplate =
          new CppCompileActionTemplate(
              sourceArtifact,
              outputFiles,
              dotdTreeArtifact,
              diagnosticsTreeArtifact,
              ltoIndexTreeArtifact,
              builder,
              ccToolchain,
              outputCategories,
              actionOwner == null ? actionConstructionContext.getActionOwner() : actionOwner);
      actionConstructionContext.registerAction(actionTemplate);
    } catch (EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }

    return outputFiles;
  }

  /**
   * Return flags that were specified on the Blaze command line. Take the filetype of sourceFilename
   * into account.
   */
  public static ImmutableList<String> getCoptsFromOptions(
      CppConfiguration config, CppSemantics semantics, String sourceFilename) {
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();

    flagsBuilder.addAll(config.getCopts());

    if (CppFileTypes.C_SOURCE.matches(sourceFilename)) {
      flagsBuilder.addAll(config.getConlyopts());
    }

    if (CppFileTypes.CPP_SOURCE.matches(sourceFilename)
        || CppFileTypes.CPP_HEADER.matches(sourceFilename)
        || CppFileTypes.CPP_MODULE_MAP.matches(sourceFilename)
        || CppFileTypes.OBJCPP_SOURCE.matches(sourceFilename)
        || CppFileTypes.CLIF_INPUT_PROTO.matches(sourceFilename)) {
      flagsBuilder.addAll(config.getCxxopts());
    }

    if (CppFileTypes.OBJC_SOURCE.matches(sourceFilename)
        || CppFileTypes.OBJCPP_SOURCE.matches(sourceFilename)
        || (CppFileTypes.CPP_HEADER.matches(sourceFilename)
            && semantics.language() == Language.OBJC)) {
      flagsBuilder.addAll(config.getObjcopts());
    }

    return flagsBuilder.build();
  }

  private ImmutableList<String> getCopts(Artifact sourceFile, Label sourceLabel) {
    ImmutableList.Builder<String> coptsList = ImmutableList.builder();
    String sourceFilename = sourceFile.getExecPathString();
    coptsList.addAll(getCoptsFromOptions(cppConfiguration, semantics, sourceFilename));
    coptsList.addAll(copts);

    if (CppFileTypes.C_SOURCE.matches(sourceFilename)) {
      coptsList.addAll(conlyopts);
    }

    if (CppFileTypes.CPP_SOURCE.matches(sourceFilename)
        || CppFileTypes.CPP_HEADER.matches(sourceFilename)
        || CppFileTypes.CPP_MODULE_MAP.matches(sourceFilename)
        || CppFileTypes.CLIF_INPUT_PROTO.matches(sourceFilename)
        || CppFileTypes.OBJCPP_SOURCE.matches(sourceFilename)) {
      coptsList.addAll(cxxopts);
    }

    if (sourceFile != null && sourceLabel != null) {
      coptsList.addAll(collectPerFileCopts(sourceFile, sourceLabel));
    }
    return coptsList.build();
  }

  private CcToolchainVariables setupCompileBuildVariables(
      CppCompileActionBuilder builder,
      Label sourceLabel,
      boolean usePic,
      boolean needsFdoBuildVariables,
      CppModuleMap cppModuleMap,
      boolean enableCoverage,
      Artifact gcnoFile,
      boolean isUsingFission,
      Artifact dwoFile,
      Artifact ltoIndexingFile,
      ImmutableMap<String, String> additionalBuildVariables)
      throws RuleErrorException, EvalException, InterruptedException {
    Artifact sourceFile = builder.getSourceFile();
    if (needsFdoBuildVariables && fdoContext.hasArtifacts()) {
      // This modifies the passed-in builder, which is a surprising side-effect, and makes it unsafe
      // to call this method multiple times for the same builder.
      builder.addMandatoryInputs(getAuxiliaryFdoInputs());
    }
    CcToolchainVariables parent = needsFdoBuildVariables ? prebuiltParentWithFdo : prebuiltParent;
    // We use the prebuilt parent variables if and only if the passed in cppModuleMap is the
    // identical to the one returned from ccCompilationContext.getCppModuleMap(): there is exactly
    // one caller which passes in any other value (the verification module map), so this should be
    // fine for now.
    boolean usePrebuiltParent =
        cppModuleMap == ccCompilationContext.getCppModuleMap()
            // Only use the prebuilt parent if there are enough sources to make it worthwhile. The
            // threshold was chosen by looking at a heap dump.
            && (compilationUnitSources.size() > 1);
    CcToolchainVariables.Builder buildVariables;
    if (parent != null && usePrebuiltParent) {
      // If we have a pre-built parent and we are allowed to use it, then do so.
      buildVariables = CcToolchainVariables.builder(parent);
    } else {
      Map<String, String> genericAdditionalBuildVariables = new LinkedHashMap<>();
      if (needsFdoBuildVariables) {
        configureFdoBuildVariables(
            genericAdditionalBuildVariables,
            cppConfiguration.getFdoInstrument(),
            cppConfiguration.getCSFdoInstrument());
      }
      CcToolchainVariables cctoolchainVariables;
      try {
        cctoolchainVariables = ccToolchain.getBuildVars();
      } catch (EvalException e) {
        throw new RuleErrorException(e.getMessage());
      }
      boolean isUsingMemProf = false;
      if (fdoContext != null && fdoContext.getMemProfProfileArtifact() != null) {
        isUsingMemProf = true;
      }
      buildVariables = CcToolchainVariables.builder(cctoolchainVariables);
      CompileBuildVariables.setupCommonVariables(
          buildVariables,
          featureConfiguration,
          ImmutableList.of(),
          cppModuleMap,
          CppHelper.getFdoBuildStamp(cppConfiguration, fdoContext, featureConfiguration),
          isUsingMemProf,
          variablesExtensions,
          genericAdditionalBuildVariables,
          ccCompilationContext.getDirectModuleMaps(),
          ccCompilationContext.getIncludeDirs(),
          ccCompilationContext.getQuoteIncludeDirs(),
          ccCompilationContext.getSystemIncludeDirs(),
          ccCompilationContext.getFrameworkIncludeDirs(),
          ccCompilationContext.getDefines(),
          ccCompilationContext.getNonTransitiveDefines());

      if (usePrebuiltParent) {
        parent = buildVariables.build();
        if (needsFdoBuildVariables) {
          prebuiltParentWithFdo = parent;
        } else {
          prebuiltParent = parent;
        }
        buildVariables = CcToolchainVariables.builder(parent);
      }
    }
    if (usePic
        && !featureConfiguration.isEnabled(CppRuleClasses.PIC)
        && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)) {
      ruleErrorConsumer.ruleError(CcCommon.PIC_CONFIGURATION_ERROR);
    }

    CompileBuildVariables.setupSpecificVariables(
        buildVariables,
        sourceFile,
        builder.getOutputFile(),
        enableCoverage,
        gcnoFile,
        dwoFile,
        isUsingFission,
        ltoIndexingFile,
        getCopts(builder.getSourceFile(), sourceLabel),
        builder.getDotdFile(),
        builder.getDiagnosticsFile(),
        usePic,
        ccCompilationContext.getExternalIncludeDirs(),
        additionalBuildVariables);
    return buildVariables.build();
  }

  /**
   * Returns a {@code CppCompileActionBuilder} with the common fields for a C++ compile action being
   * initialized.
   */
  private CppCompileActionBuilder initializeCompileAction(Artifact sourceArtifact) {
    return new CppCompileActionBuilder(
            actionConstructionContext, ccToolchain, configuration, semantics)
        .setSourceFile(sourceArtifact)
        .setCcCompilationContext(ccCompilationContext)
        .setCoptsFilter(coptsFilter)
        .setFeatureConfiguration(featureConfiguration)
        .addExecutionInfo(executionInfo);
  }

  private void createModuleCodegenAction(
      CcCompilationOutputs.Builder result, Label sourceLabel, Artifact module)
      throws RuleErrorException, EvalException, InterruptedException {
    String outputName = module.getRootRelativePath().getBaseName();

    // TODO(djasper): Make this less hacky after refactoring how the PIC/noPIC actions are created.
    boolean pic = module.getFilename().contains(".pic.");

    CppCompileActionBuilder builder = initializeCompileAction(module);
    builder.setPicMode(pic);
    builder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        ArtifactCategory.OBJECT_FILE,
        outputName);
    PathFragment ccRelativeName = module.getRootRelativePath();

    String gcnoFileName =
        CppHelper.getArtifactNameForCategory(
            ccToolchain, ArtifactCategory.COVERAGE_DATA_FILE, outputName);
    // TODO(djasper): This is now duplicated. Refactor the various create..Action functions.
    Artifact gcnoFile =
        isCodeCoverageEnabled && !cppConfiguration.useLLVMCoverageMapFormat()
            ? CppHelper.getCompileOutputArtifact(
                actionConstructionContext, label, gcnoFileName, configuration)
            : null;

    boolean generateDwo =
        CcToolchainProvider.shouldCreatePerObjectDebugInfo(featureConfiguration, cppConfiguration);
    Artifact dwoFile = generateDwo ? getDwoFile(builder.getOutputFile()) : null;
    // TODO(tejohnson): Add support for ThinLTO if needed.
    boolean bitcodeOutput =
        featureConfiguration.isEnabled(CppRuleClasses.THIN_LTO)
            && CppFileTypes.LTO_SOURCE.matches(module.getFilename());
    Preconditions.checkState(!bitcodeOutput);

    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            /* usePic= */ pic,
            /* needsFdoBuildVariables= */ ccRelativeName != null,
            ccCompilationContext.getCppModuleMap(),
            isCodeCoverageEnabled,
            gcnoFile,
            generateDwo,
            dwoFile,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));

    builder.setGcnoFile(gcnoFile);
    builder.setDwoFile(dwoFile);

    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(compileAction);
    Artifact objectFile = compileAction.getPrimaryOutput();
    if (pic) {
      result.addPicObjectFile(objectFile);
    } else {
      result.addObjectFile(objectFile);
    }
  }

  private void createHeaderAction(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      CppCompileActionBuilder builder)
      throws RuleErrorException, EvalException, InterruptedException {
    String outputNameBase =
        CppHelper.getArtifactNameForCategory(
            ccToolchain, ArtifactCategory.GENERATED_HEADER, outputName);

    builder
        .setOutputs(
            actionConstructionContext,
            ruleErrorConsumer,
            label,
            ArtifactCategory.PROCESSED_HEADER,
            outputNameBase)
        // If we generate pic actions, we prefer the header actions to use the pic artifacts.
        .setPicMode(generatePicAction);
    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            generatePicAction,
            /* needsFdoBuildVariables= */ false,
            ccCompilationContext.getCppModuleMap(),
            /* enableCoverage= */ false,
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            /* additionalBuildVariables= */ ImmutableMap.of()));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(compileAction);
    Artifact tokenFile = compileAction.getPrimaryOutput();
    result.addHeaderTokenFile(tokenFile);
  }

  private void createScanDepsAction(
      Label sourceLabel,
      Artifact sourceArtifact,
      boolean usePic,
      Artifact ddiFile,
      String outputName)
      throws RuleErrorException, EvalException, InterruptedException {
    var scanDepsBuilder = initializeCompileAction(sourceArtifact);
    scanDepsBuilder.setActionName(CppActionNames.CPP_MODULE_DEPS_SCANNING);
    Artifact dotdFile;
    if (scanDepsBuilder.dotdFilesEnabled() && scanDepsBuilder.useDotdFile(sourceArtifact)) {
      String dotdFileName =
          CppHelper.getArtifactNameForCategory(
              ccToolchain, ArtifactCategory.INCLUDED_FILE_LIST, outputName);
      dotdFile =
          CppHelper.getCompileOutputArtifact(
              actionConstructionContext, label, dotdFileName, configuration);
    } else {
      dotdFile = null;
    }
    scanDepsBuilder.setOutputs(ddiFile, dotdFile, null);
    var variables =
        setupCompileBuildVariables(
            scanDepsBuilder,
            sourceLabel,
            usePic,
            /* needsFdoBuildVariables= */ false,
            cppModuleMap,
            /* enableCoverage= */ false,
            null,
            false,
            null,
            null,
            /* additionalBuildVariables= */ ImmutableMap.of());

    scanDepsBuilder.setVariables(variables);
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, scanDepsBuilder);
    var scanDepsAction = scanDepsBuilder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(scanDepsAction);
  }

  private void createGenModmapAction(
      Artifact ddiFile, Artifact modulesInfoFile, Artifact modmapFile, Artifact modmapInputFile)
      throws EvalException {
    var genModmapTool = ccToolchain.getGenerateModmap();
    Preconditions.checkNotNull(genModmapTool, "the tool 'generate-modmap' cannot be null");
    var genModmapAction =
        new SpawnAction(
            actionConstructionContext.getActionOwner(),
            NestedSetBuilder.<Artifact>stableOrder().add(genModmapTool).build(),
            NestedSetBuilder.<Artifact>stableOrder()
                .add(genModmapTool)
                .add(ddiFile)
                .add(modulesInfoFile)
                .build(),
            ImmutableList.of(modmapFile, modmapInputFile),
            AbstractAction.DEFAULT_RESOURCE_SET,
            CommandLines.builder()
                .addSingleArgument(genModmapTool.getExecPathString())
                .addSingleArgument(ddiFile.getExecPathString())
                .addSingleArgument(modulesInfoFile.getExecPathString())
                .addSingleArgument(modmapFile.getExecPathString())
                .addSingleArgument(ccToolchain.getCompiler())
                .build(),
            ActionEnvironment.EMPTY,
            ImmutableMap.of(),
            "Generating modmap with " + ddiFile.getFilename(),
            "GenModmap",
            PathMappers.getOutputPathsMode(configuration));
    actionConstructionContext.registerAction(genModmapAction);
  }

  private void createAggDdiAction(
      NestedSet<Artifact> ddiFiles,
      ImmutableList<CommandLine> commandLines,
      NestedSet<Artifact> modulesInfoFiles,
      Artifact modulesInfoFile)
      throws EvalException {
    var aggDdiTool = ccToolchain.getAggregateDdi();
    Preconditions.checkNotNull(aggDdiTool, "the tool 'aggregate-ddi' cannot be null");
    CommandLines.Builder builder = CommandLines.builder();
    builder.addSingleArgument(aggDdiTool.getExecPathString());
    for (CommandLine commandLine : commandLines) {
      builder.addCommandLine(commandLine);
    }
    for (Artifact depModulesInfoFile : modulesInfoFiles.toList()) {
      builder.addCommandLine(
          CommandLine.of(ImmutableList.of("-m", depModulesInfoFile.getExecPathString())));
    }
    builder.addCommandLine(
        CommandLine.of(ImmutableList.of("-o", modulesInfoFile.getExecPathString())));
    var aggDdiAction =
        new SpawnAction(
            actionConstructionContext.getActionOwner(),
            NestedSetBuilder.<Artifact>stableOrder().add(aggDdiTool).build(),
            NestedSetBuilder.<Artifact>stableOrder()
                .add(aggDdiTool)
                .addTransitive(ddiFiles)
                .addTransitive(modulesInfoFiles)
                .build(),
            ImmutableList.of(modulesInfoFile),
            AbstractAction.DEFAULT_RESOURCE_SET,
            builder.build(),
            ActionEnvironment.EMPTY,
            ImmutableMap.of(),
            "Generating Cpp20ModulesInfo " + modulesInfoFile.getFilename(),
            "AggDdi",
            PathMappers.getOutputPathsMode(configuration));
    actionConstructionContext.registerAction(aggDdiAction);
  }

  private ImmutableList<Artifact> createModuleAction(
      CcCompilationOutputs.Builder result, CppModuleMap cppModuleMap)
      throws RuleErrorException, EvalException, InterruptedException {
    Artifact moduleMapArtifact = cppModuleMap.getArtifact();
    CppCompileActionBuilder builder = initializeCompileAction(moduleMapArtifact);

    Label label = Label.parseCanonicalUnchecked(cppModuleMap.getName());

    // A header module compile action is just like a normal compile action, but:
    // - the compiled source file is the module map
    // - it creates a header module (.pcm file).
    return createSourceAction(
        label,
        Paths.get(label.getName()).getFileName().toString(),
        result,
        moduleMapArtifact,
        builder,
        ArtifactCategory.CPP_MODULE,
        cppModuleMap,
        /* addObject= */ false,
        /* enableCoverage= */ false,
        /* generateDwo= */ false,
        /* bitcodeOutput= */ false);
  }

  @CanIgnoreReturnValue
  private ImmutableList<Artifact> createSourceAction(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      Artifact sourceArtifact,
      CppCompileActionBuilder builder,
      ArtifactCategory outputCategory,
      CppModuleMap cppModuleMap,
      boolean addObject,
      boolean enableCoverage,
      boolean generateDwo,
      boolean bitcodeOutput)
      throws RuleErrorException, EvalException, InterruptedException {
    ImmutableList.Builder<Artifact> directOutputs = new ImmutableList.Builder<>();
    PathFragment ccRelativeName = sourceArtifact.getRootRelativePath();

    // Create PIC compile actions (same as no-PIC, but use -fPIC and
    // generate .pic.o, .pic.d, .pic.gcno instead of .o, .d, .gcno.)
    if (generatePicAction) {
      CppCompileActionBuilder picBuilder = new CppCompileActionBuilder(builder);
      picBuilder.setPicMode(true);
      Artifact picOutputFile =
          createSourceActionHelper(
              sourceLabel,
              outputName,
              result,
              sourceArtifact,
              picBuilder,
              outputCategory,
              cppModuleMap,
              addObject,
              enableCoverage,
              generateDwo,
              bitcodeOutput,
              ccRelativeName,
              /* usePic= */ true,
              /* additionalBuildVariables= */ ImmutableMap.of());
      directOutputs.add(picOutputFile);
      if (outputCategory == ArtifactCategory.CPP_MODULE) {
        result.addModuleFile(picOutputFile);
      }
    }

    if (generateNoPicAction) {
      Artifact noPicOutputFile =
          createSourceActionHelper(
              sourceLabel,
              outputName,
              result,
              sourceArtifact,
              builder,
              outputCategory,
              cppModuleMap,
              addObject,
              enableCoverage,
              generateDwo,
              bitcodeOutput,
              ccRelativeName,
              /* usePic= */ false,
              /* additionalBuildVariables= */ ImmutableMap.of());
      directOutputs.add(noPicOutputFile);
      if (outputCategory == ArtifactCategory.CPP_MODULE) {
        result.addModuleFile(noPicOutputFile);
      }
    }
    return directOutputs.build();
  }

  private Artifact createSourceActionHelper(
      Label sourceLabel,
      String outputName,
      CcCompilationOutputs.Builder result,
      Artifact sourceArtifact,
      CppCompileActionBuilder builder,
      ArtifactCategory outputCategory,
      CppModuleMap cppModuleMap,
      boolean addObject,
      boolean enableCoverage,
      boolean generateDwo,
      boolean bitcodeOutput,
      PathFragment ccRelativeName,
      boolean usePic,
      ImmutableMap<String, String> additionalBuildVariables)
      throws RuleErrorException, EvalException, InterruptedException {
    builder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        outputCategory,
        getOutputNameBaseWith(outputName, usePic));
    String gcnoFileName =
        CppHelper.getArtifactNameForCategory(
            ccToolchain,
            ArtifactCategory.COVERAGE_DATA_FILE,
            getOutputNameBaseWith(outputName, usePic));

    Artifact gcnoFile =
        enableCoverage && !cppConfiguration.useLLVMCoverageMapFormat()
            ? CppHelper.getCompileOutputArtifact(
                actionConstructionContext, label, gcnoFileName, configuration)
            : null;

    Artifact dwoFile = generateDwo && !bitcodeOutput ? getDwoFile(builder.getOutputFile()) : null;
    Artifact ltoIndexingFile = bitcodeOutput ? getLtoIndexingFile(builder.getOutputFile()) : null;

    builder.setVariables(
        setupCompileBuildVariables(
            builder,
            sourceLabel,
            usePic,
            /* needsFdoBuildVariables= */ ccRelativeName != null && addObject,
            cppModuleMap,
            enableCoverage,
            gcnoFile,
            generateDwo,
            dwoFile,
            ltoIndexingFile,
            additionalBuildVariables));

    result.addTemps(
        createTempsActions(
            sourceArtifact, sourceLabel, outputName, builder, usePic, ccRelativeName));

    builder.setGcnoFile(gcnoFile);
    builder.setDwoFile(dwoFile);
    builder.setLtoIndexingFile(ltoIndexingFile);

    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, builder);
    CppCompileAction compileAction = builder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(compileAction);
    Artifact objectFile = compileAction.getPrimaryOutput();
    if (usePic) {
      if (addObject) {
        result.addPicObjectFile(objectFile);
      }
      if (dwoFile != null) {
        // Exec configuration targets don't produce .dwo files.
        result.addPicDwoFile(dwoFile);
      }
      if (gcnoFile != null) {
        result.addPicGcnoFile(gcnoFile);
      }
    } else {
      if (addObject) {
        result.addObjectFile(objectFile);
      }
      if (dwoFile != null) {
        // Exec configuration targets don't produce .dwo files.
        result.addDwoFile(dwoFile);
      }
      if (gcnoFile != null) {
        result.addGcnoFile(gcnoFile);
      }
    }
    if (addObject && bitcodeOutput) {
      result.addLtoBitcodeFile(objectFile, ltoIndexingFile, getCopts(sourceArtifact, sourceLabel));
    }
    return objectFile;
  }

  String getOutputNameBaseWith(String base, boolean usePic) throws RuleErrorException {
    return usePic
        ? CppHelper.getArtifactNameForCategory(ccToolchain, ArtifactCategory.PIC_FILE, base)
        : base;
  }

  /** Returns true iff code coverage is enabled for the given target. */
  public static boolean isCodeCoverageEnabled(RuleContext ruleContext) {
    BuildConfigurationValue configuration = ruleContext.getConfiguration();
    if (configuration.isCodeCoverageEnabled()) {
      // If rule is matched by the instrumentation filter, enable instrumentation
      if (InstrumentedFilesCollector.shouldIncludeLocalSources(
          configuration, ruleContext.getLabel(), ruleContext.isTestTarget())) {
        return true;
      }
      // At this point the rule itself is not matched by the instrumentation filter. However, we
      // might still want to instrument C++ rules if one of the targets listed in "deps" is
      // instrumented and, therefore, can supply header files that we would want to collect code
      // coverage for. For example, think about cc_test rule that tests functionality defined in a
      // header file that is supplied by the cc_library.
      //
      // Note that we only check direct prerequisites and not the transitive closure. This is done
      // for two reasons:
      // a) It is a good practice to declare libraries which you directly rely on. Including headers
      //    from a library hidden deep inside the transitive closure makes build dependencies less
      //    readable and can lead to unexpected breakage.
      // b) Traversing the transitive closure for each C++ compile action would require more complex
      //    implementation (with caching results of this method) to avoid O(N^2) slowdown.
      if (ruleContext.getRule().isAttrDefined("deps", BuildType.LABEL_LIST)) {
        for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("deps")) {
          CcInfo ccInfo = dep.get(CcInfo.PROVIDER);
          if (ccInfo != null
              && InstrumentedFilesCollector.shouldIncludeLocalSources(configuration, dep)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private ImmutableList<String> collectPerFileCopts(Artifact sourceFile, Label sourceLabel) {
    return cppConfiguration.getPerFileCopts().stream()
        .filter(
            perLabelOptions ->
                (sourceLabel != null && perLabelOptions.isIncluded(sourceLabel))
                    || perLabelOptions.isIncluded(sourceFile))
        .map(PerLabelOptions::getOptions)
        .flatMap(options -> options.stream())
        .collect(ImmutableList.toImmutableList());
  }

  private Artifact getDwoFile(Artifact outputFile) {
    return actionConstructionContext.getRelatedArtifact(
        outputFile.getOutputDirRelativePath(configuration.isSiblingRepositoryLayout()), ".dwo");
  }

  @Nullable
  private Artifact getLtoIndexingFile(Artifact outputFile) {
    if (featureConfiguration.isEnabled(CppRuleClasses.NO_USE_LTO_INDEXING_BITCODE_FILE)) {
      return null;
    }
    String ext = Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions());
    return actionConstructionContext.getRelatedArtifact(
        outputFile.getOutputDirRelativePath(configuration.isSiblingRepositoryLayout()), ext);
  }

  /** Create the actions for "--save_temps". */
  private ImmutableList<Artifact> createTempsActions(
      Artifact source,
      Label sourceLabel,
      String outputName,
      CppCompileActionBuilder builder,
      boolean usePic,
      PathFragment ccRelativeName)
      throws RuleErrorException, EvalException, InterruptedException {
    if (!cppConfiguration.getSaveTemps()) {
      return ImmutableList.of();
    }

    String path = source.getFilename();
    boolean isCFile = CppFileTypes.C_SOURCE.matches(path);
    boolean isCppFile = CppFileTypes.CPP_SOURCE.matches(path);
    boolean isObjcFile = CppFileTypes.OBJC_SOURCE.matches(path);
    boolean isObjcppFile = CppFileTypes.OBJCPP_SOURCE.matches(path);

    if (!isCFile && !isCppFile && !isObjcFile && !isObjcppFile) {
      return ImmutableList.of();
    }

    ArtifactCategory category =
        isCFile ? ArtifactCategory.PREPROCESSED_C_SOURCE : ArtifactCategory.PREPROCESSED_CPP_SOURCE;

    String outputArtifactNameBase = getOutputNameBaseWith(outputName, usePic);

    CppCompileActionBuilder dBuilder = new CppCompileActionBuilder(builder);
    dBuilder.setOutputs(
        actionConstructionContext, ruleErrorConsumer, label, category, outputArtifactNameBase);
    dBuilder.setVariables(
        setupCompileBuildVariables(
            dBuilder,
            sourceLabel,
            usePic,
            /* needsFdoBuildVariables= */ ccRelativeName != null,
            ccCompilationContext.getCppModuleMap(),
            /* enableCoverage= */ false,
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            ImmutableMap.of(
                CompileBuildVariables.OUTPUT_PREPROCESS_FILE.getVariableName(),
                dBuilder.getRealOutputFilePath().getSafePathString())));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, dBuilder);
    CppCompileAction dAction = dBuilder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(dAction);

    CppCompileActionBuilder sdBuilder = new CppCompileActionBuilder(builder);
    sdBuilder.setOutputs(
        actionConstructionContext,
        ruleErrorConsumer,
        label,
        ArtifactCategory.GENERATED_ASSEMBLY,
        outputArtifactNameBase);
    sdBuilder.setVariables(
        setupCompileBuildVariables(
            sdBuilder,
            sourceLabel,
            usePic,
            /* needsFdoBuildVariables= */ ccRelativeName != null,
            ccCompilationContext.getCppModuleMap(),
            /* enableCoverage= */ false,
            /* gcnoFile= */ null,
            /* isUsingFission= */ false,
            /* dwoFile= */ null,
            /* ltoIndexingFile= */ null,
            ImmutableMap.of(
                CompileBuildVariables.OUTPUT_ASSEMBLY_FILE.getVariableName(),
                sdBuilder.getRealOutputFilePath().getSafePathString())));
    semantics.finalizeCompileActionBuilder(configuration, featureConfiguration, sdBuilder);
    CppCompileAction sdAction = sdBuilder.buildOrThrowRuleError(ruleErrorConsumer);
    actionConstructionContext.registerAction(sdAction);

    return ImmutableList.of(dAction.getPrimaryOutput(), sdAction.getPrimaryOutput());
  }
}
