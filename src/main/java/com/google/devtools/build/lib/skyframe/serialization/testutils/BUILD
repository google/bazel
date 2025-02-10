load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

DUMPER_SOURCES = [
    "Canonizer.java",
    "Dumper.java",
    "FieldInfoCache.java",
    "IsomorphismKey.java",
    "GraphDataCollector.java",
    "GraphTraverser.java",
]

java_library(
    name = "testutils",
    testonly = 1,
    srcs = glob(
        ["*.java"],
        exclude = DUMPER_SOURCES + [
            "FakeDirectories.java",
            "FakeInvalidationDataHelper.java",
            "RoundTripping.java",
            "SerializationDepsUtils.java",
        ],
    ),
    runtime_deps = [
        "//src/test/java/com/google/devtools/build/lib/vfs/util",  # For FileSystem modules
    ],
    deps = [
        ":round-tripping",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "round-tripping",
    srcs = ["RoundTripping.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "depsutils",
    srcs = ["SerializationDepsUtils.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//third_party:guava",
    ],
)

java_library(
    name = "fake_directories",
    testonly = 1,
    srcs = ["FakeDirectories.java"],
    deps = [
        ":testutils",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:server_directories",
        "//src/main/java/com/google/devtools/build/lib/vfs",
    ],
)

java_library(
    name = "dumper",
    srcs = DUMPER_SOURCES,
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "fake_invalidation_data_helper",
    srcs = ["FakeInvalidationDataHelper.java"],
    deps = [
        "//src/main/protobuf:file_invalidation_data_java_proto",
        "@com_google_protobuf//:protobuf_java",
    ],
)
