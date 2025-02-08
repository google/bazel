load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
load("@rules_java//java:defs.bzl", "java_library")
load("//tools/build_rules:utilities.bzl", "java_library_srcs")

# Description:
#   Skyframe Java code
package(
    default_visibility = ["//src:__subpackages__"],
)

SKYFRAME_OBJECT_SRCS = [
    "AbstractSkyKey.java",
    "FunctionHermeticity.java",
    "GroupedDeps.java",
    "IntVersion.java",
    "NodeVersion.java",
    "NotComparableSkyValue.java",
    "SkyframeLookupResult.java",
    "SkyFunction.java",
    "SkyFunctionException.java",
    "SkyFunctionName.java",
    "SkyKey.java",
    "SkyValue.java",
    "Version.java",
]

java_library(
    name = "skyframe-objects",
    srcs = SKYFRAME_OBJECT_SRCS,
    visibility = ["//visibility:public"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//third_party:auto_value",
        "//third_party:checker_framework_annotations",
        "//third_party:guava",
    ],
)

java_library(
    name = "skyframe",
    srcs = glob(
        ["**/*.java"],
        exclude = SKYFRAME_OBJECT_SRCS + ["CPUHeavySkyKey.java"],
    ),
    deps = [
        ":cpu_heavy_skykey",
        ":graph_inconsistency_java_proto",
        ":skyframe-objects",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/clock",  # keep
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashmap",
        "//src/main/java/com/google/devtools/build/lib/collect/compacthashset",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/skyframe:package_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/supplier",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:TestType",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "skyframe_graph_stats_event",
    srcs = ["SkyframeGraphStatsEvent.java"],
    deps = [
        ":skyframe-objects",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//third_party:guava",
    ],
)

java_library(
    name = "cpu_heavy_skykey",
    srcs = ["CPUHeavySkyKey.java"],
    visibility = ["//visibility:public"],
    deps = ["skyframe-objects"],
)

java_library(
    name = "execution_phase_skykey",
    srcs = ["ExecutionPhaseSkyKey.java"],
    visibility = ["//visibility:public"],
    deps = [":skyframe-objects"],
)

java_library(
    name = "stallable_skykey",
    srcs = ["StallableSkykey.java"],
    visibility = ["//visibility:public"],
    deps = [":skyframe-objects"],
)

proto_library(
    name = "graph_inconsistency",
    srcs = ["graph_inconsistency.proto"],
)

java_proto_library(
    name = "graph_inconsistency_java_proto",
    deps = [":graph_inconsistency"],
)

java_library_srcs(
    name = "dist_jars",
    deps = [":graph_inconsistency_java_proto"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)
