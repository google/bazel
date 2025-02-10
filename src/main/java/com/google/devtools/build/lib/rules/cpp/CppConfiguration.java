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
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CppConfigurationApi;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * This class represents the C/C++ parts of the {@link BuildConfigurationValue}, including the exec
 * architecture, target architecture, compiler version, and a standard library version.
 */
@Immutable
@RequiresOptions(options = {CppOptions.class})
public final class CppConfiguration extends Fragment
    implements CppConfigurationApi<InvalidConfigurationException> {
  private static final String BAZEL_TOOLS_REPO = "@bazel_tools";

  /**
   * String indicating a Mac system, for example when used in a crosstool configuration's exec or
   * target system name.
   */
  public static final String MAC_SYSTEM_NAME = "x86_64-apple-macosx";

  /** String constant for CC_FLAGS make variable name */
  public static final String CC_FLAGS_MAKE_VARIABLE_NAME = "CC_FLAGS";

  /** An enumeration of all the tools that comprise a toolchain. */
  public enum Tool {
    AR("ar"),
    CPP("cpp"),
    GCC("gcc"),
    GCOV("gcov"),
    GCOVTOOL("gcov-tool"),
    LD("ld"),
    LLVM_COV("llvm-cov"),
    NM("nm"),
    OBJCOPY("objcopy"),
    OBJDUMP("objdump"),
    STRIP("strip"),
    DWP("dwp"),
    LLVM_PROFDATA("llvm-profdata");

    private final String namePart;

    private Tool(String namePart) {
      this.namePart = namePart;
    }

    public String getNamePart() {
      return namePart;
    }
  }

  /**
   * Values for the --hdrs_check option. Note that Bazel only supports and will default to "strict".
   */
  public enum HeadersCheckingMode {
    /**
     * Legacy behavior: Silently allow any source header file in any of the directories of the
     * containing package to be included by sources in this rule and dependent rules.
     */
    LOOSE,
    /** Disallow undeclared headers. */
    STRICT;

    public static HeadersCheckingMode getValue(String value) {
      if (value.equalsIgnoreCase("loose") || value.equalsIgnoreCase("warn")) {
        return LOOSE;
      }
      if (value.equalsIgnoreCase("strict")) {
        return STRICT;
      }
      throw new IllegalArgumentException();
    }
  }

  /**
   * --dynamic_mode parses to DynamicModeFlag, but AUTO will be translated based on platform,
   * resulting in a DynamicMode value.
   */
  public enum DynamicMode implements StarlarkValue {
    OFF,
    DEFAULT,
    FULLY
  }

  /** This enumeration is used for the --strip option. */
  public enum StripMode {
    ALWAYS("always"), // Always strip.
    SOMETIMES("sometimes"), // Strip iff compilationMode == FASTBUILD.
    NEVER("never"); // Never strip.

    private final String mode;

    private StripMode(String mode) {
      this.mode = mode;
    }

    @Override
    public String toString() {
      return mode;
    }
  }

  /**
   * This macro will be passed as a command-line parameter (eg. -DBUILD_FDO_TYPE="AUTOFDO"). For
   * possible values see {@code CppModel.getFdoBuildStamp()}.
   */
  public static final String FDO_STAMP_MACRO = "BUILD_FDO_TYPE";

  private final String fdoPath;
  private final Label fdoOptimizeLabel;

  private final String csFdoAbsolutePath;
  private final String propellerOptimizeAbsoluteCCProfile;
  private final String propellerOptimizeAbsoluteLdProfile;

  private final ImmutableList<String> conlyopts;

  private final ImmutableList<String> copts;
  private final ImmutableList<String> cxxopts;
  private final ImmutableList<String> objcopts;

  private final ImmutableList<String> linkopts;
  private final ImmutableList<String> ltoindexOptions;
  private final ImmutableList<String> ltobackendOptions;

  private final CppOptions cppOptions;

  // The dynamic mode for linking.
  private final boolean stripBinaries;
  private final CompilationMode compilationMode;
  private final boolean collectCodeCoverage;
  private final boolean isToolConfigurationDoNotUseWillBeRemovedFor129045294;

  private final boolean appleGenerateDsym;

  public CppConfiguration(BuildOptions options) throws InvalidConfigurationException {
    CppOptions cppOptions = options.get(CppOptions.class);

    CoreOptions commonOptions = options.get(CoreOptions.class);
    CompilationMode compilationMode = commonOptions.compilationMode;

    ImmutableList.Builder<String> linkoptsBuilder = ImmutableList.builder();
    linkoptsBuilder.addAll(cppOptions.linkoptList);
    if (cppOptions.experimentalOmitfp) {
      linkoptsBuilder.add("-Wl,--eh-frame-hdr");
    }

    PathFragment fdoPath = null;
    Label fdoProfileLabel = null;
    if (cppOptions.getFdoOptimize() != null) {
      if (cppOptions.getFdoOptimize().startsWith("//")) {
        try {
          fdoProfileLabel = Label.parseCanonical(cppOptions.getFdoOptimize());
        } catch (LabelSyntaxException e) {
          throw new InvalidConfigurationException(e);
        }
      } else {
        if (!cppOptions.enableFdoProfileAbsolutePath) {
          throw new InvalidConfigurationException(
              "Please use --fdo_profile instead of an absolute path set with --fdo_optimize. Using"
                  + " absolute paths may be temporary reenabled with"
                  + " --enable_fdo_profile_absolute_path");
        }
        fdoPath = PathFragment.create(cppOptions.getFdoOptimize());
        if (!fdoPath.isAbsolute()) {
          throw new InvalidConfigurationException(
              "Path of '"
                  + fdoPath.getPathString()
                  + "' in --fdo_optimize has to be either an absolute path or a label.");
        }
        try {
          // We don't check for file existence, but at least the filename should be well-formed.
          FileSystemUtils.checkBaseName(fdoPath.getBaseName());
        } catch (IllegalArgumentException e) {
          throw new InvalidConfigurationException(e);
        }
      }
    }

    PathFragment csFdoAbsolutePath = null;
    if (cppOptions.csFdoAbsolutePathForBuild != null) {
      if (!cppOptions.enableFdoProfileAbsolutePath) {
        throw new InvalidConfigurationException(
            "Please use --cs_fdo_optimize instead of an absolute path set with"
                + " --cs_fdo_absolute_path.Using absolute paths may be temporary reenabled with"
                + " --enable_fdo_profile_absolute_path");
      }
      csFdoAbsolutePath = PathFragment.create(cppOptions.csFdoAbsolutePathForBuild);
      if (!csFdoAbsolutePath.isAbsolute()) {
        throw new InvalidConfigurationException(
            "Path of '"
                + csFdoAbsolutePath.getPathString()
                + "' in --cs_fdo_absolute_path is not an absolute path.");
      }
      try {
        FileSystemUtils.checkBaseName(csFdoAbsolutePath.getBaseName());
      } catch (IllegalArgumentException e) {
        throw new InvalidConfigurationException(e);
      }
    }

    PathFragment propellerOptimizeAbsoluteCCProfile = null;
    if (cppOptions.propellerOptimizeAbsoluteCCProfile != null) {
      if (!cppOptions.enablePropellerOptimizeAbsolutePath) {
        throw new InvalidConfigurationException(
            "Please use --propeller_optimize instead of an absolute path set with"
                + " --propeller_optimize_absolute_cc_profile. Using absolute paths may be temporary"
                + " reenabled with --enable_propeller_optimize_absolute_paths");
      }
      propellerOptimizeAbsoluteCCProfile =
          PathFragment.create(cppOptions.propellerOptimizeAbsoluteCCProfile);
      if (!propellerOptimizeAbsoluteCCProfile.isAbsolute()) {
        throw new InvalidConfigurationException(
            "Path of '"
                + propellerOptimizeAbsoluteCCProfile.getPathString()
                + "' in --propeller_optimize_absolute_cc_profile is not an absolute path.");
      }
      try {
        FileSystemUtils.checkBaseName(propellerOptimizeAbsoluteCCProfile.getBaseName());
      } catch (IllegalArgumentException e) {
        throw new InvalidConfigurationException(e);
      }
    }

    PathFragment propellerOptimizeAbsoluteLdProfile = null;
    if (cppOptions.propellerOptimizeAbsoluteLdProfile != null) {
      if (!cppOptions.enablePropellerOptimizeAbsolutePath) {
        throw new InvalidConfigurationException(
            "Please use --propeller_optimize instead of an absolute path set with"
                + " --propeller_optimize_absolute_ld_profile. Using absolute paths may be temporary"
                + " reenabled with --enable_fdo_profile_absolute_path");
      }
      propellerOptimizeAbsoluteLdProfile =
          PathFragment.create(cppOptions.propellerOptimizeAbsoluteLdProfile);
      if (!propellerOptimizeAbsoluteLdProfile.isAbsolute()) {
        throw new InvalidConfigurationException(
            "Path of '"
                + propellerOptimizeAbsoluteLdProfile.getPathString()
                + "' in --propeller_optimize_absolute_ld_profile is not an absolute path.");
      }
      try {
        FileSystemUtils.checkBaseName(propellerOptimizeAbsoluteLdProfile.getBaseName());
      } catch (IllegalArgumentException e) {
        throw new InvalidConfigurationException(e);
      }
    }

    this.fdoPath = fdoPath == null ? null : fdoPath.getPathString();
    this.fdoOptimizeLabel = fdoProfileLabel;
    this.csFdoAbsolutePath = csFdoAbsolutePath == null ? null : csFdoAbsolutePath.getPathString();
    this.propellerOptimizeAbsoluteCCProfile =
        propellerOptimizeAbsoluteCCProfile == null
            ? null
            : propellerOptimizeAbsoluteCCProfile.getPathString();
    this.propellerOptimizeAbsoluteLdProfile =
        propellerOptimizeAbsoluteLdProfile == null
            ? null
            : propellerOptimizeAbsoluteLdProfile.getPathString();
    this.conlyopts = ImmutableList.copyOf(cppOptions.conlyoptList);
    this.copts = ImmutableList.copyOf(cppOptions.coptList);
    this.cxxopts = ImmutableList.copyOf(cppOptions.cxxoptList);
    this.objcopts = ImmutableList.copyOf(cppOptions.objcoptList);
    this.linkopts = linkoptsBuilder.build();
    this.ltoindexOptions = ImmutableList.copyOf(cppOptions.ltoindexoptList);
    this.ltobackendOptions = ImmutableList.copyOf(cppOptions.ltobackendoptList);
    this.cppOptions = cppOptions;
    this.stripBinaries =
        cppOptions.stripBinaries == StripMode.ALWAYS
            || (cppOptions.stripBinaries == StripMode.SOMETIMES
                && compilationMode == CompilationMode.FASTBUILD);
    this.compilationMode = compilationMode;
    this.collectCodeCoverage = commonOptions.collectCodeCoverage;
    this.isToolConfigurationDoNotUseWillBeRemovedFor129045294 = commonOptions.isExec;
    this.appleGenerateDsym = cppOptions.appleGenerateDsym;
  }

  /** Returns the label of the <code>cc_compiler</code> rule for the C++ configuration. */
  @StarlarkConfigurationField(
      name = "cc_toolchain",
      doc = "The label of the target describing the C++ toolchain",
      defaultLabel = "//tools/cpp:toolchain",
      defaultInToolRepository = true)
  @Nullable
  public Label getRuleProvidingCcToolchainProvider() {
      return null;
  }

  @Nullable
  @StarlarkConfigurationField(name = "zipper", doc = "The zipper label for FDO.")
  public Label getFdoZipper() {
    if (getFdoOptimizeLabel() != null
        || getFdoProfileLabel() != null
        || fdoPath != null
        || getMemProfProfileLabel() != null) {
      return Label.parseCanonicalUnchecked(BAZEL_TOOLS_REPO + "//tools/zip:unzip_fdo");
    }
    return null;
  }

  /** Returns the configured current compilation mode. */
  public CompilationMode getCompilationMode() {
    return compilationMode;
  }

  @StarlarkMethod(name = "compilation_mode", useStarlarkThread = true, documented = false)
  public String getCompilationModeForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return compilationMode.toString();
  }

  public boolean hasSharedLinkOption() {
    return linkopts.contains("-shared");
  }

  /** Returns the set of command-line LTO indexing options. */
  public ImmutableList<String> getLtoIndexOptions() {
    return ltoindexOptions;
  }

  @StarlarkMethod(name = "lto_index_options", documented = false, useStarlarkThread = true)
  public ImmutableList<String> getLtoIndexOptionsForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return ltoindexOptions;
  }

  /** Returns the set of command-line LTO backend options. */
  public ImmutableList<String> getLtoBackendOptions() {
    return ltobackendOptions;
  }

  @StarlarkMethod(
      name = "minimum_os_version",
      doc = "The minimum OS version for C/C++ compilation.",
      allowReturnNones = true)
  @Nullable
  public String getMinimumOsVersion() {
    return cppOptions.minimumOsVersion;
  }

  /** Returns the value of the --dynamic_mode flag. */
  public DynamicMode getDynamicModeFlag() {
    return cppOptions.dynamicMode;
  }

  @StarlarkMethod(
      name = "dynamic_mode",
      doc = "Whether C/C++ binaries/tests were requested to be linked dynamically.")
  public String getDynamicModeFlagString() {
    return cppOptions.dynamicMode.name();
  }

  public boolean useArgsParamsFile() {
    return cppOptions.useArgsParamsFile;
  }

  /** Returns whether or not to strip the binaries. */
  public boolean shouldStripBinaries() {
    return stripBinaries;
  }

  @Override
  public boolean shouldStripBinariesForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return stripBinaries;
  }

  /**
   * Returns the additional options to pass to strip when generating a {@code <name>.stripped}
   * binary by this build.
   */
  public ImmutableList<String> getStripOpts() {
    return ImmutableList.copyOf(cppOptions.stripoptList);
  }

  @Override
  public Sequence<String> getStripOptsStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return StarlarkList.immutableCopyOf(getStripOpts());
  }

  /** Returns whether temporary outputs from gcc will be saved. */
  public boolean getSaveTemps() {
    return cppOptions.saveTemps;
  }

  /**
   * Returns the {@link PerLabelOptions} to apply to the gcc command line, if the label of the
   * compiled file matches the regular expression.
   */
  public ImmutableList<PerLabelOptions> getPerFileCopts() {
    return ImmutableList.copyOf(cppOptions.perFileCopts);
  }

  /**
   * Returns the {@link PerLabelOptions} to apply to the LTO Backend command line, if the compiled
   * object matches the regular expression.
   */
  public ImmutableList<PerLabelOptions> getPerFileLtoBackendOpts() {
    return ImmutableList.copyOf(cppOptions.perFileLtoBackendOpts);
  }

  /** Returns the custom malloc library label. */
  @Override
  @StarlarkConfigurationField(
      name = "custom_malloc",
      doc = "The label specified in --custom_malloc")
  @Nullable
  public Label customMalloc() {
    return cppOptions.customMalloc;
  }

  /** Returns whether we are processing headers in dependencies of built C++ targets. */
  public boolean processHeadersInDependencies() {
    return cppOptions.processHeadersInDependencies;
  }

  /** Returns true if --fission contains the current compilation mode. */
  public boolean fissionIsActiveForCurrentCompilationMode() {
    return cppOptions.fissionModes.contains(compilationMode);
  }

  /** Returns true if --build_test_dwp is set on this build. */
  public boolean buildTestDwpIsActivated() {
    return cppOptions.buildTestDwp;
  }

  @Override
  public boolean buildTestDwpIsActivatedStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return buildTestDwpIsActivated();
  }

  /**
   * Returns true if all C++ compilations should produce position-independent code, links should
   * produce position-independent executables, and dependencies with equivalent pre-built pic and
   * nopic versions should apply the pic versions. Returns false if default settings should be
   * applied (i.e. make no special provisions for pic code).
   */
  public boolean forcePic() {
    return cppOptions.forcePic;
  }

  /** Returns true if --start_end_lib is set on this build. */
  public boolean startEndLibIsRequested() {
    return cppOptions.useStartEndLib;
  }

  @StarlarkMethod(name = "start_end_lib", documented = false, useStarlarkThread = true)
  public boolean startEndLibIsRequestedForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.useStartEndLib;
  }

  /** Returns value from --compiler option, null if the option was not passed. */
  @Nullable
  public String getCompilerFromOptions() {
    return cppOptions.cppCompiler;
  }

  public boolean experimentalLinkStaticLibrariesOnce() {
    return cppOptions.experimentalLinkStaticLibrariesOnce;
  }

  public boolean experimentalPlatformCcTest() {
    return cppOptions.experimentalPlatformCcTest;
  }

  public boolean legacyWholeArchive() {
    return cppOptions.legacyWholeArchive;
  }

  @StarlarkMethod(name = "legacy_whole_archive", documented = false, useStarlarkThread = true)
  public boolean legacyWholeArchiveForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.legacyWholeArchive;
  }

  public boolean removeLegacyWholeArchive() {
    return cppOptions.removeLegacyWholeArchive;
  }

  @StarlarkMethod(
      name = "incompatible_remove_legacy_whole_archive",
      documented = false,
      useStarlarkThread = true)
  public boolean removeLegacyWholeArchiveForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.removeLegacyWholeArchive;
  }

  public boolean getInmemoryDotdFiles() {
    return cppOptions.inmemoryDotdFiles;
  }

  public boolean getUseInterfaceSharedLibraries() {
    return cppOptions.useInterfaceSharedObjects;
  }

  @StarlarkMethod(name = "interface_shared_objects", documented = false, useStarlarkThread = true)
  public boolean getUseInterfaceSharedLibrariesforStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.useInterfaceSharedObjects;
  }

  /** Returns whether this configuration will use libunwind for stack unwinding. */
  public boolean isOmitfp() {
    return cppOptions.experimentalOmitfp;
  }

  /** Returns flags passed to Bazel by --copt option. */
  @Override
  public ImmutableList<String> getCopts() {
    if (isOmitfp()) {
      return ImmutableList.<String>builder()
          .add("-fomit-frame-pointer")
          .add("-fasynchronous-unwind-tables")
          .add("-DNO_FRAME_POINTER")
          .addAll(copts)
          .build();
    }
    return copts;
  }

  /** Returns flags passed to Bazel by --cxxopt option. */
  @Override
  public ImmutableList<String> getCxxopts() {
    return cxxopts;
  }

  /** Returns flags passed to Bazel by --conlyopt option. */
  @Override
  public ImmutableList<String> getConlyopts() {
    return conlyopts;
  }

  /** Returns flags passed to Bazel by --objccopt option. */
  @Override
  public ImmutableList<String> getObjcopts() {
    return objcopts;
  }

  /** Returns flags passed to Bazel by --linkopt option. */
  @Override
  public ImmutableList<String> getLinkopts() {
    return linkopts;
  }

  @Override
  public void reportInvalidOptions(EventHandler reporter, BuildOptions buildOptions) {
    CppOptions cppOptions = buildOptions.get(CppOptions.class);
    if (stripBinaries) {
      boolean warn = cppOptions.coptList.contains("-g");
      for (PerLabelOptions opt : cppOptions.perFileCopts) {
        warn |= opt.getOptions().contains("-g");
      }
      if (warn) {
        reporter.handle(
            Event.warn(
                "Stripping enabled, but '--copt=-g' (or --per_file_copt=...@-g) specified. "
                    + "Debug information will be generated and then stripped away. This is "
                    + "probably not what you want! Use '-c dbg' for debug mode, or use "
                    + "'--strip=never' to disable stripping"));
      }
    }

    // FDO
    if (cppOptions.getFdoOptimize() != null && cppOptions.fdoProfileLabel != null) {
      reporter.handle(Event.error("Both --fdo_optimize and --fdo_profile specified"));
    }

    if (cppOptions.fdoInstrumentForBuild != null) {
      if (cppOptions.getFdoOptimize() != null || cppOptions.fdoProfileLabel != null) {
        reporter.handle(
            Event.error(
                "Cannot instrument and optimize for FDO at the same time. Remove one of the "
                    + "'--fdo_instrument' and '--fdo_optimize/--fdo_profile' options"));
      }
      if (!cppOptions.coptList.contains("-Wno-error")) {
        // This is effectively impossible. --fdo_instrument adds this value, and only invocation
        // policy could remove it.
        reporter.handle(Event.error("Cannot instrument FDO without --copt including -Wno-error."));
      }
    }

    // This is an assertion check vs. user error because users can't trigger this state.
    // TODO(b/253313672): uncomment the below and check tests don't fail. This was originally set
    // check the exec configuration doesn't apply FDO settings. With the host configuration gone
    // we should migrate this check to the exec config. Since there's a chance of breakage it's best
    // to test this as its own dedicated change.
    // Verify.verify(
    //   !(buildOptions.get(CoreOptions.class).isExec && cppOptions.isFdo()),
    // "FDO state should not propagate to the exec configuration");
  }

  @Override
  public void processForOutputPathMnemonic(OutputDirectoriesContext ctx)
      throws Fragment.OutputDirectoriesContext.AddToMnemonicException {
    ctx.markAsExplicitInOutputPathFor("cc_output_directory_tag");
    if (!cppOptions.outputDirectoryTag.isEmpty()) {
      ctx.addToMnemonic(cppOptions.outputDirectoryTag);
    }
  }

  /** Returns true if we should share identical native libraries between different targets. */
  public boolean shareNativeDeps() {
    return cppOptions.shareNativeDeps;
  }

  public boolean isStrictSystemIncludes() {
    return cppOptions.strictSystemIncludes;
  }

  @Nullable
  String getFdoInstrument() {
    return cppOptions.fdoInstrumentForBuild;
  }

  @StarlarkMethod(
      name = "fdo_path",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public String getFdoPathForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return fdoPath == null ? null : fdoPath.toString();
  }

  @StarlarkConfigurationField(name = "fdo_optimize", doc = "The label specified in --fdo_optimize")
  public Label getFdoOptimizeLabel() {
    return fdoOptimizeLabel;
  }

  public String getCSFdoInstrument() {
    return cppOptions.csFdoInstrumentForBuild;
  }

  @Nullable
  @Override
  public String csFdoInstrumentStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCSFdoInstrument();
  }

  @StarlarkMethod(
      name = "cs_fdo_path",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public String getCsFdoPathForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return csFdoAbsolutePath;
  }

  @StarlarkMethod(
      name = "propeller_optimize_absolute_cc_profile",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public String getPropellerOptimizeAbsoluteCcProfileForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return propellerOptimizeAbsoluteCCProfile;
  }

  @StarlarkMethod(
      name = "propeller_optimize_absolute_ld_profile",
      documented = false,
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  public String getPropellerOptimizeAbsoluteLdProfileForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return propellerOptimizeAbsoluteLdProfile;
  }

  @Nullable
  @StarlarkConfigurationField(
      name = "fdo_prefetch_hints",
      doc = "The label specified in --fdo_prefetch_hints")
  public Label getFdoPrefetchHintsLabel() {
    return cppOptions.getFdoPrefetchHintsLabel();
  }

  @StarlarkConfigurationField(name = "fdo_profile", doc = "The label specified in --fdo_profile")
  public Label getFdoProfileLabel() {
    return cppOptions.fdoProfileLabel;
  }

  @StarlarkConfigurationField(
      name = "cs_fdo_profile",
      doc = "The label specified in --cs_fdo_profile")
  public Label getCSFdoProfileLabel() {
    return cppOptions.csFdoProfileLabel;
  }

  @Nullable
  @StarlarkConfigurationField(
      name = "propeller_optimize",
      doc = "The label specified in --propeller_optimize")
  public Label getPropellerOptimizeLabel() {
    if (cppOptions.fdoInstrumentForBuild != null || cppOptions.csFdoInstrumentForBuild != null) {
      return null;
    }
    return cppOptions.getPropellerOptimizeLabel();
  }

  @Nullable
  @StarlarkConfigurationField(name = "xbinary_fdo", doc = "The label specified in --xbinary_fdo")
  public Label getXFdoProfileLabel() {
    if (cppOptions.fdoOptimizeForBuild != null
        || cppOptions.fdoInstrumentForBuild != null
        || cppOptions.fdoProfileLabel != null
        || collectCodeCoverage) {
      return null;
    }

    return cppOptions.xfdoProfileLabel;
  }

  @Nullable
  @StarlarkConfigurationField(
      name = "memprof_profile",
      doc = "The memprof profile label for cc_toolchain rule")
  public Label getMemProfProfileLabel() {
    return cppOptions.getMemProfProfileLabel();
  }

  public boolean useLLVMCoverageMapFormat() {
    return cppOptions.useLLVMCoverageMapFormat;
  }

  @Nullable
  public static PathFragment computeDefaultSysroot(String builtInSysroot) {
    if (builtInSysroot.isEmpty()) {
      return null;
    }
    return PathFragment.create(builtInSysroot);
  }

  /**
   * Returns the value of the libc top-level directory (--grte_top) as specified on the command line
   */
  @StarlarkConfigurationField(name = "libc_top", doc = "The libc_top label for cc_toolchain.")
  public Label getLibcTopLabel() {
    return cppOptions.libcTopLabel;
  }

  @Override
  public Label getLibcTopLabelStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getLibcTopLabel();
  }

  @Override
  public boolean shareNativeDepsStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return shareNativeDeps();
  }

  /**
   * Returns the value of the libc top-level directory (--grte_top) as specified on the command line
   */
  @Nullable
  @StarlarkConfigurationField(
      name = "target_libc_top_DO_NOT_USE_ONLY_FOR_CC_TOOLCHAIN",
      doc = "DO NOT USE")
  public Label getTargetLibcTopLabel() {
    if (!isToolConfigurationDoNotUseWillBeRemovedFor129045294) {
      // This isn't for a platform-enabled C++ toolchain (legacy C++ toolchains evaluate in the
      // target configuration while platform-enabled toolchains evaluate in the exec configuration).
      // targetLibcTopLabel is only intended for platform-enabled toolchains and can cause errors
      // otherwise.
      //
      // For example: if a legacy-configured toolchain inherits a --grte_top pointing to an Android
      // runtime alias that select()s on a target Android CPU and an iOS dep changes the CPU to an
      // iOS CPU, the alias resolution fails. Legacy toolchains should read --grte_top through
      // libcTopLabel (which changes along with the iOS CPU change), not this.
      return null;
    }
    return cppOptions.targetLibcTopLabel;
  }

  public boolean dontEnableHostNonhost() {
    return cppOptions.dontEnableHostNonhost;
  }

  public boolean collectCodeCoverage() {
    return collectCodeCoverage;
  }

  public boolean saveFeatureState() {
    return cppOptions.saveFeatureState;
  }

  public boolean useSpecificToolFiles() {
    return cppOptions.useSpecificToolFiles;
  }

  @StarlarkMethod(
      name = "incompatible_use_specific_tool_files",
      documented = false,
      useStarlarkThread = true)
  public boolean useSpecificToolFilesForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.useSpecificToolFiles;
  }

  public boolean disableNoCopts() {
    return cppOptions.disableNoCopts;
  }

  @Override
  public boolean disableNocoptsStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return disableNoCopts();
  }

  @Override
  public boolean appleGenerateDsym() {
    return appleGenerateDsym;
  }

  public boolean experimentalStarlarkCcImport() {
    return cppOptions.experimentalStarlarkCcImport;
  }

  public boolean useCppCompileHeaderMnemonic() {
    return cppOptions.useCppCompileHeaderMnemonic;
  }

  public boolean generateLlvmLCov() {
    return cppOptions.generateLlvmLcov;
  }

  public boolean experimentalIncludeScanning() {
    return cppOptions.experimentalIncludeScanning;
  }

  public boolean objcShouldGenerateDotdFiles() {
    return cppOptions.objcGenerateDotdFiles;
  }

  @Override
  public boolean objcGenerateLinkmap() {
    return cppOptions.objcGenerateLinkmap;
  }

  public boolean objcEnableBinaryStripping() {
    return cppOptions.objcEnableBinaryStripping;
  }

  @StarlarkMethod(
      name = "objc_enable_binary_stripping",
      documented = false,
      useStarlarkThread = true)
  public boolean objcEnableBinaryStrippingForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.objcEnableBinaryStripping;
  }

  @StarlarkMethod(
      name = "experimental_cc_implementation_deps",
      documented = false,
      useStarlarkThread = true)
  public boolean experimentalCcImplementationDepsForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return experimentalCcImplementationDeps();
  }

  @StarlarkMethod(name = "experimental_cpp_modules", documented = false, useStarlarkThread = true)
  public boolean experimentalCppModulesForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return experimentalCppModules();
  }

  public boolean experimentalCcImplementationDeps() {
    return cppOptions.experimentalCcImplementationDeps;
  }

  public boolean experimentalCppModules() {
    return cppOptions.experimentalCppModules;
  }

  public boolean getExperimentalCppCompileResourcesEstimation() {
    return cppOptions.experimentalCppCompileResourcesEstimation;
  }

  @Override
  public boolean macosSetInstallName() {
    return cppOptions.macosSetInstallName;
  }

  private static void checkInExpandedApiAllowlist(StarlarkThread thread, String feature)
      throws EvalException {
    try {
      BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    } catch (EvalException e) {
      throw Starlark.errorf("%s (feature '%s' in CppConfiguration)", e.getMessage(), feature);
    }
  }

  @Override
  public boolean forcePicStarlark(StarlarkThread thread) throws EvalException {
    checkInExpandedApiAllowlist(thread, "force_pic");
    return forcePic();
  }

  @Override
  public boolean generateLlvmLcovStarlark(StarlarkThread thread) throws EvalException {
    checkInExpandedApiAllowlist(thread, "generate_llvm_lcov");
    return generateLlvmLCov();
  }

  @Nullable
  @Override
  public String fdoInstrumentStarlark(StarlarkThread thread) throws EvalException {
    checkInExpandedApiAllowlist(thread, "fdo_instrument");
    return getFdoInstrument();
  }

  @Override
  public boolean processHeadersInDependenciesStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return processHeadersInDependencies();
  }

  @Override
  public boolean saveFeatureStateStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return saveFeatureState();
  }

  @Override
  public boolean fissionActiveForCurrentCompilationModeStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return fissionIsActiveForCurrentCompilationMode();
  }

  @Override
  public boolean getExperimentalLinkStaticLibrariesOnce(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return experimentalLinkStaticLibrariesOnce();
  }

  @Override
  public boolean getExperimentalPlatformCcTest(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return experimentalPlatformCcTest();
  }

  @Override
  public boolean objcShouldStripBinary() {
    return objcEnableBinaryStripping() && getCompilationMode() == CompilationMode.OPT;
  }

  @StarlarkConfigurationField(name = "proto_profile_path")
  public Label getProtoProfilePath() {
    return cppOptions.protoProfilePath;
  }

  @StarlarkMethod(name = "proto_profile", useStarlarkThread = true, documented = false)
  public boolean getProtoProfile(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.protoProfile;
  }

  @StarlarkMethod(
      name = "experimental_starlark_linking",
      documented = false,
      useStarlarkThread = true)
  public boolean experimentalStarlarkLinking(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return cppOptions.experimentalStarlarkLinking;
  }
}
