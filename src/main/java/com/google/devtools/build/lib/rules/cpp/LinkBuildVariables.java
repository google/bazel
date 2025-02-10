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
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.SequenceBuilder;
import com.google.devtools.build.lib.vfs.PathFragment;
import net.starlark.java.eval.EvalException;

// LINT.IfChange
/** Enum covering all build variables we create for all various {@link CppLinkAction}. */
public enum LinkBuildVariables {
  /** Entries in the linker runtime search path (usually set by -rpath flag) */
  RUNTIME_LIBRARY_SEARCH_DIRECTORIES("runtime_library_search_directories"),
  /** Entries in the linker search path (usually set by -L flag) */
  LIBRARY_SEARCH_DIRECTORIES("library_search_directories"),
  /** Flags providing files to link as inputs in the linker invocation */
  LIBRARIES_TO_LINK("libraries_to_link"),
  /** Thinlto param file produced by thinlto-indexing action consumed by the final link action. */
  THINLTO_PARAM_FILE("thinlto_param_file"),
  /** Location of def file used on Windows with MSVC */
  DEF_FILE_PATH("def_file_path"),
  /** Location where hinlto should write thinlto_param_file flags when indexing. */
  THINLTO_INDEXING_PARAM_FILE("thinlto_indexing_param_file"),

  THINLTO_PREFIX_REPLACE("thinlto_prefix_replace"),
  /**
   * A build variable to let the LTO indexing step know how to map from the minimized bitcode file
   * to the full bitcode file used by the LTO Backends.
   */
  THINLTO_OBJECT_SUFFIX_REPLACE("thinlto_object_suffix_replace"),
  /**
   * A build variable for the path to the merged object file, which is an object file that is
   * created during the LTO indexing step and needs to be passed to the final link.
   */
  THINLTO_MERGED_OBJECT_FILE("thinlto_merged_object_file"),
  /** Location of linker param file created by bazel to overcome command line length limit */
  LINKER_PARAM_FILE("linker_param_file"),
  /** execpath of the output of the linker. */
  OUTPUT_EXECPATH("output_execpath"),
  /** "yes"|"no" depending on whether interface library should be generated. */
  GENERATE_INTERFACE_LIBRARY("generate_interface_library"),
  /** Path to the interface library builder tool. */
  INTERFACE_LIBRARY_BUILDER("interface_library_builder_path"),
  /** Input for the interface library ifso builder tool. */
  INTERFACE_LIBRARY_INPUT("interface_library_input_path"),
  /** Path where to generate interface library using the ifso builder tool. */
  INTERFACE_LIBRARY_OUTPUT("interface_library_output_path"),
  /** Linker flags coming from the --linkopt or linkopts attribute. */
  USER_LINK_FLAGS("user_link_flags"),
  /** A build variable giving linkstamp paths. */
  LINKSTAMP_PATHS("linkstamp_paths"),
  /** Presence of this variable indicates that PIC code should be generated. */
  FORCE_PIC("force_pic"),
  /** Presence of this variable indicates that the debug symbols should be stripped. */
  STRIP_DEBUG_SYMBOLS("strip_debug_symbols"),
  /** Truthy when current action is a cc_test linking action, falsey otherwise. */
  IS_CC_TEST("is_cc_test"),
  /**
   * Presence of this variable indicates that files were compiled with fission (debug info is in
   * .dwo files instead of .o files and linker needs to know).
   */
  IS_USING_FISSION("is_using_fission"),
  /** Path to the fdo instrument. */
  FDO_INSTRUMENT_PATH("fdo_instrument_path"),
  /** Path to the context sensitive fdo instrument. */
  CS_FDO_INSTRUMENT_PATH("cs_fdo_instrument_path"),
  /** Path to the Propeller Optimize linker profile artifact */
  PROPELLER_OPTIMIZE_LD_PATH("propeller_optimize_ld_path"),
  /** The name of the runtime solib symlink of the shared library. */
  RUNTIME_SOLIB_NAME("runtime_solib_name");

  private final String variableName;

  LinkBuildVariables(String variableName) {
    this.variableName = variableName;
  }

  public String getVariableName() {
    return variableName;
  }

  public static CcToolchainVariables.Builder setupLtoIndexingVariables(
      PathFragment binDirectoryPath,
      String thinltoParamFile,
      String thinltoMergedObjectFile,
      CcToolchainProvider ccToolchainProvider,
      FeatureConfiguration featureConfiguration,
      PathFragment ltoOutputRootPrefix,
      PathFragment ltoObjRootPrefix)
      throws EvalException {
    CcToolchainVariables.Builder buildVariables = CcToolchainVariables.builder();

    // This is a lto-indexing action and we want it to populate param file.
    buildVariables.addStringVariable(
        THINLTO_INDEXING_PARAM_FILE.getVariableName(), thinltoParamFile);
    // TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
    buildVariables.addStringVariable("thinlto_optional_params_file", "=" + thinltoParamFile);

    // Given "fullbitcode_prefix;thinlto_index_prefix;native_object_prefix", replaces
    // fullbitcode_prefix with thinlto_index_prefix to generate the index and imports files.
    // fullbitcode_prefix is the empty string because we are appending a prefix to the fullbitcode
    // instead of replacing it. This argument is passed to the linker.
    // The native objects generated after the LTOBackend action are stored in a directory by
    // replacing the prefix "fullbitcode_prefix" with "native_object_prefix", and this is used
    // when generating the param file in the indexing step, which will be used during the final
    // link step.
    if (!ltoOutputRootPrefix.equals(ltoObjRootPrefix)) {
      buildVariables.addStringVariable(
          THINLTO_PREFIX_REPLACE.getVariableName(),
          ";"
              + binDirectoryPath.getRelative(ltoOutputRootPrefix)
              + "/;"
              + binDirectoryPath.getRelative(ltoObjRootPrefix)
              + "/");
    } else {
      buildVariables.addStringVariable(
          THINLTO_PREFIX_REPLACE.getVariableName(),
          ";" + binDirectoryPath.getRelative(ltoOutputRootPrefix) + "/");
    }

    String objectFileExtension =
        ccToolchainProvider
            .getFeatures()
            .getArtifactNameExtensionForCategory(ArtifactCategory.OBJECT_FILE);
    if (!featureConfiguration.isEnabled(CppRuleClasses.NO_USE_LTO_INDEXING_BITCODE_FILE)) {
      buildVariables.addStringVariable(
          THINLTO_OBJECT_SUFFIX_REPLACE.getVariableName(),
          Iterables.getOnlyElement(CppFileTypes.LTO_INDEXING_OBJECT_FILE.getExtensions())
              + ";"
              + objectFileExtension);
    }

    buildVariables.addStringVariable(
        THINLTO_MERGED_OBJECT_FILE.getVariableName(), thinltoMergedObjectFile);

    buildVariables.addStringVariable(GENERATE_INTERFACE_LIBRARY.getVariableName(), "no");
    buildVariables.addStringVariable(INTERFACE_LIBRARY_BUILDER.getVariableName(), "ignored");
    buildVariables.addStringVariable(INTERFACE_LIBRARY_INPUT.getVariableName(), "ignored");
    buildVariables.addStringVariable(INTERFACE_LIBRARY_OUTPUT.getVariableName(), "ignored");
    return buildVariables;
  }

  public static CcToolchainVariables.Builder setupLinkingVariables(
      Artifact outputFile,
      String runtimeSolibName,
      Artifact thinltoParamFile,
      CcToolchainProvider ccToolchainProvider,
      FeatureConfiguration featureConfiguration,
      Artifact interfaceLibraryBuilder,
      Artifact interfaceLibraryOutput,
      FdoContext fdoContext)
      throws EvalException {
    CcToolchainVariables.Builder buildVariables = CcToolchainVariables.builder();
    if (thinltoParamFile != null) {
      // This is a normal link action and we need to use param file created by lto-indexing.
      buildVariables.addArtifactVariable(
          LinkBuildVariables.THINLTO_PARAM_FILE.getVariableName(), thinltoParamFile);
    }

    // output exec path
    buildVariables.addArtifactVariable(
        LinkBuildVariables.OUTPUT_EXECPATH.getVariableName(), outputFile);

    buildVariables.addStringVariable(
        LinkBuildVariables.RUNTIME_SOLIB_NAME.getVariableName(), runtimeSolibName);

    if (!ccToolchainProvider.isToolConfiguration()
        && fdoContext != null
        && featureConfiguration.isEnabled(CppRuleClasses.PROPELLER_OPTIMIZE)
        && fdoContext.getPropellerOptimizeInputFile() != null
        && fdoContext.getPropellerOptimizeInputFile().getLdArtifact() != null) {
      buildVariables.addArtifactVariable(
          LinkBuildVariables.PROPELLER_OPTIMIZE_LD_PATH.getVariableName(),
          fdoContext.getPropellerOptimizeInputFile().getLdArtifact());
    }

    boolean shouldGenerateInterfaceLibrary =
        outputFile != null && interfaceLibraryBuilder != null && interfaceLibraryOutput != null;
    if (shouldGenerateInterfaceLibrary) {
      buildVariables.addStringVariable(GENERATE_INTERFACE_LIBRARY.getVariableName(), "yes");
      buildVariables.addArtifactVariable(
          INTERFACE_LIBRARY_BUILDER.getVariableName(), interfaceLibraryBuilder);
      buildVariables.addArtifactVariable(INTERFACE_LIBRARY_INPUT.getVariableName(), outputFile);
      buildVariables.addArtifactVariable(
          INTERFACE_LIBRARY_OUTPUT.getVariableName(), interfaceLibraryOutput);
    } else {
      buildVariables.addStringVariable(GENERATE_INTERFACE_LIBRARY.getVariableName(), "no");
      buildVariables.addStringVariable(INTERFACE_LIBRARY_BUILDER.getVariableName(), "ignored");
      buildVariables.addStringVariable(INTERFACE_LIBRARY_INPUT.getVariableName(), "ignored");
      buildVariables.addStringVariable(INTERFACE_LIBRARY_OUTPUT.getVariableName(), "ignored");
    }

    return buildVariables;
  }

  public static CcToolchainVariables.Builder setupCommonVariables(
      boolean isUsingLinkerNotArchiver,
      boolean isCreatingSharedLibrary,
      String paramFile,
      boolean mustKeepDebug,
      CcToolchainProvider ccToolchainProvider,
      FeatureConfiguration featureConfiguration,
      boolean useTestOnlyFlags,
      Iterable<String> userLinkFlags,
      FdoContext fdoContext,
      NestedSet<String> runtimeLibrarySearchDirectories,
      SequenceBuilder librariesToLink,
      NestedSet<String> librarySearchDirectories)
      throws EvalException {
    CcToolchainVariables.Builder buildVariables =
        CcToolchainVariables.builder(ccToolchainProvider.getBuildVars());
    CppConfiguration cppConfiguration = ccToolchainProvider.getCppConfiguration();

    // pic
    if (cppConfiguration.forcePic()) {
      buildVariables.addStringVariable(FORCE_PIC.getVariableName(), "");
    }

    if (!mustKeepDebug && cppConfiguration.shouldStripBinaries()) {
      buildVariables.addStringVariable(STRIP_DEBUG_SYMBOLS.getVariableName(), "");
    }

    if (isUsingLinkerNotArchiver
        && CcToolchainProvider.shouldCreatePerObjectDebugInfo(
            featureConfiguration, cppConfiguration)) {
      buildVariables.addStringVariable(IS_USING_FISSION.getVariableName(), "");
    }

    buildVariables.addBooleanValue(IS_CC_TEST.getVariableName(), useTestOnlyFlags);

    buildVariables.addStringSequenceVariable(
        RUNTIME_LIBRARY_SEARCH_DIRECTORIES.getVariableName(), runtimeLibrarySearchDirectories);

    if (librariesToLink != null) {
      buildVariables.addCustomBuiltVariable(LIBRARIES_TO_LINK.getVariableName(), librariesToLink);
    }

    buildVariables.addStringSequenceVariable(
        LIBRARY_SEARCH_DIRECTORIES.getVariableName(), librarySearchDirectories);

    if (paramFile != null) {
      buildVariables.addStringVariable(LINKER_PARAM_FILE.getVariableName(), paramFile);
    }

    if (featureConfiguration.isEnabled(CppRuleClasses.FDO_INSTRUMENT)) {
      Preconditions.checkArgument(fdoContext.getBranchFdoProfile() == null);
      String fdoInstrument = cppConfiguration.getFdoInstrument();
      Preconditions.checkNotNull(fdoInstrument);
      buildVariables.addStringVariable(FDO_INSTRUMENT_PATH.getVariableName(), fdoInstrument);
    } else if (featureConfiguration.isEnabled(CppRuleClasses.CS_FDO_INSTRUMENT)) {
      String csFdoInstrument = ccToolchainProvider.getCSFdoInstrument();
      Preconditions.checkNotNull(csFdoInstrument);
      buildVariables.addStringVariable(CS_FDO_INSTRUMENT_PATH.getVariableName(), csFdoInstrument);
    }

    // For now, silently ignore linkopts if this is a static library
    userLinkFlags = isUsingLinkerNotArchiver ? userLinkFlags : ImmutableList.of();

    buildVariables.addStringSequenceVariable(
        LinkBuildVariables.USER_LINK_FLAGS.getVariableName(),
        removePieIfCreatingSharedLibrary(isCreatingSharedLibrary, userLinkFlags));
    return buildVariables;
  }

  private static Iterable<String> removePieIfCreatingSharedLibrary(
      boolean isCreatingSharedLibrary, Iterable<String> flags) {
    if (isCreatingSharedLibrary) {
      return Iterables.filter(
          flags,
          Predicates.not(
              Predicates.or(Predicates.equalTo("-pie"), Predicates.equalTo("-Wl,-pie"))));
    } else {
      return flags;
    }
  }
}

// LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/link/link_build_variables.bzl)
