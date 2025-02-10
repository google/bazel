load("@bazel_skylib//rules:copy_file.bzl", "copy_file")
load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/tools/remote/src/main/java/com/google/devtools/build/remote/worker/http:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

copy_file(
    name = "xcode_locator",
    src = "//tools/osx:xcode-locator",
    out = "xcode-locator",
)

java_library(
    name = "worker",
    srcs = glob(["*.java"]),
    data = [
        ":xcode_locator",
    ],
    resources = ["//src/main/tools:linux-sandbox"],
    visibility = ["//src/tools/remote:__subpackages__"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:bin_tools",
        "//src/main/java/com/google/devtools/build/lib/exec/local",
        "//src/main/java/com/google/devtools/build/lib/remote",
        "//src/main/java/com/google/devtools/build/lib/remote:store",
        "//src/main/java/com/google/devtools/build/lib/remote/common",
        "//src/main/java/com/google/devtools/build/lib/remote/common:cache_not_found_exception",
        "//src/main/java/com/google/devtools/build/lib/remote/disk",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/remote/util",
        "//src/main/java/com/google/devtools/build/lib/remote/util:digest_utils",
        "//src/main/java/com/google/devtools/build/lib/sandbox:linux_sandbox_command_line_builder",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:resource_converter",
        "//src/main/java/com/google/devtools/build/lib/util:single_line_formatter",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/tools/remote/src/main/java/com/google/devtools/build/remote/worker/http",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:netty",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
        "@googleapis//google/bytestream:bytestream_java_grpc",
        "@googleapis//google/bytestream:bytestream_java_proto",
        "@googleapis//google/longrunning:longrunning_java_proto",
        "@googleapis//google/rpc:rpc_java_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_grpc",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
        "@rules_java//java/runfiles",
    ],
)
