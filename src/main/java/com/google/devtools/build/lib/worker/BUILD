load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "worker_protocol",
    srcs = [
        "JsonWorkerProtocol.java",
        "ProtoWorkerProtocol.java",
        "WorkerProtocolImpl.java",
    ],
    deps = [
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:gson",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "worker_files_hash",
    srcs = ["WorkerFilesHash.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/unsafe:string",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker_spawn_runner",
    srcs = [
        "WorkerLifecycleManager.java",
        "WorkerParser.java",
        "WorkerSpawnRunner.java",
    ],
    deps = [
        ":error_message",
        ":worker",
        ":worker_files_hash",
        ":worker_key",
        ":worker_options",
        ":worker_pool",
        ":worker_process_metrics",
        ":worker_process_status",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:action_input_helper",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/actions:file_metadata",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/build/lib/actions:resource_manager",
        "//src/main/java/com/google/devtools/build/lib/actions:runfiles_tree",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec:runfiles_tree_updater",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/exec/local",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "worker_module",
    srcs = [
        "WorkerModule.java",
        "WorkerSpawnStrategy.java",
    ],
    deps = [
        ":multiplex_worker",
        ":singleplex_worker",
        ":worker_factory",
        ":worker_options",
        ":worker_pool",
        ":worker_pool_config",
        ":worker_pool_impl",
        ":worker_process_metrics",
        ":worker_spawn_runner",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:abstract_spawn_strategy",
        "//src/main/java/com/google/devtools/build/lib/exec:execution_options",
        "//src/main/java/com/google/devtools/build/lib/exec:runfiles_tree_updater",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_strategy_registry",
        "//src/main/java/com/google/devtools/build/lib/exec/local",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands/events",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_util",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_options",
        "//src/main/java/com/google/devtools/build/lib/sandbox:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox/cgroups",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "work_request_handlers",
    srcs = [
        "JsonWorkerMessageProcessor.java",
        "ProtoWorkerMessageProcessor.java",
        "WorkRequestHandler.java",
    ],
    deps = [
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:error_prone_annotations",
        "//third_party:gson",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "worker_key",
    srcs = [
        "WorkerKey.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/util:command",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
    ],
)

java_library(
    name = "worker_process_metrics",
    srcs = [
        "WorkerProcessMetrics.java",
        "WorkerProcessMetricsCollector.java",
    ],
    deps = [
        ":worker_process_status",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/metrics:resource_collector",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker_options",
    srcs = ["WorkerOptions.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:ram_resource_converter",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:guava",
    ],
)

java_library(
    name = "worker_factory",
    srcs = [
        "WorkerFactory.java",
    ],
    deps = [
        ":error_message",
        ":multiplex_worker",
        ":singleplex_worker",
        ":worker",
        ":worker_key",
        ":worker_options",
        ":worker_process_status",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/sandbox:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox/cgroups",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker_pool",
    srcs = [
        "WorkerPool.java",
    ],
    deps = [
        ":worker",
        ":worker_key",
        "//third_party:guava",
    ],
)

java_library(
    name = "worker_pool_config",
    srcs = [
        "WorkerPoolConfig.java",
    ],
)

java_library(
    name = "worker_pool_impl",
    srcs = [
        "WorkerPoolImpl.java",
    ],
    deps = [
        ":worker",
        ":worker_factory",
        ":worker_key",
        ":worker_pool",
        ":worker_pool_config",
        ":worker_process_status",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker",
    srcs = ["Worker.java"],
    deps = [
        ":worker_key",
        ":worker_process_status",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker_process_status",
    srcs = ["WorkerProcessStatus.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
    ],
)

java_library(
    name = "singleplex_worker",
    srcs = [
        "SandboxedWorker.java",
        "SingleplexWorker.java",
    ],
    deps = [
        ":recording_input_stream",
        ":worker",
        ":worker_exec_root",
        ":worker_key",
        ":worker_options",
        ":worker_process_status",
        ":worker_protocol",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/exec:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_command_line_builder",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_util",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/sandbox/cgroups",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "multiplex_worker",
    srcs = [
        "SandboxedWorkerProxy.java",
        "WorkerMultiplexer.java",
        "WorkerMultiplexerManager.java",
        "WorkerProxy.java",
    ],
    deps = [
        ":recording_input_stream",
        ":worker",
        ":worker_exec_root",
        ":worker_key",
        ":worker_process_status",
        ":worker_protocol",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "worker_exec_root",
    srcs = ["WorkerExecRoot.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/exec:tree_deleter",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_helpers",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "error_message",
    srcs = ["ErrorMessage.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "recording_input_stream",
    srcs = ["RecordingInputStream.java"],
    deps = ["//third_party:guava"],
)
