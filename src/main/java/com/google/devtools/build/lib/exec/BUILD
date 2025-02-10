load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/lib/exec/local:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "abstract_spawn_strategy",
    srcs = ["AbstractSpawnStrategy.java"],
    deps = [
        ":execution_options",
        ":spawn_cache",
        ":spawn_exec_exception",
        ":spawn_input_expander",
        ":spawn_log_context",
        ":spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/remote/common:bulk_transfer_exception",
        "//src/main/java/com/google/devtools/build/lib/util:command",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:spawn_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "always_out_of_date_action",
    srcs = ["AlwaysOutOfDateAction.java"],
)

java_library(
    name = "bin_tools",
    srcs = ["BinTools.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "blaze_executor",
    srcs = ["BlazeExecutor.java"],
    deps = [
        ":execution_options",
        ":module_action_context_registry",
        ":spawn_strategy_registry",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "check_up_to_date_filter",
    srcs = ["CheckUpToDateFilter.java"],
    deps = [
        ":always_out_of_date_action",
        ":execution_options",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//third_party:guava",
    ],
)

java_library(
    name = "execution_options",
    srcs = ["ExecutionOptions.java"],
    deps = [
        ":regex_filter_assignment_converter",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/per_label_options",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:cpu_resource_converter",
        "//src/main/java/com/google/devtools/build/lib/util:ram_resource_converter",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)

java_library(
    name = "execution_policy",
    srcs = ["ExecutionPolicy.java"],
    deps = ["//third_party:error_prone_annotations"],
)

java_library(
    name = "executor_builder",
    srcs = ["ExecutorBuilder.java"],
    deps = [
        ":executor_lifecycle_listener",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//third_party:guava",
    ],
)

java_library(
    name = "executor_lifecycle_listener",
    srcs = ["ExecutorLifecycleListener.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/skyframe:ephemeral_check_if_output_consumed",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "file_write_strategy",
    srcs = ["FileWriteStrategy.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/deterministic_writer",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/file_write_action_context",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "module_action_context_registry",
    srcs = ["ModuleActionContextRegistry.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "regex_filter_assignment_converter",
    srcs = ["RegexFilterAssignmentConverter.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_library(
    name = "remote_local_fallback_registry",
    srcs = ["RemoteLocalFallbackRegistry.java"],
    deps = [
        ":abstract_spawn_strategy",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "runfiles_tree_updater",
    srcs = ["RunfilesTreeUpdater.java"],
    deps = [
        ":bin_tools",
        ":symlink_tree_helper",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "single_build_file_cache",
    srcs = ["SingleBuildFileCache.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "spawn_cache",
    srcs = ["SpawnCache.java"],
    deps = [
        ":spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/profiler",
    ],
)

java_library(
    name = "spawn_exec_exception",
    srcs = ["SpawnExecException.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "spawn_input_expander",
    srcs = ["SpawnInputExpander.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_tree",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
    ],
)

java_library(
    name = "spawn_log_context",
    srcs = [
        "CompactSpawnLogContext.java",
        "ExpandedSpawnLogContext.java",
        "SpawnLogContext.java",
    ],
    deps = [
        ":spawn_log_context_utils",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/analysis:symlink_entry",
        "//src/main/java/com/google/devtools/build/lib/analysis/platform:platform_utils",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/remote/util:digest_utils",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:spawn_java_proto",
        "//third_party:fastutil",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
        "@zstd-jni",
    ],
)

java_library(
    name = "spawn_log_context_utils",
    srcs = [
        "SpawnLogReconstructor.java",
        "StableSort.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/protobuf:spawn_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@zstd-jni",
    ],
)

java_library(
    name = "spawn_runner",
    srcs = [
        "SpawnCheckingCacheEvent.java",
        "SpawnExecutingEvent.java",
        "SpawnProgressEvent.java",
        "SpawnRunner.java",
        "SpawnSchedulingEvent.java",
    ],
    deps = [
        ":spawn_input_expander",
        ":tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:spawn_java_proto",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "spawn_strategy_policy",
    srcs = ["SpawnStrategyPolicy.java"],
    deps = [
        "//src/main/protobuf:strategy_policy_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "spawn_strategy_registry",
    srcs = ["SpawnStrategyRegistry.java"],
    deps = [
        ":abstract_spawn_strategy",
        ":remote_local_fallback_registry",
        ":spawn_strategy_policy",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:strategy_policy_java_proto",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "spawn_strategy_resolver",
    srcs = ["SpawnStrategyResolver.java"],
    deps = [
        ":spawn_strategy_registry",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "standalone_test_result",
    srcs = ["StandaloneTestResult.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "standalone_test_strategy",
    srcs = ["StandaloneTestStrategy.java"],
    deps = [
        ":bin_tools",
        ":execution_options",
        ":spawn_exec_exception",
        ":spawn_strategy_resolver",
        ":standalone_test_result",
        ":test_policy",
        "//src/main/java/com/google/devtools/build/lib:runtime/test_summary_options",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "streamed_test_output",
    srcs = ["StreamedTestOutput.java"],
    deps = [
        ":test_log_helper",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
    ],
)

java_library(
    name = "symlink_tree_helper",
    srcs = ["SymlinkTreeHelper.java"],
    deps = [
        ":bin_tools",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_symlink",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:command",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "symlink_tree_strategy",
    srcs = ["SymlinkTreeStrategy.java"],
    deps = [
        ":bin_tools",
        ":symlink_tree_helper",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:fileset_output_symlink",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:output_service",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_log_helper",
    srcs = ["TestLogHelper.java"],
    deps = [
        ":execution_options",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_policy",
    srcs = ["TestPolicy.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
    ],
)

java_library(
    name = "test_xml_output_parser",
    srcs = ["TestXmlOutputParser.java"],
    deps = [
        ":test_xml_output_parser_exception",
        "//src/main/protobuf:test_status_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "test_xml_output_parser_exception",
    srcs = ["TestXmlOutputParserException.java"],
)

java_library(
    name = "tree_deleter",
    srcs = ["TreeDeleter.java"],
    deps = ["//src/main/java/com/google/devtools/build/lib/vfs"],
)
