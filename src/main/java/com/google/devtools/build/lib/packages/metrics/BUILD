load("@com_google_protobuf//bazel:java_proto_library.bzl", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("@rules_java//java:defs.bzl", "java_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//visibility:public"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "metrics",
    srcs = glob(["*.java"]),
    deps = [
        ":package_load_metrics_java_proto",
        ":package_metrics_java_proto",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect:extrema",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party/protobuf:protobuf_java",
        "//third_party/protobuf:protobuf_java_util",
    ],
)

proto_library(
    name = "package_metrics_proto",
    srcs = ["package_metrics.proto"],
    deps = [
        "@com_google_protobuf//:duration_proto",
    ],
)

java_proto_library(
    name = "package_metrics_java_proto",
    deps = [
        ":package_metrics_proto",
    ],
)

java_library_srcs(
    name = "package_metrics_java_proto_srcs",
    deps = [":package_metrics_java_proto"],
)

proto_library(
    name = "package_load_metrics_proto",
    srcs = ["package_load_metrics.proto"],
    deps = [
        "@com_google_protobuf//:duration_proto",
    ],
)

java_proto_library(
    name = "package_load_metrics_java_proto",
    deps = [
        ":package_load_metrics_proto",
    ],
)

java_library_srcs(
    name = "package_load_metrics_java_proto_srcs",
    deps = [":package_load_metrics_java_proto"],
)

filegroup(
    name = "dist_jars",
    srcs = [
        ":package_load_metrics_java_proto_srcs",
        ":package_metrics_java_proto_srcs",
    ],
)
