load("//:workspace_deps.bzl", "gen_workspace_stanza")

package(default_visibility = ["//visibility:private"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/test/shell:__pkg__"],
)

filegroup(
    name = "test-deps",
    testonly = 1,
    srcs = [
        "//src/test/shell/bazel:test-deps",
    ],
)

filegroup(
    name = "test-deps-minimal-bazel",
    testonly = 1,
    srcs = [
        "//src:bazel-bin_jdk_minimal",
        "//src/test/shell:bin/bazel_jdk_minimal",
        "//src/test/shell/bazel:test-deps-wo-bazel",
    ],
)

filegroup(
    name = "test-deps-nojdk",
    testonly = 1,
    srcs = [
        "//src:bazel-bin_nojdk",
        "//src/test/shell:bin/bazel_nojdk",
        "//src/test/shell/bazel:test-deps-wo-bazel",
    ],
)

gen_workspace_stanza(
    name = "protobuf_stanza",
    out = "protobuf_stanza.txt",
    repos = [
        "rules_python",
    ],
)

sh_test(
    name = "progress_reporting_test",
    size = "large",
    srcs = ["progress_reporting_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 4,
)

sh_test(
    name = "implicit_dependency_reporting_test",
    size = "medium",
    srcs = ["implicit_dependency_reporting_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "execution_strategies_test",
    size = "medium",
    srcs = ["execution_strategies_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "runfiles_test",
    size = "medium",
    srcs = ["runfiles_test.sh"],
    data = [
        "runfiles_test_utils.sh",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no-sandbox",
    ],
)

sh_test(
    name = "python_stub_test",
    size = "medium",
    srcs = ["python_stub_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "python_test",
    size = "medium",
    srcs = ["python_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "aspect_test",
    size = "medium",
    srcs = ["aspect_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "loading_phase_tests",
    size = "large",
    srcs = ["loading_phase_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 8,
    tags = [
        "requires-network",  # For Bzlmod
    ],
)

sh_test(
    name = "loading_phase_posix_tests",
    size = "large",
    srcs = ["loading_phase_posix_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",  # test uses symlinks and chmod
    ],
)

sh_test(
    name = "execution_phase_tests",
    size = "large",
    srcs = ["execution_phase_tests.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "minimal_jdk_test",
    size = "large",
    srcs = ["minimal_jdk_test.sh"],
    data = [
        ":test-deps-minimal-bazel",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "ui_test",
    size = "large",
    srcs = ["ui_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 9,
)

sh_test(
    name = "java_integration_test",
    size = "large",
    srcs = ["java_integration_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        "java_integration_test_utils.sh",
        ":test-deps",
        "//src/test/shell:shell_utils",
    ],
    shard_count = 5,
    tags = [
        "no_windows",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "startup_options_test",
    size = "medium",
    srcs = ["startup_options_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "nojdk_startup_options_test",
    size = "medium",
    srcs = ["nojdk_startup_options_test.sh"],
    data = [
        ":test-deps-nojdk",
        "@bazel_tools//tools/bash/runfiles",
    ],
    # Windows doesn't support sandboxing, which BAZEL_SUFFIX needs.
    tags = ["no_windows"],
)

sh_test(
    name = "run_test",
    size = "medium",
    srcs = ["run_test.sh"],
    data = [":test-deps"],
    shard_count = 3,
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "stub_finds_runfiles_test",
    size = "medium",
    srcs = ["stub_finds_runfiles_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "discard_analysis_cache_test",
    size = "medium",
    srcs = ["discard_analysis_cache_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "no_windows",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "bazel_testjobs_test",
    srcs = ["bazel_testjobs_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 2,
)

sh_test(
    name = "bazel_query_test",
    size = "large",
    srcs = ["bazel_query_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
    tags = ["no_windows"],
)

sh_test(
    name = "configured_query_test",
    size = "large",
    srcs = ["configured_query_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
)

sh_test(
    name = "starlark_configurations_test",
    size = "medium",
    srcs = ["starlark_configurations_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
)

sh_test(
    name = "starlark_configurations_external_workspaces_test",
    size = "medium",
    srcs = ["starlark_configurations_external_workspaces_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "analysis_test_test",
    size = "medium",
    srcs = ["analysis_test_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "aquery_test",
    size = "large",
    srcs = ["aquery_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_aquery_test",
    size = "large",
    srcs = ["bazel_aquery_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "bazel_command_log_test",
    size = "medium",
    srcs = ["bazel_command_log_test.sh"],
    data = [":test-deps"],
    shard_count = 4,
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_javabase_test",
    size = "medium",
    srcs = ["bazel_javabase_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "client_sigint_test",
    size = "medium",
    srcs = ["client_sigint_test.sh"],
    data = [":test-deps"],
    # This test doesn't work with the sandbox on, see the source file
    # for details.
    tags = [
        "no-sandbox",
        "no_windows",
    ],
)

sh_test(
    name = "modify_execution_info_test",
    srcs = ["modify_execution_info_test.sh"],
    data = [
        ":protobuf_stanza.txt",
        ":test-deps",
        "//third_party/zlib",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["requires-network"],  # Allow this test to access internet to fetch protobuf dependencies.
)

sh_test(
    name = "force_delete_output_test",
    size = "medium",
    srcs = ["force_delete_output_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "starlark_flag_test",
    size = "medium",
    srcs = ["starlark_flag_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "builtins_injection_test",
    size = "medium",
    srcs = ["builtins_injection_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "rc_options_test",
    size = "medium",
    srcs = ["rc_options_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "cpp_test",
    size = "large",
    srcs = ["cpp_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "action_aspect_test",
    size = "large",
    srcs = ["action_aspect_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "action_env_test",
    size = "large",
    srcs = ["action_env_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_library(
    name = "discard_graph_edges_lib",
    testonly = 1,
    srcs = ["discard_graph_edges_lib.sh"],
)

sh_test(
    name = "stamping_test",
    size = "medium",
    srcs = ["stamping_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "discard_graph_edges_test",
    size = "medium",
    srcs = ["discard_graph_edges_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":discard_graph_edges_lib.sh",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 8,
    tags = ["no_windows"],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "nonincremental_builds_test",
    size = "medium",
    srcs = ["nonincremental_builds_test.sh"],
    args = ["$(JAVABASE)"],
    data = [
        ":discard_graph_edges_lib.sh",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    toolchains = ["@rules_java//toolchains:current_java_runtime"],
)

sh_test(
    name = "build_event_stream_test",
    size = "medium",
    timeout = "long",
    srcs = ["build_event_stream_test.sh"],
    data = [":test-deps"],
    shard_count = 2,
    tags = ["no_windows"],
)

sh_test(
    name = "bazel_worker_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "--worker_sandboxing=no",
        "non-sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_worker_with_cgroups_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "'--worker_sandboxing=no --experimental_worker_use_cgroups_on_linux'",
        "non-sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        # MacOS does not have cgroups so it can't support hardened sandbox
        "no_macos",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_json_worker_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "--worker_sandboxing=no",
        "non-sandboxed",
        "json",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_worker_multiplexer_test",
    size = "large",
    srcs = ["bazel_worker_multiplexer_test.sh"],
    args = [
        "--worker_sandboxing=no",
        "non-sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorkerMultiplexer_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxed_worker_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "--worker_sandboxing",
        "sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        "no_windows",
    ],
)

sh_test(
    name = "bazel_hardened_sandboxed_worker_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "'--worker_sandboxing --experimental_worker_sandbox_hardening'",
        "sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        # MacOS does not have cgroups so it can't support hardened sandbox
        "no_macos",
        "no_windows",
    ],
)

sh_test(
    name = "bazel_sandboxed_worker_with_cgroups_test",
    size = "large",
    srcs = ["bazel_worker_test.sh"],
    args = [
        "'--worker_sandboxing --experimental_worker_use_cgroups_on_linux'",
        "sandboxed",
        "proto",
    ],
    data = [
        ":test-deps",
        "//src/test/java/com/google/devtools/build/lib/worker:ExampleWorker_deploy.jar",
    ],
    shard_count = 3,
    tags = [
        # MacOS does not have cgroups so it can't support hardened sandbox
        "no_macos",
        "no_windows",
    ],
)

sh_test(
    name = "server_logging_test",
    size = "medium",
    srcs = ["server_logging_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "client_test",
    size = "large",
    srcs = ["client_test.sh"],
    data = [":test-deps"],
    shard_count = 8,
    tags = ["no_windows"],
)

sh_test(
    name = "test_test",
    size = "large",
    srcs = ["test_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "outputs_test",
    size = "medium",
    srcs = ["outputs_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "flagset_test",
    size = "large",
    srcs = ["flagset_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

# Copy protoc into a known location, since //third_party/protobuf:protoc
# might be an alias.  This is referenced from testenv.sh.
genrule(
    name = "copy_protoc",
    srcs = ["//third_party/protobuf:protoc"],
    outs = ["protoc"],
    cmd = "cp $< $@",
)

sh_test(
    name = "process_wrapper_test",
    size = "medium",
    srcs = ["process-wrapper_test.sh"],
    data = [
        ":execution_statistics_utils.sh",
        ":protoc",
        ":spend_cpu_time",
        ":test-deps",
        "//src/main/protobuf:execution_statistics.proto",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "linux_sandbox_test",
    size = "large",
    srcs = ["linux-sandbox_test.sh"],
    data = [
        ":execution_statistics_utils.sh",
        ":protoc",
        ":spend_cpu_time",
        ":test-deps",
        "//src/main/protobuf:execution_statistics.proto",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "linux_sandbox_network_test",
    size = "large",
    srcs = ["linux-sandbox_network_test.sh"],
    data = [
        ":test-deps",
        "//src/test/shell:sandboxing_test_utils.sh",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "sandboxing_test",
    size = "large",
    srcs = ["sandboxing_test.sh"],
    data = [
        ":test-deps",
    ],
    shard_count = 4,
    tags = ["no_windows"],
)

package_group(
    name = "spend_cpu_time_users",
    packages = [
        "//src/test/java/com/google/devtools/build/lib/...",
        "//src/test/java/com/google/devtools/build/lib/shell/...",
    ],
)

cc_binary(
    name = "spend_cpu_time",
    testonly = 1,
    srcs = select({
        "//src/conditions:windows": ["spend_cpu_time_windows.cc"],
        "//conditions:default": ["spend_cpu_time.cc"],
    }),
    visibility = [
        ":spend_cpu_time_users",
    ],
)

sh_test(
    name = "prelude_test",
    size = "medium",
    srcs = ["prelude_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "incompatible_autoload_externally_test",
    size = "medium",
    srcs = ["incompatible_autoload_externally_test.sh"],
    data = [":test-deps"],
    tags = [
        "no_windows",
        "requires-network",
    ],
)

sh_test(
    name = "bazel_java_test",
    size = "medium",
    srcs = ["bazel_java_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
)

sh_test(
    name = "jvm_flags_escaping_test",
    srcs = ["jvm_flags_escaping_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "py_args_escaping_test",
    srcs = ["py_args_escaping_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "bash_runfiles_v1_test",
    srcs = ["bash_runfiles_v1_test.sh"],
    data = ["BUILD"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "starlark_dependency_pruning_test",
    timeout = "long",
    srcs = ["starlark_dependency_pruning_test.sh"],
    data = [":test-deps"],
    shard_count = 2,
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "validation_actions_test",
    srcs = ["validation_actions_test.sh"],
    data = [":test-deps"],
    tags = ["no_windows"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "target_pattern_file_test",
    srcs = ["target_pattern_file_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "target_compatible_with_test",
    timeout = "long",
    srcs = ["target_compatible_with_test.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "target_compatible_with_external_repo_test",
    timeout = "long",
    srcs = ["target_compatible_with_test_external_repo.sh"],
    data = [":test-deps"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_test(
    name = "info_test",
    srcs = ["info_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "analysis_phase_options_test",
    size = "medium",
    srcs = ["analysis_phase_options_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "exec_group_test",
    srcs = ["exec_group_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
)

sh_test(
    name = "watchfs_test",
    srcs = ["watchfs_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = [
        "exclusive",
        "no_windows",
    ],
)

sh_test(
    name = "toolchain_test",
    size = "large",
    srcs = ["toolchain_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 10,
    tags = ["no_windows"],
)

sh_test(
    name = "toolchain_transition_test",
    srcs = ["toolchain_transition_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "rule_transition_test",
    srcs = ["rule_transition_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    tags = ["no_windows"],
)

sh_test(
    name = "unicode_test",
    srcs = ["unicode_test.sh"],
    data = [
        "unicode_test.bzl",
        "unicode_test_BUILD",
        "unicode_test_expected.txt",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    # TODO(arostovtsev): figure out how to make this test Windows-compatible.
    tags = ["no_windows"],
)

sh_test(
    name = "subrules_test",
    srcs = ["subrules_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "config_stripped_outputs_test",
    size = "medium",
    srcs = ["config_stripped_outputs_test.sh"],
    data = [
        ":config_stripped_outputs_lib",
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    # Bazel test requires sandboxing
    tags = ["no_windows"],
)

sh_library(
    name = "config_stripped_outputs_lib",
    testonly = 1,
    srcs = ["config_stripped_outputs_lib.sh"],
)

sh_test(
    name = "focus_test",
    srcs = ["focus_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 3,
)

sh_test(
    name = "dump_test",
    srcs = ["dump_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 1,
)

sh_test(
    name = "platform_mapping_test",
    srcs = ["platform_mapping_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
    shard_count = 5,
    tags = ["no_windows"],
)

sh_test(
    name = "platform_based_flags_test",
    srcs = ["platform_based_flags_test.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

sh_test(
    name = "flags_scoping_integration_tests",
    srcs = ["flags_scoping_integration_tests.sh"],
    data = [
        ":test-deps",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

########################################################################
# Test suites.

test_suite(
    name = "all_tests",
    visibility = ["//visibility:public"],
)
