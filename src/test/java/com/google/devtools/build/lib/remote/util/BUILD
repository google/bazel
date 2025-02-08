load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

INTEGRATION_TEST_UTILS_SRCS = ["IntegrationTestUtils.java"]

FREE_PORT_FINDER_SRCS = ["FreePortFinder.java"]

java_library(
    name = "util",
    srcs = glob(
        ["*.java"],
        exclude = INTEGRATION_TEST_UTILS_SRCS + FREE_PORT_FINDER_SRCS,
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifact_expander",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/actions:forbidden_action_input_exception",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_input_expander",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_runner",
        "//src/main/java/com/google/devtools/build/lib/remote",
        "//src/main/java/com/google/devtools/build/lib/remote/common",
        "//src/main/java/com/google/devtools/build/lib/remote/common:bulk_transfer_exception",
        "//src/main/java/com/google/devtools/build/lib/remote/common:cache_not_found_exception",
        "//src/main/java/com/google/devtools/build/lib/remote/util",
        "//src/main/java/com/google/devtools/build/lib/util/io",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:spawn_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:rxjava3",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
        "@googleapis//google/bytestream:bytestream_java_grpc",
        "@googleapis//google/bytestream:bytestream_java_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)

java_library(
    name = "free_port_finder",
    srcs = FREE_PORT_FINDER_SRCS,
)

java_library(
    name = "integration_test_utils",
    srcs = INTEGRATION_TEST_UTILS_SRCS,
    data = [
        "//src/tools/remote:worker",
        "//src/tools/remote:worker_deploy.jar",
    ],
    deps = [
        ":free_port_finder",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtils",
        "//third_party:guava",
        "//third_party:jsr305",
        "@rules_java//java/runfiles",
    ],
)
