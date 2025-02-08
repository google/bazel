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

package com.google.devtools.build.lib.bazel.rules;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCppRuleClasses;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.rules.java.JavaCompileActionContext;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.TriState;
import java.io.IOException;
import java.util.List;

/** Module implementing the rule set of Bazel. */
public final class BazelRulesModule extends BlazeModule {

  /**
   * This is where deprecated options used by both Bazel and Blaze but only needed for the build
   * command go to die.
   */
  public static class BuildGraveyardOptions extends OptionsBase {

    @Option(
        name = "j2objc_dead_code_removal",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.")
    public boolean removeDeadCode;

    @Option(
        name = "experimental_proto_extra_actions",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help = "Deprecated. No-op.")
    public boolean experimentalProtoExtraActions;

    @Option(
        name = "enable_fdo_profile_absolute_path",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.")
    public boolean enableFdoProfileAbsolutePath;

    @Option(
        name = "experimental_genquery_use_graphless_query",
        defaultValue = "auto",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.")
    public TriState useGraphlessQuery;

    @Option(
        name = "use_top_level_targets_for_symlinks",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.")
    public boolean useTopLevelTargetsForSymlinks;

    @Option(
        name = "experimental_skyframe_prepare_analysis",
        deprecationWarning = "This flag is a no-op and will be deleted in a future release.",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.")
    public boolean skyframePrepareAnalysis;

    @Option(
        name = "incompatible_disable_legacy_flags_cc_toolchain_api",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED},
        help =
            "Flag for disabling the legacy cc_toolchain Starlark API for accessing legacy "
                + "CROSSTOOL fields.")
    public boolean disableLegacyFlagsCcToolchainApi;

    @Option(
        name = "incompatible_enable_profile_by_default",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean enableProfileByDefault;

    @Option(
        name = "incompatible_override_toolchain_transition",
        defaultValue = "true",
        deprecationWarning = "This is now always set, please remove this flag.",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = OptionEffectTag.NO_OP,
        help = "Deprecated, this is no longer in use and should be removed.")
    public boolean overrideToolchainTransition;

    @Option(
        name = "experimental_parse_headers_skipped_if_corresponding_srcs_found",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean parseHeadersSkippedIfCorrespondingSrcsFound;

    @Option(
        name = "experimental_worker_as_resource",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op, will be removed soon.")
    public boolean workerAsResource;

    @Option(
        name = "high_priority_workers",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op, will be removed soon.",
        allowMultiple = true)
    public List<String> highPriorityWorkers;

    @Option(
        name = "target_platform_fallback",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
        effectTags = {OptionEffectTag.NO_OP},
        help = "This option is deprecated and has no effect.")
    public String targetPlatformFallback;

    @Option(
        name = "incompatible_auto_configure_host_platform",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "This option is deprecated and has no effect.")
    public boolean autoConfigureHostPlatform;

    @Deprecated
    @Option(
        name = "experimental_require_availability_info",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "false",
        help = "Deprecated no-op.")
    public boolean requireAvailabilityInfo;

    @Option(
        name = "experimental_collect_local_action_metrics",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated no-op.")
    public boolean collectLocalExecutionStatistics;

    @Option(
        name = "experimental_collect_local_sandbox_action_metrics",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.LOGGING,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated no-op.")
    public boolean collectLocalSandboxExecutionStatistics;

    @Option(
        name = "experimental_enable_starlark_doc_extract",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help = "Deprecated no-op.")
    public boolean enableBzlDocDump;

    // TODO(b/274595070): Remove this option.
    @Option(
        name = "experimental_parallel_aquery_output",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.QUERY,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean parallelAqueryOutput;

    @Option(
        name = "experimental_show_artifacts",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated no-op.")
    public boolean showArtifacts;

    @Option(
        name = "announce",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Deprecated. No-op.",
        deprecationWarning = "This option is now deprecated and is a no-op")
    public boolean announce;

    @Option(
        name = "action_cache_store_output_metadata",
        oldName = "experimental_action_cache_store_output_metadata",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "no-op")
    public boolean actionCacheStoreOutputMetadata;

    @Option(
        name = "discard_actions_after_execution",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = OptionMetadataTag.INCOMPATIBLE_CHANGE,
        effectTags = {OptionEffectTag.NO_OP},
        help = "This option is deprecated and has no effect.")
    public boolean discardActionsAfterExecution;

    @Option(
        name = "defer_param_files",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "This option is deprecated and has no effect and will be removed in the future.")
    public boolean deferParamFiles;

    @Option(
        name = "check_fileset_dependencies_recursively",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        deprecationWarning =
            "This flag is a no-op and fileset dependencies are always checked "
                + "to ensure correctness of builds.",
        effectTags = {OptionEffectTag.NO_OP})
    public boolean checkFilesetDependenciesRecursively;

    @Option(
        name = "experimental_skyframe_native_filesets",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        deprecationWarning = "This flag is a no-op and skyframe-native-filesets is always true.")
    public boolean skyframeNativeFileset;

    @Option(
        name = "collapse_duplicate_defines",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "no-op")
    public boolean collapseDuplicateDefines;

    @Option(
        name = "incompatible_require_javaplugininfo_in_javacommon",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "When enabled java_common.compile only accepts JavaPluginInfo for plugins.")
    public boolean requireJavaPluginInfo;

    @Option(
        name = "experimental_build_transitive_python_runfiles",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        oldName = "incompatible_build_transitive_python_runfiles")
    public boolean buildTransitiveRunfilesTrees;

    @Option(
        name = "experimental_use_new_worker_pool",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean useNewWorkerPool;
  }

  /** This is where deprecated Bazel-specific options only used by the build command go to die. */
  public static final class BazelBuildGraveyardOptions extends BuildGraveyardOptions {
    @Option(
        name = "incompatible_load_python_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadPythonRulesFromBzl;

    @Option(
        name = "incompatible_load_proto_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadProtoRulesFromBzl;

    @Option(
        name = "incompatible_load_java_rules_from_bzl",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "Deprecated no-op.")
    public boolean loadJavaRulesFromBzl;

    @Option(
        name = "make_variables_source",
        defaultValue = "configuration",
        metadataTags = {OptionMetadataTag.HIDDEN, OptionMetadataTag.DEPRECATED},
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP})
    public String makeVariableSource;

    @Option(
        name = "force_ignore_dash_static",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "noop")
    public boolean forceIgnoreDashStatic;

    @Option(
        name = "experimental_profile_action_counts",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean enableActionCountProfile;

    @Option(
        name = "incompatible_remove_binary_profile",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean removeBinaryProfile;

    @Option(
        name = "experimental_post_profile_started_event",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean postProfileStartedEvent;

    @Option(
        name = "incompatible_dont_use_javasourceinfoprovider",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op")
    public boolean dontUseJavaSourceInfoProvider;

    private static final String ANDROID_FLAG_DEPRECATION =
        "Legacy Android flags have been deprecated. See"
            + " https://blog.bazel.build/2023/11/15/android-platforms.html for details and"
            + " migration directions";

    @Option(
        name = "android_sdk",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public String sdk;

    @Option(
        name = "android_cpu",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public String cpu;

    @Option(
        name = "android_crosstool_top",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public String androidCrosstoolTop;

    @Option(
        name = "android_grte_top",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public String androidLibcTopLabel;

    @Option(
        name = "incompatible_enable_android_toolchain_resolution",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public boolean incompatibleUseToolchainResolution;

    @Option(
        name = "fat_apk_cpu",
        converter = Converters.CommaSeparatedOptionSetConverter.class,
        defaultValue = "armeabi-v7a",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op",
        deprecationWarning = ANDROID_FLAG_DEPRECATION)
    public List<String> fatApkCpus;
  }

  /**
   * This is where deprecated options which need to be available for all commands go to die. If you
   * want to graveyard an all-command option specific to Blaze or Bazel, create a subclass.
   */
  public static final class AllCommandGraveyardOptions extends OptionsBase {

    @Option(
        name = "auto_cpu_environment_group",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help = "No-op")
    public String autoCpuEnvironmentGroup;

    @Option(
        name = "incompatible_top_level_aspects_require_providers",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean incompatibleTopLevelAspectsRequireProviders;

    @Option(
        name = "separate_aspect_deps",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean separateAspectDeps;

    @Option(
        name = "incompatible_visibility_private_attributes_at_definition",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op")
    public boolean incompatibleVisibilityPrivateAttributesAtDefinition;

    @Option(
        name = "incompatible_new_actions_api",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean incompatibleNewActionsApi;

    @Option(
        name = "experimental_enable_aspect_hints",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL})
    public boolean enableAspectHints;

    @Option(
        name = "bes_best_effort",
        defaultValue = "false",
        deprecationWarning =
            "BES best effort upload has been removed. The flag has no more "
                + "functionality attached to it and will be removed in a future release.",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op")
    public boolean besBestEffort;

    @Option(
        name = "experimental_use_event_based_build_completion_status",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = OptionMetadataTag.EXPERIMENTAL,
        effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION},
        help = "No-op")
    public boolean useEventBasedBuildCompletionStatus;

    @Option(
        name = "experimental_java_proto_add_allowed_public_imports",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.EXPERIMENTAL},
        help = "This flag is a noop and scheduled for removal.")
    public boolean experimentalJavaProtoAddAllowedPublicImports;

    @Option(
        name = "java_optimization_mode",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "Do not use.")
    public String javaOptimizationMode;

    @Option(
        name = "incompatible_depset_for_java_output_source_jars",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = {OptionEffectTag.NO_OP},
        metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
        help = "No-op.")
    public boolean incompatibleDepsetForJavaOutputSourceJars;

    @Option(
        name = "incompatible_use_plus_in_repo_names",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = OptionEffectTag.LOADING_AND_ANALYSIS,
        help = "No-op.")
    public boolean incompatibleUsePlusInRepoNames;

    @Option(
        name = "enable_bzlmod",
        oldName = "experimental_enable_bzlmod",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = OptionEffectTag.LOADING_AND_ANALYSIS,
        help = "No-op.")
    public boolean enableBzlmod;

    @Option(
        name = "enable_workspace",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = OptionEffectTag.LOADING_AND_ANALYSIS,
        help = "No-op.")
    public boolean enableWorkspace;

    @Option(
        name = "experimental_announce_profile_path",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean announceProfilePath;

    @Option(
        name = "incompatible_existing_rules_immutable_view",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean incompatibleExistingRulesImmutableView;

    // Safe to delete after July 2025
    @Option(
        name = "incompatible_no_package_distribs",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean incompatibleNoPackageDistribs;

    @Option(
        name = "experimental_action_resource_set",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = {OptionEffectTag.NO_OP},
        help = "No-op.")
    public boolean experimentalActionResourceSet;
  }

  @Override
  public void initializeRuleClasses(ConfiguredRuleClassProvider.Builder builder) {
    BazelRuleClassProvider.setup(builder);

    try {
      // Load auto-configuration files, it is made outside of the rule class provider so that it
      // will not be loaded for our Java tests.
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelCppRuleClasses.class, "cc_configure.WORKSPACE"));
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelRulesModule.class, "xcode_configure.WORKSPACE"));
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(BazelRulesModule.class, "rules_suffix.WORKSPACE"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return "build".equals(command.name())
        ? ImmutableList.of(BazelBuildGraveyardOptions.class, AllCommandGraveyardOptions.class)
        : ImmutableList.of(AllCommandGraveyardOptions.class);
  }

  @Override
  public void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    registryBuilder.register(JavaCompileActionContext.class, new JavaCompileActionContext());
  }
}
