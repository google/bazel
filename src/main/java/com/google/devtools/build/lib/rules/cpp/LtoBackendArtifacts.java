// Copyright 2015 The Bazel Authors. All rights reserved.
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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractCommandLine;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppLinkActionBuilder.LinkActionConstruction;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.LtoBackendArtifactsApi;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/**
 * LtoBackendArtifacts represents a set of artifacts for a single ThinLTO backend compile.
 *
 * <p>ThinLTO expands the traditional 2 step compile (N x compile .cc, 1x link (N .o files) into a 4
 * step process:
 *
 * <ul>
 *   <li>1. Bitcode generation (N times). This is produces intermediate LLVM bitcode from a source
 *       file. For this product, it reuses the .o extension.
 *   <li>2. Indexing (once on N files). This takes all bitcode .o files, and for each .o file, it
 *       decides from which other .o files symbols can be inlined. In addition, it generates an
 *       index for looking up these symbols, and an imports file for identifying new input files for
 *       each step 3 {@link LtoBackendAction}.
 *   <li>3. Backend compile (N times). This is the traditional compilation, and uses the same
 *       command line as the Bitcode generation in 1). Since the compiler has many bit code files
 *       available, it can inline functions and propagate constants across .o files. This step is
 *       costly, as it will do traditional optimization. The result is a .lto.o file, a traditional
 *       ELF object file.
 *   <li>4. Backend link (once). This is the traditional link, and produces the final executable.
 * </ul>
 */
public final class LtoBackendArtifacts implements LtoBackendArtifactsApi<Artifact> {

  // A file containing mapping of symbol => bitcode file containing the symbol.
  // It will be null when this is a shared non-lto backend.
  @Nullable private final Artifact index;

  // The bitcode file which is the input of the compile.
  private final Artifact bitcodeFile;

  // A file containing a list of bitcode files necessary to run the backend step.
  // It will be null when this is a shared non-lto backend.
  @Nullable private final Artifact imports;

  // The result of executing the above command line, an ELF object file.
  private final Artifact objectFile;

  // The corresponding dwoFile if fission is used.
  private Artifact dwoFile;

  /**
   * If allBitcodeFiles is null, create an LTO backend that does not perform any cross-module
   * optimization, by not generating import and index files.
   */
  LtoBackendArtifacts(
      PathFragment ltoOutputRootPrefix,
      PathFragment ltoObjRootPrefix,
      Artifact bitcodeFile,
      @Nullable BitcodeFiles allBitcodeFiles,
      LinkActionConstruction linkActionConstruction,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext,
      boolean usePic,
      boolean generateDwo,
      List<String> userCompileFlags)
      throws EvalException {
    boolean createSharedNonLto = allBitcodeFiles == null;
    this.bitcodeFile = bitcodeFile;
    PathFragment obj = ltoObjRootPrefix.getRelative(bitcodeFile.getExecPath());
    // indexObj is an object that does not exist but helps us find where to store the index and
    // imports files
    PathFragment indexObj = ltoOutputRootPrefix.getRelative(bitcodeFile.getExecPath());

    LtoBackendAction.Builder builder = new LtoBackendAction.Builder();

    CcToolchainVariables ccToolchainVariables;

    ccToolchainVariables = ccToolchain.getBuildVars();

    CcToolchainVariables.Builder buildVariablesBuilder =
        CcToolchainVariables.builder(ccToolchainVariables);

    initializeLtoBackendBuilder(
        builder,
        buildVariablesBuilder,
        ccToolchain,
        fdoContext,
        featureConfiguration,
        userCompileFlags);
    CcToolchainVariables buildVariables = buildVariablesBuilder.build();
    if (bitcodeFile.isTreeArtifact()) {
      objectFile = linkActionConstruction.createTreeArtifact(obj);
      if (createSharedNonLto) {
        imports = null;
        index = null;
      } else {
        imports = linkActionConstruction.createTreeArtifact(indexObj);
        index = imports;
      }
      if (generateDwo) {
        // No support for dwo files for tree artifacts at the moment. This should not throw an
        // irrecoverable exception because we can still generate dwo files for the other artifacts.
        // TODO(b/289089713): Add support for dwo files for tree artifacts.
        dwoFile = null;
      }
      createLtoBackendActionTemplate(
          linkActionConstruction.getContext(),
          featureConfiguration,
          builder,
          buildVariables,
          usePic,
          allBitcodeFiles);
    } else {
      objectFile = linkActionConstruction.create(obj);
      if (createSharedNonLto) {
        imports = null;
        index = null;
      } else {
        String importsExt = Iterables.getOnlyElement(CppFileTypes.LTO_IMPORTS_FILE.getExtensions());
        String indexExt =
            Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_ANALYSIS_FILE.getExtensions());
        imports =
            linkActionConstruction.create(FileSystemUtils.appendExtension(indexObj, importsExt));
        index = linkActionConstruction.create(FileSystemUtils.appendExtension(indexObj, indexExt));
      }
      if (generateDwo) {
        dwoFile =
            linkActionConstruction.create(
                FileSystemUtils.replaceExtension(
                    objectFile.getOutputDirRelativePath(
                        linkActionConstruction.getConfig().isSiblingRepositoryLayout()),
                    ".dwo"));
      }
      addEnvironmentVariables(builder, buildVariables, featureConfiguration);
      scheduleLtoBackendAction(
          builder,
          buildVariables,
          linkActionConstruction.getContext(),
          featureConfiguration,
          usePic,
          allBitcodeFiles);
    }
  }

  public Artifact getObjectFile() {
    return objectFile;
  }

  @StarlarkMethod(name = "object_file", documented = false, useStarlarkThread = true)
  public Artifact getObjectFileForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return objectFile;
  }

  public Artifact getBitcodeFile() {
    return bitcodeFile;
  }

  @StarlarkMethod(name = "bitcode_file", documented = false, useStarlarkThread = true)
  public Artifact getBitcodeFileForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return bitcodeFile;
  }

  public Artifact getDwoFile() {
    return dwoFile;
  }

  @StarlarkMethod(
      name = "dwo_file",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public Artifact getDwoFileForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getDwoFile();
  }

  @StarlarkMethod(name = "index", documented = false, structField = true, allowReturnNones = true)
  @Nullable
  public Artifact getIndex() {
    return index;
  }

  @StarlarkMethod(name = "imports", documented = false, structField = true, allowReturnNones = true)
  @Nullable
  public Artifact getImports() {
    return imports;
  }

  void addIndexingOutputs(ImmutableSet.Builder<Artifact> builder) {
    // For objects from linkstatic libraries, we may not be including them in the LTO indexing
    // step when linked into a test, but rather will use shared non-LTO backends for better
    // scalability when running large numbers of tests.
    if (index == null) {
      return;
    }
    builder.add(imports);
    builder.add(index);
  }

  /**
   * Populate buildVariablesBuilder, and builder with data that is independent of what file is the
   * input to the action.
   */
  private static void initializeLtoBackendBuilder(
      LtoBackendAction.Builder builder,
      CcToolchainVariables.Builder buildVariablesBuilder,
      CcToolchainProvider ccToolchain,
      FdoContext fdoContext,
      FeatureConfiguration featureConfiguration,
      List<String> userCompileFlags)
      throws EvalException {
    builder.addTransitiveInputs(ccToolchain.getCompilerFiles());
    builder.setMnemonic("CcLtoBackendCompile");
    addProfileForLtoBackend(builder, fdoContext, featureConfiguration, buildVariablesBuilder);
    // Add the context sensitive instrument path to the backend.
    if (featureConfiguration.isEnabled(CppRuleClasses.CS_FDO_INSTRUMENT)) {
      buildVariablesBuilder.addStringVariable(
          CompileBuildVariables.CS_FDO_INSTRUMENT_PATH.getVariableName(),
          ccToolchain.getCSFdoInstrument());
    }
    buildVariablesBuilder.addStringSequenceVariable(
        CompileBuildVariables.USER_COMPILE_FLAGS.getVariableName(), userCompileFlags);

    if (!featureConfiguration.actionIsConfigured(CppActionNames.LTO_BACKEND)) {
      throw Starlark.errorf(
          "Thinlto build is requested, but the C++ toolchain doesn't define an action_config"
              + " for 'lto-backend' action.");
    }
    PathFragment compiler =
        PathFragment.create(featureConfiguration.getToolPathForAction(CppActionNames.LTO_BACKEND));
    builder.setExecutable(compiler);
  }

  private static void addPathsToBuildVariablesBuilder(
      CcToolchainVariables.Builder buildVariablesBuilder,
      String indexPath,
      String objectFilePath,
      String dwoFilePath,
      String bitcodeFilePath) {
    // Ideally, those strings would come directly from the execPath of the Artifacts of
    // the LtoBackendAction.Builder; however, in order to support tree artifacts, we need
    // the bitcodeFilePath to be different from the bitcodeTreeArtifact execPath.
    // The former is a file path and the latter is the directory path.
    // Therefore we accept strings as inputs rather than artifacts.
    if (indexPath != null) {
      buildVariablesBuilder.addStringVariable("thinlto_index", indexPath);
    } else {
      // An empty input indicates not to perform cross-module optimization.
      buildVariablesBuilder.addStringVariable("thinlto_index", "/dev/null");
    }
    // The output from the LTO backend step is a native object file.
    buildVariablesBuilder.addStringVariable("thinlto_output_object_file", objectFilePath);
    // The input to the LTO backend step is the bitcode file.
    buildVariablesBuilder.addStringVariable("thinlto_input_bitcode_file", bitcodeFilePath);
    // Add the context sensitive instrument path to the backend.

    if (dwoFilePath != null) {
      buildVariablesBuilder.addStringVariable(
          CompileBuildVariables.PER_OBJECT_DEBUG_INFO_FILE.getVariableName(), dwoFilePath);
      buildVariablesBuilder.addStringVariable(
          CompileBuildVariables.IS_USING_FISSION.getVariableName(), "");
    }
  }

  private static void addInputsToLtoBackendActionBuilder(
      LtoBackendAction.Builder builder,
      @Nullable Artifact index,
      @Nullable Artifact imports,
      Artifact bitcodeFile,
      @Nullable BitcodeFiles bitcodeFiles) {
    builder.addInput(bitcodeFile);
    Preconditions.checkState(
        (index == null) == (imports == null) && (imports == null) == (bitcodeFiles == null),
        "Either all or none of index, imports and bitcodeFiles should be null");
    if (imports != null) {
      builder.addImportsInfo(bitcodeFiles, imports);
      // Although the imports file is not used by the LTOBackendAction while the action is
      // executing, it is needed during the input discovery phase, and we must list it as an input
      // to the action in order for it to be preserved under --discard_orphaned_artifacts.
      builder.addInput(imports);
    }
    if (index != null) {
      builder.addInput(index);
    }
  }

  private static void addOutputsToLtoBackendActionBuilder(
      LtoBackendAction.Builder builder, Artifact objectFile, Artifact dwoFile) {
    builder.addOutput(objectFile);
    // Add the context sensitive instrument path to the backend.
    if (dwoFile != null) {
      builder.addOutput(dwoFile);
    }
  }

  private static void addEnvironmentVariables(
      LtoBackendAction.Builder builder,
      CcToolchainVariables buildVariables,
      FeatureConfiguration featureConfiguration)
      throws EvalException {
    try {
      builder.setEnvironment(
          featureConfiguration.getEnvironmentVariables(
              CppActionNames.LTO_BACKEND, buildVariables, PathMapper.NOOP));
    } catch (ExpansionException e) {
      throw new EvalException(e.getMessage());
    }
  }

  private static void addCommandLineToLtoBackendActionBuilder(
      LtoBackendAction.Builder builder,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables buildVariables,
      boolean usePic) {
    CommandLine ltoCommandLine =
        new AbstractCommandLine() {

          @Override
          public Iterable<String> arguments() throws CommandLineExpansionException {
            return arguments(/* artifactExpander= */ null, PathMapper.NOOP);
          }

          @Override
          public ImmutableList<String> arguments(
              ArtifactExpander artifactExpander, PathMapper pathMapper)
              throws CommandLineExpansionException {
            ImmutableList.Builder<String> args = ImmutableList.builder();
            try {
              args.addAll(
                  featureConfiguration.getCommandLine(
                      CppActionNames.LTO_BACKEND, buildVariables, artifactExpander, pathMapper));
            } catch (ExpansionException e) {
              throw new CommandLineExpansionException(e.getMessage());
            }
            // If this is a PIC compile (set based on the CppConfiguration), the PIC
            // option should be added after the rest of the command line so that it
            // cannot be overridden. This is consistent with the ordering in the
            // CppCompileAction's compiler options.
            if (usePic) {
              args.add("-fPIC");
            }
            return args.build();
          }
        };
    builder.addCommandLine(ltoCommandLine);
  }

  /**
   * Adds artifact to builder. The resulting builder can be built into a valid ltoBackendAction.
   *
   * <p>Assumes that build and builderVariableBuilder have been initialized by calling {@link
   * initializeLtoBackendBuilder}. If this is not true, the action will be wrong.
   *
   * @param builder the builder to add the artifacts to, initialized by initializeLtoBackendBuilder.
   * @param buildVariables CcToolchainVariables initialized by initializeLtoBackendBuilder
   * @param featureConfiguration the feature configuration to get the command line for the builder.
   * @param index the index artifact to add. Can be a TreeFileArtifact but cannot be a Tree
   *     Artifact.
   * @param imports the imports artifact to add. Can be a TreeFileArtifact but cannot be a Tree
   *     Artifact.
   * @param bitcodeArtifact the bitcode artifact to add. If it is a Tree Artifact, bitcodeFilePath
   *     must be set.
   * @param objectFile the object file to add. Can be a TreeFileArtifact but cannot be a Tree
   *     Artifact.
   * @param bitcodeFiles the bitcode files to add.
   * @param dwoFile the dwo file to add.
   * @param usePic whether to add the PIC option to the command line.
   * @param bitcodeFilePath the path of the bitcode object we are compiling. Only used if
   *     bitcodeArtifact is a tree artifact.
   * @param isDummyAction if true then ignores the preconditions, because it is generating a dummy
   *     action, not a valid action.
   */
  public static void addArtifactsLtoBackendAction(
      LtoBackendAction.Builder builder,
      CcToolchainVariables buildVariables,
      FeatureConfiguration featureConfiguration,
      @Nullable Artifact index,
      @Nullable Artifact imports,
      Artifact bitcodeArtifact,
      Artifact objectFile,
      @Nullable BitcodeFiles bitcodeFiles,
      @Nullable Artifact dwoFile,
      boolean usePic,
      @Nullable String bitcodeFilePath,
      boolean isDummyAction) {
    Preconditions.checkState(
        isDummyAction
            || ((index == null || !index.isTreeArtifact())
                && (imports == null || !imports.isTreeArtifact())
                && (dwoFile == null || !dwoFile.isTreeArtifact())
                && !objectFile.isTreeArtifact()),
        "index, imports, object and dwo files cannot be TreeArtifacts. We need to know their exact"
            + " path not just directory path.");
    Preconditions.checkState(
        isDummyAction || (bitcodeArtifact.isTreeArtifact() ^ bitcodeFilePath == null),
        "If bitcode file is a tree artifact, the bitcode file path must contain the path. If it is"
            + " not a tree artifact, then bitcode file path should be null to not override the"
            + " path.");
    CcToolchainVariables.Builder buildVariablesBuilder =
        CcToolchainVariables.builder(buildVariables);
    addInputsToLtoBackendActionBuilder(builder, index, imports, bitcodeArtifact, bitcodeFiles);
    addOutputsToLtoBackendActionBuilder(builder, objectFile, dwoFile);
    builder.setProgressMessage("LTO Backend Compile %{output}");

    String indexPath = index == null ? null : index.getExecPathString();
    String dwoFilePath = dwoFile == null ? null : dwoFile.getExecPathString();
    addPathsToBuildVariablesBuilder(
        buildVariablesBuilder,
        indexPath,
        objectFile.getExecPathString(),
        dwoFilePath,
        bitcodeFilePath != null ? bitcodeFilePath : bitcodeArtifact.getExecPathString());
    CcToolchainVariables buildVariablesWithFiles = buildVariablesBuilder.build();
    addCommandLineToLtoBackendActionBuilder(
        builder, featureConfiguration, buildVariablesWithFiles, usePic);
  }

  private void createLtoBackendActionTemplate(
      ActionConstructionContext actionConstructionContext,
      FeatureConfiguration featureConfiguration,
      LtoBackendAction.Builder ltoBackendActionbuilder,
      CcToolchainVariables buildVariables,
      boolean usePic,
      BitcodeFiles bitcodeFiles) {
    Preconditions.checkState(
        (index == null && imports == null) || index.equals(imports),
        "index and imports tree artifact must be the same");
    LtoBackendActionTemplate actionTemplate =
        new LtoBackendActionTemplate(
            (SpecialArtifact) index,
            (SpecialArtifact) bitcodeFile,
            (SpecialArtifact) objectFile,
            (SpecialArtifact) dwoFile,
            featureConfiguration,
            ltoBackendActionbuilder,
            buildVariables,
            usePic,
            bitcodeFiles,
            actionConstructionContext.getActionOwner());
    actionConstructionContext.registerAction(actionTemplate);
  }

  private void scheduleLtoBackendAction(
      LtoBackendAction.Builder builder,
      CcToolchainVariables buildVariables,
      ActionConstructionContext actionConstructionContext,
      FeatureConfiguration featureConfiguration,
      boolean usePic,
      @Nullable BitcodeFiles bitcodeFiles) {

    addArtifactsLtoBackendAction(
        builder,
        buildVariables,
        featureConfiguration,
        index,
        imports,
        bitcodeFile,
        objectFile,
        bitcodeFiles,
        dwoFile,
        usePic,
        /* bitcodeFilePath= */ null,
        /* isDummyAction= */ false);

    actionConstructionContext.registerAction(builder.build(actionConstructionContext));
  }

  /**
   * Adds the AFDO profile path to the variable builder and the profile to the inputs of the action.
   */
  @ThreadSafe
  private static void addProfileForLtoBackend(
      LtoBackendAction.Builder builder,
      FdoContext fdoContext,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables.Builder buildVariables)
      throws EvalException {
    Artifact prefetch = fdoContext.getPrefetchHintsArtifact();
    if (prefetch != null) {
      buildVariables.addStringVariable("fdo_prefetch_hints_path", prefetch.getExecPathString());
      builder.addInput(fdoContext.getPrefetchHintsArtifact());
    }
    if (fdoContext.getPropellerOptimizeInputFile() != null
        && fdoContext.getPropellerOptimizeInputFile().getCcArtifact() != null) {
      buildVariables.addStringVariable(
          "propeller_optimize_cc_path",
          fdoContext.getPropellerOptimizeInputFile().getCcArtifact().getExecPathString());
      builder.addInput(fdoContext.getPropellerOptimizeInputFile().getCcArtifact());
    }
    if (fdoContext.getPropellerOptimizeInputFile() != null
        && fdoContext.getPropellerOptimizeInputFile().getLdArtifact() != null) {
      buildVariables.addStringVariable(
          "propeller_optimize_ld_path",
          fdoContext.getPropellerOptimizeInputFile().getLdArtifact().getExecPathString());
      builder.addInput(fdoContext.getPropellerOptimizeInputFile().getLdArtifact());
    }
    if (!featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO)
        && !featureConfiguration.isEnabled(CppRuleClasses.CS_FDO_OPTIMIZE)
        && !featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO)) {
      return;
    }

    FdoContext.BranchFdoProfile branchFdoProfile =
        Preconditions.checkNotNull(fdoContext.getBranchFdoProfile());
    Artifact profile = branchFdoProfile.getProfileArtifact();
    buildVariables.addStringVariable("fdo_profile_path", profile.getExecPathString());
    builder.addInput(branchFdoProfile.getProfileArtifact());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LtoBackendArtifacts that)) {
      return false;
    }
    return Objects.equals(index, that.index)
        && bitcodeFile.equals(that.bitcodeFile)
        && Objects.equals(imports, that.imports)
        && objectFile.equals(that.objectFile)
        && Objects.equals(dwoFile, that.dwoFile);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, bitcodeFile, imports, objectFile, dwoFile);
  }
}
