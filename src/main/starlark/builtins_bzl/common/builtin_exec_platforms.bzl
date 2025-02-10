# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
""" Default implementation of Bazel's exec transition ('cfg = "exec"').

See https://github.com/bazelbuild/bazel/discussions/19213.
"""

# TODO: Remove when get_current_os_name is no longer needed
load("@_builtins//:common/python/py_internal.bzl", "py_internal")

# The fragments that make up Bazel's exec transition. The fragment() calls in
# this file fill out this map.
bazel_fragments = {}

def fragment(propagate = [], inputs = [], outputs = [], func = lambda setting: {}):
    """Adds exec transition logic for a group of related flags.

    Args:
      propagate: Flags to propagate as-is from the input configuration.
      inputs: Flags to specially read for custom transit logic.
      outputs: Flags to specially write with custom transition logic.
      func: Custom transition logic for flags we don't auto-propagate.

    Returns:
      The fragment.
    """
    return struct(
        inputs = inputs + propagate,
        outputs = outputs + propagate,
        propagate = propagate,
        custom_logic = func,
    )

def exec_transition(fragments):
    """Returns the data for creating an exec transition from a set of fragments.

    Ideally this would create and return the transition itself. Instead, callers
    have to wrap their own transition() call around the data returned here.

    This is because Bazel checks if a transition is the exec transition, and if
    so applies different implementation logic vs. normal transitions. The check,
    at https://github.com/bazelbuild/bazel/blob/c8afa82026977e84d1c89f4f0ae3503ef7720a25/src/main/java/com/google/devtools/build/lib/analysis/config/StarlarkDefinedConfigTransition.java#L128-L130,
    compares the value of `--experimental_exec_config` with the .bzl file that
    declared the transition.

    If another .bzl file wraps this logic for its own transition, it must
    declare its own transition() call so that check matches (so Bazel sees the
    owner of that transition is the other bzl file, not this one).

    Args:
      fragments: Fragments that make up the transition, as a map from name to
      data. Data comes from the fragment() function.

    Returns:
      The data needd to declare an exec transition for those fragments.
    """
    inputs_and_outputs = _get_inputs_and_outputs(fragments)
    return struct(
        implementation = _exec_transition_impl(fragments),
        inputs = inputs_and_outputs.inputs,
        outputs = inputs_and_outputs.outputs,
    )

def _get_inputs_and_outputs(fragments):
    """Returns the (inputs, outputs) for a collection of fragments.
    """
    inputs = []
    outputs = ["//command_line_option:experimental_action_listener"]
    for fragment in fragments.values():
        inputs.extend(fragment.inputs)
        outputs.extend(fragment.outputs)
    return struct(inputs = inputs, outputs = outputs)

def _exec_transition_impl(fragments):
    """Returns an exec transition impl function from a set of fragments.

    Args:
      fragments: Fragments that make up the transition, as a map from name to
      data. Data comes from the fragment() function.
    """

    # buildifier: disable=unused-variable
    def _impl(settings, attr):
        ans = {}
        for fragment in fragments.values():
            for option in fragment.propagate:
                ans[option] = settings[option]
            ans.update(fragment.custom_logic(settings))

        # Building with --experimental_action_listener fails.
        # TODO(b/301654253): clarify what to do with this flag.
        ans["//command_line_option:experimental_action_listener"] = []
        return ans

    return _impl

################################ Fragment definitions: #########################
#
# Fragments encapsulate thematically related flags (like all flags that
# configure Java rules). Fragments are not a native Bazel concept - they're pure
# Starlark collections.

bazel_fragments["AndroidConfiguration.Options"] = fragment(
    propagate = [
        "//command_line_option:desugar_for_android",
        "//command_line_option:desugar_java8_libs",
        "//command_line_option:experimental_check_desugar_deps",
        "//command_line_option:incremental_dexing",
        "//command_line_option:experimental_incremental_dexing_after_proguard",
        "//command_line_option:experimental_use_dex_splitter_for_incremental_dexing",
        "//command_line_option:experimental_incremental_dexing_after_proguard_by_default",
        "//command_line_option:experimental_android_assume_minsdkversion",
        "//command_line_option:non_incremental_per_target_dexopts",
        "//command_line_option:dexopts_supported_in_incremental_dexing",
        "//command_line_option:dexopts_supported_in_dexmerger",
        "//command_line_option:dexopts_supported_in_dexsharder",
        "//command_line_option:android_manifest_merger",
        "//command_line_option:android_manifest_merger_order",
        "//command_line_option:experimental_allow_android_library_deps_without_srcs",
        "//command_line_option:experimental_one_version_enforcement_use_transitive_jars_for_binary_under_test",
        "//command_line_option:internal_persistent_busybox_tools",
        "//command_line_option:internal_persistent_multiplex_busybox_tools",
        "//command_line_option:incompatible_disable_native_android_rules",
        "//command_line_option:android_databinding_use_androidx",
        "//command_line_option:android_databinding_use_v3_4_args",
        "//command_line_option:break_build_on_parallel_dex2oat_failure",
        "//command_line_option:experimental_always_filter_duplicate_classes_from_android_test",
        "//command_line_option:experimental_android_compress_java_resources",
        "//command_line_option:experimental_android_databinding_v2",
        "//command_line_option:experimental_android_library_exports_manifest_default",
        "//command_line_option:experimental_android_resource_cycle_shrinking",
        "//command_line_option:experimental_android_resource_name_obfuscation",
        "//command_line_option:experimental_android_resource_path_shortening",
        "//command_line_option:experimental_android_resource_shrinking",
        "//command_line_option:experimental_android_rewrite_dexes_with_rex",
        "//command_line_option:experimental_android_use_parallel_dex2oat",
        "//command_line_option:experimental_disable_instrumentation_manifest_merge",
        "//command_line_option:experimental_filter_library_jar_with_program_jar",
        "//command_line_option:experimental_filter_r_jars_from_android_test",
        "//command_line_option:experimental_get_android_java_resources_from_optimized_jar",
        "//command_line_option:experimental_omit_resources_info_provider_from_android_binary",
        "//command_line_option:experimental_persistent_aar_extractor",
        "//command_line_option:experimental_remove_r_classes_from_instrumentation_test_jar",
        "//command_line_option:experimental_use_rtxt_from_merged_resources",
        "//command_line_option:experimental_objc_provider_from_linked",
    ],
    outputs = [
        "//command_line_option:Android configuration distinguisher",
    ],
    func = lambda settings: {
        "//command_line_option:Android configuration distinguisher": "main",
    },
)

# AndroidLocalTestConfiguration$Options: no exec configs

bazel_fragments["AppleCommandLineOptions"] = fragment(
    propagate = [
        "//command_line_option:xcode_version_config",
        "//command_line_option:xcode_version",
        "//command_line_option:ios_sdk_version",
        "//command_line_option:watchos_sdk_version",
        "//command_line_option:tvos_sdk_version",
        "//command_line_option:macos_sdk_version",
        "//command_line_option:host_macos_minimum_os",
        "//command_line_option:experimental_prefer_mutual_xcode",
        "//command_line_option:experimental_include_xcode_execution_requirements",
        "//command_line_option:apple_crosstool_top",
        "//command_line_option:incompatible_enable_apple_toolchain_resolution",
    ],
    outputs = [
        "//command_line_option:macos_minimum_os",
        "//command_line_option:apple_platform_type",
        "//command_line_option:apple configuration distinguisher",
    ],
    func = lambda settings: {
        "//command_line_option:macos_minimum_os": settings["//command_line_option:host_macos_minimum_os"],
        "//command_line_option:apple_platform_type": "macos",
        "//command_line_option:apple configuration distinguisher": "unknown",
    },
)

bazel_fragments["BazelConfigurarion$Options"] = fragment(
    propagate = [
        "//command_line_option:incompatible_check_visibility_for_toolchains",
    ],
)

bazel_fragments["BazelPythonConfiguration$Options"] = fragment(
    propagate = [
        "//command_line_option:python_top",
        "//command_line_option:python_path",
        "//command_line_option:experimental_python_import_all_repositories",
    ],
)

bazel_fragments["BazelRuleClassProvider$StrictActionEnvOptions"] = fragment(
    propagate = [
        "//command_line_option:incompatible_strict_action_env",
    ],
)

bazel_fragments["ConfigFeatureFlagOptions"] = fragment(
    propagate = [
        "//command_line_option:all feature flag values are present (internal)",
    ],
    outputs = [
        "//command_line_option:enforce_transitive_configs_for_config_feature_flag",
    ],
    func = lambda settings: {
        "//command_line_option:enforce_transitive_configs_for_config_feature_flag": False,
    },
)

def _core_options(settings):
    return {
        "//command_line_option:compilation_mode": settings["//command_line_option:host_compilation_mode"],
        "//command_line_option:is exec configuration": True,
        "//command_line_option:cpu": settings["//command_line_option:host_cpu"],
        "//command_line_option:stamp": False,
        "//command_line_option:action_env": settings["//command_line_option:host_action_env"],
        "//command_line_option:features": settings["//command_line_option:host_features"],
    }

bazel_fragments["CoreOptions"] = fragment(
    propagate = [
        "//command_line_option:experimental_output_directory_naming_scheme",
        "//command_line_option:host_compilation_mode",
        "//command_line_option:experimental_exec_configuration_distinguisher",
        "//command_line_option:experimental_output_paths",
        "//command_line_option:enable_runfiles",
        "//command_line_option:enforce_constraints",
        "//command_line_option:incompatible_merge_genfiles_directory",
        "//command_line_option:experimental_platform_in_output_dir",
        "//command_line_option:host_cpu",
        "//command_line_option:incompatible_modify_execution_info_additive",
        "//command_line_option:include_config_fragments_provider",
        "//command_line_option:experimental_debug_selects_always_succeed",
        "//command_line_option:incompatible_check_testonly_for_output_files",
        "//command_line_option:incompatible_auto_exec_groups",
        "//command_line_option:incompatible_bazel_test_exec_run_under",
        "//command_line_option:experimental_writable_outputs",
        "//command_line_option:build_runfile_manifests",
        "//command_line_option:build_runfile_links",
        "//command_line_option:legacy_external_runfiles",
        "//command_line_option:experimental_remotable_source_manifests",
        "//command_line_option:incompatible_always_include_files_in_data",
        "//command_line_option:experimental_strict_fileset_output",
        "//command_line_option:strict_filesets",
        "//command_line_option:check_visibility",
        "//command_line_option:check_licenses",
        "//command_line_option:host_features",
        "//command_line_option:host_action_env",
        "//command_line_option:archived_tree_artifact_mnemonics_filter",
        "//command_line_option:allow_unresolved_symlinks",
        "//command_line_option:experimental_exec_config",
        "//command_line_option:experimental_exclude_defines_from_exec_config",
        "//command_line_option:experimental_exclude_starlark_flags_from_exec_config",
        "//command_line_option:experimental_propagate_custom_flag",
        "//command_line_option:allow_analysis_failures",
        "//command_line_option:experimental_collect_code_coverage_for_generated_files",
        "//command_line_option:experimental_extended_sanity_checks",
        "//command_line_option:experimental_inprocess_symlink_creation",
        "//command_line_option:experimental_throttle_action_cache_check",
        "//command_line_option:experimental_use_platforms_in_output_dir_legacy_heuristic",
    ],
    inputs = ["//command_line_option:features"],
    outputs = [
        "//command_line_option:compilation_mode",
        "//command_line_option:is exec configuration",
        "//command_line_option:cpu",
        "//command_line_option:stamp",
        "//command_line_option:features",
        "//command_line_option:action_env",
    ],
    func = _core_options,
)

# CoverageConfiguration$CoverageOptions:  no getExec()

bazel_fragments["CppOptions"] = fragment(
    propagate = [
        "//command_line_option:host_copt",
        "//command_line_option:host_conlyopt",
        "//command_line_option:host_compiler",
        "//command_line_option:host_crosstool_top",
        "//command_line_option:host_cxxopt",
        "//command_line_option:host_per_file_copt",
        "//command_line_option:host_grte_top",
        "//command_line_option:host_linkopt",
        "//command_line_option:target libcTop label",
        "//command_line_option:experimental_link_static_libraries_once",
        "//command_line_option:experimental_cc_implementation_deps",
        "//command_line_option:experimental_cpp_modules",
        "//command_line_option:start_end_lib",
        "//command_line_option:experimental_inmemory_dotd_files",
        "//command_line_option:incompatible_disable_legacy_cc_provider",
        "//command_line_option:incompatible_enable_cc_toolchain_resolution",
        "//command_line_option:incompatible_remove_legacy_whole_archive",
        "//command_line_option:incompatible_dont_enable_host_nonhost_crosstool_features",
        "//command_line_option:incompatible_require_ctx_in_configure_features",
        "//command_line_option:incompatible_make_thinlto_command_lines_standalone",
        "//command_line_option:incompatible_use_specific_tool_files",
        "//command_line_option:incompatible_disable_nocopts",
        "//command_line_option:incompatible_validate_top_level_header_inclusions",
        "//command_line_option:strict_system_includes",
        "//command_line_option:experimental_use_cpp_compile_action_args_params_file",
        "//command_line_option:experimental_unsupported_and_brittle_include_scanning",
        "//command_line_option:incompatible_use_cpp_compile_header_mnemonic",
        "//command_line_option:experimental_starlark_cc_import",
        "//command_line_option:incompatible_macos_set_install_name",
        "//command_line_option:experimental_cpp_compile_resource_estimation",
        "//command_line_option:experimental_generate_llvm_lcov",
        "//command_line_option:experimental_omitfp",
        "//command_line_option:experimental_platform_cc_test",
        "//command_line_option:experimental_save_feature_state",
        "//command_line_option:experimental_use_llvm_covmap",
        "//command_line_option:experimental_starlark_linking",
    ],
    outputs = [
        "//command_line_option:crosstool_top",
        "//command_line_option:compiler",
        "//command_line_option:grte_top",
        "//command_line_option:copt",
        "//command_line_option:cxxopt",
        "//command_line_option:conlyopt",
        "//command_line_option:per_file_copt",
        "//command_line_option:linkopt",
        "//command_line_option:strip",
    ],
    func = lambda settings: {
        "//command_line_option:crosstool_top": settings["//command_line_option:host_crosstool_top"],
        "//command_line_option:compiler": settings["//command_line_option:host_compiler"],
        "//command_line_option:grte_top": settings["//command_line_option:host_grte_top"],
        # TODO: Properly fix https://github.com/bazelbuild/bazel/issues/24545 with features.
        "//command_line_option:copt": settings["//command_line_option:host_copt"] + ([] if py_internal.get_current_os_name() == "windows" else ["-g0"]),
        "//command_line_option:cxxopt": settings["//command_line_option:host_cxxopt"] + ([] if py_internal.get_current_os_name() == "windows" else ["-g0"]),
        "//command_line_option:conlyopt": settings["//command_line_option:host_conlyopt"],
        "//command_line_option:per_file_copt": settings["//command_line_option:host_per_file_copt"],
        "//command_line_option:linkopt": settings["//command_line_option:host_linkopt"],
        "//command_line_option:strip": "always",
    },
)

bazel_fragments["GenQueryConfiguration$GenQueryOptions"] = fragment(
    propagate = [
        "//command_line_option:experimental_skip_ttvs_for_genquery",
    ],
)

def _java_options(settings):
    ans = {}
    if settings["//command_line_option:host_jvmopt"] == []:
        ans["//command_line_option:jvmopt"] = ["-XX:ErrorFile=/dev/stderr"]
    else:
        ans["//command_line_option:jvmopt"] = settings["//command_line_option:host_jvmopt"]
    ans["//command_line_option:javacopt"] = settings["//command_line_option:host_javacopt"]
    ans["//command_line_option:java_launcher"] = settings["//command_line_option:host_java_launcher"]
    ans["//command_line_option:java_language_version"] = settings["//command_line_option:tool_java_language_version"]
    ans["//command_line_option:java_runtime_version"] = settings["//command_line_option:tool_java_runtime_version"]
    return ans

bazel_fragments["JavaOptions"] = fragment(
    propagate = [
        "//command_line_option:use_ijars",
        "//command_line_option:java_header_compilation",
        "//command_line_option:java_deps",
        "//command_line_option:experimental_java_classpath",
        "//command_line_option:experimental_inmemory_jdeps_files",
        "//command_line_option:experimental_strict_java_deps",
        "//command_line_option:experimental_fix_deps_tool",
        "//command_line_option:experimental_one_version_enforcement",
        "//command_line_option:experimental_import_deps_checking",
        "//command_line_option:one_version_enforcement_on_java_tests",
        "//command_line_option:experimental_allow_runtime_deps_on_neverlink",
        "//command_line_option:experimental_add_test_support_to_compile_time_deps",
        "//command_line_option:jplPropagateCcLinkParamsStore",
        "//command_line_option:java_runtime_version",
        "//command_line_option:java_language_version",
        "//command_line_option:experimental_bytecode_optimizers",
        "//command_line_option:split_bytecode_optimization_pass",
        "//command_line_option:bytecode_optimization_pass_actions",
        "//command_line_option:enforce_proguard_file_extension",
        "//command_line_option:proguard_top",
        "//command_line_option:host_javacopt",
        "//command_line_option:host_java_launcher",
        "//command_line_option:tool_java_runtime_version",
        "//command_line_option:tool_java_language_version",
        "//command_line_option:experimental_turbine_annotation_processing",
        "//command_line_option:incompatible_multi_release_deploy_jars",
        "//command_line_option:incompatible_disallow_java_import_exports",
        "//command_line_option:incompatible_disallow_java_import_empty_jars",
        "//command_line_option:experimental_disallow_legacy_java_toolchain_flags",
        "//command_line_option:experimental_enable_jspecify",
        "//command_line_option:experimental_java_header_input_pruning",
        "//command_line_option:experimental_java_test_auto_create_deploy_jar",
        "//command_line_option:experimental_limit_android_lint_to_android_constrained_java",
        "//command_line_option:experimental_run_android_lint_on_java_rules",
    ],
    inputs = [
        "//command_line_option:host_jvmopt",
    ],
    outputs = [
        "//command_line_option:jvmopt",
        "//command_line_option:javacopt",
        "//command_line_option:java_launcher",
    ],
    func = _java_options,
)

bazel_fragments["ObjcCommandLineOptions"] = fragment(
    propagate = [
        "//command_line_option:incompatible_avoid_hardcoded_objc_compilation_flags",
        "//command_line_option:incompatible_builtin_objc_strip_action",
        "//command_line_option:incompatible_disallow_sdk_frameworks_attributes",
        "//command_line_option:incompatible_objc_alwayslink_by_default",
        "//command_line_option:incompatible_strip_executable_safely",
    ],
)

bazel_fragments["PlatformOptions"] = fragment(
    propagate = [
        "//command_line_option:host_platform",
        "//command_line_option:platform_mappings",
        "//command_line_option:extra_execution_platforms",
        "//command_line_option:extra_toolchains",
        "//command_line_option:toolchain_resolution_debug",
        "//command_line_option:incompatible_use_toolchain_resolution_for_java_rules",
    ],
)

bazel_fragments["ProtoConfiguration$Options"] = fragment(
    propagate = [
        "//command_line_option:proto_compiler",
        "//command_line_option:protocopt",
        "//command_line_option:experimental_proto_descriptor_sets_include_source_info",
        "//command_line_option:proto_toolchain_for_java",
        "//command_line_option:proto_toolchain_for_j2objc",
        "//command_line_option:proto_toolchain_for_javalite",
        "//command_line_option:proto_toolchain_for_cc",
        "//command_line_option:strict_proto_deps",
        "//command_line_option:strict_public_imports",
        "//command_line_option:cc_proto_library_header_suffixes",
        "//command_line_option:cc_proto_library_source_suffixes",
    ],
)

def _python_options(settings):
    if settings["//command_line_option:host_force_python"] != None:
        host_py_version = settings["//command_line_option:host_force_python"]
    elif settings["//command_line_option:incompatible_py3_is_default"]:
        host_py_version = "py3"
    else:
        host_py_version = "py2"
    return {
        "//command_line_option:python_version": host_py_version,
    }

bazel_fragments["PythonOptions"] = fragment(
    # Could move these toolchain configuring flags to toolchain definitions?
    # And not make them flags. Must each one toggle independently of the others?
    propagate = [
        "//command_line_option:build_python_zip",
        "//command_line_option:experimental_py_binaries_include_label",
        "//command_line_option:host_force_python",
        "//command_line_option:incompatible_allow_python_version_transitions",
        "//command_line_option:incompatible_default_to_explicit_init_py",
        "//command_line_option:incompatible_disallow_legacy_py_provider",
        "//command_line_option:incompatible_py2_outputs_are_suffixed",
        "//command_line_option:incompatible_py3_is_default",
        "//command_line_option:incompatible_python_disable_py2",
        "//command_line_option:incompatible_python_disallow_native_rules",
        "//command_line_option:incompatible_remove_old_python_version_api",
        "//command_line_option:incompatible_use_python_toolchains",
        "//command_line_option:python_native_rules_allowlist",
    ],
    outputs = [
        "//command_line_option:python_version",
    ],
    func = _python_options,
)

bazel_fragments["ShellConfiguration$Options"] = fragment(
    propagate = [
        "//command_line_option:shell_executable",
    ],
)

bazel_fragments["GenQueryConfiguration$GenQueryOptions"] = fragment(
    propagate = [
        "//command_line_option:experimental_skip_ttvs_for_genquery",
    ],
)

# TestConfiguration$TestOptions: handled in native code. See b/295936652.

# Bazel's exec transition.
_transition_data = exec_transition(bazel_fragments)
bazel_exec_transition = _builtins.toplevel.exec_transition(
    implementation = _transition_data.implementation,
    inputs = _transition_data.inputs,
    outputs = _transition_data.outputs,
)
