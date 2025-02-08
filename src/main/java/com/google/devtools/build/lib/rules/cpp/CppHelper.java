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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.FailAction;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppLinkActionBuilder.LinkActionConstruction;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.server.FailureDetails.FailAction.Code;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/**
 * Helper class for functionality shared by cpp related rules.
 *
 * <p>This class can be used only after the loading phase.
 */
public class CppHelper {

  static final PathFragment OBJS = PathFragment.create("_objs");
  static final PathFragment PIC_OBJS = PathFragment.create("_pic_objs");
  static final PathFragment DOTD_FILES = PathFragment.create("_dotd");
  static final PathFragment PIC_DOTD_FILES = PathFragment.create("_pic_dotd");
  static final PathFragment DIA_FILES = PathFragment.create("_dia");
  static final PathFragment PIC_DIA_FILES = PathFragment.create("_pic_dia");

  public static final PathFragment SHARED_NONLTO_BACKEND_ROOT_PREFIX =
      PathFragment.create("shared.nonlto");

  /** Base label of the c++ toolchain category. */
  public static final String TOOLCHAIN_TYPE_LABEL = "//tools/cpp:toolchain_type";

  private CppHelper() {
    // prevents construction
  }

  /** Returns C++ toolchain, using toolchain resolution */
  public static CcToolchainProvider getToolchain(RuleContext ruleContext)
      throws RuleErrorException {
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(Label.parseCanonicalUnchecked("//tools/cpp:toolchain_type"));
    if (toolchainInfo == null) {
      toolchainInfo =
          ruleContext.getToolchainInfo(
              Label.parseCanonicalUnchecked("@bazel_tools//tools/cpp:toolchain_type"));
    }
    if (toolchainInfo == null) {
      throw ruleContext.throwWithRuleError(
          "Unable to find a CC toolchain using toolchain resolution. Did you properly set"
              + " --platforms?");
    }
    try {
      return CcToolchainProvider.PROVIDER.wrap((Info) toolchainInfo.getValue("cc"));
    } catch (EvalException e) {
      // There is not actually any reason for toolchainInfo.getValue to throw an exception.
      throw ruleContext.throwWithRuleError(
          "Unexpected eval exception from toolchainInfo.getValue('cc')");
    }
  }

  /** Returns the directory where object files are created. */
  public static PathFragment getObjDirectory(Label ruleLabel, boolean siblingRepositoryLayout) {
    return getObjDirectory(ruleLabel, false, siblingRepositoryLayout);
  }

  /** Returns the directory where object files are created. */
  public static PathFragment getObjDirectory(
      Label ruleLabel, boolean usePic, boolean siblingRepositoryLayout) {
    if (usePic) {
      return AnalysisUtils.getUniqueDirectory(ruleLabel, PIC_OBJS, siblingRepositoryLayout);
    } else {
      return AnalysisUtils.getUniqueDirectory(ruleLabel, OBJS, siblingRepositoryLayout);
    }
  }

  /** Returns the directory where dotd files are created. */
  private static PathFragment getDotdDirectory(
      Label ruleLabel, boolean usePic, boolean siblingRepositoryLayout) {
    return AnalysisUtils.getUniqueDirectory(
        ruleLabel, usePic ? PIC_DOTD_FILES : DOTD_FILES, siblingRepositoryLayout);
  }

  /** Returns the directory where serialized diagnostics files are created. */
  private static PathFragment getDiagnosticsDirectory(
      Label ruleLabel, boolean usePic, boolean siblingRepositoryLayout) {
    return AnalysisUtils.getUniqueDirectory(
        ruleLabel, usePic ? PIC_DIA_FILES : DIA_FILES, siblingRepositoryLayout);
  }

  /**
   * Given the output file path, returns the directory where the results of thinlto indexing will be
   * created: output_file.lto/
   */
  public static PathFragment getLtoOutputRootPrefix(PathFragment outputRootRelativePath) {
    return FileSystemUtils.appendExtension(outputRootRelativePath, ".lto");
  }

  /**
   * Given the lto output root directory path, returns the directory where thinlto native object
   * files are created: output_file.lto-obj/
   */
  public static PathFragment getThinLtoNativeObjectDirectoryFromLtoOutputRoot(
      PathFragment ltoOutputRootRelativePath) {
    return FileSystemUtils.appendExtension(ltoOutputRootRelativePath, "-obj");
  }

  public static Artifact getLinkedArtifact(
      String targetName,
      LinkActionConstruction linkActionConstruction,
      LinkTargetType linkType,
      String linkedArtifactNameSuffix,
      PathFragment name) {
    Artifact result =
        linkActionConstruction
            .getContext()
            .getPackageRelativeArtifact(name, linkActionConstruction.getBinDirectory());

    // If the linked artifact is not the linux default, then a FailAction is generated for said
    // linux default to satisfy the requirements of any implicit outputs.
    // TODO(b/30132703): Remove the implicit outputs of cc_library.
    Artifact linuxDefault =
        getLinuxLinkedArtifact(
            targetName, linkActionConstruction, linkType, linkedArtifactNameSuffix);
    if (!result.equals(linuxDefault)) {
      linkActionConstruction
          .getContext()
          .registerAction(
              new FailAction(
                  linkActionConstruction.getContext().getActionOwner(),
                  ImmutableList.of(linuxDefault),
                  String.format(
                      "the given toolchain supports creation of %s instead of %s",
                      result.getExecPathString(), linuxDefault.getExecPathString()),
                  Code.INCORRECT_TOOLCHAIN));
    }

    return result;
  }

  private static Artifact getLinuxLinkedArtifact(
      String targetName,
      LinkActionConstruction linkActionConstruction,
      LinkTargetType linkType,
      String linkedArtifactNameSuffix) {
    PathFragment name = PathFragment.create(targetName);
    if (linkType != LinkTargetType.EXECUTABLE) {
      name =
          name.replaceName(
              "lib"
                  + name.getBaseName()
                  + linkedArtifactNameSuffix
                  + linkType.getPicExtensionWhenApplicable()
                  + linkType.getDefaultExtension());
    }

    return linkActionConstruction
        .getContext()
        .getPackageRelativeArtifact(name, linkActionConstruction.getBinDirectory());
  }

  // TODO(bazel-team): figure out a way to merge these 2 methods. See the Todo in
  // CcCommonConfiguredTarget.noCoptsMatches().

  // LINT.IfChange
  /** Returns whether binaries must be compiled with position independent code. */
  public static boolean usePicForBinaries(
      CppConfiguration cppConfiguration, FeatureConfiguration featureConfiguration) {
    return cppConfiguration.forcePic()
        || (CcToolchainProvider.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            && (cppConfiguration.getCompilationMode() != CompilationMode.OPT
                || featureConfiguration.isEnabled(CppRuleClasses.PREFER_PIC_FOR_OPT_BINARIES)));
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)

  /** Returns the FDO build subtype. */
  @Nullable
  public static String getFdoBuildStamp(
      CppConfiguration cppConfiguration,
      FdoContext fdoContext,
      FeatureConfiguration featureConfiguration)
      throws EvalException {
    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    if (branchFdoProfile != null) {

      if (branchFdoProfile.isAutoFdo()) {
        return featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO) ? "AFDO" : null;
      }
      if (branchFdoProfile.isAutoXBinaryFdo()) {
        return featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO) ? "XFDO" : null;
      }
    }
    if (fdoContext.getBranchFdoProfile() != null
        && (fdoContext.getBranchFdoProfile().isLlvmCSFdo()
            || cppConfiguration.getCSFdoInstrument() != null)) {
      return "CSFDO";
    }
    if (fdoContext.getBranchFdoProfile() != null || cppConfiguration.getFdoInstrument() != null) {
      return "FDO";
    }
    return null;
  }

  public static ImmutableList<String> getCommandLine(
      RuleErrorConsumer ruleErrorConsumer,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables variables,
      String actionName)
      throws RuleErrorException {
    try {
      return ImmutableList.copyOf(featureConfiguration.getCommandLine(actionName, variables));
    } catch (ExpansionException e) {
      throw ruleErrorConsumer.throwWithRuleError(e);
    }
  }

  public static ImmutableMap<String, String> getEnvironmentVariables(
      FeatureConfiguration featureConfiguration, CcToolchainVariables variables, String actionName)
      throws EvalException {
    try {
      return featureConfiguration.getEnvironmentVariables(actionName, variables, PathMapper.NOOP);
    } catch (ExpansionException e) {
      throw new EvalException(e);
    }
  }

  static Artifact getCompileOutputArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      String outputName,
      BuildConfigurationValue config) {
    PathFragment objectDir = getObjDirectory(label, config.isSiblingRepositoryLayout());
    return actionConstructionContext.getDerivedArtifact(
        objectDir.getRelative(outputName), config.getBinDirectory(label.getRepository()));
  }

  /** Returns the corresponding compiled TreeArtifact given the source TreeArtifact. */
  public static SpecialArtifact getCompileOutputTreeArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      Artifact sourceTreeArtifact,
      String outputName,
      boolean usePic) {
    return actionConstructionContext.getTreeArtifact(
        getObjDirectory(
                label,
                usePic,
                actionConstructionContext.getConfiguration().isSiblingRepositoryLayout())
            .getRelative(outputName),
        sourceTreeArtifact.getRoot());
  }

  /** Returns the corresponding dotd files TreeArtifact given the source TreeArtifact. */
  public static SpecialArtifact getDotdOutputTreeArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      Artifact sourceTreeArtifact,
      String outputName,
      boolean usePic) {
    return actionConstructionContext.getTreeArtifact(
        getDotdDirectory(
                label,
                usePic,
                actionConstructionContext.getConfiguration().isSiblingRepositoryLayout())
            .getRelative(outputName),
        sourceTreeArtifact.getRoot());
  }

  /**
   * Returns the corresponding serialized diagnostics files TreeArtifact given the source
   * TreeArtifact.
   */
  public static SpecialArtifact getDiagnosticsOutputTreeArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      Artifact sourceTreeArtifact,
      String outputName,
      boolean usePic) {
    return actionConstructionContext.getTreeArtifact(
        getDiagnosticsDirectory(
                label,
                usePic,
                actionConstructionContext.getConfiguration().isSiblingRepositoryLayout())
            .getRelative(outputName),
        sourceTreeArtifact.getRoot());
  }

  public static String getArtifactNameForCategory(
      CcToolchainProvider toolchain,
      ArtifactCategory category,
      String outputName)
      throws RuleErrorException {
    try {
      return toolchain.getFeatures().getArtifactNameForCategory(category, outputName);
    } catch (EvalException e) {
      throw new RuleErrorException(e.getMessage());
    }
  }

  static String getDotdFileName(
      CcToolchainProvider toolchain,
      ArtifactCategory outputCategory,
      String outputName)
      throws RuleErrorException {
    String baseName =
        outputCategory == ArtifactCategory.OBJECT_FILE
                || outputCategory == ArtifactCategory.PROCESSED_HEADER
            ? outputName
            : getArtifactNameForCategory(toolchain, outputCategory, outputName);

    return getArtifactNameForCategory(toolchain, ArtifactCategory.INCLUDED_FILE_LIST, baseName);
  }

  static String getDiagnosticsFileName(
      CcToolchainProvider toolchain, ArtifactCategory outputCategory, String outputName)
      throws RuleErrorException {
    String baseName =
        outputCategory == ArtifactCategory.OBJECT_FILE
                || outputCategory == ArtifactCategory.PROCESSED_HEADER
            ? outputName
            : getArtifactNameForCategory(toolchain, outputCategory, outputName);

    return getArtifactNameForCategory(
        toolchain, ArtifactCategory.SERIALIZED_DIAGNOSTICS_FILE, baseName);
  }

  /**
   * Returns true if the build implied by the given config and toolchain uses --start-lib/--end-lib
   * ld options.
   */
  public static boolean useStartEndLib(
      CppConfiguration config, FeatureConfiguration featureConfiguration) {
    return config.startEndLibIsRequested()
        && featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_START_END_LIB);
  }

  /**
   * Returns the type of archives being used by the build implied by the given config and toolchain.
   */
  public static Link.ArchiveType getArchiveType(
      CppConfiguration config, FeatureConfiguration featureConfiguration) {
    return useStartEndLib(config, featureConfiguration)
        ? Link.ArchiveType.START_END_LIB
        : Link.ArchiveType.REGULAR;
  }

  /**
   * Returns true if interface shared objects should be used in the build implied by the given
   * cppConfiguration and toolchain.
   */
  public static boolean useInterfaceSharedLibraries(
      CppConfiguration cppConfiguration,
      FeatureConfiguration featureConfiguration) {
    return CcToolchainProvider.supportsInterfaceSharedLibraries(featureConfiguration)
        && cppConfiguration.getUseInterfaceSharedLibraries();
  }
}
