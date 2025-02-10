load("@rules_java//java:defs.bzl", "java_test")

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

java_test(
    name = "FlagSetsFunctionTest",
    srcs = ["FlagSetsFunctionTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime/config_flag_definitions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/skyframe:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ParsedFlagsValueTest",
    srcs = ["ParsedFlagsValueTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/analysis/config:ConfigTests_lib",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PlatformMappingFunctionParserTest",
    srcs = ["PlatformMappingFunctionParserTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:exceptions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/skyframe:testutil",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PlatformMappingFunctionTest",
    srcs = ["PlatformMappingFunctionTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_options",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:exceptions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config:platform_mapping_key",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/common/options",
        "//src/test/java/com/google/devtools/build/lib/analysis/util",
        "//src/test/java/com/google/devtools/build/lib/skyframe:testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "PlatformMappingValueTest",
    srcs = ["PlatformMappingValueTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_options",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skyframe/config",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)
