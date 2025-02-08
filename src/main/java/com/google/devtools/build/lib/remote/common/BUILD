load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "bulk_transfer_exception",
    srcs = ["BulkTransferException.java"],
    deps = [
        ":cache_not_found_exception",
        "//third_party:guava",
    ],
)

java_library(
    name = "cache_not_found_exception",
    srcs = ["CacheNotFoundException.java"],
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)

java_library(
    name = "lost_inputs_event",
    srcs = ["LostInputsEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)

java_library(
    name = "common",
    srcs = glob(
        ["*.java"],
        exclude = [
            "BulkTransferException.java",
            "CacheNotFoundException.java",
            "LostInputsEvent.java",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_input_expander",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
        "@googleapis//google/longrunning:longrunning_java_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)
