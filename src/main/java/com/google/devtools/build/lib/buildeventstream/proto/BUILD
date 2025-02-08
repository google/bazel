load("@com_google_protobuf//bazel:java_proto_library.bzl", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/com/google/devtools/build/lib:__pkg__"],
)

java_library_srcs(
    name = "dist_jars",
    deps = [":build_event_stream_java_proto"],
)

java_proto_library(
    name = "build_event_stream_java_proto",
    deps = ["build_event_stream_proto"],
)

proto_library(
    name = "build_event_stream_proto",
    srcs = ["build_event_stream.proto"],
    visibility = ["//visibility:public"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/packages/metrics:package_load_metrics_proto",
        "//src/main/protobuf:action_cache_proto",
        "//src/main/protobuf:command_line_proto",
        "//src/main/protobuf:failure_details_proto",
        "//src/main/protobuf:invocation_policy_proto",
        "@com_google_protobuf//:any_proto",
        "@com_google_protobuf//:duration_proto",
        "@com_google_protobuf//:timestamp_proto",
    ],
)
