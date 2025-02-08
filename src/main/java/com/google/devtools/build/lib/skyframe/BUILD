load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/lib/skyframe/actiongraph/v2:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/packages/testing:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/proto:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/rewinding:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

# TODO(b/144899336): Split this further.
java_library(
    name = "skyframe_cluster",
    srcs = [
        "ActionExecutionFunction.java",
        "ActionLookupConflictFindingFunction.java",
        "ActionLookupValuesTraversal.java",
        "AspectCompletor.java",
        "AspectFunction.java",
        "BazelSkyframeExecutorConstants.java",
        "BuildTopLevelAspectsDetailsFunction.java",
        "BzlLoadFunction.java",
        "BzlmodRepoRuleFunction.java",
        "CompletionFunction.java",
        "ConfiguredTargetEvaluationExceptions.java",
        "ConfiguredTargetFunction.java",
        "CoverageReportFunction.java",
        "DependencyResolver.java",
        "DirectoryListingStateFunction.java",
        "DirtinessCheckerUtils.java",
        "EnvironmentBackedRecursivePackageProvider.java",
        "ExternalFilesHelper.java",
        "ExternalPackageFunction.java",
        "FileStateFunction.java",
        "HighWaterMarkLimiter.java",
        "LocalRepositoryLookupFunction.java",
        "PackageFunction.java",
        "PackageFunctionWithMultipleGlobDeps.java",
        "PackageFunctionWithSingleGlobsDep.java",
        "PackageFunctionWithoutGlobDeps.java",
        "PrepareDepsOfPatternFunction.java",
        "SequencedSkyframeExecutor.java",
        "SequencedSkyframeExecutorFactory.java",
        "SkyframeAnalysisAndExecutionResult.java",
        "SkyframeAnalysisResult.java",
        "SkyframeBuildView.java",
        "SkyframeExecutor.java",
        "SkyframeExecutorFactory.java",
        "SkyframeExecutorWrappingWalkableGraph.java",
        "SkyframePackageManager.java",
        "SkyframeTargetPatternEvaluator.java",
        "StarlarkAspectFactory.java",
        "StarlarkBuildSettingsDetailsFunction.java",
        "StarlarkBuiltinsFunction.java",
        "TargetCompletor.java",
        "TargetPatternFunction.java",
        "TargetPatternPhaseFunction.java",
        "TestCompletionFunction.java",
        "TopLevelActionLookupConflictFindingFunction.java",
        "ToplevelStarlarkAspectFunction.java",
        "TransitiveTargetFunction.java",
        "WorkspaceFileFunction.java",
    ],
    deps = [
        ":action_artifact_cycle_reporter",
        ":action_environment_function",
        ":action_execution_inactivity_watchdog",
        ":action_execution_state",
        ":action_execution_value",
        ":action_input_collected_event",
        ":action_input_map_helper",
        ":action_input_metadata_provider",
        ":action_lookup_conflict_finding_value",
        ":action_output_metadata_store",
        ":action_template_expansion_function",
        ":action_template_expansion_value",
        ":action_utils",
        ":analysis_cache_clear_event",
        ":analysis_progress_receiver",
        ":artifact_conflict_finder",
        ":artifact_function",
        ":artifact_nested_set_function",
        ":artifact_nested_set_value",
        ":aspect_completion_value",
        ":aspect_creation_exception",
        ":aspect_key_creator",
        ":base_target_prerequisites_supplier",
        ":build_driver_function",
        ":build_driver_key",
        ":build_driver_value",
        ":build_options_scope_function",
        ":build_result_listener",
        ":bzl_compile",
        ":bzl_compile_value",
        ":bzl_load_cycle_reporter",
        ":bzl_load_failed_exception",
        ":bzl_load_value",
        ":bzlmod_repo_cycle_reporter",
        ":cached_bzl_load_value_and_deps",
        ":cached_bzl_load_value_and_deps_builder_factory",
        ":client_environment_function",
        ":client_environment_value",
        ":collect_packages_under_directory_function",
        ":collect_packages_under_directory_value",
        ":collect_targets_in_package_function",
        ":configuration_phase_started_event",
        ":configured_target_and_data",
        ":configured_target_key",
        ":configured_value_creation_exception",
        ":conflict_checking_mode",
        ":consumed_artifacts_tracker",
        ":containing_package_lookup_function",
        ":containing_package_lookup_value",
        ":coverage_report_value",
        ":cycle_utils",
        ":detailed_exceptions",
        ":diff_awareness",
        ":diff_awareness_manager",
        ":directory_listing_function",
        ":directory_listing_state_value",
        ":directory_tree_digest_function",
        ":ephemeral_check_if_output_consumed",
        ":exclusive_test_build_driver_value",
        ":execution_finished_event",
        ":file_function",
        ":fileset_entry_function",
        ":filesystem_keys",
        ":filesystem_value_checker",
        ":glob_descriptor",
        ":glob_function",
        ":glob_value",
        ":globs_function",
        ":globs_value",
        ":ignored_subdirectories_function",
        ":ignored_subdirectories_value",
        ":incremental_artifact_conflict_finder",
        ":invalid_glob_pattern_exception",
        ":loading_phase_started_event",
        ":local_repository_lookup_value",
        ":map_as_package_roots",
        ":metadata_consumer_for_metrics",
        ":node_dropping_inconsistency_receiver",
        ":package_error_function",
        ":package_error_message_function",
        ":package_identifier_batching_callback",
        ":package_lookup_function",
        ":package_lookup_value",
        ":package_progress_receiver",
        ":package_roots_no_symlink_creation",
        ":package_value",
        ":pattern_expanding_error",
        ":precomputed_function",
        ":precomputed_value",
        ":prepare_deps_of_pattern_value",
        ":prepare_deps_of_patterns_function",
        ":prepare_deps_of_targets_under_directory_function",
        ":prepare_deps_of_targets_under_directory_value",
        ":prerequisite_package_function",
        ":progress_event_suppressing_environment",
        ":project_files_lookup_function",
        ":project_function",
        ":recursive_filesystem_traversal",
        ":recursive_package_provider_backed_target_pattern_resolver",
        ":recursive_pkg_function",
        ":recursive_pkg_value",
        ":repo_file_function",
        ":repo_package_args_function",
        ":repository_mapping_function",
        ":repository_mapping_value",
        ":rule_configured_target_value",
        ":sky_functions",
        ":sky_key_stats",
        ":sky_value_dirtiness_checker",
        ":skyfocus",
        ":skyfocus_options",
        ":skyfocus_state",
        ":skyframe_action_executor",
        ":skyframe_aware_action",
        ":skyframe_error_processor",
        ":skyframe_executor_repository_helpers_holder",
        ":skyframe_incremental_build_monitor",
        ":skyframe_stats",
        ":skyvalue_retriever_utils",
        ":starlark_builtins_value",
        ":starlark_util",
        ":state_informing_sky_function_environment",
        ":target_completion_value",
        ":target_cycle_reporter",
        ":target_loading_util",
        ":target_pattern_error_function",
        ":target_pattern_phase_value",
        ":target_pattern_value",
        ":target_patterns_result_builder",
        ":test_completion_value",
        ":test_expansion_cycle_reporter",
        ":test_expansion_function",
        ":tests_for_target_pattern_function",
        ":tests_for_target_pattern_value",
        ":top_level_action_lookup_key_wrapper",
        ":top_level_aspects_details_build_failed_exception",
        ":top_level_aspects_value",
        ":top_level_status_events",
        ":transitive_base_traversal_function",
        ":transitive_target_key",
        ":transitive_target_value",
        ":transitive_traversal_function",
        ":transitive_traversal_value",
        ":tree_artifact_value",
        ":workspace_info",
        ":workspace_info_receiver",
        ":workspace_name_function",
        ":workspace_name_value",
        ":workspace_status_function",
        ":workspace_status_value",
        "//src/main/java/com/google/devtools/build/lib:build-request-options",
        "//src/main/java/com/google/devtools/build/lib:keep-going-option",
        "//src/main/java/com/google/devtools/build/lib:runtime/memory_pressure",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:action_output_directory_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:analysis_graph_stats_event",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:commandline_item",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:important_output_handler",
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/actions:resource_manager",
        "//src/main/java/com/google/devtools/build/lib/actions:thread_state_receiver",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_operation_watcher",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_complete_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:aspect_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:aspect_configured_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/additional_configuration_change_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/common_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/config_conditions",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/execution_transition_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/invalid_configuration_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/options_diff",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/run_under",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/starlark_exec_transition_loader",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/starlark_transition_cache",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/configuration_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/patch_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/transition_collector",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/transition_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_object_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:constraints/incompatible_target_checker",
        "//src/main/java/com/google/devtools/build/lib/analysis:dependency_kind",
        "//src/main/java/com/google/devtools/build/lib/analysis:exec_group_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:incompatible_platform_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:inconsistent_aspect_order_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:inconsistent_null_config_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:invalid_visibility_dependency_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:required_config_fragments_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:resolved_toolchain_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:starlark/starlark_build_settings_details_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:starlark/starlark_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:target_and_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:target_configured_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:template_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/analysis_failure_propagation_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/coverage_artifacts_known_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/instrumented_files_info",
        "//src/main/java/com/google/devtools/build/lib/analysis:toolchain_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:toolchain_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:transitive_dependency_state",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform:platform_function",
        "//src/main/java/com/google/devtools/build/lib/analysis/producers",
        "//src/main/java/com/google/devtools/build/lib/analysis/producers:unloaded_toolchain_contexts_inputs",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:repo_rule_creator",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:repo_rule_value",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repository_options",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/causes",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/cmdline:query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashset",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset:artifact_nested_set_key",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_cycle_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:autoload_symbols",
        "//src/main/java/com/google/devtools/build/lib/packages:bzl_visibility",
        "//src/main/java/com/google/devtools/build/lib/packages:globber",
        "//src/main/java/com/google/devtools/build/lib/packages:globber_utils",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/profiler/memory:current_rule_tracker",
        "//src/main/java/com/google/devtools/build/lib/query2/common:QueryTransitivePackagePreloader",
        "//src/main/java/com/google/devtools/build/lib/query2/common:universe-scope",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/repository:external_package_helper",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/local_repository_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/resolved_file_value",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/workspace_file_helper",
        "//src/main/java/com/google/devtools/build/lib/rules/genquery:genquery_configuration",
        "//src/main/java/com/google/devtools/build/lib/rules/genquery:genquery_package_providers",
        "//src/main/java/com/google/devtools/build/lib/skyframe/actiongraph/v2:actiongraph_v2",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:exceptions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:platform_mapping_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/rewinding",
        "//src/main/java/com/google/devtools/build/lib/skyframe/rewinding:rewindable_graph_inconsistency_receiver",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:dependencies_provider",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:options",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:platform_lookup_util",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:registered_execution_platforms_cycle_reporter",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:registered_execution_platforms_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:registered_toolchains_cycle_reporter",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:registered_toolchains_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:single_toolchain_resolution_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:toolchain_context_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:toolchain_context_util",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:toolchain_exception",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:toolchain_resolution_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:unloaded_toolchain_context",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:heap_offset_helper",
        "//src/main/java/com/google/devtools/build/lib/util:resource_usage",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:output_service",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:memory_pressure_java_proto",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "detailed_exceptions",
    srcs = [
        "DetailedException.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "abstract_label_cycle_reporter",
    srcs = ["AbstractLabelCycleReporter.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "action_artifact_cycle_reporter",
    srcs = ["ActionArtifactCycleReporter.java"],
    deps = [
        ":abstract_label_cycle_reporter",
        ":aspect_completion_value",
        ":sky_functions",
        ":test_completion_value",
        ":top_level_action_lookup_key_wrapper",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "action_environment_function",
    srcs = ["ActionEnvironmentFunction.java"],
    deps = [
        ":client_environment_function",
        ":client_environment_value",
        ":precomputed_value",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_execution_inactivity_watchdog",
    srcs = ["ActionExecutionInactivityWatchdog.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//third_party:guava",
    ],
)

java_library(
    name = "action_execution_state",
    srcs = ["ActionExecutionState.java"],
    deps = [
        ":action_execution_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:shared_action_event",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_execution_value",
    srcs = ["ActionExecutionValue.java"],
    deps = [
        ":action_output_metadata_store",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp:cpp_interface",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_input_map_helper",
    srcs = ["ActionInputMapHelper.java"],
    deps = [
        ":action_execution_value",
        ":metadata_consumer_for_metrics",
        ":rule_configured_target_value",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/symlink_action",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_lookup_conflict_finding_value",
    srcs = ["ActionLookupConflictFindingValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "action_input_metadata_provider",
    srcs = ["ActionInputMetadataProvider.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_output_metadata_store",
    srcs = ["ActionOutputMetadataStore.java"],
    deps = [
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_template_expansion_function",
    srcs = ["ActionTemplateExpansionFunction.java"],
    deps = [
        ":action_template_expansion_value",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_template_expansion_value",
    srcs = ["ActionTemplateExpansionValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "action_utils",
    srcs = ["ActionUtils.java"],
    deps = [
        ":artifact_function",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "analysis_cache_clear_event",
    srcs = ["AnalysisCacheClearEvent.java"],
)

java_library(
    name = "artifact_conflict_finder",
    srcs = ["ArtifactConflictFinder.java"],
    deps = [
        ":precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
    ],
)

java_library(
    name = "aspect_completion_value",
    srcs = ["AspectCompletionValue.java"],
    deps = [
        ":aspect_key_creator",
        ":sky_functions",
        ":top_level_action_lookup_key_wrapper",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/build/skyframe:stallable_skykey",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "incremental_artifact_conflict_finder",
    srcs = ["IncrementalArtifactConflictFinder.java"],
    deps = [
        ":artifact_conflict_finder",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "source_directory_event",
    srcs = ["SourceDirectoryEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
    ],
)

java_library(
    name = "skyvalue_retriever_utils",
    srcs = ["SkyValueRetrieverUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:dependencies_provider",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:options",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "artifact_function",
    srcs = ["ArtifactFunction.java"],
    deps = [
        ":action_execution_value",
        ":action_template_expansion_value",
        ":coverage_report_value",
        ":detailed_exceptions",
        ":metadata_consumer_for_metrics",
        ":recursive_filesystem_traversal",
        ":skyvalue_retriever_utils",
        ":source_directory_event",
        ":track_source_directories_flag",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:dependencies_provider",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "artifact_nested_set_function",
    srcs = ["ArtifactNestedSetFunction.java"],
    deps = [
        ":artifact_function",
        ":artifact_nested_set_value",
        ":consumed_artifacts_tracker",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset:artifact_nested_set_key",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "artifact_nested_set_value",
    srcs = ["ArtifactNestedSetValue.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "aspect_creation_exception",
    srcs = ["AspectCreationException.java"],
    deps = [
        ":sane_analysis_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/causes",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "aspect_key_creator",
    srcs = ["AspectKeyCreator.java"],
    deps = [
        ":configured_target_key",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:label_printer",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "bzl_compile",
    srcs = ["BzlCompileFunction.java"],
    deps = [
        ":bzl_compile_value",
        ":precomputed_value",
        ":starlark_builtins_value",
        ":starlark_util",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:autoload_symbols",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "bzl_compile_value",
    srcs = ["BzlCompileValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "ignored_subdirectories_function",
    srcs = ["IgnoredSubdirectoriesFunction.java"],
    deps = [
        ":ignored_subdirectories_value",
        ":precomputed_value",
        ":repo_file_function",
        ":repo_file_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "ignored_subdirectories_value",
    srcs = ["IgnoredSubdirectoriesValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "broken_diff_awareness_exception",
    srcs = ["BrokenDiffAwarenessException.java"],
    deps = ["//third_party:guava"],
)

java_library(
    name = "builder",
    srcs = ["Builder.java"],
    deps = [
        ":aspect_key_creator",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "build_driver_function",
    srcs = ["BuildDriverFunction.java"],
    deps = [
        ":aspect_completion_value",
        ":aspect_key_creator",
        ":build_driver_key",
        ":build_driver_value",
        ":configured_target_key",
        ":exclusive_test_build_driver_value",
        ":incremental_artifact_conflict_finder",
        ":package_value",
        ":precomputed_value",
        ":sane_analysis_exception",
        ":target_completion_value",
        ":test_completion_value",
        ":top_level_aspects_value",
        ":top_level_conflict_exception",
        ":top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:aspect_configured_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:constraints/top_level_constraint_semantics",
        "//src/main/java/com/google/devtools/build/lib/analysis:extra_action_artifacts_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:target_configured_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "build_driver_key",
    srcs = ["BuildDriverKey.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "build_driver_value",
    srcs = [
        "BuildDriverValue.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "build_result_listener",
    srcs = ["BuildResultListener.java"],
    deps = [
        ":aspect_key_creator",
        ":configured_target_key",
        ":top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
    ],
)

java_library(
    name = "top_level_aspects_details_build_failed_exception",
    srcs = [
        "TopLevelAspectsDetailsBuildFailedException.java",
    ],
    deps = [
        ":sane_analysis_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
    ],
)

java_library(
    name = "cached_bzl_load_value_and_deps",
    srcs = ["CachedBzlLoadData.java"],
    deps = [
        ":bzl_load_value",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "cached_bzl_load_value_and_deps_builder_factory",
    srcs = ["CachedBzlLoadDataBuilderFactory.java"],
    deps = [
        ":cached_bzl_load_value_and_deps",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
    ],
)

java_library(
    name = "client_environment_function",
    srcs = ["ClientEnvironmentFunction.java"],
    deps = [
        ":client_environment_value",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "client_environment_value",
    srcs = ["ClientEnvironmentValue.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "collect_packages_under_directory_function",
    srcs = ["CollectPackagesUnderDirectoryFunction.java"],
    deps = [
        "process_package_directory",
        ":collect_packages_under_directory_value",
        ":recursive_directory_traversal_function",
        ":recursive_pkg_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "collect_packages_under_directory_value",
    srcs = ["CollectPackagesUnderDirectoryValue.java"],
    deps = [
        ":recursive_pkg_key",
        ":recursive_pkg_sky_key",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "collect_targets_in_package_function",
    srcs = ["CollectTargetsInPackageFunction.java"],
    deps = [
        ":collect_targets_in_package_value",
        ":package_value",
        ":transitive_traversal_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "collect_targets_in_package_value",
    srcs = ["CollectTargetsInPackageValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
    ],
)

java_library(
    name = "configuration_phase_started_event",
    srcs = ["ConfigurationPhaseStartedEvent.java"],
    deps = [
        ":analysis_progress_receiver",
        "//src/main/java/com/google/devtools/build/lib/events",
    ],
)

java_library(
    name = "configured_target_and_data",
    srcs = ["ConfiguredTargetAndData.java"],
    deps = [
        ":package_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages:configured_attribute_mapper",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "configured_target_key",
    srcs = ["ConfiguredTargetKey.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "analysis_progress_receiver",
    srcs = ["AnalysisProgressReceiver.java"],
)

java_library(
    name = "configured_value_creation_exception",
    srcs = ["ConfiguredValueCreationException.java"],
    deps = [
        ":sane_analysis_exception",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/causes",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/net/starlark/java/syntax",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "conflict_checking_mode",
    srcs = ["ConflictCheckingMode.java"],
)

java_library(
    name = "consumed_artifacts_tracker",
    srcs = ["ConsumedArtifactsTracker.java"],
    deps = [
        ":ephemeral_check_if_output_consumed",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//third_party:guava",
    ],
)

java_library(
    name = "containing_package_lookup_function",
    srcs = ["ContainingPackageLookupFunction.java"],
    deps = [
        ":containing_package_lookup_value",
        ":package_lookup_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "containing_package_lookup_value",
    srcs = ["ContainingPackageLookupValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "build_options_scope_function",
    srcs = ["BuildOptionsScopeFunction.java"],
    deps = [
        ":build_options_scope_value",
        ":package_value",
        ":precomputed_value",
        ":project_files_lookup_value",
        ":project_value",
        "//src/main/java/com/google/devtools/build/lib:starlark_options_parser",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/scope",
        "//src/main/java/com/google/devtools/build/lib/analysis:project_resolution_exception",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "build_options_scope_value",
    srcs = ["BuildOptionsScopeValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/scope",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent:thread_safety",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "project_function",
    srcs = ["ProjectFunction.java"],
    deps = [
        ":bzl_load_failed_exception",
        ":bzl_load_value",
        ":project_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "project_value",
    srcs = ["ProjectValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "project_files_lookup_function",
    srcs = ["ProjectFilesLookupFunction.java"],
    deps = [
        ":containing_package_lookup_value",
        ":project_files_lookup_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "project_files_lookup_value",
    srcs = ["ProjectFilesLookupValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "coverage_report_value",
    srcs = ["CoverageReportValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "cycle_utils",
    srcs = ["CycleUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//third_party:guava",
    ],
)

java_library(
    name = "diff_awareness",
    srcs = ["DiffAwareness.java"],
    deps = [
        ":broken_diff_awareness_exception",
        ":incompatible_view_exception",
        ":workspace_info",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "diff_awareness_manager",
    srcs = ["DiffAwarenessManager.java"],
    deps = [
        ":broken_diff_awareness_exception",
        ":diff_awareness",
        ":incompatible_view_exception",
        ":workspace_info",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "directory_listing_function",
    srcs = ["DirectoryListingFunction.java"],
    deps = [
        ":directory_listing_state_value",
        ":directory_listing_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "directory_listing_state_value",
    srcs = ["DirectoryListingStateValue.java"],
    deps = [
        ":dirents",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "directory_listing_value",
    srcs = ["DirectoryListingValue.java"],
    deps = [
        ":directory_listing_state_value",
        ":dirents",
        ":filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "directory_tree_digest_function",
    srcs = ["DirectoryTreeDigestFunction.java"],
    deps = [
        ":directory_listing_value",
        ":directory_tree_digest_value",
        ":filesystem_keys",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "directory_tree_digest_value",
    srcs = ["DirectoryTreeDigestValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "dirents",
    srcs = ["Dirents.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "ephemeral_check_if_output_consumed",
    srcs = ["EphemeralCheckIfOutputConsumed.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/actions:artifacts"],
)

java_library(
    name = "exclusive_test_build_driver_value",
    srcs = ["ExclusiveTestBuildDriverValue.java"],
    deps = [
        ":build_driver_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "execution_finished_event",
    srcs = ["ExecutionFinishedEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "file_function",
    srcs = ["FileFunction.java"],
    deps = [
        ":cycle_utils",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_cycle_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_cycle_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "fileset_entry_function",
    srcs = ["FilesetEntryFunction.java"],
    deps = [
        ":fileset_entry_key",
        ":fileset_entry_value",
        ":fileset_traversal_request",
        ":recursive_filesystem_traversal",
        ":workspace_name_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_symlink",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "fileset_traversal_request",
    srcs = ["FilesetTraversalRequest.java"],
    deps = [
        ":recursive_filesystem_traversal",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
    ],
)

java_library(
    name = "fileset_entry_key",
    srcs = ["FilesetEntryKey.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "fileset_entry_value",
    srcs = ["FilesetEntryValue.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_symlink",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "filesystem_keys",
    srcs = [
        "AbstractNestedFileOpNodes.java",
        "DirectoryListingKey.java",
        "FileKey.java",
        "FileOpNodeOrFuture.java",
    ],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/concurrent:settable_future_keyed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "filesystem_value_checker",
    srcs = [
        "FileSystemValueCheckerInferringAncestors.java",
        "FilesystemValueChecker.java",
    ],
    deps = [
        ":action_execution_value",
        ":action_output_metadata_store",
        ":directory_listing_state_value",
        ":sky_functions",
        ":sky_value_dirtiness_checker",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "glob_descriptor",
    srcs = ["GlobDescriptor.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages:globber",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "glob_exception",
    srcs = ["GlobException.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/packages/producers",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "glob_function",
    srcs = [
        "GlobFunction.java",
        "GlobFunctionWithMultipleRecursiveFunctions.java",
        "GlobFunctionWithRecursionInSingleFunction.java",
    ],
    deps = [
        ":directory_listing_value",
        ":glob_descriptor",
        ":glob_exception",
        ":glob_value",
        ":ignored_subdirectories_value",
        ":package_lookup_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/packages:globber",
        "//src/main/java/com/google/devtools/build/lib/packages/producers",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "globs_function",
    srcs = ["GlobsFunction.java"],
    deps = [
        ":glob_descriptor",
        ":glob_exception",
        ":globs_value",
        ":ignored_subdirectories_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages/producers",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "glob_value",
    srcs = [
        "GlobValue.java",
        "GlobValueWithImmutableSet.java",
        "GlobValueWithNestedSet.java",
    ],
    deps = [
        ":glob_descriptor",
        ":invalid_glob_pattern_exception",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages:globber",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "globs_value",
    srcs = ["GlobsValue.java"],
    deps = [
        ":invalid_glob_pattern_exception",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages:globber",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "graph_backed_recursive_package_provider",
    srcs = ["GraphBackedRecursivePackageProvider.java"],
    deps = [
        ":package_lookup_value",
        ":package_value",
        ":root_package_extractor",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "incompatible_view_exception",
    srcs = ["IncompatibleViewException.java"],
    deps = ["//third_party:guava"],
)

java_library(
    name = "incremental_package_roots",
    srcs = ["IncrementalPackageRoots.java"],
    deps = [
        ":top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib:buildtool/symlink_forest",
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_complete_event",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "interner_with_presence_check",
    srcs = ["InternerWithPresenceCheck.java"],
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "invalid_glob_pattern_exception",
    srcs = ["InvalidGlobPatternException.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
    ],
)

java_library(
    name = "loading_phase_started_event",
    srcs = ["LoadingPhaseStartedEvent.java"],
    deps = [
        ":package_progress_receiver",
        "//src/main/java/com/google/devtools/build/lib/events",
    ],
)

java_library(
    name = "local_diff_awareness",
    srcs = [
        "LocalDiffAwareness.java",
        "MacOSXFsEventsDiffAwareness.java",
        "WatchServiceDiffAwareness.java",
    ],
    deps = [
        ":broken_diff_awareness_exception",
        ":diff_awareness",
        ":incompatible_view_exception",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/jni",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "local_repository_lookup_value",
    srcs = ["LocalRepositoryLookupValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "map_as_package_roots",
    srcs = ["MapAsPackageRoots.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
    ],
)

java_library(
    name = "metadata_consumer_for_metrics",
    srcs = ["MetadataConsumerForMetrics.java"],
    deps = [
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
    ],
)

java_library(
    name = "mutable_supplier",
    srcs = ["MutableSupplier.java"],
    deps = ["//third_party:guava"],
)

java_library(
    name = "package_error_function",
    srcs = ["PackageErrorFunction.java"],
    deps = [
        ":package_value",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "package_error_message_function",
    srcs = ["PackageErrorMessageFunction.java"],
    deps = [
        ":package_error_message_value",
        ":package_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "package_error_message_value",
    srcs = ["PackageErrorMessageValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "package_identifier_batching_callback",
    srcs = [
        "PackageIdentifierBatchingCallback.java",
        "SimplePackageIdentifierBatchingCallback.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "package_lookup_function",
    srcs = ["PackageLookupFunction.java"],
    deps = [
        ":already_reported_exception",
        ":ignored_subdirectories_value",
        ":local_repository_lookup_value",
        ":package_lookup_value",
        ":precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:LabelValidator",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/repository:external_package_helper",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "package_lookup_value",
    srcs = ["PackageLookupValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "package_progress_receiver",
    srcs = ["PackageProgressReceiver.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//third_party:guava",
    ],
)

java_library(
    name = "package_roots_no_symlink_creation",
    srcs = ["PackageRootsNoSymlinkCreation.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
    ],
)

java_library(
    name = "package_value",
    srcs = ["PackageValue.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "pattern_expanding_error",
    srcs = ["PatternExpandingError.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "default_syscall_cache",
    srcs = ["DefaultSyscallCache.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "precomputed_function",
    srcs = ["PrecomputedFunction.java"],
    deps = ["//src/main/java/com/google/devtools/build/skyframe:skyframe-objects"],
)

java_library(
    name = "precomputed_value",
    srcs = [
        "PrecomputedValue.java",
    ],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "prepare_deps_of_pattern_value",
    srcs = ["PrepareDepsOfPatternValue.java"],
    deps = [
        ":sky_functions",
        ":target_pattern_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "prepare_deps_of_patterns_function",
    srcs = ["PrepareDepsOfPatternsFunction.java"],
    deps = [
        ":prepare_deps_of_pattern_value",
        ":prepare_deps_of_patterns_value",
        ":repository_mapping_value",
        ":target_pattern_value",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "prepare_deps_of_patterns_value",
    srcs = ["PrepareDepsOfPatternsValue.java"],
    deps = [
        ":sky_functions",
        ":target_pattern_value",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/query2/common:UniverseSkyKey",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "prepare_deps_of_targets_under_directory_function",
    srcs = ["PrepareDepsOfTargetsUnderDirectoryFunction.java"],
    deps = [
        ":collect_targets_in_package_value",
        ":prepare_deps_of_targets_under_directory_value",
        ":process_package_directory",
        ":process_package_directory_result",
        ":recursive_pkg_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "prepare_deps_of_targets_under_directory_value",
    srcs = ["PrepareDepsOfTargetsUnderDirectoryValue.java"],
    deps = [
        ":recursive_pkg_key",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "prerequisite_package_function",
    srcs = ["PrerequisitePackageFunction.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "base_target_prerequisites_supplier",
    srcs = ["BaseTargetPrerequisitesSupplier.java"],
    deps = [
        ":configured_target_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:toolchain_context_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:unloaded_toolchain_context",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "process_package_directory",
    srcs = ["ProcessPackageDirectory.java"],
    deps = [
        ":directory_listing_value",
        ":dirents",
        ":package_lookup_value",
        ":precomputed_value",
        ":process_package_directory_result",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "process_package_directory_result",
    srcs = ["ProcessPackageDirectoryResult.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "progress_event_suppressing_environment",
    srcs = ["ProgressEventSuppressingEnvironment.java"],
    deps = [
        ":progress_suppressing_event_handler",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "progress_suppressing_event_handler",
    srcs = ["ProgressSuppressingEventHandler.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/events"],
)

java_library(
    name = "recursive_directory_traversal_function",
    srcs = ["RecursiveDirectoryTraversalFunction.java"],
    deps = [
        ":package_error_message_value",
        ":process_package_directory",
        ":process_package_directory_result",
        ":recursive_pkg_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "recursive_filesystem_traversal",
    srcs = [
        "RecursiveFilesystemTraversalFunction.java",
        "RecursiveFilesystemTraversalValue.java",
        "TraversalRequest.java",
    ],
    deps = [
        ":action_execution_value",
        ":detailed_exceptions",
        ":directory_listing_value",
        ":dirents",
        ":filesystem_keys",
        ":package_lookup_value",
        ":sky_functions",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/io:file_symlink_infinite_expansion_uniqueness_function",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "recursive_package_provider_backed_target_pattern_resolver",
    srcs = ["RecursivePackageProviderBackedTargetPatternResolver.java"],
    deps = [
        ":package_identifier_batching_callback",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/cmdline:query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "recursive_pkg_function",
    srcs = ["RecursivePkgFunction.java"],
    deps = [
        ":process_package_directory",
        ":recursive_directory_traversal_function",
        ":recursive_pkg_key",
        ":recursive_pkg_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "recursive_pkg_key",
    srcs = ["RecursivePkgKey.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
    ],
)

java_library(
    name = "recursive_pkg_sky_key",
    srcs = ["RecursivePkgSkyKey.java"],
    deps = [
        ":recursive_pkg_key",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "recursive_pkg_value",
    srcs = ["RecursivePkgValue.java"],
    deps = [
        ":recursive_pkg_key",
        ":recursive_pkg_sky_key",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "recursive_pkg_value_root_package_extractor",
    srcs = ["RecursivePkgValueRootPackageExtractor.java"],
    deps = [
        ":recursive_pkg_value",
        ":root_package_extractor",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "repo_package_args_function",
    srcs = ["RepoPackageArgsFunction.java"],
    deps = [
        ":repo_file_function",
        ":repo_file_value",
        ":repository_mapping_value",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "repo_file_function",
    srcs = ["RepoFileFunction.java"],
    deps = [
        ":precomputed_value",
        ":repo_file_value",
        ":starlark_util",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "repo_file_value",
    srcs = ["RepoFileValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "repository_mapping_function",
    srcs = ["RepositoryMappingFunction.java"],
    deps = [
        ":package_value",
        ":precomputed_value",
        ":repository_mapping_value",
        ":starlark_builtins_value",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "repository_mapping_value",
    srcs = ["RepositoryMappingValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "root_package_extractor",
    srcs = ["RootPackageExtractor.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//third_party:guava",
    ],
)

java_library(
    name = "sane_analysis_exception",
    srcs = [
        "AbstractSaneAnalysisException.java",
        "SaneAnalysisException.java",
    ],
    deps = [
        ":detailed_exceptions",
    ],
)

java_library(
    name = "sky_functions",
    srcs = ["SkyFunctions.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "sky_value_dirtiness_checker",
    srcs = ["SkyValueDirtinessChecker.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "skyframe_action_executor",
    srcs = ["SkyframeActionExecutor.java"],
    deps = [
        ":action_execution_state",
        ":action_execution_value",
        ":action_output_metadata_store",
        ":metadata_consumer_for_metrics",
        ":progress_suppressing_event_handler",
        ":skyframe_aware_action",
        ":track_source_directories_flag",
        ":tree_artifact_value",
        "//src/main/java/com/google/devtools/build/lib:build-request-options",
        "//src/main/java/com/google/devtools/build/lib:keep-going-option",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_output_directory_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/actions:thread_state_receiver",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/util:crash_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:resource_usage",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:output_service",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "skyframe_aware_action",
    srcs = ["SkyframeAwareAction.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "skyframe_error_processor",
    srcs = ["SkyframeErrorProcessor.java"],
    deps = [
        ":action_artifact_cycle_reporter",
        ":artifact_nested_set_function",
        ":aspect_completion_value",
        ":aspect_key_creator",
        ":build_driver_key",
        ":configured_target_key",
        ":configured_value_creation_exception",
        ":detailed_exceptions",
        ":sane_analysis_exception",
        ":sky_functions",
        ":target_completion_value",
        ":test_completion_value",
        ":top_level_conflict_exception",
        ":transitive_target_key",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:constraints/top_level_constraint_semantics",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/causes",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "skyframe_executor_repository_helpers_holder",
    srcs = ["SkyframeExecutorRepositoryHelpersHolder.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function"],
)

java_library(
    name = "skyframe_incremental_build_monitor",
    srcs = ["SkyframeIncrementalBuildMonitor.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "skymeld_module",
    srcs = ["SkymeldModule.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:build-request-options",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//third_party:flogger",
    ],
)

java_library(
    name = "skyfocus_options",
    srcs = ["SkyfocusOptions.java"],
    deps = ["//src/main/java/com/google/devtools/common/options"],
)

java_library(
    name = "skyfocus_state",
    srcs = [
        "SkyfocusState.java",
    ],
    deps = [
        ":skyfocus_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "skyfocus",
    srcs = [
        "SkyfocusExecutor.java",
        "SkyframeFocuser.java",
    ],
    deps = [
        ":directory_listing_state_value",
        ":precomputed_value",
        ":skyfocus_state",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset:artifact_nested_set_key",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "bzl_load_cycle_reporter",
    srcs = ["BzlLoadCycleReporter.java"],
    deps = [
        ":abstract_label_cycle_reporter",
        ":bzl_load_value",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/repository:request_repository_information_event",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "bzlmod_repo_cycle_reporter",
    srcs = ["BzlmodRepoCycleReporter.java"],
    deps = [
        ":abstract_label_cycle_reporter",
        ":bzl_load_value",
        ":repository_mapping_value",
        ":sky_functions",
        ":starlark_builtins_value",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:repo_rule_value",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/repository:request_repository_information_event",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "starlark_builtins_value",
    srcs = ["StarlarkBuiltinsValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
    ],
)

java_library(
    name = "bzl_load_value",
    srcs = ["BzlLoadValue.java"],
    deps = [
        ":bzl_compile_value",
        ":sky_functions",
        ":starlark_builtins_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages:bzl_visibility",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "bzl_load_failed_exception",
    srcs = ["BzlLoadFailedException.java"],
    deps = [
        ":sane_analysis_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/protobuf:failure_details_java_proto",
    ],
)

java_library(
    name = "state_informing_sky_function_environment",
    srcs = ["StateInformingSkyFunctionEnvironment.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_excluding_filtering_policy",
    srcs = ["TargetExcludingFilteringPolicy.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//third_party:guava",
    ],
)

java_library(
    name = "target_pattern_error_function",
    srcs = ["TargetPatternErrorFunction.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_pattern_phase_value",
    srcs = ["TargetPatternPhaseValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_pattern_util",
    srcs = ["TargetPatternUtil.java"],
    deps = [
        ":target_pattern_value",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_pattern_value",
    srcs = ["TargetPatternValue.java"],
    deps = [
        ":sky_functions",
        ":target_excluding_filtering_policy",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "target_patterns_result_builder",
    srcs = ["TargetPatternsResultBuilder.java"],
    deps = [
        ":package_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashset",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_completion_value",
    srcs = ["TestCompletionValue.java"],
    deps = [
        ":configured_target_key",
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_expansion_cycle_reporter",
    srcs = ["TestExpansionCycleReporter.java"],
    deps = [
        ":abstract_label_cycle_reporter",
        ":test_expansion_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "test_expansion_function",
    srcs = ["TestExpansionFunction.java"],
    deps = [
        ":package_error_function",
        ":package_value",
        ":test_expansion_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "test_expansion_value",
    srcs = ["TestExpansionValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "tests_for_target_pattern_function",
    srcs = ["TestsForTargetPatternFunction.java"],
    deps = [
        ":package_value",
        ":test_expansion_value",
        ":tests_for_target_pattern_value",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "tests_for_target_pattern_value",
    srcs = ["TestsForTargetPatternValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "top_level_action_lookup_key_wrapper",
    srcs = ["TopLevelActionLookupKeyWrapper.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/skyframe:execution_phase_skykey",
    ],
)

java_library(
    name = "top_level_aspects_value",
    srcs = ["TopLevelAspectsValue.java"],
    deps = [
        ":aspect_key_creator",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//third_party:guava",
    ],
)

java_library(
    name = "top_level_conflict_exception",
    srcs = ["TopLevelConflictException.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//third_party:guava",
    ],
)

java_library(
    name = "track_source_directories_flag",
    srcs = ["TrackSourceDirectoriesFlag.java"],
)

java_library(
    name = "transitive_base_traversal_function",
    srcs = ["TransitiveBaseTraversalFunction.java"],
    deps = [
        ":target_loading_util",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_loading_util",
    srcs = ["TargetLoadingUtil.java"],
    deps = [
        ":containing_package_lookup_value",
        ":package_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "target_completion_value",
    srcs = ["TargetCompletionValue.java"],
    deps = [
        ":configured_target_key",
        ":sky_functions",
        ":top_level_action_lookup_key_wrapper",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/build/skyframe:stallable_skykey",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "target_cycle_reporter",
    srcs = ["TargetCycleReporter.java"],
    deps = [
        ":abstract_label_cycle_reporter",
        ":aspect_key_creator",
        ":configured_target_key",
        ":sky_functions",
        ":transitive_target_key",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "top_level_status_events",
    srcs = ["TopLevelStatusEvents.java"],
    deps = [
        ":aspect_key_creator",
        ":configured_target_key",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
    ],
)

java_library(
    name = "transitive_target_key",
    srcs = ["TransitiveTargetKey.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "transitive_target_value",
    srcs = ["TransitiveTargetValue.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "transitive_traversal_function",
    srcs = ["TransitiveTraversalFunction.java"],
    deps = [
        ":sky_functions",
        ":target_loading_util",
        ":transitive_base_traversal_function",
        ":transitive_traversal_value",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "transitive_traversal_value",
    srcs = ["TransitiveTraversalValue.java"],
    deps = [
        ":interner_with_presence_check",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "traversal_info_root_package_extractor",
    srcs = ["TraversalInfoRootPackageExtractor.java"],
    deps = [
        ":collect_packages_under_directory_value",
        ":recursive_package_provider_backed_target_pattern_resolver",
        ":root_package_extractor",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:batch_callback",
        "//src/main/java/com/google/devtools/build/lib/cmdline:parallel_visitor",
        "//src/main/java/com/google/devtools/build/lib/cmdline:query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "tree_artifact_value",
    srcs = [
        "TreeArtifactValue.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:has_digest",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "workspace_info",
    srcs = ["WorkspaceInfoFromDiff.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:client_id",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
    ],
)

java_library(
    name = "workspace_info_receiver",
    srcs = ["WorkspaceInfoFromDiffReceiver.java"],
    deps = [
        ":workspace_info",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "workspace_name_function",
    srcs = ["WorkspaceNameFunction.java"],
    deps = [
        ":package_value",
        ":precomputed_value",
        ":workspace_name_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "workspace_name_value",
    srcs = ["WorkspaceNameValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "workspace_status_function",
    srcs = ["WorkspaceStatusFunction.java"],
    deps = [
        ":workspace_name_value",
        ":workspace_status_value",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "workspace_status_value",
    srcs = ["WorkspaceStatusValue.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_key",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "already_reported_exception",
    srcs = ["AlreadyReportedException.java"],
)

java_library(
    name = "rule_configured_target_value",
    srcs = [
        "AbstractConfiguredTargetValue.java",
        "NonRuleConfiguredTargetValue.java",
        "RemoteConfiguredTargetValue.java",
        "RuleConfiguredTargetValue.java",
    ],
    deps = [
        ":prerequisite_package_function",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:rule_configured_object_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "action_input_collected_event",
    srcs = ["ActionInputCollectedEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
    ],
)

java_library(
    name = "node_dropping_inconsistency_receiver",
    srcs = ["NodeDroppingInconsistencyReceiver.java"],
    deps = [
        ":sky_functions",
        "//src/main/java/com/google/devtools/build/lib/rules/genquery:genquery_package_providers",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:graph_inconsistency_java_proto",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "sky_key_stats",
    srcs = ["SkyKeyStats.java"],
)

java_library(
    name = "skyframe_stats",
    srcs = ["SkyframeStats.java"],
    deps = [
        ":sky_key_stats",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
    ],
)

java_library(
    name = "starlark_util",
    srcs = ["StarlarkUtil.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
    ],
)
