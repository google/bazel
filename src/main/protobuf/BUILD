load("@com_google_protobuf//bazel:cc_proto_library.bzl", "cc_proto_library")
load("@com_google_protobuf//bazel:java_proto_library.bzl", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("@com_google_protobuf//bazel:py_proto_library.bzl", "py_proto_library")
load("//third_party/grpc:build_defs.bzl", "java_grpc_library")
load("//third_party/grpc/bazel:cc_grpc_library.bzl", "cc_grpc_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

package(default_visibility = ["//visibility:public"])

FILES = [
    "action_cache",
    "bazel_flags",
    "builtin",
    "crash_debugging",
    "crosstool_config",
    "deps",
    "desugar_deps",
    "execution_statistics",
    "extra_actions_base",
    "java_compilation",
    "memory_pressure",
    "strategy_policy",
    "test_status",
    "worker_protocol",
    "execution_graph",
    "file_invalidation_data",
]

[proto_library(
    name = s + "_proto",
    srcs = [s + ".proto"],
) for s in FILES]

[java_proto_library(
    name = s + "_java_proto",
    deps = [":" + s + "_proto"],
) for s in FILES]

[java_library_srcs(
    name = s + "_java_proto_srcs",
    deps = [":" + s + "_java_proto"],
) for s in FILES]

proto_library(
    name = "build_proto",
    srcs = ["build.proto"],
    deps = [":stardoc_output_proto"],
)

java_proto_library(
    name = "build_java_proto",
    deps = [":build_proto"],
)

java_library_srcs(
    name = "build_java_proto_srcs",
    deps = [":build_java_proto"],
)

proto_library(
    name = "analysis_v2_proto",
    srcs = ["analysis_v2.proto"],
    deps = [":build_proto"],
)

py_proto_library(
    name = "analysis_v2_py_proto",
    deps = [":analysis_v2_proto"],
)

java_proto_library(
    name = "analysis_v2_java_proto",
    deps = [":analysis_v2_proto"],
)

java_library_srcs(
    name = "analysis_v2_java_proto_srcs",
    deps = [":analysis_v2_java_proto"],
)

java_proto_library(
    name = "any_java_proto",
    deps = ["@com_google_protobuf//:any_proto"],
)

java_proto_library(
    name = "wrappers_java_proto",
    deps = ["@com_google_protobuf//:wrappers_proto"],
)

proto_library(
    name = "command_server_proto",
    srcs = ["command_server.proto"],
    deps = [
        ":failure_details_proto",
        "@com_google_protobuf//:any_proto",
    ],
)

java_proto_library(
    name = "command_server_java_proto",
    deps = [":command_server_proto"],
)

java_library_srcs(
    name = "command_server_java_proto_srcs",
    deps = [":command_server_java_proto"],
)

proto_library(
    name = "failure_details_proto",
    srcs = ["failure_details.proto"],
    deps = ["@com_google_protobuf//:descriptor_proto"],
)

java_proto_library(
    name = "failure_details_java_proto",
    deps = [":failure_details_proto"],
)

java_library_srcs(
    name = "failure_details_java_proto_srcs",
    deps = [":failure_details_java_proto"],
)

proto_library(
    name = "invocation_policy_proto",
    srcs = ["invocation_policy.proto"],
    deps = [":strategy_policy_proto"],
)

java_proto_library(
    name = "invocation_policy_java_proto",
    deps = [":invocation_policy_proto"],
)

java_library_srcs(
    name = "invocation_policy_java_proto_srcs",
    deps = [":invocation_policy_java_proto"],
)

proto_library(
    name = "option_filters_proto",
    srcs = ["option_filters.proto"],
)

java_proto_library(
    name = "option_filters_java_proto",
    deps = [":option_filters_proto"],
)

java_library_srcs(
    name = "option_filters_java_proto_srcs",
    deps = [":option_filters_java_proto"],
)

proto_library(
    name = "command_line_proto",
    srcs = ["command_line.proto"],
    deps = [":option_filters_proto"],
)

java_proto_library(
    name = "command_line_java_proto",
    deps = [":command_line_proto"],
)

java_library_srcs(
    name = "command_line_java_proto_srcs",
    deps = [":command_line_java_proto"],
)

# Proto needed by singlejar and embedded into the java tools archive.
genrule(
    name = "desugar_deps_zip",
    srcs = ["desugar_deps.proto"],
    outs = ["desugar_deps.zip"],
    cmd = "zip -q $@ $<",
    visibility = ["//src/tools/singlejar:__pkg__"],
)

filegroup(
    name = "desugar_deps_filegroup",
    srcs = ["desugar_deps.proto"],
    visibility = ["//src/tools/singlejar:__pkg__"],
)

cc_proto_library(
    name = "desugar_deps_cc_proto",
    deps = [":desugar_deps_proto"],
)

cc_proto_library(
    name = "worker_protocol_cc_proto",
    deps = [":worker_protocol_proto"],
)

java_grpc_library(
    name = "command_server_java_grpc",
    srcs = [":command_server_proto"],
    deps = [":command_server_java_proto"],
)

cc_proto_library(
    name = "command_server_cc_proto",
    deps = [":command_server_proto"],
)

cc_grpc_library(
    name = "command_server_cc_grpc",
    srcs = [":command_server_proto"],
    extra_cc_library_kwargs = {"defines": ["GRPC_USE_PROTO_LITE=ON"]},
    deps = [
        ":command_server_cc_proto",
        "//third_party/grpc:grpc++_unsecure",
    ],
)

py_proto_library(
    name = "build_pb_py",
    deps = [":build_proto"],
)

java_library_srcs(
    name = "profile_java_proto_srcs",
    visibility = ["//visibility:private"],
    deps = ["//third_party/pprof:profile_java_proto"],
)

cc_proto_library(
    name = "execution_statistics_cc_proto",
    deps = [":execution_statistics_proto"],
)

proto_library(
    name = "remote_execution_log_proto",
    srcs = ["remote_execution_log.proto"],
    deps = [
        "@com_google_protobuf//:timestamp_proto",
        "@googleapis//google/bytestream:bytestream_proto",
        "@googleapis//google/longrunning:operations_proto",
        "@googleapis//google/rpc:status_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_proto",
    ],
)

java_proto_library(
    name = "remote_execution_log_java_proto",
    deps = [":remote_execution_log_proto"],
)

java_library_srcs(
    name = "remote_execution_log_java_proto_srcs",
    deps = [":remote_execution_log_java_proto"],
)

proto_library(
    name = "cache_salt_proto",
    srcs = ["cache_salt.proto"],
)

java_proto_library(
    name = "cache_salt_java_proto",
    deps = [":cache_salt_proto"],
)

java_library_srcs(
    name = "cache_salt_java_proto_srcs",
    deps = [":cache_salt_java_proto"],
)

proto_library(
    name = "remote_scrubbing_proto",
    srcs = ["remote_scrubbing.proto"],
)

java_proto_library(
    name = "remote_scrubbing_java_proto",
    deps = [":remote_scrubbing_proto"],
)

java_library_srcs(
    name = "remote_scrubbing_java_proto_srcs",
    deps = [":remote_scrubbing_java_proto"],
)

proto_library(
    name = "bazel_output_service_proto",
    srcs = ["bazel_output_service.proto"],
    deps = [
        "@com_google_protobuf//:any_proto",
        "@googleapis//google/rpc:status_proto",
    ],
)

java_proto_library(
    name = "bazel_output_service_java_proto",
    deps = [":bazel_output_service_proto"],
)

java_library_srcs(
    name = "bazel_output_service_java_proto_srcs",
    deps = [":bazel_output_service_java_proto"],
)

cc_proto_library(
    name = "bazel_output_service_cc_proto",
    deps = [":bazel_output_service_proto"],
)

proto_library(
    name = "bazel_output_service_rev2_proto",
    srcs = ["bazel_output_service_rev2.proto"],
    deps = [
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_proto",
    ],
)

java_proto_library(
    name = "bazel_output_service_rev2_java_proto",
    deps = [":bazel_output_service_rev2_proto"],
)

java_library_srcs(
    name = "bazel_output_service_rev2_java_proto_srcs",
    deps = [":bazel_output_service_rev2_java_proto"],
)

cc_proto_library(
    name = "bazel_output_service_rev2_cc_proto",
    deps = [":bazel_output_service_rev2_proto"],
)

java_grpc_library(
    name = "bazel_output_service_java_grpc",
    srcs = [":bazel_output_service_proto"],
    deps = [":bazel_output_service_java_proto"],
)

cc_grpc_library(
    name = "bazel_output_service_cc_grpc",
    srcs = [":bazel_output_service_proto"],
    deps = [":bazel_output_service_cc_proto"],
)

proto_library(
    name = "spawn_proto",
    srcs = ["spawn.proto"],
    deps = [
        "@com_google_protobuf//:duration_proto",
        "@com_google_protobuf//:timestamp_proto",
    ],
)

java_proto_library(
    name = "spawn_java_proto",
    deps = [":spawn_proto"],
)

java_library_srcs(
    name = "spawn_java_proto_srcs",
    deps = [":spawn_java_proto"],
)

# Note that stardoc_output_proto and stardoc_output_java_proto are vendored by
# Stardoc: https://github.com/bazelbuild/stardoc/tree/master/stardoc/proto
package_group(
    name = "stardoc_output_users",
    packages = [
        "//src/main/java/com/google/devtools/build/docgen/...",
        "//src/main/java/com/google/devtools/build/lib/rules/starlarkdocextract/...",
        "//src/main/java/com/google/devtools/build/lib/starlarkdocextract/...",
        "//src/test/java/com/google/devtools/build/docgen/...",
        "//src/test/java/com/google/devtools/build/lib/rules/starlarkdocextract/...",
        "//src/test/java/com/google/devtools/build/lib/starlarkdocextract/...",
    ],
)

proto_library(
    name = "stardoc_output_proto",
    srcs = ["stardoc_output.proto"],
    visibility = [":stardoc_output_users"],
)

java_proto_library(
    name = "stardoc_output_java_proto",
    visibility = [":stardoc_output_users"],
    deps = [":stardoc_output_proto"],
)

java_library_srcs(
    name = "stardoc_output_java_proto_srcs",
    visibility = [":stardoc_output_users"],
    deps = [":stardoc_output_java_proto"],
)

proto_library(
    name = "xcode_proto",
    srcs = ["xcode_config.proto"],
    visibility = ["//visibility:public"],
)

java_proto_library(
    name = "xcode_java_proto",
    deps = [":xcode_proto"],
)

cc_proto_library(
    name = "xcode_cc_proto",
    deps = [":xcode_proto"],
)

java_library_srcs(
    name = "xcode_java_proto_srcs",
    deps = [":xcode_java_proto"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

filegroup(
    name = "dist_jars",
    srcs = [s + "_java_proto_srcs" for s in FILES] + [
        ":analysis_v2_java_proto_srcs",
        ":bazel_output_service_java_grpc_srcs",
        ":bazel_output_service_java_proto_srcs",
        ":bazel_output_service_rev2_java_proto_srcs",
        ":build_java_proto_srcs",
        ":cache_salt_java_proto_srcs",
        ":command_line_java_proto_srcs",
        ":command_server_java_grpc_srcs",
        ":command_server_java_proto_srcs",
        ":failure_details_java_proto_srcs",
        ":invocation_policy_java_proto_srcs",
        ":option_filters_java_proto_srcs",
        ":profile_java_proto_srcs",
        ":remote_execution_log_java_proto_srcs",
        ":remote_scrubbing_java_proto_srcs",
        ":spawn_java_proto_srcs",
        ":stardoc_output_java_proto_srcs",
        ":xcode_java_proto_srcs",
    ],
)
