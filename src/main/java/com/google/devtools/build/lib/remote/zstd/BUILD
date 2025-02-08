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
    name = "zstd",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
        "@zstd-jni",
    ],
)
