load("@rules_java//java:defs.bzl", "java_library")

# Description:
#   A devtools-common options parser.
#   Open-sourced as part of Bazel.
package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/common/options/processor:srcs",
        "//src/main/java/com/google/devtools/common/options/testing:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "invocation_policy",
    srcs = [
        "InvocationPolicyEnforcer.java",
        "InvocationPolicyParser.java",
    ],
    deps = [
        ":options_internal",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "options",
    exported_plugins = ["//src/main/java/com/google/devtools/common/options/processor:options_preprocessor"],
    exports = [":options_internal"],
)

java_library(
    name = "options_internal",
    srcs = glob(
        ["*.java"],
        exclude = [
            "InvocationPolicyEnforcer.java",
            "InvocationPolicyParser.java",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:pair",
        "//src/main/java/net/starlark/java/spelling",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
