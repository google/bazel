// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.cpp;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.BuildConfigurationApi;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkActionFactoryApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintValueInfoApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;

/** Utilites related to C++ support. */
@StarlarkBuiltin(
    name = "cc_common",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "Utilities for C++ compilation, linking, and command line generation.")
public interface CcModuleApi<
        StarlarkActionFactoryT extends StarlarkActionFactoryApi,
        FileT extends FileApi,
        FeatureConfigurationT extends FeatureConfigurationApi,
        CompilationContextT extends CcCompilationContextApi<FileT, CppModuleMapT>,
        LtoBackendArtifactsT extends LtoBackendArtifactsApi<FileT>,
        LinkerInputT extends LinkerInputApi<LibraryToLinkT, LtoBackendArtifactsT, FileT>,
        LinkingContextT extends CcLinkingContextApi<?>,
        LibraryToLinkT extends LibraryToLinkApi<FileT, LtoBackendArtifactsT>,
        CcToolchainVariablesT extends CcToolchainVariablesApi,
        ConstraintValueT extends ConstraintValueInfoApi,
        StarlarkRuleContextT extends StarlarkRuleContextApi<ConstraintValueT>,
        CcToolchainConfigInfoT extends CcToolchainConfigInfoApi,
        CompilationOutputsT extends CcCompilationOutputsApi<FileT>,
        DebugInfoT extends CcDebugInfoContextApi,
        CppModuleMapT extends CppModuleMapApi<FileT>,
        LinkingOutputsT extends CcLinkingOutputsApi<FileT, LtoBackendArtifactsT>>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "CcToolchainInfo",
      doc =
          "The key used to retrieve the provider that contains information about the C++ "
              + "toolchain being used",
      structField = true)
  ProviderApi getCcToolchainProvider();

  @Deprecated
  @StarlarkMethod(
      name = "do_not_use_tools_cpp_compiler_present",
      doc =
          "Do not use this field, its only purpose is to help with migration from "
              + "config_setting.values{'compiler') to "
              + "config_settings.flag_values{'@bazel_tools//tools/cpp:compiler'}",
      structField = true)
  default void compilerFlagExists() {}

  @StarlarkMethod(
      name = "compile",
      doc =
          "Should be used for C++ compilation. Returns tuple of "
              + "(<code>CompilationContext</code>, <code>CcCompilationOutputs</code>).",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "actions",
            positional = false,
            named = true,
            doc = "<code>actions</code> object."),
        @Param(
            name = "feature_configuration",
            doc = "<code>feature_configuration</code> to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "cc_toolchain",
            doc = "<code>CcToolchainInfo</code> provider to be used.",
            positional = false,
            named = true),
        @Param(
            name = "srcs",
            doc = "The list of source files to be compiled.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "public_hdrs",
            doc =
                "List of headers needed for compilation of srcs and may be included by dependent "
                    + "rules transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "private_hdrs",
            doc =
                "List of headers needed for compilation of srcs and NOT to be included by"
                    + " dependent rules.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "textual_hdrs",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class)
            },
            documented = false,
            defaultValue = "[]"),
        @Param(
            name = "additional_exported_hdrs",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "unbound"),
        @Param(
            name = "includes",
            doc =
                "Search paths for header files referenced both by angle bracket and quotes. "
                    + "Usually passed with -I. Propagated to dependents transitively.",
            positional = false,
            named = true,
            defaultValue = "[]",
            allowedTypes = {@ParamType(type = Sequence.class), @ParamType(type = Depset.class)}),
        @Param(
            name = "loose_includes",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = Sequence.class), @ParamType(type = NoneType.class)}),
        @Param(
            name = "quote_includes",
            doc =
                "Search paths for header files referenced by quotes, "
                    + "e.g. #include \"foo/bar/header.h\". They can be either relative to the exec "
                    + "root or absolute. Usually passed with -iquote. Propagated to dependents "
                    + "transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "system_includes",
            doc =
                "Search paths for header files referenced by angle brackets, e.g. #include"
                    + " &lt;foo/bar/header.h&gt;. They can be either relative to the exec root or"
                    + " absolute. Usually passed with -isystem. Propagated to dependents "
                    + "transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "framework_includes",
            doc =
                "Search paths for header files from Apple frameworks. They can be either relative "
                    + "to the exec root or absolute. Usually passed with -F. Propagated to "
                    + "dependents transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "defines",
            doc =
                "Set of defines needed to compile this target. Each define is a string. Propagated"
                    + " to dependents transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "local_defines",
            doc =
                "Set of defines needed to compile this target. Each define is a string. Not"
                    + " propagated to dependents transitively.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "include_prefix",
            doc =
                "The prefix to add to the paths of the headers of this rule. When set, the "
                    + "headers in the hdrs attribute of this rule are accessible at is the "
                    + "value of this attribute prepended to their repository-relative path. "
                    + "The prefix in the strip_include_prefix attribute is removed before this "
                    + "prefix is added.",
            positional = false,
            named = true,
            defaultValue = "''"),
        @Param(
            name = "strip_include_prefix",
            doc =
                "The prefix to strip from the paths of the headers of this rule. When set, the"
                    + " headers in the hdrs attribute of this rule are accessible at their path"
                    + " with this prefix cut off. If it's a relative path, it's taken as a"
                    + " package-relative one. If it's an absolute one, it's understood as a"
                    + " repository-relative path. The prefix in the include_prefix attribute is"
                    + " added after this prefix is stripped.",
            positional = false,
            named = true,
            defaultValue = "''"),
        @Param(
            name = "user_compile_flags",
            doc = "Additional list of compilation options.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "conly_flags",
            doc = "Additional list of compilation options for C compiles.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "cxx_flags",
            doc = "Additional list of compilation options for C++ compiles.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "compilation_contexts",
            doc = "Headers from dependencies used for compilation.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "implementation_compilation_contexts",
            documented = false,
            positional = false,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = CcCompilationContextApi.class),
              @ParamType(type = NoneType.class)
            },
            named = true),
        @Param(
            name = "name",
            doc =
                "This is used for naming the output artifacts of actions created by this "
                    + "method. See also the `main_output` arg.",
            positional = false,
            named = true),
        @Param(
            name = "disallow_pic_outputs",
            doc = "Whether PIC outputs should be created.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "disallow_nopic_outputs",
            doc = "Whether NOPIC outputs should be created.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "additional_include_scanning_roots",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "additional_inputs",
            doc = "List of additional files needed for compilation of srcs",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "module_map",
            positional = false,
            documented = false,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = CppModuleMapApi.class),
              @ParamType(type = NoneType.class)
            },
            named = true),
        @Param(
            name = "additional_module_maps",
            positional = false,
            documented = false,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = CppModuleMapApi.class)},
            named = true),
        @Param(
            name = "propagate_module_map_to_compile_action",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "do_not_generate_module_map",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "code_coverage_enabled",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "hdrs_checking_mode",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "unbound"),
        @Param(
            name = "variables_extension",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Dict.class)},
            defaultValue = "unbound"),
        @Param(
            name = "language",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "unbound"),
        @Param(
            name = "purpose",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "unbound"),
        @Param(
            name = "copts_filter",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            defaultValue = "unbound"),
        @Param(
            name = "separate_module_headers",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class)},
            defaultValue = "unbound"),
        @Param(
            name = "module_interfaces",
            doc =
                "The list of module interfaces source files to be compiled. Note: this is an"
                    + " experimental feature, only enabled with --experimental_cpp_modules",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "non_compilation_additional_inputs",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            documented = false,
            defaultValue = "unbound"),
      })
  Tuple compile(
      StarlarkActionFactoryT starlarkActionFactoryApi,
      FeatureConfigurationT starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      Sequence<?> sourcesUnchecked, // <Artifact> expected
      Sequence<?> publicHeadersUnchecked, // <Artifact> expected
      Sequence<?> privateHeadersUnchecked, // <Artifact> expected
      Object textualHeadersStarlarkObject,
      Object additionalExportedHeadersObject,
      Object starlarkIncludes,
      Object starlarkLooseIncludes,
      Sequence<?> quoteIncludes, // <String> expected
      Sequence<?> systemIncludes, // <String> expected
      Sequence<?> frameworkIncludes, // <String> expected
      Sequence<?> defines, // <String> expected
      Sequence<?> localDefines, // <String> expected
      String includePrefix,
      String stripIncludePrefix,
      Sequence<?> userCompileFlags, // <String> expected
      Sequence<?> conlyFlags, // <String> expected
      Sequence<?> cxxFlags, // <String> expected
      Sequence<?> ccCompilationContexts, // <CcCompilationContext> expected
      Object implementationCcCompilationContextsObject,
      String name,
      boolean disallowPicOutputs,
      boolean disallowNopicOutputs,
      Sequence<?> additionalIncludeScanningRoots, // <Artifact> expected
      Sequence<?> additionalInputs, // <Artifact> expected
      Object moduleMapNoneable,
      Object additionalModuleMapsNoneable,
      Object propagateModuleMapToCompileActionObject,
      Object doNotGenerateModuleMapObject,
      Object codeCoverageEnabledObject,
      Object hdrsCheckingModeObject,
      Object variablesExtension,
      Object languageObject,
      Object purposeObject,
      Object coptsFilterObject,
      Object separateModuleHeadersObject,
      Sequence<?> moduleInterfacesUnchecked, // <Artifact> expected
      Object nonCompilationAdditionalInputsObject,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "link",
      doc = "Should be used for C++ transitive linking.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "actions",
            positional = false,
            named = true,
            doc = "<code>actions</code> object."),
        @Param(
            name = "name",
            doc = "This is used for naming the output artifacts of actions created by this method.",
            positional = false,
            named = true),
        @Param(
            name = "feature_configuration",
            doc = "<code>feature_configuration</code> to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "cc_toolchain",
            doc = "<code>CcToolchainInfo</code> provider to be used.",
            positional = false,
            named = true),
        @Param(
            name = "language",
            doc = "Only C++ supported for now. Do not use this parameter.",
            positional = false,
            named = true,
            defaultValue = "'c++'"),
        @Param(
            name = "output_type",
            doc = "Can be either 'executable' or 'dynamic_library'.",
            positional = false,
            named = true,
            defaultValue = "'executable'"),
        @Param(
            name = "link_deps_statically",
            doc = "True to link dependencies statically, False dynamically.",
            positional = false,
            named = true,
            defaultValue = "True"),
        @Param(
            name = "compilation_outputs",
            doc = "Compilation outputs containing object files to link.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = CcCompilationOutputsApi.class),
              @ParamType(type = NoneType.class)
            }),
        @Param(
            name = "linking_contexts",
            doc =
                "Linking contexts from dependencies to be linked into the linking context "
                    + "generated by this rule.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "user_link_flags",
            doc = "Additional list of linker options.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "stamp",
            doc =
                "Whether to include build information in the linked executable, if output_type is "
                    + "'executable'. If 1, build information is always included. If 0 (the "
                    + "default build information is always excluded. If -1, uses the default "
                    + "behavior, which may be overridden by the --[no]stamp flag. This should be "
                    + "unset (or set to 0) when generating the executable output for test rules.",
            positional = false,
            named = true,
            defaultValue = "0"),
        @Param(
            name = "additional_inputs",
            doc = "For additional inputs to the linking action, e.g.: linking scripts.",
            positional = false,
            named = true,
            defaultValue = "[]",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = Depset.class),
            }),
        @Param(
            name = "additional_outputs",
            doc = "For additional outputs to the linking action, e.g.: map files.",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class)},
            defaultValue = "[]"),
        @Param(
            name = "variables_extension",
            positional = false,
            named = true,
            doc =
                "Additional variables to pass to the toolchain configuration when create link "
                    + "command line.",
            allowedTypes = {@ParamType(type = Dict.class)},
            defaultValue = "{}"),
        @Param(
            name = "use_test_only_flags",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "never_link",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "test_only_target",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "native_deps",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "whole_archive",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "additional_linkstamp_defines",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "unbound"),
        @Param(
            name = "always_link",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "only_for_dynamic_libs",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
        @Param(
            name = "link_artifact_name_suffix",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = String.class)},
            defaultValue = "unbound"),
        @Param(
            name = "main_output",
            doc =
                "Name of the main output artifact that will be produced by the linker. "
                    + "Only set this if the default name generation does not match you needs "
                    + "For output_type=executable, this is the final executable filename. "
                    + "For output_type=dynamic_library, this is the shared library filename. "
                    + "If not specified, then one will be computed based on `name` and "
                    + "`output_type`",
            positional = false,
            named = true,
            documented = false,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = NoneType.class)}),
        @Param(
            name = "use_shareable_artifact_factory",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = Boolean.class)}),
        @Param(
            name = "build_config",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = BuildConfigurationApi.class),
              @ParamType(type = NoneType.class)
            }),
        @Param(
            name = "emit_interface_shared_library",
            doc =
                "When 'output_type' is 'dynamic_library' and this parameter is set to True, an"
                    + " interface shared library will be generated during linking. On Windows the"
                    + " interface shared library will always be generated regardless of this"
                    + " parameter since it is a requirement for linking",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
      })
  LinkingOutputsT link(
      StarlarkActionFactoryT starlarkActionFactoryApi,
      String name,
      FeatureConfigurationT starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      String language,
      String outputType,
      boolean linkDepsStatically,
      Object compilationOutputs,
      Sequence<?> linkingContexts, // <LinkingContextT> expected
      Sequence<?> userLinkFlags, // <String> expected
      StarlarkInt stamp,
      Object additionalInputs, // <FileT> expected
      Object linkerOutputs,
      Object variablesExtension,
      Object useTestOnlyFlags,
      Object neverLink,
      Object testOnlyTarget,
      Object nativeDeps,
      Object wholeArchive,
      Object additionalLinkstampDefines,
      Object alwaysLink,
      Object onlyForDynamicLibs,
      Object linkArtifactNameSuffix,
      Object mainOutput,
      Object useShareableArtifactFactory,
      Object buildConfig,
      Object emitInterfaceSharedLibrary,
      StarlarkThread thread)
      throws InterruptedException, EvalException;

  @StarlarkMethod(
      name = "configure_features",
      doc = "Creates a feature_configuration instance. Requires the cpp configuration fragment.",
      parameters = {
        @Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = StarlarkRuleContextApi.class),
              @ParamType(type = NoneType.class),
            },
            doc = "The rule context."),
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we configure features.",
            positional = false,
            named = true),
        @Param(
            name = "language",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The language to configure for: either c++ or objc (default c++)"),
        @Param(
            name = "requested_features",
            doc = "List of features to be enabled.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "unsupported_features",
            doc = "List of features that are unsupported by the current rule.",
            positional = false,
            named = true,
            defaultValue = "[]"),
      },
      useStarlarkThread = true)
  FeatureConfigurationT configureFeatures(
      Object ruleContextOrNone,
      Info toolchain,
      Object languageObject,
      Sequence<?> requestedFeatures, // <String> expected
      Sequence<?> unsupportedFeatures, // <String> expected
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "create_lto_compilation_context",
      doc = "Create LTO compilation context",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "objects",
            doc = "map of full object to index object",
            positional = false,
            named = true,
            defaultValue = "{}",
            allowedTypes = {
              @ParamType(type = Dict.class),
            })
      })
  LtoCompilationContextApi createLtoCompilationContextFromStarlark(
      Object objectsObject, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "create_compilation_outputs",
      doc = "Create compilation outputs object.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "objects",
            doc = "List of object files.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "pic_objects",
            doc = "List of pic object files.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "lto_compilation_context",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "dwo_objects",
            documented = false,
            doc = "Compilation outputs containing dwo files of debuginfo for fission builds.",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Depset.class),
            }),
        @Param(
            name = "pic_dwo_objects",
            doc = "Compilation outputs containing dwo files of debuginfo for pic fission builds.",
            documented = false,
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Depset.class),
            }),
      })
  CompilationOutputsT createCompilationOutputsFromStarlark(
      Object objectsObject,
      Object picObjectsObject,
      Object ltoCopmilationContextObject,
      Object dwoObjectsObject,
      Object picDwoObjectsObject,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "merge_compilation_outputs",
      doc = "Merge compilation outputs.",
      parameters = {
        @Param(name = "compilation_outputs", positional = false, named = true, defaultValue = "[]"),
      },
      useStarlarkThread = true)
  CompilationOutputsT mergeCcCompilationOutputsFromStarlark(
      Sequence<?> compilationOutputs, // <CompilationOutputsT> expected
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_tool_for_action",
      doc = "Returns tool path for given action.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl "
                    + "(https://github.com/bazelbuild/bazel/blob/master/tools/build_defs/cc/"
                    + "action_names.bzl)",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  String getToolForAction(
      FeatureConfigurationT featureConfiguration, String actionName, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_execution_requirements",
      doc = "Returns execution requirements for given action.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl "
                    + "(https://github.com/bazelbuild/bazel/blob/master/tools/build_defs/cc/"
                    + "action_names.bzl)",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  Sequence<String> getExecutionRequirements(
      FeatureConfigurationT featureConfiguration, String actionName, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "is_enabled",
      doc = "Returns True if given feature is enabled in the feature configuration.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "feature_name",
            doc = "Name of the feature.",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  default boolean isEnabled(
      FeatureConfigurationT featureConfiguration, String featureName, StarlarkThread thread)
      throws EvalException {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(
      name = "action_is_enabled",
      doc = "Returns True if given action_config is enabled in the feature configuration.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "action_name",
            doc = "Name of the action_config.",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  boolean actionIsEnabled(
      FeatureConfigurationT featureConfiguration, String actionName, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_memory_inefficient_command_line",
      doc =
          "Returns flattened command line flags for given action, using given variables for "
              + "expansion. Flattens nested sets and ideally should not be used, or at least "
              + "should not outlive analysis. Work on memory efficient function returning Args is "
              + "ongoing.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl "
                    + "(https://github.com/bazelbuild/bazel/blob/master/tools/build_defs/cc/"
                    + "action_names.bzl)",
            named = true,
            positional = false),
        @Param(
            name = "variables",
            doc = "Build variables to be used for template expansions.",
            named = true,
            positional = false),
      },
      useStarlarkThread = true)
  Sequence<String> getCommandLine(
      FeatureConfigurationT featureConfiguration,
      String actionName,
      CcToolchainVariablesT variables,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_environment_variables",
      doc = "Returns environment variables to be set for given action.",
      parameters = {
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "action_name",
            doc =
                "Name of the action. Has to be one of the names in "
                    + "@bazel_tools//tools/build_defs/cc:action_names.bzl "
                    + "(https://github.com/bazelbuild/bazel/blob/master/tools/build_defs/cc/"
                    + "action_names.bzl)",
            named = true,
            positional = false),
        @Param(
            name = "variables",
            doc = "Build variables to be used for template expansion.",
            positional = false,
            named = true),
      },
      useStarlarkThread = true)
  Dict<String, String> getEnvironmentVariable(
      FeatureConfigurationT featureConfiguration,
      String actionName,
      CcToolchainVariablesT variables,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "create_compile_variables",
      doc = "Returns variables used for compilation actions.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we are creating build variables.",
            positional = false,
            named = true),
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "source_file",
            doc =
                "Optional source file for the compilation. Please prefer passing source_file here "
                    + "over appending it to the end of the command line generated from "
                    + "cc_common.get_memory_inefficient_command_line, as then it's in the power of "
                    + "the toolchain author to properly specify and position compiler flags.",
            named = true,
            positional = false,
            defaultValue = "None"),
        @Param(
            name = "output_file",
            doc =
                "Optional output file of the compilation. Please prefer passing output_file here "
                    + "over appending it to the end of the command line generated from "
                    + "cc_common.get_memory_inefficient_command_line, as then it's in the power of "
                    + "the toolchain author to properly specify and position compiler flags.",
            named = true,
            positional = false,
            defaultValue = "None"),
        @Param(
            name = "user_compile_flags",
            doc = "List of additional compilation flags (copts).",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "include_directories",
            doc = "Depset of include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "quote_include_directories",
            doc = "Depset of quote include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "system_include_directories",
            doc = "Depset of system include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "framework_include_directories",
            doc = "Depset of framework include directories.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "preprocessor_defines",
            doc = "Depset of preprocessor defines.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "thinlto_index",
            doc = "LTO index file path.",
            named = true,
            positional = false,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "thinlto_input_bitcode_file",
            doc = "Bitcode file that is input to LTO backend.",
            named = true,
            positional = false,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "thinlto_output_object_file",
            doc = "Object file that is output by LTO backend.",
            named = true,
            positional = false,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "use_pic",
            doc = "When true the compilation will generate position independent code.",
            positional = false,
            named = true,
            defaultValue = "False"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "add_legacy_cxx_options",
            doc = "Unused.",
            named = true,
            positional = false,
            defaultValue = "False"),
        @Param(
            name = "variables_extension",
            doc = "A dictionary of additional variables used by compile actions.",
            named = true,
            positional = false,
            allowedTypes = {@ParamType(type = Dict.class)},
            defaultValue = "unbound"),
        @Param(
            name = "strip_opts",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "input_file",
            documented = false,
            named = true,
            positional = false,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
      })
  CcToolchainVariablesT getCompileBuildVariables(
      Info ccToolchainProvider,
      FeatureConfigurationT featureConfiguration,
      Object sourceFile,
      Object outputFile,
      Object userCompileFlags,
      Object includeDirs,
      Object quoteIncludeDirs,
      Object systemIncludeDirs,
      Object frameworkIncludeDirs,
      Object defines,
      Object thinLtoIndex,
      Object thinLtoInputBitcodeFile,
      Object thinLtoOutputObjectFile,
      boolean usePic,
      boolean addLegacyCxxOptions,
      Object variablesExtension,
      Object stripOpts,
      Object inputFile,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "create_link_variables",
      doc = "Returns link variables used for linking actions.",
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "cc_toolchain for which we are creating build variables.",
            positional = false,
            named = true),
        @Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "library_search_directories",
            doc = "Depset of directories where linker will look for libraries at link time.",
            positional = false,
            named = true,
            defaultValue = "[]",
            allowedTypes = {@ParamType(type = Depset.class)}),
        @Param(
            name = "runtime_library_search_directories",
            doc = "Depset of directories where loader will look for libraries at runtime.",
            positional = false,
            named = true,
            defaultValue = "[]",
            allowedTypes = {@ParamType(type = Depset.class)}),
        @Param(
            name = "user_link_flags",
            doc = "List of additional link flags (linkopts).",
            positional = false,
            named = true,
            defaultValue = "[]",
            allowedTypes = {@ParamType(type = Sequence.class)}),
        @Param(
            name = "output_file",
            doc = "Optional output file path.",
            named = true,
            positional = false,
            defaultValue = "None"),
        @Param(
            name = "param_file",
            doc = "Optional param file path.",
            named = true,
            positional = false,
            defaultValue = "None"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_using_linker",
            doc =
                "True when using linker, False when archiver. Caller is responsible for keeping "
                    + "this in sync with action name used (is_using_linker = True for linking "
                    + "executable or dynamic library, is_using_linker = False for archiving static "
                    + "library).",
            named = true,
            positional = false,
            defaultValue = "True"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_linking_dynamic_library",
            doc =
                "True when creating dynamic library, False when executable or static library. "
                    + "Caller is responsible for keeping this in sync with action name used. "
                    + ""
                    + "This field will be removed once b/65151735 is fixed.",
            named = true,
            positional = false,
            defaultValue = "False"),
        @Param(
            name = "must_keep_debug",
            doc =
                "When set to False, bazel will expose 'strip_debug_symbols' variable, which is "
                    + "usually used to use the linker to strip debug symbols from the output file.",
            named = true,
            positional = false,
            defaultValue = "True"),
        @Param(
            name = "use_test_only_flags",
            doc = "When set to true, 'is_cc_test' variable will be set.",
            named = true,
            positional = false,
            defaultValue = "False"),
        // TODO(b/65151735): Remove once we migrate crosstools to features
        @Param(
            name = "is_static_linking_mode",
            doc = "Unused.",
            named = true,
            positional = false,
            defaultValue = "True"),
      },
      useStarlarkThread = true)
  default CcToolchainVariablesT getLinkBuildVariables(
      Info ccToolchainProvider,
      FeatureConfigurationT featureConfiguration,
      Object librarySearchDirectories,
      Object runtimeLibrarySearchDirectories,
      Object userLinkFlags,
      Object outputFile,
      Object paramFile,
      boolean isUsingLinkerNotArchiver,
      boolean isCreatingSharedLibrary,
      boolean mustKeepDebug,
      boolean useTestOnlyFlags,
      boolean isStaticLinkingMode,
      StarlarkThread thread) {
    throw new UnsupportedOperationException();
  }

  @StarlarkMethod(name = "empty_variables", documented = false, useStarlarkThread = true)
  CcToolchainVariablesT getVariables(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "create_library_to_link",
      doc = "Creates <code>LibraryToLink</code>",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "actions",
            positional = false,
            named = true,
            doc = "<code>actions</code> object."),
        @Param(
            name = "feature_configuration",
            doc = "<code>feature_configuration</code> to be queried.",
            defaultValue = "None",
            positional = false,
            named = true),
        @Param(
            name = "cc_toolchain",
            doc = "<code>CcToolchainInfo</code> provider to be used.",
            defaultValue = "None",
            positional = false,
            named = true),
        @Param(
            name = "static_library",
            doc = "<code>File</code> of static library to be linked.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "pic_static_library",
            doc = "<code>File</code> of pic static library to be linked.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "dynamic_library",
            doc =
                "<code>File</code> of dynamic library to be linked. Always used for runtime "
                    + "and used for linking if <code>interface_library</code> is not passed.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "interface_library",
            doc = "<code>File</code> of interface library to be linked.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "pic_objects",
            doc = "Experimental, do not use",
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)}),
        @Param(
            name = "objects",
            doc = "Experimental, do not use",
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)}),
        @Param(
            name = "lto_compilation_context",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "None"),
        @Param(
            name = "alwayslink",
            doc = "Whether to link the static library/objects in the --whole_archive block.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "dynamic_library_symlink_path",
            doc =
                "Override the default path of the dynamic library link in the solib directory. "
                    + "Empty string to use the default.",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = String.class),
            },
            defaultValue = "''"),
        @Param(
            name = "interface_library_symlink_path",
            doc =
                "Override the default path of the interface library link in the solib directory. "
                    + "Empty string to use the default.",
            positional = false,
            named = true,
            defaultValue = "''"),
        @Param(
            name = "must_keep_debug",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
      })
  LibraryToLinkT createLibraryLinkerInput(
      Object actions,
      Object featureConfiguration,
      Object ccToolchainProvider,
      Object staticLibrary,
      Object picStaticLibrary,
      Object dynamicLibrary,
      Object interfaceLibrary,
      Object picObjectFiles, // Sequence<Artifact> expected
      Object nopicObjectFiles, // Sequence<Artifact> expected
      Object ltoCopmilationContextObject,
      boolean alwayslink,
      String dynamicLibraryPath,
      String interfaceLibraryPath,
      Object mustKeepDebug,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "create_linker_input",
      doc = "Creates a <code>LinkerInput</code>.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "owner",
            doc = "The label of the target that produced all files used in this input.",
            positional = false,
            named = true),
        @Param(
            name = "libraries",
            doc = "List of <code>LibraryToLink</code>.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Depset.class)}),
        @Param(
            name = "user_link_flags",
            doc =
                "User link flags passed as strings. Accepts either [String], [[String]] or"
                    + " depset(String). The latter is discouraged as it's only kept for"
                    + " compatibility purposes, the depset is flattened. If you want to propagate"
                    + " user_link_flags via unflattened depsets() wrap them in a LinkerInput so"
                    + " that they are not flattened till the end.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = NoneType.class),
              @ParamType(type = Depset.class, generic1 = String.class),
              @ParamType(type = Sequence.class, generic1 = String.class)
            }),
        @Param(
            name = "additional_inputs",
            doc = "For additional inputs to the linking action, e.g.: linking scripts.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Depset.class)}),
        @Param(
            name = "linkstamps",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Depset.class)}),
      })
  LinkerInputT createLinkerInput(
      Label owner,
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      Object nonCodeInputs,
      Object linkstamps,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "check_experimental_cc_shared_library",
      doc = "DO NOT USE. This is to guard use of cc_shared_library.",
      useStarlarkThread = true,
      documented = false)
  boolean checkExperimentalCcSharedLibrary(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "check_experimental_cc_static_library",
      doc = "DO NOT USE. This is to guard use of cc_static_library.",
      useStarlarkThread = true,
      documented = false)
  boolean checkExperimentalCcStaticLibrary(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "incompatible_disable_objc_library_transition",
      useStarlarkThread = true,
      documented = false)
  boolean getIncompatibleDisableObjcLibraryTransition(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "create_linking_context",
      doc = "Creates a <code>LinkingContext</code>.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "linker_inputs",
            doc = "Depset of <code>LinkerInput</code>.",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Depset.class)}),
        @Param(
            name = "libraries_to_link",
            doc = "List of <code>LibraryToLink</code>.",
            positional = false,
            named = true,
            disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
            defaultValue = "None",
            valueWhenDisabled = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Sequence.class)}),
        @Param(
            name = "user_link_flags",
            doc = "List of user link flags passed as strings.",
            positional = false,
            named = true,
            disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
            defaultValue = "None",
            valueWhenDisabled = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Sequence.class)}),
        @Param(
            name = "additional_inputs",
            doc = "For additional inputs to the linking action, e.g.: linking scripts.",
            positional = false,
            named = true,
            disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
            defaultValue = "None",
            valueWhenDisabled = "None",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Sequence.class)}),
        @Param(
            name = "extra_link_time_library",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = ExtraLinkTimeLibraryApi.class),
              @ParamType(type = NoneType.class)
            }),
        @Param(
            name = "owner",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound",
            allowedTypes = {@ParamType(type = NoneType.class), @ParamType(type = Label.class)})
      })
  LinkingContextT createCcLinkingInfo(
      Object linkerInputs,
      Object librariesToLinkObject,
      Object userLinkFlagsObject,
      Object nonCodeInputs, // <FileT> expected
      Object extraLinkTimeLibraryObject,
      Object ownerObject,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "create_compilation_context",
      doc = "Creates a <code>CompilationContext</code>.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "headers",
            doc = "Set of headers needed to compile this target",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "system_includes",
            doc =
                "Set of search paths for header files referenced by angle brackets, i.e. "
                    + "#include &lt;foo/bar/header.h&gt;. They can be either relative to the exec "
                    + "root or absolute. Usually passed with -isystem",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "includes",
            doc =
                "Set of search paths for header files referenced both by angle bracket and quotes."
                    + "Usually passed with -I",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "quote_includes",
            doc =
                "Set of search paths for header files referenced by quotes, i.e. "
                    + "#include \"foo/bar/header.h\". They can be either relative to the exec "
                    + "root or absolute. Usually passed with -iquote",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "framework_includes",
            doc = "Set of framework search paths for header files (Apple platform only)",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "defines",
            doc =
                "Set of defines needed to compile this target. Each define is a string. Propagated"
                    + " transitively to dependents.",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "local_defines",
            doc =
                "Set of defines needed to compile this target. Each define is a string. Not"
                    + " propagated transitively to dependents.",
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "direct_textual_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "direct_public_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "direct_private_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "purpose",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "module_map",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "actions",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "label",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "external_includes",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "virtual_to_original_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "dependent_cc_compilation_contexts",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "exported_dependent_cc_compilation_contexts",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "non_code_inputs",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "loose_hdrs_dirs",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "headers_checking_mode",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "propagate_module_map_to_compile_action",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "pic_header_module",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "header_module",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "separate_module_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "separate_module",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "separate_pic_module",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
        @Param(
            name = "add_public_headers_to_modular_headers",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "unbound"),
      })
  CompilationContextT createCcCompilationContext(
      Object headers,
      Object systemIncludes,
      Object includes,
      Object quoteIncludes,
      Object frameworkIncludes,
      Object defines,
      Object localDefines,
      Sequence<?> directTextualHdrs,
      Sequence<?> directPublicHdrs,
      Sequence<?> directPrivateHdrs,
      Object unused3,
      Object moduleMap,
      Object unused1,
      Object unused2,
      Object externalIncludes,
      Object virtualToOriginalHeaders,
      Sequence<?> dependentCcCompilationContexts,
      Sequence<?> exportedDependentCcCompilationContexts,
      Sequence<?> nonCodeInputs,
      Sequence<?> looseHdrsDirs,
      String headersCheckingMode,
      Boolean propagateModuleMapToCompileAction,
      Object picHeaderModule,
      Object headerModule,
      Sequence<?> separateModuleHeaders,
      Object separateModule,
      Object separatePicModule,
      Object addPublicHeadersToModularHeaders,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "create_module_map",
      documented = false,
      doc = "Creates a <code>CcModuleMap</code>.",
      useStarlarkThread = true,
      parameters = {
        @Param(name = "file", positional = false, named = true),
        @Param(
            name = "umbrella_header",
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = FileApi.class), @ParamType(type = NoneType.class)}),
        @Param(name = "name", positional = false, named = true),
      })
  CppModuleMapT createCppModuleMap(
      FileT file, Object umbrellaHeader, String name, StarlarkThread thread) throws EvalException;

  // TODO(b/65151735): Remove when cc_flags is entirely set from features.
  // This should only be called from the cc_flags_supplier rule.
  @StarlarkMethod(
      name = "legacy_cc_flags_make_variable_do_not_use",
      documented = false,
      parameters = {
        @Param(
            name = "cc_toolchain",
            doc = "C++ toolchain provider to be used.",
            positional = false,
            named = true)
      },
      useStarlarkThread = true)
  String legacyCcFlagsMakeVariable(Info ccToolchain, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "create_cc_toolchain_config_info",
      doc = "Creates a <code>CcToolchainConfigInfo</code> provider",
      useStarlarkThread = true,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, doc = "The rule context."),
        @Param(
            name = "features",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc =
                "Contains all flag specifications for one"
                    + " feature.<p>Arguments:</p><p><code>name</code>: The feature's name. It is"
                    + " possible to introduce a feature without a change to Bazel by adding a"
                    + " 'feature' section to the toolchain and adding the corresponding string as"
                    + " feature in the <code>BUILD</code> file.</p><p><code>enabled</code>: If"
                    + " 'True', this feature is enabled unless a rule type explicitly marks it as"
                    + " unsupported.</p><p><code>flag_sets</code>: A FlagSet list. If the given"
                    + " feature is enabled, the flag sets will be applied for the actions are"
                    + " specified for. </p><p><code>env_sets</code>: an EnvSet list. If the given"
                    + " feature is enabled, the env sets will be applied for the actions they are"
                    + " specified for. </p><p><code>requires</code>: A list of feature sets"
                    + " defining when this feature is supported by the  toolchain. The feature is"
                    + " supported if any of the feature sets fully apply, that is, when all"
                    + " features of a feature set are enabled. If <code>requires</code> is omitted,"
                    + " the feature is supported independently of which other features are enabled."
                    + " Use this for example to filter flags depending on the build mode enabled"
                    + " (opt / fastbuild / dbg). </p><p><code>implies</code>: A string list of"
                    + " features or action configs that are automatically enabled when this feature"
                    + " is enabled. If any of the implied features or action configs cannot be"
                    + " enabled, this feature will (silently) not be enabled either."
                    + " </p><p><code>provides</code>: A list of names this feature conflicts with."
                    + " </p>A feature cannot be enabled if:</br>- <code>provides</code> contains"
                    + " the name of a different feature or action config that we want to"
                    + " enable.</br>- <code>provides</code> contains the same value as a 'provides'"
                    + " in a different feature or action config that we want to enable. Use this in"
                    + " order to ensure that incompatible features cannot be accidentally activated"
                    + " at the same time, leading to hard to diagnose compiler errors."),
        @Param(
            name = "action_configs",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc =
                "An action config corresponds to a Bazel action, and allows selection of a tool"
                    + " based on activated features. Action config activation occurs by the same"
                    + " semantics as features: a feature can 'require' or 'imply' an action config"
                    + " in the same way that it would another"
                    + " feature.<p>Arguments:</p><p><code>action_name</code>: The name of the Bazel"
                    + " action that this config applies to, e.g. 'c-compile' or"
                    + " 'c-module-compile'.</p><p><code>enabled</code>: If 'True', this action is"
                    + " enabled unless a rule type explicitly marks it as"
                    + " unsupported.</p><p><code>tools</code>: The tool applied to the action will"
                    + " be the first tool with a feature set that matches the feature"
                    + " configuration.  An error will be thrown if no tool matches a provided"
                    + " feature configuration - for that reason, it's a good idea to provide a"
                    + " default tool with an empty feature set.</p><p><code>flag_sets</code>: If"
                    + " the given action config is enabled, the flag sets will be applied to the"
                    + " corresponding action.</p><p><code>implies</code>: A list of features or"
                    + " action configs that are automatically enabled when this action config is"
                    + " enabled. If any of the implied features or action configs cannot be"
                    + " enabled, this action config will (silently) not be enabled either.</p>"),
        @Param(
            name = "artifact_name_patterns",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc =
                "The name for an artifact of a given category of input or output artifacts to an"
                    + " action.<p>Arguments:</p><p><code>category_name</code>: The category of"
                    + " artifacts that this selection applies to. This field is compared against a"
                    + " list of categories defined in Bazel. Example categories include"
                    + " \"linked_output\" or the artifact for this selection. Together with the"
                    + " extension it is used to create an artifact name based on the target"
                    + " name.</p><p><code>extension</code>: The extension for creating the artifact"
                    + " for this selection. Together with the prefix it is used to create an"
                    + " artifact name based on the target name.</p>"),
        @Param(
            name = "cxx_builtin_include_directories",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc =
                "<p>Built-in include directories for C++ compilation. These should be the exact "
                    + "paths used by the compiler, and are generally relative to the exec root.</p>"
                    + "<p>The paths used by the compiler can be determined by 'gcc -E -xc++ - -v'."
                    + "</p><p>We currently use the C++ paths also for C compilation, which is safe "
                    + "as long as there are no name clashes between C++ and C header files.</p>"
                    + "<p>Relative paths are resolved relative to the configuration file directory."
                    + "</p><p>If the compiler has --sysroot support, then these paths should use "
                    + "%sysroot% rather than the include path, and specify the sysroot attribute "
                    + "in order to give blaze the information necessary to make the correct "
                    + "replacements.</p>"),
        @Param(
            name = "toolchain_identifier",
            positional = false,
            named = true,
            doc =
                "<p>The unique identifier of the toolchain within the crosstool release. It must "
                    + "be possible to use this as a directory name in a path.</p>"
                    + "<p>It has to match the following regex: [a-zA-Z_][\\.\\- \\w]*</p>"),
        @Param(
            name = "host_system_name",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc = "Ignored."),
        @Param(
            name = "target_system_name",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "Deprecated. The GNU System Name. The string is exposed to"
                    + " CcToolchainInfo.target_gnu_system_name."),
        @Param(
            name = "target_cpu",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "Deprecated: Use cpu based constraints instead. If the string is \"k8\", "
                    + "`target_cpu` will be omitted from the filename of raw FDO profile data."),
        @Param(
            name = "target_libc",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "Deprecated: Use OS based constraints instead. The libc version string (e.g."
                    + " \"glibc-2.2.2\"). If the string is \"macosx\", platform is assumed to be"
                    + " MacOS. Otherwise, Linux. The string is exposed to CcToolchainInfo.libc."),
        @Param(
            name = "compiler",
            positional = false,
            named = true,
            doc =
                "The compiler string (e.g. \"gcc\"). The current toolchain's compiler"
                    + " is exposed to `@bazel_tools//tools/cpp:compiler (compiler_flag)` as a flag"
                    + " value. Targets that require compiler-specific flags can use the"
                    + " config_settings in"
                    + " https://github.com/bazelbuild/rules_cc/blob/main/cc/compiler/BUILD in"
                    + " select() statements or create custom config_setting if the existing"
                    + " settings don't suffice."),
        @Param(
            name = "abi_version",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "The abi in use, which is a gcc version. E.g.: \"gcc-3.4\". The string is set to"
                    + " C++ toolchain variable ABI."),
        @Param(
            name = "abi_libc_version",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "The glibc version used by the abi we're using. The string is set to C++ toolchain"
                    + " variable ABI_LIBC_VERSION."),
        @Param(
            name = "tool_paths",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc =
                "Tool locations.<p>Arguments:</p><p><code>name</code>: Name of the"
                    + " tool.</p><p><code>path</code>: Location of the tool; Can be absolute path"
                    + " (in case of non hermetic toolchain), or path relative to the cc_toolchain's"
                    + " package.</p>"),
        @Param(
            name = "make_variables",
            positional = false,
            named = true,
            defaultValue = "[]",
            doc = "A make variable that is made accessible to rules."),
        @Param(
            name = "builtin_sysroot",
            positional = false,
            defaultValue = "None",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = NoneType.class)},
            named = true,
            doc =
                "The built-in sysroot. If this attribute is not present, Bazel does not "
                    + "allow using a different sysroot, i.e. through the --grte_top option."),
      })
  CcToolchainConfigInfoT ccToolchainConfigInfoFromStarlark(
      StarlarkRuleContextT starlarkRuleContext,
      Sequence<?> features, // <StructApi> expected
      Sequence<?> actionConfigs, // <StructApi> expected
      Sequence<?> artifactNamePatterns, // <StructApi> expected
      Sequence<?> cxxBuiltInIncludeDirectories, // <String> expected
      String toolchainIdentifier,
      Object hostSystemName,
      Object targetSystemName,
      Object targetCpu,
      Object targetLibc,
      String compiler,
      Object abiVersion,
      Object abiLibcVersion,
      Sequence<?> toolPaths, // <StructApi> expected
      Sequence<?> makeVariables, // <StructApi> expected
      Object builtinSysroot,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "create_linking_context_from_compilation_outputs",
      doc =
          "Should be used for creating library rules that can propagate information downstream in"
              + " order to be linked later by a top level rule that does transitive linking to"
              + " create an executable or a dynamic library. Returns tuple of "
              + "(<code>CcLinkingContext</code>, <code>CcLinkingOutputs</code>).",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "actions",
            positional = false,
            named = true,
            doc = "<code>actions</code> object."),
        @Param(
            name = "name",
            doc =
                "This is used for naming the output artifacts of actions created by this "
                    + "method.",
            positional = false,
            named = true),
        @Param(
            name = "feature_configuration",
            doc = "<code>feature_configuration</code> to be queried.",
            positional = false,
            named = true),
        @Param(
            name = "cc_toolchain",
            doc = "<code>CcToolchainInfo</code> provider to be used.",
            positional = false,
            named = true),
        @Param(
            name = "language",
            doc = "Only C++ supported for now. Do not use this parameter.",
            positional = false,
            named = true,
            defaultValue = "'c++'"),
        @Param(
            name = "disallow_static_libraries",
            doc = "Whether static libraries should be created.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "disallow_dynamic_library",
            doc = "Whether a dynamic library should be created.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "compilation_outputs",
            doc = "Compilation outputs containing object files to link.",
            positional = false,
            named = true),
        @Param(
            name = "linking_contexts",
            doc =
                "Libraries from dependencies. These libraries will be linked into the output "
                    + "artifact of the link() call, be it a binary or a library.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "user_link_flags",
            doc = "Additional list of linking options.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "alwayslink",
            doc = "Whether this library should always be linked.",
            positional = false,
            named = true,
            defaultValue = "False"),
        @Param(
            name = "additional_inputs",
            doc = "For additional inputs to the linking action, e.g.: linking scripts.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        @Param(
            name = "variables_extension",
            doc =
                "Additional variables to pass to the toolchain configuration when creating link"
                    + " command line.",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Dict.class)},
            defaultValue = "unbound"),
        @Param(
            name = "stamp",
            positional = false,
            named = true,
            documented = false,
            defaultValue = "unbound"),
        @Param(
            name = "linked_dll_name_suffix",
            positional = false,
            named = true,
            documented = false,
            defaultValue = "unbound"),
        @Param(
            name = "test_only_target",
            positional = false,
            named = true,
            documented = false,
            allowedTypes = {@ParamType(type = Boolean.class)},
            defaultValue = "unbound"),
      })
  Tuple createLinkingContextFromCompilationOutputs(
      StarlarkActionFactoryT starlarkActionFactoryApi,
      String name,
      FeatureConfigurationT starlarkFeatureConfiguration,
      Info starlarkCcToolchainProvider,
      String language,
      boolean disallowStaticLibraries,
      boolean disallowDynamicLibraries,
      CompilationOutputsT compilationOutputs,
      Sequence<?> linkingContexts, // <LinkingContextT> expected
      Sequence<?> userLinkFlags, // <String> expected
      boolean alwayslink,
      Sequence<?> additionalInputs, // <FileT> expected
      Object variablesExtension,
      Object stamp,
      Object linkedDllNameSuffix,
      Object testOnlyTarget,
      StarlarkThread thread)
      throws InterruptedException, EvalException;

  @StarlarkMethod(
      name = "create_debug_context",
      doc = "Create debug context",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "compilation_outputs", positional = true, named = false, defaultValue = "[]"),
      })
  DebugInfoT createCcDebugInfoFromStarlark(
      CompilationOutputsT compilationOutputs, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "merge_debug_context",
      doc = "Merge debug contexts",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "debug_contexts", defaultValue = "[]"),
      })
  DebugInfoT mergeCcDebugInfoFromStarlark(
      Sequence<?> debugInfos, // <DebugInfoT> expected
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "create_lto_backend_artifacts",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "ctx", positional = false, named = true, documented = false),
        @Param(
            name = "lto_output_root_prefix",
            positional = false,
            named = true,
            documented = false),
        @Param(name = "lto_obj_root_prefix", positional = false, named = true, documented = false),
        @Param(name = "bitcode_file", positional = false, named = true, documented = false),
        @Param(
            name = "feature_configuration",
            positional = false,
            named = true,
            documented = false),
        @Param(name = "cc_toolchain", positional = false, named = true, documented = false),
        @Param(name = "fdo_context", positional = false, named = true, documented = false),
        @Param(name = "use_pic", positional = false, named = true, documented = false),
        @Param(
            name = "should_create_per_object_debug_info",
            positional = false,
            named = true,
            documented = false),
        @Param(name = "argv", positional = false, named = true, documented = false),
      })
  LtoBackendArtifactsT createLtoBackendArtifacts(
      StarlarkRuleContextT starlarkRuleContext,
      String ltoOutputRootPrefixString,
      String ltoObjRootPrefixString,
      FileT bitcodeFile,
      FeatureConfigurationT featureConfigurationForStarlark,
      Info ccToolchain,
      StructImpl fdoContextStruct,
      boolean usePic,
      boolean shouldCreatePerObjectDebugInfo,
      Sequence<?> argv,
      StarlarkThread thread)
      throws EvalException, InterruptedException, RuleErrorException;

  @StarlarkMethod(
      name = "merge_compilation_contexts",
      doc = "Merges multiple <code>CompilationContexts</code>s into one.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "compilation_contexts",
            doc =
                "List of <code>CompilationContexts</code>s to be merged. The headers of each "
                    + "context will be exported by the direct fields in the returned provider.",
            positional = false,
            named = true,
            defaultValue = "[]"),
        // There is an inconsistency in naming compilation_context parameter of this method
        // should be named - exported_compilation_contexts and non_exported_compilation_contexts
        // should be named compilation_contexts. Because compilation_contexts is already
        // mistakenly named(cl/373784770) I've decided to go with the non_exported
        // prefix to keep things consistent.
        @Param(
            name = "non_exported_compilation_contexts",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "[]"),
      })
  CompilationContextT mergeCompilationContexts(
      Sequence<?> compilationContexts, // <CcCompilationContextApi> expected
      Sequence<?> nonExportedCompilationContexts, // <CcCompilationContextApi> expected
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_tool_requirement_for_action",
      documented = false,
      useStarlarkThread = true,
      parameters = {
        @Param(name = "feature_configuration", positional = false, named = true),
        @Param(name = "action_name", named = true, positional = false),
      })
  Sequence<String> getToolRequirementForAction(
      FeatureConfigurationT featureConfiguration, String actionName, StarlarkThread thread)
      throws EvalException;
}
