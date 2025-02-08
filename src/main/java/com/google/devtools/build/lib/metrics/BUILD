load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]) + [
        "//src/main/java/com/google/devtools/build/lib/metrics/criticalpath:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "event",
    srcs = ["BuildMetricsEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/buildeventstream",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "metrics_module",
    srcs = glob(
        ["*.java"],
        exclude = [
            "BuildMetricsEvent.java",
            "PostGCMemoryUseRecorder.java",
        ],
    ),
    deps = [
        ":event",
        ":memory-use-recorder",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:analysis_graph_stats_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_complete_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_phase_started_event",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/dynamic",
        "//src/main/java/com/google/devtools/build/lib/packages/metrics",
        "//src/main/java/com/google/devtools/build/lib/packages/metrics:package_load_metrics_java_proto",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/profiler:network_metrics_collector",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//src/main/java/com/google/devtools/build/lib/skyframe:execution_finished_event",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_key_stats",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_stats",
        "//src/main/java/com/google/devtools/build/lib/skyframe:top_level_status_events",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_process_metrics",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_process_status",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe_graph_stats_event",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:flogger",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "memory-use-recorder",
    srcs = ["PostGCMemoryUseRecorder.java"],
    deps = [
        ":garbage-collection-metrics-util",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "garbage-collection-metrics-util",
    srcs = ["GarbageCollectionMetricsUtils.java"],
)

java_library(
    name = "resource_collector",
    srcs = [
        "CgroupsInfoCollector.java",
        "PsInfoCollector.java",
        "ResourceSnapshot.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/sandbox:cgroups_info",
        "//third_party:auto_value",
        "//third_party:error_prone_annotations",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)
