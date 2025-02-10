load("@rules_java//java:defs.bzl", "java_library")

########################################################################
# Description:
#   Main Java code for Bazel
#
# IMPORTANT:  NOT A PUBLIC INTERFACE.
#
# DO NOT ADD A DEPENDENCY on any targets or sub-packages outside of
# __subpackages__ without first talking to Bazel maintainers.
#
# Here are two good reasons:
#
# 1.  FRAGILITY. This is not a stable API, it's an internal interface.
#     It changes from time to time, and your client *will* be broken.
#
# 2.  CORRECTNESS.  The BUILD file parser changes rapidly.  If
#     your application depends upon it, your application will start to
#     bit-rot almost immediately.
#
# There are better alternatives.  The 'bazel query' tool has
# stable command-line and programmatic interfaces that will work with any
# version of Bazel.  They are also debugged and documented, so you'll
# avoid many common traps and pitfalls when (e.g.)  computing
# dependencies.
package(
    default_visibility = ["//src:__subpackages__"],
)

# Generate list of all srcs via:
# bazel query 'filter("srcs", kind("filegroup rule", //src/main/java/com/google/devtools/build/lib/...))' | sort | sed -e "s/^/\"/" | sed -e "s/$/\",/" | fgrep -v "build/lib:srcs"
filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/docgen:srcs",
        "//src/main/java/com/google/devtools/build/lib/actions:srcs",
        "//src/main/java/com/google/devtools/build/lib/analysis:srcs",
        "//src/main/java/com/google/devtools/build/lib/authandtls:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel:srcs",
        "//src/main/java/com/google/devtools/build/lib/bugreport:srcs",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice:srcs",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice/client:srcs",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream:srcs",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:srcs",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/transports:srcs",
        "//src/main/java/com/google/devtools/build/lib/causes:srcs",
        "//src/main/java/com/google/devtools/build/lib/clock:srcs",
        "//src/main/java/com/google/devtools/build/lib/cmdline:srcs",
        "//src/main/java/com/google/devtools/build/lib/collect:srcs",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashmap:srcs",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashset:srcs",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset:srcs",
        "//src/main/java/com/google/devtools/build/lib/concurrent:srcs",
        "//src/main/java/com/google/devtools/build/lib/dynamic:srcs",
        "//src/main/java/com/google/devtools/build/lib/events:srcs",
        "//src/main/java/com/google/devtools/build/lib/exec:srcs",
        "//src/main/java/com/google/devtools/build/lib/graph:srcs",
        "//src/main/java/com/google/devtools/build/lib/io:srcs",
        "//src/main/java/com/google/devtools/build/lib/jni:srcs",
        "//src/main/java/com/google/devtools/build/lib/metrics:srcs",
        "//src/main/java/com/google/devtools/build/lib/network:srcs",
        "//src/main/java/com/google/devtools/build/lib/packages:srcs",
        "//src/main/java/com/google/devtools/build/lib/packages/metrics:srcs",
        "//src/main/java/com/google/devtools/build/lib/packages/producers:srcs",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics:srcs",
        "//src/main/java/com/google/devtools/build/lib/pkgcache:srcs",
        "//src/main/java/com/google/devtools/build/lib/platform:srcs",
        "//src/main/java/com/google/devtools/build/lib/profiler:srcs",
        "//src/main/java/com/google/devtools/build/lib/profiler/memory:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2/common:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2/compat:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2/engine:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2/query/aspectresolvers:srcs",
        "//src/main/java/com/google/devtools/build/lib/query2/query/output:srcs",
        "//src/main/java/com/google/devtools/build/lib/remote:srcs",
        "//src/main/java/com/google/devtools/build/lib/repository:srcs",
        "//src/main/java/com/google/devtools/build/lib/rules:srcs",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands:srcs",
        "//src/main/java/com/google/devtools/build/lib/runtime/events:srcs",
        "//src/main/java/com/google/devtools/build/lib/runtime/mobileinstall:srcs",
        "//src/main/java/com/google/devtools/build/lib/sandbox:srcs",
        "//src/main/java/com/google/devtools/build/lib/sandbox/cgroups:srcs",
        "//src/main/java/com/google/devtools/build/lib/server:srcs",
        "//src/main/java/com/google/devtools/build/lib/shell:srcs",
        "//src/main/java/com/google/devtools/build/lib/skyframe:srcs",
        "//src/main/java/com/google/devtools/build/lib/standalone:srcs",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi:srcs",
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/module:srcs",
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/proto:srcs",
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/server:srcs",
        "//src/main/java/com/google/devtools/build/lib/starlarkdocextract:srcs",
        "//src/main/java/com/google/devtools/build/lib/supplier:srcs",
        "//src/main/java/com/google/devtools/build/lib/testing/common:srcs",
        "//src/main/java/com/google/devtools/build/lib/testing/vfs:srcs",
        "//src/main/java/com/google/devtools/build/lib/unix:srcs",
        "//src/main/java/com/google/devtools/build/lib/unsafe:srcs",
        "//src/main/java/com/google/devtools/build/lib/util:srcs",
        "//src/main/java/com/google/devtools/build/lib/versioning:srcs",
        "//src/main/java/com/google/devtools/build/lib/vfs:srcs",
        "//src/main/java/com/google/devtools/build/lib/vfs/bazel:srcs",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs:srcs",
        "//src/main/java/com/google/devtools/build/lib/windows:srcs",
        "//src/main/java/com/google/devtools/build/lib/worker:srcs",
        "//src/main/java/com/google/devtools/build/skyframe:srcs",
        "//src/main/java/com/google/devtools/common/options:srcs",
        "//src/main/java/net/starlark/java:srcs",
        "//src/main/java/net/starlark/java/annot:srcs",
        "//src/main/java/net/starlark/java/annot/processor:srcs",
        "//src/main/java/net/starlark/java/eval:srcs",
        "//src/main/java/net/starlark/java/syntax:srcs",
    ],
    visibility = [
        "//src:__pkg__",
        "//src/test/shell/bazel:__pkg__",
    ],
)

java_library(
    name = "build-request-options",
    srcs = ["buildtool/BuildRequestOptions.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:caffeine",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "buildtool/symlink_forest",
    srcs = ["buildtool/SymlinkForest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "runtime/blaze_server_startup_options",
    srcs = ["runtime/BlazeServerStartupOptions.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)

java_library(
    name = "runtime/safe_request_logging",
    srcs = ["runtime/SafeRequestLogging.java"],
)

java_library(
    name = "keep-going-option",
    srcs = ["runtime/KeepGoingOption.java"],
    deps = ["//src/main/java/com/google/devtools/common/options"],
)

java_library(
    name = "loading-phase-threads-option",
    srcs = ["runtime/LoadingPhaseThreadsOption.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:flogger",
    ],
)

java_library(
    name = "runtime/blaze_command_result",
    srcs = [
        "runtime/BlazeCommandResult.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/server:idle_task",
        "//src/main/java/com/google/devtools/build/lib/util:crash_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/protobuf:command_server_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//java/core",
    ],
)

java_library(
    name = "runtime/build_event_streamer_utils",
    srcs = ["runtime/BuildEventStreamerUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/protobuf:test_status_java_proto",
    ],
)

java_library(
    name = "runtime/command_dispatcher",
    srcs = [
        "runtime/CommandDispatcher.java",
    ],
    deps = [
        ":runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/protobuf:invocation_policy_java_proto",
        "@com_google_protobuf//java/core",
    ],
)

java_library(
    name = "runtime/memory_pressure",
    srcs = [
        "runtime/GcThrashingDetector.java",
        "runtime/MemoryPressureEvent.java",
        "runtime/MemoryPressureOptions.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "runtime/test_summary_options",
    srcs = ["runtime/TestSummaryOptions.java"],
    deps = ["//src/main/java/com/google/devtools/common/options"],
)

java_library(
    name = "runtime/command_line_path_factory",
    srcs = [
        "runtime/CommandLinePathFactory.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
    ],
)

java_library(
    name = "starlark_options_parser",
    srcs = ["runtime/StarlarkOptionsParser.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_option_converters",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/util:pair",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "runtime/config_flag_definitions",
    srcs = [
        "runtime/ConfigFlagDefinitions.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)

java_library(
    name = "runtime",
    srcs = glob(
        [
            "runtime/*.java",
            "buildtool/*.java",
            "buildtool/buildevent/*.java",
        ],
        exclude = [
            "buildtool/BuildRequestOptions.java",
            "buildtool/SymlinkForest.java",
            "runtime/BlazeCommandResult.java",
            "runtime/CommandDispatcher.java",
            "runtime/CommandLinePathFactory.java",
            "runtime/ConfigFlagDefinitions.java",
            "runtime/GcThrashingDetector.java",
            "runtime/KeepGoingOption.java",
            "runtime/LoadingPhaseThreadsOption.java",
            "runtime/MemoryPressureEvent.java",
            "runtime/MemoryPressureOptions.java",
            "runtime/MemoryPressureStatCollector.java",
            "runtime/StarlarkOptionsParser.java",
            "runtime/TestSummaryOptions.java",
        ],
    ),
    deps = [
        ":build-request-options",
        ":buildtool/symlink_forest",
        ":keep-going-option",
        ":loading-phase-threads-option",
        ":runtime/blaze_command_result",
        ":runtime/command_dispatcher",
        ":runtime/command_line_path_factory",
        ":runtime/config_flag_definitions",
        ":runtime/memory_pressure",
        ":runtime/test_summary_options",
        ":starlark_options_parser",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_lookup_data",
        "//src/main/java/com/google/devtools/build/lib/actions:action_output_directory_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:commandline_item",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:important_output_handler",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/actions:package_roots",
        "//src/main/java/com/google/devtools/build/lib/actions:resource_manager",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:shared_action_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/abstract_file_write_action",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_complete_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:aspect_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_version_info",
        "//src/main/java/com/google/devtools/build/lib/analysis:build_info_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:build_view",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/additional_configuration_change_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_option_converters",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/invalid_configuration_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/starlark_defined_config_transition",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/transitions/baseline_options_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/analysis:file_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:no_build_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:no_build_request_finished_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:project_resolution_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:projects",
        "//src/main/java/com/google/devtools/build/lib/analysis:provider_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:server_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:starlark/starlark_late_bound_default",
        "//src/main/java/com/google/devtools/build/lib/analysis:template_expansion_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/coverage_artifacts_known_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/coverage_report_action_factory",
        "//src/main/java/com/google/devtools/build/lib/analysis:test/test_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:top_level_artifact_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:transitive_info_collection",
        "//src/main/java/com/google/devtools/build/lib/analysis:view_creation_failed_exception",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/downloader",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/transports",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/cmdline:LabelValidator",
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec:blaze_executor",
        "//src/main/java/com/google/devtools/build/lib/exec:check_up_to_date_filter",
        "//src/main/java/com/google/devtools/build/lib/exec:execution_options",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_builder",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_lifecycle_listener",
        "//src/main/java/com/google/devtools/build/lib/exec:module_action_context_registry",
        "//src/main/java/com/google/devtools/build/lib/exec:remote_local_fallback_registry",
        "//src/main/java/com/google/devtools/build/lib/exec:single_build_file_cache",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_cache",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_strategy_registry",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_strategy_resolver",
        "//src/main/java/com/google/devtools/build/lib/exec:symlink_tree_strategy",
        "//src/main/java/com/google/devtools/build/lib/exec:test_log_helper",
        "//src/main/java/com/google/devtools/build/lib/exec/local:options",
        "//src/main/java/com/google/devtools/build/lib/jni",
        "//src/main/java/com/google/devtools/build/lib/metrics:event",
        "//src/main/java/com/google/devtools/build/lib/metrics:garbage-collection-metrics-util",
        "//src/main/java/com/google/devtools/build/lib/metrics/criticalpath",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:collect_local_resource_usage",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/profiler/memory:allocationtracker",
        "//src/main/java/com/google/devtools/build/lib/query2",
        "//src/main/java/com/google/devtools/build/lib/query2:aquery-utils",
        "//src/main/java/com/google/devtools/build/lib/query2/common:cquery-node",
        "//src/main/java/com/google/devtools/build/lib/query2/common:options",
        "//src/main/java/com/google/devtools/build/lib/query2/engine",
        "//src/main/java/com/google/devtools/build/lib/query2/query/output",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/runtime/events",
        "//src/main/java/com/google/devtools/build/lib/server",
        "//src/main/java/com/google/devtools/build/lib/server:idle_task",
        "//src/main/java/com/google/devtools/build/lib/server:install_base_garbage_collector",
        "//src/main/java/com/google/devtools/build/lib/server:pid_file_watcher",
        "//src/main/java/com/google/devtools/build/lib/server:rpc_server",
        "//src/main/java/com/google/devtools/build/lib/server:shutdown_hooks",
        "//src/main/java/com/google/devtools/build/lib/server/signal",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/skyframe:action_execution_inactivity_watchdog",
        "//src/main/java/com/google/devtools/build/lib/skyframe:analysis_progress_receiver",
        "//src/main/java/com/google/devtools/build/lib/skyframe:aspect_completion_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:aspect_key_creator",
        "//src/main/java/com/google/devtools/build/lib/skyframe:build_driver_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe:build_driver_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:build_result_listener",
        "//src/main/java/com/google/devtools/build/lib/skyframe:builder",
        "//src/main/java/com/google/devtools/build/lib/skyframe:configuration_phase_started_event",
        "//src/main/java/com/google/devtools/build/lib/skyframe:configured_target_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe:coverage_report_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:default_syscall_cache",
        "//src/main/java/com/google/devtools/build/lib/skyframe:diff_awareness",
        "//src/main/java/com/google/devtools/build/lib/skyframe:execution_finished_event",
        "//src/main/java/com/google/devtools/build/lib/skyframe:incremental_package_roots",
        "//src/main/java/com/google/devtools/build/lib/skyframe:loading_phase_started_event",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_progress_receiver",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_roots_no_symlink_creation",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:prerequisite_package_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe:project_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:repository_mapping_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyfocus",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyfocus_options",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyfocus_state",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_action_executor",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_error_processor",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_executor_repository_helpers_holder",
        "//src/main/java/com/google/devtools/build/lib/skyframe:target_completion_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:target_pattern_phase_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:top_level_aspects_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib/skyframe:workspace_info",
        "//src/main/java/com/google/devtools/build/lib/skyframe:workspace_status_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/actiongraph/v2:actiongraph_v2",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/rewinding:action_rewound_event",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:client_id",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:dependencies_provider",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:event_listener",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:frontier_serializer",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:frontier_violation_checker",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:options",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:crash_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:custom_exit_code_publisher",
        "//src/main/java/com/google/devtools/build/lib/util:custom_failure_detail_publisher",
        "//src/main/java/com/google/devtools/build/lib/util:debug-logger-configurator",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:file_system_lock",
        "//src/main/java/com/google/devtools/build/lib/util:heap_offset_helper",
        "//src/main/java/com/google/devtools/build/lib/util:interrupted_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:logging",
        "//src/main/java/com/google/devtools/build/lib/util:memory_accountant",
        "//src/main/java/com/google/devtools/build/lib/util:object_graph_traverser",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:process",
        "//src/main/java/com/google/devtools/build/lib/util:shallow_object_size_computer",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:output_service",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/bazel",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_process_metrics",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/com/google/devtools/common/options:invocation_policy",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//src/main/protobuf:action_cache_java_proto",
        "//src/main/protobuf:analysis_v2_java_proto",
        "//src/main/protobuf:any_java_proto",
        "//src/main/protobuf:command_line_java_proto",
        "//src/main/protobuf:command_server_java_proto",
        "//src/main/protobuf:crash_debugging_java_proto",
        "//src/main/protobuf:execution_graph_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//src/main/protobuf:memory_pressure_java_proto",
        "//src/main/protobuf:option_filters_java_proto",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:error_prone_annotations",
        "//third_party:flogger",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//java/util",
        "@zstd-jni//:zstd-jni",
    ],
)

java_library(
    name = "all-build-rules",
    exports = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:legacy_exports",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/graph",
        "//src/main/java/com/google/devtools/build/lib/rules:legacy_exports",
        "//src/main/java/com/google/devtools/build/lib/rules/objc",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)

########################################################################
#
# Documentation generation
#

filegroup(
    name = "generated_docs",
    srcs = [
        "//src/main/java/com/google/devtools/build/lib:gen_buildencyclopedia",
        "//src/main/java/com/google/devtools/build/lib:gen_command-line-reference",
        "//src/main/java/com/google/devtools/build/lib:gen_skylarklibrary",
    ],
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

filegroup(
    name = "docs_embedded_in_sources",
    srcs = glob(["**/*.java"]) + [
        "//src/main/java/com/google/devtools/build/lib/analysis:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules:srcs",
        "//src/main/java/com/google/devtools/build/lib/exec:srcs",
        "//src/main/java/com/google/devtools/build/lib/repository:srcs",
        "//src/main/java/com/google/devtools/build/lib/rules:srcs",
    ],
)

genrule(
    name = "gen_buildencyclopedia",
    srcs = [
        ":docs_embedded_in_sources",
        "//src/main/java/com/google/devtools/build/docgen:bazel_link_map",
        "//src/main/starlark/docgen:gen_be_proto_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_java_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_cpp_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_objc_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_python_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_shell_stardoc_proto",
    ],
    outs = ["build-encyclopedia.zip"],
    cmd = (
        "mkdir -p $(@D)/be && " +
        "$(location //src/main/java/com/google/devtools/build/docgen:docgen_bin)" +
        " --link_map_path=$(location //src/main/java/com/google/devtools/build/docgen:bazel_link_map)" +
        " --provider=com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider" +
        " --input_root=$$PWD" +
        " --input_dir=$$PWD/src/main/java/com/google/devtools/build/lib" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_proto_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_java_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_cpp_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_objc_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_python_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_shell_stardoc_proto)" +
        " --output_dir=$(@D)/be" +
        " --create_toc=true &&" +
        "zip -qj $@ $(@D)/be/*"
    ),
    tools = [
        "//src/main/java/com/google/devtools/build/docgen:docgen_bin",
        "//src/main/java/com/google/devtools/build/docgen:docgen_javalib",
    ],
    visibility = ["//site:__pkg__"],
)

genrule(
    name = "gen_command-line-reference",
    srcs = [
        "//site:command-line-reference-prefix.html",
        "//site:command-line-reference-suffix.html",
    ],
    outs = ["command-line-reference.html"],
    cmd = (
        "cat $(location //site:command-line-reference-prefix.html) > $@ && " +
        "TMP=$$(mktemp -d $${TMPDIR:-/tmp}/tmp.XXXXXXXXXX) && " +
        # TODO(#11179): Remove when the stub template fix is released.
        "export JARBIN=$(JAVABASE)/bin/jar && " +
        "$(location //src/main/java/com/google/devtools/build/lib/bazel:BazelServer) " +
        "--batch " +
        "--install_base=$${TMP} --output_base=$${TMP}/output/ --output_user_root=$${TMP} " +
        "--failure_detail_out=$${TMP}/output/failure_detail.rawproto " +
        "help everything-as-html >> $@ 2>/dev/null && " +
        "cat $(location //site:command-line-reference-suffix.html) >> $@"
    ),
    toolchains = ["@rules_java//toolchains:current_host_java_runtime"],
    tools = [
        "//src/main/java/com/google/devtools/build/lib/bazel:BazelServer",
    ],
    visibility = ["//site:__pkg__"],
)

genrule(
    name = "gen_skylarklibrary",
    srcs = ["//src/main/java/com/google/devtools/build/docgen:bazel_link_map"],
    outs = ["skylark-library.zip"],
    cmd = "mkdir -p $(@D)/skylark-lib &&" +
          "$(location //src/main/java/com/google/devtools/build/docgen:skydoc_bin)" +
          " $(@D)/skylark-lib" +
          " --link_map_path=$(location //src/main/java/com/google/devtools/build/docgen:bazel_link_map)" +
          " --create_toc=true" +
          " && OUTPUT=$$PWD/$@" +
          " && pushd $(@D)/skylark-lib" +
          " && zip -qr $$OUTPUT ." +
          " && popd",
    tools = ["//src/main/java/com/google/devtools/build/docgen:skydoc_bin"],
    visibility = ["//site:__pkg__"],
)

genrule(
    name = "gen_reference_docs",
    srcs = [
        ":build-encyclopedia.zip",
        ":command-line-reference.html",
        ":skylark-library.zip",
        "//tools/build_defs/repo:doc",
    ],
    outs = ["reference-docs.zip"],
    cmd = "mkdir -p $(@D)/gen_docs/reference/be && " +
          "cp $(location :command-line-reference.html) $(@D)/gen_docs/reference/ && " +
          "unzip -q -d $(@D)/gen_docs/reference/be/ $(location :build-encyclopedia.zip) && " +
          "mkdir -p $(@D)/gen_docs/rules/lib/repo && " +
          "unzip -q -d $(@D)/gen_docs/rules/lib/ $(location :skylark-library.zip) && " +
          "tar -xf $(location //tools/build_defs/repo:doc) -C $(@D)/gen_docs/rules/lib/repo/ && " +
          # Ugly hack: we want to preserve paths relative to gen_docs/,
          # which means we have to temporarily cd into that directory and zip with -r.
          "pushd $(@D)/gen_docs && " +
          "zip -qr tmp.zip . && " +
          "popd && mv $(@D)/gen_docs/tmp.zip $@",
    visibility = ["//scripts/docs:__pkg__"],
)

genrule(
    name = "gen_api_proto",
    srcs = [
        "//src/main/java/com/google/devtools/build/docgen:bazel_link_map",
        "//src/main/starlark/docgen:gen_be_proto_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_java_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_cpp_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_objc_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_python_stardoc_proto",
        "//src/main/starlark/docgen:gen_be_shell_stardoc_proto",
        ":docs_embedded_in_sources",
    ],
    outs = ["builtin.pb"],
    cmd = (
        "$(location //src/main/java/com/google/devtools/build/docgen:api_exporter)" +
        " --output_file=$@" +
        " --link_map_path=$(location //src/main/java/com/google/devtools/build/docgen:bazel_link_map) " +
        " --provider=com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider" +
        " --input_root=$$PWD" +
        " --input_dir=$$PWD/src/main/java/com/google/devtools/build/lib" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_proto_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_java_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_cpp_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_objc_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_python_stardoc_proto)" +
        " --input_stardoc_proto=$(location //src/main/starlark/docgen:gen_be_shell_stardoc_proto)"
    ),
    tools = [
        "//src/main/java/com/google/devtools/build/docgen:api_exporter",
    ],
)
