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
    name = "constants",
    srcs = ["PlatformConstants.java"],
)

# Description:
#   Providers defined for platforms, constraints, and toolchains.
java_library(
    name = "platform",
    srcs = [
        "ConstraintCollection.java",
        "ConstraintSettingInfo.java",
        "ConstraintValueInfo.java",
        "DeclaredToolchainInfo.java",
        "PlatformInfo.java",
        "PlatformProperties.java",
        "PlatformValue.java",
        "ToolchainInfo.java",
        "ToolchainTypeInfo.java",
    ],
    deps = [
        ":constants",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/config_matching_provider",
        "//src/main/java/com/google/devtools/build/lib/analysis:resolved_toolchain_data",
        "//src/main/java/com/google/devtools/build/lib/analysis:transitive_info_provider",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/platform",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "platform_function",
    srcs = ["PlatformFunction.java"],
    deps = [
        ":platform",
        ":utils",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/common_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target_value",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/skyframe:configured_target_key",
        "//src/main/java/com/google/devtools/build/lib/skyframe:configured_value_creation_exception",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:repository_mapping_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/toolchains:platform_lookup_util",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "platform_utils",
    srcs = ["PlatformUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_java_proto",
    ],
)

java_library(
    name = "utils",
    srcs = ["PlatformProviderUtils.java"],
    deps = [
        ":platform",
        "//src/main/java/com/google/devtools/build/lib/analysis:provider_collection",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
