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

import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuiltins;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.syntax.Location;

/** Information about a C++ compiler used by the <code>cc_*</code> rules. */
@Immutable
public final class CcToolchainProvider {

  public static final String STARLARK_NAME = "CcToolchainInfo";
  public static final CcToolchainInfoProvider PROVIDER = new CcToolchainInfoProvider();

  /** Provider class for {@link CcToolchainProvider} objects. */
  public static class CcToolchainInfoProvider extends StarlarkProviderWrapper<CcToolchainProvider>
      implements Provider {
    public CcToolchainInfoProvider() {
      super(
          keyForBuiltins(
              Label.parseCanonicalUnchecked("@_builtins//:common/cc/cc_toolchain_info.bzl")),
          STARLARK_NAME);
    }

    public CcToolchainProvider wrapOrThrowEvalException(Info value) throws EvalException {
      if (value instanceof StarlarkInfoWithSchema
          && value.getProvider().getKey().equals(getKey())) {
        return new CcToolchainProvider((StarlarkInfo) value);
      } else {
        throw new EvalException(
            String.format("got value of type '%s', want 'CcToolchainInfo'", Starlark.type(value)));
      }
    }

    @Override
    public CcToolchainProvider wrap(Info value) throws RuleErrorException {
      if (value instanceof StarlarkInfoWithSchema
          && value.getProvider().getKey().equals(getKey())) {
        return new CcToolchainProvider((StarlarkInfo) value);
      } else {
        throw new RuleErrorException(
            "got value of type '" + Starlark.type(value) + "', want 'CcToolchainInfo'");
      }
    }

    @Override
    public boolean isExported() {
      return true;
    }

    @Override
    public String getPrintableName() {
      return STARLARK_NAME;
    }

    @Override
    public Location getLocation() {
      return Location.BUILTIN;
    }
  }

  @Nullable
  private static final NestedSet<Artifact> nullOrDepset(StarlarkInfo value, String key)
      throws EvalException, TypeException {
    if (value.getValue(key) == null || value.getValue(key) == Starlark.NONE) {
      return null;
    }
    return value.getValue(key, Depset.class).getSet(Artifact.class);
  }

  @Nullable
  private static final PathFragment nullOrPathFragment(StarlarkInfo value, String key)
      throws EvalException {
    if (value.getValue(key) == null || value.getValue(key) == Starlark.NONE) {
      return null;
    }
    return PathFragment.create(value.getValue(key, String.class));
  }

  private static final ImmutableList<PathFragment> convertStarlarkListToPathFragments(
      StarlarkInfo value, String key) throws EvalException {
    ImmutableList.Builder<PathFragment> pathFragments = ImmutableList.builder();
    for (String pathString :
        Sequence.cast(value.getValue(key, Sequence.class), String.class, key)) {
      pathFragments.add(PathFragment.create(pathString));
    }
    return pathFragments.build();
  }

  private final StarlarkInfo value;

  private CcToolchainProvider(StarlarkInfo value) {
    this.value = value;
  }

  @VisibleForTesting
  public StarlarkInfo getValue() {
    return value;
  }

  public static CcToolchainProvider create(StarlarkInfo value) {
    return new CcToolchainProvider(value);
  }

  // LINT.IfChange
  /**
   * Determines if we should apply -fPIC for this rule's C++ compilations. This determination is
   * generally made by the global C++ configuration settings "needsPic" and "usePicForBinaries".
   * However, an individual rule may override these settings by applying -fPIC" to its "nocopts"
   * attribute. This allows incompatible rules to "opt out" of global PIC settings (see bug:
   * "Provide a way to turn off -fPIC for targets that can't be built that way").
   *
   * @return true if this rule's compilations should apply -fPIC, false otherwise
   */
  public static boolean usePicForDynamicLibraries(
      CppConfiguration cppConfiguration, FeatureConfiguration featureConfiguration) {
    return cppConfiguration.forcePic()
        || featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC);
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)

  /**
   * Returns true if PER_OBJECT_DEBUG_INFO are specified and supported by the CROSSTOOL for the
   * build implied by the given configuration, toolchain and feature configuration.
   */
  public static boolean shouldCreatePerObjectDebugInfo(
      FeatureConfiguration featureConfiguration, CppConfiguration cppConfiguration) {
    return cppConfiguration.fissionIsActiveForCurrentCompilationMode()
        && featureConfiguration.isEnabled(CppRuleClasses.PER_OBJECT_DEBUG_INFO);
  }

  /** Whether the toolchains supports header parsing. */
  public boolean supportsHeaderParsing() throws EvalException {
    return value.getValue("_supports_header_parsing", Boolean.class);
  }

  /**
   * Returns true if headers should be parsed in this build.
   *
   * <p>This means headers in 'srcs' and 'hdrs' will be "compiled" using {@link CppCompileAction}).
   * It will run compiler's parser to ensure the header is self-contained. This is required for
   * layering_check to work.
   */
  public static boolean shouldProcessHeaders(
      FeatureConfiguration featureConfiguration, CppConfiguration cppConfiguration) {
    return featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS);
  }

  /**
   * Returns the path String that is either absolute or relative to the execution root that can be
   * used to execute the given tool.
   *
   * @throws RuleErrorException when the tool is not specified by the toolchain.
   */
  public static String getToolPathString(
      ImmutableMap<String, String> toolPaths,
      CppConfiguration.Tool tool,
      Label ccToolchainLabel,
      String toolchainIdentifier)
      throws EvalException {
    String toolPath = getToolPathStringOrNull(toolPaths, tool);
    if (toolPath == null) {
      throw Starlark.errorf(
          "cc_toolchain '%s' with identifier '%s' doesn't define a tool path for '%s'",
          ccToolchainLabel, toolchainIdentifier, tool.getNamePart());
    }
    return toolPath;
  }

  /**
   * Returns the path string that is either absolute or relative to the execution root that can be
   * used to execute the given tool.
   */
  public static String getToolPathStringOrNull(ImmutableMap<String, String> toolPaths, Tool tool) {
    return toolPaths.get(tool.getNamePart());
  }

  public ImmutableMap<String, String> getToolPaths() throws EvalException {
    return ImmutableMap.copyOf(
        Dict.cast(
            value.getValue("_tool_paths", Dict.class), String.class, String.class, "_tool_paths"));
  }

  public ImmutableList<PathFragment> getBuiltInIncludeDirectories() throws EvalException {
    return convertStarlarkListToPathFragments(value, "built_in_include_directories");
  }

  public String getCompiler() throws EvalException {
    return value.getValue("compiler", String.class);
  }

  /** Returns the identifier of the toolchain as specified in the {@code CToolchain} proto. */
  public String getToolchainIdentifier() throws EvalException {
    return value.getValue("toolchain_id", String.class);
  }

  /** Returns all the files in Crosstool. */
  public NestedSet<Artifact> getAllFiles() throws EvalException {
    try {
      return value.getValue("all_files", Depset.class).getSet(Artifact.class);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  /** Returns all the files in Crosstool + libc. */
  public NestedSet<Artifact> getAllFilesIncludingLibc() throws EvalException {
    try {
      return value.getValue("_all_files_including_libc", Depset.class).getSet(Artifact.class);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  /** Returns the files necessary for compilation. */
  public NestedSet<Artifact> getCompilerFiles() throws EvalException {
    try {
      return value.getValue("_compiler_files", Depset.class).getSet(Artifact.class);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  /**
   * Returns the files necessary for compilation excluding headers, assuming that included files
   * will be discovered by input discovery.
   */
  public NestedSet<Artifact> getCompilerFilesWithoutIncludes() throws EvalException {
    try {
      return value
          .getValue("_compiler_files_without_includes", Depset.class)
          .getSet(Artifact.class);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  /**
   * Returns the files necessary for an 'as' invocation. May be empty if the CROSSTOOL file does not
   * define as_files.
   */
  public NestedSet<Artifact> getAsFiles() throws EvalException {
    try {
      return value.getValue("_as_files", Depset.class).getSet(Artifact.class);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  /**
   * Returns the files necessary for an 'ar' invocation. May be empty if the CROSSTOOL file does not
   * define ar_files.
   */
  public NestedSet<Artifact> getArFiles() throws EvalException, TypeException {
    return value.getValue("_ar_files", Depset.class).getSet(Artifact.class);
  }

  /** Returns the files necessary for linking, including the files needed for libc. */
  public NestedSet<Artifact> getLinkerFiles() throws EvalException, TypeException {
    return value.getValue("_linker_files", Depset.class).getSet(Artifact.class);
  }

  /** Returns the files necessary for capturing code coverage. */
  @VisibleForTesting
  public NestedSet<Artifact> getCoverageFiles() throws EvalException, TypeException {
    return value.getValue("_coverage_files", Depset.class).getSet(Artifact.class);
  }

  /**
   * Returns true if the featureConfiguration includes statically linking the cpp runtimes.
   *
   * @param featureConfiguration the relevant FeatureConfiguration.
   */
  private static boolean shouldStaticallyLinkCppRuntimes(
      FeatureConfiguration featureConfiguration) {
    return featureConfiguration.isEnabled(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES);
  }

  @Nullable
  public NestedSet<Artifact> getStaticRuntimeLinkInputs() throws EvalException, TypeException {
    return nullOrDepset(value, "_static_runtime_lib_depset");
  }

  /** Returns the static runtime libraries. */
  public static NestedSet<Artifact> getStaticRuntimeLinkInputsOrThrowError(
      NestedSet<Artifact> staticRuntimeLinkInputs, FeatureConfiguration featureConfiguration)
      throws EvalException {
    if (shouldStaticallyLinkCppRuntimes(featureConfiguration)) {
      if (staticRuntimeLinkInputs == null) {
        throw Starlark.errorf(
            "Toolchain supports embedded runtimes, but didn't provide static_runtime_lib"
                + " attribute.");
      }
      return staticRuntimeLinkInputs;
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  @Nullable
  public NestedSet<Artifact> getDynamicRuntimeLinkInputs() throws EvalException, TypeException {
    return nullOrDepset(value, "_dynamic_runtime_lib_depset");
  }

  /** Returns the dynamic runtime libraries. */
  public static NestedSet<Artifact> getDynamicRuntimeLinkInputsOrThrowError(
      NestedSet<Artifact> dynamicRuntimeLinkInputs, FeatureConfiguration featureConfiguration)
      throws EvalException {
    if (shouldStaticallyLinkCppRuntimes(featureConfiguration)) {
      if (dynamicRuntimeLinkInputs == null) {
        throw new EvalException(
            "Toolchain supports embedded runtimes, but didn't provide dynamic_runtime_lib"
                + " attribute.");
      }
      return dynamicRuntimeLinkInputs;
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  /**
   * Returns the name of the directory where the solib symlinks for the dynamic runtime libraries
   * live. The directory itself will be under the root of the exec configuration in the 'bin'
   * directory.
   */
  public PathFragment getDynamicRuntimeSolibDir() throws EvalException {
    return PathFragment.create(value.getValue("dynamic_runtime_solib_dir", String.class));
  }

  /** Returns the {@code CcInfo} for the toolchain. */
  public CcInfo getCcInfo() throws EvalException {
    return value.getValue("_cc_info", CcInfo.class);
  }

  /** Whether the toolchains supports parameter files. */
  public boolean supportsParamFiles() throws EvalException {
    return value.getValue("_supports_param_files", Boolean.class);
  }

  /** Returns the configured features of the toolchain. */
  @Nullable
  public CcToolchainFeatures getFeatures() throws EvalException {
    return value.getValue("_toolchain_features", CcToolchainFeatures.class);
  }

  public Label getCcToolchainLabel() throws EvalException {
    return value.getValue("_toolchain_label", Label.class);
  }

  /**
   * Return the name of the directory (relative to the bin directory) that holds mangled links to
   * shared libraries. This name is always set to the '{@code _solib_<cpu_archictecture_name>}.
   */
  public String getSolibDirectory() throws EvalException {
    return value.getValue("_solib_dir", String.class);
  }

  /** Returns whether this toolchain supports interface shared libraries. */
  // TODO(gnish): Move this to FeatureConfiguration.
  public static boolean supportsInterfaceSharedLibraries(
      FeatureConfiguration featureConfiguration) {
    return featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES);
  }

  /** Return context-sensitive fdo instrumentation path. */
  public String getCSFdoInstrument() throws EvalException {
    CppConfiguration cppConfiguration =
        value.getValue("_cpp_configuration", CppConfiguration.class);
    return cppConfiguration.getCSFdoInstrument();
  }

  public CcToolchainVariables getBuildVars() throws EvalException {
    return getValue().getValue("_build_variables", CcToolchainVariables.class);
  }

  /**
   * Return the set of include files that may be included even if they are not mentioned in the
   * source file or any of the headers included by it.
   */
  public ImmutableList<Artifact> getBuiltinIncludeFiles() throws EvalException {
    return Sequence.cast(
            value.getValue("_builtin_include_files", Sequence.class),
            Artifact.class,
            "_builtin_include_files")
        .getImmutableList();
  }

  /**
   * Returns the tool which should be used for linking dynamic libraries, or in case it's not
   * specified by the crosstool this will be @tools_repository/tools/cpp:link_dynamic_library
   */
  public Artifact getLinkDynamicLibraryTool() throws EvalException {
    return value.getValue("_link_dynamic_library_tool", Artifact.class);
  }

  /** Returns the grep-includes tool which is needing during linking because of linkstamping. */
  @Nullable
  public Artifact getGrepIncludes() throws EvalException {
    return value.getNoneableValue("_grep_includes", Artifact.class);
  }

  /** Returns the tool that aggregates all .ddi files to .CXXModules.json file. */
  @Nullable
  public Artifact getAggregateDdi() throws EvalException {
    return value.getNoneableValue("_aggregate_ddi", Artifact.class);
  }

  /** Returns the tool that generates .modmap file according to .CXXModules.json and .ddi file. */
  @Nullable
  public Artifact getGenerateModmap() throws EvalException {
    return value.getNoneableValue("_generate_modmap", Artifact.class);
  }

  /** Returns the tool that builds interface libraries from dynamic libraries. */
  public Artifact getInterfaceSoBuilder() throws EvalException {
    return value.getValue("_if_so_builder", Artifact.class);
  }

  @Nullable
  public String getSysroot() throws EvalException {
    PathFragment sysroot = nullOrPathFragment(value, "sysroot");
    return sysroot != null ? sysroot.getPathString() : null;
  }

  @Nullable
  public PathFragment getSysrootPathFragment() throws EvalException {
    return nullOrPathFragment(value, "sysroot");
  }

  /**
   * Returns the abi we're using, which is a gcc version. E.g.: "gcc-3.4". Note that in practice we
   * might be using gcc-3.4 as ABI even when compiling with gcc-4.1.0, because ABIs are backwards
   * compatible.
   */
  // TODO(bazel-team): The javadoc should clarify how this is used in Blaze.
  @VisibleForTesting
  public String getAbi() throws EvalException {
    return value.getValue("_abi", String.class);
  }

  /** Returns the target architecture using blaze-specific constants (e.g. "piii"). */
  public String getTargetCpu() throws EvalException {
    return value.getValue("cpu", String.class);
  }

  /**
   * Returns the legacy value of the CC_FLAGS Make variable.
   *
   * @deprecated Use the CC_FLAGS from feature configuration instead.
   */
  // TODO(b/65151735): Remove when cc_flags is entirely from features.
  @Deprecated
  public String getLegacyCcFlagsMakeVariable() throws EvalException {
    return value.getValue("_legacy_cc_flags_make_variable", String.class);
  }

  public FdoContext getFdoContext() throws EvalException {
    return new FdoContext(value.getValue("_fdo_context", StructImpl.class));
  }

  // Not all of CcToolchainProvider is exposed to Starlark, which makes implementing deep equality
  // impossible: if Java-only parts are considered, the behavior is surprising in Starlark, if they
  // are not, the behavior is surprising in Java. Thus, object identity it is.
  @Override
  public boolean equals(Object other) {
    return other == this;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  public boolean isToolConfiguration() throws EvalException {
    return value.getValue("_is_tool_configuration", Boolean.class);
  }

  public PackageSpecificationProvider getAllowlistForLayeringCheck() throws EvalException {
    return value.getValue("_allowlist_for_layering_check", PackageSpecificationProvider.class);
  }

  public OutputGroupInfo getCcBuildInfoTranslator() throws EvalException {
    return value.getValue("_build_info_files", OutputGroupInfo.class);
  }

  public CppConfiguration getCppConfiguration() throws EvalException {
    return value.getValue("_cpp_configuration", CppConfiguration.class);
  }
}
