load("@com_google_protobuf//bazel:java_proto_library.bzl", "java_proto_library")
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

package(default_visibility = ["//src:__pkg__"])

proto_library(
    name = "action_rewind_event",
    srcs = ["action_rewind_event.proto"],
)

java_proto_library(
    name = "action_rewind_event_java_proto",
    visibility = [
        "//src/main/java/com/google/devtools/build/lib:__subpackages__",
        "//src/test/java/com/google/devtools/build/lib/testutil:__pkg__",
    ],
    deps = [":action_rewind_event"],
)

java_library_srcs(
    name = "dist_jars",
    deps = [":action_rewind_event_java_proto"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/com/google/devtools/build/lib:__subpackages__"],
)
