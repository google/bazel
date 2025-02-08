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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.extra.CppCompileInfo;
import com.google.devtools.build.lib.actions.extra.EnvironmentVariable;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.actions.PathMappers;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.server.FailureDetails.CppCompile;
import com.google.devtools.build.lib.server.FailureDetails.CppCompile.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.IORuntimeException;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

/** Action that represents some kind of C++ compilation step. */
@ThreadCompatible
public class CppCompileAction extends AbstractAction implements IncludeScannable, CommandAction {

  private static final UUID GUID = UUID.fromString("97493805-894f-493a-be66-9a698f45c31d");

  @VisibleForTesting static final String CPP_COMPILE_MNEMONIC = "CppCompile";
  @VisibleForTesting static final String OBJC_COMPILE_MNEMONIC = "ObjcCompile";

  @Nullable private final Artifact gcnoFile;
  private final Artifact sourceFile;
  private final BuildConfigurationValue configuration;
  private final NestedSet<Artifact> mandatoryInputs;
  private final NestedSet<Artifact> mandatorySpawnInputs;
  private final NestedSet<Artifact> allowedDerivedInputs;

  /**
   * The set of input files that in addition to {@link CcCompilationContext#getDeclaredIncludeSrcs}
   * need to be added to the set of input artifacts of the action if we don't use input discovery.
   * They may be pruned after execution. See {@link #findUsedHeaders} for more details.
   */
  private final NestedSet<Artifact> additionalPrunableHeaders;

  @Nullable private final Artifact grepIncludes;
  private final boolean shareable;
  private final boolean shouldScanIncludes;
  private final boolean usePic;
  private final boolean useHeaderModules;
  private final boolean needsIncludeValidation;

  private final CcCompilationContext ccCompilationContext;
  private final ImmutableList<Artifact> builtinIncludeFiles;
  // A list of files to include scan that are not source files, pcm files, or
  // included via a command-line "-include file.h". Actions that use non C++ files as source
  // files--such as Clif--may use this mechanism.
  private final ImmutableList<Artifact> additionalIncludeScanningRoots;
  @VisibleForTesting public final CompileCommandLine compileCommandLine;

  /**
   * The fingerprint of {@link #compileCommandLine}. This is computed lazily so that the command
   * line is not unnecessarily flattened outside of action execution.
   */
  private byte[] commandLineKey;

  private final ImmutableMap<String, String> executionInfo;
  private final String actionName;

  private final FeatureConfiguration featureConfiguration;

  private final ImmutableList<PathFragment> builtInIncludeDirectories;

  // TODO(b/213594908): Make CppCompileAction immutable.
  /**
   * Set when the action prepares for execution. Used to preserve state between preparation and
   * execution.
   */
  private NestedSet<Artifact> additionalInputs;

  /**
   * Used only during input discovery, when input discovery requires other actions to be executed
   * first.
   */
  private Set<DerivedArtifact> usedModules;

  private boolean inputsDiscovered = false;

  /**
   * This field is set only for C++ module compiles (compiling .cppmap files into .pcm files). It
   * stores the modules necessary for building this module as they will later also be required for
   * building users of this module. Such users can get to this data through this action's {@link
   * com.google.devtools.build.lib.skyframe.ActionExecutionValue}
   *
   * <p>This field is populated either based on the discovered headers in {@link #discoverInputs} or
   * extracted from the action inputs when restoring it from the action cache.
   */
  private NestedSet<Artifact> discoveredModules = null;

  /** Used modules that are not transitively used through other topLevelModules. */
  private NestedSet<Artifact> topLevelModules;

  private ParamFileActionInput paramFileActionInput;
  private PathFragment paramFilePath;

  /**
   * Creates a new action to compile C/C++ source files.
   *
   * @param owner the owner of the action, usually the configured target that emitted it
   * @param featureConfiguration TODO(bazel-team): Add parameter description.
   * @param variables TODO(bazel-team): Add parameter description.
   * @param sourceFile the source file that should be compiled. {@code mandatoryInputs} must contain
   *     this file
   * @param shouldScanIncludes a boolean indicating whether scanning of {@code sourceFile} is to be
   *     performed looking for inclusions.
   * @param usePic TODO(bazel-team): Add parameter description.
   * @param mandatoryInputs any additional files that need to be present for the compilation to
   *     succeed, can be empty but not null, for example, extra sources for FDO.
   * @param outputFile the object file that is written as result of the compilation
   * @param dotdFile the .d file that is generated as a side-effect of compilation
   * @param diagnosticsFile the .dia file that is generated as a side-effect of compilation
   * @param gcnoFile the coverage notes that are written in coverage mode, can be null
   * @param dwoFile the .dwo output file where debug information is stored for Fission builds (null
   *     if Fission mode is disabled)
   * @param ccCompilationContext the {@code CcCompilationContext}
   * @param coptsFilter regular expression to remove options from {@code copts}
   * @param additionalIncludeScanningRoots list of additional artifacts to include-scan
   * @param actionName a string giving the name of this action for the purpose of toolchain
   *     evaluation
   * @param cppSemantics C++ compilation semantics
   * @param builtInIncludeDirectories - list of toolchain-defined builtin include directories.
   */
  CppCompileAction(
      ActionOwner owner,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables variables,
      Artifact sourceFile,
      BuildConfigurationValue configuration,
      boolean shareable,
      boolean shouldScanIncludes,
      boolean usePic,
      boolean useHeaderModules,
      NestedSet<Artifact> mandatoryInputs,
      NestedSet<Artifact> mandatorySpawnInputs,
      ImmutableList<Artifact> builtinIncludeFiles,
      NestedSet<Artifact> additionalPrunableHeaders,
      Artifact outputFile,
      @Nullable Artifact dotdFile,
      @Nullable Artifact diagnosticsFile,
      @Nullable Artifact gcnoFile,
      @Nullable Artifact dwoFile,
      @Nullable Artifact ltoIndexingFile,
      CcCompilationContext ccCompilationContext,
      CoptsFilter coptsFilter,
      ImmutableList<Artifact> additionalIncludeScanningRoots,
      ImmutableMap<String, String> executionInfo,
      String actionName,
      CppSemantics cppSemantics,
      ImmutableList<PathFragment> builtInIncludeDirectories,
      @Nullable Artifact grepIncludes,
      ImmutableList<Artifact> additionalOutputs) {
    super(
        owner,
        mandatoryInputs,
        collectOutputs(
            Preconditions.checkNotNull(outputFile, "outputFile"),
            dotdFile,
            diagnosticsFile,
            gcnoFile,
            dwoFile,
            ltoIndexingFile,
            additionalOutputs));
    this.gcnoFile = gcnoFile;
    this.sourceFile = sourceFile;
    this.shareable = shareable;
    this.configuration = configuration;
    this.mandatoryInputs = mandatoryInputs;
    this.mandatorySpawnInputs = mandatorySpawnInputs;
    this.additionalPrunableHeaders = additionalPrunableHeaders;
    this.shouldScanIncludes = shouldScanIncludes && cppSemantics.allowIncludeScanning();
    this.usePic = usePic;
    this.useHeaderModules = useHeaderModules;
    this.ccCompilationContext = ccCompilationContext;
    this.builtinIncludeFiles = builtinIncludeFiles;
    this.additionalIncludeScanningRoots =
        Preconditions.checkNotNull(additionalIncludeScanningRoots);
    this.compileCommandLine =
        buildCommandLine(
            sourceFile, coptsFilter, actionName, dotdFile, featureConfiguration, variables);
    this.executionInfo = executionInfo;
    this.actionName = actionName;
    this.featureConfiguration = featureConfiguration;
    this.needsIncludeValidation = cppSemantics.needsIncludeValidation();
    this.builtInIncludeDirectories = builtInIncludeDirectories;
    this.additionalInputs = null;
    this.usedModules = null;
    this.topLevelModules = null;
    this.grepIncludes = grepIncludes;
    if (featureConfiguration.isEnabled(CppRuleClasses.COMPILER_PARAM_FILE)) {
      paramFilePath =
          outputFile
              .getExecPath()
              .getParentDirectory()
              .getChild(outputFile.getFilename() + ".params");
    }

    NestedSetBuilder<Artifact> allowedDerivedInputsBuilder =
        NestedSetBuilder.fromNestedSet(mandatoryInputs)
            .addTransitive(additionalPrunableHeaders)
            .addTransitive(ccCompilationContext.getTransitiveCompilationPrerequisites())
            .addTransitive(ccCompilationContext.getDeclaredIncludeSrcs())
            .addTransitive(ccCompilationContext.getTransitiveModules(usePic))
            .add(getSourceFile());

    // The separate module is an allowed input to all compiles of this context except for its own
    // compile.
    Artifact separateModule = ccCompilationContext.getSeparateHeaderModule(usePic);
    if (separateModule != null && !separateModule.equals(getPrimaryOutput())) {
      allowedDerivedInputsBuilder.add(separateModule);
    }
    allowedDerivedInputs = allowedDerivedInputsBuilder.build();
  }

  private static ImmutableSet<Artifact> collectOutputs(
      Artifact outputFile,
      @Nullable Artifact dotdFile,
      @Nullable Artifact diagnosticsFile,
      @Nullable Artifact gcnoFile,
      @Nullable Artifact dwoFile,
      @Nullable Artifact ltoIndexingFile,
      ImmutableList<Artifact> additionalOutputs) {
    ImmutableSet.Builder<Artifact> outputs = ImmutableSet.builder();
    outputs.add(outputFile);
    if (gcnoFile != null) {
      outputs.add(gcnoFile);
    }
    outputs.addAll(additionalOutputs);
    if (dotdFile != null) {
      outputs.add(dotdFile);
    }
    if (diagnosticsFile != null) {
      outputs.add(diagnosticsFile);
    }
    if (dwoFile != null) {
      outputs.add(dwoFile);
    }
    if (ltoIndexingFile != null) {
      outputs.add(ltoIndexingFile);
    }
    return outputs.build();
  }

  static CompileCommandLine buildCommandLine(
      Artifact sourceFile,
      CoptsFilter coptsFilter,
      String actionName,
      Artifact dotdFile,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables variables) {
    return CompileCommandLine.builder(sourceFile, coptsFilter, actionName, dotdFile)
        .setFeatureConfiguration(featureConfiguration)
        .setVariables(variables)
        .build();
  }

  /**
   * Whether we should do "include scanning". Note that this does *not* mean whether we should parse
   * the .d files to determine which include files were used during compilation. Instead, this means
   * whether we should a) run the pre-execution include scanner (see {@code IncludeScanningContext})
   * if one exists and b) whether the action inputs should be modified to match the results of that
   * pre-execution scanning and (if enabled) again after execution to match the results of the .d
   * file parsing.
   *
   * <p>This does *not* have anything to do with "hdrs_check".
   */
  @VisibleForTesting
  boolean shouldScanIncludes() {
    return shouldScanIncludes;
  }

  boolean useInMemoryDotdFiles() {
    return cppConfiguration().getInmemoryDotdFiles();
  }

  private boolean enabledCppCompileResourcesEstimation() {
    return cppConfiguration().getExperimentalCppCompileResourcesEstimation();
  }

  @Override
  public ActionEnvironment getEnvironment() {
    return configuration.getActionEnvironment();
  }

  @Override
  public List<PathFragment> getBuiltInIncludeDirectories() {
    return builtInIncludeDirectories;
  }

  @Nullable
  @Override
  public List<Artifact> getBuiltInIncludeFiles() {
    return builtinIncludeFiles;
  }

  @Override
  public NestedSet<Artifact> getMandatoryInputs() {
    return mandatoryInputs;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    // Never prune orphaned modules files. To cut down critical paths, CppCompileActions do not
    // add modules files as inputs. Instead they rely on input discovery to recognize the needed
    // ones. However, orphan detection runs before input discovery and thus module files would be
    // discarded as orphans.
    // This is strictly better than marking all transitive modules as inputs, which would also
    // effectively disable orphan detection for .pcm files.
    Artifact outputFile = getPrimaryOutput();
    if (outputFile.isFileType(CppFileTypes.CPP_MODULE)) {
      return ImmutableSet.of(outputFile);
    }
    return super.getMandatoryOutputs();
  }

  /**
   * Returns the list of additional inputs found by dependency discovery, during action preparation.
   * {@link #discoverInputs(ActionExecutionContext)} must be called before this method is called on
   * each action execution.
   */
  public NestedSet<Artifact> getAdditionalInputs() {
    return Preconditions.checkNotNull(additionalInputs);
  }

  /** Clears the discovered {@link #additionalInputs}. */
  private void clearAdditionalInputs() {
    additionalInputs = null;
  }

  @Override
  public boolean discoversInputs() {
    return shouldScanIncludes || getDotdFile() != null || shouldParseShowIncludes();
  }

  @Override
  protected boolean inputsDiscovered() {
    return inputsDiscovered;
  }

  @Override
  protected void setInputsDiscovered(boolean inputsDiscovered) {
    this.inputsDiscovered = inputsDiscovered;
  }

  @Override
  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  public NestedSet<Artifact> getPossibleInputsForTesting() {
    return NestedSetBuilder.fromNestedSet(getInputs())
        .addTransitive(ccCompilationContext.getDeclaredIncludeSrcs())
        .addTransitive(additionalPrunableHeaders)
        .build();
  }

  /** Returns the results of include scanning. */
  @Nullable
  private NestedSet<Artifact> findUsedHeaders(
      ActionExecutionContext actionExecutionContext, IncludeScanningHeaderData headerData)
      throws ActionExecutionException, InterruptedException {
    Preconditions.checkState(
        shouldScanIncludes, "findUsedHeaders() called although include scanning is disabled");
    try {
      try {
        List<Artifact> includes =
            actionExecutionContext
                .getContext(CppIncludeScanningContext.class)
                .findAdditionalInputs(this, actionExecutionContext, headerData);
        if (includes == null) {
          return null;
        }

        Collections.sort(includes, Artifact.EXEC_PATH_COMPARATOR);
        return NestedSetBuilder.wrap(Order.STABLE_ORDER, includes);
      } catch (IORuntimeException e) {
        throw new EnvironmentalExecException(
            e.getCauseIOException(),
            createFailureDetail("Find used headers failure", Code.FIND_USED_HEADERS_IO_EXCEPTION));
      }
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, "include scanning", this);
    }
  }

  // TODO(b/213594908): Remove this method from Action interface once CppCompileAction is immutable.
  @Override
  public void prepareInputDiscovery() {
    // Make sure to clear the additional inputs potentially left over from an old build (in case we
    // ran discoverInputs, but not beginExecution).
    clearAdditionalInputs();
  }

  /**
   * This method returns null when a required SkyValue is missing and a Skyframe restart is
   * required.
   */
  @Nullable
  @Override
  public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Preconditions.checkArgument(!sourceFile.isFileType(CppFileTypes.CPP_MODULE));

    if (additionalInputs == null) {
      List<String> options;
      try {
        options = getCompilerOptions();
      } catch (CommandLineExpansionException e) {
        String message =
            String.format(
                "failed to generate compile command for rule '%s: %s",
                getOwner().getLabel(), e.getMessage());
        DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
        throw new ActionExecutionException(message, this, /* catastrophe= */ false, code);
      }
      commandLineKey = computeCommandLineKey(options);
      ImmutableList<PathFragment> systemIncludeDirs = getSystemIncludeDirs(options);
      boolean siblingLayout =
          actionExecutionContext
              .getOptions()
              .getOptions(BuildLanguageOptions.class)
              .experimentalSiblingRepositoryLayout;
      if (!shouldScanIncludes) {
        // When not actually doing include scanning, add all prunable headers to additionalInputs.
        // This is necessary because the inputs that can be pruned by .d file parsing must be
        // returned from discoverInputs() and they cannot be in mandatoryInputs. Thus, even with
        // include scanning turned off, we pretend that we "discover" these headers.
        additionalInputs =
            NestedSetBuilder.fromNestedSet(ccCompilationContext.getDeclaredIncludeSrcs())
                .addTransitive(additionalPrunableHeaders)
                .build();
        if (needsIncludeValidation) {
          verifyActionIncludePaths(systemIncludeDirs, siblingLayout);
        }
        return additionalInputs;
      }
      IncludeScanningHeaderData.Builder includeScanningHeaderDataBuilder =
          ccCompilationContext.createIncludeScanningHeaderData(
              actionExecutionContext.getEnvironmentForDiscoveringInputs(),
              usePic,
              useHeaderModules);
      if (includeScanningHeaderDataBuilder == null) {
        return null;
      }
      // In theory, we could verify include paths even earlier, but we want to avoid the restart
      // above necessitating a double-execution.
      if (needsIncludeValidation) {
        verifyActionIncludePaths(systemIncludeDirs, siblingLayout);
      }
      IncludeScanningHeaderData includeScanningHeaderData =
          includeScanningHeaderDataBuilder
              .setSystemIncludeDirs(systemIncludeDirs)
              .setCmdlineIncludes(getCmdlineIncludes(options))
              .setIsValidUndeclaredHeader(getValidUndeclaredHeaderPredicate())
              .build();
      additionalInputs = findUsedHeaders(actionExecutionContext, includeScanningHeaderData);
      if (additionalInputs == null) {
        return null;
      }

      if (useHeaderModules) {
        boolean separate =
            getPrimaryOutput().equals(ccCompilationContext.getSeparateHeaderModule(usePic));
        usedModules =
            ccCompilationContext.computeUsedModules(usePic, additionalInputs.toSet(), separate);
      }
    }

    if (usedModules == null) {
      // There are two paths in which this can be reached:
      // 1. This is not a modular compilation or one without include scanning. In either case, we
      //    never compute used modules.
      // 2. This function has completed on a previous execution, adding all used modules to
      //    additionalInputs and resetting usedModules to null below.
      // In either case, there is nothing more to do here.
      return additionalInputs;
    }

    ImmutableMap<Artifact, NestedSet<Artifact>> transitivelyUsedModules =
        computeTransitivelyUsedModules(
            actionExecutionContext.getEnvironmentForDiscoveringInputs(), usedModules);
    if (transitivelyUsedModules == null) {
      return null;
    }

    Set<Artifact> topLevel =
        actionExecutionContext
            .getDiscoveredModulesPruner()
            .computeTopLevelModules(this, usedModules, transitivelyUsedModules);

    NestedSetBuilder<Artifact> topLevelModulesBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> discoveredModulesBuilder = NestedSetBuilder.stableOrder();
    for (Artifact module : topLevel) {
      topLevelModulesBuilder.add(module);
      discoveredModulesBuilder.addTransitive(transitivelyUsedModules.get(module));
    }
    topLevelModules = topLevelModulesBuilder.build();
    discoveredModulesBuilder.addTransitive(topLevelModules);
    NestedSet<Artifact> discoveredModules = discoveredModulesBuilder.buildInterruptibly();

    additionalInputs =
        NestedSetBuilder.fromNestedSet(additionalInputs).addTransitive(discoveredModules).build();
    if (getPrimaryOutput().isFileType(CppFileTypes.CPP_MODULE)) {
      this.discoveredModules = discoveredModules;
    }
    usedModules = null;
    return additionalInputs;
  }

  @Override
  public final NestedSet<Artifact> getOriginalInputs() {
    return mandatoryInputs;
  }

  @Nullable
  private Predicate<Artifact> getValidUndeclaredHeaderPredicate() {
    if (getDotdFile() != null) {
      // If we'll be looking at .d files later, don't remove undeclared inputs now.
      return null;
    }

    var cppConfiguration = cppConfiguration();
    Iterable<PathFragment> ignoreDirs =
        cppConfiguration.isStrictSystemIncludes()
            ? getBuiltInIncludeDirectories()
            : getValidationIgnoredDirs();
    ImmutableSet<Artifact> additionalPrunableHeadersSet = additionalPrunableHeaders.toSet();
    return header ->
        additionalPrunableHeadersSet.contains(header)
            || FileSystemUtils.startsWithAny(header.getExecPath(), ignoreDirs);
  }

  @Override
  public Artifact getPrimaryInput() {
    return getSourceFile();
  }

  /** Returns the path of the c/cc source for gcc. */
  public final Artifact getSourceFile() {
    return compileCommandLine.getSourceFile();
  }

  @Override
  @Nullable
  public Artifact getGrepIncludes() {
    return grepIncludes;
  }

  /**
   * Set by {@link #discoverInputs}. Returns a subset of {@link #getAdditionalInputs} or an empty
   * {@link NestedSet}, if this is not a compile action producing a C++ module.
   */
  @Override
  public NestedSet<Artifact> getDiscoveredModules() {
    return firstNonNull(discoveredModules, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  /** Returns the path where the compiler should put the discovered dependency information. */
  public Artifact getDotdFile() {
    return compileCommandLine.getDotdFile();
  }

  public CcCompilationContext getCcCompilationContext() {
    return ccCompilationContext;
  }

  @Override
  public List<PathFragment> getQuoteIncludeDirs() {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    result.addAll(ccCompilationContext.getQuoteIncludeDirs());
    ImmutableList<String> copts = compileCommandLine.getCopts(PathMapper.NOOP);
    for (int i = 0; i < copts.size(); i++) {
      String opt = copts.get(i);
      if (opt.startsWith("-iquote")) {
        if (opt.length() > 7) {
          result.add(PathFragment.create(opt.substring(7).trim()));
        } else if (i + 1 < copts.size()) {
          i++;
          result.add(PathFragment.create(copts.get(i)));
        } else {
          System.err.println("WARNING: dangling -iquote flag in options for " + prettyPrint());
        }
      }
    }
    return result.build();
  }

  @Override
  public List<PathFragment> getIncludeDirs() {
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    result.addAll(ccCompilationContext.getIncludeDirs());
    for (String opt : compileCommandLine.getCopts(PathMapper.NOOP)) {
      if (opt.startsWith("-I") || opt.startsWith("/I")) {
        // We insist on the combined form "-Idir".
        String includeDir = opt.substring(2);
        if (includeDir.isEmpty()) {
          continue;
        }
        if (matchesCaseInsensitiveMsvc(includeDir)) {
          // This is actually a "-imsvc", a system include dir.
          continue;
        }
        result.add(PathFragment.create(opt.substring(2)));
      }
    }
    return result.build();
  }

  @Override
  public ImmutableList<PathFragment> getFrameworkIncludeDirs() {
    return ccCompilationContext.getFrameworkIncludeDirs();
  }

  @VisibleForTesting
  List<PathFragment> getSystemIncludeDirs() throws CommandLineExpansionException {
    return getSystemIncludeDirs(getCompilerOptions());
  }

  private ImmutableList<PathFragment> getSystemIncludeDirs(List<String> compilerOptions) {
    // TODO(bazel-team): parsing the command line flags here couples us to gcc- and clang-cl-style
    // compiler command lines; use a different way to specify system includes (for example through a
    // system_includes attribute in cc_toolchain); note that that would disallow users from
    // specifying system include paths via the copts attribute.
    // Currently, this works together with the include_paths features because getCommandLine() will
    // get the system include paths from the {@code CcCompilationContext} instead.
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    for (int i = 0; i < compilerOptions.size(); i++) {
      String opt = compilerOptions.get(i);
      String systemIncludeFlag = null;
      if (opt.startsWith("-isystem")) {
        systemIncludeFlag = "-isystem";
      } else if (matchesIncludeCaseInsensitiveMsvc(opt)) {
        systemIncludeFlag = opt.substring(0, 6);
      }
      if (systemIncludeFlag == null) {
        continue;
      }

      if (opt.length() > systemIncludeFlag.length()) {
        result.add(PathFragment.create(opt.substring(systemIncludeFlag.length()).trim()));
      } else if (i + 1 < compilerOptions.size()) {
        i++;
        result.add(PathFragment.create(compilerOptions.get(i)));
      } else {
        System.err.println(
            "WARNING: dangling " + systemIncludeFlag + " flag in options for " + prettyPrint());
      }
    }
    return result.build();
  }

  private CppConfiguration cppConfiguration() {
    return configuration.getFragment(CppConfiguration.class);
  }

  private static final ImmutableList<CharMatcher> MSVC_CHARS =
      ImmutableList.of(
          CharMatcher.anyOf("mM"),
          CharMatcher.anyOf("sS"),
          CharMatcher.anyOf("vV"),
          CharMatcher.anyOf("cC"));
  private static final ImmutableList<CharMatcher> INCLUDE_PREFIX_CHARS =
      ImmutableList.of(CharMatcher.anyOf("-/"), CharMatcher.anyOf("iI"));

  private static boolean substrMatchesChars(
      String s, int startPos, ImmutableList<CharMatcher> substr) {
    for (int i = 0; i < substr.size(); i++) {
      if (!substr.get(i).matches(s.charAt(startPos + i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesCaseInsensitiveMsvc(String s) {
    return s.length() >= 4 && substrMatchesChars(s, 0, MSVC_CHARS);
  }

  private static boolean matchesIncludeCaseInsensitiveMsvc(String s) {
    return s.length() >= 6
        && substrMatchesChars(s, 0, INCLUDE_PREFIX_CHARS)
        && substrMatchesChars(s, 2, MSVC_CHARS);
  }

  private static ImmutableList<String> getCmdlineIncludes(List<String> args) {
    ImmutableList.Builder<String> cmdlineIncludes = ImmutableList.builder();
    for (Iterator<String> argi = args.iterator(); argi.hasNext(); ) {
      String arg = argi.next();
      if (arg.equals("-include") && argi.hasNext()) {
        cmdlineIncludes.add(argi.next());
      } else if (arg.startsWith("-FI") || arg.startsWith("/FI")) {
        if (arg.length() > 3) {
          cmdlineIncludes.add(arg.substring(3).trim());
        } else if (argi.hasNext()) {
          cmdlineIncludes.add(argi.next());
        }
      }
    }
    return cmdlineIncludes.build();
  }

  @Override
  public Artifact getMainIncludeScannerSource() {
    // getIncludeScannerSources() needs to return the main file first. This is used for determining
    // what file command line includes should be interpreted relative to.
    return getIncludeScannerSources().get(0);
  }

  @SuppressWarnings("LenientFormatStringValidation")
  @Override
  public ImmutableList<Artifact> getIncludeScannerSources() {
    if (getSourceFile().isFileType(CppFileTypes.CPP_MODULE_MAP)) {
      Artifact outputFile = getPrimaryOutput();
      boolean isSeparate = outputFile.equals(ccCompilationContext.getSeparateHeaderModule(usePic));
      // Expected 0 args, but got 1.
      Preconditions.checkState(
          outputFile.equals(ccCompilationContext.getHeaderModule(usePic)) || isSeparate,
          "Trying to build unknown module",
          outputFile);

      // If this is an action that compiles the header module itself, the source we build is the
      // module map, and we need to include-scan all headers that are referenced in the module map.
      return ccCompilationContext.getHeaderModuleSrcs(isSeparate);
    }
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    builder.add(getSourceFile());
    builder.addAll(additionalIncludeScanningRoots);
    return builder.build();
  }

  /**
   * Returns the list of "-D" arguments that should be used by this gcc invocation. Only used for
   * testing.
   */
  @VisibleForTesting
  public ImmutableCollection<String> getDefines() {
    return ccCompilationContext.getDefines();
  }

  @Override
  @VisibleForTesting
  public ImmutableMap<String, String> getIncompleteEnvironmentForTesting()
      throws ActionExecutionException {
    try {
      return getEffectiveEnvironment(ImmutableMap.of());
    } catch (CommandLineExpansionException e) {
      String message =
          String.format(
              "failed to generate compile environment variables for rule '%s: %s",
              getOwner().getLabel(), e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
      throw new ActionExecutionException(message, this, /*catastrophe=*/ false, code);
    }
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv)
      throws CommandLineExpansionException {
    return getEffectiveEnvironment(clientEnv, PathMapper.NOOP);
  }

  public ImmutableMap<String, String> getEffectiveEnvironment(
      Map<String, String> clientEnv, PathMapper pathMapper) throws CommandLineExpansionException {
    ActionEnvironment env = getEnvironment();
    Map<String, String> environment = Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());
    env.resolve(environment, clientEnv);

    if (!getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
      // Linux: this prevents gcc/clang from writing the unpredictable (and often irrelevant) value
      // of getcwd() into the debug info. Not applicable to Darwin or Windows, which have no /proc.
      environment.put("PWD", "/proc/self/cwd");
    }

    environment.putAll(compileCommandLine.getEnvironment(pathMapper));
    return ImmutableMap.copyOf(environment);
  }

  @Override
  public List<String> getArguments() throws CommandLineExpansionException {
    return getArguments(PathMapper.NOOP);
  }

  private List<String> getArguments(PathMapper pathMapper) throws CommandLineExpansionException {
    return compileCommandLine.getArguments(paramFilePath, getOverwrittenVariables(), pathMapper);
  }

  @Override
  public Sequence<String> getStarlarkArgv() throws EvalException {
    try {
      return StarlarkList.immutableCopyOf(
          compileCommandLine.getArguments(
              /* parameterFilePath= */ null, getOverwrittenVariables(), PathMapper.NOOP));

    } catch (CommandLineExpansionException ex) {
      throw new EvalException(ex);
    }
  }

  @Override
  public Sequence<CommandLineArgsApi> getStarlarkArgs() {
    ImmutableSet<Artifact> directoryInputs =
        getInputs().toList().stream().filter(Artifact::isDirectory).collect(toImmutableSet());

    CommandLine commandLine = compileCommandLine.getFilteredFeatureConfigurationCommandLine(this);
    ParamFileInfo paramFileInfo = null;
    if (cppConfiguration().useArgsParamsFile()) {
      paramFileInfo =
          ParamFileInfo.builder(ParameterFileType.GCC_QUOTED).setUseAlways(true).build();
    }
    CommandLineAndParamFileInfo commandLineAndParamFileInfo =
        new CommandLineAndParamFileInfo(commandLine, paramFileInfo);

    Args args = Args.forRegisteredAction(commandLineAndParamFileInfo, directoryInputs);

    return StarlarkList.immutableCopyOf(ImmutableList.of(args));
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
      throws CommandLineExpansionException, InterruptedException {
    CppCompileInfo.Builder info = CppCompileInfo.newBuilder();
    info.setTool(compileCommandLine.getToolPath());

    List<String> options =
        compileCommandLine.getCompilerOptions(getOverwrittenVariables(), PathMapper.NOOP);

    for (String option : options) {
      info.addCompilerOption(option);
    }
    info.setOutputFile(getPrimaryOutput().getExecPathString());
    info.setSourceFile(getSourceFile().getExecPathString());
    if (inputsKnown()) {
      info.addAllSourcesAndHeaders(Artifact.toExecPaths(getInputs().toList()));
    } else {
      info.addSourcesAndHeaders(getSourceFile().getExecPathString());
      info.addAllSourcesAndHeaders(
          Artifact.toExecPaths(ccCompilationContext.getDeclaredIncludeSrcs().toList()));
    }
    // TODO(ulfjack): Extra actions currently ignore the client environment.
    for (Map.Entry<String, String> envVariable :
        getEffectiveEnvironment(/*clientEnv=*/ ImmutableMap.of()).entrySet()) {
      info.addVariable(
          EnvironmentVariable.newBuilder()
              .setName(envVariable.getKey())
              .setValue(envVariable.getValue())
              .build());
    }

    try {
      return super.getExtraActionInfo(actionKeyContext)
          .setExtension(CppCompileInfo.cppCompileInfo, info.build());
    } catch (CommandLineExpansionException e) {
      throw new AssertionError("CppCompileAction command line expansion cannot fail", e);
    }
  }

  /** Returns the compiler options. */
  @VisibleForTesting
  public List<String> getCompilerOptions() throws CommandLineExpansionException {
    return compileCommandLine.getCompilerOptions(/* overwrittenVariables= */ null, PathMapper.NOOP);
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return mergeMaps(super.getExecutionInfo(), executionInfo);
  }

  private static boolean validateInclude(
      Set<Artifact> allowedIncludes, Iterable<PathFragment> ignoreDirs, Artifact include) {
    // Only declared modules are added to an action and so they are always valid.
    return include.isFileType(CppFileTypes.CPP_MODULE)
        ||
        // TODO(b/145253507): Exclude objc module maps from check, due to bad interaction with
        // local_objc_modules feature.
        include.isFileType(CppFileTypes.OBJC_MODULE_MAP)
        ||
        // It's a declared include/
        allowedIncludes.contains(include)
        ||
        // Ignore headers from built-in include directories.
        FileSystemUtils.startsWithAny(include.getExecPath(), ignoreDirs);
  }

  /**
   * Enforce that the includes actually visited during the compile were properly declared in the
   * rules.
   *
   * <p>The technique is to walk through all of the reported includes that gcc emits into the .d
   * file, and verify that they came from acceptable relative include directories. This is done in
   * two steps:
   *
   * <p>First, each included file is stripped of any include path prefix from {@code
   * quoteIncludeDirs} to produce an effective relative include dir+name.
   *
   * <p>Second, the remaining directory is looked up in {@code declaredIncludeDirs}, a list of
   * acceptable dirs. This list contains a set of dir fragments that have been calculated by the
   * configured target to be allowable for inclusion by this source. If no match is found, an error
   * is reported and an exception is thrown.
   *
   * @throws ActionExecutionException iff there was an undeclared dependency
   */
  @VisibleForTesting
  public void validateInclusions(
      ActionExecutionContext actionExecutionContext, NestedSet<Artifact> inputsForValidation)
      throws ActionExecutionException {
    if (!needsIncludeValidation) {
      return;
    }
    IncludeProblems errors = new IncludeProblems();
    Set<Artifact> allowedIncludes = new HashSet<>();
    for (NestedSet<Artifact> set :
        ImmutableList.of(
            mandatoryInputs,
            ccCompilationContext.getDeclaredIncludeSrcs(),
            additionalPrunableHeaders)) {
      for (Artifact input : set.toList()) {
        if (input.isTreeArtifact()) {
          allowedIncludes.addAll(
              actionExecutionContext.getArtifactExpander().tryExpandTreeArtifact(input));
        }
        allowedIncludes.add(input);
      }
    }

    Iterable<PathFragment> ignoreDirs =
        cppConfiguration().isStrictSystemIncludes()
            ? getBuiltInIncludeDirectories()
            : getValidationIgnoredDirs();

    // Copy the nested sets to hash sets for fast contains checking, but do so lazily.
    // Avoid immutable sets here to limit memory churn.
    for (Artifact input : inputsForValidation.toList()) {
      if (!validateInclude(allowedIncludes, ignoreDirs, input)) {
        errors.add(input.getExecPath().toString());
      }
    }
    errors.assertProblemFree(
        "undeclared inclusion(s) in rule '"
            + this.getOwner().getLabel()
            + "':\n"
            + "this rule is missing dependency declarations for the following files "
            + "included by '"
            + getSourceFile().prettyPrint()
            + "':",
        this);
  }

  private Iterable<PathFragment> getValidationIgnoredDirs() {
    List<PathFragment> cxxSystemIncludeDirs = getBuiltInIncludeDirectories();
    return Iterables.concat(cxxSystemIncludeDirs, ccCompilationContext.getSystemIncludeDirs());
  }

  @VisibleForTesting
  void verifyActionIncludePaths(
      List<PathFragment> systemIncludeDirs, boolean siblingRepositoryLayout)
      throws ActionExecutionException {
    ImmutableSet<PathFragment> ignoredDirs = ImmutableSet.copyOf(getValidationIgnoredDirs());
    // We currently do not check the output of:
    // - getBuiltinIncludeDirs(): while in practice this doesn't happen, bazel can be configured
    //   to use an absolute system root, in which case the builtin include dirs might be absolute.

    Iterable<PathFragment> includePathsToVerify =
        Iterables.concat(getIncludeDirs(), getQuoteIncludeDirs(), systemIncludeDirs);
    for (PathFragment includePath : includePathsToVerify) {
      // includePathsToVerify contains all paths that are added as -isystem directive on the command
      // line, most of which are added for include directives in the CcCompilationContext and are
      // thus also in ignoredDirs. The hash lookup prevents this from becoming O(N^2) for these.
      if (ignoredDirs.contains(includePath)
          || FileSystemUtils.startsWithAny(includePath, ignoredDirs)) {
        continue;
      }

      // Two conditions:
      // 1. Paths cannot be absolute (e.g. multiple uplevels to /etc/passwd)
      // 2. For relative paths, one starting ../ is okay for getting to a sibling repository.
      PathFragment prefix =
          siblingRepositoryLayout
              ? LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
              : LabelConstants.EXTERNAL_PATH_PREFIX;
      if (includePath.startsWith(prefix)) {
        includePath = includePath.relativeTo(prefix);
      }
      if (includePath.isAbsolute() || includePath.containsUplevelReferences()) {
        String message =
            String.format(
                "The include path '%s' references a path outside of the execution root.",
                includePath);
        DetailedExitCode code =
            createDetailedExitCode(message, Code.INCLUDE_PATH_OUTSIDE_EXEC_ROOT);
        throw new ActionExecutionException(message, this, /*catastrophe=*/ false, code);
      }
    }
  }

  /**
   * Recalculates this action's live input collection.
   *
   * <p>Can only be called if {@link #discoversInputs}, and must be called after execution in that
   * case.
   */
  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  @ThreadCompatible
  final void updateActionInputs(NestedSet<Artifact> discoveredInputs) {
    Preconditions.checkState(
        discoversInputs(), "Can't call if not discovering inputs: %s %s", discoveredInputs, this);
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.ACTION_UPDATE, this::describe)) {
      NestedSetBuilder<Artifact> inputsBuilder =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(mandatoryInputs);
      if (discoveredInputs != null) {
        inputsBuilder.addTransitive(discoveredInputs);
      }
      super.updateInputs(inputsBuilder.build());
    }
  }

  /**
   * Extracts all module (.pcm) files from potentialModules and returns a Variables object where
   * their exec paths are added to the value "module_files".
   */
  private static CcToolchainVariables calculateModuleVariable(
      NestedSet<Artifact> potentialModules) {
    ImmutableList.Builder<String> usedModulePaths = ImmutableList.builder();
    for (Artifact input : potentialModules.toList()) {
      if (input.isFileType(CppFileTypes.CPP_MODULE)) {
        usedModulePaths.add(input.getExecPathString());
      }
    }
    CcToolchainVariables.Builder variableBuilder = CcToolchainVariables.builder();
    variableBuilder.addStringSequenceVariable(
        CompileBuildVariables.MODULE_FILES.getVariableName(), usedModulePaths.build());
    return variableBuilder.build();
  }

  CcToolchainVariables getOverwrittenVariables() {
    if (useHeaderModules) {
      // TODO(cmita): Avoid keeping state in CppCompileAction.
      // There are two cases for when this method might be called:
      // 1. After input discovery, after which toplevelModules is set (in discoverInputs()).
      // 2. After the action is loaded from the local action cache, leaving topLevelModules null.
      //
      // Ideally the same thing would be done in both cases, but as is, we just overestimate modules
      // in the latter case using the inputs from the action cache.
      // Note that this breaks the invariant that Actions are immutable after the analysis phase.
      if (shouldScanIncludes && topLevelModules != null) {
        return calculateModuleVariable(topLevelModules);
      } else {
        return calculateModuleVariable(getInputs());
      }
    }
    return CcToolchainVariables.builder().build();
  }

  @Override
  public NestedSet<Artifact> getSchedulingDependencies() {
    return ccCompilationContext.getTransitiveCompilationPrerequisites();
  }

  @Override
  public NestedSet<Artifact> getAllowedDerivedInputs() {
    return allowedDerivedInputs;
  }

  /**
   * {@inheritDoc}
   *
   * <p>If this is compiling a module, restores the value of {@link #discoveredModules}, which is
   * used to create the {@link com.google.devtools.build.lib.skyframe.ActionExecutionValue} after an
   * action cache hit.
   */
  @Override
  public synchronized void updateInputs(NestedSet<Artifact> inputs) {
    super.updateInputs(inputs);
    if (getPrimaryOutput().isFileType(CppFileTypes.CPP_MODULE)) {
      discoveredModules =
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER,
              Iterables.filter(
                  inputs.toList(), input -> input.isFileType(CppFileTypes.CPP_MODULE)));
    }
  }

  @Override
  protected String getRawProgressMessage() {
    return (actionName.equals(CppActionNames.CPP_HEADER_ANALYSIS)
            ? "Header analysis for "
            : "Compiling ")
        + getSourceFile().prettyPrint();
  }

  /** Returns explicitly listed header files. */
  @Override
  public NestedSet<Artifact> getDeclaredIncludeSrcs() {
    return ccCompilationContext.getDeclaredIncludeSrcs();
  }

  /**
   * Estimates resource consumption when this action is executed locally. During investigation we
   * found linear dependency between used memory by action and number of inputs. For memory
   * estimation we are using form C + K * inputs, where C and K selected in such way, that more than
   * 95% of actions used less than C + K * inputs MB of memory during execution.
   */
  static ResourceSet estimateResourceConsumptionLocal(
      boolean enabled, String mnemonic, OS os, int inputs) {
    if (!enabled) {
      return AbstractAction.DEFAULT_RESOURCE_SET;
    }

    if (mnemonic == null) {
      return AbstractAction.DEFAULT_RESOURCE_SET;
    }

    switch (mnemonic) {
      case CPP_COMPILE_MNEMONIC:
        switch (os) {
          case DARWIN:
          case LINUX:
            return ResourceSet.createWithRamCpu(/* memoryMb= */ 80 + 0.7 * inputs, /* cpu= */ 1);
          default:
            return AbstractAction.DEFAULT_RESOURCE_SET;
        }
      case OBJC_COMPILE_MNEMONIC:
        switch (os) {
          case DARWIN:
            return ResourceSet.createWithRamCpu(/* memoryMb= */ 80 + 0.2 * inputs, /* cpu= */ 1);
          default:
            return AbstractAction.DEFAULT_RESOURCE_SET;
        }
      default:
        return AbstractAction.DEFAULT_RESOURCE_SET;
    }
  }

  @Override
  public boolean isShareable() {
    return shareable;
  }

  /** For actions that discover inputs, the key must include input names. */
  @Override
  public void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    computeKey(
        actionKeyContext,
        fp,
        getEnvironment(),
        compileCommandLine.getEnvironment(PathMapper.NOOP),
        executionInfo,
        getCommandLineKey(),
        ccCompilationContext.getDeclaredIncludeSrcs(),
        mandatoryInputs,
        mandatorySpawnInputs,
        additionalPrunableHeaders,
        builtInIncludeDirectories,
        ccCompilationContext.getTransitiveCompilationPrerequisites(),
        getMnemonic(),
        PathMappers.getOutputPathsMode(configuration));
  }

  // Separated into a helper method so that it can be called from CppCompileActionTemplate.
  static void computeKey(
      ActionKeyContext actionKeyContext,
      Fingerprint fp,
      ActionEnvironment env,
      Map<String, String> environmentVariables,
      Map<String, String> executionInfo,
      byte[] commandLineKey,
      NestedSet<Artifact> declaredIncludeSrcs,
      NestedSet<Artifact> mandatoryInputs,
      NestedSet<Artifact> mandatorySpawnInputs,
      NestedSet<Artifact> prunableHeaders,
      List<PathFragment> builtInIncludeDirectories,
      NestedSet<Artifact> inputsForInvalidation,
      String mnemonic,
      OutputPathsMode outputPathsMode)
      throws CommandLineExpansionException, InterruptedException {
    fp.addUUID(GUID);
    env.addTo(fp);
    fp.addStringMap(environmentVariables);
    fp.addStringMap(executionInfo);
    fp.addBytes(commandLineKey);

    actionKeyContext.addNestedSetToFingerprint(fp, declaredIncludeSrcs);
    fp.addInt(0); // mark the boundary between input types
    actionKeyContext.addNestedSetToFingerprint(fp, mandatoryInputs);
    actionKeyContext.addNestedSetToFingerprint(fp, mandatorySpawnInputs);
    fp.addInt(0);
    actionKeyContext.addNestedSetToFingerprint(fp, prunableHeaders);

    /*
     * getArguments() above captures all changes which affect the compilation command and hence the
     * contents of the object file. But we need to also make sure that we re-execute the action if
     * any of the fields that affect whether {@link #validateInclusions} will report an error or
     * warning have changed, otherwise we might miss some errors.
     */
    fp.addPaths(builtInIncludeDirectories);

    // This is needed for CppLinkstampCompile.
    fp.addInt(0);
    actionKeyContext.addNestedSetToFingerprint(fp, inputsForInvalidation);

    PathMappers.addToFingerprint(
        mnemonic,
        executionInfo,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        actionKeyContext,
        outputPathsMode,
        fp);
  }

  private byte[] getCommandLineKey() throws CommandLineExpansionException {
    if (commandLineKey == null) {
      // For the argv part of the cache key, ignore all compiler flags that explicitly denote module
      // file (.pcm) inputs. Depending on input discovery, some of the unused ones are removed from
      // the command line. However, these actually don't have an influence on the compile itself and
      // so ignoring them for the cache key calculation does not affect correctness. The compile
      // itself is fully determined by the input source files and module maps.
      // A better long-term solution would be to make the compiler to find them automatically and
      // never hand in the .pcm files explicitly on the command line in the first place.
      commandLineKey = computeCommandLineKey(getCompilerOptions());
    }
    return commandLineKey;
  }

  static byte[] computeCommandLineKey(List<String> compilerOptions) {
    Fingerprint fp = new Fingerprint();
    fp.addStrings(compilerOptions);
    return fp.digestAndReset();
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    PathMapper pathMapper =
        PathMappers.create(
            this, PathMappers.getOutputPathsMode(configuration), /* isStarlarkAction= */ false);

    if (featureConfiguration.isEnabled(CppRuleClasses.COMPILER_PARAM_FILE)) {
      try {
        paramFileActionInput =
            new ParamFileActionInput(
                paramFilePath,
                compileCommandLine.getCompilerOptions(getOverwrittenVariables(), pathMapper),
                // TODO(b/132888308): Support MSVC, which has its own method of escaping strings.
                ParameterFileType.GCC_QUOTED);
      } catch (CommandLineExpansionException e) {
        String message =
            String.format(
                "failed to generate compile command for rule '%s: %s",
                getOwner().getLabel(), e.getMessage());
        DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
        throw new ActionExecutionException(message, this, /* catastrophe= */ false, code);
      }
    }

    if (shouldScanIncludes) {
      updateActionInputs(additionalInputs);
    }

    ActionExecutionContext spawnContext;
    ShowIncludesFilter showIncludesFilterForStdout;
    ShowIncludesFilter showIncludesFilterForStderr;
    if (shouldParseShowIncludes()) {
      showIncludesFilterForStdout = new ShowIncludesFilter(getSourceFile().getFilename());
      showIncludesFilterForStderr = new ShowIncludesFilter(getSourceFile().getFilename());
      FileOutErr originalOutErr = actionExecutionContext.getFileOutErr();
      FileOutErr tempOutErr = originalOutErr.childOutErr();
      spawnContext = actionExecutionContext.withFileOutErr(tempOutErr);
    } else {
      spawnContext = actionExecutionContext;
      showIncludesFilterForStdout = null;
      showIncludesFilterForStderr = null;
    }

    Spawn spawn;
    try {
      spawn =
          createSpawn(
              actionExecutionContext.getExecRoot(),
              actionExecutionContext.getClientEnv(),
              pathMapper);
    } finally {
      clearAdditionalInputs();
    }

    ImmutableList<SpawnResult> spawnResults;
    byte[] dotDContents;

    try {
      spawnResults =
          actionExecutionContext.getContext(SpawnStrategyResolver.class).exec(spawn, spawnContext);
      // SpawnActionContext guarantees that the first list entry exists and corresponds to the
      // executed spawn.
      dotDContents = getDotDContents(spawnResults.get(0));
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, CppCompileAction.this);
    } catch (InterruptedException e) {
      copyTempOutErrToActionOutErrMaybe(
          spawnContext.getFileOutErr(),
          actionExecutionContext.getFileOutErr(),
          showIncludesFilterForStdout,
          showIncludesFilterForStderr);
      throw e;
    } finally {
      copyTempOutErrToActionOutErrMaybe(
          spawnContext.getFileOutErr(),
          actionExecutionContext.getFileOutErr(),
          showIncludesFilterForStdout,
          showIncludesFilterForStderr);
    }

    ensureCoverageNotesFileExists(actionExecutionContext);

    CppIncludeExtractionContext scanningContext =
        actionExecutionContext.getContext(CppIncludeExtractionContext.class);
    Path execRoot = actionExecutionContext.getExecRoot();
    boolean siblingRepositoryLayout =
        actionExecutionContext
            .getOptions()
            .getOptions(BuildLanguageOptions.class)
            .experimentalSiblingRepositoryLayout;

    if (shouldParseShowIncludes()) {
      NestedSet<Artifact> discoveredInputs =
          discoverInputsFromShowIncludesFilters(
              execRoot,
              scanningContext.getArtifactResolver(),
              showIncludesFilterForStdout,
              showIncludesFilterForStderr,
              siblingRepositoryLayout,
              pathMapper);
      updateActionInputs(discoveredInputs);
      validateInclusions(actionExecutionContext, discoveredInputs);
      return ActionResult.create(spawnResults);
    }

    if (getDotdFile() == null) {
      return ActionResult.create(spawnResults);
    }

    // Post-execute "include scanning", which modifies the action inputs to match what the
    // compile action actually used by incorporating the results of .d file parsing.
    NestedSet<Artifact> discoveredInputs =
        discoverInputsFromDotdFiles(
            actionExecutionContext,
            execRoot,
            scanningContext.getArtifactResolver(),
            dotDContents,
            siblingRepositoryLayout,
            pathMapper);
    dotDContents = null; // Garbage collect in-memory .d contents.

    updateActionInputs(discoveredInputs);

    // hdrs_check: This cannot be switched off for C++ build actions,
    // because doing so would allow for incorrect builds.
    // HeadersCheckingMode.NONE should only be used for ObjC build actions.
    validateInclusions(actionExecutionContext, discoveredInputs);
    return ActionResult.create(spawnResults);
  }

  private void copyTempOutErrToActionOutErrMaybe(
      FileOutErr tempOutErr,
      FileOutErr outErr,
      ShowIncludesFilter showIncludesFilterForStdout,
      ShowIncludesFilter showIncludesFilterForStderr)
      throws ActionExecutionException {
    // If parse_showincludes feature is enabled, instead of parsing dotD file we parse the
    // output of cl.exe caused by /showIncludes option.
    if (!shouldParseShowIncludes()) {
      return;
    }

    try {
      tempOutErr.close();
      if (tempOutErr.hasRecordedStdout()) {
        try (InputStream in = tempOutErr.getOutputPath().getInputStream()) {
          ByteStreams.copy(
              in, showIncludesFilterForStdout.getFilteredOutputStream(outErr.getOutputStream()));
        }
      }
      if (tempOutErr.hasRecordedStderr()) {
        try (InputStream in = tempOutErr.getErrorPath().getInputStream()) {
          ByteStreams.copy(
              in, showIncludesFilterForStderr.getFilteredOutputStream(outErr.getErrorStream()));
        }
      }
    } catch (IOException e) {
      throw ActionExecutionException.fromExecException(
          new EnvironmentalExecException(
              e, createFailureDetail("OutErr copy failure", Code.COPY_OUT_ERR_FAILURE)),
          CppCompileAction.this);
    }
  }

  @Nullable
  private byte[] getDotDContents(SpawnResult spawnResult) {
    if (getDotdFile() != null) {
      ByteString content = spawnResult.getInMemoryOutput(getDotdFile());
      if (content != null) {
        return content.toByteArray();
      }
    }
    return null;
  }

  private boolean shouldParseShowIncludes() {
    return featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES);
  }

  Spawn createSpawn(Path execRoot, Map<String, String> clientEnv, PathMapper pathMapper)
      throws ActionExecutionException {
    // Intentionally not adding {@link CppCompileAction#inputsForInvalidation}, those are not needed
    // for execution.
    NestedSetBuilder<ActionInput> inputsBuilder =
        NestedSetBuilder.<ActionInput>stableOrder().addTransitive(mandatorySpawnInputs);

    if (discoversInputs()) {
      inputsBuilder.addTransitive(getAdditionalInputs());
    }
    if (paramFileActionInput != null) {
      inputsBuilder.add(paramFileActionInput);
    }
    NestedSet<ActionInput> inputs = inputsBuilder.build();

    ImmutableMap.Builder<String, String> executionInfo =
        ImmutableMap.<String, String>builder().putAll(getExecutionInfo());
    if (getDotdFile() != null && useInMemoryDotdFiles()) {
      /*
       * CppCompileAction does dotd file scanning locally inside the Bazel process and thus
       * requires the dotd file contents to be available locally. In remote execution, we
       * generally don't want to stage all remote outputs on the local file system and thus
       * we need to tell the remote strategy (if any) to at least make the .d file available
       * in-memory. We can do that via
       * {@link ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS}.
       */
      executionInfo.put(
          ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS, getDotdFile().getExecPathString());
    }

    if (shouldParseShowIncludes()) {
      // Hack on Windows. The included headers dumped by cl.exe in stdout contain absolute paths.
      // When compiling the file from different workspace, the shared cache will cause header
      // dependency checking to fail. This was initially fixed by a hack (see
      // https://github.com/bazelbuild/bazel/issues/9172 for more details), but is broken again due
      // to cl/356735700. We require execution service to ignore caches from other workspace.
      executionInfo.put(
          ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE, execRoot.getBaseName());
    }

    ImmutableSet<Artifact> mandatoryOutputs;
    if (gcnoFile == null) {
      mandatoryOutputs = null; // All outputs must be created.
    } else {
      // In coverage mode, the .gcno file is not produced for an empty translation unit, but the
      // spawn should still succeed.
      Collection<Artifact> outputs = getOutputs();
      ImmutableSet.Builder<Artifact> builder =
          ImmutableSet.builderWithExpectedSize(outputs.size() - 1);
      for (Artifact output : outputs) {
        if (!output.equals(gcnoFile)) {
          builder.add(output);
        }
      }
      mandatoryOutputs = builder.build();
    }

    try {
      return new SimpleSpawn(
          this,
          ImmutableList.copyOf(getArguments(pathMapper)),
          getEffectiveEnvironment(clientEnv, pathMapper),
          executionInfo.buildOrThrow(),
          /* filesetMappings= */ ImmutableMap.of(),
          inputs,
          /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          getOutputs(),
          mandatoryOutputs,
          () ->
              estimateResourceConsumptionLocal(
                  enabledCppCompileResourcesEstimation(),
                  getMnemonic(),
                  OS.getCurrent(),
                  inputs.memoizedFlattenAndGetSize()),
          pathMapper);
    } catch (CommandLineExpansionException e) {
      String message =
          String.format(
              "failed to generate compile command for rule '%s: %s",
              getOwner().getLabel(), e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
      throw new ActionExecutionException(message, this, /* catastrophe= */ false, code);
    }
  }

  private NestedSet<Artifact> discoverInputsFromShowIncludesFilters(
      Path execRoot,
      ArtifactResolver artifactResolver,
      ShowIncludesFilter showIncludesFilterForStdout,
      ShowIncludesFilter showIncludesFilterForStderr,
      boolean siblingRepositoryLayout,
      PathMapper pathMapper)
      throws ActionExecutionException {
    Collection<Path> stdoutDeps = showIncludesFilterForStdout.getDependencies(execRoot);
    Collection<Path> stderrDeps = showIncludesFilterForStderr.getDependencies(execRoot);
    if (stdoutDeps.isEmpty()
        && stderrDeps.isEmpty()
        && (showIncludesFilterForStdout.sawPotentialUnsupportedShowIncludesLine()
            || showIncludesFilterForStderr.sawPotentialUnsupportedShowIncludesLine())) {
      // /showIncludes parsing didn't result in any headers being found (unusual) *and* also
      // encountered a line that looked like /showIncludes output in an unsupported encoding.
      String message =
          "While parsing the C++ compiler output for information about included headers, Bazel "
              + "failed to find any headers but encountered a line that appears to be "
              + "/showIncludes output in an unsupported encoding. This can result in incorrect "
              + "incremental builds. If you are using the default Windows MSVC toolchain that "
              + "ships with Bazel, ensure that the English language pack for Visual Studio is "
              + "installed and then run 'bazel clean --expunge'.";
      DetailedExitCode code = createDetailedExitCode(message, Code.FIND_USED_HEADERS_IO_EXCEPTION);
      throw new ActionExecutionException(message, this, /* catastrophe= */ false, code);
    }
    return HeaderDiscovery.discoverInputsFromDependencies(
        this,
        getSourceFile(),
        needsIncludeValidation,
        ImmutableList.<Path>builderWithExpectedSize(stdoutDeps.size() + stderrDeps.size())
            .addAll(stdoutDeps)
            .addAll(stderrDeps)
            .build(),
        getPermittedSystemIncludePrefixes(execRoot),
        getAllowedDerivedInputs(),
        execRoot,
        artifactResolver,
        siblingRepositoryLayout,
        pathMapper);
  }

  @VisibleForTesting
  public NestedSet<Artifact> discoverInputsFromDotdFiles(
      ActionExecutionContext actionExecutionContext,
      Path execRoot,
      ArtifactResolver artifactResolver,
      byte[] dotDContents,
      boolean siblingRepositoryLayout,
      PathMapper pathMapper)
      throws ActionExecutionException {
    Preconditions.checkNotNull(getDotdFile(), "Trying to scan .d file which is unset");
    return HeaderDiscovery.discoverInputsFromDependencies(
        this,
        getSourceFile(),
        needsIncludeValidation,
        processDepset(actionExecutionContext, execRoot, dotDContents).getDependencies(),
        getPermittedSystemIncludePrefixes(execRoot),
        getAllowedDerivedInputs(),
        execRoot,
        artifactResolver,
        siblingRepositoryLayout,
        pathMapper);
  }

  private DependencySet processDepset(
      ActionExecutionContext actionExecutionContext, Path execRoot, byte[] dotDContents)
      throws ActionExecutionException {
    try {
      DependencySet depSet = new DependencySet(execRoot);
      if (dotDContents != null && cppConfiguration().getInmemoryDotdFiles()) {
        return depSet.process(dotDContents);
      }
      return depSet.read(actionExecutionContext.getInputPath(getDotdFile()));
    } catch (IOException e) {
      // Some kind of IO or parse exception--wrap & rethrow it to stop the build.
      String message = "error while parsing .d file: " + e.getMessage();
      throw new ActionExecutionException(
          message, e, this, false, createDetailedExitCode(message, Code.D_FILE_PARSE_FAILURE));
    }
  }

  private List<Path> getPermittedSystemIncludePrefixes(Path execRoot) {
    List<Path> systemIncludePrefixes = new ArrayList<>();
    for (PathFragment includePath : getBuiltInIncludeDirectories()) {
      if (includePath.isAbsolute()) {
        systemIncludePrefixes.add(execRoot.getFileSystem().getPath(includePath));
      }
    }
    return systemIncludePrefixes;
  }

  /**
   * Gcc only creates a ".gcno" file if the compilation unit is non-empty. To ensure that the set of
   * outputs for a CppCompileAction remains consistent and doesn't vary dynamically depending on the
   * _contents_ of the input files, we create an empty ".gcno" file if gcc didn't create it.
   */
  private void ensureCoverageNotesFileExists(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {
    if (gcnoFile == null) {
      return;
    }
    if (!gcnoFile.isFileType(CppFileTypes.COVERAGE_NOTES)) {
      BugReport.sendNonFatalBugReport(
          new IllegalStateException(
              "In coverage mode but gcno artifact is not correct type: " + gcnoFile + ", " + this));
      return;
    }
    Path outputPath = actionExecutionContext.getInputPath(gcnoFile);
    if (outputPath.exists()) {
      return;
    }
    try {
      FileSystemUtils.createEmptyFile(outputPath);
    } catch (IOException e) {
      String message = "Error creating file '" + outputPath + "': " + e.getMessage();
      DetailedExitCode code = createDetailedExitCode(message, Code.COVERAGE_NOTES_CREATION_FAILURE);
      throw new ActionExecutionException(message, e, this, false, code);
    }
  }

  /**
   * When compiling with modules, the C++ compile action only has the {@code .pcm} files on its
   * inputs, which is not enough for extra actions that parse header files. Thus, re-run include
   * scanning and add headers to the inputs of the extra action, too.
   *
   * <p>This method returns null when a required SkyValue is missing and a Skyframe restart is
   * required.
   */
  @Nullable
  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    if (!shouldScanIncludes) {
      return NestedSetBuilder.fromNestedSet(ccCompilationContext.getDeclaredIncludeSrcs())
          .addTransitive(additionalPrunableHeaders)
          .build();
    }
    try {
      IncludeScanningHeaderData.Builder includeScanningHeaderData =
          ccCompilationContext.createIncludeScanningHeaderData(
              actionExecutionContext.getEnvironmentForDiscoveringInputs(),
              usePic,
              useHeaderModules);
      if (includeScanningHeaderData == null) {
        return null;
      }
      return findUsedHeaders(
          actionExecutionContext,
          includeScanningHeaderData
              .setSystemIncludeDirs(getSystemIncludeDirs())
              .setCmdlineIncludes(getCmdlineIncludes(getCompilerOptions()))
              .build());
    } catch (CommandLineExpansionException e) {
      String message =
          String.format(
              "failed to generate compile environment variables for rule '%s: %s",
              getOwner().getLabel(), e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
      throw new ActionExecutionException(message, this, /* catastrophe= */ false, code);
    }
  }

  static String actionNameToMnemonic(
      String actionName,
      FeatureConfiguration featureConfiguration,
      boolean useCppCompileHeaderMnemonic) {
    switch (actionName) {
      case CppActionNames.OBJC_COMPILE:
      case CppActionNames.OBJCPP_COMPILE:
        return OBJC_COMPILE_MNEMONIC;

      case CppActionNames.LINKSTAMP_COMPILE:
        // When compiling shared native deps, e.g. when two java_binary rules have the same set of
        // native dependencies, the CppCompileAction for link stamp data is shared also. This means
        // that out of two CppCompileAction instances, only one is actually executed, which means
        // that if extra actions are attached to both, one of the extra actions will find a
        // CppCompileAction for which discoverInputs() hasn't been called and thus trigger an
        // assertion. As a band-aid, change the mnemonic of said actions so that one can attach
        // extra actions to regular CppCompileActions without tickling this bug.
        return "CppLinkstampCompile";

      case CppActionNames.CPP_HEADER_PARSING:
        String suffix = useCppCompileHeaderMnemonic ? "Header" : "";
        return featureConfiguration.isEnabled(CppRuleClasses.LANG_OBJC)
            ? OBJC_COMPILE_MNEMONIC + suffix
            : CPP_COMPILE_MNEMONIC + suffix;
      case CppActionNames.CPP_HEADER_ANALYSIS:
        return "CppHeaderAnalysis";
      default:
        return CPP_COMPILE_MNEMONIC;
    }
  }

  @Override
  public String getMnemonic() {
    return actionNameToMnemonic(
        actionName, featureConfiguration, cppConfiguration().useCppCompileHeaderMnemonic());
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    // Outputting one argument per line makes it easier to diff the results.
    // The first element in getArguments() is actually the command to execute.
    String legend = "  Command: ";
    try {
      for (String argument : ShellEscaper.escapeAll(getArguments(PathMapper.NOOP))) {
        message.append(legend);
        message.append(argument);
        message.append('\n');
        legend = "  Argument: ";
      }
    } catch (CommandLineExpansionException e) {
      message.append("  Could not expand command line: ");
      message.append(e);
      message.append('\n');
    }

    for (Artifact src : getDeclaredIncludeSrcs().toList()) {
      message.append("  Declared include source: ");
      message.append(ShellEscaper.escapeString(src.getExecPathString()));
      message.append('\n');
    }

    return message.toString();
  }

  public CompileCommandLine getCompileCommandLine() {
    return compileCommandLine;
  }

  /**
   * For the given {@code usedModules}, looks up modules discovered by their generating actions.
   *
   * <p>The returned value only contains a map from elements of {@code usedModules} to the {@link
   * #discoveredModules} required to use them. If dependent actions have not been executed yet (and
   * thus {@link #discoveredModules} aren't known yet, returns null.
   */
  @Nullable
  private static ImmutableMap<Artifact, NestedSet<Artifact>> computeTransitivelyUsedModules(
      SkyFunction.Environment env, Set<DerivedArtifact> usedModules) throws InterruptedException {
    // Because SkyframeLookupResult.get call does not specify any exceptions where
    // SkyframeLookupResult is returned by env.getValuesAndExceptions, it is impossible for input
    // discovery to recover from exceptions thrown by spurious module deps (for instance, if a
    // commented-out include references a header file with an error in it). However, we generally
    // don't try to recover from errors around spurious includes discovered in the current build.
    // TODO(janakr): Can errors be aggregated here at least?
    Collection<SkyKey> skyKeys =
        Collections2.transform(usedModules, DerivedArtifact::getGeneratingActionKey);
    SkyframeLookupResult actionExecutionValues = env.getValuesAndExceptions(skyKeys);
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableMap.Builder<Artifact, NestedSet<Artifact>> transitivelyUsedModules =
        ImmutableMap.builderWithExpectedSize(usedModules.size());
    for (DerivedArtifact module : usedModules) {
      Preconditions.checkState(
          module.isFileType(CppFileTypes.CPP_MODULE), "Non-module? %s", module);
      SkyValue skyValue = actionExecutionValues.get(module.getGeneratingActionKey());
      if (skyValue == null) {
        return null;
      }
      ActionExecutionValue value =
          Preconditions.checkNotNull((ActionExecutionValue) skyValue, module);
      transitivelyUsedModules.put(module, value.getDiscoveredModules());
    }
    return transitivelyUsedModules.buildOrThrow();
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(createFailureDetail(message, detailedCode));
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setCppCompile(CppCompile.newBuilder().setCode(detailedCode))
        .build();
  }
}
