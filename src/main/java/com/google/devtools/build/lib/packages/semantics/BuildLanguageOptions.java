// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.packages.semantics;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.common.options.BoolOrEnumConverter;
import com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import java.util.List;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * Options that affect the semantics of Bazel's build language.
 *
 * <p>These are injected into Skyframe (as an instance of {@link StarlarkSemantics}) when a new
 * build invocation occurs. Changing these options between builds will therefore trigger a
 * reevaluation of everything that depends on the Starlark interpreter &mdash; in particular,
 * evaluation of all BUILD and .bzl files.
 *
 * <p><em>To add a new option, update the following:</em>
 *
 * <ul>
 *   <li>Add a new {@code @Option}-annotated field to this class. The field name and default value
 *       should be the same as in {@link StarlarkSemantics}, and the option name in the annotation
 *       should be that name written in snake_case. Add a line to set the new field in {@link
 *       #toStarlarkSemantics}.
 *   <li>Define a new {@code StarlarkSemantics.Key} or {@code StarlarkSemantics} boolean flag
 *       identifier.
 *   <li>Add a line to set the new field in both {@link ConsistencyTest#buildRandomOptions} and
 *       {@link ConsistencyTest#buildRandomSemantics}.
 *   <li>Update manual documentation in site/docs/skylark/backward-compatibility.md. Also remember
 *       to update this when flipping a flag's default value.
 *   <li>Boolean semantic flags can toggle StarlarkMethod-annotated Java methods (or their
 *       parameters) on or off, making them selectively invisible to Starlark. To do this, add a new
 *       entry to {@link BuildLanguageOptions}, then specify the identifier in {@link
 *       net.starlark.java.annot.StarlarkMethod#enableOnlyWithFlag} or {@link
 *       net.starlark.java.annot.StarlarkMethod#disableWithFlag}.
 * </ul>
 */
public final class BuildLanguageOptions extends OptionsBase {
  @Option(
      name = "experimental_java_library_export",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If enabled, experimental_java_library_export_do_not_use module is available.")
  public boolean experimentalJavaLibraryExport;

  @Option(
      name = "incompatible_stop_exporting_language_modules",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled, certain language-specific modules (such as `cc_common`) are unavailable in"
              + " user .bzl files and may only be called from their respective rules repositories.")
  public boolean incompatibleStopExportingLanguageModules;

  // TODO(#11437): Delete the special empty string value so that it's on unconditionally.
  @Option(
      name = "experimental_builtins_bzl_path",
      defaultValue = "%bundled%",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "This flag tells Bazel how to find the \"@_builtins\" .bzl files that govern how "
              + "predeclared symbols for BUILD and .bzl files are defined. This flag is only "
              + "intended for Bazel developers, to help when writing @_builtins .bzl code. "
              + "Ordinarily this value is set to \"%bundled%\", which means to use the "
              + "builtins_bzl/ directory packaged in the Bazel binary. However, it can be set to "
              + "the path (relative to the root of the current workspace) of an alternate "
              + "builtins_bzl/ directory, such as one in a Bazel source tree workspace. A literal "
              + "value of \"%workspace%\" is equivalent to the relative package path of "
              + "builtins_bzl/ within a Bazel source tree; this should only be used when running "
              + "Bazel within its own source tree. Finally, a value of the empty string disables "
              + "the builtins injection mechanism entirely.")
  public String experimentalBuiltinsBzlPath;

  @Option(
      name = "incompatible_autoload_externally",
      converter = CommaSeparatedOptionSetConverter.class,
      defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_AUTOLOAD_EXTERNALLY,
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "A comma-separated list of rules (or other symbols) that were previously part of Bazel"
              + " and which are now to be retrieved from their respective external repositories."
              + " This flag is intended to be used to facilitate migration of rules out of Bazel."
              + " See also https://github.com/bazelbuild/bazel/issues/23043.\n"
              + "A symbol that is autoloaded within a file behaves as if its built-into-Bazel"
              + " definition were replaced by its canonical new definition in an external"
              + " repository. For a BUILD file, this essentially means implicitly adding a load()"
              + " statement. For a .bzl file, it's either a load() statement or a change to a field"
              + " of the `native` object, depending on whether the autoloaded symbol is a rule.\n"
              + "Bazel maintains a hardcoded list of all symbols that may be autoloaded; only those"
              + " symbols may appear in this flag. For each symbol, Bazel knows the new definition"
              + " location in an external repository, as well as a set of special-cased"
              + " repositories that must not autoload it to avoid creating cycles.\n"
              + "A list item of \"+foo\" in this flag causes symbol foo to be autoloaded, except in"
              + " foo's exempt repositories, within which the Bazel-defined version of foo is still"
              + " available.\n"
              + "A list item of \"foo\" triggers autoloading as above, but the Bazel-defined"
              + " version of foo is not made available to the excluded repositories. This ensures"
              + " that foo's external repository does not depend on the old Bazel implementation of"
              + " foo\n"
              + "A list item of \"-foo\" does not trigger any autoloading, but makes the"
              + " Bazel-defined version of foo inaccessible throughout the workspace. This is used"
              + " to validate that the workspace is ready for foo's definition to be deleted from"
              + " Bazel.\n"
              + "If a symbol is not named in this flag then it continues to work as normal -- no"
              + " autoloading is done, nor is the Bazel-defined version suppressed. For"
              + " configuration see"
              + " https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/packages/AutoloadSymbols.java"
              + " As a shortcut also whole repository may be used, for example +@rules_python will"
              + " autoload all Python rules.")
  public List<String> incompatibleAutoloadExternally;

  @Option(
      name = "repositories_without_autoloads",
      converter = CommaSeparatedOptionSetConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "A list of additional repositories (beyond the hardcoded ones Bazel knows about) where "
              + "autoloads are not to be added. This should typically contain repositories that are"
              + " transitively depended on by a repository that may be loaded automatically "
              + "(and which can therefore potentially create a cycle).")
  public List<String> repositoriesWithoutAutoloads;

  @Option(
      name = "experimental_builtins_dummy",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "Enables an internal dummy symbol used to test builtins injection.")
  public boolean experimentalBuiltinsDummy;

  @Option(
      name = "experimental_builtins_injection_override",
      converter = CommaSeparatedNonEmptyOptionListConverter.class,
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "A comma-separated list of symbol names prefixed by a + or - character, indicating which"
              + " symbols from `@_builtins//:exports.bzl` to inject, overriding their default"
              + " injection status. Precisely, this works as follows. Each dict key of"
              + " `exported_toplevels` or `exported_rules` has the form `foo`, `+foo`, or `-foo`."
              + " The first two forms mean it gets injected by default, while the last form means"
              + " it does not get injected by default. In the first case (unprefixed), the default"
              + " is absolute and cannot be overridden. Otherwise, we then consult this options"
              + " list, and if we see foo occur here, we take the prefix of its last occurrence and"
              + " use that to decide whether or not to inject. It is a no-op to specify an unknown"
              + " symbol, or to attempt to not inject a symbol that occurs unprefixed in a dict"
              + " key.")
  public List<String> experimentalBuiltinsInjectionOverride;

  @Option(
      name = "experimental_bzl_visibility",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If enabled, adds a `visibility()` function that .bzl files may call during top-level"
              + " evaluation to set their visibility for the purpose of load() statements.")
  public boolean experimentalBzlVisibility;

  @Option(
      name = "experimental_single_package_toolchain_binding",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled, the register_toolchain function may not include target patterns which may "
              + "refer to more than one package.")
  public boolean experimentalSinglePackageToolchainBinding;

  @Option(
      name = "check_bzl_visibility",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help = "If disabled, .bzl load visibility errors are demoted to warnings.")
  public boolean checkBzlVisibility;

  @Option(
      name = "experimental_cc_skylark_api_enabled_packages",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "Passes list of packages that can use the C++ Starlark API. Don't enable this flag yet, "
              + "we will be making breaking changes.")
  public List<String> experimentalCcStarlarkApiEnabledPackages;

  @Option(
      name = "experimental_enable_android_migration_apis",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      help = "If set to true, enables the APIs required to support the Android Starlark migration.")
  public boolean experimentalEnableAndroidMigrationApis;

  @Option(
      name = "experimental_enable_first_class_macros",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      help = "If set to true, enables the `macro()` construct for defining symbolic macros.")
  public boolean experimentalEnableFirstClassMacros;

  @Option(
      name = "experimental_enable_scl_dialect",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      // TODO(brandjon): point to more extensive user documentation somewhere
      help = "If set to true, .scl files may be used in load() statements.")
  public boolean experimentalEnableSclDialect;

  @Option(
      name = "experimental_isolated_extension_usages",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.LOADING_AND_ANALYSIS,
      help =
          "If true, enables the <code>isolate</code> parameter in the <a"
              + " href=\"https://bazel.build/rules/lib/globals/module#use_extension\"><code>use_extension</code></a>"
              + " function.")
  public boolean experimentalIsolatedExtensionUsages;

  @Option(
      name = "incompatible_no_implicit_watch_label",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      metadataTags = OptionMetadataTag.INCOMPATIBLE_CHANGE,
      effectTags = OptionEffectTag.LOADING_AND_ANALYSIS,
      help =
          "If true, then methods on <code>repository_ctx</code> that are passed a Label will no"
              + " longer automatically watch the file under that label for changes even if"
              + " <code>watch = \"no\"</code>, and <code>repository_ctx.path</code> no longer"
              + " causes the returned path to be watched. Use <code>repository_ctx.watch</code>"
              + " instead.")
  public boolean incompatibleNoImplicitWatchLabel;

  @Option(
      name = "incompatible_stop_exporting_build_file_path",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, deprecated ctx.build_file_path will not be available. ctx.label.package"
              + " + '/BUILD' can be used instead.")
  public boolean incompatibleStopExportingBuildFilePath;

  @Option(
      name = "experimental_google_legacy_api",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, exposes a number of experimental pieces of Starlark build API "
              + "pertaining to Google legacy code.")
  public boolean experimentalGoogleLegacyApi;

  @Option(
      name = "experimental_platforms_api",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, enables a number of platform-related Starlark APIs useful for "
              + "debugging.")
  public boolean experimentalPlatformsApi;

  @Option(
      name = "experimental_cc_shared_library",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, rule attributes and Starlark API methods needed for the rule "
              + "cc_shared_library will be available")
  public boolean experimentalCcSharedLibrary;

  @Option(
      name = "experimental_cc_static_library",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, rule attributes and Starlark API methods needed for the rule "
              + "cc_static_library will be available")
  public boolean experimentalCcStaticLibrary;

  @Option(
      name = "incompatible_require_linker_input_cc_api",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, rule create_linking_context will require linker_inputs instead of "
              + "libraries_to_link. The old getters of linking_context will also be disabled and "
              + "just linker_inputs will be available.")
  public boolean incompatibleRequireLinkerInputCcApi;

  @Option(
      name = "experimental_repo_remote_exec",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help = "If set to true, repository_rule gains some remote execution capabilities.")
  public boolean experimentalRepoRemoteExec;

  @Option(
      name = "experimental_disable_external_package",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.LOSES_INCREMENTAL_STATE},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, the auto-generated //external package will not be available anymore. "
              + "Bazel will still be unable to parse the file 'external/BUILD', but globs reaching "
              + "into external/ from the unnamed package will work.")
  public boolean experimentalDisableExternalPackage;

  @Option(
      name = "experimental_sibling_repository_layout",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {
        OptionEffectTag.ACTION_COMMAND_LINES,
        OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.LOSES_INCREMENTAL_STATE
      },
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, non-main repositories are planted as symlinks to the main repository in"
              + " the execution root. That is, all repositories are direct children of the"
              + " $output_base/execution_root directory. This has the side effect of freeing up"
              + " $output_base/execution_root/__main__/external for the real top-level 'external' "
              + "directory.")
  public boolean experimentalSiblingRepositoryLayout;

  @Option(
      name = "incompatible_allow_tags_propagation",
      oldName = "experimental_allow_tags_propagation",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, tags will be propagated from a target to the actions' execution"
              + " requirements; otherwise tags are not propagated. See"
              + " https://github.com/bazelbuild/bazel/issues/8830 for details.")
  public boolean experimentalAllowTagsPropagation;

  @Option(
      name = "incompatible_always_check_depset_elements",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Check the validity of elements added to depsets, in all constructors. Elements must be"
              + " immutable, but historically the depset(direct=...) constructor forgot to check."
              + " Use tuples instead of lists in depset elements."
              + " See https://github.com/bazelbuild/bazel/issues/10313 for details.")
  public boolean incompatibleAlwaysCheckDepsetElements;

  @Option(
      name = "incompatible_disable_target_provider_fields",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, disable the ability to utilize the default provider via field "
              + "syntax. Use provider-key syntax instead. For example, instead of using "
              + "`ctx.attr.dep.files` to access `files`, utilize `ctx.attr.dep[DefaultInfo].files "
              + "See "
              + "https://github.com/bazelbuild/bazel/issues/9014 for details.")
  public boolean incompatibleDisableTargetProviderFields;

  @Option(
      name = "incompatible_disable_target_default_provider_fields",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, disable the ability to access providers on 'target' objects via field "
              + "syntax. Use provider-key syntax instead. For example, instead of using "
              + "`ctx.attr.dep.my_info` to access `my_info` from inside a rule implementation "
              + "function, use `ctx.attr.dep[MyInfo]`. See "
              + "https://github.com/bazelbuild/bazel/issues/9014 for details.")
  public boolean incompatibleDisableTargetDefaultProviderFields;

  // DO NOT inline this constant: it's used by copybara to alter the default value. It's different
  // in Google and in OSS Bazel.
  private static final String DISALLOW_EMPTY_GLOB_DEFAULT = "true";

  @Option(
      name = "incompatible_disallow_empty_glob",
      defaultValue = DISALLOW_EMPTY_GLOB_DEFAULT,
      category = "incompatible changes",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, the default value of the `allow_empty` argument of glob() is False.")
  public boolean incompatibleDisallowEmptyGlob;

  @Option(
      name = "incompatible_disallow_struct_provider_syntax",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, rule implementation functions may not return a struct. They must "
              + "instead return a list of provider instances.")
  public boolean incompatibleDisallowStructProviderSyntax;

  @Option(
      name = "incompatible_package_group_has_public_syntax",
      defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX,
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "In package_group's `packages` attribute, allows writing \"public\" or \"private\" to"
              + " refer to all packages or no packages respectively.")
  public boolean incompatiblePackageGroupHasPublicSyntax;

  @Option(
      name = "incompatible_fix_package_group_reporoot_syntax",
      defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX,
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "In package_group's `packages` attribute, changes the meaning of the value \"//...\" to"
              + " refer to all packages in the current repository instead of all packages in any"
              + " repository. You can use the special value \"public\" in place of \"//...\" to"
              + " obtain the old behavior. This flag requires"
              + " that --incompatible_package_group_has_public_syntax also be enabled.")
  public boolean incompatibleFixPackageGroupReporootSyntax;

  @Option(
      name = "incompatible_no_attr_license",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, disables the function `attr.license`.")
  public boolean incompatibleNoAttrLicense;

  @Option(
      name = "incompatible_no_implicit_file_export",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set, (used) source files are are package private unless exported explicitly. See "
              + "https://github.com/bazelbuild/proposals/blob/master/designs/"
              + "2019-10-24-file-visibility.md")
  public boolean incompatibleNoImplicitFileExport;

  @Option(
      name = "incompatible_no_rule_outputs_param",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, disables the `outputs` parameter of the `rule()` Starlark function.")
  public boolean incompatibleNoRuleOutputsParam;

  @Option(
      name = "incompatible_run_shell_command_string",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If set to true, the command parameter of actions.run_shell will only accept string")
  public boolean incompatibleRunShellCommandString;

  /** Used in an integration test to confirm that flags are visible to the interpreter. */
  @Option(
      name = "internal_starlark_flag_test_canary",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN})
  public boolean internalStarlarkFlagTestCanary;

  @Option(
      name = "incompatible_do_not_split_linking_cmdline",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When true, Bazel no longer modifies command line flags used for linking, and also "
              + "doesn't selectively decide which flags go to the param file and which don't.  "
              + "See https://github.com/bazelbuild/bazel/issues/7670 for details.")
  public boolean incompatibleDoNotSplitLinkingCmdline;

  @Option(
      name = "incompatible_use_cc_configure_from_rules_cc",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When true, Bazel will no longer allow using cc_configure from @bazel_tools. "
              + "Please see https://github.com/bazelbuild/bazel/issues/10134 for details and "
              + "migration instructions.")
  public boolean incompatibleUseCcConfigureFromRulesCc;

  @Option(
      name = "incompatible_unambiguous_label_stringification",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When true, Bazel will stringify the label @//foo:bar to @//foo:bar, instead of"
              + " //foo:bar. This only affects the behavior of str(), the % operator, and so on;"
              + " the behavior of repr() is unchanged. See"
              + " https://github.com/bazelbuild/bazel/issues/15916 for more information.")
  public boolean incompatibleUnambiguousLabelStringification;

  @Option(
      name = "incompatible_depset_for_libraries_to_link_getter",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "When true, Bazel no longer returns a list from linking_context.libraries_to_link but "
              + "returns a depset instead.")
  public boolean incompatibleDepsetForLibrariesToLinkGetter;

  @Option(
      name = "incompatible_java_info_merge_runtime_module_flags",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, the JavaInfo constructor will merge add_exports and "
              + "add_opens of runtime_deps in addition to deps and exports.")
  public boolean incompatibleJavaInfoMergeRuntimeModuleFlags;

  @Option(
      name = "max_computation_steps",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "The maximum number of Starlark computation steps that may be executed by a BUILD file"
              + " (zero means no limit).")
  public long maxComputationSteps;

  @Option(
      name = "nested_set_depth_limit",
      defaultValue = "3500",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "The maximum depth of the graph internal to a depset (also known as NestedSet), above"
              + " which the depset() constructor will fail.")
  public int nestedSetDepthLimit;

  @Option(
      name = "incompatible_disable_starlark_host_transitions",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If set to true, rule attributes cannot set 'cfg = \"host\"'. Rules should set "
              + "'cfg = \"exec\"' instead.")
  public boolean incompatibleDisableStarlarkHostTransitions;

  @Option(
      name = "incompatible_merge_fixed_and_default_shell_env",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled, actions registered with ctx.actions.run and ctx.actions.run_shell with both"
              + " 'env' and 'use_default_shell_env = True' specified will use an environment"
              + " obtained from the default shell environment by overriding with the values passed"
              + " in to 'env'. If disabled, the value of 'env' is completely ignored in this case.")
  public boolean incompatibleMergeFixedAndDefaultShellEnv;

  @Option(
      name = "incompatible_disable_objc_library_transition",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Disable objc_library's custom transition and inherit "
              + "from the top level target instead (No-op in Bazel)")
  public boolean incompatibleDisableObjcLibraryTransition;

  // remove after Bazel LTS in Nov 2023
  @Option(
      name = "incompatible_fail_on_unknown_attributes",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If enabled, targets that have unknown attributes set to None fail.")
  public boolean incompatibleFailOnUnknownAttributes;

  // Flip when dependencies to rules_* repos are upgraded and protobuf registers toolchains
  @Option(
      name = "incompatible_enable_proto_toolchain_resolution",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If true, proto lang rules define toolchains from protobuf repository.")
  public boolean incompatibleEnableProtoToolchainResolution;

  // Flip when java_single_jar is feature complete
  @Option(
      name = "incompatible_disable_non_executable_java_binary",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "If true, java_binary is always executable. create_executable attribute is removed.")
  public boolean incompatibleDisableNonExecutableJavaBinary;

  @Option(
      name = "experimental_rule_extension_api",
      defaultValue = FlagConstants.DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API,
      documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "Enable experimental rule extension API and subrule APIs")
  public boolean experimentalRuleExtensionApi;

  @Option(
      name = "experimental_dormant_deps",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          " If set to true, attr.label(materializer=), attr(for_dependency_resolution=),"
              + " attr.dormant_label(), attr.dormant_label_list() and"
              + " rule(for_dependency_resolution=) are allowed.")
  public boolean experimentalDormantDeps;

  @Option(
      name = "incompatible_enable_deprecated_label_apis",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If enabled, certain deprecated APIs (native.repository_name, Label.workspace_name,"
              + " Label.relative) can be used.")
  public boolean enableDeprecatedLabelApis;

  @Option(
      name = "incompatible_disallow_ctx_resolve_tools",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If set to true, calling the deprecated ctx.resolve_tools API always fails. Uses of this"
              + " API should be replaced by an executable or tools argument to ctx.actions.run or"
              + " ctx.actions.run_shell.")
  public boolean incompatibleDisallowCtxResolveTools;

  @Option(
      name = "incompatible_simplify_unconditional_selects_in_rule_attrs",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If true, simplify configurable rule attributes which contain only unconditional selects;"
              + " for example, if [\"a\"] + select(\"//conditions:default\", [\"b\"]) is assigned"
              + " to a rule attribute, it is stored as [\"a\", \"b\"]. This option does not affect"
              + " attributes of symbolic macros or attribute default values.")
  public boolean incompatibleSimplifyUnconditionalSelectsInRuleAttrs;

  @Option(
      name = "experimental_enable_starlark_set",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If true, enable the set data type and set() constructor in Starlark.")
  public boolean experimentalEnableStarlarkSet;

  @Option(
      name = "incompatible_locations_prefers_executable",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "Whether a target that provides an executable expands to the executable rather than the"
              + " files in <code>DefaultInfo.files</code> under $(locations ...) expansion if the"
              + " number of files is not 1.")
  public boolean incompatibleLocationsPrefersExecutable;

  /** An enum for specifying different modes for UTF-8 checking of Starlark files. */
  public enum Utf8EnforcementMode {
    OFF,
    WARNING,
    ERROR;

    /** Converts to {@link Utf8EnforcementMode}. */
    public static class Converter extends BoolOrEnumConverter<Utf8EnforcementMode> {
      public Converter() {
        super(
            Utf8EnforcementMode.class,
            "UTF-8 enforcement mode",
            Utf8EnforcementMode.ERROR,
            Utf8EnforcementMode.OFF);
      }
    }
  }

  @Option(
      name = "incompatible_enforce_starlark_utf8",
      defaultValue = "warning",
      converter = Utf8EnforcementMode.Converter.class,
      documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled (or set to 'error'), fail if Starlark files are not UTF-8 encoded. If set to"
              + " 'warning', emit a warning instead. If set to 'off', Bazel assumes that Starlark"
              + " files are UTF-8 encoded but does not verify this assumption. Note that Starlark"
              + " files which are not UTF-8 encoded can cause Bazel to behave inconsistently.")
  public Utf8EnforcementMode incompatibleEnforceStarlarkUtf8;

  /**
   * An interner to reduce the number of StarlarkSemantics instances. A single Blaze instance should
   * never accumulate a large number of these and being able to shortcut on object identity makes a
   * comparison later much faster. In particular, the semantics become part of the
   * MethodDescriptorKey in CallExpression and are thus compared for every function call.
   */
  private static final Interner<StarlarkSemantics> INTERNER = BlazeInterners.newWeakInterner();

  /** Constructs a {@link StarlarkSemantics} object corresponding to this set of option values. */
  public StarlarkSemantics toStarlarkSemantics() {
    // This function connects command-line flags to their corresponding StarlarkSemantics keys.
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            .setBool(EXPERIMENTAL_JAVA_LIBRARY_EXPORT, experimentalJavaLibraryExport)
            .setBool(
                INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
                incompatibleStopExportingLanguageModules)
            .setBool(INCOMPATIBLE_ALLOW_TAGS_PROPAGATION, experimentalAllowTagsPropagation)
            .set(EXPERIMENTAL_BUILTINS_BZL_PATH, experimentalBuiltinsBzlPath)
            .set(INCOMPATIBLE_AUTOLOAD_EXTERNALLY, incompatibleAutoloadExternally)
            .set(REPOSITORIES_WITHOUT_AUTOLOAD, repositoriesWithoutAutoloads)
            .setBool(EXPERIMENTAL_BUILTINS_DUMMY, experimentalBuiltinsDummy)
            .set(EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE, experimentalBuiltinsInjectionOverride)
            .setBool(EXPERIMENTAL_BZL_VISIBILITY, experimentalBzlVisibility)
            .setBool(CHECK_BZL_VISIBILITY, checkBzlVisibility)
            .setBool(
                EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS, experimentalEnableAndroidMigrationApis)
            .setBool(
                EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING,
                experimentalSinglePackageToolchainBinding)
            .setBool(EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS, experimentalEnableFirstClassMacros)
            .setBool(EXPERIMENTAL_ENABLE_SCL_DIALECT, experimentalEnableSclDialect)
            .setBool(EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, experimentalIsolatedExtensionUsages)
            .setBool(INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, incompatibleNoImplicitWatchLabel)
            .setBool(EXPERIMENTAL_GOOGLE_LEGACY_API, experimentalGoogleLegacyApi)
            .setBool(EXPERIMENTAL_PLATFORMS_API, experimentalPlatformsApi)
            .setBool(EXPERIMENTAL_CC_SHARED_LIBRARY, experimentalCcSharedLibrary)
            .setBool(EXPERIMENTAL_CC_STATIC_LIBRARY, experimentalCcStaticLibrary)
            .setBool(EXPERIMENTAL_REPO_REMOTE_EXEC, experimentalRepoRemoteExec)
            .setBool(EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE, experimentalDisableExternalPackage)
            .setBool(EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT, experimentalSiblingRepositoryLayout)
            .setBool(
                INCOMPATIBLE_DISABLE_TARGET_PROVIDER_FIELDS,
                incompatibleDisableTargetProviderFields)
            .setBool(
                INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS, incompatibleAlwaysCheckDepsetElements)
            .setBool(INCOMPATIBLE_DISALLOW_EMPTY_GLOB, incompatibleDisallowEmptyGlob)
            .setBool(
                INCOMPATIBLE_DISALLOW_STRUCT_PROVIDER_SYNTAX,
                incompatibleDisallowStructProviderSyntax)
            .setBool(
                INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX,
                incompatiblePackageGroupHasPublicSyntax)
            .setBool(
                INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX,
                incompatibleFixPackageGroupReporootSyntax)
            .setBool(
                INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS,
                incompatibleJavaInfoMergeRuntimeModuleFlags)
            .setBool(INCOMPATIBLE_NO_ATTR_LICENSE, incompatibleNoAttrLicense)
            .setBool(INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT, incompatibleNoImplicitFileExport)
            .setBool(INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM, incompatibleNoRuleOutputsParam)
            .setBool(INCOMPATIBLE_RUN_SHELL_COMMAND_STRING, incompatibleRunShellCommandString)
            .setBool(StarlarkSemantics.PRINT_TEST_MARKER, internalStarlarkFlagTestCanary)
            .setBool(
                INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE, incompatibleDoNotSplitLinkingCmdline)
            .set(INCOMPATIBLE_ENFORCE_STARLARK_UTF8, incompatibleEnforceStarlarkUtf8)
            .setBool(
                INCOMPATIBLE_USE_CC_CONFIGURE_FROM_RULES_CC, incompatibleUseCcConfigureFromRulesCc)
            .setBool(
                INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION,
                incompatibleUnambiguousLabelStringification)
            .setBool(
                INCOMPATIBLE_DEPSET_FOR_LIBRARIES_TO_LINK_GETTER,
                incompatibleDepsetForLibrariesToLinkGetter)
            .setBool(INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API, incompatibleRequireLinkerInputCcApi)
            .set(MAX_COMPUTATION_STEPS, maxComputationSteps)
            .set(NESTED_SET_DEPTH_LIMIT, nestedSetDepthLimit)
            .setBool(
                INCOMPATIBLE_DISABLE_STARLARK_HOST_TRANSITIONS,
                incompatibleDisableStarlarkHostTransitions)
            .setBool(
                INCOMPATIBLE_MERGE_FIXED_AND_DEFAULT_SHELL_ENV,
                incompatibleMergeFixedAndDefaultShellEnv)
            .setBool(
                INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION,
                incompatibleDisableObjcLibraryTransition)
            .setBool(INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES, incompatibleFailOnUnknownAttributes)
            .setBool(
                INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION,
                incompatibleEnableProtoToolchainResolution)
            .setBool(
                INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY,
                incompatibleDisableNonExecutableJavaBinary)
            .setBool(
                INCOMPATIBLE_DISABLE_TARGET_DEFAULT_PROVIDER_FIELDS,
                incompatibleDisableTargetDefaultProviderFields)
            .setBool(EXPERIMENTAL_RULE_EXTENSION_API, experimentalRuleExtensionApi)
            .setBool(EXPERIMENTAL_DORMANT_DEPS, experimentalDormantDeps)
            .setBool(INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS, enableDeprecatedLabelApis)
            .setBool(
                INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH, incompatibleStopExportingBuildFilePath)
            .setBool(INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS, incompatibleDisallowCtxResolveTools)
            .setBool(
                INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS,
                incompatibleSimplifyUnconditionalSelectsInRuleAttrs)
            .setBool(
                INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE, incompatibleLocationsPrefersExecutable)
            .setBool(
                StarlarkSemantics.EXPERIMENTAL_ENABLE_STARLARK_SET, experimentalEnableStarlarkSet)
            .build();
    return INTERNER.intern(semantics);
  }

  // StarlarkSemantics keys used by Bazel
  //
  // Sadly it is impossible to move most of these declarations closer to the
  // code they affect: the toStarlarkSemantics function above must depend
  // on every key that is bound to a command-line flag, which means those keys must
  // live in this package, not in the application logic above.
  // (In principle, a key not associated with a command-line flag may be declared anywhere.)

  // booleans: the +/- prefix indicates the default value (true/false).
  public static final String EXPERIMENTAL_JAVA_LIBRARY_EXPORT = "-experimental_java_library_export";
  public static final String INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES =
      "-incompatible_stop_exporting_language_modules";
  public static final String INCOMPATIBLE_ALLOW_TAGS_PROPAGATION =
      "+incompatible_allow_tags_propagation";
  public static final String EXPERIMENTAL_BUILTINS_DUMMY = "-experimental_builtins_dummy";
  public static final String EXPERIMENTAL_BZL_VISIBILITY = "+experimental_bzl_visibility";
  public static final String CHECK_BZL_VISIBILITY = "+check_bzl_visibility";
  public static final String EXPERIMENTAL_CC_SHARED_LIBRARY = "-experimental_cc_shared_library";
  public static final String EXPERIMENTAL_CC_STATIC_LIBRARY = "-experimental_cc_static_library";
  public static final String EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE =
      "-experimental_disable_external_package";
  public static final String EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS =
      "-experimental_enable_android_migration_apis";
  public static final String EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING =
      "-experimental_single_package_toolchain_binding";
  public static final String EXPERIMENTAL_ENABLE_FIRST_CLASS_MACROS =
      "+experimental_enable_first_class_macros";
  public static final String EXPERIMENTAL_ENABLE_SCL_DIALECT = "+experimental_enable_scl_dialect";
  public static final String ENABLE_BZLMOD = "+enable_bzlmod";
  public static final String ENABLE_WORKSPACE = "-enable_workspace";
  public static final String EXPERIMENTAL_ISOLATED_EXTENSION_USAGES =
      "-experimental_isolated_extension_usages";
  public static final String INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL =
      "+incompatible_no_implicit_watch_label";
  public static final String EXPERIMENTAL_GOOGLE_LEGACY_API = "-experimental_google_legacy_api";
  public static final String EXPERIMENTAL_PLATFORMS_API = "-experimental_platforms_api";
  public static final String EXPERIMENTAL_REPO_REMOTE_EXEC = "-experimental_repo_remote_exec";
  public static final String EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT =
      "-experimental_sibling_repository_layout";
  public static final String INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS =
      "+incompatible_always_check_depset_elements";
  public static final String INCOMPATIBLE_DEPSET_FOR_LIBRARIES_TO_LINK_GETTER =
      "+incompatible_depset_for_libraries_to_link_getter";
  public static final String INCOMPATIBLE_DISABLE_TARGET_PROVIDER_FIELDS =
      "-incompatible_disable_target_provider_fields";
  // Note that INCOMPATIBLE_DISALLOW_EMPTY_GLOB differs in Google and in OSS Bazel.
  public static final String INCOMPATIBLE_DISALLOW_EMPTY_GLOB = "+incompatible_disallow_empty_glob";
  public static final String INCOMPATIBLE_DISALLOW_STRUCT_PROVIDER_SYNTAX =
      "+incompatible_disallow_struct_provider_syntax";
  public static final String INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX =
      FlagConstants.INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX;
  public static final String INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX =
      FlagConstants.INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX;
  public static final String INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE =
      "+incompatible_do_not_split_linking_cmdline";
  public static final String INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS =
      "-incompatible_java_info_merge_runtime_module_flags";
  public static final String INCOMPATIBLE_NO_ATTR_LICENSE = "+incompatible_no_attr_license";
  public static final String INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT =
      "-incompatible_no_implicit_file_export";
  public static final String INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM =
      "-incompatible_no_rule_outputs_param";
  public static final String INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API =
      "+incompatible_require_linker_input_cc_api";
  public static final String INCOMPATIBLE_RUN_SHELL_COMMAND_STRING =
      "+incompatible_run_shell_command_string";
  public static final String INCOMPATIBLE_USE_CC_CONFIGURE_FROM_RULES_CC =
      "-incompatible_use_cc_configure_from_rules";
  public static final String INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION =
      "+incompatible_unambiguous_label_stringification";
  public static final String INCOMPATIBLE_DISABLE_STARLARK_HOST_TRANSITIONS =
      "-incompatible_disable_starlark_host_transitions";
  public static final String INCOMPATIBLE_MERGE_FIXED_AND_DEFAULT_SHELL_ENV =
      "+experimental_merge_fixed_and_default_shell_env";
  public static final String INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION =
      "+incompatible_disable_objc_library_transition";
  public static final String INCOMPATIBLE_FAIL_ON_UNKNOWN_ATTRIBUTES =
      "+incompatible_fail_on_unknown_attributes";
  public static final String INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION =
      "-incompatible_enable_proto_toolchain_resolution";
  public static final String INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY =
      "-incompatible_disable_non_executable_java_binary";
  public static final String INCOMPATIBLE_DISABLE_TARGET_DEFAULT_PROVIDER_FIELDS =
      "-incompatible_disable_target_default_provider_fields";
  public static final String EXPERIMENTAL_RULE_EXTENSION_API =
      FlagConstants.DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API_NAME;
  public static final String EXPERIMENTAL_DORMANT_DEPS = "-experimental_dormant_deps";
  public static final String INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS =
      "+incompatible_enable_deprecated_label_apis";
  public static final String INCOMPATIBLE_STOP_EXPORTING_BUILD_FILE_PATH =
      "-incompatible_stop_exporting_build_file_path";
  public static final String INCOMPATIBLE_DISALLOW_CTX_RESOLVE_TOOLS =
      "+incompatible_disallow_ctx_resolve_tools";
  public static final String INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS =
      "+incompatible_simplify_unconditional_selects_in_rule_attrs";
  public static final String INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE =
      "+incompatible_locations_prefers_executable";
  // non-booleans
  public static final StarlarkSemantics.Key<String> EXPERIMENTAL_BUILTINS_BZL_PATH =
      new StarlarkSemantics.Key<>("experimental_builtins_bzl_path", "%bundled%");
  public static final StarlarkSemantics.Key<List<String>> INCOMPATIBLE_AUTOLOAD_EXTERNALLY =
      new StarlarkSemantics.Key<>(
          "incompatible_autoload_externally",
          FlagConstants.DEFAULT_INCOMPATIBLE_AUTOLOAD_EXTERNALLY.isEmpty()
              ? ImmutableList.of()
              : ImmutableList.copyOf(
                      FlagConstants.DEFAULT_INCOMPATIBLE_AUTOLOAD_EXTERNALLY.split(","))
                  .stream()
                  .distinct()
                  .sorted()
                  .collect(toImmutableList()));
  public static final StarlarkSemantics.Key<List<String>> REPOSITORIES_WITHOUT_AUTOLOAD =
      new StarlarkSemantics.Key<>("repositories_without_autoloads", ImmutableList.of());
  public static final StarlarkSemantics.Key<List<String>> EXPERIMENTAL_BUILTINS_INJECTION_OVERRIDE =
      new StarlarkSemantics.Key<>("experimental_builtins_injection_override", ImmutableList.of());
  public static final StarlarkSemantics.Key<Utf8EnforcementMode>
      INCOMPATIBLE_ENFORCE_STARLARK_UTF8 =
          new StarlarkSemantics.Key<>(
              "incompatible_enforce_starlark_utf8", Utf8EnforcementMode.WARNING);
  public static final StarlarkSemantics.Key<Long> MAX_COMPUTATION_STEPS =
      new StarlarkSemantics.Key<>("max_computation_steps", 0L);
  public static final StarlarkSemantics.Key<Integer> NESTED_SET_DEPTH_LIMIT =
      new StarlarkSemantics.Key<>("nested_set_depth_limit", 3500);
}
