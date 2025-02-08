load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "mock",
    srcs = glob([
        "*.java",
    ]),
    data = [
        "//tools/jdk:srcs",
        "@bazel_skylib//:test_deps",
        "@com_google_protobuf//bazel/private:for_bazel_tests",
        "@rules_cc//cc:srcs",
        "@rules_cc//cc/common:srcs",
        "@rules_cc//cc/toolchains:srcs",
        "@rules_java//java:for_bazel_tests",
        "@rules_java//toolchains:srcs",
        "@rules_python//python:defs_bzl",
    ],
    resources = [
        "cc_toolchain_config.bzl",
        "//tools/build_defs/cc:action_names.bzl",
        "//tools/cpp:cc_toolchain_config_lib.bzl",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/bazel:repository_module",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/test/java/com/google/devtools/build/lib/analysis/util:util_internal",
        "//src/test/java/com/google/devtools/build/lib/packages:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestConstants",
        "//third_party:guava",
        "@rules_java//java/runfiles",
    ],
)

exports_files(["cc_toolchain_config.bzl"])
