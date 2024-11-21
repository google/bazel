load("@com_google_protobuf//bazel:java_proto_library.bzl", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("@rules_java//java:defs.bzl", "java_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

# Description:
#   Debugging helpers and modules
package(
    default_applicable_licenses = ["//:license"],
    default_visibility =
        ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

proto_library(
    name = "workspace_log_proto",
    srcs = ["workspace_log.proto"],
)

java_proto_library(
    name = "workspace_log_java_proto",
    deps = [":workspace_log_proto"],
)

java_library_srcs(
    name = "workspace_log_java_proto_srcs",
    deps = [":workspace_log_java_proto"],
)

filegroup(
    name = "dist_jars",
    srcs = [":workspace_log_java_proto_srcs"],
)

java_library(
    name = "workspace-rule-event",
    srcs = ["WorkspaceRuleEvent.java"],
    deps = [
        ":workspace_log_java_proto",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/net/starlark/java/syntax",
    ],
)

java_library(
    name = "workspace-rule-module",
    srcs = ["WorkspaceRuleModule.java"],
    deps = [
        ":debugging-options",
        ":workspace-rule-event",
        ":workspace_log_java_proto",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "debugging-options",
    srcs = ["DebuggingOptions.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
    ],
)
