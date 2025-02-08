// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.analysis.LicensesProviderImpl;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.PathMappers;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory;
import com.google.devtools.build.lib.analysis.starlark.StarlarkActionFactory.StarlarkActionContext;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.LibraryToLinkValue;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.SequenceBuilder;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariableValue;
import com.google.devtools.build.lib.rules.cpp.CppLinkActionBuilder.LinkActionConstruction;
import com.google.devtools.build.lib.rules.cpp.LegacyLinkerInputs.LibraryInput;
import com.google.devtools.build.lib.rules.cpp.LibrariesToLinkCollector.CollectedLibrariesToLink;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.NativeComputedDefaultApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** Utility methods for rules in Starlark Builtins */
@StarlarkBuiltin(name = "cc_internal", category = DocCategory.BUILTIN, documented = false)
public class CcStarlarkInternal implements StarlarkValue {

  public static final String NAME = "cc_internal";

  /**
   * Wraps a dictionary of build variables into CcToolchainVariables.
   *
   * <p>TODO(b/338618120): This code helps during the transition of cc_common.link and
   * cc_common.compile code to Starlark. Once that code is in Starlark, CcToolchainVariables rewrite
   * may commence, most likely turning them into a regular Starlark dict (or a dict with parent if
   * that optimisation is still needed).
   */
  @StarlarkMethod(
      name = "cc_toolchain_variables",
      documented = false,
      parameters = {
        @Param(name = "vars", positional = false, named = true),
      })
  @SuppressWarnings("unchecked")
  public CcToolchainVariables getCcToolchainVariables(Dict<?, ?> buildVariables)
      throws TypeException {

    CcToolchainVariables.Builder ccToolchainVariables = CcToolchainVariables.builder();
    for (var entry : buildVariables.entrySet()) {
      String key = (String) entry.getKey();
      Object value = entry.getValue();
      switch (value) {
        case String s -> ccToolchainVariables.addStringVariable(key, s);
        case Artifact a -> ccToolchainVariables.addArtifactVariable(key, a);
        case Boolean b -> ccToolchainVariables.addBooleanValue(key, b);
        case Iterable<?> values -> {
          if (key.equals("libraries_to_link")) {
            SequenceBuilder sb = new SequenceBuilder();
            for (var v : (Iterable<VariableValue>) values) {
              sb.addValue(v);
            }
            ccToolchainVariables.addCustomBuiltVariable(key, sb);
          } else {
            ccToolchainVariables.addStringSequenceVariable(key, (Iterable<String>) values);
          }
        }
        case Depset depset -> {
          Class<?> type = depset.getElementClass();
          // Type doesn't matter for empty depsets.
          if (type == String.class || type == null) {
            ccToolchainVariables.addStringSequenceVariable(key, depset.getSet(String.class));
          } else if (type == Artifact.class) {
            ccToolchainVariables.addArtifactSequenceVariable(key, depset.getSet(Artifact.class));
          } else if (type == PathFragment.class) {
            ccToolchainVariables.addPathFragmentSequenceVariable(
                key, depset.getSet(PathFragment.class));
          } else {
            throw new IllegalStateException("Unexpected depset element type: %s".formatted(type));
          }
        }
        case NoneType ignored -> {}
        default ->
            throw new IllegalStateException(
                "Unexpected value: %s (%s)".formatted(value, value.getClass()));
      }
    }
    return ccToolchainVariables.build();
  }

  @StarlarkMethod(
      name = "solib_symlink_action",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
        @Param(name = "artifact", positional = false, named = true),
        @Param(name = "solib_directory", positional = false, named = true),
        @Param(name = "runtime_solib_dir_base", positional = false, named = true),
      })
  public Artifact solibSymlinkAction(
      StarlarkRuleContext ruleContext,
      Artifact artifact,
      String solibDirectory,
      String runtimeSolibDirBase) {
    return SolibSymlinkAction.getCppRuntimeSymlink(
        ruleContext.getRuleContext(), artifact, solibDirectory, runtimeSolibDirBase);
  }

  @StarlarkMethod(
      name = "dynamic_library_symlink",
      documented = false,
      parameters = {
        @Param(name = "actions"),
        @Param(name = "library"),
        @Param(name = "solib_directory"),
        @Param(name = "preserve_name"),
        @Param(name = "prefix_consumer"),
      })
  public Artifact dynamicLibrarySymlinkAction(
      StarlarkActionFactory actions,
      Artifact library,
      String solibDirectory,
      boolean preserveName,
      boolean prefixConsumer) {
    return SolibSymlinkAction.getDynamicLibrarySymlink(
        actions.getRuleContext(), solibDirectory, library, preserveName, prefixConsumer);
  }

  @StarlarkMethod(
      name = "dynamic_library_soname",
      documented = false,
      parameters = {
        @Param(name = "actions"),
        @Param(name = "path"),
        @Param(name = "preserve_name"),
      })
  public String dynamicLibrarySoname(
      WrappedStarlarkActionFactory actions, String path, boolean preserveName) {

    return SolibSymlinkAction.getDynamicLibrarySoname(
        PathFragment.create(path),
        preserveName,
        actions.construction.getContext().getConfiguration().getMnemonic());
  }

  @StarlarkMethod(
      name = "cc_toolchain_features",
      documented = false,
      parameters = {
        @Param(name = "toolchain_config_info", positional = false, named = true),
        @Param(name = "tools_directory", positional = false, named = true),
      })
  public CcToolchainFeatures ccToolchainFeatures(
      CcToolchainConfigInfo ccToolchainConfigInfo, String toolsDirectoryPathString)
      throws EvalException {
    return new CcToolchainFeatures(
        ccToolchainConfigInfo, PathFragment.create(toolsDirectoryPathString));
  }

  @StarlarkMethod(
      name = "is_package_headers_checking_mode_set",
      documented = false,
      parameters = {@Param(name = "ctx", positional = false, named = true)})
  public boolean isPackageHeadersCheckingModeSetForStarlark(
      StarlarkRuleContext starlarkRuleContext) {
    return starlarkRuleContext
        .getRuleContext()
        .getRule()
        .getPackage()
        .getPackageArgs()
        .isDefaultHdrsCheckSet();
  }

  @StarlarkMethod(
      name = "package_headers_checking_mode",
      documented = false,
      parameters = {@Param(name = "ctx", positional = false, named = true)})
  public String getPackageHeadersCheckingModeForStarlark(StarlarkRuleContext starlarkRuleContext) {
    return starlarkRuleContext
        .getRuleContext()
        .getRule()
        .getPackage()
        .getPackageArgs()
        .getDefaultHdrsCheck();
  }

  @StarlarkMethod(
      name = "is_package_headers_checking_mode_set_for_aspect",
      documented = false,
      parameters = {@Param(name = "ctx", positional = false, named = true)})
  public boolean isPackageHeadersCheckingModeSetForStarlarkAspect(
      StarlarkRuleContext starlarkRuleContext) {
    return starlarkRuleContext
        .getRuleContext()
        .getTarget()
        .getPackage()
        .getPackageArgs()
        .isDefaultHdrsCheckSet();
  }

  @StarlarkMethod(
      name = "package_headers_checking_mode_for_aspect",
      documented = false,
      parameters = {@Param(name = "ctx", positional = false, named = true)})
  public String getPackageHeadersCheckingModeForStarlarkAspect(
      StarlarkRuleContext starlarkRuleContext) {
    return starlarkRuleContext
        .getRuleContext()
        .getTarget()
        .getPackage()
        .getPackageArgs()
        .getDefaultHdrsCheck();
  }

  @StarlarkMethod(
      name = "create_common",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
      })
  public CcCommon createCommon(StarlarkRuleContext starlarkRuleContext) {
    return new CcCommon(starlarkRuleContext.getRuleContext());
  }

  @StarlarkMethod(name = "launcher_provider", documented = false, structField = true)
  public ProviderApi getCcLauncherInfoProvider() throws EvalException {
    return CcLauncherInfo.PROVIDER;
  }

  @StarlarkMethod(
      name = "create_linkstamp",
      documented = false,
      parameters = {
        @Param(name = "actions", positional = false, named = true),
        @Param(name = "linkstamp", positional = false, named = true),
        @Param(name = "compilation_context", positional = false, named = true),
      })
  public Linkstamp createLinkstamp(
      StarlarkActionFactory starlarkActionFactoryApi,
      Artifact linkstamp,
      CcCompilationContext ccCompilationContext)
      throws EvalException {
    try {
      return new Linkstamp( // throws InterruptedException
          linkstamp,
          ccCompilationContext.getDeclaredIncludeSrcs(),
          starlarkActionFactoryApi.getRuleContext().getActionKeyContext());
    } catch (CommandLineExpansionException | InterruptedException ex) {
      throw new EvalException(ex);
    }
  }

  /**
   * TODO(bazel-team): This can be re-written directly to Starlark but it will cause a memory
   * regression due to the way StarlarkComputedDefault is stored for each rule.
   */
  static class StlComputedDefault extends ComputedDefault implements NativeComputedDefaultApi {
    @Override
    @Nullable
    public Object getDefault(AttributeMap rule) {
      return rule.getOrDefault("tags", Types.STRING_LIST, ImmutableList.of()).contains("__CC_STL__")
          ? null
          : Label.parseCanonicalUnchecked("@//third_party/stl");
    }
  }

  @StarlarkMethod(name = "stl_computed_default", documented = false)
  public ComputedDefault getStlComputedDefault() {
    return new StlComputedDefault();
  }

  @StarlarkMethod(
      name = "create_cc_launcher_info",
      doc = "Create a CcLauncherInfo instance.",
      parameters = {
        @Param(
            name = "cc_info",
            positional = false,
            named = true,
            doc = "CcInfo instance.",
            allowedTypes = {@ParamType(type = CcInfo.class)}),
        @Param(
            name = "compilation_outputs",
            positional = false,
            named = true,
            doc = "CcCompilationOutputs instance.",
            allowedTypes = {@ParamType(type = CcCompilationOutputs.class)})
      })
  public CcLauncherInfo createCcLauncherInfo(
      CcInfo ccInfo, CcCompilationOutputs compilationOutputs) {
    return new CcLauncherInfo(ccInfo, compilationOutputs);
  }

  @SerializationConstant @VisibleForSerialization
  static final StarlarkProvider starlarkCcTestRunnerInfo =
      StarlarkProvider.builder(Location.BUILTIN)
          .buildExported(
              new StarlarkProvider.Key(
                  keyForBuild(Label.parseCanonicalUnchecked("//tools/cpp/cc_test:toolchain.bzl")),
                  "CcTestRunnerInfo"));

  @StarlarkMethod(name = "CcTestRunnerInfo", documented = false, structField = true)
  public StarlarkProvider ccTestRunnerInfo() throws EvalException {
    return starlarkCcTestRunnerInfo;
  }

  @StarlarkMethod(
      name = "create_umbrella_header_action",
      documented = false,
      parameters = {
        @Param(name = "actions", positional = false, named = true),
        @Param(name = "umbrella_header", positional = false, named = true),
        @Param(name = "public_headers", positional = false, named = true),
        @Param(name = "additional_exported_headers", positional = false, named = true),
      })
  public void createUmbrellaHeaderAction(
      StarlarkActionFactory actions,
      Artifact umbrellaHeader,
      Sequence<?> publicHeaders,
      Sequence<?> additionalExportedHeaders)
      throws EvalException {
    actions
        .getRuleContext()
        .registerAction(
            new UmbrellaHeaderAction(
                actions.getRuleContext().getActionOwner(),
                umbrellaHeader,
                Sequence.cast(publicHeaders, Artifact.class, "public_headers"),
                Sequence.cast(
                        additionalExportedHeaders, String.class, "additional_exported_headers")
                    .stream()
                    .map(PathFragment::create)
                    .collect(toImmutableList())));
  }

  @StarlarkMethod(
      name = "create_module_map_action",
      documented = false,
      parameters = {
        @Param(name = "actions", positional = false, named = true),
        @Param(name = "feature_configuration", positional = false, named = true),
        @Param(name = "module_map", positional = false, named = true),
        @Param(name = "private_headers", positional = false, named = true),
        @Param(name = "public_headers", positional = false, named = true),
        @Param(name = "dependent_module_maps", positional = false, named = true),
        @Param(name = "additional_exported_headers", positional = false, named = true),
        @Param(name = "separate_module_headers", positional = false, named = true),
        @Param(name = "compiled_module", positional = false, named = true),
        @Param(name = "module_map_home_is_cwd", positional = false, named = true),
        @Param(name = "generate_submodules", positional = false, named = true),
        @Param(name = "without_extern_dependencies", positional = false, named = true),
      })
  public void createModuleMapAction(
      StarlarkActionFactory actions,
      FeatureConfigurationForStarlark featureConfigurationForStarlark,
      CppModuleMap moduleMap,
      Sequence<?> privateHeaders,
      Sequence<?> publicHeaders,
      Sequence<?> dependentModuleMaps,
      Sequence<?> additionalExportedHeaders,
      Sequence<?> separateModuleHeaders,
      Boolean compiledModule,
      Boolean moduleMapHomeIsCwd,
      Boolean generateSubmodules,
      Boolean withoutExternDependencies)
      throws EvalException {
    RuleContext ruleContext = actions.getRuleContext();
    ruleContext.registerAction(
        new CppModuleMapAction(
            ruleContext.getActionOwner(),
            moduleMap,
            Sequence.cast(privateHeaders, Artifact.class, "private_headers"),
            Sequence.cast(publicHeaders, Artifact.class, "public_headers"),
            Sequence.cast(dependentModuleMaps, CppModuleMap.class, "dependent_module_maps"),
            Sequence.cast(additionalExportedHeaders, String.class, "additional_exported_headers")
                .stream()
                .map(PathFragment::create)
                .collect(toImmutableList()),
            Sequence.cast(separateModuleHeaders, Artifact.class, "separate_module_headers"),
            compiledModule,
            moduleMapHomeIsCwd,
            generateSubmodules,
            withoutExternDependencies,
            PathMappers.getOutputPathsMode(ruleContext.getConfiguration()),
            ruleContext
                .getConfiguration()
                .modifiedExecutionInfo(ImmutableMap.of(), CppModuleMapAction.MNEMONIC)));
  }

  @SerializationConstant @VisibleForSerialization
  static final StarlarkProvider buildSettingInfo =
      StarlarkProvider.builder(Location.BUILTIN)
          .buildExported(
              new StarlarkProvider.Key(
                  keyForBuild(
                      Label.parseCanonicalUnchecked(
                          "//third_party/bazel_skylib/rules:common_settings.bzl")),
                  "BuildSettingInfo"));

  @StarlarkMethod(name = "BuildSettingInfo", documented = false, structField = true)
  public StarlarkProvider buildSettingInfo() throws EvalException {
    return buildSettingInfo;
  }

  @StarlarkMethod(
      name = "escape_label",
      documented = false,
      parameters = {
        @Param(name = "label", positional = false, named = true),
      })
  public String escapeLabel(Label label) {
    return Actions.escapeLabel(label);
  }

  @StarlarkMethod(
      name = "licenses",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
      },
      allowReturnNones = true)
  @Nullable
  public LicensesProvider getLicenses(StarlarkRuleContext starlarkRuleContext) {
    RuleContext ruleContext = starlarkRuleContext.getRuleContext();
    final License outputLicense =
        ruleContext.getRule().getToolOutputLicense(ruleContext.attributes());
    if (outputLicense != null && !outputLicense.equals(License.NO_LICENSE)) {
      final NestedSet<TargetLicense> license =
          NestedSetBuilder.create(
              Order.STABLE_ORDER, new TargetLicense(ruleContext.getLabel(), outputLicense));
      return new LicensesProviderImpl(
          license, new TargetLicense(ruleContext.getLabel(), outputLicense));
    } else {
      return null;
    }
  }

  @StarlarkMethod(
      name = "get_artifact_name_for_category",
      documented = false,
      parameters = {
        @Param(name = "cc_toolchain", positional = false, named = true),
        @Param(name = "category", positional = false, named = true),
        @Param(name = "output_name", positional = false, named = true),
      })
  public String getArtifactNameForCategory(Info ccToolchainInfo, String category, String outputName)
      throws RuleErrorException, EvalException {
    CcToolchainProvider ccToolchain = CcToolchainProvider.PROVIDER.wrap(ccToolchainInfo);
    return ccToolchain
        .getFeatures()
        .getArtifactNameForCategory(ArtifactCategory.valueOf(category), outputName);
  }

  @StarlarkMethod(
      name = "get_artifact_name_extension_for_category",
      documented = false,
      parameters = {
        @Param(name = "cc_toolchain", named = true),
        @Param(name = "category", named = true),
      })
  public String getArtifactNameExtensionForCategory(Info ccToolchainInfo, String category)
      throws RuleErrorException, EvalException {
    CcToolchainProvider ccToolchain = CcToolchainProvider.PROVIDER.wrap(ccToolchainInfo);
    return ccToolchain
        .getFeatures()
        .getArtifactNameExtensionForCategory(ArtifactCategory.valueOf(category));
  }

  @StarlarkMethod(
      name = "absolute_symlink",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
        @Param(name = "output", positional = false, named = true),
        @Param(name = "target_path", positional = false, named = true),
        @Param(name = "progress_message", positional = false, named = true),
      })
  // TODO(b/333997009): remove command line flags that specify FDO with absolute path
  public void absoluteSymlink(
      StarlarkActionContext ctx, Artifact output, String targetPath, String progressMessage) {
    SymlinkAction action =
        SymlinkAction.toAbsolutePath(
            ctx.getRuleContext().getActionOwner(),
            PathFragment.create(targetPath),
            output,
            progressMessage);
    ctx.getRuleContext().registerAction(action);
  }

  @StarlarkMethod(
      name = "for_static_library",
      documented = false,
      parameters = {@Param(name = "name"), @Param(name = "is_whole_archive", named = true)})
  public LibraryToLinkValue forStaticLibrary(String name, boolean isWholeArchive) {
    return LibraryToLinkValue.forStaticLibrary(name, isWholeArchive);
  }

  @StarlarkMethod(
      name = "for_object_file_group",
      documented = false,
      parameters = {@Param(name = "files"), @Param(name = "is_whole_archive", named = true)})
  public LibraryToLinkValue forObjectFileGroup(Sequence<?> files, boolean isWholeArchive)
      throws EvalException {
    return LibraryToLinkValue.forObjectFileGroup(
        ImmutableList.copyOf(Sequence.cast(files, Artifact.class, "files")), isWholeArchive);
  }

  @StarlarkMethod(
      name = "for_object_file",
      documented = false,
      parameters = {@Param(name = "name"), @Param(name = "is_whole_archive", named = true)})
  public LibraryToLinkValue forObjectFile(String name, boolean isWholeArchive) {
    return LibraryToLinkValue.forObjectFile(name, isWholeArchive);
  }

  @StarlarkMethod(
      name = "for_interface_library",
      documented = false,
      parameters = {@Param(name = "name")})
  public LibraryToLinkValue forInterfaceLibrary(String name) throws EvalException {
    return LibraryToLinkValue.forInterfaceLibrary(name);
  }

  @StarlarkMethod(
      name = "for_dynamic_library",
      documented = false,
      parameters = {@Param(name = "name")})
  public LibraryToLinkValue forDynamicLibrary(String name) throws EvalException {
    return LibraryToLinkValue.forDynamicLibrary(name);
  }

  @StarlarkMethod(
      name = "for_versioned_dynamic_library",
      documented = false,
      parameters = {@Param(name = "name"), @Param(name = "path")})
  public LibraryToLinkValue forVersionedDynamicLibrary(String name, String path)
      throws EvalException {
    return LibraryToLinkValue.forVersionedDynamicLibrary(name, path);
  }

  @StarlarkMethod(
      name = "simple_linker_input",
      documented = false,
      parameters = {
        @Param(name = "input"),
        @Param(name = "artifact_category", defaultValue = "'object_file'"),
        @Param(name = "disable_whole_archive", defaultValue = "False")
      })
  public LegacyLinkerInput simpleLinkerInput(
      Artifact input, String artifactCategory, boolean disableWholeArchive) {
    return LegacyLinkerInputs.simpleLinkerInput(
        input,
        ArtifactCategory.valueOf(Ascii.toUpperCase(artifactCategory)),
        /* disableWholeArchive= */ disableWholeArchive,
        input.getRootRelativePathString());
  }

  @StarlarkMethod(
      name = "linkstamp_linker_input",
      documented = false,
      parameters = {
        @Param(name = "input"),
      })
  public LegacyLinkerInput linkstampLinkerInput(Artifact input) {
    return LegacyLinkerInputs.linkstampLinkerInput(input);
  }

  @StarlarkMethod(
      name = "library_linker_input",
      documented = false,
      parameters = {
        @Param(name = "input", named = true),
        @Param(name = "artifact_category", named = true),
        @Param(name = "library_identifier", named = true),
        @Param(name = "object_files", named = true),
        @Param(name = "lto_compilation_context", named = true),
        @Param(name = "shared_non_lto_backends", defaultValue = "None", named = true),
        @Param(name = "must_keep_debug", defaultValue = "False", named = true),
        @Param(name = "disable_whole_archive", defaultValue = "False", named = true),
      })
  public LegacyLinkerInput libraryLinkerInput(
      Artifact input,
      String artifactCategory,
      String libraryIdentifier,
      Object objectFiles,
      Object ltoCompilationContext,
      Object sharedNonLtoBackends,
      boolean mustKeepDebug,
      boolean disableWholeArchive)
      throws EvalException {
    return LegacyLinkerInputs.newInputLibrary(
        input,
        ArtifactCategory.valueOf(artifactCategory),
        libraryIdentifier,
        objectFiles == Starlark.NONE
            ? null
            : Sequence.cast(objectFiles, Artifact.class, "object_files").getImmutableList(),
        ltoCompilationContext instanceof LtoCompilationContext lto ? lto : null,
        /* sharedNonLtoBackends= */ ImmutableMap.copyOf(
            Dict.noneableCast(
                sharedNonLtoBackends,
                Artifact.class,
                LtoBackendArtifacts.class,
                "shared_non_lto_backends")),
        mustKeepDebug,
        disableWholeArchive);
  }

  @StarlarkMethod(
      name = "solib_linker_input",
      documented = false,
      parameters = {
        @Param(name = "solib_symlink", named = true),
        @Param(name = "original", named = true),
        @Param(name = "library_identifier", named = true),
      })
  public LegacyLinkerInput solibLinkerInput(
      Artifact solibSymlink, Artifact original, String libraryIdentifier) throws EvalException {
    return LegacyLinkerInputs.solibLibraryInput(solibSymlink, original, libraryIdentifier);
  }

  @StarlarkMethod(
      name = "get_link_args",
      documented = false,
      parameters = {
        @Param(name = "action_name", positional = false, named = true),
        @Param(name = "feature_configuration", positional = false, named = true),
        @Param(name = "build_variables", positional = false, named = true),
        @Param(
            name = "parameter_file_type",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)}),
      })
  public Args getArgs(
      String actionName,
      FeatureConfigurationForStarlark featureConfiguration,
      CcToolchainVariables buildVariables,
      Object paramFileType)
      throws EvalException {
    LinkCommandLine.Builder linkCommandLineBuilder =
        new LinkCommandLine.Builder()
            .setActionName(actionName)
            .setBuildVariables(buildVariables)
            .setFeatureConfiguration(featureConfiguration.getFeatureConfiguration());
    if (paramFileType instanceof String) {
      linkCommandLineBuilder
          .setParameterFileType(ParameterFileType.valueOf((String) paramFileType))
          .setSplitCommandLine(true);
    }
    LinkCommandLine linkCommandLine = linkCommandLineBuilder.build();
    return Args.forRegisteredAction(
        new CommandLineAndParamFileInfo(linkCommandLine, linkCommandLine.getParamFileInfo()),
        ImmutableSet.of());
  }

  @StarlarkMethod(
      name = "convert_library_to_link_list_to_linker_input_list",
      documented = false,
      parameters = {
        @Param(name = "libraries_to_link"),
        @Param(name = "static_mode"),
        @Param(name = "for_dynamic_library"),
        @Param(name = "supports_dynamic_linker")
      })
  public StarlarkList<LibraryInput> convertLibraryToLinkListToLinkerInputList(
      Depset librariesToLink,
      boolean staticMode,
      boolean forDynamicLibrary,
      boolean supportsDynamicLinker)
      throws TypeException {
    return StarlarkList.copyOf(
        Mutability.IMMUTABLE,
        CcLinkingHelper.convertLibraryToLinkListToLinkerInputList(
            librariesToLink.getSet(LibraryToLink.class),
            staticMode,
            forDynamicLibrary,
            supportsDynamicLinker));
  }

  @StarlarkMethod(
      name = "collect_libraries_to_link",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "linker_inputs"),
        @Param(name = "cc_toolchain"),
        @Param(name = "feature_configuration"),
        @Param(name = "output"),
        @Param(name = "dynamic_library_solib_symlink_output"),
        @Param(name = "link_type"),
        @Param(name = "linking_mode"),
        @Param(name = "is_native_deps"),
        @Param(name = "need_whole_archive"),
        @Param(name = "solib_dir"),
        @Param(name = "toolchain_libraries_solib_dir"),
        @Param(name = "allow_lto_indexing"),
        @Param(name = "lto_mapping"),
        @Param(name = "workspace_name"),
      })
  public StructImpl collectLibrariesToLink(
      Sequence<?> linkerInputs,
      StarlarkInfo ccToolchain,
      FeatureConfigurationForStarlark featureConfiguration,
      Artifact output,
      Object dynamicLibrarySolibSymlinkOutput,
      StructImpl linkType,
      String linkingMode,
      boolean isNativeDeps,
      boolean needWholeArchive,
      String solibDir,
      String toolchainLibrariesSolibDir,
      boolean allowLtoIndexing,
      Dict<?, ?> ltoMapping,
      String workspaceName,
      StarlarkThread thread)
      throws EvalException {
    LibrariesToLinkCollector librariesToLinkCollector =
        new LibrariesToLinkCollector(
            isNativeDeps,
            CcToolchainProvider.create(ccToolchain),
            PathFragment.create(toolchainLibrariesSolibDir),
            LinkTargetType.valueOf(linkType.getValue("_name", String.class)),
            LinkingMode.valueOf(Ascii.toUpperCase(linkingMode)),
            output,
            PathFragment.create(solibDir),
            Dict.cast(ltoMapping, Artifact.class, Artifact.class, "lto_mapping"),
            featureConfiguration.getFeatureConfiguration(),
            allowLtoIndexing,
            Sequence.cast(linkerInputs, LegacyLinkerInput.class, "linker_inputs"),
            needWholeArchive,
            workspaceName,
            dynamicLibrarySolibSymlinkOutput == Starlark.NONE
                ? null
                : (Artifact) dynamicLibrarySolibSymlinkOutput);
    CollectedLibrariesToLink libs = librariesToLinkCollector.collectLibrariesToLink();
    return StructProvider.STRUCT.createStruct(
        Dict.immutableCopyOf(
            ImmutableMap.of(
                "libraries_to_link",
                    StarlarkList.immutableCopyOf(libs.getLibrariesToLink().getValues()),
                "expanded_linker_inputs",
                    StarlarkList.immutableCopyOf(libs.getExpandedLinkerInputs().toList()),
                "library_search_directories",
                    Depset.of(String.class, libs.getLibrarySearchDirectories()),
                "all_runtime_library_search_directories",
                    Depset.of(String.class, libs.getRuntimeLibrarySearchDirectories()))),
        thread);
  }

  @StarlarkMethod(
      name = "create_library_to_link",
      documented = false,
      parameters = {@Param(name = "library_to_link")})
  @SuppressWarnings("CheckReturnValue")
  public LibraryToLink createLibraryToLink(StructImpl libraryToLink) throws EvalException {
    LibraryToLink.Builder builder = LibraryToLink.builder();
    builder.setLibraryIdentifier(
        libraryToLink.getNoneableValue("library_identifier", String.class));
    builder.setDynamicLibrary(libraryToLink.getNoneableValue("dynamic_library", Artifact.class));
    builder.setResolvedSymlinkDynamicLibrary(
        libraryToLink.getNoneableValue("resolved_symlink_dynamic_library", Artifact.class));
    builder.setInterfaceLibrary(
        libraryToLink.getNoneableValue("interface_library", Artifact.class));
    builder.setResolvedSymlinkInterfaceLibrary(
        libraryToLink.getNoneableValue("resolve_symlink_interface_library", Artifact.class));
    builder.setStaticLibrary(libraryToLink.getNoneableValue("static_library", Artifact.class));
    builder.setPicStaticLibrary(
        libraryToLink.getNoneableValue("pic_static_library", Artifact.class));
    if (libraryToLink.getFieldNames().contains("object_files")) {
      Object value = libraryToLink.getValue("object_files");
      if (value != null && value != Starlark.NONE) {
        builder.setObjectFiles(
            Sequence.cast(value, Artifact.class, "object_files").getImmutableList());
      }
    }
    if (libraryToLink.getFieldNames().contains("pic_object_files")) {
      Object value = libraryToLink.getValue("pic_object_files");
      if (value != null && value != Starlark.NONE) {
        builder.setPicObjectFiles(
            Sequence.cast(value, Artifact.class, "pic_object_files").getImmutableList());
      }
    }
    builder.setLtoCompilationContext(
        libraryToLink.getNoneableValue("lto_compilation_context", LtoCompilationContext.class));
    builder.setPicLtoCompilationContext(
        libraryToLink.getNoneableValue("pic_lto_compilation_context", LtoCompilationContext.class));
    if (libraryToLink.getFieldNames().contains("shared_non_lto_backends")) {
      builder.setSharedNonLtoBackends(
          ImmutableMap.copyOf(
              Dict.noneableCast(
                  libraryToLink.getValue("shared_non_lto_backends"),
                  Artifact.class,
                  LtoBackendArtifacts.class,
                  "shared_non_lto_backends")));
    }
    if (libraryToLink.getFieldNames().contains("pic_shared_non_lto_backends")) {
      builder.setPicSharedNonLtoBackends(
          ImmutableMap.copyOf(
              Dict.noneableCast(
                  libraryToLink.getValue("pic_shared_non_lto_backends"),
                  Artifact.class,
                  LtoBackendArtifacts.class,
                  "shared_non_lto_backends")));
    }
    if (libraryToLink.getFieldNames().contains("disable_whole_archive")) {
      builder.setDisableWholeArchive(
          libraryToLink.getValue("disable_whole_archive", Boolean.class));
    }
    if (libraryToLink.getFieldNames().contains("alwayslink")) {
      builder.setAlwayslink(libraryToLink.getValue("alwayslink", Boolean.class));
    }
    if (libraryToLink.getFieldNames().contains("must_keep_debug")) {
      builder.setMustKeepDebug(libraryToLink.getValue("must_keep_debug", Boolean.class));
    }
    return builder.build();
  }

  // TODO(b/331164666): rewrite to Stalark using cc_common.create_lto_artifact
  @StarlarkMethod(
      name = "create_lto_artifacts",
      documented = false,
      parameters = {
        @Param(name = "actions"),
        @Param(name = "lto_compilation_context"),
        @Param(name = "feature_configuration"),
        @Param(name = "cc_toolchain"),
        @Param(name = "use_pic"),
        @Param(name = "object_files"),
        @Param(name = "lto_output_root_prefix"),
        @Param(name = "lto_obj_root_prefix"),
        @Param(name = "libraries"),
        @Param(name = "allow_lto_indexing"),
        @Param(name = "include_link_static_in_lto_indexing"),
      })
  public Iterable<LtoBackendArtifacts> createLtoArtifacts(
      WrappedStarlarkActionFactory actions,
      LtoCompilationContext ltoCompilationContext,
      FeatureConfigurationForStarlark featureConfiguration,
      StarlarkInfo toolchain,
      boolean usePicForLtoBackendActions,
      Sequence<?> objectFiles,
      String ltoOutputRootPrefix,
      String ltoObjRootPrefix,
      Depset uniqueLibraries,
      boolean allowLtoIndexing,
      boolean includeLinkStaticInLtoIndexing)
      throws EvalException {
    try {
      return CppLinkActionBuilder.createLtoArtifacts(
          actions.construction,
          ltoCompilationContext,
          featureConfiguration.getFeatureConfiguration(),
          CcToolchainProvider.create(toolchain),
          usePicForLtoBackendActions,
          ImmutableSet.copyOf(Sequence.cast(objectFiles, LegacyLinkerInput.class, "object_files")),
          PathFragment.create(ltoOutputRootPrefix),
          PathFragment.create(ltoObjRootPrefix),
          uniqueLibraries.getSet(LibraryInput.class),
          allowLtoIndexing,
          includeLinkStaticInLtoIndexing);
    } catch (TypeException e) {
      throw new EvalException(e);
    }
  }

  // TODO(b/331164666): rewrite to Stalark using cc_common.create_lto_artifact
  @StarlarkMethod(
      name = "create_shared_non_lto_artifacts",
      documented = false,
      parameters = {
        @Param(name = "actions"),
        @Param(name = "lto_compilation_context"),
        @Param(name = "is_linker"),
        @Param(name = "feature_configuration"),
        @Param(name = "cc_toolchain"),
        @Param(name = "use_pic"),
        @Param(name = "object_files"),
      })
  public ImmutableMap<Artifact, LtoBackendArtifacts> createSharedNonLtoArtifacts(
      WrappedStarlarkActionFactory actions,
      LtoCompilationContext ltoCompilationContext,
      boolean isLinker,
      FeatureConfigurationForStarlark featureConfiguration,
      StarlarkInfo toolchain,
      boolean usePicForLtoBackendActions,
      Sequence<?> objectFiles)
      throws EvalException {
    return CppLinkActionBuilder.createSharedNonLtoArtifacts(
        actions.construction,
        ltoCompilationContext,
        isLinker,
        featureConfiguration.getFeatureConfiguration(),
        CcToolchainProvider.create(toolchain),
        usePicForLtoBackendActions,
        ImmutableSet.copyOf(Sequence.cast(objectFiles, LegacyLinkerInput.class, "object_files")));
  }

  @StarlarkMethod(name = "empty_compilation_outputs", documented = false)
  public CcCompilationOutputs getEmpty() {
    return CcCompilationOutputs.EMPTY;
  }

  private static class WrappedStarlarkActionFactory extends StarlarkActionFactory {
    private final LinkActionConstruction construction;

    public WrappedStarlarkActionFactory(
        StarlarkActionFactory parent, LinkActionConstruction construction) {
      super(parent);
      this.construction = construction;
    }

    @Override
    public FileApi createShareableArtifact(
        String path, Object artifactRoot, StarlarkThread thread) {
      return construction.create(PathFragment.create(path));
    }
  }

  @StarlarkMethod(
      name = "wrap_link_actions",
      documented = false,
      parameters = {
        @Param(name = "actions"),
        @Param(name = "build_configuration", defaultValue = "None"),
        @Param(name = "sharable_artifacts", defaultValue = "False")
      })
  public WrappedStarlarkActionFactory wrapLinkActions(
      StarlarkActionFactory actions, Object config, boolean shareableArtifacts) {
    LinkActionConstruction construction =
        CppLinkActionBuilder.newActionConstruction(
            actions.getRuleContext(),
            config instanceof BuildConfigurationValue
                ? (BuildConfigurationValue) config
                : actions.getRuleContext().getConfiguration(),
            shareableArtifacts);
    return new WrappedStarlarkActionFactory(actions, construction);
  }

  @StarlarkMethod(
      name = "actions2ctx_cheat",
      documented = false,
      parameters = {
        @Param(name = "actions"),
      })
  public StarlarkRuleContext getLabel(StarlarkActionFactory actions) {
    return actions.getRuleContext().getStarlarkRuleContext();
  }

  @StarlarkMethod(
      name = "rule_kind_cheat",
      documented = false,
      parameters = {
        @Param(name = "actions"),
      })
  public String getTargetKind(StarlarkActionFactory actions) {
    return actions
        .getRuleContext()
        .getStarlarkRuleContext()
        .getRuleContext()
        .getRule()
        .getTargetKind();
  }
}
